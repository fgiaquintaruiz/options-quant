package com.fgiaquinta.optionsquant.controller;

import com.fgiaquinta.optionsquant.service.TelegramService;
import com.fgiaquinta.optionsquant.service.TradingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link TelegramWebhookController#handleWebhook}.
 *
 * <p>Scenarios covered:
 * <ul>
 *   <li>Happy path: valid secret + non-trade payload → 200 OK.</li>
 *   <li>Security gate: missing secret header → 403.</li>
 *   <li>Security gate: wrong secret → 403.</li>
 *   <li>Security fallback: unconfigured secret → allows all (insecure fallback documented).</li>
 *   <li>Callback query dispatch: {@code E|} prefix → routes to trade → 200 OK.</li>
 *   <li>Trade command: malformed format (&lt;4 parts) → 400 Bad Request.</li>
 *   <li>Trade command: invalid price (non-numeric) → 400 Bad Request.</li>
 *   <li>Trade command: successful execution → 200 with confirmation, no raw exception.</li>
 *   <li>Trade command: execution failure (service returns null) → 500, sanitized body.</li>
 *   <li>Webhook processing throws → 500, generic body, no exception class or stack trace.</li>
 *   <li>Non-callback, non-command payload → 200 OK.</li>
 * </ul>
 *
 * <p>Security assertions: every non-2xx body is scanned for forbidden strings
 * ({@code 127.0.0.1}, {@code localhost}, {@code Exception}, {@code StackTrace}).
 */
class TelegramWebhookControllerTest {

    private static final String VALID_SECRET = "super-secret-token";

    private TelegramWebhookController controller;
    private TelegramService telegramService;
    private TradingService tradingService;

    @BeforeEach
    void setUp() {
        telegramService = mock(TelegramService.class);
        tradingService = mock(TradingService.class);
        controller = new TelegramWebhookController(telegramService, tradingService);
        when(telegramService.getWebhookSecret()).thenReturn(VALID_SECRET);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private MockRequest request(String secret) {
        return new MockRequest(secret);
    }

    private MockRequest requestNoSecret() {
        return new MockRequest(null);
    }

    private void assertNoLeaks(Map<String, Object> body) {
        String bodyStr = body == null ? "" : body.toString();
        assertThat(bodyStr).doesNotContain("127.0.0.1");
        assertThat(bodyStr).doesNotContain("localhost");
        assertThat(bodyStr).doesNotContain("Exception");
        assertThat(bodyStr).doesNotContain("StackTrace");
        assertThat(bodyStr).doesNotContain("at com.fgiaquinta.optionsquant");
    }

    // ── Happy path: valid secret, non-trade payload ───────────────────────────

    @Test
    @DisplayName("POST /webhook with valid secret and empty payload → 200 OK, status=ok")
    void handleWebhook_validSecretEmptyPayload_returns200Ok() {
        ResponseEntity<Map<String, Object>> res =
                controller.handleWebhook(Map.of(), request(VALID_SECRET));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).containsEntry("status", "ok");
    }

    // ── Security gate: missing header ─────────────────────────────────────────

    @Test
    @DisplayName("POST /webhook with missing X-Telegram-Bot-Api-Secret-Token → 403 Forbidden")
    void handleWebhook_missingSecretHeader_returns403() {
        ResponseEntity<Map<String, Object>> res =
                controller.handleWebhook(Map.of(), requestNoSecret());

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(res.getBody()).containsKey("error");
        assertNoLeaks(res.getBody());
    }

    // ── Security gate: wrong secret ───────────────────────────────────────────

    @Test
    @DisplayName("POST /webhook with wrong secret → 403 Forbidden, body sanitized")
    void handleWebhook_wrongSecret_returns403() {
        ResponseEntity<Map<String, Object>> res =
                controller.handleWebhook(Map.of(), request("wrong-secret"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        Map<String, Object> body = res.getBody();
        assertThat(body).containsKey("error");
        assertNoLeaks(body);
        String errorMsg = (String) body.get("error");
        assertThat(errorMsg).doesNotContain("super-secret");
        assertThat(errorMsg).doesNotContain("wrong-secret");
    }

    // ── Security fallback: unconfigured secret ────────────────────────────────

    @Test
    @DisplayName("POST /webhook when secret is unconfigured placeholder → allows request (insecure fallback)")
    void handleWebhook_unconfiguredSecret_allowsRequestInsecure() {
        when(telegramService.getWebhookSecret()).thenReturn("YOUR_WEBHOOK_SECRET_HERE");

        ResponseEntity<Map<String, Object>> res =
                controller.handleWebhook(Map.of(), requestNoSecret());

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ── Callback query dispatch ───────────────────────────────────────────────

    @Test
    @DisplayName("POST /webhook with E| callback_query → routes to trade → 200 OK with status=executed")
    void handleWebhook_callbackQueryEprefix_routesToTradeAndReturns200() {
        var orderResult = mock(com.fgiaquinta.optionsquant.service.OrderExecutionService.OrderResult.class);
        when(orderResult.parentId()).thenReturn(99);
        when(tradingService.executeManualTrade(eq("AAPL"), eq("squeeze"), eq("CALL"), eq(180.0)))
                .thenReturn(orderResult);

        Map<String, Object> callbackQuery = Map.of(
                "id", "cq-001",
                "data", "E|AAPL|squeeze|180.0|CALL"
        );
        Map<String, Object> payload = Map.of("callback_query", callbackQuery);

        ResponseEntity<Map<String, Object>> res =
                controller.handleWebhook(payload, request(VALID_SECRET));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).containsEntry("status", "executed");
        assertThat(res.getBody()).containsEntry("ticker", "AAPL");
        verify(tradingService).executeManualTrade("AAPL", "squeeze", "CALL", 180.0);
        verify(telegramService).answerCallbackQuery(eq("cq-001"), anyString());
    }

    // ── Trade command: malformed format ───────────────────────────────────────

    @Test
    @DisplayName("POST /webhook with callback_query having fewer than 4 parts → 400 Bad Request")
    void handleWebhook_malformedCallbackData_returns400() {
        Map<String, Object> callbackQuery = Map.of(
                "id", "cq-bad",
                "data", "E|AAPL|squeeze"
        );
        Map<String, Object> payload = Map.of("callback_query", callbackQuery);

        ResponseEntity<Map<String, Object>> res =
                controller.handleWebhook(payload, request(VALID_SECRET));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        Map<String, Object> body = res.getBody();
        assertThat(body).containsKey("error");
        assertNoLeaks(body);
        verifyNoInteractions(tradingService);
    }

    // ── Trade command: invalid price ──────────────────────────────────────────

    @Test
    @DisplayName("POST /webhook with non-numeric price in callback → 400 Bad Request, no exception leaked")
    void handleWebhook_invalidPriceInCallback_returns400() {
        Map<String, Object> callbackQuery = Map.of(
                "id", "cq-price",
                "data", "E|AAPL|squeeze|not-a-price|CALL"
        );
        Map<String, Object> payload = Map.of("callback_query", callbackQuery);

        ResponseEntity<Map<String, Object>> res =
                controller.handleWebhook(payload, request(VALID_SECRET));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        Map<String, Object> body = res.getBody();
        assertThat(body).containsKey("error");
        assertNoLeaks(body);
        verifyNoInteractions(tradingService);
    }

    // ── Trade command: successful execution ───────────────────────────────────

    @Test
    @DisplayName("POST /webhook with valid E| callback and successful trade → 200 OK, body has ticker, no raw exception")
    void handleWebhook_successfulTradeExecution_returns200WithConfirmation() {
        var orderResult = mock(com.fgiaquinta.optionsquant.service.OrderExecutionService.OrderResult.class);
        when(tradingService.executeManualTrade(eq("TSLA"), eq("rsi_strat"), eq("PUT"), eq(250.5)))
                .thenReturn(orderResult);

        Map<String, Object> callbackQuery = Map.of(
                "id", "cq-trade",
                "data", "E|TSLA|rsi_strat|250.5|PUT"
        );
        Map<String, Object> payload = Map.of("callback_query", callbackQuery);

        ResponseEntity<Map<String, Object>> res =
                controller.handleWebhook(payload, request(VALID_SECRET));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = res.getBody();
        assertThat(body).containsEntry("ticker", "TSLA");
        assertThat(body).containsEntry("status", "executed");
        assertNoLeaks(body);
        verify(telegramService).sendTradeConfirmation(eq("TSLA"), eq("rsi_strat"), eq("PUT"),
                anyInt(), eq(250.5), anyInt());
    }

    // ── Trade command: execution failure ─────────────────────────────────────

    @Test
    @DisplayName("POST /webhook when trade service returns null → 500, body sanitized, no stack trace")
    void handleWebhook_tradeExecutionFails_returns500WithSanitizedBody() {
        when(tradingService.executeManualTrade(anyString(), anyString(), anyString(), anyDouble()))
                .thenReturn(null);

        Map<String, Object> callbackQuery = Map.of(
                "id", "cq-fail",
                "data", "E|NVDA|squeeze|500.0|CALL"
        );
        Map<String, Object> payload = Map.of("callback_query", callbackQuery);

        ResponseEntity<Map<String, Object>> res =
                controller.handleWebhook(payload, request(VALID_SECRET));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        Map<String, Object> body = res.getBody();
        assertThat(body).containsKey("error");
        assertNoLeaks(body);
        String errorMsg = (String) body.get("error");
        assertThat(errorMsg).doesNotContain("NullPointerException");
        assertThat(errorMsg).doesNotContain("null");
    }

    // ── Exception in webhook processing ──────────────────────────────────────

    @Test
    @DisplayName("POST /webhook when getWebhookSecret throws → 500, generic error body, no exception class leaked")
    void handleWebhook_unexpectedException_returns500WithGenericBody() {
        when(telegramService.getWebhookSecret())
                .thenThrow(new RuntimeException("jdbc://internal-db:5432 down — NPE in config"));

        ResponseEntity<Map<String, Object>> res =
                controller.handleWebhook(Map.of(), requestNoSecret());

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        Map<String, Object> body = res.getBody();
        assertThat(body).isNotNull();
        assertThat(body).containsKey("error");
        // The raw exception message must NOT appear in the response body.
        String errorStr = body.toString();
        assertThat(errorStr).doesNotContain("jdbc");
        assertThat(errorStr).doesNotContain("NPE");
        assertThat(errorStr).doesNotContain("internal-db");
        assertThat(errorStr).doesNotContain("RuntimeException");
        assertNoLeaks(body);
    }

    // ── Non-callback, non-command payload ────────────────────────────────────

    @Test
    @DisplayName("POST /webhook with regular message (no /command, no callback_query) → 200 OK, status=ok")
    void handleWebhook_regularMessagePayload_returns200Ok() {
        Map<String, Object> message = Map.of("text", "Hello bot!");
        Map<String, Object> payload = Map.of("message", message);

        ResponseEntity<Map<String, Object>> res =
                controller.handleWebhook(payload, request(VALID_SECRET));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).containsEntry("status", "ok");
        verifyNoInteractions(tradingService);
    }

    // ── Minimal mock for HttpServletRequest ───────────────────────────────────

    private static class MockRequest implements jakarta.servlet.http.HttpServletRequest {

        private final String secret;

        MockRequest(String secret) {
            this.secret = secret;
        }

        @Override
        public String getHeader(String name) {
            if ("X-Telegram-Bot-Api-Secret-Token".equals(name)) {
                return secret;
            }
            return null;
        }

        @Override public String getRemoteAddr() { return "127.0.0.1"; }

        // ── Unimplemented stubs (not needed for these tests) ──────────────────
        @Override public String getAuthType() { return null; }
        @Override public jakarta.servlet.http.Cookie[] getCookies() { return null; }
        @Override public long getDateHeader(String name) { return -1; }
        @Override public java.util.Enumeration<String> getHeaders(String name) { return java.util.Collections.emptyEnumeration(); }
        @Override public java.util.Enumeration<String> getHeaderNames() { return java.util.Collections.emptyEnumeration(); }
        @Override public int getIntHeader(String name) { return -1; }
        @Override public String getMethod() { return "POST"; }
        @Override public String getPathInfo() { return null; }
        @Override public String getPathTranslated() { return null; }
        @Override public String getContextPath() { return ""; }
        @Override public String getQueryString() { return null; }
        @Override public String getRemoteUser() { return null; }
        @Override public boolean isUserInRole(String role) { return false; }
        @Override public java.security.Principal getUserPrincipal() { return null; }
        @Override public String getRequestedSessionId() { return null; }
        @Override public String getRequestURI() { return "/webhook"; }
        @Override public StringBuffer getRequestURL() { return new StringBuffer("http://localhost/webhook"); }
        @Override public String getServletPath() { return "/webhook"; }
        @Override public jakarta.servlet.http.HttpSession getSession(boolean create) { return null; }
        @Override public jakarta.servlet.http.HttpSession getSession() { return null; }
        @Override public String changeSessionId() { return null; }
        @Override public boolean isRequestedSessionIdValid() { return false; }
        @Override public boolean isRequestedSessionIdFromCookie() { return false; }
        @Override public boolean isRequestedSessionIdFromURL() { return false; }
        @Override public boolean authenticate(jakarta.servlet.http.HttpServletResponse response) { return false; }
        @Override public void login(String username, String password) {}
        @Override public void logout() throws jakarta.servlet.ServletException {}
        @Override public java.util.Collection<jakarta.servlet.http.Part> getParts() { return java.util.Collections.emptyList(); }
        @Override public jakarta.servlet.http.Part getPart(String name) { return null; }
        @Override public <T extends jakarta.servlet.http.HttpUpgradeHandler> T upgrade(Class<T> handlerClass) { return null; }
        @Override public Object getAttribute(String name) { return null; }
        @Override public java.util.Enumeration<String> getAttributeNames() { return java.util.Collections.emptyEnumeration(); }
        @Override public String getCharacterEncoding() { return "UTF-8"; }
        @Override public void setCharacterEncoding(String env) {}
        @Override public int getContentLength() { return 0; }
        @Override public long getContentLengthLong() { return 0; }
        @Override public String getContentType() { return "application/json"; }
        @Override public jakarta.servlet.ServletInputStream getInputStream() { return null; }
        @Override public String getParameter(String name) { return null; }
        @Override public java.util.Enumeration<String> getParameterNames() { return java.util.Collections.emptyEnumeration(); }
        @Override public String[] getParameterValues(String name) { return null; }
        @Override public java.util.Map<String, String[]> getParameterMap() { return java.util.Collections.emptyMap(); }
        @Override public String getProtocol() { return "HTTP/1.1"; }
        @Override public String getScheme() { return "http"; }
        @Override public String getServerName() { return "localhost"; }
        @Override public int getServerPort() { return 8080; }
        @Override public java.io.BufferedReader getReader() { return null; }
        @Override public String getRemoteHost() { return "127.0.0.1"; }
        @Override public void setAttribute(String name, Object o) {}
        @Override public void removeAttribute(String name) {}
        @Override public java.util.Locale getLocale() { return java.util.Locale.getDefault(); }
        @Override public java.util.Enumeration<java.util.Locale> getLocales() { return java.util.Collections.enumeration(java.util.List.of(java.util.Locale.getDefault())); }
        @Override public boolean isSecure() { return false; }
        @Override public jakarta.servlet.RequestDispatcher getRequestDispatcher(String path) { return null; }
        @Override public int getRemotePort() { return 0; }
        @Override public String getLocalName() { return "localhost"; }
        @Override public String getLocalAddr() { return "127.0.0.1"; }
        @Override public int getLocalPort() { return 8080; }
        @Override public jakarta.servlet.ServletContext getServletContext() { return null; }
        @Override public jakarta.servlet.AsyncContext startAsync() { return null; }
        @Override public jakarta.servlet.AsyncContext startAsync(jakarta.servlet.ServletRequest servletRequest, jakarta.servlet.ServletResponse servletResponse) { return null; }
        @Override public boolean isAsyncStarted() { return false; }
        @Override public boolean isAsyncSupported() { return false; }
        @Override public jakarta.servlet.AsyncContext getAsyncContext() { return null; }
        @Override public jakarta.servlet.DispatcherType getDispatcherType() { return jakarta.servlet.DispatcherType.REQUEST; }
        @Override public String getRequestId() { return ""; }
        @Override public String getProtocolRequestId() { return ""; }
        @Override public jakarta.servlet.ServletConnection getServletConnection() { return null; }
    }
}
