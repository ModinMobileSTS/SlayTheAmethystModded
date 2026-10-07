from pathlib import Path

from scripts.tools.lib.agent_client import AgentClient, AgentError
from scripts.tools.harness._context import HarnessContext, set_result_success
from scripts.tools.harness.agent import _connect_agent


def run_console(ctx: HarnessContext, resolved_out_dir: Path) -> None:
    command_text = getattr(ctx.options, "console_command", "").strip()

    client = _connect_agent(ctx)
    try:
        if command_text:
            result = _execute_and_print(client, command_text)
            if result.get("executed"):
                set_result_success(ctx, True, "CONSOLE_EXECUTED", f"Console command executed: {command_text}")
            else:
                error = result.get("error", "unknown error")
                set_result_success(ctx, False, "ERROR", f"Console command failed: {error}")
        else:
            _interactive_repl(client)
            set_result_success(ctx, True, "CONSOLE_SESSION_COMPLETE", "Interactive console session finished.")
    except AgentError as exc:
        set_result_success(ctx, False, "ERROR", str(exc))
    except Exception as exc:
        set_result_success(ctx, False, "ERROR", f"Console error: {exc}")
    finally:
        client.close()


def _execute_and_print(client: AgentClient, command_text: str) -> dict:
    print(f"console> {command_text}")
    result = client.console_exec(command_text)
    if result.get("executed"):
        output = result.get("output", "")
        if output and output != "ok":
            print(output)
        else:
            print(f"  executed: {command_text}")
    else:
        error = result.get("error", "unknown error")
        print(f"  error: {error}")
    return result


def _interactive_repl(client: AgentClient) -> None:
    print(
        "\n=== BaseMod Console Mode ===\n"
        "Type BaseMod console commands (e.g. gold 999, unlock Ironclad).\n"
        "Commands: help, exit | quit | q\n"
    )
    while True:
        try:
            line = input("console> ").strip()
        except (EOFError, KeyboardInterrupt):
            break
        if not line:
            continue
        if line in ("exit", "quit", "q"):
            break
        _execute_and_print(client, line)
