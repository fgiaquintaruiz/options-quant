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
 * Verifica que SignalQualityFilter.passesCoreChecks() fue removido del path live/replay.
 *
 * Escenario: estrategia triggerea, pero el volumen del candle 1h es MUY BAJO (ratio < 0.7).
 * Con el filtro activo → la señal es bloqueada.
 * Sin el filtro → la señal debe emitirse igual.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StrategyScannerServiceSignalQualityFilterTest {

    private static final ZoneId NY = ZoneId.of("America/New_York");

    // virtualNow is AFTER all candles so idx1h > 0 and the quality filter RUNS
    // Candles: 20 bars of 1h starting at 2024-01-10 09:30 → last bar ends 2024-01-11 04:30
    // VIRTUAL_NOW = 2024-01-15 10:30 → after all candles → idx1h = 19 (last bar index)
    private static final ZonedDateTime VIRTUAL_NOW =
            ZonedDateTime.of(2024, 1, 15, 10, 30, 0, 0, NY);
    private static final ZonedDateTime CANDLE_BASE =
            ZonedDateTime.of(2024, 1, 10, 9, 30, 0, 0, NY);

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
        // @PostConstruct doesn't run without Spring; stubs are needed only if code path hits them
        when(scannerProperties.concurrentMode()).thenReturn(ScannerProperties.ConcurrentMode.FIXED);
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

        // replay clock active — virtualNow is INSIDE the candle window (idx1h > 0)
        when(replayClock.isActive()).thenReturn(true);
        when(replayClock.getNow()).thenReturn(VIRTUAL_NOW);

        // Build 20 candles starting BEFORE virtualNow, with LOW volume on last bar
        // Normal candles have volume=1000; last candle has volume=50 (ratio << 0.7 vs avg)
        List<Candle> candles = buildCandlesWithLowVolumeAtEnd(CANDLE_BASE, 20);
        when(replayCandleSource.getCandlesUntil(anyString(), any(TimeFrame.class), any()))
                .thenReturn(candles);

        // ticker not blocked, no earnings
        when(tickerMemory.isBlocked(anyString())).thenReturn(false);
        when(earningsService.hasEarningsSoon(anyString(), anyInt())).thenReturn(false);

        // strategy enabled and triggers
        when(mockStrategy.getName()).thenReturn("c3 bounce call");
        when(strategyConfigService.isLiveEnabled("c3 bounce call")).thenReturn(true);
        when(mockStrategy.isTriggered(anyString(), any(StrategyData.class), any())).thenReturn(true);

        // pattern memory allows it
        when(tickerMemory.isPatternAllowed(anyString(), anyString(), anyString())).thenReturn(true);
    }

    @Test
    @DisplayName("signal emitted even when 1h vol ratio < 0.7 — SignalQualityFilter removed from path")
    void signalEmitted_whenLowVolume_qualityFilterNotApplied() {
        // Given: strategy triggers but last 1h candle has volume << 0.7× avg
        // (previously blocked by SignalQualityFilter.passesCoreChecks vol check)

        // When
        List<StrategyScannerService.Signal> signals =
                service.scanTicker("AAPL", false, false);

        // Then: signal must be emitted — quality filter must NOT be blocking it
        assertThat(signals)
                .as("Expected signal to be emitted even with low volume; " +
                    "SignalQualityFilter.passesCoreChecks must be removed from scanTicker path")
                .isNotEmpty();
    }

    /**
     * Builds candles where the last one has extremely low volume (50 vs 1000 avg).
     * vol ratio = 50/1000 = 0.05 — well below the 0.7 threshold in passesCoreChecks.
     *
     * All candles close upward (bullish body > 25% of range) so only the volume
     * check causes the filter to block when active.
     */
    private static List<Candle> buildCandlesWithLowVolumeAtEnd(ZonedDateTime base, int count) {
        List<Candle> list = new ArrayList<>();
        for (int i = 0; i < count - 1; i++) {
            // Normal candle: open 100, high 102, low 99, close 101.5 — bullish, vol 1000
            list.add(new Candle(base.plusHours(i), 100.0, 102.0, 99.0, 101.5, 1000));
        }
        // Last candle at VIRTUAL_NOW - 1h: same bullish shape but volume = 50 (tiny)
        list.add(new Candle(base.plusHours(count - 1), 100.0, 102.0, 99.0, 101.5, 50));
        return list;
    }
}
