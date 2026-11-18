# External archive spot-check — 18 November 2026

## Scope and limitation

This is a small, manually cross-checked public-archive spot-check to validate the RE-LAB parsing/math assumptions. It is **not** the production historical dataset, not a complete archive download, and not a chronological walk-forward evaluation. Search-indexed archive content was available for the rows below; raw HTML snapshots and exact retrieval timestamps have not yet been captured. Therefore these rows are **not imported into production history** and are not treated as app-level multi-source consensus.

## Sridevi — matching records

The following four dated rows agree between the MatkaFlash Sridevi panel archive and the Kalyan Chart Sridevi result history:

| Date (IST) | Open Panna | Jodi | Close Panna | Jodi from Panna sums |
|---|---:|---:|---:|---:|
| 2026-11-09 | 277 | 65 | 249 | 65 |
| 2026-11-10 | 578 | 07 | 368 | 07 |
| 2026-11-11 | 800 | 81 | 137 | 81 |
| 2026-11-12 | 566 | 73 | 148 | 73 |

Sources:
- https://matkaflash.com/sridevi/panel-chart
- https://kalyanchart.cyou/sridevi-history/

## Kalyan — matching records

The following four dated rows agree between the FixResult Kalyan chart and the Rama567 Kalyan panel archive:

| Date (IST) | Open Panna | Jodi | Close Panna | Jodi from Panna sums |
|---|---:|---:|---:|---:|
| 2026-09-21 | 679 | 24 | 455 | 24 |
| 2026-09-22 | 169 | 65 | 140 | 65 |
| 2026-09-23 | 489 | 11 | 146 | 11 |
| 2026-09-24 | 235 | 05 | 140 | 05 |

Sources:
- https://www.fixresult.in/kalyan-matka-chart.html
- https://rama567app.com/chart/pana/Kalyan

## Checks performed

- 8/8 rows have three-digit Open Panna and Close Panna values.
- 8/8 rows have two-character Jodi values, preserving leading zeroes (07, 05).
- 8/8 rows satisfy open_ank = sum(open_panna digits) % 10, close_ank = sum(close_panna digits) % 10, and jodi = open_ank followed by close_ank.
- No source disagreement is observed among these eight matched rows.
- This small sample is suitable only as a parser/math spot-check. It is too small and too short to support pattern claims or backtest conclusions.

## Discrepancy requiring follow-up

For the Sridevi week of 2026-11-02 to 2026-11-08, the MatkaFlash weekly snippet displays blank cells for 2026-11-05 and 2026-11-06, while Kalyan Chart's daily history lists published rows for those dates (249-53-157 and 168-59-135). A blank in one archive is **not** evidence of a conflicting result or a holiday. Preserve the source-specific missing state until raw snapshots and publisher provenance are verified.

## Acceptance status

- Public archive spot-check: **PASS (8 rows only)**.
- Full archive capture with immutable raw snapshots and retrieval timestamps: **NOT DONE**.
- Publisher identity verification for independent corroboration: **NOT DONE**.
- Full historical coverage, reconciliation, and conflict audit: **NOT DONE**.
- Chronological walk-forward validation with untouched holdout and baselines: **NOT DONE**.
- Production import: **NOT DONE**.

Do not copy these spot-check rows into the production chart database from this note. Import must use the normal source-observation CSV flow with captured raw evidence and provenance.
