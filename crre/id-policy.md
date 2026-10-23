# ID 策略、缺口状态机与评审独立性

## 1. ID 格式

```
<类型>-<域>-<三位序号>
```

| 类型 | 用途 | 示例 |
|---|---|---|
| `REQ` | 需求/行为条款 | `REQ-DL-001` |
| `INV` | 不变量 | `INV-DL-001` |
| `ERR` | 错误条件/失败处理 | `ERR-DL-001` |
| `PRV` | 隐私基线 | `PRV-001` |
| `ACC` | 验收标准 | `ACC-DL-001` |
| `CONST` | 规定性常量 | `CONST-DL-001` |
| `DEC` | 设计决策（描述性） | `DEC-001` |
| `OBS` | 观察记录 | `OBS-001` |
| `CAND` | 候选规格包 | `CAND-DL-001` |
| `GAP` | 缺口 | `GAP-001` |
| `INC` | 审计发现/违规事件 | `INC-001` |
| `EV` | 证据 | `EV-G1-001` |
| `LEG` | 法律审查记录 | `LEG-001` |
| `REV` | 独立验证记录 | `REV-001` |
| `CTRL` | 治理变更记录 | `CTRL-001` |

域：`DL` 下载引擎、`BR` 浏览器/嗅探、`SH` 分享/剪贴板、`ST` 存储、`UI` 界面、`PRV` 隐私、`BLD` 构建。

序号三位、从 001 起；不复用、不重排。新增条款只能取当前最大序号 + 1。

---

## 2. 分配与登记

1. 作者在条款首次出现时分配 ID，并在 `registry/ids.csv` 登记：`id,type,title,source,status,owner,created_at`。
2. `status`：`DRAFT` / `PROPOSED` / `APPROVED` / `IMPLEMENTED` / `VERIFIED` / `WITHDRAWN`。
3. 修改条款正文不改变 ID；改变语义则新建 ID，并在旧 ID 上标 `WITHDRAWN` + `superseded_by`。
4. 废弃 ID 写入 `registry/retired-ids.txt`，永久保留。
5. 自动检查：`crre_check.py` 校验重复、未登记、退役 ID 被复用、验收缺 ID。

---

## 3. 规范性与描述性分离

| 文档 | 类别 | 允许内容 |
|---|---|---|
| `docs/spec/*.md` | NORMATIVE | MUST/SHALL/SHOULD/MAY、验收映射、常量 |
| `docs/acceptance.md` | NORMATIVE | 验证方法、判定标准、状态 |
| `docs/logic-spec.md` | INFORMATIVE | 设计说明、伪代码、实现细节 |
| `docs/implementation-guide.md` | INFORMATIVE | 调用序列、线程模型、解释 |
| `docs/ui-design.md` | INFORMATIVE | 界面设计说明 |
| `docs/known-pitfalls.md` | INFORMATIVE | 经验与故障模式 |
| `HANDOFF.md` / `README.md` | INFORMATIVE | 交接与介绍 |

规则：
- 规范性文档不写“我们这样做”“代码里是……”；只写可验证的约束。
- 描述性文档不产生新约束；出现“必须”时视为解释，权威条款在 `docs/spec/`。
- 描述性文档中的示例代码不得成为验收依据。

---

## 4. 缺口（GAP）状态机

```
OPEN ──► TRIAGED ──┬──► RESOLVED
                   ├──► ACCEPTED
                   ├──► DEFERRED ──► TRIAGED（到期复核）
                   └──► BLOCKED ──► TRIAGED（解除条件满足）
```

| 状态 | 含义 | 进入条件 | 关闭/转移证据 |
|---|---|---|---|
| `OPEN` | 已发现，未评估 | 任何人登记 | — |
| `TRIAGED` | 已定级、定责、定方案 | AUDIT 确认影响与优先级 | 处置计划 |
| `RESOLVED` | 已修复并验证 | 代码/文档已改，且验证通过 | 提交/文档哈希 + 验证记录 |
| `ACCEPTED` | 明确接受风险，不修复 | OWNER + AUDIT 书面接受 | 风险说明、范围、到期复核日 |
| `DEFERRED` | 推迟，有到期日 | OWNER 批准 | 到期日、复核人 |
| `BLOCKED` | 无法推进 | 依赖外部（法律/设备/上游） | 阻塞原因、解除条件、责任人 |

强制规则：
- 无证据不得进入 `RESOLVED`。
- `ACCEPTED` 不得用于规避法律审查、隐私基线或安全缺陷。
- `DEFERRED` 必须有到期日；到期自动回 `TRIAGED`。
- 关键（Critical/High）缺口不得在 `OPEN`/`BLOCKED` 状态下发布。

---

## 5. 评审独立性

### 5.1 验证评审者资格（全部满足）
- 不是该子系统的作者（代码、设计、规格任一）。
- 未阅读该子系统的实现文档或源码。
- 未参与该子系统的调试/性能优化讨论。
- 只接收盲测包：批准规格、`ACC-*`、安装包、测试数据。

### 5.2 评审记录必填字段

```
review_id: REV-###
scope: 子系统 + REQ/ACC 范围
author(s): 实现者姓名
reviewer: 姓名
independence:
  - not_author: true/false
  - read_source: false
  - read_design_docs: false
  - discussed_implementation: false
environment: 设备/OS/构建哈希
results: 每条 ACC 的 PASS/FAIL/SKIP + 证据 EV
conclusion: PASS / FAIL / CONDITIONAL
signed_at:
```

任一项为 `false` 或不实，结论作废。

### 5.3 法律审查独立性
- COUNSEL 不得是实现者或分析者。
- COUNSEL 不得向 CLEAN 口述被剔除内容。
- COUNSEL 的批准只覆盖其审查过的版本哈希；版本变化需重新审查或书面确认差异不影响结论。
