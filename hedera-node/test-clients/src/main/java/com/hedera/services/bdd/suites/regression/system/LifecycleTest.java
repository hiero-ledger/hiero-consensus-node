// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.regression.system;

import static com.hedera.services.bdd.junit.hedera.MarkerFile.EXEC_IMMEDIATE_MF;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getVersionInfo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.blockingOrder;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.buildUpgradeZipFrom;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doAdhoc;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.freezeOnly;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.freezeUpgrade;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.noOp;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.prepareUpgrade;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.purgeUpgradeArtifacts;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.runBackgroundTrafficUntilFreezeComplete;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
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
import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import com.hedera.services.bdd.spec.utilops.FakeNmt;
import com.hederahashgraph.api.proto.java.SemanticVersion;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
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
    AtomicInteger CURRENT_CONFIG_VERSION = new AtomicInteger(0);

    /**
     * Returns an operation that asserts that the current version of the network has the given
     * semantic version modified by the given config version.
     *
     * @param versionSupplier the supplier of the expected version
     * @return the operation
     */
    default HapiSpecOperation assertGetVersionInfoMatches(@NonNull final Supplier<SemanticVersion> versionSupplier) {
        return sourcing(() -> getVersionInfo()
                .hasProtoServicesVersion(fromBaseAndConfig(versionSupplier.get(), CURRENT_CONFIG_VERSION.get())));
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
        return sourcing(() -> upgradeToConfigVersion(CURRENT_CONFIG_VERSION.get() + 1, Map.of(), noOp()));
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
                sourcing(() -> FakeNmt.restartNetwork(CURRENT_CONFIG_VERSION.incrementAndGet(), Map.of())),
                waitForActiveNetworkWithReassignedPorts(RESTART_TIMEOUT));
    }

    /**
     * Returns an operation that upgrades the network with disabled node operator port to the next configuration version using a fake upgrade ZIP.
     * @return the operation
     */
    static SpecOperation restartWithDisabledNodeOperatorGrpcPort() {
        return restartAtNextConfigVersionVia(sourcing(
                () -> FakeNmt.restartNetworkWithDisabledNodeOperatorPort(CURRENT_CONFIG_VERSION.incrementAndGet())));
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
        return sourcing(() -> upgradeToConfigVersion(CURRENT_CONFIG_VERSION.get() + 1, envOverrides, preRestartOps));
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
                doAdhoc(() -> CURRENT_CONFIG_VERSION.set(version)),
                waitForActiveNetworkWithReassignedPorts(RESTART_TIMEOUT),
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
     * Returns a {@link SemanticVersion} that combines the given version with the given configuration version.
     *
     * @param version the base version
     * @return the config version
     */
    static int configVersionOf(@NonNull SemanticVersion version) {
        final var build = version.getBuild();
        return build.isBlank() ? 0 : Integer.parseInt(build.substring(build.indexOf("c") + 1));
    }
}
