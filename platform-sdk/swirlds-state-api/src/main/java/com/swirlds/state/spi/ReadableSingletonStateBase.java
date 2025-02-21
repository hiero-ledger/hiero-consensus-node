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
