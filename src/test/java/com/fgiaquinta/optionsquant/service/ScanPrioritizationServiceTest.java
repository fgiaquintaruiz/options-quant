package com.fgiaquinta.optionsquant.service;

import com.fgiaquinta.optionsquant.config.ScannerProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ScanPrioritizationServiceTest {

    private NewsFilterService newsFilterService;
    private TickerMemory tickerMemory;
    private ScannerProperties scannerProperties;
    private ScanPrioritizationService service;

    @BeforeEach
    void setUp() {
        newsFilterService = mock(NewsFilterService.class);
        tickerMemory = mock(TickerMemory.class);
        scannerProperties = mock(ScannerProperties.class);

        when(scannerProperties.prioritizationMode()).thenReturn(ScannerProperties.PrioritizationMode.HYBRID);
        when(scannerProperties.hybridFundamentalWeight()).thenReturn(0.65);
        when(scannerProperties.hybridMemoryWeight()).thenReturn(0.35);

        when(newsFilterService.getPriorityTickers()).thenReturn(List.of());

        service = new ScanPrioritizationService(newsFilterService, tickerMemory, scannerProperties);
    }

    // ─── Task 1.1: computeScores ────────────────────────────────────────────────

    @Test
    void computeScores_returns_hybrid_0_65_fund_plus_0_35_mem_for_known_inputs() {
        // given
        when(newsFilterService.getFundamentalScoresMap()).thenReturn(Map.of("NVDA", 0.8));
        when(tickerMemory.getLearningPriorityScore("NVDA")).thenReturn(0.4);

        // when
        var result = service.computeScores(List.of("NVDA"));

        // then
        assertThat(result).containsKey("NVDA");
        var breakdown = result.get("NVDA");
        assertThat(breakdown.ticker()).isEqualTo("NVDA");
        assertThat(breakdown.fundamentalScore()).isEqualTo(0.8);
        assertThat(breakdown.memoryScore()).isEqualTo(0.4);
        // hybrid = 0.65*0.8 + 0.35*0.4 = 0.52 + 0.14 = 0.66  (weights normalised to 1.0 → unchanged)
        assertThat(breakdown.hybridScore()).isEqualTo(0.52 + 0.14, org.assertj.core.api.Assertions.within(1e-9));
    }

    // ─── Task 1.3: orderRemainingTickers delegates to computeScores ─────────────

    @Test
    void orderRemainingTickers_produces_descending_hybrid_order_same_as_computeScores() {
        // given: AAPL has higher hybrid than TSLA
        when(newsFilterService.getFundamentalScoresMap()).thenReturn(Map.of(
                "AAPL", 0.9,
                "TSLA", 0.3
        ));
        when(tickerMemory.getLearningPriorityScore("AAPL")).thenReturn(0.8);
        when(tickerMemory.getLearningPriorityScore("TSLA")).thenReturn(0.2);

        // AAPL hybrid = 0.65*0.9 + 0.35*0.8 = 0.585 + 0.28 = 0.865
        // TSLA hybrid = 0.65*0.3 + 0.35*0.2 = 0.195 + 0.07 = 0.265

        // when
        List<String> ordered = service.orderRemainingTickers(List.of("TSLA", "AAPL"));

        // then: AAPL (higher hybrid) must come first
        assertThat(ordered).containsExactly("AAPL", "TSLA");
    }
}
