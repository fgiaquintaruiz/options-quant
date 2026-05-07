package com.fgiaquinta.optionsquant.controller.dto;

import com.fgiaquinta.optionsquant.domain.TickerInfo;

/**
 * Wire-safe projection of {@link TickerInfo} for HTTP responses.
 * Separates the domain record from the HTTP contract.
 */
public record TickerInfoDto(
        String ticker,
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
    public static TickerInfoDto from(TickerInfo t) {
        return new TickerInfoDto(
                t.ticker(),
                t.companyName(),
                t.sector(),
                t.marketCapBillion(),
                t.peRatio(),
                t.dividendYield(),
                t.beta(),
                t.epsGrowth(),
                t.revenueGrowth(),
                t.debtToEquity(),
                t.roic(),
                t.notes()
        );
    }
}
