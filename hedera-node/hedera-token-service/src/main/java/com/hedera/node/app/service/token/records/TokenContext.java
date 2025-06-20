// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.records;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.node.app.service.token.TokenService;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.record.StreamBuilder;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.Set;

/**
 * Interface that contains all information needed for {@link TokenService} responsibilities.
 */
public interface TokenContext {
    /**
     * Returns the current consensus time.
     *
     * @return the current consensus time
     */
    @NonNull
    Instant consensusTime();

    /**
     * Returns the current {@link Configuration}.
     *
     * @return the {@code Configuration}
     */
    @NonNull
    Configuration configuration();

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
     * Adds a preceding child record builder to the list of record builders. If the current {@link HandleContext} (or
     * any parent context) is rolled back, all child record builders will be reverted.
     *
     * @param <T> the record type
     * @param recordBuilderClass the record type
     * @param functionality the functionality of the record
     * @return the new child record builder
     * @throws NullPointerException if {@code recordBuilderClass} is {@code null}
     * @throws IllegalArgumentException if the record builder type is unknown to the app
     */
    @NonNull
    <T extends StreamBuilder> T addPrecedingChildRecordBuilder(
            @NonNull Class<T> recordBuilderClass, @NonNull HederaFunctionality functionality);

    /**
     * Returns the set of all known node ids, including ids that may no longer be active.
     *
     * @return the set of all known node ids
     */
    Set<Long> knownNodeIds();
}
