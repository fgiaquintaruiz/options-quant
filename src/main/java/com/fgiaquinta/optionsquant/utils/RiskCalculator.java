package com.fgiaquinta.optionsquant.utils;

import com.fgiaquinta.optionsquant.analyzers.VolatilityAnalyzer;
import com.fgiaquinta.optionsquant.models.TimeFrame;
import com.fgiaquinta.optionsquant.models.TradePlan;
import org.ta4j.core.BarSeries;

import java.time.ZonedDateTime;

public class RiskCalculator {

    // Este es el CEREBRO. Ahora extrae la volatilidad macro de 1 Hora, no el "ruido" de 5 minutos.
    public static TradePlan generatePlan(DataManager dataManager, String ticker, ZonedDateTime entryTime, boolean isCall, double entryPrice) {

        BarSeries series1h = dataManager.getSeries(ticker, TimeFrame.HOUR_1);
        int index1h = getIndexForTime(series1h, entryTime);
        if (index1h < 0) index1h = series1h.getEndIndex();

        double atr = VolatilityAnalyzer.calculateATR(series1h, 14, index1h);

        double tpMultiplier = ConfigLoader.getConfig().getDouble("risk", "tpMultiplier");
        double slMultiplier = ConfigLoader.getConfig().getDouble("risk", "slMultiplier");

        double tpDist = atr * tpMultiplier;
        double slDist = atr * slMultiplier;

        // 👉 NUEVA LÓGICA: Tope de movimiento para Opciones (Max 1% de la acción)
        // Un movimiento del 0.8% - 1% en la acción suele pagar un +15% a +25% en el contrato de opciones.
        double maxTargetPct = 0.009; // 0.9% del precio de la acción
        double maxTpDist = entryPrice * maxTargetPct;

        // Si el ATR multiplicador es demasiado ambicioso, lo recortamos para salir rápido.
        if (tpDist > maxTpDist) {
            tpDist = maxTpDist;
        }

        if (tpDist < 0.25) tpDist = 0.25; // Respiro mínimo para penny stocks
        if (slDist < 0.25) slDist = 0.25;

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