// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.test.fixtures.benchmark;

import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.common.utility.AutoCloseableWrapper;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Get an immutable state.
 */
public class ImmutableStateManager<S extends MerkleNode> implements StateManager<S> {

    private final ConcurrentLinkedDeque<S> states;

    public ImmutableStateManager() {
        states = new ConcurrentLinkedDeque<>();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public AutoCloseableWrapper<S> getState() {
        // FUTURE WORK this is not thread safe
        //  What if this state is deleted before we are finished with it?

        return new AutoCloseableWrapper<>(states.size() == 0 ? null : states.getLast(), () -> {});
    }

    /**
     * Get a queue of the current immutable states.
     */
    public ConcurrentLinkedDeque<S> getStates() {
        return states;
    }
}
