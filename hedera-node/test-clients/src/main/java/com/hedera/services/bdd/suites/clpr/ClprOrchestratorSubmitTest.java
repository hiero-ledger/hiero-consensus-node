// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.clpr;

import static com.hedera.node.app.hapi.utils.CommonPbjConverters.protoToPbj;
import static com.hedera.node.app.service.clpr.impl.schemas.V0780ClprSchema.CHANNELS_STATE_ID;
import static com.hedera.services.bdd.junit.ContextRequirement.PROPERTY_OVERRIDES;
import static com.hedera.services.bdd.junit.EmbeddedReason.NEEDS_STATE_ACCESS;
import static com.hedera.services.bdd.junit.TestTags.CLPR;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.clprCompleteConnector;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.clprCreateChannel;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.clprRegisterConnector;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.clprUpdateLedgerConfiguration;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.clpr.HapiClprUpdateLedgerConfiguration.defaultThrottles;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.blockingOrder;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.clpr.ClprTestProofs.toBundleProofBytes;
import static com.hedera.services.bdd.suites.interledger.ClprTestHelpers.CLPR_SERVICE_ADDRESS_20;
import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.protobuf.ByteString;
import com.hedera.hapi.block.stream.MerklePath;
import com.hedera.hapi.block.stream.StateProof;
import com.hedera.hapi.node.state.clpr.ClprBundleRequest;
import com.hedera.hapi.node.state.clpr.ClprBundleResponse;
import com.hedera.hapi.node.state.clpr.ClprChannel;
import com.hedera.hapi.node.state.clpr.ClprStreamingSyncPayload;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.hapi.platform.state.StateItem;
import com.hedera.hapi.platform.state.StateValue;
import com.hedera.node.app.service.clpr.ClprService;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.junit.LeakyEmbeddedHapiTest;
import com.hederahashgraph.api.proto.java.ClprChannelStatus;
import com.hederahashgraph.api.proto.java.ClprLedgerConfiguration;
import com.hederahashgraph.api.proto.java.ClprMessage;
import com.hederahashgraph.api.proto.java.ClprMessagePayload;
import com.hederahashgraph.api.proto.java.ClprSignatureScheme;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * Single-node embedded test that exercises the *receive* side of the CLPR sync
 * pipeline by opening a {@code ClprSyncWorkflow#openStreamingSync} session and feeding it a
 * {@code ClprStreamingSyncPayload} — exactly as the gRPC server does when a peer
 * endpoint streams on {@code proto.ClprEndpointService/sync}.
 *
 * <p>This is the production code path: the session deserializes the
 * {@code ClprStreamingSyncPayload}, validates the channel, builds a reply, and
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
        return hapiTest(ClprChannelCrypto.withVerifier(
                VERIFIER_CONTRACT, "hiero:embedded", CLPR_SERVICE_ADDRESS_20, crypto -> {
                    final var proofBytes = buildProofBytes();
                    // A verified message referencing an unknown remote connector; the bundle itself is valid, so it
                    // passes
                    // validation before gossip and is applied by ClprSubmitBundleHandler (the message gets a
                    // CONNECTOR_NOT_FOUND
                    // reply)
                    final var probe = ClprMessagePayload.newBuilder()
                            .setMessage(ClprMessage.newBuilder()
                                    .setConnectorId(ByteString.copyFrom(new byte[32]))
                                    .setTargetApplication(ByteString.copyFrom(new byte[20]))
                                    .setSender(ByteString.copyFrom(new byte[20]))
                                    .setMessageData(ByteString.copyFromUtf8("probe")))
                            .build();
                    final var bundleBytes = Bytes.wrap(
                            toBundleProofBytes(ClprChannelStatus.ACTIVE, 0L, 0L, new byte[32], List.of(probe)));
                    return blockingOrder(
                            overriding("clpr.minLockedStake", String.valueOf(MIN_LOCKED_STAKE)),
                            // Fund the self node's account (account 3 in embedded) so the inbound
                            // ClprSubmitBundle transaction — submitted by the node and paying with its
                            // own account — can cover gas for the verifier contract dispatch.
                            cryptoTransfer(tinyBarsFromTo(GENESIS, "3", 100_000_000_000L)),
                            clprUpdateLedgerConfiguration()
                                    .configuration(buildLedgerConfig())
                                    .payingWith(GENESIS),
                            uploadInitCode(CONNECTOR_CONTRACT),
                            contractCreate(CONNECTOR_CONTRACT),
                            clprCreateChannel()
                                    .channelId(crypto.channelId())
                                    .salt(crypto.salt())
                                    .peerVerifier(crypto.peerVerifier())
                                    .verifierContract(VERIFIER_CONTRACT)
                                    .configProofBytes(proofBytes.toByteArray())
                                    .payingWith(GENESIS),
                            clprRegisterConnector()
                                    .channelId(crypto.channelId())
                                    .commitment(crypto.connectorCommitment())
                                    .payingWith(GENESIS),
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
                            // Mimic a peer's gRPC sync call by invoking the workflow directly.
                            withOpContext((spec, opLog) -> {
                                final var hedera = spec.embeddedHederaOrThrow().hedera();
                                final var workflow = requireNonNull(
                                        hedera.clprSyncWorkflow(),
                                        "ClprSyncWorkflow not available — Hedera not started?");

                                // The peer's opening message: its one-shot request plus its bundle (PBJ form, same
                                // shape the session sees after the gRPC server deserializes the wire bytes).
                                final var syncPayload = ClprStreamingSyncPayload.newBuilder()
                                        .channelId(Bytes.wrap(crypto.channelId()))
                                        .bundleRequest(ClprBundleRequest.DEFAULT)
                                        .bundleResponse(ClprBundleResponse.newBuilder()
                                                .bundlePayload(bundleBytes)
                                                .build())
                                        .build();

                                workflow.openStreamingSync()
                                        .onMessage(ClprStreamingSyncPayload.PROTOBUF.toBytes(syncPayload));

                                // Pump fake-platform time so the fire-and-forget ClprSubmitBundle
                                // submission can traverse consensus + handle.
                                for (int i = 0; i < 60; i++) {
                                    spec.embeddedHederaOrThrow().tick(java.time.Duration.ofMillis(100));
                                    Thread.sleep(20);
                                }
                                // The handler applied the bundle: the channel has received its one message
                                final var channel = spec.embeddedStateOrThrow()
                                        .getReadableStates(ClprService.NAME)
                                        .<ProtoBytes, ClprChannel>get(CHANNELS_STATE_ID)
                                        .get(new ProtoBytes(Bytes.wrap(crypto.channelId())));
                                assertEquals(1L, requireNonNull(channel).receivedMessageId());
                                // Diagnosis cue:
                                // Run this test in IntelliJ in debug mode and set a breakpoint at the
                                // very first line of ClprSubmitBundleHandler.doHandle. If it does NOT
                                // fire, the failure is between Hedera.submit and dispatch; walk up
                                // through HandleWorkflow.handlePlatformTransaction →
                                // ParentTxnFactory.createTopLevelTxn → DispatchProcessor.processDispatch.
                            }));
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
                .setServiceAddress(ByteString.copyFrom(CLPR_SERVICE_ADDRESS_20))
                .setThrottles(defaultThrottles())
                .build();
    }
}
