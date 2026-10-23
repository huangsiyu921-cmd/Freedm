# 已知坑与设计权衡

> `DOCUMENT-CLASS: INFORMATIVE`
> `BASELINE: SPEC-BASELINE-2026-10-23`
> `AUTHORITY: docs/spec/`（本文不产生规范约束）
> `LEGAL-STATUS: NOT_REVIEWED`

全部是本次实现/联调中**真实撞到**的问题，按"现象 → 原因 → 处理"记录。
写着是为了不再踩第二遍，不是为了凑篇幅。对应的规范性错误条件见
`docs/spec/errors.md`（`ERR-*`）；本文不替代错误条件定义。

---

## 1. 分片进度不落库，续传会回退到段首（最严重）

**现象**：进程被杀后重启，下载"续传"了，但服务器看到每段请求又从头开始，总下载量是文件的两倍。

**原因**：只把任务级总量 `download.downloaded` 写进了 DB，`segment.downloaded` 一直是 0。
续传时读的是 `segment.downloaded`，于是每段都从 `start_byte` 重新下。

**处理**：每个 segment 持有一个 `AtomicLong` 作为续传锚点，ticker 每 1 秒把**每段**的偏移写库：

```kotlin
val segProgress = plan.associateWith { AtomicLong(it.downloaded) }
// ticker 内
if (now - lastSegFlush >= 1000) {
    plan.forEach { seg ->
        Repo.markSegment(id, seg.index, segProgress.getValue(seg).get(), TaskState.RUNNING)
    }
}
```

**注意**：任务级与分片级刷新频率不同（500 ms vs 1000 ms），所以
`download.downloaded` 会比 `sum(segment.downloaded)` 略微超前——这是正常的，两者用途不同，
**续传只看 segment**。

---

## 2. 重试导致进度重复累加，进度条能超过 100%

**现象**：网络抖动后任务显示 120% 进度，`download.downloaded` 大于文件大小。

**原因**：重试时从段首重下，而进度计数器又把这段字节加了一遍：

```kotlin
// 错误写法
val from = seg.startByte + seg.downloaded   // seg 是快照，重试时不变
counter.addAndGet(delta)                     // 重复计数
```

**处理**：进度锚点必须跨重试保留，且只上报**真实新增**字节：

```kotlin
val from = seg.startByte + written.get()     // written 是 AtomicLong，跨重试单调递增
written.addAndGet(n.toLong())
onBytes(n.toLong())                          // 只在真正读到新数据时调用
```

---

## 3. 预分配文件使"文件大小"无法用于判断进度

**现象**：下载到 30% 时 `ls -l` 显示文件已经是完整大小。

**原因**：为了让各段能自由 `seek`，开头就 `raf.setLength(totalBytes)` 预分配了。

**处理**：判断进度只能看 DB 里的 `segment.downloaded` / `download.downloaded`。
想从文件系统侧看实际落盘量，用 `du`（块占用）而不是 `ls -l`（逻辑大小）。

---

## 4. 服务器忽略 `Range` 时必须报错，不能照写

**现象**：某些 CDN 对带 `Range` 的请求返回 `200` + 完整 body。

**风险**：若把完整 body 写到 `start_byte + 已下载` 的偏移处，文件会被**静默写坏**，
而且大小检查还能通过（因为预分配过）。

**处理**：只要是"续传请求"（起点不等于段首），就要求响应必须是 `206`，否则抛错重试：

```kotlin
if (rangedRequest && from > seg.startByte && code != 206) {
    throw IOException("服务器未按 Range 返回（HTTP $code）")
}
```

---

## 5. 短读不会抛异常，必须自己判长度

**现象**：连接被中间设备掐断，`InputStream.read` 正常返回 -1，没有异常。

**处理**：读完一段后校验实际字节数，短了就当成失败丢给重试循环：

```kotlin
if (expected >= 0 && written.get() < expected) {
    throw IOException("连接提前结束（${written.get()}/$expected）")
}
```

---

## 6. `HEAD` 不一定被支持，探测要能回退

**现象**：部分静态托管/CDN 直接拒绝 `HEAD`（返回 403/405）。

**处理**：`HEAD` 失败时退回 `GET` + `Range: bytes=0-0`，并从 `Content-Range`
的 `bytes 0-0/TOTAL` 里取总大小：

```kotlin
val total = contentRange.substringAfterLast('/', "-").trim().toLongOrNull() ?: -1L
```

---

## 7. 续传校验用 `If-Range` + `ETag`，不要用时间戳

**处理**：首次探测记下 `ETag`（优先）/ `Last-Modified`；之后每次请求带
`If-Range: <ETag>`。服务器在资源变化时会直接回 `200` 全量，配合第 4 条就能安全丢弃旧数据。
另外启动时若发现 `ETag`/总大小与落库不一致，主动删文件重建分片计划。

---

## 8. `RandomAccessFile` 的并发写必须加锁

多个段协程共享同一个 `RandomAccessFile`。`seek` + `write` 不是原子操作，
不加锁会互相踩偏移：

```kotlin
synchronized(raf) {
    raf.seek(pos)
    raf.write(buf, 0, n)
}
```

（锁的粒度是写一次 64 KiB，实测 6 并发段 + 50 MB 文件 sha256 完全正确。）

---

## 9. `RUNNING` 与 `PAUSED` 必须能区分，否则自动恢复会误伤

**原因**：如果用同一个状态表示"没在跑"，重启后就无法判断该不该自动继续。

**处理**：
- 进程被杀 → 任务仍留在 `RUNNING`
- 用户主动暂停 → `PAUSED`
- 启动时 `UPDATE ... SET state='QUEUED' WHERE state='RUNNING'`

这样"自动恢复"只恢复前者。

---

## 10. `MediaStore` 不能随机访问 → 只能"先下后导出"

**原因**：分段下载需要 `RandomAccessFile.seek`，而 MediaStore 只提供顺序输出流。

**处理**：数据一律先落在应用私有目录，完成后**复制**到 `MediaStore.Downloads`
（写入用 `IS_PENDING=1` → 写完置 0），导出成功才删私有副本。
失败时保留私有副本，任务仍可续传。

---

## 11. 剪贴板只有前台能读

**现象**：`getPrimaryClip()` 在后台抛 `SecurityException`（Android 10+）。

**处理**：`ClipboardWatcher` 只在 `onResume` 注册、`onPause` 注销，读取整体包在
`runCatching` 里。默认关闭，用户开启才注册。

---

## 12. 用 PowerShell 从设备拉二进制会被改坏

**现象**：`adb shell run-as ... cat file.db | Out-File db.bin` 得到的文件
`sqlite3 connect` 报 `file is not a database`（只剩 51 字节）。

**原因**：PowerShell 的文本管道会按字符集转码。

**处理**：走 `exec-out` 并交给 `cmd` 做字节重定向：

```powershell
cmd /c "adb -s emulator-5556 exec-out run-as app.freedm cat databases/downloads.db > db.bin"
```

顺带一个坑：`SQLiteOpenHelper` 的库在 **`databases/`** 目录，不是 `files/`。

---

## 13. 直接改 `shared_prefs` 必须 `force-stop`

SharedPreferences 在进程启动时读入内存并缓存，改完文件不重启进程不会生效。
脚本化改设置时顺序必须是：`force-stop` → 改文件 → `am start`。

---

## 14. 明文 HTTP 在模拟器上的处理

Android 默认禁明文，而宿主机测试源是 `http://10.0.2.2:8000`。

**处理**：只在 **debug** source set 放 `network_security_config`，且白名单只有
`10.0.2.2 / localhost / 127.0.0.1`；release 保持 `cleartextTrafficPermitted=false`。
不要把这条放宽到 release。

---

## 15. `WebView.evaluateJavascript` 的返回值是"二次编码"的

**现象**：注入脚本里 `return JSON.stringify(list)`，Kotlin 侧拿到的是 `"\"[{\\\"u\\\":...}]\""`
——一个 JSON 字符串字面量，直接 `JSONArray(raw)` 会解析失败。

**原因**：`evaluateJavascript` 把 JS 返回值再 JSON 编码一次传给 `ValueCallback`。

**处理**：先当 JSON 字符串解一层，再解数组：

```kotlin
val inner = if (raw.startsWith("\"")) JSONObject("{\"v\":$raw}").getString("v") else raw
val arr = JSONArray(inner)
```

---

## 16. 导出到公共目录后 `filePath` 会失效

**现象**：开启"保存到公共下载目录"后，已完成列表里所有文件显示 `0 B`。

**原因**：`Exporter` 成功后删掉了私有副本，而 DB 里的 `dir_path/file_name` 仍指向它。
`File.length()` 对不存在的文件返回 0 且**不抛异常**，所以 `runCatching` 兜不住。

**处理**：读取大小时先判 `exists()`：

```kotlin
val size = if (f.exists()) f.length() else t.totalBytes
```

**更彻底的做法**（未实现）：导出成功后把新的 `content://` URI 记进 DB，列表与打开都走它。

---

## 17. 删除运行中的任务，它还会"诈尸"

**现象**：任务跑着的时候点删除，行没了，但下载仍在进行；有时过几秒又弹出「下载完成」通知。

**原因**：`Repo.delete` 只删数据库行，`Engine` 的段线程手里还握着 `RandomAccessFile`，
照旧往已被删掉的文件 inode 里写；收尾时 `setState(COMPLETED)` 更新 0 行、但通知照发。

**处理**：删除前先让引擎放弃该任务，且引擎的收尾路径先查记录是否还在：

```kotlin
Engine.forget(t.id)                  // 置暂停标记 + cancel，不发通知
Repo.delete(t.id, deleteFiles = true)

// Engine.start 的 catch 里 / runTask 完成前
if (Repo.task(id) == null) return
```

实测：20 MB 慢速任务在 5% 时删除，服务器两个段连接立即 `ConnectionAborted`，无崩溃、无残留行。

---

## 18. `pauseAll` 只停正在跑的，"全部暂停"看起来没反应

**原因**：只对 `jobs` 里的任务调 `pause`，队列里的 `QUEUED` 不动。第一个任务停下后
`pump()` 立刻把下一个拉起来，界面上就是"点了暂停，还在下"。

**处理**：`pauseAll` 里连同 `QUEUED` 一起置 `PAUSED`。

---

## 19. `CoroutineStart.LAZY` 的 job 取消后不会执行 finally

**现象**：偶发某个任务卡在 `QUEUED`，点开始也没用（`jobs.containsKey` 恒为 true）。

**原因**：`Engine.start` 先放进 map 再 `job.start()`。若在两者之间被取消，协程体和
`finally` 都不执行，map 里留下一条死记录。

**处理**：`pause` 里 `cancelAndJoin()` 之后用两参 `remove` 兜底：

```kotlin
if (jobs.remove(id, job)) { progressData.remove(id); publish(); Repo.setState(id, PAUSED); pump() }
```

---

## 20. `shouldInterceptRequest` 不是主线程

**现象**：长时间浏览后偶发状态异常 / 媒体列表越滚越多。

**原因**：这个回调跑在 WebView 自己的线程池里，直接 `mutableStateListOf.add()` 是跨线程写
Compose 状态；而且每个资源请求都塞一条，永不回收。

**处理**：只往 `ConcurrentLinkedQueue` 里放，主线程 500 ms 抽干合并，`MAX_SNIFFED = 400` 封顶，
`onPageStarted` 清空队列。广告拦截的判定同样只读 prefs，不碰 Compose 状态。

---

## 21. `readBytes()` 之后才判大小，等于没判

**现象**：抓 favicon 时遇到异常站点，`readBytes()` 已经把整个响应读进内存，之后那句
`if (bytes.size > 512 * 1024) return null` 只是事后诸葛亮。

**处理**：边读边计数，超限立刻放弃（`FaviconColor.MAX_ICON_BYTES = 512 KiB`）。

---

## 22. 数据库升级不能 `DROP TABLE`

**现象**：`onUpgrade` 是 `DROP TABLE …; onCreate(db)`，注释写着"未发布版本不写迁移"。

**风险**：项目已经发布了。哪天把 `Db.VERSION` 从 1 改成 2，用户所有下载记录**静默消失**，
不崩溃、不报错、无提示 —— 这是最难被发现的一类数据丢失。

**处理**：`onUpgrade`/`onDowngrade` 都改成空实现 + 注释模板，永不 DROP。真要加列时：

```kotlin
if (oldVersion < 2) db.execSQL("ALTER TABLE download ADD COLUMN <col> <type>")
// 同时在 onCreate 里镜像同一列，保证全新安装与升级安装 schema 一致
```

---

## 23. `HttpURLConnection` 不会跟随跨 host / 跨协议 302

**现象**：镜像站（如 `mirrors.aliyun.com`）返回 302，任务三次重试后 `TASK_FAIL`，日志只有
`IOException: HTTP 302`；探测还把 302 响应的 `Content-Length`（实测 215 B）当成文件大小。

**原因**：`setInstanceFollowRedirects(true)` 对跨协议（http↔https）与部分跨 host 重定向
**静默放弃**。

**处理**：`Prober.openFollowing()` 手工跟随（≤5 跳，`Location` 相对/绝对都支持），
每次跳转重新应用 `Range` / `If-Range`。探测与 `fetchSegment` 共用。
实测能跟到重定向目标；若目标本身是 `http://`，仍会被系统的明文策略拒绝（见 §20.4 缺口 6）。

---

## 24. `SocketTimeoutException` 是 `InterruptedIOException` 的子类

**现象**：弱网 / VPN 抖动时下载"停住不动"，状态却是 `PAUSED`，没有任何提示。

**原因**：暂停判定写的是 `e is InterruptedIOException`，而 `SocketTimeoutException` 正好继承它。
读超时于是被解释成"用户点了暂停"，抛 `CancellationException`，任务静默停在暂停态。

**处理**：

```kotlin
if (Thread.currentThread().isInterrupted ||
    (e is InterruptedIOException && e !is java.net.SocketTimeoutException)
) throw CancellationException("paused")
```

---

## 25. 断网应该"等待"，不该"失败"

**原因**：`fetchSegment` 重试 3 次（1s/2s/4s）就整任务 `FAILED`，而"仅 Wi-Fi"却是
`PAUSED` + 自动恢复 —— 同样是没网，两种结局。

**处理**：连接类异常（`UnknownHost`/`Connect`/`Socket`/`SocketTimeout`）且
`!hasInternet()` 时抛内部 `NetworkWait`，**不消耗重试**，`runTask` 置
`PAUSED + NET_WAIT("等待网络")`；`NetworkWatcher.onAvailable` 同时恢复 `WIFI_WAIT` 与 `NET_WAIT`。

实测：3.99 MB 处断网 → `PAUSED / 等待网络`；恢复后 `NET_AVAILABLE waiting=1` → 续传 → `COMPLETED`。

---

## 26. 文件被外部删除后，续传锚点会写坏文件

**原因**：`segment.downloaded` 还在，但 `RandomAccessFile` 打开的是新建的空文件；
`setLength` 预分配后从旧偏移续写，前面那段就是空洞。

**处理**：打开文件后先比较 `raf.length() < Σ segment.downloaded` → 记 `TASK_FILE_LOST`、
重建分片计划、从段首重下。

---

## 27. 服务重启时 `Repo` 还没初始化

**现象**：`START_STICKY` 重启的服务里，任务停在 `QUEUED` 没人驱动。

**原因**：初始化（`Repo.init`）只在 `MainActivity.onCreate` 里做；只有服务被重启的进程里
根本没有 Activity 跑过，`Repo` 的 `lateinit` 还是空的。

**处理**：新增 `FreedmApp : Application`，在 `onCreate` 里 `Repo.init` + `Diag.init`；
`DownloadService.onStartCommand` 的默认分支补 `Engine.pump()`。
