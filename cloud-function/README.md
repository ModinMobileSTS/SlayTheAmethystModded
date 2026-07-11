# Cloud Function

This directory contains the Tencent SCF feedback/log relay used by the Android
client.

Presence heartbeats, online count, and the operator panel have moved out of this
cloud function. The standalone implementation now lives in
`../online-service` and uses:

```text
Android app -> Fastify WebSocket -> SQLite3
```

## Current Scope

SCF heartbeat/presence support is deprecated. Do not configure SCF as a game
heartbeat target, and do not route client heartbeats through SCF to Cloudflare
Worker storage.

SCF is only responsible for:

- uploading feedback/log diagnostic bundles;
- creating and updating feedback issues;
- fetching feedback issue lists or state used by the launcher;
- relaying GitHub webhook events for feedback mail notifications.

New clients should read cloud-control from `online-service` and report game or
launcher presence to `heartbeat.wsUrl`.

## Endpoint

The Android client submits feedback to:

```text
POST /api/sts-feedback
```

The local relay implementation also accepts `POST /` as a compatibility route,
but the app does not rely on it.

Feedback issue information endpoints:

```text
GET  /api/feedback-issues/browse
POST /api/feedback-issues/message
POST /api/feedback-issues/state
POST /github/webhook
```

SCF exposes no heartbeat/presence endpoint.

`GET /cloud-control.json` remains as a compatibility pointer only. It no longer
serves or owns presence heartbeat settings. If `CLOUD_CONTROL_CONFIG_URL` is
configured, SCF redirects to the `online-service` `/cloud-control.json`; if it
is not configured, SCF returns `410 presence_moved`.

## What It Does

1. Receives the Android client's `multipart/form-data` feedback request.
2. Validates the optional `X-Feedback-Key`.
3. Uploads the diagnostic bundle and metadata JSON to GitHub Release assets in a dedicated diagnostics repository.
4. Trims noisy diagnostic sections from feature-request issue bodies.
5. Removes public email addresses from issue bodies and replaces them with a private mail-notification status section.
6. Creates a GitHub issue in the target repository.
7. If the user opted in, stores private mail notification state in Release assets and sends a styled creation email.
8. Receives `POST /github/webhook` issue events and sends a progress email when the target issue is commented or closed.
9. Returns `issueNumber` and `issueUrl` to the client.

## Required Environment Variables

```text
GITHUB_OWNER
GITHUB_REPO
GITHUB_APP_ID
GITHUB_APP_INSTALLATION_ID
GITHUB_APP_PRIVATE_KEY
```

## Optional Environment Variables

```text
PORT=9000
FEEDBACK_SHARED_SECRET=
GITHUB_WEBHOOK_SECRET=
GITHUB_TOKEN=
GITHUB_ISSUE_LABELS=feedback,client:android
GITHUB_DIAGNOSTICS_OWNER=
GITHUB_DIAGNOSTICS_REPO=SlayTheDiagnostics
GITHUB_DIAGNOSTICS_BRANCH=main
GITHUB_DIAGNOSTICS_RELEASE_PREFIX=feedback
GITHUB_NOTIFICATION_STATE_OWNER=
GITHUB_NOTIFICATION_STATE_REPO=SlayTheDiagnostics
GITHUB_NOTIFICATION_STATE_BRANCH=main
GITHUB_NOTIFICATION_STATE_RELEASE_PREFIX=feedback-mail-state
SMTP_HOST=
SMTP_PORT=465
SMTP_SECURE=true
SMTP_USERNAME=
SMTP_PASSWORD=
SMTP_FROM=
SMTP_REPLY_TO=
BUNDLE_MAX_BYTES=26214400
CLOUD_CONTROL_CONFIG_URL=https://presence.example.com/cloud-control.json
```

Notes:

- If `FEEDBACK_SHARED_SECRET` is set, the function requires `X-Feedback-Key`.
- GitHub operations are attributed to the GitHub App when `GITHUB_APP_*` variables are configured.
- `GITHUB_TOKEN` is kept only as a compatibility fallback when App auth is not configured.
- The relay stores diagnostics as Release assets, not Git commits, so repository history does not grow with every upload.
- `GITHUB_DIAGNOSTICS_REPO` defaults to `SlayTheDiagnostics`.
- `GITHUB_NOTIFICATION_STATE_REPO` defaults to the diagnostics repository and stores one small JSON asset per issue for mail state tracking.
- The GitHub App installation should cover both the issue repository and `SlayTheDiagnostics`.
- The GitHub App needs at least `Issues: Read and write` on `SlayTheAmethystModded` and `Contents: Read and write` on `SlayTheDiagnostics`.
- `GITHUB_APP_PRIVATE_KEY` may be provided either as a real multiline PEM or with `\n` escapes.
- If bundle persistence fails, the issue is still created and the failure reason is appended in the relay section.
- If SMTP is not configured, issues are still created and notification state is still stored, but no email is sent.
- `POST /github/webhook` must be configured as the GitHub App or repository webhook URL, and `GITHUB_WEBHOOK_SECRET` must match the webhook secret configured in GitHub.
- The webhook must subscribe to both `issues` and `issue_comment` events if you want close and comment emails to be sent.

## Local Run

```powershell
npm install
$env:GITHUB_OWNER="ModinMobileSTS"
$env:GITHUB_REPO="SlayTheAmethystModded"
$env:GITHUB_APP_ID="123456"
$env:GITHUB_APP_INSTALLATION_ID="78901234"
$env:GITHUB_APP_PRIVATE_KEY="-----BEGIN RSA PRIVATE KEY-----`n...`n-----END RSA PRIVATE KEY-----"
$env:GITHUB_WEBHOOK_SECRET="replace-me"
$env:GITHUB_DIAGNOSTICS_REPO="SlayTheDiagnostics"
$env:SMTP_HOST="smtp.example.com"
$env:SMTP_PORT="465"
$env:SMTP_SECURE="true"
$env:SMTP_USERNAME="mailer@example.com"
$env:SMTP_PASSWORD="replace-me"
$env:SMTP_FROM="SlayTheAmethyst <mailer@example.com>"
node app.js
```

Health check:

```text
GET /
GET /healthz
POST /github/webhook
```

## Packaging For Tencent SCF

Deploy from a zip whose root directly contains `app.js`, `node_modules/`, and
`scf_bootstrap`.

On Windows, do not zip the working tree manually from Explorer if
`scf_bootstrap` has been checked out with `CRLF`; the custom runtime may fail to
start with an error like `[./scf_bootstrap] no such file or directory`.

Use the repository packaging script instead:

```powershell
.\scripts\package-cloud-function.ps1
```

It writes `artifacts/cloud-function-scf.zip` and forces
`cloud-function/scf_bootstrap` to UTF-8 without BOM and `LF` line endings inside
the archive.
