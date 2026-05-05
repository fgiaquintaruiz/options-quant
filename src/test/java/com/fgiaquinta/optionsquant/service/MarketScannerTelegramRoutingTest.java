package com.fgiaquinta.optionsquant.service;

import com.fgiaquinta.optionsquant.config.IbkrProperties;
import com.fgiaquinta.optionsquant.config.ScannerProperties;
import com.fgiaquinta.optionsquant.controller.LiveModeController;
import com.fgiaquinta.optionsquant.service.StrategyScannerService.Signal;
import com.fgiaquinta.optionsquant.strategy.model.TradePlan;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class MarketScannerTelegramRoutingTest {

    private MarketScanner marketScanner;
    private TelegramService telegramService;
    private IbkrProperties ibkrProperties;
    private LiveModeController liveModeController;

    private static final TradePlan PLAN = new TradePlan(100.0, 110.0, 95.0, true, LocalTime.of(16, 0));

    @BeforeEach
    void setUp() {
        StrategyScannerService scannerService = mock(StrategyScannerService.class);
        ibkrProperties = mock(IbkrProperties.class);
        OrderExecutionService orderExecutionService = mock(OrderExecutionService.class);
        MacroEnvironmentFilter macroFilter = mock(MacroEnvironmentFilter.class);
        telegramService = mock(TelegramService.class);
        TrailingStopMonitor trailingStopMonitor = mock(TrailingStopMonitor.class);
        liveModeController = mock(LiveModeController.class);
        MarketCalendarService marketCalendar = mock(MarketCalendarService.class);
        ScannerProperties scannerProperties = mock(ScannerProperties.class);
        ScanPrioritizationService scanPrioritizationService = mock(ScanPrioritizationService.class);

        when(scannerProperties.exclusiveScanSchedulerLockWaitMs()).thenReturn(5000L);
        when(scannerProperties.livePreemptWaitMs()).thenReturn(60_000L);
        when(ibkrProperties.autoExecute()).thenReturn(false);

        marketScanner = new MarketScanner(
                scannerService, ibkrProperties, orderExecutionService,
                macroFilter, telegramService, trailingStopMonitor,
                liveModeController, marketCalendar, scannerProperties,
                scanPrioritizationService
        );
    }

    // ─── sendTelegramForScanSignal routing ──────────────────────────────────────

    @Nested
    @DisplayName("sendTelegramForScanSignal routing")
    class RoutingTests {

        @Test
        @DisplayName("autoExecute=true → sendAutoExecuteSignal called, sendSignal never called")
        void autoExecute_true_routesToSendAutoExecuteSignal() {
            when(ibkrProperties.autoExecute()).thenReturn(true);
            when(liveModeController.isLiveSignalOlderThanMaxAge(any())).thenReturn(false);

            Signal signal = freshSignal("NVDA", "momentum", "CALL", 500.0, PLAN);

            boolean notified = marketScanner.sendTelegramForScanSignal(signal);

            assertThat(notified).isTrue();
            verify(telegramService).sendAutoExecuteSignal(
                    eq("NVDA"), eq("momentum"), eq("CALL"), eq(500.0),
                    eq(PLAN.takeProfit), eq(PLAN.stopLoss)
            );
            verify(telegramService, never()).sendSignal(
                    anyString(), anyString(), anyString(), anyDouble(),
                    anyDouble(), anyDouble(), anyString()
            );
        }

        @Test
        @DisplayName("autoExecute=false → sendSignal called, sendAutoExecuteSignal never called")
        void autoExecute_false_routesToSendSignal() {
            when(ibkrProperties.autoExecute()).thenReturn(false);
            when(liveModeController.isLiveSignalOlderThanMaxAge(any())).thenReturn(false);
            when(telegramService.generateSecureOrderId(anyString(), anyString(), anyString(), anyDouble()))
                    .thenReturn("secure-id-123");

            Signal signal = freshSignal("MSFT", "breakout", "PUT", 420.0, PLAN);

            boolean notified = marketScanner.sendTelegramForScanSignal(signal);

            assertThat(notified).isTrue();
            verify(telegramService).sendSignal(
                    eq("MSFT"), eq("breakout"), eq("PUT"), eq(420.0),
                    eq(PLAN.takeProfit), eq(PLAN.stopLoss), anyString()
            );
            verify(telegramService, never()).sendAutoExecuteSignal(
                    anyString(), anyString(), anyString(), anyDouble(), anyDouble(), anyDouble()
            );
        }

        @Test
        @DisplayName("autoExecute=true passes correct ticker, strategy, price, direction to Telegram")
        void autoExecute_true_passesCorrectFields() {
            when(ibkrProperties.autoExecute()).thenReturn(true);
            when(liveModeController.isLiveSignalOlderThanMaxAge(any())).thenReturn(false);

            TradePlan plan = new TradePlan(200.0, 220.0, 190.0, false, LocalTime.of(16, 0));
            Signal signal = freshSignal("TSLA", "reversal", "PUT", 200.0, plan);

            marketScanner.sendTelegramForScanSignal(signal);

            verify(telegramService).sendAutoExecuteSignal(
                    "TSLA", "reversal", "PUT", 200.0, 220.0, 190.0
            );
        }

        @Test
        @DisplayName("autoExecute=false passes correct ticker, strategy, price, direction to Telegram")
        void autoExecute_false_passesCorrectFields() {
            when(ibkrProperties.autoExecute()).thenReturn(false);
            when(liveModeController.isLiveSignalOlderThanMaxAge(any())).thenReturn(false);
            when(telegramService.generateSecureOrderId("AAPL", "squeeze", "CALL", 150.0))
                    .thenReturn("order-xyz");

            TradePlan plan = new TradePlan(150.0, 165.0, 142.0, true, LocalTime.of(16, 0));
            Signal signal = freshSignal("AAPL", "squeeze", "CALL", 150.0, plan);

            marketScanner.sendTelegramForScanSignal(signal);

            verify(telegramService).sendSignal(
                    "AAPL", "squeeze", "CALL", 150.0, 165.0, 142.0, "order-xyz"
            );
        }
    }

    // ─── shouldNotifyTelegram staleness guard ────────────────────────────────────

    @Nested
    @DisplayName("shouldNotifyTelegram staleness guard (via sendTelegramForScanSignal)")
    class StalenessGuardTests {

        @Test
        @DisplayName("Fresh candle (<30 min) → notification sent, returns true")
        void freshCandle_notifies() {
            when(ibkrProperties.autoExecute()).thenReturn(true);
            when(liveModeController.isLiveSignalOlderThanMaxAge(any())).thenReturn(false);

            Signal signal = freshSignal("SPY", "momentum", "CALL", 500.0, PLAN);

            boolean result = marketScanner.sendTelegramForScanSignal(signal);

            assertThat(result).isTrue();
            verify(telegramService).sendAutoExecuteSignal(anyString(), anyString(), anyString(),
                    anyDouble(), anyDouble(), anyDouble());
        }

        @Test
        @DisplayName("Stale candle (>30 min) → notification skipped, returns false")
        void staleCandle_skipsNotification() {
            when(liveModeController.isLiveSignalOlderThanMaxAge(any())).thenReturn(true);

            Signal signal = staleSignal("SPY", "momentum", "CALL", 500.0, PLAN);

            boolean result = marketScanner.sendTelegramForScanSignal(signal);

            assertThat(result).isFalse();
            verifyNoInteractions(telegramService);
        }

        @Test
        @DisplayName("Null timestamp → notification skipped, returns false")
        void nullTimestamp_skipsNotification() {
            Signal signal = new Signal("QQQ", "trend", "PUT", 400.0, null, PLAN);

            boolean result = marketScanner.sendTelegramForScanSignal(signal);

            assertThat(result).isFalse();
            verifyNoInteractions(telegramService);
        }

        @Test
        @DisplayName("Null tradePlan → returns false immediately, Telegram never called")
        void nullTradePlan_returnsFalseImmediately() {
            Signal signal = new Signal("QQQ", "trend", "PUT", 400.0,
                    ZonedDateTime.now(ZoneId.of("Europe/Madrid")), null);

            boolean result = marketScanner.sendTelegramForScanSignal(signal);

            assertThat(result).isFalse();
            verifyNoInteractions(telegramService);
            verifyNoInteractions(liveModeController);
        }
    }

    // ─── processLiveSignalAfterScan integration ──────────────────────────────────

    @Nested
    @DisplayName("processLiveSignalAfterScan → Telegram delegation")
    class ProcessLiveSignalIntegrationTests {

        @Test
        @DisplayName("Fresh signal detected → sendTelegramForScanSignal called (Telegram invoked)")
        void freshSignal_triggersTelegramCall() {
            when(ibkrProperties.autoExecute()).thenReturn(true);
            when(liveModeController.isLiveSignalOlderThanMaxAge(any())).thenReturn(false);
            when(liveModeController.isRuntimeAutoExecute()).thenReturn(false);

            Signal signal = freshSignal("NVDA", "momentum", "CALL", 500.0, PLAN);

            marketScanner.processLiveSignalAfterScan(signal, ZonedDateTime.now(ZoneId.of("Europe/Madrid")));

            verify(telegramService).sendAutoExecuteSignal(
                    eq("NVDA"), eq("momentum"), eq("CALL"), eq(500.0),
                    eq(PLAN.takeProfit), eq(PLAN.stopLoss)
            );
        }

        @Test
        @DisplayName("Stale candle guard fires → Telegram NOT called")
        void staleCandle_telegramNotCalled() {
            when(liveModeController.isLiveSignalOlderThanMaxAge(any())).thenReturn(true);

            Signal signal = staleSignal("NVDA", "momentum", "CALL", 500.0, PLAN);

            marketScanner.processLiveSignalAfterScan(signal, ZonedDateTime.now(ZoneId.of("Europe/Madrid")));

            verifyNoInteractions(telegramService);
        }
    }

    // ─── helpers ─────────────────────────────────────────────────────────────────

    private Signal freshSignal(String ticker, String strategy, String direction,
                                double price, TradePlan plan) {
        return new Signal(ticker, strategy, direction, price,
                ZonedDateTime.now(ZoneId.of("Europe/Madrid")), plan);
    }

    private Signal staleSignal(String ticker, String strategy, String direction,
                                double price, TradePlan plan) {
        ZonedDateTime stale = ZonedDateTime.now(ZoneId.of("Europe/Madrid")).minusMinutes(45);
        return new Signal(ticker, strategy, direction, price, stale, plan);
    }
}
