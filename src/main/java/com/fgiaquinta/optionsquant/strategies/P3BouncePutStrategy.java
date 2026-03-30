package com.fgiaquinta.optionsquant.strategies;

import com.fgiaquinta.optionsquant.models.TimeFrame;
import com.fgiaquinta.optionsquant.services.IbkrService;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.SMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;

public class P3BouncePutStrategy implements TradingStrategy {
    private final IbkrService ibkrService;

    public P3BouncePutStrategy(IbkrService ibkrService) {
        this.ibkrService = ibkrService;
    }

    @Override
    public boolean isTriggered(int index, BarSeries series1h, BarSeries spySeries) {
        String ticker = series1h.getName().split("_")[0];

        double currentClose = series1h.getBar(index).getClosePrice().doubleValue();
        double currentOpen = series1h.getBar(index).getOpenPrice().doubleValue();
        double currentHigh = series1h.getBar(index).getHighPrice().doubleValue();

        // =========================================================================
        // REGLA 4: Entrada (1 Hora) - Vela de confirmación bajista
        // =========================================================================
        boolean isBearishCandle = currentClose < currentOpen;
        if (!isBearishCandle) return false;

        // =========================================================================
        // REGLA 1: Contexto (1 Hora) - Tendencia principal alcista
        // =========================================================================
        ClosePriceIndicator close1h = new ClosePriceIndicator(series1h);
        SMAIndicator sma20_1h = new SMAIndicator(close1h, 20);

        // El precio debe venir operando en la mitad superior de Bollinger (sobre SMA20)
        if (currentClose <= sma20_1h.getValue(index).doubleValue()) {
            return false;
        }

        // =========================================================================
        // REGLA 2: Aproximación a la Resistencia (Diaria) - Tocando MM20 Diaria
        // =========================================================================
        BarSeries series1d = ibkrService.getSeries(ticker, TimeFrame.DAY_1);
        if (series1d == null || series1d.isEmpty()) return false;

        int lastDailyIdx = series1d.getEndIndex();
        ClosePriceIndicator close1d = new ClosePriceIndicator(series1d);
        SMAIndicator sma20_1d = new SMAIndicator(close1d, 20);

        double dailySma20 = sma20_1d.getValue(lastDailyIdx).doubleValue();

        // Calculamos la proximidad a la MM20 Diaria por arriba (ej. margen del 0.8%)
        // Usamos la "mecha" superior (High) para ver si llegó a tocar la resistencia
        double distanceToDailySma20 = Math.abs(currentHigh - dailySma20) / dailySma20;
        boolean touchesDailyResistance = distanceToDailySma20 <= 0.008;

        // El precio debe tocarla pero respetarla (el cierre actual debe estar POR DEBAJO de la MM20 diaria)
        boolean respectsDailyResistance = currentClose < dailySma20;

        if (!touchesDailyResistance || !respectsDailyResistance) return false;

        // =========================================================================
        // REGLA 3: Verificación del Rebote (15 Minutos)
        // =========================================================================
        BarSeries series15m = ibkrService.getSeries(ticker, TimeFrame.MIN_15);
        if (series15m == null || series15m.isEmpty()) return false;

        int last15mIdx = series15m.getEndIndex();
        double close15mVal = series15m.getBar(last15mIdx).getClosePrice().doubleValue();
        double open15mVal = series15m.getBar(last15mIdx).getOpenPrice().doubleValue();

        // En 15m, el precio debe confirmar el rebote bajando (vela roja)
        // y mantenerse estrictamente por debajo de la resistencia Diaria.
        boolean isBouncingDown15m = (close15mVal < open15mVal) && (close15mVal < dailySma20);

        return isBouncingDown15m;
    }

    @Override
    public double calculateTP(double entryPrice) {
        // En rebotes diarios hacia abajo, buscamos un movimiento fuerte (-6%)
        return Math.round((entryPrice * 0.94) * 100.0) / 100.0;
    }

    @Override
    public double calculateSL(double entryPrice, String ticker) {
        // Stop loss ajustado por si rompe la resistencia diaria hacia arriba (+2.5%)
        return Math.round((entryPrice * 1.025) * 100.0) / 100.0;
    }

    @Override
    public String getName() {
        return "P3_BOUNCE_PUT";
    }
}