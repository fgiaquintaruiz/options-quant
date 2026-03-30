package com.fgiaquinta.optionsquant.strategies;

import com.fgiaquinta.optionsquant.analyzers.GapAnalyzer;
import com.fgiaquinta.optionsquant.analyzers.VolatilityAnalyzer;
import com.fgiaquinta.optionsquant.models.TimeFrame;
import com.fgiaquinta.optionsquant.services.IbkrService;
import com.fgiaquinta.optionsquant.utils.ConfigLoader;
import org.ta4j.core.BarSeries;

/**
 * Estrategia P4: Apertura Bajista tras Gap Up.
 * Busca una reversión (short) tras un salto de precio positivo excesivo.
 */
public class P4OpeningPutStrategy implements TradingStrategy {
    private final IbkrService ibkrService;

    public P4OpeningPutStrategy(IbkrService ibkrService) {
        this.ibkrService = ibkrService;
    }

    @Override
    public boolean isTriggered(int index, BarSeries series1h, BarSeries spySeries) {
        String ticker = series1h.getName().split("_")[0];

        // Se valida en la temporalidad de 15 Minutos
        BarSeries series15m = ibkrService.getSeries(ticker, TimeFrame.MIN_15);
        if (series15m == null || series15m.isEmpty()) return false;

        int idx15m = series15m.getEndIndex();
        if (idx15m < 1) return false;

        // =========================================================================
        // REGLA 1: Baja Volatilidad Previa (Desde config.yaml)
        // =========================================================================
        double maxBandWidth = ConfigLoader.getConfig().getParam("opening", "bandWidth");
        double prevBandWidth = VolatilityAnalyzer.getBollingerBandWidthPct(series15m, idx15m - 1, 20, 2.0);

        if (prevBandWidth > maxBandWidth) return false;

        // =========================================================================
        // REGLA 2: Gap Up (Salto alcista definido en config.yaml)
        // =========================================================================
        double minGap = ConfigLoader.getConfig().getParam("opening", "putMinGap"); // e.g., 1.5
        double maxGap = ConfigLoader.getConfig().getParam("opening", "putMaxGap"); // e.g., 4.0

        double gapPct = GapAnalyzer.getGapPercentage(series15m, idx15m);

        // Verificamos que el Gap sea positivo y esté en el rango
        if (gapPct < minGap || gapPct > maxGap) return false;

        // =========================================================================
        // REGLA 3: Reversión Bajista en Apertura (Vela roja en 15m)
        // =========================================================================
        double currentClose15m = series15m.getBar(idx15m).getClosePrice().doubleValue();
        double currentOpen15m = series15m.getBar(idx15m).getOpenPrice().doubleValue();

        return currentClose15m < currentOpen15m;
    }

    @Override
    public double calculateTP(double entryPrice) {
        double tpMult = ConfigLoader.getConfig().getParam("opening", "tp");
        // Para un PUT, el Take Profit está por debajo del precio de entrada
        return Math.round((entryPrice * (1 - tpMult)) * 100.0) / 100.0;
    }

    @Override
    public double calculateSL(double entryPrice, String ticker) {
        double slMult = ConfigLoader.getConfig().getParam("opening", "sl");
        // Para un PUT, el Stop Loss está por encima del precio de entrada
        return Math.round((entryPrice * (1 + slMult)) * 100.0) / 100.0;
    }

    @Override
    public String getName() {
        return "P4_OPENING_PUT";
    }
}