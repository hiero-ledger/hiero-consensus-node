// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.fees;

import static com.hedera.node.app.hapi.fees.calc.OverflowCheckingCalc.tinycentsToTinybars;
import static com.hedera.node.app.hapi.utils.CommonPbjConverters.fromPbj;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.transaction.ExchangeRate;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.spi.authorization.Authorizer;
import com.hedera.node.app.spi.store.ReadableStoreFactory;
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

    SimpleFeeCalculator getSimpleFeeCalculator();

    /**
     * Returns the readable store factory for accessing readable stores.
     *
     * @return the readable store factory
     */
    @NonNull
    ReadableStoreFactory readableStoreFactory();

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
     * This is typically the size of the signature map ({@code txInfo.signatureMap().sigPair().size()}).
     * <p>NOTE: this property should not be used for queries</p>
     * @return the number of signatures
     */
    int numTxnSignatures();

    /**
     * Returns the size of the full transaction in bytes.
     * This is the length of the serialized Transaction message (signedTransactionBytes),
     * which includes the transaction body, signatures, and all other transaction data.
     * This represents the actual bytes received and processed by the node.
     * <p>NOTE: this property should not be used for queries</p>
     * @return the full transaction size in bytes
     */
    int numTxnBytes();

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

    /**
     * Returns the gas price in tinycents.
     * @return the gas price in tinycents
     */
    long getGasPriceInTinycents();

    /**
     * Gets the number of tinybars equivalent to the given number of tinycents.
     *
     * @param amount the amount in tinycents
     * @return the amount in tinybars
     */
    default long tinybarsFromTinycents(final long amount) {
        return tinycentsToTinybars(amount, fromPbj(activeRate()));
    }
}
