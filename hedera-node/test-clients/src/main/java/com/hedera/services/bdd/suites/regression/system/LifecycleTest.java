// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.regression.system;

import static com.hedera.services.bdd.junit.hedera.MarkerFile.EXEC_IMMEDIATE_MF;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getVersionInfo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.blockingOrder;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.buildUpgradeZipFrom;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.freezeOnly;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.freezeUpgrade;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.noOp;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.prepareUpgrade;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.purgeUpgradeArtifacts;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.runBackgroundTrafficUntilFreezeComplete;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.updateSpecialFile;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.waitForActive;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.waitForActiveNetworkWithReassignedPorts;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.waitForAny;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.waitForFrozenNetwork;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.waitForMf;
import static com.hedera.services.bdd.spec.utilops.upgrade.BuildUpgradeZipOp.FAKE_UPGRADE_ZIP_LOC;
import static com.hedera.services.bdd.suites.HapiSuite.FUNDING;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.freeze.CommonUpgradeResources.DEFAULT_UPGRADE_FILE_ID;
import static com.hedera.services.bdd.suites.freeze.CommonUpgradeResources.FAKE_ASSETS_LOC;
import static com.hedera.services.bdd.suites.freeze.CommonUpgradeResources.upgradeFileAppendsPerBurst;
import static com.hedera.services.bdd.suites.freeze.CommonUpgradeResources.upgradeFileHashAt;
import static com.hedera.services.bdd.suites.regression.system.MixedOperations.burstOfTps;
import static java.util.Objects.requireNonNull;
import static org.hiero.consensus.model.status.PlatformStatus.ACTIVE;
import static org.hiero.consensus.model.status.PlatformStatus.CATASTROPHIC_FAILURE;

import com.hedera.services.bdd.junit.hedera.NodeSelector;
import com.hedera.services.bdd.junit.hedera.subprocess.SubProcessNetwork;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import com.hedera.services.bdd.spec.utilops.FakeNmt;
import com.hederahashgraph.api.proto.java.SemanticVersion;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Implementation support for tests that exercise the Hedera network lifecycle, including freezes,
 * restarts, software upgrades, and reconnects.
 */
public interface LifecycleTest {
    int MIXED_OPS_BURST_TPS = 50;
    Duration FREEZE_TIMEOUT = Duration.ofSeconds(240);
    Duration RESTART_TIMEOUT = Duration.ofSeconds(180);
    Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(10);
    Duration MIXED_OPS_BURST_DURATION = Duration.ofSeconds(10);
    Duration EXEC_IMMEDIATE_MF_TIMEOUT = Duration.ofSeconds(10);
    Duration RESTART_TO_ACTIVE_TIMEOUT = Duration.ofSeconds(210);
    Duration PORT_UNBINDING_WAIT_PERIOD = Duration.ofSeconds(180);
    /**
     * Legacy global config version used by initial subprocess startups.
     */
    AtomicInteger CURRENT_CONFIG_VERSION = new AtomicInteger(0);
    /**
     * Tracks configuration versions per network to avoid cross-network interference.
     */
    ConcurrentMap<String, AtomicInteger> CONFIG_VERSIONS_BY_NETWORK = new ConcurrentHashMap<>();

    /**
     * Returns the config version tracker for the target network in the given spec.
     *
     * @param spec the HAPI spec in scope
     * @return the config version tracker
     */
    static AtomicInteger configVersionFor(@NonNull final HapiSpec spec) {
        requireNonNull(spec);
        final var networkName = spec.targetNetworkOrThrow().name();
        return CONFIG_VERSIONS_BY_NETWORK.computeIfAbsent(networkName, ignore -> new AtomicInteger(0));
    }

    /**
     * Returns the current config version for the target network in the given spec.
     *
     * @param spec the HAPI spec in scope
     * @return the current config version
     */
    static int currentConfigVersion(@NonNull final HapiSpec spec) {
        return configVersionFor(spec).get();
    }

    /**
     * Sets the current config version for the target network in the given spec.
     *
     * @param spec the HAPI spec in scope
     * @param version the config version to record
     */
    static void setCurrentConfigVersion(@NonNull final HapiSpec spec, final int version) {
        configVersionFor(spec).set(version);
    }

    /**
     * Increments and returns the config version for the target network in the given spec.
     *
     * @param spec the HAPI spec in scope
     * @return the incremented config version
     */
    static int incrementConfigVersion(@NonNull final HapiSpec spec) {
        return configVersionFor(spec).incrementAndGet();
    }

    /**
     * Returns an operation that asserts that the current version of the network has the given
     * semantic version modified by the given config version.
     *
     * @param versionSupplier the supplier of the expected version
     * @return the operation
     */
    default HapiSpecOperation assertGetVersionInfoMatches(@NonNull final Supplier<SemanticVersion> versionSupplier) {
        return sourcingContextual(spec -> getVersionInfo()
                .hasProtoServicesVersion(fromBaseAndConfig(versionSupplier.get(), currentConfigVersion(spec))));
    }

    /**
     * Returns an operation that terminates and reconnects the given node.
     *
     * @param selector the node to reconnect
     * @param configVersion the configuration version to reconnect at
     * @param preReconnectOps operations to run before the node is reconnected
     * @return the operation
     */
    default HapiSpecOperation reconnectNode(
            @NonNull final NodeSelector selector,
            final int configVersion,
            @NonNull final SpecOperation... preReconnectOps) {
        requireNonNull(selector);
        requireNonNull(preReconnectOps);
        return blockingOrder(
                FakeNmt.shutdownWithin(selector, SHUTDOWN_TIMEOUT),
                burstOfTps(MIXED_OPS_BURST_TPS, MIXED_OPS_BURST_DURATION),
                preReconnectOps.length > 0 ? blockingOrder(preReconnectOps) : noOp(),
                FakeNmt.restartWithConfigVersion(selector, configVersion),
                waitForActive(selector, RESTART_TO_ACTIVE_TIMEOUT));
    }

    /**
     * Returns an operation that terminates and reconnects the given node.
     *
     * @param selector the node to reconnect
     * @param configVersion the configuration version to reconnect at
     * @param preReconnectOps operations to run before the node is reconnected
     * @return the operation
     */
    default HapiSpecOperation reconnectIssNode(
            @NonNull final NodeSelector selector,
            final int configVersion,
            @NonNull final SpecOperation... preReconnectOps) {
        requireNonNull(selector);
        requireNonNull(preReconnectOps);
        return blockingOrder(
                FakeNmt.shutdownWithin(selector, SHUTDOWN_TIMEOUT),
                burstOfTps(MIXED_OPS_BURST_TPS, MIXED_OPS_BURST_DURATION),
                preReconnectOps.length > 0 ? blockingOrder(preReconnectOps) : noOp(),
                FakeNmt.restartWithConfigVersion(selector, configVersion),
                waitForAny(selector, RESTART_TO_ACTIVE_TIMEOUT, ACTIVE, CATASTROPHIC_FAILURE));
    }

    /**
     * Returns an operation that builds a fake upgrade ZIP, uploads it to file {@code 0.0.150},
     * issues a {@code PREPARE_UPGRADE}, and awaits writing of the <i>execute_immediate.mf</i>.
     * @return the operation
     */
    default HapiSpecOperation prepareFakeUpgrade() {
        return blockingOrder(
                buildUpgradeZipFrom(FAKE_ASSETS_LOC),
                // Upload it to file 0.0.150; need sourcing() here because the operation reads contents eagerly
                sourcing(() -> updateSpecialFile(
                        GENESIS,
                        DEFAULT_UPGRADE_FILE_ID,
                        FAKE_UPGRADE_ZIP_LOC,
                        TxnUtils.BYTES_4K,
                        upgradeFileAppendsPerBurst())),
                purgeUpgradeArtifacts(),
                // Issue PREPARE_UPGRADE; need sourcing() here because we want to hash only after creating the ZIP
                sourcing(() -> prepareUpgrade()
                        .withUpdateFile(DEFAULT_UPGRADE_FILE_ID)
                        .havingHash(upgradeFileHashAt(FAKE_UPGRADE_ZIP_LOC))),
                // Wait for the immediate execution marker file (written only after 0.0.150 is unzipped)
                waitForMf(EXEC_IMMEDIATE_MF, LifecycleTest.EXEC_IMMEDIATE_MF_TIMEOUT));
    }

    /**
     * Returns an operation that upgrades the network to the next configuration version using a fake upgrade ZIP.
     * @return the operation
     */
    default SpecOperation upgradeToNextConfigVersion() {
        return sourcingContextual(spec -> upgradeToConfigVersion(currentConfigVersion(spec) + 1, Map.of(), noOp()));
    }

    /**
     * Returns an operation that upgrades the network to the next configuration version without
     * refreshing override-network.json files.
     *
     * @return the operation
     */
    default SpecOperation upgradeToNextConfigVersionWithoutOverrides() {
        return sourcingContextual(
                spec -> upgradeToConfigVersionWithoutOverrides(currentConfigVersion(spec) + 1, Map.of(), noOp()));
    }

    /**
     * Returns an operation that advances the network to the next configuration version, but does not
     * start any nodes yet. Call {@link #resumeUpgradedNetwork(NodeSelector)} to start nodes afterward.
     *
     * @param preRestartOps operations to run before the network is restarted
     * @return the operation that prepares the upgrade and shuts down the network
     */
    default SpecOperation beginUpgradeToNextConfigVersion(@NonNull final SpecOperation... preRestartOps) {
        requireNonNull(preRestartOps);
        return sourcingContextual(spec -> beginUpgradeToConfigVersion(currentConfigVersion(spec) + 1, preRestartOps));
    }

    /**
     * Returns an operation that advances the network to the given configuration version, but does not
     * start any nodes yet. Call {@link #resumeUpgradedNetwork(NodeSelector)} to start nodes afterward.
     *
     * @param version the configuration version to upgrade to
     * @param preRestartOps operations to run before the network is restarted
     * @return the operation that prepares the upgrade and shuts down the network
     */
    default HapiSpecOperation beginUpgradeToConfigVersion(
            final int version, @NonNull final SpecOperation... preRestartOps) {
        requireNonNull(preRestartOps);
        return blockingOrder(
                runBackgroundTrafficUntilFreezeComplete(),
                sourcing(() -> freezeUpgrade()
                        .startingIn(2)
                        .seconds()
                        .withUpdateFile(DEFAULT_UPGRADE_FILE_ID)
                        .havingHash(upgradeFileHashAt(FAKE_UPGRADE_ZIP_LOC))),
                confirmFreezeAndShutdown(),
                blockingOrder(preRestartOps),
                doingContextual(spec -> setCurrentConfigVersion(spec, version)));
    }

    /**
     * Returns an operation that starts the selected nodes with the current configuration version.
     *
     * @param selector the selector for the nodes to start
     * @param envOverrides the environment overrides to use
     * @return the operation that starts the nodes
     */
    default SpecOperation resumeUpgradedNetwork(
            @NonNull final NodeSelector selector, @NonNull final Map<String, String> envOverrides) {
        requireNonNull(selector);
        requireNonNull(envOverrides);
        return sourcingContextual(spec -> FakeNmt.startNodes(selector, currentConfigVersion(spec), envOverrides));
    }

    /**
     * Returns an operation that starts the selected nodes with the current configuration version.
     *
     * @param selector the selector for the nodes to start
     * @return the operation that starts the nodes
     */
    default SpecOperation resumeUpgradedNetwork(@NonNull final NodeSelector selector) {
        return resumeUpgradedNetwork(selector, Map.of());
    }

    /**
     * Returns an operation that starts the selected nodes with the current configuration version
     * without refreshing override-network.json files.
     *
     * @param selector the selector for the nodes to start
     * @param envOverrides the environment overrides to use
     * @return the operation that starts the nodes
     */
    default SpecOperation resumeUpgradedNetworkWithoutOverrides(
            @NonNull final NodeSelector selector, @NonNull final Map<String, String> envOverrides) {
        requireNonNull(selector);
        requireNonNull(envOverrides);
        return sourcingContextual(
                spec -> FakeNmt.startNodesNoOverride(selector, currentConfigVersion(spec), envOverrides));
    }

    /**
     * Returns an operation that starts the selected nodes with the current configuration version
     * without refreshing override-network.json files.
     *
     * @param selector the selector for the nodes to start
     * @return the operation that starts the nodes
     */
    default SpecOperation resumeUpgradedNetworkWithoutOverrides(@NonNull final NodeSelector selector) {
        return resumeUpgradedNetworkWithoutOverrides(selector, Map.of());
    }

    /**
     * Returns an operation that upgrades the network to the next configuration version and defers
     * starting the given node ids until after existing nodes are ACTIVE and a signed state is copied.
     *
     * @param deferredNodeIds the node ids to defer starting
     * @param sourceNodeId the node id to copy signed state and PCES from
     * @param signedStateTimeout the timeout for waiting on a post-upgrade signed state
     * @param preRestartOps operations to run before the network is restarted
     * @param postResumeOps operations to run after existing nodes are ACTIVE, before copying state
     * @return the operation that performs the staged upgrade
     */
    default SpecOperation upgradeToNextConfigVersionWithDeferredNodes(
            @NonNull final List<Long> deferredNodeIds,
            final long sourceNodeId,
            @NonNull final Duration signedStateTimeout,
            @NonNull final Map<String, String> envOverrides,
            @NonNull final SpecOperation preRestartOps,
            @NonNull final SpecOperation postResumeOps) {
        requireNonNull(deferredNodeIds);
        requireNonNull(signedStateTimeout);
        requireNonNull(envOverrides);
        requireNonNull(preRestartOps);
        requireNonNull(postResumeOps);
        if (deferredNodeIds.isEmpty()) {
            throw new IllegalArgumentException("Deferred node ids must not be empty");
        }
        final var baselineSignedStateRound = new AtomicLong(-1L);
        final var deferredIds =
                deferredNodeIds.stream().mapToLong(Long::longValue).toArray();
        final NodeSelector deferredSelector = node -> deferredNodeIds.contains(node.getNodeId());
        final var existingSelector = NodeSelector.exceptNodeIds(deferredIds);
        return blockingOrder(
                doingContextual(spec -> {
                    final var subProcessNetwork = spec.subProcessNetworkOrThrow();
                    setCurrentConfigVersion(spec, subProcessNetwork.currentConfigVersion(sourceNodeId));
                    baselineSignedStateRound.set(subProcessNetwork.latestSignedStateRound(sourceNodeId));
                    subProcessNetwork.clearOverrideNetworks();
                    subProcessNetwork.assertNoOverrideNetworks();
                }),
                beginUpgradeToNextConfigVersion(preRestartOps),
                resumeUpgradedNetworkWithoutOverrides(existingSelector, envOverrides),
                waitForActive(existingSelector, RESTART_TO_ACTIVE_TIMEOUT),
                doingContextual(spec -> spec.subProcessNetworkOrThrow().refreshClients()),
                postResumeOps,
                doingContextual(spec -> {
                    final var subProcessNetwork = spec.subProcessNetworkOrThrow();
                    subProcessNetwork.awaitSignedStateAfterRoundWithRosterEntries(
                            sourceNodeId, baselineSignedStateRound.get(), signedStateTimeout, deferredNodeIds);
                    final var firstRound = subProcessNetwork.latestSignedStateRound(sourceNodeId);
                    // Recommended: wait for a second signed state so copied PCES has fewer events
                    // from nodes removed from the roster.
                    subProcessNetwork.awaitSignedStateAfterRoundWithRosterEntries(
                            sourceNodeId, firstRound, signedStateTimeout, deferredNodeIds);
                }),
                doingContextual(spec -> {
                    final var subProcessNetwork = spec.subProcessNetworkOrThrow();
                    for (final var deferredNodeId : deferredNodeIds) {
                        subProcessNetwork.copyLatestSignedStateAndPces(sourceNodeId, deferredNodeId);
                    }
                }),
                resumeUpgradedNetworkWithoutOverrides(deferredSelector, envOverrides),
                waitForActive(deferredSelector, RESTART_TO_ACTIVE_TIMEOUT),
                doingContextual(spec -> spec.subProcessNetworkOrThrow().refreshClients()),
                waitForActive(NodeSelector.allNodes(), RESTART_TO_ACTIVE_TIMEOUT),
                doingContextual(spec -> {
                    if (spec.targetNetworkOrThrow() instanceof SubProcessNetwork subProcessNetwork) {
                        subProcessNetwork.assertNoOverrideNetworks();
                    }
                }));
    }

    /**
     * Returns an operation that upgrades the network to the next configuration version and defers
     * starting the given node ids until after existing nodes are ACTIVE and a signed state is copied,
     * using a default signed-state timeout.
     *
     * @param deferredNodeIds the node ids to defer starting
     * @param sourceNodeId the node id to copy signed state and PCES from
     * @param preRestartOps operations to run before the network is restarted
     * @param postResumeOps operations to run after existing nodes are ACTIVE, before copying state
     * @return the operation that performs the staged upgrade
     */
    default SpecOperation upgradeToNextConfigVersionWithDeferredNodes(
            @NonNull final List<Long> deferredNodeIds,
            final long sourceNodeId,
            @NonNull final SpecOperation preRestartOps,
            @NonNull final SpecOperation postResumeOps) {
        return upgradeToNextConfigVersionWithDeferredNodes(
                deferredNodeIds, sourceNodeId, Duration.ofMinutes(1), Map.of(), preRestartOps, postResumeOps);
    }

    /**
     * Returns an operation that upgrades the network to the next configuration version and defers
     * starting the given node ids until after existing nodes are ACTIVE and a signed state is copied,
     * using a default signed-state timeout.
     *
     * @param deferredNodeIds the node ids to defer starting
     * @param sourceNodeId the node id to copy signed state and PCES from
     * @param envOverrides the environment overrides to use when starting nodes
     * @param preRestartOps operations to run before the network is restarted
     * @param postResumeOps operations to run after existing nodes are ACTIVE, before copying state
     * @return the operation that performs the staged upgrade
     */
    default SpecOperation upgradeToNextConfigVersionWithDeferredNodes(
            @NonNull final List<Long> deferredNodeIds,
            final long sourceNodeId,
            @NonNull final Map<String, String> envOverrides,
            @NonNull final SpecOperation preRestartOps,
            @NonNull final SpecOperation postResumeOps) {
        return upgradeToNextConfigVersionWithDeferredNodes(
                deferredNodeIds, sourceNodeId, Duration.ofMinutes(1), envOverrides, preRestartOps, postResumeOps);
    }

    /**
     * Returns an operation that upgrades the network to the next configuration version and defers
     * starting the given node ids until after existing nodes are ACTIVE and a signed state is copied,
     * using default signed-state timeout and no post-resume operations.
     *
     * @param deferredNodeIds the node ids to defer starting
     * @param sourceNodeId the node id to copy signed state and PCES from
     * @param envOverrides the environment overrides to use when starting nodes
     * @param preRestartOps operations to run before the network is restarted
     * @return the operation that performs the staged upgrade
     */
    default SpecOperation upgradeToNextConfigVersionWithDeferredNodes(
            @NonNull final List<Long> deferredNodeIds,
            final long sourceNodeId,
            @NonNull final Map<String, String> envOverrides,
            @NonNull final SpecOperation preRestartOps) {
        return upgradeToNextConfigVersionWithDeferredNodes(
                deferredNodeIds, sourceNodeId, Duration.ofMinutes(1), envOverrides, preRestartOps, noOp());
    }

    /**
     * Returns an operation that upgrades the network to the next configuration version and defers
     * starting the given node ids until after existing nodes are ACTIVE and a signed state is copied,
     * using default signed-state timeout and no post-resume operations.
     *
     * @param deferredNodeIds the node ids to defer starting
     * @param sourceNodeId the node id to copy signed state and PCES from
     * @param preRestartOps operations to run before the network is restarted
     * @return the operation that performs the staged upgrade
     */
    default SpecOperation upgradeToNextConfigVersionWithDeferredNodes(
            @NonNull final List<Long> deferredNodeIds,
            final long sourceNodeId,
            @NonNull final SpecOperation preRestartOps) {
        return upgradeToNextConfigVersionWithDeferredNodes(
                deferredNodeIds, sourceNodeId, Duration.ofMinutes(1), Map.of(), preRestartOps, noOp());
    }

    /**
     * Returns an operation that upgrades the network to the next configuration version using a fake upgrade ZIP.
     * @return the operation
     */
    static SpecOperation restartAtNextConfigVersion() {
        return blockingOrder(
                freezeOnly().startingIn(5).seconds().payingWith(GENESIS).deferStatusResolution(),
                // Immediately submit a transaction in the same round to ensure freeze time is only
                // reset when last frozen time matches it (i.e., in a post-upgrade transaction)
                cryptoTransfer(tinyBarsFromTo(GENESIS, FUNDING, 1)),
                confirmFreezeAndShutdown(),
                sourcingContextual(spec -> FakeNmt.restartNetwork(incrementConfigVersion(spec), Map.of())),
                waitForActiveNetworkWithReassignedPorts(RESTART_TIMEOUT));
    }

    /**
     * Returns an operation that upgrades the network with disabled node operator port to the next configuration version using a fake upgrade ZIP.
     * @return the operation
     */
    static SpecOperation restartWithDisabledNodeOperatorGrpcPort() {
        return restartAtNextConfigVersionVia(sourcingContextual(
                spec -> FakeNmt.restartNetworkWithDisabledNodeOperatorPort(incrementConfigVersion(spec))));
    }

    /**
     * Returns an operation that upgrades the network to the next configuration version using a fake upgrade ZIP.
     * @return the operation
     */
    static SpecOperation restartAtNextConfigVersionVia(@NonNull final SpecOperation restartOp) {
        requireNonNull(restartOp);
        return blockingOrder(
                freezeOnly().startingIn(5).seconds().payingWith(GENESIS).deferStatusResolution(),
                // Immediately submit a transaction in the same round to ensure freeze time is only
                // reset when last frozen time matches it (i.e., in a post-upgrade transaction)
                cryptoTransfer(tinyBarsFromTo(GENESIS, FUNDING, 1)),
                confirmFreezeAndShutdown(),
                restartOp,
                waitForActiveNetworkWithReassignedPorts(RESTART_TIMEOUT));
    }

    /**
     * Returns an operation that upgrades the network to the given configuration version using a fake upgrade ZIP.
     * @param version the configuration version to upgrade to
     * @return the operation
     */
    default HapiSpecOperation upgradeToConfigVersion(final int version) {
        return upgradeToConfigVersion(version, Map.of(), noOp());
    }

    /**
     * Returns an operation that upgrades the network to the next configuration version using a fake upgrade ZIP,
     * running the given operation before the network is restarted.
     *
     * @param envOverrides the environment overrides to use
     * @param preRestartOps operations to run before the network is restarted
     * @return the operation
     */
    default SpecOperation upgradeToNextConfigVersion(
            @NonNull final Map<String, String> envOverrides, @NonNull final SpecOperation... preRestartOps) {
        requireNonNull(envOverrides);
        requireNonNull(preRestartOps);
        return sourcingContextual(
                spec -> upgradeToConfigVersion(currentConfigVersion(spec) + 1, envOverrides, preRestartOps));
    }

    /**
     * Returns an operation that upgrades the network to the next configuration version without
     * refreshing override-network.json files, running the given operations before restart.
     *
     * @param envOverrides the environment overrides to use
     * @param preRestartOps operations to run before the network is restarted
     * @return the operation that performs the upgrade
     */
    default SpecOperation upgradeToNextConfigVersionWithoutOverrides(
            @NonNull final Map<String, String> envOverrides, @NonNull final SpecOperation... preRestartOps) {
        requireNonNull(envOverrides);
        requireNonNull(preRestartOps);
        return sourcingContextual(spec ->
                upgradeToConfigVersionWithoutOverrides(currentConfigVersion(spec) + 1, envOverrides, preRestartOps));
    }

    /**
     * Returns an operation that upgrades the network to the given configuration version using a fake upgrade ZIP,
     * running the given operation before the network is restarted.
     *
     * @param version the configuration version to upgrade to
     * @param envOverrides the environment overrides to use
     * @param preRestartOps operations to run before the network is restarted
     * @return the operation
     */
    default HapiSpecOperation upgradeToConfigVersion(
            final int version,
            @NonNull final Map<String, String> envOverrides,
            @NonNull final SpecOperation... preRestartOps) {
        requireNonNull(preRestartOps);
        requireNonNull(envOverrides);
        return blockingOrder(
                runBackgroundTrafficUntilFreezeComplete(),
                sourcing(() -> freezeUpgrade()
                        .startingIn(2)
                        .seconds()
                        .withUpdateFile(DEFAULT_UPGRADE_FILE_ID)
                        .havingHash(upgradeFileHashAt(FAKE_UPGRADE_ZIP_LOC))),
                confirmFreezeAndShutdown(),
                blockingOrder(preRestartOps),
                FakeNmt.restartNetwork(version, envOverrides),
                doingContextual(spec -> setCurrentConfigVersion(spec, version)),
                waitForActiveNetworkWithReassignedPorts(RESTART_TIMEOUT),
                cryptoCreate("postUpgradeAccount"),
                // Ensure we have a post-upgrade transaction in a new period to trigger
                // system file exports while still streaming records
                doingContextual(TxnUtils::triggerAndCloseAtLeastOneFileIfNotInterrupted));
    }

    /**
     * Returns an operation that upgrades the network to the given configuration version using a fake upgrade ZIP,
     * without refreshing override-network.json files, and running the given operation before restart.
     *
     * @param version the configuration version to upgrade to
     * @param envOverrides the environment overrides to use
     * @param preRestartOps operations to run before the network is restarted
     * @return the operation
     */
    default HapiSpecOperation upgradeToConfigVersionWithoutOverrides(
            final int version,
            @NonNull final Map<String, String> envOverrides,
            @NonNull final SpecOperation... preRestartOps) {
        requireNonNull(preRestartOps);
        requireNonNull(envOverrides);
        return blockingOrder(
                doingContextual(spec -> {
                    if (spec.targetNetworkOrThrow() instanceof SubProcessNetwork subProcessNetwork) {
                        subProcessNetwork.clearOverrideNetworks();
                        subProcessNetwork.assertNoOverrideNetworks();
                    }
                }),
                runBackgroundTrafficUntilFreezeComplete(),
                sourcing(() -> freezeUpgrade()
                        .startingIn(2)
                        .seconds()
                        .withUpdateFile(DEFAULT_UPGRADE_FILE_ID)
                        .havingHash(upgradeFileHashAt(FAKE_UPGRADE_ZIP_LOC))),
                confirmFreezeAndShutdown(),
                blockingOrder(preRestartOps),
                FakeNmt.restartNetworkNoOverride(version, envOverrides),
                doingContextual(spec -> setCurrentConfigVersion(spec, version)),
                waitForActiveNetworkWithReassignedPorts(RESTART_TIMEOUT),
                doingContextual(spec -> {
                    if (spec.targetNetworkOrThrow() instanceof SubProcessNetwork subProcessNetwork) {
                        subProcessNetwork.assertNoOverrideNetworks();
                    }
                }),
                cryptoCreate("postUpgradeAccount"),
                // Ensure we have a post-upgrade transaction in a new period to trigger
                // system file exports while still streaming records
                doingContextual(TxnUtils::triggerAndCloseAtLeastOneFileIfNotInterrupted));
    }

    /**
     * Returns an operation that confirms the network has been frozen and shut down.
     * @return the operation
     */
    static HapiSpecOperation confirmFreezeAndShutdown() {
        return blockingOrder(
                waitForFrozenNetwork(FREEZE_TIMEOUT),
                // Shut down all nodes, since the platform doesn't automatically go back to ACTIVE status
                FakeNmt.shutdownNetworkWithin(SHUTDOWN_TIMEOUT));
    }

    /**
     * Returns a {@link SemanticVersion} that combines the given version with the given configuration version.
     *
     * @param version the base version
     * @param configVersion the configuration version
     * @return the combined version
     */
    static SemanticVersion fromBaseAndConfig(@NonNull SemanticVersion version, int configVersion) {
        return (configVersion == 0)
                ? version
                : version.toBuilder().setBuild("" + configVersion).build();
    }

    /**
     * Returns the config version parsed from the build metadata in a {@link SemanticVersion}.
     *
     * @param version the base version
     * @return the config version
     */
    static int configVersionOf(@NonNull SemanticVersion version) {
        final var build = version.getBuild();
        return build.isBlank() ? 0 : Integer.parseInt(build.substring(build.indexOf("c") + 1));
    }
}
