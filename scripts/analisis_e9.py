import os
import gzip, csv, collections, statistics as st
from datetime import datetime, timezone
from zoneinfo import ZoneInfo

NY=ZoneInfo("America/New_York")
PCT=0.0027

def leer(p):
    d=collections.defaultdict(list)
    with gzip.open(p,'rt') as fh:
        r=csv.reader(fh); next(r)
        for tk,ts,o,h,l,c,v in r:
            d[tk].append((int(ts),float(o),float(h),float(l),float(c)))
    for k in d: d[k].sort()
    return d

AQUI = os.path.dirname(os.path.abspath(__file__))
EXPORT = os.path.join(AQUI, "..", "data", "e9-export")
b5 = leer(os.path.join(EXPORT, "candles_min_5.csv.gz"))
b15 = leer(os.path.join(EXPORT, "candles_min_15.csv.gz"))

filas=[]
for tk in b5:
    barras=b5[tk]
    idx={}
    for i,(ts,o,h,l,c) in enumerate(barras):
        dt=datetime.fromtimestamp(ts,NY)
        if (dt.hour,dt.minute)==(9,30): idx[dt.date()]=i
    # medias de 15m
    cierres15=[(ts,c) for ts,o,h,l,c in b15.get(tk,[])]
    ts15=[x[0] for x in cierres15]
    import bisect
    for dia,i in sorted(idx.items()):
        if i+12>=len(barras): continue
        ts0,o,h,l,c = barras[i]
        # continuidad: las 12 barras siguientes tienen que ser del mismo dia y consecutivas (5 min)
        seq=barras[i:i+13]
        if any(seq[k+1][0]-seq[k][0]!=300 for k in range(12)): continue
        cuerpo=round(o*PCT,2)
        if c==o: continue
        call = c>o
        cuerpo_ok = (c>=o+cuerpo) if call else (c<=o-cuerpo)
        rango_ok  = (h < o+2*cuerpo) if call else (l > o-2*cuerpo)
        entrada=c
        sig3=seq[1:4]      # 15 min
        sig12=seq[1:13]    # 60 min
        c15=sig3[-1][4]; c60=sig12[-1][4]
        mfe15=max(x[2] for x in sig3)-entrada if call else entrada-min(x[3] for x in sig3)
        mae15=entrada-min(x[3] for x in sig3) if call else max(x[2] for x in sig3)-entrada
        # media de 15m: pendiente de la SMA20 al momento de la apertura
        j=bisect.bisect_left(ts15, ts0)
        pend=None
        if j>=21:
            prev=sum(x[1] for x in cierres15[j-20:j])/20
            now =sum(x[1] for x in cierres15[j-19:j])/20 + (c-0)/20 - 0  # sustituye la barra en formacion por el cierre de 5m
            now =(sum(x[1] for x in cierres15[j-19:j])+c)/20
            pend = 1 if now>prev else -1
        filas.append(dict(tk=tk,dia=dia,call=call,cuerpo_ok=cuerpo_ok,rango_ok=rango_ok,
                          entrada=entrada, dir15=(c15>entrada)==call, dir60=(c60>entrada)==call,
                          mfe=100*mfe15/entrada, mae=100*mae15/entrada,
                          ret15=100*((c15-entrada) if call else (entrada-c15))/entrada,
                          ret60=100*((c60-entrada) if call else (entrada-c60))/entrada,
                          pend_ok=None if pend is None else ((pend==1)==call)))

def resumen(nombre, sel):
    if not sel: print(f"{nombre:34} sin casos"); return
    n=len(sel)
    d15=100*sum(f['dir15'] for f in sel)/n
    d60=100*sum(f['dir60'] for f in sel)/n
    obj=100*sum(f['mfe']>=0.2 for f in sel)/n
    print(f"{nombre:34} n={n:5d}  dir15={d15:5.1f}%  toca+0,2%={obj:5.1f}%  MFE={st.median(f['mfe'] for f in sel):5.2f}%  MAE={st.median(f['mae'] for f in sel):5.2f}%  dir60={d60:5.1f}%")

print("Sesiones analizadas:", len(filas), "| tickers:", collections.Counter(f['tk'] for f in filas))
print()
resumen("TODAS", filas)
resumen("cuerpo OK", [f for f in filas if f['cuerpo_ok']])
resumen("  + rango limpio", [f for f in filas if f['cuerpo_ok'] and f['rango_ok']])
resumen("  + rango FALLA (esc. 3)", [f for f in filas if f['cuerpo_ok'] and not f['rango_ok']])
resumen("cuerpo NO (vela floja)", [f for f in filas if not f['cuerpo_ok']])
print()
pend=[f for f in filas if f['pend_ok'] is not None]
print(f"media 15m a favor: {100*sum(f['pend_ok'] for f in pend)/len(pend):.1f}% de las veces coincide con la vela (n={len(pend)})")
ok=[f for f in pend if f['cuerpo_ok']]
a=[f for f in ok if f['pend_ok']]; b=[f for f in ok if not f['pend_ok']]
resumen("cuerpo OK + media a favor", a)
resumen("cuerpo OK + media EN CONTRA", b)
print()
for tk in sorted(set(f['tk'] for f in filas)):
    resumen(f"[{tk}] cuerpo OK", [f for f in filas if f['tk']==tk and f['cuerpo_ok']])


print()
print("SIMULACION sobre el subyacente (entrada al cierre de 15:35; salida por objetivo o por reloj a los 15 min)")
import statistics as st
for nombre, sel in (("cuerpo OK", [f for f in filas if f['cuerpo_ok']]),
                    ("cuerpo OK + rango limpio", [f for f in filas if f['cuerpo_ok'] and f['rango_ok']]),
                    ("todas las sesiones", filas)):
    print(" ", nombre)
    for X in (0.15,0.20,0.25,0.30):
        res=[X if f['mfe']>=X else f['ret15'] for f in sel]
        ganan=100*sum(1 for r in res if r>0)/len(res)
        perd=[r for r in res if r<=0]
        print(f"    objetivo {X:.2f}%: media {st.mean(res):+.3f}%  gana {ganan:.0f}%  perdida media {st.mean(perd) if perd else 0:+.2f}%  (n={len(res)})")
print()
print("control: media del retorno a 15 min en direccion de la vela:", round(st.mean([f['ret15'] for f in filas if f['cuerpo_ok']]),4), "%")
