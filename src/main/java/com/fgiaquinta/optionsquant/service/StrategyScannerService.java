package com.fgiaquinta.optionsquant.service;

import com.fgiaquinta.optionsquant.config.IbkrProperties;
import com.fgiaquinta.optionsquant.domain.Candle;
import com.fgiaquinta.optionsquant.domain.TimeFrame;
import com.fgiaquinta.optionsquant.strategy.*;
import com.fgiaquinta.optionsquant.strategy.data.StrategyData;
import com.fgiaquinta.optionsquant.strategy.indicator.WordenStochasticIndicator;
import com.fgiaquinta.optionsquant.strategy.model.TradePlan;
import com.fgiaquinta.optionsquant.strategy.utils.RiskCalculator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.springframework.stereotype.Service;

import java.time.ZonedDateTime;
import java.time.ZoneId;
import java.util.*;

/**
 * Scans all configured tickers against all 12 strategies.
 * Returns triggered signals with trade plans.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StrategyScannerService {

    private final CandleCsvService csvService;
    private final IbkrProperties ibkrProperties;

    private final List<TradingStrategy> callStrategies;
    private final List<TradingStrategy> putStrategies;

    public StrategyScannerService(CandleCsvService csvService, IbkrProperties ibkrProperties) {
        this.csvService = csvService;
        this.ibkrProperties = ibkrProperties;

        // Initialize strategies with WordenStochastic for C5/P5
        this.callStrategies = List.of(
                new C1SqueezeCallStrategy(),
                new C2TrendCallStrategy(),
                new C3BounceCallStrategy(),
                new C4OpeningCallStrategy(),
                new C5ContinuationCallStrategy(), // uses volume surge fallback
                new C6ReversalCallStrategy()
        );
        this.putStrategies = List.of(
                new P1SqueezePutStrategy(),
                new P2TrendPutStrategy(),
                new P3BouncePutStrategy(),
                new P4OpeningPutStrategy(),
                new P5ContinuationPutStrategy(), // uses volume surge fallback
                new P6ReversalPutStrategy()
        );
    }

    /**
     * Scans all configured tickers against all strategies.
     */
    public ScanResult scanAll(boolean includeTradePlans) {
        log.info(">>> Scanning {} tickers against 12 strategies", ibkrProperties.tickers().size());
        long startTime = System.currentTimeMillis();

        List<Signal> allSignals = new ArrayList<>();

        for (String ticker : ibkrProperties.tickers()) {
            List<Signal> tickerSignals = scanTicker(ticker, includeTradePlans);
            allSignals.addAll(tickerSignals);
        }

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("<<< Scan complete: {} signals found across {} tickers in {}ms",
                allSignals.size(), ibkrProperties.tickers().size(), elapsed);

        return new ScanResult(allSignals.size(), ibkrProperties.tickers().size(), allSignals, elapsed);
    }

    /**
     * Scans a single ticker against all strategies.
     */
    public List<Signal> scanTicker(String ticker, boolean includeTradePlans) {
        log.debug("Scanning ticker: {}", ticker);

        // Load all timeframes from CSV
        Map<TimeFrame, List<Candle>> candlesByTimeframe = new EnumMap<>(TimeFrame.class);
        for (TimeFrame tf : TimeFrame.values()) {
            List<Candle> candles = csvService.loadFromCsv(ticker, tf);
            if (!candles.isEmpty()) {
                candlesByTimeframe.put(tf, candles);
            }
        }

        if (candlesByTimeframe.isEmpty()) {
            log.debug("No data found for ticker {}", ticker);
            return Collections.emptyList();
        }

        StrategyData data = new StrategyData(candlesByTimeframe);
        if (!data.hasAllTimeframes()) {
            log.debug("Incomplete data for ticker {} (missing timeframes)", ticker);
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
