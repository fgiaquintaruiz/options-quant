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
    private final TradeManager tradeManager; // INJECTED NEW MANAGER

    // Inventory filter: Stores tickers that already have an active order today
    private final Set<String> activeOrders = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    public StrategyEngine(IbkrService ibkr, List<TradingStrategy> strategies, TradeManager tradeManager) {
        this.ibkrService = ibkr;
        this.strategies = strategies;
        this.tradeManager = tradeManager;

        // Auto-maintenance: Clear inventory every 24 hours for the new trading day
        scheduler.scheduleAtFixedRate(() -> {
            clearInventory();
            System.out.println("🧹 [MAINTENANCE] Daily inventory cleared. Ready for the next session.");
        }, 12, 24, TimeUnit.HOURS);
    }

    public void onBarAdded(String ticker, BarSeries series1h) {
        BarSeries spy = ibkrService.getSeries("SPY", TimeFrame.HOUR_1);
        if (spy == null || spy.isEmpty()) return;

        int lastIdx = series1h.getEndIndex();

        for (TradingStrategy strategy : strategies) {
            if (strategy.isTriggered(lastIdx, series1h, spy)) {

                double entry = series1h.getBar(lastIdx).getClosePrice().doubleValue();

                System.out.println("🎯 TECHNICAL SIGNAL DETECTED: " + strategy.getName() + " on " + ticker);

                // DELEGATE TO TRADE MANAGER:
                // TradeManager will calculate ATR, check Capital/Risk limits,
                // handle Staircase Re-entry, and route the order (Auto vs Manual)
                tradeManager.evaluateSignal(ticker, strategy.getName(), entry, series1h);
            }
        }
    }

    public void clearInventory() {
        activeOrders.clear();
    }

    public void shutdown() {
        scheduler.shutdownNow();
    }
}