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
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedHtsFee;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingUnique;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.flattened;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedCryptoTransferTokenWithCustomFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.validateChargedUsdWithinWithTxnSize;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.TOKEN_ASSOCIATE_EXTRA_FEE_USD;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_GAS;
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
import com.hedera.services.bdd.junit.OrderedInIsolation;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.transactions.token.HapiTokenCreate;
import com.hedera.services.bdd.spec.transactions.token.HapiTokenMint;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;

@Tag(SIMPLE_FEES)
@OrderedInIsolation
@HapiTestLifecycle
public class CryptoTransferWithCustomFeesAndHooksSimpleFeesTest {
    private static final String FT_WITH_HTS_FIXED_FEE = "ftWithHtsFixedFee";
    private static final String NFT_WITH_HTS_FEE = "nftWithHtsFee";
    private static final String DENOM_TOKEN = "denomToken";
    private static final String HTS_COLLECTOR = "htsCollector";
    private static final String DENOM_COLLECTOR = "denomCollector";
    private static final String DENOM_TREASURY = "denomTreasury";
    private static final String RECEIVER_ASSOCIATED_FIRST = "receiverAssociatedFirst";
    private static final String RECEIVER_ASSOCIATED_SECOND = "receiverAssociatedSecond";
    private static final String RECEIVER_ASSOCIATED_THIRD = "receiverAssociatedThird";
    private static final String RECEIVER_FREE_AUTO_ASSOCIATIONS = "receiverFreeAutoAssociations";
    private static final long HTS_FEE = 1L;
    private static final String adminKey = "adminKey";
    private static final String feeScheduleKey = "feeScheduleKey";
    private static final String supplyKey = "supplyKey";
    private static final String HOOK_CONTRACT = "TruePreHook";
    private static final String PAYER_WITH_TWO_HOOKS = "payerWithTwoHooks";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(Map.of("fees.simpleFeesEnabled", "true", "hooks.hooksEnabled", "true"));
    }

    @Nested
    @DisplayName("Crypto Transfer with Custom Fees and Hooks - Simple Fees Test")
    class PositiveTests {
        @HapiTest
        @DisplayName(
                "Crypto Transfer FT and NFT with custom fees and with hook execution - extra hooks, tokens and accounts full charging")
        final Stream<DynamicTest>
                cryptoTransferHBARAndFtAndNFTWithTwoHooksExtraHooksAndTokensAndAccountsFullCharging() {
            return hapiTest(flattened(
                    createAccountsAndKeysWithHooks(),
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
    @DisplayName("Crypto Transfer with Failing Hooks - Simple Fees Negative Tests")
    class NegativeTests {
        @HapiTest
        @DisplayName("Crypto Transfer FT and NFT with custom fees and with failing hook - fails on handle")
        final Stream<DynamicTest> cryptoTransferHBARAndFtAndNFTWithCustomFeesAndWithFailingHookFailsOnHandle() {
            return hapiTest(flattened(
                    createAccountsAndKeysWithHooks(),
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

    private HapiTokenCreate createFungibleTokenWithFixedHtsFee(
            final String tokenName,
            final long supply,
            final String treasury,
            final String adminKey,
            final long htsFee) {
        return tokenCreate(tokenName)
                .initialSupply(supply)
                .treasury(treasury)
                .adminKey(adminKey)
                .feeScheduleKey(feeScheduleKey)
                .tokenType(FUNGIBLE_COMMON)
                .withCustom(fixedHtsFee(htsFee, DENOM_TOKEN, DENOM_COLLECTOR));
    }

    private HapiTokenCreate createNFTWithFixedFee(
            final String tokenName,
            final String treasury,
            final String supplyKey,
            final String adminKey,
            final long htsFee) {
        return tokenCreate(tokenName)
                .initialSupply(0)
                .treasury(treasury)
                .tokenType(NON_FUNGIBLE_UNIQUE)
                .supplyKey(supplyKey)
                .adminKey(adminKey)
                .feeScheduleKey(feeScheduleKey)
                .withCustom(fixedHtsFee(htsFee, FT_WITH_HTS_FIXED_FEE, HTS_COLLECTOR));
    }

    private HapiTokenMint mintNFT(final String tokenName, final int rangeStart, final int rangeEnd) {
        return mintToken(
                tokenName,
                IntStream.range(rangeStart, rangeEnd)
                        .mapToObj(a -> ByteString.copyFromUtf8(String.valueOf(a)))
                        .toList());
    }

    private HapiTokenCreate createFungibleTokenWithAdminKey(
            final String tokenName, final long supply, final String treasury, final String adminKey) {
        return tokenCreate(tokenName)
                .initialSupply(supply)
                .treasury(treasury)
                .adminKey(adminKey)
                .feeScheduleKey(feeScheduleKey)
                .tokenType(FUNGIBLE_COMMON);
    }

    private List<SpecOperation> createAccountsAndKeysWithHooks() {
        return Stream.concat(createAccountsAndKeys().stream(), createHookAccountsAndKeys().stream())
                .toList();
    }

    private List<SpecOperation> createAccountsAndKeys() {
        return List.of(
                cryptoCreate(RECEIVER_ASSOCIATED_FIRST).balance(ONE_HBAR),
                cryptoCreate(RECEIVER_ASSOCIATED_SECOND).balance(ONE_HBAR),
                cryptoCreate(RECEIVER_ASSOCIATED_THIRD).balance(ONE_HBAR),
                cryptoCreate(RECEIVER_FREE_AUTO_ASSOCIATIONS).balance(ONE_HBAR).maxAutomaticTokenAssociations(1),
                cryptoCreate(HTS_COLLECTOR).balance(0L),
                cryptoCreate(DENOM_COLLECTOR).balance(0L),
                cryptoCreate(DENOM_TREASURY).balance(0L),
                newKeyNamed(adminKey),
                newKeyNamed(feeScheduleKey),
                newKeyNamed(supplyKey));
    }

    private List<SpecOperation> createHookAccountsAndKeys() {
        return List.of(
                uploadInitCode(HOOK_CONTRACT),
                contractCreate(HOOK_CONTRACT).gas(5_000_000),
                cryptoCreate(PAYER_WITH_TWO_HOOKS)
                        .balance(ONE_HUNDRED_HBARS)
                        .withHook(accountAllowanceHook(1L, HOOK_CONTRACT))
                        .withHook(accountAllowanceHook(2L, HOOK_CONTRACT)));
    }
}
