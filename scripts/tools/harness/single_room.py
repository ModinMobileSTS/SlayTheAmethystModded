from pathlib import Path
from typing import Any

from scripts.tools.lib.sts_harness import (
    SINGLE_ROOM_DEFAULT_REMOTE_SPEC,
    encode_properties_value,
    file_timestamp,
    quote_android_shell,
    split_csv_tokens,
)
from scripts.tools.harness._context import HarnessContext
from scripts.tools.harness._device import resolve_device_sts_root
from scripts.tools.harness._runner import adb, adb_shell_script


def build_single_room_spec_text(ctx: HarnessContext) -> str:
    spec_file = ctx.options.single_room_spec.strip()
    if spec_file:
        path = ctx.repo_root / spec_file
        if not path.is_file():
            raise RuntimeError(f"Single-room spec file not found: {path}")
        ctx.result.setdefault("artifacts", {})["singleRoomInputSpec"] = str(path)
        return path.read_text(encoding="utf-8")

    character = ctx.options.single_room_character.strip()
    monster = ctx.options.single_room_monster.strip()
    cards = split_csv_tokens(ctx.options.single_room_cards)
    if not character:
        raise RuntimeError("single-room requires -SingleRoomCharacter or -SingleRoomSpec.")
    if not monster:
        raise RuntimeError("single-room requires -SingleRoomMonster or -SingleRoomSpec.")
    if not cards:
        raise RuntimeError("single-room requires at least one card through -SingleRoomCards or -SingleRoomSpec.")
    lines = [
        "# Managed by SlayTheAmethyst harness.",
        "schemaVersion=1",
        f"character={encode_properties_value(character)}",
        f"monster={encode_properties_value(monster)}",
        f"cards={encode_properties_value(','.join(cards))}",
        f"entry={encode_properties_value(getattr(ctx.options, 'single_room_entry', '') or 'first')}",
        *([f"dungeon={encode_properties_value(ctx.options.single_room_dungeon.strip())}"]
          if getattr(ctx.options, "single_room_dungeon", "").strip() else []),
        *([f"seed={encode_properties_value(ctx.options.single_room_seed.strip())}"]
          if getattr(ctx.options, "single_room_seed", "").strip() else []),
        "",
    ]
    return "\n".join(lines)


def ensure_single_room_device_spec(ctx: HarnessContext, resolved_out_dir: Path) -> str:
    if ctx.options.single_room_device_spec.strip():
        return ctx.options.single_room_device_spec.strip()

    local_spec = resolved_out_dir / SINGLE_ROOM_DEFAULT_REMOTE_SPEC
    local_spec.write_text(build_single_room_spec_text(ctx), encoding="utf-8")
    ctx.result.setdefault("artifacts", {})["singleRoomSpec"] = str(local_spec)

    sts_root = resolve_device_sts_root(ctx)
    remote_relative = f"config/{SINGLE_ROOM_DEFAULT_REMOTE_SPEC}"
    if sts_root["accessMode"] == "run-as":
        temp_remote = f"/data/local/tmp/sts-harness-{file_timestamp()}-{SINGLE_ROOM_DEFAULT_REMOTE_SPEC}"
        adb(ctx, ["push", str(local_spec), temp_remote], timeout_seconds=30)
        adb(ctx, ["shell", "chmod", "0644", temp_remote], timeout_seconds=5, allow_failure=True)
        copy_script = (
            "mkdir -p files/sts/config && "
            f"cat {quote_android_shell(temp_remote)} > files/sts/{remote_relative}"
        )
        try:
            adb(ctx, ["exec-out", "run-as", ctx.application_id or "", "sh", "-c", copy_script], timeout_seconds=10)
        finally:
            adb(ctx, ["shell", "rm", "-f", temp_remote], timeout_seconds=5, allow_failure=True)
        return f"files/sts/{remote_relative}"

    parent = f"{sts_root['root']}/config"
    adb_shell_script(ctx, f"mkdir -p {quote_android_shell(parent)}", timeout_seconds=10)
    remote_path = f"{sts_root['root']}/{remote_relative}"
    adb(ctx, ["push", str(local_spec), remote_path], timeout_seconds=30)
    return remote_path
