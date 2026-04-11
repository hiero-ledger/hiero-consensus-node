// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip1261;

import static com.hedera.services.bdd.junit.TestTags.SIMPLE_FEES;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.accountWith;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAliasedAccountInfo;
import static com.hedera.services.bdd.spec.queries.crypto.ExpectedTokenRel.relationshipWith;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.accountAllowanceHook;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingHbar;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingUnique;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.flattened;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedCryptoTransferFTFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedCryptoTransferHBARAndFTAndNFTFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedCryptoTransferHBARAndFTFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedCryptoTransferHBARAndNFTFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedCryptoTransferHbarFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedCryptoTransferNFTFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.validateChargedUsdWithinWithTxnSize;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.TOKEN_ASSOCIATE_EXTRA_FEE_USD;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_GAS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ACCOUNT_ID;
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
import com.hedera.services.bdd.spec.keys.KeyShape;
import com.hedera.services.bdd.spec.transactions.token.HapiTokenCreate;
import com.hedera.services.bdd.spec.transactions.token.HapiTokenMint;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;

@Tag(SIMPLE_FEES)
@OrderedInIsolation
@HapiTestLifecycle
public class CryptoTransferWithHooksSimpleFeesTest {
    private static final String PAYER_WITH_HOOK = "payerWithHook";
    private static final String PAYER_WITH_TWO_HOOKS = "payerWithTwoHooks";
    private static final String RECEIVER_ASSOCIATED_FIRST = "receiverAssociatedFirst";
    private static final String RECEIVER_ASSOCIATED_SECOND = "receiverAssociatedSecond";
    private static final String RECEIVER_ASSOCIATED_THIRD = "receiverAssociatedThird";
    private static final String VALID_ALIAS_ED25519 = "validAliasED25519";
    private static final String FUNGIBLE_TOKEN = "fungibleToken";
    private static final String NON_FUNGIBLE_TOKEN = "nonFungibleToken";
    private static final String adminKey = "adminKey";
    private static final String supplyKey = "supplyKey";
    private static final String HOOK_CONTRACT = "TruePreHook";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(Map.of("fees.simpleFeesEnabled", "true", "hooks.hooksEnabled", "true"));
    }

    @Nested
    @DisplayName("Crypto Transfer With Hooks - Simple Fees Positive Tests")
    class PositiveTests {
        @HapiTest
        @DisplayName("Crypto Transfer HBAR with hook execution - extra hook full charging")
        final Stream<DynamicTest> cryptoTransferHBARWithOneHookExtraHookFullCharging() {
            return hapiTest(flattened(
                    createAccountsAndKeysWithHooks(),
                    cryptoTransfer(movingHbar(1L).between(PAYER_WITH_HOOK, RECEIVER_ASSOCIATED_FIRST))
                            .withPreHookFor(PAYER_WITH_HOOK, 1L, 5_000_000L, "")
                            .payingWith(PAYER_WITH_HOOK)
                            .signedBy(PAYER_WITH_HOOK)
                            .fee(ONE_MILLION_HBARS)
                            .via("hbarTransferTxn"),
                    validateChargedUsdWithinWithTxnSize(
                            "hbarTransferTxn",
                            txnSize -> expectedCryptoTransferHbarFullFeeUsd(Map.of(
                                    SIGNATURES, 1L,
                                    HOOK_EXECUTION, 1L,
                                    ACCOUNTS, 2L,
                                    GAS, 5_000_000L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.001)));
        }

        @HapiTest
        @DisplayName("Crypto Transfer FT with hook execution - extra hook full charging")
        final Stream<DynamicTest> cryptoTransferFTWithOneHookExtraHookFullCharging() {
            return hapiTest(flattened(
                    createAccountsAndKeysWithHooks(),
                    createFungibleTokenWithoutCustomFees(FUNGIBLE_TOKEN, 100L, PAYER_WITH_HOOK, adminKey),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FUNGIBLE_TOKEN),
                    cryptoTransfer(moving(10L, FUNGIBLE_TOKEN).between(PAYER_WITH_HOOK, RECEIVER_ASSOCIATED_FIRST))
                            .withPreHookFor(PAYER_WITH_HOOK, 1L, 5_000_000L, "")
                            .payingWith(PAYER_WITH_HOOK)
                            .signedBy(PAYER_WITH_HOOK)
                            .fee(ONE_HUNDRED_HBARS)
                            .via("ftTransferTxn"),
                    validateChargedUsdWithinWithTxnSize(
                            "ftTransferTxn",
                            txnSize -> expectedCryptoTransferFTFullFeeUsd(Map.of(
                                    SIGNATURES, 1L,
                                    HOOK_EXECUTION, 1L,
                                    ACCOUNTS, 2L,
                                    TOKEN_TYPES, 1L,
                                    GAS, 5_000_000L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.001),
                    getAccountBalance(PAYER_WITH_HOOK).hasTokenBalance(FUNGIBLE_TOKEN, 90L),
                    getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(FUNGIBLE_TOKEN, 10L)));
        }

        @HapiTest
        @DisplayName("Crypto Transfer FT with same hook executed twice - extra hook full charging")
        final Stream<DynamicTest> cryptoTransferFTWithOneHookExecutedTwiceExtraHookFullCharging() {
            return hapiTest(flattened(
                    createAccountsAndKeysWithHooks(),
                    createFungibleTokenWithoutCustomFees(FUNGIBLE_TOKEN, 100L, PAYER_WITH_HOOK, adminKey),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FUNGIBLE_TOKEN),
                    tokenAssociate(RECEIVER_ASSOCIATED_SECOND, FUNGIBLE_TOKEN),
                    cryptoTransfer(
                                    moving(10L, FUNGIBLE_TOKEN).between(PAYER_WITH_HOOK, RECEIVER_ASSOCIATED_FIRST),
                                    moving(10L, FUNGIBLE_TOKEN).between(PAYER_WITH_HOOK, RECEIVER_ASSOCIATED_SECOND))
                            .withPreHookFor(PAYER_WITH_HOOK, 1L, 5_000_000L, "")
                            .payingWith(PAYER_WITH_HOOK)
                            .signedBy(PAYER_WITH_HOOK)
                            .fee(ONE_HUNDRED_HBARS)
                            .via("ftTransferTxn"),
                    validateChargedUsdWithinWithTxnSize(
                            "ftTransferTxn",
                            txnSize -> expectedCryptoTransferFTFullFeeUsd(Map.of(
                                    SIGNATURES, 1L,
                                    HOOK_EXECUTION, 1L,
                                    ACCOUNTS, 3L,
                                    TOKEN_TYPES, 1L,
                                    GAS, 5_000_000L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.001),
                    getAccountBalance(PAYER_WITH_HOOK).hasTokenBalance(FUNGIBLE_TOKEN, 80L),
                    getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(FUNGIBLE_TOKEN, 10L),
                    getAccountBalance(RECEIVER_ASSOCIATED_SECOND).hasTokenBalance(FUNGIBLE_TOKEN, 10L)));
        }

        @HapiTest
        @DisplayName("Crypto Transfer NFT with hook execution - extra hook full charging")
        final Stream<DynamicTest> cryptoTransferNFTWithOneHookExtraHookFullCharging() {
            return hapiTest(flattened(
                    createAccountsAndKeysWithHooks(),
                    createNonFungibleTokenWithoutCustomFees(NON_FUNGIBLE_TOKEN, PAYER_WITH_HOOK, supplyKey, adminKey),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, NON_FUNGIBLE_TOKEN),
                    mintNFT(NON_FUNGIBLE_TOKEN, 1, 5),
                    cryptoTransfer(movingUnique(NON_FUNGIBLE_TOKEN, 1L).between(PAYER_WITH_HOOK, RECEIVER_ASSOCIATED_FIRST))
                            .withNftSenderPreHookFor(PAYER_WITH_HOOK, 1L, 5_000_000L, "")
                            .payingWith(PAYER_WITH_HOOK)
                            .signedBy(PAYER_WITH_HOOK)
                            .fee(ONE_HUNDRED_HBARS)
                            .via("nftTransferTxn"),
                    validateChargedUsdWithinWithTxnSize(
                            "nftTransferTxn",
                            txnSize -> expectedCryptoTransferNFTFullFeeUsd(Map.of(
                                    SIGNATURES, 1L,
                                    HOOK_EXECUTION, 1L,
                                    ACCOUNTS, 2L,
                                    TOKEN_TYPES, 1L,
                                    GAS, 5_000_000L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.001),
                    getAccountBalance(PAYER_WITH_HOOK).hasTokenBalance(NON_FUNGIBLE_TOKEN, 3L),
                    getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(NON_FUNGIBLE_TOKEN, 1L)));
        }

        @HapiTest
        @DisplayName("Crypto Transfer HBAR and FT with hook execution - extra hooks full charging")
        final Stream<DynamicTest> cryptoTransferHBARAndFtWithTwoHooksExtraHookFullCharging() {
            return hapiTest(flattened(
                    createAccountsAndKeysWithHooks(),
                    createFungibleTokenWithoutCustomFees(FUNGIBLE_TOKEN, 100L, PAYER_WITH_TWO_HOOKS, adminKey),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FUNGIBLE_TOKEN),
                    cryptoTransfer(
                                    movingHbar(1L).between(PAYER_WITH_HOOK, RECEIVER_ASSOCIATED_FIRST),
                                    moving(10L, FUNGIBLE_TOKEN).between(PAYER_WITH_TWO_HOOKS, RECEIVER_ASSOCIATED_FIRST))
                            .withPreHookFor(PAYER_WITH_HOOK, 1L, 5_000_000L, "")
                            .withPreHookFor(PAYER_WITH_TWO_HOOKS, 2L, 5_000_000L, "")
                            .payingWith(PAYER_WITH_HOOK)
                            .signedBy(PAYER_WITH_HOOK, PAYER_WITH_TWO_HOOKS)
                            .fee(ONE_HUNDRED_HBARS)
                            .via("tokenTransferTxn"),
                    validateChargedUsdWithinWithTxnSize(
                            "tokenTransferTxn",
                            txnSize -> expectedCryptoTransferHBARAndFTFullFeeUsd(Map.of(
                                    SIGNATURES, 2L,
                                    HOOK_EXECUTION, 2L,
                                    ACCOUNTS, 3L,
                                    TOKEN_TYPES, 1L,
                                    GAS, 10_000_000L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.001),
                    getAccountBalance(PAYER_WITH_TWO_HOOKS).hasTokenBalance(FUNGIBLE_TOKEN, 90L),
                    getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(FUNGIBLE_TOKEN, 10L)));
        }

        @HapiTest
        @DisplayName("Crypto Transfer HBAR and NFT with hook execution - extra hooks full charging")
        final Stream<DynamicTest> cryptoTransferHBARAndNFtWithOneHookExtraHookFullCharging() {
            return hapiTest(flattened(
                    createAccountsAndKeysWithHooks(),
                    createNonFungibleTokenWithoutCustomFees(NON_FUNGIBLE_TOKEN, PAYER_WITH_TWO_HOOKS, supplyKey, adminKey),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, NON_FUNGIBLE_TOKEN),
                    mintNFT(NON_FUNGIBLE_TOKEN, 1, 5),
                    cryptoTransfer(
                                    movingHbar(1L).between(PAYER_WITH_HOOK, RECEIVER_ASSOCIATED_FIRST),
                                    movingUnique(NON_FUNGIBLE_TOKEN, 1L)
                                            .between(PAYER_WITH_TWO_HOOKS, RECEIVER_ASSOCIATED_FIRST))
                            .withPreHookFor(PAYER_WITH_HOOK, 1L, 5_000_000L, "")
                            .withNftSenderPreHookFor(PAYER_WITH_TWO_HOOKS, 2L, 5_000_000L, "")
                            .payingWith(PAYER_WITH_HOOK)
                            .signedBy(PAYER_WITH_HOOK, PAYER_WITH_TWO_HOOKS)
                            .fee(ONE_HUNDRED_HBARS)
                            .via("tokenTransferTxn"),
                    validateChargedUsdWithinWithTxnSize(
                            "tokenTransferTxn",
                            txnSize -> expectedCryptoTransferHBARAndNFTFullFeeUsd(Map.of(
                                    SIGNATURES, 2L,
                                    HOOK_EXECUTION, 2L,
                                    ACCOUNTS, 3L,
                                    TOKEN_TYPES, 1L,
                                    GAS, 10_000_000L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.001),
                    getAccountBalance(PAYER_WITH_TWO_HOOKS).hasTokenBalance(NON_FUNGIBLE_TOKEN, 3L),
                    getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(NON_FUNGIBLE_TOKEN, 1L)));
        }

        @HapiTest
        @DisplayName("Crypto Transfer HBAR, FT and NFT with hook execution - extra hooks and accounts full charging")
        final Stream<DynamicTest> cryptoTransferHBARAndFtAndNFTWithTwoHooksExtraHooksAndAccountsFullCharging() {
            return hapiTest(flattened(
                    createAccountsAndKeysWithHooks(),
                    createFungibleTokenWithoutCustomFees(FUNGIBLE_TOKEN, 100L, PAYER_WITH_TWO_HOOKS, adminKey),
                    tokenAssociate(RECEIVER_ASSOCIATED_SECOND, FUNGIBLE_TOKEN),
                    createNonFungibleTokenWithoutCustomFees(NON_FUNGIBLE_TOKEN, PAYER_WITH_TWO_HOOKS, supplyKey, adminKey),
                    tokenAssociate(RECEIVER_ASSOCIATED_THIRD, NON_FUNGIBLE_TOKEN),
                    mintNFT(NON_FUNGIBLE_TOKEN, 1, 5),
                    cryptoTransfer(
                                    movingHbar(1L).between(PAYER_WITH_HOOK, RECEIVER_ASSOCIATED_FIRST),
                                    moving(10L, FUNGIBLE_TOKEN).between(PAYER_WITH_TWO_HOOKS, RECEIVER_ASSOCIATED_SECOND),
                                    movingUnique(NON_FUNGIBLE_TOKEN, 1L)
                                            .between(PAYER_WITH_TWO_HOOKS, RECEIVER_ASSOCIATED_THIRD))
                            .withPreHookFor(PAYER_WITH_TWO_HOOKS, 1L, 5_000_000L, "")
                            .withNftSenderPreHookFor(PAYER_WITH_TWO_HOOKS, 2L, 5_000_000L, "")
                            .payingWith(PAYER_WITH_HOOK)
                            .signedBy(PAYER_WITH_HOOK, PAYER_WITH_TWO_HOOKS)
                            .fee(ONE_MILLION_HBARS)
                            .via("tokenTransferTxn"),
                    validateChargedUsdWithinWithTxnSize(
                            "tokenTransferTxn",
                            txnSize -> expectedCryptoTransferHBARAndFTAndNFTFullFeeUsd(Map.of(
                                    SIGNATURES, 2L,
                                    HOOK_EXECUTION, 2L,
                                    ACCOUNTS, 5L,
                                    TOKEN_TYPES, 2L,
                                    GAS, 10_000_000L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.001),
                    getAccountBalance(PAYER_WITH_TWO_HOOKS)
                            .hasTokenBalance(FUNGIBLE_TOKEN, 90L)
                            .hasTokenBalance(NON_FUNGIBLE_TOKEN, 3L),
                    getAccountBalance(RECEIVER_ASSOCIATED_SECOND).hasTokenBalance(FUNGIBLE_TOKEN, 10L),
                    getAccountBalance(RECEIVER_ASSOCIATED_THIRD).hasTokenBalance(NON_FUNGIBLE_TOKEN, 1L)));
        }

        @HapiTest
        @DisplayName("Crypto Transfer HBAR, FT and NFT with hook execution - extra hooks, tokens and accounts full charging")
        final Stream<DynamicTest> cryptoTransferHBARAndFtAndNFTWithTwoHooksExtraHooksAndTokensAndAccountsFullCharging() {
            return hapiTest(flattened(
                    createAccountsAndKeysWithHooks(),
                    createFungibleTokenWithoutCustomFees(FUNGIBLE_TOKEN, 100L, PAYER_WITH_TWO_HOOKS, adminKey),
                    tokenAssociate(RECEIVER_ASSOCIATED_SECOND, FUNGIBLE_TOKEN),
                    createNonFungibleTokenWithoutCustomFees(NON_FUNGIBLE_TOKEN, PAYER_WITH_TWO_HOOKS, supplyKey, adminKey),
                    tokenAssociate(RECEIVER_ASSOCIATED_THIRD, NON_FUNGIBLE_TOKEN),
                    mintNFT(NON_FUNGIBLE_TOKEN, 1, 5),
                    cryptoTransfer(
                                    movingHbar(1L).between(PAYER_WITH_HOOK, RECEIVER_ASSOCIATED_FIRST),
                                    moving(10L, FUNGIBLE_TOKEN).between(PAYER_WITH_TWO_HOOKS, RECEIVER_ASSOCIATED_SECOND),
                                    movingUnique(NON_FUNGIBLE_TOKEN, 1L)
                                            .between(PAYER_WITH_TWO_HOOKS, RECEIVER_ASSOCIATED_THIRD))
                            .withPreHookFor(PAYER_WITH_TWO_HOOKS, 1L, 5_000_000L, "")
                            .withNftSenderPreHookFor(PAYER_WITH_TWO_HOOKS, 2L, 5_000_000L, "")
                            .payingWith(PAYER_WITH_HOOK)
                            .signedBy(PAYER_WITH_HOOK, PAYER_WITH_TWO_HOOKS)
                            .fee(ONE_HUNDRED_HBARS)
                            .via("tokenTransferTxn"),
                    validateChargedUsdWithinWithTxnSize(
                            "tokenTransferTxn",
                            txnSize -> expectedCryptoTransferHBARAndFTAndNFTFullFeeUsd(Map.of(
                                    SIGNATURES, 2L,
                                    HOOK_EXECUTION, 2L,
                                    ACCOUNTS, 5L,
                                    TOKEN_TYPES, 2L,
                                    GAS, 10_000_000L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.001),
                    getAccountBalance(PAYER_WITH_TWO_HOOKS)
                            .hasTokenBalance(FUNGIBLE_TOKEN, 90L)
                            .hasTokenBalance(NON_FUNGIBLE_TOKEN, 3L),
                    getAccountBalance(RECEIVER_ASSOCIATED_SECOND).hasTokenBalance(FUNGIBLE_TOKEN, 10L),
                    getAccountBalance(RECEIVER_ASSOCIATED_THIRD).hasTokenBalance(NON_FUNGIBLE_TOKEN, 1L)));
        }

        @HapiTest
        @DisplayName("Crypto Transfer - Auto Create Accounts with FT moving and with hook execution - extra hook full charging")
        final Stream<DynamicTest> cryptoTransferFTAutoAccountCreationWithOneHookExtraHookFullCharging() {
            return hapiTest(flattened(
                    createAccountsAndKeysWithHooks(),
                    createFungibleTokenWithoutCustomFees(FUNGIBLE_TOKEN, 100L, PAYER_WITH_HOOK, adminKey),
                    cryptoTransfer(moving(10L, FUNGIBLE_TOKEN).between(PAYER_WITH_HOOK, VALID_ALIAS_ED25519))
                            .withPreHookFor(PAYER_WITH_HOOK, 1L, 5_000_000L, "")
                            .payingWith(PAYER_WITH_HOOK)
                            .signedBy(PAYER_WITH_HOOK)
                            .fee(ONE_HUNDRED_HBARS)
                            .via("ftTransferTxn"),
                    validateChargedUsdWithinWithTxnSize(
                            "ftTransferTxn",
                            txnSize -> expectedCryptoTransferFTFullFeeUsd(Map.of(
                                            SIGNATURES, 1L,
                                            HOOK_EXECUTION, 1L,
                                            ACCOUNTS, 2L,
                                            TOKEN_TYPES, 1L,
                                            GAS, 5_000_000L,
                                            PROCESSING_BYTES, (long) txnSize))
                                    + TOKEN_ASSOCIATE_EXTRA_FEE_USD,
                            0.001),
                    getAliasedAccountInfo(VALID_ALIAS_ED25519)
                            .hasToken(relationshipWith(FUNGIBLE_TOKEN))
                            .has(accountWith().key(VALID_ALIAS_ED25519).alias(VALID_ALIAS_ED25519).maxAutoAssociations(-1)),
                    getAccountBalance(PAYER_WITH_HOOK).hasTokenBalance(FUNGIBLE_TOKEN, 90L)));
        }
    }

    @Nested
    @DisplayName("Crypto Transfer With Hooks Negative Tests")
    class NegativeTests {
        @HapiTest
        @DisplayName("Crypto Transfer - Auto Create Accounts with FT moving and failing hook - fails on handle")
        final Stream<DynamicTest> cryptoTransferFTAutoAccountCreationWithFailingHookFailsOnHandle() {
            return hapiTest(flattened(
                    createAccountsAndKeysWithHooks(),
                    createFungibleTokenWithoutCustomFees(FUNGIBLE_TOKEN, 100L, PAYER_WITH_HOOK, adminKey),
                    cryptoTransfer(moving(10L, FUNGIBLE_TOKEN).between(PAYER_WITH_HOOK, VALID_ALIAS_ED25519))
                            .withPreHookFor(PAYER_WITH_HOOK, 1L, 10L, "")
                            .payingWith(PAYER_WITH_HOOK)
                            .signedBy(PAYER_WITH_HOOK)
                            .fee(ONE_HUNDRED_HBARS)
                            .via("ftTransferTxn")
                            .hasKnownStatus(INSUFFICIENT_GAS),
                    validateChargedUsdWithinWithTxnSize(
                            "ftTransferTxn",
                            txnSize -> expectedCryptoTransferFTFullFeeUsd(Map.of(
                                    SIGNATURES, 1L,
                                    HOOK_EXECUTION, 1L,
                                    ACCOUNTS, 2L,
                                    TOKEN_TYPES, 1L,
                                    GAS, 10L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.001),
                    getAliasedAccountInfo(VALID_ALIAS_ED25519).hasCostAnswerPrecheck(INVALID_ACCOUNT_ID),
                    getAccountBalance(PAYER_WITH_HOOK).hasTokenBalance(FUNGIBLE_TOKEN, 100L)));
        }
    }

    private HapiTokenCreate createFungibleTokenWithoutCustomFees(
            final String tokenName, final long supply, final String treasury, final String adminKey) {
        return tokenCreate(tokenName)
                .initialSupply(supply)
                .treasury(treasury)
                .adminKey(adminKey)
                .tokenType(FUNGIBLE_COMMON);
    }

    private HapiTokenCreate createNonFungibleTokenWithoutCustomFees(
            final String tokenName, final String treasury, final String supplyKey, final String adminKey) {
        return tokenCreate(tokenName)
                .initialSupply(0)
                .treasury(treasury)
                .tokenType(NON_FUNGIBLE_UNIQUE)
                .supplyKey(supplyKey)
                .adminKey(adminKey);
    }

    private HapiTokenMint mintNFT(final String tokenName, final int rangeStart, final int rangeEnd) {
        return mintToken(
                tokenName,
                IntStream.range(rangeStart, rangeEnd)
                        .mapToObj(a -> ByteString.copyFromUtf8(String.valueOf(a)))
                        .toList());
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
                newKeyNamed(VALID_ALIAS_ED25519).shape(KeyShape.ED25519),
                newKeyNamed(adminKey),
                newKeyNamed(supplyKey));
    }

    private List<SpecOperation> createHookAccountsAndKeys() {
        return List.of(
                uploadInitCode(HOOK_CONTRACT),
                contractCreate(HOOK_CONTRACT).gas(5_000_000),
                cryptoCreate(PAYER_WITH_HOOK)
                        .balance(ONE_MILLION_HBARS)
                        .withHook(accountAllowanceHook(1L, HOOK_CONTRACT)),
                cryptoCreate(PAYER_WITH_TWO_HOOKS)
                        .balance(ONE_HUNDRED_HBARS)
                        .withHook(accountAllowanceHook(1L, HOOK_CONTRACT))
                        .withHook(accountAllowanceHook(2L, HOOK_CONTRACT)));
    }
}
