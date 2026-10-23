# 文档导读

> 先分清两类文档：**规范（NORMATIVE）**说“必须怎样”，**设计（INFORMATIVE）**解释“为什么/怎么做”。
> 冲突时以规范为准，并登记 `GAP-*`。

## 1. 规范与验收（权威）

| 顺序 | 文档 | 回答什么问题 |
|---|---|---|
| 1 | [`spec/requirements.md`](spec/requirements.md) | **必须实现什么**：下载、浏览器、分享、存储、UI 的可验证条款（`REQ-*`） |
| 2 | [`spec/invariants.md`](spec/invariants.md) | **什么绝对不能违反**（`INV-*`） |
| 3 | [`spec/errors.md`](spec/errors.md) | **失败时怎么办**（`ERR-*`） |
| 4 | [`spec/privacy.md`](spec/privacy.md) | **发布硬门槛**（`PRV-*`） |
| 5 | [`spec/constants.md`](spec/constants.md) | 影响可观察行为的数值（`CONST-*`） |
| 6 | [`acceptance.md`](acceptance.md) | **怎么证明做对了**（`ACC-*`） |

> 当前法律状态：`NOT_REVIEWED`。这些是整改基线，不是已批准规格。

## 2. 设计说明（解释性）

| 文档 | 内容 | 备注 |
|---|---|---|
| [`logic-spec.md`](logic-spec.md) | 逐函数设计、伪代码、边界、时序 | `INFORMATIVE`；§15–§18 待法律审查 |
| [`implementation-guide.md`](implementation-guide.md) | 状态机迁移、调用序列、线程模型、常量解释 | `INFORMATIVE` |
| [`ui-design.md`](ui-design.md) | 界面信息架构与交互设计 | `INFORMATIVE`；曾是逆向端误存文档，已移回 |
| [`known-pitfalls.md`](known-pitfalls.md) | 真实踩过的坑与设计权衡 | `INFORMATIVE` |
| [`README.md`](README.md) | 本导读 | `INFORMATIVE` |

## 3. 治理与流程

| 文档 | 内容 |
|---|---|
| [`../crre/README.md`](../crre/README.md) | CRRE 治理入口、权威顺序、当前门禁状态 |
| [`../crre/roles.md`](../crre/roles.md) | 角色、访问边界、RACI |
| [`../crre/information-flow.md`](../crre/information-flow.md) | 单向信息流、交接区、污染处置 |
| [`../crre/gates.md`](../crre/gates.md) | G0–G6 准入/准出 |
| [`../crre/evidence.md`](../crre/evidence.md) | 证据留存与保管链 |
| [`../crre/gaps/GAP-REGISTER.md`](../crre/gaps/GAP-REGISTER.md) | 规格-实现缺口总账 |
| [`../crre/reviews/`](../crre/reviews/) | 独立验证与法律审查模板 |

## 4. 想快速做事

- 写新功能：先确认 `REQ-*` 存在且已法律审查；没有就停在 G2/G3。
- 改现有行为：先改 `docs/spec/`，再改代码，再补 `ACC-*` 证据。
- 发现不一致：登记 `GAP-*`，不要只写在提交信息里。
- 做验收：需要独立评审者（非作者、未读源码/设计文档），使用盲测包。
