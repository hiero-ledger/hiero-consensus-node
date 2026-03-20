// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip1261;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.keys.KeyShape;
import com.hedera.services.bdd.spec.keys.SigControl;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

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
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoApproveAllowance;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingHbar;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.assertionsHold;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyListNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.usableTxnIdNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedAccount;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedCryptoApproveAllowanceFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.signedTxnSizeFor;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.validateChargedUsdWithinWithTxnSize;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.NODE_INCLUDED_BYTES;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.DUPLICATE_TRANSACTION;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_PAYER_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_TX_FEE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ALLOWANCE_OWNER_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TRANSACTION_DURATION;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TRANSACTION_START;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.MEMO_TOO_LONG;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.RECORD_NOT_FOUND;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TRANSACTION_EXPIRED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TRANSACTION_OVERSIZE;
import static org.hiero.hapi.support.fees.Extra.ALLOWANCES;
import static org.hiero.hapi.support.fees.Extra.PROCESSING_BYTES;
import static org.hiero.hapi.support.fees.Extra.SIGNATURES;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag(SIMPLE_FEES)
@HapiTestLifecycle
public class CryptoApproveAllowanceSimpleFeesTest {
    private static final String PAYER = "payer";
    private static final String OWNER = "owner";
    private static final String SPENDER = "spender";
    private static final String SPENDER2 = "spender2";
    private static final String FT_TOKEN = "fungibleToken";
    private static final String PAYER_KEY = "payerKey";
    private static final String approveAllowanceTxn = "approveAllowanceTxn";
    private static final String DUPLICATE_TXN_ID = "duplicateTxnId";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(Map.of(
                "fees.simpleFeesEnabled", "true"));
    }

    @Nested
    @DisplayName("CryptoApproveAllowance Simple Fees Positive Test Cases")
    class CryptoApproveAllowanceSimpleFeesPositiveTestCases {

        @HapiTest
        @DisplayName("CryptoApproveAllowance - single HBAR allowance - base fee only")
        final Stream<DynamicTest> cryptoApproveAllowanceSingleHbarAllowanceBaseFeesOnly() {
            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(SPENDER).balance(ONE_HUNDRED_HBARS),
                    cryptoApproveAllowance()
                            .addCryptoAllowance(OWNER, SPENDER, ONE_HUNDRED_HBARS)
                            .payingWith(PAYER)
                            .signedBy(PAYER, OWNER)
                            .via(approveAllowanceTxn),
                    validateChargedUsdWithinWithTxnSize(
                            approveAllowanceTxn,
                            txnSize -> expectedCryptoApproveAllowanceFullFeeUsd(Map.of(
                                    SIGNATURES, 2L,
                                    ALLOWANCES, 1L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(approveAllowanceTxn, PAYER));
        }

        @HapiTest
        @DisplayName("CryptoApproveAllowance - single FT allowance - base fee only")
        final Stream<DynamicTest> cryptoApproveAllowanceSingleFtAllowanceBaseFeesOnly() {
            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(SPENDER).balance(ONE_HUNDRED_HBARS),
                    tokenCreate(FT_TOKEN).treasury(OWNER),
                    tokenAssociate(SPENDER, FT_TOKEN),
                    cryptoApproveAllowance()
                            .addTokenAllowance(OWNER, FT_TOKEN, SPENDER, 100L)
                            .payingWith(PAYER)
                            .signedBy(PAYER, OWNER)
                            .via(approveAllowanceTxn),
                    validateChargedUsdWithinWithTxnSize(
                            approveAllowanceTxn,
                            txnSize -> expectedCryptoApproveAllowanceFullFeeUsd(Map.of(
                                    SIGNATURES, 2L,
                                    ALLOWANCES, 1L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(approveAllowanceTxn, PAYER));
        }

        @HapiTest
        @DisplayName("CryptoApproveAllowance - two allowances - extra fees charged")
        final Stream<DynamicTest> cryptoApproveAllowanceTwoAllowancesExtraCharged() {
            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(SPENDER).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(SPENDER2).balance(ONE_HUNDRED_HBARS),
                    cryptoApproveAllowance()
                            .addCryptoAllowance(OWNER, SPENDER, ONE_HUNDRED_HBARS)
                            .addCryptoAllowance(OWNER, SPENDER2, ONE_HUNDRED_HBARS)
                            .payingWith(PAYER)
                            .signedBy(PAYER, OWNER)
                            .via(approveAllowanceTxn),
                    validateChargedUsdWithinWithTxnSize(
                            approveAllowanceTxn,
                            txnSize -> expectedCryptoApproveAllowanceFullFeeUsd(Map.of(
                                    SIGNATURES, 2L,
                                    ALLOWANCES, 2L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(approveAllowanceTxn, PAYER));
        }

        @HapiTest
        @DisplayName("CryptoApproveAllowance - multiple HBAR and FT allowances - extra fees charged correctly")
        final Stream<DynamicTest> cryptoApproveAllowanceThreeAllowancesWithExtrasCharged() {
            final String FT_TOKEN2 = "fungibleToken2";
            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(SPENDER).balance(ONE_HUNDRED_HBARS),
                    tokenCreate(FT_TOKEN).treasury(OWNER),
                    tokenCreate(FT_TOKEN2).treasury(OWNER),
                    tokenAssociate(SPENDER, FT_TOKEN, FT_TOKEN2),
                    cryptoApproveAllowance()
                            .addCryptoAllowance(OWNER, SPENDER, ONE_HUNDRED_HBARS)
                            .addTokenAllowance(OWNER, FT_TOKEN, SPENDER, 100L)
                            .addTokenAllowance(OWNER, FT_TOKEN2, SPENDER, 100L)
                            .payingWith(PAYER)
                            .signedBy(PAYER, OWNER)
                            .via(approveAllowanceTxn),
                    validateChargedUsdWithinWithTxnSize(
                            approveAllowanceTxn,
                            txnSize -> expectedCryptoApproveAllowanceFullFeeUsd(Map.of(
                                    SIGNATURES, 2L,
                                    ALLOWANCES, 3L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(approveAllowanceTxn, PAYER));
        }

        @HapiTest
        @DisplayName("CryptoApproveAllowance - txn above NODE_INCLUDED_BYTES - extra PROCESSING_BYTES fees charged")
        final Stream<DynamicTest> cryptoApproveAllowanceAboveProcessingBytesThresholdExtrasCharged() {
            final KeyShape largeKeyShape = threshOf(1,
                    SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE,
                    SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE,
                    SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE,
                    SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE);
            return hapiTest(
                    newKeyNamed(PAYER_KEY).shape(largeKeyShape),
                    cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(SPENDER).balance(ONE_HUNDRED_HBARS),
                    cryptoApproveAllowance()
                            .addCryptoAllowance(OWNER, SPENDER, ONE_HUNDRED_HBARS)
                            .payingWith(PAYER)
                            .signedBy(PAYER, OWNER)
                            .via(approveAllowanceTxn),
                    assertionsHold((spec, log) -> {
                        final int txnSize = signedTxnSizeFor(spec, approveAllowanceTxn);
                        log.info("Large-key CryptoApproveAllowance signed size: {} bytes", txnSize);
                        assertTrue(txnSize > NODE_INCLUDED_BYTES,
                                "Expected txn size to exceed NODE_INCLUDED_BYTES (" + NODE_INCLUDED_BYTES + "), was " + txnSize);
                    }),
                    validateChargedUsdWithinWithTxnSize(
                            approveAllowanceTxn,
                            txnSize -> expectedCryptoApproveAllowanceFullFeeUsd(Map.of(
                                    SIGNATURES, 21L,
                                    ALLOWANCES, 1L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(approveAllowanceTxn, PAYER));
        }

        @HapiTest
        @DisplayName("CryptoApproveAllowance - very large txn (just below 6KB) - full charging with extra PROCESSING_BYTES")
        final Stream<DynamicTest> cryptoApproveAllowanceVeryLargeTxnJustBelow6KBExtraCharged() {
            final KeyShape veryLargeKeyShape = threshOf(1,
                    SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE,
                    SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE,
                    SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE,
                    SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE,
                    SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE,
                    SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE);
            return hapiTest(
                    newKeyNamed(PAYER_KEY).shape(veryLargeKeyShape),
                    cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(SPENDER).balance(ONE_HUNDRED_HBARS),
                    cryptoApproveAllowance()
                            .addCryptoAllowance(OWNER, SPENDER, ONE_HUNDRED_HBARS)
                            .payingWith(PAYER)
                            .signedBy(PAYER, OWNER)
                            .via(approveAllowanceTxn),
                    assertionsHold((spec, log) -> {
                        final int txnSize = signedTxnSizeFor(spec, approveAllowanceTxn);
                        log.info("Very-large CryptoApproveAllowance signed size: {} bytes", txnSize);
                        assertTrue(txnSize < 6_000,
                                "Expected txn size (" + txnSize + ") to not exceed 6000 bytes");
                    }),
                    validateChargedUsdWithinWithTxnSize(
                            approveAllowanceTxn,
                            txnSize -> expectedCryptoApproveAllowanceFullFeeUsd(Map.of(
                                    SIGNATURES, 56L,
                                    ALLOWANCES, 1L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(approveAllowanceTxn, PAYER));
        }
    }

    @Nested
    @DisplayName("CryptoApproveAllowance Simple Fees Negative Test Cases")
    class CryptoApproveAllowanceSimpleFeesNegativeTestCases {

        @Nested
        @DisplayName("CryptoApproveAllowance Simple Fees Failures on Ingest")
        class CryptoApproveAllowanceSimpleFeesFailuresOnIngest {

            @HapiTest
            @DisplayName("CryptoApproveAllowance - threshold key with invalid signature - fails on ingest")
            final Stream<DynamicTest> cryptoApproveAllowanceThresholdKeyInvalidSignatureFailsOnIngest() {
                final KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
                final SigControl invalidSig = keyShape.signedWith(sigs(ON, OFF));
                return hapiTest(
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(SPENDER).balance(ONE_HUNDRED_HBARS),
                        cryptoApproveAllowance()
                                .addCryptoAllowance(OWNER, SPENDER, ONE_HUNDRED_HBARS)
                                .payingWith(PAYER)
                                .sigControl(forKey(PAYER_KEY, invalidSig))
                                .signedBy(PAYER, OWNER)
                                .via(approveAllowanceTxn)
                                .hasPrecheck(INVALID_SIGNATURE),
                        getTxnRecord(approveAllowanceTxn).logged().hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("CryptoApproveAllowance - threshold with nested list invalid signature - fails on ingest")
            final Stream<DynamicTest> cryptoApproveAllowanceThresholdWithListKeyInvalidSignatureFailsOnIngest() {
                final KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE, listOf(2));
                final SigControl invalidSig = keyShape.signedWith(sigs(ON, OFF, sigs(OFF, OFF)));
                return hapiTest(
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(PAYER).key(PAYER_KEY)
                                .sigControl(forKey(PAYER_KEY, invalidSig))
                                .balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(SPENDER).balance(ONE_HUNDRED_HBARS),
                        cryptoApproveAllowance()
                                .addCryptoAllowance(OWNER, SPENDER, ONE_HUNDRED_HBARS)
                                .payingWith(PAYER)
                                .sigControl(forKey(PAYER_KEY, invalidSig))
                                .signedBy(PAYER, OWNER)
                                .via(approveAllowanceTxn)
                                .hasPrecheck(INVALID_SIGNATURE),
                        getTxnRecord(approveAllowanceTxn).logged().hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("CryptoApproveAllowance - key list with missing signature - fails on ingest")
            final Stream<DynamicTest> cryptoApproveAllowanceKeyListMissingSignatureFailsOnIngest() {
                return hapiTest(
                        newKeyNamed("firstKey"),
                        newKeyNamed("secondKey"),
                        newKeyListNamed(PAYER_KEY, List.of("firstKey", "secondKey")),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(SPENDER).balance(ONE_HUNDRED_HBARS),
                        cryptoApproveAllowance()
                                .addCryptoAllowance(OWNER, SPENDER, ONE_HUNDRED_HBARS)
                                .payingWith(PAYER)
                                .signedBy("firstKey", OWNER)
                                .via(approveAllowanceTxn)
                                .hasPrecheck(INVALID_SIGNATURE),
                        getTxnRecord(approveAllowanceTxn).logged().hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("CryptoApproveAllowance - insufficient tx fee - fails on ingest")
            final Stream<DynamicTest> cryptoApproveAllowanceInsufficientTxFeeFailsOnIngest() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(SPENDER).balance(ONE_HUNDRED_HBARS),
                        cryptoApproveAllowance()
                                .addCryptoAllowance(OWNER, SPENDER, ONE_HUNDRED_HBARS)
                                .payingWith(PAYER)
                                .signedBy(PAYER, OWNER)
                                .fee(ONE_HBAR / 100_000)
                                .via(approveAllowanceTxn)
                                .hasPrecheck(INSUFFICIENT_TX_FEE),
                        getTxnRecord(approveAllowanceTxn).logged().hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("CryptoApproveAllowance - insufficient payer balance fails on ingest")
            final Stream<DynamicTest> cryptoApproveAllowanceInsufficientPayerBalanceFailsOnIngest() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HBAR / 100_000),
                        cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(SPENDER).balance(ONE_HUNDRED_HBARS),
                        cryptoApproveAllowance()
                                .addCryptoAllowance(OWNER, SPENDER, ONE_HUNDRED_HBARS)
                                .payingWith(PAYER)
                                .signedBy(PAYER, OWNER)
                                .via(approveAllowanceTxn)
                                .hasPrecheck(INSUFFICIENT_PAYER_BALANCE),
                        getTxnRecord(approveAllowanceTxn).logged().hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("CryptoApproveAllowance - memo too long - fails on ingest")
            final Stream<DynamicTest> cryptoApproveAllowanceMemoTooLongFailsOnIngest() {
                final var LONG_MEMO = "x".repeat(1025);
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(SPENDER).balance(ONE_HUNDRED_HBARS),
                        cryptoApproveAllowance()
                                .addCryptoAllowance(OWNER, SPENDER, ONE_HUNDRED_HBARS)
                                .payingWith(PAYER)
                                .signedBy(PAYER, OWNER)
                                .memo(LONG_MEMO)
                                .via(approveAllowanceTxn)
                                .hasPrecheck(MEMO_TOO_LONG),
                        getTxnRecord(approveAllowanceTxn).logged().hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("CryptoApproveAllowance - expired transaction - fails on ingest")
            final Stream<DynamicTest> cryptoApproveAllowanceExpiredTransactionFailsOnIngest() {
                final var expiredTxnId = "expiredApproveTxn";
                final var oneHourPast = -3_600L;
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(SPENDER).balance(ONE_HUNDRED_HBARS),
                        usableTxnIdNamed(expiredTxnId).modifyValidStart(oneHourPast).payerId(PAYER),
                        cryptoApproveAllowance()
                                .addCryptoAllowance(OWNER, SPENDER, ONE_HUNDRED_HBARS)
                                .payingWith(PAYER)
                                .signedBy(PAYER, OWNER)
                                .txnId(expiredTxnId)
                                .via(approveAllowanceTxn)
                                .hasPrecheck(TRANSACTION_EXPIRED),
                        getTxnRecord(approveAllowanceTxn).logged().hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("CryptoApproveAllowance - too far start time - fails on ingest")
            final Stream<DynamicTest> cryptoApproveAllowanceTooFarStartTimeFailsOnIngest() {
                final var futureTxnId = "futureTxnId";
                final var oneHourAhead = 3_600L;
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(SPENDER).balance(ONE_HUNDRED_HBARS),
                        usableTxnIdNamed(futureTxnId).modifyValidStart(oneHourAhead).payerId(PAYER),
                        cryptoApproveAllowance()
                                .addCryptoAllowance(OWNER, SPENDER, ONE_HUNDRED_HBARS)
                                .payingWith(PAYER)
                                .signedBy(PAYER, OWNER)
                                .txnId(futureTxnId)
                                .via(approveAllowanceTxn)
                                .hasPrecheck(INVALID_TRANSACTION_START),
                        getTxnRecord(approveAllowanceTxn).logged().hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("CryptoApproveAllowance - invalid duration - fails on ingest")
            final Stream<DynamicTest> cryptoApproveAllowanceInvalidDurationFailsOnIngest() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(SPENDER).balance(ONE_HUNDRED_HBARS),
                        cryptoApproveAllowance()
                                .addCryptoAllowance(OWNER, SPENDER, ONE_HUNDRED_HBARS)
                                .payingWith(PAYER)
                                .signedBy(PAYER, OWNER)
                                .validDurationSecs(0)
                                .via(approveAllowanceTxn)
                                .hasPrecheck(INVALID_TRANSACTION_DURATION),
                        getTxnRecord(approveAllowanceTxn).logged().hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("CryptoApproveAllowance - duplicate transaction - fails on ingest")
            final Stream<DynamicTest> cryptoApproveAllowanceDuplicateTxnFailsOnIngest() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(SPENDER).balance(ONE_HUNDRED_HBARS),
                        cryptoApproveAllowance()
                                .addCryptoAllowance(OWNER, SPENDER, ONE_HUNDRED_HBARS)
                                .payingWith(PAYER)
                                .signedBy(PAYER, OWNER)
                                .via(approveAllowanceTxn),
                        cryptoApproveAllowance()
                                .addCryptoAllowance(OWNER, SPENDER, ONE_HUNDRED_HBARS)
                                .payingWith(PAYER)
                                .signedBy(PAYER, OWNER)
                                .txnId(approveAllowanceTxn)
                                .via("approveAllowanceDuplicateTxn")
                                .hasPrecheck(DUPLICATE_TRANSACTION));
            }

            @HapiTest
            @DisplayName("CryptoApproveAllowance - very large txn (above 6KB) - fails on ingest")
            final Stream<DynamicTest> cryptoApproveAllowanceVeryLargeTxnAboveSixKBFailsOnIngest() {
                final KeyShape veryLargeKeyShape = threshOf(1,
                        SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE,
                        SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE,
                        SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE,
                        SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE,
                        SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE,
                        SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE);
                return hapiTest(
                        newKeyNamed(PAYER_KEY).shape(veryLargeKeyShape),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(SPENDER).balance(ONE_HUNDRED_HBARS),
                        cryptoApproveAllowance()
                                .addCryptoAllowance(OWNER, SPENDER, ONE_HUNDRED_HBARS)
                                .payingWith(PAYER)
                                .signedBy(PAYER, OWNER)
                                .via(approveAllowanceTxn)
                                .hasPrecheck(TRANSACTION_OVERSIZE),
                        getTxnRecord(approveAllowanceTxn).logged().hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }
        }

        @Nested
        @DisplayName("CryptoApproveAllowance Simple Fees Failures on Handle")
        class CryptoApproveAllowanceSimpleFeesFailuresOnHandle {

            @Tag(ONLY_SUBPROCESS)
            @LeakyHapiTest
            @DisplayName("CryptoApproveAllowance - duplicate transaction fails on handle - payer charged full fee")
            final Stream<DynamicTest> cryptoApproveAllowanceDuplicateTransactionFailsOnHandlePayerChargedFullFee() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(SPENDER).balance(ONE_HUNDRED_HBARS),
                        cryptoTransfer(movingHbar(ONE_HBAR).between(GENESIS, "3")),
                        usableTxnIdNamed(DUPLICATE_TXN_ID).payerId(PAYER),
                        cryptoApproveAllowance()
                                .addCryptoAllowance(OWNER, SPENDER, ONE_HUNDRED_HBARS)
                                .payingWith(PAYER)
                                .signedBy(PAYER, OWNER)
                                .setNode(4)
                                .txnId(DUPLICATE_TXN_ID)
                                .via(approveAllowanceTxn)
                                .logged(),
                        cryptoApproveAllowance()
                                .addCryptoAllowance(OWNER, SPENDER, ONE_HUNDRED_HBARS)
                                .payingWith(PAYER)
                                .signedBy(PAYER, OWNER)
                                .txnId(DUPLICATE_TXN_ID)
                                .setNode(3)
                                .via("approveAllowanceDuplicateTxn")
                                .hasPrecheck(DUPLICATE_TRANSACTION),
                        validateChargedUsdWithinWithTxnSize(
                                approveAllowanceTxn,
                                txnSize -> expectedCryptoApproveAllowanceFullFeeUsd(Map.of(
                                        SIGNATURES, 2L,
                                        ALLOWANCES, 1L,
                                        PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateChargedAccount(approveAllowanceTxn, PAYER));
            }

            @HapiTest
            @DisplayName("CryptoApproveAllowance - non-existent owner fails on handle - full fees charged")
            final Stream<DynamicTest> cryptoApproveAllowanceNonExistentOwnerFailsOnHandle() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(SPENDER).balance(ONE_HUNDRED_HBARS),
                        cryptoApproveAllowance()
                                .addCryptoAllowance("0.0.9999999", SPENDER, ONE_HUNDRED_HBARS)
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .via(approveAllowanceTxn)
                                .hasKnownStatus(INVALID_ALLOWANCE_OWNER_ID),
                        validateChargedUsdWithinWithTxnSize(
                                approveAllowanceTxn,
                                txnSize -> expectedCryptoApproveAllowanceFullFeeUsd(Map.of(
                                        SIGNATURES, 1L,
                                        ALLOWANCES, 1L,
                                        PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateChargedAccount(approveAllowanceTxn, PAYER));
            }

            @HapiTest
            @DisplayName("CryptoApproveAllowance - deleted owner fails on handle - full fees charged")
            final Stream<DynamicTest> cryptoApproveAllowanceDeletedOwnerFailsOnHandle() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed("ownerKey"),
                        cryptoCreate(OWNER).key("ownerKey").balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(SPENDER).balance(ONE_HUNDRED_HBARS),
                        cryptoDelete(OWNER).payingWith(PAYER),
                        cryptoApproveAllowance()
                                .addCryptoAllowance(OWNER, SPENDER, ONE_HUNDRED_HBARS)
                                .payingWith(PAYER)
                                .signedBy(PAYER, "ownerKey")
                                .fee(ONE_HBAR)
                                .via(approveAllowanceTxn)
                                .hasKnownStatus(INVALID_ALLOWANCE_OWNER_ID),
                        validateChargedUsdWithinWithTxnSize(
                                approveAllowanceTxn,
                                txnSize -> expectedCryptoApproveAllowanceFullFeeUsd(Map.of(
                                        SIGNATURES, 2L,
                                        ALLOWANCES, 1L,
                                        PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateChargedAccount(approveAllowanceTxn, PAYER));
            }
        }
    }
}
