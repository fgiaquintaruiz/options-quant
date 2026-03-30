# 🚀 Options Quant Engine v1.0.4

### "The Vanilla Quant" - Java 25 High-Performance Trading Bot

Motor de trading algorítmico de ultra-baja latencia diseñado para operar **Opciones Financieras** en Interactive Brokers (TWS/Gateway). Este proyecto prioriza el rendimiento utilizando tecnologías nativas y eliminando dependencias pesadas.

---

## 🛠 Tech Stack & Architecture

- **Runtime:** Java 25 (OpenJDK).
- **Concurrency:** Virtual Threads (Project Loom) para manejo masivo de I/O.
- **Network:** Servidor Webhook nativo `jdk.httpserver` (sin frameworks).
- **API:** IBKR TWS API v10.19+.
- **Analytics:** `ta4j` para cálculo de indicadores técnicos.

---

## 🌟 Key Features

- **Dynamic Option Picker:** El bot consulta la cadena de opciones en tiempo real y selecciona automáticamente el vencimiento más cercano que supere las **48 horas** de vida (evitando riesgos de asignación inmediata).
- **Hybrid Conditionals:** Las órdenes se ejecutan en contratos de opciones (`OPT`), pero el disparo (Trigger), el Take Profit y el Stop Loss vigilan el precio del **subyacente** (Stock) para máxima precisión técnica.
- **The Golden Rule (21:55 Exit):** Todas las posiciones abiertas se cierran automáticamente a mercado 5 minutos antes del cierre del NASDAQ si no han tocado sus objetivos.
- **Forensic Auditing:** Registro detallado en `logs/` de fills reales, slippage y comisiones exactas cobradas por el broker.

---

## 📈 Strategies Included

1.  **C1 Squeeze Call:** Entrada por volatilidad comprimida + Volumen > 115%.
2.  **C2 Trend Call:** Seguimiento de tendencia con medias móviles rápidas (EMA8).
3.  **P1 Squeeze Put:** Estrategia bajista basada en Relative Strength Rank bajo.
4.  **P2 Trend Put:** Captura de caídas aceleradas por debajo de la SMA200.

---

## 🚀 Setup & Installation

1.  **Requisitos:** TWS o IB Gateway abierto con "Enable ActiveX and Socket Clients" activo en el puerto `7497`.
2.  **Variables de Entorno:**
    - `OPTIONSQUANT_MASTER_KEY`: Llave de seguridad para el Webhook.
    - `TELEGRAM_TOKEN` / `CHAT_ID`: (Opcional) Para notificaciones.
3.  **Build:**
    ```bash
    ./gradlew build
    ```

---

## 🔒 Security Notice
Este repositorio contiene lógica de ejecución real. Nunca compartas tu `IP` pública o tu `MASTER_KEY`. El bot está configurado para conectarse a `localhost` por seguridad.

---
