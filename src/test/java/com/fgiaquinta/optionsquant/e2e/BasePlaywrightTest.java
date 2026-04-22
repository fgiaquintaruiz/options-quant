package com.fgiaquinta.optionsquant.e2e;

import com.microsoft.playwright.*;
import com.fgiaquinta.optionsquant.OptionsQuantApplication;
import org.junit.jupiter.api.*;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URI;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Base class for Playwright E2E tests.
 * Starts the Spring Boot app once per test class, runs all tests, then stops it.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class BasePlaywrightTest {

    protected static int PORT;
    protected static String BASE_URL;

    protected Playwright playwright;
    protected Browser browser;
    protected BrowserContext context;
    protected Page page;
    protected static ConfigurableApplicationContext appContext;

    @BeforeAll
    void startApp() throws Exception {
        // Find a random available port
        try (ServerSocket socket = new ServerSocket(0)) {
            PORT = socket.getLocalPort();
        }
        BASE_URL = "http://localhost:" + PORT;

        // Start Spring Boot app on test port
        System.setProperty("server.port", String.valueOf(PORT));
        System.setProperty("backtest-cli.enabled", "false");
        System.setProperty("telegram.enabled", "false");
        System.setProperty("ollama.enabled", "false");
        appContext = SpringApplication.run(OptionsQuantApplication.class);

        // Wait for app to be ready
        HttpClient client = HttpClient.newHttpClient();
        for (int i = 0; i < 30; i++) {
            try {
                HttpResponse<String> resp = client.send(
                    HttpRequest.newBuilder(URI.create(BASE_URL + "/actuator/health")).build(),
                    HttpResponse.BodyHandlers.ofString()
                );
                if (resp.statusCode() == 200) break;
            } catch (Exception ignored) {}
            try { Thread.sleep(1000); } catch (InterruptedException e) { break; }
        }
    }

    @AfterAll
    void stopApp() {
        if (appContext != null) {
            appContext.close();
        }
        System.clearProperty("server.port");
        System.clearProperty("backtest-cli.enabled");
        System.clearProperty("telegram.enabled");
        System.clearProperty("ollama.enabled");
        PORT = 0;
        BASE_URL = null;
    }

    @BeforeEach
    void setUpBrowser() {
        playwright = Playwright.create();
        browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
        context = browser.newContext();
        page = context.newPage();
    }

    @AfterEach
    void tearDownBrowser() {
        if (page != null) page.close();
        if (context != null) context.close();
        if (browser != null) browser.close();
        if (playwright != null) playwright.close();
    }

    /**
     * Helper: GET request and return JSON as string.
     */
    protected String getJson(String path) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> resp = client.send(
            HttpRequest.newBuilder(URI.create(BASE_URL + path)).build(),
            HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(200, resp.statusCode(), "Expected 200 for " + path);
        return resp.body();
    }

    /**
     * Helper: POST request with no body.
     */
    protected String postJson(String path) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> resp = client.send(
            HttpRequest.newBuilder(URI.create(BASE_URL + path))
                .POST(HttpRequest.BodyPublishers.noBody())
                .header("Content-Type", "application/x-www-form-urlencoded")
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(200, resp.statusCode(), "Expected 200 for POST " + path);
        return resp.body();
    }

    /**
     * POST JSON body to the running app (full stack — not mocked).
     */
    protected HttpResponse<String> postJsonBody(String path, String jsonBody, int expectedStatus) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> resp = client.send(
                HttpRequest.newBuilder(URI.create(BASE_URL + path))
                        .timeout(Duration.ofSeconds(60))
                        .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                        .header("Content-Type", "application/json")
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(expectedStatus, resp.statusCode(),
                () -> "POST " + path + " expected " + expectedStatus + " got " + resp.statusCode() + ": " + resp.body());
        return resp;
    }
}
