// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.fees;

import static com.hedera.services.bdd.junit.TestTags.SIMPLE_FEES;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.changeFromSnapshot;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getFileContents;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getFileInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileAppend;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileUpdate;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.balanceSnapshot;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyListNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsd;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsdForQueries;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateNodePaymentAmountForQuery;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateNonZeroNodePaymentForQuery;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.THREE_MONTHS_IN_SECONDS;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedFeeFromBytesFor;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.FILE_APPEND_BASE_FEE;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.FILE_CREATE_BASE_FEE;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.FILE_DELETE_BASE_FEE;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.FILE_GET_CONTENTS_INCLUDED_PROCESSING_BYTES;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.FILE_UPDATE_BASE_FEE;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.PROCESSING_BYTES_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.SIGNATURE_FEE_AFTER_MULTIPLIER;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.STATE_BYTES_FEE_USD;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_FILE_ID;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.spec.keys.KeyShape;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(SIMPLE_FEES)
public class FileServiceSimpleFeesSmokeTest {
    private static final String CIVILIAN = "civilian";
    private static final String KEY = "key";
    private static final double SINGLE_KEY_FEE = 0.01;
    private static final double BASE_FEE_FILE_GET_CONTENT = 0.0001;
    private static final double BASE_FEE_FILE_GET_FILE = 0.0001;
    private static final double TRANSACTION_ALLOWED_PERCENT_DIFF = 0.1;
    private static final double QUERY_ALLOWED_PERCENT_DIFF = 0.1;
    private static final long EXPECTED_NODE_PAYMENT_TINYCENTS = 84L;
    private static final long INVALID_QUERY_NODE_PAYMENT = 1_234L;
    private static final int RECORD_RETRY_LIMIT = 3_000;

    @HapiTest
    @DisplayName("USD base fee as expected for file create transaction")
    final Stream<DynamicTest> fileCreateBaseUSDFee() {
        var contents = "0".repeat(800).getBytes();

        return hapiTest(
                newKeyNamed(KEY).shape(KeyShape.SIMPLE),
                cryptoCreate(CIVILIAN).key(KEY).balance(ONE_HUNDRED_HBARS),
                newKeyListNamed("WACL", List.of(CIVILIAN)),
                fileCreate("test")
                        .key("WACL")
                        .contents(contents)
                        .payingWith(CIVILIAN)
                        .fee(ONE_HUNDRED_HBARS)
                        .signedBy(CIVILIAN)
                        .via("fileCreateBasic"),
                withOpContext((spec, opLog) -> validateChargedUsd(
                        "fileCreateBasic",
                        FILE_CREATE_BASE_FEE + expectedFeeFromBytesFor(spec, opLog, "fileCreateBasic"),
                        TRANSACTION_ALLOWED_PERCENT_DIFF)));
    }

    @HapiTest
    @DisplayName("USD fee as expected for file create transaction with extra bytes")
    final Stream<DynamicTest> fileCreateExtraBytes() {
        // Node fee BYTES includedCount is 1024
        // We need a transaction that exceeds 1024 bytes
        // File contents of ~4000 bytes should create a transaction > 1024 bytes
        final var contentBytes = 4000;

        // Service fee extra for content bytes (1000 extra bytes above included 1000)
        // Uses STATE_BYTES fee rate for file content
        final var serviceFeeFromBytes = (contentBytes - 1000) * STATE_BYTES_FEE_USD;

        return hapiTest(
                newKeyNamed(KEY).shape(KeyShape.SIMPLE),
                cryptoCreate(CIVILIAN).key(KEY).balance(ONE_HUNDRED_HBARS),
                newKeyListNamed("WACL", List.of(CIVILIAN)),
                fileCreate("test")
                        .key("WACL")
                        .contents(bytesWithLength(contentBytes))
                        .payingWith(CIVILIAN)
                        .fee(ONE_HUNDRED_HBARS)
                        .via("fileCreateExtraNodeBytes"),
                withOpContext((spec, opLog) -> validateChargedUsd(
                        "fileCreateExtraNodeBytes",
                        FILE_CREATE_BASE_FEE
                                + serviceFeeFromBytes
                                + expectedFeeFromBytesFor(spec, opLog, "fileCreateExtraNodeBytes"),
                        TRANSACTION_ALLOWED_PERCENT_DIFF)));
    }

    @HapiTest
    @DisplayName("USD fee as expected for file create transaction with extra keys")
    final Stream<DynamicTest> fileCreateExtraKeys() {
        final var contents = "0".repeat(50).getBytes();
        final var extraKeys = 4;
        final var feeFromKeys = extraKeys * SINGLE_KEY_FEE;
        final var extraSignatures = 5;
        final var feeFromSignatures = extraSignatures * SIGNATURE_FEE_AFTER_MULTIPLIER;

        return hapiTest(
                newKeyNamed("key1").shape(KeyShape.SIMPLE),
                newKeyNamed("key2").shape(KeyShape.SIMPLE),
                newKeyNamed("key3").shape(KeyShape.SIMPLE),
                newKeyNamed("key4").shape(KeyShape.SIMPLE),
                newKeyNamed("key5").shape(KeyShape.SIMPLE),
                newKeyListNamed("keyList", List.of("key1", "key2", "key3", "key4", "key5")),
                cryptoCreate(CIVILIAN),
                fileCreate("test")
                        .key("keyList")
                        .contents(contents)
                        .payingWith(CIVILIAN)
                        .via("fileCreateExtraKeys"),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        validateChargedUsd(
                                "fileCreateExtraKeys",
                                FILE_CREATE_BASE_FEE
                                        + feeFromKeys
                                        + feeFromSignatures
                                        + expectedFeeFromBytesFor(spec, opLog, "fileCreateExtraKeys"),
                                TRANSACTION_ALLOWED_PERCENT_DIFF))));
    }

    @HapiTest
    @DisplayName("USD base fee as expected for file update transaction")
    final Stream<DynamicTest> fileUpdateBaseUSDFee() {
        var contents = "0".repeat(800).getBytes();

        return hapiTest(
                newKeyNamed("key").shape(KeyShape.SIMPLE),
                cryptoCreate(CIVILIAN).key("key").balance(ONE_HUNDRED_HBARS),
                newKeyListNamed("key", List.of(CIVILIAN)),
                fileCreate("test").key("key").contents("ABC"),
                fileUpdate("test")
                        .contents(contents)
                        .payingWith(CIVILIAN)
                        .signedBy(CIVILIAN)
                        .fee(ONE_HUNDRED_HBARS)
                        .via("fileUpdateBasic"),
                validateChargedUsd("fileUpdateBasic", FILE_UPDATE_BASE_FEE, TRANSACTION_ALLOWED_PERCENT_DIFF));
    }

    @HapiTest
    @DisplayName("USD base fee as expected for file delete transaction")
    final Stream<DynamicTest> fileDeleteBaseUSDFee() {
        return hapiTest(
                newKeyNamed("key").shape(KeyShape.SIMPLE),
                cryptoCreate(CIVILIAN).key("key").balance(ONE_HUNDRED_HBARS),
                newKeyListNamed("WACL", List.of(CIVILIAN)),
                fileCreate("test").key("WACL").contents("ABC"),
                fileDelete("test")
                        .blankMemo()
                        .payingWith(CIVILIAN)
                        .signedBy(CIVILIAN)
                        .via("fileDeleteBasic"),
                validateChargedUsd("fileDeleteBasic", FILE_DELETE_BASE_FEE, TRANSACTION_ALLOWED_PERCENT_DIFF));
    }

    @HapiTest
    @DisplayName("USD base fee as expected for file append transaction")
    final Stream<DynamicTest> fileAppendBaseUSDFee() {
        final var civilian = "NonExemptPayer";

        final var baseAppend = "baseAppend";
        final var targetFile = "targetFile";
        final var magicKey = "magicKey";
        final var magicWacl = "magicWacl";

        return hapiTest(
                newKeyNamed(magicKey),
                newKeyListNamed(magicWacl, List.of(magicKey)),
                cryptoCreate(civilian).balance(ONE_HUNDRED_HBARS).key(magicKey),
                fileCreate(targetFile)
                        .key(magicWacl)
                        .lifetime(THREE_MONTHS_IN_SECONDS)
                        .contents("Nothing much!"),
                fileAppend(targetFile)
                        .fee(ONE_HBAR)
                        .signedBy(civilian)
                        .blankMemo()
                        .content("A".repeat(800))
                        .payingWith(civilian)
                        .via(baseAppend),
                validateChargedUsd(baseAppend, FILE_APPEND_BASE_FEE, TRANSACTION_ALLOWED_PERCENT_DIFF));
    }

    @HapiTest
    @DisplayName("USD base fee as expected for file get content transaction")
    final Stream<DynamicTest> fileGetContentBaseUSDFee() {
        return hapiTest(
                cryptoCreate(CIVILIAN).balance(5 * ONE_HUNDRED_HBARS),
                fileCreate("ntb").key(CIVILIAN).contents("Nothing much!"),
                getFileContents("ntb").payingWith(CIVILIAN).signedBy(CIVILIAN).via("getFileContentsBasic"),
                validateChargedUsdForQueries(
                        "getFileContentsBasic", BASE_FEE_FILE_GET_CONTENT, QUERY_ALLOWED_PERCENT_DIFF),
                validateNodePaymentAmountForQuery("getFileContentsBasic", EXPECTED_NODE_PAYMENT_TINYCENTS));
    }

    @HapiTest
    final Stream<DynamicTest> fileGetContentAboveIncludedBytes() {
        return hapiTest(
                cryptoCreate(CIVILIAN).balance(5 * ONE_HUNDRED_HBARS),
                fileCreate("ntb").key(CIVILIAN).contents(bytesWithLength(1500)),
                getFileContents("ntb").payingWith(CIVILIAN).signedBy(CIVILIAN).via("getFileContentsBasic"),
                validateChargedUsdForQueries(
                        "getFileContentsBasic",
                        BASE_FEE_FILE_GET_CONTENT
                                + (1500 - FILE_GET_CONTENTS_INCLUDED_PROCESSING_BYTES) * PROCESSING_BYTES_FEE_USD,
                        QUERY_ALLOWED_PERCENT_DIFF),
                validateNonZeroNodePaymentForQuery("getFileContentsBasic"));
    }

    @HapiTest
    @DisplayName("USD base fee as expected for file get info transaction")
    final Stream<DynamicTest> fileGetInfoBaseUSDFee() {
        return hapiTest(
                cryptoCreate(CIVILIAN).balance(5 * ONE_HUNDRED_HBARS),
                fileCreate("ntb").key(CIVILIAN).contents("Nothing much!"),
                getFileInfo("ntb").payingWith(CIVILIAN).signedBy(CIVILIAN).via("getFileInfoBasic"),
                validateChargedUsdForQueries("getFileInfoBasic", BASE_FEE_FILE_GET_FILE, QUERY_ALLOWED_PERCENT_DIFF),
                validateNodePaymentAmountForQuery("getFileInfoBasic", EXPECTED_NODE_PAYMENT_TINYCENTS));
    }

    @HapiTest
    @DisplayName("file get info - invalid file still charges the payer")
    final Stream<DynamicTest> fileGetInfoInvalidFileFails() {
        final AtomicLong paymentFee = new AtomicLong();

        return hapiTest(
                cryptoCreate(CIVILIAN).balance(ONE_HUNDRED_HBARS),
                balanceSnapshot("preGetInfo", CIVILIAN),
                getFileInfo("0.0.99999999")
                        .payingWith(CIVILIAN)
                        .nodePayment(INVALID_QUERY_NODE_PAYMENT)
                        .hasAnswerOnlyPrecheck(INVALID_FILE_ID)
                        .via("invalidGetInfo"),
                // Waits for the query payment to reach consensus, so the balance below is settled
                getTxnRecord("invalidGetInfo")
                        .setRetryLimit(RECORD_RETRY_LIMIT)
                        .exposingTo(record -> paymentFee.set(record.getTransactionFee())),
                sourcing(() -> getAccountBalance(CIVILIAN)
                        .hasTinyBars(
                                changeFromSnapshot("preGetInfo", -(INVALID_QUERY_NODE_PAYMENT + paymentFee.get())))));
    }

    @HapiTest
    @DisplayName("file get contents - invalid file still charges the payer")
    final Stream<DynamicTest> fileGetContentsInvalidFileFails() {
        final AtomicLong paymentFee = new AtomicLong();

        return hapiTest(
                cryptoCreate(CIVILIAN).balance(ONE_HUNDRED_HBARS),
                balanceSnapshot("preGetContents", CIVILIAN),
                getFileContents("0.0.99999999")
                        .payingWith(CIVILIAN)
                        .nodePayment(INVALID_QUERY_NODE_PAYMENT)
                        .hasAnswerOnlyPrecheck(INVALID_FILE_ID)
                        .via("invalidGetContents"),
                getTxnRecord("invalidGetContents")
                        .setRetryLimit(RECORD_RETRY_LIMIT)
                        .exposingTo(record -> paymentFee.set(record.getTransactionFee())),
                sourcing(() -> getAccountBalance(CIVILIAN)
                        .hasTinyBars(changeFromSnapshot(
                                "preGetContents", -(INVALID_QUERY_NODE_PAYMENT + paymentFee.get())))));
    }

    private static byte[] bytesWithLength(final int length) {
        final var result = new byte[length];
        Arrays.fill(result, (byte) 'a');
        return result;
    }
}
