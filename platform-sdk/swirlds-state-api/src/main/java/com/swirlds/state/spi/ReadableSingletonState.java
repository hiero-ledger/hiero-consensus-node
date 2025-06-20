// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.spi;

import com.hedera.pbj.runtime.Schema;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * Provides stateful access to a singleton type. Most state in Hedera is k/v state, represented by
 * {@link ReadableKVState}. But some state is not based on a map, but is rather a single instance,
 * such as the AddressBook information. This type can be used to access that state.
 *
 * @param <T> The type of the state, such as an AddressBook or NetworkData.
 */
public interface ReadableSingletonState<T> {
    /**
     * Gets the "state key" that uniquely identifies this {@link ReadableKVState} within the {@link
     * Schema} which are scoped to the service implementation. The key is therefore not globally
     * unique, only unique within the service implementation itself.
     *
     * <p>The call is idempotent, always returning the same value. It must never return null.
     *
     * @return The state key. This will never be null, and will always be the same value for an
     *     instance of {@link ReadableKVState}.
     */
    @NonNull
    String getStateKey();

    /**
     * Gets the singleton value.
     *
     * @return The value, or null if there is no value.
     */
    @Nullable
    T get();

    /**
     * Gets whether the value of this {@link ReadableSingletonState} has been read.
     *
     * @return true if {@link #get()} has been called on this instance
     */
    boolean isRead();
}
