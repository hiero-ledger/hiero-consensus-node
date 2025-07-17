package com.hedera.services.bdd.suites.fees;

import com.hedera.node.app.hapi.fees.BaseFeeRegistry;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Stream;

import static com.hedera.node.app.hapi.fees.apis.common.FeeConstants.FILE_FREE_BYTES;
import static com.hedera.node.app.hapi.fees.apis.common.FeeConstants.HCS_FREE_BYTES;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getFileContents;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getFileInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTopicInfo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.createTopic;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.deleteTopic;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileAppend;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.submitMessageTo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.updateTopic;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedConsensusHbarFee;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsd;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;

@HapiTestLifecycle
public class SimpleFeesSuite {
    private static final String PAYER = "payer";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(Map.of("fees.simpleFeesEnabled", "true"));
    }

    @Nested
    class TopicFees {
        // create topic, basic
        @HapiTest
        @DisplayName("Simple fees for creating a topic")
        final Stream<DynamicTest> createTopicFee() {
            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    createTopic("testTopic").blankMemo().payingWith(PAYER).via("create-topic-txn"),
                    validateChargedUsd("create-topic-txn", 0.01)
            );
        }

        @HapiTest
        @DisplayName("Simple fees for creating a topic with custom fees")
        final Stream<DynamicTest> createTopicCustomFee() {
            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate("collector"),
                    createTopic("testTopic")
                        .blankMemo()
                            .withConsensusCustomFee(fixedConsensusHbarFee(88, "collector"))
                        .payingWith(PAYER).via("create-topic-txn"),
                validateChargedUsd("create-topic-txn", 2)
            );
        }

        @HapiTest
        @DisplayName("Simple fees for updating a topic")
        final Stream<DynamicTest> updateTopicFee() {
            final String ADMIN = "admin";
            return hapiTest(
                    newKeyNamed(ADMIN),
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    createTopic("testTopic").blankMemo().payingWith(PAYER).adminKeyName(ADMIN).via("create-topic-txn"),
                    updateTopic("testTopic").adminKey(ADMIN).payingWith(PAYER).via("update-topic-txn"),
                    validateChargedUsd("create-topic-txn", 0.010_00),
                    validateChargedUsd("update-topic-txn", 0.000_22)
            );
        }

        @HapiTest
        @DisplayName("Simple fees for getting a topic transaction info")
        final Stream<DynamicTest> getTopicInfoFee() {
            return hapiTest(
                    newKeyNamed(PAYER),
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    createTopic("testTopic").blankMemo().payingWith(PAYER).adminKeyName(PAYER).via("create-topic-txn"),
                    getTopicInfo("testTopic").payingWith(PAYER).via("get-topic-txn").logged(),
                    validateChargedUsd("create-topic-txn", 0.01),
                    validateChargedUsd("get-topic-txn", 0.000_1)
            );
        }


        @HapiTest
        @DisplayName("Simple fee for submitting a message")
        final Stream<DynamicTest> submitMessageFee() {
            final byte[] messageBytes = new byte[600]; // up to 1k
            Arrays.fill(messageBytes, (byte) 0b1);
            final var excess_bytes = 600-HCS_FREE_BYTES;
            final var base = BaseFeeRegistry.getBaseFee("ConsensusSubmitMessage");
            final var per_byte = BaseFeeRegistry.getBaseFee("PerHCSByte");
            System.out.println("COST: " + base + " "  + excess_bytes + " "  + per_byte);
            System.out.println("COST: " + (base + excess_bytes * per_byte));
            return hapiTest(
                    newKeyNamed(PAYER),
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    createTopic("testTopic")
                            .blankMemo()
                            .payingWith(PAYER)
                            .via("create-topic-txn"),
                    validateChargedUsd("create-topic-txn", 0.010_00),
                    submitMessageTo("testTopic")
                            .blankMemo()
                            .payingWith(PAYER)
                            .message(new String(messageBytes))
                            .via("submit-message-txn"),
                    // this is the correct amount,
                    // but it fails with insufficent tx fee for some reason
                    validateChargedUsd("submit-message-txn", base + excess_bytes * per_byte, 1)
            );
        }

        //TODO: Submit message with custom fee

        //TODO: submit bigger message

        // delete topic
        @LeakyHapiTest(overrides = "fees.simpleFeesEnabled")
        final Stream<DynamicTest> deleteTopicFee() {
            return hapiTest(
                    overriding("fees.simpleFeesEnabled", "true"),
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    createTopic("testTopic").blankMemo().payingWith(PAYER).adminKeyName(PAYER).via("create-topic-txn"),
                    deleteTopic("testTopic").payingWith(PAYER).via("delete-topic-txn"),
                    validateChargedUsd("create-topic-txn", 0.010_00),
                    validateChargedUsd("delete-topic-txn", 0.005_00)
            );
        }
    }

    @Nested
    class FileFees {
        @HapiTest
        final Stream<DynamicTest> fileCreateFee() {
            final var byte_count = 1789;
            var contents = "0".repeat(byte_count).getBytes();
            final var PerFileByte = 0.000011;
            final var FileCreate = 0.050_00;
            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    fileCreate("test")
                            .memo("memotext")
                            .contents(contents)
                            .payingWith(PAYER)
                            .via("create-file-txn"),
                    validateChargedUsd("create-file-txn", FileCreate + (byte_count - FILE_FREE_BYTES) * PerFileByte)
            );
        }

        @HapiTest
        final Stream<DynamicTest> fileUpdateFee() {
            final var PerFileByte = 0.000011;
            final var FileUpdate = 0.050_00;

            var contents = "0".repeat(1789).getBytes();

            final var byte_count = 4567;
            var new_contents = "0".repeat(byte_count).getBytes();
            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    fileCreate("test")
                            .memo("memotext")
                            .contents(contents)
                            .payingWith(PAYER)
                            .via("create-file-txn"),
                    fileUpdate("test")
                            .contents(new_contents)
                            .payingWith(PAYER)
                            .via("update-file-txn"),
                    validateChargedUsd("update-file-txn", FileUpdate + (byte_count - FILE_FREE_BYTES) * PerFileByte)
            );
        }

        // TODO: FileDelete transaction body doesn't expose the file contents so we can't
        //  calculate how many bytes are being deleted
        @HapiTest
        final Stream<DynamicTest> fileDeleteFee() {
            final var PerFileByte = 0.000_011;
            final var FileDelete = 0.007_00;
            final var byte_count = 1789;

            var contents = "0".repeat(byte_count).getBytes();

            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    fileCreate("test")
                            .memo("memotext")
                            .contents(contents)
                            .payingWith(PAYER)
                            .via("create-file-txn"),
                    fileDelete("test")
                            .payingWith(PAYER)
                            .via("delete-file-txn"),
                    validateChargedUsd("delete-file-txn", FileDelete)// + (byte_count - FILE_FREE_BYTES) * PerFileByte)
            );
        }

        @HapiTest
        final Stream<DynamicTest> fileAppendFee() {
            final var PerFileByte = 0.000011;
            final var FileAppend = 0.050_00;


            final var byte_count = 4567;
            var new_contents = "0".repeat(byte_count).getBytes();
            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    fileCreate("test")
                            .memo("memotext")
                            .contents("0".repeat(1789).getBytes())
                            .payingWith(PAYER)
                            .via("create-file-txn"),
                    fileAppend("test")
                            .content(new_contents)
                            .payingWith(PAYER)
                            .via("append-file-txn"),
                    validateChargedUsd("append-file-txn", FileAppend + (byte_count - FILE_FREE_BYTES) * PerFileByte)
            );
        }

        @HapiTest
        final Stream<DynamicTest> fileGetContents() {
            final var PerFileByte = 0.000_011;
            final var FileGetContents = 0.000_10;
            final var FileCreate = 0.050_00;
            final var byte_count = 3764;
            final var correct= Math.max(byte_count - FILE_FREE_BYTES, 0) * PerFileByte + FileGetContents;
            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    fileCreate("test")
                            .memo("memotext")
                            .contents("0".repeat(byte_count).getBytes())
                            .payingWith(PAYER)
                            .via("create-file-txn"),
                    validateChargedUsd("create-file-txn", FileCreate + (byte_count - FILE_FREE_BYTES) * PerFileByte),
                    getFileContents("test")
                            .payingWith(PAYER)
                            .via("get-file-contents-txn"),
                    validateChargedUsd("get-file-contents-txn", correct)
            );
        }

        @HapiTest
        final Stream<DynamicTest> fileGetInfo() {
            final var PerFileByte = 0.000_011;
            final var FileGetContents = 0.000_10;
            final var byte_count = 3764;
            final var correct= Math.max(byte_count - FILE_FREE_BYTES, 0) * PerFileByte + FileGetContents;
            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    fileCreate("test")
                            .memo("memotext")
                            .contents("0".repeat(byte_count).getBytes())
                            .payingWith(PAYER)
                            .via("create-file-txn"),
                    getFileInfo("test")
                            .payingWith(PAYER)
                            .via("get-file-info-txn"),
                    validateChargedUsd("get-file-info-txn", correct)
            );
        }
    }


    // TODO: CryptoCreate, create token
    // TODO: CryptoCreate, create token with custom fees
    // TODO: CryptoDelete, delete token
    // TODO: CryptoTransfer, transfer value in a FT
    // TODO: CryptoGetAccountRecords: ??
    // TODO: CryptoGetAccountBalance: ??
    // TODO: CryptoGetInfo: ??
    // TODO: CryptoApproveAllowance: approve single and multiple allowances
    // TODO: CryptoDeleteAllowance: approvate

    // TODO: Token services
    // TODO: scheduled gransactions
    // TODO: smart contracts
    // TODO: File service
    // TODO: random other stuff
}
