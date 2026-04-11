package com.fgiaquinta.optionsquant.strategy.utils;

import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.SMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.helpers.VolumeIndicator;
import org.ta4j.core.indicators.helpers.HighPriceIndicator;
import org.ta4j.core.indicators.helpers.LowPriceIndicator;
import org.ta4j.core.num.Num;

/**
 * Confirmation filters for strategy entries.
 * Adds volume and trend strength validation to reduce false signals.
 */
public class ConfirmationFilters {

    /**
     * Checks if volume is above average by the specified multiplier.
     * 
     * @param series BarSeries with volume data
     * @param index Current candle index
     * @param minVolumeMultiplier Minimum volume vs average (e.g., 1.2 = 20% above average)
     * @param avgPeriod Period for volume average (default 20)
     * @return true if volume confirmation passes
     */
    public static boolean hasVolumeConfirmation(BarSeries series, int index, double minVolumeMultiplier, int avgPeriod) {
        if (series == null || series.isEmpty() || index < avgPeriod) return true; // Skip if not enough data
        
        VolumeIndicator volume = new VolumeIndicator(series);
        SMAIndicator avgVolume = new SMAIndicator(volume, avgPeriod);
        
        double currentVolume = volume.getValue(index).doubleValue();
        double averageVolume = avgVolume.getValue(index).doubleValue();
        
        if (averageVolume == 0) return true; // Skip if no volume data
        
        return currentVolume >= (averageVolume * minVolumeMultiplier);
    }

    /**
     * Checks if volume is at least 1.2x the 20-period average.
     */
    public static boolean hasVolumeConfirmation(BarSeries series, int index) {
        return hasVolumeConfirmation(series, index, 1.2, 20);
    }

    /**
     * Calculates a simplified trend strength indicator based on price momentum.
     * Uses the difference between fast and slow SMA as a proxy for trend strength.
     * 
     * @param series BarSeries
     * @param index Current candle index
     * @param minStrength Minimum strength threshold (percentage difference)
     * @return true if trend strength confirmation passes
     */
    public static boolean hasTrendStrength(BarSeries series, int index, double minStrength) {
        if (series == null || series.isEmpty() || index < 50) return true; // Skip if not enough data
        
        ClosePriceIndicator close = new ClosePriceIndicator(series);
        SMAIndicator sma20 = new SMAIndicator(close, 20);
        SMAIndicator sma50 = new SMAIndicator(close, 50);
        
        double sma20Value = sma20.getValue(index).doubleValue();
        double sma50Value = sma50.getValue(index).doubleValue();
        
        if (sma50Value == 0) return true;
        
        // Calculate percentage difference between SMAs
        double strength = Math.abs(sma20Value - sma50Value) / sma50Value * 100.0;
        
        return strength >= minStrength;
    }

    /**
     * Checks if trend strength >= 0.5% (SMAs diverged enough).
     */
    public static boolean hasTrendStrength(BarSeries series, int index) {
        return hasTrendStrength(series, index, 0.5);
    }

    /**
     * Checks if price is above the 50-period SMA (bullish trend).
     */
    public static boolean isAboveSMA50(BarSeries series, int index) {
        if (series == null || series.isEmpty() || index < 50) return true;
        
        ClosePriceIndicator close = new ClosePriceIndicator(series);
        SMAIndicator sma50 = new SMAIndicator(close, 50);
        
        double currentPrice = close.getValue(index).doubleValue();
        double sma50Value = sma50.getValue(index).doubleValue();
        
        return currentPrice > sma50Value;
    }

    /**
     * Checks if price is below the 50-period SMA (bearish trend).
     */
    public static boolean isBelowSMA50(BarSeries series, int index) {
        if (series == null || series.isEmpty() || index < 50) return true;
        
        ClosePriceIndicator close = new ClosePriceIndicator(series);
        SMAIndicator sma50 = new SMAIndicator(close, 50);
        
        double currentPrice = close.getValue(index).doubleValue();
        double sma50Value = sma50.getValue(index).doubleValue();
        
        return currentPrice < sma50Value;
    }

    /**
     * Checks if current candle is bullish (close > open).
     */
    public static boolean isBullishCandle(BarSeries series, int index) {
        if (series == null || series.isEmpty()) return false;
        
        double open = series.getBar(index).getOpenPrice().doubleValue();
        double close = series.getBar(index).getClosePrice().doubleValue();
        
        return close > open;
    }

    /**
     * Checks if current candle is bearish (close < open).
     */
    public static boolean isBearishCandle(BarSeries series, int index) {
        if (series == null || series.isEmpty()) return false;
        
        double open = series.getBar(index).getOpenPrice().doubleValue();
        double close = series.getBar(index).getClosePrice().doubleValue();
        
        return close < open;
    }
}

