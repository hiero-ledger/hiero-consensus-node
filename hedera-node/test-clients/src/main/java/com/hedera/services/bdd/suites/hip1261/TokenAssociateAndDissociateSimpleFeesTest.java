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
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenDissociate;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingHbar;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.assertionsHold;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyListNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.usableTxnIdNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedAccount;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsdWithin;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.allOnSigControl;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedNetworkOnlyFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTokenAssociateFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTokenDissociateFullFeeUsd;
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
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_ALREADY_ASSOCIATED_TO_ACCOUNT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_NOT_ASSOCIATED_TO_ACCOUNT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TRANSACTION_EXPIRED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TRANSACTION_OVERSIZE;
import static com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON;
import static org.hiero.hapi.support.fees.Extra.PROCESSING_BYTES;
import static org.hiero.hapi.support.fees.Extra.SIGNATURES;
import static org.hiero.hapi.support.fees.Extra.TOKEN_ASSOCIATE;
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
 * Tests for TokenAssociate and TokenDissociate simple fees with extras.
 * Validates that fees are correctly calculated based on:
 * - Number of signatures (extras beyond included)
 * - Number of tokens being associated/dissociated (each token costs base fee)
 */
@Tag(SIMPLE_FEES)
@HapiTestLifecycle
public class TokenAssociateAndDissociateSimpleFeesTest {

    private static final String PAYER = "payer";
    private static final String TREASURY = "treasury";
    private static final String ACCOUNT = "account";
    private static final String PAYER_KEY = "payerKey";
    private static final String TOKEN1 = "token1";
    private static final String TOKEN2 = "token2";
    private static final String TOKEN3 = "token3";
    private static final String tokenAssociateTxn = "tokenAssociateTxn";
    private static final String tokenDissociateTxn = "tokenDissociateTxn";

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
                            .via(tokenAssociateTxn),
                    validateChargedUsdWithinWithTxnSize(
                            tokenAssociateTxn,
                            txnSize -> expectedTokenAssociateFullFeeUsd(Map.of(
                                    SIGNATURES, 1L,
                                    TOKEN_ASSOCIATE, 1L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(tokenAssociateTxn, ACCOUNT));
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
                            .via(tokenAssociateTxn),
                    validateChargedUsdWithinWithTxnSize(
                            tokenAssociateTxn,
                            txnSize -> expectedTokenAssociateFullFeeUsd(Map.of(
                                    SIGNATURES, 1L,
                                    TOKEN_ASSOCIATE, 3L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(tokenAssociateTxn, ACCOUNT));
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
                            .via(tokenAssociateTxn),
                    validateChargedUsdWithinWithTxnSize(
                            tokenAssociateTxn,
                            txnSize -> expectedTokenAssociateFullFeeUsd(Map.of(
                                    SIGNATURES, 2L,
                                    TOKEN_ASSOCIATE, 1L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(tokenAssociateTxn, ACCOUNT));
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
                            .via(tokenAssociateTxn),
                    validateChargedUsdWithinWithTxnSize(
                            tokenAssociateTxn,
                            txnSize -> expectedTokenAssociateFullFeeUsd(Map.of(
                                    SIGNATURES, 1L,
                                    TOKEN_ASSOCIATE, 5L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(tokenAssociateTxn, ACCOUNT));
        }

        @HapiTest
        @DisplayName(
                "TokenAssociate - large key txn above NODE_INCLUDED_BYTES threshold - extra PROCESSING_BYTES charged")
        final Stream<DynamicTest> tokenAssociateLargeKeyExtraProcessingBytesFee() {
            return hapiTest(
                    cryptoCreate(TREASURY),
                    newKeyNamed(PAYER_KEY).shape(thresholdKeyWithPrimitives(20)),
                    cryptoCreate(ACCOUNT).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                    tokenCreate(TOKEN1).tokenType(FUNGIBLE_COMMON).treasury(TREASURY),
                    tokenAssociate(ACCOUNT, TOKEN1)
                            .sigControl(forKey(PAYER_KEY, allOnSigControl(20)))
                            .payingWith(ACCOUNT)
                            .signedBy(ACCOUNT)
                            .via(tokenAssociateTxn),
                    assertionsHold((spec, log) -> {
                        final int txnSize = signedTxnSizeFor(spec, tokenAssociateTxn);
                        log.info(
                                "Large-key TokenAssociate signed size: {} bytes (threshold: {})",
                                txnSize,
                                NODE_INCLUDED_BYTES);
                        assertTrue(
                                txnSize > NODE_INCLUDED_BYTES,
                                "Expected txn size (" + txnSize + ") to exceed " + NODE_INCLUDED_BYTES + " bytes");
                    }),
                    validateChargedUsdWithinWithTxnSize(
                            tokenAssociateTxn,
                            txnSize -> expectedTokenAssociateFullFeeUsd(
                                    Map.of(SIGNATURES, 20L, PROCESSING_BYTES, (long) txnSize)),
                            0.1));
        }

        @HapiTest
        @DisplayName("TokenAssociate - very large txn (just below 6KB) - full charging with extra PROCESSING_BYTES")
        final Stream<DynamicTest> tokenAssociateVeryLargeKeyBelowOversizeFee() {
            return hapiTest(
                    cryptoCreate(TREASURY),
                    newKeyNamed(PAYER_KEY).shape(thresholdKeyWithPrimitives(57)),
                    cryptoCreate(ACCOUNT).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                    tokenCreate(TOKEN1).tokenType(FUNGIBLE_COMMON).treasury(TREASURY),
                    tokenAssociate(ACCOUNT, TOKEN1)
                            .sigControl(forKey(PAYER_KEY, allOnSigControl(57)))
                            .payingWith(ACCOUNT)
                            .signedBy(ACCOUNT)
                            .via(tokenAssociateTxn),
                    assertionsHold((spec, log) -> {
                        final int txnSize = signedTxnSizeFor(spec, tokenAssociateTxn);
                        log.info("Very-large TokenAssociate signed size: {} bytes", txnSize);
                        assertTrue(txnSize < 6_000, "Expected txn size (" + txnSize + ") to not exceed 6000 bytes");
                    }),
                    validateChargedUsdWithinWithTxnSize(
                            tokenAssociateTxn,
                            txnSize -> expectedTokenAssociateFullFeeUsd(
                                    Map.of(SIGNATURES, 57L, PROCESSING_BYTES, (long) txnSize)),
                            0.1));
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
                            .via(tokenDissociateTxn),
                    validateChargedUsdWithinWithTxnSize(
                            tokenDissociateTxn,
                            txnSize -> expectedTokenDissociateFullFeeUsd(Map.of(
                                    SIGNATURES, 1L,
                                    TOKEN_ASSOCIATE, 1L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(tokenDissociateTxn, ACCOUNT));
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
                            .via(tokenDissociateTxn),
                    validateChargedUsdWithinWithTxnSize(
                            tokenDissociateTxn,
                            txnSize -> expectedTokenDissociateFullFeeUsd(Map.of(
                                    SIGNATURES, 1L,
                                    TOKEN_ASSOCIATE, 3L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(tokenDissociateTxn, ACCOUNT));
        }

        @HapiTest
        @DisplayName("TokenDissociate with threshold key - extra signatures")
        final Stream<DynamicTest> tokenDissociateWithThresholdKey() {
            KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
            SigControl validSig = keyShape.signedWith(sigs(ON, ON));

            return hapiTest(
                    cryptoCreate(TREASURY),
                    newKeyNamed(PAYER_KEY).shape(keyShape),
                    cryptoCreate(ACCOUNT).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                    tokenCreate(TOKEN1).tokenType(FUNGIBLE_COMMON).treasury(TREASURY),
                    tokenAssociate(ACCOUNT, TOKEN1).payingWith(ACCOUNT),
                    tokenDissociate(ACCOUNT, TOKEN1)
                            .payingWith(ACCOUNT)
                            .sigControl(forKey(PAYER_KEY, validSig))
                            .signedBy(ACCOUNT)
                            .via(tokenDissociateTxn),
                    validateChargedUsdWithinWithTxnSize(
                            tokenDissociateTxn,
                            txnSize -> expectedTokenDissociateFullFeeUsd(Map.of(
                                    SIGNATURES, 2L,
                                    TOKEN_ASSOCIATE, 1L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(tokenDissociateTxn, ACCOUNT));
        }

        @HapiTest
        @DisplayName(
                "TokenDissociate - large key txn above NODE_INCLUDED_BYTES threshold - extra PROCESSING_BYTES charged")
        final Stream<DynamicTest> tokenDissociateLargeKeyExtraProcessingBytesFee() {
            return hapiTest(
                    cryptoCreate(TREASURY),
                    newKeyNamed(PAYER_KEY).shape(thresholdKeyWithPrimitives(20)),
                    cryptoCreate(ACCOUNT).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                    tokenCreate(TOKEN1).tokenType(FUNGIBLE_COMMON).treasury(TREASURY),
                    tokenAssociate(ACCOUNT, TOKEN1).payingWith(ACCOUNT),
                    tokenDissociate(ACCOUNT, TOKEN1)
                            .sigControl(forKey(PAYER_KEY, allOnSigControl(20)))
                            .payingWith(ACCOUNT)
                            .signedBy(ACCOUNT)
                            .via(tokenDissociateTxn),
                    assertionsHold((spec, log) -> {
                        final int txnSize = signedTxnSizeFor(spec, tokenDissociateTxn);
                        log.info(
                                "Large-key TokenDissociate signed size: {} bytes (threshold: {})",
                                txnSize,
                                NODE_INCLUDED_BYTES);
                        assertTrue(
                                txnSize > NODE_INCLUDED_BYTES,
                                "Expected txn size (" + txnSize + ") to exceed " + NODE_INCLUDED_BYTES + " bytes");
                    }),
                    validateChargedUsdWithinWithTxnSize(
                            tokenDissociateTxn,
                            txnSize -> expectedTokenDissociateFullFeeUsd(
                                    Map.of(SIGNATURES, 20L, PROCESSING_BYTES, (long) txnSize)),
                            0.1));
        }

        @HapiTest
        @DisplayName("TokenDissociate - very large txn (just below 6KB) - full charging with extra PROCESSING_BYTES")
        final Stream<DynamicTest> tokenDissociateVeryLargeKeyBelowOversizeFee() {
            return hapiTest(
                    cryptoCreate(TREASURY),
                    newKeyNamed(PAYER_KEY).shape(thresholdKeyWithPrimitives(57)),
                    cryptoCreate(ACCOUNT).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                    tokenCreate(TOKEN1).tokenType(FUNGIBLE_COMMON).treasury(TREASURY),
                    tokenAssociate(ACCOUNT, TOKEN1).payingWith(ACCOUNT),
                    tokenDissociate(ACCOUNT, TOKEN1)
                            .sigControl(forKey(PAYER_KEY, allOnSigControl(57)))
                            .payingWith(ACCOUNT)
                            .signedBy(ACCOUNT)
                            .via(tokenDissociateTxn),
                    assertionsHold((spec, log) -> {
                        final int txnSize = signedTxnSizeFor(spec, tokenDissociateTxn);
                        log.info("Very-large TokenDissociate signed size: {} bytes", txnSize);
                        assertTrue(txnSize < 6_000, "Expected txn size (" + txnSize + ") to not exceed 6000 bytes");
                    }),
                    validateChargedUsdWithinWithTxnSize(
                            tokenDissociateTxn,
                            txnSize -> expectedTokenDissociateFullFeeUsd(
                                    Map.of(SIGNATURES, 57L, PROCESSING_BYTES, (long) txnSize)),
                            0.1));
        }
    }

    @Nested
    @DisplayName("TokenAssociate Simple Fees Negative Test Cases")
    class TokenAssociateNegativeTestCases {

        @Nested
        @DisplayName("TokenAssociate Failures on Ingest and Handle")
        class TokenAssociateFailuresOnIngest {
            @HapiTest
            @DisplayName("TokenAssociate - invalid signature fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenAssociateInvalidSignatureFailsOnIngest() {
                return hapiTest(
                        newKeyNamed("firstKey"),
                        newKeyNamed("secondKey"),
                        newKeyListNamed(PAYER_KEY, List.of("firstKey", "secondKey")),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        tokenCreate(TOKEN1).tokenType(FUNGIBLE_COMMON).payingWith(PAYER),
                        tokenAssociate(PAYER, TOKEN1)
                                .payingWith(PAYER)
                                .signedBy("firstKey")
                                .via(tokenAssociateTxn)
                                .hasPrecheck(INVALID_SIGNATURE),
                        getTxnRecord(tokenAssociateTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TokenAssociate - insufficient tx fee fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenAssociateInsufficientTxFeeFailsOnIngest() {
                return hapiTest(
                        cryptoCreate(TREASURY),
                        cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                        tokenCreate(TOKEN1).tokenType(FUNGIBLE_COMMON).treasury(TREASURY),
                        tokenAssociate(ACCOUNT, TOKEN1)
                                .payingWith(ACCOUNT)
                                .signedBy(ACCOUNT)
                                .fee(1L) // Fee too low
                                .via(tokenAssociateTxn)
                                .hasPrecheck(INSUFFICIENT_TX_FEE),
                        getTxnRecord(tokenAssociateTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TokenAssociate - threshold key invalid signature fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenAssociateThresholdInvalidSigFailsOnIngest() {
                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
                SigControl invalidSig = keyShape.signedWith(sigs(ON, OFF));

                return hapiTest(
                        cryptoCreate(TREASURY),
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(ACCOUNT).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        tokenCreate(TOKEN1).tokenType(FUNGIBLE_COMMON).treasury(TREASURY),
                        tokenAssociate(ACCOUNT, TOKEN1)
                                .payingWith(ACCOUNT)
                                .sigControl(forKey(PAYER_KEY, invalidSig))
                                .signedBy(ACCOUNT)
                                .via(tokenAssociateTxn)
                                .hasPrecheck(INVALID_SIGNATURE),
                        getTxnRecord(tokenAssociateTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName(
                    "TokenAssociate - threshold key with nested list invalid signature fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenAssociateThresholdWithListInvalidSigFailsOnIngest() {
                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE, listOf(2));
                SigControl invalidSig = keyShape.signedWith(sigs(ON, OFF, sigs(OFF, OFF)));

                return hapiTest(
                        cryptoCreate(TREASURY),
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(ACCOUNT)
                                .key(PAYER_KEY)
                                .sigControl(forKey(PAYER_KEY, keyShape.signedWith(sigs(ON, ON, sigs(ON, ON)))))
                                .balance(ONE_HUNDRED_HBARS),
                        tokenCreate(TOKEN1).tokenType(FUNGIBLE_COMMON).treasury(TREASURY),
                        tokenAssociate(ACCOUNT, TOKEN1)
                                .payingWith(ACCOUNT)
                                .sigControl(forKey(PAYER_KEY, invalidSig))
                                .signedBy(ACCOUNT)
                                .via(tokenAssociateTxn)
                                .hasPrecheck(INVALID_SIGNATURE),
                        getTxnRecord(tokenAssociateTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TokenAssociate - insufficient payer balance fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenAssociateInsufficientPayerBalanceFailsOnIngest() {
                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
                SigControl validSig = keyShape.signedWith(sigs(ON, ON));

                return hapiTest(
                        cryptoCreate(TREASURY),
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(ACCOUNT).key(PAYER_KEY).balance(1L), // too little balance
                        tokenCreate(TOKEN1).tokenType(FUNGIBLE_COMMON).treasury(TREASURY),
                        tokenAssociate(ACCOUNT, TOKEN1)
                                .payingWith(ACCOUNT)
                                .sigControl(forKey(PAYER_KEY, validSig))
                                .signedBy(ACCOUNT)
                                .via(tokenAssociateTxn)
                                .hasPrecheck(INSUFFICIENT_PAYER_BALANCE),
                        getTxnRecord(tokenAssociateTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TokenAssociate - memo too long fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenAssociateMemoTooLongFailsOnIngest() {
                final var LONG_MEMO = "x".repeat(1025);
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
                                .memo(LONG_MEMO)
                                .via(tokenAssociateTxn)
                                .hasPrecheck(MEMO_TOO_LONG),
                        getTxnRecord(tokenAssociateTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TokenAssociate - expired transaction fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenAssociateExpiredTransactionFailsOnIngest() {
                final var expiredTxnId = "expiredTokenAssociate";
                final var oneHourPast = -3_600L;
                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
                SigControl validSig = keyShape.signedWith(sigs(ON, ON));

                return hapiTest(
                        cryptoCreate(TREASURY),
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(ACCOUNT).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        tokenCreate(TOKEN1).tokenType(FUNGIBLE_COMMON).treasury(TREASURY),
                        usableTxnIdNamed(expiredTxnId)
                                .modifyValidStart(oneHourPast)
                                .payerId(ACCOUNT),
                        tokenAssociate(ACCOUNT, TOKEN1)
                                .payingWith(ACCOUNT)
                                .sigControl(forKey(PAYER_KEY, validSig))
                                .signedBy(ACCOUNT)
                                .txnId(expiredTxnId)
                                .via(tokenAssociateTxn)
                                .hasPrecheck(TRANSACTION_EXPIRED),
                        getTxnRecord(tokenAssociateTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TokenAssociate - too far start time fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenAssociateTooFarStartTimeFailsOnIngest() {
                final var futureTxnId = "futureTokenAssociate";
                final var oneHourFuture = 3_600L;
                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
                SigControl validSig = keyShape.signedWith(sigs(ON, ON));

                return hapiTest(
                        cryptoCreate(TREASURY),
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(ACCOUNT).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        tokenCreate(TOKEN1).tokenType(FUNGIBLE_COMMON).treasury(TREASURY),
                        usableTxnIdNamed(futureTxnId)
                                .modifyValidStart(oneHourFuture)
                                .payerId(ACCOUNT),
                        tokenAssociate(ACCOUNT, TOKEN1)
                                .payingWith(ACCOUNT)
                                .sigControl(forKey(PAYER_KEY, validSig))
                                .signedBy(ACCOUNT)
                                .txnId(futureTxnId)
                                .via(tokenAssociateTxn)
                                .hasPrecheck(INVALID_TRANSACTION_START),
                        getTxnRecord(tokenAssociateTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TokenAssociate - invalid duration fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenAssociateInvalidDurationFailsOnIngest() {
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
                                .validDurationSecs(0)
                                .via(tokenAssociateTxn)
                                .hasPrecheck(INVALID_TRANSACTION_DURATION),
                        getTxnRecord(tokenAssociateTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TokenAssociate - duplicate transaction fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenAssociateDuplicateTxnFailsOnIngest() {

                return hapiTest(
                        cryptoCreate(TREASURY),
                        cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                        tokenCreate(TOKEN1).tokenType(FUNGIBLE_COMMON).treasury(TREASURY),
                        tokenCreate(TOKEN2).tokenType(FUNGIBLE_COMMON).treasury(TREASURY),
                        // first successful associate
                        tokenAssociate(ACCOUNT, TOKEN1)
                                .payingWith(ACCOUNT)
                                .signedBy(ACCOUNT)
                                .via(tokenAssociateTxn),
                        // duplicate reusing same txnId
                        tokenAssociate(ACCOUNT, TOKEN2)
                                .payingWith(ACCOUNT)
                                .signedBy(ACCOUNT)
                                .txnId(tokenAssociateTxn)
                                .via("tokenAssociateDuplicateTxn")
                                .hasPrecheck(DUPLICATE_TRANSACTION));
            }

            @HapiTest
            @DisplayName("TokenAssociate - very large txn (above 6KB) - fails on ingest")
            final Stream<DynamicTest> tokenAssociateTransactionOversizeFailsOnIngest() {
                return hapiTest(
                        cryptoCreate(TREASURY),
                        newKeyNamed(PAYER_KEY).shape(thresholdKeyWithPrimitives(70)),
                        cryptoCreate(ACCOUNT).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        tokenCreate(TOKEN1).tokenType(FUNGIBLE_COMMON).treasury(TREASURY),
                        tokenAssociate(ACCOUNT, TOKEN1)
                                .sigControl(forKey(PAYER_KEY, allOnSigControl(70)))
                                .payingWith(ACCOUNT)
                                .signedBy(ACCOUNT)
                                .via(tokenAssociateTxn)
                                .hasPrecheck(TRANSACTION_OVERSIZE),
                        getTxnRecord(tokenAssociateTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TokenAssociate - invalid token fails on handle - full fee charged")
            final Stream<DynamicTest> tokenAssociateInvalidTokenFails() {
                return hapiTest(
                        cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                        tokenAssociate(ACCOUNT, "0.0.99999999") // Invalid token
                                .payingWith(ACCOUNT)
                                .signedBy(ACCOUNT)
                                .via(tokenAssociateTxn)
                                .hasKnownStatus(INVALID_TOKEN_ID),
                        validateChargedUsdWithinWithTxnSize(
                                tokenAssociateTxn,
                                txnSize -> expectedTokenAssociateFullFeeUsd(Map.of(
                                        SIGNATURES, 1L,
                                        TOKEN_ASSOCIATE, 1L,
                                        PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateChargedAccount(tokenAssociateTxn, ACCOUNT));
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
                                .via(tokenAssociateTxn)
                                .hasKnownStatus(TOKEN_ALREADY_ASSOCIATED_TO_ACCOUNT),
                        validateChargedUsdWithinWithTxnSize(
                                tokenAssociateTxn,
                                txnSize -> expectedTokenAssociateFullFeeUsd(Map.of(
                                        SIGNATURES, 1L,
                                        TOKEN_ASSOCIATE, 1L,
                                        PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateChargedAccount(tokenAssociateTxn, ACCOUNT));
            }

            @Tag(ONLY_SUBPROCESS)
            @LeakyHapiTest
            @DisplayName("TokenAssociate - duplicate transaction fails on handle - payer charged full fee")
            final Stream<DynamicTest> tokenAssociateDuplicateFailsOnHandle() {
                final String DUPLICATE_TXN_ID = "tokenAssociateDuplicateTxnId";
                return hapiTest(
                        cryptoCreate(TREASURY),
                        cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                        tokenCreate(TOKEN1).tokenType(FUNGIBLE_COMMON).treasury(TREASURY),
                        cryptoTransfer(movingHbar(ONE_HBAR).between(GENESIS, "3")),
                        usableTxnIdNamed(DUPLICATE_TXN_ID).payerId(ACCOUNT),
                        tokenAssociate(ACCOUNT, TOKEN1)
                                .payingWith(ACCOUNT)
                                .signedBy(ACCOUNT)
                                .setNode(4)
                                .txnId(DUPLICATE_TXN_ID)
                                .via(tokenAssociateTxn)
                                .logged(),
                        tokenAssociate(ACCOUNT, TOKEN1)
                                .payingWith(ACCOUNT)
                                .signedBy(ACCOUNT)
                                .setNode(3)
                                .txnId(DUPLICATE_TXN_ID)
                                .via("tokenAssociateDuplicateTxn")
                                .hasPrecheck(DUPLICATE_TRANSACTION),
                        validateChargedUsdWithin(tokenAssociateTxn, expectedTokenAssociateFullFeeUsd(1L, 1L), 0.1),
                        validateChargedAccount(tokenAssociateTxn, ACCOUNT));
            }
        }

        @Nested
        @DisplayName("TokenAssociate Failures on Pre-Handle")
        class TokenAssociateFailuresOnPreHandle {

            @LeakyEmbeddedHapiTest(reason = MUST_SKIP_INGEST)
            @DisplayName("TokenAssociate - invalid payer signature fails on pre-handle - network fee only")
            final Stream<DynamicTest> tokenAssociateInvalidPayerSigFailsOnPreHandle() {

                final String INNER_ID = "token-associate-txn-inner-id";

                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
                SigControl invalidSig = keyShape.signedWith(sigs(ON, OFF));

                return hapiTest(
                        cryptoCreate(TREASURY),
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(ACCOUNT).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        tokenCreate(TOKEN1).tokenType(FUNGIBLE_COMMON).treasury(TREASURY),
                        cryptoTransfer(movingHbar(ONE_HBAR).between(DEFAULT_PAYER, "0.0.4")),
                        tokenAssociate(ACCOUNT, TOKEN1)
                                .payingWith(ACCOUNT)
                                .sigControl(forKey(PAYER_KEY, invalidSig))
                                .signedBy(ACCOUNT)
                                .setNode("0.0.4")
                                .via(INNER_ID)
                                .hasKnownStatus(INVALID_PAYER_SIGNATURE),
                        getTxnRecord(INNER_ID).assertingNothingAboutHashes().logged(),
                        validateChargedUsdWithinWithTxnSize(
                                INNER_ID,
                                txnSize -> expectedNetworkOnlyFeeUsd(
                                        Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateChargedAccount(INNER_ID, "0.0.4"));
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

                return hapiTest(
                        cryptoCreate(TREASURY),
                        cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                        tokenCreate(TOKEN1).tokenType(FUNGIBLE_COMMON).treasury(TREASURY),
                        tokenAssociate(ACCOUNT, TOKEN1).payingWith(ACCOUNT),
                        tokenDissociate(ACCOUNT, TOKEN1)
                                .payingWith(ACCOUNT)
                                .signedBy(ACCOUNT)
                                .fee(1L) // Fee too low
                                .via(tokenDissociateTxn)
                                .hasPrecheck(INSUFFICIENT_TX_FEE),
                        getTxnRecord(tokenDissociateTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TokenDissociate - key list invalid signature fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenDissociateInvalidSignatureFailsOnIngest() {
                return hapiTest(
                        newKeyNamed("firstKey"),
                        newKeyNamed("secondKey"),
                        newKeyListNamed(PAYER_KEY, List.of("firstKey", "secondKey")),
                        cryptoCreate(TREASURY),
                        cryptoCreate(ACCOUNT).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        tokenCreate(TOKEN1).tokenType(FUNGIBLE_COMMON).treasury(TREASURY),
                        tokenAssociate(ACCOUNT, TOKEN1).payingWith(ACCOUNT),
                        tokenDissociate(ACCOUNT, TOKEN1)
                                .payingWith(ACCOUNT)
                                .signedBy("firstKey")
                                .via(tokenDissociateTxn)
                                .hasPrecheck(INVALID_SIGNATURE),
                        getTxnRecord(tokenDissociateTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TokenDissociate - threshold key invalid signature fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenDissociateThresholdInvalidSigFailsOnIngest() {
                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
                SigControl invalidSig = keyShape.signedWith(sigs(ON, OFF));

                return hapiTest(
                        cryptoCreate(TREASURY),
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(ACCOUNT).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        tokenCreate(TOKEN1).tokenType(FUNGIBLE_COMMON).treasury(TREASURY),
                        tokenAssociate(ACCOUNT, TOKEN1).payingWith(ACCOUNT),
                        tokenDissociate(ACCOUNT, TOKEN1)
                                .payingWith(ACCOUNT)
                                .sigControl(forKey(PAYER_KEY, invalidSig))
                                .signedBy(ACCOUNT)
                                .via(tokenDissociateTxn)
                                .hasPrecheck(INVALID_SIGNATURE),
                        getTxnRecord(tokenDissociateTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName(
                    "TokenDissociate - threshold key with nested list invalid signature fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenDissociateThresholdWithListInvalidSigFailsOnIngest() {
                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE, listOf(2));
                SigControl invalidSig = keyShape.signedWith(sigs(ON, OFF, sigs(OFF, OFF)));

                return hapiTest(
                        cryptoCreate(TREASURY),
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(ACCOUNT)
                                .key(PAYER_KEY)
                                .sigControl(forKey(PAYER_KEY, keyShape.signedWith(sigs(ON, ON, sigs(ON, ON)))))
                                .balance(ONE_HUNDRED_HBARS),
                        tokenCreate(TOKEN1).tokenType(FUNGIBLE_COMMON).treasury(TREASURY),
                        tokenAssociate(ACCOUNT, TOKEN1).payingWith(ACCOUNT),
                        tokenDissociate(ACCOUNT, TOKEN1)
                                .payingWith(ACCOUNT)
                                .sigControl(forKey(PAYER_KEY, invalidSig))
                                .signedBy(ACCOUNT)
                                .via(tokenDissociateTxn)
                                .hasPrecheck(INVALID_SIGNATURE),
                        getTxnRecord(tokenDissociateTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TokenDissociate - insufficient payer balance fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenDissociateInsufficientPayerBalanceFailsOnIngest() {
                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
                SigControl validSig = keyShape.signedWith(sigs(ON, ON));

                return hapiTest(
                        cryptoCreate(TREASURY),
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(ACCOUNT).key(PAYER_KEY).balance(1L), // too little balance
                        tokenCreate(TOKEN1).tokenType(FUNGIBLE_COMMON).treasury(TREASURY),
                        // associate first (using default payer to avoid balance issue)
                        tokenAssociate(ACCOUNT, TOKEN1),
                        tokenDissociate(ACCOUNT, TOKEN1)
                                .payingWith(ACCOUNT)
                                .sigControl(forKey(PAYER_KEY, validSig))
                                .signedBy(ACCOUNT)
                                .via(tokenDissociateTxn)
                                .hasPrecheck(INSUFFICIENT_PAYER_BALANCE),
                        getTxnRecord(tokenDissociateTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TokenDissociate - memo too long fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenDissociateMemoTooLongFailsOnIngest() {
                final var LONG_MEMO = "x".repeat(1025);
                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
                SigControl validSig = keyShape.signedWith(sigs(ON, ON));

                return hapiTest(
                        cryptoCreate(TREASURY),
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(ACCOUNT).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        tokenCreate(TOKEN1).tokenType(FUNGIBLE_COMMON).treasury(TREASURY),
                        tokenAssociate(ACCOUNT, TOKEN1).payingWith(ACCOUNT),
                        tokenDissociate(ACCOUNT, TOKEN1)
                                .payingWith(ACCOUNT)
                                .sigControl(forKey(PAYER_KEY, validSig))
                                .signedBy(ACCOUNT)
                                .memo(LONG_MEMO)
                                .via(tokenDissociateTxn)
                                .hasPrecheck(MEMO_TOO_LONG),
                        getTxnRecord(tokenDissociateTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TokenDissociate - expired transaction fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenDissociateExpiredTransactionFailsOnIngest() {
                final var expiredTxnId = "expiredTokenDissociate";
                final var oneHourPast = -3_600L;
                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
                SigControl validSig = keyShape.signedWith(sigs(ON, ON));

                return hapiTest(
                        cryptoCreate(TREASURY),
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(ACCOUNT).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        tokenCreate(TOKEN1).tokenType(FUNGIBLE_COMMON).treasury(TREASURY),
                        tokenAssociate(ACCOUNT, TOKEN1).payingWith(ACCOUNT),
                        usableTxnIdNamed(expiredTxnId)
                                .modifyValidStart(oneHourPast)
                                .payerId(ACCOUNT),
                        tokenDissociate(ACCOUNT, TOKEN1)
                                .payingWith(ACCOUNT)
                                .sigControl(forKey(PAYER_KEY, validSig))
                                .signedBy(ACCOUNT)
                                .txnId(expiredTxnId)
                                .via(tokenDissociateTxn)
                                .hasPrecheck(TRANSACTION_EXPIRED),
                        getTxnRecord(tokenDissociateTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TokenDissociate - too far start time fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenDissociateTooFarStartTimeFailsOnIngest() {
                final var futureTxnId = "futureTokenDissociate";
                final var oneHourFuture = 3_600L;
                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
                SigControl validSig = keyShape.signedWith(sigs(ON, ON));

                return hapiTest(
                        cryptoCreate(TREASURY),
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(ACCOUNT).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        tokenCreate(TOKEN1).tokenType(FUNGIBLE_COMMON).treasury(TREASURY),
                        tokenAssociate(ACCOUNT, TOKEN1).payingWith(ACCOUNT),
                        usableTxnIdNamed(futureTxnId)
                                .modifyValidStart(oneHourFuture)
                                .payerId(ACCOUNT),
                        tokenDissociate(ACCOUNT, TOKEN1)
                                .payingWith(ACCOUNT)
                                .sigControl(forKey(PAYER_KEY, validSig))
                                .signedBy(ACCOUNT)
                                .txnId(futureTxnId)
                                .via(tokenDissociateTxn)
                                .hasPrecheck(INVALID_TRANSACTION_START),
                        getTxnRecord(tokenDissociateTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TokenDissociate - invalid duration fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenDissociateInvalidDurationFailsOnIngest() {
                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
                SigControl validSig = keyShape.signedWith(sigs(ON, ON));

                return hapiTest(
                        cryptoCreate(TREASURY),
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(ACCOUNT).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        tokenCreate(TOKEN1).tokenType(FUNGIBLE_COMMON).treasury(TREASURY),
                        tokenAssociate(ACCOUNT, TOKEN1).payingWith(ACCOUNT),
                        tokenDissociate(ACCOUNT, TOKEN1)
                                .payingWith(ACCOUNT)
                                .sigControl(forKey(PAYER_KEY, validSig))
                                .signedBy(ACCOUNT)
                                .validDurationSecs(0)
                                .via(tokenDissociateTxn)
                                .hasPrecheck(INVALID_TRANSACTION_DURATION),
                        getTxnRecord(tokenDissociateTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TokenDissociate - duplicate transaction fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenDissociateDuplicateTxnFailsOnIngest() {
                return hapiTest(
                        cryptoCreate(TREASURY),
                        cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                        tokenCreate(TOKEN1).tokenType(FUNGIBLE_COMMON).treasury(TREASURY),
                        tokenCreate(TOKEN2).tokenType(FUNGIBLE_COMMON).treasury(TREASURY),
                        tokenAssociate(ACCOUNT, TOKEN1, TOKEN2).payingWith(ACCOUNT),
                        // first successful dissociate
                        tokenDissociate(ACCOUNT, TOKEN1)
                                .payingWith(ACCOUNT)
                                .signedBy(ACCOUNT)
                                .via(tokenDissociateTxn),
                        // duplicate reusing same txnId
                        tokenDissociate(ACCOUNT, TOKEN2)
                                .payingWith(ACCOUNT)
                                .signedBy(ACCOUNT)
                                .txnId(tokenDissociateTxn)
                                .via("tokenDissociateDuplicateTxn")
                                .hasPrecheck(DUPLICATE_TRANSACTION));
            }

            @HapiTest
            @DisplayName("TokenDissociate - very large txn (above 6KB) - fails on ingest")
            final Stream<DynamicTest> tokenDissociateTransactionOversizeFailsOnIngest() {
                return hapiTest(
                        cryptoCreate(TREASURY),
                        newKeyNamed("adminKey"),
                        newKeyNamed(PAYER_KEY).shape(thresholdKeyWithPrimitives(70)),
                        cryptoCreate(ACCOUNT).key("adminKey").balance(ONE_HUNDRED_HBARS),
                        tokenCreate(TOKEN1).tokenType(FUNGIBLE_COMMON).treasury(TREASURY),
                        tokenAssociate(ACCOUNT, TOKEN1).payingWith(ACCOUNT),
                        cryptoUpdate(ACCOUNT).key(PAYER_KEY),
                        tokenDissociate(ACCOUNT, TOKEN1)
                                .sigControl(forKey(PAYER_KEY, allOnSigControl(70)))
                                .payingWith(ACCOUNT)
                                .signedBy(ACCOUNT)
                                .via(tokenDissociateTxn)
                                .hasPrecheck(TRANSACTION_OVERSIZE),
                        getTxnRecord(tokenDissociateTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TokenDissociate - invalid token fails on handle - full fee charged")
            final Stream<DynamicTest> tokenDissociateInvalidTokenFails() {

                return hapiTest(
                        cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                        tokenDissociate(ACCOUNT, "0.0.99999999") // Invalid token
                                .payingWith(ACCOUNT)
                                .signedBy(ACCOUNT)
                                .via(tokenDissociateTxn)
                                .hasKnownStatus(TOKEN_NOT_ASSOCIATED_TO_ACCOUNT),
                        validateChargedUsdWithinWithTxnSize(
                                tokenDissociateTxn,
                                txnSize -> expectedTokenDissociateFullFeeUsd(
                                        Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateChargedAccount(tokenDissociateTxn, ACCOUNT));
            }

            @HapiTest
            @DisplayName("TokenDissociate - not associated fails on handle - full fee charged")
            final Stream<DynamicTest> tokenDissociateNotAssociatedFails() {

                return hapiTest(
                        cryptoCreate(TREASURY),
                        cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                        tokenCreate(TOKEN1).tokenType(FUNGIBLE_COMMON).treasury(TREASURY),
                        // Not associating the token
                        tokenDissociate(ACCOUNT, TOKEN1)
                                .payingWith(ACCOUNT)
                                .signedBy(ACCOUNT)
                                .via(tokenDissociateTxn)
                                .hasKnownStatus(TOKEN_NOT_ASSOCIATED_TO_ACCOUNT),
                        validateChargedUsdWithinWithTxnSize(
                                tokenDissociateTxn,
                                txnSize -> expectedTokenDissociateFullFeeUsd(
                                        Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateChargedAccount(tokenDissociateTxn, ACCOUNT));
            }

            @Tag(ONLY_SUBPROCESS)
            @LeakyHapiTest
            @DisplayName("TokenDissociate - duplicate transaction fails on handle - payer charged full fee")
            final Stream<DynamicTest> tokenDissociateDuplicateFailsOnHandle() {
                final String DUPLICATE_TXN_ID = "tokenDissociateDuplicateTxnId";
                return hapiTest(
                        cryptoCreate(TREASURY),
                        cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                        tokenCreate(TOKEN1).tokenType(FUNGIBLE_COMMON).treasury(TREASURY),
                        tokenAssociate(ACCOUNT, TOKEN1).payingWith(ACCOUNT),
                        cryptoTransfer(movingHbar(ONE_HBAR).between(GENESIS, "3")),
                        usableTxnIdNamed(DUPLICATE_TXN_ID).payerId(ACCOUNT),
                        tokenDissociate(ACCOUNT, TOKEN1)
                                .payingWith(ACCOUNT)
                                .signedBy(ACCOUNT)
                                .setNode(4)
                                .txnId(DUPLICATE_TXN_ID)
                                .via(tokenDissociateTxn)
                                .logged(),
                        tokenDissociate(ACCOUNT, TOKEN1)
                                .payingWith(ACCOUNT)
                                .signedBy(ACCOUNT)
                                .setNode(3)
                                .txnId(DUPLICATE_TXN_ID)
                                .via("tokenDissociateDuplicateTxn")
                                .hasPrecheck(DUPLICATE_TRANSACTION),
                        validateChargedUsdWithin(tokenDissociateTxn, expectedTokenDissociateFullFeeUsd(1L), 0.1),
                        validateChargedAccount(tokenDissociateTxn, ACCOUNT));
            }
        }

        @Nested
        @DisplayName("TokenDissociate Failures on Pre-Handle")
        class TokenDissociateFailuresOnPreHandle {

            @LeakyEmbeddedHapiTest(reason = MUST_SKIP_INGEST)
            @DisplayName("TokenDissociate - invalid payer signature fails on pre-handle - network fee only")
            final Stream<DynamicTest> tokenDissociateInvalidPayerSigFailsOnPreHandle() {

                final String INNER_ID = "token-dissociate-txn-inner-id";

                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
                SigControl invalidSig = keyShape.signedWith(sigs(ON, OFF));

                return hapiTest(
                        cryptoCreate(TREASURY),
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(ACCOUNT).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        tokenCreate(TOKEN1).tokenType(FUNGIBLE_COMMON).treasury(TREASURY),
                        tokenAssociate(ACCOUNT, TOKEN1).payingWith(ACCOUNT),
                        cryptoTransfer(movingHbar(ONE_HBAR).between(DEFAULT_PAYER, "0.0.4")),
                        tokenDissociate(ACCOUNT, TOKEN1)
                                .payingWith(ACCOUNT)
                                .sigControl(forKey(PAYER_KEY, invalidSig))
                                .signedBy(ACCOUNT)
                                .setNode("0.0.4")
                                .via(INNER_ID)
                                .hasKnownStatus(INVALID_PAYER_SIGNATURE),
                        getTxnRecord(INNER_ID).assertingNothingAboutHashes().logged(),
                        validateChargedUsdWithinWithTxnSize(
                                INNER_ID,
                                txnSize -> expectedNetworkOnlyFeeUsd(
                                        Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateChargedAccount(INNER_ID, "0.0.4"));
            }
        }
    }
}
