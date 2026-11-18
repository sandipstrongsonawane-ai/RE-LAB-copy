# Historical source register

This register lists candidate public archives for source-observation collection. These are independent third-party chart pages, not official market feeds. A page's own archive count or "verified" label is not independent proof of correctness.

## Sridevi — main session

| Source ID | URL | Coverage / notes | Collection rule |
|---|---|---|---|
| `hpofficial` | https://hpofficial.in/sridevi-panel-chart/ | Weekly Open Panna / Jodi / Close Panna table. The current-year page and cached page text can show different end dates; record the exact retrieved snapshot. | Preserve the full panel row and source capture time. Do not infer blank cells as holidays. |
| `hpofficial` | https://hpofficial.in/sridevi-jodi-chart/ | Weekly Jodi table; a second view of the same publisher's archive, not an independent publisher. | Reuse source ID `hpofficial`; compare Jodi against the panel's Panna digit sums. |
| `livesattamatka` | https://livesattamatka.org/panel-record/sridevi | Full panel chart, Monday–Sunday; recent rows are visible in the page text. | Keep as an independent source observation; reconcile against other publishers. |
| `matkaflash` | https://matkaflash.com/sridevi?tab=history | Archive states 2,123 dated records from 4 Jan 2021. Its 17 Nov 2026 board snapshot displayed 577-99-667 while another current-year source displayed 115-73-229. | Preserve the dated board value and capture timestamp as an observation; do not choose a winner from page recency alone. |

## Kalyan — main evening session

| Source ID | URL | Coverage / notes | Collection rule |
|---|---|---|---|
| `fixresult` | https://www.fixresult.in/kalyan-matka-chart.html | Weekly Mon–Sat table; archive overview states 1,221 records from 31 Oct 2022 through 24 Sep 2026. | Useful historical baseline; preserve missing cells and anomalous rows. |
| `matkaflash` | https://matkaflash.com/kalyan | Archive states 1,240 dated records from 17 Oct 2022. A 16 Nov 2026 board snapshot displayed 390-24-257, which conflicts with other recent chart observations. | Keep the 16 Nov value as source-specific evidence; do not merge it into another date or session without reconciliation. |
| `kalyanchart` | https://kalyanchart.cyou/history/ | Separate history pages for Kalyan and other day/night sessions. Some archive views have shown stale repeated values. | Verify date and session identity row-by-row; repeated rows are not automatically daily results. |

## Session identity rules

- Sridevi main, Sridevi Day, Sridevi Morning, and Sridevi Night are separate sessions.
- Kalyan evening and Kalyan Night are separate sessions.
- A publisher's weekly table, Jodi table, and panel table are different views from the same publisher, not three independent sources.
- `source_id` is the canonical publisher identity, not a page identity. Reuse one `source_id` for every page/view owned by the same publisher; distinguish the exact page with `source_url`. Independent-source consensus counts distinct publisher IDs only.
- In particular, Hira Panna Official's Sridevi panel and Jodi pages both use `source_id=hpofficial`; they must never count as two independent publishers.
- MatkaFlash Sridevi and Kalyan pages both use `source_id=matkaflash`; market/session fields keep their records separate.
- The same canonical rule applies to `livesattamatka`, `fixresult`, and `kalyanchart`; do not add market or page suffixes to a publisher ID.
- Preserve original page URL, capture/retrieval time, published time when displayed, exact raw row text, and parsed result separately.
- A missing cell, `—`, `*`, or `**` is not a numeric result. Do not convert it to 00 or a holiday without an explicit source statement.

## Known reconciliation examples to keep visible

- Sridevi 17 Nov 2026: Hira Panna's current-year result view reported `115-73-229`; MatkaFlash's board snapshot showed `577-99-667`. Treat these as conflicting source observations until exact date/session snapshots are verified. A cached/lagging archive may have stale date labels.
- Kalyan 24 Aug 2026: FixResult records `668-07-449`; this is mathematically valid because open sum is 20 and close sum is 17.
- Kalyan 16 Nov 2026: MatkaFlash displayed `390-24-257`; other archive observations have reported a different value. Keep both raw observations and check session/date before analysis.

## Data ingestion status

This register is a collection plan and reconciliation aid, **not proof that these archives have been downloaded into the app**. Only observations present in an imported source CSV are counted by the app's coverage audit or pattern analysis. Record the actual retrieval instant and do not backdate a current scrape to the chart date.


## Newly discovered archive candidates — 18 Nov 2026

These additional pages surfaced during historical-coverage research. They are recorded as **disabled candidates** in the source catalog, not active ingestion sources, because search-index excerpts are not raw HTML snapshots and have not yet passed fixture-based extraction and cross-publisher reconciliation.

| Candidate source ID | Market/session | Archive URL | Search-index evidence | Gate before enabling |
|---|---|---|---|---|
| `dlbosss` | Sridevi / main | https://www.dlbosss.com/panel-chart-record/sridevi.php | Weekly panel rows covering 2024–2026 are visible in indexed page text. | Capture raw HTML; verify week boundaries, 7-day cells, missing markers and panna/jodi math. |
| `realmatka` | Sridevi / main | https://www.realmatka.in/panel-chart-record/sridevi | Indexed excerpt includes dated weekly rows from 2024. | Capture raw HTML; fixture-test parser and compare dates/results against existing independent publishers. |
| `ratan567official` | Kalyan / main | https://ratan567official.com/kalyan/pana-chart/ | Page excerpt reports 303 dated results from 24 Sep 2025 to 23 Sep 2026. | Capture raw HTML; confirm Kalyan evening session identity and test calendar/missing-cell behavior. |

Do not count these candidates toward independent-source consensus or historical coverage until their raw snapshots are imported. Different domains are treated as different publisher IDs provisionally; common ownership or mirrored content must be investigated before counting corroboration.

## Archive-format discoveries — 18 Nov 2026

Fresh indexed page excerpts expose additional source layouts:

- Dlbosss Sridevi: numeric `DD/MM/YYYY to DD/MM/YYYY` weekly ranges and seven day columns, with digit groups separated by spaces.
- RealMatka Sridevi: month-name ranges such as `2024 DEC 30 to JAN 05`, seven day columns, and dash markers (`--------`) for missing entries.
- Ratan567 Kalyan: ISO date ranges such as `2025-09-22 To 2025-09-28`, seven day columns, and explicit star markers on non-result days.

The offline extractor now recognizes ISO and month-name date ranges, infers the next year only when a yearless end-date crosses December, preserves dash/star-only cells as `MISSING_FROM_SOURCE`, and accepts seven-column Kalyan boards while retaining the explicit Sunday marker. Parser fixtures exercise these formats.

These are parser-format discoveries from indexed text, **not raw HTML snapshot captures**. The three sources remain disabled pending byte-preserved snapshots, manifest SHA-256 audit, source-identity/mirroring checks, and full extraction/reconciliation.


## User-supplied SattaMatkaJodi panel pages — 19 Nov 2026

The following pages were opened and their published page descriptions identify long-range weekly panel matrices. They are registered as **disabled candidates** pending exact raw HTML capture, fixture-backed parser validation, and reconciliation. All pages share the same publisher domain and therefore the same canonical source ID, `sattamatkajodi`; they must never count as independent sources against one another.

| Source ID | Market/session | URL | Published archive description | Gate before enabling |
|---|---|---|---|---|
| `sattamatkajodi` | Kalyan / main | https://sattamatkajodi.com/record/kalyan-penal-chart.php | Page states records start week of 31 Dec 2012; Monday–Saturday panel columns. | Capture raw HTML; test weekly 3-cell layout, 6-day calendar, star markers and all date ranges. |
| `sattamatkajodi` | Sridevi / main (day) | https://sattamatkajodi.com/record/sridevi-satta-penal-chart.php | Page states records start week of 26 Feb 2018; Monday–Sunday panel columns. | Capture raw HTML; test 7-day layout and missing markers; keep separate from night. |
| `sattamatkajodi` | Sridevi Night / night | https://sattamatkajodi.com/record/sridevi-night-satta-penal-chart.php | Page states records start week of 28 Jan 2019; Monday–Sunday panel columns. | Capture raw HTML; test 7-day layout and audit malformed historical date labels before enabling. |

Page descriptions and visible indexed content are discovery evidence only—not raw HTML snapshots, not proof of complete coverage, and not publisher independence. Do not import rows from search/browser-rendered text as if they were raw captures.
