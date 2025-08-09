// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.internal;

import static org.assertj.core.api.Assertions.fail;
import static org.hiero.consensus.model.status.PlatformStatus.ACTIVE;
import static org.hiero.consensus.model.status.PlatformStatus.FREEZE_COMPLETE;

import com.hedera.hapi.node.base.SemanticVersion;
import com.swirlds.common.utility.Threshold;
import com.swirlds.platform.gossip.shadowgraph.SyncFallenBehindStatus;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.otter.fixtures.AsyncNetworkActions;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.TimeManager;
import org.hiero.otter.fixtures.TransactionGenerator;
import org.hiero.otter.fixtures.internal.result.MultipleNodeConsensusResultsImpl;
import org.hiero.otter.fixtures.internal.result.MultipleNodeLogResultsImpl;
import org.hiero.otter.fixtures.internal.result.MultipleNodeMarkerFileResultsImpl;
import org.hiero.otter.fixtures.internal.result.MultipleNodePcesResultsImpl;
import org.hiero.otter.fixtures.internal.result.MultipleNodePlatformStatusResultsImpl;
import org.hiero.otter.fixtures.internal.result.MultipleNodeReconnectResultsImpl;
import org.hiero.otter.fixtures.result.MultipleNodeConsensusResults;
import org.hiero.otter.fixtures.result.MultipleNodeLogResults;
import org.hiero.otter.fixtures.result.MultipleNodeMarkerFileResults;
import org.hiero.otter.fixtures.result.MultipleNodePcesResults;
import org.hiero.otter.fixtures.result.MultipleNodePlatformStatusResults;
import org.hiero.otter.fixtures.result.MultipleNodeReconnectResults;
import org.hiero.otter.fixtures.result.SingleNodeConsensusResult;
import org.hiero.otter.fixtures.result.SingleNodeLogResult;
import org.hiero.otter.fixtures.result.SingleNodeMarkerFileResult;
import org.hiero.otter.fixtures.result.SingleNodePcesResult;
import org.hiero.otter.fixtures.result.SingleNodePlatformStatusResult;
import org.hiero.otter.fixtures.result.SingleNodeReconnectResult;

/**
 * An abstract base class for a network implementation that provides common functionality shared by the different
 * environments.
 */
public abstract class AbstractNetwork implements Network {

    private static final Logger log = LogManager.getLogger();

    private static final Duration FREEZE_DELAY = Duration.ofSeconds(10);

    /**
     * The state of the network.
     */
    protected enum State {
        INIT,
        RUNNING,
        SHUTDOWN
    }

    protected State state = State.INIT;

    private final AsyncNetworkActions defaultStartAction;
    private final AsyncNetworkActions defaultFreezeAction;
    private final AsyncNetworkActions defaultShutdownAction;

    /**
     * Constructs an instance of {@link AbstractNetwork} with the specified default timeouts for start, freeze, and
     * shutdown actions.
     *
     * @param defaultStartTimeout the default timeout for starting the network
     * @param defaultFreezeTimeout the default timeout for freezing the network
     * @param defaultShutdownTimeout the default timeout for shutting down the network
     */
    protected AbstractNetwork(
            @NonNull final Duration defaultStartTimeout,
            @NonNull final Duration defaultFreezeTimeout,
            @NonNull final Duration defaultShutdownTimeout) {
        this.defaultStartAction = withTimeout(defaultStartTimeout);
        this.defaultFreezeAction = withTimeout(defaultFreezeTimeout);
        this.defaultShutdownAction = withTimeout(defaultShutdownTimeout);
    }

    /**
     * Returns the time manager for this network.
     *
     * @return the {@link TimeManager} instance
     */
    @NonNull
    protected abstract TimeManager timeManager();

    /**
     * The factory for creating freeze transactions.
     *
     * @param freezeTime the freeze time for the transaction
     * @return the byte array representing the freeze transaction
     */
    @NonNull
    protected abstract byte[] createFreezeTransaction(@NonNull final Instant freezeTime);

    /**
     * The {@link TransactionGenerator} for this network.
     *
     * @return the {@link TransactionGenerator} instance
     */
    @NonNull
    protected abstract TransactionGenerator transactionGenerator();

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public AsyncNetworkActions withTimeout(@NonNull final Duration timeout) {
        return new AsyncNetworkActionsImpl(timeout);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void start() {
        defaultStartAction.start();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void freeze() {
        defaultFreezeAction.freeze();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long totalWeight() {
        return nodes().stream().mapToLong(Node::weight).sum();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Network withConfigValue(@NonNull final String key, @NonNull final String value) {
        nodes().forEach(node -> node.configuration().set(key, value));
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Network withConfigValue(@NonNull final String key, final int value) {
        nodes().forEach(node -> node.configuration().set(key, value));
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Network withConfigValue(@NonNull final String key, final long value) {
        nodes().forEach(node -> node.configuration().set(key, value));
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Network withConfigValue(@NonNull final String key, @NonNull final Path value) {
        nodes().forEach(node -> node.configuration().set(key, value));
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Network withConfigValue(@NonNull final String key, final boolean value) {
        nodes().forEach(node -> node.configuration().set(key, value));
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void shutdown() {
        defaultShutdownAction.shutdown();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void version(@NonNull final SemanticVersion version) {
        nodes().forEach(node -> node.version(version));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void bumpConfigVersion() {
        nodes().forEach(Node::bumpConfigVersion);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public MultipleNodeConsensusResults newConsensusResults() {
        final List<SingleNodeConsensusResult> results =
                nodes().stream().map(Node::newConsensusResult).toList();
        return new MultipleNodeConsensusResultsImpl(results);
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public MultipleNodeLogResults newLogResults() {
        final List<SingleNodeLogResult> results =
                nodes().stream().map(Node::newLogResult).toList();

        return new MultipleNodeLogResultsImpl(results);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public MultipleNodePlatformStatusResults newPlatformStatusResults() {
        final List<SingleNodePlatformStatusResult> statusProgressions =
                nodes().stream().map(Node::newPlatformStatusResult).toList();
        return new MultipleNodePlatformStatusResultsImpl(statusProgressions);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public MultipleNodeReconnectResults newReconnectResults() {
        final List<SingleNodeReconnectResult> reconnectResults =
                nodes().stream().map(Node::newReconnectResult).toList();
        return new MultipleNodeReconnectResultsImpl(reconnectResults);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public MultipleNodePcesResults newPcesResults() {
        final List<SingleNodePcesResult> results =
                nodes().stream().map(Node::newPcesResult).toList();
        return new MultipleNodePcesResultsImpl(results);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public MultipleNodeMarkerFileResults newMarkerFileResults() {
        final List<SingleNodeMarkerFileResult> results =
                nodes().stream().map(Node::newMarkerFileResult).toList();
        return new MultipleNodeMarkerFileResultsImpl(results);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean nodeIsBehindByNodeWeight(@NonNull final Node maybeBehindNode) {
        final Set<Node> otherNodes = nodes().stream()
                .filter(n -> !n.selfId().equals(maybeBehindNode.selfId()))
                .collect(Collectors.toSet());

        // For simplicity, consider the node that we are checking as "behind" to be the "self" node.
        final EventWindow selfEventWindow = maybeBehindNode.newConsensusResult().getLatestEventWindow();

        long weightOfAheadNodes = 0;
        for (final Node maybeAheadNode : otherNodes) {
            final EventWindow peerEventWindow =
                    maybeAheadNode.newConsensusResult().getLatestEventWindow();

            // If any peer in the required list says the "self" node is not behind, the node is not behind.
            if (SyncFallenBehindStatus.getStatus(selfEventWindow, peerEventWindow)
                    != SyncFallenBehindStatus.SELF_FALLEN_BEHIND) {
                weightOfAheadNodes += maybeAheadNode.weight();
            }
        }
        return Threshold.STRONG_MINORITY.isSatisfiedBy(weightOfAheadNodes, totalWeight());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean nodeIsBehindByNodeCount(@NonNull final Node maybeBehindNode, final double fraction) {
        final Set<Node> otherNodes = nodes().stream()
                .filter(n -> !n.selfId().equals(maybeBehindNode.selfId()))
                .collect(Collectors.toSet());

        // For simplicity, consider the node that we are checking as "behind" to be the "self" node.
        final EventWindow selfEventWindow = maybeBehindNode.newConsensusResult().getLatestEventWindow();

        int numNodesAhead = 0;
        for (final Node maybeAheadNode : otherNodes) {
            final EventWindow peerEventWindow =
                    maybeAheadNode.newConsensusResult().getLatestEventWindow();

            // If any peer in the required list says the "self" node is behind, it is ahead so add it to the count
            if (SyncFallenBehindStatus.getStatus(selfEventWindow, peerEventWindow)
                    == SyncFallenBehindStatus.SELF_FALLEN_BEHIND) {
                numNodesAhead++;
            }
        }
        return (numNodesAhead / (1.0 * otherNodes.size())) >= fraction;
    }

    /**
     * Creates a {@link BooleanSupplier} that returns {@code true} if all nodes are in the given
     * {@link PlatformStatus}.
     *
     * @param status the status to check
     * @return the {@link BooleanSupplier}
     */
    protected BooleanSupplier allNodesInStatus(@NonNull final PlatformStatus status) {
        return () -> nodes().stream().allMatch(node -> node.platformStatus() == status);
    }

    /**
     * Throws an {@link IllegalStateException} if the network is in the given state.
     *
     * @param expected the state that will cause the exception to be thrown
     * @param message the message to include in the exception
     * @throws IllegalStateException if the network is in the expected state
     */
    protected void throwIfInState(@NonNull final State expected, @NonNull final String message) {
        if (state == expected) {
            throw new IllegalStateException(message);
        }
    }

    /**
     * Default implementation of {@link AsyncNetworkActions}
     */
    protected class AsyncNetworkActionsImpl implements AsyncNetworkActions {

        private final Duration timeout;

        /**
         * Constructs an instance of {@link AsyncNetworkActionsImpl} with the specified timeout.
         *
         * @param timeout the duration to wait for actions to complete
         */
        public AsyncNetworkActionsImpl(@NonNull final Duration timeout) {
            this.timeout = timeout;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void start() {
            throwIfInState(State.RUNNING, "Network is already running.");

            log.info("Starting network...");
            state = State.RUNNING;
            for (final Node node : nodes()) {
                node.start();
            }

            transactionGenerator().start();

            log.debug("Waiting for nodes to become active...");
            if (!timeManager().waitForCondition(allNodesInStatus(ACTIVE), timeout)) {
                fail("Timeout while waiting for nodes to become active.");
            }
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void freeze() {
            throwIfInState(State.INIT, "Network has not been started yet.");
            throwIfInState(State.SHUTDOWN, "Network has been shut down.");

            log.info("Sending freeze transaction...");
            final byte[] freezeTransaction =
                    createFreezeTransaction(timeManager().now().plus(FREEZE_DELAY));
            nodes().stream()
                    .filter(Node::isActive)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("No active node found to send freeze transaction to."))
                    .submitTransaction(freezeTransaction);

            log.debug("Waiting for nodes to freeze...");
            if (!timeManager().waitForCondition(allNodesInStatus(FREEZE_COMPLETE), timeout)) {
                fail("Timeout while waiting for all nodes to freeze.");
            }

            transactionGenerator().stop();
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void shutdown() {
            throwIfInState(State.INIT, "Network has not been started yet.");
            throwIfInState(State.SHUTDOWN, "Network has already been shut down.");

            log.info("Killing nodes immediately...");
            for (final Node node : nodes()) {
                node.killImmediately();
            }

            state = State.SHUTDOWN;

            transactionGenerator().stop();
        }
    }
}
