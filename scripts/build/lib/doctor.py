"""SlayTheAmethyst build doctor: verify every dependency a local build needs."""

from __future__ import annotations

import json
import os
import re
import shutil
import subprocess
import sys
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field
from pathlib import Path

from .commands import repo_root

OK = "ok"
WARN = "warn"
FAIL = "fail"
INFO = "info"

SDK_PLATFORM = "android-36"
REQUIRED_CMAKE = "3.22.1"
MIN_JAVA_MAJOR = 17
MIN_PYTHON = (3, 10)
MIN_DEPENDENCY_BYTES = 1024 * 1024

DEFAULT_BUNDLE_URL = (
    "https://github.com/ModinMobileSTS/SlayTheAmethystModdedDependence/releases/download/"
    "deps-20260305/build-deps.tar.gz"
)
JRE8_URL = (
    "https://github.com/ModinMobileSTS/SlayTheAmethystModdedDependence/releases/download/"
    "pojav-jre8/jre8-pojav.zip"
)
SLING_BREAK_DEFAULT_REPOSITORY = "https://github.com/Apricityx/slingbreak"
SLING_BREAK_DEFAULT_REF = "master"
NETWORK_TIMEOUT = 15


@dataclass
class Finding:
    text: str
    hint: str = ""


@dataclass
class Check:
    label: str
    status: str
    detail: str = ""
    findings: list[Finding] = field(default_factory=list)


@dataclass
class Palette:
    enabled: bool
    ascii_only: bool

    def _wrap(self, code: str, text: str) -> str:
        if not self.enabled:
            return text
        return f"\033[{code}m{text}\033[0m"

    def ok(self, text: str) -> str:
        return self._wrap("32", text)

    def fail(self, text: str) -> str:
        return self._wrap("31", text)

    def warn(self, text: str) -> str:
        return self._wrap("33", text)

    def info(self, text: str) -> str:
        return self._wrap("2", text)

    def accent(self, text: str) -> str:
        return self._wrap("36", text)

    def strong(self, text: str) -> str:
        return self._wrap("1", text)

    def marker(self, status: str) -> str:
        if self.ascii_only:
            glyph = {OK: "[OK]", FAIL: "[X]", WARN: "[!]", INFO: "[-]"}[status]
        else:
            glyph = {OK: "[✓]", FAIL: "[✗]", WARN: "[!]", INFO: "[-]"}[status]
        color = {OK: self.ok, FAIL: self.fail, WARN: self.warn, INFO: self.info}[status]
        return color(glyph)


def make_palette(no_color: bool) -> Palette:
    enabled = not no_color and os.environ.get("NO_COLOR") is None
    if enabled and not sys.stdout.isatty():
        enabled = False
    encoding = (sys.stdout.encoding or "").lower()
    ascii_only = "utf" not in encoding
    return Palette(enabled=enabled, ascii_only=ascii_only)


def _run(command: list[str], timeout: int = 20) -> tuple[int, str]:
    try:
        completed = subprocess.run(
            command,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=timeout,
            check=False,
        )
        return completed.returncode, completed.stdout or ""
    except (OSError, subprocess.SubprocessError):
        return -1, ""


def _parse_local_properties() -> dict[str, str]:
    values: dict[str, str] = {}
    path = repo_root() / "local.properties"
    if not path.is_file():
        return values
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#") or "=" not in stripped:
            continue
        key, value = stripped.split("=", 1)
        values[key.strip()] = value.strip()
    return values


def _parse_gradle_properties() -> dict[str, str]:
    values: dict[str, str] = {}
    path = repo_root() / "gradle.properties"
    if not path.is_file():
        return values
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#") or "=" not in stripped:
            continue
        key, value = stripped.split("=", 1)
        values[key.strip()] = value.strip()
    return values


def _java_major(version_text: str) -> int | None:
    match = re.search(r'version "([^"]+)"', version_text)
    token = match.group(1) if match else version_text.strip().splitlines()[0] if version_text.strip() else ""
    if not token:
        return None
    parts = token.replace("_", ".").split(".")
    try:
        major = int(parts[0])
    except ValueError:
        return None
    if major == 1 and len(parts) > 1:
        try:
            return int(parts[1])
        except ValueError:
            return None
    return major


def _format_size(num_bytes: int) -> str:
    mib = num_bytes / (1024 * 1024)
    if mib >= 1024:
        return f"{mib / 1024:.2f} GiB"
    return f"{mib:.1f} MiB"


def _resolve_android_sdk() -> tuple[Path | None, str]:
    local = _parse_local_properties()
    candidates: list[tuple[str, str | None]] = [
        ("local.properties", local.get("sdk.dir")),
        ("ANDROID_HOME", os.environ.get("ANDROID_HOME")),
        ("ANDROID_SDK_ROOT", os.environ.get("ANDROID_SDK_ROOT")),
    ]
    home = Path.home()
    candidates += [
        ("~/android-sdk", str(home / "android-sdk")),
        ("~/Android/Sdk", str(home / "Android" / "Sdk")),
        ("~/Library/Android/sdk", str(home / "Library" / "Android" / "sdk")),
        ("%LOCALAPPDATA%\\Android\\Sdk", os.environ.get("LOCALAPPDATA", "") and str(Path(os.environ["LOCALAPPDATA"]) / "Android" / "Sdk")),
    ]

    for source, value in candidates:
        if not value:
            continue
        path = Path(value).expanduser()
        if path.is_dir():
            return path, source
    return None, ""


def _find_adb(sdk: Path | None) -> tuple[Path | None, str]:
    on_path = shutil.which("adb")
    if on_path:
        return Path(on_path), "PATH"
    if sdk is not None:
        for name in ("adb", "adb.exe"):
            candidate = sdk / "platform-tools" / name
            if candidate.is_file():
                return candidate, "Android SDK platform-tools"
    return None, ""


def check_java() -> Check:
    java_home = os.environ.get("JAVA_HOME", "").strip()
    binary = Path(java_home) / "bin" / "java" if java_home else None
    if binary is None or not binary.is_file():
        found = shutil.which("java")
        binary = Path(found) if found else None

    if binary is None:
        return Check(
            "Java (Gradle launcher)",
            FAIL,
            "not found",
            [Finding("No java executable on PATH and JAVA_HOME is unset.", "Install a JDK 17+ and set JAVA_HOME.")],
        )

    _, output = _run([str(binary), "-version"])
    major = _java_major(output)
    version = "unknown"
    match = re.search(r'version "([^"]+)"', output)
    if match:
        version = match.group(1)
    elif output.strip():
        version = output.strip().splitlines()[0]

    findings = [Finding(f"java: {binary}")]
    if major is None:
        return Check("Java (Gradle launcher)", WARN, version, findings + [Finding("Could not parse the java version.")])
    if major < MIN_JAVA_MAJOR:
        return Check(
            "Java (Gradle launcher)",
            FAIL,
            version,
            findings + [Finding(f"Gradle and AGP require JDK {MIN_JAVA_MAJOR} or newer.", f"Point JAVA_HOME at a JDK {MIN_JAVA_MAJOR}+.")],
        )
    source = "JAVA_HOME" if java_home else "PATH"
    return Check("Java (Gradle launcher)", OK, f"{version} via {source}", findings)


def check_android_sdk(sdk: Path | None, source: str) -> Check:
    if sdk is None:
        return Check(
            "Android SDK",
            FAIL,
            "not found",
            [Finding("No SDK from local.properties, ANDROID_HOME, or ANDROID_SDK_ROOT.", "Install the Android SDK and set sdk.dir in local.properties.")],
        )

    findings = [Finding(f"SDK: {sdk}")]
    failures: list[Finding] = []
    warnings: list[Finding] = []

    platform_dir = sdk / "platforms" / SDK_PLATFORM
    platform_ok = platform_dir.is_dir()
    if not platform_ok:
        failures.append(
            Finding(
                f"Missing platform {SDK_PLATFORM}.",
                f'sdkmanager "platforms;{SDK_PLATFORM}"',
            )
        )

    build_tools_dir = sdk / "build-tools"
    build_tools = []
    if build_tools_dir.is_dir():
        build_tools = sorted((p.name for p in build_tools_dir.iterdir() if p.is_dir()), reverse=True)
    build_tools_label = build_tools[0] if build_tools else "none"
    if not build_tools:
        warnings.append(Finding("No build-tools installed.", 'sdkmanager "build-tools;36.0.0"'))

    licenses_dir = sdk / "licenses"
    licenses = [p for p in licenses_dir.iterdir() if p.is_file()] if licenses_dir.is_dir() else []
    if not licenses:
        warnings.append(Finding("SDK licenses are not accepted.", "sdkmanager --licenses"))

    adb, adb_source = _find_adb(sdk)
    if adb is None:
        warnings.append(Finding("adb not found.", 'sdkmanager "platform-tools"'))

    detail = f"platform {SDK_PLATFORM if platform_ok else 'missing'}, build-tools {build_tools_label}"
    if source and source != "local.properties":
        detail += f" ({source})"

    if failures:
        return Check("Android SDK", FAIL, detail, findings + failures + warnings)
    if warnings:
        return Check("Android SDK", WARN, detail, findings + warnings)
    return Check("Android SDK", OK, detail, findings)


def check_ndk(sdk: Path | None) -> Check:
    if sdk is None:
        return Check("Android NDK", INFO, "skipped (no Android SDK)")
    ndk_dir = sdk / "ndk"
    versions = []
    if ndk_dir.is_dir():
        versions = sorted((p.name for p in ndk_dir.iterdir() if p.is_dir()), reverse=True)
    if not versions:
        return Check(
            "Android NDK",
            WARN,
            "not installed",
            [Finding("The native bridge in app/src/main/jni needs an NDK.", 'sdkmanager "ndk;27.0.12077973"')],
        )
    return Check("Android NDK", OK, versions[0] if len(versions) == 1 else f"{len(versions)} installed ({versions[0]})")


def check_cmake(sdk: Path | None) -> Check:
    if sdk is None:
        return Check("CMake", INFO, "skipped (no Android SDK)")
    path = sdk / "cmake" / REQUIRED_CMAKE
    if path.is_dir():
        return Check("CMake", OK, REQUIRED_CMAKE)
    return Check(
        "CMake",
        WARN,
        f"{REQUIRED_CMAKE} not installed",
        [Finding(f"app/build.gradle.kts pins CMake {REQUIRED_CMAKE}.", f'sdkmanager "cmake;{REQUIRED_CMAKE}"')],
    )


def check_python() -> Check:
    version = ".".join(str(part) for part in sys.version_info[:3])
    if sys.version_info >= MIN_PYTHON:
        return Check("Python", OK, version)
    minimum = ".".join(str(part) for part in MIN_PYTHON)
    return Check(
        "Python",
        WARN,
        version,
        [Finding(f"scripts/ tooling needs Python {minimum}+.", f"Upgrade Python to {minimum} or newer.")],
    )


BUILD_DEP_SPECS = [
    ("desktop.jar", "Slay the Spire desktop jar"),
    ("jre8-pojav.zip", "JRE8 runtime pack"),
]


def _missing_build_deps() -> list[str]:
    deps_dir = repo_root() / "build-deps"
    return [name for name, _ in BUILD_DEP_SPECS if not (deps_dir / name).is_file()]


def check_build_dependencies() -> Check:
    deps_dir = repo_root() / "build-deps"
    findings: list[Finding] = []
    missing: list[str] = []
    suspicious: list[str] = []
    sizes: list[str] = []

    for name, description in BUILD_DEP_SPECS:
        path = deps_dir / name
        if not path.is_file():
            missing.append(name)
            findings.append(
                Finding(
                    f"{name} is missing ({description}).",
                    "Any Gradle build downloads it automatically, or place it under build-deps/ manually.",
                )
            )
            continue
        size = path.stat().st_size
        sizes.append(f"{name} {_format_size(size)}")
        if size < MIN_DEPENDENCY_BYTES:
            suspicious.append(name)
            findings.append(
                Finding(
                    f"{name} is only {_format_size(size)}; it may be a truncated download.",
                    f"Delete build-deps/{name} so the next build re-downloads it.",
                )
            )

    if missing:
        return Check("Build dependencies", FAIL, ", ".join(missing) + " missing", findings)
    if suspicious:
        return Check("Build dependencies", WARN, ", ".join(suspicious) + " looks incomplete", findings)
    return Check("Build dependencies", OK, ", ".join(sizes), findings)



def _read_signature_properties(directory: Path) -> dict[str, str]:
    path = directory / "signing.properties"
    values: dict[str, str] = {}
    if not path.is_file():
        return values
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#") or "=" not in stripped:
            continue
        key, value = stripped.split("=", 1)
        values[key.strip()] = value.strip()
    return values


def _inspect_signature_directory(directory: Path) -> tuple[bool, list[Finding], str]:
    findings: list[Finding] = []
    values = _read_signature_properties(directory)
    store_name = values.get("storeFile", "").strip() or "keystore.jks"
    keystore = directory / store_name
    valid = True
    if not keystore.is_file():
        valid = False
        findings.append(Finding(f"{directory.name}/{store_name} not found.", "Add the keystore or set storeFile in signing.properties."))
    if not values.get("storePassword", "").strip():
        valid = False
        findings.append(Finding(f"{directory.name}/signing.properties is missing storePassword.", "Add storePassword=... to signing.properties."))
    if not values.get("keyAlias", "").strip():
        valid = False
        findings.append(Finding(f"{directory.name}/signing.properties is missing keyAlias.", "Add keyAlias=... to signing.properties."))
    return valid, findings, str(keystore)


def _probe_keystore(keystore: Path, store_password: str, key_alias: str) -> Finding | None:
    """Use keytool to confirm the keystore opens and the alias exists. Returns None when
    keytool is unavailable (the probe is skipped, not failed)."""
    keytool = shutil.which("keytool")
    if keytool is None:
        java_home = os.environ.get("JAVA_HOME", "").strip()
        if java_home:
            candidate = Path(java_home) / "bin" / "keytool"
            keytool = str(candidate) if candidate.is_file() else None
    if keytool is None:
        return None

    command = [keytool, "-list", "-keystore", str(keystore), "-storepass", store_password]
    if key_alias:
        command += ["-alias", key_alias]
    code, output = _run(command, timeout=15)
    if code == 0:
        return None
    reason = "wrong storePassword" if "password" in output.lower() else "keytool rejected the keystore"
    if key_alias and key_alias in output and "not" in output.lower():
        reason = f"alias '{key_alias}' not found"
    return Finding(f"{keystore.name} failed the keytool probe ({reason}).", "Verify storePassword and keyAlias in signing.properties.")


def check_release_signing() -> Check:
    env_names = ["RELEASE_STORE_FILE", "RELEASE_STORE_PASSWORD", "RELEASE_KEY_ALIAS", "RELEASE_KEY_PASSWORD"]
    env_set = [name for name in env_names if os.environ.get(name, "").strip()]
    release_dir = repo_root() / "build-deps" / "release-signature"

    if len(env_set) == len(env_names):
        findings = [Finding("Release signing uses the standard RELEASE_* env vars.")]
        probe = _probe_keystore(
            Path(os.environ["RELEASE_STORE_FILE"]).expanduser(),
            os.environ["RELEASE_STORE_PASSWORD"],
            os.environ.get("RELEASE_KEY_ALIAS", "").strip(),
        )
        if probe is not None:
            findings.append(probe)
            return Check("Release signing", WARN, "RELEASE_* env vars failed the keytool probe", findings)
        return Check("Release signing", OK, "RELEASE_* environment variables", findings)

    if not release_dir.is_dir():
        return Check(
            "Release signing",
            WARN,
            "not configured",
            [
                Finding(
                    "No release-signature directory and no RELEASE_STORE_* env vars.",
                    "Provide build-deps/release-signature/ or set the RELEASE_* env vars for signed releases.",
                ),
                Finding("Release builds fall back to the debug signature until then."),
            ],
        )

    valid, dir_findings, keystore = _inspect_signature_directory(release_dir)
    findings = [Finding(f"release-signature: {f.text}", f.hint) for f in dir_findings]
    if not valid:
        return Check("Release signing", WARN, "build-deps/release-signature is incomplete", findings)

    findings.append(Finding(f"Release keystore: {keystore}"))
    values = _read_signature_properties(release_dir)
    probe = _probe_keystore(Path(keystore), values.get("storePassword", ""), values.get("keyAlias", ""))
    if probe is not None:
        findings.append(probe)
        return Check("Release signing", WARN, "build-deps/release-signature failed the keytool probe", findings)
    findings.append(Finding("keytool probe passed."))
    return Check("Release signing", OK, "build-deps/release-signature", findings)


def check_debug_signing() -> Check:
    debug_dir = repo_root() / "build-deps" / "debug-signature"
    if not debug_dir.is_dir():
        return Check(
            "Debug signing",
            OK,
            "AGP default keystore",
            [Finding("No build-deps/debug-signature/; AGP's default debug keystore (~/.android/debug.keystore) is used.")],
        )

    valid, dir_findings, keystore = _inspect_signature_directory(debug_dir)
    findings = [Finding(f"debug-signature: {f.text}", f.hint) for f in dir_findings]
    if not valid:
        return Check("Debug signing", WARN, "build-deps/debug-signature is incomplete", findings)

    findings.append(Finding(f"Debug keystore: {keystore}"))
    values = _read_signature_properties(debug_dir)
    probe = _probe_keystore(Path(keystore), values.get("storePassword", ""), values.get("keyAlias", ""))
    if probe is not None:
        findings.append(probe)
        return Check("Debug signing", WARN, "build-deps/debug-signature failed the keytool probe", findings)
    findings.append(Finding("keytool probe passed."))
    return Check("Debug signing", OK, "build-deps/debug-signature", findings)


def _sling_break_source_url(values: dict[str, str]) -> str:
    explicit = values.get("slingBreak.sourceUrl", "").strip()
    if explicit:
        return explicit
    repository = (
        values.get("slingBreak.repository", "").strip() or SLING_BREAK_DEFAULT_REPOSITORY
    ).rstrip("/")
    ref = values.get("slingBreak.ref", "").strip() or SLING_BREAK_DEFAULT_REF
    if re.fullmatch(r"[0-9a-fA-F]{7,40}", ref) or ref.startswith("refs/"):
        return f"{repository}/archive/{ref}.tar.gz"
    return f"{repository}/archive/refs/heads/{ref}.tar.gz"


def _has_sling_break_bundle(path: Path) -> bool:
    return (path / "index.html").is_file() and (path / "launcher-mode.js").is_file()


def _sling_break_cache_dir() -> Path:
    # Matches the FetchSlingBreakSourceTask output directory.
    return repo_root() / "app" / "build" / "generated" / "slingbreak-source"


@dataclass
class SlingBreakState:
    mode: str  # "local" | "cached" | "remote"
    detail: str
    findings: list[Finding]
    url: str
    valid: bool = True


def _sling_break_state() -> SlingBreakState:
    """Mirror the build's resolution order exactly: an explicit slingBreak.sourceDir wins,
    otherwise the bundle is fetched from the resolved remote archive and a previous
    extraction is reused only when the network fetch fails."""
    values = _parse_gradle_properties()
    override = values.get("slingBreak.sourceDir", "").strip()
    url = _sling_break_source_url(values)

    if override:
        path = (repo_root() / override).resolve()
        if _has_sling_break_bundle(path):
            return SlingBreakState(
                "local",
                f"local override ({path})",
                [Finding("slingBreak.sourceDir is set; no network fetch for Sling Break.")],
                url,
            )
        return SlingBreakState(
            "local",
            f"local override is missing the bundle ({path})",
            [
                Finding(
                    "slingBreak.sourceDir needs index.html and launcher-mode.js.",
                    "Fix the path or unset slingBreak.sourceDir to fetch from the remote archive.",
                )
            ],
            url,
            valid=False,
        )

    cache = _sling_break_cache_dir()
    if _has_sling_break_bundle(cache):
        return SlingBreakState(
            "cached",
            f"cached extraction ({cache})",
            [
                Finding(
                    "A previous extraction is cached; if the upstream is unreachable the build "
                    "reuses it instead of failing.",
                )
            ],
            url,
        )

    return SlingBreakState(
        "remote",
        f"fetched from the remote archive at build time ({url})",
        [
            Finding(
                "This is a hard build input: the build fails if the archive cannot be downloaded.",
                "Reachability is verified by the Network check; set slingBreak.sourceDir for offline builds.",
            )
        ],
        url,
    )


def check_slingbreak() -> Check:
    state = _sling_break_state()
    return Check(
        "Sling Break source",
        OK if state.valid else FAIL,
        state.detail,
        state.findings,
    )



def check_gradle_wrapper() -> Check:
    root = repo_root()
    unix = root / "gradlew"
    windows = root / "gradlew.bat"
    if unix.is_file():
        if os.name != "nt" and not os.access(unix, os.X_OK):
            return Check(
                "Gradle wrapper",
                FAIL,
                "gradlew is not executable",
                [Finding("The wrapper script lacks the executable bit.", "chmod +x gradlew")],
            )
        return Check("Gradle wrapper", OK, "gradlew")
    if windows.is_file():
        return Check("Gradle wrapper", OK, "gradlew.bat")
    return Check("Gradle wrapper", FAIL, "missing", [Finding("No gradlew or gradlew.bat at the repository root.")])


def _toolchain_majors() -> set[int]:
    majors: set[int] = set()
    jdks = Path.home() / ".gradle" / "jdks"
    if jdks.is_dir():
        for entry in jdks.iterdir():
            if not entry.is_dir():
                continue
            for number in re.findall(r"\d{1,3}", entry.name):
                value = int(number)
                if value in {8, 11, 17, 21, 23, 24}:
                    majors.add(value)
                    break
    return majors


def check_gradle_toolchains() -> Check:
    required = [8, 21]
    available = _toolchain_majors()
    missing = [major for major in required if major not in available]
    installed = ", ".join(f"JDK {major}" for major in required if major in available)
    if not missing:
        return Check("Gradle toolchains", OK, installed or "JDK 8, JDK 21")
    return Check(
        "Gradle toolchains",
        WARN,
        "JDK " + ", ".join(str(major) for major in missing) + " not cached",
        [
            Finding(
                f"Build modules target JDK {', '.join(str(major) for major in missing)}.",
                "Downloaded automatically on the first online build (foojay); pre-install them for offline builds.",
            )
        ],
    )


def check_adb(sdk: Path | None) -> Check:
    adb, source = _find_adb(sdk)
    if adb is not None:
        return Check("ADB", OK, source)
    return Check(
        "ADB",
        WARN,
        "not found",
        [Finding("Needed for install/debug/harness tasks, not for building APKs.", 'sdkmanager "platform-tools"')],
    )


@dataclass
class NetworkEndpoint:
    label: str
    url: str
    # A blocking endpoint is one the build cannot proceed without when it is unreachable.
    blocking: bool = False
    # Archive downloads are verified by transferring a slice of the body, because a HEAD
    # round-trip can succeed while the actual transfer stalls (which is how the Sling Break
    # fetch times out even though a header probe passes).
    verify_body: bool = False


PROBE_BODY_BYTES = 1024 * 1024


def _network_endpoints() -> list[NetworkEndpoint]:
    values = _parse_gradle_properties()
    bundle_url = values.get("buildDeps.bundleUrl", "").strip() or DEFAULT_BUNDLE_URL
    build_deps_incomplete = bool(_missing_build_deps())
    sling_break = _sling_break_state()

    endpoints = [
        NetworkEndpoint("Maven Central", "https://repo.maven.apache.org/maven2/junit/junit/4.13.2/junit-4.13.2.pom"),
        NetworkEndpoint("Google Maven", "https://dl.google.com/dl/android/maven2/androidx/core/core-ktx/1.17.0/core-ktx-1.17.0.pom"),
        NetworkEndpoint("Gradle Plugin Portal", "https://plugins.gradle.org/m2/"),
        NetworkEndpoint("foojay toolchains", "https://api.foojay.io/disco/v3.0/packages"),
        NetworkEndpoint("Build dependency bundle", bundle_url, blocking=build_deps_incomplete, verify_body=True),
        NetworkEndpoint("jre8-pojav.zip", JRE8_URL),
    ]
    # A local slingBreak.sourceDir removes Sling Break from the build's network inputs.
    if sling_break.mode != "local":
        endpoints.append(
            NetworkEndpoint(
                "Sling Break source",
                sling_break.url,
                blocking=sling_break.mode == "remote",
                verify_body=True,
            )
        )
    return endpoints


def _probe_endpoint(endpoint: NetworkEndpoint) -> tuple[bool, str]:
    """Return (reachable, detail). Reachable means the asset was served (HTTP < 400) and,
    when body verification is requested, the response body actually transferred."""
    headers = {"User-Agent": "SlayTheAmethyst-doctor"}
    if endpoint.verify_body:
        headers["Range"] = f"bytes=0-{PROBE_BODY_BYTES - 1}"
    request = urllib.request.Request(endpoint.url, method="GET" if endpoint.verify_body else "HEAD", headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=NETWORK_TIMEOUT) as response:
            if not endpoint.verify_body:
                return True, f"HTTP {response.status}"
            received = 0
            while received < PROBE_BODY_BYTES:
                chunk = response.read(min(65536, PROBE_BODY_BYTES - received))
                if not chunk:
                    break
                received += len(chunk)
            if received == 0:
                return False, "empty response body"
            return True, f"HTTP {response.status}, {received // 1024} KiB transferred"
    except urllib.error.HTTPError as error:
        return False, f"HTTP {error.code}"
    except urllib.error.URLError as error:
        reason = getattr(error, "reason", error)
        return False, str(reason)[:120]
    except Exception as error:  # noqa: BLE001 - report any transport failure
        return False, str(error)[:120]


def check_network() -> Check:
    endpoints = _network_endpoints()
    with ThreadPoolExecutor(max_workers=len(endpoints)) as pool:
        results = list(pool.map(_probe_endpoint, endpoints))

    findings: list[Finding] = []
    reachable = 0
    blocked: list[str] = []
    unreachable: list[str] = []

    proxy = os.environ.get("HTTPS_PROXY") or os.environ.get("https_proxy") or os.environ.get("HTTP_PROXY")
    if proxy:
        findings.append(Finding(f"Using proxy: {proxy}"))

    for endpoint, (ok, detail) in zip(endpoints, results):
        if ok:
            reachable += 1
            findings.append(Finding(f"{endpoint.label}: OK ({detail})"))
            continue
        unreachable.append(endpoint.label)
        if endpoint.blocking:
            blocked.append(endpoint.label)
        suffix = " [build-blocking]" if endpoint.blocking else ""
        findings.append(Finding(f"{endpoint.label}: unreachable ({detail}){suffix}"))

    total = len(endpoints)
    detail = f"{reachable}/{total} endpoints reachable"

    if blocked:
        findings.append(
            Finding(
                "Unreachable build-blocking inputs: " + ", ".join(blocked) + ".",
                "The build will fail until they are reachable; place the files locally "
                "(build-deps/, slingBreak.sourceDir) or restore access/mirror.",
            )
        )
        return Check("Network", FAIL, f"{detail}; {', '.join(blocked)} blocked", findings)

    if not unreachable:
        return Check("Network", OK, detail, findings)

    findings.append(
        Finding(
            "Unreachable (not build-blocking): " + ", ".join(unreachable) + ".",
            "Cached Gradle artifacts and an existing build-deps/ may still let the build proceed; "
            "restore access or use a mirror/proxy otherwise.",
        )
    )
    return Check("Network", WARN, detail, findings)


def collect_checks(include_network: bool = True) -> list[Check]:
    sdk, sdk_source = _resolve_android_sdk()
    build_deps_check = check_build_dependencies()
    checks = [
        check_java(),
        check_android_sdk(sdk, sdk_source),
        check_ndk(sdk),
        check_cmake(sdk),
        check_python(),
        build_deps_check,
        check_release_signing(),
        check_debug_signing(),
        check_slingbreak(),
        check_gradle_wrapper(),
        check_gradle_toolchains(),
        check_adb(sdk),
    ]
    if include_network:
        checks.insert(5, check_network())
    return checks


def render(checks: list[Check], palette: Palette, verbose: bool) -> None:
    print()
    print(palette.strong("SlayTheAmethyst build doctor"))
    print(palette.info("Inspecting the local environment needed to build the APK."))
    print()

    for check in checks:
        marker = palette.marker(check.status)
        detail = f" {palette.info(f'({check.detail})')}" if check.detail else ""
        print(f"{marker} {check.label}{detail}")
        show_findings = check.status in (WARN, FAIL) or verbose
        if show_findings:
            for finding in check.findings:
                print(f"    {palette.info('•')} {finding.text}")
                if finding.hint:
                    print(f"      {palette.accent('→')} {finding.hint}")

    print()
    failures = [c for c in checks if c.status == FAIL]
    warnings = [c for c in checks if c.status == WARN]

    if not failures and not warnings:
        print(palette.ok("No issues found. Ready to build."))
    else:
        parts = []
        if failures:
            parts.append(f"{len(failures)} issue{'s' if len(failures) != 1 else ''}")
        if warnings:
            parts.append(f"{len(warnings)} warning{'s' if len(warnings) != 1 else ''}")
        summary = ", ".join(parts) + " found."
        print(palette.fail(summary) if failures else palette.warn(summary))
        if not failures:
            print(palette.ok("Builds can still run."))

    print()
    print(palette.info("Next: ./gradlew :app:assembleDebug"))


def render_json(checks: list[Check]) -> None:
    payload = {
        "checks": [
            {
                "label": check.label,
                "status": check.status,
                "detail": check.detail,
                "findings": [{"text": f.text, "hint": f.hint} for f in check.findings],
            }
            for check in checks
        ],
        "summary": {
            "failures": sum(1 for c in checks if c.status == FAIL),
            "warnings": sum(1 for c in checks if c.status == WARN),
        },
    }
    print(json.dumps(payload, ensure_ascii=False, indent=2))


def run(verbose: bool = False, no_color: bool = False, as_json: bool = False, no_network: bool = False) -> int:
    checks = collect_checks(include_network=not no_network)
    if as_json:
        render_json(checks)
    else:
        render(checks, make_palette(no_color), verbose)
    return 1 if any(check.status == FAIL for check in checks) else 0
