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
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedAccount;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsd;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsdWithin;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTokenFreezeFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTokenUnfreezeFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.NETWORK_BASE_FEE;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.NETWORK_MULTIPLIER;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.SIGNATURE_FEE_AFTER_MULTIPLIER;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.SIGNATURE_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.TOKEN_FREEZE_FEE;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.TOKEN_UNFREEZE_FEE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_FROZEN_FOR_TOKEN;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_TX_FEE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_PAYER_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.RECORD_NOT_FOUND;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_HAS_NO_FREEZE_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_NOT_ASSOCIATED_TO_ACCOUNT;
import static com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyEmbeddedHapiTest;
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
                            0.001));
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
                            0.001));
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
                            0.001));
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
                            0.001));
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
                            0.001));
        }
    }

    @Nested
    @DisplayName("TokenFreeze Simple Fees Negative Test Cases")
    class TokenFreezeNegativeTestCases {

        @Nested
        @DisplayName("TokenFreeze Failures on Ingest and Handle")
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
                                .via("freezeTxn")
                                .hasKnownStatus(INVALID_SIGNATURE),
                        validateChargedUsd("freezeTxn", TOKEN_FREEZE_FEE),
                        validateChargedAccount("freezeTxn", PAYER));
            }

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
                                .payingWith(PAYER)
                                .fee(ONE_HUNDRED_HBARS),
                        tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT).fee(ONE_HUNDRED_HBARS),
                        tokenFreeze(TOKEN, ACCOUNT)
                                .payingWith(PAYER)
                                .signedBy(PAYER, FREEZE_KEY)
                                .fee(1L) // Fee too low
                                .via("freezeTxn")
                                .hasPrecheck(INSUFFICIENT_TX_FEE),
                        // If there is no record no account is charged
                        getTxnRecord("freezeTxn").hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
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
                                .hasKnownStatus(TOKEN_HAS_NO_FREEZE_KEY),
                        validateChargedUsd("freezeTxn", TOKEN_FREEZE_FEE),
                        validateChargedAccount("freezeTxn", PAYER));
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
                                .hasKnownStatus(TOKEN_NOT_ASSOCIATED_TO_ACCOUNT),
                        validateChargedUsd("freezeTxn", TOKEN_FREEZE_FEE + SIGNATURE_FEE_AFTER_MULTIPLIER),
                        validateChargedAccount("freezeTxn", PAYER));
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
                                .hasKnownStatus(ACCOUNT_FROZEN_FOR_TOKEN),
                        validateChargedUsd("freezeTxn", TOKEN_FREEZE_FEE + SIGNATURE_FEE_AFTER_MULTIPLIER),
                        validateChargedAccount("freezeTxn", PAYER));
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
                                .treasury(TREASURY)
                                .fee(ONE_HUNDRED_HBARS),
                        tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT).fee(ONE_HUNDRED_HBARS),
                        cryptoTransfer(movingHbar(ONE_HBAR).between(DEFAULT_PAYER, "0.0.4"))
                                .fee(ONE_HUNDRED_HBARS),
                        tokenFreeze(TOKEN, ACCOUNT)
                                .payingWith(PAYER)
                                .sigControl(forKey(PAYER_KEY, invalidSig))
                                .signedBy(PAYER, FREEZE_KEY)
                                .fee(ONE_HUNDRED_HBARS)
                                .setNode("0.0.4")
                                .via("freezeTxn")
                                .hasKnownStatus(INVALID_PAYER_SIGNATURE),
                        // we should charge just the network fee for due-diligence failure
                        validateChargedUsd("freezeTxn", NETWORK_BASE_FEE + SIGNATURE_FEE_USD * NETWORK_MULTIPLIER),
                        // assert that the node is charged for due-diligence failure
                        validateChargedAccount("freezeTxn", "0.0.4"));
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
            @DisplayName("TokenUnfreeze - missing freeze key signature fails at handle")
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
                                .payingWith(PAYER)
                                .fee(ONE_HUNDRED_HBARS),
                        tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT).fee(ONE_HUNDRED_HBARS),
                        tokenUnfreeze(TOKEN, ACCOUNT)
                                .payingWith(PAYER)
                                .signedBy(PAYER) // Missing freeze key signature
                                .fee(ONE_HUNDRED_HBARS)
                                .via("unfreezeTxn")
                                .hasKnownStatus(INVALID_SIGNATURE),
                        validateChargedUsd("unfreezeTxn", TOKEN_UNFREEZE_FEE, 0.1),
                        validateChargedAccount("unfreezeTxn", PAYER));
            }

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
                                .payingWith(PAYER)
                                .fee(ONE_HUNDRED_HBARS),
                        tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT).fee(ONE_HUNDRED_HBARS),
                        tokenUnfreeze(TOKEN, ACCOUNT)
                                .payingWith(PAYER)
                                .signedBy(PAYER, FREEZE_KEY)
                                .fee(1L) // Fee too low
                                .via("unfreezeTxn")
                                .hasPrecheck(INSUFFICIENT_TX_FEE),
                        getTxnRecord("unfreezeTxn").hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TokenUnfreeze - no freeze key fails")
            final Stream<DynamicTest> tokenUnfreezeNoFreezeKeyFails() {
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
                        tokenUnfreeze(TOKEN, ACCOUNT)
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .fee(ONE_HUNDRED_HBARS)
                                .via("unfreezeTxn")
                                .hasKnownStatus(TOKEN_HAS_NO_FREEZE_KEY),
                        validateChargedUsd("unfreezeTxn", TOKEN_UNFREEZE_FEE),
                        validateChargedAccount("unfreezeTxn", PAYER));
            }

            @HapiTest
            @DisplayName("TokenUnfreeze - token not associated fails")
            final Stream<DynamicTest> tokenUnfreezeNotAssociatedFails() {
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
                                .payingWith(PAYER)
                                .fee(ONE_HUNDRED_HBARS),
                        // Not associating the token
                        tokenUnfreeze(TOKEN, ACCOUNT)
                                .payingWith(PAYER)
                                .signedBy(PAYER, FREEZE_KEY)
                                .fee(ONE_HUNDRED_HBARS)
                                .via("unfreezeTxn")
                                .hasKnownStatus(TOKEN_NOT_ASSOCIATED_TO_ACCOUNT),
                        validateChargedUsd("unfreezeTxn", TOKEN_UNFREEZE_FEE + SIGNATURE_FEE_AFTER_MULTIPLIER),
                        validateChargedAccount("unfreezeTxn", PAYER));
            }
        }
    }
}
