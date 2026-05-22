package com.fgiaquinta.optionsquant.pricing;

import com.fgiaquinta.optionsquant.service.OrderExecutionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.*;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IbkrSnapshotUnderlyingPriceGatewayTest {

    private static final Instant T0 = Instant.parse("2026-05-22T14:00:00Z");

    @Mock OrderExecutionService oes;
    @Captor ArgumentCaptor<Consumer<TickPriceEvent>> consumerCaptor;

    private Clock clockAtT0;
    private IbkrSnapshotUnderlyingPriceGateway gateway;
    private Consumer<TickPriceEvent> tickPriceConsumer;
    private ExecutorService exec;

    @BeforeEach
    void setUp() {
        clockAtT0 = Clock.fixed(T0, ZoneOffset.UTC);
        gateway = new IbkrSnapshotUnderlyingPriceGateway(oes, clockAtT0);
        verify(oes).registerTickPriceConsumer(consumerCaptor.capture());
        tickPriceConsumer = consumerCaptor.getValue();
        exec = Executors.newSingleThreadExecutor();
    }

    @Test
    void get_returnsQuote_whenTickPriceFiresInTime() throws Exception {
        // Arrange — fire tick from another thread after a short delay so get() blocks first
        Future<PriceQuote> future = exec.submit(() -> gateway.get("SPY", Duration.ofSeconds(5)));

        // Capture reqId from oes.requestPriceSnapshot("SPY") interaction
        // Adapter MUST call oes.requestPriceSnapshot(ticker) and get back an int reqId.
        ArgumentCaptor<String> tickerCaptor = ArgumentCaptor.forClass(String.class);
        verify(oes, timeout(2000)).requestPriceSnapshot(tickerCaptor.capture());
        assertEquals("SPY", tickerCaptor.getValue());

        // Fire LAST tick (field 4) — reqId 0 since the adapter manages its own reqIds
        // We don't assert on a specific reqId — we trust the adapter to filter by its own
        tickPriceConsumer.accept(new TickPriceEvent(0, 4, 420.50));

        // Act / Assert
        PriceQuote quote = future.get(3, TimeUnit.SECONDS);
        assertNotNull(quote);
        assertThat(quote.price()).isEqualTo(420.50);
        assertThat(quote.asOf()).isEqualTo(T0);
    }

    @Test
    void get_returnsCachedQuote_whenWithinTTL() throws Exception {
        // Warm cache
        Future<PriceQuote> first = exec.submit(() -> gateway.get("SPY", Duration.ofSeconds(5)));
        verify(oes, timeout(2000)).requestPriceSnapshot("SPY");
        tickPriceConsumer.accept(new TickPriceEvent(0, 4, 350.00));
        PriceQuote firstQuote = first.get(3, TimeUnit.SECONDS);
        assertNotNull(firstQuote);

        // Second call — clock has not advanced, cache must hit
        PriceQuote secondQuote = gateway.get("SPY", Duration.ofSeconds(5));

        assertSame(firstQuote, secondQuote);
        verify(oes, times(1)).requestPriceSnapshot("SPY");
    }

    @Test
    void get_returnsFreshQuote_whenCacheExpired() throws Exception {
        // Warm cache at T0
        Future<PriceQuote> first = exec.submit(() -> gateway.get("SPY", Duration.ofSeconds(5)));
        verify(oes, timeout(2000)).requestPriceSnapshot("SPY");
        tickPriceConsumer.accept(new TickPriceEvent(0, 4, 350.00));
        first.get(3, TimeUnit.SECONDS);

        // Replace clock with one at T+10s → beyond 5s TTL
        Clock clockAtT10 = Clock.fixed(T0.plusSeconds(10), ZoneOffset.UTC);
        gateway = new IbkrSnapshotUnderlyingPriceGateway(oes, clockAtT10);
        // Re-capture consumer for the new instance
        verify(oes, times(2)).registerTickPriceConsumer(consumerCaptor.capture());
        Consumer<TickPriceEvent> freshConsumer = consumerCaptor.getValue();

        Future<PriceQuote> second = exec.submit(() -> gateway.get("SPY", Duration.ofSeconds(5)));
        verify(oes, timeout(2000).times(2)).requestPriceSnapshot("SPY");
        freshConsumer.accept(new TickPriceEvent(0, 4, 360.00));

        PriceQuote freshQuote = second.get(3, TimeUnit.SECONDS);
        assertNotNull(freshQuote);
        assertThat(freshQuote.price()).isEqualTo(360.00);
    }

    @Test
    void get_returnsNull_whenTimeoutExceeded() throws Exception {
        // Consumer is never fired — gateway's internal 3s timeout must surface as null
        Future<PriceQuote> future = exec.submit(() -> gateway.get("SPY", Duration.ofSeconds(5)));

        PriceQuote result = future.get(5, TimeUnit.SECONDS);
        assertNull(result, "Expected null when no tick arrives within internal timeout");
    }
}
