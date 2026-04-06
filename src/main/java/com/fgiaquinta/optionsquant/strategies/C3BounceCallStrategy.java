package com.fgiaquinta.optionsquant.strategies;

import com.fgiaquinta.optionsquant.models.TimeFrame;
import com.fgiaquinta.optionsquant.services.IbkrService;
import com.fgiaquinta.optionsquant.utils.DataManager;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.SMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;

import java.time.ZonedDateTime;

public class C3BounceCallStrategy implements TradingStrategy {
    private final IbkrService ibkrService;

    public C3BounceCallStrategy(IbkrService ibkrService) {
        this.ibkrService = ibkrService;
    }

    @Override
    public boolean isTriggered(String ticker, DataManager dataManager, ZonedDateTime currentTime) {
        // 1. Extraemos las gráficas necesarias (Diaria, 1H y 15m)
        BarSeries seriesDaily = dataManager.getSeries(ticker, TimeFrame.DAY_1);
        BarSeries series1h = dataManager.getSeries(ticker, TimeFrame.HOUR_1);
        BarSeries series15m = dataManager.getSeries(ticker, TimeFrame.MIN_15);

        if (seriesDaily == null || series1h == null || series15m == null) return false;

        // 2. Sincronización de índices para el Backtest
        int idxDaily = getIndexForTime(seriesDaily, currentTime);
        int idx1h = getIndexForTime(series1h, currentTime);
        int idx15m = getIndexForTime(series15m, currentTime);

        if (idxDaily < 20 || idx1h < 1 || idx15m < 1) return false;

        // =========================================================================
        // REGLA 2: APROXIMACIÓN AL SOPORTE (Temporalidad Diaria - MM20)
        // =========================================================================
        ClosePriceIndicator closeDaily = new ClosePriceIndicator(seriesDaily);
        SMAIndicator sma20Daily = new SMAIndicator(closeDaily, 20);

        double sma20DailyVal = sma20Daily.getValue(idxDaily).doubleValue();
        double currentPrice = series1h.getBar(idx1h).getClosePrice().doubleValue();
        double low1h = series1h.getBar(idx1h).getLowPrice().doubleValue();

        // El precio debe estar cerca o haber tocado la MM20 diaria (margen 0.5%)
        boolean isTouchingDailySupport = low1h <= (sma20DailyVal * 1.005);
        if (!isTouchingDailySupport) return false;

        // =========================================================================
        // REGLA 1: CONTEXTO (Temporalidad 1 Hora - Tendencia Bajista Previa)
        // =========================================================================
        // Según el libro, venimos de una caída hacia la media.
        // Verificamos que el precio esté por debajo de la apertura de la vela anterior (caída)
        double prevClose1h = series1h.getBar(idx1h - 1).getClosePrice().doubleValue();
        double prevOpen1h = series1h.getBar(idx1h - 1).getOpenPrice().doubleValue();

        if (prevClose1h > prevOpen1h) return false; // Queremos venir de debilidad

        // =========================================================================
        // REGLA 3: VERIFICACIÓN DEL REBOTE (Temporalidad 15 Minutos)
        // =========================================================================
        double close15m = series15m.getBar(idx15m).getClosePrice().doubleValue();
        double open15m = series15m.getBar(idx15m).getOpenPrice().doubleValue();

        // El precio debe respetar la MM20 Diaria (no cerró por debajo) y ser vela verde
        boolean isBouncingUp15m = (close15m > open15m) && (close15m > sma20DailyVal);
        if (!isBouncingUp15m) return false;

        // =========================================================================
        // REGLA 4: ENTRADA (Temporalidad 1 Hora - Vela de Confirmación Alcista)
        // =========================================================================
        double open1h = series1h.getBar(idx1h).getOpenPrice().doubleValue();
        return currentPrice > open1h; // Vela de 1H actual es verde (confirmación)
    }

    private int getIndexForTime(BarSeries series, ZonedDateTime targetTime) {
        if (targetTime == null) return series.getEndIndex();
        for (int i = series.getEndIndex(); i >= 0; i--) {
            if (!series.getBar(i).getEndTime().isAfter(targetTime)) return i;
        }
        return 0;
    }
}