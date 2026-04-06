package com.fgiaquinta.optionsquant.strategies;

import com.fgiaquinta.optionsquant.models.TimeFrame;
import com.fgiaquinta.optionsquant.services.IbkrService;
import com.fgiaquinta.optionsquant.utils.DataManager;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.SMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsMiddleIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsUpperIndicator;
import org.ta4j.core.indicators.statistics.StandardDeviationIndicator;

import java.time.ZonedDateTime;

public class C1SqueezeCallStrategy implements TradingStrategy {
    private final IbkrService ibkrService;

    public C1SqueezeCallStrategy(IbkrService ibkrService) {
        this.ibkrService = ibkrService;
    }

    @Override
    public boolean isTriggered(String ticker, DataManager dataManager, ZonedDateTime currentTime) {
        // 1. Extraemos las gráficas necesarias
        BarSeries series1h = dataManager.getSeries(ticker, TimeFrame.HOUR_1);
        BarSeries series15m = dataManager.getSeries(ticker, TimeFrame.MIN_15);

        if (series1h == null || series15m == null) return false;

        // 2. Sincronización de índices
        int idx1h = getIndexForTime(series1h, currentTime);
        int idx15m = getIndexForTime(series15m, currentTime);

        // Necesitamos al menos 70 velas para validar el canal de 10 días (Regra 2 del libro)
        if (idx1h < 70 || idx15m < 1) return false;

        // =========================================================================
        // REGLAS 1 y 2: CANAL LATERAL (SQUEEZE) - 10 DÍAS O MÁS
        // =========================================================================
        // Calculamos el ancho de las bandas de Bollinger en 1H para ver si hay "Squeeze"
        ClosePriceIndicator close1h = new ClosePriceIndicator(series1h);
        SMAIndicator sma1h20 = new SMAIndicator(close1h, 20);
        StandardDeviationIndicator sd1h = new StandardDeviationIndicator(close1h, 20);

        double upper1h = sma1h20.getValue(idx1h).doubleValue() + (2 * sd1h.getValue(idx1h).doubleValue());
        double lower1h = sma1h20.getValue(idx1h).doubleValue() - (2 * sd1h.getValue(idx1h).doubleValue());
        double bandwidth = ((upper1h - lower1h) / sma1h20.getValue(idx1h).doubleValue()) * 100;

        // Si el ancho es mayor al 2%, no es un canal lateral lo suficientemente estrecho
        if (bandwidth > 2.0) return false;

        // =========================================================================
        // REGLA 3: SEÑAL ALCISTA (Ruptura del canal en 1H)
        // =========================================================================
        double currentPrice1h = series1h.getBar(idx1h).getClosePrice().doubleValue();
        double openPrice1h = series1h.getBar(idx1h).getOpenPrice().doubleValue();

        // Debe ser una vela alcista que cierre por encima de la media de 20 en 1H
        boolean isBreakout1h = currentPrice1h > openPrice1h && currentPrice1h > sma1h20.getValue(idx1h).doubleValue();
        if (!isBreakout1h) return false;

        // =========================================================================
        // REGLA 4: CONFIRMACIÓN EN 15 MINUTOS (Alta Volatilidad en Bollinger)
        // =========================================================================
        ClosePriceIndicator close15m = new ClosePriceIndicator(series15m);
        SMAIndicator sma15m20 = new SMAIndicator(close15m, 20);
        BollingerBandsMiddleIndicator bbMid15 = new BollingerBandsMiddleIndicator(sma15m20);
        StandardDeviationIndicator sd15 = new StandardDeviationIndicator(close15m, 20);
        BollingerBandsUpperIndicator bbUpper15 = new BollingerBandsUpperIndicator(bbMid15, sd15);

        double close15 = series15m.getBar(idx15m).getClosePrice().doubleValue();
        double upper15 = bbUpper15.getValue(idx15m).doubleValue();

        // El precio en 15m debe estar rompiendo la banda superior con fuerza
        return close15 >= (upper15 * 0.999); // 0.1% de margen de toque
    }

    private int getIndexForTime(BarSeries series, ZonedDateTime targetTime) {
        if (targetTime == null) return series.getEndIndex();
        for (int i = series.getEndIndex(); i >= 0; i--) {
            if (!series.getBar(i).getEndTime().isAfter(targetTime)) return i;
        }
        return 0;
    }
}