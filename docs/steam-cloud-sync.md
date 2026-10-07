# Steam Cloud synchronization

The launcher uses one `SteamCloudSyncRepository` / `SteamCloudSyncEngine` for the
home screen, settings, automatic synchronization, and forced overwrite. Remote
backup downloads share the repository boundary and verified transport. Steam
authentication and the protocol client are retained;
the former push/pull coordinators and diff/mirror/pull planners are removed.

## Synchronization algorithm

1. Recover unfinished local transactions under the short local-state lock.
2. Freeze local `saves/` and `preferences/` under the cross-process game lease.
   Network work uses that copy, never live files that the game can change.
3. Fetch and validate the remote listing. Compare SHA-1/size remotely and
   SHA-256/size locally against the last **verified agreement**, not timestamps.
   First-sync divergent files and simultaneous conflicting edits require an
   explicit local/cloud choice. Disjoint changes merge.
4. Stage and hash-check downloads. Recheck the remote identity before writes.
5. Persist expected remote contents **before** starting an upload/delete batch.
   Always verify the resulting listing, including after successful force uploads
   and after a lost completion response. An uncertain write is not replayed.
6. Recheck the remote listing and, for live replacements, the local snapshot.
   Commit files, manifest, verified baseline, account profile, and optional save
   mode through one durable local file transaction. Network calls are outside
   the live-save and local-state locks. Pure uploads can finish while a newly
   launched game owns the live lease.

Automatic local-to-cloud deletion propagation is restricted to saves. Explicit local-wins
mirrors may also delete preferences. Blacklisted paths are excluded from sync,
and mode/profile replacement preserves their live values. Symbolic links and
ambiguous or incomplete managed remote paths fail closed.

## Recovery and storage

### Steam login availability

`ClientServerUnavailable` for `EMsg=5514 job=-1 serverType=3` means the CM
could not route the session login request; it is not a cloud-file operation or
evidence of invalid credentials. Login now tries at most three distinct CM
WebSocket endpoints, with 500 ms / 1 s backoff between retryable failures. A
service-unavailable login result (`EResult=20`) is also retryable. Credential
rejections, rate limits and caller cancellation do not trigger login failover.
Late callbacks from replaced sockets cannot invalidate the replacement session.
An exhausted server-unavailable login is reported as a transient connection
failure, without invalidating saved credentials. These new retries wrap login
only; they do not add cloud upload/delete/batch replay.

Offline `:steam-protocol:test` coverage uses fake WebSockets for node failover,
bounded exhaustion, credential/rate-limit rejection, cancellation, and stale
callbacks; no Steam account or network is required.

### Local recovery

Relative to the app's storage root, `steam-cloud/` contains:

| Path | Purpose |
| --- | --- |
| `control-v2.json` | Uncached, separately locked controls migrated from existing preferences |
| `sync-baseline.json`, `manifest.json` | Existing compatible metadata locations |
| `push-summary.txt`, `pull-summary.txt` | Best-effort operation summaries for settings and diagnostics |
| `remote-pending-v2.json` | Expected remote result for an unfinished write |
| `uncertain-remote-*.json` | Previous uncertain records retained after explicit resolution |
| `transactions-v2/<id>/` | Write-ahead journal, copies of originals, and prepared replacements |
| `history-v2/<id>/` | Original local data retained after destructive sync, with target mapping in `journal.json` |
| `frozen-v2/`, `work-v2/` | Ephemeral snapshots and download staging; never authoritative recovery data |

Uncommitted local transactions roll back idempotently; committed transactions
remain committed. Recovery runs at launcher startup, before game launch, and
before cloud/profile operations. Missing recovery data blocks destructive work
and preserves the journal. Mode rollback restores only mode-related JSON keys,
so a newer disable toggle is not undone. Old credentials, profiles, backups,
and account-scoped baselines are not discarded during migration.
Retained history and uncertain records are not automatically pruned; they remain
available for manual inspection/restoration. Failed operations can leave ephemeral
workspaces after process death, but these are not used to reconstruct saves.

If the remote result matches a pending ordinary write, recover its verified
baseline without re-uploading. Otherwise stop and require an explicit choice.
An unfinished independent-profile overwrite additionally requires its local
installation to be verified; old live cloud files cannot be silently uploaded
over the newly committed independent profile.

The service uses operation UUIDs and monotonic per-operation sequences. Callback
and broadcast are deliveries of the same event and share one UI handler and
deduplication gate. The latest snapshot is persisted in the app's internal
files directory as `steam-cloud-service-state-v2.json` for reattachment. The
reattaching UI subscribes before reading the snapshot, so a racing completion
cannot be lost between those two steps. Snapshots are scoped to the Steam account.
A dead worker becomes an interrupted state, not a replayed destructive intent.

## Game-page status card

`SteamCloudStatusCard.kt` replaces the former overview card, status sheet,
conflict dialogs, and Compose-owned retry timers. It renders the shared
ViewModel state only; hidden pager pages never start retries. Rechecking is an
explicit action and still uses the repository's automatic merge/recovery path.

The home card and sheet follow the selected **B** Web prototype in
`opendesign/mockups/cloud-save/`: a 20dp outlined home card with icon, name, status
and chevron, no standalone actions, and an automatically expanded local/Steam
relationship and stage/file-count summary while working. The icon-free sheet
header has a close action; status/hint, endpoint diagram and stage summary form
the body. Only the endpoint connection animates: the separate lower determinate
or indeterminate progress rail is removed from both card and sheet.
Technical details are collapsed by default and retain timestamps, raw errors,
warnings, paths and recovery evidence. The body scrolls independently of the
bottom action area. Content height and action changes animate over 420ms with
the prototype's easing; Compose respects the Android animator duration scale.

The removed presentation states do not remove engine safety: completed warnings
present as Synced (warnings remain in details), deferred work presents as Not
checked (lease/defer explanation in details), and uncertain writes present as
Save conflict (recovery explanation in details). No fake file counts are shown.
Local/cloud overwrite and skip-sync confirmations retain their scope warnings.

The **Background sync** button requests **immediate game launch with cloud upload continuing
in the foreground service**. It is visible for not-checked, checking, transfer,
cancelling, and cancelled states (disabled during cleanup), never for conflict,
connection failure, recovery, or directly launchable states. It starts/reuses the
existing operation and dismisses the sheet only when accepted. This explicit
launch request does not depend on the automatic-launch preference. It launches
with the current local saves without waiting for cloud checking, downloading,
uploading, or a network-mode prompt. A cloud service start rejection does not gate
this local launch. No forced local-wins resolution is implied by this button.

The launch intent is recorded before starting/reusing the worker. Merge operations
that would change live saves (including cloud deletions) defer before downloads,
before a mixed remote write batch, and again under the control lock at local commit.
The cross-process game lease remains the last guard against racing live writes.
Deferred operations are rechecked from current contents after game return, not
blindly replayed. If the game obtains its lease before a new worker can freeze
local saves, that worker also defers until return; it never copies live game writes
without the lease. Pure uploads from an existing frozen snapshot continue normally.
Cloud conflicts/failures discovered after launch are reported without relaunching
the game or replacing local saves. This choice can start with older local progress.

Automatic-launch readiness (distinct from the explicit immediate button) still
requires both the worker's readiness flag and an inspected conflict-free plan
with no remote-to-local changes. Progress snapshots retain the plan for service
reattachment. The engine uploads frozen copies, never newer live game
writes, and commits their verified baseline without reacquiring the live lease.
The JVM acquires the cross-process game lease before reading saves. Unavailable
states link to save settings or Steam login; the card remains clickable during sync.

Progress callbacks/broadcasts are queued on the UI side. Duplicate and obsolete
operation/sequence deliveries are rejected before I/O. Control-file locking and
credential/KeyStore reads run on `Dispatchers.IO`, serially; the operation binding
is rechecked on the main thread after validation so an old result cannot replace
a newer request. Clearing availability invalidates pending attachment snapshots.
Intermediate progress publication is limited to once per 200 ms, with immediate
phase changes and final file counts; terminal events are never throttled. Identical
notification text is not resent, and each progress event updates UI state once.
Download verification hashes only the newly downloaded file, rather than scanning
all earlier downloads again. The final staging snapshot, content verification,
transaction fsyncs, backups and recovery checks remain intact.

The underlying state model distinguishes unavailable/login/independent/disabled
states from checking, transfer, remote verification, local installation,
completion, conflict, deferred live-save use, cancellation, and uncertain-write
recovery. The B presentation folds the last two exceptional outcomes as described
above without changing their operation guards.
File counters describe the **current stage**, not overall sync completion.
Warnings survive completion and service reattachment. Downloads and uploads
both offer cancellation; a cancellation request stays in progress until the
worker has unwound, and a verified success racing cancellation remains success.

Explicit local/cloud choices disclose that they mirror **all managed files**,
not just listed conflicts, while preserving excluded paths. When a preview is
available, its local content fingerprint and remote manifest identity travel
with the confirmation into the service; changed contents stop execution before
recovery or writes. Uncertain-write recovery without a preview requires the
same whole-scope confirmation, rather than presenting invented file counts.

Settings changes (including disable toggles and account changes) refresh the
card when returning to the game page. Operation IDs and sequences continue to
deduplicate broadcasts/callbacks and prevent older snapshots from replacing
newer requests.

## Limits and verification

Steam's currently used application RPCs do not expose a conditional manifest
version precondition. The pre/post checks detect many concurrent changes but
**cannot eliminate the final multi-device write race**. No distributed-lock or
power-loss guarantees for unsupported filesystems are implied; durability uses
file and directory fsync and fails when the filesystem cannot support it.

Offline tests exercise planning, interrupted apply/rollback, cancellation,
unknown remote outcomes, force-upload verification, frozen background uploads,
concurrent local/remote edits, profile installation, and duplicate/stale events.
These tests use a fake transport and do not access real Steam saves:

```sh
./gradlew :app:testDebugUnitTest \
  --tests 'io.stamethyst.backend.steamcloud.*' \
  --tests 'io.stamethyst.ui.main.SteamCloud*' \
  --tests 'io.stamethyst.ui.LauncherContentSteamCloudRefreshTest' \
  :app:compileDebugAndroidTestKotlin --console=plain
```

Architecture references (ideas only; no copied implementation or added dependency):

- [Ludusavi cloud synchronization](https://github.com/mtkennerly/ludusavi/blob/master/src/cloud.rs)
- [rclone bisync safety and recovery](https://rclone.org/bisync/)
