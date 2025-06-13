// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.fees;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.SignatureMap;
import com.hedera.hapi.node.base.SubType;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.spi.authorization.Authorizer;
import com.hedera.node.app.spi.fees.FeeCalculator;
import com.hedera.node.app.spi.fees.FeeCalculatorFactory;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.store.ReadableStoreFactory;
import com.hedera.node.app.workflows.TransactionInfo;
import com.hedera.node.app.workflows.dispatcher.TransactionDispatcher;
import com.hedera.node.app.workflows.handle.DispatchHandleContext;
import com.hedera.node.config.data.FeesConfig;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;

/**
 * Simple implementation of {@link FeeContext} without any addition functionality.
 *
 * <p>This class is intended to be used during ingest. In the handle-workflow we use
 * {@link DispatchHandleContext}, which also implements{@link FeeContext}
 */
public class FeeContextImpl implements FeeContext {
    private final Instant consensusTime;
    private final TransactionInfo txInfo;
    private final Key payerKey;
    private final AccountID payerId;
    private final FeeManager feeManager;
    private final ReadableStoreFactory storeFactory;
    private final Configuration configuration;
    private final Authorizer authorizer;
    private final int numSignatures;
    private final TransactionDispatcher transactionDispatcher;

    /**
     * Constructor of {@code FeeContextImpl}
     *
     * @param consensusTime         the approximation of consensus time used during ingest
     * @param txInfo                the {@link TransactionInfo} of the transaction
     * @param payerKey              the {@link Key} of the payer
     * @param payerId               the {@link AccountID} of the payer
     * @param feeManager            the {@link FeeManager} to generate a {@link FeeCalculator}
     * @param storeFactory          the {@link ReadableStoreFactory} to create readable stores
     * @param numSignatures         the number of signatures in the transaction
     * @param transactionDispatcher the {@link TransactionDispatcher} to dispatch child transactions
     */
    public FeeContextImpl(
            @NonNull final Instant consensusTime,
            @NonNull final TransactionInfo txInfo,
            @NonNull final Key payerKey,
            @NonNull final AccountID payerId,
            @NonNull final FeeManager feeManager,
            @NonNull final ReadableStoreFactory storeFactory,
            @NonNull final Configuration configuration,
            @NonNull final Authorizer authorizer,
            final int numSignatures,
            final TransactionDispatcher transactionDispatcher) {
        this.consensusTime = consensusTime;
        this.txInfo = txInfo;
        this.payerKey = payerKey;
        this.payerId = payerId;
        this.feeManager = feeManager;
        this.storeFactory = storeFactory;
        this.configuration = configuration;
        this.authorizer = authorizer;
        this.numSignatures = numSignatures;
        this.transactionDispatcher = transactionDispatcher;
    }

    @Override
    public @NonNull AccountID payer() {
        return payerId;
    }

    @NonNull
    @Override
    public TransactionBody body() {
        return txInfo.txBody();
    }

    @NonNull
    private FeeCalculator createFeeCalculator(@NonNull SubType subType) {
        // For mono-service compatibility, we treat the sig map size as the number of verifications
        final var numVerifications = txInfo.signatureMap().sigPair().size();
        final var signatureMapSize = SignatureMap.PROTOBUF.measureRecord(txInfo.signatureMap());
        if(this.configuration().getConfigData(FeesConfig.class).simpleFeesCalculatorEnabled()) {
            if (txInfo.txBody().data().kind() == TransactionBody.DataOneOfType.CONSENSUS_CREATE_TOPIC) {
                final var rate = feeManager.exchangeRateManager.activeRate(consensusTime);
                return new SimpleFeesCalculatorImpl(txInfo.txBody(), payerKey, numVerifications, signatureMapSize, rate);
            }
        }
        return feeManager.createFeeCalculator(
                txInfo.txBody(),
                payerKey,
                txInfo.functionality(),
                numVerifications,
                signatureMapSize,
                consensusTime,
                subType,
                false,
                storeFactory);
    }

    @NonNull
    @Override
    public FeeCalculatorFactory feeCalculatorFactory() {
        return this::createFeeCalculator;
    }

    @NonNull
    @Override
    public <T> T readableStore(@NonNull Class<T> storeInterface) {
        return storeFactory.getStore(storeInterface);
    }

    @Override
    @NonNull
    public Configuration configuration() {
        return configuration;
    }

    @Override
    @NonNull
    public Authorizer authorizer() {
        return authorizer;
    }

    @Override
    public int numTxnSignatures() {
        return numSignatures;
    }

    @Override
    public Fees dispatchComputeFees(
            @NonNull final TransactionBody childTxBody, @NonNull final AccountID syntheticPayerId) {
        return transactionDispatcher.dispatchComputeFees(new ChildFeeContextImpl(
                feeManager,
                this,
                childTxBody,
                syntheticPayerId,
                true,
                authorizer,
                storeFactory,
                consensusTime,
                null, // This is only used when dispatching synthetic transactions,
                // so no signatures to verify
                numSignatures));
    }
}
