# 规范性规格包

> `DOCUMENT-CLASS: NORMATIVE INDEX`
> `BASELINE: SPEC-BASELINE-2026-10-23`
> `LEGAL-STATUS: NOT_REVIEWED / PERMANENTLY_BLOCKED (single developer)`

本目录是 FreeDM 的**唯一规范来源**（在 `governance/handoff/approved/` 为空期间的整改基线）。
描述性设计文档不得单独产生约束。

| 文件 | 内容 | ID 前缀 |
|---|---|---|
| [`requirements.md`](requirements.md) | 行为需求（下载、浏览器、分享、存储、UI） | `REQ-*` |
| [`invariants.md`](invariants.md) | 全局不变量 | `INV-*` |
| [`errors.md`](errors.md) | 错误条件与失败处理 | `ERR-*` |
| [`privacy.md`](privacy.md) | 隐私基线、权限清单 | `PRV-*` |
| [`constants.md`](constants.md) | 规定性常量 | `CONST-*` |
| [`../../acceptance.md`](../../acceptance.md) | 验收标准与判定方法 | `ACC-*` |

## 阅读顺序

1. `requirements.md`：要实现什么；
2. `invariants.md` / `errors.md`：什么绝对不能违反、失败时怎么办；
3. `privacy.md`：发布硬门槛；
4. `constants.md`：会影响可观察行为的数值；
5. `../../acceptance.md`：如何证明做对了。

## 与描述性文档的关系

- `../logic-spec.md`、`../implementation-guide.md`、`../ui-design.md` 是 HOW，属于 INFORMATIVE；
- `../known-pitfalls.md`、`../../HANDOFF.md`、`../../README.md` 是经验/介绍，属于 INFORMATIVE；
- 若描述性文档与本目录冲突，以本目录为准，并登记 `GAP-*`。

## 法律状态

当前所有条款均为**待审基线**，来源为候选规格 `CAND-DL-001`。在 `LEG-001` 签发前：

- 不得把本目录条款描述为“已法律审查”；
- 不得据此新增对外发布的功能；
- 已实现部分按 `GAP-001`/`GAP-002` 处理。
