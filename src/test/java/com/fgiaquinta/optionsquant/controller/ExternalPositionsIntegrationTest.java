package com.fgiaquinta.optionsquant.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ib.client.Contract;
import com.fgiaquinta.optionsquant.OptionsQuantApplication;
import com.fgiaquinta.optionsquant.dto.PositionSnapshot;
import com.fgiaquinta.optionsquant.service.AccountManager;
import com.fgiaquinta.optionsquant.service.OrderExecutionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

/**
 * Integration tests for the external-positions endpoints in {@link LiveModeController}.
 *
 * <p>Boots the full Spring context on a random port. Uses {@link MockBean} to prevent
 * real TWS connections. Exercises the full HTTP stack: routing, Jackson serialization,
 * snake_case JSON keys, and HTTP status codes.
 *
 * <p>No {@code @Tag("tws-paper")} — these tests run in normal CI without TWS.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = OptionsQuantApplication.class)
class ExternalPositionsIntegrationTest {

    @LocalServerPort
    private int port;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @MockitoBean
    private AccountManager accountManager;

    @MockitoBean
    private OrderExecutionService orderExecutionService;

    private HttpClient httpClient;

    @BeforeEach
    void setUp() {
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        // Safe defaults: connected, empty snapshot
        when(accountManager.isConnected()).thenReturn(true);
        when(accountManager.getPositionsSnapshot()).thenReturn(Map.of());
    }

    // -------------------------------------------------------------------------
    // Test 1 — GET returns correct JSON structure (AC-1 + AC-5)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("GET /external-positions — HTTP 200 with JSON array containing snake_case keys")
    void getExternalPositions_returnsHttpOkWithJsonArray() throws Exception {
        // GIVEN one NVDA position in snapshot, TWS connected
        Contract contract = new Contract();
        contract.symbol("NVDA");
        contract.secType("STK");
        PositionSnapshot nvda = new PositionSnapshot("NVDA", "STK", contract, 100, 87.50, Instant.now());
        when(accountManager.getPositionsSnapshot()).thenReturn(Map.of("NVDA", nvda));

        // WHEN
        HttpResponse<String> response = httpClient.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/live-ui/external-positions"))
                        .timeout(Duration.ofSeconds(10))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        // THEN — HTTP 200
        assertThat(response.statusCode()).isEqualTo(200);

        JsonNode body = objectMapper.readTree(response.body());
        JsonNode positions = body.path("positions");

        // Array is present and non-empty
        assertThat(positions.isArray()).isTrue();
        assertThat(positions.size()).isGreaterThan(0);

        // First element has all expected snake_case keys
        JsonNode first = positions.get(0);
        assertThat(first.has("ticker")).isTrue();
        assertThat(first.has("contract_type")).isTrue();
        assertThat(first.has("quantity")).isTrue();
        assertThat(first.has("avg_cost")).isTrue();
        assertThat(first.has("snapshot_timestamp")).isTrue();

        // Value-level assertions
        assertThat(first.path("ticker").asText()).isEqualTo("NVDA");
    }

    // -------------------------------------------------------------------------
    // Test 2 — GET with executedTrades empty — both positions classified "external"
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("GET /external-positions — both positions returned as external when executedTrades is empty")
    void getExternalPositions_filtersAppTrackedPositions_returnsOnlyExternal() throws Exception {
        // GIVEN two positions in snapshot, executedTrades is empty (no app-tracked trades)
        Contract nvdaContract = new Contract();
        nvdaContract.symbol("NVDA");
        nvdaContract.secType("STK");
        Contract aaplContract = new Contract();
        aaplContract.symbol("AAPL");
        aaplContract.secType("STK");

        when(accountManager.getPositionsSnapshot()).thenReturn(Map.of(
                "NVDA", new PositionSnapshot("NVDA", "STK", nvdaContract, 100, 87.50, Instant.now()),
                "AAPL", new PositionSnapshot("AAPL", "STK", aaplContract, 50, 175.0, Instant.now())
        ));

        // WHEN
        HttpResponse<String> response = httpClient.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/live-ui/external-positions"))
                        .timeout(Duration.ofSeconds(10))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        // THEN — both positions appear; all classified "external"
        assertThat(response.statusCode()).isEqualTo(200);

        JsonNode body = objectMapper.readTree(response.body());
        JsonNode positions = body.path("positions");

        assertThat(positions.isArray()).isTrue();
        assertThat(positions.size()).isEqualTo(2);

        for (JsonNode pos : positions) {
            assertThat(pos.path("classification").asText()).isEqualTo("external");
        }
    }

    // -------------------------------------------------------------------------
    // Test 3 — GET returns 503 when TWS disconnected (AC-8)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("GET /external-positions — 503 with error key when TWS is disconnected")
    void getExternalPositions_returns503_whenTwsDisconnected() throws Exception {
        // GIVEN TWS disconnected
        when(accountManager.isConnected()).thenReturn(false);

        // WHEN
        HttpResponse<String> response = httpClient.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/live-ui/external-positions"))
                        .timeout(Duration.ofSeconds(10))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        // THEN — 503 with error body
        assertThat(response.statusCode()).isEqualTo(503);

        JsonNode body = objectMapper.readTree(response.body());
        assertThat(body.has("error")).isTrue();
        assertThat(body.path("error").asText().toLowerCase()).contains("not available");
    }

    // -------------------------------------------------------------------------
    // Test 4 — POST close returns 200 with orderId when position exists (AC-2)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("POST /external-positions/NVDA/close — 200 with order_id when position exists in snapshot")
    void closeExternalPosition_returns200_whenPositionExistsInSnapshot() throws Exception {
        // GIVEN NVDA in snapshot, execution returns orderId=42
        Contract contract = new Contract();
        contract.symbol("NVDA");
        contract.secType("STK");
        when(accountManager.getPositionsSnapshot()).thenReturn(Map.of(
                "NVDA", new PositionSnapshot("NVDA", "STK", contract, 100, 87.50, Instant.now())
        ));
        when(orderExecutionService.placeMarketSellExternal(any(Contract.class), anyInt())).thenReturn(42);

        // WHEN
        HttpResponse<String> response = httpClient.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/live-ui/external-positions/NVDA/close"))
                        .timeout(Duration.ofSeconds(10))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        // THEN — 200 with orderId
        assertThat(response.statusCode()).isEqualTo(200);

        JsonNode body = objectMapper.readTree(response.body());
        assertThat(body.has("orderId")).isTrue();
        assertThat(body.path("orderId").asInt()).isEqualTo(42);
    }

    // -------------------------------------------------------------------------
    // Test 5 — POST close returns 404 when position not in snapshot (AC-4)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("POST /external-positions/TSLA/close — 404 with error key when position not in snapshot")
    void closeExternalPosition_returns404_whenPositionNotInSnapshot() throws Exception {
        // GIVEN empty snapshot — TSLA is not there
        when(accountManager.getPositionsSnapshot()).thenReturn(Map.of());

        // WHEN
        HttpResponse<String> response = httpClient.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/live-ui/external-positions/TSLA/close"))
                        .timeout(Duration.ofSeconds(10))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        // THEN — 404 with error body
        assertThat(response.statusCode()).isEqualTo(404);

        JsonNode body = objectMapper.readTree(response.body());
        assertThat(body.has("error")).isTrue();
    }
}
