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
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingHbar;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.assertionsHold;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyListNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.usableTxnIdNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedAccount;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.allOnSigControl;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedCryptoDeleteFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.signedTxnSizeFor;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.thresholdKeyWithPrimitives;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.validateChargedUsdWithinWithTxnSize;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.NODE_INCLUDED_BYTES;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_DELETED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.DUPLICATE_TRANSACTION;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_PAYER_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_TX_FEE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TRANSACTION_DURATION;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TRANSACTION_START;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.RECORD_NOT_FOUND;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TRANSACTION_EXPIRED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TRANSACTION_OVERSIZE;
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
 * Tests for CryptoDelete simple fees.
 * Validates that fees are correctly calculated based on:
 * - Number of signatures (extras beyond included)
 * - Transaction processing bytes
 */
@Tag(SIMPLE_FEES)
@HapiTestLifecycle
public class CryptoDeleteSimpleFeesTest {

    private static final String PAYER = "payer";
    private static final String ACCOUNT = "account";
    private static final String BENEFICIARY = "beneficiary";
    private static final String PAYER_KEY = "payerKey";
    private static final String cryptoDeleteTxn = "cryptoDeleteTxn";
    private static final String DUPLICATE_TXN_ID = "duplicateTxnId";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(Map.of("fees.simpleFeesEnabled", "true"));
    }

    @Nested
    @DisplayName("CryptoDelete Simple Fees Positive Test Cases")
    class CryptoDeleteSimpleFeesPositiveTestCases {

        @HapiTest
        @DisplayName("CryptoDelete - simple key - base fees charged")
        final Stream<DynamicTest> cryptoDeleteSimpleKeyBaseFeesCharged() {
            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(BENEFICIARY).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(ACCOUNT).payingWith(PAYER),
                    cryptoDelete(ACCOUNT)
                            .transfer(BENEFICIARY)
                            .payingWith(PAYER)
                            .signedBy(PAYER, ACCOUNT)
                            .via(cryptoDeleteTxn),
                    validateChargedUsdWithinWithTxnSize(
                            cryptoDeleteTxn,
                            txnSize -> expectedCryptoDeleteFullFeeUsd(
                                    Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(cryptoDeleteTxn, PAYER));
        }

        @HapiTest
        @DisplayName("CryptoDelete - payer is the deleted account - base fees full charging")
        Stream<DynamicTest> cryptoDeleteSelfBaseFeesCharged() {
            return hapiTest(
                    cryptoCreate(BENEFICIARY).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    cryptoDelete(PAYER)
                            .transfer(BENEFICIARY)
                            .payingWith(PAYER)
                            .signedBy(PAYER)
                            .via(cryptoDeleteTxn),
                    validateChargedUsdWithinWithTxnSize(
                            cryptoDeleteTxn,
                            txnSize -> expectedCryptoDeleteFullFeeUsd(
                                    Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(cryptoDeleteTxn, PAYER));
        }

        @HapiTest
        @DisplayName("CryptoDelete - threshold key - all threshold key signatures charged")
        final Stream<DynamicTest> cryptoDeleteThresholdKeyAllSignaturesCharged() {
            final KeyShape threshKeyShape = threshOf(2, SIMPLE, SIMPLE, SIMPLE);
            return hapiTest(
                    newKeyNamed(PAYER_KEY).shape(threshKeyShape),
                    cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(BENEFICIARY).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(ACCOUNT).payingWith(PAYER),
                    cryptoDelete(ACCOUNT)
                            .transfer(BENEFICIARY)
                            .payingWith(PAYER)
                            .signedBy(PAYER, ACCOUNT)
                            .via(cryptoDeleteTxn),
                    validateChargedUsdWithinWithTxnSize(
                            cryptoDeleteTxn,
                            txnSize -> expectedCryptoDeleteFullFeeUsd(
                                    Map.of(SIGNATURES, 4L, PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(cryptoDeleteTxn, PAYER));
        }

        @HapiTest
        @DisplayName("CryptoDelete - payer has threshold key with nested list - extra signatures charged")
        Stream<DynamicTest> cryptoDeletePayerThresholdWithListKeyExtraSignaturesCharged() {
            KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE, listOf(2));
            SigControl validSig = keyShape.signedWith(sigs(ON, OFF, sigs(ON, ON)));
            return hapiTest(
                    cryptoCreate(BENEFICIARY).balance(ONE_HUNDRED_HBARS),
                    newKeyNamed(PAYER_KEY).shape(keyShape),
                    cryptoCreate(PAYER)
                            .key(PAYER_KEY)
                            .sigControl(forKey(PAYER_KEY, validSig))
                            .balance(ONE_HUNDRED_HBARS),
                    cryptoDelete(PAYER)
                            .transfer(BENEFICIARY)
                            .sigControl(forKey(PAYER_KEY, validSig))
                            .payingWith(PAYER)
                            .signedBy(PAYER)
                            .via(cryptoDeleteTxn),
                    validateChargedUsdWithinWithTxnSize(
                            cryptoDeleteTxn,
                            txnSize -> expectedCryptoDeleteFullFeeUsd(
                                    Map.of(SIGNATURES, 3L, PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(cryptoDeleteTxn, PAYER));
        }

        @HapiTest
        @DisplayName("CryptoDelete - payer has key list - extra signatures charged")
        Stream<DynamicTest> cryptoDeletePayerKeyListExtraSignaturesCharged() {
            // PAYER has listOf("firstKey", "secondKey") — both sign = 2 sigs → 1 extra
            return hapiTest(
                    cryptoCreate(BENEFICIARY).balance(ONE_HUNDRED_HBARS),
                    newKeyNamed("firstKey"),
                    newKeyNamed("secondKey"),
                    newKeyListNamed(PAYER_KEY, List.of("firstKey", "secondKey")),
                    cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                    cryptoDelete(PAYER)
                            .transfer(BENEFICIARY)
                            .payingWith(PAYER)
                            .signedBy(PAYER)
                            .via(cryptoDeleteTxn),
                    validateChargedUsdWithinWithTxnSize(
                            cryptoDeleteTxn,
                            txnSize -> expectedCryptoDeleteFullFeeUsd(
                                    Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(cryptoDeleteTxn, PAYER));
        }

        @HapiTest
        @DisplayName("CryptoDelete - signed txn above NODE_INCLUDED_BYTES threshold - extra PROCESSING_BYTES charged")
        final Stream<DynamicTest> cryptoDeleteAboveProcessingBytesThresholdExtraCharged() {
            return hapiTest(
                    cryptoCreate(BENEFICIARY).balance(ONE_HUNDRED_HBARS),
                    newKeyNamed(PAYER_KEY).shape(thresholdKeyWithPrimitives(20)),
                    cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                    cryptoDelete(PAYER)
                            .sigControl(forKey(PAYER_KEY, allOnSigControl(20)))
                            .transfer(BENEFICIARY)
                            .payingWith(PAYER)
                            .signedBy(PAYER)
                            .via(cryptoDeleteTxn),
                    assertionsHold((spec, log) -> {
                        final int txnSize = signedTxnSizeFor(spec, cryptoDeleteTxn);
                        log.info(
                                "Large-key CryptoDelete signed size: {} bytes (threshold: {})",
                                txnSize,
                                NODE_INCLUDED_BYTES);
                        assertTrue(
                                txnSize > NODE_INCLUDED_BYTES,
                                "Expected txn size (" + txnSize + ") to exceed NODE_INCLUDED_BYTES ("
                                        + NODE_INCLUDED_BYTES + ")");
                    }),
                    validateChargedUsdWithinWithTxnSize(
                            cryptoDeleteTxn,
                            txnSize -> expectedCryptoDeleteFullFeeUsd(
                                    Map.of(SIGNATURES, 20L, PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(cryptoDeleteTxn, PAYER));
        }

        @HapiTest
        @DisplayName("CryptoDelete - very large txn (just below 6KB) - full charging with extra PROCESSING_BYTES")
        final Stream<DynamicTest> cryptoDeleteVeryLargeTxnJustBelow6KBExtraCharged() {
            return hapiTest(
                    cryptoCreate(BENEFICIARY).balance(ONE_HUNDRED_HBARS),
                    newKeyNamed(PAYER_KEY).shape(thresholdKeyWithPrimitives(41)),
                    cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                    cryptoDelete(PAYER)
                            .sigControl(forKey(PAYER_KEY, allOnSigControl(41)))
                            .transfer(BENEFICIARY)
                            .payingWith(PAYER)
                            .signedBy(PAYER)
                            .via(cryptoDeleteTxn),
                    assertionsHold((spec, log) -> {
                        final int txnSize = signedTxnSizeFor(spec, cryptoDeleteTxn);
                        log.info("Very-large CryptoDelete signed size: {} bytes", txnSize);
                        assertTrue(txnSize < 6_000, "Expected txn size (" + txnSize + ") to not exceed 6000 bytes");
                    }),
                    validateChargedUsdWithinWithTxnSize(
                            cryptoDeleteTxn,
                            txnSize -> expectedCryptoDeleteFullFeeUsd(
                                    Map.of(SIGNATURES, 41L, PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(cryptoDeleteTxn, PAYER));
        }
    }

    @Nested
    @DisplayName("CryptoDelete Simple Fees Negative Test Cases")
    class CryptoDeleteSimpleFeesNegativeTestCases {

        @Nested
        @DisplayName("CryptoDelete Simple Fees Failures on Ingest")
        class CryptoDeleteSimpleFeesFailuresOnIngest {

            @HapiTest
            @DisplayName("CryptoDelete - threshold key with invalid signature - fails on ingest")
            Stream<DynamicTest> cryptoDeleteThresholdKeyInvalidSignatureFailsOnIngest() {
                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
                SigControl invalidSig = keyShape.signedWith(sigs(ON, OFF));
                return hapiTest(
                        cryptoCreate(BENEFICIARY).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        cryptoDelete(PAYER)
                                .transfer(BENEFICIARY)
                                .sigControl(forKey(PAYER_KEY, invalidSig))
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .via(cryptoDeleteTxn)
                                .hasPrecheck(INVALID_SIGNATURE),
                        getTxnRecord(cryptoDeleteTxn).logged().hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("CryptoDelete - threshold key with nested list - invalid signature fails on ingest")
            Stream<DynamicTest> cryptoDeleteThresholdWithListKeyInvalidSignatureFailsOnIngest() {
                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE, listOf(2));
                SigControl invalidSig = keyShape.signedWith(sigs(ON, OFF, sigs(OFF, OFF)));
                return hapiTest(
                        cryptoCreate(BENEFICIARY).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(PAYER)
                                .key(PAYER_KEY)
                                .sigControl(forKey(PAYER_KEY, invalidSig))
                                .balance(ONE_HUNDRED_HBARS),
                        cryptoDelete(PAYER)
                                .transfer(BENEFICIARY)
                                .sigControl(forKey(PAYER_KEY, invalidSig))
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .via(cryptoDeleteTxn)
                                .hasPrecheck(INVALID_SIGNATURE),
                        getTxnRecord(cryptoDeleteTxn).logged().hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("CryptoDelete - key list with missing signature - fails on ingest")
            Stream<DynamicTest> cryptoDeleteKeyListMissingSignatureFailsOnIngest() {
                return hapiTest(
                        cryptoCreate(BENEFICIARY).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed("firstKey"),
                        newKeyNamed("secondKey"),
                        newKeyListNamed(PAYER_KEY, List.of("firstKey", "secondKey")),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        cryptoDelete(PAYER)
                                .transfer(BENEFICIARY)
                                .payingWith(PAYER)
                                .signedBy("firstKey")
                                .via(cryptoDeleteTxn)
                                .hasPrecheck(INVALID_SIGNATURE),
                        getTxnRecord(cryptoDeleteTxn).logged().hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("CryptoDelete - insufficient txn fee fails on ingest")
            Stream<DynamicTest> cryptoDeleteInsufficientTxFeeFailsOnIngest() {
                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
                SigControl validSig = keyShape.signedWith(sigs(ON, ON));
                return hapiTest(
                        cryptoCreate(BENEFICIARY).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        cryptoDelete(PAYER)
                                .transfer(BENEFICIARY)
                                .sigControl(forKey(PAYER_KEY, validSig))
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .fee(ONE_HBAR / 100000)
                                .via(cryptoDeleteTxn)
                                .hasPrecheck(INSUFFICIENT_TX_FEE),
                        getTxnRecord(cryptoDeleteTxn).logged().hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("CryptoDelete - insufficient payer balance fails on ingest")
            Stream<DynamicTest> cryptoDeleteInsufficientPayerBalanceFailsOnIngest() {
                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
                SigControl validSig = keyShape.signedWith(sigs(ON, ON));
                return hapiTest(
                        cryptoCreate(BENEFICIARY).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HBAR / 100000),
                        cryptoDelete(PAYER)
                                .transfer(BENEFICIARY)
                                .sigControl(forKey(PAYER_KEY, validSig))
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .via(cryptoDeleteTxn)
                                .hasPrecheck(INSUFFICIENT_PAYER_BALANCE),
                        getTxnRecord(cryptoDeleteTxn).logged().hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("CryptoDelete - expired transaction fails on ingest")
            Stream<DynamicTest> cryptoDeleteExpiredTransactionFailsOnIngest() {
                final var expiredTxnId = "expiredDeleteTxn";
                final var oneHourPast = -3_600L;
                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
                SigControl validSig = keyShape.signedWith(sigs(ON, ON));
                return hapiTest(
                        cryptoCreate(BENEFICIARY).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        usableTxnIdNamed(expiredTxnId)
                                .modifyValidStart(oneHourPast)
                                .payerId(PAYER),
                        cryptoDelete(PAYER)
                                .transfer(BENEFICIARY)
                                .sigControl(forKey(PAYER_KEY, validSig))
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .txnId(expiredTxnId)
                                .via(cryptoDeleteTxn)
                                .hasPrecheck(TRANSACTION_EXPIRED),
                        getTxnRecord(cryptoDeleteTxn).logged().hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("CryptoDelete - with too far start time fails on ingest")
            Stream<DynamicTest> cryptoDeleteTooFarStartTimeFailsOnIngest() {
                final var futureTxnId = "futureDeleteTxn";
                final var oneHourAhead = 3_600L;
                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
                SigControl validSig = keyShape.signedWith(sigs(ON, ON));
                return hapiTest(
                        cryptoCreate(BENEFICIARY).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        usableTxnIdNamed(futureTxnId)
                                .modifyValidStart(oneHourAhead)
                                .payerId(PAYER),
                        cryptoDelete(PAYER)
                                .transfer(BENEFICIARY)
                                .sigControl(forKey(PAYER_KEY, validSig))
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .txnId(futureTxnId)
                                .via(cryptoDeleteTxn)
                                .hasPrecheck(INVALID_TRANSACTION_START),
                        getTxnRecord(cryptoDeleteTxn).logged().hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("CryptoDelete - with invalid duration fails on ingest")
            Stream<DynamicTest> cryptoDeleteInvalidDurationFailsOnIngest() {
                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
                SigControl validSig = keyShape.signedWith(sigs(ON, ON));
                return hapiTest(
                        cryptoCreate(BENEFICIARY).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        cryptoDelete(PAYER)
                                .transfer(BENEFICIARY)
                                .sigControl(forKey(PAYER_KEY, validSig))
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .validDurationSecs(0)
                                .via(cryptoDeleteTxn)
                                .hasPrecheck(INVALID_TRANSACTION_DURATION),
                        getTxnRecord(cryptoDeleteTxn).logged().hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("CryptoDelete - duplicate txn fails on ingest")
            Stream<DynamicTest> cryptoDeleteDuplicateTxnFailsOnIngest() {
                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
                SigControl validSig = keyShape.signedWith(sigs(ON, ON));
                return hapiTest(
                        cryptoCreate(BENEFICIARY).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                        // successful first delete
                        cryptoDelete(ACCOUNT).transfer(BENEFICIARY).via(cryptoDeleteTxn),
                        // duplicate reusing same txnId
                        cryptoCreate("anotherAccount")
                                .sigControl(forKey(PAYER_KEY, validSig))
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .txnId(cryptoDeleteTxn)
                                .via("cryptoDeleteDuplicateTxn")
                                .hasPrecheck(DUPLICATE_TRANSACTION));
            }

            @HapiTest
            @DisplayName("CryptoDelete - very large txn (above 6KB) - fails on ingest")
            final Stream<DynamicTest> cryptoDeleteVeryLargeTxnAboveSixKBFailsOnIngest() {
                return hapiTest(
                        cryptoCreate(BENEFICIARY).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(PAYER_KEY).shape(thresholdKeyWithPrimitives(59)),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        cryptoDelete(PAYER)
                                .sigControl(forKey(PAYER_KEY, allOnSigControl(59)))
                                .transfer(BENEFICIARY)
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .via(cryptoDeleteTxn)
                                .hasPrecheck(TRANSACTION_OVERSIZE),
                        getTxnRecord(cryptoDeleteTxn).logged().hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }
        }

        @Nested
        @DisplayName("CryptoDelete Simple Fees Failures on Handle")
        class CryptoDeleteSimpleFeesFailuresOnHandle {

            @Tag(ONLY_SUBPROCESS)
            @LeakyHapiTest
            @DisplayName("CryptoDelete - duplicate transaction fails on handle - payer charged full fee")
            Stream<DynamicTest> cryptoDeleteWithDuplicateTransactionFailsOnHandlePayerChargedFullFee() {
                return hapiTest(
                        cryptoCreate(BENEFICIARY).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                        cryptoTransfer(movingHbar(ONE_HBAR).between(GENESIS, "3")),
                        usableTxnIdNamed(DUPLICATE_TXN_ID).payerId(PAYER),
                        cryptoDelete(ACCOUNT)
                                .transfer(BENEFICIARY)
                                .payingWith(PAYER)
                                .signedBy(PAYER, ACCOUNT)
                                .setNode(4)
                                .txnId(DUPLICATE_TXN_ID)
                                .via(cryptoDeleteTxn)
                                .logged(),
                        cryptoCreate("anotherAccount")
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .txnId(DUPLICATE_TXN_ID)
                                .setNode(3)
                                .via("cryptoDeleteDuplicateTxn")
                                .hasPrecheck(DUPLICATE_TRANSACTION),
                        validateChargedUsdWithinWithTxnSize(
                                cryptoDeleteTxn,
                                txnSize -> expectedCryptoDeleteFullFeeUsd(
                                        Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateChargedAccount(cryptoDeleteTxn, PAYER));
            }

            @HapiTest
            @DisplayName("CryptoDelete - non-existent account fails on handle - payer charged full fee")
            Stream<DynamicTest> cryptoDeleteNonExistentAccountFailsOnHandlePayerChargedFullFee() {
                return hapiTest(
                        cryptoCreate(BENEFICIARY).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoDelete("0.0.9999999")
                                .transfer(BENEFICIARY)
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .via(cryptoDeleteTxn)
                                .hasKnownStatus(INVALID_ACCOUNT_ID),
                        validateChargedUsdWithinWithTxnSize(
                                cryptoDeleteTxn,
                                txnSize -> expectedCryptoDeleteFullFeeUsd(
                                        Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateChargedAccount(cryptoDeleteTxn, PAYER));
            }

            @HapiTest
            @DisplayName("CryptoDelete - already deleted account fails on handle - payer charged full fee")
            Stream<DynamicTest> cryptoDeleteAlreadyDeletedAccountFailsOnHandlePayerChargedFullFee() {
                return hapiTest(
                        cryptoCreate(BENEFICIARY).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                        // first delete succeeds
                        cryptoDelete(ACCOUNT).transfer(BENEFICIARY),
                        // second delete on already-deleted account fails on handle
                        cryptoDelete(ACCOUNT)
                                .transfer(BENEFICIARY)
                                .payingWith(PAYER)
                                .signedBy(PAYER, ACCOUNT)
                                .via(cryptoDeleteTxn)
                                .hasKnownStatus(ACCOUNT_DELETED),
                        validateChargedUsdWithinWithTxnSize(
                                cryptoDeleteTxn,
                                txnSize -> expectedCryptoDeleteFullFeeUsd(
                                        Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateChargedAccount(cryptoDeleteTxn, PAYER));
            }
        }
    }
}
