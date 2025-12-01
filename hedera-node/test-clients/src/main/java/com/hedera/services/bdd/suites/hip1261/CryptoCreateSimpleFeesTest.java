// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip1261;

import static com.hedera.node.app.hapi.utils.CommonUtils.extractTransactionBody;
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
import static com.hedera.services.bdd.spec.transactions.TxnUtils.accountAllowanceHook;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uncheckedSubmit;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingHbar;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyListNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.usableTxnIdNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsdWithin;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.crypto.CryptoTransferSuite.sdec;
import static com.hedera.services.bdd.suites.hip1261.utils.CryptoSimpleFeesUsd.expectedCryptoCreateUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.CryptoSimpleFeesUsd.expectedNetworkFeeOnlyUsd;
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
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.HapiSpecOperation;
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

@Tag(SIMPLE_FEES)
@HapiTestLifecycle
public class CryptoCreateSimpleFeesTest {

    private static final String PAYER = "payer";
    private static final String ADMIN_KEY = "adminKey";
    private static final String PAYER_KEY = "payerKey";
    private static final String HOOK_CONTRACT = "TruePreHook";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(Map.of(
                "fees.simpleFeesEnabled", "true",
                "hooks.hooksEnabled", "true"));
    }

    @Nested
    @DisplayName("CryptoCreate Simple Fees Positive Test Cases")
    class CryptoCreateSimpleFeesPositiveTestCases {
        @HapiTest
        @DisplayName("CryptoCreate - base fees full charging without extras")
        Stream<DynamicTest> cryptoCreateWithIncludedSig() {
            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate("testAccount")
                            .payingWith(PAYER)
                            .signedBy(PAYER)
                            .fee(ONE_HBAR)
                            .via("cryptoCreateTxn"),
                    validateChargedUsdWithin("cryptoCreateTxn", expectedCryptoCreateUsd(1, 0), 0.0001));
        }

        @HapiTest
        @DisplayName("CryptoCreate - one included signature and one included key - full charging without extras")
        Stream<DynamicTest> cryptoCreateWithIncludedSigAndKey() {
            return hapiTest(
                    newKeyNamed(ADMIN_KEY),
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate("testAccount")
                            .payingWith(PAYER)
                            .key(ADMIN_KEY)
                            .signedBy(PAYER)
                            .fee(ONE_HBAR)
                            .via("cryptoCreateTxn"),
                    validateChargedUsdWithin("cryptoCreateTxn", expectedCryptoCreateUsd(1L, 1L), 0.0001));
        }

        @HapiTest
        @DisplayName("CryptoCreate - threshold with extra signatures and keys - full charging with extras")
        Stream<DynamicTest> cryptoCreateThresholdWithExtraSigAndKeys() {
            // Define a threshold submit key that requires two simple keys signatures
            KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);

            // Create a valid signature with both simple keys signing
            SigControl validSig = keyShape.signedWith(sigs(ON, ON));
            return hapiTest(
                    newKeyNamed(PAYER_KEY).shape(keyShape),
                    cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate("testAccount")
                            .key(PAYER_KEY)
                            .sigControl(forKey(PAYER_KEY, validSig))
                            .payingWith(PAYER)
                            .signedBy(PAYER)
                            .fee(ONE_HBAR)
                            .via("cryptoCreateTxn"),
                    validateChargedUsdWithin("cryptoCreateTxn", expectedCryptoCreateUsd(2L, 2L), 0.0001));
        }

        @HapiTest
        @DisplayName("CryptoCreate - threshold with two extra signatures and keys - full charging with extras")
        Stream<DynamicTest> cryptoCreateThresholdWithTwoExtraSigAndKeys() {
            // Define a threshold submit key that requires two simple keys signatures
            KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE, listOf(2));

            // Create a valid signature with both simple keys signing
            SigControl validSig = keyShape.signedWith(sigs(ON, OFF, sigs(ON, ON)));
            return hapiTest(
                    newKeyNamed(PAYER_KEY).shape(keyShape),
                    cryptoCreate(PAYER)
                            .key(PAYER_KEY)
                            .sigControl(forKey(PAYER_KEY, validSig))
                            .balance(ONE_HUNDRED_HBARS),
                    cryptoCreate("testAccount")
                            .memo("Test")
                            .key(PAYER_KEY)
                            .sigControl(forKey(PAYER_KEY, validSig))
                            .payingWith(PAYER)
                            .signedBy(PAYER)
                            .fee(ONE_HBAR)
                            .via("cryptoCreateTxn"),
                    validateChargedUsdWithin("cryptoCreateTxn", expectedCryptoCreateUsd(3L, 4L), 0.0001));
        }

        @HapiTest
        @DisplayName("CryptoCreate - key list extra signatures and keys - full charging with extras")
        Stream<DynamicTest> cryptoCreateKeyListExtraSigAndKeys() {
            return hapiTest(
                    newKeyNamed("firstKey"),
                    newKeyNamed("secondKey"),
                    newKeyListNamed(PAYER_KEY, List.of("firstKey", "secondKey")),
                    cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate("testAccount")
                            .key(PAYER_KEY)
                            .payingWith(PAYER)
                            .signedBy(PAYER)
                            .fee(ONE_HBAR)
                            .via("cryptoCreateTxn"),
                    validateChargedUsdWithin("cryptoCreateTxn", expectedCryptoCreateUsd(2L, 2L), 0.0001));
        }

        @HapiTest
        @DisplayName("CryptoCreate - with hook creation details - full charging without extras")
        Stream<DynamicTest> cryptoCreateWithIncludedSigAndHook() {
            return hapiTest(
                    uploadInitCode(HOOK_CONTRACT),
                    contractCreate(HOOK_CONTRACT).gas(5_000_000L),
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate("testAccount")
                            .withHooks(accountAllowanceHook(1L, HOOK_CONTRACT))
                            .payingWith(PAYER)
                            .signedBy(PAYER)
                            .fee(ONE_HUNDRED_HBARS)
                            .via("cryptoCreateTxn"),
                    validateChargedUsdWithin("cryptoCreateTxn", expectedCryptoCreateUsd(1L, 0L, 1L), 0.0001));
        }

        @HapiTest
        @DisplayName("CryptoCreate - with included hook, signature and key - full charging without extras")
        Stream<DynamicTest> cryptoCreateWithIncludedHookSigAndKey() {
            return hapiTest(
                    uploadInitCode(HOOK_CONTRACT),
                    contractCreate(HOOK_CONTRACT).gas(5_000_000L),
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    newKeyNamed(ADMIN_KEY),
                    cryptoCreate("testAccount")
                            .withHooks(accountAllowanceHook(1L, HOOK_CONTRACT))
                            .key(ADMIN_KEY)
                            .payingWith(PAYER)
                            .signedBy(PAYER)
                            .fee(ONE_HUNDRED_HBARS)
                            .via("cryptoCreateTxn"),
                    validateChargedUsdWithin("cryptoCreateTxn", expectedCryptoCreateUsd(1L, 1L, 1L), 0.0001));
        }

        @HapiTest
        @DisplayName("CryptoCreate - with extra hooks, signatures and keys - full charging without extras")
        Stream<DynamicTest> cryptoCreateWithExtraHookSigAndKey() {
            // Define a threshold submit key that requires two simple keys signatures
            KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);

            // Create a valid signature with both simple keys signing
            SigControl validSig = keyShape.signedWith(sigs(ON, ON));
            return hapiTest(
                    uploadInitCode(HOOK_CONTRACT),
                    contractCreate(HOOK_CONTRACT).gas(5_000_000L),
                    newKeyNamed(PAYER_KEY).shape(keyShape),
                    cryptoCreate(PAYER)
                            .key(PAYER_KEY)
                            .sigControl(forKey(PAYER_KEY, validSig))
                            .balance(ONE_HUNDRED_HBARS),
                    cryptoCreate("testAccount")
                            .memo("Test")
                            .key(PAYER_KEY)
                            .sigControl(forKey(PAYER_KEY, validSig))
                            .withHooks(accountAllowanceHook(2L, HOOK_CONTRACT), accountAllowanceHook(3L, HOOK_CONTRACT))
                            .payingWith(PAYER)
                            .signedBy(PAYER_KEY)
                            .fee(ONE_HUNDRED_HBARS)
                            .via("cryptoCreateTxn"),
                    validateChargedUsdWithin("cryptoCreateTxn", expectedCryptoCreateUsd(2L, 2L, 2L), 0.0001));
        }
    }

    @Nested
    @DisplayName("CryptoCreate Simple Fees Negative and Corner Test Cases")
    class CryptoCreateSimpleFeesNegativeAndCornerTestCases {
        @Nested
        @DisplayName("CryptoCreate Simple Fees Failures on Ingest")
        class CryptoCreateSimpleFeesFailuresOnIngest {
            @HapiTest
            @DisplayName("CryptoCreate - threshold with extra signatures and keys - invalid signature fails on ingest")
            Stream<DynamicTest> cryptoCreateThresholdWithExtraSigAndKeysInvalidSignatureFailsOnIngest() {
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();

                // Define a threshold submit key that requires two simple keys signatures
                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);

                // Create invalid signature with both simple keys signing
                SigControl invalidSig = keyShape.signedWith(sigs(ON, OFF));
                return hapiTest(
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                        cryptoCreate("testAccount")
                                .key(PAYER_KEY)
                                .sigControl(forKey(PAYER_KEY, invalidSig))
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .fee(ONE_HBAR)
                                .via("cryptoCreateTxn")
                                .hasPrecheck(INVALID_SIGNATURE),

                        // assert no txn record is created
                        getTxnRecord("cryptoCreateTxn").logged().hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND),

                        // Save balances and assert changes
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        withOpContext((spec, log) -> {
                            assertEquals(initialBalance.get(), afterBalance.get());
                        }));
            }

            @HapiTest
            @DisplayName(
                    "CryptoCreate - threshold with two extra signatures and keys - invalid signature fails on ingest")
            Stream<DynamicTest> cryptoCreateThresholdWithTwoExtraSigAndKeysInvalidSignatureFailsOnIngest() {
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();

                // Define a threshold submit key that requires two simple keys signatures
                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE, listOf(2));

                // Create invalid signature with both simple keys signing
                SigControl invalidSig = keyShape.signedWith(sigs(ON, OFF, sigs(OFF, OFF)));
                return hapiTest(
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(PAYER)
                                .key(PAYER_KEY)
                                .sigControl(forKey(PAYER_KEY, invalidSig))
                                .balance(ONE_HUNDRED_HBARS),
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                        cryptoCreate("testAccount")
                                .key(PAYER_KEY)
                                .sigControl(forKey(PAYER_KEY, invalidSig))
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .fee(ONE_HBAR)
                                .via("cryptoCreateTxn")
                                .hasPrecheck(INVALID_SIGNATURE),

                        // assert no txn record is created
                        getTxnRecord("cryptoCreateTxn").logged().hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND),

                        // Save balances and assert changes
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        withOpContext((spec, log) -> {
                            assertEquals(initialBalance.get(), afterBalance.get());
                        }));
            }

            @HapiTest
            @DisplayName("CryptoCreate - key list extra signatures and keys - invalid signature fails on ingest")
            Stream<DynamicTest> cryptoCreateKeyListExtraSigAndKeysInvalidSignatureFailsOnIngest() {
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();
                return hapiTest(
                        newKeyNamed("firstKey"),
                        newKeyNamed("secondKey"),
                        newKeyListNamed(PAYER_KEY, List.of("firstKey", "secondKey")),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                        cryptoCreate("testAccount")
                                .key(PAYER_KEY)
                                .payingWith(PAYER)
                                .signedBy("firstKey")
                                .fee(ONE_HBAR)
                                .via("cryptoCreateTxn")
                                .hasPrecheck(INVALID_SIGNATURE),

                        // assert no txn record is created
                        getTxnRecord("cryptoCreateTxn").logged().hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND),

                        // Save balances and assert changes
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        withOpContext((spec, log) -> {
                            assertEquals(initialBalance.get(), afterBalance.get());
                        }));
            }

            @HapiTest
            @DisplayName("CryptoCreate with insufficient txn fee fails on ingest")
            Stream<DynamicTest> cryptoCreateWithInsufficientTxnFeeFailsOnIngest() {
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();

                // Define a threshold submit key that requires two simple keys signatures
                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);

                // Create a valid signature with both simple keys signing
                SigControl validSig = keyShape.signedWith(sigs(ON, ON));
                return hapiTest(
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                        cryptoCreate("testAccount")
                                .key(PAYER_KEY)
                                .sigControl(forKey(PAYER_KEY, validSig))
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .fee(ONE_HBAR / 100000) // fee is too low
                                .via("cryptoCreateTxn")
                                .hasPrecheck(INSUFFICIENT_TX_FEE),

                        // assert no txn record is created
                        getTxnRecord("cryptoCreateTxn").logged().hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND),

                        // Save balances and assert changes
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        withOpContext((spec, log) -> {
                            assertEquals(initialBalance.get(), afterBalance.get());
                        }));
            }

            @HapiTest
            @DisplayName("CryptoCreate - with insufficient payer balance fails on ingest")
            Stream<DynamicTest> cryptoCreateWithInsufficientPayerBalanceFailsOnIngest() {
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();

                // Define a threshold submit key that requires two simple keys signatures
                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);

                // Create a valid signature with both simple keys signing
                SigControl validSig = keyShape.signedWith(sigs(ON, ON));
                return hapiTest(
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HBAR / 100000),
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                        cryptoCreate("testAccount")
                                .key(PAYER_KEY)
                                .sigControl(forKey(PAYER_KEY, validSig))
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .fee(ONE_HBAR)
                                .via("cryptoCreateTxn")
                                .hasPrecheck(INSUFFICIENT_PAYER_BALANCE),

                        // assert no txn record is created
                        getTxnRecord("cryptoCreateTxn").logged().hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND),

                        // Save balances and assert changes
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        withOpContext((spec, log) -> {
                            assertEquals(initialBalance.get(), afterBalance.get());
                        }));
            }

            @HapiTest
            @DisplayName("CryptoCreate - with too long memo fails on ingest")
            Stream<DynamicTest> cryptoCreateWithTooLongMemoFailsOnIngest() {
                final var LONG_MEMO = "x".repeat(1025); // memo exceeds 1024 bytes limit
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();

                // Define a threshold submit key that requires two simple keys signatures
                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);

                // Create a valid signature with both simple keys signing
                SigControl validSig = keyShape.signedWith(sigs(ON, ON));
                return hapiTest(
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                        cryptoCreate("testAccount")
                                .memo(LONG_MEMO)
                                .key(PAYER_KEY)
                                .sigControl(forKey(PAYER_KEY, validSig))
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .fee(ONE_HBAR)
                                .via("cryptoCreateTxn")
                                .hasPrecheck(MEMO_TOO_LONG),

                        // assert no txn record is created
                        getTxnRecord("cryptoCreateTxn").logged().hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND),

                        // Save balances and assert changes
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        withOpContext((spec, log) -> {
                            assertEquals(initialBalance.get(), afterBalance.get());
                        }));
            }

            @HapiTest
            @DisplayName("CryptoCreate - expired transaction fails on ingest")
            Stream<DynamicTest> cryptoCreateExpiredTransactionFailsOnIngest() {
                final var expiredTxnId = "expiredCreateTopic";
                final var oneHourPast = -3_600L; // 1 hour before
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();

                // Define a threshold submit key that requires two simple keys signatures
                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);

                // Create a valid signature with both simple keys signing
                SigControl validSig = keyShape.signedWith(sigs(ON, ON));
                return hapiTest(
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                        usableTxnIdNamed(expiredTxnId)
                                .modifyValidStart(oneHourPast)
                                .payerId(PAYER),
                        cryptoCreate("testAccount")
                                .key(PAYER_KEY)
                                .sigControl(forKey(PAYER_KEY, validSig))
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .fee(ONE_HBAR)
                                .txnId(expiredTxnId)
                                .via("cryptoCreateTxn")
                                .hasPrecheck(TRANSACTION_EXPIRED),

                        // assert no txn record is created
                        getTxnRecord("cryptoCreateTxn").logged().hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND),

                        // Save balances and assert changes
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        withOpContext((spec, log) -> {
                            assertEquals(initialBalance.get(), afterBalance.get());
                        }));
            }

            @HapiTest
            @DisplayName("CryptoCreate - with too far start time fails on ingest")
            Stream<DynamicTest> cryptoCreateWithTooFarStartTimeFailsOnIngest() {
                final var expiredTxnId = "expiredCreateTopic";
                final var oneHourPast = 3_600L; // 1 hour later
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();

                // Define a threshold submit key that requires two simple keys signatures
                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);

                // Create a valid signature with both simple keys signing
                SigControl validSig = keyShape.signedWith(sigs(ON, ON));
                return hapiTest(
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                        usableTxnIdNamed(expiredTxnId)
                                .modifyValidStart(oneHourPast)
                                .payerId(PAYER),
                        cryptoCreate("testAccount")
                                .key(PAYER_KEY)
                                .sigControl(forKey(PAYER_KEY, validSig))
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .fee(ONE_HBAR)
                                .txnId(expiredTxnId)
                                .via("cryptoCreateTxn")
                                .hasPrecheck(INVALID_TRANSACTION_START),

                        // assert no txn record is created
                        getTxnRecord("cryptoCreateTxn").logged().hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND),

                        // Save balances and assert changes
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        withOpContext((spec, log) -> {
                            assertEquals(initialBalance.get(), afterBalance.get());
                        }));
            }

            @HapiTest
            @DisplayName("CryptoCreate - with invalid duration time fails on ingest")
            Stream<DynamicTest> cryptoCreateWithInvalidDurationTimeFailsOnIngest() {
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();

                // Define a threshold submit key that requires two simple keys signatures
                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);

                // Create a valid signature with both simple keys signing
                SigControl validSig = keyShape.signedWith(sigs(ON, ON));
                return hapiTest(
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                        cryptoCreate("testAccount")
                                .key(PAYER_KEY)
                                .sigControl(forKey(PAYER_KEY, validSig))
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .fee(ONE_HBAR)
                                .validDurationSecs(0) // invalid duration
                                .via("cryptoCreateTxn")
                                .hasPrecheck(INVALID_TRANSACTION_DURATION),

                        // assert no txn record is created
                        getTxnRecord("cryptoCreateTxn").logged().hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND),

                        // Save balances and assert changes
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        withOpContext((spec, log) -> {
                            assertEquals(initialBalance.get(), afterBalance.get());
                        }));
            }

            @HapiTest
            @DisplayName("CryptoCreate - duplicate txn fails on ingest")
            Stream<DynamicTest> cryptoCreateDuplicateTxnFailsOnIngest() {
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();

                // Define a threshold submit key that requires two simple keys signatures
                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);

                // Create a valid signature with both simple keys signing
                SigControl validSig = keyShape.signedWith(sigs(ON, ON));
                return hapiTest(
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                        // Successful first transaction
                        cryptoCreate("testAccount").fee(ONE_HBAR).via("cryptoCreateTxn"),
                        // Duplicate transaction
                        cryptoCreate("testAccountDuplicate")
                                .key(PAYER_KEY)
                                .sigControl(forKey(PAYER_KEY, validSig))
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .fee(ONE_HBAR)
                                .txnId("cryptoCreateTxn")
                                .via("cryptoCreateDuplicateTxn")
                                .hasPrecheck(DUPLICATE_TRANSACTION),

                        // Save balances and assert changes
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        withOpContext((spec, log) -> {
                            assertEquals(initialBalance.get(), afterBalance.get());
                        }));
            }
        }

        @Nested
        @DisplayName("CryptoCreate Simple Fees Failures on Pre-Handle")
        class CryptoCreateSimpleFailuresOnPreHandle {
            @HapiTest
            @DisplayName("CryptoCreate with invalid signature fails on pre-handle")
            Stream<DynamicTest> cryptoCreateWithInvalidSignatureFailsOnPreHandleNetworkFeeChargedOnly() {
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();
                final AtomicLong initialNodeBalance = new AtomicLong();
                final AtomicLong afterNodeBalance = new AtomicLong();

                final String INNER_ID = "crypto-create-txn-inner-id";
                final String ENVELOPE_ID = "crypto-create-txn-envelope-id";

                // Define a threshold submit key that requires two simple keys signatures
                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
                // Create a valid signature with both simple keys signing
                SigControl invalidSig = keyShape.signedWith(sigs(ON, OFF));

                return hapiTest(
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),

                        // Register a TxnId for the inner txn
                        usableTxnIdNamed(INNER_ID).payerId(PAYER),

                        // Save balances before
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                        cryptoTransfer(movingHbar(ONE_HBAR).between(GENESIS, "3")),
                        getAccountBalance("3").exposingBalanceTo(initialNodeBalance::set),
                        withOpContext((spec, log) -> {
                            final var innerTxn = cryptoCreate("testAccount")
                                    .key(PAYER_KEY)
                                    .sigControl(forKey(PAYER_KEY, invalidSig))
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
                            assertEquals(INVALID_PAYER_SIGNATURE, status, "Expected txn to fail but it succeeded");
                        }),

                        // Save balances after and assert payer was not charged
                        getTxnRecord(INNER_ID).assertingNothingAboutHashes().logged(),
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        getAccountBalance("3").exposingBalanceTo(afterNodeBalance::set),
                        withOpContext((spec, log) -> {
                            long nodeDelta = initialNodeBalance.get() - afterNodeBalance.get();
                            log.info("Node balance change: {}", nodeDelta);
                            log.info("Recorded fee: {}", expectedNetworkFeeOnlyUsd(2));
                            assertEquals(initialBalance.get(), afterBalance.get());
                            assertTrue(initialNodeBalance.get() > afterNodeBalance.get());
                        }),
                        validateNodeOnlyCharged(
                                INNER_ID, initialNodeBalance, afterNodeBalance, expectedNetworkFeeOnlyUsd(1L), 0.01));
            }

            @HapiTest
            @DisplayName("CryptoCreate with insufficient txn fee fails on pre-handle")
            Stream<DynamicTest> cryptoCreateWithInsufficientTxnFeeFailsOnPreHandleNetworkFeeChargedOnly() {
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();
                final AtomicLong initialNodeBalance = new AtomicLong();
                final AtomicLong afterNodeBalance = new AtomicLong();

                final String INNER_ID = "crypto-create-txn-inner-id";
                final String ENVELOPE_ID = "crypto-create-txn-envelope-id";

                // Define a threshold submit key that requires two simple keys signatures
                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
                // Create a valid signature with both simple keys signing
                SigControl validSig = keyShape.signedWith(sigs(ON, ON));

                return hapiTest(
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),

                        // Register a TxnId for the inner txn
                        usableTxnIdNamed(INNER_ID).payerId(PAYER),

                        // Save balances before
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                        cryptoTransfer(movingHbar(ONE_HBAR).between(GENESIS, "3")),
                        getAccountBalance("3").exposingBalanceTo(initialNodeBalance::set),
                        withOpContext((spec, log) -> {
                            final var innerTxn = cryptoCreate("testAccount")
                                    .key(PAYER_KEY)
                                    .sigControl(forKey(PAYER_KEY, validSig))
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
                        getTxnRecord(INNER_ID).assertingNothingAboutHashes().logged(),
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        getAccountBalance("3").exposingBalanceTo(afterNodeBalance::set),
                        withOpContext((spec, log) -> {
                            long nodeDelta = initialNodeBalance.get() - afterNodeBalance.get();
                            log.info("Node balance change: {}", nodeDelta);
                            log.info("Recorded fee: {}", expectedNetworkFeeOnlyUsd(2));
                            assertEquals(initialBalance.get(), afterBalance.get());
                            assertTrue(initialNodeBalance.get() > afterNodeBalance.get());
                        }),
                        validateNodeOnlyCharged(
                                INNER_ID, initialNodeBalance, afterNodeBalance, expectedNetworkFeeOnlyUsd(2L), 0.01));
            }

            @HapiTest
            @DisplayName("CryptoCreate with insufficient payer balance fails on pre-handle")
            Stream<DynamicTest> cryptoCreateWithInsufficientPayerBalanceFailsOnPreHandleNetworkFeeChargedOnly() {
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();
                final AtomicLong initialNodeBalance = new AtomicLong();
                final AtomicLong afterNodeBalance = new AtomicLong();

                final String INNER_ID = "crypto-create-txn-inner-id";
                final String ENVELOPE_ID = "crypto-create-txn-envelope-id";

                // Define a threshold submit key that requires two simple keys signatures
                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
                // Create a valid signature with both simple keys signing
                SigControl validSig = keyShape.signedWith(sigs(ON, ON));

                return hapiTest(
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HBAR / 100000),

                        // Register a TxnId for the inner txn
                        usableTxnIdNamed(INNER_ID).payerId(PAYER),

                        // Save balances before
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                        cryptoTransfer(movingHbar(ONE_HBAR).between(GENESIS, "3")),
                        getAccountBalance("3").exposingBalanceTo(initialNodeBalance::set),
                        withOpContext((spec, log) -> {
                            final var innerTxn = cryptoCreate("testAccount")
                                    .key(PAYER_KEY)
                                    .sigControl(forKey(PAYER_KEY, validSig))
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
                            assertEquals(INSUFFICIENT_PAYER_BALANCE, status, "Expected txn to fail but it succeeded");
                        }),

                        // Save balances after and assert payer was not charged
                        getTxnRecord(INNER_ID).assertingNothingAboutHashes().logged(),
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        getAccountBalance("3").exposingBalanceTo(afterNodeBalance::set),
                        withOpContext((spec, log) -> {
                            long nodeDelta = initialNodeBalance.get() - afterNodeBalance.get();
                            log.info("Node balance change: {}", nodeDelta);
                            log.info("Recorded fee: {}", expectedNetworkFeeOnlyUsd(2));
                            assertEquals(initialBalance.get(), afterBalance.get());
                            assertTrue(initialNodeBalance.get() > afterNodeBalance.get());
                        }),
                        validateNodeOnlyCharged(
                                INNER_ID, initialNodeBalance, afterNodeBalance, expectedNetworkFeeOnlyUsd(2L), 0.01));
            }

            @HapiTest
            @DisplayName("CryptoCreate with too long memo fails on pre-handle")
            Stream<DynamicTest> cryptoCreateWithTooLongMemoFailsOnPreHandleNetworkFeeChargedOnly() {
                final var LONG_MEMO = "x".repeat(1025); // memo exceeds 1024 bytes limit
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();
                final AtomicLong initialNodeBalance = new AtomicLong();
                final AtomicLong afterNodeBalance = new AtomicLong();

                final String INNER_ID = "crypto-create-txn-inner-id";
                final String ENVELOPE_ID = "crypto-create-txn-envelope-id";

                // Define a threshold submit key that requires two simple keys signatures
                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
                // Create a valid signature with both simple keys signing
                SigControl validSig = keyShape.signedWith(sigs(ON, ON));

                return hapiTest(
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),

                        // Register a TxnId for the inner txn
                        usableTxnIdNamed(INNER_ID).payerId(PAYER),

                        // Save balances before
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                        cryptoTransfer(movingHbar(ONE_HBAR).between(GENESIS, "3")),
                        getAccountBalance("3").exposingBalanceTo(initialNodeBalance::set),
                        withOpContext((spec, log) -> {
                            final var innerTxn = cryptoCreate("testAccount")
                                    .memo(LONG_MEMO)
                                    .key(PAYER_KEY)
                                    .sigControl(forKey(PAYER_KEY, validSig))
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

                        // Save balances after and assert payer was not charged
                        getTxnRecord(INNER_ID).assertingNothingAboutHashes().logged(),
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        getAccountBalance("3").exposingBalanceTo(afterNodeBalance::set),
                        withOpContext((spec, log) -> {
                            long nodeDelta = initialNodeBalance.get() - afterNodeBalance.get();
                            log.info("Node balance change: {}", nodeDelta);
                            log.info("Recorded fee: {}", expectedNetworkFeeOnlyUsd(1));
                            assertEquals(initialBalance.get(), afterBalance.get());
                            assertTrue(initialNodeBalance.get() > afterNodeBalance.get());
                        }),
                        validateNodeOnlyCharged(
                                INNER_ID, initialNodeBalance, afterNodeBalance, expectedNetworkFeeOnlyUsd(1L), 0.01));
            }

            @HapiTest
            @DisplayName("CryptoCreate expired transaction fails on pre-handle")
            Stream<DynamicTest> cryptoCreateExpiredTransactionFailsOnPreHandleNetworkFeeChargedOnly() {
                final var oneHourPast = -3_600L; // 1 hour before
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();
                final AtomicLong initialNodeBalance = new AtomicLong();
                final AtomicLong afterNodeBalance = new AtomicLong();

                final String INNER_ID = "crypto-create-txn-inner-id";
                final String ENVELOPE_ID = "crypto-create-txn-envelope-id";

                // Define a threshold submit key that requires two simple keys signatures
                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
                // Create a valid signature with both simple keys signing
                SigControl validSig = keyShape.signedWith(sigs(ON, ON));

                return hapiTest(
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),

                        // Register a TxnId for the inner txn
                        usableTxnIdNamed(INNER_ID).modifyValidStart(oneHourPast).payerId(PAYER),

                        // Save balances before
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                        cryptoTransfer(movingHbar(ONE_HBAR).between(GENESIS, "3")),
                        getAccountBalance("3").exposingBalanceTo(initialNodeBalance::set),
                        withOpContext((spec, log) -> {
                            final var innerTxn = cryptoCreate("testAccount")
                                    .key(PAYER_KEY)
                                    .sigControl(forKey(PAYER_KEY, validSig))
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

                        // Save balances after and assert payer was not charged
                        getTxnRecord(INNER_ID).assertingNothingAboutHashes().logged(),
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        getAccountBalance("3").exposingBalanceTo(afterNodeBalance::set),
                        withOpContext((spec, log) -> {
                            long nodeDelta = initialNodeBalance.get() - afterNodeBalance.get();
                            log.info("Node balance change: {}", nodeDelta);
                            log.info("Recorded fee: {}", expectedNetworkFeeOnlyUsd(2));
                            assertEquals(initialBalance.get(), afterBalance.get());
                            assertTrue(initialNodeBalance.get() > afterNodeBalance.get());
                        }),
                        validateNodeOnlyCharged(
                                INNER_ID, initialNodeBalance, afterNodeBalance, expectedNetworkFeeOnlyUsd(2L), 0.01));
            }

            @HapiTest
            @DisplayName("CryptoCreate with too far start time fails on pre-handle")
            Stream<DynamicTest> cryptoCreateWithTooFarStartTimeFailsOnPreHandleNetworkFeeChargedOnly() {
                final var oneHourPast = 3_600L; // 1 hour later
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();
                final AtomicLong initialNodeBalance = new AtomicLong();
                final AtomicLong afterNodeBalance = new AtomicLong();

                final String INNER_ID = "crypto-create-txn-inner-id";
                final String ENVELOPE_ID = "crypto-create-txn-envelope-id";

                // Define a threshold submit key that requires two simple keys signatures
                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
                // Create a valid signature with both simple keys signing
                SigControl validSig = keyShape.signedWith(sigs(ON, ON));

                return hapiTest(
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),

                        // Register a TxnId for the inner txn
                        usableTxnIdNamed(INNER_ID).modifyValidStart(oneHourPast).payerId(PAYER),

                        // Save balances before
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                        cryptoTransfer(movingHbar(ONE_HBAR).between(GENESIS, "3")),
                        getAccountBalance("3").exposingBalanceTo(initialNodeBalance::set),
                        withOpContext((spec, log) -> {
                            final var innerTxn = cryptoCreate("testAccount")
                                    .key(PAYER_KEY)
                                    .sigControl(forKey(PAYER_KEY, validSig))
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
                        getTxnRecord(INNER_ID).assertingNothingAboutHashes().logged(),
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        getAccountBalance("3").exposingBalanceTo(afterNodeBalance::set),
                        withOpContext((spec, log) -> {
                            long nodeDelta = initialNodeBalance.get() - afterNodeBalance.get();
                            log.info("Node balance change: {}", nodeDelta);
                            log.info("Recorded fee: {}", expectedNetworkFeeOnlyUsd(2));
                            assertEquals(initialBalance.get(), afterBalance.get());
                            assertTrue(initialNodeBalance.get() > afterNodeBalance.get());
                        }),
                        validateNodeOnlyCharged(
                                INNER_ID, initialNodeBalance, afterNodeBalance, expectedNetworkFeeOnlyUsd(2L), 0.01));
            }

            @HapiTest
            @DisplayName("CryptoCreate with invalid duration time fails on pre-handle")
            Stream<DynamicTest> cryptoCreateWithInvalidDurationTimeFailsOnPreHandleNetworkFeeChargedOnly() {
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();
                final AtomicLong initialNodeBalance = new AtomicLong();
                final AtomicLong afterNodeBalance = new AtomicLong();

                final String INNER_ID = "crypto-create-txn-inner-id";
                final String ENVELOPE_ID = "crypto-create-txn-envelope-id";

                // Define a threshold submit key that requires two simple keys signatures
                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
                // Create a valid signature with both simple keys signing
                SigControl validSig = keyShape.signedWith(sigs(ON, ON));

                return hapiTest(
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),

                        // Register a TxnId for the inner txn
                        usableTxnIdNamed(INNER_ID).payerId(PAYER),

                        // Save balances before
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                        cryptoTransfer(movingHbar(ONE_HBAR).between(GENESIS, "3")),
                        getAccountBalance("3").exposingBalanceTo(initialNodeBalance::set),
                        withOpContext((spec, log) -> {
                            final var innerTxn = cryptoCreate("testAccount")
                                    .key(PAYER_KEY)
                                    .sigControl(forKey(PAYER_KEY, validSig))
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
                        getTxnRecord(INNER_ID).assertingNothingAboutHashes().logged(),
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        getAccountBalance("3").exposingBalanceTo(afterNodeBalance::set),
                        withOpContext((spec, log) -> {
                            long nodeDelta = initialNodeBalance.get() - afterNodeBalance.get();
                            log.info("Node balance change: {}", nodeDelta);
                            log.info("Recorded fee: {}", expectedNetworkFeeOnlyUsd(2));
                            assertEquals(initialBalance.get(), afterBalance.get());
                            assertTrue(initialNodeBalance.get() > afterNodeBalance.get());
                        }),
                        validateNodeOnlyCharged(
                                INNER_ID, initialNodeBalance, afterNodeBalance, expectedNetworkFeeOnlyUsd(2L), 0.01));
            }
        }

        @Nested
        @DisplayName("Corner Cases for CryptoCreate Simple Fees")
        class CornerCasesForCryptoCreateSimpleFees {
            @HapiTest
            @DisplayName(
                    "CryptoCreate - additional not required signature is not charged - full charging without extras")
            Stream<DynamicTest> cryptoCreateOneAdditionalSigIsNotCharged() {
                return hapiTest(
                        newKeyNamed(ADMIN_KEY),
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate("testAccount")
                                .payingWith(PAYER)
                                .key(ADMIN_KEY)
                                .signedBy(PAYER, ADMIN_KEY)
                                .fee(ONE_HBAR)
                                .via("cryptoCreateTxn"),
                        validateChargedUsdWithin("cryptoCreateTxn", expectedCryptoCreateUsd(1L, 1L), 0.0001));
            }

            @HapiTest
            @DisplayName(
                    "CryptoCreate - multiple additional not required signatures are not charged - full charging without extras")
            Stream<DynamicTest> cryptoCreateMultipleAdditionalSigIsNotCharged() {
                return hapiTest(
                        newKeyNamed(ADMIN_KEY),
                        newKeyNamed("extraKey1"),
                        newKeyNamed("extraKey2"),
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate("testAccount")
                                .payingWith(PAYER)
                                .key(ADMIN_KEY)
                                .signedBy(PAYER, ADMIN_KEY, "extraKey1", "extraKey2")
                                .fee(ONE_HBAR)
                                .via("cryptoCreateTxn"),
                        validateChargedUsdWithin("cryptoCreateTxn", expectedCryptoCreateUsd(1L, 1L), 0.0001));
            }

            @HapiTest
            @DisplayName(
                    "CryptoCreate - threshold payer key with multiple additional not required signatures are not charged - full charging without extras")
            Stream<DynamicTest> cryptoCreateWithThresholdKeyAndMultipleAdditionalSigIsNotCharged() {
                // Define a threshold submit key that requires two simple keys signatures
                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);

                // Create a valid signature with both simple keys signing
                SigControl validSig = keyShape.signedWith(sigs(ON, ON));
                return hapiTest(
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        newKeyNamed("extraKey1"),
                        newKeyNamed("extraKey2"),
                        cryptoCreate(PAYER)
                                .key(PAYER_KEY)
                                .sigControl(forKey(PAYER_KEY, validSig))
                                .balance(ONE_HUNDRED_HBARS),
                        cryptoCreate("testAccount")
                                .key(PAYER_KEY)
                                .sigControl(forKey(PAYER_KEY, validSig))
                                .payingWith(PAYER)
                                .signedBy(PAYER_KEY, "extraKey1", "extraKey2")
                                .fee(ONE_HBAR)
                                .via("cryptoCreateTxn"),
                        validateChargedUsdWithin("cryptoCreateTxn", expectedCryptoCreateUsd(2L, 2L), 0.0001));
            }
        }
    }

    public static HapiSpecOperation validateNodeOnlyCharged(
            String txnId,
            AtomicLong initialNodeBalance,
            AtomicLong afterNodeBalance,
            double expectedUsd,
            double allowedPercentDifference) {
        return withOpContext((spec, log) -> {

            // Calculate actual node fee in tinybars (negative delta)
            final long initialNodeBalanceTinybars = initialNodeBalance.get();
            final long afterNodeBalanceTinybars = afterNodeBalance.get();
            final long nodeDeltaTinybars = initialNodeBalanceTinybars - afterNodeBalanceTinybars;

            log.info("---- Node-only fee validation ----");
            log.info("Node balance before (tinybars): {}", initialNodeBalanceTinybars);
            log.info("Node balance after (tinybars): {}", afterNodeBalanceTinybars);
            log.info("Node delta (tinybars): {}", nodeDeltaTinybars);

            if (nodeDeltaTinybars <= 0) {
                throw new AssertionError("Node was not charged — delta: " + nodeDeltaTinybars);
            }

            // Fetch the inner record to get the exchange rate
            final var subOp = getTxnRecord(txnId).assertingNothingAboutHashes();
            allRunFor(spec, subOp);
            final var record = subOp.getResponseRecord();

            log.info("Inner txn status: {}", record.getReceipt().getStatus());

            final var rate = record.getReceipt().getExchangeRate().getCurrentRate();
            final long hbarEquiv = rate.getHbarEquiv();
            final long centEquiv = rate.getCentEquiv();

            // Convert tinybars to USD
            final double nodeChargedUsd = (1.0 * nodeDeltaTinybars)
                    / ONE_HBAR // tinybars -> HBAR
                    / hbarEquiv // HBAR -> "rate HBAR"
                    * centEquiv // "rate HBAR" -> cents
                    / 100.0; // cents -> USD

            log.info("ExchangeRate current: hbarEquiv={}, centEquiv={}", hbarEquiv, centEquiv);
            log.info("Node charged (approx) USD = {}", nodeChargedUsd);
            log.info("Expected node USD fee    = {}", expectedUsd);

            final double diff = Math.abs(nodeChargedUsd - expectedUsd);
            final double pctDiff = (expectedUsd == 0.0)
                    ? (nodeChargedUsd == 0.0 ? 0.0 : Double.POSITIVE_INFINITY)
                    : (diff / expectedUsd) * 100.0;

            log.info("Node fee difference: abs={} USD, pct={}%", diff, pctDiff);

            assertEquals(
                    expectedUsd,
                    nodeChargedUsd,
                    (allowedPercentDifference / 100.0) * expectedUsd,
                    String.format(
                            "%s fee (%s) more than %.2f percent different than expected!",
                            sdec(nodeChargedUsd, 4), txnId, allowedPercentDifference));
        });
    }

    /** tinycents -> USD */
    public static double tinycentsToUsd(long tinycents) {
        return tinycents / 100_000_000.0 / 100.0;
    }
}
