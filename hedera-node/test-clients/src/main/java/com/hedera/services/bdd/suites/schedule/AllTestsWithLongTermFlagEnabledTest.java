// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.schedule;

import static com.hedera.services.bdd.suites.utils.DynamicTestUtils.extractAllTestAnnotatedMethods;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;

// Running all test methods that are specified in ALL_TESTS constant with scheduling.longTermEnabled enabled
@HapiTestLifecycle
public class AllTestsWithLongTermFlagEnabledTest {

    private static final Supplier<?>[] ALL_TESTS = new Supplier<?>[] {
        FutureSchedulableOpsTest::new,
        ScheduleCreateTest::new,
        ScheduleDeleteTest::new,
        ScheduleExecutionTest::new,
        ScheduleRecordTest::new,
        ScheduleSignTest::new,
        StatefulScheduleExecutionTest::new
    };

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(Map.of(
                "scheduling.whitelist",
                "ContractCall,CryptoCreate,CryptoTransfer,FileDelete,FileUpdate,"
                        + "SystemDelete,ConsensusSubmitMessage,TokenBurn,TokenMint,CryptoApproveAllowance",
                "scheduling.longTermEnabled",
                "true"));
    }

    @HapiTest
    final Stream<DynamicTest> runAllTests() {
        var allDynamicTests = extractAllTestAnnotatedMethods(ALL_TESTS, List.of(), HapiTest.class);
        return allDynamicTests.stream().flatMap(s -> s);
    }
}
