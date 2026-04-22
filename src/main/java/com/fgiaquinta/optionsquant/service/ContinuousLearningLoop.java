package com.fgiaquinta.optionsquant.service;

import com.fgiaquinta.optionsquant.backtest.domain.BacktestConfig;
import com.fgiaquinta.optionsquant.backtest.domain.BacktestReport;
import com.fgiaquinta.optionsquant.backtest.engine.BacktestEngine;
import com.fgiaquinta.optionsquant.domain.TimeFrame;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Continuous Learning Loop: Runs backtests iteratively, learns from each one,
 * and tracks improvement until convergence.
 *
 * Usage:
 * 1. Start the loop with a set of tickers and date range
 * 2. System runs backtest → analyzes → applies learnings → runs again
 * 3. Stops when:
 *    - Win rate stabilizes (no improvement for 3 consecutive iterations)
 *    - Maximum iterations reached (default: 20)
 *    - User manually stops it
 *
 * This is the TRAINING MODE for paper trading before going live!
 */
@Slf4j
@Service
public class ContinuousLearningLoop {

    private final BacktestEngine backtestEngine;
    private final TradingLearningAnalyzer learningAnalyzer;
    private final TickerMemory tickerMemory;
    private final OllamaService ollamaService;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private int currentIteration = 0;

    public ContinuousLearningLoop(BacktestEngine backtestEngine,
                                  TradingLearningAnalyzer learningAnalyzer,
                                  TickerMemory tickerMemory,
                                  OllamaService ollamaService) {
        this.backtestEngine = backtestEngine;
        this.learningAnalyzer = learningAnalyzer;
        this.tickerMemory = tickerMemory;
        this.ollamaService = ollamaService;
    }

    /**
     * Starts the continuous learning loop.
     *
     * @param tickers List of tickers to trade
     * @param fromDate Start date
     * @param toDate End date
     * @param initialCapital Starting capital
     * @param riskPct Risk per trade (e.g., 0.02 = 2%)
     * @param maxIterations Maximum number of backtest iterations (default: 20)
     * @param convergenceThreshold Stop if improvement is less than this % for 3 consecutive iterations
     */
    public LearningLoopResult startLoop(List<String> tickers,
                                        LocalDate fromDate,
                                        LocalDate toDate,
                                        double initialCapital,
                                        double riskPct,
                                        int maxIterations,
                                        double convergenceThreshold) {
        if (running.compareAndSet(false, true)) {
            log.info("🚀 [Learning Loop] Starting continuous training with {} tickers, max {} iterations",
                    tickers.size(), maxIterations);
        } else {
            throw new IllegalStateException("Learning loop is already running!");
        }

        LearningLoopResult result = new LearningLoopResult();
        result.tickers = tickers;
        result.maxIterations = maxIterations;
        result.startTime = System.currentTimeMillis();

        double previousWinRate = 0;
        double previousPnl = 0;
        int noImprovementCount = 0;

        try {
            for (currentIteration = 1; currentIteration <= maxIterations; currentIteration++) {
                if (!running.get()) {
                    log.info("🛑 [Learning Loop] Manually stopped at iteration {}", currentIteration);
                    break;
                }

                log.info("\n" + "═".repeat(100));
                log.info("🔄 ITERATION {} of {}", currentIteration, maxIterations);
                log.info("═".repeat(100));

                // Step 1: Run backtest
                BacktestConfig config = new BacktestConfig(
                        tickers, fromDate, toDate,
                        initialCapital, riskPct, 0.005, 0.65,
                        3, TimeFrame.MIN_15, true, false,
                        0.0, 0.0
                );

                log.info("📊 Running backtest...");
                BacktestReport report = backtestEngine.run(config);

                if (report.totalTrades() == 0) {
                    log.warn("⚠️ [Learning Loop] No trades in iteration {}, stopping", currentIteration);
                    break;
                }

                // Step 2: Record iteration results
                IterationResult iterResult = new IterationResult();
                iterResult.iteration = currentIteration;
                iterResult.totalTrades = report.totalTrades();
                iterResult.winRate = report.winRate();
                iterResult.totalPnl = report.finalCapital() - report.initialCapital();
                iterResult.maxDrawdown = report.maxDrawdown();
                iterResult.profitFactor = report.profitFactor();
                iterResult.avgWin = report.avgWin();
                iterResult.avgLoss = report.avgLoss();
                result.iterations.add(iterResult);

                log.info("✅ Iteration {} complete: {} trades, {}% WR, ${} PnL, PF={}",
                        currentIteration, iterResult.totalTrades, 
                        String.format("%.1f", iterResult.winRate * 100),
                        String.format("%.2f", iterResult.totalPnl),
                        String.format("%.2f", iterResult.profitFactor));

                // Step 3: Analyze and learn
                log.info("🧠 [Learning] Analyzing results...");
                var learningReport = learningAnalyzer.analyze(report);

                // Step 4: Check for convergence
                double winRateImprovement = Math.abs(report.winRate() - previousWinRate);
                double pnlImprovement = Math.abs(iterResult.totalPnl - previousPnl);

                if (currentIteration > 1 &&
                    winRateImprovement < convergenceThreshold &&
                    pnlImprovement < (initialCapital * convergenceThreshold)) {
                    noImprovementCount++;
                    log.info("⏸️ [Convergence] No significant improvement (WR: {}%, PnL: ${}) - count: {}/3",
                            String.format("%.2f", winRateImprovement * 100), 
                            String.format("%.2f", pnlImprovement), noImprovementCount);

                    if (noImprovementCount >= 3) {
                        log.info("🎯 [Convergence] Reached optimal performance after {} iterations!", currentIteration);
                        log.info("   Final Win Rate: {}%", String.format("%.1f", report.winRate() * 100));
                        log.info("   Final PnL: ${}", String.format("%.2f", iterResult.totalPnl));
                        result.convergenceReason = "No improvement for 3 consecutive iterations";
                        result.convergedAtIteration = currentIteration;
                        break;
                    }
                } else {
                    noImprovementCount = 0;
                }

                previousWinRate = report.winRate();
                previousPnl = iterResult.totalPnl;

                // Step 5: Log current ticker memory status
                if (currentIteration % 5 == 0 || currentIteration == maxIterations) {
                    log.info("\n{}", tickerMemory.getLearningReport());
                }

                // Step 6: Every 10 iterations, get AI analysis
                if (currentIteration % 10 == 0 && ollamaService.isAvailable()) {
                    try {
                        log.info("🤖 [AI] Getting Ollama analysis...");
                        String aiAnalysis = ollamaService.analyzeLearningReport(tickerMemory.getLearningReport());
                        log.info("\n{}", aiAnalysis);
                    } catch (Exception e) {
                        log.warn("⚠️ AI analysis failed: {}", e.getMessage());
                    }
                }
            }

            // Final summary
            result.endTime = System.currentTimeMillis();
            result.elapsedMs = result.endTime - result.startTime;
            result.completedIterations = currentIteration - 1;

            if (result.convergedAtIteration == null) {
                result.convergenceReason = "Reached maximum iterations";
            }

            log.info("\n" + "═".repeat(100));
            log.info("🎓 [Learning Loop] TRAINING COMPLETE!");
            log.info("═".repeat(100));
            log.info("📊 RESULTS:");
            log.info("   Iterations: {}", result.completedIterations);
            log.info("   Reason: {}", result.convergenceReason);
            log.info("   Elapsed: {} ms ({} minutes)", result.elapsedMs, 
                    String.format("%.1f", result.elapsedMs / 60000.0));

            if (!result.iterations.isEmpty()) {
                IterationResult first = result.iterations.get(0);
                IterationResult last = result.iterations.get(result.iterations.size() - 1);

                log.info("\n📈 IMPROVEMENT:");
                log.info("   First: {} trades, {}% WR, ${} PnL",
                        first.totalTrades, String.format("%.1f", first.winRate * 100), 
                        String.format("%.2f", first.totalPnl));
                log.info("   Last:  {} trades, {}% WR, ${} PnL",
                        last.totalTrades, String.format("%.1f", last.winRate * 100), 
                        String.format("%.2f", last.totalPnl));
                log.info("   Δ Win Rate: {}%", String.format("%+.1f", (last.winRate - first.winRate) * 100));
                log.info("   Δ PnL: ${}", String.format("%+.2f", last.totalPnl - first.totalPnl));
            }

            log.info("\n{}", tickerMemory.getLearningReport());

        } catch (Exception e) {
            log.error("❌ [Learning Loop] Error at iteration {}: {}", currentIteration, e.getMessage(), e);
            result.error = e.getMessage();
        } finally {
            running.set(false);
        }

        return result;
    }

    /**
     * Stops the learning loop.
     */
    public void stop() {
        if (running.compareAndSet(true, false)) {
            log.info("🛑 [Learning Loop] Stop requested, will finish current iteration...");
        }
    }

    /**
     * Checks if the loop is currently running.
     */
    public boolean isRunning() {
        return running.get();
    }

    /**
     * Response record for the learning loop.
     */
    public static class LearningLoopResult {
        public List<String> tickers;
        public int maxIterations;
        public int completedIterations;
        public long startTime;
        public long endTime;
        public long elapsedMs;
        public List<IterationResult> iterations = new java.util.ArrayList<>();
        public String convergenceReason;
        public Integer convergedAtIteration;
        public String error;
    }

    public static class IterationResult {
        public int iteration;
        public int totalTrades;
        public double winRate;
        public double totalPnl;
        public double maxDrawdown;
        public double profitFactor;
        public double avgWin;
        public double avgLoss;
    }
}
