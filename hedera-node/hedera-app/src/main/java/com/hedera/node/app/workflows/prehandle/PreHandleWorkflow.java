// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.prehandle;

import static com.hedera.node.app.workflows.prehandle.PreHandleWorkflowImpl.isAtomicBatch;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.store.ReadableStoreFactory;
import com.hedera.node.app.util.ProtobufUtils;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.platform.system.events.Event;
import com.swirlds.platform.system.transaction.ConsensusTransaction;
import com.swirlds.platform.system.transaction.Transaction;
import com.swirlds.state.lifecycle.info.NodeInfo;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * A workflow to pre-handle transactions.
 */
public interface PreHandleWorkflow {
    Logger log = LogManager.getLogger(PreHandleWorkflow.class);

    /**
     * Starts the pre-handle transaction workflow of the {@link Event}
     *
     * @param readableStoreFactory the {@link ReadableStoreFactory} that is used for looking up stores
     * @param creatorInfo The {@link AccountID} of the node that created these transactions
     * @param transactions An {@link Stream} over all transactions to pre-handle
     * @param stateSignatureTxnCallback A callback to be called when encountering a {@link StateSignatureTransaction}
     * @throws NullPointerException if one of the arguments is {@code null}
     */
    void preHandle(
            @NonNull final ReadableStoreFactory readableStoreFactory,
            @NonNull final NodeInfo creatorInfo,
            @NonNull final Stream<Transaction> transactions,
            @NonNull final Consumer<StateSignatureTransaction> stateSignatureTxnCallback);

    /**
     * Starts the pre-handle transaction workflow for a single transaction.
     *
     * <p>If this method is called directly, pre-handle is done on the current thread.
     *
     * @param creatorInfo The {@link AccountID} of the node that created these transactions
     * @param storeFactory The {@link ReadableStoreFactory} based on the current state
     * @param accountStore The {@link ReadableAccountStore} based on the current state
     * @param applicationTxBytes The {@link Transaction} to pre-handle
     * @param maybeReusableResult The result of a previous call to the same method that may,
     * @param stateSignatureTxnCallback A callback to be called when encountering a {@link StateSignatureTransaction}
     * depending on changes in state, be reusable for this call
     * @param innerTransaction Whether the transaction is an inner transaction
     * @return The {@link PreHandleResult} of running pre-handle
     */
    @NonNull
    PreHandleResult preHandleTransaction(
            @NonNull NodeInfo creatorInfo,
            @NonNull ReadableStoreFactory storeFactory,
            @NonNull ReadableAccountStore accountStore,
            @NonNull Bytes applicationTxBytes,
            @Nullable PreHandleResult maybeReusableResult,
            @NonNull Consumer<StateSignatureTransaction> stateSignatureTxnCallback,
            @NonNull InnerTransaction innerTransaction);

    /**
     * Starts the pre-handle transaction workflow for all transactions including inner transactions in an atomic batch.
     *
     * @param creatorInfo the node that created the transaction
     * @param storeFactory the store factory
     * @param accountStore the account store
     * @param applicationTxBytes the transaction to be verified
     * @param maybeReusableResult the previous result of pre-handle
     * @param stateSignatureTxnCallback the callback to be called when encountering a {@link StateSignatureTransaction}
     * @return the verification data for the transaction
     */
    default PreHandleResult preHandleAllTransactions(
            @NonNull NodeInfo creatorInfo,
            @NonNull ReadableStoreFactory storeFactory,
            @NonNull ReadableAccountStore accountStore,
            @NonNull Bytes applicationTxBytes,
            @Nullable PreHandleResult maybeReusableResult,
            @NonNull Consumer<StateSignatureTransaction> stateSignatureTxnCallback) {
        final var result = preHandleTransaction(
                creatorInfo,
                storeFactory,
                accountStore,
                applicationTxBytes,
                maybeReusableResult,
                stateSignatureTxnCallback,
                InnerTransaction.NO);
        // If the transaction is an atomic batch, we need to pre-handle all inner transactions as well
        // and add their results to the outer transaction's pre-handle result
        if (result.txInfo() != null
                && isAtomicBatch(result.txInfo())
                && result.status() == PreHandleResult.Status.SO_FAR_SO_GOOD) {
            final var innerTxns = result.txInfo().txBody().atomicBatchOrThrow().transactions();
            var useInnerResults = maybeReusableResult != null
                    && maybeReusableResult.innerResults() != null
                    && !maybeReusableResult.innerResults().isEmpty();
            // Use the inner results only if the number of inner results matches the number of inner transactions
            if (useInnerResults && maybeReusableResult.innerResults().size() != innerTxns.size()) {
                useInnerResults = false;
                log.warn("The number of inner results in the atomic batch transaction does not match the number of "
                        + "inner transactions. Need to re-run pre-handle for all inner transactions.");
            }
            final List<Bytes> serializedInnerTxn;
            try {
                serializedInnerTxn = ProtobufUtils.extractInnerTransactionBytes(
                        result.txInfo().signedBytes());
            } catch (IOException | ParseException e) {
                // This should not happen
                return PreHandleResult.nodeDueDiligenceFailure(
                        creatorInfo.accountId(),
                        ResponseCodeEnum.INVALID_TRANSACTION,
                        result.txInfo(),
                        result.configVersion());
            }
            for (int i = 0; i < innerTxns.size(); i++) {
                final var innerResult = preHandleTransaction(
                        creatorInfo,
                        storeFactory,
                        accountStore,
                        serializedInnerTxn.get(i),
                        useInnerResults ? maybeReusableResult.innerResults().get(i) : null,
                        ignore -> {},
                        InnerTransaction.YES);
                result.innerResults().add(innerResult);
            }
        }
        return result;
    }

    /**
     * This method gets all the verification data for the current transaction. If pre-handle was previously ran
     * successfully, we only add the missing keys. If it did not run or an error occurred, we run it again.
     * If there is a due diligence error, this method will return a CryptoTransfer to charge the node along with
     * its verification data.
     *
     * @param creator      the node that created the transaction
     * @param platformTxn  the transaction to be verified
     * @param storeFactory the store factory
     * @param stateSignatureTxnCallback a callback to be called when encountering a {@link StateSignatureTransaction}
     * @return the verification data for the transaction
     */
    @NonNull
    default PreHandleResult getCurrentPreHandleResult(
            @NonNull final NodeInfo creator,
            @NonNull final ConsensusTransaction platformTxn,
            @NonNull final ReadableStoreFactory storeFactory,
            @NonNull final Consumer<StateSignatureTransaction> stateSignatureTxnCallback) {
        final var metadata = platformTxn.getMetadata();
        final PreHandleResult previousResult;
        if (metadata instanceof PreHandleResult result) {
            previousResult = result;
        } else {
            // This should be impossible since the Platform contract guarantees that
            // ConsensusStateEventHandler.onPreHandle()
            // is always called before ConsensusStateEventHandler.onHandleTransaction(); and our preHandle()
            // implementation
            // always sets the metadata to a PreHandleResult
            log.error(
                    "Received transaction without PreHandleResult metadata from node {} (was {})",
                    creator.nodeId(),
                    metadata);
            previousResult = null;
        }
        // We do not know how long transactions are kept in memory. Clearing metadata to avoid keeping it for too long.
        platformTxn.setMetadata(null);
        return preHandleAllTransactions(
                creator,
                storeFactory,
                storeFactory.getStore(ReadableAccountStore.class),
                platformTxn.getApplicationTransaction(),
                previousResult,
                stateSignatureTxnCallback);
    }

    enum InnerTransaction {
        YES,
        NO
    }
}
