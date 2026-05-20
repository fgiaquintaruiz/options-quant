package com.fgiaquinta.optionsquant.cli;

import com.fgiaquinta.optionsquant.backtest.domain.BacktestConfig;
import com.fgiaquinta.optionsquant.backtest.domain.BacktestReport;
import com.fgiaquinta.optionsquant.backtest.engine.BacktestEngine;
import com.fgiaquinta.optionsquant.service.BacktestAnalyzer;
import com.fgiaquinta.optionsquant.service.ContinuousLearningLoop;
import com.fgiaquinta.optionsquant.service.Emoji;
import com.fgiaquinta.optionsquant.service.TickerMemory;
import com.fgiaquinta.optionsquant.service.TickerService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;

/**
 * Simplified CLI for Options Quant backtest analysis.
 * Focus: Learning loop, signal review, and learning memory management.
 *
 * Activate with: java -jar app.jar --backtest-cli.enabled=true
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "backtest-cli.enabled", havingValue = "true")
public class BacktestCli implements CommandLineRunner {

    private final BacktestEngine backtestEngine;
    private final BacktestAnalyzer backtestAnalyzer;
    private final TickerService tickerService;
    private final ContinuousLearningLoop learningLoop;
    private final TickerMemory tickerMemory;

    public BacktestCli(BacktestEngine backtestEngine,
                       BacktestAnalyzer backtestAnalyzer,
                       TickerService tickerService,
                       ContinuousLearningLoop learningLoop,
                       TickerMemory tickerMemory) {
        this.backtestEngine = backtestEngine;
        this.backtestAnalyzer = backtestAnalyzer;
        this.tickerService = tickerService;
        this.learningLoop = learningLoop;
        this.tickerMemory = tickerMemory;
    }

    @Override
    public void run(String... args) {
        System.setOut(new PrintStream(System.out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(System.err, true, StandardCharsets.UTF_8));

        log.info("Options Quant Backtest CLI started");
        Scanner scanner = new Scanner(System.in);

        printMainMenu();

        while (true) {
            System.out.print("\nEnter command (1-6): ");
            String input = scanner.nextLine().trim();

            try {
                switch (input) {
                    case "1" -> runQuickLearningLoop();
                    case "2" -> runContinuousLearningLoop(scanner);
                    case "3" -> runSignalReview();
                    case "4" -> viewLearningReport();
                    case "5" -> resetLearningMemory();
                    case "6" -> {
                        System.out.println(Emoji.WAVE() + " Exiting Backtest CLI...");
                        return;
                    }
                    default -> System.out.println(Emoji.CROSS() + " Invalid command. Enter 1-6.");
                }
            } catch (Exception e) {
                System.out.println(Emoji.CROSS() + " Error: " + e.getMessage());
                log.error("CLI error", e);
            }
        }
    }

    private void printMainMenu() {
        System.out.println("""

                ╔═══════════════════════════════════════════════╗
                ║     %s Options Quant Backtest Analyzer CLI v2.0.0     ║
                ║     "Learn, Adapt, Conquer"                   ║
                ╚═══════════════════════════════════════════════╝

                Available commands:
                  1. %s QUICK LEARNING LOOP (Defaults: All tickers, focus on HOT)
                  2. %s CUSTOM LEARNING LOOP (Configure tickers, dates, risk)
                  3. %s AUTOMATED SIGNAL REVIEW (Analyze all trades from CSV)
                  4. %s VIEW LEARNING REPORT (Per-ticker/strategy memory)
                  5. %s RESET LEARNING MEMORY (Start fresh)
                  6. %s EXIT

                """.formatted(
                Emoji.CHART(),
                Emoji.BRAIN(),
                Emoji.BRAIN(),
                Emoji.SEARCH(),
                Emoji.CHART(),
                Emoji.WRENCH(),
                Emoji.WAVE()
        ));
    }

    /**
     * Command 1: Quick Learning Loop with defaults
     * Uses all tickers, with hot tickers first in the list.
     */
    private void runQuickLearningLoop() {
        List<String> hotTickers = tickerService.getHotTickers();
        List<String> allTickers = tickerService.getTickerSymbols();
        List<String> tickers = new ArrayList<>(hotTickers);
        allTickers.stream()
                .filter(t -> !hotTickers.contains(t))
                .forEach(tickers::add);

        LocalDate defaultTo = LocalDate.now();
        LocalDate defaultFrom = defaultTo.minusYears(1);
        double initialCapital = 50000;
        double riskPct = 0.02;
        int maxIterations = 20;
        double convergenceThreshold = 0.02;

        System.out.println("""

                ╔═══════════════════════════════════════════════╗
                ║     %s QUICK LEARNING LOOP (DEFAULTS)        ║
                ║     "Practice Makes Progress"                 ║
                ╚═══════════════════════════════════════════════╝

                Using default configuration:
                  Tickers: %d total (%d hot first)
                  Date Range: %s to %s
                  Capital: $%.0f
                  Risk: %.0f%%
                  Max Iterations: %d
                  Convergence: %.0f%%

                Starting training...
                """.formatted(Emoji.BRAIN(), tickers.size(), hotTickers.size(),
                        defaultFrom, defaultTo, initialCapital,
                        riskPct * 100, maxIterations, convergenceThreshold * 100));

        ContinuousLearningLoop.LearningLoopResult result = learningLoop.startLoop(tickers, defaultFrom, defaultTo,
                initialCapital, riskPct, maxIterations, convergenceThreshold);

        System.out.println("\n" + "=".repeat(80));
        System.out.println(Emoji.BRAIN() + " TRAINING COMPLETE");
        System.out.println("=".repeat(80));

        if (result.getError() != null) {
            System.out.println(Emoji.CROSS() + " Error: " + result.getError());
            printMainMenu();
            return;
        }

        System.out.println(Emoji.CHART() + " RESULTS:");
        System.out.println("   Iterations: " + result.getCompletedIterations());
        System.out.println("   Reason: " + result.getConvergenceReason());
        System.out.println("   Elapsed: " + (result.getElapsedMs() / 1000) + " seconds");

        if (!result.getIterations().isEmpty()) {
            ContinuousLearningLoop.IterationResult first = result.getIterations().get(0);
            ContinuousLearningLoop.IterationResult last = result.getIterations().get(result.getIterations().size() - 1);

            System.out.println("\n" + Emoji.TREND_UP() + " IMPROVEMENT:");
            System.out.println("   First: " + first.getTotalTrades() + " trades, " +
                    String.format("%.1f%%", first.getWinRate() * 100) + " WR, $" +
                    String.format("%.2f", first.getTotalPnl()) + " PnL");
            System.out.println("   Last:  " + last.getTotalTrades() + " trades, " +
                    String.format("%.1f%%", last.getWinRate() * 100) + " WR, $" +
                    String.format("%.2f", last.getTotalPnl()) + " PnL");
            System.out.println("   Delta Win Rate: " + String.format("%+.1f%%",
                    (last.getWinRate() - first.getWinRate()) * 100));
            System.out.println("   Delta PnL: $" + String.format("%+.2f",
                    last.getTotalPnl() - first.getTotalPnl()));
        }

        System.out.println("\n" + "=".repeat(80));
        System.out.println(Emoji.TARGET() + " NEXT STEPS:");
        System.out.println("=".repeat(80));
        System.out.println("  3. Run signal review to analyze trades");
        System.out.println("  4. View learning report");
        System.out.println("  2. Custom learning loop with different parameters");
        System.out.println("=".repeat(80));
        printMainMenu();
    }

    /**
     * Command 2: Continuous Learning Loop (ADVANCED CONFIG)
     */
    private void runContinuousLearningLoop(Scanner scanner) {
        System.out.println("""

                ╔═══════════════════════════════════════════════╗
                ║     %s CUSTOM LEARNING LOOP (ADVANCED)       ║
                ║     "Practice Makes Progress"                 ║
                ╚═══════════════════════════════════════════════╝

                The system will run multiple backtests automatically,
                learning from each one until it reaches optimal performance.

                """.formatted(Emoji.BRAIN()));

        // Get configuration
        System.out.println(Emoji.WRITING() + " Learning Loop Configuration:\n");

        LocalDate defaultTo = LocalDate.now();
        LocalDate defaultFrom = defaultTo.minusYears(1);

        System.out.printf("  From date (YYYY-MM-DD) [%s]: ", defaultFrom);
        String from = scanner.nextLine().trim();
        if (from.isEmpty()) from = defaultFrom.toString();

        System.out.printf("  To date (YYYY-MM-DD) [%s]: ", defaultTo);
        String to = scanner.nextLine().trim();
        if (to.isEmpty()) to = defaultTo.toString();

        System.out.print("  Tickers (comma-separated, e.g., AMZN,NVDA,GOOGL) [AMZN,NVDA,GOOGL]: ");
        String tickersInput = scanner.nextLine().trim();
        if (tickersInput.isEmpty()) tickersInput = "AMZN,NVDA,GOOGL";
        List<String> tickers = List.of(tickersInput.split(","));

        System.out.print("  Initial capital [50000]: ");
        String capital = scanner.nextLine().trim();
        double initialCapital = capital.isEmpty() ? 50000 : Double.parseDouble(capital);

        System.out.print("  Risk % per trade [0.02]: ");
        String risk = scanner.nextLine().trim();
        double riskPct = risk.isEmpty() ? 0.02 : Double.parseDouble(risk);

        System.out.print("  Max iterations [20]: ");
        String maxIter = scanner.nextLine().trim();
        int maxIterations = maxIter.isEmpty() ? 20 : Integer.parseInt(maxIter);

        System.out.print("  Convergence threshold [0.02] (2%%): ");
        String conv = scanner.nextLine().trim();
        double convergenceThreshold = conv.isEmpty() ? 0.02 : Double.parseDouble(conv);

        System.out.println("\n" + "=".repeat(80));
        System.out.println("Starting Continuous Learning Loop...");
        System.out.println("=".repeat(80));
        System.out.println("  Tickers: " + tickers);
        System.out.println("  Date Range: " + from + " to " + to);
        System.out.println("  Capital: $" + initialCapital);
        System.out.println("  Risk: " + (riskPct * 100) + "%");
        System.out.println("  Max Iterations: " + maxIterations);
        System.out.println("  Convergence: " + (convergenceThreshold * 100) + "%");
        System.out.println("=".repeat(80));
        System.out.println("\nThis will take several minutes. Watch the logs for progress!\n");

        // Run the learning loop
        ContinuousLearningLoop.LearningLoopResult result = learningLoop.startLoop(
                tickers,
                LocalDate.parse(from),
                LocalDate.parse(to),
                initialCapital,
                riskPct,
                maxIterations,
                convergenceThreshold
        );

        // Display results
        System.out.println("\n" + "=".repeat(80));
        System.out.println("LEARNING LOOP COMPLETE!");
        System.out.println("=".repeat(80));

        if (result.getError() != null) {
            System.out.println(Emoji.CROSS() + " Error: " + result.getError());
            return;
        }

        System.out.println(Emoji.CHART() + " RESULTS:");
        System.out.println("   Iterations: " + result.getCompletedIterations());
        System.out.println("   Reason: " + result.getConvergenceReason());
        System.out.println("   Elapsed: " + (result.getElapsedMs() / 1000) + " seconds");

        if (!result.getIterations().isEmpty()) {
            ContinuousLearningLoop.IterationResult first = result.getIterations().get(0);
            ContinuousLearningLoop.IterationResult last = result.getIterations().get(result.getIterations().size() - 1);

            System.out.println("\n" + Emoji.TREND_UP() + " IMPROVEMENT:");
            System.out.println("   First: " + first.getTotalTrades() + " trades, " +
                    String.format("%.1f%%", first.getWinRate() * 100) + " WR, $" +
                    String.format("%.2f", first.getTotalPnl()) + " PnL");
            System.out.println("   Last:  " + last.getTotalTrades() + " trades, " +
                    String.format("%.1f%%", last.getWinRate() * 100) + " WR, $" +
                    String.format("%.2f", last.getTotalPnl()) + " PnL");
            System.out.println("   Delta Win Rate: " + String.format("%+.1f%%",
                    (last.getWinRate() - first.getWinRate()) * 100));
            System.out.println("   Delta PnL: $" + String.format("%+.2f",
                    last.getTotalPnl() - first.getTotalPnl()));
        }

        System.out.println("\n" + "=".repeat(80));
        System.out.println(Emoji.TARGET() + " NEXT STEPS:");
        System.out.println("=".repeat(80));
        System.out.println("  3. Run signal review to analyze trades");
        System.out.println("  4. View learning report");
        System.out.println("  5. Reset and try with different parameters");
        System.out.println("=".repeat(80));
        printMainMenu();
    }

    /**
     * Command 4: View Learning Report
     */
    private void viewLearningReport() {
        System.out.println("""

                ╔═══════════════════════════════════════════════╗
                ║     %s TICKER MEMORY LEARNING REPORT         ║
                ╚═══════════════════════════════════════════════╝
                """.formatted(Emoji.BRAIN()));

        String report = tickerMemory.getLearningReport();
        System.out.println(report);
        printMainMenu();
    }

    /**
     * Command 5: Reset Learning Memory
     */
    private void resetLearningMemory() {
        System.out.println("""

                ╔═══════════════════════════════════════════════╗
                ║     %s RESET LEARNING MEMORY                 ║
                ╚═══════════════════════════════════════════════╝

                This will delete all learned patterns and statistics.
                Are you sure? (yes/no):
                """.formatted(Emoji.WRENCH()));

        Scanner scanner = new Scanner(System.in);
        String confirm = scanner.nextLine().trim().toLowerCase();

        if (confirm.equals("yes") || confirm.equals("y")) {
            tickerMemory.resetAll();
            System.out.println(Emoji.CHECK() + " Learning memory has been reset!");
        } else {
            System.out.println(Emoji.CROSS() + " Reset cancelled.");
        }
        printMainMenu();
    }

    /**
     * Command 3: Automated Signal Review
     */
    private void runSignalReview() {
        System.out.println("\n" + Emoji.SEARCH() + " Automated Signal Review:");

        Path tradesCsv = Path.of("backtest/trades.csv");
        if (!Files.exists(tradesCsv)) {
            System.out.println("  No trades.csv found. Run a backtest first.");
            printMainMenu();
            return;
        }

        try {
            List<String> lines = Files.readAllLines(tradesCsv);
            if (lines.size() < 2) {
                System.out.println("  No trades found in trades.csv.");
                printMainMenu();
                return;
            }

            // Parse header to find column indices
            String[] headers = lines.get(0).toLowerCase().split(",");
            Map<String, Integer> colIdx = new HashMap<>();
            for (int i = 0; i < headers.length; i++) {
                colIdx.put(headers[i].trim(), i);
            }

            int strategyIdx = colIdx.getOrDefault("strategy", 1);
            int netPnlIdx = colIdx.getOrDefault("netpnl", 12);
            int exitReasonIdx = colIdx.getOrDefault("exitreason", 8);
            int entryTimeIdx = colIdx.getOrDefault("entrytime", 5);
            int patternIdx = colIdx.getOrDefault("pattern", 15);

            // Data collectors
            int totalTrades = 0;
            int wins = 0;
            double totalPnl = 0;

            Map<String, int[]> strategyStats = new TreeMap<>();
            Map<String, Double> strategyPnl = new TreeMap<>();
            Map<String, int[]> patternStats = new TreeMap<>();
            Map<String, Double> patternPnl = new TreeMap<>();
            Map<Integer, Double> hourlyPnl = new TreeMap<>();
            Map<Integer, Integer> hourlyCount = new TreeMap<>();
            Map<String, int[]> exitReasonStats = new TreeMap<>();

            for (int i = 1; i < lines.size(); i++) {
                String line = lines.get(i).trim();
                if (line.isEmpty()) continue;

                String[] cols = line.split(",", -1);
                totalTrades++;

                double pnl = 0;
                if (netPnlIdx >= 0 && netPnlIdx < cols.length) {
                    try { pnl = Double.parseDouble(cols[netPnlIdx].trim()); } catch (Exception ignored) {}
                }
                totalPnl += pnl;

                boolean isWin = pnl > 0;
                if (isWin) wins++;

                // Strategy stats
                if (strategyIdx >= 0 && strategyIdx < cols.length) {
                    String strategy = cols[strategyIdx].trim();
                    if (!strategy.isEmpty()) {
                        strategyStats.computeIfAbsent(strategy, k -> new int[3])[0]++;
                        if (isWin) strategyStats.get(strategy)[1]++;
                        else strategyStats.get(strategy)[2]++;
                        strategyPnl.merge(strategy, pnl, Double::sum);
                    }
                }

                // Pattern stats
                if (patternIdx >= 0 && patternIdx < cols.length) {
                    String pattern = cols[patternIdx].trim();
                    if (!pattern.isEmpty()) {
                        patternStats.computeIfAbsent(pattern, k -> new int[2])[0]++;
                        if (isWin) patternStats.get(pattern)[1]++;
                        patternPnl.merge(pattern, pnl, Double::sum);
                    }
                }

                // Hourly stats
                if (entryTimeIdx >= 0 && entryTimeIdx < cols.length) {
                    String timeStr = cols[entryTimeIdx].trim();
                    try {
                        String hourStr = timeStr.length() >= 13 ? timeStr.substring(11, 13) : null;
                        if (hourStr != null) {
                            int hour = Integer.parseInt(hourStr);
                            hourlyPnl.merge(hour, pnl, Double::sum);
                            hourlyCount.merge(hour, 1, Integer::sum);
                        }
                    } catch (Exception ignored) {}
                }

                // Exit reason stats
                if (exitReasonIdx >= 0 && exitReasonIdx < cols.length) {
                    String reason = cols[exitReasonIdx].trim();
                    if (!reason.isEmpty()) {
                        exitReasonStats.computeIfAbsent(reason, k -> new int[2])[0]++;
                        if (isWin) exitReasonStats.get(reason)[1]++;
                    }
                }
            }

            if (totalTrades == 0) {
                System.out.println("  No trades found in trades.csv.");
                printMainMenu();
                return;
            }

            double winRate = (double) wins / totalTrades * 100;

            // Print summary
            System.out.println("\n" + "=".repeat(80));
            System.out.println(Emoji.CHART() + " TRADE SIGNAL SUMMARY");
            System.out.println("=".repeat(80));
            System.out.printf("  Total Trades:  %d%n", totalTrades);
            System.out.printf("  Win Rate:      %.1f%% (%dW / %dL)%n", winRate, wins, totalTrades - wins);
            System.out.printf("  Total PnL:     $%.2f%n", totalPnl);
            System.out.printf("  Avg PnL/Trade: $%.2f%n", totalPnl / totalTrades);

            // Per-strategy breakdown
            System.out.println("\n" + "-".repeat(80));
            System.out.println("PER-STRATEGY BREAKDOWN:");
            System.out.printf("  %-30s | %-8s | %-8s | %12s%n", "Strategy", "Trades", "Win%", "PnL");
            System.out.println("  " + "-".repeat(76));
            for (var entry : strategyStats.entrySet()) {
                String s = entry.getKey();
                int[] stats = entry.getValue();
                double sWinRate = stats[0] > 0 ? (double) stats[1] / stats[0] * 100 : 0;
                double sPnl = strategyPnl.getOrDefault(s, 0.0);
                System.out.printf("  %-30s | %8d | %7.1f%% | $%11.2f%n", s, stats[0], sWinRate, sPnl);
            }

            // Pattern performance
            System.out.println("\n" + "-".repeat(80));
            System.out.println("PATTERN PERFORMANCE:");
            System.out.printf("  %-30s | %-8s | %-8s | %12s%n", "Pattern", "Trades", "Win%", "PnL");
            System.out.println("  " + "-".repeat(76));
            for (var entry : patternStats.entrySet()) {
                String p = entry.getKey();
                int[] stats = entry.getValue();
                double pWinRate = stats[0] > 0 ? (double) stats[1] / stats[0] * 100 : 0;
                double pPnl = patternPnl.getOrDefault(p, 0.0);
                System.out.printf("  %-30s | %8d | %7.1f%% | $%11.2f%n", p, stats[0], pWinRate, pPnl);
            }

            // Best/worst patterns
            if (!patternPnl.isEmpty()) {
                String bestPattern = patternPnl.entrySet().stream()
                        .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("N/A");
                String worstPattern = patternPnl.entrySet().stream()
                        .min(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("N/A");
                System.out.printf("%n  Best Pattern:  %s ($%.2f)%n", bestPattern, patternPnl.get(bestPattern));
                System.out.printf("  Worst Pattern: %s ($%.2f)%n", worstPattern, patternPnl.get(worstPattern));
            }

            // Time-of-day analysis
            if (!hourlyPnl.isEmpty()) {
                System.out.println("\n" + "-".repeat(80));
                System.out.println("TIME-OF-DAY ANALYSIS:");
                System.out.printf("  %-10s | %-8s | %12s | %12s%n", "Hour", "Trades", "Total PnL", "Avg PnL");
                System.out.println("  " + "-".repeat(58));
                for (var entry : hourlyPnl.entrySet()) {
                    int hour = entry.getKey();
                    double hPnl = entry.getValue();
                    int hCount = hourlyCount.getOrDefault(hour, 1);
                    System.out.printf("  %02d:00      | %8d | $%11.2f | $%11.2f%n", hour, hCount, hPnl, hPnl / hCount);
                }
                int bestHour = hourlyPnl.entrySet().stream()
                        .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(-1);
                if (bestHour >= 0) {
                    System.out.printf("%n  Most Profitable Hour: %02d:00 ($%.2f)%n", bestHour, hourlyPnl.get(bestHour));
                }
            }

            // Exit reason breakdown
            if (!exitReasonStats.isEmpty()) {
                System.out.println("\n" + "-".repeat(80));
                System.out.println("EXIT REASON BREAKDOWN:");
                System.out.printf("  %-20s | %-8s | %-8s%n", "Exit Reason", "Count", "Win%");
                System.out.println("  " + "-".repeat(42));
                for (var entry : exitReasonStats.entrySet()) {
                    String reason = entry.getKey();
                    int[] stats = entry.getValue();
                    double rWinRate = stats[0] > 0 ? (double) stats[1] / stats[0] * 100 : 0;
                    System.out.printf("  %-20s | %8d | %7.1f%%%n", reason, stats[0], rWinRate);
                }
            }

            // Recommendations
            System.out.println("\n" + "-".repeat(80));
            System.out.println(Emoji.LIGHTBULB() + " AUTOMATIC RECOMMENDATIONS:");
            System.out.println("-".repeat(80));

            strategyPnl.entrySet().stream()
                    .filter(e -> e.getValue() < 0)
                    .min(Map.Entry.comparingByValue())
                    .ifPresent(e -> System.out.printf("  - Review or disable: %s (PnL: $%.2f)%n", e.getKey(), e.getValue()));

            patternPnl.entrySet().stream()
                    .filter(e -> e.getValue() < 0)
                    .min(Map.Entry.comparingByValue())
                    .ifPresent(e -> System.out.printf("  - Low-signal pattern: %s (PnL: $%.2f)%n", e.getKey(), e.getValue()));

            if (winRate < 40) {
                System.out.println("  - Overall win rate is low (<40%). Consider widening stop losses.");
            } else if (winRate > 60) {
                System.out.println("  - Strong win rate. Consider increasing position sizes.");
            }

        } catch (Exception e) {
            System.out.println(Emoji.CROSS() + " Error analyzing trades: " + e.getMessage());
            log.error("Signal review error", e);
        }

        printMainMenu();
    }
}
