// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.handle.stack;

import static java.util.Objects.requireNonNull;

import com.swirlds.state.spi.ReadableStates;
import com.swirlds.state.spi.WritableKVState;
import com.swirlds.state.spi.WritableQueueState;
import com.swirlds.state.spi.WritableSingletonState;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;

/**
 * A {@link WritableStates} implementation that delegates to the current {@link WritableStates} in a
 * {@link com.hedera.node.app.spi.workflows.HandleContext.SavepointStack}.
 *
 * <p>A {@link com.hedera.node.app.spi.workflows.HandleContext.SavepointStack} consists of a stack of frames, each of
 * which contains a set of modifications in regard to the underlying state. On the top of the stack is the most recent
 * state. This class delegates to the current {@link WritableStates} on top of such a stack.
 */
public class WritableStatesStack implements WritableStates {

    private final SavepointStackImpl stack;
    private final String statesName;

    /**
     * Constructs a {@link WritableStatesStack} that delegates to the current {@link WritableStates} in the given
     * {@link com.hedera.node.app.spi.workflows.HandleContext.SavepointStack}.
     *
     * @param stack the {@link SavepointStackImpl} that contains the stack of states
     * @param serviceName the name of the service that owns the state
     */
    public WritableStatesStack(@NonNull final SavepointStackImpl stack, @NonNull final String serviceName) {
        this.stack = requireNonNull(stack, "stack must not be null");
        this.statesName = requireNonNull(serviceName, "serviceName must not be null");
    }

    /**
     * Returns the current {@link WritableStates} in the stack. Package-private, because it should only be called by
     * {@link WritableKVStateStack} {@link WritableSingletonStateStack}, and {@link WritableQueueStateStack}.
     *
     * @return the current {@link ReadableStates} in the stack
     */
    @NonNull
    WritableStates getCurrent() {
        return stack.peek().state().getWritableStates(statesName);
    }

    /**
     * Returns the root {@link ReadableStates} of the stack. Package-private, because it should only be called by
     * {@link WritableKVStateStack}.
     *
     * @return the root {@link ReadableStates} of the stack
     */
    @NonNull
    ReadableStates getRoot() {
        return stack.rootStates(statesName);
    }

    @Override
    @NonNull
    public <K, V> WritableKVState<K, V> get(final int stateId) {
        return new WritableKVStateStack<>(this, stateId);
    }

    @Override
    @NonNull
    public <T> WritableSingletonState<T> getSingleton(final int stateId) {
        return new WritableSingletonStateStack<>(this, stateId);
    }

    @NonNull
    @Override
    public <E> WritableQueueState<E> getQueue(final int stateId) {
        return new WritableQueueStateStack<>(this, stateId);
    }

    @Override
    public boolean contains(final int stateId) {
        return getCurrent().contains(stateId);
    }

    @Override
    @NonNull
    public Set<Integer> stateIds() {
        return getCurrent().stateIds();
    }
}
