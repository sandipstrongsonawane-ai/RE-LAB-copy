import csv
import io
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from extract_weekly_chart_html import _cell_observation, extract_rows, write_csv


class WeeklyChartHtmlExtractorTest(unittest.TestCase):
    def test_extracts_sridevi_seven_day_week_and_marker_without_faking_result(self):
        html = """
        <table><tr><th>Date</th><th>Mon</th><th>Tue</th><th>Wed</th>
        <th>Thu</th><th>Fri</th><th>Sat</th><th>Sun</th></tr>
        <tr><td>29/12/25TO04/01/26</td><td>460</td><td>09</td><td>289</td>
        <td>450</td><td>92</td><td>246</td><td>189</td><td>83</td><td>689</td>
        <td>155</td><td>14</td><td>680</td><td>299</td><td>00</td><td>479</td>
        <td>* * *</td><td>**</td><td>* * *</td><td>134</td><td>89</td><td>450</td></tr></table>
        """
        rows, issues = extract_rows(
            html, market="SRIDEVI", session_id="main", source_id="hira",
            retrieved_at="2026-11-17T12:00:00+05:30",
            source_url="https://example.test/sridevi"
        )
        self.assertEqual([], issues)
        self.assertEqual(7, len(rows))
        self.assertEqual("2025-12-29", rows[0]["date"])
        self.assertEqual("460-09-289", rows[0]["result"])
        self.assertEqual("PUBLISHED", rows[0]["status"])
        self.assertEqual("* * * | ** | * * *", rows[5]["raw_text"])
        self.assertEqual("MISSING_FROM_SOURCE", rows[5]["status"])
        self.assertEqual("", rows[5]["result"])
        self.assertEqual("2026-01-04", rows[6]["date"])

    def test_extracts_single_cell_per_day_weekly_layout_used_by_hpofficial(self):
        html = """
        <table><tr><th>DATE</th><th>MON</th><th>TUE</th><th>WED</th>
        <th>THU</th><th>FRI</th><th>SAT</th><th>SUN</th></tr>
        <tr><td>29/12/25TO04/01/26</td>
        <td>4 6 0 09 2 8 9</td><td>4 5 0 92 2 4 6</td>
        <td>1 8 9 83 6 8 9</td><td>1 5 5 14 6 8 0</td>
        <td>2 9 9 00 4 7 9</td><td>2 5 0 78 1 7 0</td><td>********</td>
        </tr></table>
        """
        rows, issues = extract_rows(
            html, market="SRIDEVI", session_id="main", source_id="hpofficial",
            retrieved_at="2026-11-18T12:00:00+05:30",
            source_url="https://hpofficial.in/sridevi-panel-chart/"
        )
        self.assertEqual([], issues)
        self.assertEqual(7, len(rows))
        self.assertEqual("460-09-289", rows[0]["result"])
        self.assertEqual("299-00-479", rows[4]["result"])
        self.assertEqual("MISSING_FROM_SOURCE", rows[6]["status"])
        self.assertEqual("********", rows[6]["raw_text"])

    def test_accepts_market_specific_weekly_chart_widths(self):
        cases = (
            ("SRIDEVI", "16/11/2026 to 22/11/2026", 7),
            ("SRIDEVI_NIGHT", "16/11/2026 to 22/11/2026", 7),
            ("KALYAN", "16/11/2026 to 21/11/2026", 6),
            ("TIME_BAZAR", "16/11/2026 to 22/11/2026", 7),
            ("MAIN_BAZAR", "16/11/2026 to 20/11/2026", 5),
        )
        for market, date_range, day_count in cases:
            with self.subTest(market=market, day_count=day_count):
                weekly = (
                    f"<table><tr><td>{date_range}</td>"
                    + "".join(f"<td>{cell[:3]}</td><td>{cell[3:5]}</td><td>{cell[5:]}</td>" for cell in ["66807449"] * day_count)
                    + "</tr></table>"
                )
                rows, issues = extract_rows(
                    weekly, market=market, session_id="main", source_id="archive",
                    retrieved_at="2026-11-17T12:00:00Z"
                )
                self.assertEqual([], issues)
                self.assertEqual(day_count, len(rows))
                self.assertTrue(all(row["market"] == market for row in rows))

    def test_extracts_kalyan_six_day_week_and_flags_bad_math(self):
        html = """
        <table><tr><td>24-08-2026 to 29-08-2026</td>
        <td>668</td><td>07</td><td>449</td><td>470</td><td>17</td><td>179</td><td>247</td><td>33</td><td>490</td>
        <td>568</td><td>92</td><td>345</td><td>247</td><td>32</td><td>480</td><td>123</td><td>45</td><td>678</td>
        </tr></table>
        """
        rows, issues = extract_rows(
            html, market="KALYAN", session_id="main", source_id="fixresult",
            retrieved_at="2026-11-17T12:00:00Z"
        )
        self.assertEqual([], issues)
        self.assertEqual(6, len(rows))
        self.assertEqual("668-07-449", rows[0]["result"])
        self.assertEqual("MALFORMED", rows[5]["status"])
        self.assertEqual("", rows[5]["result"])

    def test_explicit_holiday_and_pending_markers_remain_distinct(self):
        html = """
        <table><tr><td>24-08-2026 to 29-08-2026</td>
        <td>668</td><td>07</td><td>449</td><td>470</td><td>17</td><td>179</td><td>247</td><td>33</td><td>490</td>
        <td>568</td><td>92</td><td>345</td><td>HOLIDAY</td><td></td><td></td><td>Pending</td><td></td><td></td></tr></table>
        """
        rows, issues = extract_rows(
            html, market="KALYAN", session_id="main", source_id="archive",
            retrieved_at="2026-11-17T12:00:00Z"
        )
        self.assertEqual([], issues)
        self.assertEqual("HOLIDAY_CLOSED", rows[4]["status"])
        self.assertEqual("PENDING", rows[5]["status"])
        self.assertEqual("", rows[4]["result"])
        self.assertEqual("", rows[5]["result"])

    def test_year_and_date_digits_do_not_turn_markers_into_results(self):
        html = """
        <table><tr><td>24-08-2026 to 29-08-2026</td>
        <td>668</td><td>07</td><td>449</td><td>470</td><td>17</td><td>179</td><td>247</td><td>33</td><td>490</td>
        <td>568</td><td>92</td><td>345</td><td>Holiday 2026</td><td></td><td></td>
        <td>Market closed 24-08-2026</td><td></td><td></td></tr></table>
        """
        rows, issues = extract_rows(
            html, market="KALYAN", session_id="main", source_id="archive",
            retrieved_at="2026-11-17T12:00:00Z"
        )
        self.assertEqual([], issues)
        self.assertEqual("HOLIDAY_CLOSED", rows[4]["status"])
        self.assertEqual("HOLIDAY_CLOSED", rows[5]["status"])
        self.assertEqual("", rows[4]["result"])
        self.assertEqual("", rows[5]["result"])

    def test_contradictory_marker_and_result_is_malformed(self):
        status, result = _cell_observation("Holiday 668-07-449")
        self.assertEqual("MALFORMED", status)
        self.assertEqual("", result)

    def test_source_record_ids_are_date_stable_when_irrelevant_rows_are_inserted(self):
        weekly_row = (
            "<tr><td>24-08-2026 to 29-08-2026</td>"
            + "".join(f"<td>{cell[:3]}</td><td>{cell[3:5]}</td><td>{cell[5:]}</td>" for cell in ["66807449"] * 6)
            + "</tr>"
        )
        common = dict(
            market="KALYAN", session_id="main", source_id="archive",
            retrieved_at="2026-11-17T12:00:00Z"
        )
        first, _ = extract_rows(f"<table>{weekly_row}</table>", **common)
        shifted, _ = extract_rows(
            f"<table><tr><td>Archive heading</td></tr>{weekly_row}</table>",
            **common
        )
        self.assertEqual(
            [row["source_record_id"] for row in first],
            [row["source_record_id"] for row in shifted]
        )
        self.assertEqual("2026-08-24", first[0]["source_record_id"])

    def test_extracts_month_name_week_range_and_dash_missing_marker(self):
        html = """
        <table><tr><td>2024 DEC 30 to JAN 05</td>
        <td>569 04 248</td><td>348 57 557</td><td>690 57 340</td>
        <td>240 64 167</td><td>478 96 457</td><td>270 93 148</td>
        <td>--------</td></tr></table>
        """
        rows, issues = extract_rows(
            html, market="SRIDEVI", session_id="main", source_id="realmatka",
            retrieved_at="2026-11-18T12:00:00+05:30",
            source_url="https://www.realmatka.in/panel-chart-record/sridevi"
        )
        self.assertEqual([], issues)
        self.assertEqual(7, len(rows))
        self.assertEqual("2024-12-30", rows[0]["date"])
        self.assertEqual("2025-01-05", rows[6]["date"])
        self.assertEqual("569-04-248", rows[0]["result"])
        self.assertEqual("MISSING_FROM_SOURCE", rows[6]["status"])

    def test_extracts_iso_week_range_used_by_kalyan_archive(self):
        html = """
        <table><tr><td>2025-09-22 To 2025-09-28</td>
        <td>********</td><td>********</td><td>590 49 478</td>
        <td>790 69 135</td><td>458 79 126</td><td>557 71 128</td>
        <td>********</td></tr></table>
        """
        rows, issues = extract_rows(
            html, market="SRIDEVI", session_id="main", source_id="ratan567",
            retrieved_at="2026-11-18T12:00:00+05:30",
            source_url="https://ratan567official.com/kalyan/pana-chart/"
        )
        self.assertEqual([], issues)
        self.assertEqual("2025-09-22", rows[0]["date"])
        self.assertEqual("2025-09-28", rows[6]["date"])
        self.assertEqual("590-49-478", rows[2]["result"])
        self.assertEqual("MISSING_FROM_SOURCE", rows[0]["status"])

    def test_retrieval_timestamp_requires_timezone_offset(self):
        with self.assertRaisesRegex(ValueError, "UTC offset"):
            extract_rows(
                "<table></table>", market="KALYAN", session_id="main",
                source_id="x", retrieved_at="2026-11-17T12:00:00"
            )

    def test_csv_output_round_trips_commas_and_quotes_in_raw_text(self):
        rows, _ = extract_rows(
            "<table><tr><td>24-08-2026 to 29-08-2026</td>"
            + "".join(f"<td>{cell[:3]}</td><td>{cell[3:5]}</td><td>{cell[5:]}</td>" for cell in ["66807449"] * 6)
            + "</tr></table>",
            market="KALYAN", session_id="main", source_id="fix",
            retrieved_at="2026-11-17T12:00:00Z"
        )
        rows[0]["raw_text"] = '668 07 449, board says "published"'
        output = io.StringIO()
        write_csv(rows, output)
        parsed = list(csv.DictReader(io.StringIO(output.getvalue())))
        self.assertEqual(rows[0]["raw_text"], parsed[0]["raw_text"])

    def test_rejects_bad_retrieval_timestamp_and_market(self):
        with self.assertRaises(ValueError):
            extract_rows("<table></table>", market="UNKNOWN", session_id="main",
                         source_id="x", retrieved_at="2026-11-17T12:00:00Z")
        with self.assertRaises(ValueError):
            extract_rows("<table></table>", market="KALYAN", session_id="main",
                         source_id="x", retrieved_at="not-a-time")


if __name__ == "__main__":
    unittest.main()
