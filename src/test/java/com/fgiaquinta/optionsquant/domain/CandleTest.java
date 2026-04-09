package com.fgiaquinta.optionsquant.domain;

import org.junit.jupiter.api.*;

import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.*;

class CandleTest {

    @Test
    @DisplayName("Should create candle with correct values")
    void shouldCreateCandle() {
        ZonedDateTime ts = ZonedDateTime.of(2026, 4, 8, 16, 0, 0, 0, ZoneId.of("America/New_York"));
        Candle candle = new Candle(ts, 100.0, 105.0, 99.0, 103.0, 1000000L);

        assertEquals(ts, candle.timestamp());
        assertEquals(100.0, candle.open());
        assertEquals(105.0, candle.high());
        assertEquals(99.0, candle.low());
        assertEquals(103.0, candle.close());
        assertEquals(1000000L, candle.volume());
    }

    @Test
    @DisplayName("Candle toString should be formatted correctly")
    void candleToString() {
        ZonedDateTime ts = ZonedDateTime.of(2026, 4, 8, 16, 0, 0, 0, ZoneId.of("America/New_York"));
        Candle candle = new Candle(ts, 100.0, 105.0, 99.0, 103.0, 1000000L);

        String str = candle.toString();
        assertTrue(str.contains("2026-04-08"));
        assertTrue(str.contains("100.00"));
        assertTrue(str.contains("105.00"));
        assertTrue(str.contains("99.00"));
        assertTrue(str.contains("103.00"));
    }

    @Test
    @DisplayName("Candle is a record - immutable")
    void candleIsImmutable() {
        ZonedDateTime ts = ZonedDateTime.now();
        Candle candle = new Candle(ts, 100.0, 105.0, 99.0, 103.0, 1000000L);

        // Records are immutable - no setters
        assertAll(
                () -> assertEquals(ts, candle.timestamp()),
                () -> assertEquals(100.0, candle.open()),
                () -> assertEquals(105.0, candle.high()),
                () -> assertEquals(99.0, candle.low()),
                () -> assertEquals(103.0, candle.close()),
                () -> assertEquals(1000000L, candle.volume())
        );
    }
}
