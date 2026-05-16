package com.fgiaquinta.optionsquant.backtest.batch;

import com.fgiaquinta.optionsquant.backtest.domain.BacktestConfig;
import com.fgiaquinta.optionsquant.backtest.domain.BacktestReport;
import com.fgiaquinta.optionsquant.backtest.engine.BacktestEngine;
import com.fgiaquinta.optionsquant.candle.backfill.BackfillStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for BacktestBatchRunner.
 *
 * Tests are pure domain — no Spring context, no DB.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BacktestBatchRunnerTest {

    @Mock
    private BacktestEngine backtestEngine;

    @Mock
    private BacktestCsvWriter csvWriter;

    @Mock
    private JdbcTemplate readJdbc;

    private BacktestBatchRunner runner;

    private static final BacktestReport EMPTY_REPORT = new BacktestReport(
            50000, 50000, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            Map.of(), Map.of(), List.of(), List.of(), 100L
    );

    @BeforeEach
    void setUp() {
        runner = new BacktestBatchRunner(backtestEngine, csvWriter, readJdbc) {
            @Override
            protected void exit(int code) { /* no-op: prevent System.exit in tests */ }
        };
    }

    // -------------------------------------------------------------------------
    // T1 — --backtest-all present → runner executes backtest
    // -------------------------------------------------------------------------

    @Test
    void whenBacktestAllArgPresent_runnerExecutes() throws Exception {
        var args = new DefaultApplicationArguments("--backtest-all");
        when(readJdbc.queryForList(anyString(), eq(String.class), any(Object[].class)))
                .thenReturn(List.of("AAPL", "MSFT"));
        when(backtestEngine.run(any(BacktestConfig.class))).thenReturn(EMPTY_REPORT);

        runner.run(args);

        verify(backtestEngine, atLeastOnce()).run(any(BacktestConfig.class));
    }

    // -------------------------------------------------------------------------
    // T2 — --backtest-all absent → runner is a no-op
    // -------------------------------------------------------------------------

    @Test
    void whenBacktestAllArgAbsent_runnerSkips() throws Exception {
        var args = new DefaultApplicationArguments("--server.port=9090");

        runner.run(args);

        verifyNoInteractions(backtestEngine, csvWriter);
    }

    // -------------------------------------------------------------------------
    // T3 — ticker with no valid candles in download_progress is excluded
    // -------------------------------------------------------------------------

    @Test
    void whenNoValidTickers_backtestIsNotCalled() throws Exception {
        var args = new DefaultApplicationArguments("--backtest-all");
        when(readJdbc.queryForList(anyString(), eq(String.class), any(Object[].class)))
                .thenReturn(List.of());

        runner.run(args);

        verifyNoInteractions(backtestEngine);
    }

    // -------------------------------------------------------------------------
    // T4 — BacktestConfig built from tickers returned by query
    // -------------------------------------------------------------------------

    @Test
    void whenTickersPresent_configContainsThoseTickers() throws Exception {
        var args = new DefaultApplicationArguments("--backtest-all");
        when(readJdbc.queryForList(anyString(), eq(String.class), any(Object[].class)))
                .thenReturn(List.of("AAPL", "GOOG", "TSLA"));
        when(backtestEngine.run(any(BacktestConfig.class))).thenReturn(EMPTY_REPORT);

        runner.run(args);

        ArgumentCaptor<BacktestConfig> captor = ArgumentCaptor.forClass(BacktestConfig.class);
        verify(backtestEngine).run(captor.capture());
        assertThat(captor.getValue().tickers()).containsExactlyInAnyOrder("AAPL", "GOOG", "TSLA");
    }

    // -------------------------------------------------------------------------
    // T5 — engine exception does not propagate; csvWriter is still called with empty list
    // -------------------------------------------------------------------------

    @Test
    void whenEngineThrowsException_csvWriterIsStillCalled() throws Exception {
        var args = new DefaultApplicationArguments("--backtest-all");
        when(readJdbc.queryForList(anyString(), eq(String.class), any(Object[].class)))
                .thenReturn(List.of("AAPL"));
        when(backtestEngine.run(any(BacktestConfig.class)))
                .thenThrow(new RuntimeException("engine boom"));

        // Should not throw
        runner.run(args);

        verify(csvWriter).write(any());
    }

    // -------------------------------------------------------------------------
    // T6 — --complete-tickers queries ticker_stats, not download_progress
    // -------------------------------------------------------------------------

    @Test
    void whenCompleteTickersFlagPresent_queriesTickerStats() {
        var args = new DefaultApplicationArguments("--backtest-all", "--complete-tickers");
        when(readJdbc.queryForList(contains("ticker_stats"), eq(String.class)))
                .thenReturn(List.of("AAPL", "MSFT"));
        when(backtestEngine.run(any(BacktestConfig.class))).thenReturn(EMPTY_REPORT);

        runner.run(args);

        verify(readJdbc).queryForList(contains("ticker_stats"), eq(String.class));
        verify(readJdbc, never()).queryForList(contains("download_progress"), eq(String.class), any());
    }

    // -------------------------------------------------------------------------
    // T7 — --complete-tickers: backtest runs with tickers from ticker_stats
    // -------------------------------------------------------------------------

    @Test
    void whenCompleteTickersFlagPresent_backtestRunsWithCompleteTickersOnly() throws Exception {
        var args = new DefaultApplicationArguments("--backtest-all", "--complete-tickers");
        when(readJdbc.queryForList(contains("ticker_stats"), eq(String.class)))
                .thenReturn(List.of("GOOG", "NVDA"));
        when(backtestEngine.run(any(BacktestConfig.class))).thenReturn(EMPTY_REPORT);

        runner.run(args);

        ArgumentCaptor<BacktestConfig> captor = ArgumentCaptor.forClass(BacktestConfig.class);
        verify(backtestEngine, atLeastOnce()).run(captor.capture());
        assertThat(captor.getValue().tickers()).containsExactlyInAnyOrder("GOOG", "NVDA");
    }

    // -------------------------------------------------------------------------
    // T8 — --complete-tickers with empty ticker_stats: backtest not called
    // -------------------------------------------------------------------------

    @Test
    void whenCompleteTickersFlagAndNoQualifyingTickers_backtestNotCalled() {
        var args = new DefaultApplicationArguments("--backtest-all", "--complete-tickers");
        when(readJdbc.queryForList(contains("ticker_stats"), eq(String.class)))
                .thenReturn(List.of());

        runner.run(args);

        verifyNoInteractions(backtestEngine);
    }
}
