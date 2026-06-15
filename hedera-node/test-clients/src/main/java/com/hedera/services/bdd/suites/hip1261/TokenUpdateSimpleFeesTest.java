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
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenUpdate;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedHbarFee;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingHbar;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.usableTxnIdNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedAccount;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedNetworkOnlyFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTokenUpdateFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.validateChargedUsdWithinWithTxnSize;
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
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TRANSACTION_EXPIRED;
import static com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;
import static org.hiero.hapi.support.fees.Extra.KEYS;
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
 * Tests for TokenUpdate simple fees with extras.
 * Validates that fees are correctly calculated based on:
 * - Number of signatures (extras beyond included)
 * - Number of keys being updated
 */
@Tag(SIMPLE_FEES)
@HapiTestLifecycle
public class TokenUpdateSimpleFeesTest {

    private static final String PAYER = "payer";
    private static final String TREASURY = "treasury";
    private static final String ADMIN_KEY = "adminKey";
    private static final String NEW_ADMIN_KEY = "newAdminKey";
    private static final String SUPPLY_KEY = "supplyKey";
    private static final String NEW_SUPPLY_KEY = "newSupplyKey";
    private static final String FREEZE_KEY = "freezeKey";
    private static final String PAYER_KEY = "payerKey";
    private static final String TOKEN = "fungibleToken";
    private static final String NFT_TOKEN = "nftToken";
    private static final String NEW_TREASURY = "newTreasury";
    private static final String FEE_SCHEDULE_KEY = "feeScheduleKey";
    private static final String NEW_FEE_SCHEDULE_KEY = "newFeeScheduleKey";
    private static final String HBAR_COLLECTOR = "hbarCollector";
    private static final String tokenUpdateTxn = "tokenUpdateTxn";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(Map.of("fees.simpleFeesEnabled", "true"));
    }

    @Nested
    @DisplayName("TokenUpdate Simple Fees Positive Test Cases")
    class TokenUpdateSimpleFeesPositiveTestCases {

        @HapiTest
        @DisplayName("TokenUpdate - base fees without key change")
        final Stream<DynamicTest> tokenUpdateBaseFee() {
            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(TREASURY).balance(0L),
                    newKeyNamed(ADMIN_KEY),
                    tokenCreate(TOKEN)
                            .tokenType(FUNGIBLE_COMMON)
                            .adminKey(ADMIN_KEY)
                            .treasury(TREASURY)
                            .payingWith(PAYER),
                    tokenUpdate(TOKEN)
                            .memo("Updated memo")
                            .payingWith(PAYER)
                            .signedBy(PAYER, ADMIN_KEY)
                            .via(tokenUpdateTxn),
                    validateChargedUsdWithinWithTxnSize(
                            tokenUpdateTxn,
                            txnSize -> expectedTokenUpdateFullFeeUsd(
                                    Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(tokenUpdateTxn, PAYER));
        }

        @HapiTest
        @DisplayName("TokenUpdate - with new admin key - extra key and signature")
        final Stream<DynamicTest> tokenUpdateWithNewAdminKey() {
            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(TREASURY).balance(0L),
                    newKeyNamed(ADMIN_KEY),
                    newKeyNamed(NEW_ADMIN_KEY),
                    tokenCreate(TOKEN)
                            .tokenType(FUNGIBLE_COMMON)
                            .adminKey(ADMIN_KEY)
                            .treasury(TREASURY)
                            .payingWith(PAYER),
                    tokenUpdate(TOKEN)
                            .adminKey(NEW_ADMIN_KEY)
                            .payingWith(PAYER)
                            .signedBy(PAYER, ADMIN_KEY, NEW_ADMIN_KEY)
                            .via(tokenUpdateTxn),
                    validateChargedUsdWithinWithTxnSize(
                            tokenUpdateTxn,
                            txnSize -> expectedTokenUpdateFullFeeUsd(Map.of(
                                    SIGNATURES, 3L,
                                    KEYS, 1L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(tokenUpdateTxn, PAYER));
        }

        @HapiTest
        @DisplayName("TokenUpdate - with new supply key - extra key and signature")
        final Stream<DynamicTest> tokenUpdateWithNewSupplyKey() {
            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(TREASURY).balance(0L),
                    newKeyNamed(ADMIN_KEY),
                    newKeyNamed(SUPPLY_KEY),
                    newKeyNamed(NEW_SUPPLY_KEY),
                    tokenCreate(TOKEN)
                            .tokenType(FUNGIBLE_COMMON)
                            .adminKey(ADMIN_KEY)
                            .supplyKey(SUPPLY_KEY)
                            .treasury(TREASURY)
                            .payingWith(PAYER),
                    tokenUpdate(TOKEN)
                            .supplyKey(NEW_SUPPLY_KEY)
                            .payingWith(PAYER)
                            .signedBy(PAYER, ADMIN_KEY)
                            .via(tokenUpdateTxn),
                    validateChargedUsdWithinWithTxnSize(
                            tokenUpdateTxn,
                            txnSize -> expectedTokenUpdateFullFeeUsd(Map.of(
                                    SIGNATURES, 2L,
                                    KEYS, 1L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(tokenUpdateTxn, PAYER));
        }

        @HapiTest
        @DisplayName("TokenUpdate - with multiple new keys - multiple extras")
        final Stream<DynamicTest> tokenUpdateWithMultipleNewKeys() {
            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(TREASURY).balance(0L),
                    newKeyNamed(ADMIN_KEY),
                    newKeyNamed(NEW_ADMIN_KEY),
                    newKeyNamed(SUPPLY_KEY),
                    newKeyNamed(NEW_SUPPLY_KEY),
                    newKeyNamed(FREEZE_KEY),
                    newKeyNamed("newFreezeKey"),
                    tokenCreate(TOKEN)
                            .tokenType(FUNGIBLE_COMMON)
                            .adminKey(ADMIN_KEY)
                            .supplyKey(SUPPLY_KEY)
                            .freezeKey(FREEZE_KEY)
                            .treasury(TREASURY)
                            .payingWith(PAYER),
                    tokenUpdate(TOKEN)
                            .adminKey(NEW_ADMIN_KEY)
                            .supplyKey(NEW_SUPPLY_KEY)
                            .freezeKey("newFreezeKey")
                            .payingWith(PAYER)
                            .signedBy(PAYER, ADMIN_KEY, NEW_ADMIN_KEY)
                            .via(tokenUpdateTxn),
                    validateChargedUsdWithinWithTxnSize(
                            tokenUpdateTxn,
                            txnSize -> expectedTokenUpdateFullFeeUsd(Map.of(
                                    SIGNATURES, 3L,
                                    KEYS, 3L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(tokenUpdateTxn, PAYER));
        }

        @HapiTest
        @DisplayName("TokenUpdate with threshold key - extra signatures")
        final Stream<DynamicTest> tokenUpdateWithThresholdKey() {
            KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
            SigControl validSig = keyShape.signedWith(sigs(ON, ON));

            return hapiTest(
                    newKeyNamed(PAYER_KEY).shape(keyShape),
                    cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(TREASURY).balance(0L),
                    newKeyNamed(ADMIN_KEY),
                    tokenCreate(TOKEN)
                            .tokenType(FUNGIBLE_COMMON)
                            .adminKey(ADMIN_KEY)
                            .treasury(TREASURY)
                            .payingWith(PAYER)
                            .sigControl(forKey(PAYER_KEY, validSig)),
                    tokenUpdate(TOKEN)
                            .memo("Updated memo")
                            .payingWith(PAYER)
                            .sigControl(forKey(PAYER_KEY, validSig))
                            .signedBy(PAYER, ADMIN_KEY)
                            .via(tokenUpdateTxn),
                    validateChargedUsdWithinWithTxnSize(
                            tokenUpdateTxn,
                            txnSize -> expectedTokenUpdateFullFeeUsd(
                                    Map.of(SIGNATURES, 3L, PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(tokenUpdateTxn, PAYER));
        }

        @HapiTest
        @DisplayName("TokenUpdate - with new treasury - extra signatures")
        final Stream<DynamicTest> tokenUpdateWithNewTreasury() {
            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(TREASURY).balance(0L),
                    cryptoCreate(NEW_TREASURY).balance(0L),
                    newKeyNamed(ADMIN_KEY),
                    tokenCreate(TOKEN)
                            .tokenType(FUNGIBLE_COMMON)
                            .adminKey(ADMIN_KEY)
                            .treasury(TREASURY)
                            .payingWith(PAYER),
                    tokenAssociate(NEW_TREASURY, TOKEN),
                    tokenUpdate(TOKEN)
                            .treasury(NEW_TREASURY)
                            .payingWith(PAYER)
                            .signedBy(PAYER, ADMIN_KEY, NEW_TREASURY)
                            .via(tokenUpdateTxn),
                    validateChargedUsdWithinWithTxnSize(
                            tokenUpdateTxn,
                            txnSize -> expectedTokenUpdateFullFeeUsd(
                                    Map.of(SIGNATURES, 3L, PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(tokenUpdateTxn, PAYER));
        }

        @HapiTest
        @DisplayName("TokenUpdate - token with custom fees - base fee update")
        final Stream<DynamicTest> tokenUpdateWithCustomFees() {
            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(TREASURY).balance(0L),
                    cryptoCreate(HBAR_COLLECTOR).balance(0L),
                    newKeyNamed(ADMIN_KEY),
                    tokenCreate(TOKEN)
                            .tokenType(FUNGIBLE_COMMON)
                            .adminKey(ADMIN_KEY)
                            .treasury(TREASURY)
                            .withCustom(fixedHbarFee(100L, HBAR_COLLECTOR))
                            .payingWith(PAYER),
                    tokenUpdate(TOKEN)
                            .memo("Updated memo on token with custom fees")
                            .payingWith(PAYER)
                            .signedBy(PAYER, ADMIN_KEY)
                            .via(tokenUpdateTxn),
                    validateChargedUsdWithinWithTxnSize(
                            tokenUpdateTxn,
                            txnSize -> expectedTokenUpdateFullFeeUsd(
                                    Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(tokenUpdateTxn, PAYER));
        }

        @HapiTest
        @DisplayName("TokenUpdate - with new fee schedule key - extra key")
        final Stream<DynamicTest> tokenUpdateFeeScheduleKey() {
            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(TREASURY).balance(0L),
                    newKeyNamed(ADMIN_KEY),
                    newKeyNamed(FEE_SCHEDULE_KEY),
                    newKeyNamed(NEW_FEE_SCHEDULE_KEY),
                    tokenCreate(TOKEN)
                            .tokenType(FUNGIBLE_COMMON)
                            .adminKey(ADMIN_KEY)
                            .feeScheduleKey(FEE_SCHEDULE_KEY)
                            .treasury(TREASURY)
                            .payingWith(PAYER),
                    tokenUpdate(TOKEN)
                            .feeScheduleKey(NEW_FEE_SCHEDULE_KEY)
                            .payingWith(PAYER)
                            .signedBy(PAYER, ADMIN_KEY)
                            .via(tokenUpdateTxn),
                    validateChargedUsdWithinWithTxnSize(
                            tokenUpdateTxn,
                            txnSize -> expectedTokenUpdateFullFeeUsd(Map.of(
                                    SIGNATURES, 2L,
                                    KEYS, 1L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(tokenUpdateTxn, PAYER));
        }

        @HapiTest
        @DisplayName("TokenUpdate - NFT token update - base fee")
        final Stream<DynamicTest> tokenUpdateNft() {
            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(TREASURY).balance(0L),
                    newKeyNamed(ADMIN_KEY),
                    newKeyNamed(SUPPLY_KEY),
                    tokenCreate(NFT_TOKEN)
                            .tokenType(NON_FUNGIBLE_UNIQUE)
                            .initialSupply(0L)
                            .adminKey(ADMIN_KEY)
                            .supplyKey(SUPPLY_KEY)
                            .treasury(TREASURY)
                            .payingWith(PAYER),
                    tokenUpdate(NFT_TOKEN)
                            .name("Updated NFT Name")
                            .symbol("UNFT")
                            .payingWith(PAYER)
                            .signedBy(PAYER, ADMIN_KEY)
                            .via(tokenUpdateTxn),
                    validateChargedUsdWithinWithTxnSize(
                            tokenUpdateTxn,
                            txnSize -> expectedTokenUpdateFullFeeUsd(
                                    Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(tokenUpdateTxn, PAYER));
        }

        @HapiTest
        @DisplayName("TokenUpdate with large payer key - extra processing bytes fee")
        final Stream<DynamicTest> tokenUpdateLargePayerKeyExtraProcessingBytesFee() {
            KeyShape keyShape = threshOf(
                    1, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE,
                    SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE);
            SigControl allSigned = keyShape.signedWith(
                    sigs(ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON));

            return hapiTest(
                    newKeyNamed(PAYER_KEY).shape(keyShape),
                    cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(TREASURY).balance(0L),
                    newKeyNamed(ADMIN_KEY),
                    tokenCreate(TOKEN)
                            .tokenType(FUNGIBLE_COMMON)
                            .adminKey(ADMIN_KEY)
                            .treasury(TREASURY)
                            .payingWith(PAYER)
                            .sigControl(forKey(PAYER_KEY, allSigned)),
                    tokenUpdate(TOKEN)
                            .memo("Updated memo")
                            .payingWith(PAYER)
                            .sigControl(forKey(PAYER_KEY, allSigned))
                            .signedBy(PAYER, ADMIN_KEY)
                            .via(tokenUpdateTxn),
                    validateChargedUsdWithinWithTxnSize(
                            tokenUpdateTxn,
                            txnSize -> expectedTokenUpdateFullFeeUsd(
                                    Map.of(SIGNATURES, 21L, PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(tokenUpdateTxn, PAYER));
        }

        @HapiTest
        @DisplayName("TokenUpdate with very large payer key below oversize - extra processing bytes fee")
        final Stream<DynamicTest> tokenUpdateVeryLargePayerKeyBelowOversizeFee() {
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
                    newKeyNamed(ADMIN_KEY),
                    tokenCreate(TOKEN)
                            .tokenType(FUNGIBLE_COMMON)
                            .adminKey(ADMIN_KEY)
                            .treasury(TREASURY)
                            .payingWith(PAYER)
                            .sigControl(forKey(PAYER_KEY, allSigned)),
                    tokenUpdate(TOKEN)
                            .memo("Updated memo")
                            .payingWith(PAYER)
                            .sigControl(forKey(PAYER_KEY, allSigned))
                            .signedBy(PAYER, ADMIN_KEY)
                            .via(tokenUpdateTxn),
                    validateChargedUsdWithinWithTxnSize(
                            tokenUpdateTxn,
                            txnSize -> expectedTokenUpdateFullFeeUsd(
                                    Map.of(SIGNATURES, 42L, PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(tokenUpdateTxn, PAYER));
        }
    }

    @Nested
    @DisplayName("TokenUpdate Simple Fees Negative Test Cases")
    class TokenUpdateSimpleFeesNegativeTestCases {

        @Nested
        @DisplayName("TokenUpdate Failures on Ingest and Handle")
        class TokenUpdateFailuresOnIngest {

            @HapiTest
            @DisplayName("TokenUpdate - insufficient tx fee fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenUpdateInsufficientTxFeeFailsOnIngest() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        newKeyNamed(ADMIN_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .adminKey(ADMIN_KEY)
                                .treasury(TREASURY)
                                .payingWith(PAYER),
                        tokenUpdate(TOKEN)
                                .memo("Updated memo")
                                .payingWith(PAYER)
                                .signedBy(PAYER, ADMIN_KEY)
                                .fee(1L) // Fee too low
                                .via(tokenUpdateTxn)
                                .hasPrecheck(INSUFFICIENT_TX_FEE),
                        getTxnRecord(tokenUpdateTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TokenUpdate - threshold payer key with invalid signature fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenUpdateThresholdKeyInvalidSigFailsOnIngest() {
                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
                SigControl validSig = keyShape.signedWith(sigs(ON, ON));
                SigControl invalidSig = keyShape.signedWith(sigs(ON, OFF));

                return hapiTest(
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        newKeyNamed(ADMIN_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .adminKey(ADMIN_KEY)
                                .treasury(TREASURY)
                                .payingWith(PAYER)
                                .sigControl(forKey(PAYER_KEY, validSig)),
                        tokenUpdate(TOKEN)
                                .memo("Updated memo")
                                .payingWith(PAYER)
                                .sigControl(forKey(PAYER_KEY, invalidSig))
                                .signedBy(PAYER, ADMIN_KEY)
                                .via(tokenUpdateTxn)
                                .hasPrecheck(INVALID_SIGNATURE),
                        getTxnRecord(tokenUpdateTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TokenUpdate - insufficient payer balance fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenUpdateInsufficientPayerBalanceFailsOnIngest() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HBAR / 100_000L),
                        cryptoCreate(TREASURY).balance(0L),
                        newKeyNamed(ADMIN_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .adminKey(ADMIN_KEY)
                                .treasury(TREASURY),
                        tokenUpdate(TOKEN)
                                .memo("Updated memo")
                                .payingWith(PAYER)
                                .signedBy(PAYER, ADMIN_KEY)
                                .via(tokenUpdateTxn)
                                .hasPrecheck(INSUFFICIENT_PAYER_BALANCE),
                        getTxnRecord(tokenUpdateTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TokenUpdate - memo too long fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenUpdateMemoTooLongFailsOnIngest() {
                final var LONG_MEMO = "x".repeat(1025);

                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        newKeyNamed(ADMIN_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .adminKey(ADMIN_KEY)
                                .treasury(TREASURY)
                                .payingWith(PAYER),
                        tokenUpdate(TOKEN)
                                .memo(LONG_MEMO)
                                .payingWith(PAYER)
                                .signedBy(PAYER, ADMIN_KEY)
                                .via(tokenUpdateTxn)
                                .hasPrecheck(MEMO_TOO_LONG),
                        getTxnRecord(tokenUpdateTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TokenUpdate - expired transaction fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenUpdateExpiredTransactionFailsOnIngest() {
                final var expiredTxnId = "expiredUpdateTxn";

                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        newKeyNamed(ADMIN_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .adminKey(ADMIN_KEY)
                                .treasury(TREASURY)
                                .payingWith(PAYER),
                        usableTxnIdNamed(expiredTxnId).modifyValidStart(-3_600L).payerId(PAYER),
                        tokenUpdate(TOKEN)
                                .memo("Updated memo")
                                .payingWith(PAYER)
                                .signedBy(PAYER, ADMIN_KEY)
                                .txnId(expiredTxnId)
                                .via(tokenUpdateTxn)
                                .hasPrecheck(TRANSACTION_EXPIRED),
                        getTxnRecord(tokenUpdateTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TokenUpdate - too far start time fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenUpdateTooFarStartTimeFailsOnIngest() {
                final var futureTxnId = "futureUpdateTxn";

                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        newKeyNamed(ADMIN_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .adminKey(ADMIN_KEY)
                                .treasury(TREASURY)
                                .payingWith(PAYER),
                        usableTxnIdNamed(futureTxnId).modifyValidStart(3_600L).payerId(PAYER),
                        tokenUpdate(TOKEN)
                                .memo("Updated memo")
                                .payingWith(PAYER)
                                .signedBy(PAYER, ADMIN_KEY)
                                .txnId(futureTxnId)
                                .via(tokenUpdateTxn)
                                .hasPrecheck(INVALID_TRANSACTION_START),
                        getTxnRecord(tokenUpdateTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TokenUpdate - invalid transaction duration fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenUpdateInvalidTransactionDurationFailsOnIngest() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        newKeyNamed(ADMIN_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .adminKey(ADMIN_KEY)
                                .treasury(TREASURY)
                                .payingWith(PAYER),
                        tokenUpdate(TOKEN)
                                .memo("Updated memo")
                                .payingWith(PAYER)
                                .signedBy(PAYER, ADMIN_KEY)
                                .validDurationSecs(0)
                                .via(tokenUpdateTxn)
                                .hasPrecheck(INVALID_TRANSACTION_DURATION),
                        getTxnRecord(tokenUpdateTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TokenUpdate - duplicate transaction fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenUpdateDuplicateTxnFailsOnIngest() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        newKeyNamed(ADMIN_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .adminKey(ADMIN_KEY)
                                .treasury(TREASURY)
                                .payingWith(PAYER),
                        tokenUpdate(TOKEN)
                                .memo("First update")
                                .payingWith(PAYER)
                                .signedBy(PAYER, ADMIN_KEY)
                                .via("updateFirst"),
                        tokenUpdate(TOKEN)
                                .memo("Duplicate update")
                                .payingWith(PAYER)
                                .signedBy(PAYER, ADMIN_KEY)
                                .txnId("updateFirst")
                                .via(tokenUpdateTxn)
                                .hasPrecheck(DUPLICATE_TRANSACTION));
            }

            @HapiTest
            @DisplayName("TokenUpdate - missing admin key signature fails on handle - full fee charged")
            final Stream<DynamicTest> tokenUpdateMissingAdminKeySignatureFailsOnHandle() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        newKeyNamed(ADMIN_KEY),
                        newKeyNamed(NEW_ADMIN_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .adminKey(ADMIN_KEY)
                                .treasury(TREASURY)
                                .payingWith(PAYER),
                        tokenUpdate(TOKEN)
                                .adminKey(NEW_ADMIN_KEY)
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .via(tokenUpdateTxn)
                                .hasKnownStatus(INVALID_SIGNATURE),
                        validateChargedUsdWithinWithTxnSize(
                                tokenUpdateTxn,
                                txnSize -> expectedTokenUpdateFullFeeUsd(
                                        Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateChargedAccount(tokenUpdateTxn, PAYER));
            }

            @HapiTest
            @DisplayName("TokenUpdate - invalid token fails on handle - full fee charged")
            final Stream<DynamicTest> tokenUpdateInvalidTokenFails() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        tokenUpdate("0.0.99999999") // Invalid token
                                .memo("Updated memo")
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .via(tokenUpdateTxn)
                                .hasKnownStatus(INVALID_TOKEN_ID),
                        validateChargedUsdWithinWithTxnSize(
                                tokenUpdateTxn,
                                txnSize -> expectedTokenUpdateFullFeeUsd(
                                        Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateChargedAccount(tokenUpdateTxn, PAYER));
            }

            @Nested
            @DisplayName("TokenUpdate Duplicate on Handle")
            class TokenUpdateDuplicateOnHandle {

                private static final String DUPLICATE_TXN_ID = "duplicateTokenUpdateTxnId";

                @LeakyHapiTest
                @Tag(ONLY_SUBPROCESS)
                @DisplayName("TokenUpdate - duplicate transaction fails on handle - payer charged for first only")
                final Stream<DynamicTest> tokenUpdateDuplicateFailsOnHandle() {
                    return hapiTest(
                            cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                            cryptoCreate(TREASURY).balance(0L),
                            newKeyNamed(ADMIN_KEY),
                            tokenCreate(TOKEN)
                                    .tokenType(FUNGIBLE_COMMON)
                                    .adminKey(ADMIN_KEY)
                                    .treasury(TREASURY)
                                    .payingWith(PAYER),
                            cryptoTransfer(movingHbar(ONE_HBAR).between(DEFAULT_PAYER, "3")),
                            usableTxnIdNamed(DUPLICATE_TXN_ID).payerId(PAYER),
                            tokenUpdate(TOKEN)
                                    .memo("Updated memo")
                                    .payingWith(PAYER)
                                    .signedBy(PAYER, ADMIN_KEY)
                                    .setNode(4)
                                    .txnId(DUPLICATE_TXN_ID)
                                    .via(tokenUpdateTxn),
                            tokenUpdate(TOKEN)
                                    .memo("Updated memo duplicate")
                                    .payingWith(PAYER)
                                    .signedBy(PAYER, ADMIN_KEY)
                                    .setNode(3)
                                    .txnId(DUPLICATE_TXN_ID)
                                    .hasPrecheck(DUPLICATE_TRANSACTION),
                            validateChargedUsdWithinWithTxnSize(
                                    tokenUpdateTxn,
                                    txnSize -> expectedTokenUpdateFullFeeUsd(
                                            Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                                    0.1),
                            validateChargedAccount(tokenUpdateTxn, PAYER));
                }
            }
        }

        @Nested
        @DisplayName("TokenUpdate Failures on Pre-Handle")
        class TokenUpdateFailuresOnPreHandle {

            @LeakyEmbeddedHapiTest(reason = MUST_SKIP_INGEST)
            @DisplayName("TokenUpdate - invalid payer signature fails on pre-handle - network fee only")
            final Stream<DynamicTest> tokenUpdateInvalidPayerSigFailsOnPreHandle() {
                final String INNER_ID = "token-update-txn-inner-id";

                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
                SigControl invalidSig = keyShape.signedWith(sigs(ON, OFF));

                return hapiTest(
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        newKeyNamed(ADMIN_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .adminKey(ADMIN_KEY)
                                .treasury(TREASURY),
                        cryptoTransfer(movingHbar(ONE_HBAR).between(DEFAULT_PAYER, "4")),
                        tokenUpdate(TOKEN)
                                .memo("Updated memo")
                                .payingWith(PAYER)
                                .sigControl(forKey(PAYER_KEY, invalidSig))
                                .signedBy(PAYER, ADMIN_KEY)
                                .setNode("4")
                                .via(INNER_ID)
                                .hasKnownStatus(INVALID_PAYER_SIGNATURE),
                        getTxnRecord(INNER_ID).assertingNothingAboutHashes().logged(),
                        validateChargedUsdWithinWithTxnSize(
                                INNER_ID,
                                txnSize -> expectedNetworkOnlyFeeUsd(
                                        Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateChargedAccount(INNER_ID, "4"));
            }
        }
    }
}
