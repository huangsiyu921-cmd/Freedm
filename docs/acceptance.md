# 验收清单（Acceptance）

每条都给出**可执行的验证方法与判定标准**。状态只有三种：`PASS`（已实测通过）、
`TODO`（未实测）、`N/A`（不适用）。

环境基线：`1DMPlus` AVD（Android 11 / x86_64 / `adb root`）、Android SDK 36.0.0、
JDK 17、Gradle 8.7、AGP 8.5.2。

---

## A. 下载引擎

### A1 构建 — `PASS`

```powershell
$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot"
gradle -p "D:\1DM+" assembleDebug --offline
```
判定：`BUILD SUCCESSFUL`，产出 `app\build\outputs\apk\debug\app-debug.apk`（约 9.7 MB）。

### A2 冷启动 — `PASS`

```powershell
adb install -r app-debug.apk
adb shell am start -n app.freedm/.MainActivity
adb logcat -d | findstr "FATAL EXCEPTION"
```
判定：进程存活、无 `FATAL EXCEPTION`、三个底部导航页（下载 / 浏览 / 设置）可切换。

### A3 单段下载 — `PASS`

用小于 16 MiB 的文件（分片门限是 `granule*2 = 16 MiB`）。
判定：文件落盘且大小正确。—— 实测 `README.md` 3808 B。

### A4 多线程分片 — `PASS`

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

### A5 并发写入的数据完整性 — `PASS`

多段并发 `seek`+`write` 同一个 `RandomAccessFile`，最容易出静默损坏。

```powershell
adb shell run-as app.freedm sha256sum files/downloads/test.bin
```
判定：与服务器端打印的 `sha256=` **逐字符相等**。

实测：两侧均为 `624bbe3f61588f97cfaad1af50360bb8c5fc94774d3c15dbf471dcd42b9bea8e`。

### A6 断点续传跨进程重启 — `PASS`

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

### A7 缺了它 A6 就不成立的落库 — `PASS`

判定：`segment.downloaded` 必须周期性落库，否则重启只能回退到段首。
实测（进程被杀后读 DB）：6 段各自 `downloaded=2949120`、`state=RUNNING`。

### A8 自动恢复 — `PASS`

判定：进程被杀时状态留在 `RUNNING`；启动时 `Repo.init()` 把 `RUNNING` 改写成 `QUEUED`，
而用户**手动暂停**的任务是 `PAUSED`、重启后保持暂停。二者语义不混淆。
关闭路径：设置里「自动恢复未完成任务」置关后，重启不再自动开始。

### A9 全局限速 — `PASS`

```powershell
# 把 speed_limit 写成 500000（字节/秒）后重启 app
# 触发下载，间隔 10 s 读两次 download.downloaded，算差值
```
判定：实测速率与设定值同量级（**459 KB/s vs 500 KB/s**）。
参照：本机回环不限速时可跑到数十 MB/s，差异显著，排除"限速没生效"的可能。

### A10 完成 / 失败通知 — `PASS`

```powershell
adb shell dumpsys notification --noredact | findstr "pkg=app.freedm"
```
判定：出现 `channel=download_done` 的记录。实测通过。

### A11 仅 Wi-Fi 约束 — 已实现，`TODO` 实测

需把模拟器切到蜂窝（关闭 Wi-Fi）后验证：任务应转 `PAUSED` 且 `error = "等待 Wi-Fi"`；
恢复 Wi-Fi 后**无需任何操作**自动继续。

实现要点（易错）：暂停时把 `"等待 Wi-Fi"` 写进 `download.error` 作为标记，
`NetworkWatcher` 的网络回调**只恢复带该标记的任务**，因此不会把手动暂停的任务一起拉起。
代码路径：`Engine.onWifi()` / `Engine.WIFI_WAIT` / `NetworkWatcher.start()`。

### A12 队列并发上限 — `TODO`

建 4 个任务、`max_concurrent=3`，判定同时处于 `RUNNING` 的不超过 3 个，
且第 4 个在前 3 个之一结束后**自动补位**（`Engine.pump()` 在该任务的 `finally` 里）。
代码路径：`Engine.pump()`。

---

## B. 浏览器与集成

### B1 分享接收 — `PASS`

```powershell
adb shell am start -a android.intent.action.SEND -t text/plain \
  --es android.intent.extra.TEXT 'https://example.com/f.zip' -n app.freedm/.MainActivity
```
判定：底部弹出「新建下载」，URL **已预填**。

### B2 媒体嗅探（DOM + 网络层） — `TODO`

在真实视频页打开嗅探面板，判定：能列出 `<video>`/`.m3u8`/网络层 `video/*` 资源，且同一媒体不重复。
代码路径：`Sniffer.PROBE_JS`、`BrowseScreen.shouldInterceptRequest`。

### B3 剪贴板识别 — `TODO`（需注入剪贴板内容）

判定：`clipboard_watch` 开启、app 在前台时复制一个 URL → 自动弹出「新建下载」；
**退到后台后复制 → 不应有任何反应**（`onPause` 已注销监听）。
注入剪贴板的可行手段：app 内输入框手动复制，或用 `service call clipboard`（各版本 transaction code 不同，不稳定）。

### B4 广告过滤 — `PASS`

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

规则与匹配逻辑见 `docs/logic-spec.md` §19；名单文件 `app/src/main/assets/adblock_hosts.txt`。

### B5 诊断日志 — `PASS`

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

事件表与取日志方法见 `docs/logic-spec.md` §20。

---

## C. 隐私基线

| 编号 | 判定方法 | 状态 |
|---|---|---|
| P1 零分析 SDK | `build.gradle.kts` 中 grep `firebase\|crashlytics\|umeng\|bugly\|adjust\|installreferrer` 应为空 | `PASS` |
| P2 不读设备标识 | 源码 grep `ANDROID_ID\|getDeviceId\|getImei\|getSerial\|getMacAddress\|getSimCountryIso`，除隐私说明文案外应零命中 | `PASS` |
| P3 无 InstallReferrer | 同上依赖检查 | `PASS` |
| P4 本机标识为随机 UUID | 设置页展示的 `installId` 来自 `UUID.randomUUID()`，存 SharedPreferences | `PASS` |
| P5 无额外出网 | 除用户输入的下载 URL 与浏览器导航外无网络调用 | `PASS`（代码审阅 + A3/A4 期间服务器日志只有下载请求） |
| P6 断网可用 | 飞行模式下打开 app，列表/设置/历史应正常 | `TODO` |
| P7 权限最小化 | `aapt2 dump permissions app-debug.apk` → 6 项 | `PASS` |
| P8 隐私说明页 | 设置 →「隐私说明」逐条列出不收集项 | `PASS` |

权限对照：FreeDM **6 项** vs 1DM+ **26 项**。

---

## D. 已知未实现（不是 bug，是范围）

- HLS(`.m3u8`) 只作单文件下载，**无分片合并**
- 广告过滤只按域名拦截，**不处理页面内联广告位**；规则随安装包更新，不联网订阅
- 无密码管理、无主题市场
- 无 torrent
- release 签名与 `proguard` 规则未配置实测
