// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.fees;

import static com.hedera.services.bdd.junit.TestTags.SIMPLE_FEES;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getFileContents;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getFileInfo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileAppend;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileUpdate;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyListNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsd;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsdForQueries;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.THREE_MONTHS_IN_SECONDS;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedFeeFromBytesFor;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.FILE_APPEND_BASE_FEE;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.FILE_CREATE_BASE_FEE;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.FILE_DELETE_BASE_FEE;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.FILE_UPDATE_BASE_FEE;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.SIGNATURE_FEE_AFTER_MULTIPLIER;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.STATE_BYTES_FEE_USD;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.spec.keys.KeyShape;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(SIMPLE_FEES)
public class FileServiceSimpleFeesTest {
    private static final String CIVILIAN = "civilian";
    private static final String KEY = "key";
    private static final double SINGLE_KEY_FEE = 0.01;
    private static final double BASE_FEE_FILE_GET_CONTENT = 0.0001;
    private static final double BASE_FEE_FILE_GET_FILE = 0.0001;

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
                validateChargedUsd("fileCreateBasic", FILE_CREATE_BASE_FEE));
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
                                + expectedFeeFromBytesFor(spec, opLog, "fileCreateExtraNodeBytes"))));
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
                validateChargedUsd("fileCreateExtraKeys", FILE_CREATE_BASE_FEE + feeFromKeys + feeFromSignatures));
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
                validateChargedUsd("fileUpdateBasic", FILE_UPDATE_BASE_FEE));
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
                validateChargedUsd("fileDeleteBasic", FILE_DELETE_BASE_FEE));
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
                validateChargedUsd(baseAppend, FILE_APPEND_BASE_FEE));
    }

    @HapiTest
    @DisplayName("USD base fee as expected for file get content transaction")
    final Stream<DynamicTest> fileGetContentBaseUSDFee() {
        return hapiTest(
                cryptoCreate(CIVILIAN).balance(5 * ONE_HUNDRED_HBARS),
                fileCreate("ntb").key(CIVILIAN).contents("Nothing much!"),
                getFileContents("ntb").payingWith(CIVILIAN).signedBy(CIVILIAN).via("getFileContentsBasic"),
                validateChargedUsdForQueries("getFileContentsBasic", BASE_FEE_FILE_GET_CONTENT, 1));
    }

    @HapiTest
    final Stream<DynamicTest> fileGetContentAboveIncludedBytes() {
        return hapiTest(
                cryptoCreate(CIVILIAN).balance(5 * ONE_HUNDRED_HBARS),
                fileCreate("ntb").key(CIVILIAN).contents(bytesWithLength(1500)),
                getFileContents("ntb").payingWith(CIVILIAN).signedBy(CIVILIAN).via("getFileContentsBasic"),
                validateChargedUsdForQueries(
                        "getFileContentsBasic", BASE_FEE_FILE_GET_CONTENT + 500 * STATE_BYTES_FEE_USD, 1));
    }

    @HapiTest
    @DisplayName("USD base fee as expected for file get info transaction")
    final Stream<DynamicTest> fileGetInfoBaseUSDFee() {
        return hapiTest(
                cryptoCreate(CIVILIAN).balance(5 * ONE_HUNDRED_HBARS),
                fileCreate("ntb").key(CIVILIAN).contents("Nothing much!"),
                getFileInfo("ntb").payingWith(CIVILIAN).signedBy(CIVILIAN).via("getFileInfoBasic"),
                validateChargedUsdForQueries("getFileInfoBasic", BASE_FEE_FILE_GET_FILE, 1));
    }

    private static byte[] bytesWithLength(final int length) {
        final var result = new byte[length];
        Arrays.fill(result, (byte) 'a');
        return result;
    }
}
