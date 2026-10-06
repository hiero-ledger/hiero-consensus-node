// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.clpr;

import static com.hedera.services.bdd.junit.ContextRequirement.PROPERTY_OVERRIDES;
import static com.hedera.services.bdd.junit.TestTags.CLPR;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.clprCloseChannel;
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
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.clpr.ClprTestProofs.toBundleProofBytes;
import static com.hedera.services.bdd.suites.clpr.ClprTestProofs.toConfigProofBytes;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CLPR_BUNDLE_VERIFICATION_FAILED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CLPR_CHANNEL_NOT_FOUND;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CLPR_INVALID_CHANNEL_STATUS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CLPR_NOT_ENABLED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TRANSACTION_BODY;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hederahashgraph.api.proto.java.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * HAPI tests for CLPR submit-bundle (CLPR-2.3).
 */
// TODO(#129): add an end-to-end gas-exhaustion BDD test for the max_gas_per_message ceiling
// (test plan §3.10.3 / §5.6.2). The handler now dispatches application callbacks with
// throttles.maxGasPerMessage(); a faithful BDD test must deploy a gas-burning IClprApplication
// contract, set a low maxGasPerMessage, do the full connector commit-reveal (clprCompleteConnector)
// with a funded connector contract, build a bundle whose data message targets the gas-burner's
// resolved EVM address, and verify submitBundle is handled gracefully (no crash, receivedMessageId
// advances). NOTE: single-network mode cannot observe the APPLICATION_ERROR reply status
// (getChannelQueueState only exposes received/acked message IDs), so the reply-status assertion
// belongs in the multi-network ClprMessagesSuite round-trip. Reply-status + state-integrity are
// already covered deterministically by ClprSubmitBundleHandlerTest's max_gas_per_message tests.
@Tag(CLPR)
public class ClprSubmitBundleSuite {

    private static final String VERIFIER_CONTRACT = "ClprPassThroughVerifier";
    private static final String CONNECTOR_CONTRACT = "PassThroughAuth";
    private static final String CLPR_CONTRACT = "ClprSystemContract";
    private static final byte[] ZERO_HASH = new byte[32];

    @LeakyHapiTest(requirement = PROPERTY_OVERRIDES)
    final Stream<DynamicTest> rejectsSubmitBundleWhenDisabled() {
        return hapiTest(
                overriding("clpr.enabled", "false"),
                clprSubmitBundle()
                        .channelId(new byte[32])
                        .bundlePayload(new byte[] {1})
                        .endpointNodeId(0L)
                        .payingWith(GENESIS)
                        .hasPrecheck(CLPR_NOT_ENABLED));
    }

    @LeakyHapiTest(requirement = PROPERTY_OVERRIDES)
    final Stream<DynamicTest> rejectsInvalidChannelIdLength() {
        return hapiTest(
                overriding("clpr.enabled", "true"),
                clprSubmitBundle()
                        .channelId(new byte[16])
                        .bundlePayload(new byte[] {1})
                        .endpointNodeId(0L)
                        .payingWith(GENESIS)
                        .hasPrecheck(INVALID_TRANSACTION_BODY));
    }

    @LeakyHapiTest(requirement = PROPERTY_OVERRIDES)
    final Stream<DynamicTest> rejectsChannelNotFound() {
        return hapiTest(
                overriding("clpr.enabled", "true"),
                clprSubmitBundle()
                        .channelId(new byte[32])
                        .bundlePayload(new byte[] {1})
                        .endpointNodeId(0L)
                        .payingWith(GENESIS)
                        .hasKnownStatus(CLPR_CHANNEL_NOT_FOUND));
    }

    @LeakyHapiTest(requirement = PROPERTY_OVERRIDES)
    final Stream<DynamicTest> rejectsMalformedPayload() {
        final var crypto = new ClprChannelCrypto();
        return hapiTest(
                overriding("clpr.enabled", "true"),
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
                        .bundlePayload(new byte[] {0x7F, 0x7F})
                        .endpointNodeId(0L)
                        .payingWith(GENESIS)
                        .hasKnownStatus(CLPR_BUNDLE_VERIFICATION_FAILED));
    }

    @Disabled("Spec §4.2-1a: bundle with no messages, no trust rotation, and no ack progress is rejected")
    @LeakyHapiTest(requirement = PROPERTY_OVERRIDES)
    final Stream<DynamicTest> successfulEmptyBundle() {
        final var crypto = new ClprChannelCrypto();
        // Empty bundle: no messages, next_message_id=1 (same as channel initial state)
        final var bundle = ClprBundleContent.newBuilder()
                .setMetadata(ClprQueueMetadata.newBuilder()
                        .setNextMessageId(1)
                        .setSentRunningHash(ByteString.copyFrom(ZERO_HASH))
                        .setReceivedMessageId(0)
                        .setStatus(ClprChannelStatus.ACTIVE)
                        .build())
                .build();
        return hapiTest(
                overriding("clpr.enabled", "true"),
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
                        .bundlePayload(bundle.toByteArray())
                        .endpointNodeId(0L)
                        .payingWith(GENESIS));
    }

    @LeakyHapiTest(requirement = PROPERTY_OVERRIDES)
    final Stream<DynamicTest> successfulSingleMessageBundle() {
        final var crypto = new ClprChannelCrypto();
        // Build a single data message and compute running hash
        final var dataPayload = ClprMessagePayload.newBuilder()
                .setMessage(ClprMessage.newBuilder()
                        .setConnectorId(ByteString.copyFrom(crypto.connectorId()))
                        .setTargetApplication(ByteString.copyFrom(new byte[] {40, 50}))
                        .setSender(ByteString.copyFrom(new byte[] {60, 70}))
                        .setMessageData(ByteString.copyFrom(new byte[] {1, 2, 3}))
                        .build())
                .build();
        return hapiTest(
                overridingTwo("clpr.enabled", "true", "clpr.minLockedStake", "100"),
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
                // §6.3 connector commit-reveal
                clprRegisterConnector().commitment(crypto.connectorCommitment()).payingWith(GENESIS),
                clprCompleteConnector()
                        .connectorId(crypto.connectorId())
                        .publicKey(crypto.publicKey())
                        .signature(crypto.connectorSignature())
                        .signatureScheme(ClprSignatureScheme.ED25519)
                        .salt(crypto.connectorSalt())
                        .channelId(crypto.channelId())
                        .connectorContract(VERIFIER_CONTRACT)
                        .adminKeyName(GENESIS)
                        .lockedStake(100_000_000L)
                        .payingWith(GENESIS),
                clprSubmitBundle()
                        .channelId(crypto.channelId())
                        .bundlePayload(toBundleProofBytes(
                                ClprChannelStatus.ACTIVE, 0L, 0L, ZERO_HASH, java.util.List.of(dataPayload)))
                        .endpointNodeId(0L)
                        .payingWith(GENESIS));
    }

    @LeakyHapiTest(requirement = PROPERTY_OVERRIDES)
    final Stream<DynamicTest> drainedChannelStillAcceptsBundles() {
        final var crypto = new ClprChannelCrypto();
        // One "hello" message per bundle so neither hits the EmptyBundle rejection
        // (spec §4.2 step 1a — no messages, no rotation, no ack progress).
        final var helloMessage = ClprMessagePayload.newBuilder()
                .setMessage(ClprMessage.newBuilder()
                        .setConnectorId(ByteString.copyFrom(new byte[32]))
                        .setTargetApplication(ByteString.copyFrom(new byte[20]))
                        .setSender(ByteString.copyFrom(new byte[20]))
                        .setMessageData(ByteString.copyFromUtf8("hello"))
                        .build())
                .build();
        // First bundle: peer reports CLOSING (not DRAINED). Since local has no outbound,
        // local transitions ACTIVE → CLOSING → DRAINED. Peer is only CLOSING so we stop
        // at DRAINED (not CLOSED).
        final var firstBundle = ClprBundleContent.newBuilder()
                .setMetadata(ClprQueueMetadata.newBuilder()
                        .setNextMessageId(2)
                        .setSentRunningHash(ByteString.copyFrom(ZERO_HASH))
                        .setReceivedMessageId(0)
                        .setStatus(ClprChannelStatus.CLOSING)
                        .build())
                .addMessages(helloMessage)
                .build();
        // Second bundle: submitted while local is DRAINED. Must still succeed
        // so the peer can receive acks and complete its drain.
        final var secondBundle = ClprBundleContent.newBuilder()
                .setMetadata(ClprQueueMetadata.newBuilder()
                        .setNextMessageId(2)
                        .setSentRunningHash(ByteString.copyFrom(ZERO_HASH))
                        .setReceivedMessageId(0)
                        .setStatus(ClprChannelStatus.DRAINED)
                        .build())
                .addMessages(helloMessage)
                .build();
        return hapiTest(
                overriding("clpr.enabled", "true"),
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
                // First bundle: peer CLOSING transitions local ACTIVE → CLOSING → DRAINED
                clprSubmitBundle()
                        .channelId(crypto.channelId())
                        .bundlePayload(toBundleProofBytes(firstBundle))
                        .endpointNodeId(0L)
                        .payingWith(GENESIS),
                // Second bundle on DRAINED channel — must still succeed
                clprSubmitBundle()
                        .channelId(crypto.channelId())
                        .bundlePayload(toBundleProofBytes(secondBundle))
                        .endpointNodeId(0L)
                        .payingWith(GENESIS));
    }

    @LeakyHapiTest(requirement = PROPERTY_OVERRIDES)
    final Stream<DynamicTest> closedPeerTriggersClosingOnActiveChannel() {
        // Spec §4.2 step 10: when the bundle metadata reports peer state CLOSED on an ACTIVE
        // local channel, the handler must transition local → CLOSING.
        // (peerState == CLOSED satisfies the same guard as CLOSING/DRAINED per the handler's
        // step-10 check: peerState ∈ {CLOSING, DRAINED, CLOSED} && localStatus == ACTIVE.)
        final var crypto = new ClprChannelCrypto();
        final var closedPeerBundle = ClprBundleContent.newBuilder()
                .setMetadata(ClprQueueMetadata.newBuilder()
                        .setNextMessageId(2)
                        .setSentRunningHash(ByteString.copyFrom(ZERO_HASH))
                        .setReceivedMessageId(0)
                        .setStatus(ClprChannelStatus.CLOSED)
                        .build())
                .addMessages(ClprMessagePayload.newBuilder()
                        .setMessage(ClprMessage.newBuilder()
                                .setConnectorId(ByteString.copyFrom(new byte[32]))
                                .setTargetApplication(ByteString.copyFrom(new byte[20]))
                                .setSender(ByteString.copyFrom(new byte[20]))
                                .setMessageData(ByteString.copyFromUtf8("last"))
                                .build())
                        .build())
                .build();
        return hapiTest(
                overridingTwo("clpr.enabled", "true", "clpr.minLockedStake", "100"),
                clprUpdateLedgerConfiguration()
                        .configuration(defaultLedgerConfig())
                        .payingWith(GENESIS),
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
                        .configProofBytes(toConfigProofBytes(defaultLedgerConfig()))
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
                contractCall(
                                CLPR_CONTRACT,
                                "sendMessage",
                                crypto.channelId(),
                                crypto.connectorId(),
                                new byte[20],
                                new byte[] {1, 2, 3})
                        .gas(2_000_000L)
                        .payingWith("caller"),
                // Bundle with peer=CLOSED: local ACTIVE → CLOSING (an unacked data message keeps the channel from
                // DRAINED or CLOSED)
                clprSubmitBundle()
                        .channelId(crypto.channelId())
                        .bundlePayload(toBundleProofBytes(closedPeerBundle))
                        .endpointNodeId(0L)
                        .payingWith(GENESIS),
                // The local ledger should have initiated a closing transition; to verify, ensure an attempt to close
                // the channel fails
                clprCloseChannel()
                        .channelId(crypto.channelId())
                        .payingWith(GENESIS)
                        .hasKnownStatusFrom(CLPR_INVALID_CHANNEL_STATUS));
    }

    @LeakyHapiTest(requirement = PROPERTY_OVERRIDES)
    final Stream<DynamicTest> configPropagatedOnSubmitBundleAfterConfigUpdate() {
        final var crypto = new ClprChannelCrypto();
        // Empty bundle — no messages, just triggers lazy config propagation
        final var bundle = ClprBundleContent.newBuilder()
                .setMetadata(ClprQueueMetadata.newBuilder()
                        .setNextMessageId(2)
                        .setSentRunningHash(ByteString.copyFrom(ZERO_HASH))
                        .setReceivedMessageId(0)
                        .setStatus(ClprChannelStatus.ACTIVE)
                        .build())
                .addMessages(ClprMessagePayload.newBuilder()
                        .setMessage(ClprMessage.newBuilder()
                                .setConnectorId(ByteString.copyFrom(new byte[32]))
                                .setTargetApplication(ByteString.copyFrom(new byte[20]))
                                .setSender(ByteString.copyFrom(new byte[20]))
                                .setMessageData(ByteString.copyFromUtf8("hello"))
                                .build())
                        .build())
                .build();
        return hapiTest(
                overriding("clpr.enabled", "true"),
                // Initial config
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
                // Update config AFTER channel established — makes channel's
                // lastConfigTimestamp stale, triggering lazy propagation on next interaction
                clprUpdateLedgerConfiguration()
                        .configuration(defaultLedgerConfig())
                        .payingWith(GENESIS),
                // Submit bundle — should succeed and internally enqueue ConfigUpdate
                clprSubmitBundle()
                        .channelId(crypto.channelId())
                        .bundlePayload(toBundleProofBytes(bundle))
                        .endpointNodeId(0L)
                        .payingWith(GENESIS));
    }

    private static ClprLedgerConfiguration defaultLedgerConfig() {
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
                // Non-empty endpoints required by ClprCompleteChannelHandler (spec §5.1.3
                // step 5 — verified peer config must carry at least one endpoint; shape per
                // §1.1 / §1.2). The endpoint identity isn't exercised here — we're testing
                // the bundle path, not the peer.
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
