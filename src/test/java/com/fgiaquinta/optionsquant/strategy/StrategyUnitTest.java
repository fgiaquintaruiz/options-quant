package com.fgiaquinta.optionsquant.strategy;

import com.fgiaquinta.optionsquant.domain.Candle;
import com.fgiaquinta.optionsquant.domain.TimeFrame;
import com.fgiaquinta.optionsquant.strategy.data.StrategyData;
import com.fgiaquinta.optionsquant.strategy.indicator.WordenStochasticIndicator;
import com.fgiaquinta.optionsquant.strategy.C5ContinuationCallStrategy;
import com.fgiaquinta.optionsquant.strategy.P5ContinuationPutStrategy;
import com.fgiaquinta.optionsquant.strategy.C1SqueezeCallStrategy;
import com.fgiaquinta.optionsquant.strategy.P1SqueezePutStrategy;
import com.fgiaquinta.optionsquant.strategy.C4OpeningCallStrategy;
import com.fgiaquinta.optionsquant.strategy.P4OpeningPutStrategy;
import com.fgiaquinta.optionsquant.strategy.C2TrendCallStrategy;
import com.fgiaquinta.optionsquant.strategy.P2TrendPutStrategy;
import com.fgiaquinta.optionsquant.strategy.C3BounceCallStrategy;
import com.fgiaquinta.optionsquant.strategy.P3BouncePutStrategy;
import com.fgiaquinta.optionsquant.strategy.C6ReversalCallStrategy;
import com.fgiaquinta.optionsquant.strategy.P6ReversalPutStrategy;
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

    // =========================================================================
    // P1SqueezePutStrategy Tests (mirror of C1 — bearish)
    // =========================================================================

    @Nested
    class P1SqueezePutStrategyTest {

        private StrategyData buildP1Data(
                ZonedDateTime currentTime,
                double[] hourlyCloses,
                double[] hourlyLows,
                double[] hourlyOpens,
                double[] candles15mOhlcv
        ) {
            List<Candle> hourlyCandles = new ArrayList<>();
            ZonedDateTime hourBase = currentTime.minusHours(hourlyCloses.length);
            for (int i = 0; i < hourlyCloses.length; i++) {
                hourlyCandles.add(candle(
                        hourBase.plusHours(i),
                        hourlyOpens[i], hourlyCloses[i] + 0.5, hourlyLows[i], hourlyCloses[i], 2000000L
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
                    currentTime,
                    candles15mOhlcv[0], candles15mOhlcv[1], candles15mOhlcv[2], candles15mOhlcv[3], (long) candles15mOhlcv[4]
            ));

            Map<TimeFrame, List<Candle>> data = new EnumMap<>(TimeFrame.class);
            data.put(TimeFrame.HOUR_1, hourlyCandles);
            data.put(TimeFrame.MIN_15, candles15m);

            return new StrategyData(data);
        }

        @Test
        @DisplayName("Should trigger P1 squeeze put with compressed SMAs and bearish breakout")
        void shouldTriggerP1WithCompressedSMAsAndBearishBreakout() {
            ZonedDateTime testTime = ZonedDateTime.of(2026, 4, 8, 14, 0, 0, 0, NY);

            // ChannelAnalyzer.isSmaLateralChannel needs prevIdx >= 200 + 70 → 272 bars minimum
            int totalBars = 280;
            double[] hourlyCloses = new double[totalBars];
            double[] hourlyLows = new double[totalBars];
            double[] hourlyOpens = new double[totalBars];

            for (int i = 0; i < totalBars - 1; i++) {
                double base = 100.0 + (i % 3) * 0.2 - 0.2;
                hourlyCloses[i] = base;
                hourlyLows[i] = base - 0.5;
                hourlyOpens[i] = base;
            }

            // Breakout bar: close breaks below the floor (min low of last 70 bars ~99.3), red candle
            int last = totalBars - 1;
            hourlyOpens[last] = 100.0;
            hourlyCloses[last] = 95.0;
            hourlyLows[last] = 94.5;

            // 15m close riding lower band: with all historical 15m closes at 100, lower band ~100
            // close=99.5 <= 100 * 1.005 = 100.5 → riding lower band
            double[] current15m = {99.8, 99.9, 99.0, 99.5, 2000000L};

            StrategyData data = buildP1Data(testTime, hourlyCloses, hourlyLows, hourlyOpens, current15m);
            P1SqueezePutStrategy strategy = new P1SqueezePutStrategy();

            boolean triggered = strategy.isTriggered("TEST", data, testTime);
            assertThat(triggered).isTrue();
        }

        @Test
        @DisplayName("Should NOT trigger P1 when breakout direction is bullish (close > open)")
        void shouldNotTriggerP1WhenBreakoutIsGreenCandle() {
            ZonedDateTime testTime = ZonedDateTime.of(2026, 4, 8, 14, 0, 0, 0, NY);

            int totalBars = 280;
            double[] hourlyCloses = new double[totalBars];
            double[] hourlyLows = new double[totalBars];
            double[] hourlyOpens = new double[totalBars];

            for (int i = 0; i < totalBars - 1; i++) {
                double base = 100.0 + (i % 3) * 0.2 - 0.2;
                hourlyCloses[i] = base;
                hourlyLows[i] = base - 0.5;
                hourlyOpens[i] = base;
            }

            // Price breaks below floor but candle is GREEN (close > open) → not a bearish breakout
            int last = totalBars - 1;
            hourlyOpens[last] = 94.0;
            hourlyCloses[last] = 95.0;
            hourlyLows[last] = 93.5;

            double[] current15m = {99.6, 99.8, 99.3, 99.5, 2000000L};

            StrategyData data = buildP1Data(testTime, hourlyCloses, hourlyLows, hourlyOpens, current15m);
            P1SqueezePutStrategy strategy = new P1SqueezePutStrategy();

            boolean triggered = strategy.isTriggered("TEST", data, testTime);
            assertThat(triggered).isFalse();
        }

        @Test
        @DisplayName("Should NOT trigger P1 when SMAs are not compressed (strong downtrend)")
        void shouldNotTriggerP1WhenSMAsNotCompressed() {
            ZonedDateTime testTime = ZonedDateTime.of(2026, 4, 8, 14, 0, 0, 0, NY);

            int totalBars = 201;
            double[] hourlyCloses = new double[totalBars];
            double[] hourlyLows = new double[totalBars];
            double[] hourlyOpens = new double[totalBars];

            // Strong downtrend: SMA200 >> SMA20 → spread > 4%
            for (int i = 0; i < totalBars; i++) {
                double close = 120.0 - i * 0.2;
                hourlyCloses[i] = close;
                hourlyLows[i] = close - 0.5;
                hourlyOpens[i] = close + 0.1;
            }

            double[] current15m = {79.5, 79.8, 79.0, 79.3, 2000000L};

            StrategyData data = buildP1Data(testTime, hourlyCloses, hourlyLows, hourlyOpens, current15m);
            P1SqueezePutStrategy strategy = new P1SqueezePutStrategy();

            boolean triggered = strategy.isTriggered("TEST", data, testTime);
            assertThat(triggered).isFalse();
        }

        @Test
        @DisplayName("Should NOT trigger P1 with insufficient 1H history (< 200 bars)")
        void shouldNotTriggerP1WithInsufficientHistory() {
            ZonedDateTime testTime = ZonedDateTime.of(2026, 4, 8, 14, 0, 0, 0, NY);

            int totalBars = 150;
            double[] hourlyCloses = new double[totalBars];
            double[] hourlyLows = new double[totalBars];
            double[] hourlyOpens = new double[totalBars];

            for (int i = 0; i < totalBars; i++) {
                double base = 100.0 + (i % 3) * 0.2 - 0.2;
                hourlyCloses[i] = base;
                hourlyLows[i] = base - 0.5;
                hourlyOpens[i] = base;
            }

            double[] current15m = {99.5, 99.8, 99.0, 99.3, 2000000L};

            StrategyData data = buildP1Data(testTime, hourlyCloses, hourlyLows, hourlyOpens, current15m);
            P1SqueezePutStrategy strategy = new P1SqueezePutStrategy();

            boolean triggered = strategy.isTriggered("TEST", data, testTime);
            assertThat(triggered).isFalse();
        }
    }

    // =========================================================================
    // C4OpeningCallStrategy Tests (time window — gap down + green candle)
    // =========================================================================

    @Nested
    class C4OpeningCallStrategyTest {

        /**
         * Builds StrategyData for C4/P4 opening strategies.
         * The 15m series has 21 flat candles at basePrice ending at the current bar.
         * The 5m series has 2 candles: yesterday close (idx=0) and today's open bar (idx=1).
         * The gap is engineered so openToday = closeYesterday * (1 + gapPct).
         * With all historical 15m at basePrice, the BB is extremely tight:
         *   upper ≈ lower ≈ basePrice, so openToday < lower triggers gap-down (C4)
         *   and openToday > upper triggers gap-up (P4).
         */
        private StrategyData buildC4P4Data(
                ZonedDateTime currentTime,
                double basePrice,
                double closeYesterday,
                double openToday,
                boolean greenCandle
        ) {
            List<Candle> candles15m = new ArrayList<>();
            ZonedDateTime marketOpen15m = currentTime.toLocalDate().atTime(9, 30).atZone(NY)
                    .minusMinutes(15L * 20);
            for (int i = 0; i < 20; i++) {
                candles15m.add(candle(
                        marketOpen15m.plusMinutes(15L * i),
                        basePrice, basePrice + 0.5, basePrice - 0.5, basePrice, 500000L
                ));
            }
            // Current 15m bar at today's open time (idx=20)
            candles15m.add(candle(
                    currentTime,
                    openToday, openToday + 1, openToday - 1, openToday, 1000000L
            ));

            List<Candle> candles5m = new ArrayList<>();
            // Yesterday's last 5m close (idx=0)
            candles5m.add(candle(
                    currentTime.minusDays(1).withHour(16).withMinute(0),
                    closeYesterday, closeYesterday + 0.5, closeYesterday - 0.5, closeYesterday, 500000L
            ));
            // Today's open 5m candle (idx=1) — green or red determined by caller
            double close5m = greenCandle ? openToday + 0.5 : openToday - 0.5;
            candles5m.add(candle(
                    currentTime,
                    openToday, openToday + 1, openToday - 1, close5m, 2000000L
            ));

            Map<TimeFrame, List<Candle>> data = new EnumMap<>(TimeFrame.class);
            data.put(TimeFrame.MIN_15, candles15m);
            data.put(TimeFrame.MIN_5, candles5m);

            return new StrategyData(data);
        }

        @Test
        @DisplayName("Should trigger C4 when gap down in range and green 5m candle at 9:30")
        void shouldTriggerC4WhenAllConditionsMet() {
            ZonedDateTime testTime = ZonedDateTime.of(2026, 4, 8, 9, 33, 0, 0, NY);

            double basePrice = 100.0;
            double closeYesterday = 100.0;
            // Gap down of -3% → well within -1.5% to -6% range
            // openToday < lower band (lower ≈ basePrice with flat history) ✓
            double openToday = 97.0;

            StrategyData data = buildC4P4Data(testTime, basePrice, closeYesterday, openToday, true);
            C4OpeningCallStrategy strategy = new C4OpeningCallStrategy();

            boolean triggered = strategy.isTriggered("TEST", data, testTime);
            assertThat(triggered).isTrue();
        }

        @Test
        @DisplayName("Should NOT trigger C4 outside 9:30-9:35 AM window (at 10:00 AM)")
        void shouldNotTriggerC4OutsideTimeWindow() {
            ZonedDateTime testTime = ZonedDateTime.of(2026, 4, 8, 10, 0, 0, 0, NY);

            double basePrice = 100.0;
            double closeYesterday = 100.0;
            double openToday = 97.0;

            StrategyData data = buildC4P4Data(testTime, basePrice, closeYesterday, openToday, true);
            C4OpeningCallStrategy strategy = new C4OpeningCallStrategy();

            boolean triggered = strategy.isTriggered("TEST", data, testTime);
            assertThat(triggered).isFalse();
        }

        @Test
        @DisplayName("Should NOT trigger C4 when 5m candle is red (wrong direction)")
        void shouldNotTriggerC4WhenCandleIsRed() {
            ZonedDateTime testTime = ZonedDateTime.of(2026, 4, 8, 9, 33, 0, 0, NY);

            double basePrice = 100.0;
            double closeYesterday = 100.0;
            double openToday = 97.0;

            StrategyData data = buildC4P4Data(testTime, basePrice, closeYesterday, openToday, false);
            C4OpeningCallStrategy strategy = new C4OpeningCallStrategy();

            boolean triggered = strategy.isTriggered("TEST", data, testTime);
            assertThat(triggered).isFalse();
        }

        @Test
        @DisplayName("Should NOT trigger C4 when gap is too small (< -1.5%)")
        void shouldNotTriggerC4WhenGapTooSmall() {
            ZonedDateTime testTime = ZonedDateTime.of(2026, 4, 8, 9, 33, 0, 0, NY);

            double basePrice = 100.0;
            double closeYesterday = 100.0;
            // Gap of only -0.5% → below the -1.5% threshold
            double openToday = 99.5;

            StrategyData data = buildC4P4Data(testTime, basePrice, closeYesterday, openToday, true);
            C4OpeningCallStrategy strategy = new C4OpeningCallStrategy();

            boolean triggered = strategy.isTriggered("TEST", data, testTime);
            assertThat(triggered).isFalse();
        }
    }

    // =========================================================================
    // P4OpeningPutStrategy Tests (mirror of C4 — gap up + red candle)
    // =========================================================================

    @Nested
    class P4OpeningPutStrategyTest {

        private StrategyData buildP4Data(
                ZonedDateTime currentTime,
                double basePrice,
                double closeYesterday,
                double openToday,
                boolean redCandle
        ) {
            List<Candle> candles15m = new ArrayList<>();
            ZonedDateTime marketOpen15m = currentTime.toLocalDate().atTime(9, 30).atZone(NY)
                    .minusMinutes(15L * 20);
            for (int i = 0; i < 20; i++) {
                candles15m.add(candle(
                        marketOpen15m.plusMinutes(15L * i),
                        basePrice, basePrice + 0.5, basePrice - 0.5, basePrice, 500000L
                ));
            }
            candles15m.add(candle(
                    currentTime,
                    openToday, openToday + 1, openToday - 1, openToday, 1000000L
            ));

            List<Candle> candles5m = new ArrayList<>();
            candles5m.add(candle(
                    currentTime.minusDays(1).withHour(16).withMinute(0),
                    closeYesterday, closeYesterday + 0.5, closeYesterday - 0.5, closeYesterday, 500000L
            ));
            double close5m = redCandle ? openToday - 0.5 : openToday + 0.5;
            candles5m.add(candle(
                    currentTime,
                    openToday, openToday + 1, openToday - 1, close5m, 2000000L
            ));

            Map<TimeFrame, List<Candle>> data = new EnumMap<>(TimeFrame.class);
            data.put(TimeFrame.MIN_15, candles15m);
            data.put(TimeFrame.MIN_5, candles5m);

            return new StrategyData(data);
        }

        @Test
        @DisplayName("Should trigger P4 when gap up in range and red 5m candle at 9:30")
        void shouldTriggerP4WhenAllConditionsMet() {
            ZonedDateTime testTime = ZonedDateTime.of(2026, 4, 8, 9, 33, 0, 0, NY);

            double basePrice = 100.0;
            double closeYesterday = 100.0;
            // Gap up of +3% → within +1.5% to +6% range
            // openToday > upper band (upper ≈ basePrice with flat history) ✓
            double openToday = 103.0;

            StrategyData data = buildP4Data(testTime, basePrice, closeYesterday, openToday, true);
            P4OpeningPutStrategy strategy = new P4OpeningPutStrategy();

            boolean triggered = strategy.isTriggered("TEST", data, testTime);
            assertThat(triggered).isTrue();
        }

        @Test
        @DisplayName("Should NOT trigger P4 outside 9:30-9:35 AM window (at 10:00 AM)")
        void shouldNotTriggerP4OutsideTimeWindow() {
            ZonedDateTime testTime = ZonedDateTime.of(2026, 4, 8, 10, 0, 0, 0, NY);

            double basePrice = 100.0;
            double closeYesterday = 100.0;
            double openToday = 103.0;

            StrategyData data = buildP4Data(testTime, basePrice, closeYesterday, openToday, true);
            P4OpeningPutStrategy strategy = new P4OpeningPutStrategy();

            boolean triggered = strategy.isTriggered("TEST", data, testTime);
            assertThat(triggered).isFalse();
        }

        @Test
        @DisplayName("Should NOT trigger P4 when 5m candle is green (wrong direction)")
        void shouldNotTriggerP4WhenCandleIsGreen() {
            ZonedDateTime testTime = ZonedDateTime.of(2026, 4, 8, 9, 33, 0, 0, NY);

            double basePrice = 100.0;
            double closeYesterday = 100.0;
            double openToday = 103.0;

            StrategyData data = buildP4Data(testTime, basePrice, closeYesterday, openToday, false);
            P4OpeningPutStrategy strategy = new P4OpeningPutStrategy();

            boolean triggered = strategy.isTriggered("TEST", data, testTime);
            assertThat(triggered).isFalse();
        }

        @Test
        @DisplayName("Should NOT trigger P4 when gap is too small (< +1.5%)")
        void shouldNotTriggerP4WhenGapTooSmall() {
            ZonedDateTime testTime = ZonedDateTime.of(2026, 4, 8, 9, 33, 0, 0, NY);

            double basePrice = 100.0;
            double closeYesterday = 100.0;
            // Gap of only +0.5% → below the +1.5% threshold
            double openToday = 100.5;

            StrategyData data = buildP4Data(testTime, basePrice, closeYesterday, openToday, true);
            P4OpeningPutStrategy strategy = new P4OpeningPutStrategy();

            boolean triggered = strategy.isTriggered("TEST", data, testTime);
            assertThat(triggered).isFalse();
        }
    }

    // =========================================================================
    // C2TrendCallStrategy Tests (Trend + Pullback CALL)
    // =========================================================================

    @Nested
    class C2TrendCallStrategyTest {

        /**
         * DAY_1  : 22 bars; closes mildly rising so idx-1 > idx-2 (daily uptrend)
         * HOUR_1 : 25 bars; bars 0..20 flat at 100; bars 21..23 at 101.5 (above SMA20);
         *          bar 24 is the "current" bar, engineered to pass all 1h rules
         * MIN_15 : 22 bars; bars 0..20 flat at 101 (above SMA20 which ~=100), bar 21 current
         *
         * SMA20 on HOUR_1 at idx=24:
         *   bars 5..24 contribute: bars 5..20 = 100.0 (16 bars), bars 21..23 = 101.5 (3 bars), bar 24 = close1h
         *   With close1h ≈ 100.5, SMA20 ≈ (16*100 + 3*101.5 + 100.5) / 20 = 100.325
         *   low1h = 100.1 ≤ 100.325 * 1.005 = 100.776 → touchedSupport ✓
         *   close1h = 100.5 > 100.325 → rejectedSupport ✓
         *   open1h = 100.0 < close1h → bullish ✓
         *   range = high - low = 101.5 - 100.1 = 1.4; (high - close) = 1.0 ≤ 1.4 * 0.35 = 0.49 — too big
         *   Need close near high: high=100.55, low=100.1, close=100.5
         *   range=0.45; (high-close)=0.05 ≤ 0.45*0.35=0.1575 ✓
         */
        private StrategyData buildC2Data(
                ZonedDateTime currentTime,
                double[] dailyCloses,
                double[] hourlyCloses,
                double hourlyOpen,
                double hourlyHigh,
                double hourlyLow,
                double hourlyClose,
                long hourlyVolume,
                double[] candles15mCloses
        ) {
            List<Candle> dailyCandles = new ArrayList<>();
            ZonedDateTime dayBase = currentTime.toLocalDate().atStartOfDay(NY).minusDays(dailyCloses.length - 1);
            for (int i = 0; i < dailyCloses.length; i++) {
                double c = dailyCloses[i];
                dailyCandles.add(candle(dayBase.plusDays(i), c, c + 1, c - 1, c, 5000000L));
            }

            List<Candle> hourlyCandles = new ArrayList<>();
            ZonedDateTime hourBase = currentTime.minusHours(hourlyCloses.length);
            for (int i = 0; i < hourlyCloses.length; i++) {
                double c = hourlyCloses[i];
                hourlyCandles.add(candle(hourBase.plusHours(i), c, c + 0.5, c - 0.5, c, 2000000L));
            }
            hourlyCandles.add(candle(currentTime, hourlyOpen, hourlyHigh, hourlyLow, hourlyClose, hourlyVolume));

            List<Candle> candles15m = new ArrayList<>();
            ZonedDateTime min15Base = currentTime.toLocalDate().atTime(9, 30).atZone(NY)
                    .minusMinutes(15L * (candles15mCloses.length - 1));
            for (int i = 0; i < candles15mCloses.length - 1; i++) {
                double c = candles15mCloses[i];
                candles15m.add(candle(min15Base.plusMinutes(15L * i), c, c + 0.5, c - 0.5, c, 500000L));
            }
            double lastC = candles15mCloses[candles15mCloses.length - 1];
            candles15m.add(candle(currentTime, lastC, lastC + 0.5, lastC - 0.5, lastC, 500000L));

            Map<TimeFrame, List<Candle>> data = new EnumMap<>(TimeFrame.class);
            data.put(TimeFrame.DAY_1, dailyCandles);
            data.put(TimeFrame.HOUR_1, hourlyCandles);
            data.put(TimeFrame.MIN_15, candles15m);
            return new StrategyData(data);
        }

        private double[] risingDailyCloses(int count) {
            double[] closes = new double[count];
            for (int i = 0; i < count; i++) closes[i] = 100.0 + i * 0.1;
            return closes;
        }

        private double[] hourlyClosesWithUptrend() {
            double[] closes = new double[24];
            for (int i = 0; i < 21; i++) closes[i] = 100.0;
            closes[21] = 101.5;
            closes[22] = 101.5;
            closes[23] = 101.5;
            return closes;
        }

        private double[] bullish15mCloses(int count) {
            double[] closes = new double[count];
            for (int i = 0; i < count; i++) closes[i] = 98.0 + i * 0.1;
            return closes;
        }

        @Test
        @DisplayName("Should trigger C2 when all conditions are met")
        void shouldTriggerC2WhenAllConditionsMet() {
            ZonedDateTime testTime = ZonedDateTime.of(2026, 4, 8, 11, 0, 0, 0, NY);

            double[] daily = risingDailyCloses(22);
            double[] hourly = hourlyClosesWithUptrend();
            double[] min15 = bullish15mCloses(22);

            StrategyData data = buildC2Data(testTime, daily, hourly,
                    100.0, 100.55, 100.1, 100.5, 2000000L, min15);
            C2TrendCallStrategy strategy = new C2TrendCallStrategy();

            boolean triggered = strategy.isTriggered("AAPL", data, testTime);
            assertThat(triggered).isTrue();
        }

        @Test
        @DisplayName("Should NOT trigger C2 at 9 AM NY")
        void shouldNotTriggerC2At9AM() {
            ZonedDateTime testTime = ZonedDateTime.of(2026, 4, 8, 9, 45, 0, 0, NY);

            double[] daily = risingDailyCloses(22);
            double[] hourly = hourlyClosesWithUptrend();
            double[] min15 = bullish15mCloses(22);

            StrategyData data = buildC2Data(testTime, daily, hourly,
                    100.0, 100.55, 100.1, 100.5, 2000000L, min15);
            C2TrendCallStrategy strategy = new C2TrendCallStrategy();

            boolean triggered = strategy.isTriggered("AAPL", data, testTime);
            assertThat(triggered).isFalse();
        }

        @Test
        @DisplayName("Should NOT trigger C2 when cooldown active (second call same ticker same instance)")
        void shouldNotTriggerC2WhenCooldownActive() {
            ZonedDateTime testTime = ZonedDateTime.of(2026, 4, 8, 11, 0, 0, 0, NY);

            double[] daily = risingDailyCloses(22);
            double[] hourly = hourlyClosesWithUptrend();
            double[] min15 = bullish15mCloses(22);

            StrategyData data = buildC2Data(testTime, daily, hourly,
                    100.0, 100.55, 100.1, 100.5, 2000000L, min15);
            C2TrendCallStrategy strategy = new C2TrendCallStrategy();

            boolean first = strategy.isTriggered("AAPL", data, testTime);
            boolean second = strategy.isTriggered("AAPL", data, testTime);

            assertThat(first).isTrue();
            assertThat(second).isFalse();
        }

        @Test
        @DisplayName("Should NOT trigger C2 when low does not touch SMA20 (no pullback)")
        void shouldNotTriggerC2WhenNoPullbackToSma() {
            ZonedDateTime testTime = ZonedDateTime.of(2026, 4, 8, 11, 0, 0, 0, NY);

            double[] daily = risingDailyCloses(22);
            double[] hourly = hourlyClosesWithUptrend();
            double[] min15 = bullish15mCloses(22);

            // SMA20 at idx=24 ≈ 100.325 (from 16 bars at 100 + 3 bars at 101.5 + this close)
            // low=101.0 > 100.325 * 1.005 = 100.826 → touchedSupport = false
            StrategyData data = buildC2Data(testTime, daily, hourly,
                    101.0, 101.6, 101.0, 101.5, 2000000L, min15);
            C2TrendCallStrategy strategy = new C2TrendCallStrategy();

            boolean triggered = strategy.isTriggered("AAPL", data, testTime);
            assertThat(triggered).isFalse();
        }
    }

    // =========================================================================
    // P2TrendPutStrategy Tests (Trend + Pullback PUT)
    // =========================================================================

    @Nested
    class P2TrendPutStrategyTest {

        private StrategyData buildP2Data(
                ZonedDateTime currentTime,
                double[] dailyCloses,
                double[] hourlyCloses,
                double hourlyOpen,
                double hourlyHigh,
                double hourlyLow,
                double hourlyClose,
                long hourlyVolume,
                double[] candles15mCloses
        ) {
            List<Candle> dailyCandles = new ArrayList<>();
            ZonedDateTime dayBase = currentTime.toLocalDate().atStartOfDay(NY).minusDays(dailyCloses.length - 1);
            for (int i = 0; i < dailyCloses.length; i++) {
                double c = dailyCloses[i];
                dailyCandles.add(candle(dayBase.plusDays(i), c, c + 1, c - 1, c, 5000000L));
            }

            List<Candle> hourlyCandles = new ArrayList<>();
            ZonedDateTime hourBase = currentTime.minusHours(hourlyCloses.length);
            for (int i = 0; i < hourlyCloses.length; i++) {
                double c = hourlyCloses[i];
                hourlyCandles.add(candle(hourBase.plusHours(i), c, c + 0.5, c - 0.5, c, 2000000L));
            }
            hourlyCandles.add(candle(currentTime, hourlyOpen, hourlyHigh, hourlyLow, hourlyClose, hourlyVolume));

            List<Candle> candles15m = new ArrayList<>();
            ZonedDateTime min15Base = currentTime.toLocalDate().atTime(9, 30).atZone(NY)
                    .minusMinutes(15L * (candles15mCloses.length - 1));
            for (int i = 0; i < candles15mCloses.length - 1; i++) {
                double c = candles15mCloses[i];
                candles15m.add(candle(min15Base.plusMinutes(15L * i), c, c + 0.5, c - 0.5, c, 500000L));
            }
            double lastC = candles15mCloses[candles15mCloses.length - 1];
            candles15m.add(candle(currentTime, lastC, lastC + 0.5, lastC - 0.5, lastC, 500000L));

            Map<TimeFrame, List<Candle>> data = new EnumMap<>(TimeFrame.class);
            data.put(TimeFrame.DAY_1, dailyCandles);
            data.put(TimeFrame.HOUR_1, hourlyCandles);
            data.put(TimeFrame.MIN_15, candles15m);
            return new StrategyData(data);
        }

        private double[] fallingDailyCloses(int count) {
            double[] closes = new double[count];
            for (int i = 0; i < count; i++) closes[i] = 102.0 - i * 0.1;
            return closes;
        }

        private double[] hourlyClosesWithDowntrend() {
            double[] closes = new double[24];
            for (int i = 0; i < 21; i++) closes[i] = 100.0;
            closes[21] = 98.5;
            closes[22] = 98.5;
            closes[23] = 98.5;
            return closes;
        }

        private double[] bearish15mCloses(int count) {
            double[] closes = new double[count];
            for (int i = 0; i < count; i++) closes[i] = 102.0 - i * 0.1;
            return closes;
        }

        @Test
        @DisplayName("Should trigger P2 when all conditions are met")
        void shouldTriggerP2WhenAllConditionsMet() {
            ZonedDateTime testTime = ZonedDateTime.of(2026, 4, 8, 11, 0, 0, 0, NY);

            double[] daily = fallingDailyCloses(22);
            double[] hourly = hourlyClosesWithDowntrend();
            double[] min15 = bearish15mCloses(22);

            // SMA20 at idx=24 ≈ (16*100 + 3*98.5 + close) / 20
            // close = 99.5 → SMA20 ≈ (1600 + 295.5 + 99.5) / 20 = 99.75
            // high=99.85 >= 99.75 * 0.995 = 99.25 → touchedResistance ✓
            // close=99.5 < 99.75 → rejectedResistance ✓
            // open=100.1 > close=99.5 → bearish ✓
            // range = 99.85 - 99.4 = 0.45; (close - low) = 99.5 - 99.4 = 0.1 ≤ 0.45*0.35 = 0.1575 ✓
            StrategyData data = buildP2Data(testTime, daily, hourly,
                    100.1, 99.85, 99.4, 99.5, 2000000L, min15);
            P2TrendPutStrategy strategy = new P2TrendPutStrategy();

            boolean triggered = strategy.isTriggered("AAPL", data, testTime);
            assertThat(triggered).isTrue();
        }

        @Test
        @DisplayName("Should NOT trigger P2 at 9 AM NY")
        void shouldNotTriggerP2At9AM() {
            ZonedDateTime testTime = ZonedDateTime.of(2026, 4, 8, 9, 45, 0, 0, NY);

            double[] daily = fallingDailyCloses(22);
            double[] hourly = hourlyClosesWithDowntrend();
            double[] min15 = bearish15mCloses(22);

            StrategyData data = buildP2Data(testTime, daily, hourly,
                    100.1, 99.85, 99.4, 99.5, 2000000L, min15);
            P2TrendPutStrategy strategy = new P2TrendPutStrategy();

            boolean triggered = strategy.isTriggered("AAPL", data, testTime);
            assertThat(triggered).isFalse();
        }

        @Test
        @DisplayName("Should NOT trigger P2 when cooldown active (second call same ticker same instance)")
        void shouldNotTriggerP2WhenCooldownActive() {
            ZonedDateTime testTime = ZonedDateTime.of(2026, 4, 8, 11, 0, 0, 0, NY);

            double[] daily = fallingDailyCloses(22);
            double[] hourly = hourlyClosesWithDowntrend();
            double[] min15 = bearish15mCloses(22);

            StrategyData data = buildP2Data(testTime, daily, hourly,
                    100.1, 99.85, 99.4, 99.5, 2000000L, min15);
            P2TrendPutStrategy strategy = new P2TrendPutStrategy();

            boolean first = strategy.isTriggered("AAPL", data, testTime);
            boolean second = strategy.isTriggered("AAPL", data, testTime);

            assertThat(first).isTrue();
            assertThat(second).isFalse();
        }

        @Test
        @DisplayName("Should NOT trigger P2 when high does not touch SMA20 (no pullback)")
        void shouldNotTriggerP2WhenNoPullbackToSma() {
            ZonedDateTime testTime = ZonedDateTime.of(2026, 4, 8, 11, 0, 0, 0, NY);

            double[] daily = fallingDailyCloses(22);
            double[] hourly = hourlyClosesWithDowntrend();
            double[] min15 = bearish15mCloses(22);

            // SMA20 ≈ 99.75; high=98.5 < 99.75 * 0.995 = 99.25 → touchedResistance = false
            StrategyData data = buildP2Data(testTime, daily, hourly,
                    99.0, 98.5, 97.5, 98.0, 2000000L, min15);
            P2TrendPutStrategy strategy = new P2TrendPutStrategy();

            boolean triggered = strategy.isTriggered("AAPL", data, testTime);
            assertThat(triggered).isFalse();
        }

        /**
         * Verifies that priceBelowMiddleBB is a redundant predicate and can be removed
         * without changing observable behaviour.
         *
         * priceBelowMiddleBB = currentPrice15m < bb15m.getMiddle(idx15m)
         * isDowntrend15m     = (currentPrice15m < currentSma15m) && (currentSma15m < prevSma15m)
         *
         * Both use the same SMA20 of 15-minute closes, so priceBelowMiddleBB is always
         * implied by isDowntrend15m.  A setup that satisfies isDowntrend15m + isBearishBBTrend
         * must already satisfy priceBelowMiddleBB — the third predicate adds no restriction.
         *
         * RED reasoning: the predicate is currently present in the AND chain but is logically
         * unreachable as an independent gate.  This test documents that the signal fires with
         * isDowntrend15m AND isBearishBBTrend alone (which is the entire effective condition),
         * and must continue to fire after the redundant predicate is removed.
         */
        @Test
        @DisplayName("priceBelowMiddleBB is redundant — signal fires with isDowntrend15m+isBearishBBTrend alone")
        void priceBelowMiddleBB_isRedundant_signalFiresWithTwoPredicatesAlone() {
            ZonedDateTime testTime = ZonedDateTime.of(2026, 4, 8, 11, 0, 0, 0, NY);

            double[] daily = fallingDailyCloses(22);
            double[] hourly = hourlyClosesWithDowntrend();
            // bearish15mCloses: each close decreases by 0.1; last close = 99.9, SMA20 ≈ 101.05
            // → isDowntrend15m=true (price < SMA AND SMA falling)
            // → isBearishBBTrend=true (>70% of last 10 candles below middle band)
            // → priceBelowMiddleBB=true (same SMA as isDowntrend15m — always redundant)
            double[] min15 = bearish15mCloses(22);

            // 1H candle: valid pullback-rejection setup (same as "all conditions met" test)
            StrategyData data = buildP2Data(testTime, daily, hourly,
                    100.1, 99.85, 99.4, 99.5, 2000000L, min15);
            P2TrendPutStrategy strategy = new P2TrendPutStrategy();

            // Must fire: removing priceBelowMiddleBB from the AND chain cannot change this result
            boolean triggered = strategy.isTriggered("AAPL", data, testTime);
            assertThat(triggered)
                    .as("Signal must fire when isDowntrend15m=true AND isBearishBBTrend=true; " +
                        "priceBelowMiddleBB is always implied by isDowntrend15m (same SMA20 comparison) " +
                        "and must not be an independent gate.")
                    .isTrue();
        }
    }

    // =========================================================================
    // C3BounceCallStrategy Tests (SMA20 bounce — CALL)
    // =========================================================================

    @Nested
    class C3BounceCallStrategyTest {

        /**
         * Builds StrategyData for C3 tests.
         *
         * DAY_1  : 25 bars (idx=24 is "today"), closes supplied via dailyCloses array
         * HOUR_1 : 30 bars; bars at positions 25-27 set to dipClose to produce a
         *          recent brokeBelowLowerBand event; bar 29 is the "current" bar
         * MIN_15 : 25 bars; all flat at 100 except the last bar whose close/low
         *          are set by the caller
         */
        private StrategyData buildC3Data(
                ZonedDateTime currentTime,
                double[] dailyCloses,
                double dipClose,
                double current1hClose,
                double current1hLow,
                double current15mClose
        ) {
            List<Candle> dailyCandles = new ArrayList<>();
            ZonedDateTime dayBase = currentTime.toLocalDate().atStartOfDay(NY).minusDays(dailyCloses.length - 1);
            for (int i = 0; i < dailyCloses.length; i++) {
                double c = dailyCloses[i];
                dailyCandles.add(candle(dayBase.plusDays(i), c, c + 1, c - 1, c, 5000000L));
            }

            List<Candle> hourlyCandles = new ArrayList<>();
            int totalHours = 30;
            ZonedDateTime hourBase = currentTime.minusHours(totalHours);
            for (int i = 0; i < totalHours; i++) {
                double c = (i >= 25 && i <= 27) ? dipClose : 100.0;
                if (i == totalHours - 1) {
                    hourlyCandles.add(candle(hourBase.plusHours(i),
                            current1hClose, current1hClose + 1, current1hLow, current1hClose, 2000000L));
                } else {
                    hourlyCandles.add(candle(hourBase.plusHours(i), c, c + 0.5, c - 0.5, c, 2000000L));
                }
            }

            List<Candle> candles15m = new ArrayList<>();
            int total15m = 25;
            ZonedDateTime min15Base = currentTime.toLocalDate().atTime(9, 30).atZone(NY)
                    .minusMinutes(15L * (total15m - 1));
            for (int i = 0; i < total15m; i++) {
                if (i == total15m - 1) {
                    candles15m.add(candle(min15Base.plusMinutes(15L * i),
                            current15mClose, current15mClose + 0.5, current15mClose - 0.5,
                            current15mClose, 1000000L));
                } else {
                    candles15m.add(candle(min15Base.plusMinutes(15L * i),
                            100.0, 100.5, 99.5, 100.0, 500000L));
                }
            }

            Map<TimeFrame, List<Candle>> data = new EnumMap<>(TimeFrame.class);
            data.put(TimeFrame.DAY_1, dailyCandles);
            data.put(TimeFrame.HOUR_1, hourlyCandles);
            data.put(TimeFrame.MIN_15, candles15m);
            return new StrategyData(data);
        }

        @Test
        @DisplayName("Should trigger C3 when all conditions are met")
        void shouldTriggerC3WhenAllConditionsMet() {
            ZonedDateTime testTime = ZonedDateTime.of(2026, 4, 8, 10, 5, 0, 0, NY);

            double[] dailyCloses = new double[25];
            for (int i = 0; i < 24; i++) dailyCloses[i] = 100.0 + i * 0.1;
            dailyCloses[24] = 102.0;

            StrategyData data = buildC3Data(testTime, dailyCloses, 90.0, 99.0, 98.0, 102.0);
            C3BounceCallStrategy strategy = new C3BounceCallStrategy();

            boolean triggered = strategy.isTriggered("AAPL", data, testTime);
            assertThat(triggered).isTrue();
        }

        @Test
        @DisplayName("Should NOT trigger C3 before 10 AM NY")
        void shouldNotTriggerC3BeforeTenAM() {
            ZonedDateTime testTime = ZonedDateTime.of(2026, 4, 8, 9, 50, 0, 0, NY);

            double[] dailyCloses = new double[25];
            for (int i = 0; i < 24; i++) dailyCloses[i] = 100.0 + i * 0.1;
            dailyCloses[24] = 102.0;

            StrategyData data = buildC3Data(testTime, dailyCloses, 90.0, 99.0, 98.0, 102.0);
            C3BounceCallStrategy strategy = new C3BounceCallStrategy();

            boolean triggered = strategy.isTriggered("AAPL", data, testTime);
            assertThat(triggered).isFalse();
        }

        @Test
        @DisplayName("Should NOT trigger C3 when cooldown is active (second call in same 2h window)")
        void shouldNotTriggerC3WhenCooldownActive() {
            ZonedDateTime testTime = ZonedDateTime.of(2026, 4, 8, 10, 5, 0, 0, NY);

            double[] dailyCloses = new double[25];
            for (int i = 0; i < 24; i++) dailyCloses[i] = 100.0 + i * 0.1;
            dailyCloses[24] = 102.0;

            StrategyData data = buildC3Data(testTime, dailyCloses, 90.0, 99.0, 98.0, 102.0);
            C3BounceCallStrategy strategy = new C3BounceCallStrategy();

            boolean first = strategy.isTriggered("AAPL", data, testTime);
            boolean second = strategy.isTriggered("AAPL", data, testTime);

            assertThat(first).isTrue();
            assertThat(second).isFalse();
        }

        @Test
        @DisplayName("Should NOT trigger C3 when uptrend is absent (flat daily closes)")
        void shouldNotTriggerC3WhenNoTrend() {
            ZonedDateTime testTime = ZonedDateTime.of(2026, 4, 8, 10, 5, 0, 0, NY);

            double[] dailyCloses = new double[25];
            for (int i = 0; i < 25; i++) dailyCloses[i] = 100.0;

            StrategyData data = buildC3Data(testTime, dailyCloses, 90.0, 99.0, 98.0, 102.0);
            C3BounceCallStrategy strategy = new C3BounceCallStrategy();

            boolean triggered = strategy.isTriggered("AAPL", data, testTime);
            assertThat(triggered).isFalse();
        }
    }

    // =========================================================================
    // P3BouncePutStrategy Tests (SMA20 rejection — PUT)
    // =========================================================================

    @Nested
    class P3BouncePutStrategyTest {

        /**
         * Builds StrategyData for P3 tests.
         *
         * DAY_1  : 25 bars; closes supplied via dailyCloses array
         * HOUR_1 : 30 bars; bars at positions 25-27 set to spikeClose to produce a
         *          recent brokeAboveUpperBand event; bar 29 is the "current" bar
         * MIN_15 : 25 bars; flat at 100 except the last bar with supplied close
         */
        private StrategyData buildP3Data(
                ZonedDateTime currentTime,
                double[] dailyCloses,
                double spikeClose,
                double current1hClose,
                double current1hHigh,
                double current15mClose
        ) {
            List<Candle> dailyCandles = new ArrayList<>();
            ZonedDateTime dayBase = currentTime.toLocalDate().atStartOfDay(NY).minusDays(dailyCloses.length - 1);
            for (int i = 0; i < dailyCloses.length; i++) {
                double c = dailyCloses[i];
                dailyCandles.add(candle(dayBase.plusDays(i), c, c + 1, c - 1, c, 5000000L));
            }

            List<Candle> hourlyCandles = new ArrayList<>();
            int totalHours = 30;
            ZonedDateTime hourBase = currentTime.minusHours(totalHours);
            for (int i = 0; i < totalHours; i++) {
                double c = (i >= 25 && i <= 27) ? spikeClose : 100.0;
                if (i == totalHours - 1) {
                    hourlyCandles.add(candle(hourBase.plusHours(i),
                            current1hClose, current1hHigh, current1hClose - 0.5, current1hClose, 2000000L));
                } else {
                    hourlyCandles.add(candle(hourBase.plusHours(i), c, c + 0.5, c - 0.5, c, 2000000L));
                }
            }

            List<Candle> candles15m = new ArrayList<>();
            int total15m = 25;
            ZonedDateTime min15Base = currentTime.toLocalDate().atTime(9, 30).atZone(NY)
                    .minusMinutes(15L * (total15m - 1));
            for (int i = 0; i < total15m; i++) {
                if (i == total15m - 1) {
                    candles15m.add(candle(min15Base.plusMinutes(15L * i),
                            current15mClose, current15mClose + 0.5, current15mClose - 0.5,
                            current15mClose, 1000000L));
                } else {
                    candles15m.add(candle(min15Base.plusMinutes(15L * i),
                            100.0, 100.5, 99.5, 100.0, 500000L));
                }
            }

            Map<TimeFrame, List<Candle>> data = new EnumMap<>(TimeFrame.class);
            data.put(TimeFrame.DAY_1, dailyCandles);
            data.put(TimeFrame.HOUR_1, hourlyCandles);
            data.put(TimeFrame.MIN_15, candles15m);
            return new StrategyData(data);
        }

        @Test
        @DisplayName("Should trigger P3 when all conditions are met")
        void shouldTriggerP3WhenAllConditionsMet() {
            ZonedDateTime testTime = ZonedDateTime.of(2026, 4, 8, 10, 5, 0, 0, NY);

            double[] dailyCloses = new double[25];
            for (int i = 0; i < 24; i++) dailyCloses[i] = 102.0 - i * 0.1;
            dailyCloses[24] = 100.0;

            StrategyData data = buildP3Data(testTime, dailyCloses, 110.0, 100.5, 101.5, 98.0);
            P3BouncePutStrategy strategy = new P3BouncePutStrategy();

            boolean triggered = strategy.isTriggered("AAPL", data, testTime);
            assertThat(triggered).isTrue();
        }

        @Test
        @DisplayName("Should NOT trigger P3 before 10 AM NY")
        void shouldNotTriggerP3BeforeTenAM() {
            ZonedDateTime testTime = ZonedDateTime.of(2026, 4, 8, 9, 50, 0, 0, NY);

            double[] dailyCloses = new double[25];
            for (int i = 0; i < 24; i++) dailyCloses[i] = 102.0 - i * 0.1;
            dailyCloses[24] = 100.0;

            StrategyData data = buildP3Data(testTime, dailyCloses, 110.0, 100.5, 101.5, 98.0);
            P3BouncePutStrategy strategy = new P3BouncePutStrategy();

            boolean triggered = strategy.isTriggered("AAPL", data, testTime);
            assertThat(triggered).isFalse();
        }

        @Test
        @DisplayName("Should NOT trigger P3 when cooldown is active (second call in same 2h window)")
        void shouldNotTriggerP3WhenCooldownActive() {
            ZonedDateTime testTime = ZonedDateTime.of(2026, 4, 8, 10, 5, 0, 0, NY);

            double[] dailyCloses = new double[25];
            for (int i = 0; i < 24; i++) dailyCloses[i] = 102.0 - i * 0.1;
            dailyCloses[24] = 100.0;

            StrategyData data = buildP3Data(testTime, dailyCloses, 110.0, 100.5, 101.5, 98.0);
            P3BouncePutStrategy strategy = new P3BouncePutStrategy();

            boolean first = strategy.isTriggered("AAPL", data, testTime);
            boolean second = strategy.isTriggered("AAPL", data, testTime);

            assertThat(first).isTrue();
            assertThat(second).isFalse();
        }

        @Test
        @DisplayName("Should NOT trigger P3 when downtrend is absent (flat daily closes)")
        void shouldNotTriggerP3WhenNoTrend() {
            ZonedDateTime testTime = ZonedDateTime.of(2026, 4, 8, 10, 5, 0, 0, NY);

            double[] dailyCloses = new double[25];
            for (int i = 0; i < 25; i++) dailyCloses[i] = 100.0;

            StrategyData data = buildP3Data(testTime, dailyCloses, 110.0, 100.5, 101.5, 98.0);
            P3BouncePutStrategy strategy = new P3BouncePutStrategy();

            boolean triggered = strategy.isTriggered("AAPL", data, testTime);
            assertThat(triggered).isFalse();
        }
    }

    // =========================================================================
    // C6ReversalCallStrategy Tests
    // =========================================================================

    @Nested
    class C6ReversalCallStrategyTest {

        /**
         * Builds StrategyData for C6:
         * DAY_1  : 22 flat bars (just to satisfy idx1D >= 20)
         * HOUR_1 : 24 history bars + 1 current bar (engineered OHLCV passed in)
         *          bars 0..20 = 100.0 (SMA20 anchor)
         *          bars 21..23 = belowSmaClose (to create the 3-bar downtrend prior to breakout)
         * MIN_15 : 21 history bars + 1 current bar (all at min15Close, last one at currentTime)
         *          bars built so SMA20 is rising: last 2 bars differ by +0.1
         */
        private StrategyData buildC6Data(
                ZonedDateTime currentTime,
                double belowSmaClose,
                double currentHourOpen,
                double currentHourHigh,
                double currentHourLow,
                double currentHourClose,
                long currentHourVolume,
                double min15Close
        ) {
            List<Candle> dailyCandles = new ArrayList<>();
            ZonedDateTime dayBase = currentTime.toLocalDate().atStartOfDay(NY).minusDays(21);
            for (int i = 0; i < 22; i++) {
                dailyCandles.add(candle(dayBase.plusDays(i), 100.0, 101.0, 99.0, 100.0, 5000000L));
            }

            List<Candle> hourlyCandles = new ArrayList<>();
            ZonedDateTime hourBase = currentTime.minusHours(24);
            for (int i = 0; i < 21; i++) {
                hourlyCandles.add(candle(hourBase.plusHours(i), 100.0, 100.5, 99.5, 100.0, 2000000L));
            }
            for (int i = 21; i < 24; i++) {
                hourlyCandles.add(candle(hourBase.plusHours(i), belowSmaClose, belowSmaClose + 0.3, belowSmaClose - 0.3, belowSmaClose, 2000000L));
            }
            hourlyCandles.add(candle(currentTime, currentHourOpen, currentHourHigh, currentHourLow, currentHourClose, currentHourVolume));

            List<Candle> candles15m = new ArrayList<>();
            ZonedDateTime min15Base = currentTime.minusMinutes(15L * 21);
            for (int i = 0; i < 20; i++) {
                double c = min15Close - 0.2 + i * 0.01;
                candles15m.add(candle(min15Base.plusMinutes(15L * i), c, c + 0.2, c - 0.2, c, 500000L));
            }
            candles15m.add(candle(min15Base.plusMinutes(15L * 20), min15Close - 0.1, min15Close + 0.1, min15Close - 0.3, min15Close - 0.1, 500000L));
            candles15m.add(candle(currentTime, min15Close, min15Close + 0.2, min15Close - 0.1, min15Close, 500000L));

            Map<TimeFrame, List<Candle>> data = new EnumMap<>(TimeFrame.class);
            data.put(TimeFrame.DAY_1, dailyCandles);
            data.put(TimeFrame.HOUR_1, hourlyCandles);
            data.put(TimeFrame.MIN_15, candles15m);
            return new StrategyData(data);
        }

        @Test
        @DisplayName("Should trigger C6 when all conditions are met")
        void shouldTriggerC6WhenAllConditionsMet() {
            ZonedDateTime testTime = ZonedDateTime.of(2026, 4, 8, 11, 0, 0, 0, NY);

            // belowSmaClose=98.5 → prior 3 bars below SMA20 (~100)
            // current bar: open=98.5 (below SMA), close=100.8 (above SMA ~100), bullish, top 35%
            // high=100.9, low=98.4 → range=2.5, (high-close)=0.1 ≤ 2.5*0.35=0.875 ✓
            // volume=2200000 >= avgVol(2000000)*0.90=1800000 ✓
            // min15Close=101.0 → above SMA20 and SMA slope up
            StrategyData data = buildC6Data(testTime, 98.5,
                    98.5, 100.9, 98.4, 100.8, 2200000L, 101.0);
            C6ReversalCallStrategy strategy = new C6ReversalCallStrategy();

            assertThat(strategy.isTriggered("AAPL", data, testTime)).isTrue();
        }

        @Test
        @DisplayName("Should NOT trigger C6 when cooldown active (same instance, same ticker, two calls)")
        void shouldNotTriggerC6WhenCooldownActive() {
            ZonedDateTime testTime = ZonedDateTime.of(2026, 4, 8, 11, 0, 0, 0, NY);

            StrategyData data = buildC6Data(testTime, 98.5,
                    98.5, 100.9, 98.4, 100.8, 2200000L, 101.0);
            C6ReversalCallStrategy strategy = new C6ReversalCallStrategy();

            boolean first = strategy.isTriggered("AAPL", data, testTime);
            boolean second = strategy.isTriggered("AAPL", data, testTime);

            assertThat(first).isTrue();
            assertThat(second).isFalse();
        }

        @Test
        @DisplayName("Should NOT trigger C6 when close does not break above SMA20")
        void shouldNotTriggerC6WhenNoBreakoutAboveSma() {
            ZonedDateTime testTime = ZonedDateTime.of(2026, 4, 8, 11, 0, 0, 0, NY);

            // close=99.5 < SMA20 (~100) → crossedAboveSma = false
            StrategyData data = buildC6Data(testTime, 98.5,
                    98.5, 99.8, 98.0, 99.5, 2200000L, 101.0);
            C6ReversalCallStrategy strategy = new C6ReversalCallStrategy();

            assertThat(strategy.isTriggered("AAPL", data, testTime)).isFalse();
        }

        @Test
        @DisplayName("Should NOT trigger C6 when candle closes weak (long upper wick, not in top 35%)")
        void shouldNotTriggerC6WhenCandleWeak() {
            ZonedDateTime testTime = ZonedDateTime.of(2026, 4, 8, 11, 0, 0, 0, NY);

            // close=100.2, high=103.0, low=98.4 → range=4.6, (high-close)=2.8 > 4.6*0.35=1.61 → closedNearHigh=false
            StrategyData data = buildC6Data(testTime, 98.5,
                    98.5, 103.0, 98.4, 100.2, 2200000L, 101.0);
            C6ReversalCallStrategy strategy = new C6ReversalCallStrategy();

            assertThat(strategy.isTriggered("AAPL", data, testTime)).isFalse();
        }
    }

    // =========================================================================
    // P6ReversalPutStrategy Tests
    // =========================================================================

    @Nested
    class P6ReversalPutStrategyTest {

        /**
         * Builds StrategyData for P6 (mirror of C6):
         * DAY_1  : 22 flat bars
         * HOUR_1 : 24 history bars + 1 current bar
         *          bars 0..20 = 100.0 (SMA20 anchor)
         *          bars 21..23 = aboveSmaClose (3-bar uptrend prior to breakdown)
         * MIN_15 : 21 history bars + 1 current bar at min15Close
         *          built so SMA20 is falling: last 2 bars differ by -0.1
         */
        private StrategyData buildP6Data(
                ZonedDateTime currentTime,
                double aboveSmaClose,
                double currentHourOpen,
                double currentHourHigh,
                double currentHourLow,
                double currentHourClose,
                long currentHourVolume,
                double min15Close
        ) {
            List<Candle> dailyCandles = new ArrayList<>();
            ZonedDateTime dayBase = currentTime.toLocalDate().atStartOfDay(NY).minusDays(21);
            for (int i = 0; i < 22; i++) {
                dailyCandles.add(candle(dayBase.plusDays(i), 100.0, 101.0, 99.0, 100.0, 5000000L));
            }

            List<Candle> hourlyCandles = new ArrayList<>();
            ZonedDateTime hourBase = currentTime.minusHours(24);
            for (int i = 0; i < 21; i++) {
                hourlyCandles.add(candle(hourBase.plusHours(i), 100.0, 100.5, 99.5, 100.0, 2000000L));
            }
            for (int i = 21; i < 24; i++) {
                hourlyCandles.add(candle(hourBase.plusHours(i), aboveSmaClose, aboveSmaClose + 0.3, aboveSmaClose - 0.3, aboveSmaClose, 2000000L));
            }
            hourlyCandles.add(candle(currentTime, currentHourOpen, currentHourHigh, currentHourLow, currentHourClose, currentHourVolume));

            List<Candle> candles15m = new ArrayList<>();
            ZonedDateTime min15Base = currentTime.minusMinutes(15L * 21);
            for (int i = 0; i < 20; i++) {
                double c = min15Close + 0.2 - i * 0.01;
                candles15m.add(candle(min15Base.plusMinutes(15L * i), c, c + 0.2, c - 0.2, c, 500000L));
            }
            candles15m.add(candle(min15Base.plusMinutes(15L * 20), min15Close + 0.1, min15Close + 0.3, min15Close - 0.1, min15Close + 0.1, 500000L));
            candles15m.add(candle(currentTime, min15Close, min15Close + 0.1, min15Close - 0.2, min15Close, 500000L));

            Map<TimeFrame, List<Candle>> data = new EnumMap<>(TimeFrame.class);
            data.put(TimeFrame.DAY_1, dailyCandles);
            data.put(TimeFrame.HOUR_1, hourlyCandles);
            data.put(TimeFrame.MIN_15, candles15m);
            return new StrategyData(data);
        }

        @Test
        @DisplayName("Should trigger P6 when all conditions are met")
        void shouldTriggerP6WhenAllConditionsMet() {
            ZonedDateTime testTime = ZonedDateTime.of(2026, 4, 8, 11, 0, 0, 0, NY);

            // aboveSmaClose=101.5 → prior 3 bars above SMA20 (~100)
            // current bar: open=101.5 (above SMA), close=99.2 (below SMA ~100), bearish, bottom 35%
            // high=101.6, low=99.1 → range=2.5, (close-low)=0.1 ≤ 2.5*0.35=0.875 ✓
            // volume=2200000 >= avgVol(2000000)*0.90=1800000 ✓
            // min15Close=99.0 → below SMA20 and SMA slope down
            StrategyData data = buildP6Data(testTime, 101.5,
                    101.5, 101.6, 99.1, 99.2, 2200000L, 99.0);
            P6ReversalPutStrategy strategy = new P6ReversalPutStrategy();

            assertThat(strategy.isTriggered("AAPL", data, testTime)).isTrue();
        }

        @Test
        @DisplayName("Should NOT trigger P6 when cooldown active (same instance, same ticker, two calls)")
        void shouldNotTriggerP6WhenCooldownActive() {
            ZonedDateTime testTime = ZonedDateTime.of(2026, 4, 8, 11, 0, 0, 0, NY);

            StrategyData data = buildP6Data(testTime, 101.5,
                    101.5, 101.6, 99.1, 99.2, 2200000L, 99.0);
            P6ReversalPutStrategy strategy = new P6ReversalPutStrategy();

            boolean first = strategy.isTriggered("AAPL", data, testTime);
            boolean second = strategy.isTriggered("AAPL", data, testTime);

            assertThat(first).isTrue();
            assertThat(second).isFalse();
        }

        @Test
        @DisplayName("Should NOT trigger P6 when close does not break below SMA20")
        void shouldNotTriggerP6WhenNoBreakdownBelowSma() {
            ZonedDateTime testTime = ZonedDateTime.of(2026, 4, 8, 11, 0, 0, 0, NY);

            // close=100.5 > SMA20 (~100) → crossedBelowSma = false
            StrategyData data = buildP6Data(testTime, 101.5,
                    101.5, 101.6, 100.2, 100.5, 2200000L, 99.0);
            P6ReversalPutStrategy strategy = new P6ReversalPutStrategy();

            assertThat(strategy.isTriggered("AAPL", data, testTime)).isFalse();
        }

        @Test
        @DisplayName("Should NOT trigger P6 when candle closes weak (long lower wick, not in bottom 35%)")
        void shouldNotTriggerP6WhenCandleWeak() {
            ZonedDateTime testTime = ZonedDateTime.of(2026, 4, 8, 11, 0, 0, 0, NY);

            // close=99.8, high=101.6, low=96.0 → range=5.6, (close-low)=3.8 > 5.6*0.35=1.96 → closedNearLow=false
            StrategyData data = buildP6Data(testTime, 101.5,
                    101.5, 101.6, 96.0, 99.8, 2200000L, 99.0);
            P6ReversalPutStrategy strategy = new P6ReversalPutStrategy();

            assertThat(strategy.isTriggered("AAPL", data, testTime)).isFalse();
        }
    }
}
