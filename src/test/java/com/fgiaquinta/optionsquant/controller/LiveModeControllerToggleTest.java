package com.fgiaquinta.optionsquant.controller;

import com.fgiaquinta.optionsquant.config.IbkrProperties;
import com.fgiaquinta.optionsquant.config.ScannerProperties;
import com.fgiaquinta.optionsquant.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the 8 toggle/utility endpoints in {@link LiveModeController}.
 * <p>
 * Tests use direct controller instantiation (no Spring context) matching the pattern
 * established in LiveModeControllerTest and LiveModeControllerStatusTest.
 * </p>
 */
class LiveModeControllerToggleTest {

    private LiveModeController controller;
    private MarketScanner marketScanner;
    private IbkrProperties ibkrProperties;

    @BeforeEach
    void setUp() {
        StrategyScannerService scannerService = mock(StrategyScannerService.class);
        ibkrProperties = mock(IbkrProperties.class);
        when(ibkrProperties.autoExecute()).thenReturn(false);
        when(ibkrProperties.riskPerTradePct()).thenReturn(0.02);

        TradingService tradingService = mock(TradingService.class);
        TickerService tickerService = mock(TickerService.class);
        when(tickerService.getHotTickers()).thenReturn(java.util.List.of());

        AccountManager accountManager = mock(AccountManager.class);
        when(accountManager.isConnected()).thenReturn(false);

        IbkrService ibkrService = mock(IbkrService.class);
        when(ibkrService.isConnected()).thenReturn(false);

        OrderExecutionService orderExecutionService = mock(OrderExecutionService.class);
        MarketCalendarService marketCalendarService = mock(MarketCalendarService.class);

        marketScanner = mock(MarketScanner.class);
        when(marketScanner.isSchedulerEnabled()).thenReturn(false);

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
        when(macroFilter.getAnalysisString()).thenReturn("neutral");

        controller = new LiveModeController(
                scannerService, ibkrProperties, tradingService, tickerService,
                accountManager, ibkrService, orderExecutionService,
                marketCalendarService, marketScanner, scannerProperties, macroFilter,
                mock(ScanPrioritizationService.class)
        );
    }

    // =========================================================================
    // 1. POST /toggle-extended-hours
    // =========================================================================

    @Nested
    @DisplayName("POST /toggle-extended-hours")
    class ToggleExtendedHours {

        @Test
        @DisplayName("first call flips extended hours from true (default) to false")
        void firstToggle_disablesExtendedHours() {
            // WHEN — default state is true (see field initializer: AtomicBoolean(true))
            ResponseEntity<Map<String, Object>> response = controller.toggleExtendedHours();

            // THEN
            assertThat(response.getStatusCode().value()).isEqualTo(200);
            Map<String, Object> body = response.getBody();
            assertThat(body).isNotNull();
            assertThat(body).containsEntry("success", true);
            assertThat(body).containsEntry("extendedHours", false);
            assertThat(body).containsEntry("message", "Extended hours disabled");
        }

        @Test
        @DisplayName("two consecutive calls returns to the original state")
        void doubleToggle_returnsToOriginalState() {
            // WHEN
            controller.toggleExtendedHours();                               // true → false
            ResponseEntity<Map<String, Object>> response = controller.toggleExtendedHours(); // false → true

            // THEN
            Map<String, Object> body = response.getBody();
            assertThat(body).isNotNull();
            assertThat(body).containsEntry("extendedHours", true);
            assertThat(body).containsEntry("message", "Extended hours enabled");
        }

        @Test
        @DisplayName("isExtendedHoursEnabled reflects the toggled state")
        void accessorReflectsToggledState() {
            // Default is enabled
            assertThat(controller.isExtendedHoursEnabled()).isTrue();

            controller.toggleExtendedHours();

            assertThat(controller.isExtendedHoursEnabled()).isFalse();
        }
    }

    // =========================================================================
    // 2. POST /toggle-auto-execute
    // =========================================================================

    @Nested
    @DisplayName("POST /toggle-auto-execute")
    class ToggleAutoExecute {

        @Test
        @DisplayName("first call flips autoExecute from false (from mock ibkrProperties) to true")
        void firstToggle_enablesAutoExecute() {
            // ibkrProperties.autoExecute() returns false in setUp → runtimeAutoExecute starts false
            ResponseEntity<Map<String, Object>> response = controller.toggleAutoExecute();

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            Map<String, Object> body = response.getBody();
            assertThat(body).isNotNull();
            assertThat(body).containsEntry("success", true);
            assertThat(body).containsEntry("autoExecute", true);
        }

        @Test
        @DisplayName("second call returns autoExecute to false")
        void secondToggle_disablesAutoExecute() {
            controller.toggleAutoExecute(); // false → true
            ResponseEntity<Map<String, Object>> response = controller.toggleAutoExecute(); // true → false

            assertThat(response.getBody()).containsEntry("autoExecute", false);
        }

        @Test
        @DisplayName("isRuntimeAutoExecute() reflects the in-memory toggle state")
        void accessorReflectsState() {
            assertThat(controller.isRuntimeAutoExecute()).isFalse();
            controller.toggleAutoExecute();
            assertThat(controller.isRuntimeAutoExecute()).isTrue();
        }
    }

    // =========================================================================
    // 3. POST /toggle-macro-filter
    // =========================================================================

    @Nested
    @DisplayName("POST /toggle-macro-filter")
    class ToggleMacroFilter {

        @Test
        @DisplayName("first call flips macroFilterEnabled from true (default) to false")
        void firstToggle_disablesMacroFilter() {
            // Default runtimeMacroFilterEnabled = true (field initializer)
            ResponseEntity<Map<String, Object>> response = controller.toggleMacroFilter();

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            Map<String, Object> body = response.getBody();
            assertThat(body).isNotNull();
            assertThat(body).containsEntry("success", true);
            assertThat(body).containsEntry("macroFilterEnabled", false);
        }

        @Test
        @DisplayName("second call re-enables macro filter")
        void secondToggle_enablesMacroFilter() {
            controller.toggleMacroFilter(); // true → false
            ResponseEntity<Map<String, Object>> response = controller.toggleMacroFilter(); // false → true

            assertThat(response.getBody()).containsEntry("macroFilterEnabled", true);
        }

        @Test
        @DisplayName("isRuntimeMacroFilterEnabled() accessor is consistent with toggle")
        void accessorIsConsistentWithToggle() {
            assertThat(controller.isRuntimeMacroFilterEnabled()).isTrue();
            controller.toggleMacroFilter();
            assertThat(controller.isRuntimeMacroFilterEnabled()).isFalse();
        }
    }

    // =========================================================================
    // 4. POST /set-risk
    // =========================================================================

    @Nested
    @DisplayName("POST /set-risk")
    class SetRisk {

        @Test
        @DisplayName("valid percentage is stored and echoed in response")
        void validRisk_storedAndReturned() {
            // WHEN — pass 1.5 (meaning 1.5%)
            ResponseEntity<Map<String, Object>> response = controller.setRisk(1.5);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            Map<String, Object> body = response.getBody();
            assertThat(body).isNotNull();
            assertThat(body).containsEntry("success", true);
            assertThat(body).containsEntry("riskPct", 1.5);
        }

        @Test
        @DisplayName("set-risk stores value as fraction (pct/100) visible via getStatus")
        void setRisk_storedAsFraction_visibleInStatus() {
            // GIVEN — mock dependencies needed by getStatus
            // The controller was set up with mocks that have default return values
            // We just verify riskPct in the status endpoint reflects the division by 100
            // Note: getStatus rounds: Math.round(riskPct * 100 * 10.0) / 10.0
            // So 2.0 → stored as 0.02 → status shows 2.0
            controller.setRisk(2.0);

            // We verify via getStatus that riskPct is 2.0 (Math.round(0.02 * 100 * 10.0) / 10.0)
            ResponseEntity<Map<String, Object>> statusResponse = controller.getStatus();
            assertThat(statusResponse.getBody()).containsEntry("riskPct", 2.0);
        }

        @Test
        @DisplayName("zero risk is accepted")
        void zeroRisk_isAccepted() {
            ResponseEntity<Map<String, Object>> response = controller.setRisk(0.0);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody()).containsEntry("success", true);
            assertThat(response.getBody()).containsEntry("riskPct", 0.0);
        }
    }

    // =========================================================================
    // 5. POST /toggle-scheduler
    // =========================================================================

    @Nested
    @DisplayName("POST /toggle-scheduler")
    class ToggleScheduler {

        @Test
        @DisplayName("when scheduler is disabled, toggle enables it and calls setSchedulerEnabled(true)")
        void schedulerDisabled_toggleEnablesIt() {
            // marketScanner.isSchedulerEnabled() returns false (set up in setUp)
            ResponseEntity<Map<String, Object>> response = controller.toggleScheduler();

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            Map<String, Object> body = response.getBody();
            assertThat(body).isNotNull();
            assertThat(body).containsEntry("success", true);
            assertThat(body).containsEntry("schedulerEnabled", true);
            verify(marketScanner).setSchedulerEnabled(true);
        }

        @Test
        @DisplayName("when scheduler is enabled, toggle disables it and calls setSchedulerEnabled(false)")
        void schedulerEnabled_toggleDisablesIt() {
            // Override mock to return true
            when(marketScanner.isSchedulerEnabled()).thenReturn(true);

            ResponseEntity<Map<String, Object>> response = controller.toggleScheduler();

            assertThat(response.getBody()).containsEntry("schedulerEnabled", false);
            verify(marketScanner).setSchedulerEnabled(false);
        }
    }

    // =========================================================================
    // 6. POST /toggle-mock-market
    // =========================================================================

    @Nested
    @DisplayName("POST /toggle-mock-market")
    class ToggleMockMarket {

        @Test
        @DisplayName("first call sets mockMarketOpen to true (default is false)")
        void firstToggle_enablesMockMarket() {
            // Default mockMarketOpen = false (AtomicBoolean(false))
            ResponseEntity<Map<String, Object>> response = controller.toggleMockMarket();

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            Map<String, Object> body = response.getBody();
            assertThat(body).isNotNull();
            assertThat(body).containsEntry("success", true);
            assertThat(body).containsEntry("mockMarketOpen", true);
        }

        @Test
        @DisplayName("second call returns mockMarketOpen to false")
        void secondToggle_disablesMockMarket() {
            controller.toggleMockMarket(); // false → true
            ResponseEntity<Map<String, Object>> response = controller.toggleMockMarket(); // true → false

            assertThat(response.getBody()).containsEntry("mockMarketOpen", false);
        }

        @Test
        @DisplayName("mockMarketOpen state is visible in /status after toggle")
        void mockMarketOpenVisibleInStatus() {
            controller.toggleMockMarket(); // now true

            ResponseEntity<Map<String, Object>> statusResponse = controller.getStatus();
            assertThat(statusResponse.getBody()).containsEntry("mockMarketOpen", true);
        }
    }

    // =========================================================================
    // 7. POST /inject-mock-signal
    // =========================================================================

    @Nested
    @DisplayName("POST /inject-mock-signal")
    class InjectMockSignal {

        @Test
        @DisplayName("default ticker SPY produces a valid response with all expected fields")
        void defaultTicker_returnsExpectedFields() {
            // GIVEN — marketScanner mock needed for sendTelegramForScanSignal
            when(marketScanner.sendTelegramForScanSignal(any())).thenReturn(false);

            // WHEN — no params → defaults apply (ticker=SPY, strategy=null, direction=null)
            ResponseEntity<Map<String, Object>> response = controller.injectMockSignal("SPY", null, null);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            Map<String, Object> body = response.getBody();
            assertThat(body).isNotNull();
            assertThat(body).containsEntry("success", true);
            assertThat(body).containsEntry("ticker", "SPY");
            assertThat(body).containsKey("strategy");
            assertThat(body).containsKey("direction");
            assertThat(body).containsKey("entryPrice");
            assertThat(body).containsKey("takeProfit");
            assertThat(body).containsKey("stopLoss");
            assertThat(body).containsEntry("autoExecuted", false); // no TWS connected
        }

        @Test
        @DisplayName("explicit strategy and direction are echoed in response")
        void explicitStrategyAndDirection_areEchoed() {
            when(marketScanner.sendTelegramForScanSignal(any())).thenReturn(false);

            ResponseEntity<Map<String, Object>> response =
                    controller.injectMockSignal("AAPL", "C1 Squeeze Breakout", "CALL");

            Map<String, Object> body = response.getBody();
            assertThat(body).isNotNull();
            assertThat(body).containsEntry("ticker", "AAPL");
            assertThat(body).containsEntry("strategy", "C1 Squeeze Breakout");
            assertThat(body).containsEntry("direction", "CALL");
        }

        @Test
        @DisplayName("injected signal is added to live signals list")
        void injectedSignal_appearsInGetSignals() {
            when(marketScanner.sendTelegramForScanSignal(any())).thenReturn(false);

            controller.injectMockSignal("TSLA", "P1 Squeeze Breakdown", "PUT");

            ResponseEntity<Map<String, Object>> signalsResponse = controller.getSignals();
            @SuppressWarnings("unchecked")
            java.util.List<Map<String, Object>> signals =
                    (java.util.List<Map<String, Object>>) signalsResponse.getBody().get("signals");
            assertThat(signals).anyMatch(s -> "TSLA".equals(s.get("ticker")));
        }

        @Test
        @DisplayName("PUT strategy starting with P sets direction to PUT when direction param is null")
        void putStrategyWithoutDirection_infersPUT() {
            when(marketScanner.sendTelegramForScanSignal(any())).thenReturn(false);

            ResponseEntity<Map<String, Object>> response =
                    controller.injectMockSignal("MSFT", "P2 Trend Rejection", null);

            assertThat(response.getBody()).containsEntry("direction", "PUT");
        }
    }

    // =========================================================================
    // 8. POST /set-scan-filter
    // =========================================================================

    @Nested
    @DisplayName("POST /set-scan-filter")
    class SetScanFilter {

        @Test
        @DisplayName("filter and scope are trimmed, uppercased, and echoed in response")
        void validFilterAndScope_storedAndEchoed() {
            ResponseEntity<Map<String, Object>> response =
                    controller.setScanFilter("aapl,msft ", "all");

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            Map<String, Object> body = response.getBody();
            assertThat(body).isNotNull();
            assertThat(body).containsEntry("success", true);
            assertThat(body).containsEntry("filter", "aapl,msft");   // trimmed only — no upper
            assertThat(body).containsEntry("scope", "ALL");           // uppercased
        }

        @Test
        @DisplayName("empty filter with HOT scope restores defaults")
        void emptyFilterWithHotScope_resetsToDefault() {
            // First set a filter
            controller.setScanFilter("SPY", "HOT");

            // Then reset
            ResponseEntity<Map<String, Object>> response = controller.setScanFilter("", "HOT");

            assertThat(response.getBody()).containsEntry("filter", "");
            assertThat(response.getBody()).containsEntry("scope", "HOT");
        }

        @Test
        @DisplayName("filter and scope are visible in /status after set")
        void filterAndScopeVisibleInStatus() {
            controller.setScanFilter("SPY,QQQ", "ALL");

            ResponseEntity<Map<String, Object>> statusResponse = controller.getStatus();
            assertThat(statusResponse.getBody())
                    .containsEntry("liveTickerFilter", "SPY,QQQ")
                    .containsEntry("liveTickerScope", "ALL");
        }

        @Test
        @DisplayName("blank filter string is treated as empty (no-op filter)")
        void blankFilter_treatedAsEmpty() {
            ResponseEntity<Map<String, Object>> response =
                    controller.setScanFilter("   ", "HOT");

            // trim() on "   " yields ""
            assertThat(response.getBody()).containsEntry("filter", "");
        }
    }
}
