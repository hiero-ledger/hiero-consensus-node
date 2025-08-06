// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.misc;

import static com.hedera.services.bdd.junit.ContextRequirement.PROPERTY_OVERRIDES;
import static com.hedera.services.bdd.junit.TestTags.NOT_REPEATABLE;
import static com.hedera.services.bdd.junit.hedera.NodeSelector.allNodes;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.utilops.FakeNmt.restartNetwork;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.assertHgcaaLogContains;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.blockingOrder;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doAdhoc;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.freezeOnly;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.ifCi;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.ifNotCi;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sleepForSeconds;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.waitForActive;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.waitForAny;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.waitForFrozenNetwork;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static org.hiero.consensus.model.status.PlatformStatus.STARTING_UP;

import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.junit.OrderedInIsolation;
import com.hedera.services.bdd.suites.regression.system.LifecycleTest;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Stream;
import org.hyperledger.besu.crypto.Blake2bfMessageDigest.Blake2bfDigest;
import org.hyperledger.besu.crypto.SECP256K1;
import org.hyperledger.besu.crypto.SECP256R1;
import org.hyperledger.besu.evm.precompile.AbstractAltBnPrecompiledContract;
import org.hyperledger.besu.evm.precompile.BigIntegerModularExponentiationPrecompiledContract;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;

// Order to be last as it will restart the network and halt if the lib is not present
@Order(Integer.MAX_VALUE)
@OrderedInIsolation
@Tag(NOT_REPEATABLE)
public class BesuNativeLibVerificationTest implements LifecycleTest {

    @LeakyHapiTest(requirement = PROPERTY_OVERRIDES)
    public Stream<DynamicTest> besuNativeLibVerificationHaltsIfLibNotPresent() {

        final var envOverrides = Map.of("contracts.evm.nativeLibVerification.halt.enabled", "true");

        return hapiTest(
                blockingOrder(
                        freezeOnly().startingIn(5).seconds().payingWith(GENESIS).deferStatusResolution(),
                        waitForFrozenNetwork(FREEZE_TIMEOUT),
                        LifecycleTest.confirmFreezeAndShutdown(),
                        sleepForSeconds(10),
                        doAdhoc(Blake2bfDigest::disableNative),
                        doAdhoc(BigIntegerModularExponentiationPrecompiledContract::disableNative),
                        doAdhoc(AbstractAltBnPrecompiledContract::disableNative),
                        doAdhoc(() -> new SECP256K1().disableNative()),
                        doAdhoc(() -> new SECP256R1().disableNative()),
                        restartNetwork(CURRENT_CONFIG_VERSION.get() + 1, envOverrides),
                        doAdhoc(() -> CURRENT_CONFIG_VERSION.set(CURRENT_CONFIG_VERSION.get() + 1)),
                        doingContextual(spec -> waitForAny(allNodes(), RESTART_TO_ACTIVE_TIMEOUT, STARTING_UP)),
                        doingContextual(spec -> waitForActive(allNodes(), RESTART_TO_ACTIVE_TIMEOUT)),
                        ifNotCi(
                                assertHgcaaLogContains(allNodes(), "ERROR", Duration.ofSeconds(60)),
                                assertHgcaaLogContains(allNodes(), "Native library", Duration.ZERO),
                                assertHgcaaLogContains(
                                        allNodes(),
                                        "is not present with halt mode enabled! Shutting down node.",
                                        Duration.ZERO))),
                ifCi(assertHgcaaLogContains(
                        allNodes(), "Native library verification Halt mode is enabled", Duration.ofSeconds(300))));
    }
}
