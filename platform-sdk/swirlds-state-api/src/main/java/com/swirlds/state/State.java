/*
 * Copyright (C) 2022-2025 Hedera Hashgraph, LLC
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

package com.swirlds.state;

import com.swirlds.base.time.Time;
import com.swirlds.common.FastCopyable;
import com.swirlds.common.crypto.Hash;
import com.swirlds.common.crypto.Hashable;
import com.swirlds.common.merkle.crypto.MerkleCryptography;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.state.spi.CommittableWritableStates;
import com.swirlds.state.spi.ReadableKVState;
import com.swirlds.state.spi.ReadableStates;
import com.swirlds.state.spi.WritableKVState;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.function.LongSupplier;

/**
 * The full state used of the app. The primary implementation is based on a merkle tree, and the data
 * structures provided by the hashgraph platform. But most of our code doesn't need to know that
 * detail, and are happy with just the API provided by this interface.
 */
public interface State extends FastCopyable, Hashable {
    /**
     * Initializes the state with the given parameters.
     * @param time The time provider.
     * @param metrics The metrics provider.
     * @param merkleCryptography The merkle cryptography provider.
     * @param roundSupplier The round supplier.
     */
    void init(Time time, Metrics metrics, MerkleCryptography merkleCryptography, LongSupplier roundSupplier);

    /**
     * Returns a {@link ReadableStates} for the given named service. If such a service doesn't
     * exist, an empty {@link ReadableStates} is returned.
     *
     * @param serviceName The name of the service.
     * @return A collection of {@link ReadableKVState} instances belonging to the service.
     */
    @NonNull
    ReadableStates getReadableStates(@NonNull String serviceName);

    /**
     * Returns a {@link WritableStates} for the given named service. If such a service doesn't
     * exist, an empty {@link WritableStates} is returned.
     *
     * @param serviceName The name of the service.
     * @return A collection of {@link WritableKVState} instance belonging to the service.
     */
    @NonNull
    WritableStates getWritableStates(@NonNull String serviceName);

    /**
     * Registers a listener to be notified on each commit if the {@link WritableStates} created by this {@link State}
     * are marked as {@link CommittableWritableStates}.
     *
     * @param listener The listener to be notified.
     * @throws UnsupportedOperationException if the state does not support listeners.
     */
    default void registerCommitListener(@NonNull final StateChangeListener listener) {
        throw new UnsupportedOperationException();
    }

    /**
     * Unregisters a listener from being notified on each commit if the {@link WritableStates} created by this {@link State}
     * are marked as {@link CommittableWritableStates}.
     * @param listener The listener to be unregistered.
     * @throws UnsupportedOperationException if the state does not support listeners.
     */
    default void unregisterCommitListener(@NonNull final StateChangeListener listener) {
        throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    default State copy() {
        throw new UnsupportedOperationException();
    }

    /**
     * Returns a calculated hash of the state.
     */
    @Nullable
    default Hash getHash() {
        throw new UnsupportedOperationException();
    }

    /**
     * Hashes the state on demand if it is not already hashed. If the state is already hashed, this method is a no-op.
     */
    default void computeHash() {
        throw new UnsupportedOperationException();
    }

    /**
     * Creates a snapshot for the state. The state has to be hashed and immutable before calling this method.
     * @param targetPath The path to save the snapshot.
     */
    default void createSnapshot(final @NonNull Path targetPath) {
        throw new UnsupportedOperationException();
    }

    /**
     * Loads a snapshot of a state.
     * @param targetPath The path to load the snapshot from.
     */
    default State loadSnapshot(final @NonNull Path targetPath) throws IOException {
        throw new UnsupportedOperationException();
    }
}
