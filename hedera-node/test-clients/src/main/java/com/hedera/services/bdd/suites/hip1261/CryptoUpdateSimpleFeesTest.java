// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip1261;

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
import static com.hedera.services.bdd.spec.transactions.TxnUtils.accountAllowanceHook;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingHbar;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.assertionsHold;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyListNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.usableTxnIdNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedAccount;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedCryptoUpdateFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.signedTxnSizeFor;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.validateChargedUsdWithinWithTxnSize;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.NODE_INCLUDED_BYTES;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_DELETED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.DUPLICATE_TRANSACTION;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_PAYER_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_TX_FEE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ADMIN_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TRANSACTION_DURATION;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TRANSACTION_START;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.MEMO_TOO_LONG;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.RECORD_NOT_FOUND;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TRANSACTION_EXPIRED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TRANSACTION_OVERSIZE;
import static org.hiero.hapi.support.fees.Extra.HOOK_EXECUTION;
import static org.hiero.hapi.support.fees.Extra.KEYS;
import static org.hiero.hapi.support.fees.Extra.PROCESSING_BYTES;
import static org.hiero.hapi.support.fees.Extra.SIGNATURES;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyHapiTest;
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

/**
 * Tests for CryptoUpdate simple fees.
 * Validates that fees are correctly calculated based on:
 * - Number of signatures (extras beyond included)
 * - Number of keys in the new key structure (extras beyond included)
 * - Hook updates (extras beyond included)
 * - Transaction processing bytes
 */
@Tag(SIMPLE_FEES)
@HapiTestLifecycle
public class CryptoUpdateSimpleFeesTest {
    private static final String PAYER = "payer";
    private static final String ACCOUNT = "account";
    private static final String NEW_KEY = "newKey";
    private static final String PAYER_KEY = "payerKey";
    private static final String cryptoUpdateTxn = "cryptoUpdateTxn";
    private static final String adminKey = "adminKey";
    private static final String HOOK_CONTRACT = "TruePreHook";
    private static final String DUPLICATE_TXN_ID = "duplicateTxnId";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(Map.of(
                "fees.simpleFeesEnabled", "true",
                "hooks.hooksEnabled", "true"));
    }

    @Nested
    @DisplayName("Crypto Update Positive Test Cases")
    class CryptoUpdatePositiveTestCases {

        @HapiTest
        @DisplayName("CryptoUpdate - update account memo - base fees charged")
        final Stream<DynamicTest> cryptoUpdateMemoOnlyBaseFeesCharged() {
            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    newKeyNamed(adminKey),
                    cryptoCreate(ACCOUNT).key(adminKey),
                    cryptoUpdate(ACCOUNT)
                            .entityMemo("updated memo")
                            .payingWith(PAYER)
                            .signedBy(PAYER, adminKey)
                            .via(cryptoUpdateTxn),
                    validateChargedUsdWithinWithTxnSize(
                            cryptoUpdateTxn,
                            txnSize -> expectedCryptoUpdateFullFeeUsd(
                                    Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(cryptoUpdateTxn, PAYER));
        }

        @HapiTest
        @DisplayName("CryptoUpdate - update account key - base fees charged")
        final Stream<DynamicTest> cryptoUpdateAccountKeyBaseFeesCharged() {
            return hapiTest(
                    newKeyNamed(NEW_KEY),
                    newKeyNamed(adminKey),
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(ACCOUNT).key(adminKey),
                    cryptoUpdate(ACCOUNT)
                            .key(NEW_KEY)
                            .payingWith(PAYER)
                            .signedBy(PAYER, adminKey, NEW_KEY)
                            .via(cryptoUpdateTxn),
                    validateChargedUsdWithinWithTxnSize(
                            cryptoUpdateTxn,
                            txnSize -> expectedCryptoUpdateFullFeeUsd(Map.of(
                                    SIGNATURES, 3L,
                                    KEYS, 1L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(cryptoUpdateTxn, PAYER));
        }

        @HapiTest
        @DisplayName("CryptoUpdate - threshold key account update - Full fees with extra signatures and keys charged")
        Stream<DynamicTest> cryptoUpdateThresholdKeyExtraSignaturesAndKeysCharged() {
            KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
            SigControl validSig = keyShape.signedWith(sigs(ON, ON));
            return hapiTest(
                    newKeyNamed(PAYER_KEY).shape(keyShape),
                    newKeyNamed(adminKey),
                    cryptoCreate(PAYER).key(adminKey).balance(ONE_HUNDRED_HBARS),
                    cryptoUpdate(PAYER)
                            .key(PAYER_KEY)
                            .sigControl(forKey(PAYER_KEY, validSig))
                            .payingWith(PAYER)
                            .signedBy(adminKey, PAYER_KEY)
                            .via(cryptoUpdateTxn),
                    validateChargedUsdWithinWithTxnSize(
                            cryptoUpdateTxn,
                            txnSize -> expectedCryptoUpdateFullFeeUsd(Map.of(
                                    SIGNATURES, 3L,
                                    KEYS, 2L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(cryptoUpdateTxn, PAYER));
        }

        @HapiTest
        @DisplayName(
                "CryptoUpdate - update account key with extra key list - Full fees with extra signatures and keys charged")
        final Stream<DynamicTest> cryptoUpdateWithExtraSignaturesAndKeysCharged() {
            KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE, listOf(2));
            SigControl validSig = keyShape.signedWith(sigs(ON, OFF, sigs(ON, ON)));
            return hapiTest(
                    newKeyNamed(PAYER_KEY).shape(keyShape),
                    newKeyNamed(adminKey),
                    cryptoCreate(PAYER).key(adminKey).balance(ONE_HUNDRED_HBARS),
                    cryptoUpdate(PAYER)
                            .key(PAYER_KEY)
                            .sigControl(forKey(PAYER_KEY, validSig))
                            .payingWith(PAYER)
                            .signedBy(adminKey, PAYER_KEY)
                            .via(cryptoUpdateTxn),
                    validateChargedUsdWithinWithTxnSize(
                            cryptoUpdateTxn,
                            txnSize -> expectedCryptoUpdateFullFeeUsd(Map.of(
                                    SIGNATURES, 4L,
                                    KEYS, 4L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(cryptoUpdateTxn, PAYER));
        }

        @HapiTest
        @DisplayName(
                "CryptoUpdate - account key update with key list - Full fees with extra signatures and keys charged")
        Stream<DynamicTest> cryptoUpdateAccountKeyWithKeyListExtraSignaturesAndKeysCharged() {
            return hapiTest(
                    newKeyNamed("firstKey"),
                    newKeyNamed("secondKey"),
                    newKeyNamed(adminKey),
                    newKeyListNamed(PAYER_KEY, List.of("firstKey", "secondKey")),
                    cryptoCreate(PAYER).key(adminKey).balance(ONE_HUNDRED_HBARS),
                    cryptoUpdate(PAYER)
                            .key(PAYER_KEY)
                            .payingWith(PAYER)
                            .signedBy(adminKey, PAYER_KEY)
                            .via(cryptoUpdateTxn),
                    validateChargedUsdWithinWithTxnSize(
                            cryptoUpdateTxn,
                            txnSize -> expectedCryptoUpdateFullFeeUsd(Map.of(
                                    SIGNATURES, 3L,
                                    KEYS, 2L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(cryptoUpdateTxn, PAYER));
        }

        @HapiTest
        @DisplayName("CryptoUpdate - update account with one hook - Full fees with hook extra charged")
        Stream<DynamicTest> cryptoUpdateWithOneHookFullFeesWithExtraCharged() {
            return hapiTest(
                    uploadInitCode(HOOK_CONTRACT),
                    contractCreate(HOOK_CONTRACT).gas(5_000_000L),
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    cryptoUpdate(PAYER)
                            .withHooks(accountAllowanceHook(1L, HOOK_CONTRACT))
                            .payingWith(PAYER)
                            .signedBy(PAYER)
                            .via(cryptoUpdateTxn),
                    validateChargedUsdWithinWithTxnSize(
                            cryptoUpdateTxn,
                            txnSize -> expectedCryptoUpdateFullFeeUsd(Map.of(
                                    SIGNATURES, 1L,
                                    KEYS, 0L,
                                    HOOK_EXECUTION, 1L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(cryptoUpdateTxn, PAYER));
        }

        @HapiTest
        @DisplayName("CryptoUpdate - update account with threshold key and extra hooks - Full fees with extras charged")
        Stream<DynamicTest> cryptoUpdateWithThresholdKeyAndExtraHooksSignaturesKeysAndHooksExtrasCharged() {
            KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
            SigControl validSig = keyShape.signedWith(sigs(ON, ON));
            return hapiTest(
                    newKeyNamed(adminKey),
                    uploadInitCode(HOOK_CONTRACT),
                    contractCreate(HOOK_CONTRACT).gas(5_000_000L),
                    newKeyNamed(PAYER_KEY).shape(keyShape),
                    cryptoCreate(PAYER).key(adminKey).balance(ONE_HUNDRED_HBARS),
                    cryptoUpdate(PAYER)
                            .key(PAYER_KEY)
                            .sigControl(forKey(PAYER_KEY, validSig))
                            .withHooks(accountAllowanceHook(1L, HOOK_CONTRACT), accountAllowanceHook(2L, HOOK_CONTRACT))
                            .payingWith(PAYER)
                            .signedBy(PAYER_KEY, adminKey)
                            .via(cryptoUpdateTxn),
                    validateChargedUsdWithinWithTxnSize(
                            cryptoUpdateTxn,
                            txnSize -> expectedCryptoUpdateFullFeeUsd(Map.of(
                                    SIGNATURES, 3L,
                                    KEYS, 2L,
                                    HOOK_EXECUTION, 2L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(cryptoUpdateTxn, PAYER));
        }

        @HapiTest
        @DisplayName(
                "CryptoUpdate - txn above NODE_INCLUDED_BYTES - Full fees with extra PROCESSING_BYTES fees charged")
        final Stream<DynamicTest> cryptoUpdateAboveProcessingBytesThresholdExtrasCharged() {
            final KeyShape largeKeyShape = threshOf(
                    1, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE,
                    SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE);
            return hapiTest(
                    newKeyNamed(adminKey),
                    newKeyNamed(NEW_KEY).shape(largeKeyShape),
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(ACCOUNT).key(adminKey).payingWith(PAYER),
                    cryptoUpdate(ACCOUNT)
                            .key(NEW_KEY)
                            .payingWith(PAYER)
                            .signedBy(PAYER, adminKey, NEW_KEY)
                            .via(cryptoUpdateTxn),
                    assertionsHold((spec, log) -> {
                        final int txnSize = signedTxnSizeFor(spec, cryptoUpdateTxn);
                        log.info("Large-key CryptoUpdate signed size: {} bytes", txnSize);
                        assertTrue(
                                txnSize > NODE_INCLUDED_BYTES,
                                "Expected txn size to exceed NODE_INCLUDED_BYTES (" + NODE_INCLUDED_BYTES + "), was "
                                        + txnSize);
                    }),
                    validateChargedUsdWithinWithTxnSize(
                            cryptoUpdateTxn,
                            txnSize -> expectedCryptoUpdateFullFeeUsd(Map.of(
                                    SIGNATURES, 22L,
                                    KEYS, 20L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(cryptoUpdateTxn, PAYER));
        }

        @HapiTest
        @DisplayName("CryptoUpdate - very large txn (just below 6KB) - full charging with extra PROCESSING_BYTES")
        final Stream<DynamicTest> cryptoUpdateVeryLargeTxnJustBelow6KBExtraCharged() {
            final KeyShape veryLargeKeyShape = threshOf(
                    1, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE,
                    SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE,
                    SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE,
                    SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE);
            return hapiTest(
                    newKeyNamed(adminKey),
                    newKeyNamed(PAYER_KEY).shape(veryLargeKeyShape),
                    cryptoCreate(PAYER).key(adminKey).balance(ONE_HUNDRED_HBARS),
                    cryptoUpdate(PAYER)
                            .key(PAYER_KEY)
                            .payingWith(PAYER)
                            .signedBy(adminKey, PAYER_KEY)
                            .via(cryptoUpdateTxn),
                    assertionsHold((spec, log) -> {
                        final int txnSize = signedTxnSizeFor(spec, cryptoUpdateTxn);
                        log.info("Very-large CryptoUpdate signed size: {} bytes", txnSize);
                        assertTrue(txnSize < 6_000, "Expected txn size (" + txnSize + ") to not exceed 6000 bytes");
                    }),
                    validateChargedUsdWithinWithTxnSize(
                            cryptoUpdateTxn,
                            txnSize -> expectedCryptoUpdateFullFeeUsd(Map.of(
                                    SIGNATURES, 42L,
                                    KEYS, 41L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(cryptoUpdateTxn, PAYER));
        }
    }

    @Nested
    @DisplayName("Crypto Update Negative Test Cases")
    class CryptoUpdateNegativeTestCases {

        @Nested
        @DisplayName("CryptoUpdate Simple Fees Failures on Ingest")
        class CryptoUpdateSimpleFeesFailuresOnIngest {

            @HapiTest
            @DisplayName("CryptoUpdate - threshold key with invalid signature - fails on ingest")
            Stream<DynamicTest> cryptoUpdateThresholdKeyInvalidSignatureFailsOnIngest() {
                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
                SigControl invalidSig = keyShape.signedWith(sigs(ON, OFF));
                return hapiTest(
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        cryptoUpdate(PAYER)
                                .entityMemo("update")
                                .sigControl(forKey(PAYER_KEY, invalidSig))
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .via(cryptoUpdateTxn)
                                .hasPrecheck(INVALID_SIGNATURE),

                        // assert no txn record is created
                        getTxnRecord(cryptoUpdateTxn).logged().hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("CryptoUpdate - threshold key with nested list - invalid signature fails on ingest")
            Stream<DynamicTest> cryptoUpdateThresholdWithListKeyInvalidSignatureFailsOnIngest() {
                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE, listOf(2));
                SigControl invalidSig = keyShape.signedWith(sigs(ON, OFF, sigs(OFF, OFF)));
                return hapiTest(
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(PAYER)
                                .key(PAYER_KEY)
                                .sigControl(forKey(PAYER_KEY, invalidSig))
                                .balance(ONE_HUNDRED_HBARS),
                        cryptoUpdate(PAYER)
                                .entityMemo("update")
                                .sigControl(forKey(PAYER_KEY, invalidSig))
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .via(cryptoUpdateTxn)
                                .hasPrecheck(INVALID_SIGNATURE),

                        // assert no txn record is created
                        getTxnRecord(cryptoUpdateTxn).logged().hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("CryptoUpdate - key list with missing signature - fails on ingest")
            Stream<DynamicTest> cryptoUpdateKeyListMissingSignatureFailsOnIngest() {
                return hapiTest(
                        newKeyNamed("firstKey"),
                        newKeyNamed("secondKey"),
                        newKeyListNamed(PAYER_KEY, List.of("firstKey", "secondKey")),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        cryptoUpdate(PAYER)
                                .entityMemo("update")
                                .payingWith(PAYER)
                                .signedBy("firstKey")
                                .via(cryptoUpdateTxn)
                                .hasPrecheck(INVALID_SIGNATURE),

                        // assert no txn record is created
                        getTxnRecord(cryptoUpdateTxn).logged().hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("CryptoUpdate - insufficient txn fee fails on ingest")
            Stream<DynamicTest> cryptoUpdateInsufficientTxFeeFailsOnIngest() {
                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
                SigControl validSig = keyShape.signedWith(sigs(ON, ON));
                return hapiTest(
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        cryptoUpdate(PAYER)
                                .entityMemo("update")
                                .sigControl(forKey(PAYER_KEY, validSig))
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .fee(ONE_HBAR / 100000)
                                .via(cryptoUpdateTxn)
                                .hasPrecheck(INSUFFICIENT_TX_FEE),

                        // assert no txn record is created
                        getTxnRecord(cryptoUpdateTxn).logged().hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("CryptoUpdate - insufficient payer balance fails on ingest")
            Stream<DynamicTest> cryptoUpdateInsufficientPayerBalanceFailsOnIngest() {
                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
                SigControl validSig = keyShape.signedWith(sigs(ON, ON));
                return hapiTest(
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HBAR / 100000),
                        cryptoUpdate(PAYER)
                                .entityMemo("update")
                                .sigControl(forKey(PAYER_KEY, validSig))
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .via(cryptoUpdateTxn)
                                .hasPrecheck(INSUFFICIENT_PAYER_BALANCE),

                        // assert no txn record is created
                        getTxnRecord(cryptoUpdateTxn).logged().hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("CryptoUpdate - expired transaction fails on ingest")
            Stream<DynamicTest> cryptoUpdateExpiredTransactionFailsOnIngest() {
                final var expiredTxnId = "expiredUpdateTxn";
                final var oneHourPast = -3_600L;
                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
                SigControl validSig = keyShape.signedWith(sigs(ON, ON));
                return hapiTest(
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        usableTxnIdNamed(expiredTxnId)
                                .modifyValidStart(oneHourPast)
                                .payerId(PAYER),
                        cryptoUpdate(PAYER)
                                .entityMemo("update")
                                .sigControl(forKey(PAYER_KEY, validSig))
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .txnId(expiredTxnId)
                                .via(cryptoUpdateTxn)
                                .hasPrecheck(TRANSACTION_EXPIRED),

                        // assert no txn record is created
                        getTxnRecord(cryptoUpdateTxn).logged().hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("CryptoUpdate - with too far start time fails on ingest")
            Stream<DynamicTest> cryptoUpdateTooFarStartTimeFailsOnIngest() {
                final var futureTxnId = "futureTxnId";
                final var oneHourAhead = 3_600L;
                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
                SigControl validSig = keyShape.signedWith(sigs(ON, ON));
                return hapiTest(
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        usableTxnIdNamed(futureTxnId)
                                .modifyValidStart(oneHourAhead)
                                .payerId(PAYER),
                        cryptoUpdate(PAYER)
                                .entityMemo("update")
                                .sigControl(forKey(PAYER_KEY, validSig))
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .txnId(futureTxnId)
                                .via(cryptoUpdateTxn)
                                .hasPrecheck(INVALID_TRANSACTION_START),

                        // assert no txn record is created
                        getTxnRecord(cryptoUpdateTxn).logged().hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("CryptoUpdate - with invalid duration fails on ingest")
            Stream<DynamicTest> cryptoUpdateInvalidDurationFailsOnIngest() {
                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
                SigControl validSig = keyShape.signedWith(sigs(ON, ON));
                return hapiTest(
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        cryptoUpdate(PAYER)
                                .entityMemo("update")
                                .sigControl(forKey(PAYER_KEY, validSig))
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .validDurationSecs(0)
                                .via(cryptoUpdateTxn)
                                .hasPrecheck(INVALID_TRANSACTION_DURATION),

                        // assert no txn record is created
                        getTxnRecord(cryptoUpdateTxn).logged().hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("CryptoUpdate - duplicate txn fails on ingest")
            Stream<DynamicTest> cryptoUpdateDuplicateTxnFailsOnIngest() {
                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
                SigControl validSig = keyShape.signedWith(sigs(ON, ON));
                return hapiTest(
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        // successful first transaction
                        cryptoUpdate(PAYER).entityMemo("first update").via(cryptoUpdateTxn),
                        // duplicate transaction reusing the same txnId
                        cryptoUpdate(PAYER)
                                .entityMemo("second update")
                                .sigControl(forKey(PAYER_KEY, validSig))
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .txnId(cryptoUpdateTxn)
                                .via("cryptoUpdateDuplicateTxn")
                                .hasPrecheck(DUPLICATE_TRANSACTION));
            }

            @HapiTest
            @DisplayName("CryptoUpdate - very large txn (above 6KB) - fails on ingest")
            final Stream<DynamicTest> cryptoUpdateVeryLargeTxnAboveSixKBFailsOnIngest() {
                // threshOf(1, 50×SIMPLE) — pushes serialized size above 6KB
                final KeyShape veryLargeKeyShape = threshOf(
                        1, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE,
                        SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE,
                        SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE,
                        SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE,
                        SIMPLE, SIMPLE, SIMPLE);
                return hapiTest(
                        newKeyNamed(PAYER_KEY).shape(veryLargeKeyShape),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        cryptoUpdate(PAYER)
                                .key(PAYER_KEY)
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .via(cryptoUpdateTxn)
                                .hasPrecheck(TRANSACTION_OVERSIZE),

                        // assert no txn record is created
                        getTxnRecord(cryptoUpdateTxn).logged().hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }
        }

        @Nested
        @DisplayName("CryptoUpdate Simple Fees Failures on Handle")
        class CryptoUpdateSimpleFeesFailuresOnHandle {
            @HapiTest
            @DisplayName("CryptoUpdate - empty threshold key - fails on handle - full fees charged")
            Stream<DynamicTest> cryptoUpdateEmptyThresholdKeyFailsOnHandle() {
                KeyShape keyShape = threshOf(0, 0);
                return hapiTest(
                        newKeyNamed(PAYER_KEY),
                        newKeyNamed(NEW_KEY).shape(keyShape),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        cryptoUpdate(PAYER)
                                .key(NEW_KEY)
                                .payingWith(PAYER)
                                .signedBy(PAYER_KEY, NEW_KEY)
                                .via(cryptoUpdateTxn)
                                .hasKnownStatus(INVALID_ADMIN_KEY),
                        validateChargedUsdWithinWithTxnSize(
                                cryptoUpdateTxn,
                                txnSize -> expectedCryptoUpdateFullFeeUsd(
                                        Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateChargedAccount(cryptoUpdateTxn, PAYER));
            }

            @HapiTest
            @DisplayName("CryptoUpdate - non-existent account fails on handle - full fees charged")
            Stream<DynamicTest> cryptoUpdateNonExistentAccountFailsOnHandle() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoUpdate("0.0.9999999")
                                .entityMemo("update non-existent")
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .via(cryptoUpdateTxn)
                                .hasKnownStatus(INVALID_ACCOUNT_ID),
                        validateChargedUsdWithinWithTxnSize(
                                cryptoUpdateTxn,
                                txnSize -> expectedCryptoUpdateFullFeeUsd(
                                        Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateChargedAccount(cryptoUpdateTxn, PAYER));
            }

            @HapiTest
            @DisplayName("CryptoUpdate - deleted account fails on handle - full fees charged")
            Stream<DynamicTest> cryptoUpdateDeletedAccountFailsOnHandle() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(adminKey),
                        cryptoCreate("toBeDeleted").key(adminKey).balance(ONE_HUNDRED_HBARS),
                        cryptoDelete("toBeDeleted").payingWith(PAYER),
                        cryptoUpdate("toBeDeleted")
                                .entityMemo("update deleted account")
                                .payingWith(PAYER)
                                .signedBy(PAYER, adminKey)
                                .via(cryptoUpdateTxn)
                                .hasKnownStatus(ACCOUNT_DELETED),
                        validateChargedUsdWithinWithTxnSize(
                                cryptoUpdateTxn,
                                txnSize -> expectedCryptoUpdateFullFeeUsd(
                                        Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateChargedAccount(cryptoUpdateTxn, PAYER));
            }

            @HapiTest
            @DisplayName("CryptoUpdate - empty nested threshold key fails on handle - full fees charged")
            Stream<DynamicTest> cryptoUpdateEmptyThresholdNestedKeyFailsOnHandle() {
                KeyShape keyShape = threshOf(3, listOf(0));
                return hapiTest(
                        newKeyNamed(PAYER_KEY),
                        newKeyNamed(NEW_KEY).shape(keyShape),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        cryptoUpdate(PAYER)
                                .key(NEW_KEY)
                                .payingWith(PAYER)
                                .signedBy(PAYER_KEY, NEW_KEY)
                                .via(cryptoUpdateTxn)
                                .hasKnownStatus(INVALID_ADMIN_KEY),
                        validateChargedUsdWithinWithTxnSize(
                                cryptoUpdateTxn,
                                txnSize -> expectedCryptoUpdateFullFeeUsd(
                                        Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateChargedAccount(cryptoUpdateTxn, PAYER));
            }

            @HapiTest
            @DisplayName("CryptoUpdate - memo too long fails on handle - full fees charged")
            Stream<DynamicTest> cryptoUpdateMemoTooLongFailsOnHandle() {
                final var LONG_MEMO = "x".repeat(1025); // exceeds 1024-byte limit
                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
                SigControl validSig = keyShape.signedWith(sigs(ON, ON));
                return hapiTest(
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        cryptoUpdate(PAYER)
                                .entityMemo(LONG_MEMO)
                                .sigControl(forKey(PAYER_KEY, validSig))
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .via(cryptoUpdateTxn)
                                .hasKnownStatus(MEMO_TOO_LONG),
                        validateChargedUsdWithinWithTxnSize(
                                cryptoUpdateTxn,
                                txnSize -> expectedCryptoUpdateFullFeeUsd(
                                        Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateChargedAccount(cryptoUpdateTxn, PAYER));
            }

            @Tag(ONLY_SUBPROCESS)
            @LeakyHapiTest
            @DisplayName("CryptoUpdate - duplicate transaction fails on handle - payer charged full fee")
            Stream<DynamicTest> cryptoUpdateWithDuplicateTransactionFailsOnHandlePayerChargedFullFee() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoTransfer(movingHbar(ONE_HBAR).between(GENESIS, "3")),

                        // Register a TxnId for the duplicate txns
                        usableTxnIdNamed(DUPLICATE_TXN_ID).payerId(PAYER),

                        // Submit duplicate transactions
                        cryptoUpdate(PAYER)
                                .entityMemo("first update")
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .setNode(4)
                                .txnId(DUPLICATE_TXN_ID)
                                .via(cryptoUpdateTxn)
                                .logged(),
                        cryptoUpdate(PAYER)
                                .entityMemo("duplicate update")
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .txnId(DUPLICATE_TXN_ID)
                                .setNode(3)
                                .via("cryptoUpdateDuplicateTxn")
                                .hasPrecheck(DUPLICATE_TRANSACTION),
                        validateChargedUsdWithinWithTxnSize(
                                cryptoUpdateTxn,
                                txnSize -> expectedCryptoUpdateFullFeeUsd(
                                        Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateChargedAccount(cryptoUpdateTxn, PAYER));
            }
        }
    }
}
