package com.fgiaquinta.optionsquant.strategy;

import com.fgiaquinta.optionsquant.domain.TimeFrame;
import com.fgiaquinta.optionsquant.strategy.data.StrategyData;
import com.fgiaquinta.optionsquant.strategy.utils.BollingerBandsUtil;
import org.ta4j.core.BarSeries;

import java.time.ZonedDateTime;
import java.time.ZoneId;
import java.util.Set;

public class P4OpeningPutStrategy implements TradingStrategy, TimeframeRequirements {

    @Override
    public Set<TimeFrame> requiredTimeframes() {
        return Set.of(TimeFrame.MIN_5, TimeFrame.MIN_15);
    }

    @Override
    public boolean isTriggered(String ticker, StrategyData data, ZonedDateTime currentTime) {
        ZonedDateTime nyTime = currentTime.withZoneSameInstant(ZoneId.of("America/New_York"));

        // =========================================================================
        // RULE 3: THE SNIPER (Only trade from 9:30 to 9:35 AM)
        // =========================================================================
        if (nyTime.getHour() != 9 || nyTime.getMinute() < 30 || nyTime.getMinute() > 35) {
            return false;
        }

        BarSeries series15m = data.getSeries(TimeFrame.MIN_15);
        BarSeries series5m = data.getSeries(TimeFrame.MIN_5);

        if (series15m == null || series5m == null || series15m.isEmpty() || series5m.isEmpty()) return false;

        int idx15m = data.getIndexForTime(series15m, currentTime);
        int idx5m = data.getIndexForTime(series5m, currentTime);
        if (idx15m < 20 || idx5m < 1) return false;

        // =========================================================================
        // RULE 1: LATERAL TREND (Previous day)
        // =========================================================================
        BollingerBandsUtil bb = new BollingerBandsUtil(series15m, 20);

        int prevIdx15m = idx15m - 1;

        // Relax the lateral channel a bit (max band width of 2%)
        if (!bb.isLateral(prevIdx15m, 2.0)) return false;

        // =========================================================================
        // RULE 2: THE GOLDEN GAP ZONE (GAP UP)
        // =========================================================================
        double openToday = series5m.getBar(idx5m).getOpenPrice().doubleValue();
        double closeYesterday = series5m.getBar(idx5m - 1).getClosePrice().doubleValue();

        // Did it open above yesterday's Upper Bollinger Band?
        boolean isExtremeGapUp = openToday > bb.getUpper(prevIdx15m);
        if (!isExtremeGapUp) return false;

        // GAP RANGE FILTER: Between +1.5% and +6.0% (Avoid OPA/acquisition spikes)
        double gapPct = (openToday - closeYesterday) / closeYesterday;
        if (gapPct < 0.015 || gapPct > 0.06) return false;

        // =========================================================================
        // RULE 3: SIMPLE CONFIRMATION (Red Candle)
        // =========================================================================
        double currentClose = series5m.getBar(idx5m).getClosePrice().doubleValue();

        // No wick filters. If it closes red, the trap caught the rookies and we sell.
        boolean isRedCandle = currentClose < openToday;

        return isRedCandle;
    }
}
