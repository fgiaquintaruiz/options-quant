[1.3.29] - 2026-04-02 (Estabilidad Institucional & UX)
Added
Anti-Deadlock Backfill Barrier: Implementado un Timeout de 45 segundos en la carga de datos históricos (waitForBackfillCompletion) y una purga activa de IDs de peticiones fantasma en caso de errores de API, asegurando que el bot siempre arranque incluso si TWS pierde paquetes.

Telegram Bounce-Back UX: Modificado el HttpServer (Puerto 9090) para devolver un payload HTML/JS que ejecuta window.location.href = "tg://";. Esto evita que la pestaña de Chrome se quede abierta inútilmente tras confirmar una orden desde el móvil.

Fixed
IBKR Error 135 (Bracket Rejection): Eliminada la condición de precio en la orden Padre (OrderType: MKT) dentro de OrderFactory. Esto soluciona el rechazo instantáneo que provocaba que las órdenes hijas (Take Profit / Stop Loss) fallaran por orfandad.

Bracket Logic (AND to OR): Corregido el ConditionBuilder seteando conjunctionConnection(false). Ahora IBKR entiende correctamente que el bot debe salir del trade si toca el Stop Loss/Take Profit O si se alcanza la hora límite de la Golden Rule (21:55), en lugar de exigir que ambas condiciones ocurran simultáneamente.

[1.3.28] - 2026-04-02
Added
Command Server V2: Añadido soporte para macro green y modo "Quick Trigger".

Account Latch: Barrera de sincronización en el arranque para garantizar capital neto real (NetLiquidation).

Changed
Contract Factory: Mejorado enrutamiento asignando NYSE a tickers conocidos e ISLAND a los demás.

Account Manager: Corregido cálculo Qty integrando multiplicador de opciones (x100) vs USD/Riesgo.