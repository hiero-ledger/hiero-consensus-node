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
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.burnToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingHbar;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.usableTxnIdNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedAccount;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTokenBurnFungibleFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTokenBurnNetworkFeeOnlyUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTokenBurnNftFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.validateChargedUsdWithinWithTxnSize;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.DUPLICATE_TRANSACTION;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_PAYER_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_TX_FEE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_PAYER_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOKEN_BURN_AMOUNT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TRANSACTION_DURATION;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TRANSACTION_START;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.MEMO_TOO_LONG;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.RECORD_NOT_FOUND;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_HAS_NO_SUPPLY_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TRANSACTION_EXPIRED;
import static com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;
import static org.hiero.hapi.support.fees.Extra.PROCESSING_BYTES;
import static org.hiero.hapi.support.fees.Extra.SIGNATURES;

import com.google.protobuf.ByteString;
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
 * Tests for TokenBurn simple fees with extras.
 * Validates that fees are correctly calculated based on:
 * - Number of signatures (extras beyond included)
 * - For fungible tokens: amount doesn't affect fees
 * - For NFTs: number of serials affects fees (extras beyond included)
 */
@Tag(SIMPLE_FEES)
@HapiTestLifecycle
public class TokenBurnSimpleFeesTest {

    private static final String PAYER = "payer";
    private static final String SUPPLY_KEY = "supplyKey";
    private static final String PAYER_KEY = "payerKey";
    private static final String FUNGIBLE_TOKEN = "fungibleToken";
    private static final String NFT_TOKEN = "nftToken";
    private static final String tokenBurnTxn = "tokenBurnTxn";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(Map.of("fees.simpleFeesEnabled", "true"));
    }

    @Nested
    @DisplayName("TokenBurn Fungible Simple Fees Positive Test Cases")
    class TokenBurnFungiblePositiveTestCases {

        @HapiTest
        @DisplayName("TokenBurn fungible - base fees")
        final Stream<DynamicTest> tokenBurnFungibleBaseFee() {
            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    newKeyNamed(SUPPLY_KEY),
                    tokenCreate(FUNGIBLE_TOKEN)
                            .tokenType(FUNGIBLE_COMMON)
                            .initialSupply(1000L)
                            .supplyKey(SUPPLY_KEY)
                            .treasury(PAYER),
                    burnToken(FUNGIBLE_TOKEN, 100L)
                            .payingWith(PAYER)
                            .signedBy(PAYER, SUPPLY_KEY)
                            .via(tokenBurnTxn),
                    validateChargedUsdWithinWithTxnSize(
                            tokenBurnTxn,
                            txnSize -> expectedTokenBurnFungibleFullFeeUsd(
                                    Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(tokenBurnTxn, PAYER));
        }

        @HapiTest
        @DisplayName("TokenBurn fungible - multiple units same fee")
        final Stream<DynamicTest> tokenBurnFungibleMultipleUnits() {
            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    newKeyNamed(SUPPLY_KEY),
                    tokenCreate(FUNGIBLE_TOKEN)
                            .tokenType(FUNGIBLE_COMMON)
                            .initialSupply(10000L)
                            .supplyKey(SUPPLY_KEY)
                            .treasury(PAYER),
                    burnToken(FUNGIBLE_TOKEN, 5000L) // Burn 5000 units
                            .payingWith(PAYER)
                            .signedBy(PAYER, SUPPLY_KEY)
                            .via(tokenBurnTxn),
                    validateChargedUsdWithinWithTxnSize(
                            tokenBurnTxn,
                            txnSize -> expectedTokenBurnFungibleFullFeeUsd(
                                    Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(tokenBurnTxn, PAYER));
        }

        @HapiTest
        @DisplayName("TokenBurn fungible with threshold key - extra signatures")
        final Stream<DynamicTest> tokenBurnFungibleWithThresholdKey() {
            KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
            SigControl validSig = keyShape.signedWith(sigs(ON, ON));

            return hapiTest(
                    newKeyNamed(PAYER_KEY).shape(keyShape),
                    cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                    newKeyNamed(SUPPLY_KEY),
                    tokenCreate(FUNGIBLE_TOKEN)
                            .tokenType(FUNGIBLE_COMMON)
                            .initialSupply(1000L)
                            .supplyKey(SUPPLY_KEY)
                            .treasury(PAYER)
                            .sigControl(forKey(PAYER_KEY, validSig)),
                    burnToken(FUNGIBLE_TOKEN, 100L)
                            .payingWith(PAYER)
                            .sigControl(forKey(PAYER_KEY, validSig))
                            .signedBy(PAYER, SUPPLY_KEY)
                            .via(tokenBurnTxn),
                    validateChargedUsdWithinWithTxnSize(
                            tokenBurnTxn,
                            txnSize -> expectedTokenBurnFungibleFullFeeUsd(
                                    Map.of(SIGNATURES, 3L, PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(tokenBurnTxn, PAYER));
        }

        @HapiTest
        @DisplayName("TokenBurn fungible with large payer key - extra processing bytes fee")
        final Stream<DynamicTest> tokenBurnFungibleLargeKeyExtraProcessingBytesFee() {
            KeyShape keyShape = threshOf(
                    1, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE,
                    SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE);
            SigControl allSigned = keyShape.signedWith(
                    sigs(ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON));

            return hapiTest(
                    newKeyNamed(PAYER_KEY).shape(keyShape),
                    cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                    newKeyNamed(SUPPLY_KEY),
                    tokenCreate(FUNGIBLE_TOKEN)
                            .tokenType(FUNGIBLE_COMMON)
                            .initialSupply(1000L)
                            .supplyKey(SUPPLY_KEY)
                            .treasury(PAYER)
                            .sigControl(forKey(PAYER_KEY, allSigned)),
                    burnToken(FUNGIBLE_TOKEN, 100L)
                            .payingWith(PAYER)
                            .sigControl(forKey(PAYER_KEY, allSigned))
                            .signedBy(PAYER, SUPPLY_KEY)
                            .via(tokenBurnTxn),
                    validateChargedUsdWithinWithTxnSize(
                            tokenBurnTxn,
                            txnSize -> expectedTokenBurnFungibleFullFeeUsd(
                                    Map.of(SIGNATURES, 21L, PROCESSING_BYTES, (long) txnSize)),
                            0.1));
        }

        @HapiTest
        @DisplayName("TokenBurn fungible with very large payer key below oversize - extra processing bytes fee")
        final Stream<DynamicTest> tokenBurnFungibleVeryLargeKeyBelowOversizeFee() {
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
                    newKeyNamed(SUPPLY_KEY),
                    tokenCreate(FUNGIBLE_TOKEN)
                            .tokenType(FUNGIBLE_COMMON)
                            .initialSupply(1000L)
                            .supplyKey(SUPPLY_KEY)
                            .treasury(PAYER)
                            .sigControl(forKey(PAYER_KEY, allSigned)),
                    burnToken(FUNGIBLE_TOKEN, 100L)
                            .payingWith(PAYER)
                            .sigControl(forKey(PAYER_KEY, allSigned))
                            .signedBy(PAYER, SUPPLY_KEY)
                            .via(tokenBurnTxn),
                    validateChargedUsdWithinWithTxnSize(
                            tokenBurnTxn,
                            txnSize -> expectedTokenBurnFungibleFullFeeUsd(
                                    Map.of(SIGNATURES, 42L, PROCESSING_BYTES, (long) txnSize)),
                            0.1));
        }
    }

    @Nested
    @DisplayName("TokenBurn NFT Simple Fees Positive Test Cases")
    class TokenBurnNftPositiveTestCases {

        @HapiTest
        @DisplayName("TokenBurn NFT - base fees with single serial")
        final Stream<DynamicTest> tokenBurnNftBaseFee() {
            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    newKeyNamed(SUPPLY_KEY),
                    tokenCreate(NFT_TOKEN)
                            .tokenType(NON_FUNGIBLE_UNIQUE)
                            .initialSupply(0L)
                            .supplyKey(SUPPLY_KEY)
                            .treasury(PAYER),
                    mintToken(NFT_TOKEN, List.of(ByteString.copyFromUtf8("metadata1")))
                            .payingWith(PAYER)
                            .signedBy(PAYER, SUPPLY_KEY),
                    burnToken(NFT_TOKEN, List.of(1L))
                            .payingWith(PAYER)
                            .signedBy(PAYER, SUPPLY_KEY)
                            .via(tokenBurnTxn),
                    validateChargedUsdWithinWithTxnSize(
                            tokenBurnTxn,
                            txnSize -> expectedTokenBurnNftFullFeeUsd(
                                    Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(tokenBurnTxn, PAYER));
        }

        @HapiTest
        @DisplayName("TokenBurn NFT - multiple serials - no extra fees")
        final Stream<DynamicTest> tokenBurnNftMultipleSerials() {
            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    newKeyNamed(SUPPLY_KEY),
                    tokenCreate(NFT_TOKEN)
                            .tokenType(NON_FUNGIBLE_UNIQUE)
                            .initialSupply(0L)
                            .supplyKey(SUPPLY_KEY)
                            .treasury(PAYER),
                    mintToken(
                                    NFT_TOKEN,
                                    List.of(
                                            ByteString.copyFromUtf8("m1"),
                                            ByteString.copyFromUtf8("m2"),
                                            ByteString.copyFromUtf8("m3")))
                            .payingWith(PAYER)
                            .signedBy(PAYER, SUPPLY_KEY),
                    burnToken(NFT_TOKEN, List.of(1L, 2L, 3L))
                            .payingWith(PAYER)
                            .signedBy(PAYER, SUPPLY_KEY)
                            .via(tokenBurnTxn),
                    validateChargedUsdWithinWithTxnSize(
                            tokenBurnTxn,
                            txnSize -> expectedTokenBurnNftFullFeeUsd(
                                    Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(tokenBurnTxn, PAYER));
        }

        @HapiTest
        @DisplayName("TokenBurn NFT with threshold key - extra signatures and two serials")
        final Stream<DynamicTest> tokenBurnNftWithThresholdKey() {
            KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
            SigControl validSig = keyShape.signedWith(sigs(ON, ON));

            return hapiTest(
                    newKeyNamed(PAYER_KEY).shape(keyShape),
                    cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                    newKeyNamed(SUPPLY_KEY),
                    tokenCreate(NFT_TOKEN)
                            .tokenType(NON_FUNGIBLE_UNIQUE)
                            .initialSupply(0L)
                            .supplyKey(SUPPLY_KEY)
                            .treasury(PAYER)
                            .sigControl(forKey(PAYER_KEY, validSig)),
                    mintToken(
                                    NFT_TOKEN,
                                    List.of(ByteString.copyFromUtf8("metadata1"), ByteString.copyFromUtf8("metadata2")))
                            .payingWith(PAYER)
                            .sigControl(forKey(PAYER_KEY, validSig))
                            .signedBy(PAYER, SUPPLY_KEY),
                    burnToken(NFT_TOKEN, List.of(1L, 2L))
                            .payingWith(PAYER)
                            .sigControl(forKey(PAYER_KEY, validSig))
                            .signedBy(PAYER, SUPPLY_KEY)
                            .via(tokenBurnTxn),
                    validateChargedUsdWithinWithTxnSize(
                            tokenBurnTxn,
                            txnSize -> expectedTokenBurnNftFullFeeUsd(
                                    Map.of(SIGNATURES, 3L, PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(tokenBurnTxn, PAYER));
        }

        @HapiTest
        @DisplayName("TokenBurn NFT with large payer key - extra processing bytes fee")
        final Stream<DynamicTest> tokenBurnNftLargeKeyExtraProcessingBytesFee() {
            KeyShape keyShape = threshOf(
                    1, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE,
                    SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE);
            SigControl allSigned = keyShape.signedWith(
                    sigs(ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON));

            return hapiTest(
                    newKeyNamed(PAYER_KEY).shape(keyShape),
                    cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                    newKeyNamed(SUPPLY_KEY),
                    tokenCreate(NFT_TOKEN)
                            .tokenType(NON_FUNGIBLE_UNIQUE)
                            .initialSupply(0L)
                            .supplyKey(SUPPLY_KEY)
                            .treasury(PAYER)
                            .sigControl(forKey(PAYER_KEY, allSigned)),
                    mintToken(NFT_TOKEN, List.of(ByteString.copyFromUtf8("metadata1")))
                            .payingWith(PAYER)
                            .sigControl(forKey(PAYER_KEY, allSigned))
                            .signedBy(PAYER, SUPPLY_KEY),
                    burnToken(NFT_TOKEN, List.of(1L))
                            .payingWith(PAYER)
                            .sigControl(forKey(PAYER_KEY, allSigned))
                            .signedBy(PAYER, SUPPLY_KEY)
                            .via(tokenBurnTxn),
                    validateChargedUsdWithinWithTxnSize(
                            tokenBurnTxn,
                            txnSize -> expectedTokenBurnFungibleFullFeeUsd(
                                    Map.of(SIGNATURES, 21L, PROCESSING_BYTES, (long) txnSize)),
                            0.1));
        }
    }

    @Nested
    @DisplayName("TokenBurn Simple Fees Negative Test Cases")
    class TokenBurnNegativeTestCases {

        @Nested
        @DisplayName("TokenBurn Failures on Ingest and Handle")
        class TokenBurnFailuresOnIngest {

            @HapiTest
            @DisplayName("TokenBurn - invalid signature fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenBurnInvalidSignatureFailsOnIngest() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(SUPPLY_KEY),
                        tokenCreate(FUNGIBLE_TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .initialSupply(1000L)
                                .supplyKey(SUPPLY_KEY)
                                .treasury(PAYER),
                        burnToken(FUNGIBLE_TOKEN, 100L)
                                .signedBy(PAYER) // Missing supply key signature
                                .via(tokenBurnTxn)
                                .hasPrecheck(INVALID_SIGNATURE),
                        getTxnRecord(tokenBurnTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TokenBurn - insufficient tx fee fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenBurnInsufficientTxFeeFailsOnIngest() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(SUPPLY_KEY),
                        tokenCreate(FUNGIBLE_TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .initialSupply(1000L)
                                .supplyKey(SUPPLY_KEY)
                                .treasury(PAYER),
                        burnToken(FUNGIBLE_TOKEN, 100L)
                                .payingWith(PAYER)
                                .signedBy(PAYER, SUPPLY_KEY)
                                .fee(1L) // Fee too low
                                .via(tokenBurnTxn)
                                .hasPrecheck(INSUFFICIENT_TX_FEE),
                        getTxnRecord(tokenBurnTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TokenBurn - invalid burn amount fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenBurnInvalidAmountFailsOnIngest() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(SUPPLY_KEY),
                        tokenCreate(FUNGIBLE_TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .initialSupply(1000L)
                                .supplyKey(SUPPLY_KEY)
                                .treasury(PAYER),
                        burnToken(FUNGIBLE_TOKEN, -1L) // Invalid: -1 amount
                                .payingWith(PAYER)
                                .signedBy(PAYER, SUPPLY_KEY)
                                .via(tokenBurnTxn)
                                .hasPrecheck(INVALID_TOKEN_BURN_AMOUNT));
            }

            @HapiTest
            @DisplayName("TokenBurn - threshold payer key with invalid signature fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenBurnThresholdKeyInvalidSigFailsOnIngest() {
                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
                SigControl validSig = keyShape.signedWith(sigs(ON, ON));
                SigControl invalidSig = keyShape.signedWith(sigs(ON, OFF));

                return hapiTest(
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(SUPPLY_KEY),
                        tokenCreate(FUNGIBLE_TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .initialSupply(1000L)
                                .supplyKey(SUPPLY_KEY)
                                .treasury(PAYER)
                                .sigControl(forKey(PAYER_KEY, validSig)),
                        burnToken(FUNGIBLE_TOKEN, 100L)
                                .payingWith(PAYER)
                                .sigControl(forKey(PAYER_KEY, invalidSig))
                                .signedBy(PAYER, SUPPLY_KEY)
                                .via(tokenBurnTxn)
                                .hasPrecheck(INVALID_SIGNATURE),
                        getTxnRecord(tokenBurnTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TokenBurn - insufficient payer balance fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenBurnInsufficientPayerBalanceFailsOnIngest() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HBAR / 100_000L),
                        newKeyNamed(SUPPLY_KEY),
                        tokenCreate(FUNGIBLE_TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .initialSupply(1000L)
                                .supplyKey(SUPPLY_KEY)
                                .treasury(GENESIS),
                        burnToken(FUNGIBLE_TOKEN, 100L)
                                .payingWith(PAYER)
                                .signedBy(PAYER, SUPPLY_KEY)
                                .via(tokenBurnTxn)
                                .hasPrecheck(INSUFFICIENT_PAYER_BALANCE),
                        getTxnRecord(tokenBurnTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TokenBurn - memo too long fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenBurnMemoTooLongFailsOnIngest() {
                final var LONG_MEMO = "x".repeat(1025);
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(SUPPLY_KEY),
                        tokenCreate(FUNGIBLE_TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .initialSupply(1000L)
                                .supplyKey(SUPPLY_KEY)
                                .treasury(PAYER),
                        burnToken(FUNGIBLE_TOKEN, 100L)
                                .payingWith(PAYER)
                                .signedBy(PAYER, SUPPLY_KEY)
                                .memo(LONG_MEMO)
                                .via(tokenBurnTxn)
                                .hasPrecheck(MEMO_TOO_LONG),
                        getTxnRecord(tokenBurnTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TokenBurn - expired transaction fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenBurnExpiredTransactionFailsOnIngest() {
                final var expiredTxnId = "expiredBurnTxn";
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(SUPPLY_KEY),
                        tokenCreate(FUNGIBLE_TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .initialSupply(1000L)
                                .supplyKey(SUPPLY_KEY)
                                .treasury(PAYER),
                        usableTxnIdNamed(expiredTxnId).modifyValidStart(-3_600L).payerId(PAYER),
                        burnToken(FUNGIBLE_TOKEN, 100L)
                                .payingWith(PAYER)
                                .signedBy(PAYER, SUPPLY_KEY)
                                .txnId(expiredTxnId)
                                .via(tokenBurnTxn)
                                .hasPrecheck(TRANSACTION_EXPIRED),
                        getTxnRecord(tokenBurnTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TokenBurn - too far start time fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenBurnTooFarStartTimeFailsOnIngest() {
                final var futureTxnId = "futureBurnTxn";
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(SUPPLY_KEY),
                        tokenCreate(FUNGIBLE_TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .initialSupply(1000L)
                                .supplyKey(SUPPLY_KEY)
                                .treasury(PAYER),
                        usableTxnIdNamed(futureTxnId).modifyValidStart(3_600L).payerId(PAYER),
                        burnToken(FUNGIBLE_TOKEN, 100L)
                                .payingWith(PAYER)
                                .signedBy(PAYER, SUPPLY_KEY)
                                .txnId(futureTxnId)
                                .via(tokenBurnTxn)
                                .hasPrecheck(INVALID_TRANSACTION_START),
                        getTxnRecord(tokenBurnTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TokenBurn - invalid transaction duration fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenBurnInvalidTransactionDurationFailsOnIngest() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(SUPPLY_KEY),
                        tokenCreate(FUNGIBLE_TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .initialSupply(1000L)
                                .supplyKey(SUPPLY_KEY)
                                .treasury(PAYER),
                        burnToken(FUNGIBLE_TOKEN, 100L)
                                .payingWith(PAYER)
                                .signedBy(PAYER, SUPPLY_KEY)
                                .validDurationSecs(0)
                                .via(tokenBurnTxn)
                                .hasPrecheck(INVALID_TRANSACTION_DURATION),
                        getTxnRecord(tokenBurnTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TokenBurn - duplicate transaction fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenBurnDuplicateTxnFailsOnIngest() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(SUPPLY_KEY),
                        tokenCreate(FUNGIBLE_TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .initialSupply(10000L)
                                .supplyKey(SUPPLY_KEY)
                                .treasury(PAYER),
                        burnToken(FUNGIBLE_TOKEN, 100L)
                                .payingWith(PAYER)
                                .signedBy(PAYER, SUPPLY_KEY)
                                .via("tokenBurnFirst"),
                        burnToken(FUNGIBLE_TOKEN, 50L)
                                .payingWith(PAYER)
                                .signedBy(PAYER, SUPPLY_KEY)
                                .txnId("tokenBurnFirst")
                                .via(tokenBurnTxn)
                                .hasPrecheck(DUPLICATE_TRANSACTION));
            }

            @HapiTest
            @DisplayName("TokenBurn - no supply key fails on handle - full fees charged")
            final Stream<DynamicTest> tokenBurnNoSupplyKeyFailsOnHandleFullFeesCharged() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        tokenCreate(FUNGIBLE_TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .initialSupply(1000L)
                                // No supply key
                                .treasury(PAYER),
                        burnToken(FUNGIBLE_TOKEN, 100L)
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .via(tokenBurnTxn)
                                .hasKnownStatus(TOKEN_HAS_NO_SUPPLY_KEY),
                        validateChargedUsdWithinWithTxnSize(
                                tokenBurnTxn,
                                txnSize -> expectedTokenBurnFungibleFullFeeUsd(
                                        Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateChargedAccount(tokenBurnTxn, PAYER));
            }

            @Nested
            @Tag(ONLY_SUBPROCESS)
            @DisplayName("TokenBurn Simple Fees Duplicate on Handle")
            class TokenBurnDuplicateOnHandle {

                private static final String DUPLICATE_TXN_ID = "duplicateBurnTxnId";

                @LeakyHapiTest
                @DisplayName("TokenBurn - duplicate transaction fails on handle - payer charged for first only")
                final Stream<DynamicTest> tokenBurnDuplicateFailsOnHandle() {
                    return hapiTest(
                            cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                            newKeyNamed(SUPPLY_KEY),
                            tokenCreate(FUNGIBLE_TOKEN)
                                    .tokenType(FUNGIBLE_COMMON)
                                    .initialSupply(10000L)
                                    .supplyKey(SUPPLY_KEY)
                                    .treasury(PAYER),
                            cryptoTransfer(movingHbar(ONE_HBAR).between(GENESIS, "3")),
                            usableTxnIdNamed(DUPLICATE_TXN_ID).payerId(PAYER),
                            burnToken(FUNGIBLE_TOKEN, 100L)
                                    .payingWith(PAYER)
                                    .signedBy(PAYER, SUPPLY_KEY)
                                    .setNode(4)
                                    .txnId(DUPLICATE_TXN_ID)
                                    .via(tokenBurnTxn),
                            burnToken(FUNGIBLE_TOKEN, 100L)
                                    .payingWith(PAYER)
                                    .signedBy(PAYER, SUPPLY_KEY)
                                    .txnId(DUPLICATE_TXN_ID)
                                    .setNode(3)
                                    .hasPrecheck(DUPLICATE_TRANSACTION),
                            validateChargedUsdWithinWithTxnSize(
                                    tokenBurnTxn,
                                    txnSize -> expectedTokenBurnFungibleFullFeeUsd(
                                            Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                                    0.1),
                            validateChargedAccount(tokenBurnTxn, PAYER));
                }
            }
        }

        @Nested
        @DisplayName("TokenBurn Failures on Pre-Handle")
        class TokenBurnFailuresOnPreHandle {

            @LeakyEmbeddedHapiTest(reason = MUST_SKIP_INGEST)
            @DisplayName("TokenBurn - invalid payer signature fails on pre-handle - network fee only")
            final Stream<DynamicTest> tokenBurnInvalidPayerSigFailsOnPreHandle() {
                final String INNER_ID = "token-burn-txn-inner-id";

                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
                SigControl invalidSig = keyShape.signedWith(sigs(ON, OFF));

                return hapiTest(
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(SUPPLY_KEY),
                        tokenCreate(FUNGIBLE_TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .initialSupply(1000L)
                                .supplyKey(SUPPLY_KEY)
                                .treasury(PAYER),
                        cryptoTransfer(movingHbar(ONE_HBAR).between(GENESIS, "4")),
                        burnToken(FUNGIBLE_TOKEN, 100L)
                                .payingWith(PAYER)
                                .sigControl(forKey(PAYER_KEY, invalidSig))
                                .signedBy(PAYER, SUPPLY_KEY)
                                .setNode("4")
                                .via(INNER_ID)
                                .hasKnownStatus(INVALID_PAYER_SIGNATURE),
                        getTxnRecord(INNER_ID).assertingNothingAboutHashes().logged(),
                        validateChargedUsdWithinWithTxnSize(
                                INNER_ID,
                                txnSize -> expectedTokenBurnNetworkFeeOnlyUsd(
                                        Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateChargedAccount(INNER_ID, "4"));
            }
        }
    }
}
