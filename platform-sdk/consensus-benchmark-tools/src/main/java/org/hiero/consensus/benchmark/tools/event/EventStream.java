// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.benchmark.tools.event;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Map;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.roster.RosterWrapper;

/**
 * The output of {@link EventStreamGenerator}: independent events and the network that created them.
 *
 * <p>The events are shared. A benchmark whose code under test changes an event works on copies
 * ({@link PlatformEvent#copyGossipedData()}).
 *
 * @param roster the creators of the events, with their signing certificates if the events are signed
 * @param keys   the keys of every node in the roster, or an empty map if the events are not signed
 * @param events the events, in the order they were generated
 */
public record EventStream(
        @NonNull RosterWrapper roster,
        @NonNull Map<NodeId, KeysAndCerts> keys,
        @NonNull List<PlatformEvent> events) {

    public EventStream {
        requireNonNull(roster);
        requireNonNull(keys);
        requireNonNull(events);
    }
}
