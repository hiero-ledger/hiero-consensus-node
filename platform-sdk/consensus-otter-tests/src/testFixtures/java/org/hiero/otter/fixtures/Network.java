// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures;

import com.hedera.hapi.node.base.SemanticVersion;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.List;
import org.hiero.otter.fixtures.result.MultipleNodeConsensusResults;
import org.hiero.otter.fixtures.result.MultipleNodeLogResults;
import org.hiero.otter.fixtures.result.MultipleNodePcesResults;
import org.hiero.otter.fixtures.result.MultipleNodePlatformStatusResults;

/**
 * Interface representing a network of nodes.
 *
 * <p>This interface provides methods to add and remove nodes, start the network, and add instrumented nodes.
 */
public interface Network {

    /**
     * Add regular nodes to the network.
     *
     * @param count the number of nodes to add
     * @return a list of the added nodes
     */
    @NonNull
    List<Node> addNodes(int count);

    /**
     * Start the network with the currently configured setup.
     *
     * <p>The method will wait until all nodes have become {@link org.hiero.consensus.model.status.PlatformStatus#ACTIVE}.
     * It will wait for a environment-specific timeout before throwing an exception if the nodes do not reach the
     * {@code ACTIVE} state. The default can be overridden by calling {@link #withTimeout(Duration)}.
     *
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    void start() throws InterruptedException;

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
    List<Node> getNodes();

    /**
     * Freezes the network.
     *
     * <p>This method sends a freeze transaction to one of the active nodes with a freeze time shortly after the
     * current time. The method returns once all nodes entered the
     * {@link org.hiero.consensus.model.status.PlatformStatus#FREEZE_COMPLETE} state.
     *
     * <p>It will wait for a environment-specific timeout before throwing an exception if the nodes do not reach the
     * {@code FREEZE_COMPLETE} state. The default can be overridden by calling {@link #withTimeout(Duration)}.
     *
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    void freeze() throws InterruptedException;

    /**
     * Shuts down the network. The nodes are killed immediately. No attempt is made to finish any outstanding tasks
     * or preserve any state. Once shutdown, it is possible to change the configuration etc. before resuming the
     * network with {@link #start()}.
     *
     * <p>The method will wait for an environment-specific timeout before throwing an exception if the nodes cannot be
     * killed. The default can be overridden by calling {@link #withTimeout(Duration)}.
     *
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    void shutdown() throws InterruptedException;

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
     * @see Node#setVersion(SemanticVersion)
     *
     * @param version the semantic version to set for the network
     */
    void setVersion(@NonNull SemanticVersion version);

    /**
     * This method updates the version of all nodes in the network to trigger a "config only upgrade" on the next restart.
     *
     * <p>Please note that the new version will become effective only after a node is (re-)started.
     *
     * @see Node#bumpConfigVersion()
     */
    void bumpConfigVersion();

    /**
     * Gets the consensus rounds of all nodes.
     *
     * @return the consensus rounds of the filtered nodes
     */
    @NonNull
    MultipleNodeConsensusResults getConsensusResults();

    /**
     * Gets the log results of all nodes.
     *
     * @return the log results of the nodes
     */
    @NonNull
    MultipleNodeLogResults getLogResults();

    /**
     * Gets the status progression results of all nodes in the network.
     *
     * @return the status progression results of the nodes
     */
    @NonNull
    MultipleNodePlatformStatusResults getPlatformStatusResults();

    /**
     * Gets the results related to PCES files.
     *
     * @return the PCES files created by the nodes
     */
    @NonNull
    MultipleNodePcesResults getPcesResults();
}
