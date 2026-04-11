package com.fgiaquinta.optionsquant.domain;

/**
 * Ticker information with fundamental analysis data.
 */
public record TickerInfo(
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
    /**
     * Checks if this is a high-quality stock based on fundamentals.
     */
    public boolean isHighQuality() {
        return (peRatio == null || (peRatio > 0 && peRatio < 30))
                && (roic == null || roic > 15)
                && (debtToEquity == null || debtToEquity < 1.0);
    }

    /**
     * Checks if this is a growth stock.
     */
    public boolean isGrowthStock() {
        return epsGrowth != null && epsGrowth > 15;
    }

    /**
     * Checks if this is a value stock.
     */
    public boolean isValueStock() {
        return peRatio != null && peRatio > 0 && peRatio < 15
                && dividendYield != null && dividendYield > 2;
    }

    /**
     * Gets risk level based on beta.
     */
    public RiskLevel getRiskLevel() {
        if (beta == null) return RiskLevel.MODERATE;
        if (beta < 0.8) return RiskLevel.LOW;
        if (beta < 1.2) return RiskLevel.MODERATE;
        if (beta < 1.8) return RiskLevel.HIGH;
        return RiskLevel.VERY_HIGH;
    }

    public enum RiskLevel {
        LOW, MODERATE, HIGH, VERY_HIGH
    }

    @Override
    public String toString() {
        return String.format("%s (%s) - %s", ticker, companyName, sector);
    }
}
