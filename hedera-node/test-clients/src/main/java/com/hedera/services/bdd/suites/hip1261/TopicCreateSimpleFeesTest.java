// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip1261;

import static com.hedera.services.bdd.junit.EmbeddedReason.MUST_SKIP_INGEST;
import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.junit.TestTags.ONLY_SUBPROCESS;
import static com.hedera.services.bdd.junit.TestTags.SIMPLE_FEES;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.keys.ControlForKey.forKey;
import static com.hedera.services.bdd.spec.keys.KeyShape.SIMPLE;
import static com.hedera.services.bdd.spec.keys.KeyShape.listOf;
import static com.hedera.services.bdd.spec.keys.KeyShape.sigs;
import static com.hedera.services.bdd.spec.keys.KeyShape.threshOf;
import static com.hedera.services.bdd.spec.keys.SigControl.OFF;
import static com.hedera.services.bdd.spec.keys.SigControl.ON;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.createTopic;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingHbar;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyListNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.usableTxnIdNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTopicCreateFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTopicCreateNetworkFeeOnlyUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.validateChargedFeeToUsdWithTxnSize;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.validateChargedUsdWithinWithTxnSize;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.BAD_ENCODING;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.DUPLICATE_TRANSACTION;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_PAYER_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_TX_FEE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_AUTORENEW_ACCOUNT;
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
import com.hedera.services.bdd.spec.keys.KeyShape;
import com.hedera.services.bdd.spec.keys.SigControl;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
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
    private static final String AUTO_RENEW_ACCOUNT = "autoRenewAccount";
    private static final String DUPLICATE_TXN_ID = "duplicateTxnId";
    private static final String SUBMIT_KEY = "submitKey";
    private static final String ADMIN_KEY = "adminKey";
    private static final String PAYER_KEY = "payerKey";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(Map.of("fees.simpleFeesEnabled", "true"));
    }

    @Nested
    class CreateTopicSimpleFeesPositiveTests {
        @HapiTest
        @DisplayName("Create topic - base fees full charging without extras")
        final Stream<DynamicTest> createTopicWithIncludedSigAndKey() {
            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    createTopic("testTopic")
                            .memo("testMemo")
                            .payingWith(PAYER)
                            .signedBy(PAYER)
                            .fee(ONE_HBAR)
                            .via("create-topic-txn"),
                    validateChargedUsdWithinWithTxnSize(
                            "create-topic-txn", txnSize -> expectedTopicCreateFullFeeUsd(1, 0, txnSize), 0.01));
        }

        @HapiTest
        @DisplayName("Create topic - with admin, submit key and auto-renew account is charged extras correctly")
        final Stream<DynamicTest> createTopicWithAdminSubmitKeyAndAutoRenewAccountChargedCorrectly() {
            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(AUTO_RENEW_ACCOUNT).balance(ONE_HBAR),
                    newKeyNamed(ADMIN),
                    newKeyNamed(SUBMIT_KEY),
                    createTopic("testTopic")
                            .memo("testMemo")
                            .autoRenewAccountId(AUTO_RENEW_ACCOUNT)
                            .adminKeyName(ADMIN)
                            .submitKeyName(SUBMIT_KEY)
                            .payingWith(PAYER)
                            .signedBy(PAYER, ADMIN, AUTO_RENEW_ACCOUNT)
                            .fee(ONE_HBAR)
                            .via("create-topic-txn"),
                    validateChargedUsdWithinWithTxnSize(
                            "create-topic-txn", txnSize -> expectedTopicCreateFullFeeUsd(3, 2, txnSize), 0.01));
        }

        @HapiTest
        @DisplayName("Create topic - with auto-renew account is charged extra signatures only")
        final Stream<DynamicTest> createTopicWithAutoRenewAccountChargedExtraSignaturesOnly() {
            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(AUTO_RENEW_ACCOUNT).balance(ONE_HBAR),
                    createTopic("testTopic")
                            .memo("testMemo")
                            .autoRenewAccountId(AUTO_RENEW_ACCOUNT)
                            .payingWith(PAYER)
                            .signedBy(PAYER, AUTO_RENEW_ACCOUNT)
                            .fee(ONE_HBAR)
                            .via("create-topic-txn"),
                    validateChargedUsdWithinWithTxnSize(
                            "create-topic-txn", txnSize -> expectedTopicCreateFullFeeUsd(2, 0, txnSize), 0.01));
        }

        @HapiTest
        @DisplayName("Create topic - with one extra signature and one extra key fees")
        final Stream<DynamicTest> createTopicWithOneExtraSigAndOneExtraKey() {
            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(ADMIN).balance(ONE_HUNDRED_HBARS),
                    createTopic("testTopic")
                            .blankMemo()
                            .adminKeyName(ADMIN)
                            .payingWith(PAYER)
                            .signedBy(PAYER, ADMIN)
                            .fee(ONE_HBAR)
                            .via("create-topic-txn"),
                    validateChargedUsdWithinWithTxnSize(
                            "create-topic-txn", txnSize -> expectedTopicCreateFullFeeUsd(2, 1, txnSize), 0.01));
        }

        @HapiTest
        @DisplayName("Create topic - with one extra signature and two extra keys fees")
        final Stream<DynamicTest> createTopicWithOneExtraSigAndTwoExtraKey() {
            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(ADMIN).balance(ONE_HUNDRED_HBARS),
                    newKeyNamed(SUBMIT_KEY),
                    createTopic("testTopic")
                            .blankMemo()
                            .adminKeyName(ADMIN)
                            .submitKeyName(SUBMIT_KEY)
                            .payingWith(PAYER)
                            .signedBy(PAYER, ADMIN)
                            .fee(ONE_HBAR)
                            .via("create-topic-txn"),
                    validateChargedUsdWithinWithTxnSize(
                            "create-topic-txn", txnSize -> expectedTopicCreateFullFeeUsd(2, 2, txnSize), 0.01));
        }

        @HapiTest
        @DisplayName("Create topic - with threshold signature with two extra signatures and three extra keys fees")
        final Stream<DynamicTest> createTopicWithTwoExtraSigAndThreeExtraKey() {

            // Define a threshold key that requires two simple keys signatures
            KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);

            // Create a valid signature with both simple keys signing
            SigControl validSig = keyShape.signedWith(sigs(ON, ON));

            return hapiTest(
                    newKeyNamed(SUBMIT_KEY),
                    newKeyNamed(ADMIN_KEY).shape(keyShape),
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(ADMIN).key(ADMIN_KEY).balance(ONE_HUNDRED_HBARS),
                    createTopic("testTopic")
                            .blankMemo()
                            .sigControl(forKey(ADMIN_KEY, validSig))
                            .adminKeyName(ADMIN)
                            .submitKeyName(SUBMIT_KEY)
                            .payingWith(PAYER)
                            .signedBy(PAYER, ADMIN)
                            .fee(ONE_HBAR)
                            .via("create-topic-txn"),
                    validateChargedUsdWithinWithTxnSize(
                            "create-topic-txn", txnSize -> expectedTopicCreateFullFeeUsd(3, 3, txnSize), 0.01));
        }

        @HapiTest
        @DisplayName("Create topic - with threshold signature with three extra signatures and five extra keys fees")
        final Stream<DynamicTest> createTopicWithThreeExtraSigAndFiveExtraKey() {

            // Define a threshold key that requires two simple keys signatures
            KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE, listOf(2));

            // Create a valid signature with both simple keys signing
            SigControl validSig = keyShape.signedWith(sigs(ON, ON, sigs(ON, OFF)));

            return hapiTest(
                    newKeyNamed(SUBMIT_KEY),
                    newKeyNamed(ADMIN_KEY).shape(keyShape),
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(ADMIN).key(ADMIN_KEY).balance(ONE_HUNDRED_HBARS),
                    createTopic("testTopic")
                            .blankMemo()
                            .sigControl(forKey(ADMIN_KEY, validSig))
                            .adminKeyName(ADMIN)
                            .submitKeyName(SUBMIT_KEY)
                            .payingWith(PAYER)
                            .signedBy(PAYER, ADMIN)
                            .fee(ONE_HBAR)
                            .via("create-topic-txn"),
                    validateChargedUsdWithinWithTxnSize(
                            "create-topic-txn", txnSize -> expectedTopicCreateFullFeeUsd(4, 5, txnSize), 0.01));
        }

        @HapiTest
        @DisplayName("Create topic - with key list with extra signature and extra key fees")
        final Stream<DynamicTest> createTopicWithKeyListWithExtraSigAndExtraKey() {
            return hapiTest(
                    newKeyNamed("firstKey"),
                    newKeyNamed("secondKey"),
                    newKeyListNamed(ADMIN_KEY, List.of("firstKey", "secondKey")),
                    cryptoCreate(ADMIN).key(ADMIN_KEY).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    createTopic("testTopic")
                            .blankMemo()
                            .adminKeyName(ADMIN)
                            .payingWith(PAYER)
                            .signedBy(PAYER, ADMIN)
                            .fee(ONE_HBAR)
                            .via("create-topic-txn"),
                    validateChargedUsdWithinWithTxnSize(
                            "create-topic-txn", txnSize -> expectedTopicCreateFullFeeUsd(3, 2, txnSize), 0.01));
        }

        @HapiTest
        @DisplayName("Create topic - with payer as admin and threshold signature with extra signatures and keys")
        final Stream<DynamicTest> createTopicWithPayerAsAdminWithExtraSignaturesAndKeys() {

            // Define a threshold key that requires two simple keys signatures
            KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE, listOf(2));

            // Create a valid signature with both simple keys signing
            SigControl validSig = keyShape.signedWith(sigs(ON, ON, sigs(ON, OFF)));

            return hapiTest(
                    newKeyNamed(SUBMIT_KEY),
                    newKeyNamed(ADMIN_KEY).shape(keyShape),
                    cryptoCreate(PAYER).key(ADMIN_KEY).balance(ONE_HUNDRED_HBARS),
                    createTopic("testTopic")
                            .blankMemo()
                            .sigControl(forKey(ADMIN_KEY, validSig))
                            .adminKeyName(PAYER)
                            .submitKeyName(SUBMIT_KEY)
                            .payingWith(PAYER)
                            .signedBy(PAYER)
                            .fee(ONE_HBAR)
                            .via("create-topic-txn"),
                    validateChargedUsdWithinWithTxnSize(
                            "create-topic-txn", txnSize -> expectedTopicCreateFullFeeUsd(3, 5, txnSize), 0.01));
        }

        @HapiTest
        @DisplayName(
                "Create topic - with payer key as admin and submit keys and threshold signature with extra signatures and keys")
        final Stream<DynamicTest> createTopicWithPayerAsAdminAndSubmitKeyWithExtraSignaturesAndKeys() {

            // Define a threshold key that requires two simple keys signatures
            KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE, listOf(2));

            // Create a valid signature with both simple keys signing
            SigControl validSig = keyShape.signedWith(sigs(ON, ON, sigs(ON, OFF)));

            return hapiTest(
                    newKeyNamed(PAYER_KEY).shape(keyShape),
                    cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                    createTopic("testTopic")
                            .blankMemo()
                            .sigControl(forKey(PAYER_KEY, validSig))
                            .adminKeyName(PAYER)
                            .submitKeyName(PAYER)
                            .payingWith(PAYER)
                            .signedBy(PAYER)
                            .fee(ONE_HBAR)
                            .via("create-topic-txn"),
                    validateChargedUsdWithinWithTxnSize(
                            "create-topic-txn", txnSize -> expectedTopicCreateFullFeeUsd(3, 8, txnSize), 0.01));
        }
    }

    @Nested
    class CreateTopicSimpleFeesNegativeCases {

        @Nested
        class CreateTopicSimpleFeesFailuresOnIngest {
            @HapiTest
            @DisplayName("Create topic with insufficient txn fee fails on ingest and payer not charged")
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
            @DisplayName("Create topic not signed by payer fails on ingest and payer not charged")
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
            @DisplayName("Create topic with insufficient payer balance fails on ingest and payer not charged")
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
            @DisplayName("Create topic with too long memo fails on ingest and payer not charged")
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
            @DisplayName("Create topic expired transaction fails on ingest and payer not charged")
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
            @DisplayName("Create topic with too far start time fails on ingest and payer not charged")
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
            @DisplayName("Create topic with invalid duration time fails on ingest and payer not charged")
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
            @DisplayName("Create topic duplicate txn fails on ingest and payer not charged")
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
            @DisplayName("Create topic with insufficient txn fee fails on pre-handle and payer is not charged")
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
                        validateChargedFeeToUsdWithTxnSize(
                                INNER_ID,
                                initialNodeBalance,
                                afterNodeBalance,
                                txnSize -> expectedTopicCreateNetworkFeeOnlyUsd(1, txnSize),
                                0.01));
            }

            @LeakyEmbeddedHapiTest(reason = MUST_SKIP_INGEST)
            @DisplayName("Create topic not signed by payer fails on pre-handle and payer is not charged")
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
                        validateChargedFeeToUsdWithTxnSize(
                                INNER_ID,
                                initialNodeBalance,
                                afterNodeBalance,
                                txnSize -> expectedTopicCreateNetworkFeeOnlyUsd(1, txnSize),
                                1));
            }

            @LeakyEmbeddedHapiTest(reason = MUST_SKIP_INGEST)
            @DisplayName("Create topic with insufficient payer balance fails on pre-handle and payer is not charged")
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
                        validateChargedFeeToUsdWithTxnSize(
                                INNER_ID,
                                initialNodeBalance,
                                afterNodeBalance,
                                txnSize -> expectedTopicCreateNetworkFeeOnlyUsd(2, txnSize),
                                0.01));
            }

            @LeakyEmbeddedHapiTest(reason = MUST_SKIP_INGEST)
            @DisplayName("Create topic with admin key not signed by the admin fails on pre-handle and payer is charged")
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
                        validateChargedFeeToUsdWithTxnSize(
                                INNER_ID,
                                initialBalance,
                                afterBalance,
                                txnSize -> expectedTopicCreateFullFeeUsd(1, 1, txnSize),
                                0.01));
            }

            @LeakyEmbeddedHapiTest(reason = MUST_SKIP_INGEST)
            @DisplayName("Create topic with too long memo fails on pre-handle and payer is not charged")
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
                        validateChargedFeeToUsdWithTxnSize(
                                INNER_ID,
                                initialNodeBalance,
                                afterNodeBalance,
                                txnSize -> expectedTopicCreateNetworkFeeOnlyUsd(1, txnSize),
                                0.01));
            }

            @LeakyEmbeddedHapiTest(reason = MUST_SKIP_INGEST)
            @DisplayName("Create topic expired transaction fails on pre-handle and payer is not charged")
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
                        validateChargedFeeToUsdWithTxnSize(
                                INNER_ID,
                                initialNodeBalance,
                                afterNodeBalance,
                                txnSize -> expectedTopicCreateNetworkFeeOnlyUsd(1, txnSize),
                                0.01));
            }

            @LeakyEmbeddedHapiTest(reason = MUST_SKIP_INGEST)
            @DisplayName("Create topic with too far start time fails on pre-handle and payer is not charged")
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
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        getAccountBalance("4").exposingBalanceTo(afterNodeBalance::set),
                        withOpContext((spec, log) -> {
                            long nodeDelta = initialNodeBalance.get() - afterNodeBalance.get();
                            log.info("Node balance change: {}", nodeDelta);
                            log.info("Recorded fee: {}", expectedTopicCreateNetworkFeeOnlyUsd(1));
                            assertEquals(initialBalance.get(), afterBalance.get());
                            assertTrue(initialNodeBalance.get() > afterNodeBalance.get());
                        }),
                        validateChargedFeeToUsdWithTxnSize(
                                INNER_ID,
                                initialNodeBalance,
                                afterNodeBalance,
                                txnSize -> expectedTopicCreateNetworkFeeOnlyUsd(1, txnSize),
                                0.01));
            }

            @LeakyEmbeddedHapiTest(reason = MUST_SKIP_INGEST)
            @DisplayName("Create topic with invalid duration time fails on pre-handle and payer is not charged")
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
                        validateChargedFeeToUsdWithTxnSize(
                                INNER_ID,
                                initialNodeBalance,
                                afterNodeBalance,
                                txnSize -> expectedTopicCreateNetworkFeeOnlyUsd(1, txnSize),
                                0.01));
            }
        }

        @Nested
        @DisplayName("Create Topic Simple Fees Failures on Handle")
        class CreateTopicSimpleFeesFailuresOnHandle {
            @Tag(ONLY_SUBPROCESS)
            @HapiTest
            @DisplayName("Create Topic with duplicate transaction fails on handle")
            Stream<DynamicTest> topicCreateWithDuplicateTransactionFailsOnHandlePayerChargedFullFee() {
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();
                final AtomicLong initialNodeBalance = new AtomicLong();
                final AtomicLong afterNodeBalance = new AtomicLong();

                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),

                        // Save balances before
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                        cryptoTransfer(movingHbar(ONE_HBAR).between(GENESIS, "3")),
                        getAccountBalance("3").exposingBalanceTo(initialNodeBalance::set),

                        // Register a TxnId for the inner txn
                        usableTxnIdNamed(DUPLICATE_TXN_ID).payerId(PAYER),

                        // Submit duplicate transactions
                        createTopic("testTopic")
                                .blankMemo()
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .fee(ONE_HBAR)
                                .setNode(4)
                                .txnId(DUPLICATE_TXN_ID)
                                .via("topicCreateTxn")
                                .logged(),
                        createTopic("testAccount")
                                .blankMemo()
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .fee(ONE_HBAR)
                                .setNode(3)
                                .txnId(DUPLICATE_TXN_ID)
                                .via("topicCreateDuplicateTxn")
                                .hasPrecheck(DUPLICATE_TRANSACTION),

                        // Save balances after and assert node was not charged
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        getAccountBalance("3").exposingBalanceTo(afterNodeBalance::set),
                        withOpContext((spec, log) -> {
                            long payerDelta = initialBalance.get() - afterBalance.get();
                            log.info("Payer balance change: {}", payerDelta);
                            log.info("Recorded fee: {}", expectedTopicCreateFullFeeUsd(1, 0));
                            assertTrue(initialBalance.get() > afterBalance.get());
                        }),
                        validateChargedFeeToUsdWithTxnSize(
                                "topicCreateTxn",
                                initialBalance,
                                afterBalance,
                                txnSize -> expectedTopicCreateFullFeeUsd(1L, 0L, txnSize),
                                0.01));
            }

            @HapiTest
            @DisplayName(
                    "Create topic - with invalid threshold signature with two extra signatures and three extra keys - "
                            + "fails on handle")
            final Stream<DynamicTest> createTopicWithInvalidSignatureWithTwoExtraSigAndThreeExtraKeysFailsOnHandle() {
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();

                // Define a threshold key that requires two simple keys signatures
                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);

                // Create a valid signature with both simple keys signing
                SigControl invalidSig = keyShape.signedWith(sigs(ON, OFF));

                return hapiTest(
                        newKeyNamed(SUBMIT_KEY),
                        newKeyNamed(ADMIN_KEY).shape(keyShape),
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(ADMIN).key(ADMIN_KEY).balance(ONE_HUNDRED_HBARS),
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                        createTopic("testTopic")
                                .blankMemo()
                                .sigControl(forKey(ADMIN_KEY, invalidSig))
                                .adminKeyName(ADMIN)
                                .submitKeyName(SUBMIT_KEY)
                                .payingWith(PAYER)
                                .signedBy(PAYER, ADMIN)
                                .fee(ONE_HBAR)
                                .via("create-topic-txn")
                                .hasKnownStatus(INVALID_SIGNATURE),

                        // Save balances and assert changes
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        withOpContext((spec, log) -> {
                            long payerDelta = initialBalance.get() - afterBalance.get();
                            log.info("Payer balance change: {}", payerDelta);
                            log.info("Recorded fee: {}", expectedTopicCreateFullFeeUsd(2, 3));
                            assertTrue(initialBalance.get() > afterBalance.get());
                        }),
                        validateChargedFeeToUsdWithTxnSize(
                                "create-topic-txn",
                                initialBalance,
                                afterBalance,
                                txnSize -> expectedTopicCreateFullFeeUsd(2L, 3L, txnSize),
                                0.01));
            }

            @HapiTest
            @DisplayName("Create topic - with empty threshold signature fails on handle")
            final Stream<DynamicTest> createTopicWithEmptyThresholdSignatureFailsOnHandle() {
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();

                // Define a threshold key that requires two simple keys signatures
                KeyShape keyShape = threshOf(0, 0);

                return hapiTest(
                        newKeyNamed(SUBMIT_KEY).shape(keyShape),
                        cryptoCreate(ADMIN).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                        createTopic("testTopic")
                                .blankMemo()
                                .adminKeyName(ADMIN)
                                .submitKeyName(SUBMIT_KEY)
                                .payingWith(PAYER)
                                .signedBy(PAYER, ADMIN)
                                .fee(ONE_HBAR)
                                .via("create-topic-txn")
                                .hasKnownStatus(BAD_ENCODING),

                        // Save balances and assert changes
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        withOpContext((spec, log) -> {
                            long payerDelta = initialBalance.get() - afterBalance.get();
                            log.info("Payer balance change: {}", payerDelta);
                            log.info("Recorded fee: {}", expectedTopicCreateFullFeeUsd(2, 1));
                            assertTrue(initialBalance.get() > afterBalance.get());
                        }),
                        validateChargedFeeToUsdWithTxnSize(
                                "create-topic-txn",
                                initialBalance,
                                afterBalance,
                                txnSize -> expectedTopicCreateFullFeeUsd(2L, 1L, txnSize),
                                0.01));
            }

            @HapiTest
            @DisplayName(
                    "Create topic - with invalid threshold signature with two extra signatures and five extra keys - "
                            + "fails on handle")
            final Stream<DynamicTest> createTopicWithInvalidSignatureWithThreeExtraSigAndFiveExtraKeysFailsOnHandle() {
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();

                // Define a threshold key that requires two simple keys signatures
                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE, listOf(2));

                // Create a valid signature with both simple keys signing
                SigControl invalidSig = keyShape.signedWith(sigs(ON, OFF, sigs(OFF, OFF)));

                return hapiTest(
                        newKeyNamed(SUBMIT_KEY),
                        newKeyNamed(ADMIN_KEY).shape(keyShape),
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(ADMIN).key(ADMIN_KEY).balance(ONE_HUNDRED_HBARS),
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                        createTopic("testTopic")
                                .blankMemo()
                                .sigControl(forKey(ADMIN_KEY, invalidSig))
                                .adminKeyName(ADMIN)
                                .submitKeyName(SUBMIT_KEY)
                                .payingWith(PAYER)
                                .signedBy(PAYER, ADMIN)
                                .fee(ONE_HBAR)
                                .via("create-topic-txn")
                                .hasKnownStatus(INVALID_SIGNATURE),

                        // Save balances and assert changes
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        withOpContext((spec, log) -> {
                            long payerDelta = initialBalance.get() - afterBalance.get();
                            log.info("Payer balance change: {}", payerDelta);
                            log.info("Recorded fee: {}", expectedTopicCreateFullFeeUsd(2, 5));
                            assertTrue(initialBalance.get() > afterBalance.get());
                        }),
                        validateChargedFeeToUsdWithTxnSize(
                                "create-topic-txn",
                                initialBalance,
                                afterBalance,
                                txnSize -> expectedTopicCreateFullFeeUsd(2L, 5L, txnSize),
                                0.01));
            }

            @HapiTest
            @DisplayName("Create topic - with empty threshold nested signature fails on handle")
            final Stream<DynamicTest> createTopicWithEmptyThresholdNestedSignatureFailsOnHandle() {
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();

                // Define a threshold key that requires two simple keys signatures
                KeyShape keyShape = threshOf(3, listOf(0));

                return hapiTest(
                        newKeyNamed(SUBMIT_KEY).shape(keyShape),
                        cryptoCreate(ADMIN).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                        createTopic("testTopic")
                                .blankMemo()
                                .adminKeyName(ADMIN)
                                .submitKeyName(SUBMIT_KEY)
                                .payingWith(PAYER)
                                .signedBy(PAYER, ADMIN)
                                .fee(ONE_HBAR)
                                .via("create-topic-txn")
                                .hasKnownStatus(BAD_ENCODING),

                        // Save balances and assert changes
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        withOpContext((spec, log) -> {
                            long payerDelta = initialBalance.get() - afterBalance.get();
                            log.info("Payer balance change: {}", payerDelta);
                            log.info("Recorded fee: {}", expectedTopicCreateFullFeeUsd(2, 1));
                            assertTrue(initialBalance.get() > afterBalance.get());
                        }),
                        validateChargedFeeToUsdWithTxnSize(
                                "create-topic-txn",
                                initialBalance,
                                afterBalance,
                                txnSize -> expectedTopicCreateFullFeeUsd(2L, 1L, txnSize),
                                0.01));
            }

            @HapiTest
            @DisplayName("Create topic - with invalid key list signature fails on handle")
            final Stream<DynamicTest> createTopicWithInvalidKeyListSignatureFailsOnHandle() {
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();

                return hapiTest(
                        newKeyNamed(SUBMIT_KEY),
                        newKeyNamed("firstKey"),
                        newKeyNamed("secondKey"),
                        newKeyListNamed(ADMIN_KEY, List.of("firstKey", "secondKey")),
                        cryptoCreate(ADMIN).key(ADMIN_KEY).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                        createTopic("testTopic")
                                .blankMemo()
                                .adminKeyName(ADMIN)
                                .submitKeyName(SUBMIT_KEY)
                                .payingWith(PAYER)
                                .signedBy(PAYER, "firstKey")
                                .fee(ONE_HBAR)
                                .via("create-topic-txn")
                                .hasKnownStatus(INVALID_SIGNATURE),

                        // Save balances and assert changes
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        withOpContext((spec, log) -> {
                            long payerDelta = initialBalance.get() - afterBalance.get();
                            log.info("Payer balance change: {}", payerDelta);
                            log.info("Recorded fee: {}", expectedTopicCreateFullFeeUsd(2, 3));
                            assertTrue(initialBalance.get() > afterBalance.get());
                        }),
                        validateChargedFeeToUsdWithTxnSize(
                                "create-topic-txn",
                                initialBalance,
                                afterBalance,
                                txnSize -> expectedTopicCreateFullFeeUsd(2L, 3L, txnSize),
                                0.01));
            }

            @HapiTest
            @DisplayName("Create topic - with admin, submit key and invalid auto-renew account fails on handle")
            final Stream<DynamicTest> createTopicWithAdminSubmitKeyAndInvalidAutoRenewAccountFailsOnHandle() {
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();

                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(AUTO_RENEW_ACCOUNT).balance(ONE_HBAR),
                        newKeyNamed(ADMIN),
                        newKeyNamed(SUBMIT_KEY),
                        cryptoDelete(AUTO_RENEW_ACCOUNT),
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                        createTopic("testTopic")
                                .memo("testMemo")
                                .autoRenewAccountId(AUTO_RENEW_ACCOUNT)
                                .adminKeyName(ADMIN)
                                .submitKeyName(SUBMIT_KEY)
                                .payingWith(PAYER)
                                .signedBy(PAYER, ADMIN, AUTO_RENEW_ACCOUNT)
                                .fee(ONE_HBAR)
                                .via("create-topic-txn")
                                .hasKnownStatus(INVALID_AUTORENEW_ACCOUNT),

                        // Save balances and assert changes
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        withOpContext((spec, log) -> {
                            long payerDelta = initialBalance.get() - afterBalance.get();
                            log.info("Payer balance change: {}", payerDelta);
                            log.info("Recorded fee: {}", expectedTopicCreateFullFeeUsd(3, 2));
                            assertTrue(initialBalance.get() > afterBalance.get());
                        }),
                        validateChargedFeeToUsdWithTxnSize(
                                "create-topic-txn",
                                initialBalance,
                                afterBalance,
                                txnSize -> expectedTopicCreateFullFeeUsd(3L, 2L, txnSize),
                                0.01));
            }
        }
    }
}
