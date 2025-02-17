/*
 * Copyright (C) 2022-2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hedera.node.app.workflows.prehandle;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.store.ReadableStoreFactory;
import com.swirlds.platform.system.events.Event;
import com.swirlds.platform.system.transaction.ConsensusTransaction;
import com.swirlds.platform.system.transaction.Transaction;
import com.swirlds.state.lifecycle.info.NodeInfo;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** A workflow to pre-handle transactions. */
public interface PreHandleWorkflow {
    Logger log = LogManager.getLogger(PreHandleWorkflow.class);

    /**
     * Starts the pre-handle transaction workflow of the {@link Event}
     *
     * @param readableStoreFactory the {@link ReadableStoreFactory} that is used for looking up stores
     * @param creator The {@link AccountID} of the node that created these transactions
     * @param transactions An {@link Stream} over all transactions to pre-handle
     * @param stateSignatureTxnCallback A callback to be called when encountering a {@link StateSignatureTransaction}
     * @throws NullPointerException if one of the arguments is {@code null}
     */
    void preHandle(
            @NonNull final ReadableStoreFactory readableStoreFactory,
            @NonNull final AccountID creator,
            @NonNull final Stream<Transaction> transactions,
            @NonNull final Consumer<StateSignatureTransaction> stateSignatureTxnCallback);

    /**
     * A convenience method to start the pre-handle transaction workflow for a single
     * user transaction without a reusable result.
     *
     * @param creator The {@link AccountID} of the node that created these transactions
     * @param storeFactory The {@link ReadableStoreFactory} based on the current state
     * @param accountStore The {@link ReadableAccountStore} based on the current state
     * @param platformTx The {@link Transaction} to pre-handle
     * @param stateSignatureTxnCallback A callback to be called when encountering a {@link StateSignatureTransaction}
     * @return The {@link PreHandleResult} of running pre-handle
     */
    default @NonNull PreHandleResult preHandleTransaction(
            @NonNull AccountID creator,
            @NonNull ReadableStoreFactory storeFactory,
            @NonNull ReadableAccountStore accountStore,
            @NonNull Transaction platformTx,
            @NonNull Consumer<StateSignatureTransaction> stateSignatureTxnCallback) {
        return preHandleTransaction(creator, storeFactory, accountStore, platformTx, null, stateSignatureTxnCallback);
    }

    /**
     * Starts the pre-handle transaction workflow for a single transaction.
     *
     * <p>If this method is called directly, pre-handle is done on the current thread.
     *
     * @param creator The {@link AccountID} of the node that created these transactions
     * @param storeFactory The {@link ReadableStoreFactory} based on the current state
     * @param accountStore The {@link ReadableAccountStore} based on the current state
     * @param platformTx The {@link Transaction} to pre-handle
     * @param maybeReusableResult The result of a previous call to the same method that may,
     * @param stateSignatureTxnCallback A callback to be called when encountering a {@link StateSignatureTransaction}
     * depending on changes in state, be reusable for this call
     * @return The {@link PreHandleResult} of running pre-handle
     */
    @NonNull
    PreHandleResult preHandleTransaction(
            @NonNull AccountID creator,
            @NonNull ReadableStoreFactory storeFactory,
            @NonNull ReadableAccountStore accountStore,
            @NonNull Transaction platformTx,
            @Nullable PreHandleResult maybeReusableResult,
            @NonNull Consumer<StateSignatureTransaction> stateSignatureTxnCallback);

    /**
     * This method gets all the verification data for the current transaction. If pre-handle was previously ran
     * successfully, we only add the missing keys. If it did not run or an error occurred, we run it again.
     * If there is a due diligence error, this method will return a CryptoTransfer to charge the node along with
     * its verification data.
     *
     * @param creator the node that created the transaction
     * @param platformTxn the transaction to be verified
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
            // This should be impossible since the Platform contract guarantees that StateLifecycles.onPreHandle()
            // is always called before StateLifecycles.onHandleTransaction(); and our preHandle() implementation
            // always sets the metadata to a PreHandleResult
            log.error(
                    "Received transaction without PreHandleResult metadata from node {} (was {})",
                    creator.nodeId(),
                    metadata);
            previousResult = null;
        }
        // We do not know how long transactions are kept in memory. Clearing metadata to avoid keeping it for too long.
        platformTxn.setMetadata(null);
        return preHandleTransaction(
                creator.accountId(),
                storeFactory,
                storeFactory.getStore(ReadableAccountStore.class),
                platformTxn,
                previousResult,
                stateSignatureTxnCallback);
    }
}
