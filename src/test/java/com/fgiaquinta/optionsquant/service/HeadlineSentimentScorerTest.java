package com.fgiaquinta.optionsquant.service;

import com.fgiaquinta.optionsquant.domain.NewsBias;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HeadlineSentimentScorerTest {

    private HeadlineSentimentScorer scorer;

    @BeforeEach
    void setUp() {
        scorer = new HeadlineSentimentScorer();
    }

    @Test
    void score_emptyList_returnsNeutral() {
        assertThat(scorer.score(List.of())).isEqualTo(NewsBias.NEUTRAL);
    }

    @Test
    void score_singlePositiveHeadline_returnsCall() {
        assertThat(scorer.score(List.of("earnings beat expectations"))).isEqualTo(NewsBias.CALL);
    }

    @Test
    void score_singleNegativeHeadline_returnsPut() {
        assertThat(scorer.score(List.of("Company misses guidance, loss reported"))).isEqualTo(NewsBias.PUT);
    }

    @Test
    void score_noKeywordMatch_returnsNeutral() {
        assertThat(scorer.score(List.of("quarterly report mixed results"))).isEqualTo(NewsBias.NEUTRAL);
    }

    @Test
    void score_multiplePositiveKeywordsCaseInsensitive_returnsCall() {
        assertThat(scorer.score(List.of("BEAT record surge upgrade"))).isEqualTo(NewsBias.CALL);
    }

    @Test
    void score_multipleNegativeKeywords_returnsPut() {
        assertThat(scorer.score(List.of("miss downgrade weak below disappoints"))).isEqualTo(NewsBias.PUT);
    }

    @Test
    void score_balancedPositiveAndNegative_returnsNeutral() {
        // beat (+1), surge (+1) vs miss (-1), weak (-1) → raw=0 → NEUTRAL
        assertThat(scorer.score(List.of("company beat on revenue and surge in orders but miss on guidance and weak margins"))).isEqualTo(NewsBias.NEUTRAL);
    }
}
