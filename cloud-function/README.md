# Cloud Function

This directory contains the Tencent SCF feedback/log relay and player patch
catalogue upload service used by the Android client.

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
- uploading player patch JARs, metadata and optional source files to the public
  `ModinMobileSTS/SlaytheAmethystPlayerModCatalogue` repository.

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
POST /api/mod-patches/upload
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
PATCH_CATALOGUE_OWNER=ModinMobileSTS
PATCH_CATALOGUE_REPO=SlaytheAmethystPlayerModCatalogue
PATCH_CATALOGUE_BRANCH=main
PATCH_UPLOAD_TRUST_PROXY_HOPS=0
PATCH_COMMITTER_NAME=Slay the Amethyst Catalogue
PATCH_COMMITTER_EMAIL=catalogue-upload@users.noreply.github.com
```

Notes:

- If `FEEDBACK_SHARED_SECRET` is set, the function requires `X-Feedback-Key`.
- GitHub operations are attributed to the GitHub App when `GITHUB_APP_*` variables are configured.
- `GITHUB_TOKEN` is kept only as a compatibility fallback when App auth is not configured.
- Diagnostics are stored as Release assets; player patch uploads use Git commits
  in their separate catalogue repository.
- `GITHUB_DIAGNOSTICS_REPO` defaults to `SlayTheDiagnostics`.
- `GITHUB_NOTIFICATION_STATE_REPO` defaults to the diagnostics repository and stores one small JSON asset per issue for mail state tracking.
- The GitHub App installation should cover both the issue repository and `SlayTheDiagnostics`.
- The GitHub App needs at least `Issues: Read and write` on `SlayTheAmethystModded` and `Contents: Read and write` on `SlayTheDiagnostics`.
- Patch uploads also require that installation to cover the catalogue repository
  with `Contents: Read and write`. The upload route requires App auth even when
  the feedback routes use their legacy `GITHUB_TOKEN` fallback.
- `GITHUB_APP_PRIVATE_KEY` may be provided either as a real multiline PEM or with `\n` escapes.
- If bundle persistence fails, the issue is still created and the failure reason is appended in the relay section.
- If SMTP is not configured, issues are still created and notification state is still stored, but no email is sent.
- `POST /github/webhook` must be configured as the GitHub App or repository webhook URL, and `GITHUB_WEBHOOK_SECRET` must match the webhook secret configured in GitHub.
- The webhook must subscribe to both `issues` and `issue_comment` events if you want close and comment emails to be sent.

## Player Patch Uploads

`POST /api/mod-patches/upload` accepts `multipart/form-data`. The server writes
only to the configured catalogue repository and branch; callers cannot select
another repository or an arbitrary Git path.

Uploads are anonymous: no login, bearer token, account, or `X-Feedback-Key` is
required. Author name and email in metadata are claims supplied by the uploader,
not verified identity. Any anonymous uploader can create a patch or replace an
existing patch at the same catalogue path, including changing its metadata, JAR,
or email claim (the author name is part of the directory path).
Do not treat the displayed name/email as proof of ownership. This intentionally
allows destructive overwrites; there is no per-author ownership or recovery
mechanism. Git history retains earlier commits, but the active index points at
the latest version.

Form parts:

| Part | Required | Value |
| --- | --- | --- |
| `metadata` | yes | JSON string, up to 64 KiB; schema version 1 shown below |
| `consent` | yes | JSON string: `{"publicEmail":true,"redistribution":true}` |
| `jar` | yes | Original JAR bytes, up to 25 MiB |
| `source` | no | ZIP up to 10 MiB, paths relative to `src/`; at most 256 files and 25 MiB expanded |

Example `metadata`:

```json
{
  "schemaVersion": 1,
  "name": "Example Patch",
  "description": "Explains the fix and the symptom it addresses.",
  "version": "1.0.0",
  "author": {"name": "Example Author", "email": "author@example.com"},
  "target": {
    "modId": "basemod",
    "workshopId": "1605833019",
    "versions": ["5.56.0"]
  },
  "jar": "ExamplePatch.jar"
}
```

`target.workshopId` is optional: omit it for local/non-Workshop mods (do not send
`null`, an empty string or `"0"`). When present it must be a positive decimal
string of at most 20 digits. Patches are associated by `target.modId`; missing
Workshop IDs may coexist with a known ID, but two different supplied IDs for
the same modId are rejected. The catalogue repository's validator/index generator
must support this optional field before deploying the relay change.

`target.modId` is case-sensitive, versions are exact strings, and `["*"]` means
all versions. `example` is an optional boolean, defaulting to false. Names and
paths must be trimmed Unicode NFC and satisfy the catalogue's filename rules.
`metadata.jar` determines the stored filename; the multipart filename is ignored.
The complete upload contract and index schema are in the
[catalogue documentation](https://github.com/ModinMobileSTS/SlaytheAmethystPlayerModCatalogue/blob/main/docs/catalogue.md).

Before sending consent, the client must explain that the email is public in the
metadata, generated index and permanent Git history, and that the author must
have permission to redistribute the JAR and any submitted source. Consent must
result from the player's explicit choice.

```sh
curl --fail-with-body "$SCF_BASE_URL/api/mod-patches/upload" \
  --form-string "metadata=$(cat metadata.json)" \
  --form-string 'consent={"publicEmail":true,"redistribution":true}' \
  -F 'jar=@ExamplePatch.jar' \
  -F 'source=@source.zip'
```

The JAR must contain matching `ModTheSpire.json`, an exact target dependency and
at least one `.class` entry. If present, `agent-patch.json` must match too. Every
archive member is streamed to check CRC, decompression and size limits; duplicate
paths, traversal, encrypted entries and symlinks are rejected. JARs are limited
to 4096 entries and 512 MiB expanded. Java code is never executed by the relay;
these checks do not establish that a mod is safe or actually compatible.

Files are stored together under
`mods/<modid>/<patch name>-<author name>/metadata.json`, the selected JAR name and
optional `src/`. The same path overwrites the previous upload after checking
the existing author's exact name/email. Changing `jar` removes the previous JAR
in that same commit. Omitting `source` preserves existing source; providing it
replaces the entire `src/` tree, including removal of stale source files.
Unrelated patch files, other authors' patches and concurrent commits are preserved.

The Git Data API creates blobs/trees, then **one reachable upload commit** and a
non-forced branch update. Every successful request creates a commit, even if
all bytes are unchanged. The exact message is `Example Author <author@example.com>`;
Git `author` matches the self-reported metadata, while `committer` uses the configured service
identity. Ref races re-read HEAD and revalidate path/Workshop constraints, then
rebuild trees/commits, at most five attempts. Rejected attempts can leave
unreachable Git objects, but do not add partial commits to the branch. The branch
must already have an initial commit, and its rules must permit the App to push.
Do not use an Action's `GITHUB_TOKEN` for uploads: App pushes are needed to trigger
the catalogue's index generation Action.

Success is HTTP `201`:

```json
{
  "ok": true,
  "commitSha": "<upload commit SHA>",
  "commitUrl": "https://github.com/ModinMobileSTS/SlaytheAmethystPlayerModCatalogue/commit/<SHA>",
  "path": "mods/basemod/Example Patch-Example Author",
  "indexUrl": "https://raw.githubusercontent.com/ModinMobileSTS/SlaytheAmethystPlayerModCatalogue/main/index.json",
  "indexing": "pending",
  "download_url": "https://raw.githubusercontent.com/ModinMobileSTS/SlaytheAmethystPlayerModCatalogue/<SHA>/mods/basemod/Example%20Patch-Example%20Author/ExamplePatch.jar",
  "size": 1703,
  "sha256": "<JAR SHA-256>",
  "patchModId": "example_patch"
}
```

`201` means the upload commit is on the branch. The Action still needs to update
the **single root `index.json`** and README; the client must distinguish upload
success from catalogue visibility and handle Action failures or Raw CDN lag.
There are no per-mod index requests. `download_url` is pinned to the upload
commit so it remains usable independently of indexing and later overwrites.

Errors return `{"ok":false,"error":"...","message":"..."}`. Statuses: `400`
invalid upload, `409` path or Workshop conflict/exhausted ref retries, `413` size limit, `429` quota (`Retry-After:
60`), `502` GitHub failure, and `503` upload configuration/App auth missing.
Treat an interrupted response as an uncertain result: check the repository
before re-uploading, because replay intentionally creates another commit.

Each instance permits two uploads in progress and five requests per source IP
per minute, checked before multipart buffering. By default, the source address
is the immediate socket peer; configure `PATCH_UPLOAD_TRUST_PROXY_HOPS` only to
the exact number of trusted proxy hops that overwrite/append `X-Forwarded-For`.
Never trust a client-controlled forwarding header directly. Configure shared
API gateway/WAF rate and request-size limits as well: per-instance counters do
not provide a quota across SCF instances. Anonymous uploads remain open to abuse;
the gateway/WAF should enforce global quotas and bot controls. Source uploads can require many GitHub
requests; configure the SCF/gateway timeout and body-size limit for the advertised
upload sizes, or publish lower limits if the gateway cannot accept them. Existing
feedback endpoint limits remain controlled by `BUNDLE_MAX_BYTES`.

The Android upload UI is not part of this server change.

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

Checks (Node 20+, Git installed for local Git Data API integration tests):

```sh
npm ci
npm test
npm run check
npm run check:app
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
