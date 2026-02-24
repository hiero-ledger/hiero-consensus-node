// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip1261;

import static com.hedera.services.bdd.junit.TestTags.SIMPLE_FEES;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.accountAllowanceHook;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedHbarFee;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedHbarFeeInheritingRoyaltyCollector;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedHtsFee;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fractionalFeeNetOfTransfers;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.royaltyFeeNoFallback;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.royaltyFeeWithFallback;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingHbar;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingUnique;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.flattened;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedCryptoTransferTokenWithCustomFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.validateChargedUsdWithinWithTxnSize;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.TOKEN_ASSOCIATE_EXTRA_FEE_USD;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_GAS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_SENDER_ACCOUNT_BALANCE_FOR_CUSTOM_FEE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.NO_REMAINING_AUTOMATIC_ASSOCIATIONS;
import static com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;
import static org.hiero.hapi.support.fees.Extra.ACCOUNTS;
import static org.hiero.hapi.support.fees.Extra.GAS;
import static org.hiero.hapi.support.fees.Extra.HOOK_EXECUTION;
import static org.hiero.hapi.support.fees.Extra.PROCESSING_BYTES;
import static org.hiero.hapi.support.fees.Extra.SIGNATURES;
import static org.hiero.hapi.support.fees.Extra.TOKEN_TYPES;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.transactions.token.HapiTokenCreate;
import com.hedera.services.bdd.spec.transactions.token.HapiTokenMint;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;

@Tag(SIMPLE_FEES)
@HapiTestLifecycle
public class CryptoTransferWithCustomFeesSimpleFeesTest {
    private static final String PAYER = "payer";
    private static final String PAYER_INSUFFICIENT_BALANCE = "payerInsufficientBalance";
    private static final String FT_WITH_HBAR_FIXED_FEE = "ftWithHbarFixedFee";
    private static final String FT_WITH_HBAR_FIXED_FEE_SECOND = "ftWithHbarFixedFeeSecond";
    private static final String FT_WITH_HBAR_FIXED_FEE_THIRD = "ftWithHbarFixedFeeThird";
    private static final String FT_WITH_HTS_FIXED_FEE = "ftWithHtsFixedFee";
    private static final String FT_WITH_FRACTIONAL_FEE = "ftFractionalFee";
    private static final String FT_WITH_FRACTIONAL_FEE_SECOND = "ftFractionalFeeSecond";
    private static final String FT_FIXED_AND_FRACTIONAL_FEE = "ftFixedAndFractionalFee";
    private static final String NFT_WITH_HTS_FEE = "nftWithHtsFee";
    private static final String NFT_WITH_ROYALTY_FEE_NO_FALLBACK = "nftWithRoyaltyFeeNoFallback";
    private static final String NFT_WITH_ROYALTY_FEE_WITH_FALLBACK = "nftWithRoyaltyFeeWithFallback";
    private static final String DENOM_TOKEN = "denomToken";
    private static final String FEE_COLLECTOR = "feeCollector";
    private static final String NEW_FEE_COLLECTOR = "newFeeCollector";
    private static final String NEW_FEE_COLLECTOR_SECOND = "newFeeCollectorSecond";
    private static final String HTS_COLLECTOR = "htsCollector";
    private static final String DENOM_COLLECTOR = "denomCollector";
    private static final String ROYALTY_FEE_COLLECTOR = "royaltyFeeCollector";
    private static final String OWNER = "owner";
    private static final String NEW_TREASURY_WITH_UNLIMITED_AUTO_ASSOCIATIONS =
            "newTreasuryWithUnlimitedAutoAssociations";
    private static final String DENOM_TREASURY = "denomTreasury";
    private static final String RECEIVER_ASSOCIATED_FIRST = "receiverAssociatedFirst";
    private static final String RECEIVER_ASSOCIATED_SECOND = "receiverAssociatedSecond";
    private static final String RECEIVER_ASSOCIATED_THIRD = "receiverAssociatedThird";
    private static final String RECEIVER_FREE_AUTO_ASSOCIATIONS = "receiverFreeAutoAssociations";
    private static final String RECEIVER_NO_AUTO_ASSOCIATIONS = "receiverNoAutoAssociations";

    private static final long HBAR_FEE = 1L;
    private static final long HTS_FEE = 1L;

    private static final String adminKey = "adminKey";
    private static final String feeScheduleKey = "feeScheduleKey";
    private static final String supplyKey = "supplyKey";

    private static final String HOOK_CONTRACT = "TruePreHook";
    private static final String PAYER_WITH_HOOK = "payerWithHook";
    private static final String PAYER_WITH_TWO_HOOKS = "payerWithTwoHooks";

    private static final String DUMMY_TOKEN = "dummyToken";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(Map.of(
                "fees.simpleFeesEnabled", "true",
                "hooks.hooksEnabled", "true"));
    }

    @Nested
    @DisplayName("Crypto Transfer with Custom Fees - Fixed Fees - Simple Fees Test")
    class FixedFeesTests {
        @HapiTest
        @DisplayName("Fungible Token with Fixed HBAR Fee - transfer from treasury - base fees full charging")
        final Stream<DynamicTest> cryptoTransferFT_WithFixedHbarCustomFee_baseFeesFullCharging() {
            return hapiTest(flattened(
                    // create keys, tokens and accounts
                    createAccountsAndKeys(),
                    createFungibleTokenWithFixedHbarFee(
                            FT_WITH_HBAR_FIXED_FEE, 100, OWNER, adminKey, HBAR_FEE, FEE_COLLECTOR),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_WITH_HBAR_FIXED_FEE),

                    // transfer tokens
                    cryptoTransfer(moving(10L, FT_WITH_HBAR_FIXED_FEE).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                            .payingWith(OWNER)
                            .signedBy(OWNER)
                            .fee(ONE_HBAR)
                            .via("ftTransferTxn"),
                    validateChargedUsdWithinWithTxnSize(
                            "ftTransferTxn",
                            txnSize -> expectedCryptoTransferTokenWithCustomFullFeeUsd(Map.of(
                                    SIGNATURES, 1L,
                                    ACCOUNTS, 2L,
                                    TOKEN_TYPES, 1L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.001),
                    getAccountBalance(FEE_COLLECTOR).hasTinyBars(0L),
                    getAccountBalance(OWNER).hasTokenBalance(FT_WITH_HBAR_FIXED_FEE, 90L),
                    getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(FT_WITH_HBAR_FIXED_FEE, 10L)));
        }

        @HapiTest
        @DisplayName("Fungible Token with Fixed HBAR Fee - transfer from receiver - with extra accounts charging")
        final Stream<DynamicTest> cryptoTransferFT_WithFixedHbarCustomFeeTransferFromReceiver_extraAccountsCharging() {
            return hapiTest(flattened(
                    // create keys, tokens and accounts
                    createAccountsAndKeys(),
                    createFungibleTokenWithFixedHbarFee(
                            FT_WITH_HBAR_FIXED_FEE, 100, OWNER, adminKey, HBAR_FEE, FEE_COLLECTOR),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_WITH_HBAR_FIXED_FEE),
                    tokenAssociate(RECEIVER_ASSOCIATED_SECOND, FT_WITH_HBAR_FIXED_FEE),
                    tokenAssociate(RECEIVER_ASSOCIATED_THIRD, FT_WITH_HBAR_FIXED_FEE),

                    // transfer tokens
                    cryptoTransfer(moving(20L, FT_WITH_HBAR_FIXED_FEE).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                            .payingWith(OWNER)
                            .signedBy(OWNER)
                            .fee(ONE_HBAR)
                            .via("transferFromTreasuryTxn"),
                    cryptoTransfer(
                                    moving(10L, FT_WITH_HBAR_FIXED_FEE)
                                            .between(RECEIVER_ASSOCIATED_FIRST, RECEIVER_ASSOCIATED_SECOND),
                                    moving(5L, FT_WITH_HBAR_FIXED_FEE)
                                            .between(RECEIVER_ASSOCIATED_FIRST, RECEIVER_ASSOCIATED_THIRD))
                            .payingWith(OWNER)
                            .signedBy(OWNER, RECEIVER_ASSOCIATED_FIRST)
                            .fee(ONE_HBAR)
                            .via("ftTransferTxn"),
                    validateChargedUsdWithinWithTxnSize(
                            "ftTransferTxn",
                            txnSize -> expectedCryptoTransferTokenWithCustomFullFeeUsd(Map.of(
                                    SIGNATURES, 2L,
                                    ACCOUNTS, 3L,
                                    TOKEN_TYPES, 1L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.001),
                    getAccountBalance(FEE_COLLECTOR).hasTinyBars(HBAR_FEE),
                    getAccountBalance(OWNER).hasTokenBalance(FT_WITH_HBAR_FIXED_FEE, 80L),
                    getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(FT_WITH_HBAR_FIXED_FEE, 5L),
                    getAccountBalance(RECEIVER_ASSOCIATED_SECOND).hasTokenBalance(FT_WITH_HBAR_FIXED_FEE, 10L),
                    getAccountBalance(RECEIVER_ASSOCIATED_THIRD).hasTokenBalance(FT_WITH_HBAR_FIXED_FEE, 5L)));
        }

        @HapiTest
        @DisplayName(
                "Fungible Tokens with Fixed HBAR Fee - transfer from receiver - with extra accounts, signatures and tokens charging")
        final Stream<DynamicTest> cryptoTransferFT_WithFixedHbarCustomFee_withExtrasCharging() {
            return hapiTest(flattened(
                    // create keys, tokens and accounts
                    createAccountsAndKeys(),
                    createFungibleTokenWithFixedHbarFee(
                            FT_WITH_HBAR_FIXED_FEE, 100, OWNER, adminKey, HBAR_FEE, FEE_COLLECTOR),
                    createFungibleTokenWithFixedHbarFee(
                            FT_WITH_HBAR_FIXED_FEE_SECOND, 100, OWNER, adminKey, HBAR_FEE, FEE_COLLECTOR),
                    createFungibleTokenWithFixedHbarFee(
                            FT_WITH_HBAR_FIXED_FEE_THIRD, 100, OWNER, adminKey, HBAR_FEE, FEE_COLLECTOR),
                    tokenAssociate(
                            RECEIVER_ASSOCIATED_FIRST,
                            FT_WITH_HBAR_FIXED_FEE,
                            FT_WITH_HBAR_FIXED_FEE_SECOND,
                            FT_WITH_HBAR_FIXED_FEE_THIRD),
                    tokenAssociate(
                            RECEIVER_ASSOCIATED_SECOND,
                            FT_WITH_HBAR_FIXED_FEE,
                            FT_WITH_HBAR_FIXED_FEE_SECOND,
                            FT_WITH_HBAR_FIXED_FEE_THIRD),
                    tokenAssociate(
                            RECEIVER_ASSOCIATED_THIRD,
                            FT_WITH_HBAR_FIXED_FEE,
                            FT_WITH_HBAR_FIXED_FEE_SECOND,
                            FT_WITH_HBAR_FIXED_FEE_THIRD),

                    // transfer tokens
                    cryptoTransfer(
                                    moving(10L, FT_WITH_HBAR_FIXED_FEE).between(OWNER, RECEIVER_ASSOCIATED_FIRST),
                                    moving(10L, FT_WITH_HBAR_FIXED_FEE_SECOND)
                                            .between(OWNER, RECEIVER_ASSOCIATED_SECOND),
                                    moving(10L, FT_WITH_HBAR_FIXED_FEE_THIRD).between(OWNER, RECEIVER_ASSOCIATED_THIRD))
                            .payingWith(OWNER)
                            .signedBy(OWNER)
                            .fee(ONE_HBAR)
                            .via("transferFromTreasuryTxn"),
                    cryptoTransfer(
                                    moving(1L, FT_WITH_HBAR_FIXED_FEE)
                                            .between(RECEIVER_ASSOCIATED_FIRST, RECEIVER_ASSOCIATED_SECOND),
                                    moving(1L, FT_WITH_HBAR_FIXED_FEE)
                                            .between(RECEIVER_ASSOCIATED_FIRST, RECEIVER_ASSOCIATED_THIRD),
                                    moving(1L, FT_WITH_HBAR_FIXED_FEE_SECOND)
                                            .between(RECEIVER_ASSOCIATED_SECOND, RECEIVER_ASSOCIATED_FIRST),
                                    moving(1L, FT_WITH_HBAR_FIXED_FEE_SECOND)
                                            .between(RECEIVER_ASSOCIATED_SECOND, RECEIVER_ASSOCIATED_THIRD),
                                    moving(1L, FT_WITH_HBAR_FIXED_FEE_THIRD)
                                            .between(RECEIVER_ASSOCIATED_THIRD, RECEIVER_ASSOCIATED_FIRST),
                                    moving(1L, FT_WITH_HBAR_FIXED_FEE_THIRD)
                                            .between(RECEIVER_ASSOCIATED_THIRD, RECEIVER_ASSOCIATED_SECOND))
                            .payingWith(OWNER)
                            .signedBy(
                                    OWNER,
                                    RECEIVER_ASSOCIATED_FIRST,
                                    RECEIVER_ASSOCIATED_SECOND,
                                    RECEIVER_ASSOCIATED_THIRD)
                            .fee(ONE_HBAR)
                            .via("ftTransferTxn"),
                    validateChargedUsdWithinWithTxnSize(
                            "ftTransferTxn",
                            txnSize -> expectedCryptoTransferTokenWithCustomFullFeeUsd(Map.of(
                                    SIGNATURES, 4L,
                                    ACCOUNTS, 3L,
                                    TOKEN_TYPES, 3L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.001),
                    getAccountBalance(FEE_COLLECTOR).hasTinyBars(HBAR_FEE * 3),
                    getAccountBalance(OWNER)
                            .hasTokenBalance(FT_WITH_HBAR_FIXED_FEE, 90L)
                            .hasTokenBalance(FT_WITH_HBAR_FIXED_FEE_SECOND, 90L)
                            .hasTokenBalance(FT_WITH_HBAR_FIXED_FEE_THIRD, 90L),
                    getAccountBalance(RECEIVER_ASSOCIATED_FIRST)
                            .hasTokenBalance(FT_WITH_HBAR_FIXED_FEE, 8L)
                            .hasTokenBalance(FT_WITH_HBAR_FIXED_FEE_SECOND, 1L)
                            .hasTokenBalance(FT_WITH_HBAR_FIXED_FEE_THIRD, 1L),
                    getAccountBalance(RECEIVER_ASSOCIATED_SECOND)
                            .hasTokenBalance(FT_WITH_HBAR_FIXED_FEE, 1L)
                            .hasTokenBalance(FT_WITH_HBAR_FIXED_FEE_SECOND, 8L)
                            .hasTokenBalance(FT_WITH_HBAR_FIXED_FEE_THIRD, 1L),
                    getAccountBalance(RECEIVER_ASSOCIATED_THIRD)
                            .hasTokenBalance(FT_WITH_HBAR_FIXED_FEE, 1L)
                            .hasTokenBalance(FT_WITH_HBAR_FIXED_FEE_SECOND, 1L)
                            .hasTokenBalance(FT_WITH_HBAR_FIXED_FEE_THIRD, 8L)));
        }

        @HapiTest
        @DisplayName("NFT with two layers of Fixed Fees - transfer from treasury - base fees full charging")
        final Stream<DynamicTest> cryptoTransferNFT_WithTwoLayersFixedCustomFees_baseFeesFullCharging() {
            return hapiTest(flattened(
                    // create keys, tokens and accounts
                    createAccountsAndKeys(),
                    createFungibleTokenWithAdminKey(DENOM_TOKEN, 100, DENOM_TREASURY, adminKey),
                    tokenAssociate(DENOM_COLLECTOR, DENOM_TOKEN),
                    tokenAssociate(OWNER, DENOM_TOKEN),
                    cryptoTransfer(moving(10, DENOM_TOKEN).between(DENOM_TREASURY, OWNER)),
                    createFungibleTokenWithFixedHtsFee(FT_WITH_HTS_FIXED_FEE, 100, DENOM_TREASURY, adminKey, HTS_FEE),
                    tokenAssociate(HTS_COLLECTOR, FT_WITH_HTS_FIXED_FEE),
                    tokenAssociate(OWNER, FT_WITH_HTS_FIXED_FEE),
                    cryptoTransfer(moving(10, FT_WITH_HTS_FIXED_FEE).between(DENOM_TREASURY, OWNER)),
                    createNFTWithFixedFee(NFT_WITH_HTS_FEE, OWNER, supplyKey, adminKey, HTS_FEE),
                    mintNFT(NFT_WITH_HTS_FEE, 0, 10),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, NFT_WITH_HTS_FEE),

                    // transfer tokens
                    cryptoTransfer(movingUnique(NFT_WITH_HTS_FEE, 1L).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                            .payingWith(OWNER)
                            .signedBy(OWNER)
                            .fee(ONE_HBAR)
                            .via("ftTransferTxn"),
                    validateChargedUsdWithinWithTxnSize(
                            "ftTransferTxn",
                            txnSize -> expectedCryptoTransferTokenWithCustomFullFeeUsd(Map.of(
                                    SIGNATURES, 1L,
                                    ACCOUNTS, 2L,
                                    TOKEN_TYPES, 1L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.001),
                    getAccountBalance(HTS_COLLECTOR).hasTokenBalance(FT_WITH_HTS_FIXED_FEE, 0L),
                    getAccountBalance(DENOM_COLLECTOR).hasTokenBalance(DENOM_TOKEN, 0L),
                    getAccountBalance(DENOM_TREASURY)
                            .hasTokenBalance(DENOM_TOKEN, 90)
                            .hasTokenBalance(FT_WITH_HTS_FIXED_FEE, 90),
                    getAccountBalance(OWNER)
                            .hasTokenBalance(NFT_WITH_HTS_FEE, 9L)
                            .hasTokenBalance(FT_WITH_HTS_FIXED_FEE, 10L)
                            .hasTokenBalance(DENOM_TOKEN, 10L),
                    getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(NFT_WITH_HTS_FEE, 1L)));
        }

        @HapiTest
        @DisplayName(
                "NFT with two layers of Fixed Fees - transfer from one receiver - with extra accounts, signatures and tokens charging")
        final Stream<DynamicTest>
                cryptoTransferNFTFromOneReceiver_WithTwoLayersFixedCustomFees_extraAccountsCharging() {
            return hapiTest(flattened(
                    // create keys, tokens and accounts
                    createAccountsAndKeys(),
                    createFungibleTokenWithAdminKey(DENOM_TOKEN, 100, DENOM_TREASURY, adminKey),
                    tokenAssociate(DENOM_COLLECTOR, DENOM_TOKEN),
                    tokenAssociate(OWNER, DENOM_TOKEN),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, DENOM_TOKEN),
                    cryptoTransfer(
                            moving(10, DENOM_TOKEN).between(DENOM_TREASURY, OWNER),
                            moving(10, DENOM_TOKEN).between(DENOM_TREASURY, RECEIVER_ASSOCIATED_FIRST)),
                    createFungibleTokenWithFixedHtsFee(FT_WITH_HTS_FIXED_FEE, 100, DENOM_TREASURY, adminKey, HTS_FEE),
                    tokenAssociate(HTS_COLLECTOR, FT_WITH_HTS_FIXED_FEE),
                    tokenAssociate(OWNER, FT_WITH_HTS_FIXED_FEE),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_WITH_HTS_FIXED_FEE),
                    cryptoTransfer(
                            moving(10, FT_WITH_HTS_FIXED_FEE).between(DENOM_TREASURY, OWNER),
                            moving(10, FT_WITH_HTS_FIXED_FEE).between(DENOM_TREASURY, RECEIVER_ASSOCIATED_FIRST)),
                    createNFTWithFixedFee(NFT_WITH_HTS_FEE, OWNER, supplyKey, adminKey, HTS_FEE),
                    mintNFT(NFT_WITH_HTS_FEE, 0, 10),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, NFT_WITH_HTS_FEE),
                    tokenAssociate(RECEIVER_ASSOCIATED_SECOND, NFT_WITH_HTS_FEE),
                    tokenAssociate(RECEIVER_ASSOCIATED_THIRD, NFT_WITH_HTS_FEE),

                    // transfer tokens
                    cryptoTransfer(movingUnique(NFT_WITH_HTS_FEE, 1L, 2L, 3L).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                            .payingWith(OWNER)
                            .signedBy(OWNER)
                            .fee(ONE_HBAR)
                            .via("transferFromTreasuryTxn"),
                    cryptoTransfer(
                                    movingUnique(NFT_WITH_HTS_FEE, 1L)
                                            .between(RECEIVER_ASSOCIATED_FIRST, RECEIVER_ASSOCIATED_SECOND),
                                    movingUnique(NFT_WITH_HTS_FEE, 2L)
                                            .between(RECEIVER_ASSOCIATED_FIRST, RECEIVER_ASSOCIATED_THIRD))
                            .payingWith(OWNER)
                            .signedBy(OWNER, RECEIVER_ASSOCIATED_FIRST)
                            .fee(ONE_HBAR)
                            .via("ftTransferTxn"),
                    validateChargedUsdWithinWithTxnSize(
                            "ftTransferTxn",
                            txnSize -> expectedCryptoTransferTokenWithCustomFullFeeUsd(Map.of(
                                    SIGNATURES, 2L,
                                    ACCOUNTS, 3L,
                                    TOKEN_TYPES, 2L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.001),
                    getAccountBalance(HTS_COLLECTOR).hasTokenBalance(FT_WITH_HTS_FIXED_FEE, HTS_FEE * 2),
                    getAccountBalance(DENOM_COLLECTOR).hasTokenBalance(DENOM_TOKEN, HTS_FEE),
                    getAccountBalance(DENOM_TREASURY)
                            .hasTokenBalance(DENOM_TOKEN, 80)
                            .hasTokenBalance(FT_WITH_HTS_FIXED_FEE, 80),
                    getAccountBalance(OWNER)
                            .hasTokenBalance(NFT_WITH_HTS_FEE, 7L)
                            .hasTokenBalance(FT_WITH_HTS_FIXED_FEE, 10L)
                            .hasTokenBalance(DENOM_TOKEN, 10L),
                    getAccountBalance(RECEIVER_ASSOCIATED_FIRST)
                            .hasTokenBalance(NFT_WITH_HTS_FEE, 1L)
                            .hasTokenBalance(FT_WITH_HTS_FIXED_FEE, 10 - HTS_FEE * 2)
                            .hasTokenBalance(DENOM_TOKEN, 10 - HTS_FEE),
                    getAccountBalance(RECEIVER_ASSOCIATED_SECOND).hasTokenBalance(NFT_WITH_HTS_FEE, 1L),
                    getAccountBalance(RECEIVER_ASSOCIATED_THIRD).hasTokenBalance(NFT_WITH_HTS_FEE, 1L)));
        }

        @HapiTest
        @DisplayName(
                "NFT with two layers of Fixed Fees - transfer from multiple receivers - with extra accounts, signatures and tokens charging")
        final Stream<DynamicTest>
                cryptoTransferNFTFromMultipleReceivers_WithTwoLayersFixedCustomFees_extraAccountsCharging() {
            return hapiTest(flattened(
                    // create keys, tokens and accounts
                    createAccountsAndKeys(),
                    createFungibleTokenWithAdminKey(DENOM_TOKEN, 100, DENOM_TREASURY, adminKey),
                    tokenAssociate(DENOM_COLLECTOR, DENOM_TOKEN),
                    tokenAssociate(OWNER, DENOM_TOKEN),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, DENOM_TOKEN),
                    tokenAssociate(RECEIVER_ASSOCIATED_SECOND, DENOM_TOKEN),
                    cryptoTransfer(
                            moving(10, DENOM_TOKEN).between(DENOM_TREASURY, OWNER),
                            moving(10, DENOM_TOKEN).between(DENOM_TREASURY, RECEIVER_ASSOCIATED_FIRST),
                            moving(10, DENOM_TOKEN).between(DENOM_TREASURY, RECEIVER_ASSOCIATED_SECOND)),
                    createFungibleTokenWithFixedHtsFee(FT_WITH_HTS_FIXED_FEE, 100, DENOM_TREASURY, adminKey, HTS_FEE),
                    tokenAssociate(HTS_COLLECTOR, FT_WITH_HTS_FIXED_FEE),
                    tokenAssociate(OWNER, FT_WITH_HTS_FIXED_FEE),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_WITH_HTS_FIXED_FEE),
                    tokenAssociate(RECEIVER_ASSOCIATED_SECOND, FT_WITH_HTS_FIXED_FEE),
                    cryptoTransfer(
                            moving(10, FT_WITH_HTS_FIXED_FEE).between(DENOM_TREASURY, OWNER),
                            moving(10, FT_WITH_HTS_FIXED_FEE).between(DENOM_TREASURY, RECEIVER_ASSOCIATED_FIRST),
                            moving(10, FT_WITH_HTS_FIXED_FEE).between(DENOM_TREASURY, RECEIVER_ASSOCIATED_SECOND)),
                    createNFTWithFixedFee(NFT_WITH_HTS_FEE, OWNER, supplyKey, adminKey, HTS_FEE),
                    mintNFT(NFT_WITH_HTS_FEE, 0, 10),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, NFT_WITH_HTS_FEE),
                    tokenAssociate(RECEIVER_ASSOCIATED_SECOND, NFT_WITH_HTS_FEE),
                    tokenAssociate(RECEIVER_ASSOCIATED_THIRD, NFT_WITH_HTS_FEE),

                    // transfer tokens
                    cryptoTransfer(
                                    movingUnique(NFT_WITH_HTS_FEE, 1L, 2L, 3L)
                                            .between(OWNER, RECEIVER_ASSOCIATED_FIRST),
                                    movingUnique(NFT_WITH_HTS_FEE, 4L).between(OWNER, RECEIVER_ASSOCIATED_SECOND))
                            .payingWith(OWNER)
                            .signedBy(OWNER)
                            .fee(ONE_HBAR)
                            .via("transferFromTreasuryTxn"),
                    cryptoTransfer(
                                    movingUnique(NFT_WITH_HTS_FEE, 1L)
                                            .between(RECEIVER_ASSOCIATED_FIRST, RECEIVER_ASSOCIATED_SECOND),
                                    movingUnique(NFT_WITH_HTS_FEE, 4L)
                                            .between(RECEIVER_ASSOCIATED_SECOND, RECEIVER_ASSOCIATED_THIRD))
                            .payingWith(OWNER)
                            .signedBy(OWNER, RECEIVER_ASSOCIATED_FIRST, RECEIVER_ASSOCIATED_SECOND)
                            .fee(ONE_HBAR)
                            .via("ftTransferTxn"),
                    validateChargedUsdWithinWithTxnSize(
                            "ftTransferTxn",
                            txnSize -> expectedCryptoTransferTokenWithCustomFullFeeUsd(Map.of(
                                    SIGNATURES, 3L,
                                    ACCOUNTS, 3L,
                                    TOKEN_TYPES, 2L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.001),
                    getAccountBalance(HTS_COLLECTOR).hasTokenBalance(FT_WITH_HTS_FIXED_FEE, HTS_FEE * 2),
                    getAccountBalance(DENOM_COLLECTOR).hasTokenBalance(DENOM_TOKEN, HTS_FEE * 2),
                    getAccountBalance(DENOM_TREASURY)
                            .hasTokenBalance(DENOM_TOKEN, 70)
                            .hasTokenBalance(FT_WITH_HTS_FIXED_FEE, 70),
                    getAccountBalance(OWNER)
                            .hasTokenBalance(NFT_WITH_HTS_FEE, 6L)
                            .hasTokenBalance(FT_WITH_HTS_FIXED_FEE, 10L)
                            .hasTokenBalance(DENOM_TOKEN, 10L),
                    getAccountBalance(RECEIVER_ASSOCIATED_FIRST)
                            .hasTokenBalance(NFT_WITH_HTS_FEE, 2L)
                            .hasTokenBalance(FT_WITH_HTS_FIXED_FEE, 10 - HTS_FEE)
                            .hasTokenBalance(DENOM_TOKEN, 10 - HTS_FEE),
                    getAccountBalance(RECEIVER_ASSOCIATED_SECOND)
                            .hasTokenBalance(NFT_WITH_HTS_FEE, 1L)
                            .hasTokenBalance(FT_WITH_HTS_FIXED_FEE, 10 - HTS_FEE)
                            .hasTokenBalance(DENOM_TOKEN, 10 - HTS_FEE),
                    getAccountBalance(RECEIVER_ASSOCIATED_THIRD).hasTokenBalance(NFT_WITH_HTS_FEE, 1L)));
        }

        @HapiTest
        @DisplayName(
                "Fungible Tokens with Fixed HBAR Fee - transfer from treasury and receiver - with extra accounts, signatures and tokens charging")
        final Stream<DynamicTest>
                cryptoTransferFTFromTreasuryAndReceiverInOneTransfer_WithFixedHbarCustomFee_withExtrasCharging() {
            return hapiTest(flattened(
                    // create keys, tokens and accounts
                    createAccountsAndKeys(),
                    createFungibleTokenWithFixedHbarFee(
                            FT_WITH_HBAR_FIXED_FEE, 100, OWNER, adminKey, HBAR_FEE, FEE_COLLECTOR),
                    createFungibleTokenWithFixedHbarFee(
                            FT_WITH_HBAR_FIXED_FEE_SECOND, 100, OWNER, adminKey, HBAR_FEE, FEE_COLLECTOR),
                    createFungibleTokenWithFixedHbarFee(
                            FT_WITH_HBAR_FIXED_FEE_THIRD, 100, OWNER, adminKey, HBAR_FEE, FEE_COLLECTOR),
                    tokenAssociate(
                            RECEIVER_ASSOCIATED_FIRST,
                            FT_WITH_HBAR_FIXED_FEE,
                            FT_WITH_HBAR_FIXED_FEE_SECOND,
                            FT_WITH_HBAR_FIXED_FEE_THIRD),
                    tokenAssociate(
                            RECEIVER_ASSOCIATED_SECOND,
                            FT_WITH_HBAR_FIXED_FEE,
                            FT_WITH_HBAR_FIXED_FEE_SECOND,
                            FT_WITH_HBAR_FIXED_FEE_THIRD),

                    // transfer tokens
                    cryptoTransfer(
                                    moving(10L, FT_WITH_HBAR_FIXED_FEE).between(OWNER, RECEIVER_ASSOCIATED_FIRST),
                                    moving(10L, FT_WITH_HBAR_FIXED_FEE_SECOND)
                                            .between(OWNER, RECEIVER_ASSOCIATED_FIRST),
                                    moving(10L, FT_WITH_HBAR_FIXED_FEE_THIRD)
                                            .between(OWNER, RECEIVER_ASSOCIATED_SECOND),
                                    moving(1L, FT_WITH_HBAR_FIXED_FEE)
                                            .between(RECEIVER_ASSOCIATED_FIRST, RECEIVER_ASSOCIATED_SECOND),
                                    moving(1L, FT_WITH_HBAR_FIXED_FEE_SECOND)
                                            .between(RECEIVER_ASSOCIATED_FIRST, RECEIVER_ASSOCIATED_SECOND),
                                    moving(1L, FT_WITH_HBAR_FIXED_FEE_THIRD)
                                            .between(RECEIVER_ASSOCIATED_SECOND, RECEIVER_ASSOCIATED_FIRST))
                            .payingWith(OWNER)
                            .signedBy(OWNER, RECEIVER_ASSOCIATED_FIRST, RECEIVER_ASSOCIATED_SECOND)
                            .fee(ONE_HBAR)
                            .via("ftTransferTxn"),
                    validateChargedUsdWithinWithTxnSize(
                            "ftTransferTxn",
                            txnSize -> expectedCryptoTransferTokenWithCustomFullFeeUsd(Map.of(
                                    SIGNATURES, 3L,
                                    ACCOUNTS, 3L,
                                    TOKEN_TYPES, 3L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.001),
                    getAccountBalance(FEE_COLLECTOR).hasTinyBars(0L),
                    getAccountBalance(OWNER)
                            .hasTokenBalance(FT_WITH_HBAR_FIXED_FEE, 90L)
                            .hasTokenBalance(FT_WITH_HBAR_FIXED_FEE_SECOND, 90L)
                            .hasTokenBalance(FT_WITH_HBAR_FIXED_FEE_THIRD, 90L),
                    getAccountBalance(RECEIVER_ASSOCIATED_FIRST)
                            .hasTokenBalance(FT_WITH_HBAR_FIXED_FEE, 9L)
                            .hasTokenBalance(FT_WITH_HBAR_FIXED_FEE_SECOND, 9L),
                    getAccountBalance(RECEIVER_ASSOCIATED_SECOND)
                            .hasTokenBalance(FT_WITH_HBAR_FIXED_FEE, 1L)
                            .hasTokenBalance(FT_WITH_HBAR_FIXED_FEE_SECOND, 1L)));
        }
    }

    @Nested
    @DisplayName("Crypto Transfer with Custom Fees - Fractional Fees - Simple Fees Test")
    class FractionalFeesTests {
        @HapiTest
        @DisplayName("Fungible Token with Fractional Fee - transfer from treasury - base fees full charging")
        final Stream<DynamicTest> cryptoTransferFT_WithFractionalCustomFee_baseFeesFullCharging() {
            return hapiTest(flattened(
                    // create keys, tokens and accounts
                    createAccountsAndKeys(),
                    createFungibleTokenWithTenPercentFractionalFee(
                            FT_WITH_FRACTIONAL_FEE, 100, OWNER, adminKey, FEE_COLLECTOR),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_WITH_FRACTIONAL_FEE),

                    // transfer tokens
                    cryptoTransfer(moving(10L, FT_WITH_FRACTIONAL_FEE).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                            .payingWith(OWNER)
                            .signedBy(OWNER)
                            .fee(ONE_HBAR)
                            .via("ftTransferTxn"),
                    validateChargedUsdWithinWithTxnSize(
                            "ftTransferTxn",
                            txnSize -> expectedCryptoTransferTokenWithCustomFullFeeUsd(Map.of(
                                    SIGNATURES, 1L,
                                    ACCOUNTS, 2L,
                                    TOKEN_TYPES, 1L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.001),
                    getAccountBalance(FEE_COLLECTOR).hasTokenBalance(FT_WITH_FRACTIONAL_FEE, 0L),
                    getAccountBalance(OWNER).hasTokenBalance(FT_WITH_FRACTIONAL_FEE, 90L),
                    getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(FT_WITH_FRACTIONAL_FEE, 10L)));
        }

        @HapiTest
        @DisplayName(
                "Fungible Token with Fractional Fee - transfer from receiver - with extra signatures and accounts charging")
        final Stream<DynamicTest> cryptoTransferFT_WithFractionalCustomFee_withExtraAccountsAndSignaturesCharging() {
            return hapiTest(flattened(
                    // create keys, tokens and accounts
                    createAccountsAndKeys(),
                    createFungibleTokenWithTenPercentFractionalFee(
                            FT_WITH_FRACTIONAL_FEE, 100, OWNER, adminKey, FEE_COLLECTOR),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_WITH_FRACTIONAL_FEE),
                    tokenAssociate(RECEIVER_ASSOCIATED_SECOND, FT_WITH_FRACTIONAL_FEE),
                    tokenAssociate(RECEIVER_ASSOCIATED_THIRD, FT_WITH_FRACTIONAL_FEE),

                    // transfer tokens
                    cryptoTransfer(moving(30L, FT_WITH_FRACTIONAL_FEE).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                            .payingWith(OWNER)
                            .signedBy(OWNER)
                            .fee(ONE_HBAR)
                            .via("ftTransferTxn"),
                    cryptoTransfer(
                                    moving(10L, FT_WITH_FRACTIONAL_FEE)
                                            .between(RECEIVER_ASSOCIATED_FIRST, RECEIVER_ASSOCIATED_SECOND),
                                    moving(10L, FT_WITH_FRACTIONAL_FEE)
                                            .between(RECEIVER_ASSOCIATED_FIRST, RECEIVER_ASSOCIATED_THIRD))
                            .payingWith(OWNER)
                            .signedBy(OWNER, RECEIVER_ASSOCIATED_FIRST)
                            .fee(ONE_HBAR)
                            .via("ftTransferTxn"),
                    validateChargedUsdWithinWithTxnSize(
                            "ftTransferTxn",
                            txnSize -> expectedCryptoTransferTokenWithCustomFullFeeUsd(Map.of(
                                    SIGNATURES, 2L,
                                    ACCOUNTS, 3L,
                                    TOKEN_TYPES, 1L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.001),
                    getAccountBalance(FEE_COLLECTOR).hasTokenBalance(FT_WITH_FRACTIONAL_FEE, 2L),
                    getAccountBalance(OWNER).hasTokenBalance(FT_WITH_FRACTIONAL_FEE, 70L),
                    getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(FT_WITH_FRACTIONAL_FEE, 8L),
                    getAccountBalance(RECEIVER_ASSOCIATED_SECOND).hasTokenBalance(FT_WITH_FRACTIONAL_FEE, 10L),
                    getAccountBalance(RECEIVER_ASSOCIATED_THIRD).hasTokenBalance(FT_WITH_FRACTIONAL_FEE, 10L)));
        }

        @HapiTest
        @DisplayName(
                "Fungible Token with Fractional Fee - transfer from receiver - with extra signatures, tokens and accounts charging")
        final Stream<DynamicTest>
                cryptoTransferFT_WithFractionalCustomFee_withExtraSignaturesTokensAndAccountsCharging() {
            return hapiTest(flattened(
                    // create keys, tokens and accounts
                    createAccountsAndKeys(),
                    createFungibleTokenWithTenPercentFractionalFee(
                            FT_WITH_FRACTIONAL_FEE, 100, OWNER, adminKey, FEE_COLLECTOR),
                    createFungibleTokenWithTenPercentFractionalFee(
                            FT_WITH_FRACTIONAL_FEE_SECOND, 100, OWNER, adminKey, FEE_COLLECTOR),
                    createFungibleTokenWithFractionalAndFixedFees(
                            FT_FIXED_AND_FRACTIONAL_FEE, 100, OWNER, adminKey, HBAR_FEE, FEE_COLLECTOR, FEE_COLLECTOR),
                    tokenAssociate(
                            RECEIVER_ASSOCIATED_FIRST,
                            FT_WITH_FRACTIONAL_FEE,
                            FT_WITH_FRACTIONAL_FEE_SECOND,
                            FT_FIXED_AND_FRACTIONAL_FEE),
                    tokenAssociate(
                            RECEIVER_ASSOCIATED_SECOND,
                            FT_WITH_FRACTIONAL_FEE,
                            FT_WITH_FRACTIONAL_FEE_SECOND,
                            FT_FIXED_AND_FRACTIONAL_FEE),
                    tokenAssociate(
                            RECEIVER_ASSOCIATED_THIRD,
                            FT_WITH_FRACTIONAL_FEE,
                            FT_WITH_FRACTIONAL_FEE_SECOND,
                            FT_FIXED_AND_FRACTIONAL_FEE),

                    // transfer tokens
                    cryptoTransfer(
                                    moving(30L, FT_WITH_FRACTIONAL_FEE).between(OWNER, RECEIVER_ASSOCIATED_FIRST),
                                    moving(30L, FT_WITH_FRACTIONAL_FEE_SECOND)
                                            .between(OWNER, RECEIVER_ASSOCIATED_SECOND),
                                    moving(30L, FT_FIXED_AND_FRACTIONAL_FEE).between(OWNER, RECEIVER_ASSOCIATED_THIRD))
                            .payingWith(OWNER)
                            .signedBy(OWNER)
                            .fee(ONE_HBAR)
                            .via("ftTransferTxn"),
                    cryptoTransfer(
                                    moving(10L, FT_WITH_FRACTIONAL_FEE)
                                            .between(RECEIVER_ASSOCIATED_FIRST, RECEIVER_ASSOCIATED_SECOND),
                                    moving(10L, FT_WITH_FRACTIONAL_FEE_SECOND)
                                            .between(RECEIVER_ASSOCIATED_SECOND, RECEIVER_ASSOCIATED_THIRD),
                                    moving(10L, FT_FIXED_AND_FRACTIONAL_FEE)
                                            .between(RECEIVER_ASSOCIATED_THIRD, RECEIVER_ASSOCIATED_FIRST))
                            .payingWith(OWNER)
                            .signedBy(
                                    OWNER,
                                    RECEIVER_ASSOCIATED_FIRST,
                                    RECEIVER_ASSOCIATED_SECOND,
                                    RECEIVER_ASSOCIATED_THIRD)
                            .fee(ONE_HBAR)
                            .via("ftTransferTxn"),
                    validateChargedUsdWithinWithTxnSize(
                            "ftTransferTxn",
                            txnSize -> expectedCryptoTransferTokenWithCustomFullFeeUsd(Map.of(
                                    SIGNATURES, 4L,
                                    ACCOUNTS, 3L,
                                    TOKEN_TYPES, 3L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.001),
                    getAccountBalance(FEE_COLLECTOR)
                            .hasTokenBalance(FT_WITH_FRACTIONAL_FEE, 1L)
                            .hasTokenBalance(FT_WITH_FRACTIONAL_FEE_SECOND, 1L)
                            .hasTinyBars(HBAR_FEE),
                    getAccountBalance(OWNER)
                            .hasTokenBalance(FT_WITH_FRACTIONAL_FEE, 70L)
                            .hasTokenBalance(FT_WITH_FRACTIONAL_FEE_SECOND, 70L)
                            .hasTokenBalance(FT_FIXED_AND_FRACTIONAL_FEE, 70L),
                    getAccountBalance(RECEIVER_ASSOCIATED_FIRST)
                            .hasTokenBalance(FT_WITH_FRACTIONAL_FEE, 19L)
                            .hasTokenBalance(FT_FIXED_AND_FRACTIONAL_FEE, 10L),
                    getAccountBalance(RECEIVER_ASSOCIATED_SECOND)
                            .hasTokenBalance(FT_WITH_FRACTIONAL_FEE, 10L)
                            .hasTokenBalance(FT_WITH_FRACTIONAL_FEE_SECOND, 19L),
                    getAccountBalance(RECEIVER_ASSOCIATED_THIRD)
                            .hasTokenBalance(FT_WITH_FRACTIONAL_FEE_SECOND, 10L)
                            .hasTokenBalance(FT_FIXED_AND_FRACTIONAL_FEE, 19L)));
        }
    }

    @Nested
    @DisplayName("Crypto Transfer with Custom Fees - Royalty Fees - Simple Fees Test")
    class RoyaltyFeesTests {
        @HapiTest
        @DisplayName("Fungible Token with Royalty Fee - no fallback - transfer from treasury - base fees full charging")
        final Stream<DynamicTest> cryptoTransferFT_WithRoyaltyNoFallbackCustomFee_baseFeesFullCharging() {
            return hapiTest(flattened(
                    // create keys, tokens and accounts
                    createAccountsAndKeys(),
                    createNFTWithRoyaltyNoFallback(NFT_WITH_ROYALTY_FEE_NO_FALLBACK, OWNER, supplyKey, adminKey),
                    mintNFT(NFT_WITH_ROYALTY_FEE_NO_FALLBACK, 0, 10),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, NFT_WITH_ROYALTY_FEE_NO_FALLBACK),

                    // transfer tokens
                    cryptoTransfer(movingUnique(NFT_WITH_ROYALTY_FEE_NO_FALLBACK, 1L)
                                    .between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                            .payingWith(OWNER)
                            .signedBy(OWNER)
                            .fee(ONE_HBAR)
                            .via("ftTransferTxn"),
                    validateChargedUsdWithinWithTxnSize(
                            "ftTransferTxn",
                            txnSize -> expectedCryptoTransferTokenWithCustomFullFeeUsd(Map.of(
                                    SIGNATURES, 1L,
                                    ACCOUNTS, 2L,
                                    TOKEN_TYPES, 1L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.001),
                    getAccountBalance(ROYALTY_FEE_COLLECTOR).hasTinyBars(0L),
                    getAccountBalance(OWNER).hasTokenBalance(NFT_WITH_ROYALTY_FEE_NO_FALLBACK, 9L),
                    getAccountBalance(RECEIVER_ASSOCIATED_FIRST)
                            .hasTokenBalance(NFT_WITH_ROYALTY_FEE_NO_FALLBACK, 1L)));
        }

        @HapiTest
        @DisplayName("Fungible Token with Royalty Fee - no fallback - transfer from receiver - with extras charging")
        final Stream<DynamicTest> cryptoTransferFT_WithRoyaltyNoFallbackCustomFee_withExtrasCharging() {
            return hapiTest(flattened(
                    // create keys, tokens and accounts
                    createAccountsAndKeys(),
                    createNFTWithRoyaltyNoFallback(NFT_WITH_ROYALTY_FEE_NO_FALLBACK, OWNER, supplyKey, adminKey),
                    mintNFT(NFT_WITH_ROYALTY_FEE_NO_FALLBACK, 0, 10),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, NFT_WITH_ROYALTY_FEE_NO_FALLBACK),
                    tokenAssociate(RECEIVER_ASSOCIATED_SECOND, NFT_WITH_ROYALTY_FEE_NO_FALLBACK),
                    tokenAssociate(RECEIVER_ASSOCIATED_THIRD, NFT_WITH_ROYALTY_FEE_NO_FALLBACK),

                    // transfer tokens
                    cryptoTransfer(movingUnique(NFT_WITH_ROYALTY_FEE_NO_FALLBACK, 1L, 2L, 3L, 4L)
                                    .between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                            .payingWith(OWNER)
                            .signedBy(OWNER)
                            .fee(ONE_HBAR)
                            .via("ftTransferTxn"),
                    cryptoTransfer(
                                    movingUnique(NFT_WITH_ROYALTY_FEE_NO_FALLBACK, 1L, 2L)
                                            .between(RECEIVER_ASSOCIATED_FIRST, RECEIVER_ASSOCIATED_SECOND),
                                    movingHbar(100L).between(RECEIVER_ASSOCIATED_SECOND, RECEIVER_ASSOCIATED_FIRST),
                                    movingUnique(NFT_WITH_ROYALTY_FEE_NO_FALLBACK, 3L)
                                            .between(RECEIVER_ASSOCIATED_FIRST, RECEIVER_ASSOCIATED_THIRD),
                                    movingHbar(50L).between(RECEIVER_ASSOCIATED_THIRD, RECEIVER_ASSOCIATED_FIRST))
                            .payingWith(OWNER)
                            .signedBy(
                                    OWNER,
                                    RECEIVER_ASSOCIATED_FIRST,
                                    RECEIVER_ASSOCIATED_SECOND,
                                    RECEIVER_ASSOCIATED_THIRD)
                            .fee(ONE_HBAR)
                            .via("ftTransferTxn"),
                    validateChargedUsdWithinWithTxnSize(
                            "ftTransferTxn",
                            txnSize -> expectedCryptoTransferTokenWithCustomFullFeeUsd(Map.of(
                                    SIGNATURES, 4L,
                                    ACCOUNTS, 3L,
                                    TOKEN_TYPES, 3L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.001),
                    getAccountBalance(ROYALTY_FEE_COLLECTOR).hasTinyBars(15L),
                    getAccountBalance(OWNER).hasTokenBalance(NFT_WITH_ROYALTY_FEE_NO_FALLBACK, 6L),
                    getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(NFT_WITH_ROYALTY_FEE_NO_FALLBACK, 1L),
                    getAccountBalance(RECEIVER_ASSOCIATED_SECOND).hasTokenBalance(NFT_WITH_ROYALTY_FEE_NO_FALLBACK, 2L),
                    getAccountBalance(RECEIVER_ASSOCIATED_THIRD)
                            .hasTokenBalance(NFT_WITH_ROYALTY_FEE_NO_FALLBACK, 1L)));
        }

        @HapiTest
        @DisplayName(
                "Fungible Token with Royalty Fee - with fallback - transfer from treasury - base fees full charging")
        final Stream<DynamicTest> cryptoTransferFT_WithRoyaltyWithFallbackCustomFee_baseFeesFullCharging() {
            return hapiTest(flattened(
                    // create keys, tokens and accounts
                    createAccountsAndKeys(),
                    createNFTWithRoyaltyWithFallback(NFT_WITH_ROYALTY_FEE_WITH_FALLBACK, OWNER, supplyKey, adminKey),
                    mintNFT(NFT_WITH_ROYALTY_FEE_WITH_FALLBACK, 0, 10),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, NFT_WITH_ROYALTY_FEE_WITH_FALLBACK),

                    // transfer tokens
                    cryptoTransfer(movingUnique(NFT_WITH_ROYALTY_FEE_WITH_FALLBACK, 1L)
                                    .between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                            .payingWith(OWNER)
                            .signedBy(OWNER)
                            .fee(ONE_HBAR)
                            .via("ftTransferTxn"),
                    validateChargedUsdWithinWithTxnSize(
                            "ftTransferTxn",
                            txnSize -> expectedCryptoTransferTokenWithCustomFullFeeUsd(Map.of(
                                    SIGNATURES, 1L,
                                    ACCOUNTS, 2L,
                                    TOKEN_TYPES, 1L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.001),
                    getAccountBalance(ROYALTY_FEE_COLLECTOR).hasTinyBars(0L),
                    getAccountBalance(OWNER).hasTokenBalance(NFT_WITH_ROYALTY_FEE_WITH_FALLBACK, 9L),
                    getAccountBalance(RECEIVER_ASSOCIATED_FIRST)
                            .hasTokenBalance(NFT_WITH_ROYALTY_FEE_WITH_FALLBACK, 1L)));
        }

        @HapiTest
        @DisplayName("Fungible Token with Royalty Fee - with fallback - transfer from receiver - with extras charging")
        final Stream<DynamicTest> cryptoTransferFT_WithRoyaltyWithFallbackCustomFee_withExtrasCharging() {
            return hapiTest(flattened(
                    // create keys, tokens and accounts
                    createAccountsAndKeys(),
                    createNFTWithRoyaltyWithFallback(NFT_WITH_ROYALTY_FEE_WITH_FALLBACK, OWNER, supplyKey, adminKey),
                    mintNFT(NFT_WITH_ROYALTY_FEE_WITH_FALLBACK, 0, 10),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, NFT_WITH_ROYALTY_FEE_WITH_FALLBACK),
                    tokenAssociate(RECEIVER_ASSOCIATED_SECOND, NFT_WITH_ROYALTY_FEE_WITH_FALLBACK),
                    tokenAssociate(RECEIVER_ASSOCIATED_THIRD, NFT_WITH_ROYALTY_FEE_WITH_FALLBACK),

                    // transfer tokens
                    cryptoTransfer(movingUnique(NFT_WITH_ROYALTY_FEE_WITH_FALLBACK, 1L, 2L, 3L, 4L)
                                    .between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                            .payingWith(OWNER)
                            .signedBy(OWNER)
                            .fee(ONE_HBAR)
                            .via("ftTransferTxn"),
                    cryptoTransfer(
                                    movingUnique(NFT_WITH_ROYALTY_FEE_WITH_FALLBACK, 1L, 2L)
                                            .between(RECEIVER_ASSOCIATED_FIRST, RECEIVER_ASSOCIATED_SECOND),
                                    movingUnique(NFT_WITH_ROYALTY_FEE_WITH_FALLBACK, 3L)
                                            .between(RECEIVER_ASSOCIATED_FIRST, RECEIVER_ASSOCIATED_THIRD))
                            .payingWith(OWNER)
                            .signedBy(
                                    OWNER,
                                    RECEIVER_ASSOCIATED_FIRST,
                                    RECEIVER_ASSOCIATED_SECOND,
                                    RECEIVER_ASSOCIATED_THIRD)
                            .fee(ONE_HBAR)
                            .via("ftTransferTxn"),
                    validateChargedUsdWithinWithTxnSize(
                            "ftTransferTxn",
                            txnSize -> expectedCryptoTransferTokenWithCustomFullFeeUsd(Map.of(
                                    SIGNATURES, 4L,
                                    ACCOUNTS, 3L,
                                    TOKEN_TYPES, 3L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.001),
                    getAccountBalance(ROYALTY_FEE_COLLECTOR).hasTinyBars(2L),
                    getAccountBalance(OWNER).hasTokenBalance(NFT_WITH_ROYALTY_FEE_WITH_FALLBACK, 6L),
                    getAccountBalance(RECEIVER_ASSOCIATED_FIRST)
                            .hasTokenBalance(NFT_WITH_ROYALTY_FEE_WITH_FALLBACK, 1L),
                    getAccountBalance(RECEIVER_ASSOCIATED_SECOND)
                            .hasTokenBalance(NFT_WITH_ROYALTY_FEE_WITH_FALLBACK, 2L),
                    getAccountBalance(RECEIVER_ASSOCIATED_THIRD)
                            .hasTokenBalance(NFT_WITH_ROYALTY_FEE_WITH_FALLBACK, 1L)));
        }
    }

    @Nested
    @DisplayName("Crypto Transfer with Custom Fees - Mixed Fees - Simple Fees Test")
    class MixedFeesTests {
        @HapiTest
        @DisplayName(
                "Mixed Fees - FT with fixed and fractional fee and FT with fractional fee in same transfer - extras charging")
        final Stream<DynamicTest> cryptoTransferFT_WithMixedCustomFees_withExtrasCharging() {
            return hapiTest(flattened(
                    // create keys, tokens and accounts
                    createAccountsAndKeys(),
                    createFungibleTokenWithFractionalAndFixedFees(
                            FT_FIXED_AND_FRACTIONAL_FEE, 100, OWNER, adminKey, HBAR_FEE, FEE_COLLECTOR, FEE_COLLECTOR),
                    createFungibleTokenWithTenPercentFractionalFee(
                            FT_WITH_FRACTIONAL_FEE, 100, OWNER, adminKey, FEE_COLLECTOR),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_FIXED_AND_FRACTIONAL_FEE, FT_WITH_FRACTIONAL_FEE),
                    tokenAssociate(RECEIVER_ASSOCIATED_SECOND, FT_FIXED_AND_FRACTIONAL_FEE, FT_WITH_FRACTIONAL_FEE),
                    tokenAssociate(RECEIVER_ASSOCIATED_THIRD, FT_FIXED_AND_FRACTIONAL_FEE, FT_WITH_FRACTIONAL_FEE),

                    // transfer tokens
                    cryptoTransfer(
                                    moving(30L, FT_FIXED_AND_FRACTIONAL_FEE).between(OWNER, RECEIVER_ASSOCIATED_FIRST),
                                    moving(30L, FT_WITH_FRACTIONAL_FEE).between(OWNER, RECEIVER_ASSOCIATED_SECOND))
                            .payingWith(OWNER)
                            .signedBy(OWNER)
                            .fee(ONE_HBAR)
                            .via("ftTransferTxn"),
                    cryptoTransfer(
                                    moving(10L, FT_FIXED_AND_FRACTIONAL_FEE)
                                            .between(RECEIVER_ASSOCIATED_FIRST, RECEIVER_ASSOCIATED_SECOND),
                                    moving(10L, FT_WITH_FRACTIONAL_FEE)
                                            .between(RECEIVER_ASSOCIATED_SECOND, RECEIVER_ASSOCIATED_THIRD))
                            .payingWith(OWNER)
                            .signedBy(OWNER, RECEIVER_ASSOCIATED_FIRST, RECEIVER_ASSOCIATED_SECOND)
                            .fee(ONE_HBAR)
                            .via("ftTransferTxn"),
                    validateChargedUsdWithinWithTxnSize(
                            "ftTransferTxn",
                            txnSize -> expectedCryptoTransferTokenWithCustomFullFeeUsd(Map.of(
                                    SIGNATURES, 3L,
                                    ACCOUNTS, 3L,
                                    TOKEN_TYPES, 2L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.001),
                    getAccountBalance(FEE_COLLECTOR)
                            .hasTokenBalance(FT_FIXED_AND_FRACTIONAL_FEE, 1L)
                            .hasTokenBalance(FT_WITH_FRACTIONAL_FEE, 1L)
                            .hasTinyBars(HBAR_FEE),
                    getAccountBalance(OWNER)
                            .hasTokenBalance(FT_FIXED_AND_FRACTIONAL_FEE, 70L)
                            .hasTokenBalance(FT_WITH_FRACTIONAL_FEE, 70L),
                    getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(FT_FIXED_AND_FRACTIONAL_FEE, 19L),
                    getAccountBalance(RECEIVER_ASSOCIATED_SECOND)
                            .hasTokenBalance(FT_FIXED_AND_FRACTIONAL_FEE, 10L)
                            .hasTokenBalance(FT_WITH_FRACTIONAL_FEE, 19L),
                    getAccountBalance(RECEIVER_ASSOCIATED_THIRD).hasTokenBalance(FT_WITH_FRACTIONAL_FEE, 10L)));
        }

        @HapiTest
        @DisplayName("Mixed Fees - NFT with fixed HTS fee with denom token with fractional fee - extras charging")
        final Stream<DynamicTest> cryptoTransferNFT_WithMixedCustomFees_withExtrasCharging() {
            return hapiTest(flattened(
                    // create keys, tokens and accounts
                    createAccountsAndKeys(),
                    createFungibleTokenWithTenPercentFractionalFee(DENOM_TOKEN, 100, OWNER, adminKey, DENOM_COLLECTOR),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, DENOM_TOKEN),
                    cryptoTransfer(moving(20, DENOM_TOKEN).between(OWNER, RECEIVER_ASSOCIATED_FIRST)),
                    tokenCreate(NFT_WITH_HTS_FEE)
                            .treasury(OWNER)
                            .initialSupply(0)
                            .adminKey(adminKey)
                            .supplyKey(supplyKey)
                            .tokenType(NON_FUNGIBLE_UNIQUE)
                            .withCustom(fixedHtsFee(HTS_FEE, DENOM_TOKEN, DENOM_COLLECTOR))
                            .fee(ONE_HUNDRED_HBARS)
                            .payingWith(OWNER),
                    mintNFT(NFT_WITH_HTS_FEE, 0, 10),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, NFT_WITH_HTS_FEE),
                    tokenAssociate(RECEIVER_ASSOCIATED_SECOND, NFT_WITH_HTS_FEE),
                    tokenAssociate(RECEIVER_ASSOCIATED_THIRD, NFT_WITH_HTS_FEE),
                    cryptoTransfer(movingUnique(NFT_WITH_HTS_FEE, 1L, 2L, 3L).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                            .payingWith(OWNER)
                            .signedBy(OWNER)
                            .fee(ONE_HBAR)
                            .via("transferFromTreasuryTxn"),
                    cryptoTransfer(
                                    movingUnique(NFT_WITH_HTS_FEE, 1L)
                                            .between(RECEIVER_ASSOCIATED_FIRST, RECEIVER_ASSOCIATED_SECOND),
                                    movingUnique(NFT_WITH_HTS_FEE, 2L)
                                            .between(RECEIVER_ASSOCIATED_FIRST, RECEIVER_ASSOCIATED_THIRD))
                            .payingWith(OWNER)
                            .signedBy(OWNER, RECEIVER_ASSOCIATED_FIRST)
                            .fee(ONE_HBAR)
                            .via("ftTransferTxn"),
                    validateChargedUsdWithinWithTxnSize(
                            "ftTransferTxn",
                            txnSize -> expectedCryptoTransferTokenWithCustomFullFeeUsd(Map.of(
                                    SIGNATURES, 2L,
                                    ACCOUNTS, 3L,
                                    TOKEN_TYPES, 2L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.001),
                    getAccountBalance(DENOM_COLLECTOR).hasTokenBalance(DENOM_TOKEN, 2L),
                    getAccountBalance(OWNER)
                            .hasTokenBalance(NFT_WITH_HTS_FEE, 7L)
                            .hasTokenBalance(DENOM_TOKEN, 80L),
                    getAccountBalance(RECEIVER_ASSOCIATED_FIRST)
                            .hasTokenBalance(NFT_WITH_HTS_FEE, 1L)
                            .hasTokenBalance(DENOM_TOKEN, 18L),
                    getAccountBalance(RECEIVER_ASSOCIATED_SECOND).hasTokenBalance(NFT_WITH_HTS_FEE, 1L),
                    getAccountBalance(RECEIVER_ASSOCIATED_THIRD).hasTokenBalance(NFT_WITH_HTS_FEE, 1L)));
        }
    }

    @Nested
    @DisplayName("Crypto Transfer with Custom Fees - Auto Associations and Hooks - Simple Fees Test")
    class AutoAssociationsAndHooksTests {
        @HapiTest
        @DisplayName(
                "Fungible Token with Fixed HBAR Fee - transfer to receiver with free auto-associations  - with extra accounts charging")
        final Stream<DynamicTest>
                cryptoTransferFT_WithFixedHbarCustomFeeTransferToReceiverWithFreeAutoAssociations_extraAccountsCharging() {
            return hapiTest(flattened(
                    // create keys, tokens and accounts
                    createAccountsAndKeys(),
                    createFungibleTokenWithFixedHbarFee(
                            FT_WITH_HBAR_FIXED_FEE, 100, OWNER, adminKey, HBAR_FEE, FEE_COLLECTOR),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_WITH_HBAR_FIXED_FEE),
                    tokenAssociate(RECEIVER_ASSOCIATED_SECOND, FT_WITH_HBAR_FIXED_FEE),

                    // transfer tokens
                    cryptoTransfer(moving(20L, FT_WITH_HBAR_FIXED_FEE).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                            .payingWith(OWNER)
                            .signedBy(OWNER)
                            .fee(ONE_HBAR)
                            .via("transferFromTreasuryTxn"),
                    cryptoTransfer(
                                    moving(10L, FT_WITH_HBAR_FIXED_FEE)
                                            .between(RECEIVER_ASSOCIATED_FIRST, RECEIVER_ASSOCIATED_SECOND),
                                    moving(5L, FT_WITH_HBAR_FIXED_FEE)
                                            .between(RECEIVER_ASSOCIATED_FIRST, RECEIVER_FREE_AUTO_ASSOCIATIONS))
                            .payingWith(OWNER)
                            .signedBy(OWNER, RECEIVER_ASSOCIATED_FIRST)
                            .fee(ONE_HBAR)
                            .via("ftTransferTxn"),
                    validateChargedUsdWithinWithTxnSize(
                            "ftTransferTxn",
                            txnSize -> expectedCryptoTransferTokenWithCustomFullFeeUsd(Map.of(
                                            SIGNATURES, 2L,
                                            ACCOUNTS, 3L,
                                            TOKEN_TYPES, 1L,
                                            PROCESSING_BYTES, (long) txnSize))
                                    + TOKEN_ASSOCIATE_EXTRA_FEE_USD,
                            0.001),
                    getAccountBalance(FEE_COLLECTOR).hasTinyBars(HBAR_FEE),
                    getAccountBalance(OWNER).hasTokenBalance(FT_WITH_HBAR_FIXED_FEE, 80L),
                    getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(FT_WITH_HBAR_FIXED_FEE, 5L),
                    getAccountBalance(RECEIVER_ASSOCIATED_SECOND).hasTokenBalance(FT_WITH_HBAR_FIXED_FEE, 10L),
                    getAccountBalance(RECEIVER_FREE_AUTO_ASSOCIATIONS).hasTokenBalance(FT_WITH_HBAR_FIXED_FEE, 5L)));
        }

        @HapiTest
        @DisplayName(
                "Crypto Transfer FT and NFT with custom fees and with hook execution - extra hooks, tokens and accounts full charging")
        final Stream<DynamicTest>
                cryptoTransferHBARAndFtAndNFTWithTwoHooksExtraHooksAndTokensAndAccountsFullCharging() {
            return hapiTest(flattened(
                    // create keys, tokens and accounts
                    createAccountsAndKeys(),
                    createFungibleTokenWithAdminKey(DENOM_TOKEN, 100, DENOM_TREASURY, adminKey),
                    tokenAssociate(DENOM_COLLECTOR, DENOM_TOKEN),
                    tokenAssociate(PAYER_WITH_TWO_HOOKS, DENOM_TOKEN),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, DENOM_TOKEN),
                    tokenAssociate(RECEIVER_ASSOCIATED_THIRD, DENOM_TOKEN),
                    cryptoTransfer(
                            moving(10, DENOM_TOKEN).between(DENOM_TREASURY, PAYER_WITH_TWO_HOOKS),
                            moving(10, DENOM_TOKEN).between(DENOM_TREASURY, RECEIVER_ASSOCIATED_FIRST),
                            moving(10, DENOM_TOKEN).between(DENOM_TREASURY, RECEIVER_ASSOCIATED_THIRD)),
                    createFungibleTokenWithFixedHtsFee(
                            FT_WITH_HTS_FIXED_FEE, 100L, PAYER_WITH_TWO_HOOKS, adminKey, HTS_FEE),
                    tokenAssociate(HTS_COLLECTOR, FT_WITH_HTS_FIXED_FEE),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_WITH_HTS_FIXED_FEE),
                    tokenAssociate(RECEIVER_ASSOCIATED_SECOND, FT_WITH_HTS_FIXED_FEE),
                    tokenAssociate(RECEIVER_ASSOCIATED_THIRD, FT_WITH_HTS_FIXED_FEE),
                    cryptoTransfer(
                            moving(10L, FT_WITH_HTS_FIXED_FEE).between(PAYER_WITH_TWO_HOOKS, RECEIVER_ASSOCIATED_FIRST),
                            moving(10L, FT_WITH_HTS_FIXED_FEE)
                                    .between(PAYER_WITH_TWO_HOOKS, RECEIVER_ASSOCIATED_THIRD)),
                    createNFTWithFixedFee(NFT_WITH_HTS_FEE, PAYER_WITH_TWO_HOOKS, supplyKey, adminKey, HTS_FEE),
                    mintNFT(NFT_WITH_HTS_FEE, 0, 5),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, NFT_WITH_HTS_FEE),
                    tokenAssociate(RECEIVER_ASSOCIATED_SECOND, NFT_WITH_HTS_FEE),
                    tokenAssociate(RECEIVER_ASSOCIATED_THIRD, NFT_WITH_HTS_FEE),

                    // transfer tokens
                    cryptoTransfer(
                            moving(20L, FT_WITH_HTS_FIXED_FEE).between(PAYER_WITH_TWO_HOOKS, RECEIVER_ASSOCIATED_FIRST),
                            movingUnique(NFT_WITH_HTS_FEE, 1L, 2L, 3L)
                                    .between(PAYER_WITH_TWO_HOOKS, RECEIVER_ASSOCIATED_THIRD)),
                    cryptoTransfer(
                                    moving(10L, FT_WITH_HTS_FIXED_FEE)
                                            .between(RECEIVER_ASSOCIATED_FIRST, RECEIVER_ASSOCIATED_SECOND),
                                    movingUnique(NFT_WITH_HTS_FEE, 1L)
                                            .between(RECEIVER_ASSOCIATED_THIRD, RECEIVER_ASSOCIATED_FIRST),
                                    movingUnique(NFT_WITH_HTS_FEE, 2L)
                                            .between(RECEIVER_ASSOCIATED_THIRD, RECEIVER_ASSOCIATED_SECOND),
                                    movingUnique(NFT_WITH_HTS_FEE, 4L)
                                            .between(PAYER_WITH_TWO_HOOKS, RECEIVER_FREE_AUTO_ASSOCIATIONS),
                                    movingUnique(NFT_WITH_HTS_FEE, 5L)
                                            .between(PAYER_WITH_TWO_HOOKS, RECEIVER_ASSOCIATED_FIRST))
                            .withNftSenderPreHookFor(PAYER_WITH_TWO_HOOKS, 1L, 5_000_000L, "")
                            .withNftSenderPreHookFor(PAYER_WITH_TWO_HOOKS, 2L, 5_000_000L, "")
                            .payingWith(PAYER_WITH_TWO_HOOKS)
                            .signedBy(PAYER_WITH_TWO_HOOKS, RECEIVER_ASSOCIATED_FIRST, RECEIVER_ASSOCIATED_THIRD)
                            .fee(ONE_HUNDRED_HBARS)
                            .via("tokenTransferTxn"),
                    validateChargedUsdWithinWithTxnSize(
                            "tokenTransferTxn",
                            txnSize -> expectedCryptoTransferTokenWithCustomFullFeeUsd(Map.of(
                                            SIGNATURES, 3L,
                                            HOOK_EXECUTION, 2L,
                                            ACCOUNTS, 5L,
                                            TOKEN_TYPES, 5L,
                                            GAS, 10_000_000L,
                                            PROCESSING_BYTES, (long) txnSize))
                                    + TOKEN_ASSOCIATE_EXTRA_FEE_USD,
                            0.001),
                    getAccountBalance(HTS_COLLECTOR).hasTokenBalance(FT_WITH_HTS_FIXED_FEE, 2L),
                    getAccountBalance(DENOM_COLLECTOR).hasTokenBalance(DENOM_TOKEN, 2L),
                    getAccountBalance(PAYER_WITH_TWO_HOOKS)
                            .hasTokenBalance(FT_WITH_HTS_FIXED_FEE, 60L)
                            .hasTokenBalance(NFT_WITH_HTS_FEE, 0L)
                            .hasTokenBalance(DENOM_TOKEN, 10L),
                    getAccountBalance(RECEIVER_ASSOCIATED_FIRST)
                            .hasTokenBalance(FT_WITH_HTS_FIXED_FEE, 20L)
                            .hasTokenBalance(NFT_WITH_HTS_FEE, 2L)
                            .hasTokenBalance(DENOM_TOKEN, 9L),
                    getAccountBalance(RECEIVER_ASSOCIATED_SECOND)
                            .hasTokenBalance(FT_WITH_HTS_FIXED_FEE, 10L)
                            .hasTokenBalance(NFT_WITH_HTS_FEE, 1L),
                    getAccountBalance(RECEIVER_ASSOCIATED_THIRD)
                            .hasTokenBalance(NFT_WITH_HTS_FEE, 1L)
                            .hasTokenBalance(FT_WITH_HTS_FIXED_FEE, 8L)
                            .hasTokenBalance(DENOM_TOKEN, 9L),
                    getAccountBalance(RECEIVER_FREE_AUTO_ASSOCIATIONS).hasTokenBalance(NFT_WITH_HTS_FEE, 1L)));
        }
    }

    @Nested
    @DisplayName("Crypto Transfer with Custom Fees - Simple Fees Negative Tests")
    class SimpleFeesNegativeTests {
        @HapiTest
        @DisplayName(
                "NFT with two layers of Fixed Fees - transfer from one receiver - insufficient custom fee payer balance")
        final Stream<DynamicTest>
                cryptoTransferNFTFromOneReceiver_WithTwoLayersFixedCustomFees_insufficientCustomFeePayerBalance() {
            return hapiTest(flattened(
                    // create keys, tokens and accounts
                    createAccountsAndKeys(),
                    createFungibleTokenWithAdminKey(DENOM_TOKEN, 100, DENOM_TREASURY, adminKey),
                    tokenAssociate(DENOM_COLLECTOR, DENOM_TOKEN),
                    tokenAssociate(OWNER, DENOM_TOKEN),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, DENOM_TOKEN),
                    cryptoTransfer(
                            moving(10, DENOM_TOKEN).between(DENOM_TREASURY, OWNER),
                            moving(10, DENOM_TOKEN).between(DENOM_TREASURY, RECEIVER_ASSOCIATED_FIRST)),
                    createFungibleTokenWithFixedHtsFee(FT_WITH_HTS_FIXED_FEE, 100, DENOM_TREASURY, adminKey, HTS_FEE),
                    tokenAssociate(HTS_COLLECTOR, FT_WITH_HTS_FIXED_FEE),
                    tokenAssociate(OWNER, FT_WITH_HTS_FIXED_FEE),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_WITH_HTS_FIXED_FEE),
                    cryptoTransfer(
                            moving(10, FT_WITH_HTS_FIXED_FEE).between(DENOM_TREASURY, OWNER),
                            moving(1, FT_WITH_HTS_FIXED_FEE).between(DENOM_TREASURY, RECEIVER_ASSOCIATED_FIRST)),
                    createNFTWithFixedFee(NFT_WITH_HTS_FEE, OWNER, supplyKey, adminKey, HTS_FEE),
                    mintNFT(NFT_WITH_HTS_FEE, 0, 10),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, NFT_WITH_HTS_FEE),
                    tokenAssociate(RECEIVER_ASSOCIATED_SECOND, NFT_WITH_HTS_FEE),
                    tokenAssociate(RECEIVER_ASSOCIATED_THIRD, NFT_WITH_HTS_FEE),

                    // transfer tokens
                    cryptoTransfer(movingUnique(NFT_WITH_HTS_FEE, 1L, 2L, 3L).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                            .payingWith(OWNER)
                            .signedBy(OWNER)
                            .fee(ONE_HBAR)
                            .via("transferFromTreasuryTxn"),
                    cryptoTransfer(
                                    movingUnique(NFT_WITH_HTS_FEE, 1L)
                                            .between(RECEIVER_ASSOCIATED_FIRST, RECEIVER_ASSOCIATED_SECOND),
                                    movingUnique(NFT_WITH_HTS_FEE, 2L)
                                            .between(RECEIVER_ASSOCIATED_FIRST, RECEIVER_ASSOCIATED_THIRD))
                            .payingWith(OWNER)
                            .signedBy(OWNER, RECEIVER_ASSOCIATED_FIRST)
                            .fee(ONE_HBAR)
                            .via("ftTransferTxn")
                            .hasKnownStatus(INSUFFICIENT_SENDER_ACCOUNT_BALANCE_FOR_CUSTOM_FEE),
                    validateChargedUsdWithinWithTxnSize(
                            "ftTransferTxn",
                            txnSize -> expectedCryptoTransferTokenWithCustomFullFeeUsd(Map.of(
                                    SIGNATURES, 2L,
                                    ACCOUNTS, 3L,
                                    TOKEN_TYPES, 2L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.001),
                    getAccountBalance(HTS_COLLECTOR).hasTokenBalance(FT_WITH_HTS_FIXED_FEE, 0L),
                    getAccountBalance(DENOM_COLLECTOR).hasTokenBalance(DENOM_TOKEN, 0L),
                    getAccountBalance(DENOM_TREASURY)
                            .hasTokenBalance(DENOM_TOKEN, 80L)
                            .hasTokenBalance(FT_WITH_HTS_FIXED_FEE, 89L),
                    getAccountBalance(OWNER)
                            .hasTokenBalance(NFT_WITH_HTS_FEE, 7L)
                            .hasTokenBalance(FT_WITH_HTS_FIXED_FEE, 10L)
                            .hasTokenBalance(DENOM_TOKEN, 10L),
                    getAccountBalance(RECEIVER_ASSOCIATED_FIRST)
                            .hasTokenBalance(NFT_WITH_HTS_FEE, 3L)
                            .hasTokenBalance(FT_WITH_HTS_FIXED_FEE, 1L)
                            .hasTokenBalance(DENOM_TOKEN, 10L),
                    getAccountBalance(RECEIVER_ASSOCIATED_SECOND).hasTokenBalance(NFT_WITH_HTS_FEE, 0L),
                    getAccountBalance(RECEIVER_ASSOCIATED_THIRD).hasTokenBalance(NFT_WITH_HTS_FEE, 0L)));
        }

        @HapiTest
        @DisplayName(
                "NFT with two layers of Fixed Fees - transfer from one receiver - insufficient second layer custom fee payer balance")
        final Stream<DynamicTest>
                cryptoTransferNFTFromOneReceiver_WithTwoLayersFixedCustomFees_insufficientSecondLayerCustomFeePayerBalance() {
            return hapiTest(flattened(
                    // create keys, tokens and accounts
                    createAccountsAndKeys(),
                    createFungibleTokenWithAdminKey(DENOM_TOKEN, 100, DENOM_TREASURY, adminKey),
                    tokenAssociate(DENOM_COLLECTOR, DENOM_TOKEN),
                    tokenAssociate(OWNER, DENOM_TOKEN),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, DENOM_TOKEN),
                    cryptoTransfer(moving(10, DENOM_TOKEN).between(DENOM_TREASURY, OWNER)),
                    createFungibleTokenWithFixedHtsFee(FT_WITH_HTS_FIXED_FEE, 100, DENOM_TREASURY, adminKey, HTS_FEE),
                    tokenAssociate(HTS_COLLECTOR, FT_WITH_HTS_FIXED_FEE),
                    tokenAssociate(OWNER, FT_WITH_HTS_FIXED_FEE),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_WITH_HTS_FIXED_FEE),
                    cryptoTransfer(
                            moving(10, FT_WITH_HTS_FIXED_FEE).between(DENOM_TREASURY, OWNER),
                            moving(10, FT_WITH_HTS_FIXED_FEE).between(DENOM_TREASURY, RECEIVER_ASSOCIATED_FIRST)),
                    createNFTWithFixedFee(NFT_WITH_HTS_FEE, OWNER, supplyKey, adminKey, HTS_FEE),
                    mintNFT(NFT_WITH_HTS_FEE, 0, 10),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, NFT_WITH_HTS_FEE),
                    tokenAssociate(RECEIVER_ASSOCIATED_SECOND, NFT_WITH_HTS_FEE),
                    tokenAssociate(RECEIVER_ASSOCIATED_THIRD, NFT_WITH_HTS_FEE),

                    // transfer tokens
                    cryptoTransfer(movingUnique(NFT_WITH_HTS_FEE, 1L, 2L, 3L).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                            .payingWith(OWNER)
                            .signedBy(OWNER)
                            .fee(ONE_HBAR)
                            .via("transferFromTreasuryTxn"),
                    cryptoTransfer(
                                    movingUnique(NFT_WITH_HTS_FEE, 1L)
                                            .between(RECEIVER_ASSOCIATED_FIRST, RECEIVER_ASSOCIATED_SECOND),
                                    movingUnique(NFT_WITH_HTS_FEE, 2L)
                                            .between(RECEIVER_ASSOCIATED_FIRST, RECEIVER_ASSOCIATED_THIRD))
                            .payingWith(OWNER)
                            .signedBy(OWNER, RECEIVER_ASSOCIATED_FIRST)
                            .fee(ONE_HBAR)
                            .via("ftTransferTxn")
                            .hasKnownStatus(INSUFFICIENT_SENDER_ACCOUNT_BALANCE_FOR_CUSTOM_FEE),
                    validateChargedUsdWithinWithTxnSize(
                            "ftTransferTxn",
                            txnSize -> expectedCryptoTransferTokenWithCustomFullFeeUsd(Map.of(
                                    SIGNATURES, 2L,
                                    ACCOUNTS, 3L,
                                    TOKEN_TYPES, 2L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.001),
                    getAccountBalance(HTS_COLLECTOR).hasTokenBalance(FT_WITH_HTS_FIXED_FEE, 0L),
                    getAccountBalance(DENOM_COLLECTOR).hasTokenBalance(DENOM_TOKEN, 0L),
                    getAccountBalance(DENOM_TREASURY)
                            .hasTokenBalance(DENOM_TOKEN, 90L)
                            .hasTokenBalance(FT_WITH_HTS_FIXED_FEE, 80L),
                    getAccountBalance(OWNER)
                            .hasTokenBalance(NFT_WITH_HTS_FEE, 7L)
                            .hasTokenBalance(FT_WITH_HTS_FIXED_FEE, 10L)
                            .hasTokenBalance(DENOM_TOKEN, 10L),
                    getAccountBalance(RECEIVER_ASSOCIATED_FIRST)
                            .hasTokenBalance(NFT_WITH_HTS_FEE, 3L)
                            .hasTokenBalance(FT_WITH_HTS_FIXED_FEE, 10L)
                            .hasTokenBalance(DENOM_TOKEN, 0L),
                    getAccountBalance(RECEIVER_ASSOCIATED_SECOND).hasTokenBalance(NFT_WITH_HTS_FEE, 0L),
                    getAccountBalance(RECEIVER_ASSOCIATED_THIRD).hasTokenBalance(NFT_WITH_HTS_FEE, 0L)));
        }

        @HapiTest
        @DisplayName(
                "Fungible Token with Fixed HBAR Fee - transfer to receiver without free auto-associations  - with extra accounts charging")
        final Stream<DynamicTest>
                cryptoTransferFT_WithFixedHbarCustomFeeTransferToReceiverWithoutFreeAutoAssociations_extraAccountsCharging() {
            return hapiTest(flattened(
                    // create keys, tokens and accounts
                    createAccountsAndKeys(),
                    createFungibleTokenWithFixedHbarFee(
                            FT_WITH_HBAR_FIXED_FEE, 100, OWNER, adminKey, HBAR_FEE, FEE_COLLECTOR),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_WITH_HBAR_FIXED_FEE),
                    tokenAssociate(RECEIVER_ASSOCIATED_SECOND, FT_WITH_HBAR_FIXED_FEE),
                    createFungibleTokenWithAdminKey(DUMMY_TOKEN, 100, OWNER, adminKey),
                    cryptoTransfer(moving(1L, DUMMY_TOKEN).between(OWNER, RECEIVER_FREE_AUTO_ASSOCIATIONS))
                            .payingWith(OWNER)
                            .signedBy(OWNER)
                            .fee(ONE_HBAR)
                            .via("consumeAutoAssociationsSlot"),

                    // transfer tokens
                    cryptoTransfer(moving(20L, FT_WITH_HBAR_FIXED_FEE).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                            .payingWith(OWNER)
                            .signedBy(OWNER)
                            .fee(ONE_HBAR)
                            .via("transferFromTreasuryTxn"),
                    cryptoTransfer(
                                    moving(10L, FT_WITH_HBAR_FIXED_FEE)
                                            .between(RECEIVER_ASSOCIATED_FIRST, RECEIVER_ASSOCIATED_SECOND),
                                    moving(5L, FT_WITH_HBAR_FIXED_FEE)
                                            .between(RECEIVER_ASSOCIATED_FIRST, RECEIVER_FREE_AUTO_ASSOCIATIONS))
                            .payingWith(OWNER)
                            .signedBy(OWNER, RECEIVER_ASSOCIATED_FIRST)
                            .fee(ONE_HBAR)
                            .via("ftTransferTxn")
                            .hasKnownStatus(NO_REMAINING_AUTOMATIC_ASSOCIATIONS),
                    validateChargedUsdWithinWithTxnSize(
                            "ftTransferTxn",
                            txnSize -> expectedCryptoTransferTokenWithCustomFullFeeUsd(Map.of(
                                    SIGNATURES, 2L,
                                    ACCOUNTS, 3L,
                                    TOKEN_TYPES, 1L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.001),
                    getAccountBalance(FEE_COLLECTOR).hasTinyBars(0L),
                    getAccountBalance(OWNER).hasTokenBalance(FT_WITH_HBAR_FIXED_FEE, 80L),
                    getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(FT_WITH_HBAR_FIXED_FEE, 20L),
                    getAccountBalance(RECEIVER_ASSOCIATED_SECOND).hasTokenBalance(FT_WITH_HBAR_FIXED_FEE, 0L),
                    getAccountBalance(RECEIVER_FREE_AUTO_ASSOCIATIONS).hasTokenBalance(FT_WITH_HBAR_FIXED_FEE, 0L)));
        }

        @HapiTest
        @DisplayName("Crypto Transfer FT and NFT with custom fees and with failing hook - fails on handle")
        final Stream<DynamicTest> cryptoTransferHBARAndFtAndNFTWithCustomFeesAndWithFailingHookFailsOnHandle() {
            return hapiTest(flattened(
                    // create keys, tokens and accounts
                    createAccountsAndKeys(),
                    createFungibleTokenWithAdminKey(DENOM_TOKEN, 100, DENOM_TREASURY, adminKey),
                    tokenAssociate(DENOM_COLLECTOR, DENOM_TOKEN),
                    tokenAssociate(PAYER_WITH_TWO_HOOKS, DENOM_TOKEN),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, DENOM_TOKEN),
                    tokenAssociate(RECEIVER_ASSOCIATED_THIRD, DENOM_TOKEN),
                    cryptoTransfer(
                            moving(10, DENOM_TOKEN).between(DENOM_TREASURY, PAYER_WITH_TWO_HOOKS),
                            moving(10, DENOM_TOKEN).between(DENOM_TREASURY, RECEIVER_ASSOCIATED_FIRST),
                            moving(10, DENOM_TOKEN).between(DENOM_TREASURY, RECEIVER_ASSOCIATED_THIRD)),
                    createFungibleTokenWithFixedHtsFee(
                            FT_WITH_HTS_FIXED_FEE, 100L, PAYER_WITH_TWO_HOOKS, adminKey, HTS_FEE),
                    tokenAssociate(HTS_COLLECTOR, FT_WITH_HTS_FIXED_FEE),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_WITH_HTS_FIXED_FEE),
                    tokenAssociate(RECEIVER_ASSOCIATED_SECOND, FT_WITH_HTS_FIXED_FEE),
                    tokenAssociate(RECEIVER_ASSOCIATED_THIRD, FT_WITH_HTS_FIXED_FEE),
                    cryptoTransfer(
                            moving(10L, FT_WITH_HTS_FIXED_FEE).between(PAYER_WITH_TWO_HOOKS, RECEIVER_ASSOCIATED_FIRST),
                            moving(10L, FT_WITH_HTS_FIXED_FEE)
                                    .between(PAYER_WITH_TWO_HOOKS, RECEIVER_ASSOCIATED_THIRD)),
                    createNFTWithFixedFee(NFT_WITH_HTS_FEE, PAYER_WITH_TWO_HOOKS, supplyKey, adminKey, HTS_FEE),
                    mintNFT(NFT_WITH_HTS_FEE, 0, 5),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, NFT_WITH_HTS_FEE),
                    tokenAssociate(RECEIVER_ASSOCIATED_SECOND, NFT_WITH_HTS_FEE),
                    tokenAssociate(RECEIVER_ASSOCIATED_THIRD, NFT_WITH_HTS_FEE),

                    // transfer tokens
                    cryptoTransfer(
                            moving(20L, FT_WITH_HTS_FIXED_FEE).between(PAYER_WITH_TWO_HOOKS, RECEIVER_ASSOCIATED_FIRST),
                            movingUnique(NFT_WITH_HTS_FEE, 1L, 2L, 3L)
                                    .between(PAYER_WITH_TWO_HOOKS, RECEIVER_ASSOCIATED_THIRD)),
                    cryptoTransfer(
                                    moving(10L, FT_WITH_HTS_FIXED_FEE)
                                            .between(RECEIVER_ASSOCIATED_FIRST, RECEIVER_ASSOCIATED_SECOND),
                                    movingUnique(NFT_WITH_HTS_FEE, 1L)
                                            .between(RECEIVER_ASSOCIATED_THIRD, RECEIVER_ASSOCIATED_FIRST),
                                    movingUnique(NFT_WITH_HTS_FEE, 2L)
                                            .between(RECEIVER_ASSOCIATED_THIRD, RECEIVER_ASSOCIATED_SECOND),
                                    movingUnique(NFT_WITH_HTS_FEE, 4L)
                                            .between(PAYER_WITH_TWO_HOOKS, RECEIVER_FREE_AUTO_ASSOCIATIONS),
                                    movingUnique(NFT_WITH_HTS_FEE, 5L)
                                            .between(PAYER_WITH_TWO_HOOKS, RECEIVER_ASSOCIATED_FIRST))
                            .withNftSenderPreHookFor(PAYER_WITH_TWO_HOOKS, 1L, 10L, "")
                            .withNftSenderPreHookFor(PAYER_WITH_TWO_HOOKS, 2L, 10L, "")
                            .payingWith(PAYER_WITH_TWO_HOOKS)
                            .signedBy(PAYER_WITH_TWO_HOOKS, RECEIVER_ASSOCIATED_FIRST, RECEIVER_ASSOCIATED_THIRD)
                            .fee(ONE_HUNDRED_HBARS)
                            .via("tokenTransferTxn")
                            .hasKnownStatus(INSUFFICIENT_GAS),
                    validateChargedUsdWithinWithTxnSize(
                            "tokenTransferTxn",
                            txnSize -> expectedCryptoTransferTokenWithCustomFullFeeUsd(Map.of(
                                    SIGNATURES, 3L,
                                    HOOK_EXECUTION, 2L,
                                    ACCOUNTS, 5L,
                                    TOKEN_TYPES, 5L,
                                    GAS, 20L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.001),
                    getAccountBalance(HTS_COLLECTOR).hasTokenBalance(FT_WITH_HTS_FIXED_FEE, 0L),
                    getAccountBalance(DENOM_COLLECTOR).hasTokenBalance(DENOM_TOKEN, 0L),
                    getAccountBalance(PAYER_WITH_TWO_HOOKS)
                            .hasTokenBalance(FT_WITH_HTS_FIXED_FEE, 60L)
                            .hasTokenBalance(NFT_WITH_HTS_FEE, 2L)
                            .hasTokenBalance(DENOM_TOKEN, 10L),
                    getAccountBalance(RECEIVER_ASSOCIATED_FIRST)
                            .hasTokenBalance(FT_WITH_HTS_FIXED_FEE, 30L)
                            .hasTokenBalance(NFT_WITH_HTS_FEE, 0L)
                            .hasTokenBalance(DENOM_TOKEN, 10L),
                    getAccountBalance(RECEIVER_ASSOCIATED_SECOND)
                            .hasTokenBalance(FT_WITH_HTS_FIXED_FEE, 0L)
                            .hasTokenBalance(NFT_WITH_HTS_FEE, 0L),
                    getAccountBalance(RECEIVER_ASSOCIATED_THIRD)
                            .hasTokenBalance(NFT_WITH_HTS_FEE, 3L)
                            .hasTokenBalance(FT_WITH_HTS_FIXED_FEE, 10L)
                            .hasTokenBalance(DENOM_TOKEN, 10L),
                    getAccountBalance(RECEIVER_FREE_AUTO_ASSOCIATIONS).hasTokenBalance(NFT_WITH_HTS_FEE, 0L)));
        }
    }

    private HapiTokenCreate createFungibleTokenWithFixedHbarFee(
            String tokenName, long supply, String treasury, String adminKey, long hbarFee, String fixedFeeCollector) {
        return tokenCreate(tokenName)
                .initialSupply(supply)
                .treasury(treasury)
                .adminKey(adminKey)
                .feeScheduleKey(feeScheduleKey)
                .tokenType(FUNGIBLE_COMMON)
                .withCustom(fixedHbarFee(hbarFee, fixedFeeCollector));
    }

    private HapiTokenCreate createFungibleTokenWithFixedHtsFee(
            String tokenName, long supply, String treasury, String adminKey, long htsFee) {
        return tokenCreate(tokenName)
                .initialSupply(supply)
                .treasury(treasury)
                .adminKey(adminKey)
                .feeScheduleKey(feeScheduleKey)
                .tokenType(FUNGIBLE_COMMON)
                .withCustom(fixedHtsFee(htsFee, DENOM_TOKEN, DENOM_COLLECTOR));
    }

    private HapiTokenCreate createFungibleTokenWithTenPercentFractionalFee(
            String tokenName, long supply, String treasury, String adminKey, String fractionalFeeCollector) {
        return tokenCreate(tokenName)
                .initialSupply(supply)
                .treasury(treasury)
                .adminKey(adminKey)
                .feeScheduleKey(feeScheduleKey)
                .tokenType(FUNGIBLE_COMMON)
                .fee(ONE_HUNDRED_HBARS)
                .withCustom(fractionalFeeNetOfTransfers(1L, 10L, 1L, OptionalLong.empty(), fractionalFeeCollector))
                .payingWith(treasury)
                .signedBy(treasury, adminKey, fractionalFeeCollector);
    }

    private HapiTokenCreate createFungibleTokenWithFractionalAndFixedFees(
            String tokenName,
            long supply,
            String treasury,
            String adminKey,
            long hbarFee,
            String fixedFeeCollector,
            String fractionalFeeCollector) {
        return tokenCreate(tokenName)
                .initialSupply(supply)
                .treasury(treasury)
                .adminKey(adminKey)
                .feeScheduleKey(feeScheduleKey)
                .tokenType(FUNGIBLE_COMMON)
                .fee(ONE_HUNDRED_HBARS)
                .withCustom(fixedHbarFee(hbarFee, fixedFeeCollector))
                .withCustom(fractionalFeeNetOfTransfers(1L, 10L, 1L, OptionalLong.empty(), fractionalFeeCollector))
                .payingWith(treasury)
                .signedBy(treasury, adminKey, fractionalFeeCollector);
    }

    private HapiTokenCreate createNFTWithFixedFee(
            String tokenName, String treasury, String supplyKey, String adminKey, long htsFee) {
        return tokenCreate(tokenName)
                .initialSupply(0)
                .treasury(treasury)
                .tokenType(NON_FUNGIBLE_UNIQUE)
                .supplyKey(supplyKey)
                .adminKey(adminKey)
                .feeScheduleKey(feeScheduleKey)
                .withCustom(fixedHtsFee(htsFee, FT_WITH_HTS_FIXED_FEE, HTS_COLLECTOR));
    }

    private HapiTokenCreate createNFTWithRoyaltyNoFallback(
            String tokenName, String treasury, String supplyKey, String adminKey) {
        return tokenCreate(tokenName)
                .initialSupply(0)
                .treasury(treasury)
                .adminKey(adminKey)
                .feeScheduleKey(feeScheduleKey)
                .tokenType(NON_FUNGIBLE_UNIQUE)
                .supplyKey(supplyKey)
                .withCustom(royaltyFeeNoFallback(1L, 10L, ROYALTY_FEE_COLLECTOR));
    }

    private HapiTokenCreate createNFTWithRoyaltyWithFallback(
            String tokenName, String treasury, String supplyKey, String adminKey) {
        return tokenCreate(tokenName)
                .initialSupply(0)
                .treasury(treasury)
                .tokenType(NON_FUNGIBLE_UNIQUE)
                .supplyKey(supplyKey)
                .adminKey(adminKey)
                .feeScheduleKey(feeScheduleKey)
                .withCustom(royaltyFeeWithFallback(
                        1L, 10L, fixedHbarFeeInheritingRoyaltyCollector(1), ROYALTY_FEE_COLLECTOR));
    }

    private HapiTokenMint mintNFT(String tokenName, int rangeStart, int rangeEnd) {
        return mintToken(
                tokenName,
                IntStream.range(rangeStart, rangeEnd)
                        .mapToObj(a -> ByteString.copyFromUtf8(String.valueOf(a)))
                        .toList());
    }

    private HapiTokenCreate createFungibleTokenWithAdminKey(
            String tokenName, long supply, String treasury, String adminKey) {
        return tokenCreate(tokenName)
                .initialSupply(supply)
                .treasury(treasury)
                .adminKey(adminKey)
                .feeScheduleKey(feeScheduleKey)
                .tokenType(FUNGIBLE_COMMON);
    }

    private List<SpecOperation> createAccountsAndKeys() {
        return List.of(
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(PAYER_INSUFFICIENT_BALANCE).balance(ONE_HBAR / 100000),
                uploadInitCode(HOOK_CONTRACT),
                contractCreate(HOOK_CONTRACT).gas(5_000_000),
                cryptoCreate(PAYER_WITH_HOOK)
                        .balance(ONE_HUNDRED_HBARS)
                        .withHook(accountAllowanceHook(1L, HOOK_CONTRACT)),
                cryptoCreate(PAYER_WITH_TWO_HOOKS)
                        .balance(ONE_HUNDRED_HBARS)
                        .withHook(accountAllowanceHook(1L, HOOK_CONTRACT))
                        .withHook(accountAllowanceHook(2L, HOOK_CONTRACT)),
                cryptoCreate(OWNER).balance(ONE_MILLION_HBARS),
                cryptoCreate(NEW_TREASURY_WITH_UNLIMITED_AUTO_ASSOCIATIONS)
                        .balance(ONE_HUNDRED_HBARS)
                        .maxAutomaticTokenAssociations(-1),
                cryptoCreate(RECEIVER_ASSOCIATED_FIRST).balance(ONE_HBAR),
                cryptoCreate(RECEIVER_ASSOCIATED_SECOND).balance(ONE_HBAR),
                cryptoCreate(RECEIVER_ASSOCIATED_THIRD).balance(ONE_HBAR),
                cryptoCreate(RECEIVER_FREE_AUTO_ASSOCIATIONS).balance(ONE_HBAR).maxAutomaticTokenAssociations(1),
                cryptoCreate(RECEIVER_NO_AUTO_ASSOCIATIONS).balance(ONE_HBAR).maxAutomaticTokenAssociations(0),
                cryptoCreate(FEE_COLLECTOR).balance(0L),
                cryptoCreate(NEW_FEE_COLLECTOR).balance(0L),
                cryptoCreate(NEW_FEE_COLLECTOR_SECOND).balance(0L),
                cryptoCreate(HTS_COLLECTOR).balance(0L),
                cryptoCreate(DENOM_COLLECTOR).balance(0L),
                cryptoCreate(ROYALTY_FEE_COLLECTOR).balance(0L),
                cryptoCreate(DENOM_TREASURY).balance(0L),
                newKeyNamed(adminKey),
                newKeyNamed(feeScheduleKey),
                newKeyNamed(supplyKey));
    }
}
