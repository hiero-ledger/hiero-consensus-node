// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.precompile.schedule;

import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.suites.contract.Utils.FunctionType.FUNCTION;
import static com.hedera.services.bdd.suites.contract.Utils.getABIFor;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts;
import com.hedera.services.bdd.spec.dsl.annotations.Contract;
import com.hedera.services.bdd.spec.dsl.entities.SpecContract;
import com.hedera.services.bdd.spec.dsl.operations.transactions.CallContractOperation;
import com.hedera.services.bdd.spec.utilops.UtilVerbs;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigInteger;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * Tests success scenarios of the HRC-1215 functions when enabled
 * {@code contracts.systemContract.scheduleService.scheduleCall.enabled} feature flag. This tests checks just a happy
 * path because more detailed tests with be added to
 * <a href="https://github.com/hashgraph/hedera-evm-testing">hedera-evm-testing</a> repo
 */
@Tag(SMART_CONTRACT)
@HapiTestLifecycle
public class HasScheduleCapacityTest {

    private static final AtomicInteger EXPIRY_SHIFT = new AtomicInteger(120);
    private static final BigInteger VALUE_MORE_THAN_LONG =
            BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.TEN);
    private static final String FUNCTION_NAME = "hasScheduleCapacityProxy";

    @Contract(contract = "HIP1215Contract", creationGas = 4_000_000L, isImmutable = true)
    static SpecContract contract;

    @BeforeAll
    public static void setup(final TestLifecycle lifecycle) {
        lifecycle.doAdhoc(
                UtilVerbs.overriding("contracts.systemContract.scheduleService.scheduleCall.enabled", "true"));
    }

    @AfterAll
    public static void shutdown(final TestLifecycle lifecycle) {
        lifecycle.doAdhoc(UtilVerbs.restoreDefault("contracts.systemContract.scheduleService.scheduleCall.enabled"));
    }

    private CallContractOperation hasScheduleCapacity(
            final boolean result, @NonNull final String function, @NonNull final Object... parameters) {
        return contract.call(function, parameters)
                .gas(100_000)
                .andAssert(txn -> txn.hasResults(ContractFnResultAsserts.resultWith()
                        .resultThruAbi(
                                getABIFor(FUNCTION, function, contract.name()),
                                ContractFnResultAsserts.isLiteralResult(new Object[] {result}))))
                .andAssert(txn -> txn.hasKnownStatus(ResponseCodeEnum.SUCCESS));
    }

    @HapiTest
    @DisplayName("call hasScheduleCapacity(uint256,uint256) success return true")
    public Stream<DynamicTest> hasScheduleCapacityTest() {
        return hapiTest(hasScheduleCapacity(
                true, "hasScheduleCapacityExample", BigInteger.valueOf(EXPIRY_SHIFT.getAndIncrement())));
    }

    @HapiTest
    @DisplayName("call hasScheduleCapacity(uint256,uint256) success return false by 0 expiry")
    public Stream<DynamicTest> hasScheduleCapacity0ExpiryTest() {
        return hapiTest(hasScheduleCapacity(false, FUNCTION_NAME, BigInteger.ZERO, BigInteger.valueOf(2_000_000)));
    }

    @HapiTest
    @DisplayName("call hasScheduleCapacity(uint256,uint256) success return false by huge expiry")
    public Stream<DynamicTest> hasScheduleCapacityHugeExpiryTest() {
        return hapiTest(hasScheduleCapacity(false, FUNCTION_NAME, VALUE_MORE_THAN_LONG, BigInteger.valueOf(2_000_000)));
    }

    @HapiTest
    @DisplayName("call hasScheduleCapacity(uint256,uint256) success return false by huge gasLimit")
    public Stream<DynamicTest> hasScheduleCapacityHugeGasLimitTest() {
        final BigInteger expirySecond =
                BigInteger.valueOf(System.currentTimeMillis() / 1000 + EXPIRY_SHIFT.getAndIncrement());
        return hapiTest(hasScheduleCapacity(false, FUNCTION_NAME, expirySecond, VALUE_MORE_THAN_LONG));
    }
}
