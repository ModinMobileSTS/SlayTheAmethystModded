# Arthas Module

[Arthas](https://arthas.aliyun.com)（阿尔萨斯）是阿里巴巴开源的 JVM 诊断工具。
本模块将其集成到 SlayTheAmethyst 的 Android 运行时：自定义 `arthas-bridge` 绕过 Netty，
在设备 JVM 的 `localhost:8099` 暴露纯 socket 接口；Python 客户端经 `connector` daemon
的 `connect_stream` 透传收发命令。

## 职责

- 生命周期：connector daemon 按设备探活 → 推送 JAR / 原生库 / 伴生文件 → `LOAD_AGENT` → 透传连接
- 交互：`shell` / `query`（`ArthasShell` 解析 prompt）
- Android 适配：无 Netty bridge、MTS ClassLoader、线程 CPU `/proc` fallback、async-profiler、JFR `.jfc`

## 生命周期语义

Bridge 后端设计为**长驻**：`ServerSocket` + accept 循环独立于 Arthas
`ArthasBootstrap`。因此 `stop` 与 `shutdown` 是两种不同强度的清理：

| 操作 | 发送的 Arthas 命令 | 后端 / 端口 | 之后能否直接 `query` |
|------|-------------------|------------|--------------------|
| `stop` | `reset` | 保持存活，`:8099` 继续监听 | 能，无需重新 `start` |
| `shutdown` | `reset` + `stop` | `ShellServer` 销毁，监听器关闭，端口释放 | 不能，需重新 `start` |

`stop` 只撤销字节码增强与 listener（`reset`），是日常调试收尾的默认选择；它不会
让 JVM 进入端口被占但无法服务的半死状态。

`shutdown` 走完整销毁：`stop` 会调用 `ArthasBootstrap.destroy()`，销毁
`ShellServer` 与 `SpyAPI`。bridge 的 accept 循环在下一次连接时发现 bootstrap 已
销毁，随即关闭自己的 `ServerSocket`；`ArthasManager.shutdown()` 轮询端口直到不再
接受连接（默认最多 10s）后返回。

### 幂等 attach

`agentmain` 可在同一 JVM 上重复调用。`ArthasCommandBridge` 把 `ServerSocket` 存为
静态字段：

- 若已有存活监听器且端口一致 → 跳过重建，只刷新 bootstrap 与 command resolver 后返回，
  **不会抛 `BindException`**。
- accept 循环不持有 `shellServer` 引用，每次 accept 时动态取
  `ArthasBootstrap.getInstance().getShellServer()`，因此重复 attach 后旧循环会自动
  使用新 bootstrap，无需重启循环。
- `new ServerSocket(port)` 抛 `BindException` 时区分「自己的旧监听器」（复用）与
  「外部进程占用」（才算失败）。

因此 `start` → `stop` → `start` 序列在同一 JVM 内是安全的，无需 force-stop 应用。

### Connector daemon 自愈

Arthas CLI 和 Python 客户端不维护设备端 bridge 状态。它们通过 connector 的
`arthas_connect_stream` 请求会话；daemon 按设备 serial 维护独立健康状态并先探活
`:8099`。bridge 可用时直接建立透传；bridge 失效而 game-probe `:9099` 可用时，daemon
串行执行资源部署与幂等 attach 后重试。

若游戏进程已退出、未以可用 debug 模式启动，或 game-probe 不可达，daemon 返回明确的
健康错误，**不会自动启动或重启游戏**。因此开发机侧客户端保持无状态，重连后的流仍会经过
同一设备的健康恢复路径。

### 会话回收

`SocketTerm.lastAccessedTime()` 恒返回当前时间，`ShellServerImpl` 的 reaper 永远不会
evict bridge 会话。`BridgeSession` 因此在读循环结束后显式调用
`shell.close("session closed")`，否则每次 `query` 都会在 `ShellServerImpl.sessions`
里泄漏一个 `ShellImpl`。

## 依赖与架构

```
Python CLI
    │
    ▼
ConnectorClient ──TCP──→ connector daemon
    │                     │
    │ connect_stream(8099) │ adb forward tcp:8099 → device:8099
    ▼                     ▼
ArthasShell            Device JVM
                           ├── game-probe :9099  ← LOAD_AGENT
                           └── arthas-bridge :8099 ← ServerSocket
                                ├── ArthasBootstrapCompat（无 Netty）
                                ├── SocketTerm
                                └── BridgeSession（每连接一线程）
```

两阶段加载（与 `manager.py` 一致）：

```
① LOAD_AGENT arthas-core.jar
    → 追加 system classpath（无 Agent-Class）
② load_agent(arthas-bridge.jar, "{core};port=8099")
    → 隔离 ClassLoader 反射 agentmain，启动 ServerSocket
```

`arthas-spy.jar` 必须和 `arthas-core.jar` 放在同一目录。Arthas Bootstrap
会在初始化时从该目录找到它，并追加到 bootstrap classpath；它不是独立的
agent，不能通过 `load_agent()` 调用。资源包必须包含
`java/arthas/SpyAPI.class` 和 `SpyAPI$AbstractSpy.class`。

`ArthasCommandBridge.start()` 概要：

1. `ArthasBootstrapCompat.createWithoutNetty()` 构造 Bootstrap
2. 注册 `BuiltinCommandPack`（禁用列表为空）+ 自定义 `MetaspaceCommand`
3. 注册 ByteKit 与 Enhancer transformer，修复 MTS ClassLoader 下的 ASM 类型解析、class resource 读取和重复类重转换
4. `ServerSocket(:8099)`，每次 `accept` 创建 `BridgeSession`；默认禁用会在部分 Android 内核触发 SIGSEGV 的 async-profiler 原生初始化

MTS 可能同时保留多个同名类副本。bridge 因此关闭 Arthas batch retransform，并在 JVM 仅拒绝其中一个重复副本时保留其他副本的成功增强。`nativeDiagnostics` 默认是 `false`；只有显式传入 `nativeDiagnostics=true` 才会尝试 procfs 和 async-profiler 原生诊断。

深度性能诊断还支持设备端自动执行 core/bridge 两阶段加载。自动模式仅绑定 loopback、保持 `nativeDiagnostics=false`，并将有界 stack/trace 结果写到 `sts/performance/arthas/`；电脑端 `arthas_ensure` 会优先复用该实例。

## 快速开始

### 前置条件

1. **游戏以 debug 相关模式启动**（game-probe `:9099` 作为 `-javaagent`）：

   ```bash
   # Gradle（推荐）
   ./gradlew :app:stsStart -PlaunchMode=mts -PdebugMode=true

   # harness
   python scripts/tools/main.py sts-harness -Command start -LaunchMode mts -DebugMode

   # am start
   adb shell am start -n io.stamethyst/.LauncherActivity \
     --es io.stamethyst.debug_launch_mode mts \
     --ez io.stamethyst.debug_mode true
   ```

   game-probe 启动条件：`launchMode=mts`，且满足其一：`debugMode`、`autoplay`、
   `forceJvmCrash`、`forceRuntimeCrash`、`performanceDeepDiagnostics`。

2. **connector 端口**：

   ```bash
   export STS_CONNECTOR_PORT=39999
   # 可选：手动启 daemon
   python -m scripts.tools.connector start --port 39999
   ```

3. **设备文件**由 `manager.start()` 自动推送（也可手动准备）：

   ```
   /data/data/io.stamethyst/files/arthas/
     arthas-core.jar
     arthas-spy.jar
     arthas-bridge.jar
     libprocfs_cpu.so
     libasyncProfiler-linux-arm64.so

   /data/data/io.stamethyst/files/runtimes/Internal/lib/jfr/
     default.jfc
     profile.jfc

   /data/data/io.stamethyst/files/runtimes/Internal/lib/aarch64/server/
     libjvm.debuginfo          # alloc 符号（按需）
   ```

### 设备选择

多设备时**必须**显式指定 serial：

```bash
export STS_CONNECTOR_PORT=39999
export STS_TEST_DEVICE=localhost:15555   # 可选默认

python3 -m scripts.tools.arthas --device localhost:15555 start
python3 -m scripts.tools.arthas --device localhost:15555 query "version"
python3 -m scripts.tools.arthas --device localhost:15555 stop      # 轻量清理，后端保持
python3 -m scripts.tools.arthas --device localhost:15555 shutdown  # 完整销毁，释放端口
```

解析顺序：`--device` → `STS_TEST_DEVICE`（非空且非 `auto`）→ 仅 1 台在线时自动选择 → 否则报错并列 serial。

### CLI

```bash
python3 -m scripts.tools.arthas --device <serial> start   # 推送 + LOAD_AGENT + forward
python3 -m scripts.tools.arthas --device <serial> shell   # 交互 shell
python3 -m scripts.tools.arthas --device <serial> query "thread -n 5"
python3 -m scripts.tools.arthas --device <serial> query --duration 20 "monitor com.example.Foo bar"
python3 -m scripts.tools.arthas --device <serial> stop      # reset + unforward（后端长驻）
python3 -m scripts.tools.arthas --device <serial> shutdown  # reset + stop + 等端口释放
```

可选：`--agent-port`（默认 9099）、`--arthas-port`（默认 8099）。
`monitor`、`watch`、`trace` 是持续输出命令；`query` 会持续收集到 `--duration` 到期，
默认使用 15 秒，然后发送 Ctrl-C 结束 Arthas listener，再关闭 stream。没有完整 Arthas
prompt 的部分输出会被报告为 timeout/连接错误，不会被误报为成功结果。

`start` 结束后关闭 game-probe 会话；`shell` / `query` 在成功、失败或中断时关闭 stream 并 `unforward`。
`TypeNotPresentException` 自动重连保持同一 serial。

### 程序化使用

推荐走 `ArthasManager`：

```python
from scripts.tools.connector.client import ConnectorClient
from scripts.tools.lib.agent_client import AgentClient
from scripts.tools.arthas.manager import ArthasManager
from scripts.tools.arthas.shell import ArthasShell

conn = ConnectorClient()
conn.connect()
conn.select("localhost:15555")

        mgr = ArthasManager(connector=conn, agent_client=None)
        mgr.start(port=8099)

        stream = conn.connect_arthas_stream(arthas_port=8099)
shell = ArthasShell(stream=stream)
print(shell.command("thread -n 3"))
stream.close()
        mgr.stop(port=8099)       # daemon reset only; backend stays alive
conn.close()
```

等价加载细节见 `manager.py` 的 `start()` / `stop()` / `shutdown()`。

## 协议

纯文本行协议。命令以 `\n` 结束，需消费 prompt `[arthas@PID]$ `：

```
→ version\n
← 3.6.9\n[arthas@12345]$
```

`ArthasShell.command()` 负责 drain prompt、发送命令、读到下一完整 prompt。Connector
握手与首段 stream 数据同包到达时，握手后的 remainder 会保留并交给 `Stream`，不会丢失。
Bridge 会话在 readline handler 尚未注册时暂存已到达的输入行，避免连接建立竞态丢弃查询命令；
bridge 只使用一次 shell 初始化和 readline 生命周期，不额外写入非 Arthas 协议的 ready 文本。

命令用法见离线文档 [`docs/`](docs/)（索引 [`docs/README.md`](docs/README.md)）与[官方命令列表](https://arthas.aliyun.com/doc/commands.html)。

## Android 特定说明

### ClassLoader

MTS 为每个 mod 使用独立 `URLClassLoader`。对这类类需显式 `-c <classLoaderHash>`：

```bash
sc -d com.megacrit.cardcrawl.cards.AbstractCard
jad -c 3d4eac69 com.megacrit.cardcrawl.cards.AbstractCard
```

### 字节码增强与 CommonSuperBridge

ClassLoader 隔离会导致 ASM `getCommonSuperClass()` 失败，并使 ByteKit 直接调用
`ClassLoader.getResourceAsStream()` 时找不到 MTS 中已加载类的字节码。`CommonSuperBridge`
经 `Instrumentation.getAllLoadedClasses()` 定位目标类，再通过 `Class.getResourceAsStream()`
读取资源；每次连接时会重转换已加载的 `ClassMetaClassWriter` 和 `ClassLoaderUtils`。

若 `watch` / `trace` / `monitor` 报 `Type xxx not present`：断开重连（CLI 自动重试一次）。

### Bridge 补全

| 命令 | 说明 |
|------|------|
| `classloader-metaspace` | JMX `MemoryPoolMXBean` 查询 Metaspace / CompressedClassSpace（3.6.9 无官方类，由 `MetaspaceCommand` 提供） |

### 不可用命令

| 命令 | 原因 |
|------|------|
| `mc` | JRE 无 `tools.jar`；本地 `javac` → `adb push` → `retransform` / `redefine` |

### JFR

运行时为 OpenJDK 8u482，含 `jfr.jar` 与 HotSpot JFR native。缺口是 runtime-pack 未打包
`$JAVA_HOME/lib/jfr/{default,profile}.jfc`。`manager.start()` 会按需下载并推送到设备
`.../runtimes/Internal/lib/jfr/`。

```bash
jfr start -n rec1 --duration 30s -f /data/data/io.stamethyst/files/rec1.jfr
jfr status
jfr stop -r 1
```

dump 路径须在应用私有目录。火焰图也可用 `profiler start -o jfr`。

### 线程 CPU（`/proc` fallback）

ART 上 `ThreadMXBean.getThreadCpuTime()` 常为 0/-1。bridge 加载 `libprocfs_cpu.so`，
无效时读 `/proc/self/task/<tid>/stat`。

```bash
python3 scripts/tools/arthas/build-procfs-so.py   # 需 NDK 27+，aarch64
```

### heapdump

路径必须在应用私有目录（SELinux）：

```bash
heapdump /data/data/io.stamethyst/files/heap.hprof
```

### 与 game-probe 共存

双方使用独立 `ClassFileTransformer`。`reset` 只撤销 Arthas 增强。

## Profiler / 伴生资源

async-profiler 3.0 以扁平 `.so` 部署在 `arthas/`；`libjvm.debuginfo` 用于 strip 后的
`libjvm.so` 上的 AllocTracer 符号（`download-jvm-companion.py` + `push_companion()`）。

```bash
python3 scripts/tools/arthas/build-async-profiler-so.py
# 输出: scripts/tools/arthas/resource/libasyncProfiler-linux-arm64.so

python3 scripts/tools/arthas/download-jvm-companion.py
python3 scripts/tools/arthas/download-jfr-jfc.py
```

async-profiler 对 Pojav / Android 的适配（`build-async-profiler-so.py` 自动打补丁）：

| # | 适配项 | 说明 |
|---|--------|------|
| 1 | Bionic ELF 重定位 | 强制 `musl=false` |
| 2 | VMThread bridge | 无 pthread TLS 时从 `eetop` 建独立 key |
| 3 | 信号栈 | `SA_ONSTACK` |
| 4 | `_native_libs` 并发 | `Profiler::stop()` 加 `_parse_lock` barrier |
| 5 | `libprocfs_cpu.so` | 跳过不兼容的 `parseProgramHeaders` |
| 6 | 非 HotSpot 线程 | `getThreadState()` 返回 `THREAD_RUNNING` |
| 7 | SIGSEGV handler | 禁用不可调用的 `orig_segvHandler` 替换 |

## 与 game-probe 对比

| 能力 | game-probe | Arthas |
|------|-----------|--------|
| 游戏状态 (OBSERVE) / 命令 (EXEC) | ✅ | ❌ |
| 方法参数/返回值 | TracingMonitor | `watch`（OGNL） |
| 调用链耗时 | PERF | `trace` |
| 线程 / 面板 / 类搜索 / 反编译 | ❌ | `thread` / `dashboard` / `sc` / `jad` |
| OGNL / 火焰图 / 堆转储 / 热替换 | ❌ | `ognl` / `profiler` / `heapdump` / `retransform` |

game-probe 负责游戏语义；Arthas 负责通用 JVM 诊断。

## 故障排除

| 症状 | 可能原因 | 处理 |
|------|---------|------|
| `Multiple Android devices online` | 未指定 `--device` / `STS_TEST_DEVICE` | 显式 serial |
| `connect_stream` BrokenPipe | 已执行 `shutdown`（后端销毁、端口释放） | 重新 `start`；无需重启游戏 |
| `shell closed before a complete prompt` | 后端已被 `shutdown` 命令销毁 | 重新 `start`（幂等 attach 会重建 bootstrap） |
| `LOAD_AGENT` → `already bind` | bridge 重复加载 | 无需处理；幂等 attach 会复用现有监听器 |
| `Could not initialize class ...Enhancer` / `SpyAPI$AbstractSpy` | `arthas-spy.jar` 内容错误或未与 `arthas-core.jar` 同目录部署 | 确认 spy JAR 包含 `java/arthas/SpyAPI.class`、`SpyAPI$AbstractSpy.class`，并重启游戏后重新 `start` |
| `LOAD_AGENT` → class file version | JAR 高于 JDK 8 | `-source 8 -target 8` 重编 bridge |
| `Type xxx not present` | CommonSuperBridge 首次 retransform 未就绪 | 同 serial 重连（CLI 自动重试） |
| `ognl` 返回 `null` | 方法为 `void` | 正常；改用有返回值方法 |
| game-probe `available: false` | 未开 debug/autoplay 等 | `-PdebugMode=true` 等重启 |

## 实现文件

| 文件 | 职责 |
|------|------|
| `manager.py` | daemon RPC facade；请求 daemon 探活/恢复，`stop` 调用 reset，`shutdown` 调用完整销毁 |
| `shell.py` | `ArthasShell`：prompt / 命令 / 输出；`TypeNotPresentException` 重连 |
| `cli.py` | `run_shell` / `run_query` |
| `__main__.py` | CLI：设备解析、`start`/`shell`/`query`/`stop`/`shutdown` |
| `resource/arthas-core.jar` | Arthas 3.6.9 命令引擎 |
| `resource/arthas-bridge.jar` | 自定义 bridge（源码在仓库根 `arthas-bridge/`） |
| `resource/arthas-spy.jar` | Arthas spy |
| `resource/arthas-agent.jar` | 上游 agent（本集成未推送/加载，保留资源） |
| `resource/libprocfs_cpu.so` | 线程 CPU `/proc` fallback |
| `resource/libasyncProfiler-linux-arm64.so` | async-profiler aarch64 |
| `build-procfs-so.py` | 构建 `libprocfs_cpu.so` |
| `build-async-profiler-so.py` | 交叉编译 async-profiler + 平台 patch |
| `download-jvm-companion.py` | 下载 `libjvm.debuginfo` |
| `download-jfr-jfc.py` | 提取 `default.jfc` / `profile.jfc` |

### 设备端（`arthas-bridge/`）

| 文件 | 说明 |
|------|------|
| `ArthasCommandBridge.java` | `agentmain`：Bootstrap、命令注册、幂等 ServerSocket 监听器 + `shutdownBridge()`、`.so` / profiler |
| `MetaspaceCommand.java` | `classloader-metaspace` |
| `ArthasBootstrapCompat.java` | 无 Netty Bootstrap（Apache 2.0 修改） |
| `SocketTerm.java` | 纯 socket `Term` |
| `BridgeSession.java` | 每连接 shell 会话；结束时 `shell.close()` 防止 session 泄漏 |
| `CommonSuperBridge.java` | MTS 下 ASM 公共父类解析 |
| `ClassMetaClassWriterTransformer.java` | 注入 `CommonSuperBridge` |
| `ProcFSBridge.java` / `ProcFSThreadCpuPatch.java` | 线程 CPU fallback |

## 参考

- [Arthas 官方文档](https://arthas.aliyun.com/doc/commands.html)
- [OGNL 语言指南](https://commons.apache.org/dormant/commons-ognl/language-guide.html)
- [表达式核心变量](https://arthas.aliyun.com/doc/advice-class.html)
- 本地离线文档：[`docs/`](docs/)（[`docs/README.md`](docs/README.md)）
