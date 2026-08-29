from pathlib import Path

from scripts.tools.harness._context import HarnessContext, set_result_success
from scripts.tools.harness._runner import gradle
from scripts.tools.harness.single_room import ensure_single_room_device_spec


def _gradle_device_properties(ctx: HarnessContext) -> list[str]:
    device_serial = ctx.resolved_device_serial.strip()
    if not device_serial:
        return []
    return [f"-PdeviceSerial={device_serial}"]


def run_start(
    ctx: HarnessContext,
    resolved_out_dir: Path | None = None,
    *,
    use_autoplay_task: bool = False,
) -> None:
    single_room_spec = ""
    start_task = ":app:stsStart"
    if use_autoplay_task or ctx.options.autoplay_mode == "single_room":
        if ctx.options.autoplay_mode == "single_room":
            if resolved_out_dir is None:
                raise RuntimeError("single-room start requires an artifact output directory.")
            single_room_spec = ensure_single_room_device_spec(ctx, resolved_out_dir)
        start_task = ":app:stsStartAutoplay"
    args = [
        start_task,
        f"-PlaunchMode={ctx.options.launch_mode}",
        f"-PforceJvmCrash={str(ctx.options.force_jvm_crash).lower()}",
        f"-PforceRuntimeCrash={str(ctx.options.force_runtime_crash).lower()}",
        f"-PdebugMode={str(ctx.options.debug_mode).lower()}",
        f"-Pautoplay={str(ctx.options.autoplay).lower()}",
        f"-PautoplaySaveMode={ctx.options.autoplay_save_mode}",
        f"-PautoplayMode={ctx.options.autoplay_mode}",
        f"-PautoplaySingleRoomSpec={single_room_spec}",
        *(["-PperformanceDeepDiagnostics=true"]
          if getattr(ctx.options, "command", "") == "perf-bench" else []),
        "-PdisableCardObtainEffectOwnershipCompat="
        + str(ctx.options.disable_card_obtain_effect_ownership_compat).lower(),
        *_gradle_device_properties(ctx),
    ]
    gradle(ctx, args, timeout_seconds=120)
    set_result_success(ctx, True, "START_REQUESTED", f"Launch request was sent through {start_task}.")


def run_stop(ctx: HarnessContext) -> None:
    gradle(ctx, [":app:stsStop", *_gradle_device_properties(ctx)], timeout_seconds=30)
    set_result_success(ctx, True, "STOPPED", "Application force-stop completed.")
