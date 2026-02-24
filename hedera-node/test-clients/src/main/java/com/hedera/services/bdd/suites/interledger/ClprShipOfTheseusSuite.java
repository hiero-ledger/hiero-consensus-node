// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.interledger;

import static com.hedera.services.bdd.junit.hedera.NodeSelector.byNodeId;
import static com.hedera.services.bdd.junit.hedera.NodeSelector.exceptNodeIds;
import static com.hedera.services.bdd.junit.hedera.utils.NetworkUtils.nodeIdsFrom;
import static com.hedera.services.bdd.junit.hedera.utils.WorkingDirUtils.gossipCaCertificateForNodeId;
import static com.hedera.services.bdd.spec.HapiSpec.multiNetworkHapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.nodeCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.nodeDelete;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.inParallel;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateCandidateRoster;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.FUNDING;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.freeze.CommonUpgradeResources.DEFAULT_UPGRADE_FILE_ID;
import static com.hedera.services.bdd.suites.freeze.CommonUpgradeResources.FAKE_ASSETS_LOC;
import static com.hedera.services.bdd.suites.freeze.CommonUpgradeResources.upgradeFileAppendsPerBurst;
import static com.hedera.services.bdd.suites.freeze.CommonUpgradeResources.upgradeFileHashAt;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toMap;
import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.node.app.hapi.utils.CommonPbjConverters;
import com.hedera.node.app.info.DiskStartupNetworks;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.junit.ConfigOverride;
import com.hedera.services.bdd.junit.MultiNetworkHapiTest;
import com.hedera.services.bdd.junit.OrderedInIsolation;
import com.hedera.services.bdd.junit.TestTags;
import com.hedera.services.bdd.junit.hedera.ExternalPath;
import com.hedera.services.bdd.junit.hedera.HederaNode;
import com.hedera.services.bdd.junit.hedera.MarkerFile;
import com.hedera.services.bdd.junit.hedera.NodeMetadata;
import com.hedera.services.bdd.junit.hedera.subprocess.SubProcessNetwork;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer;
import com.hedera.services.bdd.spec.utilops.CustomSpecAssert;
import com.hedera.services.bdd.spec.utilops.FakeNmt;
import com.hedera.services.bdd.spec.utilops.UtilVerbs;
import com.hedera.services.bdd.spec.utilops.lifecycle.ops.WaitForMarkerFileOp;
import com.hedera.services.bdd.spec.utilops.upgrade.BuildUpgradeZipOp;
import com.hedera.services.bdd.suites.regression.system.LifecycleTest;
import com.hederahashgraph.api.proto.java.AccountID;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.hiero.hapi.interledger.state.clpr.protoc.ClprEndpoint;
import org.hiero.hapi.interledger.state.clpr.protoc.ClprLedgerConfiguration;
import org.hiero.hapi.interledger.state.clpr.protoc.ClprLedgerId;
import org.hiero.interledger.clpr.ClprStateProofUtils;
import org.hiero.interledger.clpr.impl.client.ClprClientImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@OrderedInIsolation
@Tag(TestTags.MULTINETWORK)
public class ClprShipOfTheseusSuite implements LifecycleTest {
    private static final Pattern OVERRIDE_SCOPE_DIR_PATTERN = Pattern.compile("\\d+");
    private static final Duration CONFIG_PROPAGATION_TIMEOUT = Duration.ofMinutes(60);
    private static final Duration CONFIG_PROPAGATION_POLL_INTERVAL = Duration.ofSeconds(60);

    static {
        // Capture subprocess stdout/stderr early so startup failures surface in logs.
        System.setProperty("hapi.subprocess.capture.output", "true");
        System.setProperty("hapi.subprocess.log.exit", "true");
    }

    @MultiNetworkHapiTest(
            networks = {
                @MultiNetworkHapiTest.Network(
                        name = "SHIP_A",
                        size = 4,
                        firstGrpcPort = 35400,
                        setupOverrides = {
                            @ConfigOverride(key = "clpr.clprEnabled", value = "true"),
                            @ConfigOverride(key = "clpr.publicizeNetworkAddresses", value = "true"),
                            @ConfigOverride(key = "clpr.connectionFrequency", value = "1000"),
                            @ConfigOverride(key = "tss.hintsEnabled", value = "true"),
                            @ConfigOverride(key = "tss.historyEnabled", value = "true"),
                            @ConfigOverride(key = "tss.wrapsEnabled", value = "true"),
                            @ConfigOverride(key = "tss.initialCrsParties", value = "16")
                        }),
                @MultiNetworkHapiTest.Network(
                        name = "SHIP_B",
                        size = 4,
                        firstGrpcPort = 36400,
                        setupOverrides = {
                            @ConfigOverride(key = "clpr.clprEnabled", value = "true"),
                            @ConfigOverride(key = "clpr.publicizeNetworkAddresses", value = "false"),
                            @ConfigOverride(key = "clpr.connectionFrequency", value = "1000"),
                            @ConfigOverride(key = "tss.hintsEnabled", value = "true"),
                            @ConfigOverride(key = "tss.historyEnabled", value = "true"),
                            @ConfigOverride(key = "tss.wrapsEnabled", value = "true"),
                            @ConfigOverride(key = "tss.initialCrsParties", value = "16")
                        })
            })
    @DisplayName("Two-network Ship of Theseus baseline")
    Stream<DynamicTest> twoNetworkBasicShipOfTheseus(final SubProcessNetwork netA, final SubProcessNetwork netB) {
        /*
         * Specification: Two ships exchange CLPR ledger configurations as they evolve (Ship of Theseus).
         *
         * Stage 1: Bring up both ships; SHIP_A publicizes endpoints, SHIP_B hides them. Capture and assert each
         *          ship's local configuration matches its exposure setting.
         * Stage 2: Submit SHIP_A's configuration to SHIP_B so SHIP_B will begin publishing its own configuration back to SHIP_A.
         * Stage 3: From SHIP_B, ensure SHIP_A's configuration is stored; from SHIP_A, wait until SHIP_B's configuration arrives.
         * Stage 4: Mutate SHIP_A (delete/add node + freeze/upgrade), fetch the updated configuration, and assert roster/timestamp.
         * Stage 5: From SHIP_B, wait for SHIP_A's updated configuration and ensure it matches what SHIP_A reported.
         * Stage 6: Mutate SHIP_B similarly, fetch its updated configuration, and assert roster/timestamp.
         * Stage 7: From SHIP_A, wait for SHIP_B's updated configuration to arrive and ensure it matches SHIP_B's latest.
         * Stage 8: Incrementally rotate each ship's roster: delete one existing node, add a replacement, then restart
         *          the network. Both ships may rotate in parallel. After each rotation, verify both ships still
         *          exchange CLPR configurations. The test completes when all original nodes in both ships have been
         *          replaced and communication between the networks is verified to take place.
         *          (Both networks have the latest ClprLedgerConfiguration of the other network)
         *
         */
        final List<Long> initialRoster = List.of(0L, 1L, 2L, 3L);
        final List<Long> expectedRosterA = List.of(1L, 2L, 3L, 4L);
        final List<Long> expectedRosterB = List.of(1L, 2L, 3L, 4L);
        final List<Long> expectedRosterARotation1 = List.of(2L, 3L, 4L, 5L);
        final List<Long> expectedRosterARotation2 = List.of(3L, 4L, 5L, 6L);
        final List<Long> expectedRosterARotation3 = List.of(4L, 5L, 6L, 7L);
        final List<Long> expectedRosterBRotation1 = List.of(2L, 3L, 4L, 5L);
        final List<Long> expectedRosterBRotation2 = List.of(3L, 4L, 5L, 6L);
        final List<Long> expectedRosterBRotation3 = List.of(4L, 5L, 6L, 7L);
        final var privateClprOverrides = Map.of("clpr.publicizeNetworkAddresses", "false");
        final var netAPorts = new AtomicReference<Map<Long, PortSnapshot>>();
        final var netBPorts = new AtomicReference<Map<Long, PortSnapshot>>();
        final var basePortsA = new AtomicReference<PortSnapshot>();
        final var basePortsB = new AtomicReference<PortSnapshot>();
        final var baselineConfigA = new AtomicReference<ClprLedgerConfiguration>();
        final var baselineConfigB = new AtomicReference<ClprLedgerConfiguration>();
        final var updatedConfigA = new AtomicReference<ClprLedgerConfiguration>();
        final var updatedConfigB = new AtomicReference<ClprLedgerConfiguration>();
        final var stakerIdsA = new ConcurrentHashMap<Long, AccountID>();
        final var stakerIdsB = new ConcurrentHashMap<Long, AccountID>();

        final var builder = multiNetworkHapiTest(netA, netB)
                // Stage 1: Bring up both ships and capture baseline configs per exposure settings.
                .onNetwork("SHIP_A", ensureNetworkReady(netA, initialRoster))
                .onNetwork("SHIP_B", ensureNetworkReady(netB, initialRoster))
                .onNetwork("SHIP_A", captureBasePorts(basePortsA))
                .onNetwork("SHIP_B", captureBasePorts(basePortsB))
                .onNetwork("SHIP_A", seedStakerAccounts("shipA", initialRoster, stakerIdsA))
                .onNetwork("SHIP_B", seedStakerAccounts("shipB", initialRoster, stakerIdsB))
                .onNetwork("SHIP_A", withOpContext((spec, opLog) -> {
                    final var nodes = spec.targetNetworkOrThrow().nodes();
                    final var config = fetchLedgerConfiguration(nodes);
                    assertEndpointsMatchNetwork(config, nodes);
                    baselineConfigA.set(config);
                }))
                .onNetwork("SHIP_B", withOpContext((spec, opLog) -> {
                    final var nodes = spec.targetNetworkOrThrow().nodes();
                    final var config = fetchLedgerConfiguration(nodes);
                    assertNoPublishedEndpoints(config, nodes);
                    baselineConfigB.set(config);
                }))
                // Stage 2: Submit SHIP_A's config to SHIP_B to trigger SHIP_B publishing.
                .onNetwork("SHIP_B", withOpContext((spec, opLog) -> {
                    final var configA = requireNonNull(baselineConfigA.get(), "Baseline SHIP_A config required");
                    submitConfiguration(spec.targetNetworkOrThrow().nodes().getFirst(), configA);
                }))
                // Stage 3: Verify SHIP_A config stored on SHIP_B and SHIP_B config arrives on SHIP_A.
                .onNetwork("SHIP_B", withOpContext((spec, opLog) -> {
                    final var configA = requireNonNull(baselineConfigA.get(), "Baseline SHIP_A config required");
                    final var stored = awaitLedgerConfiguration(
                            spec.targetNetworkOrThrow().nodes(), configA.getLedgerId(), Duration.ofMinutes(2));
                    assertLedgerIdStable(configA, stored);
                }))
                .onNetwork("SHIP_A", withOpContext((spec, opLog) -> {
                    final var configB = requireNonNull(baselineConfigB.get(), "Baseline SHIP_B config required");
                    final var stored = awaitLedgerConfiguration(
                            spec.targetNetworkOrThrow().nodes(), configB.getLedgerId(), Duration.ofMinutes(2));
                    assertLedgerIdStable(configB, stored);
                }))
                // Stage 4: Mutate SHIP_A and capture the updated configuration.
                .onNetwork(
                        "SHIP_A",
                        deleteAndUpgradeNetwork(netA, "shipA", 0L, 4L, 1L, expectedRosterA, netAPorts, basePortsA))
                .onNetwork("SHIP_A", ensureNetworkReady(netA, expectedRosterA))
                .onNetwork("SHIP_A", withOpContext((spec, opLog) -> {
                    final var baseline = requireNonNull(baselineConfigA.get(), "Baseline SHIP_A config required");
                    final var nodes = spec.targetNetworkOrThrow().nodes();
                    final var updated = fetchLedgerConfiguration(
                            nodes,
                            candidate -> configTimestamp(candidate).isAfter(configTimestamp(baseline)),
                            "advance after SHIP_A upgrade");
                    assertLedgerIdStable(baseline, updated);
                    assertTimestampAdvanced(baseline, updated);
                    assertEndpointsMatchNetwork(updated, nodes);
                    updatedConfigA.set(updated);
                }))
                // Stage 5: Confirm SHIP_B receives SHIP_A's updated configuration.
                .onNetwork("SHIP_B", withOpContext((spec, opLog) -> {
                    final var updatedA = requireNonNull(updatedConfigA.get(), "Updated SHIP_A config required");
                    final var observed = awaitMatchingLedgerConfiguration(
                            spec.targetNetworkOrThrow().nodes(),
                            updatedA,
                            CONFIG_PROPAGATION_TIMEOUT,
                            CONFIG_PROPAGATION_POLL_INTERVAL);
                    assertConfigMatchesIgnoringTimestamp(updatedA, observed);
                }))
                .onNetwork("SHIP_A", moveStakeToNewNode("shipA", 0L, 4L, stakerIdsA))
                // Stage 6: Mutate SHIP_B and capture the updated configuration.
                .onNetwork(
                        "SHIP_B",
                        deleteAndUpgradeNetwork(
                                netB,
                                "shipB",
                                0L,
                                4L,
                                1L,
                                expectedRosterB,
                                netBPorts,
                                basePortsB,
                                privateClprOverrides))
                .onNetwork("SHIP_B", ensureNetworkReady(netB, expectedRosterB))
                .onNetwork("SHIP_B", withOpContext((spec, opLog) -> {
                    final var baseline = requireNonNull(baselineConfigB.get(), "Baseline SHIP_B config required");
                    final var nodes = spec.targetNetworkOrThrow().nodes();
                    final var updated = fetchLedgerConfiguration(
                            nodes,
                            candidate -> configTimestamp(candidate).isAfter(configTimestamp(baseline)),
                            "advance after SHIP_B upgrade");
                    assertLedgerIdStable(baseline, updated);
                    assertTimestampAdvanced(baseline, updated);
                    assertNoPublishedEndpoints(updated, nodes);
                    updatedConfigB.set(updated);
                }))
                // Stage 7: Confirm SHIP_A receives SHIP_B's updated configuration.
                .onNetwork("SHIP_A", withOpContext((spec, opLog) -> {
                    final var updatedB = requireNonNull(updatedConfigB.get(), "Updated SHIP_B config required");
                    final var observed = awaitMatchingLedgerConfiguration(
                            spec.targetNetworkOrThrow().nodes(),
                            updatedB,
                            CONFIG_PROPAGATION_TIMEOUT,
                            CONFIG_PROPAGATION_POLL_INTERVAL);
                    assertConfigMatchesIgnoringTimestamp(updatedB, observed);
                }))
                .onNetwork("SHIP_B", moveStakeToNewNode("shipB", 0L, 4L, stakerIdsB))
                // Stage 8: Rotate rosters on both ships while validating CLPR exchange after each rotation.
                .onNetwork(
                        "SHIP_A",
                        deleteAndUpgradeNetwork(
                                netA, "shipA", 1L, 5L, 2L, expectedRosterARotation1, netAPorts, basePortsA))
                .onNetwork("SHIP_A", ensureNetworkReady(netA, expectedRosterARotation1))
                .onNetwork("SHIP_A", withOpContext((spec, opLog) -> {
                    final var baseline = requireNonNull(updatedConfigA.get(), "Updated SHIP_A config required");
                    final var nodes = spec.targetNetworkOrThrow().nodes();
                    final var updated = fetchLedgerConfiguration(
                            nodes,
                            candidate -> configTimestamp(candidate).isAfter(configTimestamp(baseline)),
                            "advance after SHIP_A rotation 1");
                    assertLedgerIdStable(baseline, updated);
                    assertTimestampAdvanced(baseline, updated);
                    assertEndpointsMatchNetwork(updated, nodes);
                    updatedConfigA.set(updated);
                }))
                .onNetwork("SHIP_B", withOpContext((spec, opLog) -> {
                    final var updatedA = requireNonNull(updatedConfigA.get(), "Updated SHIP_A config required");
                    final var observed = awaitMatchingLedgerConfiguration(
                            spec.targetNetworkOrThrow().nodes(),
                            updatedA,
                            CONFIG_PROPAGATION_TIMEOUT,
                            CONFIG_PROPAGATION_POLL_INTERVAL);
                    assertConfigMatchesIgnoringTimestamp(updatedA, observed);
                }))
                .onNetwork("SHIP_A", withOpContext((spec, opLog) -> {
                    final var updatedB = requireNonNull(updatedConfigB.get(), "Updated SHIP_B config required");
                    final var observed = awaitMatchingLedgerConfiguration(
                            spec.targetNetworkOrThrow().nodes(),
                            updatedB,
                            CONFIG_PROPAGATION_TIMEOUT,
                            CONFIG_PROPAGATION_POLL_INTERVAL);
                    assertConfigMatchesIgnoringTimestamp(updatedB, observed);
                }))
                .onNetwork("SHIP_A", moveStakeToNewNode("shipA", 1L, 5L, stakerIdsA))
                .onNetwork(
                        "SHIP_B",
                        deleteAndUpgradeNetwork(
                                netB,
                                "shipB",
                                1L,
                                5L,
                                2L,
                                expectedRosterBRotation1,
                                netBPorts,
                                basePortsB,
                                privateClprOverrides))
                .onNetwork("SHIP_B", ensureNetworkReady(netB, expectedRosterBRotation1))
                .onNetwork("SHIP_B", withOpContext((spec, opLog) -> {
                    final var baseline = requireNonNull(updatedConfigB.get(), "Updated SHIP_B config required");
                    final var nodes = spec.targetNetworkOrThrow().nodes();
                    final var updated = fetchLedgerConfiguration(
                            nodes,
                            candidate -> configTimestamp(candidate).isAfter(configTimestamp(baseline)),
                            "advance after SHIP_B rotation 1");
                    assertLedgerIdStable(baseline, updated);
                    assertTimestampAdvanced(baseline, updated);
                    assertNoPublishedEndpoints(updated, nodes);
                    updatedConfigB.set(updated);
                }))
                .onNetwork("SHIP_A", withOpContext((spec, opLog) -> {
                    final var updatedB = requireNonNull(updatedConfigB.get(), "Updated SHIP_B config required");
                    final var observed = awaitMatchingLedgerConfiguration(
                            spec.targetNetworkOrThrow().nodes(),
                            updatedB,
                            CONFIG_PROPAGATION_TIMEOUT,
                            CONFIG_PROPAGATION_POLL_INTERVAL);
                    assertConfigMatchesIgnoringTimestamp(updatedB, observed);
                }))
                .onNetwork("SHIP_B", withOpContext((spec, opLog) -> {
                    final var updatedA = requireNonNull(updatedConfigA.get(), "Updated SHIP_A config required");
                    final var observed = awaitMatchingLedgerConfiguration(
                            spec.targetNetworkOrThrow().nodes(),
                            updatedA,
                            CONFIG_PROPAGATION_TIMEOUT,
                            CONFIG_PROPAGATION_POLL_INTERVAL);
                    assertConfigMatchesIgnoringTimestamp(updatedA, observed);
                }))
                .onNetwork("SHIP_B", moveStakeToNewNode("shipB", 1L, 5L, stakerIdsB))
                .onNetwork(
                        "SHIP_A",
                        deleteAndUpgradeNetwork(
                                netA, "shipA", 2L, 6L, 3L, expectedRosterARotation2, netAPorts, basePortsA))
                .onNetwork("SHIP_A", ensureNetworkReady(netA, expectedRosterARotation2))
                .onNetwork("SHIP_A", withOpContext((spec, opLog) -> {
                    final var baseline = requireNonNull(updatedConfigA.get(), "Updated SHIP_A config required");
                    final var nodes = spec.targetNetworkOrThrow().nodes();
                    final var updated = fetchLedgerConfiguration(
                            nodes,
                            candidate -> configTimestamp(candidate).isAfter(configTimestamp(baseline)),
                            "advance after SHIP_A rotation 2");
                    assertLedgerIdStable(baseline, updated);
                    assertTimestampAdvanced(baseline, updated);
                    assertEndpointsMatchNetwork(updated, nodes);
                    updatedConfigA.set(updated);
                }))
                .onNetwork("SHIP_B", withOpContext((spec, opLog) -> {
                    final var updatedA = requireNonNull(updatedConfigA.get(), "Updated SHIP_A config required");
                    final var observed = awaitMatchingLedgerConfiguration(
                            spec.targetNetworkOrThrow().nodes(),
                            updatedA,
                            CONFIG_PROPAGATION_TIMEOUT,
                            CONFIG_PROPAGATION_POLL_INTERVAL);
                    assertConfigMatchesIgnoringTimestamp(updatedA, observed);
                }))
                .onNetwork("SHIP_A", withOpContext((spec, opLog) -> {
                    final var updatedB = requireNonNull(updatedConfigB.get(), "Updated SHIP_B config required");
                    final var observed = awaitMatchingLedgerConfiguration(
                            spec.targetNetworkOrThrow().nodes(),
                            updatedB,
                            CONFIG_PROPAGATION_TIMEOUT,
                            CONFIG_PROPAGATION_POLL_INTERVAL);
                    assertConfigMatchesIgnoringTimestamp(updatedB, observed);
                }))
                .onNetwork("SHIP_A", moveStakeToNewNode("shipA", 2L, 6L, stakerIdsA))
                .onNetwork(
                        "SHIP_B",
                        deleteAndUpgradeNetwork(
                                netB,
                                "shipB",
                                2L,
                                6L,
                                3L,
                                expectedRosterBRotation2,
                                netBPorts,
                                basePortsB,
                                privateClprOverrides))
                .onNetwork("SHIP_B", ensureNetworkReady(netB, expectedRosterBRotation2))
                .onNetwork("SHIP_B", withOpContext((spec, opLog) -> {
                    final var baseline = requireNonNull(updatedConfigB.get(), "Updated SHIP_B config required");
                    final var nodes = spec.targetNetworkOrThrow().nodes();
                    final var updated = fetchLedgerConfiguration(
                            nodes,
                            candidate -> configTimestamp(candidate).isAfter(configTimestamp(baseline)),
                            "advance after SHIP_B rotation 2");
                    assertLedgerIdStable(baseline, updated);
                    assertTimestampAdvanced(baseline, updated);
                    assertNoPublishedEndpoints(updated, nodes);
                    updatedConfigB.set(updated);
                }))
                .onNetwork("SHIP_A", withOpContext((spec, opLog) -> {
                    final var updatedB = requireNonNull(updatedConfigB.get(), "Updated SHIP_B config required");
                    final var observed = awaitMatchingLedgerConfiguration(
                            spec.targetNetworkOrThrow().nodes(),
                            updatedB,
                            CONFIG_PROPAGATION_TIMEOUT,
                            CONFIG_PROPAGATION_POLL_INTERVAL);
                    assertConfigMatchesIgnoringTimestamp(updatedB, observed);
                }))
                .onNetwork("SHIP_B", withOpContext((spec, opLog) -> {
                    final var updatedA = requireNonNull(updatedConfigA.get(), "Updated SHIP_A config required");
                    final var observed = awaitMatchingLedgerConfiguration(
                            spec.targetNetworkOrThrow().nodes(),
                            updatedA,
                            CONFIG_PROPAGATION_TIMEOUT,
                            CONFIG_PROPAGATION_POLL_INTERVAL);
                    assertConfigMatchesIgnoringTimestamp(updatedA, observed);
                }))
                .onNetwork("SHIP_B", moveStakeToNewNode("shipB", 2L, 6L, stakerIdsB))
                .onNetwork(
                        "SHIP_A",
                        deleteAndUpgradeNetwork(
                                netA, "shipA", 3L, 7L, 4L, expectedRosterARotation3, netAPorts, basePortsA))
                .onNetwork("SHIP_A", ensureNetworkReady(netA, expectedRosterARotation3))
                .onNetwork("SHIP_A", withOpContext((spec, opLog) -> {
                    final var baseline = requireNonNull(updatedConfigA.get(), "Updated SHIP_A config required");
                    final var nodes = spec.targetNetworkOrThrow().nodes();
                    final var updated = fetchLedgerConfiguration(
                            nodes,
                            candidate -> configTimestamp(candidate).isAfter(configTimestamp(baseline)),
                            "advance after SHIP_A rotation 3");
                    assertLedgerIdStable(baseline, updated);
                    assertTimestampAdvanced(baseline, updated);
                    assertEndpointsMatchNetwork(updated, nodes);
                    updatedConfigA.set(updated);
                }))
                .onNetwork("SHIP_B", withOpContext((spec, opLog) -> {
                    final var updatedA = requireNonNull(updatedConfigA.get(), "Updated SHIP_A config required");
                    final var observed = awaitMatchingLedgerConfiguration(
                            spec.targetNetworkOrThrow().nodes(),
                            updatedA,
                            CONFIG_PROPAGATION_TIMEOUT,
                            CONFIG_PROPAGATION_POLL_INTERVAL);
                    assertConfigMatchesIgnoringTimestamp(updatedA, observed);
                }))
                .onNetwork("SHIP_A", withOpContext((spec, opLog) -> {
                    final var updatedB = requireNonNull(updatedConfigB.get(), "Updated SHIP_B config required");
                    final var observed = awaitMatchingLedgerConfiguration(
                            spec.targetNetworkOrThrow().nodes(),
                            updatedB,
                            CONFIG_PROPAGATION_TIMEOUT,
                            CONFIG_PROPAGATION_POLL_INTERVAL);
                    assertConfigMatchesIgnoringTimestamp(updatedB, observed);
                }))
                .onNetwork("SHIP_A", moveStakeToNewNode("shipA", 3L, 7L, stakerIdsA))
                .onNetwork(
                        "SHIP_B",
                        deleteAndUpgradeNetwork(
                                netB,
                                "shipB",
                                3L,
                                7L,
                                4L,
                                expectedRosterBRotation3,
                                netBPorts,
                                basePortsB,
                                privateClprOverrides))
                .onNetwork("SHIP_B", ensureNetworkReady(netB, expectedRosterBRotation3))
                .onNetwork("SHIP_B", withOpContext((spec, opLog) -> {
                    final var baseline = requireNonNull(updatedConfigB.get(), "Updated SHIP_B config required");
                    final var nodes = spec.targetNetworkOrThrow().nodes();
                    final var updated = fetchLedgerConfiguration(
                            nodes,
                            candidate -> configTimestamp(candidate).isAfter(configTimestamp(baseline)),
                            "advance after SHIP_B rotation 3");
                    assertLedgerIdStable(baseline, updated);
                    assertTimestampAdvanced(baseline, updated);
                    assertNoPublishedEndpoints(updated, nodes);
                    updatedConfigB.set(updated);
                }))
                .onNetwork("SHIP_A", withOpContext((spec, opLog) -> {
                    final var updatedB = requireNonNull(updatedConfigB.get(), "Updated SHIP_B config required");
                    final var observed = awaitMatchingLedgerConfiguration(
                            spec.targetNetworkOrThrow().nodes(),
                            updatedB,
                            CONFIG_PROPAGATION_TIMEOUT,
                            CONFIG_PROPAGATION_POLL_INTERVAL);
                    assertConfigMatchesIgnoringTimestamp(updatedB, observed);
                }))
                .onNetwork("SHIP_B", withOpContext((spec, opLog) -> {
                    final var updatedA = requireNonNull(updatedConfigA.get(), "Updated SHIP_A config required");
                    final var observed = awaitMatchingLedgerConfiguration(
                            spec.targetNetworkOrThrow().nodes(),
                            updatedA,
                            CONFIG_PROPAGATION_TIMEOUT,
                            CONFIG_PROPAGATION_POLL_INTERVAL);
                    assertConfigMatchesIgnoringTimestamp(updatedA, observed);
                }))
                .onNetwork("SHIP_B", moveStakeToNewNode("shipB", 3L, 7L, stakerIdsB))
                .onNetwork("SHIP_A", rosterShouldMatch(expectedRosterARotation3))
                .onNetwork("SHIP_B", rosterShouldMatch(expectedRosterBRotation3));

        return builder.asDynamicTests();
    }

    private SpecOperation[] deleteAndUpgradeNetwork(
            final SubProcessNetwork network,
            final String networkPrefix,
            final long nodeIdToRemove,
            final long nodeIdToAdd,
            final long sourceNodeId,
            final List<Long> expectedRoster,
            final AtomicReference<Map<Long, PortSnapshot>> portSnapshots,
            final AtomicReference<PortSnapshot> basePorts) {
        return deleteAndUpgradeNetwork(
                network,
                networkPrefix,
                nodeIdToRemove,
                nodeIdToAdd,
                sourceNodeId,
                expectedRoster,
                portSnapshots,
                basePorts,
                Map.of());
    }

    private SpecOperation[] deleteAndUpgradeNetwork(
            final SubProcessNetwork network,
            final String networkPrefix,
            final long nodeIdToRemove,
            final long nodeIdToAdd,
            final long sourceNodeId,
            final List<Long> expectedRoster,
            final AtomicReference<Map<Long, PortSnapshot>> portSnapshots,
            final AtomicReference<PortSnapshot> basePorts,
            final Map<String, String> configOverrides) {
        final var newNodeName = networkPrefix + "-node" + nodeIdToAdd;
        final var newNodeAccount = networkPrefix + "-node" + nodeIdToAdd + "-account";
        final var postUpgradeAccount = networkPrefix + "-postUpgradeAccount";
        final var gossipEndpoints = network.gossipEndpointsForNodeId(nodeIdToAdd);
        final var grpcEndpoint = network.grpcEndpointForNodeId(nodeIdToAdd);
        final AtomicReference<com.hederahashgraph.api.proto.java.AccountID> createdAccount = new AtomicReference<>();
        final Supplier<String> postResumeNode = () -> nodeAccountLiteral(network, sourceNodeId);
        final var postResumeOps = UtilVerbs.blockingOrder(
                cryptoCreate(postUpgradeAccount).setNodeFrom(postResumeNode),
                triggerAndCloseAtLeastOneFileOnNode(postResumeNode));
        return new SpecOperation[] {
            UtilVerbs.doingContextual(spec -> spec.subProcessNetworkOrThrow().refreshClients()),
            UtilVerbs.doingContextual(spec -> portSnapshots.set(portsByNodeId(spec.subProcessNetworkOrThrow()))),
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
                subProcessNetwork.awaitSignedStateAfterRound(sourceNodeId, baseline, Duration.ofMinutes(60));
            }),
            withOpContext((spec, opLog) -> {
                final var nodes = spec.subProcessNetworkOrThrow().nodes();
                // TODO: remove debug logging once marker-file wait issue is resolved.
                opLog.info(
                        "TODO remove debug: pre-upgrade roster nodes={} removeNode={} addNode={}",
                        nodes.stream()
                                .map(node -> node.getNodeId() + ":" + node.getName())
                                .toList(),
                        nodeIdToRemove,
                        nodeIdToAdd);
            }),
            prepareFakeUpgradeExcludingNode(nodeIdToAdd),
            validateCandidateRoster(roster ->
                    assertThat(nodeIdsFrom(roster).toList()).containsExactlyInAnyOrderElementsOf(expectedRoster)),
            upgradeToNextConfigVersionWithDeferredNodes(
                    List.of(nodeIdToAdd),
                    sourceNodeId,
                    configOverrides,
                    UtilVerbs.blockingOrder(
                            FakeNmt.removeNodeNoOverride(byNodeId(nodeIdToRemove)),
                            FakeNmt.addNodeNoOverride(nodeIdToAdd)),
                    postResumeOps),
            UtilVerbs.doingContextual(spec -> spec.subProcessNetworkOrThrow().refreshClients()),
            UtilVerbs.doingContextual(spec -> assertNoOverrideNetworkFiles(spec.subProcessNetworkOrThrow())),
            UtilVerbs.doingContextual(spec -> assertPortsRetained(
                    spec.subProcessNetworkOrThrow(), portSnapshots, basePorts, nodeIdToRemove, nodeIdToAdd)),
            rosterShouldMatch(expectedRoster)
        };
    }

    private SpecOperation prepareFakeUpgradeExcludingNode(final long deferredNodeId) {
        return UtilVerbs.blockingOrder(
                UtilVerbs.buildUpgradeZipFrom(FAKE_ASSETS_LOC),
                UtilVerbs.sourcing(() -> UtilVerbs.updateSpecialFile(
                        GENESIS,
                        DEFAULT_UPGRADE_FILE_ID,
                        BuildUpgradeZipOp.FAKE_UPGRADE_ZIP_LOC,
                        TxnUtils.BYTES_4K,
                        upgradeFileAppendsPerBurst())),
                UtilVerbs.purgeUpgradeArtifacts(),
                UtilVerbs.sourcing(() -> UtilVerbs.prepareUpgrade()
                        .withUpdateFile(DEFAULT_UPGRADE_FILE_ID)
                        .havingHash(upgradeFileHashAt(BuildUpgradeZipOp.FAKE_UPGRADE_ZIP_LOC))),
                new WaitForMarkerFileOp(
                        exceptNodeIds(deferredNodeId),
                        MarkerFile.EXEC_IMMEDIATE_MF,
                        LifecycleTest.EXEC_IMMEDIATE_MF_TIMEOUT));
    }

    private SpecOperation[] ensureNetworkReady(final SubProcessNetwork network, final List<Long> expectedIds) {
        return new SpecOperation[] {
            UtilVerbs.doingContextual(spec -> network.refreshClients()), rosterShouldMatch(expectedIds)
        };
    }

    private SpecOperation[] seedStakerAccounts(
            final String networkPrefix, final List<Long> nodeIds, final Map<Long, AccountID> stakerIds) {
        final var ops = new ArrayList<SpecOperation>();
        final var createOps = nodeIds.stream()
                .map(nodeId -> cryptoCreate(stakerAccountName(networkPrefix, nodeId))
                        .payingWith(GENESIS)
                        .key(GENESIS)
                        .balance(ONE_HBAR)
                        .stakedNodeId(nodeId)
                        .exposingCreatedIdTo(id -> stakerIds.put(nodeId, id)))
                .toArray(SpecOperation[]::new);
        ops.add(inParallel(createOps));
        ops.add(waitForNextStakingPeriod());
        ops.add(triggerStakingPeriodUpdate());
        return ops.toArray(SpecOperation[]::new);
    }

    private SpecOperation[] moveStakeToNewNode(
            final String networkPrefix,
            final long removedNodeId,
            final long addedNodeId,
            final Map<Long, AccountID> stakerIds) {
        final var fromAccount = stakerAccountName(networkPrefix, removedNodeId);
        final var toAccount = stakerAccountName(networkPrefix, addedNodeId);
        return new SpecOperation[] {
            UtilVerbs.sourcingContextual(spec -> cryptoCreate(toAccount)
                    .payingWith(GENESIS)
                    .key(GENESIS)
                    .balance(0L)
                    .stakedNodeId(addedNodeId)
                    .setNode(nodeAccountLiteral(spec.targetNetworkOrThrow().nodes()))
                    .exposingCreatedIdTo(id -> stakerIds.put(addedNodeId, id))),
            UtilVerbs.doingContextual(spec -> spec.registry()
                    .saveAccountId(
                            fromAccount,
                            requireNonNull(
                                    stakerIds.get(removedNodeId),
                                    "Missing staker account id for node " + removedNodeId))),
            UtilVerbs.sourcingContextual(spec -> cryptoTransfer(
                            HapiCryptoTransfer.tinyBarsFromTo(fromAccount, toAccount, ONE_HBAR))
                    .payingWith(GENESIS)
                    .signedBy(GENESIS)
                    .setNode(nodeAccountLiteral(spec.targetNetworkOrThrow().nodes()))),
            waitForNextStakingPeriod(),
            triggerStakingPeriodUpdate()
        };
    }

    private static SpecOperation waitForNextStakingPeriod() {
        return UtilVerbs.sourcingContextual(spec -> UtilVerbs.waitUntilStartOfNextStakingPeriod(
                spec.startupProperties().getLong("staking.periodMins")));
    }

    private static SpecOperation triggerStakingPeriodUpdate() {
        return UtilVerbs.sourcingContextual(spec -> {
            final var nodeAccount =
                    nodeAccountLiteral(spec.targetNetworkOrThrow().nodes());
            return triggerAndCloseAtLeastOneFileOnNode(() -> nodeAccount);
        });
    }

    private static SpecOperation triggerAndCloseAtLeastOneFileOnNode(final Supplier<String> nodeSupplier) {
        return UtilVerbs.doingContextual(spec -> {
            spec.sleepConsensusTime(Duration.ofMillis(2_200L));
            final var triggerOp = cryptoTransfer(HapiCryptoTransfer.tinyBarsFromTo(DEFAULT_PAYER, FUNDING, 1L))
                    .deferStatusResolution()
                    .hasAnyStatusAtAll()
                    .noLogging()
                    .setNodeFrom(nodeSupplier);
            CustomSpecAssert.allRunFor(spec, triggerOp);
            spec.sleepConsensusTime(Duration.ofMillis(1_100L));
        });
    }

    private static String stakerAccountName(final String networkPrefix, final long nodeId) {
        return networkPrefix + "-staker-node" + nodeId;
    }

    private static String nodeAccountLiteral(final SubProcessNetwork network, final long nodeId) {
        final var nodes = network.nodes();
        for (final var node : nodes) {
            if (node.getNodeId() == nodeId) {
                return nodeAccountLiteral(node);
            }
        }
        return nodeAccountLiteral(nodes);
    }

    private static String nodeAccountLiteral(final List<HederaNode> nodes) {
        if (nodes.isEmpty()) {
            throw new IllegalStateException("No nodes available for submission");
        }
        return nodeAccountLiteral(nodes.getFirst());
    }

    private static String nodeAccountLiteral(final HederaNode node) {
        final var accountId = node.getAccountId();
        return accountId.shardNum() + "." + accountId.realmNum() + "." + accountId.accountNumOrThrow();
    }

    private static SpecOperation captureBasePorts(final AtomicReference<PortSnapshot> basePorts) {
        return UtilVerbs.doingContextual(spec -> {
            final var base = portsByNodeId(spec.subProcessNetworkOrThrow()).get(0L);
            assertThat(base).as("Node0 baseline ports").isNotNull();
            basePorts.set(base);
        });
    }

    private SpecOperation rosterShouldMatch(final List<Long> expectedIds) {
        return withOpContext((spec, opLog) -> {
            final var actualIds = spec.subProcessNetworkOrThrow().nodes().stream()
                    .map(node -> node.getNodeId())
                    .toList();
            assertThat(actualIds).containsExactlyInAnyOrderElementsOf(expectedIds);
        });
    }

    private static void assertPortsRetained(
            final SubProcessNetwork network,
            final AtomicReference<Map<Long, PortSnapshot>> portSnapshots,
            final AtomicReference<PortSnapshot> basePorts,
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
        final var baseSnapshot = base != null ? base : basePorts.get();
        assertThat(baseSnapshot).as("Node0 baseline ports").isNotNull();
        final var expectedNew = expectedPortsForNode(baseSnapshot, nodeIdToAdd);
        assertThat(current.get(nodeIdToAdd)).isEqualTo(expectedNew);
    }

    private static Map<Long, PortSnapshot> portsByNodeId(final SubProcessNetwork network) {
        return network.nodes().stream()
                .collect(toMap(node -> node.getNodeId(), node -> PortSnapshot.from(node.metadata())));
    }

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

    private record PortSnapshot(
            int grpcPort,
            int grpcNodeOperatorPort,
            int internalGossipPort,
            int externalGossipPort,
            int prometheusPort,
            int debugPort) {
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

    private static ClprLedgerConfiguration fetchLedgerConfiguration(final List<HederaNode> nodes) {
        return fetchLedgerConfiguration(nodes, candidate -> true, "be available");
    }

    private static ClprLedgerConfiguration fetchLedgerConfiguration(
            final List<HederaNode> nodes, final Predicate<ClprLedgerConfiguration> predicate, final String reason) {
        final var deadline = Instant.now().plus(Duration.ofMinutes(60));
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
        throw new IllegalStateException(
                "Unable to fetch CLPR ledger configuration that would " + reason + " (last=" + lastSeen + ")");
    }

    private static ClprLedgerConfiguration tryFetchLedgerConfiguration(final HederaNode node) {
        try {
            try (var client = new ClprClientImpl(toPbjEndpoint(node))) {
                final var proof = client.getConfiguration();
                if (proof == null) {
                    return null;
                }
                final var pbjConfig = ClprStateProofUtils.extractConfiguration(proof);
                final var configBytes =
                        org.hiero.hapi.interledger.state.clpr.ClprLedgerConfiguration.PROTOBUF.toBytes(pbjConfig);
                return ClprLedgerConfiguration.parseFrom(configBytes.toByteArray());
            }
        } catch (UnknownHostException | com.google.protobuf.InvalidProtocolBufferException e) {
            throw new IllegalStateException("Unable to fetch CLPR ledger configuration", e);
        }
    }

    private static ClprLedgerConfiguration tryFetchLedgerConfiguration(
            final HederaNode node, final ClprLedgerId ledgerId) {
        try {
            final var pbjEndpoint = toPbjEndpoint(node);
            final var pbjLedgerId = toPbjLedgerId(ledgerId);
            try (var client = new ClprClientImpl(pbjEndpoint)) {
                final var proof = client.getConfiguration(pbjLedgerId);
                if (proof == null) {
                    return null;
                }
                final var pbjConfig = ClprStateProofUtils.extractConfiguration(proof);
                return toProtoc(pbjConfig);
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static ClprLedgerConfiguration awaitLedgerConfiguration(
            final List<HederaNode> nodes, final ClprLedgerId ledgerId, final Duration timeout) {
        final var deadline = Instant.now().plus(timeout);
        do {
            for (final var node : nodes) {
                final var config = tryFetchLedgerConfiguration(node, ledgerId);
                if (config != null) {
                    return config;
                }
            }
            try {
                Thread.sleep(20_000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        } while (Instant.now().isBefore(deadline));
        throw new IllegalStateException("Timed out waiting for ledger configuration "
                + ledgerId.getLedgerId().toStringUtf8());
    }

    private static ClprLedgerConfiguration awaitMatchingLedgerConfiguration(
            final List<HederaNode> nodes,
            final ClprLedgerConfiguration expected,
            final Duration timeout,
            final Duration pollInterval) {
        requireNonNull(nodes);
        requireNonNull(expected);
        requireNonNull(timeout);
        requireNonNull(pollInterval);
        final var deadline = Instant.now().plus(timeout);
        ClprLedgerConfiguration lastSeen = null;
        do {
            for (final var node : nodes) {
                final var config = tryFetchLedgerConfiguration(node, expected.getLedgerId());
                if (config != null) {
                    lastSeen = config;
                    if (matchesConfigIgnoringTimestamp(expected, config)) {
                        return config;
                    }
                }
            }
            try {
                Thread.sleep(pollInterval.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        } while (Instant.now().isBefore(deadline));
        throw new IllegalStateException("Timed out waiting for ledger configuration "
                + expected.getLedgerId().getLedgerId().toStringUtf8()
                + " endpoints to match expected (last=" + lastSeen + ")");
    }

    private static void submitConfiguration(final HederaNode node, final ClprLedgerConfiguration protocConfig) {
        final var payer = node.getAccountId();
        final var pbjConfig = toPbjConfig(protocConfig);
        final var proof = ClprStateProofUtils.buildLocalClprStateProofWrapper(pbjConfig);
        try (var client = new ClprClientImpl(toPbjEndpoint(node))) {
            client.setConfiguration(payer, payer, proof);
        } catch (final UnknownHostException e) {
            throw new IllegalStateException("Unable to resolve CLPR endpoint for node " + node.getName(), e);
        }
    }

    private static void assertEndpointsMatchNetwork(
            final ClprLedgerConfiguration config, final List<HederaNode> nodes) {
        requireNonNull(config);
        assertThat(config.getEndpointsList()).hasSize(nodes.size());
        nodes.forEach(node -> assertThat(config.getEndpointsList())
                .as("Expected CLPR endpoint for node " + node.getNodeId())
                .anySatisfy(endpoint -> assertEndpointMatchesNode(endpoint, node)));
    }

    private static void assertEndpointMatchesNode(final ClprEndpoint endpoint, final HederaNode node) {
        assertThat(endpoint.hasEndpoint())
                .as("Endpoint metadata should include a service endpoint")
                .isTrue();
        final var serviceEndpoint = endpoint.getEndpoint();
        assertThat(endpoint.getSigningCertificate().isEmpty())
                .as("CLPR endpoints must advertise a signing certificate for node " + node.getNodeId())
                .isFalse();
        assertThat(endpoint.hasNodeAccountId())
                .as("CLPR endpoints must advertise a node account id for node " + node.getNodeId())
                .isTrue();
        assertThat(endpoint.getNodeAccountId())
                .as("CLPR endpoint must advertise the node account id for node " + node.getNodeId())
                .isEqualTo(CommonPbjConverters.fromPbj(node.getAccountId()));
        assertThat(serviceEndpoint.getPort())
                .as("CLPR endpoint should use node " + node.getNodeId() + " gRPC port")
                .isEqualTo(node.getGrpcPort());
        assertThat(ipV4Of(serviceEndpoint))
                .as("CLPR endpoint must advertise the node " + node.getNodeId() + " host")
                .isEqualTo(node.getHost());
    }

    private static void assertNoPublishedEndpoints(final ClprLedgerConfiguration config, final List<HederaNode> nodes) {
        assertThat(config.getEndpointsList())
                .as("Non-publicized configs should still advertise certificates")
                .allSatisfy(endpoint -> {
                    assertThat(endpoint.getSigningCertificate()).isNotEmpty();
                    assertThat(endpoint.hasNodeAccountId()).isTrue();
                    assertThat(endpoint.hasEndpoint()).isFalse();
                });
        assertNodeAccountIdsMatchNetwork(config, nodes);
    }

    private static void assertNodeAccountIdsMatchNetwork(
            final ClprLedgerConfiguration config, final List<HederaNode> nodes) {
        requireNonNull(config);
        requireNonNull(nodes);
        assertThat(config.getEndpointsList()).hasSize(nodes.size());
        nodes.forEach(node -> assertThat(config.getEndpointsList())
                .as("Expected CLPR node account id for node " + node.getNodeId())
                .anySatisfy(endpoint -> assertThat(endpoint.getNodeAccountId())
                        .as("CLPR endpoint must advertise the node account id for node " + node.getNodeId())
                        .isEqualTo(CommonPbjConverters.fromPbj(node.getAccountId()))));
    }

    private static void assertConfigMatchesIgnoringTimestamp(
            final ClprLedgerConfiguration expected, final ClprLedgerConfiguration observed) {
        requireNonNull(expected);
        requireNonNull(observed);
        assertThat(observed.getLedgerId())
                .as("LedgerId should match expected configuration")
                .isEqualTo(expected.getLedgerId());
        assertThat(observed.getEndpointsList())
                .as("CLPR endpoints should match expected configuration (timestamps may differ)")
                .isEqualTo(expected.getEndpointsList());
    }

    private static boolean matchesConfigIgnoringTimestamp(
            final ClprLedgerConfiguration expected, final ClprLedgerConfiguration candidate) {
        return candidate.getLedgerId().equals(expected.getLedgerId())
                && candidate.getEndpointsList().equals(expected.getEndpointsList());
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

    private static String ipV4Of(final com.hederahashgraph.api.proto.java.ServiceEndpoint endpoint) {
        try {
            return InetAddress.getByAddress(endpoint.getIpAddressV4().toByteArray())
                    .getHostAddress();
        } catch (UnknownHostException e) {
            throw new IllegalStateException("CLPR endpoint carried an invalid IPv4 address", e);
        }
    }

    private static com.hedera.hapi.node.base.ServiceEndpoint toPbjEndpoint(final HederaNode node)
            throws UnknownHostException {
        return com.hedera.hapi.node.base.ServiceEndpoint.newBuilder()
                .ipAddressV4(Bytes.wrap(InetAddress.getByName(node.getHost()).getAddress()))
                .port(node.getGrpcPort())
                .build();
    }

    private static org.hiero.hapi.interledger.state.clpr.ClprLedgerId toPbjLedgerId(final ClprLedgerId ledgerId)
            throws ParseException {
        return org.hiero.hapi.interledger.state.clpr.ClprLedgerId.PROTOBUF.parse(
                Bytes.wrap(ledgerId.toByteArray()).toReadableSequentialData());
    }

    private static org.hiero.hapi.interledger.state.clpr.ClprLedgerConfiguration toPbjConfig(
            final ClprLedgerConfiguration config) {
        try {
            return org.hiero.hapi.interledger.state.clpr.ClprLedgerConfiguration.PROTOBUF.parse(
                    Bytes.wrap(config.toByteArray()).toReadableSequentialData());
        } catch (final ParseException e) {
            throw new IllegalStateException("Unable to parse protoc configuration to PBJ", e);
        }
    }

    private static ClprLedgerConfiguration toProtoc(
            final org.hiero.hapi.interledger.state.clpr.ClprLedgerConfiguration config) {
        try {
            return ClprLedgerConfiguration.parseFrom(
                    org.hiero.hapi.interledger.state.clpr.ClprLedgerConfiguration.PROTOBUF
                            .toBytes(config)
                            .toByteArray());
        } catch (final com.google.protobuf.InvalidProtocolBufferException e) {
            throw new IllegalStateException("Unable to convert PBJ configuration to protoc", e);
        }
    }
}
