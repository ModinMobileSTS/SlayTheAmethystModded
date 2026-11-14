# Connector

设备通信守护进程，所有工具模块与 Android 设备之间的统一连接层。

## 职责

- 常驻后台，管理设备连接与 Arthas bridge 健康生命周期
- 设备发现与选择（adb devices）
- **TCP 透传代理** — 模块不直接建 TCP 连接，通过 connector 的 TCP channel 收发
- **Arthas 自愈** — 按设备探活 `:8099`；bridge 失效但 game-probe `:9099` 存活时自动重新加载 bridge，不重启游戏
- adb 命令代理（shell, push, pull）
- 供其他模块通过 TCP API 访问设备

## 跨平台

全部使用 Python `socket` 标准库，TCP 通信，无文件依赖，支持 Linux / macOS / Windows。

## 依赖关系

```
harness / arthas / autoplay / monitor  (临时进程)
        │
        ▼
   ┌─────────────┐
   │  connector  │  ← TCP 127.0.0.1:<port>
   │  (daemon)   │     常驻进程
   └──────┬──────┘
          │ adb
          ▼
    Android Device
    ├── game-probe (:9099)     ← 需 -PdebugMode=true 或 -Pautoplay=true 才启动
    └── arthas-bridge    (:8099)  ← 通过 game-probe 的 LOAD_AGENT 加载
```

game-probe 的启动条件为 `launchMode=mts` 且至少满足其一：`debugMode`, `autoplay`, `forceJvmCrash`, `forceRuntimeCrash`, `performanceDeepDiagnostics`。

- 各模块**不直接调用 adb**，所有设备操作通过 connector
- 各模块**不直接开 TCP 连接**，通过 `connect_stream` 统一走 connector channel
- Connector 启动时自动选择设备，运行中可切换

## 管理命令

通过 `__main__.py` 提供的生命周期管理命令控制 daemon：

```bash
# 启动后台 daemon（需指定端口）
python -m scripts.tools.connector start --port 39999

# 或通过环境变量设置默认端口
export STS_CONNECTOR_PORT=39999
python -m scripts.tools.connector start

# 查看运行状态
python -m scripts.tools.connector status

# 重启
python -m scripts.tools.connector restart --port 39999

# 停止
python -m scripts.tools.connector stop

# 前台运行（调试用）
python -m scripts.tools.connector daemon --port 39999
```

`start` 命令通过 TCP ping 检查端口是否已占用，已运行则拒绝重复启动。
启动器仅从 daemon 的标准输出读取 ready JSON；完成握手后 daemon 的标准错误会与调用命令的输出流断开。因此即使 IDE、CI 或其他运行器合并并捕获 stdout/stderr，`start` 和 `restart` 也会在 daemon 就绪后正常退出。

## 环境变量

| 变量 | 说明 | 默认值 |
|------|------|--------|
| `STS_CONNECTOR_PORT` | Connector daemon 的 TCP 端口 | 必填 |
| `STS_TEST_DEVICE` | 集成测试的默认设备序列号 | `auto` |

`scripts/tools/lib/env_device.py` 的 `get_test_device_serial()` 读取 `STS_TEST_DEVICE`，所有集成测试文件和 `HarnessOrchestrator` 均通过该函数取值。

## 协议格式

TCP (127.0.0.1)，纯文本 JSON 行协议，请求/响应配对，支持多个并发客户端连接。

### 请求

```json
{"id":"req-1","method":"forward","params":{"port":9099}}
```

### 响应

```json
{"id":"req-1","result":{"ok":true,"port":9099}}
```

### 错误

```json
{"id":"req-1","error":{"code":-32000,"message":"device offline"}}
```

## 方法详述

### devices

```json
{"method":"devices"}
→ {"devices":[{"serial":"192.0.2.1:45678","state":"device","model":"Pixel_8"}]}
```

### select

```json
{"method":"select","params":{"serial":"192.0.2.1:45678","timeout_ms":10000}}
→ {"ok":true}
```
`serial` 可用 `"auto"` 自动选择第一个设备。

### status

```json
{"method":"status"}
→ {"serial":"192.0.2.1:45678","state":"online","model":"Pixel_8"}
```

### forward / unforward

Legacy helpers for adb port forward. Prefer `connect_stream` for game-probe / Arthas.

### connect_stream

建立到设备端 TCP 服务的双向透传通道。响应返回后当前连接进入透传模式。

```json
{"method":"connect_stream","params":{"port":9099}}
→ {"stream_id":"s1"}
```

```python
conn = ConnectorClient()
conn.connect()
conn.select(get_test_device_serial())
stream = conn.connect_stream(port=9099)
stream.write(b"LIST\n")
print(stream.readline())
stream.close()
```

### Arthas health RPC

`arthas_connect_stream` 是 Arthas 客户端的标准入口。daemon 将设备选择状态保持在每个客户端连接内，并按 serial 维护独立的 Arthas runtime 状态。它先确认 bridge prompt 可用；不可用时经 game-probe 重新加载 Arthas，然后才进入透传模式。

游戏进程或 game-probe 不可用时，daemon 返回 `-32010` 及诊断原因，**不会启动或重启游戏**。`arthas_ensure` 仅执行上述健康检查/修复，`arthas_status` 返回最近状态，`arthas_reset` 与 `arthas_shutdown` 分别执行轻量 reset 和完整销毁。

### shell / push / pull / install / adb

标准 adb 操作。`adb` 是通用 argv 接口（含 `exec-out`、任意子命令）。

```json
{"method":"shell","params":{"command":"ps | grep java","timeout_ms":10000}}
→ {"exit":0,"stdout":"...","stderr":""}

{"method":"push","params":{"local":"/tmp/x.jar","remote":"/sdcard/x.jar","timeout_ms":30000}}
→ {"ok":true}

{"method":"pull","params":{"remote":"/sdcard/log.txt","local":"/tmp/log.txt","timeout_ms":30000}}
→ {"ok":true}

{"method":"install","params":{"local":"/path/app.apk","replace":true,"timeout_ms":180000}}
→ {"exit":0,"stdout":"Success\n","stderr":""}

{"method":"adb","params":{"args":["exec-out","run-as","io.stamethyst","sh","-c","ls files/sts"],"timeout_ms":10000,"capture":"text"}}
→ {"exit":0,"stdout":"...","stderr":""}
```

`capture` for `adb`: `text` (default), `binary` (returns `stdout_b64` or writes `local_path`), `none`.

### logcat_dump / logcat_start / logcat_stop / logcat_status

```json
{"method":"logcat_dump","params":{"since":"07-19 01:00:00.000","local_path":"/tmp/lc.txt","timeout_ms":15000}}
→ {"ok":true,"exit":0,"local_path":"/tmp/lc.txt","bytes":1234}

{"method":"logcat_start","params":{"since":"","local_path":"/tmp/stream.txt"}}
→ {"ok":true,"capture_id":"lc1-…","local_path":"/tmp/stream.txt","stderr_path":"…"}

{"method":"logcat_stop","params":{"capture_id":"lc1-…"}}
→ {"ok":true,"capture_id":"lc1-…","local_path":"…","exit":-9,"stopped_by_daemon":true,"duration_ms":1000}

{"method":"logcat_status","params":{}}
→ {"ok":true,"captures":[{"capture_id":"…","running":true,"local_path":"…"}]}
```

Logcat files are written on the machine running the daemon (same host as harness).

### ping / quit

```json
{"method":"ping"} → {"pong":true}
{"method":"quit"} → {"ok":true}
```

## 错误码

| code | 含义 |
|------|------|
| -32000 | 通用错误 |
| -32001 | 设备离线或未选择 |
| -32002 | 端口转发失败 |
| -32003 | 命令执行超时 |
| -32004 | 参数校验失败 |

## 客户端库用法

```python
from scripts.tools.connector.client import ConnectorClient
from scripts.tools.lib.env_device import get_test_device_serial

c = ConnectorClient()  # 从 STS_CONNECTOR_PORT 环境变量读取端口
c.connect()
c.select(serial=get_test_device_serial())
result = c.shell("ps | grep java")

stream = c.connect_stream(port=9099)
stream.write(b"OBSERVE\n")
print(stream.readline())
stream.close()
```

## 文件结构

| 文件 | 职责 |
|------|------|
| `daemon.py` | TCP server，请求分发，stream proxy |
| `client.py` | 客户端库，含 Stream 封装 |
| `__main__.py` | CLI 入口，提供 start/stop/restart/status 等管理命令 |
# Workspace-local wireless ADB (Linux)

Recommended setup: copy the root `.env.local.example` to `.env.local`, set an absolute
SDK `adb` executable, a non-default local server port, and the device's wireless
debugging `host:port`. Never commit actual device endpoints or pairing credentials.
The server port is NOT the device connection port or its pairing port.

```sh
python3 -m scripts.tools.local_adb ensure
python3 -m scripts.tools.local_adb status
python3 -m scripts.tools.local_adb adb -- shell getprop ro.product.model
python3 -m scripts.tools.local_adb run -- ./gradlew :app:stsPullLogs
python3 -m scripts.tools.local_adb stop
```

OpenCode `sts` tools automatically ensure this connection; `sts.local_adb` exposes
`ensure`, `status`, and `stop`. These lifecycle actions need no device or connector
port argument. Harness status/raw queries and installation require an explicit
device serial matching the configured endpoint; configuration is not authorization.
The local manager allocates a connector port and replaces stale inherited
`STS_CONNECTOR_PORT`/`STS_TEST_DEVICE` settings in its child processes. The old
fixed-port harness auto-start is removed. Explicit legacy connectors remain
available only when no managed local configuration exists.

The manager uses official adb with an independent loopback listener, explicit
server selection on every command, process identity checks, and a cross-process
lifecycle lock. It never falls back to port 5037 or shuts down an unknown server.
State and logs live in ignored `agent-tmp/local-adb/`. Stop before changing the
server port or adb binary. Device endpoint changes may be applied on the next call.
No USB fallback or automatic pairing is performed. If the device requires pairing,
pair explicitly using the configured binary and project server (`-H 127.0.0.1 -P
<local-server-port> pair <pairing-endpoint>`); enter the code interactively, never
save it in tracked files. This lifecycle implementation requires Linux (`/proc`
ownership checks and `flock`). ADB authentication keys retain the SDK's standard
user-level behavior; this isolates the server, not the authentication identity.

Connection errors identify the failed stage: `connect timed out after 15s` means
the configured wireless connection did not complete; `device-state check timed
out after 10s` means ADB could not confirm the device state. Check the phone's
network/VPN, debugging port and authorization, then retry. Details remain in
`agent-tmp/local-adb/adb.log`. A successful `status` reports the local server and
connector processes, not phone reachability; `ensure` verifies the connection.

Gradle's custom `stsStart`, `stsStartAutoplay`, `stsStop`, and `stsPullLogs` tasks
use the local manager when `.env.local` exists. They forward `-PdeviceSerial` as
`--expected-serial`: a mismatch fails before `ensure` or any device action, while
omitting the property retains the configured non-install target. Log export checks
this before its staging/file probes. CLI callers can use the same guard:

```bash
python3 -m scripts.tools.local_adb --expected-serial USER_APPROVED_SERIAL adb -- shell pidof io.stamethyst
```

The flag precedes the action and only validates the target; it grants no installation
authorization. For host Gradle tasks needing the server environment use `local_adb run`.
Automatic Gradle installation/uninstallation tasks are blocked; explicit installs use
the harness install action with separate authorization and an exact device serial.

`scripts/perf-loop.sh` lets the harness ensure the managed connector and use its dynamic
port. Omit `--connector-port` in this mode; inherited legacy port values are ignored.
Without managed configuration, supply a legacy port via the flag or `STS_CONNECTOR_PORT`;
connector startup errors terminate the script. The performance flow never installs APKs.

Legacy connector commands below are for explicit, externally managed setups only.
