package com.fgiaquinta.optionsquant.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Slf4j
@Component
public class YahooFinanceClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient http;

    public YahooFinanceClient() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    YahooFinanceClient(HttpClient http) {
        this.http = http;
    }

    public Optional<LocalDate> fetchEarningsDate(String ticker) {
        String url = "https://query2.finance.yahoo.com/v10/finance/quoteSummary/" + ticker + "?modules=calendarEvents";
        try {
            HttpResponse<String> response = get(url);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("fetchEarningsDate: non-2xx {} for {}", response.statusCode(), ticker);
                return Optional.empty();
            }
            JsonNode root = MAPPER.readTree(response.body());
            return parseEarningsDate(root);
        } catch (IOException | InterruptedException e) {
            log.warn("fetchEarningsDate: error for {} — {}", ticker, e.getMessage());
            return Optional.empty();
        }
    }

    public List<String> fetchHeadlines(String ticker) {
        String url = "https://query1.finance.yahoo.com/v1/finance/search?q=" + ticker
                + "&newsCount=5&enableNavLinks=false&enableFuzzyQuery=false";
        try {
            HttpResponse<String> response = get(url);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("fetchHeadlines: non-2xx {} for {}", response.statusCode(), ticker);
                return Collections.emptyList();
            }
            JsonNode root = MAPPER.readTree(response.body());
            return parseHeadlines(root);
        } catch (IOException | InterruptedException e) {
            log.warn("fetchHeadlines: error for {} — {}", ticker, e.getMessage());
            return Collections.emptyList();
        }
    }

    private Optional<LocalDate> parseEarningsDate(JsonNode root) {
        try {
            long epoch = root.path("quoteSummary")
                    .path("result").get(0)
                    .path("calendarEvents")
                    .path("earnings")
                    .path("earningsDate").get(0)
                    .path("raw").asLong();
            LocalDate date = Instant.ofEpochSecond(epoch).atZone(ZoneOffset.UTC).toLocalDate();
            return Optional.of(date);
        } catch (Exception e) {
            log.warn("parseEarningsDate: failed to parse JSON — {}", e.getMessage());
            return Optional.empty();
        }
    }

    private List<String> parseHeadlines(JsonNode root) {
        List<String> titles = new ArrayList<>();
        JsonNode news = root.path("news");
        int limit = Math.min(news.size(), 5);
        for (int i = 0; i < limit; i++) {
            titles.add(news.get(i).path("title").asText());
        }
        return titles;
    }

    private HttpResponse<String> get(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "Mozilla/5.0")
                .GET()
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
