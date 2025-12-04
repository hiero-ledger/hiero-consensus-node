// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.fees;

import static com.google.protobuf.ByteString.copyFromUtf8;
import static com.hedera.services.bdd.junit.TestTags.MATS;
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
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsd;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.THOUSAND_HBAR;
import static com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * Test suite for CryptoTransfer simple fees (HIP-1261).
 * Validates the simple fee model for various transfer scenarios.
 */
@Tag(MATS)
@Tag(SIMPLE_FEES)
public class CryptoTransferSimpleFeesSuite {
    private static final double HBAR_TRANSFER_BASE_FEE = 0.0001;
    private static final double FUNGIBLE_TOKEN_BASE_FEE = 0.001;
    private static final double NFT_BASE_FEE = 0.001;
    private static final double CUSTOM_FEE_TOKEN_FEE = 0.001;
    private static final double SINGLE_HOOK_FEE = 1.0;
    private static final double HOOK_OVERHEAD_FEE = 0.0050;
    private static final double ADDITIONAL_ACCOUNT_FEE = 0.0001;
    private static final double ADDITIONAL_TOKEN_FEE = 0.0001;
    private static final double ADDITIONAL_NFT_SERIAL_FEE = 0.0001;
    private static final String PAYER = "payer";
    private static final String RECEIVER = "receiver";
    private static final String RECEIVER2 = "receiver2";
    private static final String RECEIVER3 = "receiver3";
    private static final String FEE_COLLECTOR = "feeCollector";
    private static final String FUNGIBLE_TOKEN = "fungibleToken";
    private static final String FUNGIBLE_TOKEN_2 = "fungibleToken2";
    private static final String FUNGIBLE_TOKEN_WITH_FEES = "fungibleTokenWithFees";
    private static final String NFT_TOKEN = "nftToken";
    private static final String NFT_TOKEN_2 = "nftToken2";
    private static final String NFT_TOKEN_WITH_FEES = "nftTokenWithFees";
    private static final String HOOK_CONTRACT = "TruePreHook";

    @HapiTest
    @DisplayName("DEFAULT: Simple 2-account HBAR transfer")
    final Stream<DynamicTest> defaultSimpleHbarTransfer() {
        return hapiTest(
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(RECEIVER).balance(0L),
                cryptoTransfer(movingHbar(ONE_HBAR).between(PAYER, RECEIVER))
                        .payingWith(PAYER)
                        .signedBy(PAYER)
                        .fee(ONE_HBAR)
                        .via("simpleHbarTxn"),
                validateChargedUsd("simpleHbarTxn", HBAR_TRANSFER_BASE_FEE));
    }

    @HapiTest
    @DisplayName("DEFAULT: Multi-account HBAR transfer (3 receivers)")
    final Stream<DynamicTest> defaultMultiAccountHbarTransfer() {
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
                        .via("multiHbarTxn"),
                validateChargedUsd("multiHbarTxn", HBAR_TRANSFER_BASE_FEE + 2 * ADDITIONAL_ACCOUNT_FEE));
    }

    @HapiTest
    @DisplayName("TOKEN_FUNGIBLE_COMMON: Single fungible token transfer")
    final Stream<DynamicTest> fungibleCommonSingleToken() {
        return hapiTest(
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(RECEIVER).balance(0L),
                tokenCreate(FUNGIBLE_TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
                        .initialSupply(1000L)
                        .treasury(PAYER)
                        .payingWith(PAYER),
                tokenAssociate(RECEIVER, FUNGIBLE_TOKEN),
                cryptoTransfer(moving(100, FUNGIBLE_TOKEN).between(PAYER, RECEIVER))
                        .payingWith(PAYER)
                        .signedBy(PAYER)
                        .fee(ONE_HBAR)
                        .via("fungibleSingleTxn"),
                validateChargedUsd("fungibleSingleTxn", FUNGIBLE_TOKEN_BASE_FEE));
    }

    @HapiTest
    @DisplayName("TOKEN_FUNGIBLE_COMMON: Multiple fungible tokens")
    final Stream<DynamicTest> fungibleCommonMultipleTokens() {
        return hapiTest(
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(RECEIVER).balance(0L),
                tokenCreate(FUNGIBLE_TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
                        .initialSupply(1000L)
                        .treasury(PAYER)
                        .payingWith(PAYER),
                tokenCreate(FUNGIBLE_TOKEN_2)
                        .tokenType(FUNGIBLE_COMMON)
                        .initialSupply(1000L)
                        .treasury(PAYER)
                        .payingWith(PAYER),
                tokenAssociate(RECEIVER, FUNGIBLE_TOKEN, FUNGIBLE_TOKEN_2),
                cryptoTransfer(
                                moving(100, FUNGIBLE_TOKEN).between(PAYER, RECEIVER),
                                moving(50, FUNGIBLE_TOKEN_2).between(PAYER, RECEIVER))
                        .payingWith(PAYER)
                        .signedBy(PAYER)
                        .fee(ONE_HBAR)
                        .via("fungibleMultiTxn"),
                validateChargedUsd("fungibleMultiTxn", FUNGIBLE_TOKEN_BASE_FEE + ADDITIONAL_TOKEN_FEE));
    }

    @HapiTest
    @DisplayName("TOKEN_FUNGIBLE_COMMON: Fungible + HBAR mixed transfer")
    final Stream<DynamicTest> fungibleCommonWithHbar() {
        return hapiTest(
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(RECEIVER).balance(0L),
                tokenCreate(FUNGIBLE_TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
                        .initialSupply(1000L)
                        .treasury(PAYER)
                        .payingWith(PAYER),
                tokenAssociate(RECEIVER, FUNGIBLE_TOKEN),
                cryptoTransfer(
                                movingHbar(ONE_HBAR).between(PAYER, RECEIVER),
                                moving(100, FUNGIBLE_TOKEN).between(PAYER, RECEIVER))
                        .payingWith(PAYER)
                        .signedBy(PAYER)
                        .fee(ONE_HBAR)
                        .via("fungibleHbarTxn"),
                validateChargedUsd("fungibleHbarTxn", FUNGIBLE_TOKEN_BASE_FEE));
    }

    @HapiTest
    @DisplayName("TOKEN_NON_FUNGIBLE_UNIQUE: Single NFT transfer")
    final Stream<DynamicTest> nftUniqueSingleNft() {
        return hapiTest(
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(RECEIVER).balance(0L),
                tokenCreate(NFT_TOKEN)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0L)
                        .supplyKey(PAYER)
                        .treasury(PAYER)
                        .payingWith(PAYER),
                mintToken(NFT_TOKEN, List.of(copyFromUtf8("metadata1"))),
                tokenAssociate(RECEIVER, NFT_TOKEN),
                cryptoTransfer(movingUnique(NFT_TOKEN, 1L).between(PAYER, RECEIVER))
                        .payingWith(PAYER)
                        .signedBy(PAYER)
                        .fee(ONE_HBAR)
                        .via("nftSingleTxn"),
                validateChargedUsd("nftSingleTxn", NFT_BASE_FEE));
    }

    @HapiTest
    @DisplayName("TOKEN_NON_FUNGIBLE_UNIQUE: Multiple NFTs same collection")
    final Stream<DynamicTest> nftUniqueMultipleSameCollection() {
        return hapiTest(
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(RECEIVER).balance(0L),
                tokenCreate(NFT_TOKEN)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0L)
                        .supplyKey(PAYER)
                        .treasury(PAYER)
                        .payingWith(PAYER),
                mintToken(
                        NFT_TOKEN,
                        List.of(copyFromUtf8("metadata1"), copyFromUtf8("metadata2"), copyFromUtf8("metadata3"))),
                tokenAssociate(RECEIVER, NFT_TOKEN),
                cryptoTransfer(movingUnique(NFT_TOKEN, 1L, 2L, 3L).between(PAYER, RECEIVER))
                        .payingWith(PAYER)
                        .signedBy(PAYER)
                        .fee(ONE_HBAR)
                        .via("nftMultiSameTxn"),
                validateChargedUsd("nftMultiSameTxn", NFT_BASE_FEE + 2 * ADDITIONAL_NFT_SERIAL_FEE));
    }

    @HapiTest
    @DisplayName("TOKEN_NON_FUNGIBLE_UNIQUE: NFTs from different collections")
    final Stream<DynamicTest> nftUniqueDifferentCollections() {
        return hapiTest(
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(RECEIVER).balance(0L),
                tokenCreate(NFT_TOKEN)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0L)
                        .supplyKey(PAYER)
                        .treasury(PAYER)
                        .payingWith(PAYER),
                tokenCreate(NFT_TOKEN_2)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0L)
                        .supplyKey(PAYER)
                        .treasury(PAYER)
                        .payingWith(PAYER),
                mintToken(NFT_TOKEN, List.of(copyFromUtf8("metadata1"))),
                mintToken(NFT_TOKEN_2, List.of(copyFromUtf8("metadata2"))),
                tokenAssociate(RECEIVER, NFT_TOKEN, NFT_TOKEN_2),
                cryptoTransfer(
                                movingUnique(NFT_TOKEN, 1L).between(PAYER, RECEIVER),
                                movingUnique(NFT_TOKEN_2, 1L).between(PAYER, RECEIVER))
                        .payingWith(PAYER)
                        .signedBy(PAYER)
                        .fee(ONE_HBAR)
                        .via("nftDiffCollectionsTxn"),
                validateChargedUsd("nftDiffCollectionsTxn", NFT_BASE_FEE + ADDITIONAL_NFT_SERIAL_FEE));
    }

    @HapiTest
    @DisplayName("TOKEN_NON_FUNGIBLE_UNIQUE: NFT + HBAR mixed transfer")
    final Stream<DynamicTest> nftUniqueWithHbar() {
        return hapiTest(
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(RECEIVER).balance(0L),
                tokenCreate(NFT_TOKEN)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0L)
                        .supplyKey(PAYER)
                        .treasury(PAYER)
                        .payingWith(PAYER),
                mintToken(NFT_TOKEN, List.of(copyFromUtf8("metadata1"))),
                tokenAssociate(RECEIVER, NFT_TOKEN),
                cryptoTransfer(
                                movingHbar(ONE_HBAR).between(PAYER, RECEIVER),
                                movingUnique(NFT_TOKEN, 1L).between(PAYER, RECEIVER))
                        .payingWith(PAYER)
                        .signedBy(PAYER)
                        .fee(ONE_HBAR)
                        .via("nftHbarTxn"),
                validateChargedUsd("nftHbarTxn", NFT_BASE_FEE));
    }

    @HapiTest
    @DisplayName("TOKEN_FUNGIBLE_COMMON_WITH_CUSTOM_FEES: Fungible with fixed HBAR fee")
    final Stream<DynamicTest> fungibleWithCustomFeesFixedHbar() {
        return hapiTest(
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(RECEIVER).balance(0L),
                cryptoCreate(FEE_COLLECTOR).balance(0L),
                tokenCreate(FUNGIBLE_TOKEN_WITH_FEES)
                        .tokenType(FUNGIBLE_COMMON)
                        .initialSupply(1000L)
                        .withCustom(fixedHbarFee(ONE_HBAR, FEE_COLLECTOR))
                        .treasury(PAYER)
                        .payingWith(PAYER),
                tokenAssociate(RECEIVER, FUNGIBLE_TOKEN_WITH_FEES),
                cryptoTransfer(moving(100, FUNGIBLE_TOKEN_WITH_FEES).between(PAYER, RECEIVER))
                        .payingWith(PAYER)
                        .signedBy(PAYER)
                        .fee(10 * ONE_HBAR)
                        .via("fungibleCustomFeeTxn"),
                validateChargedUsd("fungibleCustomFeeTxn", FUNGIBLE_TOKEN_BASE_FEE + CUSTOM_FEE_TOKEN_FEE));
    }

    @HapiTest
    @DisplayName("TOKEN_NON_FUNGIBLE_UNIQUE_WITH_CUSTOM_FEES: NFT with royalty fee")
    final Stream<DynamicTest> nftWithCustomFeesRoyalty() {
        return hapiTest(
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(RECEIVER).balance(0L),
                cryptoCreate(FEE_COLLECTOR).balance(0L),
                tokenCreate(NFT_TOKEN_WITH_FEES)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0L)
                        .supplyKey(PAYER)
                        .withCustom(royaltyFeeNoFallback(1, 10, FEE_COLLECTOR))
                        .treasury(PAYER)
                        .payingWith(PAYER),
                mintToken(NFT_TOKEN_WITH_FEES, List.of(copyFromUtf8("metadata1"))),
                tokenAssociate(RECEIVER, NFT_TOKEN_WITH_FEES),
                cryptoTransfer(movingUnique(NFT_TOKEN_WITH_FEES, 1L).between(PAYER, RECEIVER))
                        .payingWith(PAYER)
                        .signedBy(PAYER)
                        .fee(10 * ONE_HBAR)
                        .via("nftCustomFeeTxn"),
                validateChargedUsd("nftCustomFeeTxn", NFT_BASE_FEE + CUSTOM_FEE_TOKEN_FEE));
    }

    @HapiTest
    @DisplayName("CUSTOM: Mixed standard + custom fee tokens")
    final Stream<DynamicTest> customFeeMixedStandardAndCustom() {
        return hapiTest(
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(RECEIVER).balance(0L),
                cryptoCreate(FEE_COLLECTOR).balance(0L),
                tokenCreate(FUNGIBLE_TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
                        .initialSupply(1000L)
                        .treasury(PAYER)
                        .payingWith(PAYER),
                tokenCreate(FUNGIBLE_TOKEN_WITH_FEES)
                        .tokenType(FUNGIBLE_COMMON)
                        .initialSupply(1000L)
                        .withCustom(fixedHbarFee(ONE_HBAR, FEE_COLLECTOR))
                        .treasury(PAYER)
                        .payingWith(PAYER),
                tokenAssociate(RECEIVER, FUNGIBLE_TOKEN, FUNGIBLE_TOKEN_WITH_FEES),
                cryptoTransfer(
                                moving(100, FUNGIBLE_TOKEN).between(PAYER, RECEIVER),
                                moving(100, FUNGIBLE_TOKEN_WITH_FEES).between(PAYER, RECEIVER))
                        .payingWith(PAYER)
                        .signedBy(PAYER)
                        .fee(10 * ONE_HBAR)
                        .via("mixedStandardCustomTxn"),
                validateChargedUsd(
                        "mixedStandardCustomTxn",
                        FUNGIBLE_TOKEN_BASE_FEE + CUSTOM_FEE_TOKEN_FEE + ADDITIONAL_TOKEN_FEE));
    }

    @HapiTest
    @DisplayName("CUSTOM: Multiple custom fee tokens")
    final Stream<DynamicTest> customFeeMultipleCustomFeeTokens() {
        return hapiTest(
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(RECEIVER).balance(0L),
                cryptoCreate(FEE_COLLECTOR).balance(0L),
                tokenCreate(FUNGIBLE_TOKEN_WITH_FEES)
                        .tokenType(FUNGIBLE_COMMON)
                        .initialSupply(1000L)
                        .withCustom(fixedHbarFee(ONE_HBAR, FEE_COLLECTOR))
                        .treasury(PAYER)
                        .payingWith(PAYER),
                tokenCreate(FUNGIBLE_TOKEN_2)
                        .tokenType(FUNGIBLE_COMMON)
                        .initialSupply(1000L)
                        .withCustom(fixedHbarFee(ONE_HBAR / 2, FEE_COLLECTOR))
                        .treasury(PAYER)
                        .payingWith(PAYER),
                tokenAssociate(RECEIVER, FUNGIBLE_TOKEN_WITH_FEES, FUNGIBLE_TOKEN_2),
                cryptoTransfer(
                                moving(100, FUNGIBLE_TOKEN_WITH_FEES).between(PAYER, RECEIVER),
                                moving(100, FUNGIBLE_TOKEN_2).between(PAYER, RECEIVER))
                        .payingWith(PAYER)
                        .signedBy(PAYER)
                        .fee(10 * ONE_HBAR)
                        .via("multipleCustomFeeTxn"),
                validateChargedUsd(
                        "multipleCustomFeeTxn", FUNGIBLE_TOKEN_BASE_FEE + CUSTOM_FEE_TOKEN_FEE + ADDITIONAL_TOKEN_FEE));
    }

    @HapiTest
    @DisplayName("CUSTOM: Custom fee fungible + custom fee NFT")
    final Stream<DynamicTest> customFeeMultipleFungibleAndNft() {
        return hapiTest(
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(RECEIVER).balance(0L),
                cryptoCreate(FEE_COLLECTOR).balance(0L),
                tokenCreate(FUNGIBLE_TOKEN_WITH_FEES)
                        .tokenType(FUNGIBLE_COMMON)
                        .initialSupply(1000L)
                        .withCustom(fixedHbarFee(ONE_HBAR, FEE_COLLECTOR))
                        .treasury(PAYER)
                        .payingWith(PAYER),
                tokenCreate(NFT_TOKEN_WITH_FEES)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0L)
                        .supplyKey(PAYER)
                        .withCustom(royaltyFeeNoFallback(1, 10, FEE_COLLECTOR))
                        .treasury(PAYER)
                        .payingWith(PAYER),
                mintToken(NFT_TOKEN_WITH_FEES, List.of(copyFromUtf8("metadata1"))),
                tokenAssociate(RECEIVER, FUNGIBLE_TOKEN_WITH_FEES, NFT_TOKEN_WITH_FEES),
                cryptoTransfer(
                                moving(100, FUNGIBLE_TOKEN_WITH_FEES).between(PAYER, RECEIVER),
                                movingUnique(NFT_TOKEN_WITH_FEES, 1L).between(PAYER, RECEIVER))
                        .payingWith(PAYER)
                        .signedBy(PAYER)
                        .fee(10 * ONE_HBAR)
                        .via("customFungibleNftTxn"),
                validateChargedUsd("customFungibleNftTxn", FUNGIBLE_TOKEN_BASE_FEE + CUSTOM_FEE_TOKEN_FEE));
    }

    @HapiTest
    @DisplayName("BOUNDARY: Self-transfer (1 account)")
    final Stream<DynamicTest> accountBoundarySelfTransfer() {
        return hapiTest(
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                cryptoTransfer(movingHbar(ONE_HBAR).between(PAYER, PAYER))
                        .payingWith(PAYER)
                        .signedBy(PAYER)
                        .fee(ONE_HBAR)
                        .via("selfTransferTxn"),
                validateChargedUsd("selfTransferTxn", HBAR_TRANSFER_BASE_FEE));
    }

    @HapiTest
    @DisplayName("BOUNDARY: Three accounts (1 over threshold)")
    final Stream<DynamicTest> accountBoundaryThreeAccounts() {
        return hapiTest(
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(RECEIVER).balance(0L),
                cryptoCreate(RECEIVER2).balance(0L),
                cryptoTransfer(
                                movingHbar(ONE_HBAR).between(PAYER, RECEIVER),
                                movingHbar(ONE_HBAR).between(PAYER, RECEIVER2))
                        .payingWith(PAYER)
                        .signedBy(PAYER)
                        .fee(ONE_HBAR)
                        .via("threeAccountsTxn"),
                validateChargedUsd("threeAccountsTxn", HBAR_TRANSFER_BASE_FEE + ADDITIONAL_ACCOUNT_FEE));
    }

    @HapiTest
    @DisplayName("COUNTING: Fungible token with multiple recipients counts as 1")
    final Stream<DynamicTest> fungibleCountingMultipleRecipients() {
        return hapiTest(
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(RECEIVER).balance(0L),
                cryptoCreate(RECEIVER2).balance(0L),
                cryptoCreate(RECEIVER3).balance(0L),
                tokenCreate(FUNGIBLE_TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
                        .initialSupply(1000L)
                        .treasury(PAYER)
                        .payingWith(PAYER),
                tokenAssociate(RECEIVER, FUNGIBLE_TOKEN),
                tokenAssociate(RECEIVER2, FUNGIBLE_TOKEN),
                tokenAssociate(RECEIVER3, FUNGIBLE_TOKEN),
                cryptoTransfer(
                                moving(100, FUNGIBLE_TOKEN).between(PAYER, RECEIVER),
                                moving(100, FUNGIBLE_TOKEN).between(PAYER, RECEIVER2),
                                moving(100, FUNGIBLE_TOKEN).between(PAYER, RECEIVER3))
                        .payingWith(PAYER)
                        .signedBy(PAYER)
                        .fee(ONE_HBAR)
                        .via("fungibleMultiRecipientTxn"),
                validateChargedUsd("fungibleMultiRecipientTxn", FUNGIBLE_TOKEN_BASE_FEE + 2 * ADDITIONAL_ACCOUNT_FEE));
    }

    @HapiTest
    @DisplayName("COUNTING: Large NFT batch (10 serials)")
    final Stream<DynamicTest> nftCountingLargeCollection() {
        return hapiTest(
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(RECEIVER).balance(0L),
                tokenCreate(NFT_TOKEN)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0L)
                        .supplyKey(PAYER)
                        .treasury(PAYER)
                        .payingWith(PAYER),
                mintToken(
                        NFT_TOKEN,
                        List.of(
                                copyFromUtf8("metadata1"),
                                copyFromUtf8("metadata2"),
                                copyFromUtf8("metadata3"),
                                copyFromUtf8("metadata4"),
                                copyFromUtf8("metadata5"),
                                copyFromUtf8("metadata6"),
                                copyFromUtf8("metadata7"),
                                copyFromUtf8("metadata8"),
                                copyFromUtf8("metadata9"),
                                copyFromUtf8("metadata10"))),
                tokenAssociate(RECEIVER, NFT_TOKEN),
                cryptoTransfer(movingUnique(NFT_TOKEN, 1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L)
                                .between(PAYER, RECEIVER))
                        .payingWith(PAYER)
                        .signedBy(PAYER)
                        .fee(ONE_HBAR)
                        .via("nftLargeBatchTxn"),
                validateChargedUsd("nftLargeBatchTxn", NFT_BASE_FEE + 9 * ADDITIONAL_NFT_SERIAL_FEE));
    }

    @HapiTest
    @DisplayName("COMPLEX: HBAR + fungible + NFT in one transaction")
    final Stream<DynamicTest> complexMixedAllTypes() {
        return hapiTest(
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(RECEIVER).balance(0L),
                tokenCreate(FUNGIBLE_TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
                        .initialSupply(1000L)
                        .treasury(PAYER)
                        .payingWith(PAYER),
                tokenCreate(NFT_TOKEN)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0L)
                        .supplyKey(PAYER)
                        .treasury(PAYER)
                        .payingWith(PAYER),
                mintToken(NFT_TOKEN, List.of(copyFromUtf8("metadata1"))),
                tokenAssociate(RECEIVER, FUNGIBLE_TOKEN, NFT_TOKEN),
                cryptoTransfer(
                                movingHbar(ONE_HBAR).between(PAYER, RECEIVER),
                                moving(100, FUNGIBLE_TOKEN).between(PAYER, RECEIVER),
                                movingUnique(NFT_TOKEN, 1L).between(PAYER, RECEIVER))
                        .payingWith(PAYER)
                        .signedBy(PAYER)
                        .fee(ONE_HBAR)
                        .via("complexAllTypesTxn"),
                validateChargedUsd("complexAllTypesTxn", FUNGIBLE_TOKEN_BASE_FEE));
    }

    @HapiTest
    @DisplayName("COMPLEX: Multiple fungible + multiple NFT")
    final Stream<DynamicTest> complexMultipleTokensAndNfts() {
        return hapiTest(
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(RECEIVER).balance(0L),
                tokenCreate(FUNGIBLE_TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
                        .initialSupply(1000L)
                        .treasury(PAYER)
                        .payingWith(PAYER),
                tokenCreate(FUNGIBLE_TOKEN_2)
                        .tokenType(FUNGIBLE_COMMON)
                        .initialSupply(1000L)
                        .treasury(PAYER)
                        .payingWith(PAYER),
                tokenCreate(NFT_TOKEN)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0L)
                        .supplyKey(PAYER)
                        .treasury(PAYER)
                        .payingWith(PAYER),
                tokenCreate(NFT_TOKEN_2)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0L)
                        .supplyKey(PAYER)
                        .treasury(PAYER)
                        .payingWith(PAYER),
                mintToken(NFT_TOKEN, List.of(copyFromUtf8("metadata1"))),
                mintToken(NFT_TOKEN_2, List.of(copyFromUtf8("metadata2"))),
                tokenAssociate(RECEIVER, FUNGIBLE_TOKEN, FUNGIBLE_TOKEN_2, NFT_TOKEN, NFT_TOKEN_2),
                cryptoTransfer(
                                moving(100, FUNGIBLE_TOKEN).between(PAYER, RECEIVER),
                                moving(100, FUNGIBLE_TOKEN_2).between(PAYER, RECEIVER),
                                movingUnique(NFT_TOKEN, 1L).between(PAYER, RECEIVER),
                                movingUnique(NFT_TOKEN_2, 1L).between(PAYER, RECEIVER))
                        .payingWith(PAYER)
                        .signedBy(PAYER)
                        .fee(ONE_HBAR)
                        .via("complexMultiTokensNftsTxn"),
                validateChargedUsd(
                        "complexMultiTokensNftsTxn",
                        FUNGIBLE_TOKEN_BASE_FEE + ADDITIONAL_TOKEN_FEE + ADDITIONAL_NFT_SERIAL_FEE));
    }

    @HapiTest
    @DisplayName("COMPLEX: Maximum complexity - all token types")
    final Stream<DynamicTest> complexMaximumComplexity() {
        return hapiTest(
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(RECEIVER).balance(0L),
                cryptoCreate(FEE_COLLECTOR).balance(0L),
                tokenCreate(FUNGIBLE_TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
                        .initialSupply(1000L)
                        .treasury(PAYER)
                        .payingWith(PAYER),
                tokenCreate(FUNGIBLE_TOKEN_2)
                        .tokenType(FUNGIBLE_COMMON)
                        .initialSupply(1000L)
                        .treasury(PAYER)
                        .payingWith(PAYER),
                tokenCreate(FUNGIBLE_TOKEN_WITH_FEES)
                        .tokenType(FUNGIBLE_COMMON)
                        .initialSupply(1000L)
                        .withCustom(fixedHbarFee(ONE_HBAR, FEE_COLLECTOR))
                        .treasury(PAYER)
                        .payingWith(PAYER),
                tokenCreate(NFT_TOKEN)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0L)
                        .supplyKey(PAYER)
                        .treasury(PAYER)
                        .payingWith(PAYER),
                tokenCreate(NFT_TOKEN_WITH_FEES)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0L)
                        .supplyKey(PAYER)
                        .withCustom(royaltyFeeNoFallback(1, 10, FEE_COLLECTOR))
                        .treasury(PAYER)
                        .payingWith(PAYER),
                mintToken(NFT_TOKEN, List.of(copyFromUtf8("metadata1"), copyFromUtf8("metadata2"))),
                mintToken(NFT_TOKEN_WITH_FEES, List.of(copyFromUtf8("metadata3"))),
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
                        .via("complexMaximumTxn"),
                validateChargedUsd(
                        "complexMaximumTxn",
                        FUNGIBLE_TOKEN_BASE_FEE
                                + 2 * ADDITIONAL_TOKEN_FEE
                                + CUSTOM_FEE_TOKEN_FEE
                                + 2 * ADDITIONAL_NFT_SERIAL_FEE));
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled", "hooks.hooksEnabled"})
    @DisplayName("HOOKS: HBAR transfer with single hook")
    final Stream<DynamicTest> hbarTransferWithSingleHook() {
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
                validateChargedUsd("hbarWithHookTxn", SINGLE_HOOK_FEE + HBAR_TRANSFER_BASE_FEE + HOOK_OVERHEAD_FEE));
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled", "hooks.hooksEnabled"})
    @DisplayName("HOOKS: Fungible token transfer with single hook")
    final Stream<DynamicTest> fungibleTokenTransferWithSingleHook() {
        return hapiTest(
                uploadInitCode(HOOK_CONTRACT),
                contractCreate(HOOK_CONTRACT).gas(5_000_000),
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS).withHook(accountAllowanceHook(1L, HOOK_CONTRACT)),
                cryptoCreate(RECEIVER).balance(0L),
                tokenCreate(FUNGIBLE_TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
                        .initialSupply(1000L)
                        .treasury(PAYER)
                        .payingWith(PAYER),
                tokenAssociate(RECEIVER, FUNGIBLE_TOKEN),
                cryptoTransfer(moving(100, FUNGIBLE_TOKEN).between(PAYER, RECEIVER))
                        .withPreHookFor(PAYER, 1L, 5_000_000L, "")
                        .payingWith(PAYER)
                        .signedBy(PAYER)
                        .fee(50 * ONE_HBAR)
                        .via("fungibleWithHookTxn"),
                validateChargedUsd(
                        "fungibleWithHookTxn", SINGLE_HOOK_FEE + HBAR_TRANSFER_BASE_FEE + HOOK_OVERHEAD_FEE));
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled", "hooks.hooksEnabled"})
    @DisplayName("HOOKS: NFT transfer with two hooks (sender + receiver)")
    final Stream<DynamicTest> nftTransferWithTwoHooks() {
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
                        .payingWith(PAYER),
                mintToken(NFT_TOKEN, List.of(copyFromUtf8("metadata1"))),
                tokenAssociate(RECEIVER, NFT_TOKEN),
                cryptoTransfer(movingUnique(NFT_TOKEN, 1L).between(PAYER, RECEIVER))
                        .withNftSenderPreHookFor(PAYER, 1L, 5_000_000L, "")
                        .withNftReceiverPreHookFor(RECEIVER, 2L, 5_000_000L, "")
                        .payingWith(PAYER)
                        .signedBy(PAYER)
                        .fee(50 * ONE_HBAR)
                        .via("nftWithTwoHooksTxn"),
                validateChargedUsd(
                        "nftWithTwoHooksTxn", 2 * SINGLE_HOOK_FEE + HBAR_TRANSFER_BASE_FEE + HOOK_OVERHEAD_FEE));
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled", "hooks.hooksEnabled"})
    @DisplayName("HOOKS: Mixed transfer with multiple hooks (HBAR + fungible + NFT)")
    final Stream<DynamicTest> mixedTransferWithMultipleHooks() {
        return hapiTest(
                uploadInitCode(HOOK_CONTRACT),
                contractCreate(HOOK_CONTRACT).gas(5_000_000),
                cryptoCreate(PAYER).balance(THOUSAND_HBAR).withHook(accountAllowanceHook(1L, HOOK_CONTRACT)),
                cryptoCreate(RECEIVER).balance(ONE_HUNDRED_HBARS).withHook(accountAllowanceHook(2L, HOOK_CONTRACT)),
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
                mintToken(NFT_TOKEN, List.of(copyFromUtf8("metadata1"))),
                tokenAssociate(RECEIVER, FUNGIBLE_TOKEN, NFT_TOKEN),
                cryptoTransfer(
                                movingHbar(ONE_HBAR).between(PAYER, RECEIVER),
                                moving(100, FUNGIBLE_TOKEN).between(PAYER, RECEIVER),
                                movingUnique(NFT_TOKEN, 1L).between(PAYER, RECEIVER))
                        .withPreHookFor(PAYER, 1L, 5_000_000L, "")
                        .withPreHookFor(RECEIVER, 2L, 5_000_000L, "")
                        .withNftSenderPreHookFor(PAYER, 1L, 5_000_000L, "")
                        .withNftReceiverPreHookFor(RECEIVER, 2L, 5_000_000L, "")
                        .payingWith(PAYER)
                        .signedBy(PAYER)
                        .fee(THOUSAND_HBAR)
                        .via("mixedWithHooksTxn"),
                validateChargedUsd(
                        "mixedWithHooksTxn", 6 * SINGLE_HOOK_FEE + HBAR_TRANSFER_BASE_FEE + HOOK_OVERHEAD_FEE));
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled", "hooks.hooksEnabled"})
    @DisplayName("HOOKS: Custom fee tokens with hooks")
    final Stream<DynamicTest> complexTransferCustomFeesAndHooks() {
        return hapiTest(
                uploadInitCode(HOOK_CONTRACT),
                contractCreate(HOOK_CONTRACT).gas(5_000_000),
                cryptoCreate(PAYER).balance(THOUSAND_HBAR).withHook(accountAllowanceHook(1L, HOOK_CONTRACT)),
                cryptoCreate(RECEIVER).balance(THOUSAND_HBAR).withHook(accountAllowanceHook(2L, HOOK_CONTRACT)),
                cryptoCreate(FEE_COLLECTOR).balance(0L),
                tokenCreate(FUNGIBLE_TOKEN_WITH_FEES)
                        .tokenType(FUNGIBLE_COMMON)
                        .initialSupply(1000L)
                        .withCustom(fixedHbarFee(ONE_HBAR, FEE_COLLECTOR))
                        .treasury(PAYER)
                        .payingWith(PAYER),
                tokenCreate(NFT_TOKEN_WITH_FEES)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0L)
                        .supplyKey(PAYER)
                        .withCustom(royaltyFeeNoFallback(1, 10, FEE_COLLECTOR))
                        .treasury(PAYER)
                        .fee(ONE_HUNDRED_HBARS)
                        .payingWith(PAYER),
                mintToken(NFT_TOKEN_WITH_FEES, List.of(copyFromUtf8("metadata1")))
                        .fee(ONE_HUNDRED_HBARS),
                tokenAssociate(RECEIVER, FUNGIBLE_TOKEN_WITH_FEES, NFT_TOKEN_WITH_FEES)
                        .fee(ONE_HUNDRED_HBARS),
                cryptoTransfer(
                                moving(100, FUNGIBLE_TOKEN_WITH_FEES).between(PAYER, RECEIVER),
                                movingUnique(NFT_TOKEN_WITH_FEES, 1L).between(PAYER, RECEIVER))
                        .withPreHookFor(PAYER, 1L, 5_000_000L, "")
                        .withNftSenderPreHookFor(PAYER, 1L, 5_000_000L, "")
                        .withNftReceiverPreHookFor(RECEIVER, 2L, 5_000_000L, "")
                        .payingWith(PAYER)
                        .signedBy(PAYER)
                        .fee(ONE_HUNDRED_HBARS)
                        .via("customFeesWithHooksTxn"),
                validateChargedUsd(
                        "customFeesWithHooksTxn", 3 * SINGLE_HOOK_FEE + HBAR_TRANSFER_BASE_FEE + HOOK_OVERHEAD_FEE));
    }
}
