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
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedHbarFee;
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
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.allOnSigControl;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedNetworkOnlyFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTokenCreateFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTokenCreateFungibleWithCustomFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTokenCreateNftFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTokenCreateNftWithCustomFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.signedTxnSizeFor;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.thresholdKeyWithPrimitives;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.validateChargedUsdWithinWithTxnSize;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.NODE_INCLUDED_BYTES;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.DUPLICATE_TRANSACTION;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_PAYER_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_TX_FEE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_PAYER_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TRANSACTION_DURATION;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TRANSACTION_START;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.MEMO_TOO_LONG;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.MISSING_TOKEN_NAME;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.MISSING_TOKEN_SYMBOL;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.RECORD_NOT_FOUND;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TRANSACTION_EXPIRED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TRANSACTION_OVERSIZE;
import static com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;
import static org.hiero.hapi.support.fees.Extra.KEYS;
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
    private static final String tokenCreateTxn = "tokenCreateTxn";

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
                            .via(tokenCreateTxn),
                    validateChargedUsdWithinWithTxnSize(
                            tokenCreateTxn,
                            txnSize -> expectedTokenCreateFullFeeUsd(Map.of(
                                    SIGNATURES, 2L,
                                    KEYS, 0L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(tokenCreateTxn, PAYER));
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
                            .via(tokenCreateTxn),
                    validateChargedUsdWithinWithTxnSize(
                            tokenCreateTxn,
                            txnSize -> expectedTokenCreateNftFullFeeUsd(Map.of(
                                    SIGNATURES, 2L,
                                    KEYS, 1L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(tokenCreateTxn, PAYER));
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
                            .via(tokenCreateTxn),
                    validateChargedUsdWithinWithTxnSize(
                            tokenCreateTxn,
                            txnSize -> expectedTokenCreateFullFeeUsd(Map.of(
                                    SIGNATURES, 3L,
                                    KEYS, 1L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(tokenCreateTxn, PAYER));
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
                            .via(tokenCreateTxn),
                    validateChargedUsdWithinWithTxnSize(
                            tokenCreateTxn,
                            txnSize -> expectedTokenCreateFungibleWithCustomFullFeeUsd(Map.of(
                                    SIGNATURES, 3L,
                                    KEYS, 7L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(tokenCreateTxn, PAYER));
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
                            .via(tokenCreateTxn),
                    validateChargedUsdWithinWithTxnSize(
                            tokenCreateTxn,
                            txnSize -> expectedTokenCreateNftWithCustomFullFeeUsd(Map.of(
                                    SIGNATURES, 3L,
                                    KEYS, 7L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(tokenCreateTxn, PAYER));
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
                            .via(tokenCreateTxn),
                    validateChargedUsdWithinWithTxnSize(
                            tokenCreateTxn,
                            txnSize -> expectedTokenCreateFungibleWithCustomFullFeeUsd(Map.of(
                                    SIGNATURES, 2L,
                                    KEYS, 0L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(tokenCreateTxn, PAYER));
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
                            .via(tokenCreateTxn),
                    validateChargedUsdWithinWithTxnSize(
                            tokenCreateTxn,
                            txnSize -> expectedTokenCreateNftWithCustomFullFeeUsd(Map.of(
                                    SIGNATURES, 2L,
                                    KEYS, 1L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(tokenCreateTxn, PAYER));
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
                            .via(tokenCreateTxn),
                    validateChargedUsdWithinWithTxnSize(
                            tokenCreateTxn,
                            txnSize -> expectedTokenCreateFullFeeUsd(Map.of(
                                    SIGNATURES, 3L,
                                    KEYS, 0L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(tokenCreateTxn, PAYER));
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
                            .via(tokenCreateTxn),
                    validateChargedUsdWithinWithTxnSize(
                            tokenCreateTxn,
                            txnSize -> expectedTokenCreateFungibleWithCustomFullFeeUsd(Map.of(
                                    SIGNATURES, 3L,
                                    KEYS, 3L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(tokenCreateTxn, PAYER));
        }

        @HapiTest
        @DisplayName("TokenCreate - large key txn above NODE_INCLUDED_BYTES threshold - extra PROCESSING_BYTES charged")
        final Stream<DynamicTest> tokenCreateLargeKeyExtraProcessingBytesFee() {
            return hapiTest(
                    newKeyNamed(PAYER_KEY).shape(thresholdKeyWithPrimitives(20)),
                    cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(TREASURY).balance(0L),
                    tokenCreate("fungibleToken")
                            .sigControl(forKey(PAYER_KEY, allOnSigControl(20)))
                            .tokenType(FUNGIBLE_COMMON)
                            .treasury(TREASURY)
                            .payingWith(PAYER)
                            .signedBy(PAYER, TREASURY)
                            .via(tokenCreateTxn),
                    assertionsHold((spec, log) -> {
                        final int txnSize = signedTxnSizeFor(spec, tokenCreateTxn);
                        log.info(
                                "Large-key TokenCreate signed size: {} bytes (threshold: {})",
                                txnSize,
                                NODE_INCLUDED_BYTES);
                        assertTrue(
                                txnSize > NODE_INCLUDED_BYTES,
                                "Expected txn size (" + txnSize + ") to exceed " + NODE_INCLUDED_BYTES + " bytes");
                    }),
                    validateChargedUsdWithinWithTxnSize(
                            tokenCreateTxn,
                            txnSize -> expectedTokenCreateFullFeeUsd(
                                    Map.of(SIGNATURES, 21L, PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(tokenCreateTxn, PAYER));
        }

        @HapiTest
        @DisplayName("TokenCreate - very large txn (just below 6KB) - full charging with extra PROCESSING_BYTES")
        final Stream<DynamicTest> tokenCreateVeryLargeKeyBelowOversizeFee() {
            return hapiTest(
                    newKeyNamed(PAYER_KEY).shape(thresholdKeyWithPrimitives(55)),
                    cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(TREASURY).balance(0L),
                    tokenCreate("fungibleToken")
                            .sigControl(forKey(PAYER_KEY, allOnSigControl(55)))
                            .tokenType(FUNGIBLE_COMMON)
                            .treasury(TREASURY)
                            .payingWith(PAYER)
                            .signedBy(PAYER, TREASURY)
                            .via(tokenCreateTxn),
                    assertionsHold((spec, log) -> {
                        final int txnSize = signedTxnSizeFor(spec, tokenCreateTxn);
                        log.info("Very-large TokenCreate signed size: {} bytes", txnSize);
                        assertTrue(txnSize < 6_000, "Expected txn size (" + txnSize + ") to not exceed 6000 bytes");
                    }),
                    validateChargedUsdWithinWithTxnSize(
                            tokenCreateTxn,
                            txnSize -> expectedTokenCreateFullFeeUsd(
                                    Map.of(SIGNATURES, 56L, PROCESSING_BYTES, (long) txnSize)),
                            0.1));
        }
    }

    @Nested
    @DisplayName("TokenCreate Simple Fees Negative Test Cases")
    class TokenCreateSimpleFeesNegativeTestCases {

        @Nested
        @DisplayName("TokenCreate Failures on Ingest and Handle")
        class TokenCreateFailuresOnIngest {

            @HapiTest
            @DisplayName("TokenCreate - insufficient tx fee fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenCreateInsufficientTxFeeFailsOnIngest() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        tokenCreate("fungibleToken")
                                .tokenType(FUNGIBLE_COMMON)
                                .treasury(TREASURY)
                                .payingWith(PAYER)
                                .signedBy(PAYER, TREASURY)
                                .fee(1L) // Fee too low
                                .via(tokenCreateTxn)
                                .hasPrecheck(INSUFFICIENT_TX_FEE),
                        getTxnRecord(tokenCreateTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TokenCreate - insufficient payer balance fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenCreateInsufficientPayerBalanceFailsOnIngest() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(100L), // Very low balance
                        cryptoCreate(TREASURY).balance(0L),
                        tokenCreate("fungibleToken")
                                .tokenType(FUNGIBLE_COMMON)
                                .treasury(TREASURY)
                                .payingWith(PAYER)
                                .signedBy(PAYER, TREASURY)
                                .via(tokenCreateTxn)
                                .hasPrecheck(INSUFFICIENT_PAYER_BALANCE),
                        getTxnRecord(tokenCreateTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TokenCreate - threshold key with invalid signature fails on ingest")
            final Stream<DynamicTest> tokenCreateThresholdInvalidSignatureFailsOnIngest() {
                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
                SigControl invalidSig = keyShape.signedWith(sigs(ON, OFF)); // Only 1 of 2 required

                return hapiTest(
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        tokenCreate("fungibleToken")
                                .tokenType(FUNGIBLE_COMMON)
                                .treasury(TREASURY)
                                .payingWith(PAYER)
                                .sigControl(forKey(PAYER_KEY, invalidSig))
                                .signedBy(PAYER, TREASURY)
                                .via(tokenCreateTxn)
                                .hasPrecheck(INVALID_SIGNATURE),
                        getTxnRecord(tokenCreateTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TokenCreate - key list with missing signature fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenCreateKeyListMissingSigFailsOnIngest() {
                return hapiTest(
                        newKeyNamed("firstKey"),
                        newKeyNamed("secondKey"),
                        newKeyListNamed(PAYER_KEY, List.of("firstKey", "secondKey")),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        tokenCreate("fungibleToken")
                                .tokenType(FUNGIBLE_COMMON)
                                .treasury(TREASURY)
                                .payingWith(PAYER)
                                .signedBy("firstKey", TREASURY) // missing secondKey
                                .via(tokenCreateTxn)
                                .hasPrecheck(INVALID_SIGNATURE),
                        getTxnRecord(tokenCreateTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName(
                    "TokenCreate - threshold key with nested list invalid signature fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenCreateThresholdWithListInvalidSigFailsOnIngest() {
                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE, listOf(2));
                SigControl invalidSig = keyShape.signedWith(sigs(ON, OFF, sigs(OFF, OFF)));

                return hapiTest(
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(PAYER)
                                .key(PAYER_KEY)
                                .sigControl(forKey(PAYER_KEY, keyShape.signedWith(sigs(ON, ON, sigs(ON, ON)))))
                                .balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        tokenCreate("fungibleToken")
                                .tokenType(FUNGIBLE_COMMON)
                                .treasury(TREASURY)
                                .payingWith(PAYER)
                                .sigControl(forKey(PAYER_KEY, invalidSig))
                                .signedBy(PAYER, TREASURY)
                                .via(tokenCreateTxn)
                                .hasPrecheck(INVALID_SIGNATURE),
                        getTxnRecord(tokenCreateTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TokenCreate - memo too long fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenCreateMemoTooLongFailsOnIngest() {
                final var LONG_MEMO = "x".repeat(1025);
                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
                SigControl validSig = keyShape.signedWith(sigs(ON, ON));

                return hapiTest(
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        tokenCreate("fungibleToken")
                                .tokenType(FUNGIBLE_COMMON)
                                .treasury(TREASURY)
                                .memo(LONG_MEMO)
                                .payingWith(PAYER)
                                .sigControl(forKey(PAYER_KEY, validSig))
                                .signedBy(PAYER, TREASURY)
                                .via(tokenCreateTxn)
                                .hasPrecheck(MEMO_TOO_LONG),
                        getTxnRecord(tokenCreateTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TokenCreate - expired transaction fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenCreateExpiredTransactionFailsOnIngest() {
                final var expiredTxnId = "expiredTokenCreate";
                final var oneHourPast = -3_600L;
                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
                SigControl validSig = keyShape.signedWith(sigs(ON, ON));

                return hapiTest(
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        usableTxnIdNamed(expiredTxnId)
                                .modifyValidStart(oneHourPast)
                                .payerId(PAYER),
                        tokenCreate("fungibleToken")
                                .tokenType(FUNGIBLE_COMMON)
                                .treasury(TREASURY)
                                .payingWith(PAYER)
                                .sigControl(forKey(PAYER_KEY, validSig))
                                .signedBy(PAYER, TREASURY)
                                .txnId(expiredTxnId)
                                .via(tokenCreateTxn)
                                .hasPrecheck(TRANSACTION_EXPIRED),
                        getTxnRecord(tokenCreateTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TokenCreate - too far start time fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenCreateTooFarStartTimeFailsOnIngest() {
                final var futureTxnId = "futureTokenCreate";
                final var oneHourFuture = 3_600L;
                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
                SigControl validSig = keyShape.signedWith(sigs(ON, ON));

                return hapiTest(
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        usableTxnIdNamed(futureTxnId)
                                .modifyValidStart(oneHourFuture)
                                .payerId(PAYER),
                        tokenCreate("fungibleToken")
                                .tokenType(FUNGIBLE_COMMON)
                                .treasury(TREASURY)
                                .payingWith(PAYER)
                                .sigControl(forKey(PAYER_KEY, validSig))
                                .signedBy(PAYER, TREASURY)
                                .txnId(futureTxnId)
                                .via(tokenCreateTxn)
                                .hasPrecheck(INVALID_TRANSACTION_START),
                        getTxnRecord(tokenCreateTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TokenCreate - invalid duration fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenCreateInvalidDurationFailsOnIngest() {
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
                                .validDurationSecs(0)
                                .via(tokenCreateTxn)
                                .hasPrecheck(INVALID_TRANSACTION_DURATION),
                        getTxnRecord(tokenCreateTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TokenCreate - duplicate transaction fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenCreateDuplicateTxnFailsOnIngest() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        // first successful token create
                        tokenCreate("fungibleToken")
                                .tokenType(FUNGIBLE_COMMON)
                                .treasury(TREASURY)
                                .payingWith(PAYER)
                                .signedBy(PAYER, TREASURY)
                                .via(tokenCreateTxn),
                        // duplicate reusing same txnId
                        tokenCreate("anotherToken")
                                .tokenType(FUNGIBLE_COMMON)
                                .treasury(TREASURY)
                                .payingWith(PAYER)
                                .signedBy(PAYER, TREASURY)
                                .txnId(tokenCreateTxn)
                                .via("tokenCreateDuplicateTxn")
                                .hasPrecheck(DUPLICATE_TRANSACTION));
            }

            @HapiTest
            @DisplayName("TokenCreate - very large txn (above 6KB) - fails on ingest")
            final Stream<DynamicTest> tokenCreateTransactionOversizeFailsOnIngest() {
                return hapiTest(
                        newKeyNamed(PAYER_KEY).shape(thresholdKeyWithPrimitives(70)),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        tokenCreate("fungibleToken")
                                .sigControl(forKey(PAYER_KEY, allOnSigControl(70)))
                                .tokenType(FUNGIBLE_COMMON)
                                .treasury(TREASURY)
                                .payingWith(PAYER)
                                .signedBy(PAYER, TREASURY)
                                .via(tokenCreateTxn)
                                .hasPrecheck(TRANSACTION_OVERSIZE),
                        getTxnRecord(tokenCreateTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TokenCreate - missing token name fails at handle - full fee charged")
            final Stream<DynamicTest> tokenCreateMissingNameFailsAtHandle() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        tokenCreate("fungibleToken")
                                .name("")
                                .tokenType(FUNGIBLE_COMMON)
                                .treasury(TREASURY)
                                .payingWith(PAYER)
                                .signedBy(PAYER, TREASURY)
                                .via(tokenCreateTxn)
                                .hasKnownStatus(MISSING_TOKEN_NAME),
                        validateChargedUsdWithinWithTxnSize(
                                tokenCreateTxn,
                                txnSize -> expectedTokenCreateFullFeeUsd(Map.of(
                                        SIGNATURES, 2L,
                                        KEYS, 0L,
                                        PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateChargedAccount(tokenCreateTxn, PAYER));
            }

            @HapiTest
            @DisplayName("TokenCreate - missing token symbol fails at handle - full fee charged")
            final Stream<DynamicTest> tokenCreateMissingSymbolFailsAtHandle() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        tokenCreate("fungibleToken")
                                .symbol("")
                                .tokenType(FUNGIBLE_COMMON)
                                .treasury(TREASURY)
                                .payingWith(PAYER)
                                .signedBy(PAYER, TREASURY)
                                .via(tokenCreateTxn)
                                .hasKnownStatus(MISSING_TOKEN_SYMBOL),
                        validateChargedUsdWithinWithTxnSize(
                                tokenCreateTxn,
                                txnSize -> expectedTokenCreateFullFeeUsd(Map.of(
                                        SIGNATURES, 2L,
                                        KEYS, 0L,
                                        PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateChargedAccount(tokenCreateTxn, PAYER));
            }

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
                                .via(tokenCreateTxn)
                                .hasKnownStatus(INVALID_SIGNATURE),
                        validateChargedUsdWithinWithTxnSize(
                                tokenCreateTxn,
                                txnSize -> expectedTokenCreateFullFeeUsd(Map.of(
                                        SIGNATURES, 1L,
                                        KEYS, 0L,
                                        PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateChargedAccount(tokenCreateTxn, PAYER));
            }

            @Tag(ONLY_SUBPROCESS)
            @LeakyHapiTest
            @DisplayName("TokenCreate - duplicate transaction fails on handle - payer charged full fee")
            final Stream<DynamicTest> tokenCreateDuplicateFailsOnHandle() {
                final String DUPLICATE_TXN_ID = "tokenCreateDuplicateTxnId";
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        cryptoTransfer(movingHbar(ONE_HBAR).between(GENESIS, "3")),
                        usableTxnIdNamed(DUPLICATE_TXN_ID).payerId(PAYER),
                        tokenCreate("fungibleToken")
                                .tokenType(FUNGIBLE_COMMON)
                                .treasury(TREASURY)
                                .payingWith(PAYER)
                                .signedBy(PAYER, TREASURY)
                                .setNode(4)
                                .txnId(DUPLICATE_TXN_ID)
                                .via(tokenCreateTxn)
                                .logged(),
                        tokenCreate("anotherToken")
                                .tokenType(FUNGIBLE_COMMON)
                                .treasury(TREASURY)
                                .payingWith(PAYER)
                                .signedBy(PAYER, TREASURY)
                                .setNode(3)
                                .txnId(DUPLICATE_TXN_ID)
                                .via("tokenCreateDuplicateTxn")
                                .hasPrecheck(DUPLICATE_TRANSACTION),
                        validateChargedUsdWithinWithTxnSize(
                                tokenCreateTxn,
                                txnSize -> expectedTokenCreateFullFeeUsd(Map.of(
                                        SIGNATURES, 2L,
                                        KEYS, 0L,
                                        PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateChargedAccount(tokenCreateTxn, PAYER));
            }
        }

        @Nested
        @DisplayName("TokenCreate Failures on Pre-Handle")
        class TokenCreateFailuresOnPreHandle {

            @LeakyEmbeddedHapiTest(reason = MUST_SKIP_INGEST)
            @DisplayName("TokenCreate - invalid payer signature fails on pre-handle - network fee only")
            final Stream<DynamicTest> tokenCreateInvalidPayerSigFailsOnPreHandle() {
                final String INNER_ID = "token-create-txn-inner-id";

                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
                SigControl invalidSig = keyShape.signedWith(sigs(ON, OFF));

                return hapiTest(
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        cryptoTransfer(movingHbar(ONE_HBAR).between(DEFAULT_PAYER, "4")),
                        tokenCreate("fungibleToken")
                                .tokenType(FUNGIBLE_COMMON)
                                .treasury(TREASURY)
                                .payingWith(PAYER)
                                .sigControl(forKey(PAYER_KEY, invalidSig))
                                .signedBy(PAYER, TREASURY)
                                .setNode("4")
                                .via(INNER_ID)
                                .hasKnownStatus(INVALID_PAYER_SIGNATURE),
                        getTxnRecord(INNER_ID).assertingNothingAboutHashes().logged(),
                        validateChargedUsdWithinWithTxnSize(
                                INNER_ID,
                                txnSize -> expectedNetworkOnlyFeeUsd(
                                        Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateChargedAccount(INNER_ID, "0.0.4"));
            }

            @LeakyEmbeddedHapiTest(reason = MUST_SKIP_INGEST)
            @DisplayName("TokenCreate - invalid treasury fails on pre-handle - full fee charged")
            final Stream<DynamicTest> tokenCreateInvalidTreasuryFailsOnPreHandle() {
                final String INNER_ID = "token-create-txn-inner-id";

                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        tokenCreate("fungibleToken")
                                .tokenType(FUNGIBLE_COMMON)
                                .treasury("0.0.99999999") // Invalid treasury
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .setNode("4")
                                .via(INNER_ID)
                                .hasKnownStatus(INVALID_ACCOUNT_ID),
                        getTxnRecord(INNER_ID).assertingNothingAboutHashes().logged(),
                        validateChargedUsdWithinWithTxnSize(
                                INNER_ID,
                                txnSize -> expectedTokenCreateFullFeeUsd(
                                        Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateChargedAccount(INNER_ID, PAYER));
            }
        }
    }
}
