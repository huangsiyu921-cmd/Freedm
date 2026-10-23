# 候选规格投递区（仅说明）

`CLEAN` 仓库**不保存候选包**。`CAND-DL-001` 位于 Dirty 工作区：

```
<DIRTY_WORKSPACE>/handoff/candidate/CAND-DL-001/
├── CAND-DL-001.md
├── CAND-DL-001-notes.md
├── CAND-DL-001-observations.md
├── CAND-DL-001-traceability.csv
├── CAND-DL-001-d1-triage.md
└── manifest.json
```

规则：

- 仅 `ROLE-DIRTY` 写入 Dirty 侧候选区。
- 投递前完成 `specs/README.md §4` 检查。
- 每个包带 `manifest.json`（文件列表 + SHA-256 + 基线 + 作者）。
- 不得包含 Clean 路径、模块名、测试结果或原始表达。
- `CLEAN` 不得读取候选包；`COUNSEL` 从 Dirty 侧读取并审查。
- 批准后才把清洗后的规格写入本仓库 `crre/handoff/approved/`。

状态：`CAND-DL-001 v1.0.0 / CANDIDATE-BLOCKED`（G0/G1/G3 未通过）
