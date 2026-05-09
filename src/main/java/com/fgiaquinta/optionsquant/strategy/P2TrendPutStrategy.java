package com.fgiaquinta.optionsquant.strategy;

import com.fgiaquinta.optionsquant.domain.TimeFrame;
import com.fgiaquinta.optionsquant.strategy.data.StrategyData;
import com.fgiaquinta.optionsquant.strategy.utils.BollingerBandsUtil;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.SMAIndicator;
import org.ta4j.core.indicators.helpers.*;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class P2TrendPutStrategy implements TradingStrategy, TimeframeRequirements {
    private final Map<String, ZonedDateTime> lastTriggerMap = new HashMap<>();

    @Override
    public Set<TimeFrame> requiredTimeframes() {
        return Set.of(TimeFrame.MIN_15, TimeFrame.HOUR_1, TimeFrame.DAY_1);
    }

    @Override
    public boolean isTriggered(String ticker, StrategyData data, ZonedDateTime currentTime) {

        ZonedDateTime nyTime = currentTime.withZoneSameInstant(java.time.ZoneId.of("America/New_York"));
        if (nyTime.getHour() == 9) return false;

        ZonedDateTime lastTrigger = lastTriggerMap.get(ticker);
        if (lastTrigger != null && Duration.between(lastTrigger, currentTime).toHours() < 2) return false;

        BarSeries series1D = data.getSeries(TimeFrame.DAY_1);
        BarSeries series1h = data.getSeries(TimeFrame.HOUR_1);
        BarSeries series15m = data.getSeries(TimeFrame.MIN_15);

        if (series1D == null || series1h == null || series15m == null ||
                series1D.isEmpty() || series1h.isEmpty() || series15m.isEmpty()) {
            return false;
        }

        int idx1D = data.getIndexForTime(series1D, currentTime);
        int idx1h = data.getIndexForTime(series1h, currentTime);
        int idx15m = data.getIndexForTime(series15m, currentTime);

        if (idx1D < 20 || idx1h < 20 || idx15m < 20) return false;

        ClosePriceIndicator close1h = new ClosePriceIndicator(series1h);
        OpenPriceIndicator open1h = new OpenPriceIndicator(series1h);
        HighPriceIndicator high1h = new HighPriceIndicator(series1h);
        LowPriceIndicator low1h = new LowPriceIndicator(series1h);
        SMAIndicator sma20_1h = new SMAIndicator(close1h, 20);

        // =========================================================================
        // RULE 1: ESTABLISHED BEARISH TREND (1D and 1H)
        // =========================================================================
        ClosePriceIndicator close1D = new ClosePriceIndicator(series1D);
        boolean isDailyDowntrend = close1D.getValue(idx1D - 1).isLessThan(close1D.getValue(idx1D - 2));
        if (!isDailyDowntrend) return false;

        // Price must be below SMA20 on 1H for the last 3 bars (healthy downtrend)
        boolean wasBelowSma = true;
        for (int i = 1; i <= 3; i++) {
            if (close1h.getValue(idx1h - i).doubleValue() >= sma20_1h.getValue(idx1h - i).doubleValue()) {
                wasBelowSma = false;
                break;
            }
        }
        if (!wasBelowSma) return false;

        // =========================================================================
        // RULE 2: THE PULLBACK (Retracement to the Average upward)
        // =========================================================================
        double currentClose1h = close1h.getValue(idx1h).doubleValue();
        double currentOpen1h = open1h.getValue(idx1h).doubleValue();
        double currentHigh1h = high1h.getValue(idx1h).doubleValue();
        double currentLow1h = low1h.getValue(idx1h).doubleValue();
        double currentSma1h = sma20_1h.getValue(idx1h).doubleValue();

        // The high of the candle must "touch" or get very close to SMA20 from below (0.5% margin)
        // But NEVER close above the average (rejection of resistance).
        boolean touchedResistance = currentHigh1h >= (currentSma1h * 0.995);
        boolean rejectedResistance = currentClose1h < currentSma1h;

        if (!touchedResistance || !rejectedResistance) return false;

        // =========================================================================
        // RULE 3: INSTITUTIONAL SELLING STRENGTH (Candle and Volume)
        // =========================================================================
        boolean isBearishCandle = currentClose1h < currentOpen1h;

        // WICK FILTER: Closes in the bottom 35% of its range (heavy selling pressure)
        double candleRange = currentHigh1h - currentLow1h;
        boolean closedNearLow = (currentClose1h - currentLow1h) <= (candleRange * 0.35);

        if (!isBearishCandle || !closedNearLow) return false;

        // VOLUME FILTER: Continuation requires healthy volume
        VolumeIndicator vol1h = new VolumeIndicator(series1h);
        SMAIndicator avgVol1h = new SMAIndicator(vol1h, 10);
        double currentVol = vol1h.getValue(idx1h).doubleValue();
        double avgVol = avgVol1h.getValue(idx1h).doubleValue();
        if (currentVol < (avgVol * 0.90)) return false;

        // =========================================================================
        // RULE 4: 15-MINUTE CONFIRMATION (Bollinger Bands context per book)
        // Book: "Cambiar a la temporalidad 15 minutos y la tendencia debe mostrarse totalmente bajista"
        // =========================================================================
        ClosePriceIndicator close15m = new ClosePriceIndicator(series15m);
        SMAIndicator sma20_15m = new SMAIndicator(close15m, 20);

        double currentPrice15m = close15m.getValue(idx15m).doubleValue();
        double currentSma15m = sma20_15m.getValue(idx15m).doubleValue();
        double prevSma15m = sma20_15m.getValue(idx15m - 1).doubleValue();

        // 15m must be accompanying the bearish trend
        boolean isDowntrend15m = (currentPrice15m < currentSma15m) && (currentSma15m < prevSma15m);

        // Book requirement: Verify bearish trend in Bollinger Bands context
        BollingerBandsUtil bb15m = new BollingerBandsUtil(series15m, 20);
        boolean isBearishBBTrend = bb15m.isBearishTrend(idx15m, 10); // 70% of last 10 candles below middle band
        boolean priceBelowMiddleBB = currentPrice15m < bb15m.getMiddle(idx15m);

        // Signal confirmed if BOTH: SMA downtrend AND BB bearish context
        if (isDowntrend15m && isBearishBBTrend && priceBelowMiddleBB) {
            lastTriggerMap.put(ticker, currentTime);
            return true;
        }

        return false;
    }
}
