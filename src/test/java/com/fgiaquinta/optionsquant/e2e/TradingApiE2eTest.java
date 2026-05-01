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
}
