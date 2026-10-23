# FreeDM

独立实现的 Android 下载管理器，与 1DM+ 的下载与浏览行为互操作，**零数据收集**，Material 3 界面。

> **项目性质（2026-10-23）**
> 本项目在开发过程中对目标软件进行了逆向分析，用于理解其功能和协议。分析所得信息仅用于实现互操作性功能。
> 本项目未经法律审查，未经第三方审计，不声称符合任何特定的法律合规开发标准。
> 本项目未复制目标软件的源代码或受版权保护的表达，但不能保证法律上无风险。
>
> 当前为单人维护；法律审查与独立验证尚未完成，相关记录未签发。
>
> **流程目标（2026-10-23）**：按隔离式全流程推进——分析、候选规格、法律审查、实现、
> 独立验证逐阶段留痕；角色隔离与单向信息流见 [`governance/`](governance/README.md)。
> 当前流程尚未完成，不以声明替代审查记录。
> 代码开发继续推进；法律状态存疑并留存记录。门禁只约束发布，不约束本地开发。
>
> **合规状态（2026-10-23）**：法律审查 `NOT_REVIEWED`，独立验证未执行；当前版本
> **不得对外发布**。治理入口 [`governance/README.md`](governance/README.md)，缺口见
> [`governance/gaps/GAP-REGISTER.md`](governance/gaps/GAP-REGISTER.md)。
>
> **规范来源**：[`docs/spec/`](docs/spec/) 是唯一权威；`docs/logic-spec.md` 等为
> INFORMATIVE 设计说明。所有需求/不变量/错误/验收使用稳定 `REQ-*`/`INV-*`/`ERR-*`/`ACC-*` ID。

## 相对 1DM+ 的差异

| | 1DM+ 18.2 | FreeDM |
|---|---|---|
| 权限 | 26 项 | **6 项** |
| 设备标识 | ANDROID_ID 明文上报 | 不读取 |
| 上报端点 | `apps2sd.info/idm_check`（13 字段） | **无** |
| 分析 SDK | Firebase 栈（FCM） | 无 |
| 本机标识 | ANDROID_ID + 品牌 + 型号指纹 | 随机 UUID，仅本地 |
| UI | 抽屉 + 底部混合、主题市场 | 纯 M3 底部导航 |
| 体积 | 45.6 MB | 1.3 MB（release，R8 后） |

## 功能

- **分段下载**：HTTP Range 多线程（1–16），自动探测 `Accept-Ranges`
- **断点续传**：分片级进度落 SQLite，`ETag`/`Last-Modified` 校验后才复用已下载数据
- **队列**：全局并发 1–8，FIFO，仅 Wi-Fi 约束，失败指数退避重试 3 次
- **限速**：全局令牌桶（0–10 MB/s）
- **通知**：前台进度条、全部暂停、完成/失败提示
- **列表**：单页四筛选（全部 / 进行中 / 已完成 / 错误），分段进度条铺满整行背景
- **已完成**：点行直接打开文件；右键菜单看详细信息、重新下载、删除
- **浏览器**：WebView + 媒体嗅探（DOM 扫描 + 网络层拦截），识别 HLS/DASH 清单；
  **多标签**，每个标签有自己的历史栈，后退/前进不串标签；
  **浏览历史**独立成页（单条删除 / 清空），点一条回到浏览页打开；
  **广告过滤**：内置域名名单（请求不联网更新），可在浏览页按站点关闭
- **起始页**：快捷方式卡片堆，上滑展开成单列信息流，按信息流方向滚动；
  回到卡片堆有两条手势 —— 划到最上面再往下拉、按住底部横杠往上推；
  卡片颜色可从预设里挑，也可以选「自动」跟随站点图标
- **分享接收**：`ACTION_SEND` 进来的一律建任务；在其他应用点开的链接按默认浏览器打开网页，只有能看出是文件的（apk/zip/mp4…）或 WebView 自己渲染不了的才自动进下载队列
- **剪贴板识别**：默认关闭，仅前台生效，不保存原文
- **导出**：可选复制到系统 Downloads（MediaStore）
- **诊断日志**：`Android/data/<包名>/files/logs/diag.log`，只记域名与错误码，设置页可关可清空

## 构建

```powershell
# 需要 JDK 17 与 Android SDK（sdk.dir 写在 local.properties）
.\gradlew assembleDebug      # app\build\outputs\apk\debug\app-debug.apk
.\gradlew assembleRelease    # app\build\outputs\apk\release\app-release-unsigned.apk
```

也可以在 Android Studio 里直接 Sync + Build，release 用 `Generate Signed APK` 选自己的 keystore。

- **applicationId**：`com.huangsy.freedm`（Java 包名仍为 `app.freedm`）
- **版本**：0.1.6（versionCode 7）
- **工具链**：AGP 8.5.2 / Kotlin 2.1.20 / Compose BOM 2024.10.01 / Gradle 8.7 / JDK 17
- **可复现性**：wrapper 与依赖版本全部写死（无动态版本号），同工具链下行为一致；APK 本身是非字节级可复现（含时间戳与签名）。首次构建需联网拉依赖，之后 `--offline` 可用。

## 结构

```
app/src/main/java/app/freedm/
  MainActivity.kt          单 Activity，三个底部 tab + 逐层返回
  data/  Model.kt Db.kt Repo.kt     数据模型 / SQLiteOpenHelper / 仓储
         SiteStore.kt              起始页快捷方式（JSON 存 prefs）
         HistoryStore.kt           浏览历史（跨标签、跨会话）
  engine/ Prober.kt Engine.kt       探测与文件名推导 / 分段下载调度
          RateLimiter.kt Exporter.kt 全局限速 / 导出到公共目录
  service/ DownloadService.kt Notifier.kt   前台服务 / 终态通知
  browser/ Sniffer.kt ClipboardWatcher.kt   媒体嗅探 / 剪贴板识别
  ui/ screens/ components/ theme/    Compose 界面；components/ 是分段进度条
      BrowserBus.kt                 历史页 → 浏览页的单发通道
tools/    range_server.py（Range 测试源）· parse_dump.py（UI 自动化取坐标）
          check_doc_coverage.py（机械核对代码常量是否都写进了文档）
docs/     README.md（文档导读：先规范、后设计）
          spec/（规范性：requirements / invariants / errors / privacy / constants）
          logic-spec.md（INFORMATIVE 设计：逐函数说明与伪代码）
          implementation-guide.md（INFORMATIVE：状态机 / 调用序列 / 线程模型 / 常量解释）
          acceptance.md（规范性验收：ACC-*，带可执行判定）
          known-pitfalls.md（INFORMATIVE：真实踩坑）
          ui-design.md（INFORMATIVE：界面设计）
          evidence-resume-server.log（续传实测的服务器原始日志）
governance/     治理：角色、信息流、门禁、证据、缺口、ID 注册表、审查模板
```

## 实现者自测（非独立验证）

> 以下结果由实现者执行，**不能替代 G5 独立验证**。独立评审者必须不是作者、且未阅读实现
> 文档或源码；当前 `REV-001` 尚未签发。详见 `docs/acceptance.md` 与 `governance/gates.md`。

测试源用 `tools/range_server.py`（本机 Range 服务器，记录每个请求），文件 50 MiB。

| 项 | 结果 |
|---|---|
| 构建 | `assembleDebug` 通过，APK 9.5 MB（debug）/ 1.3 MB（release） |
| 冷启动 | Android 11 x86_64 模拟器，无崩溃 |
| 单段小文件 | `README.md` 3808 B，内容完整 |
| **多线程分片** | 服务器收到 **6 个并发 Range**，首尾相接无重叠无空洞 |
| **并发写数据完整性** | sha256 `624bbe3f…bea8e` 与服务器端**逐字符一致** |
| **跨进程断点续传** | 杀进程后重启，6 段续传起点精确等于 `段起点＋已下载字节`，**零重下** |
| 续传后完整性 | sha256 仍与上同 |
| 分片进度落库 | 进程被杀后 DB 中 6 段各 `downloaded=2949120` |
| 自动恢复 | 仅 `am start`（不点任何按钮）即续传 |
| 全局限速 | 设 500 KB/s，实测 **459 KB/s**（回环不限速可达数十 MB/s） |
| 完成通知 | `dumpsys notification` 出现 `channel=download_done` |
| 分享接收 | `ACTION_SEND` → 弹出「新建下载」且 URL 已预填 |
| 真机冷启动 | Android 16 / arm64（荣耀 MAA-AN00），无崩溃 |
| 真机分段下载 | 48.9 MB / 8 段，落盘大小与远端 `Content-Length` 一致 |
| 起始页手势 | 上滑展开成信息流、划到顶下拉 / 横杠上推回到卡片堆，松手无跳变 |
| 标签历史 | 各标签后退互不影响；从网页返回起始页后标签同步清空，重进不回到旧网址 |
| 分段进度条 | 行背景逐段着色，完成后整行纯色（与下载中同色） |
| 已完成行点击 | 直接调系统「打开方式」，不再先进详情页 |
| 返回逐层 | 网页历史 → 起始页 → 下载页 → 退出 |
| 广告过滤 | 百度首页拦下 3 个请求，菜单显示「已拦 3」；对站点关闭过滤后不再拦，白名单落 prefs |
| 运行中删除任务 | 20 MB 慢速任务在 5% 时删除：分段连接立即断开（服务器侧 `ConnectionAborted`），无崩溃、无残留记录 |
| 隐私基线 | 权限 6 项；设备标识 API 零命中；无分析 SDK |

详细步骤与判定标准见 [`docs/acceptance.md`](docs/acceptance.md)。

## 已知限制

- **Bing 首页**在 WebView 里可能只渲染出一张背景图，内容不出来，尚未定位
- **HLS(`.m3u8`) 只作单文件下载**，无分片合并（UI 已提示）
- **UI 缺口**：添加下载时更改保存位置 —— 未实现（任务列表长按多选已加入，待真机确认）
- 起始页手势与动画仍在打磨，回收的手感会继续调
- 广告过滤只按域名拦截，不处理页面内的内联广告位；规则随安装包更新，不联网订阅
- 浏览器无密码管理、无主题市场
- 未做 torrent
- release 签名与 `proguard` 规则未实测
- 已实现但**未做运行时实测**：媒体嗅探、剪贴板识别、仅 Wi-Fi 约束、队列并发上限、飞行模式可用性

## 踩过的坑

见 [`docs/known-pitfalls.md`](docs/known-pitfalls.md)：分片进度落库、重试重复计数、
预分配文件使大小失效、服务器忽略 Range 必须报错、短读检测、MediaStore 只能先下后导出等 14 条。

## 许可

GPL-3.0
