package com.fgiaquinta.optionsquant.service;

import com.fgiaquinta.optionsquant.candle.CandleRepository;
import com.fgiaquinta.optionsquant.domain.Candle;
import com.fgiaquinta.optionsquant.domain.TimeFrame;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MacroEnvironmentFilter wired to CandleRepository.
 *
 * Verifies constructor injection of CandleRepository and that SPY daily candles
 * are loaded via repository.load() (not CandleCsvService).
 */
@ExtendWith(MockitoExtension.class)
class MacroEnvironmentFilterRepositoryWiringTest {

    @Mock
    private CandleRepository candleRepository;

    private MacroEnvironmentFilter filter;

    private static List<Candle> spyCandles(int count) {
        List<Candle> candles = new ArrayList<>();
        ZonedDateTime base = ZonedDateTime.of(2026, 1, 2, 16, 0, 0, 0, ZoneOffset.UTC);
        for (int i = 0; i < count; i++) {
            double price = 500.0 + i * 0.5;
            candles.add(new Candle(base.plusDays(i), price, price + 1, price - 1, price, 10_000_000L));
        }
        return candles;
    }

    @BeforeEach
    void setUp() {
        when(candleRepository.load(eq("SPY"), eq(TimeFrame.DAY_1))).thenReturn(spyCandles(60));
        filter = new MacroEnvironmentFilter(candleRepository);
        filter.init();
    }

    @Test
    @DisplayName("MacroEnvironmentFilter accepts CandleRepository in constructor")
    void filter_acceptsCandleRepositoryInConstructor() {
        assertThat(filter).isNotNull();
    }

    @Test
    @DisplayName("MacroEnvironmentFilter loads SPY daily candles via CandleRepository.load()")
    void filter_loadsSpy_viaCandleRepository() {
        verify(candleRepository, atLeastOnce()).load("SPY", TimeFrame.DAY_1);
    }

    @Test
    @DisplayName("MacroEnvironmentFilter returns a regime after init with sufficient SPY data")
    void filter_returnsNonNullRegime_afterInit() {
        MacroEnvironmentFilter.MarketRegime regime = filter.getRegime();
        assertThat(regime).isNotNull();
    }

    @Test
    @DisplayName("MacroEnvironmentFilter falls back to NEUTRAL when SPY data is insufficient")
    void filter_fallsBackToNeutral_whenSpyDataInsufficient() {
        when(candleRepository.load(eq("SPY"), eq(TimeFrame.DAY_1))).thenReturn(spyCandles(10));
        MacroEnvironmentFilter filterFew = new MacroEnvironmentFilter(candleRepository);
        filterFew.init();

        assertThat(filterFew.getRegime()).isEqualTo(MacroEnvironmentFilter.MarketRegime.NEUTRAL);
    }

    @Test
    @DisplayName("MacroEnvironmentFilter.isMacroFavorable returns boolean without throwing")
    void filter_isMacroFavorable_doesNotThrow() {
        assertThat(filter.isMacroFavorable(true)).isInstanceOf(Boolean.class);
        assertThat(filter.isMacroFavorable(false)).isInstanceOf(Boolean.class);
    }

    @Test
    @DisplayName("MacroEnvironmentFilter.forceMarketCondition overrides regime for testing")
    void filter_forceMarketCondition_overridesRegime() {
        filter.forceMarketCondition(
                MacroEnvironmentFilter.MarketRegime.STRONGLY_BEARISH,
                MacroEnvironmentFilter.ShortTermMomentum.FALLING);

        assertThat(filter.getRegime()).isEqualTo(MacroEnvironmentFilter.MarketRegime.STRONGLY_BEARISH);
        assertThat(filter.isMacroFavorable(true)).isFalse();
    }
}
