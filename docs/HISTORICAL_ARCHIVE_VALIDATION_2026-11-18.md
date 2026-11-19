# Historical archive validation — 18 November 2026

## Executed validation scope

A chronological integration-test dataset was transcribed from two publicly indexed Kalyan panel-chart archive excerpts:

- MatkaFlash Kalyan 2024: https://matkaflash.com/kalyan/panel-chart/2024
- BharatMatka Kalyan panel archive: https://bharatmatka.si/charts/main/kalyan-panel-chart/

The selected continuous window is 4 November 2024 through 4 January 2025 (nine Monday–Saturday weeks). It contains 54 scheduled date slots, 52 published historical results, and two source-marked missing dates. Both archive excerpts show the same values for these selected weeks.

**Important provenance qualification:** the evidence available for this validation was search-indexed page text, not downloaded raw HTML snapshots. The two URLs are separate domains, but common ownership, data syndication, and publisher independence have not been independently established. The test therefore checks agreement between two archive observations; it must not be presented as conclusive independent-publisher consensus or a complete production archive import. Its fixed observation timestamp is a test clock, not the real retrieval time.

## Automated test assertions

`HistoricalArchiveWalkForwardValidationTest` verifies:

- 108 source observations (54 dates × 2 archive observations).
- 52 dates reconcile to one agreed result; two dates remain missing, never coerced to zero.
- No conflicting or single-source groups in this selected excerpt.
- All 52 published results pass three-digit Panna / two-digit Jodi structure and Jodi-from-Panna-sums validation.
- Chronological pattern analysis produces 51 strictly-prior walk-forward rows.
- Open and close candidate evaluation each scores 47 dates after requiring five strictly earlier consensus rows; the first five are correctly marked insufficient-history.
- Adding a future-dated record does not change an earlier cutoff evaluation.

## Result interpretation

This is a real historical-data integration sample and a materially stronger validation than the earlier eight-row parser spot-check. It exercises reconciliation, missing-state preservation, mathematical validation, chronological baselines, and candidate walk-forward code.

It is **not** a full-history validation of all 1,241 Kalyan archive records, nor does it validate Sridevi's full archive. Raw HTML capture with exact retrieval timestamps, complete archive ingestion, publisher-ownership/mirroring audit, full conflict reconciliation, and a larger untouched chronological holdout remain outstanding. Do not describe the selected sample as a complete archive or use its results to claim predictive advantage.
