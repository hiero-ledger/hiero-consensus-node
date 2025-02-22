// SPDX-License-Identifier: Apache-2.0
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
