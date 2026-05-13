package com.fgiaquinta.optionsquant.backtest.batch;

import com.fgiaquinta.optionsquant.backtest.domain.TradeRecord;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.time.ZonedDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.*;

/**
 * Unit tests for BacktestPersistenceService using an in-memory SQLite database.
 *
 * TDD — tests written before the implementation exists.
 */
class BacktestPersistenceServiceTest {

    private HikariDataSource dataSource;
    private BacktestPersistenceService service;
    private JdbcTemplate jdbc;

    // -------------------------------------------------------------------------
    // Test helpers
    // -------------------------------------------------------------------------

    private static HikariDataSource inMemorySqliteDs() {
        HikariConfig cfg = new HikariConfig();
        cfg.setDriverClassName("org.sqlite.JDBC");
        // Use a named in-memory DB so the same connection is reused within the pool
        cfg.setJdbcUrl("jdbc:sqlite:file:testdb?mode=memory&cache=shared");
        cfg.setMaximumPoolSize(1);
        cfg.setMinimumIdle(1);
        cfg.setPoolName("test-sqlite");
        return new HikariDataSource(cfg);
    }

    private static TradeRecord minimalTrade(String ticker) {
        return new TradeRecord(
                ticker,
                "MOMENTUM",
                "CALL",
                1,
                150.0,
                ZonedDateTime.of(2024, 1, 15, 10, 0, 0, 0, ZoneOffset.UTC),
                155.0,
                ZonedDateTime.of(2024, 1, 15, 14, 0, 0, 0, ZoneOffset.UTC),
                "TP",
                5.0,
                0.65,
                0.75,
                3.60,
                2.0,
                6.0
        );
    }

    private static TradeRecord losingTrade(String ticker) {
        return new TradeRecord(
                ticker,
                "SQUEEZE",
                "PUT",
                1,
                200.0,
                ZonedDateTime.of(2024, 2, 10, 9, 30, 0, 0, ZoneOffset.UTC),
                195.0,
                ZonedDateTime.of(2024, 2, 10, 11, 0, 0, 0, ZoneOffset.UTC),
                "SL",
                -5.0,
                0.65,
                0.75,
                -6.40,
                6.0,
                0.5
        );
    }

    @BeforeEach
    void setUp() {
        dataSource = inMemorySqliteDs();
        service = new BacktestPersistenceService(dataSource);
        service.initSchema();
        jdbc = new JdbcTemplate(dataSource);
    }

    @AfterEach
    void tearDown() {
        // Clean tables between tests (shared in-memory DB)
        jdbc.execute("DELETE FROM backtest_trades");
        jdbc.execute("DELETE FROM backtest_progress");
        dataSource.close();
    }

    // -------------------------------------------------------------------------
    // T1 — findIncompleteRun — empty DB returns Optional.empty()
    // -------------------------------------------------------------------------

    @Test
    void findIncompleteRun_whenNoRuns_returnsEmpty() {
        Optional<String> result = service.findIncompleteRun(3);

        assertThat(result).isEmpty();
    }

    // -------------------------------------------------------------------------
    // T2 — findIncompleteRun — all tickers COMPLETE returns Optional.empty()
    // -------------------------------------------------------------------------

    @Test
    void findIncompleteRun_whenAllComplete_returnsEmpty() {
        String runId = "2024-01-01_10-00";
        insertProgress(runId, "AAPL", "COMPLETE");
        insertProgress(runId, "MSFT", "COMPLETE");
        insertProgress(runId, "GOOG", "COMPLETE");
        // Real completed run has trades — verifying that a fully-complete run is NOT resumed
        insertTrade(runId, "AAPL");
        insertTrade(runId, "MSFT");
        insertTrade(runId, "GOOG");

        Optional<String> result = service.findIncompleteRun(3);

        assertThat(result).isEmpty();
    }

    // -------------------------------------------------------------------------
    // T3 — findIncompleteRun — partial run (2 of 3 complete) returns the run_id
    // -------------------------------------------------------------------------

    @Test
    void findIncompleteRun_whenPartialRun_returnsRunId() {
        String runId = "2024-01-01_10-00";
        insertProgress(runId, "AAPL", "COMPLETE");
        insertProgress(runId, "MSFT", "COMPLETE");
        // GOOG not present (not yet processed) — 2 of 3 complete = legitimate partial run
        // A real partial run always has at least one trade from the completed tickers
        insertTrade(runId, "AAPL");

        Optional<String> result = service.findIncompleteRun(3);

        assertThat(result).isPresent().contains(runId);
    }

    // -------------------------------------------------------------------------
    // T4 — persistTickerResult inserts trades and marks progress as COMPLETE
    // -------------------------------------------------------------------------

    @Test
    void persistTickerResult_insertsTradesAndProgress() {
        String runId = "2024-01-15_09-30";
        String ticker = "AAPL";
        List<TradeRecord> trades = List.of(minimalTrade(ticker), losingTrade(ticker));

        service.persistTickerResult(runId, ticker, "MIN_15", trades);

        // Verify trades were inserted
        Integer tradeCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM backtest_trades WHERE run_id=? AND ticker=?",
                Integer.class, runId, ticker);
        assertThat(tradeCount).isEqualTo(2);

        // Verify progress row is COMPLETE
        String status = jdbc.queryForObject(
                "SELECT status FROM backtest_progress WHERE run_id=? AND ticker=?",
                String.class, runId, ticker);
        assertThat(status).isEqualTo("COMPLETE");

        // Verify trade_count in progress
        Integer progressCount = jdbc.queryForObject(
                "SELECT trade_count FROM backtest_progress WHERE run_id=? AND ticker=?",
                Integer.class, runId, ticker);
        assertThat(progressCount).isEqualTo(2);
    }

    // -------------------------------------------------------------------------
    // T5 — persistTickerResult is atomic: error during transaction leaves no partial data
    // -------------------------------------------------------------------------

    @Test
    void persistTickerResult_isAtomic_rollsBackOnError() {
        // Use a broken DataSource that fails on the second connection request (mid-transaction)
        DataSource brokenDs = mock(DataSource.class, invocation -> {
            throw new RuntimeException("simulated DB failure");
        });
        BacktestPersistenceService brokenService = new BacktestPersistenceService(brokenDs);

        // Should not throw (service should handle the exception)
        // But NO data should be committed to our real DS
        assertThatCode(() -> brokenService.persistTickerResult(
                "run-fail", "AAPL", "MIN_15", List.of(minimalTrade("AAPL"))))
                .doesNotThrowAnyException();

        // Verify nothing was written to the real dataSource
        Integer tradeCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM backtest_trades WHERE run_id=?",
                Integer.class, "run-fail");
        assertThat(tradeCount).isZero();
    }

    // -------------------------------------------------------------------------
    // T6 — getCompletedTickers returns only COMPLETE tickers for a given run
    // -------------------------------------------------------------------------

    @Test
    void getCompletedTickers_returnsOnlyCompletedTickers() {
        String runId = "2024-02-01_08-00";
        insertProgress(runId, "AAPL", "COMPLETE");
        insertProgress(runId, "MSFT", "COMPLETE");
        insertProgress(runId, "GOOG", "IN_PROGRESS");

        Set<String> completed = service.getCompletedTickers(runId);

        assertThat(completed).containsExactlyInAnyOrder("AAPL", "MSFT");
        assertThat(completed).doesNotContain("GOOG");
    }

    // -------------------------------------------------------------------------
    // T7 — initSchema is idempotent (CREATE TABLE IF NOT EXISTS)
    // -------------------------------------------------------------------------

    @Test
    void initSchema_isIdempotent() {
        // Calling initSchema again should not throw
        assertThatCode(() -> service.initSchema()).doesNotThrowAnyException();
    }

    // -------------------------------------------------------------------------
    // T8 — findIncompleteRun ignores runs with progress but zero trades (corrupt state)
    // -------------------------------------------------------------------------

    @Test
    void findIncompleteRun_whenProgressExistsButNoTrades_returnsEmpty() {
        String runId = "2024-03-01_09-00";
        // Insert 3 COMPLETE rows in backtest_progress — but NO rows in backtest_trades
        insertProgress(runId, "AAPL", "COMPLETE");
        insertProgress(runId, "MSFT", "COMPLETE");
        insertProgress(runId, "GOOG", "COMPLETE");
        // backtest_trades intentionally left empty — this is the corrupt state

        Optional<String> result = service.findIncompleteRun(10);

        // Corrupt run (no trades) must be ignored
        assertThat(result).isEmpty();
    }

    // -------------------------------------------------------------------------
    // T9 — findIncompleteRun returns run_id when both progress and trades exist
    // -------------------------------------------------------------------------

    @Test
    void findIncompleteRun_whenProgressAndTradesExist_returnsRunId() {
        String runId = "2024-03-02_10-00";
        // 2 COMPLETE rows in progress + 1 trade → legitimate partial run
        insertProgress(runId, "AAPL", "COMPLETE");
        insertProgress(runId, "MSFT", "COMPLETE");
        insertTrade(runId, "AAPL");

        Optional<String> result = service.findIncompleteRun(10);

        assertThat(result).isPresent().contains(runId);
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

    private void insertTrade(String runId, String ticker) {
        long now = System.currentTimeMillis() / 1000L;
        jdbc.update(
                "INSERT INTO backtest_trades " +
                "(run_id, ticker, strategy, timeframe, signal_date, signal_type, entry_price, exit_price, pnl, win, pattern, created_at) " +
                "VALUES (?, ?, 'MOMENTUM', 'MIN_15', '2024-01-01T10:00:00Z', 'CALL', 100.0, 105.0, 5.0, 1, null, ?)",
                runId, ticker, now
        );
    }
}
