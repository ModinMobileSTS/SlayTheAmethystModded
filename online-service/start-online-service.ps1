$ErrorActionPreference = "Stop"

$serviceDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $serviceDir

$localIp = "192.168.31.137"
$panelToken = "change-me"

# Local EasyTier runtime binaries used by online-service managed mode.
$easyTierWebEmbedBinary = "D:\Desktop\SlayTheAmethystModded\agent-tmp\easytier-windows-v2.6.4\easytier-windows-x86_64\easytier-web-embed.exe"
$easyTierCoreBinary = "D:\Desktop\SlayTheAmethystModded\agent-tmp\easytier-windows-v2.6.4\easytier-windows-x86_64\easytier-core.exe"

$env:HOST = "0.0.0.0"
$env:PORT = "3001"
$env:PUBLIC_BASE_URL = "http://${localIp}:3001"
$env:PRESENCE_DB_PATH = "./data/presence.sqlite"
$env:PRESENCE_PANEL_TOKEN = $panelToken
$env:PRESENCE_HEARTBEAT_INTERVAL_SECONDS = "30"
$env:PRESENCE_OFFLINE_TIMEOUT_SECONDS = "90"
$env:PRESENCE_PANEL_SNAPSHOT_PUSH_INTERVAL_SECONDS = "2"
$env:PRESENCE_PANEL_STATS_PUSH_INTERVAL_SECONDS = "300"
$env:LOG_LEVEL = "info"

$env:EASYTIER_ENABLED = "true"
$env:EASYTIER_ROOM_API_BASE_URL = "http://${localIp}:3001"
$env:EASYTIER_WEB_CONSOLE_API_BASE_URL = "http://${localIp}:3001"
$env:EASYTIER_CONFIG_SERVER_URL = "udp://${localIp}:22020"
$env:EASYTIER_ENTRY_NODE_URL = "tcp://${localIp}:11010"
$env:EASYTIER_CONNECT_TIMEOUT_SECONDS = "12"
$env:EASYTIER_STATUS_POLL_INTERVAL_SECONDS = "5"
$env:EASYTIER_SESSION_TTL_SECONDS = "1800"
$env:EASYTIER_ALLOW_SHARED_COMMUNITY_NETWORK = "false"
$env:EASYTIER_DEFAULT_MODE = "room"

if ([string]::IsNullOrWhiteSpace($easyTierWebEmbedBinary) -or [string]::IsNullOrWhiteSpace($easyTierCoreBinary)) {
    $env:EASYTIER_MANAGED = "false"
    $env:EASYTIER_MANAGED_AUTO_START = "false"
} else {
    $env:EASYTIER_MANAGED = "true"
    $env:EASYTIER_MANAGED_AUTO_START = "true"
    $env:EASYTIER_MANAGED_RESTART_ON_EXIT = "true"
    $env:EASYTIER_RUNTIME_DATA_DIR = "../agent-tmp/online-service-local-run/easytier-runtime"
    $env:EASYTIER_WEB_EMBED_BINARY_PATH = $easyTierWebEmbedBinary
    $env:EASYTIER_CORE_BINARY_PATH = $easyTierCoreBinary
    $env:EASYTIER_SHARED_NODE_NETWORK_SECRET = "local-dev-shared-secret"
    $env:EASYTIER_SHARED_NODE_INSTANCE_NAME = "sts-online-shared-node"
    $env:EASYTIER_SHARED_NODE_HOSTNAME = "sts-online-shared-node"
    $env:EASYTIER_SHARED_NODE_NETWORK_NAME = "sts-online-shared-node"
    $env:EASYTIER_SHARED_NODE_RPC_PORTAL = "127.0.0.1:15889"
    # Do not pass --config-server to shared-node in local dev; this EasyTier build
    # rejects the bare config-server URL with "empty token". web-embed still owns
    # udp://<host>:22020 for cloud-control clients.
    $env:EASYTIER_SHARED_NODE_CONFIG_SERVER = ""
}

if (-not (Test-Path ".\node_modules")) {
    npm install
}

Write-Host "Starting online-service..."
Write-Host "Panel: http://${localIp}:3001/online?token=$panelToken"
Write-Host "Cloud control: http://${localIp}:3001/cloud-control.json"
Write-Host "Room API: http://${localIp}:3001/api/lan/session/start"

npm start
