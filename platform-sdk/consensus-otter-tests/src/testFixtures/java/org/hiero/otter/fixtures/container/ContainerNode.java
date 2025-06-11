// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.container;

import static java.util.Objects.requireNonNull;
import static org.hiero.otter.fixtures.internal.AbstractNode.LifeCycle.DESTROYED;
import static org.hiero.otter.fixtures.internal.AbstractNode.LifeCycle.RUNNING;

import com.hedera.hapi.node.base.ServiceEndpoint;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.function.Consumer;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.otter.fixtures.AsyncNodeActions;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.NodeConfiguration;
import org.hiero.otter.fixtures.internal.AbstractNode;
import org.hiero.otter.fixtures.result.SingleNodeConsensusResult;
import org.hiero.otter.fixtures.result.SingleNodeLogResult;
import org.hiero.otter.fixtures.result.SingleNodePcesResult;
import org.hiero.otter.fixtures.result.SingleNodeStatusProgression;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.output.OutputFrame;
import org.testcontainers.containers.output.OutputFrame.OutputType;
import org.testcontainers.utility.DockerImageName;

/**
 * Implementation of {@link Node} for a container environment.
 */
public class ContainerNode extends AbstractNode implements Node {

    private static final Logger log = LogManager.getLogger();

    private static final int GOSSIP_PORT = 5777;
    private static final DockerImageName DOCKER_IMAGE_NAME = DockerImageName.parse("hello-world:latest");
    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(1);

    private final GenericContainer<?> container;
    private final RosterEntry rosterEntry;
    private final AsyncNodeActions defaultAsyncAction = withTimeout(DEFAULT_TIMEOUT);

    private ContainerNodeConfiguration nodeConfiguration;
    private Roster roster = Roster.DEFAULT;

    /**
     * Constructor for the {@link ContainerNode} class.
     *
     * @param selfId the unique identifier for this node
     * @param network the network this node is part of
     */
    public ContainerNode(@NonNull final NodeId selfId, @NonNull final Network network) {
        super(selfId);

        final Consumer<OutputFrame> logConsumer = frame -> log.log(frame.getType() == OutputType.STDERR? Level.ERROR : Level.INFO, frame.getUtf8String());
        final PlatformStatusLogParser platformStatusLogParser = new PlatformStatusLogParser(newValue -> platformStatus = newValue);
        this.container = new GenericContainer<>(DOCKER_IMAGE_NAME)
                .withNetwork(network)
                .withLogConsumer(logConsumer.andThen(platformStatusLogParser));
        this.rosterEntry = RosterEntry.newBuilder()
                .nodeId(selfId.id())
                .weight(1L)
                .gossipEndpoint(ServiceEndpoint.newBuilder().port(GOSSIP_PORT).build())
                .build();
    }

    /**
     * Returns the {@link RosterEntry} for this node.
     *
     * @return the roster entry associated with this node
     */
    RosterEntry rosterEntry() {
        return rosterEntry;
    }

    /**
     * Sets the roster for this node.
     *
     * @param roster the new roster used during the next start of the node
     */
    void setRoster(@NonNull final Roster roster) {
        throwIfIn(RUNNING, "Cannot set roster while the node is running");
        throwIfIn(DESTROYED, "Cannot set roster after the node has been destroyed");

        this.roster = requireNonNull(roster);
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
    public void shutdownGracefully() throws InterruptedException {
        defaultAsyncAction.shutdownGracefully();
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
        return new ContainerAsyncNodeActions(timeout);
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
    public NodeConfiguration configuration() {
        return nodeConfiguration;
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
        container.stop();
    }

    /**
     * Container-specific implementation of {@link AsyncNodeActions}.
     */
    private class ContainerAsyncNodeActions implements AsyncNodeActions {

        private final Duration timeout;

        /**
         * Constructor for the {@link ContainerAsyncNodeActions} class.
         *
         * @param timeout the duration to wait for actions to complete
         */
        public ContainerAsyncNodeActions(@NonNull final Duration timeout) {
            this.timeout = timeout;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void start() throws InterruptedException {
            throwIfIn(LifeCycle.RUNNING, "Node has already been started.");
            throwIfIn(LifeCycle.DESTROYED, "Node has already been destroyed.");

            log.info("Starting node {}...", selfId);

            // TODO: Transfer roster and configuration before starting the container

            container.start();

            // TODO: Remove this once the PlatformStatusLogParser can be used
            platformStatus = PlatformStatus.ACTIVE;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void shutdownGracefully() throws InterruptedException {
            throw new UnsupportedOperationException("Not implemented yet!");
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void killImmediately() throws InterruptedException {
            log.info("Killing node {} immediately...", selfId);
            container.stop();
        }
    }
}
