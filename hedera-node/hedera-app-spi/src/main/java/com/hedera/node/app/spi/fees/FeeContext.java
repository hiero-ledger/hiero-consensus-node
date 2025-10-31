// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.fees;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.transaction.ExchangeRate;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.spi.authorization.Authorizer;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

public interface FeeContext {
    /**
     * Gets the payer {@link AccountID} whose expiration time will be "inherited"
     * by account-scoped properties like allowances.
     *
     * @return the {@link AccountID} of the payer in this context
     */
    @NonNull
    AccountID payer();

    /**
     * Returns the {@link TransactionBody}
     *
     * @return the {@code TransactionBody}
     */
    @NonNull
    TransactionBody body();

    /**
     * Returns the {@link FeeCalculatorFactory} which can be used to create {@link FeeCalculator} for a specific
     * {@link com.hedera.hapi.node.base.SubType}
     *
     * @return the {@code FeeCalculatorFactory}
     */
    @NonNull
    FeeCalculatorFactory feeCalculatorFactory();

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
     * Returns the current {@link Configuration} for the node.
     *
     * @return the {@code Configuration}
     */
    @NonNull
    Configuration configuration();

    /**
     * @return the {@code Authorizer}
     */
    @Nullable
    Authorizer authorizer();

    /**
     * Returns the number of signatures provided for the transaction.
     * <p>NOTE: this property should not be used for queries</p>
     * @return the number of signatures
     */
    int numTxnSignatures();

    /**
     * Dispatches the computation of fees for the given transaction body and synthetic payer ID.
     * @param txBody the transaction body
     * @param syntheticPayerId the synthetic payer ID
     * @return the computed fees
     */
    Fees dispatchComputeFees(@NonNull TransactionBody txBody, @NonNull AccountID syntheticPayerId);

    /**
     * Returns the active Exchange Rate.
     * @return the active exchange rate
     */
    ExchangeRate activeRate();
}
