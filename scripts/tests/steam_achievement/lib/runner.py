"""Python entrypoint orchestration; browser-only assertions run in Playwright's JS API."""

from pathlib import Path
import shutil
import subprocess
import sys


def main() -> int:
    node = shutil.which("node")
    if node is None:
        print("Node.js is required for the Playwright browser fixture.", file=sys.stderr)
        return 1
    fixture = Path(__file__).resolve().with_name("browser.test.cjs")
    root = fixture.parents[4]
    return subprocess.run([node, str(fixture)], cwd=root, check=False).returncode
