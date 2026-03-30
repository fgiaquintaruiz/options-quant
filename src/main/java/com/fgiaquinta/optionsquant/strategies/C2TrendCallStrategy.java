package com.fgiaquinta.optionsquant.strategies;

import com.fgiaquinta.optionsquant.analyzers.TrendAnalyzer;
import com.fgiaquinta.optionsquant.models.TimeFrame;
import com.fgiaquinta.optionsquant.services.IbkrService;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.SMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;

public class C2TrendCallStrategy implements TradingStrategy {
    private final IbkrService ibkrService;

    public C2TrendCallStrategy(IbkrService ibkrService) {
        this.ibkrService = ibkrService;
    }

    @Override
    public boolean isTriggered(int index, BarSeries series1h, BarSeries spySeries) {
        String ticker = series1h.getName().split("_")[0];

        double currentClose = series1h.getBar(index).getClosePrice().doubleValue();
        double currentOpen = series1h.getBar(index).getOpenPrice().doubleValue();

        // Exigimos que sea una vela alcista (verde) con fuerza
        boolean isBullishCandle = currentClose > currentOpen;
        if (!isBullishCandle) return false;

        // =========================================================================
        // REGLAS 1 y 2: Ruptura de la Línea de Tendencia Bajista
        // =========================================================================
        // Buscamos picos en las últimas 50 velas (aprox. 1 semana de mercado)
        double resistanceLineValue = TrendAnalyzer.getBearishTrendLineValue(series1h, index, 50);

        // Si no se pudo trazar una línea válida (ej. mercado plano sin picos), abortamos
        if (resistanceLineValue == -1) return false;

        // El precio de cierre debe romper la línea de tendencia proyectada
        if (currentClose <= resistanceLineValue) return false;

        // =========================================================================
        // REGLA 3: Confirmación de la Media Móvil (MM20) en 1 Hora
        // =========================================================================
        ClosePriceIndicator close1h = new ClosePriceIndicator(series1h);
        SMAIndicator sma20_1h = new SMAIndicator(close1h, 20);
        double currentSma20 = sma20_1h.getValue(index).doubleValue();

        // El precio debe cerrar por encima de la MM20
        if (currentClose <= currentSma20) return false;

        // =========================================================================
        // REGLA 4: Validación en temporalidad menor (15 Minutos)
        // =========================================================================
        BarSeries series15m = ibkrService.getSeries(ticker, TimeFrame.MIN_15);
        if (series15m == null || series15m.isEmpty()) return false;

        int last15mIdx = series15m.getEndIndex();
        ClosePriceIndicator close15m = new ClosePriceIndicator(series15m);
        SMAIndicator sma20_15m = new SMAIndicator(close15m, 20);

        double close15mVal = close15m.getValue(last15mIdx).doubleValue();
        double open15mVal = series15m.getBar(last15mIdx).getOpenPrice().doubleValue();
        double sma20_15mVal = sma20_15m.getValue(last15mIdx).doubleValue();

        // En 15m la tendencia debe ser claramente alcista:
        // Vela verde Y precio por encima de su propia MM20
        boolean isBullish15m = (close15mVal > open15mVal) && (close15mVal > sma20_15mVal);

        return isBullish15m;
    }

    @Override
    public double calculateTP(double entryPrice) {
        // En cambios de tendencia buscamos recorridos más largos (+6%)
        return Math.round((entryPrice * 1.06) * 100.0) / 100.0;
    }

    @Override
    public double calculateSL(double entryPrice, String ticker) {
        // Ajustamos el stop al -3%
        return Math.round((entryPrice * 0.97) * 100.0) / 100.0;
    }

    @Override
    public String getName() {
        return "C2_TREND_CALL";
    }
}