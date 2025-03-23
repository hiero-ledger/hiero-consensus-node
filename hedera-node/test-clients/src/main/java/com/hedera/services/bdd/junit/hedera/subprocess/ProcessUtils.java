// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.hedera.subprocess;

import static com.hedera.services.bdd.junit.hedera.subprocess.ConditionStatus.REACHED;
import static com.hedera.services.bdd.junit.hedera.subprocess.ConditionStatus.UNREACHABLE;
import static com.hedera.services.bdd.junit.hedera.utils.WorkingDirUtils.DATA_DIR;
import static com.hedera.services.bdd.junit.hedera.utils.WorkingDirUtils.ERROR_REDIRECT_FILE;
import static com.hedera.services.bdd.junit.hedera.utils.WorkingDirUtils.OUTPUT_DIR;
import static com.hedera.services.bdd.junit.hedera.utils.WorkingDirUtils.guaranteedExtantFile;
import static java.lang.ProcessBuilder.Redirect.DISCARD;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import com.hedera.services.bdd.junit.hedera.HederaNode;
import com.hedera.services.bdd.junit.hedera.NodeMetadata;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.status.PlatformStatus;
import org.junit.jupiter.api.Assertions;

public class ProcessUtils {
    private static final Logger log = LogManager.getLogger(ProcessUtils.class);

    private static final int FIRST_AGENT_PORT = 5005;
    private static final long NODE_ID_TO_SUSPEND = -1;
    private static final String[] EMPTY_STRING_ARRAY = new String[0];
    public static final String SAVED_STATES_DIR = "saved";
    public static final String RECORD_STREAMS_DIR = "recordStreams";
    public static final String BLOCK_STREAMS_DIR = "blockStreams";
    private static final long WAIT_SLEEP_MILLIS = 100L;

    public static final Executor EXECUTOR = Executors.newCachedThreadPool();

    private ProcessUtils() {
        throw new UnsupportedOperationException("Utility Class");
    }

    /**
     * Waits for the given node to reach the specified status within the given timeout.
     * Throws an assertion error if the status is not reached within the timeout.
     *
     * @param node the node to wait for
     * @param timeout the timeout duration
     * @param statuses the status to wait for
     */
    public static void awaitStatus(
            @NonNull final HederaNode node,
            @NonNull final Duration timeout,
            @NonNull final PlatformStatus... statuses) {
        final AtomicReference<NodeStatus> lastStatus = new AtomicReference<>();
        log.info("Waiting for node '{}' to be {} within {}", node.getName(), Arrays.toString(statuses), timeout);
        try {
            node.statusFuture(lastStatus::set, statuses).get(timeout.toMillis(), MILLISECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            Assertions.fail("Node '" + node.getName() + "' did not reach status any of " + Arrays.toString(statuses)
                    + " within " + timeout
                    + "\n  Final status: " + lastStatus.get()
                    + "\n  Cause       : " + e);
        }
        log.info("Node '{}' is {}", node.getName(), lastStatus.get());
    }

    /**
     * Destroys any process that appears to be a node started from the given metadata, based on the
     * process command being {@code java} and having a last argument matching the node ID.
     *
     * @param nodeId the id of the node whose processes should be destroyed
     */
    public static void destroyAnySubProcessNodeWithId(final long nodeId) {
        ProcessHandle.allProcesses()
                .filter(p -> p.info().command().orElse("").contains("java"))
                .filter(p -> endsWith(p.info().arguments().orElse(EMPTY_STRING_ARRAY), Long.toString(nodeId)))
                .forEach(ProcessHandle::destroyForcibly);
    }

    /**
     * Starts a sub-process node from the given metadata and main class reference, and returns its {@link ProcessHandle}.
     *
     * @param metadata the metadata of the node to start
     * @param configVersion the version of the configuration to use
     * @return the {@link ProcessHandle} of the started node
     */
    public static ProcessHandle startSubProcessNodeFrom(@NonNull final NodeMetadata metadata, final int configVersion) {
        return startSubProcessNodeFrom(metadata, configVersion, Map.of());
    }

    /**
     * Returns any environment overrides specified by the {@code hapi.spec.test.overrides} system property.
     * @return a map of environment variable overrides
     */
    public static Map<String, String> prCheckOverrides() {
        return Optional.ofNullable(System.getProperty("hapi.spec.test.overrides"))
                .map(testOverrides -> Arrays.stream(testOverrides.split(","))
                        .map(override -> override.split("="))
                        .collect(Collectors.toMap(parts -> parts[0], parts -> parts[1])))
                .orElse(Map.of());
    }

    /**
     * Starts a sub-process node from the given metadata and main class reference with the requested environment
     * overrides, and returns its {@link ProcessHandle}.
     *
     * @param metadata the metadata of the node to start
     * @param configVersion the version of the configuration to use
     * @param envOverrides the environment variables to override
     * @return the {@link ProcessHandle} of the started node
     */
    public static ProcessHandle startSubProcessNodeFrom(
            @NonNull final NodeMetadata metadata,
            final int configVersion,
            @NonNull final Map<String, String> envOverrides) {
        final var builder = new ProcessBuilder();
        final var environment = builder.environment();
        environment.put("LC_ALL", "en.UTF-8");
        environment.put("LANG", "en_US.UTF-8");
        environment.put("grpc.port", Integer.toString(metadata.grpcPort()));
        environment.put("grpc.nodeOperatorPort", Integer.toString(metadata.grpcNodeOperatorPort()));
        environment.put("hedera.config.version", Integer.toString(configVersion));
        environment.put("TSS_LIB_NUM_OF_CORES", Integer.toString(1));
        // Include an PR check overrides from build.gradle.kts
        environment.putAll(prCheckOverrides());
        // Give any overrides set by the test author the highest priority
        environment.putAll(envOverrides);
        try {
            final var redirectFile = guaranteedExtantFile(
                    metadata.workingDirOrThrow().resolve(OUTPUT_DIR).resolve(ERROR_REDIRECT_FILE));
            builder.command(javaCommandLineFor(metadata))
                    .directory(metadata.workingDirOrThrow().toFile());
            // When in CI redirect errors to a log for debugging; when running locally inherit IO
            if (System.getenv("CI") != null) {
                builder.redirectError(redirectFile).redirectOutput(DISCARD);
            } else {
                builder.inheritIO();
            }
            return builder.start().toHandle();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<String> javaCommandLineFor(@NonNull final NodeMetadata metadata) {
        final List<String> commandLine = new ArrayList<>();
        commandLine.add(ProcessHandle.current().info().command().orElseThrow());
        // Only activate JDWP if not in CI
        if (System.getenv("CI") == null) {
            commandLine.add("-agentlib:jdwp=transport=dt_socket,server=y,suspend="
                    + (metadata.nodeId() == NODE_ID_TO_SUSPEND ? "y" : "n") + ",address=*:"
                    + (FIRST_AGENT_PORT + metadata.nodeId()));
        }
        commandLine.addAll(List.of(
                "-classpath",
                // Use the same classpath that started this process, excluding test-clients
                currentNonTestClientClasspath(),
                // JVM system
                "-Dfile.encoding=UTF-8",
                "-Dprometheus.endpointPortNumber=" + metadata.prometheusPort(),
                "-Dhedera.recordStream.logDir=" + DATA_DIR + "/" + RECORD_STREAMS_DIR,
                "-Dhedera.profiles.active=DEV",
                "com.hedera.node.app.ServicesMain",
                "-local",
                Long.toString(metadata.nodeId())));
        return commandLine;
    }

    /**
     * Returns a future that resolves when the given condition is true.
     *
     * @param condition the condition to wait for
     * @return a future that resolves when the condition is true or the timeout is reached
     */
    public static CompletableFuture<Void> conditionFuture(@NonNull final BooleanSupplier condition) {
        return conditionFuture(condition, () -> WAIT_SLEEP_MILLIS);
    }

    /**
     * Returns a future that resolves when the given condition is true, backing off checking the condition by the
     * number of milliseconds returned by the given supplier.
     *
     * @param condition the condition to wait for
     * @param checkBackoffMs the supplier of the number of milliseconds to back off between checks
     * @return a future that resolves when the condition is true or the timeout is reached
     */
    public static CompletableFuture<Void> conditionFuture(
            @NonNull final BooleanSupplier condition, @NonNull final LongSupplier checkBackoffMs) {
        return conditionFuture(() -> condition.getAsBoolean() ? REACHED : ConditionStatus.PENDING, checkBackoffMs);
    }

    /**
     * Returns a future that resolves when the given condition is reached, or fails when it becomes unreachable,
     * backing off checking the condition by the number of milliseconds returned by the given supplier.
     *
     * @param condition the condition to wait for
     * @param checkBackoffMs the supplier of the number of milliseconds to back off between checks
     * @return a future that resolves when the condition is true or the timeout is reached
     */
    public static CompletableFuture<Void> conditionFuture(
            @NonNull final Supplier<ConditionStatus> condition, @NonNull final LongSupplier checkBackoffMs) {
        requireNonNull(condition);
        requireNonNull(checkBackoffMs);
        return CompletableFuture.runAsync(
                () -> {
                    ConditionStatus status;
                    while ((status = condition.get()) != REACHED) {
                        if (status == UNREACHABLE) {
                            throw new IllegalStateException("Condition is unreachable");
                        }
                        try {
                            MILLISECONDS.sleep(checkBackoffMs.getAsLong());
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException("Interrupted while waiting for condition", e);
                        }
                    }
                },
                EXECUTOR);
    }

    private static String currentNonTestClientClasspath() {
        // Could have been launched with -cp, or -classpath, or @/path/to/classpathFile.txt, or maybe module path?
        final var args = ProcessHandle.current().info().arguments().orElse(EMPTY_STRING_ARRAY);

        String classpath = "";
        for (int i = 0; i < args.length; i++) {
            final var arg = args[i];
            if (arg.startsWith("@")) {
                try {
                    final var fileContents = Files.readString(Path.of(arg.substring(1)));
                    classpath = fileContents.substring(fileContents.indexOf('/'));
                    break;
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            } else if (arg.equals("-cp") || arg.equals("-classpath")) {
                classpath = args[i + 1];
                break;
            }
        }
        if (classpath.isBlank()) {
            throw new IllegalStateException("Cannot discover the classpath. Was --module-path used instead?");
        }
        return Arrays.stream(classpath.split(":"))
                .map(String::trim) // may have picked up a '\n' in the original classpath String
                .filter(s -> !s.contains("test-clients"))
                .collect(Collectors.joining(":"));
    }

    private static boolean endsWith(final String[] args, final String lastArg) {
        return args.length > 0 && args[args.length - 1].equals(lastArg);
    }
}
