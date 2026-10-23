# 受控再实现治理包（Controlled Reimplementation Governance）

> 状态：**审计整改中**。本目录是内部流程与证据文档，不是对外合规声明，也不代表任何法律结论。
> 本流程按角色隔离与单向信息流设计，但**未经法律审查和第三方审计**，不声称已构成
> 任何特定的合规开发流程。
> 适用对象：1DM+ 18.2 的互操作性/隐私研究与 FreeDM 的独立重实现。
> 治理包版本：`GOV-0.1.0`（2026-10-23）
> 规范基线：`SPEC-BASELINE-2026-10-23`（见 `docs/spec/`）

---

## 1. 这个包解决什么问题

没有流程证据的“隔离”只是自我声明。本包把角色隔离与单向信息流拆成可检查的门禁：

1. **谁可以看什么**（`roles.md`）；
2. **信息往哪个方向流**（`information-flow.md`）；
3. **每个阶段什么算开始、什么算完成**（`gates.md`）；
4. **每一步留下什么证据、放在哪里、谁能改**（`evidence.md`）；
5. **需求/不变量/错误/验收如何获得永不复用的 ID**（`id-policy.md`）；
6. **规格与实现不一致如何登记和关闭**（`gaps/GAP-REGISTER.md`）；
7. **谁独立验证、谁不能验证**（`reviews/`）。

自动检查：`python governance/tools/governance_check.py`（在仓库根运行）。

---

## 2. 权威顺序

冲突时按以下顺序裁决：

| 优先级 | 文档 | 性质 |
|---|---|---|
| 1 | 经法律审查并签发的 `governance/handoff/approved/` 规格包 | 唯一可交付给实现组的规格 |
| 2 | `docs/spec/requirements.md`、`invariants.md`、`errors.md`、`privacy.md` | 规范性（MUST/SHALL），当前为**待审基线** |
| 3 | `docs/acceptance.md` | 规范性验收 |
| 4 | `docs/logic-spec.md`、`docs/implementation-guide.md`、`docs/ui-design.md` | 描述性设计（INFORMATIVE），不得单独产生约束 |
| 5 | `docs/known-pitfalls.md`、`HANDOFF.md`、`README.md`、`docs/README.md` | 描述性说明（INFORMATIVE） |

> 规则：规范性文档只写“必须/应当/可以”的条款与验收；描述性文档只解释“为什么/怎么做”。描述性文档里的 `MUST`、`必须`、`不得` 一律视为解释，不具规范效力。

---

## 3. 目录

```
governance/
├── README.md                 治理入口
├── roles.md                  角色、访问矩阵、利益冲突
├── information-flow.md       信息流图、单向通道、交换区、污染处置
├── gates.md                  G0–G6 准入/准出条件
├── evidence.md               证据留存清单与保管链
├── id-policy.md              ID 规则、缺口状态机、评审独立性
├── audit/                    审计报告（本次整改）
├── gaps/GAP-REGISTER.md      缺口总账
├── handoff/                  规格交接区（approved/ 只读）
├── registry/                 ID 注册表与退役表
├── reviews/                  独立验证 / 法律审查记录模板
└── tools/governance_check.py       自动一致性检查
```

---

## 4. 当前状态（必须如实阅读）

| 项目 | 状态 | 说明 |
|---|---|---|
| 法律审查 | **BLOCKED / NOT_REVIEWED** | 仓库内没有任何经签署的法律审查记录；`handoff/approved/` 为空 |
| 规格 ID | 已建立基线，历史文档仍在迁移 | 见 `registry/ids.csv` 与 `GAP-004` |
| 规定性/描述性分离 | 部分完成 | 新建 `docs/spec/`；历史设计文档已降级并标注 INFORMATIVE |
| 独立验证 | 无有效记录 | 现有验收由实现者执行，不能作为独立验证证据 |
| 信息流 | 已阻断已发现的逆向→实现反向通道 | 见 `audit/AUDIT-2026-10-23.md` 的 INC-001/INC-003 |
| 发布门禁 | **未通过** | 在 LEG-001 与 REV-001 签发前不得对外发布 |

---

## 5. 变更控制

- 治理文档本身变更需记录 `CTRL-*`，写入 `audit/`，并说明影响的门禁。
- 规范条款变更只能新增 ID 或修改条款正文；ID 永不复用。废弃 ID 写入 `registry/retired-ids.txt`。
- 任何“先实现、后补规格”的路径均视为违规，登记为 `INC-*` 与 `GAP-*`，不得以文档追认代替法律审查。
