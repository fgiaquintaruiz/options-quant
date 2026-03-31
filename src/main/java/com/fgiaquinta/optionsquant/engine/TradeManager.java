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
        System.out.println("🧐 [TradeManager] Evaluating signal: " + strategyName + " on " + ticker + " at " + price);

        // 1. Check concurrency limits
        if (!accountManager.canOpenNewTrade()) {
            System.out.println("🚫 [TradeManager] Trade blocked: Maximum concurrent trades (" +
                    ConfigLoader.getConfig().risk.maxConcurrentTrades + ") reached.");
            return;
        }

        // 2. Fundamental Filter (Watchlist/Radar)
        if (!marketRadar.isHot(ticker) && !ConfigLoader.getConfig().ibkr.tickers.contains(ticker)) {
            System.out.println("⏭️ [TradeManager] Skipping " + ticker + ": Not in active watchlist or radar.");
            return;
        }

        // 3. Staircase Re-entry Logic
        String key = ticker + "_" + strategyName;
        // Detects both "c1_squeeze" and "CALL" naming conventions
        boolean isCall = strategyName.toUpperCase().startsWith("C") || strategyName.toUpperCase().contains("CALL");

        Double lastExit = lastExits.get(key);
        if (lastExit != null) {
            if (isCall && price <= lastExit) {
                System.out.println("⏳ [TradeManager] Staircase Filter: Price must be above last exit (" + lastExit + ") for Call re-entry.");
                return;
            }
            if (!isCall && price >= lastExit) {
                System.out.println("⏳ [TradeManager] Staircase Filter: Price must be below last exit (" + lastExit + ") for Put re-entry.");
                return;
            }
        }

        // 4. Dynamic Targets via ATR
        double atr = VolatilityAnalyzer.calculateATR(series1d, 14);
        if (atr <= 0) {
            atr = price * 0.02; // Safety fallback: 2% of price
        }

        double tpDist = atr * ConfigLoader.getConfig().getParam("global", "tpAtrMultiplier");
        double slDist = atr * ConfigLoader.getConfig().getParam("global", "slAtrMultiplier");

        double tp = isCall ? price + tpDist : price - tpDist;
        double sl = isCall ? price - slDist : price + slDist;

        // Standardize to 2 decimal places for clean orders
        tp = Math.round(tp * 100.0) / 100.0;
        sl = Math.round(sl * 100.0) / 100.0;

        // 5. Position Sizing
        int qty = accountManager.calculateQuantity(price, sl);
        if (qty < 1) {
            System.out.println("📉 [TradeManager] Aborting: Calculated quantity is 0 (Risk is too high for current capital).");
            return;
        }

        // 6. Option Chain Gatekeeper (Prevents broken Telegram alerts/orders)
        String expiry = ibkrService.getOptimalExpiry(ticker);
        if (expiry == null) {
            System.out.println("⏳ [TradeManager] Signal Ignored: Waiting for IBKR to finish loading option chains for " + ticker + "...");
            return;
        }

        // 7. Final Routing
        if (ConfigLoader.getConfig().ibkr.autoExecute) {
            System.out.println("🚀 [TradeManager] Auto-executing " + (isCall ? "CALL" : "PUT") + " trade for " + ticker);
            // Corrected: Passing "CALL"/"PUT" instead of "BUY" to match IbkrService signature
            ibkrService.placeOrder(ticker, isCall ? "CALL" : "PUT", qty, price, tp, sl, strategyName);
            accountManager.addActiveTrade();
            lastExits.put(key, tp);
        } else {
            System.out.println("📩 [TradeManager] Signal passed all filters. Sending alert to Telegram...");
            TelegramService.sendSignalConfirmation(ticker, strategyName, price, tp, sl, qty);
        }
    }
}