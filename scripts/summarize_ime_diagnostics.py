"""Compare identical synthetic fixtures. Timings are evidence, never a flaky CI speed gate."""
import json
import math
import sys
from pathlib import Path


def percentile(values, p):
    ordered = sorted(values)
    return ordered[max(0, math.ceil(len(ordered) * p) - 1)] / 1_000_000


def summarize(root):
    reports = {(revision, trial): json.loads((root / f"{revision}-{trial}.json").read_text())
               for revision in ("baseline", "candidate") for trial in (1, 2)}
    reference = reports[("baseline", 1)]["samples"]
    keys = ("mode", "iteration", "fixture", "key", "candidateCount", "candidateHash")
    expected = [tuple(row[key] for key in keys) for row in reference]
    assert expected, "No measurements"
    for label, report in reports.items():
        assert [tuple(row[key] for key in keys) for row in report["samples"]] == expected, \
            f"Candidate content/order or fixture mismatch: {label}"
        assert report["mainQueueNs"], f"No main queue probes: {label}"
        assert all(row["candidatesReturnNs"] >= row["sendReturnNs"] >= 0 for row in report["samples"])
    result = {"scope": "Same API35 x86_64 emulator, ABBA fresh-data trials; synthetic engine roundtrip and independent main queue, not touch-to-pixel or phone latency", "candidate_outputs_equal": True, "trials": {}}
    for (revision, trial), report in reports.items():
        metrics = {}
        for mode in ("none", "hide", "lower"):
            rows = [row for row in report["samples"] if row["mode"] == mode]
            metrics[mode] = {field: {"n": len(rows), "p50_ms": percentile([row[field] for row in rows], .50), "p95_ms": percentile([row[field] for row in rows], .95)}
                             for field in ("sendReturnNs", "candidatesReturnNs")}
        metrics["mainQueueNs"] = {"n": len(report["mainQueueNs"]), "p50_ms": percentile(report["mainQueueNs"], .50), "p95_ms": percentile(report["mainQueueNs"], .95)}
        result["trials"][f"{revision}-{trial}"] = metrics
    (root / "summary.json").write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    summarize(Path(sys.argv[1]))
