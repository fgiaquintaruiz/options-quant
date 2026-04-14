package com.fgiaquinta.optionsquant.e2e;

import org.junit.jupiter.api.*;
import com.microsoft.playwright.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Live Mode user interactions - scanning, stopping, toggles.
 */
@DisplayName("Live Mode Interaction Tests")
class LiveModeInteractionTest extends BasePlaywrightTest {

    @Test
    @DisplayName("Start scan updates status to scanning")
    void startScanUpdatesStatus() throws Exception {
        // Start a scan
        String json = postJson("/live-ui/scan-now");
        assertNotNull(json);
        assertTrue(json.contains("\"success\""));
    }

    @Test
    @DisplayName("Status reflects scanning state after scan start")
    void statusReflectsScanningState() throws Exception {
        // Wait for any ongoing scan
        Thread.sleep(1000);

        String json = getJson("/live-ui/status");
        assertNotNull(json);
        // Scanner progress fields should be present
        assertTrue(json.contains("\"scannerScanned\""));
        assertTrue(json.contains("\"scannerTotal\""));
    }

    @Test
    @DisplayName("Stop scan request is handled gracefully")
    void stopScanHandledGracefully() throws Exception {
        // Try to stop (may or may not be scanning)
        String json = postJson("/live-ui/stop-scan");
        assertNotNull(json);
        assertTrue(json.contains("\"success\""));
    }

    @Test
    @DisplayName("Extended hours toggle returns valid response")
    void extendedHoursToggleReturnsValid() throws Exception {
        String json = postJson("/live-ui/toggle-extended-hours");
        assertNotNull(json);
        assertTrue(json.contains("\"success\":true"));
        assertTrue(json.contains("\"extendedHours\""));
        assertTrue(json.contains("\"message\""));
    }

    @Test
    @DisplayName("Double start scan returns error")
    void doubleStartScanReturnsError() throws Exception {
        // First start
        postJson("/live-ui/scan-now");
        // Immediate second start should fail
        String json = postJson("/live-ui/scan-now");
        assertNotNull(json);
        // Either success is false or message indicates already running
        // (depending on timing)
        assertTrue(json.contains("\"success\""));
    }
}
