// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.result;

/**
 * Common functionality of all results that were collected during an Otter test.
 */
public interface OtterResult {

    /**
     * Clear the result. All results that have been collected previously are discarded.
     */
    void clear();

    /**
     * Returns whether {@link #clear()} has been called on this result.
     *
     * @return {@code true} if {@link #clear()} has been called on this result, {@code false} otherwise
     */
    boolean isCleared();

    /**
     * Returns whether all results that were created so far have arrived. An environment that delivers results
     * asynchronously may still have some in transit. Assertions that need complete results fail if this method returns
     * {@code false}.
     *
     * @return {@code true} if all results have arrived, {@code false} otherwise
     */
    default boolean allResultsArrived() {
        return true;
    }
}
