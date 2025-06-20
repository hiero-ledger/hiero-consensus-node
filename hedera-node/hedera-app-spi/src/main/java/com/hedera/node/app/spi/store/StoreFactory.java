// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.store;

import com.hedera.hapi.node.transaction.TransactionBody;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Factory for creating stores and service APIs.
 */
public interface StoreFactory {

    /**
     * Get a readable store given the store's interface. This gives read-only access to the store.
     *
     * @param storeInterface The store interface to find and create a store for
     * @param <T> Interface class for a Store
     * @return An implementation of the provided store interface
     * @throws IllegalArgumentException if the storeInterface class provided is unknown to the app
     * @throws NullPointerException if {@code storeInterface} is {@code null}
     */
    @NonNull
    <T> T readableStore(@NonNull Class<T> storeInterface);

    /**
     * Return a writable store given the store's interface. This gives write access to the store.
     *
     * <p>This method is limited to stores that are part of the transaction's service.
     *
     * @param storeInterface The store interface to find and create a store for
     * @param <T> Interface class for a Store
     * @return An implementation of the provided store interface
     * @throws IllegalArgumentException if the storeInterface class provided is unknown to the app
     * @throws NullPointerException if {@code storeInterface} is {@code null}
     */
    @NonNull
    <T> T writableStore(@NonNull Class<T> storeInterface);

    /**
     * Return a service API given the API's interface. This permits use of another service
     * that doesn't have a corresponding HAPI {@link TransactionBody}.
     *
     * @param apiInterface The API interface to find and create an implementation of
     * @param <T> Interface class for an API
     * @return An implementation of the provided API interface
     * @throws IllegalArgumentException if the apiInterface class provided is unknown to the app
     * @throws NullPointerException if {@code apiInterface} is {@code null}
     */
    @NonNull
    <T> T serviceApi(@NonNull Class<T> apiInterface);
}
