package com.fgiaquinta.optionsquant.controller;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ib.client.Contract;
import com.fgiaquinta.optionsquant.config.IbkrProperties;
import com.fgiaquinta.optionsquant.config.ScannerProperties;
import com.fgiaquinta.optionsquant.service.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link LiveModeController#onAppReadyCheckExternalPositions()}.
 *
 * <p>Tests verify that a WARN is logged when the app starts with zero tracked trades
 * but TWS already reports open positions — the "restart with external positions" scenario.
 */
class LiveModeControllerStartupListenerTest {

    private LiveModeController controller;
    private AccountManager accountManager;
    private ListAppender<ILoggingEvent> logAppender;
    private Logger controllerLogger;

    @BeforeEach
    void setUp() {
        StrategyScannerService scannerService = mock(StrategyScannerService.class);
        IbkrProperties ibkrProperties = mock(IbkrProperties.class);
        when(ibkrProperties.autoExecute()).thenReturn(false);
        when(ibkrProperties.riskPerTradePct()).thenReturn(0.02);
        when(ibkrProperties.hotTickers()).thenReturn(List.of("SPY", "QQQ"));

        TradingService tradingService = mock(TradingService.class);
        TickerService tickerService = mock(TickerService.class);

        accountManager = mock(AccountManager.class);
        when(accountManager.isConnected()).thenReturn(false);

        IbkrService ibkrService = mock(IbkrService.class);
        OrderExecutionService orderExecutionService = mock(OrderExecutionService.class);
        MarketCalendarService marketCalendarService = mock(MarketCalendarService.class);
        MarketScanner marketScanner = mock(MarketScanner.class);
        when(marketScanner.isSchedulerEnabled()).thenReturn(false);
        when(scannerService.getMaxConcurrentScans()).thenReturn(4);
        when(scannerService.getScannedCount()).thenReturn(0);
        when(scannerService.getCurrentBatchLabel()).thenReturn("batch-1");
        when(scannerService.getTotalToScan()).thenReturn(100);

        ScannerProperties scannerProperties = mock(ScannerProperties.class);
        when(scannerProperties.concurrentMode()).thenReturn(ScannerProperties.ConcurrentMode.AUTO);
        when(scannerProperties.prioritizationMode()).thenReturn(ScannerProperties.PrioritizationMode.HYBRID);
        when(scannerProperties.hybridFundamentalWeight()).thenReturn(0.65);
        when(scannerProperties.hybridMemoryWeight()).thenReturn(0.35);
        when(scannerProperties.exclusiveScanSchedulerLockWaitMs()).thenReturn(5000L);
        when(scannerProperties.livePreemptWaitMs()).thenReturn(60_000L);

        MacroEnvironmentFilter macroFilter = mock(MacroEnvironmentFilter.class);
        when(macroFilter.getRegime()).thenReturn(MacroEnvironmentFilter.MarketRegime.NEUTRAL);
        when(macroFilter.getMomentum()).thenReturn(MacroEnvironmentFilter.ShortTermMomentum.FLAT);

        controller = new LiveModeController(
                scannerService, ibkrProperties, tradingService, tickerService,
                accountManager, ibkrService, orderExecutionService,
                marketCalendarService, marketScanner, scannerProperties, macroFilter,
                mock(ScanPrioritizationService.class)
        );

        // Attach ListAppender to LiveModeController logger to capture log output
        controllerLogger = (Logger) LoggerFactory.getLogger(LiveModeController.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        controllerLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        controllerLogger.detachAppender(logAppender);
    }

    // ── Positive: WARN emitted when snapshot has positions but executedTrades is empty ───

    @Test
    @DisplayName("onAppReadyCheckExternalPositions_withEmptyTradesAndNonEmptySnapshot_logsWarnWithCount")
    void onAppReadyCheckExternalPositions_withEmptyTradesAndNonEmptySnapshot_logsWarnWithCount() {
        // GIVEN — 3 fake positions in TWS, zero app-tracked trades
        Map<String, com.fgiaquinta.optionsquant.dto.PositionSnapshot> fakeSnapshot = Map.of(
                "NVDA", fakeSnapshot("NVDA", "STK"),
                "SPY",  fakeSnapshot("SPY",  "STK"),
                "AMD",  fakeSnapshot("AMD",  "OPT")
        );
        when(accountManager.getPositionsSnapshot()).thenReturn(fakeSnapshot);
        // executedTrades is empty by default (no trades injected)

        // WHEN — startup listener fires
        controller.onAppReadyCheckExternalPositions();

        // THEN — a WARN containing the count and expected wording is logged
        assertThat(logAppender.list)
                .filteredOn(e -> e.getLevel() == Level.WARN)
                .anyMatch(e -> e.getFormattedMessage().contains("3 TWS positions")
                        && e.getFormattedMessage().contains("all positions will display as external"));
    }

    // ── Negative 1: no WARN when snapshot is empty ────────────────────────────

    @Test
    @DisplayName("onAppReadyCheckExternalPositions_withEmptySnapshot_noWarnLogged")
    void onAppReadyCheckExternalPositions_withEmptySnapshot_noWarnLogged() {
        // GIVEN — empty TWS snapshot (nothing open)
        when(accountManager.getPositionsSnapshot()).thenReturn(Map.of());

        // WHEN
        controller.onAppReadyCheckExternalPositions();

        // THEN — no WARN
        assertThat(logAppender.list)
                .filteredOn(e -> e.getLevel() == Level.WARN)
                .isEmpty();
    }

    // ── Negative 2: no WARN when executedTrades is non-empty ──────────────────

    @Test
    @DisplayName("onAppReadyCheckExternalPositions_withNonEmptyExecutedTrades_noWarnLogged")
    void onAppReadyCheckExternalPositions_withNonEmptyExecutedTrades_noWarnLogged() {
        // GIVEN — TWS has a position, but the app has tracked at least one trade
        Map<String, com.fgiaquinta.optionsquant.dto.PositionSnapshot> fakeSnapshot = Map.of(
                "NVDA", fakeSnapshot("NVDA", "STK")
        );
        when(accountManager.getPositionsSnapshot()).thenReturn(fakeSnapshot);

        // Inject a tracked trade by calling the package-private addLiveSignal path indirectly:
        // use the public executeTrade path is too heavy — instead seed via addLiveSignal + a
        // fake ExecutedTradeInfo. LiveModeController.executedTrades is private but we can
        // simulate non-empty state by invoking any public method that writes to it.
        // Simplest: inject via getExecutedTrades() is read-only. Instead reflect or use
        // the public execute path's return. We can't. Use reflection here only because
        // there is no test hook available for executedTrades in this controller.
        try {
            var field = LiveModeController.class.getDeclaredField("executedTrades");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            var map = (java.util.concurrent.ConcurrentHashMap<String, LiveModeController.ExecutedTradeInfo>) field.get(controller);
            map.put("NVDA", new LiveModeController.ExecutedTradeInfo("NVDA", "10:00:00", true, "Executed", 1, 2, 3));
        } catch (Exception e) {
            throw new RuntimeException("Test setup failed: cannot inject executedTrades", e);
        }

        // WHEN
        controller.onAppReadyCheckExternalPositions();

        // THEN — no WARN because executedTrades is non-empty
        assertThat(logAppender.list)
                .filteredOn(e -> e.getLevel() == Level.WARN)
                .isEmpty();
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private com.fgiaquinta.optionsquant.dto.PositionSnapshot fakeSnapshot(String symbol, String secType) {
        Contract contract = new Contract();
        contract.symbol(symbol);
        contract.secType(secType);
        return new com.fgiaquinta.optionsquant.dto.PositionSnapshot(symbol, secType, contract, 10, 100.0, Instant.now());
    }
}
