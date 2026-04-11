package com.fgiaquinta.optionsquant.service;

import com.fgiaquinta.optionsquant.backtest.domain.BacktestReport;
import com.fgiaquinta.optionsquant.backtest.domain.TradeRecord;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
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
            log.error("Failed to read CSV: {}", e.getMessage());
        }
        
        return trades;
    }

    private TradeRecord parseTradeLine(String line) {
        String[] parts = line.split(",");
        if (parts.length < 15) return null;
        
        try {
            return new TradeRecord(
                    parts[0].trim(),                                           // ticker
                    parts[1].trim(),                                           // strategy
                    parts[2].trim(),                                           // direction
                    Integer.parseInt(parts[3].trim()),                         // qty
                    Double.parseDouble(parts[4].trim()),                       // entryPrice
                    parseTimestamp(parts[5].trim()),                           // entryTime
                    Double.parseDouble(parts[6].trim()),                       // exitPrice
                    parseTimestamp(parts[7].trim()),                           // exitTime
                    parts[8].trim(),                                           // exitReason
                    Double.parseDouble(parts[9].trim()),                       // grossPnl
                    Double.parseDouble(parts[10].trim()),                      // commission
                    Double.parseDouble(parts[11].trim()),                      // slippage
                    Double.parseDouble(parts[12].trim()),                      // netPnl
                    Double.parseDouble(parts[13].trim()),                      // maxDD
                    Double.parseDouble(parts[14].trim())                       // maxRunup
            );
        } catch (Exception e) {
            return null;
        }
    }

    private java.time.ZonedDateTime parseTimestamp(String timestamp) {
        try {
            java.time.LocalDateTime ldt = java.time.LocalDateTime.parse(
                    timestamp, 
                    java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            );
            return ldt.atZone(java.time.ZoneId.systemDefault());
        } catch (Exception e) {
            return java.time.ZonedDateTime.now();
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
}
