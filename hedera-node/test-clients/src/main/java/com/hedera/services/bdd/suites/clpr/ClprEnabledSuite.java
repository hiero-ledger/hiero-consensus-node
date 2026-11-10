// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.clpr;

import static com.hedera.hapi.util.HapiUtils.asTimestamp;
import static com.hedera.node.app.service.clpr.impl.schemas.V0780ClprSchema.ENDPOINT_MANIFEST_CONSTRUCTION_STATE_ID;
import static com.hedera.node.app.service.clpr.impl.schemas.V0780ClprSchema.ENDPOINT_MANIFEST_STATE_ID;
import static com.hedera.services.bdd.junit.EmbeddedReason.MUST_SKIP_INGEST;
import static com.hedera.services.bdd.junit.EmbeddedReason.NEEDS_STATE_ACCESS;
import static com.hedera.services.bdd.junit.TestTags.CLPR;
import static com.hedera.services.bdd.junit.TestTags.ONLY_SUBPROCESS;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.ContractInfoAsserts.contractWith;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.clprGetEndpointManifest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.clprGetLedgerConfiguration;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.contractCallLocal;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getContractInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.clprCloseChannel;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.clprCompleteChannel;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.clprCompleteConnector;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.clprDeregisterConnector;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.clprEvictPeerEndpoint;
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
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overridingAllOf;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.clpr.ClprTestProofs.VERIFY_CONFIG;
import static com.hedera.services.bdd.suites.clpr.ClprTestProofs.toBundleProofBytes;
import static com.hedera.services.bdd.suites.clpr.ClprTestProofs.toConfigProofBytes;
import static com.hedera.services.bdd.suites.contract.Utils.asSolidityAddress;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.NODE_AND_NETWORK_BASE_FEE;
import static com.hedera.services.bdd.suites.interledger.ClprTestHelpers.CLPR_SERVICE_ADDRESS_20;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.BATCH_TRANSACTION_IN_BLACKLIST;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CLPR_PEER_ENDPOINT_NOT_FOUND;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.UNAUTHORIZED;
import static java.util.stream.Collectors.toSet;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.esaulpaugh.headlong.abi.Function;
import com.google.protobuf.ByteString;
import com.hedera.hapi.node.base.Duration;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.state.clpr.ClprEndpointManifest;
import com.hedera.hapi.node.state.clpr.ClprEndpointManifestConstruction;
import com.hedera.hapi.node.state.clpr.ClprEndpointPublication;
import com.hedera.hapi.node.state.clpr.ClprEndpointPublicationEntry;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.hapi.services.auxiliary.clpr.legacy.ClprEndpointPublicationTransactionBody;
import com.hedera.node.app.service.clpr.ClprEndpointServiceDefinition;
import com.hedera.node.app.service.clpr.ClprService;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyEmbeddedHapiTest;
import com.hedera.services.bdd.junit.LeakyHapiTest;
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
    /** The default {@code clpr.minLockedStakeTinycents} ($3,000). */
    private static final long DEFAULT_MIN_LOCKED_STAKE_TINYCENTS = 30_000_000_000_000L;

    private static final String BATCH_OPERATOR = "batchOperator";
    private static final String CALLER = "caller";
    private static final String CLPR_CONTRACT = "ClprSystemContract";
    private static final String VERIFIER_CONTRACT = "ClprPassThroughVerifier";
    private static final String CONNECTOR_CONTRACT = "PassThroughAuth";
    private static final long GAS_TO_OFFER = 500_000L;
    private static final String INGEST_BYPASS_NODE = "4";
    // ClprMaintenance is never submitted, only externalized by nodes in the block stream
    private static final EnumSet<HederaFunctionality> NODE_INTERNAL_CLPR_FUNCTIONS =
            EnumSet.of(HederaFunctionality.ClprEndpointPublication, HederaFunctionality.ClprMaintenance);
    private static final List<String> CLPR_SYSTEM_CONTRACT_NUMS =
            List.of(String.valueOf(0x16eL), String.valueOf(0x16fL), String.valueOf(0x170L), String.valueOf(0x171L));

    @BeforeAll
    static void beforeAll(final TestLifecycle lifecycle) {
        lifecycle.overrideInClass(Map.of(
                "clpr.enabled",
                System.getProperty("clpr.test.enabled", "true"),
                "hedera.ingestThrottle.enabled",
                "true"));
    }

    @HapiTest
    @DisplayName("Every public CLPR transaction completes successfully when enabled")
    final Stream<DynamicTest> allClprTransactionsSucceed() {
        return exerciseTransactions(false, false);
    }

    @HapiTest
    @DisplayName("All ten CLPR transaction types are not whitelisted inside separate atomic batches")
    final Stream<DynamicTest> allClprTransactionsAreNotWhitelistedInsideAtomicBatches() {
        return exerciseTransactions(true, false);
    }

    @LeakyEmbeddedHapiTest(reason = MUST_SKIP_INGEST)
    @DisplayName("Every CLPR transaction succeeds at consensus when ingest is bypassed")
    final Stream<DynamicTest> allClprTransactionsSucceedAtConsensus() {
        return exerciseTransactions(false, true);
    }

    @LeakyEmbeddedHapiTest(reason = MUST_SKIP_INGEST)
    @DisplayName("All ten CLPR transaction types are not whitelisted in batches when ingest is bypassed")
    final Stream<DynamicTest> allClprTransactionsAreNotWhitelistedInBatchesAtConsensus() {
        return exerciseTransactions(true, true);
    }

    @HapiTest
    @DisplayName("Both CLPR queries accept COST_ANSWER and ANSWER_ONLY when enabled")
    final Stream<DynamicTest> clprQueriesSucceed() {
        // The queries are paid, so an answer's node payment must cover their cost
        return hapiTest(
                cryptoCreate(CALLER).balance(ONE_MILLION_HBARS),
                clprGetLedgerConfiguration().payingWith(CALLER).hasCostAnswerPrecheck(OK),
                clprGetEndpointManifest().payingWith(CALLER).hasCostAnswerPrecheck(OK),
                clprGetLedgerConfiguration()
                        .payingWith(CALLER)
                        .nodePayment(ONE_HBAR)
                        .hasAnswerOnlyPrecheck(OK),
                clprGetEndpointManifest()
                        .payingWith(CALLER)
                        .nodePayment(ONE_HBAR)
                        .hasAnswerOnlyPrecheck(OK));
    }

    @HapiTest
    @DisplayName("Native CLPR contracts reach proof validation when enabled")
    final Stream<DynamicTest> nativeClprContractsReachValidation() {
        // Deliberately invalid proof with the supported shared bundle selector. Enabled execution reverts; a disabled
        // native contract's address behaves as a plain system account and succeeds without executing anything, so it
        // cannot satisfy this test.
        final var operations = new ArrayList<SpecOperation>();
        operations.add(cryptoCreate(CALLER).balance(ONE_MILLION_HBARS));
        CLPR_SYSTEM_CONTRACT_NUMS.forEach(num -> operations.add(
                contractCallWithFunctionAbi(num, VERIFY_CONFIG.toJson(false), new byte[] {1}, new byte[32], new byte[0])
                        .payingWith(CALLER)
                        .gas(GAS_TO_OFFER)
                        .refusingEthConversion()
                        .hasKnownStatus(CONTRACT_REVERT_EXECUTED)));
        return hapiTest(operations.toArray(SpecOperation[]::new));
    }

    @HapiTest
    @DisplayName("A direct EVM manifest read charges the attached top-up once")
    final Stream<DynamicTest> nativeClprManifestReadChargesTopUp() {
        final var abi = new Function("getEndpointManifest()", "(bytes)").toJson(false);
        final var router = CLPR_SYSTEM_CONTRACT_NUMS.getFirst();
        final var paidRead = "paidManifestRead";
        final var balanceBefore = new AtomicLong();
        final var recordedFee = new AtomicLong();
        return hapiTest(
                cryptoCreate(CALLER).balance(ONE_HUNDRED_HBARS),
                contractCallWithFunctionAbi(router, abi)
                        .payingWith(CALLER)
                        .gas(GAS_TO_OFFER)
                        .refusingEthConversion()
                        .hasKnownStatus(CONTRACT_REVERT_EXECUTED),
                getAccountBalance(CALLER).payingWith(GENESIS).exposingBalanceTo(balanceBefore::set),
                contractCallWithFunctionAbi(router, abi)
                        .payingWith(CALLER)
                        .sending(ONE_HBAR)
                        .gas(GAS_TO_OFFER)
                        .refusingEthConversion()
                        .via(paidRead),
                getTxnRecord(paidRead).payingWith(GENESIS).exposingTo(record -> {
                    final var rate = record.getReceipt().getExchangeRate().getCurrentRate();
                    final long targetInTinybars = 200_000_000L * rate.getHbarEquiv() / rate.getCentEquiv();
                    assertTrue(record.getTransactionFee() >= targetInTinybars, "Fee must include the manifest target");
                    recordedFee.set(record.getTransactionFee());
                }),
                getAccountBalance(CALLER)
                        .payingWith(GENESIS)
                        .exposingBalanceTo(balance -> assertEquals(recordedFee.get(), balanceBefore.get() - balance)));
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
                .sending(ONE_HBAR)
                .gas(GAS_TO_OFFER)
                .hasKnownStatus(SUCCESS));
        operations.add(contractCallLocal(CLPR_CONTRACT, "getChannelQueueState", (Object) crypto.channelId())
                .gas(GAS_TO_OFFER));
        return hapiTest(operations.toArray(SpecOperation[]::new));
    }

    @LeakyHapiTest(
            overrides = {
                "clpr.messageExecutionCost",
                "clpr.progressRewardTinycents",
                "contracts.selfDestructs.externalization.enabled"
            })
    @DisplayName("A prepaid CLPR callback externalizes its committed constructor self-destruct")
    final Stream<DynamicTest> prepaidCallbackExternalizesSelfDestruct() {
        final var application = "SelfDestructClpr";
        final var submittedBundle = "selfDestructBundle";
        final var crypto = new ClprChannelCrypto();
        final long executionCost = 1_000_000L;
        final long progressRewardTinycents = 100_000_000L;
        final var callbackGasFee = new AtomicLong();
        final var operations = contractSetup();
        operations.add(overridingAllOf(Map.of(
                "clpr.messageExecutionCost", Long.toString(executionCost),
                "clpr.progressRewardTinycents", Long.toString(progressRewardTinycents),
                "contracts.selfDestructs.externalization.enabled", "true")));
        operations.add(uploadInitCode(application));
        operations.add(contractCreate(application).refusingEthConversion());
        final var txns = clprTxns(crypto);
        for (final var name : List.of(
                "updateLedgerConfiguration",
                "registerChannel",
                "completeChannel",
                "registerConnector",
                "completeConnector")) {
            operations.add(dispatch(txns.get(name), false, false));
        }
        operations.add(cryptoTransfer(tinyBarsFromTo(GENESIS, CONNECTOR_CONTRACT, ONE_HUNDRED_HBARS)));
        operations.add(withOpContext((spec, log) -> {
            final var applicationId = spec.registry().getContractId(application);
            final var payload = ClprMessagePayload.newBuilder()
                    .setMessage(ClprMessage.newBuilder()
                            .setConnectorId(ByteString.copyFrom(crypto.connectorId()))
                            .setTargetApplication(ByteString.copyFrom(asSolidityAddress(applicationId)))
                            .setSender(ByteString.copyFrom(new byte[20]))
                            .setMessageData(ByteString.EMPTY))
                    .build();
            final var bundleBytes =
                    toBundleProofBytes(ClprChannelStatus.ACTIVE, 0L, 0L, new byte[32], List.of(payload));
            final var verifierId = spec.registry().getContractId(VERIFIER_CONTRACT);
            allRunFor(
                    spec,
                    clprSubmitBundle()
                            .channelId(crypto.channelId())
                            .endpointNodeId(0L)
                            .bundlePayload(bundleBytes)
                            .payingWith(CALLER)
                            .via(submittedBundle)
                            .hasKnownStatus(SUCCESS),
                    getTxnRecord(submittedBundle).exposingAllTo(records -> {
                        final var verifier = records.stream()
                                .filter(r -> r.hasContractCallResult()
                                        && r.getContractCallResult()
                                                .getContractID()
                                                .equals(verifierId))
                                .findFirst()
                                .orElseThrow();
                        final long verifierGas =
                                verifier.getContractCallResult().getGasUsed();
                        assertTrue(verifierGas > 0);
                        final long expectedNativeFee = spec.ratesProvider()
                                .toTbWithActiveRates(100_000_000L
                                        + 1_000L * bundleBytes.length
                                        + Math.min(verifierGas, 300_000L)
                                                * spec.ratesProvider().tinycentGasPrice());
                        final var creations = records.stream()
                                .filter(r -> r.hasContractCreateResult())
                                .toList();
                        assertEquals(1, creations.size(), "The CLPR callback creates exactly one child");
                        final var creation = creations.getFirst();
                        assertEquals(SUCCESS, creation.getReceipt().getStatus());
                        final var deletedId = creation.getReceipt().getContractID();
                        final var deletions = records.stream()
                                .filter(r -> !r.hasContractCreateResult()
                                        && r.getReceipt().getContractID().equals(deletedId))
                                .toList();
                        assertEquals(1, deletions.size(), "The committed deletion is externalized exactly once");
                        final var deletion = deletions.getFirst();
                        assertEquals(SUCCESS, deletion.getReceipt().getStatus());
                        assertEquals(0L, deletion.getTransactionFee());
                        assertEquals(0, deletion.getTransferList().getAccountAmountsCount());
                        assertTrue(records.indexOf(creation) < records.indexOf(deletion));
                        final var callbacks = records.stream()
                                .filter(r -> r.hasContractCallResult()
                                        && r.getContractCallResult()
                                                .getContractID()
                                                .equals(applicationId))
                                .toList();
                        assertEquals(1, callbacks.size());
                        assertEquals(SUCCESS, callbacks.getFirst().getReceipt().getStatus());
                        assertEquals(0L, callbacks.getFirst().getTransactionFee(), "Callback gas is prepaid");
                        final var gasUsed =
                                callbacks.getFirst().getContractCallResult().getGasUsed();
                        assertTrue(gasUsed > 0);
                        callbackGasFee.set(
                                Math.multiplyExact(gasUsed, spec.ratesProvider().currentTinybarGasPrice()));
                        assertTrue(callbackGasFee.get() > 0, "The connector reimburses the callback's network gas fee");
                        assertEquals(
                                Math.addExact(expectedNativeFee, callbackGasFee.get()),
                                records.getFirst().getTransactionFee());
                        spec.registry().saveContractId("deletedClprChild", deletedId);
                    }),
                    getContractInfo("deletedClprChild").has(contractWith().isDeleted()));
        }));
        operations.add(contractCallLocal(application, "deliveries")
                .exposingTypedResultsTo(results -> assertEquals(BigInteger.ONE, results[0])));
        operations.add(getContractInfo(application).has(contractWith().isNotDeleted()));
        operations.add(withOpContext((spec, log) -> allRunFor(
                spec,
                getAccountBalance(CONNECTOR_CONTRACT)
                        .hasTinyBars(ONE_HUNDRED_HBARS
                                - executionCost
                                - spec.ratesProvider().toTbWithActiveRates(progressRewardTinycents)
                                - callbackGasFee.get()))));
        return hapiTest(operations.toArray(SpecOperation[]::new));
    }

    @HapiTest
    @DisplayName("A signed account transaction cannot publish a node endpoint")
    final Stream<DynamicTest> signedEndpointPublicationIsRejectedAtConsensus() {
        return hapiTest(endpointPublicationProbe().hasPrecheck(OK).hasKnownStatus(UNAUTHORIZED));
    }

    @Tag(ONLY_SUBPROCESS)
    @HapiTest
    @DisplayName("Every node reaches streaming sync request validation and no longer serves discovery")
    final Stream<DynamicTest> peerRpcCallsReachValidation() {
        return hapiTest(withOpContext((spec, opLog) -> {
            for (final var node : spec.targetNetworkOrThrow().nodes()) {
                final var channel = NettyChannelBuilder.forAddress(node.getHost(), node.getGrpcPort())
                        .usePlaintext()
                        .build();
                try {
                    final var error = assertThrows(
                            StatusRuntimeException.class,
                            () -> ClientCalls.blockingUnaryCall(
                                    channel,
                                    peerMethod("discoverEndpoints", MethodDescriptor.MethodType.UNARY),
                                    CallOptions.DEFAULT.withDeadlineAfter(5, TimeUnit.SECONDS),
                                    new byte[0]));
                    // The legacy discovery RPC was removed in favour of the endpoint manifest.
                    assertEquals(Status.Code.UNIMPLEMENTED, error.getStatus().getCode());
                    final var status = new CompletableFuture<Status>();
                    final var stream = ClientCalls.asyncBidiStreamingCall(
                            channel.newCall(
                                    peerMethod("sync", MethodDescriptor.MethodType.BIDI_STREAMING),
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
        @LeakyEmbeddedHapiTest(reason = NEEDS_STATE_ACCESS)
        @DisplayName("A node publication is admitted at consensus and pays its scheduled fee")
        final Stream<DynamicTest> publicationIsAdmittedAtConsensus() {
            return hapiTest(withOpContext((spec, opLog) -> {
                final var node = spec.getNetworkNodes().getFirst();
                final var payer = node.getAccountId();
                allRunFor(
                        spec,
                        cryptoTransfer(tinyBarsFromTo(GENESIS, "" + payer.accountNumOrThrow(), ONE_HUNDRED_HBARS)));
                final var embedded = spec.embeddedHederaOrThrow();
                final var txnId = new TransactionID(asTimestamp(embedded.now()), payer, false, 0);
                final var publication = TransactionBody.newBuilder()
                        .transactionID(txnId)
                        .nodeAccountID(payer)
                        .transactionValidDuration(new Duration(120L))
                        .transactionFee(ONE_HUNDRED_HBARS)
                        .clprEndpointPublication(
                                com.hedera.hapi.services.auxiliary.clpr.ClprEndpointPublicationTransactionBody
                                        .newBuilder()
                                        .endpoint(com.hedera.hapi.node.state.clpr.ClprEndpoint.newBuilder()
                                                .serviceEndpoint(
                                                        com.hedera.hapi.node.state.clpr.ClprServiceEndpoint.newBuilder()
                                                                .ipAddress("127.0.0.1")
                                                                .port(50211))))
                        .build();
                final var beforeManifest = manifest(spec);
                final var beforeConstruction = construction(spec);
                final var pending = ClprEndpointManifestConstruction.newBuilder()
                        .constructionId(beforeManifest.version() + 1)
                        .targetNodeIds(List.of(0L, 1L, 2L, 3L))
                        // Seed this node's entry to prevent the reconciler from racing the update.
                        .gatheredPublications(List.of(ClprEndpointPublicationEntry.newBuilder()
                                .nodeId(node.getNodeId())
                                .publication(ClprEndpointPublication.newBuilder()
                                        .endpoint(com.hedera.hapi.node.state.clpr.ClprEndpoint.DEFAULT)
                                        .build())
                                .build()))
                        .gracePeriodEndTime(
                                Timestamp.newBuilder().seconds(4_102_444_800L).build())
                        .build();
                putManifestState(spec, beforeManifest, pending);
                try {
                    embedded.hedera().submit(publication);
                    allRunFor(
                            spec,
                            getTxnRecord(txnId).assertingNothingAboutHashes().exposingTo(record -> {
                                assertEquals(SUCCESS, record.getReceipt().getStatus());
                                final var rate =
                                        record.getReceipt().getExchangeRate().getCurrentRate();
                                final double feeUsd = (double) record.getTransactionFee()
                                        / ONE_HBAR
                                        * rate.getCentEquiv()
                                        / rate.getHbarEquiv()
                                        / 100;
                                assertEquals(1.0 + NODE_AND_NETWORK_BASE_FEE, feeUsd, 0.0000001);
                            }));
                    assertTrue(
                            construction(spec).gatheredPublications().stream()
                                    .anyMatch(entry -> entry.nodeId() == node.getNodeId()
                                            && entry.publicationOrThrow()
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
            operations.add(dispatch(entry.getValue(), inBatch, bypassIngest));
        }
        if (inBatch) {
            operations.add(dispatch(endpointPublicationProbe(), true, bypassIngest));
        } else if (bypassIngest) {
            operations.add(
                    endpointPublicationProbe().setNode(INGEST_BYPASS_NODE).hasKnownStatus(UNAUTHORIZED));
        }
        return hapiTest(operations.toArray(SpecOperation[]::new));
    }

    private static HapiTxnOp<?> dispatch(final HapiTxnOp<?> op, final boolean inBatch, final boolean bypassIngest) {
        // The channel's cached manifest is empty here, so eviction reaching its handler proves the feature is enabled
        final var expected =
                op.type() == HederaFunctionality.ClprEvictPeerEndpoint ? CLPR_PEER_ENDPOINT_NOT_FOUND : SUCCESS;
        op.hasKnownStatus(expected);
        final HapiTxnOp<?> submitted = inBatch
                ? atomicBatch(op.batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(BATCH_TRANSACTION_IN_BLACKLIST)
                : op.hasKnownStatus(expected);
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
                cryptoCreate(CALLER).balance(ONE_MILLION_HBARS)));
    }

    private static HapiTxnOp<?> sendMessage(final ClprChannelCrypto crypto) {
        return contractCall(
                        CLPR_CONTRACT,
                        "sendMessage",
                        crypto.channelId(),
                        crypto.connectorId(),
                        new byte[20],
                        new byte[] {1, 2, 3})
                .sending(ONE_HBAR)
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
        txns.put(
                "registerConnector",
                clprRegisterConnector().channelId(crypto.channelId()).commitment(crypto.connectorCommitment()));
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
                        .lockedStakeTinycents(DEFAULT_MIN_LOCKED_STAKE_TINYCENTS));
        txns.put(
                "deregisterConnector",
                clprDeregisterConnector()
                        .channelId(crypto.channelId())
                        .connectorId(crypto.connectorId())
                        .adminKey(GENESIS)
                        .stakeRecipient(GENESIS));
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
        txns.put(
                "evictPeerEndpoint",
                clprEvictPeerEndpoint().channelId(crypto.channelId()).endpoint("203.0.113.1", 50211));
        txns.put("closeChannel", clprCloseChannel().channelId(crypto.channelId()));
        // Only governance operations require an administrator; public operations must exercise user throttles.
        txns.values()
                .forEach(op -> op.payingWith(
                        switch (op.type()) {
                            case ClprUpdateLedgerConfiguration, ClprCloseChannel, ClprEvictPeerEndpoint -> GENESIS;
                            default -> CALLER;
                        }));
        return txns;
    }

    private static ClprLedgerConfiguration ledgerConfig() {
        return ClprLedgerConfiguration.newBuilder()
                .setChainId("hiero:testing")
                .setServiceAddress(ByteString.copyFrom(CLPR_SERVICE_ADDRESS_20))
                .setThrottles(HapiClprUpdateLedgerConfiguration.defaultThrottles())
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
                .payingWith(GENESIS)
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
        spec.mutateEmbeddedState(state -> {
            final var states = state.getWritableStates(ClprService.NAME);
            states.<ClprEndpointManifest>getSingleton(ENDPOINT_MANIFEST_STATE_ID)
                    .put(manifest);
            states.<ClprEndpointManifestConstruction>getSingleton(ENDPOINT_MANIFEST_CONSTRUCTION_STATE_ID)
                    .put(construction);
        });
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
