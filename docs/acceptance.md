# 验收清单（Acceptance）

> `DOCUMENT-CLASS: NORMATIVE ACCEPTANCE`
> `BASELINE: SPEC-BASELINE-2026-10-23`
> `LEGAL-STATUS: NOT_REVIEWED`
> 每条必须有稳定 `ACC-*` ID；判定方法必须可由第三方复现。
> 现有 PASS/TODO 来自实现者自测，**尚不能作为独立验证证据**（REV-001 待签发）。
> 历史编号 A1–A18 / B1–B6 / P1–P8 保留为别名；权威 ID 为 `ACC-*`。
> 环境隔离：不得使用安装目标 APK 的 AVD；历史记录中的 `1DMPlus` 命名待复核（`GAP-010`/`GAP-021`）。

每条都给出**可执行的验证方法与判定标准**。状态只有三种：`PASS`（已实测通过）、
`TODO`（未实测）、`N/A`（不适用）。

环境基线：`1DMPlus` AVD（Android 11 / x86_64 / `adb root`）、Android SDK 36.0.0、
JDK 17、Gradle 8.7、AGP 8.5.2。

---

## A. 下载引擎

### ACC-BLD-001 · A1 构建 — `PASS`

```powershell
$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot"
gradle -p "D:\1DM+" assembleDebug --offline
```
判定：`BUILD SUCCESSFUL`，产出 `app\build\outputs\apk\debug\app-debug.apk`（约 9.7 MB）。

### ACC-DL-001 · A2 冷启动 — `PASS`

```powershell
adb install -r app-debug.apk
adb shell am start -n app.freedm/.MainActivity
adb logcat -d | findstr "FATAL EXCEPTION"
```
判定：进程存活、无 `FATAL EXCEPTION`、三个底部导航页（下载 / 浏览 / 设置）可切换。

### ACC-DL-002 · A3 单段下载 — `PASS`

用小于 16 MiB 的文件（分片门限是 `granule*2 = 16 MiB`）。
判定：文件落盘且大小正确。—— 实测 `README.md` 3808 B。

### ACC-DL-003 · A4 多线程分片 — `PASS`

```powershell
py tools\range_server.py 52428800 8000        # 50 MiB，日志记录每个 Range
# 触发下载 http://10.0.2.2:8000/test.bin
Get-Content tools\server2.log | Select-String "Range="
```
判定：服务器收到 **6 个并发 Range 请求**，区间**首尾相接、无重叠无空洞**、总和等于文件大小。

实测：
```
bytes=0-8738133          bytes=8738134-17476267
bytes=17476268-26214401  bytes=26214402-34952535
bytes=34952536-43690669  bytes=43690670-52428799
```

### ACC-DL-004 · A5 并发写入的数据完整性 — `PASS`

多段并发 `seek`+`write` 同一个 `RandomAccessFile`，最容易出静默损坏。

```powershell
adb shell run-as app.freedm sha256sum files/downloads/test.bin
```
判定：与服务器端打印的 `sha256=` **逐字符相等**。

实测：两侧均为 `624bbe3f61588f97cfaad1af50360bb8c5fc94774d3c15dbf471dcd42b9bea8e`。

### ACC-DL-005 · A6 断点续传跨进程重启 — `PASS`

```powershell
py tools\range_server.py 52428800 8000 --slow=300000   # 每连接 300 KB/s，来得及打断
# 触发下载 → 等 10 s → adb shell am force-stop app.freedm
# 查 DB 确认 segment.downloaded > 0
# adb shell am start -n app.freedm/.MainActivity（不带任何 intent，不点任何按钮）
# 看服务器日志里新增请求的 Range 起点
```
判定：重启后每个续传请求的起点 == `该段 start_byte + 该段已下载字节`，且**没有从段首重下**。

实测（每段已下载 2,949,120 B）：
```
段0  0          -> 2949120
段1  8738134    -> 11687254
段2  17476268   -> 20425388
段3  26214402   -> 29163522
段4  34952536   -> 37901656
段5  43690670   -> 46639790
```
续传完成后 sha256 仍与 A5 相同。

### ACC-DL-006 · A7 缺了它 A6 就不成立的落库 — `PASS`

判定：`segment.downloaded` 必须周期性落库，否则重启只能回退到段首。
实测（进程被杀后读 DB）：6 段各自 `downloaded=2949120`、`state=RUNNING`。

### ACC-DL-007 · A8 自动恢复 — `PASS`

判定：进程被杀时状态留在 `RUNNING`；启动时 `Repo.init()` 把 `RUNNING` 改写成 `QUEUED`，
而用户**手动暂停**的任务是 `PAUSED`、重启后保持暂停。二者语义不混淆。
关闭路径：设置里「自动恢复未完成任务」置关后，重启不再自动开始。

### ACC-DL-008 · A9 全局限速 — `PASS`

```powershell
# 把 speed_limit 写成 500000（字节/秒）后重启 app
# 触发下载，间隔 10 s 读两次 download.downloaded，算差值
```
判定：实测速率与设定值同量级（**459 KB/s vs 500 KB/s**）。
参照：本机回环不限速时可跑到数十 MB/s，差异显著，排除"限速没生效"的可能。

### ACC-DL-009 · A10 完成 / 失败通知 — `PASS`

```powershell
adb shell dumpsys notification --noredact | findstr "pkg=app.freedm"
```
判定：出现 `channel=download_done` 的记录。实测通过。

### ACC-DL-010 · A11 仅 Wi-Fi 约束 — 已实现，`TODO` 实测

需把模拟器切到蜂窝（关闭 Wi-Fi）后验证：任务应转 `PAUSED` 且 `error = "等待 Wi-Fi"`；
恢复 Wi-Fi 后**无需任何操作**自动继续。

实现要点（易错）：暂停时把 `"等待 Wi-Fi"` 写进 `download.error` 作为标记，
`NetworkWatcher` 的网络回调**只恢复带该标记的任务**，因此不会把手动暂停的任务一起拉起。
代码路径：`Engine.onWifi()` / `Engine.WIFI_WAIT` / `NetworkWatcher.start()`。

### ACC-DL-011 · A12 队列并发上限 — `TODO`

建 4 个任务、`max_concurrent=3`，判定同时处于 `RUNNING` 的不超过 3 个，
且第 4 个在前 3 个之一结束后**自动补位**（`Engine.pump()` 在该任务的 `finally` 里）。
代码路径：`Engine.pump()`。

### ACC-DL-012 · A13 文件被外部删除后续传 — `PASS`

```powershell
# 慢速源下到 ~10% 后
adb shell am force-stop com.huangsy.freedm.dev
adb shell rm -f /storage/emulated/0/Download/FreeDM/Other/slow.bin
adb shell am start -n com.huangsy.freedm.dev/app.freedm.MainActivity
adb shell cat /storage/emulated/0/Android/data/com.huangsy.freedm.dev/files/logs/diag.log
```

判定：日志出现 `TASK_FILE_LOST id=… onDisk=0 claimed=<已下载>`，且服务器侧新的 Range
**从段首开始**（如 `bytes=0-10485759`），不是从中途偏移。
实测：`onDisk=0 claimed=917504`，Range 回到段首。

### ACC-DL-013 · A14 断网 → 等待 → 自动恢复 — `PASS`

```powershell
# 任务下载中（建议 ≤ 100 KB/s 限速，留出操作时间）
adb shell svc wifi disable; adb shell svc data disable
# 等 30–40 s
adb shell svc wifi enable; adb shell svc data enable
```

判定：
1. 断网后任务转 `PAUSED`，`download.error = 等待网络`，日志 `TASK_NET_WAIT`。
2. 恢复网络后日志 `NET_AVAILABLE waiting=1` → 自动续传 → `TASK_DONE`，大小与远端一致。
实测：5.7 MB 任务断网于 3.99 MB，恢复后完成；DB 终态 `COMPLETED / 5761919 / 5761919`。

### ACC-DL-014 · A15 302 跨 host 重定向 — `PASS`

判定：`mirrors.aliyun.com/.../ubuntu-24.04.1-live-server-amd64.iso` 不再出现
`SEG_RETRY … HTTP 302`；探测能拿到真实大小（不再是 302 响应的 215 B）。
实测：跟到 `iso-osm.mirrors.aliyuncs.com` 后被明文策略拒绝（`Cleartext HTTP traffic … not permitted`）
—— 说明跟随成功，剩余是产品策略问题（见 `logic-spec.md` §20.4 缺口 6）。

### ACC-DL-015 · A16 数据库升级不清空记录 — `PASS`（代码审阅）

判定：`Db.onUpgrade` 不再包含 `DROP TABLE`；`onDowngrade` 不抛异常。
当前 `VERSION = 1` 且无 v2，无法实测迁移，故以代码审阅为准。

### ACC-DL-016 · A17 空间不足预检 — `TODO`

判定：把设备剩余空间压到小于目标文件，任务应在首字节前失败，`error` 形如
`空间不足（需 1.2 GB，剩 300 MB）`，而不是下到一半才 `ENOSPC`。
代码路径：`Engine.runTask` 的 `dir.usableSpace` 预检。

### ACC-DL-017 · A18 同名任务并发去重 — `TODO`

判定：同一 URL 连续创建两次（同一 `dir_path`），第二个任务的 `file_name` 应为
`<name> (1).<ext>`，两条记录的 `dir_path + file_name` 不重复。
代码路径：`Repo.create` → `uniqueName` / `nameInUse`。

---

## B. 浏览器与集成

### ACC-BR-001 · B1 分享接收 — `PASS`

```powershell
adb shell am start -a android.intent.action.SEND -t text/plain \
  --es android.intent.extra.TEXT 'https://example.com/f.zip' -n app.freedm/.MainActivity
```
判定：底部弹出「新建下载」，URL **已预填**。

### ACC-BR-002 · B2 媒体嗅探（DOM + 网络层） — `TODO`

在真实视频页打开嗅探面板，判定：能列出 `<video>`/`.m3u8`/网络层 `video/*` 资源，且同一媒体不重复。
代码路径：`Sniffer.PROBE_JS`、`BrowseScreen.shouldInterceptRequest`。

### ACC-BR-003 · B3 剪贴板识别 — `TODO`（需注入剪贴板内容）

判定：`clipboard_watch` 开启、app 在前台时复制一个 URL → 自动弹出「新建下载」；
**退到后台后复制 → 不应有任何反应**（`onPause` 已注销监听）。
注入剪贴板的可行手段：app 内输入框手动复制，或用 `service call clipboard`（各版本 transaction code 不同，不稳定）。

### ACC-BR-004 · B4 广告过滤 — `PASS`

```powershell
adb shell am start -n com.huangsy.freedm.dev/app.freedm.MainActivity
# 浏览 tab → 打开一个有统计脚本的页面（实测 https://www.baidu.com/）
# 右上 ⋮ → 观察「☑ 广告过滤（已拦 N）」
adb shell run-as com.huangsy.freedm.dev cat shared_prefs/settings.xml   # 看 adblock_allow
```

判定：
1. 菜单里拦截计数 **> 0**（实测百度首页 3 条：`hm.baidu.com` 等）。
2. 点「本站关闭过滤」后菜单变成「本站恢复过滤」，`settings.xml` 出现 `adblock_allow` 含该 host，重载后计数不再增长。
3. 再点「本站恢复过滤」→ 白名单清空（`<set name="adblock_allow" />`）。
4. 页面本身永远正常显示（主文档不拦）。

规则与匹配逻辑见 `docs/logic-spec.md` §18；名单文件 `app/src/main/assets/adblock_hosts.txt`。

### ACC-BR-005 · B5 诊断日志 — `PASS`

```powershell
adb shell am start -n com.huangsy.freedm.dev/app.freedm.MainActivity
# 浏览 tab → 打开任意页面（含视频的测试页：www.runoob.com/html/html5-video.html）
adb shell cat /storage/emulated/0/Android/data/com.huangsy.freedm.dev/files/logs/diag.log
```

判定：
1. 文件存在，每行形如 `10-17 21:42:01.209  PAGE_DONE  host=… ms=… sniffed=… net=… blocked=…`。
2. 至少出现 `APP_START`、`NET_TRANSPORT`、`PAGE_START`、`PAGE_DONE`、`SNIFF_DOM`。
3. 含 `<video>` 的页面应出现 `sniffed>0` 与 `SNIFF_DOM parsed>0`（实测菜鸟教程页：`sniffed=2 net=4 parsed=2`）。
4. 百度首页不应再出现 `PAGE_START utils` / `ERR_UNKNOWN_URL_SCHEME`（deeplink 已被拦）；出现 `NAV_BLOCKED` 属正常。
5. 设置页「诊断」显示路径与大小，点「清空诊断日志」后文件消失。

事件表与取日志方法见 `docs/logic-spec.md` §19。

### ACC-BR-006 · B6 视觉一致性 — `PASS`

判定（真机截图比对，规则见 `logic-spec.md` §21）：
1. 卡片堆三层可见：阴影递减 + 后层更暗；左右翻页后**两侧无残影**。
2. 底栏图标 22dp、标签 11sp，三个 tab 等宽；卡片 26dp、网格 18dp、下载行 12dp 圆角各归其位。
3. 下载页空状态：40dp 淡图标 + 标题 + 一行说明。
4. 设置 → 关于的版本号来自包信息（debug 显示 `0.1.2-dev`）。

实测（荣耀 MAA-AN00 / Android 16）：卡片堆层次与翻页残影修复、网格展开、空状态、版本号均符合。

---

## C. 隐私基线

| 编号 | 判定方法 | 状态 |
|---|---|---|
| ACC-PRV-001 · P1 零分析 SDK | `build.gradle.kts` 中 grep `firebase\|crashlytics\|umeng\|bugly\|adjust\|installreferrer` 应为空 | `PASS` |
| ACC-PRV-002 · P2 不读设备标识 | 源码 grep `ANDROID_ID\|getDeviceId\|getImei\|getSerial\|getMacAddress\|getSimCountryIso`，除隐私说明文案外应零命中 | `PASS` |
| ACC-PRV-003 · P3 无 InstallReferrer | 同上依赖检查 | `PASS` |
| ACC-PRV-004 · P4 本机标识为随机 UUID | 设置页展示的 `installId` 来自 `UUID.randomUUID()`，存 SharedPreferences | `PASS` |
| ACC-PRV-005 · P5 无额外出网 | 除用户输入的下载 URL 与浏览器导航外无网络调用 | `PASS`（代码审阅 + A3/A4 期间服务器日志只有下载请求） |
| ACC-PRV-006 · P6 断网可用 | 飞行模式下打开 app，列表/设置/历史应正常 | `TODO` |
| ACC-PRV-007 · P7 权限最小化 | `aapt2 dump permissions app-debug.apk` → 6 项 | `PASS` |
| ACC-PRV-008 · P8 隐私说明页 | 设置 →「隐私说明」逐条列出不收集项 | `PASS` |

权限对照：FreeDM **6 项** vs 1DM+ **26 项**。

---

## D. 已知未实现（不是 bug，是范围）

- HLS(`.m3u8`) 只作单文件下载，**无分片合并**
- 广告过滤只按域名拦截，**不处理页面内联广告位**；规则随安装包更新，不联网订阅
- 无密码管理、无主题市场
- 无 torrent
- release 签名与 `proguard` 规则未配置实测

> 以上范围性未实现已登记到 `crre/gaps/GAP-REGISTER.md`，不得以“已知限制”替代处置状态。
