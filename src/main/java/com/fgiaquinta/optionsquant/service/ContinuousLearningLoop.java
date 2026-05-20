package com.fgiaquinta.optionsquant.service;

import com.fgiaquinta.optionsquant.backtest.domain.BacktestConfig;
import com.fgiaquinta.optionsquant.backtest.domain.BacktestReport;
import com.fgiaquinta.optionsquant.backtest.engine.BacktestEngine;
import com.fgiaquinta.optionsquant.domain.TimeFrame;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
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
        result.setTickers(tickers);
        result.setMaxIterations(maxIterations);
        result.setStartTime(System.currentTimeMillis());

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
                        initialCapital, riskPct, 0.0008, 0.65,
                        3, TimeFrame.MIN_15, true, false,
                        0.0, 0.0, null,
                        LocalTime.of(9, 45),
                        LocalTime.of(10, 30),
                        LocalTime.of(13, 0),
                        null  // strategyFilter — run all strategies
                );

                log.info("📊 Running backtest...");
                BacktestReport report = backtestEngine.run(config);

                if (report.totalTrades() == 0) {
                    log.warn("⚠️ [Learning Loop] No trades in iteration {}, stopping", currentIteration);
                    break;
                }

                // Step 2: Record iteration results
                IterationResult iterResult = new IterationResult(
                        currentIteration,
                        report.totalTrades(),
                        report.winRate(),
                        report.finalCapital() - report.initialCapital(),
                        report.maxDrawdown(),
                        report.profitFactor(),
                        report.avgWin(),
                        report.avgLoss()
                );
                result.getIterations().add(iterResult);

                log.info("✅ Iteration {} complete: {} trades, {}% WR, ${} PnL, PF={}",
                        currentIteration, iterResult.getTotalTrades(),
                        String.format("%.1f", iterResult.getWinRate() * 100),
                        String.format("%.2f", iterResult.getTotalPnl()),
                        String.format("%.2f", iterResult.getProfitFactor()));

                // Step 3: Analyze and learn
                log.info("🧠 [Learning] Analyzing results...");
                learningAnalyzer.analyze(report);

                // Step 4: Check for convergence
                double winRateImprovement = Math.abs(report.winRate() - previousWinRate);
                double pnlImprovement = Math.abs(iterResult.getTotalPnl() - previousPnl);

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
                        log.info("   Final PnL: ${}", String.format("%.2f", iterResult.getTotalPnl()));
                        result.setConvergenceReason("No improvement for 3 consecutive iterations");
                        result.setConvergedAtIteration(currentIteration);
                        break;
                    }
                } else {
                    noImprovementCount = 0;
                }

                previousWinRate = report.winRate();
                previousPnl = iterResult.getTotalPnl();

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
            long endTime = System.currentTimeMillis();
            result.setEndTime(endTime);
            result.setElapsedMs(endTime - result.getStartTime());
            result.setCompletedIterations(currentIteration - 1);

            if (result.getConvergedAtIteration() == null) {
                result.setConvergenceReason("Reached maximum iterations");
            }

            log.info("\n" + "═".repeat(100));
            log.info("🎓 [Learning Loop] TRAINING COMPLETE!");
            log.info("═".repeat(100));
            log.info("📊 RESULTS:");
            log.info("   Iterations: {}", result.getCompletedIterations());
            log.info("   Reason: {}", result.getConvergenceReason());
            log.info("   Elapsed: {} ms ({} minutes)", result.getElapsedMs(),
                    String.format("%.1f", result.getElapsedMs() / 60000.0));

            if (!result.getIterations().isEmpty()) {
                IterationResult first = result.getIterations().get(0);
                IterationResult last = result.getIterations().get(result.getIterations().size() - 1);

                log.info("\n📈 IMPROVEMENT:");
                log.info("   First: {} trades, {}% WR, ${} PnL",
                        first.getTotalTrades(), String.format("%.1f", first.getWinRate() * 100),
                        String.format("%.2f", first.getTotalPnl()));
                log.info("   Last:  {} trades, {}% WR, ${} PnL",
                        last.getTotalTrades(), String.format("%.1f", last.getWinRate() * 100),
                        String.format("%.2f", last.getTotalPnl()));
                log.info("   Δ Win Rate: {}%", String.format("%+.1f", (last.getWinRate() - first.getWinRate()) * 100));
                log.info("   Δ PnL: ${}", String.format("%+.2f", last.getTotalPnl() - first.getTotalPnl()));
            }

            log.info("\n{}", tickerMemory.getLearningReport());

        } catch (Exception e) {
            log.error("❌ [Learning Loop] Error at iteration {}: {}", currentIteration, e.getMessage(), e);
            result.setError(e.getMessage());
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
     * Response value type for the learning loop. Mutable during construction; read-only after return.
     */
    public static class LearningLoopResult {
        private List<String> tickers;
        private int maxIterations;
        private int completedIterations;
        private long startTime;
        private long endTime;
        private long elapsedMs;
        private final List<IterationResult> iterations = new ArrayList<>();
        private String convergenceReason;
        private Integer convergedAtIteration;
        private String error;

        public List<String> getTickers() { return tickers; }
        public int getMaxIterations() { return maxIterations; }
        public int getCompletedIterations() { return completedIterations; }
        public long getStartTime() { return startTime; }
        public long getEndTime() { return endTime; }
        public long getElapsedMs() { return elapsedMs; }
        public List<IterationResult> getIterations() { return iterations; }
        public String getConvergenceReason() { return convergenceReason; }
        public Integer getConvergedAtIteration() { return convergedAtIteration; }
        public String getError() { return error; }

        // package-private setters — used only by ContinuousLearningLoop
        void setTickers(List<String> v) { this.tickers = v; }
        void setMaxIterations(int v) { this.maxIterations = v; }
        void setCompletedIterations(int v) { this.completedIterations = v; }
        void setStartTime(long v) { this.startTime = v; }
        void setEndTime(long v) { this.endTime = v; }
        void setElapsedMs(long v) { this.elapsedMs = v; }
        void setConvergenceReason(String v) { this.convergenceReason = v; }
        void setConvergedAtIteration(Integer v) { this.convergedAtIteration = v; }
        void setError(String v) { this.error = v; }
    }

    public static class IterationResult {
        private final int iteration;
        private final int totalTrades;
        private final double winRate;
        private final double totalPnl;
        private final double maxDrawdown;
        private final double profitFactor;
        private final double avgWin;
        private final double avgLoss;

        public IterationResult(int iteration, int totalTrades, double winRate, double totalPnl,
                               double maxDrawdown, double profitFactor, double avgWin, double avgLoss) {
            this.iteration = iteration;
            this.totalTrades = totalTrades;
            this.winRate = winRate;
            this.totalPnl = totalPnl;
            this.maxDrawdown = maxDrawdown;
            this.profitFactor = profitFactor;
            this.avgWin = avgWin;
            this.avgLoss = avgLoss;
        }

        public int getIteration() { return iteration; }
        public int getTotalTrades() { return totalTrades; }
        public double getWinRate() { return winRate; }
        public double getTotalPnl() { return totalPnl; }
        public double getMaxDrawdown() { return maxDrawdown; }
        public double getProfitFactor() { return profitFactor; }
        public double getAvgWin() { return avgWin; }
        public double getAvgLoss() { return avgLoss; }
    }
}
