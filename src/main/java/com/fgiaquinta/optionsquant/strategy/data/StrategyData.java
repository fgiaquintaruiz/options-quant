package com.fgiaquinta.optionsquant.strategy.data;

import com.fgiaquinta.optionsquant.domain.Candle;
import com.fgiaquinta.optionsquant.domain.TimeFrame;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeriesBuilder;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
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

    public int getIndexForTime(BarSeries series, ZonedDateTime time) {
        if (series == null || series.isEmpty()) return -1;
        int endIdx = series.getEndIndex();
        for (int i = endIdx; i >= Math.max(0, endIdx - 500); i--) {
            if (!series.getBar(i).getEndTime().isAfter(time)) {
                return i;
            }
        }
        return -1;
    }
}
