// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.query;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.transaction.ExchangeRate;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.node.app.fees.ExchangeRateManager;
import com.hedera.node.app.records.impl.BlockRecordInfoImpl;
import com.hedera.node.app.spi.fees.ExchangeRateInfo;
import com.hedera.node.app.spi.fees.FeeCalculator;
import com.hedera.node.app.spi.records.BlockRecordInfo;
import com.hedera.node.app.spi.records.RecordCache;
import com.hedera.node.app.spi.workflows.QueryContext;
import com.hedera.node.app.store.ReadableStoreFactory;
import com.swirlds.config.api.Configuration;
import com.swirlds.state.State;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

import java.time.Instant;

/**
 * Simple implementation of {@link QueryContext}.
 */
public class QueryContextImpl implements QueryContext {
    private final Instant consensusTime;
    private final ReadableStoreFactory storeFactory;
    private final Query query;
    private final Configuration configuration;
    private final RecordCache recordCache;
    private final State state;
    private final ExchangeRateManager exchangeRateManager;
    private final AccountID payer;
    private final FeeCalculator feeCalculator;
    private BlockRecordInfo blockRecordInfo; // lazily created
    private ExchangeRateInfo exchangeRateInfo; // lazily created

    /**
     * Constructor of {@code QueryContextImpl}.
     *
     * @param state         the {@link State} with the current state
     * @param storeFactory  the {@link ReadableStoreFactory} used to create the stores
     * @param query         the query that is currently being processed
     * @param configuration the current {@link Configuration}
     * @param recordCache   the {@link RecordCache} used to cache records
     * @param exchangeRateManager the {@link ExchangeRateManager} used to get the current exchange rate
     * @param feeCalculator the {@link FeeCalculator} used to calculate fees
     * @param payer         the {@link AccountID} of the payer, if present
     * @throws NullPointerException if {@code query} is {@code null}
     */
    public QueryContextImpl(
            @NonNull final Instant consensusTime,
            @NonNull final State state,
            @NonNull final ReadableStoreFactory storeFactory,
            @NonNull final Query query,
            @NonNull final Configuration configuration,
            @NonNull final RecordCache recordCache,
            @NonNull final ExchangeRateManager exchangeRateManager,
            @NonNull final FeeCalculator feeCalculator,
            @Nullable final AccountID payer) {
        this.consensusTime = consensusTime;
        this.state = requireNonNull(state, "state must not be null");
        this.storeFactory = requireNonNull(storeFactory, "storeFactory must not be null");
        this.query = requireNonNull(query, "query must not be null");
        this.configuration = requireNonNull(configuration, "configuration must not be null");
        this.recordCache = requireNonNull(recordCache, "recordCache must not be null");
        this.exchangeRateManager = requireNonNull(exchangeRateManager, "exchangeRateManager must not be null");
        this.feeCalculator = requireNonNull(feeCalculator, "feeCalculator must not be null");
        this.payer = payer;
    }

    @Override
    @NonNull
    public Query query() {
        return query;
    }

    @Override
    public @Nullable AccountID payer() {
        return payer;
    }

    @Override
    @NonNull
    public <C> C createStore(@NonNull Class<C> storeInterface) {
        return storeFactory.getStore(storeInterface);
    }

    @NonNull
    @Override
    public Configuration configuration() {
        return configuration;
    }

    @NonNull
    @Override
    public RecordCache recordCache() {
        return recordCache;
    }

    @NonNull
    @Override
    public BlockRecordInfo blockRecordInfo() {
        if (blockRecordInfo == null) {
            blockRecordInfo = BlockRecordInfoImpl.from(state);
        }
        return blockRecordInfo;
    }

    @NonNull
    @Override
    public ExchangeRateInfo exchangeRateInfo() {
        if (exchangeRateInfo == null) {
            exchangeRateInfo = exchangeRateManager.exchangeRateInfo(state);
        }
        return exchangeRateInfo;
    }

    @NonNull
    @Override
    public FeeCalculator feeCalculator() {
        return feeCalculator;
    }

    @Override
    public ExchangeRate activeRate() {
        return exchangeRateManager.activeRate(consensusTime);
    }
}
