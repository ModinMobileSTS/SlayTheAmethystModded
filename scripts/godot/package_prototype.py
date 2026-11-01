#!/usr/bin/env python3
"""Publish local two-file test payload and build-config properties. Does not upload anything."""
import argparse
import hashlib
from pathlib import Path
import shutil
import zipfile

ROOT = Path(__file__).resolve().parents[2]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:18765")
    parser.add_argument("--engine-url", help="Explicit fixed URL for the engine, e.g. a temporary host")
    parser.add_argument("--pck-url", help="Explicit fixed URL for the PCK")
    parser.add_argument("--minimal", action="store_true")
    args = parser.parse_args()
    out = ROOT / "agent-tmp/godot-spike"
    serve = out / "serve"
    serve.mkdir(parents=True, exist_ok=True)
    apk = ROOT / "godot-runtime/build/outputs/apk/release/godot-runtime-release-unsigned.apk"
    pck = out / ("minimal.pck" if args.minimal else "game.pck")
    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()
        if any(n.startswith("assets/") and not n.startswith("assets/dexopt/") for n in names):
            raise SystemExit("Engine container unexpectedly includes assets; inspect before publishing")
        if "lib/arm64-v8a/libgodot_android.so" not in names:
            raise SystemExit("Engine container is missing Godot")
    lines = []
    for kind, source, name in [("engine", apk, "engine.apk"), ("pck", pck, "game.pck")]:
        target = serve / name
        shutil.copyfile(source, target)
        digest = hashlib.sha256(target.read_bytes()).hexdigest()
        address = (args.engine_url if kind == "engine" else args.pck_url) or f"{args.base_url.rstrip('/')}/{name}"
        lines.extend([f"godot.prototype.{kind}_url={address}", f"godot.prototype.{kind}_sha256={digest}"])
        print(f"{name}: {target.stat().st_size} bytes, sha256={digest}")
    properties = out / "prototype.properties"
    properties.write_text("\n".join(lines) + "\n")
    print("Gradle flags: " + " ".join("-P" + line for line in lines))


if __name__ == "__main__":
    main()
