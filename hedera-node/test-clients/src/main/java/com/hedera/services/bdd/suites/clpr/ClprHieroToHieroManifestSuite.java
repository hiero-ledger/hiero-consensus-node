// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.clpr;

import static com.hedera.services.bdd.junit.TestTags.MULTINETWORK;
import static com.hedera.services.bdd.spec.HapiSpec.networkHapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.junit.ConfigOverride;
import com.hedera.services.bdd.junit.MultiNetworkHapiTest;
import com.hedera.services.bdd.junit.MultiNetworkHapiTest.Network;
import com.hedera.services.bdd.junit.extensions.MultiNetworkExtension;
import com.hedera.services.bdd.junit.hedera.subprocess.SubProcessNetwork;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * Multi-network HAPI suite exercising the manifest-enabled path end-to-end (#337 + #343):
 */
@Tag(MULTINETWORK)
public class ClprHieroToHieroManifestSuite extends HieroToHieroBase {

    // Network names as constants so the (typo-prone) fixture keys are defined once. Every test in this
    // suite shares the size-2 mTLS ledgerA_manifest / ledgerB_manifest fixtures. Reusing them (rather
    // than a plain manifest-enabled ledgerA/ledgerB) keeps the endpoint-manifest feature isolated to
    // this suite, so its boot-time flag can never leak into the networks the non-manifest suites share.
    private static final String LEDGER_A_MANIFEST = "ledgerA_manifest";
    private static final String LEDGER_B_MANIFEST = "ledgerB_manifest";

    // Dedicated mTLS listener ports for the rotation test. Distinct from ClprHieroToHieroMtlsSuite's
    // 41450/42450 so the two suites' hard-coded mtlsPorts never collide if scheduled together.
    private static final int MTLS_PORT_A = 43450;
    private static final int MTLS_PORT_B = 44450;
    /** A's new mtlsPort after the rotation restart — a genuinely reachable listener B can dial. */
    private static final int MTLS_PORT_A_ROTATED = 43460;
    /** B's new base mtlsPort after a rotation (8.1.3 both-sides turnover). */
    private static final int MTLS_PORT_B_ROTATED = 44460;

    /**
     * Cold-path fixture generator for {@code ledgerA_manifest}/{@code ledgerB_manifest}. It brings the
     * two networks up so the {@code @MultiNetworkHapiTest} extension's cold bootstrap harvests their
     * per-node TSS/WRAPS preload assets into {@code tss-startup-assets/}, then asserts the per-node
     * fixtures carry distinct keys. Its body exercises no rotation/propagation logic — just liveness.
     *
     * <p><b>Its {@code @Network} config must match {@link #partialRotationPropagatesToPeer} exactly</b>
     * (size, {@code enableClprMtls}, setupOverrides). The extension shares a {@link SubProcessNetwork}
     * by <em>name</em> (see {@code MultiNetworkExtension.allShared}), so if this generator declared a
     * different (e.g. plaintext) config for {@code ledgerA_manifest}, the rotation test would reuse
     * this generator's mis-configured network — with no mTLS CA provisioned — and fail. Keeping the
     * configs identical means whichever runs first configures the shared network correctly for both.
     *
     * <p>Run it once to (re)generate the committed assets; thereafter it runs warm (preload hit).
     * Deleting the committed {@code .gz} files and rerunning it regenerates them.
     */
    @MultiNetworkHapiTest({
        @Network(
                name = LEDGER_A_MANIFEST,
                size = 2,
                enableClprMtls = true,
                firstMtlsPort = MTLS_PORT_A,
                setupOverrides = {@ConfigOverride(key = "clpr.endpointManifestEnabled", value = "true")}),
        @Network(
                name = LEDGER_B_MANIFEST,
                enableClprMtls = true,
                firstMtlsPort = MTLS_PORT_B,
                setupOverrides = {@ConfigOverride(key = "clpr.endpointManifestEnabled", value = "true")})
    })
    @DisplayName("Fixture generator: brings up ledgerA_manifest (size 2) / ledgerB_manifest (mTLS) so their "
            + "per-node TSS/WRAPS assets are harvested and asserted to carry distinct keys")
    @Disabled("Fixture generator")
    Stream<DynamicTest> generateManifestLedgerFixtures(
            final SubProcessNetwork ledgerA, final SubProcessNetwork ledgerB) {
        return Stream.of(
                networkHapiTest(
                                "ledgerA_manifest liveness",
                                ledgerA,
                                cryptoCreate("livenessA").balance(ONE_HUNDRED_HBARS))
                        .findFirst()
                        .orElseThrow(),
                networkHapiTest(
                                "ledgerB_manifest liveness",
                                ledgerB,
                                cryptoCreate("livenessB").balance(ONE_HUNDRED_HBARS))
                        .findFirst()
                        .orElseThrow(),
                // Prove the per-node harvest captured each node's OWN TSS key: the multi-node ledger's
                // per-node fixtures must each carry exactly one, distinct, blsPrivateKey. (A shared node0
                // fixture would duplicate one key and silently break node1's warm start.)
                networkHapiTest(
                                "Assert ledgerA_manifest per-node TSS fixtures carry distinct keys",
                                ledgerA,
                                withOpContext((spec, opLog) ->
                                        MultiNetworkExtension.assertPerNodeFixturesHaveDistinctKeys(ledgerA.name(), 2)))
                        .findFirst()
                        .orElseThrow());
    }

    @MultiNetworkHapiTest({
        @Network(
                name = LEDGER_A_MANIFEST,
                size = 2,
                enableClprMtls = true,
                firstMtlsPort = MTLS_PORT_A,
                setupOverrides = {@ConfigOverride(key = "clpr.endpointManifestEnabled", value = "true")}),
        @Network(
                name = LEDGER_B_MANIFEST,
                enableClprMtls = true,
                firstMtlsPort = MTLS_PORT_B,
                setupOverrides = {@ConfigOverride(key = "clpr.endpointManifestEnabled", value = "true")})
    })
    @DisplayName("Manifest-enabled round-trip (mTLS): both ledgers self-derive manifests, complete an "
            + "mTLS channel, and exchange messages both ways")
    Stream<DynamicTest> manifestEnabledRoundTrip(final SubProcessNetwork ledgerA, final SubProcessNetwork ledgerB) {
        final var crypto = new ClprCrypto();
        // Reuse the shared size-2 mTLS ledgerA_manifest / ledgerB_manifest fixtures (the same @Network
        // definition the rotation tests use), so the endpoint-manifest feature is enabled at boot on
        // networks that no non-manifest suite shares — the boot-time flag cannot leak across suites.
        final byte[] caDerA = MultiNetworkExtension.clprMtlsCaDer(ledgerA.name());
        final byte[] caDerB = MultiNetworkExtension.clprMtlsCaDer(ledgerB.name());

        return Stream.concat(
                setupBothNetworksWithManifestProof(ledgerA, ledgerB, MTLS_PORT_A, MTLS_PORT_B, crypto, caDerA, caDerB),
                Stream.of(
                        // A → B: send 2 messages, then verify both delivery and ack complete.
                        networkHapiTest(
                                        "Send 2 messages from A",
                                        ledgerA,
                                        cryptoCreate("callerA").balance(ONE_HUNDRED_HBARS),
                                        uploadInitCode(CLPR_CONTRACT),
                                        contractCreate(CLPR_CONTRACT),
                                        contractCall(
                                                        CLPR_CONTRACT,
                                                        SEND_MESSAGE,
                                                        crypto.channelId,
                                                        crypto.connectorId,
                                                        new byte[20],
                                                        "hello-manifest-1".getBytes(StandardCharsets.UTF_8))
                                                .gas(GAS)
                                                .payingWith("callerA"),
                                        contractCall(
                                                        CLPR_CONTRACT,
                                                        SEND_MESSAGE,
                                                        crypto.channelId,
                                                        crypto.connectorId,
                                                        new byte[20],
                                                        "hello-manifest-2".getBytes(StandardCharsets.UTF_8))
                                                .gas(GAS)
                                                .payingWith("callerA"))
                                .findFirst()
                                .orElseThrow(),
                        awaitReceivedMessage(ledgerB, crypto.channelId, 2),
                        awaitAckedMessage(ledgerA, crypto.channelId, 2),
                        // B → A round-trip: send 2 messages back the other way.
                        networkHapiTest(
                                        "Send 2 messages from B (round-trip)",
                                        ledgerB,
                                        cryptoCreate("callerB").balance(ONE_HUNDRED_HBARS),
                                        uploadInitCode(CLPR_CONTRACT),
                                        contractCreate(CLPR_CONTRACT),
                                        contractCall(
                                                        CLPR_CONTRACT,
                                                        SEND_MESSAGE,
                                                        crypto.channelId,
                                                        crypto.connectorId,
                                                        new byte[20],
                                                        "reply-manifest-1".getBytes(StandardCharsets.UTF_8))
                                                .gas(GAS)
                                                .payingWith("callerB"),
                                        contractCall(
                                                        CLPR_CONTRACT,
                                                        SEND_MESSAGE,
                                                        crypto.channelId,
                                                        crypto.connectorId,
                                                        new byte[20],
                                                        "reply-manifest-2".getBytes(StandardCharsets.UTF_8))
                                                .gas(GAS)
                                                .payingWith("callerB"))
                                .findFirst()
                                .orElseThrow(),
                        awaitReceivedMessage(ledgerA, crypto.channelId, 2),
                        awaitAckedMessage(ledgerB, crypto.channelId, 2)));
    }

    @MultiNetworkHapiTest({
        @Network(
                name = LEDGER_A_MANIFEST,
                size = 2,
                enableClprMtls = true,
                firstMtlsPort = MTLS_PORT_A,
                setupOverrides = {@ConfigOverride(key = "clpr.endpointManifestEnabled", value = "true")}),
        @Network(
                name = LEDGER_B_MANIFEST,
                enableClprMtls = true,
                firstMtlsPort = MTLS_PORT_B,
                setupOverrides = {@ConfigOverride(key = "clpr.endpointManifestEnabled", value = "true")})
    })
    @DisplayName("8.1.1 partial rotation (mTLS, 3-node): A's node0 rotates its clpr.mtlsPort; the advanced "
            + "manifest propagates to B via a bundle state proof, B dials A's new listener, and no messages are lost")
    Stream<DynamicTest> partialRotationPropagatesToPeer(
            final SubProcessNetwork ledgerA, final SubProcessNetwork ledgerB) {
        final var crypto = new ClprCrypto();
        // Each network's real ECDSA CLPR CA cert (advertised on-chain in tls_certificate). The endpoint
        // ports are each network's clpr.mtlsPort, so the channel completes over — and syncs across —
        // the dedicated mutual-TLS listener rather than the plaintext path.
        final byte[] caDerA = MultiNetworkExtension.clprMtlsCaDer(ledgerA.name());
        final byte[] caDerB = MultiNetworkExtension.clprMtlsCaDer(ledgerB.name());
        // A's finalized manifest version captured just before the rotation; the propagation
        // assertions are all expressed relative to it (versionBeforeRotation -> +1).
        final AtomicLong versionBeforeRotation = new AtomicLong();

        return Stream.concat(
                setupBothNetworksWithManifestProof(ledgerA, ledgerB, MTLS_PORT_A, MTLS_PORT_B, crypto, caDerA, caDerB),
                Stream.of(
                        // Baseline: A's cold-start-derived manifest (version >= 2, three endpoints).
                        captureManifestVersion(ledgerA, versionBeforeRotation),
                        // Rotate ONLY node 0 of A: kill it, change its node-local clpr.mtlsPort, restart just
                        // that node (ReassignPorts.NO, no freeze, no roster/TSS change). The other 3 nodes stay
                        // up and keep their in-memory #335 peerObservedManifestVersions, so the ledger still
                        // detects B's stale view and drives the manifest proof; B stays reachable via them
                        // while node 0 returns on its new, genuinely reachable mTLS port. Node 0's re-publish
                        // opens a construction; nodes 1-3 publish their own endpoints into it (all-hands
                        // snapshot, no carry-over) and it fast-closes, advancing the manifest by exactly one.
                        rotateNodeMtlsPort(ledgerA, 0L, MTLS_PORT_A_ROTATED),
                        // Node 0 actually bound its mTLS sync listener to the rotated port (proves the endpoint
                        // change is real and reachable, not merely an advertised value). awaitLogLine scans
                        // node 0's hgcaa.log — the node that rotated.
                        networkHapiTest(
                                        "Assert A node0 restarted its mTLS listener on " + MTLS_PORT_A_ROTATED,
                                        ledgerA,
                                        withOpContext((spec, opLog) -> awaitLogLine(
                                                ledgerA,
                                                Pattern.compile("Starting CLPR mTLS sync gRPC server on port "
                                                        + MTLS_PORT_A_ROTATED),
                                                Duration.ofSeconds(60))))
                                .findFirst()
                                .orElseThrow(),
                        // A's manifest advances by exactly one (only the self-publish fires; the IP-set is
                        // unchanged so the prune does not).
                        awaitManifestVersionExactly(ledgerA, versionBeforeRotation),
                        // Drive an outbound sync A -> B; A (now current) embeds a proof of its manifest
                        // because B's reported view of A's manifest is behind (#335 staleness gate).
                        networkHapiTest(
                                        "Send a message from A after rotation",
                                        ledgerA,
                                        cryptoCreate("callerA2").balance(ONE_HUNDRED_HBARS),
                                        uploadInitCode(CLPR_CONTRACT),
                                        contractCreate(CLPR_CONTRACT),
                                        contractCall(
                                                        CLPR_CONTRACT,
                                                        SEND_MESSAGE,
                                                        crypto.channelId,
                                                        crypto.connectorId,
                                                        new byte[20],
                                                        "post-rotation".getBytes(StandardCharsets.UTF_8))
                                                .gas(GAS)
                                                .payingWith("callerA2"))
                                .findFirst()
                                .orElseThrow(),
                        // B applies the advanced manifest via Step 1b (verifyBundle -> new_endpoint_manifest).
                        awaitManifestAppliedOnPeer(ledgerB, versionBeforeRotation),
                        // Prove B didn't just bump a version number: the manifest it applied actually carries
                        // A's node0 ROTATED endpoint (:43460), so B's dial-target set now points at the new
                        // port. (The apply log lists the endpoints; assert the new port is present.)
                        networkHapiTest(
                                        "Assert B's applied manifest carries A node0's new port " + MTLS_PORT_A_ROTATED,
                                        ledgerB,
                                        withOpContext((spec, opLog) -> {
                                            final long from = versionBeforeRotation.get();
                                            awaitLogLine(
                                                    ledgerB,
                                                    Pattern.compile("applying new endpoint manifest.*version=" + from
                                                            + "->" + (from + 1) + ".*:" + MTLS_PORT_A_ROTATED),
                                                    MANIFEST_APPEAR_TIMEOUT);
                                            opLog.info(
                                                    "Peer B's applied manifest (v{}) includes A node0's rotated "
                                                            + "endpoint :{} — B will dial the new port",
                                                    from + 1,
                                                    MTLS_PORT_A_ROTATED);
                                        }))
                                .findFirst()
                                .orElseThrow(),
                        // A -> B on the new manifest: delivered to B and acked back to A.
                        awaitReceivedMessage(ledgerB, crypto.channelId, 1),
                        awaitAckedMessage(ledgerA, crypto.channelId, 1),
                        // B -> A on the new manifest: B dials A's rotated mTLS listener (the whole point of
                        // rotating a real, reachable port). Delivered to A and acked back to B.
                        networkHapiTest(
                                        "Send a message from B after rotation",
                                        ledgerB,
                                        cryptoCreate("callerB2").balance(ONE_HUNDRED_HBARS),
                                        uploadInitCode(CLPR_CONTRACT),
                                        contractCreate(CLPR_CONTRACT),
                                        contractCall(
                                                        CLPR_CONTRACT,
                                                        SEND_MESSAGE,
                                                        crypto.channelId,
                                                        crypto.connectorId,
                                                        new byte[20],
                                                        "post-rotation-reply".getBytes(StandardCharsets.UTF_8))
                                                .gas(GAS)
                                                .payingWith("callerB2"))
                                .findFirst()
                                .orElseThrow(),
                        awaitReceivedMessage(ledgerA, crypto.channelId, 1),
                        awaitAckedMessage(ledgerB, crypto.channelId, 1)));
    }

    @MultiNetworkHapiTest({
        @Network(
                name = LEDGER_A_MANIFEST,
                size = 2,
                enableClprMtls = true,
                firstMtlsPort = MTLS_PORT_A,
                setupOverrides = {@ConfigOverride(key = "clpr.endpointManifestEnabled", value = "true")}),
        @Network(
                name = LEDGER_B_MANIFEST,
                enableClprMtls = true,
                firstMtlsPort = MTLS_PORT_B,
                setupOverrides = {@ConfigOverride(key = "clpr.endpointManifestEnabled", value = "true")})
    })
    @DisplayName("8.1.2 complete turnover (one side): A rotates all its mTLS ports; B recovers A's advanced "
            + "manifest out-of-band via clprGetEndpointManifest + clprSubmitBundle, with no gRPC to A's old endpoints")
    Stream<DynamicTest> completeTurnoverOneSideRecoversViaSubmitBundle(
            final SubProcessNetwork ledgerA, final SubProcessNetwork ledgerB) {
        final var crypto = new ClprCrypto();
        final byte[] caDerA = MultiNetworkExtension.clprMtlsCaDer(ledgerA.name());
        final byte[] caDerB = MultiNetworkExtension.clprMtlsCaDer(ledgerB.name());
        // A's manifest version B cached at connection time; the recovery is expressed relative to it.
        final AtomicLong aVersionBaseline = new AtomicLong();
        final AtomicReference<ByteString> aManifestProof = new AtomicReference<>();

        return Stream.concat(
                setupBothNetworksWithManifestProof(ledgerA, ledgerB, MTLS_PORT_A, MTLS_PORT_B, crypto, caDerA, caDerB),
                Stream.of(
                        // Baseline: A's manifest version B cached at connect (captured dynamically, so the
                        // scenario is order-independent even on the shared, possibly already-rotated network).
                        captureManifestVersion(ledgerA, aVersionBaseline),
                        // Complete turnover of A: replace ALL of A's endpoints at once (single simultaneous
                        // restart). A's manifest advances by exactly one and B's entire cached A-endpoint set
                        // becomes unreachable simultaneously.
                        rotateAllNodesMtlsPorts(ledgerA, MTLS_PORT_A_ROTATED + 10),
                        awaitManifestVersionAtLeast(ledgerA, aVersionBaseline),
                        // Out-of-band recovery: read-only query of A's advanced manifest + real state proof...
                        captureManifestProof(ledgerA, aManifestProof),
                        // ...submitted on B via clprSubmitBundle. On a one-sided turnover the surviving A->B
                        // link lets A auto-push its manifest first, so accept SUCCESS (this submit applied it)
                        // or CLPR_BUNDLE_VERIFICATION_FAILED (already applied). No gRPC to any A endpoint is
                        // used by this submit (spec §8.1.4).
                        submitManifestRecoveryBundle(
                                ledgerB,
                                crypto.channelId,
                                aManifestProof,
                                ResponseCodeEnum.SUCCESS,
                                ResponseCodeEnum.CLPR_BUNDLE_VERIFICATION_FAILED),
                        // Deterministic outcome: B's cached peer manifest advances to A's new version.
                        awaitManifestAppliedOnPeer(ledgerB, aVersionBaseline)));
    }

    @MultiNetworkHapiTest({
        @Network(
                name = LEDGER_A_MANIFEST,
                size = 2,
                enableClprMtls = true,
                firstMtlsPort = MTLS_PORT_A,
                setupOverrides = {@ConfigOverride(key = "clpr.endpointManifestEnabled", value = "true")}),
        @Network(
                name = LEDGER_B_MANIFEST,
                enableClprMtls = true,
                firstMtlsPort = MTLS_PORT_B,
                setupOverrides = {@ConfigOverride(key = "clpr.endpointManifestEnabled", value = "true")})
    })
    @DisplayName("8.1.3 simultaneous turnover (both sides): A and B both rotate mTLS ports; each side "
            + "independently recovers the other's advanced manifest via clprGetEndpointManifest + clprSubmitBundle")
    Stream<DynamicTest> simultaneousTurnoverBothSidesRecoverIndependently(
            final SubProcessNetwork ledgerA, final SubProcessNetwork ledgerB) {
        final var crypto = new ClprCrypto();
        final byte[] caDerA = MultiNetworkExtension.clprMtlsCaDer(ledgerA.name());
        final byte[] caDerB = MultiNetworkExtension.clprMtlsCaDer(ledgerB.name());
        final AtomicLong aVersionBaseline = new AtomicLong();
        final AtomicLong bVersionBaseline = new AtomicLong();
        final AtomicReference<ByteString> aManifestProof = new AtomicReference<>();
        final AtomicReference<ByteString> bManifestProof = new AtomicReference<>();

        return Stream.concat(
                setupBothNetworksWithManifestProof(ledgerA, ledgerB, MTLS_PORT_A, MTLS_PORT_B, crypto, caDerA, caDerB),
                Stream.of(
                        captureManifestVersion(ledgerA, aVersionBaseline),
                        captureManifestVersion(ledgerB, bVersionBaseline),
                        // Both sides replace ALL their endpoints at once — every cached peer endpoint on both
                        // ledgers becomes unreachable simultaneously, so no automatic push can propagate
                        // either manifest. Each manifest advances by exactly one. The two turnovers run in
                        // parallel (one ParallelSpecOps worker per ledger) so both endpoint sets go down in the
                        // same window rather than A fully recovering before B starts.
                        rotateBothNetworksMtlsPortsInParallel(
                                ledgerA, MTLS_PORT_A_ROTATED + 20, ledgerB, MTLS_PORT_B_ROTATED),
                        awaitManifestVersionAtLeast(ledgerA, aVersionBaseline),
                        awaitManifestVersionAtLeast(ledgerB, bVersionBaseline),
                        // Each side independently recovers the other's new manifest out-of-band (order does
                        // not matter). With all connectivity broken the manual submitBundle is the ONLY path
                        // that can apply the manifest, so each awaitManifestAppliedOnPeer is a deterministic
                        // proof that the manifest-only recovery bundle was accepted and applied. B recovers A...
                        captureManifestProof(ledgerA, aManifestProof),
                        submitManifestRecoveryBundle(
                                ledgerB,
                                crypto.channelId,
                                aManifestProof,
                                ResponseCodeEnum.SUCCESS,
                                ResponseCodeEnum.CLPR_BUNDLE_VERIFICATION_FAILED),
                        awaitManifestAppliedOnPeer(ledgerB, aVersionBaseline),
                        // ...and A recovers B.
                        captureManifestProof(ledgerB, bManifestProof),
                        submitManifestRecoveryBundle(
                                ledgerA,
                                crypto.channelId,
                                bManifestProof,
                                ResponseCodeEnum.SUCCESS,
                                ResponseCodeEnum.CLPR_BUNDLE_VERIFICATION_FAILED),
                        awaitManifestAppliedOnPeer(ledgerA, bVersionBaseline)));
    }
}
