package com.fgiaquinta.optionsquant.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fgiaquinta.optionsquant.OptionsQuantApplication;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Requires a running TWS / IB Gateway session with API enabled (paper is fine).
 * Run only with: {@code ./gradlew twsTest} (sets {@code -DrunTwsTests=true}).
 * <p>
 * Fails immediately if the account session is not logged in ({@code accountConnected} / {@code connected} false).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = OptionsQuantApplication.class)
@Tag("tws-paper")
@EnabledIfSystemProperty(named = "runTwsTests", matches = "true")
@DisplayName("TWS paper connectivity")
class TwsPaperConnectivityTest {

    @LocalServerPort
    private int port;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("GET /live-ui/tws-status shows logged-in account (fail-fast if not)")
    void twsMustShowAccountConnected() throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/live-ui/tws-status"))
                        .timeout(Duration.ofSeconds(10))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        Assertions.assertEquals(200, response.statusCode(), () -> "tws-status HTTP: " + response.body());
        JsonNode n = objectMapper.readTree(response.body());
        boolean account = n.path("accountConnected").asBoolean(false);
        boolean overall = n.path("connected").asBoolean(false);
        Assertions.assertTrue(account && overall,
                "TWS/Gateway: iniciá sesión en paper/live y habilitá la API. "
                        + "Se esperaba accountConnected=true y connected=true. Respuesta: "
                        + response.body());
    }
}
