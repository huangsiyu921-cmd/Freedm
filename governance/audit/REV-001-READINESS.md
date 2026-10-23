# REV-001 签发前资格检查（READINESS）

> `AUDIT-ID: REV-001-READINESS-2026-10-23`
> `STATUS: STRUCTURALLY_UNAVAILABLE / SINGLE_DEVELOPER`
> `ROLE: ROLE-AUDIT（组织独立验证与复核；本文件不是验证结论）`
> `注意: 正式记录只能是 governance/reviews/REV-001-*.md，由 REVIEW 签署、AUDIT 会签。`

---

## 1. 结论

**REV-001 永不签发。** 单人项目没有非作者评审者；本文件只记录结构性原因，不是待办，也不是验证结论。

## 2. 准入条件逐项检查（gates.md G5）

| # | 条件 | 现状 | 判定 |
|---|---|---|---|
| 1 | G4 已通过；构建产物哈希固定 | G4 FAIL；无发布构建哈希 | 不满足 |
| 2 | REVIEW 已任命 | 未任命（`roles.md §4` 为 `TODO`） | 不满足 |
| 3 | REVIEW 不是该作者 | 无声明；Clean 提交身份单一，无法证明 | 不满足 |
| 4 | REVIEW 未阅读实现文档/源码 | 无声明 | 不满足 |
| 5 | 盲测包只含规格、验收、安装包、数据 | 无盲测包 | 不满足 |
| 6 | 每条 `ACC-*` 有 PASS/FAIL/SKIP 与证据 | 现有 PASS 为实现者自测 | 不满足 |

## 3. 独立性检查

| 检查 | 结果 |
|---|---|
| 是否该作者（代码/设计/规格） | 无法证明；仓库提交身份单一 |
| 是否读过源码 | 无声明；本仓库与实现文档同一目录，无法从环境证明 |
| 是否读过设计文档 | 无声明 |
| 是否与作者讨论过实现细节 | 无声明 |
| 是否只接触盲测包 | 否；尚无盲测包 |

**独立性结论：不能证明。** 仅靠“换一个文件名”或“同一账户下换一个角色”不构成独立评审。

## 4. 建议的盲测包内容

```
REV-001 blind package/
├── spec/                 批注为 APPROVED 的规格（当前为空 → 阻塞）
├── acceptance/           ACC-* 与判定方法
├── app-debug.apk         固定哈希
├── test-data/            测试用媒体、URL 清单、服务器脚本
└── README                环境要求与禁止事项
```

不包含：源码、设计文档、提交说明、`HANDOFF.md`、`docs/logic-spec.md`、
`docs/implementation-guide.md`、`docs/known-pitfalls.md`。

## 5. 验证范围建议

在 `GAP-026`/`GAP-027` 关闭前，`REV-001` 即使执行也不能使发布门禁通过。
建议先做**受限范围**的验证准备（仅 ACC 与环境），但不要在 `reviews/` 生成
`REV-001.md`，直到 G4 与 G3 的状态允许。

## 6. 禁止事项

- 不得让实现者自测结果直接转写为 `REV-001`。
- 不得向评审者提供源码、设计文档或本清单以外的实现信息。
- 不得预置空的 `reviews/REV-001.md` 让自动检查认为“已有独立验证”。
- 不得在 Critical/High `BLOCKED` 未关闭时给出 `PASS` 结论。
