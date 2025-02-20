// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal.merkle;

import com.swirlds.virtualmap.internal.VirtualStateAccessor;
import java.util.Objects;

/**
 * Basic implementation of {@link VirtualStateAccessor} which delegates to a {@link VirtualMapState}.
 * Initially this separation of interface and implementation class was essential to the design.
 * At this point, it is just useful for testing purposes.
 */
public final class VirtualStateAccessorImpl implements VirtualStateAccessor {
    /**
     * The state. Cannot be null.
     */
    private final VirtualMapState state;

    /**
     * Create a new {@link VirtualStateAccessorImpl}.
     *
     * @param state
     * 		The state. Cannot be null.
     */
    public VirtualStateAccessorImpl(VirtualMapState state) {
        this.state = Objects.requireNonNull(state);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getLabel() {
        return state.getLabel();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getFirstLeafPath() {
        return state.getFirstLeafPath();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getLastLeafPath() {
        return state.getLastLeafPath();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setFirstLeafPath(final long path) {
        state.setFirstLeafPath(path);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setLastLeafPath(final long path) {
        state.setLastLeafPath(path);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long size() {
        return state.getSize();
    }
}
