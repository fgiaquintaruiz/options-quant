package com.fgiaquinta.optionsquant.candle.backfill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.util.concurrent.RateLimiter;
import com.fgiaquinta.optionsquant.domain.Candle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@ConditionalOnProperty(
    prefix = "candles.backfill.yfinance",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true
)
public class YfinanceHistoricalClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_RETRIES = 3;

    private final String baseUrl;
    private final HttpClient http;
    private final RateLimiter rateLimiter;

    @Autowired
    public YfinanceHistoricalClient(
            @Value("${analytics.service.url:http://localhost:8001}") String baseUrl,
            @Value("${analytics.service.rate-per-second:1.0}") double ratePerSecond) {
        this(baseUrl,
             HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build(),
             ratePerSecond);
    }

    YfinanceHistoricalClient(String baseUrl, HttpClient http, double ratePerSecond) {
        this.baseUrl = baseUrl;
        this.http = http;
        this.rateLimiter = RateLimiter.create(ratePerSecond);
    }

    public List<Candle> fetchDailyCandles(String ticker, LocalDate from, LocalDate to) {
        String url = String.format("%s/api/v1/historical/%s?from=%s&to=%s&interval=1d",
                baseUrl, ticker, from, to.plusDays(1));
        rateLimiter.acquire();
        try {
            HttpResponse<String> response = sendWithRetry(url);
            if (response == null || response.statusCode() >= 400) {
                log.warn("[yfinance] Non-success response for {} ({}-{}): status={}",
                        ticker, from, to, response == null ? "null" : response.statusCode());
                return List.of();
            }
            return parseCandles(response.body());
        } catch (Exception e) {
            log.warn("[yfinance] fetchDailyCandles failed for {} ({}-{}): {}", ticker, from, to, e.getMessage());
            return List.of();
        }
    }

    private HttpResponse<String> sendWithRetry(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();
        HttpResponse<String> response = null;
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 500) return response;
            log.warn("[yfinance] 5xx on attempt {}/{} for {}", attempt, MAX_RETRIES, url);
            if (attempt < MAX_RETRIES) Thread.sleep(1000L << attempt);
        }
        return response;
    }

    private List<Candle> parseCandles(String json) throws Exception {
        JsonNode root = MAPPER.readTree(json);
        if (!root.isArray()) return List.of();
        List<Candle> result = new ArrayList<>();
        for (JsonNode node : root) {
            long tsEpoch = node.path("ts_epoch").asLong();
            ZonedDateTime ts = ZonedDateTime.ofInstant(Instant.ofEpochSecond(tsEpoch), ZoneOffset.UTC);
            result.add(new Candle(
                    ts,
                    node.path("open").asDouble(),
                    node.path("high").asDouble(),
                    node.path("low").asDouble(),
                    node.path("close").asDouble(),
                    node.path("volume").asLong()
            ));
        }
        return result;
    }
}
