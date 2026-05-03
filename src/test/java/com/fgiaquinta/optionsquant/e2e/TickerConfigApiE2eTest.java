package com.fgiaquinta.optionsquant.e2e;

import org.junit.jupiter.api.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.*;

@Tag("e2e")
@DisplayName("Ticker Config API E2E Tests")
class TickerConfigApiE2eTest extends BasePlaywrightTest {

    @Test
    @DisplayName("Get config returns universe, hot, entries, and fundamentals fields")
    void getConfig_returnsExpectedStructure() throws Exception {
        String json = getJson("/api/ticker-config");
        assertNotNull(json);
        assertTrue(json.contains("\"universe\""));
        assertTrue(json.contains("\"hot\""));
        assertTrue(json.contains("\"entries\""));
        assertTrue(json.contains("\"fundamentals\""));
        assertTrue(json.contains("\"hasRuntimeFile\""));
    }

    @Test
    @DisplayName("Validate known symbol AAPL returns valid field")
    void validateTicker_knownSymbol_returnsValid() throws Exception {
        String json = getJson("/api/ticker-config/validate?symbol=AAPL");
        assertNotNull(json);
        assertTrue(json.contains("\"valid\""));
    }

    @Test
    @DisplayName("Validate unknown symbol ZZZZZ99 returns non-null response")
    void validateTicker_unknownSymbol_returnsResponse() throws Exception {
        String json = getJson("/api/ticker-config/validate?symbol=ZZZZZ99");
        assertNotNull(json);
        // Either {valid:true} or {valid:false, reason:...} — always has valid field
        assertTrue(json.contains("\"valid\""));
    }

    @Test
    @DisplayName("Get hot ticker count returns effectiveCount field")
    void getHotTickerCount_returnsEffectiveCount() throws Exception {
        String json = getJson("/api/ticker-config/hot-ticker-count");
        assertNotNull(json);
        assertTrue(json.contains("\"effectiveCount\""));
        assertTrue(json.contains("\"ymlDefault\""));
    }

    @Test
    @DisplayName("PUT hot ticker count with valid value returns 200 and effectiveCount")
    void putHotTickerCount_validValue_returns200() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> resp = client.send(
            HttpRequest.newBuilder(URI.create(BASE_URL + "/api/ticker-config/hot-ticker-count?count=10"))
                .PUT(HttpRequest.BodyPublishers.noBody())
                .header("Content-Type", "application/x-www-form-urlencoded")
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("\"success\":true"));
        assertTrue(resp.body().contains("\"effectiveCount\":10"));
    }

    @Test
    @DisplayName("PUT hot ticker count with out-of-range value returns 400")
    void putHotTickerCount_outOfRange_returns400() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> resp = client.send(
            HttpRequest.newBuilder(URI.create(BASE_URL + "/api/ticker-config/hot-ticker-count?count=101"))
                .PUT(HttpRequest.BodyPublishers.noBody())
                .header("Content-Type", "application/x-www-form-urlencoded")
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(400, resp.statusCode());
        assertTrue(resp.body().contains("\"error\""));
    }

    @Test
    @DisplayName("PUT config with valid payload returns 200 and success/universe fields")
    void putConfig_validPayload_returns200() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        String body = "{\"universe\":[\"SPY\",\"AAPL\"],\"hot\":[\"SPY\"],\"fundamentals\":{}}";
        HttpResponse<String> resp = client.send(
            HttpRequest.newBuilder(URI.create(BASE_URL + "/api/ticker-config"))
                .PUT(HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json")
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("success"));
        assertTrue(resp.body().contains("universe"));
    }

    @Test
    @DisplayName("DELETE symbol SPY returns 200 and success field")
    void deleteSymbol_validSymbol_returns200() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        String putBody = "{\"universe\":[\"SPY\",\"AAPL\"],\"hot\":[\"SPY\"],\"fundamentals\":{}}";
        client.send(
            HttpRequest.newBuilder(URI.create(BASE_URL + "/api/ticker-config"))
                .PUT(HttpRequest.BodyPublishers.ofString(putBody))
                .header("Content-Type", "application/json")
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );
        HttpResponse<String> resp = client.send(
            HttpRequest.newBuilder(URI.create(BASE_URL + "/api/ticker-config/symbol/SPY"))
                .DELETE()
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("success"));
    }
}
