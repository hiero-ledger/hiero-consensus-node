// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.state;

import static java.util.Objects.requireNonNull;

import com.swirlds.state.State;
import com.swirlds.state.spi.ReadableStates;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.HashMap;
import java.util.Map;
import org.hiero.base.crypto.Hash;
import org.hiero.base.crypto.Hashable;

/**
 * A {@link State} that wraps another {@link State} and provides a {@link #commit()} method that
 * commits all modifications to the underlying state.
 */
public class WrappedState implements State, Hashable {

    private State delegate;
    private final Map<String, WrappedWritableStates> writableStatesMap = new HashMap<>();
    private final Map<String, ReadableStates> readableStatesMap = new HashMap<>();

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
     * Prepares this wrap for another sequential parent dispatch. If {@code newDelegate} is the same
     * object, cached {@link WrappedWritableStates} adapters are rebound to the current service
     * backends and reset in place. If the backing {@link State} changed, the adapter maps are
     * dropped.
     *
     * @param newDelegate the state to wrap
     */
    public void resetForDelegate(@NonNull final State newDelegate) {
        requireNonNull(newDelegate, "delegate must not be null");
        if (this.delegate != newDelegate) {
            this.delegate = newDelegate;
            writableStatesMap.clear();
            readableStatesMap.clear();
            return;
        }
        for (final var entry : writableStatesMap.entrySet()) {
            entry.getValue().retarget(this.delegate.getWritableStates(entry.getKey()));
        }
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
        final var cached = readableStatesMap.get(serviceName);
        if (cached != null) {
            return cached;
        }
        final var created = new ReadonlyStatesWrapper(getWritableStates(serviceName));
        readableStatesMap.put(serviceName, created);
        return created;
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
        final var cached = writableStatesMap.get(serviceName);
        if (cached != null) {
            return cached;
        }
        final var created = new WrappedWritableStates(delegate.getWritableStates(serviceName));
        writableStatesMap.put(serviceName, created);
        return created;
    }

    /**
     * Writes all modifications to the underlying {@link State}.
     */
    public void commit() {
        for (final var writableStates : writableStatesMap.values()) {
            writableStates.commit();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setHash(Hash hash) {
        delegate.setHash(hash);
    }
}
