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
    }

    public void onBarAdded(String ticker, com.fgiaquinta.optionsquant.models.TimeFrame timeFrame, org.ta4j.core.BarSeries series) {
        System.out.println("⚙️ [StrategyEngine] onBarAdded entered for " + ticker + " [" + timeFrame + "]");

        // Only evaluate on the 1-minute chart
        if (timeFrame != com.fgiaquinta.optionsquant.models.TimeFrame.MIN_1) {
            System.out.println("⏭️ [StrategyEngine] Skipped: TimeFrame is not MIN_1 (Current: " + timeFrame + ")");
            return;
        }

        // Avoid double entries
        if (activeOrders != null && activeOrders.contains(ticker)) {
            System.out.println("⏭️ [StrategyEngine] Skipped: Active order already exists for " + ticker);
            return;
        }

        // Benchmark check
        org.ta4j.core.BarSeries spy = ibkrService.getSeries("SPY", com.fgiaquinta.optionsquant.models.TimeFrame.HOUR_1);
        if (spy == null) {
            System.out.println("⏳ [StrategyEngine] Skipped: SPY Hour_1 series is NULL.");
            return;
        }
        if (spy.isEmpty()) {
            System.out.println("⏳ [StrategyEngine] Skipped: SPY Hour_1 series is EMPTY.");
            return;
        }

        int lastIdx = series.getEndIndex();
        System.out.println("🔍 [StrategyEngine] Evaluating " + strategies.size() + " strategies for " + ticker + " at index " + lastIdx);

        for (TradingStrategy strategy : strategies) {
            System.out.println("   -> Testing strategy: " + strategy.getName());

            if (strategy.isTriggered(lastIdx, series, spy)) {
                System.out.println("🎯 [StrategyEngine] SIGNAL TRIGGERED by " + strategy.getName() + " on " + ticker);

                double entry = series.getBar(lastIdx).getClosePrice().doubleValue();

                if (activeOrders != null) {
                    activeOrders.add(ticker);
                }

                System.out.println("🚀 [StrategyEngine] Delegating to TradeManager...");
                tradeManager.evaluateSignal(ticker, strategy.getName(), entry);
            } else {
                System.out.println("   -> Conditions not met for " + strategy.getName());
            }
        }
    }

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
        }, 12, 24, TimeUnit.HOURS);
    }
}