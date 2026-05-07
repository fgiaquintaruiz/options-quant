package com.fgiaquinta.optionsquant.controller;

import com.fgiaquinta.optionsquant.candle.CandleRepository;
import com.fgiaquinta.optionsquant.domain.Candle;
import com.fgiaquinta.optionsquant.domain.TickerInfo;
import com.fgiaquinta.optionsquant.domain.TimeFrame;
import com.fgiaquinta.optionsquant.infrastructure.MetricsService;
import com.fgiaquinta.optionsquant.service.TickerService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * HTTP contract for the GET /api/v1/historical/{ticker}/with-fundamentals endpoint.
 * All dependencies are mocked — no SQLite, no Spring context beyond MVC slice.
 */
@WebMvcTest(controllers = HistoricalCandleController.class)
class HistoricalCandleWithFundamentalsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CandleRepository candleRepository;

    @MockitoBean
    private MetricsService metricsService;

    @MockitoBean
    private TickerService tickerService;

    private static final String BASE = "/api/v1/historical";

    private static TickerInfo aaplInfo() {
        return new TickerInfo("AAPL", "Apple Inc.", "Technology",
                3000L, 28.5, 0.5, 1.2, 12.0, 8.0, 0.3, 25.0, null);
    }

    private static Candle candle(String date) {
        ZonedDateTime ts = LocalDate.parse(date).atStartOfDay(ZoneOffset.UTC);
        return new Candle(ts, 150.0, 155.0, 148.0, 153.0, 1_000_000L);
    }

    // ── 1. Happy path: 200 with ticker fundamentals + candles array ───────────

    @Test
    @DisplayName("Valid ticker returns 200 with fundamentals object and candles array")
    void validTicker_returnsCandlesAndFundamentals_200() throws Exception {
        when(tickerService.getTickerInfo("AAPL")).thenReturn(Optional.of(aaplInfo()));
        when(candleRepository.loadRange(eq("AAPL"), eq(TimeFrame.DAY_1), any(), any()))
                .thenReturn(List.of(candle("2024-01-02"), candle("2024-01-03")));

        mockMvc.perform(get(BASE + "/AAPL/with-fundamentals")
                        .param("from", "2024-01-01")
                        .param("to", "2024-01-31")
                        .param("interval", "1d"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ticker").exists())
                .andExpect(jsonPath("$.ticker.ticker").value("AAPL"))
                .andExpect(jsonPath("$.ticker.companyName").value("Apple Inc."))
                .andExpect(jsonPath("$.candles").isArray())
                .andExpect(jsonPath("$.candles.length()").value(2));
    }

    // ── 2. Unknown ticker → 404 ───────────────────────────────────────────────

    @Test
    @DisplayName("Unknown ticker returns 404")
    void unknownTicker_returns404() throws Exception {
        when(tickerService.getTickerInfo("UNKNOWN")).thenReturn(Optional.empty());

        mockMvc.perform(get(BASE + "/UNKNOWN/with-fundamentals")
                        .param("from", "2024-01-01")
                        .param("to", "2024-01-31")
                        .param("interval", "1d"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("ticker not found"))
                .andExpect(jsonPath("$.ticker").value("UNKNOWN"));
    }

    // ── 3. No candles in range → 200 with ticker + empty candles array ────────

    @Test
    @DisplayName("No candles in range returns 200 with ticker present and empty candles array")
    void noCandlesInRange_returnsTicker_emptyCandlesArray_200() throws Exception {
        when(tickerService.getTickerInfo("AAPL")).thenReturn(Optional.of(aaplInfo()));
        when(candleRepository.loadRange(eq("AAPL"), eq(TimeFrame.DAY_1), any(), any()))
                .thenReturn(List.of());

        mockMvc.perform(get(BASE + "/AAPL/with-fundamentals")
                        .param("from", "2024-01-01")
                        .param("to", "2024-01-02")
                        .param("interval", "1d"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ticker").exists())
                .andExpect(jsonPath("$.candles").isArray())
                .andExpect(jsonPath("$.candles.length()").value(0));
    }

    // ── 4. Missing required params → 400 ─────────────────────────────────────

    @Test
    @DisplayName("Missing from and to params returns 400")
    void missingRequiredParam_returns400() throws Exception {
        mockMvc.perform(get(BASE + "/AAPL/with-fundamentals"))
                .andExpect(status().isBadRequest());
    }

    // ── 5. Default interval is 1d when omitted ────────────────────────────────

    @Test
    @DisplayName("Omitting interval defaults to 1d and returns 200")
    void intervalDefault_isDay1_whenOmitted() throws Exception {
        when(tickerService.getTickerInfo("AAPL")).thenReturn(Optional.of(aaplInfo()));
        when(candleRepository.loadRange(eq("AAPL"), eq(TimeFrame.DAY_1), any(), any()))
                .thenReturn(List.of(candle("2024-01-02")));

        mockMvc.perform(get(BASE + "/AAPL/with-fundamentals")
                        .param("from", "2024-01-01")
                        .param("to", "2024-01-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candles.length()").value(1));
    }
}
