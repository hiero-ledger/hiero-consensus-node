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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;

@HapiTestLifecycle
public class SimpleFeesSuite {
    private static final String PAYER = "payer";
    private static final String ADMIN = "admin";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(Map.of("fees.simpleFeesEnabled", "true"));
    }

    static Stream<DynamicTest> runBeforeAfter(@NonNull final SpecOperation... ops) {
        List<SpecOperation> opsList = new ArrayList<>();
        opsList.add(overriding("fees.simpleFeesEnabled", "false"));
        //        opsList.add(withOpContext((spec, log) -> {
        //            System.out.println("simple fees enabled = false");
        //        }));
        opsList.addAll(Arrays.asList(ops));
        opsList.add(overriding("fees.simpleFeesEnabled", "true"));
        //        opsList.add(withOpContext((spec, log) -> {
        //            System.out.println("simple fees enabled = true");
        //        }));
        opsList.addAll(Arrays.asList(ops));
        return hapiTest(opsList.toArray(new SpecOperation[opsList.size()]));
    }

    static double ucents_to_USD(double amount) {
        return amount / 100_000.0;
    }

    @Nested
    class TopicFeesComparison {
        @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
        @DisplayName("compare create topic")
        final Stream<DynamicTest> createTopicPlainComparison() {
            return runBeforeAfter(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    createTopic("testTopic")
                            .blankMemo()
                            .payingWith(PAYER)
                            .fee(ONE_HBAR)
                            .via("create-topic-txn"),
                    validateChargedUsd("create-topic-txn", ucents_to_USD(1000))
                    // keys = 0, sigs = 1
                    );
        }

        @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
        @DisplayName("compare create topic with admin key")
        final Stream<DynamicTest> createTopicWithAdminComparison() {
            return runBeforeAfter(
                    getFileContents(SIMPLE_FEE_SCHEDULE).payingWith(GENESIS),
                    newKeyNamed(ADMIN),
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    createTopic("testTopic")
                            .blankMemo()
                            .payingWith(PAYER)
                            .adminKeyName(ADMIN)
                            .fee(ONE_HBAR)
                            .via("create-topic-admin-txn"),
                    validateChargedUsd("create-topic-admin-txn", ucents_to_USD(1630))

                    // keys = 1, sigs = 2
                    );
        }

        @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
        @DisplayName("compare create topic with payer as admin key")
        final Stream<DynamicTest> createTopicWithPayerAdminComparison() {
            return runBeforeAfter(
                    getFileContents(SIMPLE_FEE_SCHEDULE).payingWith(GENESIS),
                    newKeyNamed(ADMIN),
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    // keys = 1, sigs = 1,
                    createTopic("testTopic")
                            .blankMemo()
                            .payingWith(PAYER)
                            .adminKeyName(PAYER)
                            .fee(ONE_HBAR)
                            .via("create-topic-admin-txn"),
                    validateChargedUsd("create-topic-admin-txn", ucents_to_USD(1022)));
        }

        @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
        @DisplayName("compare update topic with admin key")
        final Stream<DynamicTest> updateTopicComparison() {
            final String ADMIN = "admin";
            return runBeforeAfter(
                    newKeyNamed(ADMIN),
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    // create topic. provide up to 100 hbar to pay for it
                    createTopic("testTopic")
                            .blankMemo()
                            .payingWith(PAYER)
                            .adminKeyName(ADMIN)
                            .fee(ONE_HBAR)
                            .via("create-topic-admin-txn"),
                    validateChargedUsd("create-topic-admin-txn", ucents_to_USD(1630)),
                    // update topic is base:19 + key(1-1), node:(base:1,sig:1)*3 to include network
                    updateTopic("testTopic")
                            .adminKey(ADMIN)
                            .payingWith(PAYER)
                            .fee(ONE_HBAR)
                            .via("update-topic-txn"),
                    validateChargedUsd("update-topic-txn", ucents_to_USD(35.4)));
        }

        @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
        @DisplayName("compare submit message with included bytes")
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
                    validateChargedUsd("create-topic-txn", ucents_to_USD(1000)),
                    // submit message, provide up to 1 hbar to pay for it
                    submitMessageTo("testTopic")
                            .blankMemo()
                            .payingWith(PAYER)
                            .message(new String(messageBytes))
                            .fee(ONE_HBAR)
                            .via("submit-message-txn"),
                    validateChargedUsd("submit-message-txn", ucents_to_USD(10)));
        }

        @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
        @DisplayName("compare submit message with extra bytes")
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
                    validateChargedUsd("create-topic-txn", ucents_to_USD(1000)),
                    // submit message, provide up to 1 hbar to pay for it
                    submitMessageTo("testTopic")
                            .blankMemo()
                            .payingWith(PAYER)
                            .message(new String(messageBytes))
                            .fee(ONE_HBAR)
                            .via("submit-message-txn"),
                    validateChargedUsd("submit-message-txn", ucents_to_USD(11.6)));
        }

        @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
        @DisplayName("compare get topic info")
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
                    validateChargedUsd("create-topic-txn", ucents_to_USD(1022)),
                    // get topic info, provide up to 1 hbar to pay for it
                    getTopicInfo("testTopic")
                            .payingWith(PAYER)
                            .fee(ONE_HBAR)
                            .via("get-topic-txn")
                            .logged(),
                    validateChargedUsd("get-topic-txn", ucents_to_USD(10.1)));
        }

        @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
        @DisplayName("compare delete topic with admin key")
        final Stream<DynamicTest> deleteTopicPlainComparison() {
            return runBeforeAfter(
                    newKeyNamed(ADMIN),
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    createTopic("testTopic")
                            .blankMemo()
                            .payingWith(PAYER)
                            .adminKeyName(ADMIN)
                            .fee(ONE_HBAR)
                            .via("create-topic-admin-txn"),
                    validateChargedUsd("create-topic-admin-txn", ucents_to_USD(1630)),
                    deleteTopic("testTopic").payingWith(PAYER).fee(ONE_HBAR).via("delete-topic-txn"),
                    validateChargedUsd("delete-topic-txn", ucents_to_USD(505 + 315)));
        }
    }

    @Nested
    class TopicCustomFees {
        @HapiTest
        @DisplayName("compare create topic with custom fee")
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
                                            + 0 // node + network fee
                                    )));
        }

        @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
        @DisplayName("compare submit message with custom fee and included bytes")
        final Stream<DynamicTest> submitCustomFeeMessageWithIncludedBytesComparison() {
            // 100 is less than the free size, so there's no per byte charge
            final var byte_size = 100;
            final byte[] messageBytes = new byte[byte_size]; // up to 1k
            Arrays.fill(messageBytes, (byte) 0b1);
            return runBeforeAfter(
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
                                    )),
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
                                    )));
        }
    }

    @Nested
    class TopicFees {

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
    }
}
