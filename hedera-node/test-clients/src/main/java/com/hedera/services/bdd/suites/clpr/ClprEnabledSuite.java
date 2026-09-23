// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.clpr;

import static com.hedera.node.app.service.clpr.impl.schemas.V0770ClprSchema.ENDPOINT_MANIFEST_CONSTRUCTION_STATE_ID;
import static com.hedera.node.app.service.clpr.impl.schemas.V0770ClprSchema.ENDPOINT_MANIFEST_STATE_ID;
import static com.hedera.services.bdd.junit.EmbeddedReason.MUST_SKIP_INGEST;
import static com.hedera.services.bdd.junit.EmbeddedReason.NEEDS_STATE_ACCESS;
import static com.hedera.services.bdd.junit.TestTags.CLPR;
import static com.hedera.services.bdd.junit.TestTags.ONLY_SUBPROCESS;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.clprGetEndpointManifest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.clprGetLedgerConfiguration;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
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
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.clpr.ClprTestProofs.toBundleProofBytes;
import static com.hedera.services.bdd.suites.clpr.ClprTestProofs.toConfigProofBytes;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static java.util.stream.Collectors.toSet;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.ByteString;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.clpr.ClprEndpointManifest;
import com.hedera.hapi.node.state.clpr.ClprEndpointManifestConstruction;
import com.hedera.hapi.node.state.clpr.ClprEndpointPublication;
import com.hedera.hapi.node.state.clpr.ClprEndpointPublicationEntry;
import com.hedera.hapi.services.auxiliary.clpr.legacy.ClprEndpointPublicationTransactionBody;
import com.hedera.node.app.service.clpr.ClprEndpointServiceDefinition;
import com.hedera.node.app.service.clpr.ClprService;
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
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;

/**
 * Exercises enabled CLPR behavior at HAPI, atomic-batch, consensus, EVM and peer-RPC entry points.
 *
 * <p>The master flag is set once in {@link #beforeAll(TestLifecycle)} and restored by the lifecycle
 * extension after the class. Assertions always describe enabled behavior; they never change with
 * the flag. Run with {@code -PsysProp.clpr.test.enabled=false} to verify that these tests fail.
 * Fixtures are created within each test, so a disabled run fails individual tests, not class setup.
 */
@Tag(CLPR)
@OrderedInIsolation
@HapiTestLifecycle
public class ClprEnabledSuite {
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
    private static final String PROBE_ABI = "{\"name\":\"verifyConfig\","
            + "\"inputs\":[{\"name\":\"proofBytes\",\"type\":\"bytes\"}],"
            + "\"outputs\":[{\"name\":\"\",\"type\":\"bytes\"}],"
            + "\"stateMutability\":\"view\",\"type\":\"function\"}";

    @BeforeAll
    static void beforeAll(final TestLifecycle lifecycle) {
        lifecycle.overrideInClass(Map.of(
                "clpr.enabled",
                System.getProperty("clpr.test.enabled", "true"),
                "clpr.endpointManifestEnabled",
                "false"));
    }

    @HapiTest
    @DisplayName("Every public CLPR transaction completes successfully when enabled")
    final Stream<DynamicTest> allClprTransactionsSucceed() {
        return exerciseTransactions(false, false);
    }

    @HapiTest
    @DisplayName("All ten CLPR transaction types succeed inside separate atomic batches")
    final Stream<DynamicTest> allClprTransactionsSucceedInsideAtomicBatches() {
        return exerciseTransactions(true, false);
    }

    @LeakyEmbeddedHapiTest(reason = MUST_SKIP_INGEST)
    @DisplayName("Every CLPR transaction succeeds at consensus when ingest is bypassed")
    final Stream<DynamicTest> allClprTransactionsSucceedAtConsensus() {
        return exerciseTransactions(false, true);
    }

    @LeakyEmbeddedHapiTest(reason = MUST_SKIP_INGEST)
    @DisplayName("All ten CLPR transaction types succeed in batches when ingest is bypassed")
    final Stream<DynamicTest> allClprTransactionsSucceedInBatchesAtConsensus() {
        return exerciseTransactions(true, true);
    }

    @HapiTest
    @DisplayName("Both CLPR queries accept COST_ANSWER and ANSWER_ONLY when enabled")
    final Stream<DynamicTest> clprQueriesSucceed() {
        return hapiTest(
                clprGetLedgerConfiguration().payingWith(GENESIS).hasCostAnswerPrecheck(OK),
                clprGetEndpointManifest().payingWith(GENESIS).hasCostAnswerPrecheck(OK),
                clprGetLedgerConfiguration().payingWith(GENESIS).nodePayment(1L).hasAnswerOnlyPrecheck(OK),
                clprGetEndpointManifest().payingWith(GENESIS).nodePayment(1L).hasAnswerOnlyPrecheck(OK));
    }

    @HapiTest
    @DisplayName("Native CLPR contracts reach selector or proof validation when enabled")
    final Stream<DynamicTest> nativeClprContractsReachValidation() {
        // Deliberately invalid selector/proof. Enabled execution reverts; the disabled native
        // contract halts with the distinct CLPR_NOT_ENABLED status, so it cannot satisfy this test.
        return hapiTest(CLPR_SYSTEM_CONTRACT_NUMS.stream()
                .map(num -> contractCallWithFunctionAbi(num, PROBE_ABI, (Object) new byte[] {1})
                        .payingWith(GENESIS)
                        .gas(GAS_TO_OFFER)
                        .refusingEthConversion()
                        .hasKnownStatus(CONTRACT_REVERT_EXECUTED))
                .toArray(SpecOperation[]::new));
    }

    @HapiTest
    @DisplayName("A deployed contract sends a message and reads a real CLPR channel successfully")
    final Stream<DynamicTest> clprRouterCallsSucceedFromContract() {
        final var crypto = new ClprChannelCrypto();
        final var operations = contractSetup();
        final var txns = clprTxns(crypto);
        for (final var name : List.of(
                "updateLedgerConfiguration",
                "registerChannel",
                "completeChannel",
                "registerConnector",
                "completeConnector")) {
            operations.add(dispatch(txns.get(name), false, false));
        }
        operations.add(sendMessage(crypto).via("sentMessage"));
        operations.add(getTxnRecord("sentMessage")
                .exposingTo(record -> assertTrue(
                        new BigInteger(
                                                1,
                                                record.getContractCallResult()
                                                        .getContractCallResult()
                                                        .toByteArray())
                                        .longValueExact()
                                > 0,
                        "sendMessage must return the enqueued message ID")));
        operations.add(contractCall(CLPR_CONTRACT, "getChannelQueueState", (Object) crypto.channelId())
                .payingWith(CALLER)
                .gas(GAS_TO_OFFER)
                .hasKnownStatus(SUCCESS));
        return hapiTest(operations.toArray(SpecOperation[]::new));
    }

    @HapiTest
    @DisplayName("The internal endpoint-publication wire body passes ingest when enabled")
    final Stream<DynamicTest> internalEndpointPublicationPassesIngest() {
        return hapiTest(
                endpointPublicationProbe().payingWith(GENESIS).hasPrecheck(OK).hasKnownStatus(SUCCESS));
    }

    @Tag(ONLY_SUBPROCESS)
    @HapiTest
    @DisplayName("Every node reaches peer sync, discovery and streaming request validation")
    final Stream<DynamicTest> peerRpcCallsReachValidation() {
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
                        assertInvalidPeerRequest(error.getStatus());
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
                                            new AssertionError("Empty channel ID must be rejected"));
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
                    assertInvalidPeerRequest(status.get(10, TimeUnit.SECONDS));
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
        @DisplayName("An internal publication is admitted at consensus when enabled")
        final Stream<DynamicTest> publicationIsAdmittedAtConsensus() {
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
                                    .hasKnownStatus(SUCCESS));
                    assertTrue(
                            construction(spec).gatheredPublications().stream()
                                    .anyMatch(entry -> entry.publicationOrThrow()
                                                    .endpointOrThrow()
                                                    .serviceEndpointOrThrow()
                                                    .port()
                                            == 50211),
                            "The handler must record the publication");
                } finally {
                    putManifestState(spec, beforeManifest, beforeConstruction);
                }
            }));
        }

        @LeakyEmbeddedHapiTest(reason = NEEDS_STATE_ACCESS)
        @DisplayName("Consensus finalizes an expired manifest construction when enabled")
        final Stream<DynamicTest> consensusFinalizesManifest() {
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
                    assertTrue(manifest(spec).version() > beforeManifest.version(), "Manifest must advance");
                    assertTrue(manifest(spec).endpoints().contains(endpoint), "Published endpoint must be retained");
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
        for (final var entry : txns.entrySet()) {
            if (entry.getKey().equals("redactMessage")) {
                // The connector was deregistered successfully before it had any in-flight messages.
                // Re-create it and enqueue a real message for the redaction assertion.
                final var fresh = clprTxns(crypto);
                operations.add(dispatch(fresh.get("registerConnector"), inBatch, bypassIngest));
                operations.add(dispatch(fresh.get("completeConnector"), inBatch, bypassIngest));
                final var messageId = new AtomicLong();
                operations.add(sendMessage(crypto).via("messageToRedact"));
                operations.add(getTxnRecord("messageToRedact")
                        .exposingTo(record -> messageId.set(new BigInteger(
                                        1,
                                        record.getContractCallResult()
                                                .getContractCallResult()
                                                .toByteArray())
                                .longValueExact())));
                operations.add(sourcing(() -> dispatch(
                        clprRedactMessage().channelId(crypto.channelId()).messageId(messageId.get()),
                        inBatch,
                        bypassIngest)));
            } else {
                operations.add(dispatch(entry.getValue(), inBatch, bypassIngest));
            }
        }
        operations.add(dispatch(endpointPublicationProbe(), inBatch, bypassIngest));
        return hapiTest(operations.toArray(SpecOperation[]::new));
    }

    private static HapiTxnOp<?> dispatch(final HapiTxnOp<?> op, final boolean inBatch, final boolean bypassIngest) {
        op.payingWith(GENESIS).hasKnownStatus(SUCCESS);
        final HapiTxnOp<?> submitted = inBatch
                ? atomicBatch(op.batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(SUCCESS)
                : op;
        if (bypassIngest) {
            submitted.setNode(INGEST_BYPASS_NODE);
        }
        return submitted;
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
                .hasKnownStatus(SUCCESS);
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

    private static void assertInvalidPeerRequest(final Status status) {
        assertEquals(Status.Code.INVALID_ARGUMENT, status.getCode());
        assertEquals("channel_id must be exactly 32 bytes", status.getDescription());
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
                "Every ClprService RPC must have an enabled-feature assertion");

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
                "Every Clpr* HederaFunctionality must have an enabled-feature assertion, or be listed "
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
