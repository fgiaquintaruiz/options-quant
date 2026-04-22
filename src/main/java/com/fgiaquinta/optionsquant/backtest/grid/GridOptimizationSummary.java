package com.fgiaquinta.optionsquant.backtest.grid;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Best admissible cell under optional constraints (min trades, max drawdown) and primary metric.
 */
public record GridOptimizationSummary(
        boolean hasWinner,
        Integer bestCellIndex,
        Map<String, Double> bestParameters,
        Double bestMetricValue,
        String detailMessage
) {
    static GridOptimizationSummary summarize(
            List<GridCellResult> rows,
            Integer constraintMinTrades,
            Double constraintMaxDrawdownPct,
            String primaryMetric) {
        if (rows.isEmpty()) {
            return new GridOptimizationSummary(false, null, Map.of(), null, "no rows");
        }
        String metric = primaryMetric == null || primaryMetric.isBlank()
                ? "TOTAL_PNL"
                : primaryMetric.trim().toUpperCase(Locale.ROOT);
        List<GridCellResult> admissible = rows.stream()
                .filter(r -> constraintMinTrades == null || r.totalTrades() >= constraintMinTrades)
                .filter(r -> constraintMaxDrawdownPct == null || r.maxDrawdownPct() <= constraintMaxDrawdownPct)
                .toList();
        if (admissible.isEmpty()) {
            return new GridOptimizationSummary(false, null, Map.of(), null,
                    "no admissible cell (constraintMinTrades=%s, constraintMaxDrawdownPct=%s)"
                            .formatted(constraintMinTrades, constraintMaxDrawdownPct));
        }
        Comparator<GridCellResult> cmp = Comparator.comparingDouble(r -> metricValue(r, metric));
        GridCellResult best = admissible.stream().max(cmp).orElseThrow();
        double mv = metricValue(best, metric);
        return new GridOptimizationSummary(true, best.cellIndex(), best.parameters(), mv,
                "best admissible by " + metric);
    }

    private static double metricValue(GridCellResult r, String metric) {
        return switch (metric) {
            case "PROFIT_FACTOR" -> r.profitFactor();
            case "WIN_RATE" -> r.winRate();
            default -> r.totalPnl();
        };
    }
}
