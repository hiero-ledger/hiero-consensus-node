// SPDX-License-Identifier: Apache-2.0
package org.hiero.sloth.fixtures;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.roster.Roster;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.List;
import org.hiero.consensus.model.quiescence.QuiescenceCommand;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.consensus.test.fixtures.WeightGenerator;
import org.hiero.consensus.test.fixtures.WeightGenerators;
import org.hiero.sloth.fixtures.network.Topology;
import org.hiero.sloth.fixtures.network.Topology.ConnectionState;
import org.hiero.sloth.fixtures.network.transactions.SlothTransaction;
import org.hiero.sloth.fixtures.result.MultipleNodeLogResults;
import org.hiero.sloth.fixtures.result.MultipleNodePlatformStatusResults;

/**
 * Interface representing a network of nodes.
 *
 * <p>This interface provides methods to add and remove nodes, start the network, and add instrumented nodes.
 */
public interface Network extends Configurable<Network> {

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
    default List<Node> nodes() {
        return topology().nodes();
    }

    /**
     * Returns the {@link Topology} of the network.
     *
     * @return the topology of the network
     */
    @NonNull
    Topology topology();

    /**
     * Adds a single node to the network.
     *
     * <p>How the node is connected to existing nodes and its latency, jitter, and bandwidth depend on the current
     * topology.
     *
     * @return the created node
     */
    @NonNull
    default Node addNode() {
        return topology().addNode();
    }

    /**
     * Adds multiple nodes to the network.
     *
     * <p>How the node is connected to existing nodes and its latency, jitter, and bandwidth depend on the current
     * topology.
     *
     * @param count the number of nodes to add
     * @return list of created nodes
     */
    @NonNull
    default List<Node> addNodes(final int count) {
        return topology().addNodes(count);
    }

    /**
     * Sets the weight generator for the network. The weight generator is used to assign weights to nodes if no nodes in
     * the network have their weight set explicitly via {@link Node#weight(long)}.
     *
     * <p>If no weight generator is set, the default {@link WeightGenerators#GAUSSIAN} is used.
     *
     * <p>Note that the weight generator can only be set before the network is started.
     *
     * @param weightGenerator the weight generator to use
     * @throws IllegalStateException if nodes have already been added to the network
     */
    void weightGenerator(@NonNull WeightGenerator weightGenerator);

    /**
     * Sets the weight of each node in the network to the specified value. Calling this method results in balanced
     * weight distribution.
     *
     * @param weight the weight to assign to each node. Must be positive.
     */
    void nodeWeight(long weight);

    /**
     * Gets the total weight of the network. Always positive.
     *
     * @return the network weight
     */
    default long totalWeight() {
        return nodes().stream().mapToLong(Node::weight).sum();
    }

    /**
     * Gets the roster of the network. This method can only be called after the network has been started, because the
     * roster is created during startup.
     *
     * @return the roster of the network
     * @throws IllegalStateException if the network has not been started yet
     */
    @NonNull
    Roster roster();

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
     * Sets the quiescence command of the network.
     *
     * <p>The default command is {@link QuiescenceCommand#DONT_QUIESCE}.
     *
     * @param command the new quiescence command
     */
    void sendQuiescenceCommand(@NonNull QuiescenceCommand command);

    /**
     * Returns the current connection state between two nodes in the network after all modifications (partitions,
     * latencies, bandwidth limits, etc.) have been applied.
     *
     * @param sender   the source node
     * @param receiver the destination node
     * @return the current {@link ConnectionState}
     */
    ConnectionState connectionState(@NonNull Node sender, @NonNull Node receiver);

    /**
     * Submits a single transaction to the first active node found in the network.
     *
     * @param transaction the transaction to submit
     */
    default void submitTransaction(@NonNull final SlothTransaction transaction) {
        submitTransactions(List.of(transaction));
    }

    /**
     * Submits the transactions to the first active node found in the network.
     *
     * @param transactions the transactions to submit
     */
    void submitTransactions(@NonNull List<SlothTransaction> transactions);

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
     * Checks if all nodes in the network are in the specified {@link PlatformStatus}.
     *
     * @param status the status to check against
     * @return {@code true} if all nodes are in the specified status, {@code false} otherwise
     */
    default boolean allNodesInStatus(@NonNull final PlatformStatus status) {
        return nodes().stream().allMatch(node -> node.platformStatus() == status);
    }

    /**
     * Checks if all nodes in the network are {@link PlatformStatus#ACTIVE}.
     *
     * @return {@code true} if all nodes are active, {@code false} otherwise
     */
    default boolean allNodesAreActive() {
        return allNodesInStatus(PlatformStatus.ACTIVE);
    }
}
