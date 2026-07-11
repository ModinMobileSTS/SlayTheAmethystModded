# Cloudflare Presence Storage Worker

This Worker stores legacy game presence data in Cloudflare D1. It is an
internal storage component, not the Android client's heartbeat entrypoint.

Heartbeat/presence via Tencent SCF is deprecated. Current Android builds should
load cloud-control from `../online-service` and send game or launcher presence
to `heartbeat.wsUrl`, which resolves to `GET /api/presence/ws` on the standalone
presence service. Do not point clients at this Worker, and do not use Tencent
SCF as a heartbeat relay.

Keep this Worker only for legacy deployments that still need D1-backed presence
storage, migration access, or historical data inspection.

## Endpoints

All internal endpoints require:

```text
Authorization: Bearer <PRESENCE_STORAGE_SECRET>
```

```text
POST /internal/presence/heartbeat
GET  /internal/presence/summary
GET  /internal/presence/sessions
GET  /internal/presence/stats?bucket_seconds=3600
GET  /healthz
```

`/healthz` does not require the secret.

`POST /internal/presence/heartbeat` is an authenticated internal storage API.
It is not a public app endpoint and should never be published through
cloud-control.

## Data model

```text
presence_sessions          Current latest heartbeat per client_id
presence_hourly_snapshots  Hourly online-count snapshots used for one-week charts
```

`presence_sessions` is never TTL-deleted; `first_seen_at_ms` preserves historical unique-device counting and `last_seen_at_ms` drives online checks. There is intentionally no `last_seen_at_ms` index because each heartbeat updates that column and the index would double D1 write usage. `presence_hourly_snapshots` is pruned to the latest week.

## Deploy

Install dependencies:

```powershell
cd cloudflare-presence-worker
npm install
```

Login to Cloudflare:

```powershell
npx wrangler login
```

Create the D1 database:

```powershell
npm run d1:create
```

Copy the returned `database_id` into `wrangler.toml`:

```toml
[[d1_databases]]
binding = "DB"
database_name = "sts-presence"
database_id = "..."
```

Create the internal secret:

```powershell
npx wrangler secret put PRESENCE_STORAGE_SECRET
```

Apply the remote D1 migration:

```powershell
npm run d1:migrate:remote
```

The hourly snapshot migration creates `presence_hourly_snapshots`. The third migration removes the older heartbeat-history table and `last_seen_at_ms` index to reduce write amplification. The fourth migration adds the heartbeat `device_model` and `android_version` columns used by the panel. Apply migrations before deploying Worker code that reads the weekly chart or the device-info fields.

Deploy the Worker:

```powershell
npm run deploy
```

The deployed Worker uses a Cron Trigger (`0 * * * *`) to write one online-count snapshot every hour. The stats endpoint also refreshes the current hour at most once every five minutes when the panel is open.

Legacy SCF-backed deployments used these environment variables to call the
Worker as internal storage:

```text
PRESENCE_STORAGE_URL=https://sts.presence.mctown.online
PRESENCE_STORAGE_SECRET=<same value as the Worker secret>
PRESENCE_STORAGE_TIMEOUT_MS=3000
```

Legacy SCF-backed deployments also used these Tencent-side business
configuration values:

```text
PRESENCE_HEARTBEAT_INTERVAL_SECONDS=600
PRESENCE_OFFLINE_TIMEOUT_SECONDS=1500
PRESENCE_PANEL_TOKEN=...
```

These SCF heartbeat settings are deprecated for current clients. New clients
must use the standalone `online-service` `/cloud-control.json` response and
connect to `heartbeat.wsUrl`.

The Worker also coalesces duplicate heartbeats server-side: it only updates `presence_sessions.last_seen_at_ms` when the previous stored heartbeat is at least roughly one configured heartbeat interval old, with a 60-second grace window. That protects D1 if older clients still send 240-second heartbeats.

The Worker treats its own `PRESENCE_HEARTBEAT_INTERVAL_SECONDS` and `PRESENCE_OFFLINE_TIMEOUT_SECONDS` variables as authoritative for legacy internal calls. Any SCF-forwarded values are compatibility-only and should not be used for new heartbeat routing.

## Local checks

```powershell
npm run check
npm run d1:migrate:local
npm run dev
```
