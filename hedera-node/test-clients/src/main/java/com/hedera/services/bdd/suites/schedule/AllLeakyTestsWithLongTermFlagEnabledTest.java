// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.schedule;

import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.suites.utils.DynamicTestUtils.extractAllTestAnnotatedMethods;

import com.hedera.services.bdd.junit.ContextRequirement;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

// Running all leaky test methods that are specified in ALL_TESTS constant with scheduling.longTermEnabled enabled
@HapiTestLifecycle
@Tag(MATS)
public class AllLeakyTestsWithLongTermFlagEnabledTest {

    private static final Supplier<?>[] ALL_TESTS = new Supplier<?>[] {
        FutureSchedulableOpsTest::new,
        ScheduleCreateTest::new,
        ScheduleDeleteTest::new,
        ScheduleExecutionTest::new,
        ScheduleRecordTest::new,
        ScheduleSignTest::new,
        StatefulScheduleExecutionTest::new
    };

    private static final List<String> IGNORED_TESTS = List.of(
            // The test is setting ledger.schedule.txExpiryTimeSecs to 1 second in order
            // to expire the transaction. Since this test class is turning on the longTermEnabled
            // we never use the txExpiryTimeSecs, thus the test is failing which is expected.
            "signFailsDueToDeletedExpiration");

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(Map.of(
                "scheduling.whitelist",
                "ContractCall,CryptoCreate,CryptoTransfer,FileDelete,FileUpdate,"
                        + "SystemDelete,ConsensusSubmitMessage,TokenBurn,TokenMint,CryptoApproveAllowance",
                "scheduling.longTermEnabled",
                "true"));
    }

    @LeakyHapiTest(
            overrides = {
                "tokens.nfts.maxBatchSizeMint",
                "ledger.schedule.txExpiryTimeSecs",
                "ledger.transfers.maxLen",
                "ledger.tokenTransfers.maxLen",
                "scheduling.whitelist"
            },
            requirement = ContextRequirement.FEE_SCHEDULE_OVERRIDES)
    final Stream<DynamicTest> runAllTests() {
        var allDynamicTests = extractAllTestAnnotatedMethods(ALL_TESTS, IGNORED_TESTS, LeakyHapiTest.class);
        return allDynamicTests.stream().flatMap(s -> s);
    }
}
