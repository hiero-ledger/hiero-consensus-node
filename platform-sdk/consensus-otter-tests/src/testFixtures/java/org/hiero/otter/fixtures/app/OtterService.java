// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.app;

import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.swirlds.config.api.Configuration;
import com.swirlds.platform.system.InitTrigger;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.function.Consumer;
import org.hiero.consensus.model.event.ConsensusEvent;
import org.hiero.consensus.model.event.Event;
import org.hiero.consensus.model.hashgraph.Round;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.transaction.ScopedSystemTransaction;
import org.hiero.otter.fixtures.app.state.OtterServiceStateSpecification;

/**
 * This interface defines a service of the Otter application.
 */
public interface OtterService {

    /**
     * Get the name of this service.
     *
     * @return the name of this service
     */
    @NonNull
    String name();

    /**
     * Get the specification of the state for this service.
     *
     * @return the state specification for this service
     */
    @NonNull
    OtterServiceStateSpecification stateSpecification();

    /**
     * Called when the service is initialized. This is called once when the application starts up.
     *
     * @param trigger the trigger that caused the initialization
     * @param selfId the ID of this node
     * @param configuration the configuration to use
     * @param state the current state at the time of initialization
     */
    default void initialize(
            @NonNull final InitTrigger trigger,
            @NonNull final NodeId selfId,
            @NonNull final Configuration configuration,
            @NonNull final OtterAppState state) {
        // Default implementation does nothing
    }

    /**
     * Called when the service is being shut down. This is called once when the application is shutting down.
     */
    default void destroy() {
        // Default implementation does nothing
    }

    /**
     * Called when a new round of consensus has been received. The service should only do actions for the whole round in
     * this method. For actions on individual events, use {@link #onEventStart(WritableStates, ConsensusEvent)}. For
     * actions on individual transactions, use
     * {@link #handleTransaction(WritableStates, ConsensusEvent, OtterTransaction, Instant, Consumer)} .
     *
     * @param writableStates the {@link WritableStates} to use to modify state
     * @param round the round to handle
     */
    default void onRoundStart(@NonNull final WritableStates writableStates, @NonNull final Round round) {
        // Default implementation does nothing
    }

    /**
     * Called when a round of consensus has been completely handled. This is called after all events and transactions in
     * the round have been handled.
     *
     * @param round the round that was completed
     */
    default void onRoundComplete(@NonNull final Round round) {
        // Default implementation does nothing
    }

    /**
     * Called when a new event has been received. The service should only do actions for the whole event in this method.
     * For actions on individual transactions, use
     * {@link #handleTransaction(WritableStates, ConsensusEvent, OtterTransaction, Instant, Consumer)}.
     *
     * @param writableStates the {@link WritableStates} to use to modify state
     * @param event the event to handle
     */
    default void onEventStart(@NonNull final WritableStates writableStates, @NonNull final ConsensusEvent event) {
        // Default implementation does nothing
    }

    /**
     * Called when an event has been completely handled. This is called after all transactions in the event have been
     * handled.
     *
     * @param event the event that was completed
     */
    default void onEventComplete(@NonNull final ConsensusEvent event) {
        // Default implementation does nothing
    }

    /**
     * Called when a new transaction has been received.
     *
     * @param writableStates the {@link WritableStates} to use to modify state
     * @param event the event that contains the transaction
     * @param transaction the transaction to handle
     * @param transactionTimestamp the consensus timestamp of the transaction
     * @param callback a callback to pass any system transactions to be handled by the platform
     */
    default void handleTransaction(
            @NonNull final WritableStates writableStates,
            @NonNull final ConsensusEvent event,
            @NonNull final OtterTransaction transaction,
            @NonNull final Instant transactionTimestamp,
            @NonNull final Consumer<ScopedSystemTransaction<StateSignatureTransaction>> callback) {
        // Default implementation does nothing
    }

    /**
     * Called when an event is being pre-handled. This is called before any transactions in the event are pre-handled.
     * The service should only do actions for the whole event in this method. For actions on individual transactions,
     * use {@link #preHandleTransaction(Event, OtterTransaction, Consumer)}.
     *
     * @param event the event being pre-handled
     */
    default void preHandleEvent(@NonNull final Event event) {
        // Default implementation does nothing
    }

    /**
     * Called when a transaction is being pre-handled.
     *
     * @param event the event that contains the transaction
     * @param transaction the transaction being pre-handled
     * @param callback a callback to pass any system transactions to be handled by the platform
     */
    default void preHandleTransaction(
            @NonNull final Event event,
            @NonNull final OtterTransaction transaction,
            @NonNull final Consumer<ScopedSystemTransaction<StateSignatureTransaction>> callback) {
        // Default implementation does nothing
    }
}
