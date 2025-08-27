// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.scope;

import static com.hedera.hapi.node.base.HederaFunctionality.CONTRACT_CALL;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.tuweniToPbjBytes;
import static com.hedera.node.app.spi.fees.NoopFeeCharging.NOOP_FEE_CHARGING;
import static com.hedera.node.app.spi.workflows.DispatchOptions.subDispatch;
import static com.hedera.node.app.spi.workflows.record.StreamBuilder.signedTxWith;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.contract.ContractCallTransactionBody;
import com.hedera.hapi.node.contract.ContractFunctionResult;
import com.hedera.hapi.node.contract.EvmTransactionResult;
import com.hedera.hapi.node.transaction.ExchangeRate;
import com.hedera.hapi.node.transaction.SignedTransaction;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.contract.impl.annotations.TransactionScope;
import com.hedera.node.app.service.contract.impl.records.ContractCallStreamBuilder;
import com.hedera.node.app.spi.workflows.DispatchOptions.PropagateFeeChargingStrategy;
import com.hedera.node.app.spi.workflows.DispatchOptions.StakingRewards;
import com.hedera.node.app.spi.workflows.DispatchOptions.UsePresetTxnId;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.record.StreamBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
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
@TransactionScope
public class HandleSystemContractOperations implements SystemContractOperations {
    private final HandleContext context;

    @Nullable
    private final Key maybeEthSenderKey;

    @Inject
    public HandleSystemContractOperations(@NonNull final HandleContext context, @Nullable Key maybeEthSenderKey) {
        this.context = requireNonNull(context);
        this.maybeEthSenderKey = maybeEthSenderKey;
    }

    @Override
    public @NonNull Predicate<Key> primitiveSignatureTestWith(@NonNull final VerificationStrategy strategy) {
        requireNonNull(strategy);
        return strategy.asPrimitiveSignatureTestIn(context, maybeEthSenderKey);
    }

    @NonNull
    @Override
    public Predicate<Key> signatureTestWith(@NonNull final VerificationStrategy strategy) {
        requireNonNull(strategy);
        return strategy.asSignatureTestIn(context, maybeEthSenderKey);
    }

    @Override
    public @NonNull <T extends StreamBuilder> T dispatch(
            @NonNull final TransactionBody syntheticBody,
            @NonNull final VerificationStrategy strategy,
            @NonNull final AccountID syntheticPayerId,
            @NonNull final Class<T> streamBuilderType,
            @NonNull final Set<Key> authorizingKeys,
            @NonNull final UsePresetTxnId usePresetTxnId) {
        requireNonNull(syntheticBody);
        requireNonNull(strategy);
        requireNonNull(syntheticPayerId);
        requireNonNull(streamBuilderType);
        requireNonNull(authorizingKeys);
        requireNonNull(usePresetTxnId);
        return context.dispatch(subDispatch(
                syntheticPayerId,
                syntheticBody,
                primitiveSignatureTestWith(strategy),
                authorizingKeys,
                streamBuilderType,
                StakingRewards.OFF,
                usePresetTxnId,
                // Currently the contract service "manually" pre-computes fees and takes them out of the EVM
                // transaction's remaining gas, so there is no more charging to do in the DispatchProcessor;
                // FUTURE - make the custom implementation here _directly_ deduct from remaining gas without
                // the manual precomputation upstream from here
                NOOP_FEE_CHARGING,
                PropagateFeeChargingStrategy.YES));
    }

    @Override
    public ContractCallStreamBuilder externalizePreemptedDispatch(
            @NonNull final TransactionBody syntheticBody,
            @NonNull final ResponseCodeEnum preemptingStatus,
            @NonNull final HederaFunctionality functionality) {
        requireNonNull(syntheticBody);
        requireNonNull(preemptingStatus);
        requireNonNull(functionality);

        return context.savepointStack()
                .addChildRecordBuilder(ContractCallStreamBuilder.class, functionality)
                .signedTx(signedTxWith(syntheticBody))
                .status(preemptingStatus);
    }

    @Override
    public void externalizeResult(
            @Deprecated @NonNull final ContractFunctionResult result,
            @NonNull final ResponseCodeEnum responseStatus,
            @NonNull final SignedTransaction signedTx,
            @NonNull final EvmTransactionResult txResult) {
        requireNonNull(signedTx);
        requireNonNull(result);
        requireNonNull(responseStatus);
        requireNonNull(txResult);
        context.savepointStack()
                .addChildRecordBuilder(ContractCallStreamBuilder.class, CONTRACT_CALL)
                .signedTx(signedTx)
                .status(responseStatus)
                .contractCallResult(result)
                .evmCallTransactionResult(txResult);
    }

    @Override
    public SignedTransaction syntheticSignedTxForNativeCall(
            @NonNull final Bytes input, @NonNull final ContractID contractID, boolean isViewCall) {
        requireNonNull(input);
        requireNonNull(contractID);
        var functionParameters = tuweniToPbjBytes(input);
        var contractCallBodyBuilder =
                ContractCallTransactionBody.newBuilder().contractID(contractID).functionParameters(functionParameters);
        if (isViewCall) {
            contractCallBodyBuilder.gas(1L);
        }
        var transactionBody = TransactionBody.newBuilder()
                .transactionID(TransactionID.DEFAULT)
                .contractCall(contractCallBodyBuilder.build())
                .build();
        return signedTxWith(transactionBody);
    }

    @Override
    @NonNull
    public ExchangeRate currentExchangeRate() {
        return context.exchangeRateInfo().activeRate(context.consensusNow());
    }

    @Override
    @Nullable
    public Key maybeEthSenderKey() {
        return maybeEthSenderKey;
    }
}
