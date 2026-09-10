// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.clpr;

import static com.hedera.services.bdd.junit.TestTags.MULTINETWORK;
import static com.hedera.services.bdd.junit.hedera.NodeSelector.allNodes;
import static com.hedera.services.bdd.spec.HapiSpec.networkHapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.blockingOrder;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doAdhoc;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.freezeOnly;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.freezeUpgrade;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.runBackgroundTrafficUntilFreezeComplete;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.waitForActive;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.waitForFrozenNetwork;
import static com.hedera.services.bdd.spec.utilops.upgrade.BuildUpgradeZipOp.FAKE_UPGRADE_ZIP_LOC;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.freeze.CommonUpgradeResources.DEFAULT_UPGRADE_FILE_ID;
import static com.hedera.services.bdd.suites.freeze.CommonUpgradeResources.upgradeFileHashAt;
import static com.hedera.services.bdd.suites.regression.system.LifecycleTest.confirmFreezeAndShutdown;

import com.hedera.services.bdd.junit.ConfigOverride;
import com.hedera.services.bdd.junit.MultiNetworkHapiTest;
import com.hedera.services.bdd.junit.MultiNetworkHapiTest.Network;
import com.hedera.services.bdd.junit.hedera.subprocess.SubProcessNetwork;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.utilops.FakeNmt;
import com.hedera.services.bdd.suites.regression.system.LifecycleTest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * Multi-network restart regression for the outbound-sync orchestrator.
 *
 * <p>{@code ClprChannelManager}'s in-memory channel registry, per-channel sync timers,
 * intervals, and peer endpoints are runtime-only — they are not part of consensus state, so they
 * are empty after a node restart. Because the {@code CHANNELS} key/value state cannot be
 * iterated, the orchestrator rebuilds them on startup from a <b>node-local file</b>
 * ({@code data/clpr/peer-endpoints.json}, configured by {@code clpr.peerEndpointsFile}) that the
 * manager rewrites on every channel lifecycle change (activated / closed / endpoints updated).
 * Without that rehydration an already-ACTIVE channel's outbound sync is permanently dead after a
 * restart — the channel never re-triggers {@code onChannelActivated}.
 *
 * <p>This suite exercises several restart paths end to end across two subprocess ledgers:
 * <ul>
 *   <li>{@link #restartBMidBundleGraceful} — a plain freeze + restart of B in the middle of a bundle
 *       stream, proving no message loss while A keeps retrying;</li>
 *   <li>{@link #channelSurvivesFreezeUpgradeRestartOfBASendsFirst} and
 *       {@link #channelSurvivesFreezeUpgradeRestartOfBBSendsFirst} — a freeze + software-upgrade
 *       restart of B (on the same gRPC port) with bidirectional messaging before and after, proving
 *       the channel and B's outbound sync survive the restart. They differ only in which side sends
 *       the FIRST post-restart message. Submitting to B immediately after its restart can race B's
 *       gRPC rebind (the WRAPS readiness gates read {@code hgcaa.log}, which persists across the
 *       restart, so they can return before the gRPC server is back), so the {@code ASendsFirst}
 *       variant has A (never restarted) send first to give B time to bind, while the
 *       {@code BSendsFirst} variant submits to B first.</li>
 * </ul>
 *
 * <p><b>Same-port restart.</b> Cross-network CLPR sync reaches a peer at the {@code ip:port} stored
 * in that peer's attested ledger configuration and in the node-local peer-endpoints file, not via the
 * platform address book. The stock {@code LifecycleTest.upgradeToConfigVersion} restarts via
 * {@code FakeNmt.restartNetwork} ({@code ReassignPorts.YES}), which would bring B back on a new port
 * and permanently strand A's cached endpoint. This suite instead restarts via
 * {@code FakeNmt.restartWithConfigVersion} ({@code ReassignPorts.NO}) so B keeps its gRPC port and
 * A↔B connectivity survives the restart.
 *
 * <p>{@code clpr.retryMaxAttempts=100} on A keeps its sync workflow retrying while B is down rather
 * than tripping the CircuitBreaker's default 120-second cooldown.
 */
@Tag(MULTINETWORK)
public class ClprHieroToHieroRestartSuite extends HieroToHieroBase implements LifecycleTest {

    private static final Duration FREEZE_WAIT_TIMEOUT = Duration.ofMinutes(2);
    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofMinutes(1);
    private static final Duration RESTART_TIMEOUT = Duration.ofMinutes(5);

    @MultiNetworkHapiTest({
        @Network(name = "ledgerA", setupOverrides = @ConfigOverride(key = "clpr.retryMaxAttempts", value = "100")),
        @Network("ledgerB")
    })
    @DisplayName("Graceful restart of B mid-bundle → B resumes and delivers all 3 messages")
    Stream<DynamicTest> restartBMidBundleGraceful(final SubProcessNetwork ledgerA, final SubProcessNetwork ledgerB) {
        final var crypto = new ClprCrypto();
        final int portA = ledgerA.nodes().getFirst().getGrpcPort();
        final int portB = ledgerB.nodes().getFirst().getGrpcPort();
        return Stream.concat(
                setupBothNetworks(ledgerA, ledgerB, portA, portB, crypto),
                Stream.of(
                        sendNMessages(ledgerA, crypto, "callerA", "before-restart", 3),
                        // Prove the bundle stream is in flight before we restart B.
                        awaitReceivedMessage(ledgerB, crypto.channelId, 1),
                        // Freeze + shutdown + restart must all live in ONE networkHapiTest block:
                        // after FREEZE_COMPLETE B's gRPC server is gone, so a fresh spec against
                        // ledgerB would fail to bootstrap.
                        networkHapiTest(
                                        "Freeze, shut down, and restart B",
                                        ledgerB,
                                        runBackgroundTrafficUntilFreezeComplete(),
                                        freezeOnly().startingIn(2).seconds(),
                                        waitForFrozenNetwork(FREEZE_WAIT_TIMEOUT),
                                        FakeNmt.shutdownWithin(allNodes(), SHUTDOWN_TIMEOUT),
                                        sourcing(() -> FakeNmt.restartWithConfigVersion(
                                                allNodes(), CURRENT_CONFIG_VERSION.incrementAndGet())),
                                        waitForActive(allNodes(), RESTART_TIMEOUT),
                                        blockingOrder(doAdhoc(() -> ledgerB.awaitLedgerId(RESTART_TIMEOUT))))
                                .findFirst()
                                .orElseThrow(),
                        awaitReceivedMessage(ledgerB, crypto.channelId, 3)));
    }

    @MultiNetworkHapiTest({
        @Network(name = "ledgerA", setupOverrides = @ConfigOverride(key = "clpr.retryMaxAttempts", value = "100")),
        @Network("ledgerB")
    })
    @DisplayName("Restart of B, then A sends first: bidirectional messaging resumes")
    Stream<DynamicTest> channelSurvivesFreezeUpgradeRestartOfBASendsFirst(
            final SubProcessNetwork ledgerA, final SubProcessNetwork ledgerB) {
        final var crypto = new ClprCrypto();
        final int portA = ledgerA.nodes().getFirst().getGrpcPort();
        final int portB = ledgerB.nodes().getFirst().getGrpcPort();

        return Stream.concat(
                setupBothNetworks(ledgerA, ledgerB, portA, portB, crypto),
                Stream.of(
                        // Counter model — every inbound DATA message makes the receiver enqueue an
                        // auto-Reply on its OWN outbound queue (ClprSubmitBundleHandler#enqueueReply),
                        // sharing the same next_message_id counter as explicit sends. So each side's
                        // outbound id stream interleaves its DATA with its replies to the peer, and a
                        // full round-trip advances both per-direction high-water marks
                        // (receivedMessageId / ackedMessageId) by 2. The minCounts below are tight: each
                        // is reached only when the message under test arrives/acks — not by the prior
                        // round-trip's reply (which a lower value would already satisfy, proving nothing).

                        // ── Pre-restart: prove the channel is healthy in both directions ──
                        sendNMessages(ledgerA, crypto, "callerApre", "a-pre", 1),
                        awaitReceivedMessage(ledgerB, crypto.channelId, 1), // a-pre A#1
                        awaitAckedMessage(ledgerA, crypto.channelId, 1), // B acks A#1
                        sendNMessages(ledgerB, crypto, "callerBpre", "b-pre", 1),
                        awaitReceivedMessage(ledgerA, crypto.channelId, 2), // b-pre B#2 (B#1 was reply→a-pre)
                        awaitAckedMessage(ledgerB, crypto.channelId, 2), // A acks up to B#2

                        // ── Restart B via freeze + software upgrade, keeping its gRPC port ──
                        freezeUpgradeRestartSamePort(ledgerB),

                        // ── Post-restart A→B first: a-post=A#3 — A (never restarted) sends first,
                        //    giving B time to finish binding before anything is submitted to it. ──
                        sendNMessages(ledgerA, crypto, "callerApost", "a-post", 1),
                        awaitReceivedMessage(ledgerB, crypto.channelId, 3),
                        awaitAckedMessage(ledgerA, crypto.channelId, 3),
                        // ── Post-restart B→A second: b-post=B#4. ──
                        sendNMessages(ledgerB, crypto, "callerBpost", "b-post", 1),
                        awaitReceivedMessage(ledgerA, crypto.channelId, 4),
                        awaitAckedMessage(ledgerB, crypto.channelId, 4)));
    }

    @MultiNetworkHapiTest({
        @Network(name = "ledgerA", setupOverrides = @ConfigOverride(key = "clpr.retryMaxAttempts", value = "100")),
        @Network("ledgerB")
    })
    @DisplayName("Restart of B, then B sends first: bidirectional messaging resumes")
    Stream<DynamicTest> channelSurvivesFreezeUpgradeRestartOfBBSendsFirst(
            final SubProcessNetwork ledgerA, final SubProcessNetwork ledgerB) {
        final var crypto = new ClprCrypto();
        final int portA = ledgerA.nodes().getFirst().getGrpcPort();
        final int portB = ledgerB.nodes().getFirst().getGrpcPort();

        return Stream.concat(
                setupBothNetworks(ledgerA, ledgerB, portA, portB, crypto),
                Stream.of(
                        // Counter model — every inbound DATA message makes the receiver enqueue an
                        // auto-Reply on its OWN outbound queue (ClprSubmitBundleHandler#enqueueReply),
                        // sharing the same next_message_id counter as explicit sends. So each side's
                        // outbound id stream interleaves its DATA with its replies to the peer, and a
                        // full round-trip advances both per-direction high-water marks
                        // (receivedMessageId / ackedMessageId) by 2. The minCounts below are tight: each
                        // is reached only when the message under test arrives/acks — not by the prior
                        // round-trip's reply (which a lower value would already satisfy, proving nothing).

                        // ── Pre-restart: prove the channel is healthy in both directions ──
                        sendNMessages(ledgerA, crypto, "callerApre", "a-pre", 1),
                        awaitReceivedMessage(ledgerB, crypto.channelId, 1), // a-pre A#1
                        awaitAckedMessage(ledgerA, crypto.channelId, 1), // B acks A#1
                        sendNMessages(ledgerB, crypto, "callerBpre", "b-pre", 1),
                        awaitReceivedMessage(ledgerA, crypto.channelId, 2), // b-pre B#2 (B#1 was reply→a-pre)
                        awaitAckedMessage(ledgerB, crypto.channelId, 2), // A acks up to B#2

                        // ── Restart B via freeze + software upgrade, keeping its gRPC port ──
                        freezeUpgradeRestartSamePort(ledgerB),

                        // ── Post-restart B→A first: b-post=B#3 — B (just restarted) sends first. This
                        //    submits to B right after its restart, which can race B's gRPC rebind. ──
                        sendNMessages(ledgerB, crypto, "callerBpost", "b-post", 1),
                        awaitReceivedMessage(ledgerA, crypto.channelId, 3),
                        awaitAckedMessage(ledgerB, crypto.channelId, 3),
                        // ── Post-restart A→B second: a-post=A#4. ──
                        sendNMessages(ledgerA, crypto, "callerApost", "a-post", 1),
                        awaitReceivedMessage(ledgerB, crypto.channelId, 4),
                        awaitAckedMessage(ledgerA, crypto.channelId, 4)));
    }

    /**
     * Enqueues {@code count} outbound CLPR messages from {@code network} via the
     * {@code ClprSystemContract} caller. Each {@code networkHapiTest} gets a fresh spec/registry, so
     * the caller account and the caller contract are (re)created within this block. Payloads are
     * {@code dataPrefix-1 … dataPrefix-count}; only the message counts are asserted downstream.
     */
    private DynamicTest sendNMessages(
            final SubProcessNetwork network,
            final ClprCrypto crypto,
            final String callerName,
            final String dataPrefix,
            final int count) {
        final List<SpecOperation> ops = new ArrayList<>();
        ops.add(cryptoCreate(callerName).balance(ONE_HUNDRED_HBARS));
        ops.add(uploadInitCode(CLPR_CONTRACT));
        ops.add(contractCreate(CLPR_CONTRACT));
        for (int i = 1; i <= count; i++) {
            ops.add(sendOp(crypto, callerName, dataPrefix + "-" + i));
        }
        return networkHapiTest(
                        "Send " + count + " message(s) from " + network.name() + " (" + dataPrefix + ")",
                        network,
                        ops.toArray(new SpecOperation[0]))
                .findFirst()
                .orElseThrow();
    }

    /**
     * Freeze + software-upgrade restart of {@code network}, restarting on the SAME gRPC ports so the
     * peer's cached CLPR endpoint stays valid (see class javadoc). Targets only {@code network}: all
     * ops bind to the enclosing {@code networkHapiTest}'s target network, so the peer keeps running.
     *
     * <p>After the node is ACTIVE again it must resume producing WRAPS-carrying block proofs before
     * its outbound bundles are verifiable by the peer, so this re-awaits WRAPS readiness on the
     * restarted network before the suite continues.
     */
    private DynamicTest freezeUpgradeRestartSamePort(final SubProcessNetwork network) {
        return networkHapiTest(
                        "Freeze + upgrade restart " + network.name() + " (same port)",
                        network,
                        // Stage the fake upgrade ZIP on file 0.0.150 and issue PREPARE_UPGRADE. Only B
                        // upgrades, so there is no cross-network collision on the shared upgrade artifacts.
                        prepareFakeUpgrade(),
                        blockingOrder(
                                runBackgroundTrafficUntilFreezeComplete(),
                                sourcing(() -> freezeUpgrade()
                                        .startingIn(2)
                                        .seconds()
                                        .withUpdateFile(DEFAULT_UPGRADE_FILE_ID)
                                        .havingHash(upgradeFileHashAt(FAKE_UPGRADE_ZIP_LOC))),
                                confirmFreezeAndShutdown(),
                                // ReassignPorts.NO keeps B on the same gRPC port so A's cached CLPR endpoint
                                // stays valid. Increment the config version once (for the software upgrade).
                                FakeNmt.restartWithConfigVersion(allNodes(), CURRENT_CONFIG_VERSION.incrementAndGet()),
                                waitForActive(allNodes(), RESTART_TO_ACTIVE_TIMEOUT),
                                // Block until the reloaded node is again emitting WRAPS-carrying block proofs,
                                // so its post-restart outbound bundles are peer-verifiable.
                                blockingOrder(doAdhoc(() -> {
                                    awaitWrapsExtensible(network);
                                    awaitWrapsSyncPoint(network);
                                    // The WRAPS gates above read hgcaa.log, which is appended across the restart,
                                    // so they can match the PRE-restart sync-point line and return before the
                                    // restarted node's gRPC server has rebound. awaitLedgerId probes the node with
                                    // a precheck round-trip and retries through the gRPC-not-ready-yet window (see
                                    // SubProcessNetwork#awaitLedgerIdReady), so nothing is submitted to the node
                                    // until it can actually accept transactions — closing the post-restart submit
                                    // race (e.g. a B→A send when B is the restarted node).
                                    network.awaitLedgerId(RESTART_TO_ACTIVE_TIMEOUT);
                                }))))
                .findFirst()
                .orElseThrow();
    }
}
