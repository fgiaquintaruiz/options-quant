package com.fgiaquinta.optionsquant.candle.sqlite;

import com.fgiaquinta.optionsquant.domain.Candle;
import com.fgiaquinta.optionsquant.domain.TimeFrame;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for SqliteCandleRepository: upsert, hasLocalData, lastTimestamp.
 * Extends SqliteTestBase (in-memory SQLite, schema created before each test).
 * The same DataSource is used for both write and read operations.
 */
class SqliteCandleRepositoryUpsertTest extends SqliteTestBase {

    private static final String TICKER = "AAPL";
    private static final TimeFrame TF = TimeFrame.MIN_5;

    private SqliteCandleRepository repo;

    @BeforeEach
    void setUpRepo() {
        // Use the same ds for both write and read to simplify test setup
        repo = new SqliteCandleRepository(ds, ds);
    }

    // -------------------------------------------------------------------------
    // upsert — empty list
    // -------------------------------------------------------------------------

    @Test
    void upsert_emptyList_noRowsInserted_noException() {
        assertDoesNotThrow(() -> repo.upsert(TICKER, TF, List.of()),
                "upsert with empty list must not throw");

        assertFalse(repo.hasLocalData(TICKER, TF),
                "hasLocalData must return false after upserting empty list");
    }

    // -------------------------------------------------------------------------
    // upsert + hasLocalData
    // -------------------------------------------------------------------------

    @Test
    void upsert_threeCandles_hasLocalDataReturnsTrue() {
        List<Candle> candles = makeCandles(3);
        repo.upsert(TICKER, TF, candles);

        assertTrue(repo.hasLocalData(TICKER, TF),
                "hasLocalData must be true after upserting 3 candles");
    }

    // -------------------------------------------------------------------------
    // upsert idempotency
    // -------------------------------------------------------------------------

    @Test
    void upsert_sameCandlesTwice_stillThreeRows() {
        List<Candle> candles = makeCandles(3);
        repo.upsert(TICKER, TF, candles);
        repo.upsert(TICKER, TF, candles); // second upsert with identical data

        long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM candles WHERE ticker=? AND timeframe=?",
                Long.class, TICKER, TF.name());
        assertEquals(3L, count, "Idempotent upsert must keep exactly 3 rows");
    }

    // -------------------------------------------------------------------------
    // upsert conflict — OHLCV updated
    // -------------------------------------------------------------------------

    @Test
    void upsert_onConflict_ohlcvUpdated() {
        ZonedDateTime ts = ZonedDateTime.of(2024, 1, 2, 9, 30, 0, 0, ZoneOffset.UTC);
        Candle original  = new Candle(ts, 100.0, 110.0, 95.0, 105.0, 1000L);
        Candle updated   = new Candle(ts, 200.0, 220.0, 190.0, 210.0, 9999L);

        repo.upsert(TICKER, TF, List.of(original));
        repo.upsert(TICKER, TF, List.of(updated));

        // Query the row directly to confirm update took effect
        Candle stored = jdbc.queryForObject(
                "SELECT ts_epoch,open,high,low,close,volume FROM candles " +
                "WHERE ticker=? AND timeframe=?",
                (rs, n) -> new CandleRowMapper().mapRow(rs, n),
                TICKER, TF.name());

        assertNotNull(stored);
        assertEquals(200.0, stored.open(),  0.0001, "open must be updated");
        assertEquals(220.0, stored.high(),  0.0001, "high must be updated");
        assertEquals(190.0, stored.low(),   0.0001, "low must be updated");
        assertEquals(210.0, stored.close(), 0.0001, "close must be updated");
        assertEquals(9999L, stored.volume(), "volume must be updated");
    }

    // -------------------------------------------------------------------------
    // lastTimestamp — empty
    // -------------------------------------------------------------------------

    @Test
    void lastTimestamp_empty_returnsEmptyOptional() {
        Optional<ZonedDateTime> result = repo.lastTimestamp(TICKER, TF);
        assertTrue(result.isEmpty(), "lastTimestamp must return empty Optional when no data");
    }

    // -------------------------------------------------------------------------
    // lastTimestamp — after upsert
    // -------------------------------------------------------------------------

    @Test
    void lastTimestamp_afterUpsert_returnsMaxTimestamp() {
        List<Candle> candles = makeCandles(3);
        repo.upsert(TICKER, TF, candles);

        Optional<ZonedDateTime> result = repo.lastTimestamp(TICKER, TF);

        assertTrue(result.isPresent(), "lastTimestamp must be present after upsert");
        ZonedDateTime expected = candles.stream()
                .map(Candle::timestamp)
                .max(ZonedDateTime::compareTo)
                .orElseThrow();
        assertEquals(expected.toEpochSecond(), result.get().toEpochSecond(),
                "lastTimestamp must equal the maximum timestamp of upserted candles");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Creates {@code count} candles starting at 2024-01-02T09:30:00Z, spaced 5 minutes apart.
     */
    private static List<Candle> makeCandles(int count) {
        ZonedDateTime base = ZonedDateTime.of(2024, 1, 2, 9, 30, 0, 0, ZoneOffset.UTC);
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(i -> new Candle(
                        base.plusMinutes(5L * i),
                        100.0 + i, 110.0 + i, 90.0 + i, 105.0 + i, 1000L + i))
                .toList();
    }
}
