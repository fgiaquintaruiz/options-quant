package com.fgiaquinta.optionsquant.cli;

import com.fgiaquinta.optionsquant.backtest.domain.BacktestConfig;
import com.fgiaquinta.optionsquant.backtest.domain.BacktestReport;
import com.fgiaquinta.optionsquant.backtest.engine.BacktestEngine;
import com.fgiaquinta.optionsquant.service.BacktestAnalyzer;
import com.fgiaquinta.optionsquant.service.TickerService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Advanced CLI for running backtests with comprehensive analysis.
 * Outputs results to JSON files for later strategy tuning.
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
    private final ObjectMapper objectMapper;

    public BacktestCli(BacktestEngine backtestEngine, BacktestAnalyzer backtestAnalyzer, TickerService tickerService) {
        this.backtestEngine = backtestEngine;
        this.backtestAnalyzer = backtestAnalyzer;
        this.tickerService = tickerService;
        
        // Configure Jackson for JSON output
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    @Override
    public void run(String... args) {
        log.info("🚀 Options Quant Backtest CLI started");
        Scanner scanner = new Scanner(System.in);

        System.out.println("""
                
                ╔═══════════════════════════════════════════════╗
                ║     📊 Options Quant Backtest Analyzer CLI v1.3.30     ║
                ║     "Measure Twice, Cut Once"                  ║
                ╚═══════════════════════════════════════════════╝
                
                Available commands:
                  1. Run backtest + analysis (save to JSON)
                  2. Compare two backtest results
                  3. Analyze existing backtest results
                  4. View strategy performance history
                  5. Generate strategy tuning recommendations
                  6. Exit
                
                """);

        while (true) {
            System.out.print("\nEnter command (1-6): ");
            String input = scanner.nextLine().trim();

            try {
                switch (input) {
                    case "1" -> runBacktestAndSave(scanner);
                    case "2" -> compareResults(scanner);
                    case "3" -> analyzeExistingResults(scanner);
                    case "4" -> viewStrategyHistory();
                    case "5" -> generateTuningRecommendations();
                    case "6" -> {
                        System.out.println("👋 Exiting Backtest CLI...");
                        return;
                    }
                    default -> System.out.println("❌ Invalid command. Enter 1-6.");
                }
            } catch (Exception e) {
                System.out.println("❌ Error: " + e.getMessage());
                log.error("CLI error", e);
            }
        }
    }

    private void runBacktestAndSave(Scanner scanner) {
        System.out.println("\n📝 Backtest Configuration:");
        System.out.println("  ℹ️ Press Enter to use default values shown in [brackets]");

        // Default: 1 year back from today
        LocalDate defaultTo = LocalDate.now();
        LocalDate defaultFrom = defaultTo.minusYears(1);

        System.out.println("\n  ┌─ Date Range ──────────────────────────────────┐");
        System.out.println("  │ From: Start date for historical data         │");
        System.out.println("  │ To:   End date (usually today)               │");
        System.out.println("  └──────────────────────────────────────────────┘");
        System.out.printf("  From date (YYYY-MM-DD) [%s]: ", defaultFrom);
        String from = scanner.nextLine().trim();
        if (from.isEmpty()) from = defaultFrom.toString();

        System.out.printf("  To date (YYYY-MM-DD) [%s]: ", defaultTo);
        String to = scanner.nextLine().trim();
        if (to.isEmpty()) to = defaultTo.toString();

        System.out.println("\n  ┌─ Risk Management ─────────────────────────────┐");
        System.out.println("  │ Capital: Starting account balance            │");
        System.out.println("  │ Risk %: Max % of capital per trade           │");
        System.out.println("  │         0.01 = 1%, 0.02 = 2% (recommended)  │");
        System.out.println("  └──────────────────────────────────────────────┘");
        System.out.print("  Initial capital [50000]: ");
        String capital = scanner.nextLine().trim();
        double initialCapital = capital.isEmpty() ? 50000 : Double.parseDouble(capital);

        System.out.print("  Risk % per trade [0.02]: ");
        String risk = scanner.nextLine().trim();
        double riskPct = risk.isEmpty() ? 0.02 : Double.parseDouble(risk);

        System.out.println("\n  ┌─ Ticker Selection ────────────────────────────┐");
        System.out.println("  │ Options:                                       │");
        System.out.println("  │   [Enter]     = Scan all 512 tickers           │");
        System.out.println("  │   SPY,AAPL    = Scan specific tickers          │");
        System.out.println("  │   @hot        = Scan 15 hot tickers only       │");
        System.out.println("  │   @quality    = Scan high-quality tickers      │");
        System.out.println("  └──────────────────────────────────────────────┘");
        System.out.print("  Tickers (comma-separated, empty=all, @hot, @quality): ");
        String tickersInput = scanner.nextLine().trim();
        List<String> tickers;
        if (tickersInput.isEmpty()) {
            tickers = tickerService.getTickerSymbols();
        } else if (tickersInput.equalsIgnoreCase("@hot")) {
            tickers = tickerService.getHotTickers();
        } else if (tickersInput.equalsIgnoreCase("@quality")) {
            tickers = tickerService.getHighQualityTickers().stream()
                    .map(t -> t.ticker())
                    .toList();
        } else {
            tickers = List.of(tickersInput.split(","));
        }

        // Auto-generate test name with date, time, and ticker count
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("MMdd_HHmm"));
        String testName = String.format("bt_%s_%dtickers", timestamp, tickers.size());
        
        System.out.println("\n  ┌─ Test Identification ─────────────────────────┐");
        System.out.println("  │ Auto-generated: bt_MMdd_HHMM_Ntickers        │");
        System.out.println("  │ Custom: Enter your own name for comparison   │");
        System.out.println("  └──────────────────────────────────────────────┘");
        System.out.printf("  Test name (auto-generated) [%s]: ", testName);
        String customName = scanner.nextLine().trim();
        if (!customName.isEmpty()) {
            testName = customName;
        }

        System.out.println("\n🔄 Running backtest...");
        BacktestConfig config = new BacktestConfig(
                tickers,
                LocalDate.parse(from),
                LocalDate.parse(to),
                initialCapital,
                riskPct,
                0.005,  // slippage
                0.65,   // commission
                3,      // max concurrent
                com.fgiaquinta.optionsquant.domain.TimeFrame.MIN_15,
                true
        );

        BacktestReport report = backtestEngine.run(config);

        // Analyze results
        System.out.println("\n🔍 Analyzing results...");
        BacktestAnalyzer.AnalysisReport analysis = backtestAnalyzer.analyzeTrades(
                java.nio.file.Path.of("backtest/trades.csv"));

        // Create comprehensive result object
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("testName", testName);
        result.put("timestamp", LocalDateTime.now().toString());
        result.put("config", Map.of(
                "from", from,
                "to", to,
                "initialCapital", initialCapital,
                "riskPerTradePct", riskPct,
                "tickers", tickers.size(),
                "tickerList", tickers
        ));
        // Build performance map
        Map<String, Object> perfMap = new LinkedHashMap<>();
        perfMap.put("finalCapital", report.finalCapital());
        perfMap.put("totalReturn", report.totalReturn());
        perfMap.put("totalReturnPct", report.totalReturnPct() * 100);
        perfMap.put("totalTrades", report.totalTrades());
        perfMap.put("winningTrades", report.winningTrades());
        perfMap.put("losingTrades", report.losingTrades());
        perfMap.put("winRate", report.winRate() * 100);
        perfMap.put("profitFactor", report.profitFactor());
        perfMap.put("sharpeRatio", report.sharpeRatio());
        perfMap.put("maxDrawdown", report.maxDrawdown());
        perfMap.put("maxDrawdownPct", report.maxDrawdownPct() * 100);
        perfMap.put("avgWin", report.avgWin());
        perfMap.put("avgLoss", report.avgLoss());
        perfMap.put("avgTradeDurationHours", report.avgTradeDurationHours());
        result.put("performance", perfMap);
        result.put("byStrategy", report.byStrategy());
        result.put("byTicker", report.byTicker());
        result.put("analysis", Map.of(
                "summary", analysis.summary(),
                "totalSuggestions", analysis.getSuggestionCount(),
                "criticalIssues", analysis.getCriticalSuggestions(),
                "optimizationTips", analysis.getOptimizationSuggestions(),
                "allSuggestions", analysis.suggestions()
        ));

        // Save to results directory
        Path resultsDir = Path.of("backtest/results");
        try {
            Files.createDirectories(resultsDir);
            Path resultFile = resultsDir.resolve(testName + ".json");
            objectMapper.writeValue(resultFile.toFile(), result);

            System.out.println("\n" + "=".repeat(60));
            System.out.println("✅ BACKTEST COMPLETE");
            System.out.println("=".repeat(60));
            System.out.printf("Results saved to: %s%n", resultFile.toAbsolutePath());
            System.out.printf("Trades CSV: backtest/trades.csv%n");
            System.out.printf("Equity CSV: backtest/equity.csv%n");
            System.out.printf("Summary: backtest/summary.txt%n");
            System.out.println("\n📊 Performance Summary:");
            System.out.printf("  Return: $%.2f (%.2f%%)%n", report.totalReturn(), report.totalReturnPct() * 100);
            System.out.printf("  Trades: %d (Win Rate: %.1f%%)%n", report.totalTrades(), report.winRate() * 100);
            System.out.printf("  Profit Factor: %.2f%n", report.profitFactor());
            System.out.printf("  Max Drawdown: %.2f%%%n", report.maxDrawdownPct() * 100);
            System.out.printf("  Sharpe Ratio: %.2f%n", report.sharpeRatio());
            System.out.println("\n💡 Suggestions:");
            System.out.printf("  Critical Issues: %d%n", analysis.getCriticalSuggestions().size());
            System.out.printf("  Optimization Tips: %d%n", analysis.getOptimizationSuggestions().size());
            
        } catch (Exception e) {
            System.out.println("❌ Failed to save results: " + e.getMessage());
            log.error("Failed to save results", e);
        }
    }

    private void compareResults(Scanner scanner) {
        System.out.println("\n📊 Compare Two Backtest Results:");
        
        System.out.print("  First test filename (without .json): ");
        String test1 = scanner.nextLine().trim();
        
        System.out.print("  Second test filename (without .json): ");
        String test2 = scanner.nextLine().trim();

        try {
            Path resultsDir = Path.of("backtest/results");
            Map<?, ?> result1 = objectMapper.readValue(
                    resultsDir.resolve(test1 + ".json").toFile(), Map.class);
            Map<?, ?> result2 = objectMapper.readValue(
                    resultsDir.resolve(test2 + ".json").toFile(), Map.class);

            Map<String, Object> perf1 = (Map<String, Object>) result1.get("performance");
            Map<String, Object> perf2 = (Map<String, Object>) result2.get("performance");

            System.out.println("\n" + "=".repeat(80));
            System.out.println("📈 BACKTEST COMPARISON");
            System.out.println("=".repeat(80));
            System.out.printf("%-25s | %-20s | %-20s%n", "Metric", test1, test2);
            System.out.println("-".repeat(80));
            System.out.printf("%-25s | $%-19.2f | $%-19.2f%n", "Final Capital", 
                    perf1.get("finalCapital"), perf2.get("finalCapital"));
            System.out.printf("%-25s | %19.2f%% | %19.2f%%n", "Return %", 
                    perf1.get("totalReturnPct"), perf2.get("totalReturnPct"));
            System.out.printf("%-25s | %20d | %20dn", "Total Trades", 
                    perf1.get("totalTrades"), perf2.get("totalTrades"));
            System.out.printf("%-25s | %19.1f%% | %19.1f%%n", "Win Rate", 
                    perf1.get("winRate"), perf2.get("winRate"));
            System.out.printf("%-25s | %20.2f | %20.2f%n", "Profit Factor", 
                    perf1.get("profitFactor"), perf2.get("profitFactor"));
            System.out.printf("%-25s | %19.2f%% | %19.2f%%n", "Max Drawdown %", 
                    perf1.get("maxDrawdownPct"), perf2.get("maxDrawdownPct"));
            System.out.printf("%-25s | %20.2f | %20.2f%n", "Sharpe Ratio", 
                    perf1.get("sharpeRatio"), perf2.get("sharpeRatio"));
            System.out.println("=".repeat(80));

            // Calculate winner
            double return1 = (double) perf1.get("totalReturnPct");
            double return2 = (double) perf2.get("totalReturnPct");
            String winner = return1 > return2 ? test1 : test2;
            System.out.printf("\n🏆 Better Return: %s (%.2f%% vs %.2f%%)%n", 
                    winner, return1, return2);

        } catch (Exception e) {
            System.out.println("❌ Failed to compare: " + e.getMessage());
        }
    }

    private void analyzeExistingResults(Scanner scanner) {
        System.out.println("\n📂 Available Backtest Results:");
        
        Path resultsDir = Path.of("backtest/results");
        if (!Files.exists(resultsDir)) {
            System.out.println("  No results found. Run a backtest first.");
            return;
        }

        try {
            Files.list(resultsDir)
                    .filter(p -> p.toString().endsWith(".json"))
                    .sorted()
                    .forEach(p -> {
                        try {
                            Map<?, ?> result = objectMapper.readValue(p.toFile(), Map.class);
                            Map<?, ?> perf = (Map<?, ?>) result.get("performance");
                            System.out.printf("  %-40s | Return: %7.2f%% | Trades: %3d | Win: %5.1f%%%n",
                                    p.getFileName(),
                                    perf.get("totalReturnPct"),
                                    perf.get("totalTrades"),
                                    perf.get("winRate"));
                        } catch (Exception e) {
                            System.out.println("  " + p.getFileName() + " (error reading)");
                        }
                    });

            System.out.print("\n  Select test to analyze (filename without .json): ");
            String testName = scanner.nextLine().trim();
            
            Path testFile = resultsDir.resolve(testName + ".json");
            if (Files.exists(testFile)) {
                Map<?, ?> result = objectMapper.readValue(testFile.toFile(), Map.class);
                Map<?, ?> analysis = (Map<?, ?>) result.get("analysis");
                
                System.out.println("\n" + "=".repeat(60));
                System.out.println("📈 ANALYSIS: " + testName);
                System.out.println("=".repeat(60));
                System.out.println(analysis.get("summary"));
                System.out.println("\n⚠️ Critical Issues:");
                @SuppressWarnings("unchecked")
                List<String> critical = (List<String>) analysis.get("criticalIssues");
                critical.forEach(s -> System.out.println("  " + s));
                
                System.out.println("\n💡 Optimization Tips:");
                @SuppressWarnings("unchecked")
                List<String> optimization = (List<String>) analysis.get("optimizationTips");
                optimization.forEach(s -> System.out.println("  " + s));
            } else {
                System.out.println("❌ File not found: " + testFile);
            }
        } catch (Exception e) {
            System.out.println("❌ Error: " + e.getMessage());
        }
    }

    private void viewStrategyHistory() {
        System.out.println("\n📊 Strategy Performance History:");
        
        Path resultsDir = Path.of("backtest/results");
        if (!Files.exists(resultsDir)) {
            System.out.println("  No results found.");
            return;
        }

        try {
            // Aggregate performance by strategy across all tests
            Map<String, List<Double>> strategyReturns = new TreeMap<>();
            
            Files.list(resultsDir)
                    .filter(p -> p.toString().endsWith(".json"))
                    .forEach(p -> {
                        try {
                            Map<?, ?> result = objectMapper.readValue(p.toFile(), Map.class);
                            @SuppressWarnings("unchecked")
                            Map<String, Map<String, Object>> byStrategy = 
                                    (Map<String, Map<String, Object>>) result.get("byStrategy");
                            
                            if (byStrategy != null) {
                                byStrategy.forEach((strategy, stats) -> {
                                    strategyReturns.computeIfAbsent(strategy, k -> new ArrayList<>())
                                            .add((double) stats.getOrDefault("totalPnl", 0.0));
                                });
                            }
                        } catch (Exception e) {
                            // Skip invalid files
                        }
                    });

            System.out.printf("%-25s | %-10s | %-12s | %-12s%n", 
                    "Strategy", "Tests", "Avg PnL", "Total PnL");
            System.out.println("-".repeat(65));
            
            strategyReturns.forEach((strategy, returns) -> {
                double avg = returns.stream().mapToDouble(Double::doubleValue).average().orElse(0);
                double total = returns.stream().mapToDouble(Double::doubleValue).sum();
                System.out.printf("%-25s | %10d | $%11.2f | $%11.2f%n", 
                        strategy, returns.size(), avg, total);
            });
            
        } catch (Exception e) {
            System.out.println("❌ Error: " + e.getMessage());
        }
    }

    private void generateTuningRecommendations() {
        System.out.println("\n🔧 Strategy Tuning Recommendations:");
        
        Path resultsDir = Path.of("backtest/results");
        if (!Files.exists(resultsDir)) {
            System.out.println("  No results found. Run a backtest first.");
            return;
        }

        try {
            // Find the latest test
            Path latestTest = Files.list(resultsDir)
                    .filter(p -> p.toString().endsWith(".json"))
                    .sorted()
                    .reduce((first, second) -> second)
                    .orElse(null);

            if (latestTest == null) {
                System.out.println("  No test results found.");
                return;
            }

            Map<?, ?> result = objectMapper.readValue(latestTest.toFile(), Map.class);
            @SuppressWarnings("unchecked")
            Map<String, Map<String, Object>> byStrategy = 
                    (Map<String, Map<String, Object>>) result.get("byStrategy");

            System.out.println("\n" + "=".repeat(80));
            System.out.println("📋 AUTOMATIC STRATEGY TUNING RECOMMENDATIONS");
            System.out.println("=".repeat(80));

            byStrategy.forEach((strategy, stats) -> {
                double winRate = (double) stats.getOrDefault("winRate", 0.0);
                double profitFactor = (double) stats.getOrDefault("profitFactor", 0.0);
                double totalPnl = (double) stats.getOrDefault("totalPnl", 0.0);
                int trades = (int) stats.getOrDefault("trades", 0);

                System.out.printf("\n%s (%d trades):%n", strategy, trades);
                
                if (winRate < 0.40 && trades >= 5) {
                    System.out.println("  ❌ LOW WIN RATE: " + String.format("%.1f%%", winRate * 100));
                    System.out.println("  → Recommendation: Widen SL ATR multiplier by 0.3-0.5");
                    System.out.println("  → Consider: Better entry timing or disable strategy");
                } else if (winRate >= 0.60 && profitFactor > 1.5) {
                    System.out.println("  ✅ HIGH PERFORMER: " + String.format("%.1f%% win rate", winRate * 100));
                    System.out.println("  → Recommendation: Increase risk allocation for this strategy");
                }

                if (profitFactor < 1.0 && trades >= 5) {
                    System.out.println("  ⚠️ NEGATIVE EXPECTANCY: PF=" + String.format("%.2f", profitFactor));
                    System.out.println("  → Recommendation: Review TP/SL ratio, aim for >1.5");
                }

                if (totalPnl < -1000 && trades >= 3) {
                    System.out.println("  🚨 SIGNIFICANT LOSSES: $" + String.format("%.2f", totalPnl));
                    System.out.println("  → Recommendation: Reduce position size or disable temporarily");
                }
            });

            System.out.println("\n" + "=".repeat(80));
            System.out.println("💡 To apply recommendations, edit:");
            System.out.println("   src/main/java/com/fgiaquinta/optionsquant/strategy/utils/RiskCalculator.java");
            System.out.println("   → Modify STRATEGY_SL_MULTIPLIERS and STRATEGY_TP_MULTIPLIERS");
            System.out.println("=".repeat(80));

        } catch (Exception e) {
            System.out.println("❌ Error: " + e.getMessage());
        }
    }
}
