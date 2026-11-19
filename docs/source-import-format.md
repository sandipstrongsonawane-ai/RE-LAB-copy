# Source observation CSV import

The importer loads **source observations**, not final chart rows. It keeps source identity, session identity, retrieval/publication timestamps, and the raw text. Conflicting sources remain separate observations and must pass through source reconciliation before a chart row is analyzed.

## Required headers

`market,session_id,date,source_id,retrieved_at,raw_text,status`

Optional headers:

`source_record_id,source_url,published_at,result`

- `market`: `SRIDEVI` or `KALYAN`
- `session_id`: stable identity such as `main` or `day`; never merge distinct sessions
- `source_id`: canonical publisher ID, not a page URL or page ID; all pages/views from one publisher must reuse the same ID. Use `source_url` to identify the exact page. Independent consensus counts distinct `source_id` values only.
- `date`: ISO date, `YYYY-MM-DD`
- `retrieved_at` and optional `published_at`: ISO-8601 instants; `retrieved_at` must include a UTC offset or `Z`
- `status`: `PUBLISHED`, `PENDING`, `HOLIDAY_CLOSED`, `MISSING_FROM_SOURCE`, or `MALFORMED`
- `raw_text`: exact source text as captured; quoted CSV fields support commas, quotes, and embedded newlines
- `result`: optional normalized `OPN-JJ-CLN` form, e.g. `277-65-249`

A published result is accepted only if its three-digit Pannas and two-digit Jodi are structurally valid and the Jodi matches both Panna digit sums. Reuse the same `source_id` when importing multiple pages from one publisher; different pages from one publisher are not independent corroboration. The weekly HTML extractor recognizes grouped, compact, and digit-spaced result layouts; unrelated year/date digits are not treated as a result. Explicit holiday/pending markers remain non-results, and a marker combined with a result-shaped value is flagged as `MALFORMED`. Extracted `source_record_id` values are based on chart date so inserting unrelated rows into a later page snapshot does not change the record identity. Invalid published rows are retained as `MALFORMED` observations with their raw text; they are not silently corrected. Non-published statuses never become chart results.

Rows that cannot be parsed (for example, invalid dates or timestamps) are reported as row-numbered import issues. Valid rows in the same file are still returned.

## Source publication timing analysis

The Android app can export `relab-source-publication-timing.csv` grouped by market, session, and canonical publisher. It reports observation counts, published rows with/without an explicit `published_at`, negative timestamp-order anomalies, and median/minimum/maximum non-negative capture lag in minutes.

Capture lag is calculated only as `retrieved_at - published_at` when both timestamps are explicitly present on a published observation. Rows without `published_at` are counted as missing; the app does not substitute the chart date, scheduled session time, or retrieval time. Negative lags are counted separately and excluded from non-negative lag statistics because they may indicate a bad timestamp, timezone, or source display convention. Median is the midpoint average for an even sample count. These are observed capture delays, not proof of the exact moment a publisher first made a result public.

## Example

```csv
market,session_id,date,source_id,retrieved_at,raw_text,status,source_record_id,source_url,published_at,result
SRIDEVI,main,2026-11-09,publisher-a,2026-11-09T08:00:00Z,"277-65-249",PUBLISHED,row-1,https://example.test/archive,2026-11-09T08:01:00Z,277-65-249
SRIDEVI,main,2026-11-10,publisher-a,2026-11-10T08:00:00Z,"Result not posted",PENDING,row-2,https://example.test/archive,,
```

This schema does not imply that any historical dataset has already been imported. Source collection and reconciliation remain separate, auditable steps.

## Batch extraction of saved HTML snapshots

For multiple archive pages, create a manifest CSV with these columns:

`html_file,market,session_id,source_id,retrieved_at,source_url`

Paths in `html_file` are resolved relative to the manifest file. Each row represents one saved page snapshot; use a new row for each publisher or retrieval time. Example:

```csv
html_file,market,session_id,source_id,retrieved_at,source_url
snapshots/hira-sridevi.html,SRIDEVI,main,hpofficial,2026-11-17T12:00:00+05:30,https://hpofficial.in/sridevi-panel-chart/
snapshots/fix-kalyan.html,KALYAN,main,fixresult-kalyan,2026-11-17T12:05:00+05:30,https://www.fixresult.in/kalyan-matka-chart.html
```

Run the offline batch extractor:

```bash
python scripts/extract_archive_batch.py data/source-snapshots.csv --output build/source-observations.csv
```

The command does not access the network. Use canonical publisher IDs in `source_id` (for example, `hpofficial`, not a page-specific suffix). It preserves every source snapshot as separate observations. If any manifest entry is invalid or a snapshot is missing, it exits with code 2 and does not write a combined CSV; fix the manifest and rerun it.
