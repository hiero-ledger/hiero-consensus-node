// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip1259;

import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.integration.RepeatableHip1064Tests.validateRecordFees;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.utilops.UtilVerbs;
import edu.umd.cs.findbugs.annotations.NonNull;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@HapiTestLifecycle
public class Hip1259DisabledTests {
    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(Map.of("nodes.feeCollectionAccountEnabled", "false"));
    }

    @HapiTest
    @Tag(MATS)
    final Stream<DynamicTest> validateFeeDistributed() {
        return hapiTest(
                cryptoCreate("payer").balance(ONE_MILLION_HBARS),
                cryptoCreate("receiver")
                        .balance(ONE_HUNDRED_HBARS)
                        .payingWith("payer")
                        .via("creation"),
                getTxnRecord("creation").logged(),
                validateRecordContains("creation", List.of(3L, 801L)),
                validateRecordNotContains("creation", List.of(802L)));
    }

    public static SpecOperation validateRecordContains(final String record, List<Long> expectedFeeAccounts) {
        return UtilVerbs.withOpContext((spec, opLog) -> {
            var fileCreate = getTxnRecord(record);
            allRunFor(spec, fileCreate);
            var response = fileCreate.getResponseRecord();
            assertEquals(
                    1,
                    response.getTransferList().getAccountAmountsList().stream()
                            .filter(aa -> aa.getAmount() < 0)
                            .count());
            assertTrue(response.getTransferList().getAccountAmountsList().stream()
                    .filter(aa -> aa.getAmount() > 0)
                    .map(aa -> aa.getAccountID().getAccountNum())
                    .sorted()
                    .toList().containsAll(expectedFeeAccounts));
        });
    }

    public static SpecOperation validateRecordNotContains(final String record, List<Long> expectedFeeAccounts) {
        return UtilVerbs.withOpContext((spec, opLog) -> {
            var fileCreate = getTxnRecord(record);
            allRunFor(spec, fileCreate);
            var response = fileCreate.getResponseRecord();
            for (final var expectedFeeAccount : expectedFeeAccounts) {
                assertFalse(response.getTransferList().getAccountAmountsList().stream()
                        .anyMatch(aa -> aa.getAccountID().getAccountNum() == expectedFeeAccount));
            }
        });
    }
}
