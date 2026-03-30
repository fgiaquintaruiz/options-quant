package com.fgiaquinta.optionsquant.strategies;

import com.fgiaquinta.optionsquant.analyzers.ChannelAnalyzer;
import com.fgiaquinta.optionsquant.models.TimeFrame;
import com.fgiaquinta.optionsquant.services.IbkrService;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.SMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsMiddleIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsLowerIndicator;
import org.ta4j.core.indicators.statistics.StandardDeviationIndicator;

public class P1SqueezePutStrategy implements TradingStrategy {
    private final IbkrService ibkrService;

    public P1SqueezePutStrategy(IbkrService ibkrService) {
        this.ibkrService = ibkrService;
    }

    @Override
    public boolean isTriggered(int index, BarSeries series1h, BarSeries spySeries) {
        String ticker = series1h.getName().split("_")[0];

        // REGLAS 1 y 2: Canal Lateral de 10 días
        if (!ChannelAnalyzer.isSmaLateralChannel(series1h, index, 70, 2.5)) {
            return false;
        }

        // REGLA 3: Señal Bajista (Ruptura del Canal hacia ABAJO en 1H)
        ClosePriceIndicator close1h = new ClosePriceIndicator(series1h);
        SMAIndicator sma20 = new SMAIndicator(close1h, 20);
        SMAIndicator sma40 = new SMAIndicator(close1h, 40);
        SMAIndicator sma100 = new SMAIndicator(close1h, 100);
        SMAIndicator sma200 = new SMAIndicator(close1h, 200);

        double currentClose = close1h.getValue(index).doubleValue();
        double currentOpen = series1h.getBar(index).getOpenPrice().doubleValue();

        // Buscamos el "piso" de las medias móviles
        double minSma = Math.min(Math.min(sma20.getValue(index).doubleValue(), sma40.getValue(index).doubleValue()),
                Math.min(sma100.getValue(index).doubleValue(), sma200.getValue(index).doubleValue()));

        // Vela roja (bajista) y cierre por debajo del piso de todas las medias
        boolean isBearishBreakout = (currentClose < currentOpen) && (currentClose < minSma);

        if (!isBearishBreakout) {
            return false;
        }

        // REGLA 4: Confirmación en temporalidad menor (15 Minutos)
        BarSeries series15m = ibkrService.getSeries(ticker, TimeFrame.MIN_15);
        if (series15m == null || series15m.isEmpty()) {
            return false;
        }

        int last15mIdx = series15m.getEndIndex();
        ClosePriceIndicator close15m = new ClosePriceIndicator(series15m);

        SMAIndicator sma15m20 = new SMAIndicator(close15m, 20);
        BollingerBandsMiddleIndicator bbMiddle = new BollingerBandsMiddleIndicator(sma15m20);
        StandardDeviationIndicator sd15m = new StandardDeviationIndicator(close15m, 20);

        // ATENCIÓN: Para PUT miramos el indicador INFERIOR
        BollingerBandsLowerIndicator bbLower = new BollingerBandsLowerIndicator(bbMiddle, sd15m);

        double currentClose15m = close15m.getValue(last15mIdx).doubleValue();
        double currentOpen15m = series15m.getBar(last15mIdx).getOpenPrice().doubleValue();
        double lowerBand15m = bbLower.getValue(last15mIdx).doubleValue();

        // Verificamos "Alta Volatilidad a la baja": Vela roja tocando o rompiendo Banda Inferior
        boolean isBollingerConfirmation = (currentClose15m < currentOpen15m) && (currentClose15m <= (lowerBand15m * 1.002));

        return isBollingerConfirmation;
    }

    @Override
    public double calculateTP(double entryPrice) {
        // Rentabilidad objetivo del +5% hacia abajo (bajista)
        return Math.round((entryPrice * 0.95) * 100.0) / 100.0;
    }

    @Override
    public double calculateSL(double entryPrice, String ticker) {
        // Riesgo máximo del -3% en contra (hacia arriba)
        return Math.round((entryPrice * 1.03) * 100.0) / 100.0;
    }

    @Override
    public String getName() {
        return "P1_SQUEEZE_PUT";
    }
}