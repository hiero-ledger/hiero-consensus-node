// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.container;

import static java.util.Objects.requireNonNull;
import static org.hiero.consensus.pces.impl.common.PcesUtilities.getDatabaseDirectory;
import static org.hiero.otter.fixtures.container.utils.ContainerConstants.CONTAINER_APP_WORKING_DIR;
import static org.hiero.otter.fixtures.container.utils.ContainerConstants.CONTAINER_CONTROL_PORT;
import static org.hiero.otter.fixtures.container.utils.ContainerConstants.HASHSTREAM_LOG_PATH;
import static org.hiero.otter.fixtures.container.utils.ContainerConstants.METRICS_PATH;
import static org.hiero.otter.fixtures.container.utils.ContainerConstants.NODE_COMMUNICATION_PORT;
import static org.hiero.otter.fixtures.container.utils.ContainerConstants.OTTER_LOG_PATH;
import static org.hiero.otter.fixtures.container.utils.ContainerConstants.SWIRLDS_LOG_PATH;
import static org.hiero.otter.fixtures.internal.AbstractNode.LifeCycle.DESTROYED;
import static org.hiero.otter.fixtures.internal.AbstractNode.LifeCycle.INIT;
import static org.hiero.otter.fixtures.internal.AbstractNode.LifeCycle.RUNNING;
import static org.hiero.otter.fixtures.internal.AbstractNode.LifeCycle.SHUTDOWN;
import static org.junit.jupiter.api.Assertions.fail;

import com.google.protobuf.Empty;
import com.swirlds.common.config.StateCommonConfig;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status.Code;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.config.EventConfig;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.quiescence.QuiescenceCommand;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.ProfilerEvent;
import org.hiero.otter.fixtures.TimeManager;
import org.hiero.otter.fixtures.app.OtterApp;
import org.hiero.otter.fixtures.app.OtterAppConfig;
import org.hiero.otter.fixtures.app.services.consistency.ConsistencyService;
import org.hiero.otter.fixtures.app.services.consistency.ConsistencyServiceConfig;
import org.hiero.otter.fixtures.container.proto.ContainerControlServiceGrpc;
import org.hiero.otter.fixtures.container.proto.EventMessage;
import org.hiero.otter.fixtures.container.proto.InitRequest;
import org.hiero.otter.fixtures.container.proto.KillImmediatelyRequest;
import org.hiero.otter.fixtures.container.proto.NodeCommunicationServiceGrpc;
import org.hiero.otter.fixtures.container.proto.NodeCommunicationServiceGrpc.NodeCommunicationServiceStub;
import org.hiero.otter.fixtures.container.proto.PingResponse;
import org.hiero.otter.fixtures.container.proto.PlatformStatusChange;
import org.hiero.otter.fixtures.container.proto.QuiescenceRequest;
import org.hiero.otter.fixtures.container.proto.StartRequest;
import org.hiero.otter.fixtures.container.proto.SyntheticBottleneckRequest;
import org.hiero.otter.fixtures.container.proto.TransactionRequest;
import org.hiero.otter.fixtures.container.proto.TransactionRequestAnswer;
import org.hiero.otter.fixtures.container.utils.ContainerConstants;
import org.hiero.otter.fixtures.container.utils.ContainerUtils;
import org.hiero.otter.fixtures.internal.AbstractNode;
import org.hiero.otter.fixtures.internal.AbstractTimeManager.TimeTickReceiver;
import org.hiero.otter.fixtures.internal.KeysAndCertsConverter;
import org.hiero.otter.fixtures.internal.NetworkConfiguration;
import org.hiero.otter.fixtures.internal.ProtobufConverter;
import org.hiero.otter.fixtures.internal.result.ConsensusRoundPool;
import org.hiero.otter.fixtures.internal.result.NodeResultsCollector;
import org.hiero.otter.fixtures.internal.result.SingleNodeEventStreamResultImpl;
import org.hiero.otter.fixtures.internal.result.SingleNodePcesResultImpl;
import org.hiero.otter.fixtures.internal.result.SingleNodeReconnectResultImpl;
import org.hiero.otter.fixtures.network.transactions.OtterTransaction;
import org.hiero.otter.fixtures.result.SingleNodeConsensusResult;
import org.hiero.otter.fixtures.result.SingleNodeEventStreamResult;
import org.hiero.otter.fixtures.result.SingleNodeLogResult;
import org.hiero.otter.fixtures.result.SingleNodePcesResult;
import org.hiero.otter.fixtures.result.SingleNodePlatformStatusResult;
import org.hiero.otter.fixtures.result.SingleNodeReconnectResult;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.Network;
import org.testcontainers.images.builder.ImageFromDockerfile;

/**
 * Implementation of {@link Node} for a container environment.
 */
public class ContainerNode extends AbstractNode implements Node, TimeTickReceiver {

    private static final Logger log = LogManager.getLogger();

    /** The time manager to use for this node */
    private final TimeManager timeManager;

    /** The image used to run the consensus node. */
    private final ContainerImage container;

    /** The local base directory where artifacts copied from the container will be stored. */
    private final Path localOutputDirectory;

    /** The channel used for the {@link ContainerControlServiceGrpc} */
    private final ManagedChannel containerControlChannel;

    /** The channel used for the {@link NodeCommunicationServiceGrpc} */
    private final ManagedChannel nodeCommChannel;

    /** The gRPC service used to initialize and stop the consensus node */
    private final ContainerControlServiceGrpc.ContainerControlServiceBlockingStub containerControlBlockingStub;

    /** The gRPC service used to communicate with the consensus node */
    private NodeCommunicationServiceGrpc.NodeCommunicationServiceBlockingStub nodeCommBlockingStub;

    /** The configuration of this node */
    private final ContainerNodeConfiguration nodeConfiguration;

    /** A queue of all test run related events as they occur, such as log message and status changes. */
    private final BlockingQueue<EventMessage> receivedEvents = new LinkedBlockingQueue<>();

    /** A collector of the various test run related events stored as strongly typed objects use for assertions. */
    private final NodeResultsCollector resultsCollector;

    /** A source of randomness for the node */
    private final Random random;

    /** The profiler for this node */
    private final ContainerProfiler profiler;

    /**
     * Constructor for the {@link ContainerNode} class.
     *
     * @param selfId the unique identifier for this node
     * @param timeManager the time manager to use for this node
     * @param keysAndCerts the keys for the node
     * @param network the network this node is part of
     * @param dockerImage the Docker image to use for this node
     * @param outputDirectory the directory where the node's output will be stored
     * @param networkConfiguration the network configuration for this node
     * @param consensusRoundPool the shared pool for deduplicating consensus rounds
     */
    public ContainerNode(
            @NonNull final NodeId selfId,
            @NonNull final TimeManager timeManager,
            @NonNull final KeysAndCerts keysAndCerts,
            @NonNull final Network network,
            @NonNull final ImageFromDockerfile dockerImage,
            @NonNull final Path outputDirectory,
            @NonNull final NetworkConfiguration networkConfiguration,
            @NonNull final ConsensusRoundPool consensusRoundPool) {
        super(selfId, keysAndCerts, networkConfiguration);

        this.localOutputDirectory = requireNonNull(outputDirectory, "outputDirectory must not be null");
        this.timeManager = requireNonNull(timeManager, "timeManager must not be null");

        this.resultsCollector = new NodeResultsCollector(selfId, consensusRoundPool);
        this.nodeConfiguration =
                new ContainerNodeConfiguration(() -> lifeCycle, networkConfiguration.overrideProperties());
        this.random = new SecureRandom();

        container = new ContainerImage(dockerImage, network, selfId);
        container.start();

        containerControlChannel = ManagedChannelBuilder.forAddress(
                        container.getHost(), container.getMappedPort(CONTAINER_CONTROL_PORT))
                .maxInboundMessageSize(32 * 1024 * 1024)
                .usePlaintext()
                .build();
        nodeCommChannel = ManagedChannelBuilder.forAddress(
                        container.getHost(), container.getMappedPort(NODE_COMMUNICATION_PORT))
                .maxInboundMessageSize(32 * 1024 * 1024)
                .usePlaintext()
                .build();

        // Blocking stub for initializing and killing the consensus node
        containerControlBlockingStub = ContainerControlServiceGrpc.newBlockingStub(containerControlChannel);

        profiler = new ContainerProfiler(selfId, container, localOutputDirectory);
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public SingleNodeEventStreamResult newEventStreamResult() {
        final Path eventStreamDir = localOutputDirectory.resolve(ContainerConstants.EVENT_STREAM_DIRECTORY);
        downloadEventStreamFiles();
        return new SingleNodeEventStreamResultImpl(
                selfId, eventStreamDir, configuration().current(), newReconnectResult());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void doStart(@NonNull final Duration timeout) {
        throwIfInLifecycle(LifeCycle.RUNNING, "Node has already been started.");
        throwIfInLifecycle(LifeCycle.DESTROYED, "Node has already been destroyed.");

        log.info("Starting node {}...", selfId);

        if (savedStateDirectory != null) {
            final StateCommonConfig stateCommonConfig =
                    configuration().current().getConfigData(StateCommonConfig.class);
            ContainerUtils.copySavedStateToContainer(container, selfId, stateCommonConfig, savedStateDirectory);
        }

        final InitRequest initRequest = InitRequest.newBuilder()
                .setSelfId(ProtobufConverter.toLegacy(selfId))
                .build();
        //noinspection ResultOfMethodCallIgnored
        containerControlBlockingStub.init(initRequest);

        final StartRequest startRequest = StartRequest.newBuilder()
                .setRoster(ProtobufConverter.fromPbj(roster()))
                .setKeysAndCerts(KeysAndCertsConverter.toProto(keysAndCerts))
                .setVersion(ProtobufConverter.fromPbj(version))
                .putAllOverriddenProperties(nodeConfiguration.overrideProperties())
                .build();

        // Blocking stub for communicating with the consensus node
        nodeCommBlockingStub = NodeCommunicationServiceGrpc.newBlockingStub(nodeCommChannel);

        final NodeCommunicationServiceStub stub = NodeCommunicationServiceGrpc.newStub(nodeCommChannel);
        stub.start(startRequest, new StreamObserver<>() {
            @Override
            public void onNext(final EventMessage value) {
                receivedEvents.add(value);
            }

            @Override
            public void onError(@NonNull final Throwable error) {
                /*
                 * After a call to killImmediately() the server forcibly closes the stream and the
                 * client receives an INTERNAL error. This is expected and must *not* fail the test.
                 * Only report unexpected errors that occur while the node is still running.
                 */
                if ((lifeCycle == RUNNING) && !isExpectedError(error)) {
                    final String message = String.format("gRPC error from node %s", selfId);
                    fail(message, error);
                }
            }

            private static boolean isExpectedError(final @NonNull Throwable error) {
                if (error instanceof final StatusRuntimeException sre) {
                    final Code code = sre.getStatus().getCode();
                    return code == Code.UNAVAILABLE || code == Code.CANCELLED || code == Code.INTERNAL;
                }
                return false;
            }

            @Override
            public void onCompleted() {
                if (lifeCycle != DESTROYED && lifeCycle != SHUTDOWN) {
                    fail("Node " + selfId + " has closed the connection while running the test");
                }
            }
        });

        lifeCycle = RUNNING;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @SuppressWarnings("ResultOfMethodCallIgnored")
    protected void doKillImmediately(@NonNull final Duration timeout) {
        log.info("Killing node {} immediately...", selfId);
        try {
            // Mark the node as shutting down *before* sending the request to avoid race
            // conditions with the stream observer receiving an error.
            lifeCycle = SHUTDOWN;

            final KillImmediatelyRequest request = KillImmediatelyRequest.newBuilder()
                    .setTimeoutSeconds((int) timeout.getSeconds())
                    .build();
            // Unary call – will throw if server returns an error.
            containerControlBlockingStub.withDeadlineAfter(timeout).killImmediately(request);
            platformStatus = null;

            log.info("Node {} has been killed", selfId);
        } catch (final Exception e) {
            fail("Failed to kill node %d immediately".formatted(selfId.id()), e);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @SuppressWarnings("ResultOfMethodCallIgnored")
    protected void doStartSyntheticBottleneck(@NonNull final Duration delayPerRound, @NonNull final Duration timeout) {
        log.info("Starting synthetic bottleneck on node {}", selfId);
        nodeCommBlockingStub
                .withDeadlineAfter(timeout)
                .syntheticBottleneckUpdate(SyntheticBottleneckRequest.newBuilder()
                        .setSleepMillisPerRound(delayPerRound.toMillis())
                        .build());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @SuppressWarnings("ResultOfMethodCallIgnored")
    protected void doStopSyntheticBottleneck(@NonNull final Duration timeout) {
        log.info("Stopping synthetic bottleneck on node {}", selfId);
        nodeCommBlockingStub
                .withDeadlineAfter(timeout)
                .syntheticBottleneckUpdate(SyntheticBottleneckRequest.newBuilder()
                        .setSleepMillisPerRound(0)
                        .build());
    }

    /**
     * {@inheritDoc}
     */
    @SuppressWarnings("ResultOfMethodCallIgnored")
    @Override
    protected void doSendQuiescenceCommand(@NonNull final QuiescenceCommand command, @NonNull final Duration timeout) {
        log.info("Sending quiescence command {} on node {}", command, selfId);
        final org.hiero.otter.fixtures.container.proto.QuiescenceCommand dto =
                switch (command) {
                    case QUIESCE -> org.hiero.otter.fixtures.container.proto.QuiescenceCommand.QUIESCE;
                    case BREAK_QUIESCENCE ->
                        org.hiero.otter.fixtures.container.proto.QuiescenceCommand.BREAK_QUIESCENCE;
                    case DONT_QUIESCE -> org.hiero.otter.fixtures.container.proto.QuiescenceCommand.DONT_QUIESCE;
                };
        nodeCommBlockingStub
                .withDeadlineAfter(timeout)
                .quiescenceCommandUpdate(
                        QuiescenceRequest.newBuilder().setCommand(dto).build());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void submitTransactions(@NonNull final List<OtterTransaction> transactions) {
        throwIfInLifecycle(INIT, "Node has not been started yet.");
        throwIfInLifecycle(SHUTDOWN, "Node has been shut down.");
        throwIfInLifecycle(DESTROYED, "Node has been destroyed.");

        try {
            final TransactionRequest.Builder builder = TransactionRequest.newBuilder();
            transactions.forEach(t -> builder.addPayload(t.toByteString()));
            final TransactionRequestAnswer answer = nodeCommBlockingStub.submitTransaction(builder.build());
            if (answer.getNumFailed() > 0) {
                fail("%d out of %d transaction(s) failed to submit for node %d."
                        .formatted(answer.getNumFailed(), transactions.size(), selfId.id()));
            }
        } catch (final Exception e) {
            fail("Failed to submit transaction(s) to node %d".formatted(selfId.id()), e);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isAlive() {
        final PingResponse response =
                containerControlBlockingStub.nodePing(Empty.newBuilder().build());
        if (!response.getAlive()) {
            lifeCycle = SHUTDOWN;
            platformStatus = null;
        }
        return response.getAlive();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public ContainerNodeConfiguration configuration() {
        return nodeConfiguration;
    }

    /**
     * Gets the container instance for this node. This allows direct access to the underlying Testcontainers container
     * for operations like retrieving console logs.
     *
     * @return the container instance
     */
    @NonNull
    public ContainerImage container() {
        return container;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public SingleNodeConsensusResult newConsensusResult() {
        return resultsCollector.newConsensusResult();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public SingleNodeLogResult newLogResult() {
        return resultsCollector.newLogResult();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public SingleNodePlatformStatusResult newPlatformStatusResult() {
        return resultsCollector.newStatusProgression();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public SingleNodePcesResult newPcesResult() {
        throwIsNotInLifecycle(SHUTDOWN, "Node must be in the shutdown state to retrieve PCES results.");

        final Configuration configuration = nodeConfiguration.current();
        try {
            final Path databaseDirectory =
                    getDatabaseDirectory(configuration, org.hiero.consensus.model.node.NodeId.of(selfId.id()));
            final Path localPcesDirectory = localOutputDirectory.resolve(databaseDirectory);

            Files.createDirectories(localPcesDirectory);

            // List all files recursively in the container's PCES directory
            final Path base = Path.of(CONTAINER_APP_WORKING_DIR, databaseDirectory.toString());
            final ExecResult execResult = container.execInContainer("sh", "-lc", "find '" + base + "' -type f");
            final String stdout = execResult.getStdout();

            if (stdout != null && !stdout.isBlank()) {
                final String[] files = stdout.split("\n");
                for (final String file : files) {
                    if (file == null || file.isBlank()) {
                        continue;
                    }
                    final Path containerFile = Path.of(file).normalize();
                    final Path relative = base.relativize(containerFile);
                    final Path localFile = localPcesDirectory.resolve(relative);
                    Files.createDirectories(localFile.getParent());
                    container.copyFileFromContainer(containerFile.toString(), localFile.toString());
                }
            } else {
                log.warn("No PCES files found in container");
            }

            return new SingleNodePcesResultImpl(selfId, nodeConfiguration.current(), localPcesDirectory);
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to copy PCES files from container", e);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while copying PCES files from container", e);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public SingleNodeReconnectResult newReconnectResult() {
        return new SingleNodeReconnectResultImpl(selfId, newPlatformStatusResult(), newLogResult());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void startProfiling(
            @NonNull final String outputFilename,
            @NonNull final Duration samplingInterval,
            @NonNull final ProfilerEvent... events) {
        profiler.startProfiling(outputFilename, samplingInterval, events);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void stopProfiling() {
        profiler.stopProfiling();
    }

    /**
     * Shuts down the container and cleans up resources. Once this method is called, the node cannot be started again
     * and no more data can be retrieved. This method is idempotent and can be called multiple times without any side
     * effects.
     */
    void destroy() {
        try {
            // copy logs from the container to the local filesystem
            downloadConsensusFiles();
            downloadStateFiles();
            downloadConsistencyServiceFiles();
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to copy files from container", e);
        }

        log.info("Destroying container of node {}...", selfId);
        containerControlChannel.shutdownNow();
        nodeCommChannel.shutdownNow();
        if (container.isRunning()) {
            container.stop();
        }

        resultsCollector.destroy();
        platformStatus = null;
        lifeCycle = DESTROYED;
    }

    private void downloadEventStreamFiles() {
        final EventConfig eventConfig = nodeConfiguration.current().getConfigData(EventConfig.class);
        copyFolderFromContainer(eventConfig.eventsLogDir(), ContainerConstants.EVENT_STREAM_DIRECTORY);
    }

    private void downloadConsensusFiles() throws IOException {
        copyFileFromContainer(SWIRLDS_LOG_PATH);
        copyFileFromContainer(HASHSTREAM_LOG_PATH);
        copyFileFromContainer(OTTER_LOG_PATH);
        copyFileFromContainer(METRICS_PATH.formatted(selfId.id()));
    }

    private void downloadStateFiles() {
        final StateCommonConfig stateConfig = nodeConfiguration.current().getConfigData(StateCommonConfig.class);
        final Path stateDirectory = stateConfig.savedStateDirectory().resolve(OtterApp.APP_NAME);
        copyFolderFromContainer(stateDirectory.toString());
    }

    private void downloadConsistencyServiceFiles() {
        final OtterAppConfig appConfig = nodeConfiguration.current().getConfigData(OtterAppConfig.class);
        final boolean consistencyServiceEnabled = appConfig.services().stream()
                .anyMatch(serviceName -> serviceName.contains(ConsistencyService.class.getSimpleName()));
        if (!consistencyServiceEnabled) {
            return;
        }
        final StateCommonConfig stateConfig = nodeConfiguration.current().getConfigData(StateCommonConfig.class);
        final ConsistencyServiceConfig consistencyServiceConfig =
                nodeConfiguration.current().getConfigData(ConsistencyServiceConfig.class);

        final Path historyFileDirectory = stateConfig
                .savedStateDirectory()
                .resolve(consistencyServiceConfig.historyFileDirectory())
                .resolve(Long.toString(selfId.id()));

        final Path historyFilePath = historyFileDirectory.resolve(consistencyServiceConfig.historyFileName());
        copyFileFromContainer(historyFilePath.toString(), consistencyServiceConfig.historyFileName());
    }

    private void copyFileFromContainer(@NonNull final String relativePath) {
        copyFileFromContainer(relativePath, relativePath);
    }

    private void copyFileFromContainer(@NonNull final String sourcePath, @NonNull final String relativeTargetPath) {
        final String containerPath =
                sourcePath.startsWith(File.separator) ? sourcePath : CONTAINER_APP_WORKING_DIR + sourcePath;
        final Path localPath = localOutputDirectory.resolve(relativeTargetPath);

        try {
            final ExecResult result = container.execInContainer("test", "-f", containerPath);
            if (result.getExitCode() == 0) {
                Files.createDirectories(localPath.getParent());
                container.copyFileFromContainer(containerPath, localPath.toString());
            } else {
                log.warn("File not found in node {}: {}", selfId.id(), containerPath);
            }
        } catch (final IOException e) {
            log.warn("Failed to copy file from node {}: {}", selfId.id(), containerPath, e);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while copying file from node {}: {}", selfId.id(), containerPath, e);
        }
    }

    private void copyFolderFromContainer(@NonNull final String relativePath) {
        copyFolderFromContainer(relativePath, relativePath);
    }

    private void copyFolderFromContainer(@NonNull final String sourcePath, @NonNull final String relativeTargetPath) {
        final String containerPath =
                sourcePath.startsWith(File.separator) ? sourcePath : CONTAINER_APP_WORKING_DIR + sourcePath;
        final Path localPath = localOutputDirectory.resolve(relativeTargetPath);

        try {
            final ExecResult result = container.execInContainer("test", "-d", containerPath);
            if (result.getExitCode() == 0) {
                Files.createDirectories(localPath);
                // Use Docker cp command to copy the entire directory
                final String containerId = container.getContainerId();
                final ProcessBuilder processBuilder = new ProcessBuilder(
                        "docker", "cp", containerId + ":" + containerPath + "/.", localPath.toString());
                final Process process = processBuilder.start();
                process.waitFor();
            } else {
                log.warn("Folder not found in node {}: {}", selfId.id(), containerPath);
            }
        } catch (final IOException e) {
            log.warn("Failed to copy folder from node {}: {}", selfId.id(), containerPath, e);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while copying folder from node {}: {}", selfId.id(), containerPath, e);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    protected TimeManager timeManager() {
        return timeManager;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    protected Random random() {
        return random;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void tick(@NonNull final Instant now) {
        EventMessage event;
        while ((event = receivedEvents.poll()) != null) {
            switch (event.getEventCase()) {
                case LOG_ENTRY -> resultsCollector.addLogEntry(ProtobufConverter.toPlatform(event.getLogEntry()));
                case PLATFORM_STATUS_CHANGE -> handlePlatformChange(event);
                case CONSENSUS_ROUND ->
                    resultsCollector.addConsensusRound(ProtobufConverter.toPlatform(event.getConsensusRound()));
                default -> log.warn("Received unexpected event: {}", event);
            }
        }
    }

    private void handlePlatformChange(@NonNull final EventMessage value) {
        final PlatformStatusChange change = value.getPlatformStatusChange();
        final String statusName = change.getNewStatus();
        log.info("Received platform status change from node {}: {}", selfId, statusName);
        try {
            final PlatformStatus newStatus = PlatformStatus.valueOf(statusName);
            platformStatus = newStatus;
            resultsCollector.addPlatformStatus(newStatus);
        } catch (final IllegalArgumentException e) {
            log.warn("Received unknown platform status: {}", statusName);
        }
    }
}
