package com.fgiaquinta.optionsquant.engine;

import com.fgiaquinta.optionsquant.services.IbkrService;
import com.fgiaquinta.optionsquant.strategies.TradingStrategy;
import com.fgiaquinta.optionsquant.models.TimeFrame;
import org.ta4j.core.BarSeries;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class StrategyEngine {
    private final IbkrService ibkrService;
    private final List<TradingStrategy> strategies;
    // Filtro de inventario: Guarda los tickers que ya tienen una orden activa hoy
    private final Set<String> activeOrders = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    public StrategyEngine(IbkrService ibkr, List<TradingStrategy> strategies) {
        this.ibkrService = ibkr;
        this.strategies = strategies;

        // Auto-mantenimiento: Limpiar el inventario cada 24 horas para el nuevo día de trading
        scheduler.scheduleAtFixedRate(() -> {
            clearInventory();
            System.out.println("🧹 [MANTENIMIENTO] Inventario diario limpiado. Listo para la siguiente sesión.");
        }, 12, 24, TimeUnit.HOURS); // Ejecuta el primer reset en 12h, y luego cada 24h
    }

    public void onBarAdded(String ticker, BarSeries series1h) {
        BarSeries spy = ibkrService.getSeries("SPY", TimeFrame.HOUR_1);
        if (spy == null || spy.isEmpty()) return;

        int lastIdx = series1h.getEndIndex();

        for (TradingStrategy strategy : strategies) {
            if (strategy.isTriggered(lastIdx, series1h, spy)) {

                // 1. Verificamos si ya está activo hoy
                if (activeOrders.contains(ticker)) {
                    continue;
                }

                double price = series1h.getBar(lastIdx).getClosePrice().doubleValue();
                double tp = strategy.calculateTP(price);
                double sl = strategy.calculateSL(price, ticker);

                // 2. Intentamos enviar la orden
                boolean sent = ibkrService.placeOrder(ticker, "BUY", 10, price, tp, sl, strategy.getName());

                // 3. SOLO SI SE ENVIÓ, bloqueamos el ticker para futuras señales
                if (sent) {
                    activeOrders.add(ticker);
                    System.out.println("✅ Orden confirmada y ticker bloqueado por hoy: " + ticker);
                }
            }
        }
    }

    // Método para limpiar el inventario
    public void clearInventory() {
        activeOrders.clear();
    }

    // Método para apagar el scheduler de forma segura si se detiene el bot
    public void shutdown() {
        scheduler.shutdownNow();
    }
}