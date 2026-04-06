package com.fgiaquinta.optionsquant.strategies;

import com.fgiaquinta.optionsquant.analyzers.VolatilityAnalyzer;
import com.fgiaquinta.optionsquant.models.TimeFrame;
import com.fgiaquinta.optionsquant.services.IbkrService;
import com.fgiaquinta.optionsquant.utils.DataManager;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.SMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;

import java.time.ZonedDateTime;

public class P1SqueezePutStrategy implements TradingStrategy {
    private final IbkrService ibkrService;

    public P1SqueezePutStrategy(IbkrService ibkrService) {
        this.ibkrService = ibkrService;
    }

    @Override
    public boolean isTriggered(String ticker, DataManager dataManager, ZonedDateTime currentTime) {
        // 1. Extraemos la gráfica de 1 Hora desde la caché central (DataManager)
        BarSeries series1h = dataManager.getSeries(ticker, TimeFrame.HOUR_1);

        if (series1h == null || series1h.isEmpty()) {
            return false;
        }

        // 2. Sincronización temporal de índices
        int idx1h = getIndexForTime(series1h, currentTime);

        // Necesitamos al menos 20 velas de historia para calcular la SMA20 y las Bandas de Bollinger
        if (idx1h < 20) return false;

        // =========================================================================
        // REGLA 1: Detección de Squeeze (Baja volatilidad histórica)
        // =========================================================================
        double width = VolatilityAnalyzer.getBollingerBandWidthPct(series1h, idx1h, 20, 2.0);

        // El umbral se mantiene en 1.5%. Si las bandas están más anchas, no hay Squeeze.
        if (width > 1.5) return false;

        // =========================================================================
        // REGLA 2: Confirmación bajista (Cierre por debajo de la media de 20)
        // =========================================================================
        double close = series1h.getBar(idx1h).getClosePrice().doubleValue();

        ClosePriceIndicator closeIndicator = new ClosePriceIndicator(series1h);
        SMAIndicator sma20 = new SMAIndicator(closeIndicator, 20);
        double sma20Val = sma20.getValue(idx1h).doubleValue();

        // Para un PUT, queremos que el precio rompa hacia abajo la media móvil
        return close < sma20Val;
    }

    // Helper method para la sincronización temporal (Evita el Look-Ahead Bias)
    private int getIndexForTime(BarSeries series, ZonedDateTime time) {
        for (int i = series.getEndIndex(); i >= Math.max(0, series.getEndIndex() - 500); i--) {
            if (!series.getBar(i).getEndTime().isAfter(time)) {
                return i;
            }
        }
        return -1;
    }
}