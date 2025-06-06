// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.handle.stack;

import static java.util.Objects.requireNonNull;

import com.swirlds.state.spi.WritableQueueState;
import com.swirlds.state.spi.WritableSingletonState;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Iterator;
import java.util.function.Predicate;

/**
 * An implementation of {@link WritableQueueState} that delegates to the current {@link WritableSingletonState} in a
 * {@link com.hedera.node.app.spi.workflows.HandleContext.SavepointStack}.
 *
 * <p>A {@link com.hedera.node.app.spi.workflows.HandleContext.SavepointStack} consists of a stack of frames, each of
 * which contains a set of modifications in regard to the state of the underlying frame. On the top of the stack is the
 * most recent state. This class delegates to the current {@link WritableQueueState} on top of such a stack.
 *
 * <p>All changes made to the {@link WritableQueueStateStack} are applied to the frame on top of the stack.
 * Consequently, all frames added later on top of the current frame will see the changes. If the frame is removed
 * however, the changes are lost.
 *
 * @param <E> The type of element held in the queue.
 */
public class WritableQueueStateStack<E> implements WritableQueueState<E> {

    private final WritableStatesStack writableStatesStack;
    private final String serviceName;
    private final String stateKey;

    /**
     * Constructs a {@link WritableQueueStateStack} that delegates to the current {@link WritableQueueState} in
     * the given {@link WritableStatesStack} for the given state key. A {@link WritableStatesStack} is an implementation
     * of {@link WritableStates} that delegates to the most recent version in a
     * {@link com.hedera.node.app.spi.workflows.HandleContext.SavepointStack}
     *
     * @param writableStatesStack the {@link WritableStatesStack}
     * @param serviceName the service name
     * @param stateKey the state key
     * @throws NullPointerException if any of the arguments is {@code null}
     */
    public WritableQueueStateStack(
            @NonNull final WritableStatesStack writableStatesStack,
            @NonNull final String serviceName,
            @NonNull final String stateKey) {
        this.writableStatesStack = requireNonNull(writableStatesStack, "writableStatesStack must not be null");
        this.serviceName = requireNonNull(serviceName, "serviceName must not be null");
        this.stateKey = requireNonNull(stateKey, "stateKey must not be null");
    }

    @NonNull
    private WritableQueueState<E> getCurrent() {
        return writableStatesStack.getCurrent().getQueue(stateKey);
    }

    @NonNull
    @Override
    public String getServiceName() {
        return serviceName;
    }

    @NonNull
    @Override
    public String getStateKey() {
        return stateKey;
    }

    @Nullable
    @Override
    public E peek() {
        return getCurrent().peek();
    }

    @NonNull
    @Override
    public Iterator<E> iterator() {
        return getCurrent().iterator();
    }

    @Override
    public void add(@NonNull E element) {
        getCurrent().add(element);
    }

    @Nullable
    @Override
    public E removeIf(@NonNull Predicate<E> predicate) {
        return getCurrent().removeIf(predicate);
    }
}
