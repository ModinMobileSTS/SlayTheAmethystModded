from scripts.tools.harness._context import HarnessContext, set_result_success
from scripts.tools.harness._runner import adb, gradle
from scripts.tools.lib.device_safety import require_explicit_device


def run_install(ctx: HarnessContext) -> None:
    serial = require_explicit_device(ctx.options.device_serial)
    if ctx.options.command != "install":
        raise RuntimeError("Only the explicit install command may install an APK; test flows must not install apps.")
    if ctx.resolved_device_serial != serial:
        raise RuntimeError("Selected device does not match the explicitly requested install target.")
    gradle(ctx, [":app:assembleDebug"])
    apk_root = ctx.repo_root / "app" / "build" / "outputs" / "apk" / "debug"
    apks = sorted(apk_root.glob("*.apk"), key=lambda item: item.stat().st_mtime, reverse=True) if apk_root.exists() else []
    if not apks:
        raise RuntimeError(f"No debug APK found under: {apk_root}")
    apk = apks[0]
    ctx.result.setdefault("artifacts", {})["debugApk"] = str(apk)
    adb(ctx, ["install", "-r", str(apk)], timeout_seconds=180)
    set_result_success(ctx, True, "INSTALLED", "Debug APK installed.")
