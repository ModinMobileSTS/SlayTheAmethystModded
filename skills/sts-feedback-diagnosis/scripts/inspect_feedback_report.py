#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import re
import shutil
import sys
import zipfile
from collections import Counter
from pathlib import Path
from typing import Iterable

INTERESTING_KEYWORDS = (
    "exception",
    "error",
    "fatal",
    "crash",
    "caused by",
    "outofmemory",
    "oom",
    "sigsegv",
    "anr",
    "warn",
    "fail",
)

SIGNATURES = {
    "glframebuffer": "glframebuffer",
    "loadout": "loadout",
    "outofmemory": "outofmemory",
    "sigsegv": "sigsegv",
    "anr": "anr",
    "exception": "exception",
    "error": "error",
    "fatal": "fatal",
    "crash": "crash",
    "fail": "fail",
}

SELECTED_SETTINGS = (
    "General.targetFps",
    "General.render.scale",
    "InputAndUi.mobileHudEnabled",
    "Renderer.render.surfaceBackend",
    "Renderer.render.selectionMode",
    "Renderer.render.manualBackend",
    "MobileGlues.anglePolicy",
    "MobileGlues.multidrawMode",
    "MobileGlues.customGlVersion",
    "JvmAndDiagnostics.jvm.heapMaxMb",
    "JvmAndDiagnostics.diag.logcatCaptureEnabled",
    "JvmAndDiagnostics.diag.launcherLogcatCaptureEnabled",
    "Compatibility.virtualFboPoc",
    "Compatibility.runtimeTextureCompat",
    "Compatibility.largeTextureDownscaleCompat",
    "Compatibility.nonRenderableFboFormatCompat",
    "Compatibility.fboIdleReclaimCompat",
    "Compatibility.fboPressureDownscaleCompat",
)


class ReportSource:
    def __init__(self, source: Path) -> None:
        self.source = source
        self.kind: str
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

        if (source / "feedback").is_dir() or (source / "jvm_logs").is_dir():
            self.kind = "directory"
            self.sts_dir = source
            return

        raise FileNotFoundError(
            "Directory input must contain either an 'sts' folder or extracted 'feedback'/'jvm_logs' folders."
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

    def read_text(self, name: str) -> str | None:
        if not self.has(name):
            return None
        if self.zip_file is not None:
            assert self.zip_file is not None
            return self.zip_file.read(name).decode("utf-8", errors="replace")

        assert self.sts_dir is not None
        return self._path_for(name).read_text(encoding="utf-8", errors="replace")

    def extract_to(self, dest: Path) -> None:
        if self.zip_file is not None:
            dest.mkdir(parents=True, exist_ok=True)
            assert self.zip_file is not None
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
        description="Inspect an STS feedback report zip or extracted sts directory."
    )
    parser.add_argument("input", help="Path to sts-feedback-report-*.zip or extracted report root")
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
        print("== Report ==")
        print(f"source={source}")
        print(f"type={report.kind}")
        print(f"entry_count={len(names)}")
        print()

        request_data = load_request_json(report)
        print_feedback_section(report, request_data)
        print_files_section(report, names)
        print_process_exit_section(report)
        print_launcher_settings_section(report)
        print_log_signals_section(report)
        return 0
    finally:
        report.close()


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
        title_text = report.read_text("sts/feedback/issue_title.txt") or ""
        issue_title = title_text.strip()

    issue_body = report.read_text("sts/feedback/issue_body.md") or ""
    summary_line = first_heading_body(issue_body, "## 概要")
    detail_line = first_heading_body(issue_body, "## 详细描述")
    repro_line = first_heading_body(issue_body, "## 复现步骤")

    print("== Feedback ==")
    print(f"issue_title={or_unknown(issue_title)}")
    print(f"category={or_unknown(feedback.get('categoryLabel') or feedback.get('category'))}")
    print(f"game_issue_type={or_unknown(feedback.get('gameIssueTypeLabel') or feedback.get('gameIssueType'))}")
    print(f"reproduced_on_last_run={or_unknown(feedback.get('reproducedOnLastRun'))}")
    print(f"suspect_unknown={or_unknown(feedback.get('suspectUnknown'))}")
    print(
        "suspected_mods="
        + format_list(extract_suspected_mods(feedback.get("suspectedMods")))
    )
    print(f"summary={or_unknown(summary_line or feedback.get('summary'))}")
    print(f"detail={or_unknown(detail_line or feedback.get('detail'))}")
    print(f"reproduction={or_unknown(repro_line or feedback.get('reproductionSteps'))}")
    print(f"launcher_version={or_unknown(environment.get('versionName'))}")
    print(f"android={or_unknown(environment.get('androidRelease'))} sdk={or_unknown(environment.get('androidSdkInt'))}")
    manufacturer = str(environment.get("manufacturer", "")).strip()
    model = str(environment.get("model", "")).strip()
    device_label = " ".join(part for part in (manufacturer, model) if part).strip()
    print(f"device={or_unknown(device_label or environment.get('device'))}")
    print(f"enabled_mod_count={mod_count}")
    print()


def print_files_section(report: ReportSource, names: list[str]) -> None:
    latest_log = "sts/jvm_logs/latest.log"
    archived_logs = [
        name for name in names if name.startswith("sts/jvm_logs/jvm_log_") and name.endswith(".log")
    ]
    archived_logs.sort(reverse=True)
    logcat_files = [name for name in names if name.startswith("sts/logcat/")]
    optional_files = [
        name
        for name in (
            "sts/jvm_logs/boot_bridge_events.log",
            "sts/jvm_logs/jvm_gc.log",
            "sts/jvm_logs/jvm_heap_snapshot.txt",
            "sts/jvm_logs/last_signal_dump.txt",
            "sts/jvm_logs/process_exit_trace.txt",
            "sts/crash/summary.txt",
            "sts/jvm_histograms/summary.txt",
        )
        if report.has(name)
    ]

    print("== Files ==")
    print(f"latest_log={describe_file(report, latest_log)}")
    print(f"archived_log_count={len(archived_logs)}")
    for name in archived_logs[:4]:
        print(f"archived_log={describe_file(report, name)}")
    print(f"logcat_file_count={len(logcat_files)}")
    for name in logcat_files[:6]:
        print(f"logcat_file={describe_file(report, name)}")
    print(f"optional_file_count={len(optional_files)}")
    for name in optional_files:
        print(f"optional_file={describe_file(report, name)}")
    print()


def print_process_exit_section(report: ReportSource) -> None:
    text = report.read_text("sts/jvm_logs/process_exit_info.txt") or ""
    values = parse_key_value_text(text)
    latest_summary = parse_key_value_text(report.read_text("sts/jvm_logs/latest_log_summary.txt") or "")

    print("== Process Exit ==")
    if values:
        for key in (
            "processExit.reason",
            "processExit.status",
            "processExit.timestamp",
            "processExit.description",
            "processExit.trace.present",
            "signalDump.present",
        ):
            print(f"{key}={or_unknown(values.get(key))}")
    else:
        print("process_exit_info=missing")

    if latest_summary:
        print(f"latestLog.detectedCrashMarker={or_unknown(latest_summary.get('latestLog.detectedCrashMarker'))}")
        print(f"latestLog.lastNonBlankLine={or_unknown(latest_summary.get('latestLog.lastNonBlankLine'))}")
    else:
        print("latest_log_summary=missing")
    print()


def print_launcher_settings_section(report: ReportSource) -> None:
    text = report.read_text("sts/jvm_logs/launcher_settings.txt") or ""
    values = parse_launcher_settings(text)

    print("== Launcher Settings ==")
    if not values:
        print("launcher_settings=missing")
        print()
        return

    for key in SELECTED_SETTINGS:
        print(f"{key}={or_unknown(values.get(key))}")
    print()


def print_log_signals_section(report: ReportSource) -> None:
    text = report.read_text("sts/jvm_logs/latest.log") or ""
    print("== latest.log signals ==")
    if not text:
        print("latest_log=missing")
        print()
        return

    lines = [line.rstrip() for line in text.splitlines() if line.strip()]
    signature_counts = Counter()
    matching_lines: list[str] = []
    repeated_lines = Counter()

    for raw_line in lines:
        lowered = raw_line.lower()
        if any(keyword in lowered for keyword in INTERESTING_KEYWORDS):
            matching_lines.append(raw_line.strip())
            normalized = normalize_log_line(raw_line)
            repeated_lines[normalized] += 1
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


def parse_launcher_settings(text: str) -> dict[str, str]:
    result: dict[str, str] = {}
    section = ""
    for raw_line in text.splitlines():
        line = raw_line.strip()
        if not line or line.startswith("launcherSettings."):
            continue
        if line.startswith("[") and line.endswith("]"):
            section = line[1:-1].strip()
            continue
        if "=" not in line:
            continue
        key, value = line.split("=", 1)
        full_key = f"{section}.{key.strip()}" if section else key.strip()
        result[full_key] = value.strip()
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


def first_heading_body(text: str, heading: str) -> str:
    if not text:
        return ""
    lines = text.splitlines()
    for index, raw_line in enumerate(lines):
        if raw_line.strip() != heading:
            continue
        collected: list[str] = []
        for inner in lines[index + 1 :]:
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


def normalize_log_line(line: str) -> str:
    stripped = line.strip()
    stripped = re.sub(r"^\d{2}:\d{2}:\d{2}\.\d+\s+", "", stripped)
    stripped = re.sub(r"^\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d+\s+\d+\s+\d+\s+", "", stripped)
    return stripped


def describe_file(report: ReportSource, name: str) -> str:
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
