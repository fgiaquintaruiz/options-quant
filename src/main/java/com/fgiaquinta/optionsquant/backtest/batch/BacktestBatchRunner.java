package com.fgiaquinta.optionsquant.backtest.batch;

import com.fgiaquinta.optionsquant.backtest.domain.BacktestConfig;
import com.fgiaquinta.optionsquant.backtest.domain.BacktestReport;
import com.fgiaquinta.optionsquant.backtest.domain.TradeRecord;
import com.fgiaquinta.optionsquant.backtest.engine.BacktestEngine;
import com.fgiaquinta.optionsquant.candle.backfill.BackfillStatus;
import com.fgiaquinta.optionsquant.domain.TimeFrame;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Scanner;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * CLI runner for the {@code --backtest-all} mode.
 *
 * <p>When activated, queries SQLite for all tickers with valid candles
 * (status COMPLETE_TWS or COMPLETE_YFINANCE), runs all 12 strategies
 * against them via {@link BacktestEngine}, then writes a CSV with
 * per-(ticker, strategy) aggregated results.
 *
 * <p>Phase 2 adds SQLite persistence (backtest_trades + backtest_progress) and
 * resume logic: if a previous run was interrupted, the user is prompted to
 * resume from where it left off.
 *
 * <p>Order 3 — runs after backfill (Order 2) and schema init (Order 1).
 */
@Slf4j
@Component
@Order(3)
@ConditionalOnProperty(name = "candles.store", havingValue = "sqlite", matchIfMissing = true)
public class BacktestBatchRunner implements ApplicationRunner {

    private static final DateTimeFormatter RUN_ID_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm");

    /** Execution timeframe — defined once so it's consistent across config + persistence. */
    private static final TimeFrame EXECUTION_TIMEFRAME = TimeFrame.MIN_15;

    /**
     * Statuses that indicate a ticker has real candle data worth backtesting.
     */
    private static final String VALID_STATUS_SQL = """
            SELECT DISTINCT ticker
            FROM download_progress
            WHERE status IN (?, ?)
            ORDER BY ticker
            """;

    private final BacktestEngine backtestEngine;
    private final BacktestCsvWriter csvWriter;
    private final JdbcTemplate readJdbc;
    private final BacktestPersistenceService persistenceService;
    private final BacktestExportService exportService;
    private final Supplier<String> lineReader;

    /**
     * Production constructor — wired by Spring.
     */
    @Autowired
    public BacktestBatchRunner(
            BacktestEngine backtestEngine,
            BacktestCsvWriter csvWriter,
            @Qualifier("candlesReadDs") DataSource readDs,
            BacktestPersistenceService persistenceService,
            BacktestExportService exportService) {
        this.backtestEngine = backtestEngine;
        this.csvWriter = csvWriter;
        this.readJdbc = new JdbcTemplate(readDs);
        this.persistenceService = persistenceService;
        this.exportService = exportService;
        this.lineReader = () -> new Scanner(System.in).nextLine();
    }

    /**
     * Test-friendly constructor — receives pre-built collaborators.
     */
    BacktestBatchRunner(
            BacktestEngine backtestEngine,
            BacktestCsvWriter csvWriter,
            JdbcTemplate readJdbc,
            BacktestPersistenceService persistenceService,
            Supplier<String> lineReader) {
        this.backtestEngine = backtestEngine;
        this.csvWriter = csvWriter;
        this.readJdbc = readJdbc;
        this.persistenceService = persistenceService;
        this.exportService = null;
        this.lineReader = lineReader;
    }

    /**
     * Legacy test constructor (no persistence) — kept for backward compatibility
     * with existing BacktestBatchRunnerTest that doesn't need persistence.
     */
    BacktestBatchRunner(
            BacktestEngine backtestEngine,
            BacktestCsvWriter csvWriter,
            JdbcTemplate readJdbc) {
        this.backtestEngine = backtestEngine;
        this.csvWriter = csvWriter;
        this.readJdbc = readJdbc;
        this.persistenceService = null;
        this.exportService = null;
        this.lineReader = () -> "n";
    }

    /**
     * Wraps {@link System#exit} so tests can subclass and intercept without
     * actually terminating the JVM.
     */
    protected void exit(int code) {
        System.exit(code);
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!args.containsOption("backtest-all")) {
            log.debug("--backtest-all flag not present. Batch backtest skipped.");
            return;
        }

        // --backtest-fresh / --backtest-fresh-force: wipe DB before running
        if (persistenceService != null
                && (args.containsOption("backtest-fresh") || args.containsOption("backtest-fresh-force"))) {
            boolean force = args.containsOption("backtest-fresh-force");
            log.info("[backtest] --backtest-fresh{} detected", force ? "-force" : "");

            if (!force) {
                String answer = readConfirmation();
                if (answer == null) {
                    // No interactive console and no force flag
                    log.error("[backtest] No interactive console. Use --backtest-fresh-force for non-interactive environments.");
                    exit(1);
                    return;
                }
                if (!"yes".equals(answer)) {
                    log.info("[backtest] Aborted by user.");
                    exit(1);
                    return;
                }
            }

            log.info("[backtest] Cleaning previous data...");
            try {
                BacktestPersistenceService.DeleteResult result = persistenceService.deleteAllData();
                log.info("[backtest] Deleted {} trades and {} progress entries",
                        result.trades(), result.progress());
            } catch (Exception e) {
                log.error("[backtest] Failed to delete backtest data: {} — aborting.", e.getMessage(), e);
                exit(1);
                return;
            }
        }

        log.info("[backtest] === Batch backtest START (--backtest-all) ===");
        Instant startTime = Instant.now();

        // Phase 2: initialize schema
        if (persistenceService != null) {
            persistenceService.initSchema();
        }

        List<String> allTickers = resolveValidTickers();

        if (allTickers.isEmpty()) {
            log.warn("[backtest] No tickers with valid candles found. Skipping.");
            csvWriter.write(List.of());
            return;
        }

        // Phase 2: determine run_id and filtered ticker list (resume logic)
        String runId;
        List<String> tickers;

        if (persistenceService != null) {
            Optional<String> incompleteRun = persistenceService.findIncompleteRun(allTickers.size());

            if (args.containsOption("backtest-resume")) {
                // --backtest-resume flag: resume without prompting stdin
                if (incompleteRun.isPresent()) {
                    String existingRunId = incompleteRun.get();
                    Set<String> completed = persistenceService.getCompletedTickers(existingRunId);
                    runId = existingRunId;
                    tickers = allTickers.stream()
                            .filter(t -> !completed.contains(t))
                            .collect(Collectors.toList());
                    log.info("[backtest] [--backtest-resume] Resuming run {} — {} tickers remaining",
                            runId, tickers.size());
                } else {
                    runId = newRunId();
                    tickers = new ArrayList<>(allTickers);
                    log.info("[backtest] [--backtest-resume] No incomplete run found — starting fresh {} — {} tickers",
                            runId, tickers.size());
                }
            } else if (args.containsOption("backtest-fresh") || args.containsOption("backtest-fresh-force")) {
                // --backtest-fresh / --backtest-fresh-force: DB was already wiped above; always start a new run
                runId = newRunId();
                tickers = new ArrayList<>(allTickers);
                log.info("[backtest] [--backtest-fresh] Starting fresh run {} — {} tickers", runId, tickers.size());
            } else if (incompleteRun.isPresent()) {
                // No CLI flag — try stdin, fall back to fresh if stdin is unavailable
                String existingRunId = incompleteRun.get();
                log.info("[backtest] Found incomplete run {} ({} tickers done). Resume? (y/n)",
                        existingRunId, allTickers.size());
                boolean resume = false;
                try {
                    String answer = lineReader.get();
                    resume = "y".equalsIgnoreCase(answer.trim());
                } catch (NoSuchElementException | IllegalStateException e) {
                    log.warn("[backtest] stdin not available — defaulting to fresh run");
                }
                if (resume) {
                    Set<String> completed = persistenceService.getCompletedTickers(existingRunId);
                    runId = existingRunId;
                    tickers = allTickers.stream()
                            .filter(t -> !completed.contains(t))
                            .collect(Collectors.toList());
                    log.info("[backtest] Resuming run {} — {} tickers remaining", runId, tickers.size());
                } else {
                    runId = newRunId();
                    tickers = new ArrayList<>(allTickers);
                    log.info("[backtest] Starting fresh run {} — {} tickers", runId, tickers.size());
                }
            } else {
                runId = newRunId();
                tickers = new ArrayList<>(allTickers);
                log.info("[backtest] New run {} — {} tickers", runId, tickers.size());
            }
        } else {
            runId = newRunId();
            tickers = new ArrayList<>(allTickers);
        }

        log.info("[backtest] {} tickers with valid candles — running 12 strategies", tickers.size());

        BacktestConfig config = buildConfig(tickers, runId);
        List<BacktestBatchResult> results = new ArrayList<>();
        BacktestReport report = null;

        long batchStart = System.currentTimeMillis();
        try {
            report = backtestEngine.run(config);
            results = aggregateResults(report, EXECUTION_TIMEFRAME);
            log.info("[backtest] DONE — {} tickers × 12 strategies — {} result rows",
                    tickers.size(), results.size());
        } catch (Exception e) {
            log.error("[backtest] Engine failed: {} — writing empty CSV", e.getMessage(), e);
        }

        long elapsed = System.currentTimeMillis() - batchStart;
        long minutes = elapsed / 60_000;
        long seconds = (elapsed % 60_000) / 1_000;
        log.info("[backtest] DONE — {} tickers, {} trades, elapsed: {}m {}s",
                tickers.size(), report != null ? report.totalTrades() : 0, minutes, seconds);
        csvWriter.write(results);

        Instant endTime = Instant.now();
        Duration runtime = Duration.between(startTime, endTime);
        RunMetrics metrics = queryRunMetrics(runId);

        double runtimeSecs = runtime.toSeconds() > 0 ? runtime.toSeconds() : 1;
        double tradesPerSec = metrics.totalTradesLong() / runtimeSecs;
        double tickersPerMin = tickers.size() / (runtimeSecs / 60.0);

        log.info("[backtest] === Batch backtest END ===");
        log.info("[backtest] Runtime: {}", formatDuration(runtime));
        log.info("[backtest] Tickers processed: {}", tickers.size());
        log.info("[backtest] Trades generated: {}", metrics.totalTrades());
        log.info("[backtest] Throughput: {} trades/sec, {} tickers/min",
                String.format("%.2f", tradesPerSec),
                String.format("%.2f", tickersPerMin));
        log.info("[backtest] Run ID: {}", runId);
        log.info("[backtest] Total PnL: ${}", metrics.totalPnl());

        if (exportService != null) {
            exportService.exportRunToCSV(runId);
        }
        exit(0);
    }

    // -------------------------------------------------------------------------
    // Runtime metrics
    // -------------------------------------------------------------------------

    /**
     * Formats a {@link Duration} as "Xm Ys" — always whole minutes and seconds,
     * never decimal minutes (e.g. "0m 45s", not "0.75m").
     */
    static String formatDuration(Duration d) {
        return d.toMinutesPart() + "m " + d.toSecondsPart() + "s";
    }

    /**
     * Queries backtest_trades for aggregate metrics of the given run.
     * Each query is wrapped in a separate try-catch so a failure in one
     * does not suppress the other — both degrade gracefully to "N/A".
     */
    RunMetrics queryRunMetrics(String runId) {
        String totalTrades;
        long totalTradesLong = 0;
        try {
            Long count = readJdbc.queryForObject(
                    "SELECT COUNT(*) FROM backtest_trades WHERE run_id = ?",
                    Long.class, runId);
            totalTradesLong = count != null ? count : 0L;
            totalTrades = String.valueOf(totalTradesLong);
        } catch (Exception e) {
            log.warn("[backtest] Could not query totalTrades for run {}: {}", runId, e.getMessage());
            totalTrades = "N/A";
        }

        String totalPnl;
        try {
            Double pnl = readJdbc.queryForObject(
                    "SELECT SUM(pnl) FROM backtest_trades WHERE run_id = ?",
                    Double.class, runId);
            totalPnl = String.format("%.2f", pnl != null ? pnl : 0.0);
        } catch (Exception e) {
            log.warn("[backtest] Could not query totalPnl for run {}: {}", runId, e.getMessage());
            totalPnl = "N/A";
        }

        return new RunMetrics(totalTrades, totalTradesLong, totalPnl);
    }

    /**
     * Aggregate metrics for a completed backtest run.
     *
     * @param totalTrades    string representation ("N/A" on query failure)
     * @param totalTradesLong numeric value for throughput calculation (0 on failure)
     * @param totalPnl       formatted PnL string ("N/A" on query failure)
     */
    record RunMetrics(String totalTrades, long totalTradesLong, String totalPnl) {}

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Prompts the user for confirmation before wiping backtest data.
     *
     * <p>Tries {@link System#console()} first (interactive terminal); falls back to
     * {@link #lineReader} for test injection. Returns {@code null} when no interactive
     * channel is available, signalling the caller to abort with a clear message.
     *
     * @return the trimmed answer string, or {@code null} if no console is available
     */
    private String readConfirmation() {
        System.out.println("About to DELETE all backtest data. Type 'yes' to continue:");
        java.io.Console console = System.console();
        if (console != null) {
            String line = console.readLine();
            return line != null ? line.trim() : "";
        }
        try {
            return lineReader.get().trim();
        } catch (Exception e) {
            return null;
        }
    }

    private String newRunId() {
        return LocalDateTime.now().format(RUN_ID_FMT);
    }

    /**
     * Queries download_progress for tickers that have at least one row with
     * COMPLETE_TWS or COMPLETE_YFINANCE status — meaning real candle data exists.
     */
    private List<String> resolveValidTickers() {
        try {
            return readJdbc.queryForList(
                    VALID_STATUS_SQL,
                    String.class,
                    new Object[]{
                            BackfillStatus.COMPLETE_TWS.name(),
                            BackfillStatus.COMPLETE_YFINANCE.name()
                    }
            );
        } catch (Exception e) {
            log.error("[backtest] Failed to query valid tickers from download_progress: {}", e.getMessage(), e);
            return List.of();
        }
    }

    /**
     * Builds a BacktestConfig covering the full historical range (2018–today).
     * Registers a per-ticker callback so trades are persisted immediately after
     * each ticker completes, rather than in a single batch at the end.
     * The callback is safe to call from multiple worker threads concurrently.
     */
    private BacktestConfig buildConfig(List<String> tickers, String runId) {
        LocalDate from = LocalDate.of(2018, 1, 1);
        LocalDate to = LocalDate.now(ZoneOffset.UTC);
        if (persistenceService != null) {
            return BacktestConfig.withCallback(tickers, from, to,
                    (ticker, trades) -> {
                        persistenceService.persistTickerResult(
                                runId, ticker, EXECUTION_TIMEFRAME.name(), trades);
                        log.info("[backtest] Persisted {} ({} trades)", ticker, trades.size());
                    });
        }
        return BacktestConfig.defaults(tickers, from, to);
    }

    /**
     * Aggregates a BacktestReport into per-(ticker, strategy) rows.
     *
     * <p>Each TradeRecord has ticker + strategy. We group trades by (ticker, strategy),
     * count signals (trades = signals in backtest mode), split by direction (CALL=long, PUT=short),
     * compute win rate, and find first/last signal dates.
     *
     * @param report        the engine output
     * @param executionTf   the timeframe used for this run (recorded in the result row)
     */
    private List<BacktestBatchResult> aggregateResults(BacktestReport report, TimeFrame executionTf) {
        // Key: "ticker|strategy"
        Map<String, ResultAccumulator> accumulators = new HashMap<>();

        for (TradeRecord trade : report.trades()) {
            String key = trade.ticker() + "|" + trade.strategy();
            accumulators.computeIfAbsent(key, k -> new ResultAccumulator(
                    trade.ticker(), trade.strategy(), executionTf.name()
            )).add(trade);
        }

        List<BacktestBatchResult> results = new ArrayList<>();
        for (ResultAccumulator acc : accumulators.values()) {
            results.add(acc.toResult());
        }
        results.sort((a, b) -> {
            int c = a.ticker().compareTo(b.ticker());
            return c != 0 ? c : a.strategy().compareTo(b.strategy());
        });

        return results;
    }

    // -------------------------------------------------------------------------
    // Inner accumulator (package-private for testability)
    // -------------------------------------------------------------------------

    static final class ResultAccumulator {
        final String ticker;
        final String strategy;
        final String timeframe;

        int totalSignals = 0;
        int longSignals = 0;
        int shortSignals = 0;
        int wins = 0;
        LocalDate firstSignalDate = null;
        LocalDate lastSignalDate = null;

        ResultAccumulator(String ticker, String strategy, String timeframe) {
            this.ticker = ticker;
            this.strategy = strategy;
            this.timeframe = timeframe;
        }

        void add(TradeRecord trade) {
            totalSignals++;
            if (trade.isWin()) wins++;

            // CALL = long options, PUT = short direction
            String dir = trade.direction() != null ? trade.direction().toUpperCase() : "";
            if (dir.contains("CALL") || dir.equals("LONG")) {
                longSignals++;
            } else {
                shortSignals++;
            }

            LocalDate tradeDate = trade.entryTime() != null
                    ? trade.entryTime().withZoneSameInstant(ZoneOffset.UTC).toLocalDate()
                    : null;
            if (tradeDate != null) {
                if (firstSignalDate == null || tradeDate.isBefore(firstSignalDate)) {
                    firstSignalDate = tradeDate;
                }
                if (lastSignalDate == null || tradeDate.isAfter(lastSignalDate)) {
                    lastSignalDate = tradeDate;
                }
            }
        }

        BacktestBatchResult toResult() {
            double winRate = totalSignals == 0 ? 0.0 : (double) wins / totalSignals;
            return new BacktestBatchResult(
                    ticker, strategy, timeframe,
                    totalSignals, longSignals, shortSignals,
                    winRate, firstSignalDate, lastSignalDate
            );
        }
    }
}
