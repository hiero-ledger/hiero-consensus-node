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
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingHbar;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsdWithin;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTokenMintFungibleFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTokenMintNetworkFeeOnlyUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.validateChargedFeeToUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.SIGNATURE_FEE_AFTER_MULTIPLIER;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.TOKEN_MINT_NFT_FEE_USD;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_TX_FEE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_PAYER_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.RECORD_NOT_FOUND;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_HAS_NO_SUPPLY_KEY;
import static com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.ByteString;
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
                            .payingWith(PAYER)
                            .fee(ONE_HUNDRED_HBARS),
                    mintToken(FUNGIBLE_TOKEN, 1L)
                            .payingWith(PAYER)
                            .signedBy(PAYER, SUPPLY_KEY)
                            .fee(ONE_HUNDRED_HBARS)
                            .via("tokenMintTxn"),
                    validateChargedUsdWithin(
                            "tokenMintTxn",
                            expectedTokenMintFungibleFullFeeUsd(2L), // 2 sigs
                            0.001));
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
                            .payingWith(PAYER)
                            .fee(ONE_HUNDRED_HBARS),
                    mintToken(FUNGIBLE_TOKEN, 1000L) // 1000 units
                            .payingWith(PAYER)
                            .signedBy(PAYER, SUPPLY_KEY)
                            .fee(ONE_HUNDRED_HBARS)
                            .via("tokenMintTxn"),
                    validateChargedUsdWithin(
                            "tokenMintTxn",
                            expectedTokenMintFungibleFullFeeUsd(2L), // 2 sigs - amount doesn't affect fee
                            0.001));
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
                            .fee(ONE_HUNDRED_HBARS)
                            .sigControl(forKey(PAYER_KEY, validSig)),
                    mintToken(FUNGIBLE_TOKEN, 100L)
                            .payingWith(PAYER)
                            .sigControl(forKey(PAYER_KEY, validSig))
                            .signedBy(PAYER, SUPPLY_KEY)
                            .fee(ONE_HUNDRED_HBARS)
                            .via("tokenMintTxn"),
                    validateChargedUsdWithin(
                            "tokenMintTxn",
                            expectedTokenMintFungibleFullFeeUsd(3L), // 3 sigs (2 payer + 1 supply)
                            0.001));
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
                            .payingWith(PAYER)
                            .fee(ONE_HUNDRED_HBARS),
                    mintToken(NFT_TOKEN, List.of(ByteString.copyFromUtf8("metadata1")))
                            .payingWith(PAYER)
                            .signedBy(PAYER, SUPPLY_KEY)
                            .fee(ONE_HUNDRED_HBARS)
                            .via("tokenMintTxn"),
                    validateChargedUsdWithin(
                            "tokenMintTxn", TOKEN_MINT_NFT_FEE_USD + SIGNATURE_FEE_AFTER_MULTIPLIER, 0.001));
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
                            .payingWith(PAYER)
                            .fee(ONE_HUNDRED_HBARS),
                    mintToken(
                                    NFT_TOKEN,
                                    List.of(
                                            ByteString.copyFromUtf8("metadata1"),
                                            ByteString.copyFromUtf8("metadata2"),
                                            ByteString.copyFromUtf8("metadata3")))
                            .payingWith(PAYER)
                            .signedBy(PAYER, SUPPLY_KEY)
                            .fee(ONE_HUNDRED_HBARS)
                            .via("tokenMintTxn"),
                    validateChargedUsdWithin(
                            "tokenMintTxn", 3 * TOKEN_MINT_NFT_FEE_USD + SIGNATURE_FEE_AFTER_MULTIPLIER, 0.001));
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
                            .payingWith(PAYER)
                            .fee(ONE_HUNDRED_HBARS),
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
                            .fee(ONE_HUNDRED_HBARS)
                            .via("tokenMintTxn"),
                    validateChargedUsdWithin(
                            "tokenMintTxn", 5 * TOKEN_MINT_NFT_FEE_USD + SIGNATURE_FEE_AFTER_MULTIPLIER, 0.001));
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
                            .fee(ONE_HUNDRED_HBARS)
                            .sigControl(forKey(PAYER_KEY, validSig)),
                    mintToken(
                                    NFT_TOKEN,
                                    List.of(ByteString.copyFromUtf8("metadata1"), ByteString.copyFromUtf8("metadata2")))
                            .payingWith(PAYER)
                            .sigControl(forKey(PAYER_KEY, validSig))
                            .signedBy(PAYER, SUPPLY_KEY)
                            .fee(ONE_HUNDRED_HBARS)
                            .via("tokenMintTxn"),
                    validateChargedUsdWithin(
                            "tokenMintTxn", 2 * TOKEN_MINT_NFT_FEE_USD + 2 * SIGNATURE_FEE_AFTER_MULTIPLIER, 0.001));
        }
    }

    @Nested
    @DisplayName("TokenMint Simple Fees Negative Test Cases")
    class TokenMintNegativeTestCases {

        @Nested
        @DisplayName("TokenMint Failures on Ingest and Handle")
        class TokenMintFailuresOnIngest {

            @HapiTest
            @DisplayName("TokenMint - missing supply key signature fails at handle - full fee charged")
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
                                .payingWith(PAYER)
                                .fee(ONE_HUNDRED_HBARS),
                        mintToken(FUNGIBLE_TOKEN, 100L)
                                .payingWith(PAYER)
                                .signedBy(PAYER) // Missing supply key signature
                                .fee(ONE_HUNDRED_HBARS)
                                .via("tokenMintTxn")
                                .hasKnownStatus(INVALID_SIGNATURE),
                        validateChargedUsdWithin(
                                "tokenMintTxn",
                                expectedTokenMintFungibleFullFeeUsd(1L), // 1 sig (payer only)
                                0.001));
            }

            @HapiTest
            @DisplayName("TokenMint - insufficient tx fee fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenMintInsufficientTxFeeFailsOnIngest() {
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();

                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        newKeyNamed(SUPPLY_KEY),
                        tokenCreate(FUNGIBLE_TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .initialSupply(0L)
                                .supplyKey(SUPPLY_KEY)
                                .treasury(TREASURY)
                                .payingWith(PAYER)
                                .fee(ONE_HUNDRED_HBARS),
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                        mintToken(FUNGIBLE_TOKEN, 100L)
                                .payingWith(PAYER)
                                .signedBy(PAYER, SUPPLY_KEY)
                                .fee(1L) // Fee too low
                                .via("tokenMintTxn")
                                .hasPrecheck(INSUFFICIENT_TX_FEE),
                        getTxnRecord("tokenMintTxn").hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND),
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        withOpContext((spec, log) -> {
                            assertEquals(initialBalance.get(), afterBalance.get());
                        }));
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
                                .payingWith(PAYER)
                                .fee(ONE_HUNDRED_HBARS),
                        mintToken(FUNGIBLE_TOKEN, 100L)
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .fee(ONE_HUNDRED_HBARS)
                                .via("tokenMintTxn")
                                .hasKnownStatus(TOKEN_HAS_NO_SUPPLY_KEY),
                        validateChargedUsdWithin(
                                "tokenMintTxn",
                                expectedTokenMintFungibleFullFeeUsd(1L), // 1 sig (payer only)
                                0.001));
            }
        }

        @Nested
        @DisplayName("TokenMint Failures on Pre-Handle")
        class TokenMintFailuresOnPreHandle {

            @LeakyEmbeddedHapiTest(reason = MUST_SKIP_INGEST)
            @DisplayName("TokenMint - invalid payer signature fails on pre-handle - network fee only")
            final Stream<DynamicTest> tokenMintInvalidPayerSigFailsOnPreHandle() {
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();
                final AtomicLong initialNodeBalance = new AtomicLong();
                final AtomicLong afterNodeBalance = new AtomicLong();

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
                                .treasury(TREASURY)
                                .fee(ONE_HUNDRED_HBARS),
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                        cryptoTransfer(movingHbar(ONE_HBAR).between(DEFAULT_PAYER, "0.0.4"))
                                .fee(ONE_HUNDRED_HBARS),
                        getAccountBalance("0.0.4").exposingBalanceTo(initialNodeBalance::set),
                        mintToken(FUNGIBLE_TOKEN, 100L)
                                .payingWith(PAYER)
                                .sigControl(forKey(PAYER_KEY, invalidSig))
                                .signedBy(PAYER, SUPPLY_KEY)
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
                                expectedTokenMintNetworkFeeOnlyUsd(2L),
                                0.001));
            }
        }
    }
}
