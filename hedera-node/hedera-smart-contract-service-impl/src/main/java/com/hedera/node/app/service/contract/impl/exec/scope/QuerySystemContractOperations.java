// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.scope;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.Transaction;
import com.hedera.hapi.node.contract.ContractFunctionResult;
import com.hedera.hapi.node.contract.EvmTransactionResult;
import com.hedera.hapi.node.transaction.ExchangeRate;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.contract.impl.annotations.QueryScope;
import com.hedera.node.app.service.contract.impl.records.ContractCallStreamBuilder;
import com.hedera.node.app.spi.workflows.DispatchOptions.UsePresetTxnId;
import com.hedera.node.app.spi.workflows.QueryContext;
import com.hedera.node.app.spi.workflows.record.StreamBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.InstantSource;
import java.util.Set;
import java.util.function.Predicate;
import javax.inject.Inject;
import org.apache.tuweni.bytes.Bytes;

/**
 * Provides the "extended" scope a Hedera system contract needs to perform its operations.
 *
 * <p>This lets an EVM smart contract make atomic changes scoped to a message frame, even though
 * these changes involve state that it cannot mutate directly via the {@code ContractService}'s
 * {@code WritableStates}.
 */
@QueryScope
public class QuerySystemContractOperations implements SystemContractOperations {
    private final QueryContext context;
    private final InstantSource instantSource;

    @Inject
    public QuerySystemContractOperations(
            @NonNull final QueryContext queryContext, @NonNull final InstantSource instantSource) {
        this.context = requireNonNull(queryContext);
        this.instantSource = requireNonNull(instantSource);
    }

    @Override
    public @NonNull <T extends StreamBuilder> T dispatch(
            @NonNull final TransactionBody syntheticTransaction,
            @NonNull final VerificationStrategy strategy,
            @NonNull final AccountID syntheticPayerId,
            @NonNull final Class<T> streamBuilderType,
            @NonNull final Set<Key> authorizingKeys,
            @NonNull final UsePresetTxnId usePresetTxnId) {
        throw new UnsupportedOperationException("Cannot dispatch synthetic transaction");
    }

    @Override
    public ContractCallStreamBuilder externalizePreemptedDispatch(
            @NonNull final TransactionBody syntheticBody,
            @NonNull final ResponseCodeEnum preemptingStatus,
            @NonNull final HederaFunctionality functionality) {
        throw new UnsupportedOperationException("Cannot externalize preempted dispatch");
    }

    @Override
    public @NonNull Predicate<Key> primitiveSignatureTestWith(@NonNull final VerificationStrategy strategy) {
        throw new UnsupportedOperationException("Cannot compute a signature test");
    }

    @NonNull
    @Override
    public Predicate<Key> signatureTestWith(@NonNull final VerificationStrategy strategy) {
        throw new UnsupportedOperationException("Cannot compute a signature test");
    }

    @Override
    public void externalizeResult(
            @NonNull final ContractFunctionResult result,
            @NonNull final ResponseCodeEnum responseStatus,
            @Nullable final Transaction transaction,
            @NonNull final EvmTransactionResult txResult) {
        // No-op
    }

    @Override
    public Transaction syntheticTransactionForNativeCall(
            @NonNull final Bytes input, @NonNull final ContractID contractID, final boolean isViewCall) {
        // Ignored since externalizeResult() is a no-op
        return Transaction.DEFAULT;
    }

    @Override
    @NonNull
    public ExchangeRate currentExchangeRate() {
        return context.exchangeRateInfo().activeRate(instantSource.instant());
    }

    @Override
    @Nullable
    public Key maybeEthSenderKey() {
        return null;
    }
}
