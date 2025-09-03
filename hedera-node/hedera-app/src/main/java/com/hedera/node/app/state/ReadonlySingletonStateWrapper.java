// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.state;

import static java.util.Objects.requireNonNull;

import com.swirlds.state.spi.ReadableSingletonState;
import com.swirlds.state.spi.WritableSingletonState;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * A wrapper around a {@link WritableSingletonState} that provides read-only access to a given
 * {@link WritableSingletonState} delegate.
 *
 * @param <T> The type of the state, such as an AddressBook or NetworkData.
 */
public class ReadonlySingletonStateWrapper<T> implements ReadableSingletonState<T> {

    private final WritableSingletonState<T> delegate;

    /**
     * Constructs a {@link ReadonlySingletonStateWrapper} that wraps the given {@link WritableSingletonState}.
     *
     * @param delegate the {@link WritableSingletonState} to wrap
     */
    public ReadonlySingletonStateWrapper(@NonNull final WritableSingletonState<T> delegate) {
        this.delegate = requireNonNull(delegate, "delegate must not be null");
    }

    @Override
    public int getStateId() {
        return delegate.getStateId();
    }

    @Nullable
    @Override
    public T get() {
        return delegate.get();
    }

    @Override
    public boolean isRead() {
        return delegate.isRead();
    }
}
