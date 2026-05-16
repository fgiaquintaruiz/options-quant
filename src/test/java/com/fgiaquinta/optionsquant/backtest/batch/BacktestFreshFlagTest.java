package com.fgiaquinta.optionsquant.backtest.batch;

import com.fgiaquinta.optionsquant.backtest.domain.BacktestConfig;
import com.fgiaquinta.optionsquant.backtest.domain.BacktestReport;
import com.fgiaquinta.optionsquant.backtest.engine.BacktestEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * TDD tests for --backtest-fresh and --backtest-fresh-force flags.
 *
 * Tests are pure unit — no Spring context, no real DB.
 * System.exit() is intercepted via the protected exit() wrapper.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BacktestFreshFlagTest {

    @Mock private BacktestEngine backtestEngine;
    @Mock private BacktestCsvWriter csvWriter;
    @Mock private JdbcTemplate readJdbc;
    @Mock private BacktestPersistenceService persistenceService;
    @Mock private Supplier<String> lineReader;

    /**
     * Testable subclass that captures exit() calls instead of calling System.exit().
     */
    private static class TestableBatchRunner extends BacktestBatchRunner {
        int exitCode = Integer.MIN_VALUE;

        TestableBatchRunner(BacktestEngine engine, BacktestCsvWriter writer,
                            JdbcTemplate readJdbc, BacktestPersistenceService persistence,
                            Supplier<String> lineReader) {
            super(engine, writer, readJdbc, persistence, lineReader);
        }

        @Override
        protected void exit(int code) {
            exitCode = code;
            // Do NOT call System.exit — capture for assertion
        }
    }

    private TestableBatchRunner runner;

    private static final BacktestReport EMPTY_REPORT = new BacktestReport(
            50000, 50000, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            Map.of(), Map.of(), List.of(), List.of(), 100L
    );

    @BeforeEach
    void setUp() {
        runner = new TestableBatchRunner(
                backtestEngine, csvWriter, readJdbc,
                persistenceService, lineReader);
    }

    // -------------------------------------------------------------------------
    // T1 — --backtest-fresh alone (without --backtest-all) → no DB interaction
    // -------------------------------------------------------------------------

    @Test
    void freshFlag_withoutBacktestAll_doesNothing() throws Exception {
        var args = new DefaultApplicationArguments("--backtest-fresh");

        runner.run(args);

        verifyNoInteractions(backtestEngine, csvWriter);
        verify(persistenceService, never()).deleteAllData();
        assertThat(runner.exitCode).isEqualTo(Integer.MIN_VALUE); // exit() never called
    }

    // -------------------------------------------------------------------------
    // T2 — --backtest-fresh-force + --backtest-all → executes deletes, no stdin
    // -------------------------------------------------------------------------

    @Test
    void freshForceFlagWithBacktestAll_skipsConfirmation() throws Exception {
        var args = new DefaultApplicationArguments("--backtest-all", "--backtest-fresh-force");

        when(readJdbc.queryForList(anyString(), eq(String.class), any(Object[].class)))
                .thenReturn(List.of("AAPL", "MSFT"));
        when(persistenceService.findIncompleteRun(anyInt())).thenReturn(Optional.empty());
        when(persistenceService.deleteAllData()).thenReturn(new BacktestPersistenceService.DeleteResult(10, 5));
        when(backtestEngine.run(any(BacktestConfig.class))).thenReturn(EMPTY_REPORT);

        runner.run(args);

        // lineReader must NEVER be called (force skips prompt)
        verify(lineReader, never()).get();
        // deleteAllData must be called exactly once
        verify(persistenceService, times(1)).deleteAllData();
    }

    // -------------------------------------------------------------------------
    // T3 — --backtest-fresh + --backtest-all, user answers "no" → abort
    // -------------------------------------------------------------------------

    @Test
    void freshFlag_withBacktestAll_abortsWhenAnswerIsNo() throws Exception {
        var args = new DefaultApplicationArguments("--backtest-all", "--backtest-fresh");

        when(lineReader.get()).thenReturn("no");

        runner.run(args);

        // deleteAllData must NOT be called — user aborted
        verify(persistenceService, never()).deleteAllData();
        // engine must NOT run
        verify(backtestEngine, never()).run(any());
        // exit(1) must be called
        assertThat(runner.exitCode).isEqualTo(1);
    }

    // -------------------------------------------------------------------------
    // T4 — --backtest-fresh + --backtest-all, user answers "yes" → executes deletes
    // -------------------------------------------------------------------------

    @Test
    void freshFlag_withBacktestAll_proceedsWhenAnswerIsYes() throws Exception {
        var args = new DefaultApplicationArguments("--backtest-all", "--backtest-fresh");

        when(readJdbc.queryForList(anyString(), eq(String.class), any(Object[].class)))
                .thenReturn(List.of("AAPL"));
        when(persistenceService.findIncompleteRun(anyInt())).thenReturn(Optional.empty());
        when(persistenceService.deleteAllData()).thenReturn(new BacktestPersistenceService.DeleteResult(3, 2));
        when(backtestEngine.run(any(BacktestConfig.class))).thenReturn(EMPTY_REPORT);
        when(lineReader.get()).thenReturn("yes");

        runner.run(args);

        // deleteAllData called once
        verify(persistenceService, times(1)).deleteAllData();
        // engine ran
        verify(backtestEngine, times(1)).run(any(BacktestConfig.class));
        // exit(1) NOT called — only exit(0) at end of normal flow
        assertThat(runner.exitCode).isNotEqualTo(1);
    }

    // -------------------------------------------------------------------------
    // T5 — deleteAllData throws → exit(1), engine never runs
    // -------------------------------------------------------------------------

    @Test
    void freshForceFlag_whenDeleteFails_abortsWithExit1() throws Exception {
        var args = new DefaultApplicationArguments("--backtest-all", "--backtest-fresh-force");

        when(persistenceService.deleteAllData())
                .thenThrow(new RuntimeException("SQLite locked"));

        runner.run(args);

        verify(backtestEngine, never()).run(any());
        assertThat(runner.exitCode).isEqualTo(1);
    }

    // -------------------------------------------------------------------------
    // T6 — table doesn't exist (deleteAllData returns 0,0) → proceeds normally
    // -------------------------------------------------------------------------

    @Test
    void freshForceFlag_whenTableNotFound_proceedsWithBacktest() throws Exception {
        var args = new DefaultApplicationArguments("--backtest-all", "--backtest-fresh-force");

        when(readJdbc.queryForList(anyString(), eq(String.class), any(Object[].class)))
                .thenReturn(List.of("AAPL"));
        when(persistenceService.findIncompleteRun(anyInt())).thenReturn(Optional.empty());
        // deleteAllData handles missing tables internally and returns 0,0
        when(persistenceService.deleteAllData()).thenReturn(new BacktestPersistenceService.DeleteResult(0, 0));
        when(backtestEngine.run(any(BacktestConfig.class))).thenReturn(EMPTY_REPORT);

        runner.run(args);

        // No abort — backtest ran normally
        verify(backtestEngine, times(1)).run(any(BacktestConfig.class));
        assertThat(runner.exitCode).isNotEqualTo(1);
    }
}
