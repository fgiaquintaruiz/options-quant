# Changelog

## [1.0.4] - 2026-03-30
### Added
- **ForensicLogger**: Registro automático de ejecuciones reales, precios de fill y comisiones.
- **Time Parsing Robustness**: Soporte para formatos de fecha duales (Epoch y String con Timezone) de IBKR.
- **Startup Re-scan**: Lógica de escaneo inicial para procesar señales pendientes en los CSVs al arrancar.

### Fixed
- **IBKR Error 200**: Solucionado el problema de "No security definition" añadiendo `tradingClass` y búsqueda dinámica de contratos.
- **IBKR Error 148**: Cambio de órdenes STP a MKT Condicionales para permitir cierres por tiempo.
- **Chronological Overlap**: Filtro en `historicalData` para evitar errores de solapamiento de velas en `ta4j`.

## [1.0.3] - 2026-03-25
### Changed
- **Option Pivot**: Transición de trading de acciones a 10 contratos fijos de Opciones Financieras.
- **Dynamic Expiry**: Búsqueda automática del mejor vencimiento > 48 horas.

## [1.0.2] - 2026-03-10
### Added
- **Virtual Threads**: Implementación de Project Loom para el lector de la API y el servidor Webhook.
- **Native Server**: Eliminación de Javalin en favor de `jdk.httpserver` para reducir la huella de memoria.

## [1.0.1] - 2026-02-15
### Added
- **Base Logic**: Implementación de estrategias C1, C2, P1 y P2.
- **CSV DataManager**: Sistema de persistencia de datos históricos.

## [1.0.0] - 2026-01-20
- Lanzamiento inicial del motor base y conexión con IBKR API.
