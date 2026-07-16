// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.opsduration;

import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.namedHapiTest;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.contract.opsduration.OpsDurationThrottleTest.enableDefaultOpsDurationThrottleNoRefill;
import static com.hedera.services.bdd.suites.contract.opsduration.OpsDurationThrottleTest.restoreDefaults;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONSENSUS_GAS_EXHAUSTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;

import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.dsl.annotations.Contract;
import com.hedera.services.bdd.spec.dsl.entities.SpecContract;
import com.hedera.services.bdd.spec.dsl.operations.transactions.CallContractOperation;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;

import java.math.BigInteger;
import java.util.Map;
import java.util.stream.Stream;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(SMART_CONTRACT)
@HapiTestLifecycle
public class OpsDurationDynamicOpcodesTest {

    record TestInput(String name, BigInteger length, ResponseCodeEnum status) {
    }

    private static final TestInput[] OPS_DURATION_BENCHMARK_DYNAMIC_FUNCTIONS = {
        new TestInput("benchKeccak256", BigInteger.valueOf(1048576), SUCCESS),
        new TestInput("benchCalldatacopy", BigInteger.valueOf(1048576), SUCCESS),
        new TestInput("benchCodecopy", BigInteger.valueOf(1048576), SUCCESS),
        new TestInput("benchExtcodecopy", BigInteger.valueOf(1048576), SUCCESS),
        new TestInput("benchReturndatacopy", BigInteger.valueOf(524288), SUCCESS),
        new TestInput("benchLog0", BigInteger.valueOf(1048576), SUCCESS),
        new TestInput("benchLog1", BigInteger.valueOf(1048576), SUCCESS),
        new TestInput("benchLog2", BigInteger.valueOf(1048576), SUCCESS),
        new TestInput("benchLog3", BigInteger.valueOf(1048576), SUCCESS),
        new TestInput("benchLog4", BigInteger.valueOf(1048576), SUCCESS),
        new TestInput("benchCreate", BigInteger.valueOf(1048576), SUCCESS),
            new TestInput("benchCreate2", BigInteger.valueOf(1048576), SUCCESS),
        new TestInput("benchReturn", BigInteger.valueOf(1048576), SUCCESS),
        new TestInput("benchRevert", BigInteger.valueOf(1048576), CONTRACT_REVERT_EXECUTED)
    };

    @Contract(contract = "OpsDurationDynamicOpcodes", creationGas = 5_000_000)
    static SpecContract contract;

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        // config override for benchCreate and benchCreate2 tests to allow bigger create init code
        testLifecycle.overrideInClass(Map.of(
                "contracts.maxInitcodeSize", "1048576"));
    }

    @LeakyHapiTest
    final Stream<DynamicTest> dynamicOpcodesTest() {
        return Stream.of(OPS_DURATION_BENCHMARK_DYNAMIC_FUNCTIONS)
                .flatMap(test -> Stream.of(compareGasTest(test.name(), test.length(), test.status())));
    }

    private static DynamicTest compareGasTest(
            final String name, final BigInteger length, final ResponseCodeEnum status) {
        return namedHapiTest(
                name,
                enableDefaultOpsDurationThrottleNoRefill(),
                // if checked opcodes consume static opsDuration, {length} is enough to not trigger
                // CONSENSUS_GAS_EXHAUSTED for all tested opcodes
                // but if opcodes consume dynamic opsDuration based on the {length} input size, all tested opcodes
                // should trigger CONSENSUS_GAS_EXHAUSTED
                call(name, length, status),
                call(name, length, CONSENSUS_GAS_EXHAUSTED),
                withOpContext((spec, _) -> restoreDefaults(spec)));
    }

    private static CallContractOperation call(
            final String name, final BigInteger length, final ResponseCodeEnum status) {
        return contract.call(name, length).gas(15_000_000).andAssert(e -> e.hasKnownStatus(status));
    }
}
