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

package com.swirlds.state.spi;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;

/** An implementation of {@link WritableStates} that is always empty. */
public class EmptyWritableStates implements WritableStates {
    public static final WritableStates INSTANCE = new EmptyWritableStates();

    @NonNull
    @Override
    public final <K, V> WritableKVState<K, V> get(@NonNull final String stateKey) {
        Objects.requireNonNull(stateKey);
        throw new IllegalArgumentException("There are no k/v states");
    }

    @NonNull
    @Override
    public final <T> WritableSingletonState<T> getSingleton(@NonNull final String stateKey) {
        Objects.requireNonNull(stateKey);
        throw new IllegalArgumentException("There are no singleton states");
    }

    @NonNull
    @Override
    public final <E> WritableQueueState<E> getQueue(@NonNull final String stateKey) {
        Objects.requireNonNull(stateKey);
        throw new IllegalArgumentException("There are no queue states");
    }

    @Override
    public final boolean contains(@NonNull final String stateKey) {
        return false;
    }

    @NonNull
    @Override
    public final Set<String> stateKeys() {
        return Collections.emptySet();
    }
}
