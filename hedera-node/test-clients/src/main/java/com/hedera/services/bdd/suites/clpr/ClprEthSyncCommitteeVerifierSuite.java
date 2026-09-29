// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.clpr;

import static com.hedera.node.app.service.clpr.impl.schemas.V0770ClprSchema.CHANNELS_STATE_ID;
import static com.hedera.services.bdd.junit.EmbeddedReason.NEEDS_STATE_ACCESS;
import static com.hedera.services.bdd.junit.TestTags.CLPR;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.clprCompleteChannel;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.clprCompleteConnector;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.clprRegisterChannel;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.clprRegisterConnector;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.clprSubmitBundle;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.clprUpdateLedgerConfiguration;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.flattened;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CLPR_BUNDLE_VERIFICATION_FAILED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CLPR_VERIFIER_CONFIG_FAILED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.ByteString;
import com.hedera.hapi.node.state.clpr.ClprChannel;
import com.hedera.hapi.node.state.clpr.ClprChannelStatus;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.node.app.service.clpr.ClprService;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.junit.LeakyEmbeddedHapiTest;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hederahashgraph.api.proto.java.ClprEndpoint;
import com.hederahashgraph.api.proto.java.ClprLedgerConfiguration;
import com.hederahashgraph.api.proto.java.ClprMessage;
import com.hederahashgraph.api.proto.java.ClprMessagePayload;
import com.hederahashgraph.api.proto.java.ClprServiceEndpoint;
import com.hederahashgraph.api.proto.java.ClprSignatureScheme;
import com.hederahashgraph.api.proto.java.ClprThrottles;
import com.hederahashgraph.api.proto.java.ContractID;
import com.swirlds.state.spi.ReadableKVState;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * End-to-end HAPI coverage for the built-in Ethereum sync-committee verifier system contract
 * (EVM address {@code 0x171}): from channel completion via a raw-RLP config payload through a
 * self-submitted bundle that the verifier proves with SSZ + Merkle-Patricia proofs.
 *
 * <p>The payloads are produced by {@link EthSyncCommitteeProofs}, which re-derives the wire bytes
 * independently of the production encoders, so these tests exercise the node's deserialization on
 * the receiving CLPR endpoint as a black box.
 */
@Tag(CLPR)
public class ClprEthSyncCommitteeVerifierSuite {

    /** Built-in Ethereum sync-committee verifier system contract (contract num 0x171). */
    private static final ContractID ETH_VERIFIER =
            ContractID.newBuilder().setContractNum(0x171L).build();

    /** Connector application contract — deployed only so the connector commit-reveal has a target. */
    private static final String CONNECTOR_CONTRACT = "PassThroughAuth";

    private static final byte[] TARGET_APPLICATION = {40, 50};
    private static final byte[] SENDER = {60, 70};
    private static final byte[] MESSAGE_DATA = {1, 2, 3};
    private static final byte[] MESSAGE_DATA_2 = {4, 5, 6};

    @LeakyEmbeddedHapiTest(
            reason = NEEDS_STATE_ACCESS,
            overrides = {"clpr.verifierGasLimit"})
    @DisplayName("Eth verifier: self-submitted single-message bundle")
    final Stream<DynamicTest> completesChannelAndDeliversBundle() {
        final var crypto = new ClprChannelCrypto();
        final var payload = firstMessage(crypto);
        final var content = EthSyncCommitteeProofs.singleMessageBundleContent(payload);

        return hapiTest(flattened(
                setupChannelWithEthereumVerifier(crypto, EthSyncCommitteeProofs.configPayload()),
                clprSubmitBundle()
                        .channelId(crypto.channelId())
                        .bundlePayload(EthSyncCommitteeProofs.bundlePayload(content))
                        .endpointNodeId(0L)
                        .payingWith(GENESIS),
                // The proven bundle delivered message #1, so receivedMessageId advances 0 -> 1.
                withOpContext((spec, opLog) -> {
                    final var conn = readChannelFromState(spec, crypto);
                    assertEquals(1L, conn.receivedMessageId(), "receivedMessageId should advance to 1");
                })));
    }

    @LeakyEmbeddedHapiTest(
            reason = NEEDS_STATE_ACCESS,
            overrides = {"clpr.verifierGasLimit"})
    @DisplayName("Eth verifier: peer serviceAddress not 20 bytes → CLPR_VERIFIER_CONFIG_FAILED")
    final Stream<DynamicTest> rejectsConfigWithBadServiceAddress() {
        final var crypto = new ClprChannelCrypto();
        return hapiTest(
                // A full 512-key committee makes the verifier calldata ~25KB+, so the dispatch needs
                // more than the 300k default verifier gas.
                overriding("clpr.verifierGasLimit", "5000000"),
                clprUpdateLedgerConfiguration()
                        .configuration(localLedgerConfig())
                        .payingWith(GENESIS),
                clprRegisterChannel().ownershipCommitment(crypto.commitment()).payingWith(GENESIS),
                clprCompleteChannel()
                        .channelId(crypto.channelId())
                        .publicKey(crypto.publicKey())
                        .signature(crypto.signature())
                        .signatureScheme(ClprSignatureScheme.ED25519)
                        .verifierContractId(ETH_VERIFIER)
                        .configProofBytes(EthSyncCommitteeProofs.configPayloadBadServiceAddress())
                        .payingWith(GENESIS)
                        .hasKnownStatus(CLPR_VERIFIER_CONFIG_FAILED));
    }

    @LeakyEmbeddedHapiTest(
            reason = NEEDS_STATE_ACCESS,
            overrides = {"clpr.verifierGasLimit"})
    @DisplayName("Eth verifier: tampered bundle account proof → CLPR_BUNDLE_VERIFICATION_FAILED")
    final Stream<DynamicTest> rejectsTamperedBundle() {
        final var crypto = new ClprChannelCrypto();
        final var content = EthSyncCommitteeProofs.singleMessageBundleContent(firstMessage(crypto));

        return hapiTest(flattened(
                setupChannelWithEthereumVerifier(crypto, EthSyncCommitteeProofs.configPayload()),
                clprSubmitBundle()
                        .channelId(crypto.channelId())
                        .bundlePayload(EthSyncCommitteeProofs.tamperedBundlePayload(content))
                        .endpointNodeId(0L)
                        .payingWith(GENESIS)
                        .hasKnownStatus(CLPR_BUNDLE_VERIFICATION_FAILED)));
    }

    /**
     * Verifies the full committee-rotation lifecycle end-to-end:
     * <ol>
     *   <li>A rotation bundle (signed by the initial committee) carries a proven {@code nextCommittee};
     *       the verifier updates the channel's trust anchor to the new committee and delivers message #1.</li>
     *   <li>A follow-up bundle signed by the new committee is accepted against the updated trust anchor
     *       and delivers message #2.</li>
     * </ol>
     *
     */
    @LeakyEmbeddedHapiTest(
            reason = NEEDS_STATE_ACCESS,
            overrides = {"clpr.verifierGasLimit"})
    @DisplayName("Eth verifier: rotation bundle updates trust anchor; new-committee bundle succeeds")
    final Stream<DynamicTest> completesChannelAfterCommitteeRotation() {
        final var crypto = new ClprChannelCrypto();
        final var payload1 = firstMessage(crypto);
        final var payload2 = secondMessage(crypto);
        final var rotationContent = EthSyncCommitteeProofs.singleMessageBundleContent(payload1);
        final var postRotationContent = EthSyncCommitteeProofs.twoMessageBundleContent(payload1, payload2);

        return hapiTest(flattened(
                setupChannelWithEthereumVerifier(crypto, EthSyncCommitteeProofs.configPayload()),
                // Step 1: rotation bundle — delivers message #1 and proves nextCommittee.
                clprSubmitBundle()
                        .channelId(crypto.channelId())
                        .bundlePayload(EthSyncCommitteeProofs.rotationBundlePayload(rotationContent))
                        .endpointNodeId(0L)
                        .payingWith(GENESIS),
                withOpContext((spec, opLog) -> {
                    final var conn = readChannelFromState(spec, crypto);
                    assertEquals(1L, conn.receivedMessageId(), "receivedMessageId should advance to 1");
                    assertTrue(conn.trustAnchor().length() > 0, "trust anchor should be non-empty after rotation");
                }),
                // Step 2: post-rotation bundle — delivers message #2 using the updated trust anchor.
                clprSubmitBundle()
                        .channelId(crypto.channelId())
                        .bundlePayload(EthSyncCommitteeProofs.postRotationBundlePayload(postRotationContent))
                        .endpointNodeId(0L)
                        .payingWith(GENESIS),
                withOpContext((spec, opLog) -> {
                    final var conn = readChannelFromState(spec, crypto);
                    assertEquals(
                            2L,
                            conn.receivedMessageId(),
                            "receivedMessageId should advance to 2 after post-rotation bundle");
                })));
    }

    /**
     * completeChannel under {@code clpr.endpointManifestEnabled=true}, with the peer endpoint manifest supplied as
     * the config path's {@code endpoint_manifest_proof_bytes} (raw {@code ClprEndpointManifest} bytes, spec §4.8).
     * Exercises 0, 1, and N (3) manifest endpoints — each on a fresh channel — and asserts the resulting
     * Channel's {@code endpoint_manifest_version >= 1} and that its stored endpoints match the supplied manifest.
     */
    @LeakyEmbeddedHapiTest(
            reason = NEEDS_STATE_ACCESS,
            overrides = {"clpr.verifierGasLimit", "clpr.endpointManifestEnabled"})
    @DisplayName("Eth verifier: completeChannel installs the config endpoint manifest (0, 1, N endpoints)")
    final Stream<DynamicTest> completeChannelInstallsConfigManifest() {
        final int[] endpointCounts = {0, 1, 3};
        final List<SpecOperation> ops = new ArrayList<>();
        // A full 512-key committee makes the verifier calldata ~25KB+, so the dispatch needs more than
        // the 300k default verifier gas.
        ops.add(overriding("clpr.verifierGasLimit", "5000000"));
        ops.add(overriding("clpr.endpointManifestEnabled", "true"));
        ops.add(clprUpdateLedgerConfiguration()
                .configuration(localLedgerConfig())
                .payingWith(GENESIS));
        for (final int count : endpointCounts) {
            final var crypto = new ClprChannelCrypto();
            ops.add(clprRegisterChannel()
                    .ownershipCommitment(crypto.commitment())
                    .payingWith(GENESIS));
            ops.add(clprCompleteChannel()
                    .channelId(crypto.channelId())
                    .publicKey(crypto.publicKey())
                    .signature(crypto.signature())
                    .signatureScheme(ClprSignatureScheme.ED25519)
                    .verifierContractId(ETH_VERIFIER)
                    .configProofBytes(EthSyncCommitteeProofs.configPayload())
                    .endpointManifestProofBytes(EthSyncCommitteeProofs.manifestBytes(1L, count))
                    .payingWith(GENESIS));
            ops.add(withOpContext((spec, opLog) -> {
                final var conn = readChannelFromState(spec, crypto);
                assertEquals(ClprChannelStatus.ACTIVE, conn.status(), "channel should be ACTIVE");
                assertEquals(1L, conn.endpointManifestVersion(), "endpoint_manifest_version should be the supplied 1");
                assertTrue(conn.endpointManifestVersion() >= 1L, "endpoint_manifest_version should be >= 1");
                assertManifestEndpoints(conn, count);
            }));
        }
        return hapiTest(ops.toArray(SpecOperation[]::new));
    }

    /**
     * A bundle carrying a higher-version endpoint manifest (spec §4.9) advances the Channel's cached manifest via
     * Step-1b. The channel opens at version 1 (the config-synthesized manifest); the bundle proves a version-2
     * manifest at the commitment slot (18) and, when applied, updates {@code endpoint_manifest} / {@code
     * endpoint_manifest_version} while also delivering message #1.
     */
    @LeakyEmbeddedHapiTest(
            reason = NEEDS_STATE_ACCESS,
            overrides = {"clpr.verifierGasLimit", "clpr.endpointManifestEnabled"})
    @DisplayName("Eth verifier: bundle carrying a higher-version manifest updates the Channel via Step-1b")
    final Stream<DynamicTest> bundleAdvancesEndpointManifest() {
        final var crypto = new ClprChannelCrypto();
        final var content = EthSyncCommitteeProofs.singleMessageBundleContent(firstMessage(crypto));
        final byte[] advanceManifest = EthSyncCommitteeProofs.manifestBytes(2L, 2);

        return hapiTest(flattened(
                overriding("clpr.endpointManifestEnabled", "true"),
                // completeChannel with an empty endpoint_manifest_proof_bytes synthesizes a version-1 manifest
                // from the config endpoints, so the channel opens at manifest version 1.
                setupChannelWithEthereumVerifier(crypto, EthSyncCommitteeProofs.configPayload()),
                withOpContext((spec, opLog) -> {
                    final var conn = readChannelFromState(spec, crypto);
                    assertEquals(1L, conn.endpointManifestVersion(), "channel opens at synthesized manifest v1");
                }),
                clprSubmitBundle()
                        .channelId(crypto.channelId())
                        .bundlePayload(EthSyncCommitteeProofs.bundlePayloadWithManifest(content, advanceManifest))
                        .endpointNodeId(0L)
                        .payingWith(GENESIS),
                withOpContext((spec, opLog) -> {
                    final var conn = readChannelFromState(spec, crypto);
                    assertEquals(1L, conn.receivedMessageId(), "bundle delivered message #1");
                    assertEquals(2L, conn.endpointManifestVersion(), "manifest advanced to v2 via Step-1b");
                    assertManifestEndpoints(conn, 2);
                })));
    }

    /**
     * A manifest whose {@code service_address} does not match the config's service address violates spec §4.8, so the
     * Ethereum verifier rejects it inside {@code verifyConfig} and completeChannel reverts with
     * {@code CLPR_VERIFIER_CONFIG_FAILED}.
     */
    @LeakyEmbeddedHapiTest(
            reason = NEEDS_STATE_ACCESS,
            overrides = {"clpr.verifierGasLimit", "clpr.endpointManifestEnabled"})
    @DisplayName("Eth verifier: manifest service_address mismatch → CLPR_VERIFIER_CONFIG_FAILED")
    final Stream<DynamicTest> rejectsManifestWithMismatchedServiceAddress() {
        final var crypto = new ClprChannelCrypto();
        return hapiTest(
                overriding("clpr.verifierGasLimit", "5000000"),
                overriding("clpr.endpointManifestEnabled", "true"),
                clprUpdateLedgerConfiguration()
                        .configuration(localLedgerConfig())
                        .payingWith(GENESIS),
                clprRegisterChannel().ownershipCommitment(crypto.commitment()).payingWith(GENESIS),
                clprCompleteChannel()
                        .channelId(crypto.channelId())
                        .publicKey(crypto.publicKey())
                        .signature(crypto.signature())
                        .signatureScheme(ClprSignatureScheme.ED25519)
                        .verifierContractId(ETH_VERIFIER)
                        .configProofBytes(EthSyncCommitteeProofs.configPayload())
                        .endpointManifestProofBytes(EthSyncCommitteeProofs.manifestBytesBadServiceAddress())
                        .payingWith(GENESIS)
                        .hasKnownStatus(CLPR_VERIFIER_CONFIG_FAILED));
    }

    // ── Helpers ──

    /**
     * Asserts the Channel's cached endpoint manifest has exactly {@code expectedCount} endpoints matching the
     * deterministic IP/port produced by {@link EthSyncCommitteeProofs#manifestEndpointIp}/{@code manifestEndpointPort}.
     */
    private static void assertManifestEndpoints(final ClprChannel conn, final int expectedCount) {
        final var manifest = conn.endpointManifest();
        assertTrue(manifest != null, "endpoint manifest should be present");
        final var endpoints = manifest.endpoints();
        assertEquals(expectedCount, endpoints.size(), "manifest endpoint count");
        for (int i = 0; i < expectedCount; i++) {
            final var svc = endpoints.get(i).serviceEndpoint();
            assertEquals(EthSyncCommitteeProofs.manifestEndpointIp(i), svc.ipAddress(), "endpoint[" + i + "] ip");
            assertEquals(EthSyncCommitteeProofs.manifestEndpointPort(i), svc.port(), "endpoint[" + i + "] port");
        }
    }

    private static SpecOperation[] setupChannelWithEthereumVerifier(
            ClprChannelCrypto crypto, byte[] ledgerConfigPayload) {
        return new SpecOperation[] {
            // A full 512-key committee makes the verifier calldata ~25KB+, so the dispatch needs
            // more than the 300k default verifier gas.
            overriding("clpr.verifierGasLimit", "5000000"),
            clprUpdateLedgerConfiguration().configuration(localLedgerConfig()).payingWith(GENESIS),
            uploadInitCode(CONNECTOR_CONTRACT),
            contractCreate(CONNECTOR_CONTRACT),
            clprRegisterChannel().ownershipCommitment(crypto.commitment()).payingWith(GENESIS),
            clprCompleteChannel()
                    .channelId(crypto.channelId())
                    .publicKey(crypto.publicKey())
                    .signature(crypto.signature())
                    .signatureScheme(ClprSignatureScheme.ED25519)
                    .verifierContractId(ETH_VERIFIER)
                    .configProofBytes(ledgerConfigPayload)
                    .payingWith(GENESIS),
            // completeChannel seeds an ACTIVE channel with the verifier-derived trust anchor.
            withOpContext((spec, opLog) -> {
                final var conn = readChannelFromState(spec, crypto);
                assertEquals(ClprChannelStatus.ACTIVE, conn.status(), "channel should be ACTIVE");
                assertTrue(conn.trustAnchor().length() > 0, "trust anchor should be seeded");
                assertEquals(0L, conn.receivedMessageId(), "no inbound messages yet");
            }),
            clprRegisterConnector().commitment(crypto.connectorCommitment()).payingWith(GENESIS),
            clprCompleteConnector()
                    .connectorId(crypto.connectorId())
                    .publicKey(crypto.publicKey())
                    .signature(crypto.connectorSignature())
                    .signatureScheme(ClprSignatureScheme.ED25519)
                    .salt(crypto.connectorSalt())
                    .channelId(crypto.channelId())
                    .connectorContract(CONNECTOR_CONTRACT)
                    .adminKeyName(GENESIS)
                    .lockedStake(100_000_000L)
                    .payingWith(GENESIS)
        };
    }

    private static ClprMessagePayload firstMessage(final ClprChannelCrypto crypto) {
        return ClprMessagePayload.newBuilder()
                .setMessage(ClprMessage.newBuilder()
                        .setConnectorId(ByteString.copyFrom(crypto.connectorId()))
                        .setTargetApplication(ByteString.copyFrom(TARGET_APPLICATION))
                        .setSender(ByteString.copyFrom(SENDER))
                        .setMessageData(ByteString.copyFrom(MESSAGE_DATA))
                        .build())
                .build();
    }

    private static ClprMessagePayload secondMessage(final ClprChannelCrypto crypto) {
        return ClprMessagePayload.newBuilder()
                .setMessage(ClprMessage.newBuilder()
                        .setConnectorId(ByteString.copyFrom(crypto.connectorId()))
                        .setTargetApplication(ByteString.copyFrom(TARGET_APPLICATION))
                        .setSender(ByteString.copyFrom(SENDER))
                        .setMessageData(ByteString.copyFrom(MESSAGE_DATA_2))
                        .build())
                .build();
    }

    private static ClprChannel readChannelFromState(final HapiSpec spec, final ClprChannelCrypto crypto) {
        final ReadableKVState<ProtoBytes, ClprChannel> channels =
                spec.embeddedStateOrThrow().getReadableStates(ClprService.NAME).get(CHANNELS_STATE_ID);
        final var conn = channels.get(new ProtoBytes(Bytes.wrap(crypto.channelId())));
        if (conn == null) {
            throw new IllegalStateException("Channel not found in embedded state");
        }
        return conn;
    }

    /**
     * The local ledger's own configuration (unrelated to the peer config carried in the proof). The
     * Ethereum verifier reads the peer's service address from the config payload, so this local
     * service address need not be 20 bytes.
     */
    private static ClprLedgerConfiguration localLedgerConfig() {
        return ClprLedgerConfiguration.newBuilder()
                .setChainId("hiero:testing")
                .setServiceAddress(ByteString.copyFrom(new byte[] {0, 0, 1}))
                .setThrottles(ClprThrottles.newBuilder()
                        .setMaxMessagesPerBundle(100)
                        .setMaxMessagePayloadBytes(65536)
                        .setMaxGasPerMessage(1_000_000L)
                        .setMaxQueueDepth(1000)
                        .setMaxSyncBytes(1_048_576L)
                        .build())
                .addEndpoints(ClprEndpoint.newBuilder()
                        .setServiceEndpoint(ClprServiceEndpoint.newBuilder()
                                .setIpAddress("127.0.0.1")
                                .setPort(50211)
                                .build())
                        .setTlsCertificate(ByteString.copyFrom(new byte[] {0x01}))
                        .build())
                .build();
    }
}
