package com.fgiaquinta.optionsquant.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * Persisted runtime ticker configuration (optional file {@code data/ticker-runtime.json}).
 * Overrides YAML when {@code universe} / {@code hot} are non-empty.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TickerRuntimeConfigPayload(
        List<String> universe,
        List<String> hot,
        Map<String, TickerFundamentalPayload> fundamentals
) {
    public TickerRuntimeConfigPayload {
        if (universe == null) universe = List.of();
        if (hot == null) hot = List.of();
        if (fundamentals == null) fundamentals = Map.of();
    }
}
