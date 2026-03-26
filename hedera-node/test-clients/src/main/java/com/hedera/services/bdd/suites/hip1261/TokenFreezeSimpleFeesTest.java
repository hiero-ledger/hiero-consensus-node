// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip1261;

import static com.hedera.services.bdd.junit.EmbeddedReason.MUST_SKIP_INGEST;
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
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenFreeze;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenUnfreeze;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingHbar;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.usableTxnIdNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedAccount;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTokenFreezeFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTokenFreezeNetworkFeeOnlyUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTokenUnfreezeFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTokenUnfreezeNetworkFeeOnlyUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.validateChargedUsdWithinWithTxnSize;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_FROZEN_FOR_TOKEN;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.DUPLICATE_TRANSACTION;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_PAYER_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_TX_FEE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_PAYER_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TRANSACTION_DURATION;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TRANSACTION_START;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.MEMO_TOO_LONG;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.RECORD_NOT_FOUND;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_HAS_NO_FREEZE_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_NOT_ASSOCIATED_TO_ACCOUNT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TRANSACTION_EXPIRED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TRANSACTION_OVERSIZE;
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
 * Tests for TokenFreeze and TokenUnfreeze simple fees with extras.
 * Validates that fees are correctly calculated based on:
 * - Number of signatures (extras beyond included)
 */
@Tag(SIMPLE_FEES)
@HapiTestLifecycle
public class TokenFreezeSimpleFeesTest {
    private static final String PAYER = "payer";
    private static final String TREASURY = "treasury";
    private static final String ACCOUNT = "account";
    private static final String FREEZE_KEY = "freezeKey";
    private static final String PAYER_KEY = "payerKey";
    private static final String TOKEN = "fungibleToken";
    private static final String freezeTxn = "freezeTxn";
    private static final String unfreezeTxn = "unfreezeTxn";
    private static final String DUPLICATE_TXN_ID = "duplicateFreezeTxnId";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(Map.of("fees.simpleFeesEnabled", "true"));
    }

    @Nested
    @DisplayName("TokenFreeze Simple Fees Positive Test Cases")
    class TokenFreezePositiveTestCases {

        @HapiTest
        @DisplayName("TokenFreeze - base fees")
        final Stream<DynamicTest> tokenFreezeBaseFee() {
            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(TREASURY).balance(0L),
                    cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                    newKeyNamed(FREEZE_KEY),
                    tokenCreate(TOKEN)
                            .tokenType(FUNGIBLE_COMMON)
                            .freezeKey(FREEZE_KEY)
                            .freezeDefault(false)
                            .treasury(TREASURY)
                            .payingWith(PAYER),
                    tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT),
                    tokenFreeze(TOKEN, ACCOUNT)
                            .payingWith(PAYER)
                            .signedBy(PAYER, FREEZE_KEY)
                            .via(freezeTxn),
                    validateChargedUsdWithinWithTxnSize(
                            freezeTxn,
                            txnSize -> expectedTokenFreezeFullFeeUsd(
                                    Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(freezeTxn, PAYER));
        }

        @HapiTest
        @DisplayName("TokenFreeze with threshold payer key - extra signatures")
        final Stream<DynamicTest> tokenFreezeWithThresholdPayerKey() {
            KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
            SigControl validSig = keyShape.signedWith(sigs(ON, ON));

            return hapiTest(
                    newKeyNamed(PAYER_KEY).shape(keyShape),
                    cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(TREASURY).balance(0L),
                    cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                    newKeyNamed(FREEZE_KEY),
                    tokenCreate(TOKEN)
                            .tokenType(FUNGIBLE_COMMON)
                            .freezeKey(FREEZE_KEY)
                            .freezeDefault(false)
                            .treasury(TREASURY)
                            .payingWith(PAYER)
                            .sigControl(forKey(PAYER_KEY, validSig)),
                    tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT),
                    tokenFreeze(TOKEN, ACCOUNT)
                            .payingWith(PAYER)
                            .sigControl(forKey(PAYER_KEY, validSig))
                            .signedBy(PAYER, FREEZE_KEY)
                            .via(freezeTxn),
                    validateChargedUsdWithinWithTxnSize(
                            freezeTxn,
                            txnSize -> expectedTokenFreezeFullFeeUsd(
                                    Map.of(SIGNATURES, 3L, PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(freezeTxn, PAYER));
        }

        @HapiTest
        @DisplayName("TokenFreeze with threshold freeze key - extra signatures")
        final Stream<DynamicTest> tokenFreezeWithThresholdFreezeKey() {
            KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
            SigControl validSig = keyShape.signedWith(sigs(ON, ON));

            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(TREASURY).balance(0L),
                    cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                    newKeyNamed(FREEZE_KEY).shape(keyShape),
                    tokenCreate(TOKEN)
                            .tokenType(FUNGIBLE_COMMON)
                            .freezeKey(FREEZE_KEY)
                            .freezeDefault(false)
                            .treasury(TREASURY)
                            .payingWith(PAYER)
                            .signedBy(PAYER, TREASURY, FREEZE_KEY)
                            .sigControl(forKey(FREEZE_KEY, validSig)),
                    tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT),
                    tokenFreeze(TOKEN, ACCOUNT)
                            .payingWith(PAYER)
                            .signedBy(PAYER, FREEZE_KEY)
                            .sigControl(forKey(FREEZE_KEY, validSig))
                            .via(freezeTxn),
                    validateChargedUsdWithinWithTxnSize(
                            freezeTxn,
                            txnSize -> expectedTokenFreezeFullFeeUsd(
                                    Map.of(SIGNATURES, 3L, PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(freezeTxn, PAYER));
        }

        @HapiTest
        @DisplayName("TokenFreeze with large payer key - extra processing bytes fee")
        final Stream<DynamicTest> tokenFreezeLargeKeyExtraProcessingBytesFee() {
            KeyShape keyShape = threshOf(
                    1, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE,
                    SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE);
            SigControl allSigned = keyShape.signedWith(
                    sigs(ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON));

            return hapiTest(
                    newKeyNamed(PAYER_KEY).shape(keyShape),
                    cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(TREASURY).balance(0L),
                    cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                    newKeyNamed(FREEZE_KEY),
                    tokenCreate(TOKEN)
                            .tokenType(FUNGIBLE_COMMON)
                            .freezeKey(FREEZE_KEY)
                            .freezeDefault(false)
                            .treasury(TREASURY)
                            .payingWith(PAYER)
                            .sigControl(forKey(PAYER_KEY, allSigned)),
                    tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT),
                    tokenFreeze(TOKEN, ACCOUNT)
                            .payingWith(PAYER)
                            .sigControl(forKey(PAYER_KEY, allSigned))
                            .signedBy(PAYER, FREEZE_KEY)
                            .via(freezeTxn),
                    validateChargedUsdWithinWithTxnSize(
                            freezeTxn,
                            txnSize -> expectedTokenFreezeFullFeeUsd(
                                    Map.of(SIGNATURES, 21L, PROCESSING_BYTES, (long) txnSize)),
                            0.1));
        }

        @HapiTest
        @DisplayName("TokenFreeze with very large payer key below oversize - extra processing bytes fee")
        final Stream<DynamicTest> tokenFreezeVeryLargeKeyBelowOversizeExtraProcessingBytesFee() {
            KeyShape keyShape = threshOf(
                    1, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE,
                    SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE,
                    SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE,
                    SIMPLE, SIMPLE, SIMPLE, SIMPLE);
            SigControl allSigned = keyShape.signedWith(sigs(
                    ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON,
                    ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON));

            return hapiTest(
                    newKeyNamed(PAYER_KEY).shape(keyShape),
                    cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(TREASURY).balance(0L),
                    cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                    newKeyNamed(FREEZE_KEY),
                    tokenCreate(TOKEN)
                            .tokenType(FUNGIBLE_COMMON)
                            .freezeKey(FREEZE_KEY)
                            .freezeDefault(false)
                            .treasury(TREASURY)
                            .payingWith(PAYER)
                            .sigControl(forKey(PAYER_KEY, allSigned)),
                    tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT),
                    tokenFreeze(TOKEN, ACCOUNT)
                            .payingWith(PAYER)
                            .sigControl(forKey(PAYER_KEY, allSigned))
                            .signedBy(PAYER, FREEZE_KEY)
                            .via(freezeTxn),
                    validateChargedUsdWithinWithTxnSize(
                            freezeTxn,
                            txnSize -> expectedTokenFreezeFullFeeUsd(
                                    Map.of(SIGNATURES, 41L, PROCESSING_BYTES, (long) txnSize)),
                            0.1));
        }
    }

    @Nested
    @DisplayName("TokenUnfreeze Simple Fees Positive Test Cases")
    class TokenUnfreezePositiveTestCases {

        @HapiTest
        @DisplayName("TokenUnfreeze - base fees")
        final Stream<DynamicTest> tokenUnfreezeBaseFee() {
            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(TREASURY).balance(0L),
                    cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                    newKeyNamed(FREEZE_KEY),
                    tokenCreate(TOKEN)
                            .tokenType(FUNGIBLE_COMMON)
                            .freezeKey(FREEZE_KEY)
                            .freezeDefault(true) // Token is created frozen
                            .treasury(TREASURY)
                            .payingWith(PAYER),
                    tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT),
                    tokenUnfreeze(TOKEN, ACCOUNT)
                            .payingWith(PAYER)
                            .signedBy(PAYER, FREEZE_KEY)
                            .via(unfreezeTxn),
                    validateChargedUsdWithinWithTxnSize(
                            unfreezeTxn,
                            txnSize -> expectedTokenUnfreezeFullFeeUsd(
                                    Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(unfreezeTxn, PAYER));
        }

        @HapiTest
        @DisplayName("TokenUnfreeze with threshold payer key - extra signatures")
        final Stream<DynamicTest> tokenUnfreezeWithThresholdPayerKey() {
            KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
            SigControl validSig = keyShape.signedWith(sigs(ON, ON));

            return hapiTest(
                    newKeyNamed(PAYER_KEY).shape(keyShape),
                    cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(TREASURY).balance(0L),
                    cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                    newKeyNamed(FREEZE_KEY),
                    tokenCreate(TOKEN)
                            .tokenType(FUNGIBLE_COMMON)
                            .freezeKey(FREEZE_KEY)
                            .freezeDefault(false)
                            .treasury(TREASURY)
                            .payingWith(PAYER)
                            .sigControl(forKey(PAYER_KEY, validSig)),
                    tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT),
                    tokenFreeze(TOKEN, ACCOUNT)
                            .payingWith(PAYER)
                            .sigControl(forKey(PAYER_KEY, validSig))
                            .signedBy(PAYER, FREEZE_KEY),
                    tokenUnfreeze(TOKEN, ACCOUNT)
                            .payingWith(PAYER)
                            .sigControl(forKey(PAYER_KEY, validSig))
                            .signedBy(PAYER, FREEZE_KEY)
                            .via(unfreezeTxn),
                    validateChargedUsdWithinWithTxnSize(
                            unfreezeTxn,
                            txnSize -> expectedTokenUnfreezeFullFeeUsd(
                                    Map.of(SIGNATURES, 3L, PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(unfreezeTxn, PAYER));
        }

        @HapiTest
        @DisplayName("TokenUnfreeze with large payer key - extra processing bytes fee")
        final Stream<DynamicTest> tokenUnfreezeLargeKeyExtraProcessingBytesFee() {
            KeyShape keyShape = threshOf(
                    1, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE,
                    SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE);
            SigControl allSigned = keyShape.signedWith(
                    sigs(ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON));

            return hapiTest(
                    newKeyNamed(PAYER_KEY).shape(keyShape),
                    cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(TREASURY).balance(0L),
                    cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                    newKeyNamed(FREEZE_KEY),
                    tokenCreate(TOKEN)
                            .tokenType(FUNGIBLE_COMMON)
                            .freezeKey(FREEZE_KEY)
                            .freezeDefault(true)
                            .treasury(TREASURY)
                            .payingWith(PAYER)
                            .sigControl(forKey(PAYER_KEY, allSigned)),
                    tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT),
                    tokenUnfreeze(TOKEN, ACCOUNT)
                            .payingWith(PAYER)
                            .sigControl(forKey(PAYER_KEY, allSigned))
                            .signedBy(PAYER, FREEZE_KEY)
                            .via(unfreezeTxn),
                    validateChargedUsdWithinWithTxnSize(
                            unfreezeTxn,
                            txnSize -> expectedTokenUnfreezeFullFeeUsd(
                                    Map.of(SIGNATURES, 21L, PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(unfreezeTxn, PAYER));
        }

        @HapiTest
        @DisplayName("TokenUnfreeze with very large payer key below oversize - extra processing bytes fee")
        final Stream<DynamicTest> tokenUnfreezeVeryLargeKeyBelowOversizeExtraProcessingBytesFee() {
            KeyShape keyShape = threshOf(
                    1, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE,
                    SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE,
                    SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE,
                    SIMPLE, SIMPLE, SIMPLE, SIMPLE);
            SigControl allSigned = keyShape.signedWith(sigs(
                    ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON,
                    ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON));

            return hapiTest(
                    newKeyNamed(PAYER_KEY).shape(keyShape),
                    cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(TREASURY).balance(0L),
                    cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                    newKeyNamed(FREEZE_KEY),
                    tokenCreate(TOKEN)
                            .tokenType(FUNGIBLE_COMMON)
                            .freezeKey(FREEZE_KEY)
                            .freezeDefault(true)
                            .treasury(TREASURY)
                            .payingWith(PAYER)
                            .sigControl(forKey(PAYER_KEY, allSigned)),
                    tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT),
                    tokenUnfreeze(TOKEN, ACCOUNT)
                            .payingWith(PAYER)
                            .sigControl(forKey(PAYER_KEY, allSigned))
                            .signedBy(PAYER, FREEZE_KEY)
                            .via(unfreezeTxn),
                    validateChargedUsdWithinWithTxnSize(
                            unfreezeTxn,
                            txnSize -> expectedTokenUnfreezeFullFeeUsd(
                                    Map.of(SIGNATURES, 41L, PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(unfreezeTxn, PAYER));
        }
    }

    @Nested
    @DisplayName("TokenFreeze Simple Fees Negative Test Cases")
    class TokenFreezeNegativeTestCases {

        @Nested
        @DisplayName("TokenFreeze Failures on Ingest and Handle")
        class TokenFreezeFailuresOnIngest {

            @HapiTest
            @DisplayName("TokenFreeze - insufficient tx fee fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenFreezeInsufficientTxFeeFailsOnIngest() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(FREEZE_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .freezeKey(FREEZE_KEY)
                                .freezeDefault(false)
                                .treasury(TREASURY)
                                .payingWith(PAYER),
                        tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT),
                        tokenFreeze(TOKEN, ACCOUNT)
                                .payingWith(PAYER)
                                .signedBy(PAYER, FREEZE_KEY)
                                .fee(1L) // Fee too low
                                .via(freezeTxn)
                                .hasPrecheck(INSUFFICIENT_TX_FEE),
                        getTxnRecord(freezeTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TokenFreeze - insufficient payer balance fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenFreezeInsufficientPayerBalanceFailsOnIngest() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS / 100_000L),
                        cryptoCreate(TREASURY).balance(0L),
                        cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(FREEZE_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .freezeKey(FREEZE_KEY)
                                .freezeDefault(false)
                                .treasury(TREASURY),
                        tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT),
                        tokenFreeze(TOKEN, ACCOUNT)
                                .payingWith(PAYER)
                                .signedBy(PAYER, FREEZE_KEY)
                                .via(freezeTxn)
                                .hasPrecheck(INSUFFICIENT_PAYER_BALANCE),
                        getTxnRecord(freezeTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TokenFreeze - threshold payer key with invalid signature fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenFreezeThresholdInvalidSigFailsOnIngest() {
                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
                SigControl invalidSig = keyShape.signedWith(sigs(ON, OFF));

                return hapiTest(
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(FREEZE_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .freezeKey(FREEZE_KEY)
                                .freezeDefault(false)
                                .treasury(TREASURY),
                        tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT),
                        tokenFreeze(TOKEN, ACCOUNT)
                                .payingWith(PAYER)
                                .sigControl(forKey(PAYER_KEY, invalidSig))
                                .signedBy(PAYER, FREEZE_KEY)
                                .via(freezeTxn)
                                .hasPrecheck(INVALID_SIGNATURE),
                        getTxnRecord(freezeTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TokenFreeze - memo too long fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenFreezeMemoTooLongFailsOnIngest() {
                final var LONG_MEMO = "x".repeat(1025);
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(FREEZE_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .freezeKey(FREEZE_KEY)
                                .freezeDefault(false)
                                .treasury(TREASURY),
                        tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT),
                        tokenFreeze(TOKEN, ACCOUNT)
                                .payingWith(PAYER)
                                .signedBy(PAYER, FREEZE_KEY)
                                .memo(LONG_MEMO)
                                .via(freezeTxn)
                                .hasPrecheck(MEMO_TOO_LONG),
                        getTxnRecord(freezeTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TokenFreeze - expired transaction fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenFreezeExpiredTransactionFailsOnIngest() {
                final var expiredTxnId = "expiredTxn";
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(FREEZE_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .freezeKey(FREEZE_KEY)
                                .freezeDefault(false)
                                .treasury(TREASURY),
                        usableTxnIdNamed(expiredTxnId).modifyValidStart(-3_600L).payerId(PAYER),
                        tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT),
                        tokenFreeze(TOKEN, ACCOUNT)
                                .payingWith(PAYER)
                                .signedBy(PAYER, FREEZE_KEY)
                                .txnId(expiredTxnId)
                                .via(freezeTxn)
                                .hasPrecheck(TRANSACTION_EXPIRED),
                        getTxnRecord(freezeTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TokenFreeze - too far start time fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenFreezeTooFarStartTimeFailsOnIngest() {
                final var futureTxnId = "futureTxn";
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(FREEZE_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .freezeKey(FREEZE_KEY)
                                .freezeDefault(false)
                                .treasury(TREASURY),
                        usableTxnIdNamed(futureTxnId).modifyValidStart(3_600L).payerId(PAYER),
                        tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT),
                        tokenFreeze(TOKEN, ACCOUNT)
                                .payingWith(PAYER)
                                .signedBy(PAYER, FREEZE_KEY)
                                .txnId(futureTxnId)
                                .via(freezeTxn)
                                .hasPrecheck(INVALID_TRANSACTION_START),
                        getTxnRecord(freezeTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TokenFreeze - invalid transaction duration fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenFreezeInvalidTransactionDurationFailsOnIngest() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(FREEZE_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .freezeKey(FREEZE_KEY)
                                .freezeDefault(false)
                                .treasury(TREASURY),
                        tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT),
                        tokenFreeze(TOKEN, ACCOUNT)
                                .payingWith(PAYER)
                                .signedBy(PAYER, FREEZE_KEY)
                                .validDurationSecs(0)
                                .via(freezeTxn)
                                .hasPrecheck(INVALID_TRANSACTION_DURATION),
                        getTxnRecord(freezeTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TokenFreeze - duplicate transaction fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenFreezeDuplicateTransactionFailsOnIngest() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(FREEZE_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .freezeKey(FREEZE_KEY)
                                .freezeDefault(false)
                                .treasury(TREASURY),
                        tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT),
                        tokenFreeze(TOKEN, ACCOUNT)
                                .payingWith(PAYER)
                                .signedBy(PAYER, FREEZE_KEY)
                                .via("freezeTxn"),
                        tokenFreeze(TOKEN, ACCOUNT)
                                .payingWith(PAYER)
                                .signedBy(PAYER, FREEZE_KEY)
                                .txnId("freezeTxn")
                                .via(freezeTxn)
                                .hasPrecheck(DUPLICATE_TRANSACTION));
            }

            @HapiTest
            @DisplayName("TokenFreeze oversize transaction fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenFreezeOversizeTxnFailsOnIngest() {
                KeyShape keyShape = threshOf(
                        1, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE,
                        SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE,
                        SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE,
                        SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE,
                        SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE,
                        SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE,
                        SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE);
                SigControl allSigned = keyShape.signedWith(sigs(
                        ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON,
                        ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON,
                        ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON,
                        ON, ON, ON, ON, ON, ON, ON, ON));

                return hapiTest(
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(FREEZE_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .freezeKey(FREEZE_KEY)
                                .freezeDefault(false)
                                .treasury(TREASURY),
                        tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT),
                        tokenFreeze(TOKEN, ACCOUNT)
                                .payingWith(PAYER)
                                .sigControl(forKey(PAYER_KEY, allSigned))
                                .signedBy(PAYER, FREEZE_KEY)
                                .via(freezeTxn)
                                .hasPrecheck(TRANSACTION_OVERSIZE),
                        getTxnRecord(freezeTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TokenFreeze - no freeze key - fails on handle - full fees charged")
            final Stream<DynamicTest> tokenFreezeNoFreezeKeyFailsOnHandleFullFeesCharged() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                // No freeze key
                                .treasury(TREASURY)
                                .payingWith(PAYER),
                        tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT),
                        tokenFreeze(TOKEN, ACCOUNT)
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .via(freezeTxn)
                                .hasKnownStatus(TOKEN_HAS_NO_FREEZE_KEY),
                        validateChargedUsdWithinWithTxnSize(
                                freezeTxn,
                                txnSize -> expectedTokenFreezeFullFeeUsd(
                                        Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateChargedAccount(freezeTxn, PAYER));
            }

            @HapiTest
            @DisplayName("TokenFreeze - token not associated fails on hande - full fees charged")
            final Stream<DynamicTest> tokenFreezeNotAssociatedFailsOnHandleFullFeesCharged() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(FREEZE_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .freezeKey(FREEZE_KEY)
                                .freezeDefault(false)
                                .treasury(TREASURY)
                                .payingWith(PAYER),
                        // Not associating the token
                        tokenFreeze(TOKEN, ACCOUNT)
                                .payingWith(PAYER)
                                .signedBy(PAYER, FREEZE_KEY)
                                .via(freezeTxn)
                                .hasKnownStatus(TOKEN_NOT_ASSOCIATED_TO_ACCOUNT),
                        validateChargedUsdWithinWithTxnSize(
                                freezeTxn,
                                txnSize -> expectedTokenFreezeFullFeeUsd(
                                        Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateChargedAccount(freezeTxn, PAYER));
            }

            @HapiTest
            @DisplayName("TokenFreeze - already frozen fails on handle - full fees charged")
            final Stream<DynamicTest> tokenFreezeAlreadyFrozenFailsOnHandleFullFeesCharged() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(FREEZE_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .freezeKey(FREEZE_KEY)
                                .freezeDefault(true) // Already frozen
                                .treasury(TREASURY)
                                .payingWith(PAYER),
                        tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT),
                        tokenFreeze(TOKEN, ACCOUNT)
                                .payingWith(PAYER)
                                .signedBy(PAYER, FREEZE_KEY)
                                .via(freezeTxn)
                                .hasKnownStatus(ACCOUNT_FROZEN_FOR_TOKEN),
                        validateChargedUsdWithinWithTxnSize(
                                freezeTxn,
                                txnSize -> expectedTokenFreezeFullFeeUsd(
                                        Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateChargedAccount(freezeTxn, PAYER));
            }

            @HapiTest
            @DisplayName("TokenFreeze - missing freeze key signature fails on handle - full fees charged")
            final Stream<DynamicTest> tokenFreezeMissingFreezeKeySignatureFailsOnHandleFullFeesCharged() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(FREEZE_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .freezeKey(FREEZE_KEY)
                                .freezeDefault(false)
                                .treasury(TREASURY)
                                .payingWith(PAYER),
                        tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT),
                        tokenFreeze(TOKEN, ACCOUNT)
                                .payingWith(PAYER)
                                .signedBy(PAYER) // Missing freeze key signature
                                .via(freezeTxn)
                                .hasKnownStatus(INVALID_SIGNATURE),
                        validateChargedUsdWithinWithTxnSize(
                                freezeTxn,
                                txnSize -> expectedTokenFreezeFullFeeUsd(
                                        Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateChargedAccount(freezeTxn, PAYER));
            }

            @LeakyHapiTest
            @DisplayName("TokenFreeze - duplicate transaction fails on handle - payer charged for first only")
            final Stream<DynamicTest> tokenFreezeDuplicateFailsOnHandle() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(FREEZE_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .freezeKey(FREEZE_KEY)
                                .freezeDefault(false)
                                .treasury(TREASURY)
                                .payingWith(PAYER),
                        tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT),
                        cryptoTransfer(movingHbar(ONE_HBAR).between(DEFAULT_PAYER, "3")),
                        usableTxnIdNamed(DUPLICATE_TXN_ID).payerId(PAYER),
                        tokenFreeze(TOKEN, ACCOUNT)
                                .payingWith(PAYER)
                                .signedBy(PAYER, FREEZE_KEY)
                                .setNode(4)
                                .txnId(DUPLICATE_TXN_ID)
                                .via(freezeTxn),
                        tokenFreeze(TOKEN, ACCOUNT)
                                .payingWith(PAYER)
                                .signedBy(PAYER, FREEZE_KEY)
                                .txnId(DUPLICATE_TXN_ID)
                                .setNode(3)
                                .hasPrecheck(DUPLICATE_TRANSACTION),
                        validateChargedUsdWithinWithTxnSize(
                                freezeTxn,
                                txnSize -> expectedTokenFreezeFullFeeUsd(
                                        Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateChargedAccount(freezeTxn, PAYER));
            }
        }

        @Nested
        @DisplayName("TokenFreeze Failures on Pre-Handle")
        class TokenFreezeFailuresOnPreHandle {

            @LeakyEmbeddedHapiTest(reason = MUST_SKIP_INGEST)
            @DisplayName("TokenFreeze - invalid payer signature fails on pre-handle - network fee only")
            final Stream<DynamicTest> tokenFreezeInvalidPayerSigFailsOnPreHandle() {
                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
                SigControl invalidSig = keyShape.signedWith(sigs(ON, OFF));

                return hapiTest(
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(FREEZE_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .freezeKey(FREEZE_KEY)
                                .freezeDefault(false)
                                .treasury(TREASURY),
                        tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT),
                        cryptoTransfer(movingHbar(ONE_HBAR).between(DEFAULT_PAYER, "4")),
                        tokenFreeze(TOKEN, ACCOUNT)
                                .payingWith(PAYER)
                                .sigControl(forKey(PAYER_KEY, invalidSig))
                                .signedBy(PAYER, FREEZE_KEY)
                                .setNode("4")
                                .via(freezeTxn)
                                .hasKnownStatus(INVALID_PAYER_SIGNATURE),
                        validateChargedUsdWithinWithTxnSize(
                                freezeTxn,
                                txnSize -> expectedTokenFreezeNetworkFeeOnlyUsd(
                                        Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateChargedAccount(freezeTxn, "4"));
            }
        }
    }

    @Nested
    @DisplayName("TokenUnfreeze Simple Fees Negative Test Cases")
    class TokenUnfreezeNegativeTestCases {

        @Nested
        @DisplayName("TokenUnfreeze Failures on Ingest and Handle")
        class TokenUnfreezeFailuresOnIngest {

            @HapiTest
            @DisplayName("TokenUnfreeze - insufficient tx fee fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenUnfreezeInsufficientTxFeeFailsOnIngest() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(FREEZE_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .freezeKey(FREEZE_KEY)
                                .freezeDefault(true)
                                .treasury(TREASURY)
                                .payingWith(PAYER),
                        tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT),
                        tokenUnfreeze(TOKEN, ACCOUNT)
                                .payingWith(PAYER)
                                .signedBy(PAYER, FREEZE_KEY)
                                .fee(1L) // Fee too low
                                .via(unfreezeTxn)
                                .hasPrecheck(INSUFFICIENT_TX_FEE),
                        getTxnRecord(unfreezeTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TokenUnfreeze - insufficient payer balance fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenUnfreezeInsufficientPayerBalanceFailsOnIngest() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS / 100_000L),
                        cryptoCreate(TREASURY).balance(0L),
                        cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(FREEZE_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .freezeKey(FREEZE_KEY)
                                .freezeDefault(true)
                                .treasury(TREASURY),
                        tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT),
                        tokenUnfreeze(TOKEN, ACCOUNT)
                                .payingWith(PAYER)
                                .signedBy(PAYER, FREEZE_KEY)
                                .via(unfreezeTxn)
                                .hasPrecheck(INSUFFICIENT_PAYER_BALANCE),
                        getTxnRecord(unfreezeTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TokenUnfreeze - threshold payer key with invalid signature fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenUnfreezeThresholdInvalidSigFailsOnIngest() {
                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
                SigControl invalidSig = keyShape.signedWith(sigs(ON, OFF));

                return hapiTest(
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(FREEZE_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .freezeKey(FREEZE_KEY)
                                .freezeDefault(true)
                                .treasury(TREASURY),
                        tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT),
                        tokenUnfreeze(TOKEN, ACCOUNT)
                                .payingWith(PAYER)
                                .sigControl(forKey(PAYER_KEY, invalidSig))
                                .signedBy(PAYER, FREEZE_KEY)
                                .via(unfreezeTxn)
                                .hasPrecheck(INVALID_SIGNATURE),
                        getTxnRecord(unfreezeTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TokenUnfreeze - memo too long fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenUnfreezeMemoTooLongFailsOnIngest() {
                final var LONG_MEMO = "x".repeat(1025);
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(FREEZE_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .freezeKey(FREEZE_KEY)
                                .freezeDefault(true)
                                .treasury(TREASURY),
                        tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT),
                        tokenUnfreeze(TOKEN, ACCOUNT)
                                .payingWith(PAYER)
                                .signedBy(PAYER, FREEZE_KEY)
                                .memo(LONG_MEMO)
                                .via(unfreezeTxn)
                                .hasPrecheck(MEMO_TOO_LONG),
                        getTxnRecord(unfreezeTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TokenUnfreeze - expired transaction fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenUnfreezeExpiredTransactionFailsOnIngest() {
                final var expiredTxnId = "expiredTxn";
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(FREEZE_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .freezeKey(FREEZE_KEY)
                                .freezeDefault(true)
                                .treasury(TREASURY),
                        usableTxnIdNamed(expiredTxnId).modifyValidStart(-3_600L).payerId(PAYER),
                        tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT),
                        tokenUnfreeze(TOKEN, ACCOUNT)
                                .payingWith(PAYER)
                                .signedBy(PAYER, FREEZE_KEY)
                                .txnId(expiredTxnId)
                                .via(unfreezeTxn)
                                .hasPrecheck(TRANSACTION_EXPIRED),
                        getTxnRecord(unfreezeTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TokenUnfreeze - too far start time fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenUnfreezeTooFarStartTimeFailsOnIngest() {
                final var futureTxnId = "futureTxn";
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(FREEZE_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .freezeKey(FREEZE_KEY)
                                .freezeDefault(true)
                                .treasury(TREASURY),
                        usableTxnIdNamed(futureTxnId).modifyValidStart(3_600L).payerId(PAYER),
                        tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT),
                        tokenUnfreeze(TOKEN, ACCOUNT)
                                .payingWith(PAYER)
                                .signedBy(PAYER, FREEZE_KEY)
                                .txnId(futureTxnId)
                                .via(unfreezeTxn)
                                .hasPrecheck(INVALID_TRANSACTION_START),
                        getTxnRecord(unfreezeTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TokenUnfreeze - invalid transaction duration fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenUnfreezeInvalidTransactionDurationFailsOnIngest() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(FREEZE_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .freezeKey(FREEZE_KEY)
                                .freezeDefault(true)
                                .treasury(TREASURY),
                        tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT),
                        tokenUnfreeze(TOKEN, ACCOUNT)
                                .payingWith(PAYER)
                                .signedBy(PAYER, FREEZE_KEY)
                                .validDurationSecs(0)
                                .via(unfreezeTxn)
                                .hasPrecheck(INVALID_TRANSACTION_DURATION),
                        getTxnRecord(unfreezeTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TokenUnfreeze - duplicate transaction fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenUnfreezeDuplicateTransactionFailsOnIngest() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(FREEZE_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .freezeKey(FREEZE_KEY)
                                .freezeDefault(true)
                                .treasury(TREASURY),
                        tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT),
                        tokenUnfreeze(TOKEN, ACCOUNT)
                                .payingWith(PAYER)
                                .signedBy(PAYER, FREEZE_KEY)
                                .via("unfreezeTxn"),
                        tokenUnfreeze(TOKEN, ACCOUNT)
                                .payingWith(PAYER)
                                .signedBy(PAYER, FREEZE_KEY)
                                .txnId("unfreezeTxn")
                                .via(unfreezeTxn)
                                .hasPrecheck(DUPLICATE_TRANSACTION));
            }

            @HapiTest
            @DisplayName("TokenUnfreeze oversize transaction fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenUnfreezeOversizeTxnFailsOnIngest() {
                KeyShape keyShape = threshOf(
                        1, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE,
                        SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE,
                        SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE,
                        SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE,
                        SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE,
                        SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE,
                        SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE);
                SigControl allSigned = keyShape.signedWith(sigs(
                        ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON,
                        ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON,
                        ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON,
                        ON, ON, ON, ON, ON, ON, ON, ON));

                return hapiTest(
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(FREEZE_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .freezeKey(FREEZE_KEY)
                                .freezeDefault(true)
                                .treasury(TREASURY),
                        tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT),
                        tokenUnfreeze(TOKEN, ACCOUNT)
                                .payingWith(PAYER)
                                .sigControl(forKey(PAYER_KEY, allSigned))
                                .signedBy(PAYER, FREEZE_KEY)
                                .via(unfreezeTxn)
                                .hasPrecheck(TRANSACTION_OVERSIZE),
                        getTxnRecord(unfreezeTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TokenUnfreeze - missing freeze key signature fails at handle - full fees charged")
            final Stream<DynamicTest> tokenUnfreezeMissingFreezeKeySignatureFailsAtHandle() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(FREEZE_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .freezeKey(FREEZE_KEY)
                                .freezeDefault(true) // Start frozen
                                .treasury(TREASURY)
                                .payingWith(PAYER),
                        tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT),
                        tokenUnfreeze(TOKEN, ACCOUNT)
                                .payingWith(PAYER)
                                .signedBy(PAYER) // Missing freeze key signature
                                .via(unfreezeTxn)
                                .hasKnownStatus(INVALID_SIGNATURE),
                        validateChargedUsdWithinWithTxnSize(
                                unfreezeTxn,
                                txnSize -> expectedTokenUnfreezeFullFeeUsd(
                                        Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateChargedAccount(unfreezeTxn, PAYER));
            }

            @HapiTest
            @DisplayName("TokenUnfreeze - no freeze key fails on handle - full fees charged")
            final Stream<DynamicTest> tokenUnfreezeNoFreezeKeyFailsOnHandle() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                // No freeze key
                                .treasury(TREASURY)
                                .payingWith(PAYER),
                        tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT),
                        tokenUnfreeze(TOKEN, ACCOUNT)
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .via(unfreezeTxn)
                                .hasKnownStatus(TOKEN_HAS_NO_FREEZE_KEY),
                        validateChargedUsdWithinWithTxnSize(
                                unfreezeTxn,
                                txnSize -> expectedTokenUnfreezeFullFeeUsd(
                                        Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateChargedAccount(unfreezeTxn, PAYER));
            }

            @HapiTest
            @DisplayName("TokenUnfreeze - token not associated fails on handle - full fees charged")
            final Stream<DynamicTest> tokenUnfreezeNotAssociatedFailsOnHandle() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(FREEZE_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .freezeKey(FREEZE_KEY)
                                .freezeDefault(true)
                                .treasury(TREASURY)
                                .payingWith(PAYER),
                        // Not associating the token
                        tokenUnfreeze(TOKEN, ACCOUNT)
                                .payingWith(PAYER)
                                .signedBy(PAYER, FREEZE_KEY)
                                .via(unfreezeTxn)
                                .hasKnownStatus(TOKEN_NOT_ASSOCIATED_TO_ACCOUNT),
                        validateChargedUsdWithinWithTxnSize(
                                unfreezeTxn,
                                txnSize -> expectedTokenUnfreezeFullFeeUsd(
                                        Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateChargedAccount(unfreezeTxn, PAYER));
            }

            @LeakyHapiTest
            @DisplayName("TokenUnfreeze - duplicate transaction fails on handle - payer charged for first only")
            final Stream<DynamicTest> tokenUnfreezeDuplicateFailsOnHandle() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(FREEZE_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .freezeKey(FREEZE_KEY)
                                .freezeDefault(true)
                                .treasury(TREASURY)
                                .payingWith(PAYER),
                        tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT),
                        cryptoTransfer(movingHbar(ONE_HBAR).between(DEFAULT_PAYER, "3")),
                        usableTxnIdNamed(DUPLICATE_TXN_ID).payerId(PAYER),
                        tokenUnfreeze(TOKEN, ACCOUNT)
                                .payingWith(PAYER)
                                .signedBy(PAYER, FREEZE_KEY)
                                .setNode(4)
                                .txnId(DUPLICATE_TXN_ID)
                                .via(unfreezeTxn),
                        tokenUnfreeze(TOKEN, ACCOUNT)
                                .payingWith(PAYER)
                                .signedBy(PAYER, FREEZE_KEY)
                                .txnId(DUPLICATE_TXN_ID)
                                .setNode(3)
                                .hasPrecheck(DUPLICATE_TRANSACTION),
                        validateChargedUsdWithinWithTxnSize(
                                unfreezeTxn,
                                txnSize -> expectedTokenFreezeFullFeeUsd(
                                        Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateChargedAccount(unfreezeTxn, PAYER));
            }
        }

        @Nested
        @DisplayName("TokenUnfreeze Failures on Pre-Handle")
        class TokenUnfreezeFailuresOnPreHandle {

            @LeakyEmbeddedHapiTest(reason = MUST_SKIP_INGEST)
            @DisplayName("TokenUnfreeze - invalid payer signature fails on pre-handle - network fee only")
            final Stream<DynamicTest> tokenUnfreezeInvalidPayerSigFailsOnPreHandle() {
                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
                SigControl invalidSig = keyShape.signedWith(sigs(ON, OFF));

                return hapiTest(
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(FREEZE_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .freezeKey(FREEZE_KEY)
                                .freezeDefault(true)
                                .treasury(TREASURY),
                        tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT),
                        cryptoTransfer(movingHbar(ONE_HBAR).between(DEFAULT_PAYER, "4")),
                        tokenUnfreeze(TOKEN, ACCOUNT)
                                .payingWith(PAYER)
                                .sigControl(forKey(PAYER_KEY, invalidSig))
                                .signedBy(PAYER, FREEZE_KEY)
                                .setNode("4")
                                .via(unfreezeTxn)
                                .hasKnownStatus(INVALID_PAYER_SIGNATURE),
                        validateChargedUsdWithinWithTxnSize(
                                unfreezeTxn,
                                txnSize -> expectedTokenUnfreezeNetworkFeeOnlyUsd(
                                        Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateChargedAccount(unfreezeTxn, "4"));
            }
        }
    }
}
