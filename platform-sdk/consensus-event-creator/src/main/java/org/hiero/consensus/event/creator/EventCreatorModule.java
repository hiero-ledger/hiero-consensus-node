// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.event.creator;

import com.hedera.hapi.node.state.roster.Roster;
import com.swirlds.base.time.Time;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.security.SecureRandom;
import java.time.Duration;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.quiescence.QuiescenceCommand;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.consensus.model.transaction.EventTransactionSupplier;
import org.hiero.consensus.model.transaction.SignatureTransactionCheck;

/**
 * Creates and signs events. Will sometimes decide not to create new events based on external rules.
 */
public interface EventCreatorModule {

    /**
     * Initialize the event creator
     *
     * @param configuration             provides the configuration for the event creator
     * @param metrics                   provides the metrics for the event creator
     * @param time                      provides the time source for the event creator
     * @param random                    provides the secure random source for the event creator
     * @param keysAndCerts              provides the key for signing events
     * @param roster                    provides the current roster
     * @param selfId                    the ID of this node
     * @param transactionSupplier       provides transactions to include in events
     * @param signatureTransactionCheck checks for pending signature transactions
     */
    void initialize(
            @NonNull Configuration configuration,
            @NonNull Metrics metrics,
            @NonNull Time time,
            @NonNull SecureRandom random,
            @NonNull KeysAndCerts keysAndCerts,
            @NonNull Roster roster,
            @NonNull NodeId selfId,
            @NonNull EventTransactionSupplier transactionSupplier,
            @NonNull SignatureTransactionCheck signatureTransactionCheck);

    /**
     * Attempt to create an event.
     *
     * @return the created event, or null if no event was created
     */
    @Nullable
    PlatformEvent maybeCreateEvent();

    /**
     * Register a new event from event intake.
     *
     * @param event the event to add
     */
    void registerEvent(@NonNull PlatformEvent event);

    /**
     * Update the event window, defining the minimum threshold for an event to be non-ancient.
     *
     * @param eventWindow the event window
     */
    void setEventWindow(@NonNull EventWindow eventWindow);

    /**
     * Update the platform status.
     *
     * @param platformStatus the new platform status
     */
    void updatePlatformStatus(@NonNull PlatformStatus platformStatus);

    /**
     * Report the amount of time that the system has been in an unhealthy state. Will receive a report of
     * {@link Duration#ZERO} when the system enters a healthy state.
     *
     * @param duration the amount of time that the system has been in an unhealthy state
     */
    void reportUnhealthyDuration(@NonNull final Duration duration);

    /**
     * Report the lag in rounds behind the other nodes. A negative value means we are ahead of the other nodes.
     *
     * @param lag the lag in rounds behind the other nodes
     */
    void reportSyncRoundLag(@NonNull Double lag);

    /**
     * Set the quiescence state of this event creator. The event creator will always behave according to the most
     * recent quiescence command that it has been given.
     *
     * @param quiescenceCommand the quiescence command
     */
    void quiescenceCommand(@NonNull QuiescenceCommand quiescenceCommand);

    /**
     * Clear the internal state of the event creation manager.
     */
    void clear();
}
