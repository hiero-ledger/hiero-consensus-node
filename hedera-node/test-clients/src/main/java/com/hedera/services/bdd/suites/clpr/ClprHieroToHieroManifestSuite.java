// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.clpr;

import static com.hedera.services.bdd.junit.TestTags.MULTINETWORK;
import static com.hedera.services.bdd.spec.HapiSpec.networkHapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.junit.ConfigOverride;
import com.hedera.services.bdd.junit.MultiNetworkHapiTest;
import com.hedera.services.bdd.junit.MultiNetworkHapiTest.Network;
import com.hedera.services.bdd.junit.MultiNetworkLeakyHapiTest;
import com.hedera.services.bdd.junit.hedera.subprocess.SubProcessNetwork;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * Multi-network HAPI suite exercising the manifest-enabled path end-to-end: both ledgers self-derive
 * endpoint manifests from their rosters, and the endpoint rotation scenarios of spec §8.1 rotate a
 * manifest by changing a ledger's roster — adding and deleting consensus nodes through DAB upgrades.
 *
 * <p>Every test shares the {@code ledgerA_manifest} / {@code ledgerB_manifest} networks, and test order is
 * not guaranteed, so the roster-changing tests work from whatever roster they find: they pick new node ids
 * after the highest one the network has had, replace the nodes present when they start, and assert manifests
 * relative to the version they first observe. {@code maxNodeIds} covers every node the tests can add in any
 * order (node ids are never reused), and {@code maxLiveNodes} bounds each ledger's live nodes while the other
 * is up — a complete turnover runs more only while the peer ledger is shut down.
 */
@Tag(MULTINETWORK)
@MultiNetworkLeakyHapiTest
public class ClprHieroToHieroManifestSuite extends HieroToHieroBase {

    // Network names as constants so the (typo-prone) fixture keys are defined once. Every test in this
    // suite shares the HAPI TLS ledgerA_manifest / ledgerB_manifest networks. Using them (rather than a
    // plain manifest-enabled ledgerA/ledgerB) keeps the endpoint-manifest feature and its roster changes
    // isolated to this suite, so they never leak into the networks the non-manifest suites share.
    private static final String LEDGER_A_MANIFEST = "ledgerA_manifest";
    private static final String LEDGER_B_MANIFEST = "ledgerB_manifest";

    // HAPI TLS listener port bases (node i binds base + i). Distinct from ClprHieroToHieroTlsSuite's
    // 41450/42450 and wide enough for every node id below each ledger's maxNodeIds.
    private static final int HAPI_TLS_PORT_A = 43450;
    private static final int HAPI_TLS_PORT_B = 44450;

    // Node ids the tests can use in total, whatever order they run in: A starts with 2 nodes; partial
    // rotation replaces 1, and each complete turnover replaces all 2. B starts with 1; only the
    // simultaneous turnover replaces it.
    private static final int MAX_NODE_IDS_A = 2 + 1 + 2 + 2;
    private static final int MAX_NODE_IDS_B = 1 + 1;
    // A runs 3 nodes during a partial rotation while B is up; a complete turnover of A (4 live nodes) and
    // of B (2 live nodes) each run while the other ledger is shut down, so the group never exceeds 3 + 1.
    private static final int MAX_LIVE_NODES_A = 3;
    private static final int MAX_LIVE_NODES_B = 1;
    // A node added at an upgrade publishes its history proof key during the next weight rotation, though it is not
    // yet among the signers that rotation waits for. That extra key defeats the "every expected key is published"
    // early start, so the proof is only assembled once tss.transitionProofKeyGracePeriod (300s by default) ends.
    // Shorten it so each roster change's rotations do not each idle five minutes.
    private static final String PROOF_KEY_GRACE_PERIOD = "2s";
    // A hinTS construction whose target includes a node that is not yet a hinTS party (one added at an upgrade) waits
    // tss.transitionHintsKeyGracePeriod (300s by default) for that node to publish its hinTS key; keep the wait short.
    private static final String HINTS_KEY_GRACE_PERIOD = "2s";

    @MultiNetworkHapiTest({
        @Network(
                name = LEDGER_A_MANIFEST,
                size = 2,
                maxNodeIds = MAX_NODE_IDS_A,
                maxLiveNodes = MAX_LIVE_NODES_A,
                enableHapiTls = true,
                firstHapiTlsPort = HAPI_TLS_PORT_A,
                setupOverrides = {
                    // Let the circuit breaker exclude peers it can't reach so a rotation doesn't
                    // spam sync failures against departed endpoints (see ClprSynchronizerImpl.selectPeer).
                    @ConfigOverride(key = "clpr.syncPeerExclusionEnabled", value = "true"),
                    @ConfigOverride(key = "tss.transitionProofKeyGracePeriod", value = PROOF_KEY_GRACE_PERIOD),
                    @ConfigOverride(key = "tss.transitionHintsKeyGracePeriod", value = HINTS_KEY_GRACE_PERIOD)
                }),
        @Network(
                name = LEDGER_B_MANIFEST,
                maxNodeIds = MAX_NODE_IDS_B,
                maxLiveNodes = MAX_LIVE_NODES_B,
                enableHapiTls = true,
                firstHapiTlsPort = HAPI_TLS_PORT_B,
                setupOverrides = {
                    @ConfigOverride(key = "clpr.syncPeerExclusionEnabled", value = "true"),
                    @ConfigOverride(key = "tss.transitionProofKeyGracePeriod", value = PROOF_KEY_GRACE_PERIOD),
                    @ConfigOverride(key = "tss.transitionHintsKeyGracePeriod", value = HINTS_KEY_GRACE_PERIOD)
                })
    })
    @DisplayName("Manifest-enabled round-trip (HAPI TLS): both ledgers self-derive manifests, complete a "
            + "TLS channel, and exchange messages both ways")
    Stream<DynamicTest> manifestEnabledRoundTrip(final SubProcessNetwork ledgerA, final SubProcessNetwork ledgerB) {
        final var crypto = new ClprCrypto();
        return multiNetworkHapiTest(
                "manifestEnabledRoundTrip",
                Stream.concat(
                        setupBothNetworksWithManifestProof(ledgerA, ledgerB, HAPI_TLS_PORT_A, HAPI_TLS_PORT_B, crypto),
                        Stream.of(
                                sendMessages(ledgerA, crypto, "callerA", "hello-manifest-1", "hello-manifest-2"),
                                awaitReceivedMessage(ledgerB, crypto.channelId, 2),
                                awaitAckedMessage(ledgerA, crypto.channelId, 2),
                                sendMessages(ledgerB, crypto, "callerB", "reply-manifest-1", "reply-manifest-2"),
                                awaitReceivedMessage(ledgerA, crypto.channelId, 2),
                                awaitAckedMessage(ledgerB, crypto.channelId, 2))));
    }

    @MultiNetworkHapiTest({
        @Network(
                name = LEDGER_A_MANIFEST,
                size = 2,
                maxNodeIds = MAX_NODE_IDS_A,
                maxLiveNodes = MAX_LIVE_NODES_A,
                enableHapiTls = true,
                firstHapiTlsPort = HAPI_TLS_PORT_A,
                setupOverrides = {
                    @ConfigOverride(key = "clpr.syncPeerExclusionEnabled", value = "true"),
                    @ConfigOverride(key = "tss.transitionProofKeyGracePeriod", value = PROOF_KEY_GRACE_PERIOD),
                    @ConfigOverride(key = "tss.transitionHintsKeyGracePeriod", value = HINTS_KEY_GRACE_PERIOD)
                }),
        @Network(
                name = LEDGER_B_MANIFEST,
                maxNodeIds = MAX_NODE_IDS_B,
                maxLiveNodes = MAX_LIVE_NODES_B,
                enableHapiTls = true,
                firstHapiTlsPort = HAPI_TLS_PORT_B,
                setupOverrides = {
                    @ConfigOverride(key = "clpr.syncPeerExclusionEnabled", value = "true"),
                    @ConfigOverride(key = "tss.transitionProofKeyGracePeriod", value = PROOF_KEY_GRACE_PERIOD),
                    @ConfigOverride(key = "tss.transitionHintsKeyGracePeriod", value = HINTS_KEY_GRACE_PERIOD)
                })
    })
    @DisplayName("8.1.1 partial rotation: A replaces one node; A's advanced manifest reaches B in a bundle "
            + "proof while B keeps syncing through A's remaining node, and no messages are lost")
    Stream<DynamicTest> partialRotationPropagatesToPeer(
            final SubProcessNetwork ledgerA, final SubProcessNetwork ledgerB) {
        final var crypto = new ClprCrypto();
        final AtomicLong aVersionBaseline = new AtomicLong();
        final AtomicLong aVersionRotated = new AtomicLong();
        final List<Long> addedNodeIds = new ArrayList<>();

        return multiNetworkHapiTest(
                "partialRotationPropagatesToPeer",
                Stream.of(
                                setupBothNetworksWithManifestProof(
                                        ledgerA, ledgerB, HAPI_TLS_PORT_A, HAPI_TLS_PORT_B, crypto),
                                Stream.of(captureManifestVersion(ledgerA, aVersionBaseline)),
                                // Add one node to A, then delete its lowest-id node; the rest stay up throughout
                                replaceNodes(ledgerA, network -> 1, addedNodeIds),
                                Stream.of(
                                        // A's manifest advanced and advertises exactly its new roster
                                        awaitManifestMatchesRoster(
                                                ledgerA, HAPI_TLS_PORT_A, aVersionBaseline, aVersionRotated),
                                        // A -> B: B's view of A's manifest is stale (#335 staleness gate), so A
                                        // embeds a proof of its manifest in the bundle
                                        sendMessages(ledgerA, crypto, "callerA2", "post-rotation"),
                                        // B applies it (Step 1b), and it carries A's new node's endpoint
                                        awaitManifestAppliedOnPeer(
                                                ledgerB,
                                                aVersionRotated,
                                                () -> tlsPorts(HAPI_TLS_PORT_A, addedNodeIds)),
                                        awaitReceivedMessage(ledgerB, crypto.channelId, 1),
                                        deferredAwaitAckedMessage(ledgerA, crypto, 1),
                                        // B -> A on the new manifest, delivered and acked
                                        sendMessages(ledgerB, crypto, "callerB2", "post-rotation-reply"),
                                        deferredAwaitReceivedMessage(ledgerA, crypto, 1),
                                        awaitAckedMessage(ledgerB, crypto.channelId, 1)))
                        .flatMap(steps -> steps));
    }

    @MultiNetworkHapiTest({
        @Network(
                name = LEDGER_A_MANIFEST,
                size = 2,
                maxNodeIds = MAX_NODE_IDS_A,
                maxLiveNodes = MAX_LIVE_NODES_A,
                enableHapiTls = true,
                firstHapiTlsPort = HAPI_TLS_PORT_A,
                setupOverrides = {
                    @ConfigOverride(key = "clpr.syncPeerExclusionEnabled", value = "true"),
                    @ConfigOverride(key = "tss.transitionProofKeyGracePeriod", value = PROOF_KEY_GRACE_PERIOD),
                    @ConfigOverride(key = "tss.transitionHintsKeyGracePeriod", value = HINTS_KEY_GRACE_PERIOD)
                }),
        @Network(
                name = LEDGER_B_MANIFEST,
                maxNodeIds = MAX_NODE_IDS_B,
                maxLiveNodes = MAX_LIVE_NODES_B,
                enableHapiTls = true,
                firstHapiTlsPort = HAPI_TLS_PORT_B,
                setupOverrides = {
                    @ConfigOverride(key = "clpr.syncPeerExclusionEnabled", value = "true"),
                    @ConfigOverride(key = "tss.transitionProofKeyGracePeriod", value = PROOF_KEY_GRACE_PERIOD),
                    @ConfigOverride(key = "tss.transitionHintsKeyGracePeriod", value = HINTS_KEY_GRACE_PERIOD)
                })
    })
    @Disabled("Need a fix to hydrate sync cache from manifest on restart")
    @DisplayName("8.1.2 complete turnover (one side): A replaces all its nodes while partitioned from B; B "
            + "recovers A's manifest via clprGetEndpointManifest + clprSubmitBundle, with no gRPC to A's old nodes")
    Stream<DynamicTest> completeTurnoverOneSideRecoversViaSubmitBundle(
            final SubProcessNetwork ledgerA, final SubProcessNetwork ledgerB) {
        final var crypto = new ClprCrypto();
        // A's manifest version B cached at connection time; the recovery is expressed relative to it
        final AtomicLong aVersionBaseline = new AtomicLong();
        final AtomicLong aVersionTurnedOver = new AtomicLong();
        final AtomicReference<ByteString> aManifestProof = new AtomicReference<>();
        final List<Long> addedNodeIds = new ArrayList<>();

        return multiNetworkHapiTest(
                "completeTurnoverOneSideRecoversViaSubmitBundle",
                Stream.of(
                                setupBothNetworksWithManifestProof(
                                        ledgerA, ledgerB, HAPI_TLS_PORT_A, HAPI_TLS_PORT_B, crypto),
                                Stream.of(
                                        captureManifestVersion(ledgerA, aVersionBaseline),
                                        // Partition B (R1: endpoints rotated during partition), so A cannot push
                                        // any of its intermediate manifests to it
                                        shutDownLedger(ledgerB)),
                                // Replace every node of A: B's entire cached A-endpoint set is now gone
                                replaceNodes(ledgerA, network -> network.nodes().size(), addedNodeIds),
                                Stream.of(
                                        awaitManifestMatchesRoster(
                                                ledgerA, HAPI_TLS_PORT_A, aVersionBaseline, aVersionTurnedOver),
                                        restartLedger(ledgerB),
                                        // Out-of-band recovery: read-only query of A's manifest + its state proof...
                                        deferred(
                                                "Capture manifest proof of " + ledgerA.name(),
                                                () -> captureManifestProof(ledgerA, aManifestProof)),
                                        // ...submitted on B via clprSubmitBundle. B's endpoints did not change, so A
                                        // can still reach B and may push its manifest first: accept SUCCESS (this
                                        // submit applied it) or CLPR_BUNDLE_VERIFICATION_FAILED (already applied).
                                        // No gRPC to any A endpoint is used by this submit (spec §8.1.4).
                                        submitManifestRecoveryBundle(
                                                ledgerB,
                                                crypto.channelId,
                                                aManifestProof,
                                                ResponseCodeEnum.SUCCESS,
                                                ResponseCodeEnum.CLPR_BUNDLE_VERIFICATION_FAILED),
                                        // Deterministic outcome: B's cached peer manifest is A's new one
                                        awaitManifestAppliedOnPeer(
                                                ledgerB,
                                                aVersionTurnedOver,
                                                () -> tlsPorts(HAPI_TLS_PORT_A, addedNodeIds)),
                                        // B -> A reaches only A's new nodes; then A -> B
                                        sendMessages(ledgerB, crypto, "callerB2", "post-turnover"),
                                        deferredAwaitReceivedMessage(ledgerA, crypto, 1),
                                        awaitAckedMessage(ledgerB, crypto.channelId, 1),
                                        sendMessages(ledgerA, crypto, "callerA2", "post-turnover-reply"),
                                        awaitReceivedMessage(ledgerB, crypto.channelId, 1),
                                        deferredAwaitAckedMessage(ledgerA, crypto, 1)))
                        .flatMap(steps -> steps));
    }

    @MultiNetworkHapiTest({
        @Network(
                name = LEDGER_A_MANIFEST,
                size = 2,
                maxNodeIds = MAX_NODE_IDS_A,
                maxLiveNodes = MAX_LIVE_NODES_A,
                enableHapiTls = true,
                firstHapiTlsPort = HAPI_TLS_PORT_A,
                setupOverrides = {
                    @ConfigOverride(key = "clpr.syncPeerExclusionEnabled", value = "true"),
                    @ConfigOverride(key = "tss.transitionProofKeyGracePeriod", value = PROOF_KEY_GRACE_PERIOD),
                    @ConfigOverride(key = "tss.transitionHintsKeyGracePeriod", value = HINTS_KEY_GRACE_PERIOD)
                }),
        @Network(
                name = LEDGER_B_MANIFEST,
                maxNodeIds = MAX_NODE_IDS_B,
                maxLiveNodes = MAX_LIVE_NODES_B,
                enableHapiTls = true,
                firstHapiTlsPort = HAPI_TLS_PORT_B,
                setupOverrides = {
                    @ConfigOverride(key = "clpr.syncPeerExclusionEnabled", value = "true"),
                    @ConfigOverride(key = "tss.transitionProofKeyGracePeriod", value = PROOF_KEY_GRACE_PERIOD),
                    @ConfigOverride(key = "tss.transitionHintsKeyGracePeriod", value = HINTS_KEY_GRACE_PERIOD)
                })
    })
    @Disabled("Need a fix to hydrate sync cache from manifest on restart")
    @DisplayName("8.1.3 simultaneous turnover (both sides): A and B each replace all their nodes while partitioned; "
            + "each side independently recovers the other's manifest via clprGetEndpointManifest + clprSubmitBundle")
    Stream<DynamicTest> simultaneousTurnoverBothSidesRecoverIndependently(
            final SubProcessNetwork ledgerA, final SubProcessNetwork ledgerB) {
        final var crypto = new ClprCrypto();
        final AtomicLong aVersionBaseline = new AtomicLong();
        final AtomicLong bVersionBaseline = new AtomicLong();
        final AtomicLong aVersionTurnedOver = new AtomicLong();
        final AtomicLong bVersionTurnedOver = new AtomicLong();
        final AtomicReference<ByteString> aManifestProof = new AtomicReference<>();
        final AtomicReference<ByteString> bManifestProof = new AtomicReference<>();
        final List<Long> addedNodeIdsA = new ArrayList<>();
        final List<Long> addedNodeIdsB = new ArrayList<>();

        return multiNetworkHapiTest(
                "simultaneousTurnoverBothSidesRecoverIndependently",
                Stream.of(
                                setupBothNetworksWithManifestProof(
                                        ledgerA, ledgerB, HAPI_TLS_PORT_A, HAPI_TLS_PORT_B, crypto),
                                Stream.of(
                                        captureManifestVersion(ledgerA, aVersionBaseline),
                                        captureManifestVersion(ledgerB, bVersionBaseline),
                                        // The ledgers never see each other between here and both turnovers being
                                        // done: each turns over while the other is shut down, so neither can push
                                        // a manifest to the other
                                        shutDownLedger(ledgerB)),
                                replaceNodes(ledgerA, network -> network.nodes().size(), addedNodeIdsA),
                                Stream.of(
                                        awaitManifestMatchesRoster(
                                                ledgerA, HAPI_TLS_PORT_A, aVersionBaseline, aVersionTurnedOver),
                                        shutDownLedger(ledgerA),
                                        restartLedger(ledgerB)),
                                replaceNodes(ledgerB, network -> network.nodes().size(), addedNodeIdsB),
                                Stream.of(
                                        awaitManifestMatchesRoster(
                                                ledgerB, HAPI_TLS_PORT_B, bVersionBaseline, bVersionTurnedOver),
                                        // Every endpoint each side cached for the other is gone
                                        restartLedger(ledgerA),
                                        // With all connectivity broken the manual submitBundle is the only way to
                                        // apply either manifest, so each awaitManifestAppliedOnPeer proves the
                                        // manifest-only recovery bundle was accepted. Order does not matter.
                                        // B recovers A...
                                        deferred(
                                                "Capture manifest proof of " + ledgerA.name(),
                                                () -> captureManifestProof(ledgerA, aManifestProof)),
                                        deferred(
                                                "Submit A's manifest on " + ledgerB.name(),
                                                () -> submitManifestRecoveryBundle(
                                                        ledgerB,
                                                        crypto.channelId,
                                                        aManifestProof,
                                                        ResponseCodeEnum.SUCCESS,
                                                        ResponseCodeEnum.CLPR_BUNDLE_VERIFICATION_FAILED)),
                                        awaitManifestAppliedOnPeer(
                                                ledgerB,
                                                aVersionTurnedOver,
                                                () -> tlsPorts(HAPI_TLS_PORT_A, addedNodeIdsA)),
                                        // ...and A recovers B
                                        deferred(
                                                "Capture manifest proof of " + ledgerB.name(),
                                                () -> captureManifestProof(ledgerB, bManifestProof)),
                                        deferred(
                                                "Submit B's manifest on " + ledgerA.name(),
                                                () -> submitManifestRecoveryBundle(
                                                        ledgerA,
                                                        crypto.channelId,
                                                        bManifestProof,
                                                        ResponseCodeEnum.SUCCESS,
                                                        ResponseCodeEnum.CLPR_BUNDLE_VERIFICATION_FAILED)),
                                        awaitManifestAppliedOnPeer(
                                                ledgerA,
                                                bVersionTurnedOver,
                                                () -> tlsPorts(HAPI_TLS_PORT_B, addedNodeIdsB)),
                                        // Normal sync resumes both ways over the recovered manifests
                                        sendMessages(ledgerA, crypto, "callerA2", "post-turnover"),
                                        deferredAwaitReceivedMessage(ledgerB, crypto, 1),
                                        deferredAwaitAckedMessage(ledgerA, crypto, 1),
                                        sendMessages(ledgerB, crypto, "callerB2", "post-turnover-reply"),
                                        deferredAwaitReceivedMessage(ledgerA, crypto, 1),
                                        deferredAwaitAckedMessage(ledgerB, crypto, 1)))
                        .flatMap(steps -> steps));
    }

    /** The HAPI TLS ports ({@code tlsPortBase + nodeId}) of the given nodes, which is where their endpoints are. */
    private static List<Integer> tlsPorts(final int tlsPortBase, final List<Long> nodeIds) {
        return nodeIds.stream().map(nodeId -> tlsPortBase + nodeId.intValue()).toList();
    }

    /**
     * Sends one CLPR message per payload from {@code network} through a fresh caller and caller contract.
     * Deferred, so it reaches the network's current nodes after a roster change.
     */
    private static DynamicTest sendMessages(
            final SubProcessNetwork network, final ClprCrypto crypto, final String caller, final String... payloads) {
        return deferred("Send " + payloads.length + " message(s) from " + network.name(), () -> networkHapiTest(
                        "Send " + payloads.length + " message(s) from " + network.name(),
                        network,
                        Stream.concat(
                                        Stream.of(
                                                cryptoCreate(caller).balance(ONE_HUNDRED_HBARS),
                                                uploadInitCode(CLPR_CONTRACT),
                                                contractCreate(CLPR_CONTRACT)),
                                        Stream.of(payloads).map(payload -> contractCall(
                                                        CLPR_CONTRACT,
                                                        SEND_MESSAGE,
                                                        crypto.channelId,
                                                        crypto.connectorId,
                                                        new byte[20],
                                                        payload.getBytes(StandardCharsets.UTF_8))
                                                .gas(GAS)
                                                .payingWith(caller)))
                                .toArray(SpecOperation[]::new))
                .findFirst()
                .orElseThrow());
    }

    private static DynamicTest deferredAwaitReceivedMessage(
            final SubProcessNetwork network, final ClprCrypto crypto, final long minCount) {
        return deferred(
                "Await received messages on " + network.name(),
                () -> awaitReceivedMessage(network, crypto.channelId, minCount));
    }

    private static DynamicTest deferredAwaitAckedMessage(
            final SubProcessNetwork network, final ClprCrypto crypto, final long minCount) {
        return deferred(
                "Await acked messages on " + network.name(),
                () -> awaitAckedMessage(network, crypto.channelId, minCount));
    }
}
