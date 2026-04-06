package com.fgiaquinta.optionsquant.strategies;

import com.fgiaquinta.optionsquant.analyzers.GapAnalyzer;
import com.fgiaquinta.optionsquant.analyzers.WordenAnalyzer;
import com.fgiaquinta.optionsquant.models.TimeFrame;
import com.fgiaquinta.optionsquant.services.IbkrService;
import com.fgiaquinta.optionsquant.utils.ConfigLoader;
import com.fgiaquinta.optionsquant.utils.DataManager;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.SMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;

import java.time.ZonedDateTime;

public class C5ContinuationCallStrategy implements TradingStrategy {
    public static final String CONTINUATION = "continuation";

    private final IbkrService ibkrService;

    public C5ContinuationCallStrategy(IbkrService ibkrService) {
        this.ibkrService = ibkrService;
    }

    @Override
    public boolean isTriggered(String ticker, DataManager dataManager, ZonedDateTime currentTime) {
        // 1. Extraemos las gráficas necesarias desde la caché central (DataManager)
        BarSeries series1h = dataManager.getSeries(ticker, TimeFrame.HOUR_1);
        BarSeries series15m = dataManager.getSeries(ticker, TimeFrame.MIN_15);

        if (series1h == null || series1h.isEmpty() || series15m == null || series15m.isEmpty()) {
            return false;
        }

        // 2. Sincronización temporal de índices (Evita el "Look-Ahead Bias" en backtesting)
        int idx1h = getIndexForTime(series1h, currentTime);
        int idx15m = getIndexForTime(series15m, currentTime);

        if (idx1h < 1 || idx15m < 20) return false;

        // =========================================================================
        // REGLA 1: Filtrado de Gap (Valores desde config.yaml)
        // =========================================================================
        double minGap = ConfigLoader.getConfig().getDouble(CONTINUATION, "callMinGap");
        double maxGap = ConfigLoader.getConfig().getDouble(CONTINUATION, "callMaxGap");

        double gapPct = GapAnalyzer.getGapPercentage(series1h, idx1h);
        if (gapPct < minGap || gapPct > maxGap) return false;

        // =========================================================================
        // REGLA 2: Momentum fuerte en 1h (Worden Stochastics)
        // =========================================================================
        double threshold = ConfigLoader.getConfig().getDouble(CONTINUATION, "callWordenThreshold");
        double wStoc = WordenAnalyzer.getWordenStochastic(series1h, idx1h, 12, 3);

        if (wStoc < threshold) return false;

        // =========================================================================
        // REGLA 3: Confirmación de tendencia en 15m (Efecto Imán / Precio > SMA20)
        // =========================================================================
        ClosePriceIndicator close15m = new ClosePriceIndicator(series15m);
        SMAIndicator sma20 = new SMAIndicator(close15m, 20);

        double currentPrice15m = close15m.getValue(idx15m).doubleValue();
        double sma20Val = sma20.getValue(idx15m).doubleValue();

        return currentPrice15m > sma20Val;
    }

    // Helper method para la sincronización temporal (Por si tu interfaz no lo provee por defecto)
    private int getIndexForTime(BarSeries series, ZonedDateTime time) {
        for (int i = series.getEndIndex(); i >= Math.max(0, series.getEndIndex() - 500); i--) {
            if (!series.getBar(i).getEndTime().isAfter(time)) {
                return i;
            }
        }
        return -1;
    }
}