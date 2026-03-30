package com.fgiaquinta.optionsquant.engine;

import com.fgiaquinta.optionsquant.services.IbkrService;
import com.fgiaquinta.optionsquant.strategies.TradingStrategy;
import org.ta4j.core.BarSeries;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class StrategyEngine {
    private final IbkrService ibkrService;
    private final List<TradingStrategy> strategies;
    // Filtro de inventario: Guarda los tickers que ya tienen una orden activa hoy
    private final Set<String> activeOrders = ConcurrentHashMap.newKeySet();

    public StrategyEngine(IbkrService ibkr, List<TradingStrategy> strategies) {
        this.ibkrService = ibkr;
        this.strategies = strategies;
    }

    public void onBarAdded(String ticker, BarSeries series1h) {
        BarSeries spy = ibkrService.getSeries("SPY_1hour");
        if (spy == null || spy.isEmpty()) return;

        int lastIdx = series1h.getEndIndex();

        for (TradingStrategy strategy : strategies) {
            if (strategy.isTriggered(lastIdx, series1h, spy)) {

                // 1. Verificamos si ya está activo
                if (activeOrders.contains(ticker)) {
                    continue;
                }

                double price = series1h.getBar(lastIdx).getClosePrice().doubleValue();
                double tp = strategy.calculateTP(price);
                double sl = strategy.calculateSL(price, ticker);

                // 2. Intentamos enviar la orden
                boolean sent = ibkrService.placeOrder(ticker, "BUY", 10, price, tp, sl, strategy.getName());

                // 3. SOLO SI SE ENVIÓ, bloqueamos el ticker para futuras señales
                if (sent) {
                    activeOrders.add(ticker);
                    System.out.println("✅ Orden confirmada y ticker bloqueado: " + ticker);
                } else {
                    // Opcional: log de aviso de que faltan datos de mercado
                    // System.out.println("⏳ Esperando datos de contrato para " + ticker + "...");
                }
            }
        }
    }

    // Metodo para limpiar el inventario (puedes llamarlo al final del dia)
    public void clearInventory() {
        activeOrders.clear();
    }
}