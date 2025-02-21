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

import com.swirlds.state.spi.ReadableQueueState;
import com.swirlds.state.spi.WritableQueueState;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Iterator;

/**
 * A wrapper around a {@link WritableQueueState} that provides read-only access to a given
 * {@link WritableQueueState} delegate.
 *
 * @param <E> The type of the elements in the queue
 */
public class ReadonlyQueueStateWrapper<E> implements ReadableQueueState<E> {
    private final WritableQueueState<E> delegate;

    /**
     * Create a new wrapper around the given {@code delegate}.
     *
     * @param delegate the {@link WritableQueueState} to wrap
     */
    public ReadonlyQueueStateWrapper(@NonNull final WritableQueueState<E> delegate) {
        this.delegate = requireNonNull(delegate, "delegate must not be null");
    }

    @Override
    public String getServiceName() {
        return delegate.getServiceName();
    }

    @NonNull
    @Override
    public String getStateKey() {
        return delegate.getStateKey();
    }

    @Nullable
    @Override
    public E peek() {
        return delegate.peek();
    }

    @NonNull
    @Override
    public Iterator<E> iterator() {
        return delegate.iterator();
    }
}
