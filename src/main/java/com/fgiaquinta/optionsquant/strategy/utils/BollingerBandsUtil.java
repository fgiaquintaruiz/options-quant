package com.fgiaquinta.optionsquant.strategy.utils;

import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.SMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.statistics.StandardDeviationIndicator;

/**
 * Bollinger Bands utility for strategies.
 * Provides BB calculations and trend analysis per the book requirements.
 */
public class BollingerBandsUtil {

    private final BarSeries series;
    private final ClosePriceIndicator closePrice;
    private final int period;
    private final double k; // standard deviations (default 2)

    public BollingerBandsUtil(BarSeries series, int period, double k) {
        this.series = series;
        this.closePrice = new ClosePriceIndicator(series);
        this.period = period;
        this.k = k;
    }

    public BollingerBandsUtil(BarSeries series, int period) {
        this(series, period, 2.0);
    }

    /**
     * Gets the middle band (SMA of close prices).
     */
    public double getMiddle(int index) {
        return new SMAIndicator(closePrice, period).getValue(index).doubleValue();
    }

    /**
     * Gets the upper band (SMA + k * StdDev).
     */
    public double getUpper(int index) {
        double middle = getMiddle(index);
        double stdDev = getStandardDeviation(index);
        return middle + (k * stdDev);
    }

    /**
     * Gets the lower band (SMA - k * StdDev).
     */
    public double getLower(int index) {
        double middle = getMiddle(index);
        double stdDev = getStandardDeviation(index);
        return middle - (k * stdDev);
    }

    /**
     * Gets the standard deviation at the given index.
     */
    public double getStandardDeviation(int index) {
        return new StandardDeviationIndicator(closePrice, period).getValue(index).doubleValue();
    }

    /**
     * Gets the Bollinger Band width percentage at the given index.
     * Width = (Upper - Lower) / Middle * 100
     */
    public double getWidthPercent(int index) {
        double upper = getUpper(index);
        double lower = getLower(index);
        double middle = getMiddle(index);
        return ((upper - lower) / middle) * 100.0;
    }

    /**
     * Checks if the bands are lateral (low volatility/squeeze).
     * Returns true if width < threshold percent.
     */
    public boolean isLateral(int index, double thresholdPercent) {
        return getWidthPercent(index) < thresholdPercent;
    }

    /**
     * Checks if the bands are lateral with default 2% threshold.
     */
    public boolean isLateral(int index) {
        return isLateral(index, 2.0);
    }

    /**
     * Checks if a candle is completely BELOW the lower band.
     * Both low and close must be below the lower band.
     */
    public boolean isCandleCompletelyBelowLowerBand(int index) {
        double lowerBand = getLower(index);
        double candleLow = series.getBar(index).getLowPrice().doubleValue();
        double candleClose = series.getBar(index).getClosePrice().doubleValue();
        return candleLow < lowerBand && candleClose < lowerBand;
    }

    /**
     * Checks if a candle is completely ABOVE the upper band.
     * Both high and close must be above the upper band.
     */
    public boolean isCandleCompletelyAboveUpperBand(int index) {
        double upperBand = getUpper(index);
        double candleHigh = series.getBar(index).getHighPrice().doubleValue();
        double candleClose = series.getBar(index).getClosePrice().doubleValue();
        return candleHigh > upperBand && candleClose > upperBand;
    }

    /**
     * Checks if candle is riding the upper band (close near upper band).
     * Used for C1 Squeeze Call confirmation.
     */
    public boolean isRidingUpperBand(int index, double proximityPercent) {
        double upperBand = getUpper(index);
        double candleClose = series.getBar(index).getClosePrice().doubleValue();
        return candleClose >= (upperBand * (1.0 - proximityPercent));
    }

    /**
     * Checks if candle is riding the lower band (close near lower band).
     * Used for P1 Squeeze Put confirmation.
     */
    public boolean isRidingLowerBand(int index, double proximityPercent) {
        double lowerBand = getLower(index);
        double candleClose = series.getBar(index).getClosePrice().doubleValue();
        return candleClose <= (lowerBand * (1.0 + proximityPercent));
    }

    /**
     * Checks if price broke above the upper band recently (within last N candles).
     */
    public boolean brokeAboveUpperBand(int currentIndex, int lookback) {
        for (int i = currentIndex; i >= Math.max(0, currentIndex - lookback); i--) {
            double upperBand = getUpper(i);
            double candleClose = series.getBar(i).getClosePrice().doubleValue();
            if (candleClose > upperBand) return true;
        }
        return false;
    }

    /**
     * Checks if price broke below the lower band recently (within last N candles).
     */
    public boolean brokeBelowLowerBand(int currentIndex, int lookback) {
        for (int i = currentIndex; i >= Math.max(0, currentIndex - lookback); i--) {
            double lowerBand = getLower(i);
            double candleClose = series.getBar(i).getClosePrice().doubleValue();
            if (candleClose < lowerBand) return true;
        }
        return false;
    }

    /**
     * Checks if trend is bullish in BB context.
     * Price is consistently above middle band and bands are expanding upward.
     */
    public boolean isBullishTrend(int currentIndex, int lookback) {
        int aboveMiddleCount = 0;
        for (int i = currentIndex; i >= Math.max(0, currentIndex - lookback); i--) {
            double middle = getMiddle(i);
            double close = series.getBar(i).getClosePrice().doubleValue();
            if (close > middle) aboveMiddleCount++;
        }
        // Bullish if >70% of candles are above middle band
        return (double) aboveMiddleCount / (lookback + 1) > 0.7;
    }

    /**
     * Checks if trend is bearish in BB context.
     * Price is consistently below middle band and bands are expanding downward.
     */
    public boolean isBearishTrend(int currentIndex, int lookback) {
        int belowMiddleCount = 0;
        for (int i = currentIndex; i >= Math.max(0, currentIndex - lookback); i--) {
            double middle = getMiddle(i);
            double close = series.getBar(i).getClosePrice().doubleValue();
            if (close < middle) belowMiddleCount++;
        }
        // Bearish if >70% of candles are below middle band
        return (double) belowMiddleCount / (lookback + 1) > 0.7;
    }
}
