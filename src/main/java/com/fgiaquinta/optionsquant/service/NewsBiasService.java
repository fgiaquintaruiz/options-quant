package com.fgiaquinta.optionsquant.service;

import com.fgiaquinta.optionsquant.domain.NewsBias;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Component
public class NewsBiasService {

    private final ConcurrentHashMap<String, NewsBias> biasCache = new ConcurrentHashMap<>();
    private final YahooFinanceClient yahooClient;
    private final HeadlineSentimentScorer scorer;
    private final ExecutorService executor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "news-prefetch");
        t.setDaemon(true);
        return t;
    });
    private volatile long lastPrefetchTime = 0L;

    public NewsBiasService(YahooFinanceClient yahooClient, HeadlineSentimentScorer scorer) {
        this.yahooClient = yahooClient;
        this.scorer = scorer;
    }

    public CompletableFuture<Void> prefetchAsync(List<String> tickers) {
        biasCache.clear();
        lastPrefetchTime = 0L;
        return CompletableFuture.runAsync(
                () -> tickers.parallelStream().forEach(this::prefetchOne),
                executor
        ).thenRun(() -> lastPrefetchTime = System.currentTimeMillis());
    }

    private void prefetchOne(String ticker) {
        try {
            List<String> headlines = yahooClient.fetchHeadlines(ticker);
            NewsBias bias = scorer.score(headlines);
            biasCache.put(ticker.toUpperCase(), bias);
        } catch (Throwable t) {
            log.warn("prefetchOne: failed for {} — {}", ticker, t.getMessage());
        }
    }

    public NewsBias getBias(String ticker) {
        return biasCache.getOrDefault(ticker.toUpperCase(), NewsBias.NEUTRAL);
    }

    public void invalidateCache() {
        biasCache.clear();
        lastPrefetchTime = 0L;
    }

    public boolean isCacheStale() {
        return System.currentTimeMillis() - lastPrefetchTime > 3_600_000L;
    }
}
