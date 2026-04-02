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
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.grantTokenKyc;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.revokeTokenKyc;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingHbar;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.usableTxnIdNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedAccount;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTokenGrantKycFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTokenGrantKycNetworkFeeOnlyUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTokenRevokeKycFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.validateChargedUsdFromRecordWithTxnSize;
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
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_HAS_NO_KYC_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_NOT_ASSOCIATED_TO_ACCOUNT;
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
 * Tests for TokenGrantKyc and TokenRevokeKyc simple fees with extras.
 * Validates that fees are correctly calculated based on:
 * - Number of signatures (extras beyond included)
 */
@Tag(SIMPLE_FEES)
@HapiTestLifecycle
public class TokenKycSimpleFeesTest {

    private static final String PAYER = "payer";
    private static final String TREASURY = "treasury";
    private static final String ACCOUNT = "account";
    private static final String KYC_KEY = "kycKey";
    private static final String PAYER_KEY = "payerKey";
    private static final String TOKEN = "fungibleToken";
    private static final String grantKycTxn = "grantKycTxn";
    private static final String revokeKycTxn = "revokeKycTxn";
    private static final String DUPLICATE_TXN_ID = "duplicateGrantKycTxnId";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(Map.of("fees.simpleFeesEnabled", "true"));
    }

    @Nested
    @DisplayName("TokenGrantKyc Simple Fees Positive Test Cases")
    class TokenGrantKycPositiveTestCases {

        @HapiTest
        @DisplayName("TokenGrantKyc - base fees")
        final Stream<DynamicTest> tokenGrantKycBaseFee() {
            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(TREASURY).balance(0L),
                    cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                    newKeyNamed(KYC_KEY),
                    tokenCreate(TOKEN)
                            .tokenType(FUNGIBLE_COMMON)
                            .kycKey(KYC_KEY)
                            .treasury(TREASURY)
                            .payingWith(PAYER),
                    tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT),
                    grantTokenKyc(TOKEN, ACCOUNT)
                            .payingWith(PAYER)
                            .signedBy(PAYER, KYC_KEY)
                            .via(grantKycTxn),
                    validateChargedUsdWithinWithTxnSize(
                            grantKycTxn,
                            txnSize -> expectedTokenGrantKycFullFeeUsd(
                                    Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(grantKycTxn, PAYER));
        }

        @HapiTest
        @DisplayName("TokenGrantKyc with threshold key - extra signatures")
        final Stream<DynamicTest> tokenGrantKycWithThresholdKey() {
            KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
            SigControl validSig = keyShape.signedWith(sigs(ON, ON));

            return hapiTest(
                    newKeyNamed(PAYER_KEY).shape(keyShape),
                    cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(TREASURY).balance(0L),
                    cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                    newKeyNamed(KYC_KEY),
                    tokenCreate(TOKEN)
                            .tokenType(FUNGIBLE_COMMON)
                            .kycKey(KYC_KEY)
                            .treasury(TREASURY)
                            .payingWith(PAYER)
                            .sigControl(forKey(PAYER_KEY, validSig)),
                    tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT),
                    grantTokenKyc(TOKEN, ACCOUNT)
                            .payingWith(PAYER)
                            .sigControl(forKey(PAYER_KEY, validSig))
                            .signedBy(PAYER, KYC_KEY)
                            .via(grantKycTxn),
                    validateChargedUsdWithinWithTxnSize(
                            grantKycTxn,
                            txnSize -> expectedTokenGrantKycFullFeeUsd(
                                    Map.of(SIGNATURES, 3L, PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(grantKycTxn, PAYER));
        }

        @HapiTest
        @DisplayName("TokenGrantKyc with threshold kyc key - extra signatures")
        final Stream<DynamicTest> tokenGrantKycWithThresholdKycKey() {
            KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
            SigControl validSig = keyShape.signedWith(sigs(ON, ON));

            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(TREASURY).balance(0L),
                    cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                    newKeyNamed(KYC_KEY).shape(keyShape),
                    tokenCreate(TOKEN)
                            .tokenType(FUNGIBLE_COMMON)
                            .kycKey(KYC_KEY)
                            .treasury(TREASURY)
                            .payingWith(PAYER)
                            .signedBy(PAYER, TREASURY, KYC_KEY)
                            .sigControl(forKey(KYC_KEY, validSig)),
                    tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT),
                    grantTokenKyc(TOKEN, ACCOUNT)
                            .payingWith(PAYER)
                            .signedBy(PAYER, KYC_KEY)
                            .sigControl(forKey(KYC_KEY, validSig))
                            .via(grantKycTxn),
                    validateChargedUsdWithinWithTxnSize(
                            grantKycTxn,
                            txnSize -> expectedTokenGrantKycFullFeeUsd(
                                    Map.of(SIGNATURES, 3L, PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(grantKycTxn, PAYER));
        }

        @HapiTest
        @DisplayName("TokenGrantKyc with large payer key - extra processing bytes fee")
        final Stream<DynamicTest> tokenGrantKycLargeKeyExtraProcessingBytesFee() {
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
                    newKeyNamed(KYC_KEY),
                    tokenCreate(TOKEN)
                            .tokenType(FUNGIBLE_COMMON)
                            .kycKey(KYC_KEY)
                            .treasury(TREASURY)
                            .payingWith(PAYER)
                            .sigControl(forKey(PAYER_KEY, allSigned)),
                    tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT),
                    grantTokenKyc(TOKEN, ACCOUNT)
                            .payingWith(PAYER)
                            .sigControl(forKey(PAYER_KEY, allSigned))
                            .signedBy(PAYER, KYC_KEY)
                            .via(grantKycTxn),
                    validateChargedUsdWithinWithTxnSize(
                            grantKycTxn,
                            txnSize -> expectedTokenGrantKycFullFeeUsd(
                                    Map.of(SIGNATURES, 21L, PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(grantKycTxn, PAYER));
        }

        @HapiTest
        @DisplayName("TokenGrantKyc with very large payer key below oversize - extra processing bytes fee")
        final Stream<DynamicTest> tokenGrantKycVeryLargeKeyBelowOversizeFee() {
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
                    newKeyNamed(KYC_KEY),
                    tokenCreate(TOKEN)
                            .tokenType(FUNGIBLE_COMMON)
                            .kycKey(KYC_KEY)
                            .treasury(TREASURY)
                            .payingWith(PAYER)
                            .sigControl(forKey(PAYER_KEY, allSigned)),
                    tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT),
                    grantTokenKyc(TOKEN, ACCOUNT)
                            .payingWith(PAYER)
                            .sigControl(forKey(PAYER_KEY, allSigned))
                            .signedBy(PAYER, KYC_KEY)
                            .via(grantKycTxn),
                    validateChargedUsdWithinWithTxnSize(
                            grantKycTxn,
                            txnSize -> expectedTokenGrantKycFullFeeUsd(
                                    Map.of(SIGNATURES, 41L, PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(grantKycTxn, PAYER));
        }
    }

    @Nested
    @DisplayName("TokenRevokeKyc Simple Fees Positive Test Cases")
    class TokenRevokeKycPositiveTestCases {

        @HapiTest
        @DisplayName("TokenRevokeKyc - base fees")
        final Stream<DynamicTest> tokenRevokeKycBaseFee() {
            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(TREASURY).balance(0L),
                    cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                    newKeyNamed(KYC_KEY),
                    tokenCreate(TOKEN)
                            .tokenType(FUNGIBLE_COMMON)
                            .kycKey(KYC_KEY)
                            .treasury(TREASURY)
                            .payingWith(PAYER),
                    tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT),
                    grantTokenKyc(TOKEN, ACCOUNT).payingWith(PAYER).signedBy(PAYER, KYC_KEY),
                    revokeTokenKyc(TOKEN, ACCOUNT)
                            .payingWith(PAYER)
                            .signedBy(PAYER, KYC_KEY)
                            .via(revokeKycTxn),
                    validateChargedUsdWithinWithTxnSize(
                            revokeKycTxn,
                            txnSize -> expectedTokenRevokeKycFullFeeUsd(
                                    Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                            0.1));
        }

        @HapiTest
        @DisplayName("TokenRevokeKyc with threshold key - extra signatures")
        final Stream<DynamicTest> tokenRevokeKycWithThresholdKey() {
            KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
            SigControl validSig = keyShape.signedWith(sigs(ON, ON));

            return hapiTest(
                    newKeyNamed(PAYER_KEY).shape(keyShape),
                    cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(TREASURY).balance(0L),
                    cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                    newKeyNamed(KYC_KEY),
                    tokenCreate(TOKEN)
                            .tokenType(FUNGIBLE_COMMON)
                            .kycKey(KYC_KEY)
                            .treasury(TREASURY)
                            .payingWith(PAYER)
                            .sigControl(forKey(PAYER_KEY, validSig)),
                    tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT),
                    grantTokenKyc(TOKEN, ACCOUNT)
                            .payingWith(PAYER)
                            .sigControl(forKey(PAYER_KEY, validSig))
                            .signedBy(PAYER, KYC_KEY),
                    revokeTokenKyc(TOKEN, ACCOUNT)
                            .payingWith(PAYER)
                            .sigControl(forKey(PAYER_KEY, validSig))
                            .signedBy(PAYER, KYC_KEY)
                            .via(revokeKycTxn),
                    validateChargedUsdWithinWithTxnSize(
                            revokeKycTxn,
                            txnSize -> expectedTokenRevokeKycFullFeeUsd(
                                    Map.of(SIGNATURES, 3L, PROCESSING_BYTES, (long) txnSize)),
                            0.1));
        }

        @HapiTest
        @DisplayName("TokenRevokeKyc with large payer key - extra processing bytes fee")
        final Stream<DynamicTest> tokenRevokeKycLargeKeyExtraProcessingBytesFee() {
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
                    newKeyNamed(KYC_KEY),
                    tokenCreate(TOKEN)
                            .tokenType(FUNGIBLE_COMMON)
                            .kycKey(KYC_KEY)
                            .treasury(TREASURY)
                            .payingWith(PAYER)
                            .sigControl(forKey(PAYER_KEY, allSigned)),
                    tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT),
                    grantTokenKyc(TOKEN, ACCOUNT)
                            .payingWith(PAYER)
                            .sigControl(forKey(PAYER_KEY, allSigned))
                            .signedBy(PAYER, KYC_KEY)
                            .via(grantKycTxn),
                    revokeTokenKyc(TOKEN, ACCOUNT)
                            .payingWith(PAYER)
                            .sigControl(forKey(PAYER_KEY, allSigned))
                            .signedBy(PAYER, KYC_KEY)
                            .via(revokeKycTxn),
                    validateChargedUsdWithinWithTxnSize(
                            revokeKycTxn,
                            txnSize -> expectedTokenGrantKycFullFeeUsd(
                                    Map.of(SIGNATURES, 21L, PROCESSING_BYTES, (long) txnSize)),
                            0.1));
        }

        @HapiTest
        @DisplayName("TokenRevokeKyc with very large payer key below oversize - extra processing bytes fee")
        final Stream<DynamicTest> tokenRevokeKycVeryLargeKeyBelowOversizeFee() {
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
                    newKeyNamed(KYC_KEY),
                    tokenCreate(TOKEN)
                            .tokenType(FUNGIBLE_COMMON)
                            .kycKey(KYC_KEY)
                            .treasury(TREASURY)
                            .payingWith(PAYER)
                            .sigControl(forKey(PAYER_KEY, allSigned)),
                    tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT),
                    grantTokenKyc(TOKEN, ACCOUNT)
                            .payingWith(PAYER)
                            .sigControl(forKey(PAYER_KEY, allSigned))
                            .signedBy(PAYER, KYC_KEY)
                            .via(grantKycTxn),
                    revokeTokenKyc(TOKEN, ACCOUNT)
                            .payingWith(PAYER)
                            .sigControl(forKey(PAYER_KEY, allSigned))
                            .signedBy(PAYER, KYC_KEY)
                            .via(revokeKycTxn),
                    validateChargedUsdWithinWithTxnSize(
                            revokeKycTxn,
                            txnSize -> expectedTokenGrantKycFullFeeUsd(
                                    Map.of(SIGNATURES, 21L, PROCESSING_BYTES, (long) txnSize)),
                            0.1));
        }
    }

    @Nested
    @DisplayName("TokenKyc Simple Fees Negative Test Cases")
    class TokenKycNegativeTestCases {

        @Nested
        @DisplayName("TokenGrantKyc Failures on Ingest and Handle")
        class TokenGrantKycFailuresOnIngestAndHandle {

            @HapiTest
            @DisplayName("TokenGrantKyc - insufficient tx fee fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenGrantKycInsufficientTxFeeFailsOnIngest() {

                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(KYC_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .kycKey(KYC_KEY)
                                .treasury(TREASURY)
                                .payingWith(PAYER),
                        tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT),
                        grantTokenKyc(TOKEN, ACCOUNT)
                                .payingWith(PAYER)
                                .signedBy(PAYER, KYC_KEY)
                                .fee(1L) // Fee too low
                                .via(grantKycTxn)
                                .hasPrecheck(INSUFFICIENT_TX_FEE),
                        getTxnRecord(grantKycTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TokenGrantKyc - threshold payer key with invalid signature fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenGrantKycThresholdKeyInvalidSigFailsOnIngest() {
                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
                SigControl validSig = keyShape.signedWith(sigs(ON, ON));
                SigControl invalidSig = keyShape.signedWith(sigs(ON, OFF));

                return hapiTest(
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(KYC_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .kycKey(KYC_KEY)
                                .treasury(TREASURY)
                                .payingWith(PAYER)
                                .sigControl(forKey(PAYER_KEY, validSig)),
                        tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT),
                        grantTokenKyc(TOKEN, ACCOUNT)
                                .payingWith(PAYER)
                                .sigControl(forKey(PAYER_KEY, invalidSig))
                                .signedBy(PAYER, KYC_KEY)
                                .via(grantKycTxn)
                                .hasPrecheck(INVALID_SIGNATURE),
                        getTxnRecord(grantKycTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TokenGrantKyc - insufficient payer balance fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenGrantKycInsufficientPayerBalanceFailsOnIngest() {

                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS / 100_000L),
                        cryptoCreate(TREASURY).balance(0L),
                        cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(KYC_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .kycKey(KYC_KEY)
                                .treasury(TREASURY),
                        tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT),
                        grantTokenKyc(TOKEN, ACCOUNT)
                                .payingWith(PAYER)
                                .signedBy(PAYER, KYC_KEY)
                                .via(grantKycTxn)
                                .hasPrecheck(INSUFFICIENT_PAYER_BALANCE),
                        getTxnRecord(grantKycTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TokenGrantKyc - memo too long fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenGrantKycMemoTooLongFailsOnIngest() {
                final var LONG_MEMO = "x".repeat(1025);
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(KYC_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .kycKey(KYC_KEY)
                                .treasury(TREASURY),
                        tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT),
                        grantTokenKyc(TOKEN, ACCOUNT)
                                .payingWith(PAYER)
                                .signedBy(PAYER, KYC_KEY)
                                .memo(LONG_MEMO)
                                .via(grantKycTxn)
                                .hasPrecheck(MEMO_TOO_LONG),
                        getTxnRecord(grantKycTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TokenGrantKyc - expired transaction fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenGrantKycExpiredTransactionFailsOnIngest() {
                final var expiredTxnId = "expiredTxn";
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(KYC_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .kycKey(KYC_KEY)
                                .treasury(TREASURY),
                        tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT),
                        usableTxnIdNamed(expiredTxnId).modifyValidStart(-3_600L).payerId(PAYER),
                        grantTokenKyc(TOKEN, ACCOUNT)
                                .payingWith(PAYER)
                                .signedBy(PAYER, KYC_KEY)
                                .txnId(expiredTxnId)
                                .via(grantKycTxn)
                                .hasPrecheck(TRANSACTION_EXPIRED),
                        getTxnRecord(grantKycTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TokenGrantKyc - transaction with too far start time fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenGrantKycTooFarStartTimeTransactionFailsOnIngest() {
                final var futureTxnId = "futureTxn";
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(KYC_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .kycKey(KYC_KEY)
                                .treasury(TREASURY),
                        tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT),
                        usableTxnIdNamed(futureTxnId).modifyValidStart(3_600L).payerId(PAYER),
                        grantTokenKyc(TOKEN, ACCOUNT)
                                .payingWith(PAYER)
                                .signedBy(PAYER, KYC_KEY)
                                .txnId(futureTxnId)
                                .via(grantKycTxn)
                                .hasPrecheck(INVALID_TRANSACTION_START),
                        getTxnRecord(grantKycTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TokenGrantKyc - invalid transaction duration fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenGrantKycInvalidTransactionDurationFailsOnIngest() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(KYC_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .kycKey(KYC_KEY)
                                .treasury(TREASURY),
                        tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT),
                        grantTokenKyc(TOKEN, ACCOUNT)
                                .payingWith(PAYER)
                                .signedBy(PAYER, KYC_KEY)
                                .validDurationSecs(0)
                                .via(grantKycTxn)
                                .hasPrecheck(INVALID_TRANSACTION_DURATION),
                        getTxnRecord(grantKycTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TokenGrantKyc - duplicate transaction fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenGrantKycDuplicateTransactionFailsOnIngest() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(KYC_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .kycKey(KYC_KEY)
                                .treasury(TREASURY),
                        tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT),
                        grantTokenKyc(TOKEN, ACCOUNT)
                                .payingWith(PAYER)
                                .signedBy(PAYER, KYC_KEY)
                                .via("grantKycTxnFirst"),
                        grantTokenKyc(TOKEN, ACCOUNT)
                                .payingWith(PAYER)
                                .signedBy(PAYER, KYC_KEY)
                                .txnId("grantKycTxnFirst")
                                .via(grantKycTxn)
                                .hasPrecheck(DUPLICATE_TRANSACTION));
            }

            @HapiTest
            @DisplayName("TokenGrantKyc - missing kyc key signature fails at handle")
            final Stream<DynamicTest> tokenGrantKycMissingKycKeySignatureFailsAtHandle() {

                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(KYC_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .kycKey(KYC_KEY)
                                .treasury(TREASURY)
                                .payingWith(PAYER),
                        tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT),
                        grantTokenKyc(TOKEN, ACCOUNT)
                                .payingWith(PAYER)
                                .signedBy(PAYER) // Missing KYC key signature
                                .via(grantKycTxn)
                                .hasKnownStatus(INVALID_SIGNATURE),
                        validateChargedUsdFromRecordWithTxnSize(
                                grantKycTxn,
                                txnSize -> expectedTokenGrantKycFullFeeUsd(
                                        Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateChargedAccount(grantKycTxn, PAYER));
            }

            @HapiTest
            @DisplayName("TokenGrantKyc - no kyc key fails on handle - full fees charged")
            final Stream<DynamicTest> tokenGrantKycNoKycKeyFailsOnHandle() {

                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                // No KYC key
                                .treasury(TREASURY)
                                .payingWith(PAYER),
                        tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT),
                        grantTokenKyc(TOKEN, ACCOUNT)
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .via(grantKycTxn)
                                .hasKnownStatus(TOKEN_HAS_NO_KYC_KEY),
                        validateChargedUsdFromRecordWithTxnSize(
                                grantKycTxn,
                                txnSize -> expectedTokenGrantKycFullFeeUsd(
                                        Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateChargedAccount(grantKycTxn, PAYER));
            }

            @HapiTest
            @DisplayName("TokenGrantKyc - token not associated fails on handle - full fees charged")
            final Stream<DynamicTest> tokenGrantKycNotAssociatedFailsOnHandle() {

                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(KYC_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .kycKey(KYC_KEY)
                                .treasury(TREASURY)
                                .payingWith(PAYER),
                        // Not associating the token
                        grantTokenKyc(TOKEN, ACCOUNT)
                                .payingWith(PAYER)
                                .signedBy(PAYER, KYC_KEY)
                                .via(grantKycTxn)
                                .hasKnownStatus(TOKEN_NOT_ASSOCIATED_TO_ACCOUNT),
                        validateChargedUsdFromRecordWithTxnSize(
                                grantKycTxn,
                                txnSize -> expectedTokenGrantKycFullFeeUsd(
                                        Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateChargedAccount(grantKycTxn, PAYER));
            }

            @Tag(ONLY_SUBPROCESS)
            @LeakyHapiTest
            @DisplayName("TokenGrantKyc - duplicate transaction fails on handle - payer charged for first only")
            final Stream<DynamicTest> tokenGrantKycDuplicateFailsOnHandle() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(KYC_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .kycKey(KYC_KEY)
                                .treasury(TREASURY)
                                .payingWith(PAYER),
                        tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT),
                        cryptoTransfer(movingHbar(ONE_HBAR).between(DEFAULT_PAYER, "3")),
                        usableTxnIdNamed(DUPLICATE_TXN_ID).payerId(PAYER),
                        grantTokenKyc(TOKEN, ACCOUNT)
                                .payingWith(PAYER)
                                .signedBy(PAYER, KYC_KEY)
                                .setNode(4)
                                .txnId(DUPLICATE_TXN_ID)
                                .via(grantKycTxn),
                        grantTokenKyc(TOKEN, ACCOUNT)
                                .payingWith(PAYER)
                                .signedBy(PAYER, KYC_KEY)
                                .txnId(DUPLICATE_TXN_ID)
                                .setNode(3)
                                .hasPrecheck(DUPLICATE_TRANSACTION),
                        validateChargedUsdFromRecordWithTxnSize(
                                grantKycTxn,
                                txnSize -> expectedTokenGrantKycFullFeeUsd(
                                        Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateChargedAccount(grantKycTxn, PAYER));
            }
        }

        @Nested
        @DisplayName("TokenGrantKyc Failures on Pre-Handle")
        class TokenGrantKycFailuresOnPreHandle {

            @LeakyEmbeddedHapiTest(reason = MUST_SKIP_INGEST)
            @DisplayName("TokenGrantKyc - invalid payer signature fails on pre-handle - network fee only")
            final Stream<DynamicTest> tokenGrantKycInvalidPayerSigFailsOnPreHandle() {
                final String INNER_ID = "grant-kyc-txn-inner-id";

                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
                SigControl invalidSig = keyShape.signedWith(sigs(ON, OFF));

                return hapiTest(
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(KYC_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .kycKey(KYC_KEY)
                                .treasury(TREASURY),
                        tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT),
                        cryptoTransfer(movingHbar(ONE_HBAR).between(DEFAULT_PAYER, "4")),
                        grantTokenKyc(TOKEN, ACCOUNT)
                                .payingWith(PAYER)
                                .sigControl(forKey(PAYER_KEY, invalidSig))
                                .signedBy(PAYER, KYC_KEY)
                                .setNode("4")
                                .via(INNER_ID)
                                .hasKnownStatus(INVALID_PAYER_SIGNATURE),
                        getTxnRecord(INNER_ID).assertingNothingAboutHashes().logged(),
                        validateChargedUsdFromRecordWithTxnSize(
                                INNER_ID,
                                txnSize -> expectedTokenGrantKycNetworkFeeOnlyUsd(
                                        Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateChargedAccount(INNER_ID, "4"));
            }
        }
    }

    @Nested
    @DisplayName("TokenRevokeKyc Simple Fees Negative Test Cases")
    class TokenRevokeKycNegativeTestCases {

        @Nested
        @DisplayName("TokenRevokeKyc Failures on Ingest and Handle")
        class TokenRevokeKycFailuresOnIngestAndHandle {

            @HapiTest
            @DisplayName("TokenRevokeKyc - insufficient tx fee fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenRevokeKycInsufficientTxFeeFailsOnIngest() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(KYC_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .kycKey(KYC_KEY)
                                .treasury(TREASURY)
                                .payingWith(PAYER),
                        tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT),
                        grantTokenKyc(TOKEN, ACCOUNT).payingWith(PAYER).signedBy(PAYER, KYC_KEY),
                        revokeTokenKyc(TOKEN, ACCOUNT)
                                .payingWith(PAYER)
                                .signedBy(PAYER, KYC_KEY)
                                .fee(1L) // Fee too low
                                .via(revokeKycTxn)
                                .hasPrecheck(INSUFFICIENT_TX_FEE),
                        getTxnRecord(revokeKycTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TokenRevokeKyc - threshold payer key with invalid signature fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenRevokeKycThresholdKeyInvalidSigFailsOnIngest() {
                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
                SigControl validSig = keyShape.signedWith(sigs(ON, ON));
                SigControl invalidSig = keyShape.signedWith(sigs(ON, OFF));

                return hapiTest(
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(KYC_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .kycKey(KYC_KEY)
                                .treasury(TREASURY)
                                .payingWith(PAYER)
                                .sigControl(forKey(PAYER_KEY, validSig)),
                        tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT),
                        grantTokenKyc(TOKEN, ACCOUNT)
                                .payingWith(PAYER)
                                .sigControl(forKey(PAYER_KEY, validSig))
                                .signedBy(PAYER, KYC_KEY)
                                .via(grantKycTxn),
                        revokeTokenKyc(TOKEN, ACCOUNT)
                                .payingWith(PAYER)
                                .sigControl(forKey(PAYER_KEY, invalidSig))
                                .signedBy(PAYER, KYC_KEY)
                                .via(revokeKycTxn)
                                .hasPrecheck(INVALID_SIGNATURE),
                        getTxnRecord(revokeKycTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TokenRevokeKyc - insufficient payer balance fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenRevokeKycInsufficientPayerBalanceFailsOnIngest() {

                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS / 100_000L),
                        cryptoCreate(TREASURY).balance(0L),
                        cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(KYC_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .kycKey(KYC_KEY)
                                .treasury(TREASURY),
                        tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT),
                        grantTokenKyc(TOKEN, ACCOUNT),
                        revokeTokenKyc(TOKEN, ACCOUNT)
                                .payingWith(PAYER)
                                .signedBy(PAYER, KYC_KEY)
                                .via(revokeKycTxn)
                                .hasPrecheck(INSUFFICIENT_PAYER_BALANCE),
                        getTxnRecord(revokeKycTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TokenRevokeKyc - memo too long fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenRevokeKycMemoTooLongFailsOnIngest() {
                final var LONG_MEMO = "x".repeat(1025);
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(KYC_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .kycKey(KYC_KEY)
                                .treasury(TREASURY),
                        tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT),
                        grantTokenKyc(TOKEN, ACCOUNT),
                        revokeTokenKyc(TOKEN, ACCOUNT)
                                .payingWith(PAYER)
                                .signedBy(PAYER, KYC_KEY)
                                .memo(LONG_MEMO)
                                .via(revokeKycTxn)
                                .hasPrecheck(MEMO_TOO_LONG),
                        getTxnRecord(revokeKycTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TokenRevokeKyc - expired transaction fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenRevokeKycExpiredTransactionFailsOnIngest() {
                final var expiredTxnId = "expiredTxn";
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(KYC_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .kycKey(KYC_KEY)
                                .treasury(TREASURY),
                        tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT),
                        usableTxnIdNamed(expiredTxnId).modifyValidStart(-3_600L).payerId(PAYER),
                        grantTokenKyc(TOKEN, ACCOUNT),
                        revokeTokenKyc(TOKEN, ACCOUNT)
                                .payingWith(PAYER)
                                .signedBy(PAYER, KYC_KEY)
                                .txnId(expiredTxnId)
                                .via(revokeKycTxn)
                                .hasPrecheck(TRANSACTION_EXPIRED),
                        getTxnRecord(revokeKycTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TokenRevokeKyc - transaction with too far start time fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenRevokeKycTooFarStartTimeTransactionFailsOnIngest() {
                final var futureTxnId = "futureTxn";
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(KYC_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .kycKey(KYC_KEY)
                                .treasury(TREASURY),
                        tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT),
                        usableTxnIdNamed(futureTxnId).modifyValidStart(3_600L).payerId(PAYER),
                        grantTokenKyc(TOKEN, ACCOUNT),
                        revokeTokenKyc(TOKEN, ACCOUNT)
                                .payingWith(PAYER)
                                .signedBy(PAYER, KYC_KEY)
                                .txnId(futureTxnId)
                                .via(revokeKycTxn)
                                .hasPrecheck(INVALID_TRANSACTION_START),
                        getTxnRecord(revokeKycTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TokenRevokeKyc - invalid transaction duration fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenRevokeKycInvalidTransactionDurationFailsOnIngest() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(KYC_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .kycKey(KYC_KEY)
                                .treasury(TREASURY),
                        tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT),
                        grantTokenKyc(TOKEN, ACCOUNT),
                        revokeTokenKyc(TOKEN, ACCOUNT)
                                .payingWith(PAYER)
                                .signedBy(PAYER, KYC_KEY)
                                .validDurationSecs(0)
                                .via(revokeKycTxn)
                                .hasPrecheck(INVALID_TRANSACTION_DURATION),
                        getTxnRecord(revokeKycTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TokenRevokeKyc - duplicate transaction fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenRevokeKycDuplicateTransactionFailsOnIngest() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(KYC_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .kycKey(KYC_KEY)
                                .treasury(TREASURY),
                        tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT),
                        revokeTokenKyc(TOKEN, ACCOUNT)
                                .payingWith(PAYER)
                                .signedBy(PAYER, KYC_KEY)
                                .via("revokeKycTxnFirst"),
                        grantTokenKyc(TOKEN, ACCOUNT)
                                .payingWith(PAYER)
                                .signedBy(PAYER, KYC_KEY)
                                .txnId("revokeKycTxnFirst")
                                .via(revokeKycTxn)
                                .hasPrecheck(DUPLICATE_TRANSACTION));
            }

            @HapiTest
            @DisplayName("TokenRevokeKyc - missing kyc key signature fails at handle - full fees charged")
            final Stream<DynamicTest> tokenRevokeKycMissingKycKeySignatureFailsOnHandle() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(KYC_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .kycKey(KYC_KEY)
                                .treasury(TREASURY)
                                .payingWith(PAYER),
                        tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT),
                        grantTokenKyc(TOKEN, ACCOUNT).payingWith(PAYER).signedBy(PAYER, KYC_KEY),
                        revokeTokenKyc(TOKEN, ACCOUNT)
                                .payingWith(PAYER)
                                .signedBy(PAYER) // Missing KYC key signature
                                .via(revokeKycTxn)
                                .hasKnownStatus(INVALID_SIGNATURE),
                        validateChargedUsdFromRecordWithTxnSize(
                                revokeKycTxn,
                                txnSize -> expectedTokenRevokeKycFullFeeUsd(
                                        Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateChargedAccount(revokeKycTxn, PAYER));
            }

            @HapiTest
            @DisplayName("TokenRevokeKyc - no kyc key fails on handle - full fees charged")
            final Stream<DynamicTest> tokenRevokeKycNoKycKeyFailsOnHandle() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                // No KYC key
                                .treasury(TREASURY)
                                .payingWith(PAYER),
                        tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT),
                        revokeTokenKyc(TOKEN, ACCOUNT)
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .via(revokeKycTxn)
                                .hasKnownStatus(TOKEN_HAS_NO_KYC_KEY),
                        validateChargedUsdFromRecordWithTxnSize(
                                revokeKycTxn,
                                txnSize -> expectedTokenRevokeKycFullFeeUsd(
                                        Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateChargedAccount(revokeKycTxn, PAYER));
            }

            @HapiTest
            @DisplayName("TokenRevokeKyc - token not associated fails on handle - full fees charged")
            final Stream<DynamicTest> tokenRevokeKycNotAssociatedFailsOnHandle() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(KYC_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .kycKey(KYC_KEY)
                                .treasury(TREASURY)
                                .payingWith(PAYER),
                        // Not associating the token
                        revokeTokenKyc(TOKEN, ACCOUNT)
                                .payingWith(PAYER)
                                .signedBy(PAYER, KYC_KEY)
                                .via(revokeKycTxn)
                                .hasKnownStatus(TOKEN_NOT_ASSOCIATED_TO_ACCOUNT),
                        validateChargedUsdFromRecordWithTxnSize(
                                revokeKycTxn,
                                txnSize -> expectedTokenRevokeKycFullFeeUsd(
                                        Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateChargedAccount(revokeKycTxn, PAYER));
            }

            @Tag(ONLY_SUBPROCESS)
            @LeakyHapiTest
            @DisplayName("TokenRevokeKyc - duplicate transaction fails on handle - payer charged for first only")
            final Stream<DynamicTest> tokenRevokeKycDuplicateFailsOnHandle() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(KYC_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .kycKey(KYC_KEY)
                                .treasury(TREASURY)
                                .payingWith(PAYER),
                        tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT),
                        cryptoTransfer(movingHbar(ONE_HBAR).between(DEFAULT_PAYER, "3")),
                        usableTxnIdNamed(DUPLICATE_TXN_ID).payerId(PAYER),
                        grantTokenKyc(TOKEN, ACCOUNT),
                        revokeTokenKyc(TOKEN, ACCOUNT)
                                .payingWith(PAYER)
                                .signedBy(PAYER, KYC_KEY)
                                .setNode(4)
                                .txnId(DUPLICATE_TXN_ID)
                                .via(revokeKycTxn),
                        revokeTokenKyc(TOKEN, ACCOUNT)
                                .payingWith(PAYER)
                                .signedBy(PAYER, KYC_KEY)
                                .txnId(DUPLICATE_TXN_ID)
                                .setNode(3)
                                .hasPrecheck(DUPLICATE_TRANSACTION),
                        validateChargedUsdFromRecordWithTxnSize(
                                revokeKycTxn,
                                txnSize -> expectedTokenGrantKycFullFeeUsd(
                                        Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateChargedAccount(revokeKycTxn, PAYER));
            }
        }

        @Nested
        @DisplayName("TokenRevokeKyc Failures on Pre-Handle")
        class TokenGrantKycFailuresOnPreHandle {

            @LeakyEmbeddedHapiTest(reason = MUST_SKIP_INGEST)
            @DisplayName("TokenRevokeKyc - invalid payer signature fails on pre-handle - network fee only")
            final Stream<DynamicTest> tokenRevokeKycInvalidPayerSigFailsOnPreHandle() {
                final String INNER_ID = "revoke-kyc-txn-inner-id";

                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
                SigControl invalidSig = keyShape.signedWith(sigs(ON, OFF));

                return hapiTest(
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(KYC_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .kycKey(KYC_KEY)
                                .treasury(TREASURY),
                        tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT),
                        cryptoTransfer(movingHbar(ONE_HBAR).between(DEFAULT_PAYER, "4")),
                        grantTokenKyc(TOKEN, ACCOUNT),
                        revokeTokenKyc(TOKEN, ACCOUNT)
                                .payingWith(PAYER)
                                .sigControl(forKey(PAYER_KEY, invalidSig))
                                .signedBy(PAYER, KYC_KEY)
                                .setNode("4")
                                .via(INNER_ID)
                                .hasKnownStatus(INVALID_PAYER_SIGNATURE),
                        getTxnRecord(INNER_ID).assertingNothingAboutHashes().logged(),
                        validateChargedUsdFromRecordWithTxnSize(
                                INNER_ID,
                                txnSize -> expectedTokenGrantKycNetworkFeeOnlyUsd(
                                        Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateChargedAccount(INNER_ID, "4"));
            }
        }
    }
}
