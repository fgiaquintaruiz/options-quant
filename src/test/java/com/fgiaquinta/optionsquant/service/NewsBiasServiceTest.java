package com.fgiaquinta.optionsquant.service;

import com.fgiaquinta.optionsquant.domain.NewsBias;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NewsBiasServiceTest {

    @Mock YahooFinanceClient yahooClient;
    @Mock HeadlineSentimentScorer scorer;
    NewsBiasService service;

    @BeforeEach
    void setUp() {
        service = new NewsBiasService(yahooClient, scorer);
    }

    @Test
    void getBias_noPrefetch_returnsNeutral() {
        assertThat(service.getBias("AAPL")).isEqualTo(NewsBias.NEUTRAL);
    }

    @Test
    void prefetchAsync_singleTicker_populatesCache() throws Exception {
        when(yahooClient.fetchHeadlines("AAPL")).thenReturn(List.of("beat record"));
        when(scorer.score(List.of("beat record"))).thenReturn(NewsBias.CALL);

        service.prefetchAsync(List.of("AAPL")).join();

        assertThat(service.getBias("AAPL")).isEqualTo(NewsBias.CALL);
    }

    @Test
    void prefetchAsync_emptyHeadlines_storesNeutral() throws Exception {
        when(yahooClient.fetchHeadlines("TSLA")).thenReturn(List.of());
        when(scorer.score(List.of())).thenReturn(NewsBias.NEUTRAL);

        service.prefetchAsync(List.of("TSLA")).join();

        assertThat(service.getBias("TSLA")).isEqualTo(NewsBias.NEUTRAL);
    }

    @Test
    void prefetchAsync_calledTwice_secondResultWins() throws Exception {
        when(yahooClient.fetchHeadlines("AAPL"))
                .thenReturn(List.of("beat record"))
                .thenReturn(List.of("downgrade weak"));
        when(scorer.score(List.of("beat record"))).thenReturn(NewsBias.CALL);
        when(scorer.score(List.of("downgrade weak"))).thenReturn(NewsBias.PUT);

        service.prefetchAsync(List.of("AAPL")).join();
        service.prefetchAsync(List.of("AAPL")).join();

        assertThat(service.getBias("AAPL")).isEqualTo(NewsBias.PUT);
    }

    @Test
    void getBias_lowercaseTicker_normalizedToUpperCase() throws Exception {
        when(yahooClient.fetchHeadlines("AAPL")).thenReturn(List.of("beat record"));
        when(scorer.score(List.of("beat record"))).thenReturn(NewsBias.CALL);

        service.prefetchAsync(List.of("AAPL")).join();

        assertThat(service.getBias("aapl")).isEqualTo(NewsBias.CALL);
    }

    @Test
    void invalidateCache_clearsAllEntries() throws Exception {
        when(yahooClient.fetchHeadlines("AAPL")).thenReturn(List.of("beat record"));
        when(scorer.score(List.of("beat record"))).thenReturn(NewsBias.CALL);

        service.prefetchAsync(List.of("AAPL")).join();
        service.invalidateCache();

        assertThat(service.getBias("AAPL")).isEqualTo(NewsBias.NEUTRAL);
    }
}
