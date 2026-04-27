package com.fgiaquinta.optionsquant.controller;

import com.ib.client.Contract;
import com.fgiaquinta.optionsquant.config.IbkrProperties;
import com.fgiaquinta.optionsquant.config.ScannerProperties;
import com.fgiaquinta.optionsquant.dto.ExternalPositionDto;
import com.fgiaquinta.optionsquant.dto.PositionSnapshot;
import com.fgiaquinta.optionsquant.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@code GET /live-ui/external-positions}.
 *
 * <p>Strategy: construct {@link LiveModeController} directly (no Spring context),
 * mock {@link AccountManager} to seed {@code getPositionsSnapshot()} return values,
 * and use reflection to seed {@code executedTrades} — the same pattern used in
 * {@link LiveModeControllerCloseTradeTest} and {@link LiveModeControllerStartupListenerTest}.
 */
class LiveModeControllerExternalPositionsTest {

    private LiveModeController controller;
    private AccountManager accountManager;
    private OrderExecutionService orderExecutionService;

    @BeforeEach
    void setUp() {
        final StrategyScannerService scannerService = mock(StrategyScannerService.class);
        final IbkrProperties ibkrProperties = mock(IbkrProperties.class);
        when(ibkrProperties.autoExecute()).thenReturn(false);
        when(ibkrProperties.riskPerTradePct()).thenReturn(0.02);

        final TradingService tradingService = mock(TradingService.class);
        final TickerService tickerService = mock(TickerService.class);
        this.accountManager = mock(AccountManager.class);
        // Default: snapshot empty, connected
        when(accountManager.getPositionsSnapshot()).thenReturn(Map.of());
        when(accountManager.isConnected()).thenReturn(true);

        final IbkrService ibkrService = mock(IbkrService.class);
        this.orderExecutionService = mock(OrderExecutionService.class);
        final MarketCalendarService marketCalendarService = mock(MarketCalendarService.class);
        final MarketScanner marketScanner = mock(MarketScanner.class);

        final ScannerProperties scannerProperties = mock(ScannerProperties.class);
        when(scannerProperties.concurrentMode()).thenReturn(ScannerProperties.ConcurrentMode.AUTO);
        when(scannerProperties.prioritizationMode()).thenReturn(ScannerProperties.PrioritizationMode.HYBRID);
        when(scannerProperties.hybridFundamentalWeight()).thenReturn(0.65);
        when(scannerProperties.hybridMemoryWeight()).thenReturn(0.35);
        when(scannerProperties.exclusiveScanSchedulerLockWaitMs()).thenReturn(5000L);
        when(scannerProperties.livePreemptWaitMs()).thenReturn(60_000L);

        final MacroEnvironmentFilter macroFilter = mock(MacroEnvironmentFilter.class);

        this.controller = new LiveModeController(
                scannerService, ibkrProperties, tradingService, tickerService,
                accountManager, ibkrService, this.orderExecutionService,
                marketCalendarService, marketScanner, scannerProperties, macroFilter,
                mock(ScanPrioritizationService.class)
        );
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Seeds executedTrades via reflection — no public test hook exists for this field. */
    private void seedExecutedTrades(String... tickers) throws Exception {
        Field field = LiveModeController.class.getDeclaredField("executedTrades");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        ConcurrentHashMap<String, LiveModeController.ExecutedTradeInfo> map =
                (ConcurrentHashMap<String, LiveModeController.ExecutedTradeInfo>) field.get(controller);
        map.clear();
        for (String ticker : tickers) {
            map.put(ticker, new LiveModeController.ExecutedTradeInfo(
                    ticker, Instant.now().toString(), true, "seeded", null, null, null));
        }
    }

    private PositionSnapshot snapshot(String ticker) {
        return new PositionSnapshot(ticker, "STK", null, 100, 150.0, Instant.now());
    }

    /** Creates a PositionSnapshot with a real Contract for endpoints that use contract/quantity. */
    private PositionSnapshot snapshotWithContract(String ticker) {
        Contract c = new Contract();
        c.symbol(ticker);
        c.secType("STK");
        return new PositionSnapshot(ticker, "STK", c, 50, 120.0, Instant.now());
    }

    /** Seeds the scheduled1450Tickers set via reflection. */
    private void seedScheduled1450(String... tickers) throws Exception {
        Field field = LiveModeController.class.getDeclaredField("scheduled1450Tickers");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Set<String> set = (Set<String>) field.get(controller);
        set.clear();
        for (String t : tickers) {
            set.add(t);
        }
    }

    // -----------------------------------------------------------------------
    // Tests
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("GET /external-positions returns 200 with empty list when snapshot is empty")
    void getExternalPositions_emptySnapshot_returnsEmptyList() {
        // GIVEN snapshot empty, executedTrades empty (defaults from setUp)

        // WHEN
        ResponseEntity<Map<String, Object>> response = controller.getExternalPositions();

        // THEN
        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        @SuppressWarnings("unchecked")
        List<?> positions = (List<?>) response.getBody().get("positions");
        assertNotNull(positions, "Body must contain 'positions' key");
        assertTrue(positions.isEmpty(), "positions should be empty");
    }

    @Test
    @DisplayName("GET /external-positions returns 200 with all positions when executedTrades is empty")
    void getExternalPositions_allExternal_returnsAllPositions() {
        // GIVEN 3 positions, executedTrades empty
        when(accountManager.getPositionsSnapshot()).thenReturn(Map.of(
                "NVDA", snapshot("NVDA"),
                "AAPL", snapshot("AAPL"),
                "MSFT", snapshot("MSFT")
        ));

        // WHEN
        ResponseEntity<Map<String, Object>> response = controller.getExternalPositions();

        // THEN
        assertEquals(200, response.getStatusCode().value());
        @SuppressWarnings("unchecked")
        List<ExternalPositionDto> positions = (List<ExternalPositionDto>) response.getBody().get("positions");
        assertNotNull(positions);
        assertEquals(3, positions.size(), "All 3 positions should be returned");
        positions.forEach(dto ->
                assertEquals("external", dto.classification(),
                        "classification must be 'external' for all entries"));
    }

    @Test
    @DisplayName("GET /external-positions filters out app-tracked tickers from executedTrades")
    void getExternalPositions_mixedTrades_filtersAppTracked() throws Exception {
        // GIVEN snapshot has NVDA, AAPL, MSFT; executedTrades has NVDA
        when(accountManager.getPositionsSnapshot()).thenReturn(Map.of(
                "NVDA", snapshot("NVDA"),
                "AAPL", snapshot("AAPL"),
                "MSFT", snapshot("MSFT")
        ));
        seedExecutedTrades("NVDA");

        // WHEN
        ResponseEntity<Map<String, Object>> response = controller.getExternalPositions();

        // THEN
        assertEquals(200, response.getStatusCode().value());
        @SuppressWarnings("unchecked")
        List<ExternalPositionDto> positions = (List<ExternalPositionDto>) response.getBody().get("positions");
        assertNotNull(positions);
        assertEquals(2, positions.size(), "NVDA should be filtered; only AAPL and MSFT remain");
        List<String> tickers = positions.stream()
                .map(ExternalPositionDto::ticker)
                .toList();
        assertFalse(tickers.contains("NVDA"), "NVDA must not appear (app-tracked)");
        assertTrue(tickers.contains("AAPL"), "AAPL must appear");
        assertTrue(tickers.contains("MSFT"), "MSFT must appear");
    }

    @Test
    @DisplayName("GET /external-positions returns 200 with empty list when all positions are app-tracked")
    void getExternalPositions_allAppTracked_returnsEmptyList() throws Exception {
        // GIVEN snapshot has NVDA, AAPL; executedTrades has both
        when(accountManager.getPositionsSnapshot()).thenReturn(Map.of(
                "NVDA", snapshot("NVDA"),
                "AAPL", snapshot("AAPL")
        ));
        seedExecutedTrades("NVDA", "AAPL");

        // WHEN
        ResponseEntity<Map<String, Object>> response = controller.getExternalPositions();

        // THEN
        assertEquals(200, response.getStatusCode().value());
        @SuppressWarnings("unchecked")
        List<?> positions = (List<?>) response.getBody().get("positions");
        assertNotNull(positions);
        assertTrue(positions.isEmpty(), "All positions are app-tracked; list must be empty");
    }

    @Test
    @DisplayName("GET /external-positions returns 503 when TWS is disconnected")
    void getExternalPositions_twsDisconnected_returns503() {
        // GIVEN TWS disconnected
        when(accountManager.isConnected()).thenReturn(false);

        // WHEN
        ResponseEntity<Map<String, Object>> response = controller.getExternalPositions();

        // THEN
        assertEquals(503, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertNotNull(response.getBody().get("error"), "Body must contain 'error' key");
    }

    @Test
    @DisplayName("GET /external-positions uses snapshot as source of truth — result only contains snapshot entries")
    void getExternalPositions_resultOnlyContainsSnapshotEntries() {
        // GIVEN snapshot has only TSLA
        when(accountManager.getPositionsSnapshot()).thenReturn(Map.of(
                "TSLA", snapshot("TSLA")
        ));

        // WHEN
        ResponseEntity<Map<String, Object>> response = controller.getExternalPositions();

        // THEN
        @SuppressWarnings("unchecked")
        List<ExternalPositionDto> positions = (List<ExternalPositionDto>) response.getBody().get("positions");
        assertNotNull(positions);
        assertEquals(1, positions.size());
        assertEquals("TSLA", positions.get(0).ticker(),
                "Result must only contain tickers present in the snapshot");
    }

    // -----------------------------------------------------------------------
    // TASK 3.2 — POST /live-ui/external-positions/{ticker}/close
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("POST /external-positions/{ticker}/close — 200 + orderId when ticker is valid external position")
    void closeExternalPosition_validExternalTicker_returns200WithOrderId() {
        // GIVEN ticker in snapshot, not in executedTrades, orderExecutionService returns orderId 42
        when(accountManager.getPositionsSnapshot()).thenReturn(Map.of(
                "NVDA", snapshotWithContract("NVDA")
        ));
        when(orderExecutionService.placeMarketSellExternal(any(Contract.class), anyInt())).thenReturn(42);

        // WHEN
        ResponseEntity<Map<String, Object>> response = controller.closeExternalPosition("NVDA");

        // THEN
        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals("Market SELL placed", response.getBody().get("message"));
        assertEquals(42, response.getBody().get("orderId"));
        verify(orderExecutionService, times(1)).placeMarketSellExternal(any(Contract.class), anyInt());
    }

    @Test
    @DisplayName("POST /external-positions/{ticker}/close — 404 when ticker not in snapshot")
    void closeExternalPosition_tickerNotInSnapshot_returns404() {
        // GIVEN snapshot is empty
        when(accountManager.getPositionsSnapshot()).thenReturn(Map.of());

        // WHEN
        ResponseEntity<Map<String, Object>> response = controller.closeExternalPosition("NVDA");

        // THEN
        assertEquals(404, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertNotNull(response.getBody().get("error"));
        verify(orderExecutionService, never()).placeMarketSellExternal(any(), anyInt());
    }

    @Test
    @DisplayName("POST /external-positions/{ticker}/close — 404 when ticker is in executedTrades (app-tracked)")
    void closeExternalPosition_appTrackedTicker_returns404() throws Exception {
        // GIVEN ticker in snapshot AND in executedTrades
        when(accountManager.getPositionsSnapshot()).thenReturn(Map.of(
                "NVDA", snapshotWithContract("NVDA")
        ));
        seedExecutedTrades("NVDA");

        // WHEN
        ResponseEntity<Map<String, Object>> response = controller.closeExternalPosition("NVDA");

        // THEN
        assertEquals(404, response.getStatusCode().value());
        assertNotNull(response.getBody().get("error"));
        verify(orderExecutionService, never()).placeMarketSellExternal(any(), anyInt());
    }

    @Test
    @DisplayName("POST /external-positions/{ticker}/close — 503 when TWS disconnected")
    void closeExternalPosition_twsDisconnected_returns503() {
        // GIVEN TWS disconnected
        when(accountManager.isConnected()).thenReturn(false);

        // WHEN
        ResponseEntity<Map<String, Object>> response = controller.closeExternalPosition("NVDA");

        // THEN
        assertEquals(503, response.getStatusCode().value());
        assertNotNull(response.getBody().get("error"));
        verify(orderExecutionService, never()).placeMarketSellExternal(any(), anyInt());
    }

    // -----------------------------------------------------------------------
    // TASK 3.3 — POST /live-ui/external-positions/{ticker}/schedule-close-1450
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("POST /external-positions/{ticker}/schedule-close-1450 — 201 with orderId on valid ticker")
    void scheduleClose1450_validExternalTicker_returns201WithOrderId() {
        // GIVEN ticker in snapshot, not in executedTrades, conditional order returns orderId 77
        when(accountManager.getPositionsSnapshot()).thenReturn(Map.of(
                "AAPL", snapshotWithContract("AAPL")
        ));
        when(orderExecutionService.placeConditionalOrder(any(Contract.class), any(com.ib.client.Order.class), any(com.ib.client.OrderCondition.class)))
                .thenReturn(77);

        // WHEN
        ResponseEntity<Map<String, Object>> response = controller.scheduleClose1450("AAPL");

        // THEN
        assertEquals(201, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals(77, response.getBody().get("orderId"));
        assertEquals("14:50 close scheduled", response.getBody().get("message"));
        verify(orderExecutionService, times(1)).placeConditionalOrder(
                any(Contract.class), any(com.ib.client.Order.class), any(com.ib.client.OrderCondition.class));
    }

    @Test
    @DisplayName("POST /external-positions/{ticker}/schedule-close-1450 — 409 on duplicate schedule")
    void scheduleClose1450_alreadyScheduled_returns409() throws Exception {
        // GIVEN ticker valid AND already in scheduled1450Tickers
        when(accountManager.getPositionsSnapshot()).thenReturn(Map.of(
                "AAPL", snapshotWithContract("AAPL")
        ));
        seedScheduled1450("AAPL");

        // WHEN second call
        ResponseEntity<Map<String, Object>> response = controller.scheduleClose1450("AAPL");

        // THEN 409 and placeConditionalOrder never called
        assertEquals(409, response.getStatusCode().value());
        assertTrue(response.getBody().get("error").toString().contains("AAPL"),
                "Error message should mention the ticker");
        verify(orderExecutionService, never()).placeConditionalOrder(any(), any(), any());
    }

    @Test
    @DisplayName("POST /external-positions/{ticker}/schedule-close-1450 — 404 when ticker not in snapshot")
    void scheduleClose1450_tickerNotInSnapshot_returns404() {
        // GIVEN empty snapshot
        when(accountManager.getPositionsSnapshot()).thenReturn(Map.of());

        // WHEN
        ResponseEntity<Map<String, Object>> response = controller.scheduleClose1450("TSLA");

        // THEN
        assertEquals(404, response.getStatusCode().value());
        verify(orderExecutionService, never()).placeConditionalOrder(any(), any(), any());
    }

    @Test
    @DisplayName("POST /external-positions/{ticker}/schedule-close-1450 — 404 when ticker is app-tracked")
    void scheduleClose1450_appTrackedTicker_returns404() throws Exception {
        // GIVEN ticker in snapshot and executedTrades
        when(accountManager.getPositionsSnapshot()).thenReturn(Map.of(
                "AMD", snapshotWithContract("AMD")
        ));
        seedExecutedTrades("AMD");

        // WHEN
        ResponseEntity<Map<String, Object>> response = controller.scheduleClose1450("AMD");

        // THEN
        assertEquals(404, response.getStatusCode().value());
        verify(orderExecutionService, never()).placeConditionalOrder(any(), any(), any());
    }

    @Test
    @DisplayName("POST /external-positions/{ticker}/schedule-close-1450 — 503 when TWS disconnected")
    void scheduleClose1450_twsDisconnected_returns503() {
        // GIVEN TWS disconnected
        when(accountManager.isConnected()).thenReturn(false);

        // WHEN
        ResponseEntity<Map<String, Object>> response = controller.scheduleClose1450("MSFT");

        // THEN
        assertEquals(503, response.getStatusCode().value());
        verify(orderExecutionService, never()).placeConditionalOrder(any(), any(), any());
    }

    @Test
    @DisplayName("POST /external-positions/{ticker}/schedule-close-1450 — idempotency: second call does NOT place second order")
    void scheduleClose1450_secondCallSameTicker_doesNotPlaceSecondOrder() {
        // GIVEN valid ticker
        when(accountManager.getPositionsSnapshot()).thenReturn(Map.of(
                "NVDA", snapshotWithContract("NVDA")
        ));
        when(orderExecutionService.placeConditionalOrder(any(), any(), any())).thenReturn(10);

        // WHEN first call succeeds
        ResponseEntity<Map<String, Object>> first = controller.scheduleClose1450("NVDA");
        assertEquals(201, first.getStatusCode().value());

        // AND second call for same ticker
        ResponseEntity<Map<String, Object>> second = controller.scheduleClose1450("NVDA");
        assertEquals(409, second.getStatusCode().value());

        // THEN placeConditionalOrder called exactly ONCE total
        verify(orderExecutionService, times(1)).placeConditionalOrder(any(), any(), any());
    }

    @Test
    @DisplayName("GET /external-positions DTO has snake_case JSON keys (contract_type, avg_cost, snapshot_timestamp)")
    void getExternalPositions_dtoShape_hasSnakeCaseKeys() {
        // GIVEN one position
        when(accountManager.getPositionsSnapshot()).thenReturn(Map.of(
                "AMD", new PositionSnapshot("AMD", "STK", null, 50, 120.5,
                        Instant.parse("2025-01-15T10:30:00Z"))
        ));

        // WHEN
        ResponseEntity<Map<String, Object>> response = controller.getExternalPositions();

        // THEN — verify the DTO record directly (Jackson snake_case serialization is covered by ExternalPositionDtoTest)
        @SuppressWarnings("unchecked")
        List<ExternalPositionDto> positions = (List<ExternalPositionDto>) response.getBody().get("positions");
        assertNotNull(positions);
        assertEquals(1, positions.size());

        ExternalPositionDto extDto = positions.get(0);
        assertNotNull(extDto, "DTO must not be null");
        assertEquals("AMD", extDto.ticker());
        assertEquals("STK", extDto.contractType());
        assertEquals(50, extDto.quantity());
        assertEquals(120.5, extDto.avgCost(), 0.001);
        assertEquals("external", extDto.classification());
        assertNotNull(extDto.snapshotTimestamp());
    }
}
