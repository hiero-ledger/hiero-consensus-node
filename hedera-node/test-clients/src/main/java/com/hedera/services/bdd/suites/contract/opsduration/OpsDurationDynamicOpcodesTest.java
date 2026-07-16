// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.opsduration;

import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.namedHapiTest;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overridingAllOf;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.contract.opsduration.OpsDurationThrottleTest.enableDefaultOpsDurationThrottleNoRefill;
import static com.hedera.services.bdd.suites.contract.opsduration.OpsDurationThrottleTest.restoreDefaults;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONSENSUS_GAS_EXHAUSTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;

import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.dsl.annotations.Contract;
import com.hedera.services.bdd.spec.dsl.entities.SpecContract;
import com.hedera.services.bdd.spec.dsl.operations.transactions.CallContractOperation;
import com.hedera.services.bdd.spec.utilops.UtilVerbs;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(SMART_CONTRACT)
public class OpsDurationDynamicOpcodesTest {

    record TestInput(String name, BigInteger length, ResponseCodeEnum status, Map<String, String> configsChange) {}

    private static final TestInput[] OPS_DURATION_BENCHMARK_DYNAMIC_FUNCTIONS = {
        new TestInput("benchKeccak256", BigInteger.valueOf(1048576), SUCCESS, null),
        new TestInput("benchCalldatacopy", BigInteger.valueOf(1048576), SUCCESS, null),
        new TestInput("benchCodecopy", BigInteger.valueOf(1048576), SUCCESS, null),
        new TestInput("benchExtcodecopy", BigInteger.valueOf(1048576), SUCCESS, null),
        new TestInput("benchReturndatacopy", BigInteger.valueOf(524288), SUCCESS, null),
        new TestInput("benchLog0", BigInteger.valueOf(1048576), SUCCESS, null),
        new TestInput("benchLog1", BigInteger.valueOf(1048576), SUCCESS, null),
        new TestInput("benchLog2", BigInteger.valueOf(1048576), SUCCESS, null),
        new TestInput("benchLog3", BigInteger.valueOf(1048576), SUCCESS, null),
        new TestInput("benchLog4", BigInteger.valueOf(1048576), SUCCESS, null),
        new TestInput(
                "benchCreate", BigInteger.valueOf(1048576), SUCCESS, Map.of("contracts.maxInitcodeSize", "1048576")),
        new TestInput(
                "benchCreate2", BigInteger.valueOf(1048576), SUCCESS, Map.of("contracts.maxInitcodeSize", "1048576")),
        new TestInput("benchReturn", BigInteger.valueOf(1048576), SUCCESS, null),
        new TestInput("benchRevert", BigInteger.valueOf(1048576), CONTRACT_REVERT_EXECUTED, null)
    };

    @Contract(contract = "OpsDurationDynamicOpcodes", creationGas = 5_000_000)
    static SpecContract opsDurationDynamicOpcodesContract;

    @LeakyHapiTest
    final Stream<DynamicTest> dynamicOpcodesTest() {
        return Stream.of(OPS_DURATION_BENCHMARK_DYNAMIC_FUNCTIONS)
                .flatMap(test ->
                        Stream.of(compareGasTest(test.name(), test.length(), test.status(), test.configsChange())));
    }

    private static DynamicTest compareGasTest(
            final String name,
            final BigInteger length,
            final ResponseCodeEnum status,
            final Map<String, String> configsChange) {
        List<SpecOperation> ops = new ArrayList<>();
        ops.add(enableDefaultOpsDurationThrottleNoRefill());
        if (configsChange != null && !configsChange.isEmpty()) {
            ops.add(overridingAllOf(configsChange));
        }
        ops.add(call(name, length, status));
        ops.add(call(name, length, CONSENSUS_GAS_EXHAUSTED));
        ops.add(withOpContext((spec, _) -> {
            restoreDefaults(spec);
            if (configsChange != null && !configsChange.isEmpty()) {
                allRunFor(
                        spec,
                        configsChange.keySet().stream()
                                .map(UtilVerbs::restoreDefault)
                                .toList());
            }
        }));
        return namedHapiTest(name, ops.toArray(new SpecOperation[0]));
    }

    private static CallContractOperation call(
            final String name, final BigInteger length, final ResponseCodeEnum status) {
        return opsDurationDynamicOpcodesContract
                .call(name, length)
                .gas(15_000_000)
                .andAssert(e -> e.hasKnownStatus(status));
    }
}
