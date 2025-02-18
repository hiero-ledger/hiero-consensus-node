/*
 * Copyright (C) 2023-2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hedera.node.app.state;

import static java.util.Objects.requireNonNull;

import com.swirlds.base.time.Time;
import com.swirlds.common.crypto.Hash;
import com.swirlds.common.crypto.Hashable;
import com.swirlds.common.merkle.crypto.MerkleCryptography;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.state.State;
import com.swirlds.state.spi.ReadableStates;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

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
