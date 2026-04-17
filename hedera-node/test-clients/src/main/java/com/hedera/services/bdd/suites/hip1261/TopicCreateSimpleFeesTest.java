// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip1261;

import static com.hedera.services.bdd.junit.EmbeddedReason.MUST_SKIP_INGEST;
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
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.createTopic;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingHbar;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyListNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.usableTxnIdNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedAccount;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedNetworkOnlyFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTopicCreateFullFeeUsd;
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
import static org.hiero.hapi.support.fees.Extra.KEYS;
import static org.hiero.hapi.support.fees.Extra.PROCESSING_BYTES;
import static org.hiero.hapi.support.fees.Extra.SIGNATURES;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyEmbeddedHapiTest;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.keys.KeyShape;
import com.hedera.services.bdd.spec.keys.SigControl;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;

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
    private static final String createTopicTxn = "create-topic-txn";

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
                            .via(createTopicTxn),
                    validateChargedUsdWithinWithTxnSize(
                            createTopicTxn,
                            txnSize -> expectedTopicCreateFullFeeUsd(Map.of(
                                    SIGNATURES, 1L,
                                    KEYS, 0L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(createTopicTxn, PAYER));
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
                            .via(createTopicTxn),
                    validateChargedUsdWithinWithTxnSize(
                            createTopicTxn,
                            txnSize -> expectedTopicCreateFullFeeUsd(Map.of(
                                    SIGNATURES, 3L,
                                    KEYS, 2L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(createTopicTxn, PAYER));
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
                            .via(createTopicTxn),
                    validateChargedUsdWithinWithTxnSize(
                            createTopicTxn,
                            txnSize -> expectedTopicCreateFullFeeUsd(Map.of(
                                    SIGNATURES, 2L,
                                    KEYS, 0L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(createTopicTxn, PAYER));
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
                            .via(createTopicTxn),
                    validateChargedUsdWithinWithTxnSize(
                            createTopicTxn,
                            txnSize -> expectedTopicCreateFullFeeUsd(Map.of(
                                    SIGNATURES, 2L,
                                    KEYS, 1L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(createTopicTxn, PAYER));
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
                            .via(createTopicTxn),
                    validateChargedUsdWithinWithTxnSize(
                            createTopicTxn,
                            txnSize -> expectedTopicCreateFullFeeUsd(Map.of(
                                    SIGNATURES, 2L,
                                    KEYS, 2L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(createTopicTxn, PAYER));
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
                            .via(createTopicTxn),
                    validateChargedUsdWithinWithTxnSize(
                            createTopicTxn,
                            txnSize -> expectedTopicCreateFullFeeUsd(Map.of(
                                    SIGNATURES, 3L,
                                    KEYS, 3L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(createTopicTxn, PAYER));
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
                            .via(createTopicTxn),
                    validateChargedUsdWithinWithTxnSize(
                            createTopicTxn,
                            txnSize -> expectedTopicCreateFullFeeUsd(Map.of(
                                    SIGNATURES, 4L,
                                    KEYS, 5L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(createTopicTxn, PAYER));
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
                            .via(createTopicTxn),
                    validateChargedUsdWithinWithTxnSize(
                            createTopicTxn,
                            txnSize -> expectedTopicCreateFullFeeUsd(Map.of(
                                    SIGNATURES, 3L,
                                    KEYS, 2L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(createTopicTxn, PAYER));
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
                            .via(createTopicTxn),
                    validateChargedUsdWithinWithTxnSize(
                            createTopicTxn,
                            txnSize -> expectedTopicCreateFullFeeUsd(Map.of(
                                    SIGNATURES, 3L,
                                    KEYS, 5L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(createTopicTxn, PAYER));
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
                            .via(createTopicTxn),
                    validateChargedUsdWithinWithTxnSize(
                            createTopicTxn,
                            txnSize -> expectedTopicCreateFullFeeUsd(Map.of(
                                    SIGNATURES, 3L,
                                    KEYS, 8L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(createTopicTxn, PAYER));
        }

        @HapiTest
        @DisplayName("Create topic - with submit key only is charged key extra without extra signature")
        final Stream<DynamicTest> createTopicWithSubmitKeyOnlyChargedKeyExtra() {
            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    newKeyNamed(SUBMIT_KEY),
                    createTopic("testTopic")
                            .blankMemo()
                            .submitKeyName(SUBMIT_KEY)
                            .payingWith(PAYER)
                            .signedBy(PAYER) // Submit key does NOT need to sign at creation
                            .via(createTopicTxn),
                    validateChargedUsdWithinWithTxnSize(
                            createTopicTxn,
                            txnSize -> expectedTopicCreateFullFeeUsd(Map.of(
                                    SIGNATURES, 1L,
                                    KEYS, 1L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(createTopicTxn, PAYER));
        }

        @HapiTest
        @DisplayName("Create topic - with large payer key - extra processing bytes fee")
        final Stream<DynamicTest> createTopicWithLargePayerKeyExtraProcessingBytesFee() {
            KeyShape keyShape = threshOf(
                    1, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE,
                    SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE);
            SigControl allSigned = keyShape.signedWith(
                    sigs(ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON));

            return hapiTest(
                    newKeyNamed(PAYER_KEY).shape(keyShape),
                    cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                    createTopic("testTopic")
                            .blankMemo()
                            .payingWith(PAYER)
                            .sigControl(forKey(PAYER_KEY, allSigned))
                            .signedBy(PAYER)
                            .via(createTopicTxn),
                    validateChargedUsdWithinWithTxnSize(
                            createTopicTxn,
                            txnSize -> expectedTopicCreateFullFeeUsd(Map.of(
                                    SIGNATURES, 20L,
                                    KEYS, 0L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(createTopicTxn, PAYER));
        }

        @HapiTest
        @DisplayName("Create topic - with very large payer key below oversize - extra processing bytes fee")
        final Stream<DynamicTest> createTopicWithVeryLargePayerKeyBelowOversizeFee() {
            KeyShape keyShape = threshOf(
                    1, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE,
                    SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE,
                    SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE,
                    SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE);
            SigControl allSigned = keyShape.signedWith(sigs(
                    ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON,
                    ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON));

            return hapiTest(
                    newKeyNamed(PAYER_KEY).shape(keyShape),
                    cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                    createTopic("testTopic")
                            .blankMemo()
                            .payingWith(PAYER)
                            .sigControl(forKey(PAYER_KEY, allSigned))
                            .signedBy(PAYER)
                            .via(createTopicTxn),
                    validateChargedUsdWithinWithTxnSize(
                            createTopicTxn,
                            txnSize -> expectedTopicCreateFullFeeUsd(Map.of(
                                    SIGNATURES, 41L,
                                    KEYS, 0L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.1));
        }
    }

    @Nested
    class CreateTopicSimpleFeesNegativeCases {

        @Nested
        class CreateTopicSimpleFeesFailuresOnIngest {
            @HapiTest
            @DisplayName("Create topic with insufficient txn fee fails on ingest and payer not charged")
            final Stream<DynamicTest> createTopicInsufficientFeeFailsOnIngest() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),

                        // Create topic with insufficient fee
                        createTopic("testTopic")
                                .blankMemo()
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .fee(ONE_HBAR / 100000) // fee is too low
                                .via(createTopicTxn)
                                .hasPrecheck(INSUFFICIENT_TX_FEE),
                        // assert no txn record is created
                        getTxnRecord(createTopicTxn).logged().hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("Create topic not signed by payer fails on ingest and payer not charged")
            final Stream<DynamicTest> createTopicNotSignedByPayerFailsOnIngest() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(ADMIN).balance(ONE_HUNDRED_HBARS),

                        // Create topic with admin key not signed by payer
                        createTopic("testTopic")
                                .blankMemo()
                                .payingWith(PAYER)
                                .adminKeyName(ADMIN)
                                .signedBy(ADMIN)
                                .via(createTopicTxn)
                                .hasPrecheck(INVALID_SIGNATURE),
                        // assert no txn record is created
                        getTxnRecord(createTopicTxn).logged().hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("Create topic with insufficient payer balance fails on ingest and payer not charged")
            final Stream<DynamicTest> createTopicWithInsufficientPayerBalanceFailsOnIngest() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HBAR / 100000), // insufficient balance
                        newKeyNamed(ADMIN),

                        // Create topic with insufficient payer balance
                        createTopic("testTopic")
                                .blankMemo()
                                .payingWith(PAYER)
                                .adminKeyName(ADMIN)
                                .via(createTopicTxn)
                                .hasPrecheck(INSUFFICIENT_PAYER_BALANCE),
                        // assert no txn record is created
                        getTxnRecord(createTopicTxn).logged().hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("Create topic with too long memo fails on ingest and payer not charged")
            final Stream<DynamicTest> createTopicTooLongMemoFailsOnIngest() {
                final var LONG_MEMO = "x".repeat(1025); // memo exceeds 1024 bytes limit
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        // Create topic with too long memo
                        createTopic("testTopic")
                                .memo(LONG_MEMO)
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .via(createTopicTxn)
                                .hasPrecheck(MEMO_TOO_LONG),
                        // assert no txn record is created
                        getTxnRecord(createTopicTxn).logged().hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("Create topic expired transaction fails on ingest and payer not charged")
            final Stream<DynamicTest> createTopicExpiredFailsOnIngest() {
                final var expiredTxnId = "expiredCreateTopic";
                final var oneHourPast = -3_600L; // 1 hour before
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        // Create expired topic
                        usableTxnIdNamed(expiredTxnId)
                                .modifyValidStart(oneHourPast)
                                .payerId(PAYER),
                        createTopic("testTopic")
                                .blankMemo()
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .txnId(expiredTxnId)
                                .via(createTopicTxn)
                                .hasPrecheck(TRANSACTION_EXPIRED),
                        // assert no txn record is created
                        getTxnRecord(createTopicTxn).logged().hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("Create topic with too far start time fails on ingest and payer not charged")
            final Stream<DynamicTest> createTopicTooFarStartTimeFailsOnIngest() {
                final var futureTxnId = "futureCreateTopic";
                final var oneHourFuture = 3_600L; // 1 hour after
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        // Create topic with start time in the future
                        usableTxnIdNamed(futureTxnId)
                                .modifyValidStart(oneHourFuture)
                                .payerId(PAYER),
                        createTopic("testTopic")
                                .blankMemo()
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .txnId(futureTxnId)
                                .via(createTopicTxn)
                                .hasPrecheck(INVALID_TRANSACTION_START),
                        // assert no txn record is created
                        getTxnRecord(createTopicTxn).logged().hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("Create topic with invalid duration time fails on ingest and payer not charged")
            final Stream<DynamicTest> createTopicInvalidDurationTimeFailsOnIngest() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        // Create topic with invalid duration time
                        createTopic("testTopic")
                                .blankMemo()
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .validDurationSecs(0) // invalid duration
                                .via(createTopicTxn)
                                .hasPrecheck(INVALID_TRANSACTION_DURATION),
                        // assert no txn record is created
                        getTxnRecord(createTopicTxn).logged().hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("Create topic duplicate txn fails on ingest and payer not charged")
            final Stream<DynamicTest> createTopicDuplicateTxnFailsOnIngest() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        // Create topic successful first txn
                        createTopic("testTopic").blankMemo().via(createTopicTxn),
                        // Create topic duplicate txn
                        createTopic("testTopicDuplicate")
                                .blankMemo()
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .txnId(createTopicTxn)
                                .via("create-topic-duplicate-txn")
                                .hasPrecheck(DUPLICATE_TRANSACTION));
            }
        }

        @Nested
        class CreateTopicSimpleFeesFailuresOnPreHandle {
            @LeakyEmbeddedHapiTest(reason = MUST_SKIP_INGEST)
            @DisplayName("Create topic with insufficient txn fee fails on pre-handle and payer is not charged")
            final Stream<DynamicTest> createTopicInsufficientFeeFailsOnPreHandle() {
                final String INNER_ID = "create-topic-txn-inner-id";

                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),

                        // Register a TxnId for the inner txn
                        usableTxnIdNamed(INNER_ID).payerId(PAYER),
                        cryptoTransfer(movingHbar(ONE_HBAR).between(GENESIS, "4")),
                        createTopic("testTopic")
                                .blankMemo()
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .fee(ONE_HBAR / 100000) // fee is too low
                                .setNode(4)
                                .via(INNER_ID)
                                .hasKnownStatus(INSUFFICIENT_TX_FEE),
                        getTxnRecord(INNER_ID).assertingNothingAboutHashes().logged(),
                        validateChargedUsdWithinWithTxnSize(
                                INNER_ID,
                                txnSize -> expectedNetworkOnlyFeeUsd(
                                        Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateChargedAccount(INNER_ID, "4"));
            }

            @LeakyEmbeddedHapiTest(reason = MUST_SKIP_INGEST)
            @DisplayName("Create topic not signed by payer fails on pre-handle and payer is not charged")
            final Stream<DynamicTest> createTopicNotSignedByPayerFailsOnPreHandle() {
                final String INNER_ID = "create-topic-txn-inner-id";

                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(ADMIN).balance(ONE_HUNDRED_HBARS),

                        // Register a TxnId for the inner txn
                        usableTxnIdNamed(INNER_ID).payerId(PAYER),
                        cryptoTransfer(movingHbar(ONE_HBAR).between(GENESIS, "4")),
                        createTopic("testTopic")
                                .blankMemo()
                                .payingWith(PAYER)
                                .adminKeyName(ADMIN)
                                .signedBy(ADMIN)
                                .setNode(4)
                                .via(INNER_ID)
                                .hasKnownStatus(INVALID_PAYER_SIGNATURE),
                        getTxnRecord(INNER_ID).assertingNothingAboutHashes().logged(),
                        validateChargedUsdWithinWithTxnSize(
                                INNER_ID,
                                txnSize -> expectedNetworkOnlyFeeUsd(
                                        Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateChargedAccount(INNER_ID, "4"));
            }

            @LeakyEmbeddedHapiTest(reason = MUST_SKIP_INGEST)
            @DisplayName("Create topic with insufficient payer balance fails on pre-handle and payer is not charged")
            final Stream<DynamicTest> createTopicWithInsufficientPayerBalanceFailsOnPreHandle() {
                final String INNER_ID = "create-topic-txn-inner-id";

                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HBAR / 100000), // insufficient balance
                        cryptoCreate(ADMIN).balance(ONE_HUNDRED_HBARS),

                        // Register a TxnId for the inner txn
                        usableTxnIdNamed(INNER_ID).payerId(PAYER),
                        cryptoTransfer(movingHbar(ONE_HBAR).between(GENESIS, "4")),
                        createTopic("testTopic")
                                .blankMemo()
                                .payingWith(PAYER)
                                .adminKeyName(ADMIN)
                                .signedBy(ADMIN, PAYER)
                                .setNode(4)
                                .via(INNER_ID)
                                .hasKnownStatus(INSUFFICIENT_PAYER_BALANCE),
                        getTxnRecord(INNER_ID).assertingNothingAboutHashes().logged(),
                        validateChargedUsdWithinWithTxnSize(
                                INNER_ID,
                                txnSize -> expectedNetworkOnlyFeeUsd(
                                        Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateChargedAccount(INNER_ID, "4"));
            }

            @LeakyEmbeddedHapiTest(reason = MUST_SKIP_INGEST)
            @DisplayName("Create topic with admin key not signed by the admin fails on pre-handle and payer is charged")
            final Stream<DynamicTest> createTopicWithAdminKeyNotSignedByAdminFailsOnPreHandlePayerIsCharged() {
                final String INNER_ID = "create-topic-txn-inner-id";

                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(ADMIN).balance(ONE_HUNDRED_HBARS),

                        // Register a TxnId for the inner txn
                        usableTxnIdNamed(INNER_ID).payerId(PAYER),

                        // Save balances before
                        cryptoTransfer(movingHbar(ONE_HBAR).between(GENESIS, "4")),
                        createTopic("testTopic")
                                .blankMemo()
                                .payingWith(PAYER)
                                .adminKeyName(ADMIN)
                                .signedBy(PAYER)
                                .setNode(4)
                                .via(INNER_ID)
                                .hasKnownStatus(INVALID_SIGNATURE),
                        getTxnRecord(INNER_ID).assertingNothingAboutHashes().logged(),
                        validateChargedUsdWithinWithTxnSize(
                                INNER_ID,
                                txnSize -> expectedTopicCreateFullFeeUsd(Map.of(
                                        SIGNATURES, 1L,
                                        KEYS, 1L,
                                        PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateChargedAccount(INNER_ID, PAYER));
            }

            @LeakyEmbeddedHapiTest(reason = MUST_SKIP_INGEST)
            @DisplayName("Create topic with too long memo fails on pre-handle and payer is not charged")
            final Stream<DynamicTest> createTopicWithTooLongMemoFailsOnPreHandlePayerIsNotCharged() {
                final var LONG_MEMO = "x".repeat(1025); // memo exceeds 1024 bytes limit
                final String INNER_ID = "create-topic-txn-inner-id";
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),

                        // Register a TxnId for the inner txn
                        usableTxnIdNamed(INNER_ID).payerId(PAYER),
                        cryptoTransfer(movingHbar(ONE_HBAR).between(GENESIS, "4")),
                        createTopic("testTopic")
                                .memo(LONG_MEMO)
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .setNode(4)
                                .via(INNER_ID)
                                .hasKnownStatus(MEMO_TOO_LONG),
                        getTxnRecord(INNER_ID).assertingNothingAboutHashes().logged(),
                        validateChargedUsdWithinWithTxnSize(
                                INNER_ID,
                                txnSize -> expectedNetworkOnlyFeeUsd(
                                        Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateChargedAccount(INNER_ID, "4"));
            }

            @LeakyEmbeddedHapiTest(reason = MUST_SKIP_INGEST)
            @DisplayName("Create topic expired transaction fails on pre-handle and payer is not charged")
            final Stream<DynamicTest> createTopicExpiredFailsOnPreHandlePayerIsNotCharged() {
                final var oneHourPast = -3_600L; // 1 hour before
                final String INNER_ID = "create-topic-txn-inner-id";

                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),

                        // Register a TxnId for the inner txn
                        usableTxnIdNamed(INNER_ID).modifyValidStart(oneHourPast).payerId(PAYER),
                        cryptoTransfer(movingHbar(ONE_HBAR).between(GENESIS, "4")),
                        createTopic("testTopic")
                                .blankMemo()
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .setNode(4)
                                .txnId(INNER_ID)
                                .via(INNER_ID)
                                .hasKnownStatus(TRANSACTION_EXPIRED),
                        getTxnRecord(INNER_ID).assertingNothingAboutHashes().logged(),
                        validateChargedUsdWithinWithTxnSize(
                                INNER_ID,
                                txnSize -> expectedNetworkOnlyFeeUsd(
                                        Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateChargedAccount(INNER_ID, "4"));
            }

            @LeakyEmbeddedHapiTest(reason = MUST_SKIP_INGEST)
            @DisplayName("Create topic with too far start time fails on pre-handle and payer is not charged")
            final Stream<DynamicTest> createTopicTooFarStartTimeFailsOnPreHandlePayerIsNotCharged() {
                final var oneHourFuture = 3_600L; // 1 hour after
                final String INNER_ID = "create-topic-txn-inner-id";

                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),

                        // Register a TxnId for the inner txn
                        usableTxnIdNamed(INNER_ID)
                                .modifyValidStart(oneHourFuture)
                                .payerId(PAYER),

                        // Save balances before
                        cryptoTransfer(movingHbar(ONE_HBAR).between(GENESIS, "4")),
                        createTopic("testTopic")
                                .blankMemo()
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .setNode(4)
                                .txnId(INNER_ID)
                                .via(INNER_ID)
                                .hasKnownStatus(INVALID_TRANSACTION_START),
                        getTxnRecord(INNER_ID).assertingNothingAboutHashes().logged(),
                        validateChargedUsdWithinWithTxnSize(
                                INNER_ID,
                                txnSize -> expectedNetworkOnlyFeeUsd(
                                        Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateChargedAccount(INNER_ID, "4"));
            }

            @LeakyEmbeddedHapiTest(reason = MUST_SKIP_INGEST)
            @DisplayName("Create topic with invalid duration time fails on pre-handle and payer is not charged")
            final Stream<DynamicTest> createTopicInvalidDurationTimeFailsOnPreHandlePayerIsNotCharged() {
                final String INNER_ID = "create-topic-txn-inner-id";
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),

                        // Register a TxnId for the inner txn
                        usableTxnIdNamed(INNER_ID).payerId(PAYER),
                        cryptoTransfer(movingHbar(ONE_HBAR).between(GENESIS, "4")),
                        createTopic("testTopic")
                                .blankMemo()
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .validDurationSecs(0) // invalid duration
                                .setNode(4)
                                .txnId(INNER_ID)
                                .via(INNER_ID)
                                .hasKnownStatus(INVALID_TRANSACTION_DURATION),

                        // Save balances after and assert payer was not charged
                        getTxnRecord(INNER_ID).assertingNothingAboutHashes().logged(),
                        validateChargedUsdWithinWithTxnSize(
                                INNER_ID,
                                txnSize -> expectedNetworkOnlyFeeUsd(
                                        Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateChargedAccount(INNER_ID, "4"));
            }
        }

        @Nested
        @DisplayName("Create Topic Simple Fees Failures on Handle")
        class CreateTopicSimpleFeesFailuresOnHandle {
            @Tag(ONLY_SUBPROCESS)
            @HapiTest
            @DisplayName("Create Topic with duplicate transaction fails on handle")
            Stream<DynamicTest> topicCreateWithDuplicateTransactionFailsOnHandlePayerChargedFullFee() {

                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoTransfer(movingHbar(ONE_HBAR).between(GENESIS, "3")),
                        usableTxnIdNamed(DUPLICATE_TXN_ID).payerId(PAYER),

                        // Submit duplicate transactions
                        createTopic("testTopic")
                                .blankMemo()
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .setNode(4)
                                .txnId(DUPLICATE_TXN_ID)
                                .via("topicCreateTxn"),
                        createTopic("testAccount")
                                .blankMemo()
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .setNode(3)
                                .txnId(DUPLICATE_TXN_ID)
                                .via("topicCreateDuplicateTxn")
                                .hasPrecheck(DUPLICATE_TRANSACTION),
                        validateChargedUsdWithinWithTxnSize(
                                "topicCreateTxn",
                                txnSize -> expectedTopicCreateFullFeeUsd(Map.of(
                                        SIGNATURES, 1L,
                                        KEYS, 0L,
                                        PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateChargedAccount("topicCreateTxn", PAYER));
            }

            @HapiTest
            @DisplayName(
                    "Create topic - with invalid threshold signature with two extra signatures and three extra keys - "
                            + "fails on handle")
            final Stream<DynamicTest> createTopicWithInvalidSignatureWithTwoExtraSigAndThreeExtraKeysFailsOnHandle() {

                // Define a threshold key that requires two simple keys signatures
                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);

                // Create a valid signature with both simple keys signing
                SigControl invalidSig = keyShape.signedWith(sigs(ON, OFF));

                return hapiTest(
                        newKeyNamed(SUBMIT_KEY),
                        newKeyNamed(ADMIN_KEY).shape(keyShape),
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(ADMIN).key(ADMIN_KEY).balance(ONE_HUNDRED_HBARS),
                        createTopic("testTopic")
                                .blankMemo()
                                .sigControl(forKey(ADMIN_KEY, invalidSig))
                                .adminKeyName(ADMIN)
                                .submitKeyName(SUBMIT_KEY)
                                .payingWith(PAYER)
                                .signedBy(PAYER, ADMIN)
                                .via(createTopicTxn)
                                .hasKnownStatus(INVALID_SIGNATURE),
                        validateChargedUsdWithinWithTxnSize(
                                createTopicTxn,
                                txnSize -> expectedTopicCreateFullFeeUsd(Map.of(
                                        SIGNATURES, 2L,
                                        KEYS, 3L,
                                        PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateChargedAccount(createTopicTxn, PAYER));
            }

            @HapiTest
            @DisplayName("Create topic - with empty threshold signature fails on handle")
            final Stream<DynamicTest> createTopicWithEmptyThresholdSignatureFailsOnHandle() {
                // Define a threshold key that requires two simple keys signatures
                KeyShape keyShape = threshOf(0, 0);

                return hapiTest(
                        newKeyNamed(SUBMIT_KEY).shape(keyShape),
                        cryptoCreate(ADMIN).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        createTopic("testTopic")
                                .blankMemo()
                                .adminKeyName(ADMIN)
                                .submitKeyName(SUBMIT_KEY)
                                .payingWith(PAYER)
                                .signedBy(PAYER, ADMIN)
                                .via(createTopicTxn)
                                .hasKnownStatus(BAD_ENCODING),
                        validateChargedUsdWithinWithTxnSize(
                                createTopicTxn,
                                txnSize -> expectedTopicCreateFullFeeUsd(Map.of(
                                        SIGNATURES, 2L,
                                        KEYS, 1L,
                                        PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateChargedAccount(createTopicTxn, PAYER));
            }

            @HapiTest
            @DisplayName(
                    "Create topic - with invalid threshold signature with two extra signatures and five extra keys - "
                            + "fails on handle")
            final Stream<DynamicTest> createTopicWithInvalidSignatureWithThreeExtraSigAndFiveExtraKeysFailsOnHandle() {
                // Define a threshold key that requires two simple keys signatures
                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE, listOf(2));

                // Create a valid signature with both simple keys signing
                SigControl invalidSig = keyShape.signedWith(sigs(ON, OFF, sigs(OFF, OFF)));

                return hapiTest(
                        newKeyNamed(SUBMIT_KEY),
                        newKeyNamed(ADMIN_KEY).shape(keyShape),
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(ADMIN).key(ADMIN_KEY).balance(ONE_HUNDRED_HBARS),
                        createTopic("testTopic")
                                .blankMemo()
                                .sigControl(forKey(ADMIN_KEY, invalidSig))
                                .adminKeyName(ADMIN)
                                .submitKeyName(SUBMIT_KEY)
                                .payingWith(PAYER)
                                .signedBy(PAYER, ADMIN)
                                .via(createTopicTxn)
                                .hasKnownStatus(INVALID_SIGNATURE),
                        validateChargedUsdWithinWithTxnSize(
                                createTopicTxn,
                                txnSize -> expectedTopicCreateFullFeeUsd(Map.of(
                                        SIGNATURES, 2L,
                                        KEYS, 5L,
                                        PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateChargedAccount(createTopicTxn, PAYER));
            }

            @HapiTest
            @DisplayName("Create topic - with empty threshold nested signature fails on handle")
            final Stream<DynamicTest> createTopicWithEmptyThresholdNestedSignatureFailsOnHandle() {
                // Define a threshold key that requires two simple keys signatures
                KeyShape keyShape = threshOf(3, listOf(0));

                return hapiTest(
                        newKeyNamed(SUBMIT_KEY).shape(keyShape),
                        cryptoCreate(ADMIN).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        createTopic("testTopic")
                                .blankMemo()
                                .adminKeyName(ADMIN)
                                .submitKeyName(SUBMIT_KEY)
                                .payingWith(PAYER)
                                .signedBy(PAYER, ADMIN)
                                .via(createTopicTxn)
                                .hasKnownStatus(BAD_ENCODING),
                        validateChargedUsdWithinWithTxnSize(
                                createTopicTxn,
                                txnSize -> expectedTopicCreateFullFeeUsd(Map.of(
                                        SIGNATURES, 2L,
                                        KEYS, 1L,
                                        PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateChargedAccount(createTopicTxn, PAYER));
            }

            @HapiTest
            @DisplayName("Create topic - with invalid key list signature fails on handle")
            final Stream<DynamicTest> createTopicWithInvalidKeyListSignatureFailsOnHandle() {
                return hapiTest(
                        newKeyNamed(SUBMIT_KEY),
                        newKeyNamed("firstKey"),
                        newKeyNamed("secondKey"),
                        newKeyListNamed(ADMIN_KEY, List.of("firstKey", "secondKey")),
                        cryptoCreate(ADMIN).key(ADMIN_KEY).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        createTopic("testTopic")
                                .blankMemo()
                                .adminKeyName(ADMIN)
                                .submitKeyName(SUBMIT_KEY)
                                .payingWith(PAYER)
                                .signedBy(PAYER, "firstKey")
                                .via(createTopicTxn)
                                .hasKnownStatus(INVALID_SIGNATURE),
                        validateChargedUsdWithinWithTxnSize(
                                createTopicTxn,
                                txnSize -> expectedTopicCreateFullFeeUsd(Map.of(
                                        SIGNATURES, 2L,
                                        KEYS, 3L,
                                        PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateChargedAccount(createTopicTxn, PAYER));
            }

            @HapiTest
            @DisplayName("Create topic - with admin, submit key and invalid auto-renew account fails on handle")
            final Stream<DynamicTest> createTopicWithAdminSubmitKeyAndInvalidAutoRenewAccountFailsOnHandle() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(AUTO_RENEW_ACCOUNT).balance(ONE_HBAR),
                        newKeyNamed(ADMIN),
                        newKeyNamed(SUBMIT_KEY),
                        cryptoDelete(AUTO_RENEW_ACCOUNT),
                        createTopic("testTopic")
                                .memo("testMemo")
                                .autoRenewAccountId(AUTO_RENEW_ACCOUNT)
                                .adminKeyName(ADMIN)
                                .submitKeyName(SUBMIT_KEY)
                                .payingWith(PAYER)
                                .signedBy(PAYER, ADMIN, AUTO_RENEW_ACCOUNT)
                                .via(createTopicTxn)
                                .hasKnownStatus(INVALID_AUTORENEW_ACCOUNT),
                        validateChargedUsdWithinWithTxnSize(
                                createTopicTxn,
                                txnSize -> expectedTopicCreateFullFeeUsd(Map.of(
                                        SIGNATURES, 3L,
                                        KEYS, 2L,
                                        PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateChargedAccount(createTopicTxn, PAYER));
            }
        }
    }
}
