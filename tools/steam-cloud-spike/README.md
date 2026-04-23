# Steam Cloud Read-Only Spike

这个模块是一个独立的 JVM 命令行 spike，用来验证 `Slay the Spire` 的 Steam 云存档链路是否能走通。

当前目标只包括：

- Steam 登录
- 枚举 `646570` 的云文件列表
- 按需下载选中的云文件

当前明确不做：

- 上传/删除
- 主 launcher 接入
- Android app 运行时集成

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

默认输出目录：

```text
.tmp/sts-steam-cloud-spike
```

主要文件：

- `cloud-list.tsv`：完整云文件清单
- `downloads/`：下载下来的文件
- `downloads.tsv`：下载结果清单

如果指定了 `--write-auth-file`，还会写出一个包含敏感信息的环境变量文件。
