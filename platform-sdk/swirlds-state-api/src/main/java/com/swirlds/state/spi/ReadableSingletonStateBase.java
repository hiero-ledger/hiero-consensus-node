// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.spi;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * A convenient implementation of {@link ReadableSingletonStateBase}.
 *
 * @param <T> The type of the value
 */
public abstract class ReadableSingletonStateBase<T> implements ReadableSingletonState<T> {

    private boolean read = false;

    protected final String serviceName;

    protected final String stateKey;

    /**
     * Creates a new instance.
     *
     * @param serviceName The name of the service that owns the state.
     * @param stateKey The state key for this instance.
     */
    public ReadableSingletonStateBase(@NonNull final String serviceName, @NonNull final String stateKey) {
        this.serviceName = requireNonNull(serviceName);
        this.stateKey = requireNonNull(stateKey);
    }

    @Override
    @NonNull
    public final String getServiceName() {
        return serviceName;
    }

    @Override
    @NonNull
    public final String getStateKey() {
        return stateKey;
    }

    @Override
    public T get() {
        var value = readFromDataSource();
        this.read = true;
        return value;
    }

    /**
     * Reads the data from the underlying data source (which may be a merkle data structure, a
     * fast-copyable data structure, or something else).
     *
     * @return The value read from the underlying data source. May be null.
     */
    protected abstract T readFromDataSource();

    @Override
    public boolean isRead() {
        return read;
    }

    /** Clears any cached data, including whether the instance has been read. */
    public void reset() {
        this.read = false;
    }
}
