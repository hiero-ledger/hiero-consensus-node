// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.otter.docker.app;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static org.hiero.consensus.otter.docker.app.ConsensusNodeMain.STARTED_MARKER_FILE;
import static org.hiero.consensus.otter.docker.app.ConsensusNodeMain.STARTED_MARKER_FILE_NAME;
import static org.hiero.otter.fixtures.container.utils.ContainerConstants.CONTAINER_APP_WORKING_DIR;
import static org.hiero.otter.fixtures.container.utils.ContainerConstants.getNodeCommunicationDebugPort;

import com.hedera.pbj.runtime.grpc.GrpcException;
import com.hedera.pbj.runtime.grpc.GrpcStatus;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.main.model.NodeId;
import org.hiero.consensus.otter.docker.app.platform.NodeCommunicationService;
import org.hiero.otter.fixtures.container.proto.ContainerControlServiceInterface;
import org.hiero.otter.fixtures.container.proto.Empty;
import org.hiero.otter.fixtures.container.proto.InitRequest;
import org.hiero.otter.fixtures.container.proto.KillImmediatelyRequest;
import org.hiero.otter.fixtures.container.proto.PingResponse;
import org.hiero.otter.fixtures.container.proto.ThreadDumpResponse;

/**
 * gRPC service implementation for communication between the test framework and the container to start and stop the
 * consensus node.
 * <p>
 * This service handles incoming messages to initialize the {@link NodeCommunicationService} which handles communication
 * with the consensus node itself.
 */
public final class DockerManager implements ContainerControlServiceInterface {

    /** Logger */
    private static final Logger log = LogManager.getLogger(DockerManager.class);

    /** The string location of the docker application jar */
    private static final String DOCKER_APP_JAR = CONTAINER_APP_WORKING_DIR + "apps/DockerApp.jar";

    /** The string location of the docker application libraries */
    private static final String DOCKER_APP_LIBS = CONTAINER_APP_WORKING_DIR + "lib/*";

    /**
     * The main class in the docker application jar that starts the
     * {@link org.hiero.otter.fixtures.container.proto.NodeCommunicationServiceInterface}
     */
    private static final String CONSENSUS_NODE_MAIN_CLASS = "org.hiero.consensus.otter.docker.app.ConsensusNodeMain";

    /**
     * The maximum duration to wait for the marker file written by the consensus node main class to indicate it's
     * service is up and running.
     */
    private final Duration MAX_MARKER_FILE_WAIT_TIME = Duration.ofSeconds(10);

    /**
     * The ID of the consensus node in this container. The ID must not be changed even between restarts. In the future,
     * successive init calls should verify that the self ID is the same.
     */
    private NodeId selfId;

    /** The process running the {@link org.hiero.otter.fixtures.container.proto.NodeCommunicationServiceInterface} */
    private Process process;

    /**
     * Initializes the consensus node manager and starts its gRPC server. Once this request has completed, the consensus
     * node manager gRPC service is available to receive requests from the test framework.
     *
     * @param request the initialization request containing the self node ID
     * @return an empty response confirming that the consensus node manager gRPC service is available
     * @throws GrpcException if the node ID was changed, or if the consensus node process could not be started
     */
    @Override
    @NonNull
    public synchronized Empty init(@NonNull final InitRequest request) {
        log.info("Init request received");
        final NodeId requestSelfId = NodeId.of(request.selfIdOrElse(com.hedera.hapi.platform.state.NodeId.DEFAULT)
                .id());
        if (attemptingToChangeSelfId(requestSelfId)) {
            log.error(
                    "Node ID cannot be changed after initialization. Current ID: {}, requested ID: {}",
                    selfId.id(),
                    requestSelfId.id());
            throw new GrpcException(GrpcStatus.FAILED_PRECONDITION, "Node ID cannot be changed after initialization.");
        }

        this.selfId = requestSelfId;

        // Set the debug port for the node communication service as JVM arguments
        // This ensures only the main process gets the debug agent, not tools like jps/jcmd
        final int debugPort = getNodeCommunicationDebugPort(selfId);
        final List<String> command = new ArrayList<>(List.of(
                "java",
                "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:" + debugPort,
                "-Djdk.attach.allowAttachSelf=true",
                "-XX:+StartAttachListener"));

        if (request.gcLoggingEnabled()) {
            command.add("-Xlog:gc*:file=" + CONTAINER_APP_WORKING_DIR + "output/gc.log:time");
        }
        command.addAll(request.jvmArgs());

        command.addAll(List.of(
                "-cp", DOCKER_APP_JAR + ":" + DOCKER_APP_LIBS, CONSENSUS_NODE_MAIN_CLASS, String.valueOf(selfId.id())));

        final ProcessBuilder processBuilder = new ProcessBuilder(command);

        processBuilder.inheritIO();

        log.info("Starting NodeCommunicationService with self ID: {}", selfId.id());
        try {
            process = processBuilder.start();
        } catch (final IOException e) {
            log.error("Failed to start the consensus node process", e);
            throw new GrpcException(GrpcStatus.INTERNAL, "Failed to start the consensus node process", e);
        }
        log.info("NodeCommunicationService started. Waiting for gRPC service to initialize...");

        try {
            if (waitForStartedMarkerFile()) {
                log.info("NodeCommunicationService initialized");
                return Empty.DEFAULT;
            }
            if (!process.isAlive()) {
                log.error("Consensus node stopped prematurely. Errorcode: {}", process.exitValue());
            } else {
                log.error("Consensus node process started, but marker file was not detected in the allowed time");
            }
            throw new GrpcException(
                    GrpcStatus.INTERNAL,
                    "Consensus node process started, but marker file was not detected in the allowed time");
        } catch (final IOException e) {
            log.error("Failed to delete the started marker file", e);
            throw new GrpcException(GrpcStatus.INTERNAL, "Failed to delete the started marker file", e);
        } catch (final InterruptedException e) {
            log.warn("Interrupted while waiting for the started marker file", e);
            Thread.currentThread().interrupt();
            throw new GrpcException(GrpcStatus.INTERNAL, "Interrupted while waiting for the started marker file", e);
        } finally {
            log.info("Init request completed.");
        }
    }

    /**
     * Waits for the marker file to be created by the consensus node main class, indicating that the service is up and
     * ready to accept requests. Once the file is detected, it deletes the file to prevent it from being read on
     * subsequent starts of the node.
     *
     * @return true if the marker file was found and deleted, false if the wait timed out
     * @throws IOException if an I/O error occurs while deleting the marker file
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    private boolean waitForStartedMarkerFile() throws IOException, InterruptedException {
        final Instant deadline = Instant.now().plus(MAX_MARKER_FILE_WAIT_TIME);
        try (final WatchService watchService = FileSystems.getDefault().newWatchService()) {
            Path.of(CONTAINER_APP_WORKING_DIR).register(watchService, ENTRY_CREATE);
            while (Instant.now().isBefore(deadline)) {
                final Duration timeLeft = Duration.between(Instant.now(), deadline);
                final WatchKey watchKey = watchService.poll(timeLeft.toMillis(), TimeUnit.MILLISECONDS);
                if (watchKey == null) {
                    return false;
                }
                for (final WatchEvent<?> event : watchKey.pollEvents()) {
                    if (event.kind() == ENTRY_CREATE
                            && STARTED_MARKER_FILE_NAME.equals(event.context().toString())) {
                        log.info("Node Communication Service marker file found at {}", STARTED_MARKER_FILE);
                        Files.delete(STARTED_MARKER_FILE);
                        return true;
                    }
                }
                if (!watchKey.reset()) {
                    return false;
                }
            }
            return false;
        }
    }

    private boolean attemptingToChangeSelfId(@NonNull final NodeId requestedSelfId) {
        return this.selfId != null && selfId.id() != requestedSelfId.id();
    }

    /**
     * Immediately terminates the platform. The container and dispatcher are left intact to allow data to be gathered
     * for verification.
     *
     * @param request The request to terminate the platform.
     * @return an empty response confirming termination
     * @throws GrpcException if the consensus node process did not terminate within the requested timeout
     */
    @Override
    @NonNull
    public synchronized Empty killImmediately(@NonNull final KillImmediatelyRequest request) {
        log.info("Received kill request: {}", request);
        try {
            if (process != null) {
                process.destroyForcibly();
                if (!process.waitFor(request.timeoutSeconds(), TimeUnit.SECONDS)) {
                    log.error("Failed to terminate the consensus node process within the timeout period.");
                    throw new GrpcException(
                            GrpcStatus.INTERNAL,
                            "Failed to terminate the consensus node process within the timeout period.");
                }
            }
            return Empty.DEFAULT;
        } catch (final InterruptedException e) {
            log.error("Interrupted while waiting for the consensus node process to terminate.", e);
            Thread.currentThread().interrupt();
            throw new GrpcException(
                    GrpcStatus.INTERNAL, "Interrupted while waiting for the consensus node process to terminate.", e);
        } finally {
            log.info("Kill request completed.");
        }
    }

    /**
     * Pings the node communication service to check if it is alive.
     *
     * @param request An empty request.
     * @return the ping response
     */
    @Override
    @NonNull
    public PingResponse nodePing(@NonNull final Empty request) {
        log.info("Received ping request");
        final PingResponse response = PingResponse.newBuilder()
                .alive(process != null && process.isAlive())
                .build();
        log.debug("Ping request completed");
        return response;
    }

    /**
     * Captures a JVM thread dump of the consensus node process for diagnostics (for example, to investigate a node
     * that failed to recover after chaos). The dump is produced by {@code jcmd} attaching to the child process, so it
     * works even when the node's own threads are wedged. This is best-effort: on any failure, or if the process is not
     * alive, a short explanatory message is returned instead of a dump and the RPC still succeeds.
     *
     * @param request An empty request.
     * @return the thread dump
     */
    @Override
    @NonNull
    public ThreadDumpResponse dumpThreads(@NonNull final Empty request) {
        log.info("Received thread dump request");
        final Process nodeProcess;
        synchronized (this) {
            nodeProcess = process;
        }
        final String threadDump;
        if (nodeProcess == null || !nodeProcess.isAlive()) {
            threadDump = "(consensus node process is not alive)";
        } else {
            threadDump = captureThreadDump(nodeProcess.pid());
        }
        log.info("Thread dump request completed");
        return ThreadDumpResponse.newBuilder().threadDump(threadDump).build();
    }

    /**
     * Runs {@code jcmd <pid> Thread.print -l} against the consensus node process and returns its combined output.
     *
     * @param pid the process ID of the consensus node process to dump
     * @return the thread dump text, or a short explanatory message if the dump could not be captured
     */
    private static String captureThreadDump(final long pid) {
        final String jcmd =
                Path.of(System.getProperty("java.home"), "bin", "jcmd").toString();
        Path output = null;
        try {
            output = Files.createTempFile("thread-dump-" + pid + "-", ".txt");
            final Process dump = new ProcessBuilder(jcmd, Long.toString(pid), "Thread.print", "-l")
                    .redirectErrorStream(true)
                    .redirectOutput(output.toFile())
                    .start();
            if (!dump.waitFor(30, TimeUnit.SECONDS)) {
                dump.destroyForcibly();
                return "(thread dump timed out after 30s for pid " + pid + ")";
            }
            return Files.readString(output);
        } catch (final IOException e) {
            return "(thread dump failed for pid " + pid + ": " + e + ")";
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return "(thread dump interrupted for pid " + pid + ")";
        } finally {
            if (output != null) {
                try {
                    Files.deleteIfExists(output);
                } catch (final IOException e) {
                    log.warn("Failed to delete temporary thread dump file {}", output, e);
                }
            }
        }
    }
}
