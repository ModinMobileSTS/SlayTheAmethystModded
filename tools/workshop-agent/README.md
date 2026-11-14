# Workshop agent CLI

Desktop companion for `.opencode/plugins/sts-workshop.ts`, using JDK 21. It exposes
search, details and original-file downloads for Slay the Spire (default app ID
`646570`) or an explicitly selected Steam app.

## Architecture

- `WorkshopBrowse.kt` follows the launcher's `WorkshopBrowseParser`: legacy cards,
  `window.SSR.renderContext = JSON.parse(...)`, and `valve-ssr-data` JSON scripts.
  Requests mirror `WorkshopService.searchWorkshop` parameters. Empty recognized
  search pages are distinct from unrecognized/login/network-interception pages.
  This CLI uses public browsing only, with no authenticated CM search fallback.
- `WorkshopAgent.kt` uses `GetPublishedFileDetails` for metadata, verifies item/app
  identity, then delegates download to `WorkshopDownloadEngine.createDefault`.
  The shared engine owns direct download, anonymous CM, public CDN fallback,
  manifest/chunk assembly, integrity checks and logs. The CLI additionally checks
  direct-download length against metadata and hashes completed artifacts.
- The CLI-only `io.stamethyst.backend.workshop.AndroidZstdBridge` implements the
  exact reflection ABI expected by `workshop-core` using desktop `zstd-jni`.
  The launcher's Android bridge is a different class in a separate application;
  this module is never an Android dependency.
- The OpenCode adapter launches the executable with argv and JSON stdin, without
  shell interpolation. It owns deadlines, cancellation, output path confinement,
  fresh per-download directories, and saved results. Plugin unload stops children.

## Build and use

From repository root:

```sh
mkdir -p agent-tmp
flock agent-tmp/ai-agent-gradle.lock ./gradlew --offline :tools:workshop-agent:installDist --console=plain --max-workers=2
```

The executable is `tools/workshop-agent/build/install/workshop-agent/bin/workshop-agent`.
Run with `JAVA_HOME` pointing to JDK 21 if the system default is older.
Rebuild after source/library changes; the plugin does not rebuild at tool invocation.

One JSON request is read from stdin until EOF. Example requests:

```json
{"action":"search","query":"BaseMod","page":1,"pageSize":20,"language":"schinese"}
```

```json
{"action":"details","publishedFileId":"1605833019"}
```

```json
{"action":"download","publishedFileId":"1605833019","outputDir":"agent-tmp/my-basemod","threads":8}
```

For direct CLI use, the caller supplies an empty/new output directory and its own
deadline. The OpenCode adapter supplies both automatically. Protocol stdout is
newline-delimited JSON:

```json
{"type":"progress","progress":{"state":"Resolving"}}
{"type":"result","success":true,"result":{"items":[],"page":1,"hasNextPage":false}}
```

Failure emits `{"type":"result","success":false,"error":"..."}` and exits 1;
success exits 0. The adapter requires both exit 0 and a complete successful result.
The full CLI result is bounded to 2 MB by the adapter. Read results are concise;
download results shown to the model include at most 100 artifacts, with the full
list saved in `result.json`.

Downloads are original files (not guaranteed to be a single JAR). Collection and
wrong-app requests fail before downloading. Dependencies are not downloaded
recursively, API `children` may be incomplete, and no files are imported/patched
or installed on a device. Partial download directories remain available on failure.

## Offline verification

```sh
node --test .opencode/tests/sts-workshop.test.mjs
npm run typecheck --prefix .opencode
flock agent-tmp/ai-agent-gradle.lock ./gradlew --offline :tools:workshop-agent:test :workshop-core:test --console=plain --max-workers=2
```

The plugin tests use a fake CLI. JVM tests use local MockWebServer fixtures for
browse/details and a real-engine direct download; they also check failure streams,
metadata size mismatch, preexisting output preservation and desktop Zstd JNI.
These checks do not prove availability of Steam's live browse/CM/CDN services.
