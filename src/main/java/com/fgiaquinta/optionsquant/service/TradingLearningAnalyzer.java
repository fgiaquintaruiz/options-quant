package com.fgiaquinta.optionsquant.service;

import com.fgiaquinta.optionsquant.backtest.domain.BacktestReport;
import com.fgiaquinta.optionsquant.backtest.domain.TradeRecord;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Analyzes backtest results to generate actionable learning insights.
 *
 * This is the BRAIN of the learning system. After every backtest, it:
 * 1. Identifies which patterns work/don't work per ticker
 * 2. Finds optimal time windows for each ticker
 * 3. Detects market condition preferences (VIX, trend)
 * 4. Recommends strategy parameter adjustments
 * 5. Generates a comprehensive learning report
 */
@Slf4j
@Service
public class TradingLearningAnalyzer {

    private final TickerMemory tickerMemory;

    public TradingLearningAnalyzer(TickerMemory tickerMemory) {
        this.tickerMemory = tickerMemory;
    }

    /**
     * Analyzes a completed backtest report and generates learning insights.
     */
    public LearningAnalysis analyze(BacktestReport report) {
        log.info("🧠 [Learning Analyzer] Analyzing backtest: {} trades, {}% WR, ${} PnL",
                report.totalTrades(), String.format("%.1f", report.winRate() * 100),
                String.format("%.2f", report.finalCapital() - report.initialCapital()));

        LearningAnalysis analysis = new LearningAnalysis();
        analysis.totalTrades = report.totalTrades();
        analysis.winRate = report.winRate();
        analysis.totalPnl = report.finalCapital() - report.initialCapital();
        analysis.maxDrawdown = report.maxDrawdown();

        // Run all analysis dimensions
        analysis.patternAnalysis = analyzePatternEffectiveness(report.trades());
        analysis.timeAnalysis = analyzeTimePatterns(report.trades());
        analysis.tickerStrategyAnalysis = analyzeTickerStrategyCombos(report.trades());
        analysis.lossCategorization = categorizeAllLosses(report.trades());
        analysis.recommendations = generateRecommendations(analysis);
        analysis.autoAdjustments = applyAutoAdjustments(analysis);

        // Log the learning report
        logLearningReport(analysis);

        return analysis;
    }

    /**
     * Analyzes which candlestick patterns work best.
     */
    private Map<String, PatternEffectiveness> analyzePatternEffectiveness(List<TradeRecord> trades) {
        Map<String, List<TradeRecord>> byPattern = trades.stream()
                .collect(Collectors.groupingBy(TradeRecord::candlestickPattern));

        Map<String, PatternEffectiveness> result = new HashMap<>();
        
        byPattern.forEach((pattern, patternTrades) -> {
            long wins = patternTrades.stream().filter(TradeRecord::isWin).count();
            double winRate = (double) wins / patternTrades.size();
            double avgPnl = patternTrades.stream().mapToDouble(TradeRecord::netPnl).average().orElse(0);
            double totalPnl = patternTrades.stream().mapToDouble(TradeRecord::netPnl).sum();

            PatternEffectiveness pe = new PatternEffectiveness();
            pe.pattern = pattern;
            pe.totalTrades = patternTrades.size();
            pe.wins = (int) wins;
            pe.winRate = winRate;
            pe.avgPnl = avgPnl;
            pe.totalPnl = totalPnl;
            pe.tickers = patternTrades.stream().map(TradeRecord::ticker).collect(Collectors.toSet());

            // Categorize effectiveness
            if (pe.totalTrades >= 5) {
                if (winRate >= 0.60 && totalPnl > 0) {
                    pe.effectiveness = "HIGHLY_EFFECTIVE";
                } else if (winRate >= 0.45 && totalPnl > 0) {
                    pe.effectiveness = "MODERATELY_EFFECTIVE";
                } else if (winRate < 0.35 && totalPnl < 0) {
                    pe.effectiveness = "INEFFECTIVE";
                } else {
                    pe.effectiveness = "NEUTRAL";
                }
            } else {
                pe.effectiveness = "INSUFFICIENT_DATA";
            }

            result.put(pattern, pe);
        });

        return result;
    }

    /**
     * Analyzes time-based patterns (what times work best).
     */
    private TimeAnalysisResult analyzeTimePatterns(List<TradeRecord> trades) {
        Map<Integer, List<TradeRecord>> byHour = trades.stream()
                .filter(t -> t.entryHour() >= 0)
                .collect(Collectors.groupingBy(TradeRecord::entryHour));

        TimeAnalysisResult result = new TimeAnalysisResult();
        
        byHour.forEach((hour, hourTrades) -> {
            long wins = hourTrades.stream().filter(TradeRecord::isWin).count();
            double winRate = (double) wins / hourTrades.size();
            double totalPnl = hourTrades.stream().mapToDouble(TradeRecord::netPnl).sum();

            TimeSlot slot = new TimeSlot();
            slot.hour = hour;
            slot.timeRange = String.format("%02d:00-%02d:59", hour, hour);
            slot.totalTrades = hourTrades.size();
            slot.wins = (int) wins;
            slot.winRate = winRate;
            slot.totalPnl = totalPnl;

            result.timeSlots.add(slot);
        });

        result.timeSlots.sort((a, b) -> Double.compare(b.totalPnl, a.totalPnl));

        // Identify best/worst hours
        if (!result.timeSlots.isEmpty()) {
            result.bestHour = result.timeSlots.get(0);
            result.worstHour = result.timeSlots.get(result.timeSlots.size() - 1);
        }

        return result;
    }

    /**
     * Analyzes ticker+strategy combinations to find winners/losers.
     */
    private Map<TickerStrategyKey, TickerStrategyAnalysis> analyzeTickerStrategyCombos(List<TradeRecord> trades) {
        Map<TickerStrategyKey, List<TradeRecord>> byTickerStrategy = trades.stream()
                .collect(Collectors.groupingBy(t -> new TickerStrategyKey(t.ticker(), t.strategy())));

        Map<TickerStrategyKey, TickerStrategyAnalysis> result = new HashMap<>();

        byTickerStrategy.forEach((key, comboTrades) -> {
            String ticker = key.ticker();
            String strategy = key.strategy();

            long wins = comboTrades.stream().filter(TradeRecord::isWin).count();
            double winRate = (double) wins / comboTrades.size();
            double totalPnl = comboTrades.stream().mapToDouble(TradeRecord::netPnl).sum();
            double avgPnl = comboTrades.stream().mapToDouble(TradeRecord::netPnl).average().orElse(0);
            double maxDD = comboTrades.stream().mapToDouble(TradeRecord::maxDrawdown).max().orElse(0);

            // Analyze patterns used
            Map<String, Long> patternCounts = comboTrades.stream()
                    .collect(Collectors.groupingBy(TradeRecord::candlestickPattern, Collectors.counting()));
            Map<String, Double> patternPnl = comboTrades.stream()
                    .collect(Collectors.groupingBy(TradeRecord::candlestickPattern,
                            Collectors.averagingDouble(TradeRecord::netPnl)));

            TickerStrategyAnalysis tsa = new TickerStrategyAnalysis();
            tsa.ticker = ticker;
            tsa.strategy = strategy;
            tsa.totalTrades = comboTrades.size();
            tsa.wins = (int) wins;
            tsa.winRate = winRate;
            tsa.totalPnl = totalPnl;
            tsa.avgPnl = avgPnl;
            tsa.maxDrawdown = maxDD;
            tsa.patternCounts = patternCounts;
            tsa.patternPnl = patternPnl;

            // Performance categorization
            if (tsa.totalTrades >= 5) {
                if (winRate >= 0.60 && totalPnl > 0) {
                    tsa.performance = "WINNER";
                } else if (winRate < 0.35 && totalPnl < 0) {
                    tsa.performance = "LOSER";
                } else {
                    tsa.performance = "NEEDS_OPTIMIZATION";
                }
            } else {
                tsa.performance = "INSUFFICIENT_DATA";
            }

            result.put(key, tsa);
        });

        return result;
    }

    /**
     * Categorizes all losses to understand root causes.
     */
    private LossCategorizationResult categorizeAllLosses(List<TradeRecord> trades) {
        Map<String, Integer> categories = new HashMap<>();
        Map<String, List<TradeRecord>> losses = trades.stream()
                .filter(t -> !t.isWin())
                .collect(Collectors.groupingBy(t -> {
                    String lossCat = t.categorizeLoss();
                    return lossCat != null ? lossCat : "UNKNOWN";
                }));

        losses.forEach((category, categoryTrades) -> {
            categories.put(category, categoryTrades.size());
        });

        LossCategorizationResult result = new LossCategorizationResult();
        result.categories = categories;
        result.totalLosses = losses.values().stream().mapToInt(List::size).sum();
        result.lossTrades = losses;

        return result;
    }

    /**
     * Generates actionable recommendations based on analysis.
     */
    private List<String> generateRecommendations(LearningAnalysis analysis) {
        List<String> recommendations = new ArrayList<>();

        // Pattern recommendations
        analysis.patternAnalysis.forEach((pattern, pe) -> {
            if ("INEFFECTIVE".equals(pe.effectiveness)) {
                recommendations.add(String.format(
                        "🚫 Pattern '%s' is ineffective (%.0f%% WR, %.0f trades): Consider disabling or adjusting parameters",
                        pattern, pe.winRate * 100, (double) pe.totalTrades));
            } else if ("HIGHLY_EFFECTIVE".equals(pe.effectiveness)) {
                recommendations.add(String.format(
                        "✅ Pattern '%s' is highly effective (%.0f%% WR, $%.2f PnL): Prioritize this setup",
                        pattern, pe.winRate * 100, pe.totalPnl));
            }
        });

        // Time recommendations
        if (analysis.timeAnalysis.bestHour != null) {
            TimeSlot best = analysis.timeAnalysis.bestHour;
            if (best.totalTrades >= 3) {
                recommendations.add(String.format(
                        "⏰ Best trading time: %s (%.0f%% WR, $%.2f PnL): Focus entries in this window",
                        best.timeRange, best.winRate * 100, best.totalPnl));
            }
        }
        if (analysis.timeAnalysis.worstHour != null) {
            TimeSlot worst = analysis.timeAnalysis.worstHour;
            if (worst.totalTrades >= 3 && worst.totalPnl < 0) {
                recommendations.add(String.format(
                        "⚠️ Worst trading time: %s (%.0f%% WR, $%.2f PnL): Avoid entries during this period",
                        worst.timeRange, worst.winRate * 100, worst.totalPnl));
            }
        }

        // Ticker-strategy recommendations
        analysis.tickerStrategyAnalysis.forEach((key, tsa) -> {
            if ("LOSER".equals(tsa.performance)) {
                // Find worst pattern
                String worstPattern = tsa.patternPnl.entrySet().stream()
                        .min(Map.Entry.comparingByValue())
                        .map(Map.Entry::getKey)
                        .orElse("unknown");

                recommendations.add(String.format(
                        "❌ %s + %s is losing (%.0f%% WR, $%.2f): Worst pattern is '%s' ($%.2f avg) — disable it",
                        tsa.ticker, tsa.strategy, tsa.winRate * 100, tsa.totalPnl,
                        worstPattern, tsa.patternPnl.get(worstPattern)));
            } else if ("WINNER".equals(tsa.performance)) {
                String bestPattern = tsa.patternPnl.entrySet().stream()
                        .max(Map.Entry.comparingByValue())
                        .map(Map.Entry::getKey)
                        .orElse("unknown");

                recommendations.add(String.format(
                        "🏆 %s + %s is winning (%.0f%% WR, $%.2f): Best pattern is '%s' — increase position size",
                        tsa.ticker, tsa.strategy, tsa.winRate * 100, tsa.totalPnl,
                        bestPattern));
            }
        });

        // Loss categorization insights
        analysis.lossCategorization.categories.forEach((category, count) -> {
            double pct = (double) count / analysis.lossCategorization.totalLosses * 100;
            if (pct > 30) {
                recommendations.add(String.format(
                        "📝 %.0f%% of losses are '%s' (%d trades): This is your primary issue — focus on fixing it",
                        pct, category, count));
            }
        });

        return recommendations;
    }

    /**
     * Automatically applies adjustments to TickerMemory based on analysis.
     */
    private List<String> applyAutoAdjustments(LearningAnalysis analysis) {
        List<String> adjustments = new ArrayList<>();

        // Update ticker strategy profiles with learnings
        analysis.tickerStrategyAnalysis.forEach((key, tsa) -> {
            TickerStrategyProfile profile = tickerMemory.getStrategyProfile(tsa.ticker, tsa.strategy);
            if (profile == null) return;

            java.util.concurrent.atomic.AtomicBoolean adjusted = new java.util.concurrent.atomic.AtomicBoolean(false);

            // Disable ineffective patterns
            tsa.patternPnl.forEach((pattern, avgPnl) -> {
                Long count = tsa.patternCounts.get(pattern);
                if (count != null && count >= 3 && avgPnl < 0) {
                    profile.disabledPatterns.add(pattern);
                    profile.enabledPatterns.remove(pattern);
                    adjustments.add(String.format("🚫 Disabled pattern '%s' for %s+%s (avg $%.2f over %d trades)",
                            pattern, tsa.ticker, tsa.strategy, avgPnl, count));
                    adjusted.set(true);
                } else if (count != null && count >= 3 && avgPnl > 50) {
                    profile.enabledPatterns.add(pattern);
                    profile.disabledPatterns.remove(pattern);
                    adjustments.add(String.format("✅ Enabled pattern '%s' for %s+%s (avg $%.2f over %d trades)",
                            pattern, tsa.ticker, tsa.strategy, avgPnl, count));
                    adjusted.set(true);
                }
            });

            // Adjust time filters if clear patterns emerge
            if (analysis.timeAnalysis.bestHour != null && analysis.timeAnalysis.bestHour.totalTrades >= 5) {
                TimeSlot best = analysis.timeAnalysis.bestHour;
                if (best.winRate > 0.60) {
                    profile.earliestEntryTime = LocalTime.of(best.hour, 0);
                    profile.latestEntryTime = LocalTime.of(best.hour + 1, 59);
                    adjustments.add(String.format("⏰ Set time filter for %s+%s to %s-%s",
                            tsa.ticker, tsa.strategy, profile.earliestEntryTime, profile.latestEntryTime));
                    adjusted.set(true);
                }
            }

            // Adjust ATR multipliers based on loss category
            if ("VOLATILITY".equals(profile.getTopLossCategory()) && profile.totalTrades >= 5) {
                profile.atrStopMultiplier *= 1.2;  // Widen stops by 20%
                adjustments.add(String.format("📏 Widened ATR stop multiplier to %.2f for %s+%s (too many volatility losses)",
                        profile.atrStopMultiplier, tsa.ticker, tsa.strategy));
                adjusted.set(true);
            }

            if (adjusted.get()) {
                log.info("🔧 [Auto-Adjust] Applied {} adjustments for {}", 
                        adjustments.stream().filter(a -> a.contains(tsa.ticker)).count(),
                        key);
            }
        });

        return adjustments;
    }

    /**
     * Logs a comprehensive learning report.
     */
    private void logLearningReport(LearningAnalysis analysis) {
        log.info("\n" +
                "============================================================\n" +
                "🧠 TRADING LEARNING REPORT\n" +
                "============================================================\n" +
                "📊 SUMMARY\n" +
                "  Total Trades: {}\n" +
                "  Win Rate: {}%\n" +
                "  Total PnL: ${}\n" +
                "  Max Drawdown: ${}\n" +
                "\n" +
                "🎯 PATTERN EFFECTIVENESS\n" +
                "{}\n" +
                "\n" +
                "⏰ TIME ANALYSIS\n" +
                "  Best Hour: {} ({} WR, ${} PnL)\n" +
                "  Worst Hour: {} ({} WR, ${} PnL)\n" +
                "\n" +
                "📝 LOSS CATEGORIZATION\n" +
                "{}\n" +
                "\n" +
                "💡 RECOMMENDATIONS ({} total)\n" +
                "{}\n" +
                "\n" +
                "🔧 AUTO-ADJUSTMENTS ({} applied)\n" +
                "{}\n" +
                "============================================================",
                analysis.totalTrades,
                String.format("%.1f", analysis.winRate * 100),
                String.format("%.2f", analysis.totalPnl),
                String.format("%.2f", analysis.maxDrawdown),
                formatPatternAnalysis(analysis.patternAnalysis),
                analysis.timeAnalysis.bestHour != null ? analysis.timeAnalysis.bestHour.timeRange : "N/A",
                analysis.timeAnalysis.bestHour != null ? String.format("%.0f%%", analysis.timeAnalysis.bestHour.winRate * 100) : "N/A",
                analysis.timeAnalysis.bestHour != null ? String.format("%.2f", analysis.timeAnalysis.bestHour.totalPnl) : "0",
                analysis.timeAnalysis.worstHour != null ? analysis.timeAnalysis.worstHour.timeRange : "N/A",
                analysis.timeAnalysis.worstHour != null ? String.format("%.0f%%", analysis.timeAnalysis.worstHour.winRate * 100) : "N/A",
                analysis.timeAnalysis.worstHour != null ? String.format("%.2f", analysis.timeAnalysis.worstHour.totalPnl) : "0",
                formatLossCategorization(analysis.lossCategorization),
                analysis.recommendations.size(),
                String.join("\n", analysis.recommendations),
                analysis.autoAdjustments.size(),
                String.join("\n", analysis.autoAdjustments)
        );
    }

    private String formatPatternAnalysis(Map<String, PatternEffectiveness> patternAnalysis) {
        StringBuilder sb = new StringBuilder();
        patternAnalysis.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue().totalPnl, a.getValue().totalPnl))
                .forEach(entry -> {
                    PatternEffectiveness pe = entry.getValue();
                    String emoji = switch (pe.effectiveness) {
                        case "HIGHLY_EFFECTIVE" -> "🔥";
                        case "MODERATELY_EFFECTIVE" -> "✅";
                        case "INEFFECTIVE" -> "❌";
                        default -> "⏳";
                    };
                    sb.append(String.format("  %s %-25s: %.0f%% WR, $%8.2f PnL, %2d trades [%s]%n",
                            emoji, pe.pattern, pe.winRate * 100, pe.totalPnl, pe.totalTrades, pe.effectiveness));
                });
        return sb.toString();
    }

    private String formatLossCategorization(LossCategorizationResult result) {
        StringBuilder sb = new StringBuilder();
        result.categories.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .forEach(entry -> {
                    double pct = (double) entry.getValue() / result.totalLosses * 100;
                    sb.append(String.format("  %-20s: %2d losses (%.0f%%)%n",
                            entry.getKey(), entry.getValue(), pct));
                });
        return sb.toString();
    }

    // ===== Key Records =====

    /**
     * Composite key for ticker+strategy combinations (replaces string concatenation).
     */
    public record TickerStrategyKey(String ticker, String strategy) {}

    // ===== Analysis Result Records =====

    public static class LearningAnalysis {
        public int totalTrades;
        public double winRate;
        public double totalPnl;
        public double maxDrawdown;
        public Map<String, PatternEffectiveness> patternAnalysis;
        public TimeAnalysisResult timeAnalysis;
        public Map<TickerStrategyKey, TickerStrategyAnalysis> tickerStrategyAnalysis;
        public LossCategorizationResult lossCategorization;
        public List<String> recommendations;
        public List<String> autoAdjustments;
    }

    public static class PatternEffectiveness {
        public String pattern;
        public int totalTrades;
        public int wins;
        public double winRate;
        public double avgPnl;
        public double totalPnl;
        public Set<String> tickers;
        public String effectiveness;
    }

    public static class TimeAnalysisResult {
        public List<TimeSlot> timeSlots = new ArrayList<>();
        public TimeSlot bestHour;
        public TimeSlot worstHour;
    }

    public static class TimeSlot {
        public int hour;
        public String timeRange;
        public int totalTrades;
        public int wins;
        public double winRate;
        public double totalPnl;
    }

    public static class TickerStrategyAnalysis {
        public String ticker;
        public String strategy;
        public int totalTrades;
        public int wins;
        public double winRate;
        public double totalPnl;
        public double avgPnl;
        public double maxDrawdown;
        public Map<String, Long> patternCounts;
        public Map<String, Double> patternPnl;
        public String performance;
    }

    public static class LossCategorizationResult {
        public Map<String, Integer> categories;
        public int totalLosses;
        public Map<String, List<TradeRecord>> lossTrades;
    }
}
