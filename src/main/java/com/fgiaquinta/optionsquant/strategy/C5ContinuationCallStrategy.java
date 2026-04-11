package com.fgiaquinta.optionsquant.strategy;

import com.fgiaquinta.optionsquant.domain.TimeFrame;
import com.fgiaquinta.optionsquant.strategy.data.StrategyData;
import com.fgiaquinta.optionsquant.strategy.indicator.WordenStochasticIndicator;
import com.fgiaquinta.optionsquant.strategy.utils.BollingerBandsUtil;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.SMAIndicator;
import org.ta4j.core.indicators.helpers.*;

import java.time.ZonedDateTime;
import java.time.ZoneId;

/**
 * C5 - EFECTO IMÁN (Magnet Effect) - CALL version
 *
 * Bearish trend + gap down + Bollinger breakout below + Worden Stochastic confirmation.
 *
 * REQUIREMENTS (from the course author's book):
 * 1. Clearly bearish trend (several days falling)
 * 2. Price opens with a strong gap down, far from 20-period MA on 1H (at least 3% below)
 * 3. On 15m Bollinger Bands, the first candle must be completely outside (below) the oscillator
 * 4. When the new candle starts forming, it must cross the red line of Worden Stochastics (buy confirmation)
 *
 * Time window: 9:45 AM - 9:55 AM NY (after the first 15m candle closes)
 */
public class C5ContinuationCallStrategy implements TradingStrategy {

    private final WordenStochasticIndicator wordenStochastic;

    /**
     * Constructor with WordenStochasticIndicator for confirmation.
     */
    public C5ContinuationCallStrategy(WordenStochasticIndicator wordenStochastic) {
        this.wordenStochastic = wordenStochastic;
    }

    /**
     * Default constructor using volume surge as fallback (legacy behavior).
     */
    public C5ContinuationCallStrategy() {
        this.wordenStochastic = null;
    }

    @Override
    public boolean isTriggered(String ticker, StrategyData data, ZonedDateTime currentTime) {
        ZonedDateTime nyTime = currentTime.withZoneSameInstant(ZoneId.of("America/New_York"));

        // =========================================================================
        // RULE 0: THE SNIPER (Evaluate right after the 1st 15m candle)
        // Window: 9:45 AM to 9:55 AM
        // =========================================================================
        if (nyTime.getHour() != 9 || nyTime.getMinute() < 45 || nyTime.getMinute() > 55) {
            return false;
        }

        BarSeries series1D = data.getSeries(TimeFrame.DAY_1);
        BarSeries series1h = data.getSeries(TimeFrame.HOUR_1);
        BarSeries series15m = data.getSeries(TimeFrame.MIN_15);

        if (series1D == null || series1h == null || series15m == null ||
                series1D.isEmpty() || series1h.isEmpty() || series15m.isEmpty()) return false;

        int idx1D = data.getIndexForTime(series1D, currentTime);
        int idx1h = data.getIndexForTime(series1h, currentTime);
        int idx15m = data.getIndexForTime(series15m, currentTime);

        if (idx1D < 3 || idx1h < 20 || idx15m < 20) return false;

        // =========================================================================
        // RULE 1: CLEARLY BEARISH TREND (Multiple days falling)
        // =========================================================================
        ClosePriceIndicator close1D = new ClosePriceIndicator(series1D);
        double prevClose1D = close1D.getValue(idx1D - 1).doubleValue();
        double prev2Close1D = close1D.getValue(idx1D - 2).doubleValue();
        double prev3Close1D = close1D.getValue(idx1D - 3).doubleValue();

        // Confirm at least 2 consecutive red daily candles
        if (prevClose1D >= prev2Close1D || prev2Close1D >= prev3Close1D) {
            return false;
        }

        // =========================================================================
        // RULE 2: STRONG GAP DOWN AND FAR FROM MM20 ON 1 HOUR
        // =========================================================================
        ClosePriceIndicator close1h = new ClosePriceIndicator(series1h);
        SMAIndicator sma20_1h = new SMAIndicator(close1h, 20);
        double currentSma1h = sma20_1h.getValue(idx1h).doubleValue();

        int firstCandle15mIdx = idx15m - 1; // The 9:30 to 9:45 candle
        double first15mOpen = series15m.getBar(firstCandle15mIdx).getOpenPrice().doubleValue();

        // Must be a Gap Down relative to yesterday's close
        if (first15mOpen >= prevClose1D) return false;

        // "Very far" from the 20-period Moving Average (At least 3% below the magnet)
        if (first15mOpen > (currentSma1h * 0.97)) return false;

        // =========================================================================
        // RULE 3: FIRST 15M CANDLE COMPLETELY OUTSIDE BOLLINGER (BELOW)
        // =========================================================================
        BollingerBandsUtil bb = new BollingerBandsUtil(series15m, 20);

        double lowerBand15m = bb.getLower(firstCandle15mIdx - 1);
        double first15mHigh = series15m.getBar(firstCandle15mIdx).getHighPrice().doubleValue();

        // "Completely outside": Even the highest point of that first candle must be below the band
        if (first15mHigh >= lowerBand15m) {
            return false;
        }

        // =========================================================================
        // RULE 4: CONFIRMATION - Worden Stochastic cross OR volume surge fallback
        // =========================================================================
        double currentPrice = series15m.getBar(idx15m).getClosePrice().doubleValue();
        double currentOpen = series15m.getBar(idx15m).getOpenPrice().doubleValue();

        // The new candle (9:45 - 10:00) starts green and begins rising toward the magnet
        boolean isReversingUp = currentPrice > currentOpen;

        boolean confirmation;
        if (wordenStochastic != null) {
            // Worden Stochastic: buy when value crosses above 20 (oversold recovery)
            double currentWorden = wordenStochastic.getValue(idx15m).doubleValue();
            double prevWorden = wordenStochastic.getValue(idx15m - 1).doubleValue();
            // Cross above 20 level (the "red line") from below = buy signal
            boolean crossedAbove20 = prevWorden <= 20 && currentWorden > 20;
            confirmation = crossedAbove20;
        } else {
            // Fallback: volume surge (1.5x average) as mathematical substitute
            VolumeIndicator vol15m = new VolumeIndicator(series15m);
            SMAIndicator avgVol15m = new SMAIndicator(vol15m, 10);
            double firstCandleVol = vol15m.getValue(firstCandle15mIdx).doubleValue();
            double avgVol = avgVol15m.getValue(firstCandle15mIdx - 1).doubleValue();
            confirmation = firstCandleVol > (avgVol * 1.5);
        }

        return isReversingUp && confirmation;
    }
}
