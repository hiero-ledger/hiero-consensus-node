// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.spi;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Collections;
import java.util.Set;

/** An implementation of {@link ReadableStates} that is always empty. */
public final class EmptyReadableStates implements ReadableStates {

    public static final ReadableStates INSTANCE = new EmptyReadableStates();

    @NonNull
    @Override
    public <K, V> ReadableKVState<K, V> get(final int stateId) {
        throw new IllegalArgumentException("There are no k/v states");
    }

    @NonNull
    @Override
    public <T> ReadableSingletonState<T> getSingleton(final int stateId) {
        throw new IllegalArgumentException("There are no singleton states");
    }

    @NonNull
    @Override
    public <E> ReadableQueueState<E> getQueue(final int stateId) {
        throw new IllegalArgumentException("There are no queue states");
    }

    @Override
    public boolean contains(final int stateId) {
        return false;
    }

    @NonNull
    @Override
    public Set<Integer> stateIds() {
        return Collections.emptySet();
    }
}
