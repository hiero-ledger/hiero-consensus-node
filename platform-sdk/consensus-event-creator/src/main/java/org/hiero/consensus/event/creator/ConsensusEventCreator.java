// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.event.creator;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.component.framework.model.WiringModel;
import com.swirlds.component.framework.wires.input.InputWire;
import com.swirlds.component.framework.wires.output.OutputWire;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.List;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.status.PlatformStatus;

/**
 * Public API of the Consensus Event Creator component.
 *
 * <p>Every node in the network participating in consensus is permitted to create events to gossip
 * to neighbors. These events are used both for transmitting user transactions, and as the basis of
 * the hashgraph algorithm for "gossiping about gossip". Therefore, the Event Creator has two main
 * responsibilities:
 * <ul>
 *     <li>Create events with "other parent(s)" so as to help the hashgraph progress consensus</li>
 *     <li>Fill events with transactions to be sent to the network through Gossip</li>
 * </ul>
 *
 * <h2>Creating Events</h2>
 *
 * <p>The Event Creator is configured with a maximum_event_creation_frequency, measured in
 * events/sec. This is a network wide setting. If any node creates events more rapidly than this
 * setting, then the node will be reported to the Sheriff. An event is not necessarily created at
 * this frequency, but will be created at no more than this frequency.
 *
 * <p>When it is time to potentially create an event, the Event Creator will determine whether it
 * should create the event. It may consider whether there are any transactions to send, or whether
 * creating an event will help advance the hashgraph. It may decide that creating the event would be
 * bad for the network, and veto such creation. Or it may decide that creating the event should be
 * permitted.
 *
 * <p>If the event is to be created, the Event Creator will decide which nodes to select as "other
 * parents". Today, we have exactly one "other parent" per event, but multiple "other parents" is
 * shown to effectively reduce latency and network traffic. While the implementation of Event
 * Creator may choose to support only a single "other parent", the module is designed and intended
 * to support multiple "other parents".
 *
 * <h2>Filling Events</h2>
 *
 * <p>Events form a large amount of the network traffic between nodes. Each event has some overhead
 * in terms of metadata, such as the hashes of the parent events and cryptographic signatures. Thus,
 * for bandwidth and scalability reasons, it is more desirable to have fewer, large events rather
 * than many small events. On the other hand, events should be created frequently enough to reduce
 * the overall latency experienced by a transaction. The Event Creator is designed so as to find the
 * optimal balance between event creation frequency and size. The particular algorithm that does so
 * (the Tipset algorithm, or "Enhanced Other Parent Selection" algorithm) is not defined here, but
 * can be found in the design documentation for Event Creator.
 *
 * <p>When it is time to create a new event, a call is made to Execution to fill the event with user
 * transactions. Newly created events are sent to Event Intake, which then validates them, assigns
 * generations, durably persists them, etc., before sending them out through Gossip and so forth.
 *
 * <h2>Stale Self-Events</h2>
 *
 * <p>The Event Creator needs to know about the state of the hashgraph for several reasons. If it
 * uses the Tipset algorithm, then it needs a way to evict events from its internal caches that are
 * ancient. And it needs to report "stale" self-events to the Execution layer. A stale self-event is
 * a self-event that became ancient without ever coming to consensus. If the Event Creator
 * determines that a self-event has become stale, then it will notify the Execution layer. Execution
 * may look at each transaction within the self-event, and decide that some transactions (such as
 * those that have expired or will soon expire) should be dropped while others (such as those not
 * close to expiration) should be resubmitted in the next event.
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
     * @param platformContext the platform context to be used during initialization
     * @param model the wiring model to be used during initialization
     * @return this {@link ConsensusEventCreator} instance
     */
    @NonNull
    ConsensusEventCreator initialize(@NonNull PlatformContext platformContext, @NonNull WiringModel model);

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
