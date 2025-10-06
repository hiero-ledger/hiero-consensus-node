// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.spi;

import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * Provides stateful access to a singleton type. Most state in Hedera is k/v state, represented by
 * {@link ReadableKVState}. But some state is not based on a map, but is rather a single instance,
 * such as the AddressBook information. This type can be used to access that state.
 *
 * @param <T> The type of the state, such as an AddressBook or NetworkData.
 */
public interface ReadableSingletonState<T> extends ReadableState {

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
