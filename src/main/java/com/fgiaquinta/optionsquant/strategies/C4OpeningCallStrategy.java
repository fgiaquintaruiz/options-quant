package com.fgiaquinta.optionsquant.strategies;

import com.fgiaquinta.optionsquant.models.TimeFrame;
import com.fgiaquinta.optionsquant.services.IbkrService;
import com.fgiaquinta.optionsquant.utils.DataManager;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.statistics.StandardDeviationIndicator;

import java.time.ZonedDateTime;

public class C4OpeningCallStrategy implements TradingStrategy {
    private final IbkrService ibkrService;

    public C4OpeningCallStrategy(IbkrService ibkrService) {
        this.ibkrService = ibkrService;
    }

    @Override
    public boolean isTriggered(String ticker, DataManager dataManager, ZonedDateTime currentTime) {
        // 1. En apertura usamos principalmente 15 Minutos y 5 Minutos
        BarSeries series15m = dataManager.getSeries(ticker, TimeFrame.MIN_15);
        BarSeries series5m = dataManager.getSeries(ticker, TimeFrame.MIN_5);

        if (series15m == null || series5m == null) return false;

        // Sincronización
        int idx15m = getIndexForTime(series15m, currentTime);
        int idx5m = getIndexForTime(series5m, currentTime);

        if (idx15m < 1 || idx5m < 1) return false;

        // Solo operamos en la ventana de apertura (9:30 AM a 10:00 AM NY)
        int hour = series15m.getBar(idx15m).getEndTime().getHour();
        int minute = series15m.getBar(idx15m).getEndTime().getMinute();
        if (hour != 9 || minute > 50) return false;

        // =========================================================================
        // REGLA 1 y 2: GAP DOWN (Salto a la baja)
        // =========================================================================
        double closePrevDay = series15m.getBar(idx15m - 1).getClosePrice().doubleValue();
        double openToday = series15m.getBar(idx15m).getOpenPrice().doubleValue();

        double gapPct = ((openToday - closePrevDay) / closePrevDay) * 100;

        // El libro sugiere un Gap Down de entre -1.5% y -4% para una reversión probable
        if (gapPct > -1.5 || gapPct < -4.0) return false;

        // =========================================================================
        // REGLA 3: VOLATILIDAD BAJA PREVIA
        // =========================================================================
        // Verificamos que el día anterior no haya sido una locura de volatilidad
        StandardDeviationIndicator sd = new StandardDeviationIndicator(new ClosePriceIndicator(series15m), 20);
        if (sd.getValue(idx15m - 1).doubleValue() > (closePrevDay * 0.02)) return false;

        // =========================================================================
        // REGLA 4: VELA DE REVERSIÓN (Vela verde en 5m o 15m)
        // =========================================================================
        double currentClose = series5m.getBar(idx5m).getClosePrice().doubleValue();
        double currentOpen = series5m.getBar(idx5m).getOpenPrice().doubleValue();

        return currentClose > currentOpen; // Confirmación de que el Gap se está empezando a llenar
    }

    private int getIndexForTime(BarSeries series, ZonedDateTime targetTime) {
        if (targetTime == null) return series.getEndIndex();
        for (int i = series.getEndIndex(); i >= 0; i--) {
            if (!series.getBar(i).getEndTime().isAfter(targetTime)) return i;
        }
        return 0;
    }
}