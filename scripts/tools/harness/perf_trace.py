"""
perf_trace — background Arthas sampler for flush spike diagnosis.

Runs two Arthas commands while the game is live:

  stack com.badlogic.gdx.graphics.g2d.SpriteBatch flush -n <N>
      Captures the full Java call stack at each flush() invocation.
      Aggregated into a caller-frequency table: which methods drive the
      most flushes.

  trace com.megacrit.cardcrawl.cards.AbstractCard render -n <N> '#cost > 5'
      Captures per-invocation timing for AbstractCard.render() calls that
      exceed 5 ms.  Reveals slow individual card renders.

Both commands run in a daemon thread so the bench poll loop is not
blocked.  Call start_tracer() after the game reaches READY, and
collect_tracer_report() after the game exits.
"""

from __future__ import annotations

import re
import threading
import time
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
    caller_table: list[CallerEntry] = field(default_factory=list)
    slow_renders: list[SlowRenderEntry] = field(default_factory=list)
    error: str = ""
    duration_s: float = 0.0
    arthas_pid: str = ""
    function_hotspots: list[FunctionHotspot] = field(default_factory=list)


_MAX_STACK_SAMPLES   = 300
_MAX_TRACE_SAMPLES   = 100
_MAX_EXAMPLE_STACKS  = 3
_TRACE_COST_FLOOR_MS = 5      # only capture renders >5 ms
_DEFAULT_DURATION_S  = 90     # sampling window; tracer interrupted after this

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

            # A streaming command owns its shell connection. Run both samplers
            # concurrently so the short autoplay window is shared by stack and
            # trace instead of being consumed serially.
            workers = [
                threading.Thread(
                    target=self._sample_stack_connection,
                    args=(ArthasShell, ConnectorClient),
                    daemon=True,
                    name="arthas-stack-sampler",
                ),
                threading.Thread(
                    target=self._sample_trace_connection,
                    args=(ArthasShell, ConnectorClient),
                    daemon=True,
                    name="arthas-trace-sampler",
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

    def _sample_stack_connection(self, shell_type: Any, connector_type: Any) -> None:
        conn = stream = None
        try:
            conn, stream, shell = self._open_shell(shell_type, connector_type)
            shell.command("options disable-sub-class true")
            self._collect_stacks(shell)
        except Exception as exc:
            self._result.raw_stack_output = f"[error: {exc}]"
            self._append_error(f"Arthas stack failed: {exc}")
        finally:
            self._close_sample_connection(conn, stream)

    def _sample_trace_connection(self, shell_type: Any, connector_type: Any) -> None:
        conn = stream = None
        try:
            conn, stream, shell = self._open_shell(shell_type, connector_type)
            shell.command("options disable-sub-class true")
            self._collect_traces(shell)
        except Exception as exc:
            self._result.raw_trace_output = f"[error: {exc}]"
            self._append_error(f"Arthas trace failed: {exc}")
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

    def _collect_stacks(self, shell: Any) -> None:
        """Run `stack SpriteBatch flush` and save raw output."""
        cmd = (
            f"stack {_FLUSH_CLASS} {_FLUSH_METHOD} -n {_MAX_STACK_SAMPLES}"
        )
        try:
            raw = shell.command(cmd, duration=self._duration_s)
            self._result.raw_stack_output = raw
            self._out_dir.mkdir(parents=True, exist_ok=True)
            (self._out_dir / "arthas-stack-flush.txt").write_text(
                raw, encoding="utf-8", errors="replace"
            )
        except Exception as exc:
            self._result.raw_stack_output = f"[error: {exc}]"

    def _collect_traces(self, shell: Any) -> None:
        """Run `trace AbstractCard render` and save raw output."""
        trace_duration = min(30.0, max(5.0, self._duration_s / 3))
        cmd = (
            f"trace {_RENDER_CLASS} {_RENDER_METHOD} "
            f"-n {_MAX_TRACE_SAMPLES} '#cost > {_TRACE_COST_FLOOR_MS}'"
        )
        try:
            raw = shell.command(cmd, duration=trace_duration)
            self._result.raw_trace_output = raw
            self._out_dir.mkdir(parents=True, exist_ok=True)
            (self._out_dir / "arthas-trace-render.txt").write_text(
                raw, encoding="utf-8", errors="replace"
            )
        except Exception as exc:
            self._result.raw_trace_output = f"[error: {exc}]"


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
    for line in raw.splitlines():
        stripped = line.strip()
        m = root_re.match(stripped)
        if m:
            if current is not None:
                entries.append(current)
            cost_ms = float(m.group(1))
            method  = _shorten_frame(m.group(2).strip())
            current = SlowRenderEntry(method=method, cost_ms=cost_ms)
            continue
        m = sub_re.match(stripped)
        if m and current is not None:
            cost_ms = float(m.group(1))
            method  = _shorten_frame(m.group(2).strip())
            current.subframes.append(f"{cost_ms:.1f}ms  {method}")

    if current is not None:
        entries.append(current)

    return sorted(entries, key=lambda e: e.cost_ms, reverse=True)


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
    frame = frame.strip().replace(":", ".")
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
    """Build a human-readable flush + trace report."""
    callers = parse_stack_output(result.raw_stack_output)
    slow_renders = parse_trace_output(result.raw_trace_output)
    result.function_hotspots = aggregate_trace_hotspots(slow_renders)

    w = 70
    lines: list[str] = []
    lines.append("=" * w)
    lines.append("  Flush Spike Source Analysis  (Arthas stack/trace)")
    if result.arthas_pid:
        lines.append(f"  JVM pid: {result.arthas_pid}")
    lines.append(f"  Sampling window: {result.duration_s:.0f}s")
    if result.error:
        lines.append(f"  [!] {result.error}")
    lines.append("-" * w)

    # ── flush caller table ──────────────────────────────────────────────────
    total_samples = sum(e.count for e in callers)
    lines.append(f"  SpriteBatch.flush() callers  (samples: {total_samples})")
    lines.append("")
    if not callers:
        lines.append("    (no stack samples collected)")
    else:
        col_w = 44
        lines.append(f"    {'caller':<{col_w}}  {'count':>6}  {'%':>5}")
        lines.append(f"    {'-'*col_w}  {'-'*6}  {'-'*5}")
        for entry in callers[:top_n]:
            pct = 100.0 * entry.count / total_samples if total_samples else 0.0
            lines.append(
                f"    {entry.frame:<{col_w}}  {entry.count:>6}  {pct:>4.1f}%"
            )
        if len(callers) > top_n:
            lines.append(f"    ... and {len(callers) - top_n} more callers")

    lines.append("")

    # ── top example stacks ──────────────────────────────────────────────────
    if callers:
        top = callers[0]
        lines.append(f"  Top caller example stack ({top.frame}):")
        if top.stacks:
            for frame in top.stacks[0]:
                lines.append(f"      at {frame}")
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

    # ── slow render table ───────────────────────────────────────────────────
    lines.append(
        f"  Slow AbstractCard.render() calls  (>{_TRACE_COST_FLOOR_MS}ms, samples: {len(slow_renders)})"
    )
    lines.append("")
    if not slow_renders:
        lines.append("    (no slow renders captured)")
    else:
        for entry in slow_renders[:top_n]:
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
) -> tuple[str, Path]:
    """Wait for the tracer, build the report string, write it to disk.

    Returns (report_text, report_path).
    """
    tracer.join(timeout=join_timeout)
    report = format_report(tracer.result)
    report_path = out_dir / "flush-spike-report.txt"
    out_dir.mkdir(parents=True, exist_ok=True)
    report_path.write_text(report, encoding="utf-8")
    return report, report_path
