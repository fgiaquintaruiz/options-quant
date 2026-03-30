package com.fgiaquinta.optionsquant.strategies;

import com.fgiaquinta.optionsquant.services.IbkrService;
import com.fgiaquinta.optionsquant.utils.MarketMath;
import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.*;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import java.time.ZoneId;

public class C2TrendCallStrategy implements TradingStrategy {
    private final IbkrService ibkr;
    public C2TrendCallStrategy(IbkrService ibkr) { this.ibkr = ibkr; }

    @Override public String getName() { return "C2_TREND_CALL"; }

    @Override
    public boolean isTriggered(int i, BarSeries s1h, BarSeries spy) {
        if (i < 200) return false;

        var time = s1h.getBar(i).getEndTime().withZoneSameInstant(ZoneId.of("Europe/Madrid"));
        if (time.getHour() == 15) return false;

        ClosePriceIndicator cp = new ClosePriceIndicator(s1h);
        double c = cp.getValue(i).doubleValue();
        double e8 = new EMAIndicator(cp, 8).getValue(i).doubleValue();
        double s20 = new SMAIndicator(cp, 20).getValue(i).doubleValue();
        double s50 = new SMAIndicator(cp, 50).getValue(i).doubleValue();

        // Filtro de cuerpo (60% de conviccion)
        Bar b = s1h.getBar(i);
        double body = Math.abs(b.getClosePrice().doubleValue() - b.getOpenPrice().doubleValue());
        double range = b.getHighPrice().doubleValue() - b.getLowPrice().doubleValue();
        if (range > 0 && (body / range) < 0.60) return false;

        return c > e8 && s20 > s50 && MarketMath.calculateRsRank(s1h, spy, i) > 75;
    }

    @Override
    public double calculateTP(double entryPrice) {
        return entryPrice + 1.50; // Objetivo de $1.50
    }

    @Override
    public double calculateSL(double entryPrice, String ticker) {
        // SL dinámico: $4.50 para NVDA/TSLA, $3.00 para el resto
        double stopMove = (ticker.equals("NVDA") || ticker.equals("TSLA")) ? 4.50 : 3.00;
        return entryPrice - stopMove;
    }
}