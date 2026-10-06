// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.clpr;

import static com.hedera.services.bdd.junit.ContextRequirement.PROPERTY_OVERRIDES;
import static com.hedera.services.bdd.junit.TestTags.CLPR;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.clprCloseChannel;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.clprCompleteChannel;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.clprRegisterChannel;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.clprSubmitBundle;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.AUTHORIZATION_FAILED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CLPR_CHANNEL_NOT_FOUND;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CLPR_INVALID_CHANNEL_STATUS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CLPR_NOT_ENABLED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TRANSACTION_BODY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.NOT_SUPPORTED;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.junit.LeakyHapiTest;
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
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * HAPI tests for CLPR close-channel (CLPR-1.5).
 */
@Tag(CLPR)
public class ClprCloseChannelSuite {

    private static final String VERIFIER_CONTRACT = "ClprPassThroughVerifier";

    @LeakyHapiTest(requirement = PROPERTY_OVERRIDES)
    final Stream<DynamicTest> rejectsCloseWhenDisabled() {
        return hapiTest(
                overriding("clpr.enabled", "false"),
                clprCloseChannel().channelId(new byte[32]).payingWith(GENESIS).hasPrecheck(CLPR_NOT_ENABLED));
    }

    @LeakyHapiTest(requirement = PROPERTY_OVERRIDES)
    final Stream<DynamicTest> nonAdminCannotCloseChannel() {
        return hapiTest(
                overriding("clpr.enabled", "true"),
                cryptoCreate("civilian").balance(ONE_HUNDRED_HBARS),
                clprCloseChannel()
                        .channelId(new byte[32])
                        .payingWith("civilian")
                        .hasKnownStatusFrom(AUTHORIZATION_FAILED, NOT_SUPPORTED));
    }

    @LeakyHapiTest(requirement = PROPERTY_OVERRIDES)
    final Stream<DynamicTest> rejectsInvalidChannelIdLength() {
        return hapiTest(
                overriding("clpr.enabled", "true"),
                clprCloseChannel().channelId(new byte[16]).payingWith(GENESIS).hasPrecheck(INVALID_TRANSACTION_BODY));
    }

    @LeakyHapiTest(requirement = PROPERTY_OVERRIDES)
    final Stream<DynamicTest> rejectsCloseOfNonexistentChannel() {
        return hapiTest(
                overriding("clpr.enabled", "true"),
                clprCloseChannel().channelId(new byte[32]).payingWith(GENESIS).hasKnownStatus(CLPR_CHANNEL_NOT_FOUND));
    }

    @LeakyHapiTest(requirement = PROPERTY_OVERRIDES)
    final Stream<DynamicTest> canCloseActiveChannel() {
        final var crypto = new ClprChannelCrypto();
        return hapiTest(
                overriding("clpr.enabled", "true"),
                // Deploy a simple contract to act as the verifier
                uploadInitCode(VERIFIER_CONTRACT),
                contractCreate(VERIFIER_CONTRACT),
                // Phase 1: Register (commit)
                clprRegisterChannel().ownershipCommitment(crypto.commitment()).payingWith(GENESIS),
                // Phase 2: Complete (reveal) — creates ACTIVE channel
                clprCompleteChannel()
                        .channelId(crypto.channelId())
                        .publicKey(crypto.publicKey())
                        .signature(crypto.signature())
                        .signatureScheme(ClprSignatureScheme.ED25519)
                        .verifierContract(VERIFIER_CONTRACT)
                        .configProofBytes(defaultConfigProofBytes())
                        .payingWith(GENESIS),
                // Phase 3: Close — transitions ACTIVE → CLOSING
                clprCloseChannel().channelId(crypto.channelId()).payingWith(GENESIS));
    }

    @LeakyHapiTest(requirement = PROPERTY_OVERRIDES)
    final Stream<DynamicTest> adminRecoveryClosesDrainedChannel() {
        final var crypto = new ClprChannelCrypto();
        final var drainingBundle = ClprBundleContent.newBuilder()
                .setMetadata(ClprQueueMetadata.newBuilder()
                        .setNextMessageId(2)
                        .setSentRunningHash(ByteString.copyFrom(new byte[32]))
                        .setReceivedMessageId(0)
                        .setStatus(ClprChannelStatus.CLOSING)
                        .build())
                .addMessages(ClprMessagePayload.newBuilder()
                        .setMessage(ClprMessage.newBuilder()
                                .setConnectorId(ByteString.copyFrom(new byte[32]))
                                .setTargetApplication(ByteString.copyFrom(new byte[20]))
                                .setSender(ByteString.copyFrom(new byte[20]))
                                .setMessageData(ByteString.copyFromUtf8("drain"))
                                .build())
                        .build())
                .build();
        final var hashAfterBundle1 = ClprTestProofs.runningHashAfter(new byte[32], drainingBundle.getMessagesList());
        return hapiTest(
                overriding("clpr.enabled", "true"),
                uploadInitCode(VERIFIER_CONTRACT),
                contractCreate(VERIFIER_CONTRACT),
                clprRegisterChannel().ownershipCommitment(crypto.commitment()).payingWith(GENESIS),
                clprCompleteChannel()
                        .channelId(crypto.channelId())
                        .publicKey(crypto.publicKey())
                        .signature(crypto.signature())
                        .signatureScheme(ClprSignatureScheme.ED25519)
                        .verifierContract(VERIFIER_CONTRACT)
                        .configProofBytes(defaultConfigProofBytes())
                        .payingWith(GENESIS),
                // Submit a bundle with peer=CLOSING containing one DATA message. The receiving ledger
                // transitions ACTIVE → CLOSING and enqueues a CHANNEL_CLOSED reply. Not yet DRAINED — peer hasn't
                // acked the receiving ledger's reply.
                clprSubmitBundle()
                        .channelId(crypto.channelId())
                        .bundlePayload(ClprTestProofs.toBundleProofBytes(drainingBundle))
                        .endpointNodeId(0L)
                        .payingWith(GENESIS),
                // Peer confirms it received our CHANNEL_CLOSED reply (receivedMessageId=1) and has
                // no further data to send — both queues should now be empty, so the receiving ledger's channel
                // drains.
                clprSubmitBundle()
                        .channelId(crypto.channelId())
                        .bundlePayload(ClprTestProofs.toBundleProofBytes(
                                ClprBundleContent.newBuilder()
                                        .setMetadata(ClprQueueMetadata.newBuilder()
                                                .setNextMessageId(3)
                                                .setSentRunningHash(ByteString.copyFrom(new byte[32]))
                                                // Setting received message ID simulates the peer ledger receiving the
                                                // CHANNEL_CLOSED response from the receiving ledger
                                                .setReceivedMessageId(1)
                                                .setStatus(ClprChannelStatus.CLOSING)
                                                .build())
                                        .addMessages(ClprMessagePayload.newBuilder()
                                                .setMessageReply(ClprMessageReply.newBuilder()
                                                        .setMessageId(1)
                                                        .setStatus(ClprMessageReplyStatus.CHANNEL_CLOSED)
                                                        .build())
                                                .build())
                                        .build(),
                                hashAfterBundle1))
                        .endpointNodeId(0L)
                        .payingWith(GENESIS),
                // Admin recovery: peer is unable to submit a close notification for some reason, so admin calls
                // closeChannel to transition channel from DRAINED -> CLOSED
                clprCloseChannel().channelId(crypto.channelId()).payingWith(GENESIS),
                // Subsequent close attempts are rejected since channel is now CLOSED
                clprCloseChannel()
                        .channelId(crypto.channelId())
                        .payingWith(GENESIS)
                        .hasKnownStatus(CLPR_INVALID_CHANNEL_STATUS));
    }

    /**
     * Builds a synthetic StateProof wrapping a minimal ClprLedgerConfiguration so the
     * deployed ClprPassThroughVerifier accepts it (see {@link ClprTestProofs} for layout).
     *
     * <p>Spec refs: §3.1 (Verifier Contract Interface — verifyConfig returns the proven
     * ClprLedgerConfiguration), §5.1.3 (completeChannel runs verifyConfig over the
     * supplied config_proof_bytes); §1.1 (ClprLedgerConfiguration shape, including the
     * renamed {@code endpoints} field and the {@code initial_trust_anchor} pair).
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
                // At least one endpoint is required: ClprCompleteChannelHandler asserts
                // !peerConfig.endpoints().isEmpty() (spec §5.1.3 step 5 — verified peer config
                // must carry a non-empty endpoints list; matches §1.1 / §1.2 ClprEndpoint).
                .addEndpoints(ClprEndpoint.newBuilder()
                        .setServiceEndpoint(ClprServiceEndpoint.newBuilder()
                                .setIpAddress("127.0.0.1")
                                .setPort(50211)
                                .build())
                        .setTlsCertificate(ByteString.copyFrom(new byte[] {0x01}))
                        .build())
                .build());
    }
}
