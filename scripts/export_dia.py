# Exporta las velas DIARIAS de todos los tickers de candles.db, para medir
# los rebotes en medias moviles (puntos 1-8 del material) sobre decadas y cientos de empresas.
# Uso:  python scripts\export_dia.py
# Deja data\e9-export\candles_day_1.csv.gz  y  cobertura_day_1.csv
# Abre la base en modo solo lectura: no la modifica.

import csv, gzip, os, sqlite3, time

def fecha(ts):
    """Formatea un ts_epoch aunque venga en milisegundos, nulo o fuera de rango."""
    try:
        ts = int(ts)
    except (TypeError, ValueError):
        return "?"
    if abs(ts) > 10**11:      # viene en milisegundos
        ts //= 1000
    if not (0 < ts < 4*10**9):
        return "?"
    try:
        return time.strftime("%Y-%m-%d", time.gmtime(ts))
    except (OSError, ValueError, OverflowError):
        return "?"


AQUI = os.path.dirname(os.path.abspath(__file__))
DB = os.path.join(AQUI, "..", "data", "candles.db")
SALIDA = os.path.join(os.path.dirname(DB), "e9-export")
MINIMO_VELAS = 300          # con menos de ~15 meses no se puede mirar la MA200

os.makedirs(SALIDA, exist_ok=True)
con = sqlite3.connect("file:%s?mode=ro" % DB.replace("\\", "/"), uri=True)
cur = con.cursor()

print("base:", DB)
print("buscando tickers con velas DAY_1 ...")
t0 = time.time()
cur.execute("SELECT ticker, COUNT(*), MIN(ts_epoch), MAX(ts_epoch) FROM candles "
            "WHERE timeframe='DAY_1' GROUP BY ticker ORDER BY ticker")
tickers = cur.fetchall()
print("  %d tickers en %.0fs" % (len(tickers), time.time() - t0))

cob = os.path.join(SALIDA, "cobertura_day_1.csv")
usar = []
with open(cob, "w", newline="", encoding="utf-8") as fh:
    w = csv.writer(fh)
    w.writerow(["ticker", "velas", "primera", "ultima", "incluido"])
    for tk, n, mn, mx in tickers:
        ok = n >= MINIMO_VELAS
        w.writerow([tk, n, fecha(mn), fecha(mx), "si" if ok else "no"])
        if ok:
            usar.append(tk)
print("con al menos %d velas: %d tickers" % (MINIMO_VELAS, len(usar)))

destino = os.path.join(SALIDA, "candles_day_1.csv.gz")
t0 = time.time(); n = 0
with gzip.open(destino, "wt", newline="", encoding="utf-8") as fh:
    w = csv.writer(fh)
    w.writerow(["ticker", "ts_epoch", "open", "high", "low", "close", "volume"])
    for i, tk in enumerate(usar, 1):
        cur.execute("SELECT ticker, ts_epoch, open, high, low, close, volume FROM candles "
                    "WHERE ticker=? AND timeframe='DAY_1' ORDER BY ts_epoch", (tk,))
        filas = cur.fetchall()
        w.writerows(filas)
        n += len(filas)
        if i % 25 == 0:
            print("  %d/%d tickers · %d velas · %.0fs" % (i, len(usar), n, time.time() - t0))

print("\nlisto: %s  (%d velas, %.1f MB, %.0fs)" % (destino, n, os.path.getsize(destino) / 1e6, time.time() - t0))
print("cobertura:", cob)
print("Avisale a Claude que ya estan los archivos.")
con.close()
