# 🚀 Options Quant Engine v1.3.29

### "The Vanilla Quant" - Java 21+ High-Performance Trading Bot

Un motor de trading algorítmico híbrido diseñado para Interactive Brokers (TWS/Gateway). Diseñado para una ejecución ultrarrápida, gestión de riesgo estricta y uso eficiente de la API de IBKR.

---

## 🌟 Características Avanzadas (Core Architecture)

- **Delta Fetching Híbrido & Anti-Deadlock:** El bot prioriza la lectura de datos históricos desde archivos CSV locales. Calcula dinámicamente el tiempo transcurrido desde la última vela y solicita a IBKR solo el Delta faltante. Incluye un sistema **Anti-Deadlock con Timeout de 45 segundos** que evita congelamientos si la API de IBKR pierde peticiones.
- **Hard Caps Anti-Error 162:** Limitación inteligente de peticiones de datos históricos. Previene baneos automáticos de IBKR limitando descargas máximas según el marco temporal.
- **Ticker Sanitization & Smart Routing:** Limpieza extrema de símbolos (`replaceAll("[^a-zA-Z]", "")`) y enrutamiento dinámico de `primaryExch` (NASDAQ vs NYSE) para garantizar un rechazo del 0% por "Error 200: No security definition".
- **Opciones "Exchange-Aware":** Selección dinámica del *Strike Price* validado directamente por la exchange. El bot no adivina el strike, lo machea con las opciones reales disponibles.
- **Sincronización de Equidad en Tiempo Real:** El `AccountManager` detiene la ejecución de simulaciones hasta que el balance real de la cuenta (NetLiquidation) se sincronice, calculando con precisión matemática la cantidad de contratos permitidos según el riesgo por trade (Options Multiplier Aware).
- **Staircase Filter:** Bloquea re-entradas perdedoras si el precio de la acción no ha mejorado respecto al último Stop Loss ejecutado.
- **Advanced Bracket Orders (OCA):** Enrutamiento institucional de órdenes. La entrada se lanza directamente a mercado (MKT/LMT) sin bloqueos condicionales, mientras que el riesgo se delega 100% a los servidores de IBKR usando algoritmos "One-Cancels-All" con lógica condicional híbrida (Precio OR Tiempo "Golden Rule").

---

## 📟 Consola de Control Remoto & Telegram

El bot incluye dos vías de control remoto sin necesidad de detener el motor:

### 1. Servidor de Comandos Local (Puerto `7070`)
Ideal para testing desde la terminal. Cómo conectar (Git Bash / Linux / macOS):
```bash
curl telnet://localhost:7070
ComandoDescripciónskipHace un bypass del análisis Pre-Market de la IA y pone el bot en estado "LIVE" instantáneamente.macro greenFuerza los filtros macroeconómicos (MarketRadar) a estado FAVORABLE. Ideal para testing.trigger <TICKER> <PRICE>[Quick Test] Dispara automáticamente la C1SqueezeCallStrategy para el ticker indicado.trigger <TICK> <STRAT> <PRICE>Ejecuta manualmente una estrategia específica saltándose las condiciones de tiempo.exitCierra la conexión de la consola remota.