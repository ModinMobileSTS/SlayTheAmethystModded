# Release Automation Guide

This directory documents the GitHub Actions release system used by this repository.

## Scope

- Build signed Android release APK in GitHub Actions.
- Publish APK to GitHub Releases when pushing `v*` tags.
- Pull private build dependencies from a private GitHub release asset bundle.

Workflow file:
- `.github/workflows/release.yml`

## 1. Prepare Signing Key

Generate upload keystore:

```powershell
keytool -genkeypair -v `
  -keystore .\signing\stamethyst-upload.jks `
  -alias upload `
  -keyalg RSA -keysize 2048 -validity 10000
```

Export public certificate (safe to share):

```powershell
keytool -export -rfc `
  -keystore .\signing\stamethyst-upload.jks `
  -alias upload `
  -file .\signing\upload_certificate.pem
```

Convert keystore to Base64 for GitHub secret:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes(".\signing\stamethyst-upload.jks")) `
  | Set-Content -NoNewline .\signing\keystore.base64.txt
```

Notes:
- Never commit `.jks` or Base64 secret files.
- For PKCS12 keystores, `RELEASE_KEY_PASSWORD` is commonly the same as `RELEASE_STORE_PASSWORD`.

## 2. Prepare Private Build Dependency Bundle

The CI runner needs these files packaged as `build-deps.tar.gz`:

- `build-deps/steamapps/common/SlayTheSpire/desktop-1.0.jar`
- `build-deps/runtime-pack/jre8-pojav.zip`
- `build-deps/runtime-pack/gdx_video_natives/libgdx-video-desktoparm64.so`
- `build-deps/runtime-pack/gdx_video_natives/libgdx-video-desktoparm.so`

Compute SHA256:

```powershell
Get-FileHash .\build-deps.tar.gz -Algorithm SHA256
```

Save checksum text in release format:

```text
<sha256>  build-deps.tar.gz
```

## 3. Upload Dependency Bundle to Private GitHub Release

Recommended: separate private repo for dependency artifacts (example: `ModinMobileSTS/SlayTheAmethystModdedDependence`).

Create dependency release (example tag `deps-20260305`) and upload:

```bash
gh release create deps-20260305 build-deps.tar.gz build-deps.tar.sha256.txt --repo ModinMobileSTS/SlayTheAmethystModdedDependence --title deps-20260305
```

## 4. Configure GitHub Environment

Environment name used by workflow:
- `release-signing`

Environment variables:
- `BUILD_DEPS_RELEASE_TAG` (for example `deps-20260305`)
- `BUILD_DEPS_SHA256` (SHA256 of `build-deps.tar.gz`)
- `BUILD_DEPS_ASSET_NAME` (optional, default `build-deps.tar.gz`)
- `BUILD_DEPS_REPO` (optional, default current repo, format `owner/repo`)

Environment secrets:
- `ANDROID_KEYSTORE_BASE64`
- `RELEASE_STORE_PASSWORD`
- `RELEASE_KEY_ALIAS`
- `RELEASE_KEY_PASSWORD`
- `BUILD_DEPS_GH_TOKEN` (optional for same-repo assets; required for cross-repo private asset download)

Token permissions:
- For `BUILD_DEPS_GH_TOKEN`, minimum permission is target dependency repo `Contents: Read`.

## 5. Trigger a Release

Recommended local release flow:

```bash
bash scripts/prepare-release.sh
```

```bat
scripts\prepare-release.bat
```

What the script does:
- Read `application.version.name` from `gradle.properties`.
- Ask whether to release `v<version>`.
- Run a local preflight before tagging: `:app:lintDebug` and `:app:assembleRelease`.
- Create `docs/release/note/v<version>.md` with `新特性` and `修复` sections if it does not already exist.
- Wait for you to finish editing the release note.
- Commit only the release note and `gradle.properties` when it has local changes.
- Create and push annotated tag `v<version>`.
- When the GitHub Actions release workflow runs, that same `docs/release/note/v<version>.md` file is used as the GitHub Release body.

Notes:
- The script intentionally does not include unrelated working tree changes in the release commit.
- The local preflight uses the same signing inputs as local release builds. If passwords are not already present in environment variables, the script will prompt for them.
- On Windows, run it from Git Bash or another Bash environment.
- On Windows Command Prompt or PowerShell, you can use `scripts\prepare-release.bat`.
- If you explicitly want to skip the preflight, use `scripts\prepare-release.bat -SkipLocalCheck` or `bash scripts/prepare-release.sh --skip-local-check`.

Tag-based release (publishes GitHub Release automatically):

```bash
bash scripts/prepare-release.sh
```

Behavior:
- Workflow builds signed APK.
- Workflow uploads artifact to Actions run.
- Workflow creates or updates GitHub Release with tag `v*`, uploads APK, and uses `docs/release/note/v<version>.md` as the release notes when the file exists.

Manual run:
- `workflow_dispatch` builds APK and uploads run artifact.
- It does not publish GitHub Release unless run is tag-triggered.

## 6. Verify Output

After successful run:
- Actions artifact contains release APK.
- GitHub Release `v*` contains APK asset.

Optional local verification:

```bash
apksigner verify --verbose --print-certs SlayTheAmethyst-dev-<version>.APK
```

## Troubleshooting

`AAPT: resource mipmap/ic_launcher_amethyst not found`
- Cause: icon files referenced by manifest are missing in committed `mipmap-*` resources.
- Fix: commit `ic_launcher_amethyst*.png` files and rerun.

`KeytoolException ... BadPaddingException ... Failed to read key`
- Cause: keystore/password/alias mismatch in secrets.
- Fix:
  - refresh `ANDROID_KEYSTORE_BASE64` from current `.jks`
  - verify `RELEASE_KEY_ALIAS`
  - verify `RELEASE_STORE_PASSWORD`
  - verify `RELEASE_KEY_PASSWORD`

`Missing BUILD_DEPS_*`
- Cause: environment variables not configured.
- Fix: set required variables under `release-signing` environment.

`gh release download ... 404`
- Cause: dependency repo/tag/asset mismatch or token lacks access.
- Fix:
  - verify `BUILD_DEPS_REPO`, `BUILD_DEPS_RELEASE_TAG`, `BUILD_DEPS_ASSET_NAME`
  - ensure token has `Contents: Read` on dependency repo
