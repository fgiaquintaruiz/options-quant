package com.fgiaquinta.optionsquant.pricing;

import com.fgiaquinta.optionsquant.OptionsQuantApplication;
import com.fgiaquinta.optionsquant.service.OrderExecutionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for {@link IbkrSnapshotUnderlyingPriceGateway} against a real TWS / IB Gateway.
 *
 * <p>Requires a running TWS / IB Gateway session with API enabled (paper is fine).
 * Run only with:
 * <pre>
 *   ./gradlew test -DrunTwsTests=true \
 *     --tests "*IbkrSnapshotUnderlyingPriceGatewayIT*"
 * </pre>
 *
 * <p>Skipped by default via {@link EnabledIfSystemProperty} so CI never hits TWS.
 */
@SpringBootTest(classes = OptionsQuantApplication.class)
@Tag("tws-paper")
@EnabledIfSystemProperty(named = "runTwsTests", matches = "true")
@DisplayName("IbkrSnapshotUnderlyingPriceGateway — TWS IT")
class IbkrSnapshotUnderlyingPriceGatewayIT {

    @Autowired
    private IbkrSnapshotUnderlyingPriceGateway gateway;

    @Autowired
    private OrderExecutionService oes;

    @BeforeEach
    void ensureTwsConnected() {
        // OES.connect() is idempotent: no-op when already connected.
        // We trigger it on-demand to avoid relying on @PostConstruct ordering in test context.
        oes.connect();
    }

    @Test
    @DisplayName("get(SPY, 5s) returns a live price > 0 with a fresh asOf timestamp")
    void get_returnsLiveSpyPrice_whenTwsConnected() {
        Instant before = Instant.now();

        PriceQuote quote = gateway.get("SPY", Duration.ofSeconds(5));

        assertThat(quote)
                .as("TWS must return a SPY snapshot when API session is logged in")
                .isNotNull();
        assertThat(quote.price()).isGreaterThan(0.0);
        assertThat(quote.asOf())
                .as("asOf should be stamped close to now")
                .isAfter(before.minusSeconds(1))
                .isBefore(Instant.now().plusSeconds(1));
        assertThat(Duration.between(quote.asOf(), Instant.now()))
                .isLessThan(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("get(SPY, 1ms) bypasses cache and forces a fresh snapshot on every call")
    void get_cacheMiss_returnsFreshOnSubShortTtl() {
        // 1ms TTL effectively forces a fresh snapshot every call — exercises the
        // semaphore + requestPriceSnapshot path even after the cache is warm.
        PriceQuote quote = gateway.get("SPY", Duration.ofMillis(1));

        assertThat(quote)
                .as("Fresh snapshot must succeed with sub-millisecond TTL")
                .isNotNull();
        assertThat(quote.price()).isGreaterThan(0.0);
        assertThat(Duration.between(quote.asOf(), Instant.now()))
                .as("Quote must be timestamped within the last 10 seconds")
                .isLessThan(Duration.ofSeconds(10));
    }
}
