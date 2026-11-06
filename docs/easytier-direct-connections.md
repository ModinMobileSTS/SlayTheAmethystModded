# EasyTier UDP 直连配置

安卓启动器和 Windows 联机伴侣的客户端配置统一使用：

```toml
listeners = ["udp://0.0.0.0:0"]
```

`0` 表示由系统分配空闲 UDP 端口，不是监听服务器的固定端口。EasyTier
将实际监听地址提供给同房间节点，让可达的局域网或公网地址成为直连候选。

## 修复内容与边界

- 安卓实现：`lan-core/src/main/kotlin/io/stamethyst/backend/easytier/EasyTierRuntimeConfigBuilder.kt`。
- PC 实现：`desktop-companion/src/main.rs` 的 `easytier_config`。
- 原配置 `listeners = []` 未禁用自动 UDP 打洞，但移除了常规监听的直接连接候选。
  同局域网设备因而也可能依赖公网 NAT 回流打洞，失败后只能继续中转。
- 保留房间网络身份、DHCP / 静态虚拟 IP、服务端下发的 `entryNodeUrl`、自动打洞和中转回退。
  不将 `configServerUrl` 当作 peer，也不要求把 TCP 入口改为 UDP。
- Windows 防火墙需要允许运行时 `easytier-core.exe` 在目标网络上通信。
- 此修复恢复可达节点的直接连接机会，不保证所有运营商 NAT / 防火墙下的公网打洞成功。

## 验证

```bash
./gradlew :lan-core:test
./gradlew :app:testDebugUnitTest --tests io.stamethyst.backend.easytier.EasyTierRuntimeConfigBuilderTest
cargo test --manifest-path desktop-companion/Cargo.toml easytier_config
```

配置回归覆盖随机端口监听、DHCP / 静态 IP、网络身份和引导 peer 不变。

运行时验收需至少两名客户端进入同一房间：

1. 同局域网：确认双方运行时存在实际 UDP listener，peer/route 显示对方为 `p2p`，
   隧道类型为 `udp`，而非经共享节点的 `relay(2)`。
2. 不同网络：记录双方 NAT 类型、STUN 结果和打洞日志；受限环境允许正常回退到中转。
3. 断开并重新加入房间，确认旧 listener 被释放、随机端口重新分配且虚拟 IP 正常。

共享服务器仅统计经过自己的流量，其“中转占比”不代表全体玩家的 P2P 失败率；
客户端之间的直连流量不会出现在服务器转发统计里。

## 房间成员连接胶囊

安卓房间成员列表显示本机到各玩家的连接方式，并复用现有 EasyTier 运行时轮询：

- `本机`：当前玩家；`离线`：房间 API 标记该成员离线。
- `直连 · UDP / TCP`：成员虚拟 IPv4 对应一跳路由，且存在未关闭的 peer 连接。
  协议来自这些直接连接；存在多个协议时一并列出。
- `中转`：成员的有效路由代价大于一跳，即使同时存在直连候选也按路由显示。
- `连接中`：正在加入 / 重连，或新鲜运行时数据中尚未发现该成员的可用路由。
- `未知`：尚未成功读取运行信息，或观察已过期。
- `未连接`：本机没有加入该房间，或当前连接已断开 / 失败。

成员匹配使用虚拟 IP 的主机地址，不使用代理网段、房主身份或固定的中转服务器文案。
公共服务器路由和本机路由不参与匹配。观察有效期为 `max(30 秒, 3 × 轮询间隔)`；
运行时读数为空、缺少本机 IP、失败、断线、权限变化或切换会话时清除旧观察。
不向服务端上报成员之间的连接信息，也不把中转第一跳延迟冒充端到端延迟。
