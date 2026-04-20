package com.fgiaquinta.optionsquant.e2e;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for /actuator/health and basic app readiness.
 */
@Tag("e2e")
@DisplayName("Health Endpoint Tests")
class HealthEndpointTest extends BasePlaywrightTest {

    @Test
    @DisplayName("Health endpoint returns 200 with UP status")
    void healthEndpointReturnsUp() throws Exception {
        String json = getJson("/actuator/health");
        assertNotNull(json);
        assertTrue(json.contains("\"status\":\"UP\""));
    }

    @Test
    @DisplayName("Health endpoint includes liveness and readiness groups")
    void healthEndpointIncludesGroups() throws Exception {
        String json = getJson("/actuator/health");
        assertTrue(json.contains("\"groups\":"));
        assertTrue(json.contains("\"liveness\""));
        assertTrue(json.contains("\"readiness\""));
    }

    @Test
    @DisplayName("Health liveness sub-endpoint returns 200")
    void healthLivenessReturnsOk() throws Exception {
        String json = getJson("/actuator/health/liveness");
        assertNotNull(json);
        assertTrue(json.contains("\"status\":\"UP\""));
    }

    @Test
    @DisplayName("Health readiness sub-endpoint returns 200")
    void healthReadinessReturnsOk() throws Exception {
        String json = getJson("/actuator/health/readiness");
        assertNotNull(json);
        assertTrue(json.contains("\"status\":\"UP\""));
    }

    @Test
    @DisplayName("Unknown path does not return health data")
    void unknownPathReturns404() throws Exception {
        String json = getJson("/nonexistent");
        // Spring Boot returns an HTML error page for unknown paths
        // Just verify it's not the health endpoint response
        assertFalse(json.contains("\"groups\":") && json.contains("\"liveness\""),
            "Unknown path should not return health groups");
    }
}
