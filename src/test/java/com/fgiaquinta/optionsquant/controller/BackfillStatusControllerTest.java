package com.fgiaquinta.optionsquant.controller;

import com.fgiaquinta.optionsquant.candle.backfill.BackfillProgressTracker;
import com.fgiaquinta.optionsquant.candle.backfill.BackfillStatusSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for BackfillStatusController.
 * No Spring context — direct controller instantiation with mocks.
 */
class BackfillStatusControllerTest {

    private BackfillProgressTracker tracker;
    private BackfillStatusController controller;

    @BeforeEach
    void setUp() {
        tracker = mock(BackfillProgressTracker.class);
        controller = new BackfillStatusController(tracker);
    }

    // ── T7 ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("T7: getStatus_returns200WithExpectedJsonStructure — all required fields present")
    void getStatus_returns200WithExpectedJsonStructure() {
        BackfillStatusSnapshot snapshot = new BackfillStatusSnapshot(
                "DAY_1", 50, 200, 25.0, 3.5, 42.8, 2, true
        );
        when(tracker.getStatus()).thenReturn(snapshot);

        ResponseEntity<BackfillStatusSnapshot> response = controller.getStatus();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        BackfillStatusSnapshot body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.timeframe()).isEqualTo("DAY_1");
        assertThat(body.completedTickers()).isEqualTo(50);
        assertThat(body.totalTickers()).isEqualTo(200);
        assertThat(body.progressPercent()).isEqualTo(25.0);
        assertThat(body.velocityPerMin()).isEqualTo(3.5);
        assertThat(body.etaMinutes()).isEqualTo(42.8);
        assertThat(body.errors()).isEqualTo(2);
        assertThat(body.running()).isTrue();
    }

    // ── T8 ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("T8: getStatus_whenBackfillNotRunning_returnsIdleResponse — all zeros, timeframe=IDLE")
    void getStatus_whenBackfillNotRunning_returnsIdleResponse() {
        BackfillStatusSnapshot idleSnapshot = new BackfillStatusSnapshot(
                "IDLE", 0, 0, 0.0, 0.0, 0.0, 0, false
        );
        when(tracker.getStatus()).thenReturn(idleSnapshot);

        ResponseEntity<BackfillStatusSnapshot> response = controller.getStatus();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        BackfillStatusSnapshot body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.timeframe()).isEqualTo("IDLE");
        assertThat(body.completedTickers()).isZero();
        assertThat(body.totalTickers()).isZero();
        assertThat(body.progressPercent()).isZero();
        assertThat(body.velocityPerMin()).isZero();
        assertThat(body.etaMinutes()).isZero();
        assertThat(body.errors()).isZero();
        assertThat(body.running()).isFalse();
    }
}
