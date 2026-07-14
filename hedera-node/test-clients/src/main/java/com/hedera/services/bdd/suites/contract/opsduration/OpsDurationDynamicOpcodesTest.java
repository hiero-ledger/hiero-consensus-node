package com.hedera.services.bdd.suites.contract.opsduration;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.spec.dsl.annotations.Contract;
import com.hedera.services.bdd.spec.dsl.entities.SpecContract;
import com.hedera.services.bdd.spec.dsl.operations.transactions.CallContractOperation;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

import java.math.BigInteger;
import java.util.stream.Stream;

import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.namedHapiTest;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.contract.opsduration.OpsDurationThrottleTest.enableDefaultOpsDurationThrottleNoRefill;
import static com.hedera.services.bdd.suites.contract.opsduration.OpsDurationThrottleTest.restoreDefaults;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;

@Tag(SMART_CONTRACT)
public class OpsDurationDynamicOpcodesTest {

    private static final String[] OPS_DURATION_BENCHMARK_DYNAMIC_FUNCTIONS = {
            "benchKeccak256",
//            "benchCalldatacopy",
//            "benchCodecopy",
//            "benchExtcodecopy",
//            "benchReturndatacopy",
//            "benchLog0",
//            "benchLog1",
//            "benchLog2",
//            "benchLog3",
//            "benchLog4",
//            "benchCreate",
//            "benchCreate2",
//            "benchReturn",
//            "benchRevert"
    };

    @Contract(contract = "OpsDurationDynamicOpcodes", creationGas = 5_000_000)
    static SpecContract contract;

    @HapiTest
    final Stream<DynamicTest> dynamicOpcodesTest() {
        return Stream.of(OPS_DURATION_BENCHMARK_DYNAMIC_FUNCTIONS)
                .flatMap(name -> Stream.of(compareGasTest(name)));
    }

    private static DynamicTest compareGasTest(String name) {
        return namedHapiTest(name,
                enableDefaultOpsDurationThrottleNoRefill(),
                call(name, BigInteger.valueOf(32)),
                call(name, BigInteger.valueOf(128)),
                call(name, BigInteger.valueOf(1024)),
                call(name, BigInteger.valueOf(1048576)),
                withOpContext((spec, _) -> restoreDefaults(spec))
        );
    }

    private static CallContractOperation call(String name, BigInteger length) {
        return contract.call(name, length)
                .gas(10_000_000)
                .andAssert(e -> {
            if ("benchRevert".equals(name)) {
                e.hasKnownStatus(CONTRACT_REVERT_EXECUTED);
            }
        });
    }

}
