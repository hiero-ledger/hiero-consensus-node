// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.container;

import static java.util.Objects.requireNonNull;
import static org.hiero.consensus.pces.impl.common.PcesUtilities.getDatabaseDirectory;
import static org.hiero.otter.fixtures.container.utils.ContainerConstants.CONTAINER_APP_WORKING_DIR;
import static org.hiero.otter.fixtures.container.utils.ContainerConstants.CONTAINER_CONTROL_PORT;
import static org.hiero.otter.fixtures.container.utils.ContainerConstants.GC_LOG_PATH;
import static org.hiero.otter.fixtures.container.utils.ContainerConstants.HASHSTREAM_LOG_PATH;
import static org.hiero.otter.fixtures.container.utils.ContainerConstants.METRICS_OTHER;
import static org.hiero.otter.fixtures.container.utils.ContainerConstants.METRICS_PATH;
import static org.hiero.otter.fixtures.container.utils.ContainerConstants.NODE_COMMUNICATION_PORT;
import static org.hiero.otter.fixtures.container.utils.ContainerConstants.OTTER_LOG_PATH;
import static org.hiero.otter.fixtures.container.utils.ContainerConstants.SWIRLDS_LOG_PATH;
import static org.hiero.otter.fixtures.internal.AbstractMiniNode.LifeCycle.DESTROYED;
import static org.hiero.otter.fixtures.internal.AbstractMiniNode.LifeCycle.INIT;
import static org.hiero.otter.fixtures.internal.AbstractMiniNode.LifeCycle.RUNNING;
import static org.hiero.otter.fixtures.internal.AbstractMiniNode.LifeCycle.SHUTDOWN;
import static org.junit.jupiter.api.Assertions.fail;

import com.hedera.pbj.runtime.grpc.GrpcException;
import com.hedera.pbj.runtime.grpc.GrpcStatus;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
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
import org.hiero.base.file.FileSystemManager;
import org.hiero.consensus.PathsConfig;
import org.hiero.consensus.event.stream.config.EventConfig;
import org.hiero.consensus.main.model.KeysAndCerts;
import org.hiero.consensus.main.model.NodeId;
import org.hiero.consensus.main.model.QuiescenceCommand;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.ProfilerEvent;
import org.hiero.otter.fixtures.TimeManager;
import org.hiero.otter.fixtures.app.OtterApp;
import org.hiero.otter.fixtures.app.OtterAppConfig;
import org.hiero.otter.fixtures.app.services.consistency.ConsistencyService;
import org.hiero.otter.fixtures.app.services.consistency.ConsistencyServiceConfig;
import org.hiero.otter.fixtures.container.proto.ContainerControlServiceInterface;
import org.hiero.otter.fixtures.container.proto.ContainerControlServiceInterface.ContainerControlServiceClient;
import org.hiero.otter.fixtures.container.proto.Empty;
import org.hiero.otter.fixtures.container.proto.EventMessage;
import org.hiero.otter.fixtures.container.proto.InitRequest;
import org.hiero.otter.fixtures.container.proto.KillImmediatelyRequest;
import org.hiero.otter.fixtures.container.proto.NodeCommunicationServiceInterface;
import org.hiero.otter.fixtures.container.proto.NodeCommunicationServiceInterface.NodeCommunicationServiceClient;
import org.hiero.otter.fixtures.container.proto.PingResponse;
import org.hiero.otter.fixtures.container.proto.PlatformStatusChange;
import org.hiero.otter.fixtures.container.proto.QuiescenceRequest;
import org.hiero.otter.fixtures.container.proto.StartRequest;
import org.hiero.otter.fixtures.container.proto.SyncPoint;
import org.hiero.otter.fixtures.container.proto.SyntheticBottleneckRequest;
import org.hiero.otter.fixtures.container.proto.ThreadDumpResponse;
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

    /** Backoff between retries of the unary {@code start} RPC while the connection is re-established. */
    private static final Duration START_RETRY_BACKOFF = Duration.ofMillis(250);

    /** The time manager to use for this node */
    private final TimeManager timeManager;

    /** The image used to run the consensus node. */
    private final ContainerImage container;

    /** The local base directory where artifacts copied from the container will be stored. */
    private final Path localOutputDirectory;

    /** The connection to the {@link ContainerControlServiceInterface}, used to initialize and stop the consensus node */
    private final GrpcConnection<ContainerControlServiceClient> containerControlConnection;

    /** The connection to the {@link NodeCommunicationServiceInterface}, used to communicate with the consensus node */
    final GrpcConnection<NodeCommunicationServiceClient> nodeCommConnection;

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

    /** Whether GC logging is enabled for the consensus node process */
    private final boolean gcLoggingEnabled;

    /** JVM arguments to add when starting up the java process */
    private final List<String> jvmArgs;

    /** The self-healing client subscribed to this node's event stream; created in {@link #doStart}. */
    private EventStreamClient eventStreamClient;

    /**
     * Constructor for the {@link ContainerNode} class.
     *
     * @param selfId               the unique identifier for this node
     * @param timeManager          the time manager to use for this node
     * @param keysAndCerts         the keys for the node
     * @param network              the network this node is part of
     * @param dockerImage          the Docker image to use for this node
     * @param outputDirectory      the directory where the node's output will be stored
     * @param networkConfiguration the network configuration for this node
     * @param consensusRoundPool   the shared pool for deduplicating consensus rounds
     * @param gcLoggingEnabled     {@code true} if GC logging should be enabled for the node process
     * @param jvmArgs              additional JVM arguments to pass to the node process
     */
    public ContainerNode(
            @NonNull final NodeId selfId,
            @NonNull final TimeManager timeManager,
            @NonNull final KeysAndCerts keysAndCerts,
            @NonNull final Network network,
            @NonNull final ImageFromDockerfile dockerImage,
            @NonNull final Path outputDirectory,
            @NonNull final NetworkConfiguration networkConfiguration,
            @NonNull final ConsensusRoundPool consensusRoundPool,
            final boolean gcLoggingEnabled,
            @NonNull final List<String> jvmArgs) {
        super(selfId, keysAndCerts, networkConfiguration);

        this.localOutputDirectory = requireNonNull(outputDirectory, "outputDirectory must not be null");
        this.timeManager = requireNonNull(timeManager, "timeManager must not be null");

        this.resultsCollector = new NodeResultsCollector(selfId, consensusRoundPool, false);
        this.nodeConfiguration =
                new ContainerNodeConfiguration(() -> lifeCycle, networkConfiguration.overrideProperties());
        this.random = new SecureRandom();
        this.gcLoggingEnabled = gcLoggingEnabled;
        this.jvmArgs = List.copyOf(requireNonNull(jvmArgs, "jvmArgs must not be null"));

        container = new ContainerImage(dockerImage, network, selfId);
        container.start();

        containerControlConnection = new GrpcConnection<>(
                container.getHost(),
                container.getMappedPort(CONTAINER_CONTROL_PORT),
                ContainerControlServiceClient::new);
        nodeCommConnection = new GrpcConnection<>(
                container.getHost(),
                container.getMappedPort(NODE_COMMUNICATION_PORT),
                NodeCommunicationServiceClient::new);

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
        throwIfInLifecycle(RUNNING, "Node has already been started.");
        throwIfInLifecycle(DESTROYED, "Node has already been destroyed.");

        log.info("Starting node {}...", selfId);

        if (lifeCycle == INIT && savedStateDirectory != null) {
            final PathsConfig pathsConfig = configuration().current().getConfigData(PathsConfig.class);
            ContainerUtils.copySavedStateToContainer(container, selfId, pathsConfig, savedStateDirectory);
        }

        final InitRequest initRequest = InitRequest.newBuilder()
                .selfId(com.hedera.hapi.platform.state.NodeId.newBuilder()
                        .id(selfId.id())
                        .build())
                .gcLoggingEnabled(gcLoggingEnabled)
                .jvmArgs(jvmArgs)
                .build();
        containerControlConnection.call(client -> client.init(initRequest));

        final StartRequest startRequest = StartRequest.newBuilder()
                .roster(roster())
                .keysAndCerts(KeysAndCertsConverter.toProto(keysAndCerts))
                .version(version)
                .overriddenProperties(nodeConfiguration.overrideProperties())
                .build();

        // Request the start of the platform. The node acknowledges the request before it constructs the platform,
        // so this call throws if the node rejects the request, but not if constructing the platform fails later.
        startPlatform(startRequest, timeout);

        lifeCycle = RUNNING;

        // Subscribe to the event stream on a self-healing client. Every subscription uses a connection of its own,
        // because closing the connection is the only way to end the stream from this side.
        eventStreamClient = new EventStreamClient(
                selfId,
                () -> GrpcConnection.connect(
                        container.getHost(),
                        container.getMappedPort(NODE_COMMUNICATION_PORT),
                        NodeCommunicationServiceClient::new),
                receivedEvents,
                () -> lifeCycle == RUNNING,
                this::isAlive);
        eventStreamClient.start();
    }

    /**
     * Invokes the unary {@code start} RPC, retrying while the response is {@code UNAVAILABLE} and the start budget has
     * not been exhausted. This tolerates the connection to a freshly (re)started node process not being available yet;
     * any other status, or exhausting the budget, propagates the error.
     *
     * <p>The node acknowledges the request as soon as it has accepted it and constructs the platform afterwards. This
     * method therefore returns while the platform is still starting, and a failure to construct or start the platform
     * is not reported by it; the node logs such a failure instead.
     *
     * @param startRequest the request to send
     * @param timeout      the overall budget for starting the platform
     */
    private void startPlatform(@NonNull final StartRequest startRequest, @NonNull final Duration timeout) {
        final Instant deadline = timeManager.now().plus(timeout);
        while (true) {
            try {
                nodeCommConnection.call(client -> client.start(startRequest));
                return;
            } catch (final GrpcException e) {
                if (e.status() != GrpcStatus.UNAVAILABLE || !timeManager.now().isBefore(deadline)) {
                    throw e;
                }
                log.warn(
                        "Start of node {} returned UNAVAILABLE; retrying while the connection is re-established",
                        selfId);
            }
            // Back off before retrying to give the new node process time to accept connections.
            timeManager.waitFor(START_RETRY_BACKOFF);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void doKillImmediately(@NonNull final Duration timeout) {
        log.info("Killing node {} immediately...", selfId);
        try {
            // Mark the node as shutting down *before* sending the request to avoid race
            // conditions with the stream observer receiving an error.
            lifeCycle = SHUTDOWN;

            // Stop the event stream client before killing the node so its re-subscribe loop treats the
            // resulting stream end as expected rather than failing the test.
            if (eventStreamClient != null) {
                eventStreamClient.close();
            }

            final KillImmediatelyRequest request = KillImmediatelyRequest.newBuilder()
                    .timeoutSeconds((int) timeout.getSeconds())
                    .build();
            // Unary call – will throw if server returns an error.
            containerControlConnection.call(client -> client.killImmediately(request), timeout);
            // The connection ended with the node process. A new one is established when the node is started again.
            nodeCommConnection.close();
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
    protected void doStartSyntheticBottleneck(@NonNull final Duration delayPerRound, @NonNull final Duration timeout) {
        log.info("Starting synthetic bottleneck on node {}", selfId);
        final SyntheticBottleneckRequest request = SyntheticBottleneckRequest.newBuilder()
                .sleepMillisPerRound(delayPerRound.toMillis())
                .build();
        nodeCommConnection.call(client -> client.syntheticBottleneckUpdate(request), timeout);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void doStopSyntheticBottleneck(@NonNull final Duration timeout) {
        log.info("Stopping synthetic bottleneck on node {}", selfId);
        final SyntheticBottleneckRequest request =
                SyntheticBottleneckRequest.newBuilder().sleepMillisPerRound(0).build();
        nodeCommConnection.call(client -> client.syntheticBottleneckUpdate(request), timeout);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void doSendQuiescenceCommand(@NonNull final QuiescenceCommand command, @NonNull final Duration timeout) {
        log.info("Sending quiescence command {} on node {}", command, selfId);
        final org.hiero.otter.fixtures.container.proto.QuiescenceCommand dto = switch (command) {
            case QUIESCE -> org.hiero.otter.fixtures.container.proto.QuiescenceCommand.QUIESCE;
            case BREAK_QUIESCENCE -> org.hiero.otter.fixtures.container.proto.QuiescenceCommand.BREAK_QUIESCENCE;
            case DONT_QUIESCE -> org.hiero.otter.fixtures.container.proto.QuiescenceCommand.DONT_QUIESCE;
        };
        final QuiescenceRequest request =
                QuiescenceRequest.newBuilder().command(dto).build();
        nodeCommConnection.call(client -> client.quiescenceCommandUpdate(request), timeout);
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
            final List<Bytes> payload = transactions.stream()
                    .map(OtterTransaction.PROTOBUF::toBytes)
                    .toList();
            final TransactionRequest request =
                    TransactionRequest.newBuilder().payload(payload).build();
            final TransactionRequestAnswer answer =
                    nodeCommConnection.call(client -> client.submitTransaction(request));
            if (answer.numFailed() > 0) {
                fail("%d out of %d transaction(s) failed to submit for node %d."
                        .formatted(answer.numFailed(), transactions.size(), selfId.id()));
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
        final PingResponse response = containerControlConnection.call(client -> client.nodePing(Empty.DEFAULT));
        if (!response.alive()) {
            lifeCycle = SHUTDOWN;
            platformStatus = null;
            if (eventStreamClient != null) {
                eventStreamClient.close();
            }
        }
        return response.alive();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public EventWindow latestEventWindow() {
        return newConsensusResult().getLatestEventWindow();
    }

    /**
     * {@inheritDoc}
     *
     * <p>The dump is produced by the container's control process attaching to the (separate) consensus node process,
     * so it can capture a node whose own threads are wedged. Best-effort: returns a short explanatory message instead
     * of throwing if the dump cannot be captured.
     */
    @Override
    @NonNull
    public String dumpThreads() {
        try {
            final ThreadDumpResponse response = containerControlConnection.call(
                    client -> client.dumpThreads(Empty.DEFAULT), Duration.ofSeconds(40));
            return response.threadDump();
        } catch (final GrpcException e) {
            return "(thread dump RPC failed for node " + selfId + ": " + e.getMessage() + ")";
        }
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
        throwIsNotInLifecycle(
                SHUTDOWN,
                "Node must be in the shutdown state to retrieve PCES results. "
                        + "Ensure network.shutdown() or node.shutdown() is called before accessing PCES results.");

        final Configuration configuration = nodeConfiguration.current();
        final PathsConfig pathsConfig = configuration.getConfigData(PathsConfig.class);
        final FileSystemManager fileSystemManager =
                new FileSystemManager(pathsConfig.savedStateDir(), pathsConfig.tmpDir());
        try {
            final Path databaseDirectory = getDatabaseDirectory(
                    configuration, fileSystemManager, org.hiero.consensus.main.model.NodeId.of(selfId.id()));
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
        if (eventStreamClient != null) {
            eventStreamClient.close();
        }
        containerControlConnection.close();
        nodeCommConnection.close();
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
        copyFileFromContainer(METRICS_OTHER);
        if (gcLoggingEnabled) {
            copyFileFromContainer(GC_LOG_PATH);
        }
    }

    private void downloadStateFiles() {
        final PathsConfig pathsConfig = nodeConfiguration.current().getConfigData(PathsConfig.class);
        final Path stateDirectory = pathsConfig.savedStateDir().resolve(OtterApp.APP_NAME);
        copyFolderFromContainer(stateDirectory.toString());
    }

    private void downloadConsistencyServiceFiles() {
        final OtterAppConfig appConfig = nodeConfiguration.current().getConfigData(OtterAppConfig.class);
        final boolean consistencyServiceEnabled = appConfig.services().stream()
                .anyMatch(serviceName -> serviceName.contains(ConsistencyService.class.getSimpleName()));
        if (!consistencyServiceEnabled) {
            return;
        }

        final PathsConfig pathsConfig = nodeConfiguration.current().getConfigData(PathsConfig.class);
        final ConsistencyServiceConfig consistencyServiceConfig =
                nodeConfiguration.current().getConfigData(ConsistencyServiceConfig.class);

        final Path historyFileDirectory = pathsConfig
                .savedStateDir()
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
            switch (event.event().kind()) {
                case LOG_ENTRY -> resultsCollector.addLogEntry(ProtobufConverter.toPlatform(event.logEntryOrThrow()));
                case PLATFORM_STATUS_CHANGE -> handlePlatformChange(event);
                case CONSENSUS_ROUND ->
                    resultsCollector.addConsensusRound(ProtobufConverter.toPlatform(event.consensusRoundOrThrow()));
                case SYNC_POINT -> handleSyncPoint(event);
                default -> log.warn("Received unexpected event: {}", event);
            }
        }
    }

    private void handlePlatformChange(@NonNull final EventMessage value) {
        final PlatformStatusChange change = value.platformStatusChangeOrThrow();
        final String statusName = change.newStatus();
        log.info("Received platform status change from node {}: {}", selfId, statusName);
        try {
            final PlatformStatus newStatus = PlatformStatus.valueOf(statusName);
            platformStatus = newStatus;
            resultsCollector.addPlatformStatus(newStatus);
        } catch (final IllegalArgumentException e) {
            log.warn("Received unknown platform status: {}", statusName);
        }
    }

    /**
     * Applies the status snapshot carried by a sync point to the cached {@link #platformStatus}. A sync point is a
     * snapshot delivered at the start of a (re-)subscription, not a transition, so it is deliberately <em>not</em>
     * added to the results collector's status progression; doing so would inject a phantom transition on every
     * reconnect.
     */
    private void handleSyncPoint(@NonNull final EventMessage value) {
        final SyncPoint syncPoint = value.syncPointOrThrow();
        final String statusName = syncPoint.currentStatus();
        if (statusName.isEmpty()) {
            return;
        }
        log.info("Received sync point from node {} with current status: {}", selfId, statusName);
        try {
            platformStatus = PlatformStatus.valueOf(statusName);
        } catch (final IllegalArgumentException e) {
            log.warn("Received unknown platform status in sync point: {}", statusName);
        }
    }
}
