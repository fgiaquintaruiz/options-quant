package com.fgiaquinta.optionsquant.e2e;

import org.junit.jupiter.api.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.*;

@Tag("e2e")
@DisplayName("Live Trading Toggle E2E Tests")
class LiveTradingToggleE2eTest extends BasePlaywrightTest {

    @Test
    @DisplayName("Toggle scheduler returns schedulerEnabled field")
    void toggleScheduler_returnsValidResponse() throws Exception {
        String json = postJson("/live-ui/toggle-scheduler");
        assertNotNull(json);
        assertTrue(json.contains("\"success\":true"));
        assertTrue(json.contains("\"schedulerEnabled\""));
    }

    @Test
    @DisplayName("Toggle mock market returns mockMarketOpen field")
    void toggleMockMarket_returnsValidResponse() throws Exception {
        String json = postJson("/live-ui/toggle-mock-market");
        assertNotNull(json);
        assertTrue(json.contains("\"success\":true"));
        assertTrue(json.contains("\"mockMarketOpen\""));
    }

    @Test
    @DisplayName("Toggle auto-execute returns autoExecute field")
    void toggleAutoExecute_returnsValidResponse() throws Exception {
        String json = postJson("/live-ui/toggle-auto-execute");
        assertNotNull(json);
        assertTrue(json.contains("\"success\":true"));
        assertTrue(json.contains("\"autoExecute\""));
    }

    @Test
    @DisplayName("Toggle macro filter returns macroFilterEnabled field")
    void toggleMacroFilter_returnsValidResponse() throws Exception {
        String json = postJson("/live-ui/toggle-macro-filter");
        assertNotNull(json);
        assertTrue(json.contains("\"success\":true"));
        assertTrue(json.contains("\"macroFilterEnabled\""));
    }

    @Test
    @DisplayName("Set max concurrent with valid count returns 200 and confirms value")
    void setMaxConcurrent_validCount_returns200() throws Exception {
        String json = postJson("/live-ui/set-max-concurrent?count=4");
        assertNotNull(json);
        assertTrue(json.contains("\"success\":true"));
        assertTrue(json.contains("\"maxConcurrentScans\":4"));
    }

    @Test
    @DisplayName("Set max concurrent with count=0 returns 400")
    void setMaxConcurrent_invalidCount_returns400() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> resp = client.send(
            HttpRequest.newBuilder(URI.create(BASE_URL + "/live-ui/set-max-concurrent?count=0"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .header("Content-Type", "application/x-www-form-urlencoded")
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(400, resp.statusCode());
        assertTrue(resp.body().contains("\"success\":false"));
    }

    @Test
    @DisplayName("Set risk with valid percentage returns riskPct field")
    void setRisk_validPct_returns200() throws Exception {
        String json = postJson("/live-ui/set-risk?pct=1.5");
        assertNotNull(json);
        assertTrue(json.contains("\"success\":true"));
        assertTrue(json.contains("\"riskPct\""));
    }

    @Test
    @DisplayName("Market status returns session and timing fields")
    void getMarketStatus_returnsExpectedFields() throws Exception {
        String json = getJson("/live-ui/market-status");
        assertNotNull(json);
        assertTrue(json.contains("\"session\""));
        assertTrue(json.contains("\"nowEt\""));
        assertTrue(json.contains("\"nextOpenEt\""));
        assertTrue(json.contains("\"secondsToNextOpen\""));
    }

    @Test
    @DisplayName("Scan activity returns activity and scanning state fields")
    void getScanActivity_returnsStructure() throws Exception {
        String json = getJson("/live-ui/scan-activity");
        assertNotNull(json);
        assertTrue(json.contains("\"activity\""));
        assertTrue(json.contains("\"isScanning\""));
        assertTrue(json.contains("\"batchLabel\""));
        assertTrue(json.contains("\"scanned\""));
        assertTrue(json.contains("\"total\""));
    }

    @Test
    @DisplayName("Force stop returns success and wasScanning fields")
    void forceStop_returnsSuccess() throws Exception {
        String json = postJson("/live-ui/force-stop");
        assertNotNull(json);
        assertTrue(json.contains("\"success\":true"));
        assertTrue(json.contains("\"wasScanning\""));
    }
}
