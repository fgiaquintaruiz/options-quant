package com.fgiaquinta.optionsquant.strategies;

import com.fgiaquinta.optionsquant.models.TimeFrame;
import com.fgiaquinta.optionsquant.services.IbkrService;
import com.fgiaquinta.optionsquant.utils.DataManager;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.SMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.helpers.LowPriceIndicator;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Map;

public class C3BounceCallStrategy implements TradingStrategy {
    private final IbkrService ibkrService;

    // 👉 ANTI-AMETRALLADORA: Cooldown de 2 horas
    private final Map<String, ZonedDateTime> lastTriggerMap = new HashMap<>();

    public C3BounceCallStrategy(IbkrService ibkrService) {
        this.ibkrService = ibkrService;
    }

    @Override
    public boolean isTriggered(String ticker, DataManager dataManager, ZonedDateTime currentTime) {

        // =========================================================================
        // REGLA 0.1: FILTRO DE APERTURA (Bloquear 9 AM de NY)
        // =========================================================================
        ZonedDateTime nyTime = currentTime.withZoneSameInstant(java.time.ZoneId.of("America/New_York"));
        if (nyTime.getHour() == 9) {
            return false;
        }

        // =========================================================================
        // REGLA 0.2: COOLDOWN DE 2 HORAS (Bloqueador de re-entradas)
        // =========================================================================
        ZonedDateTime lastTrigger = lastTriggerMap.get(ticker);
        if (lastTrigger != null && Duration.between(lastTrigger, currentTime).toHours() < 2) {
            return false;
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
        // REGLA 1: Tendencia Alcista Principal (1 Día)
        // =========================================================================
        ClosePriceIndicator close1D = new ClosePriceIndicator(series1D);
        // Exigimos que el cierre de ayer sea MAYOR que el cierre de anteayer
        boolean isUptrend = close1D.getValue(idx1D - 1).isGreaterThan(close1D.getValue(idx1D - 2));
        if (!isUptrend) return false;

        // =========================================================================
        // REGLA 2: Retroceso (Bounce) al soporte de la media de 20 en 1 Hora
        // =========================================================================
        ClosePriceIndicator close1h = new ClosePriceIndicator(series1h);
        LowPriceIndicator low1h = new LowPriceIndicator(series1h);
        SMAIndicator sma20_1h = new SMAIndicator(close1h, 20);

        double currentLow1h = low1h.getValue(idx1h).doubleValue();
        double currentClose1h = close1h.getValue(idx1h).doubleValue();
        double sma20Val1h = sma20_1h.getValue(idx1h).doubleValue();

        // Tocó el soporte (Margen del 0.2% por encima)
        boolean touchedSupport = currentLow1h <= (sma20Val1h * 1.002);
        // Rechazó caer y el cuerpo cerró por encima del soporte
        boolean rejectedSupport = currentClose1h > sma20Val1h;

        if (!touchedSupport || !rejectedSupport) {
            return false;
        }

        // =========================================================================
        // REGLA 3: Confirmación de Reversión en 15 Minutos
        // =========================================================================
        ClosePriceIndicator close15m = new ClosePriceIndicator(series15m);
        SMAIndicator sma20_15m = new SMAIndicator(close15m, 20);

        double currentPrice15m = close15m.getValue(idx15m).doubleValue();
        double sma20Val15m = sma20_15m.getValue(idx15m).doubleValue();

        // En 15m, el precio ya cruzó y se mantiene por encima de la SMA20
        boolean confirmedUptrend15m = currentPrice15m > sma20Val15m;

        if (confirmedUptrend15m) {
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