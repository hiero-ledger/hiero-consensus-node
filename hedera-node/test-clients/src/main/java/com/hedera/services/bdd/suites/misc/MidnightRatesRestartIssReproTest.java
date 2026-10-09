// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.misc;

import static com.hedera.services.bdd.junit.TestTags.ISS;
import static com.hedera.services.bdd.junit.hedera.ExternalPath.SAVED_STATES_DIR;
import static com.hedera.services.bdd.junit.hedera.NodeSelector.byNodeId;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getVersionInfo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileUpdate;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.assertHgcaaLogContainsText;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.assertHgcaaLogContainsTimeframe;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.assertHgcaaLogDoesNotContainText;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.freezeOnly;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.waitForFrozenNetwork;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.ADEQUATE_FUNDS;
import static com.hedera.services.bdd.suites.HapiSuite.EXCHANGE_RATES;
import static com.hedera.services.bdd.suites.HapiSuite.EXCHANGE_RATE_CONTROL;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.SYSTEM_ADMIN;
import static com.hedera.services.bdd.suites.crypto.ParseableIssBlockStreamValidationOp.ISS_NODE_ID;
import static com.hedera.services.bdd.suites.regression.system.LifecycleTest.configVersionOf;

import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.junit.hedera.NodeSelector;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.suites.crypto.ParseableIssBlockStreamValidationOp;
import com.hedera.services.bdd.suites.regression.system.LifecycleTest;
import com.hederahashgraph.api.proto.java.SemanticVersion;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * Reproduces a consensus-breaking ISS caused by the in-memory exchange-rate midnight baseline being moved without a
 * matching change to the {@code MIDNIGHT_RATES} state singleton.
 *
 * <p>An exchange-rate update paid by the system admin (0.0.50) bypasses the intraday limit and moves the in-memory
 * midnight baseline, but never writes the {@code MIDNIGHT_RATES} singleton (only a staking-period boundary does). A
 * node that reconnects afterwards reloads its baseline from that singleton, so it holds the pre-move baseline while its
 * peers hold the moved one. A later update by the rates admin (0.0.57) that is within the intraday limit of the moved
 * baseline, but far outside the limit of the pre-move baseline, is then accepted by the peers and rejected by the
 * reconnected node — a divergent handle result, hence an ISS on the reconnected node.
 *
 * <p>The scenario is deterministic without a fixed sleep: before restarting node1, it waits until node1 has written a
 * saved state whose consensus time is at or after the system-admin update. The restart then loads that post-update
 * snapshot instead of replaying the update from an older one — replaying it would re-apply the in-memory baseline move
 * and heal the divergence. The staking period is set to a day so that no staking-period boundary, which re-derives the
 * midnight rates on every node, heals the divergence during the test.
 */
@Tag(ISS)
class MidnightRatesRestartIssReproTest implements LifecycleTest {
    private static final Duration SAVED_STATE_WAIT_TIMEOUT = Duration.ofSeconds(120);

    // Leaky (runs isolated) because it overrides a network property, but it does not declare the override for
    // automatic restoration: this test deliberately ISSes and freezes the network, so a teardown restore would run
    // against a dead network. The network is torn down after the test, so the override does not leak to other tests.
    @LeakyHapiTest
    final Stream<DynamicTest> systemAdminRateMoveLeaksMidnightBaselineCausingReconnectIss() {
        final AtomicReference<SemanticVersion> startVersion = new AtomicReference<>();
        final AtomicReference<Instant> moveConsensusTime = new AtomicReference<>();
        final AtomicReference<Instant> issWindowStart = new AtomicReference<>();
        return hapiTest(
                overriding("staking.periodMins", "1440"),
                getVersionInfo().exposingServicesVersionTo(startVersion::set),
                cryptoTransfer(tinyBarsFromTo(GENESIS, EXCHANGE_RATE_CONTROL, ADEQUATE_FUNDS))
                        .fee(ONE_HUNDRED_HBARS),
                cryptoTransfer(tinyBarsFromTo(GENESIS, SYSTEM_ADMIN, ADEQUATE_FUNDS))
                        .fee(ONE_HUNDRED_HBARS),
                // The system admin bypasses the intraday limit and moves the in-memory midnight baseline to 1:120,
                // without writing the MIDNIGHT_RATES singleton
                fileUpdate(EXCHANGE_RATES)
                        .payingWith(SYSTEM_ADMIN)
                        .fee(ONE_HUNDRED_HBARS)
                        .via("midnightRateMove")
                        .contents(
                                spec -> spec.ratesProvider().rateSetWith(1, 120).toByteString()),
                getTxnRecord("midnightRateMove")
                        .exposingTo(record -> moveConsensusTime.set(Instant.ofEpochSecond(
                                record.getConsensusTimestamp().getSeconds(),
                                record.getConsensusTimestamp().getNanos()))),
                // Wait until node1 has a saved state at or after the move, so its restart loads that post-move snapshot
                // rather than replaying the move from an older one (which would re-apply the baseline shift)
                awaitSavedStateAtOrAfter(moveConsensusTime),
                // Reconnect node1; it reloads its midnight baseline from the (pre-move) singleton, unlike its peers
                sourcing(() -> reconnectIssNode(byNodeId(ISS_NODE_ID), configVersionOf(startVersion.get()))),
                // The reconnect alone must not cause an ISS
                assertHgcaaLogDoesNotContainText(
                        NodeSelector.byNodeId(ISS_NODE_ID), "ISS detected", Duration.ofSeconds(30)),
                // Mark the log position just before the straddle, so the ISS asserted below is attributed to this
                // update and not to any (already excluded) reconnect-induced ISS
                doingContextual(spec -> issWindowStart.set(Instant.now())),
                // 1:130 is within 25% of the moved 1:120 baseline (peers accept), but far outside 25% of the pre-move
                // baseline (the reconnected node rejects) — the divergence that causes the ISS
                fileUpdate(EXCHANGE_RATES)
                        .payingWith(EXCHANGE_RATE_CONTROL)
                        .fee(ONE_HUNDRED_HBARS)
                        .contents(
                                spec -> spec.ratesProvider().rateSetWith(1, 130).toByteString()),
                // The reconnected node detects the ISS within the window that opened at the straddle (not earlier, at
                // the reconnect) and completes its fatal block-stream shutdown
                assertHgcaaLogContainsTimeframe(
                        NodeSelector.byNodeId(ISS_NODE_ID),
                        issWindowStart::get,
                        Duration.ofSeconds(120),
                        Duration.ofSeconds(120),
                        "ISS detected"),
                assertHgcaaLogContainsText(
                        NodeSelector.byNodeId(ISS_NODE_ID),
                        "Block stream fatal shutdown complete",
                        Duration.ofSeconds(30)),
                // The remaining nodes still handle transactions and can freeze
                freezeOnly().startingIn(2).seconds(),
                waitForFrozenNetwork(FREEZE_TIMEOUT, NodeSelector.exceptNodeIds(ISS_NODE_ID)),
                // Assert the block streams carry the ISS signature: the reconnected node diverges, the rest froze
                new ParseableIssBlockStreamValidationOp());
    }

    /**
     * Returns an operation that blocks until the ISS node has written a saved state whose consensus time is at or after
     * the given instant, so a subsequent restart loads that state instead of replaying earlier transactions.
     */
    private SpecOperation awaitSavedStateAtOrAfter(final AtomicReference<Instant> target) {
        return withOpContext((spec, opLog) -> {
            final var savedStatesDir =
                    spec.getNetworkNodes().get((int) ISS_NODE_ID).getExternalPath(SAVED_STATES_DIR);
            // Holds the last expected-but-transient failure (a partially written state file), so a persistent one is
            // reported in the timeout message instead of leaving a bare, causeless timeout
            final var lastError = new AtomicReference<Exception>();
            final var deadline = Instant.now().plus(SAVED_STATE_WAIT_TIMEOUT);
            while (Instant.now().isBefore(deadline)) {
                final var at = target.get();
                if (at != null && hasSavedStateAtOrAfter(savedStatesDir, at, lastError)) {
                    return;
                }
                Thread.sleep(1000);
            }
            Assertions.fail("node" + ISS_NODE_ID + " wrote no saved state at or after " + target.get() + " within "
                    + SAVED_STATE_WAIT_TIMEOUT
                    + (lastError.get() == null ? "" : "; last error reading state metadata: " + lastError.get()));
        });
    }

    private static boolean hasSavedStateAtOrAfter(
            final Path savedStatesDir, final Instant target, final AtomicReference<Exception> lastError)
            throws Exception {
        if (!Files.isDirectory(savedStatesDir)) {
            return false;
        }
        try (final var rounds = Files.list(savedStatesDir)) {
            return rounds.filter(Files::isDirectory)
                    .map(dir -> dir.resolve("stateMetadata.txt"))
                    .filter(Files::isRegularFile)
                    .anyMatch(meta -> consensusTimeOf(meta, lastError)
                            .map(t -> !t.isBefore(target))
                            .orElse(false));
        }
    }

    private static Optional<Instant> consensusTimeOf(
            final Path stateMetadata, final AtomicReference<Exception> lastError) {
        try {
            for (final var line : Files.readAllLines(stateMetadata)) {
                if (line.startsWith("CONSENSUS_TIMESTAMP:")) {
                    return Optional.of(Instant.parse(
                            line.substring("CONSENSUS_TIMESTAMP:".length()).trim()));
                }
            }
        } catch (final IOException | DateTimeParseException transientFailure) {
            // Expected while a state file is mid-write or rotating; remember it but keep polling. Any other exception
            // is unexpected and propagates to fail the operation immediately with its real cause.
            lastError.set(transientFailure);
        }
        return Optional.empty();
    }
}
