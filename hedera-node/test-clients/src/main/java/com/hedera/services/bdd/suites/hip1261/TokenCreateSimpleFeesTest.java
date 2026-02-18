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
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedHbarFee;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingHbar;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsdWithin;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTokenCreateFungibleFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTokenCreateFungibleWithCustomFeesFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTokenCreateNetworkFeeOnlyUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTokenCreateNftFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTokenCreateNftWithCustomFeesFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.validateChargedFeeToUsd;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_PAYER_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_TX_FEE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_PAYER_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.MISSING_TOKEN_NAME;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.MISSING_TOKEN_SYMBOL;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.RECORD_NOT_FOUND;
import static com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;
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
 * Tests for TokenCreate simple fees with extras.
 * Validates that fees are correctly calculated based on:
 * - Number of signatures (extras beyond included)
 * - Number of keys (admin, supply, freeze, kyc, wipe, pause, fee schedule, metadata)
 * - Presence of custom fees
 * - Token type (fungible vs NFT)
 */
@Tag(SIMPLE_FEES)
@HapiTestLifecycle
public class TokenCreateSimpleFeesTest {

    private static final String PAYER = "payer";
    private static final String TREASURY = "treasury";
    private static final String ADMIN_KEY = "adminKey";
    private static final String SUPPLY_KEY = "supplyKey";
    private static final String FREEZE_KEY = "freezeKey";
    private static final String KYC_KEY = "kycKey";
    private static final String WIPE_KEY = "wipeKey";
    private static final String PAUSE_KEY = "pauseKey";
    private static final String FEE_SCHEDULE_KEY = "feeScheduleKey";
    private static final String PAYER_KEY = "payerKey";
    private static final String HBAR_COLLECTOR = "hbarCollector";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(Map.of("fees.simpleFeesEnabled", "true"));
    }

    @Nested
    @DisplayName("TokenCreate Simple Fees Positive Test Cases")
    class TokenCreateSimpleFeesPositiveTestCases {

        @HapiTest
        @DisplayName("TokenCreate fungible - base fees without extras")
        final Stream<DynamicTest> tokenCreateFungibleBaseFee() {
            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(TREASURY).balance(0L),
                    tokenCreate("fungibleToken")
                            .tokenType(FUNGIBLE_COMMON)
                            .treasury(TREASURY)
                            .payingWith(PAYER)
                            .signedBy(PAYER, TREASURY)
                            .fee(ONE_HUNDRED_HBARS)
                            .via("tokenCreateTxn"),
                    validateChargedUsdWithin("tokenCreateTxn", expectedTokenCreateFungibleFullFeeUsd(2L, 0L), 0.001));
        }

        @HapiTest
        @DisplayName("TokenCreate NFT - base fees without extras")
        final Stream<DynamicTest> tokenCreateNftBaseFee() {
            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(TREASURY).balance(0L),
                    newKeyNamed(SUPPLY_KEY),
                    tokenCreate("nftToken")
                            .tokenType(NON_FUNGIBLE_UNIQUE)
                            .initialSupply(0L)
                            .supplyKey(SUPPLY_KEY)
                            .treasury(TREASURY)
                            .payingWith(PAYER)
                            .signedBy(PAYER, TREASURY)
                            .fee(ONE_HUNDRED_HBARS)
                            .via("tokenCreateTxn"),
                    validateChargedUsdWithin(
                            "tokenCreateTxn",
                            expectedTokenCreateNftFullFeeUsd(2L, 1L), // 1 key = supply key
                            0.001));
        }

        @HapiTest
        @DisplayName("TokenCreate fungible with admin key - one extra key")
        final Stream<DynamicTest> tokenCreateFungibleWithAdminKey() {
            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(TREASURY).balance(0L),
                    newKeyNamed(ADMIN_KEY),
                    tokenCreate("fungibleToken")
                            .tokenType(FUNGIBLE_COMMON)
                            .adminKey(ADMIN_KEY)
                            .treasury(TREASURY)
                            .payingWith(PAYER)
                            .signedBy(PAYER, TREASURY, ADMIN_KEY)
                            .fee(ONE_HUNDRED_HBARS)
                            .via("tokenCreateTxn"),
                    validateChargedUsdWithin(
                            "tokenCreateTxn",
                            expectedTokenCreateFungibleFullFeeUsd(3L, 1L), // 3 sigs: payer + treasury + admin key
                            0.001));
        }

        @HapiTest
        @DisplayName("TokenCreate fungible with all keys - full charging with extras")
        final Stream<DynamicTest> tokenCreateFungibleWithAllKeys() {
            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(TREASURY).balance(0L),
                    newKeyNamed(ADMIN_KEY),
                    newKeyNamed(SUPPLY_KEY),
                    newKeyNamed(FREEZE_KEY),
                    newKeyNamed(KYC_KEY),
                    newKeyNamed(WIPE_KEY),
                    newKeyNamed(PAUSE_KEY),
                    newKeyNamed(FEE_SCHEDULE_KEY),
                    tokenCreate("fungibleToken")
                            .tokenType(FUNGIBLE_COMMON)
                            .adminKey(ADMIN_KEY)
                            .supplyKey(SUPPLY_KEY)
                            .freezeKey(FREEZE_KEY)
                            .kycKey(KYC_KEY)
                            .wipeKey(WIPE_KEY)
                            .pauseKey(PAUSE_KEY)
                            .feeScheduleKey(FEE_SCHEDULE_KEY)
                            .treasury(TREASURY)
                            .payingWith(PAYER)
                            .signedBy(PAYER, TREASURY, ADMIN_KEY)
                            .fee(ONE_HUNDRED_HBARS)
                            .via("tokenCreateTxn"),
                    validateChargedUsdWithin(
                            "tokenCreateTxn",
                            expectedTokenCreateFungibleFullFeeUsd(
                                    3L, 7L), // 3 sigs: payer + treasury + admin key, 7 keys
                            0.001));
        }

        @HapiTest
        @DisplayName("TokenCreate NFT with all keys - full charging with extras")
        final Stream<DynamicTest> tokenCreateNftWithAllKeys() {
            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(TREASURY).balance(0L),
                    newKeyNamed(ADMIN_KEY),
                    newKeyNamed(SUPPLY_KEY),
                    newKeyNamed(FREEZE_KEY),
                    newKeyNamed(KYC_KEY),
                    newKeyNamed(WIPE_KEY),
                    newKeyNamed(PAUSE_KEY),
                    newKeyNamed(FEE_SCHEDULE_KEY),
                    tokenCreate("nftToken")
                            .tokenType(NON_FUNGIBLE_UNIQUE)
                            .initialSupply(0L)
                            .adminKey(ADMIN_KEY)
                            .supplyKey(SUPPLY_KEY)
                            .freezeKey(FREEZE_KEY)
                            .kycKey(KYC_KEY)
                            .wipeKey(WIPE_KEY)
                            .pauseKey(PAUSE_KEY)
                            .feeScheduleKey(FEE_SCHEDULE_KEY)
                            .treasury(TREASURY)
                            .payingWith(PAYER)
                            .signedBy(PAYER, TREASURY, ADMIN_KEY)
                            .fee(ONE_HUNDRED_HBARS)
                            .via("tokenCreateTxn"),
                    validateChargedUsdWithin(
                            "tokenCreateTxn",
                            expectedTokenCreateNftFullFeeUsd(3L, 7L), // 3 sigs: payer + treasury + admin key, 7 keys
                            0.001));
        }

        @HapiTest
        @DisplayName("TokenCreate fungible with custom fee - full charging with extras")
        final Stream<DynamicTest> tokenCreateFungibleWithCustomFee() {
            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(TREASURY).balance(0L),
                    cryptoCreate(HBAR_COLLECTOR).balance(0L),
                    tokenCreate("fungibleToken")
                            .tokenType(FUNGIBLE_COMMON)
                            .treasury(TREASURY)
                            .withCustom(fixedHbarFee(100L, HBAR_COLLECTOR))
                            .payingWith(PAYER)
                            .signedBy(PAYER, TREASURY)
                            .fee(ONE_HUNDRED_HBARS)
                            .via("tokenCreateTxn"),
                    validateChargedUsdWithin(
                            "tokenCreateTxn", expectedTokenCreateFungibleWithCustomFeesFullFeeUsd(2L, 0L), 0.001));
        }

        @HapiTest
        @DisplayName("TokenCreate NFT with custom fee - full charging with extras")
        final Stream<DynamicTest> tokenCreateNftWithCustomFee() {
            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(TREASURY).balance(0L),
                    cryptoCreate(HBAR_COLLECTOR).balance(0L),
                    newKeyNamed(SUPPLY_KEY),
                    tokenCreate("nftToken")
                            .tokenType(NON_FUNGIBLE_UNIQUE)
                            .initialSupply(0L)
                            .supplyKey(SUPPLY_KEY)
                            .treasury(TREASURY)
                            .withCustom(fixedHbarFee(100L, HBAR_COLLECTOR))
                            .payingWith(PAYER)
                            .signedBy(PAYER, TREASURY)
                            .fee(ONE_HUNDRED_HBARS)
                            .via("tokenCreateTxn"),
                    validateChargedUsdWithin(
                            "tokenCreateTxn",
                            expectedTokenCreateNftWithCustomFeesFullFeeUsd(2L, 1L), // 1 key = supply key
                            0.001));
        }

        @HapiTest
        @DisplayName("TokenCreate with threshold key - extra signatures")
        final Stream<DynamicTest> tokenCreateWithThresholdKey() {
            // Define a threshold key that requires 2 of 2 signatures
            KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
            SigControl validSig = keyShape.signedWith(sigs(ON, ON));

            return hapiTest(
                    newKeyNamed(PAYER_KEY).shape(keyShape),
                    cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(TREASURY).balance(0L),
                    tokenCreate("fungibleToken")
                            .tokenType(FUNGIBLE_COMMON)
                            .treasury(TREASURY)
                            .payingWith(PAYER)
                            .sigControl(forKey(PAYER_KEY, validSig))
                            .signedBy(PAYER, TREASURY)
                            .fee(ONE_HUNDRED_HBARS)
                            .via("tokenCreateTxn"),
                    validateChargedUsdWithin(
                            "tokenCreateTxn",
                            expectedTokenCreateFungibleFullFeeUsd(3L, 0L), // 3 sigs: 2 payer + 1 treasury
                            0.001));
        }

        @HapiTest
        @DisplayName("TokenCreate with keys and custom fee - combined extras")
        final Stream<DynamicTest> tokenCreateWithKeysAndCustomFee() {
            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(TREASURY).balance(0L),
                    cryptoCreate(HBAR_COLLECTOR).balance(0L),
                    newKeyNamed(ADMIN_KEY),
                    newKeyNamed(SUPPLY_KEY),
                    newKeyNamed(FREEZE_KEY),
                    tokenCreate("fungibleToken")
                            .tokenType(FUNGIBLE_COMMON)
                            .adminKey(ADMIN_KEY)
                            .supplyKey(SUPPLY_KEY)
                            .freezeKey(FREEZE_KEY)
                            .treasury(TREASURY)
                            .withCustom(fixedHbarFee(100L, HBAR_COLLECTOR))
                            .payingWith(PAYER)
                            .signedBy(PAYER, TREASURY, ADMIN_KEY)
                            .fee(ONE_HUNDRED_HBARS)
                            .via("tokenCreateTxn"),
                    validateChargedUsdWithin(
                            "tokenCreateTxn",
                            expectedTokenCreateFungibleWithCustomFeesFullFeeUsd(
                                    3L, 3L), // 3 sigs: payer + treasury + admin key, 3 keys
                            0.001));
        }
    }

    @Nested
    @DisplayName("TokenCreate Simple Fees Negative Test Cases")
    class TokenCreateSimpleFeesNegativeTestCases {

        @Nested
        @DisplayName("TokenCreate Failures on Handle")
        class TokenCreateFailuresOnHandle {

            @HapiTest
            @DisplayName("TokenCreate - missing treasury signature fails at handle - full fee charged")
            final Stream<DynamicTest> tokenCreateMissingTreasurySignatureFailsAtHandle() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        tokenCreate("fungibleToken")
                                .tokenType(FUNGIBLE_COMMON)
                                .treasury(TREASURY)
                                .payingWith(PAYER)
                                .signedBy(PAYER) // Missing treasury signature
                                .fee(ONE_HUNDRED_HBARS)
                                .via("tokenCreateTxn")
                                .hasKnownStatus(INVALID_SIGNATURE),
                        validateChargedUsdWithin(
                                "tokenCreateTxn",
                                expectedTokenCreateFungibleFullFeeUsd(1L, 0L), // 1 sig (payer only)
                                0.001));
            }
        }

        @Nested
        @DisplayName("TokenCreate Failures on Ingest and Handle")
        class TokenCreateFailuresOnIngest {

            @HapiTest
            @DisplayName("TokenCreate - insufficient tx fee fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenCreateInsufficientTxFeeFailsOnIngest() {
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();

                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                        tokenCreate("fungibleToken")
                                .tokenType(FUNGIBLE_COMMON)
                                .treasury(TREASURY)
                                .payingWith(PAYER)
                                .signedBy(PAYER, TREASURY)
                                .fee(1L) // Fee too low
                                .via("tokenCreateTxn")
                                .hasPrecheck(INSUFFICIENT_TX_FEE),
                        getTxnRecord("tokenCreateTxn").hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND),
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        withOpContext((spec, log) -> {
                            assertEquals(initialBalance.get(), afterBalance.get());
                        }));
            }

            @HapiTest
            @DisplayName("TokenCreate - insufficient payer balance fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenCreateInsufficientPayerBalanceFailsOnIngest() {
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();

                return hapiTest(
                        cryptoCreate(PAYER).balance(100L), // Very low balance
                        cryptoCreate(TREASURY).balance(0L),
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                        tokenCreate("fungibleToken")
                                .tokenType(FUNGIBLE_COMMON)
                                .treasury(TREASURY)
                                .payingWith(PAYER)
                                .signedBy(PAYER, TREASURY)
                                .fee(ONE_HUNDRED_HBARS)
                                .via("tokenCreateTxn")
                                .hasPrecheck(INSUFFICIENT_PAYER_BALANCE),
                        getTxnRecord("tokenCreateTxn").hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND),
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        withOpContext((spec, log) -> {
                            assertEquals(initialBalance.get(), afterBalance.get());
                        }));
            }

            @HapiTest
            @DisplayName("TokenCreate - missing token name fails at handle - full fee charged")
            final Stream<DynamicTest> tokenCreateMissingNameFailsAtHandle() {
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();

                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                        tokenCreate("fungibleToken")
                                .name("")
                                .tokenType(FUNGIBLE_COMMON)
                                .treasury(TREASURY)
                                .payingWith(PAYER)
                                .signedBy(PAYER, TREASURY)
                                .fee(ONE_HUNDRED_HBARS)
                                .via("tokenCreateTxn")
                                .hasKnownStatus(MISSING_TOKEN_NAME),
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        withOpContext((spec, log) -> {
                            assertTrue(initialBalance.get() > afterBalance.get());
                        }),
                        validateChargedFeeToUsd(
                                "tokenCreateTxn",
                                initialBalance,
                                afterBalance,
                                expectedTokenCreateFungibleFullFeeUsd(2L, 0L),
                                0.001));
            }

            @HapiTest
            @DisplayName("TokenCreate - missing token symbol fails at handle - full fee charged")
            final Stream<DynamicTest> tokenCreateMissingSymbolFailsAtHandle() {
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();

                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                        tokenCreate("fungibleToken")
                                .symbol("")
                                .tokenType(FUNGIBLE_COMMON)
                                .treasury(TREASURY)
                                .payingWith(PAYER)
                                .signedBy(PAYER, TREASURY)
                                .fee(ONE_HUNDRED_HBARS)
                                .via("tokenCreateTxn")
                                .hasKnownStatus(MISSING_TOKEN_SYMBOL),
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        withOpContext((spec, log) -> {
                            assertTrue(initialBalance.get() > afterBalance.get());
                        }),
                        validateChargedFeeToUsd(
                                "tokenCreateTxn",
                                initialBalance,
                                afterBalance,
                                expectedTokenCreateFungibleFullFeeUsd(2L, 0L),
                                0.001));
            }

            @HapiTest
            @DisplayName("TokenCreate - threshold key with invalid signature fails on ingest")
            final Stream<DynamicTest> tokenCreateThresholdInvalidSignatureFailsOnIngest() {
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();

                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
                SigControl invalidSig = keyShape.signedWith(sigs(ON, OFF)); // Only 1 of 2 required

                return hapiTest(
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                        tokenCreate("fungibleToken")
                                .tokenType(FUNGIBLE_COMMON)
                                .treasury(TREASURY)
                                .payingWith(PAYER)
                                .sigControl(forKey(PAYER_KEY, invalidSig))
                                .signedBy(PAYER, TREASURY)
                                .fee(ONE_HUNDRED_HBARS)
                                .via("tokenCreateTxn")
                                .hasPrecheck(INVALID_SIGNATURE),
                        getTxnRecord("tokenCreateTxn").hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND),
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        withOpContext((spec, log) -> {
                            assertEquals(initialBalance.get(), afterBalance.get());
                        }));
            }
        }

        @Nested
        @DisplayName("TokenCreate Failures on Pre-Handle")
        class TokenCreateFailuresOnPreHandle {

            @LeakyEmbeddedHapiTest(reason = MUST_SKIP_INGEST)
            @DisplayName("TokenCreate - invalid payer signature fails on pre-handle - network fee only")
            final Stream<DynamicTest> tokenCreateInvalidPayerSigFailsOnPreHandle() {
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();
                final AtomicLong initialNodeBalance = new AtomicLong();
                final AtomicLong afterNodeBalance = new AtomicLong();

                final String INNER_ID = "token-create-txn-inner-id";

                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
                SigControl invalidSig = keyShape.signedWith(sigs(ON, OFF));

                return hapiTest(
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                        cryptoTransfer(movingHbar(ONE_HBAR).between(DEFAULT_PAYER, "0.0.4"))
                                .fee(ONE_HUNDRED_HBARS),
                        getAccountBalance("0.0.4").exposingBalanceTo(initialNodeBalance::set),
                        tokenCreate("fungibleToken")
                                .tokenType(FUNGIBLE_COMMON)
                                .treasury(TREASURY)
                                .payingWith(PAYER)
                                .sigControl(forKey(PAYER_KEY, invalidSig))
                                .signedBy(PAYER, TREASURY)
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
                                expectedTokenCreateNetworkFeeOnlyUsd(2L),
                                0.001));
            }

            @LeakyEmbeddedHapiTest(reason = MUST_SKIP_INGEST)
            @DisplayName("TokenCreate - invalid treasury fails on handle - full fee charged")
            final Stream<DynamicTest> tokenCreateInvalidTreasuryFailsOnPreHandle() {
                final String INNER_ID = "token-create-txn-inner-id";

                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        tokenCreate("fungibleToken")
                                .tokenType(FUNGIBLE_COMMON)
                                .treasury("0.0.99999999") // Invalid treasury
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .fee(ONE_HUNDRED_HBARS)
                                .setNode("0.0.4")
                                .via(INNER_ID)
                                .hasKnownStatus(INVALID_ACCOUNT_ID),
                        getTxnRecord(INNER_ID).assertingNothingAboutHashes().logged(),
                        validateChargedUsdWithin(
                                INNER_ID,
                                expectedTokenCreateFungibleFullFeeUsd(1L, 0L), // 1 sig (payer only), 0 keys
                                0.001));
            }
        }
    }
}
