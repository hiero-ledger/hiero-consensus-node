// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.regression.system;

import static com.hedera.services.bdd.junit.TestTags.ND_RECONNECT;
import static com.hedera.services.bdd.junit.hedera.NodeSelector.byNodeId;
import static com.hedera.services.bdd.spec.HapiSpec.defaultHapiSpec;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.assertHgcaaLogContainsText;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.logIt;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sleepFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.waitForActive;
import static com.hedera.services.bdd.suites.regression.system.MixedOperations.burstOfTps;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.OrderedInIsolation;
import com.hedera.services.bdd.spec.utilops.FakeNmt;
import java.time.Duration;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;

/**
 * This test is to verify reconnect functionality. It submits a burst of mixed operations, then
 * shuts one node,and starts it back after some time. Node will reconnect, and once reconnect is completed
 * submits the same burst of mixed operations again.
 */
@Tag(ND_RECONNECT)
@OrderedInIsolation
public class MixedOpsNodeDeathReconnectTest implements LifecycleTest {

    /** Subprocess node id 2 — classic operator account 0.0.5 ({@code setNode("5")}). */
    private static final long RECONNECT_NODE_ID = 2L;

    private static final long UPGRADING_NODE_ID = 1L;

    @Order(1)
    @HapiTest
    final Stream<DynamicTest> reconnectMixedOps() {
        return defaultHapiSpec("RestartMixedOps")
                .given(
                        // Validate we can initially submit transactions to 0.0.5 (node id 2)
                        cryptoCreate("nobody").setNode("5"),
                        // Run some mixed transactions
                        burstOfTps(MIXED_OPS_BURST_TPS, MIXED_OPS_BURST_DURATION),
                        FakeNmt.shutdownWithin(byNodeId(RECONNECT_NODE_ID), SHUTDOWN_TIMEOUT),
                        logIt("Node id " + RECONNECT_NODE_ID + " is supposedly down"),
                        sleepFor(PORT_UNBINDING_WAIT_PERIOD.toMillis()))
                .when(
                        // Submit operations while that node is down
                        burstOfTps(MIXED_OPS_BURST_TPS, MIXED_OPS_BURST_DURATION),
                        FakeNmt.restartNode(byNodeId(RECONNECT_NODE_ID)),
                        // ACTIVE (BUSY and RECONNECT_COMPLETE are too transient to reliably poll for)
                        waitForActive(byNodeId(RECONNECT_NODE_ID), RESTART_TO_ACTIVE_TIMEOUT))
                .then(
                        // Run some more transactions
                        burstOfTps(MIXED_OPS_BURST_TPS, MIXED_OPS_BURST_DURATION),
                        cryptoCreate("somebody").setNode("5"));
    }

    /**
     * A node restarted at a newer software version while its
     * latest on-disk saved state is a non-freeze state must refuse to start. Migrating from a non-freeze state on upgrade
     * produces a root hash that diverges from the rest of the network (an ISS); the guard in
     * {@code Hedera#assertFreezeStateOnUpgrade} instead logs a fatal error and exits the node via
     * {@code SystemExitUtils.exitSystem}.
     *
     * <p>The guard only fires on an upgrade — a same-version restart from a non-freeze state is normal crash recovery and
     * must still succeed. Uses an isolated per-method subprocess network because the guarded node deliberately exits and
     * never rejoins.
     */
    @Order(2)
    @HapiTest
    final Stream<DynamicTest> nodeExitsWhenUpgradingFromNonFreezeState() {
        return hapiTest(
                // Advance rounds so a non-freeze state (first-round-after-genesis / periodic snapshot) is written to
                // disk, without ever freezing the network.
                burstOfTps(MIXED_OPS_BURST_TPS, MIXED_OPS_BURST_DURATION),
                // Take the node down abnormally (no freeze), so its latest on-disk state is a non-freeze state.
                FakeNmt.shutdownWithin(byNodeId(UPGRADING_NODE_ID), SHUTDOWN_TIMEOUT),
                // Bring it back at a higher config version, i.e. an upgrade that resumes from that non-freeze state.
                // The guard exits the node during state initialization, before it binds any gossip port, so the
                // reconnect-oriented PORT_UNBINDING_WAIT_PERIOD used by node-death tests is unnecessary here.
                FakeNmt.restartWithConfigVersion(byNodeId(UPGRADING_NODE_ID), CURRENT_CONFIG_VERSION.get() + 1),
                // The node must log the fatal guard message and exit instead of joining the network.
                assertHgcaaLogContainsText(
                        byNodeId(UPGRADING_NODE_ID),
                        "while resuming from a non-freeze state at round",
                        Duration.ofSeconds(60)));
    }
}
