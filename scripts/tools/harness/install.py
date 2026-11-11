from pathlib import Path

from scripts.tools.harness._context import HarnessContext, set_result_success
from scripts.tools.harness._runner import adb, gradle


def run_install(ctx: HarnessContext) -> None:
    gradle(ctx, [":app:assembleDebug"])
    apk_root = ctx.repo_root / "app" / "build" / "outputs" / "apk" / "debug"
    apks = sorted(apk_root.glob("*.apk"), key=lambda item: item.stat().st_mtime, reverse=True) if apk_root.exists() else []
    if not apks:
        raise RuntimeError(f"No debug APK found under: {apk_root}")
    apk = apks[0]
    ctx.result.setdefault("artifacts", {})["debugApk"] = str(apk)
    adb(ctx, ["install", "--fastdeploy", "-r", str(apk)], timeout_seconds=180)
    set_result_success(ctx, True, "INSTALLED", "Debug APK installed.")
