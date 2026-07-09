# Workshop 与 Steam 协议栈专题

用于回答两个问题：

- Workshop 功能为什么不是一个普通 HTTP 下载器？
- App、共享库、Steam 协议与内容下载之间的边界如何划分？

## 1. 子系统目标

该子系统负责：

- 浏览 Workshop 内容
- 查询已订阅项
- 订阅 / 退订
- 下载 Workshop 内容
- 管理下载进度、后台服务、日志与落盘结果

## 2. 容器内部分层图

```mermaid
flowchart TB
    subgraph App["`:app`"]
        UI["Workshop UI<br/>WorkshopViewModel / Screens"]:::app
        Service["WorkshopService"]:::app
        Bg["WorkshopDownloadProcessService"]:::app
        DownloaderAdapter["WorkshopContentDownloader<br/>SteamPipeWorkshopContentDownloader"]:::app
        Storage["Metadata / Preview / Task / Log Stores"]:::app
    end

    subgraph Shared["共享库"]
        Engine["WorkshopDownloadEngine"]:::lib
        Resolver["PublishedFileResolver"]:::lib
        Direct["DirectWorkshopDownloader"]:::lib
        Ugc["UgcWorkshopDownloader"]:::lib
        Protocol["steam-protocol"]:::lib
    end

    subgraph Steam["Steam 外部服务"]
        PublishedApi["PublishedFile / RemoteStorage API"]:::ext
        Directory["Steam Directory / CM Server Directory"]:::ext
        CM["Steam CM WebSocket"]:::ext
        CDN["Steam CDN / SteamPipe Content Servers"]:::ext
        Community["Steam Community / Web Detail"]:::ext
    end

    UI --> Service
    UI --> Bg
    Service --> Storage
    Bg --> DownloaderAdapter
    DownloaderAdapter --> Engine
    Service --> Protocol
    Service --> Community
    Engine --> Resolver
    Engine --> Direct
    Engine --> Ugc
    Resolver --> PublishedApi
    Ugc --> Protocol
    Protocol --> Directory
    Protocol --> CM
    Ugc --> CDN

    classDef app fill:#e6fffa,stroke:#2f855a,color:#1f3d2d;
    classDef lib fill:#ebf8ff,stroke:#3182ce,color:#1a365d;
    classDef ext fill:#f7fafc,stroke:#718096,color:#1a202c;
```

## 3. 关键职责分工

| 层次 | 主要职责 |
| --- | --- |
| `WorkshopViewModel / UI` | 用户交互、页面状态与下载命令发起 |
| `WorkshopService` | 浏览、订阅、详情补全、Steam 登录态读取、App 侧编排 |
| `WorkshopDownloadProcessService` | 后台下载生命周期、通知、并发槽位与任务队列管理 |
| `WorkshopContentDownloader` | 适配 `WorkshopDownloadEngine` 输出为启动器内部事件模型 |
| `WorkshopDownloadEngine` | 统一下载编排入口，决定走直链还是 UGC Manifest |
| `PublishedFileResolver` | 调用 Steam Published File 详情接口并解析下载模式 |
| `DirectWorkshopDownloader` | 处理 `file_url` 直链下载、断点续传与落盘 |
| `UgcWorkshopDownloader` | 处理 SteamPipe / UGC Manifest / chunk / CDN 下载与组装 |
| `steam-protocol` | CM 会话、目录服务、内容服务、Published File 协议调用 |

## 4. 下载路径分叉图

```mermaid
flowchart LR
    Request["下载请求<br/>appId + publishedFileId"] --> Resolver["PublishedFileResolver"]

    Resolver --> Decision{"解析结果"}
    Decision -->|"file_url 存在"| Direct["DirectWorkshopDownloader<br/>直链下载"]
    Decision -->|"hcontent_file 存在"| Ugc["UgcWorkshopDownloader<br/>UGC Manifest 下载"]

    Direct --> DirectFile["单文件 / 断点续传 / 最终落盘"]

    Ugc --> Directory["SteamDirectoryClient<br/>加载 CM 与内容服务器目录"]
    Ugc --> Session["SteamCmSession / SteamContentClient<br/>连接 CM、取 manifest request code"]
    Ugc --> Manifest["下载 Depot Manifest"]
    Ugc --> Chunks["chunk 并发下载 / 校验 / 缓存"]
    Ugc --> Assemble["按 Manifest 组装输出文件"]
```

### 设计含义

- Workshop 内容并不总是一个公开文件 URL。
- 当存在 `hcontent_file` 时，需要走 Steam 的更底层内容分发路径。
- 因此该子系统天然包含协议解析、目录发现、内容服务器选择、manifest 解析与 chunk 组装。

## 5. 浏览与订阅链路图

```mermaid
sequenceDiagram
    actor User as 用户
    participant UI as Workshop UI
    participant WS as WorkshopService
    participant Auth as SteamCloudAuthStore
    participant PF as SteamPublishedFileClient
    participant Dir as SteamDirectoryClient
    participant CM as Steam CM

    User->>UI: 搜索 / 查看已订阅 / 订阅模组
    UI->>WS: browse / browseSubscriptions / subscribe
    WS->>Auth: 读取 Steam 登录态
    WS->>PF: 发起 Published File 协议请求
    PF->>Dir: 加载 CM 服务器目录
    PF->>CM: 建立会话并调用服务方法
    CM-->>PF: 返回搜索 / 订阅结果
    PF-->>WS: 返回标准化 items
    WS-->>UI: 返回启动器视图模型
```

## 6. 后台下载时序图

```mermaid
sequenceDiagram
    actor User as 用户
    participant UI as Workshop UI
    participant BG as WorkshopDownloadProcessService
    participant Adapter as SteamPipeWorkshopContentDownloader
    participant Engine as WorkshopDownloadEngine
    participant Resolver as PublishedFileResolver
    participant Steam as Steam API / CM / CDN

    User->>UI: 点击下载
    UI->>BG: start(details)
    BG->>Adapter: download(details, outputDir)
    Adapter->>Engine: download(appId, publishedFileId, outputDir)
    Engine->>Resolver: resolve()
    Resolver->>Steam: GetPublishedFileDetails
    Steam-->>Resolver: file_url 或 hcontent_file
    Resolver-->>Engine: ResolvedWorkshopItem
    Engine->>Steam: 走 Direct 或 UGC 下载路径
    Steam-->>Engine: 文件 / chunk / manifest
    Engine-->>Adapter: DownloadEvent
    Adapter-->>BG: WorkshopDownloadEvent
    BG-->>UI: 进度 / 完成 / 失败
```

## 7. 关键架构结论

1. `WorkshopService` 主要负责 App 侧业务编排，不承担底层下载实现。
2. `workshop-core` 负责下载编排与文件落地，是独立的下载核心库。
3. `steam-protocol` 负责更底层的 Steam 协议通信，不与 UI 耦合。
4. 下载路径必须支持“直链模式”和“SteamPipe/UGC 模式”两条链。
5. `WorkshopDownloadProcessService` 使下载生命周期独立于前台页面，适合长任务与断续恢复。
