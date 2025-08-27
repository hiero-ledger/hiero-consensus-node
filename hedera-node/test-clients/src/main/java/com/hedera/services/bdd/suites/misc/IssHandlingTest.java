// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.misc;

import static com.hedera.services.bdd.junit.TestTags.ISS;
import static com.hedera.services.bdd.junit.hedera.ExternalPath.APPLICATION_PROPERTIES;
import static com.hedera.services.bdd.junit.hedera.NodeSelector.byNodeId;
import static com.hedera.services.bdd.junit.hedera.utils.WorkingDirUtils.updateBootstrapProperties;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getVersionInfo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingHbar;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.assertHgcaaLogContains;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.assertHgcaaLogDoesNotContain;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.freezeOnly;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sleepForSeconds;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.waitForFrozenNetwork;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.crypto.ParseableIssBlockStreamValidationOp.ISS_NODE_ID;
import static com.hedera.services.bdd.suites.regression.system.LifecycleTest.configVersionOf;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.hedera.NodeSelector;
import com.hedera.services.bdd.suites.crypto.ParseableIssBlockStreamValidationOp;
import com.hedera.services.bdd.suites.regression.system.LifecycleTest;
import com.hederahashgraph.api.proto.java.SemanticVersion;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * Validates ISS detection works by reconnecting {@code node1} with an artificially low override for
 * {@code ledger.transfers.maxLen}, then submitting a {@link com.hedera.hapi.node.token.CryptoTransferTransactionBody}
 * that exceeds that artificial limit.
 * <p>
 * This should cause an ISS to be detected in {@code node1}, and the block stream manager to complete its fatal shutdown
 * process. The remaining nodes should still be able to handle transactions and freeze the network.
 */
@Tag(ISS)
class IssHandlingTest implements LifecycleTest {
    private static final Logger log = LogManager.getLogger(IssHandlingTest.class);

    @HapiTest
    final Stream<DynamicTest> simulateIss() {
        final AtomicReference<SemanticVersion> startVersion = new AtomicReference<>();
        return hapiTest(
                getVersionInfo().exposingServicesVersionTo(startVersion::set),
                // Wait long enough for node1 to have typically written round 1 snapshot
                // to disk; restarting from this boundary snpshot can surface edge cases
                sleepForSeconds(2),
                // Reconnect node1 with an aberrant ledger.transfers.maxLen override
                sourcing(() -> reconnectIssNode(
                        byNodeId(ISS_NODE_ID),
                        configVersionOf(startVersion.get()),
                        // Before restarting node0, update its application properties to have a low transfer limit
                        doingContextual(spec -> {
                            final var loc = spec.getNetworkNodes()
                                    .get((int) ISS_NODE_ID)
                                    .getExternalPath(APPLICATION_PROPERTIES);
                            log.info("Setting artificial transfer limit @ {}", loc);
                            updateBootstrapProperties(loc, Map.of("ledger.transfers.maxLen", "5"));
                        }))),
                assertHgcaaLogContains(
                        NodeSelector.byNodeId(ISS_NODE_ID), "ledger.transfers.maxLen = 5", Duration.ofSeconds(10)),
                // First assert there was no ISS caused by simply reconnecting
                assertHgcaaLogDoesNotContain(
                        NodeSelector.byNodeId(ISS_NODE_ID), "ISS detected", Duration.ofSeconds(30)),

                // But now submit a transaction within the normal allowed transfers.maxLen limit, while
                // _not_ within the artificial limit set on the reconnected node
                cryptoTransfer(movingHbar(6L).distributing(GENESIS, "3", "4", "5", "6", "7", "8"))
                        .signedBy(GENESIS),
                // Verify we actually got an ISS in node1
                assertHgcaaLogContains(NodeSelector.byNodeId(ISS_NODE_ID), "ISS detected", Duration.ofSeconds(60)),
                // Verify the block stream manager completed its fatal shutdown process
                assertHgcaaLogContains(
                        NodeSelector.byNodeId(ISS_NODE_ID),
                        "Block stream fatal shutdown complete",
                        Duration.ofSeconds(30)),
                // Submit a freeze
                freezeOnly().startingIn(2).seconds(),
                waitForFrozenNetwork(FREEZE_TIMEOUT, NodeSelector.exceptNodeIds(ISS_NODE_ID)),
                // And do some more validations
                new ParseableIssBlockStreamValidationOp());
    }
}
