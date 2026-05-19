package com.fgiaquinta.optionsquant.controller;

import com.fgiaquinta.optionsquant.config.IbkrProperties;
import com.fgiaquinta.optionsquant.config.ScannerProperties;
import com.fgiaquinta.optionsquant.service.*;
import com.fgiaquinta.optionsquant.service.StrategyScannerService.Signal;
import com.fgiaquinta.optionsquant.strategy.model.TradePlan;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies that the JSONL replay signal line includes a {@code tradePlan} JSON object
 * with {@code entry}, {@code tp}, and {@code sl} fields.
 *
 * Sub-task A of the replay serialization enhancement.
 */
@Tag("integration")
class LiveModeControllerReplayJsonlTradePlanTest {

    @TempDir
    Path tempDir;

    private LiveModeController controller;

    @BeforeEach
    void setUp() {
        IbkrProperties ibkrProperties = mock(IbkrProperties.class);
        when(ibkrProperties.autoExecute()).thenReturn(false);
        when(ibkrProperties.riskPerTradePct()).thenReturn(0.02);
        when(ibkrProperties.accountId()).thenReturn("DUN598126");

        ScannerProperties scannerProperties = mock(ScannerProperties.class);
        when(scannerProperties.concurrentMode()).thenReturn(ScannerProperties.ConcurrentMode.AUTO);
        when(scannerProperties.prioritizationMode()).thenReturn(ScannerProperties.PrioritizationMode.HYBRID);
        when(scannerProperties.hybridFundamentalWeight()).thenReturn(0.65);
        when(scannerProperties.hybridMemoryWeight()).thenReturn(0.35);
        when(scannerProperties.exclusiveScanSchedulerLockWaitMs()).thenReturn(5000L);
        when(scannerProperties.livePreemptWaitMs()).thenReturn(60_000L);

        controller = new LiveModeController(
                mock(StrategyScannerService.class), ibkrProperties, mock(TradingService.class),
                mock(TickerService.class), mock(AccountManager.class), mock(IbkrService.class),
                mock(OrderExecutionService.class), mock(MarketCalendarService.class),
                mock(MarketScanner.class), scannerProperties, mock(MacroEnvironmentFilter.class),
                mock(ScanPrioritizationService.class)
        );

        ReflectionTestUtils.setField(controller, "replaySignalDir", tempDir.toString());
    }

    @Test
    @DisplayName("JSONL line includes tradePlan with entry, tp, sl when signal has a TradePlan")
    void addLiveSignal_withTradePlan_includesTradePlanInJsonl() throws IOException {
        TradePlan plan = new TradePlan(875.50, 900.00, 850.00, true, LocalTime.of(15, 45));
        ZonedDateTime ts = ZonedDateTime.parse("2026-04-22T14:35:00Z");
        Signal signal = new Signal("NVDA", "C1Squeeze", "CALL", 875.50, ts, plan, "hammer", true);

        controller.addLiveSignal(signal);

        Path jsonlFile = tempDir.resolve("replay-signals-2026-04-22.jsonl");
        assertThat(jsonlFile).exists();

        List<String> lines = Files.readAllLines(jsonlFile);
        assertThat(lines).hasSize(1);

        String line = lines.get(0);
        assertThat(line)
                .as("tradePlan object must be present")
                .contains("\"tradePlan\":");
        assertThat(line)
                .as("entry field must contain the entry price")
                .contains("\"entry\":875");
        assertThat(line)
                .as("tp field must contain the take-profit price")
                .contains("\"tp\":900");
        assertThat(line)
                .as("sl field must contain the stop-loss price")
                .contains("\"sl\":850");
    }

    @Test
    @DisplayName("JSONL line includes tradePlan as null when signal has no TradePlan")
    void addLiveSignal_withNullTradePlan_includesNullTradePlanInJsonl() throws IOException {
        ZonedDateTime ts = ZonedDateTime.parse("2026-04-22T14:35:00Z");
        Signal signal = new Signal("AAPL", "P2Trend", "PUT", 172.00, ts, null, "engulfing", true);

        controller.addLiveSignal(signal);

        Path jsonlFile = tempDir.resolve("replay-signals-2026-04-22.jsonl");
        assertThat(jsonlFile).exists();

        String line = Files.readAllLines(jsonlFile).get(0);
        assertThat(line)
                .as("tradePlan key must be present even when null")
                .contains("\"tradePlan\":null");
    }

    @Test
    @DisplayName("tradePlan values use 4-decimal precision in the JSONL line")
    void addLiveSignal_withTradePlan_pricesHaveFourDecimalPrecision() throws IOException {
        TradePlan plan = new TradePlan(123.4567, 130.1234, 118.9876, true, LocalTime.of(15, 45));
        ZonedDateTime ts = ZonedDateTime.parse("2026-04-22T14:35:00Z");
        Signal signal = new Signal("TSLA", "C1Squeeze", "CALL", 123.4567, ts, plan, "squeeze_breakout", true);

        controller.addLiveSignal(signal);

        String line = Files.readAllLines(tempDir.resolve("replay-signals-2026-04-22.jsonl")).get(0);
        assertThat(line).contains("\"entry\":123.4567");
        assertThat(line).contains("\"tp\":130.1234");
        assertThat(line).contains("\"sl\":118.9876");
    }
}
