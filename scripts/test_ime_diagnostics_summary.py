import contextlib
import io
import json
import tempfile
import unittest
from pathlib import Path
from summarize_ime_diagnostics import percentile, summarize


class SummaryTest(unittest.TestCase):
    def fixture(self, root):
        report = {"samples": [{"mode": mode, "iteration": 0, "fixture": 0, "key": 0,
                  "candidateCount": 2, "candidateHash": "synthetic-hash",
                  "sendReturnNs": 1000000, "candidatesReturnNs": 2000000}
                  for mode in ("none", "hide", "lower")], "mainQueueNs": [1000000, 3000000]}
        for revision in ("baseline", "candidate"):
            for trial in (1, 2):
                (root / f"{revision}-{trial}.json").write_text(json.dumps(report))

    def test_matching_outputs_and_percentiles(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder); self.fixture(root)
            with contextlib.redirect_stdout(io.StringIO()): summarize(root)
            summary = json.loads((root / "summary.json").read_text())
            self.assertTrue(summary["candidate_outputs_equal"])
            self.assertEqual(summary["trials"]["candidate-1"]["mainQueueNs"]["p95_ms"], 3)
        self.assertEqual(percentile(list(range(1, 101)), .95), 95 / 1000000)

    def test_changed_candidate_order_fails(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder); self.fixture(root)
            path = root / "candidate-1.json"; report = json.loads(path.read_text())
            report["samples"][0]["candidateHash"] = "changed"
            path.write_text(json.dumps(report))
            with self.assertRaises(AssertionError): summarize(root)

    def test_missing_probe_fails(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder); self.fixture(root)
            path = root / "candidate-1.json"; report = json.loads(path.read_text())
            report["mainQueueNs"] = []
            path.write_text(json.dumps(report))
            with self.assertRaises(AssertionError): summarize(root)


if __name__ == "__main__": unittest.main()
