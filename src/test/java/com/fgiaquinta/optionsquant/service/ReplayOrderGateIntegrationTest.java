package com.fgiaquinta.optionsquant.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.fgiaquinta.optionsquant.OptionsQuantApplication;
import com.fgiaquinta.optionsquant.service.AccountManager;
import com.fgiaquinta.optionsquant.service.IbkrService;
import com.fgiaquinta.optionsquant.service.OrderExecutionService;
import com.fgiaquinta.optionsquant.infrastructure.MetricsService;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for {@link ReplayOrderGate} wired through the Spring context.
 *
 * <p>Verifies that the gate reads {@code replay.max-orders-per-minute} from
 * {@code application.yml} (value: 10) and rejects the 11th attempt within the
 * same 60-second sliding window.
 *
 * <p>No TWS connection required — all IBKR-touching beans are mocked.
 */
@Tag("integration")
@SpringBootTest(classes = OptionsQuantApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ReplayOrderGateIntegrationTest {

    // ── Mock every bean that would open a real socket / external connection ──

    @MockitoBean
    private AccountManager accountManager;

    @MockitoBean
    private IbkrService ibkrService;

    @MockitoBean
    private OrderExecutionService orderExecutionService;

    @MockitoBean
    private MetricsService metricsService;

    // ── Subject under test — retrieved from the real Spring context ──────────

    @Autowired
    private ReplayOrderGate gate;

    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("El gate permite exactamente 10 órdenes y rechaza el intento nro 11 dentro de 60 segundos")
    void replayOrderGate_rejectsEleventhAttemptWithinWindow() {
        gate.reset(); // start from a clean window regardless of prior context

        for (int i = 1; i <= 10; i++) {
            boolean allowed = gate.tryConsume();
            assertThat(allowed)
                    .as("Intento %d de 10 debe ser permitido", i)
                    .isTrue();
        }

        boolean eleventh = gate.tryConsume();
        assertThat(eleventh)
                .as("El intento nro 11 dentro de la misma ventana de 60s debe ser rechazado")
                .isFalse();
    }

    @Test
    @DisplayName("El gate expone count() == 10 después de consumir el máximo permitido")
    void replayOrderGate_countReflectsMaxAfterSaturation() {
        gate.reset();

        for (int i = 0; i < 10; i++) gate.tryConsume();

        assertThat(gate.count())
                .as("count() debe reflejar los 10 slots consumidos dentro de la ventana")
                .isEqualTo(10);
    }
}
