// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.fees;

import static com.hedera.services.bdd.spec.HapiSpec.customizedHapiTest;
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
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sleepFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsd;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.THREE_MONTHS_IN_SECONDS;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.spec.keys.KeyShape;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;

public class FileServiceFeesSuite {
    private static final String MEMO = "Really quite something!";
    private static final String CIVILIAN = "civilian";
    private static final String KEY = "key";
    private static final double BASE_FEE_FILE_CREATE = 0.05;
    private static final double BASE_FEE_FILE_UPDATE = 0.05;
    private static final double BASE_FEE_FILE_DELETE = 0.007;
    private static final double BASE_FEE_FILE_APPEND = 0.05;
    private static final double BASE_FEE_FILE_GET_CONTENT = 0.0001;
    private static final double BASE_FEE_FILE_GET_FILE = 0.0001;

    @HapiTest
    @DisplayName("USD base fee as expected for file create transaction")
    final Stream<DynamicTest> fileCreateBaseUSDFee() {
        // 90 days considered for base fee
        var contents = "0".repeat(1000).getBytes();

        return hapiTest(
                newKeyNamed(KEY).shape(KeyShape.SIMPLE),
                cryptoCreate(CIVILIAN).key(KEY).balance(ONE_HUNDRED_HBARS),
                newKeyListNamed("WACL", List.of(CIVILIAN)),
                fileCreate("test")
                        .memo(MEMO)
                        .key("WACL")
                        .contents(contents)
                        .payingWith(CIVILIAN)
                        .via("fileCreateBasic"),
                validateChargedUsd("fileCreateBasic", BASE_FEE_FILE_CREATE));
    }

    @HapiTest
    @DisplayName("USD base fee as expected for file update transaction")
    final Stream<DynamicTest> fileUpdateBaseUSDFee() {
        var contents = "0".repeat(1000).getBytes();

        return hapiTest(
                newKeyNamed("key").shape(KeyShape.SIMPLE),
                cryptoCreate(CIVILIAN).key("key").balance(ONE_HUNDRED_HBARS),
                newKeyListNamed("key", List.of(CIVILIAN)),
                fileCreate("test").key("key").contents("ABC"),
                fileUpdate("test")
                        .contents(contents)
                        .memo(MEMO)
                        .payingWith(CIVILIAN)
                        .via("fileUpdateBasic"),
                validateChargedUsd("fileUpdateBasic", BASE_FEE_FILE_UPDATE));
    }

    @HapiTest
    @DisplayName("USD base fee as expected for file delete transaction")
    final Stream<DynamicTest> fileDeleteBaseUSDFee() {
        return hapiTest(
                newKeyNamed("key").shape(KeyShape.SIMPLE),
                cryptoCreate(CIVILIAN).key("key").balance(ONE_HUNDRED_HBARS),
                newKeyListNamed("WACL", List.of(CIVILIAN)),
                fileCreate("test").memo(MEMO).key("WACL").contents("ABC"),
                fileDelete("test").blankMemo().payingWith(CIVILIAN).via("fileDeleteBasic"),
                validateChargedUsd("fileDeleteBasic", BASE_FEE_FILE_DELETE));
    }

    @HapiTest
    @DisplayName("USD base fee as expected for file append transaction")
    final Stream<DynamicTest> fileAppendBaseUSDFee() {
        final var civilian = "NonExemptPayer";

        final var baseAppend = "baseAppend";
        final var targetFile = "targetFile";
        final var contentBuilder = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            contentBuilder.append("A");
        }
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
                        .signedBy(magicKey)
                        .blankMemo()
                        .content(contentBuilder.toString())
                        .payingWith(civilian)
                        .via(baseAppend),
                validateChargedUsd(baseAppend, BASE_FEE_FILE_APPEND));
    }

    @HapiTest
    @DisplayName("USD base fee as expected for file get content transaction")
    final Stream<DynamicTest> fileGetContentBaseUSDFee() {
        return customizedHapiTest(
                Map.of("memo.useSpecName", "false"),
                cryptoCreate(CIVILIAN).balance(5 * ONE_HUNDRED_HBARS),
                fileCreate("ntb").key(CIVILIAN).contents("Nothing much!").memo(MEMO),
                getFileContents("ntb").payingWith(CIVILIAN).signedBy(CIVILIAN).via("getFileContentsBasic"),
                sleepFor(1000),
                validateChargedUsd("getFileContentsBasic", BASE_FEE_FILE_GET_CONTENT));
    }

    @HapiTest
    @DisplayName("USD base fee as expected for file get info transaction")
    final Stream<DynamicTest> fileGetInfoBaseUSDFee() {
        return customizedHapiTest(
                Map.of("memo.useSpecName", "false"),
                cryptoCreate(CIVILIAN).balance(5 * ONE_HUNDRED_HBARS),
                fileCreate("ntb").key(CIVILIAN).contents("Nothing much!").memo(MEMO),
                getFileInfo("ntb").payingWith(CIVILIAN).signedBy(CIVILIAN).via("getFileInfoBasic"),
                sleepFor(1000),
                validateChargedUsd("getFileInfoBasic", BASE_FEE_FILE_GET_FILE));
    }
}
