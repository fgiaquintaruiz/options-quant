package com.fgiaquinta.optionsquant.service;

import com.fgiaquinta.optionsquant.config.IbkrProperties;
import com.fgiaquinta.optionsquant.domain.Candle;
import com.fgiaquinta.optionsquant.domain.TimeFrame;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Active Position Trailing Stop Monitor.
 *
 * Runs every 1 minute to:
 * 1. Monitor open positions for time stops (90-minute max)
 * 2. Trail stop losses to SMA20 when position is profitable (>0.35%)
 *
 * This replaces the legacy TradeManager.monitorActivePositions().
 */
@Slf4j
@Service
public class TrailingStopMonitor {

    private final CandleCsvService csvService;
    private final IbkrService ibkrService;
    private final OrderExecutionService orderExecutionService;
    private final IbkrProperties ibkrProperties;

    // Active positions tracking: ticker -> ActivePosition
    private final Map<String, ActivePosition> activePositions = new ConcurrentHashMap<>();

    // Configuration thresholds
    private static final double MIN_PROFIT_PCT_TO_TRAIL = 0.35;  // Start trailing at 0.35% profit
    private static final int MAX_HOLD_MINUTES = 90;              // Time stop after 90 minutes
    private static final int SMA_PERIOD = 20;                    // SMA20 for trailing stop

    public TrailingStopMonitor(CandleCsvService csvService, IbkrService ibkrService,
                               OrderExecutionService orderExecutionService, IbkrProperties ibkrProperties) {
        this.csvService = csvService;
        this.ibkrService = ibkrService;
        this.orderExecutionService = orderExecutionService;
        this.ibkrProperties = ibkrProperties;
        log.info("🎯 [TrailingStop] Initialized - trailing at {}% profit, time stop at {} min",
                MIN_PROFIT_PCT_TO_TRAIL, MAX_HOLD_MINUTES);
    }

    /**
     * Registers a new active position for monitoring.
     * Call this after a bracket order is placed and filled.
     *
     * @param ticker The ticker symbol
     * @param isCall true for CALL, false for PUT
     * @param entryPrice The entry price
     * @param slOrderId The IBKR stop-loss order ID (for modification)
     */
    public void registerPosition(String ticker, boolean isCall, double entryPrice, int slOrderId) {
        ActivePosition position = new ActivePosition(
                ticker, isCall, entryPrice, slOrderId, ZonedDateTime.now(ZoneId.of("America/New_York"))
        );
        activePositions.put(ticker, position);
        log.info("📍 [TrailingStop] Registered position: {} {} @ ${}, SL orderId={}",
                ticker, isCall ? "CALL" : "PUT", String.format("%.2f", entryPrice), slOrderId);
    }

    /**
     * Removes a position from monitoring (called when position is closed).
     */
    public void removePosition(String ticker) {
        ActivePosition removed = activePositions.remove(ticker);
        if (removed != null) {
            log.info("🧹 [TrailingStop] Removed position: {} {} (held {} min)",
                    removed.ticker, removed.isCall ? "CALL" : "PUT", removed.getHeldMinutes());
        }
    }

    /**
     * Records the exit price for the Staircase filter when a position closes.
     */
    private void recordExitForStaircase(String ticker, double exitPrice) {
        // This will be called by the IbkrCallbackHandler when it detects a fill
        // For now, we rely on the callback handler to notify us
    }

    /**
     * Runs every 1 minute during market hours to monitor active positions.
     * Cron: every minute, 9:30-16:00 NY time (market hours)
     */
    @Scheduled(cron = "0 * 9-16 * * MON-FRI", zone = "America/New_York")
    public void monitorPositions() {
        if (activePositions.isEmpty()) {
            return;
        }

        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("America/New_York"));
        log.debug("🔍 [TrailingStop] Checking {} active positions...", activePositions.size());

        for (ActivePosition position : activePositions.values()) {
            try {
                monitorSinglePosition(position, now);
            } catch (Exception e) {
                log.error("❌ [TrailingStop] Error monitoring position for {}: {}", position.ticker, e.getMessage());
            }
        }
    }

    private void monitorSinglePosition(ActivePosition position, ZonedDateTime now) {
        // Get latest 15-min candles for SMA calculation
        List<Candle> candles = csvService.loadFromCsv(position.ticker, TimeFrame.MIN_15);
        if (candles == null || candles.isEmpty()) {
            log.debug("⏭️ [TrailingStop] No candle data for {}", position.ticker);
            return;
        }

        double currentPrice = candles.get(candles.size() - 1).close();

        // === 1. TIME STOP (90-minute max hold) ===
        long heldMinutes = position.getHeldMinutes();
        if (heldMinutes >= MAX_HOLD_MINUTES) {
            log.warn("⏳ [Time Stop] {} held {} min (max {}). Forcing exit at ${}",
                    position.ticker, heldMinutes, MAX_HOLD_MINUTES, String.format("%.2f", currentPrice));
            // Adjust SL to current price to force exit on next touch
            // TODO: Call ibkrService.modifyStopLossCondition(position.slOrderId, position.ticker, currentPrice);
            // For now, log and mark for manual review
            log.warn("⚠️ [Time Stop] Manual intervention required: modify SL order {} to ${}",
                    position.slOrderId, String.format("%.2f", currentPrice));
            return;
        }

        // === 2. TRAILING STOP (SMA20 when profitable) ===
        double profitPct = position.isCall ?
                ((currentPrice - position.entryPrice) / position.entryPrice) * 100 :
                ((position.entryPrice - currentPrice) / position.entryPrice) * 100;

        if (profitPct > MIN_PROFIT_PCT_TO_TRAIL) {
            // Calculate SMA20 on 15-min timeframe
            double sma20 = calculateSMA20(candles);
            if (sma20 <= 0) {
                log.debug("⏭️ [TrailingStop] Not enough data for SMA20 on {}", position.ticker);
                return;
            }

            // Check if SMA20 is more favorable than current SL
            boolean shouldTrail;
            if (position.isCall) {
                // For CALLs: SMA20 should be HIGHER than current SL (tighter stop, more profit protected)
                shouldTrail = sma20 > position.currentStopLoss;
            } else {
                // For PUTs: SMA20 should be LOWER than current SL
                shouldTrail = sma20 < position.currentStopLoss;
            }

            if (shouldTrail) {
                log.info("🎯 [TrailingStop] {} {} - profit {}%, trailing SL to SMA20 ${} (was ${})",
                        position.ticker, position.isCall ? "CALL" : "PUT",
                        String.format("%.2f", profitPct), String.format("%.2f", sma20), String.format("%.2f", position.currentStopLoss));

                // TODO: Call ibkrService.modifyStopLossCondition(position.slOrderId, position.ticker, sma20);
                position.currentStopLoss = sma20;  // Track locally for now
            }
        }
    }

    /**
     * Calculates SMA20 from the last 20 candle closes.
     */
    private double calculateSMA20(List<Candle> candles) {
        if (candles.size() < SMA_PERIOD) {
            return 0;
        }

        double sum = 0;
        for (int i = candles.size() - SMA_PERIOD; i < candles.size(); i++) {
            sum += candles.get(i).close();
        }
        return sum / SMA_PERIOD;
    }

    /**
     * Gets all actively monitored positions.
     */
    public Map<String, ActivePosition> getActivePositions() {
        return Map.copyOf(activePositions);
    }

    /**
     * Gets the count of monitored positions.
     */
    public int getMonitoredPositionCount() {
        return activePositions.size();
    }

    /**
     * Represents an actively monitored position.
     */
    public static class ActivePosition {
        public final String ticker;
        public final boolean isCall;
        public final double entryPrice;
        public final int slOrderId;
        public final ZonedDateTime entryTime;
        public double currentStopLoss;

        public ActivePosition(String ticker, boolean isCall, double entryPrice, int slOrderId, ZonedDateTime entryTime) {
            this.ticker = ticker;
            this.isCall = isCall;
            this.entryPrice = entryPrice;
            this.slOrderId = slOrderId;
            this.entryTime = entryTime;
            this.currentStopLoss = 0;  // Will be set from TradePlan
        }

        public long getHeldMinutes() {
            ZonedDateTime now = ZonedDateTime.now(ZoneId.of("America/New_York"));
            return java.time.Duration.between(entryTime, now).toMinutes();
        }

        @Override
        public String toString() {
            return String.format("%s %s @ %.2f (held %d min, SL orderId=%d)",
                    ticker, isCall ? "CALL" : "PUT", entryPrice, getHeldMinutes(), slOrderId);
        }
    }
}
