package com.fgiaquinta.optionsquant.strategy;

import com.fgiaquinta.optionsquant.domain.TimeFrame;
import com.fgiaquinta.optionsquant.strategy.data.StrategyData;
import com.fgiaquinta.optionsquant.strategy.utils.BollingerBandsUtil;
import com.fgiaquinta.optionsquant.strategy.utils.ConditionEvaluator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.SMAIndicator;
import org.ta4j.core.indicators.helpers.*;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class C2TrendCallStrategy implements TradingStrategy, TimeframeRequirements {
    private final Map<String, ZonedDateTime> lastTriggerMap = new ConcurrentHashMap<>();

    @Override
    public Set<TimeFrame> requiredTimeframes() {
        return Set.of(TimeFrame.MIN_15, TimeFrame.HOUR_1, TimeFrame.DAY_1);
    }

    /**
     * Evaluates the C2 Trend Call strategy against the provided market data at {@code currentTime}.
     *
     * <p>The evaluation proceeds through four sequential steps (short-circuit on first failure):
     * <ol>
     *   <li>Línea de tendencia bordeando puntos de la tendencia previa — 1D uptrend + 1H price above SMA20 for last 3 bars</li>
     *   <li>El precio rompe la línea de tendencia — low touches SMA20 (within 0.5%) and close stays above</li>
     *   <li>El precio rompe la SMA20 + vela de confirmación alcista — bullish candle, close in top 35%, volume ≥ 90%</li>
     *   <li>En 15m la tendencia alcista debe estar totalmente alineada — SMA20 uptrend AND Bollinger Bands bullish context</li>
     * </ol>
     *
     * <p>When DEBUG logging is enabled, each step emits a structured log line:
     * {@code [C2] <ticker> @ <time> — Paso X/4 "<step description>" → <value> ✅|❌ STOP}
     *
     * @param ticker      the instrument symbol being evaluated
     * @param data        multi-timeframe market data container
     * @param currentTime the virtual or wall-clock time of evaluation
     * @return {@code true} if all four steps are satisfied; {@code false} on the first failure
     */
    @Override
    public boolean isCall() {
        return true;
    }

    @Override
    public boolean isTriggered(String ticker, StrategyData data, ZonedDateTime currentTime) {

        final ZonedDateTime nyTime = currentTime.withZoneSameInstant(java.time.ZoneId.of("America/New_York"));
        if (nyTime.getHour() == 9) return false;

        final ZonedDateTime lastTrigger = lastTriggerMap.get(ticker);
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

        final ClosePriceIndicator close1D = new ClosePriceIndicator(series1D);
        final double dailyClose1 = close1D.getValue(idx1D - 1).doubleValue();
        final double dailyClose2 = close1D.getValue(idx1D - 2).doubleValue();
        final boolean isDailyUptrend = dailyClose1 > dailyClose2;

        final ClosePriceIndicator close1h = new ClosePriceIndicator(series1h);
        final OpenPriceIndicator open1h   = new OpenPriceIndicator(series1h);
        final HighPriceIndicator high1h   = new HighPriceIndicator(series1h);
        final LowPriceIndicator low1h     = new LowPriceIndicator(series1h);
        final SMAIndicator sma20_1h       = new SMAIndicator(close1h, 20);

        // Last 3 bars above SMA20
        boolean wasAboveSma = true;
        int barsBelowSma = 0;
        for (int i = 1; i <= 3; i++) {
            if (close1h.getValue(idx1h - i).doubleValue() <= sma20_1h.getValue(idx1h - i).doubleValue()) {
                wasAboveSma = false;
                barsBelowSma++;
            }
        }
        final boolean capturedWasAboveSma = wasAboveSma;
        final int capturedBarsBelowSma    = barsBelowSma;

        final double currentClose1h = close1h.getValue(idx1h).doubleValue();
        final double currentOpen1h  = open1h.getValue(idx1h).doubleValue();
        final double currentHigh1h  = high1h.getValue(idx1h).doubleValue();
        final double currentLow1h   = low1h.getValue(idx1h).doubleValue();
        final double currentSma1h   = sma20_1h.getValue(idx1h).doubleValue();

        // Paso 2: low touches SMA20 (within 0.5% margin), close stays above
        final boolean touchedSupport   = currentLow1h <= (currentSma1h * 1.005);
        final boolean rejectedSupport  = currentClose1h > currentSma1h;

        // Paso 3: bullish candle body + close near high + volume
        final boolean isBullishCandle  = currentClose1h > currentOpen1h;
        final double candleRange       = currentHigh1h - currentLow1h;
        final boolean closedNearHigh   = candleRange > 0 && (currentHigh1h - currentClose1h) <= (candleRange * 0.35);

        final VolumeIndicator vol1h   = new VolumeIndicator(series1h);
        final SMAIndicator avgVol1h   = new SMAIndicator(vol1h, 10);
        final double currentVol = vol1h.getValue(idx1h).doubleValue();
        final double avgVol     = avgVol1h.getValue(idx1h).doubleValue();
        final boolean hasVolume = currentVol >= (avgVol * 0.90);

        // Paso 4: 15m fully aligned bullish
        final ClosePriceIndicator close15m = new ClosePriceIndicator(series15m);
        final SMAIndicator sma20_15m       = new SMAIndicator(close15m, 20);
        final double currentPrice15m = close15m.getValue(idx15m).doubleValue();
        final double currentSma15m   = sma20_15m.getValue(idx15m).doubleValue();
        final double prevSma15m      = sma20_15m.getValue(idx15m - 1).doubleValue();
        final boolean isUptrend15m   = (currentPrice15m > currentSma15m) && (currentSma15m > prevSma15m);

        final BollingerBandsUtil bb15m  = new BollingerBandsUtil(series15m, 20);
        final boolean isBullishBBTrend  = bb15m.isBullishTrend(idx15m, 10);

        // ---- Four-step condition array (strategy conditions) ----

        final List<Condition> conditions = List.of(
            new Condition() {
                @Override public boolean test() { return isDailyUptrend && capturedWasAboveSma; }
                @Override public String label()  { return "Línea de tendencia bordeando puntos de la tendencia previa"; }
                @Override public String value()  {
                    return String.format("1D close %.4f>%.4f=%b; 1H bars-below-sma20=%d/3",
                            dailyClose1, dailyClose2, isDailyUptrend, capturedBarsBelowSma);
                }
            },
            new Condition() {
                @Override public boolean test() { return touchedSupport && rejectedSupport; }
                @Override public String label()  { return "El precio rompe la línea de tendencia"; }
                @Override public String value()  {
                    return String.format("low %.4f <= SMA20*1.005 %.4f=%b; close %.4f > SMA20 %.4f=%b",
                            currentLow1h, currentSma1h * 1.005, touchedSupport,
                            currentClose1h, currentSma1h, rejectedSupport);
                }
            },
            new Condition() {
                @Override public boolean test() { return isBullishCandle && closedNearHigh && hasVolume; }
                @Override public String label()  { return "El precio rompe la SMA20 + vela de confirmación alcista"; }
                @Override public String value()  {
                    return String.format("bullish=%b; closedNearHigh=%b (%.1f%%); vol %.0f>=90%%avg %.0f=%b",
                            isBullishCandle, closedNearHigh,
                            candleRange > 0 ? ((currentHigh1h - currentClose1h) / candleRange * 100) : 0,
                            currentVol, avgVol * 0.90, hasVolume);
                }
            },
            new Condition() {
                @Override public boolean test() { return isUptrend15m && isBullishBBTrend; }
                @Override public String label()  { return "En 15m la tendencia alcista debe estar totalmente alineada"; }
                @Override public String value()  {
                    return String.format("15m price %.4f>SMA %.4f && SMA>prevSMA %.4f=%b; BB bullish=%b",
                            currentPrice15m, currentSma15m, prevSma15m, isUptrend15m, isBullishBBTrend);
                }
            }
        );

        if (!ConditionEvaluator.evaluate("[C2]", ticker, currentTime, conditions, log)) {
            return false;
        }

        lastTriggerMap.put(ticker, currentTime);
        return true;
    }
}
