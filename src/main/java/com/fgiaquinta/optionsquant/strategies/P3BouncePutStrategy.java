package com.fgiaquinta.optionsquant.strategies;

import com.fgiaquinta.optionsquant.models.TimeFrame;
import com.fgiaquinta.optionsquant.services.IbkrService;
import com.fgiaquinta.optionsquant.utils.DataManager;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.SMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.helpers.HighPriceIndicator;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Map;

public class P3BouncePutStrategy implements TradingStrategy {
    private final IbkrService ibkrService;

    // 👉 ANTI-AMETRALLADORA: Almacenamos la última vez que hizo un trade por cada empresa
    private final Map<String, ZonedDateTime> lastTriggerMap = new HashMap<>();

    public P3BouncePutStrategy(IbkrService ibkrService) {
        this.ibkrService = ibkrService;
    }

    @Override
    public boolean isTriggered(String ticker, DataManager dataManager, ZonedDateTime currentTime) {

        // =========================================================================
        // REGLA 0.1: FILTRO DE APERTURA (No operar P3 en los primeros 30 min)
        // =========================================================================
        ZonedDateTime nyTime = currentTime.withZoneSameInstant(java.time.ZoneId.of("America/New_York"));
        if (nyTime.getHour() < 10) {
            return false;
        }

        // =========================================================================
        // REGLA 0.3: COOLDOWN DE 2 HORAS (Bloqueador de re-entradas)
        // =========================================================================
        ZonedDateTime lastTrigger = lastTriggerMap.get(ticker);
        if (lastTrigger != null && Duration.between(lastTrigger, currentTime).toHours() < 2) {
            return false; // Ignorar, la señal aún se está desarrollando
        }

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

        // =========================================================================
        // REGLA 1: Tendencia Bajista Principal (1 Día)
        // =========================================================================
        ClosePriceIndicator close1D = new ClosePriceIndicator(series1D);
        boolean isDowntrend = close1D.getValue(idx1D - 1).isLessThan(close1D.getValue(idx1D - 2));
        if (!isDowntrend) return false;

        // =========================================================================
        // REGLA 2: Retroceso (Bounce) a la media de 20 en 1 Hora
        // =========================================================================
        ClosePriceIndicator close1h = new ClosePriceIndicator(series1h);
        HighPriceIndicator high1h = new HighPriceIndicator(series1h);
        SMAIndicator sma20_1h = new SMAIndicator(close1h, 20);

        double currentHigh1h = high1h.getValue(idx1h).doubleValue();
        double currentClose1h = close1h.getValue(idx1h).doubleValue();
        double sma20Val1h = sma20_1h.getValue(idx1h).doubleValue();

        boolean touchedResistance = currentHigh1h >= (sma20Val1h * 0.998);
        boolean rejectedResistance = currentClose1h < sma20Val1h;

        if (!touchedResistance || !rejectedResistance) {
            return false;
        }

        // =========================================================================
        // REGLA 3: Confirmación de Reversión en 15 Minutos
        // =========================================================================
        ClosePriceIndicator close15m = new ClosePriceIndicator(series15m);
        SMAIndicator sma20_15m = new SMAIndicator(close15m, 20);

        double currentPrice15m = close15m.getValue(idx15m).doubleValue();
        double sma20Val15m = sma20_15m.getValue(idx15m).doubleValue();

        boolean confirmedDowntrend15m = currentPrice15m < sma20Val15m;

        if (confirmedDowntrend15m) {
            // Guardamos la hora de este disparo para bloquear los siguientes durante 2h
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