package com.fgiaquinta.optionsquant.backtest;

import com.fgiaquinta.optionsquant.backtest.domain.BacktestConfig;
import com.fgiaquinta.optionsquant.backtest.domain.BacktestReport;
import com.fgiaquinta.optionsquant.backtest.engine.BacktestEngine;
import com.fgiaquinta.optionsquant.candle.CandleRepository;
import com.fgiaquinta.optionsquant.domain.Candle;
import com.fgiaquinta.optionsquant.domain.TimeFrame;
import com.fgiaquinta.optionsquant.service.TickerMemory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T15 — BacktestEngine wired to CandleRepository.
 *
 * Verifies that BacktestEngine delegates candle loading to CandleRepository.stream()
 * (lazy load, T20 upgrade) and NOT to CandleCsvService.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BacktestEngineRepositoryWiringTest {

    private static final LocalDate DATE = LocalDate.of(2026, 4, 22);

    @Mock
    private CandleRepository candleRepository;

    @Mock
    private TickerMemory tickerMemory;

    private BacktestEngine engine;

    @BeforeEach
    void setUp() {
        // Each call must return a fresh stream — streams are single-use
        when(candleRepository.stream(any(), any())).thenAnswer(inv -> Stream.empty());
        engine = new BacktestEngine(candleRepository, tickerMemory, 1, false);
    }

    @Test
    @DisplayName("T15: BacktestEngine injects CandleRepository — not CandleCsvService")
    void backtestEngine_acceptsCandleRepositoryInConstructor() {
        assertThat(engine).isNotNull();
    }

    @Test
    @DisplayName("T15: BacktestEngine delegates candle loading to CandleRepository.stream()")
    void backtestEngine_delegatesStreamToCandleRepository() {
        BacktestConfig config = BacktestConfig.defaults(List.of("SPY"), DATE, DATE);

        engine.run(config, false, null);

        verify(candleRepository, atLeastOnce()).stream(eq("SPY"), any(TimeFrame.class));
    }

    @Test
    @DisplayName("T15: BacktestEngine returns non-null report even when repository returns empty streams")
    void backtestEngine_returnsReport_whenRepositoryIsEmpty() {
        BacktestConfig config = BacktestConfig.defaults(List.of("AAPL"), DATE, DATE);

        BacktestReport report = engine.run(config, false, null);

        assertThat(report).isNotNull();
        assertThat(report.initialCapital()).isEqualTo(config.initialCapital());
        assertThat(report.totalTrades()).isZero();
    }

    @Test
    @DisplayName("T15: BacktestEngine streams all 4 timeframes per ticker via CandleRepository")
    void backtestEngine_streamsAllTimeframesPerTicker() {
        BacktestConfig config = BacktestConfig.defaults(List.of("TSLA"), DATE, DATE);

        engine.run(config, false, null);

        for (TimeFrame tf : TimeFrame.values()) {
            verify(candleRepository, atLeastOnce()).stream("TSLA", tf);
        }
    }

    @Test
    @DisplayName("T15: BacktestEngine processes candles returned by CandleRepository.stream()")
    void backtestEngine_processesReturnedCandles() {
        ZonedDateTime ts = DATE.atTime(14, 30).atZone(ZoneOffset.UTC);
        Candle candle = new Candle(ts, 100.0, 105.0, 99.0, 103.0, 1000L);
        when(candleRepository.stream(any(), any())).thenAnswer(inv -> Stream.of(candle));

        BacktestConfig config = BacktestConfig.defaults(List.of("MSFT"), DATE, DATE);
        BacktestReport report = engine.run(config, false, null);

        assertThat(report).isNotNull();
    }
}
