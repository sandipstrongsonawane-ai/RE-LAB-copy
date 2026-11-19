#!/usr/bin/env python3
"""Batch-extract saved weekly chart HTML snapshots using a CSV manifest.

Offline by design: every HTML file must already exist locally. If any manifest
entry cannot be processed, the CLI exits non-zero and writes no combined CSV.
"""
from __future__ import annotations

import argparse
import csv
import sys
from pathlib import Path

from extract_weekly_chart_html import extract_rows, write_csv
from audit_archive_manifest import audit_manifest

MANIFEST_FIELDS = (
    "html_file", "market", "session_id", "source_id", "retrieved_at"
)


def _validate_capture_timestamp(value: str) -> None:
    from datetime import datetime
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if parsed.tzinfo is None or parsed.utcoffset() is None:
        raise ValueError("retrieved_at must include a UTC offset or Z")


def extract_manifest(
    manifest_path: Path,
) -> tuple[list[dict[str, str]], list[str], list[str]]:
    """Return (observations, warnings, errors) for a snapshot manifest."""
    observations: list[dict[str, str]] = []
    warnings: list[str] = []
    errors: list[str] = []
    manifest_path = manifest_path.resolve()

    try:
        with manifest_path.open("r", encoding="utf-8-sig", newline="") as handle:
            reader = csv.DictReader(handle)
            if reader.fieldnames is None:
                return [], [], ["manifest is empty or has no CSV header"]

            # Normalize harmless surrounding whitespace once, before DictReader
            # creates row dictionaries. This also catches duplicates such as
            # "market" and " market " instead of treating one as a missing key.
            headers = [name.strip() if name is not None else "" for name in reader.fieldnames]
            if any(not name for name in headers):
                return [], [], ["manifest contains an empty column name"]
            if len(headers) != len(set(headers)):
                return [], [], ["manifest contains duplicate column names"]
            missing = [field for field in MANIFEST_FIELDS if field not in headers]
            if missing:
                return [], [], [
                    "manifest is missing required columns: " + ", ".join(missing)
                ]
            reader.fieldnames = headers

            snapshot_count = 0
            for row_number, row in enumerate(reader, start=2):
                snapshot_count += 1
                prefix = f"manifest row {row_number}"
                if None in row:
                    errors.append(f"{prefix}: too many CSV fields")
                    continue

                values = {key: (row.get(key) or "").strip() for key in MANIFEST_FIELDS}
                empty = [key for key, value in values.items() if not value]
                if empty:
                    errors.append(
                        f"{prefix}: empty required fields: {', '.join(empty)}"
                    )
                    continue

                try:
                    _validate_capture_timestamp(values["retrieved_at"])
                    html_path = Path(values["html_file"])
                    if not html_path.is_absolute():
                        html_path = manifest_path.parent / html_path
                    html = html_path.read_text(encoding="utf-8-sig")
                    rows, snapshot_issues = extract_rows(
                        html,
                        market=values["market"],
                        session_id=values["session_id"],
                        source_id=values["source_id"],
                        retrieved_at=values["retrieved_at"],
                        source_url=(row.get("source_url") or "").strip(),
                    )
                except (OSError, UnicodeError, ValueError) as error:
                    errors.append(f"{prefix}: {error}")
                    continue

                warnings.extend(
                    f"{prefix} ({html_path.name}): {issue}"
                    for issue in snapshot_issues
                )
                if not rows:
                    errors.append(
                        f"{prefix}: no observations extracted from {html_path}"
                    )
                    continue
                observations.extend(rows)

            if snapshot_count == 0:
                errors.append("manifest contains no snapshot rows")
    except OSError as error:
        errors.append(f"cannot read manifest {manifest_path}: {error}")

    return observations, warnings, errors


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest_csv", type=Path)
    parser.add_argument(
        "--output", type=Path,
        help="combined source-observation CSV path (default: stdout)"
    )
    args = parser.parse_args()

    # Provenance is a mandatory CLI gate: never emit a production batch from
    # snapshots whose bytes/source identity have not passed the SHA-256 audit.
    audit = audit_manifest(args.manifest_csv)
    if not audit["ok"]:
        for error in audit["errors"]:
            print(f"PROVENANCE ERROR: {error}", file=sys.stderr)
        return 2

    rows, warnings, errors = extract_manifest(args.manifest_csv)
    for warning in warnings:
        print(f"WARNING: {warning}", file=sys.stderr)
    for error in errors:
        print(f"ERROR: {error}", file=sys.stderr)
    if errors:
        return 2

    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        with args.output.open("w", encoding="utf-8", newline="") as output:
            write_csv(rows, output)
    else:
        write_csv(rows, sys.stdout)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
