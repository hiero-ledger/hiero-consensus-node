// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.interledger;

import static com.hedera.services.bdd.spec.HapiSpec.customizedHapiTest;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.blockingOrder;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.hapi.node.base.ServiceEndpoint;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.TestTags;
import com.hedera.services.bdd.junit.hedera.HederaNode;
import com.hedera.services.bdd.junit.hedera.NodeSelector;
import com.hedera.services.bdd.junit.hedera.subprocess.SubProcessNetwork;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoCreate;
import com.hedera.services.bdd.spec.transactions.node.HapiNodeCreate;
import com.hedera.services.bdd.spec.transactions.node.HapiNodeDelete;
import com.hedera.services.bdd.spec.utilops.upgrade.AddNodeOp;
import com.hedera.services.bdd.spec.utilops.upgrade.RemoveNodeOp;
import com.hedera.services.bdd.suites.hip869.NodeCreateTest;
import com.hedera.services.bdd.suites.regression.system.LifecycleTest;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.security.cert.CertificateEncodingException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.hiero.hapi.interledger.state.clpr.ClprEndpoint;
import org.hiero.hapi.interledger.state.clpr.ClprLedgerConfiguration;
import org.hiero.hapi.interledger.state.clpr.ClprLedgerId;
import org.hiero.interledger.clpr.impl.client.ClprClientImpl;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(TestTags.CLPR)
public class ClprSuite extends AbstractClprSuite implements LifecycleTest {
    @org.junit.jupiter.api.BeforeAll
    static void enableClprForSuite() {
        // Ensure CLPR is enabled before subprocess networks are provisioned
        System.setProperty("clpr.clprEnabled", "true");
        System.setProperty("clpr.devModeEnabled", "true");
        System.setProperty("clpr.publicizeNetworkAddresses", "true");
        System.setProperty("clpr.connectionFrequency", "500");
    }

    @org.junit.jupiter.api.BeforeEach
    void initDefaults() {
        setConfigDefaults();
    }

    private static final Duration CONFIG_APPLY_TIMEOUT = Duration.ofSeconds(150);
    private static final Duration POLL_DELAY = Duration.ofMillis(200);


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
        final var initialRoster = new AtomicReference<List<Long>>();
        final var baselineConfig = new AtomicReference<ClprLedgerConfiguration>();
        final var postPublicizeOffConfig = new AtomicReference<ClprLedgerConfiguration>();
        final var finalConfig = new AtomicReference<ClprLedgerConfiguration>();
        System.setProperty("clpr.clprEnabled", "true");
        final SpecOperation[] ops = new SpecOperation[] {
            withOpContext((spec, log) -> {
                final var network = spec.subProcessNetworkOrThrow();
                network.refreshClients();
                final var ids = network.nodes().stream()
                        .map(HederaNode::getNodeId)
                        .sorted()
                        .toList();
                if (ids.size() < 3) {
                    throw new IllegalStateException("CLPR roster test expects at least 3 nodes, found " + ids);
                }
                initialRoster.set(ids);
                log.info("Initial roster {}", ids);
            }),
            rosterShouldMatch(initialRoster::get),
            withOpContext((spec, log) -> {
                final var network = spec.subProcessNetworkOrThrow();
                final var expectedEndpoints = expectedServiceEndpoints(network);
                final var config = awaitConfiguration(network, null, null, null);
                Assertions.assertNotNull(config.ledgerId(), "ledgerId should be set");
                Assertions.assertFalse(config.endpoints().isEmpty(), "endpoints should be present");
                Assertions.assertEquals(
                        initialRoster.get().size(),
                        config.endpoints().size(),
                        "endpoint count should match roster size");
                assertEndpointsMatchExpected(config.endpoints(), expectedEndpoints);
                assertCertificatesPresentAndUnique(config.endpoints());
                baselineConfig.set(config);
            }),
            // Single roster-change upgrade: expect CLPR state change and metadata refresh
            rosterChangeUpgradeAndAssert(baselineConfig, postPublicizeOffConfig),
            publicizeOnUpgradeAndAssert(postPublicizeOffConfig, finalConfig)
        };

        return customizedHapiTest(Map.of("clpr.connectionFrequency", "500", "hapi.spec.network.size", "3"), ops);
    }

    private static void setPublicizeFlag(final SubProcessNetwork network, final boolean enable) {
        network.nodes().forEach(node -> {
            final var path = node.metadata().workingDirOrThrow().resolve("data/config/application.properties");
            try {
                var content = Files.readString(path);
                content = content.replaceAll(
                        "clpr\\.publicizeNetworkAddresses\\s*=\\s*.*",
                        "clpr.publicizeNetworkAddresses=" + Boolean.toString(enable));
                if (!content.contains("clpr.publicizeNetworkAddresses")) {
                    content = content + System.lineSeparator() + "clpr.publicizeNetworkAddresses="
                            + Boolean.toString(enable) + System.lineSeparator();
                }
                Files.writeString(path, content);
            } catch (final IOException e) {
                throw new IllegalStateException(
                        "Failed to update application.properties for node " + node.getNodeId(), e);
            }
        });
    }

    private SpecOperation rosterChangeUpgradeAndAssert(
            final AtomicReference<ClprLedgerConfiguration> baselineConfig,
            final AtomicReference<ClprLedgerConfiguration> postPublicizeOffConfig) {
        final var nodeIdToRemove = new AtomicReference<Long>();
        final var nodeIdToAdd = new AtomicReference<Long>();
        final var newNodeAccount = new AtomicReference<com.hederahashgraph.api.proto.java.AccountID>();
        final var gossipEndpoints = new AtomicReference<List<com.hederahashgraph.api.proto.java.ServiceEndpoint>>();
        final var grpcEndpoint = new AtomicReference<com.hederahashgraph.api.proto.java.ServiceEndpoint>();
        final var expectedRoster = new AtomicReference<List<Long>>();
        final var newNodeCert = new AtomicReference<byte[]>();
        return blockingOrder(
                withOpContext((spec, log) -> {
                    final var network = spec.subProcessNetworkOrThrow();
                    final var ids = network.nodes().stream()
                            .map(HederaNode::getNodeId)
                            .sorted()
                            .toList();
                    final var chosenRemove = ids.stream()
                            .filter(id -> id != 0L)
                            .findFirst()
                            .orElseThrow(() -> new IllegalStateException("No removable node found"));
                    final var nextId =
                            ids.stream().mapToLong(Long::longValue).max().orElse(0L) + 1;
                    nodeIdToRemove.set(chosenRemove);
                    nodeIdToAdd.set(nextId);
                    gossipEndpoints.set(network.gossipEndpointsForNextNodeId());
                    grpcEndpoint.set(network.grpcEndpointForNextNodeId());
                    newNodeCert.set(encodeCert());
                    expectedRoster.set(
                            Stream.concat(ids.stream().filter(id -> !id.equals(chosenRemove)), Stream.of(nextId))
                                    .sorted()
                                    .toList());
                }),
                new HapiCryptoCreate("clprNewNodeAccount")
                        .payingWith(GENESIS)
                        .balance(ONE_HBAR)
                        .exposingCreatedIdTo(newNodeAccount::set),
                withOpContext((spec, log) -> {
                    final var pbjId = com.hedera.node.app.hapi.utils.CommonPbjConverters.toPbj(newNodeAccount.get());
                    spec.subProcessNetworkOrThrow().updateNodeAccount(nodeIdToAdd.get(), pbjId);
                }),
                sourcing(() -> new HapiNodeDelete(String.valueOf(nodeIdToRemove.get())).payingWith(GENESIS)),
                sourcing(() -> new HapiNodeCreate("clpr-node-" + nodeIdToAdd.get(), "clprNewNodeAccount")
                        .payingWith(GENESIS)
                        .description("clpr-node-" + nodeIdToAdd.get())
                        .serviceEndpoint(List.of(requireNonNull(grpcEndpoint.get(), "grpc endpoint not set")))
                        .gossipEndpoint(requireNonNull(gossipEndpoints.get(), "gossip endpoints not set"))
                        .adminKey(GENESIS)
                        .gossipCaCertificate(requireNonNull(newNodeCert.get(), "new node cert not set"))),
                withOpContext((spec, log) -> {
                    setPublicizeFlag(spec.subProcessNetworkOrThrow(), false);
                    log.info("Set clpr.publicizeNetworkAddresses=false for all nodes before roster-change upgrade");
                }),
                prepareFakeUpgrade(),
                withOpContext((spec, log) -> upgradeToNextConfigVersion(
                                Map.of(),
                                new RemoveNodeOp(NodeSelector.byNodeId(nodeIdToRemove.get())),
                                new AddNodeOp(nodeIdToAdd.get()))
                        .execFor(spec)),
                withOpContext((spec, log) -> {
                    final var network = spec.subProcessNetworkOrThrow();
                    network.refreshClients();
                    final var updatedConfig = awaitConfiguration(
                            network,
                            baselineConfig.get().ledgerId(),
                            null,
                            toInstant(baselineConfig.get().timestampOrThrow()));
                    Assertions.assertEquals(
                            baselineConfig.get().ledgerId(),
                            updatedConfig.ledgerId(),
                            "ledgerId must remain stable after roster change");
                    Assertions.assertTrue(
                            toInstant(updatedConfig.timestampOrThrow())
                                    .isAfter(toInstant(baselineConfig.get().timestampOrThrow())),
                            "config timestamp should advance after roster change");
                    Assertions.assertEquals(
                            expectedRoster.get().size(),
                            updatedConfig.endpoints().size(),
                            "endpoint count should match new roster size");
                    Assertions.assertTrue(
                            updatedConfig.endpoints().stream()
                                    .allMatch(ep -> ep.endpoint() == null
                                            || (ep.endpoint().ipAddressV4().length() == 0
                                                    && ep.endpoint().port() == 0)),
                            "endpoints should omit network data when publicize is false");
                    postPublicizeOffConfig.set(updatedConfig);
                }));
    }

    private SpecOperation publicizeOnUpgradeAndAssert(
            final AtomicReference<ClprLedgerConfiguration> postPublicizeOffConfig,
            final AtomicReference<ClprLedgerConfiguration> finalConfig) {
        return blockingOrder(
                withOpContext((spec, log) -> {
                    setPublicizeFlag(spec.subProcessNetworkOrThrow(), true);
                    log.info("Set clpr.publicizeNetworkAddresses=true for all nodes before final upgrade");
                }),
                prepareFakeUpgrade(),
                withOpContext(
                        (spec, log) -> upgradeToNextConfigVersion(Map.of()).execFor(spec)),
                withOpContext((spec, log) -> {
                    final var network = spec.subProcessNetworkOrThrow();
                    network.refreshClients();
                    final var minTs = toInstant(postPublicizeOffConfig.get().timestampOrThrow());
                    final var expectedEndpoints = expectedServiceEndpoints(network);
                    final var updatedConfig = awaitConfiguration(
                            network, postPublicizeOffConfig.get().ledgerId(), null, minTs.plusMillis(1));
                    Assertions.assertEquals(
                            postPublicizeOffConfig.get().ledgerId(),
                            updatedConfig.ledgerId(),
                            "ledgerId must remain stable across publicize toggle");
                    Assertions.assertEquals(
                            expectedEndpoints.size(),
                            updatedConfig.endpoints().size(),
                            "endpoint count should match roster size after publicize re-enable");
                    assertEndpointsMatchExpected(updatedConfig.endpoints(), expectedEndpoints);
                    Assertions.assertEquals(
                            certSet(postPublicizeOffConfig.get().endpoints()),
                            certSet(updatedConfig.endpoints()),
                            "certificates must remain stable across publicize toggles");
                    finalConfig.set(updatedConfig);
                }));
    }

    private static void assertStableConfig(
            final ClprLedgerConfiguration baselineConfig, final ClprLedgerConfiguration fetched) {
        Assertions.assertEquals(
                baselineConfig.ledgerId(), fetched.ledgerId(), "ledgerId must remain stable across restart");
        Assertions.assertFalse(
                toInstant(fetched.timestamp()).isBefore(toInstant(baselineConfig.timestamp())),
                "timestamp must not move backwards across restart");
        Assertions.assertEquals(
                certSet(baselineConfig.endpoints()),
                certSet(fetched.endpoints()),
                "endpoints/certificates must remain stable across restart");
    }

    private static byte[] encodeCert() {
        try {
            return NodeCreateTest.generateX509Certificates(1).getFirst().getEncoded();
        } catch (final CertificateEncodingException e) {
            throw new IllegalStateException("Failed to generate/encode X509 certificate for node create", e);
        }
    }

    private SpecOperation rosterShouldMatch(final Supplier<List<Long>> expectedIds) {
        return withOpContext((spec, opLog) -> {
            final var actualIds = spec.subProcessNetworkOrThrow().nodes().stream()
                    .map(HederaNode::getNodeId)
                    .toList();
            assertThat(actualIds).containsExactlyInAnyOrderElementsOf(expectedIds.get());
        });
    }

    private static ServiceEndpoint serviceEndpointFor(final HederaNode node) {
        try {
            final var addressBytes = InetAddress.getByName(node.getHost()).getAddress();
            return ServiceEndpoint.newBuilder()
                    .ipAddressV4(Bytes.wrap(addressBytes))
                    .port(node.getGrpcPort())
                    .build();
        } catch (final UnknownHostException e) {
            throw new IllegalStateException("Unable to resolve node host for CLPR endpoint", e);
        }
    }

    private static List<ServiceEndpoint> expectedServiceEndpoints(final SubProcessNetwork network) {
        return network.nodes().stream().map(ClprSuite::serviceEndpointFor).toList();
    }

    private static void assertEndpointsMatchExpected(
            final List<ClprEndpoint> actual, final List<ServiceEndpoint> expected) {
        Assertions.assertFalse(actual.isEmpty(), "CLPR endpoints must be present");
        actual.forEach(ep -> {
            Assertions.assertNotNull(ep.endpoint(), "endpoint should be present when publicize is enabled");
            Assertions.assertTrue(ep.endpoint().ipAddressV4().length() > 0, "endpoint ip should be set");
            Assertions.assertTrue(ep.endpoint().port() > 0, "endpoint port should be set");
        });
        final var actualSet = actual.stream().map(ClprEndpoint::endpoint).collect(java.util.stream.Collectors.toSet());
        final var expectedSet = Set.copyOf(expected);
        Assertions.assertEquals(expectedSet, actualSet, "endpoints must match expected gRPC gateways");
    }

    private static void assertCertificatesPresentAndUnique(final List<ClprEndpoint> endpoints) {
        final var certs = certSet(endpoints);
        Assertions.assertEquals(endpoints.size(), certs.size(), "Each endpoint should provide a unique certificate");
        certs.forEach(cert -> Assertions.assertTrue(cert.length() > 0, "Certificates must be non-empty"));
    }

    private static ClprLedgerConfiguration awaitConfiguration(
            final SubProcessNetwork network,
            final ClprLedgerId expectedLedgerId,
            final Set<Bytes> expectedCerts,
            final Instant minTimestamp) {
        return awaitConfiguration(network, expectedLedgerId, expectedCerts, minTimestamp, CONFIG_APPLY_TIMEOUT);
    }

    private static ClprLedgerConfiguration awaitConfiguration(
            final SubProcessNetwork network,
            final ClprLedgerId expectedLedgerId,
            final Set<Bytes> expectedCerts,
            final Instant minTimestamp,
            final Duration timeout) {
        try (var client = new ClprClientImpl(serviceEndpointFor(network.nodes().getFirst()))) {
            final long deadlineNanos = System.nanoTime() + timeout.toNanos();
            ClprLedgerConfiguration current;
            String lastObserved = null;
            while (true) {
                current = expectedLedgerId == null
                        ? client.getConfiguration()
                        : client.getConfiguration(expectedLedgerId);
                final String summary = current == null ? "null" : summarize(current);
                if (!summary.equals(lastObserved)) {
                    System.out.printf("CLPR await: observed %s%n", summary);
                    lastObserved = summary;
                }
                if (isAcceptable(current, expectedLedgerId, expectedCerts, minTimestamp)) {
                    return current;
                }
                if (System.nanoTime() >= deadlineNanos) {
                    throw new IllegalStateException("Timed out waiting for CLPR configuration update");
                }
                Thread.sleep(POLL_DELAY.toMillis());
            }
        } catch (final UnknownHostException e) {
            throw new IllegalStateException("Unable to construct CLPR client", e);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while awaiting CLPR configuration", e);
        }
    }

    private static boolean isAcceptable(
            final ClprLedgerConfiguration current,
            final ClprLedgerId expectedLedgerId,
            final Set<Bytes> expectedCerts,
            final Instant minTimestamp) {
        if (current == null) {
            return false;
        }
        if (expectedLedgerId != null && !expectedLedgerId.equals(current.ledgerId())) {
            return false;
        }
        if (expectedCerts != null) {
            if (current.endpoints().isEmpty()) {
                return false;
            }
            if (!expectedCerts.equals(certSet(current.endpoints()))) {
                return false;
            }
        }
        if (minTimestamp != null) {
            final var ts = toInstant(current.timestamp());
            if (ts.isBefore(minTimestamp)) {
                return false;
            }
        }
        return true;
    }

    private static String summarize(final ClprLedgerConfiguration config) {
        return "ledger="
                + config.ledgerId().ledgerId()
                + " ts="
                + config.timestamp().seconds()
                + "."
                + config.timestamp().nanos()
                + " endpoints="
                + config.endpoints();
    }

    private static Set<Bytes> certSet(final List<ClprEndpoint> endpoints) {
        return endpoints.stream().map(ClprEndpoint::signingCertificate).collect(java.util.stream.Collectors.toSet());
    }

    private static Instant toInstant(final com.hedera.hapi.node.base.Timestamp timestamp) {
        return Instant.ofEpochSecond(timestamp.seconds(), timestamp.nanos());
    }
}
