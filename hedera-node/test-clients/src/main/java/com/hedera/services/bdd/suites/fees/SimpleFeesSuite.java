// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.fees;

import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getFileContents;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTopicInfo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.createTopic;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.deleteTopic;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.submitMessageTo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.updateTopic;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedConsensusHbarFee;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsd;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.SIMPLE_FEE_SCHEDULE;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeExtraDef;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.SpecOperation;
import edu.umd.cs.findbugs.annotations.NonNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.hiero.hapi.support.fees.Extra;
import org.hiero.hapi.support.fees.FeeSchedule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;

@HapiTestLifecycle
public class SimpleFeesSuite {
    private static final String PAYER = "payer";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(Map.of("fees.simpleFeesEnabled", "true"));
    }

    static Stream<DynamicTest> runBeforeAfter(@NonNull final SpecOperation... ops) {
        List<SpecOperation> opsList = new ArrayList<>();
        opsList.add(overriding("fees.simpleFeesEnabled", "false"));
        opsList.addAll(Arrays.asList(ops));
        opsList.add(overriding("fees.simpleFeesEnabled", "true"));
        opsList.addAll(Arrays.asList(ops));
        return hapiTest(opsList.toArray(new SpecOperation[opsList.size()]));
    }

    static double ucents_to_USD(double amount) {
        return amount / 100_000.0;
    }

    @Nested
    class TopicFees {

        // create topic, basic
        @HapiTest
        @DisplayName("Simple Fees for creating a topic")
        final Stream<DynamicTest> createTopicPlain() {
            return hapiTest(
                    getFileContents(SIMPLE_FEE_SCHEDULE).payingWith(GENESIS),
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    createTopic("testTopic")
                            .blankMemo()
                            .payingWith(PAYER)
                            .fee(ONE_HBAR)
                            .via("create-topic-txn"),
                    validateChargedUsd(
                            "create-topic-txn",
                            ucents_to_USD(
                                    1000 // base fee for create topic
                                    + 0 // no extra keys
                                    + 1 * 3 // node and network fee
                            )));
        }

        @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
        @DisplayName("test creating a topic with and without simple fees")
        final Stream<DynamicTest> createTopicPlainComparison() {
            return runBeforeAfter(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    createTopic("testTopic")
                            .blankMemo()
                            .payingWith(PAYER)
                            .fee(ONE_HBAR)
                            .via("create-topic-txn"),
                    validateChargedUsd("create-topic-txn", ucents_to_USD(1000))
            );
        }

        @HapiTest
        @DisplayName("Simple fees for creating a topic with an admin key")
        final Stream<DynamicTest> createTopicWithAdmin() {
            return hapiTest(
                    getFileContents(SIMPLE_FEE_SCHEDULE).payingWith(GENESIS),
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    createTopic("testTopic")
                            .blankMemo()
                            .payingWith(PAYER)
                            .adminKeyName(PAYER)
                            .fee(ONE_HBAR)
                            .via("create-topic-txn"),
                    validateChargedUsd(
                            "create-topic-txn",
                            ucents_to_USD(
                                    1000 // base fee for create topic
                                    + 10 // admin key
                                    + 1 * 3 // node and network fee
                            )));
        }

        @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
        final Stream<DynamicTest> createTopicWithAdminComparison() {
            return runBeforeAfter(
                    getFileContents(SIMPLE_FEE_SCHEDULE).payingWith(GENESIS),
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    createTopic("testTopic")
                            .blankMemo()
                            .payingWith(PAYER)
                            .adminKeyName(PAYER)
                            .fee(ONE_HBAR)
                            .via("create-topic-txn"),
                    validateChargedUsd(
                            "create-topic-txn",
                            ucents_to_USD(1020))
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
                            .payingWith(PAYER)
                            .fee(ONE_HUNDRED_HBARS)
                            .via("create-topic-txn"),
                    validateChargedUsd(
                            "create-topic-txn",
                            ucents_to_USD(
                                    1000 // base fee for create topic
                                            + 200000 // custom fee
                                            + 1 * 3 // node + network fee
                                    )));
        }

        @HapiTest
        @DisplayName("Simple fees for creating a topic with custom fees")
        final Stream<DynamicTest> createTopicCustomFeeComparison() {
            return runBeforeAfter(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate("collector"),
                    createTopic("testTopic")
                            .blankMemo()
                            .withConsensusCustomFee(fixedConsensusHbarFee(88, "collector"))
                            .payingWith(PAYER)
                            .fee(ONE_HUNDRED_HBARS)
                            .via("create-topic-txn"),
                    validateChargedUsd(
                            "create-topic-txn",
                            ucents_to_USD(
                                    1000 // base fee for create topic
                                            + 200_000 // custom fee
                                            + 1 * 3 // node + network fee
                            )));
        }


        @HapiTest
        @DisplayName("Simple fees for updating a topic")
        final Stream<DynamicTest> updateTopicFee() {
            final String ADMIN = "admin";
            return hapiTest(
                    newKeyNamed(ADMIN),
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    // create topic. provide up to 100 hbar to pay for it
                    createTopic("testTopic")
                            .blankMemo()
                            .payingWith(PAYER)
                            .adminKeyName(ADMIN)
                            .fee(ONE_HUNDRED_HBARS)
                            .via("create-topic-txn"),
                    // create topic should be base:19 + key:(2-1), node:(base:1, sig:1) * 3 to include network
                    validateChargedUsd(
                            "create-topic-txn",
                            ucents_to_USD(
                                    1000 // base fee for create topic
                                            + 0 // 1024 bytes are included for free
                                            + 10 // one extra sig
                                            + (1) * 3 // extra sig in node fee, x3 to include network fee
                                    )),
                    // update topic, provide up to 100 hbar to pay for it
                    // update topic is base:19 + key(1-1), node:(base:1,sig:1)*3 to include network
                    updateTopic("testTopic")
                            .adminKey(ADMIN)
                            .payingWith(PAYER)
                            .fee(ONE_HUNDRED_HBARS)
                            .via("update-topic-txn"),
                    validateChargedUsd(
                            "update-topic-txn",
                            ucents_to_USD(
                                    31 // base fee for update topic
                                            + 0 // 1024 bytes are included for free
                                            + 1 // one extra sig
                                            + 1 * 3 // 1 extra sig (3) for node fee, x3 to include network fee
                                    )));
        }

        //TODO: the version where we create a new admin key costs extra
        @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
        @DisplayName("update a topic with and without simple fees")
        final Stream<DynamicTest> updateTopicComparison() {
            final String ADMIN = "admin";
            return runBeforeAfter(
                    newKeyNamed(ADMIN),
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    // create topic. provide up to 100 hbar to pay for it
                    createTopic("testTopic")
                            .blankMemo()
                            .payingWith(PAYER)
//                            .adminKeyName(ADMIN)
                            .adminKeyName(PAYER)
                            .fee(ONE_HBAR)
                            .via("create-topic-txn"),
                    validateChargedUsd( "create-topic-txn", ucents_to_USD(1020)),
                    // update topic, provide up to 100 hbar to pay for it
                    // update topic is base:19 + key(1-1), node:(base:1,sig:1)*3 to include network
                    updateTopic("testTopic")
                            .adminKey(ADMIN)
                            .payingWith(PAYER)
                            .fee(ONE_HUNDRED_HBARS)
                            .via("update-topic-txn"),
                    validateChargedUsd(
                            "update-topic-txn",
                            ucents_to_USD(35))
            );
        }


        @HapiTest
        @DisplayName("Simple fees for getting a topic transaction info")
        final Stream<DynamicTest> getTopicInfoFee() {
            return hapiTest(
                    newKeyNamed(PAYER),
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    // create topic. provide up to 1 hbar to pay for it
                    createTopic("testTopic")
                            .blankMemo()
                            .payingWith(PAYER)
                            .adminKeyName(PAYER)
                            .fee(ONE_HBAR)
                            .via("create-topic-txn"),
                    // the extra 10 is for the admin key
                    validateChargedUsd("create-topic-txn", ucents_to_USD(1000 + 10 + 1 * 3)),
                    // get topic info, provide up to 1 hbar to pay for it
                    getTopicInfo("testTopic")
                            .payingWith(PAYER)
                            .fee(ONE_HBAR)
                            .via("get-topic-txn")
                            .logged(),
                    validateChargedUsd("get-topic-txn", 0.0001));
        }

        @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
        @DisplayName("get topic info with and without simple fees")
        final Stream<DynamicTest> getTopicInfoComparison() {
            return runBeforeAfter(
                    newKeyNamed(PAYER),
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    // create topic. provide up to 1 hbar to pay for it
                    createTopic("testTopic")
                            .blankMemo()
                            .payingWith(PAYER)
                            .adminKeyName(PAYER)
                            .fee(ONE_HBAR)
                            .via("create-topic-txn"),
                    // the extra 10 is for the admin key
                    validateChargedUsd("create-topic-txn", ucents_to_USD(1000 + 10 + 1 * 3)),
                    // get topic info, provide up to 1 hbar to pay for it
                    getTopicInfo("testTopic")
                            .payingWith(PAYER)
                            .fee(ONE_HBAR)
                            .via("get-topic-txn")
                            .logged(),
                    validateChargedUsd("get-topic-txn", ucents_to_USD(10))
            );
        }

        @HapiTest
        @DisplayName("Simple fee for submitting a message, all bytes included")
        final Stream<DynamicTest> submitMessageFeeWithIncludedBytes() {
            // 100 is less than the free size, so there's no per byte charge
            final var byte_size = 100;
            final byte[] messageBytes = new byte[byte_size]; // up to 1k
            Arrays.fill(messageBytes, (byte) 0b1);
            return hapiTest(
                    newKeyNamed(PAYER),
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    // create topic, provide up to 1 hbar to pay for it
                    createTopic("testTopic")
                            .blankMemo()
                            .payingWith(PAYER)
                            .fee(ONE_HBAR)
                            .via("create-topic-txn"),
                    validateChargedUsd("create-topic-txn", ucents_to_USD(1000 + 1 * 3)),
                    // submit message, provide up to 1 hbar to pay for it
                    submitMessageTo("testTopic")
                            .blankMemo()
                            .payingWith(PAYER)
                            .message(new String(messageBytes))
                            .fee(ONE_HBAR)
                            .via("submit-message-txn"),
                    validateChargedUsd(
                            "submit-message-txn",
                            ucents_to_USD(
                                    7 // base fee
                                    + 1 * 3 // node + network fee
                                    )));
        }

        @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
        @DisplayName("submit included message with and without simple fees")
        final Stream<DynamicTest> submitMessageFeeWithIncludedBytesComparison() {
            // 100 is less than the free size, so there's no per byte charge
            final var byte_size = 100;
            final byte[] messageBytes = new byte[byte_size]; // up to 1k
            Arrays.fill(messageBytes, (byte) 0b1);
            return runBeforeAfter(
                    newKeyNamed(PAYER),
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    // create topic, provide up to 1 hbar to pay for it
                    createTopic("testTopic")
                            .blankMemo()
                            .payingWith(PAYER)
                            .fee(ONE_HBAR)
                            .via("create-topic-txn"),
                    validateChargedUsd("create-topic-txn", ucents_to_USD(1000 + 1 * 3)),
                    // submit message, provide up to 1 hbar to pay for it
                    submitMessageTo("testTopic")
                            .blankMemo()
                            .payingWith(PAYER)
                            .message(new String(messageBytes))
                            .fee(ONE_HBAR)
                            .via("submit-message-txn"),
                    validateChargedUsd(
                            "submit-message-txn",
                            ucents_to_USD(
                                    7 // base fee
                                            + 1 * 3 // node + network fee
                            )));
        }

        @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
        @DisplayName("submit included message with custom fees  with and without simple fees")
        final Stream<DynamicTest> submitCustomFeeMessageWithIncludedBytesComparison() {
            // 100 is less than the free size, so there's no per byte charge
            final var byte_size = 100;
            final byte[] messageBytes = new byte[byte_size]; // up to 1k
            Arrays.fill(messageBytes, (byte) 0b1);
            return runBeforeAfter(
//                    newKeyNamed(PAYER),
                    cryptoCreate(PAYER).balance(ONE_MILLION_HBARS),
                    cryptoCreate("collector"),
                    createTopic("testTopic")
                            .blankMemo()
                            .withConsensusCustomFee(fixedConsensusHbarFee(88, "collector"))
                            .payingWith(PAYER)
                            .fee(ONE_HUNDRED_HBARS)
                            .via("create-topic-txn"),
                    validateChargedUsd(
                            "create-topic-txn",
                            ucents_to_USD(
                                    1000 // base fee for create topic
                                            + 200_000 // custom fee
                                            + 1 * 3 // node + network fee
                            ))
                    ,
                    // submit message, provide up to 1 hbar to pay for it
                    submitMessageTo("testTopic")
                            .blankMemo()
                            .payingWith(PAYER)
                            .message(new String(messageBytes))
                            .fee(ONE_HUNDRED_HBARS)
                            .via("submit-message-txn"),
                    validateChargedUsd(
                            "submit-message-txn",
                            ucents_to_USD(
                                    7 // base fee
                                            + 5000 // custom fee
                                            + 1 * 3 // node + network fee
                            ))
            );
        }

        @HapiTest
        @DisplayName("Simple fee for submitting a large message")
        final Stream<DynamicTest> submitBiggerMessageFee() {
            // 256 included + an extra 500
            final var byte_size = 500 + 256;
            final byte[] messageBytes = new byte[byte_size]; // up to 1k
            Arrays.fill(messageBytes, (byte) 0b1);
            return hapiTest(
                    newKeyNamed(PAYER),
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    // create topic, provide up to 1 hbar to pay for it
                    createTopic("testTopic")
                            .blankMemo()
                            .payingWith(PAYER)
                            .fee(ONE_HBAR)
                            .via("create-topic-txn"),
                    validateChargedUsd("create-topic-txn", ucents_to_USD(1000 + 1 * 3)),
                    // submit message, provide up to 1 hbar to pay for it
                    submitMessageTo("testTopic")
                            .blankMemo()
                            .payingWith(PAYER)
                            .message(new String(messageBytes))
                            .fee(ONE_HBAR)
                            .via("submit-message-txn"),
                    validateChargedUsd(
                            "submit-message-txn",
                            ucents_to_USD(
                                    7 // base fee for submit message
                                            + 1.6 // for the extra 500 bytes
                                            + 1 * 3 // node + network fee
                                    )));
        }

        @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
        @DisplayName("submit big message with and without simple fees")
        final Stream<DynamicTest> submitBiggerMessageFeeComparison() {
            // 100 is less than the free size, so there's no per byte charge
            final var byte_size = 500 + 256;
            final byte[] messageBytes = new byte[byte_size]; // up to 1k
            Arrays.fill(messageBytes, (byte) 0b1);
            return runBeforeAfter(
                    newKeyNamed(PAYER),
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    // create topic, provide up to 1 hbar to pay for it
                    createTopic("testTopic")
                            .blankMemo()
                            .payingWith(PAYER)
                            .fee(ONE_HBAR)
                            .via("create-topic-txn"),
                    validateChargedUsd("create-topic-txn", ucents_to_USD(1000 + 1 * 3)),
                    // submit message, provide up to 1 hbar to pay for it
                    submitMessageTo("testTopic")
                            .blankMemo()
                            .payingWith(PAYER)
                            .message(new String(messageBytes))
                            .fee(ONE_HBAR)
                            .via("submit-message-txn"),
                    validateChargedUsd(
                            "submit-message-txn",
                            ucents_to_USD(
                                    7 // base fee
                                            + 1.6 // overage fees
                                            + 1 * 3 // node + network fee
                            )));
        }


        @HapiTest()
        @DisplayName("Simple fee for deleting a topic")
        final Stream<DynamicTest> deleteTopicPlain() {
            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    createTopic("testTopic")
                            .blankMemo()
                            .payingWith(PAYER)
                            .adminKeyName(PAYER)
                            .fee(ONE_HBAR)
                            .via("create-topic-txn"),
                    validateChargedUsd(
                            "create-topic-txn",
                            ucents_to_USD(
                                    1000 // base fee for create topic
                                            + 10 // for the admin key
                                            + 1 * 3 // node + network fee
                                    )),
                    deleteTopic("testTopic").payingWith(PAYER).fee(ONE_HBAR).via("delete-topic-txn"),
                    validateChargedUsd(
                            "delete-topic-txn",
                            ucents_to_USD(
                                    470 // base fee for delete topic
                                            + 11 * 3 // node + network fee
                                    )));
        }

        @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
        @DisplayName("Simple fee for deleting a topic with and without simple fees")
        final Stream<DynamicTest> deleteTopicPlainComparison() {
            return runBeforeAfter(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    createTopic("testTopic")
                            .blankMemo()
                            .payingWith(PAYER)
                            .adminKeyName(PAYER)
                            .fee(ONE_HBAR)
                            .via("create-topic-txn"),
                    validateChargedUsd(
                            "create-topic-txn",
                            ucents_to_USD(1020))
                    ,
                    deleteTopic("testTopic").payingWith(PAYER).fee(ONE_HBAR).via("delete-topic-txn"),
                    validateChargedUsd(
                            "delete-topic-txn",
                            ucents_to_USD(505))
            );
        }
    }
}
