package com.fgiaquinta.optionsquant.engine;

import com.fgiaquinta.optionsquant.models.OptimizationResult;
import org.ta4j.core.BarSeries;
import org.ta4j.core.Strategy;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class PreMarketRoutine {
    private final FastBacktester backtester;
    private final AiStrategyOptimizer optimizer;

    // Memory map storing today's custom AI parameters per ticker
    private final Map<String, OptimizationResult> dailyOverrides = new ConcurrentHashMap<>();

    public PreMarketRoutine(FastBacktester backtester, AiStrategyOptimizer optimizer) {
        this.backtester = backtester;
        this.optimizer = optimizer;
    }

    // Call this method during the pre-market data loading phase
    public void optimizeTicker(String ticker, String strategyName, BarSeries historicalSeries, Strategy strategy) {
        System.out.println("Running JIT Backtest for: " + ticker);

        // 1. Generate historical performance metrics
        String metricsJson = backtester.runSimulation(ticker, strategyName, historicalSeries, strategy);

        // 2. Feed metrics to Gemini to get today's optimal ATR multipliers
        OptimizationResult optimizedParams = optimizer.analyzeBacktest(metricsJson);

        if (optimizedParams != null) {
            dailyOverrides.put(ticker + "_" + strategyName, optimizedParams);
            System.out.printf("AI Tuned %s: TP ATR = %.2f | SL ATR = %.2f | Insight: %s%n",
                    ticker, optimizedParams.recommendedTpAtr, optimizedParams.recommendedSlAtr, optimizedParams.insight);
        }
    }

    public OptimizationResult getOverridesFor(String ticker, String strategyName) {
        return dailyOverrides.get(ticker + "_" + strategyName);
    }
}