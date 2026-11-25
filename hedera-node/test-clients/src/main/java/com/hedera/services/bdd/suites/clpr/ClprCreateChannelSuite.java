// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.clpr;

import static com.hedera.node.app.service.clpr.impl.schemas.V0780ClprSchema.CHANNELS_STATE_ID;
import static com.hedera.services.bdd.junit.ContextRequirement.PROPERTY_OVERRIDES;
import static com.hedera.services.bdd.junit.EmbeddedReason.NEEDS_STATE_ACCESS;
import static com.hedera.services.bdd.junit.TestTags.CLPR;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.clprCreateChannel;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.clprCreateConnector;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.clprDeleteConnector;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.blockingOrder;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.contract.Utils.asSolidityAddress;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CLPR_CHANNEL_ALREADY_EXISTS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CLPR_CONNECTOR_ALREADY_EXISTS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CLPR_INVALID_VERIFIER_CONTRACT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CLPR_NOT_ENABLED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.google.protobuf.ByteString;
import com.hedera.hapi.node.state.clpr.ClprChannel;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.node.app.service.clpr.ClprService;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.LeakyEmbeddedHapiTest;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.transactions.clpr.HapiClprCreateConnector;
import com.hederahashgraph.api.proto.java.ClprLedgerConfiguration;
import com.hederahashgraph.api.proto.java.ClprThrottles;
import com.hederahashgraph.api.proto.java.ContractID;
import com.hederahashgraph.api.proto.java.Timestamp;
import com.swirlds.state.spi.ReadableKVState;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * HAPI tests for CLPR one-step channel opening and connector registration.
 *
 * <p>Tests that require multi-transaction sequences, real contract deployment,
 * or cross-handler state interaction belong here. Single-handler validation
 * branch coverage belongs in the unit tests.
 */
@Tag(CLPR)
public class ClprCreateChannelSuite {

    private static final String VERIFIER = "ClprPassThroughVerifier";
    private static final String CONNECTOR_CONTRACT = "PassThroughAuth";
    private static final long LOCKED_STAKE = 100_000_000L;

    // ── Happy-path tests ─────────────────────────────────────────────────────

    @LeakyHapiTest(requirement = PROPERTY_OVERRIDES)
    @DisplayName("Opening a channel creates an ACTIVE channel")
    final Stream<DynamicTest> openCreatesActiveChannel() {
        return hapiTest(ClprChannelCrypto.withVerifier(VERIFIER, "hiero:testing", new byte[] {0, 0, 1}, crypto -> {
            return blockingOrder(
                    cryptoCreate("registrant").balance(ONE_HUNDRED_HBARS),
                    clprCreateChannel()
                            .channelId(crypto.channelId())
                            .salt(crypto.salt())
                            .peerVerifier(crypto.peerVerifier())
                            .trustAnchor(crypto.peerTrustAnchor())
                            .localConfigTimestamp(crypto.localConfigTimestamp())
                            .localTrustAnchorHash(crypto.localTrustAnchorHash())
                            .verifierContract(VERIFIER)
                            .configProofBytes(defaultConfigProofBytes())
                            .payingWith("registrant")
                            .hasKnownStatus(SUCCESS));
        }));
    }

    @LeakyHapiTest(requirement = PROPERTY_OVERRIDES)
    @DisplayName("A connector ID cannot be used twice on a channel, including after deletion")
    final Stream<DynamicTest> connectorCannotBeRecreatedOnSameChannel() {
        return hapiTest(ClprChannelCrypto.withVerifier(VERIFIER, "hiero:testing", new byte[] {0, 0, 1}, crypto -> {
            return blockingOrder(
                    overriding("clpr.enabled", "true"),
                    uploadInitCode(CONNECTOR_CONTRACT),
                    contractCreate(CONNECTOR_CONTRACT),
                    clprCreateChannel()
                            .channelId(crypto.channelId())
                            .salt(crypto.salt())
                            .peerVerifier(crypto.peerVerifier())
                            .trustAnchor(crypto.peerTrustAnchor())
                            .localConfigTimestamp(crypto.localConfigTimestamp())
                            .localTrustAnchorHash(crypto.localTrustAnchorHash())
                            .verifierContract(VERIFIER)
                            .configProofBytes(defaultConfigProofBytes())
                            .payingWith(GENESIS)
                            .hasKnownStatus(SUCCESS),
                    createConnector(crypto)
                            .payingWith(GENESIS)
                            .signedBy(GENESIS)
                            .hasKnownStatus(INVALID_SIGNATURE),
                    createConnector(crypto).payingWith(GENESIS).hasKnownStatus(SUCCESS),
                    // Connector exists
                    createConnector(crypto).payingWith(GENESIS).hasKnownStatus(CLPR_CONNECTOR_ALREADY_EXISTS),
                    clprDeleteConnector()
                            .channelId(crypto.channelId())
                            .connectorId(crypto.connectorId())
                            .adminKey(GENESIS)
                            .stakeRecipient(GENESIS)
                            .payingWith(GENESIS)
                            .hasKnownStatus(SUCCESS),
                    // Deleting the connector preserves its registration history
                    createConnector(crypto).payingWith(GENESIS).hasKnownStatus(CLPR_CONNECTOR_ALREADY_EXISTS));
        }));
    }

    @LeakyHapiTest(requirement = PROPERTY_OVERRIDES)
    @DisplayName("Opening an existing channel returns CLPR_CHANNEL_ALREADY_EXISTS")
    final Stream<DynamicTest> duplicateOpenFails() {
        return hapiTest(ClprChannelCrypto.withVerifier(VERIFIER, "hiero:testing", new byte[] {0, 0, 1}, crypto -> {
            return blockingOrder(
                    clprCreateChannel()
                            .channelId(crypto.channelId())
                            .salt(crypto.salt())
                            .peerVerifier(crypto.peerVerifier())
                            .trustAnchor(crypto.peerTrustAnchor())
                            .localConfigTimestamp(crypto.localConfigTimestamp())
                            .localTrustAnchorHash(crypto.localTrustAnchorHash())
                            .verifierContract(VERIFIER)
                            .configProofBytes(defaultConfigProofBytes())
                            .payingWith(GENESIS)
                            .hasKnownStatus(SUCCESS),
                    clprCreateChannel()
                            .channelId(crypto.channelId())
                            .salt(crypto.salt())
                            .peerVerifier(crypto.peerVerifier())
                            .trustAnchor(crypto.peerTrustAnchor())
                            .localConfigTimestamp(crypto.localConfigTimestamp())
                            .localTrustAnchorHash(crypto.localTrustAnchorHash())
                            .verifierContract(VERIFIER)
                            .configProofBytes(defaultConfigProofBytes())
                            .payingWith(GENESIS)
                            .hasKnownStatus(CLPR_CHANNEL_ALREADY_EXISTS));
        }));
    }

    @LeakyHapiTest(requirement = PROPERTY_OVERRIDES)
    @DisplayName("Opening with an unknown verifier returns CLPR_INVALID_VERIFIER_CONTRACT")
    final Stream<DynamicTest> nonExistentVerifierFails() {
        return hapiTest(ClprChannelCrypto.withVerifier(VERIFIER, "hiero:testing", new byte[] {0, 0, 1}, crypto -> {
            final var bogusContractId = ContractID.newBuilder()
                    .setShardNum(0)
                    .setRealmNum(0)
                    .setContractNum(99_999_999L)
                    .build();
            return blockingOrder(clprCreateChannel()
                    .channelId(crypto.channelId())
                    .salt(crypto.salt())
                    .peerVerifier(crypto.peerVerifier())
                    .trustAnchor(crypto.peerTrustAnchor())
                    .localConfigTimestamp(crypto.localConfigTimestamp())
                    .localTrustAnchorHash(crypto.localTrustAnchorHash())
                    .verifierContractId(bogusContractId)
                    .configProofBytes(defaultConfigProofBytes())
                    .payingWith(GENESIS)
                    .hasKnownStatus(CLPR_INVALID_VERIFIER_CONTRACT));
        }));
    }

    @HapiTest
    @DisplayName("Opening with the unsupported Sei verifier returns CLPR_INVALID_VERIFIER_CONTRACT")
    final Stream<DynamicTest> seiVerifierFails() {
        final var seiByNumber = ContractID.newBuilder().setContractNum(0x170L).build();
        final var seiAddress = asSolidityAddress(seiByNumber);
        final var seiByAddress = ContractID.newBuilder()
                .setEvmAddress(ByteString.copyFrom(seiAddress))
                .build();
        // The verifier is rejected before the channel ID is checked
        final var crypto = new ClprChannelCrypto();
        return hapiTest(Stream.of(seiByNumber, seiByAddress)
                .map(verifier -> clprCreateChannel()
                        .channelId(crypto.channelId())
                        .salt(crypto.salt())
                        .peerVerifier(crypto.peerVerifier())
                        .trustAnchor(crypto.peerTrustAnchor())
                        .localConfigTimestamp(crypto.localConfigTimestamp())
                        .localTrustAnchorHash(crypto.localTrustAnchorHash())
                        .verifierContractId(verifier)
                        .configProofBytes(defaultConfigProofBytes())
                        .payingWith(GENESIS)
                        .hasKnownStatus(CLPR_INVALID_VERIFIER_CONTRACT))
                .toArray(SpecOperation[]::new));
    }

    @LeakyHapiTest(
            requirement = PROPERTY_OVERRIDES,
            overrides = {"clpr.enabled"})
    @DisplayName("Open when CLPR is disabled fails with CLPR_NOT_ENABLED")
    final Stream<DynamicTest> openWhenDisabledFails() {
        return hapiTest(
                overriding("clpr.enabled", "false"),
                clprCreateChannel()
                        .channelId(new byte[32])
                        .salt(new byte[32])
                        .peerVerifier(new byte[20])
                        .trustAnchor(new byte[] {1})
                        .localConfigTimestamp(Timestamp.getDefaultInstance())
                        .localTrustAnchorHash(new byte[32])
                        .verifierContractId(
                                ContractID.newBuilder().setContractNum(366).build())
                        .configProofBytes(defaultConfigProofBytes())
                        .payingWith(GENESIS)
                        .hasPrecheck(CLPR_NOT_ENABLED));
    }

    // ── Embedded-state regression tests ─────────────────────────────────────

    @LeakyEmbeddedHapiTest(
            reason = NEEDS_STATE_ACCESS,
            overrides = {"clpr.enabled"})
    @DisplayName("verifyConfig: verifier-returned throttles and chainId are stored on channel")
    final Stream<DynamicTest> verifyConfigFieldsStoredOnChannel() {
        return hapiTest(ClprChannelCrypto.withVerifier(VERIFIER, "hiero:testing", new byte[] {0, 0, 1}, crypto -> {
            return blockingOrder(
                    clprCreateChannel()
                            .channelId(crypto.channelId())
                            .salt(crypto.salt())
                            .peerVerifier(crypto.peerVerifier())
                            .trustAnchor(crypto.peerTrustAnchor())
                            .localConfigTimestamp(crypto.localConfigTimestamp())
                            .localTrustAnchorHash(crypto.localTrustAnchorHash())
                            .verifierContract(VERIFIER)
                            .configProofBytes(defaultConfigProofBytes())
                            .payingWith(GENESIS)
                            .hasKnownStatus(SUCCESS),
                    withOpContext((spec, opLog) -> {
                        final var conn = readChannelFromState(spec, crypto);
                        assertEquals(
                                "hiero:testing",
                                conn.chainId(),
                                "chainId from verifyConfig should be stored on channel");
                        final var t = conn.peerThrottles();
                        assertNotNull(t, "peerThrottles should be stored from verifyConfig return");
                        assertEquals(
                                100,
                                t.maxMessagesPerBundle(),
                                "maxMessagesPerBundle from verifier should match proof config");
                        assertEquals(
                                65536,
                                t.maxMessagePayloadBytes(),
                                "maxMessagePayloadBytes from verifier should match proof config");
                        assertEquals(
                                1_000_000L,
                                t.maxGasPerMessage(),
                                "maxGasPerMessage from verifier should match proof config");
                        assertEquals(1000, t.maxQueueDepth(), "maxQueueDepth from verifier should match proof config");
                        assertEquals(
                                1_048_576L, t.maxSyncBytes(), "maxSyncBytes from verifier should match proof config");
                        // channelContext = abi.encodePacked(bytes32 channelId, bytes serviceAddress)
                        // serviceAddress = {0, 0, 1} (3 bytes); channelId = 32 bytes → total 35
                        assertEquals(
                                35L,
                                conn.channelContext().length(),
                                "channelContext should be 32 (channelId) + 3 (serviceAddress) bytes");
                    }));
        }));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Synthetic config proof bytes for the passthrough verifier path; spec refs §3.1
     * (verifyConfig contract) and §5.1.3 (createChannel consumes the verifier output).
     */
    private static HapiClprCreateConnector createConnector(final ClprChannelCrypto crypto) {
        return clprCreateConnector()
                .connectorId(crypto.connectorId())
                .ed25519Identity(crypto.seed())
                .salt(crypto.connectorSalt())
                .channelId(crypto.channelId())
                .connectorContract(CONNECTOR_CONTRACT)
                .adminKeyName(GENESIS)
                .lockedStake(LOCKED_STAKE);
    }

    private static byte[] defaultConfigProofBytes() {
        return ClprTestProofs.toConfigProofBytes(ClprLedgerConfiguration.newBuilder()
                .setChainId("hiero:testing")
                .setServiceAddress(ByteString.copyFrom(new byte[] {0, 0, 1}))
                .setThrottles(ClprThrottles.newBuilder()
                        .setMaxMessagesPerBundle(100)
                        .setMaxMessagePayloadBytes(65536)
                        .setMaxGasPerMessage(1_000_000L)
                        .setMaxQueueDepth(1000)
                        .setMaxSyncBytes(1_048_576L)
                        .build())
                .build());
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
}
