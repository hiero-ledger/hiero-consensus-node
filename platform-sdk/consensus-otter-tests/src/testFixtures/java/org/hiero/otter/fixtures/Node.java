// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.otter.fixtures.result.SingleNodeConsensusResult;
import org.hiero.otter.fixtures.result.SingleNodeLogResult;
import org.hiero.otter.fixtures.result.SingleNodePcesResult;
import org.hiero.otter.fixtures.result.SingleNodeStatusProgression;

/**
 * Interface representing a node in the network.
 *
 * <p>This interface provides methods to control the state of the node, such as killing and reviving it.
 */
public interface Node {

    /**
     * Kill the node without prior cleanup.
     *
     * <p>This method simulates a sudden failure of the node. No attempt to finish ongoing work,
     * preserve the current state, or any other similar operation is made. To simulate a graceful
     * shutdown, use {@link #shutdownGracefully(Duration)} instead.
     *
     *
     * @param timeout the duration to wait before considering the kill operation as failed
     */
    void failUnexpectedly(@NonNull Duration timeout) throws InterruptedException;

    /**
     * Shutdown the node gracefully.
     *
     * <p>This method simulates a graceful shutdown of the node. It allows the node to finish any
     * ongoing work, preserve the current state, and perform any other necessary cleanup operations
     * before shutting down. If the simulation of a sudden failure is desired, use
     * {@link #failUnexpectedly(Duration)} instead.
     */
    void shutdownGracefully(@NonNull Duration timeout) throws InterruptedException;

    /**
     * Revive the node.
     *
     * @param timeout the duration to wait before considering the revive operation as failed
     */
    void revive(@NonNull Duration timeout) throws InterruptedException;

    /**
     * Submit a transaction to the node.
     *
     * @param transaction the transaction to submit
     */
    void submitTransaction(@NonNull byte[] transaction);

    /**
     * Gets the configuration of the node. The returned object can be used to evaluate the current
     * configuration, but also for modifications.
     *
     * @return the configuration of the node
     */
    @NonNull
    NodeConfiguration getConfiguration();

    /**
     * Gets the self id of the node. This value can be used to identify a node.
     *
     * @return the self id
     */
    @NonNull
    NodeId getSelfId();

    /**
     * Gets the consensus rounds of the node.
     *
     * @return the consensus rounds of the node
     */
    @NonNull
    SingleNodeConsensusResult getConsensusResult();

    /**
     * Gets the log results of this node.
     *
     * @return the log results of this node
     */
    @NonNull
    SingleNodeLogResult getLogResult();

    /**
     * Gets the status progression of the node.
     *
     * @return the status progression of the node
     */
    @NonNull
    SingleNodeStatusProgression getStatusProgression();

    /**
     * Gets the results related to PCES files.
     *
     * @return the PCES files created by the node
     */
    @NonNull
    SingleNodePcesResult getPcesResult();
}
