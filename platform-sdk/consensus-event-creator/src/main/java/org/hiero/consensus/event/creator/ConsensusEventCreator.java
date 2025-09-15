// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.event.creator;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.base.time.Time;
import com.swirlds.component.framework.component.InputWireLabel;
import com.swirlds.component.framework.model.WiringModel;
import com.swirlds.component.framework.wires.input.InputWire;
import com.swirlds.component.framework.wires.output.OutputWire;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.status.PlatformStatus;

/**
 * Public API of the Consensus Event Creator component.
 */
@SuppressWarnings("unused")
public interface ConsensusEventCreator {

    /**
     * {@link InputWire} for valid, ordered, and recorded events received from the
     * {@code EventIntake} component.
     *
     * @return the {@link InputWire} for the received events
     */
    @InputWireLabel("PlatformEvent")
    @NonNull
    InputWire<PlatformEvent> getOrderedEventsInputWire();

    /**
     * {@link OutputWire} for new self events created by this component.
     *
     * @return the {@link OutputWire} for the new self events
     */
    @NonNull
    OutputWire<PlatformEvent> getMaybeCreatedEventOutputWire();

    // *******************************************************************
    // Additional wires. Most likely going to be added to the architecture
    // *******************************************************************

    /**
     * {@link InputWire} for the health status of the consensus module received from the
     * {@code HealthMonitor}. The health status is represented as a {@link Duration} indicating the
     * time since the system became unhealthy.
     *
     * @return the {@link InputWire} for the health status
     */
    @InputWireLabel("health info")
    @NonNull
    InputWire<Duration> getHealthStatusInputWire();

    /**
     * {@link InputWire} for the heartbeat signal received from the {@code HeartbeatGenerator}. The
     * heartbeat signal is sent at regular intervals to trigger the creation of new events.
     *
     * @return the {@link InputWire} for the heartbeat signal
     */
    @InputWireLabel("heartbeat")
    @NonNull
    InputWire<Instant> getHeartbeatInputWire();

    /**
     * {@link InputWire} for the platform status received from the {@code StatusStateMachine}.
     *
     * @return the {@link InputWire} for the platform status
     */
    @InputWireLabel("PlatformStatus")
    @NonNull
    InputWire<PlatformStatus> getPlatformStatusInputWire();

    /**
     * {@link InputWire} for the sync round lag.
     *
     * <p>The sync round lag is the number of rounds this node is behind the median of the latest
     * rounds of all connected peers.
     *
     * @return the {@link InputWire} for the sync round lag
     */
    @InputWireLabel("sync round lag")
    @NonNull
    InputWire<Double> getSyncRoundLagInputWire();

    /**
     * Initialize the components
     *
     * @param model,
     * @param configuration
     * @param metrics
     * @param time
     * @param random
     * @param keysAndCerts
     * @param roster
     * @param selfId
     * @param transactionSupplier
     */
    void initialize(
            @NonNull WiringModel model,
            @NonNull Configuration configuration,
            @NonNull Metrics metrics,
            @NonNull Time time,
            @NonNull SecureRandom random,
            @NonNull KeysAndCerts keysAndCerts,
            @NonNull Roster roster,
            @NonNull NodeId selfId,
            @NonNull TransactionSupplier transactionSupplier);

    /**
     * Destroys the component.
     *
     * @return this {@link ConsensusEventCreator} instance
     */
    @NonNull
    ConsensusEventCreator destroy();

    /**
     * Listener for transaction requests.
     *
     * <p>The {@link ConsensusEventCreator} will call the {@link #getTransactionsForEvent()} method
     * to get all transactions that should be added to the next event.
     */
    interface TransactionSupplier {
        /**
         * Returns all transactions that should be added to the next event.
         *
         * @return the transactions to add to the next event
         */
        @NonNull
        List<Bytes> getTransactionsForEvent();

        /**
         * Returns whether there are any transactions that should be added to the next event.
         *
         * @return {@code true} if there are transactions waiting, {@code false} otherwise
         */
        boolean hasTransactionsForEvents();
    }

    // *****************************************************************
    // Temporary workaround to allow reuse of the EventCreator component
    // *****************************************************************

    /**
     * {@link InputWire} for the event window received from the {@code Hashgraph} component.
     *
     * @return the {@link InputWire} for the event window
     */
    @InputWireLabel("event window")
    @NonNull
    InputWire<EventWindow> getEventWindowInputWire();

    /**
     * Starts squelching the internal event creation manager.
     *
     * <p>Please note that this method is a temporary workaround and will be removed in the future.
     */
    void startSquelching();

    /**
     * Flushes all events of the internal event creation manager.
     *
     * <p>Please note that this method is a temporary workaround and will be removed in the future.
     */
    void flush();

    /**
     * Stops squelching the internal event creation manager.
     *
     * <p>Please note that this method is a temporary workaround and will be removed in the future.
     */
    void stopSquelching();

    /**
     * Get an {@link InputWire} to clear the internal state of the internal event creation manager.
     *
     * <p>Please note that this method is a temporary workaround and will be removed in the future.
     *
     * @return the {@link InputWire} to clear the event creation manager
     */
    @InputWireLabel("clear")
    @NonNull
    InputWire<Object> getClearEventCreationMangerInputWire();
}
