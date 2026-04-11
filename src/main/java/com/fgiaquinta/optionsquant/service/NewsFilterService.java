package com.fgiaquinta.optionsquant.service;

import com.fgiaquinta.optionsquant.domain.TickerInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Filters and prioritizes tickers based on news sentiment and fundamental criteria.
 * Creates a priority list of 10 tickers for focused strategy scanning.
 * Based on TWS API news and market scanning capabilities.
 */
@Slf4j
@Service
public class NewsFilterService {

    private final TickerService tickerService;
    private volatile List<String> priorityTickers = new ArrayList<>();
    private volatile long lastUpdate = 0;

    // Cache refresh interval: 30 minutes
    private static final long CACHE_TTL_MS = 30 * 60 * 1000;

    public NewsFilterService(TickerService tickerService) {
        this.tickerService = tickerService;
    }

    /**
     * Gets the top 10 priority tickers based on news and fundamentals.
     * Prioritizes:
     * 1. High volume movers (from recent backtest performance)
     * 2. Strong fundamentals (ROIC > 15, EPS growth > 10%)
     * 3. Low debt (D/E < 0.5)
     * 4. Reasonable valuation (P/E < 25)
     */
    public List<String> getPriorityTickers() {
        if (priorityTickers.isEmpty() || System.currentTimeMillis() - lastUpdate > CACHE_TTL_MS) {
            priorityTickers = calculatePriorityTickers();
            lastUpdate = System.currentTimeMillis();
        }
        return priorityTickers;
    }

    /**
     * Forces a refresh of the priority ticker list.
     */
    public List<String> refreshPriorityTickers() {
        priorityTickers = calculatePriorityTickers();
        lastUpdate = System.currentTimeMillis();
        log.info("🔄 Refreshed priority tickers: {}", priorityTickers);
        return priorityTickers;
    }

    private List<String> calculatePriorityTickers() {
        List<TickerInfo> allTickers = tickerService.getAllTickers();

        // Score each ticker based on multiple criteria
        Map<String, Double> scores = new HashMap<>();

        for (TickerInfo ticker : allTickers) {
            double score = 0;

            // Fundamental score (0-40 points)
            if (ticker.roic() != null && ticker.roic() > 15) score += 15;
            else if (ticker.roic() != null && ticker.roic() > 10) score += 10;
            else if (ticker.roic() != null && ticker.roic() > 5) score += 5;

            if (ticker.epsGrowth() != null && ticker.epsGrowth() > 15) score += 15;
            else if (ticker.epsGrowth() != null && ticker.epsGrowth() > 10) score += 10;
            else if (ticker.epsGrowth() != null && ticker.epsGrowth() > 5) score += 5;

            // Debt score (0-20 points)
            if (ticker.debtToEquity() != null && ticker.debtToEquity() < 0.3) score += 20;
            else if (ticker.debtToEquity() != null && ticker.debtToEquity() < 0.5) score += 15;
            else if (ticker.debtToEquity() != null && ticker.debtToEquity() < 1.0) score += 10;
            else if (ticker.debtToEquity() != null) score += 5;

            // Valuation score (0-20 points)
            if (ticker.peRatio() != null && ticker.peRatio() > 0 && ticker.peRatio() < 15) score += 20;
            else if (ticker.peRatio() != null && ticker.peRatio() < 25) score += 15;
            else if (ticker.peRatio() != null && ticker.peRatio() < 35) score += 10;
            else if (ticker.peRatio() != null) score += 5;

            // Market cap score (0-20 points) - prefer mid to large cap for liquidity
            if (ticker.marketCapBillion() != null) {
                if (ticker.marketCapBillion() > 50) score += 20;
                else if (ticker.marketCapBillion() > 20) score += 15;
                else if (ticker.marketCapBillion() > 10) score += 10;
                else score += 5;
            }

            // Beta score (0-10 points) - prefer moderate beta
            if (ticker.beta() != null) {
                if (ticker.beta() > 0.8 && ticker.beta() < 1.3) score += 10;
                else if (ticker.beta() > 0.6 && ticker.beta() < 1.5) score += 7;
                else score += 3;
            }

            scores.put(ticker.ticker(), score);
        }

        // Sort by score descending and take top 10
        return scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(10)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }

    /**
     * Checks if a ticker is in the priority list.
     */
    public boolean isPriorityTicker(String ticker) {
        return getPriorityTickers().contains(ticker.toUpperCase());
    }

    /**
     * Gets ticker info with scoring details for debugging.
     */
    public Map<String, Object> getTickerScore(String ticker) {
        Optional<TickerInfo> infoOpt = tickerService.getTickerInfo(ticker);
        if (infoOpt.isEmpty()) return Map.of("error", "Ticker not found");

        TickerInfo info = infoOpt.get();
        Map<String, Object> scoreDetails = new LinkedHashMap<>();
        scoreDetails.put("ticker", info.ticker());
        scoreDetails.put("company", info.companyName());
        scoreDetails.put("sector", info.sector());
        scoreDetails.put("roic", info.roic());
        scoreDetails.put("epsGrowth", info.epsGrowth());
        scoreDetails.put("debtToEquity", info.debtToEquity());
        scoreDetails.put("peRatio", info.peRatio());
        scoreDetails.put("marketCapB", info.marketCapBillion());
        scoreDetails.put("beta", info.beta());
        scoreDetails.put("isPriority", isPriorityTicker(ticker));

        return scoreDetails;
    }

    /**
     * Simulates news sentiment impact (placeholder for real news API integration).
     * In the future, this will integrate with:
     * - TWS News API (reqNewsArticle, getNewsProviders)
     * - External news APIs (Benzinga, NewsAPI, etc.)
     * - Social sentiment (StockTwits, Reddit)
     */
    public NewsSentiment getNewsSentiment(String ticker) {
        // TODO: Implement real news sentiment analysis
        // For now, return neutral
        return new NewsSentiment(ticker, Sentiment.NEUTRAL, 0.0, List.of());
    }

    public record NewsSentiment(
            String ticker,
            Sentiment sentiment,
            double score, // -1.0 to 1.0
            List<String> headlines
    ) {}

    public enum Sentiment {
        VERY_BEARISH, BEARISH, NEUTRAL, BULLISH, VERY_BULLISH
    }
}
