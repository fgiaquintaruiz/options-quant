package com.fgiaquinta.optionsquant.strategy.data;

import com.fgiaquinta.optionsquant.domain.Candle;
import com.fgiaquinta.optionsquant.domain.TimeFrame;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.ta4j.core.BarSeries;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TDD — binary-search fix for {@link StrategyData#getIndexForTime(BarSeries, ZonedDateTime)}.
 *
 * <p>The original implementation scanned backwards only the last 500 bars, so any
 * timestamp older than 500 bars from the end returned -1 even when the bar was
 * present in the series. The fix replaces that scan with an O(log n) binary search
 * over the full series.
 */
class StrategyDataGetIndexForTimeTest {

    private static final ZoneId UTC = ZoneId.of("UTC");

    /** Base timestamp — 2018-01-02T15:00:00Z, one candle per hour. */
    private static final ZonedDateTime BASE = ZonedDateTime.of(2018, 1, 2, 15, 0, 0, 0, UTC);

    /** 900 hourly candles → span from 2018-01-02 15:00 to ~2020-02-19 (900 h later). */
    private static final int TOTAL_BARS = 900;

    private StrategyData strategyData;
    private BarSeries series;

    @BeforeEach
    void buildLargeSeries() {
        List<Candle> candles = new ArrayList<>(TOTAL_BARS);
        for (int i = 0; i < TOTAL_BARS; i++) {
            ZonedDateTime ts = BASE.plusHours(i);
            candles.add(new Candle(ts, 100, 101, 99, 100, 1000L));
        }
        Map<TimeFrame, List<Candle>> data = new EnumMap<>(TimeFrame.class);
        data.put(TimeFrame.HOUR_1, candles);
        strategyData = new StrategyData(data);
        series = strategyData.getSeries(TimeFrame.HOUR_1);
    }

    /**
     * Test 1 (RED first): a timestamp that falls at bar index ~20 000 h into the series
     * — actually at bar ~20 (within 500-bar window but with time value in 2018).
     *
     * <p>The critical scenario is a timestamp that maps to a bar MORE than 500 positions
     * from the end. With 900 bars total, bar 0 is at endIdx - 899, which is beyond the
     * 500-bar backwards scan. The timestamp at bar 0 (BASE) must return index 0, not -1.
     */
    @Test
    @DisplayName("RED→GREEN: timestamp at bar 0 (>500 bars from end) returns index 0, not -1")
    void getIndexForTime_timestampBeyond500BarWindow_returnsValidIndex() {
        // bar 0 = BASE = 2018-01-02T15:00:00Z
        // endIdx = 899; endIdx - 500 = 399 → loop in old code stops at i=399, never visits bar 0
        ZonedDateTime queryTime = BASE; // exactly at bar 0's endTime

        int result = strategyData.getIndexForTime(series, queryTime);

        assertThat(result)
                .as("bar at BASE (index 0) must be found — old 500-bar scan window missed it")
                .isGreaterThanOrEqualTo(0);
    }

    /** Test 2: a recent timestamp (last bar) should also return a valid index. */
    @Test
    @DisplayName("timestamp at the last bar returns a valid index")
    void getIndexForTime_recentTimestamp_returnsValidIndex() {
        // Last bar's endTime = BASE + 899 hours
        ZonedDateTime queryTime = BASE.plusHours(TOTAL_BARS - 1);

        int result = strategyData.getIndexForTime(series, queryTime);

        assertThat(result)
                .as("last bar must be found")
                .isEqualTo(series.getEndIndex());
    }

    /** Test 3: timestamp BEFORE all candles → must return -1 (no bar covers it). */
    @Test
    @DisplayName("timestamp before all candles returns -1")
    void getIndexForTime_beforeAllCandles_returnsMinusOne() {
        ZonedDateTime beforeAll = ZonedDateTime.of(2000, 1, 1, 0, 0, 0, 0, UTC);

        int result = strategyData.getIndexForTime(series, beforeAll);

        assertThat(result)
                .as("no bar covers a timestamp prior to the series start")
                .isEqualTo(-1);
    }

    /** Test 4: timestamp AFTER all candles → must return the last valid index (endIndex). */
    @Test
    @DisplayName("timestamp after all candles returns endIndex")
    void getIndexForTime_afterAllCandles_returnsEndIndex() {
        ZonedDateTime afterAll = ZonedDateTime.of(2030, 1, 1, 0, 0, 0, 0, UTC);

        int result = strategyData.getIndexForTime(series, afterAll);

        assertThat(result)
                .as("a future timestamp should anchor to the last known bar")
                .isEqualTo(series.getEndIndex());
    }
}
