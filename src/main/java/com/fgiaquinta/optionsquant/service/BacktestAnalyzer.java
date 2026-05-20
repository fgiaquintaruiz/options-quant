package com.fgiaquinta.optionsquant.service;

import com.fgiaquinta.optionsquant.backtest.domain.BacktestReport;
import com.fgiaquinta.optionsquant.backtest.domain.TradeRecord;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Analyzes backtest results and provides actionable insights for strategy improvements.
 * Reads trades.csv and generates recommendations based on performance patterns.
 */
@Slf4j
@Service
public class BacktestAnalyzer {

    private static final Path DEFAULT_TRADES_CSV = Path.of("backtest/trades.csv");

    /**
     * Analyzes the trades.csv file and returns improvement suggestions.
     */
    public AnalysisReport analyzeTrades(Path csvPath) {
        if (!Files.exists(csvPath)) {
            log.warn("No trades.csv found at {}. Cannot analyze.", csvPath.toAbsolutePath());
            return new AnalysisReport("No trades.csv found", List.of());
        }

        try {
            List<TradeRecord> trades = loadTradesFromCsv(csvPath);
            if (trades.isEmpty()) {
                return new AnalysisReport("No trades loaded", List.of());
            }

            List<String> suggestions = new ArrayList<>();
            
            // Run all analyses
            suggestions.addAll(analyzePositionSizing(trades));
            suggestions.addAll(analyzeStrategyPerformance(trades));
            suggestions.addAll(analyzeExitPatterns(trades));
            suggestions.addAll(analyzeTimePatterns(trades));
            suggestions.addAll(analyzeRiskMetrics(trades));
            suggestions.addAll(analyzeTickerConcentration(trades));

            return new AnalysisReport(
                    String.format("Analyzed %d trades", trades.size()),
                    suggestions
            );
        } catch (Exception e) {
            log.error("Failed to analyze trades: {}", e.getMessage(), e);
            return new AnalysisReport("Analysis failed: " + e.getMessage(), List.of());
        }
    }

    /**
     * Analyzes position sizing for anomalies.
     */
    private List<String> analyzePositionSizing(List<TradeRecord> trades) {
        List<String> suggestions = new ArrayList<>();
        
        Map<String, List<TradeRecord>> byStrategy = trades.stream()
                .collect(Collectors.groupingBy(TradeRecord::strategy));

        for (Map.Entry<String, List<TradeRecord>> entry : byStrategy.entrySet()) {
            String strategy = entry.getKey();
            List<TradeRecord> strategyTrades = entry.getValue();
            
            int maxQty = strategyTrades.stream().mapToInt(TradeRecord::quantity).max().orElse(0);
            int minQty = strategyTrades.stream().mapToInt(TradeRecord::quantity).min().orElse(0);
            
            // Flag if max quantity is more than 3x min (position sizing bug)
            if (minQty > 0 && maxQty > minQty * 3) {
                suggestions.add(String.format(
                        "🚨 POSITION SIZING BUG: %s has qty range %d-%d contracts. " +
                        "Check AccountManager.calculateQuantity() for this strategy.",
                        strategy, minQty, maxQty
                ));
            }
            
            // Suggest capping position size
            if (maxQty > 10) {
                suggestions.add(String.format(
                        "⚠️ HIGH EXPOSURE: %s traded up to %d contracts. " +
                        "Consider capping at 10 contracts per trade to limit risk.",
                        strategy, maxQty
                ));
            }
        }

        return suggestions;
    }

    /**
     * Analyzes strategy performance and suggests improvements.
     */
    private List<String> analyzeStrategyPerformance(List<TradeRecord> trades) {
        List<String> suggestions = new ArrayList<>();
        
        Map<String, List<TradeRecord>> byStrategy = trades.stream()
                .collect(Collectors.groupingBy(TradeRecord::strategy));

        for (Map.Entry<String, List<TradeRecord>> entry : byStrategy.entrySet()) {
            String strategy = entry.getKey();
            List<TradeRecord> strategyTrades = entry.getValue();
            
            int totalTrades = strategyTrades.size();
            int wins = (int) strategyTrades.stream().filter(t -> t.netPnl() > 0).count();
            double winRate = (double) wins / totalTrades;
            
            double totalPnl = strategyTrades.stream().mapToDouble(TradeRecord::netPnl).sum();
            double avgWin = strategyTrades.stream().filter(t -> t.netPnl() > 0)
                    .mapToDouble(TradeRecord::netPnl).average().orElse(0);
            double avgLoss = strategyTrades.stream().filter(t -> t.netPnl() < 0)
                    .mapToDouble(TradeRecord::netPnl).average().orElse(0);
            
            // Low win rate warning
            if (totalTrades >= 5 && winRate < 0.40) {
                suggestions.add(String.format(
                        "📉 LOW WIN RATE: %s has %.0f%% win rate (%d/%d trades). " +
                        "Consider: (1) Widening ATR stop multiplier, (2) Better entry timing, " +
                        "(3) Disabling this strategy until market conditions improve.",
                        strategy, winRate * 100, wins, totalTrades
                ));
            }
            
            // Poor risk/reward
            if (avgLoss != 0 && Math.abs(avgWin / avgLoss) < 1.0 && winRate < 0.50) {
                suggestions.add(String.format(
                        "⚖️ POOR RISK/REWARD: %s avg win $%.0f vs avg loss $%.0f. " +
                        "Increase TP ATR multiplier or decrease SL multiplier.",
                        strategy, avgWin, avgLoss
                ));
            }
            
            // Consistently profitable - suggest increasing allocation
            if (totalTrades >= 5 && winRate > 0.65 && totalPnl > 0) {
                suggestions.add(String.format(
                        "✅ HIGH PERFORMER: %s has %.0f%% win rate with $%.0f total PnL. " +
                        "Consider increasing risk allocation for this strategy.",
                        strategy, winRate * 100, totalPnl
                ));
            }
        }

        // CALL vs PUT comparison
        List<TradeRecord> calls = trades.stream()
                .filter(t -> t.direction().equals("CALL")).collect(Collectors.toList());
        List<TradeRecord> puts = trades.stream()
                .filter(t -> t.direction().equals("PUT")).collect(Collectors.toList());
        
        if (!calls.isEmpty() && !puts.isEmpty()) {
            double callWinRate = (double) calls.stream().filter(t -> t.netPnl() > 0).count() / calls.size();
            double putWinRate = (double) puts.stream().filter(t -> t.netPnl() > 0).count() / puts.size();
            
            if (Math.abs(callWinRate - putWinRate) > 0.20) {
                suggestions.add(String.format(
                        "🔄 DIRECTION BIAS: CALL win rate %.0f%% vs PUT %.0f%%. " +
                        "Review entry/exit logic asymmetry between call and put strategies.",
                        callWinRate * 100, putWinRate * 100
                ));
            }
        }

        return suggestions;
    }

    /**
     * Analyzes exit patterns (SL vs TP vs EOS).
     */
    private List<String> analyzeExitPatterns(List<TradeRecord> trades) {
        List<String> suggestions = new ArrayList<>();
        
        long totalTrades = trades.size();
        long slCount = trades.stream().filter(t -> "SL".equals(t.exitReason())).count();
        long tpCount = trades.stream().filter(t -> "TP".equals(t.exitReason())).count();
        long eosCount = trades.stream().filter(t -> "EOS".equals(t.exitReason())).count();
        
        double slRate = (double) slCount / totalTrades;
        double tpRate = (double) tpCount / totalTrades;
        
        // Too many stop losses
        if (slRate > 0.60) {
            suggestions.add(String.format(
                    "🛑 HIGH SL RATE: %.0f%% of trades hitting stop loss (%d/%d). " +
                    "Recommendations: (1) Increase SL ATR multiplier by 0.3-0.5, " +
                    "(2) Review entry timing - may be entering on false breakouts, " +
                    "(3) Consider using volatility-based stops instead of fixed ATR.",
                    slRate * 100, slCount, totalTrades
            ));
        }
        
        // Good TP rate
        if (tpRate > 0.50) {
            suggestions.add(String.format(
                    "✅ GOOD TP RATE: %.0f%% of trades hitting take profit (%d/%d). " +
                    "Strategy entries are well-timed.",
                    tpRate * 100, tpCount, totalTrades
            ));
        }
        
        // Analyze SL trades for pattern
        List<TradeRecord> slTrades = trades.stream()
                .filter(t -> "SL".equals(t.exitReason())).collect(Collectors.toList());
        
        if (!slTrades.isEmpty()) {
            double avgHoldTime = slTrades.stream()
                    .mapToDouble(t -> Duration.between(t.entryTime(), t.exitTime()).toMinutes())
                    .average().orElse(0);
            
            // If avg hold time < 2 hours, stops are too tight
            if (avgHoldTime < 120) {
                suggestions.add(String.format(
                        "⏱️ QUICK STOPS: Avg SL hold time %.0f minutes. " +
                        "Stops may be too tight. Increase SL ATR multiplier.",
                        avgHoldTime
                ));
            }
        }

        return suggestions;
    }

    /**
     * Analyzes time-based patterns.
     */
    private List<String> analyzeTimePatterns(List<TradeRecord> trades) {
        List<String> suggestions = new ArrayList<>();
        
        // Group trades by hour
        Map<Integer, List<TradeRecord>> byHour = trades.stream()
                .collect(Collectors.groupingBy(t -> t.entryTime().getHour()));
        
        // Find worst performing hours
        for (Map.Entry<Integer, List<TradeRecord>> entry : byHour.entrySet()) {
            int hour = entry.getKey();
            List<TradeRecord> hourTrades = entry.getValue();
            
            double winRate = (double) hourTrades.stream().filter(t -> t.netPnl() > 0).count() 
                    / hourTrades.size();
            double avgPnl = hourTrades.stream().mapToDouble(TradeRecord::netPnl).average().orElse(0);
            
            if (hourTrades.size() >= 3 && winRate < 0.30) {
                suggestions.add(String.format(
                        "🕐 POOR HOUR: Entry hour %d:00 has %.0f%% win rate (avg PnL $%.0f). " +
                        "Consider avoiding entries during this time.",
                        hour, winRate * 100, avgPnl
                ));
            }
        }

        return suggestions;
    }

    /**
     * Analyzes risk metrics.
     */
    private List<String> analyzeRiskMetrics(List<TradeRecord> trades) {
        List<String> suggestions = new ArrayList<>();
        
        // Calculate max consecutive losses
        int maxConsecutiveLosses = 0;
        int currentLosses = 0;
        for (TradeRecord trade : trades) {
            if (trade.netPnl() <= 0) {
                currentLosses++;
                maxConsecutiveLosses = Math.max(maxConsecutiveLosses, currentLosses);
            } else {
                currentLosses = 0;
            }
        }
        
        if (maxConsecutiveLosses >= 5) {
            suggestions.add(String.format(
                    "⚠️ CONSECUTIVE LOSSES: Max %d losing trades in a row. " +
                    "Ensure account can withstand drawdown. " +
                    "Consider reducing risk-per-trade from 2%% to 1%%.",
                    maxConsecutiveLosses
            ));
        }
        
        // Average max drawdown per trade
        double avgMaxDD = trades.stream().mapToDouble(TradeRecord::maxDrawdown).average().orElse(0);
        double avgNetPnl = trades.stream().mapToDouble(TradeRecord::netPnl).average().orElse(0);
        
        if (Math.abs(avgMaxDD) > Math.abs(avgNetPnl) * 2) {
            suggestions.add(String.format(
                    "📊 HIGH DRAWDOWN: Avg max DD $%.0f vs avg PnL $%.0f. " +
                    "Trades experience significant adverse moves. " +
                    "Review entry timing or use scale-in approach.",
                    avgMaxDD, avgNetPnl
            ));
        }

        return suggestions;
    }

    /**
     * Analyzes ticker concentration.
     */
    private List<String> analyzeTickerConcentration(List<TradeRecord> trades) {
        List<String> suggestions = new ArrayList<>();
        
        Map<String, List<TradeRecord>> byTicker = trades.stream()
                .collect(Collectors.groupingBy(TradeRecord::ticker));
        
        for (Map.Entry<String, List<TradeRecord>> entry : byTicker.entrySet()) {
            String ticker = entry.getKey();
            List<TradeRecord> tickerTrades = entry.getValue();
            
            double totalPnl = tickerTrades.stream().mapToDouble(TradeRecord::netPnl).sum();
            double winRate = (double) tickerTrades.stream().filter(t -> t.netPnl() > 0).count() 
                    / tickerTrades.size();
            
            if (tickerTrades.size() >= 5 && winRate < 0.35) {
                suggestions.add(String.format(
                        "📉 TICKER UNDERPERFORMER: %s has %.0f%% win rate over %d trades ($%.0f PnL). " +
                        "Consider removing from watchlist or reducing position size.",
                        ticker, winRate * 100, tickerTrades.size(), totalPnl
                ));
            }
        }

        return suggestions;
    }

    /**
     * Loads trades from CSV file.
     */
    private List<TradeRecord> loadTradesFromCsv(Path csvPath) {
        List<TradeRecord> trades = new ArrayList<>();
        
        try {
            List<String> lines = Files.readAllLines(csvPath);
            if (lines.size() <= 1) return trades; // Only header
            
            // Skip header line
            for (int i = 1; i < lines.size(); i++) {
                String line = lines.get(i).trim();
                if (line.isEmpty()) continue;
                
                try {
                    TradeRecord trade = parseTradeLine(line);
                    if (trade != null) {
                        trades.add(trade);
                    }
                } catch (Exception e) {
                    log.debug("Failed to parse line {}: {}", i, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("Failed to read CSV: {}", e.getMessage(), e);
        }
        
        return trades;
    }

    private TradeRecord parseTradeLine(String line) {
        String[] parts = line.split(",");
        if (parts.length < 15) return null;
        
        try {
            final ZonedDateTime entryTime = parseTimestamp(parts[5].trim());
            final ZonedDateTime exitTime = parseTimestamp(parts[7].trim());
            if (entryTime == null || exitTime == null) return null;
            return new TradeRecord(
                    parts[0].trim(),                                           // ticker
                    parts[1].trim(),                                           // strategy
                    parts[2].trim(),                                           // direction
                    Integer.parseInt(parts[3].trim()),                         // qty
                    Double.parseDouble(parts[4].trim()),                       // entryPrice
                    entryTime,                                                 // entryTime
                    Double.parseDouble(parts[6].trim()),                       // exitPrice
                    exitTime,                                                  // exitTime
                    parts[8].trim(),                                           // exitReason
                    Double.parseDouble(parts[9].trim()),                       // grossPnl
                    Double.parseDouble(parts[10].trim()),                      // commission
                    Double.parseDouble(parts[11].trim()),                      // slippage
                    Double.parseDouble(parts[12].trim()),                      // netPnl
                    Double.parseDouble(parts[13].trim()),                      // maxDD
                    Double.parseDouble(parts[14].trim())                       // maxRunup
            );
        } catch (Exception e) {
            log.warn("Failed to parse trade line '{}' — skipping record", line, e);
            return null;
        }
    }

    private ZonedDateTime parseTimestamp(String timestamp) {
        try {
            final LocalDateTime ldt = LocalDateTime.parse(
                    timestamp,
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            );
            return ldt.atZone(ZoneId.of("America/New_York"));
        } catch (Exception e) {
            log.warn("Failed to parse timestamp '{}' — skipping record", timestamp, e);
            return null;
        }
    }

    /**
     * Report containing analysis results.
     */
    public record AnalysisReport(
            String summary,
            List<String> suggestions
    ) {
        public int getSuggestionCount() {
            return suggestions.size();
        }
        
        public List<String> getCriticalSuggestions() {
            return suggestions.stream()
                    .filter(s -> s.contains("🚨") || s.contains("🛑") || s.contains("⚠️"))
                    .collect(Collectors.toList());
        }
        
        public List<String> getOptimizationSuggestions() {
            return suggestions.stream()
                    .filter(s -> s.contains("✅") || s.contains("📉") || s.contains("⚖️"))
                    .collect(Collectors.toList());
        }
        
        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("=== BACKTEST ANALYSIS REPORT ===\n");
            sb.append(summary).append("\n\n");
            sb.append(String.format("Total Suggestions: %d\n", suggestions.size()));
            sb.append(String.format("Critical Issues: %d\n", getCriticalSuggestions().size()));
            sb.append(String.format("Optimization Tips: %d\n\n", getOptimizationSuggestions().size()));

            for (int i = 0; i < suggestions.size(); i++) {
                sb.append(String.format("%d. %s\n", i + 1, suggestions.get(i)));
            }

            return sb.toString();
        }
    }

    // -------------------------------------------------------------------------
    // Dashboard-facing analysis methods (work with Map-based trade data)
    // -------------------------------------------------------------------------

    /**
     * Computes performance stats and generates recommendations for a list of
     * pre-filtered strategy trades (as {@code Map<String, Object>} rows from the
     * dashboard's in-memory trade cache).
     *
     * @param trades   strategy trades, already filtered by strategy name (and optionally ticker)
     * @return map with keys: wins, losses, winRate, totalPnl, avgWin, avgLoss,
     *         exitReasons, recommendations, suggestedParams
     */
    public Map<String, Object> analyzeStrategyTrades(List<Map<String, Object>> trades) {
        final long wins = trades.stream().filter(t -> (double) t.getOrDefault("netPnl", 0.0) > 0).count();
        final long losses = trades.size() - wins;
        final double totalPnl = trades.stream().mapToDouble(t -> (double) t.getOrDefault("netPnl", 0.0)).sum();
        final double winRate = (double) wins / trades.size();
        final double avgWin = wins > 0 ? trades.stream().filter(t -> (double) t.get("netPnl") > 0)
                .mapToDouble(t -> (double) t.get("netPnl")).average().orElse(0) : 0;
        final double avgLoss = losses > 0 ? trades.stream().filter(t -> (double) t.get("netPnl") <= 0)
                .mapToDouble(t -> Math.abs((double) t.get("netPnl"))).average().orElse(0) : 0;

        final Map<String, Long> exitReasons = new LinkedHashMap<>();
        for (Map<String, Object> trade : trades) {
            final String reason = (String) trade.getOrDefault("exitReason", "unknown");
            exitReasons.merge(reason, 1L, Long::sum);
        }

        final List<String> recommendations = new ArrayList<>();
        final List<String> suggestedParams = new ArrayList<>();

        if (winRate < 0.40 && trades.size() >= 3) {
            recommendations.add("Win rate is low (" + String.format("%.1f%%", winRate * 100) + ") — consider widening SL ATR multiplier by 0.3");
            suggestedParams.add("SL ATR: increase by 0.3 (e.g., 2.0 → 2.3)");
            recommendations.add("Review entry conditions: may be entering too early/late");
            recommendations.add("Consider disabling this strategy for current market conditions");
        }
        if (totalPnl < -500 && trades.size() >= 3) {
            recommendations.add("Strategy is losing money — reduce position size by 50%");
            suggestedParams.add("Position size: reduce by 50%");
            recommendations.add("Check if market regime has changed (trending vs ranging)");
        }
        if (avgLoss > avgWin * 1.5 && losses > 2) {
            recommendations.add("Average loss is " + String.format("%.0f%%", (avgLoss / avgWin - 1) * 100) + " larger than average win — tighten SL or reduce risk");
            suggestedParams.add("Risk per trade: reduce from 2% to 1%");
        }
        if (winRate >= 0.60 && totalPnl > 0 && trades.size() >= 3) {
            recommendations.add("Strategy is performing well — consider increasing position size");
            suggestedParams.add("Position size: increase by 25%");
            recommendations.add("This strategy is a winner — allocate more capital");
        }

        final long slHits = exitReasons.getOrDefault("SL", 0L);
        final long tpHits = exitReasons.getOrDefault("TP", 0L);
        if (slHits > tpHits && trades.size() >= 3) {
            recommendations.add("More SL hits (" + slHits + ") than TP hits (" + tpHits + ") — SL might be too tight");
            suggestedParams.add("SL ATR: widen by 0.2-0.5");
        }

        final Map<String, Object> result = new LinkedHashMap<>();
        result.put("wins", wins);
        result.put("losses", losses);
        result.put("winRate", winRate * 100);
        result.put("totalPnl", totalPnl);
        result.put("avgWin", avgWin);
        result.put("avgLoss", avgLoss);
        result.put("exitReasons", exitReasons);
        result.put("recommendations", recommendations);
        result.put("suggestedParams", suggestedParams);
        return result;
    }

    /**
     * Builds a before/after comparison map for strategy retest results.
     *
     * @param previousTrades trades from the last completed run
     * @param currentStats   strategy stats from the new backtest report
     * @return map with keys: previous, current, improvement
     */
    public Map<String, Object> buildStrategyComparison(
            List<Map<String, Object>> previousTrades,
            BacktestReport.StrategyStats currentStats) {

        final double previousPnl = previousTrades.stream()
                .mapToDouble(t -> (double) t.getOrDefault("netPnl", 0.0)).sum();
        final long previousWins = previousTrades.stream()
                .filter(t -> (double) t.getOrDefault("netPnl", 0.0) > 0).count();
        final double previousWinRate = previousTrades.isEmpty() ? 0 :
                (double) previousWins / previousTrades.size();

        final double currentPnl = currentStats != null ? currentStats.totalPnl() : 0;
        final double currentWinRate = currentStats != null ? currentStats.winRate() : 0;
        final int currentTradeCount = currentStats != null ? currentStats.trades() : 0;

        final Map<String, Object> previous = new LinkedHashMap<>();
        previous.put("trades", previousTrades.size());
        previous.put("winRate", previousWinRate * 100);
        previous.put("totalPnl", previousPnl);

        final Map<String, Object> current = new LinkedHashMap<>();
        current.put("trades", currentTradeCount);
        current.put("winRate", currentWinRate * 100);
        current.put("totalPnl", currentPnl);

        final Map<String, Object> improvement = new LinkedHashMap<>();
        improvement.put("pnlDiff", currentPnl - previousPnl);
        improvement.put("winRateDiff", (currentWinRate - previousWinRate) * 100);
        improvement.put("improved", currentPnl > previousPnl);

        final Map<String, Object> result = new LinkedHashMap<>();
        result.put("previous", previous);
        result.put("current", current);
        result.put("improvement", improvement);
        return result;
    }
}
