package com.fgiaquinta.optionsquant.options;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * Selects the strike window to record for an option chain: N strikes below and N above the
 * underlying price, N given at construction; a strike equal to the price counts as the first one below.
 *
 * <h2>Internals</h2>
 * <ol>
 * <li>The tree set takes the first element as a root, then the new elements are compared with the existing nodes,
 * if the new element is equal to a node is dropped, otherwise if it's bigger goes to the right
 *   side of the tree and if it's smaller goes to the left side of the tree</li>
 * <li>The final list will return the strikes in the below including the exact match strike and the above views so
 * there are no duplicates. How: the below view is headSet(price, true) and the above view is tailSet(price, false),
 * so a strike equal to the price appears only in the below view</li>
 * <li>minBelowSize is protecting against the below view taking more than a strikes per side, the aboveSizeBalanced
 * protects against that the above view has more elements than needed to reach the good number and belowSizeBalanced
 * is protecting against the below view taking more strikes than the above balance ones</li>
 * <li>Depends on the case if the below view is short above will
 * absorb it, if the above is short the below will absorb it, and because first we
 * identify the below size, then we compensate in the above and then we confirm the below with the above compensated.</li>
 * <li>The guard will guarantee that the adding both views we'll have the minimum number of strikes to choose
 * from because both views together hold at least 2N, whichever side ran out, the other one has enough for
 * the third step to reach exactly 2N, so no fourth step is needed</li>
 * <li>skip cuts the first n elements and limit truncates the list to no longer than, because in skip we're removing
 * the first elements that are away from the price so we need to substract the belowSizeBalanced to the total below view.
 * the above view is ascending, so its nearest strikes are the first ones, and limit counts what you keep while skip
 * counts what you throw away</li>
 * </ol>
 * <h2>Anchor</h2>
 * The method behaves like an adjustable sliding window of 2N strikes split by a red line at the price; when one side
 * runs short, the window slides into the other side.
 */
public final class StrikeWindow {

    private final int strikesPerSide;

    public StrikeWindow(int strikesPerSide) {
        if (strikesPerSide > 0) {
            this.strikesPerSide = strikesPerSide;
        } else {
            throw new IllegalArgumentException("Strikes per side needs to be greater than zero, got %s"
                    .formatted(strikesPerSide));
        }

    }

    public List<Double> select(final List<Double> strikes, final double underlyingPrice) {
        Objects.requireNonNull(strikes, "strikes");
        TreeSet<Double> sanitizedStrikes = new TreeSet<>(strikes);
        int totalStrikes = 2 * strikesPerSide;

        if (sanitizedStrikes.size() < totalStrikes) {
            throw new IllegalArgumentException("Need at least %s distinct strikes (2 x %s per side), got %s"
                    .formatted(totalStrikes, strikesPerSide, sanitizedStrikes.size()));
        }

        SortedSet<Double> below = sanitizedStrikes.headSet(underlyingPrice, true);
        SortedSet<Double> above = sanitizedStrikes.tailSet(underlyingPrice, false);

        Result result = balanceSides(below, totalStrikes, above);

        List<Double> belowFiltered = below.stream().skip(below.size() - result.belowSizeBalanced()).toList();
        List<Double> aboveFiltered = above.stream().limit(result.aboveSizeBalanced()).toList();

        List<Double> finalStrikes = new ArrayList<>(belowFiltered);
        finalStrikes.addAll(aboveFiltered);
        return finalStrikes;
    }

    private Result balanceSides(SortedSet<Double> below, int totalStrikes, SortedSet<Double> above) {
        int belowSize = below.size();
        int minBelowSize = Math.min(belowSize, strikesPerSide);
        int maxBelowSize = totalStrikes - minBelowSize;
        int aboveSizeBalanced = Math.min(above.size(), maxBelowSize);
        int maxAboveSize = totalStrikes - aboveSizeBalanced;
        int belowSizeBalanced = Math.min(belowSize, maxAboveSize);
        return new Result(aboveSizeBalanced, belowSizeBalanced);
    }

    private record Result(int aboveSizeBalanced, int belowSizeBalanced) {
    }
}
