// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.fees;

import static com.hedera.services.bdd.junit.TestTags.SIMPLE_FEES;
import static com.hedera.services.bdd.spec.HapiSpec.customizedHapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getVersionInfo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsdForQueries;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_BILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;

import com.hedera.services.bdd.junit.HapiTest;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * Tests for simple fee calculations in the Network Admin service
 * (GetVersionInfo, TransactionGetRecord, TransactionGetReceipt).
 */
@Tag(SIMPLE_FEES)
public class NetworkServiceSimpleFeesTest {
    private static final String ALICE = "alice";
    private static final String BOB = "bob";

    // Base fees from the simple fee schedule
    private static final double BASE_FEE_GET_VERSION_INFO = 0.0001;
    private static final double BASE_FEE_TRANSACTION_GET_RECORD = 0.0001;

    @HapiTest
    @DisplayName("USD base fee as expected for GetVersionInfo query")
    final Stream<DynamicTest> getVersionInfoBaseUSDFee() {
        return customizedHapiTest(
                Map.of("memo.useSpecName", "false"),
                cryptoCreate(BOB).balance(ONE_HUNDRED_HBARS),
                getVersionInfo().signedBy(BOB).payingWith(BOB).via("versionInfo"),
                validateChargedUsdForQueries("versionInfo", BASE_FEE_GET_VERSION_INFO, 1.0));
    }

    @HapiTest
    @DisplayName("USD base fee as expected for TransactionGetRecord query")
    final Stream<DynamicTest> transactionGetRecordBaseUSDFee() {
        final var createTxn = "createTxn";
        final var recordQuery = "recordQuery";

        return customizedHapiTest(
                Map.of("memo.useSpecName", "false"),
                cryptoCreate(ALICE).balance(ONE_BILLION_HBARS),
                cryptoCreate(BOB)
                        .balance(ONE_HUNDRED_HBARS)
                        .signedBy(ALICE)
                        .payingWith(ALICE)
                        .via(createTxn),
                getTxnRecord(createTxn).signedBy(BOB).payingWith(BOB).via(recordQuery),
                validateChargedUsdForQueries(recordQuery, BASE_FEE_TRANSACTION_GET_RECORD, 1.0));
    }
}
