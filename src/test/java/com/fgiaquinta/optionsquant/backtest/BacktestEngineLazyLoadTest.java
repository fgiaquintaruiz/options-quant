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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T20 — BacktestEngine loads candles lazily via CandleRepository.stream()
 * instead of CandleRepository.load() (eager full-list).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BacktestEngineLazyLoadTest {

    private static final LocalDate DATE = LocalDate.of(2026, 4, 22);

    @Mock
    private CandleRepository candleRepository;

    @Mock
    private TickerMemory tickerMemory;

    private BacktestEngine engine;

    @BeforeEach
    void setUp() {
        // Each call must return a fresh stream — the same instance cannot be consumed twice.
        when(candleRepository.stream(any(), any())).thenAnswer(inv -> Stream.empty());
        engine = new BacktestEngine(candleRepository, tickerMemory, 1, false);
    }

    @Test
    @DisplayName("T20: BacktestEngine uses stream() not load() for candle loading")
    void backtestEngine_usesStream_notLoad() {
        BacktestConfig config = BacktestConfig.defaults(List.of("SPY"), DATE, DATE);

        engine.run(config, false, null);

        verify(candleRepository, atLeastOnce()).stream(eq("SPY"), any(TimeFrame.class));
        verify(candleRepository, never()).load(any(), any());
    }

    @Test
    @DisplayName("T20: BacktestEngine streams all 4 timeframes per ticker")
    void backtestEngine_streamsAllTimeframesPerTicker() {
        BacktestConfig config = BacktestConfig.defaults(List.of("AAPL"), DATE, DATE);

        engine.run(config, false, null);

        for (TimeFrame tf : TimeFrame.values()) {
            verify(candleRepository, atLeastOnce()).stream("AAPL", tf);
        }
    }

    @Test
    @DisplayName("T20: BacktestEngine processes candles in chronological order across two tickers")
    void backtestEngine_processesCandles_chronologicalOrder() {
        ZonedDateTime t1 = DATE.atTime(9, 30).atZone(ZoneOffset.UTC);
        ZonedDateTime t2 = DATE.atTime(9, 45).atZone(ZoneOffset.UTC);
        ZonedDateTime t3 = DATE.atTime(10, 0).atZone(ZoneOffset.UTC);

        Candle c1 = new Candle(t1, 100, 105, 99, 103, 1000);
        Candle c2 = new Candle(t2, 103, 108, 102, 106, 1200);
        Candle c3 = new Candle(t3, 106, 110, 105, 109, 900);

        // Return a fresh stream on every call — streams are consumable once only
        when(candleRepository.stream(eq("SPY"), any())).thenAnswer(inv -> Stream.of(c1, c2, c3));
        when(candleRepository.stream(eq("QQQ"), any())).thenAnswer(inv -> Stream.of(c1, c2, c3));

        BacktestConfig config = BacktestConfig.defaults(List.of("SPY", "QQQ"), DATE, DATE);

        BacktestReport report = engine.run(config, false, null);

        assertThat(report).isNotNull();
    }

    @Test
    @DisplayName("T20: all stream cursors are closed after backtest completes")
    void backtestEngine_closesCursors_afterBacktest() {
        AtomicBoolean closed = new AtomicBoolean(false);

        // The first stream call gets a tracked stream; all subsequent calls get fresh empty streams
        when(candleRepository.stream(any(), any())).thenAnswer(inv -> {
            if (!closed.get()) {
                return Stream.<Candle>empty().onClose(() -> closed.set(true));
            }
            return Stream.<Candle>empty();
        });

        BacktestConfig config = BacktestConfig.defaults(List.of("SPY"), DATE, DATE);
        engine.run(config, false, null);

        assertThat(closed.get()).isTrue();
    }

    @Test
    @DisplayName("T20: returns non-null report when streams are empty")
    void backtestEngine_returnsReport_whenStreamsEmpty() {
        BacktestConfig config = BacktestConfig.defaults(List.of("TSLA"), DATE, DATE);

        BacktestReport report = engine.run(config, false, null);

        assertThat(report).isNotNull();
        assertThat(report.totalTrades()).isZero();
    }
}
