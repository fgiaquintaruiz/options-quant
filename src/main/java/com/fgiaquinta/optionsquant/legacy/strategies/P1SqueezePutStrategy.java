package com.fgiaquinta.optionsquant.strategies;

import com.fgiaquinta.optionsquant.models.TimeFrame;
import com.fgiaquinta.optionsquant.services.IbkrService;
import com.fgiaquinta.optionsquant.utils.DataManager;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.SMAIndicator;
import org.ta4j.core.indicators.helpers.*;
import org.ta4j.core.indicators.statistics.StandardDeviationIndicator;

import java.time.ZonedDateTime;
import java.time.ZoneId;

public class P1SqueezePutStrategy implements TradingStrategy {
    private final IbkrService ibkrService;

    public P1SqueezePutStrategy(IbkrService ibkrService) {
        this.ibkrService = ibkrService;
    }

    @Override
    public boolean isTriggered(String ticker, DataManager dataManager, ZonedDateTime currentTime) {
        BarSeries series1h = dataManager.getSeries(ticker, TimeFrame.HOUR_1);
        BarSeries series15m = dataManager.getSeries(ticker, TimeFrame.MIN_15);

        if (series1h == null || series15m == null || series1h.isEmpty() || series15m.isEmpty()) return false;

        int idx1h = getIndexForTime(series1h, currentTime);
        int idx15m = getIndexForTime(series15m, currentTime);

        if (idx1h < 200 || idx15m < 20) return false;

        ClosePriceIndicator close1h = new ClosePriceIndicator(series1h);

        // =========================================================================
        // REGLA 1 y 2: CANAL LATERAL Y MEDIAS ENTRELAZADAS (10 DÍAS / ~70 HORAS)
        // =========================================================================
        SMAIndicator sma20 = new SMAIndicator(close1h, 20);
        SMAIndicator sma40 = new SMAIndicator(close1h, 40);
        SMAIndicator sma100 = new SMAIndicator(close1h, 100);
        SMAIndicator sma200 = new SMAIndicator(close1h, 200);

        int prevIdx = idx1h - 1;
        double s20 = sma20.getValue(prevIdx).doubleValue();
        double s40 = sma40.getValue(prevIdx).doubleValue();
        double s100 = sma100.getValue(prevIdx).doubleValue();
        double s200 = sma200.getValue(prevIdx).doubleValue();

        double maxSma = Math.max(Math.max(s20, s40), Math.max(s100, s200));
        double minSma = Math.min(Math.min(s20, s40), Math.min(s100, s200));

        if ((maxSma - minSma) / minSma > 0.04) return false;

        // Buscamos el PISO de ese canal de 10 días
        double minPriceLast10Days = Double.MAX_VALUE;
        for(int i = 1; i <= 70; i++) {
            double low = series1h.getBar(idx1h - i).getLowPrice().doubleValue();
            if(low < minPriceLast10Days) minPriceLast10Days = low;
        }

        // =========================================================================
        // REGLA 3: EL ROMPIMIENTO BAJISTA
        // =========================================================================
        double currentClose1h = close1h.getValue(idx1h).doubleValue();
        double currentOpen1h = series1h.getBar(idx1h).getOpenPrice().doubleValue();

        // Rompe el piso con fuerza roja
        boolean isBreakoutDown = currentClose1h < minPriceLast10Days && currentClose1h < currentOpen1h;
        if (!isBreakoutDown) return false;

        // =========================================================================
        // REGLA 4: CONFIRMACIÓN DE ALTA VOLATILIDAD EN BOLLINGER 15 MINUTOS
        // =========================================================================
        ClosePriceIndicator close15m = new ClosePriceIndicator(series15m);
        SMAIndicator sma20_15m = new SMAIndicator(close15m, 20);
        StandardDeviationIndicator sd15m = new StandardDeviationIndicator(close15m, 20);

        double currentSma15m = sma20_15m.getValue(idx15m).doubleValue();
        double currentSd15m = sd15m.getValue(idx15m).doubleValue();
        double lowerBand15m = currentSma15m - (currentSd15m * 2);

        double currentClose15m = close15m.getValue(idx15m).doubleValue();

        // Empujando la banda inferior hacia abajo
        boolean isRidingLowerBand = currentClose15m <= (lowerBand15m * 1.005);

        return isRidingLowerBand;
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