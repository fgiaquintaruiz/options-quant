package com.fgiaquinta.optionsquant.utils;

import com.fgiaquinta.optionsquant.analyzers.VolatilityAnalyzer;
import com.fgiaquinta.optionsquant.models.TimeFrame;
import com.fgiaquinta.optionsquant.models.TradePlan;
import org.ta4j.core.BarSeries;

import java.time.ZonedDateTime;

public class RiskCalculator {

    // Este es el CEREBRO. Ahora extrae la volatilidad macro de 1 Hora, no el "ruido" de 5 minutos.
    public static TradePlan generatePlan(DataManager dataManager, String ticker, ZonedDateTime entryTime, boolean isCall, double entryPrice) {

        // 1. Sacamos la gráfica de 1 Hora (Contexto)
        BarSeries series1h = dataManager.getSeries(ticker, TimeFrame.HOUR_1);
        int index1h = getIndexForTime(series1h, entryTime);

        if (index1h < 0) index1h = series1h.getEndIndex(); // Fallback de seguridad

        // 2. Calculamos el ATR en la temporalidad de 1H (Esto da márgenes reales de $1, $2 o $5)
        double atr = VolatilityAnalyzer.calculateATR(series1h, 14, index1h);

        // 3. Leemos la configuración
        double tpMultiplier = ConfigLoader.getConfig().getDouble("risk", "tpMultiplier");
        double slMultiplier = ConfigLoader.getConfig().getDouble("risk", "slMultiplier");

        double tpDist = atr * tpMultiplier;
        double slDist = atr * slMultiplier;

        // Protección extra: Si el ATR es diminuto (mercado plano), le damos un respiro mínimo de $0.50
        if (tpDist < 0.5) tpDist = 0.5;
        if (slDist < 0.5) slDist = 0.5;

        // 4. Calculamos niveles matemáticos finales
        double tp = Math.round((isCall ? entryPrice + tpDist : entryPrice - tpDist) * 100.0) / 100.0;
        double sl = Math.round((isCall ? entryPrice - slDist : entryPrice + slDist) * 100.0) / 100.0;

        return new TradePlan(entryPrice, tp, sl, isCall, java.time.LocalTime.of(15, 55));
    }

    // Método auxiliar para sincronizar la hora
    private static int getIndexForTime(BarSeries series, ZonedDateTime time) {
        for (int i = series.getEndIndex(); i >= Math.max(0, series.getEndIndex() - 500); i--) {
            if (!series.getBar(i).getEndTime().isAfter(time)) {
                return i;
            }
        }
        return -1;
    }
}