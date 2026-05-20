package com.fgiaquinta.optionsquant.service;

import com.fgiaquinta.optionsquant.candle.CandleRepository;
import com.fgiaquinta.optionsquant.config.IbkrProperties;
import com.fgiaquinta.optionsquant.config.ScannerProperties;
import com.fgiaquinta.optionsquant.domain.Candle;
import com.fgiaquinta.optionsquant.domain.TimeFrame;
import com.fgiaquinta.optionsquant.strategy.TradingStrategy;
import com.fgiaquinta.optionsquant.strategy.config.StrategyConfigService;
import com.fgiaquinta.optionsquant.strategy.data.StrategyData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Verifies that during replay mode, generated signals carry the virtual clock
 * time (replayClock.getNow()) as their timestamp — NOT the historical candle
 * close time returned by getLatestTimestamp(data).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StrategyScannerServiceReplayClockTimestampTest {

    private static final ZoneId NY = ZoneId.of("America/New_York");

    // virtualNow is earlier than candle timestamps so idx1h == -1 (quality filter skipped)
    private static final ZonedDateTime VIRTUAL_NOW =
            ZonedDateTime.of(2024, 1, 15, 10, 30, 0, 0, NY);
    private static final ZonedDateTime CANDLE_TIME =
            ZonedDateTime.of(2024, 1, 15, 11, 0, 0, 0, NY);

    @Mock private CandleRepository candleRepository;
    @Mock private IbkrService ibkrService;
    @Mock private IbkrProperties ibkrProperties;
    @Mock private TickerService tickerService;
    @Mock private TickerMemory tickerMemory;
    @Mock private EarningsDateService earningsService;
    @Mock private NewsBiasService newsBiasService;
    @Mock private MarketCalendarService marketCalendar;
    @Mock private ScannerProperties scannerProperties;
    @Mock private ScanPrioritizationService scanPrioritizationService;
    @Mock private StrategyConfigService strategyConfigService;
    @Mock private ReplayClock replayClock;
    @Mock private ReplayCandleSource replayCandleSource;
    @Mock private TradingStrategy mockStrategy;

    private StrategyScannerService service;

    @BeforeEach
    void setUp() {
        when(scannerProperties.concurrentMode())
                .thenReturn(ScannerProperties.ConcurrentMode.FIXED);
        when(scannerProperties.fixedMaxConcurrent()).thenReturn(1);

        service = new StrategyScannerService(
                candleRepository, ibkrService, ibkrProperties,
                tickerService, tickerMemory, earningsService,
                newsBiasService, marketCalendar,
                scannerProperties, scanPrioritizationService,
                strategyConfigService,
                Optional.of(replayClock), Optional.of(replayCandleSource));

        ReflectionTestUtils.setField(service, "callStrategies", List.of(mockStrategy));
        ReflectionTestUtils.setField(service, "putStrategies", List.of());

        // replay clock is active and returns virtualNow
        when(replayClock.isActive()).thenReturn(true);
        when(replayClock.getNow()).thenReturn(VIRTUAL_NOW);

        // candles all end AFTER virtualNow so idx1h == -1 → quality filter bypassed
        List<Candle> candles = buildCandles(CANDLE_TIME, 5);
        when(replayCandleSource.getCandlesUntil(anyString(), any(TimeFrame.class), any()))
                .thenReturn(candles);

        // ticker is not blocked, no earnings
        when(tickerMemory.isBlocked(anyString())).thenReturn(false);
        when(earningsService.hasEarningsSoon(anyString(), anyInt())).thenReturn(false);

        // strategy is enabled and triggers
        when(mockStrategy.getName()).thenReturn("c3 bounce call");
        when(strategyConfigService.isLiveEnabled("c3 bounce call")).thenReturn(true);
        when(mockStrategy.isTriggered(anyString(), any(StrategyData.class), any())).thenReturn(true);

        // pattern memory allows the signal
        when(tickerMemory.isPatternAllowed(anyString(), anyString(), anyString())).thenReturn(true);
    }

    @Test
    @DisplayName("replay: signal timestamp must equal virtualNow, not candle close time")
    void signalTimestamp_equalsVirtualNow_duringReplay() {
        List<StrategyScannerService.Signal> signals =
                service.scanTicker("AAPL", false, false);

        assertThat(signals).isNotEmpty();
        assertThat(signals.get(0).timestamp())
                .isEqualTo(VIRTUAL_NOW);
    }

    private static List<Candle> buildCandles(ZonedDateTime base, int count) {
        List<Candle> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(new Candle(base.plusHours(i), 100, 101, 99, 100, 1000));
        }
        return list;
    }
}
