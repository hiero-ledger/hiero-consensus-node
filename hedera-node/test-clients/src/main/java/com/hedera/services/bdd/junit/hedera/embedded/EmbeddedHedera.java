// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.hedera.embedded;

import static com.hedera.services.bdd.junit.hedera.embedded.fakes.FakeEvent.FAKE_EVENT_BIRTH_ROUND;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.hedera.node.app.Hedera;
import com.hedera.node.app.fixtures.state.FakeState;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.Query;
import com.hederahashgraph.api.proto.java.Response;
import com.hederahashgraph.api.proto.java.Timestamp;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.api.proto.java.TransactionID;
import com.hederahashgraph.api.proto.java.TransactionResponse;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Consumer;
import org.hiero.consensus.model.transaction.ScopedSystemTransaction;

public interface EmbeddedHedera {
    /**
     * Starts the embedded Hedera node.
     */
    void start();

    /**
     * Starts the embedded Hedera node from a saved state customized by the given specs.
     *
     * @param state the state to customize
     */
    void restart(@NonNull FakeState state);

    /**
     * Stops the embedded Hedera node.
     */
    void stop();

    /**
     * Returns the fake state of the embedded Hedera node.
     * @return the fake state of the embedded Hedera node
     */
    FakeState state();

    /**
     * Returns the software version of the embedded Hedera node.
     * @return the software version of the embedded Hedera node
     */
    SemanticVersion version();

    /**
     * Returns the next in a repeatable sequence of valid start times that the embedded Hedera's
     * ingest workflow will accept within a {@link TransactionID}.
     *
     * <p>Ensures unique timestamps by incrementing the nanosecond field of the timestamp each time
     * this method is called---which will definitely be fewer than a billion times in a second.
     *
     * @return the next valid start time from a repeatable sequence
     */
    Timestamp nextValidStart();

    /**
     * Returns the current synthetic time in the embedded Hedera node.
     *
     * @return the current synthetic time
     */
    Instant now();

    /**
     * Returns the duration of elapsed synthetic time in the embedded Hedera node.
     * @return the duration of elapsed synthetic time
     */
    Duration restartOffset();

    /**
     * Returns the embedded Hedera.
     * @return the embedded Hedera
     */
    Hedera hedera();

    /**
     * Returns the roster of the embedded Hedera node.
     * @return the roster of the embedded Hedera node
     */
    Roster roster();

    /**
     * Advances the synthetic time in the embedded Hedera node by a given duration.
     */
    void tick(@NonNull Duration duration);

    /**
     * Submits a transaction to the embedded node.
     *
     * @param transaction the transaction to submit
     * @param nodeAccountId the account ID of the node to submit the transaction to
     * @return the response to the transaction
     */
    default TransactionResponse submit(@NonNull Transaction transaction, @NonNull AccountID nodeAccountId) {
        return submit(transaction, nodeAccountId, FAKE_EVENT_BIRTH_ROUND);
    }

    /**
     * Submits a transaction to the embedded node.
     *
     * @param transaction the transaction to submit
     * @param nodeAccountId the account ID of the node to submit the transaction to
     * @param preHandleCallback the callback to call during preHandle when a {@link StateSignatureTransaction} is encountered
     * @param handleCallback the callback to call during preHandle when a {@link StateSignatureTransaction} is encountered
     * @return the response to the transaction
     */
    TransactionResponse submit(
            @NonNull Transaction transaction,
            @NonNull AccountID nodeAccountId,
            @NonNull Consumer<ScopedSystemTransaction<StateSignatureTransaction>> preHandleCallback,
            @NonNull Consumer<ScopedSystemTransaction<StateSignatureTransaction>> handleCallback);

    /**
     * Sends a query to the embedded node.
     *
     * @param query the query to send
     * @param nodeAccountId the account ID of the node to send the query to
     * @return the response to the query
     */
    Response send(@NonNull Query query, @NonNull AccountID nodeAccountId, final boolean asNodeOperator);

    /**
     * Submits a transaction to the embedded node.
     * @param transaction the transaction to submit
     * @param nodeAccountId the account ID of the node to submit the transaction to
     * @param eventBirthRound the round in which the event was born, used for event processing
     * @return the response to the transaction
     */
    TransactionResponse submit(Transaction transaction, AccountID nodeAccountId, long eventBirthRound);

    /**
     * Triggers the stale event callback for a given transaction.
     * @param transaction the transaction for which to trigger the stale event callback
     */
    void triggerStaleEventCallbackForTransaction(@NonNull Transaction transaction);

    /**
     * Sets whether all events should be considered stale.
     * @param considerAllEventsStale true if all events should be considered stale, false otherwise
     */
    void considerAllEventsStale(boolean considerAllEventsStale);
}
