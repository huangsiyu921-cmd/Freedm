# 规格交接区（handoff）

本目录是 Dirty → Clean 的**唯一**合法通道。

```
handoff/
├── candidate/    DIRTY 写入候选规格；COUNSEL/AUDIT 只读
├── approved/     COUNSEL 写入批准规格；CLEAN/AUDIT 只读
└── rejected/     COUNSEL 退回的候选；DIRTY 可修订后重新投递
```

规则：

1. 禁止把 APK、DEX、反编译源码、截图、抓包、原始资源放入本目录。
2. 候选包必须带 `CAND-*` 编号、`manifest.json`（文件列表 + 哈希 + 基线 + 作者）。
3. 批准包必须带 `LEG-*` 记录编号与批准哈希；无记录视为未批准。
4. CLEAN 只能读 `approved/`，不得读 `candidate/`、`rejected/` 或 Dirty 工作区。
5. 任何越界读取按 `INC-*` 处理，并评估受影响实现是否需要重做。
6. 目录权限：`candidate/` 仅 DIRTY 写；`approved/` 仅 COUNSEL 写；CLEAN 只读。

## 当前状态

| 目录 | 内容 | 状态 |
|---|---|---|
| `candidate/` | 空（待从 Dirty `specs/` 投递） | 未投递 |
| `approved/` | 空 | **无批准规格；CLEAN 不得据此新增实现** |
| `rejected/` | 空 | — |
