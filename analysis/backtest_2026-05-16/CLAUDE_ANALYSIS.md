# Análisis de Overfitting — Run 2026-05-17_00-10

Run sobre 62 tickers, 3,695 trades, $809k PnL nominal, 10 estrategias activas.
Rango: 2018-01-16 a 2026-05-15.

Universo: ticker_stats con has_all_4_tfs=1 AND bars_total >= 80000 (snapshot 16/05 noche).

## Ranking de estrategias

### 🟢 PROMOVER A PAPER TRADING

**p6 reversal** — La estrella del set
- 1,053 trades · $382k PnL · win rate 64.6%
- Consistente TODOS los años (2018→2026 sin años perdedores)
- Top ticker BA $17k = solo 4.5% del PnL → buena diversificación
- VEREDICTO: edge robusto a regímenes. Paper trading inmediato.

**p1 squeeze** — Sólida
- 403 trades · $142k PnL · win rate 62.0%
- Pico en 2024 ($50k) y 2025 ($54k), positivo desde 2019
- Top ticker ADBE 13% del PnL → diversificación aceptable
- VEREDICTO: edge real. Paper trading.

**c6 reversal** — Buena
- 903 trades · $129k PnL · win rate 63.2%
- Consistente todos los años (2018→2026)
- 60+ tickers operados, top AAPL 10%
- VEREDICTO: edge real. Paper trading.

### 🟡 REVISAR PARÁMETROS

**p2 trend** — Decaying
- 255 trades · $65k PnL · win rate 59.2%
- 2024 $36k → 2025 $14k → 2026 $4k (declining)
- Edge se concentra en 2024
- ACCIÓN: optimizar antes de paper

**c1 squeeze** — OK pero marginal
- 454 trades · $41k PnL · win rate 59.5%
- LULU pierde -$7,284 (peor ticker) → señal idiosincrásica
- ACCIÓN: revisar gestión de riesgo

**c2 trend** — Inconsistente
- 358 trades · $26k PnL · win rate 54.5%
- 2025 -$2,665 perdedor después de buen 2024
- GEV pierde -$7,869 (concentración tóxica)
- ACCIÓN: revisar

**p3 bounce** — Muestra insuficiente
- 59 trades en 8 años · win rates volátiles año a año
- COIN solo = 40% del PnL → concentración tóxica
- ACCIÓN: ampliar universo y re-validar

### 🔴 CONSIDERAR ELIMINAR

**p5 continuation** — Win rate 42.1%, edge frágil
**c5 continuation** — PnL casi cero ($2,640), sin consistencia temporal
**c3 bounce** — PnL TOTAL NEGATIVO (-$980)

## Red flags globales

1. **Concentración temporal**: 90%+ del PnL viene de 2024-2026. Pre-2024 sparse.
2. **Universo no diversificado**: top tickers son nombres líquidos conocidos. Falta stress test en small caps.
3. **Worst trades excesivos**: -$1,500 vs avg $200-400 → gestión de riesgo deficiente.
4. **LULU problemático en 4 estrategias**: capaz tiene comportamiento idiosincrásico.
5. **GEV bias direccional**: pierde en CALL, gana en PUT. Considera direccional.
6. **Faltan c4/p4 opening en resultados**: investigar por qué no generaron trades.

## Next steps

1. Re-correr con universo focal (10 mega caps + SPY + 5 tácticos)
2. Verificar por qué c4/p4 opening no generan signals
3. Eliminar c3 bounce, c5 continuation del set
4. Investigar gestión de riesgo (worst trade limit)
5. Conectar top 3 estrategias a paper trading IBKR
6. Implementar filtro de estrategias por DB (tabla strategy_config) + UI React

---

Generado por Claude Desktop sobre TSVs del run 2026-05-17_00-10.
Fecha del análisis: 2026-05-17.
