package com.fgiaquinta.optionsquant.candle.backfill;

import com.fgiaquinta.optionsquant.candle.sqlite.SqliteTestBase;
import com.fgiaquinta.optionsquant.domain.TimeFrame;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for BackfillCheckpoint.
 * Uses SqliteTestBase for in-memory SQLite with the full candle schema.
 */
class BackfillCheckpointTest extends SqliteTestBase {

    private BackfillCheckpoint checkpoint;

    @BeforeEach
    void setUpCheckpoint() {
        // SqliteTestBase provides ds (DataSource) and jdbc (JdbcTemplate)
        checkpoint = new BackfillCheckpoint(ds);
    }

    // -------------------------------------------------------------------------
    // T13-1: getLastDownloaded — no row → Optional.empty()
    // -------------------------------------------------------------------------

    @Test
    void getLastDownloaded_noRow_returnsEmpty() {
        Optional<ZonedDateTime> result = checkpoint.getLastDownloaded("AAPL", TimeFrame.MIN_5);

        assertTrue(result.isEmpty(),
                "getLastDownloaded must return empty when no row exists for (ticker, tf)");
    }

    // -------------------------------------------------------------------------
    // T13-2: save() + getLastDownloaded() → returns saved timestamp
    // -------------------------------------------------------------------------

    @Test
    void save_thenGetLastDownloaded_returnsSavedTimestamp() {
        ZonedDateTime ts = ZonedDateTime.of(2024, 6, 15, 10, 30, 0, 0, ZoneOffset.UTC);

        checkpoint.save("AAPL", TimeFrame.MIN_5, ts);
        Optional<ZonedDateTime> result = checkpoint.getLastDownloaded("AAPL", TimeFrame.MIN_5);

        assertTrue(result.isPresent(), "getLastDownloaded must return a value after save");
        assertEquals(ts.toEpochSecond(), result.get().toEpochSecond(),
                "saved timestamp epoch must match returned epoch");
    }

    // -------------------------------------------------------------------------
    // T13-3: save() twice → upserts, returns latest
    // -------------------------------------------------------------------------

    @Test
    void save_twice_upserts_returnsLatest() {
        ZonedDateTime first  = ZonedDateTime.of(2024, 6, 15, 10, 0, 0, 0, ZoneOffset.UTC);
        ZonedDateTime second = ZonedDateTime.of(2024, 6, 16, 10, 0, 0, 0, ZoneOffset.UTC);

        checkpoint.save("MSFT", TimeFrame.DAY_1, first);
        checkpoint.save("MSFT", TimeFrame.DAY_1, second);

        Optional<ZonedDateTime> result = checkpoint.getLastDownloaded("MSFT", TimeFrame.DAY_1);

        assertTrue(result.isPresent(), "getLastDownloaded must be present after two saves");
        assertEquals(second.toEpochSecond(), result.get().toEpochSecond(),
                "second save must overwrite first (upsert semantics)");

        // Exactly one row in the table
        Long rowCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM download_progress WHERE ticker=? AND timeframe=?",
                Long.class, "MSFT", TimeFrame.DAY_1.name());
        assertEquals(1L, rowCount, "upsert must keep exactly one row per (ticker, tf)");
    }

    // -------------------------------------------------------------------------
    // T13-4: ticker not found → empty, no exception
    // -------------------------------------------------------------------------

    @Test
    void getLastDownloaded_unknownTicker_returnsEmpty() {
        // Pre-populate with a different ticker to confirm row isolation
        ZonedDateTime ts = ZonedDateTime.of(2024, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        checkpoint.save("KNOWN", TimeFrame.HOUR_1, ts);

        Optional<ZonedDateTime> result = checkpoint.getLastDownloaded("UNKNOWN", TimeFrame.HOUR_1);

        assertFalse(result.isPresent(),
                "getLastDownloaded must return empty for a ticker with no saved checkpoint");
    }
}
