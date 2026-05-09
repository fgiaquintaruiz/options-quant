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
import java.util.Set;

/**
 * P5 - EFECTO IMÁN (Magnet Effect) - PUT version
 *
 * Bullish trend + gap up + Bollinger breakout above + Worden Stochastic confirmation.
 *
 * REQUIREMENTS (from the course author's book):
 * 1. Clearly bullish trend (several days rising)
 * 2. Price opens with a strong gap up, far from 20-period MA on 1H (at least 3% above)
 * 3. On 15m Bollinger Bands, the first candle must be completely outside (above) the oscillator
 * 4. When the new candle starts forming, it must cross the red line of Worden Stochastics (sell confirmation)
 *
 * Time window: 9:45 AM - 9:55 AM NY (after the first 15m candle closes)
 */
public class P5ContinuationPutStrategy implements TradingStrategy, TimeframeRequirements {

    @Override
    public Set<TimeFrame> requiredTimeframes() {
        return Set.of(TimeFrame.MIN_15, TimeFrame.HOUR_1, TimeFrame.DAY_1);
    }

    private final WordenStochasticIndicator wordenStochastic;

    /**
     * Constructor with WordenStochasticIndicator for confirmation.
     */
    public P5ContinuationPutStrategy(WordenStochasticIndicator wordenStochastic) {
        this.wordenStochastic = wordenStochastic;
    }

    /**
     * Default constructor using volume surge as fallback (legacy behavior).
     */
    public P5ContinuationPutStrategy() {
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
        // RULE 1: CLEARLY BULLISH TREND (Multiple days rising)
        // =========================================================================
        ClosePriceIndicator close1D = new ClosePriceIndicator(series1D);
        double prevClose1D = close1D.getValue(idx1D - 1).doubleValue();
        double prev2Close1D = close1D.getValue(idx1D - 2).doubleValue();
        double prev3Close1D = close1D.getValue(idx1D - 3).doubleValue();

        // Confirm at least 2 consecutive green daily candles
        if (prevClose1D <= prev2Close1D || prev2Close1D <= prev3Close1D) {
            return false;
        }

        // =========================================================================
        // RULE 2: STRONG GAP UP AND FAR FROM MM20 ON 1 HOUR
        // =========================================================================
        ClosePriceIndicator close1h = new ClosePriceIndicator(series1h);
        SMAIndicator sma20_1h = new SMAIndicator(close1h, 20);
        double currentSma1h = sma20_1h.getValue(idx1h).doubleValue();

        int firstCandle15mIdx = idx15m - 1; // The 9:30 to 9:45 candle
        double first15mOpen = series15m.getBar(firstCandle15mIdx).getOpenPrice().doubleValue();

        // Must be a Gap Up relative to yesterday's close
        if (first15mOpen <= prevClose1D) return false;

        // "Very far" from the 20-period Moving Average (At least 3% above the magnet)
        if (first15mOpen < (currentSma1h * 1.03)) return false;

        // =========================================================================
        // RULE 3: FIRST 15M CANDLE COMPLETELY OUTSIDE BOLLINGER (ABOVE)
        // =========================================================================
        BollingerBandsUtil bb = new BollingerBandsUtil(series15m, 20);

        double upperBand15m = bb.getUpper(firstCandle15mIdx - 1);
        double first15mLow = series15m.getBar(firstCandle15mIdx).getLowPrice().doubleValue();

        // "Completely outside": Even the lower wick of that first candle must be above the band
        if (first15mLow <= upperBand15m) {
            return false;
        }

        // =========================================================================
        // RULE 4: CONFIRMATION - Worden Stochastic cross OR volume surge fallback
        // =========================================================================
        double currentPrice = series15m.getBar(idx15m).getClosePrice().doubleValue();
        double currentOpen = series15m.getBar(idx15m).getOpenPrice().doubleValue();

        // The new candle (9:45 - 10:00) starts red and begins sinking toward the magnet
        boolean isReversingDown = currentPrice < currentOpen;

        boolean confirmation;
        if (wordenStochastic != null) {
            // Worden Stochastic: sell when value crosses below 80 (overbought recovery)
            double currentWorden = wordenStochastic.getValue(idx15m).doubleValue();
            double prevWorden = wordenStochastic.getValue(idx15m - 1).doubleValue();
            // Cross below 80 level (the "red line") from above = sell signal
            boolean crossedBelow80 = prevWorden >= 80 && currentWorden < 80;
            confirmation = crossedBelow80;
        } else {
            // Fallback: volume surge (1.5x average) as mathematical substitute
            VolumeIndicator vol15m = new VolumeIndicator(series15m);
            SMAIndicator avgVol15m = new SMAIndicator(vol15m, 10);
            double firstCandleVol = vol15m.getValue(firstCandle15mIdx).doubleValue();
            double avgVol = avgVol15m.getValue(firstCandle15mIdx - 1).doubleValue();
            confirmation = firstCandleVol > (avgVol * 1.5);
        }

        return isReversingDown && confirmation;
    }
}
