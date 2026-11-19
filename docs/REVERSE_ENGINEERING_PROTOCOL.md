# Reverse Engineering Protocol

This repository is the research instrument. Pattern claims must be generated from reproducible code over reconciled historical records, not manually inferred from a small recent sample.

## 1. Questions the engine must answer

For each market/session independently:

1. What is the exact raw record structure and which source published each observation?
2. Which result states exist: published, pending, holiday/closed, missing from source, conflicting, malformed?
3. At what actual timestamp did each source first publish or update a result? Keep this distinct from scheduled session time.
4. How are Panna and Jodi mathematically related? Verify every record and retain invalid observations for audit rather than silently dropping them.
5. Which exact-digit and digit-family structures occur, and how do their frequencies differ?
6. Which patterns repeat, how long are repeat gaps, and do transitions vary by weekday or market?
7. Do any discovered relationships survive chronological out-of-sample testing?

## 2. Feature catalogue

Compute and retain independent feature columns for:

- Exact digits and digit positions (hundreds/tens/units).
- User-defined family digits: F0={0,5}, F1={1,6}, F2={2,7}, F3={3,8}, F4={4,9}; family code = digit % 5.
- Ordered 3-position family sequences (125 possible sequences per Panna side).
- Panna digit sum, ank, SP/DP/TP class, sorted digits, pair locations, doubles/triples, and reverse-Panna relation.
- Open-to-Close exact and family relationships.
- Jodi digits, Jodi family derivation, and leading-zero-safe string representation.
- Weekday, inter-result gaps, repeat intervals, source publication/update delays, and source disagreement.

Do not treat Jodi family as independent evidence: it is derived from the Panna family sums.

## 3. Pattern discovery

For each feature and market/session, calculate:

- Absolute count, denominator, percentage, and missing-data coverage.
- Conditional frequency by prior state, weekday, and relevant feature groups.
- Transition counts and row-normalized transition rates.
- Repeat-gap distributions and run lengths.
- Exact-digit results alongside family-collapsed results.
- Source-specific results alongside reconciled results.

Use explicit minimum-support thresholds and always show sample size. A pattern with a high percentage but tiny support must be labelled low-confidence.

## 4. Leakage-free evaluation

- Sort by actual result date; resolve multiple same-date observations by market/session identity, never by arbitrary row order.
- For each test date, train only on records strictly earlier than that date.
- Freeze the feature definitions before evaluating a test segment.
- Compare every candidate pattern against simple baselines (global frequency, weekday frequency, and previous-state frequency).
- Report coverage, support, hit rate where a target definition is legitimate, baseline difference, and uncertainty intervals.
- Use rolling/expanding chronological folds. Never random-shuffle time series.
- Keep a final untouched chronological holdout.
- If a rule is selected using the test segment, that segment is no longer an unbiased test.

## 5. Source and data integrity

Each raw observation needs market, session, date, source identity, source URL or archive key, retrieval time, publication/update time when available, raw text, parsed values, and a status. Conflicting observations remain separate until a documented reconciliation decision is made. A missing source row is not a zero and not proof of a holiday. Stale repeated rows must be detected and flagged, not copied into daily results.

## 6. Required research outputs

The app must eventually expose:

- Source coverage and conflict register.
- Per-market chronology with missing/holiday/pending states.
- Exact and family frequency tables.
- Panna/Jodi mathematical validation report.
- 5x5 family transition matrices and 125-state Panna-family transition analysis.
- Open/Close relation analysis, weekday breakdown, and repeat-gap analysis.
- Walk-forward evaluation with baseline comparisons.
- Exportable machine-readable results and a reproducibility manifest.

## 7. Acceptance criteria

A research result is not accepted unless it has: dataset version/hash, source provenance, date range, market/session identity, sample size, method/version, chronological split, baseline comparison, and reproducible test output. No guaranteed prediction or winning-number language is permitted.

## Reconciliation confidence rule

A date/session is labeled `CONSENSUS` only when at least two distinct `source_id` publishers have published observations and every published value agrees. Repeated snapshots from one publisher do not count as independent confirmation. A valid result seen from only one publisher is labeled `SINGLE_SOURCE_UNVERIFIED` and is excluded from pattern-analysis records. Conflicting published values remain `CONFLICT`; pending, holiday, and missing-only groups remain without a published result. A source with a pending or missing observation does not corroborate another publisher's result.

## Publisher identity and independent corroboration

`source_id` is a canonical publisher ID, not a page URL or a page-specific label. Every archive page and table view from the same publisher must reuse that ID; `source_url` records the exact page. The app counts distinct publisher IDs, not distinct URLs or retrieval snapshots, when deciding whether a result has independent multi-source consensus. For example, the Hira Panna Official Sridevi panel and Jodi pages both use `hpofficial` and cannot corroborate one another as separate publishers.

## Coverage labels

The historical coverage audit distinguishes a result seen from one publisher (`SINGLE_SOURCE_UNVERIFIED`) from a matching result independently published by at least two canonical publisher IDs (`CONSENSUS`). Both count as dates with published observations, but only the latter is corroborated. Multiple snapshots or pages with the same publisher ID count once. Different published values remain `CONFLICT`.
