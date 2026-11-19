import contextlib
import csv
import hashlib
import io
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from extract_archive_batch import main


WEEKLY_HTML = """<table><tr><td>24-08-2026 to 29-08-2026</td>
<td>668</td><td>07</td><td>449</td><td>470</td><td>17</td><td>179</td><td>247</td><td>33</td><td>490</td>
<td>568</td><td>92</td><td>345</td><td>247</td><td>32</td><td>480</td><td>168</td><td>50</td><td>145</td></tr></table>"""


class BatchProvenanceGateTest(unittest.TestCase):
    def _write_manifest(self, root: Path, *, include_provenance: bool) -> Path:
        snapshot = root / "archive.html"
        snapshot.write_text(WEEKLY_HTML, encoding="utf-8")
        fields = ["html_file", "market", "session_id", "source_id", "retrieved_at"]
        row = {
            "html_file": "archive.html",
            "market": "KALYAN",
            "session_id": "main",
            "source_id": "publisher-a",
            "retrieved_at": "2026-11-18T12:00:00+05:30",
        }
        if include_provenance:
            fields += ["source_url", "sha256"]
            row["source_url"] = "https://example.test/kalyan"
            row["sha256"] = hashlib.sha256(snapshot.read_bytes()).hexdigest()
        manifest = root / "manifest.csv"
        with manifest.open("w", encoding="utf-8", newline="") as handle:
            writer = csv.DictWriter(handle, fieldnames=fields)
            writer.writeheader()
            writer.writerow(row)
        return manifest

    def test_cli_refuses_batch_when_provenance_columns_are_missing(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest = self._write_manifest(root, include_provenance=False)
            output = root / "observations.csv"
            stderr = io.StringIO()
            with patch("sys.argv", ["extract_archive_batch.py", str(manifest), "--output", str(output)]):
                with contextlib.redirect_stderr(stderr):
                    code = main()
            self.assertEqual(2, code)
            self.assertFalse(output.exists())
            self.assertIn("source_url", stderr.getvalue())
            self.assertIn("sha256", stderr.getvalue())

    def test_cli_extracts_only_after_valid_snapshot_digest(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest = self._write_manifest(root, include_provenance=True)
            output = root / "observations.csv"
            with patch("sys.argv", ["extract_archive_batch.py", str(manifest), "--output", str(output)]):
                code = main()
            self.assertEqual(0, code)
            self.assertTrue(output.exists())
            with output.open("r", encoding="utf-8", newline="") as handle:
                rows = list(csv.DictReader(handle))
            self.assertEqual(6, len(rows))
            self.assertEqual({"publisher-a"}, {row["source_id"] for row in rows})


if __name__ == "__main__":
    unittest.main()
