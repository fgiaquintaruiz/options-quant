package com.fgiaquinta.optionsquant.engine;

import com.fgiaquinta.optionsquant.models.OptimizationResult;
import com.fgiaquinta.optionsquant.services.IbkrService;
import com.fgiaquinta.optionsquant.strategies.TradingStrategy;
import org.ta4j.core.*;
import org.ta4j.core.rules.AbstractRule;
import org.ta4j.core.rules.BooleanRule;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class PreMarketRoutine {
    // Memory map storing today's custom AI parameters per ticker
    private final Map<String, OptimizationResult> dailyOverrides = new ConcurrentHashMap<>();
    private boolean safeToTrade = true; // Por defecto asumimos que es seguro

    private final FastBacktester backtester;
    private final AiStrategyOptimizer optimizer;
    private final IbkrService ibkrService;

    public PreMarketRoutine(FastBacktester backtester, AiStrategyOptimizer optimizer, IbkrService ibkrService) {
        this.backtester = backtester;
        this.optimizer = optimizer;
        this.ibkrService = ibkrService;
    }

    /**
     * Runs the simulation for a specific strategy and returns the metrics as a JSON string.
     */
    public String generateMetrics(String ticker, String strategyName, BarSeries historicalSeries, TradingStrategy strategy) {
        System.out.println("📊 [PreMarket] Running simulation for: " + strategyName);

        BarSeries spyDaily = ibkrService.getSeries("SPY", com.fgiaquinta.optionsquant.models.TimeFrame.DAY_1);

        // 1. Map custom TradingStrategy to native ta4j Strategy
        org.ta4j.core.Strategy ta4jStrategy = mapToTa4jStrategy(strategy, historicalSeries, spyDaily);

        // 2. Run simulation and return the raw JSON metrics
        return backtester.runSimulation(ticker, strategyName, historicalSeries, ta4jStrategy);
    }

    public OptimizationResult getOverridesFor(String ticker, String strategyName) {
        return dailyOverrides.get(ticker + "_" + strategyName);
    }

    // We now pass the IbkrService and the strategies list to fetch the required arguments
    // PreMarketRoutine.java

    public void runDailyAnalysis(String ticker, IbkrService ibkrService, List<TradingStrategy> strategies) {
        System.out.println("🧠 [PreMarket] Starting batch optimization for " + ticker);

        BarSeries historicalSeries = ibkrService.getSeries(ticker, com.fgiaquinta.optionsquant.models.TimeFrame.DAY_1);
        if (historicalSeries == null || historicalSeries.isEmpty()) {
            System.err.println("❌ No historical data for " + ticker);
            return;
        }

        // Map to collect: StrategyName -> MetricsJson
        Map<String, String> allMetrics = new HashMap<>();

        for (TradingStrategy strategy : strategies) {
            // Use the refactored method to get metrics
            String json = generateMetrics(ticker, strategy.getName(), historicalSeries, strategy);
            allMetrics.put(strategy.getName(), json);

            // Throttling local para no estresar la CPU
            try { Thread.sleep(100); } catch (InterruptedException e) {}
        }

        // 3. ONE SINGLE CALL to Gemini for all strategies
        List<OptimizationResult> optimizedResults = optimizer.analyzeBatch(allMetrics);

        // 4. Save results to dailyOverrides
        if (optimizedResults != null) {
            for (OptimizationResult res : optimizedResults) {
                String key = ticker + "_" + res.strategy;
                dailyOverrides.put(key, res);
                System.out.printf("✅ [AI Tuned] %s: TP %.2f | SL %.2f%n", res.strategy, res.recommendedTpAtr, res.recommendedSlAtr);
            }
        }

        this.safeToTrade = true;
    }

    // Update your getOverridesFor method to accept the exact composite key
    public com.fgiaquinta.optionsquant.models.OptimizationResult getOverridesFor(String compositeKey) {
        if (dailyOverrides == null) return null;
        return dailyOverrides.get(compositeKey);
    }

    // Método que el Main usa para saber si debe continuar o abortar
    public boolean isSafeToTrade() {
        return safeToTrade;
    }

    /**
     * Adapts your custom TradingStrategy into a ta4j native Strategy.
     */
    private Strategy mapToTa4jStrategy(TradingStrategy customStrategy, BarSeries targetSeries, BarSeries benchmarkSeries) {
        // Create an entry rule that bridges to your custom isTriggered logic
        Rule customEntryRule = new AbstractRule() {
            @Override
            public boolean isSatisfied(int index, TradingRecord tradingRecord) {
                if (customStrategy == null) return false;

                // Call your existing dynamic logic
                return customStrategy.isTriggered(index, targetSeries, benchmarkSeries);
            }
        };

        // For the exit rule, FastBacktester usually manages exits via TP/SL simulations.
        // If your backtester requires an active exit rule to close positions, you can replace BooleanRule.FALSE
        // with a StopLossRule or StopGainRule from ta4j.
        Rule dummyExitRule = new BooleanRule(false);

        // Combine them into a ta4j BaseStrategy
        return new BaseStrategy(customStrategy.getName(), customEntryRule, dummyExitRule);
    }
}