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
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.freezeUpgrade;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.noOp;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.runBackgroundTrafficUntilFreezeComplete;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sleepFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.untilHgcaaLogContainsPattern;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.untilHgcaaLogContainsText;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.waitForActive;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withExternalizedLedgerIdFromHgcaaLog;
import static com.hedera.services.bdd.spec.utilops.upgrade.BuildUpgradeZipOp.FAKE_UPGRADE_ZIP_LOC;
import static com.hedera.services.bdd.suites.HapiSuite.FUNDING;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_BILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.freeze.CommonUpgradeResources.DEFAULT_UPGRADE_FILE_ID;
import static com.hedera.services.bdd.suites.freeze.CommonUpgradeResources.upgradeFileHashAt;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.OrderedInIsolation;
import com.hedera.services.bdd.junit.hedera.subprocess.SubProcessNetwork;
import com.hedera.services.bdd.junit.hedera.subprocess.SubProcessNode.ReassignPorts;
import com.hedera.services.bdd.junit.support.validators.block.StateChangesValidator;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.dsl.annotations.Account;
import com.hedera.services.bdd.spec.dsl.entities.SpecAccount;
import com.hedera.services.bdd.spec.utilops.lifecycle.ops.TryToStartNodesOp;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;

/**
 * Validates construction of genesis and incremental WRAPS proofs, and that the network then
 * externalizes blocks signed with the resulting WRAPS chain-of-trust proof. Also validates that an
 * upgrade can request a fresh genesis WRAPS proof for the current roster, and that the chain of
 * trust then extends from that proof.
 */
@Tag(WRAPS)
@HapiTestLifecycle
@OrderedInIsolation
public class WrapsHandoffsTest implements LifecycleTest {
    private static final String GENESIS_WRAPS_PROOF_CONSTRUCTED = "FINISHED constructing genesis WRAPS proof";
    private static final String INCREMENTAL_WRAPS_PROOF_STARTED = "Constructing incremental WRAPS proof";
    private static final String INCREMENTAL_WRAPS_PROOF_CONSTRUCTED = "FINISHED constructing incremental WRAPS proof";
    private static final String FRESH_GENESIS_REQUESTED = "Fresh genesis WRAPS proof requested for the current roster";
    /**
     * A construction grounding a new chain of trust has the same roster as source and target (the backreference),
     * yet unlike the original genesis it is created WITH a source proof.
     */
    private static final String FRESH_GENESIS_CONSTRUCTION_PATTERN =
            "Created NEXT construction #(\\d+) for rosters \\(source=([0-9a-f]+), target=\\2\\) WITH WRAPS-extensible source proof";
    /**
     * The upgrade that requests the fresh genesis proof also forces mock signatures, since a fresh genesis
     * proof is only requested while block proofs do not yet carry the chain of trust.
     */
    private static final Map<String, String> FRESH_GENESIS_UPGRADE_ENV =
            Map.of("tss.needsFreshGenesisWrapsProof", "true", "tss.forceMockSignatures", "true");

    private static final Duration LEDGER_ID_TIMEOUT = Duration.ofMinutes(1);
    private static final Duration WRAPS_PROOF_TIMEOUT = Duration.ofMinutes(20);
    private static final Duration STAKE_PERIOD_DURATION = Duration.ofMinutes(25);
    private static final Duration FRESH_GENESIS_REQUEST_TIMEOUT = Duration.ofMinutes(2);
    private static final Duration LOG_POLL_INTERVAL = Duration.ofSeconds(1);
    private static final long TRANSFER_PACING_MS = 250L;
    private static final Random RANDOM = new Random(2_721_828L);

    @Account(tinybarBalance = ONE_BILLION_HBARS, stakedNodeId = 0)
    static SpecAccount NODE0_STAKER;

    @Account(tinybarBalance = ONE_BILLION_HBARS / 100, stakedNodeId = 1)
    static SpecAccount NODE1_STAKER;

    @Account(tinybarBalance = ONE_BILLION_HBARS / 100, stakedNodeId = 2)
    static SpecAccount NODE2_STAKER;

    /**
     * Runs before any stake is placed, so the roster has no weight rotation pending when the upgrade freezes;
     * the network must have nothing else in flight for the fresh genesis proof to be the only construction.
     */
    @HapiTest
    @Order(0)
    final Stream<DynamicTest> upgradeRequestingFreshGenesisWrapsProofGroundsOne() {
        return hapiTest(sourcingContextual(spec -> {
            if (!hasWrapsArtifactsPath()) {
                return noOp();
            }
            StateChangesValidator.ADAPTIVE_SIGNATURE_CHECKS_ENABLED.set(true);
            StateChangesValidator.AT_LEAST_ONE_WRAPS_ASSERTION_ENABLED.set(true);
            final AtomicReference<String> constructionId = new AtomicReference<>();
            return blockingOrder(
                    withExternalizedLedgerIdFromHgcaaLog(
                            byNodeId(0),
                            LEDGER_ID_TIMEOUT,
                            LOG_POLL_INTERVAL,
                            () -> new SpecOperation[] {fundingTransfer(), sleepFor(TRANSFER_PACING_MS)},
                            this::assertAllGetInfoResponsesIncludeExternalizedLedgerId),
                    untilHgcaaLogContainsText(
                                    allNodes(),
                                    GENESIS_WRAPS_PROOF_CONSTRUCTED,
                                    WRAPS_PROOF_TIMEOUT,
                                    LOG_POLL_INTERVAL,
                                    () -> new SpecOperation[] {fundingTransfer(), sleepFor(TRANSFER_PACING_MS)})
                            .loggingOff(),
                    prepareFakeUpgrade(),
                    sourcing(() -> upgradeKeepingPorts(CURRENT_CONFIG_VERSION.get() + 1, FRESH_GENESIS_UPGRADE_ENV)),
                    untilHgcaaLogContainsText(
                                    allNodes(),
                                    FRESH_GENESIS_REQUESTED,
                                    FRESH_GENESIS_REQUEST_TIMEOUT,
                                    LOG_POLL_INTERVAL,
                                    () -> new SpecOperation[] {fundingTransfer(), sleepFor(TRANSFER_PACING_MS)})
                            .loggingOff(),
                    untilHgcaaLogContainsPattern(
                                    allNodes(),
                                    FRESH_GENESIS_CONSTRUCTION_PATTERN,
                                    FRESH_GENESIS_REQUEST_TIMEOUT,
                                    LOG_POLL_INTERVAL,
                                    () -> new SpecOperation[] {fundingTransfer(), sleepFor(TRANSFER_PACING_MS)})
                            .loggingOff()
                            .exposingMatchGroupTo(1, constructionId),
                    sourcing(() -> untilHgcaaLogContainsText(
                                    allNodes(),
                                    "History proof constructed (#" + constructionId.get() + ", WRAPS-extensible? true)",
                                    WRAPS_PROOF_TIMEOUT,
                                    LOG_POLL_INTERVAL,
                                    () -> new SpecOperation[] {fundingTransfer(), sleepFor(TRANSFER_PACING_MS)})
                            .loggingOff()));
        }));
    }

    @HapiTest
    @Order(1)
    final Stream<DynamicTest> genesisAndIncrementalWrapsProofsConstructed() {
        return hapiTest(sourcingContextual(spec -> {
            if (hasWrapsArtifactsPath()) {
                StateChangesValidator.ADAPTIVE_SIGNATURE_CHECKS_ENABLED.set(true);
                StateChangesValidator.AT_LEAST_ONE_WRAPS_ASSERTION_ENABLED.set(true);
                return blockingOrder(
                        // Staking to the nodes rotates their weights at the next stake period boundary, which
                        // is what drives the incremental proof
                        NODE0_STAKER.getInfo(),
                        NODE1_STAKER.getInfo(),
                        NODE2_STAKER.getInfo(),
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

    private static boolean hasWrapsArtifactsPath() {
        final var wrapsArtifactsPath = System.getProperty("hapi.spec.tssLibWrapsArtifactsPath");
        return wrapsArtifactsPath != null && !wrapsArtifactsPath.isBlank();
    }

    /**
     * Upgrades the network to the given configuration version with the given environment overrides, restarting
     * every node on the ports it already has. The usual upgrade reassigns ports and has the nodes adopt the
     * resulting override roster; with TSS constructions in state that is a roster change the network has not
     * prepared for, whereas a production upgrade leaves the roster alone unless a candidate roster was adopted.
     */
    private static SpecOperation upgradeKeepingPorts(final int version, @NonNull final Map<String, String> env) {
        return blockingOrder(
                runBackgroundTrafficUntilFreezeComplete(),
                sourcing(() -> freezeUpgrade()
                        .startingIn(2)
                        .seconds()
                        .withUpdateFile(DEFAULT_UPGRADE_FILE_ID)
                        .havingHash(upgradeFileHashAt(FAKE_UPGRADE_ZIP_LOC))),
                LifecycleTest.confirmFreezeAndShutdown(),
                new TryToStartNodesOp(allNodes(), version, ReassignPorts.NO, env),
                doAdhoc(() -> CURRENT_CONFIG_VERSION.set(version)),
                waitForActive(allNodes(), RESTART_TIMEOUT),
                doingContextual(spec -> {
                    if (spec.targetNetworkOrThrow() instanceof SubProcessNetwork subProcessNetwork) {
                        subProcessNetwork.refreshClients();
                    }
                }));
    }

    private static SpecOperation fundingTransfer() {
        return cryptoTransfer(tinyBarsFromTo(GENESIS, FUNDING, ONE_HBAR));
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
