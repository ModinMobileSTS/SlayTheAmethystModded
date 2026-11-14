"""Summarize exported instrumentation logs, offline; never operates a device."""

import argparse
import json
from collections import defaultdict
from pathlib import Path
from statistics import median


def parse(text):
    if "INSTRUMENTATION_CODE: -1" not in text or "Delta smoke PASS" not in text:
        raise ValueError("Instrumentation did not finish successfully")
    rows = []
    for line in text.splitlines():
        prefix = "INSTRUMENTATION_STATUS: row="
        if line.startswith(prefix):
            row = json.loads(line[len(prefix):])
            if row.get("schema") != 1 or row.get("verified") is not True:
                raise ValueError("Unverified or unknown row")
            rows.append(row)
    if not rows:
        raise ValueError("No benchmark rows")
    keys = [(r["engine"], r["fixture"], r["baseBytes"], r["iteration"]) for r in rows]
    if len(keys) != len(set(keys)):
        raise ValueError("Duplicate observations in a run")
    return rows


def summarize(rows):
    groups = defaultdict(list)
    for row in rows:
        if not row["warmup"]:
            groups[(row["engine"], row["fixture"], row["baseBytes"])].append(row)
    result = []
    for (engine, fixture, size), samples in sorted(groups.items()):
        for key in ("baseSha256", "targetSha256", "patchBytes", "fingerprint", "abi"):
            if len({r[key] for r in samples}) != 1:
                raise ValueError("Inconsistent sample identity/configuration: " + key)
        result.append({
            "engine": engine, "fixture": fixture, "baseBytes": size,
            "samples": len(samples), "patchBytes": samples[0]["patchBytes"],
            "generateMedianMs": median(r["generateNs"] for r in samples) / 1e6,
            "applyMedianMs": median(r["applyAndSyncNs"] for r in samples) / 1e6,
            "applyMinMs": min(r["applyAndSyncNs"] for r in samples) / 1e6,
            "applyMaxMs": max(r["applyAndSyncNs"] for r in samples) / 1e6,
            "processCumulativeRssHighWaterKb": max(r["vmHwmKb"] for r in samples),
        })
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("logs", type=Path, nargs="+")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    rows = [row for path in args.logs for row in parse(path.read_text())]
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output / "observations.jsonl").write_text("".join(json.dumps(r) + "\n" for r in rows))
    summary = summarize(rows)
    (args.output / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")
    print(json.dumps(summary, indent=2))


if __name__ == "__main__":
    main()
