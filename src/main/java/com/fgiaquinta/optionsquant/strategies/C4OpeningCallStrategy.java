package com.fgiaquinta.optionsquant.strategies;

import com.fgiaquinta.optionsquant.analyzers.GapAnalyzer;
import com.fgiaquinta.optionsquant.analyzers.VolatilityAnalyzer;
import com.fgiaquinta.optionsquant.models.TimeFrame;
import com.fgiaquinta.optionsquant.services.IbkrService;
import org.ta4j.core.BarSeries;

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
        // REGLA 1: Baja Volatilidad Previa (Squeeze en la última vela de ayer)
        // =========================================================================
        // Miramos la vela anterior (ayer al cierre) para ver si las bandas estaban estrechas (< 1.2%)
        double prevBandWidth = VolatilityAnalyzer.getBollingerBandWidthPct(series15m, idx15m - 1, 20, 2.0);
        if (prevBandWidth > 1.2) return false;

        // =========================================================================
        // REGLA 2: Gap Down Extremo (Salto hacia abajo > 1.5%)
        // =========================================================================
        double gapPct = GapAnalyzer.getGapPercentage(series15m, idx15m);

        // Buscamos un Gap Down (valor negativo), por ejemplo menor a -1.5%
        if (gapPct > -1.5) return false;

        // =========================================================================
        // REGLA 3: Reversión Alcista en Apertura
        // =========================================================================
        double currentClose15m = series15m.getBar(idx15m).getClosePrice().doubleValue();
        double currentOpen15m = series15m.getBar(idx15m).getOpenPrice().doubleValue();

        // La primera vela de 15m debe ser verde (fuerza de compra tras el gap)
        boolean isBullishReversal = currentClose15m > currentOpen15m;

        return isBullishReversal;
    }

    @Override
    public double calculateTP(double entryPrice) {
        // En Gaps buscamos el "Gap Fill" (retorno al cierre de ayer), aprox +4%
        return Math.round((entryPrice * 1.04) * 100.0) / 100.0;
    }

    @Override
    public double calculateSL(double entryPrice, String ticker) {
        // Stop loss corto (-2%) porque si el Gap sigue bajando, la tesis se anula
        return Math.round((entryPrice * 0.98) * 100.0) / 100.0;
    }

    @Override
    public String getName() {
        return "c4_opening_call";
    }
}