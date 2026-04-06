package com.fgiaquinta.optionsquant.strategies;

import com.fgiaquinta.optionsquant.models.TimeFrame;
import com.fgiaquinta.optionsquant.services.IbkrService;
import com.fgiaquinta.optionsquant.utils.DataManager;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.SMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;

import java.time.ZonedDateTime;

public class C2TrendCallStrategy implements TradingStrategy {
    private final IbkrService ibkrService;

    public C2TrendCallStrategy(IbkrService ibkrService) {
        this.ibkrService = ibkrService;
    }

    @Override
    public boolean isTriggered(String ticker, DataManager dataManager, ZonedDateTime currentTime) {
        // 1. Extraemos las gráficas necesarias de la caché central
        BarSeries series1h = dataManager.getSeries(ticker, TimeFrame.HOUR_1);
        BarSeries series15m = dataManager.getSeries(ticker, TimeFrame.MIN_15);

        if (series1h == null || series15m == null) return false;

        // 2. Sincronización de índices (Crucial para el Backtest)
        int idx1h = getIndexForTime(series1h, currentTime);
        int idx15m = getIndexForTime(series15m, currentTime);

        if (idx1h < 20 || idx15m < 20) return false;

        // =========================================================================
        // REGLA 1 y 2: Ruptura de Línea de Tendencia Bajista
        // =========================================================================
        // Lógica simplificada: Comprobamos si venimos de máximos descendentes
        // y el precio actual rompe esa estructura al alza.
        double currentPrice = series1h.getBar(idx1h).getClosePrice().doubleValue();
        double prevHigh1 = series1h.getBar(idx1h - 5).getHighPrice().doubleValue();
        double prevHigh2 = series1h.getBar(idx1h - 10).getHighPrice().doubleValue();

        boolean isBreakingTrend = currentPrice > prevHigh1 && prevHigh1 < prevHigh2;
        if (!isBreakingTrend) return false;

        // =========================================================================
        // REGLA 3: Ruptura de MM20 y Vela de Confirmación Alcista (1 Hora)
        // =========================================================================
        ClosePriceIndicator close1h = new ClosePriceIndicator(series1h);
        SMAIndicator sma20_1h = new SMAIndicator(close1h, 20);

        double sma20Value1h = sma20_1h.getValue(idx1h).doubleValue();
        double open1h = series1h.getBar(idx1h).getOpenPrice().doubleValue();

        // El precio debe cerrar por encima de la MM20 y ser una vela verde (confirmación)
        boolean isConfirmedAboveMA20 = (currentPrice > open1h) && (currentPrice > sma20Value1h);
        if (!isConfirmedAboveMA20) return false;

        // =========================================================================
        // REGLA 4: Validación de Temporalidad Menor (15 Minutos)
        // =========================================================================
        ClosePriceIndicator close15m = new ClosePriceIndicator(series15m);
        SMAIndicator sma20_15m = new SMAIndicator(close15m, 20);

        double close15 = series15m.getBar(idx15m).getClosePrice().doubleValue();
        double open15 = series15m.getBar(idx15m).getOpenPrice().doubleValue();
        double sma20Value15m = sma20_15m.getValue(idx15m).doubleValue();

        // En 15m la tendencia debe ser TOTALMENTE alcista (Precio > MM20 y Vela Verde)
        boolean isBullish15m = (close15 > open15) && (close15 > sma20Value15m);

        return isBullish15m;
    }

    private int getIndexForTime(BarSeries series, ZonedDateTime targetTime) {
        if (targetTime == null) return series.getEndIndex();
        for (int i = series.getEndIndex(); i >= 0; i--) {
            if (!series.getBar(i).getEndTime().isAfter(targetTime)) return i;
        }
        return 0;
    }
}