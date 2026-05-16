package com.fgiaquinta.optionsquant.backtest.batch;

import com.fgiaquinta.optionsquant.backtest.engine.BacktestEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * TDD tests for runtime metrics logging in BacktestBatchRunner.
 *
 * <p>Tests are pure unit — no Spring context, no real DB.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BacktestBatchRunnerMetricsTest {

    @Mock private BacktestEngine backtestEngine;
    @Mock private BacktestCsvWriter csvWriter;
    @Mock private JdbcTemplate readJdbc;
    @Mock private BacktestPersistenceService persistenceService;
    @Mock private Supplier<String> lineReader;

    private BacktestBatchRunner runner;

    @BeforeEach
    void setUp() {
        runner = new BacktestBatchRunner(
                backtestEngine, csvWriter, readJdbc,
                persistenceService, lineReader) {
            @Override
            protected void exit(int code) { /* no-op: prevent System.exit in tests */ }
        };
    }

    // -------------------------------------------------------------------------
    // T1 — Duration formatting: less than 60 seconds → "0m Xs"
    // -------------------------------------------------------------------------

    @Test
    void formatDuration_lessThan60Seconds_showsZeroMinutes() {
        Duration d = Duration.ofSeconds(45);
        String result = BacktestBatchRunner.formatDuration(d);
        assertThat(result).isEqualTo("0m 45s");
    }

    // -------------------------------------------------------------------------
    // T2 — Duration formatting: exactly 60 seconds → "1m 0s"
    // -------------------------------------------------------------------------

    @Test
    void formatDuration_exactly60Seconds_showsOneMinuteZeroSeconds() {
        Duration d = Duration.ofSeconds(60);
        String result = BacktestBatchRunner.formatDuration(d);
        assertThat(result).isEqualTo("1m 0s");
    }

    // -------------------------------------------------------------------------
    // T3 — Duration formatting: 2 minutes 37 seconds
    // -------------------------------------------------------------------------

    @Test
    void formatDuration_2min37sec_showsCorrectFormat() {
        Duration d = Duration.ofSeconds(157);
        String result = BacktestBatchRunner.formatDuration(d);
        assertThat(result).isEqualTo("2m 37s");
    }

    // -------------------------------------------------------------------------
    // T4 — Duration formatting: never shows decimal minutes ("0.7m" is wrong)
    // -------------------------------------------------------------------------

    @Test
    void formatDuration_42Seconds_doesNotContainDecimalMinutes() {
        Duration d = Duration.ofSeconds(42);
        String result = BacktestBatchRunner.formatDuration(d);
        assertThat(result).doesNotContain(".");
        assertThat(result).startsWith("0m");
    }

    // -------------------------------------------------------------------------
    // T5 — SQL fallback: COUNT query throws → totalTrades shows "N/A"
    // -------------------------------------------------------------------------

    @Test
    void queryMetrics_whenCountQueryThrows_returnsNAForTrades() {
        when(readJdbc.queryForObject(
                contains("COUNT(*)"), eq(Long.class), anyString()))
                .thenThrow(new RuntimeException("db error"));

        BacktestBatchRunner.RunMetrics metrics =
                runner.queryRunMetrics("some-run-id");

        assertThat(metrics.totalTrades()).isEqualTo("N/A");
    }

    // -------------------------------------------------------------------------
    // T6 — SQL fallback: SUM query throws → totalPnl shows "N/A"
    // -------------------------------------------------------------------------

    @Test
    void queryMetrics_whenSumQueryThrows_returnsNAForPnl() {
        when(readJdbc.queryForObject(
                contains("COUNT(*)"), eq(Long.class), anyString()))
                .thenReturn(42L);
        when(readJdbc.queryForObject(
                contains("SUM(pnl)"), eq(Double.class), anyString()))
                .thenThrow(new RuntimeException("db error"));

        BacktestBatchRunner.RunMetrics metrics =
                runner.queryRunMetrics("some-run-id");

        assertThat(metrics.totalTrades()).isEqualTo("42");
        assertThat(metrics.totalPnl()).isEqualTo("N/A");
    }

    // -------------------------------------------------------------------------
    // T7 — SQL success: both queries return values → metrics are populated
    // -------------------------------------------------------------------------

    @Test
    void queryMetrics_whenBothQueriesSucceed_returnsPopulatedMetrics() {
        when(readJdbc.queryForObject(
                contains("COUNT(*)"), eq(Long.class), anyString()))
                .thenReturn(150L);
        when(readJdbc.queryForObject(
                contains("SUM(pnl)"), eq(Double.class), anyString()))
                .thenReturn(3750.50);

        BacktestBatchRunner.RunMetrics metrics =
                runner.queryRunMetrics("some-run-id");

        assertThat(metrics.totalTrades()).isEqualTo("150");
        assertThat(metrics.totalPnl()).isEqualTo("3750.50");
    }

    // -------------------------------------------------------------------------
    // T8 — SUM returns null (no trades) → totalPnl shows "0.00"
    // -------------------------------------------------------------------------

    @Test
    void queryMetrics_whenSumReturnsNull_showsZeroPnl() {
        when(readJdbc.queryForObject(
                contains("COUNT(*)"), eq(Long.class), anyString()))
                .thenReturn(0L);
        when(readJdbc.queryForObject(
                contains("SUM(pnl)"), eq(Double.class), anyString()))
                .thenReturn(null);

        BacktestBatchRunner.RunMetrics metrics =
                runner.queryRunMetrics("some-run-id");

        assertThat(metrics.totalTrades()).isEqualTo("0");
        assertThat(metrics.totalPnl()).isEqualTo("0.00");
    }

}
