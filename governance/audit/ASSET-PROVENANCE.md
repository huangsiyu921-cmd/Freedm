# 资产来源清单与污染影响评估

> `AUDIT-ID: ASSET-PROVENANCE-2026-10-23`
> `ROLE: ROLE-AUDIT（只读审计；未修改 app/src 与 docs/spec 正文）`
> `BASELINE: 3447557（Clean）；CAND-DL-001 现状（Dirty）`
> `SCOPE: D:\1DM+（Clean） + <DIRTY_WORKSPACE>（Dirty）`
> `LEGAL-STATUS: NOT_REVIEWED`
> `结论: 资产来源可分级，但发布门禁仍为 BLOCKED；本文件不构成法律意见。`
> 2026-10-23 单人模式：`GAP-006/007/020` 已转 `DEFERRED`；本文件的分析结论不变，不构成法律判定。

---

## 0. 结论摘要

1. **StartPage 卡片堆 / 展开 / 回收动画可判为 Clean 原创**，登记 `DEC-001`。依据见 §3.1；
   该结论是审计判定，不是法律结论，COUNSEL 仍可复核。
2. **SegmentProgressBar 不是原创**：其几何、1 px 缝规则、尺寸链与 D1 文档逐条对应，
   归入 `GAP-006` / `GAP-020`，状态维持 **BLOCKED**。
3. **FileKind 扩展名表来源待判定**：扩展名集合本身接近事实性数据，但分类顺序、
   家族划分与“镜像归其它”的编排与 D1 文档一致，归入 `GAP-007`，状态维持 **BLOCKED**。
4. **INC-001 / INC-012 的影响面需要复核**：候选包已补 `OBS-001..018`，并重写了
   `REQ-UI-001..011`、`REQ-SH-005`、`REQ-BR-007..012` 的来源映射；但 AUDIT 尚未复核
   OBS 与条款的对应关系，G0/G1 仍未通过。`GAP-026` 维持 **BLOCKED**（复核未完成）。
5. 受影响实现模块的独立性同样不能证明，标 **BLOCKED**（`GAP-027`）。
6. `LEG-001` 与 `REV-001` **均不具备签发条件**：角色未任命、无独立性声明、
   仓库提交身份单一。未在 `governance/reviews/` 预置 `LEG-*.md` / `REV-*.md`，以免被自动检查
   误计为“已签发”。资格检查见 `LEG-001-READINESS.md`、`REV-001-READINESS.md`。
7. `python governance/tools/governance_check.py`：**INTEGRITY PASS**；**GATE BLOCKED**。
   本次新增 2 个 Critical BLOCKED 后，门禁阻塞项由 9 个如实增至 **11 个**。未改任何状态求绿。

---

## 1. 判定口径

| 判定 | 含义 | 证据要求 |
|---|---|---|
| **原创** | 未在 Dirty 现存规格/隔离区找到对应描述，且有独立设计或迭代证据 | Dirty 全库检索 + 设计文档 + 代码历史/注释 |
| **依据候选规格** | 行为来自 `CAND-DL-001`，但该候选规格未经法律审查 | 条款级章节映射 |
| **待判定** | 存在对应关系或来源可疑，需 COUNSEL 判定是否构成受保护表达复制 | 对应章节 + 哈希；不得据此放行 |
| **非原创** | 结构/几何/编排与 D1 文档逐条对应 | 逐条对照记录 |
| **阻断（未独立）** | 反向信息流可能影响，无法证明独立 | 影响面清单 + 无 `OBS-*` 追溯 |

> 本文中的 `AP-xx` 是审计工作表编号，**不是** `registry/ids.csv` 中的治理 ID。

---

## 2. 资产来源清单（按模块）

| AP | 资产 / 模块 | Clean 路径 | 判定 | 依据 | 关联 | 证据强度 |
|---|---|---|---|---|---|---|
| AP-01 | 下载引擎、分片计划、探测 | `engine/Engine.kt`、`engine/Prober.kt`、`engine/DownloadLinks.kt`、`data/Repo.kt` | 依据候选规格（未批准） | CAND-DL-001 §1.2–§1.5 | GAP-002 / GAP-027 / REQ-DL-007..032 | B |
| AP-02 | 分片级进度、落库、续传锚点 | `engine/Engine.kt`、`data/Repo.kt` | 待判定（阻断） | 候选规格 §1.4 + 隔离区 §8 的实现细节 | GAP-027 / REQ-DL-020..025 | C |
| AP-03 | 全局限速 | `engine/RateLimiter.kt` | 待判定（阻断） | 候选规格 §1.5；隔离区 §7 含 Clean 实测速率 | GAP-027 / REQ-DL-031 | C |
| AP-04 | 文件名推导、保存与导出 | `engine/Prober.kt`、`engine/Exporter.kt` | 依据候选规格 + 待判定 | CAND-DL-001 §1.6/§4；隔离区 §8 含导出实现细节 | GAP-027 / REQ-DL-033..038 | B/C |
| AP-05 | 前台服务与通知 | `service/DownloadService.kt`、`service/Notifier.kt` | 依据候选规格 | CAND-DL-001 §1.8；渠道命名属本实现细节 | REQ-DL-044 | B |
| AP-06 | 数据库与持久化 | `data/Db.kt`、`data/Model.kt`、`data/Repo.kt`、`data/HistoryStore.kt`、`data/SiteStore.kt` | 依据候选规格（未批准） | CAND-DL-001 §5；schema 与候选规格一致 | GAP-004 / REQ-ST-001..004 | B |
| AP-07 | 浏览器内核与媒体嗅探 | `ui/screens/BrowseScreen.kt`、`browser/Sniffer.kt` | 待判定（阻断） | 候选规格 §2；隔离区 §8 含单次扫描上限等实现细节 | GAP-027 / REQ-BR-001..004 | C |
| AP-08 | 浏览器菜单、历史、工具栏 | `ui/screens/BrowseScreen.kt`、`ui/screens/HistoryScreen.kt`、`ui/BrowserBus.kt` | 待判定（追溯失效） | `ui-design.md §3.1` 有独立设计，但 REQ 来源章节错误 | GAP-026 / REQ-BR-007..009 | C |
| AP-09 | 广告过滤 | `browser/AdBlocker.kt`、`assets/adblock_hosts.txt` | 待判定 | `logic-spec §18` 与 D1 广告笔记存在策略对应；实现刻意缩小能力 | GAP-016 / GAP-020 | C |
| AP-10 | 剪贴板监听 | `browser/ClipboardWatcher.kt` | 待判定（阻断） | 候选规格 §3.2；隔离区 §8 含去重细节 | GAP-027 / REQ-SH-004 | C |
| AP-11 | 分享接收 | `MainActivity.kt`、`ui/ShareBus.kt` | 依据候选规格 | CAND-DL-001 §3.1 | REQ-SH-001..003 | B |
| AP-12 | 诊断日志 | `diag/Diag.kt` | 待判定（追溯失效） | REQ-SH-005 的来源 §7 已删除；行格式为独立设计 | GAP-026 | C |
| AP-13 | 分段进度条 | `ui/components/SegmentProgressBar.kt` | **非原创** | 与 D1 `ui-progressview.md` §3 逐条对应 | **GAP-006 / GAP-020** | A（对应明确） |
| AP-14 | 文件类型表与判定顺序 | `data/FileKind.kt` | **待判定** | 与 D1 `file-types-and-folders.md` §2–§3 的集合/顺序/编排对应 | **GAP-007** | B |
| AP-15 | 起始页卡片堆、展开、回收动画 | `ui/screens/StartPage.kt`、`data/SiteStore.kt`、`browser/FaviconColor.kt`、`ui/theme/Tokens.kt` | **原创** | Dirty 全库与隔离区检索无对应；代码注释记录多轮交互缺陷修复；`ui-design.md` 未描述该资产 | **DEC-001** / GAP-026（REQ-UI-005 追溯） | B+ |
| AP-16 | 主题、颜色、动效令牌 | `ui/theme/Theme.kt`、`ui/theme/Tokens.kt` | 原创 | M3 动态取色 + 自定义令牌；`ui-design.md §5` | — | B |
| AP-17 | 文件类型图标 | `res/drawable/ic_kind_*.xml`、`ic_file.xml` | 原创（待视觉复核） | 单色 vector、运行时着色；未使用原始资源名 | GAP-003 观察项 | B |
| AP-18 | 界面文案 | `res/values/strings.xml` | 原创（未做逐字比对） | 中文文案为独立撰写；未做与原始产品的逐字比对 | GAP-003 观察项 | C |
| AP-19 | 构建、清单、隐私基线 | `build.gradle.kts`、`AndroidManifest.xml`、`res/xml/file_paths.xml` | 原创配置 | 权限最小化按 `PRV-007`；无分析 SDK | PRV-001..010 | A（静态检查） |
| AP-20 | 格式化与通用工具 | `ui/Format.kt`、`ui/BrowserBus.kt` | 原创 / 通用工程实现 | 纯函数与事件总线；`logic-spec §14` 自述有重复实现 | GAP-025 | B |

证据强度：A = 有直接对照/哈希；B = 有设计文档或代码证据；C = 仅排除性证据，需 COUNSEL 复核。

---

## 3. 三项重点资产的判定依据

### 3.1 StartPage 卡片堆 / 展开 / 回收动画 → 原创，`DEC-001`

支持“原创”的事实：

- Dirty `specs/*.md`（含 `_quarantine/`）按 `卡片 / card / deck / 快捷 / 起始页 / 动画 / 手势`
  检索，仅命中一条与本资产无关的“无动画”描述；没有卡片堆、展开、回收、翻页的设计或行为记录。
- `StartPage.kt` 的注释记录了本实现自己的缺陷修复史（翻页残影、双写偏移、fling 回弹、
  抓手条与列表手势冲突），这些是本项目的迭代产物，不是从外部规格一次照搬的结构。
- `docs/ui-design.md` 没有描述起始页卡片堆；`REQ-UI-005` 虽要求该能力，但其来源
  `CAND-DL-001 §8` 已被删除（见 §4），因此该 REQ 不能作为“来自 Dirty”的证据。
- 该资产依赖本项目的 `SiteStore`、`FaviconColor`、`Motion` 令牌，属于本实现自有的组合。

限制：

- 不能排除 Dirty 曾以口头或其他未留痕方式描述过类似交互；本判定是**基于现存证据的审计判定**。
- `DEC-001` 是描述性设计决策，不产生规范约束；若 COUNSEL 认定存在实质相似，应撤销该判定并重设计。

### 3.2 SegmentProgressBar → 非原创

对照 D1 `ui-progressview.md §3`（SHA-256 `eb7da41c…`）与 `SegmentProgressBar.kt`：

| 对照点 | 结果 |
|---|---|
| 整行背景式进度（不是独立细条） | 一致 |
| 等宽切片、按文件顺序排列 | 一致 |
| 前一段未满时在当前段起点留 1 px 缝 | 一致 |
| 前一段已满时取消缝、两段连成一片 | 一致 |
| 有缝分支的填充宽度不减去那 1 px | 一致 |
| 单段退化为普通水平条 | 一致 |
| 行内尺寸链（图标、字号、间距、行高） | `logic-spec §15.4` 与 D1 §5 对应 |

**判定：非原创。** 这不是“行为/功能”层面的必要实现，而是具体几何与绘制的选择。
在 `GAP-006` / `GAP-020` 关闭前，该模块不得进入发布包。

### 3.3 FileKind 扩展名表 → 待判定

- 扩展名到文件类别的映射具有事实性成分（例如某扩展名通常属于音频/视频），
  单一扩展名本身通常不构成受保护表达。
- 但以下属于**选择与编排**，需要 COUNSEL 判断：
  - 九个类别的划分与顺序；
  - 哪些扩展名被收录、哪些被排除；
  - “镜像类归入其它”的例外；
  - 字幕优先、音频先于视频的判定顺序。
- 这些编排与 D1 `file-types-and-folders.md §2–§3`（SHA-256 `b6d1c274…`）存在可追溯对应。

**判定：待判定，维持 `GAP-007` BLOCKED。** 若 COUNSEL 认定编排受保护，应独立重建分类集合
与顺序，并登记新的 `DEC-*`；不得以“事实性数据”概括整张表。

---

## 4. INC-001 / INC-012 影响范围

### 4.1 事实基础

- `CAND-DL-001` 现只保留 §1–§6，§7 是“已移除的违规内容”说明，§8 不存在。
- 原 §7（实现状态）与原 §8（实现细节约定）已隔离到 Dirty `specs/_quarantine/`
  （隔离前快照见 `EV-G1-009`，tree SHA-256 `306c371e…`）。
- 原 §7 含 Clean 的实测数字与实现状态；原 §8 含 Clean 的模块行为约定。
- 候选包已登记 `OBS-001..018`；`registry/ids.csv` 含对应条目。但观察方法、环境与证据
  尚未经 AUDIT 复核，G2 仍不满足。

### 4.2 受影响的需求条款

| 条款组 | 原来源 | 现状 | 独立性判定 |
|---|---|---|---|
| `REQ-UI-001..011` | `CAND-DL-001 §8` | §8 已删除 | **BLOCKED**（无法证明独立） |
| `REQ-SH-005` | `CAND-DL-001 §7` | §7 已删除（且原 §7 是实现状态） | **BLOCKED** |
| `REQ-BR-007..012` | `CAND-DL-001 §3 / §6` | §3 是分享/剪贴板，§6 是隐私基线，与内容不符 | **BLOCKED**（来源映射失效） |
| `REQ-BR-001..004` | `CAND-DL-001 §2` | 章节存在，但 §8 含单次扫描上限/去重实现细节 | 待判定，随 `GAP-027` |
| `REQ-DL-012..015` | `CAND-DL-001 §1.3` | 章节存在；§7/§8 含同一组数值的 Clean 实测 | **BLOCKED** |
| `REQ-DL-020..025` | `CAND-DL-001 §1.4` | §8 复述分片级落库与续传约定 | **BLOCKED** |
| `REQ-DL-026..032` | `CAND-DL-001 §1.5` | §7 含“并发/补位/仅 Wi-Fi 已实现”与限速实测 | **BLOCKED** |
| `REQ-DL-041..043` | `CAND-DL-001 §1.8` | §7/§8 含 500 ms、3 秒窗口、实测速率 | **BLOCKED** |
| `REQ-DL-001..011、016..019、033..040、044` | `CAND-DL-001 §1` / §4 | 章节存在；候选包已补 `OBS-*`，但复核未完成；整包曾被 §7 校准 | 待判定；不得单独放行 |
| `REQ-ST-001..006` | `CAND-DL-001 §5` / `PRV-004` | 章节存在；§8 含 DB 线程安全约定 | 待判定，随 `GAP-027` |
| `REQ-SH-001..004` | `CAND-DL-001 §3` | 章节存在；§8 含剪贴板去重细节 | 待判定，随 `GAP-027` |

**总体结论：当前没有任何一条需求能以 `OBS-*` 证明其观察独立性。**
因此不能只把少数条款标 BLOCKED 而放行其余条款；`GAP-027` 以“整包独立性未证明”登记。

### 4.3 受影响的实现模块

| 模块 | 受影响原因 | 判定 |
|---|---|---|
| `engine/Engine.kt`、`data/Repo.kt` | 分片、落库、续传、并发、补位 | **BLOCKED**（无法证明独立） |
| `engine/RateLimiter.kt` | 限速实测数字曾回流 | **BLOCKED** |
| `ui/components/SegmentProgressBar.kt` | 几何直接对应 | **BLOCKED**（GAP-006/020） |
| `data/FileKind.kt` | 分类编排对应 | **BLOCKED**（GAP-007） |
| `browser/Sniffer.kt`、`ui/screens/BrowseScreen.kt` | 扫描上限/去重细节 | **BLOCKED** |
| `browser/ClipboardWatcher.kt` | 去重细节 | **BLOCKED** |
| `diag/Diag.kt` | REQ 来源章节已删除 | **BLOCKED**（追溯） |
| `ui/screens/*`（起始页除外） | REQ-UI-* 来源失效 | **BLOCKED**（追溯） |
| `ui/screens/StartPage.kt` | 无对应描述 | 原创（`DEC-001`），但 REQ 追溯仍受 `GAP-026` 影响 |
| `ui/theme/*`、`res/*` | 无对应描述 | 原创（待视觉/文案复核） |
| `service/*`、`data/*`（非上列） | 行为条款来源未批准 | 待判定 |

> 说明：**“BLOCKED”表示不能作为发布依据，不等于已认定侵权。** 解除路径见 `GAP-REGISTER`。

---

## 5. 门禁阻塞项处置建议

`governance_check.py` 当前报告 9 个阻塞项（6 个 GAP + 3 个缺件）。本次审计新增 2 个
Critical BLOCKED（`GAP-026`、`GAP-027`），实际为 11 个。逐项如下。

| # | 阻塞项 | 谁负责 | 需要什么证据 | 解除条件 |
|---|---|---|---|---|
| 1 | `GAP-001` 全部规格未经法律审查 | OWNER 任命 / COUNSEL 签署 | COUNSEL 任命书、利益冲突声明、候选包哈希、逐条 KEEP/REDACT/REJECT | `LEG-001` 签署 APPROVED 或 APPROVED-WITH-REDACTIONS，且 `handoff/approved/` 非空 |
| 2 | `GAP-002` 实现早于批准规格 | COUNSEL + AUDIT | 模块→条款追溯矩阵；本文件 §4 的独立性评估；无法证明者的重做记录 | 每个模块有 `REQ-*` 追溯与独立性结论；重做项有提交哈希 |
| 3 | `GAP-006` 分段进度条几何 | COUNSEL 判定 / CLEAN 执行 | 逐条对照记录；若受保护，独立重设计（新 `DEC-*`）+ 视觉回归 | LEG 结论 + 新实现哈希 + `REV-001` 相关 ACC 通过 |
| 4 | `GAP-007` 文件类型表 | COUNSEL 判定 / CLEAN 执行 | 区分事实性扩展名与选择/编排；必要时重建集合与顺序 | LEG 结论 + 代码 diff + 单测 |
| 5 | `GAP-008` 无独立验证 | AUDIT | 非作者 REVIEW 任命、独立性声明、盲测包、逐 ACC 结果 | `REV-001` 签署 + AUDIT 会签 |
| 6 | `GAP-020` `logic-spec §15–§18` 与逆向笔记对应 | COUNSEL | 逐节审查（几何/多选/类型表/广告过滤）与删除或重写记录 | LEG 记录 + 文档哈希 + 无残留对应 |
| 7 | 无签署 `LEG-*` | OWNER / COUNSEL | 同 #1 | 同 #1 |
| 8 | 无签署 `REV-*` | AUDIT / REVIEW | 同 #5 | 同 #5 |
| 9 | `handoff/approved/` 为空 | COUNSEL | 批准包 + manifest + 哈希 | `LEG-001` 通过后写入且哈希匹配 |
| 10 | `GAP-026`（新增）需求来源章节失效 | DIRTY 重投 / COUNSEL 确认 | 重投候选规格：逐条真实章节、`OBS-*` 引用、manifest 哈希 | G2 自检通过 + COUNSEL 书面确认映射 |
| 11 | `GAP-027`（新增）INC-001/012 影响面未闭环 | COUNSEL + AUDIT | 对 §4.2/§4.3 的书面判定；受影响模块独立重设计或书面风险接受 | LEG 结论 + 重设计提交/哈希 + 相关 `REV-*` |

**不得**以修改 `GAP-REGISTER` 状态、预置 `LEG-*.md`/`REV-*.md`、或往 `approved/` 放占位文件
的方式让 `governance_check.py` 的 GATE 变绿。

---

## 6. LEG-001 / REV-001 独立性检查

### 6.1 共同事实

- `roles.md §4` 人员登记全部为 `TODO` / 未任命；`ROLE-COUNSEL` 与 `ROLE-REVIEW` 均未任命。
- `governance/registry/role-declarations/` 目录不存在，没有任何签署声明。
- Clean 仓库提交身份单一（`huangsiyu921-cmd`）。在无法区分人员的情况下，
  “非作者”与“未读源码”都只能靠自我声明，不能靠记录证明。
- 未创建 `governance/reviews/LEG-001.md` 或 `REV-001.md`：自动检查把“文件存在”当作已签发，
  预置模板会造成假通过。

### 6.2 LEG-001（法律审查）

| 条件 | 现状 | 结论 |
|---|---|---|
| COUNSEL 已任命且非 DIRTY/CLEAN 成员 | 未任命 | 不满足 |
| 已签署利益冲突/独立性声明 | 无 | 不满足 |
| 候选包有 manifest 与哈希 | 无（`handoff/candidate/` 为空） | 不满足 |
| 逐条 KEEP/REDACT/REJECT | 无 | 不满足 |
| 批准包哈希与版本记录 | 无 | 不满足 |

**结论：LEG-001 不得签发。** 需先完成 G3 准入。

### 6.3 REV-001（独立验证）

| 条件 | 现状 | 结论 |
|---|---|---|
| REVIEW 非该子系统作者 | 无任命、无声明；提交身份单一，无法证明 | 不满足 |
| 未阅读实现文档或源码 | 无声明 | 不满足 |
| 未与作者讨论实现细节 | 无声明 | 不满足 |
| 只接收盲测包 | 无盲测包、无构建哈希 | 不满足 |
| 逐 ACC 的 PASS/FAIL/SKIP + 证据 | 现有 PASS 均为实现者自测 | 不满足 |

**结论：REV-001 不得签发。** 现有 `docs/acceptance.md` 的 PASS 不能转为 REV 证据。

---

## 7. 本次审计的边界

- 未做法律判断，未做二进制/视觉相似度分析，未逐字比对全部文案与图标。
- 未复跑功能测试；未修改 `app/src`、`docs/spec`、Dirty `specs/`。
- 未销毁隔离区；隔离区哈希沿用 `EV-G1-009` 快照。
- 本文引用的 Dirty 内容仅到“章节/哈希/结论”层级，未复制原始类名、字段名、枚举、
  布局或完整数据表，避免在 Clean 仓库二次传播原始表达。

## 8. 复核命令

```powershell
cd D:\1DM+
python governance/tools/governance_check.py
git diff --check
```

预期：`INTEGRITY: PASS`、`GATE: BLOCKED`。门禁是否解除不取决于本文件，而取决于
`GAP-REGISTER` 的关闭证据与 OWNER/COUNSEL 的签署。
