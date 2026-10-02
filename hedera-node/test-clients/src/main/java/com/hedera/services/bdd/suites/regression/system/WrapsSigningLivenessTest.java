// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.regression.system;

import static com.hedera.node.app.history.impl.WrapsHistoryProver.AGGREGATION_FAILURE_PREFIX;
import static com.hedera.services.bdd.junit.TestTags.LONG_RUNNING;
import static com.hedera.services.bdd.junit.hedera.ExternalPath.APPLICATION_LOG;
import static com.hedera.services.bdd.junit.hedera.NodeSelector.allNodes;
import static com.hedera.services.bdd.junit.hedera.NodeSelector.byNodeId;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.explicit;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.assertHgcaaLogDoesNotContainText;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.blockingOrder;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sleepFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.untilHgcaaLogContainsPattern;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.untilHgcaaLogContainsText;
import static com.hedera.services.bdd.suites.HapiSuite.FUNDING;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.HistoryProofKeyPublication;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.WrapsPhase.POST_AGGREGATION;
import static com.hederahashgraph.api.proto.java.WrapsPhase.R1;
import static com.hederahashgraph.api.proto.java.WrapsPhase.R2;
import static com.hederahashgraph.api.proto.java.WrapsPhase.R3;

import com.google.protobuf.ByteString;
import com.hedera.hapi.services.auxiliary.history.legacy.HistoryProofKeyPublicationTransactionBody;
import com.hedera.node.app.history.HistoryLibrary.AddressBook;
import com.hedera.node.app.history.impl.HistoryLibraryImpl;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.OrderedInIsolation;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.dsl.annotations.Account;
import com.hedera.services.bdd.spec.dsl.entities.SpecAccount;
import com.hedera.services.bdd.spec.transactions.HapiExplicitTxn;
import com.hederahashgraph.api.proto.java.WrapsPhase;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * Shows that a single invalid WRAPS signing share from one node does not leave a history proof construction stuck in
 * the {@code AGGREGATE} phase; the network restarts the signing protocol and completes the construction.
 *
 * <p>The test plays node 3's part in the signing protocol. The treasury is a superuser, so a
 * {@code HistoryProofKeyPublication} it submits through node 3 is handled as a WRAPS message from node 3. Once
 * node 3 has an R1 message in the construction, its own prover never generates the entropy it needs for R2 and
 * R3, so only the submitted messages count for it. They come from a key pair other than node 3's: the checks of
 * R1 and R2 messages on arrival do not involve the sender's key, so they accept the R1 and R2; and the R3, a share
 * for node 3 signing alone, is well-formed but cannot aggregate with the others.
 */
@Tag(LONG_RUNNING)
@HapiTestLifecycle
@OrderedInIsolation
public class WrapsSigningLivenessTest {
    private static final long BYZANTINE_NODE_ID = 3L;
    private static final Pattern NEXT_CONSTRUCTION_CREATED = Pattern.compile("Created NEXT construction #(\\d+) ");
    private static final Duration ROTATION_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration PHASE_TIMEOUT = Duration.ofMinutes(1);
    private static final Duration SETTLE_TIME = Duration.ofSeconds(75);
    private static final long PACING_MS = 250L;
    private static final String GRACE_PERIOD = "30s";
    // Node 3 publishes its own R1 less than a second after the construction is created
    private static final long R1_PACING_MS = 100L;

    @Account(tinybarBalance = ONE_MILLION_HBARS, stakedNodeId = 0)
    static SpecAccount NODE0_STAKER;

    @Account(tinybarBalance = 2 * ONE_MILLION_HBARS, stakedNodeId = 1)
    static SpecAccount NODE1_STAKER;

    @Account(tinybarBalance = 3 * ONE_MILLION_HBARS, stakedNodeId = 2)
    static SpecAccount NODE2_STAKER;

    @Account(tinybarBalance = 4 * ONE_MILLION_HBARS, stakedNodeId = 3)
    static SpecAccount NODE3_STAKER;

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle lifecycle) {
        // Node 3's R2 and R3 are sent only once their phase starts, so give each more time to reach consensus
        lifecycle.overrideInClass(Map.of("tss.wrapsMessageGracePeriod", GRACE_PERIOD));
    }

    @HapiTest
    final Stream<DynamicTest> invalidR3ShareRestartsSigningInsteadOfStalling() {
        final var library = new HistoryLibraryImpl();
        final var keys = library.newSchnorrKeyPair();
        final var entropy = new byte[32];
        new SecureRandom().nextBytes(entropy);
        final var soloBook =
                new AddressBook(new long[] {1L}, new byte[][] {keys.publicKey()}, new long[] {BYZANTINE_NODE_ID});
        final var r1 = library.runWrapsPhaseR1(entropy, new byte[0], keys.privateKey());
        final var r2 = library.runWrapsPhaseR2(
                entropy, new byte[0], new byte[][] {r1}, keys.privateKey(), soloBook, Set.of(BYZANTINE_NODE_ID));
        // A well-formed share, but for node 3 signing alone with another key; so it cannot aggregate with the others
        final var r3 = library.runWrapsPhaseR3(
                entropy,
                new byte[0],
                new byte[][] {r1},
                new byte[][] {r2},
                keys.privateKey(),
                soloBook,
                Set.of(BYZANTINE_NODE_ID));
        final var nodeAccount = new AtomicReference<String>();
        final var baselineConstructionId = new AtomicLong();
        final var settledConstructionId = new AtomicLong();
        return hapiTest(
                doingContextual(spec -> {
                    final var accountId = spec.targetNetworkOrThrow()
                            .getRequiredNode(byNodeId(BYZANTINE_NODE_ID))
                            .getAccountId();
                    nodeAccount.set(
                            accountId.shardNum() + "." + accountId.realmNum() + "." + accountId.accountNumOrThrow());
                    baselineConstructionId.set(lastNextConstructionId(spec));
                }),
                // The handler accepts a WRAPS message from the treasury and attributes it to node 3; no
                // construction ever takes a POST_AGGREGATION message, so this changes nothing
                sourcing(() -> wrapsMessageVia(nodeAccount.get(), POST_AGGREGATION, new byte[32], 0L)
                        .hasKnownStatus(SUCCESS)),
                // Stake to every node, so the next stake period boundary rotates the weights
                NODE0_STAKER.getInfo(),
                NODE1_STAKER.getInfo(),
                NODE2_STAKER.getInfo(),
                NODE3_STAKER.getInfo(),
                awaitSettledRotation(baselineConstructionId, settledConstructionId),
                // A single stake change then drives exactly one new construction
                cryptoTransfer(tinyBarsFromTo(GENESIS, NODE0_STAKER.name(), ONE_MILLION_HBARS)),
                sourcing(() -> {
                    final long constructionId = settledConstructionId.get() + 1;
                    final var account = nodeAccount.get();
                    return blockingOrder(
                            // Polling often keeps the copies sent after node 0 accepts one to a minimum
                            untilHgcaaLogContainsText(
                                    byNodeId(0),
                                    receivedText(R1, constructionId),
                                    ROTATION_TIMEOUT,
                                    Duration.ofMillis(PACING_MS),
                                    () -> new SpecOperation[] {
                                        wrapsMessageVia(account, R1, r1, constructionId)
                                                .deferStatusResolution(),
                                        sleepFor(R1_PACING_MS)
                                    }),
                            // Node 3 never published an R1 of its own, so the accepted one is ours
                            assertHgcaaLogDoesNotContainText(
                                    byNodeId(BYZANTINE_NODE_ID),
                                    "Considering publication of WRAPS R1 output on construction #" + constructionId,
                                    Duration.ZERO),
                            // Send exactly one R2 and one R3 once their phase starts, so no copy can land in the
                            // next attempt and take the slot of node 3's own message there
                            messageOnceInPhase(account, R2, r2, constructionId),
                            messageOnceInPhase(account, R3, r3, constructionId),
                            // Every node sees the aggregate fail and restarts signing, rather than waiting forever
                            untilHgcaaLogContainsPattern(
                                    allNodes(),
                                    Pattern.quote("Restarted WRAPS signing for construction #" + constructionId
                                                    + " (retry 1/")
                                            + "\\d+"
                                            + Pattern.quote(
                                                    ") after recoverable failure '" + AGGREGATION_FAILURE_PREFIX),
                                    PHASE_TIMEOUT,
                                    () -> new SpecOperation[] {backgroundTransfer(), sleepFor(PACING_MS)}),
                            // With node 3 back to signing honestly, the restarted protocol completes
                            untilHgcaaLogContainsText(
                                    allNodes(), constructedText(constructionId), ROTATION_TIMEOUT, () ->
                                            new SpecOperation[] {backgroundTransfer(), sleepFor(PACING_MS)}));
                }));
    }

    /**
     * Returns an operation that waits for the given phase of the construction to start, submits the given WRAPS
     * message through the given node once, and waits for node 0 to accept it.
     */
    private static SpecOperation messageOnceInPhase(
            @NonNull final String nodeAccount,
            @NonNull final WrapsPhase phase,
            @NonNull final byte[] message,
            final long constructionId) {
        return blockingOrder(
                untilHgcaaLogContainsText(
                        byNodeId(0),
                        "Advanced to " + phase + " for construction #" + constructionId,
                        PHASE_TIMEOUT,
                        () -> new SpecOperation[] {backgroundTransfer(), sleepFor(PACING_MS)}),
                wrapsMessageVia(nodeAccount, phase, message, constructionId),
                untilHgcaaLogContainsText(byNodeId(0), receivedText(phase, constructionId), PHASE_TIMEOUT, () ->
                        new SpecOperation[] {sleepFor(PACING_MS)}));
    }

    /**
     * Returns an operation that submits the given WRAPS message for the given construction through the given node,
     * paid by the treasury.
     */
    private static HapiExplicitTxn wrapsMessageVia(
            @NonNull final String nodeAccount,
            @NonNull final WrapsPhase phase,
            @NonNull final byte[] message,
            final long constructionId) {
        return explicit(
                        HistoryProofKeyPublication,
                        (spec, b) ->
                                b.setHistoryProofKeyPublication(HistoryProofKeyPublicationTransactionBody.newBuilder()
                                        .setWrapsMessage(ByteString.copyFrom(message))
                                        .setPhase(phase)
                                        .setConstructionId(constructionId)))
                .payingWith(GENESIS)
                .setNodeFrom(() -> nodeAccount)
                .noLogging();
    }

    /**
     * Returns an operation that waits for the rotation driven by new stakes to complete, and then for the weights
     * to stay put for longer than a stake period; and exposes the id of the last construction created.
     */
    private static SpecOperation awaitSettledRotation(
            @NonNull final AtomicLong baselineConstructionId, @NonNull final AtomicLong settledConstructionId) {
        return doingContextual(spec -> {
            final var deadline = Instant.now().plus(ROTATION_TIMEOUT);
            long lastSeenId = -1;
            var lastSeenAt = Instant.now();
            while (Instant.now().isBefore(deadline)) {
                allRunFor(spec, backgroundTransfer(), sleepFor(1_000L));
                final long lastId = lastNextConstructionId(spec);
                if (lastId <= baselineConstructionId.get() || !logOf(spec).contains(constructedText(lastId))) {
                    continue;
                }
                if (lastId != lastSeenId) {
                    lastSeenId = lastId;
                    lastSeenAt = Instant.now();
                } else if (Duration.between(lastSeenAt, Instant.now()).compareTo(SETTLE_TIME) >= 0) {
                    settledConstructionId.set(lastId);
                    return;
                }
            }
            Assertions.fail("Weights did not rotate and settle within " + ROTATION_TIMEOUT);
        });
    }

    private static SpecOperation backgroundTransfer() {
        return cryptoTransfer(tinyBarsFromTo(GENESIS, FUNDING, 1L))
                .deferStatusResolution()
                .noLogging();
    }

    private static long lastNextConstructionId(@NonNull final HapiSpec spec) {
        final var matcher = NEXT_CONSTRUCTION_CREATED.matcher(logOf(spec));
        long lastId = -1;
        while (matcher.find()) {
            lastId = Math.max(lastId, Long.parseLong(matcher.group(1)));
        }
        return lastId;
    }

    private static String logOf(@NonNull final HapiSpec spec) {
        try {
            return Files.readString(
                    spec.targetNetworkOrThrow().getRequiredNode(byNodeId(0)).getExternalPath(APPLICATION_LOG));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String receivedText(@NonNull final WrapsPhase phase, final long constructionId) {
        return "Received " + phase + " message from node" + BYZANTINE_NODE_ID + " for construction #" + constructionId
                + " in phase=" + phase + ") -> accepted";
    }

    private static String constructedText(final long constructionId) {
        return "History proof constructed (#" + constructionId + ",";
    }
}
