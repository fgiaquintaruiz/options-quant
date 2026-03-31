package com.fgiaquinta.optionsquant.engine;

import com.fgiaquinta.optionsquant.analyzers.VolatilityAnalyzer;
import com.fgiaquinta.optionsquant.services.IbkrService;
import com.fgiaquinta.optionsquant.services.TelegramService;
import com.fgiaquinta.optionsquant.utils.ConfigLoader;
import org.ta4j.core.BarSeries;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class TradeManager {
    private final IbkrService ibkrService;
    private final MarketRadar marketRadar;
    private final AccountManager accountManager;

    // Memory map for Staircase Re-entry logic
    private final Map<String, Double> lastExits = new ConcurrentHashMap<>();

    public TradeManager(IbkrService ibkrService, MarketRadar marketRadar, AccountManager accountManager) {
        this.ibkrService = ibkrService;
        this.marketRadar = marketRadar;
        this.accountManager = accountManager;
    }

    public void evaluateSignal(String ticker, String strategyName, double price, BarSeries series1d) {
        // 1. Verify capital and concurrent trade limits
        if (!accountManager.canOpenNewTrade()) return;

        // 2. Fundamental Filter: Is it on the radar or base list?
        if (!marketRadar.isHot(ticker) && !ConfigLoader.getConfig().ibkr.tickers.contains(ticker)) return;

        String key = ticker + "_" + strategyName;
        boolean isCall = strategyName.contains("CALL");

        // 3. Re-entry Price Memory Logic
        Double lastExit = lastExits.get(key);
        if (lastExit != null) {
            if (isCall && price <= lastExit) return;
            if (!isCall && price >= lastExit) return;
        }

        // 4. Dynamic TP/SL using ATR
        double atr = VolatilityAnalyzer.calculateATR(series1d, 14);
        if (atr == 0) atr = price * 0.02;

        double tpDist = atr * ConfigLoader.getConfig().getParam("global", "tpAtrMultiplier");
        double slDist = atr * ConfigLoader.getConfig().getParam("global", "slAtrMultiplier");

        double tp = isCall ? price + tpDist : price - tpDist;
        double sl = isCall ? price - slDist : price + slDist;

        tp = Math.round(tp * 100.0) / 100.0;
        sl = Math.round(sl * 100.0) / 100.0;

        // 5. Position Sizing via Kelly/Risk rules
        int qty = accountManager.calculateQuantity(price, sl);
        if (qty < 1) return;

        // 6. Execution Routing
        if (ConfigLoader.getConfig().ibkr.autoExecute) {
            // Autonomous Mode
            ibkrService.placeOrder(ticker, "BUY", qty, price, tp, sl, strategyName);
            accountManager.addActiveTrade();
            lastExits.put(key, tp);
            System.out.println("🚀 Auto-executing trade for " + ticker);
        } else {
            // Manual Confirmation Mode
            TelegramService.sendSignalConfirmation(ticker, strategyName, price, tp, sl, qty);
            System.out.println("📩 Signal sent to Telegram. Waiting for manual confirmation for " + ticker);
        }
    }
}