// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.hedera.subprocess;

import static com.hedera.services.bdd.junit.hedera.ExternalPath.APPLICATION_LOG;
import static com.hedera.services.bdd.junit.hedera.ExternalPath.SWIRLDS_LOG;
import static com.hedera.services.bdd.junit.hedera.subprocess.ConditionStatus.PENDING;
import static com.hedera.services.bdd.junit.hedera.subprocess.ConditionStatus.REACHED;
import static com.hedera.services.bdd.junit.hedera.subprocess.NodeStatus.BindExceptionSeen.NO;
import static com.hedera.services.bdd.junit.hedera.subprocess.NodeStatus.BindExceptionSeen.YES;
import static com.hedera.services.bdd.junit.hedera.subprocess.NodeStatus.GrpcStatus.DOWN;
import static com.hedera.services.bdd.junit.hedera.subprocess.NodeStatus.GrpcStatus.NA;
import static com.hedera.services.bdd.junit.hedera.subprocess.NodeStatus.GrpcStatus.UP;
import static com.hedera.services.bdd.junit.hedera.subprocess.ProcessUtils.conditionFuture;
import static com.hedera.services.bdd.junit.hedera.subprocess.ProcessUtils.destroyAnySubProcessNodeWithId;
import static com.hedera.services.bdd.junit.hedera.subprocess.ProcessUtils.startSubProcessNodeFrom;
import static com.hedera.services.bdd.junit.hedera.subprocess.StatusLookupAttempt.newLogAttempt;
import static com.hedera.services.bdd.junit.hedera.utils.WorkingDirUtils.ERROR_REDIRECT_FILE;
import static com.hedera.services.bdd.junit.hedera.utils.WorkingDirUtils.OUTPUT_DIR;
import static java.util.Objects.requireNonNull;
import static org.hiero.consensus.model.status.PlatformStatus.ACTIVE;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.node.app.Hedera;
import com.hedera.services.bdd.junit.hedera.AbstractLocalNode;
import com.hedera.services.bdd.junit.hedera.HederaNode;
import com.hedera.services.bdd.junit.hedera.NodeMetadata;
import com.hedera.services.bdd.junit.hedera.subprocess.NodeStatus.BindExceptionSeen;
import com.hedera.services.bdd.suites.regression.system.LifecycleTest;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.status.PlatformStatus;

/**
 * A node running in its own OS process as a subprocess of the JUnit test runner.
 */
public class SubProcessNode extends AbstractLocalNode<SubProcessNode> implements HederaNode {
    private static final Logger log = LogManager.getLogger(SubProcessNode.class);

    /**
     * How many milliseconds to wait between retries when scanning the application log for
     * the node status.
     */
    private static final long LOG_SCAN_BACKOFF_MS = 1000L;
    /**
     * How many milliseconds to wait between retrying a Prometheus status lookup.
     */
    private static final long PROMETHEUS_BACKOFF_MS = 100L;
    /**
     * The maximum number of retries to make when looking up the status of a node via Prometheus
     * before resorting to scanning the application log. (Empirically, if Prometheus is not up
     * within a minute or so, it's not going to be; and we should fall back to log scanning.)
     */
    private static final int MAX_PROMETHEUS_RETRIES = 1000;
    /**
     * How many retries to make between checking if a bind exception has been thrown in the logs.
     */
    private static final int BINDING_CHECK_INTERVAL = 10;

    private final Pattern statusPattern;
    private final GrpcPinger grpcPinger;
    private final PrometheusClient prometheusClient;
    /**
     * If this node is running, the {@link ProcessHandle} of the node's process; null otherwise.
     */
    @Nullable
    private ProcessHandle processHandle;

    public SubProcessNode(
            @NonNull final NodeMetadata metadata,
            @NonNull final GrpcPinger grpcPinger,
            @NonNull final PrometheusClient prometheusClient) {
        super(metadata);
        this.grpcPinger = requireNonNull(grpcPinger);
        this.statusPattern = Pattern.compile(".*HederaNode#" + getNodeId() + " is (\\w+)");
        this.prometheusClient = requireNonNull(prometheusClient);
        // Just something to keep checkModuleInfo from claiming we don't require com.hedera.node.app
        requireNonNull(Hedera.class);
    }

    @Override
    public SubProcessNode start() {
        return startWithConfigVersion(LifecycleTest.CURRENT_CONFIG_VERSION.get());
    }

    @Override
    public CompletableFuture<Void> statusFuture(
            @Nullable Consumer<NodeStatus> nodeStatusObserver, @NonNull final PlatformStatus... statuses) {
        requireNonNull(statuses);
        final var retryCount = new AtomicInteger();
        final var acceptanceSet = EnumSet.noneOf(PlatformStatus.class);
        Collections.addAll(acceptanceSet, statuses);
        return conditionFuture(
                () -> {
                    final var nominalSoFar = retryCount.get() <= MAX_PROMETHEUS_RETRIES;
                    final var lookupAttempt = nominalSoFar
                            ? prometheusClient.statusFromLocalEndpoint(metadata.prometheusPort())
                            : statusFromLog();
                    var grpcStatus = NA;
                    final var statusNow = lookupAttempt.status();
                    var statusReached = acceptanceSet.contains(statusNow);
                    if (statusReached && lookupAttempt.status() == ACTIVE) {
                        grpcStatus = grpcPinger.isLive(metadata.grpcPort()) ? UP : DOWN;
                        statusReached = grpcStatus == UP;
                    }
                    var bindExceptionSeen = BindExceptionSeen.NA;
                    // This extra logic just barely justifies its existence by giving up
                    // immediately when a bind exception is seen in the logs, since in
                    // practice these are never transient; it also lets us try reassigning
                    // ports when first starting the network to maybe salvage the run
                    if (!statusReached
                            && statusNow == ACTIVE
                            && !nominalSoFar
                            && retryCount.get() % BINDING_CHECK_INTERVAL == 0) {
                        if (swirldsLogContains("java.net.BindException")) {
                            bindExceptionSeen = YES;
                        } else {
                            bindExceptionSeen = NO;
                        }
                    }
                    if (nodeStatusObserver != null) {
                        nodeStatusObserver.accept(new NodeStatus(
                                lookupAttempt, grpcStatus, bindExceptionSeen, retryCount.getAndIncrement()));
                    }
                    return statusReached ? REACHED : PENDING;
                },
                () -> retryCount.get() > MAX_PROMETHEUS_RETRIES ? LOG_SCAN_BACKOFF_MS : PROMETHEUS_BACKOFF_MS);
    }

    @Override
    public CompletableFuture<Void> minLogsFuture(@NonNull final String pattern, final int n) {
        return conditionFuture(
                () -> numApplicationLogLinesWith(pattern) >= n ? REACHED : PENDING, () -> LOG_SCAN_BACKOFF_MS);
    }

    @Override
    public CompletableFuture<Void> stopFuture() {
        if (processHandle == null) {
            return CompletableFuture.completedFuture(null);
        }
        final var stopFuture = processHandle.onExit().thenAccept(handle -> {
            log.info("Destroyed PID {}", handle.pid());
            this.processHandle = null;
        });
        log.info(
                "Destroying node{} with PID '{}' (Alive? {})",
                metadata.nodeId(),
                processHandle.pid(),
                processHandle.isAlive() ? "Yes" : "No");
        if (!processHandle.destroyForcibly()) {
            log.warn("May have failed to stop node{} with PID '{}'", metadata.nodeId(), processHandle.pid());
        }
        return stopFuture;
    }

    @Override
    public boolean dumpThreads() {
        requireNonNull(processHandle);
        try {
            triggerThreadDump();
            return true;
        } catch (Exception e) {
            log.warn(
                    "Unable to dump threads for node{} with PID '{}' (Alive? {}), assuming it was stopped",
                    metadata.nodeId(),
                    processHandle.pid(),
                    processHandle.isAlive() ? "Yes" : "No",
                    e);
            processHandle = null;
            return false;
        }
    }

    @Override
    public String toString() {
        return "SubProcessNode{" + "metadata=" + metadata + ", workingDirInitialized=" + workingDirInitialized + '}';
    }

    @Override
    protected SubProcessNode self() {
        return this;
    }

    /**
     * Starts the node with the given config version.
     * @param configVersion the config version to use
     * @return this node
     */
    public SubProcessNode startWithConfigVersion(final int configVersion) {
        return startWithConfigVersion(configVersion, Map.of());
    }

    /**
     * Starts the node with the given config version.
     * @param configVersion the config version to use
     * @param envOverrides the environment overrides to use
     * @return this node
     */
    public SubProcessNode startWithConfigVersion(
            final int configVersion, @NonNull final Map<String, String> envOverrides) {
        assertStopped();
        assertWorkingDirInitialized();
        destroyAnySubProcessNodeWithId(metadata.nodeId());
        processHandle = startSubProcessNodeFrom(metadata, configVersion, envOverrides);
        return this;
    }

    /**
     * Reassigns the ports used by this node.
     *
     * @param grpcPort the new gRPC port
     * @param grpcNodeOperatorPort the new gRPC node operator port
     * @param gossipPort the new gossip port
     * @param tlsGossipPort the new TLS gossip port
     * @param prometheusPort the new Prometheus port
     */
    public void reassignPorts(
            final int grpcPort,
            final int grpcNodeOperatorPort,
            final int gossipPort,
            final int tlsGossipPort,
            final int prometheusPort) {
        metadata = metadata.withNewPorts(grpcPort, grpcNodeOperatorPort, gossipPort, tlsGossipPort, prometheusPort);
    }

    /**
     * Reassigns the account ID used by this node.
     *
     * @param accountId the new account ID
     */
    public void reassignNodeAccountIdFrom(@NonNull final AccountID accountId) {
        metadata = metadata.withNewAccountId(accountId);
    }

    private boolean swirldsLogContains(@NonNull final String text) {
        try (var lines = Files.lines(getExternalPath(SWIRLDS_LOG))) {
            return lines.anyMatch(line -> line.contains(text));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private int numApplicationLogLinesWith(@NonNull final String text) {
        try (var lines = Files.lines(getExternalPath(APPLICATION_LOG))) {
            return (int) lines.filter(line -> line.contains(text)).count();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void assertStopped() {
        if (processHandle != null) {
            throw new IllegalStateException("Node is still running");
        }
    }

    private StatusLookupAttempt statusFromLog() {
        final AtomicReference<String> status = new AtomicReference<>();
        try (final var lines = Files.lines(getExternalPath(APPLICATION_LOG))) {
            lines.map(statusPattern::matcher).filter(Matcher::matches).forEach(matcher -> status.set(matcher.group(1)));
            return newLogAttempt(status.get(), status.get() == null ? "No status line in log" : null);
        } catch (IOException e) {
            return newLogAttempt(null, e.getMessage());
        }
    }

    public enum ReassignPorts {
        YES,
        NO
    }

    private void triggerThreadDump() throws IOException {
        final var javaHome = System.getProperty("java.home");
        var jcmdPath = javaHome + File.separator + "bin" + File.separator + "jcmd";
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            jcmdPath += ".exe";
        }
        final long pid = requireNonNull(processHandle).pid();
        final var errorRedirectPath =
                metadata.workingDirOrThrow().resolve(OUTPUT_DIR).resolve(ERROR_REDIRECT_FILE);
        final var processBuilder = new ProcessBuilder(jcmdPath, Long.toString(pid), "Thread.print")
                .redirectOutput(errorRedirectPath.toFile())
                .redirectErrorStream(true);
        final var jcmd = processBuilder.start();
        final int exitCode;
        try {
            exitCode = jcmd.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
        if (exitCode != 0) {
            throw new IOException("jcmd exited with code " + exitCode);
        }
    }
}
