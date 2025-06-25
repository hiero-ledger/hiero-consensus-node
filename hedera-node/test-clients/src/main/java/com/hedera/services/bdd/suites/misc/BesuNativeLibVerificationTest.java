// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.misc;

import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.utilops.FakeNmt.restartNetwork;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.blockingOrder;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doAdhoc;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.freezeOnly;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sleepForSeconds;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.waitForAny;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.regression.system.LifecycleTest.confirmFreezeAndShutdown;
import static org.hiero.consensus.model.status.PlatformStatus.ACTIVE;
import static org.hiero.consensus.model.status.PlatformStatus.STARTING_UP;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.OrderedInIsolation;
import com.hedera.services.bdd.junit.hedera.NodeSelector;
import com.hedera.services.bdd.suites.regression.system.LifecycleTest;
import java.util.Map;
import java.util.stream.Stream;
import org.hyperledger.besu.crypto.Blake2bfMessageDigest.Blake2bfDigest;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Order;

// Order to be last as it will restart the network and halt if the lib is not present
@Order(Integer.MAX_VALUE)
@OrderedInIsolation
public class BesuNativeLibVerificationTest implements LifecycleTest {

    @HapiTest
    public Stream<DynamicTest> besuNativeLibVerificationHaltsIfLibNotPresent() {

        final var envOverrides = Map.of("contracts.evm.nativeLibVerification.halt.enabled", "true");
        final var envOverridesAfterReset = Map.of("contracts.evm.nativeLibVerification.halt.enabled", "false");

        return hapiTest(blockingOrder(
                doAdhoc(Blake2bfDigest::disableNative),
                doingContextual(
                        spec -> waitForAny(NodeSelector.allNodes(), RESTART_TO_ACTIVE_TIMEOUT, STARTING_UP, ACTIVE)),
                freezeOnly().startingIn(5).seconds().payingWith(GENESIS).deferStatusResolution(),
                confirmFreezeAndShutdown(),
                sleepForSeconds(5),
                restartNetwork(CURRENT_CONFIG_VERSION.get() + 1, envOverrides),
                doAdhoc(() -> CURRENT_CONFIG_VERSION.set(CURRENT_CONFIG_VERSION.get() + 1)),
                doingContextual(
                        spec -> waitForAny(NodeSelector.allNodes(), RESTART_TO_ACTIVE_TIMEOUT, STARTING_UP, ACTIVE)),
                confirmFreezeAndShutdown(),
                // sleep and restore state without the native lib verification halt enabled
                sleepForSeconds(5),
                restartNetwork(CURRENT_CONFIG_VERSION.get() + 2, envOverridesAfterReset),
                doAdhoc(() -> CURRENT_CONFIG_VERSION.set(CURRENT_CONFIG_VERSION.get() + 2)),
                doingContextual(
                        spec -> waitForAny(NodeSelector.allNodes(), RESTART_TO_ACTIVE_TIMEOUT, STARTING_UP, ACTIVE))));
    }
}
