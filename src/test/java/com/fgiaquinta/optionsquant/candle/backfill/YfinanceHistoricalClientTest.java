package com.fgiaquinta.optionsquant.candle.backfill;

import com.fgiaquinta.optionsquant.domain.Candle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class YfinanceHistoricalClientTest {

    @Mock
    private HttpClient httpClient;

    private YfinanceHistoricalClient client;

    @BeforeEach
    void setUp() {
        client = new YfinanceHistoricalClient("http://localhost:8001", httpClient, 100.0);
    }

    // -------------------------------------------------------------------------
    // T8-1: valid JSON array → parsed into Candle list
    // -------------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void fetchDailyCandles_validJsonResponse_returnsParsedCandles() throws Exception {
        String json = """
                [
                  {"ts_epoch": 1641168000, "open": 100.0, "high": 105.0, "low": 99.0, "close": 104.0, "volume": 1000},
                  {"ts_epoch": 1641254400, "open": 104.0, "high": 108.0, "low": 103.0, "close": 107.0, "volume": 1100},
                  {"ts_epoch": 1641340800, "open": 107.0, "high": 110.0, "low": 106.0, "close": 109.0, "volume": 1200}
                ]
                """;
        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn(json);
        doReturn(mockResponse).when(httpClient).send(any(), any());

        List<Candle> result = client.fetchDailyCandles("AAPL",
                LocalDate.of(2022, 1, 3), LocalDate.of(2022, 1, 6));

        assertThat(result).hasSize(3);
        assertThat(result.get(0).close()).isEqualTo(104.0);
        assertThat(result.get(1).close()).isEqualTo(107.0);
        assertThat(result.get(2).close()).isEqualTo(109.0);
    }

    // -------------------------------------------------------------------------
    // T8-2: empty JSON array → empty list (no exception)
    // -------------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void fetchDailyCandles_emptyArrayResponse_returnsEmptyList() throws Exception {
        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn("[]");
        doReturn(mockResponse).when(httpClient).send(any(), any());

        List<Candle> result = client.fetchDailyCandles("AAPL",
                LocalDate.of(2022, 1, 3), LocalDate.of(2022, 1, 6));

        assertThat(result).isEmpty();
    }

    // -------------------------------------------------------------------------
    // T8-3: HTTP 500 on all retries → empty list, retried MAX_RETRIES times
    // -------------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void fetchDailyCandles_http500OnAllRetries_returnsEmptyAndRetriesThreeTimes() throws Exception {
        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(500);
        doReturn(mockResponse).when(httpClient).send(any(), any());

        List<Candle> result = client.fetchDailyCandles("AAPL",
                LocalDate.of(2022, 1, 3), LocalDate.of(2022, 1, 6));

        assertThat(result).isEmpty();
        verify(httpClient, times(3)).send(any(), any());
    }

    // -------------------------------------------------------------------------
    // T8-4: IOException (connection refused) → empty list, no exception propagated
    // -------------------------------------------------------------------------

    @Test
    void fetchDailyCandles_connectionRefused_returnsEmptyList() throws Exception {
        when(httpClient.send(any(), any())).thenThrow(new java.io.IOException("Connection refused"));

        List<Candle> result = client.fetchDailyCandles("AAPL",
                LocalDate.of(2022, 1, 3), LocalDate.of(2022, 1, 6));

        assertThat(result).isEmpty();
    }

    // -------------------------------------------------------------------------
    // T8-5: HTTP 404 (non-5xx) → no retry, returns empty list immediately
    // -------------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void fetchDailyCandles_http404_returnsEmptyWithoutRetry() throws Exception {
        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(404);
        doReturn(mockResponse).when(httpClient).send(any(), any());

        List<Candle> result = client.fetchDailyCandles("AAPL",
                LocalDate.of(2022, 1, 3), LocalDate.of(2022, 1, 6));

        assertThat(result).isEmpty();
        // 404 is < 500 → exits retry loop on first attempt, no retries
        verify(httpClient, times(1)).send(any(), any());
    }

    // -------------------------------------------------------------------------
    // T8-6: yfinance `end` is exclusive — URL must contain to.plusDays(1)
    //        Domain: from=2018-01-01, to=2018-03-31 (inclusive)
    //        Expected URL param: to=2018-04-01
    // -------------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void day1_effectiveTo_passedToYfinanceAsExclusiveEnd_plusOneDay() throws Exception {
        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn("[]");

        ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        doReturn(mockResponse).when(httpClient).send(requestCaptor.capture(), any());

        client.fetchDailyCandles("AAPL",
                LocalDate.of(2018, 1, 1), LocalDate.of(2018, 3, 31));

        String uri = requestCaptor.getValue().uri().toString();
        // yfinance treats `end` as exclusive, so the inclusive domain `to` (2018-03-31)
        // must be sent as 2018-04-01 to ensure that day's candle is included.
        assertThat(uri)
                .as("URL must contain to=2018-04-01 (exclusive end) not 2018-03-31")
                .contains("to=2018-04-01");
        assertThat(uri)
                .as("URL must not contain the bare inclusive end date as the to param")
                .doesNotContain("to=2018-03-31");
    }
}
