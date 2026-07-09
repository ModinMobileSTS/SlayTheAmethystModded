# SlayTheAmethyst 架构汇报顺序建议

这份文档用于把主报告与附录专题图组织成一套可直接汇报的材料。

主报告入口：

- [系统架构设计图集](../system-architecture-report.md)

附录专题图：

- [Workshop 与 Steam 协议栈专题](./workshop-steam-architecture.md)
- [反馈、诊断与 GitHub 链路专题](./feedback-diagnostics-architecture.md)
- [Runtime Compat 补丁域专题](./runtime-compat-architecture.md)

## 建议的汇报结构

### 第 1 页：系统定位

使用主报告中的“系统上下文图”。

讲解重点：

- 这不是普通移动 App，而是 Android 上的桌面 JVM 托管系统。
- 启动器同时承担游戏启动、模组管理、Workshop、Steam Cloud、反馈、Presence、更新入口。
- 系统边界外最关键的依赖是 Steam、反馈中继、Presence 服务、GitHub 资产与更新源。

### 第 2 页：总体容器设计

使用主报告中的“系统容器图”。

讲解重点：

- 移动端主应用与“设备端桌面运行时”是两个不同容器。
- 云侧不是单一后端，而是按能力拆成 Presence 与 Feedback Relay 两条链。
- 更新与资源分发也是独立外部依赖，不与反馈链路耦合。

### 第 3 页：Android 多进程部署

使用主报告中的“Android 部署与进程图”。

讲解重点：

- 默认进程负责 UI 与启动准备。
- `:game` 进程隔离桌面 JVM 与渲染生命周期。
- `:diag`、`:logcat`、`:steamcloud` 进一步隔离重任务与诊断任务。

### 第 4 页：运行时装配机制

使用主报告中的“运行时装配图”。

讲解重点：

- 构建产物不是直接链接，而是以运行时资产形式打包进 APK。
- 设备端启动前会落地 `boot-bridge`、`agent-connector`、`gdx-patch`、bundled mods 与 runtime pack。
- 运行中的核心对象是设备端桌面 JVM，不是 Android 业务对象本身。

### 第 5 页：关键启动时序

使用主报告中的“关键启动时序图”。

讲解重点：

- 启动准备先在默认进程完成。
- `BootBridgeLauncher` 是 JVM 入口桥，而不是最终业务入口。
- 最终委托给 `MTS Loader` 或 `DesktopLauncher`。

### 第 6 页：应用内部分层

使用主报告中的“`:app` 内部组件图”。

讲解重点：

- `backend.launch` 是核心编排层。
- `backend.mods` 是项目差异化最强的特有子系统。
- `workshop-core` 与 `steam-protocol` 被抽成共享库，而不是塞进 `:app` 巨型模块。

### 第 7 页：专题一，Workshop 与 Steam 协议栈

使用：

- [Workshop 与 Steam 协议栈专题](./workshop-steam-architecture.md)

讲解重点：

- Workshop 元数据访问、订阅管理、下载执行是分层处理的。
- 下载链路存在 `file_url` 直链路径与 `UGC Manifest` 路径两种模式。
- 该子系统最复杂的部分不是 UI，而是协议解析、会话、CDN、chunk 下载与组装。

### 第 8 页：专题二，反馈与诊断闭环

使用：

- [反馈、诊断与 GitHub 链路专题](./feedback-diagnostics-architecture.md)

讲解重点：

- 反馈不是单纯发文本，而是“设备诊断包 + Issue 同步 + 邮件通知”的闭环。
- 客户端先本地构建诊断包，再上传到中继。
- 中继负责落 GitHub Issues、诊断资产和邮件，不把所有逻辑硬塞在客户端。

### 第 9 页：专题三，Runtime Compat 设计

使用：

- [Runtime Compat 补丁域专题](./runtime-compat-architecture.md)

讲解重点：

- 兼容性修复大量以 ModTheSpire Patch 形式交付，而不是直接改桌面主 Jar。
- 补丁按域拆成 `ui`、`touch`、`rescue`、`compatibility`、`diagnostics`、`autoplay` 等。
- 启动参数与系统属性负责控制 patch 组的激活和 cache-hit-only 行为。

### 第 10 页：架构权衡与风险

建议口径：

- 优点：兼容能力强、运行时可观测性高、桌面模组生态复用度高、云侧职责拆分明确。
- 代价：启动链复杂、运行时资产多、跨进程状态与诊断链路较长、调试成本高于普通 App。
- 后续重点：持续控制 `backend.launch` 与 `backend.mods` 的复杂度，保持诊断资产和启动阶段契约稳定。

## 问答跳转建议

如果被问到下面这些问题，建议直接跳到对应材料：

| 问题 | 建议跳转 |
| --- | --- |
| 为什么要多进程？ | 主报告“Android 部署与进程图” |
| 为什么不是普通安卓游戏启动方式？ | 主报告“运行时装配图” + “关键启动时序图” |
| Workshop 为什么复杂？ | `workshop-steam-architecture.md` |
| 反馈为什么要 GitHub 和诊断资产仓库？ | `feedback-diagnostics-architecture.md` |
| 兼容补丁为什么做成 Mod？ | `runtime-compat-architecture.md` |
| cache-hit-only 优化怎么控制？ | `runtime-compat-architecture.md` |
