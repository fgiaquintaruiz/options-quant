package com.fgiaquinta.optionsquant.strategies;

import com.fgiaquinta.optionsquant.analyzers.GapAnalyzer;
import com.fgiaquinta.optionsquant.analyzers.VolatilityAnalyzer;
import com.fgiaquinta.optionsquant.models.TimeFrame;
import com.fgiaquinta.optionsquant.services.IbkrService;
import com.fgiaquinta.optionsquant.utils.ConfigLoader;
import org.ta4j.core.BarSeries;

/**
 * Estrategia C4: Apertura Alcista tras Gap Down.
 * Busca una reversión tras un salto de precio negativo en la apertura.
 */
public class C4OpeningCallStrategy implements TradingStrategy {
    private final IbkrService ibkrService;

    public C4OpeningCallStrategy(IbkrService ibkrService) {
        this.ibkrService = ibkrService;
    }

    @Override
    public boolean isTriggered(int index, BarSeries series1h, BarSeries spySeries) {
        String ticker = series1h.getName().split("_")[0];

        // Esta estrategia se valida principalmente en 15 Minutos (Apertura)
        BarSeries series15m = ibkrService.getSeries(ticker, TimeFrame.MIN_15);
        if (series15m == null || series15m.isEmpty()) return false;

        int idx15m = series15m.getEndIndex();
        if (idx15m < 1) return false;

        // =========================================================================
        // REGLA 1: Baja Volatilidad Previa (Valor desde config.yaml)
        // =========================================================================
        double maxBandWidth = ConfigLoader.getConfig().getDouble("opening", "bandWidth");
        double prevBandWidth = VolatilityAnalyzer.getBollingerBandWidthPct(series15m, idx15m - 1, 20, 2.0);

        if (prevBandWidth > maxBandWidth) return false;

        // =========================================================================
        // REGLA 2: Gap Down Extremo (Límites desde config.yaml)
        // =========================================================================
        double minGap = ConfigLoader.getConfig().getDouble("opening", "callMinGap"); // e.g., -1.5
        double maxGap = ConfigLoader.getConfig().getDouble("opening", "callMaxGap"); // e.g., -4.0

        double gapPct = GapAnalyzer.getGapPercentage(series15m, idx15m);

        // Verificamos que el Gap esté dentro del rango negativo definido
        if (gapPct > minGap || gapPct < maxGap) return false;

        // =========================================================================
        // REGLA 3: Reversión Alcista en Apertura (Vela verde en 15m)
        // =========================================================================
        double currentClose15m = series15m.getBar(idx15m).getClosePrice().doubleValue();
        double currentOpen15m = series15m.getBar(idx15m).getOpenPrice().doubleValue();

        return currentClose15m > currentOpen15m;
    }

    @Override
    public double calculateTP(double entryPrice) {
        double tpMult = ConfigLoader.getConfig().getDouble("global", "tpAtrMultiplier");
        return Math.round((entryPrice * (1 + tpMult)) * 100.0) / 100.0;
    }

    @Override
    public double calculateSL(double entryPrice, String ticker) {
        double slMult = ConfigLoader.getConfig().getDouble("global", "slAtrMultiplier");
        return Math.round((entryPrice * (1 - slMult)) * 100.0) / 100.0;
    }

    @Override
    public String getName() {
        return "C4_OPENING_CALL";
    }
}