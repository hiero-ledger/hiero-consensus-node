// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.app.services.platform;

import static com.swirlds.logging.legacy.LogMarker.STARTUP;
import static com.swirlds.platform.state.service.PlatformStateUtils.isInFreezePeriod;
import static org.hiero.consensus.model.PbjConverters.fromPbjTimestamp;

import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.swirlds.config.api.Configuration;
import com.swirlds.platform.state.service.WritablePlatformStateStore;
import com.swirlds.platform.system.InitTrigger;
import com.swirlds.state.merkle.VirtualMapState;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.function.Consumer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.event.ConsensusEvent;
import org.hiero.consensus.model.event.Event;
import org.hiero.consensus.model.hashgraph.Round;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.transaction.ScopedSystemTransaction;
import org.hiero.otter.fixtures.app.OtterService;
import org.hiero.otter.fixtures.app.state.OtterServiceStateSpecification;
import org.hiero.otter.fixtures.network.transactions.OtterFreezeTransaction;
import org.hiero.otter.fixtures.network.transactions.OtterTransaction;

/**
 * The main entry point for the PlatformState service in the Otter application.
 */
public class PlatformStateService implements OtterService {

    private static final Logger log = LogManager.getLogger();

    private static final String NAME = "PlatformStateService";

    private static final PlatformStateSpecification STATE_SPECIFICATION = new PlatformStateSpecification();

    /**
     * {@inheritDoc}
     */
    @Override
    public void initialize(
            @NonNull final InitTrigger trigger,
            @NonNull final NodeId selfId,
            @NonNull final Configuration configuration,
            @NonNull final VirtualMapState state) {
        log.info(STARTUP.getMarker(), "PlatformStateService initialized");
    }

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
        store.setFreezeTime(fromPbjTimestamp(freezeTime));
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

    /**
     * {@inheritDoc}
     */
    @Override
    public void onRoundComplete(@NonNull final WritableStates writableStates, @NonNull final Round round) {
        final WritablePlatformStateStore store = new WritablePlatformStateStore(writableStates);

        // Update the latest freeze round after everything is handled.
        // The platform sets the latestFreezeTime, but not the freeze round :(
        if (isInFreezePeriod(round.getConsensusTimestamp(), store.getFreezeTime(), store.getLastFrozenTime())) {
            // If this is a freeze round, we need to update the freeze info state
            store.setLatestFreezeRound(round.getRoundNum());
        }
    }
}
