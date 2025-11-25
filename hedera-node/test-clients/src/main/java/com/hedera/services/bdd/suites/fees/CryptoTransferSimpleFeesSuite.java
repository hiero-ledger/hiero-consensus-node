// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.fees;

import static com.google.protobuf.ByteString.copyFromUtf8;
import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.junit.TestTags.SIMPLE_FEES;
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
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.compareSimpleToOld;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.THOUSAND_HBAR;
import static com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;

import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * Test suite for CryptoTransfer simple fees (HIP-1261).
 * Compares old fee model vs new simple fee model to ensure 1-to-1 matching.
 */
@Tag(MATS)
@Tag(SIMPLE_FEES)
@HapiTestLifecycle
public class CryptoTransferSimpleFeesSuite {
    private static final String PAYER = "payer";
    private static final String RECEIVER = "receiver";
    private static final String RECEIVER2 = "receiver2";
    private static final String RECEIVER3 = "receiver3";
    private static final String FUNGIBLE_TOKEN = "fungibleToken";
    private static final String FUNGIBLE_TOKEN_2 = "fungibleToken2";
    private static final String FUNGIBLE_TOKEN_WITH_FEES = "fungibleTokenWithFees";
    private static final String NFT_TOKEN = "nftToken";
    private static final String NFT_TOKEN_2 = "nftToken2";
    private static final String NFT_TOKEN_WITH_FEES = "nftTokenWithFees";
    private static final String FEE_COLLECTOR = "feeCollector";
    private static final String HOOK_CONTRACT = "TruePreHook";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(Map.of("fees.simpleFeesEnabled", "true", "hooks.hooksEnabled", "true"));
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("DEFAULT: Simple 2-account HBAR transfer")
    final Stream<DynamicTest> defaultSimpleHbarTransfer() {
        return compareSimpleToOld(
                () -> Arrays.asList(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(RECEIVER).balance(0L),
                        cryptoTransfer(movingHbar(ONE_HBAR).between(PAYER, RECEIVER))
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .fee(ONE_HBAR)
                                .via("simpleHbarTxn")),
                "simpleHbarTxn",
                0.0001,
                1.0,
                0.0001,
                1.0);
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("DEFAULT: Multi-account HBAR transfer (3 receivers)")
    final Stream<DynamicTest> defaultMultiAccountHbarTransfer() {
        return compareSimpleToOld(
                () -> Arrays.asList(
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
                                .via("multiHbarTxn")),
                "multiHbarTxn",
                0.0001,
                1.0,
                0.0001,
                1.0);
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("TOKEN_FUNGIBLE_COMMON: Single fungible token transfer")
    final Stream<DynamicTest> fungibleCommonSingleToken() {
        return compareSimpleToOld(
                () -> Arrays.asList(
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
                                .via("fungibleSingleTxn")),
                "fungibleSingleTxn",
                0.0011,
                1.0,
                0.0011,
                1.0);
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("TOKEN_FUNGIBLE_COMMON: Multiple fungible tokens")
    final Stream<DynamicTest> fungibleCommonMultipleTokens() {
        return compareSimpleToOld(
                () -> Arrays.asList(
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
                                .via("fungibleMultiTxn")),
                "fungibleMultiTxn",
                0.0012,
                1.0,
                0.0012,
                1.0);
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("TOKEN_FUNGIBLE_COMMON: Fungible + HBAR mixed transfer")
    final Stream<DynamicTest> fungibleCommonWithHbar() {
        return compareSimpleToOld(
                () -> Arrays.asList(
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
                                .via("fungibleHbarTxn")),
                "fungibleHbarTxn",
                0.0011,
                1.0,
                0.0011,
                1.0);
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("TOKEN_NON_FUNGIBLE_UNIQUE: Single NFT transfer")
    final Stream<DynamicTest> nftUniqueSingleNft() {
        return compareSimpleToOld(
                () -> Arrays.asList(
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
                                .via("nftSingleTxn")),
                "nftSingleTxn",
                0.0011,
                1.0,
                0.0011,
                1.0);
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("TOKEN_NON_FUNGIBLE_UNIQUE: Multiple NFTs same collection")
    final Stream<DynamicTest> nftUniqueMultipleSameCollection() {
        return compareSimpleToOld(
                () -> Arrays.asList(
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
                                        copyFromUtf8("metadata3"))),
                        tokenAssociate(RECEIVER, NFT_TOKEN),
                        cryptoTransfer(movingUnique(NFT_TOKEN, 1L, 2L, 3L).between(PAYER, RECEIVER))
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .fee(ONE_HBAR)
                                .via("nftMultiSameTxn")),
                "nftMultiSameTxn",
                0.0015,
                1.0,
                0.0015,
                1.0);
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("TOKEN_NON_FUNGIBLE_UNIQUE: NFTs from different collections")
    final Stream<DynamicTest> nftUniqueDifferentCollections() {
        return compareSimpleToOld(
                () -> Arrays.asList(
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
                                .via("nftDiffCollectionsTxn")),
                "nftDiffCollectionsTxn",
                0.0012,
                1.0,
                0.0012,
                1.0);
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("TOKEN_NON_FUNGIBLE_UNIQUE: NFT + HBAR mixed transfer")
    final Stream<DynamicTest> nftUniqueWithHbar() {
        return compareSimpleToOld(
                () -> Arrays.asList(
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
                                .via("nftHbarTxn")),
                "nftHbarTxn",
                0.0011,
                1.0,
                0.0011,
                1.0);
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("TOKEN_FUNGIBLE_COMMON_WITH_CUSTOM_FEES: Fungible with fixed HBAR fee")
    final Stream<DynamicTest> fungibleWithCustomFeesFixedHbar() {
        return compareSimpleToOld(
                () -> Arrays.asList(
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
                                .via("fungibleCustomFeeTxn")),
                "fungibleCustomFeeTxn",
                0.0022,
                1.0,
                0.0022,
                1.0);
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("TOKEN_NON_FUNGIBLE_UNIQUE_WITH_CUSTOM_FEES: NFT with royalty fee")
    final Stream<DynamicTest> nftWithCustomFeesRoyalty() {
        return compareSimpleToOld(
                () -> Arrays.asList(
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
                                .via("nftCustomFeeTxn")),
                "nftCustomFeeTxn",
                0.0022,
                1.0,
                0.0022,
                1.0);
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("CUSTOM: Mixed standard + custom fee tokens")
    final Stream<DynamicTest> customFeeMixedStandardAndCustom() {
        return compareSimpleToOld(
                () -> Arrays.asList(
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
                                .via("mixedStandardCustomTxn")),
                "mixedStandardCustomTxn",
                0.0022,
                1.0,
                0.0022,
                1.0);
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("CUSTOM: Multiple custom fee tokens")
    final Stream<DynamicTest> customFeeMultipleCustomFeeTokens() {
        return compareSimpleToOld(
                () -> Arrays.asList(
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
                                .via("multipleCustomFeeTxn")),
                "multipleCustomFeeTxn",
                0.0025,
                1.0,
                0.0025,
                1.0);
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("CUSTOM: Custom fee fungible + custom fee NFT")
    final Stream<DynamicTest> customFeeMultipleFungibleAndNft() {
        return compareSimpleToOld(
                () -> Arrays.asList(
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
                                .via("customFungibleNftTxn")),
                "customFungibleNftTxn",
                0.0023,
                1.0,
                0.0023,
                1.0);
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("BOUNDARY: Self-transfer (1 account)")
    final Stream<DynamicTest> accountBoundarySelfTransfer() {
        return compareSimpleToOld(
                () -> Arrays.asList(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoTransfer(movingHbar(ONE_HBAR).between(PAYER, PAYER))
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .fee(ONE_HBAR)
                                .via("selfTransferTxn")),
                "selfTransferTxn",
                0.0001,
                1.0,
                0.0001,
                1.0);
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("BOUNDARY: Three accounts (1 over threshold)")
    final Stream<DynamicTest> accountBoundaryThreeAccounts() {
        return compareSimpleToOld(
                () -> Arrays.asList(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(RECEIVER).balance(0L),
                        cryptoCreate(RECEIVER2).balance(0L),
                        cryptoTransfer(
                                        movingHbar(ONE_HBAR).between(PAYER, RECEIVER),
                                        movingHbar(ONE_HBAR).between(PAYER, RECEIVER2))
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .fee(ONE_HBAR)
                                .via("threeAccountsTxn")),
                "threeAccountsTxn",
                0.0001,
                1.0,
                0.0001,
                1.0);
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("COUNTING: Fungible token with multiple recipients counts as 1")
    final Stream<DynamicTest> fungibleCountingMultipleRecipients() {
        return compareSimpleToOld(
                () -> Arrays.asList(
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
                                .via("fungibleMultiRecipientTxn")),
                "fungibleMultiRecipientTxn",
                0.0011,
                1.0,
                0.0011,
                1.0);
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("COUNTING: Large NFT batch (10 serials)")
    final Stream<DynamicTest> nftCountingLargeCollection() {
        return compareSimpleToOld(
                () -> Arrays.asList(
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
                                .via("nftLargeBatchTxn")),
                "nftLargeBatchTxn",
                0.0092,
                1.0,
                0.0092,
                1.0);
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("COMPLEX: HBAR + fungible + NFT in one transaction")
    final Stream<DynamicTest> complexMixedAllTypes() {
        return compareSimpleToOld(
                () -> Arrays.asList(
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
                                .via("complexAllTypesTxn")),
                "complexAllTypesTxn",
                0.0011,
                1.0,
                0.0011,
                1.0);
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("COMPLEX: Multiple fungible + multiple NFT")
    final Stream<DynamicTest> complexMultipleTokensAndNfts() {
        return compareSimpleToOld(
                () -> Arrays.asList(
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
                                .via("complexMultiTokensNftsTxn")),
                "complexMultiTokensNftsTxn",
                0.0013,
                1.0,
                0.0013,
                1.0);
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("COMPLEX: Maximum complexity - all token types")
    final Stream<DynamicTest> complexMaximumComplexity() {
        return compareSimpleToOld(
                () -> Arrays.asList(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(RECEIVER).balance(0L),
                        cryptoCreate(FEE_COLLECTOR).balance(0L),
                        // 2 standard fungible
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
                        // 1 custom fee fungible
                        tokenCreate(FUNGIBLE_TOKEN_WITH_FEES)
                                .tokenType(FUNGIBLE_COMMON)
                                .initialSupply(1000L)
                                .withCustom(fixedHbarFee(ONE_HBAR, FEE_COLLECTOR))
                                .treasury(PAYER)
                                .payingWith(PAYER),
                        // 1 standard NFT
                        tokenCreate(NFT_TOKEN)
                                .tokenType(NON_FUNGIBLE_UNIQUE)
                                .initialSupply(0L)
                                .supplyKey(PAYER)
                                .treasury(PAYER)
                                .payingWith(PAYER),
                        // 1 custom fee NFT
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
                                .via("complexMaximumTxn")),
                "complexMaximumTxn",
                0.0025,
                1.0,
                0.0025,
                1.0);
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled", "hooks.hooksEnabled"})
    @DisplayName("HOOKS: HBAR transfer with single hook")
    final Stream<DynamicTest> hbarTransferWithSingleHook() {
        return compareSimpleToOld(
                () -> Arrays.asList(
                        uploadInitCode(HOOK_CONTRACT),
                        contractCreate(HOOK_CONTRACT).gas(5_000_000),
                        cryptoCreate(PAYER)
                                .balance(ONE_HUNDRED_HBARS)
                                .withHook(accountAllowanceHook(1L, HOOK_CONTRACT)),
                        cryptoCreate(RECEIVER).balance(0L),
                        cryptoTransfer(movingHbar(ONE_HBAR).between(PAYER, RECEIVER))
                                .withPreHookFor(PAYER, 1L, 5_000_000L, "")
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .fee(50 * ONE_HBAR)
                                .via("hbarWithHookTxn")),
                "hbarWithHookTxn",
                1.0051,
                1.0,
                1.0051,
                1.0);
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled", "hooks.hooksEnabled"})
    @DisplayName("HOOKS: Fungible token transfer with single hook")
    final Stream<DynamicTest> fungibleTokenTransferWithSingleHook() {
        return compareSimpleToOld(
                () -> Arrays.asList(
                        uploadInitCode(HOOK_CONTRACT),
                        contractCreate(HOOK_CONTRACT).gas(5_000_000),
                        cryptoCreate(PAYER)
                                .balance(ONE_HUNDRED_HBARS)
                                .withHook(accountAllowanceHook(1L, HOOK_CONTRACT)),
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
                                .via("fungibleWithHookTxn")),
                "fungibleWithHookTxn",
                1.0051,
                1.0,
                1.0051,
                1.0);
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled", "hooks.hooksEnabled"})
    @DisplayName("HOOKS: NFT transfer with two hooks (sender + receiver)")
    final Stream<DynamicTest> nftTransferWithTwoHooks() {
        return compareSimpleToOld(
                () -> Arrays.asList(
                        uploadInitCode(HOOK_CONTRACT),
                        contractCreate(HOOK_CONTRACT).gas(5_000_000),
                        cryptoCreate(PAYER)
                                .balance(ONE_HUNDRED_HBARS)
                                .withHook(accountAllowanceHook(1L, HOOK_CONTRACT)),
                        cryptoCreate(RECEIVER)
                                .balance(ONE_HUNDRED_HBARS)
                                .withHook(accountAllowanceHook(2L, HOOK_CONTRACT)),
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
                                .via("nftWithTwoHooksTxn")),
                "nftWithTwoHooksTxn",
                4.0051,
                1.0,
                4.0051,
                1.0);
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled", "hooks.hooksEnabled"})
    @DisplayName("HOOKS: Mixed transfer with multiple hooks (HBAR + fungible + NFT)")
    final Stream<DynamicTest> mixedTransferWithMultipleHooks() {
        return compareSimpleToOld(
                () -> Arrays.asList(
                        uploadInitCode(HOOK_CONTRACT),
                        contractCreate(HOOK_CONTRACT).gas(5_000_000),
                        cryptoCreate(PAYER).balance(THOUSAND_HBAR).withHook(accountAllowanceHook(1L, HOOK_CONTRACT)),
                        cryptoCreate(RECEIVER)
                                .balance(ONE_HUNDRED_HBARS)
                                .withHook(accountAllowanceHook(2L, HOOK_CONTRACT)),
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
                                .via("mixedWithHooksTxn")),
                //                | Simple fee (4 hooks × $1)  | ~$4  |
                //                | Gas execution (4 × 5M gas) | ~$32 |
                //                | Total                      | ~$36 |
                "mixedWithHooksTxn",
                36.005099998,
                1.0,
                36.0050999988,
                1.0);
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled", "hooks.hooksEnabled"})
    @DisplayName("HOOKS: Custom fee tokens with hooks")
    final Stream<DynamicTest> complexTransferCustomFeesAndHooks() {
        return compareSimpleToOld(
                () -> Arrays.asList(
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
                                .via("customFeesWithHooksTxn")),
                "customFeesWithHooksTxn",
                // $0.005 + (3 hooks × $1.00) + (2 × $3.00)
                9.0052999992,
                1.0,
                9.0052999992,
                1.0);
    }
}
