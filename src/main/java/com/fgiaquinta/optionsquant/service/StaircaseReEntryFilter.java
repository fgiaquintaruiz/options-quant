package com.fgiaquinta.optionsquant.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Staircase Re-Entry Filter.
 *
 * Prevents re-entering a ticker at a worse price than the last exit.
 * - For CALLs: blocks if current price >= last exit price (must enter cheaper)
 * - For PUTs: blocks if current price <= last exit price (must enter higher)
 *
 * This ensures the bot only re-enters when it gets a better deal than the previous trade.
 */
@Slf4j
@Service
public class StaircaseReEntryFilter {

    private final Map<String, Double> lastExitPrices = new ConcurrentHashMap<>();

    /**
     * Checks if a re-entry is allowed based on the last exit price.
     *
     * @param ticker The ticker symbol
     * @param isCall true for CALL, false for PUT
     * @param currentPrice The current entry price being considered
     * @return true if re-entry is allowed (price improved), false if blocked
     */
    public boolean isReEntryAllowed(String ticker, boolean isCall, double currentPrice) {
        Double lastExitPrice = lastExitPrices.get(ticker);
        if (lastExitPrice == null) {
            // No previous exit, allow first entry
            return true;
        }

        if (isCall) {
            // CALL: current price must be LOWER than last exit (cheaper entry)
            if (currentPrice >= lastExitPrice) {
                log.warn("⏳ [Staircase] BLOCKED re-entry for {} CALL: current ${:.2f} >= last exit ${:.2f}",
                        ticker, currentPrice, lastExitPrice);
                return false;
            }
        } else {
            // PUT: current price must be HIGHER than last exit (better premium)
            if (currentPrice <= lastExitPrice) {
                log.warn("⏳ [Staircase] BLOCKED re-entry for {} PUT: current ${:.2f} <= last exit ${:.2f}",
                        ticker, currentPrice, lastExitPrice);
                return false;
            }
        }

        log.info("✅ [Staircase] Price improved for {} {} (current ${:.2f} vs last exit ${:.2f}) — allowing re-entry",
                ticker, isCall ? "CALL" : "PUT", currentPrice, lastExitPrice);
        return true;
    }

    /**
     * Records an exit price for a ticker. Call this when a position is closed.
     *
     * @param ticker The ticker symbol
     * @param exitPrice The price at which the position was closed
     */
    public void recordExit(String ticker, double exitPrice) {
        lastExitPrices.put(ticker, exitPrice);
        log.info("💾 [Staircase] Recorded exit for {} at ${:.2f}", ticker, exitPrice);
    }

    /**
     * Clears the last exit price for a ticker (e.g., after a timeout or manual reset).
     */
    public void clearExit(String ticker) {
        lastExitPrices.remove(ticker);
        log.debug("🧹 [Staircase] Cleared exit record for {}", ticker);
    }

    /**
     * Gets the last exit price for a ticker, or null if none exists.
     */
    public Double getLastExitPrice(String ticker) {
        return lastExitPrices.get(ticker);
    }

    /**
     * Gets all tracked exit prices (for monitoring/debugging).
     */
    public Map<String, Double> getAllExitPrices() {
        return Map.copyOf(lastExitPrices);
    }
}
