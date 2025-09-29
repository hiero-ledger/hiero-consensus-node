// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.app;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.swirlds.state.lifecycle.Schema;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.function.Consumer;
import org.hiero.consensus.model.event.Event;
import org.hiero.consensus.model.hashgraph.Round;
import org.hiero.consensus.model.transaction.ScopedSystemTransaction;
import org.hiero.consensus.model.transaction.Transaction;

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
     * Get the schema for the genesis state of this service.
     *
     * @param version the current software version
     * @return the schema for the genesis state of this service
     */
    @NonNull
    Schema genesisSchema(@NonNull SemanticVersion version);

    /**
     * Called when a new round of consensus has been received. The service should only do actions
     * for the whole round in this method. For actions on individual events, use {@link #onEvent(WritableStates, Event)}.
     * For actions on individual transactions, use {@link #onTransaction(WritableStates, Event, Transaction, Consumer)}.
     *
     * @param writableStates the {@link WritableStates} to use to modify state
     * @param round
     */
    default void onRound(@NonNull final WritableStates writableStates, @NonNull final Round round) {
        // Default implementation does nothing
    }

    /**
     * Called when a new event has been received. The service should only do actions for the whole event in this method.
     * For actions on individual transactions, use {@link #onTransaction(WritableStates, Event, Transaction, Consumer)}.
     *
     * @param writableStates the {@link WritableStates} to use to modify state
     * @param event the event to handle
     */
    default void onEvent(@NonNull final WritableStates writableStates, @NonNull final Event event) {
        // Default implementation does nothing
    }

    /**
     * Called when a new transaction has been received.
     *
     * @param writableStates the {@link WritableStates} to use to modify state
     * @param event the event that contains the transaction
     * @param transaction the transaction to handle
     * @param callback a callback to pass any system transactions to be handled by the platform
     */
    default void onTransaction(
            @NonNull final WritableStates writableStates,
            @NonNull final Event event,
            @NonNull final Transaction transaction,
            @NonNull final Consumer<ScopedSystemTransaction<StateSignatureTransaction>> callback) {
        // Default implementation does nothing
    }
}
