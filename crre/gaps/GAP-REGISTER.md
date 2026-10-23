# 缺口总账（GAP Register）

> `OWNER: ROLE-AUDIT`
> 状态机见 `crre/id-policy.md §4`：`OPEN → TRIAGED → RESOLVED / ACCEPTED / DEFERRED / BLOCKED`。
> **Critical/High 缺口处于 OPEN/BLOCKED 时不得发布。**
> 每条缺口必须有责任人、证据与关闭条件；不得以“已知限制”代替状态。

---

## 1. 法律与信息流

| ID | 严重度 | 标题 | 证据 | 状态 | 处置 / 解除条件 | Owner |
|---|---|---|---|---|---|---|
| GAP-001 | Critical | 全部规格未经法律审查 | 仓库无 `LEG-*`；`handoff/approved/` 为空 | **BLOCKED** | 任命 COUNSEL，签发 `LEG-001` | OWNER |
| GAP-002 | Critical | 现有实现早于批准规格 | 提交 324c65b；`docs/logic-spec.md` 自述“当前有重复实现” | **BLOCKED** | 法律审查 + 追溯评估；无法证明独立性的模块重做 | COUNSEL/AUDIT |
| GAP-003 | High | D1 原始分析含受保护表达 | `adblock-engine.md` 原始枚举、`ui-progressview.md` 几何、`file-types` 原始方法名 | **TRIAGED** | 逐条清洗或由 COUNSEL 判定不可用 | DIRTY |
| GAP-006 | Critical | 分段进度条几何与候选规格逐条对应 | `docs/logic-spec.md §15` 与 `SegmentProgressBar.kt` | **BLOCKED** | 法律判定；必要时独立重设计并记录 `DEC-*` | COUNSEL/CLEAN |
| GAP-007 | High | 文件类型表来源标注“原始数据，可直接复用” | `file-types-and-folders.md §3`、`FileKind.kt` | **BLOCKED** | 法律判定事实性数据/选择编排；必要时重建表 | COUNSEL/CLEAN |
| GAP-020 | Critical | `logic-spec §15–§18` 与逆向笔记存在可追溯关系 | §15/§16/§17/§18 与 Dirty 四份笔记对应 | **BLOCKED** | 与 GAP-006/007 合并法律审查；未通过前不得新增实现 | COUNSEL |

## 2. 文档与追溯

| ID | 严重度 | 标题 | 证据 | 状态 | 处置 / 解除条件 | Owner |
|---|---|---|---|---|---|---|
| GAP-004 | Medium | 历史文档未全量 ID 化 | `logic-spec` 仅 4 条不变量有 ID | **OPEN** | 按章节建立 `REQ-*` 映射并逐条迁移 | CLEAN |
| GAP-005 | Medium | 描述性文档仍含规范措辞 | `logic-spec` 多处“必须”、`implementation-guide` 原“必须照抄” | **OPEN** | 改为解释性措辞；权威条款迁入 `docs/spec/` | CLEAN |
| GAP-009 | High | 无需求→代码追溯矩阵 | 无提交/模块到 `REQ-*` 的映射 | **OPEN** | 建立 `traceability.csv` 并在 G4 检查 | CLEAN |
| GAP-011 | Medium | 候选规格 §7 六态与 §1.1 五态冲突 | 原 `behavior-spec §7` 写“六态”，§1.1 为五态 | **RESOLVED** | 删除 §7 并隔离；以五态为准 | DIRTY |
| GAP-016 | Low | `ui-design.md` 早期“广告拦截不做”与实现不一致 | 文档 §3 与 README 功能表冲突 | **OPEN** | 更新设计文档并登记 `DEC-*` | CLEAN |
| GAP-022 | Low | `ui-design.md` 交叉引用第 17 部分错误 | 原文指向 §17，实际多选在 §16 | **RESOLVED** | 已改为第 16 部分 | CLEAN |
| GAP-024 | Medium | 候选规格章节→REQ 映射未落到条款级 | `behavior-spec` 仅按大节映射 | **OPEN** | 逐条映射到 `REQ-*`，作为 G2 准出 | DIRTY |

## 3. 验证与环境

| ID | 严重度 | 标题 | 证据 | 状态 | 处置 / 解除条件 | Owner |
|---|---|---|---|---|---|---|
| GAP-008 | Critical | 无独立验证记录 | `acceptance.md` 的 PASS 均为实现者自测 | **BLOCKED** | 任命非作者、未读源码/设计文档的 REVIEW，签发 `REV-001` | AUDIT |
| GAP-010 | High | 环境隔离不清 | 测试 AVD 名 `1DMPlus`，与逆向环境同名 | **TRIAGED** | 改用独立 AVD/设备并记录；确认未安装目标 APK | AUDIT |
| GAP-015 | High | P6 飞行模式可用未实测 | `ACC-PRV-006` 为 TODO | **OPEN** | 执行飞行模式测试并留证 | REVIEW |
| GAP-017 | Medium | 仅 Wi-Fi / 并发上限未实测 | `ACC-DL-010`、`ACC-DL-011` 为 TODO | **OPEN** | 执行网络切换与并发测试 | REVIEW |
| GAP-018 | Medium | 空间不足预检 / 同名并发去重仅代码审阅 | `ACC-DL-016`、`ACC-DL-017` 为 TODO | **OPEN** | 构造磁盘限制与并发同名测试 | REVIEW |
| GAP-021 | High | 验收环境使用 `1DMPlus` AVD 名称 | `acceptance.md` 环境基线 | **TRIAGED** | 与 GAP-010 合并处理；历史记录标注 | AUDIT |
| GAP-023 | Medium | 证据清单/哈希缺失 | 本次审计前无统一清单 | **OPEN** | 生成 `EVIDENCE-MANIFEST.csv` 与 `EV-*` 登记（本次 verify） | AUDIT |

## 4. 范围性未实现（不是缺陷）

| ID | 严重度 | 标题 | 证据 | 状态 | 处置 / 复核日 | Owner |
|---|---|---|---|---|---|---|
| GAP-012 | Medium | HLS/DASH 只作单文件下载，无分片合并 | `REQ-BR-005`、`ACC-BR-002` | **DEFERRED** | 2027-01-31 复核 | OWNER |
| GAP-013 | Low | 广告过滤不处理内联广告位 | `README` 已知限制 | **DEFERRED** | 2027-01-31 复核 | OWNER |
| GAP-014 | Medium | 列表长按多选、添加下载改保存位置未实现 | `ui-design.md` | **DEFERRED** | 2027-01-31 复核 | OWNER |
| GAP-019 | Medium | release 签名与 `proguard` 规则未实测 | `README` 已知限制 | **DEFERRED** | 发布前复核 | OWNER |
| GAP-025 | Low | `logic-spec §14.4` 自述存在重复格式化实现 | 文档原文 | **OPEN** | 合并实现并登记 `DEC-*` | CLEAN |

---

## 5. 统计（2026-10-23）

| 状态 | 数量 |
|---|---|
| BLOCKED | 6 |
| OPEN | 10 |
| TRIAGED | 3 |
| RESOLVED | 2 |
| DEFERRED | 4 |

**发布结论：不通过。** 存在 6 个 `BLOCKED`（含 4 个 Critical）与 11 个 `OPEN`。
