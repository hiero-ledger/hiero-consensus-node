// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.regression.system;

import static com.hedera.services.bdd.junit.TestTags.ND_RECONNECT;
import static com.hedera.services.bdd.spec.HapiSpec.defaultHapiSpec;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.logIt;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sleepFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.waitForActive;
import static com.hedera.services.bdd.suites.regression.system.LifecycleTest.RESTART_TO_ACTIVE_TIMEOUT;
import static com.hedera.services.bdd.suites.regression.system.MixedOperations.burstOfTps;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.spec.utilops.FakeNmt;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * This test is to verify reconnect functionality. It submits a burst of mixed operations, then
 * shuts one node,and starts it back after some time. Node will reconnect, and once reconnect is completed
 * submits the same burst of mixed operations again.
 */
@Tag(ND_RECONNECT)
public class MixedOpsNodeDeathReconnectTest implements LifecycleTest {

    @HapiTest
    final Stream<DynamicTest> reconnectMixedOps() {
        return defaultHapiSpec("RestartMixedOps")
                .given(
                        // Validate we can initially submit transactions to node2
                        cryptoCreate("nobody").setNode("5"),
                        // Run some mixed transactions
                        burstOfTps(MIXED_OPS_BURST_TPS, MIXED_OPS_BURST_DURATION),
                        // Stop node 2
                        FakeNmt.shutdownWithin("Carol", SHUTDOWN_TIMEOUT),
                        logIt("Node 2 is supposedly down"),
                        sleepFor(PORT_UNBINDING_WAIT_PERIOD.toMillis()))
                .when(
                        // Submit operations when node 2 is down
                        burstOfTps(MIXED_OPS_BURST_TPS, MIXED_OPS_BURST_DURATION),
                        // Restart node2
                        FakeNmt.restartNode("Carol"),
                        // Wait for node2 ACTIVE (BUSY and RECONNECT_COMPLETE are too transient to reliably poll for)
                        waitForActive("Carol", RESTART_TO_ACTIVE_TIMEOUT))
                .then(
                        // Run some more transactions
                        burstOfTps(MIXED_OPS_BURST_TPS, MIXED_OPS_BURST_DURATION),
                        // And validate we can still submit transactions to node2
                        cryptoCreate("somebody").setNode("5"));
    }
}
