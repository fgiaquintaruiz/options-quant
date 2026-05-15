"""
TDD tests for massive_import.py — RED first, then GREEN after implementation.
Run with: python scripts/test_massive_import.py -v
"""
import sqlite3
import unittest
from unittest.mock import patch, MagicMock, call
from pathlib import Path


# This import will fail (RED) until massive_import.py is created
from massive_import import (
    parse_env_key,
    build_url,
    parse_bars,
    upsert_bars,
    is_done,
    mark_progress,
    ensure_progress_table,
    fetch_all_bars,
    import_ticker_timeframe,
    load_tickers,
    main,
    BASE_URL,
    FETCH_ERROR,
    RATE_LIMIT_SLEEP,
)


# ---------------------------------------------------------------------------
# Test 1 — parse_env_key
# ---------------------------------------------------------------------------
class TestParseEnvKey(unittest.TestCase):

    def test_simple_key_value(self):
        content = "MASSIVE_API_KEY=abc123\n"
        self.assertEqual(parse_env_key("MASSIVE_API_KEY", content), "abc123")

    def test_commented_line_ignored(self):
        content = "# MASSIVE_API_KEY=should_be_ignored\nMASSIVE_API_KEY=real\n"
        self.assertEqual(parse_env_key("MASSIVE_API_KEY", content), "real")

    def test_missing_key_returns_none(self):
        content = "OTHER_KEY=value\n"
        self.assertIsNone(parse_env_key("MASSIVE_API_KEY", content))

    def test_value_with_equals_sign(self):
        # partition('=') keeps the rest intact
        content = "MASSIVE_API_KEY=abc=def\n"
        self.assertEqual(parse_env_key("MASSIVE_API_KEY", content), "abc=def")


# ---------------------------------------------------------------------------
# Test 2 — build_url
# ---------------------------------------------------------------------------
class TestBuildUrl(unittest.TestCase):

    def _make_url(self):
        return build_url(
            api_key="TESTKEY",
            ticker="SPY",
            multiplier=5,
            timespan="minute",
            from_date="2024-01-01",
            to_date="2024-12-31",
        )

    def test_contains_ticker(self):
        self.assertIn("SPY", self._make_url())

    def test_contains_multiplier(self):
        self.assertIn("/5/", self._make_url())

    def test_contains_timespan(self):
        self.assertIn("minute", self._make_url())

    def test_contains_from_date(self):
        self.assertIn("2024-01-01", self._make_url())

    def test_contains_to_date(self):
        self.assertIn("2024-12-31", self._make_url())

    def test_contains_adjusted(self):
        self.assertIn("adjusted=true", self._make_url())

    def test_contains_limit(self):
        self.assertIn("limit=50000", self._make_url())

    def test_contains_api_key(self):
        self.assertIn("apiKey=TESTKEY", self._make_url())

    def test_starts_with_base_url(self):
        self.assertTrue(self._make_url().startswith(BASE_URL))


# ---------------------------------------------------------------------------
# Test 3 — parse_bars
# ---------------------------------------------------------------------------
class TestParseBars(unittest.TestCase):

    def _make_response(self):
        return {
            "status": "OK",
            "results": [
                {"t": 1_700_000_000_000, "o": 1.1, "h": 1.5, "l": 0.9, "c": 1.3, "v": 1234.7},
                {"t": 1_700_000_300_000, "o": 1.3, "h": 1.6, "l": 1.0, "c": 1.4, "v": 5678.2},
            ],
        }

    def test_two_results_returns_two_tuples(self):
        bars = parse_bars(self._make_response(), "SPY", "MIN_5")
        self.assertEqual(len(bars), 2)

    def test_epoch_millis_converted_to_seconds(self):
        bars = parse_bars(self._make_response(), "SPY", "MIN_5")
        self.assertEqual(bars[0][2], 1_700_000_000_000 // 1000)  # ts_epoch

    def test_volume_rounded_to_int(self):
        bars = parse_bars(self._make_response(), "SPY", "MIN_5")
        self.assertIsInstance(bars[0][7], int)
        self.assertEqual(bars[0][7], round(1234.7))

    def test_tuple_order(self):
        # Expected: (ticker, timeframe, ts_epoch, open, high, low, close, volume)
        bars = parse_bars(self._make_response(), "SPY", "MIN_5")
        ticker, timeframe, ts_epoch, o, h, l, c, v = bars[0]
        self.assertEqual(ticker, "SPY")
        self.assertEqual(timeframe, "MIN_5")
        self.assertEqual(o, 1.1)
        self.assertEqual(h, 1.5)
        self.assertEqual(l, 0.9)
        self.assertEqual(c, 1.3)

    def test_empty_results_returns_empty_list(self):
        bars = parse_bars({"status": "OK", "results": []}, "SPY", "MIN_5")
        self.assertEqual(bars, [])

    def test_missing_results_key_returns_empty_list(self):
        bars = parse_bars({"status": "FETCH_ERROR", "error": "timeout"}, "SPY", "MIN_5")
        self.assertEqual(bars, [])


# ---------------------------------------------------------------------------
# Test 4 — upsert_bars
# ---------------------------------------------------------------------------
class TestUpsertBars(unittest.TestCase):

    def _make_conn(self):
        conn = sqlite3.connect(":memory:")
        conn.execute("""
            CREATE TABLE candles (
                ticker TEXT, timeframe TEXT, ts_epoch INTEGER,
                open REAL, high REAL, low REAL, close REAL, volume INTEGER,
                PRIMARY KEY (ticker, timeframe, ts_epoch)
            ) WITHOUT ROWID
        """)
        conn.commit()
        return conn

    def _sample_bars(self):
        return [
            ("SPY", "MIN_5", 1_700_000_000, 1.1, 1.5, 0.9, 1.3, 1234),
            ("SPY", "MIN_5", 1_700_000_300, 1.3, 1.6, 1.0, 1.4, 5678),
        ]

    def test_insert_two_bars(self):
        conn = self._make_conn()
        upsert_bars(conn, self._sample_bars())
        conn.commit()
        count = conn.execute("SELECT COUNT(*) FROM candles").fetchone()[0]
        self.assertEqual(count, 2)

    def test_reinsertion_is_upsert_no_duplicate(self):
        conn = self._make_conn()
        upsert_bars(conn, self._sample_bars())
        conn.commit()
        upsert_bars(conn, self._sample_bars())
        conn.commit()
        count = conn.execute("SELECT COUNT(*) FROM candles").fetchone()[0]
        self.assertEqual(count, 2)


# ---------------------------------------------------------------------------
# Test 5 — is_done / mark_progress
# ---------------------------------------------------------------------------
class TestProgressTracking(unittest.TestCase):

    def _make_conn(self):
        conn = sqlite3.connect(":memory:")
        ensure_progress_table(conn)
        conn.commit()
        return conn

    def test_not_marked_returns_false(self):
        conn = self._make_conn()
        self.assertFalse(is_done(conn, "SPY", "MIN_5"))

    def test_marked_done_returns_true(self):
        conn = self._make_conn()
        mark_progress(conn, "SPY", "MIN_5", "DONE", 42)
        conn.commit()
        self.assertTrue(is_done(conn, "SPY", "MIN_5"))

    def test_marked_error_returns_false(self):
        conn = self._make_conn()
        mark_progress(conn, "SPY", "MIN_5", "ERROR", 0)
        conn.commit()
        self.assertFalse(is_done(conn, "SPY", "MIN_5"))

    def test_marked_empty_returns_false(self):
        conn = self._make_conn()
        mark_progress(conn, "SPY", "MIN_5", "EMPTY", 0)
        conn.commit()
        self.assertFalse(is_done(conn, "SPY", "MIN_5"))


# ---------------------------------------------------------------------------
# Test 6 — import_ticker_timeframe mock completo
# ---------------------------------------------------------------------------
class TestImportTickerTimeframe(unittest.TestCase):

    def _make_conn(self):
        conn = sqlite3.connect(":memory:")
        conn.execute("""
            CREATE TABLE candles (
                ticker TEXT, timeframe TEXT, ts_epoch INTEGER,
                open REAL, high REAL, low REAL, close REAL, volume INTEGER,
                PRIMARY KEY (ticker, timeframe, ts_epoch)
            ) WITHOUT ROWID
        """)
        ensure_progress_table(conn)
        conn.commit()
        return conn

    def _fake_response(self):
        return {
            "status": "OK",
            "results": [
                {"t": 1_700_000_000_000, "o": 1.0, "h": 1.1, "l": 0.9, "c": 1.05, "v": 100.0},
                {"t": 1_700_000_300_000, "o": 1.05, "h": 1.2, "l": 1.0, "c": 1.1, "v": 200.0},
                {"t": 1_700_000_600_000, "o": 1.1, "h": 1.3, "l": 1.05, "c": 1.25, "v": 300.0},
            ],
        }

    @patch("massive_import.fetch_bars")
    def test_upserts_correct_bar_count(self, mock_fetch):
        mock_fetch.return_value = self._fake_response()
        conn = self._make_conn()
        sleep_mock = MagicMock()
        tf = {"name": "MIN_5", "multiplier": 5, "timespan": "minute"}

        result = import_ticker_timeframe(
            conn, "TESTKEY", "SPY", tf, "2024-01-01", "2024-12-31",
            sleep_fn=sleep_mock,
        )

        count = conn.execute("SELECT COUNT(*) FROM candles").fetchone()[0]
        self.assertEqual(count, 3)
        self.assertEqual(result["bars"], 3)

    @patch("massive_import.fetch_bars")
    def test_marks_progress_done(self, mock_fetch):
        mock_fetch.return_value = self._fake_response()
        conn = self._make_conn()
        tf = {"name": "MIN_5", "multiplier": 5, "timespan": "minute"}

        import_ticker_timeframe(
            conn, "TESTKEY", "SPY", tf, "2024-01-01", "2024-12-31",
        )

        self.assertTrue(is_done(conn, "SPY", "MIN_5"))

    @patch("massive_import.fetch_bars")
    def test_sleep_not_called_by_this_function(self, mock_fetch):
        mock_fetch.return_value = self._fake_response()
        conn = self._make_conn()
        sleep_mock = MagicMock()
        tf = {"name": "MIN_5", "multiplier": 5, "timespan": "minute"}

        import_ticker_timeframe(
            conn, "TESTKEY", "SPY", tf, "2024-01-01", "2024-12-31",
            sleep_fn=sleep_mock,
        )

        sleep_mock.assert_not_called()

    @patch("massive_import.fetch_bars")
    def test_returns_result_dict_with_expected_keys(self, mock_fetch):
        mock_fetch.return_value = self._fake_response()
        conn = self._make_conn()
        tf = {"name": "MIN_5", "multiplier": 5, "timespan": "minute"}

        result = import_ticker_timeframe(
            conn, "TESTKEY", "SPY", tf, "2024-01-01", "2024-12-31",
        )

        self.assertIn("ticker", result)
        self.assertIn("timeframe", result)
        self.assertIn("status", result)
        self.assertIn("bars", result)
        self.assertEqual(result["ticker"], "SPY")
        self.assertEqual(result["timeframe"], "MIN_5")
        self.assertEqual(result["status"], "DONE")


# ---------------------------------------------------------------------------
# Test 7 — main skip logic
# ---------------------------------------------------------------------------
class TestMainSkipLogic(unittest.TestCase):

    @patch("massive_import.fetch_bars")
    @patch("massive_import.is_done", return_value=True)
    @patch("massive_import.load_tickers", return_value=["SPY", "AAPL"])
    @patch("massive_import.ensure_progress_table")
    def test_no_fetch_when_all_done(
        self, mock_ensure, mock_load, mock_is_done, mock_fetch
    ):
        env_content = "MASSIVE_API_KEY=fakekey\n"

        # Use a real in-memory DB so sqlite3.connect works without patching the module
        real_conn = sqlite3.connect(":memory:")
        real_conn.execute("""
            CREATE TABLE IF NOT EXISTS candles (
                ticker TEXT, timeframe TEXT, ts_epoch INTEGER,
                open REAL, high REAL, low REAL, close REAL, volume INTEGER,
                PRIMARY KEY (ticker, timeframe, ts_epoch)
            ) WITHOUT ROWID
        """)
        real_conn.commit()

        with patch("builtins.open", unittest.mock.mock_open(read_data=env_content)), \
             patch("massive_import.DB_PATH", Path(":memory:")), \
             patch("massive_import.sqlite3.connect", return_value=real_conn), \
             patch("massive_import.time"):
            # Pass args=[] so argparse doesn't pick up unittest's -v flag
            main(args=[])

        # fetch_bars should never be called when is_done is always True
        mock_fetch.assert_not_called()


# ---------------------------------------------------------------------------
# Test 8 — fetch_all_bars
# ---------------------------------------------------------------------------
class TestFetchAllBars(unittest.TestCase):
    """TDD tests for the pagination wrapper fetch_all_bars."""

    TF = {"name": "MIN_5", "multiplier": 5, "timespan": "minute"}

    def _make_results(self, count: int, last_t: int = 1_700_000_000_000) -> list:
        """Return a list of `count` fake bar dicts with the last one having t=last_t."""
        bars = [{"t": 1_700_000_000_000 + i * 300_000,
                 "o": 1.0, "h": 1.1, "l": 0.9, "c": 1.05, "v": 100.0}
                for i in range(count)]
        if count > 0:
            bars[-1]["t"] = last_t
        return bars

    @patch("massive_import.fetch_bars")
    @patch("massive_import.build_url")
    def test_single_page_no_pagination(self, mock_build_url, mock_fetch):
        """Less than 50000 results → only one fetch, sleep never called."""
        mock_build_url.return_value = "http://fake-url"
        mock_fetch.return_value = {
            "status": "OK",
            "results": self._make_results(100),
            "resultsCount": 100,
        }
        sleep_mock = MagicMock()

        result = fetch_all_bars("KEY", "SPY", self.TF, "2024-01-01", "2024-12-31",
                                sleep_fn=sleep_mock)

        mock_fetch.assert_called_once()
        self.assertEqual(len(result["results"]), 100)
        self.assertEqual(result["status"], "OK")
        sleep_mock.assert_not_called()

    @patch("massive_import.fetch_bars")
    @patch("massive_import.build_url")
    def test_two_pages(self, mock_build_url, mock_fetch):
        """Exactly 50000 on first call → fetches second page; sleep called once between pages."""
        LAST_T = 1_700_000_000_000
        page1_results = self._make_results(50_000, last_t=LAST_T)
        page2_results = self._make_results(200)

        mock_build_url.return_value = "http://fake-url"
        mock_fetch.side_effect = [
            {"status": "OK", "results": page1_results, "resultsCount": 50_000},
            {"status": "OK", "results": page2_results, "resultsCount": 200},
        ]
        sleep_mock = MagicMock()

        result = fetch_all_bars("KEY", "SPY", self.TF, "2024-01-01", "2024-12-31",
                                sleep_fn=sleep_mock)

        self.assertEqual(mock_fetch.call_count, 2)
        # Second build_url call must use str(last_t + 1) as from_date
        second_build_call_kwargs = mock_build_url.call_args_list[1]
        # build_url is called positionally: (api_key, ticker, multiplier, timespan, from_date, to_date)
        args_second = second_build_call_kwargs[0]
        self.assertEqual(args_second[4], str(LAST_T + 1))
        # sleep called once (between page 1 and page 2, NOT after page 2)
        sleep_mock.assert_called_once_with(RATE_LIMIT_SLEEP)
        self.assertEqual(len(result["results"]), 50_200)

    @patch("massive_import.fetch_bars")
    @patch("massive_import.build_url")
    def test_three_pages(self, mock_build_url, mock_fetch):
        """Two full pages then a partial → fetch called 3 times, sleep called twice."""
        T1 = 1_700_000_000_000
        T2 = 1_800_000_000_000
        page1 = self._make_results(50_000, last_t=T1)
        page2 = self._make_results(50_000, last_t=T2)
        page3 = self._make_results(500)

        mock_build_url.return_value = "http://fake-url"
        mock_fetch.side_effect = [
            {"status": "OK", "results": page1, "resultsCount": 50_000},
            {"status": "OK", "results": page2, "resultsCount": 50_000},
            {"status": "OK", "results": page3, "resultsCount": 500},
        ]
        sleep_mock = MagicMock()

        result = fetch_all_bars("KEY", "SPY", self.TF, "2024-01-01", "2024-12-31",
                                sleep_fn=sleep_mock)

        self.assertEqual(mock_fetch.call_count, 3)
        self.assertEqual(sleep_mock.call_count, 2)
        self.assertEqual(len(result["results"]), 100_500)

    @patch("massive_import.fetch_bars")
    @patch("massive_import.build_url")
    def test_fetch_error_on_first_page(self, mock_build_url, mock_fetch):
        """FETCH_ERROR on first page → return immediately without sleeping."""
        mock_build_url.return_value = "http://fake-url"
        mock_fetch.return_value = {
            "status": FETCH_ERROR,
            "error": "timeout",
            "results": [],
        }
        sleep_mock = MagicMock()

        result = fetch_all_bars("KEY", "SPY", self.TF, "2024-01-01", "2024-12-31",
                                sleep_fn=sleep_mock)

        self.assertEqual(result["status"], FETCH_ERROR)
        sleep_mock.assert_not_called()

    @patch("massive_import.fetch_bars")
    @patch("massive_import.build_url")
    def test_fetch_error_on_second_page(self, mock_build_url, mock_fetch):
        """FETCH_ERROR on second page → sleep was called once (between p1 and p2), then return error."""
        page1 = self._make_results(50_000, last_t=1_700_000_000_000)
        mock_build_url.return_value = "http://fake-url"
        mock_fetch.side_effect = [
            {"status": "OK", "results": page1, "resultsCount": 50_000},
            {"status": FETCH_ERROR, "error": "timeout", "results": []},
        ]
        sleep_mock = MagicMock()

        result = fetch_all_bars("KEY", "SPY", self.TF, "2024-01-01", "2024-12-31",
                                sleep_fn=sleep_mock)

        self.assertEqual(result["status"], FETCH_ERROR)
        sleep_mock.assert_called_once_with(RATE_LIMIT_SLEEP)


# ---------------------------------------------------------------------------
# Test 9 — import_ticker_timeframe delegates to fetch_all_bars
# ---------------------------------------------------------------------------
class TestImportTickerTimeframePagination(unittest.TestCase):
    """Verify import_ticker_timeframe uses fetch_all_bars (not fetch_bars directly)."""

    def _make_conn(self):
        conn = sqlite3.connect(":memory:")
        conn.execute("""
            CREATE TABLE candles (
                ticker TEXT, timeframe TEXT, ts_epoch INTEGER,
                open REAL, high REAL, low REAL, close REAL, volume INTEGER,
                PRIMARY KEY (ticker, timeframe, ts_epoch)
            ) WITHOUT ROWID
        """)
        ensure_progress_table(conn)
        conn.commit()
        return conn

    @patch("massive_import.fetch_all_bars")
    def test_import_uses_fetch_all_bars(self, mock_fetch_all):
        """import_ticker_timeframe must delegate to fetch_all_bars, passing sleep_fn through."""
        mock_fetch_all.return_value = {
            "status": "OK",
            "results": [
                {"t": 1_700_000_000_000, "o": 1.0, "h": 1.1, "l": 0.9, "c": 1.05, "v": 100.0},
            ],
            "resultsCount": 1,
        }
        conn = self._make_conn()
        sleep_mock = MagicMock()
        tf = {"name": "MIN_5", "multiplier": 5, "timespan": "minute"}

        result = import_ticker_timeframe(
            conn, "TESTKEY", "SPY", tf, "2024-01-01", "2024-12-31",
            sleep_fn=sleep_mock,
        )

        # fetch_all_bars must have been called (not fetch_bars directly)
        mock_fetch_all.assert_called_once()
        call_kwargs = mock_fetch_all.call_args
        # Verify sleep_fn was forwarded
        # fetch_all_bars(api_key, ticker, tf, from_date, to_date, sleep_fn=...)
        self.assertIn(sleep_mock, call_kwargs[0] + tuple(call_kwargs[1].values()))
        self.assertEqual(result["status"], "DONE")
        self.assertEqual(result["bars"], 1)


if __name__ == "__main__":
    unittest.main()
