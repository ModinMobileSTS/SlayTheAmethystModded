# Production Task runtime

## Android ownership and storage

`backend/tasks/TaskRuntime.kt` is the release-available facade. `StsApplication` starts it only in
the default process. Initialization and recovery run on IO, never block Application main-thread
startup. Callers await readiness and use execute/query/observe/control APIs, not a mutable Manager.
Submitted operations belong to the host scope, not to the Activity awaiting the result.
Filesystem mutation requests are serialized. RequestPause/RequestCancel can reach active work;
current file tasks check controls before starting and finish their commit section without interruption.
No foreground service, reboot receiver, periodic wakeup, or promise of background survival exists.
Process death is reconciled next time this host starts. Existing Workshop/Download/Delta are unchanged.

`RuntimePaths.taskStorageRoot` requires getExternalFilesDir(null), with no internal fallback:

```
<externalFilesDir>/io-tasks/production/manager/{id}.task
<externalFilesDir>/io-tasks/production/manager/work/{id}/
<externalFilesDir>/io-tasks/production/history/{id}.task
<externalFilesDir>/io-tasks/production/history/work/{id}/{candidate,backup}
```

Verification uses the same constructor, registration, serialization, recovery and layout beneath
`io-tasks/verification/<UUID>/`. This factory and boundary fault injection reject release builds.
Initialization errors block requests and are logged; corrupt/unknown records are not discarded.
A failed production instance currently requires process restart after the underlying issue is fixed;
there is no live reinitialization or removable-storage rebinding mechanism.

## File I/O contract

`FileIoTasks` supports mkdir (one directory, existing parent), copy (regular file), write (complete
regular-file source, preserved), replace (existing regular file), move (file/tree), delete (quarantine)
and undo retained history via a new Task. Read-only scan and SHA-256 are ordinary FileInspection APIs.
Definitions use absolute normalized paths. Links, linked ancestors, overlapping operands, shared
workspace/history operands and unsupported special files are rejected.

Writes build and sync a candidate before durable rename intent. Replacement has two recoverable
renames, not a single atomic old-to-new swap: readers may temporarily observe no target. Recovery
reconciles each rename by file identity plus contents. Undo persists the committed prefix before
reversing it; subsequent process loss resumes that same prefix. External content/identity changes or
occupied destinations fail closed. Directories include descendant identities/content in checks.
These are not metadata-preserving archive/backup APIs: permissions and timestamps are not restored.

All mutations require host-exclusive operand namespaces. Checks do not prevent TOCTOU from unrelated
writers; legacy code must not concurrently modify the same operands. File keys must be available.
Rename uses ATOMIC_MOVE only, never copy/delete fallback. History and mutation targets must support
same-filesystem rename; EXDEV propagates with recoverable intent retained. FileStore prechecks are
not used because Android app UIDs can receive SecurityException. Large copies restart from the
beginning after interruption; bounded buffering, no mid-copy pause/resume guarantee.

History outlives Plan collection. `undo(historyId)` consumes it without requiring the reclaimed
Manager record; undo tasks do not support undo-of-undo. Re-execution after revert requires a new Task.
Cleanup rolls back mutations; reclaim does not destroy history. No automatic retention/purge exists:
quarantine, backups and reversed candidates retain disk usage. Permanent destruction is intentionally
not exposed as reversible deletion. This is process-loss protection, not power-loss/directory-fsync
durability or multi-ROM certification.

## Test-After evidence, 2026-11-14

- `./gradlew --offline :io-task-manager:test :app:assembleDebug :app:assembleDebugAndroidTest
  --console=plain`: PASS, JVM 30 tests (10 new file I/O tests), both debug APKs built.
- User-approved M2007J3SY: APKs updated using workspace local_adb `install --fastdeploy -r`.
  Four targeted classes: TaskRuntimeInstrumentedTest, FileIoInstrumentedTest,
  DurableContractInstrumentedTest, DurableRecoveryInstrumentedTest: `OK (37 tests)`, 12.42 s.
  No uninstall or data clearing. UUID directories only; kill targets the isolated debug service.
- Host tests cover external path/layout, no internal fallback artifacts, owner exclusion, serialized
  submission, corrupt-record startup failure and reopen reconciliation. Real kill tests execute and
  recover through the production host, injecting death after business commit/undo before Manager
  completion. JVM tests synthesize all replacement commit/undo prefixes, including partial rollback.
- External suite initially exposed unsupported symlink creation. External artifact/collection tests
  remain external; two explicit internal-storage symlink tests separately preserve link assertions.
  Initial FileStore SecurityException from earlier internal run was fixed without a weaker fallback.
- Logs: `agent-tmp/task-runtime-build.log`, `agent-tmp/task-runtime-device.log`.
- Not verified: release APK packaging/runtime, power loss, disk-full/hardware faults, real media
  removal, automatic background wakeup, multiple ROMs or actual legacy-business migration.
  External-unavailable branch is explicit but has no fault-injected test yet.
