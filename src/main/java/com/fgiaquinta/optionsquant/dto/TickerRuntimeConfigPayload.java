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
        Map<String, TickerFundamentalPayload> fundamentals,
        Integer hotTickerCount
) {
    /**
     * Compact constructor: {@code universe} and {@code fundamentals} are normalized to empty collections
     * when null, but {@code hot} is left nullable so callers can distinguish:
     * <ul>
     *   <li>{@code hot == null} — field absent or explicitly null in JSON → fall back to YAML</li>
     *   <li>{@code hot.isEmpty()} — explicit {@code []} in JSON → return empty (override, no YAML fallback)</li>
     *   <li>{@code hot} non-empty — return runtime list</li>
     * </ul>
     *
     * <p>{@code hotTickerCount == null} means "not overridden — use YAML default".
     */
    public TickerRuntimeConfigPayload {
        if (universe == null) universe = List.of();
        // hot intentionally NOT normalized — null means "not provided, fall back to YAML"
        if (fundamentals == null) fundamentals = Map.of();
        // hotTickerCount intentionally NOT normalized — null means "use YAML ibkr.hot-ticker-count"
    }
}
