# 反馈系统设计

## 当前实现

### 入口

- 设置页顶部新增了一个“问题反馈”卡片，作为统一入口。
- 入口会跳转到新的反馈表单页面。

### 反馈类型

表单支持三种类型：

1. 功能建议
2. 启动器 Bug
3. 游戏内 Bug

其中，游戏内 Bug 流程会强制用户继续判断：

1. 是否为最近一次运行复现
2. 怀疑的模组
3. 问题表现类型（卡顿 / 显示不正常 / 崩溃）

“怀疑的模组”仍然是必填项，但提供“不确定”选项，避免用户被迫误判。

### 自动附带信息

提交时客户端会自动整理：

- `latest.log` 与轮转 JVM 日志
- `boot_bridge_events.log`
- JVM histogram / GC / heap snapshot（若存在）
- 启用模组快照
- App 版本
- Android 版本
- 设备厂商 / 型号 / ABI
- CPU 型号与架构
- 可用内存 / 总内存
- `latest.log` 关键行摘要

如果用户在表单中附加截图，截图也会一起打进反馈压缩包。

### 游戏内 Bug 的“最近一次运行”

如果用户选择“不是最近一次运行复现”，客户端不会阻止提交，但会在 UI 中提示：

> 建议先复现一次以便收集更完整日志，但也可以继续提交。

### 崩溃问题

如果用户选择“游戏内 Bug -> 崩溃”，客户端会优先尝试读取最近的 `ProcessExitInfo`，并把崩溃上下文写入诊断包。

## 客户端上传行为

### 构建配置

当前客户端已经把上传地址写死为：

```text
http://1315061624-boxfc2p5fb.ap-guangzhou.tencentscf.com/api/sts-feedback
```

如果需要云函数密钥，仍然可以在 `gradle.properties` 中配置：

```properties
feedback.apiKey=optional-secret
```

### 上传方式

客户端通过 `multipart/form-data` POST 到固定地址 `http://1315061624-boxfc2p5fb.ap-guangzhou.tencentscf.com/api/sts-feedback`。

字段如下：

- `payload_json`
  - 结构化 JSON，包含反馈类型、描述、复现步骤、环境信息、启用模组、怀疑模组、截图元数据、Issue 标题和 Issue 正文
- `issue_title`
  - 已格式化的一行标题
- `issue_body`
  - 已格式化的 Markdown Issue 正文
- `bundle`
  - 诊断压缩包，文件名形如 `sts-feedback-report-YYYYMMDD-HHMMSS.zip`

如果配置了 `feedback.apiKey`，客户端会额外发送请求头：

```text
X-Feedback-Key: <feedback.apiKey>
```

## 云函数实现

当前仓库内已经新增 `cloud-function/` 目录，基于你提供的 Express 模板扩展成实际可用的反馈 relay。

当前云函数会完成以下工作：

1. 校验可选请求头 `X-Feedback-Key`
2. 接收 `payload_json`、`issue_title`、`issue_body` 与 `bundle`
3. 将诊断压缩包与 metadata JSON 上传到 GitHub `Release assets`，默认存到 `SlayTheDiagnostics` 仓库
4. 如果是“功能建议”，会在创建 Issue 前从正文里剔除“环境信息”“启用模组快照”“latest.log 关键行”三段
5. 调用 GitHub Issues API 创建 Issue
6. 在 Issue 正文末尾追加一段“云函数记录”，写入 `Request ID` 和诊断包链接
7. 返回 JSON，包含：
   - `issueNumber`
   - `issueUrl`
   - `diagnosticBundle`

功能建议的诊断压缩包仍然会照常上传；这里只是裁剪 GitHub Issue 正文里不必要的三段诊断摘要。

### 云函数环境变量

必填：

- `GITHUB_OWNER`
- `GITHUB_REPO`
- `GITHUB_APP_ID`
- `GITHUB_APP_INSTALLATION_ID`
- `GITHUB_APP_PRIVATE_KEY`

可选：

- `FEEDBACK_SHARED_SECRET`
- `GITHUB_TOKEN`
- `GITHUB_ISSUE_LABELS`
- `GITHUB_DIAGNOSTICS_OWNER`
- `GITHUB_DIAGNOSTICS_REPO`
- `GITHUB_DIAGNOSTICS_BRANCH`
- `GITHUB_DIAGNOSTICS_RELEASE_PREFIX`
- `BUNDLE_MAX_BYTES`
- `PORT`

如果未配置 `GITHUB_DIAGNOSTICS_REPO`，云函数会默认把诊断资源写入 `SlayTheDiagnostics`。

诊断资源不会写进 Git 提交历史，而是写到 diagnostics 仓库的每日 Release assets 中。

如果配置了 `GITHUB_APP_*`，Issue 与 diagnostics 上传会归属于 GitHub App，而不是个人账号。

`GITHUB_TOKEN` 仅作为兼容回退方案保留；默认部署应使用 GitHub App installation token。

如果诊断压缩包持久化失败，云函数仍会继续创建 Issue，但会在“云函数记录”里标明上传失败原因。

### 部署入口

当前客户端实际使用的提交入口为：

```text
POST /api/sts-feedback
```

健康检查：

```text
GET /
GET /healthz
```

如果云函数额外兼容 `POST /`，可以继续保留，但客户端不会依赖它。

客户端会优先解析以下字段：

- 顶层 `issueUrl` / `issue_url` / `html_url` / `url`
- 顶层 `issueNumber` / `issue_number` / `number`
- 或 `issue` 对象中的同名字段

## 诊断压缩包结构

反馈压缩包会在原有诊断压缩包基础上增加：

- `sts/feedback/issue_title.txt`
- `sts/feedback/issue_body.md`
- `sts/feedback/request.json`
- `sts/feedback/enabled_mods.txt`
- `sts/feedback/latest_log_summary.txt`
- `sts/feedback/screenshots/*`

## 当前限制

1. 截图上限为 4 张。
2. 截图通过文档选择器导入后会先复制到应用缓存目录。
3. 当前入口只在“设置”页顶部提供，尚未接入崩溃弹窗后的直接跳转。
4. 云函数与 GitHub 的实际鉴权、去重、频控、反滥用逻辑仍需在服务端完成。
