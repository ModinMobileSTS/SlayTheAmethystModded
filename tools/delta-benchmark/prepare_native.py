"""Explicit opt-in source download; never run by Gradle. Sources remain in build/."""
import argparse
import json
from pathlib import Path
import subprocess

PINS = {
    "bsdiff": ("https://github.com/mendsley/bsdiff.git", "b817e9491cf7b8699c8462ef9e2657ca4ccd7667"),
    "HDiffPatch": ("https://github.com/sisong/HDiffPatch.git", "06961361340085ddb2a551775dc402172b8ca67c"),
    "xdelta": ("https://github.com/jmacd/xdelta.git", "5d936d2c30f78729375c3ae070248a05bef27bd2"),
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--local-cache", type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parent / "build" / "native-vendor"
    root.mkdir(parents=True, exist_ok=True)
    manifest = {}
    for name, (url, commit) in PINS.items():
        dest = root / name
        if not dest.exists():
            source = str((args.local_cache / name).resolve()) if args.local_cache else url
            subprocess.run(["git", "clone", "--no-checkout", source, str(dest)], check=True)
        if subprocess.run(["git", "-C", str(dest), "cat-file", "-e", commit], capture_output=True).returncode:
            subprocess.run(["git", "-C", str(dest), "fetch", "--depth", "1", url, commit], check=True)
        subprocess.run(["git", "-C", str(dest), "checkout", "--detach", commit], check=True)
        actual = subprocess.check_output(["git", "-C", str(dest), "rev-parse", "HEAD"], text=True).strip()
        if actual != commit:
            raise RuntimeError("Source pin mismatch")
        if subprocess.check_output(["git", "-C", str(dest), "status", "--porcelain"], text=True):
            raise RuntimeError("Modified vendor checkout: " + name)
        manifest[name] = {"url": url, "commit": commit}
    (root / "pins.json").write_text(json.dumps(manifest, indent=2) + "\n")


if __name__ == "__main__":
    main()
