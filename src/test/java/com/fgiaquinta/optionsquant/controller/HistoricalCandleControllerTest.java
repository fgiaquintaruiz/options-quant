package com.fgiaquinta.optionsquant.controller;

import com.fgiaquinta.optionsquant.candle.CandleRepository;
import com.fgiaquinta.optionsquant.domain.Candle;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * HTTP contract for {@link HistoricalCandleController}.
 * CandleRepository is fully mocked — no SQLite, no Spring context beyond MVC slice.
 */
@WebMvcTest(controllers = HistoricalCandleController.class)
class HistoricalCandleControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CandleRepository candleRepository;

    @MockitoBean
    private MetricsService metricsService;

    @MockitoBean
    private TickerService tickerService;

    private static final String BASE = "/api/v1/historical";

    private static Candle candle(String date) {
        ZonedDateTime ts = LocalDate.parse(date).atStartOfDay(ZoneOffset.UTC);
        return new Candle(ts, 150.0, 155.0, 148.0, 153.0, 1_000_000L);
    }

    // ── 1. Happy path: 200 with JSON array ───────────────────────────────────

    @Test
    @DisplayName("GET /api/v1/historical/AAPL?interval=1d returns 200 with candle array")
    void happyPath_1d_returns200WithArray() throws Exception {
        List<Candle> candles = List.of(candle("2022-01-03"), candle("2022-01-04"));
        when(candleRepository.loadRange(eq("AAPL"), eq(TimeFrame.DAY_1), any(), any()))
                .thenReturn(candles);

        mockMvc.perform(get(BASE + "/AAPL")
                        .param("from", "2022-01-01")
                        .param("to", "2022-03-31")
                        .param("interval", "1d"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    // ── 2. interval=5m maps to MIN_5 ─────────────────────────────────────────

    @Test
    @DisplayName("interval=5m maps to MIN_5")
    void interval5m_mapsToMin5() throws Exception {
        when(candleRepository.loadRange(eq("AAPL"), eq(TimeFrame.MIN_5), any(), any()))
                .thenReturn(List.of(candle("2022-01-03")));

        mockMvc.perform(get(BASE + "/AAPL")
                        .param("from", "2022-01-01")
                        .param("to", "2022-03-31")
                        .param("interval", "5m"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    // ── 3. interval=1h maps to HOUR_1 ────────────────────────────────────────

    @Test
    @DisplayName("interval=1h maps to HOUR_1")
    void interval1h_mapsToHour1() throws Exception {
        when(candleRepository.loadRange(eq("AAPL"), eq(TimeFrame.HOUR_1), any(), any()))
                .thenReturn(List.of(candle("2022-01-03")));

        mockMvc.perform(get(BASE + "/AAPL")
                        .param("from", "2022-01-01")
                        .param("to", "2022-03-31")
                        .param("interval", "1h"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    // ── 4. interval=15m maps to MIN_15 ───────────────────────────────────────

    @Test
    @DisplayName("interval=15m maps to MIN_15")
    void interval15m_mapsToMin15() throws Exception {
        when(candleRepository.loadRange(eq("AAPL"), eq(TimeFrame.MIN_15), any(), any()))
                .thenReturn(List.of(candle("2022-01-03")));

        mockMvc.perform(get(BASE + "/AAPL")
                        .param("from", "2022-01-01")
                        .param("to", "2022-03-31")
                        .param("interval", "15m"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    // ── 5. Unknown interval → 400 ─────────────────────────────────────────────

    @Test
    @DisplayName("Unknown interval returns 400 with descriptive message")
    void unknownInterval_returns400() throws Exception {
        mockMvc.perform(get(BASE + "/AAPL")
                        .param("from", "2022-01-01")
                        .param("to", "2022-03-31")
                        .param("interval", "2w"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Unknown interval. Use: 1d, 1h, 15m, 5m"));
    }

    // ── 6. Missing 'from' → 400 ───────────────────────────────────────────────

    @Test
    @DisplayName("Missing 'from' param returns 400")
    void missingFrom_returns400() throws Exception {
        mockMvc.perform(get(BASE + "/AAPL")
                        .param("to", "2022-03-31")
                        .param("interval", "1d"))
                .andExpect(status().isBadRequest());
    }

    // ── 7. Missing 'to' → 400 ────────────────────────────────────────────────

    @Test
    @DisplayName("Missing 'to' param returns 400")
    void missingTo_returns400() throws Exception {
        mockMvc.perform(get(BASE + "/AAPL")
                        .param("from", "2022-01-01")
                        .param("interval", "1d"))
                .andExpect(status().isBadRequest());
    }

    // ── 8. from after to → 400 with message ──────────────────────────────────

    @Test
    @DisplayName("from after to returns 400 with descriptive message")
    void fromAfterTo_returns400() throws Exception {
        mockMvc.perform(get(BASE + "/AAPL")
                        .param("from", "2022-06-01")
                        .param("to", "2022-01-01")
                        .param("interval", "1d"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("'from' must not be after 'to'"));
    }

    // ── 9. Response fields shape ──────────────────────────────────────────────

    @Test
    @DisplayName("Valid request returns candles with expected fields")
    void validRequest_returnsCandlesWithExpectedFields() throws Exception {
        ZonedDateTime ts = LocalDate.parse("2022-01-03").atStartOfDay(ZoneOffset.UTC);
        Candle c = new Candle(ts, 150.0, 155.0, 148.0, 153.0, 1_000_000L);
        when(candleRepository.loadRange(eq("AAPL"), eq(TimeFrame.DAY_1), any(), any()))
                .thenReturn(List.of(c));

        mockMvc.perform(get(BASE + "/AAPL")
                        .param("from", "2022-01-01")
                        .param("to", "2022-03-31")
                        .param("interval", "1d"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].ticker").value("AAPL"))
                .andExpect(jsonPath("$[0].date").value("2022-01-03"))
                .andExpect(jsonPath("$[0].open").value(150.0))
                .andExpect(jsonPath("$[0].high").value(155.0))
                .andExpect(jsonPath("$[0].low").value(148.0))
                .andExpect(jsonPath("$[0].close").value(153.0))
                .andExpect(jsonPath("$[0].volume").value(1_000_000));
    }
}
