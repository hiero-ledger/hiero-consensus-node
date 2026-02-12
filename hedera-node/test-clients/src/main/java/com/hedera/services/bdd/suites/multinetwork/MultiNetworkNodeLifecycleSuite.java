// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.multinetwork;

import static com.hedera.services.bdd.junit.hedera.NodeSelector.byNodeId;
import static com.hedera.services.bdd.junit.hedera.utils.NetworkUtils.nodeIdsFrom;
import static com.hedera.services.bdd.junit.hedera.utils.WorkingDirUtils.gossipCaCertificateForNodeId;
import static com.hedera.services.bdd.spec.HapiSpec.multiNetworkHapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.nodeCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.nodeDelete;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateCandidateRoster;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static java.util.stream.Collectors.toMap;
import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.node.app.hapi.utils.CommonPbjConverters;
import com.hedera.node.app.info.DiskStartupNetworks;
import com.hedera.services.bdd.junit.MultiNetworkHapiTest;
import com.hedera.services.bdd.junit.OrderedInIsolation;
import com.hedera.services.bdd.junit.TestTags;
import com.hedera.services.bdd.junit.hedera.ExternalPath;
import com.hedera.services.bdd.junit.hedera.NodeMetadata;
import com.hedera.services.bdd.junit.hedera.subprocess.SubProcessNetwork;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import com.hedera.services.bdd.spec.utilops.FakeNmt;
import com.hedera.services.bdd.spec.utilops.UtilVerbs;
import com.hedera.services.bdd.suites.regression.system.LifecycleTest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * Exercises freeze-upgrade driven node removals across multiple networks and verifies roster changes.
 */
@OrderedInIsolation
@Tag(TestTags.MULTINETWORK)
public class MultiNetworkNodeLifecycleSuite implements LifecycleTest {
    private static final Pattern OVERRIDE_SCOPE_DIR_PATTERN = Pattern.compile("\\d+");

    /**
     * Validates roster changes across multiple networks after freeze-driven upgrades.
     *
     * @param netA first subprocess network
     * @param netB second subprocess network
     * @param netC third subprocess network
     * @return dynamic tests representing the multi-network plan
     */
    @MultiNetworkHapiTest(
            networks = {
                @MultiNetworkHapiTest.Network(name = "NET_A", size = 4, firstGrpcPort = 27400),
                @MultiNetworkHapiTest.Network(name = "NET_B", size = 4, firstGrpcPort = 28400),
                @MultiNetworkHapiTest.Network(name = "NET_C", size = 4, firstGrpcPort = 29400)
            })
    @DisplayName("Multi-network freeze upgrade updates rosters")
    Stream<DynamicTest> nodeRosterChangesAcrossNetworks(
            final SubProcessNetwork netA, final SubProcessNetwork netB, final SubProcessNetwork netC) {
        /*
         * Test structure notes (keep in sync if changing):
         * 1) All three networks must be alive together before any node is deleted/added. This ensures the shared
         *    static HapiClients channel pools are populated for each network and avoids cross-network interference.
         * 2) Each network performs its own delete/add + freeze-upgrade sequence, but we do all three in one
         *    multi-network spec so the networks stay concurrent.
         * 3) After all upgrades, we re-verify each network’s roster to prove the freeze upgrade changed membership
         *    as intended and that the networks are still ACTIVE after the restarts.
         * 4) New node ids must use the next available id for the subprocess network (here, 4 for every network)
         *    to align with SubProcessNetwork’s port/account assignment. If you change the sizes, adjust expected ids.
         * 5) Do not clear global channel pools in refreshClients(); other networks may still be using them. If you
         *    need a full reset, call HapiClients.tearDown() explicitly at suite teardown, not mid-spec.
         */
        final List<Long> initialRoster = List.of(0L, 1L, 2L, 3L);
        final List<Long> expectedRosterA = List.of(0L, 2L, 3L, 4L);
        final List<Long> expectedRosterB = List.of(0L, 1L, 3L, 4L);
        final List<Long> expectedRosterC = List.of(0L, 1L, 2L, 4L);
        final var netAPorts = new AtomicReference<Map<Long, PortSnapshot>>();
        final var netBPorts = new AtomicReference<Map<Long, PortSnapshot>>();
        final var netCPorts = new AtomicReference<Map<Long, PortSnapshot>>();

        final var builder = multiNetworkHapiTest(netA, netB, netC)
                // Ensure all networks are up before any node updates occur
                .onNetwork("NET_A", ensureNetworkReady(netA, initialRoster))
                .onNetwork("NET_B", ensureNetworkReady(netB, initialRoster))
                .onNetwork("NET_C", ensureNetworkReady(netC, initialRoster))
                // Each network removes a different node id and verifies the roster reflects the change
                .onNetwork("NET_A", deleteAndUpgradeNetwork(netA, "netA", 1L, 4L, expectedRosterA, netAPorts))
                .onNetwork("NET_B", deleteAndUpgradeNetwork(netB, "netB", 2L, 4L, expectedRosterB, netBPorts))
                .onNetwork("NET_C", deleteAndUpgradeNetwork(netC, "netC", 3L, 4L, expectedRosterC, netCPorts))
                // After all upgrades, verify each network is running with the expected roster
                .onNetwork("NET_A", ensureNetworkReady(netA, expectedRosterA))
                .onNetwork("NET_B", ensureNetworkReady(netB, expectedRosterB))
                .onNetwork("NET_C", ensureNetworkReady(netC, expectedRosterC))
                // Final cross-network verification that all networks stayed active and have the correct roster
                .onNetwork("NET_A", rosterShouldMatch(expectedRosterA))
                .onNetwork("NET_B", rosterShouldMatch(expectedRosterB))
                .onNetwork("NET_C", rosterShouldMatch(expectedRosterC));

        return builder.asDynamicTests();
    }

    /**
     * Verifies a staged node addition where the new node joins from a copied signed state.
     *
     * @param net the subprocess network under test
     * @return dynamic tests representing the staged upgrade plan
     */
    @MultiNetworkHapiTest(
            networks = {@MultiNetworkHapiTest.Network(name = "NET_STAGE", size = 4, firstGrpcPort = 30400)})
    @DisplayName("Upgrade starts existing nodes before new node joins from signed state")
    Stream<DynamicTest> stagedNodeAdditionUsesSignedState(final SubProcessNetwork net) {
        final List<Long> initialRoster = List.of(0L, 1L, 2L, 3L);
        final List<Long> expectedRoster = List.of(0L, 1L, 2L, 3L, 4L);
        final var builder = multiNetworkHapiTest(net)
                .onNetwork("NET_STAGE", ensureNetworkReady(net, initialRoster))
                .onNetwork("NET_STAGE", addNodeAndUpgradeWithDeferredStart(net, "netStage", 4L, expectedRoster))
                .onNetwork("NET_STAGE", rosterShouldMatch(expectedRoster));
        return builder.asDynamicTests();
    }

    /**
     * Builds the operations to remove one node and add another, then perform a freeze upgrade.
     *
     * @param network the target subprocess network
     * @param networkPrefix prefix for entity names
     * @param nodeIdToRemove the node id to remove
     * @param nodeIdToAdd the node id to add
     * @param expectedRoster expected roster after the upgrade
     * @param portSnapshots holder for pre-upgrade port snapshots
     * @return the operations to execute on the network
     */
    private SpecOperation[] deleteAndUpgradeNetwork(
            final SubProcessNetwork network,
            final String networkPrefix,
            final long nodeIdToRemove,
            final long nodeIdToAdd,
            final List<Long> expectedRoster,
            final AtomicReference<Map<Long, PortSnapshot>> portSnapshots) {
        final var newNodeName = networkPrefix + "-node" + nodeIdToAdd;
        final var newNodeAccount = networkPrefix + "-node" + nodeIdToAdd + "-account";
        final var postUpgradeAccount = networkPrefix + "-postUpgradeAccount";
        final var gossipEndpoints = network.gossipEndpointsForNodeId(nodeIdToAdd);
        final var grpcEndpoint = network.grpcEndpointForNodeId(nodeIdToAdd);
        final AtomicReference<com.hederahashgraph.api.proto.java.AccountID> createdAccount = new AtomicReference<>();
        final long sourceNodeId = 0L;
        final var postResumeOps = UtilVerbs.blockingOrder(
                cryptoCreate(postUpgradeAccount),
                UtilVerbs.doingContextual(TxnUtils::triggerAndCloseAtLeastOneFileIfNotInterrupted));
        return new SpecOperation[] {
            // Ensure channel pools are initialized for this network before fee downloads
            UtilVerbs.doingContextual(spec -> spec.subProcessNetworkOrThrow().refreshClients()),
            UtilVerbs.doingContextual(spec -> portSnapshots.set(portsByNodeId(spec.subProcessNetworkOrThrow()))),
            doingContextual(spec -> LifecycleTest.setCurrentConfigVersion(spec, 0)),
            cryptoCreate(newNodeAccount).payingWith(GENESIS).balance(ONE_HBAR).exposingCreatedIdTo(createdAccount::set),
            withOpContext((spec, opLog) -> {
                final var protoId = createdAccount.get();
                final var pbjId = CommonPbjConverters.toPbj(protoId);
                // Ensure the subprocess network uses the created account for the new node id
                spec.subProcessNetworkOrThrow().updateNodeAccount(nodeIdToAdd, pbjId);
                // Also align default shard/realm in case the created account is not the classic default number
                opLog.info(
                        "Mapped node {} to account {} for {}",
                        nodeIdToAdd,
                        protoId.getAccountNum(),
                        spec.targetNetworkOrThrow().name());
            }),
            nodeDelete(String.valueOf(nodeIdToRemove)).payingWith(GENESIS),
            nodeCreate(newNodeName, newNodeAccount)
                    .payingWith(GENESIS)
                    .description(newNodeName)
                    .serviceEndpoint(List.of(grpcEndpoint))
                    .gossipEndpoint(gossipEndpoints)
                    .adminKey(GENESIS)
                    .gossipCaCertificate(gossipCaCertificateForNodeId(nodeIdToAdd)),
            UtilVerbs.doingContextual(spec -> {
                final var subProcessNetwork = spec.subProcessNetworkOrThrow();
                final var baseline = subProcessNetwork.latestSignedStateRound(sourceNodeId);
                subProcessNetwork.awaitSignedStateAfterRound(sourceNodeId, baseline, Duration.ofMinutes(1));
            }),
            prepareFakeUpgrade(),
            validateCandidateRoster(roster ->
                    assertThat(nodeIdsFrom(roster).toList()).containsExactlyInAnyOrderElementsOf(expectedRoster)),
            upgradeToNextConfigVersionWithDeferredNodes(
                    List.of(nodeIdToAdd),
                    sourceNodeId,
                    UtilVerbs.blockingOrder(
                            FakeNmt.removeNodeNoOverride(byNodeId(nodeIdToRemove)),
                            FakeNmt.addNodeNoOverride(nodeIdToAdd)),
                    postResumeOps),
            // Refresh clients after the network restart to pick up new ports/endpoints
            UtilVerbs.doingContextual(spec -> spec.subProcessNetworkOrThrow().refreshClients()),
            UtilVerbs.doingContextual(spec -> assertNoOverrideNetworkFiles(spec.subProcessNetworkOrThrow())),
            UtilVerbs.doingContextual(spec ->
                    assertPortsRetained(spec.subProcessNetworkOrThrow(), portSnapshots, nodeIdToRemove, nodeIdToAdd)),
            rosterShouldMatch(expectedRoster)
        };
    }

    /**
     * Builds the operations to add a node and defer its start until a signed state is copied.
     *
     * @param network the target subprocess network
     * @param networkPrefix prefix for entity names
     * @param nodeIdToAdd the node id to add
     * @param expectedRoster expected roster after the upgrade
     * @return the operations to execute on the network
     */
    private SpecOperation[] addNodeAndUpgradeWithDeferredStart(
            final SubProcessNetwork network,
            final String networkPrefix,
            final long nodeIdToAdd,
            final List<Long> expectedRoster) {
        final var newNodeName = networkPrefix + "-node" + nodeIdToAdd;
        final var newNodeAccount = networkPrefix + "-node" + nodeIdToAdd + "-account";
        final var gossipEndpoints = network.gossipEndpointsForNodeId(nodeIdToAdd);
        final var grpcEndpoint = network.grpcEndpointForNodeId(nodeIdToAdd);
        final AtomicReference<com.hederahashgraph.api.proto.java.AccountID> createdAccount = new AtomicReference<>();
        final long sourceNodeId = 0L;
        final var postResumeOps = UtilVerbs.blockingOrder(
                cryptoCreate("postUpgradeAccount"),
                UtilVerbs.doingContextual(TxnUtils::triggerAndCloseAtLeastOneFileIfNotInterrupted));
        return new SpecOperation[] {
            UtilVerbs.doingContextual(spec -> spec.subProcessNetworkOrThrow().refreshClients()),
            doingContextual(spec -> LifecycleTest.setCurrentConfigVersion(spec, 0)),
            cryptoCreate(newNodeAccount).payingWith(GENESIS).balance(ONE_HBAR).exposingCreatedIdTo(createdAccount::set),
            withOpContext((spec, opLog) -> {
                final var protoId = createdAccount.get();
                final var pbjId = CommonPbjConverters.toPbj(protoId);
                spec.subProcessNetworkOrThrow().updateNodeAccount(nodeIdToAdd, pbjId);
                opLog.info(
                        "Mapped node {} to account {} for {}",
                        nodeIdToAdd,
                        protoId.getAccountNum(),
                        spec.targetNetworkOrThrow().name());
            }),
            nodeCreate(newNodeName, newNodeAccount)
                    .payingWith(GENESIS)
                    .description(newNodeName)
                    .serviceEndpoint(List.of(grpcEndpoint))
                    .gossipEndpoint(gossipEndpoints)
                    .adminKey(GENESIS)
                    .gossipCaCertificate(gossipCaCertificateForNodeId(nodeIdToAdd)),
            UtilVerbs.doingContextual(spec -> {
                final var subProcessNetwork = spec.subProcessNetworkOrThrow();
                final var baseline = subProcessNetwork.latestSignedStateRound(sourceNodeId);
                subProcessNetwork.awaitSignedStateAfterRound(sourceNodeId, baseline, Duration.ofMinutes(1));
            }),
            prepareFakeUpgrade(),
            validateCandidateRoster(roster ->
                    assertThat(nodeIdsFrom(roster).toList()).containsExactlyInAnyOrderElementsOf(expectedRoster)),
            upgradeToNextConfigVersionWithDeferredNodes(
                    List.of(nodeIdToAdd), sourceNodeId, FakeNmt.addNodeNoOverride(nodeIdToAdd), postResumeOps),
            UtilVerbs.doingContextual(spec -> assertNoOverrideNetworkFiles(spec.subProcessNetworkOrThrow()))
        };
    }

    /**
     * Ensures the network is active and the roster matches the expected ids.
     *
     * @param network the network to refresh
     * @param expectedIds the expected node ids
     * @return operations that validate readiness
     */
    private SpecOperation[] ensureNetworkReady(final SubProcessNetwork network, final List<Long> expectedIds) {
        return new SpecOperation[] {
            UtilVerbs.doingContextual(spec -> network.refreshClients()), rosterShouldMatch(expectedIds)
        };
    }

    /**
     * Validates that the current roster contains the expected node ids.
     *
     * @param expectedIds expected node ids
     * @return an operation that asserts the roster matches
     */
    private SpecOperation rosterShouldMatch(final List<Long> expectedIds) {
        return withOpContext((spec, opLog) -> {
            final var actualIds = spec.subProcessNetworkOrThrow().nodes().stream()
                    .map(node -> node.getNodeId())
                    .toList();
            assertThat(actualIds).containsExactlyInAnyOrderElementsOf(expectedIds);
        });
    }

    /**
     * Asserts that port assignments are preserved for existing nodes and correctly derived for new nodes.
     *
     * @param network the network under test
     * @param portSnapshots baseline ports before upgrade
     * @param nodeIdToRemove node id that should be absent
     * @param nodeIdToAdd node id that should be present
     */
    private static void assertPortsRetained(
            final SubProcessNetwork network,
            final AtomicReference<Map<Long, PortSnapshot>> portSnapshots,
            final long nodeIdToRemove,
            final long nodeIdToAdd) {
        final var baseline = portSnapshots.get();
        assertThat(baseline).as("Baseline ports captured").isNotNull();
        final var current = portsByNodeId(network);
        assertThat(current).containsKey(nodeIdToAdd);
        assertThat(current).doesNotContainKey(nodeIdToRemove);
        baseline.forEach((nodeId, snapshot) -> {
            if (nodeId != nodeIdToRemove) {
                assertThat(current).containsKey(nodeId);
                assertThat(current.get(nodeId)).isEqualTo(snapshot);
            }
        });
        final var base = baseline.get(0L);
        assertThat(base).as("Node0 baseline ports").isNotNull();
        final var expectedNew = expectedPortsForNode(base, nodeIdToAdd);
        assertThat(current.get(nodeIdToAdd)).isEqualTo(expectedNew);
    }

    /**
     * Returns the current port assignments keyed by node id.
     *
     * @param network the network to inspect
     * @return map of node id to port snapshot
     */
    private static Map<Long, PortSnapshot> portsByNodeId(final SubProcessNetwork network) {
        return network.nodes().stream()
                .collect(toMap(node -> node.getNodeId(), node -> PortSnapshot.from(node.metadata())));
    }

    /**
     * Ensures no override-network.json files are present in any node config directory.
     *
     * @param network the network to inspect
     */
    private static void assertNoOverrideNetworkFiles(final SubProcessNetwork network) {
        network.nodes().forEach(node -> {
            final var configDir = node.getExternalPath(ExternalPath.DATA_CONFIG_DIR);
            assertThat(configDir.resolve(DiskStartupNetworks.OVERRIDE_NETWORK_JSON))
                    .as("override-network.json absent for node " + node.getNodeId())
                    .doesNotExist();
            try (var dirs = Files.list(configDir)) {
                dirs.filter(Files::isDirectory)
                        .filter(dir -> OVERRIDE_SCOPE_DIR_PATTERN
                                .matcher(dir.getFileName().toString())
                                .matches())
                        .forEach(dir -> assertThat(dir.resolve(DiskStartupNetworks.OVERRIDE_NETWORK_JSON))
                                .as("scoped override-network.json absent for node " + node.getNodeId())
                                .doesNotExist());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    /**
     * Calculates expected ports for a node given a base snapshot and node id.
     *
     * @param base baseline ports from node0
     * @param nodeId node id to project ports for
     * @return expected port snapshot for the node
     */
    private static PortSnapshot expectedPortsForNode(final PortSnapshot base, final long nodeId) {
        final var offset = (int) nodeId;
        return new PortSnapshot(
                base.grpcPort() + offset * 2,
                base.grpcNodeOperatorPort() + offset,
                base.internalGossipPort() + offset * 2,
                base.externalGossipPort() + offset * 2,
                base.prometheusPort() + offset,
                base.debugPort() + offset);
    }

    /**
     * Captures port assignments for a single node.
     */
    private record PortSnapshot(
            int grpcPort,
            int grpcNodeOperatorPort,
            int internalGossipPort,
            int externalGossipPort,
            int prometheusPort,
            int debugPort) {
        /**
         * Creates a snapshot from node metadata.
         *
         * @param metadata node metadata
         * @return port snapshot for the node
         */
        private static PortSnapshot from(final NodeMetadata metadata) {
            return new PortSnapshot(
                    metadata.grpcPort(),
                    metadata.grpcNodeOperatorPort(),
                    metadata.internalGossipPort(),
                    metadata.externalGossipPort(),
                    metadata.prometheusPort(),
                    metadata.debugPort());
        }
    }
}
