"""
Massive (= Polygon.io) free plan history explorer.
Tests 7 date ranges for AAPL to determine available history.

Usage:
    python scripts/massive_explore.py

Reads MASSIVE_API_KEY from .env in project root.
No external dependencies — stdlib only.
"""
import json
import os
import sys
import time
import urllib.request
from datetime import datetime, timezone
from pathlib import Path


# ---------------------------------------------------------------------------
# Core functions
# ---------------------------------------------------------------------------

def parse_env_key(key_name: str, content: str):
    """
    Parse a .env file content (string), ignore comment lines (#),
    return the value for key_name or None if not found.
    """
    for line in content.splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        if "=" in stripped:
            k, _, v = stripped.partition("=")
            if k.strip() == key_name:
                return v.strip()
    return None


def build_url(ticker: str, multiplier: int, timespan: str,
              from_date: str, to_date: str, api_key: str) -> str:
    """
    Build Polygon.io aggregates URL.
    Endpoint: /v2/aggs/ticker/{ticker}/range/{multiplier}/{timespan}/{from}/{to}
    Query params: adjusted=true, sort=asc, limit=10, apiKey={api_key}
    """
    base = (
        f"https://api.polygon.io/v2/aggs/ticker/{ticker}"
        f"/range/{multiplier}/{timespan}/{from_date}/{to_date}"
    )
    params = f"adjusted=true&sort=asc&limit=10&apiKey={api_key}"
    return f"{base}?{params}"


def fetch(url: str) -> dict:
    """
    HTTP GET url with timeout=15s, parse JSON, return dict.
    On any error return {"status": "FETCH_ERROR", "error": str(e)}.
    """
    try:
        with urllib.request.urlopen(url, timeout=15) as response:
            raw = response.read().decode("utf-8")
            return json.loads(raw)
    except Exception as e:
        return {"status": "FETCH_ERROR", "error": str(e)}


def parse_result(response: dict) -> dict:
    """
    Extract status, count, and first_bar_utc from a Polygon response dict.

    Returns:
        {
            "status": str,
            "count": int,
            "first_bar_utc": str | None,   # "YYYY-MM-DD HH:MM UTC"
        }
    """
    status = response.get("status", "UNKNOWN")

    # Error statuses — no results to parse
    if status in ("ERROR", "FETCH_ERROR"):
        return {"status": status, "count": 0, "first_bar_utc": None}

    results = response.get("results", [])
    count = response.get("resultsCount", len(results))

    if not results:
        return {"status": status, "count": 0, "first_bar_utc": None}

    # First bar timestamp is in epoch milliseconds
    first_t_ms = results[0].get("t")
    if first_t_ms is None:
        first_bar_utc = None
    else:
        dt = datetime.fromtimestamp(first_t_ms / 1000, tz=timezone.utc)
        first_bar_utc = dt.strftime("%Y-%m-%d %H:%M UTC")

    return {"status": status, "count": count, "first_bar_utc": first_bar_utc}


def run_tests(tests: list, fetcher=None, sleep_fn=None) -> list:
    """
    Iterate tests, call fetcher(url) + parse_result, sleep sleep_fn(13)
    between calls (NOT after the last one).

    Args:
        tests:    list of dicts with keys: name, ticker, multiplier,
                  timespan, from_date, to_date.
        fetcher:  callable(url) -> dict. Defaults to fetch.
        sleep_fn: callable(seconds). Defaults to time.sleep.

    Returns:
        list of dicts: each test dict merged with parse_result output.
    """
    if fetcher is None:
        fetcher = fetch
    if sleep_fn is None:
        sleep_fn = time.sleep

    results = []
    for i, test in enumerate(tests):
        url = build_url(
            ticker=test["ticker"],
            multiplier=test["multiplier"],
            timespan=test["timespan"],
            from_date=test["from_date"],
            to_date=test["to_date"],
            api_key=test.get("api_key", ""),
        )
        response = fetcher(url)
        parsed = parse_result(response)
        results.append({**test, **parsed})

        # Sleep between requests — NOT after the last one
        if i < len(tests) - 1:
            sleep_fn(13)

    return results


def print_table(results: list):
    """
    Print an aligned table with columns:
    Test | Timeframe | From | Status | Count | First Bar (UTC)
    """
    header = f"{'Test':<6} {'Timeframe':<12} {'From':<12} {'Status':<12} {'Count':>6}  {'First Bar (UTC)'}"
    separator = "-" * len(header)
    print(separator)
    print(header)
    print(separator)
    for r in results:
        timeframe = f"{r['multiplier']}{r['timespan'][:3].upper()}"
        print(
            f"{r['name']:<6} {timeframe:<12} {r['from_date']:<12} "
            f"{r['status']:<12} {r.get('count', 0):>6}  {r.get('first_bar_utc') or 'N/A'}"
        )
    print(separator)


def main():
    # Detect .env from project root (two levels up from scripts/)
    env_path = Path(__file__).parent.parent / ".env"

    if not env_path.exists():
        print(f"ERROR: .env file not found at {env_path}")
        sys.exit(1)

    content = env_path.read_text(encoding="utf-8")
    api_key = parse_env_key("MASSIVE_API_KEY", content)

    if api_key is None:
        print("ERROR: MASSIVE_API_KEY not found in .env")
        sys.exit(1)

    tests = [
        {
            "name": "T1",
            "ticker": "AAPL",
            "multiplier": 5,
            "timespan": "minute",
            "from_date": "2024-08-01",
            "to_date": "2024-08-05",
            "api_key": api_key,
        },
        {
            "name": "T2",
            "ticker": "AAPL",
            "multiplier": 5,
            "timespan": "minute",
            "from_date": "2025-01-06",
            "to_date": "2025-01-10",
            "api_key": api_key,
        },
        {
            "name": "T3",
            "ticker": "AAPL",
            "multiplier": 5,
            "timespan": "minute",
            "from_date": "2023-01-02",
            "to_date": "2023-01-06",
            "api_key": api_key,
        },
        {
            "name": "T4",
            "ticker": "AAPL",
            "multiplier": 5,
            "timespan": "minute",
            "from_date": "2022-01-03",
            "to_date": "2022-01-07",
            "api_key": api_key,
        },
        {
            "name": "T5",
            "ticker": "AAPL",
            "multiplier": 5,
            "timespan": "minute",
            "from_date": "2018-01-02",
            "to_date": "2018-01-05",
            "api_key": api_key,
        },
        {
            "name": "T6",
            "ticker": "AAPL",
            "multiplier": 15,
            "timespan": "minute",
            "from_date": "2024-08-01",
            "to_date": "2024-08-05",
            "api_key": api_key,
        },
        {
            "name": "T7",
            "ticker": "AAPL",
            "multiplier": 15,
            "timespan": "minute",
            "from_date": "2018-01-02",
            "to_date": "2018-01-05",
            "api_key": api_key,
        },
    ]

    print(f"\nPolygon.io free plan history explorer — {len(tests)} tests for AAPL")
    print(f"Sleeping 13s between requests to respect rate limits...\n")

    results = run_tests(tests)
    print_table(results)


if __name__ == "__main__":
    main()
