package com.fgiaquinta.optionsquant.strategies;

import com.fgiaquinta.optionsquant.models.TimeFrame;
import com.fgiaquinta.optionsquant.services.IbkrService;
import com.fgiaquinta.optionsquant.utils.DataManager;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.SMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.statistics.StandardDeviationIndicator;

import java.time.ZonedDateTime;
import java.time.ZoneId;

public class P4OpeningPutStrategy implements TradingStrategy {
    private final IbkrService ibkrService;

    public P4OpeningPutStrategy(IbkrService ibkrService) {
        this.ibkrService = ibkrService;
    }

    @Override
    public boolean isTriggered(String ticker, DataManager dataManager, ZonedDateTime currentTime) {
        ZonedDateTime nyTime = currentTime.withZoneSameInstant(ZoneId.of("America/New_York"));

        // =========================================================================
        // REGLA 3: EL FRANCOTIRADOR (Solo operar entre 9:30 y 9:35 AM)
        // =========================================================================
        if (nyTime.getHour() != 9 || nyTime.getMinute() < 30 || nyTime.getMinute() > 35) {
            return false; // Pasados los 5 minutos, la estrategia queda sin efecto
        }

        BarSeries series15m = dataManager.getSeries(ticker, TimeFrame.MIN_15);
        BarSeries series5m = dataManager.getSeries(ticker, TimeFrame.MIN_5);

        if (series15m == null || series5m == null || series15m.isEmpty() || series5m.isEmpty()) return false;

        int idx15m = getIndexForTime(series15m, currentTime);
        int idx5m = getIndexForTime(series5m, currentTime);
        if (idx15m < 20 || idx5m < 1) return false;

        // =========================================================================
        // REGLA 1: TENDENCIA TOTALMENTE LATERAL Y SIN VOLATILIDAD (El día anterior)
        // =========================================================================
        ClosePriceIndicator close15m = new ClosePriceIndicator(series15m);
        SMAIndicator sma20_15m = new SMAIndicator(close15m, 20);
        StandardDeviationIndicator sd15m = new StandardDeviationIndicator(close15m, 20);

        // Leemos las Bandas de Bollinger en la vela ANTERIOR (el cierre de ayer)
        double prevSma = sma20_15m.getValue(idx15m - 1).doubleValue();
        double prevSd = sd15m.getValue(idx15m - 1).doubleValue();
        double prevLowerBand = prevSma - (prevSd * 2);
        double prevUpperBand = prevSma + (prevSd * 2);

        // Verificamos si era lateral (Ancho de la banda menor al 1.5% del precio)
        double bandWidthPct = (prevUpperBand - prevLowerBand) / prevSma;
        if (bandWidthPct > 0.015) return false;

        // =========================================================================
        // REGLA 2: SALTO AL ALZA EXTREMO (Fuera de Bollinger) Y BAJANDO
        // =========================================================================
        double openToday = series5m.getBar(idx5m).getOpenPrice().doubleValue();

        // ¿Amaneció muy por encima de la Banda Superior de ayer?
        boolean isExtremeGapUp = openToday > prevUpperBand;
        if (!isExtremeGapUp) return false;

        // Confirmación: "observar que comience a bajar"
        double currentPrice = series5m.getBar(idx5m).getClosePrice().doubleValue();
        boolean isCrashingDown = currentPrice < openToday;

        return isCrashingDown;
    }

    private int getIndexForTime(BarSeries series, ZonedDateTime time) {
        for (int i = series.getEndIndex(); i >= Math.max(0, series.getEndIndex() - 500); i--) {
            if (!series.getBar(i).getEndTime().isAfter(time)) {
                return i;
            }
        }
        return -1;
    }
}