package com.fgiaquinta.optionsquant.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EarningsDateServiceTest {

    private YahooFinanceClient yahooClient;
    private EarningsDateService service;

    @BeforeEach
    void setUp() {
        yahooClient = mock(YahooFinanceClient.class);
        service = new EarningsDateService(yahooClient);
    }

    @Test
    void refreshEarningsDates_clientReturnsDate_storesIt() {
        when(yahooClient.fetchEarningsDate("AAPL"))
                .thenReturn(Optional.of(LocalDate.of(2026, 6, 15)));

        service.refreshEarningsDates(List.of("AAPL"));

        assertThat(service.getEarningsDate("AAPL")).isEqualTo(LocalDate.of(2026, 6, 15));
    }

    @Test
    void refreshEarningsDates_clientReturnsEmpty_preservesExistingEntry() {
        // Prime the map with a prior entry via the Yahoo path
        when(yahooClient.fetchEarningsDate("TSLA"))
                .thenReturn(Optional.of(LocalDate.of(2026, 7, 20)));
        service.refreshEarningsDates(List.of("TSLA"));

        // Now client returns empty — prior entry must survive
        when(yahooClient.fetchEarningsDate("TSLA")).thenReturn(Optional.empty());
        service.refreshEarningsDates(List.of("TSLA"));

        assertThat(service.getEarningsDate("TSLA")).isEqualTo(LocalDate.of(2026, 7, 20));
    }

    @Test
    void hasEarningsSoon_dateWithinWindow_returnsTrue() {
        when(yahooClient.fetchEarningsDate("AAPL"))
                .thenReturn(Optional.of(LocalDate.now().plusDays(3)));
        service.refreshEarningsDates(List.of("AAPL"));

        assertThat(service.hasEarningsSoon("AAPL", 7)).isTrue();
    }

    @Test
    void hasEarningsSoon_dateBeyondWindow_returnsFalse() {
        when(yahooClient.fetchEarningsDate("AAPL"))
                .thenReturn(Optional.of(LocalDate.now().plusDays(30)));
        service.refreshEarningsDates(List.of("AAPL"));

        assertThat(service.hasEarningsSoon("AAPL", 7)).isFalse();
    }

    @Test
    void hasEarningsSoon_unknownTicker_returnsFalse() {
        assertThat(service.hasEarningsSoon("UNKNOWN", 7)).isFalse();
    }
}
