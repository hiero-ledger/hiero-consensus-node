// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.app.services.platform;

import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.swirlds.platform.state.service.WritablePlatformStateStore;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.function.Consumer;
import org.hiero.base.utility.CommonUtils;
import org.hiero.consensus.model.event.ConsensusEvent;
import org.hiero.consensus.model.event.Event;
import org.hiero.consensus.model.transaction.ScopedSystemTransaction;
import org.hiero.otter.fixtures.app.OtterFreezeTransaction;
import org.hiero.otter.fixtures.app.OtterService;
import org.hiero.otter.fixtures.app.OtterTransaction;
import org.hiero.otter.fixtures.app.state.OtterServiceStateSpecification;

/**
 * The main entry point for the PlatformState service in the Otter application.
 */
public class PlatformStateService implements OtterService {

    private static final String NAME = "PlatformStateService";

    private static final PlatformStateSpecification STATE_SPECIFICATION = new PlatformStateSpecification();

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public String name() {
        return NAME;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public OtterServiceStateSpecification stateSpecification() {
        return STATE_SPECIFICATION;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void handleTransaction(
            @NonNull final WritableStates writableStates,
            @NonNull final ConsensusEvent event,
            @NonNull final OtterTransaction transaction,
            @NonNull final Instant transactionTimestamp,
            @NonNull final Consumer<ScopedSystemTransaction<StateSignatureTransaction>> callback) {
        switch (transaction.data().kind()) {
            case FREEZE_TRANSACTION -> handleFreeze(writableStates, transaction.freezeTransaction());
            case STATE_SIGNATURE_TRANSACTION ->
                handleStateSignature(event, transaction.stateSignatureTransaction(), callback);
            case EMPTY_TRANSACTION, UNSET -> {
                // No action needed for empty transactions
            }
        }
    }

    /**
     * Handles the freeze transaction by updating the freeze time in the platform state.
     *
     * @param writableStates the current state of the Otter testing tool
     * @param freezeTransaction the freeze transaction to handle
     */
    private static void handleFreeze(
            @NonNull final WritableStates writableStates, @NonNull final OtterFreezeTransaction freezeTransaction) {
        final Timestamp freezeTime = freezeTransaction.freezeTime();
        final WritablePlatformStateStore store = new WritablePlatformStateStore(writableStates);
        store.setFreezeTime(CommonUtils.fromPbjTimestamp(freezeTime));
    }

    /**
     * Handles the state signature transaction by creating a new ScopedSystemTransaction and passing it to the callback.
     *
     * @param event the event associated with the transaction
     * @param transaction the state signature transaction to handle
     * @param callback the callback to invoke with the new ScopedSystemTransaction
     */
    private static void handleStateSignature(
            @NonNull final Event event,
            @NonNull final StateSignatureTransaction transaction,
            @NonNull final Consumer<ScopedSystemTransaction<StateSignatureTransaction>> callback) {
        callback.accept(new ScopedSystemTransaction<>(event.getCreatorId(), event.getBirthRound(), transaction));
    }
}
