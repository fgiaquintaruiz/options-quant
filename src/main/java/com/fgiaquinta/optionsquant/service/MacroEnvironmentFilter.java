package com.fgiaquinta.optionsquant.service;

import com.fgiaquinta.optionsquant.domain.Candle;
import com.fgiaquinta.optionsquant.domain.TimeFrame;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Macro environment filter using multi-factor analysis:
 * 1. SPY vs 50-SMA distance (trend strength)
 * 2. SPY short-term momentum (5-day trend direction)
 * 3. Market regime classification (not just binary bullish/bearish)
 *
 * Instead of blanket-blocking PUT trades in bullish markets, this filter:
 * - Evaluates the strength of the trend
 * - Detects short-term pullbacks even in bullish regimes
 * - Only blocks trades when conditions are EXTREMELY against them
 *
 * This allows PUT opportunities during pullbacks in bullish markets,
 * and CALL opportunities during bounces in bearish markets.
 */
@Slf4j
@Service
public class MacroEnvironmentFilter {

    private final CandleCsvService csvService;

    // Market regime based on SPY vs 50-SMA
    public enum MarketRegime {
        STRONGLY_BULLISH,   // SPY > 3% above 50-SMA
        BULLISH,            // SPY > 1% above 50-SMA
        NEUTRAL,            // SPY within ±1% of 50-SMA
        BEARISH,            // SPY > 1% below 50-SMA
        STRONGLY_BEARISH    // SPY > 3% below 50-SMA
    }

    // Short-term momentum direction
    public enum ShortTermMomentum {
        RISING,     // 5-day SMA > 10-day SMA (short-term uptrend)
        FALLING,    // 5-day SMA < 10-day SMA (short-term downtrend)
        FLAT        // Nearly equal
    }

    private volatile MarketRegime regime = MarketRegime.NEUTRAL;
    private volatile ShortTermMomentum momentum = ShortTermMomentum.FLAT;
    private volatile double spyPrice = 0;
    private volatile double sma50 = 0;
    private volatile double distanceFromSma50Pct = 0;
    private volatile long lastCheckTime = 0;
    private static final long CHECK_INTERVAL_MS = 15 * 60 * 1000; // Check every 15 minutes

    @Autowired(required = false)
    private TelegramService telegramService;

    public MacroEnvironmentFilter(CandleCsvService csvService) {
        this.csvService = csvService;
    }

    /**
     * Initializes the macro filter on startup by checking SPY trend.
     */
    @PostConstruct
    public void init() {
        log.info("🌐 [Macro Filter] Initializing - multi-factor analysis (50-SMA + short-term momentum)...");
        updateMarketAnalysis();

        // Telegram notification removed - only send during live trading, not backtesting
        // if (telegramService != null && telegramService.isEnabled()) {
        //     String trend = getAnalysisString();
        //     telegramService.sendMacroStatus(trend);
        // }
    }

    /**
     * Checks if macro conditions are acceptable for the trade direction.
     * This is a SOFT filter — it only blocks in EXTREME conditions.
     *
     * @param isCall true for CALL trades, false for PUT trades
     * @return true if macro conditions are acceptable (not extremely against the trade)
     */
    public boolean isMacroFavorable(boolean isCall) {
        // Refresh market analysis if stale
        long now = System.currentTimeMillis();
        if (lastCheckTime == 0 || (now - lastCheckTime) > CHECK_INTERVAL_MS) {
            updateMarketAnalysis();
        }

        if (isCall) {
            return isMacroFavorableForCall();
        } else {
            return isMacroFavorableForPut();
        }
    }

    /**
     * Evaluates if CALL trades are acceptable.
     * Blocks only in STRONGLY_BEARISH regime with falling momentum.
     * Warns (but allows) in BEARISH regime.
     */
    private boolean isMacroFavorableForCall() {
        // STRONGLY_BEARISH + falling momentum = block CALLs (market crashing)
        if (regime == MarketRegime.STRONGLY_BEARISH && momentum == ShortTermMomentum.FALLING) {
            log.warn("🛑 [Macro Filter] BLOCKED CALL: STRONGLY bearish regime ({}% from 50-SMA) + falling momentum",
                    String.format("%+.2f", distanceFromSma50Pct));
            return false;
        }

        // BEARISH + falling momentum = warn but allow (counter-trend bounce plays exist)
        if (regime == MarketRegime.BEARISH && momentum == ShortTermMomentum.FALLING) {
            log.warn("⚠️ [Macro Filter] CAUTION on CALL: Bearish regime ({}%) + falling momentum — counter-trend trade",
                    String.format("%+.2f", distanceFromSma50Pct));
            return true;  // Allow but warn
        }

        // BULLISH or NEUTRAL + rising = favorable
        if ((regime == MarketRegime.BULLISH || regime == MarketRegime.STRONGLY_BULLISH)
                && momentum == ShortTermMomentum.RISING) {
            log.debug("✅ [Macro Filter] FAVORABLE for CALL: {} regime + rising momentum", regime);
        }

        // NEUTRAL = allow (market is ranging, both directions viable)
        if (regime == MarketRegime.NEUTRAL) {
            log.debug("✅ [Macro Filter] NEUTRAL regime — allowing CALL (range-bound market)");
        }

        return true;
    }

    /**
     * Evaluates if PUT trades are acceptable.
     * Blocks only in STRONGLY_BULLISH regime with rising momentum.
     * Warns (but allows) in BULLISH regime — pullbacks happen.
     */
    private boolean isMacroFavorableForPut() {
        // STRONGLY_BULLISH + rising momentum = block PUTs (market rocketing up)
        if ((regime == MarketRegime.STRONGLY_BULLISH) && momentum == ShortTermMomentum.RISING) {
            log.warn("🛑 [Macro Filter] BLOCKED PUT: STRONGLY bullish regime ({}% from 50-SMA) + rising momentum",
                    String.format("%+.2f", distanceFromSma50Pct));
            return false;
        }

        // BULLISH + rising momentum = warn but allow (pullback plays exist)
        if ((regime == MarketRegime.BULLISH || regime == MarketRegime.STRONGLY_BULLISH)
                && momentum == ShortTermMomentum.FALLING) {
            log.warn("⚠️ [Macro Filter] CAUTION on PUT: Bullish regime ({}%) but falling momentum — pullback trade",
                    String.format("%+.2f", distanceFromSma50Pct));
            return true;  // Allow — SPY is pulling back, good for PUTs
        }

        // BEARISH or NEUTRAL + falling = favorable for PUTs
        if ((regime == MarketRegime.BEARISH || regime == MarketRegime.STRONGLY_BEARISH)
                && momentum == ShortTermMomentum.FALLING) {
            log.debug("✅ [Macro Filter] FAVORABLE for PUT: {} regime + falling momentum", regime);
        }

        // NEUTRAL = allow (market is ranging, both directions viable)
        if (regime == MarketRegime.NEUTRAL) {
            log.debug("✅ [Macro Filter] NEUTRAL regime — allowing PUT (range-bound market)");
        }

        return true;
    }

    /**
     * Updates the market analysis with multi-factor data.
     */
    private synchronized void updateMarketAnalysis() {
        try {
            List<Candle> spyDaily = csvService.loadFromCsv("SPY", TimeFrame.DAY_1);

            if (spyDaily == null || spyDaily.size() < 50) {
                log.warn("🌐 Macro filter: Not enough SPY data (need 50 days, have {})",
                        spyDaily != null ? spyDaily.size() : 0);
                regime = MarketRegime.NEUTRAL;
                momentum = ShortTermMomentum.FLAT;
                lastCheckTime = System.currentTimeMillis();
                return;
            }

            // === Factor 1: SPY vs 50-SMA distance ===
            double sum = 0;
            int count = Math.min(50, spyDaily.size());
            for (int i = spyDaily.size() - count; i < spyDaily.size(); i++) {
                sum += spyDaily.get(i).close();
            }
            sma50 = sum / count;
            spyPrice = spyDaily.get(spyDaily.size() - 1).close();
            distanceFromSma50Pct = ((spyPrice - sma50) / sma50) * 100.0;

            // Classify regime
            if (distanceFromSma50Pct > 3.0) {
                regime = MarketRegime.STRONGLY_BULLISH;
            } else if (distanceFromSma50Pct > 1.0) {
                regime = MarketRegime.BULLISH;
            } else if (distanceFromSma50Pct < -3.0) {
                regime = MarketRegime.STRONGLY_BEARISH;
            } else if (distanceFromSma50Pct < -1.0) {
                regime = MarketRegime.BEARISH;
            } else {
                regime = MarketRegime.NEUTRAL;
            }

            // === Factor 2: Short-term momentum (5-day SMA vs 10-day SMA) ===
            double sma5Sum = 0;
            int sma5Count = Math.min(5, spyDaily.size());
            for (int i = spyDaily.size() - sma5Count; i < spyDaily.size(); i++) {
                sma5Sum += spyDaily.get(i).close();
            }
            double sma5 = sma5Sum / sma5Count;

            double sma10Sum = 0;
            int sma10Count = Math.min(10, spyDaily.size());
            for (int i = spyDaily.size() - sma10Count; i < spyDaily.size(); i++) {
                sma10Sum += spyDaily.get(i).close();
            }
            double sma10 = sma10Sum / sma10Count;

            double momentumDiff = ((sma5 - sma10) / sma10) * 100.0;
            if (momentumDiff > 0.2) {
                momentum = ShortTermMomentum.RISING;
            } else if (momentumDiff < -0.2) {
                momentum = ShortTermMomentum.FALLING;
            } else {
                momentum = ShortTermMomentum.FLAT;
            }

            lastCheckTime = System.currentTimeMillis();

            // Log the analysis
            log.info("🌐 [Macro Filter] SPY ${} | 50-SMA ${} ({}%) | Regime: {}",
                    String.format("%.2f", spyPrice), String.format("%.2f", sma50), 
                    String.format("%+.2f", distanceFromSma50Pct), regime);
            log.info("   → 5-day SMA: ${} | 10-day SMA: ${} | Momentum: {} ({}%)",
                    String.format("%.2f", sma5), String.format("%.2f", sma10), 
                    momentum, String.format("%+.3f", momentumDiff));
            log.info("   → CALL trades: {} | PUT trades: {}",
                    regime == MarketRegime.STRONGLY_BEARISH && momentum == ShortTermMomentum.FALLING ? "BLOCKED" : "allowed",
                    regime == MarketRegime.STRONGLY_BULLISH && momentum == ShortTermMomentum.RISING ? "BLOCKED" : "allowed");

        } catch (Exception e) {
            log.error("❌ [Macro Filter] Error checking SPY trend: {}", e.getMessage());
            regime = MarketRegime.NEUTRAL;
            momentum = ShortTermMomentum.FLAT;
            lastCheckTime = System.currentTimeMillis();
        }
    }

    /**
     * Forces a specific market condition (for testing).
     */
    public void forceMarketCondition(MarketRegime forcedRegime, ShortTermMomentum forcedMomentum) {
        regime = forcedRegime;
        momentum = forcedMomentum;
        lastCheckTime = System.currentTimeMillis();
        log.info("🌐 [Macro Filter] FORCED: Regime={}, Momentum={}", regime, momentum);
    }

    /**
     * Gets current market regime.
     */
    public MarketRegime getRegime() {
        return regime;
    }

    /**
     * Gets current short-term momentum.
     */
    public ShortTermMomentum getMomentum() {
        return momentum;
    }

    /**
     * Gets market analysis as string for logging/Telegram.
     */
    public String getAnalysisString() {
        return String.format("SPY $%.2f | 50-SMA $%.2f (%+.2f%%) | Regime: %s | Momentum: %s",
                spyPrice, sma50, distanceFromSma50Pct, regime, momentum);
    }
}
