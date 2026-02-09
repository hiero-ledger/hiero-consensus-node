// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.interledger;

import static com.hedera.services.bdd.junit.hedera.NodeSelector.byNodeId;
import static com.hedera.services.bdd.junit.hedera.utils.NetworkUtils.CLASSIC_NODE_NAMES;
import static com.hedera.services.bdd.junit.hedera.utils.NetworkUtils.classicFeeCollectorIdFor;
import static com.hedera.services.bdd.junit.hedera.utils.NetworkUtils.nodeIdsFrom;
import static com.hedera.services.bdd.junit.hedera.utils.WorkingDirUtils.gossipCaCertificateForNodeId;
import static com.hedera.services.bdd.spec.HapiSpec.customizedHapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.nodeCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.nodeDelete;
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
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.hiero.hapi.interledger.state.clpr.protoc.ClprEndpoint;
import org.hiero.hapi.interledger.state.clpr.protoc.ClprLedgerConfiguration;
import org.hiero.interledger.clpr.ClprStateProofUtils;
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
            assertThat(config.getLedgerId().getLedgerId())
                    .as("Genesis CLPR ledger id must be populated")
                    .isNotEmpty();
            assertThat(config.getEndpointsList())
                    .as("Genesis CLPR ledger configuration must include endpoints")
                    .isNotEmpty()
                    .allSatisfy(endpoint -> assertThat(endpoint.getSigningCertificate())
                            .as("CLPR endpoints must advertise signing certificates")
                            .isNotEmpty());
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
                    final var endpoints = config.getEndpointsList();
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
        final var expectedLedgerId = baseline.getLedgerId().getLedgerId();
        assertThat(candidate.getLedgerId().getLedgerId())
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
                config.getTimestamp().getSeconds(), config.getTimestamp().getNanos());
    }

    private static ClprLedgerConfiguration tryFetchLedgerConfiguration(final HederaNode node) {
        try {
            final var pbjEndpoint = com.hedera.hapi.node.base.ServiceEndpoint.newBuilder()
                    .ipAddressV4(
                            Bytes.wrap(InetAddress.getByName(node.getHost()).getAddress()))
                    .port(node.getGrpcPort())
                    .build();
            try (final var client = new ClprClientImpl(pbjEndpoint)) {
                final var proof = client.getConfiguration();
                if (proof == null) {
                    return null;
                }
                final var pbjConfig = ClprStateProofUtils.extractConfiguration(proof);
                final var configBytes =
                        org.hiero.hapi.interledger.state.clpr.ClprLedgerConfiguration.PROTOBUF.toBytes(pbjConfig);
                return ClprLedgerConfiguration.parseFrom(configBytes.toByteArray());
            }
        } catch (UnknownHostException
                | com.google.protobuf.InvalidProtocolBufferException
                | IllegalArgumentException
                | IllegalStateException e) {
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
}
