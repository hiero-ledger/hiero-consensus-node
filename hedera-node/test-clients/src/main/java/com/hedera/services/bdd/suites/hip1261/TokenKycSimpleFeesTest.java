// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip1261;

import static com.hedera.services.bdd.junit.EmbeddedReason.MUST_SKIP_INGEST;
import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.junit.TestTags.SIMPLE_FEES;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.keys.ControlForKey.forKey;
import static com.hedera.services.bdd.spec.keys.KeyShape.SIMPLE;
import static com.hedera.services.bdd.spec.keys.KeyShape.sigs;
import static com.hedera.services.bdd.spec.keys.KeyShape.threshOf;
import static com.hedera.services.bdd.spec.keys.SigControl.OFF;
import static com.hedera.services.bdd.spec.keys.SigControl.ON;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.grantTokenKyc;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.revokeTokenKyc;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingHbar;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsdWithin;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTokenGrantKycFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTokenGrantKycNetworkFeeOnlyUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTokenRevokeKycFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.validateChargedFeeToUsd;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_TX_FEE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_PAYER_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.RECORD_NOT_FOUND;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_HAS_NO_KYC_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_NOT_ASSOCIATED_TO_ACCOUNT;
import static com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyEmbeddedHapiTest;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.keys.KeyShape;
import com.hedera.services.bdd.spec.keys.SigControl;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
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
@Tag(MATS)
@Tag(SIMPLE_FEES)
@HapiTestLifecycle
public class TokenKycSimpleFeesTest {

    private static final String PAYER = "payer";
    private static final String TREASURY = "treasury";
    private static final String ACCOUNT = "account";
    private static final String KYC_KEY = "kycKey";
    private static final String PAYER_KEY = "payerKey";
    private static final String TOKEN = "fungibleToken";

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
                            .payingWith(PAYER)
                            .fee(ONE_HUNDRED_HBARS),
                    tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT).fee(ONE_HUNDRED_HBARS),
                    grantTokenKyc(TOKEN, ACCOUNT)
                            .payingWith(PAYER)
                            .signedBy(PAYER, KYC_KEY)
                            .fee(ONE_HUNDRED_HBARS)
                            .via("grantKycTxn"),
                    validateChargedUsdWithin(
                            "grantKycTxn",
                            expectedTokenGrantKycFullFeeUsd(2L), // 2 sigs
                            0.001));
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
                            .fee(ONE_HUNDRED_HBARS)
                            .sigControl(forKey(PAYER_KEY, validSig)),
                    tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT).fee(ONE_HUNDRED_HBARS),
                    grantTokenKyc(TOKEN, ACCOUNT)
                            .payingWith(PAYER)
                            .sigControl(forKey(PAYER_KEY, validSig))
                            .signedBy(PAYER, KYC_KEY)
                            .fee(ONE_HUNDRED_HBARS)
                            .via("grantKycTxn"),
                    validateChargedUsdWithin(
                            "grantKycTxn",
                            expectedTokenGrantKycFullFeeUsd(3L), // 3 sigs (2 payer + 1 kyc key)
                            0.001));
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
                            .fee(ONE_HUNDRED_HBARS)
                            .sigControl(forKey(KYC_KEY, validSig)),
                    tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT).fee(ONE_HUNDRED_HBARS),
                    grantTokenKyc(TOKEN, ACCOUNT)
                            .payingWith(PAYER)
                            .signedBy(PAYER, KYC_KEY)
                            .sigControl(forKey(KYC_KEY, validSig))
                            .fee(ONE_HUNDRED_HBARS)
                            .via("grantKycTxn"),
                    validateChargedUsdWithin(
                            "grantKycTxn",
                            expectedTokenGrantKycFullFeeUsd(3L), // 3 sigs (1 payer + 2 kyc key)
                            0.001));
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
                            .payingWith(PAYER)
                            .fee(ONE_HUNDRED_HBARS),
                    tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT).fee(ONE_HUNDRED_HBARS),
                    grantTokenKyc(TOKEN, ACCOUNT)
                            .payingWith(PAYER)
                            .signedBy(PAYER, KYC_KEY)
                            .fee(ONE_HUNDRED_HBARS),
                    revokeTokenKyc(TOKEN, ACCOUNT)
                            .payingWith(PAYER)
                            .signedBy(PAYER, KYC_KEY)
                            .fee(ONE_HUNDRED_HBARS)
                            .via("revokeKycTxn"),
                    validateChargedUsdWithin(
                            "revokeKycTxn",
                            expectedTokenRevokeKycFullFeeUsd(2L), // 2 sigs
                            0.001));
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
                            .fee(ONE_HUNDRED_HBARS)
                            .sigControl(forKey(PAYER_KEY, validSig)),
                    tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT).fee(ONE_HUNDRED_HBARS),
                    grantTokenKyc(TOKEN, ACCOUNT)
                            .payingWith(PAYER)
                            .sigControl(forKey(PAYER_KEY, validSig))
                            .signedBy(PAYER, KYC_KEY)
                            .fee(ONE_HUNDRED_HBARS),
                    revokeTokenKyc(TOKEN, ACCOUNT)
                            .payingWith(PAYER)
                            .sigControl(forKey(PAYER_KEY, validSig))
                            .signedBy(PAYER, KYC_KEY)
                            .fee(ONE_HUNDRED_HBARS)
                            .via("revokeKycTxn"),
                    validateChargedUsdWithin(
                            "revokeKycTxn",
                            expectedTokenRevokeKycFullFeeUsd(3L), // 3 sigs (2 payer + 1 kyc key)
                            0.001));
        }
    }

    @Nested
    @DisplayName("TokenKyc Simple Fees Negative Test Cases")
    class TokenKycNegativeTestCases {

        @Nested
        @DisplayName("TokenGrantKyc Failures on Ingest and Handle")
        class TokenGrantKycFailuresOnIngest {

            @HapiTest
            @DisplayName("TokenGrantKyc - missing kyc key signature fails at handle")
            final Stream<DynamicTest> tokenGrantKycMissingKycKeySignatureFailsAtHandle() {
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();

                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(KYC_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .kycKey(KYC_KEY)
                                .treasury(TREASURY)
                                .payingWith(PAYER)
                                .fee(ONE_HUNDRED_HBARS),
                        tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT).fee(ONE_HUNDRED_HBARS),
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                        grantTokenKyc(TOKEN, ACCOUNT)
                                .payingWith(PAYER)
                                .signedBy(PAYER) // Missing KYC key signature
                                .fee(ONE_HUNDRED_HBARS)
                                .via("grantKycTxn")
                                .hasKnownStatus(INVALID_SIGNATURE),
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        withOpContext((spec, log) -> {
                            assertTrue(initialBalance.get() > afterBalance.get());
                        }),
                        validateChargedFeeToUsd(
                                "grantKycTxn",
                                initialBalance,
                                afterBalance,
                                expectedTokenGrantKycFullFeeUsd(1L),
                                0.001));
            }

            @HapiTest
            @DisplayName("TokenGrantKyc - insufficient tx fee fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenGrantKycInsufficientTxFeeFailsOnIngest() {
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();

                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(KYC_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .kycKey(KYC_KEY)
                                .treasury(TREASURY)
                                .payingWith(PAYER)
                                .fee(ONE_HUNDRED_HBARS),
                        tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT).fee(ONE_HUNDRED_HBARS),
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                        grantTokenKyc(TOKEN, ACCOUNT)
                                .payingWith(PAYER)
                                .signedBy(PAYER, KYC_KEY)
                                .fee(1L) // Fee too low
                                .via("grantKycTxn")
                                .hasPrecheck(INSUFFICIENT_TX_FEE),
                        getTxnRecord("grantKycTxn").hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND),
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        withOpContext((spec, log) -> {
                            assertEquals(initialBalance.get(), afterBalance.get());
                        }));
            }

            @HapiTest
            @DisplayName("TokenGrantKyc - no kyc key fails")
            final Stream<DynamicTest> tokenGrantKycNoKycKeyFails() {
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();

                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                // No KYC key
                                .treasury(TREASURY)
                                .payingWith(PAYER)
                                .fee(ONE_HUNDRED_HBARS),
                        tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT).fee(ONE_HUNDRED_HBARS),
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                        grantTokenKyc(TOKEN, ACCOUNT)
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .fee(ONE_HUNDRED_HBARS)
                                .via("grantKycTxn")
                                .hasKnownStatus(TOKEN_HAS_NO_KYC_KEY),
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        withOpContext((spec, log) -> {
                            assertTrue(initialBalance.get() > afterBalance.get());
                        }),
                        validateChargedFeeToUsd(
                                "grantKycTxn",
                                initialBalance,
                                afterBalance,
                                expectedTokenGrantKycFullFeeUsd(1L),
                                0.001));
            }

            @HapiTest
            @DisplayName("TokenGrantKyc - token not associated fails")
            final Stream<DynamicTest> tokenGrantKycNotAssociatedFails() {
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();

                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(KYC_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .kycKey(KYC_KEY)
                                .treasury(TREASURY)
                                .payingWith(PAYER)
                                .fee(ONE_HUNDRED_HBARS),
                        // Not associating the token
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                        grantTokenKyc(TOKEN, ACCOUNT)
                                .payingWith(PAYER)
                                .signedBy(PAYER, KYC_KEY)
                                .fee(ONE_HUNDRED_HBARS)
                                .via("grantKycTxn")
                                .hasKnownStatus(TOKEN_NOT_ASSOCIATED_TO_ACCOUNT),
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        withOpContext((spec, log) -> {
                            assertTrue(initialBalance.get() > afterBalance.get());
                        }),
                        validateChargedFeeToUsd(
                                "grantKycTxn",
                                initialBalance,
                                afterBalance,
                                expectedTokenGrantKycFullFeeUsd(2L),
                                0.001));
            }
        }

        @Nested
        @DisplayName("TokenGrantKyc Failures on Pre-Handle")
        class TokenGrantKycFailuresOnPreHandle {

            @LeakyEmbeddedHapiTest(reason = MUST_SKIP_INGEST)
            @DisplayName("TokenGrantKyc - invalid payer signature fails on pre-handle - network fee only")
            final Stream<DynamicTest> tokenGrantKycInvalidPayerSigFailsOnPreHandle() {
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();
                final AtomicLong initialNodeBalance = new AtomicLong();
                final AtomicLong afterNodeBalance = new AtomicLong();

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
                                .treasury(TREASURY)
                                .fee(ONE_HUNDRED_HBARS),
                        tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT).fee(ONE_HUNDRED_HBARS),
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                        cryptoTransfer(movingHbar(ONE_HBAR).between(DEFAULT_PAYER, "0.0.4"))
                                .fee(ONE_HUNDRED_HBARS),
                        getAccountBalance("0.0.4").exposingBalanceTo(initialNodeBalance::set),
                        grantTokenKyc(TOKEN, ACCOUNT)
                                .payingWith(PAYER)
                                .sigControl(forKey(PAYER_KEY, invalidSig))
                                .signedBy(PAYER, KYC_KEY)
                                .fee(ONE_HUNDRED_HBARS)
                                .setNode("0.0.4")
                                .via(INNER_ID)
                                .hasKnownStatus(INVALID_PAYER_SIGNATURE),
                        getTxnRecord(INNER_ID).assertingNothingAboutHashes().logged(),
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        getAccountBalance("0.0.4").exposingBalanceTo(afterNodeBalance::set),
                        withOpContext((spec, log) -> {
                            assertEquals(initialBalance.get(), afterBalance.get());
                            assertTrue(initialNodeBalance.get() > afterNodeBalance.get());
                        }),
                        validateChargedFeeToUsd(
                                INNER_ID,
                                initialNodeBalance,
                                afterNodeBalance,
                                expectedTokenGrantKycNetworkFeeOnlyUsd(2L),
                                0.001));
            }
        }
    }

    @Nested
    @DisplayName("TokenRevokeKyc Simple Fees Negative Test Cases")
    class TokenRevokeKycNegativeTestCases {

        @Nested
        @DisplayName("TokenRevokeKyc Failures on Ingest and Handle")
        class TokenRevokeKycFailuresOnIngest {

            @HapiTest
            @DisplayName("TokenRevokeKyc - missing kyc key signature fails at handle")
            final Stream<DynamicTest> tokenRevokeKycMissingKycKeySignatureFailsAtHandle() {
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();

                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(KYC_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .kycKey(KYC_KEY)
                                .treasury(TREASURY)
                                .payingWith(PAYER)
                                .fee(ONE_HUNDRED_HBARS),
                        tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT).fee(ONE_HUNDRED_HBARS),
                        grantTokenKyc(TOKEN, ACCOUNT)
                                .payingWith(PAYER)
                                .signedBy(PAYER, KYC_KEY)
                                .fee(ONE_HUNDRED_HBARS),
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                        revokeTokenKyc(TOKEN, ACCOUNT)
                                .payingWith(PAYER)
                                .signedBy(PAYER) // Missing KYC key signature
                                .fee(ONE_HUNDRED_HBARS)
                                .via("revokeKycTxn")
                                .hasKnownStatus(INVALID_SIGNATURE),
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        withOpContext((spec, log) -> {
                            assertTrue(initialBalance.get() > afterBalance.get());
                        }),
                        validateChargedFeeToUsd(
                                "revokeKycTxn",
                                initialBalance,
                                afterBalance,
                                expectedTokenRevokeKycFullFeeUsd(1L),
                                0.001));
            }

            @HapiTest
            @DisplayName("TokenRevokeKyc - insufficient tx fee fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenRevokeKycInsufficientTxFeeFailsOnIngest() {
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();

                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(KYC_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .kycKey(KYC_KEY)
                                .treasury(TREASURY)
                                .payingWith(PAYER)
                                .fee(ONE_HUNDRED_HBARS),
                        tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT).fee(ONE_HUNDRED_HBARS),
                        grantTokenKyc(TOKEN, ACCOUNT)
                                .payingWith(PAYER)
                                .signedBy(PAYER, KYC_KEY)
                                .fee(ONE_HUNDRED_HBARS),
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                        revokeTokenKyc(TOKEN, ACCOUNT)
                                .payingWith(PAYER)
                                .signedBy(PAYER, KYC_KEY)
                                .fee(1L) // Fee too low
                                .via("revokeKycTxn")
                                .hasPrecheck(INSUFFICIENT_TX_FEE),
                        getTxnRecord("revokeKycTxn").hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND),
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        withOpContext((spec, log) -> {
                            assertEquals(initialBalance.get(), afterBalance.get());
                        }));
            }

            @HapiTest
            @DisplayName("TokenRevokeKyc - no kyc key fails")
            final Stream<DynamicTest> tokenRevokeKycNoKycKeyFails() {
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();

                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                // No KYC key
                                .treasury(TREASURY)
                                .payingWith(PAYER)
                                .fee(ONE_HUNDRED_HBARS),
                        tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT).fee(ONE_HUNDRED_HBARS),
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                        revokeTokenKyc(TOKEN, ACCOUNT)
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .fee(ONE_HUNDRED_HBARS)
                                .via("revokeKycTxn")
                                .hasKnownStatus(TOKEN_HAS_NO_KYC_KEY),
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        withOpContext((spec, log) -> {
                            assertTrue(initialBalance.get() > afterBalance.get());
                        }),
                        validateChargedFeeToUsd(
                                "revokeKycTxn",
                                initialBalance,
                                afterBalance,
                                expectedTokenRevokeKycFullFeeUsd(1L),
                                0.001));
            }

            @HapiTest
            @DisplayName("TokenRevokeKyc - token not associated fails")
            final Stream<DynamicTest> tokenRevokeKycNotAssociatedFails() {
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();

                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(KYC_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .kycKey(KYC_KEY)
                                .treasury(TREASURY)
                                .payingWith(PAYER)
                                .fee(ONE_HUNDRED_HBARS),
                        // Not associating the token
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                        revokeTokenKyc(TOKEN, ACCOUNT)
                                .payingWith(PAYER)
                                .signedBy(PAYER, KYC_KEY)
                                .fee(ONE_HUNDRED_HBARS)
                                .via("revokeKycTxn")
                                .hasKnownStatus(TOKEN_NOT_ASSOCIATED_TO_ACCOUNT),
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        withOpContext((spec, log) -> {
                            assertTrue(initialBalance.get() > afterBalance.get());
                        }),
                        validateChargedFeeToUsd(
                                "revokeKycTxn",
                                initialBalance,
                                afterBalance,
                                expectedTokenRevokeKycFullFeeUsd(2L),
                                0.001));
            }
        }
    }
}
