package com.fgiaquinta.optionsquant.service;

import com.fgiaquinta.optionsquant.domain.NewsBias;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class HeadlineSentimentScorer {

    private static final Map<String, Integer> KEYWORDS = Map.ofEntries(
            Map.entry("beat", 1),
            Map.entry("beats", 1),
            Map.entry("record", 1),
            Map.entry("surge", 1),
            Map.entry("upgrade", 1),
            Map.entry("raised", 1),
            Map.entry("buyback", 1),
            Map.entry("outperform", 1),
            Map.entry("strong", 1),
            Map.entry("accelerating", 1),
            Map.entry("breakthrough", 1),
            Map.entry("dividend", 1),
            Map.entry("growth", 1),
            Map.entry("exceeds", 1),
            Map.entry("topped", 1),
            Map.entry("miss", -1),
            Map.entry("misses", -1),
            Map.entry("cut", -1),
            Map.entry("downgrade", -1),
            Map.entry("probe", -1),
            Map.entry("lawsuit", -1),
            Map.entry("investigation", -1),
            Map.entry("warning", -1),
            Map.entry("recall", -1),
            Map.entry("restructuring", -1),
            Map.entry("layoff", -1),
            Map.entry("weak", -1),
            Map.entry("below", -1),
            Map.entry("disappoints", -1),
            Map.entry("loss", -1),
            Map.entry("fraud", -1),
            Map.entry("halt", -1)
    );

    public NewsBias score(List<String> headlines) {
        if (headlines.isEmpty()) {
            return NewsBias.NEUTRAL;
        }
        String combined = String.join(" ", headlines).toLowerCase();
        int raw = KEYWORDS.entrySet().stream()
                .filter(e -> combined.contains(e.getKey()))
                .mapToInt(Map.Entry::getValue)
                .sum();
        return threshold(raw);
    }

    private NewsBias threshold(int raw) {
        int capped = Math.max(-3, Math.min(3, raw));
        double normalized = capped / 3.0;
        if (normalized > 0.2) return NewsBias.CALL;
        if (normalized < -0.2) return NewsBias.PUT;
        return NewsBias.NEUTRAL;
    }
}
