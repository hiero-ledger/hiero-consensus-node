// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.event.creator;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.base.time.Time;
import com.swirlds.component.framework.model.WiringModel;
import com.swirlds.component.framework.wires.input.InputWire;
import com.swirlds.component.framework.wires.output.OutputWire;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.List;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.hiero.consensus.model.hashgraph.EventWindow;
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
    @NonNull
    InputWire<PlatformEvent> getOrderedEventsInputWire();

    /**
     * {@link InputWire} for the rounds received from the {@code Hashgraph} component.
     *
     * @return the {@link InputWire} for the received rounds
     */
    @NonNull
    InputWire<ConsensusRound> getRoundsInputWire();

    /**
     * {@link OutputWire} for new self events created by this component.
     *
     * @return the {@link OutputWire} for the new self events
     */
    @NonNull
    OutputWire<PlatformEvent> getNewSelfEventOutputWire();

    /**
     * {@link OutputWire} for stale events detected by this component.
     *
     * @return the {@link OutputWire} for the stale events
     */
    @NonNull
    OutputWire<PlatformEvent> getStaleEventOutputWire();

    /**
     * Registers a listener for transaction requests.
     *
     * @param listener the listener to register
     * @return this {@link ConsensusEventCreator} instance
     */
    @NonNull
    ConsensusEventCreator registerTransactionRequestListener(@NonNull TransactionRequestListener listener);

    /**
     * Unegisters a listener for transaction requests.
     *
     * @param listener the listener to register
     * @return this {@link ConsensusEventCreator} instance
     */
    @NonNull
    ConsensusEventCreator unregisterTransactionRequestListener(@NonNull TransactionRequestListener listener);

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
    InputWire<Duration> getHealthStatusInputWire();

    /**
     * {@link InputWire} for the platform status received from the {@code StatusStateMachine}.
     *
     * @return the {@link InputWire} for the platform status
     */
    InputWire<PlatformStatus> getPlatformStatusInputWire();

    /**
     * Initializes the component.
     *
     * @param configuration the configuration to be used during initialization
     * @param metrics the metrics to be used during initialization
     * @param time the time source to be used during initialization
     * @param model the wiring model to be used during initialization
     * @return this {@link ConsensusEventCreator} instance
     */
    @NonNull
    ConsensusEventCreator initialize(
            @NonNull Configuration configuration,
            @NonNull Metrics metrics,
            @NonNull Time time,
            @NonNull WiringModel model);

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
    interface TransactionRequestListener {
        /**
         * Returns all transactions that should be added to the next event.
         *
         * @return the transactions to add to the next event
         */
        List<Bytes> getTransactionsForEvent();
    }

    // *****************************************************************
    // Temporary workaround to allow reuse of the EventCreator component
    // *****************************************************************

    /**
     * {@link InputWire} for the event window received from the {@code Hashgraph} component.
     *
     * <p>This InputWire should be combined with {@link #getRoundsInputWire()}.
     *
     * @return the {@link InputWire} for the event window
     */
    InputWire<EventWindow> getEventWindowInputWire();

    /**
     * {@link InputWire} for the initial event window received from the {@code Hashgraph} component.
     *
     * <p>This InputWire should be replaced with something else. It only notifies the stale event
     * detector about the event window after restart or reconnect. A direct method call seems more
     * appropriate. Right now, it is not clear why an InputWire is used here.
     *
     * @return the {@link InputWire} for the initial event window
     */
    InputWire<EventWindow> getInitialEventWindowInputWire();

    /**
     * Starts squelching the internal event creation manager.
     *
     * <p>Please note that this method is a temporary workaround and will be removed in the future.
     */
    void startSquelchingEventCreationManager();

    /**
     * Starts squelching the internal stale event detector.
     *
     * <p>Please note that this method is a temporary workaround and will be removed in the future.
     */
    void startSquelchingStaleEventDetector();

    /**
     * Flushes all events of the internal event creation manager.
     *
     * <p>Please note that this method is a temporary workaround and will be removed in the future.
     */
    void flushEventCreationManager();

    /**
     * Flushes all events of the internal stale event detector.
     *
     * <p>Please note that this method is a temporary workaround and will be removed in the future.
     */
    void flushStaleEventDetector();

    /**
     * Stops squelching the internal event creation manager.
     *
     * <p>Please note that this method is a temporary workaround and will be removed in the future.
     */
    void stopSquelchingEventCreationManager();

    /**
     * Stops squelching the internal stale event detector.
     *
     * <p>Please note that this method is a temporary workaround and will be removed in the future.
     */
    void stopSquelchingStaleEventDetector();

    /**
     * Get an {@link InputWire} to clear the internal state of the internal event creation manager.
     *
     * <p>Please note that this method is a temporary workaround and will be removed in the future.
     */
    InputWire<Void> getClearEventCreationMangerInputWire();

    /**
     * Get an {@link InputWire} to clear the internal state of the internal stale event detector.
     *
     * <p>Please note that this method is a temporary workaround and will be removed in the future.
     */
    InputWire<Void> getClearStaleEventDetectorInputWire();
}
