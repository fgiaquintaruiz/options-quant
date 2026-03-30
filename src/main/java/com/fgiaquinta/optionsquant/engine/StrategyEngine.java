package com.fgiaquinta.optionsquant.engine;

import com.fgiaquinta.optionsquant.services.IbkrService;
import com.fgiaquinta.optionsquant.strategies.TradingStrategy;
import com.fgiaquinta.optionsquant.models.TimeFrame; // IMPORTANTE: Agregar el Enum
import org.ta4j.core.BarSeries;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class StrategyEngine {
    private final IbkrService ibkrService;
    private final List<TradingStrategy> strategies;
    // Inventory filter: Saves tickers that already have an active order today
    private final Set<String> activeOrders = ConcurrentHashMap.newKeySet();

    public StrategyEngine(IbkrService ibkr, List<TradingStrategy> strategies) {
        this.ibkrService = ibkr;
        this.strategies = strategies;
    }

    public void onBarAdded(String ticker, BarSeries series1h) {
        // CORRECCIÓN: Usar la nueva firma Multi-Timeframe
        BarSeries spy = ibkrService.getSeries("SPY", TimeFrame.HOUR_1);
        if (spy == null || spy.isEmpty()) return;

        int lastIdx = series1h.getEndIndex();

        for (TradingStrategy strategy : strategies) {
            if (strategy.isTriggered(lastIdx, series1h, spy)) {

                // 1. Verify if it's already active
                if (activeOrders.contains(ticker)) {
                    continue;
                }

                double price = series1h.getBar(lastIdx).getClosePrice().doubleValue();
                double tp = strategy.calculateTP(price);
                double sl = strategy.calculateSL(price, ticker);

                // 2. Try to send the order
                boolean sent = ibkrService.placeOrder(ticker, "BUY", 10, price, tp, sl, strategy.getName());

                // 3. ONLY IF SENT, block the ticker for future signals
                if (sent) {
                    activeOrders.add(ticker);
                    System.out.println("✅ Orden confirmada y ticker bloqueado: " + ticker);
                } else {
                    // Optional: Warning log for missing market data
                    // System.out.println("⏳ Esperando datos de contrato para " + ticker + "...");
                }
            }
        }
    }

    // Method to clear inventory (call it at the end of the day)
    public void clearInventory() {
        activeOrders.clear();
    }
}