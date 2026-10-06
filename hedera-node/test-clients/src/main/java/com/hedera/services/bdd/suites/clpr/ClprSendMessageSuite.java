// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.clpr;

import static com.hedera.services.bdd.junit.ContextRequirement.PROPERTY_OVERRIDES;
import static com.hedera.services.bdd.junit.TestTags.CLPR;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.clprCompleteChannel;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.clprCompleteConnector;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.clprRegisterChannel;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.clprRegisterConnector;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.clprUpdateLedgerConfiguration;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overridingTwo;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.clpr.ClprTestProofs.toConfigProofBytes;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hederahashgraph.api.proto.java.ClprEndpoint;
import com.hederahashgraph.api.proto.java.ClprLedgerConfiguration;
import com.hederahashgraph.api.proto.java.ClprServiceEndpoint;
import com.hederahashgraph.api.proto.java.ClprSignatureScheme;
import com.hederahashgraph.api.proto.java.ClprThrottles;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * HAPI tests for CLPR sendMessage system contract method (CLPR-2.2).
 *
 * <p>These tests exercise the full flow: deploy a Solidity wrapper contract that calls the
 * CLPR system contract at address 0x16e, validate that messages are enqueued on active
 * channels with registered connectors, and verify error cases.
 */
@Tag(CLPR)
public class ClprSendMessageSuite {

    private static final String CLPR_CONTRACT = "ClprSystemContract";
    private static final String VERIFIER_CONTRACT = "ClprPassThroughVerifier";
    private static final String CONNECTOR_CONTRACT = "PassThroughAuth";
    private static final String SEND_MESSAGE = "sendMessage";
    private static final long GAS_TO_OFFER = 500_000L;
    private static final long MIN_LOCKED_STAKE = 100L;

    @LeakyHapiTest(requirement = PROPERTY_OVERRIDES)
    final Stream<DynamicTest> sendMessageOnActiveChannelSucceeds() {
        final var crypto = new ClprChannelCrypto();
        return hapiTest(
                overridingTwo("clpr.enabled", "true", "clpr.minLockedStake", String.valueOf(MIN_LOCKED_STAKE)),
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
                // §5.1 commit-reveal channel setup
                clprRegisterChannel().ownershipCommitment(crypto.commitment()).payingWith(GENESIS),
                clprCompleteChannel()
                        .channelId(crypto.channelId())
                        .publicKey(crypto.publicKey())
                        .signature(crypto.signature())
                        .signatureScheme(ClprSignatureScheme.ED25519)
                        .verifierContract(VERIFIER_CONTRACT)
                        .configProofBytes(toConfigProofBytes(defaultLedgerConfig()))
                        .payingWith(GENESIS),
                // §6.3 commit-reveal connector setup — the actual proto API migration.
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
                        .lockedStake(MIN_LOCKED_STAKE)
                        .payingWith(GENESIS),
                // sendMessage(channelId, connectorId, target, data) — connectorId is the
                // 32-byte keccak hash from ChannelCrypto (spec §6.3 deriveConnectorId).
                contractCall(
                                CLPR_CONTRACT,
                                SEND_MESSAGE,
                                crypto.channelId(),
                                crypto.connectorId(),
                                new byte[] {10, 20, 30},
                                new byte[] {1, 2, 3, 4, 5})
                        .gas(GAS_TO_OFFER)
                        .payingWith("caller")
                        .via("sendMsgTxn"),
                getTxnRecord("sendMsgTxn").logged());
    }

    @LeakyHapiTest(requirement = PROPERTY_OVERRIDES)
    final Stream<DynamicTest> sendMessageWithNoChannelReverts() {
        return hapiTest(
                overriding("clpr.enabled", "true"),
                uploadInitCode(CLPR_CONTRACT),
                contractCreate(CLPR_CONTRACT),
                cryptoCreate("caller").balance(ONE_HUNDRED_HBARS),
                // Call sendMessage with a nonexistent channel — should revert
                contractCall(
                                CLPR_CONTRACT,
                                SEND_MESSAGE,
                                new byte[32],
                                new byte[32],
                                new byte[] {10, 20, 30},
                                new byte[] {1, 2, 3})
                        .gas(GAS_TO_OFFER)
                        .payingWith("caller")
                        .hasKnownStatus(CONTRACT_REVERT_EXECUTED));
    }

    @LeakyHapiTest(requirement = PROPERTY_OVERRIDES)
    final Stream<DynamicTest> sendMessageWhenDisabledReverts() {
        return hapiTest(
                overriding("clpr.enabled", "false"),
                uploadInitCode(CLPR_CONTRACT),
                contractCreate(CLPR_CONTRACT),
                cryptoCreate("caller").balance(ONE_HUNDRED_HBARS),
                contractCall(CLPR_CONTRACT, SEND_MESSAGE, new byte[32], new byte[32], new byte[0], new byte[0])
                        .gas(GAS_TO_OFFER)
                        .payingWith("caller")
                        .hasKnownStatus(CONTRACT_REVERT_EXECUTED));
    }

    @LeakyHapiTest(requirement = PROPERTY_OVERRIDES)
    final Stream<DynamicTest> sendMessageWithMissingConnectorReverts() {
        final var crypto = new ClprChannelCrypto();
        return hapiTest(
                overriding("clpr.enabled", "true"),
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
                // Create ACTIVE channel but do NOT register any connector
                clprRegisterChannel().ownershipCommitment(crypto.commitment()).payingWith(GENESIS),
                clprCompleteChannel()
                        .channelId(crypto.channelId())
                        .publicKey(crypto.publicKey())
                        .signature(crypto.signature())
                        .signatureScheme(ClprSignatureScheme.ED25519)
                        .verifierContract(VERIFIER_CONTRACT)
                        .configProofBytes(toConfigProofBytes(defaultLedgerConfig()))
                        .payingWith(GENESIS),
                // Send message with unregistered connector — should revert
                contractCall(
                                CLPR_CONTRACT,
                                SEND_MESSAGE,
                                crypto.channelId(),
                                new byte[32],
                                new byte[] {10, 20, 30},
                                new byte[] {1, 2, 3})
                        .gas(GAS_TO_OFFER)
                        .payingWith("caller")
                        .hasKnownStatus(CONTRACT_REVERT_EXECUTED));
    }

    // ---- Helpers ----a

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
                // step 5 — verified peer config must include at least one endpoint; shape per
                // §1.1 / §1.2).
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
