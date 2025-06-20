// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.spi;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * A convenient implementation of {@link ReadableSingletonStateBase}.
 *
 * @param <T> The type of the value
 */
public class ReadableSingletonStateBase<T> implements ReadableSingletonState<T> {
    private final String stateKey;
    private final Supplier<T> backingStoreAccessor;
    private boolean read = false;

    /**
     * Creates a new instance.
     *
     * @param stateKey The state key for this instance
     * @param backingStoreAccessor A {@link Supplier} that provides access to the value in the
     *     backing store.
     */
    public ReadableSingletonStateBase(@NonNull final String stateKey, @NonNull final Supplier<T> backingStoreAccessor) {
        this.stateKey = Objects.requireNonNull(stateKey);
        this.backingStoreAccessor = Objects.requireNonNull(backingStoreAccessor);
    }

    @Override
    @NonNull
    public final String getStateKey() {
        return stateKey;
    }

    @Override
    public T get() {
        this.read = true;
        return backingStoreAccessor.get();
    }

    @Override
    public boolean isRead() {
        return read;
    }

    /** Clears any cached data, including whether the instance has been read. */
    public void reset() {
        this.read = false;
    }
}
