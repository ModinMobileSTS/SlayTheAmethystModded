"""Strict, framed queries used only by status collection."""
from typing import Any
from uuid import uuid4

from scripts.tools.harness._context import HarnessContext
from scripts.tools.harness._runner import adb, adb_shell_script
from scripts.tools.lib.sts_harness import limit_text, quote_android_shell


class StatusQueryError(RuntimeError):
    """The device state could not be observed; never interpret this as stopped."""


def status_query_script(script: str, label: str, token: str) -> str:
    # The daemon merges stderr into stdout. Separate them remotely before that
    # merge: fd 3 streams the body stdout unchanged, while substitution captures
    # only stderr. A trailing sentinel preserves even newline-only stderr.
    return f"""# sts-status-query: {label}
{{
sts_error=$(
(
set -e
{script}
) 2>&1 1>&3
sts_body_exit=$?
printf '.'
exit "$sts_body_exit"
)
sts_query_exit=$?
sts_error=${{sts_error%.}}
if [ -n "$sts_error" ]; then
    printf '%s' "$sts_error" >&2
    if [ "$sts_query_exit" -eq 0 ]; then sts_query_exit=1; fi
fi
if [ "$sts_query_exit" -ne 0 ]; then exit "$sts_query_exit"; fi
printf '\\n{token}\\n'
}} 3>&1
"""


def run_status_query(
    ctx: HarnessContext,
    script: str,
    *,
    label: str,
    sts_root: dict[str, Any] | None = None,
    timeout_seconds: int = 5,
) -> str:
    token = f"__STS_STATUS_OK_{uuid4().hex}__"
    wrapped = status_query_script(script, label, token)
    if sts_root is not None and sts_root["accessMode"] == "run-as":
        result = adb(ctx, ["exec-out", "run-as", ctx.application_id or "", "sh", "-c", wrapped],
                     timeout_seconds=timeout_seconds, allow_failure=True)
    else:
        result = adb_shell_script(ctx, wrapped, timeout_seconds=timeout_seconds, allow_failure=True)
    if result.exit_code != 0:
        raise StatusQueryError(f"{label} query failed (exit {result.exit_code}): {limit_text(result.output, 1000)}")
    suffix = f"\n{token}\n"
    if not result.output.endswith(suffix):
        raise StatusQueryError(f"{label} query did not return a complete success frame.")
    return result.output[:-len(suffix)]


def status_path_probe_script(path: str) -> str:
    # `test -e` alone cannot distinguish ENOENT from EACCES. Confirm absence in
    # a successfully listed/searchable parent (recursing for absent parents).
    # Existing but unstatable entries, including dangling symlinks, fail closed.
    return r"""sts_status_probe() (
    target=$1
    if [ -e "$target" ]; then printf 'exists\n'; exit 0; fi
    case "$target" in
        /|.) printf 'Cannot inspect path root\n' >&2; exit 1 ;;
        */*) parent=${target%/*}; name=${target##*/}; parent=${parent:-/} ;;
        *) parent=.; name=$target ;;
    esac
    parent_state=$(sts_status_probe "$parent") || exit $?
    if [ "$parent_state" = missing ]; then printf 'missing\n'; exit 0; fi
    entries=$(cd "$parent" && ls -a) || exit $?
    newline='
'
    case "$newline$entries$newline" in
        *"$newline$name$newline"*) printf 'Cannot inspect existing path\n' >&2; exit 1 ;;
        *) printf 'missing\n' ;;
    esac
)
""" + f"sts_path={quote_android_shell(path)}\nsts_presence=$(sts_status_probe \"$sts_path\") || exit $?\n"
