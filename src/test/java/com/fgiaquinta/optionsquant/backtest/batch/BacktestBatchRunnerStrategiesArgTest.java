package com.fgiaquinta.optionsquant.backtest.batch;

import com.fgiaquinta.optionsquant.backtest.domain.BacktestConfig;
import com.fgiaquinta.optionsquant.backtest.domain.BacktestReport;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * TDD — RED → GREEN for the --strategies CLI flag.
 *
 * <p>Covers three scenarios:
 * <ol>
 *   <li>--strategies=c4,p4  → BacktestConfig.strategyFilter contains ["c4", "p4"]</li>
 *   <li>--strategies=cX     → throws IllegalArgumentException mentioning "cX" and valid codes</li>
 *   <li>No --strategies flag → BacktestConfig.strategyFilter is null (run all)</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BacktestBatchRunnerStrategiesArgTest {

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
        // Use the legacy no-persistence test constructor; override exit() to prevent System.exit
        runner = new BacktestBatchRunner(backtestEngine, csvWriter, readJdbc) {
            @Override
            protected void exit(int code) { /* no-op */ }
        };

        // Stub: a single ticker returned from download_progress query
        when(readJdbc.queryForList(anyString(), eq(String.class), any(Object[].class)))
                .thenReturn(List.of("AAPL"));

        // Stub: engine returns empty report, captures config
        when(backtestEngine.run(any(BacktestConfig.class))).thenReturn(EMPTY_REPORT);

        // Stub: run metrics queries (COUNT and SUM)
        when(readJdbc.queryForObject(anyString(), eq(Long.class), anyString())).thenReturn(0L);
        when(readJdbc.queryForObject(anyString(), eq(Double.class), anyString())).thenReturn(0.0);
    }

    // -------------------------------------------------------------------------
    // T1 — --strategies=c4,p4 → filter passed through to BacktestConfig
    // -------------------------------------------------------------------------

    @Test
    void whenStrategiesFlagProvidedWithValidCodes_thenConfigContainsFilter() throws Exception {
        ArgumentCaptor<BacktestConfig> configCaptor = ArgumentCaptor.forClass(BacktestConfig.class);

        runner.run(new DefaultApplicationArguments(
                "--backtest-all", "--strategies=c4,p4"));

        org.mockito.Mockito.verify(backtestEngine).run(configCaptor.capture());
        BacktestConfig config = configCaptor.getValue();

        assertThat(config.strategyFilter())
                .containsExactlyInAnyOrder("c4", "p4");
    }

    // -------------------------------------------------------------------------
    // T2 — --strategies=cX → IllegalArgumentException with invalid code and valid list
    // -------------------------------------------------------------------------

    @Test
    void whenStrategiesFlagContainsInvalidCode_thenThrowsIllegalArgumentException() {
        // Input "cX" is lowercased to "cx" before validation — error message reports the normalized form.
        assertThatThrownBy(() ->
                runner.run(new DefaultApplicationArguments(
                        "--backtest-all", "--strategies=cX")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cx")  // normalized to lowercase before reporting
                .hasMessageContaining("c1"); // valid codes list must be present
    }

    // -------------------------------------------------------------------------
    // T3 — no --strategies flag → strategyFilter is null (run all)
    // -------------------------------------------------------------------------

    @Test
    void whenStrategiesFlagAbsent_thenConfigStrategyFilterIsNull() throws Exception {
        ArgumentCaptor<BacktestConfig> configCaptor = ArgumentCaptor.forClass(BacktestConfig.class);

        runner.run(new DefaultApplicationArguments("--backtest-all"));

        org.mockito.Mockito.verify(backtestEngine).run(configCaptor.capture());
        BacktestConfig config = configCaptor.getValue();

        assertThat(config.strategyFilter()).isNull();
    }
}
