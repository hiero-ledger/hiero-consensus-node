// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip1261;

import static com.hedera.services.bdd.junit.EmbeddedReason.MUST_SKIP_INGEST;
import static com.hedera.services.bdd.junit.TestTags.ONLY_SUBPROCESS;
import static com.hedera.services.bdd.junit.TestTags.SIMPLE_FEES;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.keys.ControlForKey.forKey;
import static com.hedera.services.bdd.spec.keys.KeyShape.SIMPLE;
import static com.hedera.services.bdd.spec.keys.KeyShape.sigs;
import static com.hedera.services.bdd.spec.keys.KeyShape.threshOf;
import static com.hedera.services.bdd.spec.keys.SigControl.OFF;
import static com.hedera.services.bdd.spec.keys.SigControl.ON;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenPause;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenUnpause;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingHbar;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.usableTxnIdNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedAccount;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTokenPauseFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTokenPauseNetworkFeeOnlyUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTokenUnpauseFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTokenUnpauseNetworkFeeOnlyUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.validateChargedUsdWithinWithTxnSize;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.DUPLICATE_TRANSACTION;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_PAYER_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_TX_FEE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_PAYER_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TRANSACTION_DURATION;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TRANSACTION_START;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.MEMO_TOO_LONG;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.RECORD_NOT_FOUND;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_HAS_NO_PAUSE_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TRANSACTION_EXPIRED;
import static com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON;
import static org.hiero.hapi.support.fees.Extra.PROCESSING_BYTES;
import static org.hiero.hapi.support.fees.Extra.SIGNATURES;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyEmbeddedHapiTest;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.keys.KeyShape;
import com.hedera.services.bdd.spec.keys.SigControl;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;

/**
 * Tests for TokenPause and TokenUnpause simple fees with extras.
 * Validates that fees are correctly calculated based on:
 * - Number of signatures (extras beyond included)
 */
@Tag(SIMPLE_FEES)
@HapiTestLifecycle
public class TokenPauseSimpleFeesTest {

    private static final String PAYER = "payer";
    private static final String TREASURY = "treasury";
    private static final String PAUSE_KEY = "pauseKey";
    private static final String PAYER_KEY = "payerKey";
    private static final String TOKEN = "fungibleToken";
    private static final String pauseTxn = "pauseTxn";
    private static final String unpauseTxn = "unpauseTxn";
    private static final String DUPLICATE_TXN_ID = "duplicatePauseTxnId";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(Map.of("fees.simpleFeesEnabled", "true"));
    }

    @Nested
    @DisplayName("TokenPause Simple Fees Positive Test Cases")
    class TokenPausePositiveTestCases {

        @HapiTest
        @DisplayName("TokenPause - base fees")
        final Stream<DynamicTest> tokenPauseBaseFee() {
            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(TREASURY).balance(0L),
                    newKeyNamed(PAUSE_KEY),
                    tokenCreate(TOKEN)
                            .tokenType(FUNGIBLE_COMMON)
                            .pauseKey(PAUSE_KEY)
                            .treasury(TREASURY)
                            .payingWith(PAYER),
                    tokenPause(TOKEN)
                            .payingWith(PAYER)
                            .signedBy(PAYER, PAUSE_KEY)
                            .via(pauseTxn),
                    validateChargedUsdWithinWithTxnSize(
                            pauseTxn,
                            txnSize -> expectedTokenPauseFullFeeUsd(
                                    Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(pauseTxn, PAYER));
        }

        @HapiTest
        @DisplayName("TokenPause with threshold payer key - extra signatures")
        final Stream<DynamicTest> tokenPauseWithThresholdPayerKey() {
            KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
            SigControl validSig = keyShape.signedWith(sigs(ON, ON));

            return hapiTest(
                    newKeyNamed(PAYER_KEY).shape(keyShape),
                    cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(TREASURY).balance(0L),
                    newKeyNamed(PAUSE_KEY),
                    tokenCreate(TOKEN)
                            .tokenType(FUNGIBLE_COMMON)
                            .pauseKey(PAUSE_KEY)
                            .treasury(TREASURY)
                            .payingWith(PAYER)
                            .sigControl(forKey(PAYER_KEY, validSig)),
                    tokenPause(TOKEN)
                            .payingWith(PAYER)
                            .sigControl(forKey(PAYER_KEY, validSig))
                            .signedBy(PAYER, PAUSE_KEY)
                            .via(pauseTxn),
                    validateChargedUsdWithinWithTxnSize(
                            pauseTxn,
                            txnSize -> expectedTokenPauseFullFeeUsd(
                                    Map.of(SIGNATURES, 3L, PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(pauseTxn, PAYER));
        }

        @HapiTest
        @DisplayName("TokenPause with threshold pause key - extra signatures")
        final Stream<DynamicTest> tokenPauseWithThresholdPauseKey() {
            KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
            SigControl validSig = keyShape.signedWith(sigs(ON, ON));

            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(TREASURY).balance(0L),
                    newKeyNamed(PAUSE_KEY).shape(keyShape),
                    tokenCreate(TOKEN)
                            .tokenType(FUNGIBLE_COMMON)
                            .pauseKey(PAUSE_KEY)
                            .treasury(TREASURY)
                            .payingWith(PAYER)
                            .signedBy(PAYER, TREASURY, PAUSE_KEY)
                            .sigControl(forKey(PAUSE_KEY, validSig)),
                    tokenPause(TOKEN)
                            .payingWith(PAYER)
                            .signedBy(PAYER, PAUSE_KEY)
                            .sigControl(forKey(PAUSE_KEY, validSig))
                            .via(pauseTxn),
                    validateChargedUsdWithinWithTxnSize(
                            pauseTxn,
                            txnSize -> expectedTokenPauseFullFeeUsd(
                                    Map.of(SIGNATURES, 3L, PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(pauseTxn, PAYER));
        }

        @HapiTest
        @DisplayName("TokenPause with large payer key - extra processing bytes fee")
        final Stream<DynamicTest> tokenPauseLargeKeyExtraProcessingBytesFee() {
            KeyShape keyShape = threshOf(
                    1, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE,
                    SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE);
            SigControl allSigned = keyShape.signedWith(
                    sigs(ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON));

            return hapiTest(
                    newKeyNamed(PAYER_KEY).shape(keyShape),
                    cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(TREASURY).balance(0L),
                    newKeyNamed(PAUSE_KEY),
                    tokenCreate(TOKEN)
                            .tokenType(FUNGIBLE_COMMON)
                            .pauseKey(PAUSE_KEY)
                            .treasury(TREASURY)
                            .payingWith(PAYER)
                            .sigControl(forKey(PAYER_KEY, allSigned)),
                    tokenPause(TOKEN)
                            .payingWith(PAYER)
                            .sigControl(forKey(PAYER_KEY, allSigned))
                            .signedBy(PAYER, PAUSE_KEY)
                            .via(pauseTxn),
                    validateChargedUsdWithinWithTxnSize(
                            pauseTxn,
                            txnSize -> expectedTokenPauseFullFeeUsd(
                                    Map.of(SIGNATURES, 21L, PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(pauseTxn, PAYER));
        }

        @HapiTest
        @DisplayName("TokenPause with very large payer key below oversize - extra processing bytes fee")
        final Stream<DynamicTest> tokenPauseVeryLargeKeyBelowOversizeFee() {
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
                    cryptoCreate(TREASURY).balance(0L),
                    newKeyNamed(PAUSE_KEY),
                    tokenCreate(TOKEN)
                            .tokenType(FUNGIBLE_COMMON)
                            .pauseKey(PAUSE_KEY)
                            .treasury(TREASURY)
                            .payingWith(PAYER)
                            .sigControl(forKey(PAYER_KEY, allSigned)),
                    tokenPause(TOKEN)
                            .payingWith(PAYER)
                            .sigControl(forKey(PAYER_KEY, allSigned))
                            .signedBy(PAYER, PAUSE_KEY)
                            .via(pauseTxn),
                    validateChargedUsdWithinWithTxnSize(
                            pauseTxn,
                            txnSize -> expectedTokenPauseFullFeeUsd(
                                    Map.of(SIGNATURES, 42L, PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(pauseTxn, PAYER));
        }
    }

    @Nested
    @DisplayName("TokenUnpause Simple Fees Positive Test Cases")
    class TokenUnpausePositiveTestCases {

        @HapiTest
        @DisplayName("TokenUnpause - base fees")
        final Stream<DynamicTest> tokenUnpauseBaseFee() {
            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(TREASURY).balance(0L),
                    newKeyNamed(PAUSE_KEY),
                    tokenCreate(TOKEN)
                            .tokenType(FUNGIBLE_COMMON)
                            .pauseKey(PAUSE_KEY)
                            .treasury(TREASURY)
                            .payingWith(PAYER),
                    tokenPause(TOKEN).payingWith(PAYER).signedBy(PAYER, PAUSE_KEY),
                    tokenUnpause(TOKEN)
                            .payingWith(PAYER)
                            .signedBy(PAYER, PAUSE_KEY)
                            .via(unpauseTxn),
                    validateChargedUsdWithinWithTxnSize(
                            unpauseTxn,
                            txnSize -> expectedTokenUnpauseFullFeeUsd(
                                    Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(unpauseTxn, PAYER));
        }

        @HapiTest
        @DisplayName("TokenUnpause with threshold payer key - extra signatures")
        final Stream<DynamicTest> tokenUnpauseWithThresholdPayerKey() {
            KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
            SigControl validSig = keyShape.signedWith(sigs(ON, ON));

            return hapiTest(
                    newKeyNamed(PAYER_KEY).shape(keyShape),
                    cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(TREASURY).balance(0L),
                    newKeyNamed(PAUSE_KEY),
                    tokenCreate(TOKEN)
                            .tokenType(FUNGIBLE_COMMON)
                            .pauseKey(PAUSE_KEY)
                            .treasury(TREASURY)
                            .payingWith(PAYER)
                            .sigControl(forKey(PAYER_KEY, validSig)),
                    tokenPause(TOKEN)
                            .payingWith(PAYER)
                            .sigControl(forKey(PAYER_KEY, validSig))
                            .signedBy(PAYER, PAUSE_KEY),
                    tokenUnpause(TOKEN)
                            .payingWith(PAYER)
                            .sigControl(forKey(PAYER_KEY, validSig))
                            .signedBy(PAYER, PAUSE_KEY)
                            .via(unpauseTxn),
                    validateChargedUsdWithinWithTxnSize(
                            unpauseTxn,
                            txnSize -> expectedTokenUnpauseFullFeeUsd(
                                    Map.of(SIGNATURES, 3L, PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(unpauseTxn, PAYER));
        }

        @HapiTest
        @DisplayName("TokenUnpause with large payer key - extra processing bytes fee")
        final Stream<DynamicTest> tokenUnpauseLargeKeyExtraProcessingBytesFee() {
            KeyShape keyShape = threshOf(
                    1, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE,
                    SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE);
            SigControl allSigned = keyShape.signedWith(
                    sigs(ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON));

            return hapiTest(
                    newKeyNamed(PAYER_KEY).shape(keyShape),
                    cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(TREASURY).balance(0L),
                    newKeyNamed(PAUSE_KEY),
                    tokenCreate(TOKEN)
                            .tokenType(FUNGIBLE_COMMON)
                            .pauseKey(PAUSE_KEY)
                            .treasury(TREASURY)
                            .payingWith(PAYER)
                            .sigControl(forKey(PAYER_KEY, allSigned)),
                    tokenPause(TOKEN)
                            .payingWith(PAYER)
                            .sigControl(forKey(PAYER_KEY, allSigned))
                            .signedBy(PAYER, PAUSE_KEY),
                    tokenUnpause(TOKEN)
                            .payingWith(PAYER)
                            .sigControl(forKey(PAYER_KEY, allSigned))
                            .signedBy(PAYER, PAUSE_KEY)
                            .via(unpauseTxn),
                    validateChargedUsdWithinWithTxnSize(
                            unpauseTxn,
                            txnSize -> expectedTokenUnpauseFullFeeUsd(
                                    Map.of(SIGNATURES, 21L, PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(unpauseTxn, PAYER));
        }

        @HapiTest
        @DisplayName("TokenUnpause with very large payer key below oversize - extra processing bytes fee")
        final Stream<DynamicTest> tokenUnpauseVeryLargeKeyBelowOversizeFee() {
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
                    cryptoCreate(TREASURY).balance(0L),
                    newKeyNamed(PAUSE_KEY),
                    tokenCreate(TOKEN)
                            .tokenType(FUNGIBLE_COMMON)
                            .pauseKey(PAUSE_KEY)
                            .treasury(TREASURY)
                            .payingWith(PAYER)
                            .sigControl(forKey(PAYER_KEY, allSigned)),
                    tokenPause(TOKEN)
                            .payingWith(PAYER)
                            .sigControl(forKey(PAYER_KEY, allSigned))
                            .signedBy(PAYER, PAUSE_KEY),
                    tokenUnpause(TOKEN)
                            .payingWith(PAYER)
                            .sigControl(forKey(PAYER_KEY, allSigned))
                            .signedBy(PAYER, PAUSE_KEY)
                            .via(unpauseTxn),
                    validateChargedUsdWithinWithTxnSize(
                            unpauseTxn,
                            txnSize -> expectedTokenUnpauseFullFeeUsd(
                                    Map.of(SIGNATURES, 42L, PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(unpauseTxn, PAYER));
        }
    }

    @Nested
    @DisplayName("TokenPause Simple Fees Negative Test Cases")
    class TokenPauseNegativeTestCases {

        @Nested
        @DisplayName("TokenPause Failures on Ingest and Handle")
        class TokenPauseFailuresOnIngestAndHandle {

            @HapiTest
            @DisplayName("TokenPause - insufficient tx fee fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenPauseInsufficientTxFeeFailsOnIngest() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        newKeyNamed(PAUSE_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .pauseKey(PAUSE_KEY)
                                .treasury(TREASURY)
                                .payingWith(PAYER),
                        tokenPause(TOKEN)
                                .payingWith(PAYER)
                                .signedBy(PAYER, PAUSE_KEY)
                                .fee(1L) // Fee too low
                                .via(pauseTxn)
                                .hasPrecheck(INSUFFICIENT_TX_FEE),
                        getTxnRecord(pauseTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TokenPause - threshold payer key with invalid signature fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenPauseThresholdKeyInvalidSigFailsOnIngest() {
                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
                SigControl validSig = keyShape.signedWith(sigs(ON, ON));
                SigControl invalidSig = keyShape.signedWith(sigs(ON, OFF));

                return hapiTest(
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        newKeyNamed(PAUSE_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .pauseKey(PAUSE_KEY)
                                .treasury(TREASURY)
                                .payingWith(PAYER)
                                .sigControl(forKey(PAYER_KEY, validSig)),
                        tokenPause(TOKEN)
                                .payingWith(PAYER)
                                .sigControl(forKey(PAYER_KEY, invalidSig))
                                .signedBy(PAYER, PAUSE_KEY)
                                .via(pauseTxn)
                                .hasPrecheck(INVALID_SIGNATURE),
                        getTxnRecord(pauseTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TokenPause - insufficient payer balance fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenPauseInsufficientPayerBalanceFailsOnIngest() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HBAR / 100_000L),
                        cryptoCreate(TREASURY).balance(0L),
                        newKeyNamed(PAUSE_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .pauseKey(PAUSE_KEY)
                                .treasury(GENESIS),
                        tokenPause(TOKEN)
                                .payingWith(PAYER)
                                .signedBy(PAYER, PAUSE_KEY)
                                .via(pauseTxn)
                                .hasPrecheck(INSUFFICIENT_PAYER_BALANCE),
                        getTxnRecord(pauseTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TokenPause - memo too long fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenPauseMemoTooLongFailsOnIngest() {
                final var LONG_MEMO = "x".repeat(1025);
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        newKeyNamed(PAUSE_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .pauseKey(PAUSE_KEY)
                                .treasury(TREASURY)
                                .payingWith(PAYER),
                        tokenPause(TOKEN)
                                .payingWith(PAYER)
                                .signedBy(PAYER, PAUSE_KEY)
                                .memo(LONG_MEMO)
                                .via(pauseTxn)
                                .hasPrecheck(MEMO_TOO_LONG),
                        getTxnRecord(pauseTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TokenPause - expired transaction fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenPauseExpiredTransactionFailsOnIngest() {
                final var expiredTxnId = "expiredPauseTxn";
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        newKeyNamed(PAUSE_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .pauseKey(PAUSE_KEY)
                                .treasury(TREASURY)
                                .payingWith(PAYER),
                        usableTxnIdNamed(expiredTxnId).modifyValidStart(-3_600L).payerId(PAYER),
                        tokenPause(TOKEN)
                                .payingWith(PAYER)
                                .signedBy(PAYER, PAUSE_KEY)
                                .txnId(expiredTxnId)
                                .via(pauseTxn)
                                .hasPrecheck(TRANSACTION_EXPIRED),
                        getTxnRecord(pauseTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TokenPause - too far start time fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenPauseTooFarStartTimeFailsOnIngest() {
                final var futureTxnId = "futurePauseTxn";
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        newKeyNamed(PAUSE_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .pauseKey(PAUSE_KEY)
                                .treasury(TREASURY)
                                .payingWith(PAYER),
                        usableTxnIdNamed(futureTxnId).modifyValidStart(3_600L).payerId(PAYER),
                        tokenPause(TOKEN)
                                .payingWith(PAYER)
                                .signedBy(PAYER, PAUSE_KEY)
                                .txnId(futureTxnId)
                                .via(pauseTxn)
                                .hasPrecheck(INVALID_TRANSACTION_START),
                        getTxnRecord(pauseTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TokenPause - invalid transaction duration fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenPauseInvalidTransactionDurationFailsOnIngest() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        newKeyNamed(PAUSE_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .pauseKey(PAUSE_KEY)
                                .treasury(TREASURY)
                                .payingWith(PAYER),
                        tokenPause(TOKEN)
                                .payingWith(PAYER)
                                .signedBy(PAYER, PAUSE_KEY)
                                .validDurationSecs(0)
                                .via(pauseTxn)
                                .hasPrecheck(INVALID_TRANSACTION_DURATION),
                        getTxnRecord("pauseTxn").hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TokenPause - duplicate transaction fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenPauseDuplicateTxnFailsOnIngest() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        newKeyNamed(PAUSE_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .pauseKey(PAUSE_KEY)
                                .treasury(TREASURY)
                                .payingWith(PAYER),
                        tokenPause(TOKEN)
                                .payingWith(PAYER)
                                .signedBy(PAYER, PAUSE_KEY)
                                .via("pauseFirst"),
                        tokenUnpause(TOKEN)
                                .payingWith(PAYER)
                                .signedBy(PAYER, PAUSE_KEY)
                                .txnId("pauseFirst")
                                .via(pauseTxn)
                                .hasPrecheck(DUPLICATE_TRANSACTION));
            }

            @HapiTest
            @DisplayName("TokenPause - missing pause key signature fails on handle - full fees charged")
            final Stream<DynamicTest> tokenPauseMissingPauseKeySignatureFailsOnHandle() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        newKeyNamed(PAUSE_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .pauseKey(PAUSE_KEY)
                                .treasury(TREASURY)
                                .payingWith(PAYER),
                        tokenPause(TOKEN)
                                .payingWith(PAYER)
                                .signedBy(PAYER) // Missing pause key signature
                                .via(pauseTxn)
                                .hasKnownStatus(INVALID_SIGNATURE),
                        validateChargedUsdWithinWithTxnSize(
                                pauseTxn,
                                txnSize -> expectedTokenPauseFullFeeUsd(
                                        Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateChargedAccount(pauseTxn, PAYER));
            }

            @HapiTest
            @DisplayName("TokenPause - no pause key fails on handle - full fees charged")
            final Stream<DynamicTest> tokenPauseNoPauseKeyFailsOnHandle() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                // No pause key
                                .treasury(TREASURY)
                                .payingWith(PAYER),
                        tokenPause(TOKEN)
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .via(pauseTxn)
                                .hasKnownStatus(TOKEN_HAS_NO_PAUSE_KEY),
                        validateChargedUsdWithinWithTxnSize(
                                pauseTxn,
                                txnSize -> expectedTokenPauseFullFeeUsd(
                                        Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateChargedAccount(pauseTxn, PAYER));
            }
        }

        @Nested
        @Tag(ONLY_SUBPROCESS)
        @DisplayName("TokenPause Simple Fees Duplicate on Handle")
        class TokenPauseDuplicateOnHandle {

            @LeakyHapiTest
            @DisplayName("TokenPause - duplicate transaction fails on handle - payer charged for first only")
            final Stream<DynamicTest> tokenPauseDuplicateFailsOnHandle() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        newKeyNamed(PAUSE_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .pauseKey(PAUSE_KEY)
                                .treasury(TREASURY)
                                .payingWith(PAYER),
                        cryptoTransfer(movingHbar(ONE_HBAR).between(DEFAULT_PAYER, "3")),
                        usableTxnIdNamed(DUPLICATE_TXN_ID).payerId(PAYER),
                        tokenPause(TOKEN)
                                .payingWith(PAYER)
                                .signedBy(PAYER, PAUSE_KEY)
                                .setNode(4)
                                .txnId(DUPLICATE_TXN_ID)
                                .via(pauseTxn),
                        tokenPause(TOKEN)
                                .payingWith(PAYER)
                                .signedBy(PAYER, PAUSE_KEY)
                                .txnId(DUPLICATE_TXN_ID)
                                .setNode(3)
                                .hasPrecheck(DUPLICATE_TRANSACTION),
                        validateChargedUsdWithinWithTxnSize(
                                pauseTxn,
                                txnSize -> expectedTokenPauseFullFeeUsd(
                                        Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateChargedAccount(pauseTxn, PAYER));
            }
        }

        @Nested
        @DisplayName("TokenPause Failures on Pre-Handle")
        class TokenPauseFailuresOnPreHandle {

            @LeakyEmbeddedHapiTest(reason = MUST_SKIP_INGEST)
            @DisplayName("TokenPause - invalid payer signature fails on pre-handle - network fee only")
            final Stream<DynamicTest> tokenPauseInvalidPayerSigFailsOnPreHandle() {
                final String INNER_ID = "pause-txn-inner-id";

                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
                SigControl invalidSig = keyShape.signedWith(sigs(ON, OFF));

                return hapiTest(
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        newKeyNamed(PAUSE_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .pauseKey(PAUSE_KEY)
                                .treasury(TREASURY),
                        cryptoTransfer(movingHbar(ONE_HBAR).between(DEFAULT_PAYER, "4")),
                        tokenPause(TOKEN)
                                .payingWith(PAYER)
                                .sigControl(forKey(PAYER_KEY, invalidSig))
                                .signedBy(PAYER, PAUSE_KEY)
                                .setNode("4")
                                .via(INNER_ID)
                                .hasKnownStatus(INVALID_PAYER_SIGNATURE),
                        getTxnRecord(INNER_ID).assertingNothingAboutHashes().logged(),
                        validateChargedUsdWithinWithTxnSize(
                                INNER_ID,
                                txnSize -> expectedTokenPauseNetworkFeeOnlyUsd(
                                        Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateChargedAccount(INNER_ID, "4"));
            }
        }
    }

    @Nested
    @DisplayName("TokenUnpause Simple Fees Negative Test Cases")
    class TokenUnpauseNegativeTestCases {

        @Nested
        @DisplayName("TokenUnpause Failures on Ingest and Handle")
        class TokenUnpauseFailuresOnIngestAndHandle {

            @HapiTest
            @DisplayName("TokenUnpause - insufficient tx fee fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenUnpauseInsufficientTxFeeFailsOnIngest() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        newKeyNamed(PAUSE_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .pauseKey(PAUSE_KEY)
                                .treasury(TREASURY)
                                .payingWith(PAYER),
                        tokenPause(TOKEN).payingWith(PAYER).signedBy(PAYER, PAUSE_KEY),
                        tokenUnpause(TOKEN)
                                .payingWith(PAYER)
                                .signedBy(PAYER, PAUSE_KEY)
                                .fee(1L) // Fee too low
                                .via(unpauseTxn)
                                .hasPrecheck(INSUFFICIENT_TX_FEE),
                        getTxnRecord(unpauseTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TokenUnpause - threshold payer key with invalid signature fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenUnpauseThresholdKeyInvalidSigFailsOnIngest() {
                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
                SigControl validSig = keyShape.signedWith(sigs(ON, ON));
                SigControl invalidSig = keyShape.signedWith(sigs(ON, OFF));

                return hapiTest(
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        newKeyNamed(PAUSE_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .pauseKey(PAUSE_KEY)
                                .treasury(TREASURY)
                                .payingWith(PAYER)
                                .sigControl(forKey(PAYER_KEY, validSig)),
                        tokenPause(TOKEN)
                                .payingWith(PAYER)
                                .sigControl(forKey(PAYER_KEY, validSig))
                                .signedBy(PAYER, PAUSE_KEY),
                        tokenUnpause(TOKEN)
                                .payingWith(PAYER)
                                .sigControl(forKey(PAYER_KEY, invalidSig))
                                .signedBy(PAYER, PAUSE_KEY)
                                .via(unpauseTxn)
                                .hasPrecheck(INVALID_SIGNATURE),
                        getTxnRecord(unpauseTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TokenUnpause - insufficient payer balance fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenUnpauseInsufficientPayerBalanceFailsOnIngest() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HBAR / 100_000L),
                        newKeyNamed(PAUSE_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .pauseKey(PAUSE_KEY)
                                .treasury(GENESIS),
                        tokenUnpause(TOKEN)
                                .payingWith(PAYER)
                                .signedBy(PAYER, PAUSE_KEY)
                                .via(unpauseTxn)
                                .hasPrecheck(INSUFFICIENT_PAYER_BALANCE),
                        getTxnRecord(unpauseTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TokenUnpause - memo too long fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenUnpauseMemoTooLongFailsOnIngest() {
                final var LONG_MEMO = "x".repeat(1025);
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        newKeyNamed(PAUSE_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .pauseKey(PAUSE_KEY)
                                .treasury(TREASURY)
                                .payingWith(PAYER),
                        tokenPause(TOKEN).payingWith(PAYER).signedBy(PAYER, PAUSE_KEY),
                        tokenUnpause(TOKEN)
                                .payingWith(PAYER)
                                .signedBy(PAYER, PAUSE_KEY)
                                .memo(LONG_MEMO)
                                .via(unpauseTxn)
                                .hasPrecheck(MEMO_TOO_LONG),
                        getTxnRecord(unpauseTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TokenUnpause - expired transaction fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenUnpauseExpiredTransactionFailsOnIngest() {
                final var expiredTxnId = "expiredUnpauseTxn";
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        newKeyNamed(PAUSE_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .pauseKey(PAUSE_KEY)
                                .treasury(TREASURY)
                                .payingWith(PAYER),
                        tokenPause(TOKEN).payingWith(PAYER).signedBy(PAYER, PAUSE_KEY),
                        usableTxnIdNamed(expiredTxnId).modifyValidStart(-3_600L).payerId(PAYER),
                        tokenUnpause(TOKEN)
                                .payingWith(PAYER)
                                .signedBy(PAYER, PAUSE_KEY)
                                .txnId(expiredTxnId)
                                .via(unpauseTxn)
                                .hasPrecheck(TRANSACTION_EXPIRED),
                        getTxnRecord(unpauseTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TokenUnpause - too far start time fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenUnpauseTooFarStartTimeFailsOnIngest() {
                final var futureTxnId = "futureUnpauseTxn";
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        newKeyNamed(PAUSE_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .pauseKey(PAUSE_KEY)
                                .treasury(TREASURY)
                                .payingWith(PAYER),
                        tokenPause(TOKEN).payingWith(PAYER).signedBy(PAYER, PAUSE_KEY),
                        usableTxnIdNamed(futureTxnId).modifyValidStart(3_600L).payerId(PAYER),
                        tokenUnpause(TOKEN)
                                .payingWith(PAYER)
                                .signedBy(PAYER, PAUSE_KEY)
                                .txnId(futureTxnId)
                                .via(unpauseTxn)
                                .hasPrecheck(INVALID_TRANSACTION_START),
                        getTxnRecord(unpauseTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TokenUnpause - invalid transaction duration fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenUnpauseInvalidTransactionDurationFailsOnIngest() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        newKeyNamed(PAUSE_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .pauseKey(PAUSE_KEY)
                                .treasury(TREASURY)
                                .payingWith(PAYER),
                        tokenPause(TOKEN).payingWith(PAYER).signedBy(PAYER, PAUSE_KEY),
                        tokenUnpause(TOKEN)
                                .payingWith(PAYER)
                                .signedBy(PAYER, PAUSE_KEY)
                                .validDurationSecs(0)
                                .via(unpauseTxn)
                                .hasPrecheck(INVALID_TRANSACTION_DURATION),
                        getTxnRecord(unpauseTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TokenUnpause - duplicate transaction fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenUnpauseDuplicateTxnFailsOnIngest() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        newKeyNamed(PAUSE_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .pauseKey(PAUSE_KEY)
                                .treasury(TREASURY)
                                .payingWith(PAYER),
                        tokenPause(TOKEN).payingWith(PAYER).signedBy(PAYER, PAUSE_KEY),
                        tokenUnpause(TOKEN)
                                .payingWith(PAYER)
                                .signedBy(PAYER, PAUSE_KEY)
                                .via("unpauseFirst"),
                        tokenPause(TOKEN)
                                .payingWith(PAYER)
                                .signedBy(PAYER, PAUSE_KEY)
                                .txnId("unpauseFirst")
                                .via(unpauseTxn)
                                .hasPrecheck(DUPLICATE_TRANSACTION));
            }

            @HapiTest
            @DisplayName("TokenUnpause - missing pause key signature fails on handle - full fees charged")
            final Stream<DynamicTest> tokenUnpauseMissingPauseKeySignatureFailsOnHandle() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        newKeyNamed(PAUSE_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .pauseKey(PAUSE_KEY)
                                .treasury(TREASURY)
                                .payingWith(PAYER),
                        tokenPause(TOKEN).payingWith(PAYER).signedBy(PAYER, PAUSE_KEY),
                        tokenUnpause(TOKEN)
                                .payingWith(PAYER)
                                .signedBy(PAYER) // Missing pause key signature
                                .via(unpauseTxn)
                                .hasKnownStatus(INVALID_SIGNATURE),
                        validateChargedUsdWithinWithTxnSize(
                                unpauseTxn,
                                txnSize -> expectedTokenUnpauseFullFeeUsd(
                                        Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateChargedAccount(unpauseTxn, PAYER));
            }

            @HapiTest
            @DisplayName("TokenUnpause - no pause key fails on handle - full fees charged")
            final Stream<DynamicTest> tokenUnpauseNoPauseKeyFailsOnHandle() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                // No pause key
                                .treasury(TREASURY)
                                .payingWith(PAYER),
                        tokenUnpause(TOKEN)
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .via(unpauseTxn)
                                .hasKnownStatus(TOKEN_HAS_NO_PAUSE_KEY),
                        validateChargedUsdWithinWithTxnSize(
                                unpauseTxn,
                                txnSize -> expectedTokenUnpauseFullFeeUsd(
                                        Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateChargedAccount(unpauseTxn, PAYER));
            }
        }

        @Nested
        @Tag(ONLY_SUBPROCESS)
        @DisplayName("TokenUnpause Simple Fees Duplicate on Handle")
        class TokenUnpauseDuplicateOnHandle {

            @LeakyHapiTest
            @DisplayName("TokenUnpause - duplicate transaction fails on handle - payer charged for first only")
            final Stream<DynamicTest> tokenUnpauseDuplicateFailsOnHandle() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        newKeyNamed(PAUSE_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .pauseKey(PAUSE_KEY)
                                .treasury(TREASURY)
                                .payingWith(PAYER),
                        tokenPause(TOKEN).payingWith(PAYER).signedBy(PAYER, PAUSE_KEY),
                        cryptoTransfer(movingHbar(ONE_HBAR).between(DEFAULT_PAYER, "3")),
                        usableTxnIdNamed(DUPLICATE_TXN_ID).payerId(PAYER),
                        tokenUnpause(TOKEN)
                                .payingWith(PAYER)
                                .signedBy(PAYER, PAUSE_KEY)
                                .setNode(4)
                                .txnId(DUPLICATE_TXN_ID)
                                .via(unpauseTxn),
                        tokenUnpause(TOKEN)
                                .payingWith(PAYER)
                                .signedBy(PAYER, PAUSE_KEY)
                                .txnId(DUPLICATE_TXN_ID)
                                .setNode(3)
                                .hasPrecheck(DUPLICATE_TRANSACTION),
                        validateChargedUsdWithinWithTxnSize(
                                unpauseTxn,
                                txnSize -> expectedTokenUnpauseFullFeeUsd(
                                        Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateChargedAccount(unpauseTxn, PAYER));
            }
        }

        @Nested
        @DisplayName("TokenUnpause Failures on Pre-Handle")
        class TokenUnpauseFailuresOnPreHandle {

            @LeakyEmbeddedHapiTest(reason = MUST_SKIP_INGEST)
            @DisplayName("TokenUnpause - invalid payer signature fails on pre-handle - network fee only")
            final Stream<DynamicTest> tokenUnpauseInvalidPayerSigFailsOnPreHandle() {
                final String INNER_ID = "unpause-txn-inner-id";

                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
                SigControl invalidSig = keyShape.signedWith(sigs(ON, OFF));

                return hapiTest(
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        newKeyNamed(PAUSE_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .pauseKey(PAUSE_KEY)
                                .treasury(TREASURY),
                        tokenPause(TOKEN),
                        cryptoTransfer(movingHbar(ONE_HBAR).between(DEFAULT_PAYER, "4")),
                        tokenUnpause(TOKEN)
                                .payingWith(PAYER)
                                .sigControl(forKey(PAYER_KEY, invalidSig))
                                .signedBy(PAYER, PAUSE_KEY)
                                .setNode("4")
                                .via(INNER_ID)
                                .hasKnownStatus(INVALID_PAYER_SIGNATURE),
                        getTxnRecord(INNER_ID).assertingNothingAboutHashes().logged(),
                        validateChargedUsdWithinWithTxnSize(
                                INNER_ID,
                                txnSize -> expectedTokenUnpauseNetworkFeeOnlyUsd(
                                        Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateChargedAccount(INNER_ID, "4"));
            }
        }
    }
}
