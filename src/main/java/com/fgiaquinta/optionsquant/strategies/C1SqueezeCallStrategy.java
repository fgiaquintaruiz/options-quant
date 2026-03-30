package com.fgiaquinta.optionsquant.strategies;

import com.fgiaquinta.optionsquant.services.IbkrService;
import com.fgiaquinta.optionsquant.utils.MarketMath;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.*;
import org.ta4j.core.indicators.bollinger.*;
import org.ta4j.core.indicators.helpers.*;
import org.ta4j.core.indicators.statistics.StandardDeviationIndicator;

import java.time.ZoneId;

public class C1SqueezeCallStrategy implements TradingStrategy {
    private final IbkrService ibkrService;
    public C1SqueezeCallStrategy(IbkrService ibkrService) { this.ibkrService = ibkrService; }

    @Override public String getName() { return "C1_SQUEEZE_CALL"; }

    @Override
    public boolean isTriggered(int i, BarSeries series, BarSeries baseline) {
        if (i < 200) return false;

        // 1. Filtro Horario (Evitar apertura movida)
        var time = series.getBar(i).getEndTime().withZoneSameInstant(ZoneId.of("Europe/Madrid"));
        if (time.getHour() == 15 && time.getMinute() <= 45) return false;

        ClosePriceIndicator cp = new ClosePriceIndicator(series);
        VolumeIndicator vol = new VolumeIndicator(series);
        SMAIndicator volMa10 = new SMAIndicator(vol, 10);

        // 2. Filtro de Volumen (15% superior a la media de 10)
        if (vol.getValue(i).doubleValue() < volMa10.getValue(i).doubleValue() * 1.15) return false;

        // 3. Filtro de Cuerpo (Mínimo 60% de convicción)
        double high = series.getBar(i).getHighPrice().doubleValue();
        double low = series.getBar(i).getLowPrice().doubleValue();
        double open = series.getBar(i).getOpenPrice().doubleValue();
        double close = series.getBar(i).getClosePrice().doubleValue();
        double body = Math.abs(close - open);
        double range = high - low;
        if (range > 0 && (body / range) < 0.60) return false;

        // 4. Lógica original de Squeeze
        SMAIndicator sma20 = new SMAIndicator(cp, 20);
        BollingerBandsMiddleIndicator bbMid = new BollingerBandsMiddleIndicator(sma20);
        StandardDeviationIndicator sd20 = new StandardDeviationIndicator(cp, 20);
        BollingerBandsUpperIndicator bbUpper = new BollingerBandsUpperIndicator(bbMid, sd20);

        return close > bbUpper.getValue(i).doubleValue() && MarketMath.calculateRsRank(series, baseline, i) > 80;
    }

    @Override
    public double calculateTP(double entryPrice) {
        return entryPrice + 1.50; // Objetivo fijo de $1.50
    }

    @Override
    public double calculateSL(double entryPrice, String ticker) {
        // SL dinamico por volatilidad del activo
        double stopMove = (ticker.equals("NVDA") || ticker.equals("TSLA")) ? 4.50 : 3.00;
        return entryPrice - stopMove;
    }
}