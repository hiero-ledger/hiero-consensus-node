// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.utility;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;

/**
 * Describes various mathematically important thresholds.
 */
public enum Threshold {
    /**
     * In order to meet this threshold, the part must be &ge;1/3 of the whole.
     */
    STRONG_MINORITY(Threshold::isStrongMinority),
    /**
     * In order to meet this threshold, the part must be &gt;1/2 of the whole.
     */
    MAJORITY(Threshold::isMajority),
    /**
     * In order to meet this threshold, the part must be &gt;2/3 of the whole.
     */
    SUPER_MAJORITY(Threshold::isSuperMajority);

    /**
     * A method that evaluates a threshold.
     */
    @FunctionalInterface
    private interface ThresholdEvaluator {
        /**
         * Check if a threshold is satisfied by a given ratio.
         *
         * @param part  the numerator
         * @param whole the denominator
         * @return true if the threshold is satisfied by the provided ratio
         */
        boolean isSatisfiedBy(long part, long whole);
    }

    /**
     * The method that evaluates this threshold.
     */
    private final ThresholdEvaluator evaluator;

    /**
     * Constructor
     *
     * @param evaluator the method that evaluates this threshold
     */
    Threshold(@NonNull final ThresholdEvaluator evaluator) {
        this.evaluator = Objects.requireNonNull(evaluator);
    }

    /**
     * Check if a threshold is satisfied by a given ratio.
     *
     * @param part  the numerator
     * @param whole the denominator
     * @return true if the threshold is satisfied by the provided ratio
     */
    public boolean isSatisfiedBy(final long part, final long whole) {
        return evaluator.isSatisfiedBy(part, whole);
    }

    /**
     * Is the part more than 2/3 of the whole?
     *
     * @param part  a long value, the fraction of the whole being compared
     * @param whole a long value, the whole being considered (such as the sum of the entire weight)
     * @return true if part is more than two thirds of the whole
     */
    private static boolean isSuperMajority(final long part, final long whole) {

        // For non-negative integers p and w,
        // the following three inequalities are
        // mathematically equivalent (for
        // infinite precision real computations):
        //
        // p > w * 2 / 3
        //
        // p > floor(w * 2 / 3)
        //
        // p > floor(w / 3) * 2 + floor((w mod 3) * 2 / 3)
        //
        // Therefore, given that Java long division
        // rounds toward zero, it is equivalent to do
        // the following:
        //
        // p > w / 3 * 2 + (w % 3) * 2 / 3;
        //
        // That avoids overflow for p and w
        // if they are positive long variable.

        return part > whole / 3 * 2 + (whole % 3) * 2 / 3;
    }

    /**
     * Is the part 1/3 or more of the whole?
     *
     * @param part  a long value, the fraction of the whole being compared
     * @param whole a long value, the whole being considered (such as the sum of the entire weight)
     * @return true if part is greater than or equal to one third of the whole
     */
    private static boolean isStrongMinority(final long part, final long whole) {

        // Java long division rounds down, but in this case we instead want to round up.
        // If whole is divisible by three then floor(whole/3) == ceil(whole/3)
        // If whole is not divisible by three then floor(whole/3) + 1 == ceil(whole/3)

        return part >= (whole / 3) + ((whole % 3 == 0) ? 0 : 1);
    }

    /**
     * Is the part more than 1/2 of the whole?
     *
     * @param part  a long value, the fraction of the whole being compared
     * @param whole a long value, the whole being considered (such as the sum of the entire weight)
     * @return true if part is greater or equal to one half of the whole
     */
    private static boolean isMajority(final long part, final long whole) {
        return part >= (whole / 2) + 1;
    }
}
