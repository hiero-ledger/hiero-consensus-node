// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.network;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.assertj.core.data.Percentage;

/**
 * Configuration for a mesh network topology where all nodes are fully connected.
 *
 * <p>Users can configure the jitter, and bandwidth characteristics that apply to all
 * connections in the mesh topology. Additionally, specific latencies between each pair of the nodes can be
 * explicitly specified.
 */
public record PredefinedPingTopologyConfiguration(
        int[][] pingMatrix,
        @NonNull Percentage jitter,
        @NonNull BandwidthLimit bandwidth) implements TopologyConfiguration {}
