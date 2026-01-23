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
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenDissociate;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingHbar;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyListNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsdWithin;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTokenAssociateFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTokenAssociateNetworkFeeOnlyUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTokenDissociateFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTokenDissociateNetworkFeeOnlyUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.validateChargedFeeToUsd;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_TX_FEE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_PAYER_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOKEN_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.RECORD_NOT_FOUND;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_ALREADY_ASSOCIATED_TO_ACCOUNT;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;

/**
 * Tests for TokenAssociate and TokenDissociate simple fees with extras.
 * Validates that fees are correctly calculated based on:
 * - Number of signatures (extras beyond included)
 * - Number of tokens being associated/dissociated (each token costs base fee)
 */
@Tag(MATS)
@Tag(SIMPLE_FEES)
@HapiTestLifecycle
public class TokenAssociateSimpleFeesTest {

    private static final String PAYER = "payer";
    private static final String TREASURY = "treasury";
    private static final String ACCOUNT = "account";
    private static final String PAYER_KEY = "payerKey";
    private static final String TOKEN1 = "token1";
    private static final String TOKEN2 = "token2";
    private static final String TOKEN3 = "token3";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(Map.of("fees.simpleFeesEnabled", "true"));
    }

    @Nested
    @DisplayName("TokenAssociate Simple Fees Positive Test Cases")
    class TokenAssociatePositiveTestCases {

        @HapiTest
        @DisplayName("TokenAssociate - base fees for single token")
        final Stream<DynamicTest> tokenAssociateSingleTokenBaseFee() {
            return hapiTest(
                    cryptoCreate(TREASURY),
                    cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                    tokenCreate(TOKEN1).tokenType(FUNGIBLE_COMMON).treasury(TREASURY),
                    tokenAssociate(ACCOUNT, TOKEN1)
                            .payingWith(ACCOUNT)
                            .signedBy(ACCOUNT)
                            .fee(ONE_HUNDRED_HBARS)
                            .via("tokenAssociateTxn"),
                    validateChargedUsdWithin(
                            "tokenAssociateTxn",
                            expectedTokenAssociateFullFeeUsd(1L, 1L), // 1 sig, 1 token
                            0.001));
        }

        @HapiTest
        @DisplayName("TokenAssociate - multiple tokens extra fee")
        final Stream<DynamicTest> tokenAssociateMultipleTokens() {
            return hapiTest(
                    cryptoCreate(TREASURY),
                    cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                    tokenCreate(TOKEN1).tokenType(FUNGIBLE_COMMON).treasury(TREASURY),
                    tokenCreate(TOKEN2).tokenType(FUNGIBLE_COMMON).treasury(TREASURY),
                    tokenCreate(TOKEN3).tokenType(FUNGIBLE_COMMON).treasury(TREASURY),
                    tokenAssociate(ACCOUNT, TOKEN1, TOKEN2, TOKEN3)
                            .payingWith(ACCOUNT)
                            .signedBy(ACCOUNT)
                            .fee(ONE_HUNDRED_HBARS)
                            .via("tokenAssociateTxn"),
                    validateChargedUsdWithin(
                            "tokenAssociateTxn",
                            expectedTokenAssociateFullFeeUsd(1L, 3L), // 1 sig, 3 tokens
                            0.001));
        }

        @HapiTest
        @DisplayName("TokenAssociate with threshold key - extra signatures")
        final Stream<DynamicTest> tokenAssociateWithThresholdKey() {
            KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
            SigControl validSig = keyShape.signedWith(sigs(ON, ON));

            return hapiTest(
                    cryptoCreate(TREASURY),
                    newKeyNamed(PAYER_KEY).shape(keyShape),
                    cryptoCreate(ACCOUNT).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                    tokenCreate(TOKEN1).tokenType(FUNGIBLE_COMMON).treasury(TREASURY),
                    tokenAssociate(ACCOUNT, TOKEN1)
                            .payingWith(ACCOUNT)
                            .sigControl(forKey(PAYER_KEY, validSig))
                            .signedBy(ACCOUNT)
                            .fee(ONE_HUNDRED_HBARS)
                            .via("tokenAssociateTxn"),
                    validateChargedUsdWithin(
                            "tokenAssociateTxn",
                            expectedTokenAssociateFullFeeUsd(2L, 1L), // 2 sigs, 1 token
                            0.001));
        }

        @HapiTest
        @DisplayName("TokenAssociate - 5 tokens extra fee")
        final Stream<DynamicTest> tokenAssociateFiveTokens() {
            return hapiTest(
                    cryptoCreate(TREASURY),
                    cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                    tokenCreate("tokenA").tokenType(FUNGIBLE_COMMON).treasury(TREASURY),
                    tokenCreate("tokenB").tokenType(FUNGIBLE_COMMON).treasury(TREASURY),
                    tokenCreate("tokenC").tokenType(FUNGIBLE_COMMON).treasury(TREASURY),
                    tokenCreate("tokenD").tokenType(FUNGIBLE_COMMON).treasury(TREASURY),
                    tokenCreate("tokenE").tokenType(FUNGIBLE_COMMON).treasury(TREASURY),
                    tokenAssociate(ACCOUNT, "tokenA", "tokenB", "tokenC", "tokenD", "tokenE")
                            .payingWith(ACCOUNT)
                            .signedBy(ACCOUNT)
                            .fee(ONE_HUNDRED_HBARS)
                            .via("tokenAssociateTxn"),
                    validateChargedUsdWithin(
                            "tokenAssociateTxn",
                            expectedTokenAssociateFullFeeUsd(1L, 5L), // 1 sig, 5 tokens
                            0.001));
        }
    }

    @Nested
    @DisplayName("TokenDissociate Simple Fees Positive Test Cases")
    class TokenDissociatePositiveTestCases {

        @HapiTest
        @DisplayName("TokenDissociate - base fees for single token")
        final Stream<DynamicTest> tokenDissociateSingleTokenBaseFee() {
            return hapiTest(
                    cryptoCreate(TREASURY),
                    cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                    tokenCreate(TOKEN1).tokenType(FUNGIBLE_COMMON).treasury(TREASURY),
                    tokenAssociate(ACCOUNT, TOKEN1).payingWith(ACCOUNT),
                    tokenDissociate(ACCOUNT, TOKEN1)
                            .payingWith(ACCOUNT)
                            .signedBy(ACCOUNT)
                            .fee(ONE_HUNDRED_HBARS)
                            .via("tokenDissociateTxn"),
                    validateChargedUsdWithin(
                            "tokenDissociateTxn",
                            expectedTokenDissociateFullFeeUsd(1L, 1L), // 1 sig, 1 token
                            0.001));
        }

        @HapiTest
        @DisplayName("TokenDissociate - multiple tokens extra fee")
        final Stream<DynamicTest> tokenDissociateMultipleTokens() {
            return hapiTest(
                    cryptoCreate(TREASURY),
                    cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                    tokenCreate(TOKEN1).tokenType(FUNGIBLE_COMMON).treasury(TREASURY),
                    tokenCreate(TOKEN2).tokenType(FUNGIBLE_COMMON).treasury(TREASURY),
                    tokenCreate(TOKEN3).tokenType(FUNGIBLE_COMMON).treasury(TREASURY),
                    tokenAssociate(ACCOUNT, TOKEN1, TOKEN2, TOKEN3).payingWith(ACCOUNT),
                    tokenDissociate(ACCOUNT, TOKEN1, TOKEN2, TOKEN3)
                            .payingWith(ACCOUNT)
                            .signedBy(ACCOUNT)
                            .fee(ONE_HUNDRED_HBARS)
                            .via("tokenDissociateTxn"),
                    validateChargedUsdWithin(
                            "tokenDissociateTxn",
                            expectedTokenDissociateFullFeeUsd(1L, 3L), // 1 sig, 3 tokens
                            0.001));
        }
    }

    @Nested
    @DisplayName("TokenAssociate Simple Fees Negative Test Cases")
    class TokenAssociateNegativeTestCases {

        @Nested
        @DisplayName("TokenAssociate Failures on Ingest and Handle")
        class TokenAssociateFailuresOnIngest {

            // TODO revisit this test failure Answer-only precheck was OK, not one of [RECORD_NOT_FOUND]!
            // com.hedera.services.bdd.spec.exceptions.HapiQueryPrecheckStateException: Answer-only precheck was OK, not
            // one of [RECORD_NOT_FOUND]!
            @HapiTest
            @DisplayName("TokenAssociate - invalid signature fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenAssociateInvalidSignatureFailsOnIngest() {
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();
                return hapiTest(
                        newKeyNamed("firstKey"),
                        newKeyNamed("secondKey"),
                        newKeyListNamed(PAYER_KEY, List.of("firstKey", "secondKey")),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        tokenCreate(TOKEN1)
                                .tokenType(FUNGIBLE_COMMON)
                                .payingWith(PAYER)
                                .fee(ONE_HUNDRED_HBARS),
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                        tokenAssociate(PAYER, TOKEN1)
                                .payingWith(PAYER)
                                .signedBy("firstKey")
                                .fee(ONE_HUNDRED_HBARS)
                                .via("tokenAssociateTxn")
                                .hasPrecheck(INVALID_SIGNATURE),
                        getTxnRecord("tokenAssociateTxn").hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND),
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        withOpContext((spec, log) -> {
                            assertEquals(initialBalance.get(), afterBalance.get());
                        }));
            }

            @HapiTest
            @DisplayName("TokenAssociate - insufficient tx fee fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenAssociateInsufficientTxFeeFailsOnIngest() {
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();

                return hapiTest(
                        cryptoCreate(TREASURY),
                        cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                        tokenCreate(TOKEN1).tokenType(FUNGIBLE_COMMON).treasury(TREASURY),
                        getAccountBalance(ACCOUNT).exposingBalanceTo(initialBalance::set),
                        tokenAssociate(ACCOUNT, TOKEN1)
                                .payingWith(ACCOUNT)
                                .signedBy(ACCOUNT)
                                .fee(1L) // Fee too low
                                .via("tokenAssociateTxn")
                                .hasPrecheck(INSUFFICIENT_TX_FEE),
                        getTxnRecord("tokenAssociateTxn").hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND),
                        getAccountBalance(ACCOUNT).exposingBalanceTo(afterBalance::set),
                        withOpContext((spec, log) -> {
                            assertEquals(initialBalance.get(), afterBalance.get());
                        }));
            }

            @HapiTest
            @DisplayName("TokenAssociate - invalid token fails on handle - full fee charged")
            final Stream<DynamicTest> tokenAssociateInvalidTokenFails() {
                return hapiTest(
                        cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                        tokenAssociate(ACCOUNT, "0.0.99999999") // Invalid token
                                .payingWith(ACCOUNT)
                                .signedBy(ACCOUNT)
                                .fee(ONE_HUNDRED_HBARS)
                                .via("tokenAssociateTxn")
                                .hasKnownStatus(INVALID_TOKEN_ID),
                        validateChargedUsdWithin(
                                "tokenAssociateTxn",
                                expectedTokenAssociateFullFeeUsd(1L, 1L), // 1 sig, 1 token
                                0.001));
            }

            @HapiTest
            @DisplayName("TokenAssociate - already associated fails on handle - full fee charged")
            final Stream<DynamicTest> tokenAssociateAlreadyAssociatedFails() {
                return hapiTest(
                        cryptoCreate(TREASURY),
                        cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                        tokenCreate(TOKEN1).tokenType(FUNGIBLE_COMMON).treasury(TREASURY),
                        tokenAssociate(ACCOUNT, TOKEN1).payingWith(ACCOUNT),
                        tokenAssociate(ACCOUNT, TOKEN1)
                                .payingWith(ACCOUNT)
                                .signedBy(ACCOUNT)
                                .fee(ONE_HUNDRED_HBARS)
                                .via("tokenAssociateTxn")
                                .hasKnownStatus(TOKEN_ALREADY_ASSOCIATED_TO_ACCOUNT),
                        validateChargedUsdWithin(
                                "tokenAssociateTxn",
                                expectedTokenAssociateFullFeeUsd(1L, 1L), // 1 sig, 1 token
                                0.001));
            }
        }

        @Nested
        @DisplayName("TokenAssociate Failures on Pre-Handle")
        class TokenAssociateFailuresOnPreHandle {

            @LeakyEmbeddedHapiTest(reason = MUST_SKIP_INGEST)
            @DisplayName("TokenAssociate - invalid payer signature fails on pre-handle - network fee only")
            final Stream<DynamicTest> tokenAssociateInvalidPayerSigFailsOnPreHandle() {
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();
                final AtomicLong initialNodeBalance = new AtomicLong();
                final AtomicLong afterNodeBalance = new AtomicLong();

                final String INNER_ID = "token-associate-txn-inner-id";

                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
                SigControl invalidSig = keyShape.signedWith(sigs(ON, OFF));

                return hapiTest(
                        cryptoCreate(TREASURY),
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(ACCOUNT).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        tokenCreate(TOKEN1).tokenType(FUNGIBLE_COMMON).treasury(TREASURY),
                        getAccountBalance(ACCOUNT).exposingBalanceTo(initialBalance::set),
                        cryptoTransfer(movingHbar(ONE_HBAR).between(DEFAULT_PAYER, "0.0.4")),
                        getAccountBalance("0.0.4").exposingBalanceTo(initialNodeBalance::set),
                        tokenAssociate(ACCOUNT, TOKEN1)
                                .payingWith(ACCOUNT)
                                .sigControl(forKey(PAYER_KEY, invalidSig))
                                .signedBy(ACCOUNT)
                                .fee(ONE_HUNDRED_HBARS)
                                .setNode("0.0.4")
                                .via(INNER_ID)
                                .hasKnownStatus(INVALID_PAYER_SIGNATURE),
                        getTxnRecord(INNER_ID).assertingNothingAboutHashes().logged(),
                        getAccountBalance(ACCOUNT).exposingBalanceTo(afterBalance::set),
                        getAccountBalance("0.0.4").exposingBalanceTo(afterNodeBalance::set),
                        withOpContext((spec, log) -> {
                            assertEquals(initialBalance.get(), afterBalance.get());
                            assertTrue(initialNodeBalance.get() > afterNodeBalance.get());
                        }),
                        validateChargedFeeToUsd(
                                INNER_ID,
                                initialNodeBalance,
                                afterNodeBalance,
                                expectedTokenAssociateNetworkFeeOnlyUsd(1L),
                                0.001));
            }
        }
    }

    @Nested
    @DisplayName("TokenDissociate Simple Fees Negative Test Cases")
    class TokenDissociateNegativeTestCases {

        @Nested
        @DisplayName("TokenDissociate Failures on Ingest and Handle")
        class TokenDissociateFailuresOnIngest {

            @HapiTest
            @DisplayName("TokenDissociate - insufficient tx fee fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenDissociateInsufficientTxFeeFailsOnIngest() {
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();

                return hapiTest(
                        cryptoCreate(TREASURY),
                        cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                        tokenCreate(TOKEN1).tokenType(FUNGIBLE_COMMON).treasury(TREASURY),
                        tokenAssociate(ACCOUNT, TOKEN1).payingWith(ACCOUNT),
                        getAccountBalance(ACCOUNT).exposingBalanceTo(initialBalance::set),
                        tokenDissociate(ACCOUNT, TOKEN1)
                                .payingWith(ACCOUNT)
                                .signedBy(ACCOUNT)
                                .fee(1L) // Fee too low
                                .via("tokenDissociateTxn")
                                .hasPrecheck(INSUFFICIENT_TX_FEE),
                        getTxnRecord("tokenDissociateTxn").hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND),
                        getAccountBalance(ACCOUNT).exposingBalanceTo(afterBalance::set),
                        withOpContext((spec, log) -> {
                            assertEquals(initialBalance.get(), afterBalance.get());
                        }));
            }

            @HapiTest
            @DisplayName("TokenDissociate - invalid token fails on handle - full fee charged")
            final Stream<DynamicTest> tokenDissociateInvalidTokenFails() {
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();

                return hapiTest(
                        cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                        getAccountBalance(ACCOUNT).exposingBalanceTo(initialBalance::set),
                        tokenDissociate(ACCOUNT, "0.0.99999999") // Invalid token
                                .payingWith(ACCOUNT)
                                .signedBy(ACCOUNT)
                                .fee(ONE_HUNDRED_HBARS)
                                .via("tokenDissociateTxn")
                                .hasKnownStatus(TOKEN_NOT_ASSOCIATED_TO_ACCOUNT),
                        getAccountBalance(ACCOUNT).exposingBalanceTo(afterBalance::set),
                        withOpContext((spec, log) -> {
                            assertTrue(initialBalance.get() > afterBalance.get());
                        }),
                        validateChargedFeeToUsd(
                                "tokenDissociateTxn",
                                initialBalance,
                                afterBalance,
                                expectedTokenDissociateFullFeeUsd(1L),
                                0.001));
            }

            @HapiTest
            @DisplayName("TokenDissociate - not associated fails on handle - full fee charged")
            final Stream<DynamicTest> tokenDissociateNotAssociatedFails() {
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();

                return hapiTest(
                        cryptoCreate(TREASURY),
                        cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                        tokenCreate(TOKEN1).tokenType(FUNGIBLE_COMMON).treasury(TREASURY),
                        // Not associating the token
                        getAccountBalance(ACCOUNT).exposingBalanceTo(initialBalance::set),
                        tokenDissociate(ACCOUNT, TOKEN1)
                                .payingWith(ACCOUNT)
                                .signedBy(ACCOUNT)
                                .fee(ONE_HUNDRED_HBARS)
                                .via("tokenDissociateTxn")
                                .hasKnownStatus(TOKEN_NOT_ASSOCIATED_TO_ACCOUNT),
                        getAccountBalance(ACCOUNT).exposingBalanceTo(afterBalance::set),
                        withOpContext((spec, log) -> {
                            assertTrue(initialBalance.get() > afterBalance.get());
                        }),
                        validateChargedFeeToUsd(
                                "tokenDissociateTxn",
                                initialBalance,
                                afterBalance,
                                expectedTokenDissociateFullFeeUsd(1L),
                                0.001));
            }
        }

        @Nested
        @DisplayName("TokenDissociate Failures on Pre-Handle")
        class TokenDissociateFailuresOnPreHandle {

            @LeakyEmbeddedHapiTest(reason = MUST_SKIP_INGEST)
            @DisplayName("TokenDissociate - invalid payer signature fails on pre-handle - network fee only")
            final Stream<DynamicTest> tokenDissociateInvalidPayerSigFailsOnPreHandle() {
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();
                final AtomicLong initialNodeBalance = new AtomicLong();
                final AtomicLong afterNodeBalance = new AtomicLong();

                final String INNER_ID = "token-dissociate-txn-inner-id";

                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
                SigControl invalidSig = keyShape.signedWith(sigs(ON, OFF));

                return hapiTest(
                        cryptoCreate(TREASURY),
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(ACCOUNT).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        tokenCreate(TOKEN1).tokenType(FUNGIBLE_COMMON).treasury(TREASURY),
                        tokenAssociate(ACCOUNT, TOKEN1).payingWith(ACCOUNT).fee(ONE_HUNDRED_HBARS),
                        getAccountBalance(ACCOUNT).exposingBalanceTo(initialBalance::set),
                        cryptoTransfer(movingHbar(ONE_HBAR).between(DEFAULT_PAYER, "0.0.4")),
                        getAccountBalance("0.0.4").exposingBalanceTo(initialNodeBalance::set),
                        tokenDissociate(ACCOUNT, TOKEN1)
                                .payingWith(ACCOUNT)
                                .sigControl(forKey(PAYER_KEY, invalidSig))
                                .signedBy(ACCOUNT)
                                .fee(ONE_HUNDRED_HBARS)
                                .setNode("0.0.4")
                                .via(INNER_ID)
                                .hasKnownStatus(INVALID_PAYER_SIGNATURE),
                        getTxnRecord(INNER_ID).assertingNothingAboutHashes().logged(),
                        getAccountBalance(ACCOUNT).exposingBalanceTo(afterBalance::set),
                        getAccountBalance("0.0.4").exposingBalanceTo(afterNodeBalance::set),
                        withOpContext((spec, log) -> {
                            assertEquals(initialBalance.get(), afterBalance.get());
                            assertTrue(initialNodeBalance.get() > afterNodeBalance.get());
                        }),
                        validateChargedFeeToUsd(
                                INNER_ID,
                                initialNodeBalance,
                                afterNodeBalance,
                                expectedTokenDissociateNetworkFeeOnlyUsd(1L),
                                0.001));
            }
        }
    }
}
