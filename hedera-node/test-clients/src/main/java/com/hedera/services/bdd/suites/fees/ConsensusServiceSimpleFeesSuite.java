// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.fees;

import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.junit.TestTags.SIMPLE_FEES;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.createTopic;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.deleteTopic;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.submitMessageTo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.updateTopic;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsd;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsdWithin;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.spec.SpecOperation;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;

@Tag(SIMPLE_FEES)
@Tag(MATS)
@HapiTestLifecycle
public class ConsensusServiceSimpleFeesSuite {

    @FunctionalInterface
    public interface OpsProvider {
        List<SpecOperation> provide();
    }

    private Stream<DynamicTest> compare(OpsProvider provider) {
        List<SpecOperation> opsList = new ArrayList<>();
        opsList.add(overriding("fees.simpleFeesEnabled", "false"));
        opsList.add(withOpContext((spec, op) -> {
            System.out.println("old fees");
        }));
        opsList.addAll(provider.provide());
        opsList.add(overriding("fees.simpleFeesEnabled", "true"));
        opsList.add(withOpContext((spec, op) -> {
            System.out.println("new fees");
        }));
        opsList.addAll(provider.provide());
        return hapiTest(opsList.toArray(new SpecOperation[opsList.size()]));
    }

    @Nested
    class TopicFeesComparison {
        private static final String PAYER = "payer";
        private static final String ADMIN = "admin";

        @HapiTest()
        @DisplayName("compare create topic")
        final Stream<DynamicTest> createTopicPlainComparison() {
            return compare(() -> Arrays.asList(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    createTopic("testTopic")
                            .blankMemo()
                            .payingWith(PAYER)
                            .fee(ONE_HBAR)
                            .via("create-topic-txn"),
                    validateChargedUsd("create-topic-txn", 0.01)));
        }

        @HapiTest()
        @DisplayName("compare create topic with admin key")
        final Stream<DynamicTest> createTopicWithAdminComparison() {
            return compare(() -> Arrays.asList(
                    newKeyNamed(ADMIN),
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    createTopic("testTopic")
                            .blankMemo()
                            .payingWith(PAYER)
                            .adminKeyName(ADMIN)
                            .fee(ONE_HBAR)
                            .via("create-topic-admin-txn"),
                    // TODO: adjust this once we get final pricing
                    validateChargedUsdWithin("create-topic-admin-txn", 0.0163, 30)));
        }

        @HapiTest()
        @DisplayName("compare create topic with payer as admin key")
        final Stream<DynamicTest> createTopicWithPayerAdminComparison() {
            return compare(() -> Arrays.asList(
                    newKeyNamed(ADMIN),
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    createTopic("testTopic")
                            .blankMemo()
                            .payingWith(PAYER)
                            .adminKeyName(PAYER)
                            .fee(ONE_HBAR)
                            .via("create-topic-admin-txn"),
                    // TODO: adjust this once we get final pricing
                    validateChargedUsdWithin("create-topic-admin-txn", 0.01022, 100)));
        }

        @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
        @DisplayName("compare update topic with admin key")
        final Stream<DynamicTest> updateTopicComparisonWithPayerAdmin() {
            return compare(() -> Arrays.asList(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    createTopic("testTopic")
                            .blankMemo()
                            .payingWith(PAYER)
                            .adminKeyName(PAYER)
                            .fee(ONE_HBAR)
                            .via("create-topic-admin-txn"),
                    // TODO: adjust this once we get final pricing
                    validateChargedUsdWithin("create-topic-admin-txn", 0.01022, 100),
                    updateTopic("testTopic").payingWith(PAYER).fee(ONE_HBAR).via("update-topic-txn"),
                    // TODO: adjust this once we get final pricing
                    validateChargedUsdWithin("update-topic-txn", 0.000356, 1000)));
        }

        @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
        @DisplayName("compare update topic with admin key")
        final Stream<DynamicTest> updateTopicComparisonWithAdmin() {
            final String ADMIN = "admin";
            return compare(() -> Arrays.asList(
                    newKeyNamed(ADMIN),
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    createTopic("testTopic")
                            .blankMemo()
                            .payingWith(PAYER)
                            .adminKeyName(ADMIN)
                            .fee(ONE_HBAR)
                            .via("create-topic-admin-txn"),
                    // TODO: adjust this once we get final pricing
                    validateChargedUsdWithin("create-topic-admin-txn", 0.02, 30),
                    updateTopic("testTopic")
                            .adminKey(ADMIN)
                            .payingWith(PAYER)
                            .fee(ONE_HBAR)
                            .via("update-topic-txn"),
                    // TODO: adjust this once we get final pricing
                    // old pricing          is 0.000356
                    // new official pricing is 0.000220
                    // 2 sigs + 1 key
                    validateChargedUsdWithin("update-topic-txn", 0.000356, 1000)));
        }

        @HapiTest()
        @DisplayName("compare submit message with included bytes")
        final Stream<DynamicTest> submitMessageFeeWithIncludedBytesComparison() {
            // 100 is less than the free size, so there's no per byte charge
            final var byte_size = 100;
            final byte[] messageBytes = new byte[byte_size]; // up to 1k
            Arrays.fill(messageBytes, (byte) 0b1);
            return compare(() -> Arrays.asList(
                    newKeyNamed(PAYER),
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    // create topic, provide up to 1 hbar to pay for it
                    createTopic("testTopic")
                            .blankMemo()
                            .payingWith(PAYER)
                            .fee(ONE_HBAR)
                            .via("create-topic-txn"),
                    validateChargedUsd("create-topic-txn", 0.01000),
                    // submit message, provide up to 1 hbar to pay for it
                    submitMessageTo("testTopic")
                            .blankMemo()
                            .payingWith(PAYER)
                            .message(new String(messageBytes))
                            .fee(ONE_HBAR)
                            .via("submit-message-txn"),
                    // old price is          0.00010
                    // official new price is 0.00010
                    // calc'd   new price is 0.0001899
                    // TODO: adjust this once we get final pricing
                    validateChargedUsdWithin("submit-message-txn", 0.00010, 100)));
        }

        @HapiTest()
        @DisplayName("compare submit message with extra bytes")
        final Stream<DynamicTest> submitBiggerMessageFeeComparison() {
            // 100 is less than the free size, so there's no per byte charge
            final var byte_size = 1023;
            final byte[] messageBytes = new byte[byte_size]; // up to 1k
            Arrays.fill(messageBytes, (byte) 0b1);
            return compare(() -> Arrays.asList(
                    newKeyNamed(PAYER),
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    // create topic, provide up to 1 hbar to pay for it
                    createTopic("testTopic")
                            .blankMemo()
                            .payingWith(PAYER)
                            .fee(ONE_HBAR)
                            .via("create-topic-txn"),
                    validateChargedUsd("create-topic-txn", 0.01000),
                    // submit message, provide up to 1 hbar to pay for it
                    submitMessageTo("testTopic")
                            .blankMemo()
                            .payingWith(PAYER)
                            .message(new String(messageBytes))
                            .fee(ONE_HBAR)
                            .via("submit-message-txn"),
                    // old prices is 0.0001233
                    // new price is  0.0001 + 110 for each extra byte
                    // TODO: adjust this once we get final pricing
                    validateChargedUsdWithin("submit-message-txn", 0.0001233, 100)));
        }

        // TODO: support queries
        //        @HapiTest()
        //        @DisplayName("compare get topic info")
        //        final Stream<DynamicTest> getTopicInfoComparison() {
        //            return compare(() -> Arrays.asList(
        //                    newKeyNamed(PAYER),
        //                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
        //                    // create topic. provide up to 1 hbar to pay for it
        //                    createTopic("testTopic")
        //                            .blankMemo()
        //                            .payingWith(PAYER)
        //                            .adminKeyName(PAYER)
        //                            .fee(ONE_HBAR)
        //                            .via("create-topic-txn"),
        //                    // the extra 10 is for the admin key
        //                    validateChargedUsd("create-topic-txn", 0.01022),
        //                    // get topic info, provide up to 1 hbar to pay for it
        //                    getTopicInfo("testTopic")
        //                            .payingWith(PAYER)
        //                            .fee(ONE_HBAR)
        //                            .via("get-topic-txn")
        //                            .logged(),
        //                    validateChargedUsd("get-topic-txn", 0.000101)));
        //        }

        @HapiTest()
        @DisplayName("compare delete topic with admin key")
        final Stream<DynamicTest> deleteTopicPlainComparison() {
            return compare(() -> Arrays.asList(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    createTopic("testTopic")
                            .blankMemo()
                            .payingWith(PAYER)
                            .adminKeyName(PAYER)
                            .fee(ONE_HBAR)
                            .via("create-topic-admin-txn"),
                    validateChargedUsdWithin("create-topic-admin-txn", 0.01020, 100),
                    deleteTopic("testTopic")
                            .signedBy(PAYER)
                            .payingWith(PAYER)
                            .fee(ONE_HBAR)
                            .via("delete-topic-txn"),
                    validateChargedUsdWithin("delete-topic-txn", 0.005, 10)));
        }
    }
}
