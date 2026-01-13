// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.throttling;

import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.utilops.SysFileOverrideOp.Target.THROTTLES;
import static com.hedera.services.bdd.spec.utilops.SysFileOverrideOp.withoutAutoRestoring;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.inParallel;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.BUSY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.OrderedInIsolation;
import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import com.hedera.services.bdd.spec.utilops.SysFileOverrideOp;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Order;

/**
 * Tests for HIP-1313 high-volume throttling.
 * Validates that transactions with highVolume=true use separate high-volume throttle buckets.
 */
@OrderedInIsolation
public class HighVolumeThrottlingTest {

    private static final SysFileOverrideOp throttleOverrideOp =
            withoutAutoRestoring(THROTTLES, () -> TxnUtils.resourceAsString("testSystemFiles/high-volume-throttles.json"));

    @HapiTest
    @Order(1)
    final Stream<DynamicTest> setHighVolumeThrottles() {
        return hapiTest(throttleOverrideOp);
    }

    @HapiTest
    @Order(2)
    final Stream<DynamicTest> normalCryptoCreateIsThrottledAtLowRate() {
        // Normal CryptoCreate has 2 ops/sec limit, so 10 parallel requests should get some BUSY
        return hapiTest(
                inParallel(IntStream.range(0, 10)
                        .mapToObj(i -> cryptoCreate("normalAccount" + i)
                                .balance(ONE_HUNDRED_HBARS)
                                .hasKnownStatusFrom(SUCCESS, BUSY)
                                .noLogging())
                        .toArray(HapiSpecOperation[]::new)));
    }

    @HapiTest
    @Order(3)
    final Stream<DynamicTest> highVolumeCryptoCreateUsesHigherThrottleLimit() {
        // High-volume CryptoCreate has 100 ops/sec limit, so 10 parallel requests should all succeed
        return hapiTest(
                inParallel(IntStream.range(0, 10)
                        .mapToObj(i -> cryptoCreate("highVolumeAccount" + i)
                                .balance(ONE_HUNDRED_HBARS)
                                .highVolume()
                                .hasKnownStatus(SUCCESS)
                                .noLogging())
                        .toArray(HapiSpecOperation[]::new)));
    }

    @HapiTest
    @Order(4)
    final Stream<DynamicTest> normalTokenCreateIsThrottledAtLowRate() {
        // Normal TokenCreate has 2 ops/sec limit, so 10 parallel requests should get some BUSY
        return hapiTest(
                inParallel(IntStream.range(0, 10)
                        .mapToObj(i -> tokenCreate("normalToken" + i)
                                .hasKnownStatusFrom(SUCCESS, BUSY)
                                .noLogging())
                        .toArray(HapiSpecOperation[]::new)));
    }

    @HapiTest
    @Order(5)
    final Stream<DynamicTest> highVolumeTokenCreateUsesHigherThrottleLimit() {
        // High-volume TokenCreate has 100 ops/sec limit, so 10 parallel requests should all succeed
        return hapiTest(
                inParallel(IntStream.range(0, 10)
                        .mapToObj(i -> tokenCreate("highVolumeToken" + i)
                                .highVolume()
                                .hasKnownStatus(SUCCESS)
                                .noLogging())
                        .toArray(HapiSpecOperation[]::new)));
    }

    @HapiTest
    @Order(6)
    final Stream<DynamicTest> restoreThrottles() {
        return hapiTest(withOpContext((spec, opLog) -> throttleOverrideOp.restoreContentsIfNeeded(spec)));
    }
}

