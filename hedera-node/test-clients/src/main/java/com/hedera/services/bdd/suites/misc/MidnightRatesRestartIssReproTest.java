// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.misc;

import static com.hedera.services.bdd.junit.TestTags.ISS;
import static com.hedera.services.bdd.junit.hedera.ExternalPath.SAVED_STATES_DIR;
import static com.hedera.services.bdd.junit.hedera.NodeSelector.byNodeId;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getFileContents;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getVersionInfo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileUpdate;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.assertHgcaaLogDoesNotContainText;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
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
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;

/**
 * Guards against a consensus-breaking ISS that occurred when the in-memory exchange-rate midnight baseline was moved
 * without a matching change to the {@code MIDNIGHT_RATES} state singleton.
 *
 * <p>An exchange-rate update paid by the system admin (0.0.50) bypasses the intraday limit and moves the midnight
 * baseline. That move is now persisted to the {@code MIDNIGHT_RATES} singleton in the same transaction, so a node that
 * reconnects afterwards reloads the moved baseline from the singleton and holds the same baseline as its peers. A later
 * update by the rates admin (0.0.57) that is within the intraday limit of the moved baseline is then accepted by every
 * node, including the reconnected one, so no node diverges and no ISS occurs.
 *
 * <p>Before the fix the move touched only in-memory state, so the reconnected node reloaded the pre-move baseline from
 * the stale singleton and rejected the later update its peers accepted — a divergent handle result, hence an ISS.
 *
 * <p>The scenario is deterministic without a fixed sleep: before restarting node1, it waits until node1 has written a
 * saved state whose consensus time is at or after the system-admin update. The restart then loads that post-update
 * snapshot instead of replaying the update from an older one. The staking period is set to a day so that no
 * staking-period boundary, which re-derives the midnight rates on every node, masks the behavior under test.
 *
 * <p>Ordered first among the ISS-tagged classes, which share one subprocess network run in class order: a sibling that
 * freezes the network as its terminal step would otherwise leave it halted before this spec could initialize. For the
 * same reason this spec does not freeze the network, so the specs after it still find it live; it proves the
 * reconnected node stays healthy by driving fee-bearing traffic the whole network must handle identically.
 */
@Order(1)
@Tag(ISS)
class MidnightRatesRestartIssReproTest implements LifecycleTest {
    private static final Duration SAVED_STATE_WAIT_TIMEOUT = Duration.ofSeconds(120);

    // Leaky (runs isolated) because it overrides staking.periodMins; the override is declared so it is restored after
    // the test. The ISS-tagged specs share one subprocess network that is never recreated between specs, so this test
    // also restores the exchange-rate file it mutates (see the end of the scenario) rather than leaking a rate far
    // outside the intraday limit to the specs that follow.
    @LeakyHapiTest(overrides = {"staking.periodMins"})
    final Stream<DynamicTest> systemAdminRateMovePersistsBaselineSoReconnectDoesNotIss() {
        final AtomicReference<SemanticVersion> startVersion = new AtomicReference<>();
        final AtomicReference<Instant> moveConsensusTime = new AtomicReference<>();
        final AtomicReference<byte[]> originalRates = new AtomicReference<>();
        return hapiTest(
                overriding("staking.periodMins", "1440"),
                getVersionInfo().exposingServicesVersionTo(startVersion::set),
                // Capture the baseline exchange-rate file so it can be restored for the specs that follow
                getFileContents(EXCHANGE_RATES).consumedBy(originalRates::set),
                cryptoTransfer(tinyBarsFromTo(GENESIS, EXCHANGE_RATE_CONTROL, ADEQUATE_FUNDS))
                        .fee(ONE_HUNDRED_HBARS),
                cryptoTransfer(tinyBarsFromTo(GENESIS, SYSTEM_ADMIN, ADEQUATE_FUNDS))
                        .fee(ONE_HUNDRED_HBARS),
                // The system admin bypasses the intraday limit and moves the midnight baseline to 1:120, persisting the
                // move to the MIDNIGHT_RATES singleton
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
                // rather than replaying the move from an older one
                awaitSavedStateAtOrAfter(moveConsensusTime),
                // Reconnect node1; it reloads its midnight baseline from the (now moved) committed singleton, matching
                // its peers
                sourcing(() -> reconnectIssNode(byNodeId(ISS_NODE_ID), configVersionOf(startVersion.get()))),
                assertHgcaaLogDoesNotContainText(
                        NodeSelector.byNodeId(ISS_NODE_ID), "ISS detected", Duration.ofSeconds(30)),
                // 1:130 is within 25% of the moved 1:120 baseline, so every node -- including the reconnected one --
                // accepts it; there is no divergence
                fileUpdate(EXCHANGE_RATES)
                        .payingWith(EXCHANGE_RATE_CONTROL)
                        .fee(ONE_HUNDRED_HBARS)
                        .contents(
                                spec -> spec.ratesProvider().rateSetWith(1, 130).toByteString()),
                // The reconnected node must not ISS on the straddling update
                assertHgcaaLogDoesNotContainText(
                        NodeSelector.byNodeId(ISS_NODE_ID), "ISS detected", Duration.ofSeconds(30)),
                // Drive more fee-bearing traffic the whole network -- including the reconnected node -- must handle
                // identically; a divergent handle result on any of these would surface as an ISS on the reconnected
                // node. The network is deliberately left running (not frozen) for the ISS-tagged specs that follow.
                cryptoTransfer(tinyBarsFromTo(GENESIS, EXCHANGE_RATE_CONTROL, 1L))
                        .fee(ONE_HUNDRED_HBARS),
                cryptoTransfer(tinyBarsFromTo(GENESIS, SYSTEM_ADMIN, 1L)).fee(ONE_HUNDRED_HBARS),
                assertHgcaaLogDoesNotContainText(
                        NodeSelector.byNodeId(ISS_NODE_ID), "ISS detected", Duration.ofSeconds(30)),
                // Restore the baseline exchange rate so the specs that follow on this shared network do not inherit the
                // moved 1:130 rate (which would exceed the intraday limit for their own updates). The system admin
                // bypasses the intraday limit, so the restore is accepted in one step.
                sourcing(() -> fileUpdate(EXCHANGE_RATES)
                        .payingWith(SYSTEM_ADMIN)
                        .fee(ONE_HUNDRED_HBARS)
                        .contents(originalRates.get())));
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
