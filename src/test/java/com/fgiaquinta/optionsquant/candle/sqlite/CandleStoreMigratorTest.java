package com.fgiaquinta.optionsquant.candle.sqlite;

import com.fgiaquinta.optionsquant.candle.CandleRepository;
import com.fgiaquinta.optionsquant.candle.RepositoryException;
import com.fgiaquinta.optionsquant.domain.TimeFrame;
import com.fgiaquinta.optionsquant.service.CandleCsvService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for CandleStoreMigrator.
 *
 * <p>Uses:
 * <ul>
 *   <li>SqliteTestBase for in-memory SQLite with full candle schema</li>
 *   <li>@TempDir for isolated fake CSV data directories (never touches real data/)</li>
 *   <li>Real CandleCsvService and SqliteCandleRepository — no mocks</li>
 * </ul>
 */
class CandleStoreMigratorTest extends SqliteTestBase {

    @TempDir
    Path dataDir;

    private CandleStoreMigrator migrator;
    private CandleCsvService csvService;
    private CandleRepository repository;

    @BeforeEach
    void setUpMigrator() {
        csvService = new CandleCsvService();
        csvService.setDataDir(dataDir);

        repository = new SqliteCandleRepository(ds, ds);

        migrator = new CandleStoreMigrator(csvService, repository, ds);
        migrator.setDataPath(dataDir.toString());
        migrator.setArchiveCsv(false); // default: no archiving unless test overrides
    }

    // -------------------------------------------------------------------------
    // T12-1: isMigrationNeeded — no schema_meta row → true
    // -------------------------------------------------------------------------

    @Test
    void isMigrationNeeded_noSchemaMetaRow_returnsTrue() {
        assertTrue(migrator.isMigrationNeeded(),
                "Migration must be needed when schema_meta has no csv_migration_status row");
    }

    // -------------------------------------------------------------------------
    // T12-2: isMigrationNeeded — row with 'COMPLETE' → false
    // -------------------------------------------------------------------------

    @Test
    void isMigrationNeeded_withCompleteRow_returnsFalse() {
        jdbc.update("INSERT INTO schema_meta(key, value) VALUES('csv_migration_status', 'COMPLETE')");

        assertFalse(migrator.isMigrationNeeded(),
                "Migration must NOT be needed when schema_meta shows COMPLETE");
    }

    // -------------------------------------------------------------------------
    // T12-3: parseCsvName — all 4 suffixes map correctly
    // -------------------------------------------------------------------------

    @Test
    void parseCsvName_allSuffixes_mapCorrectly() {
        assertParsed("AAPL_5min.csv",  "AAPL",  TimeFrame.MIN_5);
        assertParsed("MSFT_15min.csv", "MSFT",  TimeFrame.MIN_15);
        assertParsed("SPY_1hour.csv",  "SPY",   TimeFrame.HOUR_1);
        assertParsed("TSLA_1day.csv",  "TSLA",  TimeFrame.DAY_1);
    }

    private void assertParsed(String filename, String expectedTicker, TimeFrame expectedTf) {
        CandleStoreMigrator.ParsedFileName parsed = migrator.parseCsvName(filename);
        assertEquals(expectedTicker, parsed.ticker(),
                "ticker mismatch for " + filename);
        assertEquals(expectedTf, parsed.timeframe(),
                "timeframe mismatch for " + filename);
    }

    // -------------------------------------------------------------------------
    // T12-4: parseCsvName — unknown suffix → throws RepositoryException
    // -------------------------------------------------------------------------

    @Test
    void parseCsvName_unknownSuffix_throwsRepositoryException() {
        assertThrows(RepositoryException.class,
                () -> migrator.parseCsvName("AAPL_30min.csv"),
                "parseCsvName must throw RepositoryException for unrecognized suffix");
    }

    // -------------------------------------------------------------------------
    // T12-5: run() with 2 fake CSV files → both migrated
    // -------------------------------------------------------------------------

    @Test
    void run_twoCsvFiles_bothMigratedToSqlite() throws Exception {
        writeFakeCsv(dataDir, "AAPL_5min.csv",  3);
        writeFakeCsv(dataDir, "MSFT_1day.csv",  2);

        migrator.run(null);

        assertTrue(repository.hasLocalData("AAPL", TimeFrame.MIN_5),
                "AAPL MIN_5 must be in SQLite after migration");
        assertTrue(repository.hasLocalData("MSFT", TimeFrame.DAY_1),
                "MSFT DAY_1 must be in SQLite after migration");

        assertEquals(3, repository.load("AAPL", TimeFrame.MIN_5).size(),
                "AAPL MIN_5 must have 3 candles");
        assertEquals(2, repository.load("MSFT", TimeFrame.DAY_1).size(),
                "MSFT DAY_1 must have 2 candles");
    }

    // -------------------------------------------------------------------------
    // T12-6: run() called twice → idempotent (second run skips)
    // -------------------------------------------------------------------------

    @Test
    void run_calledTwice_idempotent_noduplicates() throws Exception {
        writeFakeCsv(dataDir, "SPY_15min.csv", 4);

        migrator.run(null);
        migrator.run(null); // second run must be a no-op

        long rowCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM candles WHERE ticker='SPY' AND timeframe='MIN_15'",
                Long.class);
        assertEquals(4L, rowCount,
                "Second run must not duplicate rows — migration is idempotent");
    }

    // -------------------------------------------------------------------------
    // T12-7: run() with one bad file → bad file skipped, good file migrated
    // -------------------------------------------------------------------------

    @Test
    void run_withOneBadFile_badFileSkipped_goodFileMigrated() throws Exception {
        writeFakeCsv(dataDir, "TSLA_1hour.csv", 3);
        // Write an unrecognized-suffix file — will fail parseCsvName
        Files.writeString(dataDir.resolve("UNKNOWN_30min.csv"),
                "Time,Open,High,Low,Close,Volume\n2024-01-02 09:30:00,100.0,110.0,90.0,105.0,1000\n");

        assertDoesNotThrow(() -> migrator.run(null),
                "run() must not throw even if one file has an unrecognized suffix");

        assertTrue(repository.hasLocalData("TSLA", TimeFrame.HOUR_1),
                "TSLA HOUR_1 must be migrated even when another file fails");
    }

    // -------------------------------------------------------------------------
    // T12-8: archiveCsv=true → CSV moved to archive directory
    // -------------------------------------------------------------------------

    @Test
    void run_archiveCsvTrue_csvMovedToArchiveDir() throws Exception {
        writeFakeCsv(dataDir, "AAPL_1day.csv", 2);
        migrator.setArchiveCsv(true);

        migrator.run(null);

        // Original file must no longer exist
        assertFalse(Files.exists(dataDir.resolve("AAPL_1day.csv")),
                "original CSV must be moved (not present in dataDir) when archiveCsv=true");

        // An archive subdirectory must exist
        long archiveDirs = Files.list(dataDir)
                .filter(Files::isDirectory)
                .filter(p -> p.getFileName().toString().startsWith("_archived_csv_"))
                .count();
        assertTrue(archiveDirs > 0,
                "an _archived_csv_<startTs> directory must exist after archiveCsv=true");

        // The file must be inside the archive dir
        Path archiveDir = Files.list(dataDir)
                .filter(Files::isDirectory)
                .filter(p -> p.getFileName().toString().startsWith("_archived_csv_"))
                .findFirst()
                .orElseThrow();
        assertTrue(Files.exists(archiveDir.resolve("AAPL_1day.csv")),
                "AAPL_1day.csv must be present inside the archive directory");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Creates a minimal valid CSV file with {@code rowCount} synthetic candle rows.
     * Header: Time,Open,High,Low,Close,Volume
     * Timestamps start at 2024-01-02 09:30:00, spaced 1 minute apart (safe for all timeframes).
     */
    static void writeFakeCsv(Path dir, String filename, int rowCount) throws IOException {
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
