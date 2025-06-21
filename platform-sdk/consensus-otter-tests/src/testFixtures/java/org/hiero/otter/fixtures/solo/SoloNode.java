// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.solo;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.SemanticVersion;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Duration;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.otter.fixtures.AsyncNodeActions;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.NodeConfiguration;
import org.hiero.otter.fixtures.result.SingleNodeConsensusResult;
import org.hiero.otter.fixtures.result.SingleNodeLogResult;
import org.hiero.otter.fixtures.result.SingleNodePcesResult;
import org.hiero.otter.fixtures.result.SingleNodeStatusProgression;

/**
 * Implementation of {@link Node} for a Solo environment.
 */
public class SoloNode implements Node {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(1);

    private final NodeId selfId;

    private final AsyncNodeActions defaultAsyncAction = withTimeout(DEFAULT_TIMEOUT);

    private SemanticVersion version;
    private SoloNodeConfiguration configuration;

    @Nullable
    private PlatformStatus status;

    /**
     * Constructor for the {@link SoloNode} class.
     *
     * @param selfId the unique identifier for this node
     */
    public SoloNode(@NonNull final NodeId selfId) {
        this.selfId = requireNonNull(selfId);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void killImmediately() throws InterruptedException {
        defaultAsyncAction.killImmediately();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void start() throws InterruptedException {
        defaultAsyncAction.start();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public AsyncNodeActions withTimeout(@NonNull final Duration timeout) {
        return new SoloAsyncNodeActions(timeout);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void submitTransaction(@NonNull final byte[] transaction) {
        throw new UnsupportedOperationException("Not implemented yet!");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public NodeConfiguration getConfiguration() {
        return configuration;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public NodeId getSelfId() {
        return selfId;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Nullable
    public PlatformStatus platformStatus() {
        return status;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public SemanticVersion getVersion() {
        return version;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setVersion(@NonNull final SemanticVersion version) {
        throw new UnsupportedOperationException("Not implemented yet!");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void bumpConfigVersion() {
        throw new UnsupportedOperationException("Not implemented yet!");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public SingleNodeConsensusResult getConsensusResult() {
        throw new UnsupportedOperationException("Not implemented yet!");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public SingleNodeLogResult getLogResult() {
        throw new UnsupportedOperationException("Not implemented yet!");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public SingleNodeStatusProgression getStatusProgression() {
        throw new UnsupportedOperationException("Not implemented yet!");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public SingleNodePcesResult getPcesResult() {
        throw new UnsupportedOperationException("Not implemented yet!");
    }

    /**
     * Shuts down the node and cleans up resources. Once this method is called, the node cannot be started again. This
     * method is idempotent and can be called multiple times without any side effects.
     *
     * @throws InterruptedException if the thread is interrupted while the node is being destroyed
     */
    void destroy() throws InterruptedException {
        throw new UnsupportedOperationException("Not implemented yet!");
    }

    /**
     * Solo-specific implementation of {@link AsyncNodeActions}.
     */
    private class SoloAsyncNodeActions implements AsyncNodeActions {

        private final Duration timeout;

        /**
         * Constructor for the {@link SoloAsyncNodeActions} class.
         *
         * @param timeout the duration to wait for actions to complete
         */
        public SoloAsyncNodeActions(@NonNull final Duration timeout) {
            this.timeout = timeout;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void start() throws InterruptedException {
            throw new UnsupportedOperationException("Not implemented yet!");
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void killImmediately() throws InterruptedException {
            throw new UnsupportedOperationException("Not implemented yet!");
        }
    }
}
