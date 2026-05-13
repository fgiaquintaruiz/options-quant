package com.fgiaquinta.optionsquant.strategy.data;

import com.fgiaquinta.optionsquant.domain.Candle;
import com.fgiaquinta.optionsquant.domain.TimeFrame;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeriesBuilder;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Wrapper that converts our Candle domain objects to ta4j BarSeries
 * and provides easy access for strategies.
 */
public class StrategyData {
    private final Map<TimeFrame, List<Candle>> candlesByTimeframe;
    private final Map<TimeFrame, BarSeries> builtSeries = new ConcurrentHashMap<>();

    public StrategyData(Map<TimeFrame, List<Candle>> candlesByTimeframe) {
        this.candlesByTimeframe = candlesByTimeframe;
        // Pre-build all series
        for (TimeFrame tf : TimeFrame.values()) {
            List<Candle> candles = candlesByTimeframe.get(tf);
            if (candles != null && !candles.isEmpty()) {
                buildSeries(tf, candles);
            }
        }
    }

    private void buildSeries(TimeFrame tf, List<Candle> candles) {
        BarSeries series = new BaseBarSeriesBuilder().withName(tf.name()).build();
        for (Candle c : candles) {
            series.addBar(c.timestamp(), c.open(), c.high(), c.low(), c.close(), c.volume());
        }
        builtSeries.put(tf, series);
    }

    public BarSeries getSeries(TimeFrame tf) {
        return builtSeries.get(tf);
    }

    public List<Candle> getCandles(TimeFrame tf) {
        return candlesByTimeframe.get(tf);
    }

    public boolean hasAllTimeframes() {
        return builtSeries.containsKey(TimeFrame.MIN_5)
                && builtSeries.containsKey(TimeFrame.MIN_15)
                && builtSeries.containsKey(TimeFrame.HOUR_1)
                && builtSeries.containsKey(TimeFrame.DAY_1);
    }

    /**
     * True iff every requested timeframe has a non-empty candle list loaded.
     *
     * <p>Used by the scanner to gate per-strategy execution: a strategy whose
     * {@link com.fgiaquinta.optionsquant.strategy.TimeframeRequirements#requiredTimeframes()}
     * are all available is allowed to run; missing ones cause the strategy
     * to be skipped (but other strategies can still run on the same ticker).
     */
    public boolean hasAvailableTimeframes(Set<TimeFrame> required) {
        if (required == null || required.isEmpty()) return true;
        for (TimeFrame tf : required) {
            List<Candle> candles = candlesByTimeframe.get(tf);
            if (candles == null || candles.isEmpty()) return false;
        }
        return true;
    }

    public int getIndexForTime(BarSeries series, ZonedDateTime time) {
        if (series == null || series.isEmpty()) return -1;
        int left = 0, right = series.getEndIndex(), result = -1;
        while (left <= right) {
            int mid = (left + right) / 2;
            if (!series.getBar(mid).getEndTime().isAfter(time)) {
                result = mid;
                left = mid + 1;
            } else {
                right = mid - 1;
            }
        }
        return result;
    }
}
