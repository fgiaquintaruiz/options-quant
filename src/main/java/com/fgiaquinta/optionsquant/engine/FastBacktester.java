package com.fgiaquinta.optionsquant.engine;

import org.ta4j.core.BarSeries;
import org.ta4j.core.BarSeriesManager;
import org.ta4j.core.Strategy;
import org.ta4j.core.TradingRecord;
import org.ta4j.core.criteria.pnl.ProfitLossPercentageCriterion;
import org.ta4j.core.criteria.WinningPositionsRatioCriterion;
import org.ta4j.core.criteria.MaximumDrawdownCriterion;

public class FastBacktester {

    // Runs a simulation and returns the metrics formatted as a JSON string
    public String runSimulation(String ticker, String strategyName, BarSeries series, Strategy strategy) {
        BarSeriesManager seriesManager = new BarSeriesManager(series);
        TradingRecord tradingRecord = seriesManager.run(strategy);

        int totalTrades = tradingRecord.getPositionCount();

        // If no trades occurred, return a baseline JSON
        if (totalTrades == 0) {
            return String.format(
                    "{\"ticker\": \"%s\", \"strategy\": \"%s\", \"totalTrades\": 0, \"winRate\": 0.0, \"profitFactor\": 0.0, \"maxDrawdown\": 0.0}",
                    ticker, strategyName
            );
        }

        // Calculate performance criteria using ta4j
        double winRate = new WinningPositionsRatioCriterion().calculate(series, tradingRecord).doubleValue();
        double maxDrawdown = new MaximumDrawdownCriterion().calculate(series, tradingRecord).doubleValue();

        // Simplified Profit Factor calculation for the prompt context
        double totalProfit = new ProfitLossPercentageCriterion().calculate(series, tradingRecord).doubleValue();

        // Format the output specifically for the LLM prompt
        return String.format(
                "{\"ticker\": \"%s\", \"strategy\": \"%s\", \"totalTrades\": %d, \"winRate\": %.2f, \"totalProfitPct\": %.2f, \"maxDrawdown\": %.2f}",
                ticker, strategyName, totalTrades, winRate, totalProfit, maxDrawdown
        );
    }
}