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

public class P5ContinuationPutStrategy implements TradingStrategy {
    private final IbkrService ibkrService;

    public P5ContinuationPutStrategy(IbkrService ibkrService) {
        this.ibkrService = ibkrService;
    }

    @Override
    public boolean isTriggered(String ticker, DataManager dataManager, ZonedDateTime currentTime) {
        ZonedDateTime nyTime = currentTime.withZoneSameInstant(ZoneId.of("America/New_York"));

        // =========================================================================
        // REGLA 0: EL FRANCOTIRADOR (Evaluamos justo después de la 1ra vela de 15m)
        // Ventana de 9:45 AM a 9:55 AM
        // =========================================================================
        if (nyTime.getHour() != 9 || nyTime.getMinute() < 45 || nyTime.getMinute() > 55) {
            return false;
        }

        BarSeries series1D = dataManager.getSeries(ticker, TimeFrame.DAY_1);
        BarSeries series1h = dataManager.getSeries(ticker, TimeFrame.HOUR_1);
        BarSeries series15m = dataManager.getSeries(ticker, TimeFrame.MIN_15);

        if (series1D == null || series1h == null || series15m == null ||
                series1D.isEmpty() || series1h.isEmpty() || series15m.isEmpty()) return false;

        int idx1D = getIndexForTime(series1D, currentTime);
        int idx1h = getIndexForTime(series1h, currentTime);
        int idx15m = getIndexForTime(series15m, currentTime);

        if (idx1D < 3 || idx1h < 20 || idx15m < 20) return false;

        // =========================================================================
        // REGLA 1: TENDENCIA CLARAMENTE ALCISTA (Varios días subiendo)
        // =========================================================================
        ClosePriceIndicator close1D = new ClosePriceIndicator(series1D);
        double prevClose1D = close1D.getValue(idx1D - 1).doubleValue();
        double prev2Close1D = close1D.getValue(idx1D - 2).doubleValue();
        double prev3Close1D = close1D.getValue(idx1D - 3).doubleValue();

        // Confirmamos que lleva al menos 2 días consecutivos cerrando en positivo
        if (prevClose1D <= prev2Close1D || prev2Close1D <= prev3Close1D) {
            return false;
        }

        // =========================================================================
        // REGLA 2: SALTO FUERTE AL ALZA Y ALEJADO DE LA MM20 EN 1 HORA
        // =========================================================================
        ClosePriceIndicator close1h = new ClosePriceIndicator(series1h);
        SMAIndicator sma20_1h = new SMAIndicator(close1h, 20);
        double currentSma1h = sma20_1h.getValue(idx1h).doubleValue();

        int firstCandle15mIdx = idx15m - 1; // La vela de 9:30 a 9:45
        double first15mOpen = series15m.getBar(firstCandle15mIdx).getOpenPrice().doubleValue();

        // Tiene que ser un Gap Up respecto al cierre de ayer
        if (first15mOpen <= prevClose1D) return false;

        // "Muy alejado" de la Media Móvil 20 (Al menos un 3% por encima del imán)
        if (first15mOpen < (currentSma1h * 1.03)) return false;

        // =========================================================================
        // REGLA 3: PRIMERA VELA DE 15M COMPLETAMENTE FUERA DE BOLLINGER (ARRIBA)
        // =========================================================================
        ClosePriceIndicator close15m = new ClosePriceIndicator(series15m);
        SMAIndicator sma20_15m = new SMAIndicator(close15m, 20);
        StandardDeviationIndicator sd15m = new StandardDeviationIndicator(close15m, 20);

        // Calculamos la banda superior usando los datos previos a la apertura
        double prevSma15m = sma20_15m.getValue(firstCandle15mIdx - 1).doubleValue();
        double prevSd15m = sd15m.getValue(firstCandle15mIdx - 1).doubleValue();
        double upperBand15m = prevSma15m + (prevSd15m * 2);

        double first15mLow = series15m.getBar(firstCandle15mIdx).getLowPrice().doubleValue();

        // "Completamente fuera": Incluso la mecha inferior de esa primera vela debe estar sobre la banda
        if (first15mLow <= upperBand15m) {
            return false;
        }

        // =========================================================================
        // REGLA 4: CONFIRMACIÓN DE DESPLOME (El imán hace efecto)
        // =========================================================================
        double currentPrice = series15m.getBar(idx15m).getClosePrice().doubleValue();
        double currentOpen = series15m.getBar(idx15m).getOpenPrice().doubleValue();

        // La nueva vela (9:45 - 10:00) arranca en rojo y empieza a hundirse hacia el imán
        boolean isReversingDown = currentPrice < currentOpen;

        // Volumen altísimo (Sustituto matemático del cruce de Worden Stochastics)
        VolumeIndicator vol15m = new VolumeIndicator(series15m);
        SMAIndicator avgVol15m = new SMAIndicator(vol15m, 10);
        double firstCandleVol = vol15m.getValue(firstCandle15mIdx).doubleValue();
        double avgVol = avgVol15m.getValue(firstCandle15mIdx - 1).doubleValue();

        boolean hasVolumeSurge = firstCandleVol > (avgVol * 1.5);

        return isReversingDown && hasVolumeSurge;
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