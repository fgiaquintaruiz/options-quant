package com.fgiaquinta.optionsquant.service;

import com.fgiaquinta.optionsquant.candle.CandleRepository;
import com.fgiaquinta.optionsquant.config.IbkrProperties;
import com.fgiaquinta.optionsquant.config.ScannerProperties;
import com.fgiaquinta.optionsquant.domain.TimeFrame;
import com.fgiaquinta.optionsquant.strategy.config.StrategyConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T18 — StrategyScannerService wired to CandleRepository.
 *
 * Verifies constructor injection of CandleRepository (interface) and
 * that candle loading delegates to repository.load().
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StrategyScannerServiceRepositoryWiringTest {

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
        when(scannerProperties.concurrentMode())
                .thenReturn(ScannerProperties.ConcurrentMode.FIXED);
        when(scannerProperties.fixedMaxConcurrent()).thenReturn(1);
        when(candleRepository.load(any(), any())).thenReturn(List.of());
        when(earningsService.hasEarningsSoon(any(), any(int.class))).thenReturn(false);
        when(tickerMemory.isBlocked(any())).thenReturn(false);

        service = new StrategyScannerService(
                candleRepository, ibkrService, ibkrProperties,
                tickerService, tickerMemory, earningsService,
                newsBiasService, marketCalendar,
                scannerProperties, scanPrioritizationService,
                strategyConfigService,
                Optional.empty(), Optional.empty());
    }

    @Test
    @DisplayName("T18: StrategyScannerService accepts CandleRepository in constructor")
    void scannerService_acceptsCandleRepositoryInConstructor() {
        assertThat(service).isNotNull();
    }

    @Test
    @DisplayName("T18: scanTicker delegates candle loading to CandleRepository.load()")
    void scanTicker_delegatesLoadToCandleRepository() {
        service.scanTicker("AAPL", false, false);

        verify(candleRepository, atLeastOnce()).load(any(), any(TimeFrame.class));
    }

    @Test
    @DisplayName("T18: getLastKnownPrice delegates to CandleRepository.load()")
    void getLastKnownPrice_delegatesLoadToCandleRepository() {
        when(candleRepository.load("SPY", TimeFrame.MIN_5)).thenReturn(List.of());
        when(candleRepository.load("SPY", TimeFrame.MIN_15)).thenReturn(List.of());

        double price = service.getLastKnownPrice("SPY");

        assertThat(price).isZero();
        verify(candleRepository, atLeastOnce()).load(any(), any(TimeFrame.class));
    }
}
