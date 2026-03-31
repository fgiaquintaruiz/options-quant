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

    // Memory map to ensure we only re-enter at better prices (Staircase Logic)
    private final Map<String, Double> lastExits = new ConcurrentHashMap<>();

    public TradeManager(IbkrService ibkrService, MarketRadar marketRadar, AccountManager accountManager) {
        this.ibkrService = ibkrService;
        this.marketRadar = marketRadar;
        this.accountManager = accountManager;
    }

    public void evaluateSignal(String ticker, String strategyName, double price, BarSeries series1d) {
        System.out.println("🧐 [TradeManager] Evaluating signal: " + strategyName + " on " + ticker + " at " + price);

        // 1. Check Concurrency Limits
        if (!accountManager.canOpenNewTrade()) {
            System.out.println("🚫 [TradeManager] Trade blocked: Maximum concurrent trades reached.");
            return;
        }

        // 2. Fundamental Filter
        if (!marketRadar.isHot(ticker) && !ConfigLoader.getConfig().ibkr.tickers.contains(ticker)) {
            System.out.println("⏭️ [TradeManager] Skipping " + ticker + ": Not in base watchlist or active radar.");
            return;
        }

        // --- STAIRCASE FILTER: USES THE lastExits MAP ---
        String key = ticker + "_" + strategyName;
        boolean isCall = strategyName.toUpperCase().contains("CALL") || strategyName.toUpperCase().startsWith("C");
        Double lastExitPrice = lastExits.get(key);

        if (lastExitPrice != null) {
            if (isCall && price <= lastExitPrice) {
                System.out.println("⏳ [TradeManager] Staircase Filter: Call entry " + price + " must be above last exit " + lastExitPrice);
                return;
            }
            if (!isCall && price >= lastExitPrice) {
                System.out.println("⏳ [TradeManager] Staircase Filter: Put entry " + price + " must be below last exit " + lastExitPrice);
                return;
            }
        }

        // 3. Option Chain Gatekeeper
        String expiry = ibkrService.getOptimalExpiry(ticker);
        if (expiry == null) {
            System.out.println("⏳ [TradeManager] Signal Ignored: Option data not ready for " + ticker);
            return;
        }

        // 4. Dynamic Targets via ATR
        double atr = VolatilityAnalyzer.calculateATR(series1d, 14);
        if (atr <= 0) atr = price * 0.02;

        double tpDist = atr * ConfigLoader.getConfig().getParam("global", "tpAtrMultiplier");
        double slDist = atr * ConfigLoader.getConfig().getParam("global", "slAtrMultiplier");

        double tp = Math.round((isCall ? price + tpDist : price - tpDist) * 100.0) / 100.0;
        double sl = Math.round((isCall ? price - slDist : price + slDist) * 100.0) / 100.0;

        // 5. Position Sizing
        int qty = accountManager.calculateQuantity(price, sl);
        if (qty < 1) {
            System.out.println("📉 [TradeManager] Aborting: Calculated quantity is 0.");
            return;
        }

        // 6. Routing and Memory Update
        if (ConfigLoader.getConfig().ibkr.autoExecute) {
            System.out.println("🚀 [TradeManager] Auto-executing " + (isCall ? "CALL" : "PUT") + " for " + ticker);
            ibkrService.placeOrder(ticker, isCall ? "CALL" : "PUT", qty, price, tp, sl, strategyName);
            accountManager.addActiveTrade();

            // Update the staircase memory with the projected exit (TP)
            lastExits.put(key, tp);
        } else {
            System.out.println("📩 [TradeManager] Sending interactive alert to Telegram...");
            TelegramService.sendSignalConfirmation(ticker, strategyName, price, tp, sl, qty);

            // Note: In manual mode, lastExits would ideally be updated
            // when the user confirms the execution via the web server.
        }
    }
}