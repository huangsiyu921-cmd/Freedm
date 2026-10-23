# 规范性隐私基线

> `DOCUMENT-CLASS: NORMATIVE`
> `BASELINE: SPEC-BASELINE-2026-10-23`
> `LEGAL-STATUS: NOT_REVIEWED / BLOCKED`
>
> 隐私基线是硬性验收项；任一不满足即不得发布。静态检查与运行检查都必须留证。

| ID | 级别 | 条款 | 验证 |
|---|---|---|---|
| PRV-001 | MUST | 不得集成任何分析、统计、崩溃上报或归因 SDK；依赖清单中不得出现 firebase、crashlytics、umeng、bugly、adjust、installreferrer 等。 | 依赖树静态检查 |
| PRV-002 | MUST | 不得读取 `ANDROID_ID`、IMEI、MEID、序列号、MAC、SIM/运营商信息。 | 源码 grep + 运行权限检查 |
| PRV-003 | MUST | 不得使用安装来源归因（InstallReferrer）。 | 依赖/权限检查 |
| PRV-004 | MUST | 应用内唯一标识必须是启动时生成的随机 UUID，仅存本地，且与任何设备属性无关。 | 代码审查 + 清除数据后对比 |
| PRV-005 | MUST | 除用户输入的 URL、内置浏览器导航与用户发起的下载外，不得产生任何出网请求。 | 抓包 + 端点扫描 |
| PRV-006 | MUST | 断网时，除下载外的功能（列表、设置、历史、主题、隐私说明）必须可用。 | 飞行模式测试 |
| PRV-007 | MUST | 权限清单必须最小化，目标为 6 项：`INTERNET`、`ACCESS_NETWORK_STATE`、`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_DATA_SYNC`、`POST_NOTIFICATIONS`、`WAKE_LOCK`；Android ≤9 可追加 `WRITE_EXTERNAL_STORAGE`。 | `aapt2 dump permissions` |
| PRV-008 | MUST | 必须提供“隐私说明”页，逐条列出不收集的项目，并与实际行为一致。 | 交互检查 + 代码对照 |
| PRV-009 | MUST | 诊断日志只写本地，不含设备标识、完整 URL 查询参数或用户内容；可关闭、可清空。 | 日志抽样 |
| PRV-010 | MUST | 广告过滤规则随包分发，不得联网订阅；用户导入规则只存本地。 | 抓包 + 文件检查 |

## 权限清单（目标）

```
INTERNET
ACCESS_NETWORK_STATE
FOREGROUND_SERVICE
FOREGROUND_SERVICE_DATA_SYNC
POST_NOTIFICATIONS
WAKE_LOCK
```

Android ≤9：`WRITE_EXTERNAL_STORAGE`（仅在用户选择公共目录时按需申请）。

## 禁用的权限/能力

相机（扫码）、麦克风（语音）、通讯录、短信、电话状态、位置、后台读取剪贴板、悬浮窗。

## 验收映射

| 隐私条款 | 验收 |
|---|---|
| PRV-001 | ACC-PRV-001 |
| PRV-002 | ACC-PRV-002 |
| PRV-003 | ACC-PRV-003 |
| PRV-004 | ACC-PRV-004 |
| PRV-005 | ACC-PRV-005 |
| PRV-006 | ACC-PRV-006 |
| PRV-007 | ACC-PRV-007 |
| PRV-008 | ACC-PRV-008 |
| PRV-009 | ACC-BR-005 |
| PRV-010 | ACC-BR-004 |
