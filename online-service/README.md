# SlayTheAmethyst Online Service

Standalone online service for game-process online status and future EasyTier
control-plane integration. The repository directory, runtime service names,
image names, and deployment examples now all use `online-service`.

It replaces the old
Tencent SCF -> Cloudflare Worker/D1 chain with:

```text
Android app -> Fastify WebSocket -> SQLite3
```

The Android client keeps one WebSocket connection open, sends a full presence
frame when the connection opens or stable metadata changes, then sends minimal
heartbeat frames every 30 seconds by default. The Vue3 panel also uses WebSocket
server push for sessions and stats, so it no longer polls the HTTP endpoints.

## Features

- Public game presence WebSocket: `GET /api/presence/ws`
- Compatibility HTTP heartbeat: `POST /api/presence/heartbeat`
- Public online summary: `GET /api/presence/summary`
- Public online count alias: `GET /api/presence/online-count`
- Protected session list: `GET /api/presence/sessions?token=...`
- Protected hourly stats: `GET /api/presence/stats?token=...&bucket_seconds=3600&window_seconds=604800`
- Vue3 panel: `GET /online` (`/presence` remains available as a legacy alias)
- Panel WebSocket: `GET /api/presence/panel/ws?token=...`
- Cloud-control config: `GET /cloud-control.json`
- EasyTier room session start: `POST /api/lan/session/start`
- EasyTier room session stop: `POST /api/lan/session/stop`
- EasyTier room session status: `GET /api/lan/session/status?sessionId=...`
- EasyTier room info: `GET /api/lan/rooms/{roomId}`
- Protected EasyTier runtime status: `GET /api/easytier/runtime/status?token=...`
- Protected EasyTier runtime start: `POST /api/easytier/runtime/start?token=...`
- Protected EasyTier runtime stop: `POST /api/easytier/runtime/stop?token=...`
- Protected EasyTier runtime restart: `POST /api/easytier/runtime/restart?token=...`

## Run

Windows 开发阶段推荐直接普通启动，不依赖 Docker：

```powershell
cd online-service
npm install
$env:PRESENCE_PANEL_TOKEN = "change-me"
$env:PUBLIC_BASE_URL = "https://presence.example.com"
npm start
```

Open:

```text
http://localhost:3001/online?token=change-me
```

如果只是联调 Presence / Room API，不需要配置 EasyTier 二进制。
如果要联调 EasyTier 本地托管，再额外配置下面的 `EASYTIER_MANAGED*`
和二进制路径。

## Configuration

```text
HOST=0.0.0.0
PORT=3001
PUBLIC_BASE_URL=https://online.example.com
PRESENCE_DB_PATH=./data/presence.sqlite
PRESENCE_HEARTBEAT_INTERVAL_SECONDS=30
PRESENCE_OFFLINE_TIMEOUT_SECONDS=90
QQ_GROUP_NUMBER=1029305387
PRESENCE_PANEL_TOKEN=change-me
PRESENCE_PANEL_SNAPSHOT_PUSH_INTERVAL_SECONDS=2
PRESENCE_PANEL_STATS_PUSH_INTERVAL_SECONDS=300
EASYTIER_ENABLED=false
EASYTIER_ROOM_API_BASE_URL=https://online.example.com
EASYTIER_WEB_CONSOLE_API_BASE_URL=https://online.example.com
EASYTIER_CONFIG_SERVER_SCHEME=udp
EASYTIER_CONFIG_SERVER_PORT=22020
EASYTIER_CONFIG_SERVER_URL=
EASYTIER_ENTRY_NODE_SCHEME=tcp
EASYTIER_ENTRY_NODE_PORT=11010
EASYTIER_ENTRY_NODE_URL=
EASYTIER_CONNECT_TIMEOUT_SECONDS=12
EASYTIER_STATUS_POLL_INTERVAL_SECONDS=5
EASYTIER_SESSION_TTL_SECONDS=1800
EASYTIER_ALLOW_SHARED_COMMUNITY_NETWORK=false
EASYTIER_DEFAULT_MODE=room
EASYTIER_MANAGED=false
EASYTIER_MANAGED_AUTO_START=false
EASYTIER_MANAGED_RESTART_ON_EXIT=true
EASYTIER_MANAGED_STOP_TIMEOUT_MS=5000
EASYTIER_MANAGED_RESTART_DELAY_MS=2000
EASYTIER_RUNTIME_DATA_DIR=./data/easytier-runtime
EASYTIER_WEB_EMBED_BINARY_PATH=
EASYTIER_WEB_EMBED_BINARY_ARGS=
EASYTIER_WEB_EMBED_API_SERVER_PORT=11211
EASYTIER_WEB_EMBED_API_SERVER_ADDR=127.0.0.1
EASYTIER_WEB_EMBED_DISABLE_WEB=true
EASYTIER_WEB_EMBED_INTERNAL_AUTH_TOKEN=
EASYTIER_CORE_BINARY_PATH=
EASYTIER_CORE_BINARY_ARGS=
EASYTIER_CORE_EXTRA_ARGS=
EASYTIER_SHARED_NODE_NETWORK_NAME=sts-online-shared-node
EASYTIER_SHARED_NODE_NETWORK_SECRET=
EASYTIER_SHARED_NODE_INSTANCE_NAME=sts-online-shared-node
EASYTIER_SHARED_NODE_HOSTNAME=sts-online-shared-node
EASYTIER_SHARED_NODE_RPC_PORTAL=127.0.0.1:15888
EASYTIER_SHARED_NODE_CONFIG_SERVER=
LOG_LEVEL=info
```

`PUBLIC_BASE_URL` is used to emit absolute URLs in `/cloud-control.json`.
Behind a reverse proxy, configure it to the public HTTPS origin so Android gets
a `wss://.../api/presence/ws` URL.

When `EASYTIER_ENABLED=true`, `roomApiBaseUrl` and `webConsoleApiBaseUrl`
default to `PUBLIC_BASE_URL`, while `configServerUrl` and `entryNodeUrl`
default to the same host with `udp://:22020` and `tcp://:11010`. You can
override either address with `EASYTIER_CONFIG_SERVER_URL` or
`EASYTIER_ENTRY_NODE_URL`. `EASYTIER_SESSION_TTL_SECONDS` controls how long a
signed room session remains valid before the server expires it automatically.

When `EASYTIER_MANAGED=true`, the Node service can manage local EasyTier child
processes in development:

- `EASYTIER_WEB_EMBED_BINARY_PATH`: local `easytier-web-embed` path
- `EASYTIER_CORE_BINARY_PATH`: local `easytier-core` path
- `EASYTIER_SHARED_NODE_NETWORK_SECRET`: required shared-node network secret
- `EASYTIER_RUNTIME_DATA_DIR`: logs and runtime data directory
- `EASYTIER_MANAGED_AUTO_START=true`: auto start child processes after the
  HTTP service is already listening

If the binary path is missing or the file does not exist, the HTTP service
still starts and `/api/easytier/runtime/status` reports the runtime as
unconfigured. This is intentional for Windows development.

## Windows Local EasyTier Runtime

Example local development setup:

```powershell
cd online-service
npm install
$env:PRESENCE_PANEL_TOKEN = "change-me"
$env:PUBLIC_BASE_URL = "https://online.example.com"
$env:EASYTIER_ENABLED = "true"
$env:EASYTIER_MANAGED = "true"
$env:EASYTIER_MANAGED_AUTO_START = "false"
$env:EASYTIER_WEB_EMBED_BINARY_PATH = "D:\tools\easytier\easytier-web-embed.exe"
$env:EASYTIER_CORE_BINARY_PATH = "D:\tools\easytier\easytier-core.exe"
$env:EASYTIER_SHARED_NODE_NETWORK_SECRET = "change-this-secret"
npm start
```

Check runtime status:

```powershell
Invoke-RestMethod `
  -Uri "http://127.0.0.1:3001/api/easytier/runtime/status?token=change-me"
```

Start child processes:

```powershell
Invoke-RestMethod `
  -Method Post `
  -Uri "http://127.0.0.1:3001/api/easytier/runtime/start?token=change-me"
```

Stop child processes:

```powershell
Invoke-RestMethod `
  -Method Post `
  -Uri "http://127.0.0.1:3001/api/easytier/runtime/stop?token=change-me"
```

Restart child processes:

```powershell
Invoke-RestMethod `
  -Method Post `
  -Uri "http://127.0.0.1:3001/api/easytier/runtime/restart?token=change-me"
```

Runtime logs are written under:

```text
online-service/data/easytier-runtime/logs/
```

## Docker

Docker is for packaging/publishing later. Windows development can stay on the
plain `npm start` flow above.

Build locally:

```powershell
docker build -t ghcr.io/modinmobilests/slaytheamethyst-online-service:latest .
```

Run with Docker Compose from the repository root:

```powershell
docker compose up -d online-service
```

On a server that only has `docker-compose.yaml`, Compose pulls the published
GHCR image directly and does not need the `online-service/` source directory.

The compose file exposes the service on:

```text
http://localhost:3001/online?token=change-me
```

For production, set `PUBLIC_BASE_URL` to the public HTTPS origin and replace
`PRESENCE_PANEL_TOKEN`.

## Cloud-Control Payload

`GET /cloud-control.json` returns the compact WebSocket heartbeat settings and
the official QQ group used by launcher entry points:

```json
{
  "heartbeat": {
    "intervalSeconds": 30,
    "wsUrl": "wss://online.example.com/api/presence/ws"
  },
  "qqGroup": {
    "number": "1029305387"
  },
  "easyTier": {
    "enabled": false,
    "roomApiBaseUrl": "https://online.example.com",
    "webConsoleApiBaseUrl": "https://online.example.com",
    "configServerUrl": "",
    "entryNodeUrl": "",
    "connectTimeoutSeconds": 12,
    "statusPollIntervalSeconds": 5,
    "allowSharedCommunityNetwork": false,
    "defaultMode": "room"
  }
}
```

The HTTP heartbeat endpoint remains available only for compatibility; new app
builds read `heartbeat.wsUrl`, report presence over WebSocket, and use
`qqGroup.number` when opening or displaying the official QQ group. When
EasyTier is enabled, the launcher also reads the `easyTier` section for the
single-server connection entrypoint and room/control-plane base URLs.

## EasyTier Room API

The current service-side MVP keeps Room API state in the same SQLite database
as presence snapshots. It does not call the real EasyTier server or web console
yet; instead, it signs short-lived launcher sessions so the Android client can
finish protocol integration against `/api/lan/*`.

Start a room session:

```powershell
Invoke-RestMethod `
  -Method Post `
  -Uri http://127.0.0.1:3001/api/lan/session/start `
  -ContentType 'application/json' `
  -Body '{"roomId":"alpha-room","playerId":"alice","displayName":"Alice","clientVersion":"1.4.8","deviceSummary":"Pixel 8 sdk35"}'
```

Query session status:

```powershell
Invoke-RestMethod `
  -Uri "http://127.0.0.1:3001/api/lan/session/status?sessionId=lan_xxx"
```

Query room info:

```powershell
Invoke-RestMethod `
  -Uri http://127.0.0.1:3001/api/lan/rooms/alpha-room
```

Stop a room session:

```powershell
Invoke-RestMethod `
  -Method Post `
  -Uri http://127.0.0.1:3001/api/lan/session/stop `
  -ContentType 'application/json' `
  -Body '{"sessionId":"lan_xxx"}'
```

Business rules in the current MVP:

- The first player to join a room becomes the room owner.
- Starting a new session for the same `roomId + playerId` supersedes the older
  active session.
- Room session responses include short-lived `sessionId`, `aclGroup`,
  `networkSecret`, and `expiresAt` fields expected by the Android client.
- `GET /api/lan/rooms/{roomId}` returns the latest known member identity for
  each player and whether that player currently has an active unexpired session.

## WebSocket Messages

App -> server full presence frame, sent on WebSocket connect and whenever
metadata changes:

```json
{
  "type": "presence",
  "client_id": "android:...",
  "device_id": "...",
  "id_type": "android_id_sha256",
  "state": "game",
  "player_name": "Player",
  "app_version": "1.4.8",
  "device_model": "Google Pixel 8",
  "android_version": "Android 15 (SDK 35)",
  "sent_at": 1760000000000
}
```

App -> server minimal heartbeat frame, sent while the WebSocket connection is
already established and metadata is unchanged:

```json
{
  "type": "presence",
  "client_id": "android:...",
  "state": "game",
  "sent_at": 1760000000000
}
```

Minimal heartbeat frames update `state` and the latest heartbeat timestamp.
Missing metadata fields keep their previous stored values so the panel continues
to show player name, app version, model, and Android version from the full
presence frame.

Server -> app:

```json
{
  "type": "presence_ack",
  "ok": true,
  "online": 1,
  "totalOnlineUsers": 1,
  "heartbeatIntervalSeconds": 30,
  "offlineTimeoutSeconds": 90,
  "storageBackend": "sqlite3"
}
```

Panel messages use `type: "snapshot"` and `type: "stats"` with payloads matching
the compatibility HTTP JSON responses. Send `type: "refresh_stats"` with
`windowSeconds` to switch the trend window; supported panel choices are 24
hours, 3 days, 7 days, 14 days, and 30 days.

The panel pie chart can switch between current online sessions, current-day
unique devices, and historical unique devices. Current-day distribution uses
the `Asia/Hong_Kong` natural-day boundary and includes every device whose
latest heartbeat landed on that day. Historical distribution is aggregated from
all rows in `presence_sessions`, while online distribution is calculated from
active sessions in the latest panel snapshot. Snapshot-driven panel data is
pushed every 2 seconds by default; hourly trend stats remain on the slower
server-push cadence.
