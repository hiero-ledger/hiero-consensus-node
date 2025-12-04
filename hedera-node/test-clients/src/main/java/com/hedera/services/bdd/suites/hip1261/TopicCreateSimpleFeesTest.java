// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip1261;

import static com.hedera.services.bdd.junit.EmbeddedReason.MUST_SKIP_INGEST;
import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.junit.TestTags.SIMPLE_FEES;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.createTopic;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingHbar;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.usableTxnIdNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTopicCreateFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTopicCreateNetworkFeeOnlyUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.validateChargedFeeToUsd;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.DUPLICATE_TRANSACTION;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_PAYER_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_TX_FEE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_PAYER_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TRANSACTION_DURATION;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TRANSACTION_START;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.MEMO_TOO_LONG;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.RECORD_NOT_FOUND;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TRANSACTION_EXPIRED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyEmbeddedHapiTest;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;

@Tag(MATS)
@Tag(SIMPLE_FEES)
@HapiTestLifecycle
public class TopicCreateSimpleFeesTest {
    private static final String PAYER = "payer";
    private static final String ADMIN = "admin";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(Map.of("fees.simpleFeesEnabled", "true"));
    }

    @Nested
    class CreateTopicSimpleFeesNegativeCases {

        @Nested
        class CreateTopicSimpleFeesPositiveTests {}

        @Nested
        class CreateTopicSimpleFeesFailuresOnIngest {
            @HapiTest
            @DisplayName("create topic with insufficient txn fee fails on ingest and payer not charged")
            final Stream<DynamicTest> createTopicInsufficientFeeFailsOnIngest() {
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();
                return hapiTest(
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

            @HapiTest
            @DisplayName("create topic not signed by payer fails on ingest and payer not charged")
            final Stream<DynamicTest> createTopicNotSignedByPayerFailsOnIngest() {
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();
                return hapiTest(
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

            @HapiTest
            @DisplayName("create topic with insufficient payer balance fails on ingest and payer not charged")
            final Stream<DynamicTest> createTopicWithInsufficientPayerBalanceFailsOnIngest() {
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();
                return hapiTest(
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

            @HapiTest
            @DisplayName("create topic with too long memo fails on ingest and payer not charged")
            final Stream<DynamicTest> createTopicTooLongMemoFailsOnIngest() {
                final var LONG_MEMO = "x".repeat(1025); // memo exceeds 1024 bytes limit
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();
                return hapiTest(
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

            @HapiTest
            @DisplayName("create topic expired transaction fails on ingest and payer not charged")
            final Stream<DynamicTest> createTopicExpiredFailsOnIngest() {
                final var expiredTxnId = "expiredCreateTopic";
                final var oneHourPast = -3_600L; // 1 hour before
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();
                return hapiTest(
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

            @HapiTest
            @DisplayName("create topic with too far start time fails on ingest and payer not charged")
            final Stream<DynamicTest> createTopicTooFarStartTimeFailsOnIngest() {
                final var futureTxnId = "futureCreateTopic";
                final var oneHourFuture = 3_600L; // 1 hour after
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();
                return hapiTest(
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

            @HapiTest
            @DisplayName("create topic with invalid duration time fails on ingest and payer not charged")
            final Stream<DynamicTest> createTopicInvalidDurationTimeFailsOnIngest() {
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();
                return hapiTest(
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

            @HapiTest
            @DisplayName("create topic duplicate txn fails on ingest and payer not charged")
            final Stream<DynamicTest> createTopicDuplicateTxnFailsOnIngest() {
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();
                return hapiTest(
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
        }

        @Nested
        class CreateTopicSimpleFeesFailuresOnPreHandle {
            @LeakyEmbeddedHapiTest(reason = MUST_SKIP_INGEST)
            @DisplayName("create topic with insufficient txn fee fails on pre-handle and payer is not charged")
            final Stream<DynamicTest> createTopicInsufficientFeeFailsOnPreHandle() {
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();
                final AtomicLong initialNodeBalance = new AtomicLong();
                final AtomicLong afterNodeBalance = new AtomicLong();

                final String INNER_ID = "create-topic-txn-inner-id";

                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),

                        // Register a TxnId for the inner txn
                        usableTxnIdNamed(INNER_ID).payerId(PAYER),

                        // Save balances before
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                        cryptoTransfer(movingHbar(ONE_HBAR).between(GENESIS, "4")),
                        getAccountBalance("4").exposingBalanceTo(initialNodeBalance::set),
                        createTopic("testTopic")
                                .blankMemo()
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .fee(ONE_HBAR / 100000) // fee is too low
                                .setNode(4)
                                .via(INNER_ID)
                                .hasKnownStatus(INSUFFICIENT_TX_FEE),

                        // Save balances after and assert payer was not charged
                        getTxnRecord(INNER_ID).assertingNothingAboutHashes().logged(),
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        getAccountBalance("4").exposingBalanceTo(afterNodeBalance::set),
                        withOpContext((spec, log) -> {
                            long nodeDelta = initialNodeBalance.get() - afterNodeBalance.get();
                            log.info("Node balance change: {}", nodeDelta);
                            log.info("Recorded fee: {}", expectedTopicCreateNetworkFeeOnlyUsd(1));
                            assertEquals(initialBalance.get(), afterBalance.get());
                            assertTrue(initialNodeBalance.get() > afterNodeBalance.get());
                        }),
                        validateChargedFeeToUsd(
                                INNER_ID,
                                initialNodeBalance,
                                afterNodeBalance,
                                expectedTopicCreateNetworkFeeOnlyUsd(1),
                                0.01));
            }

            @LeakyEmbeddedHapiTest(reason = MUST_SKIP_INGEST)
            @DisplayName("create topic not signed by payer fails on pre-handle and payer is not charged")
            final Stream<DynamicTest> createTopicNotSignedByPayerFailsOnPreHandle() {
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();
                final AtomicLong initialNodeBalance = new AtomicLong();
                final AtomicLong afterNodeBalance = new AtomicLong();

                final String INNER_ID = "create-topic-txn-inner-id";

                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(ADMIN).balance(ONE_HUNDRED_HBARS),

                        // Register a TxnId for the inner txn
                        usableTxnIdNamed(INNER_ID).payerId(PAYER),

                        // Save balances before
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                        cryptoTransfer(movingHbar(ONE_HBAR).between(GENESIS, "4")),
                        getAccountBalance("4").exposingBalanceTo(initialNodeBalance::set),
                        createTopic("testTopic")
                                .blankMemo()
                                .payingWith(PAYER)
                                .adminKeyName(ADMIN)
                                .signedBy(ADMIN)
                                .fee(ONE_HBAR)
                                .setNode(4)
                                .via(INNER_ID)
                                .hasKnownStatus(INVALID_PAYER_SIGNATURE),

                        // Save balances after and assert payer was not charged
                        getTxnRecord(INNER_ID).assertingNothingAboutHashes().logged(),
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        getAccountBalance("4").exposingBalanceTo(afterNodeBalance::set),
                        withOpContext((spec, log) -> {
                            long nodeDelta = initialNodeBalance.get() - afterNodeBalance.get();
                            log.info("Node balance change: {}", nodeDelta);
                            log.info("Recorded fee: {}", expectedTopicCreateNetworkFeeOnlyUsd(1));
                            assertEquals(initialBalance.get(), afterBalance.get());
                            assertTrue(initialNodeBalance.get() > afterNodeBalance.get());
                        }),
                        validateChargedFeeToUsd(
                                INNER_ID,
                                initialNodeBalance,
                                afterNodeBalance,
                                expectedTopicCreateNetworkFeeOnlyUsd(1),
                                0.01));
            }

            @LeakyEmbeddedHapiTest(reason = MUST_SKIP_INGEST)
            @DisplayName("create topic with insufficient payer balance fails on pre-handle and payer is not charged")
            final Stream<DynamicTest> createTopicWithInsufficientPayerBalanceFailsOnPreHandle() {
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();
                final AtomicLong initialNodeBalance = new AtomicLong();
                final AtomicLong afterNodeBalance = new AtomicLong();

                final String INNER_ID = "create-topic-txn-inner-id";

                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HBAR / 100000), // insufficient balance
                        cryptoCreate(ADMIN).balance(ONE_HUNDRED_HBARS),

                        // Register a TxnId for the inner txn
                        usableTxnIdNamed(INNER_ID).payerId(PAYER),

                        // Save balances before
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                        cryptoTransfer(movingHbar(ONE_HBAR).between(GENESIS, "4")),
                        getAccountBalance("4").exposingBalanceTo(initialNodeBalance::set),
                        createTopic("testTopic")
                                .blankMemo()
                                .payingWith(PAYER)
                                .adminKeyName(ADMIN)
                                .signedBy(ADMIN, PAYER)
                                .fee(ONE_HBAR)
                                .setNode(4)
                                .via(INNER_ID)
                                .hasKnownStatus(INSUFFICIENT_PAYER_BALANCE),

                        // Save balances after and assert payer was not charged
                        getTxnRecord(INNER_ID).assertingNothingAboutHashes().logged(),
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        getAccountBalance("4").exposingBalanceTo(afterNodeBalance::set),
                        withOpContext((spec, log) -> {
                            long nodeDelta = initialNodeBalance.get() - afterNodeBalance.get();
                            log.info("Node balance change: {}", nodeDelta);
                            log.info("Recorded fee: {}", expectedTopicCreateNetworkFeeOnlyUsd(2));
                            assertEquals(initialBalance.get(), afterBalance.get());
                            assertTrue(initialNodeBalance.get() > afterNodeBalance.get());
                        }),
                        validateChargedFeeToUsd(
                                INNER_ID,
                                initialNodeBalance,
                                afterNodeBalance,
                                expectedTopicCreateNetworkFeeOnlyUsd(2),
                                0.01));
            }

            @LeakyEmbeddedHapiTest(reason = MUST_SKIP_INGEST)
            @DisplayName("create topic with admin key not signed by the admin fails on pre-handle and payer is charged")
            final Stream<DynamicTest> createTopicWithAdminKeyNotSignedByAdminFailsOnPreHandlePayerIsCharged() {
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();
                final AtomicLong initialNodeBalance = new AtomicLong();
                final AtomicLong afterNodeBalance = new AtomicLong();

                final String INNER_ID = "create-topic-txn-inner-id";

                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(ADMIN).balance(ONE_HUNDRED_HBARS),

                        // Register a TxnId for the inner txn
                        usableTxnIdNamed(INNER_ID).payerId(PAYER),

                        // Save balances before
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                        cryptoTransfer(movingHbar(ONE_HBAR).between(GENESIS, "4")),
                        getAccountBalance("4").exposingBalanceTo(initialNodeBalance::set),
                        createTopic("testTopic")
                                .blankMemo()
                                .payingWith(PAYER)
                                .adminKeyName(ADMIN)
                                .signedBy(PAYER)
                                .fee(ONE_HBAR)
                                .setNode(4)
                                .via(INNER_ID)
                                .hasKnownStatus(INVALID_SIGNATURE),

                        // Save balances after and assert changes
                        getTxnRecord(INNER_ID).assertingNothingAboutHashes().logged(),
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        getAccountBalance("4").exposingBalanceTo(afterNodeBalance::set),
                        withOpContext((spec, log) -> {
                            assertTrue(initialBalance.get() > afterBalance.get());
                            long payerDelta = initialBalance.get() - afterBalance.get();
                            log.info("Payer balance change: {}", payerDelta);
                            log.info("Recorded fee: {}", expectedTopicCreateFullFeeUsd(1, 1));
                        }),
                        validateChargedFeeToUsd(
                                INNER_ID, initialBalance, afterBalance, expectedTopicCreateFullFeeUsd(1, 1), 0.01));
            }

            @LeakyEmbeddedHapiTest(reason = MUST_SKIP_INGEST)
            @DisplayName("create topic with too long memo fails on pre-handle and payer is not charged")
            final Stream<DynamicTest> createTopicWithTooLongMemoFailsOnPreHandlePayerIsNotCharged() {
                final var LONG_MEMO = "x".repeat(1025); // memo exceeds 1024 bytes limit
                final String INNER_ID = "create-topic-txn-inner-id";
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();
                final AtomicLong initialNodeBalance = new AtomicLong();
                final AtomicLong afterNodeBalance = new AtomicLong();
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),

                        // Register a TxnId for the inner txn
                        usableTxnIdNamed(INNER_ID).payerId(PAYER),

                        // Save balances before
                        cryptoTransfer(movingHbar(ONE_HBAR).between(GENESIS, "4")),
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                        getAccountBalance("4").exposingBalanceTo(initialNodeBalance::set),
                        createTopic("testTopic")
                                .memo(LONG_MEMO)
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .fee(ONE_HBAR)
                                .setNode(4)
                                .via(INNER_ID)
                                .hasKnownStatus(MEMO_TOO_LONG),

                        // Save balances after and assert changes
                        getTxnRecord(INNER_ID).assertingNothingAboutHashes().logged(),
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        getAccountBalance("4").exposingBalanceTo(afterNodeBalance::set),
                        withOpContext((spec, log) -> {
                            long nodeDelta = initialNodeBalance.get() - afterNodeBalance.get();
                            log.info("Node balance change: {}", nodeDelta);
                            log.info("Recorded fee: {}", expectedTopicCreateNetworkFeeOnlyUsd(1));
                            assertEquals(initialBalance.get(), afterBalance.get());
                            assertTrue(initialNodeBalance.get() > afterNodeBalance.get());
                        }),
                        validateChargedFeeToUsd(
                                INNER_ID,
                                initialNodeBalance,
                                afterNodeBalance,
                                expectedTopicCreateNetworkFeeOnlyUsd(1),
                                0.01));
            }

            @LeakyEmbeddedHapiTest(reason = MUST_SKIP_INGEST)
            @DisplayName("create topic expired transaction fails on pre-handle and payer is not charged")
            final Stream<DynamicTest> createTopicExpiredFailsOnPreHandlePayerIsNotCharged() {
                final var oneHourPast = -3_600L; // 1 hour before
                final String INNER_ID = "create-topic-txn-inner-id";
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();
                final AtomicLong initialNodeBalance = new AtomicLong();
                final AtomicLong afterNodeBalance = new AtomicLong();

                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),

                        // Register a TxnId for the inner txn
                        usableTxnIdNamed(INNER_ID).modifyValidStart(oneHourPast).payerId(PAYER),

                        // Save balances before
                        cryptoTransfer(movingHbar(ONE_HBAR).between(GENESIS, "4")),
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                        getAccountBalance("4").exposingBalanceTo(initialNodeBalance::set),
                        createTopic("testTopic")
                                .blankMemo()
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .fee(ONE_HBAR)
                                .setNode(4)
                                .txnId(INNER_ID)
                                .via(INNER_ID)
                                .hasKnownStatus(TRANSACTION_EXPIRED),

                        // Save balances after and assert changes
                        getTxnRecord(INNER_ID).assertingNothingAboutHashes().logged(),
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        getAccountBalance("4").exposingBalanceTo(afterNodeBalance::set),
                        withOpContext((spec, log) -> {
                            long nodeDelta = initialNodeBalance.get() - afterNodeBalance.get();
                            log.info("Node balance change: {}", nodeDelta);
                            log.info("Recorded fee: {}", expectedTopicCreateNetworkFeeOnlyUsd(1));
                            assertEquals(initialBalance.get(), afterBalance.get());
                            assertTrue(initialNodeBalance.get() > afterNodeBalance.get());
                        }),
                        validateChargedFeeToUsd(
                                INNER_ID,
                                initialNodeBalance,
                                afterNodeBalance,
                                expectedTopicCreateNetworkFeeOnlyUsd(1),
                                0.01));
            }

            @LeakyEmbeddedHapiTest(reason = MUST_SKIP_INGEST)
            @DisplayName("create topic with too far start time fails on pre-handle and payer is not charged")
            final Stream<DynamicTest> createTopicTooFarStartTimeFailsOnPreHandlePayerIsNotCharged() {
                final var oneHourFuture = 3_600L; // 1 hour after
                final String INNER_ID = "create-topic-txn-inner-id";
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
                        cryptoTransfer(movingHbar(ONE_HBAR).between(GENESIS, "4")),
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                        getAccountBalance("4").exposingBalanceTo(initialNodeBalance::set),
                        createTopic("testTopic")
                                .blankMemo()
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .fee(ONE_HBAR)
                                .setNode(4)
                                .txnId(INNER_ID)
                                .via(INNER_ID)
                                .hasKnownStatus(INVALID_TRANSACTION_START),

                        // Save balances after and assert payer was not charged
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        getAccountBalance("4").exposingBalanceTo(afterNodeBalance::set),
                        withOpContext((spec, log) -> {
                            long nodeDelta = initialNodeBalance.get() - afterNodeBalance.get();
                            log.info("Node balance change: {}", nodeDelta);
                            log.info("Recorded fee: {}", expectedTopicCreateNetworkFeeOnlyUsd(1));
                            assertEquals(initialBalance.get(), afterBalance.get());
                            assertTrue(initialNodeBalance.get() > afterNodeBalance.get());
                        }),
                        validateChargedFeeToUsd(
                                INNER_ID,
                                initialNodeBalance,
                                afterNodeBalance,
                                expectedTopicCreateNetworkFeeOnlyUsd(1),
                                0.01));
            }

            @LeakyEmbeddedHapiTest(reason = MUST_SKIP_INGEST)
            @DisplayName("create topic with invalid duration time fails on pre-handle and payer is not charged")
            final Stream<DynamicTest> createTopicInvalidDurationTimeFailsOnPreHandlePayerIsNotCharged() {
                final String INNER_ID = "create-topic-txn-inner-id";
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();
                final AtomicLong initialNodeBalance = new AtomicLong();
                final AtomicLong afterNodeBalance = new AtomicLong();

                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),

                        // Register a TxnId for the inner txn
                        usableTxnIdNamed(INNER_ID).payerId(PAYER),

                        // Save balances before
                        cryptoTransfer(movingHbar(ONE_HBAR).between(GENESIS, "4")),
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                        getAccountBalance("4").exposingBalanceTo(initialNodeBalance::set),
                        createTopic("testTopic")
                                .blankMemo()
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .fee(ONE_HBAR)
                                .validDurationSecs(0) // invalid duration
                                .setNode(4)
                                .txnId(INNER_ID)
                                .via(INNER_ID)
                                .hasKnownStatus(INVALID_TRANSACTION_DURATION),

                        // Save balances after and assert payer was not charged
                        getTxnRecord(INNER_ID).assertingNothingAboutHashes().logged(),
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        getAccountBalance("4").exposingBalanceTo(afterNodeBalance::set),
                        withOpContext((spec, log) -> {
                            long nodeDelta = initialNodeBalance.get() - afterNodeBalance.get();
                            log.info("Node balance change: {}", nodeDelta);
                            log.info("Recorded fee: {}", expectedTopicCreateNetworkFeeOnlyUsd(1));
                            assertEquals(initialBalance.get(), afterBalance.get());
                            assertTrue(initialNodeBalance.get() > afterNodeBalance.get());
                        }),
                        validateChargedFeeToUsd(
                                INNER_ID,
                                initialNodeBalance,
                                afterNodeBalance,
                                expectedTopicCreateNetworkFeeOnlyUsd(1),
                                0.01));
            }
        }
    }
}
