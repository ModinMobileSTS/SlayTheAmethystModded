"""Arthas diagnosis for frame-probe stalls.

The prior sampler counted common ``SpriteBatch.flush`` callers. That explains
batch composition, but it cannot explain a long frame: every normal frame has
flushes and the command had no timestamp correlation with frame-probe data.

This sampler traces the render-loop root only when one loop iteration exceeds
the frame budget, then separately traces explicit GC and texture upload. The
root trace carries the same render-thread stack that produced the long frame.
"""

from __future__ import annotations

import re
import json
import threading
import time
from datetime import datetime, timezone, timedelta
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

# ---------------------------------------------------------------------------
# Data model
# ---------------------------------------------------------------------------

@dataclass
class CallerEntry:
    frame: str
    count: int = 0
    stacks: list[list[str]] = field(default_factory=list)   # up to MAX_EXAMPLE_STACKS


@dataclass
class SlowRenderEntry:
    method: str
    cost_ms: float
    subframes: list[str] = field(default_factory=list)
    timestamp_text: str = ""
    thread_name: str = ""
    matched_incident: dict[str, Any] | None = None
    same_second_incident_count: int = 0
    same_second_long_incident_count: int = 0
    same_second_max_ms: float = 0.0


@dataclass
class FunctionHotspot:
    frame: str
    count: int = 0
    total_ms: float = 0.0
    max_ms: float = 0.0


@dataclass
class TracerResult:
    raw_stack_output: str = ""
    raw_trace_output: str = ""
    raw_gc_output: str = ""
    raw_upload_output: str = ""
    raw_update_output: str = ""
    raw_dungeon_output: str = ""
    caller_table: list[CallerEntry] = field(default_factory=list)
    slow_renders: list[SlowRenderEntry] = field(default_factory=list)
    error: str = ""
    duration_s: float = 0.0
    arthas_pid: str = ""
    function_hotspots: list[FunctionHotspot] = field(default_factory=list)
    started_epoch_ms: int = 0
    ended_epoch_ms: int = 0
    incident_count_in_window: int = 0
    long_incident_count_in_window: int = 0
    long_frame_entries: list[SlowRenderEntry] = field(default_factory=list)
    upload_entries: list[SlowRenderEntry] = field(default_factory=list)
    update_entries: list[SlowRenderEntry] = field(default_factory=list)
    dungeon_entries: list[SlowRenderEntry] = field(default_factory=list)


_MAX_STACK_SAMPLES   = 300
_MAX_TRACE_SAMPLES   = 100
_MAX_EXAMPLE_STACKS  = 3
_TRACE_COST_FLOOR_MS = 5      # only capture renders >5 ms
_DEFAULT_DURATION_S  = 90     # sampling window; tracer interrupted after this

_FRAME_CLASS = "com.megacrit.cardcrawl.core.CardCrawlGame"
_FRAME_METHOD = "render"
_FRAME_COST_FLOOR_MS = 33
_GC_CLASS = "java.lang.System"
_GC_METHOD = "gc"
_UPLOAD_CLASS = "com.badlogic.gdx.graphics.GLTexture"
_UPLOAD_METHOD = "uploadImageData"

# SpriteBatch.flush is in libGDX — use the full class name
_FLUSH_CLASS  = "com.badlogic.gdx.graphics.g2d.SpriteBatch"
_FLUSH_METHOD = "flush"

_RENDER_CLASS  = "com.megacrit.cardcrawl.cards.AbstractCard"
_RENDER_METHOD = "render"


# ---------------------------------------------------------------------------
# Public API
# ---------------------------------------------------------------------------

class FlushTracer:
    """Runs Arthas stack/trace commands in a background thread."""

    def __init__(
        self,
        connector: Any,
        device_serial: str,
        out_dir: Path,
        duration_s: float = _DEFAULT_DURATION_S,
        agent_port: int = 9099,
        arthas_port: int = 8099,
    ) -> None:
        self._connector = connector
        self._device_serial = device_serial
        self._out_dir = out_dir
        self._duration_s = duration_s
        self._agent_port = agent_port
        self._arthas_port = arthas_port
        self._result = TracerResult()
        self._thread: threading.Thread | None = None
        self._started_at: float = 0.0

    # ── lifecycle ────────────────────────────────────────────────────────────

    def start(self) -> None:
        """Spawn the background sampling thread.  Non-blocking."""
        self._started_at = time.monotonic()
        self._result.started_epoch_ms = int(time.time() * 1000)
        self._thread = threading.Thread(
            target=self._run, daemon=True, name="flush-tracer"
        )
        self._thread.start()

    def join(self, timeout: float | None = None) -> None:
        """Wait for sampling to finish."""
        if self._thread is not None:
            self._thread.join(timeout=timeout)

    @property
    def result(self) -> TracerResult:
        return self._result

    # ── internals ────────────────────────────────────────────────────────────

    def _run(self) -> None:
        try:
            self._do_sample()
        except Exception as exc:
            self._result.error = f"tracer thread failed: {exc}"

    def _do_sample(self) -> None:
        from scripts.tools.connector.client import ConnectorClient
        from scripts.tools.arthas.shell import ArthasShell

        conn = ConnectorClient(port=self._connector.port)
        conn.connect()
        try:
            if not conn.select(self._device_serial):
                self._result.error = f"tracer: failed to select device {self._device_serial}"
                return

            ensure = conn.arthas_ensure(
                agent_port=self._agent_port,
                arthas_port=self._arthas_port,
            )
            self._result.arthas_pid = str(ensure.get("pid", ""))

            # A streaming command owns its shell connection. Each probe uses a
            # separate session so root-frame, GC, and upload evidence covers the
            # same diagnostic window.
            workers = [
                threading.Thread(
                    target=self._sample_frame_connection,
                    args=(ArthasShell, ConnectorClient),
                    daemon=True,
                    name="arthas-frame-sampler",
                ),
                threading.Thread(
                    target=self._sample_gc_connection,
                    args=(ArthasShell, ConnectorClient),
                    daemon=True,
                    name="arthas-gc-sampler",
                ),
                threading.Thread(
                    target=self._sample_upload_connection,
                    args=(ArthasShell, ConnectorClient),
                    daemon=True,
                    name="arthas-upload-sampler",
                ),
                threading.Thread(
                    target=self._sample_update_connection,
                    args=(ArthasShell, ConnectorClient),
                    daemon=True,
                    name="arthas-update-sampler",
                ),
                threading.Thread(
                    target=self._sample_dungeon_connection,
                    args=(ArthasShell, ConnectorClient),
                    daemon=True,
                    name="arthas-dungeon-sampler",
                ),
            ]
            for worker in workers:
                worker.start()
            for worker in workers:
                worker.join()
        finally:
            try:
                conn.close()
            except Exception:
                pass

        self._result.duration_s = time.monotonic() - self._started_at
        self._result.ended_epoch_ms = int(time.time() * 1000)

    def _open_shell(self, shell_type: Any, connector_type: Any) -> tuple[Any, Any, Any]:
        conn = connector_type(port=self._connector.port)
        conn.connect()
        if not conn.select(self._device_serial):
            conn.close()
            raise RuntimeError(f"failed to select device {self._device_serial}")
        ensure = conn.arthas_ensure(
            agent_port=self._agent_port,
            arthas_port=self._arthas_port,
        )
        if not self._result.arthas_pid:
            self._result.arthas_pid = str(ensure.get("pid", ""))
        stream = conn.connect_arthas_stream(
            agent_port=self._agent_port,
            arthas_port=self._arthas_port,
        )
        shell = shell_type(stream=stream)
        return conn, stream, shell

    def _sample_frame_connection(self, shell_type: Any, connector_type: Any) -> None:
        conn = stream = None
        try:
            conn, stream, shell = self._open_shell(shell_type, connector_type)
            shell.command("options disable-sub-class true")
            self._collect_frame_traces(shell)
        except Exception as exc:
            self._result.raw_stack_output = f"[error: {exc}]"
            self._append_error(f"Arthas frame trace failed: {exc}")
        finally:
            self._close_sample_connection(conn, stream)

    def _sample_gc_connection(self, shell_type: Any, connector_type: Any) -> None:
        conn = stream = None
        try:
            conn, stream, shell = self._open_shell(shell_type, connector_type)
            shell.command("options disable-sub-class true")
            shell.command("options unsafe true")
            self._collect_gc_traces(shell)
        except Exception as exc:
            self._result.raw_gc_output = f"[error: {exc}]"
            self._append_error(f"Arthas GC trace failed: {exc}")
        finally:
            self._close_sample_connection(conn, stream)

    @staticmethod
    def _close_sample_connection(conn: Any, stream: Any) -> None:
        for resource in (stream, conn):
            if resource is not None:
                try:
                    resource.close()
                except Exception:
                    pass

    def _sample_upload_connection(self, shell_type: Any, connector_type: Any) -> None:
        conn = stream = None
        try:
            conn, stream, shell = self._open_shell(shell_type, connector_type)
            shell.command("options disable-sub-class true")
            self._collect_upload_traces(shell)
        except Exception as exc:
            self._append_error(f"Arthas upload trace failed: {exc}")
        finally:
            self._close_sample_connection(conn, stream)

    def _sample_update_connection(self, shell_type: Any, connector_type: Any) -> None:
        conn = stream = None
        try:
            conn, stream, shell = self._open_shell(shell_type, connector_type)
            shell.command("options disable-sub-class true")
            raw = shell.command(
                f"trace com.megacrit.cardcrawl.core.CardCrawlGame update -n {_MAX_TRACE_SAMPLES} '#cost > 10'",
                duration=self._duration_s,
            )
            self._result.raw_update_output = raw
            self._out_dir.mkdir(parents=True, exist_ok=True)
            (self._out_dir / "arthas-trace-cardcrawl-update.txt").write_text(
                raw, encoding="utf-8", errors="replace"
            )
        except Exception as exc:
            self._result.raw_update_output = f"[error: {exc}]"
            self._append_error(f"Arthas update trace failed: {exc}")
        finally:
            self._close_sample_connection(conn, stream)

    def _sample_dungeon_connection(self, shell_type: Any, connector_type: Any) -> None:
        conn = stream = None
        try:
            conn, stream, shell = self._open_shell(shell_type, connector_type)
            shell.command("options disable-sub-class true")
            raw = shell.command(
                f"trace com.megacrit.cardcrawl.dungeons.AbstractDungeon render -n {_MAX_TRACE_SAMPLES} '#cost > 10'",
                duration=self._duration_s,
            )
            self._result.raw_dungeon_output = raw
            self._out_dir.mkdir(parents=True, exist_ok=True)
            (self._out_dir / "arthas-trace-dungeon-render.txt").write_text(
                raw, encoding="utf-8", errors="replace"
            )
        except Exception as exc:
            self._result.raw_dungeon_output = f"[error: {exc}]"
            self._append_error(f"Arthas dungeon trace failed: {exc}")
        finally:
            self._close_sample_connection(conn, stream)

    def _collect_frame_traces(self, shell: Any) -> None:
        """Trace only render-loop iterations that match frame-probe jank."""
        cmd = (
            f"trace {_FRAME_CLASS} {_FRAME_METHOD} -n {_MAX_TRACE_SAMPLES} "
            f"'#cost > {_FRAME_COST_FLOOR_MS}'"
        )
        try:
            raw = shell.command(cmd, duration=self._duration_s)
            self._result.raw_stack_output = raw
            self._result.raw_trace_output = raw
            self._out_dir.mkdir(parents=True, exist_ok=True)
            (self._out_dir / "arthas-trace-long-frame.txt").write_text(
                raw, encoding="utf-8", errors="replace"
            )
        except Exception as exc:
            self._result.raw_stack_output = f"[error: {exc}]"

    def _collect_gc_traces(self, shell: Any) -> None:
        """Capture explicit System.gc callers instead of inferring from heap drop."""
        cmd = (
            f"stack {_GC_CLASS} {_GC_METHOD} -n {_MAX_TRACE_SAMPLES}"
        )
        try:
            raw = shell.command(cmd, duration=self._duration_s)
            self._result.raw_gc_output = raw
            self._out_dir.mkdir(parents=True, exist_ok=True)
            (self._out_dir / "arthas-stack-system-gc.txt").write_text(
                raw, encoding="utf-8", errors="replace"
            )
        except Exception as exc:
            self._result.raw_gc_output = f"[error: {exc}]"

    def _collect_upload_traces(self, shell: Any) -> None:
        cmd = f"trace {_UPLOAD_CLASS} {_UPLOAD_METHOD} -n {_MAX_TRACE_SAMPLES} '#cost > {_TRACE_COST_FLOOR_MS}'"
        raw = shell.command(cmd, duration=self._duration_s)
        self._result.raw_upload_output = raw
        self._out_dir.mkdir(parents=True, exist_ok=True)
        (self._out_dir / "arthas-trace-texture-upload.txt").write_text(
            raw, encoding="utf-8", errors="replace"
        )


# ---------------------------------------------------------------------------
# Parsers
# ---------------------------------------------------------------------------

def parse_stack_output(raw: str) -> list[CallerEntry]:
    """Parse Arthas `stack` output into a caller-frequency table.

    Each stack block looks like:
        ts=... thread_name=GL Thread 0 ...
        @com.badlogic.gdx.graphics.g2d.SpriteBatch.flush()
            at com.megacrit.cardcrawl.cards.AbstractCard.renderGlow(...)
            at com.megacrit.cardcrawl.cards.AbstractCard.render(...)
            ...
    The *direct caller* is the first `at` line after the `@...flush` line.
    """
    caller_map: dict[str, CallerEntry] = {}

    # split into per-invocation blocks on the "ts=" header line
    blocks = re.split(r"(?=^ts=)", raw, flags=re.MULTILINE)
    for block in blocks:
        lines = [l.rstrip() for l in block.splitlines() if l.strip()]
        if not lines:
            continue

        # find the @SpriteBatch.flush line
        flush_idx = None
        for i, line in enumerate(lines):
            stripped = line.lstrip()
            if stripped.startswith(f"@{_FLUSH_CLASS}.{_FLUSH_METHOD}") or \
               stripped.startswith(f"@{_FLUSH_CLASS}:{_FLUSH_METHOD}"):
                flush_idx = i
                break

        if flush_idx is None:
            continue

        # collect the `at ...` frames that follow
        at_frames: list[str] = []
        for line in lines[flush_idx + 1:]:
            stripped = line.lstrip()
            if stripped.startswith("at "):
                at_frames.append(stripped[3:].strip())
            elif stripped.startswith("@") or stripped.startswith("ts="):
                break   # next invocation started

        if not at_frames:
            continue

        direct_caller = at_frames[0]
        # strip the package prefix for readability but keep class + method
        short = _shorten_frame(direct_caller)

        if short not in caller_map:
            caller_map[short] = CallerEntry(frame=short)
        entry = caller_map[short]
        entry.count += 1
        if len(entry.stacks) < _MAX_EXAMPLE_STACKS:
            entry.stacks.append(at_frames[:8])

    return sorted(caller_map.values(), key=lambda e: e.count, reverse=True)


def parse_trace_output(raw: str) -> list[SlowRenderEntry]:
    """Parse Arthas `trace` output into slow render entries.

    Trace lines look like:
        `---[12.3ms] com.megacrit...AbstractCard:render()
            +---[5.1ms] com.megacrit...AbstractCard:applyPowers() #235
    """
    entries: list[SlowRenderEntry] = []
    # Each invocation starts with a backtick line showing root cost
    root_re = re.compile(r"`---\[(\d+(?:\.\d+)?)ms\]\s+(.+)")
    sub_re  = re.compile(r"\+---\[(\d+(?:\.\d+)?)ms\]\s+(.+)")

    current: SlowRenderEntry | None = None
    timestamp_text = ""
    thread_name = ""
    for line in raw.splitlines():
        stripped = re.sub(r"\x1b\[[0-9;]*m", "", line.strip())
        header = re.match(r"`?---?ts=([^;]+);thread_name=([^;]+);", stripped)
        if header:
            timestamp_text = header.group(1).strip()
            thread_name = header.group(2).strip()
            continue
        m = root_re.match(stripped)
        if m:
            if current is not None:
                entries.append(current)
            cost_ms = float(m.group(1))
            method  = _shorten_frame(m.group(2).strip())
            current = SlowRenderEntry(
                method=method,
                cost_ms=cost_ms,
                timestamp_text=timestamp_text,
                thread_name=thread_name,
            )
            continue
        m = sub_re.match(stripped)
        if m and current is not None:
            cost_ms = float(m.group(1))
            method  = _shorten_frame(m.group(2).strip())
            current.subframes.append(f"{cost_ms:.1f}ms  {method}")

    if current is not None:
        entries.append(current)

    return sorted(entries, key=lambda e: e.cost_ms, reverse=True)


def _trace_epoch_ms(timestamp_text: str) -> int | None:
    if not timestamp_text:
        return None
    try:
        # Android game logs use the device's local wall clock. The test
        # devices used by the harness report China Standard Time; correlation
        # falls back to a broad nearest-time search if this is not exact.
        parsed = datetime.strptime(timestamp_text, "%Y-%m-%d %H:%M:%S")
        return int(parsed.replace(tzinfo=timezone(timedelta(hours=8))).timestamp() * 1000)
    except ValueError:
        return None


def correlate_trace_incidents(entries: list[SlowRenderEntry], incidents: list[dict[str, Any]]) -> None:
    """Attach same-second frame-probe aggregates to Arthas samples.

    Arthas emits timestamps with one-second precision, while frame-probe emits
    milliseconds. A nearest millisecond match would fabricate causality, so we
    only report aggregate evidence from the shared wall-clock second.
    """
    if not incidents:
        return
    usable = [item for item in incidents if isinstance(item.get("t"), (int, float))]
    for entry in entries:
        timestamp = _trace_epoch_ms(entry.timestamp_text)
        if timestamp is None:
            continue
        second_start = timestamp - (timestamp % 1000)
        same_second = [
            item for item in usable
            if second_start <= float(item["t"]) < second_start + 1000
        ]
        entry.same_second_incident_count = len(same_second)
        long_same_second = [
            item for item in same_second if float(item.get("totalMs", 0.0)) > 33.0
        ]
        entry.same_second_long_incident_count = len(long_same_second)
        entry.same_second_max_ms = max(
            (float(item.get("totalMs", 0.0)) for item in same_second),
            default=0.0,
        )


def parse_direct_callers(raw: str, limit: int = 15) -> list[tuple[str, int]]:
    """Return direct callers from generic Arthas stack output."""
    counts: dict[str, int] = {}
    for block in re.split(r"(?=^ts=)", raw, flags=re.MULTILINE):
        frames = [line.strip()[3:] for line in block.splitlines()
                  if line.strip().startswith("at ")]
        if frames:
            counts[frames[0]] = counts.get(frames[0], 0) + 1
    return sorted(counts.items(), key=lambda item: item[1], reverse=True)[:limit]


def aggregate_trace_hotspots(entries: list[SlowRenderEntry]) -> list[FunctionHotspot]:
    """Rank traced functions by accumulated and worst observed cost."""
    hotspots: dict[str, FunctionHotspot] = {}
    for entry in entries:
        samples = [(entry.method, entry.cost_ms)]
        for subframe in entry.subframes:
            match = re.match(r"\s*(\d+(?:\.\d+)?)ms\s+(.+)", subframe)
            if match:
                samples.append((match.group(2).strip(), float(match.group(1))))
        for frame, cost_ms in samples:
            hotspot = hotspots.setdefault(frame, FunctionHotspot(frame=frame))
            hotspot.count += 1
            hotspot.total_ms += cost_ms
            hotspot.max_ms = max(hotspot.max_ms, cost_ms)
    return sorted(
        hotspots.values(),
        key=lambda item: (item.total_ms, item.max_ms, item.count),
        reverse=True,
    )


def _shorten_frame(frame: str) -> str:
    """Turn 'com.megacrit.cardcrawl.cards.AbstractCard.renderGlow(AbstractCard.java:123)'
    into 'AbstractCard.renderGlow (AbstractCard.java:123)'."""
    # strip line-number annotation like "(AbstractCard.java:123)"
    frame = re.sub(r"\x1b\[[0-9;]*m", "", frame.strip()).replace(":", ".")
    m = re.match(r"^(.+?)(?:\((.+?)\))?$", frame)
    if not m:
        return frame
    fqn  = m.group(1).rstrip("()")
    loc  = m.group(2) or ""
    # keep only ClassName.method
    parts = fqn.split(".")
    if len(parts) >= 2:
        short = f"{parts[-2]}.{parts[-1]}"
    else:
        short = fqn
    if loc:
        return f"{short} ({loc})"
    return short


# ---------------------------------------------------------------------------
# Report formatter
# ---------------------------------------------------------------------------

def format_report(result: TracerResult, top_n: int = 15) -> str:
    """Build a report that distinguishes long frames, GC and texture upload."""
    long_frames = result.long_frame_entries or parse_trace_output(result.raw_trace_output)
    gc_callers = parse_direct_callers(result.raw_gc_output)
    uploads = result.upload_entries or parse_trace_output(result.raw_upload_output)
    updates = result.update_entries or parse_trace_output(result.raw_update_output)
    dungeons = result.dungeon_entries or parse_trace_output(result.raw_dungeon_output)
    result.function_hotspots = aggregate_trace_hotspots(long_frames)

    w = 70
    lines: list[str] = []
    lines.append("=" * w)
    lines.append("  Long Frame Source Analysis  (Arthas trace)")
    if result.arthas_pid:
        lines.append(f"  JVM pid: {result.arthas_pid}")
    lines.append(f"  Sampling window: {result.duration_s:.0f}s")
    if result.started_epoch_ms and result.ended_epoch_ms:
        lines.append(f"  Wall window: {result.started_epoch_ms}..{result.ended_epoch_ms}")
        lines.append(
            f"  Frame-probe incidents in window: {result.incident_count_in_window} "
            f"(>33ms: {result.long_incident_count_in_window})"
        )
    if result.error:
        lines.append(f"  [!] {result.error}")
    lines.append("-" * w)

    lines.append(f"  Long render-loop samples  (samples: {len(long_frames)})")
    lines.append("")
    if not long_frames:
        lines.append("    (no long render-loop samples collected)")
    else:
        for entry in long_frames[:top_n]:
            match = entry.matched_incident
            context = (
                f" arthas_second={entry.timestamp_text or 'unknown'}"
                f" probe_same_second={entry.same_second_incident_count}"
                f" probe_long_same_second={entry.same_second_long_incident_count}"
                f" probe_max_same_second={entry.same_second_max_ms:.1f}ms"
            )
            lines.append(f"    {entry.cost_ms:>7.1f}ms  {entry.method}{context}")
            for sub in entry.subframes[:4]:
                lines.append(f"               +-- {sub}")

    lines.append("")
    lines.append("  Explicit System.gc() callers")
    if not gc_callers:
        if "No class or method is affected" in result.raw_gc_output:
            lines.append("    (Arthas did not affect System.gc(); check unsafe/core-class support)")
        else:
            lines.append("    (no System.gc() stack samples collected)")
    else:
        for frame, count in gc_callers:
            lines.append(f"    {count:>5}  {_shorten_frame(frame)}")
    lines.append("")
    lines.append("  Deep render subpath samples")
    lines.append(f"    CardCrawlGame.update: {len(updates)} samples")
    lines.append(f"    AbstractDungeon.render: {len(dungeons)} samples")
    for label, entries in (("update", updates), ("dungeon", dungeons)):
        for entry in entries[:3]:
            lines.append(f"    {label} {entry.cost_ms:>7.1f}ms  {entry.method}")
            for sub in entry.subframes[:6]:
                lines.append(f"               +-- {sub}")
    lines.append("")

    lines.append("  Functions contributing most trace time")
    lines.append("")
    if not result.function_hotspots:
        lines.append("    (no function hotspots captured)")
    else:
        lines.append(
            f"    {'function':<44}  {'calls':>6}  {'total ms':>9}  {'max ms':>8}"
        )
        lines.append(f"    {'-'*44}  {'-'*6}  {'-'*9}  {'-'*8}")
        for hotspot in result.function_hotspots[:top_n]:
            lines.append(
                f"    {hotspot.frame:<44}  {hotspot.count:>6}  "
                f"{hotspot.total_ms:>9.1f}  {hotspot.max_ms:>8.1f}"
            )
    lines.append("")

    lines.append(
        f"  Slow texture upload calls  (>{_TRACE_COST_FLOOR_MS}ms, samples: {len(uploads)})"
    )
    lines.append("")
    if not uploads:
        if "Affect(class count:" in result.raw_upload_output:
            lines.append("    (no slow texture uploads captured in this window)")
        else:
            lines.append("    (texture upload trace failed or returned no command output)")
    else:
        for entry in uploads[:top_n]:
            lines.append(f"    {entry.cost_ms:>7.1f}ms  {entry.method}")
            for sub in entry.subframes[:4]:
                lines.append(f"               +-- {sub}")
    lines.append("")
    lines.append("=" * w)
    return "\n".join(lines)


# ---------------------------------------------------------------------------
# Convenience: start + collect helpers used by perf_bench
# ---------------------------------------------------------------------------

def start_tracer(
    connector: Any,
    device_serial: str,
    out_dir: Path,
    duration_s: float = _DEFAULT_DURATION_S,
    agent_port: int = 9099,
    arthas_port: int = 8099,
) -> FlushTracer:
    """Create and start a FlushTracer.  Returns immediately."""
    tracer = FlushTracer(
        connector=connector,
        device_serial=device_serial,
        out_dir=out_dir,
        duration_s=duration_s,
        agent_port=agent_port,
        arthas_port=arthas_port,
    )
    tracer.start()
    return tracer


def collect_tracer_report(
    tracer: FlushTracer,
    out_dir: Path,
    join_timeout: float = 30.0,
    incidents_path: Path | None = None,
) -> tuple[str, Path]:
    """Wait for the tracer, build the report string, write it to disk.

    Returns (report_text, report_path).
    """
    tracer.join(timeout=join_timeout)
    if incidents_path is not None and incidents_path.is_file():
        incidents = []
        for raw in incidents_path.read_text(encoding="utf-8", errors="replace").splitlines():
            try:
                incidents.append(json.loads(raw))
            except Exception:
                continue
        in_window = [
            item for item in incidents
            if isinstance(item.get("t"), (int, float))
            and tracer.result.started_epoch_ms <= item["t"] <= tracer.result.ended_epoch_ms
        ]
        tracer.result.incident_count_in_window = len(in_window)
        tracer.result.long_incident_count_in_window = sum(
            1 for item in in_window if float(item.get("totalMs", 0.0)) > 33.0
        )
        tracer.result.long_frame_entries = parse_trace_output(tracer.result.raw_trace_output)
        tracer.result.upload_entries = parse_trace_output(tracer.result.raw_upload_output)
        tracer.result.update_entries = parse_trace_output(tracer.result.raw_update_output)
        tracer.result.dungeon_entries = parse_trace_output(tracer.result.raw_dungeon_output)
        correlate_trace_incidents(tracer.result.long_frame_entries, in_window)
        correlate_trace_incidents(tracer.result.upload_entries, in_window)
        correlate_trace_incidents(tracer.result.update_entries, in_window)
        correlate_trace_incidents(tracer.result.dungeon_entries, in_window)
        out_dir.mkdir(parents=True, exist_ok=True)
        (out_dir / "arthas-frame-correlation.json").write_text(
            json.dumps({
                "arthasPid": tracer.result.arthas_pid,
                "startedEpochMs": tracer.result.started_epoch_ms,
                "endedEpochMs": tracer.result.ended_epoch_ms,
                "incidentCountInWindow": tracer.result.incident_count_in_window,
                "longIncidentCountInWindow": tracer.result.long_incident_count_in_window,
                "note": "Window correlation only; Arthas stream output has no stable frame-probe event id.",
            }, indent=2),
            encoding="utf-8",
        )
    report = format_report(tracer.result)
    report_path = out_dir / "flush-spike-report.txt"
    out_dir.mkdir(parents=True, exist_ok=True)
    report_path.write_text(report, encoding="utf-8")
    return report, report_path
