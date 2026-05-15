"""
TDD tests for massive_explore.py
Run: python scripts/test_massive_explore.py -v
All tests must FAIL before implementation exists (RED), then pass after (GREEN).
"""
import unittest
from unittest.mock import Mock, call


class TestParseEnvKey(unittest.TestCase):
    """Test 1 — parse_env_key"""

    def test_returns_value_for_existing_key(self):
        from massive_explore import parse_env_key
        content = "MASSIVE_API_KEY=abc123\nOTHER_KEY=xyz"
        self.assertEqual("abc123", parse_env_key("MASSIVE_API_KEY", content))

    def test_ignores_comment_lines(self):
        from massive_explore import parse_env_key
        content = "# MASSIVE_API_KEY=should_be_ignored\nMASSIVE_API_KEY=real_value"
        self.assertEqual("real_value", parse_env_key("MASSIVE_API_KEY", content))

    def test_returns_none_when_key_not_found(self):
        from massive_explore import parse_env_key
        content = "OTHER_KEY=some_value\nANOTHER_KEY=other"
        self.assertIsNone(parse_env_key("MASSIVE_API_KEY", content))

    def test_returns_none_for_empty_content(self):
        from massive_explore import parse_env_key
        self.assertIsNone(parse_env_key("MASSIVE_API_KEY", ""))

    def test_handles_inline_comment_after_value(self):
        from massive_explore import parse_env_key
        # .env files: value is everything after '=', strip only trailing whitespace
        content = "MASSIVE_API_KEY=abc123"
        self.assertEqual("abc123", parse_env_key("MASSIVE_API_KEY", content))


class TestBuildUrl(unittest.TestCase):
    """Test 2 — build_url"""

    def test_builds_correct_url(self):
        from massive_explore import build_url
        result = build_url(
            ticker="AAPL",
            multiplier=5,
            timespan="minute",
            from_date="2024-08-01",
            to_date="2024-08-05",
            api_key="TESTKEY",
        )
        expected = (
            "https://api.polygon.io/v2/aggs/ticker/AAPL/range/5/minute"
            "/2024-08-01/2024-08-05"
            "?adjusted=true&sort=asc&limit=10&apiKey=TESTKEY"
        )
        self.assertEqual(expected, result)

    def test_builds_url_with_different_ticker(self):
        from massive_explore import build_url
        result = build_url("SPY", 15, "minute", "2023-01-02", "2023-01-06", "KEY123")
        self.assertIn("/ticker/SPY/range/15/minute/", result)
        self.assertIn("apiKey=KEY123", result)


class TestParseResult(unittest.TestCase):
    """Test 3 — parse_result"""

    def test_extracts_fields_from_valid_response(self):
        from massive_explore import parse_result
        response = {
            "status": "OK",
            "resultsCount": 3,
            "results": [
                {"t": 1722499200000, "o": 100, "h": 110, "l": 90, "c": 105, "v": 1000},
                {"t": 1722499500000, "o": 105, "h": 115, "l": 95, "c": 108, "v": 1200},
            ],
        }
        result = parse_result(response)
        self.assertEqual("OK", result["status"])
        self.assertEqual(3, result["count"])
        self.assertIsNotNone(result["first_bar_utc"])
        # 1722499200000 ms = 1722499200 s → 2024-08-01 16:00:00 UTC
        self.assertIsInstance(result["first_bar_utc"], str)
        self.assertIn("2024-08-01", result["first_bar_utc"])

    def test_first_bar_utc_none_when_results_empty(self):
        from massive_explore import parse_result
        response = {"status": "OK", "resultsCount": 0, "results": []}
        result = parse_result(response)
        self.assertIsNone(result["first_bar_utc"])
        self.assertEqual(0, result["count"])

    def test_first_bar_utc_none_when_no_results_key(self):
        from massive_explore import parse_result
        response = {"status": "OK", "resultsCount": 0}
        result = parse_result(response)
        self.assertIsNone(result["first_bar_utc"])

    def test_error_status_returns_zero_count(self):
        from massive_explore import parse_result
        response = {"status": "ERROR", "error": "Forbidden"}
        result = parse_result(response)
        self.assertEqual("ERROR", result["status"])
        self.assertEqual(0, result["count"])
        self.assertIsNone(result["first_bar_utc"])

    def test_fetch_error_status(self):
        from massive_explore import parse_result
        response = {"status": "FETCH_ERROR", "error": "timeout"}
        result = parse_result(response)
        self.assertEqual("FETCH_ERROR", result["status"])
        self.assertEqual(0, result["count"])
        self.assertIsNone(result["first_bar_utc"])


class TestRunTests(unittest.TestCase):
    """Test 4 — run_tests sleeps N-1 times for N tests"""

    def test_sleeps_n_minus_one_times_for_n_tests(self):
        from massive_explore import run_tests

        mock_fetcher = Mock(return_value={"status": "OK", "resultsCount": 0, "results": []})
        mock_sleep = Mock()

        tests = [
            {"name": "T1", "ticker": "AAPL", "multiplier": 5, "timespan": "minute",
             "from_date": "2024-08-01", "to_date": "2024-08-05"},
            {"name": "T2", "ticker": "AAPL", "multiplier": 5, "timespan": "minute",
             "from_date": "2025-01-06", "to_date": "2025-01-10"},
            {"name": "T3", "ticker": "AAPL", "multiplier": 5, "timespan": "minute",
             "from_date": "2023-01-02", "to_date": "2023-01-06"},
        ]

        results = run_tests(tests, fetcher=mock_fetcher, sleep_fn=mock_sleep)

        # N=3 → sleep called exactly N-1 = 2 times
        self.assertEqual(2, mock_sleep.call_count)
        mock_sleep.assert_called_with(13)

    def test_sleeps_zero_times_for_single_test(self):
        from massive_explore import run_tests

        mock_fetcher = Mock(return_value={"status": "OK", "resultsCount": 0, "results": []})
        mock_sleep = Mock()

        tests = [
            {"name": "T1", "ticker": "AAPL", "multiplier": 5, "timespan": "minute",
             "from_date": "2024-08-01", "to_date": "2024-08-05"},
        ]

        run_tests(tests, fetcher=mock_fetcher, sleep_fn=mock_sleep)

        # N=1 → sleep called exactly 0 times
        self.assertEqual(0, mock_sleep.call_count)

    def test_returns_list_with_one_result_per_test(self):
        from massive_explore import run_tests

        mock_fetcher = Mock(return_value={"status": "OK", "resultsCount": 2, "results": [
            {"t": 1722499200000}
        ]})
        mock_sleep = Mock()

        tests = [
            {"name": "T1", "ticker": "AAPL", "multiplier": 5, "timespan": "minute",
             "from_date": "2024-08-01", "to_date": "2024-08-05"},
            {"name": "T2", "ticker": "AAPL", "multiplier": 15, "timespan": "minute",
             "from_date": "2024-08-01", "to_date": "2024-08-05"},
        ]

        results = run_tests(tests, fetcher=mock_fetcher, sleep_fn=mock_sleep)

        self.assertEqual(2, len(results))
        self.assertIn("status", results[0])
        self.assertIn("count", results[0])
        self.assertIn("first_bar_utc", results[0])


if __name__ == "__main__":
    unittest.main()
