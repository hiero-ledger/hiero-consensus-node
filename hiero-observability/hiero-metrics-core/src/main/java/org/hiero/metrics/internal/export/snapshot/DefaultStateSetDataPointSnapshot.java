// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal.export.snapshot;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.metrics.api.export.snapshot.StateSetDataPointSnapshot;
import org.hiero.metrics.internal.core.LabelValues;

/**
 * A default implementation of {@link StateSetDataPointSnapshot} that holds a set of states represented as boolean values.
 *
 * @param <E> the enum type representing the possible states
 */
public final class DefaultStateSetDataPointSnapshot<E extends Enum<E>> extends BaseDataPointSnapshot
        implements StateSetDataPointSnapshot<E> {

    private final E[] enumConstants;
    private final boolean[] states;

    public DefaultStateSetDataPointSnapshot(@NonNull LabelValues dynamicLabelValues, @NonNull E[] enumConstants) {
        super(dynamicLabelValues);
        this.enumConstants = enumConstants;
        this.states = new boolean[enumConstants.length];
    }

    /**
     * Updates the state at the specified index in the range of the states array {@link #states()}.
     *
     * @param idx   the index of the state to update
     * @param value the new value for the state (true for active, false for inactive)
     * @throws IndexOutOfBoundsException if the index is out of range of {@link #states()} length
     */
    public void updateState(int idx, boolean value) {
        this.states[idx] = value;
    }

    @Override
    public E[] states() {
        return enumConstants;
    }

    @Override
    public boolean state(int idx) {
        return states[idx];
    }
}
