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
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.burnToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingHbar;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsdWithin;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTokenBurnFungibleFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTokenBurnNetworkFeeOnlyUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTokenBurnNftFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.validateChargedFeeToUsd;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_TX_FEE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_PAYER_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOKEN_BURN_AMOUNT;
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
 * Tests for TokenBurn simple fees with extras.
 * Validates that fees are correctly calculated based on:
 * - Number of signatures (extras beyond included)
 * - For fungible tokens: amount doesn't affect fees
 * - For NFTs: number of serials affects fees (extras beyond included)
 */
@Tag(MATS)
@Tag(SIMPLE_FEES)
@HapiTestLifecycle
public class TokenBurnSimpleFeesTest {

    private static final String PAYER = "payer";
    private static final String SUPPLY_KEY = "supplyKey";
    private static final String PAYER_KEY = "payerKey";
    private static final String FUNGIBLE_TOKEN = "fungibleToken";
    private static final String NFT_TOKEN = "nftToken";

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
                            .fee(ONE_HUNDRED_HBARS)
                            .via("tokenBurnTxn"),
                    validateChargedUsdWithin(
                            "tokenBurnTxn",
                            expectedTokenBurnFungibleFullFeeUsd(2L), // 2 sigs
                            0.001));
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
                            .fee(ONE_HUNDRED_HBARS)
                            .via("tokenBurnTxn"),
                    validateChargedUsdWithin(
                            "tokenBurnTxn",
                            expectedTokenBurnFungibleFullFeeUsd(2L), // 2 sigs - amount doesn't affect fee
                            0.001));
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
                            .fee(ONE_HUNDRED_HBARS)
                            .via("tokenBurnTxn"),
                    validateChargedUsdWithin(
                            "tokenBurnTxn",
                            expectedTokenBurnFungibleFullFeeUsd(3L), // 3 sigs (2 payer + 1 supply)
                            0.001));
        }
    }

    @Nested
    @DisplayName("TokenBurn NFT Simple Fees Positive Test Cases")
    class TokenBurnNftPositiveTestCases {

        @HapiTest
        @DisplayName("TokenBurn NFT - base fees for single serial")
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
                            .signedBy(PAYER, SUPPLY_KEY)
                            .fee(ONE_HUNDRED_HBARS),
                    burnToken(NFT_TOKEN, List.of(1L))
                            .payingWith(PAYER)
                            .signedBy(PAYER, SUPPLY_KEY)
                            .fee(ONE_HUNDRED_HBARS)
                            .via("tokenBurnTxn"),
                    validateChargedUsdWithin(
                            "tokenBurnTxn",
                            expectedTokenBurnNftFullFeeUsd(2L, 1L), // 2 sigs, 1 serial
                            0.001));
        }

        @HapiTest
        @DisplayName("TokenBurn NFT - multiple serials extra fee")
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
                            .signedBy(PAYER, SUPPLY_KEY)
                            .fee(ONE_HUNDRED_HBARS),
                    burnToken(NFT_TOKEN, List.of(1L, 2L, 3L))
                            .payingWith(PAYER)
                            .signedBy(PAYER, SUPPLY_KEY)
                            .fee(ONE_HUNDRED_HBARS)
                            .via("tokenBurnTxn"),
                    validateChargedUsdWithin(
                            "tokenBurnTxn",
                            expectedTokenBurnNftFullFeeUsd(2L, 3L), // 2 sigs, 3 serials
                            0.001));
        }

        @HapiTest
        @DisplayName("TokenBurn NFT with threshold key - extra signatures and serials")
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
                            .signedBy(PAYER, SUPPLY_KEY)
                            .fee(ONE_HUNDRED_HBARS),
                    burnToken(NFT_TOKEN, List.of(1L, 2L))
                            .payingWith(PAYER)
                            .sigControl(forKey(PAYER_KEY, validSig))
                            .signedBy(PAYER, SUPPLY_KEY)
                            .fee(ONE_HUNDRED_HBARS)
                            .via("tokenBurnTxn"),
                    validateChargedUsdWithin(
                            "tokenBurnTxn",
                            expectedTokenBurnNftFullFeeUsd(3L, 2L), // 3 sigs, 2 serials
                            0.001));
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
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();

                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(SUPPLY_KEY),
                        tokenCreate(FUNGIBLE_TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .initialSupply(1000L)
                                .supplyKey(SUPPLY_KEY)
                                .treasury(PAYER),
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                        burnToken(FUNGIBLE_TOKEN, 100L)
                                .signedBy(PAYER) // Missing supply key signature
                                .via("tokenBurnTxn")
                                .hasPrecheck(INVALID_SIGNATURE),
                        getTxnRecord("tokenBurnTxn").hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND),
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        withOpContext((spec, log) -> {
                            assertEquals(initialBalance.get(), afterBalance.get());
                        }));
            }

            @HapiTest
            @DisplayName("TokenBurn - insufficient tx fee fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenBurnInsufficientTxFeeFailsOnIngest() {
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();

                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(SUPPLY_KEY),
                        tokenCreate(FUNGIBLE_TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .initialSupply(1000L)
                                .supplyKey(SUPPLY_KEY)
                                .treasury(PAYER),
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                        burnToken(FUNGIBLE_TOKEN, 100L)
                                .payingWith(PAYER)
                                .signedBy(PAYER, SUPPLY_KEY)
                                .fee(1L) // Fee too low
                                .via("tokenBurnTxn")
                                .hasPrecheck(INSUFFICIENT_TX_FEE),
                        getTxnRecord("tokenBurnTxn").hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND),
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        withOpContext((spec, log) -> {
                            assertEquals(initialBalance.get(), afterBalance.get());
                        }));
            }

            @HapiTest
            @DisplayName("TokenBurn - invalid burn amount fails")
            final Stream<DynamicTest> tokenBurnInvalidAmountFails() {
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();

                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(SUPPLY_KEY),
                        tokenCreate(FUNGIBLE_TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .initialSupply(1000L)
                                .supplyKey(SUPPLY_KEY)
                                .treasury(PAYER),
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                        burnToken(FUNGIBLE_TOKEN, -1L) // Invalid: -1 amount
                                .payingWith(PAYER)
                                .signedBy(PAYER, SUPPLY_KEY)
                                .via("tokenBurnTxn")
                                .hasPrecheck(INVALID_TOKEN_BURN_AMOUNT),
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        withOpContext((spec, log) -> {
                            assertEquals(initialBalance.get(), afterBalance.get());
                        }));
            }

            @HapiTest
            @DisplayName("TokenBurn - no supply key fails")
            final Stream<DynamicTest> tokenBurnNoSupplyKeyFails() {
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();

                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        tokenCreate(FUNGIBLE_TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .initialSupply(1000L)
                                // No supply key
                                .treasury(PAYER),
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                        burnToken(FUNGIBLE_TOKEN, 100L)
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .fee(ONE_HUNDRED_HBARS)
                                .via("tokenBurnTxn")
                                .hasKnownStatus(TOKEN_HAS_NO_SUPPLY_KEY),
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        withOpContext((spec, log) -> {
                            assertTrue(initialBalance.get() > afterBalance.get());
                        }),
                        validateChargedFeeToUsd(
                                "tokenBurnTxn",
                                initialBalance,
                                afterBalance,
                                expectedTokenBurnFungibleFullFeeUsd(1L),
                                0.001));
            }
        }

        @Nested
        @DisplayName("TokenBurn Failures on Pre-Handle")
        class TokenBurnFailuresOnPreHandle {

            @LeakyEmbeddedHapiTest(reason = MUST_SKIP_INGEST)
            @DisplayName("TokenBurn - invalid payer signature fails on pre-handle - network fee only")
            final Stream<DynamicTest> tokenBurnInvalidPayerSigFailsOnPreHandle() {
                final AtomicLong initialNodeBalance = new AtomicLong();
                final AtomicLong afterNodeBalance = new AtomicLong();

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
                        cryptoTransfer(movingHbar(ONE_HBAR).between(GENESIS, "0.0.4")),
                        getAccountBalance("0.0.4").exposingBalanceTo(initialNodeBalance::set),
                        burnToken(FUNGIBLE_TOKEN, 100L)
                                .payingWith(PAYER)
                                .sigControl(forKey(PAYER_KEY, invalidSig))
                                .signedBy(PAYER, SUPPLY_KEY)
                                .fee(ONE_HUNDRED_HBARS)
                                .setNode("0.0.4")
                                .via(INNER_ID)
                                .hasKnownStatus(INVALID_PAYER_SIGNATURE),
                        getTxnRecord(INNER_ID).assertingNothingAboutHashes().logged(),
                        getAccountBalance("0.0.4").exposingBalanceTo(afterNodeBalance::set),
                        withOpContext((spec, log) -> {
                            assertTrue(initialNodeBalance.get() > afterNodeBalance.get());
                        }),
                        validateChargedFeeToUsd(
                                INNER_ID,
                                initialNodeBalance,
                                afterNodeBalance,
                                expectedTokenBurnNetworkFeeOnlyUsd(2L),
                                0.001));
            }
        }
    }
}
