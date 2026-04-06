package com.fgiaquinta.optionsquant.strategies;

import com.fgiaquinta.optionsquant.models.TimeFrame;
import com.fgiaquinta.optionsquant.services.IbkrService;
import com.fgiaquinta.optionsquant.utils.DataManager;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.SMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.rules.CrossedDownIndicatorRule;

import java.time.ZonedDateTime;

public class P2TrendPutStrategy implements TradingStrategy {
    private final IbkrService ibkrService;

    public P2TrendPutStrategy(IbkrService ibkrService) {
        this.ibkrService = ibkrService;
    }

    @Override
    public boolean isTriggered(String ticker, DataManager dataManager, ZonedDateTime currentTime) {
        // Extraemos ambas temporalidades necesarias para las Reglas 3 y 4
        BarSeries series1h = dataManager.getSeries(ticker, TimeFrame.HOUR_1);
        BarSeries series15m = dataManager.getSeries(ticker, TimeFrame.MIN_15);

        if (series1h == null || series1h.isEmpty() || series15m == null || series15m.isEmpty()) {
            return false;
        }

        // Sincronización temporal de índices
        int idx1h = getIndexForTime(series1h, currentTime);
        int idx15m = getIndexForTime(series15m, currentTime);

        if (idx1h < 20 || idx15m < 20) return false;

        // =========================================================================
        // REGLAS 1, 2 y 3: Ruptura de Tendencia Alcista y Media Móvil 20 en 1H
        // =========================================================================
        ClosePriceIndicator close1h = new ClosePriceIndicator(series1h);
        SMAIndicator sma20_1h = new SMAIndicator(close1h, 20);

        // Verificamos que había una tendencia alcista previa (las 3 velas anteriores estaban por encima de la SMA20)
        boolean wasUptrend = close1h.getValue(idx1h - 1).isGreaterThan(sma20_1h.getValue(idx1h - 1)) &&
                close1h.getValue(idx1h - 2).isGreaterThan(sma20_1h.getValue(idx1h - 2)) &&
                close1h.getValue(idx1h - 3).isGreaterThan(sma20_1h.getValue(idx1h - 3));

        // Verificamos la Ruptura (Cross Down): El precio acaba de cruzar y cerrar por debajo de la SMA20
        CrossedDownIndicatorRule crossDownRule = new CrossedDownIndicatorRule(close1h, sma20_1h);
        boolean isBreakout = crossDownRule.isSatisfied(idx1h);

        if (!wasUptrend || !isBreakout) {
            return false; // Si no venía subiendo, o no acaba de romper a la baja, abortamos.
        }

        // =========================================================================
        // REGLA 4: Confirmación en temporalidad de 15 Minutos
        // =========================================================================
        ClosePriceIndicator close15m = new ClosePriceIndicator(series15m);
        SMAIndicator sma20_15m = new SMAIndicator(close15m, 20);

        double currentPrice15m = close15m.getValue(idx15m).doubleValue();
        double sma20Val15m = sma20_15m.getValue(idx15m).doubleValue();

        // En 15 minutos, la tendencia debe mostrarse ya totalmente bajista
        boolean confirmedDowntrend15m = currentPrice15m < sma20Val15m;

        // Si todas las reglas se cumplen, disparamos el Gatillo para comprar PUTS
        return confirmedDowntrend15m;
    }

    // Helper method para la sincronización temporal
    private int getIndexForTime(BarSeries series, ZonedDateTime time) {
        for (int i = series.getEndIndex(); i >= Math.max(0, series.getEndIndex() - 500); i--) {
            if (!series.getBar(i).getEndTime().isAfter(time)) {
                return i;
            }
        }
        return -1;
    }
}