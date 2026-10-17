# 逐逻辑实现规格（Logic Spec）

> 粒度目标：不看源码，只按本文就能逐行写出等价实现。
> 每个函数给出 **签名 / 前置条件 / 步骤 / 副作用 / 边界 / 为什么**。
> 伪代码用语言无关写法；`→` 表示赋值，`⊥` 表示返回/中止。

---

# 第 0 部分 · 全局约束

## 0.1 协程与线程拓扑

```
主线程 (Dispatchers.Main.immediate)
  └─ DownloadService：订阅 progress / tasks，刷新通知

IO 线程池 (Dispatchers.IO)
  ├─ Engine.jobs[taskId] : Job            每任务一个，ConcurrentHashMap 去重
  │    ├─ runTask(id)                     串行跑该任务的全部阶段
  │    └─ ticker : Job                    同为该 Job 的子协程，500ms 心跳
  │         └─ 每 500ms 写进度（不阻塞下载）
  └─ 分片协程 × N                         coroutineScope 内 launch，N = 段数
        └─ fetchSegment(...)              阻塞式 HttpURLConnection 读取
```

**必须遵守的三条**

| 规则 | 原因 |
|---|---|
| 一个 `taskId` 同时最多一个 Job | `jobs` 是 `ConcurrentHashMap`，`start()` 先查后放 |
| 分片写文件必须 `synchronized(raf) { seek; write }` | `seek`+`write` 非原子，多协程共享同一个 `RandomAccessFile` |
| 所有 DB 访问经 `Repo`，内部一把 `lock` | SQLite 连接非线程安全，且需要"读-改-写"整体一致 |

## 0.2 真相源与派生量

```
SQLite (downloads.db)          ← 唯一真相源，进程死亡后唯一幸存的东西
  └─ Repo.tasks: StateFlow<List<DownloadTask>>
  └─ Repo.segments: StateFlow<Map<taskId, List<Segment>>>

Engine.progress: StateFlow<Map<taskId, TaskProgress>>   ← 纯内存，进程死即消失
```

**不变量 I1**：`download.downloaded` ≥ `Σ segment.downloaded`，最多超前一个 tick。
**不变量 I2**：续传**只**读 `segment.downloaded`，从不读 `download.downloaded`。
**不变量 I3**：`Σ segment.downloaded` ≤ `download.total_bytes`（若 total>0）。
**不变量 I4**：一个任务的所有 segment 区间恰好覆盖 `[0, total-1]`，无重叠。

---

# 第 1 部分 · 数据结构

## 1.1 TaskState

```
enum TaskState { QUEUED, RUNNING, PAUSED, COMPLETED, FAILED }

isActive   := state ∈ {QUEUED, RUNNING}
isFinished := state ∈ {COMPLETED, FAILED}
```

**没有 CANCELED**。删除任务直接删行，不存在中间态。
持久化为 `TEXT`（枚举名）。反序列化失败（脏数据）→ 回退 `FAILED`，不抛异常。

## 1.2 DownloadTask（对应 `download` 表一行）

| 字段 | 类型 | 谁写 | 约束 / 说明 |
|---|---|---|---|
| `id` | Long | DB 自增 | 0 表示"尚未入库"，仅用于内存构造 |
| `url` | String | create | 用户给的原始 URL，**永不改写** |
| `finalUrl` | String? | runTask 探测后 | 重定向后的真实 URL；为 null 时回落到 `url` |
| `fileName` | String | create（经 FileNamer） | 已清洗、已去重，**不再二次加工** |
| `dirPath` | String | create | 绝对路径；runTask 会 `mkdirs()` 兜底 |
| `totalBytes` | Long | probe / ticker | `-1` = 未知。**不是**"已下载多少" |
| `downloaded` | Long | ticker | 任务级累计，可超前（见 I1） |
| `state` | TaskState | 多处，见迁移表 | |
| `etag` | String? | probe | 续传校验用；null 表示服务器没给 |
| `lastModified` | String? | probe | 仅记录，当前不参与校验 |
| `mime` | String? | probe | 仅记录 |
| `segmented` | Boolean | probe | `true` = 服务器支持 Range，**不等于**实际分了多段 |
| `threadCount` | Int | create | 1–16，创建后不变 |
| `wifiOnly` | Boolean | create | 仅 Wi-Fi 约束 |
| `error` | String? | setState | 失败原因；**特殊值 `"等待 Wi-Fi"` 是状态标记**（见 §7） |
| `createdAt` | Long | create | FIFO 排序键 |
| `updatedAt` | Long | 每次 update/setState | 仅记录 |

派生属性：
```
progress := if (totalBytes > 0) downloaded / totalBytes else 0   // 截断到 [0,1]
filePath := dirPath + "/" + fileName                             // 注意 dirPath 无尾斜杠
```

## 1.3 Segment（对应 `segment` 表一行）

| 字段 | 说明 |
|---|---|
| `id` | 表主键，仅 DB 用 |
| `downloadId` | 归属任务。从 **buildPlan 出来的对象里是 0**（占位），落库时才由 `planSegments` 填入真实 id |
| `index` | 段序号，0 起，同一任务内唯一 |
| `startByte` / `endByte` | 闭区间 `[start, end]`。`endByte = Long.MAX_VALUE` 表示"到文件尾"（大小未知时用） |
| `downloaded` | **续传锚点**。语义 = 该段从 startByte 起已可靠写入的字节数 |
| `state` | `QUEUED` / `RUNNING` / `COMPLETED`。这里的 RUNNING 仅表示"有进度、当前没在跑"，与任务级语义无关 |

派生：
```
length := endByte - startByte + 1     // endByte 为哨兵值时无意义，需特判
```

## 1.4 ProbeResult / TaskProgress

```
ProbeResult { finalUrl, totalBytes, acceptRanges, etag, lastModified, mime, suggestedName }
TaskProgress { downloaded, total, bytesPerSec }      // 纯内存，UI 直接用
```

---

# 第 2 部分 · 持久层（Repo + Db）

## 2.1 建表 DDL（version 1，无迁移）

```sql
CREATE TABLE download (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  url TEXT NOT NULL, final_url TEXT,
  file_name TEXT NOT NULL, dir_path TEXT NOT NULL,
  total_bytes INTEGER NOT NULL DEFAULT -1,
  downloaded  INTEGER NOT NULL DEFAULT 0,
  state TEXT NOT NULL,
  etag TEXT, last_modified TEXT, mime TEXT,
  segmented INTEGER NOT NULL DEFAULT 0,
  thread_count INTEGER NOT NULL DEFAULT 8,
  wifi_only INTEGER NOT NULL DEFAULT 0,
  error TEXT,
  created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL
);

CREATE TABLE segment (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  download_id INTEGER NOT NULL,
  idx INTEGER NOT NULL,
  start_byte INTEGER NOT NULL,
  end_byte   INTEGER NOT NULL,
  downloaded INTEGER NOT NULL DEFAULT 0,
  state TEXT NOT NULL
);
CREATE INDEX idx_segment_download ON segment(download_id);
```

- 库名 `downloads.db`，位于 **`databases/`**（不是 `files/`）。
- 外键约束开启；`onUpgrade` 直接 DROP + 重建（未发布版本不写迁移）。
- 布尔统一存 `INTEGER 0/1`。

## 2.2 `Repo.init(ctx)` — 进程启动唯一入口

```
1. 记下 applicationContext
2. 建 Db 实例；确保 downloadsDir 存在（filesDir/downloads）
3. UPDATE download SET state='QUEUED' WHERE state='RUNNING'      ← 关键
4. refresh()
```

**为什么第 3 步是 QUEUED 而不是 PAUSED**
`RUNNING` 落库 = "崩溃前它正在跑"，语义是"应该继续"。用户主动暂停记的是 `PAUSED`，
两者必须可区分，否则"自动恢复"会把手动暂停的任务也拉起来。

## 2.3 `Repo.refresh()`

```
list := SELECT * FROM download ORDER BY created_at DESC   （加锁同步执行）
tasks.value → list
```
全量重查。任务量在几十量级，不做增量。

## 2.4 `Repo.create(...)` → taskId

```
1. now := 当前毫秒
2. 用入参 + now 构造 DownloadTask{ id=0, state=QUEUED, downloaded=0,
     finalUrl/etag/lastModified/mime/segmented 取自 probe（可为 null / false） }
3. INSERT INTO download
4. refresh()
5. ⊥ 自增 id
```

边界：`probe == null`（探测失败）时 `totalBytes = -1`、`segmented = false`，任务仍可创建。

## 2.5 `Repo.fastProgress(id, downloaded, totalBytes)`

```
UPDATE download SET downloaded=?, total_bytes=?, updated_at=? WHERE id=?
```
**只更新这三列**，不碰 state。ticker 每 500 ms 调一次。

## 2.6 `Repo.setState(id, state, error = null)`

```
UPDATE download SET state=?, error=?, updated_at=? WHERE id=?
refresh()
```
注意：`error` 每次都会被**覆盖**（含 null）。所以置 `QUEUED` 时会自动清掉旧的 `"等待 Wi-Fi"` 标记。

## 2.7 `Repo.planSegments(id, plan)`

```
加锁:
  DELETE FROM segment WHERE download_id=?
  FOREACH seg IN plan:
     INSERT (download_id=id, idx=seg.index, start_byte, end_byte, downloaded=0, state='QUEUED')
publishSegments(id)
```
**注意**：写入的 `download_id` 用的是**函数参数 id**，不是 `seg.downloadId`（后者是占位 0）。

## 2.8 `Repo.markSegment(taskId, idx, downloaded, state)`

```
UPDATE segment SET downloaded=?, state=? WHERE download_id=? AND idx=?
```
失败路径也要调（把已写入的偏移落库），这是崩溃续传的前提。

## 2.9 `Repo.delete(id, deleteFiles)`

```
1. t := task(id)                     // 先读，为了拿文件路径
2. DELETE FROM segment WHERE download_id=?
3. DELETE FROM download WHERE id=?
4. if (deleteFiles && t != null) 删文件（失败静默）
5. refresh()
```
没有 CANCELED 中间态。

## 2.10 `Repo.buildPlan(total, threads)` — 分片算法（逐分支）

```
n      ← clamp(threads, 1, 16)
if (total <= 0)               ⊥ []                       // 大小未知：由调用方走单段分支
granule ← 8 * 1024 * 1024                                // 8 MiB
if (total < 2 * granule)      ⊥ [ Segment(index=0, start=0, end=total-1) ]   // <16 MiB 不分段

count ← min( n , max(1, min(64, floor(total / granule))) )
chunk ← ceil(total / count)                              // = (total + count - 1) / count

out ← []
start ← 0 ; idx ← 0
while (start < total):
    end ← min(start + chunk - 1, total - 1)
    out.append(Segment(index=idx, start=start, end=end, downloaded=0))
    start ← end + 1
    idx   ← idx + 1
⊥ out
```

**四个必须照做的细节**

1. `count` 受**线程数**约束。`8 线程 + 1 GiB` → `min(8, min(64,128)) = 8` 段，不是 64 段。
2. 阈值用 `<` 不是 `≤`：`total == 16 MiB` 时**会**分段（2 段）。
3. `chunk` 向上取整，所以 `count * chunk ≥ total`，最后一段靠 `min(...)` 截断。
4. 返回对象的 `downloadId` 恒为 0（占位）；落库由 `planSegments` 补真实 id。

**示例**（便于自测）

| total | threads | 结果 |
|---|---|---|
| 1000 | 8 | 1 段 `[0,999]`（<16 MiB） |
| 16 MiB | 8 | 2 段，每段 8 MiB |
| 50 MiB | 8 | `count=min(8,6)=6`，`chunk=8738134`，6 段 |
| 1 GiB | 8 | `count=8`，`chunk=134217728`，8 段 |
| 1 GiB | 4 | `count=4`，`chunk=268435456`，4 段 |

---

# 第 3 部分 · 探测与文件名（Prober / FileNamer）

## 3.1 `Prober.probe(url)` → ProbeResult

```
1. conn ← open(url, method=HEAD, range=null)          // 超时 连接20s / 读20s
2. code ← conn.responseCode
3. if (code ∉ [200,299]):
       conn.disconnect()
       conn ← open(url, method=GET, range="bytes=0-0") // 部分 CDN 拒绝 HEAD
       code ← conn.responseCode
4. if (code ∉ [200,399])  ⊥ 抛错 "HTTP <code>"        // 让 runTask 判 FAILED
5. contentRange ← 响应头 Content-Range                // 形如 "bytes 0-0/12345"
   acceptRanges  ← (Accept-Ranges 含 "bytes") ∨ (contentRange != null) ∨ (code == 206)
6. total ←
       if contentRange 非空:  取 "/" 之后的数字，失败则 -1
       else:                  Content-Length 转数字，失败则 -1
7. mime ← Content-Type 去掉 ";..." 部分并 trim
8. suggestedName ← FileNamer.fromHeaders(Content-Disposition, conn.url, mime)
9. ⊥ ProbeResult(finalUrl=conn.url, total, acceptRanges && total > 0,
                 etag=ETag头, lastModified=Last-Modified头, mime, suggestedName)
```

**要点**

- `acceptRanges` 必须再 `&& total > 0`：不知道总大小就无法算区间。
- `finalUrl` 取 `conn.url`（已跟随重定向），不是入参 `url`。
- 整个函数在 IO 线程调用；任何异常向上抛，由 `runTask` 或调用方 `runCatching` 处理。

## 3.2 `FileNamer.fromHeaders(disposition, url, mime)` → String?

```
1. if disposition 非空:
     a. r ← parseFilenameStar(disposition)    // filename*=UTF-8''...
     b. if r 非空 ⊥ sanitize(r)
     c. r ← parseFilenameQuoted(disposition)  // filename="..."
     d. if r 非空 ⊥ sanitize(r)
2. base ← urlBaseName(url)  之后 sanitize
3. if (base 非空 && base 含 '.' && base 长度 > 1) ⊥ base      // 已有扩展名，直接用
4. ext ← MIME 到扩展名映射[mime]
5. if (base 非空) ⊥ if (ext 非空 && !base.endsWith("."+ext)) base+"."+ext else base
6. if (ext 非空) ⊥ "download." + ext
7. ⊥ null
```

**子函数**

```
parseFilenameStar(d):
   i ← d 中 "filename*=" 的位置（忽略大小写），没有 → null
   v ← i+len 起、截到 ';' 前、去引号
   parts ← v.split("'", limit=3)              // charset'lang'value
   if parts.size == 3: v ← URLDecode(parts[2], UTF-8)（解码失败原样返回）
   ⊥ v

parseFilenameQuoted(d):
   i ← "filename=" 位置，没有 → null
   v ← 其后、截到 ';' 前，去首尾引号
   ⊥ v

urlBaseName(url):
   path ← url 去掉 '#...' 再去掉 '?...'
   ⊥ path 最后一段（空则 null）

sanitize(name):
   n ← 把 [\ / : * ? " < > |] 和 U+0000–U+001F 全部替换为 '_'
   n ← trim，再去掉首尾 '.'
   if n 为空 → "download"
   if n 长度 > 120:
       有扩展名: 保留扩展名，主名截断，总长 ≤ 120
       无扩展名: 直接截 120
   ⊥ n

uniqueIn(dir, name):
   if dir/name 不存在 ⊥ name
   stem/ext ← 拆分
   for i in 1..999:
       cand ← "stem (i)ext"
       if dir/cand 不存在 ⊥ cand
   ⊥ "stem-1000ext"
```

## 3.3 常量 User-Agent

```
"Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) "
"Chrome/124.0 Mobile Safari/537.36 FreeDM/0.1"
```
探测与每段下载都必须带同一个 UA。

---

# 第 4 部分 · 下载引擎（核心）

## 4.1 `Engine.start(taskId)`

```
1. if jobs 已含 taskId ⊥ 返回              // 幂等，防重复启动
2. RateLimiter.setRate(prefs.getLong("speed_limit", 0))
3. job ← 新 Job（惰性），body:
     try:
        runTask(taskId)
     catch CancellationException:
        Repo.setState(id, PAUSED)          // 用户暂停
        rethrow
     catch Throwable t:
        msg ← t.message ?: t 类名
        Repo.setState(id, FAILED, msg)
        Notifier.failed(ctx, id, task?.fileName ?: "下载任务", msg)
     finally:
        progressData.remove(id) ; publish()
        jobs.remove(id)
        pump()                              // ← 补位：立刻调度下一个排队任务
4. jobs[taskId] ← job
5. job.start()
```

**注意**：`start()` **不检查并发上限**。用户点「开始下载」直接起。上限只在 `pump()` 里守。

## 4.2 `Engine.pump()`

```
1. limit ← clamp(prefs.getInt("max_concurrent", 3), 1, 8)
2. if (jobs.size >= limit) ⊥ 返回
3. cand ← tasks.value
            .filter { state == QUEUED && !jobs.containsKey(id) }
            .sortedBy { createdAt }                 // FIFO
            .take(limit - jobs.size)
4. FOREACH cand: start(id)
```

## 4.3 `Engine.pause(id)`

```
1. job ← jobs[id]
2. if (job != null): 在 scope 里 job.cancelAndJoin()    // 阻塞等待真正停下
3. if (!isRunning(id)) Repo.setState(id, PAUSED)        // 兜底：本来没跑就直接落状态
```
`pauseAll()` = 对 `jobs.keys` 逐个 `pause()`。
`resumeAll()` = 把**所有** `PAUSED` 置 `QUEUED`，再 `pump()`。

## 4.4 `runTask(id)` — 十个步骤

> 前置：由 `start()` 调用，跑在 IO 线程，是该任务的唯一活动 Job。

```
S0. task ← Repo.task(id) ；若 null ⊥ 直接返回（记录已被删）
    Repo.setState(id, RUNNING)

S1. dir ← File(task.dirPath) ；mkdirs()
    file ← File(dir, task.fileName)

S2. probe ← null
    if (task.totalBytes <= 0 || task.etag == null):        // 缺大小或缺校验器才探测
        probe ← Prober.probe(task.finalUrl ?: task.url)
        task ← task.copy(finalUrl, totalBytes, segmented=probe.acceptRanges,
                         etag, lastModified, mime  ← probe 对应值)
        Repo.update(task)

S3. existing ← Repo.loadSegments(id)
    if (existing 非空 && probe != null):                    // 只有本次探测过才校验
        etagChanged ← probe.etag != null && task.etag != null && probe.etag != task.etag
        sizeChanged ← probe.totalBytes > 0 && task.totalBytes != probe.totalBytes
        if (etagChanged || sizeChanged):
            Repo.planSegments(id, Repo.buildPlan(probe.totalBytes, task.threadCount))
            runCatching { file.delete() }                   // 旧数据作废

S4. if (Repo.loadSegments(id) 为空):                        // 首次运行（或 S3 重建过）
        total ← task.totalBytes
        fresh ← if (task.segmented && total > 0):
                    Repo.buildPlan(total, task.threadCount)
                else:
                    [ Segment(index=0, start=0,
                              end = (total>0 ? total-1 : Long.MAX_VALUE),
                              downloaded=0) ]               // 大小未知走哨兵单段
        Repo.planSegments(id, fresh)
    plan ← Repo.loadSegments(id)                            // 重读，拿到真实 downloadId

S5. if (task.wifiOnly && !onWifi()):
        Repo.setState(id, PAUSED, error="等待 Wi-Fi")
        ⊥ 返回                                              // 不删文件，不动分片

S6. raf ← RandomAccessFile(file, "rw")
    try:
        if (task.totalBytes > 0 && raf.length() < task.totalBytes)
            raf.setLength(task.totalBytes)                  // 预分配，让各段能自由 seek

S7.     segProgress ← { seg → AtomicLong(seg.downloaded) }   // 每段一个续传锚点
        counter     ← AtomicLong( Σ plan.downloaded )
        total       ← if (task.totalBytes > 0) task.totalBytes else -1
        lastFlush ← now ; lastBytes ← counter.get()
        lastSegFlush ← 0 ; lastSegBytes ← counter.get()

        ticker ← 每 500 ms 执行：                            // 跑在独立子协程
            now ← 当前毫秒
            bytes ← counter.get()
            bps ← if (now > lastFlush) (bytes - lastBytes) * 1000 / (now - lastFlush) else 0
            progressData[id] ← TaskProgress(bytes, total, bps) ; publish()
            Repo.fastProgress(id, bytes, total)
            if (now - lastSegFlush >= 1000 OR bytes - lastSegBytes >= 1 MiB):
                FOREACH seg IN plan: Repo.markSegment(id, seg.index, segProgress[seg].get(), RUNNING)
                lastSegFlush ← now ; lastSegBytes ← bytes
            lastFlush ← now ; lastBytes ← bytes

S8.     try:
            coroutineScope:
                FOREACH seg IN plan:
                    launch(IO):
                        acc ← segProgress[seg]
                        ok  ← fetchSegment(id, task, seg, raf, acc) { delta → counter.addAndGet(delta) }
                        if (!ok) throw Error("分段 <seg.index> 下载失败")
                        Repo.markSegment(id, seg.index, seg.length, COMPLETED)
        finally:
            ticker.cancel()

S9.     finalBytes ← counter.get()
        progressData[id] ← TaskProgress(finalBytes, total, 0) ; publish()
        Repo.fastProgress(id, finalBytes, total)

        if (total > 0 && file.length() < total) throw Error("文件大小不符：<len>/<total>")

        Repo.setState(id, COMPLETED)
        Notifier.completed(ctx, id, task.fileName, file.absolutePath)
        Exporter.exportIfEnabled(ctx, file, task.fileName)

    finally:
        raf.close()（静默）
```

**S8 的失败语义**：`coroutineScope` 内任一子协程抛异常 → **兄弟协程全部被取消**，
异常上抛到 `start()` 的 catch → 整个任务 `FAILED`。
即：**任意一段重试耗尽 = 整任务失败**，不会"部分成功"。

**S3 的一个陷阱**：只有当次执行了探测（`probe != null`）才会做校验器比对。
如果任务在创建时已经拿到 `totalBytes` 和 `etag`，续传时**不会**重新验证资源是否变化。
这是有意的取舍：省一次网络往返，代价是"服务器内容换了但 ETag 也恰好没变"时无法发现。

## 4.5 `fetchSegment(taskId, task, seg, raf, written, onBytes)` — 单段状态机

> `written: AtomicLong` 是**该段**的续传锚点，初值 `seg.downloaded`。
> 它与 ticker 共享，所以崩在任意时刻都能从最近落库值继续。

```
url      ← task.finalUrl ?: task.url
expected ← if (seg.endByte == MAX_VALUE) -1 else seg.length     // -1 = 长度未知
attempt  ← 0

while (attempt < 3):
    attempt += 1
    try:
        if (expected >= 0 && written.get() >= expected) ⊥ true   // 已完成

        from  ← seg.startByte + written.get()                   // ★ 用当前锚点，不是快照
        ranged ← (seg.endByte != MAX_VALUE)

        conn ← HttpURLConnection(url)
            connectTimeout=20s, readTimeout=30s, followRedirects=true
            UA ← Prober.UA
            if ranged: Range ← "bytes=<from>-<seg.endByte>"
            if task.etag != null: If-Range ← task.etag

        code ← conn.responseCode
        if (code ∉ [200,299]) throw IOException("HTTP <code>")

        // ★★ 关键防线：续传请求必须得到 206
        if (ranged && from > seg.startByte && code != 206):
            throw IOException("服务器未按 Range 返回（HTTP <code>）")

        input ← conn.inputStream
        pos ← from
        buf ← byte[64 KiB]
        loop:
            n ← input.read(buf)
            if (n <= 0) break
            synchronized(raf): raf.seek(pos) ; raf.write(buf, 0, n)
            pos     += n
            written += n                       // ★ 先递增锚点
            onBytes(n)                         // 只上报真实新增，重试不会重复计数
            RateLimiter.acquire(n)             // 可能阻塞（限速）

        // ★ 短读检测：连接静默截断不会抛异常
        if (expected >= 0 && written.get() < expected):
            throw IOException("连接提前结束（<written>/<expected>）")

        ⊥ true

    catch (e: Exception):
        Repo.markSegment(taskId, seg.index, written.get(), QUEUED)   // 失败也落库
        if (attempt >= 3) ⊥ false
        sleep(1000 << (attempt - 1))           // 1s, 2s, 4s
```

**为什么每一处都这么写**

| 代码 | 不这么写会怎样 |
|---|---|
| `from` 用 `written.get()` 而非 `seg.downloaded` | 重试从段首重下，流量翻倍 |
| `written` 跨重试保留 | 同上，且进度会重复累加（超过 100%） |
| 续传响应非 206 就抛错 | 服务器返回整个文件时，整包体写到非零偏移 → **文件静默损坏**，且大小校验因为预分配能通过 |
| 短读检测 | 连接被掐断但 `read` 正常返回 -1，会当成"下完了" → 文件有空洞 |
| 失败时 `markSegment` | 崩溃恢复退回到上次 tick 的偏移，多下一段 |

## 4.6 `onWifi()`

```
cm ← ConnectivityManager ；取不到 → ⊥ true（放行，避免误判锁死）
caps ← cm.getNetworkCapabilities(cm.activeNetwork) ；null → ⊥ false
⊥ caps 有 TRANSPORT_WIFI 或 TRANSPORT_ETHERNET
```

---

# 第 5 部分 · 限速（RateLimiter）

全局单例，令牌桶，**所有任务的所有分片共享一个桶**。

```
state: bytesPerSec(volatile, 0 表示不限速), allowance(Double), lastNanos

setRate(v):
    synchronized: bytesPerSec ← max(v,0) ; allowance ← 0 ; lastNanos ← now

acquire(bytes):
    rate ← bytesPerSec
    if (rate <= 0 || bytes <= 0) ⊥ 返回                     // 不限速直接过
    synchronized:
        now ← nanoTime
        allowance += (now - lastNanos) / 1e9 * rate         // 按流逝时间补充令牌
        lastNanos ← now
        if (allowance > rate) allowance ← rate              // 桶容量 = 1 秒的量（限制突发）
        allowance -= bytes
        if (allowance < 0):
            waitMs ← ceil(-allowance / rate * 1000) 且至少 1
            sleep(waitMs)                                   // 阻塞该分片线程
            allowance  ← 0
            lastNanos  ← nanoTime
```

- 阻塞发生在 `fetchSegment` 的读循环里，天然对该分片生效。
- 因为桶容量只有 1 秒的量，多分片并发时总吞吐 ≈ `bytesPerSec`（实测 500 KB/s 设定 → 459 KB/s）。
- 注意：`synchronized` 抢锁本身会串行化多分片，这是**有意的**——它同时充当限速下的公平排队。

---

# 第 6 部分 · 通知与服务

## 6.1 渠道

| 渠道 id | 名称 | importance | 特性 |
|---|---|---|---|
| `download_progress` | 下载进度 | LOW | 无声、无震动、不显示角标 |
| `download_done` | 下载完成 | DEFAULT | — |

前台通知 id 固定 `1001`；终态通知 id = **taskId**（早期用 `path.hashCode()` 会撞车，已改）。

## 6.2 `DownloadService`

```
onCreate:
  建渠道
  startForeground(1001, 汇总占位通知)
  订阅 Engine.progress  → updateNotification()
  订阅 Repo.tasks       → updateNotification()

onStartCommand(intent):
  action == PAUSE_ALL → Engine.pauseAll() ；若无活跃任务则停服务
  否则                 → updateNotification()
  ⊥ START_STICKY

updateNotification():
  active ← tasks 中 state ∈ {RUNNING, QUEUED}
  if (active 为空): stopForeground(remove) ; stopSelf() ; ⊥ 返回    // 自己退场
  downloaded ← Σ progress[id].downloaded ?: task.downloaded
  total      ← Σ progress[id].total     ?: task.totalBytes（截到 ≥0）
  bps        ← Σ progress[].bytesPerSec
  title      ← active.size == 1 ? 该任务文件名 : "<n> 个任务下载中"
  text       ← "<已下载> / <总量> · <速率>/s"（总量未知则省前半段）
  pct        ← total > 0 ? downloaded*100/total : 0
  发通知：进度条(pct) + 动作「全部暂停」
```

## 6.3 `Notifier`

```
completed(ctx, taskId, fileName, path):
  requestCode ← taskId.toInt()
  open ← FileProvider.getUriForFile(ctx, "<pkg>.fileprovider", File(path))
         失败则 null（通知仍发出，只是点不开）
  MIME ← 按扩展名猜（mp4/webm/mkv/mp3/m4a/pdf/zip/apk/jpg/png/其他 octet-stream）
  发通知（channel=download_done, autoCancel, contentIntent=open）

failed(ctx, taskId, fileName, error):
  发通知，点击回到 MainActivity
```

正文文案保持极短（`下载完成` / `下载失败`）。

## 6.4 服务自停时机（`stopSelfIfIdle`）

```
stopSelfIfIdle():
    if (tasks 中不存在 state ∈ {RUNNING, QUEUED} 的任务):
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
```

两个调用点：

1. `updateNotification()` 开头发现 `active 为空` → 调它后返回（避免发空进度通知）
2. `onStartCommand` 收到 `PAUSE_ALL` 之后 → 全暂停后可能已无活跃任务

**作用**：下载全部结束后服务自己退场，不留常驻前台通知。
若随后又有新任务，`DownloadService.start()` 会重新拉起。

---

# 第 7 部分 · 网络监听（NetworkWatcher）

```
start(ctx):
    if 已注册 ⊥ 返回                                  // 幂等
    cm.registerDefaultNetworkCallback(object {
        onAvailable(network):
            waiting ← tasks.value.filter {
                state == PAUSED && error == "等待 Wi-Fi"   // ★ 只认这个标记
            }
            if waiting 为空 ⊥ 返回
            FOREACH w IN waiting: Repo.setState(w.id, QUEUED)   // 顺带清掉 error
            DownloadService.start(ctx)
            Engine.pump()
    })
```

**为什么用 `error` 字段当标记**
需求是"Wi-Fi 恢复后自动继续"，但**不能**把用户手动暂停的任务也拉起来。
两者状态都是 `PAUSED`，只能靠附加信息区分。`error` 字段天然适合：
`setState` 置 `QUEUED` 时会把 `error` 覆盖为 null，标记自动清除，无需额外清理。

**注册时机**：`MainActivity.onCreate`（`Repo.init` 之后）。
进程死掉后回调随之失效，下次打开重新注册——可接受。

---

# 第 8 部分 · 导出到公共目录（Exporter）

```
exportIfEnabled(ctx, file, displayName):
    if (!prefs.getBoolean("export_public", false)) ⊥ 返回
    if (!file.exists() || file.length() == 0)      ⊥ 返回
    runCatching {                                   // 全程静默，失败不影响任务状态
        if (SDK >= Q) exportViaMediaStore(...)
        else {
            dir ← Environment.getExternalStoragePublicDirectory(DIRECTORY_DOWNLOADS)
            dir.mkdirs()
            file.copyTo(dir/displayName, overwrite=true)
            file.delete()
        }
    }

exportViaMediaStore(ctx, file, displayName):
    resolver ← ctx.contentResolver
    pending  ← { DISPLAY_NAME=displayName, MIME_TYPE=octet-stream, IS_PENDING=1 }
    uri ← resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, pending)
    if (uri == null) ⊥ 返回                          // 重名冲突等，保留私有副本
    resolver.openOutputStream(uri)?.use { 把 file 全量 copy 进去 } ?: ⊥ 返回
    resolver.update(uri, { IS_PENDING=0 }, null, null)
    file.delete()                                    // 仅在全部成功后删
```

**两条硬约束**

1. **必须先下到私有目录再复制**。分段下载依赖 `RandomAccessFile.seek`，MediaStore 只给顺序流。
2. **导出行删除私有副本后，DB 的 `dir_path/file_name` 会指向不存在的文件**。
   任何读取文件大小的地方必须 `if (f.exists()) f.length() else t.totalBytes`，
   否则列表显示 `0 B`（`File.length()` 对不存在的文件返回 0 且不抛异常）。

---

# 第 9 部分 · 媒体嗅探（Sniffer）

## 9.1 注入脚本（DOM 侧）

编译期固定字符串，**不从网络下发、不含任何回调上报**。

```
(function () {
  out = []
  push(u, t): if (u) out.push({u: u, t: t || ""})

  // 1) 可见的音视频元素
  FOR EACH el IN document.querySelectorAll('video, audio'):
      w ← el.clientWidth ; h ← el.clientHeight
      if (w > 100 && h > 100 && el.src) push(el.src, el.title || "")
      FOR EACH s IN el.querySelectorAll('source'): push(s.src, "")
      if (el.currentSrc) push(el.currentSrc, "")

  // 2) 指向媒体文件的链接
  FOR EACH a IN document.querySelectorAll('a[href]'):
      if (a.href 匹配 /\.(mp4|webm|mkv|mov|m3u8|mpd|mp3|m4a|aac|ogg|flv|ts)(\?|#|$)/i)
          push(a.href, a.textContent.trim())

  // 3) 去重（保持首次出现顺序），最多 50 条
  去重 out by u
  return JSON.stringify(out.slice(0, 50))
})()
```

触发时机：`onPageFinished` 后调用一次。页面重新加载时清空已有结果。

## 9.2 解析返回值（踩过的坑）

`WebView.evaluateJavascript` 会把 JS 返回值**再 JSON 编码一次**，所以拿到的是
`"\"[{\\\"u\\\":...}]\""`——字符串字面量，不是数组。

```
parseProbeResult(raw):
    if (raw 为空 || raw == "null") ⊥ []
    inner ← if (raw 以 '"' 开头) 把 raw 当 JSON 字符串解一层，取内容 else raw
    arr   ← JSONArray(inner)      // 解析失败 ⊥ []（静默，不抛）
    FOREACH o IN arr:
        u ← o.optString("u") ; if (u 为空) skip
        ⊥ SniffedMedia(url=u, kind=kindOf(u, null),
                       title=o.optString("t") 为空则取 url 最后一段,
                       fromNetwork=false)
```

## 9.3 网络层补充

```
shouldInterceptRequest(request):
    if (!sniff 开启) ⊥ null（放行）
    u ← request.url
    kind ← kindOf(u, null)
    if (kind ∈ {VIDEO, AUDIO, HLS, DASH}) 收进结果集（fromNetwork=true）
    ⊥ null（永不拦截请求本身）
```

**局限**：这个回调拿不到响应体和响应头，只能按 URL 后缀猜类型。
真实 MIME 只能靠 DOM 扫描补。已记入已知缺陷。

## 9.4 `kindOf(url, mime)` 判定顺序

```
path ← url 去 '#'、去 '?' 后转小写 ；m ← mime 去 ';...' 转小写
HLS   if path 以 .m3u8 结尾 OR m ∈ {application/vnd.apple.mpegurl, application/x-mpegurl}
DASH  if path 以 .mpd 结尾  OR m == application/dash+xml
VIDEO if m 以 "video/" 开头 OR path 以 [.mp4 .webm .mkv .mov .flv .ts] 之一结尾
AUDIO if m 以 "audio/" 开头 OR path 以 [.mp3 .m4a .aac .ogg .opus .wav] 之一结尾
否则 FILE
```

## 9.5 去重键

```
normalize(u):
    noFrag ← u 去 '#' 及之后
    base   ← noFrag 去 '?' 及之后
    query  ← noFrag 中 '?' 之后的部分（无则空）
    ⊥ query 为空 ? base : base + "?" + (query 按 & 切分后排序再拼回)
```
同一媒体被 DOM 与网络同时命中 → 合并为一条，保留先到者的 `fromNetwork` 标记。

---

# 第 10 部分 · 剪贴板识别（ClipboardWatcher）

**默认关闭**；开启后**只在 UI 前台时**注册。

```
enabled ← prefs.getBoolean("clipboard_watch", false)

start():   // 由 Activity.onResume 调用
    if (!enabled || 已注册) ⊥ 返回
    cm.addPrimaryClipChangedListener { handle(cm) }

stop():    // 由 Activity.onPause 调用
    if (未注册) ⊥ 返回
    cm.removePrimaryClipChangedListener(...)
    已注册 ← false

handle(cm):
    text ← runCatching {
        if (!cm.hasPrimaryClip()) ⊥ 返回
        desc ← cm.primaryClipDescription ?: ⊥ 返回
        if (!desc.hasMimeType("text/plain")) ⊥ 返回
        cm.primaryClip.getItemAt(0).coerceToText(ctx).toString()
    } 失败则 ⊥ 返回                     // 后台读剪贴板抛 SecurityException，必须吞掉
    if (text == lastSeen) ⊥ 返回        // 内容没变不重复触发
    lastSeen ← text
    url ← 从 text 中提取第一个 http(s) URL ；没有 ⊥ 返回
    onLink(url)                         // → ShareBus.post(url)
```

**隐私要求（不可放宽）**：不保存原文、不写日志、不发网络、退出前台立即注销监听。

---

# 第 11 部分 · UI 动作 → 完整调用序列

> 这一节是"按钮按下去之后到底发生什么"。漏一步就会出现"点了没反应"。

## 11.1 添加下载弹窗「开始下载」

```
1. clean ← 从输入框提取第一个 http(s) URL ；为空则按钮禁用
2. probe ← runCatching { Prober.probe(clean) }.getOrNull()      // 失败可为 null
3. suggested ← 用户填的文件名 ifBlank { probe.suggestedName ?: "download" }
4. name ← FileNamer.uniqueIn(downloadsDir, FileNamer.sanitize(suggested))
5. id   ← Repo.create(clean, name, downloadsDir, threads, wifiOnly, probe)
6. if (probe == null) 提示"无法探测服务器信息，将按单连接重试"
7. DownloadService.start(ctx)          ← 漏掉：没有通知，后台不保活
8. Engine.start(id)                    ← 漏掉：任务不启动
9. 关闭弹窗
```

## 11.2 任务卡片「暂停 / 继续」

```
if (state == RUNNING):  Engine.pause(id)
else:                   Repo.setState(id, QUEUED) ; DownloadService.start(ctx) ; Engine.pump()
```
继续时 `startService` 是为了避免服务已自停后再无通知。

## 11.3 任务卡片「删除」

```
Repo.delete(id, deleteFiles = true)     // 记录 + 文件一起删
```

## 11.4 顶部「全部开始 / 全部暂停」

```
全部开始: Engine.resumeAll()    // 内部：所有 PAUSED → QUEUED，再 pump
全部暂停: Engine.pauseAll()
```

## 11.5 详情页

```
暂停/继续按钮:
    when (state) {
        RUNNING   → Engine.pause(id)
        COMPLETED → 无动作
        else      → Repo.setState(id, QUEUED) ; Engine.pump()
    }
删除按钮: Repo.delete(id, true) 后返回上一页
进入页面时: Repo.publishSegments(id)     // 拉一次分片详情
```

## 11.6 已完成页

```
重新下载: Repo.create(同 url, uniqueIn(原目录, 原名), 同 dirPath, 同 threadCount, 同 wifiOnly, probe=null)
          ; Engine.pump()
          —— 注意 probe 传 null，重新探测
重试:     Repo.setState(id, QUEUED) ; Engine.pump()
删除:     Repo.delete(id, true)
```

## 11.7 嗅探结果「下载」

```
probe ← runCatching { Prober.probe(item.url) }.getOrNull()
name  ← uniqueIn(downloadsDir, sanitize(probe.suggestedName ?: item.title.ifBlank{"media"}))
id    ← Repo.create(item.url, name, downloadsDir, defaultThreads(), false, probe)
DownloadService.start(ctx) ; Engine.start(id)
```

## 11.8 浏览器

```
地址栏回车: 若以 http(s):// 开头 → 直接加载
            否则若含 '.' 且不含空格 → 加载 "https://" + 输入
            否则 → 搜索引擎前缀 + URLEncode(输入)      // 默认 DuckDuckGo
后退/前进: WebView.goBack() / goForward()，enabled 由 canGoBack/canGoForward 控制
刷新:     WebView.reload()
主页:     WebView.loadUrl("about:blank") ; 清空地址栏与嗅探结果
```

## 11.9 应用启动（MainActivity.onCreate）

```
1. Repo.init(ctx)                       // RUNNING → QUEUED（必须在 2 之前）
2. consumeIntent(intent)                // 分享/VIEW 的 URL → ShareBus.post
3. NetworkWatcher.start(ctx)
4. if (auto_resume && 存在 QUEUED):
       DownloadService.start(ctx) ; Engine.pump()
5. 请求 POST_NOTIFICATIONS（SDK ≥ 33 且未授予）
onResume: ClipboardWatcher.start()
onPause:  ClipboardWatcher.stop()
```

`onNewIntent` 只做第 2 步。

---

# 第 12 部分 · 边界条件总表

| 场景 | 行为 |
|---|---|
| `Repo.task(id)` 返回 null（记录已删） | `runTask` 直接返回，不报错 |
| `total <= 0`（大小未知） | 单段、`end = Long.MAX_VALUE`、请求不带 `Range`、**不预分配**、跳过大小校验 |
| `total` 恰为 16 MiB | **会**分段（阈值用 `<`），2 段 |
| 线程数 > 段数 | 多出的线程不启动，段数由 `count` 决定 |
| 服务器拒绝 HEAD（403/405） | 回退 `GET + Range: bytes=0-0` |
| 服务器忽略 Range 返回 200 | 续传请求（`from > startByte`）抛错重试；首次请求（`from == startByte`）可接受 |
| 连接静默截断（read 返回 -1） | 短读检测抛错 → 重试 |
| 单段 3 次重试全失败 | 整任务 `FAILED`，兄弟分片被取消 |
| 下载中进程被杀 | 状态停留 `RUNNING` → 下次启动转 `QUEUED` → 从 `segment.downloaded` 续 |
| 用户手动暂停 | 状态 `PAUSED`，重启后**不自动恢复** |
| 仅 Wi-Fi 且当前非 Wi-Fi | `PAUSED` + `error="等待 Wi-Fi"`，网络恢复时只恢复这类 |
| 资源 ETag 变化 | 重建分片计划 + 删文件，从零重下（仅在当次探测过时才检测） |
| 文件名超 120 字符 | 保留扩展名截断 |
| 文件名非法字符 | 替换为 `_`，首尾去 `.` |
| 目标文件已存在 | `uniqueIn` 追加 ` (1)`…` (999)` |
| `probe` 失败 | 仍创建任务（`totalBytes=-1`），下载时重新探测 |
| 导出到公共目录失败 | 保留私有副本，任务仍 `COMPLETED` |
| 导出成功 | 私有副本被删，UI 读大小必须 `exists()` 兜底 |
| 已完成任务再次"开始" | UI 不提供该动作；`Engine.start` 会重新走 `runTask`（plan 已存在，直接从 `downloaded` 续，通常立即完成） |
| 重复点击"开始下载" | `Engine.start` 幂等（`jobs` 已含则返回） |
| 通知权限未授予 | 通知静默失败，下载不受影响 |

---

# 第 13 部分 · 时序

## 13.1 全新下载（50 MiB，8 线程）

```
UI ──probe──► Prober           HEAD → 200, Accept-Ranges, ETag, len=52428800
UI ──create─► Repo             INSERT download (total=52428800, segmented=1, state=QUEUED)
UI ──start──► Engine           jobs[id]=job ; job.start()
                runTask
                  S2: total>0 且 etag 已存在 → 跳过探测
                  S4: loadSegments 为空 → buildPlan(52428800, 8)
                        → count=min(8, min(64,6))=6 ; chunk=8738134 → 6 段
                      planSegments 落库
                  S6: raf.setLength(52428800)
                  S7: 启动 ticker
                  S8: 6 个协程并发 fetchSegment
                        每个发 Range: bytes=<start>-<end>（带 If-Range）
                        服务器全部 206
                  每 500ms: fastProgress；每 1s 或 1MiB: 6 次 markSegment
                  S9: 校验 len == 52428800 → COMPLETED → 通知 → 导出(可选)
```

## 13.2 崩溃后续传

```
[下载中] 6 段各写 2949120 字节；最后落库 segment.downloaded=2949120
[进程被杀] download.state 仍为 RUNNING
[重启] MainActivity.onCreate
    Repo.init:       RUNNING → QUEUED
    NetworkWatcher.start
    auto_resume → DownloadService.start + Engine.pump
    Engine.start → runTask
        S2: total/etag 已在 → 跳过探测（probe == null）
        S3: probe == null → 不做校验器比对
        S4: loadSegments 非空 → 沿用原 plan（6 段，downloaded=2949120）
        S7: segProgress 初值 = 2949120
        S8: from = startByte + 2949120 → 6 个 Range 请求从断点开始
```

## 13.3 暂停与恢复

```
用户点暂停 → Engine.pause → job.cancelAndJoin()
    runTask 抛 CancellationException
    → catch: Repo.setState(id, PAUSED)
    → finally: ticker 已取消；jobs.remove；pump()（补位）
    注意：被取消时不会执行 S9，所以不会误标 COMPLETED

用户点继续 → Repo.setState(id, QUEUED) ; Engine.pump()
    → pump 挑中 → start → runTask → 从 segment.downloaded 续
```

## 13.4 仅 Wi-Fi 被约束

```
[蜂窝网络] runTask S5: wifiOnly && !onWifi()
    → Repo.setState(id, PAUSED, "等待 Wi-Fi") ; 返回
    → start 的 finally: jobs.remove ; pump()
[切回 Wi-Fi] NetworkWatcher.onAvailable
    → 筛出 error=="等待 Wi-Fi" 的 PAUSED 任务
    → 置 QUEUED（error 被清空）
    → DownloadService.start ; Engine.pump()
[用户手动暂停的任务] error == null → 不在筛选结果里 → 不会被拉起
```

---

# 第 14 部分 · 显示格式化

> 看着琐碎，但**不写死数值规则，两个实现显示的大小和剩余时间就会不一样**。

## 14.1 `formatBytes(bytes)` → String

```
if (bytes < 0)    ⊥ "—"                                    // 未知大小的占位
if (bytes < 1024) ⊥ "<bytes> B"
units ← [KB, MB, GB, TB]
v ← bytes / 1024.0 ; i ← 0
while (v >= 1024 && i < 3): v ← v / 1024 ; i ← i + 1
⊥ v >= 100 ? "%.0f <units[i]>"                             // ≥100 不带小数
           : "%.1f <units[i]>"                             // 否则一位小数
```

样例（可直接当单测）：`-1 → "—"`；`999 → "999 B"`；`1024 → "1.0 KB"`；
`1048576 → "1.0 MB"`；`52428800 → "50.0 MB"`；`104333312 → "100 MB"`。

## 14.2 `formatEta(remainingBytes, bytesPerSec)` → String

```
if (bytesPerSec <= 0 || remainingBytes <= 0) ⊥ ""          // 空串 → UI 不显示该段
secs ← remainingBytes / bytesPerSec
if (secs < 60)   ⊥ "<secs> 秒"
if (secs < 3600) ⊥ "<secs/60> 分 <secs%60> 秒"
⊥ "<secs/3600> 时 <(secs%3600)/60> 分"
```

## 14.3 `firstUrl(text)` → String?

```
if (text 为 null / 空 / 全空白) ⊥ null
m ← 正则 https?://[^\s"'<>]+ 的第一个匹配
⊥ m 去掉尾部连续的 [. , ) ] " '] 字符
```

三处共用：分享意图、剪贴板文本、手动输入框——都靠它从自由文本里抠 URL。
**不要三处各写一个正则**，否则边界（末尾带句号、带右括号）处理会不一致。

## 14.4 ⚠️ 当前有一处重复实现

`DownloadService` 里有个私有 `fmt(bytes)`，规则与 `formatBytes` 完全相同
（`<1024` 显示 B、`≥100` 不带小数、否则一位小数），两处独立维护。

**重建时应合并为一个**，否则改了其中一处，会出现"通知里的速度"和"列表里的速度"格式不一致。

