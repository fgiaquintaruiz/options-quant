package com.fgiaquinta.optionsquant.candle;

import com.fgiaquinta.optionsquant.candle.csv.CsvCandleRepository;
import com.fgiaquinta.optionsquant.candle.sqlite.SchemaInitializer;
import com.fgiaquinta.optionsquant.candle.sqlite.SqliteCandleRepository;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Contract test for {@link CandleRepository}.
 *
 * Runs every scenario against BOTH implementations:
 * - {@link SqliteCandleRepository} backed by {@code jdbc:sqlite::memory:}
 * - {@link CsvCandleRepository} backed by a temp directory
 *
 * The parameterized fixture returns a fresh {@link Fixture} per test: each
 * implementation starts from an empty state and is torn down after the test.
 */
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class CandleRepositoryContractTest {

    // -------------------------------------------------------------------------
    // Fixture — wraps a CandleRepository + teardown logic
    // -------------------------------------------------------------------------

    record Fixture(String name, CandleRepository repository, Runnable teardown) {
        @Override
        public String toString() {
            return name;
        }
    }

    private final List<Fixture> openFixtures = new ArrayList<>();

    @AfterEach
    void tearDownFixtures() {
        openFixtures.forEach(f -> {
            try {
                f.teardown().run();
            } catch (Exception ignored) {}
        });
        openFixtures.clear();
    }

    static Stream<Fixture> implementations() throws Exception {
        return Stream.of(sqliteFixture(), csvFixture());
    }

    private static Fixture sqliteFixture() throws Exception {
        HikariConfig cfg = new HikariConfig();
        cfg.setDriverClassName("org.sqlite.JDBC");
        cfg.setJdbcUrl("jdbc:sqlite::memory:");
        cfg.setMaximumPoolSize(1);
        cfg.setConnectionInitSql("PRAGMA busy_timeout=5000;");
        HikariDataSource ds = new HikariDataSource(cfg);
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        SchemaInitializer.forTesting(jdbc).afterPropertiesSet();

        SqliteCandleRepository repo = new SqliteCandleRepository(ds, ds);
        return new Fixture("SqliteCandleRepository", repo, ds::close);
    }

    private static Fixture csvFixture() throws IOException {
        Path tempDir = Files.createTempDirectory("candle-contract-csv-");
        CsvCandleRepository repo = new CsvCandleRepository();
        repo.setDataDir(tempDir);
        return new Fixture("CsvCandleRepository", repo, () -> {
            // Best-effort cleanup of temp dir
            try {
                try (var walk = Files.walk(tempDir)) {
                    walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                    });
                }
            } catch (Exception ignored) {}
        });
    }

    // -------------------------------------------------------------------------
    // Contract: load — empty state
    // -------------------------------------------------------------------------

    @ParameterizedTest(name = "[{0}] load returns empty list when no data")
    @MethodSource("implementations")
    void load_returnsEmptyList_whenNoData(Fixture f) {
        openFixtures.add(f);
        List<com.fgiaquinta.optionsquant.domain.Candle> result =
                f.repository().load("AAPL", com.fgiaquinta.optionsquant.domain.TimeFrame.MIN_5);
        assertTrue(result.isEmpty(), "Expected empty list when no data has been stored");
    }

    // -------------------------------------------------------------------------
    // Contract: upsert + load round-trip, ordered by timestamp
    // -------------------------------------------------------------------------

    @ParameterizedTest(name = "[{0}] upsert+load round-trip returns candles in timestamp order")
    @MethodSource("implementations")
    void upsertAndLoad_roundtrip_returnsSameCandlesInOrder(Fixture f) {
        openFixtures.add(f);
        ZonedDateTime base = ZonedDateTime.of(2024, 1, 2, 9, 30, 0, 0, ZoneOffset.UTC);
        List<com.fgiaquinta.optionsquant.domain.Candle> candles = List.of(
            new com.fgiaquinta.optionsquant.domain.Candle(base,               100.0, 105.0, 99.0,  103.0, 1000L),
            new com.fgiaquinta.optionsquant.domain.Candle(base.plusMinutes(5), 103.0, 107.0, 102.0, 106.0, 1500L),
            new com.fgiaquinta.optionsquant.domain.Candle(base.plusMinutes(10), 106.0, 108.0, 105.0, 107.0, 1200L)
        );

        f.repository().upsert("AAPL", com.fgiaquinta.optionsquant.domain.TimeFrame.MIN_5, candles);
        List<com.fgiaquinta.optionsquant.domain.Candle> loaded =
                f.repository().load("AAPL", com.fgiaquinta.optionsquant.domain.TimeFrame.MIN_5);

        assertEquals(3, loaded.size(), "Expected 3 candles after upsert");
        // Compare by epoch second — CSV implementation may restore timestamps in local timezone
        assertEquals(base.toEpochSecond(),               loaded.get(0).timestamp().toEpochSecond(), "First must be earliest");
        assertEquals(base.plusMinutes(5).toEpochSecond(), loaded.get(1).timestamp().toEpochSecond(), "Second must be middle");
        assertEquals(base.plusMinutes(10).toEpochSecond(), loaded.get(2).timestamp().toEpochSecond(), "Third must be latest");
        // Ascending order invariant
        assertTrue(loaded.get(0).timestamp().toEpochSecond() < loaded.get(1).timestamp().toEpochSecond(), "Must be ascending");
        assertTrue(loaded.get(1).timestamp().toEpochSecond() < loaded.get(2).timestamp().toEpochSecond(), "Must be ascending");
    }

    // -------------------------------------------------------------------------
    // Contract: upsert idempotency — no duplicates on second call
    // -------------------------------------------------------------------------

    @ParameterizedTest(name = "[{0}] upsert twice is idempotent — no duplicates")
    @MethodSource("implementations")
    void upsert_twice_isIdempotent(Fixture f) {
        openFixtures.add(f);
        ZonedDateTime base = ZonedDateTime.of(2024, 1, 2, 9, 30, 0, 0, ZoneOffset.UTC);
        List<com.fgiaquinta.optionsquant.domain.Candle> candles = List.of(
            new com.fgiaquinta.optionsquant.domain.Candle(base, 100.0, 105.0, 99.0, 103.0, 1000L),
            new com.fgiaquinta.optionsquant.domain.Candle(base.plusMinutes(5), 103.0, 107.0, 102.0, 106.0, 1500L)
        );

        f.repository().upsert("SPY", com.fgiaquinta.optionsquant.domain.TimeFrame.DAY_1, candles);
        f.repository().upsert("SPY", com.fgiaquinta.optionsquant.domain.TimeFrame.DAY_1, candles);

        List<com.fgiaquinta.optionsquant.domain.Candle> loaded =
                f.repository().load("SPY", com.fgiaquinta.optionsquant.domain.TimeFrame.DAY_1);
        assertEquals(2, loaded.size(), "Idempotent upsert must not create duplicates");
    }

    // -------------------------------------------------------------------------
    // Contract: hasLocalData — false before, true after upsert
    // -------------------------------------------------------------------------

    @ParameterizedTest(name = "[{0}] hasLocalData returns false before upsert, true after")
    @MethodSource("implementations")
    void hasLocalData_returnsFalseBeforeUpsert_trueAfter(Fixture f) {
        openFixtures.add(f);
        assertFalse(f.repository().hasLocalData("TSLA", com.fgiaquinta.optionsquant.domain.TimeFrame.HOUR_1),
            "Expected hasLocalData=false before any upsert");

        ZonedDateTime ts = ZonedDateTime.of(2024, 1, 2, 9, 30, 0, 0, ZoneOffset.UTC);
        f.repository().upsert("TSLA", com.fgiaquinta.optionsquant.domain.TimeFrame.HOUR_1, List.of(
            new com.fgiaquinta.optionsquant.domain.Candle(ts, 200.0, 210.0, 198.0, 205.0, 5000L)
        ));

        assertTrue(f.repository().hasLocalData("TSLA", com.fgiaquinta.optionsquant.domain.TimeFrame.HOUR_1),
            "Expected hasLocalData=true after upsert");
    }

    // -------------------------------------------------------------------------
    // Contract: lastTimestamp — empty Optional when no data
    // -------------------------------------------------------------------------

    @ParameterizedTest(name = "[{0}] lastTimestamp returns empty Optional when no data")
    @MethodSource("implementations")
    void lastTimestamp_returnsEmptyOptional_whenNoData(Fixture f) {
        openFixtures.add(f);
        Optional<ZonedDateTime> result =
                f.repository().lastTimestamp("SPY", com.fgiaquinta.optionsquant.domain.TimeFrame.DAY_1);
        assertTrue(result.isEmpty(), "Expected empty Optional when no data stored");
    }

    // -------------------------------------------------------------------------
    // Contract: lastTimestamp — returns max timestamp after upsert
    // -------------------------------------------------------------------------

    @ParameterizedTest(name = "[{0}] lastTimestamp returns max timestamp after upsert")
    @MethodSource("implementations")
    void lastTimestamp_afterUpsert_returnsMaxTimestamp(Fixture f) {
        openFixtures.add(f);
        ZonedDateTime base = ZonedDateTime.of(2024, 1, 2, 9, 30, 0, 0, ZoneOffset.UTC);
        ZonedDateTime expected = base.plusHours(2);
        f.repository().upsert("MSFT", com.fgiaquinta.optionsquant.domain.TimeFrame.MIN_15, List.of(
            new com.fgiaquinta.optionsquant.domain.Candle(base,             100.0, 105.0, 99.0, 103.0, 1000L),
            new com.fgiaquinta.optionsquant.domain.Candle(base.plusHours(1), 110.0, 115.0, 108.0, 112.0, 1200L),
            new com.fgiaquinta.optionsquant.domain.Candle(expected,           120.0, 125.0, 118.0, 122.0, 1400L)
        ));

        Optional<ZonedDateTime> result =
                f.repository().lastTimestamp("MSFT", com.fgiaquinta.optionsquant.domain.TimeFrame.MIN_15);

        assertTrue(result.isPresent(), "lastTimestamp must be present after upsert");
        assertEquals(expected.toEpochSecond(), result.get().toEpochSecond(),
                "lastTimestamp must equal the latest stored timestamp");
    }

    // -------------------------------------------------------------------------
    // Contract: loadRange — [from inclusive, to exclusive)
    // -------------------------------------------------------------------------

    @ParameterizedTest(name = "[{0}] loadRange filters [from, to) correctly")
    @MethodSource("implementations")
    void loadRange_filtersCorrectly_includeFromExcludeTo(Fixture f) {
        openFixtures.add(f);
        ZonedDateTime base = ZonedDateTime.of(2024, 1, 2, 9, 30, 0, 0, ZoneOffset.UTC);
        ZonedDateTime t0 = base;
        ZonedDateTime t1 = base.plusMinutes(5);
        ZonedDateTime t2 = base.plusMinutes(10);
        ZonedDateTime t3 = base.plusMinutes(15);

        f.repository().upsert("AAPL", com.fgiaquinta.optionsquant.domain.TimeFrame.MIN_5, List.of(
            new com.fgiaquinta.optionsquant.domain.Candle(t0, 100.0, 105.0, 99.0,  103.0, 1000L),
            new com.fgiaquinta.optionsquant.domain.Candle(t1, 103.0, 107.0, 102.0, 106.0, 1500L),
            new com.fgiaquinta.optionsquant.domain.Candle(t2, 106.0, 108.0, 105.0, 107.0, 1200L),
            new com.fgiaquinta.optionsquant.domain.Candle(t3, 107.0, 110.0, 106.0, 109.0, 1300L)
        ));

        // [t1, t3) → should return t1 and t2 only
        List<com.fgiaquinta.optionsquant.domain.Candle> result =
                f.repository().loadRange("AAPL", com.fgiaquinta.optionsquant.domain.TimeFrame.MIN_5, t1, t3);

        assertEquals(2, result.size(), "Expected 2 candles in [t1, t3)");
        // Compare by epoch second — CSV implementation may restore timestamps in local timezone
        assertEquals(t1.toEpochSecond(), result.get(0).timestamp().toEpochSecond(), "First candle should be at t1 (inclusive)");
        assertEquals(t2.toEpochSecond(), result.get(1).timestamp().toEpochSecond(), "Second candle should be at t2");
    }

    // -------------------------------------------------------------------------
    // Contract: stream — lazy, ordered, closeable
    // -------------------------------------------------------------------------

    @ParameterizedTest(name = "[{0}] stream yields all candles in ascending order")
    @MethodSource("implementations")
    void stream_yieldsAllCandlesInOrder(Fixture f) {
        openFixtures.add(f);
        ZonedDateTime base = ZonedDateTime.of(2024, 1, 2, 9, 30, 0, 0, ZoneOffset.UTC);
        f.repository().upsert("QQQ", com.fgiaquinta.optionsquant.domain.TimeFrame.HOUR_1, List.of(
            new com.fgiaquinta.optionsquant.domain.Candle(base,             100.0, 105.0, 99.0, 103.0, 1000L),
            new com.fgiaquinta.optionsquant.domain.Candle(base.plusHours(1), 103.0, 108.0, 102.0, 107.0, 1200L),
            new com.fgiaquinta.optionsquant.domain.Candle(base.plusHours(2), 107.0, 110.0, 105.0, 109.0, 900L)
        ));

        List<com.fgiaquinta.optionsquant.domain.Candle> result;
        try (Stream<com.fgiaquinta.optionsquant.domain.Candle> stream =
                     f.repository().stream("QQQ", com.fgiaquinta.optionsquant.domain.TimeFrame.HOUR_1)) {
            result = stream.toList();
        }

        assertEquals(3, result.size(), "stream must yield all 3 candles");
        // Compare by epoch second — CSV implementation may restore timestamps in local timezone
        assertEquals(base.toEpochSecond(),             result.get(0).timestamp().toEpochSecond(), "First must be earliest");
        assertEquals(base.plusHours(1).toEpochSecond(), result.get(1).timestamp().toEpochSecond(), "Second must be middle");
        assertEquals(base.plusHours(2).toEpochSecond(), result.get(2).timestamp().toEpochSecond(), "Third must be latest");
        // Ascending order invariant
        assertTrue(result.get(0).timestamp().toEpochSecond() < result.get(1).timestamp().toEpochSecond(), "Must be ascending");
        assertTrue(result.get(1).timestamp().toEpochSecond() < result.get(2).timestamp().toEpochSecond(), "Must be ascending");
    }

    @ParameterizedTest(name = "[{0}] stream closed before exhaustion does not throw")
    @MethodSource("implementations")
    void stream_closedBeforeExhaustion_doesNotThrow(Fixture f) {
        openFixtures.add(f);
        ZonedDateTime base = ZonedDateTime.of(2024, 1, 2, 9, 30, 0, 0, ZoneOffset.UTC);
        f.repository().upsert("IBM", com.fgiaquinta.optionsquant.domain.TimeFrame.MIN_5, List.of(
            new com.fgiaquinta.optionsquant.domain.Candle(base,               100.0, 105.0, 99.0, 103.0, 1000L),
            new com.fgiaquinta.optionsquant.domain.Candle(base.plusMinutes(5), 103.0, 107.0, 102.0, 106.0, 1200L)
        ));

        assertDoesNotThrow(() -> {
            try (Stream<com.fgiaquinta.optionsquant.domain.Candle> stream =
                         f.repository().stream("IBM", com.fgiaquinta.optionsquant.domain.TimeFrame.MIN_5)) {
                stream.findFirst(); // consume one, then close via try-with-resources
            }
        }, "Closing a partially-consumed stream must not throw");
    }
}
