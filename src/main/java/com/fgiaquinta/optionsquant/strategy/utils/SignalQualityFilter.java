package com.fgiaquinta.optionsquant.strategy.utils;

import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.ATRIndicator;
import org.ta4j.core.indicators.SMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.helpers.TRIndicator;
import org.ta4j.core.indicators.helpers.VolumeIndicator;

/**
 * Generic false signal detection filters.
 *
 * These checks identify common patterns that indicate a signal is likely false:
 * 1. Low volume breakouts (no conviction behind the move)
 * 2. Long wick rejections (price was rejected at the breakout level)
 * 3. Inside bar setups (consolidation, not breakout)
 * 4. Choppy prior price action (no clear directional bias)
 * 5. Contracting volatility (ATR declining, suggesting false breakout)
 *
 * Usage: Call these from strategy isTriggered() methods AFTER the core logic
 * to filter out low-quality signals.
 */
public class SignalQualityFilter {

    private SignalQualityFilter() {}

    /**
     * Checks if the signal candle has sufficient volume conviction.
     *
     * @param series The bar series
     * @param currentIndex Current bar index (the signal candle)
     * @param minAvgPeriods Number of periods for volume average (default 10)
     * @param minVolumeRatio Minimum volume ratio vs average (default 0.8)
     * @return true if volume is sufficient, false if it's a low-volume false signal
     */
    public static boolean hasVolumeConviction(BarSeries series, int currentIndex, int minAvgPeriods, double minVolumeRatio) {
        if (currentIndex < minAvgPeriods) return true; // Not enough data, allow by default

        VolumeIndicator vol = new VolumeIndicator(series);
        SMAIndicator avgVol = new SMAIndicator(vol, minAvgPeriods);

        double signalVol = vol.getValue(currentIndex).doubleValue();
        double avgVolValue = avgVol.getValue(currentIndex).doubleValue();

        if (avgVolValue <= 0) return true; // No volume data, allow

        double volumeRatio = signalVol / avgVolValue;
        return volumeRatio >= minVolumeRatio;
    }

    /**
     * Checks if the signal candle has a meaningful body (not just wicks).
     *
     * A candle where the body is < 30% of the total range indicates
     * indecision/rejection, not conviction.
     *
     * @param series The bar series
     * @param currentIndex Current bar index (the signal candle)
     * @param minBodyRatio Minimum body-to-range ratio (default 0.3 = 30%)
     * @return true if candle body is substantial
     */
    public static boolean hasSubstantialBody(BarSeries series, int currentIndex, double minBodyRatio) {
        Bar bar = series.getBar(currentIndex);
        double high = bar.getHighPrice().doubleValue();
        double low = bar.getLowPrice().doubleValue();
        double open = bar.getOpenPrice().doubleValue();
        double close = bar.getClosePrice().doubleValue();

        double totalRange = high - low;
        if (totalRange <= 0) return true; // Doji or no movement, allow

        double bodySize = Math.abs(close - open);
        double bodyRatio = bodySize / totalRange;

        return bodyRatio >= minBodyRatio;
    }

    /**
     * Checks if the signal candle closes in the direction of the signal.
     *
     * For CALL signals: candle should close in the top 50% of its range
     * For PUT signals: candle should close in the bottom 50% of its range
     *
     * @param series The bar series
     * @param currentIndex Current bar index (the signal candle)
     * @param isCall true for CALL signals, false for PUT signals
     * @return true if candle closes in signal direction
     */
    public static boolean closesInSignalDirection(BarSeries series, int currentIndex, boolean isCall) {
        Bar bar = series.getBar(currentIndex);
        double high = bar.getHighPrice().doubleValue();
        double low = bar.getLowPrice().doubleValue();
        double close = bar.getClosePrice().doubleValue();

        double totalRange = high - low;
        if (totalRange <= 0) return true; // No range, allow

        double closePosition = (close - low) / totalRange; // 0 = at low, 1 = at high

        if (isCall) {
            return closePosition >= 0.5; // Close in top half
        } else {
            return closePosition <= 0.5; // Close in bottom half
        }
    }

    /**
     * Checks if the prior candles show a clear directional bias.
     *
     * If the last N candles before the signal are alternating directions
     * (red-green-red-green), it indicates choppy/noisy price action and
     * the breakout is more likely to be false.
     *
     * @param series The bar series
     * @param currentIndex Current bar index (the signal candle)
     * @param lookbackBars Number of prior candles to check (default 3)
     * @param maxAlternations Maximum allowed direction changes (default 1)
     * @return true if prior candles show some directional consistency
     */
    public static boolean hasDirectionalConsistency(BarSeries series, int currentIndex, int lookbackBars, int maxAlternations) {
        if (currentIndex < lookbackBars) return true; // Not enough data, allow

        int alternations = 0;
        boolean prevWasUp = series.getBar(currentIndex - lookbackBars).getClosePrice().doubleValue()
                >= series.getBar(currentIndex - lookbackBars).getOpenPrice().doubleValue();

        for (int i = currentIndex - lookbackBars + 1; i < currentIndex; i++) {
            boolean currentIsUp = series.getBar(i).getClosePrice().doubleValue()
                    >= series.getBar(i).getOpenPrice().doubleValue();
            if (currentIsUp != prevWasUp) {
                alternations++;
            }
            prevWasUp = currentIsUp;
        }

        return alternations <= maxAlternations;
    }

    /**
     * Checks if volatility is expanding (ATR increasing), confirming the breakout.
     *
     * If ATR is contracting or flat, the "breakout" is more likely to be
     * a false signal within a consolidation range.
     *
     * @param series The bar series
     * @param currentIndex Current bar index (the signal candle)
     * @param atrPeriod ATR period (default 14)
     * @param minAtrGrowth Minimum ATR growth ratio (current ATR / prior ATR, default 0.95)
     * @return true if volatility is not contracting significantly
     */
    public static boolean isVolatilityExpanding(BarSeries series, int currentIndex, int atrPeriod, double minAtrGrowth) {
        if (currentIndex < atrPeriod + 5) return true; // Not enough data, allow

        TRIndicator tr = new TRIndicator(series);
        ATRIndicator atr = new ATRIndicator(tr, atrPeriod);

        double currentAtr = atr.getValue(currentIndex).doubleValue();
        double priorAtr = atr.getValue(currentIndex - 3).doubleValue(); // 3 bars ago

        if (priorAtr <= 0) return true; // No ATR data, allow

        double atrGrowth = currentAtr / priorAtr;
        return atrGrowth >= minAtrGrowth;
    }

    // ===== Convenience methods with defaults =====

    /**
     * Runs all basic quality checks with default thresholds.
     * Returns true only if ALL checks pass.
     *
     * @param series The bar series
     * @param currentIndex Current bar index
     * @param isCall true for CALL, false for PUT
     * @return true if signal passes all quality filters
     */
    public static boolean passesAllChecks(BarSeries series, int currentIndex, boolean isCall) {
        return hasVolumeConviction(series, currentIndex, 10, 0.8)
                && hasSubstantialBody(series, currentIndex, 0.3)
                && closesInSignalDirection(series, currentIndex, isCall)
                && hasDirectionalConsistency(series, currentIndex, 3, 2)
                && isVolatilityExpanding(series, currentIndex, 14, 0.9);
    }

    /**
     * Runs only the most impactful checks: volume, body, and direction.
     * Less strict than passesAllChecks, good for strategies that already
     * have strong entry logic.
     */
    public static boolean passesCoreChecks(BarSeries series, int currentIndex, boolean isCall) {
        return hasVolumeConviction(series, currentIndex, 10, 0.7)
                && hasSubstantialBody(series, currentIndex, 0.25)
                && closesInSignalDirection(series, currentIndex, isCall);
    }

    /**
     * Returns a human-readable quality report for debugging.
     */
    public static String getQualityReport(BarSeries series, int currentIndex, boolean isCall) {
        Bar bar = series.getBar(currentIndex);
        double high = bar.getHighPrice().doubleValue();
        double low = bar.getLowPrice().doubleValue();
        double open = bar.getOpenPrice().doubleValue();
        double close = bar.getClosePrice().doubleValue();
        double totalRange = high - low;
        double bodySize = Math.abs(close - open);
        double bodyRatio = totalRange > 0 ? bodySize / totalRange : 0;

        VolumeIndicator vol = new VolumeIndicator(series);
        SMAIndicator avgVol = new SMAIndicator(vol, 10);
        double signalVol = vol.getValue(currentIndex).doubleValue();
        double avgVolValue = avgVol.getValue(currentIndex).doubleValue();
        double volRatio = avgVolValue > 0 ? signalVol / avgVolValue : 0;

        String direction = isCall ? "CALL" : "PUT";
        double closePos = totalRange > 0 ? (close - low) / totalRange : 0.5;
        boolean closesCorrectly = isCall ? closePos >= 0.5 : closePos <= 0.5;

        return String.format(
                "[%s] Body: %.0f%% of range | Vol: %.1fx avg | Close: %.0f%% of range (%s) | %s",
                direction,
                bodyRatio * 100,
                volRatio,
                closePos * 100,
                closesCorrectly ? "OK" : "FAIL",
                passesCoreChecks(series, currentIndex, isCall) ? "PASS" : "FAIL"
        );
    }
}
