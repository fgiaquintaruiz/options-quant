package com.fgiaquinta.optionsquant.dto;

import java.time.Instant;
import java.util.Map;

/**
 * Envelope returned by {@code GET /live-ui/scan-scores}.
 *
 * <ul>
 *   <li>{@code scoresByTicker} — latest scan-score breakdown per ticker (empty before first scan)</li>
 *   <li>{@code scanStartedAt} — UTC instant when the last {@code setScanScores()} was called;
 *       {@code null} before the first scan runs</li>
 * </ul>
 */
public record ScanScoresResponse(
        Map<String, ScanScoreBreakdown> scoresByTicker,
        Instant scanStartedAt
) {}
