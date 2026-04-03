package com.fgiaquinta.optionsquant.engine;

import com.fgiaquinta.optionsquant.strategies.TradingStrategy;
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

    // Archivo: src/main/java/com/fgiaquinta/optionsquant/engine/FastBacktester.java

    /**
     * Ejecuta una estrategia en modo diagnóstico para auditar visualmente las señales
     * en tu plataforma de gráficos (TradingView, IBKR, etc.).
     */
    public void runDiagnosticVerification(String ticker, BarSeries series, TradingStrategy customStrategy, Strategy ta4jStrategy) {
        System.out.println("=========================================================");
        System.out.println("🔍 INICIANDO AUDITORÍA VISUAL: " + customStrategy.getName() + " en " + ticker);
        System.out.println("=========================================================");

        // Ejecutamos el motor de backtest de ta4j
        org.ta4j.core.BarSeriesManager seriesManager = new org.ta4j.core.BarSeriesManager(series);
        org.ta4j.core.TradingRecord tradingRecord = seriesManager.run(ta4jStrategy);

        java.time.format.DateTimeFormatter formatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

        if (tradingRecord.getPositions().isEmpty()) {
            System.out.println("⚠️ No se generaron señales para este periodo. Revisa la lógica de entrada.");
            return;
        }

        int winCount = 0;
        int lossCount = 0;

        for (org.ta4j.core.Position position : tradingRecord.getPositions()) {
            // Extraer info de Entrada
            int entryIndex = position.getEntry().getIndex();
            java.time.ZonedDateTime entryTime = series.getBar(entryIndex).getEndTime();
            double entryPrice = position.getEntry().getNetPrice().doubleValue();

            // Extraer info de Salida
            int exitIndex = position.getExit().getIndex();
            java.time.ZonedDateTime exitTime = series.getBar(exitIndex).getEndTime();
            double exitPrice = position.getExit().getNetPrice().doubleValue();

            org.ta4j.core.num.Num profit = position.getProfit();
            boolean isWin = profit.isPositive();
            if (isWin) winCount++; else lossCount++;

            System.out.printf("🎯 TRADE #%d%n", tradingRecord.getPositions().indexOf(position) + 1);
            System.out.printf("   🟢 ENTRADA: %s | Precio: $%.2f | Índice Vela: %d%n", entryTime.format(formatter), entryPrice, entryIndex);
            System.out.printf("   🔴 SALIDA:  %s | Precio: $%.2f | Índice Vela: %d%n", exitTime.format(formatter), exitPrice, exitIndex);
            System.out.printf("   %s RESULTADO: %.2f%%%n", (isWin ? "✅" : "❌"), profit.doubleValue());
            System.out.println("   ------------------------------------------------------");
        }

        System.out.println("📊 RESUMEN DEL DIAGNÓSTICO:");
        System.out.printf("   Total Trades: %d | Aciertos: %d | Fallos: %d%n", tradingRecord.getPositions().size(), winCount, lossCount);
        System.out.println("=========================================================\n");
    }
}