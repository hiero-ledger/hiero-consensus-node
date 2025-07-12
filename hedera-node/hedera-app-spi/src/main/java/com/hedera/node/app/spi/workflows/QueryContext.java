// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.workflows;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.transaction.ExchangeRate;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.node.app.spi.fees.ExchangeRateInfo;
import com.hedera.node.app.spi.fees.FeeCalculator;
import com.hedera.node.app.spi.records.BlockRecordInfo;
import com.hedera.node.app.spi.records.RecordCache;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * Context of a single query. Contains all query specific information.
 */
public interface QueryContext {

    /**
     * Returns the {@link Query} that is currently being processed.
     *
     * @return the {@link Query} that is currently being processed
     */
    @NonNull
    Query query();

    /**
     * Gets the payer {@link AccountID} for this context's query; or null if the query is free.
     *
     * @return the {@link AccountID} of the payer in this context, if there is one
     */
    @Nullable
    AccountID payer();

    /**
     * Create a new store given the store's interface. This gives read-only access to the store.
     *
     * @param storeInterface The store interface to find and create a store for
     * @param <C>            Interface class for a Store
     * @return An implementation of store interface provided, or null if the store
     * @throws IllegalArgumentException if the storeInterface class provided is unknown to the app
     * @throws NullPointerException     if {@code clazz} is {@code null}
     */
    @NonNull
    <C> C createStore(@NonNull Class<C> storeInterface);

    /**
     * Returns the current {@link Configuration}.
     *
     * @return the {@link Configuration}
     */
    @NonNull
    Configuration configuration();

    /** Gets the {@link RecordCache}. */
    @NonNull
    RecordCache recordCache();

    /** Gets the {@link BlockRecordInfo}. */
    @NonNull
    BlockRecordInfo blockRecordInfo();

    /**
     * Returns information on current exchange rates
     */
    @NonNull
    ExchangeRateInfo exchangeRateInfo();

    /**
     * Get a calculator for calculating fees for the current query
     *
     * @return The {@link FeeCalculator} to use.
     */
    @NonNull
    FeeCalculator feeCalculator();

    ExchangeRate activeRate();

}
