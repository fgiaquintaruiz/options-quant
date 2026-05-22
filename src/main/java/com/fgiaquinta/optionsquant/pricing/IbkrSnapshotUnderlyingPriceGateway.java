package com.fgiaquinta.optionsquant.pricing;

import com.fgiaquinta.optionsquant.service.OrderExecutionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class IbkrSnapshotUnderlyingPriceGateway implements UnderlyingPriceGateway {

    private static final int TWS_MAX_CONCURRENT_SNAPSHOTS = 5;
    private static final Duration INTERNAL_TIMEOUT = Duration.ofSeconds(3);
    private static final int LAST_PRICE_FIELD = 4;

    private final OrderExecutionService oes;
    private final Clock clock;
    private final Semaphore snapshotPermits = new Semaphore(TWS_MAX_CONCURRENT_SNAPSHOTS);
    private final ConcurrentHashMap<String, PriceQuote> cache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, Double> pendingPrices = new ConcurrentHashMap<>();

    @Autowired
    public IbkrSnapshotUnderlyingPriceGateway(OrderExecutionService oes) {
        this(oes, Clock.systemUTC());
    }

    // Package-private for tests
    IbkrSnapshotUnderlyingPriceGateway(OrderExecutionService oes, Clock clock) {
        this.oes = oes;
        this.clock = clock;
        // Constructor-time registration so tests can capture the consumer via ArgumentCaptor
        // in @BeforeEach before any assertions
        oes.registerTickPriceConsumer(this::onTickPrice);
    }

    private void onTickPrice(TickPriceEvent event) {
        if (event.field() != LAST_PRICE_FIELD) return;
        pendingPrices.put(event.reqId(), event.price());
    }

    @Override
    public PriceQuote get(String ticker, Duration maxAge) {
        // Cache lookup BEFORE acquiring semaphore — fast path
        PriceQuote cached = cache.get(ticker);
        if (cached != null && !isStale(cached, maxAge)) {
            return cached;
        }

        try {
            snapshotPermits.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        try {
            int reqId = oes.requestPriceSnapshot(ticker);
            Double price = waitForTick(reqId, INTERNAL_TIMEOUT);
            if (price == null) return null;
            PriceQuote quote = new PriceQuote(price, clock.instant());
            cache.put(ticker, quote);
            return quote;
        } finally {
            snapshotPermits.release();
        }
    }

    private boolean isStale(PriceQuote quote, Duration maxAge) {
        Instant now = clock.instant();
        return Duration.between(quote.asOf(), now).compareTo(maxAge) > 0;
    }

    private Double waitForTick(int reqId, Duration timeout) {
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadlineNanos) {
            Double price = pendingPrices.remove(reqId);
            if (price != null) return price;
            try {
                TimeUnit.MILLISECONDS.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        pendingPrices.remove(reqId); // cleanup if tick arrives late
        return null;
    }
}
