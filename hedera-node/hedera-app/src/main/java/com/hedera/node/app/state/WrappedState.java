// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.state;

import static java.util.Objects.requireNonNull;

import com.swirlds.base.time.Time;
import com.swirlds.common.merkle.crypto.MerkleCryptography;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.state.State;
import com.swirlds.state.spi.ReadableStates;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;
import org.hiero.base.crypto.Hash;
import org.hiero.base.crypto.Hashable;

/**
 * A {@link State} that wraps another {@link State} and provides a {@link #commit()} method that
 * commits all modifications to the underlying state.
 */
public class WrappedState implements State, Hashable {

    private final State delegate;
    private final Map<String, WrappedWritableStates> writableStatesMap = new HashMap<>();

    /**
     * Constructs a {@link WrappedState} that wraps the given {@link State}.
     *
     * @param delegate the {@link State} to wrap
     * @throws NullPointerException if {@code delegate} is {@code null}
     */
    public WrappedState(@NonNull final State delegate) {
        this.delegate = requireNonNull(delegate, "delegate must not be null");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void init(Time time, Metrics metrics, MerkleCryptography merkleCryptography, LongSupplier roundSupplier) {
        delegate.init(time, metrics, merkleCryptography, roundSupplier);
    }

    /**
     * Returns {@code true} if the state of this {@link WrappedState} has been modified.
     *
     * @return {@code true}, if the state has been modified; otherwise {@code false}
     */
    public boolean isModified() {
        for (final var writableStates : writableStatesMap.values()) {
            if (writableStates.isModified()) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@inheritDoc}
     *
     * The {@link ReadableStates} instances returned from this method are based on the {@link WritableStates} instances
     * for the same service name. This means that any modifications to the {@link WritableStates} will be reflected
     * in the {@link ReadableStates} instances returned from this method.
     * <p>
     * Unlike other {@link State} implementations, the returned {@link ReadableStates} of this implementation
     * must only be used in the handle workflow.
     */
    @Override
    @NonNull
    public ReadableStates getReadableStates(@NonNull String serviceName) {
        return new ReadonlyStatesWrapper(getWritableStates(serviceName));
    }

    /**
     * {@inheritDoc}
     *
     * This method guarantees that the same {@link WritableStates} instance is returned for the same {@code serviceName}
     * to ensure all modifications to a {@link WritableStates} are kept together.
     */
    @Override
    @NonNull
    public WritableStates getWritableStates(@NonNull String serviceName) {
        return writableStatesMap.computeIfAbsent(
                serviceName, s -> new WrappedWritableStates(delegate.getWritableStates(s)));
    }

    /**
     * Writes all modifications to the underlying {@link State}.
     */
    public void commit() {
        for (final var writableStates : writableStatesMap.values()) {
            writableStates.commit(delegate.isStartUpMode());
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setHash(Hash hash) {
        delegate.setHash(hash);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isStartUpMode() {
        return delegate.isStartUpMode();
    }
}
