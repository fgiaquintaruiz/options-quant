package com.fgiaquinta.optionsquant.backtest.batch;

import com.fgiaquinta.optionsquant.backtest.domain.BacktestConfig;
import com.fgiaquinta.optionsquant.backtest.domain.BacktestReport;
import com.fgiaquinta.optionsquant.backtest.domain.TradeRecord;
import com.fgiaquinta.optionsquant.backtest.engine.BacktestEngine;
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

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * TDD — RED tests for the callback-based per-ticker persistence refactoring.
 *
 * <p>These tests verify:
 * <ol>
 *   <li>BacktestConfig built by BacktestBatchRunner registers an {@code onTickerComplete} callback.</li>
 *   <li>The callback calls {@code persistenceService.persistTickerResult()} — not {@code persistTradesFromReport()}.</li>
 *   <li>persist happens per ticker during the run, not once at the end.</li>
 * </ol>
 *
 * <p>The engine is mocked — it captures the config, invokes the callback manually,
 * and returns an empty report. This isolates the wiring from real backtest execution.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BacktestBatchRunnerCallbackTest {

    @Mock
    private BacktestEngine backtestEngine;

    @Mock
    private BacktestCsvWriter csvWriter;

    @Mock
    private JdbcTemplate readJdbc;

    @Mock
    private BacktestPersistenceService persistenceService;

    @Mock
    private Supplier<String> lineReader;

    private BacktestBatchRunner runner;

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static TradeRecord trade(String ticker) {
        return new TradeRecord(
                ticker, "MOMENTUM", "CALL", 1, 100.0,
                ZonedDateTime.of(2024, 1, 10, 10, 0, 0, 0, ZoneOffset.UTC),
                105.0,
                ZonedDateTime.of(2024, 1, 10, 14, 0, 0, 0, ZoneOffset.UTC),
                "TP", 5.0, 0.65, 0.75, 3.60, 2.0, 6.0
        );
    }

    private static BacktestReport emptyReport() {
        return new BacktestReport(
                50000, 50000, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                Map.of(), Map.of(), List.of(), List.of(), 100L
        );
    }

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
    // T1 — config passed to engine has a non-null onTickerComplete callback
    // -------------------------------------------------------------------------

    @Test
    void whenBacktestAllRuns_configHasNonNullCallback() throws Exception {
        var args = new DefaultApplicationArguments("--backtest-all");

        when(readJdbc.queryForList(anyString(), eq(String.class), any(Object[].class)))
                .thenReturn(List.of("AAPL", "MSFT", "GOOG"));
        when(persistenceService.findIncompleteRun(anyInt())).thenReturn(Optional.empty());
        when(backtestEngine.run(any(BacktestConfig.class))).thenReturn(emptyReport());

        runner.run(args);

        ArgumentCaptor<BacktestConfig> captor = ArgumentCaptor.forClass(BacktestConfig.class);
        verify(backtestEngine).run(captor.capture());
        assertThat(captor.getValue().onTickerComplete())
                .as("BacktestConfig must carry a non-null onTickerComplete callback")
                .isNotNull();
    }

    // -------------------------------------------------------------------------
    // T2 — invoking the callback calls persistTickerResult once per ticker
    //
    // The engine is mocked to capture the config, then we invoke the callback
    // manually for 3 tickers — simulating what the engine would do.
    // persistTickerResult must be called exactly 3 times.
    // -------------------------------------------------------------------------

    @Test
    void whenCallbackInvokedPerTicker_persistTickerResultCalledPerTicker() throws Exception {
        var args = new DefaultApplicationArguments("--backtest-all");

        when(readJdbc.queryForList(anyString(), eq(String.class), any(Object[].class)))
                .thenReturn(List.of("AAPL", "MSFT", "GOOG"));
        when(persistenceService.findIncompleteRun(anyInt())).thenReturn(Optional.empty());

        // Engine: capture config, invoke callback for each ticker, return empty report
        when(backtestEngine.run(any(BacktestConfig.class))).thenAnswer(invocation -> {
            BacktestConfig config = invocation.getArgument(0);
            if (config.onTickerComplete() != null) {
                config.onTickerComplete().accept("AAPL", List.of(trade("AAPL")));
                config.onTickerComplete().accept("MSFT", List.of(trade("MSFT")));
                config.onTickerComplete().accept("GOOG", List.of());
            }
            return emptyReport();
        });

        runner.run(args);

        // persistTickerResult must be called exactly 3 times (once per ticker)
        verify(persistenceService, times(3)).persistTickerResult(
                anyString(), anyString(), anyString(), anyList());
    }

    // -------------------------------------------------------------------------
    // T3 — persist happens DURING the run, not only after run() returns
    //
    // We track call count inside the engine answer before returning the report.
    // If persist were called only after engine.run() returns, count would be 0 inside answer.
    // -------------------------------------------------------------------------

    @Test
    void whenCallbackRegistered_persistHappensBeforeRunCompletes() throws Exception {
        var args = new DefaultApplicationArguments("--backtest-all");

        when(readJdbc.queryForList(anyString(), eq(String.class), any(Object[].class)))
                .thenReturn(List.of("AAPL", "MSFT"));
        when(persistenceService.findIncompleteRun(anyInt())).thenReturn(Optional.empty());

        AtomicInteger countDuringRun = new AtomicInteger(0);

        when(backtestEngine.run(any(BacktestConfig.class))).thenAnswer(invocation -> {
            BacktestConfig config = invocation.getArgument(0);
            if (config.onTickerComplete() != null) {
                config.onTickerComplete().accept("AAPL", List.of(trade("AAPL")));
                config.onTickerComplete().accept("MSFT", List.of(trade("MSFT")));
            }
            // Capture how many times persistTickerResult was called BEFORE engine.run() returns
            countDuringRun.set(
                    mockingDetails(persistenceService).getInvocations().stream()
                            .filter(inv -> inv.getMethod().getName().equals("persistTickerResult"))
                            .mapToInt(inv -> 1).sum()
            );
            return emptyReport();
        });

        runner.run(args);

        assertThat(countDuringRun.get())
                .as("persistTickerResult must be called DURING engine.run(), not only after it returns")
                .isEqualTo(2);
    }

    // -------------------------------------------------------------------------
    // T4 — ticker and timeframe arguments forwarded correctly to persistTickerResult
    // -------------------------------------------------------------------------

    @Test
    void whenCallbackInvoked_correctTickerAndTimeframeForwarded() throws Exception {
        var args = new DefaultApplicationArguments("--backtest-all");

        when(readJdbc.queryForList(anyString(), eq(String.class), any(Object[].class)))
                .thenReturn(List.of("AAPL"));
        when(persistenceService.findIncompleteRun(anyInt())).thenReturn(Optional.empty());

        when(backtestEngine.run(any(BacktestConfig.class))).thenAnswer(invocation -> {
            BacktestConfig config = invocation.getArgument(0);
            if (config.onTickerComplete() != null) {
                config.onTickerComplete().accept("AAPL", List.of(trade("AAPL"), trade("AAPL")));
            }
            return emptyReport();
        });

        runner.run(args);

        ArgumentCaptor<String> runIdCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> tickerCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> timeframeCaptor = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TradeRecord>> tradesCaptor =
                (ArgumentCaptor<List<TradeRecord>>) (ArgumentCaptor<?>) ArgumentCaptor.forClass(List.class);

        verify(persistenceService).persistTickerResult(
                runIdCaptor.capture(),
                tickerCaptor.capture(),
                timeframeCaptor.capture(),
                tradesCaptor.capture());

        assertThat(tickerCaptor.getValue()).isEqualTo("AAPL");
        assertThat(timeframeCaptor.getValue()).isEqualTo("MIN_15");
        assertThat(tradesCaptor.getValue()).hasSize(2);
    }
}
