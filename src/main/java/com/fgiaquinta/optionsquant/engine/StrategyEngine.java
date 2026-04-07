package com.fgiaquinta.optionsquant.engine;

import com.fgiaquinta.optionsquant.services.IbkrService;
import com.fgiaquinta.optionsquant.strategies.TradingStrategy;
import com.fgiaquinta.optionsquant.utils.DataManager;
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
    private final TradeManager tradeManager;
    private final DataManager dataManager;
    private final MarketRadar radar; // Inyectamos el radar para el filtro Macro

    private final Set<String> activeOrders = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

    public StrategyEngine(IbkrService ibkr, List<TradingStrategy> strategies, TradeManager tradeManager, DataManager dataManager, MarketRadar radar) {
        this.ibkrService = ibkr;
        this.strategies = strategies;
        this.tradeManager = tradeManager;
        this.dataManager = dataManager;
        this.radar = radar;
    }

    // =========================================================================
    // 👉 EL NUEVO CORAZÓN: Evalúa un ticker solo si tiene la data completa
    // =========================================================================
    public void evaluate(String ticker) {
        // 1. Filtro de seguridad: ¿Están las 4 temporalidades descargadas?
        if (!dataManager.isTickerReady(ticker)) {
            return;
        }

        // 2. Filtro de inventario: ¿Ya operamos este ticker hoy?
        if (activeOrders.contains(ticker)) {
            return;
        }

        // 3. Obtenemos la serie de 5m (nuestro reloj para el precio actual)
        BarSeries series5m = dataManager.getSeries(ticker, TimeFrame.MIN_5);
        if (series5m == null || series5m.isEmpty()) return;

        double currentPrice = series5m.getLastBar().getClosePrice().doubleValue();
        java.time.ZonedDateTime currentTime = series5m.getLastBar().getEndTime();

        // 4. Bucle de estrategias
        for (TradingStrategy strategy : strategies) {

            // Filtro Macro del Radar (Opcional pero recomendado)
            boolean isCall = strategy.getName().toLowerCase().contains("call");
            if (!radar.isMacroFavorable(isCall)) continue;

            if (strategy.isTriggered(ticker, dataManager, currentTime)) {
                System.out.println("🎯 [SIGNAL] " + strategy.getName() + " en " + ticker + " a $" + currentPrice);

                activeOrders.add(ticker);
                tradeManager.evaluateSignal(ticker, strategy.getName(), currentPrice);
            }
        }
    }

    // =========================================================================
    // 👉 EL POLLING CENTRALIZADO: Escanea todos los tickers activos
    // =========================================================================
    public void startLiveScanner(List<String> activeTickers) {
        System.out.println("📡 [StrategyEngine] Iniciando escáner centralizado cada 60 segundos...");

        scheduler.scheduleAtFixedRate(() -> {
            for (String ticker : activeTickers) {
                try {
                    evaluate(ticker);
                } catch (Exception e) {
                    System.err.println("❌ Error evaluando " + ticker + ": " + e.getMessage());
                }
            }
        }, 10, 60, TimeUnit.SECONDS); // Empieza en 10s, repite cada 60s
    }

    public void startMaintenanceScheduler() {
        scheduler.scheduleAtFixedRate(() -> {
            activeOrders.clear();
            System.out.println("🧹 [MAINTENANCE] Inventario diario limpiado.");
        }, 1, 1, TimeUnit.DAYS);
    }

    // ... (resto del código del motor) ...

    /**
     * 👉 DETENCIÓN SEGURA: Apaga los hilos del escáner y mantenimiento.
     * Es vital llamarlo al cerrar la aplicación para liberar memoria y sockets.
     */
    public void shutdown() {
        System.out.println("🛑 [StrategyEngine] Apagando escáner y liberando recursos...");
        try {
            scheduler.shutdown(); // Intento de apagado suave
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow(); // Apagado forzoso si no responde en 5s
            }
            System.out.println("✅ [StrategyEngine] Hilos cerrados correctamente.");
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}