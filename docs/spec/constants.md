# 规范性常量

> `DOCUMENT-CLASS: NORMATIVE`
> `BASELINE: SPEC-BASELINE-2026-10-23`
> `LEGAL-STATUS: NOT_REVIEWED / BLOCKED`
>
> 常量属于可观察行为的一部分；修改常量必须走规格变更，不得只改实现。

| ID | 常量 | 值 | 用途 | 验收 |
|---|---|---|---|---|
| CONST-DL-001 | 探测连接超时 / 读超时 | 20 s / 20 s | 能力探测 | ACC-DL-003 |
| CONST-DL-002 | 下载连接超时 / 读超时 | 20 s / 30 s | 分片下载 | ACC-DL-006 |
| CONST-DL-003 | 读缓冲 | 64 KiB | 所有流读取 | ACC-DL-005 |
| CONST-DL-004 | 分片粒度 | 8 MiB | 分片计划 | ACC-DL-004 |
| CONST-DL-005 | 分片上限 | 64 | 分片计划 | ACC-DL-004 |
| CONST-DL-006 | 不分段阈值 | 总大小 < 2 × 8 MiB | 分片计划 | ACC-DL-003 |
| CONST-DL-007 | 单请求重试次数 | 3 | 分片下载 | ACC-DL-006 |
| CONST-DL-008 | 重试退避 | 1 s / 2 s / 4 s | 分片下载 | ACC-DL-006 |
| CONST-DL-009 | 进度流刷新 | 500 ms | 界面进度 | ACC-DL-005 |
| CONST-DL-010 | 任务级落库 | 500 ms | 持久化 | ACC-DL-007 |
| CONST-DL-011 | 分片级落库 | 1 s 或 1 MiB，取先到 | 续传锚点 | ACC-DL-007 |
| CONST-DL-012 | 默认线程数 | 8（范围 1–16） | 分片下载 | ACC-DL-004 |
| CONST-DL-013 | 默认并发任务数 | 3（范围 1–8） | 调度 | ACC-DL-012 |
| CONST-DL-014 | 测速窗口 | 3 s | 速度/剩余时间 | ACC-DL-009 |
| CONST-DL-015 | 文件名长度上限 | 120 字符（保留扩展名） | 文件名 | 单元测试 |
| CONST-DL-016 | 重名后缀上限 | 999 | 文件名 | 单元测试 |
| CONST-DL-017 | 诊断日志轮转上限 | 约 1 MiB（两份） | 诊断 | ACC-BR-005 |
| CONST-BR-001 | 单次页面扫描上限 | 50 条 | 嗅探 | ACC-BR-002 |
| CONST-BR-002 | 媒体体积过滤 | `video` 宽或高 > 100 | DOM 扫描 | ACC-BR-002 |

## 变更规则

- 常量变化必须新建 `CTRL-*` 变更记录，说明影响的 `REQ-*`/`ACC-*`。
- 不得在描述性文档中另立一套常量而不更新本文件。
