// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.regression.system;

import static com.hedera.node.app.history.HistoryLibrary.MISSING_SCHNORR_KEY;
import static com.hedera.services.bdd.junit.TestTags.LONG_RUNNING;
import static com.hedera.services.bdd.junit.hedera.ExternalPath.APPLICATION_LOG;
import static com.hedera.services.bdd.junit.hedera.NodeSelector.allNodes;
import static com.hedera.services.bdd.junit.hedera.NodeSelector.byNodeId;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.explicit;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.assertHgcaaLogContainsText;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.assertHgcaaLogDoesNotContainText;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.blockingOrder;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sleepFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.suites.HapiSuite.FUNDING;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.HistoryProofKeyPublication;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;

import com.google.protobuf.ByteString;
import com.hedera.hapi.services.auxiliary.history.legacy.HistoryProofKeyPublicationTransactionBody;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.OrderedInIsolation;
import com.hedera.services.bdd.junit.hedera.HederaNode;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.dsl.annotations.Account;
import com.hedera.services.bdd.spec.dsl.entities.SpecAccount;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;

/**
 * Shows that a node publishing an invalid proof key, or a copy of another node's proof key, counts as not having
 * published one; so the next history proof construction still completes, instead of every node failing to hash
 * that construction's target address book. Also shows that every node accepts the votes for the resulting proofs,
 * which must commit to the target proof keys and history each node computes for itself.
 *
 * <p>The treasury is a superuser, so a {@code HistoryProofKeyPublication} it submits through node 3 is handled as
 * node 3 publishing its next proof key; and the next construction, driven by new stakes, adopts that key.
 */
@Tag(LONG_RUNNING)
@HapiTestLifecycle
@OrderedInIsolation
public class InvalidProofKeyLivenessTest {
    private static final long PUBLISHING_NODE_ID = 3L;
    private static final long COPIED_NODE_ID = 2L;
    private static final Pattern CONSTRUCTION_CREATED =
            Pattern.compile("Created (?:ACTIVE|NEXT) construction #(\\d+) ");
    private static final Pattern PROOF_CONSTRUCTED = Pattern.compile("History proof constructed \\(#(\\d+),");
    private static final Pattern COPIED_NODE_KEY =
            Pattern.compile("node" + COPIED_NODE_ID + " published new proof key '([0-9a-f]+)'");
    private static final Duration TIMEOUT = Duration.ofMinutes(5);
    private static final long PACING_MS = 1_000L;

    @Account(tinybarBalance = 2 * ONE_MILLION_HBARS, stakedNodeId = 0)
    static SpecAccount NODE0_STAKER;

    @Account(tinybarBalance = 2 * ONE_MILLION_HBARS, stakedNodeId = 1)
    static SpecAccount NODE1_STAKER;

    @Account(tinybarBalance = 2 * ONE_MILLION_HBARS, stakedNodeId = 2)
    static SpecAccount NODE2_STAKER;

    @Account(tinybarBalance = ONE_MILLION_HBARS, stakedNodeId = 3)
    static SpecAccount NODE3_STAKER;

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle lifecycle) {
        // With node 3's key ignored, assembly waits for the end of the proof key grace period
        lifecycle.overrideInClass(Map.of("tss.transitionProofKeyGracePeriod", "20s"));
    }

    @HapiTest
    @Order(1)
    final Stream<DynamicTest> invalidProofKeyDoesNotStallTheNextConstruction() {
        return nextConstructionIgnoresNode3Key(
                spec -> new byte[(int) MISSING_SCHNORR_KEY.length()],
                // Staking to the nodes rotates their weights at the next stake period boundary
                blockingOrder(
                        NODE0_STAKER.getInfo(),
                        NODE1_STAKER.getInfo(),
                        NODE2_STAKER.getInfo(),
                        NODE3_STAKER.getInfo()));
    }

    @HapiTest
    @Order(2)
    final Stream<DynamicTest> copiedProofKeyDoesNotStallTheNextConstruction() {
        return nextConstructionIgnoresNode3Key(
                spec -> HexFormat.of().parseHex(lastMatch(logOf(spec, 0), COPIED_NODE_KEY)),
                // Moving stake between nodes rotates their weights again
                blockingOrder(
                        NODE0_STAKER.getInfo(),
                        NODE1_STAKER.getInfo(),
                        cryptoTransfer(tinyBarsFromTo(NODE0_STAKER.name(), NODE1_STAKER.name(), ONE_MILLION_HBARS))
                                .payingWith(NODE0_STAKER.name())));
    }

    /**
     * Returns a test that has node 3 publish the given key as its next proof key, then changes stakes with the
     * given operation, and checks that every node completes the next construction without node 3's key.
     */
    private static Stream<DynamicTest> nextConstructionIgnoresNode3Key(
            @NonNull final Function<HapiSpec, byte[]> proofKey, @NonNull final SpecOperation stakeChange) {
        final var nodeAccount = new AtomicReference<String>();
        final var key = new AtomicReference<byte[]>();
        final var baselineConstructionId = new AtomicLong();
        final var newConstructionId = new AtomicLong();
        return hapiTest(
                doingContextual(spec -> {
                    final var accountId = spec.targetNetworkOrThrow()
                            .getRequiredNode(byNodeId(PUBLISHING_NODE_ID))
                            .getAccountId();
                    nodeAccount.set(
                            accountId.shardNum() + "." + accountId.realmNum() + "." + accountId.accountNumOrThrow());
                    baselineConstructionId.set(maxMatch(logOf(spec, 0), CONSTRUCTION_CREATED));
                }),
                // Node 3 must already have a key in use, so the new one is only adopted by the next construction
                awaitingLogs(
                        "history proof for the latest construction",
                        spec -> everyNodeConstructed(spec, baselineConstructionId.get())),
                doingContextual(spec -> key.set(proofKey.apply(spec))),
                sourcing(() -> explicit(
                                HistoryProofKeyPublication,
                                (spec, b) -> b.setHistoryProofKeyPublication(
                                        HistoryProofKeyPublicationTransactionBody.newBuilder()
                                                .setProofKey(ByteString.copyFrom(key.get()))))
                        .payingWith(GENESIS)
                        .setNodeFrom(nodeAccount::get)
                        .hasKnownStatus(SUCCESS)),
                stakeChange,
                awaitingLogs("new construction", spec -> {
                    final long id = maxMatch(logOf(spec, 0), CONSTRUCTION_CREATED);
                    newConstructionId.set(id);
                    return id > baselineConstructionId.get();
                }),
                awaitingLogs(
                        "history proof for the construction adopting node 3's key",
                        spec -> everyNodeConstructed(spec, newConstructionId.get())),
                sourcing(() -> assertHgcaaLogContainsText(
                        allNodes(),
                        "Ignoring proof key from node" + PUBLISHING_NODE_ID + " for construction #"
                                + newConstructionId.get(),
                        Duration.ZERO)),
                assertHgcaaLogDoesNotContainText(
                        allNodes(), "Ignoring proof key from node" + COPIED_NODE_ID, Duration.ZERO),
                assertHgcaaLogDoesNotContainText(allNodes(), "WRAPS.hashAddressBook() returned null", Duration.ZERO),
                assertHgcaaLogDoesNotContainText(allNodes(), "INVALID proof of unexpected target", Duration.ZERO));
    }

    /**
     * Returns an operation that keeps the network busy until the given condition holds, failing after the timeout.
     */
    private static SpecOperation awaitingLogs(
            @NonNull final String what, @NonNull final Predicate<HapiSpec> condition) {
        return doingContextual(spec -> {
            final var deadline = Instant.now().plus(TIMEOUT);
            while (!condition.test(spec)) {
                if (Instant.now().isAfter(deadline)) {
                    Assertions.fail("No " + what + " within " + TIMEOUT);
                }
                allRunFor(
                        spec,
                        cryptoTransfer(tinyBarsFromTo(GENESIS, FUNDING, 1L))
                                .deferStatusResolution()
                                .noLogging(),
                        sleepFor(PACING_MS));
            }
        });
    }

    private static boolean everyNodeConstructed(@NonNull final HapiSpec spec, final long minConstructionId) {
        return spec.targetNetworkOrThrow().nodes().stream()
                .mapToLong(HederaNode::getNodeId)
                .allMatch(nodeId -> maxMatch(logOf(spec, nodeId), PROOF_CONSTRUCTED) >= minConstructionId);
    }

    private static long maxMatch(@NonNull final String log, @NonNull final Pattern pattern) {
        final var matcher = pattern.matcher(log);
        long max = -1;
        while (matcher.find()) {
            max = Math.max(max, Long.parseLong(matcher.group(1)));
        }
        return max;
    }

    private static String lastMatch(@NonNull final String log, @NonNull final Pattern pattern) {
        final var matcher = pattern.matcher(log);
        String last = null;
        while (matcher.find()) {
            last = matcher.group(1);
        }
        Assertions.assertNotNull(last, "No match for " + pattern);
        return last;
    }

    private static String logOf(@NonNull final HapiSpec spec, final long nodeId) {
        try {
            return Files.readString(spec.targetNetworkOrThrow()
                    .getRequiredNode(byNodeId(nodeId))
                    .getExternalPath(APPLICATION_LOG));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
