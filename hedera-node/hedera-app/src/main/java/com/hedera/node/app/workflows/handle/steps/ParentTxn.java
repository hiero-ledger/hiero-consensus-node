// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.handle.steps;

import static com.hedera.node.app.workflows.handle.HandleWorkflow.initializeBuilderInfo;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.transaction.ExchangeRateSet;
import com.hedera.node.app.spi.workflows.record.StreamBuilder;
import com.hedera.node.app.store.ReadableStoreFactory;
import com.hedera.node.app.workflows.TransactionInfo;
import com.hedera.node.app.workflows.handle.record.TokenContextImpl;
import com.hedera.node.app.workflows.handle.stack.SavepointStackImpl;
import com.hedera.node.app.workflows.prehandle.PreHandleResult;
import com.swirlds.config.api.Configuration;
import com.swirlds.state.State;
import com.swirlds.state.lifecycle.info.NodeInfo;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.function.Consumer;

public record ParentTxn(
        @NonNull HederaFunctionality functionality,
        @NonNull Instant consensusNow,
        @NonNull State state,
        @NonNull TransactionInfo txnInfo,
        @NonNull TokenContextImpl tokenContextImpl,
        @NonNull SavepointStackImpl stack,
        @NonNull PreHandleResult preHandleResult,
        @NonNull ReadableStoreFactory readableStoreFactory,
        @NonNull Configuration config,
        @NonNull NodeInfo creatorInfo) {

    /**
     * Initializes and returns the base stream builder for this user transaction.
     * @param exchangeRates the exchange rates to use
     * @return the initialized stream builder
     */
    public StreamBuilder initBaseBuilder(@NonNull final ExchangeRateSet exchangeRates) {
        return initializeBuilderInfo(baseBuilder(), txnInfo, exchangeRates);
    }

    /**
     * Initializes and returns the base stream builder for this user transaction.
     *
     * @param exchangeRates the exchange rates to use
     * @param builderSpec the builder specification
     * @return the initialized stream builder
     */
    public <T extends StreamBuilder> T initBaseBuilder(
            @NonNull final ExchangeRateSet exchangeRates,
            @NonNull final Class<T> builderType,
            @NonNull final Consumer<T> builderSpec) {
        final var baseBuilder = builderType.cast(initializeBuilderInfo(baseBuilder(), txnInfo, exchangeRates));
        builderSpec.accept(baseBuilder);
        return baseBuilder;
    }

    /**
     * Returns the base stream builder for this user transaction.
     *
     * @return the base stream builder
     */
    public StreamBuilder baseBuilder() {
        return stack.getBaseBuilder(StreamBuilder.class);
    }
}
