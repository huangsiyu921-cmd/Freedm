# 独立验证记录模板（REV）

> 复制为 `governance/reviews/REV-<序号>-<子系统>.md`。未填完整或独立性不成立的记录无效。

```
review_id: REV-___
scope: <子系统 / 模块>
requirements: <REQ-* / INV-* / ERR-* 列表>
acceptance: <ACC-* 列表>
build:
  version: <版本>
  commit: <提交>
  artifact_sha256: <安装包/产物哈希>
  toolchain: <JDK/Gradle/AGP/SDK>

author(s): <实现者姓名>
reviewer: <评审者姓名>
independence:
  not_author: true
  read_source: false
  read_design_docs: false
  discussed_implementation: false
conflict_note: <如有，说明>

environment:
  device: <型号/AVD>
  os: <Android 版本/API>
  network: <条件>
  date: <ISO-8601>

results:
| ACC ID | 判定 | 证据 ID | 备注 |
|---|---|---|---|
| ACC-___ | PASS/FAIL/SKIP | EV-___ | |

failures:
- <ACC ID> → <GAP ID>（必须登记，不得只写在备注里）

conclusion: PASS / FAIL / CONDITIONAL
conditions: <CONDITIONAL 时的条件>
signed_by: <评审者>
signed_at: <ISO-8601>
audit_review: <AUDIT 复核人 + 日期>
```

## 评审者声明

```
我确认：
[ ] 我不是该子系统的作者；
[ ] 我没有阅读该子系统的源码或实现文档；
[ ] 我没有与该子系统作者讨论实现细节；
[ ] 我只使用了盲测包与验收步骤；
[ ] 以上结果与证据可复核。
签名：
```
