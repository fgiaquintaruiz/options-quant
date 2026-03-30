package com.fgiaquinta.optionsquant.strategies;

import com.fgiaquinta.optionsquant.analyzers.ChannelAnalyzer;
import com.fgiaquinta.optionsquant.models.TimeFrame;
import com.fgiaquinta.optionsquant.services.IbkrService;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.SMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsMiddleIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsUpperIndicator;
import org.ta4j.core.indicators.statistics.StandardDeviationIndicator;

public class C1SqueezeCallStrategy implements TradingStrategy {
    private final IbkrService ibkrService;

    public C1SqueezeCallStrategy(IbkrService ibkrService) {
        this.ibkrService = ibkrService;
    }

    @Override
    public boolean isTriggered(int index, BarSeries series1h, BarSeries spySeries) {
        // Extraemos el ticker dinámicamente del nombre de la serie (ej: "AAPL_1hour" -> "AAPL")
        String ticker = series1h.getName().split("_")[0];

        // =========================================================================
        // REGLAS 1 y 2: Canal Lateral de 10 días (70 velas de 1H)
        // =========================================================================
        // Exigimos que las 4 medias móviles estén comprimidas en un margen máximo del 2.5%
        if (!ChannelAnalyzer.isSmaLateralChannel(series1h, index, 70, 2.5)) {
            return false;
        }

        // =========================================================================
        // REGLA 3: Señal Alcista (Ruptura del Canal en 1H)
        // =========================================================================
        ClosePriceIndicator close1h = new ClosePriceIndicator(series1h);
        SMAIndicator sma20 = new SMAIndicator(close1h, 20);
        SMAIndicator sma40 = new SMAIndicator(close1h, 40);
        SMAIndicator sma100 = new SMAIndicator(close1h, 100);
        SMAIndicator sma200 = new SMAIndicator(close1h, 200);

        double currentClose = close1h.getValue(index).doubleValue();
        double currentOpen = series1h.getBar(index).getOpenPrice().doubleValue();

        // Buscamos cuál es el "techo" actual de las medias móviles
        double maxSma = Math.max(Math.max(sma20.getValue(index).doubleValue(), sma40.getValue(index).doubleValue()),
                Math.max(sma100.getValue(index).doubleValue(), sma200.getValue(index).doubleValue()));

        // Confirmamos que es una vela verde (alcista) y que su cierre rompe el techo de las medias
        boolean isBullishBreakout = (currentClose > currentOpen) && (currentClose > maxSma);

        if (!isBullishBreakout) {
            return false;
        }

        // =========================================================================
        // REGLA 4: Confirmación en temporalidad menor (15 Minutos)
        // =========================================================================
        BarSeries series15m = ibkrService.getSeries(ticker, TimeFrame.MIN_15);
        if (series15m == null || series15m.isEmpty()) {
            return false; // Sin datos de 15m no podemos confirmar, abortamos
        }

        int last15mIdx = series15m.getEndIndex();
        ClosePriceIndicator close15m = new ClosePriceIndicator(series15m);

        // Calculamos las Bandas de Bollinger de 15 minutos (20 periodos, factor 2)
        SMAIndicator sma15m20 = new SMAIndicator(close15m, 20);
        BollingerBandsMiddleIndicator bbMiddle = new BollingerBandsMiddleIndicator(sma15m20);
        StandardDeviationIndicator sd15m = new StandardDeviationIndicator(close15m, 20);
        BollingerBandsUpperIndicator bbUpper = new BollingerBandsUpperIndicator(bbMiddle, sd15m);

        double currentClose15m = close15m.getValue(last15mIdx).doubleValue();
        double currentOpen15m = series15m.getBar(last15mIdx).getOpenPrice().doubleValue();
        double upperBand15m = bbUpper.getValue(last15mIdx).doubleValue();

        // Verificamos "Alta Volatilidad": La vela de 15m debe ser verde y estar tocando
        // o rompiendo la Banda de Bollinger superior (margen de tolerancia del 0.2%)
        boolean isBollingerConfirmation = (currentClose15m > currentOpen15m) && (currentClose15m >= (upperBand15m * 0.998));

        return isBollingerConfirmation;
    }

    @Override
    public double calculateTP(double entryPrice) {
        // Rentabilidad objetivo del +5% sobre el activo (Ajustable a tu gusto)
        return Math.round((entryPrice * 1.05) * 100.0) / 100.0;
    }

    @Override
    public double calculateSL(double entryPrice, String ticker) {
        // Riesgo máximo del -3% sobre el activo (Ajustable a tu gusto)
        return Math.round((entryPrice * 0.97) * 100.0) / 100.0;
    }

    @Override
    public String getName() {
        return "C1_SQUEEZE_CALL";
    }
}