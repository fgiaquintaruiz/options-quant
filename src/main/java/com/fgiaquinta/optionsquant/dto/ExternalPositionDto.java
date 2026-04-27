package com.fgiaquinta.optionsquant.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Response DTO for the {@code GET /live-ui/external-positions} endpoint.
 *
 * <p>Represents a position held in TWS that was NOT opened by this application
 * (i.e., ticker is absent from {@code LiveModeController.executedTrades}).
 *
 * <p>All JSON keys are snake_case via {@link JsonProperty} annotations on each component.
 * Jackson's default camelCase naming is overridden per-field to avoid a global
 * {@code PropertyNamingStrategy} that would affect other DTOs.
 *
 * @param ticker            Ticker symbol (e.g. "NVDA")
 * @param contractType      Contract type: "STK" or "OPT"
 * @param quantity          Number of shares / contracts held
 * @param avgCost           Average cost basis reported by TWS
 * @param snapshotTimestamp ISO-8601 UTC timestamp of the last snapshot capture
 * @param classification    Always {@code "external"} for this endpoint
 */
public record ExternalPositionDto(
        @JsonProperty("ticker")               String ticker,
        @JsonProperty("contract_type")        String contractType,
        @JsonProperty("quantity")             int quantity,
        @JsonProperty("avg_cost")             double avgCost,
        @JsonProperty("snapshot_timestamp")   String snapshotTimestamp,
        @JsonProperty("classification")       String classification
) {
}
