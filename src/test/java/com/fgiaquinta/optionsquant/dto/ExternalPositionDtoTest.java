package com.fgiaquinta.optionsquant.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RED: Verifies ExternalPositionDto serializes to JSON with correct snake_case keys.
 * All tests fail until ExternalPositionDto is created (GREEN).
 */
class ExternalPositionDtoTest {

    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
    }

    @Test
    @DisplayName("externalPositionDto_serializes_withSnakeCaseKeys")
    void externalPositionDto_serializes_withSnakeCaseKeys() throws Exception {
        // GIVEN
        ExternalPositionDto dto = new ExternalPositionDto(
                "NVDA",
                "STK",
                100,
                87.50,
                "2026-04-26T14:30:00Z",
                "external"
        );

        // WHEN
        String json = mapper.writeValueAsString(dto);

        // THEN — assert snake_case key presence
        assertThat(json).contains("\"ticker\"");
        assertThat(json).contains("\"contract_type\"");
        assertThat(json).contains("\"quantity\"");
        assertThat(json).contains("\"avg_cost\"");
        assertThat(json).contains("\"snapshot_timestamp\"");
        assertThat(json).contains("\"classification\"");
    }

    @Test
    @DisplayName("externalPositionDto_serializes_withCorrectValues")
    void externalPositionDto_serializes_withCorrectValues() throws Exception {
        // GIVEN
        ExternalPositionDto dto = new ExternalPositionDto(
                "AAPL",
                "OPT",
                50,
                5.75,
                "2026-04-26T15:00:00Z",
                "external"
        );

        // WHEN
        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = mapper.readValue(mapper.writeValueAsString(dto), Map.class);

        // THEN — assert values match fields
        assertThat(parsed.get("ticker")).isEqualTo("AAPL");
        assertThat(parsed.get("contract_type")).isEqualTo("OPT");
        assertThat(parsed.get("quantity")).isEqualTo(50);
        assertThat(parsed.get("avg_cost")).isEqualTo(5.75);
        assertThat(parsed.get("snapshot_timestamp")).isEqualTo("2026-04-26T15:00:00Z");
        assertThat(parsed.get("classification")).isEqualTo("external");
    }

    @Test
    @DisplayName("externalPositionDto_doesNotContain_camelCaseKeys")
    void externalPositionDto_doesNotContain_camelCaseKeys() throws Exception {
        // GIVEN
        ExternalPositionDto dto = new ExternalPositionDto(
                "MSFT", "STK", 200, 300.0, "2026-04-26T16:00:00Z", "external"
        );

        // WHEN
        String json = mapper.writeValueAsString(dto);

        // THEN — camelCase keys must NOT appear
        assertThat(json).doesNotContain("contractType");
        assertThat(json).doesNotContain("avgCost");
        assertThat(json).doesNotContain("snapshotTimestamp");
    }

    @Test
    @DisplayName("externalPositionDto_accessors_returnConstructorValues")
    void externalPositionDto_accessors_returnConstructorValues() {
        // GIVEN
        ExternalPositionDto dto = new ExternalPositionDto(
                "TSLA", "STK", 30, 210.0, "2026-04-26T17:00:00Z", "external"
        );

        // THEN — record accessors work
        assertThat(dto.ticker()).isEqualTo("TSLA");
        assertThat(dto.contractType()).isEqualTo("STK");
        assertThat(dto.quantity()).isEqualTo(30);
        assertThat(dto.avgCost()).isEqualTo(210.0);
        assertThat(dto.snapshotTimestamp()).isEqualTo("2026-04-26T17:00:00Z");
        assertThat(dto.classification()).isEqualTo("external");
    }
}
