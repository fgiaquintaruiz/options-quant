package com.fgiaquinta.optionsquant.engine;

import com.fgiaquinta.optionsquant.services.IbkrService;
import com.fgiaquinta.optionsquant.strategies.TradingStrategy;
import com.fgiaquinta.optionsquant.utils.DataManager;
import com.fgiaquinta.optionsquant.models.TimeFrame;

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
    private final DataManager dataManager; // Inyectado

    // Filtro de inventario: Guarda los tickers que ya tienen una orden activa hoy
    private final Set<String> activeOrders = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

    private List<String> activeTickers;
    private int currentMinuteTick = 0;

    public StrategyEngine(IbkrService ibkr, List<TradingStrategy> strategies, TradeManager tradeManager, DataManager dataManager) {
        this.ibkrService = ibkr;
        this.strategies = strategies;
        this.tradeManager = tradeManager;
        this.dataManager = dataManager;
    }

    public List<TradingStrategy> getStrategies() {
        return strategies;
    }

    public void setActiveTickers(List<String> tickers) {
        this.activeTickers = tickers;
    }

    // ==========================================================
    // 👉 NUEVO MOTOR: STAGGERED POLLER
    // ==========================================================
    public void startStaggeredPolling() {
        System.out.println("⏱️ [StrategyEngine] Iniciando Staggered Poller (Loop de 1 minuto)...");

        scheduler.scheduleAtFixedRate(() -> {
            try {
                executeMinuteTick();
            } catch (Exception e) {
                System.err.println("❌ [StrategyEngine] Error crítico en el Poller: " + e.getMessage());
                e.printStackTrace();
            }
        }, 0, 1, TimeUnit.MINUTES);
    }

    private void executeMinuteTick() {
        currentMinuteTick++;

        if (activeTickers == null || activeTickers.isEmpty()) return;

        // 1. ACTUALIZAR MAPA MAYOR (1 Día) - Minuto 1, y luego cada 4 horas (240 mins)
        if (currentMinuteTick == 1 || currentMinuteTick % 240 == 0) {
            System.out.println("🔄 [Poller] Actualizando mapa mayor de 1 DÍA...");
            for (String ticker : activeTickers) {
                ibkrService.requestHistoricalDataForCache(ticker, TimeFrame.DAY_1);
            }
        }

        // 2. ACTUALIZAR CONTEXTO (1 Hora) - Cada 60 minutos
        if (currentMinuteTick % 60 == 0) {
            System.out.println("🔄 [Poller] Actualizando contexto de 1 HORA...");
            for (String ticker : activeTickers) {
                ibkrService.requestHistoricalDataForCache(ticker, TimeFrame.HOUR_1);
            }
        }

        // 3. ACTUALIZAR CONFIRMACIONES (15 Minutos) - Cada 15 minutos
        if (currentMinuteTick % 15 == 0) {
            System.out.println("🔄 [Poller] Actualizando confirmaciones de 15 MINUTOS...");
            for (String ticker : activeTickers) {
                ibkrService.requestHistoricalDataForCache(ticker, TimeFrame.MIN_15);
            }
        }

        // 4. ACTUALIZAR GATILLO (5 Minutos) - STAGGERED (Escalonado)
        // Dividimos la carga de tickers en 5 bloques para no saturar IBKR
        int bucketSize = Math.max(1, activeTickers.size() / 5);
        int startIndex = (currentMinuteTick % 5) * bucketSize;
        int endIndex = Math.min(startIndex + bucketSize, activeTickers.size());

        for (int i = startIndex; i < endIndex; i++) {
            String ticker = activeTickers.get(i);

            // Si ya entramos en un trade hoy para este ticker, lo ignoramos
            if (activeOrders.contains(ticker)) continue;

            // Pedimos gráfica rápida de 5 minutos
            ibkrService.requestHistoricalDataForCache(ticker, TimeFrame.MIN_5);

            // Verificamos si la caché ya descargó TODO (Día, 1H, 15m, 5m)
            if (dataManager.hasAllRequiredData(ticker)) {
                evaluateStrategiesForTicker(ticker);
            }
        }
    }

    private void evaluateStrategiesForTicker(String ticker) {
        // 1. Obtenemos la serie rápida (5m) para saber el precio de ejecución
        org.ta4j.core.BarSeries series5m = dataManager.getSeries(ticker, TimeFrame.MIN_5);
        if (series5m == null || series5m.isEmpty()) return;

        double currentPrice = series5m.getLastBar().getClosePrice().doubleValue();

        // 2. Obtenemos el tiempo actual de la última vela (NY Time)
        java.time.ZonedDateTime currentTime = series5m.getLastBar().getEndTime();

        for (TradingStrategy strategy : strategies) {

            // 👉 LLAMADA CORREGIDA: Ahora le pasamos el Ticker, el DataManager y el Tiempo
            if (strategy.isTriggered(ticker, dataManager, currentTime)) {

                System.out.println("🎯 [StrategyEngine] SIGNAL TRIGGERED by " + strategy.getName() + " on " + ticker);

                activeOrders.add(ticker); // Evita entrar múltiples veces en el mismo ticker hoy

                System.out.println("🚀 [StrategyEngine] Delegating to TradeManager...");
                tradeManager.evaluateSignal(ticker, strategy.getName(), currentPrice);
            }
        }
    }

    // ==========================================================
    // 👉 INTACTO: MÉTODOS DE MANTENIMIENTO ORIGINALES
    // ==========================================================
    public void clearInventory() {
        activeOrders.clear();
    }

    public void shutdown() {
        scheduler.shutdownNow();
    }

    public void startMaintenanceScheduler() {
        scheduler.scheduleAtFixedRate(() -> {
            clearInventory();
            System.out.println("🧹 [MAINTENANCE] Daily inventory cleared.");
        }, 1, 1, TimeUnit.DAYS);
    }
}