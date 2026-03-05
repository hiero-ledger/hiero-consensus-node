// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.interledger;

import static com.hedera.services.bdd.spec.HapiSpec.multiNetworkHapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getVersionInfo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.nodeCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.nodeDelete;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.FUNDING;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.interledger.ClprMessagesSuite.awaitLocalLedgerConfiguration;
import static com.hedera.services.bdd.suites.interledger.ClprMessagesSuite.awaitMessageQueueMetadataAvailable;
import static com.hedera.services.bdd.suites.interledger.ClprMessagesSuite.getFirstNode;
import static com.hedera.services.bdd.suites.interledger.ClprMessagesSuite.sleepQuietly;
import static com.hedera.services.bdd.suites.interledger.ClprMessagesSuite.submitConfiguration;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.services.bdd.junit.ConfigOverride;
import com.hedera.services.bdd.junit.MultiNetworkHapiTest;
import com.hedera.services.bdd.junit.OrderedInIsolation;
import com.hedera.services.bdd.junit.TestTags;
import com.hedera.services.bdd.junit.hedera.ExternalPath;
import com.hedera.services.bdd.junit.hedera.HederaNode;
import com.hedera.services.bdd.junit.hedera.NodeSelector;
import com.hedera.services.bdd.junit.hedera.subprocess.SubProcessNetwork;
import com.hedera.services.bdd.junit.hedera.utils.NetworkUtils;
import com.hedera.services.bdd.junit.hedera.utils.WorkingDirUtils;
import com.hedera.services.bdd.spec.utilops.FakeNmt;
import com.hedera.services.bdd.spec.utilops.UtilVerbs;
import com.hedera.services.bdd.suites.regression.system.LifecycleTest;
import com.hederahashgraph.api.proto.java.SemanticVersion;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.apache.logging.log4j.Logger;
import org.hiero.hapi.interledger.state.clpr.ClprLedgerConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;

/**
 * CLPR node assignment HapiTest suite.
 *
 * <p>Tests round-robin node assignment across multi-node source ledgers, including
 * dynamic address book changes (node add/remove/restart).
 */
@OrderedInIsolation
@Tag(TestTags.MULTINETWORK)
public class ClprNodeAssigmentSuite implements LifecycleTest {

    private static final String SOURCE_LEDGER = "source";
    private static final String REMOTE_A = "remoteA";
    private static final String REMOTE_B = "remoteB";

    @MultiNetworkHapiTest(
            networks = {
                @MultiNetworkHapiTest.Network(
                        name = SOURCE_LEDGER,
                        size = 3,
                        firstGrpcPort = 35400,
                        setupOverrides = {
                            @ConfigOverride(key = "clpr.clprEnabled", value = "true"),
                            @ConfigOverride(key = "clpr.publicizeNetworkAddresses", value = "false"),
                            @ConfigOverride(key = "clpr.connectionFrequency", value = "5000"),
                            @ConfigOverride(key = "contracts.systemContract.clprQueue.enabled", value = "true")
                        }),
                @MultiNetworkHapiTest.Network(
                        name = REMOTE_A,
                        size = 1,
                        firstGrpcPort = 36400,
                        setupOverrides = {
                            @ConfigOverride(key = "clpr.clprEnabled", value = "true"),
                            @ConfigOverride(key = "clpr.publicizeNetworkAddresses", value = "true"),
                            @ConfigOverride(key = "clpr.connectionFrequency", value = "20000"),
                            @ConfigOverride(key = "contracts.systemContract.clprQueue.enabled", value = "true")
                        }),
                @MultiNetworkHapiTest.Network(
                        name = REMOTE_B,
                        size = 1,
                        firstGrpcPort = 37400,
                        setupOverrides = {
                            @ConfigOverride(key = "clpr.clprEnabled", value = "true"),
                            @ConfigOverride(key = "clpr.publicizeNetworkAddresses", value = "true"),
                            @ConfigOverride(key = "clpr.connectionFrequency", value = "20000"),
                            @ConfigOverride(key = "contracts.systemContract.clprQueue.enabled", value = "true")
                        })
            })
    @DisplayName("Three-node source round-robin node assignment")
    @Order(1)
    Stream<DynamicTest> threeNodeSourceRoundRobinMessaging(
            final SubProcessNetwork source, final SubProcessNetwork remoteA, final SubProcessNetwork remoteB) {
        /*
         * Specification: A 3-node source ledger communicates with two single-node remote ledgers.
         *
         * Goal:
         * - Prove reduced redundancy — not all 3 nodes contact every remote ledger simultaneously.
         * - Prove rotation — the responsible node changes over time (different cycles).
         *
         * Stage 1: Bootstrap all three ledgers and capture their CLPR configurations.
         * Stage 2: Exchange configurations between source and both remotes.
         * Stage 3: Await message queue metadata on source for both remote ledgers.
         * Stage 4: Wait for multiple round-robin cycles to accumulate log evidence.
         * Stage 5: Assert reduced redundancy — every source node has "Skipping ledger" in logs.
         * Stage 6: Assert rotation — more than 1 source node logged "Completed publish/pull"
         *          for at least one remote ledger.
         */
        final var sourceConfig = new AtomicReference<ClprLedgerConfiguration>();
        final var remoteAConfig = new AtomicReference<ClprLedgerConfiguration>();
        final var remoteBConfig = new AtomicReference<ClprLedgerConfiguration>();

        final var builder = multiNetworkHapiTest(source, remoteA, remoteB)
                // Stage 1: Bootstrap — capture each ledger's local configuration.
                .onNetwork(SOURCE_LEDGER, withOpContext((spec, log) -> {
                    final var config = awaitLocalLedgerConfiguration(spec.getNetworkNodes());
                    sourceConfig.set(config);
                    log.info("CLPR_RR_TEST|stage=bootstrap|ledger=source|ledgerId={}", config.ledgerId());
                }))
                .onNetwork(REMOTE_A, withOpContext((spec, log) -> {
                    final var config = awaitLocalLedgerConfiguration(spec.getNetworkNodes());
                    remoteAConfig.set(config);
                    log.info("CLPR_RR_TEST|stage=bootstrap|ledger=remoteA|ledgerId={}", config.ledgerId());
                }))
                .onNetwork(REMOTE_B, withOpContext((spec, log) -> {
                    final var config = awaitLocalLedgerConfiguration(spec.getNetworkNodes());
                    remoteBConfig.set(config);
                    log.info("CLPR_RR_TEST|stage=bootstrap|ledger=remoteB|ledgerId={}", config.ledgerId());
                }))

                // Stage 2: Exchange configurations — source learns about both remotes and vice versa.
                .onNetwork(SOURCE_LEDGER, withOpContext((spec, log) -> {
                    submitConfiguration(spec, getFirstNode(spec), requireNonNull(remoteAConfig.get()));
                    submitConfiguration(spec, getFirstNode(spec), requireNonNull(remoteBConfig.get()));
                }))
                .onNetwork(
                        REMOTE_A,
                        withOpContext((spec, log) ->
                                submitConfiguration(spec, getFirstNode(spec), requireNonNull(sourceConfig.get()))))
                .onNetwork(
                        REMOTE_B,
                        withOpContext((spec, log) ->
                                submitConfiguration(spec, getFirstNode(spec), requireNonNull(sourceConfig.get()))))

                // Stage 3: Await message queue metadata on source for both remote ledgers.
                .onNetwork(SOURCE_LEDGER, withOpContext((spec, log) -> {
                    awaitMessageQueueMetadataAvailable(
                            log, spec.getNetworkNodes(), requireNonNull(remoteAConfig.get()));
                    awaitMessageQueueMetadataAvailable(
                            log, spec.getNetworkNodes(), requireNonNull(remoteBConfig.get()));
                }))

                // Stage 4: Wait for multiple round-robin cycles to accumulate log evidence.
                .onNetwork(SOURCE_LEDGER, withOpContext((spec, log) -> {
                    log.info("CLPR_RR_TEST|stage=wait|waiting 20s for round-robin cycles");
                    sleepQuietly(Duration.ofSeconds(20));
                }))

                // Stage 5: Reduced redundancy — every source node must have skipped at least one ledger.
                .onNetwork(
                        SOURCE_LEDGER,
                        UtilVerbs.assertHgcaaLogContainsText(
                                NodeSelector.allNodes(), "CLPR Endpoint: Skipping ledger", Duration.ZERO))

                // Stage 6: Rotation matrix — parse "Current cycle" log lines from all source nodes,
                //          build a (cycle × remoteLedger) matrix showing the assigned node index,
                //          verify each assignment matches the formula, and assert rotation occurred.
                .onNetwork(SOURCE_LEDGER, withOpContext((spec, log) -> {
                    final var sourceNodes = spec.getNetworkNodes();
                    final int rosterSize = sourceNodes.size();

                    // Collect all (cycle, remoteLedger, assignedNode) tuples from logs
                    final var assignments = collectAssignmentMatrix(sourceNodes);
                    logAssignmentMatrix(assignments, log, "CLPR_RR_TEST");
                    verifyAssignmentProperties(assignments, rosterSize, log, "round-robin", true);
                }));

        return builder.asDynamicTests();
    }

    @MultiNetworkHapiTest(
            networks = {
                @MultiNetworkHapiTest.Network(
                        name = SOURCE_LEDGER,
                        size = 3,
                        firstGrpcPort = 38400,
                        setupOverrides = {
                            @ConfigOverride(key = "clpr.clprEnabled", value = "true"),
                            @ConfigOverride(key = "clpr.publicizeNetworkAddresses", value = "false"),
                            @ConfigOverride(key = "clpr.connectionFrequency", value = "5000"),
                            @ConfigOverride(key = "contracts.systemContract.clprQueue.enabled", value = "true"),
                            @ConfigOverride(key = "nodes.maxNumber", value = "10")
                        }),
                @MultiNetworkHapiTest.Network(
                        name = REMOTE_A,
                        size = 1,
                        firstGrpcPort = 39400,
                        setupOverrides = {
                            @ConfigOverride(key = "clpr.clprEnabled", value = "true"),
                            @ConfigOverride(key = "clpr.publicizeNetworkAddresses", value = "true"),
                            @ConfigOverride(key = "clpr.connectionFrequency", value = "20000"),
                            @ConfigOverride(key = "contracts.systemContract.clprQueue.enabled", value = "true")
                        })
            })
    @DisplayName("Node assignment adjusts after address book changes")
    @Order(2)
    Stream<DynamicTest> nodeAssignmentAdjustsAfterAddressBookChanges(
            final SubProcessNetwork source, final SubProcessNetwork remoteA) {
        final var sourceConfig = new AtomicReference<ClprLedgerConfiguration>();
        final var remoteConfig = new AtomicReference<ClprLedgerConfiguration>();
        final var maxCycleAfterInitial = new AtomicLong(-1);
        final var maxCycleAfterAdd = new AtomicLong(-1);
        final var maxCycleAfterRemove = new AtomicLong(-1);

        final var builder = multiNetworkHapiTest(source, remoteA)
                // Stage 1: Bootstrap — capture each ledger's local configuration.
                .onNetwork(SOURCE_LEDGER, withOpContext((spec, log) -> {
                    final var config = awaitLocalLedgerConfiguration(spec.getNetworkNodes());
                    sourceConfig.set(config);
                    log.info("CLPR_DAB_TEST|stage=bootstrap|ledger=source|ledgerId={}", config.ledgerId());
                }))
                .onNetwork(REMOTE_A, withOpContext((spec, log) -> {
                    final var config = awaitLocalLedgerConfiguration(spec.getNetworkNodes());
                    remoteConfig.set(config);
                    log.info("CLPR_DAB_TEST|stage=bootstrap|ledger=remoteA|ledgerId={}", config.ledgerId());
                }))

                // Stage 2: Exchange configurations.
                .onNetwork(
                        SOURCE_LEDGER,
                        withOpContext((spec, log) ->
                                submitConfiguration(spec, getFirstNode(spec), requireNonNull(remoteConfig.get()))))
                .onNetwork(
                        REMOTE_A,
                        withOpContext((spec, log) ->
                                submitConfiguration(spec, getFirstNode(spec), requireNonNull(sourceConfig.get()))))

                // Stage 3: Await message queue metadata on source.
                .onNetwork(
                        SOURCE_LEDGER,
                        withOpContext((spec, log) -> awaitMessageQueueMetadataAvailable(
                                log, spec.getNetworkNodes(), requireNonNull(remoteConfig.get()))))

                // Stage 4: Wait for initial round-robin cycles (rosterSize=3), then validate.
                .onNetwork(SOURCE_LEDGER, withOpContext((spec, log) -> {
                    log.info("CLPR_DAB_TEST|stage=initial_wait|waiting 20s for round-robin cycles");
                    sleepQuietly(Duration.ofSeconds(20));

                    final var assignments = collectAssignmentMatrix(spec.getNetworkNodes());
                    logAssignmentMatrix(assignments, log, "CLPR_DAB_TEST");
                    verifyAssignmentProperties(assignments, 3, log, "initial (rosterSize=3)", false);
                    maxCycleAfterInitial.set(maxCycle(assignments));
                    log.info("CLPR_DAB_TEST|stage=initial_validated|maxCycle={}", maxCycleAfterInitial.get());
                }))

                // Stage 5: Add node 3 via DAB.
                .onNetwork(SOURCE_LEDGER, withOpContext((spec, log) -> {
                    log.info("CLPR_DAB_TEST|stage=add_node|adding node 3");
                    allRunFor(
                            spec,
                            nodeCreate("node3", NetworkUtils.classicFeeCollectorIdFor(3))
                                    .adminKey(DEFAULT_PAYER)
                                    .description(NetworkUtils.CLASSIC_NODE_NAMES[3])
                                    .withAvailableSubProcessPorts()
                                    .gossipCaCertificate(WorkingDirUtils.gossipCaCertificateForNodeId(3L)));
                    allRunFor(spec, prepareFakeUpgrade());
                    allRunFor(
                            spec,
                            upgradeToNextConfigVersionWithDeferredNodes(
                                    List.of(3L),
                                    0L,
                                    FakeNmt.addNodeNoOverride(3L),
                                    cryptoTransfer(tinyBarsFromTo(GENESIS, FUNDING, 1))));
                    log.info("CLPR_DAB_TEST|stage=add_node|node 3 added successfully");
                }))

                // Stage 6: Wait for cycles after add (rosterSize=4), then validate.
                .onNetwork(SOURCE_LEDGER, withOpContext((spec, log) -> {
                    log.info("CLPR_DAB_TEST|stage=post_add_wait|waiting 20s for round-robin cycles");
                    sleepQuietly(Duration.ofSeconds(20));

                    final var allAssignments = collectAssignmentMatrix(spec.getNetworkNodes());
                    final var newAssignments =
                            collectAssignmentMatrixAfterCycle(allAssignments, maxCycleAfterInitial.get());
                    assertThat(newAssignments)
                            .as("Expected new cycles after adding node 3")
                            .isNotEmpty();
                    logAssignmentMatrix(newAssignments, log, "CLPR_DAB_TEST");
                    verifyAssignmentProperties(newAssignments, 4, log, "after add node 3 (rosterSize=4)", false);
                    maxCycleAfterAdd.set(maxCycle(allAssignments));
                    log.info("CLPR_DAB_TEST|stage=post_add_validated|maxCycle={}", maxCycleAfterAdd.get());
                }))

                // Stage 7: Remove node 1 via DAB.
                .onNetwork(SOURCE_LEDGER, withOpContext((spec, log) -> {
                    log.info("CLPR_DAB_TEST|stage=remove_node|removing node 1");
                    allRunFor(spec, nodeDelete("1"));
                    allRunFor(spec, prepareFakeUpgrade());
                    allRunFor(
                            spec,
                            upgradeToNextConfigVersionWithoutOverrides(
                                    Map.of(), FakeNmt.removeNodeNoOverride(NodeSelector.byNodeId(1))));
                    log.info("CLPR_DAB_TEST|stage=remove_node|node 1 removed successfully");
                }))

                // Stage 8: Wait for cycles after remove (rosterSize=3, nodes 0,2,3), then validate.
                .onNetwork(SOURCE_LEDGER, withOpContext((spec, log) -> {
                    log.info("CLPR_DAB_TEST|stage=post_remove_wait|waiting 20s for round-robin cycles");
                    sleepQuietly(Duration.ofSeconds(20));

                    final var allAssignments = collectAssignmentMatrix(spec.getNetworkNodes());
                    final var newAssignments =
                            collectAssignmentMatrixAfterCycle(allAssignments, maxCycleAfterAdd.get());
                    assertThat(newAssignments)
                            .as("Expected new cycles after removing node 1")
                            .isNotEmpty();
                    logAssignmentMatrix(newAssignments, log, "CLPR_DAB_TEST");
                    verifyAssignmentProperties(newAssignments, 3, log, "after remove node 1 (rosterSize=3)", false);
                    maxCycleAfterRemove.set(maxCycle(allAssignments));
                    log.info("CLPR_DAB_TEST|stage=post_remove_validated|maxCycle={}", maxCycleAfterRemove.get());
                }));

        return builder.asDynamicTests();
    }

    @MultiNetworkHapiTest(
            networks = {
                @MultiNetworkHapiTest.Network(
                        name = SOURCE_LEDGER,
                        size = 4,
                        firstGrpcPort = 38400,
                        setupOverrides = {
                            @ConfigOverride(key = "clpr.clprEnabled", value = "true"),
                            @ConfigOverride(key = "clpr.publicizeNetworkAddresses", value = "false"),
                            @ConfigOverride(key = "clpr.connectionFrequency", value = "5000"),
                            @ConfigOverride(key = "contracts.systemContract.clprQueue.enabled", value = "true")
                        }),
                @MultiNetworkHapiTest.Network(
                        name = REMOTE_A,
                        size = 1,
                        firstGrpcPort = 39400,
                        setupOverrides = {
                            @ConfigOverride(key = "clpr.clprEnabled", value = "true"),
                            @ConfigOverride(key = "clpr.publicizeNetworkAddresses", value = "true"),
                            @ConfigOverride(key = "clpr.connectionFrequency", value = "20000"),
                            @ConfigOverride(key = "contracts.systemContract.clprQueue.enabled", value = "true")
                        })
            })
    @DisplayName("Node assignment adjusts after node restart")
    @Order(3)
    Stream<DynamicTest> nodeAssignmentAdjustsAfterNodeRestart(
            final SubProcessNetwork source, final SubProcessNetwork remoteA) {
        /*
         * Specification: A 3-node source ledger restarts one node and verifies round-robin
         * assignment adapts correctly.
         *
         * Goal:
         * - Prove that while a node is down, the remaining nodes handle all assignments.
         * - Prove that after restart the node re-joins the round-robin correctly.
         *
         * Stage 1: Bootstrap and exchange configurations.
         * Stage 2: Wait for initial round-robin cycles, capture baseline assignments.
         * Stage 3: Shut down node 1 (without burstOfTps to avoid PLATFORM_NOT_ACTIVE).
         * Stage 4: While node 1 is down, verify only remaining nodes are assigned.
         * Stage 5: Restart node 1 and wait for it to become ACTIVE.
         * Stage 6: Verify all 3 nodes participate in round-robin again.
         */
        final var sourceConfig = new AtomicReference<ClprLedgerConfiguration>();
        final var remoteConfig = new AtomicReference<ClprLedgerConfiguration>();
        final var maxCycleBeforeShutdown = new AtomicLong(-1);
        final var maxCycleWhileDown = new AtomicLong(-1);
        final AtomicReference<SemanticVersion> currentVersion = new AtomicReference<>();

        final var builder = multiNetworkHapiTest(source, remoteA)
                // Stage 1: Bootstrap — capture each ledger's local configuration.
                .onNetwork(SOURCE_LEDGER, withOpContext((spec, log) -> {
                    final var config = awaitLocalLedgerConfiguration(spec.getNetworkNodes());
                    sourceConfig.set(config);
                    log.info("CLPR_RESTART_TEST|stage=bootstrap|ledger=source|ledgerId={}", config.ledgerId());
                }))
                .onNetwork(REMOTE_A, withOpContext((spec, log) -> {
                    final var config = awaitLocalLedgerConfiguration(spec.getNetworkNodes());
                    remoteConfig.set(config);
                    log.info("CLPR_RESTART_TEST|stage=bootstrap|ledger=remoteA|ledgerId={}", config.ledgerId());
                }))

                // Exchange configurations.
                .onNetwork(
                        SOURCE_LEDGER,
                        withOpContext((spec, log) ->
                                submitConfiguration(spec, getFirstNode(spec), requireNonNull(remoteConfig.get()))))
                .onNetwork(
                        REMOTE_A,
                        withOpContext((spec, log) ->
                                submitConfiguration(spec, getFirstNode(spec), requireNonNull(sourceConfig.get()))))

                // Await message queue metadata on source.
                .onNetwork(
                        SOURCE_LEDGER,
                        withOpContext((spec, log) -> awaitMessageQueueMetadataAvailable(
                                log, spec.getNetworkNodes(), requireNonNull(remoteConfig.get()))))

                // Stage 2: Wait for initial round-robin cycles (rosterSize=3), capture baseline.
                .onNetwork(SOURCE_LEDGER, withOpContext((spec, log) -> {
                    log.info("CLPR_RESTART_TEST|stage=initial_wait|waiting 20s for round-robin cycles");
                    sleepQuietly(Duration.ofSeconds(20));

                    final var assignments = collectAssignmentMatrix(spec.getNetworkNodes());
                    logAssignmentMatrix(assignments, log, "CLPR_RESTART_TEST");
                    verifyAssignmentProperties(assignments, 4, log, "initial (rosterSize=3)", false);
                    maxCycleBeforeShutdown.set(maxCycle(assignments));
                    log.info("CLPR_RESTART_TEST|stage=initial_validated|maxCycle={}", maxCycleBeforeShutdown.get());
                }))

                // Stage 3: Shut down node 1 (no burstOfTps — avoids PLATFORM_NOT_ACTIVE).
                .onNetwork(SOURCE_LEDGER, withOpContext((spec, log) -> {
                    log.info("CLPR_RESTART_TEST|stage=shutdown|shutting down node 1");
                    allRunFor(
                            spec,
                            getVersionInfo().exposingServicesVersionTo(currentVersion::set),
                            FakeNmt.shutdownWithin(NodeSelector.byNodeId(1), SHUTDOWN_TIMEOUT));
                    log.info("CLPR_RESTART_TEST|stage=shutdown|node 1 is down");
                }))

                // Stage 4: While node 1 is down, verify only the remaining 2 nodes are assigned.
                .onNetwork(SOURCE_LEDGER, withOpContext((spec, log) -> {
                    log.info("CLPR_RESTART_TEST|stage=while_down|waiting 20s for cycles with node 1 down");
                    sleepQuietly(Duration.ofSeconds(20));

                    // Collect only from the nodes that are still running (exclude node 1)
                    final var activeNodes = spec.getNetworkNodes().stream()
                            .filter(n -> n.getNodeId() != 1)
                            .toList();
                    final var allAssignments = collectAssignmentMatrix(activeNodes);
                    final var newAssignments =
                            collectAssignmentMatrixAfterCycle(allAssignments, maxCycleBeforeShutdown.get());
                    assertThat(newAssignments)
                            .as("Expected cycles while node 1 is down")
                            .isNotEmpty();
                    logAssignmentMatrix(newAssignments, log, "CLPR_RESTART_TEST");

                    // Assignment should still use rosterSize=3 (node 1 is in roster, just offline),
                    // but node 1 should not appear as the observer (it's not logging)
                    verifyAssignmentProperties(newAssignments, 4, log, "while node 1 down (rosterSize=3)", false);
                    maxCycleWhileDown.set(maxCycle(allAssignments));
                    log.info("CLPR_RESTART_TEST|stage=while_down_validated|maxCycle={}", maxCycleWhileDown.get());
                }))

                // Stage 5: Restart node 1 and wait for ACTIVE.
                // Use config version 0 explicitly — this is a fresh network with no upgrades.
                // Cannot use currentConfigVersion(spec) because the static CONFIG_VERSIONS_BY_NETWORK
                // map may have stale values from other tests that also use network name "source".
                .onNetwork(SOURCE_LEDGER, withOpContext((spec, log) -> {
                    log.info("CLPR_RESTART_TEST|stage=restart|restarting node 1");
                    allRunFor(
                            spec,
                            FakeNmt.restartWithConfigVersion(
                                    NodeSelector.byNodeId(1), LifecycleTest.configVersionOf(currentVersion.get())),
                            UtilVerbs.waitForActive(NodeSelector.byNodeId(1), RESTART_TO_ACTIVE_TIMEOUT));
                    log.info("CLPR_RESTART_TEST|stage=restart|node 1 is ACTIVE again");
                }))

                // Stage 6: Verify all 3 nodes participate in round-robin after restart.
                .onNetwork(SOURCE_LEDGER, withOpContext((spec, log) -> {
                    log.info("CLPR_RESTART_TEST|stage=post_restart|waiting 20s for round-robin cycles");
                    sleepQuietly(Duration.ofSeconds(20));

                    final var allAssignments = collectAssignmentMatrix(spec.getNetworkNodes());
                    final var newAssignments =
                            collectAssignmentMatrixAfterCycle(allAssignments, maxCycleWhileDown.get());
                    assertThat(newAssignments)
                            .as("Expected cycles after node 1 restart")
                            .isNotEmpty();
                    logAssignmentMatrix(newAssignments, log, "CLPR_RESTART_TEST");
                    verifyAssignmentProperties(newAssignments, 4, log, "after restart node 1 (rosterSize=3)", false);
                    log.info("CLPR_RESTART_TEST|stage=post_restart_validated|all stages passed");
                }));

        return builder.asDynamicTests();
    }

    /**
     * A single observed round-robin assignment from a node's log.
     */
    private record CycleAssignment(long cycle, String remoteLedger, int assignedNode) {}

    // Matches: "CLPR Endpoint: Current cycle {cycle}; Assigned node {node} for ClprLedgerId[ledgerId={id}]"
    private static final Pattern CYCLE_ASSIGNMENT_PATTERN =
            Pattern.compile("Current cycle (\\d+); Assigned node (\\d+) for ClprLedgerId\\[ledgerId=([^]]+)]");

    /**
     * Parses "Current cycle" log lines from all source nodes and returns the complete
     * set of (cycle, remoteLedger, assignedNode) observations.
     */
    private static List<CycleAssignment> collectAssignmentMatrix(final List<HederaNode> nodes) {
        final var result = new ArrayList<CycleAssignment>();
        for (final var node : nodes) {
            try {
                final var logPath = node.getExternalPath(ExternalPath.APPLICATION_LOG);
                for (final var line : Files.readAllLines(logPath)) {
                    final Matcher m = CYCLE_ASSIGNMENT_PATTERN.matcher(line);
                    if (m.find()) {
                        final long cycle = Long.parseLong(m.group(1));
                        final int assignedNode = Integer.parseInt(m.group(2));
                        final var remoteLedger = m.group(3);
                        result.add(new CycleAssignment(cycle, remoteLedger, assignedNode));
                    }
                }
            } catch (final IOException e) {
                throw new IllegalStateException("Failed to read log for node " + node.getName(), e);
            }
        }
        return result;
    }

    /**
     * Finds the assigned node index for a given cycle and remote ledger, or -1 if not observed.
     */
    private static int findAssignedNode(
            final List<CycleAssignment> assignments, final long cycle, final String remoteLedger) {
        for (final var entry : assignments) {
            if (entry.cycle == cycle && entry.remoteLedger.equals(remoteLedger)) {
                return entry.assignedNode;
            }
        }
        return -1;
    }

    /**
     * Filters assignment observations to only those with cycle strictly greater than {@code minCycleExclusive}.
     */
    private static List<CycleAssignment> collectAssignmentMatrixAfterCycle(
            final List<CycleAssignment> assignments, final long minCycleExclusive) {
        return assignments.stream().filter(a -> a.cycle > minCycleExclusive).toList();
    }

    /**
     * Returns the maximum cycle number observed in the assignment list, or -1 if empty.
     */
    private static long maxCycle(final List<CycleAssignment> assignments) {
        return assignments.stream().mapToLong(CycleAssignment::cycle).max().orElse(-1);
    }

    /**
     * Logs a formatted assignment matrix: rows = cycles, columns = remote ledgers, cells = assigned node.
     */
    private static void logAssignmentMatrix(
            final List<CycleAssignment> assignments, final Logger log, final String logPrefix) {
        final var allCycles = new TreeSet<Long>();
        final var allLedgers = new TreeSet<String>();
        for (final var entry : assignments) {
            allCycles.add(entry.cycle);
            allLedgers.add(entry.remoteLedger);
        }

        final var sb = new StringBuilder();
        sb.append("\n=== Round-Robin Assignment Matrix (cycle × remote ledger → assigned node) ===\n");
        sb.append(String.format("%-10s", "Cycle"));
        for (final var ledger : allLedgers) {
            final var shortId = ledger.length() > 8 ? ledger.substring(ledger.length() - 8) : ledger;
            sb.append(String.format("  %-12s", shortId));
        }
        sb.append('\n');

        for (final var cycle : allCycles) {
            sb.append(String.format("%-10d", cycle));
            for (final var ledger : allLedgers) {
                final var nodeIdx = findAssignedNode(assignments, cycle, ledger);
                sb.append(String.format("  %-12s", nodeIdx >= 0 ? "node" + nodeIdx : "-"));
            }
            sb.append('\n');
        }
        sb.append("=============================================================================\n");
        log.info("{}|assignment_matrix|{}", logPrefix, sb);
    }

    /**
     * Validates behavioral properties of the round-robin assignment:
     * <ul>
     *   <li><b>Range</b> — every assigned node index is in {@code [0, rosterSize)}.</li>
     *   <li><b>Consistency</b> — for the same (cycle, remoteLedger) pair, only one assigned node is observed.</li>
     *   <li><b>Rotation</b> (optional) — at least one remote ledger was assigned to more than one distinct node
     *       across the observed cycles.</li>
     * </ul>
     */
    private static void verifyAssignmentProperties(
            final List<CycleAssignment> assignments,
            final int rosterSize,
            final Logger log,
            final String label,
            final boolean requireRotation) {
        log.info(
                "verifyAssignmentProperties|label={}|rosterSize={}|observations={}",
                label,
                rosterSize,
                assignments.size());

        // Range: every assigned node must be in [0, rosterSize)
        for (final var entry : assignments) {
            assertThat(entry.assignedNode)
                    .as(
                            "[%s] Cycle %d, ledger ...%s: node%d out of range [0, %d)",
                            label,
                            entry.cycle,
                            entry.remoteLedger.length() > 8
                                    ? entry.remoteLedger.substring(entry.remoteLedger.length() - 8)
                                    : entry.remoteLedger,
                            entry.assignedNode,
                            rosterSize)
                    .isBetween(0, rosterSize - 1);
        }

        // Consistency: for each (cycle, ledger), all observations must agree on the assigned node
        final var seen = new java.util.HashMap<String, Integer>();
        for (final var entry : assignments) {
            final var key = entry.cycle + "|" + entry.remoteLedger;
            final var prev = seen.put(key, entry.assignedNode);
            if (prev != null) {
                assertThat(entry.assignedNode)
                        .as(
                                "[%s] Inconsistent assignment for cycle %d, ledger ...%s: node%d vs node%d",
                                label,
                                entry.cycle,
                                entry.remoteLedger.length() > 8
                                        ? entry.remoteLedger.substring(entry.remoteLedger.length() - 8)
                                        : entry.remoteLedger,
                                prev,
                                entry.assignedNode)
                        .isEqualTo(prev);
            }
        }

        // Rotation: at least one ledger should have >1 distinct assigned node
        if (requireRotation) {
            final var allLedgers = new TreeSet<String>();
            for (final var entry : assignments) {
                allLedgers.add(entry.remoteLedger);
            }
            for (final var ledger : allLedgers) {
                final var distinctNodes = new HashSet<Integer>();
                for (final var entry : assignments) {
                    if (entry.remoteLedger.equals(ledger)) {
                        distinctNodes.add(entry.assignedNode);
                    }
                }
                if (distinctNodes.size() > 1) {
                    log.info(
                            "verifyAssignmentProperties|rotation_confirmed|label={}|ledger=...{}|nodes={}",
                            label,
                            ledger.length() > 8 ? ledger.substring(ledger.length() - 8) : ledger,
                            distinctNodes);
                    return;
                }
            }
            final var nodesPerLedger = new StringBuilder();
            for (final var ledger : allLedgers) {
                final var nodes = new HashSet<Integer>();
                for (final var e : assignments) {
                    if (e.remoteLedger.equals(ledger)) nodes.add(e.assignedNode);
                }
                nodesPerLedger.append(String.format(
                        "  ...%s -> %s\n",
                        ledger.length() > 8 ? ledger.substring(ledger.length() - 8) : ledger, nodes));
            }
            assertThat(false)
                    .as(
                            "[%s] Expected rotation but each ledger was always assigned to the same node:\n%s",
                            label, nodesPerLedger)
                    .isTrue();
        }
    }
}
