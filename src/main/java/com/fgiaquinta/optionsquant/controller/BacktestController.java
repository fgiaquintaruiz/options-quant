package com.fgiaquinta.optionsquant.controller;

import com.fgiaquinta.optionsquant.backtest.domain.BacktestConfig;
import com.fgiaquinta.optionsquant.backtest.domain.BacktestReport;
import com.fgiaquinta.optionsquant.backtest.engine.BacktestEngine;
import com.fgiaquinta.optionsquant.config.IbkrProperties;
import com.fgiaquinta.optionsquant.domain.TimeFrame;
import com.fgiaquinta.optionsquant.service.TickerService;
import io.micrometer.core.annotation.Timed;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * REST API for running backtests.
 */
@Slf4j
@RestController
@RequestMapping("/api/backtest")
@RequiredArgsConstructor
public class BacktestController {

    private final BacktestEngine backtestEngine;
    private final IbkrProperties ibkrProperties;
    private final TickerService tickerService;
    private final com.fgiaquinta.optionsquant.service.BacktestAnalyzer backtestAnalyzer;
    private final com.fgiaquinta.optionsquant.service.NewsFilterService newsFilterService;
    private final com.fgiaquinta.optionsquant.service.StrategyScreenerService screenerService;
    private final com.fgiaquinta.optionsquant.service.TradingLearningAnalyzer learningAnalyzer;
    private final com.fgiaquinta.optionsquant.service.TickerMemory tickerMemory;
    private final com.fgiaquinta.optionsquant.service.OllamaService ollamaService;
    private final com.fgiaquinta.optionsquant.service.ContinuousLearningLoop continuousLearningLoop;

    /**
     * Run a backtest with default parameters.
     * POST /api/backtest/run?from=2025-01-01&to=2026-04-01&tickers=SPY,AAPL
     */
    @Timed(value = "backtest.run", description = "Run a backtest")
    @PostMapping("/run")
    public ResponseEntity<BacktestReport> run(
            @RequestParam String from,
            @RequestParam String to,
            @RequestParam(required = false) String tickers,
            @RequestParam(defaultValue = "50000") double initialCapital,
            @RequestParam(defaultValue = "0.02") double riskPct,
            @RequestParam(defaultValue = "0.0008") double slippagePct,
            @RequestParam(defaultValue = "0.65") double commission,
            @RequestParam(defaultValue = "3") int maxConcurrent,
            @RequestParam(defaultValue = "MIN_15") TimeFrame execTimeframe
    ) {
        log.info(">>> POST /api/backtest/run from={} to={} tickers={}", from, to, tickers);

        LocalDate fromDate = LocalDate.parse(from);
        LocalDate toDate = LocalDate.parse(to);
        
        List<String> tickerList = tickers != null && !tickers.isEmpty()
                ? List.of(tickers.split(","))
                : tickerService.getTickerSymbols();

        BacktestConfig config = new BacktestConfig(
                tickerList, fromDate, toDate,
                initialCapital, riskPct, slippagePct, commission,
                maxConcurrent, execTimeframe, true, false,
                0.0, 0.0, null,
                java.time.LocalTime.of(9, 45),
                java.time.LocalTime.of(10, 30),
                java.time.LocalTime.of(13, 0)
        );

        BacktestReport report = backtestEngine.run(config);

        // === LEARNING SYSTEM: Analyze and learn from the backtest ===
        if (report.totalTrades() > 0) {
            log.info("🧠 [Learning] Analyzing backtest results for automated learning...");
            var learningReport = learningAnalyzer.analyze(report);
            
            // Log ticker memory status
            log.info("\n{}", tickerMemory.getLearningReport());
        }

        log.info("<<< POST /api/backtest/run - {} trades, return={}%",
                report.totalTrades(), String.format("%.2f", report.totalReturnPct() * 100));
        return ResponseEntity.ok(report);
    }

    /**
     * Run a backtest with the full configured ticker list.
     * POST /api/backtest/run-all?from=2025-01-01&to=2026-04-01
     */
    @PostMapping("/run-all")
    public ResponseEntity<BacktestReport> runAll(
            @RequestParam String from,
            @RequestParam String to,
            @RequestParam(defaultValue = "50000") double initialCapital,
            @RequestParam(defaultValue = "0.02") double riskPct
    ) {
        log.info(">>> POST /api/backtest/run-all from={} to={}", from, to);

        List<String> tickerList = tickerService.getTickerSymbols();

        BacktestConfig config = BacktestConfig.defaults(
                tickerList,
                LocalDate.parse(from),
                LocalDate.parse(to)
        );

        BacktestReport report = backtestEngine.run(config);

        log.info("<<< POST /api/backtest/run-all - {} trades, return={}%",
                report.totalTrades(), String.format("%.2f", report.totalReturnPct() * 100));
        return ResponseEntity.ok(report);
    }

    /**
     * Get the current ticker memory learning report.
     * GET /api/backtest/learning-report
     */
    @GetMapping("/learning-report")
    public ResponseEntity<String> getLearningReport() {
        log.info(">>> GET /api/backtest/learning-report");
        return ResponseEntity.ok(tickerMemory.getLearningReport());
    }

    /**
     * Get AI-powered analysis of the current learning report.
     * POST /api/backtest/ai-analysis
     */
    @PostMapping("/ai-analysis")
    public ResponseEntity<String> getAiAnalysis() {
        log.info(">>> POST /api/backtest/ai-analysis");
        
        String learningReport = tickerMemory.getLearningReport();
        String aiAnalysis = ollamaService.analyzeLearningReport(learningReport);
        
        return ResponseEntity.ok(aiAnalysis);
    }

    /**
     * Start continuous learning loop (TRAINING MODE).
     * POST /api/backtest/learn?from=2025-01-01&to=2026-04-01&tickers=AMZN,NVDA,GOOGL&maxIterations=20
     */
    @PostMapping("/learn")
    public ResponseEntity<com.fgiaquinta.optionsquant.service.ContinuousLearningLoop.LearningLoopResult> startLearningLoop(
            @RequestParam String from,
            @RequestParam String to,
            @RequestParam String tickers,
            @RequestParam(defaultValue = "50000") double initialCapital,
            @RequestParam(defaultValue = "0.02") double riskPct,
            @RequestParam(defaultValue = "20") int maxIterations,
            @RequestParam(defaultValue = "0.02") double convergenceThreshold
    ) {
        log.info(">>> POST /api/backtest/learn from={} to={} tickers={} maxIter={}", 
                from, to, tickers, maxIterations);

        LocalDate fromDate = LocalDate.parse(from);
        LocalDate toDate = LocalDate.parse(to);
        List<String> tickerList = List.of(tickers.split(","));

        // Run in a separate thread to avoid blocking
        new Thread(() -> {
            var result = continuousLearningLoop.startLoop(
                    tickerList, fromDate, toDate, initialCapital, riskPct, 
                    maxIterations, convergenceThreshold);
            log.info("<<< Learning loop completed: {} iterations", result.completedIterations);
        }).start();

        return ResponseEntity.accepted().build();
    }

    /**
     * Stop the continuous learning loop.
     * POST /api/backtest/learn/stop
     */
    @PostMapping("/learn/stop")
    public ResponseEntity<String> stopLearningLoop() {
        log.info(">>> POST /api/backtest/learn/stop");
        continuousLearningLoop.stop();
        return ResponseEntity.ok("Learning loop stop requested");
    }

    /**
     * Check if learning loop is running.
     * GET /api/backtest/learn/status
     */
    @GetMapping("/learn/status")
    public ResponseEntity<Map<String, Object>> getLearningLoopStatus() {
        boolean isRunning = continuousLearningLoop.isRunning();
        return ResponseEntity.ok(Map.of(
                "running", isRunning,
                "message", isRunning ? "Learning loop is running" : "Learning loop is idle"
        ));
    }

    /**
     * Analyze the latest backtest results and get improvement suggestions.
     * POST /api/backtest/analyze
     */
    @PostMapping("/analyze")
    public ResponseEntity<com.fgiaquinta.optionsquant.service.BacktestAnalyzer.AnalysisReport> analyze() {
        log.info(">>> POST /api/backtest/analyze");

        com.fgiaquinta.optionsquant.service.BacktestAnalyzer.AnalysisReport analysis = 
                backtestAnalyzer.analyzeTrades(java.nio.file.Path.of("backtest/trades.csv"));

        log.info("<<< POST /api/backtest/analyze - {} suggestions", analysis.getSuggestionCount());
        return ResponseEntity.ok(analysis);
    }

    /**
     * Get ticker information from CSV.
     * GET /api/backtest/tickers?sector=Technology&minMarketCap=50
     */
    @GetMapping("/tickers")
    public ResponseEntity<List<com.fgiaquinta.optionsquant.domain.TickerInfo>> getTickers(
            @RequestParam(required = false) String sector,
            @RequestParam(required = false) Long minMarketCap,
            @RequestParam(required = false) Long maxMarketCap,
            @RequestParam(defaultValue = "false") boolean highQuality,
            @RequestParam(defaultValue = "false") boolean highGrowth
    ) {
        List<com.fgiaquinta.optionsquant.domain.TickerInfo> tickers;

        if (highQuality) {
            tickers = tickerService.getHighQualityTickers();
        } else if (highGrowth) {
            tickers = tickerService.getHighGrowthTickers(15.0);
        } else if (sector != null) {
            tickers = tickerService.getBySector(sector);
        } else if (minMarketCap != null && maxMarketCap != null) {
            tickers = tickerService.getByMarketCapRange(minMarketCap, maxMarketCap);
        } else {
            tickers = tickerService.getAllTickers();
        }

        return ResponseEntity.ok(tickers);
    }

    /**
     * Get priority tickers (top 10 based on fundamentals).
     * GET /api/backtest/priority-tickers
     */
    @GetMapping("/priority-tickers")
    public ResponseEntity<List<String>> getPriorityTickers(
            @RequestParam(defaultValue = "false") boolean refresh
    ) {
        List<String> tickers = refresh 
                ? newsFilterService.refreshPriorityTickers()
                : newsFilterService.getPriorityTickers();
        return ResponseEntity.ok(tickers);
    }

    /**
     * Get detailed score for a specific ticker.
     * GET /api/backtest/ticker-score/{ticker}
     */
    @GetMapping("/ticker-score/{ticker}")
    public ResponseEntity<Map<String, Object>> getTickerScore(@PathVariable String ticker) {
        return ResponseEntity.ok(newsFilterService.getTickerScore(ticker));
    }

    /**
     * Run strategy screener on all tickers.
     * POST /api/backtest/screen?count=10
     */
    @PostMapping("/screen")
    public ResponseEntity<com.fgiaquinta.optionsquant.service.StrategyScreenerService.ScreeningResult> screen(
            @RequestParam(defaultValue = "10") int count
    ) {
        log.info(">>> POST /api/backtest/screen count={}", count);
        
        // TODO: Load candle data for all tickers
        // For now, return empty - this needs candle data integration
        var result = new com.fgiaquinta.optionsquant.service.StrategyScreenerService.ScreeningResult(
                List.of(),
                0,
                "Screener requires candle data - use live trading mode"
        );
        
        return ResponseEntity.ok(result);
    }
}
