package com.fgiaquinta.optionsquant.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SuppressWarnings({"unchecked", "rawtypes"})
class YahooFinanceClientTest {

    private HttpClient mockHttp;
    private HttpResponse mockResponse;
    private YahooFinanceClient client;

    @BeforeEach
    void setUp() throws Exception {
        mockHttp = mock(HttpClient.class);
        mockResponse = mock(HttpResponse.class);
        // doReturn avoids generic type inference issues with HttpClient.send()
        doReturn(mockResponse).when(mockHttp).send(any(), any());
        client = new YahooFinanceClient(mockHttp);
    }

    // --- fetchEarningsDate ---

    @Test
    void fetchEarningsDate_200WithValidJson_returnsCorrectDate() throws Exception {
        String json = """
                {"quoteSummary":{"result":[{"calendarEvents":{"earnings":{"earningsDate":[{"raw":1746057600}]}}}]}}
                """;
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn(json);

        var result = client.fetchEarningsDate("AAPL");

        assertThat(result).isPresent();
        assertThat(result.get()).isEqualTo(LocalDate.of(2025, 5, 1));
    }

    @Test
    void fetchEarningsDate_429Response_returnsEmpty() throws Exception {
        when(mockResponse.statusCode()).thenReturn(429);

        var result = client.fetchEarningsDate("AAPL");

        assertThat(result).isEmpty();
    }

    @Test
    void fetchEarningsDate_malformedJson_returnsEmpty() throws Exception {
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn("{not valid json{{");

        var result = client.fetchEarningsDate("AAPL");

        assertThat(result).isEmpty();
    }

    // --- fetchHeadlines ---

    @Test
    void fetchHeadlines_200WithThreeNews_returnsThreeTitles() throws Exception {
        String json = """
                {"news":[{"title":"Title 1"},{"title":"Title 2"},{"title":"Title 3"}]}
                """;
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn(json);

        List<String> result = client.fetchHeadlines("AAPL");

        assertThat(result).hasSize(3).containsExactly("Title 1", "Title 2", "Title 3");
    }

    @Test
    void fetchHeadlines_500Response_returnsEmptyList() throws Exception {
        when(mockResponse.statusCode()).thenReturn(500);

        List<String> result = client.fetchHeadlines("AAPL");

        assertThat(result).isEmpty();
    }

    @Test
    void fetchHeadlines_ioExceptionThrown_returnsEmptyList() throws Exception {
        doThrow(new IOException("network error")).when(mockHttp).send(any(), any());

        List<String> result = client.fetchHeadlines("AAPL");

        assertThat(result).isEmpty();
    }
}
