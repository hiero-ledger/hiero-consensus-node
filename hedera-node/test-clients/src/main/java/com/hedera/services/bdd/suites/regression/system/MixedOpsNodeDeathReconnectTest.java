/*
 * Copyright (C) 2023-2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
import com.hedera.services.bdd.spec.props.JutilPropertySource;
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
    private static final String SHARD = JutilPropertySource.getDefaultInstance().get("default.shard");
    private static final String REALM = JutilPropertySource.getDefaultInstance().get("default.realm");

    @HapiTest
    final Stream<DynamicTest> reconnectMixedOps() {
        return defaultHapiSpec("RestartMixedOps")
                .given(
                        // Validate we can initially submit transactions to node2
                        cryptoCreate("nobody").setNode(String.format("%s.%s.5", SHARD, REALM)),
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
                        cryptoCreate("somebody").setNode(String.format("%s.%s.5", SHARD, REALM)));
    }
}
