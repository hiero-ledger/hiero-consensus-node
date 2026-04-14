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
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingHbar;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.usableTxnIdNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedAccount;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTokenMintFungibleFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTokenMintNetworkFeeOnlyUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTokenMintNftFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.validateChargedUsdWithinWithTxnSize;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.DUPLICATE_TRANSACTION;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_PAYER_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_TX_FEE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_PAYER_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
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
import static org.hiero.hapi.support.fees.Extra.TOKEN_MINT_NFT;

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
 * Tests for TokenMint simple fees with extras.
 * Validates that fees are correctly calculated based on:
 * - Number of signatures (extras beyond included)
 * - For fungible tokens: amount doesn't affect fees
 * - For NFTs: number of serials affects fees (extras beyond included)
 */
@Tag(SIMPLE_FEES)
@HapiTestLifecycle
public class TokenMintSimpleFeesTest {

    private static final String PAYER = "payer";
    private static final String TREASURY = "treasury";
    private static final String SUPPLY_KEY = "supplyKey";
    private static final String PAYER_KEY = "payerKey";
    private static final String FUNGIBLE_TOKEN = "fungibleToken";
    private static final String NFT_TOKEN = "nftToken";
    private static final String tokenMintTxn = "tokenMintTxn";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(Map.of("fees.simpleFeesEnabled", "true"));
    }

    @Nested
    @DisplayName("TokenMint Fungible Simple Fees Positive Test Cases")
    class TokenMintFungiblePositiveTestCases {

        @HapiTest
        @DisplayName("TokenMint fungible - base fees for single unit")
        final Stream<DynamicTest> tokenMintFungibleBaseFee() {
            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(TREASURY).balance(0L),
                    newKeyNamed(SUPPLY_KEY),
                    tokenCreate(FUNGIBLE_TOKEN)
                            .tokenType(FUNGIBLE_COMMON)
                            .initialSupply(0L)
                            .supplyKey(SUPPLY_KEY)
                            .treasury(TREASURY)
                            .payingWith(PAYER),
                    mintToken(FUNGIBLE_TOKEN, 1L)
                            .payingWith(PAYER)
                            .signedBy(PAYER, SUPPLY_KEY)
                            .via(tokenMintTxn),
                    validateChargedUsdWithinWithTxnSize(
                            tokenMintTxn,
                            txnSize -> expectedTokenMintFungibleFullFeeUsd(
                                    Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(tokenMintTxn, PAYER));
        }

        @HapiTest
        @DisplayName("TokenMint fungible - multiple units same fee")
        final Stream<DynamicTest> tokenMintFungibleMultipleUnits() {
            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(TREASURY).balance(0L),
                    newKeyNamed(SUPPLY_KEY),
                    tokenCreate(FUNGIBLE_TOKEN)
                            .tokenType(FUNGIBLE_COMMON)
                            .initialSupply(0L)
                            .supplyKey(SUPPLY_KEY)
                            .treasury(TREASURY)
                            .payingWith(PAYER),
                    mintToken(FUNGIBLE_TOKEN, 1000L) // 1000 units
                            .payingWith(PAYER)
                            .signedBy(PAYER, SUPPLY_KEY)
                            .via(tokenMintTxn),
                    validateChargedUsdWithinWithTxnSize(
                            tokenMintTxn,
                            txnSize -> expectedTokenMintFungibleFullFeeUsd(
                                    Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(tokenMintTxn, PAYER));
        }

        @HapiTest
        @DisplayName("TokenMint fungible with threshold key - extra signatures")
        final Stream<DynamicTest> tokenMintFungibleWithThresholdKey() {
            KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
            SigControl validSig = keyShape.signedWith(sigs(ON, ON));

            return hapiTest(
                    newKeyNamed(PAYER_KEY).shape(keyShape),
                    cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(TREASURY).balance(0L),
                    newKeyNamed(SUPPLY_KEY),
                    tokenCreate(FUNGIBLE_TOKEN)
                            .tokenType(FUNGIBLE_COMMON)
                            .initialSupply(0L)
                            .supplyKey(SUPPLY_KEY)
                            .treasury(TREASURY)
                            .payingWith(PAYER)
                            .sigControl(forKey(PAYER_KEY, validSig)),
                    mintToken(FUNGIBLE_TOKEN, 100L)
                            .payingWith(PAYER)
                            .sigControl(forKey(PAYER_KEY, validSig))
                            .signedBy(PAYER, SUPPLY_KEY)
                            .via(tokenMintTxn),
                    validateChargedUsdWithinWithTxnSize(
                            tokenMintTxn,
                            txnSize -> expectedTokenMintFungibleFullFeeUsd(
                                    Map.of(SIGNATURES, 3L, PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(tokenMintTxn, PAYER));
        }

        @HapiTest
        @DisplayName("TokenMint fungible with large payer key - extra processing bytes fee")
        final Stream<DynamicTest> tokenMintFungibleLargeKeyExtraProcessingBytesFee() {
            KeyShape keyShape = threshOf(
                    1, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE,
                    SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE);
            SigControl allSigned = keyShape.signedWith(
                    sigs(ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON));

            return hapiTest(
                    newKeyNamed(PAYER_KEY).shape(keyShape),
                    cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(TREASURY).balance(0L),
                    newKeyNamed(SUPPLY_KEY),
                    tokenCreate(FUNGIBLE_TOKEN)
                            .tokenType(FUNGIBLE_COMMON)
                            .initialSupply(0L)
                            .supplyKey(SUPPLY_KEY)
                            .treasury(TREASURY)
                            .payingWith(PAYER)
                            .sigControl(forKey(PAYER_KEY, allSigned)),
                    mintToken(FUNGIBLE_TOKEN, 100L)
                            .payingWith(PAYER)
                            .sigControl(forKey(PAYER_KEY, allSigned))
                            .signedBy(PAYER, SUPPLY_KEY)
                            .via(tokenMintTxn),
                    validateChargedUsdWithinWithTxnSize(
                            tokenMintTxn,
                            txnSize -> expectedTokenMintFungibleFullFeeUsd(
                                    Map.of(SIGNATURES, 21L, PROCESSING_BYTES, (long) txnSize)),
                            0.1));
        }

        @HapiTest
        @DisplayName("TokenMint fungible with very large payer key below oversize - extra processing bytes fee")
        final Stream<DynamicTest> tokenMintFungibleVeryLargeKeyBelowOversizeFee() {
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
                    newKeyNamed(SUPPLY_KEY),
                    tokenCreate(FUNGIBLE_TOKEN)
                            .tokenType(FUNGIBLE_COMMON)
                            .initialSupply(0L)
                            .supplyKey(SUPPLY_KEY)
                            .treasury(TREASURY)
                            .payingWith(PAYER)
                            .sigControl(forKey(PAYER_KEY, allSigned)),
                    mintToken(FUNGIBLE_TOKEN, 100L)
                            .payingWith(PAYER)
                            .sigControl(forKey(PAYER_KEY, allSigned))
                            .signedBy(PAYER, SUPPLY_KEY)
                            .via(tokenMintTxn),
                    validateChargedUsdWithinWithTxnSize(
                            tokenMintTxn,
                            txnSize -> expectedTokenMintFungibleFullFeeUsd(
                                    Map.of(SIGNATURES, 42L, PROCESSING_BYTES, (long) txnSize)),
                            0.1));
        }
    }

    @Nested
    @DisplayName("TokenMint NFT Simple Fees Positive Test Cases")
    class TokenMintNftPositiveTestCases {

        @HapiTest
        @DisplayName("TokenMint NFT - base fees for single serial")
        final Stream<DynamicTest> tokenMintNftBaseFee() {
            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(TREASURY).balance(0L),
                    newKeyNamed(SUPPLY_KEY),
                    tokenCreate(NFT_TOKEN)
                            .tokenType(NON_FUNGIBLE_UNIQUE)
                            .initialSupply(0L)
                            .supplyKey(SUPPLY_KEY)
                            .treasury(TREASURY)
                            .payingWith(PAYER),
                    mintToken(NFT_TOKEN, List.of(ByteString.copyFromUtf8("metadata1")))
                            .payingWith(PAYER)
                            .signedBy(PAYER, SUPPLY_KEY)
                            .via(tokenMintTxn),
                    validateChargedUsdWithinWithTxnSize(
                            tokenMintTxn,
                            txnSize -> expectedTokenMintNftFullFeeUsd(Map.of(
                                    SIGNATURES, 2L,
                                    TOKEN_MINT_NFT, 1L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(tokenMintTxn, PAYER));
        }

        @HapiTest
        @DisplayName("TokenMint NFT - multiple serials extra fee")
        final Stream<DynamicTest> tokenMintNftMultipleSerials() {
            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(TREASURY).balance(0L),
                    newKeyNamed(SUPPLY_KEY),
                    tokenCreate(NFT_TOKEN)
                            .tokenType(NON_FUNGIBLE_UNIQUE)
                            .initialSupply(0L)
                            .supplyKey(SUPPLY_KEY)
                            .treasury(TREASURY)
                            .payingWith(PAYER),
                    mintToken(
                                    NFT_TOKEN,
                                    List.of(
                                            ByteString.copyFromUtf8("metadata1"),
                                            ByteString.copyFromUtf8("metadata2"),
                                            ByteString.copyFromUtf8("metadata3")))
                            .payingWith(PAYER)
                            .signedBy(PAYER, SUPPLY_KEY)
                            .via(tokenMintTxn),
                    validateChargedUsdWithinWithTxnSize(
                            tokenMintTxn,
                            txnSize -> expectedTokenMintNftFullFeeUsd(Map.of(
                                    SIGNATURES, 2L,
                                    TOKEN_MINT_NFT, 3L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(tokenMintTxn, PAYER));
        }

        @HapiTest
        @DisplayName("TokenMint NFT - 5 serials extra fee")
        final Stream<DynamicTest> tokenMintNftFiveSerials() {
            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(TREASURY).balance(0L),
                    newKeyNamed(SUPPLY_KEY),
                    tokenCreate(NFT_TOKEN)
                            .tokenType(NON_FUNGIBLE_UNIQUE)
                            .initialSupply(0L)
                            .supplyKey(SUPPLY_KEY)
                            .treasury(TREASURY)
                            .payingWith(PAYER),
                    mintToken(
                                    NFT_TOKEN,
                                    List.of(
                                            ByteString.copyFromUtf8("m1"),
                                            ByteString.copyFromUtf8("m2"),
                                            ByteString.copyFromUtf8("m3"),
                                            ByteString.copyFromUtf8("m4"),
                                            ByteString.copyFromUtf8("m5")))
                            .payingWith(PAYER)
                            .signedBy(PAYER, SUPPLY_KEY)
                            .via(tokenMintTxn),
                    validateChargedUsdWithinWithTxnSize(
                            tokenMintTxn,
                            txnSize -> expectedTokenMintNftFullFeeUsd(Map.of(
                                    SIGNATURES, 2L,
                                    TOKEN_MINT_NFT, 5L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(tokenMintTxn, PAYER));
        }

        @HapiTest
        @DisplayName("TokenMint NFT with threshold key - extra signatures and serials")
        final Stream<DynamicTest> tokenMintNftWithThresholdKey() {
            KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
            SigControl validSig = keyShape.signedWith(sigs(ON, ON));

            return hapiTest(
                    newKeyNamed(PAYER_KEY).shape(keyShape),
                    cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(TREASURY).balance(0L),
                    newKeyNamed(SUPPLY_KEY),
                    tokenCreate(NFT_TOKEN)
                            .tokenType(NON_FUNGIBLE_UNIQUE)
                            .initialSupply(0L)
                            .supplyKey(SUPPLY_KEY)
                            .treasury(TREASURY)
                            .payingWith(PAYER)
                            .sigControl(forKey(PAYER_KEY, validSig)),
                    mintToken(
                                    NFT_TOKEN,
                                    List.of(ByteString.copyFromUtf8("metadata1"), ByteString.copyFromUtf8("metadata2")))
                            .payingWith(PAYER)
                            .sigControl(forKey(PAYER_KEY, validSig))
                            .signedBy(PAYER, SUPPLY_KEY)
                            .via(tokenMintTxn),
                    validateChargedUsdWithinWithTxnSize(
                            tokenMintTxn,
                            txnSize -> expectedTokenMintNftFullFeeUsd(Map.of(
                                    SIGNATURES, 3L,
                                    TOKEN_MINT_NFT, 2L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(tokenMintTxn, PAYER));
        }

        @HapiTest
        @DisplayName("TokenMint NFT with large payer key - extra processing bytes fee")
        final Stream<DynamicTest> tokenMintNftLargeKeyExtraProcessingBytesFee() {
            KeyShape keyShape = threshOf(
                    1, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE,
                    SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE);
            SigControl allSigned = keyShape.signedWith(
                    sigs(ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON));

            return hapiTest(
                    newKeyNamed(PAYER_KEY).shape(keyShape),
                    cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(TREASURY).balance(0L),
                    newKeyNamed(SUPPLY_KEY),
                    tokenCreate(NFT_TOKEN)
                            .tokenType(NON_FUNGIBLE_UNIQUE)
                            .initialSupply(0L)
                            .supplyKey(SUPPLY_KEY)
                            .treasury(TREASURY)
                            .payingWith(PAYER)
                            .sigControl(forKey(PAYER_KEY, allSigned)),
                    mintToken(NFT_TOKEN, List.of(ByteString.copyFromUtf8("metadata1")))
                            .payingWith(PAYER)
                            .sigControl(forKey(PAYER_KEY, allSigned))
                            .signedBy(PAYER, SUPPLY_KEY)
                            .via(tokenMintTxn),
                    validateChargedUsdWithinWithTxnSize(
                            tokenMintTxn,
                            txnSize -> expectedTokenMintNftFullFeeUsd(Map.of(
                                    SIGNATURES, 21L,
                                    TOKEN_MINT_NFT, 1L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(tokenMintTxn, PAYER));
        }
    }

    @Nested
    @DisplayName("TokenMint Simple Fees Negative Test Cases")
    class TokenMintNegativeTestCases {

        @Nested
        @DisplayName("TokenMint Failures on Ingest and Handle")
        class TokenMintFailuresOnIngest {

            @HapiTest
            @DisplayName("TokenMint - threshold payer key with invalid signature fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenMintThresholdKeyInvalidSigFailsOnIngest() {
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
                        mintToken(FUNGIBLE_TOKEN, 100L)
                                .payingWith(PAYER)
                                .sigControl(forKey(PAYER_KEY, invalidSig))
                                .signedBy(PAYER, SUPPLY_KEY)
                                .via(tokenMintTxn)
                                .hasPrecheck(INVALID_SIGNATURE),
                        getTxnRecord(tokenMintTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TokenMint - insufficient payer balance fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenMintInsufficientPayerBalanceFailsOnIngest() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HBAR / 100_000L),
                        newKeyNamed(SUPPLY_KEY),
                        tokenCreate(FUNGIBLE_TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .initialSupply(1000L)
                                .supplyKey(SUPPLY_KEY)
                                .treasury(GENESIS),
                        mintToken(FUNGIBLE_TOKEN, 100L)
                                .payingWith(PAYER)
                                .signedBy(PAYER, SUPPLY_KEY)
                                .via(tokenMintTxn)
                                .hasPrecheck(INSUFFICIENT_PAYER_BALANCE),
                        getTxnRecord(tokenMintTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TokenMint - memo too long fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenMintMemoTooLongFailsOnIngest() {
                final var LONG_MEMO = "x".repeat(1025);
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(SUPPLY_KEY),
                        tokenCreate(FUNGIBLE_TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .initialSupply(1000L)
                                .supplyKey(SUPPLY_KEY)
                                .treasury(PAYER),
                        mintToken(FUNGIBLE_TOKEN, 100L)
                                .payingWith(PAYER)
                                .signedBy(PAYER, SUPPLY_KEY)
                                .memo(LONG_MEMO)
                                .via(tokenMintTxn)
                                .hasPrecheck(MEMO_TOO_LONG),
                        getTxnRecord(tokenMintTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TokenMint - expired transaction fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenMintExpiredTransactionFailsOnIngest() {
                final var expiredTxnId = "expiredMintTxn";
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(SUPPLY_KEY),
                        tokenCreate(FUNGIBLE_TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .initialSupply(1000L)
                                .supplyKey(SUPPLY_KEY)
                                .treasury(PAYER),
                        usableTxnIdNamed(expiredTxnId).modifyValidStart(-3_600L).payerId(PAYER),
                        mintToken(FUNGIBLE_TOKEN, 100L)
                                .payingWith(PAYER)
                                .signedBy(PAYER, SUPPLY_KEY)
                                .txnId(expiredTxnId)
                                .via(tokenMintTxn)
                                .hasPrecheck(TRANSACTION_EXPIRED),
                        getTxnRecord(tokenMintTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TokenMint - too far start time fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenMintTooFarStartTimeFailsOnIngest() {
                final var futureTxnId = "futureMintTxn";
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(SUPPLY_KEY),
                        tokenCreate(FUNGIBLE_TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .initialSupply(1000L)
                                .supplyKey(SUPPLY_KEY)
                                .treasury(PAYER),
                        usableTxnIdNamed(futureTxnId).modifyValidStart(3_600L).payerId(PAYER),
                        mintToken(FUNGIBLE_TOKEN, 100L)
                                .payingWith(PAYER)
                                .signedBy(PAYER, SUPPLY_KEY)
                                .txnId(futureTxnId)
                                .via(tokenMintTxn)
                                .hasPrecheck(INVALID_TRANSACTION_START),
                        getTxnRecord(tokenMintTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TokenMint - invalid transaction duration fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenMintInvalidTransactionDurationFailsOnIngest() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(SUPPLY_KEY),
                        tokenCreate(FUNGIBLE_TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .initialSupply(1000L)
                                .supplyKey(SUPPLY_KEY)
                                .treasury(PAYER),
                        mintToken(FUNGIBLE_TOKEN, 100L)
                                .payingWith(PAYER)
                                .signedBy(PAYER, SUPPLY_KEY)
                                .validDurationSecs(0)
                                .via(tokenMintTxn)
                                .hasPrecheck(INVALID_TRANSACTION_DURATION),
                        getTxnRecord(tokenMintTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TokenMint - duplicate transaction fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenMintDuplicateTxnFailsOnIngest() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(SUPPLY_KEY),
                        tokenCreate(FUNGIBLE_TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .initialSupply(10000L)
                                .supplyKey(SUPPLY_KEY)
                                .treasury(PAYER),
                        mintToken(FUNGIBLE_TOKEN, 100L)
                                .payingWith(PAYER)
                                .signedBy(PAYER, SUPPLY_KEY)
                                .via("tokenMintFirst"),
                        mintToken(FUNGIBLE_TOKEN, 50L)
                                .payingWith(PAYER)
                                .signedBy(PAYER, SUPPLY_KEY)
                                .txnId("tokenMintFirst")
                                .via(tokenMintTxn)
                                .hasPrecheck(DUPLICATE_TRANSACTION));
            }

            @HapiTest
            @DisplayName("TokenMint - insufficient tx fee fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenMintInsufficientTxFeeFailsOnIngest() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        newKeyNamed(SUPPLY_KEY),
                        tokenCreate(FUNGIBLE_TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .initialSupply(0L)
                                .supplyKey(SUPPLY_KEY)
                                .treasury(TREASURY)
                                .payingWith(PAYER),
                        mintToken(FUNGIBLE_TOKEN, 100L)
                                .payingWith(PAYER)
                                .signedBy(PAYER, SUPPLY_KEY)
                                .fee(1L) // Fee too low
                                .via(tokenMintTxn)
                                .hasPrecheck(INSUFFICIENT_TX_FEE),
                        getTxnRecord(tokenMintTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TokenMint - missing supply key signature fails on handle - full fee charged")
            final Stream<DynamicTest> tokenMintMissingSupplyKeySignatureFailsAtHandle() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        newKeyNamed(SUPPLY_KEY),
                        tokenCreate(FUNGIBLE_TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .initialSupply(0L)
                                .supplyKey(SUPPLY_KEY)
                                .treasury(TREASURY)
                                .payingWith(PAYER),
                        mintToken(FUNGIBLE_TOKEN, 100L)
                                .payingWith(PAYER)
                                .signedBy(PAYER) // Missing supply key signature
                                .via(tokenMintTxn)
                                .hasKnownStatus(INVALID_SIGNATURE),
                        validateChargedUsdWithinWithTxnSize(
                                tokenMintTxn,
                                txnSize -> expectedTokenMintFungibleFullFeeUsd(
                                        Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateChargedAccount(tokenMintTxn, PAYER));
            }

            @HapiTest
            @DisplayName("TokenMint - no supply key fails on handle - full fee charged")
            final Stream<DynamicTest> tokenMintNoSupplyKeyFails() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        tokenCreate(FUNGIBLE_TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .initialSupply(100L)
                                // No supply key
                                .treasury(TREASURY)
                                .payingWith(PAYER),
                        mintToken(FUNGIBLE_TOKEN, 100L)
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .via(tokenMintTxn)
                                .hasKnownStatus(TOKEN_HAS_NO_SUPPLY_KEY),
                        validateChargedUsdWithinWithTxnSize(
                                tokenMintTxn,
                                txnSize -> expectedTokenMintFungibleFullFeeUsd(
                                        Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateChargedAccount(tokenMintTxn, PAYER));
            }

            @Tag(ONLY_SUBPROCESS)
            @LeakyHapiTest
            @DisplayName("TokenMint - duplicate transaction fails on handle - payer charged for first only")
            final Stream<DynamicTest> tokenMintDuplicateFailsOnHandle() {
                final String DUPLICATE_TXN_ID = "tokenCreateDuplicateTxnId";
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        newKeyNamed(SUPPLY_KEY),
                        tokenCreate(FUNGIBLE_TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .initialSupply(0L)
                                .supplyKey(SUPPLY_KEY)
                                .treasury(TREASURY)
                                .payingWith(PAYER),
                        cryptoTransfer(movingHbar(ONE_HBAR).between(DEFAULT_PAYER, "3")),
                        usableTxnIdNamed(DUPLICATE_TXN_ID).payerId(PAYER),
                        mintToken(FUNGIBLE_TOKEN, 100L)
                                .payingWith(PAYER)
                                .signedBy(PAYER, SUPPLY_KEY)
                                .setNode(4)
                                .txnId(DUPLICATE_TXN_ID)
                                .via(tokenMintTxn),
                        mintToken(FUNGIBLE_TOKEN, 100L)
                                .payingWith(PAYER)
                                .signedBy(PAYER, SUPPLY_KEY)
                                .txnId(DUPLICATE_TXN_ID)
                                .setNode(3)
                                .hasPrecheck(DUPLICATE_TRANSACTION),
                        validateChargedUsdWithinWithTxnSize(
                                tokenMintTxn,
                                txnSize -> expectedTokenMintFungibleFullFeeUsd(
                                        Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateChargedAccount(tokenMintTxn, PAYER));
            }
        }

        @Nested
        @DisplayName("TokenMint Failures on Pre-Handle")
        class TokenMintFailuresOnPreHandle {

            @LeakyEmbeddedHapiTest(reason = MUST_SKIP_INGEST)
            @DisplayName("TokenMint - invalid payer signature fails on pre-handle - network fee only")
            final Stream<DynamicTest> tokenMintInvalidPayerSigFailsOnPreHandle() {
                final String INNER_ID = "token-mint-txn-inner-id";

                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
                SigControl invalidSig = keyShape.signedWith(sigs(ON, OFF));

                return hapiTest(
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        newKeyNamed(SUPPLY_KEY),
                        tokenCreate(FUNGIBLE_TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .initialSupply(0L)
                                .supplyKey(SUPPLY_KEY)
                                .treasury(TREASURY),
                        cryptoTransfer(movingHbar(ONE_HBAR).between(DEFAULT_PAYER, "4")),
                        mintToken(FUNGIBLE_TOKEN, 100L)
                                .payingWith(PAYER)
                                .sigControl(forKey(PAYER_KEY, invalidSig))
                                .signedBy(PAYER, SUPPLY_KEY)
                                .setNode("4")
                                .via(INNER_ID)
                                .hasKnownStatus(INVALID_PAYER_SIGNATURE),
                        getTxnRecord(INNER_ID).assertingNothingAboutHashes().logged(),
                        validateChargedUsdWithinWithTxnSize(
                                INNER_ID,
                                txnSize -> expectedTokenMintNetworkFeeOnlyUsd(
                                        Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateChargedAccount(INNER_ID, "4"));
            }
        }
    }
}
