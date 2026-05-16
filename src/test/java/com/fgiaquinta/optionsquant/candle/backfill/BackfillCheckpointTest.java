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
        Optional<ZonedDateTime> result = checkpoint.getLastDownloaded("AAPL", TimeFrame.MIN_5, ChunkOrigin.HISTORICAL);

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
        Optional<ZonedDateTime> result = checkpoint.getLastDownloaded("AAPL", TimeFrame.MIN_5, ChunkOrigin.HISTORICAL);

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

        Optional<ZonedDateTime> result = checkpoint.getLastDownloaded("MSFT", TimeFrame.DAY_1, ChunkOrigin.HISTORICAL);

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
    // T13-5: save with skipErrorCode=200 → skip_error_code persisted correctly
    // -------------------------------------------------------------------------

    @Test
    void saveWithSkipErrorCode_persistsCorrectly() {
        ZonedDateTime ts = ZonedDateTime.of(2024, 6, 15, 10, 30, 0, 0, ZoneOffset.UTC);

        checkpoint.save("DELISTED", TimeFrame.HOUR_1, ts,
                BackfillStatus.SKIPPED_PERMANENT, ChunkOrigin.HISTORICAL, 200);

        // Verify status in DB
        String status = jdbc.queryForObject(
                "SELECT status FROM download_progress WHERE ticker = ? AND timeframe = ?",
                String.class, "DELISTED", TimeFrame.HOUR_1.name());
        assertEquals(BackfillStatus.SKIPPED_PERMANENT.name(), status,
                "Status must be SKIPPED_PERMANENT");

        // Verify skip_error_code in DB
        Integer errorCode = jdbc.queryForObject(
                "SELECT skip_error_code FROM download_progress WHERE ticker = ? AND timeframe = ?",
                Integer.class, "DELISTED", TimeFrame.HOUR_1.name());
        assertNotNull(errorCode, "skip_error_code must not be null");
        assertEquals(200, errorCode.intValue(), "skip_error_code must be 200");
    }

    // -------------------------------------------------------------------------
    // T13-6: save without skipErrorCode → skip_error_code is NULL in DB
    // -------------------------------------------------------------------------

    @Test
    void saveWithoutSkipErrorCode_skipErrorCodeIsNull() {
        ZonedDateTime ts = ZonedDateTime.of(2024, 6, 15, 10, 30, 0, 0, ZoneOffset.UTC);

        // Call the existing 4-arg overload (no skipErrorCode)
        checkpoint.save("AAPL", TimeFrame.MIN_5, ts, BackfillStatus.COMPLETE_TWS);

        // skip_error_code must be NULL
        Object errorCode = jdbc.queryForMap(
                "SELECT skip_error_code FROM download_progress WHERE ticker = ? AND timeframe = ?",
                "AAPL", TimeFrame.MIN_5.name()).get("skip_error_code");
        assertNull(errorCode, "skip_error_code must be NULL when not provided");
    }

    // -------------------------------------------------------------------------
    // T13-7: upsert with skipErrorCode — existing SKIPPED_PERMANENT row retains code
    // -------------------------------------------------------------------------

    @Test
    void upsertSkippedPermanent_doesNotEraseErrorCode() {
        ZonedDateTime ts = ZonedDateTime.of(2024, 6, 15, 10, 30, 0, 0, ZoneOffset.UTC);

        // Save SKIPPED_PERMANENT with code 200 once
        checkpoint.save("BADTICKER", TimeFrame.HOUR_1, ts,
                BackfillStatus.SKIPPED_PERMANENT, ChunkOrigin.HISTORICAL, 200);

        // Save again (upsert) with the same overload — code must be preserved
        checkpoint.save("BADTICKER", TimeFrame.HOUR_1, ts.plusDays(1),
                BackfillStatus.SKIPPED_PERMANENT, ChunkOrigin.HISTORICAL, 200);

        Integer errorCode = jdbc.queryForObject(
                "SELECT skip_error_code FROM download_progress WHERE ticker = ? AND timeframe = ?",
                Integer.class, "BADTICKER", TimeFrame.HOUR_1.name());
        assertEquals(200, errorCode.intValue(), "skip_error_code must be preserved on upsert");

        Long rowCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM download_progress WHERE ticker = ?",
                Long.class, "BADTICKER");
        assertEquals(1L, rowCount, "upsert must keep exactly one row");
    }

    // -------------------------------------------------------------------------
    // T13-8: isAllTimeframesPermanentlySkipped — all SKIPPED_PERMANENT → true
    // -------------------------------------------------------------------------

    @Test
    void isAllTimeframesPermanentlySkipped_allSkipped_returnsTrue() {
        ZonedDateTime ts = ZonedDateTime.of(2024, 6, 15, 10, 30, 0, 0, ZoneOffset.UTC);
        // Save SKIPPED_PERMANENT for all timeframes
        for (TimeFrame tf : TimeFrame.values()) {
            checkpoint.save("DEAD", tf, ts, BackfillStatus.SKIPPED_PERMANENT, ChunkOrigin.HISTORICAL, 200);
        }

        assertTrue(checkpoint.isAllTimeframesPermanentlySkipped("DEAD"),
                "Must return true when ALL timeframes have SKIPPED_PERMANENT");
    }

    // -------------------------------------------------------------------------
    // T13-9: isAllTimeframesPermanentlySkipped — one active timeframe → false
    // -------------------------------------------------------------------------

    @Test
    void isAllTimeframesPermanentlySkipped_oneActiveTimeframe_returnsFalse() {
        ZonedDateTime ts = ZonedDateTime.of(2024, 6, 15, 10, 30, 0, 0, ZoneOffset.UTC);
        checkpoint.save("PARTIAL", TimeFrame.HOUR_1, ts,
                BackfillStatus.SKIPPED_PERMANENT, ChunkOrigin.HISTORICAL, 200);
        checkpoint.save("PARTIAL", TimeFrame.MIN_15, ts,
                BackfillStatus.COMPLETE_TWS, ChunkOrigin.HISTORICAL, null);

        assertFalse(checkpoint.isAllTimeframesPermanentlySkipped("PARTIAL"),
                "Must return false when at least one timeframe is not SKIPPED_PERMANENT");
    }

    // -------------------------------------------------------------------------
    // T13-10: isAllTimeframesPermanentlySkipped — no rows → false (never attempted)
    // -------------------------------------------------------------------------

    @Test
    void isAllTimeframesPermanentlySkipped_noRows_returnsFalse() {
        assertFalse(checkpoint.isAllTimeframesPermanentlySkipped("NEVER_ATTEMPTED"),
                "Must return false when no rows exist (ticker has not been attempted)");
    }

    // -------------------------------------------------------------------------
    // T13-4: ticker not found → empty, no exception
    // -------------------------------------------------------------------------

    @Test
    void getLastDownloaded_unknownTicker_returnsEmpty() {
        // Pre-populate with a different ticker to confirm row isolation
        ZonedDateTime ts = ZonedDateTime.of(2024, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        checkpoint.save("KNOWN", TimeFrame.HOUR_1, ts);

        Optional<ZonedDateTime> result = checkpoint.getLastDownloaded("UNKNOWN", TimeFrame.HOUR_1, ChunkOrigin.HISTORICAL);

        assertFalse(result.isPresent(),
                "getLastDownloaded must return empty for a ticker with no saved checkpoint");
    }
}
