package com.fgiaquinta.optionsquant.service;

import com.fgiaquinta.optionsquant.domain.Candle;
import com.fgiaquinta.optionsquant.domain.TimeFrame;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CandleCsvServiceTest {

    private CandleCsvService service;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        service = new CandleCsvService();
        service.setDataDir(tempDir);
    }

    @Test
    @DisplayName("Should save and load candles correctly")
    void shouldSaveAndLoadCandles() {
        List<Candle> candles = List.of(
                new Candle(ZonedDateTime.of(2026, 4, 6, 16, 0, 0, 0, ZoneId.of("America/New_York")), 100.0, 105.0, 99.0, 103.0, 1000000L),
                new Candle(ZonedDateTime.of(2026, 4, 7, 16, 0, 0, 0, ZoneId.of("America/New_York")), 103.0, 108.0, 102.0, 107.0, 1200000L),
                new Candle(ZonedDateTime.of(2026, 4, 8, 16, 0, 0, 0, ZoneId.of("America/New_York")), 107.0, 110.0, 106.0, 109.0, 1500000L)
        );

        service.saveToCsv("SPY", TimeFrame.DAY_1, candles);

        List<Candle> loaded = service.loadFromCsv("SPY", TimeFrame.DAY_1);

        assertNotNull(loaded, "Loaded list should not be null");
        assertFalse(loaded.isEmpty(), "Loaded list should not be empty");
        assertEquals(3, loaded.size(), "Should have 3 candles. Actual: " + loaded.size());
        // Candle 0: 2026-04-06: O:100, H:105, L:99, C:103
        assertEquals(100.0, loaded.get(0).open());
        assertEquals(103.0, loaded.get(0).close());
        // Candle 2: 2026-04-08: O:107, H:110, L:106, C:109
        assertEquals(107.0, loaded.get(2).open());
        assertEquals(109.0, loaded.get(2).close());
    }

    @Test
    @DisplayName("Should return empty list when file does not exist")
    void shouldReturnEmptyWhenFileMissing() {
        List<Candle> candles = service.loadFromCsv("NONEXISTENT", TimeFrame.DAY_1);
        assertTrue(candles.isEmpty());
    }

    @Test
    @DisplayName("Should detect existing local data")
    void shouldDetectExistingData() {
        List<Candle> candles = List.of(
                new Candle(ZonedDateTime.of(2026, 4, 8, 16, 0, 0, 0, ZoneId.of("America/New_York")), 100.0, 105.0, 99.0, 103.0, 1000000L)
        );
        service.saveToCsv("AAPL", TimeFrame.DAY_1, candles);

        assertTrue(service.hasLocalData("AAPL", TimeFrame.DAY_1));
        assertFalse(service.hasLocalData("AAPL", TimeFrame.HOUR_1));
        assertFalse(service.hasLocalData("MSFT", TimeFrame.DAY_1));
    }

    @Test
    @DisplayName("Should handle empty candle list gracefully")
    void shouldHandleEmptyList() {
        assertDoesNotThrow(() -> service.saveToCsv("SPY", TimeFrame.DAY_1, List.of()));
    }

    @Test
    @DisplayName("TimeFrame should generate correct cache keys")
    void timeFrameCacheKeys() {
        assertEquals("SPY_1day", TimeFrame.DAY_1.toCacheKey("SPY"));
        assertEquals("AAPL_1hour", TimeFrame.HOUR_1.toCacheKey("AAPL"));
        assertEquals("MSFT_15min", TimeFrame.MIN_15.toCacheKey("MSFT"));
        assertEquals("TSLA_5min", TimeFrame.MIN_5.toCacheKey("TSLA"));
    }

    @Test
    @DisplayName("TimeFrame should have correct IBKR parameters")
    void timeFrameIbkrParameters() {
        assertEquals("1 day", TimeFrame.DAY_1.getIbkrBarSize());
        assertEquals("1 Y", TimeFrame.DAY_1.getIbkrDuration());
        assertEquals("1day", TimeFrame.DAY_1.getFileSuffix());

        assertEquals("5 mins", TimeFrame.MIN_5.getIbkrBarSize());
        assertEquals("10 D", TimeFrame.MIN_5.getIbkrDuration());
        assertEquals("5min", TimeFrame.MIN_5.getFileSuffix());
    }

    @Test
    @DisplayName("saveToCsv: destination file exists with correct header and content after write")
    void saveToCsv_destinationFileExistsWithCorrectContent() throws IOException {
        List<Candle> candles = List.of(
                new Candle(ZonedDateTime.of(2026, 4, 6, 16, 0, 0, 0, ZoneId.of("America/New_York")),
                        150.0, 155.0, 149.0, 153.0, 500000L)
        );

        service.saveToCsv("TSLA", TimeFrame.DAY_1, candles);

        Path destFile = tempDir.resolve(TimeFrame.DAY_1.toCacheKey("TSLA") + ".csv");
        assertTrue(Files.exists(destFile), "Destination CSV file must exist after saveToCsv");

        List<String> lines = Files.readAllLines(destFile);
        assertFalse(lines.isEmpty(), "File must not be empty");
        assertEquals("Time,Open,High,Low,Close,Volume", lines.get(0), "First line must be the CSV header");
        assertTrue(lines.get(1).contains("150.00"), "Data line must contain the open price");
        assertTrue(lines.get(1).contains("153.00"), "Data line must contain the close price");
        assertTrue(lines.get(1).contains("500000"), "Data line must contain the volume");
    }

    @Test
    @DisplayName("saveToCsv: no .tmp file remains in directory after successful write")
    void saveToCsv_noTmpFileRemainsAfterSuccessfulWrite() throws IOException {
        List<Candle> candles = List.of(
                new Candle(ZonedDateTime.of(2026, 4, 7, 16, 0, 0, 0, ZoneId.of("America/New_York")),
                        200.0, 210.0, 199.0, 205.0, 800000L)
        );

        service.saveToCsv("AAPL", TimeFrame.DAY_1, candles);

        long tmpCount = Files.list(tempDir)
                .filter(p -> p.getFileName().toString().endsWith(".tmp"))
                .count();
        assertEquals(0, tmpCount, "No .tmp files should remain in dataDir after a successful write");
    }
}
