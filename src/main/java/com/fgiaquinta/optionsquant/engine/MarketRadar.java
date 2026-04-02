package com.fgiaquinta.optionsquant.engine;

import com.fgiaquinta.optionsquant.services.IbkrService;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.SMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class MarketRadar {
    private boolean forceMacroFavorable = false;

    private final List<String> hotTickers = new CopyOnWriteArrayList<>();
    private final AiNewsInterpreter newsInterpreter = new AiNewsInterpreter();
    private final IbkrService ibkrService;

    // Inject IbkrService to fetch macro data
    public MarketRadar(IbkrService ibkrService) {
        this.ibkrService = ibkrService;
    }

    public void addHotTicker(String symbol) {
        if (!hotTickers.contains(symbol)) {
            hotTickers.add(symbol);
            System.out.println("🔭 Added to Radar: " + symbol);
        }
    }

    public void setForceMacroFavorable(boolean force) {
        this.forceMacroFavorable = force;
        System.out.println("🌐 [MarketRadar] Macro Environment Force: " + (force ? "ON (Always Green)" : "OFF"));
    }

    public boolean isHot(String ticker) {
        return hotTickers.contains(ticker);
    }

    public boolean isEnvironmentFavorable(boolean isCall) {
        if (forceMacroFavorable) return true;

        boolean technicalFavorable = checkTechnicalEnvironment(isCall);

        System.out.println("🧠 Asking Gemini for real-time sentiment approval...");
        boolean aiFavorable = newsInterpreter.isSentimentFavorable(isCall);

        if (!aiFavorable) {
            System.out.println("🛑 Gemini blocked this trade due to conflicting news/sentiment.");
        }

        return technicalFavorable && aiFavorable;
    }

    // Real Implementation: Macro Trend Filter
    private boolean checkTechnicalEnvironment(boolean isCall) {
        System.out.println("📊 [MarketRadar] Checking macro technical environment (SPY 50-SMA Trend)...");
        try {
            BarSeries spy = ibkrService.getSeries("SPY", com.fgiaquinta.optionsquant.models.TimeFrame.DAY_1);

            // If we don't have enough data to calculate a 50 SMA, allow trade to proceed safely
            if (spy == null || spy.getBarCount() < 50) {
                System.out.println("⚠️ [MarketRadar] Not enough SPY daily data for SMA. Bypassing technical macro check.");
                return true;
            }

            ClosePriceIndicator closePrice = new ClosePriceIndicator(spy);
            SMAIndicator sma50 = new SMAIndicator(closePrice, 50);

            double currentSpyPrice = closePrice.getValue(spy.getEndIndex()).doubleValue();
            double smaValue = sma50.getValue(spy.getEndIndex()).doubleValue();

            if (isCall) {
                boolean favorable = currentSpyPrice > smaValue;
                if (!favorable) System.out.println("🛑 [MarketRadar] Blocked CALL: SPY is below 50 SMA (Macro Downtrend).");
                return favorable;
            } else {
                boolean favorable = currentSpyPrice < smaValue;
                if (!favorable) System.out.println("🛑 [MarketRadar] Blocked PUT: SPY is above 50 SMA (Macro Uptrend).");
                return favorable;
            }
        } catch (Exception e) {
            System.err.println("❌ [MarketRadar] Error checking environment: " + e.getMessage());
            return true;
        }
    }

}