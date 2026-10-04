"""Compile the real JNI audio recovery functions with host-side OpenAL stubs.

No recovery logic is reimplemented here. Only the unrelated Android/JVM headers
are replaced; the helper, request and poll functions come from the current tree.
"""

from __future__ import annotations

import json
import os
from pathlib import Path
import re
import shlex
import subprocess


REPO_ROOT = Path(__file__).resolve().parents[3]
NATIVE_SOURCE = REPO_ROOT / "app/src/main/jni/input_bridge_v3.c"
FIXTURE = REPO_ROOT / "scripts/tools/tests/fixtures/native_audio_recovery.c"

HOST_HEADERS = """
#include <stdbool.h>
#include <stdio.h>
#include <string.h>
#include <stdatomic.h>
#include <pthread.h>
#include <dlfcn.h>
#define JNIEXPORT
#define JNICALL
#define JNI_TRUE 1
#define JNI_FALSE 0
typedef void JNIEnv;
typedef void* jclass;
typedef unsigned char jboolean;
"""


def _extract_function(source: str, signature: str) -> str:
    start = source.index(signature)
    # Count braces outside comments and literals, preserving the original body.
    tokens = re.finditer(
        r'/\*.*?\*/|//[^\n]*|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|[{}]',
        source[start:],
        re.DOTALL,
    )
    depth = 0
    for token in tokens:
        if token.group() == "{":
            depth += 1
        elif token.group() == "}":
            depth -= 1
            if depth == 0:
                return source[start:start + token.end()]
    raise ValueError(f"Unterminated native function: {signature}")


def build_probe(out_dir: Path) -> Path:
    source = NATIVE_SOURCE.read_text(encoding="utf-8")
    defines = "\n".join(re.findall(
        r"^#define (?:AL_GAIN|ALC_\w+|AUDIO_ROUTE_LOG_PREFIX|AUDIO_COMMAND_\w+)\b[^\n]*",
        source,
        re.MULTILINE,
    ))
    helpers = source[
        source.index("typedef void* (*POJAV_alcGetCurrentContext_fn)"):
        source.index("static void registerFunctions(JNIEnv *env);")
    ]
    poll = _extract_function(
        source,
        "JNIEXPORT jboolean JNICALL Java_org_lwjgl_glfw_CallbackBridge_nativeRecoverAudioOutput(",
    )
    request = _extract_function(
        source,
        "JNIEXPORT void JNICALL Java_org_lwjgl_glfw_CallbackBridge_nativeRequestAudioRecovery(",
    )
    out_dir.mkdir(parents=True, exist_ok=True)
    generated = out_dir / "native_audio_recovery_probe.c"
    generated.write_text(
        "\n".join((HOST_HEADERS, defines, helpers, poll, request,
                   FIXTURE.read_text(encoding="utf-8"))),
        encoding="utf-8",
    )
    executable = out_dir / "native_audio_recovery_probe"
    compiler = shlex.split(os.environ.get("CC", "cc"))
    completed = subprocess.run(
        [*compiler, "-std=c11", "-D_GNU_SOURCE", str(generated),
         "-pthread", "-ldl", "-o", str(executable)],
        capture_output=True,
        text=True,
        timeout=30,
    )
    (out_dir / "compile.log").write_text(
        completed.stdout + completed.stderr, encoding="utf-8",
    )
    if completed.returncode:
        raise RuntimeError(f"Native audio probe compilation failed:\n{completed.stderr}")
    return executable


def run_scenario(executable: Path, scenario: str) -> list[dict]:
    completed = subprocess.run(
        [str(executable), scenario], capture_output=True, text=True, timeout=10,
    )
    (executable.parent / f"{scenario}.log").write_text(
        completed.stdout + completed.stderr, encoding="utf-8",
    )
    if completed.returncode:
        raise RuntimeError(f"Audio scenario {scenario} failed:\n{completed.stderr}")
    return [json.loads(line) for line in completed.stdout.splitlines()
            if line.startswith('{"phase":')]
