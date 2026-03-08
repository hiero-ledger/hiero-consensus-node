// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.regression.system;

import static com.hedera.services.bdd.junit.TestTags.WRAPS;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_BILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.OrderedInIsolation;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.dsl.annotations.Account;
import com.hedera.services.bdd.spec.dsl.entities.SpecAccount;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 */
@Tag(WRAPS)
@HapiTestLifecycle
@OrderedInIsolation
public class WrapsHandoffsTest implements LifecycleTest {
    @Account(tinybarBalance = ONE_BILLION_HBARS, stakedNodeId = 0)
    static SpecAccount NODE0_STAKER;

    @Account(tinybarBalance = ONE_BILLION_HBARS / 100, stakedNodeId = 1)
    static SpecAccount NODE1_STAKER;

    @Account(tinybarBalance = ONE_BILLION_HBARS / 100, stakedNodeId = 2)
    static SpecAccount NODE2_STAKER;

    @Account(tinybarBalance = ONE_MILLION_HBARS / 100, stakedNodeId = 3)
    static SpecAccount NODE3_STAKER;

    @BeforeAll
    public static void setup(TestLifecycle lifecycle) {
        lifecycle.doAdhoc(
                NODE0_STAKER.getInfo(), NODE1_STAKER.getInfo(), NODE2_STAKER.getInfo(), NODE3_STAKER.getInfo());
    }

    @HapiTest
    final Stream<DynamicTest> addressBookAndNodeDetailsPopulated() {
        return hapiTest();
    }
}
