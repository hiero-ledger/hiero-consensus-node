// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.container;

import static org.hiero.otter.fixtures.internal.AbstractNode.LifeCycle.DESTROYED;
import static org.hiero.otter.fixtures.internal.AbstractNode.LifeCycle.RUNNING;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.platform.state.NodeId;
import edu.umd.cs.findbugs.annotations.NonNull;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.otter.fixtures.AsyncNodeActions;
import org.hiero.otter.fixtures.KeysAndCertsConverter;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.NodeConfiguration;
import org.hiero.otter.fixtures.ProtobufConverter;
import org.hiero.otter.fixtures.container.proto.EventMessage;
import org.hiero.otter.fixtures.container.proto.PlatformStatusChange;
import org.hiero.otter.fixtures.container.proto.StartRequest;
import org.hiero.otter.fixtures.container.proto.TestControlGrpc;
import org.hiero.otter.fixtures.container.proto.TestControlGrpc.TestControlStub;
import org.hiero.otter.fixtures.internal.AbstractNode;
import org.hiero.otter.fixtures.internal.result.NodeResultsCollector;
import org.hiero.otter.fixtures.internal.result.SingleNodeLogResultImpl;
import org.hiero.otter.fixtures.logging.StructuredLog;
import org.hiero.otter.fixtures.result.SingleNodeConsensusResult;
import org.hiero.otter.fixtures.result.SingleNodeLogResult;
import org.hiero.otter.fixtures.result.SingleNodePcesResult;
import org.hiero.otter.fixtures.result.SingleNodeStatusProgression;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.output.OutputFrame;
import org.testcontainers.containers.output.OutputFrame.OutputType;
import org.testcontainers.images.builder.ImageFromDockerfile;

/**
 * Implementation of {@link Node} for a container environment.
 */
public class ContainerNode extends AbstractNode implements Node {

    private static final Logger log = LogManager.getLogger();

    public static final int GOSSIP_PORT = 5777;
    private static final int CONTROL_PORT = 8080;
    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(1);

    private final GenericContainer<?> container;
    private final Roster roster;
    private final KeysAndCerts keysAndCerts;
    private final ManagedChannel channel;
    private final AsyncNodeActions defaultAsyncAction = withTimeout(DEFAULT_TIMEOUT);
    private final ContainerNodeConfiguration nodeConfiguration = new ContainerNodeConfiguration();
    private final NodeResultsCollector resultsCollector;
    private final List<StructuredLog> receivedLogs = new CopyOnWriteArrayList<>();

    /**
     * Constructor for the {@link ContainerNode} class.
     *
     * @param selfId       the unique identifier for this node
     * @param roster       the roster of the network
     * @param keysAndCerts the keys for the node
     * @param network      the network this node is part of
     * @param dockerImage  the Docker image to use for this node
     */
    public ContainerNode(
            @NonNull final NodeId selfId,
            @NonNull final Roster roster,
            @NonNull final KeysAndCerts keysAndCerts,
            @NonNull final Network network,
            @NonNull final ImageFromDockerfile dockerImage) {
        super(selfId);
        this.roster = Objects.requireNonNull(roster, "roster must not be null");
        this.keysAndCerts = Objects.requireNonNull(keysAndCerts, "keysAndCerts must not be null");

        this.resultsCollector = new NodeResultsCollector(selfId);

        final String alias = "node-" + selfId.id();

        final Consumer<OutputFrame> logWriter = frame -> {
            final Level level = frame.getType() == OutputType.STDERR ? Level.ERROR : Level.INFO;
            final String message = "%s: %s".formatted(alias, frame.getUtf8StringWithoutLineEnding());
            log.log(level, message);
        };
        final PlatformStatusLogParser platformStatusLogParser =
                new PlatformStatusLogParser(newValue -> platformStatus = newValue);

        //noinspection resource
        this.container = new GenericContainer<>(dockerImage)
                .withNetwork(network)
                .withNetworkAliases(alias)
                .withLogConsumer(logWriter.andThen(platformStatusLogParser))
                .withExposedPorts(8080);
        container.start();
        channel = ManagedChannelBuilder.forAddress(container.getHost(), container.getMappedPort(CONTROL_PORT))
                .usePlaintext()
                .build();
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
    public void start() throws IOException, InterruptedException {
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
    public NodeConfiguration<?> configuration() {
        return nodeConfiguration;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public SingleNodeConsensusResult getConsensusResult() {
        return resultsCollector.getConsensusResult();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public SingleNodeLogResult getLogResult() {
        return new SingleNodeLogResultImpl(selfId, List.copyOf(receivedLogs));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public SingleNodeStatusProgression getStatusProgression() {
        return resultsCollector.getStatusProgression();
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
     * @throws IOException if an I/O error occurs while sending the destroy command
     * @throws InterruptedException if the thread is interrupted while waiting for the command to complete
     */
    void destroy() throws IOException, InterruptedException {
        if (lifeCycle == RUNNING) {
            if (channel != null) {
                channel.shutdownNow();
            }
            container.stop();
        }
        resultsCollector.destroy();
        platformStatus = null;
        lifeCycle = DESTROYED;
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
        public void start() throws IOException, InterruptedException {
            throwIfIn(LifeCycle.RUNNING, "Node has already been started.");
            throwIfIn(LifeCycle.DESTROYED, "Node has already been destroyed.");

            log.info("Starting node {}...", selfId);

            final StartRequest startRequest = StartRequest.newBuilder()
                    .setSelfId(ProtobufConverter.toGoogle(selfId))
                    .setRoster(ProtobufConverter.toGoogle(roster))
                    .setKeysAndCerts(KeysAndCertsConverter.toProto(keysAndCerts))
                    .putAllOverriddenProperties(nodeConfiguration.overriddenProperties())
                    .build();

            final TestControlStub stub = TestControlGrpc.newStub(channel);
            stub.start(startRequest, new StreamObserver<>() {
                @Override
                public void onNext(final EventMessage value) {
                    if (value.hasPlatformStatusChange()) {
                        final PlatformStatusChange change = value.getPlatformStatusChange();
                        final String statusName = change.getNewStatus();
                        try {
                            final PlatformStatus newStatus = PlatformStatus.valueOf(statusName);
                            platformStatus = newStatus;
                            resultsCollector.addPlatformStatus(newStatus);
                        } catch (final IllegalArgumentException e) {
                            log.warn("Received unknown platform status: {}", statusName);
                        }
                    } else if (value.hasLogEntry()) {
                        receivedLogs.add(ProtobufConverter.fromGoogle(value.getLogEntry()));
                    } else {
                        // Other message types can be handled later
                        log.info(value);
                    }
                }

                @Override
                public void onError(Throwable t) {
                    log.error("gRPC error from node {}", selfId, t);
                }

                @Override
                public void onCompleted() {
                    // nothing
                }
            });

            lifeCycle = RUNNING;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void shutdownGracefully() {
            throw new UnsupportedOperationException("Not implemented yet!");
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void killImmediately() {
            log.info("Killing node {} immediately...", selfId);
            if (channel != null) {
                channel.shutdownNow();
            }
            container.stop();
        }
    }
}
