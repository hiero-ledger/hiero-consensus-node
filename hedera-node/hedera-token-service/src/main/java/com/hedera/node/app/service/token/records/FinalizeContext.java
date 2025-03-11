// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.records;

import com.hedera.node.app.spi.workflows.record.StreamBuilder;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.function.Consumer;

/**
 * Represents the context of used for finalizing a user transaction.
 */
@SuppressWarnings("UnusedReturnValue")
public interface FinalizeContext {
    /**
     * Returns the current consensus time.
     *
     * @return the current consensus time
     */
    @NonNull
    Instant consensusTime();

    /**
     * Returns the current {@link Configuration} for the node.
     *
     * @return the {@code Configuration}
     */
    @NonNull
    Configuration configuration();

    /**
     * Indicates whether the transaction has any child or preceding records.
     * This is true only for the user transaction that triggered the dispatch.
     *
     * @return {@code true} if the transaction has child ore preceding records; otherwise {@code false}
     */
    boolean hasChildOrPrecedingRecords();

    /**
     * This method can be used to iterate over all child records.
     *
     * @param recordBuilderClass the record type
     * @param consumer the consumer to be called for each record
     * @param <T> the record type
     * @throws NullPointerException if any parameter is {@code null}
     * @throws IllegalArgumentException if the record builder type is unknown to the app
     */
    <T> void forEachChildRecord(@NonNull Class<T> recordBuilderClass, @NonNull Consumer<T> consumer);

    /**
     * Whether this context represents a {@code SCHEDULED} dispatch, which should defer
     * updating stake metadata for modified accounts until the triggering {@code SCHEDULE_SIGN}
     * or {@code SCHEDULE_CREATE} transaction is finalized.
     *
     * @return {@code true} if this context represents a scheduled dispatch; otherwise {@code false}
     */
    boolean isScheduleDispatch();

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
     * Returns a record builder for the given record builder subtype.
     *
     * @param recordBuilderClass the record type
     * @param <T> the record type
     * @return a builder for the given record type
     * @throws NullPointerException if {@code recordBuilderClass} is {@code null}
     * @throws IllegalArgumentException if the record builder type is unknown to the app
     */
    @NonNull
    <T extends StreamBuilder> T userTransactionRecordBuilder(@NonNull Class<T> recordBuilderClass);
}
