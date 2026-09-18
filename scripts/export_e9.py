# Exporta de candles.db lo justo para medir E9 v2 (filtros de apertura).
# Uso:  python export_e9.py            -> exporta los tickers de siempre
#       python export_e9.py NVDA SPY   -> exporta los que le pases
# Escribe en data\e9-export\  y no toca la base (la abre en modo solo lectura).

import csv, gzip, os, sqlite3, sys, time

AQUI = os.path.dirname(os.path.abspath(__file__))
DB = os.path.join(AQUI, "..", "data", "candles.db")
SALIDA = os.path.join(os.path.dirname(DB), "e9-export")
TICKERS = sys.argv[1:] or ["NVDA", "AMZN", "TQQQ", "TSLA", "BABA"]
TIMEFRAMES = ["MIN_5", "MIN_15"]

os.makedirs(SALIDA, exist_ok=True)
con = sqlite3.connect("file:%s?mode=ro" % DB.replace("\\", "/"), uri=True)
cur = con.cursor()

print("base:", DB)
print("tickers:", ", ".join(TICKERS))

# 1) cobertura: que hay y desde cuando
cob = os.path.join(SALIDA, "cobertura.csv")
with open(cob, "w", newline="", encoding="utf-8") as fh:
    w = csv.writer(fh)
    w.writerow(["ticker", "timeframe", "velas", "primera", "ultima"])
    q = ("SELECT ticker, timeframe, COUNT(*), datetime(MIN(ts_epoch),'unixepoch'), "
         "datetime(MAX(ts_epoch),'unixepoch') FROM candles WHERE ticker=? GROUP BY ticker, timeframe")
    for t in TICKERS:
        for fila in cur.execute(q, (t,)):
            w.writerow(fila)
            print("  %-6s %-7s %8d velas  %s -> %s" % fila)
print("cobertura ->", cob)

# 2) las velas
for tf in TIMEFRAMES:
    destino = os.path.join(SALIDA, "candles_%s.csv.gz" % tf.lower())
    t0 = time.time(); n = 0
    with gzip.open(destino, "wt", newline="", encoding="utf-8") as fh:
        w = csv.writer(fh)
        w.writerow(["ticker", "ts_epoch", "open", "high", "low", "close", "volume"])
        for t in TICKERS:
            cur.execute("SELECT ticker, ts_epoch, open, high, low, close, volume FROM candles "
                        "WHERE ticker=? AND timeframe=? ORDER BY ts_epoch", (t, tf))
            while True:
                lote = cur.fetchmany(50000)
                if not lote:
                    break
                w.writerows(lote)
                n += len(lote)
    mb = os.path.getsize(destino) / 1e6
    print("%s -> %s  (%d velas, %.1f MB, %.0fs)" % (tf, destino, n, mb, time.time() - t0))

con.close()
print("\nListo. Avisale a Claude que ya estan los archivos en data\\e9-export\\")
