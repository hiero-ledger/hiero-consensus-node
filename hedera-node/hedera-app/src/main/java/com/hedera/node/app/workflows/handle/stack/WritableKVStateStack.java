// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.handle.stack;

import static java.util.Objects.requireNonNull;

import com.swirlds.state.spi.WritableKVState;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Iterator;
import java.util.Set;

/**
 * An implementation of {@link WritableKVState} that delegates to the current {@link WritableKVState} in a
 * {@link com.hedera.node.app.spi.workflows.HandleContext.SavepointStack}.
 *
 * <p>A {@link com.hedera.node.app.spi.workflows.HandleContext.SavepointStack} consists of a stack of frames, each of
 * which contains a set of modifications in regard to the state of the underlying frame. On the top of the stack is the
 * most recent state. This class delegates to the current {@link WritableKVState} on top of such a stack.
 *
 * <p>All changes made to the {@link WritableKVStateStack} are applied to the frame on top of the stack. Consequently,
 * all frames added later on top of the current frame will see the changes. If the frame is removed however, the
 * changes are lost.
 *
 * @param <K> the type of the keys
 * @param <V> the type of the values
 */
public class WritableKVStateStack<K, V> implements WritableKVState<K, V> {

    private final WritableStatesStack writableStatesStack;
    private final String serviceName;
    private final String stateKey;

    /**
     * Constructs a {@link WritableKVStateStack} that delegates to the current {@link WritableKVState} in
     * the given {@link WritableStatesStack} for the given state key. A {@link WritableStatesStack} is an implementation
     * of {@link com.swirlds.state.spi.WritableStates} that delegates to the most recent version in a
     * {@link com.hedera.node.app.spi.workflows.HandleContext.SavepointStack}
     *
     * @param writableStatesStack the {@link WritableStatesStack}
     * @param serviceName         the service name
     * @param stateKey            the state key
     * @throws NullPointerException if any of the arguments is {@code null}
     */
    public WritableKVStateStack(
            @NonNull final WritableStatesStack writableStatesStack,
            @NonNull final String serviceName,
            @NonNull final String stateKey) {
        this.writableStatesStack = requireNonNull(writableStatesStack, "writableStatesStack must not be null");
        this.serviceName = requireNonNull(serviceName, "serviceName must not be null");
        this.stateKey = requireNonNull(stateKey, "stateKey must not be null");
    }

    @NonNull
    private WritableKVState<K, V> getCurrent() {
        return writableStatesStack.getCurrent().get(stateKey);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public String getServiceName() {
        return serviceName;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public String getStateKey() {
        return stateKey;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Nullable
    public V get(@NonNull final K key) {
        return getCurrent().get(key);
    }

    /**
     * {@inheritDoc}
     */
    @SuppressWarnings("unchecked")
    @Nullable
    @Override
    public V getOriginalValue(@NonNull K key) {
        return (V) writableStatesStack.getRoot().get(stateKey).get(key);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void put(@NonNull final K key, @NonNull final V value) {
        getCurrent().put(key, value);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void remove(@NonNull final K key) {
        getCurrent().remove(key);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Iterator<K> keys() {
        return getCurrent().keys();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Set<K> modifiedKeys() {
        return getCurrent().modifiedKeys();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Set<K> readKeys() {
        return getCurrent().readKeys();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Deprecated
    public long size() {
        return getCurrent().size();
    }
}
