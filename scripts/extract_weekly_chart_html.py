#!/usr/bin/env python3
"""Extract weekly supported market HTML chart tables into RE-LAB source-observation CSV.

This parser works on saved HTML snapshots. It never fetches a website and never
turns blank/marker cells into numeric results. Capture time must be supplied by
the caller so archive date and retrieval date cannot be confused.
"""
from __future__ import annotations

import argparse
import csv
import re
import sys
from datetime import date, datetime, timedelta
from html.parser import HTMLParser
from pathlib import Path
from typing import Iterable

CSV_FIELDS = [
    "market", "session_id", "date", "source_id", "retrieved_at",
    "raw_text", "status", "source_record_id", "source_url",
    "published_at", "result",
]


class TableRows(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.rows: list[list[str]] = []
        self._row: list[str] | None = None
        self._cell: list[str] | None = None

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        tag = tag.lower()
        if tag == "tr":
            self._row = []
        elif tag in {"td", "th"} and self._row is not None:
            self._cell = []

    def handle_data(self, data: str) -> None:
        if self._cell is not None:
            self._cell.append(data)

    def handle_endtag(self, tag: str) -> None:
        tag = tag.lower()
        if tag in {"td", "th"} and self._cell is not None and self._row is not None:
            self._row.append(" ".join("".join(self._cell).split()))
            self._cell = None
        elif tag == "tr" and self._row is not None:
            if self._row:
                self.rows.append(self._row)
            self._row = None
            self._cell = None


def _parse_date(value: str) -> date:
    for fmt in ("%d/%m/%y", "%d/%m/%Y", "%d-%m-%Y", "%d-%m-%y"):
        try:
            return datetime.strptime(value, fmt).date()
        except ValueError:
            pass
    raise ValueError(f"Unsupported date in weekly range: {value}")


def _week_range(value: str) -> tuple[date, date] | None:
    matches = re.findall(r"\d{1,2}[/-]\d{1,2}[/-]\d{2,4}", value)
    if len(matches) != 2:
        return None
    try:
        start, end = map(_parse_date, matches)
    except ValueError:
        return None
    if end < start:
        return None
    return start, end


def _result_digits(cell: str) -> str | None:
    """Recognize supported result layouts without harvesting unrelated date/year digits."""
    grouped = re.search(r"(?<!\d)(\d{3})[\s./-]+(\d{2})[\s./-]+(\d{3})(?!\d)", cell)
    if grouped:
        return "".join(grouped.groups())

    compact = re.search(r"(?<!\d)(\d{8})(?!\d)", cell)
    if compact:
        return compact.group(1)

    # Some weekly boards render the three Panna digits individually, then
    # the two Jodi digits, then the three closing Panna digits.
    numeric_tokens = re.findall(r"\d+", cell)
    if [len(token) for token in numeric_tokens] == [1, 1, 1, 2, 1, 1, 1]:
        return "".join(numeric_tokens)
    if [len(token) for token in numeric_tokens] == [1] * 8:
        return "".join(numeric_tokens)
    return None


def _cell_observation(cell: str) -> tuple[str, str]:
    """Return (status, normalized result); raw cell text is stored separately."""
    lowered = cell.casefold()
    holiday = re.search(
        r"\b(?:holiday|closed|market\s+off|no\s+market|weekly\s+off)\b", lowered
    ) is not None
    pending = re.search(
        r"\b(?:pending|awaiting|not\s+declared)\b", lowered
    ) is not None
    result_digits = _result_digits(cell)

    # A marker accompanied by a result-shaped value is contradictory source
    # content; retain it as MALFORMED rather than silently choosing either.
    if holiday or pending:
        if holiday and pending or result_digits is not None:
            return "MALFORMED", ""
        return ("HOLIDAY_CLOSED" if holiday else "PENDING"), ""

    if result_digits is None:
        if not re.search(r"\d", cell):
            return "MISSING_FROM_SOURCE", ""
        return "MALFORMED", ""

    open_panna, jodi, close_panna = (
        result_digits[:3], result_digits[3:5], result_digits[5:]
    )
    expected = f"{sum(map(int, open_panna)) % 10}{sum(map(int, close_panna)) % 10}"
    if jodi != expected:
        return "MALFORMED", ""
    return "PUBLISHED", f"{open_panna}-{jodi}-{close_panna}"


def extract_rows(
    html: str,
    *,
    market: str,
    session_id: str,
    source_id: str,
    retrieved_at: str,
    source_url: str = "",
) -> tuple[list[dict[str, str]], list[str]]:
    market = market.upper()
    supported_markets = {"SRIDEVI", "SRIDEVI_NIGHT", "KALYAN", "TIME_BAZAR", "MAIN_BAZAR"}
    if market not in supported_markets:
        raise ValueError("market must be one of: " + ", ".join(sorted(supported_markets)))
    if not session_id.strip() or not source_id.strip():
        raise ValueError("session_id and source_id are required")
    # A capture time is an instant, so a timezone offset is mandatory.
    captured_at = datetime.fromisoformat(retrieved_at.replace("Z", "+00:00"))
    if captured_at.tzinfo is None or captured_at.utcoffset() is None:
        raise ValueError("retrieved_at must include a UTC offset or Z")

    parser = TableRows()
    parser.feed(html)
    output: list[dict[str, str]] = []
    issues: list[str] = []
    allowed_day_counts = {
        "SRIDEVI": {7}, "SRIDEVI_NIGHT": {7}, "KALYAN": {6},
        "TIME_BAZAR": {7}, "MAIN_BAZAR": {5},
    }[market]

    for row_number, cells in enumerate(parser.rows, start=1):
        if len(cells) < 2:
            continue
        week = _week_range(cells[0])
        if week is None:
            continue
        start, end = week
        day_count = (end - start).days + 1
        if start.weekday() != 0 or day_count not in allowed_day_counts:
            issues.append(f"row {row_number}: week range does not match {market} chart layout: {cells[0]}")
            continue
        one_cell_per_day = len(cells) == day_count + 1
        three_cells_per_day = len(cells) == (day_count * 3) + 1
        if not one_cell_per_day and not three_cells_per_day:
            issues.append(
                f"row {row_number}: date range has {day_count} days but row contains "
                f"{len(cells) - 1} cells; expected one or three cells per day"
            )
            continue

        daily_groups = (
            [[cell] for cell in cells[1:]]
            if one_cell_per_day
            else [cells[index:index + 3] for index in range(1, len(cells), 3)]
        )
        for offset, parts in enumerate(daily_groups):
            chart_date = start + timedelta(days=offset)
            raw_cell = " | ".join(parts)
            combined = " | ".join(parts)
            if one_cell_per_day:
                status, result = _cell_observation(parts[0])
            elif any("*" in part for part in parts):
                status, result = "MISSING_FROM_SOURCE", ""
            elif re.search(r"\b(?:holiday|closed|market\s+off|no\s+market|weekly\s+off|pending|awaiting|not\s+declared)\b", combined, re.IGNORECASE):
                status, result = _cell_observation(combined)
            elif not any(part.strip() for part in parts):
                status, result = "MISSING_FROM_SOURCE", ""
            else:
                open_value, jodi_value, close_value = parts
                open_digits = re.sub(r"\D", "", open_value)
                jodi_digits = re.sub(r"\D", "", jodi_value)
                close_digits = re.sub(r"\D", "", close_value)
                if len(open_digits) == 3 and len(jodi_digits) == 2 and len(close_digits) == 3:
                    status, result = _cell_observation(
                        f"{open_digits} {jodi_digits} {close_digits}"
                    )
                else:
                    status, result = "MALFORMED", ""
            output.append({
                "market": market,
                "session_id": session_id,
                "date": chart_date.isoformat(),
                "source_id": source_id,
                "retrieved_at": retrieved_at,
                "raw_text": raw_cell,
                "status": status,
                # Stable across snapshots even if unrelated table rows are inserted.
                "source_record_id": chart_date.isoformat(),
                "source_url": source_url,
                "published_at": "",
                "result": result,
            })

    if not output:
        issues.append("no weekly chart rows were recognized; verify the saved HTML and table format")
    return output, issues


def write_csv(rows: Iterable[dict[str, str]], output) -> None:
    writer = csv.DictWriter(output, fieldnames=CSV_FIELDS, lineterminator="\n")
    writer.writeheader()
    writer.writerows(rows)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("html_file", type=Path)
    parser.add_argument("--market", required=True, choices=("SRIDEVI", "SRIDEVI_NIGHT", "KALYAN", "TIME_BAZAR", "MAIN_BAZAR"))
    parser.add_argument("--session-id", required=True)
    parser.add_argument("--source-id", required=True)
    parser.add_argument("--retrieved-at", required=True, help="ISO-8601 instant, e.g. 2026-11-17T12:00:00+05:30")
    parser.add_argument("--source-url", default="")
    args = parser.parse_args()

    html = args.html_file.read_text(encoding="utf-8")
    rows, issues = extract_rows(
        html,
        market=args.market,
        session_id=args.session_id,
        source_id=args.source_id,
        retrieved_at=args.retrieved_at,
        source_url=args.source_url,
    )
    write_csv(rows, sys.stdout)
    for issue in issues:
        print(f"WARNING: {issue}", file=sys.stderr)
    return 0 if rows else 2


if __name__ == "__main__":
    raise SystemExit(main())
