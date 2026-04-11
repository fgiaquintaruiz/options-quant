package com.fgiaquinta.optionsquant.engine;

import java.util.HashMap;
import java.util.Map;

public class FastBacktester {

    // Global accumulators
    private double globalProfitPct = 0.0;
    private int globalWins = 0;
    private int globalLosses = 0;

    // Strategy breakdown trackers
    private final Map<String, Double> strategyProfits = new HashMap<>();
    private final Map<String, Integer> strategyWins = new HashMap<>();
    private final Map<String, Integer> strategyLosses = new HashMap<>();

    /**
     * Executes a strategy in diagnostic mode to visually audit signals.
     */
    public void runDiagnosticVerification(String ticker, org.ta4j.core.BarSeries series, com.fgiaquinta.optionsquant.strategies.TradingStrategy customStrategy, org.ta4j.core.Strategy ta4jStrategy) {
        System.out.println("=========================================================");
        System.out.println("🔍 INICIANDO AUDITORÍA VISUAL: " + customStrategy.getName() + " en " + ticker);
        System.out.println("=========================================================");

        org.ta4j.core.BarSeriesManager seriesManager = new org.ta4j.core.BarSeriesManager(series);
        org.ta4j.core.TradingRecord tradingRecord = seriesManager.run(ta4jStrategy);

        java.time.format.DateTimeFormatter formatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
        String stratName = customStrategy.getName();

        // Initialize strategy in maps if not present
        strategyProfits.putIfAbsent(stratName, 0.0);
        strategyWins.putIfAbsent(stratName, 0);
        strategyLosses.putIfAbsent(stratName, 0);

        if (tradingRecord.getPositions().isEmpty()) {
            System.out.println("⚠️ No se generaron señales para este periodo.");
            return;
        }

        double localProfitPct = 0.0;
        int localWins = 0;
        int localLosses = 0;

        for (org.ta4j.core.Position position : tradingRecord.getPositions()) {
            int entryIndex = position.getEntry().getIndex();
            java.time.ZonedDateTime entryTimeNY = series.getBar(entryIndex).getEndTime();
            java.time.ZonedDateTime entryTimeLocal = entryTimeNY.withZoneSameInstant(java.time.ZoneId.systemDefault());
            double entryPrice = position.getEntry().getNetPrice().doubleValue();

            int exitIndex = position.getExit().getIndex();
            java.time.ZonedDateTime exitTimeNY = series.getBar(exitIndex).getEndTime();
            java.time.ZonedDateTime exitTimeLocal = exitTimeNY.withZoneSameInstant(java.time.ZoneId.systemDefault());
            double exitPrice = position.getExit().getNetPrice().doubleValue();

            boolean isLong = position.getEntry().isBuy();
            double tradePct = isLong ? ((exitPrice - entryPrice) / entryPrice) * 100.0 : ((entryPrice - exitPrice) / entryPrice) * 100.0;

            boolean isWin = tradePct > 0;
            if (isWin) {
                localWins++;
                globalWins++;
                strategyWins.put(stratName, strategyWins.get(stratName) + 1);
            } else {
                localLosses++;
                globalLosses++;
                strategyLosses.put(stratName, strategyLosses.get(stratName) + 1);
            }

            localProfitPct += tradePct;
            globalProfitPct += tradePct;
            strategyProfits.put(stratName, strategyProfits.get(stratName) + tradePct);

            System.out.printf("🎯 TRADE #%d [%s]%n", tradingRecord.getPositions().indexOf(position) + 1, stratName);
            System.out.printf("   🟢 ENTRADA: %s | Precio: $%.2f | Índice Vela: %d%n", entryTimeLocal.format(formatter), entryPrice, entryIndex);
            System.out.printf("   🔴 SALIDA:  %s | Precio: $%.2f | Índice Vela: %d%n", exitTimeLocal.format(formatter), exitPrice, exitIndex);
            System.out.printf("   %s RESULTADO: %.2f%%%n", (isWin ? "✅" : "❌"), tradePct);
            System.out.println("   ------------------------------------------------------");
        }

        System.out.println("📊 RESUMEN PARCIAL (" + ticker + "):");
        System.out.printf("   Trades: %d | Aciertos: %d | Fallos: %d | Rendimiento: %.2f%%%n",
                (localWins + localLosses), localWins, localLosses, localProfitPct);
        System.out.println("=========================================================\n");
    }

    /**
     * Prints the final global summary with breakdown per strategy.
     */
    public void printGlobalSummary() {
        System.out.println("\n#########################################################");
        System.out.println("🏆 RESUMEN GLOBAL DEL BACKTEST");
        System.out.println("#########################################################");

        System.out.println("\n📈 DESGLOSE POR ESTRATEGIA:");
        for (Map.Entry<String, Double> entry : strategyProfits.entrySet()) {
            String strat = entry.getKey();
            int w = strategyWins.get(strat);
            int l = strategyLosses.get(strat);
            int total = w + l;

            if (total > 0) {
                System.out.printf("   🔹 %s -> Trades: %d | Aciertos: %d | Fallos: %d | Rendimiento: %s%.2f%%%n",
                        strat, total, w, l, (entry.getValue() >= 0 ? "+" : ""), entry.getValue());
            }
        }

        System.out.println("\n🌎 TOTAL ACUMULADO GLOBAL:");
        System.out.printf("   Total Trades: %d | Aciertos: %d | Fallos: %d%n", (globalWins + globalLosses), globalWins, globalLosses);
        System.out.printf("   💰 Rendimiento Total: %s%.2f%%%n", (globalProfitPct >= 0 ? "+" : ""), globalProfitPct);
        System.out.println("#########################################################\n");
    }
}