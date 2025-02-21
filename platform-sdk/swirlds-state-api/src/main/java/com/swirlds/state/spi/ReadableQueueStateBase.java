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
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Iterator;

/**
 * Base implementation of the {@link ReadableQueueState}. Caches the peeked element.
 *
 * @param <E> The type of the elements in this queue
 */
public abstract class ReadableQueueStateBase<E> implements ReadableQueueState<E> {

    private E peekedElement;

    protected final String serviceName;

    protected final String stateKey;

    /** Create a new instance */
    protected ReadableQueueStateBase(@NonNull final String serviceName, @NonNull final String stateKey) {
        this.serviceName = requireNonNull(serviceName);
        this.stateKey = requireNonNull(stateKey);
    }

    @Override
    @NonNull
    public final String getStateKey() {
        return stateKey;
    }

    @Override
    @NonNull
    public final String getServiceName() {
        return serviceName;
    }

    @Nullable
    @Override
    public E peek() {
        if (peekedElement == null) {
            peekedElement = peekOnDataSource();
        }
        return peekedElement;
    }

    @NonNull
    @Override
    public Iterator<E> iterator() {
        return iterateOnDataSource();
    }

    @Nullable
    protected abstract E peekOnDataSource();

    @NonNull
    protected abstract Iterator<E> iterateOnDataSource();
}
