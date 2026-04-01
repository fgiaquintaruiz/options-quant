package com.fgiaquinta.optionsquant.engine;

import com.fgiaquinta.optionsquant.analyzers.VolatilityAnalyzer;
import com.fgiaquinta.optionsquant.models.TimeFrame;
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
    private final PreMarketRoutine preMarket;


    // Memory map to ensure we only re-enter at better prices (Staircase Logic)
    private final Map<String, Double> lastExits = new ConcurrentHashMap<>();

    public TradeManager(IbkrService ibkrService, MarketRadar marketRadar, AccountManager accountManager, PreMarketRoutine preMarket) {
        this.ibkrService = ibkrService;
        this.marketRadar = marketRadar;
        this.accountManager = accountManager;
        this.preMarket = preMarket;
    }

    public void evaluateSignal(String ticker, String strategyName, double price) {
        System.out.println("🧐 [TradeManager] evaluateSignal entered for: " + strategyName + " on " + ticker + " at " + price);

        // Determine direction based on strategy name
        boolean isCall = strategyName.toLowerCase().contains("call") || strategyName.toLowerCase().contains("long");

        // 1. MACRO ENVIRONMENT & AI SENTIMENT CHECK (Calling the Radar)
        if (!marketRadar.isEnvironmentFavorable(isCall)) {
            System.out.println("🛑 [TradeManager] Trade Blocked: MarketRadar or Gemini detects unfavorable macro conditions.");
            return;
        }

        // 2. Check Concurrency Limits
        if (!accountManager.canOpenNewTrade()) {
            System.out.println("🚫 [TradeManager] Trade blocked: Maximum concurrent trades reached.");
            return;
        }

        // Get daily series for ATR
        org.ta4j.core.BarSeries dailySeries = ibkrService.getSeries(ticker, com.fgiaquinta.optionsquant.models.TimeFrame.DAY_1);

        double atr;
        if (dailySeries == null) {
            System.out.println("⚠️ [TradeManager] Warning: Daily series is NULL for " + ticker + ". Falling back to 2% fixed ATR.");
            atr = price * 0.02;
        } else if (dailySeries.isEmpty()) {
            System.out.println("⚠️ [TradeManager] Warning: Daily series is EMPTY for " + ticker + ". Falling back to 2% fixed ATR.");
            atr = price * 0.02;
        } else {
            atr = VolatilityAnalyzer.calculateATR(dailySeries, 14);
            System.out.println("📊 [TradeManager] Calculated ATR(14): " + atr);
        }

        // Fetch the AI-optimized settings using the exact composite key used in PreMarketRoutine
        String compositeKey = ticker + "_" + strategyName;
        com.fgiaquinta.optionsquant.models.OptimizationResult aiParams = preMarket.getOverridesFor(compositeKey);

        double tpDist;
        double slDist;

        if (aiParams != null) {
            System.out.println("🧠 [TradeManager] Applying AI-optimized parameters for " + ticker + " (" + strategyName + ")");
            // Access the public variables directly instead of getters
            tpDist = atr * aiParams.recommendedTpAtr;
            slDist = atr * aiParams.recommendedSlAtr;
        } else {
            // Fallback to the standard config.yaml if the AI has no overrides
            tpDist = atr * com.fgiaquinta.optionsquant.utils.ConfigLoader.getConfig().getParam("global", "tpAtrMultiplier");
            slDist = atr * com.fgiaquinta.optionsquant.utils.ConfigLoader.getConfig().getParam("global", "slAtrMultiplier");
        }

        double tp = Math.round((isCall ? price + tpDist : price - tpDist) * 100.0) / 100.0;
        double sl = Math.round((isCall ? price - slDist : price + slDist) * 100.0) / 100.0;

        System.out.println("🎯 [TradeManager] Levels Calculated -> TP: " + tp + " | SL: " + sl);

        int qty = accountManager.calculateQuantity(price, sl);
        System.out.println("⚖️ [TradeManager] Calculated Position Qty: " + qty);

        if (qty < 1) {
            System.out.println("📉 [TradeManager] Aborting: Quantity is 0. Risk is too high or account equity is too low.");
            return;
        }

        if (com.fgiaquinta.optionsquant.utils.ConfigLoader.getConfig().ibkr.autoExecute) {
            System.out.println("🚀 [TradeManager] Executing via IBKR -> " + (isCall ? "CALL" : "PUT") + " | Qty: " + qty);
            ibkrService.placeOrder(ticker, isCall ? "CALL" : "PUT", qty, price, tp, sl, strategyName);
            accountManager.addActiveTrade();
        } else {
            System.out.println("📩 [TradeManager] Auto-execute is false. Sending Telegram alert.");
            com.fgiaquinta.optionsquant.services.TelegramService.sendSignalConfirmation(ticker, strategyName, price, tp, sl, qty);
        }
    }
}