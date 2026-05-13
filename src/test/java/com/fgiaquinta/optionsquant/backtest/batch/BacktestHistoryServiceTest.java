package com.fgiaquinta.optionsquant.backtest.batch;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.fgiaquinta.optionsquant.backtest.dto.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TDD — tests written BEFORE BacktestHistoryService implementation.
 * Uses in-memory SQLite to verify SQL queries and result mapping.
 */
class BacktestHistoryServiceTest {

    private HikariDataSource dataSource;
    private BacktestHistoryService service;
    private JdbcTemplate jdbc;

    // -------------------------------------------------------------------------
    // Setup
    // -------------------------------------------------------------------------

    private java.io.File dbFile;

    private HikariDataSource inMemorySqliteDs() throws java.io.IOException {
        // Use a unique temp file per test to guarantee complete isolation.
        // SQLite named in-memory DBs with cache=shared are shared across connections
        // within the same JVM even with different names, causing cross-test contamination.
        dbFile = java.io.File.createTempFile("history_test_", ".db");
        dbFile.deleteOnExit();
        HikariConfig cfg = new HikariConfig();
        cfg.setDriverClassName("org.sqlite.JDBC");
        cfg.setJdbcUrl("jdbc:sqlite:" + dbFile.getAbsolutePath());
        cfg.setMaximumPoolSize(1);
        cfg.setMinimumIdle(1);
        cfg.setPoolName("test-history-sqlite");
        return new HikariDataSource(cfg);
    }

    @BeforeEach
    void setUp() throws java.io.IOException {
        dataSource = inMemorySqliteDs();
        jdbc = new JdbcTemplate(dataSource);
        createSchema();
        service = new BacktestHistoryService(dataSource);
    }

    @AfterEach
    void tearDown() {
        dataSource.close();
        if (dbFile != null) {
            dbFile.delete();
        }
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

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void insertProgress(String runId, String ticker, String status) {
        jdbc.update(
                "INSERT INTO backtest_progress (run_id, ticker, status, trade_count, pnl, updated_at) VALUES (?, ?, ?, 0, 0, ?)",
                runId, ticker, status, System.currentTimeMillis() / 1000L
        );
    }

    private void insertTrade(String runId, String ticker, String strategy, String signalType,
                              String signalDate, double pnl, int win, String pattern) {
        long now = System.currentTimeMillis() / 1000L;
        jdbc.update(
                "INSERT INTO backtest_trades " +
                "(run_id, ticker, strategy, timeframe, signal_date, signal_type, entry_price, exit_price, pnl, win, pattern, created_at) " +
                "VALUES (?, ?, ?, 'MIN_15', ?, ?, 100.0, 105.0, ?, ?, ?, ?)",
                runId, ticker, strategy, signalDate, signalType, pnl, win, pattern, now
        );
    }

    // -------------------------------------------------------------------------
    // T1 — listRuns_returnsAllRunsOrderedByDateDesc
    // -------------------------------------------------------------------------

    @Test
    void listRuns_returnsAllRunsOrderedByDateDesc() {
        insertProgress("2026-01-01_run", "AAPL", "COMPLETE");
        insertProgress("2026-01-01_run", "MSFT", "COMPLETE");
        insertProgress("2026-02-01_run", "AAPL", "COMPLETE");

        insertTrade("2026-01-01_run", "AAPL", "MOMENTUM", "CALL", "2026-01-01T10:00:00Z", 5.0, 1, null);
        insertTrade("2026-01-01_run", "MSFT", "SQUEEZE", "PUT", "2026-01-02T11:00:00Z", -3.0, 0, "DOJI");
        insertTrade("2026-02-01_run", "AAPL", "MOMENTUM", "CALL", "2026-02-01T09:30:00Z", 7.0, 1, null);

        List<RunSummaryDto> runs = service.listRuns();

        assertThat(runs).hasSize(2);
        // Ordered DESC by run_id — "2026-02-01_run" > "2026-01-01_run"
        assertThat(runs.get(0).runId()).isEqualTo("2026-02-01_run");
        assertThat(runs.get(1).runId()).isEqualTo("2026-01-01_run");

        RunSummaryDto first = runs.get(0);
        assertThat(first.totalTickers()).isEqualTo(1);
        assertThat(first.completedTickers()).isEqualTo(1);
        assertThat(first.totalTrades()).isEqualTo(1);

        RunSummaryDto second = runs.get(1);
        assertThat(second.totalTickers()).isEqualTo(2);
        assertThat(second.completedTickers()).isEqualTo(2);
        assertThat(second.totalTrades()).isEqualTo(2);
    }

    // -------------------------------------------------------------------------
    // T2 — getRunSummary_unknownRunId_returnsEmptyBreakdowns
    // -------------------------------------------------------------------------

    @Test
    void getRunSummary_unknownRunId_returnsEmptyBreakdowns() {
        RunDetailDto detail = service.getRunSummary("nonexistent-run");

        assertThat(detail.runId()).isEqualTo("nonexistent-run");
        assertThat(detail.totalTrades()).isZero();
        assertThat(detail.totalPnl()).isZero();
        assertThat(detail.byStrategy()).isEmpty();
        assertThat(detail.bySignalType()).isEmpty();
    }

    // -------------------------------------------------------------------------
    // T3 — getRunSummary_returnsCorrectStrategyBreakdown
    // -------------------------------------------------------------------------

    @Test
    void getRunSummary_returnsCorrectStrategyBreakdown() {
        String runId = "2026-03-01_run";
        insertTrade(runId, "AAPL", "MOMENTUM", "CALL", "2026-03-01T10:00:00Z", 10.0, 1, null);
        insertTrade(runId, "AAPL", "MOMENTUM", "CALL", "2026-03-01T11:00:00Z", 8.0, 1, null);
        insertTrade(runId, "MSFT", "MOMENTUM", "PUT", "2026-03-01T12:00:00Z", -5.0, 0, "HAMMER");
        insertTrade(runId, "GOOG", "SQUEEZE", "CALL", "2026-03-01T13:00:00Z", 3.0, 1, null);

        RunDetailDto detail = service.getRunSummary(runId);

        assertThat(detail.totalTrades()).isEqualTo(4);
        assertThat(detail.byStrategy()).hasSize(2);

        StrategyBreakdownDto momentum = detail.byStrategy().stream()
                .filter(s -> s.strategy().equals("MOMENTUM"))
                .findFirst()
                .orElseThrow();
        assertThat(momentum.trades()).isEqualTo(3);
        // win_rate = 2/3 * 100 = 66.7
        assertThat(momentum.winRate()).isEqualTo(66.7);

        StrategyBreakdownDto squeeze = detail.byStrategy().stream()
                .filter(s -> s.strategy().equals("SQUEEZE"))
                .findFirst()
                .orElseThrow();
        assertThat(squeeze.trades()).isEqualTo(1);
        assertThat(squeeze.winRate()).isEqualTo(100.0);
    }

    // -------------------------------------------------------------------------
    // T4 — getTrades_withWinFilter_returnsOnlyLosses
    // -------------------------------------------------------------------------

    @Test
    void getTrades_withWinFilter_returnsOnlyLosses() {
        String runId = "2026-04-01_run";
        insertTrade(runId, "AAPL", "MOMENTUM", "CALL", "2026-04-01T10:00:00Z", 5.0, 1, null);
        insertTrade(runId, "MSFT", "MOMENTUM", "PUT", "2026-04-01T11:00:00Z", -3.0, 0, null);
        insertTrade(runId, "GOOG", "SQUEEZE", "CALL", "2026-04-01T12:00:00Z", -7.0, 0, "DOJI");

        TradesPageDto page = service.getTrades(runId, null, null, 0, 100, 0);

        assertThat(page.total()).isEqualTo(2);
        assertThat(page.trades()).hasSize(2);
        assertThat(page.trades()).allMatch(t -> t.win() == 0);
    }

    // -------------------------------------------------------------------------
    // T5 — getTrades_withStrategyFilter_returnsOnlyMatchingStrategy
    // -------------------------------------------------------------------------

    @Test
    void getTrades_withStrategyFilter_returnsOnlyMatchingStrategy() {
        String runId = "2026-04-02_run";
        insertTrade(runId, "AAPL", "MOMENTUM", "CALL", "2026-04-02T10:00:00Z", 5.0, 1, null);
        insertTrade(runId, "MSFT", "SQUEEZE", "CALL", "2026-04-02T11:00:00Z", 3.0, 1, null);
        insertTrade(runId, "GOOG", "MOMENTUM", "PUT", "2026-04-02T12:00:00Z", -2.0, 0, null);

        TradesPageDto page = service.getTrades(runId, "MOMENTUM", null, null, 100, 0);

        assertThat(page.total()).isEqualTo(2);
        assertThat(page.trades()).allMatch(t -> t.strategy().equals("MOMENTUM"));
    }

    // -------------------------------------------------------------------------
    // T6 — getTrades_pagination_worksCorrectly
    // -------------------------------------------------------------------------

    @Test
    void getTrades_pagination_worksCorrectly() {
        String runId = "2026-04-03_run";
        for (int i = 0; i < 5; i++) {
            insertTrade(runId, "AAPL", "MOMENTUM", "CALL",
                    "2026-04-03T10:0" + i + ":00Z", i + 1.0, 1, null);
        }

        TradesPageDto page = service.getTrades(runId, null, null, null, 2, 1);

        // total is still 5, but only 2 returned starting at offset 1
        assertThat(page.total()).isEqualTo(5);
        assertThat(page.trades()).hasSize(2);
    }

    // -------------------------------------------------------------------------
    // T7 — getLossesAnalysis_byHour_groupsCorrectly
    // -------------------------------------------------------------------------

    @Test
    void getLossesAnalysis_byHour_groupsCorrectly() {
        String runId = "2026-05-01_run";
        // 2 losses at 09:xx, 1 loss at 14:xx, 1 win at 09:xx (should be excluded)
        insertTrade(runId, "AAPL", "MOMENTUM", "CALL", "2026-05-01T09:15:00Z", -4.0, 0, null);
        insertTrade(runId, "MSFT", "MOMENTUM", "CALL", "2026-05-01T09:30:00Z", -6.0, 0, null);
        insertTrade(runId, "GOOG", "MOMENTUM", "PUT", "2026-05-01T14:00:00Z", -3.0, 0, "DOJI");
        insertTrade(runId, "TSLA", "SQUEEZE", "CALL", "2026-05-01T09:45:00Z", 8.0, 1, null); // win — excluded

        LossesAnalysisDto analysis = service.getLossesAnalysis(runId);

        assertThat(analysis.byHour()).hasSize(2);

        HourlyLossDto hour09 = analysis.byHour().stream()
                .filter(h -> h.hour().equals("09"))
                .findFirst()
                .orElseThrow();
        assertThat(hour09.count()).isEqualTo(2);
        assertThat(hour09.avgLoss()).isEqualTo(-5.0);

        HourlyLossDto hour14 = analysis.byHour().stream()
                .filter(h -> h.hour().equals("14"))
                .findFirst()
                .orElseThrow();
        assertThat(hour14.count()).isEqualTo(1);
        assertThat(hour14.avgLoss()).isEqualTo(-3.0);
    }

    // -------------------------------------------------------------------------
    // T8 — getLossesAnalysis_worstTrades_top20MaxSize
    // -------------------------------------------------------------------------

    @Test
    void getLossesAnalysis_worstTrades_top20MaxSize() {
        String runId = "2026-05-02_run";
        // Insert 25 losses
        for (int i = 0; i < 25; i++) {
            insertTrade(runId, "AAPL", "MOMENTUM", "CALL",
                    "2026-05-02T10:00:00Z", -(i + 1.0), 0, null);
        }

        LossesAnalysisDto analysis = service.getLossesAnalysis(runId);

        assertThat(analysis.worstTrades()).hasSize(20);
        // Should be ordered ASC by pnl (worst first)
        assertThat(analysis.worstTrades().get(0).pnl()).isLessThanOrEqualTo(analysis.worstTrades().get(1).pnl());
    }

    // -------------------------------------------------------------------------
    // T9 — getLossesAnalysis_byPattern_groupsCorrectly
    // -------------------------------------------------------------------------

    @Test
    void getLossesAnalysis_byPattern_groupsCorrectly() {
        String runId = "2026-05-03_run";
        insertTrade(runId, "AAPL", "MOMENTUM", "CALL", "2026-05-03T10:00:00Z", -3.0, 0, "DOJI");
        insertTrade(runId, "MSFT", "SQUEEZE", "PUT", "2026-05-03T11:00:00Z", -5.0, 0, "DOJI");
        insertTrade(runId, "GOOG", "MOMENTUM", "CALL", "2026-05-03T12:00:00Z", -2.0, 0, "HAMMER");

        LossesAnalysisDto analysis = service.getLossesAnalysis(runId);

        assertThat(analysis.byPattern()).hasSize(2);

        PatternLossDto doji = analysis.byPattern().stream()
                .filter(p -> p.pattern().equals("DOJI"))
                .findFirst()
                .orElseThrow();
        assertThat(doji.count()).isEqualTo(2);
        assertThat(doji.avgLoss()).isEqualTo(-4.0);
    }

    // -------------------------------------------------------------------------
    // T10 — listRuns_emptyDb_returnsEmptyList
    // -------------------------------------------------------------------------

    @Test
    void listRuns_emptyDb_returnsEmptyList() {
        List<RunSummaryDto> runs = service.listRuns();
        assertThat(runs).isEmpty();
    }
}
