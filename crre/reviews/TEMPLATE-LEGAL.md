# 法律审查记录模板（LEG）

> 复制为 `crre/reviews/LEG-<序号>-<规格包>.md`。只有 COUNSEL 可签署。
> 本模板不构成法律意见；审查范围与适用法域由 COUNSEL 填写。

```
review_id: LEG-___
candidate_package: CAND-___
candidate_sha256: <候选规格包哈希>
baseline: SPEC-BASELINE-___
counsel: <姓名>
date: <ISO-8601>
jurisdiction_scope: <适用法域/合同/政策>
conflict_check: <与 DIRTY/CLEAN 无隶属/雇佣/利益关系>

scope:
- <规格文件列表>

method:
- 逐条检查：代码/伪代码、命名、布局、视觉、文案、商标、事实性数据
- 对照原始证据仅用于判断是否构成复制，不把原始表达写入批准文本

findings:
| 条款 ID | 判定 KEEP/REDACT/REJECT | 理由 | 处理 |
|---|---|---|---|
| REQ-___ | | | |

redaction_summary:
- 删除的类别与数量（不复制原文）：
- 保留的事实性数据及理由：
- 无法完全清除的相似性及限制：

decision: APPROVED / APPROVED-WITH-REDACTIONS / REJECTED
approved_package_sha256: <批准包哈希>
conditions:
- <版本变更需重新审查/差异确认等>

signed_by: <COUNSEL>
signed_at: <ISO-8601>
```

## 审查员声明

```
我确认：
[ ] 我独立于分析组与实现组；
[ ] 我没有参与目标软件的实现或本项目的代码编写；
[ ] 我未向实现组口述被剔除的原始表达；
[ ] 我的结论仅覆盖上述哈希对应的版本；
[ ] 如版本变化，我将重新评估或书面确认差异不影响结论。
签名：
```
