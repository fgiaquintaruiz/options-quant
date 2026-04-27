package com.fgiaquinta.optionsquant.dto;

/**
 * Immutable score breakdown for a single ticker produced by
 * {@link com.fgiaquinta.optionsquant.service.ScanPrioritizationService#computeScores}.
 *
 * <ul>
 *   <li>{@code fundamentalScore} — normalised CSV fundamental score [0, 1]</li>
 *   <li>{@code memoryScore}      — learning/memory priority score [0, 1]</li>
 *   <li>{@code hybridScore}      — weighted blend: {@code fw*fundamental + mw*memory}</li>
 * </ul>
 */
public record ScanScoreBreakdown(
        String ticker,
        double fundamentalScore,
        double memoryScore,
        double hybridScore
) {}
