#!/usr/bin/env python3
"""Structural summary for a Slay the Amethyst diagnostics bundle.

Supports every archive the launcher emits:

- sts-feedback-report-*.zip   (base diagnostics + sts/feedback/*)
- sts-crash-report-*.zip      (base diagnostics + sts/crash/summary.txt)
- sts-jvm-logs-export-*.zip   (base diagnostics only)
- sts-performance-logs-*.zip  (sts/performance/*)

as well as an extracted ``sts/`` directory from any of them.

The base diagnostics bundle lives under ``sts/logs``, ``sts/info`` and a set of
per-domain folders. Older bundles used ``sts/jvm_logs``; that layout is still
resolved as a fallback so previously collected reports keep working.
"""
from __future__ import annotations

import argparse
import json
import re
import shutil
import sys
import zipfile
from collections import Counter
from pathlib import Path
from typing import Iterable, Sequence

INTERESTING_KEYWORDS = (
    "exception",
    "error",
    "fatal",
    "crash",
    "caused by",
    "outofmemory",
    "oom",
    "sigsegv",
    "sigabrt",
    "anr",
    "warn",
    "fail",
)

SIGNATURES = {
    "glframebuffer": "glframebuffer",
    "loadout": "loadout",
    "outofmemory": "outofmemory",
    "sigsegv": "sigsegv",
    "sigabrt": "sigabrt",
    "anr": "anr",
    "exception": "exception",
    "error": "error",
    "fatal": "fatal",
    "crash": "crash",
    "fail": "fail",
}

# (section, key, label). Section is the curated ``[Title / Subtitle]`` heading.
SELECTED_SETTINGS: tuple[tuple[str, str, str], ...] = (
    ("Game / Rendering", "targetFps", "targetFps"),
    ("Game / Rendering", "render.scale", "render.scale"),
    ("Developer / Advanced rendering", "render.surfaceBackend", "render.surfaceBackend"),
    ("Developer / Advanced rendering", "render.selectionMode", "render.selectionMode"),
    ("Developer / Advanced rendering", "render.manualBackend", "render.manualBackend"),
    ("Developer / Advanced rendering", "jvm.heapMaxMb", "jvm.heapMaxMb"),
    ("Developer / MobileGlues", "anglePolicy", "MobileGlues.anglePolicy"),
    ("Developer / MobileGlues", "multidrawMode", "MobileGlues.multidrawMode"),
    ("Developer / MobileGlues", "customGlVersion", "MobileGlues.customGlVersion"),
    ("Developer / Status and logs", "diag.logcatCaptureEnabled", "diag.logcatCaptureEnabled"),
    (
        "Developer / Status and logs",
        "diag.launcherLogcatCaptureEnabled",
        "diag.launcherLogcatCaptureEnabled",
    ),
)

COMPAT_SETTING_KEYS: tuple[str, ...] = (
    "runtimeTextureCompat",
    "largeTextureDownscaleCompat",
    "nonRenderableFboFormatCompat",
    "fboIdleReclaimCompat",
    "fboPressureDownscaleCompat",
    "globalAtlasFilterCompat",
    "modManifestRootCompat",
    "frierenModCompat",
    "downfallImportCompat",
    "vupShionModCompat",
    "jacketNoAnoKoModCompat",
    "fragmentShaderPrecisionCompat",
    "mainMenuPreviewReuseCompat",
    "powerIconRenderRescueCompat",
    "baseModCustomMonsterRenderRescueCompat",
    "nonCombatPlayerRenderRescueCompat",
    "cardTooltipKeywordRescueCompat",
    "nativeTouchscreenAllowlistCompat",
    "hinaCharacterRenderCompat",
    "fboManagerCompat",
)

# Legacy path -> preferred path. Only used when the preferred path is missing.
LEGACY_PATHS = {
    "sts/jvm_logs/latest.log": "sts/logs/latest.log",
    "sts/jvm_logs/latest_log_summary.txt": "sts/logs/latest_log_summary.txt",
    "sts/jvm_logs/launcher_settings.txt": "sts/info/launcher_settings.txt",
    "sts/jvm_logs/device_info.txt": "sts/info/device_info.txt",
    "sts/jvm_logs/boot_bridge_events.log": "sts/logs/boot_bridge_events.log",
    "sts/jvm_logs/last_signal_dump.txt": "sts/logs/last_signal_dump.txt",
    "sts/jvm_logs/process_exit_trace.txt": "sts/logs/process_exit_trace.txt",
    "sts/jvm_logs/jvm_gc.log": "sts/logs/jvm_gc.log",
    "sts/jvm_logs/jvm_heap_snapshot.txt": "sts/logs/jvm_heap_snapshot.txt",
    "sts/logcat/logcat_app_capture.log": "sts/logcat/app/launcher_logcat_app_capture.log",
    "sts/logcat/logcat_system_capture.log": "sts/logcat/system/launcher_logcat_system_capture.log",
    "sts/jvm_histograms/summary.txt": "sts/performance/jvm_histograms/summary.txt",
}
# Preferred path -> legacy path, so a new-style candidate still resolves on old reports.
REVERSE_LEGACY = {preferred: legacy for legacy, preferred in LEGACY_PATHS.items()}
# Directory prefixes that moved between layouts.
LEGACY_PREFIXES = {
    "sts/logs/": ("sts/jvm_logs/",),
    "sts/info/": ("sts/jvm_logs/",),
}


class ReportSource:
    def __init__(self, source: Path) -> None:
        self.source = source
        self.kind: str
        self.performance = False
        self.zip_file: zipfile.ZipFile | None = None
        self.sts_dir: Path | None = None

        if source.is_file():
            self.kind = "zip"
            self.zip_file = zipfile.ZipFile(source)
            return

        if not source.is_dir():
            raise FileNotFoundError(f"Input does not exist: {source}")

        if (source / "sts").is_dir():
            self.kind = "directory"
            self.sts_dir = source / "sts"
            return

        if (source / "logs").is_dir() or (source / "info").is_dir() or (source / "feedback").is_dir():
            self.kind = "directory"
            self.sts_dir = source
            return

        raise FileNotFoundError(
            "Directory input must contain an 'sts' folder or an extracted 'logs'/'info'/'feedback' folder."
        )

    def close(self) -> None:
        if self.zip_file is not None:
            self.zip_file.close()

    def names(self) -> list[str]:
        if self.zip_file is not None:
            return sorted(self.zip_file.namelist())

        assert self.sts_dir is not None
        result: list[str] = []
        for path in self.sts_dir.rglob("*"):
            if path.is_file():
                relative = path.relative_to(self.sts_dir).as_posix()
                result.append(f"sts/{relative}")
        result.sort()
        return result

    def has(self, name: str) -> bool:
        if self.zip_file is not None:
            try:
                self.zip_file.getinfo(name)
                return True
            except KeyError:
                return False

        assert self.sts_dir is not None
        return self._path_for(name).is_file()

    def translate(self, path: str) -> str:
        """Map a base-diagnostics path onto the performance bundle layout.

        The performance bundle flattens ``sts/logs`` and ``sts/info`` into
        ``sts/performance`` while keeping other domain folders.
        """
        if not self.performance or not path.startswith("sts/"):
            return path
        relative = path.removeprefix("sts/").removeprefix("logs/").removeprefix("info/")
        return f"sts/performance/{relative}"

    def pick(self, *candidates: str) -> str | None:
        for candidate in candidates:
            for mapped in (
                self.translate(candidate),
                LEGACY_PATHS.get(candidate),
                REVERSE_LEGACY.get(candidate),
                candidate,
            ):
                if mapped and self.has(mapped):
                    return mapped
        return None

    def list_under(self, prefix: str) -> list[str]:
        mapped = self.translate(prefix) if prefix.startswith("sts/") else prefix
        prefixes = [mapped]
        if not self.performance:
            prefixes.extend(LEGACY_PREFIXES.get(prefix, ()))
        return sorted(
            name for name in self.names() if any(name.startswith(p) for p in prefixes)
        )

    def size(self, name: str) -> int | None:
        if not self.has(name):
            return None
        if self.zip_file is not None:
            try:
                return self.zip_file.getinfo(name).file_size
            except KeyError:
                return None

        assert self.sts_dir is not None
        return self._path_for(name).stat().st_size

    def read_text(self, name: str | None) -> str | None:
        if name is None or not self.has(name):
            return None
        if self.zip_file is not None:
            return self.zip_file.read(name).decode("utf-8", errors="replace")

        assert self.sts_dir is not None
        return self._path_for(name).read_text(encoding="utf-8", errors="replace")

    def read_picked(self, *candidates: str) -> str | None:
        return self.read_text(self.pick(*candidates))

    def extract_to(self, dest: Path) -> None:
        if self.zip_file is not None:
            dest.mkdir(parents=True, exist_ok=True)
            self.zip_file.extractall(dest)
            return

        assert self.sts_dir is not None
        target = dest / "sts"
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copytree(self.sts_dir, target, dirs_exist_ok=True)

    def _path_for(self, name: str) -> Path:
        assert self.sts_dir is not None
        relative = name.removeprefix("sts/")
        return self.sts_dir / Path(relative)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Inspect an STS diagnostics bundle (feedback, crash, JVM-log export or performance)."
    )
    parser.add_argument("input", help="Path to sts-*.zip or an extracted report root / sts directory")
    parser.add_argument(
        "--extract-dir",
        help="Optional output directory. If set, extract or copy the report there.",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    source = Path(args.input).expanduser().resolve()
    report = ReportSource(source)
    try:
        if args.extract_dir:
            extract_dir = Path(args.extract_dir).expanduser().resolve()
            report.extract_to(extract_dir)
            print(f"extracted_to={extract_dir}")

        names = report.names()
        bundle = classify_bundle(report, names)
        report.performance = bundle == "performance-logs"
        print("== Report ==")
        print(f"source={source}")
        print(f"type={report.kind}")
        print(f"bundle={bundle}")
        print(f"entry_count={len(names)}")
        print()

        request_data = load_request_json(report)
        if request_data or report.has("sts/feedback/issue_body.md"):
            print_feedback_section(report, request_data)
        print_files_section(report)
        print_crash_section(report)
        print_launcher_settings_section(report)
        print_device_section(report)
        print_domain_section(report)
        print_log_signals_section(report)
        return 0
    finally:
        report.close()


def classify_bundle(report: ReportSource, names: Sequence[str]) -> str:
    if report.has("sts/feedback/request.json") or report.has("sts/feedback/issue_body.md"):
        return "feedback-report"
    if report.has("sts/crash/summary.txt"):
        return "crash-report"
    if any(name.startswith("sts/performance/") for name in names):
        return "performance-logs"
    if any(name.startswith("sts/logs/") or name.startswith("sts/jvm_logs/") for name in names):
        return "jvm-logs-export"
    return "unknown"


def load_request_json(report: ReportSource) -> dict:
    text = report.read_text("sts/feedback/request.json")
    if not text:
        return {}
    try:
        data = json.loads(text)
    except json.JSONDecodeError:
        return {}
    return data if isinstance(data, dict) else {}


def print_feedback_section(report: ReportSource, request_data: dict) -> None:
    feedback = as_dict(request_data.get("feedback"))
    environment = as_dict(request_data.get("environment"))
    enabled_mods = request_data.get("enabledMods")
    mod_count = len(enabled_mods) if isinstance(enabled_mods, list) else count_enabled_mods_text(report)

    issue_title = ""
    issue = as_dict(request_data.get("issue"))
    if issue:
        issue_title = str(issue.get("title", "")).strip()
    if not issue_title:
        issue_title = (report.read_text("sts/feedback/issue_title.txt") or "").strip()

    issue_body = report.read_text("sts/feedback/issue_body.md") or ""
    summary_line = heading_body(issue_body, "## 概要")
    detail_line = heading_body(issue_body, "## 详细描述")
    repro_line = heading_body(issue_body, "## 复现步骤")

    print("== Feedback ==")
    print(f"issue_title={or_unknown(issue_title)}")
    print(f"category={or_unknown(feedback.get('categoryLabel') or feedback.get('category'))}")
    print(f"game_issue_type={or_unknown(feedback.get('gameIssueTypeLabel') or feedback.get('gameIssueType'))}")
    print(f"reproduced_on_last_run={or_unknown(feedback.get('reproducedOnLastRun'))}")
    print(f"suspect_unknown={or_unknown(feedback.get('suspectUnknown'))}")
    print("suspected_mods=" + format_list(extract_suspected_mods(feedback.get("suspectedMods"))))
    print(f"summary={or_unknown(summary_line or feedback.get('summary'))}")
    print(f"detail={or_unknown(detail_line or feedback.get('detail'))}")
    print(f"reproduction={or_unknown(repro_line or feedback.get('reproductionSteps'))}")
    print(f"launcher_version={or_unknown(environment.get('versionName'))}")
    print(
        "android="
        f"{or_unknown(environment.get('androidRelease'))} "
        f"sdk={or_unknown(environment.get('androidSdkInt'))}"
    )
    manufacturer = str(environment.get("manufacturer", "")).strip()
    model = str(environment.get("model", "")).strip()
    device_label = " ".join(part for part in (manufacturer, model) if part).strip()
    print(f"device={or_unknown(device_label or environment.get('device'))}")
    print(f"enabled_mod_count={mod_count}")
    print()


def print_files_section(report: ReportSource) -> None:
    latest_log = report.pick("sts/logs/latest.log", "sts/jvm_logs/latest.log")
    archived_logs = sorted(
        (
            name
            for name in report.list_under("sts/logs/")
            if name.rsplit("/", 1)[-1].startswith("jvm_log_") and name.endswith(".log")
        ),
        reverse=True,
    )
    logcat_files = report.list_under("sts/logcat/")
    optional_keys = (
        "sts/logs/boot_bridge_events.log",
        "sts/logs/startup_trace.log",
        "sts/logs/jvm_gc.log",
        "sts/logs/jvm_heap_snapshot.txt",
        "sts/logs/last_signal_dump.txt",
        "sts/logs/process_exit_trace.txt",
        "sts/crash/summary.txt",
        "sts/launcher_perf_snapshot.txt",
        "sts/performance_launch_audit.log",
        "sts/frame-probe-incidents.jsonl",
        "sts/arthas-bridge.log",
        "sts/jvm_histograms/summary.txt",
    )
    optional_files = [report.pick(key) for key in optional_keys]
    optional_files = [name for name in optional_files if name is not None]

    print("== Files ==")
    print(f"latest_log={describe_file(report, latest_log)}")
    print(f"archived_log_count={len(archived_logs)}")
    for name in archived_logs[:4]:
        print(f"archived_log={describe_file(report, name)}")
    print(f"logcat_file_count={len(logcat_files)}")
    for name in logcat_files[:8]:
        print(f"logcat_file={describe_file(report, name)}")
    print(f"optional_file_count={len(optional_files)}")
    for name in optional_files:
        print(f"optional_file={describe_file(report, name)}")
    print()


def print_crash_section(report: ReportSource) -> None:
    summary = report.read_picked("sts/crash/summary.txt")
    signal_dump = report.read_picked("sts/logs/last_signal_dump.txt", "sts/jvm_logs/last_signal_dump.txt")
    process_exit_trace = report.pick(
        "sts/logs/process_exit_trace.txt", "sts/jvm_logs/process_exit_trace.txt"
    )
    launcher_reports = [
        name
        for name in report.list_under("sts/launcher_crash_reports/")
        if not name.endswith("index.txt") and not name.endswith("read_error.txt")
    ]

    if not summary and not signal_dump and not process_exit_trace and not launcher_reports:
        return

    print("== Crash / exits ==")
    if summary:
        values = parse_key_value_text(summary)
        for key in (
            "crash.code",
            "crash.isSignal",
            "processExit.processName",
            "processExit.reason",
            "processExit.status",
            "processExit.trace.present",
            "signalDump.present",
        ):
            print(f"{key}={or_unknown(values.get(key))}")
        for line in first_non_tagged_lines(summary):
            print(f"crash_detail={shorten(line, 240)}")
    elif report.has("sts/crash/summary.txt"):
        print("crash_summary=missing")
    else:
        print("crash_summary=absent_not_a_crash_bundle")

    if signal_dump:
        first_signal = first_block(signal_dump, "signal=")
        if first_signal:
            for line in first_signal:
                if line.startswith(("signal=", "si_code=", "sender_pid=", "detail=")):
                    print(f"signal_dump.{line}")
        for frame in ("pc_symbol=", "lr_symbol="):
            line = find_line(signal_dump, frame)
            if line:
                print(f"signal_dump.{line}")

    if process_exit_trace:
        print(f"process_exit_trace={describe_file(report, process_exit_trace)}")
    print(f"launcher_crash_report_count={len(launcher_reports)}")
    for name in launcher_reports[:3]:
        print(f"launcher_crash_report={name}")
    print()


def print_launcher_settings_section(report: ReportSource) -> None:
    text = report.read_picked("sts/info/launcher_settings.txt", "sts/jvm_logs/launcher_settings.txt")
    zh_present = report.pick("sts/info/launcher_settings.zh.txt") is not None
    raw_preferences_present = bool(text and "[Raw preferences" in text)

    print("== Launcher Settings ==")
    if not text:
        print("launcher_settings=missing")
        print()
        return

    values = parse_launcher_settings(text)
    for section, key, label in SELECTED_SETTINGS:
        print(f"{label}={or_unknown(values.get(section, {}).get(key))}")
    compatibility = values.get("Developer / Compatibility settings", {})
    for key in COMPAT_SETTING_KEYS:
        value = compatibility.get(key)
        if value is not None and value.strip().lower() == "true":
            print(f"compat_enabled={key}")
    print(f"launcher_settings.zh_present={str(zh_present).lower()}")
    print(f"raw_preferences_present={str(raw_preferences_present).lower()}")
    print()


def print_device_section(report: ReportSource) -> None:
    text = report.read_picked("sts/info/device_info.txt", "sts/jvm_logs/device_info.txt")
    if not text:
        print("== Device ==")
        print("device_info=missing")
        print()
        return
    values = parse_key_value_text(text)
    print("== Device ==")
    for key in (
        "launcher.versionName",
        "launcher.versionCode",
        "device.manufacturer",
        "device.model",
        "android.release",
        "android.sdkInt",
        "device.abis",
    ):
        print(f"{key}={or_unknown(values.get(key))}")
    print()


def print_domain_section(report: ReportSource) -> None:
    lines: list[str] = []

    resource_pack = report.read_picked("sts/resource_pack/state.txt")
    if resource_pack:
        rp = parse_key_value_text(resource_pack)
        lines.append(
            "resource_pack="
            f"ready={or_unknown(rp.get('resourcePack.ready'))} "
            f"state={or_unknown(rp.get('resourcePack.state'))} "
            f"version={or_unknown(rp.get('resourcePack.version'))} "
            f"source={or_unknown(rp.get('resourcePack.stateSource'))} "
            f"error={or_unknown(rp.get('resourcePack.stateError'))}"
        )

    easytier = report.read_picked("sts/easytier/config_snapshot.txt")
    if easytier:
        et = parse_key_value_text(easytier)
        lines.append(
            "easytier="
            f"enabled={or_unknown(et.get('enabled'))} "
            f"canConnect={or_unknown(et.get('canConnect'))} "
            f"defaultMode={or_unknown(et.get('defaultMode'))}"
        )
    easytier_events = [
        name for name in report.list_under("sts/easytier/") if "event" in name
    ]
    if easytier_events:
        lines.append(f"easytier_event_count={len(easytier_events)}")

    for label, prefix, index_name in (
        ("workshop_download", "sts/workshop/download_tasks/", "sts/workshop/download_tasks/index.txt"),
        ("workshop_market_failed", "sts/workshop/market_failed/", "sts/workshop/market_failed/index.txt"),
        (
            "workshop_auto_import_patch",
            "sts/workshop/auto_import_patch_logs/",
            "sts/workshop/auto_import_patch_logs/index.txt",
        ),
        ("workshop_sts_jar_import", "sts/workshop/sts_jar_import_logs/", None),
    ):
        index_text = report.read_picked(index_name) if index_name else None
        if index_text:
            count = parse_index_count(index_text)
        else:
            count = sum(
                1
                for name in report.list_under(prefix)
                if not name.endswith("index.txt")
            )
        if count:
            lines.append(f"{label}_count={count}")

    steam_cloud = report.read_picked("sts/steam_cloud/phase1/last-operation-summary.txt")
    if steam_cloud:
        outcome = find_line(steam_cloud, "Outcome:")
        operation = find_line(steam_cloud, "Operation:")
        lines.append(f"steam_cloud={or_unknown(outcome)} {or_unknown(operation)}")
    cloud_failures = report.list_under("sts/steam_cloud/phase1/failures/")
    if cloud_failures:
        lines.append(f"steam_cloud_failure_count={len(cloud_failures)}")

    steam_login = report.list_under("sts/steam_login/")
    if steam_login:
        failed = sum(1 for name in steam_login if "login-failed" in name)
        lines.append(f"steam_login_records={len(steam_login)} failed={failed}")

    presence = report.read_picked("sts/steam-game-presence/last-operation-summary.txt")
    if presence:
        outcome = find_line(presence, "Outcome:")
        lines.append(f"steam_presence={or_unknown(outcome)}")

    achievement = report.read_picked("sts/achievement_sync/achievement_sync.log")
    if achievement:
        lines.append(f"achievement_sync_events={count_nonblank(achievement)}")

    window_log = report.read_picked("sts/window/window_diagnostics.log")
    if window_log:
        lines.append(f"window_diagnostics_events={count_nonblank(window_log)}")

    memory_logs = report.list_under("sts/memory_diagnostics/")
    if memory_logs:
        lines.append(f"memory_diagnostics_slots={len(memory_logs)}")

    histograms = report.list_under("sts/jvm_histograms/")
    if histograms:
        lines.append(f"jvm_histogram_slots={len(histograms)}")

    if not lines:
        return
    print("== Domains ==")
    for line in lines:
        print(line)
    print()


def print_log_signals_section(report: ReportSource) -> None:
    text = report.read_picked("sts/logs/latest.log", "sts/jvm_logs/latest.log")
    print("== latest.log signals ==")
    if not text:
        print("latest_log=missing")
        print()
        return

    lines = [line.rstrip() for line in text.splitlines() if line.strip()]
    signature_counts: Counter[str] = Counter()
    matching_lines: list[str] = []
    repeated_lines: Counter[str] = Counter()

    for raw_line in lines:
        lowered = raw_line.lower()
        if any(keyword in lowered for keyword in INTERESTING_KEYWORDS):
            matching_lines.append(raw_line.strip())
            repeated_lines[normalize_log_line(raw_line)] += 1
        for label, needle in SIGNATURES.items():
            if needle in lowered:
                signature_counts[label] += 1

    print("signature_counts=" + format_counter(signature_counts))
    top_repeated = [(line, count) for line, count in repeated_lines.most_common(6) if count > 1]
    if top_repeated:
        for line, count in top_repeated:
            print(f"repeated_match={count}x {shorten(line, 220)}")
    else:
        print("repeated_match=none")

    if matching_lines:
        for line in matching_lines[-12:]:
            print(f"recent_match={shorten(line, 220)}")
    else:
        print("recent_match=none")
    print()


def parse_key_value_text(text: str) -> dict[str, str]:
    result: dict[str, str] = {}
    for raw_line in text.splitlines():
        line = raw_line.strip()
        if not line or "=" not in line:
            continue
        key, value = line.split("=", 1)
        result[key.strip()] = value.strip()
    return result


def parse_launcher_settings(text: str) -> dict[str, dict[str, str]]:
    """Return {section_title: {key: value}} for curated settings sections.

    Rendering stops at the trailing ``[Raw preferences / ...]`` dump so raw
    persisted keys never shadow the resolved values above them.
    """
    result: dict[str, dict[str, str]] = {}
    section: str | None = None
    for raw_line in text.splitlines():
        line = raw_line.strip()
        if not line:
            continue
        if line.startswith("[") and line.endswith("]"):
            title = line[1:-1].strip()
            section = None if title.lower().startswith("raw preferences") else title
            continue
        if section is None or "=" not in line:
            continue
        key, value = line.split("=", 1)
        result.setdefault(section, {})[key.strip()] = value.strip()
    return result


def extract_suspected_mods(value: object) -> list[str]:
    if not isinstance(value, list):
        return []
    result: list[str] = []
    for entry in value:
        if not isinstance(entry, dict):
            continue
        label = (
            str(entry.get("name", "")).strip()
            or str(entry.get("manifestModId", "")).strip()
            or str(entry.get("modId", "")).strip()
        )
        if label:
            result.append(label)
    return result


def count_enabled_mods_text(report: ReportSource) -> int:
    text = report.read_text("sts/feedback/enabled_mods.txt") or ""
    return sum(1 for line in text.splitlines() if line.strip().startswith("- "))


def parse_index_count(text: str) -> int:
    for line in text.splitlines():
        stripped = line.strip()
        for prefix in ("Task count:", "Log count:", "Report count:"):
            if stripped.startswith(prefix):
                value = stripped[len(prefix):].strip()
                if value.isdigit():
                    return int(value)
    return 0


def heading_body(text: str, heading: str) -> str:
    if not text:
        return ""
    lines = text.splitlines()
    for index, raw_line in enumerate(lines):
        if raw_line.strip() != heading:
            continue
        collected: list[str] = []
        for inner in lines[index + 1:]:
            stripped = inner.strip()
            if stripped.startswith("## "):
                break
            if not stripped:
                if collected:
                    break
                continue
            if stripped.startswith("- "):
                return stripped[2:]
            collected.append(stripped)
        return shorten(" ".join(collected), 220)
    return ""


def first_block(text: str, start_prefix: str) -> list[str]:
    block: list[str] = []
    started = False
    for raw_line in text.splitlines():
        line = raw_line.strip()
        if not started:
            if line.startswith(start_prefix):
                started = True
                block.append(line)
            continue
        if line.startswith(start_prefix):
            break
        block.append(line)
    return block


def first_non_tagged_lines(text: str) -> list[str]:
    detail: list[str] = []
    collecting = False
    for raw_line in text.splitlines():
        line = raw_line.strip()
        if line.startswith("crash.detail="):
            collecting = True
            value = line.split("=", 1)[1].strip()
            if value and value != "none":
                detail.append(value)
            continue
        if collecting:
            if not line:
                break
            if "=" in line and not line.startswith((" ", "\t")):
                break
            detail.append(line)
    return detail


def find_line(text: str, needle: str) -> str | None:
    for raw_line in text.splitlines():
        if needle in raw_line:
            return raw_line.strip()
    return None


def count_nonblank(text: str) -> int:
    return sum(1 for line in text.splitlines() if line.strip())


def normalize_log_line(line: str) -> str:
    stripped = line.strip()
    stripped = re.sub(r"^\d{2}:\d{2}:\d{2}\.\d+\s+", "", stripped)
    stripped = re.sub(r"^\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d+\s+\d+\s+\d+\s+", "", stripped)
    return stripped


def describe_file(report: ReportSource, name: str | None) -> str:
    if name is None:
        return "missing"
    size = report.size(name)
    if size is None:
        return f"{name} (missing)"
    return f"{name} ({format_bytes(size)})"


def format_bytes(value: int) -> str:
    units = ("B", "KiB", "MiB", "GiB")
    size = float(value)
    unit_index = 0
    while size >= 1024.0 and unit_index < len(units) - 1:
        size /= 1024.0
        unit_index += 1
    if unit_index == 0:
        return f"{int(size)} {units[unit_index]}"
    return f"{size:.1f} {units[unit_index]}"


def as_dict(value: object) -> dict:
    return value if isinstance(value, dict) else {}


def format_list(values: Iterable[str]) -> str:
    result = [value for value in values if value]
    return ", ".join(result) if result else "none"


def format_counter(counter: Counter[str]) -> str:
    pairs = [f"{key}:{counter[key]}" for key in sorted(counter) if counter[key] > 0]
    return ", ".join(pairs) if pairs else "none"


def shorten(value: object, limit: int = 160) -> str:
    text = str(value).replace("\r", " ").replace("\n", " ").strip()
    if not text:
        return "unknown"
    if len(text) <= limit:
        return text
    return text[: limit - 3].rstrip() + "..."


def or_unknown(value: object) -> str:
    if value is None:
        return "unknown"
    if isinstance(value, bool):
        return "true" if value else "false"
    text = str(value).strip()
    return text if text else "unknown"


if __name__ == "__main__":
    sys.exit(main())
