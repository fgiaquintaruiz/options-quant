package com.fgiaquinta.optionsquant.service;

import com.fgiaquinta.optionsquant.candle.CandleRepository;
import com.fgiaquinta.optionsquant.config.IbkrProperties;
import com.fgiaquinta.optionsquant.config.ScannerProperties;
import com.fgiaquinta.optionsquant.domain.Candle;
import com.fgiaquinta.optionsquant.domain.TimeFrame;
import com.fgiaquinta.optionsquant.strategy.TimeframeRequirements;
import com.fgiaquinta.optionsquant.strategy.config.StrategyConfigService;
import com.fgiaquinta.optionsquant.strategy.TradingStrategy;
import com.fgiaquinta.optionsquant.strategy.data.StrategyData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.ZonedDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the timeframe-requirements gating logic of
 * {@link StrategyScannerService#applicableStrategies(StrategyData, List)}:
 * a strategy is included iff every timeframe it declares via
 * {@link TimeframeRequirements#requiredTimeframes()} is loaded.
 *
 * <p>This is the core decision point that decouples ticker viability from
 * strategy viability — the OLD scanner aborted the entire ticker when any
 * timeframe was missing, the NEW scanner only skips the affected strategies.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StrategyScannerServiceTimeframeRequirementsTest {

    private static final ZoneId NY = ZoneId.of("America/New_York");
    private static final ZonedDateTime BASE = ZonedDateTime.of(2026, 4, 6, 10, 0, 0, 0, NY);

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

    private StrategyScannerService service;

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.when(scannerProperties.concurrentMode())
                .thenReturn(ScannerProperties.ConcurrentMode.FIXED);
        org.mockito.Mockito.when(scannerProperties.fixedMaxConcurrent()).thenReturn(1);

        service = new StrategyScannerService(
                candleRepository, ibkrService, ibkrProperties,
                tickerService, tickerMemory, earningsService,
                newsBiasService, marketCalendar,
                scannerProperties, scanPrioritizationService,
                strategyConfigService);
    }

    private List<Candle> sampleCandles() {
        List<Candle> list = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            list.add(new Candle(BASE.plusMinutes(i), 100, 101, 99, 100, 1000));
        }
        return list;
    }

    private StrategyData dataWith(TimeFrame... timeframes) {
        Map<TimeFrame, List<Candle>> map = new EnumMap<>(TimeFrame.class);
        for (TimeFrame tf : timeframes) {
            map.put(tf, sampleCandles());
        }
        return new StrategyData(map);
    }

    @Test
    @DisplayName("applicableStrategies returns all 12 when full data is present")
    void allTwelveRunWhenFullDataAvailable() {
        StrategyData full = dataWith(TimeFrame.MIN_5, TimeFrame.MIN_15,
                TimeFrame.HOUR_1, TimeFrame.DAY_1);

        List<TradingStrategy> applicable = service.applicableStrategies(
                full, service.getAllStrategies());

        assertThat(applicable).hasSize(12);
    }

    @Test
    @DisplayName("applicableStrategies excludes C4/P4 when MIN_5 missing, keeps the other 10")
    void excludesOpeningWhenMin5Missing() {
        // 15m + 1h + 1d but NO 5m
        StrategyData partial = dataWith(TimeFrame.MIN_15, TimeFrame.HOUR_1, TimeFrame.DAY_1);

        List<TradingStrategy> applicable = service.applicableStrategies(
                partial, service.getAllStrategies());

        assertThat(applicable).hasSize(10);
        assertThat(applicable.stream().map(s -> s.getClass().getSimpleName()).toList())
                .doesNotContain("C4OpeningCallStrategy", "P4OpeningPutStrategy");
    }

    @Test
    @DisplayName("applicableStrategies returns only Squeeze (C1/P1) when only MIN_15+HOUR_1 are loaded")
    void onlySqueezeWhenMin15AndHour1Present() {
        StrategyData minimal = dataWith(TimeFrame.MIN_15, TimeFrame.HOUR_1);

        List<TradingStrategy> applicable = service.applicableStrategies(
                minimal, service.getAllStrategies());

        assertThat(applicable.stream().map(s -> s.getClass().getSimpleName()).toList())
                .containsExactlyInAnyOrder("C1SqueezeCallStrategy", "P1SqueezePutStrategy");
    }

    @Test
    @DisplayName("applicableStrategies is empty when only DAY_1 is loaded — no strategy fits")
    void emptyWhenOnlyDay1Present() {
        StrategyData onlyDay = dataWith(TimeFrame.DAY_1);

        List<TradingStrategy> applicable = service.applicableStrategies(
                onlyDay, service.getAllStrategies());

        assertThat(applicable).isEmpty();
    }

    @Test
    @DisplayName("applicableStrategies returns only C4/P4 when only MIN_5+MIN_15 present (perfect Opening data)")
    void onlyOpeningWhenMin5AndMin15Present() {
        StrategyData openingData = dataWith(TimeFrame.MIN_5, TimeFrame.MIN_15);

        List<TradingStrategy> applicable = service.applicableStrategies(
                openingData, service.getAllStrategies());

        assertThat(applicable.stream().map(s -> s.getClass().getSimpleName()).toList())
                .containsExactlyInAnyOrder("C4OpeningCallStrategy", "P4OpeningPutStrategy");
    }
}
