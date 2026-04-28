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
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenFeeScheduleUpdate;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedHbarFee;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedHbarFeeInheritingRoyaltyCollector;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedHtsFee;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fractionalFee;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.royaltyFeeNoFallback;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.royaltyFeeWithFallback;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingHbar;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
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
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTokenFeeScheduleUpdateFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.signedTxnSizeFor;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.thresholdKeyWithPrimitives;
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
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_HAS_NO_FEE_SCHEDULE_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TRANSACTION_EXPIRED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TRANSACTION_OVERSIZE;
import static com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;
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
import java.util.OptionalLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;

/**
 * Tests for TokenFeeScheduleUpdate simple fees with extras.
 * Validates that fees are correctly calculated based on:
 * - Number of signatures (extras beyond included)
 * - Transaction size (processing bytes overage)
 */
@Tag(SIMPLE_FEES)
@HapiTestLifecycle
public class TokenFeeScheduleUpdateSimpleFeesTest {

    private static final String PAYER = "payer";
    private static final String TREASURY = "treasury";
    private static final String FEE_SCHEDULE_KEY = "feeScheduleKey";
    private static final String PAYER_KEY = "payerKey";
    private static final String TOKEN = "fungibleToken";
    private static final String FEE_COLLECTOR = "feeCollector";
    private static final String feeScheduleUpdateTxn = "feeScheduleUpdateTxn";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(Map.of("fees.simpleFeesEnabled", "true"));
    }

    @Nested
    @DisplayName("TokenFeeScheduleUpdate Simple Fees Positive Test Cases")
    class TokenFeeScheduleUpdateSimpleFeesPositiveTestCases {

        @HapiTest
        @DisplayName("TokenFeeScheduleUpdate - base fees")
        final Stream<DynamicTest> tokenFeeScheduleUpdateBaseFee() {
            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(TREASURY).balance(0L),
                    cryptoCreate(FEE_COLLECTOR).balance(0L),
                    newKeyNamed(FEE_SCHEDULE_KEY),
                    tokenCreate(TOKEN)
                            .tokenType(FUNGIBLE_COMMON)
                            .feeScheduleKey(FEE_SCHEDULE_KEY)
                            .treasury(TREASURY)
                            .payingWith(PAYER),
                    tokenFeeScheduleUpdate(TOKEN)
                            .withCustom(fixedHbarFee(1L, FEE_COLLECTOR))
                            .payingWith(PAYER)
                            .signedBy(PAYER, FEE_SCHEDULE_KEY)
                            .via(feeScheduleUpdateTxn),
                    validateChargedUsdWithinWithTxnSize(
                            feeScheduleUpdateTxn,
                            txnSize -> expectedTokenFeeScheduleUpdateFullFeeUsd(
                                    Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(feeScheduleUpdateTxn, PAYER));
        }

        @HapiTest
        @DisplayName("TokenFeeScheduleUpdate with threshold payer key - extra signatures")
        final Stream<DynamicTest> tokenFeeScheduleUpdateWithThresholdPayerKey() {
            KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
            SigControl validSig = keyShape.signedWith(sigs(ON, ON));

            return hapiTest(
                    newKeyNamed(PAYER_KEY).shape(keyShape),
                    cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(TREASURY).balance(0L),
                    cryptoCreate(FEE_COLLECTOR).balance(0L),
                    newKeyNamed(FEE_SCHEDULE_KEY),
                    tokenCreate(TOKEN)
                            .tokenType(FUNGIBLE_COMMON)
                            .feeScheduleKey(FEE_SCHEDULE_KEY)
                            .treasury(TREASURY)
                            .payingWith(PAYER)
                            .sigControl(forKey(PAYER_KEY, validSig)),
                    tokenFeeScheduleUpdate(TOKEN)
                            .withCustom(fixedHbarFee(1L, FEE_COLLECTOR))
                            .payingWith(PAYER)
                            .sigControl(forKey(PAYER_KEY, validSig))
                            .signedBy(PAYER, FEE_SCHEDULE_KEY)
                            .via(feeScheduleUpdateTxn),
                    validateChargedUsdWithinWithTxnSize(
                            feeScheduleUpdateTxn,
                            txnSize -> expectedTokenFeeScheduleUpdateFullFeeUsd(
                                    Map.of(SIGNATURES, 3L, PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(feeScheduleUpdateTxn, PAYER));
        }

        @HapiTest
        @DisplayName("TokenFeeScheduleUpdate with threshold fee schedule key - extra signatures")
        final Stream<DynamicTest> tokenFeeScheduleUpdateWithThresholdFeeScheduleKey() {
            KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
            SigControl validSig = keyShape.signedWith(sigs(ON, ON));

            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(TREASURY).balance(0L),
                    cryptoCreate(FEE_COLLECTOR).balance(0L),
                    newKeyNamed(FEE_SCHEDULE_KEY).shape(keyShape),
                    tokenCreate(TOKEN)
                            .tokenType(FUNGIBLE_COMMON)
                            .feeScheduleKey(FEE_SCHEDULE_KEY)
                            .treasury(TREASURY)
                            .payingWith(PAYER)
                            .sigControl(forKey(FEE_SCHEDULE_KEY, validSig)),
                    tokenFeeScheduleUpdate(TOKEN)
                            .withCustom(fixedHbarFee(1L, FEE_COLLECTOR))
                            .payingWith(PAYER)
                            .signedBy(PAYER, FEE_SCHEDULE_KEY)
                            .sigControl(forKey(FEE_SCHEDULE_KEY, validSig))
                            .via(feeScheduleUpdateTxn),
                    validateChargedUsdWithinWithTxnSize(
                            feeScheduleUpdateTxn,
                            txnSize -> expectedTokenFeeScheduleUpdateFullFeeUsd(
                                    Map.of(SIGNATURES, 3L, PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(feeScheduleUpdateTxn, PAYER));
        }

        @HapiTest
        @DisplayName(
                "TokenFeeScheduleUpdate - large key txn above NODE_INCLUDED_BYTES threshold - extra PROCESSING_BYTES charged")
        final Stream<DynamicTest> tokenFeeScheduleUpdateLargeKeyExtraProcessingBytesFee() {
            return hapiTest(
                    newKeyNamed(PAYER_KEY).shape(thresholdKeyWithPrimitives(20)),
                    cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(TREASURY).balance(0L),
                    cryptoCreate(FEE_COLLECTOR).balance(0L),
                    newKeyNamed(FEE_SCHEDULE_KEY),
                    tokenCreate(TOKEN)
                            .tokenType(FUNGIBLE_COMMON)
                            .feeScheduleKey(FEE_SCHEDULE_KEY)
                            .treasury(TREASURY)
                            .payingWith(PAYER),
                    tokenFeeScheduleUpdate(TOKEN)
                            .sigControl(forKey(PAYER_KEY, allOnSigControl(20)))
                            .withCustom(fixedHbarFee(1L, FEE_COLLECTOR))
                            .payingWith(PAYER)
                            .signedBy(PAYER, FEE_SCHEDULE_KEY)
                            .via(feeScheduleUpdateTxn),
                    assertionsHold((spec, log) -> {
                        final int txnSize = signedTxnSizeFor(spec, feeScheduleUpdateTxn);
                        log.info(
                                "Large-key TokenFeeScheduleUpdate signed size: {} bytes (threshold: {})",
                                txnSize,
                                NODE_INCLUDED_BYTES);
                        assertTrue(
                                txnSize > NODE_INCLUDED_BYTES,
                                "Expected txn size (" + txnSize + ") to exceed " + NODE_INCLUDED_BYTES + " bytes");
                    }),
                    validateChargedUsdWithinWithTxnSize(
                            feeScheduleUpdateTxn,
                            txnSize -> expectedTokenFeeScheduleUpdateFullFeeUsd(
                                    Map.of(SIGNATURES, 21L, PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(feeScheduleUpdateTxn, PAYER));
        }

        @HapiTest
        @DisplayName("TokenFeeScheduleUpdate - token with existing fixed hbar fee updated to add fixed hts fee")
        final Stream<DynamicTest> tokenFeeScheduleUpdateAddFixedHtsFee() {
            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(TREASURY).balance(0L),
                    cryptoCreate(FEE_COLLECTOR).balance(0L),
                    newKeyNamed(FEE_SCHEDULE_KEY),
                    tokenCreate(TOKEN)
                            .tokenType(FUNGIBLE_COMMON)
                            .feeScheduleKey(FEE_SCHEDULE_KEY)
                            .treasury(TREASURY)
                            .withCustom(fixedHbarFee(1L, FEE_COLLECTOR))
                            .payingWith(PAYER),
                    tokenAssociate(FEE_COLLECTOR, TOKEN),
                    tokenFeeScheduleUpdate(TOKEN)
                            .withCustom(fixedHbarFee(1L, FEE_COLLECTOR))
                            .withCustom(fixedHtsFee(10L, TOKEN, FEE_COLLECTOR))
                            .payingWith(PAYER)
                            .signedBy(PAYER, FEE_SCHEDULE_KEY)
                            .via(feeScheduleUpdateTxn),
                    validateChargedUsdWithinWithTxnSize(
                            feeScheduleUpdateTxn,
                            txnSize -> expectedTokenFeeScheduleUpdateFullFeeUsd(
                                    Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                            0.1));
        }

        @HapiTest
        @DisplayName("TokenFeeScheduleUpdate - NFT token updated with royalty fee")
        final Stream<DynamicTest> tokenFeeScheduleUpdateNftWithRoyaltyFee() {
            final String NFT_TOKEN = "nftToken";
            final String SUPPLY_KEY = "supplyKey";

            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(TREASURY).balance(0L),
                    cryptoCreate(FEE_COLLECTOR).balance(0L),
                    newKeyNamed(FEE_SCHEDULE_KEY),
                    newKeyNamed(SUPPLY_KEY),
                    tokenCreate(NFT_TOKEN)
                            .tokenType(NON_FUNGIBLE_UNIQUE)
                            .feeScheduleKey(FEE_SCHEDULE_KEY)
                            .supplyKey(SUPPLY_KEY)
                            .treasury(TREASURY)
                            .initialSupply(0)
                            .payingWith(PAYER),
                    tokenAssociate(FEE_COLLECTOR, NFT_TOKEN),
                    tokenFeeScheduleUpdate(NFT_TOKEN)
                            .withCustom(royaltyFeeNoFallback(1L, 10L, FEE_COLLECTOR))
                            .payingWith(PAYER)
                            .signedBy(PAYER, FEE_SCHEDULE_KEY)
                            .via(feeScheduleUpdateTxn),
                    validateChargedUsdWithinWithTxnSize(
                            feeScheduleUpdateTxn,
                            txnSize -> expectedTokenFeeScheduleUpdateFullFeeUsd(
                                    Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                            0.1));
        }

        @HapiTest
        @DisplayName("TokenFeeScheduleUpdate - NFT token updated with royalty fee with hbar fallback")
        final Stream<DynamicTest> tokenFeeScheduleUpdateNftWithRoyaltyFeeAndFallback() {
            final String NFT_TOKEN = "nftToken";
            final String SUPPLY_KEY = "supplyKey";

            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(TREASURY).balance(0L),
                    cryptoCreate(FEE_COLLECTOR).balance(0L),
                    newKeyNamed(FEE_SCHEDULE_KEY),
                    newKeyNamed(SUPPLY_KEY),
                    tokenCreate(NFT_TOKEN)
                            .tokenType(NON_FUNGIBLE_UNIQUE)
                            .feeScheduleKey(FEE_SCHEDULE_KEY)
                            .supplyKey(SUPPLY_KEY)
                            .initialSupply(0)
                            .treasury(TREASURY)
                            .payingWith(PAYER),
                    tokenAssociate(FEE_COLLECTOR, NFT_TOKEN),
                    tokenFeeScheduleUpdate(NFT_TOKEN)
                            .withCustom(royaltyFeeWithFallback(
                                    1L, 10L, fixedHbarFeeInheritingRoyaltyCollector(5L), FEE_COLLECTOR))
                            .payingWith(PAYER)
                            .signedBy(PAYER, FEE_SCHEDULE_KEY)
                            .via(feeScheduleUpdateTxn),
                    validateChargedUsdWithinWithTxnSize(
                            feeScheduleUpdateTxn,
                            txnSize -> expectedTokenFeeScheduleUpdateFullFeeUsd(
                                    Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                            0.1));
        }

        @HapiTest
        @DisplayName("TokenFeeScheduleUpdate - replace existing fees with fractional fee")
        final Stream<DynamicTest> tokenFeeScheduleUpdateReplaceFeeWithFractionalFee() {
            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(TREASURY).balance(0L),
                    cryptoCreate(FEE_COLLECTOR).balance(0L),
                    newKeyNamed(FEE_SCHEDULE_KEY),
                    tokenCreate(TOKEN)
                            .tokenType(FUNGIBLE_COMMON)
                            .feeScheduleKey(FEE_SCHEDULE_KEY)
                            .treasury(TREASURY)
                            .withCustom(fixedHbarFee(1L, FEE_COLLECTOR))
                            .payingWith(PAYER),
                    tokenAssociate(FEE_COLLECTOR, TOKEN),
                    tokenFeeScheduleUpdate(TOKEN)
                            .withCustom(fractionalFee(1L, 20L, 1L, OptionalLong.of(100L), FEE_COLLECTOR))
                            .payingWith(PAYER)
                            .signedBy(PAYER, FEE_SCHEDULE_KEY)
                            .via(feeScheduleUpdateTxn),
                    validateChargedUsdWithinWithTxnSize(
                            feeScheduleUpdateTxn,
                            txnSize -> expectedTokenFeeScheduleUpdateFullFeeUsd(
                                    Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                            0.1));
        }

        @HapiTest
        @DisplayName(("TokenFeeScheduleUpdate - don't bypass the custom fee creation charge"))
        final Stream<DynamicTest> tokenFeeScheduleUpdateBypassesCustomFeeCreationCharge() {
            return hapiTest(
                    newKeyNamed("feeScheduleKey"),
                    cryptoCreate("payer").balance(ONE_HUNDRED_HBARS),
                    cryptoCreate("feeCollector").balance(0L),

                    // PATH A: Create token with 1 custom fee directly
                    tokenCreate("tokenA")
                            .tokenType(FUNGIBLE_COMMON)
                            .initialSupply(1_000L)
                            .treasury("payer")
                            .feeScheduleKey("feeScheduleKey")
                            .withCustom(fixedHbarFee(1L, "feeCollector"))
                            .payingWith("payer")
                            .via("createWithFees"),

                    // PATH B.1: Create the same token WITHOUT custom fees
                    tokenCreate("tokenB")
                            .tokenType(FUNGIBLE_COMMON)
                            .initialSupply(1_000L)
                            .treasury("payer")
                            .feeScheduleKey("feeScheduleKey")
                            .payingWith("payer")
                            .via("createWithoutFees"),

                    // PATH B.2: add the same custom fee via TokenFeeScheduleUpdate
                    tokenFeeScheduleUpdate("tokenB")
                            .withCustom(fixedHbarFee(1L, "feeCollector"))
                            .payingWith("payer")
                            .signedBy("payer", "feeScheduleKey")
                            .via("feeScheduleUpdate"),
                    assertionsHold((spec, log) -> {
                        final var recA = getTxnRecord("createWithFees");
                        final var recB = getTxnRecord("createWithoutFees");
                        final var recBUpd = getTxnRecord("feeScheduleUpdate");
                        allRunFor(spec, recA, recB, recBUpd);

                        final long feeA = recA.getResponseRecord().getTransactionFee();
                        final long feeB = recB.getResponseRecord().getTransactionFee();
                        final long feeBUpd = recBUpd.getResponseRecord().getTransactionFee();
                        final long feeBTotal = feeB + feeBUpd;
                        final double ratio = (double) feeA / feeBTotal;

                        assertTrue(
                                (ratio > 0.999) && (ratio < 1.001),
                                "BUG: Path A cost: " + feeA + "!= Path B cost: " + feeBTotal + " ratio (A/B): "
                                        + String.format("%.2fx", ratio) + " —> Path B is "
                                        + String.format("%.2fx", ratio) + " cheaper");
                    }));
        }

        @HapiTest
        @DisplayName("TokenFeeScheduleUpdate - clear all custom fees")
        final Stream<DynamicTest> tokenFeeScheduleUpdateClearAllFees() {
            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(TREASURY).balance(0L),
                    cryptoCreate(FEE_COLLECTOR).balance(0L),
                    newKeyNamed(FEE_SCHEDULE_KEY),
                    tokenCreate(TOKEN)
                            .tokenType(FUNGIBLE_COMMON)
                            .feeScheduleKey(FEE_SCHEDULE_KEY)
                            .treasury(TREASURY)
                            .withCustom(fixedHbarFee(1L, FEE_COLLECTOR))
                            .payingWith(PAYER),
                    tokenFeeScheduleUpdate(TOKEN)
                            .payingWith(PAYER)
                            .signedBy(PAYER, FEE_SCHEDULE_KEY)
                            .via(feeScheduleUpdateTxn),
                    validateChargedUsdWithinWithTxnSize(
                            feeScheduleUpdateTxn,
                            txnSize -> expectedTokenFeeScheduleUpdateFullFeeUsd(
                                    Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                            0.1));
        }
    }

    @Nested
    @DisplayName("TokenFeeScheduleUpdate Simple Fees Negative Test Cases")
    class TokenFeeScheduleUpdateSimpleFeesNegativeTestCases {

        @Nested
        @DisplayName("TokenFeeScheduleUpdate Failures on Ingest and Handle")
        class TokenFeeScheduleUpdateFailuresOnIngest {

            @HapiTest
            @DisplayName("TokenFeeScheduleUpdate - insufficient tx fee fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenFeeScheduleUpdateInsufficientTxFeeFailsOnIngest() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        cryptoCreate(FEE_COLLECTOR).balance(0L),
                        newKeyNamed(FEE_SCHEDULE_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .feeScheduleKey(FEE_SCHEDULE_KEY)
                                .treasury(TREASURY)
                                .payingWith(PAYER),
                        tokenFeeScheduleUpdate(TOKEN)
                                .withCustom(fixedHbarFee(1L, FEE_COLLECTOR))
                                .payingWith(PAYER)
                                .signedBy(PAYER, FEE_SCHEDULE_KEY)
                                .fee(1L) // Fee too low
                                .via(feeScheduleUpdateTxn)
                                .hasPrecheck(INSUFFICIENT_TX_FEE),
                        getTxnRecord(feeScheduleUpdateTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TokenFeeScheduleUpdate - threshold key invalid signature fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenFeeScheduleUpdateThresholdInvalidSigFailsOnIngest() {
                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
                SigControl invalidSig = keyShape.signedWith(sigs(ON, OFF));

                return hapiTest(
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        cryptoCreate(FEE_COLLECTOR).balance(0L),
                        newKeyNamed(FEE_SCHEDULE_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .feeScheduleKey(FEE_SCHEDULE_KEY)
                                .treasury(TREASURY)
                                .payingWith(PAYER)
                                .sigControl(forKey(PAYER_KEY, keyShape.signedWith(sigs(ON, ON)))),
                        tokenFeeScheduleUpdate(TOKEN)
                                .withCustom(fixedHbarFee(1L, FEE_COLLECTOR))
                                .payingWith(PAYER)
                                .sigControl(forKey(PAYER_KEY, invalidSig))
                                .signedBy(PAYER, FEE_SCHEDULE_KEY)
                                .via(feeScheduleUpdateTxn)
                                .hasPrecheck(INVALID_SIGNATURE),
                        getTxnRecord(feeScheduleUpdateTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName(
                    "TokenFeeScheduleUpdate - threshold key with nested list invalid signature fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenFeeScheduleUpdateThresholdWithListInvalidSigFailsOnIngest() {
                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE, listOf(2));
                SigControl invalidSig = keyShape.signedWith(sigs(ON, OFF, sigs(OFF, OFF)));

                return hapiTest(
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(PAYER)
                                .key(PAYER_KEY)
                                .sigControl(forKey(PAYER_KEY, keyShape.signedWith(sigs(ON, ON, sigs(ON, ON)))))
                                .balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        cryptoCreate(FEE_COLLECTOR).balance(0L),
                        newKeyNamed(FEE_SCHEDULE_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .feeScheduleKey(FEE_SCHEDULE_KEY)
                                .treasury(TREASURY)
                                .payingWith(PAYER)
                                .sigControl(forKey(PAYER_KEY, keyShape.signedWith(sigs(ON, ON, sigs(ON, ON))))),
                        tokenFeeScheduleUpdate(TOKEN)
                                .withCustom(fixedHbarFee(1L, FEE_COLLECTOR))
                                .payingWith(PAYER)
                                .sigControl(forKey(PAYER_KEY, invalidSig))
                                .signedBy(PAYER, FEE_SCHEDULE_KEY)
                                .via(feeScheduleUpdateTxn)
                                .hasPrecheck(INVALID_SIGNATURE),
                        getTxnRecord(feeScheduleUpdateTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TokenFeeScheduleUpdate - key list with missing signature fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenFeeScheduleUpdateKeyListMissingSigFailsOnIngest() {
                return hapiTest(
                        newKeyNamed("firstKey"),
                        newKeyNamed("secondKey"),
                        newKeyListNamed(PAYER_KEY, List.of("firstKey", "secondKey")),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        cryptoCreate(FEE_COLLECTOR).balance(0L),
                        newKeyNamed(FEE_SCHEDULE_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .feeScheduleKey(FEE_SCHEDULE_KEY)
                                .treasury(TREASURY)
                                .payingWith(PAYER),
                        tokenFeeScheduleUpdate(TOKEN)
                                .withCustom(fixedHbarFee(1L, FEE_COLLECTOR))
                                .payingWith(PAYER)
                                .signedBy("firstKey", FEE_SCHEDULE_KEY) // missing secondKey from list
                                .via(feeScheduleUpdateTxn)
                                .hasPrecheck(INVALID_SIGNATURE),
                        getTxnRecord(feeScheduleUpdateTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TokenFeeScheduleUpdate - insufficient payer balance fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenFeeScheduleUpdateInsufficientPayerBalanceFailsOnIngest() {
                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
                SigControl validSig = keyShape.signedWith(sigs(ON, ON));

                return hapiTest(
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(1L), // too little balance
                        cryptoCreate(TREASURY).balance(0L),
                        cryptoCreate(FEE_COLLECTOR).balance(0L),
                        newKeyNamed(FEE_SCHEDULE_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .feeScheduleKey(FEE_SCHEDULE_KEY)
                                .treasury(TREASURY),
                        tokenFeeScheduleUpdate(TOKEN)
                                .withCustom(fixedHbarFee(1L, FEE_COLLECTOR))
                                .payingWith(PAYER)
                                .sigControl(forKey(PAYER_KEY, validSig))
                                .signedBy(PAYER, FEE_SCHEDULE_KEY)
                                .via(feeScheduleUpdateTxn)
                                .hasPrecheck(INSUFFICIENT_PAYER_BALANCE),
                        getTxnRecord(feeScheduleUpdateTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TokenFeeScheduleUpdate - memo too long fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenFeeScheduleUpdateMemoTooLongFailsOnIngest() {
                final var LONG_MEMO = "x".repeat(1025);
                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
                SigControl validSig = keyShape.signedWith(sigs(ON, ON));

                return hapiTest(
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        cryptoCreate(FEE_COLLECTOR).balance(0L),
                        newKeyNamed(FEE_SCHEDULE_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .feeScheduleKey(FEE_SCHEDULE_KEY)
                                .treasury(TREASURY)
                                .payingWith(PAYER)
                                .sigControl(forKey(PAYER_KEY, validSig)),
                        tokenFeeScheduleUpdate(TOKEN)
                                .withCustom(fixedHbarFee(1L, FEE_COLLECTOR))
                                .payingWith(PAYER)
                                .sigControl(forKey(PAYER_KEY, validSig))
                                .signedBy(PAYER, FEE_SCHEDULE_KEY)
                                .memo(LONG_MEMO)
                                .via(feeScheduleUpdateTxn)
                                .hasPrecheck(MEMO_TOO_LONG),
                        getTxnRecord(feeScheduleUpdateTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TokenFeeScheduleUpdate - expired transaction fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenFeeScheduleUpdateExpiredTransactionFailsOnIngest() {
                final var expiredFeeScheduleUpdateTxn = "expiredFeeScheduleUpdate";
                final var oneHourPast = -3_600L;
                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
                SigControl validSig = keyShape.signedWith(sigs(ON, ON));

                return hapiTest(
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        cryptoCreate(FEE_COLLECTOR).balance(0L),
                        newKeyNamed(FEE_SCHEDULE_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .feeScheduleKey(FEE_SCHEDULE_KEY)
                                .treasury(TREASURY)
                                .payingWith(PAYER)
                                .sigControl(forKey(PAYER_KEY, validSig)),
                        usableTxnIdNamed(expiredFeeScheduleUpdateTxn)
                                .modifyValidStart(oneHourPast)
                                .payerId(PAYER),
                        tokenFeeScheduleUpdate(TOKEN)
                                .withCustom(fixedHbarFee(1L, FEE_COLLECTOR))
                                .payingWith(PAYER)
                                .sigControl(forKey(PAYER_KEY, validSig))
                                .signedBy(PAYER, FEE_SCHEDULE_KEY)
                                .txnId(expiredFeeScheduleUpdateTxn)
                                .via(feeScheduleUpdateTxn)
                                .hasPrecheck(TRANSACTION_EXPIRED),
                        getTxnRecord(feeScheduleUpdateTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TokenFeeScheduleUpdate - too far start time fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenFeeScheduleUpdateTooFarStartTimeFailsOnIngest() {
                final var futureFeeScheduleUpdateTxn = "futureFeeScheduleUpdate";
                final var oneHourFuture = 3_600L;
                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
                SigControl validSig = keyShape.signedWith(sigs(ON, ON));

                return hapiTest(
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        cryptoCreate(FEE_COLLECTOR).balance(0L),
                        newKeyNamed(FEE_SCHEDULE_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .feeScheduleKey(FEE_SCHEDULE_KEY)
                                .treasury(TREASURY)
                                .payingWith(PAYER)
                                .sigControl(forKey(PAYER_KEY, validSig)),
                        usableTxnIdNamed(futureFeeScheduleUpdateTxn)
                                .modifyValidStart(oneHourFuture)
                                .payerId(PAYER),
                        tokenFeeScheduleUpdate(TOKEN)
                                .withCustom(fixedHbarFee(1L, FEE_COLLECTOR))
                                .payingWith(PAYER)
                                .sigControl(forKey(PAYER_KEY, validSig))
                                .signedBy(PAYER, FEE_SCHEDULE_KEY)
                                .txnId(futureFeeScheduleUpdateTxn)
                                .via(feeScheduleUpdateTxn)
                                .hasPrecheck(INVALID_TRANSACTION_START),
                        getTxnRecord(feeScheduleUpdateTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TokenFeeScheduleUpdate - invalid duration fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenFeeScheduleUpdateInvalidDurationFailsOnIngest() {
                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
                SigControl validSig = keyShape.signedWith(sigs(ON, ON));

                return hapiTest(
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        cryptoCreate(FEE_COLLECTOR).balance(0L),
                        newKeyNamed(FEE_SCHEDULE_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .feeScheduleKey(FEE_SCHEDULE_KEY)
                                .treasury(TREASURY)
                                .payingWith(PAYER)
                                .sigControl(forKey(PAYER_KEY, validSig)),
                        tokenFeeScheduleUpdate(TOKEN)
                                .withCustom(fixedHbarFee(1L, FEE_COLLECTOR))
                                .payingWith(PAYER)
                                .sigControl(forKey(PAYER_KEY, validSig))
                                .signedBy(PAYER, FEE_SCHEDULE_KEY)
                                .validDurationSecs(0)
                                .via(feeScheduleUpdateTxn)
                                .hasPrecheck(INVALID_TRANSACTION_DURATION),
                        getTxnRecord(feeScheduleUpdateTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TokenFeeScheduleUpdate - duplicate transaction fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenFeeScheduleUpdateDuplicateTxnFailsOnIngest() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        cryptoCreate(FEE_COLLECTOR).balance(0L),
                        newKeyNamed(FEE_SCHEDULE_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .feeScheduleKey(FEE_SCHEDULE_KEY)
                                .treasury(TREASURY)
                                .payingWith(PAYER),
                        // first successful update
                        tokenFeeScheduleUpdate(TOKEN)
                                .withCustom(fixedHbarFee(1L, FEE_COLLECTOR))
                                .payingWith(PAYER)
                                .signedBy(PAYER, FEE_SCHEDULE_KEY)
                                .via(feeScheduleUpdateTxn),
                        // duplicate reusing same feeScheduleUpdateTxn
                        cryptoCreate("dummy")
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .txnId(feeScheduleUpdateTxn)
                                .via("feeScheduleUpdateDuplicateTxn")
                                .hasPrecheck(DUPLICATE_TRANSACTION));
            }

            @HapiTest
            @DisplayName("TokenFeeScheduleUpdate - very large txn fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenFeeScheduleUpdateTransactionOversizeFailsOnIngest() {
                return hapiTest(
                        newKeyNamed(PAYER_KEY).shape(thresholdKeyWithPrimitives(70)),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        cryptoCreate(FEE_COLLECTOR).balance(0L),
                        newKeyNamed(FEE_SCHEDULE_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .feeScheduleKey(FEE_SCHEDULE_KEY)
                                .treasury(TREASURY),
                        tokenFeeScheduleUpdate(TOKEN)
                                .sigControl(forKey(PAYER_KEY, allOnSigControl(70)))
                                .withCustom(fixedHbarFee(1L, FEE_COLLECTOR))
                                .payingWith(PAYER)
                                .signedBy(PAYER, FEE_SCHEDULE_KEY)
                                .via(feeScheduleUpdateTxn)
                                .hasPrecheck(TRANSACTION_OVERSIZE),
                        getTxnRecord(feeScheduleUpdateTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TokenFeeScheduleUpdate - missing fee schedule key signature fails on handle - fee charged")
            final Stream<DynamicTest> tokenFeeScheduleUpdateMissingFeeScheduleKeySignatureFailsAtHandle() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        cryptoCreate(FEE_COLLECTOR).balance(0L),
                        newKeyNamed(FEE_SCHEDULE_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .feeScheduleKey(FEE_SCHEDULE_KEY)
                                .treasury(TREASURY)
                                .payingWith(PAYER),
                        tokenFeeScheduleUpdate(TOKEN)
                                .withCustom(fixedHbarFee(1L, FEE_COLLECTOR))
                                .payingWith(PAYER)
                                .signedBy(PAYER) // Missing fee schedule key signature
                                .via(feeScheduleUpdateTxn)
                                .hasKnownStatus(INVALID_SIGNATURE),
                        validateChargedUsdWithinWithTxnSize(
                                feeScheduleUpdateTxn,
                                txnSize -> expectedTokenFeeScheduleUpdateFullFeeUsd(
                                        Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateChargedAccount(feeScheduleUpdateTxn, PAYER));
            }

            @HapiTest
            @DisplayName("TokenFeeScheduleUpdate - invalid token fails on handle - fee charged")
            final Stream<DynamicTest> tokenFeeScheduleUpdateInvalidTokenFails() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(FEE_COLLECTOR).balance(0L),
                        tokenFeeScheduleUpdate("0.0.99999999") // Invalid token
                                .withCustom(fixedHbarFee(1L, FEE_COLLECTOR))
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .via(feeScheduleUpdateTxn)
                                .hasKnownStatus(INVALID_TOKEN_ID),
                        validateChargedUsdWithinWithTxnSize(
                                feeScheduleUpdateTxn,
                                txnSize -> expectedTokenFeeScheduleUpdateFullFeeUsd(
                                        Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateChargedAccount(feeScheduleUpdateTxn, PAYER));
            }

            @HapiTest
            @DisplayName("TokenFeeScheduleUpdate - token without fee schedule key fails on handle - fee charged")
            final Stream<DynamicTest> tokenFeeScheduleUpdateNoFeeScheduleKeyFails() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        cryptoCreate(FEE_COLLECTOR).balance(0L),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                // No fee schedule key
                                .treasury(TREASURY)
                                .payingWith(PAYER),
                        tokenFeeScheduleUpdate(TOKEN)
                                .withCustom(fixedHbarFee(1L, FEE_COLLECTOR))
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .via(feeScheduleUpdateTxn)
                                .hasKnownStatus(TOKEN_HAS_NO_FEE_SCHEDULE_KEY),
                        validateChargedUsdWithinWithTxnSize(
                                feeScheduleUpdateTxn,
                                txnSize -> expectedTokenFeeScheduleUpdateFullFeeUsd(
                                        Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateChargedAccount(feeScheduleUpdateTxn, PAYER));
            }

            @Tag(ONLY_SUBPROCESS)
            @LeakyHapiTest
            @DisplayName("TokenFeeScheduleUpdate - duplicate transaction fails on handle - payer charged full fee")
            final Stream<DynamicTest> tokenFeeScheduleUpdateDuplicateFailsOnHandle() {
                final String DUPLICATE_TXN_ID = "feeScheduleUpdateDuplicateTxn";
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        cryptoCreate(FEE_COLLECTOR).balance(0L),
                        newKeyNamed(FEE_SCHEDULE_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .feeScheduleKey(FEE_SCHEDULE_KEY)
                                .treasury(TREASURY)
                                .payingWith(PAYER),
                        cryptoTransfer(movingHbar(ONE_HBAR).between(GENESIS, "3")),
                        usableTxnIdNamed(DUPLICATE_TXN_ID).payerId(PAYER),
                        tokenFeeScheduleUpdate(TOKEN)
                                .withCustom(fixedHbarFee(1L, FEE_COLLECTOR))
                                .payingWith(PAYER)
                                .signedBy(PAYER, FEE_SCHEDULE_KEY)
                                .setNode(4)
                                .txnId(DUPLICATE_TXN_ID)
                                .via(feeScheduleUpdateTxn)
                                .logged(),
                        tokenFeeScheduleUpdate(TOKEN)
                                .withCustom(fixedHbarFee(2L, FEE_COLLECTOR))
                                .payingWith(PAYER)
                                .signedBy(PAYER, FEE_SCHEDULE_KEY)
                                .setNode(3)
                                .txnId(DUPLICATE_TXN_ID)
                                .via("feeScheduleUpdateDuplicateTxn")
                                .hasPrecheck(DUPLICATE_TRANSACTION),
                        validateChargedUsdWithinWithTxnSize(
                                feeScheduleUpdateTxn,
                                txnSize -> expectedTokenFeeScheduleUpdateFullFeeUsd(
                                        Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateChargedAccount(feeScheduleUpdateTxn, PAYER));
            }
        }

        @Nested
        @DisplayName("TokenFeeScheduleUpdate Failures on Pre-Handle")
        class TokenFeeScheduleUpdateFailuresOnPreHandle {

            @LeakyEmbeddedHapiTest(reason = MUST_SKIP_INGEST)
            @DisplayName("TokenFeeScheduleUpdate - invalid payer signature fails on pre-handle - network fee only")
            final Stream<DynamicTest> tokenFeeScheduleUpdateInvalidPayerSigFailsOnPreHandle() {
                final String INNER_ID = "fee-schedule-update-txn-inner-id";

                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
                SigControl invalidSig = keyShape.signedWith(sigs(ON, OFF));

                return hapiTest(
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        cryptoCreate(FEE_COLLECTOR).balance(0L),
                        newKeyNamed(FEE_SCHEDULE_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .feeScheduleKey(FEE_SCHEDULE_KEY)
                                .treasury(TREASURY),
                        cryptoTransfer(movingHbar(ONE_HBAR).between(DEFAULT_PAYER, "4")),
                        tokenFeeScheduleUpdate(TOKEN)
                                .withCustom(fixedHbarFee(1L, FEE_COLLECTOR))
                                .payingWith(PAYER)
                                .sigControl(forKey(PAYER_KEY, invalidSig))
                                .signedBy(PAYER, FEE_SCHEDULE_KEY)
                                .setNode("4")
                                .via(INNER_ID)
                                .hasKnownStatus(INVALID_PAYER_SIGNATURE),
                        getTxnRecord(INNER_ID).assertingNothingAboutHashes().logged(),
                        validateChargedUsdWithinWithTxnSize(
                                INNER_ID,
                                txnSize -> expectedNetworkOnlyFeeUsd(
                                        Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                                0.1));
            }
        }
    }
}
