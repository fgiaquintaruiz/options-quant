package com.fgiaquinta.optionsquant.candle;

import com.fgiaquinta.optionsquant.candle.sqlite.CandleStoreMigrator;
import com.fgiaquinta.optionsquant.candle.sqlite.SqliteCandleRepository;
import com.fgiaquinta.optionsquant.candle.sqlite.SqliteTestBase;
import com.fgiaquinta.optionsquant.domain.Candle;
import com.fgiaquinta.optionsquant.domain.TimeFrame;
import com.fgiaquinta.optionsquant.service.CandleCsvService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T26 — End-to-end migration test.
 *
 * Uses {@code jdbc:sqlite::memory:} (via {@link SqliteTestBase}) and a temp
 * directory for fake CSV files. No TWS connection required.
 *
 * Flow:
 * 1. Write known CSV candle data to a temp directory.
 * 2. Run {@link CandleStoreMigrator} against those CSVs → SQLite.
 * 3. Verify candles are in SQLite via {@link SqliteCandleRepository#load()}.
 * 4. Verify {@code loadRange()} returns the expected subset.
 * 5. Verify results match the original CSV data (no data corruption).
 */
class E2EMigrationTest extends SqliteTestBase {

    @TempDir
    Path dataDir;

    private SqliteCandleRepository repository;
    private CandleStoreMigrator migrator;

    @BeforeEach
    void setUpMigration() {
        repository = new SqliteCandleRepository(ds, ds);

        CandleCsvService csvService = new CandleCsvService();
        csvService.setDataDir(dataDir);

        migrator = new CandleStoreMigrator(csvService, repository, ds);
        migrator.setDataPath(dataDir.toString());
        migrator.setArchiveCsv(false);
    }

    // -------------------------------------------------------------------------
    // E2E-1: CSV → SQLite migration, all rows present
    // -------------------------------------------------------------------------

    @Test
    void e2e_csvMigration_allCandlesPresentInSqlite() throws Exception {
        writeFakeCsv(dataDir, "AAPL_5min.csv", 5);
        writeFakeCsv(dataDir, "MSFT_1day.csv", 3);

        migrator.run(null);

        assertTrue(repository.hasLocalData("AAPL", TimeFrame.MIN_5),
                "AAPL MIN_5 must be present in SQLite after migration");
        assertTrue(repository.hasLocalData("MSFT", TimeFrame.DAY_1),
                "MSFT DAY_1 must be present in SQLite after migration");

        assertEquals(5, repository.load("AAPL", TimeFrame.MIN_5).size(),
                "All 5 AAPL candles must be migrated");
        assertEquals(3, repository.load("MSFT", TimeFrame.DAY_1).size(),
                "All 3 MSFT candles must be migrated");
    }

    // -------------------------------------------------------------------------
    // E2E-2: Migrated candles are ordered ascending by timestamp
    // -------------------------------------------------------------------------

    @Test
    void e2e_migratedCandles_orderedByTimestampAscending() throws Exception {
        writeFakeCsv(dataDir, "SPY_1hour.csv", 4);

        migrator.run(null);

        List<Candle> candles = repository.load("SPY", TimeFrame.HOUR_1);
        assertEquals(4, candles.size());

        for (int i = 1; i < candles.size(); i++) {
            assertTrue(
                candles.get(i - 1).timestamp().isBefore(candles.get(i).timestamp()),
                "Candle at index " + i + " must be after candle at index " + (i - 1));
        }
    }

    // -------------------------------------------------------------------------
    // E2E-3: loadRange on migrated data returns correct subset
    // -------------------------------------------------------------------------

    @Test
    void e2e_loadRange_onMigratedData_returnsCorrectSubset() throws Exception {
        // 6 rows: t0 to t5 (1-minute spacing)
        writeFakeCsv(dataDir, "QQQ_5min.csv", 6);

        migrator.run(null);

        List<Candle> all = repository.load("QQQ", TimeFrame.MIN_5);
        assertEquals(6, all.size(), "All 6 candles must be present after migration");

        ZonedDateTime from = all.get(1).timestamp(); // t1 inclusive
        ZonedDateTime to   = all.get(4).timestamp(); // t4 exclusive → t1, t2, t3

        List<Candle> range = repository.loadRange("QQQ", TimeFrame.MIN_5, from, to);

        assertEquals(3, range.size(), "loadRange [t1, t4) must return 3 candles");
        assertEquals(from, range.get(0).timestamp(), "First candle must be t1 (inclusive)");
        assertEquals(all.get(3).timestamp(), range.get(2).timestamp(), "Last candle must be t3");
    }

    // -------------------------------------------------------------------------
    // E2E-4: Migration is idempotent (run twice → no duplicates)
    // -------------------------------------------------------------------------

    @Test
    void e2e_migration_isIdempotent_noduplicates() throws Exception {
        writeFakeCsv(dataDir, "TSLA_15min.csv", 4);

        migrator.run(null);
        migrator.run(null); // second run must be a no-op

        assertEquals(4, repository.load("TSLA", TimeFrame.MIN_15).size(),
                "Running migration twice must not duplicate candles");
    }

    // -------------------------------------------------------------------------
    // E2E-5: OHLCV data integrity — values match what was written to CSV
    // -------------------------------------------------------------------------

    @Test
    void e2e_ohlcvValues_matchOriginalCsvData() throws Exception {
        // Write a CSV with known, predictable values (writeFakeCsv row 0: open=100, high=110, low=90, close=105, vol=1000)
        writeFakeCsv(dataDir, "IBM_1day.csv", 1);

        migrator.run(null);

        List<Candle> loaded = repository.load("IBM", TimeFrame.DAY_1);
        assertEquals(1, loaded.size(), "One candle must be present");

        Candle c = loaded.get(0);
        assertEquals(100.0, c.open(),   0.01, "open must match CSV value (row 0 → 100.0)");
        assertEquals(110.0, c.high(),   0.01, "high must match CSV value (row 0 → 110.0)");
        assertEquals(90.0,  c.low(),    0.01, "low must match CSV value (row 0 → 90.0)");
        assertEquals(105.0, c.close(),  0.01, "close must match CSV value (row 0 → 105.0)");
        assertEquals(1000L, c.volume(),      "volume must match CSV value (row 0 → 1000)");
    }

    // -------------------------------------------------------------------------
    // E2E-6: stream() on migrated data yields all candles
    // -------------------------------------------------------------------------

    @Test
    void e2e_stream_onMigratedData_yieldsAllCandles() throws Exception {
        writeFakeCsv(dataDir, "NVDA_5min.csv", 5);

        migrator.run(null);

        List<Candle> fromStream;
        try (var stream = repository.stream("NVDA", TimeFrame.MIN_5)) {
            fromStream = stream.toList();
        }

        assertEquals(5, fromStream.size(), "stream() must yield all 5 migrated candles");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Creates a minimal valid CSV file with {@code rowCount} synthetic candle rows.
     * Header: Time,Open,High,Low,Close,Volume
     * Timestamps start at 2024-01-02 09:30:00, spaced 1 minute apart.
     * Row i has: open=100+i, high=110+i, low=90+i, close=105+i, volume=1000+i.
     */
    private static void writeFakeCsv(Path dir, String filename, int rowCount) throws Exception {
        StringBuilder sb = new StringBuilder("Time,Open,High,Low,Close,Volume\n");
        for (int i = 0; i < rowCount; i++) {
            int minuteOffset = i;
            int hours   = 9 + (30 + minuteOffset) / 60;
            int minutes = (30 + minuteOffset) % 60;
            sb.append(String.format("2024-01-02 %02d:%02d:00,%.2f,%.2f,%.2f,%.2f,%d%n",
                    hours, minutes,
                    100.0 + i, 110.0 + i, 90.0 + i, 105.0 + i, 1000L + i));
        }
        Files.writeString(dir.resolve(filename), sb.toString());
    }
}
