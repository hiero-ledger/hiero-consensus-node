// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.scope;

import static java.util.Collections.emptySet;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.contract.ContractFunctionResult;
import com.hedera.hapi.node.contract.EvmTransactionResult;
import com.hedera.hapi.node.transaction.ExchangeRate;
import com.hedera.hapi.node.transaction.SignedTransaction;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.contract.impl.records.ContractCallStreamBuilder;
import com.hedera.node.app.spi.workflows.DispatchOptions.UsePresetTxnId;
import com.hedera.node.app.spi.workflows.record.StreamBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Set;
import java.util.function.Predicate;
import org.apache.tuweni.bytes.Bytes;

public interface SystemContractOperations {
    /**
     * Attempts to dispatch the given {@code syntheticTransaction} in the context of the current
     * {@link HandleHederaOperations}, performing signature verification with priority given to the included
     * {@code VerificationStrategy}.
     *
     * <p>If the result is {@code SUCCESS}, but this scope or any of its parents revert, the record
     * of this dispatch should have its stateful side effects cleared and its result set to {@code REVERTED_SUCCESS}.
     *
     * <p><b>Note:</b> As of release 0.57, all system contracts used this overload, hence forwarding an empty set of
     * authorizing keys with the dispatch. However, <a href="https://hips.hedera.com/hip/hip-755">HIP-755</a>
     * will soon make use the overload below so the HRC-755 system contracts can forward non-empty sets of authorizing
     * keys to the {@link HederaFunctionality#SCHEDULE_SIGN} handler.
     *
     * @param syntheticBody the synthetic transaction to dispatch
     * @param strategy             the non-cryptographic signature verification to use
     * @param syntheticPayerId     the payer of the synthetic transaction
     * @param streamBuilderType  the class of the stream builder to use
     * @return the result of the dispatch
     */
    @NonNull
    default <T extends StreamBuilder> T dispatch(
            @NonNull TransactionBody syntheticBody,
            @NonNull VerificationStrategy strategy,
            @NonNull AccountID syntheticPayerId,
            @NonNull Class<T> streamBuilderType) {
        return dispatch(syntheticBody, strategy, syntheticPayerId, streamBuilderType, emptySet(), UsePresetTxnId.NO);
    }

    /**
     * Attempts to dispatch the given {@code syntheticTransaction} in the context of the current
     * {@link HandleHederaOperations}, performing signature verification with priority given to the included
     * {@code VerificationStrategy}, and exposing the given {@code authorizingKeys} as the set of keys
     * authorizing the dispatch.
     *
     * <p>If the result is {@code SUCCESS}, but this scope or any of its parents revert, the record
     * of this dispatch should have its stateful side effects cleared and its result set to {@code REVERTED_SUCCESS}.
     *
     * @param syntheticBody the synthetic transaction to dispatch
     * @param strategy the non-cryptographic signature verification to use
     * @param syntheticPayerId the payer of the synthetic transaction
     * @param streamBuilderType the class of the stream builder to use
     * @param authorizingKeys the keys authorizing the dispatch
     * @param usePresetTxnId whether to set the expected transaction ID in the dispatch body
     * @return the result of the dispatch
     */
    @NonNull
    <T extends StreamBuilder> T dispatch(
            @NonNull TransactionBody syntheticBody,
            @NonNull VerificationStrategy strategy,
            @NonNull AccountID syntheticPayerId,
            @NonNull Class<T> streamBuilderType,
            @NonNull Set<Key> authorizingKeys,
            @NonNull UsePresetTxnId usePresetTxnId);

    /**
     * Externalizes the preemption of the given {@code syntheticTransaction} hat would have otherwise been
     * dispatched in the context of the current {@link HandleHederaOperations}.
     *
     * @param syntheticBody the preempted dispatch
     * @param preemptingStatus the status code causing the preemption
     * @param functionality the functionality of the preemption
     * @return the record of the preemption
     */
    ContractCallStreamBuilder externalizePreemptedDispatch(
            @NonNull TransactionBody syntheticBody,
            @NonNull ResponseCodeEnum preemptingStatus,
            @NonNull HederaFunctionality functionality);

    /**
     * Returns a {@link Predicate} that tests whether a primitive {@link Key} has signed
     * based on the given verification strategy. Used when dispatching a synthetic
     * transaction, as the workflow expects only a primitive signature test.
     *
     * @param strategy the verification strategy to use
     * @return a {@link Predicate} that tests whether a primitive {@link Key} is active
     */
    @NonNull
    Predicate<Key> primitiveSignatureTestWith(@NonNull VerificationStrategy strategy);

    /**
     * Returns a {@link Predicate} that tests whether a {@link Key} structure has an
     * active signature based on the given verification strategy. Used when checking
     * whether the workflow will judge an account's key to have signed a dispatch, and
     * hence whether a debit should be switched to an approval.
     *
     * @param strategy the verification strategy to use
     * @return a test whether a {@link Key} structure has an active signature
     */
    @NonNull
    Predicate<Key> signatureTestWith(@NonNull VerificationStrategy strategy);

    /**
     * Attempts to create a child record of the current record, with the given {@code result}.
     *
     * @param result contract function result
     * @param responseStatus response status
     * @param signedTx transaction
     * @param txResult the concise EVM transaction result
     */
    void externalizeResult(
            @Deprecated @NonNull ContractFunctionResult result,
            @NonNull ResponseCodeEnum responseStatus,
            @NonNull SignedTransaction signedTx,
            @NonNull EvmTransactionResult txResult);

    /**
     * Generate synthetic transaction for child hts call
     *
     * @param input the input data
     * @param contractID the contract id
     * @param isViewCall if the call is a view call
     * @return the synthetic transaction
     */
    SignedTransaction syntheticSignedTxForNativeCall(
            @NonNull Bytes input, @NonNull ContractID contractID, boolean isViewCall);

    /**
     * Returns the {@link ExchangeRate} for the current consensus time.  This will enable the translation from hbars
     * to dollars
     *
     * @return ExchangeRate for the current consensus time
     */
    @NonNull
    ExchangeRate currentExchangeRate();

    /**
     * Returns the ecdsa eth key for the sender of current transaction if one exists.
     */
    @Nullable
    Key maybeEthSenderKey();
}
