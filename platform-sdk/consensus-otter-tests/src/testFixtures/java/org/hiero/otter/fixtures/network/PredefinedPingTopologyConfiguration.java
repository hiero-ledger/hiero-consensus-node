// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.network;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import org.assertj.core.data.Percentage;

/**
 * Configuration for a mesh network topology where all nodes are fully connected.
 *
 * <p>Users can configure the jitter, and bandwidth characteristics that apply to all
 * connections in the mesh topology. Additionally, specific latencies between each pair of the nodes can be
 * explicitly specified.
 */
public record PredefinedPingTopologyConfiguration(
        @NonNull int[][] pingMatrix,
        @NonNull Percentage jitter,
        @NonNull BandwidthLimit bandwidth) implements TopologyConfiguration {

    /**
     * @param pingMatrix list of latencies in milliseconds; latency from node X to node Y is defined in pingMatrix[X][Y]
     * @param jitter the jitter percentage for connections
     * @param bandwidth the bandwidth limit for connections
     */
    public PredefinedPingTopologyConfiguration {
        Objects.requireNonNull(pingMatrix, "pingMatrix");
        Objects.requireNonNull(jitter, "jitter");
        Objects.requireNonNull(bandwidth, "bandwidth");
    }
}
