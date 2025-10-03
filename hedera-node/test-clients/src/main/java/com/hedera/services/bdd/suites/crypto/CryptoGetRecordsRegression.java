// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.crypto;

import static com.hedera.services.bdd.junit.TestTags.CRYPTO;
import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.assertions.TransferListAsserts.including;
import static com.hedera.services.bdd.spec.keys.KeyShape.SIMPLE;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountRecords;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_DELETED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_PAYER_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_TX_FEE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TRANSACTION_BODY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.spec.assertions.AssertUtils;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(CRYPTO)
public class CryptoGetRecordsRegression {
    private static final String LOW_THRESH_PAYER = "lowThreshPayer";
    private static final String ACCOUNT_TO_BE_DELETED = "toBeDeleted";
    private static final String ACCOUNT_1 = "account1";
    private static final String PAYER = "payer";

    @HapiTest
    @Tag(MATS)
    final Stream<DynamicTest> succeedsNormally() {
        String memo = "Dim galleries, dusky corridors got past...";

        return hapiTest(
                cryptoCreate("misc"),
                cryptoCreate(LOW_THRESH_PAYER).sendThreshold(1L),
                cryptoTransfer(tinyBarsFromTo(GENESIS, "misc", 1))
                        .payingWith(LOW_THRESH_PAYER)
                        .memo(memo)
                        .via("txn"),
                getAccountRecords(LOW_THRESH_PAYER)
                        .has(AssertUtils.inOrder(recordWith()
                                .txnId("txn")
                                .memo(memo)
                                .transfers(including(tinyBarsFromTo(GENESIS, "misc", 1L)))
                                .status(SUCCESS)
                                .payer(LOW_THRESH_PAYER))));
    }

    @HapiTest
    final Stream<DynamicTest> failsForMissingAccount() {
        return hapiTest(
                getAccountRecords("5.5.3").hasCostAnswerPrecheck(INVALID_ACCOUNT_ID),
                getAccountRecords("5.5.3").nodePayment(123L).hasAnswerOnlyPrecheck(INVALID_ACCOUNT_ID));
    }

    @HapiTest
    final Stream<DynamicTest> failsForMalformedPayment() {
        return hapiTest(
                newKeyNamed("wrong").shape(SIMPLE),
                getAccountRecords(GENESIS).signedBy("wrong").hasAnswerOnlyPrecheck(INVALID_SIGNATURE));
    }

    @HapiTest
    final Stream<DynamicTest> failsForUnfundablePayment() {
        long everything = 1_234L;
        return hapiTest(
                cryptoCreate("brokePayer").balance(everything),
                getAccountRecords(GENESIS)
                        .payingWith("brokePayer")
                        .nodePayment(everything)
                        .hasAnswerOnlyPrecheck(INSUFFICIENT_PAYER_BALANCE));
    }

    @HapiTest
    final Stream<DynamicTest> failsForInsufficientPayment() {
        return hapiTest(
                cryptoCreate(PAYER),
                getAccountRecords(GENESIS)
                        .payingWith(PAYER)
                        .nodePayment(1L)
                        .hasAnswerOnlyPrecheck(INSUFFICIENT_TX_FEE));
    }

    @HapiTest
    final Stream<DynamicTest> failsForInvalidTrxBody() {
        return hapiTest(getAccountRecords(GENESIS)
                .useEmptyTxnAsAnswerPayment()
                .hasAnswerOnlyPrecheck(INVALID_TRANSACTION_BODY));
    }

    @HapiTest
    final Stream<DynamicTest> failsForDeletedAccount() {
        return hapiTest(
                cryptoCreate(ACCOUNT_TO_BE_DELETED),
                cryptoDelete(ACCOUNT_TO_BE_DELETED).transfer(GENESIS),
                getAccountRecords(ACCOUNT_TO_BE_DELETED).hasCostAnswerPrecheck(ACCOUNT_DELETED),
                getAccountRecords(ACCOUNT_TO_BE_DELETED).nodePayment(123L).hasAnswerOnlyPrecheck(ACCOUNT_DELETED));
    }

    @HapiTest
    @Tag(MATS)
    final Stream<DynamicTest> getAccountRecords_testForDuplicates() {
        return hapiTest(
                cryptoCreate(ACCOUNT_1).balance(5000000000000L).sendThreshold(1L),
                cryptoCreate("account2").balance(5000000000000L).sendThreshold(1L),
                cryptoTransfer(tinyBarsFromTo(ACCOUNT_1, "account2", 10L))
                        .payingWith(ACCOUNT_1)
                        .via("thresholdTxn"),
                getAccountRecords(ACCOUNT_1).logged());
    }
}
