// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.spi;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A convenient base class for mutable singletons.
 *
 * @param <T> The type
 */
public class WritableSingletonStateBase<T> extends ReadableSingletonStateBase<T> implements WritableSingletonState<T> {
    /**
     * A sentinel value to represent null in the backing store.
     */
    private static final Object NULL_VALUE = new Object();

    private final Consumer<T> backingStoreMutator;
    private Object value;
    /**
     * Listeners to be notified when the singleton changes.
     */
    private final List<SingletonChangeListener<T>> listeners = new ArrayList<>();

    /**
     * Creates a new instance.
     *
     * @param stateKey The state key for this instance
     * @param backingStoreAccessor A {@link Supplier} that provides access to the value in the
     *     backing store.
     * @param backingStoreMutator A {@link Consumer} for mutating the value in the backing store.
     */
    public WritableSingletonStateBase(
            @NonNull final String stateKey,
            @NonNull final Supplier<T> backingStoreAccessor,
            @NonNull final Consumer<T> backingStoreMutator) {
        super(stateKey, backingStoreAccessor);
        this.backingStoreMutator = Objects.requireNonNull(backingStoreMutator);
    }

    /**
     * Register a listener to be notified of changes to the state on {@link #commit()}. We do not support unregistering
     * a listener, as the lifecycle of a {@link WritableSingletonState} is scoped to the set of mutations made to a
     * state in a round; and there is no case where an application would only want to be notified of a subset of those
     * changes.
     * @param listener the listener to register
     */
    public void registerListener(@NonNull final SingletonChangeListener<T> listener) {
        requireNonNull(listener);
        listeners.add(listener);
    }

    @Override
    public T get() {
        // Possible pattern: "put" and then "get". In this case, "read" should be false!! Otherwise,
        // we invalidate tx when we don't need to
        final var currentValue = currentValue();
        if (currentValue != null) {
            // C.f. https://github.com/hashgraph/hedera-services/issues/14582; in principle we should
            // also return null here if value is NULL_VALUE, but in production with the SingletonNode
            // backing store, null values are never actually set so this doesn't matter
            return currentValue;
        }
        return super.get();
    }

    @Override
    public void put(T value) {
        this.value = value == null ? NULL_VALUE : value;
    }

    @Override
    public boolean isModified() {
        return value != null;
    }

    /**
     * Flushes all changes into the underlying data store. This method should <strong>ONLY</strong>
     * be called by the code that created the {@link WritableSingletonStateBase} instance or owns
     * it. Don't cast and commit unless you own the instance!
     */
    public void commit() {
        if (value != null) {
            final var currentValue = currentValue();
            backingStoreMutator.accept(currentValue);
            if (currentValue != null) {
                listeners.forEach(l -> l.singletonUpdateChange(currentValue));
            }
        }
        reset();
    }

    @SuppressWarnings("unchecked")
    private T currentValue() {
        return value == NULL_VALUE ? null : (T) value;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Clears the "modified" and cached value, in addition to the super implementation
     */
    @Override
    public void reset() {
        this.value = null;
        super.reset();
    }
}
