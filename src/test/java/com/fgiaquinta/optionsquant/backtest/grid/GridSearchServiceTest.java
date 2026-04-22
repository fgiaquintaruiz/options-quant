package com.fgiaquinta.optionsquant.backtest.grid;

import com.fgiaquinta.optionsquant.backtest.domain.BacktestConfig;
import com.fgiaquinta.optionsquant.backtest.domain.BacktestReport;
import com.fgiaquinta.optionsquant.backtest.engine.BacktestEngine;
import com.fgiaquinta.optionsquant.config.GridSearchProperties;
import com.fgiaquinta.optionsquant.domain.TimeFrame;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.assertj.core.groups.Tuple;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GridSearchServiceTest {

    @Mock
    private BacktestEngine backtestEngine;

    private GridSearchProperties properties;
    private GridSearchService gridSearchService;

    @BeforeEach
    void setUp() {
        properties = new GridSearchProperties();
        properties.setMaxCells(500);
        properties.setTimeoutMs(0);
        gridSearchService = new GridSearchService(backtestEngine, properties);
    }

    private static GridSearchRequest baseRequest(List<GridAxis> axes) {
        return new GridSearchRequest(
                List.of("SPY"),
                LocalDate.of(2025, 1, 1),
                LocalDate.of(2025, 6, 1),
                50_000, 0.02, 0.005, 0.65,
                3, TimeFrame.MIN_15, true, false,
                axes,
                null, null, null, null, null, null,
                null, null, null);
    }

    @Test
    @DisplayName("2×2 grid runs four backtests with distinct TP/SL delta pairs")
    void grid2x2RunsFourCells() {
        when(backtestEngine.run(any(BacktestConfig.class), eq(false), isNull())).thenReturn(dummyReport());

        GridSearchRequest req = baseRequest(List.of(
                new GridAxis("tpMultiplierDelta", List.of(0.0, 0.1)),
                new GridAxis("slMultiplierDelta", List.of(0.0, 0.2))));

        GridSearchResult result = gridSearchService.run(req);
        assertThat(result.success()).isTrue();
        assertThat(result.requestedCells()).isEqualTo(4);
        assertThat(result.completedCells()).isEqualTo(4);
        assertThat(result.rows()).hasSize(4);
        assertThat(result.csvPath()).isNotNull();
        assertThat(result.optimization()).isNotNull();
        assertThat(result.optimization().hasWinner()).isTrue();
        verify(backtestEngine, times(4)).run(any(BacktestConfig.class), eq(false), isNull());

        ArgumentCaptor<BacktestConfig> cap = ArgumentCaptor.forClass(BacktestConfig.class);
        verify(backtestEngine, times(4)).run(cap.capture(), eq(false), isNull());
        assertThat(cap.getAllValues())
                .extracting(BacktestConfig::tpMultiplierDelta, BacktestConfig::slMultiplierDelta)
                .containsExactlyInAnyOrder(
                        Tuple.tuple(0.0, 0.0),
                        Tuple.tuple(0.0, 0.2),
                        Tuple.tuple(0.1, 0.0),
                        Tuple.tuple(0.1, 0.2)
                );
    }

    @Test
    @DisplayName("Rejects grid larger than max-cells")
    void rejectsExcessCells() {
        properties.setMaxCells(3);
        gridSearchService = new GridSearchService(backtestEngine, properties);

        GridSearchRequest req = baseRequest(List.of(
                new GridAxis("tpMultiplierDelta", List.of(0.0, 0.1)),
                new GridAxis("slMultiplierDelta", List.of(0.0, 0.2))));

        assertThatThrownBy(() -> gridSearchService.run(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("4 cells")
                .hasMessageContaining("maximum is 3");
    }

    @Test
    @DisplayName("cartesian product size matches countCells")
    void cartesianMatchesCount() {
        List<GridAxis> axes = List.of(
                new GridAxis("tpMultiplierDelta", List.of(0.0, 0.1)),
                new GridAxis("slMultiplierDelta", List.of(0.0, 0.2))
        );
        assertThat(GridSearchService.countCells(axes)).isEqualTo(4);
        assertThat(GridSearchService.cartesian(axes)).hasSize(4);
    }

    @Test
    @DisplayName("Timeout stops after first slow cell")
    void timeoutStopsEarly() throws Exception {
        properties.setTimeoutMs(15);
        gridSearchService = new GridSearchService(backtestEngine, properties);

        when(backtestEngine.run(any(BacktestConfig.class), eq(false), isNull())).thenAnswer(inv -> {
            Thread.sleep(40);
            return dummyReport();
        });

        GridSearchRequest req = baseRequest(List.of(
                new GridAxis("tpMultiplierDelta", List.of(0.0, 0.1)),
                new GridAxis("slMultiplierDelta", List.of(0.0, 0.2))));

        GridSearchResult result = gridSearchService.run(req);
        assertThat(result.success()).isFalse();
        assertThat(result.completedCells()).isEqualTo(1);
        assertThat(result.message()).contains("timeout");
    }

    @Test
    @DisplayName("RANDOM mode runs N sampled cells without replacement")
    void randomModeSamplesSubset() {
        when(backtestEngine.run(any(BacktestConfig.class), eq(false), isNull())).thenReturn(dummyReport());

        GridSearchRequest req = new GridSearchRequest(
                List.of("SPY"),
                LocalDate.of(2025, 1, 1),
                LocalDate.of(2025, 6, 1),
                50_000, 0.02, 0.005, 0.65,
                3, TimeFrame.MIN_15, true, false,
                List.of(
                        new GridAxis("tpMultiplierDelta", List.of(0.0, 0.1)),
                        new GridAxis("slMultiplierDelta", List.of(0.0, 0.2))
                ),
                "RANDOM",
                2,
                42L,
                null, null, null,
                null, null, null);

        GridSearchResult result = gridSearchService.run(req);
        assertThat(result.success()).isTrue();
        assertThat(result.requestedCells()).isEqualTo(2);
        verify(backtestEngine, times(2)).run(any(BacktestConfig.class), eq(false), isNull());
    }

    @Test
    @DisplayName("Walk-forward runs in-sample grid then OOS backtest per rolling fold")
    void walkForwardIsThenOos() {
        when(backtestEngine.run(any(BacktestConfig.class), eq(false), isNull())).thenReturn(dummyReport());

        GridSearchRequest req = new GridSearchRequest(
                List.of("SPY"),
                LocalDate.of(2025, 1, 1),
                LocalDate.of(2025, 6, 30),
                50_000, 0.02, 0.005, 0.65,
                3, TimeFrame.MIN_15, true, false,
                List.of(
                        new GridAxis("tpMultiplierDelta", List.of(0.0, 0.1)),
                        new GridAxis("slMultiplierDelta", List.of(0.0, 0.2))
                ),
                null, null, null, null, null, null,
                30, 14, 14);

        GridSearchResult result = gridSearchService.run(req);
        assertThat(result.success()).isTrue();
        assertThat(result.walkForwardFolds()).isNotNull();
        assertThat(result.walkForwardFolds()).isNotEmpty();
        assertThat(result.walkForwardSummary()).isNotNull();
        assertThat(result.walkForwardSummary().foldsWithOosBacktest()).isPositive();
        assertThat(result.optimization()).isNull();
        assertThat(result.rows()).isEmpty();

        int foldsWithOos = result.walkForwardSummary().foldsWithOosBacktest();
        assertThat(foldsWithOos).isGreaterThanOrEqualTo(2);
        int cellsPerFold = 4;
        verify(backtestEngine, atLeast(foldsWithOos * cellsPerFold + foldsWithOos)).run(any(BacktestConfig.class), eq(false), isNull());
    }

    @Test
    @DisplayName("Walk-forward rejects RANDOM searchMode")
    void walkForwardRejectsRandom() {
        GridSearchRequest req = new GridSearchRequest(
                List.of("SPY"),
                LocalDate.of(2025, 1, 1),
                LocalDate.of(2025, 12, 31),
                50_000, 0.02, 0.005, 0.65,
                3, TimeFrame.MIN_15, true, false,
                List.of(
                        new GridAxis("tpMultiplierDelta", List.of(0.0)),
                        new GridAxis("slMultiplierDelta", List.of(0.0))
                ),
                "RANDOM",
                1,
                1L,
                null, null, null,
                30, 14, null);

        assertThatThrownBy(() -> gridSearchService.run(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("walk-forward");
    }

    @Test
    @DisplayName("Walk-forward rejects train days without test days")
    void walkForwardRejectsPartialWindowFields() {
        GridSearchRequest req = new GridSearchRequest(
                List.of("SPY"),
                LocalDate.of(2025, 1, 1),
                LocalDate.of(2025, 12, 31),
                50_000, 0.02, 0.005, 0.65,
                3, TimeFrame.MIN_15, true, false,
                List.of(
                        new GridAxis("tpMultiplierDelta", List.of(0.0)),
                        new GridAxis("slMultiplierDelta", List.of(0.0))
                ),
                null, null, null, null, null, null,
                30, null, null);

        assertThatThrownBy(() -> gridSearchService.run(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("walkForwardTrainDays and walkForwardTestDays");
    }

    @Test
    @DisplayName("Walk-forward rejects date span shorter than train+test")
    void walkForwardRejectsTooShortCalendarSpan() {
        GridSearchRequest req = new GridSearchRequest(
                List.of("SPY"),
                LocalDate.of(2025, 1, 1),
                LocalDate.of(2025, 1, 25),
                50_000, 0.02, 0.005, 0.65,
                3, TimeFrame.MIN_15, true, false,
                List.of(
                        new GridAxis("tpMultiplierDelta", List.of(0.0)),
                        new GridAxis("slMultiplierDelta", List.of(0.0))
                ),
                null, null, null, null, null, null,
                30, 14, null);

        assertThatThrownBy(() -> gridSearchService.run(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Date range too short");
    }

    @Test
    @DisplayName("Walk-forward rejects step days < 1 when set")
    void walkForwardRejectsInvalidStep() {
        GridSearchRequest req = new GridSearchRequest(
                List.of("SPY"),
                LocalDate.of(2025, 1, 1),
                LocalDate.of(2025, 12, 31),
                50_000, 0.02, 0.005, 0.65,
                3, TimeFrame.MIN_15, true, false,
                List.of(
                        new GridAxis("tpMultiplierDelta", List.of(0.0)),
                        new GridAxis("slMultiplierDelta", List.of(0.0))
                ),
                null, null, null, null, null, null,
                30, 14, 0);

        assertThatThrownBy(() -> gridSearchService.run(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("walkForwardStepDays");
    }

    @Test
    @DisplayName("Empty tickers rejected at validation")
    void emptyTickersRejected() {
        GridSearchRequest req = new GridSearchRequest(
                List.of(),
                LocalDate.of(2025, 1, 1),
                LocalDate.of(2025, 2, 1),
                50_000, 0.02, 0.005, 0.65,
                3, TimeFrame.MIN_15, true, false,
                List.of(
                        new GridAxis("tpMultiplierDelta", List.of(0.0)),
                        new GridAxis("slMultiplierDelta", List.of(0.0))
                ),
                null, null, null, null, null, null,
                null, null, null);

        assertThatThrownBy(() -> gridSearchService.run(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tickers");
    }

    @Test
    @DisplayName("constraintMinTrades can exclude all cells from optimization winner")
    void constraintsNoWinner() {
        when(backtestEngine.run(any(BacktestConfig.class), eq(false), isNull())).thenReturn(dummyReport());

        GridSearchRequest req = new GridSearchRequest(
                List.of("SPY"),
                LocalDate.of(2025, 1, 1),
                LocalDate.of(2025, 6, 1),
                50_000, 0.02, 0.005, 0.65,
                3, TimeFrame.MIN_15, true, false,
                List.of(
                        new GridAxis("tpMultiplierDelta", List.of(0.0)),
                        new GridAxis("slMultiplierDelta", List.of(0.0))
                ),
                null, null, null,
                100,
                null,
                null,
                null, null, null);

        GridSearchResult result = gridSearchService.run(req);
        assertThat(result.optimization().hasWinner()).isFalse();
    }

    private static BacktestReport dummyReport() {
        return new BacktestReport(
                50_000, 51_000, 1_000, 0.02,
                10, 6, 4, 0.6, 1.5,
                800, 0.016, 1.0, 100, -80, 2.0,
                Map.of(), Map.of(), List.of(), List.of(), 50L
        );
    }
}
