// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.turtle.app;

import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.hedera.node.app.hapi.utils.CommonPbjConverters;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.platform.state.service.WritablePlatformStateStore;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.function.Consumer;
import org.hiero.base.utility.CommonUtils;
import org.hiero.consensus.model.event.Event;
import org.hiero.consensus.model.transaction.ScopedSystemTransaction;

/**
 * Utility class to handle transactions in the Turtle testing tool.
 */
public class TransactionHandlers {

    private TransactionHandlers() {}

    /**
     * Handles the transaction based on its type.
     *
     * @param state the current state of the Turtle testing tool
     * @param event the event associated with the transaction
     * @param transaction the transaction to handle
     * @param callback the callback to invoke with the new ScopedSystemTransaction
     */
    public static void handleTransaction(
            @NonNull final TurtleAppState state,
            @NonNull final Event event,
            @NonNull final TurtleTransaction transaction,
            @NonNull Consumer<ScopedSystemTransaction<StateSignatureTransaction>> callback) {
        switch (transaction.getDataCase()) {
            case FREEZETRANSACTION -> handleFreeze(state, transaction.getFreezeTransaction());
            case STATESIGNATURETRANSACTION ->
                handleStateSignature(event, transaction.getStateSignatureTransaction(), callback);
            case EMPTYTRANSACTION, DATA_NOT_SET -> {
                // No action needed for empty transactions
            }
        }
    }

    /**
     * Handles the freeze transaction by updating the freeze time in the platform state.
     *
     * @param state the current state of the Turtle testing tool
     * @param freezeTransaction the freeze transaction to handle
     */
    public static void handleFreeze(
            @NonNull final TurtleAppState state, @NonNull final TurtleFreezeTransaction freezeTransaction) {
        final Timestamp freezeTime = CommonPbjConverters.toPbj(freezeTransaction.getFreezeTime());
        WritablePlatformStateStore store =
                new WritablePlatformStateStore(state.getWritableStates("PlatformStateService"));
        store.setFreezeTime(CommonUtils.fromPbjTimestamp(freezeTime));
    }

    /**
     * Handles the state signature transaction by creating a new ScopedSystemTransaction and passing it to the callback.
     *
     * @param event the event associated with the transaction
     * @param transaction the state signature transaction to handle
     * @param callback the callback to invoke with the new ScopedSystemTransaction
     */
    public static void handleStateSignature(
            @NonNull final Event event,
            @NonNull final com.hedera.hapi.platform.event.legacy.StateSignatureTransaction transaction,
            @NonNull final Consumer<ScopedSystemTransaction<StateSignatureTransaction>> callback) {
        final StateSignatureTransaction newTransaction = new StateSignatureTransaction(
                transaction.getRound(),
                Bytes.wrap(transaction.getSignature().toByteArray()),
                Bytes.wrap(transaction.getHash().toByteArray()));
        callback.accept(
                new ScopedSystemTransaction<>(event.getCreatorId(), event.getSoftwareVersion(), newTransaction));
    }
}
