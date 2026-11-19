# Raw archive snapshot capture and audit

RE-LAB does not silently scrape public chart pages or treat search snippets as raw source data. Capture each page manually or through an approved archival workflow, save the exact response bytes as an HTML file, and record the capture instant at the time it was obtained.

## Manifest

Create a UTF-8 CSV with these exact columns:

`html_file,market,session_id,source_id,source_url,retrieved_at,sha256`

- `html_file`: path relative to the manifest, or an absolute path.
- `market` and `session_id`: exact market/session identity; do not merge day/night sessions.
- `source_id`: canonical publisher identity, shared across that publisher's panel/Jodi/history pages.
- `source_url`: exact HTTPS page URL captured.
- `retrieved_at`: actual capture instant in ISO-8601 with UTC offset, e.g. `2026-11-18T14:25:00+05:30`. Never substitute chart date.
- `sha256`: lowercase SHA-256 digest of the saved HTML bytes.

## Audit before extraction

From the repository root:

```sh
python scripts/audit_archive_manifest.py path/to/manifest.csv
python scripts/audit_archive_manifest.py path/to/manifest.csv --json
```

The audit fails on missing files, changed bytes, malformed digests, non-HTTPS URLs, timezone-free capture timestamps, duplicate source/market/session/capture identities, or incomplete manifest columns.

The batch extractor now runs this provenance audit automatically as a mandatory preflight; it refuses to emit a combined CSV if the manifest, raw bytes, or SHA-256 checks fail. You can still run the audit separately for a readable report. Use the same snapshot manifest with the offline batch extractor:

```sh
python scripts/extract_archive_batch.py path/to/manifest.csv --output build/source-observations.csv
```

The batch extractor still validates the chart table layout and result math. Review every warning and reconcile extracted observations against other publishers before importing. A SHA-256 match proves snapshot integrity after capture; it does not prove that a publisher's result is correct or independent.
