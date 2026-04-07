// SPDX-License-Identifier: Apache-2.0
package org.hiero.sloth.fixtures.remote;

import static java.util.Objects.requireNonNull;
import static org.hiero.sloth.fixtures.internal.AbstractNode.LifeCycle.DESTROYED;
import static org.hiero.sloth.fixtures.internal.AbstractNode.LifeCycle.INIT;
import static org.hiero.sloth.fixtures.internal.AbstractNode.LifeCycle.RUNNING;
import static org.hiero.sloth.fixtures.internal.AbstractNode.LifeCycle.SHUTDOWN;
import static org.junit.jupiter.api.Assertions.fail;

import com.google.protobuf.Empty;
import edu.umd.cs.findbugs.annotations.NonNull;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status.Code;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
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
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.quiescence.QuiescenceCommand;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.sloth.fixtures.Node;
import org.hiero.sloth.fixtures.ProfilerEvent;
import org.hiero.sloth.fixtures.SlothTransactionType;
import org.hiero.sloth.fixtures.TimeManager;
import org.hiero.sloth.fixtures.container.proto.ContainerControlServiceGrpc;
import org.hiero.sloth.fixtures.container.proto.EventMessage;
import org.hiero.sloth.fixtures.container.proto.InitRequest;
import org.hiero.sloth.fixtures.container.proto.KillImmediatelyRequest;
import org.hiero.sloth.fixtures.container.proto.NodeCommunicationServiceGrpc;
import org.hiero.sloth.fixtures.container.proto.NodeCommunicationServiceGrpc.NodeCommunicationServiceStub;
import org.hiero.sloth.fixtures.container.proto.PingResponse;
import org.hiero.sloth.fixtures.container.proto.PlatformStatusChange;
import org.hiero.sloth.fixtures.container.proto.QuiescenceRequest;
import org.hiero.sloth.fixtures.container.proto.StartRequest;
import org.hiero.sloth.fixtures.container.proto.StartTransactionGenerationRequest;
import org.hiero.sloth.fixtures.container.proto.StopTransactionGenerationResponse;
import org.hiero.sloth.fixtures.container.proto.TransactionRequest;
import org.hiero.sloth.fixtures.container.proto.TransactionRequestAnswer;
import org.hiero.sloth.fixtures.container.proto.TransactionType;
import org.hiero.sloth.fixtures.internal.AbstractNode;
import org.hiero.sloth.fixtures.internal.AbstractTimeManager.TimeTickReceiver;
import org.hiero.sloth.fixtures.internal.KeysAndCertsConverter;
import org.hiero.sloth.fixtures.internal.NetworkConfiguration;
import org.hiero.sloth.fixtures.internal.ProtobufConverter;
import org.hiero.sloth.fixtures.internal.result.NodeResultsCollector;
import org.hiero.sloth.fixtures.network.transactions.SlothTransaction;
import org.hiero.sloth.fixtures.result.SingleNodeLogResult;
import org.hiero.sloth.fixtures.result.SingleNodePlatformStatusResult;

/**
 * Implementation of {@link Node} for a remote SSH environment. Each node is deployed and executed on a remote machine
 * via SSH. Communication with the node happens over gRPC through SSH port-forwarded tunnels.
 */
public class RemoteNode extends AbstractNode implements Node, TimeTickReceiver {

    private static final Logger log = LogManager.getLogger();

    private final TimeManager timeManager;
    private final SshExecutor sshExecutor;
    private final RemoteHostAllocator.HostAssignment hostAssignment;
    private final Path localOutputDirectory;
    private final String remoteNodeDir;
    private final String remoteJavaPath;
    private final boolean cleanupOnDestroy;

    private final ManagedChannel containerControlChannel;
    private final ManagedChannel nodeCommChannel;
    private final ContainerControlServiceGrpc.ContainerControlServiceBlockingStub containerControlBlockingStub;
    private NodeCommunicationServiceGrpc.NodeCommunicationServiceBlockingStub nodeCommBlockingStub;

    private final RemoteNodeConfiguration nodeConfiguration;
    private final BlockingQueue<EventMessage> receivedEvents = new LinkedBlockingQueue<>();
    private final NodeResultsCollector resultsCollector;
    private final Random random;
    private final RemoteProfiler profiler;

    private Process controlPortForward;
    private Process commPortForward;
    private final int localControlPort;
    private final int localCommPort;

    /**
     * Constructor for the {@link RemoteNode} class.
     *
     * @param selfId the unique identifier for this node
     * @param timeManager the time manager to use for this node
     * @param keysAndCerts the keys for the node
     * @param sshExecutor the SSH executor for the remote host
     * @param hostAssignment the host and port assignment for this node
     * @param outputDirectory the local directory where the node's output will be stored
     * @param networkConfiguration the network configuration for this node
     * @param remoteWorkDir the base working directory on the remote host
     * @param remoteJavaPath the path to the Java executable on the remote host
     * @param cleanupOnDestroy whether to clean up remote files on destroy
     */
    public RemoteNode(
            @NonNull final NodeId selfId,
            @NonNull final TimeManager timeManager,
            @NonNull final KeysAndCerts keysAndCerts,
            @NonNull final SshExecutor sshExecutor,
            @NonNull final RemoteHostAllocator.HostAssignment hostAssignment,
            @NonNull final Path outputDirectory,
            @NonNull final NetworkConfiguration networkConfiguration,
            @NonNull final String remoteWorkDir,
            @NonNull final String remoteJavaPath,
            final boolean cleanupOnDestroy) {
        super(selfId, keysAndCerts, networkConfiguration);

        this.localOutputDirectory = requireNonNull(outputDirectory, "outputDirectory must not be null");
        this.timeManager = requireNonNull(timeManager, "timeManager must not be null");
        this.sshExecutor = requireNonNull(sshExecutor, "sshExecutor must not be null");
        this.hostAssignment = requireNonNull(hostAssignment, "hostAssignment must not be null");
        this.remoteJavaPath = requireNonNull(remoteJavaPath, "remoteJavaPath must not be null");
        this.cleanupOnDestroy = cleanupOnDestroy;

        this.remoteNodeDir = remoteWorkDir + "/node-" + selfId.id() + "/";
        this.resultsCollector = new NodeResultsCollector(selfId);
        this.nodeConfiguration =
                new RemoteNodeConfiguration(() -> lifeCycle, networkConfiguration.overrideProperties(), remoteNodeDir);
        this.random = new SecureRandom();

        // Create remote directories and deploy artifacts
        deployToRemoteHost();

        // Start DockerMain on the remote host
        startRemoteDockerMain();

        // Set up SSH port forwarding for gRPC channels
        localControlPort = SshExecutor.findFreePort();
        localCommPort = SshExecutor.findFreePort();
        controlPortForward = sshExecutor.startPortForward(localControlPort, hostAssignment.controlPort());
        commPortForward = sshExecutor.startPortForward(localCommPort, hostAssignment.commPort());

        // Create gRPC channels through the SSH tunnels
        containerControlChannel = ManagedChannelBuilder.forAddress("localhost", localControlPort)
                .maxInboundMessageSize(32 * 1024 * 1024)
                .usePlaintext()
                .build();
        nodeCommChannel = ManagedChannelBuilder.forAddress("localhost", localCommPort)
                .maxInboundMessageSize(32 * 1024 * 1024)
                .usePlaintext()
                .build();

        containerControlBlockingStub = ContainerControlServiceGrpc.newBlockingStub(containerControlChannel);

        profiler = new RemoteProfiler(selfId, sshExecutor, localOutputDirectory, remoteNodeDir);
    }

    private void deployToRemoteHost() {
        log.info("Deploying artifacts to {} at {}...", sshExecutor.host(), remoteNodeDir);

        // Kill any leftover processes from a previous run
        killRemoteProcess();

        final Path buildDataDir = Path.of("build", "data");
        if (!Files.exists(buildDataDir.resolve("apps")) || !Files.exists(buildDataDir.resolve("lib"))) {
            throw new UncheckedIOException(
                    new IOException("Build artifacts not found at " + buildDataDir.toAbsolutePath()
                            + ". Run the Gradle build first (e.g. ./gradlew :consensus-sloth:copyDockerizedApp)."));
        }

        // Create directory structure on remote host
        sshExecutor.exec("mkdir", "-p", remoteNodeDir + "output");

        // Upload apps/ and lib/ via tar+ssh (single stream, much faster than scp -r for 100+ JARs)
        sshExecutor.uploadViaTar(buildDataDir, "apps", remoteNodeDir);
        sshExecutor.uploadViaTar(buildDataDir, "lib", remoteNodeDir);

        log.info("Deployment to {} completed", sshExecutor.host());
    }

    private void startRemoteDockerMain() {
        log.info(
                "Starting DockerMain on {} (control port {}, comm port {})...",
                sshExecutor.host(),
                hostAssignment.controlPort(),
                hostAssignment.commPort());

        final String command = String.format(
                "cd %s && nohup %s"
                        + " -Dsloth.workdir=%s"
                        + " -Dsloth.control.port=%d"
                        + " -Dsloth.comm.port=%d"
                        + " -Dsloth.java=%s"
                        + " -Djdk.attach.allowAttachSelf=true"
                        + " -XX:+StartAttachListener"
                        + " -jar %sapps/DockerApp.jar"
                        + " > %soutput/docker-main.log 2>&1 < /dev/null &",
                remoteNodeDir,
                remoteJavaPath,
                remoteNodeDir,
                hostAssignment.controlPort(),
                hostAssignment.commPort(),
                remoteJavaPath,
                remoteNodeDir,
                remoteNodeDir);

        sshExecutor.execBackground(command);

        // Wait for the control port to become available
        waitForRemotePort(hostAssignment.controlPort());
        log.info("DockerMain started on {}", sshExecutor.host());
    }

    private void waitForRemotePort(final int port) {
        final Duration timeout = Duration.ofSeconds(30);
        final Instant deadline = Instant.now().plus(timeout);

        while (Instant.now().isBefore(deadline)) {
            final SshExecutor.ExecResult result =
                    sshExecutor.exec(Duration.ofSeconds(5), "ss", "-tln", "sport", "=", ":" + port);
            if (result.exitCode() == 0 && result.stdout().contains("LISTEN")) {
                return;
            }
            try {
                Thread.sleep(500);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while waiting for remote port " + port, e);
            }
        }
        throw new UncheckedIOException(
                new IOException("Timeout waiting for port " + port + " on " + sshExecutor.host()));
    }

    @Override
    protected void doStart(@NonNull final Duration timeout) {
        throwIfInLifecycle(LifeCycle.RUNNING, "Node has already been started.");
        throwIfInLifecycle(LifeCycle.DESTROYED, "Node has already been destroyed.");

        log.info("Starting node {}...", selfId);

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

        nodeCommBlockingStub = NodeCommunicationServiceGrpc.newBlockingStub(nodeCommChannel);

        final NodeCommunicationServiceStub stub = NodeCommunicationServiceGrpc.newStub(nodeCommChannel);
        stub.start(startRequest, new StreamObserver<>() {
            @Override
            public void onNext(final EventMessage value) {
                receivedEvents.add(value);
            }

            @Override
            public void onError(@NonNull final Throwable error) {
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

    @Override
    @SuppressWarnings("ResultOfMethodCallIgnored")
    protected void doKillImmediately(@NonNull final Duration timeout) {
        log.info("Killing node {} immediately...", selfId);
        try {
            lifeCycle = SHUTDOWN;
            final KillImmediatelyRequest request = KillImmediatelyRequest.newBuilder()
                    .setTimeoutSeconds((int) timeout.getSeconds())
                    .build();
            containerControlBlockingStub.withDeadlineAfter(timeout).killImmediately(request);
            platformStatus = null;
            log.info("Node {} has been killed", selfId);
        } catch (final Exception e) {
            fail("Failed to kill node %d immediately".formatted(selfId.id()), e);
        }
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    @Override
    protected void doSendQuiescenceCommand(@NonNull final QuiescenceCommand command, @NonNull final Duration timeout) {
        log.info("Sending quiescence command {} on node {}", command, selfId);
        final org.hiero.sloth.fixtures.container.proto.QuiescenceCommand dto =
                switch (command) {
                    case QUIESCE -> org.hiero.sloth.fixtures.container.proto.QuiescenceCommand.QUIESCE;
                    case BREAK_QUIESCENCE ->
                        org.hiero.sloth.fixtures.container.proto.QuiescenceCommand.BREAK_QUIESCENCE;
                    case DONT_QUIESCE -> org.hiero.sloth.fixtures.container.proto.QuiescenceCommand.DONT_QUIESCE;
                };
        nodeCommBlockingStub
                .withDeadlineAfter(timeout)
                .quiescenceCommandUpdate(
                        QuiescenceRequest.newBuilder().setCommand(dto).build());
    }

    @Override
    public void submitTransactions(@NonNull final List<SlothTransaction> transactions) {
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

    @Override
    public void startTransactionGeneration(final double tps, @NonNull final SlothTransactionType type) {
        final TransactionType protoType = type == SlothTransactionType.BENCHMARK
                ? TransactionType.BENCHMARK_TRANSACTION
                : TransactionType.EMPTY_TRANSACTION;
        final StartTransactionGenerationRequest request = StartTransactionGenerationRequest.newBuilder()
                .setTps(tps)
                .setType(protoType)
                .build();
        //noinspection ResultOfMethodCallIgnored
        nodeCommBlockingStub.startTransactionGeneration(request);
    }

    @Override
    public long stopTransactionGeneration() {
        final StopTransactionGenerationResponse response = nodeCommBlockingStub.stopTransactionGeneration(
                Empty.newBuilder().build());
        return response.getGeneratedCount();
    }

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

    @Override
    @NonNull
    public RemoteNodeConfiguration configuration() {
        return nodeConfiguration;
    }

    @Override
    @NonNull
    public SingleNodeLogResult newLogResult() {
        return resultsCollector.newLogResult();
    }

    @Override
    @NonNull
    public SingleNodePlatformStatusResult newPlatformStatusResult() {
        return resultsCollector.newStatusProgression();
    }

    @Override
    public void startProfiling(
            @NonNull final String outputFilename,
            @NonNull final Duration samplingInterval,
            @NonNull final ProfilerEvent... events) {
        profiler.startProfiling(outputFilename, samplingInterval, events);
    }

    @Override
    public void stopProfiling() {
        profiler.stopProfiling();
    }

    /**
     * Shuts down the remote node and cleans up resources. Downloads artifacts from the remote host, kills the remote
     * Java process, and optionally cleans up remote files. This method is idempotent.
     */
    void destroy() {
        // Download artifacts from the remote host
        downloadRemoteArtifacts();

        log.info("Destroying remote node {}...", selfId);
        containerControlChannel.shutdownNow();
        nodeCommChannel.shutdownNow();

        // Kill SSH port-forward tunnels
        if (controlPortForward != null && controlPortForward.isAlive()) {
            controlPortForward.destroyForcibly();
        }
        if (commPortForward != null && commPortForward.isAlive()) {
            commPortForward.destroyForcibly();
        }

        // Kill the remote DockerMain process
        killRemoteProcess();

        if (cleanupOnDestroy) {
            sshExecutor.exec("rm", "-rf", remoteNodeDir);
            log.info("Cleaned up remote directory {}", remoteNodeDir);
        }

        resultsCollector.destroy();
        platformStatus = null;
        lifeCycle = DESTROYED;
    }

    private void killRemoteProcess() {
        // Kill any Java process using our working directory. Use a single string passed to SSH
        // so the shell on the remote side interprets the entire pipeline correctly.
        final String killCommand =
                String.format("pkill -9 -f 'sloth.workdir=%s' 2>/dev/null; sleep 1; true", remoteNodeDir);
        sshExecutor.exec(killCommand);
        log.info("Killed remote processes for node {} on {}", selfId, sshExecutor.host());
    }

    private void downloadRemoteArtifacts() {
        try {
            Files.createDirectories(localOutputDirectory);
        } catch (final IOException e) {
            log.warn("Failed to create local output directory: {}", localOutputDirectory, e);
            return;
        }
        downloadFile("output/swirlds.log");
        downloadFile("output/swirlds-hashstream/swirlds-hashstream.log");
        downloadFile("output/sloth.log");
        downloadFile("output/docker-main.log");
        downloadFile("data/stats/MainNetStats" + selfId.id() + ".csv");
        downloadFile("data/stats/metrics.txt");
    }

    private void downloadFile(@NonNull final String relativePath) {
        final String remotePath = remoteNodeDir + relativePath;
        final Path localPath = localOutputDirectory.resolve(relativePath);

        // Check if file exists remotely
        final SshExecutor.ExecResult result = sshExecutor.exec("test", "-f", remotePath, "&&", "echo", "exists");
        if (result.exitCode() == 0 && result.stdout().trim().equals("exists")) {
            try {
                Files.createDirectories(localPath.getParent());
                sshExecutor.download(remotePath, localPath);
            } catch (final Exception e) {
                log.warn("Failed to download file from node {}: {}", selfId.id(), remotePath, e);
            }
        } else {
            log.warn("File not found on node {}: {}", selfId.id(), remotePath);
        }
    }

    @Override
    @NonNull
    protected TimeManager timeManager() {
        return timeManager;
    }

    @Override
    @NonNull
    protected Random random() {
        return random;
    }

    @Override
    public void tick(@NonNull final Instant now) {
        EventMessage event;
        while ((event = receivedEvents.poll()) != null) {
            switch (event.getEventCase()) {
                case LOG_ENTRY -> resultsCollector.addLogEntry(ProtobufConverter.toPlatform(event.getLogEntry()));
                case PLATFORM_STATUS_CHANGE -> handlePlatformChange(event);
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
