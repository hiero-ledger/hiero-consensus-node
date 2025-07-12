// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.fees;

import static com.hedera.hapi.util.HapiUtils.functionOf;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.SubType;
import com.hedera.hapi.node.transaction.ExchangeRate;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.hapi.util.UnknownHederaFunctionality;
import com.hedera.node.app.signature.AppKeyVerifier;
import com.hedera.node.app.spi.authorization.Authorizer;
import com.hedera.node.app.spi.fees.FeeCalculator;
import com.hedera.node.app.spi.fees.FeeCalculatorFactory;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.store.ReadableStoreFactory;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;

/**
 * A {@link FeeContext} to use when computing the cost of a child transaction within
 * a given {@link com.hedera.node.app.spi.workflows.HandleContext}.
 */
public class ChildFeeContextImpl implements FeeContext {
    private final FeeManager feeManager;
    private final FeeContext context;
    private final TransactionBody body;
    private final AccountID payerId;
    private final boolean computeFeesAsInternalDispatch;
    private final Authorizer authorizer;
    private final ReadableStoreFactory storeFactory;
    private final Instant consensusNow;
    // The verifier is non-null only for batch inner transactions.
    // Since other synthetic child transactions have no signatures to verify, the verifier is no needed.
    @Nullable
    private final AppKeyVerifier verifier;

    private final int signatureMapSize;

    public ChildFeeContextImpl(
            @NonNull final FeeManager feeManager,
            @NonNull final FeeContext context,
            @NonNull final TransactionBody body,
            @NonNull final AccountID payerId,
            final boolean computeFeesAsInternalDispatch,
            @NonNull final Authorizer authorizer,
            @NonNull final ReadableStoreFactory storeFactory,
            @NonNull final Instant consensusNow,
            @Nullable final AppKeyVerifier verifier,
            final int signatureMapSize) {
        this.feeManager = requireNonNull(feeManager);
        this.context = requireNonNull(context);
        this.body = requireNonNull(body);
        this.payerId = requireNonNull(payerId);
        this.computeFeesAsInternalDispatch = computeFeesAsInternalDispatch;
        this.authorizer = requireNonNull(authorizer);
        this.storeFactory = requireNonNull(storeFactory);
        this.consensusNow = requireNonNull(consensusNow);
        this.verifier = verifier;
        this.signatureMapSize = signatureMapSize;
    }

    @Override
    public @NonNull AccountID payer() {
        return payerId;
    }

    @Override
    public @NonNull TransactionBody body() {
        return body;
    }

    private @NonNull FeeCalculator createFeeCalculator(@NonNull final SubType subType) {
        try {
            return feeManager.createFeeCalculator(
                    body,
                    Key.DEFAULT,
                    functionOf(body),
                    numTxnSignatures(),
                    signatureMapSize,
                    consensusNow,
                    subType,
                    computeFeesAsInternalDispatch,
                    storeFactory);
        } catch (UnknownHederaFunctionality e) {
            throw new IllegalStateException(
                    "Child fee context was constructed with invalid transaction body " + body, e);
        }
    }

    @NonNull
    @Override
    public FeeCalculatorFactory feeCalculatorFactory() {
        return this::createFeeCalculator;
    }

    @Override
    public <T> @NonNull T readableStore(@NonNull final Class<T> storeInterface) {
        return context.readableStore(storeInterface);
    }

    @Override
    public @NonNull Configuration configuration() {
        return context.configuration();
    }

    @Override
    public @Nullable Authorizer authorizer() {
        return authorizer;
    }

    @Override
    public int numTxnSignatures() {
        return verifier == null ? 0 : verifier.numSignaturesVerified();
    }

    @Override
    public Fees dispatchComputeFees(@NonNull final TransactionBody txBody, @NonNull final AccountID syntheticPayerId) {
        return context.dispatchComputeFees(txBody, syntheticPayerId);
    }

    @Override
    public ExchangeRate activeRate() {
        return null;
    }
}
