// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api.datapoint;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;

/**
 * A data point for holding {@code boolean} state of a set of values of enum type.
 * The state can be set to {@code true} or {@code false} for each value of enum.
 * <p>
 * <b>Setting and getting value for enum state is thread-safe and atomic,
 * but {@link #reset()} is not atomic while thread-safe</b>
 *
 * @param <E> the enum type of the states
 */
public interface StateSetDataPoint<E extends Enum<E>> extends DataPoint {

    /**
     * Set the state of the given value.
     *
     * @param value the value to set the state for
     * @param state the state to set
     */
    default void set(E value, boolean state) {
        if (state) {
            setTrue(value);
        } else {
            setFalse(value);
        }
    }

    /**
     * Set the state of the given value to {@code false}.
     *
     * @param value the value to set the state for
     */
    void setFalse(E value);

    /**
     * Set the state of the given value to {@code true}.
     *
     * @param value the value to set the state for
     */
    void setTrue(E value);

    /**
     * Get the state of the given value.
     *
     * @param value the value to get the state for
     * @return the state of the value, or <code>false</code> if the value is not present
     */
    boolean getState(E value);

    /**
     * Get the set of all values with a state.
     *
     * @return the set of all values with a state
     */
    @NonNull
    Set<E> getStates();
}
