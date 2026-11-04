// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.precompile.validation;

import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sleepFor;

import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.junit.OrderedInIsolation;
import com.hedera.services.bdd.spec.dsl.annotations.Contract;
import com.hedera.services.bdd.spec.dsl.entities.SpecContract;
import java.math.BigInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@HapiTestLifecycle
@Tag(SMART_CONTRACT)
@OrderedInIsolation
public class ModExpGasRequirementTest {

    @Contract(contract = "ModExpCaller", creationGas = 2_000_000L)
    static SpecContract contract;

    @LeakyHapiTest
    Stream<DynamicTest> test() {
        return hapiTest(
                contract.call("callSmall", BigInteger.valueOf(10_000)),
                contract.call("callWithMaxHeaders", BigInteger.TEN),
                sleepFor(1000),
                contract.call("callSmall", BigInteger.valueOf(10_000)));
    }
}
