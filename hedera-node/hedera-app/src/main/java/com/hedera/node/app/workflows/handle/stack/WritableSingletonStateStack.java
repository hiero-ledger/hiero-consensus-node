// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.handle.stack;

import static java.util.Objects.requireNonNull;

import com.swirlds.state.spi.WritableSingletonState;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * An implementation of {@link WritableSingletonState} that delegates to the current {@link WritableSingletonState} in a
 * {@link com.hedera.node.app.spi.workflows.HandleContext.SavepointStack}.
 *
 * <p>A {@link com.hedera.node.app.spi.workflows.HandleContext.SavepointStack} consists of a stack of frames, each of
 * which contains a set of modifications in regard to the state of the underlying frame. On the top of the stack is the
 * most recent state. This class delegates to the current {@link WritableSingletonState} on top of such a stack.
 *
 * <p>All changes made to the {@link WritableSingletonStateStack} are applied to the frame on top of the stack.
 * Consequently, all frames added later on top of the current frame will see the changes. If the frame is removed
 * however, the changes are lost.
 *
 * @param <T> the type of the singleton state
 */
public class WritableSingletonStateStack<T> implements WritableSingletonState<T> {

    private final WritableStatesStack writableStatesStack;
    private final int stateId;

    /**
     * Constructs a {@link WritableSingletonStateStack} that delegates to the current {@link WritableSingletonState} in
     * the given {@link WritableStatesStack} for the given state key. A {@link WritableStatesStack} is an implementation
     * of {@link WritableStates} that delegates to the most recent version in a
     * {@link com.hedera.node.app.spi.workflows.HandleContext.SavepointStack}
     *
     * @param writableStatesStack the {@link WritableStatesStack}
     * @param stateId the state ID
     * @throws NullPointerException if any of the arguments is {@code null}
     */
    public WritableSingletonStateStack(@NonNull final WritableStatesStack writableStatesStack, final int stateId) {
        this.writableStatesStack = requireNonNull(writableStatesStack, "writableStatesStack must not be null");
        this.stateId = stateId;
    }

    @NonNull
    private WritableSingletonState<T> getCurrent() {
        return writableStatesStack.getCurrent().getSingleton(stateId);
    }

    @Override
    public int getStateId() {
        return stateId;
    }

    @Override
    @Nullable
    public T get() {
        return getCurrent().get();
    }

    @Override
    public boolean isRead() {
        return getCurrent().isRead();
    }

    @Override
    public void put(@Nullable final T value) {
        getCurrent().put(value);
    }

    @Override
    public boolean isModified() {
        return getCurrent().isModified();
    }
}
