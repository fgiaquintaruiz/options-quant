package com.fgiaquinta.optionsquant.backtest.batch;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * TDD — tests written before implementation exists.
 *
 * Uses an in-memory SQLite DataSource to isolate from the real candles.db.
 * Overrides the EXPORT_DIR constant at field level for temp-dir isolation.
 */
class BacktestExportServiceTest {

    @TempDir
    Path tempDir;

    private HikariDataSource dataSource;
    private JdbcTemplate jdbc;
    private BacktestExportService service;

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    @BeforeEach
    void setUp() throws Exception {
        dataSource = inMemorySqliteDs();
        jdbc = new JdbcTemplate(dataSource);
        createSchema();
        service = new BacktestExportService(dataSource);
        overrideExportDir(service, tempDir.resolve("backtest-exports"));
    }

    @AfterEach
    void tearDown() {
        dataSource.close();
    }

    // -------------------------------------------------------------------------
    // T1 — exportRunToCSV creates the export directory
    // -------------------------------------------------------------------------

    @Test
    void exportRunToCSV_createsExportDirectory() throws Exception {
        String runId = "2024-01-15_10-00";
        insertProgress(runId, "AAPL");

        service.exportRunToCSV(runId);

        Path exportDir = tempDir.resolve("backtest-exports");
        assertThat(exportDir).isDirectory();
    }

    // -------------------------------------------------------------------------
    // T2 — trades CSV is created with correct filename
    // -------------------------------------------------------------------------

    @Test
    void exportRunToCSV_createsTradeCsvWithCorrectName() throws Exception {
        String runId = "2024-01-15_10-00";
        insertProgress(runId, "AAPL");
        insertTrade(runId, "AAPL", "C1SqueezeCall", "CALL", 150.0, 155.0, 5.0, 1, "squeeze");

        service.exportRunToCSV(runId);

        Path tradesFile = tempDir.resolve("backtest-exports")
                .resolve("backtest-trades-" + runId + ".csv");
        assertThat(tradesFile).exists();
    }

    // -------------------------------------------------------------------------
    // T3 — trades CSV starts with BOM and has correct headers
    // -------------------------------------------------------------------------

    @Test
    void exportRunToCSV_tradesCsvHasBomAndHeaders() throws Exception {
        String runId = "2024-01-15_10-00";
        insertProgress(runId, "AAPL");
        insertTrade(runId, "AAPL", "C1SqueezeCall", "CALL", 150.0, 155.0, 5.0, 1, "squeeze");

        service.exportRunToCSV(runId);

        Path tradesFile = tempDir.resolve("backtest-exports")
                .resolve("backtest-trades-" + runId + ".csv");
        String content = Files.readString(tradesFile, StandardCharsets.UTF_8);

        // BOM must be first character
        assertThat(content).startsWith("\uFEFF");
        // Header row must be present
        assertThat(content).contains("ticker,strategy,timeframe,signal_date,signal_type,entry_price,exit_price,pnl,win,pattern");
    }

    // -------------------------------------------------------------------------
    // T4 — trades CSV contains data rows for the run
    // -------------------------------------------------------------------------

    @Test
    void exportRunToCSV_tradesCsvContainsDataRows() throws Exception {
        String runId = "2024-01-15_10-00";
        insertProgress(runId, "AAPL");
        insertTrade(runId, "AAPL", "C1SqueezeCall", "CALL", 150.0, 155.0, 5.0, 1, "squeeze");
        insertTrade(runId, "MSFT", "P1SqueezePut", "PUT", 300.0, 295.0, -5.0, 0, "reversal");

        service.exportRunToCSV(runId);

        Path tradesFile = tempDir.resolve("backtest-exports")
                .resolve("backtest-trades-" + runId + ".csv");
        String content = Files.readString(tradesFile, StandardCharsets.UTF_8);

        assertThat(content).contains("AAPL");
        assertThat(content).contains("MSFT");
        assertThat(content).contains("C1SqueezeCall");
        assertThat(content).contains("P1SqueezePut");
    }

    // -------------------------------------------------------------------------
    // T5 — summary CSV is created with correct filename
    // -------------------------------------------------------------------------

    @Test
    void exportRunToCSV_createsSummaryCsvWithCorrectName() throws Exception {
        String runId = "2024-01-15_10-00";
        insertProgress(runId, "AAPL");
        insertTrade(runId, "AAPL", "C1SqueezeCall", "CALL", 150.0, 155.0, 5.0, 1, "squeeze");

        service.exportRunToCSV(runId);

        Path summaryFile = tempDir.resolve("backtest-exports")
                .resolve("backtest-summary-" + runId + ".csv");
        assertThat(summaryFile).exists();
    }

    // -------------------------------------------------------------------------
    // T6 — summary CSV starts with BOM and contains === SUMMARY === section
    // -------------------------------------------------------------------------

    @Test
    void exportRunToCSV_summaryCsvHasBomAndSummarySection() throws Exception {
        String runId = "2024-01-15_10-00";
        insertProgress(runId, "AAPL");
        insertTrade(runId, "AAPL", "C1SqueezeCall", "CALL", 150.0, 155.0, 5.0, 1, "squeeze");
        insertTrade(runId, "MSFT", "P1SqueezePut", "PUT", 300.0, 295.0, -5.0, 0, "reversal");

        service.exportRunToCSV(runId);

        Path summaryFile = tempDir.resolve("backtest-exports")
                .resolve("backtest-summary-" + runId + ".csv");
        String content = Files.readString(summaryFile, StandardCharsets.UTF_8);

        assertThat(content).startsWith("\uFEFF");
        assertThat(content).contains("=== SUMMARY ===");
        assertThat(content).contains("total_trades,wins,losses,win_rate,total_pnl,avg_pnl");
    }

    // -------------------------------------------------------------------------
    // T7 — summary CSV contains all required sections
    // -------------------------------------------------------------------------

    @Test
    void exportRunToCSV_summaryCsvContainsAllSections() throws Exception {
        String runId = "2024-01-15_10-00";
        insertProgress(runId, "AAPL");
        insertTrade(runId, "AAPL", "C1SqueezeCall", "CALL", 150.0, 155.0, 5.0, 1, "squeeze");
        insertTrade(runId, "MSFT", "P1SqueezePut", "PUT", 300.0, 295.0, -5.0, 0, "reversal");

        service.exportRunToCSV(runId);

        Path summaryFile = tempDir.resolve("backtest-exports")
                .resolve("backtest-summary-" + runId + ".csv");
        String content = Files.readString(summaryFile, StandardCharsets.UTF_8);

        assertThat(content).contains("=== SUMMARY ===");
        assertThat(content).contains("=== BY STRATEGY ===");
        assertThat(content).contains("=== CALL VS PUT ===");
        assertThat(content).contains("=== LOSSES BY PATTERN ===");
        assertThat(content).contains("=== LOSSES BY TICKER ===");
        assertThat(content).contains("=== WORST 20 TRADES ===");
    }

    // -------------------------------------------------------------------------
    // T8 — export is silent on failure (bad datasource)
    // -------------------------------------------------------------------------

    @Test
    void exportRunToCSV_doesNotPropagateExceptionOnFailure() {
        // Create a service with a closed datasource to force JDBC failure
        HikariDataSource brokenDs = inMemorySqliteDs();
        brokenDs.close(); // force all connections to fail
        BacktestExportService brokenService = new BacktestExportService(brokenDs);

        // Must NOT throw — export failure must be logged and swallowed
        assertThatCode(() -> brokenService.exportRunToCSV("any-run-id"))
                .doesNotThrowAnyException();
    }

    // -------------------------------------------------------------------------
    // T9 — empty run (no trades) still creates files without crashing
    // -------------------------------------------------------------------------

    @Test
    void exportRunToCSV_withNoTrades_createsFilesGracefully() throws Exception {
        String runId = "2024-01-15_10-00";
        insertProgress(runId, "AAPL");
        // No trades inserted

        service.exportRunToCSV(runId);

        Path tradesFile = tempDir.resolve("backtest-exports")
                .resolve("backtest-trades-" + runId + ".csv");
        Path summaryFile = tempDir.resolve("backtest-exports")
                .resolve("backtest-summary-" + runId + ".csv");

        assertThat(tradesFile).exists();
        assertThat(summaryFile).exists();
    }

    // -------------------------------------------------------------------------
    // T10 — summary aggregates correctly: 1 win + 1 loss = 50% win rate
    // -------------------------------------------------------------------------

    @Test
    void exportRunToCSV_summaryAggregatesCorrectly() throws Exception {
        String runId = "2024-01-15_10-00";
        insertProgress(runId, "AAPL");
        insertTrade(runId, "AAPL", "C1SqueezeCall", "CALL", 150.0, 155.0, 5.0, 1, "squeeze");
        insertTrade(runId, "AAPL", "P1SqueezePut", "PUT", 300.0, 295.0, -5.0, 0, "reversal");

        service.exportRunToCSV(runId);

        Path summaryFile = tempDir.resolve("backtest-exports")
                .resolve("backtest-summary-" + runId + ".csv");
        String content = Files.readString(summaryFile, StandardCharsets.UTF_8);

        // total_trades=2, wins=1, losses=1, win_rate=0.50
        assertThat(content).contains("2,1,1,0.50");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static HikariDataSource inMemorySqliteDs() {
        HikariConfig cfg = new HikariConfig();
        cfg.setDriverClassName("org.sqlite.JDBC");
        cfg.setJdbcUrl("jdbc:sqlite:file:exporttestdb?mode=memory&cache=shared");
        cfg.setMaximumPoolSize(1);
        cfg.setMinimumIdle(1);
        cfg.setPoolName("test-export-sqlite");
        return new HikariDataSource(cfg);
    }

    private void createSchema() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS backtest_trades (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    run_id TEXT NOT NULL,
                    ticker TEXT NOT NULL,
                    strategy TEXT NOT NULL,
                    timeframe TEXT NOT NULL,
                    signal_date TEXT NOT NULL,
                    signal_type TEXT NOT NULL,
                    entry_price REAL NOT NULL,
                    exit_price REAL,
                    pnl REAL,
                    win INTEGER,
                    pattern TEXT,
                    created_at INTEGER NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS backtest_progress (
                    run_id TEXT NOT NULL,
                    ticker TEXT NOT NULL,
                    status TEXT NOT NULL,
                    trade_count INTEGER DEFAULT 0,
                    pnl REAL DEFAULT 0,
                    updated_at INTEGER NOT NULL,
                    PRIMARY KEY (run_id, ticker)
                )
                """);
    }

    private void insertProgress(String runId, String ticker) {
        jdbc.update(
                "INSERT INTO backtest_progress (run_id, ticker, status, trade_count, pnl, updated_at) VALUES (?, ?, 'COMPLETE', 0, 0, ?)",
                runId, ticker, System.currentTimeMillis() / 1000L
        );
    }

    private void insertTrade(String runId, String ticker, String strategy, String signalType,
                             double entryPrice, double exitPrice, double pnl, int win, String pattern) {
        long now = System.currentTimeMillis() / 1000L;
        jdbc.update(
                "INSERT INTO backtest_trades " +
                "(run_id, ticker, strategy, timeframe, signal_date, signal_type, entry_price, exit_price, pnl, win, pattern, created_at) " +
                "VALUES (?, ?, ?, 'MIN_15', '2024-01-15T10:00:00Z', ?, ?, ?, ?, ?, ?, ?)",
                runId, ticker, strategy, signalType, entryPrice, exitPrice, pnl, win, pattern, now
        );
    }

    /**
     * Overrides the EXPORT_DIR field via reflection so tests write to a temp directory
     * instead of the real "data/backtest-exports" path.
     */
    private static void overrideExportDir(BacktestExportService svc, Path dir) throws Exception {
        Field field = BacktestExportService.class.getDeclaredField("exportDir");
        field.setAccessible(true);
        field.set(svc, dir);
    }
}
