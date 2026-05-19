package com.fgiaquinta.optionsquant.controller;

import com.fgiaquinta.optionsquant.config.IbkrProperties;
import com.fgiaquinta.optionsquant.config.ScannerProperties;
import com.fgiaquinta.optionsquant.service.*;
import com.fgiaquinta.optionsquant.service.StrategyScannerService.Signal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Integration test for the JSONL persistence path inside
 * {@link LiveModeController#addLiveSignal(Signal)}.
 *
 * <p>Verifies that every replay signal (i.e. {@code Signal.replay() == true}) is
 * appended to a {@code replay-signals-{date}.jsonl} file inside
 * {@code replaySignalDir}, and that the written JSON line contains all required
 * fields: {@code ticker}, {@code strategy}, {@code direction}, {@code price},
 * {@code timestamp}, and {@code pattern}.
 *
 * <p>Uses {@code @TempDir} to redirect file output away from the real filesystem.
 * No Spring context and no TWS connection required.
 */
@Tag("integration")
class LiveModeControllerReplayJsonlIntegrationTest {

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

        // Redirect JSONL output to @TempDir — avoids writing to the real "data/" directory
        ReflectionTestUtils.setField(controller, "replaySignalDir", tempDir.toString());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Task 6.3 — primary scenario
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("addLiveSignal con replay=true escribe una línea JSONL con todos los campos requeridos")
    void addLiveSignal_replaySignal_writesJsonlLineWithAllFields() throws IOException {
        // GIVEN — a replay signal with a known timestamp
        ZonedDateTime ts = ZonedDateTime.parse("2026-04-22T14:35:00Z");
        Signal signal = new Signal(
                "NVDA", "C1Squeeze", "BUY", 875.50,
                ts, null, "hammer", true
        );
        String expectedFile = "replay-signals-2026-04-22.jsonl";

        // WHEN
        controller.addLiveSignal(signal);

        // THEN — file exists in tempDir
        Path jsonlFile = tempDir.resolve(expectedFile);
        assertThat(jsonlFile).exists();

        List<String> lines = Files.readAllLines(jsonlFile);
        assertThat(lines).hasSize(1);

        String line = lines.get(0);
        assertThat(line)
                .as("ticker field must be present")
                .contains("\"ticker\":\"NVDA\"");
        assertThat(line)
                .as("strategy field must be present")
                .contains("\"strategy\":\"C1Squeeze\"");
        assertThat(line)
                .as("direction field must be present")
                .contains("\"direction\":\"BUY\"");
        assertThat(line)
                .as("price field must be present with 4 decimal places")
                .contains("\"price\":875.5000");
        assertThat(line)
                .as("timestamp field must be present")
                .contains("\"timestamp\":");
        assertThat(line)
                .as("pattern field must be present")
                .contains("\"pattern\":\"hammer\"");
    }

    @Test
    @DisplayName("addLiveSignal con replay=true acumula múltiples señales en el mismo archivo del día")
    void addLiveSignal_multipleReplaySignals_appendsAllLinesToSameFile() throws IOException {
        // GIVEN — two signals on the same date
        ZonedDateTime ts = ZonedDateTime.parse("2026-04-22T14:35:00Z");
        Signal first  = new Signal("NVDA", "C1Squeeze", "BUY",  875.50, ts, null, "hammer",  true);
        Signal second = new Signal("AAPL", "P2Trend",   "SELL", 172.30, ts, null, "engulfing", true);

        // WHEN
        controller.addLiveSignal(first);
        controller.addLiveSignal(second);

        // THEN — both lines in the same file (APPEND semantics)
        Path jsonlFile = tempDir.resolve("replay-signals-2026-04-22.jsonl");
        assertThat(jsonlFile).exists();

        List<String> lines = Files.readAllLines(jsonlFile);
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0)).contains("\"ticker\":\"NVDA\"");
        assertThat(lines.get(1)).contains("\"ticker\":\"AAPL\"");
    }

    @Test
    @DisplayName("addLiveSignal replay: el nombre del archivo contiene el runId del reloj activo")
    void addLiveSignal_replaySignal_filenameContainsRunId() throws IOException {
        // GIVEN — a replay clock is active with a known runId
        ReplayClock replayClock = new ReplayClock();
        replayClock.activate(java.time.ZonedDateTime.parse("2026-04-22T14:30:00Z"), 60, "R-abc1234");
        ReflectionTestUtils.setField(controller, "replayClock", replayClock);
        ReflectionTestUtils.setField(controller, "replaySignalDir", tempDir.toString());
        ReflectionTestUtils.setField(controller, "replaySignalsFilePattern", "replay-signals-%s-%s.jsonl");

        ZonedDateTime ts = ZonedDateTime.parse("2026-04-22T14:35:00Z");
        Signal signal = new Signal("TSLA", "C1Squeeze", "BUY", 250.0, ts, null, "hammer", true);

        // WHEN
        controller.addLiveSignal(signal);

        // THEN — file name contains both date AND runId
        boolean foundFileWithRunId = Files.list(tempDir)
                .anyMatch(p -> p.getFileName().toString().contains("R-abc1234"));
        assertThat(foundFileWithRunId)
                .as("JSONL filename must contain the runId (not just the date)")
                .isTrue();
    }

    @Test
    @DisplayName("addLiveSignal con replay=false NO escribe ningún archivo JSONL")
    void addLiveSignal_liveSignal_doesNotWriteAnyJsonlFile() throws IOException {
        // GIVEN — a non-replay (live) signal
        Signal live = new Signal(
                "MSFT", "EMA", "SELL", 415.00,
                ZonedDateTime.parse("2026-04-22T14:40:00Z"), null, "doji", false
        );

        // WHEN
        controller.addLiveSignal(live);

        // THEN — no JSONL file written to tempDir
        assertThat(Files.list(tempDir).findAny())
                .as("No JSONL file should be written for a non-replay signal")
                .isEmpty();
    }
}
