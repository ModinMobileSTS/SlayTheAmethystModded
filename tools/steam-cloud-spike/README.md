# Steam CM Protocol Spike

这个模块是一个独立的 JVM 命令行 spike，用来验证 `Slay the Spire` 的 Steam 云存档链路是否能走通。

当前目标包括：

- Steam 登录
- 枚举 `646570` 的云文件列表
- 按需下载选中的云文件
- 受显式确认保护的单一成就协议实验

当前明确不做：

- 上传/删除
- 主 launcher 接入
- Android app 运行时集成

## 成就协议实验

`achievementUnlock` 与 `achievementLock` 只面向 `Slay the Spire` 的 `shrug_it_off`。
前者必须提供 `--confirm-shrug-it-off`，后者必须提供
`--confirm-lock-shrug-it-off`。两者都会先读取用户统计和 schema，保留返回的 `crc_stats`，
只设置或清除该成就的单个 stat bit，再发送 `ClientStoreUserStats2`（EMsg `5466`），并等待
`ClientStoreUserStatsResponse`（EMsg `821`）的 Job 响应。仅当响应为 `EResult.OK`、
没有验证错误且重新读取确认对应 bit 状态后，命令才报告成功。它们不会重置整个 stat 或其他成就 bit。

这不是 Steam 官方面向普通用户的 API。请只在你拥有权限的账号和游戏上进行实验；
联网、反作弊或服务器权威游戏可能拒绝写入或出现进度不一致。Android app 不会调用这个
实验写入路径。

## 在电脑上获取 Refresh Token

`refreshToken` 任务会在电脑上完成 Steam 凭据登录和 Steam Guard 验证，取得持久 refresh token，方便调试 CM 协议而不依赖 Android 设备上的登录状态。该任务不会请求 depot key。

默认会将敏感信息写到稳定的本机会话文件：

```text
agent-tmp/steam-desktop-session.env
```

文件包含 `STEAM_ACCOUNT_NAME`、`STEAM_STEAM_ID64`、`STEAM_REFRESH_TOKEN` 和可用时的 `STEAM_GUARD_DATA`。不要提交、分享或上传该文件；Linux/macOS 上工具会尝试将文件权限限制为当前用户读写。

后续 `depotKey` 和 `achievementUnlock` 命令会自动读取这个会话文件，不需要重复登录：

```powershell
.\gradlew.bat :tools:steam-cloud-spike:depotKey --args="--no-output"
.\gradlew.bat :tools:steam-cloud-spike:achievementUnlock --args="--confirm-shrug-it-off --no-output"
.\gradlew.bat :tools:steam-cloud-spike:achievementLock --args="--confirm-lock-shrug-it-off --no-output"
```

如果环境变量中的代理导致 Steam CM TLS/WebSocket 握手失败，可以对单次命令强制直连：

```powershell
.\gradlew.bat :tools:steam-cloud-spike:achievementLock --args="--confirm-lock-shrug-it-off --no-output --no-proxy"
```

`--no-proxy` 会覆盖 `STEAM_PROXY_URL`、`HTTPS_PROXY` 和 `HTTP_PROXY`；命令启动时会输出
`steamTransport=direct` 或所选代理地址，方便确认实际连接路径。

如果需要重新授权或更换账号，使用 `--reauthenticate`。它会忽略现有 refresh token，重新请求账号密码和 Steam Guard/授权确认，然后覆盖本机会话文件：

```powershell
.\gradlew.bat :tools:steam-cloud-spike:refreshToken --args="--reauthenticate"
```

也可以通过 `--env-file` 指定其他会话文件；命令行参数和环境变量会覆盖文件中的同名值。

交互式登录：

```powershell
.\gradlew.bat :tools:steam-cloud-spike:refreshToken
```

也可提前提供账号和密码，Steam Guard 仍会按需提示：

```powershell
$env:STEAM_USERNAME="your_steam_account"
$env:STEAM_PASSWORD="your_password"
.\gradlew.bat :tools:steam-cloud-spike:refreshToken
```

需要本地代理时：

```powershell
.\gradlew.bat :tools:steam-cloud-spike:refreshToken --args="--proxy-url http://127.0.0.1:7897"
```

token 默认不会回显到终端。仅在确实需要复制到临时调试环境时才使用：

```powershell
.\gradlew.bat :tools:steam-cloud-spike:refreshToken --args="--print-token --no-output"
```

## 获取 Depot Key

`depotKey` 任务会用用户自己的 Steam 登录态请求 depot decryption key。默认目标是 `appId=646570`、`depotId=877621`，也就是当前自动导入路径遇到加密文件名时需要的桌面 depot key。

推荐直接运行 PowerShell 脚本，按提示输入账号密码；结果会回显到终端，不写本地 key 文件：

```powershell
.\tools\steam-cloud-spike\get-depot-key.ps1
```

如果需要 Steam Guard，可以直接按提示输入手机令牌动态码；也可以提前设置环境变量：

```powershell
$env:STEAM_USERNAME="your_steam_account"
$env:STEAM_PASSWORD="your_password"
$env:STEAM_2FA_CODE="12345"
.\tools\steam-cloud-spike\get-depot-key.ps1
```

也可以通过脚本参数传入账号，密码仍会由脚本隐藏输入：

```powershell
.\tools\steam-cloud-spike\get-depot-key.ps1 -Username "your_steam_account"
```

如果你已有之前保存的 refresh token/env 文件，可以让脚本读取后直接回显 depot key：

```powershell
.\tools\steam-cloud-spike\get-depot-key.ps1 -EnvFile "agent-tmp\steam-depot-key-646570-877621.env"
```

如果本机需要代理连接 Steam CM：

```powershell
.\tools\steam-cloud-spike\get-depot-key.ps1 -ProxyUrl "http://127.0.0.1:7897"
```

底层 Gradle 任务仍然可直接调用；如果要直接写本地凭据文件，可以不用脚本，改用：

```powershell
.\gradlew.bat :tools:steam-cloud-spike:depotKey --args="--app-id 646570 --depot-id 877621"
```

## 运行

先看帮助：

```powershell
.\gradlew :tools:steam-cloud-spike:run --args="--help"
```

### 方式 1：账号密码登录

```powershell
$env:STEAM_USERNAME="your_steam_account"
$env:STEAM_PASSWORD="your_password"
.\gradlew :tools:steam-cloud-spike:run --args="--write-auth-file .tmp/sts-steam-cloud-spike/auth.env"
```

如果账号需要 Steam Guard：

- 可以等手机确认，默认开启 `accept-device-confirmation`
- 或者提前设置 `STEAM_2FA_CODE`
- 邮箱验证码可设置 `STEAM_EMAIL_CODE`
- 如果你是通过 `gradlew ... :run` 启动，当前版本已经显式透传 `stdin`；如果仍然拿不到交互输入，优先改用 `STEAM_2FA_CODE` / `STEAM_EMAIL_CODE`
- 注意：JavaSteam 1.6.0 的 websocket 传输层有一个大约 30 秒的无响应 watchdog。`protocol=auto` 会优先尝试 websocket，所以如果你需要手动输入 2FA，最好提前把 `STEAM_2FA_CODE` / `STEAM_EMAIL_CODE` 设好；如果本机直连 TCP 可用，也可以改成 `--protocol tcp`

### 方式 2：refresh token 登录

```powershell
$env:STEAM_ACCOUNT_NAME="your_steam_account"
$env:STEAM_REFRESH_TOKEN="your_refresh_token"
.\gradlew :tools:steam-cloud-spike:run
```

## 连接排查

如果问题发生在登录之前，先只验证 Steam 传输层，不走鉴权：

```powershell
.\gradlew :tools:steam-cloud-spike:run --args="--connect-only"
```

强制走 websocket，并显式指定代理：

```powershell
$env:STEAM_PROXY_URL="http://127.0.0.1:7897"
.\gradlew :tools:steam-cloud-spike:run --args="--connect-only --protocol websocket"
```

也可以直接复用常见代理环境变量：

```powershell
$env:HTTP_PROXY="http://127.0.0.1:7897"
$env:HTTPS_PROXY="http://127.0.0.1:7897"
.\gradlew :tools:steam-cloud-spike:run --args="--connect-only --protocol websocket"
```

强制走 TCP：

```powershell
.\gradlew :tools:steam-cloud-spike:run --args="--connect-only --protocol tcp"
```

注意：

- `--protocol tcp` 目前不会通过 `http://...` 代理隧道转发，所以如果你依赖本地 HTTP 代理，优先先测 `websocket`
- 当前实现会把 `STEAM_PROXY_URL` / `HTTP_PROXY` / `HTTPS_PROXY` 同步到 JVM 代理系统属性，尽量让 JavaSteam 的目录拉取和 websocket 链路吃到同一套代理配置

## 下载示例

下载全部云文件：

```powershell
.\gradlew :tools:steam-cloud-spike:run --args="--download-all"
```

按索引下载：

```powershell
.\gradlew :tools:steam-cloud-spike:run --args="--download-index 1 --download-index 2"
```

按路径或模糊匹配下载：

```powershell
.\gradlew :tools:steam-cloud-spike:run --args="--download-path %WinAppDataRoaming%/SlayTheSpire/preferences/STSPlayer"
.\gradlew :tools:steam-cloud-spike:run --args="--download-match preferences"
```

## 输出

默认云存档输出目录：

```text
.tmp/sts-steam-cloud-spike
```

主要文件：

- `cloud-list.tsv`：完整云文件清单
- `downloads/`：下载下来的文件
- `downloads.tsv`：下载结果清单

如果指定了 `--write-auth-file`，还会写出一个包含敏感信息的环境变量文件。
