package com.fgiaquinta.optionsquant.candle.sqlite;

import com.fgiaquinta.optionsquant.domain.Candle;
import com.fgiaquinta.optionsquant.domain.TimeFrame;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SqliteCandleRepository: load, loadRange, stream.
 * Extends SqliteTestBase (in-memory SQLite, schema created before each test).
 */
class SqliteCandleRepositoryQueryTest extends SqliteTestBase {

    private static final String TICKER = "AAPL";
    private static final TimeFrame TF = TimeFrame.MIN_5;

    private SqliteCandleRepository repo;

    @BeforeEach
    void setUpRepo() {
        repo = new SqliteCandleRepository(ds, ds);
    }

    // -------------------------------------------------------------------------
    // load
    // -------------------------------------------------------------------------

    @Test
    void load_empty_returnsEmptyList() {
        List<Candle> result = repo.load(TICKER, TF);
        assertTrue(result.isEmpty(), "load on empty table must return empty list");
    }

    @Test
    void load_afterUpsert_returnsCandlesSortedAscending() {
        // Insert out-of-order to ensure sorting
        ZonedDateTime t0 = ZonedDateTime.of(2024, 1, 2, 9, 30, 0, 0, ZoneOffset.UTC);
        ZonedDateTime t1 = t0.plusMinutes(5);
        ZonedDateTime t2 = t0.plusMinutes(10);
        List<Candle> candles = List.of(
                new Candle(t2, 106.0, 108.0, 105.0, 107.0, 1200L),
                new Candle(t0, 100.0, 105.0, 99.0,  103.0, 1000L),
                new Candle(t1, 103.0, 107.0, 102.0, 106.0, 1500L)
        );
        repo.upsert(TICKER, TF, candles);

        List<Candle> result = repo.load(TICKER, TF);

        assertEquals(3, result.size(), "Must load all 3 candles");
        assertEquals(t0, result.get(0).timestamp(), "First must be earliest");
        assertEquals(t1, result.get(1).timestamp(), "Second must be middle");
        assertEquals(t2, result.get(2).timestamp(), "Third must be latest");
    }

    // -------------------------------------------------------------------------
    // loadRange
    // -------------------------------------------------------------------------

    @Test
    void loadRange_fiveCandles_rangeCoversThree() {
        ZonedDateTime base = ZonedDateTime.of(2024, 1, 2, 9, 30, 0, 0, ZoneOffset.UTC);
        List<Candle> candles = List.of(
                new Candle(base,                100.0, 105.0, 99.0,  103.0, 1000L), // t0
                new Candle(base.plusMinutes(5), 103.0, 107.0, 102.0, 106.0, 1500L), // t1
                new Candle(base.plusMinutes(10),106.0, 108.0, 105.0, 107.0, 1200L), // t2
                new Candle(base.plusMinutes(15),107.0, 110.0, 106.0, 109.0, 1300L), // t3
                new Candle(base.plusMinutes(20),109.0, 112.0, 108.0, 111.0, 1400L)  // t4
        );
        repo.upsert(TICKER, TF, candles);

        // from = t1 (inclusive), to = t4 (exclusive) → should return t1, t2, t3
        List<Candle> result = repo.loadRange(TICKER, TF,
                base.plusMinutes(5), base.plusMinutes(20));

        assertEquals(3, result.size(), "loadRange must return exactly 3 candles");
        assertEquals(base.plusMinutes(5),  result.get(0).timestamp(), "t1 must be included (from inclusive)");
        assertEquals(base.plusMinutes(10), result.get(1).timestamp(), "t2 must be included");
        assertEquals(base.plusMinutes(15), result.get(2).timestamp(), "t3 must be included");
    }

    @Test
    void loadRange_fromEqualsTo_returnsEmpty() {
        ZonedDateTime base = ZonedDateTime.of(2024, 1, 2, 9, 30, 0, 0, ZoneOffset.UTC);
        repo.upsert(TICKER, TF, List.of(
                new Candle(base, 100.0, 105.0, 99.0, 103.0, 1000L)
        ));

        // from == to → [t, t) is an empty interval
        List<Candle> result = repo.loadRange(TICKER, TF, base, base);

        assertTrue(result.isEmpty(), "loadRange with from==to must return empty list");
    }

    // -------------------------------------------------------------------------
    // stream
    // -------------------------------------------------------------------------

    @Test
    void stream_yieldsAllCandlesInOrder() {
        ZonedDateTime base = ZonedDateTime.of(2024, 1, 2, 9, 30, 0, 0, ZoneOffset.UTC);
        List<Candle> candles = List.of(
                new Candle(base,                100.0, 105.0, 99.0,  103.0, 1000L),
                new Candle(base.plusMinutes(5), 103.0, 107.0, 102.0, 106.0, 1500L),
                new Candle(base.plusMinutes(10),106.0, 108.0, 105.0, 107.0, 1200L)
        );
        repo.upsert(TICKER, TF, candles);

        List<Candle> result;
        try (Stream<Candle> stream = repo.stream(TICKER, TF)) {
            result = stream.toList();
        }

        assertEquals(3, result.size(), "stream must yield all 3 candles");
        assertEquals(base,                result.get(0).timestamp(), "First candle at base");
        assertEquals(base.plusMinutes(5), result.get(1).timestamp(), "Second at base+5m");
        assertEquals(base.plusMinutes(10),result.get(2).timestamp(), "Third at base+10m");
    }

    @Test
    void stream_tryWithResources_noResourceLeak() {
        // Even if the stream is closed without consuming all elements, no exception must be thrown
        ZonedDateTime base = ZonedDateTime.of(2024, 1, 2, 9, 30, 0, 0, ZoneOffset.UTC);
        repo.upsert(TICKER, TF, List.of(
                new Candle(base,                100.0, 105.0, 99.0,  103.0, 1000L),
                new Candle(base.plusMinutes(5), 103.0, 107.0, 102.0, 106.0, 1500L)
        ));

        assertDoesNotThrow(() -> {
            try (Stream<Candle> stream = repo.stream(TICKER, TF)) {
                // consume only one element then let try-with-resources close it
                stream.findFirst();
            }
        }, "Closing a partially-consumed stream must not throw");
    }
}
