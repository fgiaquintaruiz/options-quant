package com.fgiaquinta.optionsquant.e2e;

import org.junit.jupiter.api.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

@Tag("e2e")
@DisplayName("Trading API E2E Tests")
class TradingApiE2eTest extends BasePlaywrightTest {

    @Test
    @DisplayName("Account status returns balance and connection fields")
    void accountStatus_returnsConnectionFields() throws Exception {
        String json = getJson("/api/trading/account-status");
        assertNotNull(json);
        assertTrue(json.contains("\"balance\""));
        assertTrue(json.contains("\"riskPerTrade\""));
        assertTrue(json.contains("\"riskPerTradePct\""));
        assertTrue(json.contains("\"activeTrades\""));
        assertTrue(json.contains("\"canTrade\""));
    }

    @Test
    @DisplayName("Candles status returns connected field")
    void candlesStatus_returnsStructure() throws Exception {
        String json = getJson("/api/candles/status");
        assertNotNull(json);
        assertTrue(json.contains("\"connected\""));
    }

    @Test
    @DisplayName("Scan and execute with autoExecute=false returns result structure")
    void scanAndExecute_autoExecuteFalse_returnsResult() throws Exception {
        // This triggers a real scan — use 60s timeout to allow completion
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> resp = client.send(
            HttpRequest.newBuilder(URI.create(BASE_URL + "/api/trading/scan-and-execute?autoExecute=false"))
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.noBody())
                .header("Content-Type", "application/x-www-form-urlencoded")
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(200, resp.statusCode());
        String body = resp.body();
        assertNotNull(body);
        // TradingResult wraps scanResult + executions
        assertTrue(body.contains("\"scanResult\"") || body.contains("\"executions\""),
            "Response must contain scanResult or executions from TradingResult");
    }

    @Test
    @DisplayName("Backup with empty payload returns status ok")
    void backup_validPayload_returns200() throws Exception {
        HttpResponse<String> resp = postJsonBody("/api/backup", "{}", 200);
        String body = resp.body();
        assertNotNull(body);
        assertTrue(body.contains("\"status\":\"ok\""));
    }

    @Test
    @DisplayName("Execute signal with valid params returns 200")
    void executeSignal_validParams_returns200() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> resp = client.send(
            HttpRequest.newBuilder(URI.create(BASE_URL + "/api/trading/execute?ticker=SPY&direction=BUY&qty=1&entryPrice=450.0&tp=460.0&sl=440.0"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .header("Content-Type", "application/x-www-form-urlencoded")
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(200, resp.statusCode());
    }

    @Test
    @DisplayName("Download candles for SPY DAY returns 200")
    void downloadCandles_validTicker_returns200() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> resp = client.send(
            HttpRequest.newBuilder(URI.create(BASE_URL + "/api/candles/download?ticker=SPY&timeframe=DAY&saveToCsv=false"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .header("Content-Type", "application/x-www-form-urlencoded")
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(200, resp.statusCode());
    }

    @Test
    @DisplayName("Download all candles for SPY returns 200")
    void downloadAllCandles_validTicker_returns200() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> resp = client.send(
            HttpRequest.newBuilder(URI.create(BASE_URL + "/api/candles/download-all?ticker=SPY&saveToCsv=false"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .header("Content-Type", "application/x-www-form-urlencoded")
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(200, resp.statusCode());
    }

    @Test
    @DisplayName("Download all tickers candles returns 200")
    void downloadAllTickersCandles_returns200() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> resp = client.send(
            HttpRequest.newBuilder(URI.create(BASE_URL + "/api/candles/download-all-tickers?saveToCsv=false"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .header("Content-Type", "application/x-www-form-urlencoded")
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(200, resp.statusCode());
    }
}
