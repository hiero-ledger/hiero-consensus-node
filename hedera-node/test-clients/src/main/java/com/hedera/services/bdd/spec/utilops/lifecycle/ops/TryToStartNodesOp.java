// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.lifecycle.ops;

import static java.util.Objects.requireNonNull;

import com.hedera.services.bdd.junit.hedera.HederaNode;
import com.hedera.services.bdd.junit.hedera.NodeSelector;
import com.hedera.services.bdd.junit.hedera.subprocess.SubProcessNetwork;
import com.hedera.services.bdd.junit.hedera.subprocess.SubProcessNode;
import com.hedera.services.bdd.junit.hedera.subprocess.SubProcessNode.ReassignPorts;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.utilops.lifecycle.AbstractLifecycleOp;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Assertions;

/**
 * Shuts down the selected node or nodes specified by the {@link NodeSelector}.
 */
public class TryToStartNodesOp extends AbstractLifecycleOp {
    private static final Logger log = LogManager.getLogger(TryToStartNodesOp.class);
    private static final Duration BIND_RETRY_TIMEOUT = Duration.ofMinutes(1);
    private static final Duration INITIAL_BIND_BACKOFF = Duration.ofMillis(250);
    private static final Duration MAX_BIND_BACKOFF = Duration.ofSeconds(5);
    private static final Duration BIND_CHECK_WINDOW = Duration.ofSeconds(2);
    private static final Duration BIND_CHECK_INTERVAL = Duration.ofMillis(200);

    private final int configVersion;
    private final ReassignPorts reassignPorts;
    private final Map<String, String> envOverrides;
    private final boolean refreshOverrides;

    public TryToStartNodesOp(@NonNull final NodeSelector selector) {
        this(selector, 0, ReassignPorts.NO, Map.of(), true);
    }

    public TryToStartNodesOp(@NonNull final NodeSelector selector, final int configVersion) {
        this(selector, configVersion, ReassignPorts.NO, Map.of(), true);
    }

    public TryToStartNodesOp(
            @NonNull final NodeSelector selector,
            final int configVersion,
            @NonNull final ReassignPorts reassignPorts,
            @NonNull final Map<String, String> envOverrides) {
        this(selector, configVersion, reassignPorts, envOverrides, true);
    }

    public TryToStartNodesOp(
            @NonNull final NodeSelector selector,
            final int configVersion,
            @NonNull final ReassignPorts reassignPorts,
            @NonNull final Map<String, String> envOverrides,
            final boolean refreshOverrides) {
        super(selector);
        this.configVersion = configVersion;
        this.reassignPorts = requireNonNull(reassignPorts);
        this.envOverrides = requireNonNull(envOverrides);
        this.refreshOverrides = refreshOverrides;
    }

    @Override
    protected boolean submitOp(@NonNull final HapiSpec spec) throws Throwable {
        final var targetNetwork = spec.targetNetworkOrThrow();
        if (!(targetNetwork instanceof SubProcessNetwork subProcessNetwork)) {
            if (reassignPorts == ReassignPorts.YES) {
                throw new IllegalStateException("Can only reassign ports for a SubProcessNetwork");
            }
            return super.submitOp(spec);
        }
        // Override handling matrix:
        // - refreshOverrides=false: no override files allowed; port reassignment is illegal.
        // - refreshOverrides=true: refresh override files with new or current ports;
        //   - the configVersion selects versioned helpers.
        if (!refreshOverrides) {
            if (reassignPorts == ReassignPorts.YES) {
                throw new IllegalStateException("Cannot reassign ports without refreshing override networks");
            }
            // No-override startup requires a clean config directory.
            subProcessNetwork.assertNoOverrideNetworks();
        } else {
            if (reassignPorts == ReassignPorts.YES) {
                if (configVersion > 0) {
                    subProcessNetwork.refreshOverrideWithNewPortsForConfigVersion(configVersion);
                } else {
                    subProcessNetwork.refreshOverrideWithNewPorts();
                }
            } else {
                if (configVersion > 0) {
                    subProcessNetwork.refreshOverrideWithCurrentPortsForConfigVersion(configVersion);
                } else {
                    subProcessNetwork.refreshOverrideWithCurrentPorts();
                }
            }
        }
        return super.submitOp(spec);
    }

    @Override
    protected void run(@NonNull final HederaNode node, @NonNull HapiSpec spec) {
        log.info("Starting node '{}' - {} using overrides {}", node.getName(), node.metadata(), envOverrides);
        try {
            if (!(node instanceof SubProcessNode subProcessNode)) {
                throw new IllegalStateException("Node is not a SubProcessNode");
            }
            startWithBindRetry(subProcessNode);
        } catch (Exception e) {
            log.error("Node '{}' failed to start", node, e);
            Assertions.fail("Node " + node + " failed to start (" + e.getMessage() + ")");
        }
        log.info("Node '{}' has started", node.getName());
    }

    private void startWithBindRetry(@NonNull final SubProcessNode node) {
        var backoff = INITIAL_BIND_BACKOFF;
        final var deadline = Instant.now().plus(BIND_RETRY_TIMEOUT);
        var attempt = 0;
        while (!Instant.now().plus(backoff).isAfter(deadline)) {
            final var snapshot = node.bindExceptionLogSnapshot();
            node.startWithConfigVersion(configVersion, envOverrides);
            if (!bindExceptionSeenWithinWindow(node, snapshot)) {
                log.info("Successfully bound node {} to snapshot {} (after {} attempts)", node, snapshot, attempt);
                return;
            }
            attempt++;
            node.stopFuture().join();
            sleep(backoff);
            backoff = backoff.multipliedBy(2);
            if (backoff.compareTo(MAX_BIND_BACKOFF) > 0) {
                backoff = MAX_BIND_BACKOFF;
            }
        }
        // timeout reached
        log.error(
                "BindException while starting node '{}' ({} attempts over {}), giving up",
                node.getName(),
                attempt,
                BIND_RETRY_TIMEOUT);
        throw new IllegalStateException(
                "Node '" + node.getName() + "' saw BindException on start after " + attempt + " attempts");
    }

    private boolean bindExceptionSeenWithinWindow(
            @NonNull final SubProcessNode node, @NonNull final SubProcessNode.BindExceptionLogSnapshot snapshot) {
        final var deadline = Instant.now().plus(BIND_CHECK_WINDOW);
        while (Instant.now().isBefore(deadline)) {
            if (node.bindExceptionLoggedSince(snapshot)) {
                return true;
            }
            sleep(BIND_CHECK_INTERVAL);
        }
        return node.bindExceptionLoggedSince(snapshot);
    }

    private static void sleep(@NonNull final Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting to retry node start", e);
        }
    }
}
