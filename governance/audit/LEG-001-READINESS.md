# LEG-001 签发前资格检查（READINESS）

> `AUDIT-ID: LEG-001-READINESS-2026-10-23`
> `STATUS: NOT_READY / BLOCKED`
> `ROLE: ROLE-AUDIT（审查组织与独立性检查；不是 COUNSEL，不签发法律意见）`
> `注意: 本文件不是法律审查记录。正式记录只能是 governance/reviews/LEG-001-*.md，由 COUNSEL 签署。`

---

## 1. 结论

**LEG-001 现在不得签发。** 候选包已在 Dirty 侧带 manifest（`EV-G2-001`），但仍缺审查员任命、独立性声明，以及 G0/G1 与 OBS 的 AUDIT 复核。
在 G3 准入条件满足前，任何“法律审查已通过”的表述都不成立。

## 2. 准入条件逐项检查（gates.md G3）

| # | 条件 | 现状 | 判定 |
|---|---|---|---|
| 1 | G2 已通过；候选规格哈希已登记 | G2 BLOCKED；候选包在 Dirty `handoff/candidate/`，manifest 已登记 `EV-G2-001`；G0/G1 未过 | 不满足 |
| 2 | COUNSEL 已任命且与两组无利益冲突 | `roles.md §4` 为 `TODO`；无声明文件 | 不满足 |
| 3 | 候选包只含行为条款与稳定 ID | 候选包已重写为逐条 `REQ-*` 并补 `OBS-001..018`；AUDIT 未复核（`GAP-026`） | 部分满足 |
| 4 | 每条条款可追溯到观察记录 | `OBS-001..018` 已登记；证据与映射未复核 | 部分满足 |
| 5 | 原始表达已隔离/限读 | D1 四份已加 `DIRTY-RAW`；隔离区已建 | 部分满足 |
| 6 | 审查范围与适用法域已确定 | 未填写 | 不满足 |

## 3. 独立性检查

| 检查 | 结果 |
|---|---|
| COUNSEL 是否为实现者/分析者 | 未任命，无法检查 |
| 是否签署利益冲突声明 | 否；`governance/registry/role-declarations/` 不存在 |
| 是否能向 CLEAN 口述被剔除内容 | 无约束记录；须在 `LEG-001` 中书面承诺 |
| 审查版本是否可固定 | 候选包 manifest SHA-256 `d4d942ac…` 已登记，可固定 |

**独立性结论：不能证明。** 即使现在指定某人，也必须先签署声明并把声明归档到
`governance/registry/role-declarations/`（不进入公开仓库）。

## 4. 签发前必须补齐的材料

1. `<DIRTY_WORKSPACE>/handoff/candidate/CAND-DL-001/manifest.json`：文件列表、SHA-256、基线、作者（已具备，待审查员核对）。
2. 逐条 `KEEP / REDACT / REJECT` 表，覆盖 `CAND-DL-001` 全部条款与 `docs/spec/*` 的
   `REQ/INV/ERR/PRV/CONST`。
3. 对 `GAP-006`（进度条几何）、`GAP-007`（文件类型表）、`GAP-020`（`logic-spec §15–§18`）
   的明确判定与处理。
4. 对 `GAP-026`/`GAP-027` 的书面结论：受影响条款是否可保留、模块是否需重做。
5. 批准包哈希与生效日期；写入 `handoff/approved/`。

## 5. 禁止事项

- 不得由 OWNER 或 AUDIT 代签。
- 不得先创建空的 `reviews/LEG-001.md` 让自动检查认为“已有法律审查”。
- 不得以“规格已整改”代替逐条审查记录。
- 不得把 Dirty 原始表达写入批准包或本文件。
