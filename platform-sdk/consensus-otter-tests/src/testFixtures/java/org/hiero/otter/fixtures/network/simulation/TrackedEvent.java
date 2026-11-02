// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.network.simulation;

import static java.util.Objects.requireNonNull;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Arrays;
import java.util.BitSet;
import org.hiero.consensus.model.event.PlatformEvent;

/**
 * An event, as identified by its descriptor and signature, that has been submitted to the simulated network, with what
 * the network needs to know about it: which nodes have it, and when it is first due to arrive at each node.
 *
 * <p>Nodes are identified by their rank, which is their index in the roster.
 *
 * <p>This class is not safe to use from more than one thread.
 */
final class TrackedEvent {

    /**
     * Stands for the arrival time of a copy that is not on its way.
     */
    static final long NOT_SENT = Long.MAX_VALUE;

    /**
     * One of the submitted instances of the event, which copies to send are made from.
     */
    private final PlatformEvent event;

    /**
     * The signature of the event.
     */
    private final Bytes signature;

    /**
     * The rank of the node that created the event, or {@code -1} if that node is not part of the network.
     */
    private final int creator;

    /**
     * The ranks of the nodes that have submitted the event. A node that has submitted an event has it, and can send it
     * on.
     */
    private final BitSet submitters = new BitSet();

    /**
     * The time the earliest copy that is on its way to a node arrives, indexed by the rank of the node, or
     * {@link #NOT_SENT} if there is none. It stays set once that copy has arrived.
     */
    private final long[] earliestArrivals;

    /**
     * The rank of the node that sent the copy in {@link #earliestArrivals}, indexed by the rank of the receiver.
     */
    private final int[] earliestSenders;

    /**
     * Whether the event has been added to the event log.
     */
    private boolean logged;

    /**
     * Constructor.
     *
     * @param event     one of the submitted instances of the event
     * @param creator   the rank of the node that created the event, or {@code -1} if that node is not part of the network
     * @param nodeCount the number of nodes in the network
     */
    TrackedEvent(@NonNull final PlatformEvent event, final int creator, final int nodeCount) {
        this.event = requireNonNull(event);
        this.signature = event.getSignature();
        this.creator = creator;
        earliestArrivals = new long[nodeCount];
        earliestSenders = new int[nodeCount];
        Arrays.fill(earliestArrivals, NOT_SENT);
        Arrays.fill(earliestSenders, -1);
    }

    /**
     * @return one of the submitted instances of the event, which must not be modified
     */
    @NonNull
    PlatformEvent event() {
        return event;
    }

    /**
     * @return the signature of the event
     */
    @NonNull
    Bytes signature() {
        return signature;
    }

    /**
     * @return the rank of the node that created the event, or {@code -1} if that node is not part of the network
     */
    int creator() {
        return creator;
    }

    /**
     * @return the ranks of the nodes that have submitted the event, which the caller must not modify
     */
    @NonNull
    BitSet submitters() {
        return submitters;
    }

    /**
     * Gets the time the earliest copy that is on its way to a node arrives.
     *
     * @param receiver the rank of the node
     * @return the arrival time, or {@link #NOT_SENT} if no copy is on its way
     */
    long earliestArrival(final int receiver) {
        return earliestArrivals[receiver];
    }

    /**
     * Gets the node that sent the earliest copy that is on its way to a node.
     *
     * @param receiver the rank of the node
     * @return the rank of the sender, or {@code -1} if no copy is on its way
     */
    int earliestSender(final int receiver) {
        return earliestSenders[receiver];
    }

    /**
     * Records that a copy, which arrives earlier than any other, is on its way to a node.
     *
     * @param receiver the rank of the node
     * @param sender   the rank of the node that sends the copy
     * @param arrival  the time the copy arrives
     */
    void setEarliest(final int receiver, final int sender, final long arrival) {
        earliestArrivals[receiver] = arrival;
        earliestSenders[receiver] = sender;
    }

    /**
     * Forgets the copies that are on their way to a node, because they will not arrive.
     *
     * @param receiver the rank of the node
     */
    void resetEarliest(final int receiver) {
        earliestArrivals[receiver] = NOT_SENT;
        earliestSenders[receiver] = -1;
    }

    /**
     * Records that the event is added to the event log.
     *
     * @return {@code true} if the event was not in the log yet
     */
    boolean markLogged() {
        if (logged) {
            return false;
        }
        logged = true;
        return true;
    }
}
