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
import com.hedera.services.bdd.suites.regression.system.LifecycleTest;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
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
import java.util.zip.ZipFile;
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
    private static final String SEMANTIC_VERSION_RESOURCE = "semantic-version.properties";
    private static final String SERVICES_VERSION_PROPERTY = "hedera.services.version";
    private static final String HAPI_PROTO_VERSION_PROPERTY = "hapi.proto.version";
    private static final String SERVICES_VERSION_OVERRIDE_PROPERTY = "hapi.spec.override.services.version";
    private static final String HAPI_PROTO_VERSION_OVERRIDE_PROPERTY = "hapi.spec.override.hapi.proto.version";
    private static final String NETWORK_ADMIN_IMPL_MODULE = "com.hedera.node.app.service.network.admin.impl";
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
        final var effectiveEnvOverrides = new HashMap<>(envOverrides);
        final var servicesVersionOverride = Optional.ofNullable(
                        effectiveEnvOverrides.get(LifecycleTest.SERVICES_VERSION_OVERRIDE_KEY))
                .orElse(System.getProperty(LifecycleTest.SERVICES_VERSION_OVERRIDE_PROPERTY));
        final var hapiVersionOverride = Optional.ofNullable(
                        effectiveEnvOverrides.get(LifecycleTest.HAPI_PROTO_VERSION_OVERRIDE_KEY))
                .orElse(System.getProperty(
                        LifecycleTest.HAPI_PROTO_VERSION_OVERRIDE_PROPERTY, servicesVersionOverride));
        final var effectiveModulePath = currentNonTestClientModulePath();
        final var semanticVersionOverridePatchPath =
                withSemanticVersionOverridePatchPath(metadata, effectiveEnvOverrides);
        effectiveEnvOverrides.remove(LifecycleTest.SERVICES_VERSION_OVERRIDE_KEY);
        effectiveEnvOverrides.remove(LifecycleTest.HAPI_PROTO_VERSION_OVERRIDE_KEY);
        environment.put("LC_ALL", "en.UTF-8");
        environment.put("LANG", "en_US.UTF-8");
        environment.put("grpc.port", Integer.toString(metadata.grpcPort()));
        environment.put("grpc.nodeOperatorPort", Integer.toString(metadata.grpcNodeOperatorPort()));
        environment.put("hedera.config.version", Integer.toString(configVersion));
        environment.put("RUST_BACKTRACE", "full");
        environment.put("TSS_LIB_NUM_OF_CORES", Integer.toString(1));
        // Set path to the (unzipped) https://builds.hedera.com/tss/hiero/wraps/v0.2/wraps-v0.2.0.tar.gz,
        // e.g. "/Users/hincadenza/misc/wraps-v0.2.0", to get the WRAPS library ready to produce proofs
        environment.put("TSS_LIB_WRAPS_ARTIFACTS_PATH", System.getProperty("hapi.spec.tssLibWrapsArtifactsPath", ""));
        environment.put("hedera.shard", String.valueOf(metadata.accountId().shardNum()));
        environment.put("hedera.realm", String.valueOf(metadata.accountId().realmNum()));
        // Include an PR check overrides from build.gradle.kts
        environment.putAll(prCheckOverrides());
        // Give any overrides set by the test author the highest priority
        environment.putAll(effectiveEnvOverrides);
        try {
            final var redirectFile = guaranteedExtantFile(
                    metadata.workingDirOrThrow().resolve(OUTPUT_DIR).resolve(ERROR_REDIRECT_FILE));
            final var commandLine = javaCommandLineFor(
                    metadata,
                    effectiveModulePath,
                    semanticVersionOverridePatchPath,
                    servicesVersionOverride,
                    hapiVersionOverride);
            builder.command(commandLine).directory(metadata.workingDirOrThrow().toFile());
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

    private static List<String> javaCommandLineFor(
            @NonNull final NodeMetadata metadata,
            @NonNull final String modulePath,
            final String semanticVersionOverridePatchPath,
            final String servicesVersionOverride,
            final String hapiVersionOverride) {
        final List<String> commandLine = new ArrayList<>();
        commandLine.add(ProcessHandle.current().info().command().orElseThrow());
        // Only activate JDWP if not in CI
        if (System.getenv("CI") == null) {
            commandLine.add("-agentlib:jdwp=transport=dt_socket,server=y,suspend="
                    + (metadata.nodeId() == NODE_ID_TO_SUSPEND ? "y" : "n") + ",address=*:"
                    + (FIRST_AGENT_PORT + metadata.nodeId()));
        }
        if (semanticVersionOverridePatchPath != null) {
            commandLine.add("--patch-module");
            commandLine.add(NETWORK_ADMIN_IMPL_MODULE + "=" + semanticVersionOverridePatchPath);
        }
        if (servicesVersionOverride != null && !servicesVersionOverride.isBlank()) {
            commandLine.add("-D" + SERVICES_VERSION_OVERRIDE_PROPERTY + "=" + servicesVersionOverride);
        }
        if (hapiVersionOverride != null && !hapiVersionOverride.isBlank()) {
            commandLine.add("-D" + HAPI_PROTO_VERSION_OVERRIDE_PROPERTY + "=" + hapiVersionOverride);
        }
        commandLine.addAll(List.of(
                "--module-path",
                modulePath,
                // JVM system
                "-Dfile.encoding=UTF-8",
                "-Dprometheus.endpointPortNumber=" + metadata.prometheusPort(),
                "-Dhedera.recordStream.logDir=" + DATA_DIR + "/" + RECORD_STREAMS_DIR,
                "-Dhedera.recordStream.wrappedRecordHashesDir=" + DATA_DIR + "/wrappedRecordHashes",
                "-Dhedera.profiles.active=DEV",
                "--module",
                "com.hedera.node.app/com.hedera.node.app.ServicesMain",
                "-local",
                Long.toString(metadata.nodeId())));
        return commandLine;
    }

    private static String withSemanticVersionOverrideModulePath(
            @NonNull final NodeMetadata metadata, @NonNull final Map<String, String> envOverrides) {
        final var targetServicesVersion = Optional.ofNullable(
                        envOverrides.get(LifecycleTest.SERVICES_VERSION_OVERRIDE_KEY))
                .orElse(System.getProperty(LifecycleTest.SERVICES_VERSION_OVERRIDE_PROPERTY));
        if (targetServicesVersion == null || targetServicesVersion.isBlank()) {
            return currentNonTestClientModulePath();
        }
        final var targetHapiVersion = Optional.ofNullable(
                        envOverrides.get(LifecycleTest.HAPI_PROTO_VERSION_OVERRIDE_KEY))
                .orElse(System.getProperty(LifecycleTest.HAPI_PROTO_VERSION_OVERRIDE_PROPERTY, targetServicesVersion));
        final var modulePathEntries = currentNonTestClientModulePath().split(File.pathSeparator);
        final var rewrittenEntries = new ArrayList<String>(modulePathEntries.length);
        final var overrideDir = metadata.workingDirOrThrow().resolve(DATA_DIR).resolve("semanticVersionOverrideJars");
        var rewrittenJarCount = 0;
        var rewrittenDirCount = 0;
        try {
            Files.createDirectories(overrideDir);
            for (final var entry : modulePathEntries) {
                final var entryPath = Path.of(entry);
                if (Files.exists(entryPath)) {
                    if (entry.endsWith(".jar")) {
                        final var rewrittenJar = rewriteJarWithSemanticVersionOverride(
                                entryPath, overrideDir, targetServicesVersion, targetHapiVersion);
                        if (rewrittenJar == null) {
                            rewrittenEntries.add(entry);
                        } else {
                            rewrittenEntries.add(rewrittenJar.toString());
                            rewrittenJarCount++;
                        }
                    } else if (Files.isDirectory(entryPath)) {
                        final var rewrittenDir = rewriteDirectoryWithSemanticVersionOverride(
                                entryPath, overrideDir, targetServicesVersion, targetHapiVersion);
                        if (rewrittenDir == null) {
                            rewrittenEntries.add(entry);
                        } else {
                            rewrittenEntries.add(rewrittenDir.toString());
                            rewrittenDirCount++;
                        }
                    } else {
                        rewrittenEntries.add(entry);
                    }
                } else {
                    rewrittenEntries.add(entry);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed preparing semantic-version override jars", e);
        }
        log.warn(
                "Node {} semantic-version override using services={}, hapi={} (rewrote {} jars, {} directories)",
                metadata.nodeId(),
                targetServicesVersion,
                targetHapiVersion,
                rewrittenJarCount,
                rewrittenDirCount);
        return String.join(File.pathSeparator, rewrittenEntries);
    }

    private static String withSemanticVersionOverridePatchPath(
            @NonNull final NodeMetadata metadata, @NonNull final Map<String, String> envOverrides) {
        final var targetServicesVersion = Optional.ofNullable(
                        envOverrides.get(LifecycleTest.SERVICES_VERSION_OVERRIDE_KEY))
                .orElse(System.getProperty(LifecycleTest.SERVICES_VERSION_OVERRIDE_PROPERTY));
        if (targetServicesVersion == null || targetServicesVersion.isBlank()) {
            return null;
        }
        final var targetHapiVersion = Optional.ofNullable(
                        envOverrides.get(LifecycleTest.HAPI_PROTO_VERSION_OVERRIDE_KEY))
                .orElse(System.getProperty(LifecycleTest.HAPI_PROTO_VERSION_OVERRIDE_PROPERTY, targetServicesVersion));
        final var classPathDir =
                metadata.workingDirOrThrow().resolve(DATA_DIR).resolve("semanticVersionOverrideClasspath");
        final var classPathFile = classPathDir.resolve(SEMANTIC_VERSION_RESOURCE);
        try {
            Files.createDirectories(classPathDir);
            final var properties = new Properties();
            properties.setProperty(SERVICES_VERSION_PROPERTY, targetServicesVersion);
            properties.setProperty(HAPI_PROTO_VERSION_PROPERTY, targetHapiVersion);
            try (final var out = Files.newOutputStream(classPathFile)) {
                properties.store(out, "Generated by ProcessUtils");
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed preparing semantic-version override patch path", e);
        }
        log.warn(
                "Node {} semantic-version patch using services={}, hapi={} at {}",
                metadata.nodeId(),
                targetServicesVersion,
                targetHapiVersion,
                classPathDir);
        return classPathDir.toString();
    }

    private static Path rewriteJarWithSemanticVersionOverride(
            @NonNull final Path sourceJar,
            @NonNull final Path overrideDir,
            @NonNull final String servicesVersion,
            @NonNull final String hapiVersion)
            throws IOException {
        // Avoid mounting a zip filesystem for the shared source JAR, which can race across nodes.
        try (final var zipFile = new ZipFile(sourceJar.toFile())) {
            if (zipFile.getEntry(SEMANTIC_VERSION_RESOURCE) == null) {
                return null;
            }
        }
        final var uniqueSuffix = Integer.toHexString(
                sourceJar.toAbsolutePath().normalize().toString().hashCode());
        final var sourceName = sourceJar.getFileName().toString();
        final var targetName = sourceName.endsWith(".jar")
                ? sourceName.substring(0, sourceName.length() - 4) + "-" + uniqueSuffix + ".jar"
                : sourceName + "-" + uniqueSuffix;
        final var targetJar = overrideDir.resolve(targetName);
        Files.copy(sourceJar, targetJar, StandardCopyOption.REPLACE_EXISTING);
        final var targetUri = URI.create("jar:" + targetJar.toUri());
        try (final FileSystem zipFs = java.nio.file.FileSystems.newFileSystem(targetUri, Map.of())) {
            final var semverEntry = zipFs.getPath("/" + SEMANTIC_VERSION_RESOURCE);
            if (!Files.exists(semverEntry)) {
                return null;
            }
            final var properties = new Properties();
            try (final var in = Files.newInputStream(semverEntry)) {
                properties.load(in);
            }
            properties.setProperty(SERVICES_VERSION_PROPERTY, servicesVersion);
            properties.setProperty(HAPI_PROTO_VERSION_PROPERTY, hapiVersion);
            try (final var out = Files.newOutputStream(semverEntry)) {
                properties.store(out, "Generated by ProcessUtils");
            }
        }
        return targetJar;
    }

    private static Path rewriteDirectoryWithSemanticVersionOverride(
            @NonNull final Path sourceDir,
            @NonNull final Path overrideDir,
            @NonNull final String servicesVersion,
            @NonNull final String hapiVersion)
            throws IOException {
        final var semverEntry = sourceDir.resolve(SEMANTIC_VERSION_RESOURCE);
        if (!Files.exists(semverEntry)) {
            return null;
        }
        final var uniqueSuffix = Integer.toHexString(
                sourceDir.toAbsolutePath().normalize().toString().hashCode());
        final var targetDir =
                overrideDir.resolve(sourceDir.getFileName().toString() + "-" + uniqueSuffix + "-semver-override");
        if (Files.exists(targetDir)) {
            try (final var files = Files.walk(targetDir)) {
                files.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(path -> {
                    try {
                        Files.delete(path);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
            }
        }
        try (final var files = Files.walk(sourceDir)) {
            files.forEach(path -> {
                final var relative = sourceDir.relativize(path);
                final var targetPath = targetDir.resolve(relative.toString());
                try {
                    if (Files.isDirectory(path)) {
                        Files.createDirectories(targetPath);
                    } else {
                        Files.copy(path, targetPath, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
        final var overrideSemverEntry = targetDir.resolve(SEMANTIC_VERSION_RESOURCE);
        final var properties = new Properties();
        try (final var in = Files.newInputStream(overrideSemverEntry)) {
            properties.load(in);
        }
        properties.setProperty(SERVICES_VERSION_PROPERTY, servicesVersion);
        properties.setProperty(HAPI_PROTO_VERSION_PROPERTY, hapiVersion);
        try (final var out = Files.newOutputStream(overrideSemverEntry)) {
            properties.store(out, "Generated by ProcessUtils");
        }
        return targetDir;
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

    private static String currentNonTestClientModulePath() {
        // When started through Gradle, this was launched with @/path/to/pathFile.txt.
        // This also works when launched with --module-path, -cp, or -classpath.
        final var args = ProcessHandle.current().info().arguments().orElse(EMPTY_STRING_ARRAY);

        String moduleOrClassPath = "";
        for (int i = 0; i < args.length; i++) {
            final var arg = args[i];
            if (arg.startsWith("@")) {
                try {
                    final var fileContents = Files.readString(Path.of(arg.substring(1)));
                    moduleOrClassPath = fileContents.substring(fileContents.indexOf('/'));
                    break;
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            } else if (arg.equals("--module-path") || arg.equals("-cp") || arg.equals("-classpath")) {
                moduleOrClassPath = args[i + 1];
                break;
            }
        }
        if (moduleOrClassPath.isBlank()) {
            throw new IllegalStateException("Cannot discover module path or classpath.");
        }
        return Arrays.stream(moduleOrClassPath.split(File.pathSeparator))
                .map(String::trim) // may have picked up a '\n' in the original path String
                .filter(s -> !s.contains("test-clients"))
                .collect(Collectors.joining(File.pathSeparator));
    }

    private static boolean endsWith(final String[] args, final String lastArg) {
        return args.length > 0 && args[args.length - 1].equals(lastArg);
    }
}
