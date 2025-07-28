// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.precompile.schedule;

import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.suites.contract.Utils.FunctionType.FUNCTION;
import static com.hedera.services.bdd.suites.contract.Utils.getABIFor;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts;
import com.hedera.services.bdd.spec.dsl.annotations.Contract;
import com.hedera.services.bdd.spec.dsl.entities.SpecContract;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import java.math.BigInteger;
import java.util.stream.Stream;
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
@DisplayName("Schedule call")
@HapiTestLifecycle
public class HasScheduleCapacityTest {

    @Contract(contract = "HIP1215Contract", creationGas = 4_000_000L, isImmutable = true)
    static SpecContract contract;

    @BeforeAll
    public static void setup(TestLifecycle lifecycle) {
        lifecycle.doAdhoc(overriding("contracts.systemContract.scheduleService.hasScheduleCapacity.enabled", "true"));
    }

    @HapiTest
    @DisplayName("hasScheduleCapacity(uint256,uint256)")
    public Stream<DynamicTest> scheduleCallWithCapacityCheckAndDeleteTest() {
        return hapiTest(contract.call("hasScheduleCapacityExample", BigInteger.valueOf(50))
                .gas(100_000)
                .andAssert(txn -> txn.hasResults(
                        ContractFnResultAsserts.resultWith()
                                .resultThruAbi(
                                        getABIFor(FUNCTION, "hasScheduleCapacityExample", contract.name()),
                                        ContractFnResultAsserts.isLiteralResult(new Object[] {true})),
                        // for child record asserting, because hasScheduleCapacity is a view function
                        ContractFnResultAsserts.anyResult()))
                .andAssert(txn -> txn.hasKnownStatus(ResponseCodeEnum.SUCCESS)));
    }
}
