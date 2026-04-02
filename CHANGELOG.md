---

## 📅 CHANGELOG.md

```markdown
# Changelog

## [1.3.24] - 2026-04-02
### Added
- **TCP Command Server**: Servidor de comandos en puerto 7070 para controlar el bot desde una terminal externa (Git Bash/Telnet).
- **Comandos de Consola**: Soporte para `skip` (AI Bypass), `trigger` (Manual signal) y `exit`.
- **AI Pre-Market Routine**: Optimización dinámica de multiplicadores TP/SL vía Gemini Pro.
- **Filtro de Reentrada Staircase**: Lógica en `TradeManager` para evitar compras repetidas a precios desfavorables.
- **Delta Fetching Logic**: Sistema de carga híbrida CSV + API que reduce el tiempo de arranque y evita errores 321/162 de IBKR.

### Fixed
- **ClassCastException**: Corregido error crítico de lectura de booleanos en la configuración.
- **IBKR Pacing Violations**: Implementado un "throttle" de 100ms entre peticiones de tickers para evitar desconexiones (Error 504).
- **Gemini Rate Limiting**: Añadido delay de 4s y lógica de reintento para errores de cuota 429.
- **Circular Dependency**: Resuelto el enlace entre `IbkrService` y `TradeManager` mediante inyección por setter.

## [1.0.4] - 2026-03-30
### Added
- **ForensicLogger**: Auditoría de fills, slippage y comisiones.
- **Time Parsing**: Soporte para formatos de fecha duales de la API de IBKR.

## [1.0.1] - 2026-02-15
- Implementación inicial de estrategias C1, C2, P1, P2 y DataManager base.