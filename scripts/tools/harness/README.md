# Harness Module

端到端测试编排器。设备 I/O 全部经 **connector**；主机侧用 Gradle / CFR。

## 前置条件

```bash
export STS_CONNECTOR_PORT=39999
python -m scripts.tools.connector start --port 39999
# 或 harness 参数: -ConnectorPort 39999
```

Harness **不会**自动拉起 daemon（与 arthas 客户端默认 auto_start 不同）。

## 架构

```
Harness.run()
  ├── connect_connector() + select_device()  → ConnectorClient
  └── run_command(ctx, out_dir)  ──→  命令分发
        ├── doctor               → harness/doctor.run_doctor(ctx)
        ├── install              → harness/install.run_install(ctx)
        ├── start / stop         → harness/run.run_start/stop(ctx)
        ├── logs                 → harness/logs.run_logs(ctx, out_dir)
        ├── screenshot           → harness/screenshot.run_screenshot(ctx, out_dir)
        ├── status               → harness/status.run_status(ctx)
        ├── mods / set-mods      → harness/mods.run_mods/run_set_mods(ctx)
        ├── smoke               → harness/smoke.run_smoke(ctx, out_dir)
        ├── single-room         → harness/single_room_run.run_single_room(ctx, out_dir)
        ├── exit                → harness/exit.run_exit(ctx, out_dir)
        ├── decompil             → harness/decompil.run_decompil(ctx, out_dir)
        ├── agent-attach/detach/list/status → harness/agent.*(ctx, out_dir)
        ├── play                 → harness/play.run_play(ctx, out_dir)
        ├── console              → harness/console.run_console(ctx, out_dir)
        ├── hotreload            → harness/hotreload.run_hotreload(ctx, out_dir)
        ├── perf                 → harness/perf.run_perf(ctx, out_dir)
        ├── startup-cache-profile → harness/startup_cache.run_startup_cache_profile(ctx, out_dir)
        └── steam-cloud-sync     → sts_harness.harness_steam_cloud_sync (仍用 connector adb/logcat)
```

共享模块：
- `_context.py` — `HarnessContext`（含 `connector`）
- `_runner.py` — `run_native`（主机命令）, `adb`/`adb_shell_script`（经 connector）, `gradle`
- `_device.py` — storage root / logcat_dump / logcat_start|stop（经 connector）
- `_status.py` — 状态观测

每个命令函数签名为 `(ctx: HarnessContext, ...) -> None` 或返回 `int`。

## game-probe 连接

`agent-*`、`play`、`console`、`hotreload` 和 `perf` 通过 `AgentClient(connector=…)` + `connect_stream` 连接 game-probe（默认端口 `9099`）。可用 `-AgentPort` 覆盖。

`console` 需要以启用 game-probe 的方式启动游戏，并要求 BaseMod DevConsole 可用。不传命令时进入交互模式；可使用 `-ConsoleCommand "gold 999"` 执行单条命令。

## 文件结构

| 文件 | 职责 |
|------|------|
| `_context.py` | HarnessContext dataclass + set_result_success |
| `_runner.py` | run_native, CommandResult, adb, adb_shell_script, gradle, build_adb_args |
| `_device.py` | resolve_device_sts_root, read_remote_sts_text, remote_sts_root_script, remote_sts_path_state, parse_remote_path_state_output, clear_runtime_signals, harness_logcat_dump, start/stop_logcat_capture |
| `_status.py` | harness_status, parse_boot_bridge_events, find_crash_marker, find_single_room_result, find_harness_logcat_crash, last_non_blank_line, extract_startup_cache_log_evidence, process_pid_text, package_version_info, desktop_jar_patch_snapshot, wait_harness_status, update_status_harness_logcat |
| `orchestrator.py` | HarnessOrchestrator 独立编排器 |
| `doctor.py` | doctor 命令 |
| `install.py` | install 命令 |
| `run.py` | start + stop 命令 |
| `logs.py` | logs 命令 |
| `screenshot.py` | screenshot 命令 |
| `status.py` | status 命令 |
| `mods.py` | mods + set-mods 命令 |
| `decompil.py` | decompil 命令 |
| `agent.py` | agent-attach/detach/list/status 命令 |
| `play.py` | play 命令 |
| `console.py` | console 命令（BaseMod DevConsole 交互式/单发控制） |
| `hotreload.py` | hotreload 命令 |
| `perf.py` | perf 命令 |
| `smoke.py` | smoke 命令 |
| `single_room_run.py` | single-room 启动并保持运行 |
| `exit.py` | 通用 GDX 优雅退出请求，超时后 force-stop |
| `single_room.py` | single-room spec 构建 + 设备推送 |
| `startup_cache.py` | startup-cache-profile 命令 |

## 输出

- 未指定 `-OutDir`：`debug-artifacts/harness/<command>-<timestamp>/result.json`
- 指定 `-OutDir <path>`：`<path>/<timestamp>/result.json`（不在 base 下直接写，也不清空 base）

<a id="stsHarnessPerfBench"></a>

## `stsHarnessPerfBench`

执行完整 fresh autoplay 地牢基准，自动开启深度性能诊断，拉取超预算帧 incidents，生成指标并与 baseline 比较：

```bash
./gradlew :app:stsHarnessPerfBench \
  -PdeviceSerial=10.126.126.1:5555 \
  -PharnessOutDir=agent-tmp/perf-bench \
  -PperfBenchTimeoutSeconds=720
```

运行前必须启动 connector；端口通过 `STS_CONNECTOR_PORT` 提供：

```bash
export STS_CONNECTOR_PORT=39999
python3 -m scripts.tools.connector start --port 39999
```

### 生效参数

| Gradle property | 类型 / 默认值 | 含义 |
|---|---|---|
| `-PpythonExecutable=<path>` | 字符串；`python` | 启动 harness 的 Python 可执行文件。系统没有 `python` 命令时使用 `python3`。 |
| `-PdeviceSerial=<serial>` | 字符串；空 | 目标设备序列号。多设备连接时必须指定；空值由 connector 选择唯一可用设备。 |
| `-PharnessOutDir=<path>` | 路径；空 | 输出基目录。指定后写入 `<path>/<timestamp>/`；空值写入 `debug-artifacts/harness/perf-bench-<timestamp>/`。不会清空基目录。 |
| `-PperfBenchTimeoutSeconds=<seconds>` | 正整数；`720` | 整轮游戏等待超时。优先级高于 `harnessTimeoutSeconds`。超时后仍会收集现有 incidents 并停止游戏。 |
| `-PharnessTimeoutSeconds=<seconds>` | 正整数；`720`（仅本任务） | 未提供 `perfBenchTimeoutSeconds` 时的超时回退值。其他 harness task 的默认值不同。 |
| `-PharnessPollIntervalSeconds=<seconds>` | 正整数；`2` | 游戏进程和 READY/FAIL 状态轮询间隔。 |
| `-PperfBenchBaseline=<path>` | JSON 路径；`scripts/tools/harness/perf_bench_baseline.json` | 指定比较 baseline。相对路径以仓库根目录为基准；文件不存在时使用代码内置阈值。 |
| `-PperfBenchUpdateBaseline=true` | 布尔；`false` | 用本轮指标覆盖 baseline。只写入 `total/render p95/p99`、flush spike、GC stall 数量和最大值。 |
| `-PdisableCardObtainEffectOwnershipCompat=true` | 布尔；`false` | 禁用 `ShowCardAndAddToHandEffect` ownership 兼容补丁，用于复现或比较该补丁。 |

布尔参数只有值严格为 `true` 时启用；省略、`false` 或其他值均视为关闭。

路径参数必须使用 ASCII 连字符 `-`。例如应写 `agent-tmp/perf-bench`，不要写成包含 Unicode 短横线的 `agent–tmp/perf-bench`；后者会创建另一个难以识别的目录。

设备必须在 `adb devices -l` 中显示为 `device`。`offline`、`unauthorized` 或列表中不存在都会在启动前终止基准；TCP ADB 可先执行：

```bash
adb disconnect <serial>
adb connect <serial>
adb -s <serial> get-state
```

超时优先级：

```text
perfBenchTimeoutSeconds > harnessTimeoutSeconds > 720
```

### 任务固定行为

以下行为由 perf-bench 强制设置，传入同名通用 property 不会改变本任务：

| 固定项 | 有效值 | 被忽略的 property |
|---|---|---|
| 启动模式 | `mts` | `launchMode` |
| autoplay | 开启 | `autoplay` |
| autoplay 场景 | `normal` 完整地牢 | `autoplayMode`、`autoplaySingleRoomSpec`、`singleRoomSpecFile`、`singleRoomMonster`、`singleRoomCards` |
| 存档模式 | `fresh` | `autoplaySaveMode` |
| 深度性能诊断 | 开启 | `performanceDeepDiagnostics` |
| 安装 APK | 每轮安装 | `harnessSkipInstall` 当前只对 `stsHarnessSmoke` 生效 |
| JVM/runtime 强制崩溃 | 关闭 | `forceJvmCrash`、`forceRuntimeCrash` 被 `stsStartAutoplay` 固定关闭 |
| 结束处理 | 总是停止游戏 | `noStopAfterSmoke` 只用于 smoke |

### 兼容保留参数

以下 property 当前会被 Gradle 接受并转发，但 perf-bench 实现没有消费，不应依赖：

| Gradle property | 当前状态 |
|---|---|
| `-PperfBenchEnableProfiler=true` | 可选，默认 `false`。游戏达到 READY 后启动 Arthas tracer，采集 `SpriteBatch.flush` 调用栈和慢 `AbstractCard.render`；会执行 class retransform，不应作为默认性能门禁。 |
| `-PperfBenchProfilerSeconds=<seconds>` | 无效果。自动 tracer 时长按总 timeout 的 60% 计算，并限制在 `30–180` 秒。 |
| `-PperfBenchCharacter=<id>` | 只写入 harness 内部 character 字段，不会改变 normal autoplay 的角色选择。 |
| `-PsingleRoomCharacter=<id>` | 同上；且优先于 `perfBenchCharacter`，但不会改变 normal autoplay。 |

### Baseline 与退出状态

- 只分析 `frame-probe-incidents.jsonl` 中超过帧预算的帧；这些分位数不是全量帧分位数。
- 每项 threshold 允许 `15%` 容差，超过后写入 `regressions`。
- 性能回归属于信息结果：task 仍返回退出码 `0`，应读取 `result.json` 的状态和 `perf-result.json`，不能只看 Gradle 成功与否。
- `-PperfBenchUpdateBaseline=true` 会写入 baseline，即使本轮相对旧 baseline 存在回归；更新前应人工检查场景和数据完整性。

### 输出文件

每轮时间戳目录通常包含：

| 文件 / 目录 | 内容 |
|---|---|
| `result.json` | Harness 总结果、状态、artifact 路径和错误信息。 |
| `perf-result.json` | 本轮 metrics、thresholds 和 regressions。 |
| `frame-probe-incidents.jsonl` | 从设备拉取的超预算帧数据。 |
| `harness-logcat-<timestamp>.txt` | 本轮 logcat 捕获。 |
| `arthas-trace/` | 自动 tracer 输出；启动失败时 `result.json` 会记录 `tracerError`。 |

关键指标语义和配对测试限制见 [`docs/gpu-performance-diagnostics.md`](../../../docs/gpu-performance-diagnostics.md)。
