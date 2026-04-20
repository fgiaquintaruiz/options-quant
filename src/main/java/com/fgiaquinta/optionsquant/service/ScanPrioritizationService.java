package com.fgiaquinta.optionsquant.service;

import com.fgiaquinta.optionsquant.config.ScannerProperties;
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
     * Reorders {@code remaining} tickers (already excluding hot list). NATURAL mode preserves iteration order.
     */
    public List<String> orderRemainingTickers(List<String> remaining) {
        if (remaining == null || remaining.isEmpty()) {
            return List.of();
        }
        if (scannerProperties.prioritizationMode() == ScannerProperties.PrioritizationMode.NATURAL) {
            return new ArrayList<>(remaining);
        }

        Map<String, Double> fundamentals = newsFilterService.getFundamentalScoresMap();
        Set<String> priorityTier = new HashSet<>(newsFilterService.getPriorityTickers());

        final double fwRaw = scannerProperties.hybridFundamentalWeight();
        final double mwRaw = scannerProperties.hybridMemoryWeight();
        double sum = fwRaw + mwRaw;
        if (sum <= 0) {
            sum = 1;
        }
        final double fw = fwRaw / sum;
        final double mw = mwRaw / sum;

        ToDoubleFunction<String> hybridScore = t -> {
            String u = t.toUpperCase();
            double f = fundamentals.getOrDefault(u, 0.0);
            double m = tickerMemory.getLearningPriorityScore(u);
            return fw * f + mw * m;
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

        log.debug("Hybrid scan order: {} priority-tier, {} other (fund weight={}, memory weight={})",
                inPriority.size(), notInPriority.size(), fw, mw);
        return out;
    }
}
