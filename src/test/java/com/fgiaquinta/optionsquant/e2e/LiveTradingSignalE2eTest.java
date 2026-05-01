package com.fgiaquinta.optionsquant.e2e;

import org.junit.jupiter.api.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.*;

@Tag("e2e")
@DisplayName("Live Trading Signal E2E Tests")
class LiveTradingSignalE2eTest extends BasePlaywrightTest {

    @Test
    @DisplayName("Inject mock signal for SPY returns all signal fields")
    void injectMockSignal_defaultTicker_returnsSignalFields() throws Exception {
        String json = postJson("/live-ui/inject-mock-signal");
        assertNotNull(json);
        assertTrue(json.contains("\"success\":true"));
        assertTrue(json.contains("\"ticker\""));
        assertTrue(json.contains("\"strategy\""));
        assertTrue(json.contains("\"direction\""));
        assertTrue(json.contains("\"entryPrice\""));
        assertTrue(json.contains("\"takeProfit\""));
        assertTrue(json.contains("\"stopLoss\""));
        assertTrue(json.contains("\"autoExecuted\""));
    }

    @Test
    @DisplayName("Inject mock signal for AAPL returns ticker=AAPL in response")
    void injectMockSignal_customTicker_returnsCorrectTicker() throws Exception {
        String json = postJson("/live-ui/inject-mock-signal?ticker=AAPL");
        assertNotNull(json);
        assertTrue(json.contains("\"ticker\":\"AAPL\""));
    }

    @Test
    @DisplayName("Signals list contains injected signal")
    void signals_afterInject_containsSignal() throws Exception {
        postJson("/live-ui/inject-mock-signal?ticker=MSFT");

        String json = getJson("/live-ui/signals");
        assertNotNull(json);
        assertTrue(json.contains("\"signals\""));
        assertTrue(json.contains("\"count\""));

        // After injecting, count must be > 0
        assertFalse(json.contains("\"count\":0"), "Expected at least one signal after inject");
    }

    @Test
    @DisplayName("Clear stale signals returns removed count")
    void clearStaleSignals_returnsRemovedCount() throws Exception {
        String json = postJson("/live-ui/signals/clear-stale");
        assertNotNull(json);
        assertTrue(json.contains("\"success\":true"));
        assertTrue(json.contains("\"removed\""));
    }

    @Test
    @DisplayName("Batch delete signals for AAPL returns 200 with removed count")
    void batchDeleteSignals_validList_returns200() throws Exception {
        HttpResponse<String> resp = postJsonBody("/live-ui/signals/batch-delete", "[\"AAPL\"]", 200);
        String body = resp.body();
        assertNotNull(body);
        assertTrue(body.contains("\"success\":true"));
        assertTrue(body.contains("\"removed\""));
        assertTrue(body.contains("\"skippedOpenPosition\""));
    }

    @Test
    @DisplayName("Delete signal via DELETE method returns success or not-found response")
    void deleteSignal_existingTicker_returns200() throws Exception {
        // Inject first so the signal exists
        postJson("/live-ui/inject-mock-signal?ticker=SPY");

        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> resp = client.send(
            HttpRequest.newBuilder(URI.create(BASE_URL + "/live-ui/signal?ticker=SPY"))
                .DELETE()
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(200, resp.statusCode());
        String body = resp.body();
        assertTrue(body.contains("\"ticker\":\"SPY\""));
        assertTrue(body.contains("\"success\""));
    }

    @Test
    @DisplayName("Execute signal for injected SPY returns response structure")
    void executeSignal_injectedTicker_returnsResponseStructure() throws Exception {
        // Inject the mock signal so the ticker and price are known
        String injectJson = postJson("/live-ui/inject-mock-signal?ticker=SPY");
        assertNotNull(injectJson);

        // Extract entryPrice from inject response (present as numeric field)
        // Use a reasonable fallback price — the endpoint requires price as param
        // We call execute-signal; TWS connectivity determines success/failure, but response structure is always present
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> resp = client.send(
            HttpRequest.newBuilder(URI.create(BASE_URL + "/live-ui/execute-signal?ticker=SPY&direction=CALL&price=500.0"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .header("Content-Type", "application/x-www-form-urlencoded")
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );
        String body = resp.body();
        assertNotNull(body);
        // Response always contains "success" and "message" regardless of TWS state
        assertTrue(body.contains("\"success\""), "Response must always contain 'success' field");
        assertTrue(body.contains("\"message\""), "Response must always contain 'message' field");
    }

    @Test
    @DisplayName("Cancel trade for non-existent ticker returns structured response")
    void cancelTrade_nonExistentTicker_returnsResponse() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        // cancel-trade requires orderId (int) — use 0 which will fail at TWS level but still return structure
        HttpResponse<String> resp = client.send(
            HttpRequest.newBuilder(URI.create(BASE_URL + "/live-ui/cancel-trade?ticker=NONEXISTENT99&orderId=0"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .header("Content-Type", "application/x-www-form-urlencoded")
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );
        String body = resp.body();
        assertNotNull(body);
        assertTrue(body.contains("\"success\""), "Response must always contain 'success' field");
        assertTrue(body.contains("\"message\""), "Response must always contain 'message' field");
    }
}
