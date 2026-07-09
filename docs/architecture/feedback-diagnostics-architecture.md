# 反馈、诊断与 GitHub 链路专题

用于回答两个问题：

- 为什么反馈系统不是“客户端直接发一个 issue”？
- 诊断、Issue、邮件通知与客户端订阅是如何形成闭环的？

## 1. 子系统目标

该子系统负责：

- 客户端收集环境信息、启用模组快照、日志摘要与截图
- 构建标准化诊断归档
- 上传反馈包到中继服务
- 在 GitHub 上创建与同步 issue
- 保存诊断资产
- 客户端本地缓存与订阅 issue 状态

## 2. 子系统上下文图

```mermaid
flowchart LR
    User["玩家"]:::actor
    Maintainer["维护者"]:::actor

    Client["Android 客户端<br/>Feedback UI + Diagnostics Builder"]:::sys
    Relay["cloud-function<br/>Express / Multipart Relay"]:::sys
    Issues["GitHub Issues"]:::ext
    Assets["GitHub Diagnostics / Release Assets"]:::ext
    Mail["SMTP"]:::ext

    User --> Client
    Client --> Relay
    Relay --> Issues
    Relay --> Assets
    Relay --> Mail
    Maintainer --> Issues

    classDef actor fill:#fff7e6,stroke:#c98a00,color:#222;
    classDef sys fill:#e6fffa,stroke:#2f855a,color:#1f3d2d;
    classDef ext fill:#f7fafc,stroke:#718096,color:#1a202c;
```

## 3. 客户端内部组件图

```mermaid
flowchart TB
    subgraph Client["Android 客户端"]
        UI["Feedback UI / ViewModels"]:::app
        Submit["FeedbackSubmissionService"]:::app
        Archive["DiagnosticsArchiveBuilder"]:::app
        LogAnalyzer["FeedbackLogAnalyzer"]:::app
        IssueSync["FeedbackIssueSyncService"]:::app
        LocalStore["FeedbackIssueLocalStore"]:::app
        Crash["ProcessExitInfoCapture / LatestLogCrashDetector"]:::app
    end

    subgraph Remote["远端"]
        Relay["cloud-function"]:::remote
        GitHub["GitHub Issues / Assets"]:::remote
    end

    UI --> Submit
    Submit --> Archive
    Submit --> LogAnalyzer
    Submit --> Crash
    Submit --> Relay

    UI --> IssueSync
    IssueSync --> LocalStore
    IssueSync --> GitHub
    Relay --> GitHub

    classDef app fill:#ebf8ff,stroke:#3182ce,color:#1a365d;
    classDef remote fill:#f7fafc,stroke:#718096,color:#1a202c;
```

## 4. 反馈提交时序图

```mermaid
sequenceDiagram
    actor User as 用户
    participant UI as Feedback UI
    participant Submit as FeedbackSubmissionService
    participant Archive as DiagnosticsArchiveBuilder
    participant Relay as cloud-function
    participant Assets as GitHub Diagnostics Assets
    participant Issues as GitHub Issues
    participant Mail as SMTP

    User->>UI: 填写反馈并提交
    UI->>Submit: submit(draft)
    Submit->>Archive: 构建诊断归档 zip
    Archive-->>Submit: archiveFile
    Submit->>Relay: multipart 上传 payload + issue body + archive + screenshots
    Relay->>Assets: 保存诊断 bundle / metadata
    Relay->>Issues: 创建或更新 issue
    Relay->>Mail: 发送创建通知（可选）
    Relay-->>Submit: issueNumber / issueUrl
    Submit-->>UI: 提交结果
```

## 5. 问题同步与本地订阅图

```mermaid
flowchart LR
    subgraph Client["Android 客户端"]
        Browser["Issue Browser / Conversation UI"]:::app
        Sync["FeedbackIssueSyncService"]:::app
        Cache["FeedbackIssueLocalStore"]:::app
        Mirror["GithubMirrorFallback / Accelerated HTTP"]:::app
    end

    GitHub["GitHub API / Issues"]:::ext

    Browser --> Sync
    Sync --> Cache
    Sync --> Mirror
    Mirror --> GitHub

    classDef app fill:#ebf8ff,stroke:#3182ce,color:#1a365d;
    classDef ext fill:#f7fafc,stroke:#718096,color:#1a202c;
```

### 设计含义

- 客户端不是“只发一次请求就结束”，而是长期维护本地 issue 订阅与缓存。
- `FeedbackIssueSyncService` 负责浏览、订阅、刷新、未读状态与本地缓存合并。
- GitHub 访问还经过镜像/加速与回退策略，而不是纯裸连。

## 6. 诊断归档内容图

```mermaid
flowchart TB
    Archive["Diagnostics Archive ZIP"]:::bundle

    Archive --> Jvm["JVM Logs<br/>latest.log / jvm_logs / boot_bridge_events.log"]:::entry
    Archive --> Exit["Process Exit / Signal / Crash Summary"]:::entry
    Archive --> Settings["Launcher Settings Snapshot"]:::entry
    Archive --> SteamCloud["Steam Cloud Diagnostics"]:::entry
    Archive --> Workshop["Workshop Download / Auto Import Logs"]:::entry
    Archive --> Logcat["logcat / launcher logcat"]:::entry
    Archive --> Hist["Heap / GC / Histogram / Memory Diagnostics"]:::entry

    classDef bundle fill:#fffaf0,stroke:#b7791f,color:#4a2c00;
    classDef entry fill:#f7fafc,stroke:#718096,color:#1a202c;
```

## 7. 为什么采用“客户端 + 中继 + GitHub 资产”架构

1. 诊断包体积和结构明显超出“直接 issue 文本提交”的适用范围。
2. 诊断资产写入 Release assets，不污染主仓库 Git 历史。
3. 邮件通知、私有联系信息处理与 webhook 回调更适合在中继端完成。
4. 客户端本地仍然保留 issue 浏览、订阅和同步能力，形成闭环。

## 8. 关键架构结论

1. 客户端负责“采集、归档、上传前组装”。
2. 中继负责“鉴权、资产落盘、issue 创建、邮件通知、webhook 事件处理”。
3. GitHub 同时承担“工单系统”和“诊断资产索引”两个角色，但数据面被拆分到 Issues 与 Release Assets。
4. 该设计把终端环境证据与维护者处理链路真正连接起来，而不是只做一个表单提交。
