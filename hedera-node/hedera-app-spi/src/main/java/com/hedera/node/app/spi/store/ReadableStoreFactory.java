// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.store;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Factory for creating readable stores. This interface provides read-only access to stores.
 */
public interface ReadableStoreFactory {

    /**
     * Return a readable store given the store's interface. This gives read-only access to the store.
     *
     * @param storeInterface The store interface to find and create a store for
     * @param <T> Interface class for a Store
     * @return An implementation of the provided store interface
     * @throws IllegalArgumentException if the storeInterface class provided is unknown to the app
     * @throws NullPointerException if {@code storeInterface} is {@code null}
     */
    @NonNull
    <T> T readableStore(@NonNull Class<T> storeInterface);
}
