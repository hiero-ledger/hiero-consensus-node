// SPDX-License-Identifier: Apache-2.0
package org.hiero.sloth.fixtures.container.docker;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static org.hiero.sloth.fixtures.container.docker.ConsensusNodeMain.STARTED_MARKER_FILE;
import static org.hiero.sloth.fixtures.container.docker.ConsensusNodeMain.STARTED_MARKER_FILE_NAME;
import static org.hiero.sloth.fixtures.container.utils.ContainerConstants.CONTAINER_APP_WORKING_DIR;
import static org.hiero.sloth.fixtures.container.utils.ContainerConstants.ENV_SLOTH_JAVA;
import static org.hiero.sloth.fixtures.container.utils.ContainerConstants.ENV_SLOTH_WORKDIR;
import static org.hiero.sloth.fixtures.container.utils.ContainerConstants.getNodeCommunicationDebugPort;

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
import java.util.regex.Pattern;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.main.model.NodeId;
import org.hiero.sloth.fixtures.container.proto.ContainerControlServiceInterface;
import org.hiero.sloth.fixtures.container.proto.Empty;
import org.hiero.sloth.fixtures.container.proto.InitRequest;
import org.hiero.sloth.fixtures.container.proto.KillImmediatelyRequest;
import org.hiero.sloth.fixtures.container.proto.PingResponse;

/**
 * gRPC service implementation for communication between the test framework and the container to start and stop the
 * consensus node.
 */
public final class DockerManager implements ContainerControlServiceInterface {

    /** Logger */
    private static final Logger log = LogManager.getLogger(DockerManager.class);

    /** The working directory resolved from system property or default, always ending with '/'. */
    private static final String WORK_DIR =
            normalizeDir(System.getProperty(ENV_SLOTH_WORKDIR, CONTAINER_APP_WORKING_DIR));

    /** The string location of the docker application jar */
    private static final String DOCKER_APP_JAR = WORK_DIR + "apps/DockerApp.jar";

    /** The string location of the docker application libraries */
    private static final String DOCKER_APP_LIBS = WORK_DIR + "lib/*";

    /**
     * The main class in the docker application jar that starts the
     * {@link org.hiero.sloth.fixtures.container.proto.NodeCommunicationServiceInterface}
     */
    private static final String CONSENSUS_NODE_MAIN_CLASS =
            "org.hiero.sloth.fixtures.container.docker.ConsensusNodeMain";

    /**
     * Pattern for validating JVM arguments. Only allows arguments starting with '-' followed by
     * alphanumeric characters, dots, colons, equals, commas, slashes, and other safe characters.
     */
    private static final Pattern VALID_JVM_ARG_PATTERN = Pattern.compile("-[a-zA-Z][a-zA-Z0-9_.,:=+/\\\\*@\\- ]*");

    /**
     * The maximum duration to wait for the marker file written by the consensus node main class to indicate it's
     * service is up and running.
     */
    private final Duration MAX_MARKER_FILE_WAIT_TIME = Duration.ofSeconds(10);

    /**
     * The ID of the consensus node in this container. The ID must not be changed even between restarts.
     */
    private NodeId selfId;

    /** The process running the {@link org.hiero.sloth.fixtures.container.proto.NodeCommunicationServiceInterface} */
    private Process process;

    /**
     * Initializes the consensus node manager and starts its gRPC server.
     *
     * @param request the initialization request containing the self node ID
     * @return an empty response confirming that the consensus node manager gRPC service is available
     * @throws GrpcException if the request is invalid, or if the consensus node process could not be started
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
        final int debugPort = getNodeCommunicationDebugPort(selfId);
        final String javaPath = System.getProperty(ENV_SLOTH_JAVA, "java");
        final List<String> command = new ArrayList<>(List.of(
                javaPath,
                "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:" + debugPort,
                "-Djdk.attach.allowAttachSelf=true",
                "-XX:+StartAttachListener"));

        command.add("-Dsloth.workdir=" + WORK_DIR);
        final int commPort = Integer.getInteger("sloth.comm.port", 8081);
        command.add("-Dsloth.comm.port=" + commPort);

        if (request.gcLoggingEnabled()) {
            command.add("-Xlog:gc*:file=" + WORK_DIR + "output/gc.log:time");
        }
        for (final String jvmArg : request.jvmArgs()) {
            if (!VALID_JVM_ARG_PATTERN.matcher(jvmArg).matches()) {
                log.error("Invalid JVM argument rejected: {}", jvmArg);
                throw new GrpcException(GrpcStatus.INVALID_ARGUMENT, "Invalid JVM argument: " + jvmArg);
            }
            command.add(jvmArg);
        }

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

    private boolean waitForStartedMarkerFile() throws IOException, InterruptedException {
        final Instant deadline = Instant.now().plus(MAX_MARKER_FILE_WAIT_TIME);
        try (final WatchService watchService = FileSystems.getDefault().newWatchService()) {
            Path.of(WORK_DIR).register(watchService, ENTRY_CREATE);
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

    private static String normalizeDir(@NonNull final String dir) {
        return dir.endsWith("/") ? dir : dir + "/";
    }

    /**
     * Immediately terminates the platform.
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
}
