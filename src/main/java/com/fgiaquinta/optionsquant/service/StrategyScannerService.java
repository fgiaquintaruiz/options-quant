package com.fgiaquinta.optionsquant.service;

import com.fgiaquinta.optionsquant.config.IbkrProperties;
import com.fgiaquinta.optionsquant.domain.Candle;
import com.fgiaquinta.optionsquant.domain.TimeFrame;
import com.fgiaquinta.optionsquant.strategy.*;
import com.fgiaquinta.optionsquant.strategy.data.StrategyData;
import com.fgiaquinta.optionsquant.strategy.model.TradePlan;
import com.fgiaquinta.optionsquant.strategy.utils.RiskCalculator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.ZoneId;
import java.util.*;

/**
 * Scans all configured tickers against all 12 strategies.
 * Returns triggered signals with trade plans.
 * Auto-downloads fresh data if CSV is missing or stale.
 */
@Slf4j
@Service
public class StrategyScannerService {

    private final CandleCsvService csvService;
    private final IbkrService ibkrService;
    private final IbkrProperties ibkrProperties;

    private final List<TradingStrategy> callStrategies;
    private final List<TradingStrategy> putStrategies;

    // Maximum age for data to be considered "fresh"
    private static final Map<TimeFrame, Duration> FRESHNESS_THRESHOLDS = Map.of(
            TimeFrame.MIN_5, Duration.ofMinutes(15),
            TimeFrame.MIN_15, Duration.ofMinutes(30),
            TimeFrame.HOUR_1, Duration.ofHours(2),
            TimeFrame.DAY_1, Duration.ofHours(26)
    );

    public StrategyScannerService(CandleCsvService csvService, IbkrService ibkrService, IbkrProperties ibkrProperties) {
        this.csvService = csvService;
        this.ibkrService = ibkrService;
        this.ibkrProperties = ibkrProperties;

        this.callStrategies = List.of(
                new C1SqueezeCallStrategy(),
                new C2TrendCallStrategy(),
                new C3BounceCallStrategy(),
                new C4OpeningCallStrategy(),
                new C5ContinuationCallStrategy(),
                new C6ReversalCallStrategy()
        );
        this.putStrategies = List.of(
                new P1SqueezePutStrategy(),
                new P2TrendPutStrategy(),
                new P3BouncePutStrategy(),
                new P4OpeningPutStrategy(),
                new P5ContinuationPutStrategy(),
                new P6ReversalPutStrategy()
        );
    }

    /**
     * Scans all configured tickers against all strategies.
     * Auto-downloads fresh data if CSV is missing or stale.
     */
    public ScanResult scanAll(boolean includeTradePlans, boolean autoRefreshData) {
        log.info(">>> Scanning {} tickers against 12 strategies (autoRefresh={})", ibkrProperties.tickers().size(), autoRefreshData);
        long startTime = System.currentTimeMillis();

        List<Signal> allSignals = new ArrayList<>();

        for (String ticker : ibkrProperties.tickers()) {
            try {
                List<Signal> tickerSignals = scanTicker(ticker, includeTradePlans, autoRefreshData);
                allSignals.addAll(tickerSignals);
            } catch (Exception e) {
                log.error("Error scanning ticker {}: {}", ticker, e.getMessage());
            }
        }

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("<<< Scan complete: {} signals found across {} tickers in {}ms",
                allSignals.size(), ibkrProperties.tickers().size(), elapsed);

        return new ScanResult(allSignals.size(), ibkrProperties.tickers().size(), allSignals, elapsed);
    }

    /**
     * Scans all tickers with auto-refresh enabled by default.
     */
    public ScanResult scanAll(boolean includeTradePlans) {
        return scanAll(includeTradePlans, true);
    }

    /**
     * Scans a single ticker against all strategies.
     */
    public List<Signal> scanTicker(String ticker, boolean includeTradePlans, boolean autoRefreshData) {
        log.debug("Scanning ticker: {} (autoRefresh={})", ticker, autoRefreshData);

        // Load all timeframes from CSV, auto-download if stale/missing
        Map<TimeFrame, List<Candle>> candlesByTimeframe = new EnumMap<>(TimeFrame.class);
        boolean needsDownload = false;

        for (TimeFrame tf : TimeFrame.values()) {
            List<Candle> candles = csvService.loadFromCsv(ticker, tf);
            if (candles.isEmpty()) {
                log.debug("No CSV data for {} [{}]", ticker, tf);
                needsDownload = true;
            } else if (autoRefreshData && isStale(candles, tf)) {
                Duration threshold = FRESHNESS_THRESHOLDS.get(tf);
                log.info("Data stale for {} [{}]: last candle {} vs threshold {}", ticker, tf, getLastTimestamp(candles), threshold);
                needsDownload = true;
            } else {
                candlesByTimeframe.put(tf, candles);
            }
        }

        // Auto-download missing or stale data
        if (needsDownload && autoRefreshData) {
            log.info("Auto-refreshing data for ticker: {}", ticker);
            try {
                Map<TimeFrame, List<Candle>> freshData = ibkrService.downloadAllTimeframes(ticker);
                freshData.forEach((tf, candles) -> {
                    if (!candles.isEmpty()) {
                        csvService.saveToCsv(ticker, tf, candles);
                        candlesByTimeframe.put(tf, candles);
                    }
                });
                log.info("Refreshed {} timeframes for {}", freshData.size(), ticker);
            } catch (Exception e) {
                log.error("Failed to download data for {}: {}", ticker, e.getMessage());
                // Fall back to whatever cached data we have
            }
        }

        if (candlesByTimeframe.isEmpty()) {
            log.debug("No usable data for ticker {}", ticker);
            return Collections.emptyList();
        }

        StrategyData data = new StrategyData(candlesByTimeframe);
        if (!data.hasAllTimeframes()) {
            log.debug("Incomplete data for ticker {} (have {}, need 4 timeframes)", ticker, candlesByTimeframe.keySet());
            return Collections.emptyList();
        }

        // Use the latest candle's timestamp as "current time"
        ZonedDateTime currentTime = getLatestTimestamp(data);
        if (currentTime == null) return Collections.emptyList();

        // Convert to NY timezone for strategy time checks
        ZonedDateTime nyTime = currentTime.withZoneSameInstant(ZoneId.of("America/New_York"));

        List<Signal> signals = new ArrayList<>();
        List<TradingStrategy> allStrategies = new ArrayList<>();
        allStrategies.addAll(callStrategies);
        allStrategies.addAll(putStrategies);

        for (TradingStrategy strategy : allStrategies) {
            try {
                boolean triggered = strategy.isTriggered(ticker, data, nyTime);
                if (triggered) {
                    double currentPrice = getCurrentPrice(data);
                    boolean isCall = strategy.getName().contains("call");

                    TradePlan tradePlan = null;
                    if (includeTradePlans) {
                        tradePlan = RiskCalculator.generatePlan(data, ticker, currentTime, !isCall, currentPrice);
                    }

                    Signal signal = new Signal(
                            ticker,
                            strategy.getName(),
                            isCall ? "CALL" : "PUT",
                            currentPrice,
                            nyTime,
                            tradePlan
                    );

                    signals.add(signal);
                    log.info("🎯 SIGNAL: {} triggered {} at ${}", ticker, strategy.getName(), currentPrice);
                }
            } catch (Exception e) {
                log.warn("Error evaluating strategy {} for ticker {}: {}", strategy.getName(), ticker, e.getMessage());
            }
        }

        return signals;
    }

    /**
     * Scans a single ticker with auto-refresh enabled by default.
     */
    public List<Signal> scanTicker(String ticker, boolean includeTradePlans) {
        return scanTicker(ticker, includeTradePlans, true);
    }

    // ===== Data Freshness Checks =====

    private boolean isStale(List<Candle> candles, TimeFrame tf) {
        if (candles.isEmpty()) return true;
        ZonedDateTime lastTimestamp = candles.get(candles.size() - 1).timestamp();
        Duration threshold = FRESHNESS_THRESHOLDS.getOrDefault(tf, Duration.ofHours(2));
        Duration age = Duration.between(lastTimestamp, ZonedDateTime.now(ZoneId.of("America/New_York")));
        return age.compareTo(threshold) > 0;
    }

    private ZonedDateTime getLastTimestamp(List<Candle> candles) {
        return candles.get(candles.size() - 1).timestamp();
    }

    // ===== Helpers =====

    private ZonedDateTime getLatestTimestamp(StrategyData data) {
        ZonedDateTime latest = null;
        for (TimeFrame tf : TimeFrame.values()) {
            List<Candle> candles = data.getCandles(tf);
            if (candles != null && !candles.isEmpty()) {
                ZonedDateTime ts = candles.get(candles.size() - 1).timestamp();
                if (latest == null || ts.isAfter(latest)) {
                    latest = ts;
                }
            }
        }
        return latest;
    }

    private double getCurrentPrice(StrategyData data) {
        List<Candle> candles5m = data.getCandles(TimeFrame.MIN_5);
        if (candles5m != null && !candles5m.isEmpty()) {
            return candles5m.get(candles5m.size() - 1).close();
        }
        List<Candle> candles15m = data.getCandles(TimeFrame.MIN_15);
        if (candles15m != null && !candles15m.isEmpty()) {
            return candles15m.get(candles15m.size() - 1).close();
        }
        return 0;
    }

    // ---- Response Records ----

    public record ScanResult(
            int totalSignals,
            int tickersScanned,
            List<Signal> signals,
            long elapsedMs
    ) {}

    public record Signal(
            String ticker,
            String strategy,
            String direction,
            double currentPrice,
            ZonedDateTime timestamp,
            TradePlan tradePlan
    ) {}
}
