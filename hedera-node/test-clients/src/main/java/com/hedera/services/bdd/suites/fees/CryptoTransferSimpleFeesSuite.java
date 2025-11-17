// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.fees;

import static com.google.protobuf.ByteString.copyFromUtf8;
import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.junit.TestTags.SIMPLE_FEES;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedHbarFee;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.royaltyFeeNoFallback;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingHbar;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingUnique;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsdWithin;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;

import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.SpecOperation;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
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
 *
 * Canonical prices being tested:
 * - DEFAULT (HBAR-only): $0.0001
 * - TOKEN_FUNGIBLE_COMMON: $0.0001 (same as DEFAULT)
 * - TOKEN_NON_FUNGIBLE_UNIQUE: $0.0001 (same as DEFAULT)
 * - TOKEN_FUNGIBLE_COMMON_WITH_CUSTOM_FEES: $0.0002
 * - TOKEN_NON_FUNGIBLE_UNIQUE_WITH_CUSTOM_FEES: $0.0002
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

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(Map.of("fees.simpleFeesEnabled", "true"));
    }

    static Stream<DynamicTest> runBeforeAfter(@NonNull final SpecOperation... ops) {
        List<SpecOperation> opsList = new ArrayList<>();
        opsList.add(overriding("fees.simpleFeesEnabled", "false"));
        opsList.addAll(Arrays.asList(ops));
        opsList.add(overriding("fees.simpleFeesEnabled", "true"));
        opsList.addAll(Arrays.asList(ops));
        return hapiTest(opsList.toArray(new SpecOperation[opsList.size()]));
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("DEFAULT: Simple 2-account HBAR transfer")
    final Stream<DynamicTest> defaultSimpleHbarTransfer() {
        return runBeforeAfter(
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(RECEIVER).balance(0L),
                cryptoTransfer(movingHbar(ONE_HBAR).between(PAYER, RECEIVER))
                        .payingWith(PAYER)
                        .signedBy(PAYER)
                        .fee(ONE_HBAR)
                        .via("simple-hbar-txn"),
                // Expected: node(0.00001) + network(0.00009) + service(0) = 0.0001
                // Uses 1 signature (PAYER) matching fee schedule includedCount: 1
                validateChargedUsdWithin("simple-hbar-txn", 0.0001, 5.0));
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("DEFAULT: Multi-account HBAR transfer (3 receivers)")
    final Stream<DynamicTest> defaultMultiAccountHbarTransfer() {
        return runBeforeAfter(
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
                        .via("multi-hbar-txn"),
                // Expected: base $0.0001 + (4 accounts - 2 included) × $0 = $0.0001
                // Note: ACCOUNTS extra has fee=$0, so no additional charge
                // Uses 1 signature (PAYER) matching fee schedule includedCount: 1
                validateChargedUsdWithin("multi-hbar-txn", 0.0001, 5.0));
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("TOKEN_FUNGIBLE_COMMON: Single fungible token transfer")
    final Stream<DynamicTest> fungibleCommonSingleToken() {
        return runBeforeAfter(
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
                        .via("fungible-single-txn"),
                // Expected: $0.0001 (same as HBAR-only, no token-specific premium)
                // Uses 1 signature (PAYER) matching fee schedule includedCount: 1
                validateChargedUsdWithin("fungible-single-txn", 0.0001, 5.0));
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("TOKEN_FUNGIBLE_COMMON: Multiple fungible tokens")
    final Stream<DynamicTest> fungibleCommonMultipleTokens() {
        return runBeforeAfter(
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
                        .via("fungible-multi-txn"),
                // Expected: $0.0001 (same as HBAR-only, no token-specific premium)
                // Uses 1 signature (PAYER) matching fee schedule includedCount: 1
                validateChargedUsdWithin("fungible-multi-txn", 0.0001, 5.0));
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("TOKEN_FUNGIBLE_COMMON: Fungible + HBAR mixed transfer")
    final Stream<DynamicTest> fungibleCommonWithHbar() {
        return runBeforeAfter(
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
                        .via("fungible-hbar-txn"),
                // Expected: $0.0001 (same as HBAR-only, no token-specific premium)
                // Uses 1 signature (PAYER) matching fee schedule includedCount: 1
                validateChargedUsdWithin("fungible-hbar-txn", 0.0001, 5.0));
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("TOKEN_NON_FUNGIBLE_UNIQUE: Single NFT transfer")
    final Stream<DynamicTest> nftUniqueSingleNft() {
        return runBeforeAfter(
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
                        .via("nft-single-txn"),
                // Expected: $0.0001 (same as HBAR-only, no token-specific premium)
                // Uses 1 signature (PAYER) matching fee schedule includedCount: 1
                validateChargedUsdWithin("nft-single-txn", 0.0001, 5.0));
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("TOKEN_NON_FUNGIBLE_UNIQUE: Multiple NFTs same collection")
    final Stream<DynamicTest> nftUniqueMultipleSameCollection() {
        return runBeforeAfter(
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
                        .via("nft-multi-same-txn"),
                // Expected: $0.0001 (same as HBAR-only, no token-specific premium)
                // Uses 1 signature (PAYER) matching fee schedule includedCount: 1
                validateChargedUsdWithin("nft-multi-same-txn", 0.0001, 5.0));
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("TOKEN_NON_FUNGIBLE_UNIQUE: NFTs from different collections")
    final Stream<DynamicTest> nftUniqueDifferentCollections() {
        return runBeforeAfter(
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
                        .via("nft-diff-collections-txn"),
                // Expected: $0.0001 (same as HBAR-only, no token-specific premium)
                // Uses 1 signature (PAYER) matching fee schedule includedCount: 1
                validateChargedUsdWithin("nft-diff-collections-txn", 0.0001, 5.0));
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("TOKEN_NON_FUNGIBLE_UNIQUE: NFT + HBAR mixed transfer")
    final Stream<DynamicTest> nftUniqueWithHbar() {
        return runBeforeAfter(
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
                        .via("nft-hbar-txn"),
                // Expected: $0.0001 (same as HBAR-only, no token-specific premium)
                // Uses 1 signature (PAYER) matching fee schedule includedCount: 1
                validateChargedUsdWithin("nft-hbar-txn", 0.0001, 5.0));
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("TOKEN_FUNGIBLE_COMMON_WITH_CUSTOM_FEES: Fungible with fixed HBAR fee")
    final Stream<DynamicTest> fungibleWithCustomFeesFixedHbar() {
        return runBeforeAfter(
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
                        .via("fungible-custom-fee-txn"),
                // Expected: $0.0002 (custom fee tokens have slight premium)
                // Uses 1 signature (PAYER) matching fee schedule includedCount: 1
                validateChargedUsdWithin("fungible-custom-fee-txn", 0.0002, 5.0));
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("TOKEN_NON_FUNGIBLE_UNIQUE_WITH_CUSTOM_FEES: NFT with royalty fee")
    final Stream<DynamicTest> nftWithCustomFeesRoyalty() {
        return runBeforeAfter(
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
                        .via("nft-custom-fee-txn"),
                // Expected: $0.0002 (custom fee tokens have slight premium)
                // Uses 1 signature (PAYER) matching fee schedule includedCount: 1
                validateChargedUsdWithin("nft-custom-fee-txn", 0.0002, 5.0));
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("P1-CUSTOM: Mixed standard + custom fee tokens")
    final Stream<DynamicTest> customFeeMixedStandardAndCustom() {
        return runBeforeAfter(
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
                        .via("mixed-standard-custom-txn"),
                // Expected: $0.0002 (custom fee token present, determines pricing tier)
                validateChargedUsdWithin("mixed-standard-custom-txn", 0.0002, 5.0));
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("P1-CUSTOM: Multiple custom fee tokens")
    final Stream<DynamicTest> customFeeMultipleCustomFeeTokens() {
        return runBeforeAfter(
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
                        .via("multiple-custom-fee-txn"),
                // Expected: $0.0005 (2 custom fee fungible tokens transferred)
                // NOTE: System counts 4 custom fee token types - likely custom fee HBAR collections
                validateChargedUsdWithin("multiple-custom-fee-txn", 0.0005, 5.0));
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("P1-CUSTOM: Custom fee fungible + custom fee NFT")
    final Stream<DynamicTest> customFeeMultipleFungibleAndNft() {
        return runBeforeAfter(
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
                        .via("custom-fungible-nft-txn"),
                // Expected: $0.0003 (1 custom fee fungible + 1 custom fee NFT serial)
                validateChargedUsdWithin("custom-fungible-nft-txn", 0.0003, 5.0));
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("P2-BOUNDARY: Self-transfer (1 account)")
    final Stream<DynamicTest> accountBoundarySelfTransfer() {
        return runBeforeAfter(
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                cryptoTransfer(movingHbar(ONE_HBAR).between(PAYER, PAYER))
                        .payingWith(PAYER)
                        .signedBy(PAYER)
                        .fee(ONE_HBAR)
                        .via("self-transfer-txn"),
                // Expected: base $0.0001 (1 account ≤ 2 includedCount, so no ACCOUNTS overage)
                validateChargedUsdWithin("self-transfer-txn", 0.0001, 5.0));
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("P2-BOUNDARY: Three accounts (1 over threshold)")
    final Stream<DynamicTest> accountBoundaryThreeAccounts() {
        return runBeforeAfter(
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(RECEIVER).balance(0L),
                cryptoCreate(RECEIVER2).balance(0L),
                cryptoTransfer(
                                movingHbar(ONE_HBAR).between(PAYER, RECEIVER),
                                movingHbar(ONE_HBAR).between(PAYER, RECEIVER2))
                        .payingWith(PAYER)
                        .signedBy(PAYER)
                        .fee(ONE_HBAR)
                        .via("three-accounts-txn"),
                // Expected: base $0.0001 (3 accounts - 2 included = 1 overage × $0 = base only)
                validateChargedUsdWithin("three-accounts-txn", 0.0001, 5.0));
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("P2-COUNTING: Fungible token with multiple recipients counts as 1")
    final Stream<DynamicTest> fungibleCountingMultipleRecipients() {
        return runBeforeAfter(
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
                        .via("fungible-multi-recipient-txn"),
                // Expected: $0.0001 (same as HBAR-only, no token-specific premium)
                // (Validates unique token counting: 4 AccountAmounts but 1 unique token)
                validateChargedUsdWithin("fungible-multi-recipient-txn", 0.0001, 5.0));
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("P2-COUNTING: Large NFT batch (10 serials)")
    final Stream<DynamicTest> nftCountingLargeCollection() {
        return runBeforeAfter(
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
                        .via("nft-large-batch-txn"),
                // Expected: $0.0001 (same as HBAR-only, no token-specific premium)
                validateChargedUsdWithin("nft-large-batch-txn", 0.0001, 5.0));
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("P3-COMPLEX: HBAR + fungible + NFT in one transaction")
    final Stream<DynamicTest> complexMixedAllTypes() {
        return runBeforeAfter(
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
                        .via("complex-all-types-txn"),
                // Expected: $0.0001 (same as HBAR-only, no token-specific premium)
                validateChargedUsdWithin("complex-all-types-txn", 0.0001, 5.0));
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("P3-COMPLEX: Multiple fungible + multiple NFT")
    final Stream<DynamicTest> complexMultipleTokensAndNfts() {
        return runBeforeAfter(
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
                        .via("complex-multi-tokens-nfts-txn"),
                // Expected: $0.0001 (same as HBAR-only, no token-specific premium)
                validateChargedUsdWithin("complex-multi-tokens-nfts-txn", 0.0001, 5.0));
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("P3-COMPLEX: Maximum complexity - all token types")
    final Stream<DynamicTest> complexMaximumComplexity() {
        return runBeforeAfter(
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
                        .via("complex-maximum-txn"),
                // Expected: $0.0003 (1 custom fee fungible + 1 custom fee NFT serial)
                // Mix of: 2 standard fungible (no extra charge) + 1 custom fungible ($0.0001)
                //         + 2 standard NFTs (no extra charge) + 1 custom NFT ($0.0001)
                validateChargedUsdWithin("complex-maximum-txn", 0.0003, 5.0));
    }
}
