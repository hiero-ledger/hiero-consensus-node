// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.event.creator.impl.tipset;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Stores a weighed tipset advancement score.
 * <p>
 * If zero weight nodes were not a thing, we could use a long as a tipset advancement score. Or, if it was ok to ignore
 * zero weight nodes, we could do the same as well. But since we don't want to allow zero sake nodes to get stale
 * events, we need to have a mechanism for separately tracking when zero weight nodes have advanced.
 *
 * @param advancementWeight          the advancement weight provided by nodes with non-zero weight. Contributes to
 *                                   meeting the threshold required to advance the current snapshot.
 * @param zeroWeightAdvancementCount the advancement provided by nodes with zero weight, this is incremented by one for
 *                                   each zero weight node that advances. Does not contribute to meeting the threshold
 *                                   required to advance the current snapshot.
 */
public record TipsetAdvancementWeight(long advancementWeight, long zeroWeightAdvancementCount) {

    /**
     * Zero advancement weight. For convenience.
     */
    public static final TipsetAdvancementWeight ZERO_ADVANCEMENT_WEIGHT = TipsetAdvancementWeight.of(0, 0);

    /**
     * Create a new instance of a tipset advancement score
     *
     * @param advancementWeight          the advancement weight provided by nodes with non-zero weight
     * @param zeroWeightAdvancementCount the number of advancing zero weight nodes
     * @return a new instance of a tipset advancement score
     */
    public static TipsetAdvancementWeight of(final long advancementWeight, final long zeroWeightAdvancementCount) {
        return new TipsetAdvancementWeight(advancementWeight, zeroWeightAdvancementCount);
    }

    /**
     * Subtract two weights and return the result.
     *
     * @param that the weight to subtract from this weight
     * @return the result of subtracting the given weight from this weight
     */
    @NonNull
    public TipsetAdvancementWeight minus(@NonNull final TipsetAdvancementWeight that) {
        return new TipsetAdvancementWeight(
                advancementWeight - that.advancementWeight,
                zeroWeightAdvancementCount - that.zeroWeightAdvancementCount);
    }

    /**
     * Add two weights and return the result.
     *
     * @param that the weight to add to this weight
     * @return the result of adding the given weight to this weight
     */
    @NonNull
    public TipsetAdvancementWeight plus(@NonNull final TipsetAdvancementWeight that) {
        return new TipsetAdvancementWeight(
                advancementWeight + that.advancementWeight,
                zeroWeightAdvancementCount + that.zeroWeightAdvancementCount);
    }

    /**
     * Check if this weight is greater than the given weight. First {@link #advancementWeight()} is compared. If that is
     * equal, then {@link #zeroWeightAdvancementCount()} breaks the tie.
     *
     * @param that the weight to compare to
     * @return true if this weight is greater than the given weight
     */
    public boolean isGreaterThan(@NonNull final TipsetAdvancementWeight that) {
        return advancementWeight > that.advancementWeight
                || (advancementWeight == that.advancementWeight
                        && zeroWeightAdvancementCount > that.zeroWeightAdvancementCount);
    }

    /**
     * Check if this weight is zero.
     */
    public boolean isNonZero() {
        return advancementWeight != 0 || zeroWeightAdvancementCount != 0;
    }
}
