"""
massive_import.py — Importa 730 días de candles desde Polygon.io hacia candles.db.

Uso:
    python scripts/massive_import.py
    python scripts/massive_import.py --dry-run

Rate limit: 5 req/min (plan free) → sleep 12s entre requests.
Duración estimada: ~3.5 horas para 513 tickers x 2 timeframes.
"""

import argparse
import json
import sqlite3
import time
import urllib.request
from datetime import date, datetime, timedelta
from pathlib import Path

# ---------------------------------------------------------------------------
# Constantes
# ---------------------------------------------------------------------------

PROJECT_ROOT = Path(__file__).parent.parent
DB_PATH = PROJECT_ROOT / "data" / "candles.db"
ENV_PATH = PROJECT_ROOT / ".env"
BASE_URL = "https://api.polygon.io"
RATE_LIMIT_SLEEP = 12       # seconds between requests (5/min free plan)
FETCH_ERROR = "FETCH_ERROR"  # sentinel returned by fetch_bars on HTTP/network failure
HISTORY_DAYS = 730          # today - 730 días (límite 730-day free plan de Polygon.io)
TIMEFRAMES = [
    {"name": "MIN_5",  "multiplier": 5,  "timespan": "minute"},
    {"name": "MIN_15", "multiplier": 15, "timespan": "minute"},
    {"name": "HOUR_1", "multiplier": 1,  "timespan": "hour"},
]


# ---------------------------------------------------------------------------
# Funciones públicas
# ---------------------------------------------------------------------------

def parse_env_key(key_name: str, content: str):
    """
    Parsea contenido de un archivo .env (string).
    Ignora líneas que comienzan con '#'.
    Usa partition('=') para soportar '=' en el valor.
    Retorna el valor como str, o None si no se encuentra.
    """
    for line in content.splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        k, sep, v = stripped.partition("=")
        if sep and k.strip() == key_name:
            return v
    return None


def load_tickers(db_path: Path) -> list:
    """
    Lee todos los tickers distintos de la tabla candles.
    Retorna lista ordenada alfabéticamente.
    """
    conn = sqlite3.connect(str(db_path))
    try:
        rows = conn.execute(
            "SELECT DISTINCT ticker FROM candles WHERE timeframe IN ('MIN_5', 'HOUR_1') ORDER BY ticker"
        ).fetchall()
        return [row[0] for row in rows]
    finally:
        conn.close()


def ensure_progress_table(conn):
    """
    Crea la tabla massive_import_progress si no existe.
    Idempotente (IF NOT EXISTS).
    """
    conn.execute("""
        CREATE TABLE IF NOT EXISTS massive_import_progress (
            ticker    TEXT NOT NULL,
            timeframe TEXT NOT NULL,
            status    TEXT NOT NULL,
            bars      INTEGER DEFAULT 0,
            fetched_at INTEGER NOT NULL,
            PRIMARY KEY (ticker, timeframe)
        ) WITHOUT ROWID
    """)
    conn.commit()


def is_done(conn, ticker: str, timeframe: str) -> bool:
    """
    Retorna True si el par (ticker, timeframe) ya fue importado con status='DONE'.
    """
    row = conn.execute(
        "SELECT 1 FROM massive_import_progress WHERE ticker=? AND timeframe=? AND status='DONE'",
        (ticker, timeframe),
    ).fetchone()
    return row is not None


def mark_progress(conn, ticker: str, timeframe: str, status: str, bars: int):
    """
    Guarda o actualiza el progreso de importación para un par (ticker, timeframe).
    """
    conn.execute(
        """
        INSERT OR REPLACE INTO massive_import_progress
            (ticker, timeframe, status, bars, fetched_at)
        VALUES (?, ?, ?, ?, ?)
        """,
        (ticker, timeframe, status, bars, int(time.time())),
    )


def build_url(api_key: str, ticker: str, multiplier: int, timespan: str,
              from_date: str, to_date: str) -> str:
    """
    Construye la URL para el endpoint /v2/aggs de Polygon.io.
    """
    return (
        f"{BASE_URL}/v2/aggs/ticker/{ticker}/range/{multiplier}/{timespan}"
        f"/{from_date}/{to_date}"
        f"?adjusted=true&sort=asc&limit=50000&apiKey={api_key}"
    )


def fetch_bars(url: str) -> dict:
    """
    Hace GET a la URL dada con timeout=30s.
    Retorna el JSON parseado.
    Si falla, retorna dict con status='FETCH_ERROR' y results=[].
    """
    try:
        with urllib.request.urlopen(url, timeout=30) as resp:
            body = resp.read()
            return json.loads(body)
    except Exception as exc:
        return {"status": FETCH_ERROR, "error": str(exc), "results": []}


def fetch_all_bars(api_key: str, ticker: str, tf: dict, from_date: str, to_date: str,
                   sleep_fn=time.sleep) -> dict:
    """
    Fetches ALL bars for a ticker/timeframe pair, following Polygon.io pagination via next_url.

    The /v2/aggs endpoint returns at most 50,000 bars per request. When the response
    contains a `next_url` field, more pages exist — regardless of how many bars the current
    page returned. This function follows `next_url` until it is absent.

    NOTE: Polygon does NOT include apiKey in next_url — we append it on each request.

    Rate limiting between pages is handled internally via sleep_fn.
    CALLER still handles sleep after the full ticker import completes.

    Returns a merged dict: {"status": "OK", "results": all_results, "resultsCount": len(all_results)}
    On FETCH_ERROR: returns the error dict immediately (no retry).
    """
    url = build_url(api_key, ticker, tf["multiplier"], tf["timespan"], from_date, to_date)
    response = fetch_bars(url)

    if response.get("status") == FETCH_ERROR:
        return response

    all_results = list(response.get("results") or [])

    while response.get("next_url"):
        next_url = response["next_url"] + f"&apiKey={api_key}"
        sleep_fn(RATE_LIMIT_SLEEP)
        response = fetch_bars(next_url)

        if response.get("status") == FETCH_ERROR:
            return response

        all_results.extend(response.get("results") or [])

    return {"status": "OK", "results": all_results, "resultsCount": len(all_results)}


def parse_bars(response: dict, ticker: str, timeframe: str) -> list:
    """
    Convierte la respuesta de Polygon en lista de tuples para insertar en candles.
    Tuple order: (ticker, timeframe, ts_epoch, open, high, low, close, volume)
    - t en millis → dividir por 1000
    - v float → round() → int
    """
    results = response.get("results") or []
    bars = []
    for r in results:
        ts_epoch = r["t"] // 1000
        volume = round(r["v"])
        bars.append((ticker, timeframe, ts_epoch, r["o"], r["h"], r["l"], r["c"], volume))
    return bars


def upsert_bars(conn, bars: list):
    """
    Inserta o reemplaza bars en la tabla candles.
    No hace commit — el caller es responsable.
    """
    conn.executemany(
        """
        INSERT OR REPLACE INTO candles
            (ticker, timeframe, ts_epoch, open, high, low, close, volume)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """,
        bars,
    )


def import_ticker_timeframe(conn, api_key: str, ticker: str, tf: dict,
                             from_date: str, to_date: str,
                             sleep_fn=time.sleep) -> dict:
    """
    Orquesta una importación completa para un par (ticker, timeframe).
    Rate limiting between pages handled internally via sleep_fn.
    CALLER still handles sleep after the full ticker import completes.
    Retorna dict con keys: ticker, timeframe, status, bars.
    """
    response = fetch_all_bars(api_key, ticker, tf, from_date, to_date, sleep_fn)

    if response.get("status") == FETCH_ERROR:
        mark_progress(conn, ticker, tf["name"], "ERROR", 0)
        conn.commit()
        return {"ticker": ticker, "timeframe": tf["name"], "status": "ERROR", "bars": 0}

    bars = parse_bars(response, ticker, tf["name"])

    if not bars:
        mark_progress(conn, ticker, tf["name"], "EMPTY", 0)
        conn.commit()
        return {"ticker": ticker, "timeframe": tf["name"], "status": "EMPTY", "bars": 0}

    upsert_bars(conn, bars)
    mark_progress(conn, ticker, tf["name"], "DONE", len(bars))
    conn.commit()
    return {"ticker": ticker, "timeframe": tf["name"], "status": "DONE", "bars": len(bars)}


def main(args=None):
    """
    Punto de entrada principal.
    Lee .env, abre DB, itera tickers x timeframes e importa con rate limiting.
    Soporta --dry-run para simular sin hacer HTTP calls ni writes.
    """
    parser = argparse.ArgumentParser(description="Importa candles históricos desde Polygon.io")
    parser.add_argument("--dry-run", action="store_true",
                        help="Simula sin hacer HTTP calls ni escribir en DB")
    parser.add_argument("--only-tickers", type=str, default=None,
                        help="Comma-separated tickers (overrides load_tickers)")
    parsed = parser.parse_args(args)

    # Leer API key
    env_content = ENV_PATH.read_text(encoding="utf-8")
    api_key = parse_env_key("MASSIVE_API_KEY", env_content)
    if not api_key:
        print(f"{datetime.now().strftime('%Y-%m-%d %H:%M:%S')} [ERROR] MASSIVE_API_KEY no encontrada en .env — abortando.")
        raise SystemExit(1)

    from_date = (date.today() - timedelta(days=HISTORY_DAYS)).isoformat()
    to_date = date.today().isoformat()

    print(f"[INFO] Rango: {from_date} -> {to_date}")
    print(f"[INFO] Dry-run: {parsed.dry_run}")

    conn = sqlite3.connect(str(DB_PATH))
    try:
        ensure_progress_table(conn)
        cursor = conn.cursor()
        cursor.execute("SELECT ticker, timeframe FROM massive_import_progress WHERE status='DONE'")
        done_set = {(row[0], row[1]) for row in cursor.fetchall()}
        if parsed.only_tickers:
            tickers = [t.strip().upper() for t in parsed.only_tickers.split(",") if t.strip()]
            print(f"[INFO] Modo --only-tickers: {tickers}")
        else:
            tickers = load_tickers(DB_PATH)
            print(f"[INFO] Tickers encontrados: {len(tickers)}")

        counts = {"DONE": 0, "ERROR": 0, "EMPTY": 0, "SKIP": 0}

        for ticker in tickers:
            for tf in TIMEFRAMES:
                if (ticker, tf["name"]) in done_set:
                    print(f"{datetime.now().strftime('%Y-%m-%d %H:%M:%S')} [SKIP] {ticker} / {tf['name']}")
                    counts["SKIP"] += 1
                    continue  # No sleep cuando se saltea

                if parsed.dry_run:
                    print(f"[DRY-RUN] Haría fetch: {ticker} / {tf['name']}")
                    time.sleep(0)  # no sleep en dry-run
                    continue

                result = import_ticker_timeframe(conn, api_key, ticker, tf, from_date, to_date)
                status = result["status"]
                bars = result["bars"]
                counts[status] = counts.get(status, 0) + 1
                if status == "DONE":
                    done_set.add((ticker, tf["name"]))
                print(f"{datetime.now().strftime('%Y-%m-%d %H:%M:%S')} [{status}] {ticker} / {tf['name']} — bars={bars}")

                time.sleep(RATE_LIMIT_SLEEP)

        print(
            f"\n[RESUMEN] DONE: {counts['DONE']} | "
            f"ERROR: {counts.get('ERROR', 0)} | "
            f"EMPTY: {counts.get('EMPTY', 0)} | "
            f"SKIP: {counts['SKIP']}"
        )
    finally:
        conn.close()


if __name__ == "__main__":
    main()
