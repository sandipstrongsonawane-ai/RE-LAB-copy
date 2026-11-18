#!/usr/bin/env python3
"""Audit saved archive snapshots against a provenance manifest (offline only)."""
from __future__ import annotations

import argparse
import csv
import hashlib
import json
import sys
from datetime import datetime
from pathlib import Path
from urllib.parse import urlparse

REQUIRED = (
    "html_file", "market", "session_id", "source_id",
    "source_url", "retrieved_at", "sha256",
)


def audit_manifest(manifest_path: Path) -> dict:
    manifest_path = manifest_path.resolve()
    errors: list[str] = []
    warnings: list[str] = []
    audited: list[dict[str, str]] = []
    seen: set[tuple[str, str, str, str]] = set()

    try:
        with manifest_path.open("r", encoding="utf-8-sig", newline="") as handle:
            reader = csv.DictReader(handle)
            headers = reader.fieldnames or []
            normalized = [h.strip() for h in headers]
            if not headers:
                return {"ok": False, "audited": [], "errors": ["manifest has no header"], "warnings": []}
            if any(not h for h in normalized):
                return {"ok": False, "audited": [], "errors": ["manifest has an empty header"], "warnings": []}
            if len(normalized) != len(set(normalized)):
                return {"ok": False, "audited": [], "errors": ["manifest has duplicate headers"], "warnings": []}
            missing = [name for name in REQUIRED if name not in normalized]
            if missing:
                return {"ok": False, "audited": [], "errors": ["missing required columns: " + ", ".join(missing)], "warnings": []}
            reader.fieldnames = normalized

            for line, row in enumerate(reader, start=2):
                prefix = f"row {line}"
                if None in row:
                    errors.append(f"{prefix}: too many CSV fields")
                    continue
                values = {key: (row.get(key) or "").strip() for key in REQUIRED}
                empty = [key for key, value in values.items() if not value]
                if empty:
                    errors.append(f"{prefix}: empty required fields: " + ", ".join(empty))
                    continue
                try:
                    stamp = datetime.fromisoformat(values["retrieved_at"].replace("Z", "+00:00"))
                    if stamp.tzinfo is None or stamp.utcoffset() is None:
                        raise ValueError("retrieved_at must include UTC offset or Z")
                except ValueError as exc:
                    errors.append(f"{prefix}: {exc}")
                    continue

                parsed_url = urlparse(values["source_url"])
                if parsed_url.scheme != "https" or not parsed_url.netloc:
                    errors.append(f"{prefix}: source_url must be an absolute HTTPS URL")
                    continue

                digest = values["sha256"].lower()
                if len(digest) != 64 or any(ch not in "0123456789abcdef" for ch in digest):
                    errors.append(f"{prefix}: sha256 must be 64 hexadecimal characters")
                    continue

                path = Path(values["html_file"])
                if not path.is_absolute():
                    path = manifest_path.parent / path
                try:
                    payload = path.read_bytes()
                except OSError as exc:
                    errors.append(f"{prefix}: cannot read snapshot {path}: {exc}")
                    continue
                actual = hashlib.sha256(payload).hexdigest()
                if actual != digest:
                    errors.append(f"{prefix}: SHA-256 mismatch for {path.name}")
                    continue

                identity = (
                    values["source_id"], values["market"],
                    values["session_id"], values["retrieved_at"],
                )
                if identity in seen:
                    errors.append(f"{prefix}: duplicate source/market/session/capture identity")
                    continue
                seen.add(identity)
                audited.append({
                    "html_file": str(path),
                    "source_id": values["source_id"],
                    "market": values["market"].upper(),
                    "session_id": values["session_id"],
                    "source_url": values["source_url"],
                    "retrieved_at": values["retrieved_at"],
                    "sha256": actual,
                    "bytes": str(len(payload)),
                })
    except OSError as exc:
        errors.append(f"cannot read manifest {manifest_path}: {exc}")

    if not audited and not errors:
        errors.append("manifest contains no snapshot rows")
    return {
        "ok": not errors,
        "audited": audited,
        "errors": errors,
        "warnings": warnings,
        "audited_count": len(audited),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest_csv", type=Path)
    parser.add_argument("--json", action="store_true", help="emit machine-readable JSON")
    args = parser.parse_args()
    result = audit_manifest(args.manifest_csv)
    if args.json:
        print(json.dumps(result, indent=2, sort_keys=True))
    else:
        print(f"Audited snapshots: {result.get('audited_count', 0)}")
        for warning in result["warnings"]:
            print("WARNING: " + warning, file=sys.stderr)
        for error in result["errors"]:
            print("ERROR: " + error, file=sys.stderr)
        print("STATUS: " + ("PASS" if result["ok"] else "FAIL"))
    return 0 if result["ok"] else 2


if __name__ == "__main__":
    raise SystemExit(main())
