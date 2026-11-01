#!/usr/bin/env python3
"""Build a pinned two-URL debug launcher from previously exported prototype PCKs."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]


def run(*args):
    subprocess.run(args, cwd=ROOT, check=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:18765")
    parser.add_argument("--engine-url")
    parser.add_argument("--pck-url")
    parser.add_argument("--minimal", action="store_true")
    args = parser.parse_args()
    run("./gradlew", ":godot-runtime:assembleRelease", "--console=plain", "-Pkotlin.compiler.execution.strategy=in-process")
    command = [sys.executable, "scripts/godot/package_prototype.py", "--base-url", args.base_url]
    if args.engine_url:
        command.extend(["--engine-url", args.engine_url])
    if args.pck_url:
        command.extend(["--pck-url", args.pck_url])
    if args.minimal:
        command.append("--minimal")
    run(*command)
    properties = (ROOT / "agent-tmp/godot-spike/prototype.properties").read_text().splitlines()
    run("./gradlew", ":app:assembleDebug", ":app:testDebugUnitTest", "--tests", "io.stamethyst.godot.PrototypeFilesTest",
        "--console=plain", "-Pkotlin.compiler.execution.strategy=in-process", *("-P" + line for line in properties))
    run(sys.executable, "scripts/godot/check_payload.py", "--engine",
        "godot-runtime/build/outputs/apk/release/godot-runtime-release-unsigned.apk", "--launcher",
        "app/build/outputs/apk/debug/app-debug.apk")
    output = ROOT / "agent-tmp/godot-spike/slaytheamethyst-godot-prototype.apk"
    shutil.copyfile(ROOT / "app/build/outputs/apk/debug/app-debug.apk", output)
    report = {"apk": str(output), "bytes": output.stat().st_size,
              "sha256": hashlib.sha256(output.read_bytes()).hexdigest(),
              "components": dict(line.split("=", 1) for line in properties),
              "deviceValidated": False}
    (output.parent / "build-report.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
