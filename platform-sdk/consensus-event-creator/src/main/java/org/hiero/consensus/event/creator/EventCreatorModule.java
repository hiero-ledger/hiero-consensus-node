// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.event.creator;

import com.hedera.hapi.node.state.roster.Roster;
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
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.gossip.SyncProgress;
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
     * @param model                     the wiring model for this component
     * @param configuration             provides the configuration for the event creator
     * @param metrics                   provides the metrics for the event creator
     * @param time                      provides the time source for the event creator
     * @param random                    provides the secure random source for the event creator
     * @param keysAndCerts              provides the keys and certificates for this node
     * @param roster                    provides the current roster
     * @param selfId                    the ID of this node
     * @param transactionSupplier       provides transactions to include in events
     * @param signatureTransactionCheck checks for pending signature transactions
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
            @NonNull EventTransactionSupplier transactionSupplier,
            @NonNull SignatureTransactionCheck signatureTransactionCheck);

    /**
     * {@link OutputWire} for new self events created by this component.
     *
     * @return the {@link OutputWire} for the new self events
     */
    @NonNull
    OutputWire<PlatformEvent> createdEventOutputWire();

    /**
     * {@link InputWire} for valid, ordered, and recorded events received from the
     * {@code EventIntake} module.
     *
     * @return the {@link InputWire} for the received events
     */
    @InputWireLabel("PlatformEvent")
    @NonNull
    InputWire<PlatformEvent> orderedEventInputWire();

    /**
     * {@link InputWire} for the event window received from the {@code Hashgraph} component.
     *
     * @return the {@link InputWire} for the event window
     */
    @InputWireLabel("event window")
    @NonNull
    InputWire<EventWindow> eventWindowInputWire();

    /**
     * {@link InputWire} for the platform status received from the {@code StatusStateMachine}.
     *
     * @return the {@link InputWire} for the platform status
     */
    @InputWireLabel("PlatformStatus")
    @NonNull
    InputWire<PlatformStatus> platformStatusInputWire();

    /**
     * {@link InputWire} for the health status of the consensus module received from the
     * {@code HealthMonitor}. The health status is represented as a {@link Duration} indicating the
     * time since the system became unhealthy.
     *
     * @return the {@link InputWire} for the health status
     */
    @InputWireLabel("health info")
    @NonNull
    InputWire<Duration> healthStatusInputWire();

    /**
     * {@link InputWire} for the sync progress. The sync information is provided for specific peers. The
     * {@link EventCreatorModule} can use this information to compute the round lag or any other information it needs
     * to control event creation.
     *
     * @return the {@link InputWire} for the sync round lag
     */
    @InputWireLabel("sync progress")
    @NonNull
    InputWire<SyncProgress> syncProgressInputWire();

    /**
     * {@link InputWire} for the quiescence command. The event creator will always behave according to the most recent
     * quiescence command that it has been given.
     *
     * @return the {@link InputWire} for the sync round lag
     */
    @InputWireLabel("quiescence")
    @NonNull
    InputWire<QuiescenceCommand> quiescenceCommandInputWire();

    /**
     * Destroys the module.
     */
    void destroy();

    // **************************************************************
    // Temporary workaround to allow reuse of the EventCreator module
    // **************************************************************

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
    InputWire<Object> clearCreationMangerInputWire();
}
