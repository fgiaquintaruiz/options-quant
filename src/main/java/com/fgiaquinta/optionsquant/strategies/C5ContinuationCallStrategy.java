package com.fgiaquinta.optionsquant.strategies;

import com.fgiaquinta.optionsquant.analyzers.GapAnalyzer;
import com.fgiaquinta.optionsquant.analyzers.WordenAnalyzer;
import com.fgiaquinta.optionsquant.models.TimeFrame;
import com.fgiaquinta.optionsquant.services.IbkrService;
import com.fgiaquinta.optionsquant.utils.ConfigLoader;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.SMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;

public class C5ContinuationCallStrategy implements TradingStrategy {
    public static final String CONTINUATION = "continuation";

    private final IbkrService ibkrService;

    public C5ContinuationCallStrategy(IbkrService ibkrService) {
        this.ibkrService = ibkrService;
    }

    @Override
    public boolean isTriggered(int index, BarSeries series1h, BarSeries spySeries) {
        String ticker = series1h.getName().split("_")[0];

        // REGLA 1: Filtrado de Gap (Valores desde config.yaml)
        double minGap = ConfigLoader.getConfig().getParam(CONTINUATION, "callMinGap");
        double maxGap = ConfigLoader.getConfig().getParam(CONTINUATION, "callMaxGap");

        double gapPct = GapAnalyzer.getGapPercentage(series1h, index);
        if (gapPct < minGap || gapPct > maxGap) return false;

        // REGLA 2: Worden Stochastic (Percentil de fuerza desde config.yaml)
        double threshold = ConfigLoader.getConfig().getParam(CONTINUATION, "callWordenThreshold");
        double wStoc = WordenAnalyzer.getWordenStochastic(series1h, index, 12, 3);

        if (wStoc < threshold) return false;

        // REGLA 3: Confirmación de tendencia en 15m (Precio > SMA20)
        BarSeries series15m = ibkrService.getSeries(ticker, TimeFrame.MIN_15);
        if (series15m == null || series15m.isEmpty()) return false;

        int idx15m = series15m.getEndIndex();
        ClosePriceIndicator close15m = new ClosePriceIndicator(series15m);
        SMAIndicator sma20 = new SMAIndicator(close15m, 20);

        double currentPrice15m = series15m.getBar(idx15m).getClosePrice().doubleValue();
        double sma20Val = sma20.getValue(idx15m).doubleValue();

        return currentPrice15m > sma20Val;
    }

    @Override
    public double calculateTP(double entryPrice) {
        // Multiplicador de Take Profit desde config.yaml (ej: 0.045)
        double tpMult = ConfigLoader.getConfig().getParam(CONTINUATION, "tp");
        return Math.round((entryPrice * (1 + tpMult)) * 100.0) / 100.0;
    }

    @Override
    public double calculateSL(double entryPrice, String ticker) {
        // Multiplicador de Stop Loss desde config.yaml (ej: 0.025)
        double slMult = ConfigLoader.getConfig().getParam(CONTINUATION, "sl");
        return Math.round((entryPrice * (1 - slMult)) * 100.0) / 100.0;
    }

    @Override
    public String getName() {
        return "c5_continuation_call";
    }
}