// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api.export.snapshot;

/**
 * A {@link DataPointSnapshot} that contains a set of states represented as a {@code boolean} values.
 *
 * @param <E> enum type representing possible states
 */
public interface StateSetDataPointSnapshot<E extends Enum<E>> extends DataPointSnapshot {

    /**
     * Returns the array of possible states for this state set.
     * The order of states in the array corresponds to the order of {@code boolean} values
     * returned by {@link #state(int)}.
     *
     * @return an array of possible states represented by enum
     */
    E[] states();

    /**
     * Returns whether the state at the specified index is active (true) or inactive (false).
     *
     * @param idx the index of the state to check
     * @return true if the state is active, false otherwise
     * @throws IndexOutOfBoundsException if the index is out of range of {@link #states()} length
     */
    boolean state(int idx);
}
