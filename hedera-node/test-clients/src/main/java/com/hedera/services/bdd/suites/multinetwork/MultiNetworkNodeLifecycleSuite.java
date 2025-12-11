// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.multinetwork;

import static com.hedera.services.bdd.junit.hedera.NodeSelector.byNodeId;
import static com.hedera.services.bdd.junit.hedera.utils.AddressBookUtils.nodeIdsFrom;
import static com.hedera.services.bdd.spec.HapiSpec.multiNetworkHapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.nodeCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.nodeDelete;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doAdhoc;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateCandidateRoster;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.node.app.hapi.utils.CommonPbjConverters;
import com.hedera.services.bdd.junit.MultiNetworkHapiTest;
import com.hedera.services.bdd.junit.TestTags;
import com.hedera.services.bdd.junit.hedera.subprocess.SubProcessNetwork;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.utilops.FakeNmt;
import com.hedera.services.bdd.spec.utilops.UtilVerbs;
import com.hedera.services.bdd.suites.hip869.NodeCreateTest;
import com.hedera.services.bdd.suites.regression.system.LifecycleTest;
import java.security.cert.CertificateEncodingException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * Exercises freeze-upgrade driven node removals across multiple networks and verifies roster changes.
 */
@Tag(TestTags.MULTINETWORK)
public class MultiNetworkNodeLifecycleSuite extends AbstractMultiNetworkSuite implements LifecycleTest {
    @BeforeEach
    void initDefaults() {
        setConfigDefaults();
    }

    @MultiNetworkHapiTest(
            networks = {
                @MultiNetworkHapiTest.Network(name = "NET_A", size = 4),
                @MultiNetworkHapiTest.Network(name = "NET_B", size = 4),
                @MultiNetworkHapiTest.Network(name = "NET_C", size = 4)
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

        final var builder = multiNetworkHapiTest(netA, netB, netC)
                // Ensure all networks are up before any node updates occur
                .onNetwork("NET_A", ensureNetworkReady(netA, initialRoster))
                .onNetwork("NET_B", ensureNetworkReady(netB, initialRoster))
                .onNetwork("NET_C", ensureNetworkReady(netC, initialRoster))
                // Each network removes a different node id and verifies the roster reflects the change
                .onNetwork("NET_A", deleteAndUpgradeNetwork(netA, "netA", 1L, 4L, expectedRosterA))
                .onNetwork("NET_B", deleteAndUpgradeNetwork(netB, "netB", 2L, 4L, expectedRosterB))
                .onNetwork("NET_C", deleteAndUpgradeNetwork(netC, "netC", 3L, 4L, expectedRosterC))
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

    private SpecOperation[] deleteAndUpgradeNetwork(
            final SubProcessNetwork network,
            final String networkPrefix,
            final long nodeIdToRemove,
            final long nodeIdToAdd,
            final List<Long> expectedRoster) {
        final var newNodeName = networkPrefix + "-node" + nodeIdToAdd;
        final var newNodeAccount = networkPrefix + "-node" + nodeIdToAdd + "-account";
        final var gossipEndpoints = network.gossipEndpointsForNextNodeId();
        final var grpcEndpoint = network.grpcEndpointForNextNodeId();
        final AtomicReference<com.hederahashgraph.api.proto.java.AccountID> createdAccount = new AtomicReference<>();
        return new SpecOperation[] {
            // Ensure channel pools are initialized for this network before fee downloads
            UtilVerbs.doingContextual(spec -> spec.subProcessNetworkOrThrow().refreshClients()),
            doAdhoc(() -> CURRENT_CONFIG_VERSION.set(0)),
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
                    .gossipCaCertificate(encodeCert()),
            prepareFakeUpgrade(),
            validateCandidateRoster(roster ->
                    assertThat(nodeIdsFrom(roster).toList()).containsExactlyInAnyOrderElementsOf(expectedRoster)),
            upgradeToNextConfigVersion(
                    Map.of(), FakeNmt.removeNode(byNodeId(nodeIdToRemove)), FakeNmt.addNode(nodeIdToAdd)),
            // Refresh clients after the network restart to pick up new ports/endpoints
            UtilVerbs.doingContextual(spec -> spec.subProcessNetworkOrThrow().refreshClients()),
            rosterShouldMatch(expectedRoster)
        };
    }

    private SpecOperation[] ensureNetworkReady(final SubProcessNetwork network, final List<Long> expectedIds) {
        return new SpecOperation[] {
            UtilVerbs.doingContextual(spec -> network.refreshClients()), rosterShouldMatch(expectedIds)
        };
    }

    private SpecOperation rosterShouldMatch(final List<Long> expectedIds) {
        return withOpContext((spec, opLog) -> {
            final var actualIds = spec.subProcessNetworkOrThrow().nodes().stream()
                    .map(node -> node.getNodeId())
                    .toList();
            assertThat(actualIds).containsExactlyInAnyOrderElementsOf(expectedIds);
        });
    }

    private static byte[] encodeCert() {
        try {
            return NodeCreateTest.generateX509Certificates(1).getFirst().getEncoded();
        } catch (CertificateEncodingException e) {
            throw new IllegalStateException("Failed to generate/encode X509 certificate for node create", e);
        }
    }
}
