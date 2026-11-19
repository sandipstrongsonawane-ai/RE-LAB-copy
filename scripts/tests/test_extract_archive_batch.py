import csv
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from extract_archive_batch import extract_manifest


WEEKLY_HTML = """
<table><tr><td>24-08-2026 to 29-08-2026</td>
<td>668</td><td>07</td><td>449</td><td>470</td><td>17</td><td>179</td><td>247</td><td>33</td><td>490</td>
<td>568</td><td>92</td><td>345</td><td>247</td><td>32</td><td>480</td><td>168</td><td>50</td><td>145</td></tr></table>
"""


class ArchiveBatchExtractorTest(unittest.TestCase):
    def test_combines_multiple_local_snapshots_and_preserves_source_identity(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "hira.html").write_text(WEEKLY_HTML, encoding="utf-8")
            (root / "fixresult.html").write_text(WEEKLY_HTML, encoding="utf-8")
            manifest = root / "manifest.csv"
            with manifest.open("w", encoding="utf-8", newline="") as handle:
                writer = csv.DictWriter(
                    handle,
                    fieldnames=[
                        "html_file", "market", "session_id", "source_id",
                        "retrieved_at", "source_url"
                    ],
                )
                writer.writeheader()
                writer.writerow({
                    "html_file": "hira.html", "market": "KALYAN",
                    "session_id": "main", "source_id": "hira",
                    "retrieved_at": "2026-11-17T12:00:00Z",
                    "source_url": "https://example.test/hira"
                })
                writer.writerow({
                    "html_file": "fixresult.html", "market": "KALYAN",
                    "session_id": "main", "source_id": "fixresult",
                    "retrieved_at": "2026-11-17T12:05:00Z",
                    "source_url": "https://example.test/fixresult"
                })

            rows, warnings, errors = extract_manifest(manifest)

            self.assertEqual([], warnings)
            self.assertEqual([], errors)
            self.assertEqual(12, len(rows))
            self.assertEqual({"hira", "fixresult"}, {row["source_id"] for row in rows})
            self.assertEqual(
                {"2026-08-24", "2026-08-25", "2026-08-26",
                 "2026-08-27", "2026-08-28", "2026-08-29"},
                {row["date"] for row in rows}
            )
            self.assertEqual(
                {"https://example.test/hira", "https://example.test/fixresult"},
                {row["source_url"] for row in rows}
            )

    def test_missing_snapshot_is_an_error_and_is_reported_without_silent_success(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "present.html").write_text(WEEKLY_HTML, encoding="utf-8")
            manifest = root / "manifest.csv"
            manifest.write_text(
                "html_file,market,session_id,source_id,retrieved_at\n"
                "present.html,KALYAN,main,archive,2026-11-17T12:00:00Z\n"
                "missing.html,KALYAN,main,archive,2026-11-17T12:00:00Z\n",
                encoding="utf-8",
            )

            rows, warnings, errors = extract_manifest(manifest)

            self.assertEqual(6, len(rows))
            self.assertEqual([], warnings)
            self.assertEqual(1, len(errors))
            self.assertIn("missing.html", errors[0])

    def test_manifest_requires_all_identity_and_capture_columns(self):
        with tempfile.TemporaryDirectory() as directory:
            manifest = Path(directory) / "manifest.csv"
            manifest.write_text(
                "html_file,market,source_id\nfile.html,KALYAN,archive\n",
                encoding="utf-8",
            )
            rows, warnings, errors = extract_manifest(manifest)
            self.assertEqual([], rows)
            self.assertEqual([], warnings)
            self.assertEqual(1, len(errors))
            self.assertIn("session_id", errors[0])
            self.assertIn("retrieved_at", errors[0])

    def test_manifest_rejects_timezone_free_capture_timestamp(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "archive.html").write_text(WEEKLY_HTML, encoding="utf-8")
            manifest = root / "manifest.csv"
            manifest.write_text(
                "html_file,market,session_id,source_id,retrieved_at\n"
                "archive.html,KALYAN,main,archive,2026-11-17T12:00:00\n",
                encoding="utf-8",
            )
            rows, warnings, errors = extract_manifest(manifest)
            self.assertEqual([], rows)
            self.assertEqual([], warnings)
            self.assertEqual(1, len(errors))
            self.assertIn("UTC offset", errors[0])

    def test_manifest_rejects_duplicate_headers(self):
        with tempfile.TemporaryDirectory() as directory:
            manifest = Path(directory) / "manifest.csv"
            manifest.write_text(
                "html_file,market,market,session_id,source_id,retrieved_at\n",
                encoding="utf-8",
            )
            rows, warnings, errors = extract_manifest(manifest)
            self.assertEqual([], rows)
            self.assertEqual([], warnings)
            self.assertEqual(1, len(errors))
            self.assertIn("duplicate", errors[0])

    def test_manifest_with_bom_and_relative_paths_is_supported(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "archive.html").write_text(WEEKLY_HTML, encoding="utf-8")
            (root / "manifest.csv").write_text(
                "\ufeffhtml_file,market,session_id,source_id,retrieved_at\n"
                "archive.html,KALYAN,main,archive,2026-11-17T12:00:00Z\n",
                encoding="utf-8",
            )
            rows, warnings, errors = extract_manifest(root / "manifest.csv")
            self.assertEqual([], warnings)
            self.assertEqual([], errors)
            self.assertEqual(6, len(rows))

    def test_manifest_trims_header_whitespace_before_mapping_rows(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "archive.html").write_text(WEEKLY_HTML, encoding="utf-8")
            manifest = root / "manifest.csv"
            manifest.write_text(
                " html_file , market , session_id , source_id , retrieved_at \n"
                "archive.html,KALYAN,main,archive,2026-11-17T12:00:00Z\n",
                encoding="utf-8",
            )
            rows, warnings, errors = extract_manifest(manifest)
            self.assertEqual([], warnings)
            self.assertEqual([], errors)
            self.assertEqual(6, len(rows))

    def test_manifest_rejects_headers_that_duplicate_after_whitespace_trim(self):
        with tempfile.TemporaryDirectory() as directory:
            manifest = Path(directory) / "manifest.csv"
            manifest.write_text(
                "html_file,market, market ,session_id,source_id,retrieved_at\n",
                encoding="utf-8",
            )
            rows, warnings, errors = extract_manifest(manifest)
            self.assertEqual([], rows)
            self.assertEqual([], warnings)
            self.assertEqual(1, len(errors))
            self.assertIn("duplicate", errors[0])

    def test_manifest_rejects_empty_header_names(self):
        with tempfile.TemporaryDirectory() as directory:
            manifest = Path(directory) / "manifest.csv"
            manifest.write_text(
                "html_file,market,,session_id,source_id,retrieved_at\n",
                encoding="utf-8",
            )
            rows, warnings, errors = extract_manifest(manifest)
            self.assertEqual([], rows)
            self.assertEqual([], warnings)
            self.assertEqual(1, len(errors))
            self.assertIn("empty column", errors[0])


if __name__ == "__main__":
    unittest.main()
