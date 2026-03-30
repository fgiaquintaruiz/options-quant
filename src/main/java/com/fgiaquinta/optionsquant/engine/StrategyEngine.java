package com.fgiaquinta.optionsquant.engine;

import com.fgiaquinta.optionsquant.services.IbkrService;
import com.fgiaquinta.optionsquant.services.TelegramService;
import com.fgiaquinta.optionsquant.strategies.TradingStrategy;
import com.fgiaquinta.optionsquant.models.TimeFrame;
import com.fgiaquinta.optionsquant.utils.ConfigLoader;
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

                double entry = series1h.getBar(lastIdx).getClosePrice().doubleValue();
                double tp = strategy.calculateTP(entry);
                double sl = strategy.calculateSL(entry, ticker);
                int qty = (int) ConfigLoader.getConfig().getParam("global", "quantity");

                // 1. SIEMPRE avisamos a Telegram (el botón ya lleva la info para ejecutar)
                TelegramService.sendSignalAlert(ticker, strategy.getName(), entry, sl, tp);

                // 2. SOLO ejecutamos en IBKR si la propiedad está en true
                if (ConfigLoader.getConfig().ibkr.autoExecute) {
                    ibkrService.placeOrder(ticker, "BUY", qty, entry, tp, sl, strategy.getName());
                    System.out.println("🚀 EJECUCIÓN AUTOMÁTICA enviada a IBKR para " + ticker);
                } else {
                    System.out.println("📩 SEÑAL DETECTADA: Esperando confirmación manual desde Telegram para " + ticker);
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