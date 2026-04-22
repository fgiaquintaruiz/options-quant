package com.fgiaquinta.optionsquant.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fgiaquinta.optionsquant.domain.TickerInfo;

/**
 * JSON shape for manual ticker / fundamental overrides (ABM, {@code data/ticker-runtime.json}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record TickerFundamentalPayload(
        String companyName,
        String sector,
        Long marketCapBillion,
        Double peRatio,
        Double dividendYield,
        Double beta,
        Double epsGrowth,
        Double revenueGrowth,
        Double debtToEquity,
        Double roic,
        String notes
) {
    public static TickerFundamentalPayload fromTickerInfo(TickerInfo info) {
        if (info == null) return null;
        return new TickerFundamentalPayload(
                info.companyName(),
                info.sector(),
                info.marketCapBillion(),
                info.peRatio(),
                info.dividendYield(),
                info.beta(),
                info.epsGrowth(),
                info.revenueGrowth(),
                info.debtToEquity(),
                info.roic(),
                info.notes()
        );
    }

    public TickerInfo toTickerInfo(String ticker) {
        String sym = ticker == null ? "" : ticker.trim().toUpperCase();
        return new TickerInfo(
                sym,
                blankToNull(companyName) != null ? companyName.trim() : sym,
                blankToNull(sector),
                marketCapBillion,
                peRatio,
                dividendYield,
                beta,
                epsGrowth,
                revenueGrowth,
                debtToEquity,
                roic,
                blankToNull(notes)
        );
    }

    private static String blankToNull(String s) {
        if (s == null || s.isBlank()) return null;
        return s;
    }
}
