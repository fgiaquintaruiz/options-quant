package com.fgiaquinta.optionsquant.strategies;

import com.fgiaquinta.optionsquant.models.TimeFrame;
import com.fgiaquinta.optionsquant.services.IbkrService;
import com.fgiaquinta.optionsquant.utils.DataManager;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.SMAIndicator;
import org.ta4j.core.indicators.helpers.*;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Map;

public class P1SqueezePutStrategy implements TradingStrategy {
    private final IbkrService ibkrService;
    private final Map<String, ZonedDateTime> lastTriggerMap = new HashMap<>();

    public P1SqueezePutStrategy(IbkrService ibkrService) {
        this.ibkrService = ibkrService;
    }

    @Override
    public boolean isTriggered(String ticker, DataManager dataManager, ZonedDateTime currentTime) {

        ZonedDateTime nyTime = currentTime.withZoneSameInstant(java.time.ZoneId.of("America/New_York"));
        if (nyTime.getHour() == 9) return false;

        ZonedDateTime lastTrigger = lastTriggerMap.get(ticker);
        if (lastTrigger != null && Duration.between(lastTrigger, currentTime).toHours() < 2) return false;

        BarSeries series1D = dataManager.getSeries(ticker, TimeFrame.DAY_1);
        BarSeries series1h = dataManager.getSeries(ticker, TimeFrame.HOUR_1);
        BarSeries series15m = dataManager.getSeries(ticker, TimeFrame.MIN_15);

        if (series1D == null || series1h == null || series15m == null ||
                series1D.isEmpty() || series1h.isEmpty() || series15m.isEmpty()) {
            return false;
        }

        int idx1D = getIndexForTime(series1D, currentTime);
        int idx1h = getIndexForTime(series1h, currentTime);
        int idx15m = getIndexForTime(series15m, currentTime);

        if (idx1D < 20 || idx1h < 20 || idx15m < 20) return false;

        ClosePriceIndicator close1h = new ClosePriceIndicator(series1h);
        OpenPriceIndicator open1h = new OpenPriceIndicator(series1h);
        HighPriceIndicator high1h = new HighPriceIndicator(series1h);
        LowPriceIndicator low1h = new LowPriceIndicator(series1h);
        SMAIndicator sma20_1h = new SMAIndicator(close1h, 20);

        // =========================================================================
        // REGLA 1: TENDENCIA ALCISTA PREVIA (Filtro de "Estar por encima")
        // El precio debe haber cerrado POR ENCIMA de la SMA20 durante las 3 horas previas.
        // =========================================================================
        boolean wasClearUptrend = true;
        for (int i = 1; i <= 3; i++) {
            if (close1h.getValue(idx1h - i).doubleValue() <= sma20_1h.getValue(idx1h - i).doubleValue()) {
                wasClearUptrend = false;
                break;
            }
        }
        if (!wasClearUptrend) return false;

        // =========================================================================
        // REGLAS 2 y 3: ROTURA DE MM20 HACIA ABAJO (PUT)
        // =========================================================================
        double currentClose1h = close1h.getValue(idx1h).doubleValue();
        double currentOpen1h = open1h.getValue(idx1h).doubleValue();
        double currentHigh1h = high1h.getValue(idx1h).doubleValue();
        double currentLow1h = low1h.getValue(idx1h).doubleValue();
        double currentSma1h = sma20_1h.getValue(idx1h).doubleValue();

        // Cruzó hacia abajo (el precio de ahora es menor que la media)
        boolean crossedBelowSma = currentClose1h < currentSma1h;
        // Es vela roja (cierre menor que apertura)
        boolean isBearishCandle = currentClose1h < currentOpen1h;

        // 👉 FILTRO DE MECHA: La vela debe cerrar en su 35% INFERIOR (sin mechas largas abajo)
        double candleRange = currentHigh1h - currentLow1h;
        boolean closedNearLow = (currentClose1h - currentLow1h) <= (candleRange * 0.35);

        if (!crossedBelowSma || !isBearishCandle || !closedNearLow) return false;

        // 👉 FILTRO DE VOLUMEN: Al menos el 90% del promedio de las últimas 10 velas
        VolumeIndicator vol1h = new VolumeIndicator(series1h);
        SMAIndicator avgVol1h = new SMAIndicator(vol1h, 10);
        double currentVol = vol1h.getValue(idx1h).doubleValue();
        double avgVol = avgVol1h.getValue(idx1h).doubleValue();
        if (currentVol < (avgVol * 0.90)) return false;

        // =========================================================================
        // REGLA 4: CONFIRMACIÓN EN 15 MINUTOS (Tendencia bajista total)
        // =========================================================================
        ClosePriceIndicator close15m = new ClosePriceIndicator(series15m);
        SMAIndicator sma20_15m = new SMAIndicator(close15m, 20);

        double currentPrice15m = close15m.getValue(idx15m).doubleValue();
        double currentSma15m = sma20_15m.getValue(idx15m).doubleValue();
        double prevSma15m = sma20_15m.getValue(idx15m - 1).doubleValue();

        // El precio está debajo de la media de 15m y la media apunta hacia abajo
        boolean isDowntrend15m = (currentPrice15m < currentSma15m) && (currentSma15m < prevSma15m);

        if (isDowntrend15m) {
            lastTriggerMap.put(ticker, currentTime);
            return true;
        }

        return false;
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