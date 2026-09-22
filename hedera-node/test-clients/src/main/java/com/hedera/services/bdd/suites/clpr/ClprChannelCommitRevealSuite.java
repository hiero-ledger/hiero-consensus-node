// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.clpr;

import static com.hedera.node.app.service.clpr.impl.schemas.V0770ClprSchema.CHANNELS_STATE_ID;
import static com.hedera.services.bdd.junit.ContextRequirement.PROPERTY_OVERRIDES;
import static com.hedera.services.bdd.junit.EmbeddedReason.NEEDS_STATE_ACCESS;
import static com.hedera.services.bdd.junit.TestTags.CLPR;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.clprCloseChannel;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.clprCompleteChannel;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.clprRegisterChannel;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CLPR_CHANNEL_ALREADY_EXISTS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CLPR_COMMITMENT_MISMATCH;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CLPR_INVALID_VERIFIER_CONTRACT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CLPR_NOT_ENABLED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.google.protobuf.ByteString;
import com.hedera.hapi.node.state.clpr.ClprChannel;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.node.app.service.clpr.ClprService;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.junit.LeakyEmbeddedHapiTest;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hederahashgraph.api.proto.java.ClprEndpoint;
import com.hederahashgraph.api.proto.java.ClprLedgerConfiguration;
import com.hederahashgraph.api.proto.java.ClprServiceEndpoint;
import com.hederahashgraph.api.proto.java.ClprSignatureScheme;
import com.hederahashgraph.api.proto.java.ClprThrottles;
import com.hederahashgraph.api.proto.java.ContractID;
import com.swirlds.state.spi.ReadableKVState;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * HAPI tests for CLPR channel commit-reveal happy path and failure modes.
 *
 * <p>Tests that require multi-transaction sequences, real contract deployment,
 * or cross-handler state interaction belong here. Single-handler validation
 * branch coverage belongs in the unit tests.
 */
@Tag(CLPR)
public class ClprChannelCommitRevealSuite {

    private static final String VERIFIER = "ClprPassThroughVerifier";

    // ── Happy-path tests ─────────────────────────────────────────────────────

    @LeakyHapiTest(requirement = PROPERTY_OVERRIDES)
    @DisplayName("Happy path: commit then reveal creates ACTIVE channel")
    final Stream<DynamicTest> commitThenRevealCreatesActiveChannel() {
        final var crypto = new ClprChannelCrypto();
        return hapiTest(
                overriding("clpr.enabled", "true"),
                uploadInitCode(VERIFIER),
                contractCreate(VERIFIER),
                cryptoCreate("registrant").balance(ONE_HUNDRED_HBARS),
                clprRegisterChannel()
                        .ownershipCommitment(crypto.commitment())
                        .payingWith("registrant")
                        .hasKnownStatus(SUCCESS),
                clprCompleteChannel()
                        .channelId(crypto.channelId())
                        .publicKey(crypto.publicKey())
                        .signature(crypto.signature())
                        .signatureScheme(ClprSignatureScheme.ED25519)
                        .verifierContract(VERIFIER)
                        .configProofBytes(defaultConfigProofBytes())
                        .payingWith("registrant")
                        .hasKnownStatus(SUCCESS));
    }

    @LeakyHapiTest(requirement = PROPERTY_OVERRIDES)
    @DisplayName("Commit is idempotent — re-submitting same commitment succeeds")
    final Stream<DynamicTest> commitIsIdempotent() {
        final var crypto = new ClprChannelCrypto();
        return hapiTest(
                overriding("clpr.enabled", "true"),
                clprRegisterChannel()
                        .ownershipCommitment(crypto.commitment())
                        .payingWith(GENESIS)
                        .hasKnownStatus(SUCCESS),
                clprRegisterChannel()
                        .ownershipCommitment(crypto.commitment())
                        .payingWith(GENESIS)
                        .hasKnownStatus(SUCCESS));
    }

    @LeakyHapiTest(requirement = PROPERTY_OVERRIDES)
    @DisplayName("Different payer can complete a commitment registered by someone else")
    final Stream<DynamicTest> differentPayerCanComplete() {
        final var crypto = new ClprChannelCrypto();
        return hapiTest(
                overriding("clpr.enabled", "true"),
                uploadInitCode(VERIFIER),
                contractCreate(VERIFIER),
                cryptoCreate("committer").balance(ONE_HUNDRED_HBARS),
                cryptoCreate("completer").balance(ONE_HUNDRED_HBARS),
                clprRegisterChannel()
                        .ownershipCommitment(crypto.commitment())
                        .payingWith("committer")
                        .hasKnownStatus(SUCCESS),
                clprCompleteChannel()
                        .channelId(crypto.channelId())
                        .publicKey(crypto.publicKey())
                        .signature(crypto.signature())
                        .signatureScheme(ClprSignatureScheme.ED25519)
                        .verifierContract(VERIFIER)
                        .configProofBytes(defaultConfigProofBytes())
                        .payingWith("completer")
                        .hasKnownStatus(SUCCESS));
    }

    // ── Multi-step failure tests ─────────────────────────────────────────────

    @LeakyHapiTest(requirement = PROPERTY_OVERRIDES)
    @DisplayName("Double reveal fails — second attempt gets CHANNEL_ALREADY_EXISTS")
    final Stream<DynamicTest> doubleRevealFails() {
        final var crypto = new ClprChannelCrypto();
        return hapiTest(
                overriding("clpr.enabled", "true"),
                uploadInitCode(VERIFIER),
                contractCreate(VERIFIER),
                clprRegisterChannel().ownershipCommitment(crypto.commitment()).payingWith(GENESIS),
                clprCompleteChannel()
                        .channelId(crypto.channelId())
                        .publicKey(crypto.publicKey())
                        .signature(crypto.signature())
                        .signatureScheme(ClprSignatureScheme.ED25519)
                        .verifierContract(VERIFIER)
                        .configProofBytes(defaultConfigProofBytes())
                        .payingWith(GENESIS)
                        .hasKnownStatus(SUCCESS),
                clprCompleteChannel()
                        .channelId(crypto.channelId())
                        .publicKey(crypto.publicKey())
                        .signature(crypto.signature())
                        .signatureScheme(ClprSignatureScheme.ED25519)
                        .verifierContract(VERIFIER)
                        .configProofBytes(defaultConfigProofBytes())
                        .payingWith(GENESIS)
                        .hasKnownStatus(CLPR_CHANNEL_ALREADY_EXISTS));
    }

    @LeakyHapiTest(requirement = PROPERTY_OVERRIDES)
    @DisplayName("Admin deletes pending commitment, then reveal fails with COMMITMENT_MISMATCH")
    final Stream<DynamicTest> adminDeletesPendingThenRevealFails() {
        final var crypto = new ClprChannelCrypto();
        return hapiTest(
                overriding("clpr.enabled", "true"),
                uploadInitCode(VERIFIER),
                contractCreate(VERIFIER),
                // Phase 1: commit
                clprRegisterChannel().ownershipCommitment(crypto.commitment()).payingWith(GENESIS),
                // Admin sweeps the abandoned pending commitment
                clprCloseChannel()
                        .channelId(crypto.channelId())
                        .ownershipCommitment(crypto.commitment())
                        .payingWith(GENESIS),
                // Phase 2: reveal should now fail — commitment was deleted
                clprCompleteChannel()
                        .channelId(crypto.channelId())
                        .publicKey(crypto.publicKey())
                        .signature(crypto.signature())
                        .signatureScheme(ClprSignatureScheme.ED25519)
                        .verifierContract(VERIFIER)
                        .configProofBytes(defaultConfigProofBytes())
                        .payingWith(GENESIS)
                        .hasKnownStatus(CLPR_COMMITMENT_MISMATCH));
    }

    @LeakyHapiTest(requirement = PROPERTY_OVERRIDES)
    @DisplayName("Cross-registrant attack: reveal with wrong key fails COMMITMENT_MISMATCH")
    final Stream<DynamicTest> crossRegistrantAttackFails() {
        final var legitimate = new ClprChannelCrypto();
        final var attacker = new ClprChannelCrypto();
        return hapiTest(
                overriding("clpr.enabled", "true"),
                uploadInitCode(VERIFIER),
                contractCreate(VERIFIER),
                cryptoCreate("attacker").balance(ONE_HUNDRED_HBARS),
                // Legitimate registrant commits
                clprRegisterChannel()
                        .ownershipCommitment(legitimate.commitment())
                        .payingWith(GENESIS),
                // Attacker tries to reveal with legitimate's channelId but attacker's key.
                // keccak256(legitimate.channelId || attacker.pubKey) != legitimate.commitment
                clprCompleteChannel()
                        .channelId(legitimate.channelId())
                        .publicKey(attacker.publicKey())
                        .signature(attacker.signature())
                        .signatureScheme(ClprSignatureScheme.ED25519)
                        .verifierContract(VERIFIER)
                        .configProofBytes(defaultConfigProofBytes())
                        .payingWith("attacker")
                        .hasKnownStatus(CLPR_COMMITMENT_MISMATCH));
    }

    @LeakyHapiTest(requirement = PROPERTY_OVERRIDES)
    @DisplayName("Reveal with non-existent verifier contract fails INVALID_VERIFIER_CONTRACT")
    final Stream<DynamicTest> nonExistentVerifierFails() {
        final var crypto = new ClprChannelCrypto();
        final var bogusContractId = ContractID.newBuilder()
                .setShardNum(0)
                .setRealmNum(0)
                .setContractNum(99_999_999L)
                .build();
        return hapiTest(
                overriding("clpr.enabled", "true"),
                clprRegisterChannel().ownershipCommitment(crypto.commitment()).payingWith(GENESIS),
                clprCompleteChannel()
                        .channelId(crypto.channelId())
                        .publicKey(crypto.publicKey())
                        .signature(crypto.signature())
                        .signatureScheme(ClprSignatureScheme.ED25519)
                        .verifierContractId(bogusContractId)
                        .configProofBytes(defaultConfigProofBytes())
                        .payingWith(GENESIS)
                        .hasKnownStatus(CLPR_INVALID_VERIFIER_CONTRACT));
    }

    @LeakyHapiTest(requirement = PROPERTY_OVERRIDES)
    @DisplayName("Commit when CLPR is disabled fails with CLPR_NOT_ENABLED")
    final Stream<DynamicTest> commitWhenDisabledFails() {
        return hapiTest(
                overriding("clpr.enabled", "false"),
                clprRegisterChannel()
                        .ownershipCommitment(new byte[32])
                        .payingWith(GENESIS)
                        .hasPrecheck(CLPR_NOT_ENABLED));
    }

    // ── Embedded-state regression tests ─────────────────────────────────────

    @LeakyEmbeddedHapiTest(
            reason = NEEDS_STATE_ACCESS,
            overrides = {"clpr.enabled"})
    @DisplayName("verifyConfig V2: verifier-returned throttles and chainId are stored on channel")
    final Stream<DynamicTest> verifyConfigV2FieldsStoredOnChannel() {
        final var crypto = new ClprChannelCrypto();
        return hapiTest(
                overriding("clpr.enabled", "true"),
                uploadInitCode(VERIFIER),
                contractCreate(VERIFIER),
                clprRegisterChannel().ownershipCommitment(crypto.commitment()).payingWith(GENESIS),
                clprCompleteChannel()
                        .channelId(crypto.channelId())
                        .publicKey(crypto.publicKey())
                        .signature(crypto.signature())
                        .signatureScheme(ClprSignatureScheme.ED25519)
                        .verifierContract(VERIFIER)
                        .configProofBytes(defaultConfigProofBytes())
                        .payingWith(GENESIS)
                        .hasKnownStatus(SUCCESS),
                withOpContext((spec, opLog) -> {
                    final var conn = readChannelFromState(spec, crypto);
                    assertEquals(
                            "hiero:testing",
                            conn.chainId(),
                            "chainId from verifyConfig V2 should be stored on channel");
                    final var t = conn.peerThrottles();
                    assertNotNull(t, "peerThrottles should be stored from verifyConfig V2 return");
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
                    assertEquals(1_048_576L, t.maxSyncBytes(), "maxSyncBytes from verifier should match proof config");
                    // channelContext = abi.encodePacked(bytes32 channelId, bytes serviceAddress)
                    // serviceAddress = {0, 0, 1} (3 bytes); channelId = 32 bytes → total 35
                    assertEquals(
                            35L,
                            conn.channelContext().length(),
                            "channelContext should be 32 (channelId) + 3 (serviceAddress) bytes");
                }));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Synthetic config proof bytes for the passthrough verifier path; spec refs §3.1
     * (verifyConfig contract) and §5.1.3 (completeChannel consumes the verifier output).
     */
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
                // Non-empty endpoints required by ClprCompleteChannelHandler (spec §5.1.3
                // step 5 — verified peer config must include at least one endpoint).
                .addEndpoints(ClprEndpoint.newBuilder()
                        .setServiceEndpoint(ClprServiceEndpoint.newBuilder()
                                .setIpAddress("127.0.0.1")
                                .setPort(50211)
                                .build())
                        .setTlsCertificate(ByteString.copyFrom(new byte[] {0x01}))
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
