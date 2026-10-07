# Steam CM Protocol Spike

这个模块包含独立的 Python 交互登录 CLI 和 JVM 云协议工具，用来验证 `Slay the Spire` 的 Steam 云存档链路是否能走通。

当前目标包括：

- Steam 登录
- 枚举 `646570` 的云文件列表
- 按需下载选中的云文件
- 在显式确认后上传和删除云文件
- 受显式确认保护的单一成就协议实验

当前明确不做：

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

## Python 交互登录（全新实现）

直接在终端运行 `login.py`，不经过 Gradle、JavaSteam 或旧 JVM 登录工具。脚本使用
Steam Authentication HTTPS API，申请 **SteamClient** 平台的持久 refresh token，供后续
CM 云工具使用。不会读取旧 token 跳过登录，也不会请求 depot key、写云存档或修改成就。

Python 3.10+；首次安装依赖后登录：

```sh
python3 -m pip install -r tools/steam-cloud-spike/login/requirements.txt
python3 tools/steam-cloud-spike/login.py
```

Windows 使用 `python` 替代 `python3`。推荐在自己的虚拟环境中安装依赖。
按提示输入账号和隐藏的密码；有多种 Steam Guard 方式时可选择手机确认、手机令牌码或邮箱验证。
验证码错误/过期可重输（最多三次），限流不会自动重试；手机确认等待默认最多五分钟。
`Ctrl+C` 可以取消。失败、超时或取消不会替换已有会话；重新登录成功后替换已有文件需要交互确认，
或者事先明确传入 `--overwrite`。

```sh
python3 tools/steam-cloud-spike/login.py --username your_account --overwrite
python3 tools/steam-cloud-spike/login.py --proxy-url http://127.0.0.1:7897
python3 tools/steam-cloud-spike/login.py --no-proxy --timeout 600
python3 tools/steam-cloud-spike/login.py --output agent-tmp/another-session.env
```

代理默认使用 `STEAM_PROXY_URL`，否则使用 Requests 的标准 HTTP(S) 代理环境变量。
`--no-proxy` 强制直连。代理地址（可能含凭据）不输出到日志。
密码和验证码只允许隐藏交互输入，不接受密码/token 命令行参数、环境变量或管道，
也没有 `--print-token` 开关。`--help` 不需要安装依赖。

旧 Gradle `login` / `refreshToken` 任务及 JVM refresh-token 登录实现已移除，
请使用上面的 Python 命令；depot/成就/云工具自身的现有协议行为保持不变。

默认会将敏感信息写到稳定的本机会话文件：

```text
agent-tmp/steam-desktop-session.env
```

文件包含 `STEAM_ACCOUNT_NAME`、`STEAM_STEAM_ID64`、`STEAM_REFRESH_TOKEN` 和可用时的 `STEAM_GUARD_DATA`，兼容现有 JVM 会话读取器。
默认路径锚定到仓库根目录，不受调用目录影响。密码及 access token 不落盘；token 不回显。
文件通过同目录随机临时文件原子替换，POSIX 系统从创建临时文件开始即为 `0600`。
Windows 请确认存放目录的 ACL 仅允许当前用户访问。不要提交、分享或上传该文件；默认目录已被 Git 忽略。

离线测试（不需要账号、不访问 Steam）：

```sh
python3 -m unittest discover -s tools/steam-cloud-spike/login -p 'test_*.py'
```

测试覆盖 RSA 加密、Steam Guard 选择/重试、轮询标识变化、超时/取消、错误脱敏、代理和原子私密存储。
真实账号登录需要用户在自己的终端完成，不会在自动测试中发起。

后续 `depotKey` 和 `achievementUnlock` 命令会自动读取这个会话文件，不需要重复登录：

```powershell
.\gradlew.bat :tools:steam-cloud-spike:depotKey --args="--no-output"
.\gradlew.bat :tools:steam-cloud-spike:achievementUnlock --args="--confirm-shrug-it-off --no-output"
.\gradlew.bat :tools:steam-cloud-spike:achievementLock --args="--confirm-lock-shrug-it-off --no-output"
```

## Steam Cloud 读写验收

默认的 `run` 只读列出云文件；下载也不会修改云端。上传和删除必须分别带确认参数，命令完成后会重新读取 manifest 验证结果：

```powershell
.\gradlew.bat :tools:steam-cloud-spike:run --args="--upload-path sts-manual/test.save --upload-source .tmp/test.save --confirm-cloud-write"
.\gradlew.bat :tools:steam-cloud-spike:run --args="--delete-path sts-manual/test.save --confirm-cloud-delete"
.\gradlew.bat :tools:steam-cloud-spike:run --args="--download-path sts-manual/test.save"
```

真实云端验收测试默认跳过。先用 `python3 tools/steam-cloud-spike/login.py` 获取登录态，
再设置 `STS_STEAM_CLOUD_LIVE=true` 运行只读/下载测试。写入/删除测试还需要额外明确设置
`STS_STEAM_CLOUD_ACCEPT_MUTATIONS=true`：

```powershell
$env:STS_STEAM_CLOUD_LIVE="true"
# 只读/下载：无需开启云端写入
.\gradlew.bat :tools:steam-cloud-spike:test --tests "*authenticatedCloudReadAndDownload"

# 仅在明确允许创建/删除验收文件后启用：
$env:STS_STEAM_CLOUD_ACCEPT_MUTATIONS="true"
.\gradlew.bat :tools:steam-cloud-spike:test --tests "*SteamCloudLiveAcceptanceTest"
```

没有认证会话时测试会输出明确的 WARNING 并跳过，不会尝试登录或修改云端。

写入验收只使用 `sts-acceptance/<随机 UUID>.save`：先确认目标不存在，上传后下载并逐字节
比较，再删除并重新读取完整清单，确认清单恢复到测试前状态。删除或清理失败会使测试失败，
不会被忽略。临时文件放在 `agent-tmp/steam-cloud-live/`；失败时保留诊断目录和
`remote-path.txt`，便于定位并清理该次验收文件。

上传工具为 JavaSteam `beginFileUpload` 显式提供非空、随 Runner 关闭而取消的 coroutine
scope；该 API 没有可供 Java 省略 scope 的重载，传 `null` 会在发送上传请求前抛异常。
上传块的 HTTP 方法按 Steamworks `EHTTPMethod` 映射为 `POST=3`、`PUT=4`，不能误用
`HEAD=2` 作为 POST；离线回归测试覆盖这两种上传方法及其他方法的拒绝行为。

## 只读成就协议探测

`achievementProbe` 使用本机会话读取指定成就的 CM schema、原始 stat、achievement block 和
`ClientStoreUserStats2` 可用字段。它不会发送任何成就或统计写入请求，默认目标为 `minimalist`：

```powershell
.\gradlew.bat :tools:steam-cloud-spike:achievementProbe
.\gradlew.bat :tools:steam-cloud-spike:achievementProbe --args="--achievement-api-name minimalist"
```

## 原始 Bit 实验

仅限测试账号。`achievementBitProbe` 写入一个明确指定的 CM stat bit，再比较 Steam Player
服务返回的已解锁成就名称。它必须同时指定目标和确认标记：

```powershell
.\gradlew.bat :tools:steam-cloud-spike:achievementBitProbe --args="--stat-id 1 --bit-index 26 --confirm-unsafe-stat-bit"
```

该命令会改变账号状态；不要用于正常账号或未知 AppID。

如果环境变量中的代理导致 Steam CM TLS/WebSocket 握手失败，可以对单次命令强制直连：

```powershell
.\gradlew.bat :tools:steam-cloud-spike:achievementLock --args="--confirm-lock-shrug-it-off --no-output --no-proxy"
```

`--no-proxy` 会覆盖 `STEAM_PROXY_URL`、`HTTPS_PROXY` 和 `HTTP_PROXY`；命令启动时会输出
`steamTransport=direct` 或所选代理地址，方便确认实际连接路径。

如果需要重新授权或更换账号，再次运行 Python 登录 CLI。其他 JVM 工具可用
`--env-file` 指定会话文件；这些工具的命令行参数和环境变量会覆盖文件中的同名值。

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

### 方式 1：自动读取 Python 登录会话（推荐）

```powershell
python tools/steam-cloud-spike/login.py
.\gradlew :tools:steam-cloud-spike:run
```

云工具自动读取仓库根目录的 `agent-tmp/steam-desktop-session.env`，不需要把密码或 token 放进环境变量。

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

## 云写入验收

云读取和下载默认是只读的。上传和删除必须分别提供确认标记；命令会先完成批次，再重新读取清单验证结果。

```powershell
.\gradlew :tools:steam-cloud-spike:run --args="--upload-path preferences/STSPlayer --upload-source .tmp/STSPlayer --confirm-cloud-write"
.\gradlew :tools:steam-cloud-spike:run --args="--delete-path preferences/STSPlayer --confirm-cloud-delete"
```

不要把生产存档作为验收目标；Steam RPC 没有远端版本前置条件，两个设备同时写入时仍可能发生最后写入者覆盖。

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
