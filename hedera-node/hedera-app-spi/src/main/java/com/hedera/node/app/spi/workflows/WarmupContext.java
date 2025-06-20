// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.workflows;

import com.hedera.hapi.node.transaction.TransactionBody;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Represents the context of a single {@code warm()}-call.
 */
public interface WarmupContext {

    /**
     * Gets the {@link TransactionBody}
     *
     * @return the {@link TransactionBody} in this context
     */
    @NonNull
    TransactionBody body();

    /**
     * Create a new store given the store's interface. This gives read-only access to the store.
     *
     * @param storeInterface The store interface to find and create a store for
     * @param <C> Interface class for a Store
     * @return An implementation of store interface provided, or null if the store
     * @throws IllegalArgumentException if the storeInterface class provided is unknown to the app
     * @throws NullPointerException if {@code storeInterface} is {@code null}
     */
    @NonNull
    <C> C createStore(@NonNull final Class<C> storeInterface);
}
