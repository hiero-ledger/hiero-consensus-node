// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.clpr;

import static com.hedera.services.bdd.junit.ContextRequirement.PROPERTY_OVERRIDES;
import static com.hedera.services.bdd.junit.TestTags.CLPR;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.clprCompleteChannel;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.clprRedactMessage;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.clprRegisterChannel;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.AUTHORIZATION_FAILED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CLPR_CHANNEL_NOT_FOUND;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CLPR_MESSAGE_NOT_FOUND;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CLPR_NOT_ENABLED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TRANSACTION_BODY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.NOT_SUPPORTED;

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
 * HAPI tests for CLPR message redaction (CLPR-2.5).
 */
@Tag(CLPR)
public class ClprRedactMessageSuite {

    private static final String VERIFIER_CONTRACT = "ClprPassThroughVerifier";

    @LeakyHapiTest(requirement = PROPERTY_OVERRIDES)
    final Stream<DynamicTest> rejectsRedactWhenDisabled() {
        return hapiTest(
                overriding("clpr.enabled", "false"),
                clprRedactMessage()
                        .channelId(new byte[32])
                        .messageId(1L)
                        .payingWith(GENESIS)
                        .hasPrecheck(CLPR_NOT_ENABLED));
    }

    @LeakyHapiTest(requirement = PROPERTY_OVERRIDES)
    final Stream<DynamicTest> nonAdminCannotRedactMessage() {
        return hapiTest(
                overriding("clpr.enabled", "true"),
                cryptoCreate("civilian").balance(ONE_HUNDRED_HBARS),
                clprRedactMessage()
                        .channelId(new byte[32])
                        .messageId(1L)
                        .payingWith("civilian")
                        .hasKnownStatusFrom(AUTHORIZATION_FAILED, NOT_SUPPORTED));
    }

    @LeakyHapiTest(requirement = PROPERTY_OVERRIDES)
    final Stream<DynamicTest> rejectsInvalidChannelIdLength() {
        return hapiTest(
                overriding("clpr.enabled", "true"),
                clprRedactMessage()
                        .channelId(new byte[16])
                        .messageId(1L)
                        .payingWith(GENESIS)
                        .hasPrecheck(INVALID_TRANSACTION_BODY));
    }

    @LeakyHapiTest(requirement = PROPERTY_OVERRIDES)
    final Stream<DynamicTest> rejectsZeroMessageId() {
        return hapiTest(
                overriding("clpr.enabled", "true"),
                clprRedactMessage().channelId(new byte[32]).payingWith(GENESIS).hasPrecheck(INVALID_TRANSACTION_BODY));
    }

    @LeakyHapiTest(requirement = PROPERTY_OVERRIDES)
    final Stream<DynamicTest> rejectsRedactOfNonexistentChannel() {
        return hapiTest(
                overriding("clpr.enabled", "true"),
                clprRedactMessage()
                        .channelId(new byte[32])
                        .messageId(1L)
                        .payingWith(GENESIS)
                        .hasKnownStatus(CLPR_CHANNEL_NOT_FOUND));
    }

    @LeakyHapiTest(requirement = PROPERTY_OVERRIDES)
    final Stream<DynamicTest> rejectsRedactOfMessageBeyondQueueRange() {
        final var crypto = new ClprChannelCrypto();
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
                // No messages sent yet, so message_id 1 is beyond queue range
                clprRedactMessage()
                        .channelId(crypto.channelId())
                        .messageId(1L)
                        .payingWith(GENESIS)
                        .hasKnownStatus(CLPR_MESSAGE_NOT_FOUND));
    }

    /**
     * Synthetic config proof for the passthrough verifier path; spec refs §3.1 (verifyConfig
     * contract) and §5.1.3 (completeChannel consumes the verifier output).
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
                // step 5 — verified peer config must carry at least one endpoint).
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
