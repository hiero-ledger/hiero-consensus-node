// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip1259;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.integration.RepeatableHip1064Tests.validateRecordFees;

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
                cryptoCreate("payer"),
                cryptoCreate("receiver").fee(ONE_HUNDRED_HBARS).payingWith("payer").via("creation")
//                validateRecordFees("creation", List.of(3L, 98L, 800L, 801L))
        );
    }
}
