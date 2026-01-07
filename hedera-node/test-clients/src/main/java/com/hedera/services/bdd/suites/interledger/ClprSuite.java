// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.interledger;

import static com.hedera.node.app.hapi.utils.CommonPbjConverters.toPbj;
import static com.hedera.services.bdd.junit.hedera.NodeSelector.byNodeId;
import static com.hedera.services.bdd.junit.hedera.utils.AddressBookUtils.CLASSIC_NODE_NAMES;
import static com.hedera.services.bdd.junit.hedera.utils.AddressBookUtils.classicFeeCollectorIdFor;
import static com.hedera.services.bdd.junit.hedera.utils.AddressBookUtils.nodeIdsFrom;
import static com.hedera.services.bdd.junit.hedera.utils.WorkingDirUtils.VALID_CERT;
import static com.hedera.services.bdd.spec.HapiPropertySource.asAccount;
import static com.hedera.services.bdd.spec.HapiSpec.customizedHapiTest;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.nodeCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.nodeDelete;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sleepFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateCandidateRoster;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

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
import com.hederahashgraph.api.proto.java.ServiceEndpoint;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.hiero.hapi.interledger.state.clpr.ClprMessage;
import org.hiero.hapi.interledger.state.clpr.ClprMessageBundle;
import org.hiero.hapi.interledger.state.clpr.ClprMessageQueueMetadata;
import org.hiero.hapi.interledger.state.clpr.protoc.ClprEndpoint;
import org.hiero.hapi.interledger.state.clpr.protoc.ClprLedgerConfiguration;
import org.hiero.interledger.clpr.client.ClprClient;
import org.hiero.interledger.clpr.impl.client.ClprClientImpl;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;

@Tag(TestTags.CLPR)
@OrderedInIsolation
@HapiTestLifecycle
public class ClprSuite implements LifecycleTest {
    private static final Map<String, String> CLPR_OVERRIDES = Map.of("clpr.clprEnabled", "true");
    private static final long NODE_ID_TO_REMOVE = 3L;
    private static final long REPLACEMENT_NODE_ID = 4L;
    private static final String REPLACEMENT_NODE_NAME = CLASSIC_NODE_NAMES[(int) REPLACEMENT_NODE_ID];
    private static final Map<String, String> PUBLICIZE_DISABLED = Map.of("clpr.publicizeNetworkAddresses", "false");
    private static final Map<String, String> PUBLICIZE_ENABLED = Map.of("clpr.publicizeNetworkAddresses", "true");

    @BeforeAll
    static void beforeAll(final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(CLPR_OVERRIDES);
    }

    @Order(1)
    @DisplayName("Roster change upgrade refreshes CLPR ledger configuration")
    @HapiTest
    final Stream<DynamicTest> rosterChangeUpgradeRefreshesLedgerConfig() {
        /*
         * What this test explicitly verifies:
         * 1) With clpr.publicizeNetworkAddresses=true (default), the fetched CLPR ledger configuration
         *    includes one endpoint per roster node, each with a network address and the node’s gRPC
         *    gateway port (not the gossip port). Certificates must be present and consistent.
         * 2) Remove one node and add a new node (with a fresh certificate); set
         *    clpr.publicizeNetworkAddresses=false on all nodes; perform a freeze/upgrade; then fetch
         *    the configuration. Expectations: same ledgerId, advanced timestamp, new roster size, and
         *    all endpoints omit network addresses/ports when publicize=false.
         * 3) Set clpr.publicizeNetworkAddresses=true again; perform another freeze/upgrade; then
         *    fetch the configuration. Expectations: same ledgerId, advanced timestamp, roster size is
         *    unchanged, endpoints contain network addresses/ports matching the gRPC gateways for the
         *    current roster nodes, and certificates remain stable.
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
                    final var endpoints = config.getEndpointsList();
                    assertThat(endpoints)
                            .as("CLPR ledger configuration should expose one endpoint per node")
                            .hasSize(nodes.size());
                    nodes.forEach(node -> assertThat(endpoints)
                            .as("Expected CLPR endpoint for node {}", node.getNodeId())
                            .anySatisfy(endpoint -> assertEndpointMatchesNode(endpoint, node)));
                }),
                nodeDelete(Long.toString(NODE_ID_TO_REMOVE)),
                nodeCreate(REPLACEMENT_NODE_NAME, classicFeeCollectorIdFor(REPLACEMENT_NODE_ID))
                        .adminKey(HapiSuite.DEFAULT_PAYER)
                        .description(REPLACEMENT_NODE_NAME)
                        .withAvailableSubProcessPorts()
                        .gossipCaCertificate(VALID_CERT),
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
                    final var config = fetchLedgerConfiguration(nodes);
                    final var baseline = requireNonNull(baselineConfig.get(), "Baseline configuration required");
                    assertLedgerIdStable(baseline, config);
                    assertTimestampAdvanced(baseline, config);
                    assertThat(config.getEndpointsList())
                            .as("CLPR endpoints should omit network addresses when publicize=false")
                            .hasSize(nodes.size())
                            .allSatisfy(endpoint -> {
                                assertThat(endpoint.getSigningCertificate())
                                        .as("CLPR endpoints must retain certificates")
                                        .isNotEmpty();
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
                    assertThat(nodes.stream().map(HederaNode::getNodeId).toList())
                            .as("Node {} should remain replaced by {}", NODE_ID_TO_REMOVE, REPLACEMENT_NODE_ID)
                            .doesNotContain(NODE_ID_TO_REMOVE)
                            .contains(REPLACEMENT_NODE_ID);
                    activeNodes.set(nodes);
                    final var config = fetchLedgerConfiguration(nodes);
                    final var priorConfig = requireNonNull(latestConfig.get(), "Latest configuration required");
                    assertLedgerIdStable(priorConfig, config);
                    assertTimestampAdvanced(priorConfig, config);
                    final var endpoints = config.getEndpointsList();
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
    @Order(2)
    @HapiTest
    final Stream<DynamicTest> handleMessageQueue() {
        /*
         * This test verify that `update` and `get` massage queue metadata endpoints are working.
         * Since the transaction handler doesn't have any validations yet, it will simply store the metadata in to sate.
         */
        AtomicReference<HederaNode> targetNode = new AtomicReference<>();
        AtomicReference<ClprMessageQueueMetadata> fetchResult = new AtomicReference<>();
        ClprMessageQueueMetadata localMessageQueueMetadata = ClprMessageQueueMetadata.newBuilder()
                .receivedMessageId(0)
                .sentMessageId(0)
                .nextOutgoingMessageId(1)
                .ledgerShortId(0)
                .build();
        return hapiTest(
                // set target node to use with the CLPR client
                doingContextual(spec -> {
                    final var tNode = spec.getNetworkNodes().getFirst();
                    assertThat(tNode).isNotNull();
                    targetNode.set(tNode);
                }),
                // send update local message queue metadata transaction
                updateMessageQueueMetadata(targetNode, localMessageQueueMetadata),
                // try to fetch local message queue metadata
                sleepFor(5000),
                fetchMessageQueueMetadata(targetNode, fetchResult),

                // validate the result
                doingContextual(spec -> {
                    assertThat(fetchResult.get()).isEqualTo(localMessageQueueMetadata);
                }));
    }

    @DisplayName("Process message bundle works")
    @Order(3)
    @HapiTest
    final Stream<DynamicTest> handleProcessMessageBundle() {
        /*
         * This test verify that `process` and `get` massages endpoints are working.
         * Since the transaction handler doesn't have any validations yet, it will simply store the messages in to sate.
         */
        AtomicReference<HederaNode> targetNode = new AtomicReference<>();
        AtomicReference<ClprMessageBundle> fetchResult = new AtomicReference<>();
        Bytes msgData = Bytes.wrap("Hello CLPR".getBytes());
        ClprMessage msg = ClprMessage.newBuilder().messageData(msgData).build();
        ClprMessageBundle bundleToProcess =
                ClprMessageBundle.newBuilder().messages(msg).build();
        return hapiTest(
                // set target node
                doingContextual(spec -> {
                    final var tNode = spec.getNetworkNodes().getFirst();
                    assertThat(tNode).isNotNull();
                    targetNode.set(tNode);
                }),

                // send process message bundle transaction
                processMessageBundle(targetNode, bundleToProcess),

                // try to fetch message bundle
                sleepFor(5000),
                fetchMessageBundle(targetNode, fetchResult),

                // validate the result
                doingContextual(spec -> {
                    assertThat(fetchResult.get()).isEqualTo(bundleToProcess);
                    final var firstMsg = fetchResult.get().messages().getFirst();
                    assertThat(firstMsg).isNotNull();
                    final var resultMsgData = firstMsg.messageData();
                    assertThat(resultMsgData.asUtf8String(0, resultMsgData.length()))
                            .isEqualTo("Hello CLPR");
                }));
    }

    private static ClprLedgerConfiguration fetchLedgerConfiguration(final List<HederaNode> nodes) {
        final var deadline = Instant.now().plus(Duration.ofMinutes(1));
        do {
            for (final var node : nodes) {
                final var config = tryFetchLedgerConfiguration(node);
                if (config != null) {
                    return config;
                }
            }
            try {
                Thread.sleep(1_000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        } while (Instant.now().isBefore(deadline));
        throw new IllegalStateException("Unable to fetch CLPR ledger configuration from any node");
    }

    private static void assertLedgerIdStable(
            final ClprLedgerConfiguration baseline, final ClprLedgerConfiguration candidate) {
        requireNonNull(candidate);
        final var expectedLedgerId = baseline.getLedgerId().getLedgerId();
        assertThat(candidate.getLedgerId().getLedgerId())
                .as("LedgerId must remain stable across roster changes")
                .isEqualTo(expectedLedgerId);
    }

    private static void assertTimestampAdvanced(
            final ClprLedgerConfiguration baseline, final ClprLedgerConfiguration candidate) {
        final var baselineInstant = Instant.ofEpochSecond(
                baseline.getTimestamp().getSeconds(), baseline.getTimestamp().getNanos());
        final var candidateInstant = Instant.ofEpochSecond(
                candidate.getTimestamp().getSeconds(), candidate.getTimestamp().getNanos());
        assertThat(candidateInstant)
                .as("Ledger configuration timestamp must advance after roster change")
                .isAfter(baselineInstant);
    }

    private static ClprLedgerConfiguration tryFetchLedgerConfiguration(final HederaNode node) {
        try (final var client = createClient(node)) {
            final var pbjConfig = client.getConfiguration();
            if (pbjConfig == null) {
                return null;
            }
            final var configBytes =
                    org.hiero.hapi.interledger.state.clpr.ClprLedgerConfiguration.PROTOBUF.toBytes(pbjConfig);
            return ClprLedgerConfiguration.parseFrom(configBytes.toByteArray());
        } catch (com.google.protobuf.InvalidProtocolBufferException e) {
            throw new IllegalStateException("Unable to fetch CLPR ledger configuration", e);
        }
    }

    private static void assertEndpointMatchesNode(final ClprEndpoint endpoint, final HederaNode node) {
        assertThat(endpoint.hasEndpoint())
                .as("Endpoint metadata should include a service endpoint")
                .isTrue();
        final var serviceEndpoint = endpoint.getEndpoint();
        assertThat(endpoint.getSigningCertificate().isEmpty())
                .as("CLPR endpoints must advertise a signing certificate for node {}", node.getNodeId())
                .isFalse();
        assertThat(serviceEndpoint.getPort())
                .as("CLPR endpoint should use node {} gRPC port", node.getNodeId())
                .isEqualTo(node.getGrpcPort());
        assertThat(ipV4Of(serviceEndpoint))
                .as("CLPR endpoint must advertise the node {} host", node.getNodeId())
                .isEqualTo(node.getHost());
    }

    private static String ipV4Of(final ServiceEndpoint endpoint) {
        try {
            return InetAddress.getByAddress(endpoint.getIpAddressV4().toByteArray())
                    .getHostAddress();
        } catch (UnknownHostException e) {
            throw new IllegalStateException("CLPR endpoint carried an invalid IPv4 address", e);
        }
    }

    private static ClprClient createClient(final HederaNode node) {
        try {
            final var pbjEndpoint = com.hedera.hapi.node.base.ServiceEndpoint.newBuilder()
                    .ipAddressV4(
                            Bytes.wrap(InetAddress.getByName(node.getHost()).getAddress()))
                    .port(node.getGrpcPort())
                    .build();
            return new ClprClientImpl(pbjEndpoint);
        } catch (UnknownHostException e) {
            throw new IllegalStateException("Failed to create CLPR client", e);
        }
    }

    private static ContextualActionOp updateMessageQueueMetadata(
            final AtomicReference<HederaNode> node, final ClprMessageQueueMetadata clprMessageQueueMetadata) {

        return doingContextual(spec -> {
            try (final var client = createClient(node.get())) {
                final var payer = asAccount(spec, 2);
                client.updateMessageQueueMetadata(
                        toPbj(payer),
                        node.get().getAccountId(),
                        client.getConfiguration().ledgerId(),
                        clprMessageQueueMetadata);
            }
        });
    }

    private static ContextualActionOp processMessageBundle(
            final AtomicReference<HederaNode> node, final ClprMessageBundle messageBundle) {

        return doingContextual(spec -> {
            try (final var client = createClient(node.get())) {
                final var payer = asAccount(spec, 2);
                client.processMessageBundle(
                        toPbj(payer),
                        node.get().getAccountId(),
                        client.getConfiguration().ledgerId(),
                        messageBundle);
            }
        });
    }

    private static ContextualActionOp fetchMessageBundle(
            final AtomicReference<HederaNode> node, final AtomicReference<ClprMessageBundle> exposingMessageBundle) {
        return doingContextual(spec -> {
            try (final var client = createClient(node.get())) {
                final var messageBundle =
                        client.getMessages(client.getConfiguration().ledgerId(), 10, 1000);
                exposingMessageBundle.set(messageBundle);
            }
        });
    }

    private static ContextualActionOp fetchMessageQueueMetadata(
            final AtomicReference<HederaNode> node,
            final AtomicReference<ClprMessageQueueMetadata> exposingMessageQueueMetadata) {
        return doingContextual(spec -> {
            try (final var client = createClient(node.get())) {
                final var messageQueueMetadata =
                        client.getMessageQueueMetadata(client.getConfiguration().ledgerId());
                exposingMessageQueueMetadata.set(messageQueueMetadata);
            }
        });
    }
}
