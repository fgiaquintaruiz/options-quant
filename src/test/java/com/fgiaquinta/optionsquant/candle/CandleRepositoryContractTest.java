package com.fgiaquinta.optionsquant.candle;

import com.fgiaquinta.optionsquant.domain.Candle;
import com.fgiaquinta.optionsquant.domain.TimeFrame;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Contract test for CandleRepository.
 * Verifies the contract using a simple in-memory mock implementation.
 */
class CandleRepositoryContractTest {

    private CandleRepository repository;

    @BeforeEach
    void setUp() {
        repository = new InMemoryCandleRepository();
    }

    @Test
    void load_returnsEmptyList_whenNoData() {
        List<Candle> result = repository.load("AAPL", TimeFrame.MIN_5);
        assertTrue(result.isEmpty(), "Expected empty list when no data has been stored");
    }

    @Test
    void upsertAndLoad_roundtrip_returnsSameCandlesInOrder() {
        ZonedDateTime base = ZonedDateTime.of(2024, 1, 2, 9, 30, 0, 0, ZoneOffset.UTC);
        List<Candle> candles = List.of(
            new Candle(base,              100.0, 105.0, 99.0,  103.0, 1000L),
            new Candle(base.plusMinutes(5), 103.0, 107.0, 102.0, 106.0, 1500L),
            new Candle(base.plusMinutes(10), 106.0, 108.0, 105.0, 107.0, 1200L)
        );

        repository.upsert("AAPL", TimeFrame.MIN_5, candles);
        List<Candle> loaded = repository.load("AAPL", TimeFrame.MIN_5);

        assertEquals(3, loaded.size(), "Expected 3 candles after upsert");
        assertEquals(candles, loaded, "Loaded candles must match upserted candles in order");
    }

    @Test
    void hasLocalData_returnsFalseBeforeUpsert_trueAfter() {
        assertFalse(repository.hasLocalData("TSLA", TimeFrame.HOUR_1),
            "Expected hasLocalData=false before any upsert");

        ZonedDateTime ts = ZonedDateTime.of(2024, 1, 2, 9, 30, 0, 0, ZoneOffset.UTC);
        repository.upsert("TSLA", TimeFrame.HOUR_1, List.of(
            new Candle(ts, 200.0, 210.0, 198.0, 205.0, 5000L)
        ));

        assertTrue(repository.hasLocalData("TSLA", TimeFrame.HOUR_1),
            "Expected hasLocalData=true after upsert");
    }

    @Test
    void lastTimestamp_returnsEmptyOptional_whenNoData() {
        Optional<ZonedDateTime> result = repository.lastTimestamp("SPY", TimeFrame.DAY_1);
        assertTrue(result.isEmpty(), "Expected empty Optional when no data stored");
    }

    @Test
    void loadRange_filtersCorrectly_includeFromExcludeTo() {
        ZonedDateTime base = ZonedDateTime.of(2024, 1, 2, 9, 30, 0, 0, ZoneOffset.UTC);
        ZonedDateTime t0 = base;
        ZonedDateTime t1 = base.plusMinutes(5);
        ZonedDateTime t2 = base.plusMinutes(10);
        ZonedDateTime t3 = base.plusMinutes(15);

        List<Candle> candles = List.of(
            new Candle(t0, 100.0, 105.0, 99.0,  103.0, 1000L),
            new Candle(t1, 103.0, 107.0, 102.0, 106.0, 1500L),
            new Candle(t2, 106.0, 108.0, 105.0, 107.0, 1200L),
            new Candle(t3, 107.0, 110.0, 106.0, 109.0, 1300L)
        );
        repository.upsert("AAPL", TimeFrame.MIN_5, candles);

        // from=t1 (inclusive), to=t3 (exclusive) → should return t1 and t2
        List<Candle> result = repository.loadRange("AAPL", TimeFrame.MIN_5, t1, t3);

        assertEquals(2, result.size(), "Expected 2 candles in [t1, t3)");
        assertEquals(t1, result.get(0).timestamp(), "First candle should be at t1");
        assertEquals(t2, result.get(1).timestamp(), "Second candle should be at t2");
    }

    // -------------------------------------------------------------------------
    // In-memory mock implementation
    // -------------------------------------------------------------------------

    private static class InMemoryCandleRepository implements CandleRepository {

        private final Map<String, List<Candle>> store = new HashMap<>();

        private String key(String ticker, TimeFrame tf) {
            return ticker + ":" + tf.name();
        }

        @Override
        public List<Candle> load(String ticker, TimeFrame tf) {
            return store.getOrDefault(key(ticker, tf), List.of());
        }

        @Override
        public List<Candle> loadRange(String ticker, TimeFrame tf, ZonedDateTime from, ZonedDateTime to) {
            return load(ticker, tf).stream()
                .filter(c -> !c.timestamp().isBefore(from) && c.timestamp().isBefore(to))
                .sorted(Comparator.comparing(Candle::timestamp))
                .toList();
        }

        @Override
        public Optional<ZonedDateTime> lastTimestamp(String ticker, TimeFrame tf) {
            return load(ticker, tf).stream()
                .map(Candle::timestamp)
                .max(Comparator.naturalOrder());
        }

        @Override
        public void upsert(String ticker, TimeFrame tf, List<Candle> candles) {
            List<Candle> existing = new ArrayList<>(store.getOrDefault(key(ticker, tf), new ArrayList<>()));
            // Simple upsert: replace by timestamp
            Map<ZonedDateTime, Candle> byTs = new HashMap<>();
            existing.forEach(c -> byTs.put(c.timestamp(), c));
            candles.forEach(c -> byTs.put(c.timestamp(), c));
            List<Candle> merged = new ArrayList<>(byTs.values());
            merged.sort(Comparator.comparing(Candle::timestamp));
            store.put(key(ticker, tf), merged);
        }

        @Override
        public boolean hasLocalData(String ticker, TimeFrame tf) {
            return !load(ticker, tf).isEmpty();
        }

        @Override
        public Stream<Candle> stream(String ticker, TimeFrame tf) {
            return load(ticker, tf).stream();
        }
    }
}
