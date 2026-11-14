from scripts.tools.harness._context import HarnessContext, set_result_success
from scripts.tools.harness._status import StatusQueryError, harness_status


def run_status(ctx: HarnessContext) -> None:
    try:
        status = harness_status(ctx)
    except StatusQueryError as exc:
        ctx.result["statusSnapshot"] = {
            "observedState": "UNKNOWN",
            "applicationId": ctx.application_id,
            "deviceSerial": ctx.resolved_device_serial,
            "queryError": str(exc),
        }
        set_result_success(ctx, False, "STATUS_QUERY_FAILED", str(exc))
        return
    ctx.result["statusSnapshot"] = status
    set_result_success(ctx, True, status["observedState"], "Status snapshot captured.")
