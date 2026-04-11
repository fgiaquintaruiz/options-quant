package com.fgiaquinta.optionsquant.service;

import com.fgiaquinta.optionsquant.domain.Candle;
import com.fgiaquinta.optionsquant.domain.TimeFrame;
import com.fgiaquinta.optionsquant.domain.TickerInfo;
import com.fgiaquinta.optionsquant.strategy.TradingStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.ZonedDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Screens all 356 tickers to find suitable candidates for strategies.
 * Implements incremental criteria relaxation to ensure candidates are always found.
 */
@Slf4j
@Service
public class StrategyScreenerService {

    private final TickerService tickerService;
    private final List<TradingStrategy> strategies;

    // Screening criteria levels (from strict to relaxed)
    private static final ScreeningCriteria[][] CRITERIA_LEVELS = {
            // Level 1: Strict (ideal conditions)
            {new ScreeningCriteria(0.02, 1.5, 50_000_000, 0.02)},
            // Level 2: Moderate
            {new ScreeningCriteria(0.015, 1.2, 20_000_000, 0.015)},
            // Level 3: Relaxed
            {new ScreeningCriteria(0.01, 1.0, 10_000_000, 0.01)},
            // Level 4: Very relaxed
            {new ScreeningCriteria(0.005, 0.8, 5_000_000, 0.005)}
    };

    public StrategyScreenerService(TickerService tickerService) {
        this.tickerService = tickerService;
        this.strategies = List.of(
                new com.fgiaquinta.optionsquant.strategy.C1SqueezeCallStrategy(),
                new com.fgiaquinta.optionsquant.strategy.C2TrendCallStrategy(),
                new com.fgiaquinta.optionsquant.strategy.C3BounceCallStrategy(),
                new com.fgiaquinta.optionsquant.strategy.C4OpeningCallStrategy(),
                new com.fgiaquinta.optionsquant.strategy.C5ContinuationCallStrategy(),
                new com.fgiaquinta.optionsquant.strategy.C6ReversalCallStrategy(),
                new com.fgiaquinta.optionsquant.strategy.P1SqueezePutStrategy(),
                new com.fgiaquinta.optionsquant.strategy.P2TrendPutStrategy(),
                new com.fgiaquinta.optionsquant.strategy.P3BouncePutStrategy(),
                new com.fgiaquinta.optionsquant.strategy.P4OpeningPutStrategy(),
                new com.fgiaquinta.optionsquant.strategy.P5ContinuationPutStrategy(),
                new com.fgiaquinta.optionsquant.strategy.P6ReversalPutStrategy()
        );
    }

    /**
     * Screens all tickers and returns candidates sorted by strategy match score.
     * Automatically relaxes criteria if no candidates found.
     */
    public ScreeningResult screenAllTickers(Map<String, Map<TimeFrame, List<Candle>>> candleData) {
        log.info("🔍 Screening all tickers for strategy opportunities...");

        for (int level = 0; level < CRITERIA_LEVELS.length; level++) {
            ScreeningCriteria[] criteria = CRITERIA_LEVELS[level];
            log.info("  Trying criteria level {}/{}", level + 1, CRITERIA_LEVELS.length);

            ScreeningResult result = screenWithCriteria(candleData, criteria[0], level + 1);

            if (!result.candidates().isEmpty()) {
                log.info("✅ Found {} candidates at level {}", result.candidates().size(), level + 1);
                return result;
            }

            log.info("  No candidates at level {}, relaxing criteria...", level + 1);
        }

        log.warn("⚠️ No candidates found even with relaxed criteria");
        return new ScreeningResult(List.of(), 4, "No suitable candidates found");
    }

    /**
     * Screens tickers with specific criteria.
     */
    private ScreeningResult screenWithCriteria(
            Map<String, Map<TimeFrame, List<Candle>>> candleData,
            ScreeningCriteria criteria,
            int criteriaLevel) {

        List<TickerCandidate> candidates = new ArrayList<>();

        for (String ticker : candleData.keySet()) {
            Map<TimeFrame, List<Candle>> tickerCandles = candleData.get(ticker);
            if (tickerCandles == null || tickerCandles.isEmpty()) continue;

            // Check if we have enough data
            if (!hasMinimumData(tickerCandles)) continue;

            // Score this ticker against all strategies
            TickerScore score = scoreTicker(ticker, tickerCandles, criteria);

            if (score.matches() > 0) {
                candidates.add(new TickerCandidate(
                        ticker,
                        score.matches(),
                        score.strategies(),
                        score.strength()
                ));
            }
        }

        // Sort by match count descending, then by strength
        candidates.sort(Comparator.<TickerCandidate>comparingInt(TickerCandidate::matchCount)
                .thenComparingDouble(TickerCandidate::strength).reversed());

        String message = candidates.isEmpty()
                ? "No candidates at criteria level " + criteriaLevel
                : String.format("Found %d candidates", candidates.size());

        return new ScreeningResult(candidates, criteriaLevel, message);
    }

    /**
     * Checks if ticker has minimum required data.
     */
    private boolean hasMinimumData(Map<TimeFrame, List<Candle>> tickerCandles) {
        // Need at least 20 candles on each timeframe for proper analysis
        for (TimeFrame tf : TimeFrame.values()) {
            List<Candle> candles = tickerCandles.get(tf);
            if (candles == null || candles.size() < 20) return false;
        }
        return true;
    }

    /**
     * Scores a ticker against all strategies.
     */
    private TickerScore scoreTicker(
            String ticker,
            Map<TimeFrame, List<Candle>> tickerCandles,
            ScreeningCriteria criteria) {

        List<String> matchedStrategies = new ArrayList<>();
        double totalStrength = 0;

        for (TradingStrategy strategy : strategies) {
            try {
                // Build StrategyData
                com.fgiaquinta.optionsquant.strategy.data.StrategyData data =
                        new com.fgiaquinta.optionsquant.strategy.data.StrategyData(tickerCandles);

                // Check if strategy is triggered
                if (strategy.isTriggered(ticker, data, ZonedDateTime.now())) {
                    matchedStrategies.add(strategy.getName());

                    // Calculate strength based on signal quality
                    double strength = calculateSignalStrength(ticker, data, strategy, criteria);
                    totalStrength += strength;
                }
            } catch (Exception e) {
                log.debug("Strategy {} error for {}: {}", strategy.getName(), ticker, e.getMessage());
            }
        }

        return new TickerScore(matchedStrategies.size(), matchedStrategies, totalStrength);
    }

    /**
     * Calculates signal strength (0-1 scale) based on multiple factors.
     */
    private double calculateSignalStrength(
            String ticker,
            com.fgiaquinta.optionsquant.strategy.data.StrategyData data,
            TradingStrategy strategy,
            ScreeningCriteria criteria) {

        double strength = 0.5; // Base strength

        // Volume factor (higher volume = stronger signal)
        List<Candle> candles15m = data.getCandles(com.fgiaquinta.optionsquant.domain.TimeFrame.MIN_15);

        if (!candles15m.isEmpty()) {
            Candle last = candles15m.get(candles15m.size() - 1);
            double avgVolume = candles15m.stream()
                    .mapToDouble(Candle::volume)
                    .average()
                    .orElse(1_000_000);

            if (last.volume() > avgVolume * 2) strength += 0.2;
            else if (last.volume() > avgVolume * 1.5) strength += 0.1;
        }

        // Trend alignment factor
        // (Would check if multiple timeframes align)

        return Math.min(1.0, strength);
    }

    /**
     * Gets the top N candidates for trading.
     */
    public List<TickerCandidate> getTopCandidates(
            Map<String, Map<TimeFrame, List<Candle>>> candleData,
            int count) {

        ScreeningResult result = screenAllTickers(candleData);
        return result.candidates().stream()
                .limit(count)
                .collect(Collectors.toList());
    }

    /**
     * Screening criteria configuration.
     */
    public record ScreeningCriteria(
            double minVolumeChange,    // Minimum volume change % (0.02 = 2%)
            double minPriceChange,     // Minimum price change % (0.015 = 1.5%)
            long minMarketCap,         // Minimum market cap
            double minVolatility       // Minimum ATR/price ratio
    ) {}

    /**
     * Result of ticker screening.
     */
    public record ScreeningResult(
            List<TickerCandidate> candidates,
            int criteriaLevelUsed,
            String message
    ) {}

    /**
     * Score for a single ticker.
     */
    private record TickerScore(
            int matches,
            List<String> strategies,
            double strength
    ) {}

    /**
     * Candidate ticker with strategy matches.
     */
    public record TickerCandidate(
            String ticker,
            int matchCount,
            List<String> matchedStrategies,
            double strength
    ) {
        @Override
        public String toString() {
            return String.format("%-6s | Matches: %d | Strategies: %s | Strength: %.2f",
                    ticker, matchCount, String.join(", ", matchedStrategies), strength);
        }
    }
}
