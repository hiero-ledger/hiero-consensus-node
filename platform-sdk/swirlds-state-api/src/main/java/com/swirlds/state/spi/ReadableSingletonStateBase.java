// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.spi;

/**
 * A convenient implementation of {@link ReadableSingletonStateBase}.
 *
 * @param <T> The type of the value
 */
public abstract class ReadableSingletonStateBase<T> implements ReadableSingletonState<T> {

    private boolean read = false;

    protected final int stateId;

    /** State label used in logs, typically serviceName.stateKey */
    protected final String label;

    /**
     * Creates a new instance.
     *
     * @param stateId The state ID for this instance.
     * @param label The state label
     */
    public ReadableSingletonStateBase(final int stateId, final String label) {
        this.stateId = stateId;
        this.label = label;
    }

    @Override
    public final int getStateId() {
        return stateId;
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
