package com.fgiaquinta.optionsquant.strategy;

import com.fgiaquinta.optionsquant.domain.Candle;
import com.fgiaquinta.optionsquant.domain.TimeFrame;
import com.fgiaquinta.optionsquant.strategy.data.StrategyData;
import com.fgiaquinta.optionsquant.strategy.indicator.WordenStochasticIndicator;
import com.fgiaquinta.optionsquant.strategy.C5ContinuationCallStrategy;
import com.fgiaquinta.optionsquant.strategy.P5ContinuationPutStrategy;
import com.fgiaquinta.optionsquant.strategy.C1SqueezeCallStrategy;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeriesBuilder;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;

import java.time.ZonedDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.EnumMap;

class StrategyUnitTest {

    private static final ZoneId NY = ZoneId.of("America/New_York");

    private Candle candle(ZonedDateTime time, double open, double high, double low, double close, long volume) {
        return new Candle(time, open, high, low, close, volume);
    }

    // =========================================================================
    // StrategyData Tests
    // =========================================================================

    @Nested
    class StrategyDataTest {

        @Test
        @DisplayName("Should convert Candle list to BarSeries correctly")
        void shouldConvertCandlesToBarSeries() {
            ZonedDateTime base = ZonedDateTime.of(2026, 4, 6, 9, 30, 0, 0, NY);
            List<Candle> candles15m = new ArrayList<>();
            for (int i = 0; i < 30; i++) {
                candles15m.add(candle(
                        base.plusMinutes(15L * i),
                        100.0 + i, 102.0 + i, 99.0 + i, 101.0 + i, 1000000L
                ));
            }

            Map<TimeFrame, List<Candle>> data = new EnumMap<>(TimeFrame.class);
            data.put(TimeFrame.MIN_15, candles15m);

            StrategyData strategyData = new StrategyData(data);
            BarSeries series = strategyData.getSeries(TimeFrame.MIN_15);

            assertThat(series).isNotNull();
            assertThat(series.getBarCount()).isEqualTo(30);
            assertThat(series.getBar(0).getOpenPrice().doubleValue()).isEqualTo(100.0);
            assertThat(series.getBar(0).getClosePrice().doubleValue()).isEqualTo(101.0);
            assertThat(series.getBar(0).getHighPrice().doubleValue()).isEqualTo(102.0);
            assertThat(series.getBar(0).getLowPrice().doubleValue()).isEqualTo(99.0);
            assertThat(series.getBar(0).getVolume().doubleValue()).isEqualTo(1000000.0);
        }

        @Test
        @DisplayName("hasAllTimeframes should return true only when all 4 timeframes have data")
        void hasAllTimeframes_requiresAllFour() {
            ZonedDateTime base = ZonedDateTime.of(2026, 4, 6, 9, 30, 0, 0, NY);
            List<Candle> candles = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                candles.add(candle(base.plusMinutes(5L * i), 100, 101, 99, 100, 1000L));
            }

            // Only MIN_5 provided
            Map<TimeFrame, List<Candle>> partial = new EnumMap<>(TimeFrame.class);
            partial.put(TimeFrame.MIN_5, candles);
            StrategyData partialData = new StrategyData(partial);
            assertThat(partialData.hasAllTimeframes()).isFalse();

            // All four provided
            Map<TimeFrame, List<Candle>> full = new EnumMap<>(TimeFrame.class);
            full.put(TimeFrame.MIN_5, candles);
            full.put(TimeFrame.MIN_15, candles);
            full.put(TimeFrame.HOUR_1, candles);
            full.put(TimeFrame.DAY_1, candles);
            StrategyData fullData = new StrategyData(full);
            assertThat(fullData.hasAllTimeframes()).isTrue();
        }

        @Test
        @DisplayName("getIndexForTime should find the correct index for a given time")
        void getIndexForTime_findsCorrectIndex() {
            ZonedDateTime base = ZonedDateTime.of(2026, 4, 6, 9, 30, 0, 0, NY);
            List<Candle> candles = new ArrayList<>();
            for (int i = 0; i < 50; i++) {
                candles.add(candle(
                        base.plusMinutes(15L * i),
                        100.0, 101.0, 99.0, 100.0, 1000L
                ));
            }

            Map<TimeFrame, List<Candle>> data = new EnumMap<>(TimeFrame.class);
            data.put(TimeFrame.MIN_15, candles);
            StrategyData strategyData = new StrategyData(data);
            BarSeries series = strategyData.getSeries(TimeFrame.MIN_15);

            // The time of bar 10
            ZonedDateTime targetTime = base.plusMinutes(15L * 10);
            int index = strategyData.getIndexForTime(series, targetTime);

            assertThat(index).isEqualTo(10);
        }

        @Test
        @DisplayName("getIndexForTime should return -1 for empty series")
        void getIndexForTime_emptySeriesReturnsMinusOne() {
            BarSeries emptySeries = new BaseBarSeriesBuilder().build();
            StrategyData strategyData = new StrategyData(Map.of());
            int index = strategyData.getIndexForTime(emptySeries, ZonedDateTime.now(NY));
            assertThat(index).isEqualTo(-1);
        }
    }

    // =========================================================================
    // WordenStochasticIndicator Tests
    // =========================================================================

    @Nested
    class WordenStochasticIndicatorTest {

        @Test
        @DisplayName("Should calculate percentile rank correctly for known prices")
        void shouldCalculatePercentileRank() {
            BarSeries series = new BaseBarSeriesBuilder().withName("test").build();
            // 10 bars with known close prices
            double[] closes = {10, 20, 15, 30, 25, 35, 5, 40, 45, 50};
            ZonedDateTime base = ZonedDateTime.of(2026, 4, 1, 9, 30, 0, 0, NY);
            for (int i = 0; i < closes.length; i++) {
                series.addBar(base.plusMinutes(i), closes[i], closes[i] + 1, closes[i] - 1, closes[i], 1000);
            }

            ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
            int period = 7;
            WordenStochasticIndicator wordenStoch = new WordenStochasticIndicator(closePrice, period);

            // At index 9, period=7, looks at indices 3..9: [30, 25, 35, 5, 40, 45, 50]
            // Sorted: [5, 25, 30, 35, 40, 45, 50], 50 is at rank 6
            // result = (100 / 6) * 6 = 100.0
            double valueAt9 = wordenStoch.getValue(9).doubleValue();
            assertThat(valueAt9).isCloseTo(100.0, within(0.01));

            // At index 6, period=7, looks at indices 0..6: [10, 20, 15, 30, 25, 35, 5]
            // Sorted: [5, 10, 15, 20, 25, 30, 35], 5 is at rank 0
            // result = (100 / 6) * 0 = 0.0
            double valueAt6 = wordenStoch.getValue(6).doubleValue();
            assertThat(valueAt6).isCloseTo(0.0, within(0.01));
        }

        @Test
        @DisplayName("Should return 50 for early bars (before period - 1)")
        void shouldReturn50ForEarlyBars() {
            BarSeries series = new BaseBarSeriesBuilder().withName("test").build();
            ZonedDateTime base = ZonedDateTime.of(2026, 4, 1, 9, 30, 0, 0, NY);
            for (int i = 0; i < 5; i++) {
                series.addBar(base.plusMinutes(i), 100 + i, 101 + i, 99 + i, 100 + i, 1000);
            }

            ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
            int period = 10;
            WordenStochasticIndicator wordenStoch = new WordenStochasticIndicator(closePrice, period);

            // Index 0 through 8 should all return 50 (not enough data)
            assertThat(wordenStoch.getValue(0).doubleValue()).isEqualTo(50.0);
            assertThat(wordenStoch.getValue(4).doubleValue()).isEqualTo(50.0);
        }

        @Test
        @DisplayName("Should handle flat prices (all same close)")
        void shouldHandleFlatPrices() {
            BarSeries series = new BaseBarSeriesBuilder().withName("test").build();
            ZonedDateTime base = ZonedDateTime.of(2026, 4, 1, 9, 30, 0, 0, NY);
            for (int i = 0; i < 10; i++) {
                series.addBar(base.plusMinutes(i), 100, 101, 99, 100, 1000);
            }

            ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
            WordenStochasticIndicator wordenStoch = new WordenStochasticIndicator(closePrice, 10);

            // All closes are 100, sorted: [100, 100, ..., 100]
            // indexOf returns 0 (first occurrence), result = (100 / 9) * 0 = 0.0
            double value = wordenStoch.getValue(9).doubleValue();
            assertThat(value).isCloseTo(0.0, within(0.01));
        }

        @Test
        @DisplayName("Should calculate high value when close is at top of range")
        void shouldCalculateHighValueAtTop() {
            BarSeries series = new BaseBarSeriesBuilder().withName("test").build();
            // Period 5: closes 10, 20, 30, 40, 50
            ZonedDateTime base = ZonedDateTime.of(2026, 4, 1, 9, 30, 0, 0, NY);
            double[] closes = {10, 20, 30, 40, 50};
            for (int i = 0; i < closes.length; i++) {
                series.addBar(base.plusMinutes(i), closes[i], closes[i] + 1, closes[i] - 1, closes[i], 1000);
            }

            ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
            WordenStochasticIndicator wordenStoch = new WordenStochasticIndicator(closePrice, 5);

            // At index 4 (close=50), sorted: [10, 20, 30, 40, 50], rank=4
            // result = (100 / 4) * 4 = 100.0
            assertThat(wordenStoch.getValue(4).doubleValue()).isCloseTo(100.0, within(0.01));

            // At index 2 (close=30), index < period-1=4, returns 50
            assertThat(wordenStoch.getValue(2).doubleValue()).isEqualTo(50.0);
        }
    }

    // =========================================================================
    // C5ContinuationCallStrategy Tests (Efecto Imán - CALL)
    // =========================================================================

    @Nested
    class C5ContinuationCallStrategyTest {

        /**
         * Builds a full StrategyData with daily, hourly, and 15m candles.
         * The currentTime parameter sets the "now" time for the strategy evaluation.
         * The 15m candles are built so that:
         * - The last candle is the "current" candle at currentTime
         * - The second-to-last candle is the "first 15m candle" (9:30-9:45)
         * Daily candles: last = today, idx-1 = yesterday, idx-2 = 2 days ago, idx-3 = 3 days ago
         */
        private StrategyData buildC5Data(
                ZonedDateTime currentTime,
                double[] dailyCloses,    // at least 4, from old to new
                double[] hourlyCloses,   // at least 25, from old to new
                double[] first15mOhlcv,  // {open, high, low, close, volume} for the 9:30-9:45 candle
                double[] current15mOhlcv // {open, high, low, close, volume} for the 9:45-10:00 candle
        ) {
            // Build daily candles
            List<Candle> dailyCandles = new ArrayList<>();
            ZonedDateTime dayBase = currentTime.toLocalDate().atStartOfDay(NY).minusDays(dailyCloses.length - 1);
            for (int i = 0; i < dailyCloses.length; i++) {
                double close = dailyCloses[i];
                dailyCandles.add(candle(
                        dayBase.plusDays(i),
                        close, close + 1, close - 1, close, 5000000L
                ));
            }

            // Build hourly candles
            List<Candle> hourlyCandles = new ArrayList<>();
            ZonedDateTime hourBase = currentTime.minusHours(hourlyCloses.length);
            for (int i = 0; i < hourlyCloses.length; i++) {
                double close = hourlyCloses[i];
                hourlyCandles.add(candle(
                        hourBase.plusHours(i),
                        close, close + 1, close - 1, close, 2000000L
                ));
            }

            // Build 15m candles
            List<Candle> candles15m = new ArrayList<>();
            // We need at least 22 candles: 20+ historical + first 15m (idx-1) + current (idx)
            // Historical 15m candles before today's open
            ZonedDateTime marketOpen = currentTime.toLocalDate().atTime(9, 30).atZone(NY)
                    .minusMinutes(15L * 20);
            for (int i = 0; i < 20; i++) {
                double close = 100.0;
                candles15m.add(candle(
                        marketOpen.plusMinutes(15L * i),
                        close, close + 0.5, close - 0.5, close, 500000L
                ));
            }

            // First 15m candle (9:30-9:45) - index = idx15m - 1
            candles15m.add(candle(
                    currentTime.toLocalDate().atTime(9, 30).atZone(NY),
                    first15mOhlcv[0], first15mOhlcv[1], first15mOhlcv[2], first15mOhlcv[3], (long) first15mOhlcv[4]
            ));

            // Current 15m candle (9:45-10:00) - index = idx15m
            candles15m.add(candle(
                    currentTime.toLocalDate().atTime(9, 45).atZone(NY),
                    current15mOhlcv[0], current15mOhlcv[1], current15mOhlcv[2], current15mOhlcv[3], (long) current15mOhlcv[4]
            ));

            Map<TimeFrame, List<Candle>> data = new EnumMap<>(TimeFrame.class);
            data.put(TimeFrame.DAY_1, dailyCandles);
            data.put(TimeFrame.HOUR_1, hourlyCandles);
            data.put(TimeFrame.MIN_15, candles15m);

            return new StrategyData(data);
        }

        @Test
        @DisplayName("Should trigger when all conditions are met (bearish trend, gap down, BB breakout, volume surge)")
        void shouldTriggerWhenAllConditionsMet() {
            ZonedDateTime testTime = ZonedDateTime.of(2026, 4, 8, 9, 50, 0, 0, NY);

            // Daily: 2 consecutive red candles (falling closes)
            double[] dailyCloses = {105, 104, 103, 102, 101}; // falling trend

            // Hourly: enough data, SMA20 will be around 100, first15mOpen must be < 97 (3% below)
            double[] hourlyCloses = new double[25];
            for (int i = 0; i < 25; i++) {
                hourlyCloses[i] = 100.0;
            }

            // First 15m candle: open at 94 (gap down from yesterday's 101, and >3% below SMA~100)
            // high must be completely below the lower Bollinger Band
            // With 20 historical closes at 100, SMA=100, stddev~0, lower band ~100
            // So high=95 is below 100
            double[] first15m = {94, 95, 93, 94.5, 2000000L}; // high volume, gap down

            // Current 15m candle: green (close > open), confirming reversal
            double[] current15m = {94.5, 96, 94, 95.5, 1000000L};

            StrategyData data = buildC5Data(testTime, dailyCloses, hourlyCloses, first15m, current15m);

            // Use default constructor (volume surge fallback)
            C5ContinuationCallStrategy strategy = new C5ContinuationCallStrategy();
            boolean triggered = strategy.isTriggered("TEST", data, testTime);

            assertThat(triggered).isTrue();
        }

        @Test
        @DisplayName("Should NOT trigger outside 9:45-9:55 AM window")
        void shouldNotTriggerOutsideTimeWindow() {
            ZonedDateTime testTime = ZonedDateTime.of(2026, 4, 8, 10, 30, 0, 0, NY);

            double[] dailyCloses = {105, 104, 103, 102, 101};
            double[] hourlyCloses = new double[25];
            for (int i = 0; i < 25; i++) hourlyCloses[i] = 100.0;
            double[] first15m = {94, 95, 93, 94.5, 2000000L};
            double[] current15m = {94.5, 96, 94, 95.5, 1000000L};

            StrategyData data = buildC5Data(testTime, dailyCloses, hourlyCloses, first15m, current15m);
            C5ContinuationCallStrategy strategy = new C5ContinuationCallStrategy();

            // 10:30 AM is outside the 9:45-9:55 window
            boolean triggered = strategy.isTriggered("TEST", data, testTime);
            assertThat(triggered).isFalse();
        }

        @Test
        @DisplayName("Should NOT trigger at 9:40 AM (before window)")
        void shouldNotTriggerAt940() {
            ZonedDateTime testTime = ZonedDateTime.of(2026, 4, 8, 9, 40, 0, 0, NY);

            double[] dailyCloses = {105, 104, 103, 102, 101};
            double[] hourlyCloses = new double[25];
            for (int i = 0; i < 25; i++) hourlyCloses[i] = 100.0;
            double[] first15m = {94, 95, 93, 94.5, 2000000L};
            double[] current15m = {94.5, 96, 94, 95.5, 1000000L};

            StrategyData data = buildC5Data(testTime, dailyCloses, hourlyCloses, first15m, current15m);
            C5ContinuationCallStrategy strategy = new C5ContinuationCallStrategy();

            boolean triggered = strategy.isTriggered("TEST", data, testTime);
            assertThat(triggered).isFalse();
        }

        @Test
        @DisplayName("Should NOT trigger without bearish trend (rising daily closes)")
        void shouldNotTriggerWithoutBearishTrend() {
            ZonedDateTime testTime = ZonedDateTime.of(2026, 4, 8, 9, 50, 0, 0, NY);

            // Rising closes = bullish trend, not bearish
            double[] dailyCloses = {98, 99, 100, 101, 102};
            double[] hourlyCloses = new double[25];
            for (int i = 0; i < 25; i++) hourlyCloses[i] = 100.0;
            double[] first15m = {94, 95, 93, 94.5, 2000000L};
            double[] current15m = {94.5, 96, 94, 95.5, 1000000L};

            StrategyData data = buildC5Data(testTime, dailyCloses, hourlyCloses, first15m, current15m);
            C5ContinuationCallStrategy strategy = new C5ContinuationCallStrategy();

            boolean triggered = strategy.isTriggered("TEST", data, testTime);
            assertThat(triggered).isFalse();
        }

        @Test
        @DisplayName("Should NOT trigger without gap down (open >= yesterday close)")
        void shouldNotTriggerWithoutGapDown() {
            ZonedDateTime testTime = ZonedDateTime.of(2026, 4, 8, 9, 50, 0, 0, NY);

            // Bearish trend
            double[] dailyCloses = {105, 104, 103, 102, 101};
            double[] hourlyCloses = new double[25];
            for (int i = 0; i < 25; i++) hourlyCloses[i] = 100.0;
            // First 15m open = 101.5 which is >= yesterday close of 101 -> NO gap down
            double[] first15m = {101.5, 102, 100, 101, 2000000L};
            double[] current15m = {101, 102, 100, 101.5, 1000000L};

            StrategyData data = buildC5Data(testTime, dailyCloses, hourlyCloses, first15m, current15m);
            C5ContinuationCallStrategy strategy = new C5ContinuationCallStrategy();

            boolean triggered = strategy.isTriggered("TEST", data, testTime);
            assertThat(triggered).isFalse();
        }

        @Test
        @DisplayName("Should NOT trigger when first 15m candle is NOT completely below Bollinger Band")
        void shouldNotTriggerWhenNotBelowBollingerBand() {
            ZonedDateTime testTime = ZonedDateTime.of(2026, 4, 8, 9, 50, 0, 0, NY);

            double[] dailyCloses = {105, 104, 103, 102, 101};
            double[] hourlyCloses = new double[25];
            for (int i = 0; i < 25; i++) hourlyCloses[i] = 100.0;
            // First 15m high = 101 which is NOT below the lower BB (~100 with flat data)
            double[] first15m = {94, 101, 93, 94.5, 2000000L};
            double[] current15m = {94.5, 96, 94, 95.5, 1000000L};

            StrategyData data = buildC5Data(testTime, dailyCloses, hourlyCloses, first15m, current15m);
            C5ContinuationCallStrategy strategy = new C5ContinuationCallStrategy();

            boolean triggered = strategy.isTriggered("TEST", data, testTime);
            assertThat(triggered).isFalse();
        }

        @Test
        @DisplayName("Should NOT trigger when current candle is not reversing up (red candle)")
        void shouldNotTriggerWhenNotReversingUp() {
            ZonedDateTime testTime = ZonedDateTime.of(2026, 4, 8, 9, 50, 0, 0, NY);

            double[] dailyCloses = {105, 104, 103, 102, 101};
            double[] hourlyCloses = new double[25];
            for (int i = 0; i < 25; i++) hourlyCloses[i] = 100.0;
            double[] first15m = {94, 95, 93, 94.5, 2000000L};
            // Current candle is red: close < open
            double[] current15m = {95.5, 96, 94, 94.0, 1000000L};

            StrategyData data = buildC5Data(testTime, dailyCloses, hourlyCloses, first15m, current15m);
            C5ContinuationCallStrategy strategy = new C5ContinuationCallStrategy();

            boolean triggered = strategy.isTriggered("TEST", data, testTime);
            assertThat(triggered).isFalse();
        }
    }

    // =========================================================================
    // P5ContinuationPutStrategy Tests (Efecto Imán - PUT)
    // =========================================================================

    @Nested
    class P5ContinuationPutStrategyTest {

        private StrategyData buildP5Data(
                ZonedDateTime currentTime,
                double[] dailyCloses,
                double[] hourlyCloses,
                double[] first15mOhlcv,
                double[] current15mOhlcv
        ) {
            List<Candle> dailyCandles = new ArrayList<>();
            ZonedDateTime dayBase = currentTime.toLocalDate().atStartOfDay(NY).minusDays(dailyCloses.length - 1);
            for (int i = 0; i < dailyCloses.length; i++) {
                double close = dailyCloses[i];
                dailyCandles.add(candle(
                        dayBase.plusDays(i),
                        close, close + 1, close - 1, close, 5000000L
                ));
            }

            List<Candle> hourlyCandles = new ArrayList<>();
            ZonedDateTime hourBase = currentTime.minusHours(hourlyCloses.length);
            for (int i = 0; i < hourlyCloses.length; i++) {
                double close = hourlyCloses[i];
                hourlyCandles.add(candle(
                        hourBase.plusHours(i),
                        close, close + 1, close - 1, close, 2000000L
                ));
            }

            List<Candle> candles15m = new ArrayList<>();
            ZonedDateTime marketOpen = currentTime.toLocalDate().atTime(9, 30).atZone(NY)
                    .minusMinutes(15L * 20);
            for (int i = 0; i < 20; i++) {
                double close = 100.0;
                candles15m.add(candle(
                        marketOpen.plusMinutes(15L * i),
                        close, close + 0.5, close - 0.5, close, 500000L
                ));
            }

            candles15m.add(candle(
                    currentTime.toLocalDate().atTime(9, 30).atZone(NY),
                    first15mOhlcv[0], first15mOhlcv[1], first15mOhlcv[2], first15mOhlcv[3], (long) first15mOhlcv[4]
            ));

            candles15m.add(candle(
                    currentTime.toLocalDate().atTime(9, 45).atZone(NY),
                    current15mOhlcv[0], current15mOhlcv[1], current15mOhlcv[2], current15mOhlcv[3], (long) current15mOhlcv[4]
            ));

            Map<TimeFrame, List<Candle>> data = new EnumMap<>(TimeFrame.class);
            data.put(TimeFrame.DAY_1, dailyCandles);
            data.put(TimeFrame.HOUR_1, hourlyCandles);
            data.put(TimeFrame.MIN_15, candles15m);

            return new StrategyData(data);
        }

        @Test
        @DisplayName("Should trigger PUT when all conditions are met (bullish trend, gap up, BB breakout above, volume surge)")
        void shouldTriggerPutWhenAllConditionsMet() {
            ZonedDateTime testTime = ZonedDateTime.of(2026, 4, 8, 9, 50, 0, 0, NY);

            // Daily: 2 consecutive green candles (rising closes)
            double[] dailyCloses = {98, 99, 100, 101, 102};

            // Hourly: SMA20 around 100, first15mOpen must be > 103 (3% above)
            double[] hourlyCloses = new double[25];
            for (int i = 0; i < 25; i++) hourlyCloses[i] = 100.0;

            // First 15m: open at 106 (gap up from 102, >3% above SMA~100)
            // low must be completely above the upper Bollinger Band (~100 with flat data)
            double[] first15m = {106, 108, 105, 107, 2000000L};

            // Current 15m: red candle (close < open), confirming reversal down
            double[] current15m = {107, 107.5, 104, 105, 1000000L};

            StrategyData data = buildP5Data(testTime, dailyCloses, hourlyCloses, first15m, current15m);
            P5ContinuationPutStrategy strategy = new P5ContinuationPutStrategy();

            boolean triggered = strategy.isTriggered("TEST", data, testTime);
            assertThat(triggered).isTrue();
        }

        @Test
        @DisplayName("Should NOT trigger PUT outside 9:45-9:55 AM window")
        void shouldNotTriggerPutOutsideTimeWindow() {
            ZonedDateTime testTime = ZonedDateTime.of(2026, 4, 8, 11, 0, 0, 0, NY);

            double[] dailyCloses = {98, 99, 100, 101, 102};
            double[] hourlyCloses = new double[25];
            for (int i = 0; i < 25; i++) hourlyCloses[i] = 100.0;
            double[] first15m = {106, 108, 105, 107, 2000000L};
            double[] current15m = {107, 107.5, 104, 105, 1000000L};

            StrategyData data = buildP5Data(testTime, dailyCloses, hourlyCloses, first15m, current15m);
            P5ContinuationPutStrategy strategy = new P5ContinuationPutStrategy();

            boolean triggered = strategy.isTriggered("TEST", data, testTime);
            assertThat(triggered).isFalse();
        }

        @Test
        @DisplayName("Should NOT trigger PUT without bullish trend (falling daily closes)")
        void shouldNotTriggerPutWithoutBullishTrend() {
            ZonedDateTime testTime = ZonedDateTime.of(2026, 4, 8, 9, 50, 0, 0, NY);

            // Falling = bearish, not bullish
            double[] dailyCloses = {105, 104, 103, 102, 101};
            double[] hourlyCloses = new double[25];
            for (int i = 0; i < 25; i++) hourlyCloses[i] = 100.0;
            double[] first15m = {106, 108, 105, 107, 2000000L};
            double[] current15m = {107, 107.5, 104, 105, 1000000L};

            StrategyData data = buildP5Data(testTime, dailyCloses, hourlyCloses, first15m, current15m);
            P5ContinuationPutStrategy strategy = new P5ContinuationPutStrategy();

            boolean triggered = strategy.isTriggered("TEST", data, testTime);
            assertThat(triggered).isFalse();
        }

        @Test
        @DisplayName("Should NOT trigger PUT without gap up (open <= yesterday close)")
        void shouldNotTriggerPutWithoutGapUp() {
            ZonedDateTime testTime = ZonedDateTime.of(2026, 4, 8, 9, 50, 0, 0, NY);

            double[] dailyCloses = {98, 99, 100, 101, 102};
            double[] hourlyCloses = new double[25];
            for (int i = 0; i < 25; i++) hourlyCloses[i] = 100.0;
            // First 15m open = 101 which is <= yesterday close of 102 -> NO gap up
            double[] first15m = {101, 103, 100, 102, 2000000L};
            double[] current15m = {102, 102.5, 100, 101, 1000000L};

            StrategyData data = buildP5Data(testTime, dailyCloses, hourlyCloses, first15m, current15m);
            P5ContinuationPutStrategy strategy = new P5ContinuationPutStrategy();

            boolean triggered = strategy.isTriggered("TEST", data, testTime);
            assertThat(triggered).isFalse();
        }

        @Test
        @DisplayName("Should NOT trigger PUT when first 15m candle is NOT completely above Bollinger Band")
        void shouldNotTriggerPutWhenNotAboveBollingerBand() {
            ZonedDateTime testTime = ZonedDateTime.of(2026, 4, 8, 9, 50, 0, 0, NY);

            double[] dailyCloses = {98, 99, 100, 101, 102};
            double[] hourlyCloses = new double[25];
            for (int i = 0; i < 25; i++) hourlyCloses[i] = 100.0;
            // First 15m low = 99 which is NOT above the upper BB (~100 with flat data)
            double[] first15m = {106, 108, 99, 107, 2000000L};
            double[] current15m = {107, 107.5, 104, 105, 1000000L};

            StrategyData data = buildP5Data(testTime, dailyCloses, hourlyCloses, first15m, current15m);
            P5ContinuationPutStrategy strategy = new P5ContinuationPutStrategy();

            boolean triggered = strategy.isTriggered("TEST", data, testTime);
            assertThat(triggered).isFalse();
        }

        @Test
        @DisplayName("Should NOT trigger PUT when current candle is not reversing down (green candle)")
        void shouldNotTriggerPutWhenNotReversingDown() {
            ZonedDateTime testTime = ZonedDateTime.of(2026, 4, 8, 9, 50, 0, 0, NY);

            double[] dailyCloses = {98, 99, 100, 101, 102};
            double[] hourlyCloses = new double[25];
            for (int i = 0; i < 25; i++) hourlyCloses[i] = 100.0;
            double[] first15m = {106, 108, 105, 107, 2000000L};
            // Current candle is green: close > open
            double[] current15m = {105, 107, 104, 106.5, 1000000L};

            StrategyData data = buildP5Data(testTime, dailyCloses, hourlyCloses, first15m, current15m);
            P5ContinuationPutStrategy strategy = new P5ContinuationPutStrategy();

            boolean triggered = strategy.isTriggered("TEST", data, testTime);
            assertThat(triggered).isFalse();
        }
    }

    // =========================================================================
    // C1SqueezeCallStrategy Tests
    // =========================================================================

    @Nested
    class C1SqueezeCallStrategyTest {

        private StrategyData buildC1Data(
                ZonedDateTime currentTime,
                double[] hourlyCloses,    // at least 201, from old to new
                double[] hourlyHighs,     // matching highs
                double[] hourlyOpens,     // matching opens
                double[] candles15mOhlcv  // {open, high, low, close, volume} for the current 15m candle
        ) {
            List<Candle> hourlyCandles = new ArrayList<>();
            ZonedDateTime hourBase = currentTime.minusHours(hourlyCloses.length);
            for (int i = 0; i < hourlyCloses.length; i++) {
                hourlyCandles.add(candle(
                        hourBase.plusHours(i),
                        hourlyOpens[i], hourlyHighs[i], hourlyCloses[i] - 0.5, hourlyCloses[i], 2000000L
                ));
            }

            // Build 15m candles: 20 historical + 1 current
            List<Candle> candles15m = new ArrayList<>();
            ZonedDateTime marketOpen = currentTime.toLocalDate().atTime(9, 30).atZone(NY)
                    .minusMinutes(15L * 20);
            for (int i = 0; i < 20; i++) {
                double close = 100.0;
                candles15m.add(candle(
                        marketOpen.plusMinutes(15L * i),
                        close, close + 0.5, close - 0.5, close, 500000L
                ));
            }
            // Current 15m candle
            candles15m.add(candle(
                    currentTime,
                    candles15mOhlcv[0], candles15mOhlcv[1], candles15mOhlcv[2], candles15mOhlcv[3], (long) candles15mOhlcv[4]
            ));

            Map<TimeFrame, List<Candle>> data = new EnumMap<>(TimeFrame.class);
            data.put(TimeFrame.HOUR_1, hourlyCandles);
            data.put(TimeFrame.MIN_15, candles15m);

            return new StrategyData(data);
        }

        @Test
        @DisplayName("Should trigger C1 squeeze call with compressed SMAs and breakout")
        void shouldTriggerC1WithCompressedSMAsAndBreakout() {
            ZonedDateTime testTime = ZonedDateTime.of(2026, 4, 8, 14, 0, 0, 0, NY);

            // ChannelAnalyzer.isSmaLateralChannel needs prevIdx >= 200 + 70 - 1 → >= 269 bars before breakout window
            int totalBars = 280;
            double[] hourlyCloses = new double[totalBars];
            double[] hourlyHighs = new double[totalBars];
            double[] hourlyOpens = new double[totalBars];

            for (int i = 0; i < totalBars - 1; i++) {
                double base = 100.0 + (i % 3) * 0.2 - 0.2;
                hourlyCloses[i] = base;
                hourlyHighs[i] = base + 0.5;
                hourlyOpens[i] = base;
            }

            int last = totalBars - 1;
            hourlyCloses[last] = 105.0;
            hourlyHighs[last] = 105.5;
            hourlyOpens[last] = 100.0;

            double[] current15m = {104, 105, 103, 104.5, 3000000L};

            StrategyData data = buildC1Data(testTime, hourlyCloses, hourlyHighs, hourlyOpens, current15m);
            C1SqueezeCallStrategy strategy = new C1SqueezeCallStrategy();

            boolean triggered = strategy.isTriggered("TEST", data, testTime);
            assertThat(triggered).isTrue();
        }

        @Test
        @DisplayName("Should NOT trigger C1 when SMAs are not compressed (spread > 4%)")
        void shouldNotTriggerC1WhenSMAsNotCompressed() {
            ZonedDateTime testTime = ZonedDateTime.of(2026, 4, 8, 14, 0, 0, 0, NY);

            int totalBars = 201;
            double[] hourlyCloses = new double[totalBars];
            double[] hourlyHighs = new double[totalBars];
            double[] hourlyOpens = new double[totalBars];

            // Create a strong uptrend: early closes low, recent closes high
            // SMA200 will be much lower than SMA20 -> spread > 4%
            for (int i = 0; i < totalBars; i++) {
                double close = 80.0 + i * 0.2; // 80 to 120
                hourlyCloses[i] = close;
                hourlyHighs[i] = close + 0.5;
                hourlyOpens[i] = close;
            }

            double[] current15m = {120, 122, 119, 121, 3000000L};

            StrategyData data = buildC1Data(testTime, hourlyCloses, hourlyHighs, hourlyOpens, current15m);
            C1SqueezeCallStrategy strategy = new C1SqueezeCallStrategy();

            boolean triggered = strategy.isTriggered("TEST", data, testTime);
            assertThat(triggered).isFalse();
        }

        @Test
        @DisplayName("Should NOT trigger C1 when there is no breakout (price does not break ceiling)")
        void shouldNotTriggerC1WhenNoBreakout() {
            ZonedDateTime testTime = ZonedDateTime.of(2026, 4, 8, 14, 0, 0, 0, NY);

            int totalBars = 201;
            double[] hourlyCloses = new double[totalBars];
            double[] hourlyHighs = new double[totalBars];
            double[] hourlyOpens = new double[totalBars];

            // All bars in tight range, no breakout
            for (int i = 0; i < totalBars; i++) {
                double base = 100.0 + (i % 3) * 0.1 - 0.1;
                hourlyCloses[i] = base;
                hourlyHighs[i] = base + 0.3;
                hourlyOpens[i] = base;
            }
            // Last bar: no breakout, close still within range
            hourlyCloses[200] = 100.0;
            hourlyHighs[200] = 100.3;
            hourlyOpens[200] = 100.0;

            double[] current15m = {100, 100.5, 99.5, 100.2, 500000L};

            StrategyData data = buildC1Data(testTime, hourlyCloses, hourlyHighs, hourlyOpens, current15m);
            C1SqueezeCallStrategy strategy = new C1SqueezeCallStrategy();

            boolean triggered = strategy.isTriggered("TEST", data, testTime);
            assertThat(triggered).isFalse();
        }

        @Test
        @DisplayName("Should NOT trigger C1 when breakout candle is red (close < open)")
        void shouldNotTriggerC1WhenBreakoutCandleIsRed() {
            ZonedDateTime testTime = ZonedDateTime.of(2026, 4, 8, 14, 0, 0, 0, NY);

            int totalBars = 201;
            double[] hourlyCloses = new double[totalBars];
            double[] hourlyHighs = new double[totalBars];
            double[] hourlyOpens = new double[totalBars];

            for (int i = 0; i < 200; i++) {
                double base = 100.0 + (i % 3) * 0.2 - 0.2;
                hourlyCloses[i] = base;
                hourlyHighs[i] = base + 0.5;
                hourlyOpens[i] = base;
            }

            // Breakout but red candle: open=105, close=102
            hourlyCloses[200] = 102.0;
            hourlyHighs[200] = 105.5;
            hourlyOpens[200] = 105.0;

            double[] current15m = {104, 105, 103, 104.5, 3000000L};

            StrategyData data = buildC1Data(testTime, hourlyCloses, hourlyHighs, hourlyOpens, current15m);
            C1SqueezeCallStrategy strategy = new C1SqueezeCallStrategy();

            boolean triggered = strategy.isTriggered("TEST", data, testTime);
            assertThat(triggered).isFalse();
        }

        @Test
        @DisplayName("Should NOT trigger C1 when 15m candle is not riding upper band")
        void shouldNotTriggerC1WhenNotRidingUpperBand() {
            ZonedDateTime testTime = ZonedDateTime.of(2026, 4, 8, 14, 0, 0, 0, NY);

            int totalBars = 201;
            double[] hourlyCloses = new double[totalBars];
            double[] hourlyHighs = new double[totalBars];
            double[] hourlyOpens = new double[totalBars];

            for (int i = 0; i < 200; i++) {
                double base = 100.0 + (i % 3) * 0.2 - 0.2;
                hourlyCloses[i] = base;
                hourlyHighs[i] = base + 0.5;
                hourlyOpens[i] = base;
            }
            hourlyCloses[200] = 105.0;
            hourlyHighs[200] = 105.5;
            hourlyOpens[200] = 100.0;

            // 15m candle NOT riding upper band: close is well below upper BB
            // With all historical 15m closes at 100, SMA20=100, stddev~0, upper band ~100
            // close=50 is far below 100 * 0.995 = 99.5
            double[] current15m = {50, 51, 49, 50, 500000L};

            StrategyData data = buildC1Data(testTime, hourlyCloses, hourlyHighs, hourlyOpens, current15m);
            C1SqueezeCallStrategy strategy = new C1SqueezeCallStrategy();

            boolean triggered = strategy.isTriggered("TEST", data, testTime);
            assertThat(triggered).isFalse();
        }
    }
}
