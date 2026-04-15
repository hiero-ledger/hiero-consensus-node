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
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenDelete;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingHbar;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.assertionsHold;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyListNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.usableTxnIdNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedAccount;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTokenDeleteFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTokenDeleteNetworkFeeOnlyUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.signedTxnSizeFor;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.validateChargedUsdWithinWithTxnSize;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.NODE_INCLUDED_BYTES;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.DUPLICATE_TRANSACTION;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_PAYER_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_TX_FEE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_PAYER_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOKEN_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TRANSACTION_DURATION;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TRANSACTION_START;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.MEMO_TOO_LONG;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.RECORD_NOT_FOUND;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_IS_IMMUTABLE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_WAS_DELETED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TRANSACTION_EXPIRED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TRANSACTION_OVERSIZE;
import static com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON;
import static org.hiero.hapi.support.fees.Extra.PROCESSING_BYTES;
import static org.hiero.hapi.support.fees.Extra.SIGNATURES;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyEmbeddedHapiTest;
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
 * Tests for TokenDelete simple fees with extras.
 * Validates that fees are correctly calculated based on:
 * - Number of signatures (extras beyond included)
 */
@Tag(SIMPLE_FEES)
@HapiTestLifecycle
public class TokenDeleteSimpleFeesTest {

    private static final String PAYER = "payer";
    private static final String TREASURY = "treasury";
    private static final String ADMIN_KEY = "adminKey";
    private static final String PAYER_KEY = "payerKey";
    private static final String TOKEN = "fungibleToken";
    private static final String tokenDeleteTxn = "tokenDeleteTxn";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(Map.of("fees.simpleFeesEnabled", "true"));
    }

    @Nested
    @DisplayName("TokenDelete Simple Fees Positive Test Cases")
    class TokenDeleteSimpleFeesPositiveTestCases {

        @HapiTest
        @DisplayName("TokenDelete - base fees")
        final Stream<DynamicTest> tokenDeleteBaseFee() {
            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(TREASURY).balance(0L),
                    newKeyNamed(ADMIN_KEY),
                    tokenCreate(TOKEN)
                            .tokenType(FUNGIBLE_COMMON)
                            .adminKey(ADMIN_KEY)
                            .treasury(TREASURY)
                            .payingWith(PAYER),
                    tokenDelete(TOKEN)
                            .payingWith(PAYER)
                            .signedBy(PAYER, ADMIN_KEY)
                            .via(tokenDeleteTxn),
                    validateChargedUsdWithinWithTxnSize(
                            tokenDeleteTxn,
                            txnSize -> expectedTokenDeleteFullFeeUsd(
                                    Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                            0.1));
        }

        @HapiTest
        @DisplayName("TokenDelete with threshold key - extra signatures")
        final Stream<DynamicTest> tokenDeleteWithThresholdKey() {
            KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
            SigControl validSig = keyShape.signedWith(sigs(ON, ON));

            return hapiTest(
                    newKeyNamed(PAYER_KEY).shape(keyShape),
                    cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(TREASURY).balance(0L),
                    newKeyNamed(ADMIN_KEY),
                    tokenCreate(TOKEN)
                            .tokenType(FUNGIBLE_COMMON)
                            .adminKey(ADMIN_KEY)
                            .treasury(TREASURY)
                            .payingWith(PAYER)
                            .sigControl(forKey(PAYER_KEY, validSig)),
                    tokenDelete(TOKEN)
                            .payingWith(PAYER)
                            .sigControl(forKey(PAYER_KEY, validSig))
                            .signedBy(PAYER, ADMIN_KEY)
                            .via(tokenDeleteTxn),
                    validateChargedUsdWithinWithTxnSize(
                            tokenDeleteTxn,
                            txnSize -> expectedTokenDeleteFullFeeUsd(
                                    Map.of(SIGNATURES, 3L, PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(tokenDeleteTxn, PAYER));
        }

        @HapiTest
        @DisplayName("TokenDelete with threshold admin key - extra signatures")
        final Stream<DynamicTest> tokenDeleteWithThresholdAdminKey() {
            KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
            SigControl validSig = keyShape.signedWith(sigs(ON, ON));

            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(TREASURY).balance(0L),
                    newKeyNamed(ADMIN_KEY).shape(keyShape),
                    tokenCreate(TOKEN)
                            .tokenType(FUNGIBLE_COMMON)
                            .adminKey(ADMIN_KEY)
                            .treasury(TREASURY)
                            .payingWith(PAYER)
                            .signedBy(PAYER, TREASURY, ADMIN_KEY)
                            .sigControl(forKey(ADMIN_KEY, validSig)),
                    tokenDelete(TOKEN)
                            .payingWith(PAYER)
                            .signedBy(PAYER, ADMIN_KEY)
                            .sigControl(forKey(ADMIN_KEY, validSig))
                            .via(tokenDeleteTxn),
                    validateChargedUsdWithinWithTxnSize(
                            tokenDeleteTxn,
                            txnSize -> expectedTokenDeleteFullFeeUsd(
                                    Map.of(SIGNATURES, 3L, PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(tokenDeleteTxn, PAYER));
        }

        @HapiTest
        @DisplayName("TokenDelete - large key txn above NODE_INCLUDED_BYTES threshold - extra PROCESSING_BYTES charged")
        final Stream<DynamicTest> tokenDeleteLargeKeyExtraProcessingBytesFee() {
            final KeyShape largeKeyShape = threshOf(
                    1, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE,
                    SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE);
            return hapiTest(
                    newKeyNamed(PAYER_KEY).shape(largeKeyShape),
                    cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(TREASURY).balance(0L),
                    newKeyNamed(ADMIN_KEY),
                    tokenCreate(TOKEN)
                            .tokenType(FUNGIBLE_COMMON)
                            .adminKey(ADMIN_KEY)
                            .treasury(TREASURY)
                            .payingWith(PAYER),
                    tokenDelete(TOKEN)
                            .payingWith(PAYER)
                            .signedBy(PAYER, ADMIN_KEY)
                            .via(tokenDeleteTxn),
                    assertionsHold((spec, log) -> {
                        final int txnSize = signedTxnSizeFor(spec, tokenDeleteTxn);
                        log.info(
                                "Large-key TokenDelete signed size: {} bytes (threshold: {})",
                                txnSize,
                                NODE_INCLUDED_BYTES);
                        assertTrue(
                                txnSize > NODE_INCLUDED_BYTES,
                                "Expected txn size (" + txnSize + ") to exceed " + NODE_INCLUDED_BYTES + " bytes");
                    }),
                    validateChargedUsdWithinWithTxnSize(
                            tokenDeleteTxn,
                            txnSize -> expectedTokenDeleteFullFeeUsd(
                                    Map.of(SIGNATURES, 21L, PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(tokenDeleteTxn, PAYER));
        }

        @HapiTest
        @DisplayName("TokenDelete - very large txn (just below 6KB) - full charging with extra PROCESSING_BYTES")
        final Stream<DynamicTest> tokenDeleteVeryLargeKeyBelowOversizeFee() {
            final KeyShape veryLargeKeyShape = threshOf(
                    1, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE,
                    SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE,
                    SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE,
                    SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE,
                    SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE);
            return hapiTest(
                    newKeyNamed(PAYER_KEY).shape(veryLargeKeyShape),
                    cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(TREASURY).balance(0L),
                    newKeyNamed(ADMIN_KEY),
                    tokenCreate(TOKEN)
                            .tokenType(FUNGIBLE_COMMON)
                            .adminKey(ADMIN_KEY)
                            .treasury(TREASURY)
                            .payingWith(PAYER),
                    tokenDelete(TOKEN)
                            .payingWith(PAYER)
                            .signedBy(PAYER, ADMIN_KEY)
                            .via(tokenDeleteTxn),
                    assertionsHold((spec, log) -> {
                        final int txnSize = signedTxnSizeFor(spec, tokenDeleteTxn);
                        log.info("Very-large TokenDelete signed size: {} bytes", txnSize);
                        assertTrue(txnSize < 6_000, "Expected txn size (" + txnSize + ") to not exceed 6000 bytes");
                    }),
                    validateChargedUsdWithinWithTxnSize(
                            tokenDeleteTxn,
                            txnSize -> expectedTokenDeleteFullFeeUsd(
                                    Map.of(SIGNATURES, 56L, PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(tokenDeleteTxn, PAYER));
        }
    }

    @Nested
    @DisplayName("TokenDelete Simple Fees Negative Test Cases")
    class TokenDeleteSimpleFeesNegativeTestCases {

        @Nested
        @DisplayName("TokenDelete Failures on Ingest and Handle")
        class TokenDeleteFailuresOnIngest {

            @HapiTest
            @DisplayName("TokenDelete - insufficient tx fee fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenDeleteInsufficientTxFeeFailsOnIngest() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        newKeyNamed(ADMIN_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .adminKey(ADMIN_KEY)
                                .treasury(TREASURY)
                                .payingWith(PAYER),
                        tokenDelete(TOKEN)
                                .payingWith(PAYER)
                                .signedBy(PAYER, ADMIN_KEY)
                                .fee(1L) // Fee too low
                                .via(tokenDeleteTxn)
                                .hasPrecheck(INSUFFICIENT_TX_FEE),
                        getTxnRecord(tokenDeleteTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TokenDelete - threshold key invalid signature fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenDeleteThresholdInvalidSigFailsOnIngest() {
                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
                SigControl invalidSig = keyShape.signedWith(sigs(ON, OFF));

                return hapiTest(
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        newKeyNamed(ADMIN_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .adminKey(ADMIN_KEY)
                                .treasury(TREASURY)
                                .payingWith(PAYER)
                                .sigControl(forKey(PAYER_KEY, keyShape.signedWith(sigs(ON, ON)))),
                        tokenDelete(TOKEN)
                                .payingWith(PAYER)
                                .sigControl(forKey(PAYER_KEY, invalidSig))
                                .signedBy(PAYER, ADMIN_KEY)
                                .via(tokenDeleteTxn)
                                .hasPrecheck(INVALID_SIGNATURE),
                        getTxnRecord(tokenDeleteTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName(
                    "TokenDelete - threshold key with nested list invalid signature fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenDeleteThresholdWithListInvalidSigFailsOnIngest() {
                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE, listOf(2));
                SigControl invalidSig = keyShape.signedWith(sigs(ON, OFF, sigs(OFF, OFF)));

                return hapiTest(
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(PAYER)
                                .key(PAYER_KEY)
                                .sigControl(forKey(PAYER_KEY, keyShape.signedWith(sigs(ON, ON, sigs(ON, ON)))))
                                .balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        newKeyNamed(ADMIN_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .adminKey(ADMIN_KEY)
                                .treasury(TREASURY)
                                .payingWith(PAYER)
                                .sigControl(forKey(PAYER_KEY, keyShape.signedWith(sigs(ON, ON, sigs(ON, ON))))),
                        tokenDelete(TOKEN)
                                .payingWith(PAYER)
                                .sigControl(forKey(PAYER_KEY, invalidSig))
                                .signedBy(PAYER, ADMIN_KEY)
                                .via(tokenDeleteTxn)
                                .hasPrecheck(INVALID_SIGNATURE),
                        getTxnRecord(tokenDeleteTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TokenDelete - key list with missing signature fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenDeleteKeyListMissingSigFailsOnIngest() {
                return hapiTest(
                        newKeyNamed("firstKey"),
                        newKeyNamed("secondKey"),
                        newKeyListNamed(PAYER_KEY, List.of("firstKey", "secondKey")),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        newKeyNamed(ADMIN_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .adminKey(ADMIN_KEY)
                                .treasury(TREASURY)
                                .payingWith(PAYER),
                        tokenDelete(TOKEN)
                                .payingWith(PAYER)
                                .signedBy("firstKey", ADMIN_KEY) // missing secondKey from list
                                .via(tokenDeleteTxn)
                                .hasPrecheck(INVALID_SIGNATURE),
                        getTxnRecord(tokenDeleteTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TokenDelete - insufficient payer balance fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenDeleteInsufficientPayerBalanceFailsOnIngest() {
                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
                SigControl validSig = keyShape.signedWith(sigs(ON, ON));

                return hapiTest(
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(1L), // too little balance
                        cryptoCreate(TREASURY).balance(0L),
                        newKeyNamed(ADMIN_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .adminKey(ADMIN_KEY)
                                .treasury(TREASURY),
                        tokenDelete(TOKEN)
                                .payingWith(PAYER)
                                .sigControl(forKey(PAYER_KEY, validSig))
                                .signedBy(PAYER, ADMIN_KEY)
                                .via(tokenDeleteTxn)
                                .hasPrecheck(INSUFFICIENT_PAYER_BALANCE),
                        getTxnRecord(tokenDeleteTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TokenDelete - memo too long fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenDeleteMemoTooLongFailsOnIngest() {
                final var LONG_MEMO = "x".repeat(1025);
                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
                SigControl validSig = keyShape.signedWith(sigs(ON, ON));

                return hapiTest(
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        newKeyNamed(ADMIN_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .adminKey(ADMIN_KEY)
                                .treasury(TREASURY)
                                .payingWith(PAYER)
                                .sigControl(forKey(PAYER_KEY, validSig)),
                        tokenDelete(TOKEN)
                                .payingWith(PAYER)
                                .sigControl(forKey(PAYER_KEY, validSig))
                                .signedBy(PAYER, ADMIN_KEY)
                                .memo(LONG_MEMO)
                                .via(tokenDeleteTxn)
                                .hasPrecheck(MEMO_TOO_LONG),
                        getTxnRecord(tokenDeleteTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TokenDelete - expired transaction fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenDeleteExpiredTransactionFailsOnIngest() {
                final var expiredTxnId = "expiredTokenDelete";
                final var oneHourPast = -3_600L;
                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
                SigControl validSig = keyShape.signedWith(sigs(ON, ON));

                return hapiTest(
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        newKeyNamed(ADMIN_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .adminKey(ADMIN_KEY)
                                .treasury(TREASURY)
                                .payingWith(PAYER)
                                .sigControl(forKey(PAYER_KEY, validSig)),
                        usableTxnIdNamed(expiredTxnId)
                                .modifyValidStart(oneHourPast)
                                .payerId(PAYER),
                        tokenDelete(TOKEN)
                                .payingWith(PAYER)
                                .sigControl(forKey(PAYER_KEY, validSig))
                                .signedBy(PAYER, ADMIN_KEY)
                                .txnId(expiredTxnId)
                                .via(tokenDeleteTxn)
                                .hasPrecheck(TRANSACTION_EXPIRED),
                        getTxnRecord(tokenDeleteTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TokenDelete - too far start time fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenDeleteTooFarStartTimeFailsOnIngest() {
                final var futureTxnId = "futureTokenDelete";
                final var oneHourFuture = 3_600L;
                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
                SigControl validSig = keyShape.signedWith(sigs(ON, ON));

                return hapiTest(
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        newKeyNamed(ADMIN_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .adminKey(ADMIN_KEY)
                                .treasury(TREASURY)
                                .payingWith(PAYER)
                                .sigControl(forKey(PAYER_KEY, validSig)),
                        usableTxnIdNamed(futureTxnId)
                                .modifyValidStart(oneHourFuture)
                                .payerId(PAYER),
                        tokenDelete(TOKEN)
                                .payingWith(PAYER)
                                .sigControl(forKey(PAYER_KEY, validSig))
                                .signedBy(PAYER, ADMIN_KEY)
                                .txnId(futureTxnId)
                                .via(tokenDeleteTxn)
                                .hasPrecheck(INVALID_TRANSACTION_START),
                        getTxnRecord(tokenDeleteTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TokenDelete - invalid duration fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenDeleteInvalidDurationFailsOnIngest() {
                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
                SigControl validSig = keyShape.signedWith(sigs(ON, ON));

                return hapiTest(
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        newKeyNamed(ADMIN_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .adminKey(ADMIN_KEY)
                                .treasury(TREASURY)
                                .payingWith(PAYER)
                                .sigControl(forKey(PAYER_KEY, validSig)),
                        tokenDelete(TOKEN)
                                .payingWith(PAYER)
                                .sigControl(forKey(PAYER_KEY, validSig))
                                .signedBy(PAYER, ADMIN_KEY)
                                .validDurationSecs(0)
                                .via(tokenDeleteTxn)
                                .hasPrecheck(INVALID_TRANSACTION_DURATION),
                        getTxnRecord(tokenDeleteTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TokenDelete - duplicate transaction fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenDeleteDuplicateTxnFailsOnIngest() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        newKeyNamed(ADMIN_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .adminKey(ADMIN_KEY)
                                .treasury(TREASURY)
                                .payingWith(PAYER),
                        // first successful delete
                        tokenDelete(TOKEN)
                                .payingWith(PAYER)
                                .signedBy(PAYER, ADMIN_KEY)
                                .via(tokenDeleteTxn),
                        // duplicate reusing same txnId — uses a different operation so it doesn't need the token
                        cryptoCreate("dummy")
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .txnId(tokenDeleteTxn)
                                .via("tokenDeleteDuplicateTxn")
                                .hasPrecheck(DUPLICATE_TRANSACTION));
            }

            @HapiTest
            @DisplayName("TokenDelete - very large txn (above 6KB) fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenDeleteTransactionOversizeFailsOnIngest() {
                final KeyShape veryLargeKeyShape = threshOf(
                        1, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE,
                        SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE,
                        SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE,
                        SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE,
                        SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE,
                        SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE);
                return hapiTest(
                        newKeyNamed(PAYER_KEY).shape(veryLargeKeyShape),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        newKeyNamed(ADMIN_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .adminKey(ADMIN_KEY)
                                .treasury(TREASURY),
                        tokenDelete(TOKEN)
                                .payingWith(PAYER)
                                .signedBy(PAYER, ADMIN_KEY)
                                .via(tokenDeleteTxn)
                                .hasPrecheck(TRANSACTION_OVERSIZE),
                        getTxnRecord(tokenDeleteTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TokenDelete - missing admin key signature fails on handle - fee charged")
            final Stream<DynamicTest> tokenDeleteMissingAdminKeySignatureFailsAtHandle() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        newKeyNamed(ADMIN_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .adminKey(ADMIN_KEY)
                                .treasury(TREASURY)
                                .payingWith(PAYER),
                        tokenDelete(TOKEN)
                                .payingWith(PAYER)
                                .signedBy(PAYER) // Missing admin key signature
                                .via(tokenDeleteTxn)
                                .hasKnownStatus(INVALID_SIGNATURE),
                        validateChargedUsdWithinWithTxnSize(
                                tokenDeleteTxn,
                                txnSize -> expectedTokenDeleteFullFeeUsd(
                                        Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateChargedAccount(tokenDeleteTxn, PAYER));
            }

            @HapiTest
            @DisplayName("TokenDelete - invalid token fails on handle - fee charged")
            final Stream<DynamicTest> tokenDeleteInvalidTokenFails() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        tokenDelete("0.0.99999999") // Invalid token
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .via(tokenDeleteTxn)
                                .hasKnownStatus(INVALID_TOKEN_ID),
                        validateChargedUsdWithinWithTxnSize(
                                tokenDeleteTxn,
                                txnSize -> expectedTokenDeleteFullFeeUsd(
                                        Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateChargedAccount(tokenDeleteTxn, PAYER));
            }

            @HapiTest
            @DisplayName("TokenDelete - immutable token fails on handle - fee charged")
            final Stream<DynamicTest> tokenDeleteImmutableTokenFails() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                // No admin key - token is immutable
                                .treasury(TREASURY)
                                .payingWith(PAYER),
                        tokenDelete(TOKEN)
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .via(tokenDeleteTxn)
                                .hasKnownStatus(TOKEN_IS_IMMUTABLE),
                        validateChargedUsdWithinWithTxnSize(
                                tokenDeleteTxn,
                                txnSize -> expectedTokenDeleteFullFeeUsd(
                                        Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateChargedAccount(tokenDeleteTxn, PAYER));
            }

            @HapiTest
            @DisplayName("TokenDelete - already deleted token fails on handle - fee charged")
            final Stream<DynamicTest> tokenDeleteAlreadyDeletedFails() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        newKeyNamed(ADMIN_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .adminKey(ADMIN_KEY)
                                .treasury(TREASURY)
                                .payingWith(PAYER),
                        tokenDelete(TOKEN).payingWith(PAYER).signedBy(PAYER, ADMIN_KEY),
                        tokenDelete(TOKEN)
                                .payingWith(PAYER)
                                .signedBy(PAYER, ADMIN_KEY)
                                .via(tokenDeleteTxn)
                                .hasKnownStatus(TOKEN_WAS_DELETED),
                        validateChargedUsdWithinWithTxnSize(
                                tokenDeleteTxn,
                                txnSize -> expectedTokenDeleteFullFeeUsd(
                                        Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateChargedAccount(tokenDeleteTxn, PAYER));
            }

            @Tag(ONLY_SUBPROCESS)
            @LeakyHapiTest
            @DisplayName("TokenDelete - duplicate transaction fails on handle - payer charged full fee")
            final Stream<DynamicTest> tokenDeleteDuplicateFailsOnHandle() {
                final String DUPLICATE_TXN_ID = "tokenDeleteDuplicateTxnId";
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        newKeyNamed(ADMIN_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .adminKey(ADMIN_KEY)
                                .treasury(TREASURY)
                                .payingWith(PAYER),
                        cryptoTransfer(movingHbar(ONE_HBAR).between(GENESIS, "3")),
                        usableTxnIdNamed(DUPLICATE_TXN_ID).payerId(PAYER),
                        tokenDelete(TOKEN)
                                .payingWith(PAYER)
                                .signedBy(PAYER, ADMIN_KEY)
                                .setNode(4)
                                .txnId(DUPLICATE_TXN_ID)
                                .via(tokenDeleteTxn)
                                .logged(),
                        tokenDelete(TOKEN)
                                .payingWith(PAYER)
                                .signedBy(PAYER, ADMIN_KEY)
                                .setNode(3)
                                .txnId(DUPLICATE_TXN_ID)
                                .via("tokenDeleteDuplicateTxn")
                                .hasPrecheck(DUPLICATE_TRANSACTION),
                        validateChargedUsdWithinWithTxnSize(
                                tokenDeleteTxn,
                                txnSize -> expectedTokenDeleteFullFeeUsd(
                                        Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateChargedAccount(tokenDeleteTxn, PAYER));
            }
        }

        @Nested
        @DisplayName("TokenDelete Failures on Pre-Handle")
        class TokenDeleteFailuresOnPreHandle {

            @LeakyEmbeddedHapiTest(reason = MUST_SKIP_INGEST)
            @DisplayName("TokenDelete - invalid payer signature fails on pre-handle - network fee only")
            final Stream<DynamicTest> tokenDeleteInvalidPayerSigFailsOnPreHandle() {
                final String INNER_ID = "token-delete-txn-inner-id";

                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
                SigControl invalidSig = keyShape.signedWith(sigs(ON, OFF));

                return hapiTest(
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        newKeyNamed(ADMIN_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .adminKey(ADMIN_KEY)
                                .treasury(TREASURY),
                        cryptoTransfer(movingHbar(ONE_HBAR).between(DEFAULT_PAYER, "4")),
                        tokenDelete(TOKEN)
                                .payingWith(PAYER)
                                .sigControl(forKey(PAYER_KEY, invalidSig))
                                .signedBy(PAYER, ADMIN_KEY)
                                .setNode("0.0.4")
                                .via(INNER_ID)
                                .hasKnownStatus(INVALID_PAYER_SIGNATURE),
                        getTxnRecord(INNER_ID).assertingNothingAboutHashes().logged(),
                        validateChargedUsdWithinWithTxnSize(
                                INNER_ID,
                                txnSize -> expectedTokenDeleteNetworkFeeOnlyUsd(
                                        Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                                0.1));
            }
        }
    }
}
