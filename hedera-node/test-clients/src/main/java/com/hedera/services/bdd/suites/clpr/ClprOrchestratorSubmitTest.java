// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.clpr;

import static com.hedera.node.app.hapi.utils.CommonPbjConverters.protoToPbj;
import static com.hedera.services.bdd.junit.ContextRequirement.PROPERTY_OVERRIDES;
import static com.hedera.services.bdd.junit.EmbeddedReason.NEEDS_STATE_ACCESS;
import static com.hedera.services.bdd.junit.TestTags.CLPR;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.clprCompleteChannel;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.clprCompleteConnector;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.clprRegisterChannel;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.clprRegisterConnector;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.clprUpdateLedgerConfiguration;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static java.util.Objects.requireNonNull;

import com.google.protobuf.ByteString;
import com.hedera.hapi.block.stream.MerklePath;
import com.hedera.hapi.block.stream.StateProof;
import com.hedera.hapi.node.state.clpr.ClprSyncPayload;
import com.hedera.hapi.platform.state.StateItem;
import com.hedera.hapi.platform.state.StateValue;
import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.junit.LeakyEmbeddedHapiTest;
import com.hederahashgraph.api.proto.java.ClprEndpoint;
import com.hederahashgraph.api.proto.java.ClprLedgerConfiguration;
import com.hederahashgraph.api.proto.java.ClprServiceEndpoint;
import com.hederahashgraph.api.proto.java.ClprSignatureScheme;
import com.hederahashgraph.api.proto.java.ClprThrottles;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;
import org.bouncycastle.jcajce.provider.digest.Keccak;
import org.bouncycastle.math.ec.rfc8032.Ed25519;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * Single-node embedded test that exercises the *receive* side of the CLPR sync
 * pipeline by invoking {@code ClprSyncWorkflow#handleSync} directly with a
 * {@code ClprSyncPayload} — exactly as the gRPC server does when a peer
 * endpoint calls {@code proto.ClprEndpointService/sync}.
 *
 * <p>This is the production code path: the inbound gRPC handler deserializes
 * a {@code ClprSyncPayload}, validates the channel, builds a response, and
 * fire-and-forget submits the inbound bundle as a {@code ClprSubmitBundle}
 * HAPI transaction via {@link com.hedera.node.app.spi.AppContext.Gossip#submit}
 * (i.e. {@link com.hedera.node.app.Hedera#submit}). Runs in embedded mode so
 * the receiving pipeline can be stepped through in IntelliJ.
 */
@Tag(CLPR)
public class ClprOrchestratorSubmitTest {

    private static final String VERIFIER_CONTRACT = "ClprPassThroughVerifier";
    private static final String CONNECTOR_CONTRACT = "PassThroughAuth";
    private static final long MIN_LOCKED_STAKE = 100L;

    @LeakyEmbeddedHapiTest(reason = NEEDS_STATE_ACCESS, requirement = PROPERTY_OVERRIDES)
    @DisplayName("Inbound gRPC sync reaches ClprSubmitBundleHandler")
    final Stream<DynamicTest> inboundSyncReachesHandler() {
        final var crypto = new Crypto();

        final var proofBytes = buildProofBytes();
        return hapiTest(
                overriding("clpr.enabled", "true"),
                overriding("clpr.minLockedStake", String.valueOf(MIN_LOCKED_STAKE)),
                // Fund the self node's account (account 3 in embedded) so the inbound
                // ClprSubmitBundle transaction — submitted by the node and paying with its
                // own account — can cover gas for the verifier contract dispatch.
                cryptoTransfer(tinyBarsFromTo(GENESIS, "3", 100_000_000_000L)),
                clprUpdateLedgerConfiguration()
                        .configuration(buildLedgerConfig())
                        .payingWith(GENESIS),
                uploadInitCode(VERIFIER_CONTRACT),
                contractCreate(VERIFIER_CONTRACT),
                uploadInitCode(CONNECTOR_CONTRACT),
                contractCreate(CONNECTOR_CONTRACT),
                clprRegisterChannel()
                        .ownershipCommitment(crypto.channelCommitment)
                        .payingWith(GENESIS),
                clprCompleteChannel()
                        .channelId(crypto.channelId)
                        .publicKey(crypto.publicKey)
                        .signature(crypto.channelSignature)
                        .signatureScheme(ClprSignatureScheme.ED25519)
                        .verifierContract(VERIFIER_CONTRACT)
                        .configProofBytes(proofBytes.toByteArray())
                        .payingWith(GENESIS),
                clprRegisterConnector().commitment(crypto.connectorCommitment).payingWith(GENESIS),
                clprCompleteConnector()
                        .connectorId(crypto.connectorId)
                        .publicKey(crypto.publicKey)
                        .signature(crypto.connectorSignature)
                        .signatureScheme(ClprSignatureScheme.ED25519)
                        .salt(crypto.connectorSalt)
                        .channelId(crypto.channelId)
                        .connectorContract(CONNECTOR_CONTRACT)
                        .adminKeyName(GENESIS)
                        .lockedStake(MIN_LOCKED_STAKE)
                        .payingWith(GENESIS),
                // Mimic a peer's gRPC sync call by invoking the workflow directly.
                withOpContext((spec, opLog) -> {
                    final var hedera = spec.embeddedHederaOrThrow().hedera();
                    final var workflow = requireNonNull(
                            hedera.clprSyncWorkflow(), "ClprSyncWorkflow not available — Hedera not started?");

                    // Build a ClprSyncPayload (PBJ form, same shape the gRPC server sees
                    // after deserializing the wire bytes from a peer endpoint).
                    final var syncPayload = ClprSyncPayload.newBuilder()
                            .channelId(Bytes.wrap(crypto.channelId))
                            .bundlePayload(proofBytes)
                            .build();
                    final var requestBytes = ClprSyncPayload.PROTOBUF.toBytes(syncPayload);
                    final var responseBuf = BufferedData.allocate(8192);

                    workflow.handleSync(requestBytes, responseBuf);

                    // Pump fake-platform time so the fire-and-forget ClprSubmitBundle
                    // submission can traverse consensus + handle.
                    for (int i = 0; i < 60; i++) {
                        spec.embeddedHederaOrThrow().tick(java.time.Duration.ofMillis(100));
                        Thread.sleep(20);
                    }
                    // Diagnosis cue:
                    // Run this test in IntelliJ in debug mode and set a breakpoint at the
                    // very first line of ClprSubmitBundleHandler.doHandle. If it does NOT
                    // fire, the failure is between Hedera.submit and dispatch; walk up
                    // through HandleWorkflow.handlePlatformTransaction →
                    // ParentTxnFactory.createTopLevelTxn → DispatchProcessor.processDispatch.
                }));
    }

    private static Bytes buildProofBytes() {
        final ClprLedgerConfiguration ledgerConfig = buildLedgerConfig();
        final var stateValue = StateValue.newBuilder()
                .clprServiceILedgerConfiguration(
                        protoToPbj(ledgerConfig, com.hedera.hapi.node.state.clpr.ClprLedgerConfiguration.class))
                .build();
        final var stateItem = StateItem.newBuilder().value(stateValue).build();
        final var leaf = StateItem.PROTOBUF.toBytes(stateItem);
        final var path = MerklePath.newBuilder().stateItemLeaf(leaf).build();
        final var proofBytes =
                StateProof.PROTOBUF.toBytes(StateProof.newBuilder().paths(path).build());
        return proofBytes;
    }

    private static ClprLedgerConfiguration buildLedgerConfig() {
        return ClprLedgerConfiguration.newBuilder()
                .setChainId("hiero:embedded")
                .setServiceAddress(ByteString.copyFrom(new byte[] {0, 0, 1}))
                .addEndpoints(ClprEndpoint.newBuilder()
                        .setServiceEndpoint(ClprServiceEndpoint.newBuilder()
                                .setIpAddress("127.0.0.1")
                                .setPort(50211)
                                .build())
                        .setTlsCertificate(ByteString.copyFrom(new byte[] {0x01}))
                        .build())
                .setThrottles(ClprThrottles.newBuilder()
                        .setMaxMessagesPerBundle(100)
                        .setMaxMessagePayloadBytes(65536)
                        .setMaxGasPerMessage(1_000_000L)
                        .setMaxQueueDepth(1000)
                        .setMaxSyncBytes(1_048_576L)
                        .build())
                .build();
    }

    private static final class Crypto {
        private static final byte[] TEST_PRIVATE_KEY = {
            0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
            0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f, 0x10,
            0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x18,
            0x19, 0x1a, 0x1b, 0x1c, 0x1d, 0x1e, 0x1f, 0x20
        };
        private static final byte[] CLPR_SERVICE_ADDRESS = {
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, (byte) 0x6e
        };

        final byte[] publicKey = new byte[32];
        final byte[] channelId;
        final byte[] channelCommitment;
        final byte[] channelSignature = new byte[Ed25519.SIGNATURE_SIZE];
        final byte[] connectorSalt = new byte[32];
        final byte[] connectorId;
        final byte[] connectorCommitment;
        final byte[] connectorSignature = new byte[Ed25519.SIGNATURE_SIZE];

        Crypto() {
            Ed25519.generatePublicKey(TEST_PRIVATE_KEY, 0, publicKey, 0);
            channelId = keccak256("orch-test-conn-v1".getBytes(StandardCharsets.UTF_8));
            channelCommitment = keccak256(concat(channelId, publicKey));
            final var connSig = keccak256(channelId);
            Ed25519.sign(TEST_PRIVATE_KEY, 0, connSig, 0, connSig.length, channelSignature, 0);
            connectorId = keccak256(concat(channelId, concat(publicKey, connectorSalt)));
            connectorCommitment = keccak256(concat(connectorId, publicKey));
            final var connectorSig = keccak256(concat(connectorId, CLPR_SERVICE_ADDRESS));
            Ed25519.sign(TEST_PRIVATE_KEY, 0, connectorSig, 0, connectorSig.length, connectorSignature, 0);
        }

        private static byte[] keccak256(final byte[] in) {
            return new Keccak.Digest256().digest(in);
        }

        private static byte[] concat(final byte[] a, final byte[] b) {
            final byte[] r = new byte[a.length + b.length];
            System.arraycopy(a, 0, r, 0, a.length);
            System.arraycopy(b, 0, r, a.length, b.length);
            return r;
        }
    }
}
