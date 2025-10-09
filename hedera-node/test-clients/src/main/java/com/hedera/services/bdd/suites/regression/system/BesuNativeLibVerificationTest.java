// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.regression.system;

import static com.hedera.services.bdd.junit.TestTags.RESTART;
import static com.hedera.services.bdd.junit.hedera.NodeSelector.allNodes;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.assertHgcaaLogContains;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.blockingOrder;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doAdhoc;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.freezeUpgrade;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.ifCi;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.ifNotCi;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.runBackgroundTrafficUntilFreezeComplete;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.upgrade.BuildUpgradeZipOp.FAKE_UPGRADE_ZIP_LOC;
import static com.hedera.services.bdd.suites.freeze.CommonUpgradeResources.DEFAULT_UPGRADE_FILE_ID;
import static com.hedera.services.bdd.suites.freeze.CommonUpgradeResources.upgradeFileHashAt;
import static java.util.Objects.requireNonNull;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.OrderedInIsolation;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.utilops.FakeNmt;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Stream;
import org.hyperledger.besu.crypto.SECP256K1;
import org.hyperledger.besu.crypto.SECP256R1;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;

// Order to be last as it will restart the network and halt if the lib is not present
@OrderedInIsolation
@Order(Integer.MAX_VALUE)
@Tag(RESTART)
@Disabled("Only to be run manually on a local env when validating native lib verification behavior")
public class BesuNativeLibVerificationTest implements LifecycleTest {

    @HapiTest
    public Stream<DynamicTest> besuNativeLibVerificationHaltsIfLibNotPresent() {

        final var envOverrides = Map.of("contracts.evm.nativeLibVerification.halt.enabled", "true");

        return hapiTest(blockingOrder(
                upgradeToNextConfigVersion(
                        envOverrides, doAdhoc(() -> new SECP256K1().disableNative()), doAdhoc(() -> new SECP256R1()
                                .disableNative())),
                ifNotCi(
                        assertHgcaaLogContains(allNodes(), "ERROR", Duration.ofSeconds(60)),
                        assertHgcaaLogContains(allNodes(), "Native library", Duration.ZERO),
                        assertHgcaaLogContains(
                                allNodes(),
                                "is not present with halt mode enabled! Shutting down node.",
                                Duration.ZERO)),
                ifCi(
                        assertHgcaaLogContains(
                                allNodes(),
                                "Native library verification Halt mode is enabled",
                                Duration.ofSeconds(300)),
                        doAdhoc(LifecycleTest::restartAtNextConfigVersion))));
    }

    @Override
    public SpecOperation upgradeToNextConfigVersion(
            @NonNull Map<String, String> envOverrides, @NonNull SpecOperation... preRestartOps) {
        final var version = CURRENT_CONFIG_VERSION.incrementAndGet();
        requireNonNull(preRestartOps);
        requireNonNull(envOverrides);
        return blockingOrder(
                runBackgroundTrafficUntilFreezeComplete(),
                sourcing(() -> freezeUpgrade()
                        .startingIn(2)
                        .seconds()
                        .withUpdateFile(DEFAULT_UPGRADE_FILE_ID)
                        .havingHash(upgradeFileHashAt(FAKE_UPGRADE_ZIP_LOC))),
                LifecycleTest.confirmFreezeAndShutdown(),
                blockingOrder(preRestartOps),
                // we just restart the network with the new config version
                FakeNmt.restartNetwork(version, envOverrides),
                doAdhoc(() -> CURRENT_CONFIG_VERSION.set(version)));
    }
}
