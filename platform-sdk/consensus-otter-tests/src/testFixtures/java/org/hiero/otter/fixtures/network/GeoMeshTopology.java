// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.network;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import org.hiero.otter.fixtures.InstrumentedNode;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.network.utils.GeographicLatencyConfiguration;

/**
 * Interface for a mesh network topology that simulates realistic latency and jitter based on geographic distribution.
 */
@SuppressWarnings("unused")
public interface GeoMeshTopology extends MeshTopology {

    /**
     * Adds a single node to the network in the specified geographic location.
     *
     * @param continent the continent for the new node
     * @param region the region within the continent for the new node
     * @return the created node
     * @throws NullPointerException if {@code continent} or {@code region} is {@code null}
     */
    @NonNull
    default Node addNode(@NonNull final String continent, @NonNull final String region) {
        return addNodes(1, continent, region).getFirst();
    }

    /**
     * Adds multiple nodes to the network in the specified geographic location.
     *
     * @param count the number of nodes to add
     * @param continent the continent for the new nodes
     * @param region the region within the continent for the new nodes
     * @return list of created nodes
     * @throws NullPointerException if {@code continent} or {@code region} is {@code null}
     */
    @NonNull
    List<Node> addNodes(int count, @NonNull String continent, @NonNull String region);

    /**
     * Add an instrumented node to the topology.
     *
     * <p>This method is used to add a node that has additional instrumentation for testing purposes.
     * For example, it can exhibit malicious or erroneous behavior.
     *
     * @param continent the continent for the new node
     * @param region the region within the continent for the new node
     * @return the added instrumented node
     * @throws NullPointerException if {@code continent} or {@code region} is {@code null}
     */
    @NonNull
    InstrumentedNode addInstrumentedNode(@NonNull final String continent, @NonNull final String region);

    /**
     * Gets the continent of the specified node.
     *
     * @param node the node to query
     * @return the continent of the node
     * @throws NullPointerException if {@code node} is {@code null}
     * @throws IllegalArgumentException if the node is not part of this topology
     */
    @NonNull
    String getContinent(@NonNull Node node);

    /**
     * Gets the region of the specified node.
     *
     * @param node the node to query
     * @return the region of the node
     * @throws NullPointerException if {@code node} is {@code null}
     * @throws IllegalArgumentException if the node is not part of this topology
     */
    @NonNull
    String getRegion(@NonNull Node node);

    /**
     * Sets realistic latency and jitter based on geographic distribution. Applies different latency characteristics for
     * same-region, same-continent, and intercontinental connections based on the provided configuration.
     *
     * <p>If no {@link GeographicLatencyConfiguration} is set, the default
     * {@link GeographicLatencyConfiguration#DEFAULT} is used.
     *
     * @param configuration the geographic latency configuration to apply
     * @throws NullPointerException if {@code config} is {@code null}
     */
    void setGeographicLatencyConfiguration(@NonNull GeographicLatencyConfiguration configuration);
}
