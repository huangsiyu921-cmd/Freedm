# 缺口总账（GAP Register）

> `OWNER: ROLE-AUDIT`
> 状态机见 `governance/id-policy.md §4`：`OPEN → TRIAGED → RESOLVED / ACCEPTED / DEFERRED / BLOCKED`。
> **Critical/High 缺口处于 OPEN/BLOCKED 时不得发布。**
> 每条缺口必须有责任人、证据、关闭证据与解除条件；不得以“已知限制”代替状态。
> 本次维护（2026-10-23，第二版）：新增 `GAP-026..030`，增加“关闭证据”列；
> 未把任何 `BLOCKED` 改为 `RESOLVED`。
> 2026-10-23 单人模式：`GAP-006/007/020` 转 `DEFERRED` 并披露风险；Critical/High 的 `DEFERRED` 仍计入发布 blocker。
> 代码开发继续推进；法律状态存疑并留存记录；门禁只约束发布。

---

## 1. 法律与信息流

| ID | 严重度 | 标题 | 证据 | 状态 | 关闭证据 | 处置 / 解除条件 | Owner |
|---|---|---|---|---|---|---|---|
| GAP-001 | Critical | 全部规格未经法律审查 | 仓库无 `LEG-*`；`handoff/approved/` 为空 | **BLOCKED** | 未取得 | 任命 COUNSEL，签发 `LEG-001`，批准包入 `approved/` | OWNER |
| GAP-002 | Critical | 现有实现早于批准规格 | 提交 324c65b；`docs/logic-spec.md` 自述“当前有重复实现” | **BLOCKED** | 未取得 | 法律审查 + 模块→条款追溯；无法证明独立性的模块重做 | COUNSEL/AUDIT |
| GAP-003 | High | D1 原始分析含受保护表达 | `adblock-engine.md` 原始枚举、`ui-progressview.md` 几何、`file-types` 原始方法名 | **TRIAGED** | 部分：4 份 D1 已加限读横幅（EV-G1-009） | 逐条清洗或由 COUNSEL 判定不可用 | DIRTY |
| GAP-006 | Critical | 分段进度条几何与候选规格逐条对应 | `docs/logic-spec.md §15` 与 `SegmentProgressBar.kt`；`ASSET-PROVENANCE §3.2` | **DEFERRED** | 不适用（单人模式，无 COUNSEL） | 不做法律判定、不签发 LEG；风险已披露并留存；代码继续推进；商业发布或取得法律意见前重开 | OWNER |
| GAP-007 | High | 文件类型表来源标注“原始数据，可直接复用” | `file-types-and-folders.md §3`、`FileKind.kt`；`ASSET-PROVENANCE §3.3` | **DEFERRED** | 不适用（单人模式，无 COUNSEL） | 不做法律判定、不签发 LEG；风险已披露并留存；代码继续推进；商业发布或取得法律意见前重开 | OWNER |
| GAP-020 | Critical | `logic-spec §15–§18` 与逆向笔记存在可追溯关系 | §15/§16/§17/§18 与 Dirty 四份笔记对应 | **DEFERRED** | 不适用（单人模式，无 COUNSEL） | 不做法律判定、不签发 LEG；风险已披露并留存；代码继续推进；商业发布或取得法律意见前重开 | OWNER |
| GAP-026 | Critical | 需求来源映射与 `OBS-*` 复核未完成 | `REQ-UI-001..011` 原引已删除的 `CAND §8`；`REQ-SH-005` 原引 §7；`REQ-BR-007..012` 原来源不符；候选包已重写并登记 `OBS-001..018`，但 AUDIT 未复核；见 `ASSET-PROVENANCE §4` | **BLOCKED** | 未取得 | AUDIT 复核 OBS 与条款映射；COUNSEL 书面确认；G0/G1 通过后重开 G2 | DIRTY/AUDIT/COUNSEL |
| GAP-027 | Critical | INC-001/INC-012 影响面未闭环 | `ASSET-PROVENANCE §4`；受影响 REQ 与模块清单；隔离区证据 `EV-G1-009` | **BLOCKED** | 未取得 | COUNSEL 对影响面作书面判定；受影响模块独立重设计或书面风险接受；相关 `REV-*` 通过 | COUNSEL/AUDIT |

## 2. 文档与追溯

| ID | 严重度 | 标题 | 证据 | 状态 | 关闭证据 | 处置 / 解除条件 | Owner |
|---|---|---|---|---|---|---|---|
| GAP-004 | Medium | 历史文档未全量 ID 化 | `logic-spec` 仅 4 条不变量有 ID | **OPEN** | 未取得 | 按章节建立 `REQ-*` 映射并逐条迁移 | CLEAN |
| GAP-005 | Medium | 描述性文档仍含规范措辞 | `logic-spec` 多处“必须”、`implementation-guide` 原“必须照抄” | **OPEN** | 未取得 | 改为解释性措辞；权威条款迁入 `docs/spec/` | CLEAN |
| GAP-009 | High | 无需求→代码追溯矩阵 | 无提交/模块到 `REQ-*` 的映射 | **OPEN** | 未取得 | 建立 `traceability.csv` 并在 G4 检查 | CLEAN |
| GAP-011 | Medium | 候选规格 §7 六态与 §1.1 五态冲突 | 原 `behavior-spec §7` 写“六态”，§1.1 为五态 | **RESOLVED** | 已取得：§7 已删除并隔离（EV-G1-009） | 以五态为准 | DIRTY |
| GAP-016 | Low | `ui-design.md` 早期“广告拦截不做”与实现不一致 | 文档 §3 与 README 功能表冲突 | **OPEN** | 未取得 | 更新设计文档并登记 `DEC-*` | CLEAN |
| GAP-022 | Low | `ui-design.md` 交叉引用第 17 部分错误 | 原文指向 §17，实际多选在 §16 | **RESOLVED** | 已取得：已改为第 16 部分 | — | CLEAN |
| GAP-024 | Medium | 候选规格章节→REQ 映射未复核 | `behavior-spec` 已重写为逐条 `REQ-*` 表格；`CAND-DL-001-traceability.csv` 70 条；待 AUDIT 复核 | **TRIAGED** | 未取得 | AUDIT 复核后关闭；与 GAP-026 合并处理 | DIRTY/AUDIT |

## 3. 验证与环境

| ID | 严重度 | 标题 | 证据 | 状态 | 关闭证据 | 处置 / 解除条件 | Owner |
|---|---|---|---|---|---|---|---|
| GAP-008 | Critical | 无独立验证记录 | `acceptance.md` 的 PASS 均为实现者自测 | **BLOCKED** | 未取得 | 任命非作者、未读源码/设计文档的 REVIEW，签发 `REV-001` | AUDIT |
| GAP-010 | High | 环境隔离不清 | 测试 AVD 名 `1DMPlus`，与逆向环境同名 | **TRIAGED** | 未取得 | 改用独立 AVD/设备并记录安装包列表 | AUDIT |
| GAP-015 | High | P6 飞行模式可用未实测 | `ACC-PRV-006` 为 `TODO` | **OPEN** | 未取得 | 执行飞行模式测试并留证 | REVIEW |
| GAP-017 | Medium | 仅 Wi-Fi / 并发上限未实测 | `ACC-DL-010`、`ACC-DL-011` 为 `TODO` | **OPEN** | 未取得 | 执行网络切换与并发测试 | REVIEW |
| GAP-018 | Medium | 空间不足预检 / 同名并发去重仅代码审阅 | `ACC-DL-016`、`ACC-DL-017` 为 `TODO` | **OPEN** | 未取得 | 构造磁盘限制与并发同名测试 | REVIEW |
| GAP-021 | High | 验收环境使用 `1DMPlus` AVD 名称 | `acceptance.md` 环境基线 | **TRIAGED** | 未取得 | 与 GAP-010 合并处理；历史记录标注 | AUDIT |
| GAP-023 | Medium | 证据清单/哈希缺失 | 本次审计前无统一清单 | **OPEN** | 部分：`registry/evidence.csv` 已建（EV-G1-001..009、EV-G0-001、EV-G4-001..003）；缺访问日志与保管链复核 | 补齐访问日志、复核树哈希；生成 G6 清单 | AUDIT |
| GAP-028 | High | B2/B3 验收为 TODO 且无缺口条目 | `ACC-BR-002`、`ACC-BR-003` 为 `TODO` | **OPEN** | 未取得 | 执行媒体嗅探（DOM+网络）与剪贴板测试并留证；失败转 GAP | REVIEW |
| GAP-029 | Medium | 302 重定向后的明文 HTTP 策略缺口未登记 | `ACC-DL-014` 实测跟随后被明文策略拒绝 | **OPEN** | 未取得 | 决定是否允许明文重定向；更新 `REQ-*`/`ACC-*` 并留证 | CLEAN/COUNSEL |

## 4. 范围性未实现（不是缺陷）

| ID | 严重度 | 标题 | 证据 | 状态 | 关闭证据 | 处置 / 复核日 | Owner |
|---|---|---|---|---|---|---|---|
| GAP-012 | Medium | HLS/DASH 只作单文件下载，无分片合并 | `REQ-BR-005`、`ACC-BR-002` | **DEFERRED** | 复核日记录：2027-01-31 | 到期复核 | OWNER |
| GAP-013 | Low | 广告过滤不处理内联广告位 | `README` 已知限制 | **DEFERRED** | 复核日记录：2027-01-31 | 到期复核 | OWNER |
| GAP-014 | Medium | 添加下载时更改保存位置未实现 | 多选已于 2026-10-23 实现并通过 `assembleDebug`；保存位置仍固定为私有目录 | **DEFERRED** | 多选：代码完成，待真机；保存位置：未做 | 到期复核；多选待真机确认后另记 | OWNER |
| GAP-019 | Medium | release 签名与 `proguard` 规则未实测 | `README` 已知限制 | **DEFERRED** | 复核日记录：发布前 | 发布前复核 | OWNER |
| GAP-025 | Low | `logic-spec §14.4` 自述存在重复格式化实现 | 文档原文 | **OPEN** | 未取得 | 合并实现并登记 `DEC-*` | CLEAN |
| GAP-030 | Medium | 无 torrent、无密码管理、无主题市场未逐条登记 | `acceptance.md §D` | **DEFERRED** | 复核日记录：2027-01-31 | OWNER 确认范围并保持范围声明 | OWNER |

---

## 5. TODO / 已知限制覆盖检查

| 来源 | 条目 | GAP |
|---|---|---|
| `acceptance.md` | `ACC-DL-010` 仅 Wi-Fi | GAP-017 |
| `acceptance.md` | `ACC-DL-011` 并发上限 | GAP-017 |
| `acceptance.md` | `ACC-DL-016` 空间预检 | GAP-018 |
| `acceptance.md` | `ACC-DL-017` 同名去重 | GAP-018 |
| `acceptance.md` | `ACC-PRV-006` 飞行模式 | GAP-015 |
| `acceptance.md` | `ACC-BR-002` 媒体嗅探 | GAP-028（本次补登） |
| `acceptance.md` | `ACC-BR-003` 剪贴板 | GAP-028（本次补登） |
| `acceptance.md` | A15 明文 HTTP 残余 | GAP-029（本次补登） |
| `acceptance.md §D` | HLS / 内联广告 / 多选与保存位置 / release 签名 | GAP-012 / 013 / 014 / 019 |
| `acceptance.md §D` | torrent、密码管理、主题市场 | GAP-030（本次补登） |
| `docs/known-pitfalls.md` | 各缺口 1–7 | 已映射到 GAP-015/017/018/029 与 `logic-spec §20.4` |

> 规则：新增 TODO 或“已知限制”时，必须同时分配 `GAP-*` 并登记 `registry/ids.csv`。

---

## 6. 统计（2026-10-23 第二版）

| 状态 | 数量 |
|---|---|
| BLOCKED | 5 |
| OPEN | 11 |
| TRIAGED | 4 |
| RESOLVED | 2 |
| DEFERRED | 8 |
| **合计** | **30** |

**发布结论：不通过。** 5 个 `BLOCKED` + 3 个 Critical/High 的 `DEFERRED`（GAP-006/007/020，单人模式风险披露）仍计入 blocker；`governance_check.py` 报告
**11 个门禁阻塞项**（8 个 Critical/High BLOCKED + 无 `LEG-*` + 无 `REV-*` + `approved/` 为空）。
在 `GAP-001/002/006/008/020/026/027` 与 `GAP-007` 关闭前不得对外发布。
