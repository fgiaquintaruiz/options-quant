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
import org.ta4j.core.indicators.statistics.StandardDeviationIndicator;

import java.time.ZonedDateTime;

public class P5ContinuationPutStrategy implements TradingStrategy {
    public static final String CONTINUATION = "continuation";
    private final IbkrService ibkrService;

    public P5ContinuationPutStrategy(IbkrService ibkrService) {
        this.ibkrService = ibkrService;
    }

    @Override
    public boolean isTriggered(String ticker, DataManager dataManager, ZonedDateTime currentTime) {
        // Extraemos las 3 temporalidades necesarias (Contexto, Gatillo y Gap)
        BarSeries series1D = dataManager.getSeries(ticker, TimeFrame.DAY_1);
        BarSeries series1h = dataManager.getSeries(ticker, TimeFrame.HOUR_1);
        BarSeries series15m = dataManager.getSeries(ticker, TimeFrame.MIN_15);

        if (series1D == null || series1h == null || series15m == null ||
                series1D.isEmpty() || series1h.isEmpty() || series15m.isEmpty()) {
            return false;
        }

        // Sincronización temporal de índices
        int idx1D = getIndexForTime(series1D, currentTime);
        int idx1h = getIndexForTime(series1h, currentTime);
        int idx15m = getIndexForTime(series15m, currentTime);

        if (idx1D < 3 || idx1h < 20 || idx15m < 20) return false;

        // =========================================================================
        // REGLA 1: Tendencia claramente alcista (Varios días subiendo)
        // =========================================================================
        ClosePriceIndicator close1D = new ClosePriceIndicator(series1D);
        boolean isUptrend = close1D.getValue(idx1D - 1).isGreaterThan(close1D.getValue(idx1D - 2)) &&
                close1D.getValue(idx1D - 2).isGreaterThan(close1D.getValue(idx1D - 3));

        if (!isUptrend) return false;

        // =========================================================================
        // REGLA 2: Fuerte salto al alza (Gap Up) alejado de la media
        // =========================================================================
        // Nota: Asegúrate de que en config.yaml, putMinGap y putMaxGap para 'continuation'
        // sean valores POSITIVOS para reflejar este salto al alza.
        double minGap = ConfigLoader.getConfig().getDouble(CONTINUATION, "putMinGap");
        double maxGap = ConfigLoader.getConfig().getDouble(CONTINUATION, "putMaxGap");

        double gapPct = GapAnalyzer.getGapPercentage(series1h, idx1h);
        if (gapPct < minGap || gapPct > maxGap) return false;

        // =========================================================================
        // REGLA 3: Vela de 15m completamente fuera del oscilador Bollinger
        // =========================================================================
        ClosePriceIndicator close15m = new ClosePriceIndicator(series15m);
        SMAIndicator sma20_15m = new SMAIndicator(close15m, 20);
        StandardDeviationIndicator sd15m = new StandardDeviationIndicator(close15m, 20);

        double upperBandVal = sma20_15m.getValue(idx15m).doubleValue() + (sd15m.getValue(idx15m).doubleValue() * 2.0);
        double low15m = series15m.getBar(idx15m).getLowPrice().doubleValue();

        // El precio más bajo de la vela debe estar por encima de la banda superior
        boolean completelyOutsideBollinger = low15m > upperBandVal;
        if (!completelyOutsideBollinger) return false;

        // =========================================================================
        // REGLA 4: Confirmación Worden Stochastics cruzando la línea
        // =========================================================================
        double threshold = ConfigLoader.getConfig().getDouble(CONTINUATION, "putWordenThreshold");
        double wStoc = WordenAnalyzer.getWordenStochastic(series15m, idx15m, 12, 3);

        // Confirmamos el cruce a la baja / debilidad del indicador
        boolean wordenConfirmed = wStoc < threshold;

        return wordenConfirmed;
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