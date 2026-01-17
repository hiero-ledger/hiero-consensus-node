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
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenFreeze;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenUnfreeze;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingHbar;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsdWithin;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTokenFreezeFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTokenFreezeNetworkFeeOnlyUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTokenUnfreezeFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.validateChargedFeeToUsd;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_FROZEN_FOR_TOKEN;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_TX_FEE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_PAYER_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.RECORD_NOT_FOUND;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_HAS_NO_FREEZE_KEY;
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
 * Tests for TokenFreeze and TokenUnfreeze simple fees with extras.
 * Validates that fees are correctly calculated based on:
 * - Number of signatures (extras beyond included)
 */
@Tag(MATS)
@Tag(SIMPLE_FEES)
@HapiTestLifecycle
public class TokenFreezeSimpleFeesTest {

    private static final String PAYER = "payer";
    private static final String TREASURY = "treasury";
    private static final String ACCOUNT = "account";
    private static final String FREEZE_KEY = "freezeKey";
    private static final String PAYER_KEY = "payerKey";
    private static final String TOKEN = "fungibleToken";

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
                            .payingWith(PAYER)
                            .fee(ONE_HUNDRED_HBARS),
                    tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT).fee(ONE_HUNDRED_HBARS),
                    tokenFreeze(TOKEN, ACCOUNT)
                            .payingWith(PAYER)
                            .signedBy(PAYER, FREEZE_KEY)
                            .fee(ONE_HUNDRED_HBARS)
                            .via("freezeTxn"),
                    validateChargedUsdWithin(
                            "freezeTxn",
                            expectedTokenFreezeFullFeeUsd(2L), // 2 sigs
                            50.0));
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
                            .fee(ONE_HUNDRED_HBARS)
                            .sigControl(forKey(PAYER_KEY, validSig)),
                    tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT).fee(ONE_HUNDRED_HBARS),
                    tokenFreeze(TOKEN, ACCOUNT)
                            .payingWith(PAYER)
                            .sigControl(forKey(PAYER_KEY, validSig))
                            .signedBy(PAYER, FREEZE_KEY)
                            .fee(ONE_HUNDRED_HBARS)
                            .via("freezeTxn"),
                    validateChargedUsdWithin(
                            "freezeTxn",
                            expectedTokenFreezeFullFeeUsd(3L), // 3 sigs (2 payer + 1 freeze key)
                            50.0));
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
                            .fee(ONE_HUNDRED_HBARS)
                            .sigControl(forKey(FREEZE_KEY, validSig)),
                    tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT).fee(ONE_HUNDRED_HBARS),
                    tokenFreeze(TOKEN, ACCOUNT)
                            .payingWith(PAYER)
                            .signedBy(PAYER, FREEZE_KEY)
                            .sigControl(forKey(FREEZE_KEY, validSig))
                            .fee(ONE_HUNDRED_HBARS)
                            .via("freezeTxn"),
                    validateChargedUsdWithin(
                            "freezeTxn",
                            expectedTokenFreezeFullFeeUsd(3L), // 3 sigs (1 payer + 2 freeze key)
                            50.0));
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
                            .freezeDefault(true) // Token starts frozen
                            .treasury(TREASURY)
                            .payingWith(PAYER)
                            .fee(ONE_HUNDRED_HBARS),
                    tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT).fee(ONE_HUNDRED_HBARS),
                    // Account is already frozen by default
                    tokenUnfreeze(TOKEN, ACCOUNT)
                            .payingWith(PAYER)
                            .signedBy(PAYER, FREEZE_KEY)
                            .fee(ONE_HUNDRED_HBARS)
                            .via("unfreezeTxn"),
                    validateChargedUsdWithin(
                            "unfreezeTxn",
                            expectedTokenUnfreezeFullFeeUsd(2L), // 2 sigs
                            50.0));
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
                            .fee(ONE_HUNDRED_HBARS)
                            .sigControl(forKey(PAYER_KEY, validSig)),
                    tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT).fee(ONE_HUNDRED_HBARS),
                    tokenFreeze(TOKEN, ACCOUNT)
                            .payingWith(PAYER)
                            .sigControl(forKey(PAYER_KEY, validSig))
                            .signedBy(PAYER, FREEZE_KEY)
                            .fee(ONE_HUNDRED_HBARS),
                    tokenUnfreeze(TOKEN, ACCOUNT)
                            .payingWith(PAYER)
                            .sigControl(forKey(PAYER_KEY, validSig))
                            .signedBy(PAYER, FREEZE_KEY)
                            .fee(ONE_HUNDRED_HBARS)
                            .via("unfreezeTxn"),
                    validateChargedUsdWithin(
                            "unfreezeTxn",
                            expectedTokenUnfreezeFullFeeUsd(3L), // 3 sigs (2 payer + 1 freeze key)
                            50.0));
        }
    }

    @Nested
    @DisplayName("TokenFreeze Simple Fees Negative Test Cases")
    class TokenFreezeNegativeTestCases {

        @Nested
        @DisplayName("TokenFreeze Failures on Ingest")
        class TokenFreezeFailuresOnIngest {

            @HapiTest
            @DisplayName("TokenFreeze - missing freeze key signature fails at handle")
            final Stream<DynamicTest> tokenFreezeMissingFreezeKeySignatureFailsAtHandle() {
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
                                .payingWith(PAYER)
                                .fee(ONE_HUNDRED_HBARS),
                        tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT).fee(ONE_HUNDRED_HBARS),
                        tokenFreeze(TOKEN, ACCOUNT)
                                .payingWith(PAYER)
                                .signedBy(PAYER) // Missing freeze key signature
                                .fee(ONE_HUNDRED_HBARS)
                                .hasKnownStatus(INVALID_SIGNATURE));
            }

            @HapiTest
            @DisplayName("TokenFreeze - insufficient tx fee fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenFreezeInsufficientTxFeeFailsOnIngest() {
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();

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
                                .payingWith(PAYER)
                                .fee(ONE_HUNDRED_HBARS),
                        tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT).fee(ONE_HUNDRED_HBARS),
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                        tokenFreeze(TOKEN, ACCOUNT)
                                .payingWith(PAYER)
                                .signedBy(PAYER, FREEZE_KEY)
                                .fee(1L) // Fee too low
                                .via("freezeTxn")
                                .hasPrecheck(INSUFFICIENT_TX_FEE),
                        getTxnRecord("freezeTxn").hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND),
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        withOpContext((spec, log) -> {
                            assertEquals(initialBalance.get(), afterBalance.get());
                        }));
            }

            @HapiTest
            @DisplayName("TokenFreeze - no freeze key fails")
            final Stream<DynamicTest> tokenFreezeNoFreezeKeyFails() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                // No freeze key
                                .treasury(TREASURY)
                                .payingWith(PAYER)
                                .fee(ONE_HUNDRED_HBARS),
                        tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT).fee(ONE_HUNDRED_HBARS),
                        tokenFreeze(TOKEN, ACCOUNT)
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .fee(ONE_HUNDRED_HBARS)
                                .via("freezeTxn")
                                .hasKnownStatus(TOKEN_HAS_NO_FREEZE_KEY));
            }

            @HapiTest
            @DisplayName("TokenFreeze - token not associated fails")
            final Stream<DynamicTest> tokenFreezeNotAssociatedFails() {
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
                                .payingWith(PAYER)
                                .fee(ONE_HUNDRED_HBARS),
                        // Not associating the token
                        tokenFreeze(TOKEN, ACCOUNT)
                                .payingWith(PAYER)
                                .signedBy(PAYER, FREEZE_KEY)
                                .fee(ONE_HUNDRED_HBARS)
                                .via("freezeTxn")
                                .hasKnownStatus(TOKEN_NOT_ASSOCIATED_TO_ACCOUNT));
            }

            @HapiTest
            @DisplayName("TokenFreeze - already frozen fails")
            final Stream<DynamicTest> tokenFreezeAlreadyFrozenFails() {
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
                                .payingWith(PAYER)
                                .fee(ONE_HUNDRED_HBARS),
                        tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT).fee(ONE_HUNDRED_HBARS),
                        tokenFreeze(TOKEN, ACCOUNT)
                                .payingWith(PAYER)
                                .signedBy(PAYER, FREEZE_KEY)
                                .fee(ONE_HUNDRED_HBARS)
                                .via("freezeTxn")
                                .hasKnownStatus(ACCOUNT_FROZEN_FOR_TOKEN));
            }
        }

        @Nested
        @DisplayName("TokenFreeze Failures on Pre-Handle")
        class TokenFreezeFailuresOnPreHandle {

            @LeakyEmbeddedHapiTest(reason = MUST_SKIP_INGEST)
            @DisplayName("TokenFreeze - invalid payer signature fails on pre-handle - network fee only")
            final Stream<DynamicTest> tokenFreezeInvalidPayerSigFailsOnPreHandle() {
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();
                final AtomicLong initialNodeBalance = new AtomicLong();
                final AtomicLong afterNodeBalance = new AtomicLong();

                final String INNER_ID = "freeze-txn-inner-id";

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
                                .treasury(TREASURY)
                                .fee(ONE_HUNDRED_HBARS),
                        tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT).fee(ONE_HUNDRED_HBARS),
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                        cryptoTransfer(movingHbar(ONE_HBAR).between(DEFAULT_PAYER, "0.0.4"))
                                .fee(ONE_HUNDRED_HBARS),
                        getAccountBalance("0.0.4").exposingBalanceTo(initialNodeBalance::set),
                        tokenFreeze(TOKEN, ACCOUNT)
                                .payingWith(PAYER)
                                .sigControl(forKey(PAYER_KEY, invalidSig))
                                .signedBy(PAYER, FREEZE_KEY)
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
                                expectedTokenFreezeNetworkFeeOnlyUsd(2L),
                                1.0));
            }
        }
    }
}
