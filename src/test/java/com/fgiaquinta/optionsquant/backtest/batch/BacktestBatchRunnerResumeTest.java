package com.fgiaquinta.optionsquant.backtest.batch;

import com.fgiaquinta.optionsquant.backtest.domain.BacktestConfig;
import com.fgiaquinta.optionsquant.backtest.domain.BacktestReport;
import com.fgiaquinta.optionsquant.backtest.domain.TradeRecord;
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

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for BacktestBatchRunner resume logic (Phase 2).
 *
 * TDD — tests written before the implementation exists.
 * Uses Mockito to isolate the resume/persist orchestration from DB and engine.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BacktestBatchRunnerResumeTest {

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
    // Test helpers
    // -------------------------------------------------------------------------

    private static TradeRecord trade(String ticker) {
        return new TradeRecord(
                ticker,
                "MOMENTUM",
                "CALL",
                1,
                100.0,
                ZonedDateTime.of(2024, 1, 10, 10, 0, 0, 0, ZoneOffset.UTC),
                105.0,
                ZonedDateTime.of(2024, 1, 10, 14, 0, 0, 0, ZoneOffset.UTC),
                "TP",
                5.0, 0.65, 0.75, 3.60, 2.0, 6.0
        );
    }

    private static BacktestReport reportWithTrades(List<TradeRecord> trades) {
        return new BacktestReport(
                50000, 50000, 0, 0, trades.size(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                Map.of(), Map.of(), List.of(), trades, 100L
        );
    }

    @BeforeEach
    void setUp() {
        runner = new BacktestBatchRunner(
                backtestEngine, csvWriter, readJdbc,
                persistenceService, lineReader);
    }

    // -------------------------------------------------------------------------
    // T1 — no incomplete run → start fresh, lineReader never called
    // -------------------------------------------------------------------------

    @Test
    void whenNoIncompleteRun_startsNewRun() throws Exception {
        var args = new DefaultApplicationArguments("--backtest-all");

        when(readJdbc.queryForList(anyString(), eq(String.class), any(Object[].class)))
                .thenReturn(List.of("AAPL", "MSFT", "GOOG"));
        when(persistenceService.findIncompleteRun(3)).thenReturn(Optional.empty());
        when(backtestEngine.run(any(BacktestConfig.class))).thenReturn(reportWithTrades(List.of()));

        runner.run(args);

        // lineReader must NOT be called when there's no incomplete run
        verify(lineReader, never()).get();

        // Engine must be called with all 3 tickers
        ArgumentCaptor<BacktestConfig> captor = ArgumentCaptor.forClass(BacktestConfig.class);
        verify(backtestEngine).run(captor.capture());
        assertThat(captor.getValue().tickers()).containsExactlyInAnyOrder("AAPL", "MSFT", "GOOG");
    }

    // -------------------------------------------------------------------------
    // T2 — incomplete run found, user says "y" → resume with remaining tickers
    // -------------------------------------------------------------------------

    @Test
    void whenIncompleteRunAndUserSaysYes_resumesExistingRun() throws Exception {
        var args = new DefaultApplicationArguments("--backtest-all");
        String existingRunId = "2025-01-01_10-00";

        when(readJdbc.queryForList(anyString(), eq(String.class), any(Object[].class)))
                .thenReturn(List.of("AAPL", "MSFT", "GOOG"));
        when(persistenceService.findIncompleteRun(3)).thenReturn(Optional.of(existingRunId));
        when(persistenceService.getCompletedTickers(existingRunId)).thenReturn(Set.of("AAPL"));
        when(lineReader.get()).thenReturn("y");
        when(backtestEngine.run(any(BacktestConfig.class))).thenReturn(reportWithTrades(List.of()));

        runner.run(args);

        // Engine should be called WITHOUT AAPL (already done)
        ArgumentCaptor<BacktestConfig> captor = ArgumentCaptor.forClass(BacktestConfig.class);
        verify(backtestEngine).run(captor.capture());
        assertThat(captor.getValue().tickers())
                .containsExactlyInAnyOrder("MSFT", "GOOG")
                .doesNotContain("AAPL");
    }

    // -------------------------------------------------------------------------
    // T3 — incomplete run found, user says "n" → new run with all tickers
    // -------------------------------------------------------------------------

    @Test
    void whenIncompleteRunAndUserSaysNo_startsNewRun() throws Exception {
        var args = new DefaultApplicationArguments("--backtest-all");
        String existingRunId = "2025-01-01_10-00";

        when(readJdbc.queryForList(anyString(), eq(String.class), any(Object[].class)))
                .thenReturn(List.of("AAPL", "MSFT", "GOOG"));
        when(persistenceService.findIncompleteRun(3)).thenReturn(Optional.of(existingRunId));
        when(lineReader.get()).thenReturn("n");
        when(backtestEngine.run(any(BacktestConfig.class))).thenReturn(reportWithTrades(List.of()));

        runner.run(args);

        // Engine must be called with ALL tickers (fresh run)
        ArgumentCaptor<BacktestConfig> captor = ArgumentCaptor.forClass(BacktestConfig.class);
        verify(backtestEngine).run(captor.capture());
        assertThat(captor.getValue().tickers()).containsExactlyInAnyOrder("AAPL", "MSFT", "GOOG");

        // getCompletedTickers must NOT be called (we're starting fresh)
        verify(persistenceService, never()).getCompletedTickers(anyString());
    }

    // -------------------------------------------------------------------------
    // T4 — persistTickerResult called once per distinct ticker via callback
    //
    // Persist now happens mid-run via onTickerComplete callback, not after.
    // Engine mock invokes the callback for each ticker, simulating what the real
    // engine does. persistTickerResult must be called once per ticker.
    // -------------------------------------------------------------------------

    @Test
    void afterEngineRun_persistsEachTickerFromReport() throws Exception {
        var args = new DefaultApplicationArguments("--backtest-all");

        List<TradeRecord> aaplTrades = List.of(trade("AAPL"), trade("AAPL"));
        List<TradeRecord> msftTrades = List.of(trade("MSFT"));

        when(readJdbc.queryForList(anyString(), eq(String.class), any(Object[].class)))
                .thenReturn(List.of("AAPL", "MSFT"));
        when(persistenceService.findIncompleteRun(anyInt())).thenReturn(Optional.empty());

        // Engine invokes the callback per ticker (as the real engine does), then returns a report
        when(backtestEngine.run(any(BacktestConfig.class))).thenAnswer(invocation -> {
            BacktestConfig config = invocation.getArgument(0);
            if (config.onTickerComplete() != null) {
                config.onTickerComplete().accept("AAPL", aaplTrades);
                config.onTickerComplete().accept("MSFT", msftTrades);
            }
            List<TradeRecord> allTrades = new ArrayList<>();
            allTrades.addAll(aaplTrades);
            allTrades.addAll(msftTrades);
            return reportWithTrades(allTrades);
        });

        runner.run(args);

        // persistTickerResult called twice (once per ticker, via callback)
        ArgumentCaptor<String> tickerCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<List> tradesCaptor = ArgumentCaptor.forClass(List.class);
        verify(persistenceService, times(2)).persistTickerResult(
                anyString(),
                tickerCaptor.capture(),
                anyString(),
                tradesCaptor.capture());

        assertThat(tickerCaptor.getAllValues()).containsExactlyInAnyOrder("AAPL", "MSFT");

        // AAPL call must receive 2 trades, MSFT call must receive 1
        List<String> capturedTickers = tickerCaptor.getAllValues();
        List<List> capturedTrades = tradesCaptor.getAllValues();
        for (int i = 0; i < capturedTickers.size(); i++) {
            String t = capturedTickers.get(i);
            List<?> tl = capturedTrades.get(i);
            if ("AAPL".equals(t)) {
                assertThat(tl).hasSize(2);
            } else if ("MSFT".equals(t)) {
                assertThat(tl).hasSize(1);
            }
        }
    }

    // -------------------------------------------------------------------------
    // T5 — initSchema is called at the start of every backtest-all run
    // -------------------------------------------------------------------------

    @Test
    void run_alwaysCallsInitSchema() throws Exception {
        var args = new DefaultApplicationArguments("--backtest-all");

        when(readJdbc.queryForList(anyString(), eq(String.class), any(Object[].class)))
                .thenReturn(List.of("AAPL"));
        when(persistenceService.findIncompleteRun(anyInt())).thenReturn(Optional.empty());
        when(backtestEngine.run(any(BacktestConfig.class))).thenReturn(reportWithTrades(List.of()));

        runner.run(args);

        verify(persistenceService).initSchema();
    }

    // -------------------------------------------------------------------------
    // T6 — --backtest-resume flag: resumes without prompting lineReader
    // -------------------------------------------------------------------------

    @Test
    void whenBacktestResumeFlagPresent_resumesWithoutPrompt() throws Exception {
        var args = new DefaultApplicationArguments("--backtest-all", "--backtest-resume");
        String existingRunId = "2025-01-01_10-00";

        when(readJdbc.queryForList(anyString(), eq(String.class), any(Object[].class)))
                .thenReturn(List.of("AAPL", "MSFT", "GOOG"));
        when(persistenceService.findIncompleteRun(3)).thenReturn(Optional.of(existingRunId));
        when(persistenceService.getCompletedTickers(existingRunId)).thenReturn(Set.of("AAPL"));
        when(backtestEngine.run(any(BacktestConfig.class))).thenReturn(reportWithTrades(List.of()));

        runner.run(args);

        // lineReader must NEVER be called when the flag is present
        verify(lineReader, never()).get();

        // Engine must be called WITHOUT AAPL (already completed)
        ArgumentCaptor<BacktestConfig> captor = ArgumentCaptor.forClass(BacktestConfig.class);
        verify(backtestEngine).run(captor.capture());
        assertThat(captor.getValue().tickers())
                .containsExactlyInAnyOrder("MSFT", "GOOG")
                .doesNotContain("AAPL");
    }

    // -------------------------------------------------------------------------
    // T7 — --backtest-fresh flag: starts fresh without prompting lineReader
    // -------------------------------------------------------------------------

    @Test
    void whenBacktestFreshFlagPresent_startsFreshWithoutPrompt() throws Exception {
        var args = new DefaultApplicationArguments("--backtest-all", "--backtest-fresh");
        String existingRunId = "2025-01-01_10-00";

        when(readJdbc.queryForList(anyString(), eq(String.class), any(Object[].class)))
                .thenReturn(List.of("AAPL", "MSFT", "GOOG"));
        when(persistenceService.findIncompleteRun(3)).thenReturn(Optional.of(existingRunId));
        when(backtestEngine.run(any(BacktestConfig.class))).thenReturn(reportWithTrades(List.of()));

        runner.run(args);

        // lineReader must NEVER be called when the flag is present
        verify(lineReader, never()).get();

        // Engine must be called with ALL tickers (fresh run ignores completed)
        ArgumentCaptor<BacktestConfig> captor = ArgumentCaptor.forClass(BacktestConfig.class);
        verify(backtestEngine).run(captor.capture());
        assertThat(captor.getValue().tickers())
                .containsExactlyInAnyOrder("AAPL", "MSFT", "GOOG");

        // getCompletedTickers must NOT be called (we're not resuming)
        verify(persistenceService, never()).getCompletedTickers(anyString());
    }

    // -------------------------------------------------------------------------
    // T8 — no flag + stdin unavailable: defaults to fresh run without crash
    // -------------------------------------------------------------------------

    @Test
    void whenNoFlagAndStdinUnavailable_defaultsToFresh() throws Exception {
        var args = new DefaultApplicationArguments("--backtest-all");
        String existingRunId = "2025-01-01_10-00";

        when(readJdbc.queryForList(anyString(), eq(String.class), any(Object[].class)))
                .thenReturn(List.of("AAPL", "MSFT", "GOOG"));
        when(persistenceService.findIncompleteRun(3)).thenReturn(Optional.of(existingRunId));
        // Simulate stdin not available — lineReader throws NoSuchElementException
        when(lineReader.get()).thenThrow(new java.util.NoSuchElementException("No line found"));
        when(backtestEngine.run(any(BacktestConfig.class))).thenReturn(reportWithTrades(List.of()));

        // Must NOT throw
        runner.run(args);

        // Engine must be called with ALL tickers (fallback = fresh)
        ArgumentCaptor<BacktestConfig> captor = ArgumentCaptor.forClass(BacktestConfig.class);
        verify(backtestEngine).run(captor.capture());
        assertThat(captor.getValue().tickers())
                .containsExactlyInAnyOrder("AAPL", "MSFT", "GOOG");
    }
}
