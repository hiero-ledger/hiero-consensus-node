// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.regression.system;

import static com.hedera.services.bdd.junit.TestTags.WRAPS;
import static com.hedera.services.bdd.junit.hedera.NodeSelector.allNodes;
import static com.hedera.services.bdd.junit.hedera.NodeSelector.byNodeId;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.blockingOrder;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doAdhoc;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.noOp;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overridingAllOf;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sleepFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.untilHgcaaLogContainsPattern;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.untilHgcaaLogContainsText;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withExternalizedLedgerIdFromHgcaaLog;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_BILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.junit.OrderedInIsolation;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.junit.support.validators.block.StateChangesValidator;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.dsl.annotations.Account;
import com.hedera.services.bdd.spec.dsl.entities.SpecAccount;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;

/**
 * Validates construction of genesis and incremental WRAPS proofs, and that the network then
 * externalizes blocks signed with the resulting WRAPS chain-of-trust proof.
 */
@Tag(WRAPS)
@HapiTestLifecycle
@OrderedInIsolation
public class WrapsHandoffsTest implements LifecycleTest {
    private static final String GENESIS_WRAPS_PROOF_CONSTRUCTED = "FINISHED constructing genesis WRAPS proof";
    private static final String INCREMENTAL_WRAPS_PROOF_STARTED = "Constructing incremental WRAPS proof";
    private static final String INCREMENTAL_WRAPS_PROOF_CONSTRUCTED = "FINISHED constructing incremental WRAPS proof";
    private static final Duration LEDGER_ID_TIMEOUT = Duration.ofMinutes(1);
    private static final Duration WRAPS_PROOF_TIMEOUT = Duration.ofMinutes(20);
    private static final Duration STAKE_PERIOD_DURATION = Duration.ofMinutes(25);
    private static final Duration LOG_POLL_INTERVAL = Duration.ofSeconds(1);
    private static final long TRANSFER_PACING_MS = 250L;
    private static final Random RANDOM = new Random(2_721_828L);

    /**
     * An arbitrary, well-formed SHA-384 hex value standing in for a rotated proving key archive hash.
     * The .bin artifacts behind it are never touched, so proofs built after the rotation are still
     * valid; only the identity the network reads changes, which is all the re-anchor logic keys off.
     */
    private static final String ROTATED_PROVING_KEY_HASH = "ab".repeat(48);

    private static final String WRAPS_HASH_FILE_NAME = "wraps.sha384";
    /**
     * Matches the creation of a construction that grounds a new chain of trust: the same roster on both sides
     * (via the backreference), but with a source proof present -- which the original genesis never has.
     */
    private static final String RE_ANCHOR_CONSTRUCTION_PATTERN = "Created (?:ACTIVE|NEXT) construction #(\\d+) "
            + "for rosters \\(source=([0-9a-f]+), target=\\2\\) WITH WRAPS-extensible source proof";

    private static final Duration RE_ANCHOR_TIMEOUT = Duration.ofMinutes(10);

    @Account(tinybarBalance = ONE_BILLION_HBARS, stakedNodeId = 0)
    static SpecAccount NODE0_STAKER;

    @Account(tinybarBalance = ONE_BILLION_HBARS / 100, stakedNodeId = 1)
    static SpecAccount NODE1_STAKER;

    @Account(tinybarBalance = ONE_BILLION_HBARS / 100, stakedNodeId = 2)
    static SpecAccount NODE2_STAKER;

    /**
     * The proving key marker as found before any rotation, so it can be put back afterwards. The artifacts
     * directory is shared by every node and outlives this class, so a rotation left in place would leave the
     * next run's configured hash disagreeing with the marker -- and no node able to build a proof at all.
     */
    private static String originalProvingKeyMarker;

    @BeforeAll
    public static void setup(TestLifecycle lifecycle) {
        originalProvingKeyMarker = readProvingKeyMarker();
        lifecycle.doAdhoc(NODE0_STAKER.getInfo(), NODE1_STAKER.getInfo(), NODE2_STAKER.getInfo());
    }

    @AfterAll
    public static void restoreProvingKeyMarker() {
        if (originalProvingKeyMarker != null) {
            writeProvingKeyMarker(originalProvingKeyMarker.trim());
        }
    }

    @HapiTest
    @Order(0)
    final Stream<DynamicTest> genesisAndIncrementalWrapsProofsConstructed() {
        return hapiTest(sourcingContextual(_ -> {
            if (hasWrapsArtifactsPath()) {
                StateChangesValidator.ADAPTIVE_SIGNATURE_CHECKS_ENABLED.set(true);
                StateChangesValidator.AT_LEAST_ONE_WRAPS_ASSERTION_ENABLED.set(true);
                return blockingOrder(
                        withExternalizedLedgerIdFromHgcaaLog(
                                byNodeId(0),
                                LEDGER_ID_TIMEOUT,
                                LOG_POLL_INTERVAL,
                                () -> new SpecOperation[] {randomStakerTransfer(), sleepFor(TRANSFER_PACING_MS)},
                                this::assertAllGetInfoResponsesIncludeExternalizedLedgerId),
                        untilHgcaaLogContainsText(
                                        allNodes(),
                                        GENESIS_WRAPS_PROOF_CONSTRUCTED,
                                        WRAPS_PROOF_TIMEOUT,
                                        LOG_POLL_INTERVAL,
                                        () -> new SpecOperation[] {randomStakerTransfer(), sleepFor(TRANSFER_PACING_MS)
                                        })
                                .loggingOff(),
                        untilHgcaaLogContainsText(
                                        allNodes(),
                                        INCREMENTAL_WRAPS_PROOF_STARTED,
                                        STAKE_PERIOD_DURATION,
                                        LOG_POLL_INTERVAL,
                                        () -> new SpecOperation[] {randomStakerTransfer(), sleepFor(TRANSFER_PACING_MS)
                                        })
                                .loggingOff(),
                        untilHgcaaLogContainsText(
                                        allNodes(),
                                        INCREMENTAL_WRAPS_PROOF_CONSTRUCTED,
                                        WRAPS_PROOF_TIMEOUT.plus(WRAPS_PROOF_TIMEOUT),
                                        LOG_POLL_INTERVAL,
                                        () -> new SpecOperation[] {randomStakerTransfer(), sleepFor(TRANSFER_PACING_MS)
                                        })
                                .loggingOff());
            } else {
                StateChangesValidator.AT_LEAST_ONE_WRAPS_ASSERTION_ENABLED.set(false);
                return noOp();
            }
        }));
    }

    /**
     * A WRAPS proof may only be folded onto by a construction using the same proving key, so a network whose
     * configured proving key changes cannot extend its chain of trust and has to ground a new one. This rotates
     * the key and asserts the network does exactly that: it creates a construction with the current roster as
     * both source and target, and completes a genesis WRAPS proof for it.
     *
     * <p>Only the proving key's <i>identity</i> is rotated -- the marker file the node reads, and the configured
     * hash -- while the artifacts behind it stay put. That isolates the consensus-node behaviour from the
     * cryptography: the circuit is still the one this build's hardcoded verification key belongs to, so the
     * proof it grounds is genuinely valid and the whole construct/vote/threshold/handoff path is exercised.
     *
     * <p>The rotation is applied as a live property override rather than an upgrade restart. The decision is
     * taken from configuration read every round, so no restart is needed; and a restart here would have the
     * framework adopt an override-network roster while a transition construction is still in flight, which
     * kills the node on an unrelated pre-existing conflict in {@code onAdoptRoster}.
     *
     * <p>{@code tss.forceMockSignatures} is forced true because re-anchoring moves the ledger id, which is only
     * supported while block proofs do not yet carry the chain of trust.
     */
    @LeakyHapiTest(
            overrides = {"tss.wrapsProvingKeyHash", "tss.wrapsAllowFreshGenesisOnKeyChange", "tss.forceMockSignatures"})
    @Order(1)
    final Stream<DynamicTest> rotatingProvingKeyGroundsASecondGenesisProof() {
        return hapiTest(sourcingContextual(_ -> {
            if (!hasWrapsArtifactsPath()) {
                return noOp();
            }
            final AtomicReference<String> reAnchorConstructionId = new AtomicReference<>();
            return blockingOrder(
                    // Rewrite the marker first. Until the property catches up the node simply defers proof
                    // work; the reverse order would have it briefly reading a key it does not have installed.
                    doAdhoc(() -> writeProvingKeyMarker(ROTATED_PROVING_KEY_HASH)),
                    overridingAllOf(Map.of(
                            "tss.wrapsProvingKeyHash", ROTATED_PROVING_KEY_HASH,
                            "tss.wrapsAllowFreshGenesisOnKeyChange", "true",
                            "tss.forceMockSignatures", "true")),
                    // The signature of a re-anchor: one roster as both source and target (so it grounds rather
                    // than extends), yet a foldable source proof exists (so it is not the original genesis,
                    // which was created WITHOUT one).
                    untilHgcaaLogContainsPattern(
                                    allNodes(),
                                    RE_ANCHOR_CONSTRUCTION_PATTERN,
                                    RE_ANCHOR_TIMEOUT,
                                    LOG_POLL_INTERVAL,
                                    () -> new SpecOperation[] {randomStakerTransfer(), sleepFor(TRANSFER_PACING_MS)})
                            .loggingOff()
                            .exposingMatchGroupTo(1, reAnchorConstructionId),
                    // ...and that construction going on to complete a recursive proof, which is what makes the
                    // new chain of trust real rather than merely started.
                    sourcing(() -> untilHgcaaLogContainsText(
                                    allNodes(),
                                    "History proof constructed (#" + reAnchorConstructionId.get()
                                            + ", WRAPS-extensible? true)",
                                    WRAPS_PROOF_TIMEOUT,
                                    LOG_POLL_INTERVAL,
                                    () -> new SpecOperation[] {randomStakerTransfer(), sleepFor(TRANSFER_PACING_MS)})
                            .loggingOff()));
        }));
    }

    /**
     * Rewrites the marker the node compares against {@code tss.wrapsProvingKeyHash} to decide whether its
     * installed proving key is the one the network is configured to use. The artifacts directory is shared by
     * every node in a subprocess network, so this reaches all of them at once.
     */
    @Nullable
    private static String readProvingKeyMarker() {
        final var artifactsDir = System.getProperty("hapi.spec.tssLibWrapsArtifactsPath");
        if (artifactsDir == null || artifactsDir.isBlank()) {
            return null;
        }
        final var marker = Path.of(artifactsDir).resolve(WRAPS_HASH_FILE_NAME);
        try {
            return Files.isRegularFile(marker) ? Files.readString(marker) : null;
        } catch (final IOException e) {
            throw new UncheckedIOException("Could not read the WRAPS proving key marker in " + artifactsDir, e);
        }
    }

    private static void writeProvingKeyMarker(@NonNull final String hashHex) {
        final var artifactsDir = System.getProperty("hapi.spec.tssLibWrapsArtifactsPath");
        try {
            Files.writeString(Path.of(artifactsDir).resolve(WRAPS_HASH_FILE_NAME), hashHex + "\n");
        } catch (final IOException e) {
            throw new UncheckedIOException("Could not rewrite the WRAPS proving key marker in " + artifactsDir, e);
        }
    }

    private static boolean hasWrapsArtifactsPath() {
        final var wrapsArtifactsPath = System.getProperty("hapi.spec.tssLibWrapsArtifactsPath");
        return wrapsArtifactsPath != null && !wrapsArtifactsPath.isBlank();
    }

    private static SpecOperation randomStakerTransfer() {
        final var stakers = stakers();
        final var senderIndex = RANDOM.nextInt(stakers.size());
        var receiverIndex = RANDOM.nextInt(stakers.size() - 1);
        if (receiverIndex >= senderIndex) {
            receiverIndex++;
        }
        final var sender = stakers.get(senderIndex);
        final var receiver = stakers.get(receiverIndex);
        final long amount = RANDOM.nextLong(10L, 101L) * ONE_HBAR;
        return cryptoTransfer(tinyBarsFromTo(sender.name(), receiver.name(), amount))
                .payingWith(sender.name());
    }

    private static List<SpecAccount> stakers() {
        return List.of(NODE0_STAKER, NODE1_STAKER, NODE2_STAKER);
    }
}
