// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.misc;

import static com.hedera.services.bdd.junit.TestTags.ISS;
import static com.hedera.services.bdd.junit.hedera.NodeSelector.byNodeId;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getVersionInfo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingHbar;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.assertHgcaaLogContainsText;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.assertHgcaaLogDoesNotContainText;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.freezeOnly;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sleepForSeconds;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.untilHgcaaLogContainsText;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.verify;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.waitForFrozenNetwork;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.crypto.ParseableIssBlockStreamValidationOp.ISS_NODE_ID;
import static com.hedera.services.bdd.suites.regression.system.LifecycleTest.configVersionOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.hedera.NodeSelector;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.suites.crypto.ParseableIssBlockStreamValidationOp;
import com.hedera.services.bdd.suites.regression.system.LifecycleTest;
import com.hederahashgraph.api.proto.java.SemanticVersion;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * Validates ISS detection works by reconnecting {@code node1} with an artificially low override for
 * {@code ledger.transfers.maxLen}, then submitting a {@link com.hedera.hapi.node.token.CryptoTransferTransactionBody}
 * that exceeds that artificial limit.
 * <p>
 * This should cause an ISS to be detected in {@code node1}. The block stream manager is <i>not</i> stopped at ISS
 * detection; {@code node1} keeps streaming until the platform reaches {@code CATASTROPHIC_FAILURE}, at which point the
 * manager flushes the contents of any open/pending blocks to disk for triage. The remaining nodes should still be able
 * to handle transactions and freeze the network.
 * <p>
 * The test also enables the failure-block-capture feature on {@code node1} and asserts that both artifacts are
 * <b>staged to the node-local {@code issBlockDir}</b> (the node does no upload itself — a separate deployment uploader
 * ships the staged files):
 * <ul>
 *   <li>{@code triage/} — the catastrophic-failure flushed open/pending set;</li>
 *   <li>the ISS-round block (under {@code detect/} or {@code failure/}) — deterministic for this halting ISS: after
 *   {@code awaitFatalShutdown} flushes the block to disk, {@code Hedera.newPlatformStatus(CATASTROPHIC_FAILURE)} stages
 *   it <i>synchronously</i> (before the node halts), so it cannot be lost to a shutdown race. (The detection path also
 *   captures it asynchronously for non-halting ISSes; the two de-duplicate.)</li>
 * </ul>
 */
@Tag(ISS)
class IssHandlingTest implements LifecycleTest {

    /** The absolute staging dir the ISS node writes captured artifacts into; asserted on disk after the ISS. */
    private final AtomicReference<Path> issBlockDir = new AtomicReference<>();

    @HapiTest
    final Stream<DynamicTest> simulateIss() {
        final AtomicReference<SemanticVersion> startVersion = new AtomicReference<>();
        return hapiTest(
                getVersionInfo().exposingServicesVersionTo(startVersion::set),
                // Wait long enough for node1 to have typically written round 1 snapshot
                // to disk; restarting from this boundary snapshot can surface edge cases
                sleepForSeconds(2),
                // Reconnect node1 with an aberrant ledger.transfers.maxLen override and the failure-capture feature
                // enabled, staging into a dir under the node's working directory.
                sourcing(() -> reconnectIssNode(
                        byNodeId(ISS_NODE_ID),
                        configVersionOf(startVersion.get()),
                        IssStagingTestSupport.configureFailureStaging(issBlockDir))),
                assertHgcaaLogContainsText(
                        NodeSelector.byNodeId(ISS_NODE_ID), "ledger.transfers.maxLen = 5", Duration.ofSeconds(10)),
                // First assert there was no ISS caused by simply reconnecting
                assertHgcaaLogDoesNotContainText(
                        NodeSelector.byNodeId(ISS_NODE_ID), "ISS detected", Duration.ofSeconds(30)),

                // But now submit a transaction within the normal allowed transfers.maxLen limit, while
                // _not_ within the artificial limit set on the reconnected node
                cryptoTransfer(movingHbar(6L).distributing(GENESIS, "3", "4", "5", "6", "7", "8"))
                        .signedBy(GENESIS),
                // Verify we actually got an ISS in node1. Detection lags the offending round by several rounds of
                // state-signature gossip (latency varies), and the ISS node's log is reset when it halts at
                // CATASTROPHIC_FAILURE — so we POLL until the line appears (catching it before any reset) rather than
                // sleep-then-read-once.
                untilHgcaaLogContainsText(
                        NodeSelector.byNodeId(ISS_NODE_ID),
                        "ISS detected",
                        Duration.ofSeconds(180),
                        () -> new SpecOperation[0]),
                // Verify the block stream manager completed its fatal shutdown process
                untilHgcaaLogContainsText(
                        NodeSelector.byNodeId(ISS_NODE_ID),
                        "Block stream fatal shutdown complete",
                        Duration.ofSeconds(60),
                        () -> new SpecOperation[0]),
                // The ISS-round block and the flushed triage set are both STAGED to the node-local dir (a separate
                // deployment uploader would ship them). Assert both the staging logs and the files on disk.
                untilHgcaaLogContainsText(
                        NodeSelector.byNodeId(ISS_NODE_ID),
                        "Staged ISS round",
                        Duration.ofSeconds(90),
                        () -> new SpecOperation[0]),
                untilHgcaaLogContainsText(
                        NodeSelector.byNodeId(ISS_NODE_ID),
                        "Triage block staging complete",
                        Duration.ofSeconds(90),
                        () -> new SpecOperation[0]),
                verify(() -> assertStaged(issBlockDir.get())),
                // Submit a freeze
                freezeOnly().startingIn(2).seconds(),
                waitForFrozenNetwork(FREEZE_TIMEOUT, NodeSelector.exceptNodeIds(ISS_NODE_ID)),
                // And do some more validations
                new ParseableIssBlockStreamValidationOp());
    }

    /**
     * Asserts the ISS node staged its artifacts to disk: an ISS-round block under {@code detect/} or {@code failure/},
     * and a flushed block under {@code triage/}.
     */
    private static void assertStaged(final Path issBlockDir) {
        final List<String> staged = IssStagingTestSupport.stagedRegularFiles(issBlockDir);
        assertTrue(
                staged.stream().anyMatch(p -> (p.contains("/detect/") || p.contains("/failure/")) && p.endsWith(".gz")),
                "expected an ISS block staged under detect/ or failure/; saw " + staged);
        assertTrue(
                staged.stream().anyMatch(p -> p.contains("/triage/") && p.endsWith(".gz")),
                "expected a triage/ block staged; saw " + staged);
    }
}
