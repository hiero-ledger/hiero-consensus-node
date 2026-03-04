// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.interledger;

import static com.hedera.node.app.hapi.utils.CommonPbjConverters.toPbj;
import static com.hedera.services.bdd.junit.hedera.NodeSelector.byNodeId;
import static com.hedera.services.bdd.junit.hedera.utils.NetworkUtils.CLASSIC_NODE_NAMES;
import static com.hedera.services.bdd.junit.hedera.utils.NetworkUtils.classicFeeCollectorIdFor;
import static com.hedera.services.bdd.junit.hedera.utils.NetworkUtils.nodeIdsFrom;
import static com.hedera.services.bdd.junit.hedera.utils.WorkingDirUtils.gossipCaCertificateForNodeId;
import static com.hedera.services.bdd.spec.HapiPropertySource.asAccount;
import static com.hedera.services.bdd.spec.HapiSpec.customizedHapiTest;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCallWithFunctionAbi;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.nodeCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.nodeDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateCandidateRoster;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.contract.Utils.FunctionType.FUNCTION;
import static com.hedera.services.bdd.suites.contract.Utils.asAddress;
import static com.hedera.services.bdd.suites.contract.Utils.getABIFor;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import com.esaulpaugh.headlong.abi.Address;
import com.esaulpaugh.headlong.abi.Function;
import com.esaulpaugh.headlong.abi.Tuple;
import com.esaulpaugh.headlong.abi.TupleType;
import com.hedera.hapi.node.base.ServiceEndpoint;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.OrderedInIsolation;
import com.hedera.services.bdd.junit.TestTags;
import com.hedera.services.bdd.junit.hedera.HederaNode;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.utilops.ContextualActionOp;
import com.hedera.services.bdd.spec.utilops.FakeNmt;
import com.hedera.services.bdd.suites.HapiSuite;
import com.hedera.services.bdd.suites.regression.system.LifecycleTest;
import com.hederahashgraph.api.proto.java.ContractID;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.hiero.hapi.interledger.state.clpr.ClprEndpoint;
import org.hiero.hapi.interledger.state.clpr.ClprLedgerConfiguration;
import org.hiero.hapi.interledger.state.clpr.ClprLedgerId;
import org.hiero.hapi.interledger.state.clpr.ClprMessage;
import org.hiero.hapi.interledger.state.clpr.ClprMessageBundle;
import org.hiero.hapi.interledger.state.clpr.ClprMessageKey;
import org.hiero.hapi.interledger.state.clpr.ClprMessagePayload;
import org.hiero.hapi.interledger.state.clpr.ClprMessageQueueMetadata;
import org.hiero.hapi.interledger.state.clpr.ClprMessageValue;
import org.hiero.interledger.clpr.ClprStateProofUtils;
import org.hiero.interledger.clpr.client.ClprClient;
import org.hiero.interledger.clpr.impl.ClprMessageUtils;
import org.hiero.interledger.clpr.impl.client.ClprClientImpl;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;

@OrderedInIsolation
@Tag(TestTags.CLPR)
@HapiTestLifecycle
public class ClprSuite implements LifecycleTest {
    private static final Map<String, String> CLPR_OVERRIDES =
            Map.of("clpr.clprEnabled", "true", "contracts.systemContract.clprQueue.enabled", "true");
    private static final long NODE_ID_TO_REMOVE = 3L;
    private static final long REPLACEMENT_NODE_ID = 4L;
    private static final String REPLACEMENT_NODE_NAME = CLASSIC_NODE_NAMES[(int) REPLACEMENT_NODE_ID];
    private static final String CLPR_MIDDLEWARE = "ClprMiddleware";
    private static final String ECHO_APP = "EchoApplication";
    private static final String CLPR_QUEUE_SYSTEM_CONTRACT = "0x000000000000000000000000000000000000016e";
    private static final String ABI_REGISTER_LOCAL_APPLICATION =
            getABIFor(FUNCTION, "registerLocalApplication", CLPR_MIDDLEWARE);
    private static final String ABI_SET_TRUSTED_CALLBACK_CALLER =
            getABIFor(FUNCTION, "setTrustedCallbackCaller", CLPR_MIDDLEWARE);
    private static final Function ENQUEUE_MESSAGE = new Function(
            "enqueueMessage((address,(address,bytes32,(uint256,string),bytes),bytes32,(bool,(uint256,string),bytes),((bytes32,(uint256,string),(uint256,string),(uint256,string)),bytes)))",
            "(uint64)");
    private static final TupleType<Tuple> REQUEST_ROUTE_HEADER_TYPE =
            TupleType.parse("(uint8,bytes32,address,address)");
    private static final int ABI_SELECTOR_LENGTH = 4;
    private static final Map<String, String> PUBLICIZE_DISABLED =
            Map.of("clpr.publicizeNetworkAddresses", "false", "contracts.systemContract.clprQueue.enabled", "true");
    private static final Map<String, String> PUBLICIZE_ENABLED =
            Map.of("clpr.publicizeNetworkAddresses", "true", "contracts.systemContract.clprQueue.enabled", "true");
    private static final Duration CLPR_QUERY_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration CLPR_QUERY_POLL_INTERVAL = Duration.ofMillis(200);

    @BeforeAll
    static void beforeAll(final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(CLPR_OVERRIDES);
    }

    @DisplayName("Genesis publishes CLPR ledger configuration")
    @HapiTest
    @Order(1)
    final Stream<DynamicTest> genesisPublishesLedgerConfig() {
        /*
         * Minimal smoke test that queries a single node for the genesis CLPR configuration.
         *
         * Likely failure scenarios:
         * - Genesis bootstrap skipped because the roster/roster hash is unavailable, so no CLPR_SET is dispatched.
         * - State proof verification/parsing fails because CLPR state IDs drift from key/value field numbers.
         * - CLPR state changes were not committed at genesis, leaving no proof to fetch.
         */
        return customizedHapiTest(Map.of(), withOpContext((spec, opLog) -> {
            final var nodes = List.copyOf(spec.subProcessNetworkOrThrow().nodes());
            assertThat(nodes)
                    .as("Network must provide at least one node for CLPR genesis bootstrap")
                    .isNotEmpty();
            final var node = nodes.get(0);
            final var config = fetchLedgerConfiguration(List.of(node));
            assertThat(config.ledgerId().ledgerId())
                    .as("Genesis CLPR ledger id must be populated")
                    .isNotNull();
            assertThat(config.endpoints())
                    .as("Genesis CLPR ledger configuration must include endpoints")
                    .isNotEmpty()
                    .allSatisfy(endpoint -> assertThat(endpoint.signingCertificate())
                            .as("CLPR endpoints must advertise signing certificates")
                            .isNotNull());
        }));
    }

    @DisplayName("Roster change upgrade refreshes CLPR ledger configuration")
    @HapiTest
    @Order(2)
    final Stream<DynamicTest> rosterChangeUpgradeRefreshesLedgerConfig() {
        /*
         * What this test explicitly verifies:
         * 1) With clpr.publicizeNetworkAddresses=true (default), the fetched CLPR ledger configuration
         *    includes one endpoint per roster node, each with a network address and the node's gRPC
         *    gateway port (not the gossip port). Certificates must be present and consistent.
         * 2) Toggle clpr.publicizeNetworkAddresses=false without a roster change; perform a freeze/upgrade;
         *    then fetch the configuration. Expectations: same ledgerId, advanced timestamp, roster size is
         *    unchanged, and all endpoints omit network addresses/ports when publicize=false.
         * 3) Remove one node and add a new node (with a fresh certificate); keep
         *    clpr.publicizeNetworkAddresses=false; perform a freeze/upgrade; then fetch the configuration.
         *    Expectations: same ledgerId, advanced timestamp, roster size is unchanged, and all endpoints
         *    omit network addresses/ports when publicize=false.
         * 4) With no roster change, set clpr.publicizeNetworkAddresses=true; perform another freeze/upgrade;
         *    then fetch the configuration. Expectations: same ledgerId, advanced timestamp, roster unchanged,
         *    endpoints contain network addresses/ports matching the gRPC gateways for the current roster nodes,
         *    and certificates remain stable.
         */

        final AtomicReference<List<HederaNode>> activeNodes = new AtomicReference<>();
        final AtomicReference<ClprLedgerConfiguration> baselineConfig = new AtomicReference<>();
        final AtomicReference<ClprLedgerConfiguration> latestConfig = new AtomicReference<>();
        return customizedHapiTest(
                Map.of(),
                withOpContext((spec, opLog) -> {
                    final var nodes =
                            List.copyOf(spec.subProcessNetworkOrThrow().nodes());
                    assertThat(nodes)
                            .as("Stage 1 expects a four-node subprocess network")
                            .hasSize(4);
                    activeNodes.set(nodes);
                }),
                withOpContext((spec, opLog) -> {
                    final var nodes = requireNonNull(
                            activeNodes.get(), "Active node metadata must be captured before querying CLPR state");
                    final var config = fetchLedgerConfiguration(nodes);
                    baselineConfig.set(config);
                    latestConfig.set(config);
                    final var endpoints = config.endpoints();
                    assertThat(endpoints)
                            .as("CLPR ledger configuration should expose one endpoint per node")
                            .hasSize(nodes.size());
                    nodes.forEach(node -> assertThat(endpoints)
                            .as("Expected CLPR endpoint for node {}", node.getNodeId())
                            .anySatisfy(endpoint -> assertEndpointMatchesNode(endpoint, node)));
                }),
                prepareFakeUpgrade(),
                upgradeToNextConfigVersion(PUBLICIZE_DISABLED),
                withOpContext((spec, opLog) -> {
                    final var nodes =
                            List.copyOf(spec.subProcessNetworkOrThrow().nodes());
                    activeNodes.set(nodes);
                    final var baseline = requireNonNull(baselineConfig.get(), "Baseline configuration required");
                    final var baselineTimestamp = configTimestamp(baseline);
                    final var config = fetchLedgerConfiguration(
                            nodes,
                            candidate -> configTimestamp(candidate).isAfter(baselineTimestamp),
                            "advance after publicize toggle");
                    assertLedgerIdStable(baseline, config);
                    assertTimestampAdvanced(baseline, config);
                    assertThat(config.endpoints())
                            .as("CLPR endpoints should omit network addresses when publicize=false")
                            .hasSize(nodes.size())
                            .allSatisfy(endpoint -> {
                                assertThat(endpoint.signingCertificate())
                                        .as("CLPR endpoints must retain certificates")
                                        .isNotNull();
                                assertThat(endpoint.hasEndpoint())
                                        .as("publicize=false should omit service endpoints")
                                        .isFalse();
                            });
                    latestConfig.set(config);
                }),
                nodeDelete(Long.toString(NODE_ID_TO_REMOVE)),
                nodeCreate(REPLACEMENT_NODE_NAME, classicFeeCollectorIdFor(REPLACEMENT_NODE_ID))
                        .adminKey(HapiSuite.DEFAULT_PAYER)
                        .description(REPLACEMENT_NODE_NAME)
                        .withAvailableSubProcessPorts()
                        .gossipCaCertificate(gossipCaCertificateForNodeId(REPLACEMENT_NODE_ID)),
                prepareFakeUpgrade(),
                validateCandidateRoster(addressBook -> assertThat(nodeIdsFrom(addressBook))
                        .as("Roster should replace node {}/{}", NODE_ID_TO_REMOVE, REPLACEMENT_NODE_ID)
                        .contains(REPLACEMENT_NODE_ID)
                        .contains(0L, 1L, 2L)
                        .doesNotContain(NODE_ID_TO_REMOVE)),
                upgradeToNextConfigVersion(
                        PUBLICIZE_DISABLED,
                        FakeNmt.removeNode(byNodeId(NODE_ID_TO_REMOVE)),
                        FakeNmt.addNode(REPLACEMENT_NODE_ID)),
                withOpContext((spec, opLog) -> {
                    final var nodes =
                            List.copyOf(spec.subProcessNetworkOrThrow().nodes());
                    assertThat(nodes)
                            .as("Roster change should keep a four-node network")
                            .hasSize(4);
                    assertThat(nodes.stream().map(HederaNode::getNodeId).toList())
                            .as("Node {} should be replaced by {}", NODE_ID_TO_REMOVE, REPLACEMENT_NODE_ID)
                            .doesNotContain(NODE_ID_TO_REMOVE)
                            .contains(REPLACEMENT_NODE_ID);
                    activeNodes.set(nodes);
                    final var priorConfig = requireNonNull(latestConfig.get(), "Latest configuration required");
                    final var priorTimestamp = configTimestamp(priorConfig);
                    final var config = fetchLedgerConfiguration(
                            nodes,
                            candidate -> configTimestamp(candidate).isAfter(priorTimestamp),
                            "advance after roster change");
                    assertLedgerIdStable(priorConfig, config);
                    assertTimestampAdvanced(priorConfig, config);
                    assertThat(config.endpoints())
                            .as("CLPR endpoints should omit network addresses when publicize=false")
                            .hasSize(nodes.size())
                            .allSatisfy(endpoint -> {
                                assertThat(endpoint.signingCertificate())
                                        .as("CLPR endpoints must retain certificates")
                                        .isNotNull();
                                assertThat(endpoint.hasEndpoint())
                                        .as("publicize=false should omit service endpoints")
                                        .isFalse();
                            });
                    latestConfig.set(config);
                }),
                prepareFakeUpgrade(),
                upgradeToNextConfigVersion(PUBLICIZE_ENABLED),
                withOpContext((spec, opLog) -> {
                    final var nodes =
                            List.copyOf(spec.subProcessNetworkOrThrow().nodes());
                    assertThat(nodes)
                            .as("Publicize enablement should keep the four-node roster intact")
                            .hasSize(4);
                    final var priorNodes = requireNonNull(activeNodes.get(), "Prior node metadata required");
                    assertThat(nodes.stream().map(HederaNode::getNodeId).toList())
                            .as("Roster should be unchanged when only toggling publicize")
                            .containsExactlyInAnyOrderElementsOf(priorNodes.stream()
                                    .map(HederaNode::getNodeId)
                                    .toList());
                    activeNodes.set(nodes);
                    final var priorConfig = requireNonNull(latestConfig.get(), "Latest configuration required");
                    final var priorTimestamp = configTimestamp(priorConfig);
                    final var config = fetchLedgerConfiguration(
                            nodes,
                            candidate -> configTimestamp(candidate).isAfter(priorTimestamp),
                            "advance after roster change");
                    assertLedgerIdStable(priorConfig, config);
                    assertTimestampAdvanced(priorConfig, config);
                    final var endpoints = config.endpoints();
                    assertThat(endpoints)
                            .as("publicize=true should expose endpoint metadata for each node")
                            .hasSize(nodes.size());
                    nodes.forEach(node -> assertThat(endpoints)
                            .as("Expected CLPR endpoint with network address for node {}", node.getNodeId())
                            .anySatisfy(endpoint -> assertEndpointMatchesNode(endpoint, node)));
                    latestConfig.set(config);
                }));
    }

    @DisplayName("Update message queue metadata works")
    @Order(3)
    @HapiTest
    final Stream<DynamicTest> handleMessageQueue() {
        /*
         * This test verifies update and get message queue metadata endpoints.
         */
        AtomicReference<HederaNode> targetNode = new AtomicReference<>();
        AtomicReference<ClprLedgerId> remoteLedgerIdRef = new AtomicReference<>();
        AtomicReference<ClprMessageQueueMetadata> fetchResult = new AtomicReference<>();
        final Supplier<ClprMessageQueueMetadata> localMessageQueueMetadataSupplier =
                () -> ClprMessageQueueMetadata.newBuilder()
                        .receivedMessageId(0)
                        .sentMessageId(0)
                        .nextMessageId(1)
                        .ledgerId(remoteLedgerIdRef.get())
                        .build();
        return hapiTest(
                // Specify node 0 as the target node to illicit local ledger responses
                doingContextual(spec -> {
                    final var tNode = spec.getNetworkNodes().getFirst();
                    assertThat(tNode).isNotNull();
                    targetNode.set(tNode);
                    remoteLedgerIdRef.set(ClprLedgerId.newBuilder()
                            .ledgerId(Bytes.wrap("Mock remote ledger ID".getBytes()))
                            .build());
                }),
                // send update message queue metadata transaction for a remote ledger
                updateMessageQueueMetadataForLedger(
                        targetNode, remoteLedgerIdRef::get, localMessageQueueMetadataSupplier),
                // try to fetch message queue metadata for the remote ledger
                fetchMessageQueueMetadataForLedger(targetNode, remoteLedgerIdRef::get, fetchResult),

                // validate the result
                doingContextual(spec -> {
                    final var remoteLedgerId = requireNonNull(remoteLedgerIdRef.get(), "Remote ledger id required");
                    final var result = fetchResult.get();
                    assertThat(result).as("Expected message queue metadata").isNotNull();
                    assertThat(result.ledgerId()).isEqualTo(remoteLedgerId);
                    assertThat(result.receivedMessageId()).isEqualTo(0);
                    assertThat(result.sentMessageId()).isEqualTo(0);
                    assertThat(result.nextMessageId()).isEqualTo(1);
                    assertThat(result.receivedRunningHash().length()).isGreaterThan(0);
                    assertThat(result.sentRunningHash().length()).isGreaterThan(0);
                }));
    }

    @DisplayName("Process message bundle works")
    @Order(4)
    @HapiTest
    final Stream<DynamicTest> handleProcessMessageBundle() {
        /*
         * Small smoke test for process/get message endpoints:
         * - submits a valid canonical inbound request bundle for a 32-byte remote ledger id
         * - verifies queue receive cursor advances
         * - verifies one outbound message-reply is available for pull
         */
        AtomicReference<HederaNode> targetNode = new AtomicReference<>();
        AtomicReference<ClprLedgerId> remoteLedgerIdRef = new AtomicReference<>();
        AtomicReference<ClprMessageBundle> bundleToProcess = new AtomicReference<>();
        AtomicReference<ClprMessageBundle> fetchResult = new AtomicReference<>();
        final AtomicReference<ContractID> middlewareIdRef = new AtomicReference<>();
        final AtomicReference<ContractID> echoAppIdRef = new AtomicReference<>();
        final byte[] remoteLedgerBytes = new byte[32];
        remoteLedgerBytes[31] = 0x2A;
        return hapiTest(
                // set target node
                doingContextual(spec -> {
                    final var tNode = spec.getNetworkNodes().getFirst();
                    assertThat(tNode).isNotNull();
                    targetNode.set(tNode);
                    remoteLedgerIdRef.set(ClprLedgerId.newBuilder()
                            .ledgerId(Bytes.wrap(remoteLedgerBytes))
                            .build());
                }),
                // ensure queue is initialized
                updateMessageQueueMetadataForLedger(
                        targetNode, remoteLedgerIdRef::get, () -> ClprMessageQueueMetadata.newBuilder()
                                .receivedMessageId(0)
                                .sentMessageId(0)
                                .nextMessageId(1)
                                .ledgerId(remoteLedgerIdRef.get())
                                .build()),
                // deploy minimal middleware + destination app for queue callback
                withOpContext((spec, opLog) -> {
                    final var queueAddress = asHeadlongAddress(CLPR_QUEUE_SYSTEM_CONTRACT);
                    allRunFor(
                            spec,
                            uploadInitCode(CLPR_MIDDLEWARE, ECHO_APP),
                            contractCreate(CLPR_MIDDLEWARE, queueAddress, remoteLedgerBytes)
                                    .gas(8_000_000L)
                                    .exposingContractIdTo(middlewareIdRef::set));
                    final var middlewareAddress = asHeadlongAddress(
                            asAddress(requireNonNull(middlewareIdRef.get(), "Middleware id required")));
                    allRunFor(
                            spec,
                            contractCreate(ECHO_APP, middlewareAddress)
                                    .gas(3_000_000L)
                                    .exposingContractIdTo(echoAppIdRef::set));
                    final var echoAddress =
                            asHeadlongAddress(asAddress(requireNonNull(echoAppIdRef.get(), "Echo app id required")));
                    final var payerAddress = asHeadlongAddress(asAddress(asAccount(spec, 2)));
                    allRunFor(
                            spec,
                            contractCallWithFunctionAbi(
                                            asContractIdLiteral(
                                                    requireNonNull(middlewareIdRef.get(), "Middleware id required")),
                                            ABI_SET_TRUSTED_CALLBACK_CALLER,
                                            payerAddress)
                                    .gas(500_000L),
                            contractCallWithFunctionAbi(
                                            asContractIdLiteral(
                                                    requireNonNull(middlewareIdRef.get(), "Middleware id required")),
                                            ABI_REGISTER_LOCAL_APPLICATION,
                                            echoAddress)
                                    .gas(500_000L));
                }),
                // build a valid bundle with a canonical request payload
                doingContextual(spec -> {
                    final var node = targetNode.get();
                    try (final var client = createClient(node)) {
                        final var remoteLedgerId = requireNonNull(remoteLedgerIdRef.get(), "Remote ledger id required");
                        final var queue = awaitMessageQueueMetadata(client, remoteLedgerId);
                        final var sourceMiddlewareAddress = asHeadlongAddress(
                                asAddress(requireNonNull(middlewareIdRef.get(), "Middleware id required")));
                        final var destinationMiddlewareAddress = asHeadlongAddress(
                                asAddress(requireNonNull(middlewareIdRef.get(), "Middleware id required")));
                        final var destinationAppAddress = asHeadlongAddress(
                                asAddress(requireNonNull(echoAppIdRef.get(), "Echo app id required")));
                        final var payload = canonicalInboundRequestPayload(
                                remoteLedgerBytes,
                                sourceMiddlewareAddress,
                                destinationMiddlewareAddress,
                                destinationAppAddress,
                                "Hello CLPR".getBytes(StandardCharsets.UTF_8));
                        final var hashAfterProcessing =
                                ClprMessageUtils.nextRunningHash(payload, queue.receivedRunningHash());

                        final var messageId = queue.receivedMessageId() + 1;
                        final var lastKey = ClprMessageKey.newBuilder()
                                .messageId(messageId)
                                .ledgerId(remoteLedgerId)
                                .build();
                        final var lastValue = ClprMessageValue.newBuilder()
                                .payload(payload)
                                .runningHashAfterProcessing(hashAfterProcessing)
                                .build();
                        final var stateProof = ClprStateProofUtils.buildLocalClprStateProofWrapper(lastKey, lastValue);

                        bundleToProcess.set(ClprMessageBundle.newBuilder()
                                .ledgerId(remoteLedgerId)
                                .messages(List.of())
                                .stateProof(stateProof)
                                .build());
                    }
                }),

                // send process message bundle transaction
                processMessageBundle(targetNode, bundleToProcess::get),
                // wait for receive cursor to advance after processing the inbound payload
                doingContextual(spec -> {
                    final var node = targetNode.get();
                    try (final var client = createClient(node)) {
                        final var remoteLedgerId = requireNonNull(remoteLedgerIdRef.get(), "Remote ledger id required");
                        awaitMessageQueueMetadata(
                                client,
                                remoteLedgerId,
                                metadata -> metadata.receivedMessageId() >= 1,
                                "process inbound payload");
                    }
                }),

                // try to fetch message bundle
                fetchMessageBundleForLedger(
                        targetNode,
                        remoteLedgerIdRef::get,
                        fetchResult,
                        1,
                        bundle ->
                                bundle.messages() != null && !bundle.messages().isEmpty(),
                        "contain at least one outbound message"),

                // validate the result
                doingContextual(spec -> {
                    final var result = fetchResult.get();
                    assertThat(result).isNotNull();
                    final var firstMsg = result.messages().getFirst();
                    assertThat(firstMsg).isNotNull();
                    assertThat(firstMsg.hasMessageReply()).isTrue();
                    assertThat(firstMsg.messageReply().messageReplyData().length())
                            .isGreaterThan(0);
                }));
    }

    private static ClprMessagePayload canonicalInboundRequestPayload(
            final byte[] sourceLedgerId,
            final Address sourceMiddlewareAddress,
            final Address destinationMiddlewareAddress,
            final Address destinationApplicationAddress,
            final byte[] applicationPayloadData) {
        final var amount = Tuple.of(BigInteger.ZERO, "HBAR");
        final var routeHeaderBytes = toArray(REQUEST_ROUTE_HEADER_TYPE.encode(
                Tuple.of(1, sourceLedgerId, sourceMiddlewareAddress, destinationMiddlewareAddress)));
        final var applicationMessage =
                Tuple.of(destinationApplicationAddress, new byte[32], amount, applicationPayloadData);
        final var connectorMessage = Tuple.of(true, amount, new byte[0]);
        final var balanceReport = Tuple.of(new byte[32], amount, amount, amount);
        final var middlewareMessage = Tuple.of(balanceReport, routeHeaderBytes);
        final var clprMessage = Tuple.of(
                sourceMiddlewareAddress, applicationMessage, new byte[32], connectorMessage, middlewareMessage);
        final var enqueueCallData = toArray(ENQUEUE_MESSAGE.encodeCall(Tuple.singleton(clprMessage)));
        final var canonicalMessageData =
                Arrays.copyOfRange(enqueueCallData, ABI_SELECTOR_LENGTH, enqueueCallData.length);
        return ClprMessagePayload.newBuilder()
                .message(ClprMessage.newBuilder()
                        .messageData(Bytes.wrap(canonicalMessageData))
                        .build())
                .build();
    }

    private static byte[] toArray(final ByteBuffer byteBuffer) {
        final var duplicate = byteBuffer.duplicate();
        final var bytes = new byte[duplicate.remaining()];
        duplicate.get(bytes);
        return bytes;
    }

    private static ClprLedgerConfiguration fetchLedgerConfiguration(final List<HederaNode> nodes) {
        return fetchLedgerConfiguration(nodes, candidate -> true, "be available");
    }

    private static ClprLedgerConfiguration fetchLedgerConfiguration(
            final List<HederaNode> nodes, final Predicate<ClprLedgerConfiguration> predicate, final String reason) {
        final var deadline = Instant.now().plus(Duration.ofMinutes(2));
        ClprLedgerConfiguration lastSeen = null;
        do {
            for (final var node : nodes) {
                final var config = tryFetchLedgerConfiguration(node);
                if (config != null) {
                    lastSeen = config;
                    if (predicate.test(config)) {
                        return config;
                    }
                }
            }
            try {
                Thread.sleep(1_000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        } while (Instant.now().isBefore(deadline));
        final var lastTimestamp =
                lastSeen == null ? "<none>" : configTimestamp(lastSeen).toString();
        throw new IllegalStateException(
                "Unable to fetch CLPR ledger configuration that would " + reason + " (last=" + lastTimestamp + ")");
    }

    private static void assertLedgerIdStable(
            final ClprLedgerConfiguration baseline, final ClprLedgerConfiguration candidate) {
        requireNonNull(candidate);
        final var expectedLedgerId = baseline.ledgerId().ledgerId();
        assertThat(candidate.ledgerId().ledgerId())
                .as("LedgerId must remain stable across roster changes")
                .isEqualTo(expectedLedgerId);
    }

    private static void assertTimestampAdvanced(
            final ClprLedgerConfiguration baseline, final ClprLedgerConfiguration candidate) {
        assertThat(configTimestamp(candidate))
                .as("Ledger configuration timestamp must advance after roster change")
                .isAfter(configTimestamp(baseline));
    }

    private static Instant configTimestamp(final ClprLedgerConfiguration config) {
        return Instant.ofEpochSecond(
                config.timestamp().seconds(), config.timestamp().nanos());
    }

    private static ClprLedgerConfiguration tryFetchLedgerConfiguration(final HederaNode node) {
        try {
            try (final var client = createClient(node)) {
                final var proof = client.getConfiguration();
                if (proof == null) {
                    return null;
                }
                return ClprStateProofUtils.extractConfiguration(proof);
            }
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw new IllegalStateException("Unable to fetch CLPR ledger configuration", e);
        }
    }

    private static void assertEndpointMatchesNode(final ClprEndpoint endpoint, final HederaNode node) {
        assertThat(endpoint.hasEndpoint())
                .as("Endpoint metadata should include a service endpoint")
                .isTrue();
        final var serviceEndpoint = endpoint.endpoint();
        assertThat(endpoint.signingCertificate() == null)
                .as("CLPR endpoints must advertise a signing certificate for node {}", node.getNodeId())
                .isFalse();
        assertThat(serviceEndpoint.port())
                .as("CLPR endpoint should use node {} gRPC port", node.getNodeId())
                .isEqualTo(node.getGrpcPort());
        assertThat(ipV4Of(serviceEndpoint))
                .as("CLPR endpoint must advertise the node {} host", node.getNodeId())
                .isEqualTo(node.getHost());
    }

    private static String ipV4Of(final ServiceEndpoint endpoint) {
        try {
            return InetAddress.getByAddress(endpoint.ipAddressV4().toByteArray())
                    .getHostAddress();
        } catch (UnknownHostException e) {
            throw new IllegalStateException("CLPR endpoint carried an invalid IPv4 address", e);
        }
    }

    static ClprClient createClient(final HederaNode node) {
        try {
            final var pbjEndpoint = ServiceEndpoint.newBuilder()
                    .ipAddressV4(
                            Bytes.wrap(InetAddress.getByName(node.getHost()).getAddress()))
                    .port(node.getGrpcPort())
                    .build();
            return new ClprClientImpl(pbjEndpoint);
        } catch (UnknownHostException e) {
            throw new IllegalStateException("Failed to create CLPR client", e);
        }
    }

    private static String asContractIdLiteral(final ContractID contractId) {
        return contractId.getShardNum() + "." + contractId.getRealmNum() + "." + contractId.getContractNum();
    }

    private static Address asHeadlongAddress(final String hexAddress) {
        return com.hedera.services.bdd.spec.transactions.contract.HapiParserUtil.asHeadlongAddress(hexAddress);
    }

    private static Address asHeadlongAddress(final byte[] address) {
        return com.hedera.services.bdd.spec.transactions.contract.HapiParserUtil.asHeadlongAddress(address);
    }

    private static ContextualActionOp updateMessageQueueMetadataForLedger(
            final AtomicReference<HederaNode> node,
            final Supplier<ClprLedgerId> ledgerIdSupplier,
            final Supplier<ClprMessageQueueMetadata> metadataSupplier) {
        return doingContextual(spec -> {
            try (final var client = createClient(node.get())) {
                final var payer = asAccount(spec, 2);
                final var ledgerId = ledgerIdSupplier.get();
                final var proof = ClprStateProofUtils.buildLocalClprStateProofWrapper(metadataSupplier.get());
                client.updateMessageQueueMetadata(toPbj(payer), node.get().getAccountId(), ledgerId, proof);
            }
        });
    }

    private static ContextualActionOp processMessageBundle(
            final AtomicReference<HederaNode> node, final Supplier<ClprMessageBundle> messageBundleSupplier) {

        return doingContextual(spec -> {
            try (final var client = createClient(node.get())) {
                final var config = tryFetchLedgerConfiguration(node.get());
                final var payer = asAccount(spec, 2);
                client.submitProcessMessageBundleTxn(
                        toPbj(payer), node.get().getAccountId(), config.ledgerId(), messageBundleSupplier.get());
            }
        });
    }

    private static ContextualActionOp fetchMessageBundleForLedger(
            final AtomicReference<HederaNode> node,
            final Supplier<ClprLedgerId> ledgerIdSupplier,
            final AtomicReference<ClprMessageBundle> exposingMessageBundle,
            final int maxNumMsg) {
        return fetchMessageBundleForLedger(
                node, ledgerIdSupplier, exposingMessageBundle, maxNumMsg, bundle -> true, "be available");
    }

    private static ContextualActionOp fetchMessageBundleForLedger(
            final AtomicReference<HederaNode> node,
            final Supplier<ClprLedgerId> ledgerIdSupplier,
            final AtomicReference<ClprMessageBundle> exposingMessageBundle,
            final int maxNumMsg,
            final Predicate<ClprMessageBundle> predicate,
            final String reason) {
        return doingContextual(spec -> {
            try (final var client = createClient(node.get())) {
                final var messageBundle =
                        awaitMessageBundle(client, ledgerIdSupplier.get(), maxNumMsg, predicate, reason);
                exposingMessageBundle.set(requireNonNull(messageBundle, "Message bundle required"));
            }
        });
    }

    private static ContextualActionOp fetchMessageQueueMetadataForLedger(
            final AtomicReference<HederaNode> node,
            final Supplier<ClprLedgerId> ledgerIdSupplier,
            final AtomicReference<ClprMessageQueueMetadata> exposingMessageQueueMetadata) {
        return doingContextual(spec -> {
            try (final var client = createClient(node.get())) {
                final var ledgerId = ledgerIdSupplier.get();
                exposingMessageQueueMetadata.set(awaitMessageQueueMetadata(client, ledgerId));
            }
        });
    }

    private static ClprMessageQueueMetadata awaitMessageQueueMetadata(
            final ClprClient client, final ClprLedgerId ledgerId) {
        return awaitMessageQueueMetadata(client, ledgerId, metadata -> true, "be available");
    }

    private static ClprMessageQueueMetadata awaitMessageQueueMetadata(
            final ClprClient client,
            final ClprLedgerId ledgerId,
            final Predicate<ClprMessageQueueMetadata> predicate,
            final String reason) {
        final var deadline = Instant.now().plus(CLPR_QUERY_TIMEOUT);
        do {
            final var proof = client.getMessageQueueMetadata(ledgerId);
            if (proof != null) {
                final var metadata = ClprStateProofUtils.extractMessageQueueMetadata(proof);
                if (predicate.test(metadata)) {
                    return metadata;
                }
            }
            sleepQuietly(CLPR_QUERY_POLL_INTERVAL);
        } while (Instant.now().isBefore(deadline));
        throw new IllegalStateException(
                "Timed out waiting for message queue metadata for ledger " + ledgerId + " to " + reason);
    }

    private static ClprMessageBundle awaitMessageBundle(
            final ClprClient client, final ClprLedgerId ledgerId, final int maxNumMsg) {
        return awaitMessageBundle(client, ledgerId, maxNumMsg, bundle -> true, "be available");
    }

    private static ClprMessageBundle awaitMessageBundle(
            final ClprClient client,
            final ClprLedgerId ledgerId,
            final int maxNumMsg,
            final Predicate<ClprMessageBundle> predicate,
            final String reason) {
        final var deadline = Instant.now().plus(CLPR_QUERY_TIMEOUT);
        do {
            final var bundle = client.getMessages(ledgerId, maxNumMsg, 1000);
            if (bundle != null && predicate.test(bundle)) {
                return bundle;
            }
            sleepQuietly(CLPR_QUERY_POLL_INTERVAL);
        } while (Instant.now().isBefore(deadline));
        throw new IllegalStateException(
                "Timed out waiting for message bundle for ledger " + ledgerId + " to " + reason);
    }

    private static void sleepQuietly(final Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for CLPR metadata", e);
        }
    }
}
