# Visión del producto (uso personal)

Documento vivo de una página: prioridades y límites. Actualizar cuando cambie el foco.

## Qué problema resuelve

Motor de trading asistido contra **Interactive Brokers (TWS/Gateway)**: escanear tickers, señales, gestión de riesgo, backtests y una **SPA** (live / backtest / health) servida por Spring en `:9090`.

## Qué no es (por ahora)

- No es un producto multi-tenant ni un servicio SaaS.
- No sustituye criterio humano ni due diligence; las pérdidas son responsabilidad operativa.
- No garantiza rentabilidad ni datos de mercado fuera de lo que IBKR y el propio código entreguen.

## Modos operativos

| Modo | Idea |
|------|------|
| **Paper / sim** | Puerto y cuenta de paper en TWS; validar flujos sin dinero real. |
| **Live** | Solo cuando configuración, riesgo y límites estén explícitamente revisados. |

La fuente de verdad de host/puerto/cuenta es `application.yml` (y lo que marque TWS).

## Definición de “suficientemente bien” (criterios personales)

Marcar o reescribir según evolucione el proyecto:

- [ ] Arranque local predecible: backend + frontend empaquetado o `npm run dev` documentado.
- [ ] UI live: ver estado de escaneo, señales y controles sin errores bloqueantes.
- [ ] UI backtest: lanzar / parar / ver progreso o resultados según lo implementado.
- [ ] Conexión IBKR: comportamiento claro **con TWS apagado** (errores controlados) y **con TWS encendido** (datos u órdenes según configuración).
- [ ] Tests automatizados que yo confíe para no romper lo crítico al cambiar código.

## Decisiones técnicas que valen la pena documentar aparte

Si una elección es costosa de deshacer (ej. modelo de órdenes, reintentos IBKR, límites de riesgo), un **ADR** corto en `.atl/` o `docs/adr/` basta; no hace falta un RFC formal.

## Última revisión

Fecha: (completar al editar)
