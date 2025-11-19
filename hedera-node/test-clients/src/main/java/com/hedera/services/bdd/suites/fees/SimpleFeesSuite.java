// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.fees;

import static com.hedera.node.app.hapi.utils.CommonUtils.extractTransactionBody;
import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.junit.TestTags.SIMPLE_FEES;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getReceipt;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.createTopic;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.deleteTopic;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.submitMessageTo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uncheckedSubmit;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.updateTopic;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingHbar;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.usableTxnIdNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsd;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.DUPLICATE_TRANSACTION;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_PAYER_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_TX_FEE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_PAYER_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TRANSACTION_DURATION;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TRANSACTION_START;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.MEMO_TOO_LONG;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.RECORD_NOT_FOUND;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TRANSACTION_EXPIRED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.SpecOperation;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;

@Tag(MATS)
@Tag(SIMPLE_FEES)
@HapiTestLifecycle
public class SimpleFeesSuite {
    private static final String PAYER = "payer";
    private static final String ADMIN = "admin";
    private static final String NEW_ADMIN = "newAdmin";

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

    private static long ucents(int value) {
        return value * 100000;
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

    /*
    Disable custom fees for now.
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
     */

    @Nested
    class TopicFees {
        /*
        Disable custom fees for now.
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
        */
        //        @LeakyHapiTest
        //        @DisplayName("Simple fees for getting a topic transaction info")
        //        final Stream<DynamicTest> getTopicInfoFee() {
        //            var feeSchedule = FeeSchedule.DEFAULT
        //                    .copyBuilder()
        //                    .extras(
        //                            makeExtraDef(Extra.BYTES, 1),
        //                            makeExtraDef(Extra.KEYS, 2),
        //                            makeExtraDef(Extra.SIGNATURES, 3),
        //                            makeExtraDef(Extra.CUSTOM_FEE, 500))
        //                    .node(NodeFee.DEFAULT
        //                            .copyBuilder()
        //                            .build())
        //                    .network(NetworkFee.DEFAULT.copyBuilder().multiplier(2).build())
        //                    .services(makeService(
        //                            "Consensus",
        //                            makeServiceFee(CONSENSUS_CREATE_TOPIC, ucents(15), makeExtraIncluded(Extra.KEYS,
        // 1)),
        //                            makeServiceFee(CONSENSUS_GET_TOPIC_INFO, ucents(10))))
        //                    .build();
        //            final var contents = FeeSchedule.PROTOBUF.toBytes(feeSchedule).toByteArray();
        //            return hapiTest(
        //                    fileUpdate(SIMPLE_FEE_SCHEDULE).payingWith(GENESIS).contents(contents),
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
        //                    validateChargedUsd("create-topic-txn",ucents_to_USD(15)),
        //                    // get topic info, provide up to 1 hbar to pay for it
        //                    getTopicInfo("testTopic")
        //                            .payingWith(PAYER)
        //                            .fee(ONE_HBAR)
        //                            .via("get-topic-txn")
        //                            .logged(),
        //                    validateChargedUsd("get-topic-txn", ucents_to_USD(10))
        //            );
        //        }
    }

    @Nested
    class TopicFeesNegativeCases {

        @Nested
        class TopicFeesComparisonCreateTopicFailsOnIngest {
            @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
            @DisplayName("create topic with insufficient txn fee fails on ingest and payer not charged")
            final Stream<DynamicTest> createTopicInsufficientFeeFailsOnIngest() {
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();
                return runBeforeAfter(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),

                        // Save payer balance before
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),

                        // Create topic with insufficient fee
                        createTopic("testTopic")
                                .blankMemo()
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .fee(ONE_HBAR / 100000) // fee is too low
                                .via("create-topic-txn")
                                .hasPrecheck(INSUFFICIENT_TX_FEE),

                        // assert no txn record is created
                        getTxnRecord("create-topic-txn").logged().hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND),

                        // Save balances and assert changes
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        withOpContext((spec, log) -> {
                            assertEquals(initialBalance.get(), afterBalance.get());
                        }));
            }

            @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
            @DisplayName("create topic not signed by payer fails on ingest and payer not charged")
            final Stream<DynamicTest> createTopicNotSignedByPayerFailsOnIngest() {
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();
                return runBeforeAfter(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(ADMIN).balance(ONE_HUNDRED_HBARS),

                        // Save payer balance before
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),

                        // Create topic with admin key not signed by payer
                        createTopic("testTopic")
                                .blankMemo()
                                .payingWith(PAYER)
                                .adminKeyName(ADMIN)
                                .signedBy(ADMIN)
                                .fee(ONE_HBAR)
                                .via("create-topic-txn")
                                .hasPrecheck(INVALID_SIGNATURE),

                        // assert no txn record is created
                        getTxnRecord("create-topic-txn").logged().hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND),

                        // Save balances and assert changes
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        withOpContext((spec, log) -> {
                            assertEquals(initialBalance.get(), afterBalance.get());
                        }));
            }

            @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
            @DisplayName("create topic with insufficient payer balance fails on ingest and payer not charged")
            final Stream<DynamicTest> createTopicWithInsufficientPayerBalanceFailsOnIngest() {
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();
                return runBeforeAfter(
                        cryptoCreate(PAYER).balance(ONE_HBAR / 100000), // insufficient balance
                        newKeyNamed(ADMIN),

                        // Save payer balance before
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),

                        // Create topic with insufficient payer balance
                        createTopic("testTopic")
                                .blankMemo()
                                .payingWith(PAYER)
                                .adminKeyName(ADMIN)
                                .fee(ONE_HBAR)
                                .via("create-topic-txn")
                                .hasPrecheck(INSUFFICIENT_PAYER_BALANCE),

                        // assert no txn record is created
                        getTxnRecord("create-topic-txn").logged().hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND),

                        // Save balances and assert changes
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        withOpContext((spec, log) -> {
                            assertEquals(initialBalance.get(), afterBalance.get());
                        }));
            }

            @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
            @DisplayName("create topic with too long memo fails on ingest and payer not charged")
            final Stream<DynamicTest> createTopicTooLongMemoFailsOnIngest() {
                final var LONG_MEMO = "x".repeat(1025); // memo exceeds 1024 bytes limit
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();
                return runBeforeAfter(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),

                        // Save payer balance before
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),

                        // Create topic with too long memo
                        createTopic("testTopic")
                                .memo(LONG_MEMO)
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .fee(ONE_HBAR)
                                .via("create-topic-txn")
                                .hasPrecheck(MEMO_TOO_LONG),

                        // assert no txn record is created
                        getTxnRecord("create-topic-txn").logged().hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND),

                        // Save balances and assert changes
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        withOpContext((spec, log) -> {
                            assertEquals(initialBalance.get(), afterBalance.get());
                        }));
            }

            @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
            @DisplayName("create topic expired transaction fails on ingest and payer not charged")
            final Stream<DynamicTest> createTopicExpiredFailsOnIngest() {
                final var expiredTxnId = "expiredCreateTopic";
                final var oneHourPast = -3_600L; // 1 hour before
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();
                return runBeforeAfter(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),

                        // Save payer balance before
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),

                        // Create expired topic
                        usableTxnIdNamed(expiredTxnId)
                                .modifyValidStart(oneHourPast)
                                .payerId(PAYER),
                        createTopic("testTopic")
                                .blankMemo()
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .fee(ONE_HBAR)
                                .txnId(expiredTxnId)
                                .via("create-topic-txn")
                                .hasPrecheck(TRANSACTION_EXPIRED),

                        // assert no txn record is created
                        getTxnRecord("create-topic-txn").logged().hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND),

                        // Save balances and assert changes
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        withOpContext((spec, log) -> {
                            assertEquals(initialBalance.get(), afterBalance.get());
                        }));
            }

            @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
            @DisplayName("create topic with too far start time fails on ingest and payer not charged")
            final Stream<DynamicTest> createTopicTooFarStartTimeFailsOnIngest() {
                final var futureTxnId = "futureCreateTopic";
                final var oneHourFuture = 3_600L; // 1 hour before
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();
                return runBeforeAfter(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),

                        // Save payer balance before
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),

                        // Create topic with start time in the future
                        usableTxnIdNamed(futureTxnId)
                                .modifyValidStart(oneHourFuture)
                                .payerId(PAYER),
                        createTopic("testTopic")
                                .blankMemo()
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .fee(ONE_HBAR)
                                .txnId(futureTxnId)
                                .via("create-topic-txn")
                                .hasPrecheck(INVALID_TRANSACTION_START),

                        // assert no txn record is created
                        getTxnRecord("create-topic-txn").logged().hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND),

                        // Save balances and assert changes
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        withOpContext((spec, log) -> {
                            assertEquals(initialBalance.get(), afterBalance.get());
                        }));
            }

            @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
            @DisplayName("create topic with invalid duration time fails on ingest and payer not charged")
            final Stream<DynamicTest> createTopicInvalidDurationTimeFailsOnIngest() {
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();
                return runBeforeAfter(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),

                        // Save payer balance before
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),

                        // Create topic with invalid duration time
                        createTopic("testTopic")
                                .blankMemo()
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .fee(ONE_HBAR)
                                .validDurationSecs(0) // invalid duration
                                .via("create-topic-txn")
                                .hasPrecheck(INVALID_TRANSACTION_DURATION),

                        // assert no txn record is created
                        getTxnRecord("create-topic-txn").logged().hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND),

                        // Save balances and assert changes
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        withOpContext((spec, log) -> {
                            assertEquals(initialBalance.get(), afterBalance.get());
                        }));
            }

            @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
            @DisplayName("create topic duplicate txn fails on ingest and payer not charged")
            final Stream<DynamicTest> createTopicDuplicateTxnFailsOnIngest() {
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();
                return runBeforeAfter(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),

                        // Save payer balance before
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),

                        // Create topic successful first txn
                        createTopic("testTopic").blankMemo().fee(ONE_HBAR).via("create-topic-txn"),
                        // Create topic duplicate txn
                        createTopic("testTopicDuplicate")
                                .blankMemo()
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .fee(ONE_HBAR)
                                .txnId("create-topic-txn")
                                .via("create-topic-duplicate-txn")
                                .hasPrecheck(DUPLICATE_TRANSACTION),

                        // Save balances and assert changes
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        withOpContext((spec, log) -> {
                            assertEquals(initialBalance.get(), afterBalance.get());
                        }));
            }

            @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
            @DisplayName("update topic not signed by payer fails on ingest and payer not charged")
            final Stream<DynamicTest> updateTopicNotSignedByPayerFailsOnIngest() {
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();
                return runBeforeAfter(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(ADMIN).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(NEW_ADMIN).balance(ONE_HUNDRED_HBARS),

                        // Save payer balance before
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),

                        // Create topic with admin key
                        createTopic("testTopic")
                                .blankMemo()
                                .payingWith(ADMIN)
                                .adminKeyName(ADMIN)
                                .signedBy(ADMIN)
                                .fee(ONE_HBAR)
                                .via("create-topic-txn"),

                        // Update topic admin key not signed by payer
                        updateTopic("testTopic")
                                .blankMemo()
                                .payingWith(PAYER)
                                .adminKey(NEW_ADMIN)
                                .signedBy(ADMIN, NEW_ADMIN)
                                .fee(ONE_HBAR)
                                .via("update-topic-txn")
                                .hasPrecheck(INVALID_SIGNATURE),

                        // assert no txn record is created
                        getTxnRecord("update-topic-txn").logged().hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND),

                        // Save balances and assert changes
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        withOpContext((spec, log) -> {
                            assertEquals(initialBalance.get(), afterBalance.get());
                        }));
            }

            @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
            @DisplayName("delete topic not signed by payer fails on ingest and payer not charged")
            final Stream<DynamicTest> deleteTopicNotSignedByPayerFailsOnIngest() {
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();
                return runBeforeAfter(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(ADMIN).balance(ONE_HUNDRED_HBARS),

                        // Save payer balance before
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),

                        // Create topic with admin key
                        createTopic("testTopic")
                                .blankMemo()
                                .payingWith(ADMIN)
                                .adminKeyName(ADMIN)
                                .signedBy(ADMIN)
                                .fee(ONE_HBAR)
                                .via("create-topic-txn"),

                        // Delete topic not signed by payer
                        deleteTopic("testTopic")
                                .blankMemo()
                                .payingWith(PAYER)
                                .signedBy(ADMIN)
                                .fee(ONE_HBAR)
                                .via("delete-topic-txn")
                                .hasPrecheck(INVALID_SIGNATURE),

                        // assert no txn record is created
                        getTxnRecord("delete-topic-txn").logged().hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND),

                        // Save balances and assert changes
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        withOpContext((spec, log) -> {
                            assertEquals(initialBalance.get(), afterBalance.get());
                        }));
            }

            @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
            @DisplayName("submit message to topic not signed by payer fails on ingest and payer not charged")
            final Stream<DynamicTest> submitMessageToTopicNotSignedByPayerFailsOnIngest() {
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();
                return runBeforeAfter(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(ADMIN).balance(ONE_HUNDRED_HBARS),

                        // Save payer balance before
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),

                        // Create topic with admin key
                        createTopic("testTopic")
                                .blankMemo()
                                .payingWith(ADMIN)
                                .adminKeyName(ADMIN)
                                .signedBy(ADMIN)
                                .fee(ONE_HBAR)
                                .via("create-topic-txn"),

                        // Submit message to topic not signed by payer
                        submitMessageTo("testTopic")
                                .blankMemo()
                                .payingWith(PAYER)
                                .signedBy(ADMIN)
                                .fee(ONE_HBAR)
                                .message("Topic Message")
                                .via("submit-topic-message-txn")
                                .hasPrecheck(INVALID_SIGNATURE),

                        // assert no txn record is created
                        getTxnRecord("submit-topic-message-txn").logged().hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND),

                        // Save balances and assert changes
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        withOpContext((spec, log) -> {
                            assertEquals(initialBalance.get(), afterBalance.get());
                        }));
            }
        }

        // DISABLED: Requires code changes to charge minimal fees for pre-handle validation failures instead of full
        // transaction fees.
        @Disabled("Pre-handle validation failures charge full transaction fee instead of minimal unreadable fee")
        @Nested
        class SimpleFeesEnabledOnlyCreateTopicFailsOnPreHandle {
            @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
            @DisplayName("create topic with insufficient txn fee fails on pre-handle and payer is not charged")
            final Stream<DynamicTest> createTopicInsufficientFeeFailsOnPreHandle() {
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();
                final AtomicLong initialNodeBalance = new AtomicLong();
                final AtomicLong afterNodeBalance = new AtomicLong();

                final String INNER_ID = "create-topic-txn-inner-id";
                final String ENVELOPE_ID = "create-topic-txn-envelope-id";

                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),

                        // Register a TxnId for the inner txn
                        usableTxnIdNamed(INNER_ID).payerId(PAYER),

                        // Save balances before
                        cryptoTransfer(movingHbar(ONE_HBAR).between(GENESIS, "3")),
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                        getAccountBalance("3").exposingBalanceTo(initialNodeBalance::set),
                        withOpContext((spec, log) -> {
                            // build the inner txn
                            final var innerTxn = createTopic("testTopic")
                                    .blankMemo()
                                    .payingWith(PAYER)
                                    .signedBy(PAYER)
                                    .fee(ONE_HBAR / 100000) // fee is too low
                                    .txnId(INNER_ID)
                                    .via(INNER_ID);

                            // create signed bytes
                            final var signed = innerTxn.signedTxnFor(spec);

                            // extract the txn body from the signed txn
                            final var txnBody = extractTransactionBody(signed);

                            // save the txn id and bytes in the registry
                            spec.registry().saveTxnId(INNER_ID, txnBody.getTransactionID());
                            spec.registry().saveBytes(INNER_ID, signed.toByteString());

                            // submit the unchecked wrapping txn
                            final var envelope = uncheckedSubmit(innerTxn).via(ENVELOPE_ID);
                            allRunFor(spec, envelope);

                            final var operation = getTxnRecord(INNER_ID).assertingNothingAboutHashes();
                            allRunFor(spec, operation);

                            final var status =
                                    operation.getResponseRecord().getReceipt().getStatus();
                            assertEquals(INSUFFICIENT_TX_FEE, status, "Expected txn to fail but it succeeded");
                        }),

                        // Save balances after and assert payer was not charged
                        validateChargedUsd(INNER_ID, ucents_to_USD(1.99992)),
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        getAccountBalance("3").exposingBalanceTo(afterNodeBalance::set),
                        withOpContext((spec, log) -> {
                            assertEquals(initialBalance.get(), afterBalance.get());
                            assertTrue(initialNodeBalance.get() > afterNodeBalance.get());
                        }));
            }

            @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
            @DisplayName("create topic not signed by payer fails on pre-handle and payer is not charged")
            final Stream<DynamicTest> createTopicNotSignedByPayerFailsOnPreHandle() {
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();
                final AtomicLong initialNodeBalance = new AtomicLong();
                final AtomicLong afterNodeBalance = new AtomicLong();

                final String INNER_ID = "create-topic-txn-inner-id";
                final String ENVELOPE_ID = "create-topic-txn-envelope-id";

                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(ADMIN).balance(ONE_HUNDRED_HBARS),

                        // Register a TxnId for the inner txn
                        usableTxnIdNamed(INNER_ID).payerId(PAYER),

                        // Save balances before
                        cryptoTransfer(movingHbar(ONE_HBAR).between(GENESIS, "3")),
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                        getAccountBalance("3").exposingBalanceTo(initialNodeBalance::set),
                        withOpContext((spec, log) -> {
                            // build the inner txn
                            final var innerTxn = createTopic("testTopic")
                                    .blankMemo()
                                    .payingWith(PAYER)
                                    .adminKeyName(ADMIN)
                                    .signedBy(ADMIN)
                                    .fee(ONE_HBAR)
                                    .txnId(INNER_ID)
                                    .via(INNER_ID);

                            // create signed bytes
                            final var signed = innerTxn.signedTxnFor(spec);

                            // extract the txn body from the signed txn
                            final var txnBody = extractTransactionBody(signed);

                            // save the txn id and bytes in the registry
                            spec.registry().saveTxnId(INNER_ID, txnBody.getTransactionID());
                            spec.registry().saveBytes(INNER_ID, signed.toByteString());

                            // submit the unchecked wrapping txn
                            final var envelope = uncheckedSubmit(innerTxn).via(ENVELOPE_ID);
                            allRunFor(spec, envelope);

                            final var operation = getTxnRecord(INNER_ID).assertingNothingAboutHashes();
                            allRunFor(spec, operation);

                            final var status =
                                    operation.getResponseRecord().getReceipt().getStatus();
                            assertEquals(INVALID_PAYER_SIGNATURE, status, "Expected txn to fail but it succeeded");
                        }),

                        // Save balances after and assert payer was not charged
                        validateChargedUsd(INNER_ID, ucents_to_USD(1.99992)),
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        getAccountBalance("3").exposingBalanceTo(afterNodeBalance::set),
                        withOpContext((spec, log) -> {
                            assertEquals(initialBalance.get(), afterBalance.get());
                            assertTrue(initialNodeBalance.get() > afterNodeBalance.get());
                        }));
            }

            @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
            @DisplayName("create topic with insufficient payer balance fails on pre-handle and payer is not charged")
            final Stream<DynamicTest> createTopicWithInsufficientPayerBalanceFailsOnPreHandle() {
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();
                final AtomicLong initialNodeBalance = new AtomicLong();
                final AtomicLong afterNodeBalance = new AtomicLong();

                final String INNER_ID = "create-topic-txn-inner-id";
                final String ENVELOPE_ID = "create-topic-txn-envelope-id";

                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HBAR / 100000), // insufficient balance
                        cryptoCreate(ADMIN).balance(ONE_HUNDRED_HBARS),

                        // Register a TxnId for the inner txn
                        usableTxnIdNamed(INNER_ID).payerId(PAYER),

                        // Save balances before
                        cryptoTransfer(movingHbar(ONE_HBAR).between(GENESIS, "3")),
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                        getAccountBalance("3").exposingBalanceTo(initialNodeBalance::set),
                        withOpContext((spec, log) -> {
                            // build the inner txn
                            final var innerTxn = createTopic("testTopic")
                                    .blankMemo()
                                    .payingWith(PAYER)
                                    .adminKeyName(ADMIN)
                                    .signedBy(ADMIN, PAYER)
                                    .fee(ONE_HBAR)
                                    .txnId(INNER_ID)
                                    .via(INNER_ID);

                            // create signed bytes
                            final var signed = innerTxn.signedTxnFor(spec);

                            // extract the txn body from the signed txn
                            final var txnBody = extractTransactionBody(signed);

                            // save the txn id and bytes in the registry
                            spec.registry().saveTxnId(INNER_ID, txnBody.getTransactionID());
                            spec.registry().saveBytes(INNER_ID, signed.toByteString());

                            // submit the unchecked wrapping txn
                            final var envelope = uncheckedSubmit(innerTxn).via(ENVELOPE_ID);
                            allRunFor(spec, envelope);

                            final var operation = getTxnRecord(INNER_ID).assertingNothingAboutHashes();
                            allRunFor(spec, operation);

                            final var status =
                                    operation.getResponseRecord().getReceipt().getStatus();
                            assertEquals(INSUFFICIENT_PAYER_BALANCE, status, "Expected txn to fail but it succeeded");
                        }),

                        // Save balances after and assert payer was not charged
                        validateChargedUsd(INNER_ID, ucents_to_USD(1.99992)),
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        getAccountBalance("3").exposingBalanceTo(afterNodeBalance::set),
                        withOpContext((spec, log) -> {
                            assertEquals(initialBalance.get(), afterBalance.get());
                            assertTrue(initialNodeBalance.get() > afterNodeBalance.get());
                        }));
            }

            @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
            @DisplayName("create topic with admin key not signed by the admin fails on pre-handle and payer is charged")
            final Stream<DynamicTest> createTopicWithAdminKeyNotSignedByAdminFailsOnPreHandlePayerIsCharged() {
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();

                final String INNER_ID = "create-topic-txn-inner-id";
                final String ENVELOPE_ID = "create-topic-txn-envelope-id";

                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(ADMIN).balance(ONE_HUNDRED_HBARS),

                        // Register a TxnId for the inner txn
                        usableTxnIdNamed(INNER_ID).payerId(PAYER),

                        // Save balances before
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                        withOpContext((spec, log) -> {
                            // build the inner txn
                            final var innerTxn = createTopic("testTopic")
                                    .blankMemo()
                                    .payingWith(PAYER)
                                    .adminKeyName(ADMIN)
                                    .signedBy(PAYER)
                                    .fee(ONE_HBAR)
                                    .txnId(INNER_ID)
                                    .via(INNER_ID);

                            // create signed bytes
                            final var signed = innerTxn.signedTxnFor(spec);

                            // extract the txn body from the signed txn
                            final var txnBody = extractTransactionBody(signed);

                            // save the txn id and bytes in the registry
                            spec.registry().saveTxnId(INNER_ID, txnBody.getTransactionID());
                            spec.registry().saveBytes(INNER_ID, signed.toByteString());

                            // submit the unchecked wrapping txn
                            final var envelope = uncheckedSubmit(innerTxn).via(ENVELOPE_ID);
                            allRunFor(spec, envelope);

                            final var operation = getTxnRecord(INNER_ID).assertingNothingAboutHashes();
                            allRunFor(spec, operation);

                            final var status =
                                    operation.getResponseRecord().getReceipt().getStatus();
                            assertEquals(INVALID_SIGNATURE, status, "Expected txn to fail but it succeeded");
                        }),
                        // Save balances after and assert changes
                        validateChargedUsd(INNER_ID, ucents_to_USD(1021)),
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        withOpContext((spec, log) -> {
                            assertTrue(initialBalance.get() > afterBalance.get());
                        }));
            }

            @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
            @DisplayName("create topic with too long memo fails on pre-handle and payer is not charged")
            final Stream<DynamicTest> createTopicWithTooLongMemoFailsOnPreHandlePayerIsNotCharged() {
                final var LONG_MEMO = "x".repeat(1025); // memo exceeds 1024 bytes limit
                final String INNER_ID = "create-topic-txn-inner-id";
                final String ENVELOPE_ID = "create-topic-txn-envelope-id";
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();
                final AtomicLong initialNodeBalance = new AtomicLong();
                final AtomicLong afterNodeBalance = new AtomicLong();
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),

                        // Register a TxnId for the inner txn
                        usableTxnIdNamed(INNER_ID).payerId(PAYER),

                        // Save balances before
                        cryptoTransfer(movingHbar(ONE_HBAR).between(GENESIS, "3")),
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                        getAccountBalance("3").exposingBalanceTo(initialNodeBalance::set),
                        withOpContext((spec, log) -> {
                            // build the inner txn
                            final var innerTxn = createTopic("testTopic")
                                    .memo(LONG_MEMO)
                                    .payingWith(PAYER)
                                    .signedBy(PAYER)
                                    .fee(ONE_HBAR)
                                    .txnId(INNER_ID)
                                    .via(INNER_ID);

                            // create signed bytes
                            final var signed = innerTxn.signedTxnFor(spec);

                            // extract the txn body from the signed txn
                            final var txnBody = extractTransactionBody(signed);

                            // save the txn id and bytes in the registry
                            spec.registry().saveTxnId(INNER_ID, txnBody.getTransactionID());
                            spec.registry().saveBytes(INNER_ID, signed.toByteString());

                            // submit the unchecked wrapping txn
                            final var envelope = uncheckedSubmit(innerTxn).via(ENVELOPE_ID);
                            allRunFor(spec, envelope);

                            final var operation = getTxnRecord(INNER_ID).assertingNothingAboutHashes();
                            allRunFor(spec, operation);

                            final var status =
                                    operation.getResponseRecord().getReceipt().getStatus();
                            assertEquals(MEMO_TOO_LONG, status, "Expected txn to fail but it succeeded");
                        }),
                        validateChargedUsd(INNER_ID, ucents_to_USD(1.99992)),
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        getAccountBalance("3").exposingBalanceTo(afterNodeBalance::set),
                        withOpContext((spec, log) -> {
                            assertEquals(initialBalance.get(), afterBalance.get());
                            assertTrue(initialNodeBalance.get() > afterNodeBalance.get());
                        }));
            }

            @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
            @DisplayName("create topic expired transaction fails on pre-handle and payer is not charged")
            final Stream<DynamicTest> createTopicExpiredFailsOnPreHandlePayerIsNotCharged() {
                final var oneHourPast = -3_600L; // 1 hour before
                final String INNER_ID = "create-topic-txn-inner-id";
                final String ENVELOPE_ID = "create-topic-txn-envelope-id";
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();
                final AtomicLong initialNodeBalance = new AtomicLong();
                final AtomicLong afterNodeBalance = new AtomicLong();

                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),

                        // Register a TxnId for the inner txn
                        usableTxnIdNamed(INNER_ID).modifyValidStart(oneHourPast).payerId(PAYER),

                        // Save balances before
                        cryptoTransfer(movingHbar(ONE_HBAR).between(GENESIS, "3")),
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                        getAccountBalance("3").exposingBalanceTo(initialNodeBalance::set),
                        withOpContext((spec, log) -> {
                            // build the inner txn
                            final var innerTxn = createTopic("testTopic")
                                    .blankMemo()
                                    .payingWith(PAYER)
                                    .signedBy(PAYER)
                                    .fee(ONE_HBAR)
                                    .txnId(INNER_ID)
                                    .via(INNER_ID);

                            // create signed bytes
                            final var signed = innerTxn.signedTxnFor(spec);

                            // extract the txn body from the signed txn
                            final var txnBody = extractTransactionBody(signed);

                            // save the txn id and bytes in the registry
                            spec.registry().saveTxnId(INNER_ID, txnBody.getTransactionID());
                            spec.registry().saveBytes(INNER_ID, signed.toByteString());

                            // submit the unchecked wrapping txn
                            final var envelope = uncheckedSubmit(innerTxn).via(ENVELOPE_ID);
                            allRunFor(spec, envelope);

                            final var operation = getTxnRecord(INNER_ID).assertingNothingAboutHashes();
                            allRunFor(spec, operation);

                            final var status =
                                    operation.getResponseRecord().getReceipt().getStatus();
                            assertEquals(TRANSACTION_EXPIRED, status, "Expected txn to fail but it succeeded");
                        }),
                        validateChargedUsd(INNER_ID, ucents_to_USD(1.99992)),
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        getAccountBalance("3").exposingBalanceTo(afterNodeBalance::set),
                        withOpContext((spec, log) -> {
                            assertEquals(initialBalance.get(), afterBalance.get());
                            assertTrue(initialNodeBalance.get() > afterNodeBalance.get());
                        }));
            }

            @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
            @DisplayName("create topic with too far start time fails on pre-handle and payer is not charged")
            final Stream<DynamicTest> createTopicTooFarStartTimeFailsOnPreHandlePayerIsNotCharged() {
                final var oneHourFuture = 3_600L; // 1 hour before
                final String INNER_ID = "create-topic-txn-inner-id";
                final String ENVELOPE_ID = "create-topic-txn-envelope-id";
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();
                final AtomicLong initialNodeBalance = new AtomicLong();
                final AtomicLong afterNodeBalance = new AtomicLong();

                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),

                        // Register a TxnId for the inner txn
                        usableTxnIdNamed(INNER_ID)
                                .modifyValidStart(oneHourFuture)
                                .payerId(PAYER),

                        // Save balances before
                        cryptoTransfer(movingHbar(ONE_HBAR).between(GENESIS, "3")),
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                        getAccountBalance("3").exposingBalanceTo(initialNodeBalance::set),
                        withOpContext((spec, log) -> {
                            // build the inner txn
                            final var innerTxn = createTopic("testTopic")
                                    .blankMemo()
                                    .payingWith(PAYER)
                                    .signedBy(PAYER)
                                    .fee(ONE_HBAR)
                                    .txnId(INNER_ID)
                                    .via(INNER_ID);

                            // create signed bytes
                            final var signed = innerTxn.signedTxnFor(spec);

                            // extract the txn body from the signed txn
                            final var txnBody = extractTransactionBody(signed);

                            // save the txn id and bytes in the registry
                            spec.registry().saveTxnId(INNER_ID, txnBody.getTransactionID());
                            spec.registry().saveBytes(INNER_ID, signed.toByteString());

                            // submit the unchecked wrapping txn
                            final var envelope = uncheckedSubmit(innerTxn).via(ENVELOPE_ID);
                            allRunFor(spec, envelope);

                            final var operation = getTxnRecord(INNER_ID).assertingNothingAboutHashes();
                            allRunFor(spec, operation);

                            final var status =
                                    operation.getResponseRecord().getReceipt().getStatus();
                            assertEquals(INVALID_TRANSACTION_START, status, "Expected txn to fail but it succeeded");
                        }),

                        // Save balances after and assert payer was not charged
                        validateChargedUsd(INNER_ID, ucents_to_USD(1.99992)),
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        getAccountBalance("3").exposingBalanceTo(afterNodeBalance::set),
                        withOpContext((spec, log) -> {
                            assertEquals(initialBalance.get(), afterBalance.get());
                            assertTrue(initialNodeBalance.get() > afterNodeBalance.get());
                        }));
            }

            @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
            @DisplayName("create topic with invalid duration time fails on pre-handle and payer is not charged")
            final Stream<DynamicTest> createTopicInvalidDurationTimeFailsOnPreHandlePayerIsNotCharged() {
                final String INNER_ID = "create-topic-txn-inner-id";
                final String ENVELOPE_ID = "create-topic-txn-envelope-id";
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();
                final AtomicLong initialNodeBalance = new AtomicLong();
                final AtomicLong afterNodeBalance = new AtomicLong();

                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),

                        // Register a TxnId for the inner txn
                        usableTxnIdNamed(INNER_ID).payerId(PAYER),

                        // Save balances before
                        cryptoTransfer(movingHbar(ONE_HBAR).between(GENESIS, "3")),
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                        getAccountBalance("3").exposingBalanceTo(initialNodeBalance::set),
                        withOpContext((spec, log) -> {
                            // build the inner txn
                            final var innerTxn = createTopic("testTopic")
                                    .blankMemo()
                                    .payingWith(PAYER)
                                    .signedBy(PAYER)
                                    .fee(ONE_HBAR)
                                    .validDurationSecs(0) // invalid duration
                                    .txnId(INNER_ID)
                                    .via(INNER_ID);

                            // create signed bytes
                            final var signed = innerTxn.signedTxnFor(spec);

                            // extract the txn body from the signed txn
                            final var txnBody = extractTransactionBody(signed);

                            // save the txn id and bytes in the registry
                            spec.registry().saveTxnId(INNER_ID, txnBody.getTransactionID());
                            spec.registry().saveBytes(INNER_ID, signed.toByteString());

                            // submit the unchecked wrapping txn
                            final var envelope = uncheckedSubmit(innerTxn).via(ENVELOPE_ID);
                            allRunFor(spec, envelope);

                            final var operation = getTxnRecord(INNER_ID).assertingNothingAboutHashes();
                            allRunFor(spec, operation);

                            final var status =
                                    operation.getResponseRecord().getReceipt().getStatus();
                            assertEquals(INVALID_TRANSACTION_DURATION, status, "Expected txn to fail but it succeeded");
                        }),

                        // Save balances after and assert payer was not charged
                        validateChargedUsd(INNER_ID, ucents_to_USD(1.99992)),
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        withOpContext((spec, log) -> {
                            assertEquals(initialBalance.get(), afterBalance.get());
                            assertTrue(initialNodeBalance.get() > afterNodeBalance.get());
                        }));
            }

            @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
            @DisplayName("create topic duplicate txn fails on pre-handle and payer is not charged")
            final Stream<DynamicTest> createTopicDuplicateTxnFailsOnPreHandlePayerIsNotCharged() {
                final String INNER_ID = "create-topic-txn-inner-id";
                final String ENVELOPE_ID = "create-topic-txn-envelope-id";
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();

                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),

                        // Register a TxnId for the inner txn
                        usableTxnIdNamed(INNER_ID).payerId(PAYER),

                        // Save balances before
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                        withOpContext((spec, log) -> {
                            // build the inner txn
                            final var innerTxn = createTopic("testTopic")
                                    .blankMemo()
                                    .payingWith(PAYER)
                                    .signedBy(PAYER)
                                    .fee(ONE_HBAR)
                                    .txnId(INNER_ID)
                                    .via(INNER_ID);

                            // create signed bytes
                            final var signed = innerTxn.signedTxnFor(spec);

                            // extract the txn body from the signed txn
                            final var txnBody = extractTransactionBody(signed);

                            // save the txn id and bytes in the registry
                            spec.registry().saveTxnId(INNER_ID, txnBody.getTransactionID());
                            spec.registry().saveBytes(INNER_ID, signed.toByteString());

                            // submit the unchecked wrapping txn
                            final var envelope = uncheckedSubmit(innerTxn).via(ENVELOPE_ID);
                            final var envelopeDuplicate =
                                    uncheckedSubmit(innerTxn).via(ENVELOPE_ID);
                            allRunFor(spec, envelope, envelopeDuplicate);

                            final var operation = getTxnRecord(INNER_ID).assertingNothingAboutHashes();
                            allRunFor(spec, operation);

                            // assert original txn succeeded
                            final var status =
                                    operation.getResponseRecord().getReceipt().getStatus();
                            assertEquals(SUCCESS, status, "Expected txn to fail but it succeeded");
                        }),

                        // assert duplicate txn record is created and original txn charged
                        getReceipt(INNER_ID)
                                .andAnyDuplicates()
                                .logged()
                                .hasPriorityStatus(SUCCESS)
                                .hasDuplicateStatuses(DUPLICATE_TRANSACTION),
                        validateChargedUsd(INNER_ID, ucents_to_USD(1003)),
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        withOpContext((spec, log) -> {
                            assertTrue(initialBalance.get() > afterBalance.get());
                        }));
            }

            @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
            @DisplayName("update topic not signed by payer fails on pre-handle and payer not charged")
            final Stream<DynamicTest> updateTopicNotSignedByPayerFailsOnPreHandle() {
                final String INNER_ID = "create-topic-txn-inner-id";
                final String ENVELOPE_ID = "create-topic-txn-envelope-id";
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();
                final AtomicLong initialNodeBalance = new AtomicLong();
                final AtomicLong afterNodeBalance = new AtomicLong();

                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(ADMIN).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(NEW_ADMIN).balance(ONE_HUNDRED_HBARS),

                        // Register a TxnId for the inner txn
                        usableTxnIdNamed(INNER_ID).payerId(PAYER),

                        // Save balance before
                        cryptoTransfer(movingHbar(ONE_HBAR).between(GENESIS, "3")),
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                        getAccountBalance("3").exposingBalanceTo(initialNodeBalance::set),

                        // Create topic with admin key
                        createTopic("testTopic")
                                .blankMemo()
                                .payingWith(ADMIN)
                                .adminKeyName(ADMIN)
                                .signedBy(ADMIN)
                                .fee(ONE_HBAR)
                                .via("create-topic-txn"),
                        withOpContext((spec, log) -> {
                            // build the inner txn
                            final var innerTxn = updateTopic("testTopic")
                                    .blankMemo()
                                    .adminKey(NEW_ADMIN)
                                    .payingWith(PAYER)
                                    .signedBy(ADMIN, NEW_ADMIN)
                                    .fee(ONE_HBAR)
                                    .txnId(INNER_ID)
                                    .via(INNER_ID);

                            // create signed bytes
                            final var signed = innerTxn.signedTxnFor(spec);

                            // extract the txn body from the signed txn
                            final var txnBody = extractTransactionBody(signed);

                            // save the txn id and bytes in the registry
                            spec.registry().saveTxnId(INNER_ID, txnBody.getTransactionID());
                            spec.registry().saveBytes(INNER_ID, signed.toByteString());

                            // submit the unchecked wrapping txn
                            final var envelope = uncheckedSubmit(innerTxn).via(ENVELOPE_ID);
                            allRunFor(spec, envelope);

                            final var operation = getTxnRecord(INNER_ID).assertingNothingAboutHashes();
                            allRunFor(spec, operation);

                            final var status =
                                    operation.getResponseRecord().getReceipt().getStatus();
                            assertEquals(INVALID_PAYER_SIGNATURE, status, "Expected txn to fail but it succeeded");
                        }),

                        // Save payer balance after and assert payer was not charged
                        validateChargedUsd(INNER_ID, ucents_to_USD(1.99992)),
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        getAccountBalance("3").exposingBalanceTo(afterNodeBalance::set),
                        withOpContext((spec, log) -> {
                            assertEquals(initialBalance.get(), afterBalance.get());
                            assertTrue(initialNodeBalance.get() > afterNodeBalance.get());
                        }));
            }

            @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
            @DisplayName("delete topic not signed by payer fails on pre-handle and payer not charged")
            final Stream<DynamicTest> deleteTopicNotSignedByPayerFailsOnPreHandle() {
                final String INNER_ID = "create-topic-txn-inner-id";
                final String ENVELOPE_ID = "create-topic-txn-envelope-id";
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();
                final AtomicLong initialNodeBalance = new AtomicLong();
                final AtomicLong afterNodeBalance = new AtomicLong();

                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(ADMIN).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(NEW_ADMIN).balance(ONE_HUNDRED_HBARS),

                        // Register a TxnId for the inner txn
                        usableTxnIdNamed(INNER_ID).payerId(PAYER),

                        // Save balances before
                        cryptoTransfer(movingHbar(ONE_HBAR).between(GENESIS, "3")),
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                        getAccountBalance("3").exposingBalanceTo(initialNodeBalance::set),
                        // Create topic with admin key
                        createTopic("testTopic")
                                .blankMemo()
                                .payingWith(ADMIN)
                                .adminKeyName(ADMIN)
                                .signedBy(ADMIN)
                                .fee(ONE_HBAR)
                                .via("create-topic-txn"),
                        withOpContext((spec, log) -> {
                            // build the inner txn
                            final var innerTxn = deleteTopic("testTopic")
                                    .payingWith(PAYER)
                                    .signedBy(ADMIN)
                                    .fee(ONE_HBAR)
                                    .txnId(INNER_ID)
                                    .via(INNER_ID);

                            // create signed bytes
                            final var signed = innerTxn.signedTxnFor(spec);

                            // extract the txn body from the signed txn
                            final var txnBody = extractTransactionBody(signed);

                            // save the txn id and bytes in the registry
                            spec.registry().saveTxnId(INNER_ID, txnBody.getTransactionID());
                            spec.registry().saveBytes(INNER_ID, signed.toByteString());

                            // submit the unchecked wrapping txn
                            final var envelope = uncheckedSubmit(innerTxn).via(ENVELOPE_ID);
                            allRunFor(spec, envelope);

                            final var operation = getTxnRecord(INNER_ID).assertingNothingAboutHashes();
                            allRunFor(spec, operation);

                            final var status =
                                    operation.getResponseRecord().getReceipt().getStatus();
                            assertEquals(INVALID_PAYER_SIGNATURE, status, "Expected txn to fail but it succeeded");
                        }),

                        // Save payer balance after and assert payer was not charged
                        validateChargedUsd(INNER_ID, ucents_to_USD(1.99992)),
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        getAccountBalance("3").exposingBalanceTo(afterNodeBalance::set),
                        withOpContext((spec, log) -> {
                            assertEquals(initialBalance.get(), afterBalance.get());
                            assertTrue(initialNodeBalance.get() > afterNodeBalance.get());
                        }));
            }

            @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
            @DisplayName("submit message to topic not signed by payer fails on pre-handle and payer not charged")
            final Stream<DynamicTest> submitMessageToTopicNotSignedByPayerFailsOnPreHandle() {
                final String INNER_ID = "create-topic-txn-inner-id";
                final String ENVELOPE_ID = "create-topic-txn-envelope-id";
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();
                final AtomicLong initialNodeBalance = new AtomicLong();
                final AtomicLong afterNodeBalance = new AtomicLong();

                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(ADMIN).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(NEW_ADMIN).balance(ONE_HUNDRED_HBARS),

                        // Register a TxnId for the inner txn
                        usableTxnIdNamed(INNER_ID).payerId(PAYER),

                        // Save balances before
                        cryptoTransfer(movingHbar(ONE_HBAR).between(GENESIS, "3")),
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                        getAccountBalance("3").exposingBalanceTo(initialNodeBalance::set),
                        // Create topic with admin key
                        createTopic("testTopic")
                                .blankMemo()
                                .payingWith(ADMIN)
                                .adminKeyName(ADMIN)
                                .signedBy(ADMIN)
                                .fee(ONE_HBAR)
                                .via("create-topic-txn"),
                        withOpContext((spec, log) -> {
                            // build the inner txn
                            final var innerTxn = submitMessageTo("testTopic")
                                    .payingWith(PAYER)
                                    .signedBy(ADMIN)
                                    .fee(ONE_HBAR)
                                    .txnId(INNER_ID)
                                    .via(INNER_ID);

                            // create signed bytes
                            final var signed = innerTxn.signedTxnFor(spec);

                            // extract the txn body from the signed txn
                            final var txnBody = extractTransactionBody(signed);

                            // save the txn id and bytes in the registry
                            spec.registry().saveTxnId(INNER_ID, txnBody.getTransactionID());
                            spec.registry().saveBytes(INNER_ID, signed.toByteString());

                            // submit the unchecked wrapping txn
                            final var envelope = uncheckedSubmit(innerTxn).via(ENVELOPE_ID);
                            allRunFor(spec, envelope);

                            final var operation = getTxnRecord(INNER_ID).assertingNothingAboutHashes();
                            allRunFor(spec, operation);

                            final var status =
                                    operation.getResponseRecord().getReceipt().getStatus();
                            assertEquals(INVALID_PAYER_SIGNATURE, status, "Expected txn to fail but it succeeded");
                        }),

                        // Save payer balance after and assert payer was not charged
                        validateChargedUsd(INNER_ID, ucents_to_USD(1.99992)),
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        getAccountBalance("3").exposingBalanceTo(afterNodeBalance::set),
                        withOpContext((spec, log) -> {
                            assertEquals(initialBalance.get(), afterBalance.get());
                            assertTrue(initialNodeBalance.get() > afterNodeBalance.get());
                        }));
            }
        }
    }
}
