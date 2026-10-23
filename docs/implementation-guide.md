# 实现指南 — 各部分怎么接起来

> `DOCUMENT-CLASS: INFORMATIVE DESIGN`
> `BASELINE: SPEC-BASELINE-2026-10-23`
> `AUTHORITY: docs/spec/`（本文不产生规范约束）
> `LEGAL-STATUS: NOT_REVIEWED`

`docs/spec/requirements.md` 描述"系统必须表现成什么样"，`docs/ui-design.md` 描述"界面怎么设计"。
**本文描述"代码怎么接"** —— 状态由谁能改、每个用户动作的完整调用序列、线程模型、
以及影响可观察行为的常量解释。常量权威值见 `docs/spec/constants.md`；本文若与规范冲突，
以规范为准并登记 `GAP-*`。

只读前两份文档能写出大致功能，但会在下面这些地方分叉：状态迁移写错导致自动恢复误伤、
调用序列漏一步导致按钮没反应、常量各取各的值导致行为不一致。

---

## 1. 状态机：谁能把 X 改成 Y

```
                    ┌──────────── 用户「继续」/重试 ────────────┐
                    │        resumeAll() / NetworkWatcher      │
                    ▼                                          │
 (新建)──► QUEUED ──► RUNNING ──► COMPLETED                      │
            ▲           │                                        │
            │           ├──► FAILED ─────────────────────────────┘
            │           │
            │           └──► PAUSED ────────────────────────────┘
            │                 ▲
            └── 进程重启 ──────┘（仅 RUNNING→QUEUED，PAUSED 不动）
```

| 从 | 到 | 触发者 | 条件 |
|---|---|---|---|
| — | `QUEUED` | `Repo.create()` | 用户新建任务 |
| `QUEUED` | `RUNNING` | `Engine.pump()` → `runTask()` 首行 | 活跃任务数 < `max_concurrent` |
| `RUNNING` | `PAUSED` | `Engine.pause()` | 用户点暂停（内部 `cancelAndJoin`） |
| `RUNNING` | `PAUSED` | `runTask()` 自身 | 仅 Wi-Fi 但当前非 Wi-Fi，同时把 `error` 写成 `"等待 Wi-Fi"` |
| `RUNNING` | `COMPLETED` | `runTask()` 末尾 | 全部分片完成 **且** `file.length() >= total` |
| `RUNNING` | `FAILED` | `Engine.start()` 的 catch | 任一分片 3 次重试全失败 / 大小不符 / 探测抛异常 |
| `PAUSED` | `QUEUED` | 用户点继续、`resumeAll()` | 无条件 |
| `PAUSED` | `QUEUED` | `NetworkWatcher` 网络回调 | **仅当 `error == "等待 Wi-Fi"`** |
| `FAILED` | `QUEUED` | 用户点重试 | 无条件 |
| 任意 | 记录消失 | `Repo.delete()` | 直接删行，**没有中间态** |
| `RUNNING` | `QUEUED` | `Repo.init()`（进程启动） | 崩溃前在跑的任务 |
| `PAUSED` | `PAUSED` | `Repo.init()` | 用户手动暂停的**必须保持不动** |

### 没有「取消」态

状态只有五个。删除任务**直接删记录**，不经过中间态。早期版本曾定义 `CANCELED`，
但没有任何代码路径写入它，已从枚举中删除——重建时不要凭空设计「取消」流程。

---

## 2. 调用约定：每个动作的完整序列

这一段最容易漏。少一步的表现是"按钮没反应"或"没有通知"。

### 2.1 新建下载

```kotlin
val probe = runCatching { Prober.probe(url) }.getOrNull()      // 失败可为 null，仍能建任务
val name  = FileNamer.uniqueIn(dir, FileNamer.sanitize(suggested ?: "download"))
val id    = Repo.create(url, name, dir.absolutePath, threads, wifiOnly, probe)
DownloadService.start(context)   // ← 漏掉这行：没有通知，且后台不保证存活
Engine.start(id)                 // ← 用户显式点击，直接启动
```

两个坑：

- `Engine.start()` **不检查并发上限**，用户点「开始下载」就立刻起。只有 `pump()` 才守上限。
- `probe` 失败不要拦着不让建任务；下载时 `runTask` 会重新探测。

### 2.2 继续 / 重试

```kotlin
Repo.setState(id, TaskState.QUEUED)   // 必须
Engine.pump()                          // 必须
```

**两步缺一不可**：只 pump 不改状态 → 调度器看不到它；只改状态不 pump → 没人启动它。
（`Engine.resumeAll()` 是这两步的批处理版。）

### 2.3 暂停

```kotlin
Engine.pause(id)
```

内部 `cancelAndJoin` 当前 Job；若该任务本来没在跑，则直接置 `PAUSED`。

### 2.4 删除

```kotlin
Repo.delete(id, deleteFiles = true)   // true=连文件一起删；false=只删记录
```

### 2.5 应用启动

```kotlin
Repo.init(context)                    // RUNNING → QUEUED
consumeIntent(intent)                 // 分享/VIEW 进来的 URL → ShareBus
NetworkWatcher.start(context)         // 注册网络回调
if (auto_resume && Repo.tasks.any { it.state == QUEUED }) {
    DownloadService.start(context)
    Engine.pump()
}
```

顺序不能换：必须先 `Repo.init` 完成 `RUNNING→QUEUED` 迁移，`pump()` 才看得到待恢复任务。

### 2.6 「全部开始 / 全部暂停」

```kotlin
Engine.resumeAll()   // PAUSED → QUEUED 后 pump
Engine.pauseAll()
```

---

## 3. 数据流与线程模型

```
        ┌─────────────┐
        │  SQLite     │  ← 唯一真相源（downloads.db）
        └──────┬──────┘
               │ 全部读写经 Repo，内部一把锁
        ┌──────▼──────┐
        │   Repo      │
        └──────┬──────┘
               │ Repo.tasks: StateFlow<List<DownloadTask>>
               │ （每次写操作后 refresh() 全量重查）
        ┌──────▼──────┐        ┌──────────────────────────┐
        │  Compose UI │◄───────│ Engine.progress: StateFlow│
        │collectAsState│        │ (内存态，500ms 刷新)      │
        └─────────────┘        └──────────────────────────┘
```

- 每个任务一个 Job（`ConcurrentHashMap<Long, Job>` 保证同一任务不会跑两份）
- Job 跑在 `Dispatchers.IO`；每任务再起一个 ticker 子协程（500 ms 心跳），任务结束 `cancel`
- 分片在任务内 `coroutineScope { plan.forEach { launch(IO) } }` 并发
- 写文件必须 `synchronized(raf) { seek; write }` —— `seek`+`write` 不是原子操作
- 任务进入终态时 `finally` 里 `pump()` 补位，维持并发数

**WebView 线程（浏览器）**

- `shouldInterceptRequest` 跑在 WebView 自己的线程池：只读 `prefs`（线程安全）+ 往
  `ConcurrentLinkedQueue` 里放候选，**不碰 Compose 状态**
- 主线程每 500 ms 抽干队列、更新拦截计数；页面切换时清空队列
- 广告拦截的判定同样只读 prefs，命中后返回空 `WebResourceResponse`

**关键不变量**

- `download.downloaded` 会**略微超前**于 `sum(segment.downloaded)`（前者 500 ms 刷、后者 1 s 或 1 MiB 刷）。这是正常的，**续传只读 segment**。
- `segment.state = RUNNING` 表示"有进度、当前没在跑"，与任务级状态无关。
- 一个任务的 `segment` 行由 `planSegments()` 一次性写入，之后只更新 `downloaded`。

---

## 4. 常量表（权威值见 `docs/spec/constants.md`）

> 下表解释常量的用途；**规范性值以 `docs/spec/constants.md` 的 `CONST-*` 为准**。
> 修改常量必须先改规范并记录 `CTRL-*`，不得只改代码。

| 常量 | 值 | 用途 |
|---|---|---|
| 探测连接超时 / 读超时 | 20 s / 20 s | `Prober` |
| 下载连接超时 / 读超时 | 20 s / 30 s | `fetchSegment` |
| 读缓冲 | 64 KiB | 所有流读取 |
| 分片粒度 | 8 MiB | `buildPlan` |
| 分片上限 | 64 段 | `buildPlan` |
| 不分段阈值 | `size < 2 × 8 MiB` | `buildPlan` |
| 单请求重试 | 3 次 | `fetchSegment` |
| 重试退避 | 1 s / 2 s / 4 s（`1000 shl (attempt-1)`） | `fetchSegment` |
| 进度流刷新 | 500 ms | ticker |
| 任务级落库 | 500 ms | ticker |
| 分片级落库 | 1 s **或** 1 MiB，取先到 | ticker |
| 默认线程数 | 8（1–16） | `Engine.defaultThreads()` |
| 默认并发 | 3（1–8） | `Engine.maxConcurrent()` |
| 速度窗口 | 单次 tick 差值 × 1000 / 间隔 | 测速 |
| 文件名上限 | 120 字符 | `FileNamer.sanitize` |
| 重名后缀 | ` (1)`…` (999)` | `FileNamer.uniqueIn` |
| 嗅探单次上限 | 50 条 | `Sniffer.PROBE_JS` |
| 嗅探结果上限 | 400 条 | `BrowseScreen` 的 `MAX_SNIFFED` |
| 嗅探视口阈值 | `clientWidth/Height > 100` | `Sniffer.PROBE_JS` |
| favicon 读取上限 | 512 KiB | `FaviconColor` |
| favicon 连接/读超时 | 6 s / 6 s | `FaviconColor` |
| 广告过滤默认 | 开启 | `AdBlocker.PREF_ENABLED` |
| 广告规则文件 | `assets/adblock_hosts.txt` | `AdBlocker.ASSET` |
| 前台通知 id | 1001 | `DownloadService` |
| 终态通知 id | 任务 id | `Notifier` |
| 数据库 | `downloads.db`，version 1 | `Db` |
| 通知渠道 | `download_progress`(LOW, 静音) / `download_done`(DEFAULT) | `DownloadService` |
| 限速令牌桶容量 | 1 秒的量 | `RateLimiter` |

---

## 5. 模块职责（重建时按这个拆文件）

| 文件 | 职责 | 依赖 |
|---|---|---|
| `data/Model.kt` | `TaskState`、`DownloadTask`、`Segment`、`ProbeResult` 数据类 | 无 |
| `data/Db.kt` | `SQLiteOpenHelper` + 游标映射 + `ContentValues` | 无 |
| `data/Repo.kt` | 唯一 DB 入口（加锁）、`tasks`/`segments` StateFlow、`buildPlan` | Db |
| `engine/Prober.kt` | 能力探测 + `FileNamer`（文件名推导与清洗） | — |
| `engine/Engine.kt` | 任务调度、分片下载、重试、进度、终态通知与导出 | Repo, Prober, Notifier, Exporter, RateLimiter |
| `engine/RateLimiter.kt` | 全局令牌桶 | — |
| `engine/Exporter.kt` | 完成后复制到 MediaStore Downloads | Repo |
| `engine/NetworkWatcher.kt` | 网络恢复时只唤醒 `WIFI_WAIT` 任务 | Repo, Engine |
| `service/DownloadService.kt` | 前台服务 + 进度通知 | Engine, Repo |
| `service/Notifier.kt` | 完成/失败通知 + FileProvider 打开文件 | — |
| `browser/Sniffer.kt` | 嗅探脚本常量 + 结果解析 + 类型判定 | — |
| `browser/AdBlocker.kt` | 域名名单加载与匹配、站点白名单、拦截计数 | Repo |
| `browser/ClipboardWatcher.kt` | 前台剪贴板监听（默认关） | Repo |
| `ui/*` | Compose 界面，只通过 Repo/Engine 的公开方法操作 | 全部 |

---

## 6. 已知实现缺陷（照文档重建时可以顺手修掉）

| 缺陷 | 说明 |
|---|---|
| 嗅探网络层判不准 | `shouldInterceptRequest` 拿不到响应体，只能按 URL 后缀猜；真实 MIME 要等 DOM 扫描补 |
| HLS 未合并 | `.m3u8` 当普通文件下，拿到的是清单文本而非视频 |
| 通知暂停按钮只停全部 | 前台通知只有「全部暂停」，没有单任务控制 |
| 完成通知不定位任务 | `PendingIntent` 指向 MainActivity 首页，不落到具体任务详情 |

> 已修：`CANCELED` 死状态（已从枚举删除）、终态通知 id 撞车（改用任务 id）。
