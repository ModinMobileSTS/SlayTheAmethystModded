# 诊断日志归并方案

状态：提案（待评审）
范围：启动器诊断/日志文件的设备端目录、反馈归档（zip）结构、以及两者的对应关系。

## 1. 问题定义

对 issue-610 / issue-611 反馈包的实测结论：日志类文件散落在 7+ 个顶层目录，且存在五类结构性问题：

| # | 问题 | 例证 |
|---|------|------|
| P1 | 设备路径 ≠ 归档路径，同一文件两套名字 | `memory_diagnostics.log` 设备上在 `jvm_logs/`，归档成 `sts/memory_diagnostics/`；Steam Cloud 设备上在 `<storageRoot>/steam-cloud/`（sts 的兄弟目录），归档成 `sts/steam_cloud/phase1/` |
| P2 | 同一域多个目录、命名两套 | Workshop 浏览失败：设备 `workshop_browse_failure_logs/` ↔ 归档 `workshop/market_failed/`；自动导入：设备 `workshop_auto_import_patch_logs/`（扁平）↔ 归档 `workshop/auto_import_patch_logs/` |
| P3 | 命名风格混杂 | `steam-game-presence`（kebab）vs 其余全 snake；`download_tasks` vs `auto_import_patch_logs` |
| P4 | 扩展名混乱 | `.log`、`.log.txt`、`.txt`、`.jsonl` 并存 |
| P5 | 同名不同物 | `logs/latest_log_summary.txt`（key=value 机读）与 `feedback/latest_log_summary.txt`（人类摘要）内容、格式完全不同 |

根因：`DiagnosticsArchiveBuilder` 用几十处手写字符串拼接归档条目名，与 `RuntimePaths` 的设备路径各自演化，没有单一映射约束。

## 2. 现状地图（设备真实位置 → 归档条目）

| 内容 | 设备真实位置 | 归档条目 | 差异 |
|---|---|---|---|
| 游戏主日志 | `<sts>/latest.log`（游戏进程 log4j 写入） | `sts/logs/latest.log` | 有 |
| JVM 轮转日志 | `<sts>/jvm_logs/jvm_log_*.log` | `sts/logs/jvm_log_*.log` | 有 |
| 启动桥接事件 | `<sts>/boot_bridge_events.log` | `sts/logs/boot_bridge_events.log` | 有 |
| GC / 堆快照 / 信号转储 | `<sts>/jvm_gc.log`、`jvm_heap_snapshot.txt`、`last_signal_dump.txt` | `sts/logs/*` | 有 |
| 内存诊断 | `<sts>/jvm_logs/memory_diagnostics.log(.N)` | `sts/memory_diagnostics/` | 有 |
| 成就同步 | `<sts>/jvm_logs/achievement_sync.log(.N)` | `sts/achievement_sync/` | 有 |
| 窗口诊断 | `<sts>/window/` | `sts/window/` | 一致 |
| logcat | `<sts>/logcat/app\|system/` | 同 | 一致 |
| 启动器崩溃报告 | `<sts>/launcher_crash_reports/` | 同 | 一致 |
| WS 浏览失败 | `<sts>/workshop_browse_failure_logs/` | `sts/workshop/market_failed/` | 有 |
| WS 自动导入补丁 | `<sts>/workshop_auto_import_patch_logs/` | `sts/workshop/auto_import_patch_logs/` | 有 |
| WS 下载任务 | DB + `<filesDir>/workshop/<appId>/<id>/download.log` | `sts/workshop/download_tasks/*.log.txt`、`raw_download_logs/` | 有 |
| Steam Cloud | `<storageRoot>/steam-cloud/`（含 `failures/`、`login-history/`） | `sts/steam_cloud/phase1/*`、`sts/steam_login/` | 有（跨根+两次改名） |
| 在线状态上报 | `<filesDir>/steam-game-presence/`（内部存储） | `sts/steam-game-presence/` | 有（跨存储） |
| EasyTier | `<storageRoot>/easytier/` | `sts/easytier/` | 有（跨根） |
| 机读摘要 | 不落盘（打包时生成） | `sts/logs/latest_log_summary.txt` | 生成性 |
| 反馈摘要 | 不落盘（打包时生成） | `sts/feedback/latest_log_summary.txt` | 生成性，与上行同名不同物 |

关键代码位置：

- 设备路径唯一来源：`app/src/main/java/io/stamethyst/config/RuntimePaths.kt`
- 归档拼装：`app/src/main/java/io/stamethyst/backend/diag/DiagnosticsArchiveBuilder.kt`
- JVM 轮转：`app/src/main/java/io/stamethyst/backend/launch/JvmLogRotationManager.kt`
- 反馈摘要：`app/src/main/java/io/stamethyst/backend/feedback/FeedbackSubmissionService.kt`

## 3. 目标设计

### 3.1 三条原则

1. **直通原则（单一映射）**：除生成性目录外，zip 条目路径 == 设备路径相对 `stsRoot` 的相对路径。禁止归档层二次起名。
2. **单根原则**：所有运行时可写的诊断产物收敛到 `<sts>/` 下（消灭 `storageRoot` 兄弟目录与内部 `filesDir` 散落）。
3. **生成物隔离**：只有 `readme.txt`、`info/`、`feedback/`、`crash/` 和各域 `summary.txt` 属于打包时生成，其余必须是设备的真实文件。

### 3.2 目标目录树（设备 = 归档）

```
<sts>/
├── latest.log                        # 不动：游戏进程写入 + harness 契约
├── boot_bridge_events.log            # 不动：harness 契约
├── .harness_exit_request             # 不动：harness 契约
├── logs/                             # JVM 域（原 jvm_logs/ + 根部三个散文件并入）
│   ├── index.txt                     # 新：轮转槽位清单
│   ├── jvm_log_<ts>.log              # ← 原 jvm_logs/jvm_log_*.log
│   ├── jvm_gc.log                    # ← 原 <sts> 根
│   ├── jvm_heap_snapshot.txt         # ← 原 <sts> 根
│   ├── last_signal_dump.txt          # ← 原 <sts> 根
│   ├── startup_trace.log             # ← 原 jvm_logs/
│   └── summary.txt                   # 机读 key=value（原 latest_log_summary.txt 改名）
├── diag/                             # 横切诊断域
│   ├── memory/                       # ← 原 jvm_logs/memory_diagnostics.log(.N)
│   ├── achievements/                 # ← 原 jvm_logs/achievement_sync.log(.N)
│   ├── window/                       # ← 原 window/
│   ├── logcat/app|system/            # ← 原 logcat/（不变）
│   └── crashes/                      # ← 原 launcher_crash_reports/（文件名前缀不变）
├── workshop/                         # Workshop 域（设备端建真目录）
│   ├── downloads/                    # ← 原 download_tasks；扩展名 .log.txt → .log
│   ├── raw/<appId>/<publishedFileId>/download.log   # 原始下载日志直通
│   ├── browse_failures/              # ← 原 workshop_browse_failure_logs / market_failed
│   └── auto_import/                  # ← 原 workshop_auto_import_patch_logs
├── steam/                            # Steam 域
│   ├── cloud/                        # ← 原 <storageRoot>/steam-cloud（含 failures/、login_history/）
│   └── presence/                     # ← 原 <filesDir>/steam-game-presence
├── easytier/                         # ← 原 <storageRoot>/easytier
├── feedback/                         # 仅归档生成：issue_*、request.json、latest_log_digest.txt
├── crash/                            # 仅崩溃包生成：summary.txt
└── （info/device_info.txt、info/launcher_settings.txt 仅归档生成）
```

性能专项包（`sts-performance-logs-*.zip`）沿用同一原则：`sts/performance/` 下的条目同样改为源文件直通（`frame-probe-incidents.jsonl` 等迁入 `logs/` 后按新路径收录）。

### 3.3 命名规范

- 目录与文件一律小写 snake_case；kebab-case 只允许存在于既有外部契约（如 `sts-launcher-crash-*` 前缀，保留不动）。
- 扩展名白名单：滚动文本日志 `.log`；一次性报告 `.txt`；结构化事件 `.jsonl`；索引固定 `index.txt`。消灭 `.log.txt`。
- 多槽位目录必须提供 `index.txt`（沿用现有 index 构建函数）。
- 摘要二分：机读 `<domain>/summary.txt`（key=value）；人类可读摘要全包仅一份 `feedback/latest_log_digest.txt`。
- 轮转规则统一为 `<base>.<n>`（`RollingTextLogWriter` 现状，文档化即可）。

## 4. 实施计划

### Phase 0 — 审计冻结（0.5 天）

- 全仓扫描 `"sts/` 字面量（`rg '"sts/' app/src/main/java`），清单附于本文档，作为验收基线。
- 已确认 `cloud-function/src/submission.js` 不解析包内路径，无需改动。

### Phase 1 — 归档直通化（1–2 天，先行独立合入）

- `DiagnosticsArchiveBuilder` 删除全部手写条目名，改为 `file.relativeTo(stsRoot)` 直通；仅例外表（`readme.txt`、`info/`、`feedback/`、`crash/`、各 `summary.txt`）允许显式命名。
- `buildArchiveReadme()` 改为从布局常量生成，杜绝说明与结构漂移。
- 新增单元测试：构造临时 sts 树 → 打包 → 断言「除例外表外每个 entry 在设备上存在同相对路径文件」；并断言源码无未登记的 `"sts/` 字面量。
- 本阶段不改任何写入位置；旧布局文件按旧路径原样收录，立即获得「包内可见即设备可见」的性质。

### Phase 2 — 设备侧归并 + 启动迁移器（2–3 天）

- `RuntimePaths` 切换到 3.2 目标常量，更新 `ensureBaseDirs()`。
- 新增 `DiagnosticsLayoutMigrator`：启动时按 `componentRoot` 内版本标记执行一次；逐项 try-move、失败跳过不阻塞启动、整体幂等：
  - `jvm_logs/*` → `logs/`、`diag/memory/`、`diag/achievements/`
  - 根部 `jvm_gc.log`、`jvm_heap_snapshot.txt`、`last_signal_dump.txt` → `logs/`
  - `workshop_browse_failure_logs/` → `workshop/browse_failures/`；`workshop_auto_import_patch_logs/` → `workshop/auto_import/`
  - `<storageRoot>/steam-cloud/` → `steam/cloud/`；`<storageRoot>/easytier/` → `easytier/`；`<filesDir>/steam-game-presence/` → `steam/presence/`
  - `window/` → `diag/window/`；`logcat/` → `diag/logcat/`；`launcher_crash_reports/` → `diag/crashes/`
- 各 Store/Logger（均已通过 `RuntimePaths` 或自有 `list*` 访问器取路径，是现成的抽象层）切换新路径；一个版本周期内读取顺序为新路径优先、legacy 回退。
- `JvmLaunchController` 的 GC 日志参数与 signal dump 写入路径同步切换。
- `JvmLogRotationManager` 指向 `logs/`；新增 `logs/index.txt` 输出。

### Phase 3 — 摘要与命名收尾（1 天）

- 机读摘要落位 `logs/summary.txt`；反馈人类摘要改名 `feedback/latest_log_digest.txt`。
- Workshop 下载任务日志扩展名统一 `.log`。
- 补齐所有多槽位目录的 `index.txt`。

### Phase 4 — 消费者同步（1 天）

- `scripts/tools/lib/sts_harness.py`、`scripts/tools/harness/_device.py`、`_status.py`：`latest.log`、`boot_bridge_events.log`、`.harness_exit_request` 因留根而零改动；`steam-cloud/*` 轮询路径改为 `sts/steam/cloud/*`。
- `.opencode/skills/sts-feedback-diagnosis/`：`SKILL.md`、`references/bundle-and-triage.md`、`scripts/inspect_feedback_report.py` 的路径表全面更新（其当前引用的 `sts/jvm_logs/latest_log_summary.txt` 等早已过期）。
- 文档：`docs/architecture-overview.md`、`docs/backend-startup-chain.md`、`docs/debug-automation/README.md`、`docs/gpu-performance-diagnostics.md`、`docs/architecture/feedback-diagnostics-architecture.md`。
- 测试同步：`RuntimePaths*FilesTest`（4 个）、`DiagnosticsArchiveBuilderAutoImportPatchLogsTest`、`PerformanceDiagnosticsArchiveTest`、`test_sts_harness.py` 等。

## 5. 兼容契约（明确不动）

| 契约 | 原因 |
|---|---|
| `<sts>/latest.log`、`<sts>/boot_bridge_events.log`、`<sts>.harness_exit_request` | 游戏进程写入 + `scripts/tools/harness` 固定读取 |
| `sts-launcher-crash-*` 文件名前缀 | `RuntimePaths.isLauncherCrashReportFileName` 过滤依赖，UI 分享可见 |
| `jvm_log_<yyyyMMdd-HHmmss-SSS>.log` 时间戳格式 | 诊断 skill 与脚本的正则依赖 |
| `<sts>/saves/`、`enabled_mods.txt`、`mods/` 等游戏可见数据 | 非诊断范畴 |

## 6. 验收标准

1. 任一新导出 zip 中，除例外表外的每个 entry 都能在设备 `stsRoot` 下找到同相对路径来源文件（由 Phase 1 测试固化）。
2. `rg '"sts/' app/src/main/java --glob '!**/test/**'` 仅命中例外表登记项。
3. 旧版本覆盖安装后首启迁移完成、幂等重跑无副作用、迁移失败不影响启动。
4. debug-automation 的 smoke / steam-cloud-sync 流程在新布局下通过。
5. `sts-feedback-diagnosis` skill 在新包上按更新后的路径表可完成一次完整分诊。
6. 全包不存在同名不同物的摘要文件；`readme.txt` 与实际结构一致（由代码生成保证）。

## 7. 风险与对策

| 风险 | 对策 |
|---|---|
| 迁移中途进程被杀导致半迁移状态 | migrator 逐文件 try-move + 幂等设计，下次启动续迁；move 失败仅记录不抛出 |
| 社区教程/用户引用旧路径 | 发版说明列出新旧对照表；一个版本周期内 store 层 legacy 回退读 |
| harness 轮询 steam-cloud 旧位置 | Phase 4 与脚本同 PR 切换；脚本读取失败视为环境未迁移，走既有 fallback |
| 性能包与主包布局不一致延续 | Phase 1 直通化对两个 bundle 同时生效 |
