import csv
import hashlib
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from audit_archive_manifest import audit_manifest


class ArchiveManifestAuditTest(unittest.TestCase):
    def _fixture(self, root: Path, *, mutate=False, timestamp="2026-11-18T12:00:00+05:30"):
        payload = b"<html><table><tr><td>archived raw chart snapshot</td></tr></table></html>"
        snapshot = root / "archive.html"
        snapshot.write_bytes(payload + (b" changed" if mutate else b""))
        manifest = root / "manifest.csv"
        with manifest.open("w", encoding="utf-8", newline="") as handle:
            writer = csv.DictWriter(handle, fieldnames=[
                "html_file", "market", "session_id", "source_id",
                "source_url", "retrieved_at", "sha256"
            ])
            writer.writeheader()
            writer.writerow({
                "html_file": "archive.html",
                "market": "KALYAN",
                "session_id": "main",
                "source_id": "fixresult",
                "source_url": "https://example.test/kalyan/archive",
                "retrieved_at": timestamp,
                "sha256": hashlib.sha256(payload).hexdigest(),
            })
        return manifest

    def test_accepts_snapshot_with_matching_digest_and_timezone(self):
        with tempfile.TemporaryDirectory() as directory:
            result = audit_manifest(self._fixture(Path(directory)))
            self.assertTrue(result["ok"], result["errors"])
            self.assertEqual(1, result["audited_count"])
            self.assertEqual(70, int(result["audited"][0]["bytes"]))

    def test_rejects_changed_snapshot(self):
        with tempfile.TemporaryDirectory() as directory:
            result = audit_manifest(self._fixture(Path(directory), mutate=True))
            self.assertFalse(result["ok"])
            self.assertIn("SHA-256 mismatch", result["errors"][0])

    def test_rejects_timezone_free_capture_time(self):
        with tempfile.TemporaryDirectory() as directory:
            result = audit_manifest(self._fixture(
                Path(directory), timestamp="2026-11-18T12:00:00"
            ))
            self.assertFalse(result["ok"])
            self.assertIn("UTC offset", result["errors"][0])

    def test_rejects_non_https_source_url(self):
        with tempfile.TemporaryDirectory() as directory:
            manifest = self._fixture(Path(directory))
            rows = list(csv.DictReader(manifest.open(encoding="utf-8")))
            rows[0]["source_url"] = "http://example.test/archive"
            with manifest.open("w", encoding="utf-8", newline="") as handle:
                writer = csv.DictWriter(handle, fieldnames=rows[0].keys())
                writer.writeheader()
                writer.writerows(rows)
            result = audit_manifest(manifest)
            self.assertFalse(result["ok"])
            self.assertIn("HTTPS", result["errors"][0])

    def test_rejects_duplicate_capture_identity(self):
        with tempfile.TemporaryDirectory() as directory:
            manifest = self._fixture(Path(directory))
            rows = list(csv.DictReader(manifest.open(encoding="utf-8")))
            with manifest.open("a", encoding="utf-8", newline="") as handle:
                writer = csv.DictWriter(handle, fieldnames=rows[0].keys())
                writer.writerow(rows[0])
            result = audit_manifest(manifest)
            self.assertFalse(result["ok"])
            self.assertIn("duplicate", result["errors"][0])

    def test_rejects_missing_snapshot(self):
        with tempfile.TemporaryDirectory() as directory:
            manifest = self._fixture(Path(directory))
            (Path(directory) / "archive.html").unlink()
            result = audit_manifest(manifest)
            self.assertFalse(result["ok"])
            self.assertIn("cannot read snapshot", result["errors"][0])


if __name__ == "__main__":
    unittest.main()
