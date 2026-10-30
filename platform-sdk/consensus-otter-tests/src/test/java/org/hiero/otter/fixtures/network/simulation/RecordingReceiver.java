// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.network.simulation;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;

/**
 * An {@link EventReceiver} that records what it is given and can be told to stop accepting.
 */
final class RecordingReceiver implements EventReceiver {

    /** A peer's event window, paired with the peer it belongs to. */
    record PeerEventWindow(@NonNull NodeId sender, @NonNull EventWindow eventWindow) {}

    private final NodeId nodeId;

    /** The events received, in the order they were delivered. */
    final List<PlatformEvent> receivedEvents = new ArrayList<>();

    /** The event windows received, in the order they were delivered. */
    final List<PeerEventWindow> receivedEventWindows = new ArrayList<>();

    /** Whether to accept what is delivered. A node that is not running refuses everything. */
    boolean accepting = true;

    /** Senders whose events are refused while everyone else's are accepted. */
    final Set<NodeId> refusedSenders = new HashSet<>();

    /** How many times each event was offered, whether or not it was accepted. */
    int receiveEventCalls;

    RecordingReceiver(@NonNull final NodeId nodeId) {
        this.nodeId = nodeId;
    }

    @Override
    public NodeId getNodeId() {
        return nodeId;
    }

    @Override
    public boolean receiveEvent(@NonNull final PlatformEvent event) {
        receiveEventCalls++;
        if (!accepting || refusedSenders.contains(event.getSenderId())) {
            return false;
        }
        receivedEvents.add(event);
        return true;
    }

    @Override
    public boolean receiveEventWindow(@NonNull final NodeId sender, @NonNull final EventWindow eventWindow) {
        if (!accepting) {
            return false;
        }
        receivedEventWindows.add(new PeerEventWindow(sender, eventWindow));
        return true;
    }
}
