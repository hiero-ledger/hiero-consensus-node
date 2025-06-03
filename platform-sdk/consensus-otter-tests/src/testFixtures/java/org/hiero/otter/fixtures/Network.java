// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.List;
import org.hiero.otter.fixtures.result.MultipleNodeConsensusResults;
import org.hiero.otter.fixtures.result.MultipleNodeLogResults;
import org.hiero.otter.fixtures.result.MultipleNodePcesResults;
import org.hiero.otter.fixtures.result.MultipleNodeStatusProgression;

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
     * @param timeout the duration to wait before considering the start operation as failed
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    void start(@NonNull Duration timeout) throws InterruptedException;

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
     * @return a list of nodes in the network
     */
    @NonNull
    List<Node> getNodes();

    /**
     * Prepares the network for an upgrade. All required preparations steps are executed and the network
     * is shutdown. Once shutdown, it is possible to change the configuration etc. before resuming the
     * network with {@link #resume(Duration)}.
     *
     * @param timeout the duration to wait before considering the preparation operation as failed
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    void prepareUpgrade(@NonNull Duration timeout) throws InterruptedException;

    /**
     * Resumes the network after it has previously been paused, e.g. to prepare for an upgrade.
     *
     * @param duration the duration to wait before considering the resume operation as failed
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    void resume(@NonNull Duration duration) throws InterruptedException;

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
     * Gets the status progression of all nodes in the network.
     *
     * @return the status progression of the nodes
     */
    @NonNull
    MultipleNodeStatusProgression getStatusProgression();

    /**
     * Gets the results related to PCES files.
     *
     * @return the PCES files created by the nodes
     */
    @NonNull
    MultipleNodePcesResults getPcesResults();
}
