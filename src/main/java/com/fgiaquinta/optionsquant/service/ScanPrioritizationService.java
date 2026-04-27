package com.fgiaquinta.optionsquant.service;

import com.fgiaquinta.optionsquant.config.ScannerProperties;
import com.fgiaquinta.optionsquant.dto.ScanScoreBreakdown;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.ToDoubleFunction;
import java.util.stream.Collectors;

/**
 * Orders non-hot tickers for live scans: {@link ScannerProperties.PrioritizationMode#HYBRID}
 * blends CSV fundamentals with {@link TickerMemory} learning; priority top-10 tier is scanned before the rest.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScanPrioritizationService {

    private final NewsFilterService newsFilterService;
    private final TickerMemory tickerMemory;
    private final ScannerProperties scannerProperties;

    /**
     * Computes {@link ScanScoreBreakdown} for every ticker in {@code tickers}.
     * Weights are normalised so they always sum to 1.0.
     *
     * @param tickers list of ticker symbols (case-insensitive; stored as upper-case in result keys)
     * @return immutable map: upper-case ticker → breakdown
     */
    public Map<String, ScanScoreBreakdown> computeScores(List<String> tickers) {
        if (tickers == null || tickers.isEmpty()) {
            return Map.of();
        }

        Map<String, Double> fundamentals = newsFilterService.getFundamentalScoresMap();
        double[] weights = normaliseWeights();
        final double fw = weights[0];
        final double mw = weights[1];

        Map<String, ScanScoreBreakdown> result = new LinkedHashMap<>(tickers.size() * 2);
        for (String t : tickers) {
            String u = t.toUpperCase();
            double f = fundamentals.getOrDefault(u, 0.0);
            double m = tickerMemory.getLearningPriorityScore(u);
            double hybrid = fw * f + mw * m;
            result.put(u, new ScanScoreBreakdown(u, f, m, hybrid));
        }
        return Collections.unmodifiableMap(result);
    }

    // ── private helpers ────────────────────────────────────────────────────────

    /** Returns [fw, mw] normalised to sum=1. */
    private double[] normaliseWeights() {
        double fw = scannerProperties.hybridFundamentalWeight();
        double mw = scannerProperties.hybridMemoryWeight();
        double sum = fw + mw;
        if (sum <= 0) sum = 1;
        return new double[]{fw / sum, mw / sum};
    }

    /**
     * Reorders {@code remaining} tickers (already excluding hot list). NATURAL mode preserves iteration order.
     * Delegates score computation to {@link #computeScores} to avoid duplicated logic (DRY).
     */
    public List<String> orderRemainingTickers(List<String> remaining) {
        if (remaining == null || remaining.isEmpty()) {
            return List.of();
        }
        if (scannerProperties.prioritizationMode() == ScannerProperties.PrioritizationMode.NATURAL) {
            return new ArrayList<>(remaining);
        }

        Map<String, ScanScoreBreakdown> scores = computeScores(remaining);
        Set<String> priorityTier = new HashSet<>(newsFilterService.getPriorityTickers());

        ToDoubleFunction<String> hybridScore = t -> {
            ScanScoreBreakdown bd = scores.get(t.toUpperCase());
            return bd != null ? bd.hybridScore() : 0.0;
        };

        List<String> inPriority = remaining.stream()
                .filter(t -> priorityTier.contains(t.toUpperCase()))
                .sorted(Comparator.comparingDouble(hybridScore).reversed())
                .collect(Collectors.toList());

        List<String> notInPriority = remaining.stream()
                .filter(t -> !priorityTier.contains(t.toUpperCase()))
                .sorted(Comparator.comparingDouble(hybridScore).reversed())
                .collect(Collectors.toList());

        List<String> out = new ArrayList<>(inPriority.size() + notInPriority.size());
        out.addAll(inPriority);
        out.addAll(notInPriority);

        double[] w = normaliseWeights();
        log.debug("Hybrid scan order: {} priority-tier, {} other (fund weight={}, memory weight={})",
                inPriority.size(), notInPriority.size(), w[0], w[1]);
        return out;
    }
}
