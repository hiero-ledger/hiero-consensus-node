// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.clpr;

import static com.hedera.node.app.hapi.utils.CommonPbjConverters.protoToPbj;
import static com.hedera.node.app.hapi.utils.CommonPbjConverters.toPbj;
import static com.hedera.node.app.service.clpr.impl.schemas.V0770ClprSchema.CHANNELS_STATE_ID;
import static com.hedera.node.app.service.clpr.impl.schemas.V0770ClprSchema.CONNECTORS_STATE_ID;
import static com.hedera.node.app.service.clpr.impl.schemas.V0770ClprSchema.ENDPOINT_MANIFEST_CONSTRUCTION_STATE_ID;
import static com.hedera.node.app.service.clpr.impl.schemas.V0770ClprSchema.ENDPOINT_MANIFEST_STATE_ID;
import static com.hedera.node.app.service.clpr.impl.schemas.V0770ClprSchema.LEDGER_CONFIGURATION_STATE_ID;
import static com.hedera.services.bdd.junit.EmbeddedReason.MUST_SKIP_INGEST;
import static com.hedera.services.bdd.junit.EmbeddedReason.NEEDS_STATE_ACCESS;
import static com.hedera.services.bdd.junit.TestTags.CLPR;
import static com.hedera.services.bdd.junit.TestTags.ONLY_SUBPROCESS;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.clprGetEndpointManifest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.clprGetLedgerConfiguration;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.clprCloseChannel;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.clprCompleteChannel;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.clprCompleteConnector;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.clprDeregisterConnector;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.clprRedactMessage;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.clprRegisterChannel;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.clprRegisterConnector;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.clprSubmitBundle;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.clprUpdateLedgerConfiguration;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCallWithFunctionAbi;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.clpr.ClprTestProofs.VERIFY_CONFIG_WITH_SEED_ENDPOINTS;
import static com.hedera.services.bdd.suites.clpr.ClprTestProofs.toBundleProofBytes;
import static com.hedera.services.bdd.suites.clpr.ClprTestProofs.toConfigProofBytes;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CLPR_NOT_ENABLED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INNER_TRANSACTION_FAILED;
import static java.util.stream.Collectors.toSet;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.ByteString;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.clpr.ClprChannel;
import com.hedera.hapi.node.state.clpr.ClprConnector;
import com.hedera.hapi.node.state.clpr.ClprConnectorKey;
import com.hedera.hapi.node.state.clpr.ClprEndpointManifest;
import com.hedera.hapi.node.state.clpr.ClprEndpointManifestConstruction;
import com.hedera.hapi.node.state.clpr.ClprEndpointPublication;
import com.hedera.hapi.node.state.clpr.ClprEndpointPublicationEntry;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.hapi.services.auxiliary.clpr.legacy.ClprEndpointPublicationTransactionBody;
import com.hedera.node.app.service.clpr.ClprEndpointServiceDefinition;
import com.hedera.node.app.service.clpr.ClprService;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyEmbeddedHapiTest;
import com.hedera.services.bdd.junit.OrderedInIsolation;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.transactions.HapiTxnOp;
import com.hedera.services.bdd.spec.transactions.clpr.HapiClprRegisterChannel;
import com.hedera.services.bdd.spec.transactions.clpr.HapiClprUpdateLedgerConfiguration;
import com.hederahashgraph.api.proto.java.ClprChannelStatus;
import com.hederahashgraph.api.proto.java.ClprEndpoint;
import com.hederahashgraph.api.proto.java.ClprLedgerConfiguration;
import com.hederahashgraph.api.proto.java.ClprMessage;
import com.hederahashgraph.api.proto.java.ClprMessagePayload;
import com.hederahashgraph.api.proto.java.ClprServiceEndpoint;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.service.proto.java.ClprServiceGrpc;
import io.grpc.CallOptions;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.StreamObserver;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;

/**
 * Verifies that the entry points covered by {@link ClprEnabledSuite} reject CLPR work when disabled.
 *
 * <p>The master flag is set once in {@link #beforeAll(TestLifecycle)} and restored after the class.
 * Assertions always describe disabled behavior. Run with {@code -PsysProp.clpr.test.enabled=true}
 * to verify that these rejection assertions fail when the feature is enabled.
 */
@Tag(CLPR)
@OrderedInIsolation
@HapiTestLifecycle
public class ClprDisabledSuite {
    private static final String BATCH_OPERATOR = "batchOperator";
    private static final String CALLER = "caller";
    private static final String CLPR_CONTRACT = "ClprSystemContract";
    private static final String VERIFIER_CONTRACT = "ClprPassThroughVerifier";
    private static final String CONNECTOR_CONTRACT = "PassThroughAuth";
    private static final long GAS_TO_OFFER = 500_000L;
    private static final String INGEST_BYPASS_NODE = "4";
    private static final EnumSet<HederaFunctionality> NODE_INTERNAL_CLPR_FUNCTIONS =
            EnumSet.of(HederaFunctionality.ClprEndpointPublication);
    private static final List<String> CLPR_SYSTEM_CONTRACT_NUMS =
            List.of(String.valueOf(0x16eL), String.valueOf(0x16fL), String.valueOf(0x170L), String.valueOf(0x171L));

    @BeforeAll
    static void beforeAll(final TestLifecycle lifecycle) {
        lifecycle.overrideInClass(Map.of(
                "clpr.enabled",
                System.getProperty("clpr.test.enabled", "false"),
                "clpr.endpointManifestEnabled",
                "false"));
    }

    @HapiTest
    @DisplayName("Every public CLPR transaction is rejected at ingest when disabled")
    final Stream<DynamicTest> allClprTransactionsAreRejectedAtIngest() {
        return exerciseTransactions(false, false);
    }

    @HapiTest
    @DisplayName("All ten CLPR transaction types are rejected at ingest inside separate atomic batches")
    final Stream<DynamicTest> allClprTransactionsInBatchesAreRejectedAtIngest() {
        return exerciseTransactions(true, false);
    }

    @LeakyEmbeddedHapiTest(reason = {MUST_SKIP_INGEST, NEEDS_STATE_ACCESS})
    @DisplayName("Every CLPR transaction is rejected at consensus when ingest is bypassed")
    final Stream<DynamicTest> allClprTransactionsAreRejectedAtConsensus() {
        return exerciseTransactions(false, true);
    }

    @LeakyEmbeddedHapiTest(reason = {MUST_SKIP_INGEST, NEEDS_STATE_ACCESS})
    @DisplayName("All ten CLPR transaction types fail in batches at consensus when ingest is bypassed")
    final Stream<DynamicTest> allClprTransactionsInBatchesAreRejectedAtConsensus() {
        return exerciseTransactions(true, true);
    }

    @HapiTest
    @DisplayName("Both CLPR queries reject COST_ANSWER and ANSWER_ONLY when disabled")
    final Stream<DynamicTest> clprQueriesAreRejected() {
        return hapiTest(
                clprGetLedgerConfiguration().payingWith(GENESIS).hasCostAnswerPrecheck(CLPR_NOT_ENABLED),
                clprGetEndpointManifest().payingWith(GENESIS).hasCostAnswerPrecheck(CLPR_NOT_ENABLED),
                clprGetLedgerConfiguration()
                        .payingWith(GENESIS)
                        .nodePayment(1L)
                        .hasAnswerOnlyPrecheck(CLPR_NOT_ENABLED),
                clprGetEndpointManifest().payingWith(GENESIS).nodePayment(1L).hasAnswerOnlyPrecheck(CLPR_NOT_ENABLED));
    }

    @HapiTest
    @DisplayName("Every native CLPR contract halts with CLPR_NOT_ENABLED when disabled")
    final Stream<DynamicTest> nativeClprContractsAreRejected() {
        // A direct call exposes the native halt reason, rather than a wrapper's generic revert.
        // Enabled execution reaches selector/proof validation and cannot satisfy this assertion.
        return hapiTest(CLPR_SYSTEM_CONTRACT_NUMS.stream()
                .map(num -> contractCallWithFunctionAbi(
                                num, VERIFY_CONFIG_WITH_SEED_ENDPOINTS.toJson(false), new byte[] {1}, new byte[32])
                        .payingWith(GENESIS)
                        .gas(GAS_TO_OFFER)
                        .refusingEthConversion()
                        .hasKnownStatus(CLPR_NOT_ENABLED))
                .toArray(SpecOperation[]::new));
    }

    @LeakyEmbeddedHapiTest(reason = NEEDS_STATE_ACCESS)
    @DisplayName("A deployed contract cannot read an existing channel or send a message when CLPR is disabled")
    final Stream<DynamicTest> clprRouterCallsFromContractAreRejected() {
        final var crypto = new ClprChannelCrypto();
        final var operations = contractSetup();
        // Seed state without enabling CLPR. An unknown channel would revert even with CLPR on,
        // hiding a missing flag check. This existing channel's read succeeds in an enabled run.
        operations.add(withClprState(
                crypto,
                contractCall(CLPR_CONTRACT, "getChannelQueueState", (Object) crypto.channelId())
                        .payingWith(CALLER)
                        .gas(GAS_TO_OFFER)
                        .hasKnownStatus(CONTRACT_REVERT_EXECUTED),
                sendMessage(crypto)));
        return hapiTest(operations.toArray(SpecOperation[]::new));
    }

    @HapiTest
    @DisplayName("The internal endpoint-publication wire body is rejected at ingest when disabled")
    final Stream<DynamicTest> internalEndpointPublicationIsRejectedAtIngest() {
        return hapiTest(endpointPublicationProbe().payingWith(GENESIS).hasPrecheck(CLPR_NOT_ENABLED));
    }

    @Tag(ONLY_SUBPROCESS)
    @HapiTest
    @DisplayName("Every node rejects peer sync, discovery and streaming while CLPR is disabled")
    final Stream<DynamicTest> peerRpcCallsAreRejected() {
        return hapiTest(withOpContext((spec, opLog) -> {
            for (final var node : spec.targetNetworkOrThrow().nodes()) {
                final var channel = NettyChannelBuilder.forAddress(node.getHost(), node.getGrpcPort())
                        .usePlaintext()
                        .build();
                try {
                    for (final var method : List.of("sync", "discoverEndpoints")) {
                        final var error = assertThrows(
                                StatusRuntimeException.class,
                                () -> ClientCalls.blockingUnaryCall(
                                        channel,
                                        peerMethod(method, MethodDescriptor.MethodType.UNARY),
                                        CallOptions.DEFAULT.withDeadlineAfter(5, TimeUnit.SECONDS),
                                        new byte[0]));
                        assertPeerServiceDisabled(error.getStatus());
                    }
                    final var status = new CompletableFuture<Status>();
                    final var stream = ClientCalls.asyncBidiStreamingCall(
                            channel.newCall(
                                    peerMethod("streamingSync", MethodDescriptor.MethodType.BIDI_STREAMING),
                                    CallOptions.DEFAULT.withDeadlineAfter(5, TimeUnit.SECONDS)),
                            new StreamObserver<byte[]>() {
                                @Override
                                public void onNext(final byte[] response) {
                                    status.completeExceptionally(
                                            new AssertionError("Disabled CLPR peer service must reject requests"));
                                }

                                @Override
                                public void onError(final Throwable error) {
                                    status.complete(Status.fromThrowable(error));
                                }

                                @Override
                                public void onCompleted() {
                                    status.complete(Status.OK);
                                }
                            });
                    stream.onNext(new byte[0]);
                    stream.onCompleted();
                    assertPeerServiceDisabled(status.get(10, TimeUnit.SECONDS));
                } finally {
                    channel.shutdownNow();
                    assertTrue(channel.awaitTermination(5, TimeUnit.SECONDS));
                }
            }
        }));
    }

    @Nested
    @DisplayName("Endpoint manifest lifecycle")
    class ManifestTests {
        @BeforeAll
        static void enableManifestLifecycle(final TestLifecycle lifecycle) {
            // Only the manifest sub-feature changes for this group; the master flag is inherited.
            lifecycle.overrideInClass(Map.of("clpr.endpointManifestEnabled", "true"));
        }

        @LeakyEmbeddedHapiTest(reason = {MUST_SKIP_INGEST, NEEDS_STATE_ACCESS})
        @DisplayName("The manifest sub-feature cannot admit a publication while CLPR is disabled")
        final Stream<DynamicTest> publicationIsRejectedAtConsensus() {
            return hapiTest(withOpContext((spec, opLog) -> {
                final var beforeManifest = manifest(spec);
                final var beforeConstruction = construction(spec);
                final var pending = ClprEndpointManifestConstruction.newBuilder()
                        .constructionId(beforeManifest.version() + 1)
                        .targetNodeIds(List.of(0L, 1L, 2L, 3L))
                        .gracePeriodEndTime(
                                Timestamp.newBuilder().seconds(4_102_444_800L).build())
                        .build();
                putManifestState(spec, beforeManifest, pending);
                try {
                    allRunFor(
                            spec,
                            endpointPublicationProbe()
                                    .payingWith(GENESIS)
                                    .setNode(INGEST_BYPASS_NODE)
                                    .hasKnownStatus(CLPR_NOT_ENABLED));
                    assertEquals(beforeManifest, manifest(spec), "Disabled CLPR must not publish a manifest");
                    assertEquals(pending, construction(spec), "Disabled CLPR must not admit the publication");
                } finally {
                    putManifestState(spec, beforeManifest, beforeConstruction);
                }
            }));
        }

        @LeakyEmbeddedHapiTest(reason = NEEDS_STATE_ACCESS)
        @DisplayName("Consensus cannot finalize an expired manifest construction while CLPR is disabled")
        final Stream<DynamicTest> consensusCannotFinalizeManifest() {
            return hapiTest(withOpContext((spec, opLog) -> {
                final var beforeManifest = manifest(spec);
                final var beforeConstruction = construction(spec);
                final var endpoint = com.hedera.hapi.node.state.clpr.ClprEndpoint.newBuilder()
                        .serviceEndpoint(com.hedera.hapi.node.state.clpr.ClprServiceEndpoint.newBuilder()
                                .ipAddress("127.0.0.1")
                                .port(50211)
                                .build())
                        .build();
                final var pending = ClprEndpointManifestConstruction.newBuilder()
                        .constructionId(beforeManifest.version() + 1)
                        .targetNodeIds(List.of(0L))
                        .gatheredPublications(List.of(ClprEndpointPublicationEntry.newBuilder()
                                .nodeId(0L)
                                .publication(ClprEndpointPublication.newBuilder()
                                        .endpoint(endpoint)
                                        .build())
                                .build()))
                        .gracePeriodEndTime(Timestamp.DEFAULT)
                        .graceExtensionsUsed(Integer.MAX_VALUE)
                        .build();
                putManifestState(spec, beforeManifest, pending);
                try {
                    allRunFor(
                            spec,
                            cryptoTransfer(tinyBarsFromTo(GENESIS, "98", 1L)).payingWith(GENESIS));
                    assertEquals(beforeManifest, manifest(spec), "Disabled CLPR must not publish a manifest");
                    assertEquals(pending, construction(spec), "Disabled CLPR must not advance construction");
                } finally {
                    putManifestState(spec, beforeManifest, beforeConstruction);
                }
            }));
        }
    }

    private static Stream<DynamicTest> exerciseTransactions(final boolean inBatch, final boolean bypassIngest) {
        final var crypto = new ClprChannelCrypto();
        final var txns = clprTxns(crypto);
        final var operations = contractSetup();
        operations.add(withOpContext((spec, opLog) -> assertCoversEveryClprEntryPoint(txns)));
        operations.add(cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS));
        final var probes = new ArrayList<SpecOperation>();
        txns.values().forEach(op -> probes.add(dispatch(op, inBatch, bypassIngest)));
        probes.add(dispatch(endpointPublicationProbe(), inBatch, bypassIngest));
        if (bypassIngest) {
            // Pre-handle still runs when ingest is bypassed. Deregistration needs an existing
            // connector and its admin key before the handler's master flag check can execute.
            operations.add(withClprState(crypto, probes.toArray(SpecOperation[]::new)));
        } else {
            operations.addAll(probes);
        }
        return hapiTest(operations.toArray(SpecOperation[]::new));
    }

    private static HapiTxnOp<?> dispatch(final HapiTxnOp<?> op, final boolean inBatch, final boolean bypassIngest) {
        op.payingWith(GENESIS);
        if (bypassIngest) {
            op.hasKnownStatus(CLPR_NOT_ENABLED);
        }
        // Each type gets its own batch: a rejection of the first inner operation must not hide
        // missing guards on the remaining CLPR transaction types.
        final HapiTxnOp<?> submitted =
                inBatch ? atomicBatch(op.batchKey(BATCH_OPERATOR)).payingWith(BATCH_OPERATOR) : op;
        if (bypassIngest) {
            submitted.setNode(INGEST_BYPASS_NODE).hasKnownStatus(inBatch ? INNER_TRANSACTION_FAILED : CLPR_NOT_ENABLED);
        } else {
            submitted.hasPrecheck(CLPR_NOT_ENABLED);
        }
        return submitted;
    }

    private static SpecOperation withClprState(final ClprChannelCrypto crypto, final SpecOperation... operations) {
        return withOpContext((spec, opLog) -> {
            final var channelKey = new ProtoBytes(Bytes.wrap(crypto.channelId()));
            final var connectorKey = new ClprConnectorKey(channelKey.value(), Bytes.wrap(crypto.connectorId()));
            final var states = spec.embeddedStateOrThrow().getWritableStates(ClprService.NAME);
            final var channels = states.<ProtoBytes, ClprChannel>get(CHANNELS_STATE_ID);
            final var connectors = states.<ClprConnectorKey, ClprConnector>get(CONNECTORS_STATE_ID);
            final var configurations = states.<com.hedera.hapi.node.state.clpr.ClprLedgerConfiguration>getSingleton(
                    LEDGER_CONFIGURATION_STATE_ID);
            final var previousChannel = channels.get(channelKey);
            final var previousConnector = connectors.get(connectorKey);
            final var previousConfiguration = configurations.get();
            final var configuration =
                    protoToPbj(ledgerConfig(), com.hedera.hapi.node.state.clpr.ClprLedgerConfiguration.class);
            final var channel = ClprChannel.newBuilder()
                    .channelId(channelKey.value())
                    .status(com.hedera.hapi.node.state.clpr.ClprChannelStatus.ACTIVE)
                    .verifierContract(toPbj(spec.registry().getContractId(VERIFIER_CONTRACT)))
                    .nextMessageId(1L)
                    .sentRunningHash(Bytes.wrap(new byte[32]))
                    .receivedRunningHash(Bytes.wrap(new byte[32]))
                    .lastConfigTimestamp(configuration.timestamp())
                    .peerThrottles(configuration.throttles())
                    .build();
            final var connector = ClprConnector.newBuilder()
                    .channelId(connectorKey.channelId())
                    .connectorId(connectorKey.connectorId())
                    .connectorContract(toPbj(spec.registry().getContractId(CONNECTOR_CONTRACT)))
                    .adminKey(toPbj(spec.registry().getKey(GENESIS)))
                    .build();
            channels.put(channelKey, channel);
            connectors.put(connectorKey, connector);
            configurations.put(configuration);
            spec.commitEmbeddedState();
            try {
                allRunFor(spec, operations);
                final var after = spec.embeddedStateOrThrow().getReadableStates(ClprService.NAME);
                assertEquals(
                        channel,
                        after.<ProtoBytes, ClprChannel>get(CHANNELS_STATE_ID).get(channelKey));
                assertEquals(
                        connector,
                        after.<ClprConnectorKey, ClprConnector>get(CONNECTORS_STATE_ID)
                                .get(connectorKey));
                assertEquals(
                        configuration,
                        after.<com.hedera.hapi.node.state.clpr.ClprLedgerConfiguration>getSingleton(
                                        LEDGER_CONFIGURATION_STATE_ID)
                                .get());
            } finally {
                final var restored = spec.embeddedStateOrThrow().getWritableStates(ClprService.NAME);
                final var restoredChannels = restored.<ProtoBytes, ClprChannel>get(CHANNELS_STATE_ID);
                final var restoredConnectors = restored.<ClprConnectorKey, ClprConnector>get(CONNECTORS_STATE_ID);
                if (previousChannel == null) {
                    restoredChannels.remove(channelKey);
                } else {
                    restoredChannels.put(channelKey, previousChannel);
                }
                if (previousConnector == null) {
                    restoredConnectors.remove(connectorKey);
                } else {
                    restoredConnectors.put(connectorKey, previousConnector);
                }
                restored.<com.hedera.hapi.node.state.clpr.ClprLedgerConfiguration>getSingleton(
                                LEDGER_CONFIGURATION_STATE_ID)
                        .put(previousConfiguration);
                spec.commitEmbeddedState();
            }
        });
    }

    private static ArrayList<SpecOperation> contractSetup() {
        return new ArrayList<>(List.of(
                uploadInitCode(VERIFIER_CONTRACT, CONNECTOR_CONTRACT, CLPR_CONTRACT),
                contractCreate(VERIFIER_CONTRACT),
                contractCreate(CONNECTOR_CONTRACT),
                contractCreate(CLPR_CONTRACT),
                cryptoCreate(CALLER).balance(ONE_HUNDRED_HBARS)));
    }

    private static HapiTxnOp<?> sendMessage(final ClprChannelCrypto crypto) {
        return contractCall(
                        CLPR_CONTRACT,
                        "sendMessage",
                        crypto.channelId(),
                        crypto.connectorId(),
                        new byte[20],
                        new byte[] {1, 2, 3})
                .payingWith(CALLER)
                .gas(GAS_TO_OFFER)
                .hasKnownStatus(CONTRACT_REVERT_EXECUTED);
    }

    private static LinkedHashMap<String, HapiTxnOp<?>> clprTxns(final ClprChannelCrypto crypto) {
        final var txns = new LinkedHashMap<String, HapiTxnOp<?>>();
        txns.put("updateLedgerConfiguration", clprUpdateLedgerConfiguration().configuration(ledgerConfig()));
        txns.put("registerChannel", clprRegisterChannel().ownershipCommitment(crypto.commitment()));
        txns.put(
                "completeChannel",
                clprCompleteChannel()
                        .channelId(crypto.channelId())
                        .publicKey(crypto.publicKey())
                        .signature(crypto.signature())
                        .verifierContract(VERIFIER_CONTRACT)
                        .configProofBytes(toConfigProofBytes(ledgerConfig())));
        txns.put("registerConnector", clprRegisterConnector().commitment(crypto.connectorCommitment()));
        txns.put(
                "completeConnector",
                clprCompleteConnector()
                        .connectorId(crypto.connectorId())
                        .publicKey(crypto.publicKey())
                        .signature(crypto.connectorSignature())
                        .salt(crypto.connectorSalt())
                        .channelId(crypto.channelId())
                        .connectorContract(CONNECTOR_CONTRACT)
                        .adminKeyName(GENESIS)
                        .lockedStake(100_000_000L));
        txns.put(
                "deregisterConnector",
                clprDeregisterConnector()
                        .channelId(crypto.channelId())
                        .connectorId(crypto.connectorId())
                        .adminKey(GENESIS)
                        .stakeRecipient(GENESIS));
        txns.put(
                "redactMessage",
                clprRedactMessage().channelId(crypto.channelId()).messageId(1L));
        // A verified message referencing an unknown remote connector produces CONNECTOR_NOT_FOUND
        // in the outbound reply. The bundle itself must still be accepted successfully.
        final var payload = ClprMessagePayload.newBuilder()
                .setMessage(ClprMessage.newBuilder()
                        .setConnectorId(ByteString.copyFrom(new byte[32]))
                        .setTargetApplication(ByteString.copyFrom(new byte[20]))
                        .setSender(ByteString.copyFrom(new byte[20]))
                        .setMessageData(ByteString.copyFromUtf8("probe")))
                .build();
        txns.put(
                "submitBundle",
                clprSubmitBundle()
                        .channelId(crypto.channelId())
                        .endpointNodeId(0L)
                        .bundlePayload(
                                toBundleProofBytes(ClprChannelStatus.ACTIVE, 0L, 0L, new byte[32], List.of(payload))));
        txns.put("closeChannel", clprCloseChannel().channelId(crypto.channelId()));
        return txns;
    }

    private static ClprLedgerConfiguration ledgerConfig() {
        return ClprLedgerConfiguration.newBuilder()
                .setChainId("hiero:testing")
                .setServiceAddress(ByteString.copyFrom(new byte[] {0, 0, 1}))
                .setThrottles(HapiClprUpdateLedgerConfiguration.defaultThrottles())
                .addEndpoints(ClprEndpoint.newBuilder()
                        .setServiceEndpoint(ClprServiceEndpoint.newBuilder()
                                .setIpAddress("127.0.0.1")
                                .setPort(50211))
                        .setTlsCertificate(ByteString.copyFrom(new byte[] {1})))
                .build();
    }

    private static void assertPeerServiceDisabled(final Status status) {
        assertEquals(Status.Code.UNAVAILABLE, status.getCode());
        assertEquals("CLPR is not enabled", status.getDescription());
    }

    private static void assertCoversEveryClprEntryPoint(final LinkedHashMap<String, HapiTxnOp<?>> txns) {
        final var assertedRpcs = new LinkedHashSet<>(txns.keySet());
        assertedRpcs.add("getLedgerConfiguration");
        assertedRpcs.add("getEndpointManifest");
        assertEquals(
                ClprServiceGrpc.getServiceDescriptor().getMethods().stream()
                        .map(MethodDescriptor::getBareMethodName)
                        .collect(toSet()),
                assertedRpcs,
                "Every ClprService RPC must have an disabled-feature assertion");

        final var expectedFunctions = EnumSet.noneOf(HederaFunctionality.class);
        for (final var function : HederaFunctionality.values()) {
            if (function.name().startsWith("Clpr")) {
                expectedFunctions.add(function);
            }
        }
        expectedFunctions.removeAll(NODE_INTERNAL_CLPR_FUNCTIONS);
        final var assertedFunctions = EnumSet.noneOf(HederaFunctionality.class);
        txns.values().forEach(op -> assertedFunctions.add(op.type()));
        assertedFunctions.add(clprGetLedgerConfiguration().type());
        assertedFunctions.add(clprGetEndpointManifest().type());
        assertEquals(
                expectedFunctions,
                assertedFunctions,
                "Every Clpr* HederaFunctionality must have an disabled-feature assertion, or be listed "
                        + "in NODE_INTERNAL_CLPR_FUNCTIONS with the reason it is unreachable from HAPI");
    }

    private static HapiClprRegisterChannel endpointPublicationProbe() {
        // Reuse a transaction transport but replace its oneof body before signing. This checks
        // the actual parsed functionality, including an internal operation without its own RPC.
        return clprRegisterChannel()
                .withBodyMutation((body, spec) ->
                        body.setClprEndpointPublication(ClprEndpointPublicationTransactionBody.newBuilder()
                                .setEndpoint(ClprEndpoint.newBuilder()
                                        .setServiceEndpoint(ClprServiceEndpoint.newBuilder()
                                                .setIpAddress("127.0.0.1")
                                                .setPort(50211)))))
                .fee(100_000_000L);
    }

    private static ClprEndpointManifest manifest(final HapiSpec spec) {
        return spec.embeddedStateOrThrow()
                .getReadableStates(ClprService.NAME)
                .<ClprEndpointManifest>getSingleton(ENDPOINT_MANIFEST_STATE_ID)
                .get();
    }

    private static ClprEndpointManifestConstruction construction(final HapiSpec spec) {
        return spec.embeddedStateOrThrow()
                .getReadableStates(ClprService.NAME)
                .<ClprEndpointManifestConstruction>getSingleton(ENDPOINT_MANIFEST_CONSTRUCTION_STATE_ID)
                .get();
    }

    private static void putManifestState(
            final HapiSpec spec,
            final ClprEndpointManifest manifest,
            final ClprEndpointManifestConstruction construction) {
        final var states = spec.embeddedStateOrThrow().getWritableStates(ClprService.NAME);
        states.<ClprEndpointManifest>getSingleton(ENDPOINT_MANIFEST_STATE_ID).put(manifest);
        states.<ClprEndpointManifestConstruction>getSingleton(ENDPOINT_MANIFEST_CONSTRUCTION_STATE_ID)
                .put(construction);
        spec.commitEmbeddedState();
    }

    private static MethodDescriptor<byte[], byte[]> peerMethod(
            final String name, final MethodDescriptor.MethodType type) {
        final MethodDescriptor.Marshaller<byte[]> marshaller = new MethodDescriptor.Marshaller<>() {
            @Override
            public InputStream stream(final byte[] value) {
                return new ByteArrayInputStream(value);
            }

            @Override
            public byte[] parse(final InputStream stream) {
                try {
                    return stream.readAllBytes();
                } catch (final IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        };
        return MethodDescriptor.<byte[], byte[]>newBuilder()
                .setType(type)
                .setFullMethodName(ClprEndpointServiceDefinition.SERVICE_NAME + "/" + name)
                .setRequestMarshaller(marshaller)
                .setResponseMarshaller(marshaller)
                .build();
    }
}
