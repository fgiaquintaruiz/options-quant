package com.fgiaquinta.optionsquant.strategies;

import com.fgiaquinta.optionsquant.analyzers.GapAnalyzer;
import com.fgiaquinta.optionsquant.models.TimeFrame;
import com.fgiaquinta.optionsquant.services.IbkrService;
import com.fgiaquinta.optionsquant.utils.ConfigLoader;
import com.fgiaquinta.optionsquant.utils.DataManager;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.SMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.statistics.StandardDeviationIndicator;

import java.time.ZonedDateTime;

/**
 * Estrategia P4: Apertura Bajista tras Gap Up.
 * Busca una reversión (short) tras un salto de precio positivo excesivo.
 */
public class P4OpeningPutStrategy implements TradingStrategy {
    public static final String OPENING = "opening";
    private final IbkrService ibkrService;

    public P4OpeningPutStrategy(IbkrService ibkrService) {
        this.ibkrService = ibkrService;
    }

    @Override
    public boolean isTriggered(String ticker, DataManager dataManager, ZonedDateTime currentTime) {
        // Extraemos las temporalidades
        BarSeries series1h = dataManager.getSeries(ticker, TimeFrame.HOUR_1);
        BarSeries series15m = dataManager.getSeries(ticker, TimeFrame.MIN_15);

        if (series1h == null || series1h.isEmpty() || series15m == null || series15m.isEmpty()) {
            return false;
        }

        // Sincronización temporal de índices
        int idx1h = getIndexForTime(series1h, currentTime);
        int idx15m = getIndexForTime(series15m, currentTime);

        if (idx1h < 20 || idx15m < 20) return false;

        // =========================================================================
        // REGLA 1: Gap Up Excesivo (Salto fuerte al alza)
        // =========================================================================
        double minGap = ConfigLoader.getConfig().getDouble(OPENING, "putMinGap");
        double maxGap = ConfigLoader.getConfig().getDouble(OPENING, "putMaxGap");

        // Calculamos el Gap usando tu analizador en la gráfica de 1H o Diaria
        double gapPct = GapAnalyzer.getGapPercentage(series1h, idx1h);
        if (gapPct < minGap || gapPct > maxGap) return false;

        // =========================================================================
        // REGLA 2: Alejamiento de la Media Móvil (Bollinger Bands en 15m)
        // =========================================================================
        ClosePriceIndicator close15m = new ClosePriceIndicator(series15m);
        SMAIndicator sma20_15m = new SMAIndicator(close15m, 20);
        StandardDeviationIndicator sd15m = new StandardDeviationIndicator(close15m, 20);

        double sma20Val = sma20_15m.getValue(idx15m).doubleValue();
        double sdVal = sd15m.getValue(idx15m).doubleValue();
        double upperBandVal = sma20Val + (sdVal * 2.0); // Banda superior clásica

        double currentOpen15m = series15m.getBar(idx15m).getOpenPrice().doubleValue();

        // La vela de 15m debe abrir por encima o muy cerca de la Banda de Bollinger Superior
        boolean outsideBollinger = currentOpen15m >= upperBandVal;

        // =========================================================================
        // REGLA 3: Confirmación de Reversión Bajista (Vela Roja)
        // =========================================================================
        double currentClose15m = close15m.getValue(idx15m).doubleValue();
        boolean isRedCandle = currentClose15m < currentOpen15m;

        return outsideBollinger && isRedCandle;
    }

    // Helper method para la sincronización temporal
    private int getIndexForTime(BarSeries series, ZonedDateTime time) {
        for (int i = series.getEndIndex(); i >= Math.max(0, series.getEndIndex() - 500); i--) {
            if (!series.getBar(i).getEndTime().isAfter(time)) {
                return i;
            }
        }
        return -1;
    }
}