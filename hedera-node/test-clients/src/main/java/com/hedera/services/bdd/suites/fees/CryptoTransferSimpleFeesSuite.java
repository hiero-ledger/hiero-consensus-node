// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.fees;

import static com.google.protobuf.ByteString.copyFromUtf8;
import static com.hedera.services.bdd.junit.TestTags.SIMPLE_FEES;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.accountAllowanceHook;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedHbarFee;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.royaltyFeeNoFallback;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingHbar;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingUnique;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsd;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * Test suite for CryptoTransfer simple fees (HIP-1261).
 * Validates the simple fee model for various transfer scenarios.
 */
@Tag(SIMPLE_FEES)
@HapiTestLifecycle
public class CryptoTransferSimpleFeesSuite {
    // Fee tiers
    private static final double HBAR_TRANSFER_FEE = 0.0001;
    public static final double TOKEN_TRANSFER_FEE = 0.001;
    private static final double TOKEN_TRANSFER_CUSTOM_FEE = 0.002;

    // Extras
    private static final double ADDITIONAL_ACCOUNT_FEE = 0.0001;
    private static final double TOKEN_TYPES_EXTRA_FEE = 0.0001;
    private static final double ADDITIONAL_NFT_SERIAL_FEE = 0.0001;
    private static final double HOOK_INVOCATION_USD = 0.005;
    private static final double NFT_TRANSFER_BASE_USD = 0.001;

    // Entity names
    private static final String PAYER = "payer";
    private static final String RECEIVER = "receiver";
    private static final String RECEIVER2 = "receiver2";
    private static final String RECEIVER3 = "receiver3";
    private static final String FEE_COLLECTOR = "feeCollector";
    private static final String FUNGIBLE_TOKEN = "fungibleToken";
    private static final String FUNGIBLE_TOKEN_2 = "fungibleToken2";
    private static final String FUNGIBLE_TOKEN_WITH_FEES = "fungibleTokenWithFees";
    private static final String NFT_TOKEN = "nftToken";
    private static final String NFT_TOKEN_WITH_FEES = "nftTokenWithFees";
    private static final String HOOK_CONTRACT = "TruePreHook";

    @BeforeAll
    public static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(Map.of("hooks.hooksEnabled", "true"));
    }

    // ==================== FEE TIER TESTS ====================

    @HapiTest
    @DisplayName("HBAR: Simple 2-account transfer")
    final Stream<DynamicTest> hbarSimpleTransfer() {
        return hapiTest(
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(RECEIVER).balance(0L),
                cryptoTransfer(movingHbar(ONE_HBAR).between(PAYER, RECEIVER))
                        .payingWith(PAYER)
                        .signedBy(PAYER)
                        .fee(ONE_HBAR)
                        .via("hbarTxn"),
                validateChargedUsd("hbarTxn", HBAR_TRANSFER_FEE));
    }

    @HapiTest
    @DisplayName("FUNGIBLE: Single token transfer")
    final Stream<DynamicTest> fungibleSingleTransfer() {
        return hapiTest(
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(RECEIVER).balance(0L),
                tokenCreate(FUNGIBLE_TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
                        .initialSupply(1000L)
                        .treasury(PAYER)
                        .fee(ONE_HUNDRED_HBARS)
                        .payingWith(PAYER),
                tokenAssociate(RECEIVER, FUNGIBLE_TOKEN),
                cryptoTransfer(moving(100, FUNGIBLE_TOKEN).between(PAYER, RECEIVER))
                        .payingWith(PAYER)
                        .signedBy(PAYER)
                        .fee(ONE_HBAR)
                        .via("fungibleTxn"),
                validateChargedUsd("fungibleTxn", TOKEN_TRANSFER_FEE));
    }

    @HapiTest
    @DisplayName("NFT: Single NFT transfer (same fee as fungible)")
    final Stream<DynamicTest> nftSingleTransfer() {
        return hapiTest(
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(RECEIVER).balance(0L),
                tokenCreate(NFT_TOKEN)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0L)
                        .supplyKey(PAYER)
                        .treasury(PAYER)
                        .fee(ONE_HUNDRED_HBARS)
                        .payingWith(PAYER),
                mintToken(NFT_TOKEN, List.of(metadata(1))),
                tokenAssociate(RECEIVER, NFT_TOKEN),
                cryptoTransfer(movingUnique(NFT_TOKEN, 1L).between(PAYER, RECEIVER))
                        .payingWith(PAYER)
                        .signedBy(PAYER)
                        .fee(ONE_HBAR)
                        .via("nftTxn"),
                validateChargedUsd("nftTxn", TOKEN_TRANSFER_FEE));
    }

    @HapiTest
    @DisplayName("CUSTOM FEES: Token with custom fee")
    final Stream<DynamicTest> tokenWithCustomFee() {
        return hapiTest(
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(RECEIVER).balance(0L),
                cryptoCreate(FEE_COLLECTOR).balance(0L),
                tokenCreate(FUNGIBLE_TOKEN_WITH_FEES)
                        .tokenType(FUNGIBLE_COMMON)
                        .initialSupply(1000L)
                        .withCustom(fixedHbarFee(ONE_HBAR, FEE_COLLECTOR))
                        .treasury(PAYER)
                        .fee(ONE_HUNDRED_HBARS)
                        .payingWith(PAYER),
                tokenAssociate(RECEIVER, FUNGIBLE_TOKEN_WITH_FEES),
                cryptoTransfer(moving(100, FUNGIBLE_TOKEN_WITH_FEES).between(PAYER, RECEIVER))
                        .payingWith(PAYER)
                        .signedBy(PAYER)
                        .fee(10 * ONE_HBAR)
                        .via("customFeeTxn"),
                validateChargedUsd("customFeeTxn", TOKEN_TRANSFER_CUSTOM_FEE));
    }

    // ==================== KEY FIX: MIXED FT+NFT SINGLE CHARGE ====================

    @HapiTest
    @DisplayName("MIXED FT+NFT: Single TOKEN_TRANSFER_BASE charge (key consolidation fix)")
    final Stream<DynamicTest> mixedFtNftSingleCharge() {
        // This verifies the fix: FT+NFT should NOT double-charge
        return hapiTest(
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(RECEIVER).balance(0L),
                tokenCreate(FUNGIBLE_TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
                        .initialSupply(1000L)
                        .treasury(PAYER)
                        .fee(ONE_HUNDRED_HBARS)
                        .payingWith(PAYER),
                tokenCreate(NFT_TOKEN)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0L)
                        .supplyKey(PAYER)
                        .treasury(PAYER)
                        .fee(ONE_HUNDRED_HBARS)
                        .payingWith(PAYER),
                mintToken(NFT_TOKEN, List.of(metadata(1))),
                tokenAssociate(RECEIVER, FUNGIBLE_TOKEN, NFT_TOKEN),
                cryptoTransfer(
                                moving(100, FUNGIBLE_TOKEN).between(PAYER, RECEIVER),
                                movingUnique(NFT_TOKEN, 1L).between(PAYER, RECEIVER))
                        .payingWith(PAYER)
                        .signedBy(PAYER)
                        .fee(ONE_HBAR)
                        .via("mixedFtNftTxn"),
                // Charge for base token transfer and extra token type!
                validateChargedUsd("mixedFtNftTxn", TOKEN_TRANSFER_FEE + TOKEN_TYPES_EXTRA_FEE));
    }

    @HapiTest
    @DisplayName("MIXED CUSTOM: FT + NFT with custom fees (single custom fee tier)")
    final Stream<DynamicTest> mixedFtNftWithCustomFees() {
        return hapiTest(
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(RECEIVER).balance(0L),
                cryptoCreate(FEE_COLLECTOR).balance(0L),
                tokenCreate(FUNGIBLE_TOKEN_WITH_FEES)
                        .tokenType(FUNGIBLE_COMMON)
                        .initialSupply(1000L)
                        .withCustom(fixedHbarFee(ONE_HBAR, FEE_COLLECTOR))
                        .treasury(PAYER)
                        .fee(ONE_HUNDRED_HBARS)
                        .payingWith(PAYER),
                tokenCreate(NFT_TOKEN_WITH_FEES)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0L)
                        .supplyKey(PAYER)
                        .withCustom(royaltyFeeNoFallback(1, 10, FEE_COLLECTOR))
                        .treasury(PAYER)
                        .fee(ONE_HUNDRED_HBARS)
                        .payingWith(PAYER),
                mintToken(NFT_TOKEN_WITH_FEES, List.of(metadata(1))),
                tokenAssociate(RECEIVER, FUNGIBLE_TOKEN_WITH_FEES, NFT_TOKEN_WITH_FEES),
                cryptoTransfer(
                                moving(100, FUNGIBLE_TOKEN_WITH_FEES).between(PAYER, RECEIVER),
                                movingUnique(NFT_TOKEN_WITH_FEES, 1L).between(PAYER, RECEIVER))
                        .payingWith(PAYER)
                        .signedBy(PAYER)
                        .fee(10 * ONE_HBAR)
                        .via("mixedCustomTxn"),
                // Charge for token transfer with custom fee and extra token type
                validateChargedUsd("mixedCustomTxn", TOKEN_TRANSFER_CUSTOM_FEE + TOKEN_TYPES_EXTRA_FEE));
    }

    // ==================== EXTRAS TESTS ====================

    @HapiTest
    @DisplayName("ACCOUNTS EXTRA: 4 accounts (2 over threshold)")
    final Stream<DynamicTest> accountsExtraCharge() {
        return hapiTest(
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(RECEIVER).balance(0L),
                cryptoCreate(RECEIVER2).balance(0L),
                cryptoCreate(RECEIVER3).balance(0L),
                cryptoTransfer(
                                movingHbar(ONE_HBAR).between(PAYER, RECEIVER),
                                movingHbar(ONE_HBAR).between(PAYER, RECEIVER2),
                                movingHbar(ONE_HBAR).between(PAYER, RECEIVER3))
                        .payingWith(PAYER)
                        .signedBy(PAYER)
                        .fee(ONE_HBAR)
                        .via("multiAccountTxn"),
                // Base + 2 extra accounts (4 total, 2 included)
                validateChargedUsd("multiAccountTxn", HBAR_TRANSFER_FEE + 2 * ADDITIONAL_ACCOUNT_FEE));
    }

    @HapiTest
    @DisplayName("FUNGIBLE_TOKENS EXTRA: 2 tokens (1 over threshold)")
    final Stream<DynamicTest> fungibleTokensExtraCharge() {
        return hapiTest(
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(RECEIVER).balance(0L),
                tokenCreate(FUNGIBLE_TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
                        .initialSupply(1000L)
                        .treasury(PAYER)
                        .fee(ONE_HUNDRED_HBARS)
                        .payingWith(PAYER),
                tokenCreate(FUNGIBLE_TOKEN_2)
                        .tokenType(FUNGIBLE_COMMON)
                        .initialSupply(1000L)
                        .treasury(PAYER)
                        .fee(ONE_HUNDRED_HBARS)
                        .payingWith(PAYER),
                tokenAssociate(RECEIVER, FUNGIBLE_TOKEN, FUNGIBLE_TOKEN_2),
                cryptoTransfer(
                                moving(100, FUNGIBLE_TOKEN).between(PAYER, RECEIVER),
                                moving(50, FUNGIBLE_TOKEN_2).between(PAYER, RECEIVER))
                        .payingWith(PAYER)
                        .signedBy(PAYER)
                        .fee(ONE_HBAR)
                        .via("multiTokenTxn"),
                validateChargedUsd("multiTokenTxn", TOKEN_TRANSFER_FEE + TOKEN_TYPES_EXTRA_FEE));
    }

    @HapiTest
    @DisplayName("NFT_SERIALS EXTRA: 3 serials (2 over threshold)")
    final Stream<DynamicTest> nftSerialsExtraCharge() {
        return hapiTest(
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(RECEIVER).balance(0L),
                tokenCreate(NFT_TOKEN)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0L)
                        .supplyKey(PAYER)
                        .treasury(PAYER)
                        .fee(ONE_HUNDRED_HBARS)
                        .payingWith(PAYER),
                mintToken(NFT_TOKEN, List.of(metadata(1), metadata(2), metadata(3))),
                tokenAssociate(RECEIVER, NFT_TOKEN),
                cryptoTransfer(movingUnique(NFT_TOKEN, 1L, 2L, 3L).between(PAYER, RECEIVER))
                        .payingWith(PAYER)
                        .signedBy(PAYER)
                        .fee(ONE_HBAR)
                        .via("multiSerialTxn"),
                validateChargedUsd("multiSerialTxn", TOKEN_TRANSFER_FEE + 2 * ADDITIONAL_NFT_SERIAL_FEE));
    }

    @HapiTest
    @DisplayName("CUSTOM_FEE EXTRA: Mix of standard + custom fee tokens")
    final Stream<DynamicTest> customFeeExtraCharge() {
        return hapiTest(
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(RECEIVER).balance(0L),
                cryptoCreate(FEE_COLLECTOR).balance(0L),
                tokenCreate(FUNGIBLE_TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
                        .initialSupply(1000L)
                        .treasury(PAYER)
                        .fee(ONE_HUNDRED_HBARS)
                        .payingWith(PAYER),
                tokenCreate(FUNGIBLE_TOKEN_WITH_FEES)
                        .tokenType(FUNGIBLE_COMMON)
                        .initialSupply(1000L)
                        .withCustom(fixedHbarFee(ONE_HBAR, FEE_COLLECTOR))
                        .treasury(PAYER)
                        .fee(ONE_HUNDRED_HBARS)
                        .payingWith(PAYER),
                tokenAssociate(RECEIVER, FUNGIBLE_TOKEN, FUNGIBLE_TOKEN_WITH_FEES),
                cryptoTransfer(
                                moving(100, FUNGIBLE_TOKEN).between(PAYER, RECEIVER),
                                moving(100, FUNGIBLE_TOKEN_WITH_FEES).between(PAYER, RECEIVER))
                        .payingWith(PAYER)
                        .signedBy(PAYER)
                        .fee(10 * ONE_HBAR)
                        .via("mixedStandardCustomTxn"),
                // Custom fee tier + 1 extra token
                validateChargedUsd("mixedStandardCustomTxn", TOKEN_TRANSFER_CUSTOM_FEE + TOKEN_TYPES_EXTRA_FEE));
    }

    // ==================== COMPLEX SCENARIO ====================

    @HapiTest
    @DisplayName("COMPLEX: Maximum complexity - all token types with extras")
    final Stream<DynamicTest> complexMaximumComplexity() {
        return hapiTest(
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(RECEIVER).balance(0L),
                cryptoCreate(FEE_COLLECTOR).balance(0L),
                tokenCreate(FUNGIBLE_TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
                        .initialSupply(1000L)
                        .treasury(PAYER)
                        .fee(ONE_HUNDRED_HBARS)
                        .payingWith(PAYER),
                tokenCreate(FUNGIBLE_TOKEN_2)
                        .tokenType(FUNGIBLE_COMMON)
                        .initialSupply(1000L)
                        .treasury(PAYER)
                        .fee(ONE_HUNDRED_HBARS)
                        .payingWith(PAYER),
                tokenCreate(FUNGIBLE_TOKEN_WITH_FEES)
                        .tokenType(FUNGIBLE_COMMON)
                        .initialSupply(1000L)
                        .withCustom(fixedHbarFee(ONE_HBAR, FEE_COLLECTOR))
                        .treasury(PAYER)
                        .fee(ONE_HUNDRED_HBARS)
                        .payingWith(PAYER),
                tokenCreate(NFT_TOKEN)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0L)
                        .supplyKey(PAYER)
                        .treasury(PAYER)
                        .fee(ONE_HUNDRED_HBARS)
                        .payingWith(PAYER),
                tokenCreate(NFT_TOKEN_WITH_FEES)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0L)
                        .supplyKey(PAYER)
                        .withCustom(royaltyFeeNoFallback(1, 10, FEE_COLLECTOR))
                        .treasury(PAYER)
                        .fee(ONE_HUNDRED_HBARS)
                        .payingWith(PAYER),
                mintToken(NFT_TOKEN, List.of(metadata(1), metadata(2))),
                mintToken(NFT_TOKEN_WITH_FEES, List.of(metadata(3))),
                tokenAssociate(
                        RECEIVER,
                        FUNGIBLE_TOKEN,
                        FUNGIBLE_TOKEN_2,
                        FUNGIBLE_TOKEN_WITH_FEES,
                        NFT_TOKEN,
                        NFT_TOKEN_WITH_FEES),
                cryptoTransfer(
                                movingHbar(ONE_HBAR).between(PAYER, RECEIVER),
                                moving(100, FUNGIBLE_TOKEN).between(PAYER, RECEIVER),
                                moving(100, FUNGIBLE_TOKEN_2).between(PAYER, RECEIVER),
                                moving(100, FUNGIBLE_TOKEN_WITH_FEES).between(PAYER, RECEIVER),
                                movingUnique(NFT_TOKEN, 1L, 2L).between(PAYER, RECEIVER),
                                movingUnique(NFT_TOKEN_WITH_FEES, 1L).between(PAYER, RECEIVER))
                        .payingWith(PAYER)
                        .signedBy(PAYER)
                        .fee(10 * ONE_HBAR)
                        .via("complexTxn"),
                // Custom fee tier (since custom fees present)
                // + 3 fungible tokens
                // + 3 NFT serials (6 total - 1 included)
                validateChargedUsd("complexTxn", TOKEN_TRANSFER_CUSTOM_FEE + 5 * TOKEN_TYPES_EXTRA_FEE));
    }

    // ==================== HOOK TESTS ====================

    @HapiTest
    @DisplayName("HOOKS: HBAR transfer with single hook")
    final Stream<DynamicTest> hbarTransferWithHook() {
        return hapiTest(
                uploadInitCode(HOOK_CONTRACT),
                contractCreate(HOOK_CONTRACT).gas(5_000_000),
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS).withHook(accountAllowanceHook(1L, HOOK_CONTRACT)),
                cryptoCreate(RECEIVER).balance(0L),
                cryptoTransfer(movingHbar(ONE_HBAR).between(PAYER, RECEIVER))
                        .withPreHookFor(PAYER, 1L, 5_000_000L, "")
                        .payingWith(PAYER)
                        .signedBy(PAYER)
                        .fee(50 * ONE_HBAR)
                        .via("hbarWithHookTxn"),
                sourcingContextual(spec -> {
                    final long tinybarGasCost =
                            5_000_000L * spec.ratesProvider().currentTinybarGasPrice();
                    final double usdGasCost = spec.ratesProvider().toUsdWithActiveRates(tinybarGasCost);
                    return validateChargedUsd("hbarWithHookTxn", HBAR_TRANSFER_FEE + HOOK_INVOCATION_USD + usdGasCost);
                }));
    }

    @HapiTest
    @DisplayName("HOOKS: Token transfer with single hook")
    final Stream<DynamicTest> tokenTransferWithHook() {
        return hapiTest(
                uploadInitCode(HOOK_CONTRACT),
                contractCreate(HOOK_CONTRACT).gas(5_000_000),
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS).withHook(accountAllowanceHook(1L, HOOK_CONTRACT)),
                cryptoCreate(RECEIVER).balance(0L),
                tokenCreate(FUNGIBLE_TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
                        .initialSupply(1000L)
                        .treasury(PAYER)
                        .fee(ONE_HUNDRED_HBARS)
                        .payingWith(PAYER),
                tokenAssociate(RECEIVER, FUNGIBLE_TOKEN),
                cryptoTransfer(moving(100, FUNGIBLE_TOKEN).between(PAYER, RECEIVER))
                        .withPreHookFor(PAYER, 1L, 5_000_000L, "")
                        .payingWith(PAYER)
                        .signedBy(PAYER)
                        .fee(50 * ONE_HBAR)
                        .via("tokenWithHookTxn"),
                sourcingContextual(spec -> {
                    final long tinybarGasCost =
                            5_000_000L * spec.ratesProvider().currentTinybarGasPrice();
                    final double usdGasCost = spec.ratesProvider().toUsdWithActiveRates(tinybarGasCost);
                    return validateChargedUsd(
                            "tokenWithHookTxn", TOKEN_TRANSFER_FEE + HOOK_INVOCATION_USD + usdGasCost);
                }));
    }

    @HapiTest
    @DisplayName("HOOKS: NFT transfer with multiple hooks (sender + receiver)")
    final Stream<DynamicTest> nftTransferWithMultipleHooks() {
        return hapiTest(
                uploadInitCode(HOOK_CONTRACT),
                contractCreate(HOOK_CONTRACT).gas(5_000_000),
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS).withHook(accountAllowanceHook(1L, HOOK_CONTRACT)),
                cryptoCreate(RECEIVER).balance(ONE_HUNDRED_HBARS).withHook(accountAllowanceHook(2L, HOOK_CONTRACT)),
                tokenCreate(NFT_TOKEN)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0L)
                        .supplyKey(PAYER)
                        .treasury(PAYER)
                        .fee(ONE_HUNDRED_HBARS)
                        .payingWith(PAYER),
                mintToken(NFT_TOKEN, List.of(metadata(1))),
                tokenAssociate(RECEIVER, NFT_TOKEN),
                cryptoTransfer(movingUnique(NFT_TOKEN, 1L).between(PAYER, RECEIVER))
                        .withNftSenderPreHookFor(PAYER, 1L, 5_000_000L, "")
                        .withNftReceiverPreHookFor(RECEIVER, 2L, 5_000_000L, "")
                        .payingWith(PAYER)
                        .signedBy(PAYER)
                        .fee(50 * ONE_HBAR)
                        .via("nftWithHooksTxn"),
                // 2 hooks (sender + receiver)
                sourcingContextual(spec -> {
                    final long tinybarGasCost =
                            5_000_000L * 2 * spec.ratesProvider().currentTinybarGasPrice();
                    final double usdGasCost = spec.ratesProvider().toUsdWithActiveRates(tinybarGasCost);
                    return validateChargedUsd(
                            "nftWithHooksTxn", NFT_TRANSFER_BASE_USD + 2 * HOOK_INVOCATION_USD + usdGasCost);
                }));
    }

    private static ByteString metadata(final int idNumber) {
        return copyFromUtf8("metadata" + idNumber);
    }
}
