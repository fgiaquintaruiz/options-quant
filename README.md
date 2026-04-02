# 🚀 Options Quant Engine v1.3.24

### "The Vanilla Quant" - Java 25 High-Performance Trading Bot

An ultra-low latency algorithmic trading engine designed for **Financial Options** on Interactive Brokers (TWS/Gateway). This project prioritizes performance using native technologies and AI-driven risk management.

---

## 🛠 Tech Stack & Architecture

- **Runtime:** Java 25 (OpenJDK).
- **Concurrency:** Virtual Threads (Project Loom) for massive I/O handling.
- **AI Engine:** Google Gemini Pro for daily TP/SL optimization and sentiment-based hot-lists.
- **Network:** Native `jdk.httpserver` (no heavy frameworks) and TCP Socket for remote commands.
- **API:** IBKR TWS API v10.19+.
- **Analytics:** `ta4j` for technical indicator calculations.

---

## 🔄 System Execution Flow

The engine follows a strict lifecycle to ensure data integrity and AI alignment:

1.  **Initialization:** Loads `config.yaml` and starts the Cloudflare Tunnel for secure Telegram callbacks.
2.  **Smart Connectivity:** Establishes connection with TWS/Gateway and validates account permissions.
3.  **Delta Fetching:** Loads historical data from local CSVs first. It calculates the "Delta" (missing time) and only requests those specific candles from IBKR to bypass pacing violations.
4.  **AI Pre-Market Routine:** Once backfills are complete, the engine runs a batch analysis via Gemini. The AI optimizes Take Profit (TP) and Stop Loss (SL) multipliers based on 14-day ATR and recent strategy performance.
5.  **Live Monitoring:** The `StrategyEngine` monitors incoming ticks. If a strategy triggers, it passes through the **MarketRadar** and **Staircase Filter**.
6.  **Trade Execution:** Orders are routed to IBKR. If `autoExecute` is off, a Telegram message is sent with an interactive "🚀 EXECUTE" button.

---

## 🌟 Advanced Features

- **Staircase Re-entry Filter:** Prevents "chopping" by requiring a better entry price than the previous exit (Lower for Calls, Higher for Puts).
- **AI Hot-List:** Only tickers with a high Gemini confidence score (>70) are permitted for daily trading.
- **Dynamic Option Picker:** Automatically selects the nearest expiry > 48 hours to avoid gamma risk.
- **Delta Caching:** Minimizes startup time by using a hybrid CSV + Live API data loading strategy.

---

## 📟 Developer Tools: Remote Command Console

The engine features a dedicated TCP Command Server on **Port 7070** for real-time control without log interference.

**How to connect:**
```bash
curl telnet://localhost:7070
````

### Comandos Disponibles:
skip: Omite la espera del análisis de IA y activa el sistema inmediatamente (estado "Ready").

trigger <TICKER> <ESTRATEGIA> <PRECIO>: Fuerza una señal manual para probar el flujo completo (Cálculos -> Telegram -> Ejecución).

Ejemplo: trigger AAPL C1SqueezeCallStrategy 150.0

exit: Cierra la conexión de la consola remota.

🌟 Filtros de Seguridad Avanzados
Staircase Filter: Bloquea re-entradas si el precio no ha mejorado respecto al último cierre (más bajo en Calls / más alto en Puts) para evitar el "chopping".

AI Hot-List: Solo se operan activos con alta probabilidad de movimiento según el análisis de sentimiento y volatilidad matutino de Gemini.

Dynamic Picker: Selección automática de contratos de opciones con vencimiento > 48 horas para mitigar riesgos de asignación.

🚀 Instalación rápida
Configuración: Edita el archivo config.yaml. Para pruebas de desarrollo, asegúrate de poner simulationMode: true.

Ejecución: - Desde terminal: ./gradlew run

Desde IDE: Inicia Main.java (Click derecho -> Run Main).

Control: Abre una terminal aparte (Git Bash) y conéctate vía:
curl telnet://localhost:7070