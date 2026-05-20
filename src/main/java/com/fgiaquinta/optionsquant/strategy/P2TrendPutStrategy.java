package com.fgiaquinta.optionsquant.strategy;

import com.fgiaquinta.optionsquant.domain.TimeFrame;
import com.fgiaquinta.optionsquant.strategy.data.StrategyData;
import com.fgiaquinta.optionsquant.strategy.utils.BollingerBandsUtil;
import lombok.extern.slf4j.Slf4j;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.SMAIndicator;
import org.ta4j.core.indicators.helpers.*;

import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class P2TrendPutStrategy implements TradingStrategy, TimeframeRequirements {
    private final Map<String, ZonedDateTime> lastTriggerMap = new ConcurrentHashMap<>();

    @Override
    public Set<TimeFrame> requiredTimeframes() {
        return Set.of(TimeFrame.MIN_15, TimeFrame.HOUR_1, TimeFrame.DAY_1);
    }

    /**
     * Evaluates the P2 Trend Put strategy against the provided market data at {@code currentTime}.
     *
     * <p>The evaluation proceeds through four sequential book steps (short-circuit on first failure):
     * <ol>
     *   <li>Tendencia bajista establecida — 1D downtrend + 1H price below SMA20 for last 3 bars</li>
     *   <li>Pullback rechazado en SMA20 — high touches SMA20 (&ge;99.5%) and close stays below</li>
     *   <li>Vela bajista con presión vendedora — bearish candle, close in bottom 35%, volume &ge;90%</li>
     *   <li>Tendencia bajista total en 15m — SMA20 downtrend AND Bollinger Bands bearish context</li>
     * </ol>
     *
     * <p>When DEBUG logging is enabled, each step emits a structured log line:
     * {@code [P2] <ticker> @ <time> — Paso X/4 "<libro description>" → <value> ✅|❌ STOP}
     *
     * @param ticker      the instrument symbol being evaluated
     * @param data        multi-timeframe market data container
     * @param currentTime the virtual or wall-clock time of evaluation
     * @return {@code true} if all four steps are satisfied; {@code false} on the first failure
     */
    @Override
    public boolean isTriggered(String ticker, StrategyData data, ZonedDateTime currentTime) {

        ZonedDateTime nyTime = currentTime.withZoneSameInstant(ZoneId.of("America/New_York"));
        if (nyTime.getHour() == 9) return false;

        ZonedDateTime lastTrigger = lastTriggerMap.get(ticker);
        if (lastTrigger != null && Duration.between(lastTrigger, currentTime).toHours() < 2) return false;

        final BarSeries series1D = data.getSeries(TimeFrame.DAY_1);
        final BarSeries series1h = data.getSeries(TimeFrame.HOUR_1);
        final BarSeries series15m = data.getSeries(TimeFrame.MIN_15);

        if (series1D == null || series1h == null || series15m == null ||
                series1D.isEmpty() || series1h.isEmpty() || series15m.isEmpty()) {
            return false;
        }

        final int idx1D = data.getIndexForTime(series1D, currentTime);
        final int idx1h = data.getIndexForTime(series1h, currentTime);
        final int idx15m = data.getIndexForTime(series15m, currentTime);

        if (idx1D < 20 || idx1h < 20 || idx15m < 20) return false;

        // ---- Pre-compute all values needed by conditions ----

        ClosePriceIndicator close1D = new ClosePriceIndicator(series1D);
        final double dailyClose1    = close1D.getValue(idx1D - 1).doubleValue();
        final double dailyClose2    = close1D.getValue(idx1D - 2).doubleValue();
        final boolean isDailyDowntrend = dailyClose1 < dailyClose2;

        ClosePriceIndicator close1h = new ClosePriceIndicator(series1h);
        OpenPriceIndicator open1h   = new OpenPriceIndicator(series1h);
        HighPriceIndicator high1h   = new HighPriceIndicator(series1h);
        LowPriceIndicator low1h     = new LowPriceIndicator(series1h);
        SMAIndicator sma20_1h       = new SMAIndicator(close1h, 20);

        // Last 3 bars below SMA20
        boolean wasBelowSma = true;
        int barsAboveSma = 0;
        for (int i = 1; i <= 3; i++) {
            if (close1h.getValue(idx1h - i).doubleValue() >= sma20_1h.getValue(idx1h - i).doubleValue()) {
                wasBelowSma = false;
                barsAboveSma++;
            }
        }
        final boolean capturedWasBelowSma = wasBelowSma;
        final int capturedBarsAboveSma    = barsAboveSma;

        final double currentClose1h = close1h.getValue(idx1h).doubleValue();
        final double currentOpen1h  = open1h.getValue(idx1h).doubleValue();
        final double currentHigh1h  = high1h.getValue(idx1h).doubleValue();
        final double currentLow1h   = low1h.getValue(idx1h).doubleValue();
        final double currentSma1h   = sma20_1h.getValue(idx1h).doubleValue();

        final boolean touchedResistance = currentHigh1h >= (currentSma1h * 0.995);
        final boolean rejectedResistance = currentClose1h < currentSma1h;

        final boolean isBearishCandle = currentClose1h < currentOpen1h;
        final double candleRange      = currentHigh1h - currentLow1h;
        final boolean closedNearLow   = candleRange > 0 && (currentClose1h - currentLow1h) <= (candleRange * 0.35);

        VolumeIndicator vol1h = new VolumeIndicator(series1h);
        SMAIndicator avgVol1h = new SMAIndicator(vol1h, 10);
        final double currentVol = vol1h.getValue(idx1h).doubleValue();
        final double avgVol     = avgVol1h.getValue(idx1h).doubleValue();
        final boolean hasVolume = currentVol >= (avgVol * 0.90);

        ClosePriceIndicator close15m = new ClosePriceIndicator(series15m);
        SMAIndicator sma20_15m       = new SMAIndicator(close15m, 20);
        final double currentPrice15m = close15m.getValue(idx15m).doubleValue();
        final double currentSma15m   = sma20_15m.getValue(idx15m).doubleValue();
        final double prevSma15m      = sma20_15m.getValue(idx15m - 1).doubleValue();
        final boolean isDowntrend15m = (currentPrice15m < currentSma15m) && (currentSma15m < prevSma15m);

        BollingerBandsUtil bb15m    = new BollingerBandsUtil(series15m, 20);
        final boolean isBearishBBTrend = bb15m.isBearishTrend(idx15m, 10);

        // ---- Four-step condition array (mapped to reference book) ----

        final List<Condition> conditions = List.of(
            new Condition() {
                public boolean test() { return isDailyDowntrend && capturedWasBelowSma; }
                public String label()  { return "Tendencia bajista establecida (1D + 1H bajo SMA20)"; }
                public String value()  {
                    return String.format("1D close %.4f<%.4f=%b; 1H bars-above-sma20=%d/3",
                            dailyClose1, dailyClose2, isDailyDowntrend, capturedBarsAboveSma);
                }
            },
            new Condition() {
                public boolean test() { return touchedResistance && rejectedResistance; }
                public String label()  { return "Pullback rechazado en SMA20 (toca y cierra por debajo)"; }
                public String value()  {
                    return String.format("high %.4f >= SMA20*0.995 %.4f=%b; close %.4f < SMA20 %.4f=%b",
                            currentHigh1h, currentSma1h * 0.995, touchedResistance,
                            currentClose1h, currentSma1h, rejectedResistance);
                }
            },
            new Condition() {
                public boolean test() { return isBearishCandle && closedNearLow && hasVolume; }
                public String label()  { return "Vela bajista con presión vendedora (cuerpo + mecha + volumen)"; }
                public String value()  {
                    return String.format("bearish=%b; closedNearLow=%b (%.1f%%); vol %.0f>=90%%avg %.0f=%b",
                            isBearishCandle, closedNearLow,
                            candleRange > 0 ? ((currentClose1h - currentLow1h) / candleRange * 100) : 0,
                            currentVol, avgVol * 0.90, hasVolume);
                }
            },
            new Condition() {
                public boolean test() { return isDowntrend15m && isBearishBBTrend; }
                public String label()  { return "Tendencia bajista total en 15m (SMA + Bollinger)"; }
                public String value()  {
                    return String.format("15m price %.4f<SMA %.4f && SMA<prevSMA %.4f=%b; BB bearish=%b",
                            currentPrice15m, currentSma15m, prevSma15m, isDowntrend15m, isBearishBBTrend);
                }
            }
        );

        final int total = conditions.size();
        for (int step = 0; step < total; step++) {
            final Condition c = conditions.get(step);
            if (log.isDebugEnabled()) {
                final String stepPrefix = String.format("[P2] %s @ %s — Paso %d/%d \"%s\" → %s",
                        ticker, currentTime.toLocalTime(), step + 1, total, c.label(), c.value());
                if (!c.test()) {
                    log.debug("{} ❌ STOP", stepPrefix);
                    return false;
                }
                log.debug("{} ✅", stepPrefix);
            } else if (!c.test()) {
                return false;
            }
        }

        lastTriggerMap.put(ticker, currentTime);
        return true;
    }
}
