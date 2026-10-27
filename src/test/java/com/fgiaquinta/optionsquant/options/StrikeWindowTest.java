package com.fgiaquinta.optionsquant.options;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercise 1. Given the full strike list of an option chain and the underlying price at 09:30,
 * select the recording window: the 5 strikes below and the 5 above the price. If the price sits
 * exactly on a strike, that strike is added as an eleventh position. The window is fixed for the
 * whole trading day.
 */
class StrikeWindowTest {

    private final StrikeWindow window = new StrikeWindow();

    // ─── Happy paths ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("price between two strikes returns 5 below and 5 above, ascending")
    void select_priceBetweenStrikes_returnsFiveBelowAndFiveAbove() {
        final List<Double> result = window.select(range(170, 190, 1), 180.50);

        assertThat(result).containsExactly(176.0, 177.0, 178.0, 179.0, 180.0,
                181.0, 182.0, 183.0, 184.0, 185.0);
    }

    @Test
    @DisplayName("price exactly on a strike adds that strike as an eleventh position")
    void select_priceOnStrike_returnsElevenStrikesIncludingMatch() {
        final List<Double> result = window.select(range(170, 190, 1), 180.00);

        assertThat(result).containsExactlyElementsOf(range(175, 185, 1));
    }

    @Test
    @DisplayName("unsorted input gives the same ascending result")
    void select_unsortedInput_returnsSameAscendingResult() {
        final List<Double> shuffled = range(170, 190, 1);
        Collections.reverse(shuffled);
        Collections.swap(shuffled, 3, 17);

        final List<Double> result = window.select(shuffled, 180.50);

        assertThat(result).containsExactly(176.0, 177.0, 178.0, 179.0, 180.0,
                181.0, 182.0, 183.0, 184.0, 185.0);
    }

    @Test
    @DisplayName("duplicate strikes in the input are counted once")
    void select_duplicateStrikes_areIgnored() {
        final List<Double> withDuplicates = range(170, 190, 1);
        withDuplicates.addAll(List.of(178.0, 179.0, 180.0, 180.0, 181.0, 182.0));

        final List<Double> result = window.select(withDuplicates, 180.50);

        assertThat(result).containsExactly(176.0, 177.0, 178.0, 179.0, 180.0,
                181.0, 182.0, 183.0, 184.0, 185.0);
    }

    @Test
    @DisplayName("non-uniform strike spacing still picks the 5 nearest on each side")
    void select_nonUniformSpacing_picksNearestFiveOnEachSide() {
        final List<Double> strikes = new ArrayList<>(List.of(170.0, 172.5, 175.0, 177.5, 180.0,
                181.0, 182.0, 183.0, 184.0, 185.0, 186.0, 187.0, 190.0));

        final List<Double> result = window.select(strikes, 182.40);

        assertThat(result).containsExactly(177.5, 180.0, 181.0, 182.0, 183.0,
                184.0, 185.0, 186.0, 187.0, 190.0);
    }

    // ─── Edge: not enough strikes on one side ────────────────────────────────

    @Test
    @DisplayName("only 2 strikes above: window shifts down and still returns 10 (8 below + 2 above)")
    void select_notEnoughStrikesAbove_shiftsWindowDown() {
        final List<Double> result = window.select(range(170, 183, 1), 181.50);

        assertThat(result).containsExactlyElementsOf(range(174, 183, 1));
    }

    @Test
    @DisplayName("only 2 strikes below: window shifts up and still returns 10 (2 below + 8 above)")
    void select_notEnoughStrikesBelow_shiftsWindowUp() {
        final List<Double> result = window.select(range(170, 183, 1), 171.50);

        assertThat(result).containsExactlyElementsOf(range(170, 179, 1));
    }

    // ─── Invalid input ───────────────────────────────────────────────────────

    @Test
    @DisplayName("fewer than 10 distinct strikes throws IllegalArgumentException")
    void select_fewerThanTenDistinctStrikes_throwsIllegalArgument() {
        // 12 entries but only 9 distinct values
        final List<Double> strikes = range(170, 178, 1);
        strikes.addAll(List.of(170.0, 171.0, 172.0));

        assertThatThrownBy(() -> window.select(strikes, 174.50))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("empty strike list throws IllegalArgumentException")
    void select_emptyList_throwsIllegalArgument() {
        assertThatThrownBy(() -> window.select(new ArrayList<>(), 180.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("null strike list throws NullPointerException")
    void select_nullList_throwsNullPointer() {
        assertThatThrownBy(() -> window.select(null, 180.0))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("null price throws NullPointerException")
    void select_nullPrice_throwsNullPointer() {
        assertThatThrownBy(() -> window.select(range(170, 190, 1), null))
                .isInstanceOf(NullPointerException.class);
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /** Builds a mutable list of strikes from {@code from} to {@code to} inclusive. */
    private static List<Double> range(final int from, final int to, final int step) {
        final List<Double> strikes = new ArrayList<>();
        for (int s = from; s <= to; s += step) {
            strikes.add((double) s);
        }
        return strikes;
    }
}
