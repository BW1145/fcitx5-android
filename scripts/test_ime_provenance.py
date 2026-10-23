import hashlib
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from record_ime_provenance import record


class ProvenanceTest(unittest.TestCase):
    def test_actual_binary_hashes_relative_paths_and_license_text(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder) / "source"; root.mkdir()
            artifacts = Path(folder) / "artifacts"
            for name in ("scripts/personal-rime-dependencies.json", "gradle/libs.versions.toml",
                         "gradle/wrapper/gradle-wrapper.properties", "LICENSE"):
                path = root / name; path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("fixture license or lock")
            binary = artifacts / "candidate/app.apk"
            binary.parent.mkdir(parents=True); binary.write_bytes(b"test fixture bytes")
            with patch("record_ime_provenance.capture", return_value={"test": True}):
                record(root, artifacts)
            result = json.loads((artifacts / "provenance.json").read_text())
            self.assertEqual(result["apks"], [{"path": "candidate/app.apk", "bytes": 18,
                "sha256": hashlib.sha256(binary.read_bytes()).hexdigest()}])
            self.assertEqual((artifacts / "license-sources/LICENSE").read_text(), "fixture license or lock")
            self.assertIn("candidate/app.apk", (artifacts / "SHA256SUMS").read_text())


if __name__ == "__main__": unittest.main()
