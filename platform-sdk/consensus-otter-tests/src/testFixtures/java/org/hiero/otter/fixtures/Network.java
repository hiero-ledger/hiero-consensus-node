// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures;

import com.hedera.hapi.node.base.SemanticVersion;
import com.swirlds.common.test.fixtures.WeightGenerator;
import com.swirlds.common.test.fixtures.WeightGenerators;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.hiero.otter.fixtures.result.MultipleNodeConsensusResults;
import org.hiero.otter.fixtures.result.MultipleNodeLogResults;
import org.hiero.otter.fixtures.result.MultipleNodeMarkerFileResults;
import org.hiero.otter.fixtures.result.MultipleNodePcesResults;
import org.hiero.otter.fixtures.result.MultipleNodePlatformStatusResults;
import org.hiero.otter.fixtures.result.MultipleNodeReconnectResults;

/**
 * Interface representing a network of nodes.
 *
 * <p>This interface provides methods to add and remove nodes, start the network, and add instrumented nodes.
 */
@SuppressWarnings("unused")
public interface Network {

    /**
     * Add regular nodes to the network.
     *
     * @param count the number of nodes to add
     * @return a list of the added nodes
     */
    @NonNull
    default List<Node> addNodes(final int count) {
        return addNodes(count, WeightGenerators.GAUSSIAN);
    }

    /**
     * Add regular nodes to the network with specific weights.
     *
     * @param count - the number of nodes to add
     * @param weightGenerator - the generator to use for the weights of the nodes
     * @return a list of the added nodes
     */
    @NonNull
    List<Node> addNodes(int count, WeightGenerator weightGenerator);

    /**
     * Start the network with the currently configured setup.
     *
     * <p>The method will wait until all nodes have become
     * {@link org.hiero.consensus.model.status.PlatformStatus#ACTIVE}. It will wait for a environment-specific timeout
     * before throwing an exception if the nodes do not reach the {@code ACTIVE} state. The default can be overridden by
     * calling {@link #withTimeout(Duration)}.
     */
    void start();

    /**
     * Add an instrumented node to the network.
     *
     * <p>This method is used to add a node that has additional instrumentation for testing purposes.
     * For example, it can exhibit malicious or erroneous behavior.
     *
     * @return the added instrumented node
     */
    @NonNull
    InstrumentedNode addInstrumentedNode();

    /**
     * Get the list of nodes in the network.
     *
     * <p>The {@link List} cannot be modified directly. However, if a node is added or removed from the network, the
     * list is automatically updated. That means, if it is necessary to have a constant list, it is recommended to
     * create a copy.
     *
     * @return a list of nodes in the network
     */
    @NonNull
    List<Node> nodes();

    /**
     * Gets the total weight of the network. Always positive.
     *
     * @return the network weight
     */
    long totalWeight();

    /**
     * Updates a single property of the configuration for every node in the network. Can only be invoked when no nodes
     * in the network are running.
     *
     * @param key the key of the property
     * @param value the value of the property
     * @return this {@code Network} instance for method chaining
     */
    Network withConfigValue(@NonNull String key, @NonNull String value);

    /**
     * Updates a single property of the configuration for every node in the network. Can only be invoked when no nodes
     * in the network are running.
     *
     * @param key the key of the property
     * @param value the value of the property
     * @return this {@code Network} instance for method chaining
     */
    Network withConfigValue(@NonNull String key, int value);

    /**
     * Updates a single property of the configuration for every node in the network. Can only be invoked when no nodes
     * in the network are running.
     *
     * @param key the key of the property
     * @param value the value of the property
     * @return this {@code Network} instance for method chaining
     */
    Network withConfigValue(@NonNull String key, long value);

    /**
     * Updates a single property of the configuration for every node in the network. Can only be invoked when no nodes
     * in the network are running.
     *
     * @param key the key of the property
     * @param value the value of the property
     * @return this {@code Network} instance for method chaining
     */
    Network withConfigValue(@NonNull String key, boolean value);

    /**
     * Updates a single property of the configuration for every node in the network. Can only be invoked when no nodes
     * in the network are running.
     *
     * @param key the key of the property
     * @param value the value of the property
     * @return this {@code Network} instance for method chaining
     */
    Network withConfigValue(@NonNull String key, @NonNull Path value);

    /**
     * Freezes the network.
     *
     * <p>This method sends a freeze transaction to one of the active nodes with a freeze time shortly after the
     * current time. The method returns once all nodes entered the
     * {@link org.hiero.consensus.model.status.PlatformStatus#FREEZE_COMPLETE} state.
     *
     * <p>It will wait for a environment-specific timeout before throwing an exception if the nodes do not reach the
     * {@code FREEZE_COMPLETE} state. The default can be overridden by calling {@link #withTimeout(Duration)}.
     */
    void freeze();

    /**
     * Shuts down the network. The nodes are killed immediately. No attempt is made to finish any outstanding tasks or
     * preserve any state. Once shutdown, it is possible to change the configuration etc. before resuming the network
     * with {@link #start()}.
     *
     * <p>The method will wait for an environment-specific timeout before throwing an exception if the nodes cannot be
     * killed. The default can be overridden by calling {@link #withTimeout(Duration)}.
     */
    void shutdown();

    /**
     * Allows to override the default timeout for network operations.
     *
     * @param timeout the duration to wait before considering the operation as failed
     * @return an instance of {@link AsyncNetworkActions} that can be used to perform network actions
     */
    @NonNull
    AsyncNetworkActions withTimeout(@NonNull Duration timeout);

    /**
     * Sets the version of the network.
     *
     * <p>This method sets the version of all nodes currently added to the network. Please note that the new version
     * will become effective only after a node is (re-)started.
     *
     * @param version the semantic version to set for the network
     * @see Node#version(SemanticVersion)
     */
    void version(@NonNull SemanticVersion version);

    /**
     * This method updates the version of all nodes in the network to trigger a "config only upgrade" on the next
     * restart.
     *
     * <p>Please note that the new version will become effective only after a node is (re-)started.
     *
     * @see Node#bumpConfigVersion()
     */
    void bumpConfigVersion();

    /**
     * Creates a new result with all the consensus rounds of all nodes that are currently in the network.
     *
     * @return the consensus rounds of the filtered nodes
     */
    @NonNull
    MultipleNodeConsensusResults newConsensusResults();

    /**
     * Creates a new result with all the log results of all nodes that are currently in the network.
     *
     * @return the log results of the nodes
     */
    @NonNull
    MultipleNodeLogResults newLogResults();

    /**
     * Creates a new result with all the status progression results of all nodes that are currently in the network.
     *
     * @return the status progression results of the nodes
     */
    @NonNull
    MultipleNodePlatformStatusResults newPlatformStatusResults();

    /**
     * Creates a new result with all the PCES file results of all nodes that are currently in the network.
     *
     * @return the PCES files created by the nodes
     */
    @NonNull
    MultipleNodePcesResults newPcesResults();

    /**
     * Creates a new result with all node reconnect results of all nodes that are currently in the network.
     *
     * @return the results of node reconnects
     */
    @NonNull
    MultipleNodeReconnectResults newReconnectResults();

    /**
     * Creates a new result with all marker file results of all nodes that are currently in the network.
     *
     * @return the marker file results of the nodes
     */
    @NonNull
    MultipleNodeMarkerFileResults newMarkerFileResults();

    /**
     * Checks if a node is behind compared to a strong minority of the network. A node is considered behind a peer when
     * its minimum non-ancient round is older than the peer's minimum non-expired round.
     *
     * @param maybeBehindNode the node to check behind status for
     * @return {@code true} if the node is behind by node weight, {@code false} otherwise
     * @see com.swirlds.platform.gossip.shadowgraph.SyncFallenBehindStatus
     */
    boolean nodeIsBehindByNodeWeight(@NonNull Node maybeBehindNode);

    /**
     * Checks if a node is behind compared to a fraction of peers in the network. A node is considered behind a peer
     * when its minimum non-ancient round is older than the peer's minimum non-expired round.
     *
     * @param maybeBehindNode the node to check behind status for
     * @param fraction the fraction of peers to consider for the behind check
     * @return {@code true} if the node is behind by the specified fraction of peers, {@code false} otherwise
     * @see com.swirlds.platform.gossip.shadowgraph.SyncFallenBehindStatus
     */
    boolean nodeIsBehindByNodeCount(@NonNull Node maybeBehindNode, double fraction);
}
