// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.clpr;

import static com.hedera.hapi.node.state.clpr.ClprChannelStatus.ACTIVE;
import static com.hedera.hapi.node.state.clpr.ClprChannelStatus.PAUSED;
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
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overridingTwo;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.clpr.ClprTestProofs.toConfigProofBytes;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CLPR_BUNDLE_VERIFICATION_FAILED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;

import com.google.protobuf.ByteString;
import com.hedera.hapi.node.state.clpr.ClprChannel;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.node.app.service.clpr.ClprService;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.junit.LeakyEmbeddedHapiTest;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.transactions.contract.HapiContractCall;
import com.hederahashgraph.api.proto.java.ClprBundleContent;
import com.hederahashgraph.api.proto.java.ClprChannelStatus;
import com.hederahashgraph.api.proto.java.ClprEndpoint;
import com.hederahashgraph.api.proto.java.ClprLedgerConfiguration;
import com.hederahashgraph.api.proto.java.ClprMessage;
import com.hederahashgraph.api.proto.java.ClprMessagePayload;
import com.hederahashgraph.api.proto.java.ClprMessageReply;
import com.hederahashgraph.api.proto.java.ClprMessageReplyStatus;
import com.hederahashgraph.api.proto.java.ClprQueueMetadata;
import com.hederahashgraph.api.proto.java.ClprServiceEndpoint;
import com.hederahashgraph.api.proto.java.ClprSignatureScheme;
import com.hederahashgraph.api.proto.java.ClprThrottles;
import com.swirlds.state.spi.WritableKVState;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * Bundle-handler tests that play the role of a misbehaving or edge-case peer by submitting
 * forged {@link ClprBundleContent} payloads through {@code clprSubmitBundle}. Embedded mode
 * is used so {@code channel.sentRunningHash} can be read mid-test and folded into the
 * forgery — the handler's Step 4 hash check requires the bundle's claimed
 * {@code metadata.received_running_hash} to match A's actual outbound chain.
 */
@Tag(CLPR)
public class ClprNegativePeerSuite {

    private static final String VERIFIER_CONTRACT = "ClprPassThroughVerifier";
    private static final String CONNECTOR_CONTRACT = "PassThroughAuth";
    private static final String CLPR_CONTRACT = "ClprSystemContract";
    private static final byte[] ZERO_HASH = new byte[32];

    @LeakyEmbeddedHapiTest(
            reason = NEEDS_STATE_ACCESS,
            overrides = {"clpr.enabled", "clpr.minLockedStake"})
    @DisplayName("Malformed (raw) bundle → CLPR_BUNDLE_VERIFICATION_FAILED")
    final Stream<DynamicTest> malformedBundleRejected() {
        final var crypto = new ClprChannelCrypto();
        return hapiTest(
                clprUpdateLedgerConfiguration()
                        .configuration(defaultLedgerConfig())
                        .payingWith(GENESIS),
                uploadInitCode(VERIFIER_CONTRACT),
                contractCreate(VERIFIER_CONTRACT),
                clprRegisterChannel().ownershipCommitment(crypto.commitment()).payingWith(GENESIS),
                clprCompleteChannel()
                        .channelId(crypto.channelId())
                        .publicKey(crypto.publicKey())
                        .signature(crypto.signature())
                        .signatureScheme(ClprSignatureScheme.ED25519)
                        .verifierContract(VERIFIER_CONTRACT)
                        .configProofBytes(toConfigProofBytes(defaultLedgerConfig()))
                        .payingWith(GENESIS),
                clprSubmitBundle()
                        .channelId(crypto.channelId())
                        .bundlePayload(bundleWithSwappedMessageOrder())
                        .endpointNodeId(0L)
                        .payingWith(GENESIS)
                        .hasKnownStatus(CLPR_BUNDLE_VERIFICATION_FAILED));
    }

    /** Spec §4.5 step 4: out-of-order replies → channel PAUSED (no bundle-level rejection). */
    @LeakyEmbeddedHapiTest(
            reason = NEEDS_STATE_ACCESS,
            overrides = {"clpr.enabled", "clpr.minLockedStake"})
    @DisplayName("Out-of-order replies → channel PAUSED (spec §4.5)")
    final Stream<DynamicTest> outOfOrderReplies() {
        final var crypto = new ClprChannelCrypto();
        final var aSentRunningHash = new AtomicReference<byte[]>();
        return hapiTest(concat(
                setupChannelWithTwoSends(crypto, defaultLedgerConfig(), aSentRunningHash),
                sourcing(() -> clprSubmitBundle()
                        .channelId(crypto.channelId())
                        .bundlePayload(bundleWithOutOfOrderReplies(aSentRunningHash.get()))
                        .endpointNodeId(0L)
                        .payingWith(GENESIS)),
                requirePaused(crypto)));
    }

    /** Spec §4.5 PAUSED-recovery: after PAUSED, a well-formed reply bundle returns the channel to ACTIVE. */
    @LeakyEmbeddedHapiTest(
            reason = NEEDS_STATE_ACCESS,
            overrides = {"clpr.enabled", "clpr.minLockedStake"})
    @DisplayName("PAUSED → ACTIVE recovery after a multi-message bundle (spec §4.5)")
    final Stream<DynamicTest> pausedToActiveRecoveryAfterMultiMessageBundle() {
        final var crypto = new ClprChannelCrypto();
        final var aSentRunningHash = new AtomicReference<byte[]>();
        return hapiTest(concat(
                setupChannelWithTwoSends(crypto, defaultLedgerConfig(), aSentRunningHash),
                sourcing(() -> clprSubmitBundle()
                        .channelId(crypto.channelId())
                        .bundlePayload(bundleWithOutOfOrderReplies(aSentRunningHash.get()))
                        .endpointNodeId(0L)
                        .payingWith(GENESIS)),
                requirePaused(crypto),
                sourcing(() -> clprSubmitBundle()
                        .channelId(crypto.channelId())
                        .bundlePayload(bundleWithCorrectlyOrderedReplies(aSentRunningHash.get()))
                        .endpointNodeId(0L)
                        .payingWith(GENESIS)),
                requireActive(crypto)));
    }

    /**
     * Spec §4.2 step 6 vs §4.3 step 5: handler-driven reply enqueues bypass {@code maxQueueDepth},
     * but the next app-originated {@code sendMessage} respects it and reverts.
     */
    @LeakyEmbeddedHapiTest(
            reason = NEEDS_STATE_ACCESS,
            overrides = {"clpr.enabled", "clpr.minLockedStake", "clpr.connectorQueueQuotaPct"})
    @DisplayName("Queue-full during multi-message inbound: reply enqueues bypass cap, app sends blocked")
    final Stream<DynamicTest> queueFullDuringMultiMessageInbound() {
        final var crypto = new ClprChannelCrypto();
        final var aSentRunningHash = new AtomicReference<byte[]>();
        // 2 sends fit under cap=4; the bundle's 2 reply enqueues push next_message_id to 5.
        final int tightQueueDepth = 4;
        return hapiTest(concat(
                new SpecOperation[] {
                    // 100% disables the per-connector quota so the depth cap isn't masked by §8.11.
                    overriding("clpr.connectorQueueQuotaPct", "100"),
                },
                setupChannelWithTwoSends(crypto, defaultLedgerConfig(tightQueueDepth), aSentRunningHash),
                sourcing(() -> clprSubmitBundle()
                        .channelId(crypto.channelId())
                        .bundlePayload(bundleWithTwoDataMessagesUnknownConnector(aSentRunningHash.get()))
                        .endpointNodeId(0L)
                        .payingWith(GENESIS)),
                withOpContext((spec, opLog) -> {
                    final var conn = readChannel(spec, crypto);
                    if (conn.nextMessageId() <= tightQueueDepth) {
                        throw new AssertionError("Expected next_message_id > " + tightQueueDepth
                                + " after reply enqueues; was " + conn.nextMessageId());
                    }
                }),
                sendMessageOp(crypto, "msg-blocked").hasKnownStatus(CONTRACT_REVERT_EXECUTED)));
    }

    /** Step 8's non-resetting {@code responseIndex} must skip the Data slot between two replies. */
    @LeakyEmbeddedHapiTest(
            reason = NEEDS_STATE_ACCESS,
            overrides = {"clpr.enabled", "clpr.minLockedStake"})
    @DisplayName("Interleaved Data+Reply in same bundle → channel stays ACTIVE")
    final Stream<DynamicTest> interleavedDataAndReplyInSameBundle() {
        final var crypto = new ClprChannelCrypto();
        final var aSentRunningHash = new AtomicReference<byte[]>();
        return hapiTest(concat(
                setupChannelWithTwoSends(crypto, defaultLedgerConfig(), aSentRunningHash),
                sourcing(() -> clprSubmitBundle()
                        .channelId(crypto.channelId())
                        .bundlePayload(bundleInterleavedDataAndReply(aSentRunningHash.get()))
                        .endpointNodeId(0L)
                        .payingWith(GENESIS)),
                requireActive(crypto)));
    }

    // ── Setup helpers ────────────────────────────────────────────────────────

    /**
     * Builds the common setup: ledger config + verifier/connector/system contracts + channel
     * commit-reveal + connector commit-reveal + 2 app-originated sends. Captures A's
     * {@code sentRunningHash} into {@code aSentRunningHash} so forged bundles can pass Step 4.
     */
    private SpecOperation[] setupChannelWithTwoSends(
            final ClprChannelCrypto crypto,
            final ClprLedgerConfiguration config,
            final AtomicReference<byte[]> aSentRunningHash) {
        return new SpecOperation[] {
            overridingTwo("clpr.enabled", "true", "clpr.minLockedStake", "100"),
            clprUpdateLedgerConfiguration().configuration(config).payingWith(GENESIS),
            uploadInitCode(VERIFIER_CONTRACT),
            contractCreate(VERIFIER_CONTRACT),
            uploadInitCode(CONNECTOR_CONTRACT),
            contractCreate(CONNECTOR_CONTRACT),
            uploadInitCode(CLPR_CONTRACT),
            contractCreate(CLPR_CONTRACT),
            cryptoCreate("caller").balance(ONE_HUNDRED_HBARS),
            clprRegisterChannel().ownershipCommitment(crypto.commitment()).payingWith(GENESIS),
            clprCompleteChannel()
                    .channelId(crypto.channelId())
                    .publicKey(crypto.publicKey())
                    .signature(crypto.signature())
                    .signatureScheme(ClprSignatureScheme.ED25519)
                    .verifierContract(VERIFIER_CONTRACT)
                    .configProofBytes(toConfigProofBytes(config))
                    .payingWith(GENESIS),
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
                    .payingWith(GENESIS),
            sendMessageOp(crypto, "msg-1"),
            sendMessageOp(crypto, "msg-2"),
            withOpContext((spec, opLog) -> aSentRunningHash.set(
                    readChannel(spec, crypto).sentRunningHash().toByteArray())),
        };
    }

    private static HapiContractCall sendMessageOp(final ClprChannelCrypto crypto, final String data) {
        return contractCall(
                        CLPR_CONTRACT,
                        "sendMessage",
                        crypto.channelId(),
                        crypto.connectorId(),
                        new byte[20],
                        data.getBytes(StandardCharsets.UTF_8))
                .gas(2_000_000L)
                .payingWith("caller");
    }

    private SpecOperation requireActive(final ClprChannelCrypto crypto) {
        return withOpContext((spec, opLog) -> {
            final var conn = readChannel(spec, crypto);
            if (conn.status() != ACTIVE) {
                throw new AssertionError("Expected ACTIVE, was " + conn.status());
            }
        });
    }

    private SpecOperation requirePaused(final ClprChannelCrypto crypto) {
        return withOpContext((spec, opLog) -> {
            final var conn = readChannel(spec, crypto);
            if (conn.status() != PAUSED) {
                throw new AssertionError("Expected PAUSED, was " + conn.status());
            }
        });
    }

    private ClprChannel readChannel(final HapiSpec spec, final ClprChannelCrypto crypto) {
        final var conn = embeddedClprChannelsOrThrow(spec).get(new ProtoBytes(Bytes.wrap(crypto.channelId())));
        if (conn == null) {
            throw new IllegalStateException("Channel not found in embedded state");
        }
        return conn;
    }

    private @NonNull WritableKVState<ProtoBytes, ClprChannel> embeddedClprChannelsOrThrow(final HapiSpec spec) {
        return spec.embeddedStateOrThrow().getWritableStates(ClprService.NAME).get(CHANNELS_STATE_ID);
    }

    private static SpecOperation[] concat(final SpecOperation[] head, final SpecOperation... tail) {
        final var out = new SpecOperation[head.length + tail.length];
        System.arraycopy(head, 0, out, 0, head.length);
        System.arraycopy(tail, 0, out, head.length, tail.length);
        return out;
    }

    private static SpecOperation[] concat(
            final SpecOperation[] head, final SpecOperation[] mid, final SpecOperation... tail) {
        final var out = new SpecOperation[head.length + mid.length + tail.length];
        System.arraycopy(head, 0, out, 0, head.length);
        System.arraycopy(mid, 0, out, head.length, mid.length);
        System.arraycopy(tail, 0, out, head.length + mid.length, tail.length);
        return out;
    }

    private static ClprLedgerConfiguration defaultLedgerConfig() {
        return defaultLedgerConfig(1000);
    }

    private static ClprLedgerConfiguration defaultLedgerConfig(final int maxQueueDepth) {
        return ClprLedgerConfiguration.newBuilder()
                .setChainId("hiero:testing")
                .setServiceAddress(ByteString.copyFrom(new byte[] {0, 0, 1}))
                .setThrottles(ClprThrottles.newBuilder()
                        .setMaxMessagesPerBundle(100)
                        .setMaxMessagePayloadBytes(65536)
                        .setMaxGasPerMessage(1_000_000L)
                        .setMaxQueueDepth(maxQueueDepth)
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

    // ── Bundle helpers ─────────────────────────────────────────────────────

    /** Raw {@link ClprBundleContent} (not wrapped in a StateProof) — verifier reverts. */
    private static byte[] bundleWithSwappedMessageOrder() {
        final var payloadA = dataPayload(new byte[] {1, 2, 3}, "msg-A");
        final var payloadB = dataPayload(new byte[] {4, 5, 6}, "msg-B");
        final var h1 = sha256(ZERO_HASH, payloadA.toByteArray());
        final var claimedRunningHash = sha256(h1, payloadB.toByteArray());
        return ClprBundleContent.newBuilder()
                .setMetadata(ClprQueueMetadata.newBuilder()
                        .setNextMessageId(3)
                        .setSentRunningHash(ByteString.copyFrom(claimedRunningHash))
                        .setReceivedMessageId(0)
                        .setStatus(ClprChannelStatus.ACTIVE)
                        .build())
                .addMessages(payloadB)
                .addMessages(payloadA)
                .build()
                .toByteArray();
    }

    /** [reply→2, reply→1] — violates the strictly-increasing order Step 8 expects. */
    private static byte[] bundleWithOutOfOrderReplies(final byte[] aSentRunningHash) {
        return replyBundle(aSentRunningHash, replyPayload(2L), replyPayload(1L));
    }

    /** [reply→1, reply→2] — well-formed, used to drive PAUSED → ACTIVE recovery. */
    private static byte[] bundleWithCorrectlyOrderedReplies(final byte[] aSentRunningHash) {
        return replyBundle(aSentRunningHash, replyPayload(1L), replyPayload(2L));
    }

    /**
     * 2 data messages with an unknown connector_id. The handler emits
     * {@code CONNECTOR_NOT_FOUND} replies (still enqueued into A's outbound).
     * {@code metadata.received_message_id = 0} so A's {@code ackedMessageId} doesn't
     * advance — otherwise the in-flight count would mask the depth-cap boundary.
     */
    private static byte[] bundleWithTwoDataMessagesUnknownConnector(final byte[] aSentRunningHash) {
        final var bundle = ClprBundleContent.newBuilder()
                .setMetadata(ClprQueueMetadata.newBuilder()
                        // nextMessageId = ackedMessageId(0) + 1 + msgCount(2) — see ClprTestProofs.
                        .setNextMessageId(3)
                        .setReceivedMessageId(0)
                        .setReceivedRunningHash(ByteString.copyFrom(aSentRunningHash))
                        .setStatus(ClprChannelStatus.ACTIVE)
                        .build())
                .addMessages(dataPayload(new byte[32], "B-data-1"))
                .addMessages(dataPayload(new byte[32], "B-data-2"))
                .build();
        return ClprTestProofs.toBundleProofBytes(bundle);
    }

    /** [reply→1, data, reply→2] — exercises Step 8's non-resetting responseIndex. */
    private static byte[] bundleInterleavedDataAndReply(final byte[] aSentRunningHash) {
        final var bundle = ClprBundleContent.newBuilder()
                .setMetadata(ClprQueueMetadata.newBuilder()
                        .setNextMessageId(4)
                        .setReceivedMessageId(2)
                        .setReceivedRunningHash(ByteString.copyFrom(aSentRunningHash))
                        .setStatus(ClprChannelStatus.ACTIVE)
                        .build())
                .addMessages(replyPayload(1L))
                .addMessages(dataPayload(new byte[32], "B-interleaved-data"))
                .addMessages(replyPayload(2L))
                .build();
        return ClprTestProofs.toBundleProofBytes(bundle);
    }

    private static byte[] replyBundle(final byte[] aSentRunningHash, final ClprMessagePayload... replies) {
        final var bundleBuilder = ClprBundleContent.newBuilder()
                .setMetadata(ClprQueueMetadata.newBuilder()
                        .setNextMessageId(replies.length + 1)
                        .setReceivedMessageId(replies.length)
                        .setReceivedRunningHash(ByteString.copyFrom(aSentRunningHash))
                        .setStatus(ClprChannelStatus.ACTIVE)
                        .build());
        for (final var reply : replies) {
            bundleBuilder.addMessages(reply);
        }
        return ClprTestProofs.toBundleProofBytes(bundleBuilder.build());
    }

    private static ClprMessagePayload replyPayload(final long replyTargetId) {
        return ClprMessagePayload.newBuilder()
                .setMessageReply(ClprMessageReply.newBuilder()
                        .setMessageId(replyTargetId)
                        .setStatus(ClprMessageReplyStatus.SUCCESS)
                        .setMessageReplyData(ByteString.EMPTY)
                        .build())
                .build();
    }

    private static ClprMessagePayload dataPayload(final byte[] connectorAddr, final String data) {
        return ClprMessagePayload.newBuilder()
                .setMessage(ClprMessage.newBuilder()
                        .setConnectorId(ByteString.copyFrom(connectorAddr))
                        .setTargetApplication(ByteString.copyFrom(new byte[20]))
                        .setSender(ByteString.copyFrom(new byte[20]))
                        .setMessageData(ByteString.copyFromUtf8(data))
                        .build())
                .build();
    }

    private static byte[] sha256(final byte[] previousHash, final byte[] serializedPayload) {
        try {
            final var digest = MessageDigest.getInstance("SHA-256");
            digest.update(previousHash);
            digest.update(serializedPayload);
            return digest.digest();
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
