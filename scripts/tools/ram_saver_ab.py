#!/usr/bin/env python3
"""Repeatable APK A/B runner for the single-room RAM Saver benchmark."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import statistics
import subprocess
import time
from pathlib import Path


PACKAGE = "io.stamethyst"
DEVICE = "10.126.126.2:5555"
STS = "/sdcard/Android/data/io.stamethyst/files/sts"
COMPONENTS = (
    "AmethystRuntimeCompat.jar",
    "RamSaver.jar",
    "AmethystFrameProbe.jar",
)


def run(device: str, *args: str, timeout: float = 60.0, check: bool = True) -> str:
    result = subprocess.run(
        ["adb", "-s", device, *args],
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        timeout=timeout,
    )
    if check and result.returncode != 0:
        raise RuntimeError(result.stdout.strip())
    return result.stdout


def shell(device: str, command: str, *, check: bool = True) -> str:
    return run(device, "shell", "sh", "-c", command, check=check)


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def package_component_hash(apk: Path, component: str) -> str:
    result = subprocess.run(
        ["unzip", "-p", str(apk), "assets/components/mods/" + component],
        stdout=subprocess.PIPE,
        check=True,
    )
    return hashlib.sha256(result.stdout).hexdigest()


def install(device: str, apk: Path) -> None:
    run(device, "install", "-r", "-d", str(apk), timeout=180.0)
    for name in COMPONENTS:
        run(device, "shell", "rm", "-f", "%s/required_mods/%s" % (STS, name))
    time.sleep(1.0)


def result_from_log(text: str) -> dict[str, str] | None:
    matches = re.findall(r"\[amethyst-autoplay\] single_room result ([^\n]+)", text)
    if not matches:
        return None
    return dict(re.findall(r"(\w+)=([^ ]+)", matches[-1]))


def collect(device: str, apk: Path, output: Path, duration: int) -> None:
    output.mkdir(parents=True, exist_ok=False)
    install(device, apk)
    spec = output / "single-room.properties"
    spec.write_text(
        "schemaVersion=1\ncharacter=IRONCLAD\nmonster=Lagavulin\n"
        "cards=Strike_R,Strike_R,Strike_R,Strike_R,Strike_R\n",
        encoding="utf-8",
    )
    run(device, "push", str(spec), STS + "/config/autoplay-single-room.properties")
    for name in ("latest.log", "frame-probe-incidents.jsonl", "jvm_gc.log"):
        run(device, "shell", "rm", "-f", STS + "/" + name)
    run(device, "shell", "am", "force-stop", PACKAGE)
    time.sleep(1.0)
    run(
        device,
        "shell", "am", "start", "-n", PACKAGE + "/.LauncherActivity",
        "--es", "io.stamethyst.debug_launch_mode", "mts",
        "--ez", "io.stamethyst.debug_autoplay", "true",
        "--es", "io.stamethyst.debug_autoplay_save_mode", "fresh",
        "--es", "io.stamethyst.debug_autoplay_mode", "single_room",
        "--es", "io.stamethyst.debug_autoplay_single_room_spec",
        STS + "/config/autoplay-single-room.properties",
        "--ez", "io.stamethyst.debug_autoplay_single_room_bench_mode", "true",
        "--ez", "io.stamethyst.debug_performance_deep_diagnostics", "true",
    )
    deadline = time.monotonic() + duration
    result_deadline = None
    samples = [
        "epoch_ms\tpid\tpss_kb\ttemperature_tenth_c\tbattery_level\tvoltage_mv\tcurrent_ua\n"
    ]
    while time.monotonic() < deadline and (
        result_deadline is None or time.monotonic() < result_deadline
    ):
        now_ms = int(time.time() * 1000)
        pid = shell(device, "pidof " + PACKAGE, check=False).strip()
        mem = shell(device, "dumpsys meminfo " + PACKAGE, check=False)
        battery = shell(device, "dumpsys battery", check=False)
        pss_match = re.search(r"^\s*TOTAL\s+(\d+)", mem, re.MULTILINE)
        def battery_field(name: str) -> str:
            match = re.search(r"^\s*" + re.escape(name) + r":\s*(\d+)", battery, re.MULTILINE)
            return match.group(1) if match else ""
        current = shell(device, "dumpsys battery get current_now", check=False).strip()
        samples.append("\t".join((
            str(now_ms), pid, pss_match.group(1) if pss_match else "",
            battery_field("temperature"), battery_field("level"),
            battery_field("voltage"), current,
        )) + "\n")
        if result_deadline is None:
            live_log = shell(device, "cat " + STS + "/latest.log", check=False)
            if "single_room result outcome=" in live_log:
                result_deadline = time.monotonic() + 8.0
        time.sleep(2.0)
    log = shell(device, "cat " + STS + "/latest.log", check=False)
    (output / "latest.log").write_text(log, encoding="utf-8", errors="replace")
    for name in ("frame-probe-incidents.jsonl", "jvm_gc.log", "launcher_perf_snapshot.txt"):
        text = shell(device, "cat " + STS + "/" + name, check=False)
        (output / name).write_text(text, encoding="utf-8", errors="replace")
    (output / "process-samples.tsv").write_text("".join(samples), encoding="utf-8")
    run(device, "shell", "am", "force-stop", PACKAGE)
    result = result_from_log(log)
    played_cards = len(re.findall(r"single_room combat played card=", log))
    blocked_neows_lament = len(re.findall(r"single_room blocked NeowsLament atBattleStart", log))
    save_json(output / "run.json", {
        "apk": str(apk),
        "apkSha256": sha256(apk),
        "componentHashes": {
            name: package_component_hash(apk, name) for name in COMPONENTS
        },
        "result": result,
        "playedCards": played_cards,
        "blockedNeowsLamentTriggers": blocked_neows_lament,
        "durationSeconds": duration,
    })
    if result is None or result.get("outcome") != "monsters_defeated":
        raise RuntimeError("single-room benchmark did not finish successfully")
    if played_cards != 20:
        raise RuntimeError(f"single-room benchmark played {played_cards} cards; expected 20")
    if blocked_neows_lament != 1:
        raise RuntimeError(
            "single-room benchmark did not isolate Neow's Lament exactly once"
        )


def save_json(path: Path, value: object) -> None:
    path.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")


def analyze(path: Path) -> None:
    records = [json.loads(line) for line in (path / "frame-probe-incidents.jsonl").read_text().splitlines() if line]
    if not records:
        raise RuntimeError("no frame-probe records: deep diagnostics may not be active")
    report = {"records": len(records), "lastFrame": records[-1]["frame"]}
    for key in ("totalMs", "renderMs", "swapMs", "flushes", "switches"):
        values = [float(record[key]) for record in records]
        report[key] = {
            "mean": statistics.mean(values),
            "p95": statistics.quantiles(values, n=20, method="inclusive")[18],
            "p99": statistics.quantiles(values, n=100, method="inclusive")[98],
            "max": max(values),
        }
    report["spikes"] = {str(ms): sum(float(r["totalMs"]) >= ms for r in records)
                         for ms in (33, 50, 100, 250, 500, 1000)}
    sample_path = path / "process-samples.tsv"
    if sample_path.is_file():
        rows = []
        for line in sample_path.read_text(encoding="utf-8").splitlines()[1:]:
            fields = line.split("\t")
            try:
                rows.append((int(fields[2]), int(fields[3]), int(fields[4]), int(fields[5]),
                             float(fields[6]) if fields[6] else None))
            except (ValueError, IndexError):
                continue
        if rows:
            pss = [row[0] for row in rows]
            powers = [abs(row[4]) * row[3] / 1e9 for row in rows if row[4] is not None]
            report["memory"] = {
                "pssMeanKb": statistics.mean(pss),
                "pssP95Kb": statistics.quantiles(pss, n=20, method="inclusive")[18],
                "pssMaxKb": max(pss),
            }
            report["thermal"] = {
                "temperatureMinC": min(row[1] for row in rows) / 10,
                "temperatureMaxC": max(row[1] for row in rows) / 10,
            }
            report["battery"] = {
                "levelStart": rows[0][2],
                "levelEnd": rows[-1][2],
                "rawPowerMeanW": statistics.mean(powers) if powers else None,
                "rawPowerMaxW": max(powers) if powers else None,
                "warning": "Battery-side current is invalid for app power while charging.",
            }
    save_json(path / "analysis.json", report)
    print(json.dumps(report, indent=2))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("command", choices=("collect", "analyze"))
    parser.add_argument("--device", default=DEVICE)
    parser.add_argument("--apk", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--duration", type=int, default=120)
    args = parser.parse_args()
    if args.command == "collect":
        if args.apk is None or args.output is None:
            parser.error("collect requires --apk and --output")
        collect(args.device, args.apk.resolve(), args.output.resolve(), args.duration)
    else:
        if args.output is None:
            parser.error("analyze requires --output")
        analyze(args.output.resolve())


if __name__ == "__main__":
    main()
