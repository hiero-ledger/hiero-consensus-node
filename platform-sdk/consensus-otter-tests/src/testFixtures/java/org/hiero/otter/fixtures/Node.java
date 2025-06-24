// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures;

import com.hedera.hapi.node.base.SemanticVersion;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Duration;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.otter.fixtures.result.SingleNodeConsensusResult;
import org.hiero.otter.fixtures.result.SingleNodeLogResult;
import org.hiero.otter.fixtures.result.SingleNodePcesResult;
import org.hiero.otter.fixtures.result.SingleNodeStatusProgression;

/**
 * Interface representing a node in the network.
 *
 * <p>This interface provides methods to control the state of the node, such as killing and reviving it.
 */
@SuppressWarnings("unused")
public interface Node {

    /**
     * The default software version of the node when no specific version is set for the node.
     */
    SemanticVersion DEFAULT_VERSION = SemanticVersion.newBuilder().major(1).build();

    /**
     * Kill the node without prior cleanup.
     *
     * <p>This method simulates a sudden failure of the node. No attempt to finish ongoing work,
     * preserve the current state, or any other similar operation is made.
     *
     * <p>The method will wait for a environment-specific timeout before throwing an exception if the nodes cannot be
     * killed. The default can be overridden by calling {@link #withTimeout(Duration)}.
     *
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    void killImmediately() throws InterruptedException;

    /**
     * Start the node.
     *
     * <p>The method will wait for a environment-specific timeout before throwing an exception if the node cannot be
     * started. The default can be overridden by calling {@link #withTimeout(Duration)}.
     *
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    void start() throws InterruptedException;

    /**
     * Allows to override the default timeout for node operations.
     *
     * @param timeout the duration to wait before considering the operation as failed
     * @return an instance of {@link AsyncNodeActions} that can be used to perform node actions
     */
    AsyncNodeActions withTimeout(@NonNull Duration timeout);

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
     * Returns the status of the platform while the node is running or {@code null} if not.
     *
     * @return the status of the platform
     */
    @Nullable
    PlatformStatus platformStatus();

    /**
     * Checks if the node's {@link PlatformStatus} is {@link PlatformStatus#ACTIVE}.
     *
     * @return {@code true} if the node is active, {@code false} otherwise
     */
    default boolean isActive() {
        return platformStatus() == PlatformStatus.ACTIVE;
    }

    /**
     * Gets the software version of the node.
     *
     * @return the software version of the node
     */
    @NonNull
    SemanticVersion getVersion();

    /**
     * Sets the software version of the node.
     *
     * <p>If no version is set, {@link #DEFAULT_VERSION} will be used.
     *
     * <p>Please note that the new version will become effective only after the node is (re-)started.
     *
     * @param version the software version to set for the node
     */
    void setVersion(@NonNull SemanticVersion version);

    /**
     * This method updates the version to trigger a "config only upgrade" on the next restart.
     *
     * <p>Please note that the new version will become effective only after the node is (re-)started.
     */
    void bumpConfigVersion();

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
