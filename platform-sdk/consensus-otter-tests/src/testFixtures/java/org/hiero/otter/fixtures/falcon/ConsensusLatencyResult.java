// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.falcon;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.Collections;
import java.util.Formatter;
import java.util.LinkedHashMap;
import java.util.Map;
import org.hiero.consensus.model.node.NodeId;

/**
 * The creation-to-consensus latency (C2C) and the event throughput of a Falcon network, measured in simulated time.
 *
 * <p>The C2C of an event, as observed by a node, is the time between the creation of the event and the moment that
 * node's consensus engine placed the event in a consensus round. Every node observes every event, so the averages are
 * taken over all (node, event) pairs.
 *
 * @param nodes the number of nodes in the network
 * @param averageC2C the average C2C over all nodes and all events that reached consensus
 * @param maxC2C the maximum C2C observed by any node for any event
 * @param eventsPerSec the number of events created by the whole network per second of simulated time
 * @param bytesPerSec the number of bytes of events created by the whole network per second of simulated time
 * @param averageC2CPerNode the average C2C observed by each individual node, in node registration order
 */
public record ConsensusLatencyResult(
        int nodes,
        @NonNull Duration averageC2C,
        @NonNull Duration maxC2C,
        long eventsPerSec,
        long bytesPerSec,
        @NonNull Map<NodeId, Duration> averageC2CPerNode) {

    /**
     * Creates a new {@link ConsensusLatencyResult}.
     *
     * @throws NullPointerException if any of the non-primitive arguments is {@code null}
     */
    public ConsensusLatencyResult {
        requireNonNull(averageC2C);
        requireNonNull(maxC2C);
        averageC2CPerNode = Collections.unmodifiableMap(new LinkedHashMap<>(requireNonNull(averageC2CPerNode)));
    }

    /**
     * Returns a multi-line summary of the result. The per-node averages are omitted to keep the output compact.
     *
     * @return a human-readable summary
     */
    @Override
    @NonNull
    public String toString() {
        final StringBuilder sb = new StringBuilder();
        final Formatter fmt = new Formatter(sb);
        fmt.format("Num nodes:    %d%n", nodes);
        fmt.format("Avg C2C:      %s%n", averageC2C);
        fmt.format("Max C2C:      %s%n", maxC2C);
        fmt.format("Ev/sec:       %,d%n", eventsPerSec);
        fmt.format("Bytes/sec:    %,d%n", bytesPerSec);
        return sb.toString();
    }
}
