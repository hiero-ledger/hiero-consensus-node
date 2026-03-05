// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.fees;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.transaction.ExchangeRate;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.spi.authorization.Authorizer;
import com.hedera.node.app.spi.store.ReadableStoreFactory;
import com.hedera.node.app.spi.workflows.QueryContext;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * A lightweight fee context used by simple fee calculators. It may wrap either a {@link FeeContext}
 * (for transactions) or a {@link QueryContext} (for queries).
 *
 * <p>If no {@link FeeContext} is available, any {@link FeeContext} method called on this interface
 * will throw {@link UnsupportedOperationException}.
 */
public interface SimpleFeeContext extends FeeContext {
    /**
     * Returns the number of signatures in the transaction.
     *
     * @return the number of signatures in the transaction
     */
    int numTxnSignatures();

    /**
     * Returns the number of bytes in the transaction.
     *
     * @return the number of bytes in the transaction
     */
    int numTxnBytes();

    /**
     * Returns the wrapped {@link FeeContext} or null if none is available while handling a query.
     *
     * @return the wrapped fee context, or null if none is available
     */
    @Nullable
    FeeContext feeContext();

    /**
     * Returns the wrapped {@link QueryContext} or null if none is available while handling a transaction.
     *
     * @return the wrapped query context, or null if none is available
     */
    @Nullable
    QueryContext queryContext();

    /**
     * Returns the wrapped {@link FeeContext} or throws if none is available.
     *
     * @return the wrapped fee context
     * @throws UnsupportedOperationException if no fee context is available
     */
    default @NonNull FeeContext requireFeeContext() {
        final var context = feeContext();
        if (context == null) {
            throw new UnsupportedOperationException("FeeContext not available for this SimpleFeeContext");
        }
        return context;
    }

    @Override
    default @NonNull AccountID payer() {
        return requireFeeContext().payer();
    }

    @Override
    default @NonNull TransactionBody body() {
        return requireFeeContext().body();
    }

    @Override
    default @NonNull FeeCalculatorFactory feeCalculatorFactory() {
        return requireFeeContext().feeCalculatorFactory();
    }

    @Override
    default SimpleFeeCalculator getSimpleFeeCalculator() {
        return requireFeeContext().getSimpleFeeCalculator();
    }

    @Override
    default @NonNull ReadableStoreFactory readableStoreFactory() {
        return requireFeeContext().readableStoreFactory();
    }

    @Override
    default @NonNull <T> T readableStore(@NonNull final Class<T> storeInterface) {
        return requireFeeContext().readableStore(storeInterface);
    }

    @Override
    default @NonNull Configuration configuration() {
        return requireFeeContext().configuration();
    }

    @Override
    default @Nullable Authorizer authorizer() {
        return requireFeeContext().authorizer();
    }

    @Override
    default @NonNull Fees dispatchComputeFees(
            @NonNull final TransactionBody txBody, @NonNull final AccountID syntheticPayerId) {
        return requireFeeContext().dispatchComputeFees(txBody, syntheticPayerId);
    }

    @Override
    default ExchangeRate activeRate() {
        return requireFeeContext().activeRate();
    }

    @Override
    default long getGasPriceInTinycents() {
        return requireFeeContext().getGasPriceInTinycents();
    }
}
