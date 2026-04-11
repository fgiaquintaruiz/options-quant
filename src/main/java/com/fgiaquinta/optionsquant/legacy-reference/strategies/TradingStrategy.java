package com.fgiaquinta.optionsquant.strategies;

import com.fgiaquinta.optionsquant.utils.DataManager;
import java.time.ZonedDateTime;

public interface TradingStrategy {

    // Devuelve el nombre de la estrategia
    default String getName() {
        return this.getClass().getSimpleName().replace("Strategy", "").toLowerCase();
    }

    /**
     * Evalúa si la estrategia debe disparar una orden.
     * @param ticker El símbolo a operar (ej. "MSFT")
     * @param dataManager La caché central con TODAS las temporalidades
     * @param currentTime La hora actual (En Backtest es la hora de la vela simulada, en Live es null)
     * @return true si se cumplen todas las reglas, false si no.
     */
    boolean isTriggered(String ticker, DataManager dataManager, ZonedDateTime currentTime);
}