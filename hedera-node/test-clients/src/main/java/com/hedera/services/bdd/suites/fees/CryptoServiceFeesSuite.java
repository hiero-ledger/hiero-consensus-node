// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.fees;

import static com.google.protobuf.ByteString.copyFromUtf8;
import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.spec.HapiSpec.customizedHapiTest;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AccountDetailsAsserts.accountDetailsWith;
import static com.hedera.services.bdd.spec.keys.KeyShape.SIMPLE;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountDetails;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountRecords;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoApproveAllowance;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoDeleteAllowance;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoApproveAllowance.MISSING_OWNER;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedHbarFee;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingUnique;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overridingTwo;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sleepFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsd;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsdWithin;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.THREE_MONTHS_IN_SECONDS;
import static com.hedera.services.bdd.suites.HapiSuite.TOKEN_TREASURY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_MAX_AUTO_ASSOCIATIONS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.REQUESTED_NUM_AUTOMATIC_ASSOCIATIONS_EXCEEDS_ASSOCIATION_LIMIT;
import static com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hederahashgraph.api.proto.java.TokenSupplyType;
import com.hederahashgraph.api.proto.java.TokenType;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@HapiTestLifecycle
public class CryptoServiceFeesSuite {
    private static final double BASE_FEE_CRYPTO_GET_ACCOUNT_INFO = 0.0001;
    private static final double BASE_FEE_CRYPTO_CREATE = 0.05;
    private static final double BASE_FEE_CRYPTO_DELETE = 0.005;
    private static final double BASE_FEE_CRYPTO_DELETE_ALLOWANCE = 0.05;
    private static final double BASE_FEE_CRYPTO_UPDATE = 0.000214;
    private static final double BASE_FEE_WITH_EXPIRY_CRYPTO_UPDATE = 0.00022;
    private static final double BASE_FEE_HBAR_CRYPTO_TRANSFER = 0.0001;
    private static final double BASE_FEE_HTS_CRYPTO_TRANSFER = 0.001;
    private static final double BASE_FEE_NFT_CRYPTO_TRANSFER = 0.001;
    private static final double BASE_FEE_CRYPTO_GET_ACCOUNT_RECORDS = 0.0001;

    private static final String CIVILIAN = "civilian";
    private static final String FEES_ACCOUNT = "feesAccount";
    private static final String OWNER = "owner";
    private static final String SPENDER = "spender";
    private static final String SECOND_SPENDER = "spender2";
    private static final String SENDER = "sender";
    private static final String RECEIVER = "receiver";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.doAdhoc(
                cryptoCreate(FEES_ACCOUNT).balance(5 * ONE_HUNDRED_HBARS),
                cryptoCreate(CIVILIAN).balance(5 * ONE_HUNDRED_HBARS).key(FEES_ACCOUNT));
    }

    @HapiTest
    @DisplayName("CryptoCreate transaction has expected base fee")
    final Stream<DynamicTest> cryptoCreateBaseUSDFee() {
        final var cryptoCreate = "cryptoCreate";
        return hapiTest(
                cryptoCreate(cryptoCreate)
                        .key(CIVILIAN)
                        .via(cryptoCreate)
                        .blankMemo()
                        .signedBy(CIVILIAN)
                        .payingWith(CIVILIAN),
                validateChargedUsd(cryptoCreate, BASE_FEE_CRYPTO_CREATE));
    }

    @HapiTest
    @DisplayName("CryptoDelete transaction has expected base fee")
    final Stream<DynamicTest> cryptoDeleteBaseUSDFee() {
        final var cryptoCreate = "cryptoCreate";
        final var cryptoDelete = "cryptoDelete";
        return customizedHapiTest(
                Map.of("memo.useSpecName", "false"),
                cryptoCreate(cryptoCreate).balance(5 * ONE_HUNDRED_HBARS).key(CIVILIAN),
                cryptoDelete(cryptoCreate)
                        .via(cryptoDelete)
                        .payingWith(CIVILIAN)
                        .signedBy(CIVILIAN),
                validateChargedUsd(cryptoDelete, BASE_FEE_CRYPTO_DELETE));
    }

    @HapiTest
    @DisplayName("CryptoDeleteAllowance transaction has expected base fee")
    final Stream<DynamicTest> cryptoDeleteAllowanceBaseUSDFee() {
        final String token = "token";
        final String nft = "nft";
        final String supplyKey = "supplyKey";
        final String baseDeleteNft = "baseDeleteNft";
        return hapiTest(
                newKeyNamed(supplyKey),
                cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                cryptoCreate(SPENDER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(TOKEN_TREASURY).balance(100 * ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                tokenCreate(token)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .supplyType(TokenSupplyType.FINITE)
                        .supplyKey(supplyKey)
                        .initialSupply(10L)
                        .maxSupply(1000L)
                        .treasury(TOKEN_TREASURY),
                tokenCreate(nft)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .supplyType(TokenSupplyType.FINITE)
                        .supplyKey(supplyKey)
                        .initialSupply(0)
                        .maxSupply(10L)
                        .treasury(TOKEN_TREASURY),
                tokenAssociate(OWNER, token),
                tokenAssociate(OWNER, nft),
                mintToken(
                                nft,
                                List.of(
                                        ByteString.copyFromUtf8("a"),
                                        ByteString.copyFromUtf8("b"),
                                        ByteString.copyFromUtf8("c")))
                        .via("nftTokenMint"),
                mintToken(token, 500L).via("tokenMint"),
                cryptoTransfer(movingUnique(nft, 1L, 2L, 3L).between(TOKEN_TREASURY, OWNER)),
                cryptoApproveAllowance()
                        .payingWith(OWNER)
                        .addCryptoAllowance(OWNER, SPENDER, 100L)
                        .addTokenAllowance(OWNER, token, SPENDER, 100L)
                        .addNftAllowance(OWNER, nft, SPENDER, false, List.of(1L, 2L, 3L)),
                /* without specifying owner */
                cryptoDeleteAllowance()
                        .payingWith(OWNER)
                        .blankMemo()
                        .addNftDeleteAllowance(MISSING_OWNER, nft, List.of(1L))
                        .via(baseDeleteNft),
                validateChargedUsdWithin(baseDeleteNft, BASE_FEE_CRYPTO_DELETE_ALLOWANCE, 0.01),
                cryptoApproveAllowance().payingWith(OWNER).addNftAllowance(OWNER, nft, SPENDER, false, List.of(1L)),
                /* with specifying owner */
                cryptoDeleteAllowance()
                        .payingWith(OWNER)
                        .blankMemo()
                        .addNftDeleteAllowance(OWNER, nft, List.of(1L))
                        .via(baseDeleteNft),
                validateChargedUsdWithin(baseDeleteNft, BASE_FEE_CRYPTO_DELETE_ALLOWANCE, 0.01));
    }

    @HapiTest
    @DisplayName("CryptoApproveAllowance transaction has expected base fee")
    final Stream<DynamicTest> cryptoApproveAllowanceBaseUSDFee() {
        final String SUPPLY_KEY = "supplyKeyApproveAllowance";
        final String APPROVE_TXN = "approveTxn";
        final String ANOTHER_SPENDER = "spender1";
        final String FUNGIBLE_TOKEN = "fungible";
        final String NON_FUNGIBLE_TOKEN = "nonFungible";

        return hapiTest(
                newKeyNamed(SUPPLY_KEY),
                cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                cryptoCreate(SPENDER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(ANOTHER_SPENDER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(SECOND_SPENDER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(TOKEN_TREASURY).balance(100 * ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                tokenCreate(FUNGIBLE_TOKEN)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .supplyType(TokenSupplyType.FINITE)
                        .supplyKey(SUPPLY_KEY)
                        .maxSupply(1000L)
                        .initialSupply(10L)
                        .treasury(TOKEN_TREASURY),
                tokenCreate(NON_FUNGIBLE_TOKEN)
                        .maxSupply(10L)
                        .initialSupply(0)
                        .supplyType(TokenSupplyType.FINITE)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .supplyKey(SUPPLY_KEY)
                        .treasury(TOKEN_TREASURY),
                tokenAssociate(OWNER, FUNGIBLE_TOKEN),
                tokenAssociate(OWNER, NON_FUNGIBLE_TOKEN),
                mintToken(
                        NON_FUNGIBLE_TOKEN,
                        List.of(
                                ByteString.copyFromUtf8("a"),
                                ByteString.copyFromUtf8("b"),
                                ByteString.copyFromUtf8("c"))),
                mintToken(FUNGIBLE_TOKEN, 500L),
                cryptoTransfer(movingUnique(NON_FUNGIBLE_TOKEN, 1L, 2L, 3L).between(TOKEN_TREASURY, OWNER)),
                cryptoApproveAllowance()
                        .payingWith(OWNER)
                        .addCryptoAllowance(OWNER, SPENDER, 100L)
                        .via("approve")
                        .fee(ONE_HBAR)
                        .blankMemo()
                        .logged(),
                validateChargedUsdWithin("approve", 0.05, 0.01),
                cryptoApproveAllowance()
                        .payingWith(OWNER)
                        .addTokenAllowance(OWNER, FUNGIBLE_TOKEN, SPENDER, 100L)
                        .via("approveTokenTxn")
                        .fee(ONE_HBAR)
                        .blankMemo()
                        .logged(),
                validateChargedUsdWithin("approveTokenTxn", 0.05012, 0.01),
                cryptoApproveAllowance()
                        .payingWith(OWNER)
                        .addNftAllowance(OWNER, NON_FUNGIBLE_TOKEN, SPENDER, false, List.of(1L))
                        .via("approveNftTxn")
                        .fee(ONE_HBAR)
                        .blankMemo()
                        .logged(),
                validateChargedUsdWithin("approveNftTxn", 0.050101, 0.01),
                cryptoApproveAllowance()
                        .payingWith(OWNER)
                        .addNftAllowance(OWNER, NON_FUNGIBLE_TOKEN, ANOTHER_SPENDER, true, List.of())
                        .via("approveForAllNftTxn")
                        .fee(ONE_HBAR)
                        .blankMemo()
                        .logged(),
                validateChargedUsdWithin("approveForAllNftTxn", 0.05, 0.01),
                cryptoApproveAllowance()
                        .payingWith(OWNER)
                        .addCryptoAllowance(OWNER, SECOND_SPENDER, 100L)
                        .addTokenAllowance(OWNER, FUNGIBLE_TOKEN, SECOND_SPENDER, 100L)
                        .addNftAllowance(OWNER, NON_FUNGIBLE_TOKEN, SECOND_SPENDER, false, List.of(1L))
                        .via(APPROVE_TXN)
                        .fee(ONE_HBAR)
                        .blankMemo()
                        .logged(),
                validateChargedUsdWithin(APPROVE_TXN, 0.05238, 0.01),
                getAccountDetails(OWNER)
                        .payingWith(GENESIS)
                        .has(accountDetailsWith()
                                .cryptoAllowancesCount(2)
                                .nftApprovedForAllAllowancesCount(1)
                                .tokenAllowancesCount(2)
                                .cryptoAllowancesContaining(SECOND_SPENDER, 100L)
                                .tokenAllowancesContaining(FUNGIBLE_TOKEN, SECOND_SPENDER, 100L)),
                /* edit existing allowances */
                cryptoApproveAllowance()
                        .payingWith(OWNER)
                        .addCryptoAllowance(OWNER, SECOND_SPENDER, 200L)
                        .via("approveModifyCryptoTxn")
                        .fee(ONE_HBAR)
                        .blankMemo()
                        .logged(),
                validateChargedUsdWithin("approveModifyCryptoTxn", 0.049375, 0.01),
                cryptoApproveAllowance()
                        .payingWith(OWNER)
                        .addTokenAllowance(OWNER, FUNGIBLE_TOKEN, SECOND_SPENDER, 200L)
                        .via("approveModifyTokenTxn")
                        .fee(ONE_HBAR)
                        .blankMemo()
                        .logged(),
                validateChargedUsdWithin("approveModifyTokenTxn", 0.04943, 0.01),
                cryptoApproveAllowance()
                        .payingWith(OWNER)
                        .addNftAllowance(OWNER, NON_FUNGIBLE_TOKEN, ANOTHER_SPENDER, false, List.of())
                        .via("approveModifyNftTxn")
                        .fee(ONE_HBAR)
                        .blankMemo()
                        .logged(),
                validateChargedUsdWithin("approveModifyNftTxn", 0.049375, 0.01),
                getAccountDetails(OWNER)
                        .payingWith(GENESIS)
                        .has(accountDetailsWith()
                                .cryptoAllowancesCount(2)
                                .nftApprovedForAllAllowancesCount(0)
                                .tokenAllowancesCount(2)
                                .cryptoAllowancesContaining(SECOND_SPENDER, 200L)
                                .tokenAllowancesContaining(FUNGIBLE_TOKEN, SECOND_SPENDER, 200L)));
    }

    @LeakyHapiTest(overrides = {"entities.maxLifetime", "ledger.maxAutoAssociations"})
    @DisplayName("CryptoUpdate transaction has expected base fee")
    @Tag(MATS)
    final Stream<DynamicTest> cryptoUpdateBaseUSDFee() {

        final var baseTxn = "baseTxn";
        final var plusOneTxn = "plusOneTxn";
        final var plusTenTxn = "plusTenTxn";
        final var plusFiveKTxn = "plusFiveKTxn";
        final var plusFiveKAndOneTxn = "plusFiveKAndOneTxn";
        final var invalidNegativeTxn = "invalidNegativeTxn";
        final var validNegativeTxn = "validNegativeTxn";
        final var allowedPercentDiff = 1.5;
        final var canonicalAccount = "canonicalAccount";
        final var payer = "payer";
        final var autoAssocTarget = "autoAssocTarget";

        AtomicLong expiration = new AtomicLong();
        return hapiTest(
                overridingTwo(
                        "ledger.maxAutoAssociations", "5000",
                        "entities.maxLifetime", "3153600000"),
                newKeyNamed("key").shape(SIMPLE),
                cryptoCreate(payer).key("key").balance(1_000 * ONE_HBAR),
                cryptoCreate(canonicalAccount)
                        .key("key")
                        .balance(100 * ONE_HBAR)
                        .autoRenewSecs(THREE_MONTHS_IN_SECONDS)
                        .blankMemo()
                        .payingWith(payer),
                cryptoCreate(autoAssocTarget)
                        .key("key")
                        .balance(100 * ONE_HBAR)
                        .autoRenewSecs(THREE_MONTHS_IN_SECONDS)
                        .blankMemo()
                        .payingWith(payer),
                getAccountInfo(canonicalAccount).exposingExpiry(expiration::set),
                sourcing(() -> cryptoUpdate(canonicalAccount)
                        .payingWith(canonicalAccount)
                        .expiring(expiration.get() + THREE_MONTHS_IN_SECONDS)
                        .blankMemo()
                        .via(baseTxn)),
                getAccountInfo(canonicalAccount).hasMaxAutomaticAssociations(0).logged(),
                cryptoUpdate(autoAssocTarget)
                        .payingWith(autoAssocTarget)
                        .blankMemo()
                        .maxAutomaticAssociations(1)
                        .via(plusOneTxn),
                getAccountInfo(autoAssocTarget).hasMaxAutomaticAssociations(1).logged(),
                cryptoUpdate(autoAssocTarget)
                        .payingWith(autoAssocTarget)
                        .blankMemo()
                        .maxAutomaticAssociations(11)
                        .via(plusTenTxn),
                getAccountInfo(autoAssocTarget).hasMaxAutomaticAssociations(11).logged(),
                cryptoUpdate(autoAssocTarget)
                        .payingWith(autoAssocTarget)
                        .blankMemo()
                        .maxAutomaticAssociations(5000)
                        .via(plusFiveKTxn),
                getAccountInfo(autoAssocTarget)
                        .hasMaxAutomaticAssociations(5000)
                        .logged(),
                cryptoUpdate(autoAssocTarget)
                        .payingWith(autoAssocTarget)
                        .blankMemo()
                        .maxAutomaticAssociations(-1000)
                        .via(invalidNegativeTxn)
                        .hasKnownStatus(INVALID_MAX_AUTO_ASSOCIATIONS),
                cryptoUpdate(autoAssocTarget)
                        .payingWith(autoAssocTarget)
                        .blankMemo()
                        .maxAutomaticAssociations(5001)
                        .via(plusFiveKAndOneTxn)
                        .hasKnownStatus(REQUESTED_NUM_AUTOMATIC_ASSOCIATIONS_EXCEEDS_ASSOCIATION_LIMIT),
                cryptoUpdate(autoAssocTarget)
                        .payingWith(autoAssocTarget)
                        .blankMemo()
                        .maxAutomaticAssociations(-1)
                        .via(validNegativeTxn),
                getAccountInfo(autoAssocTarget).hasMaxAutomaticAssociations(-1).logged(),
                validateChargedUsd(baseTxn, BASE_FEE_WITH_EXPIRY_CRYPTO_UPDATE, allowedPercentDiff),
                validateChargedUsd(plusOneTxn, BASE_FEE_CRYPTO_UPDATE, allowedPercentDiff),
                validateChargedUsd(plusTenTxn, BASE_FEE_CRYPTO_UPDATE, allowedPercentDiff),
                validateChargedUsd(plusFiveKTxn, BASE_FEE_CRYPTO_UPDATE, allowedPercentDiff),
                validateChargedUsd(validNegativeTxn, BASE_FEE_CRYPTO_UPDATE, allowedPercentDiff));
    }

    @HapiTest
    @DisplayName("CryptoTransfer transaction has expected base fee")
    final Stream<DynamicTest> cryptoTransferBaseUSDFee() {
        final String SUPPLY_KEY = "supplyKeyCryptoTransfer";
        final var expectedHtsXferWithCustomFeePriceUsd = 0.002;
        final var expectedNftXferWithCustomFeePriceUsd = 0.002;
        final var transferAmount = 1L;
        final var customFeeCollector = "customFeeCollector";
        final var nonTreasurySender = "nonTreasurySender";
        final var hbarXferTxn = "hbarXferTxn";
        final var fungibleToken = "fungibleToken";
        final var fungibleTokenWithCustomFee = "fungibleTokenWithCustomFee";
        final var htsXferTxn = "htsXferTxn";
        final var htsXferTxnWithCustomFee = "htsXferTxnWithCustomFee";
        final var nonFungibleToken = "nonFungibleToken";
        final var nonFungibleTokenWithCustomFee = "nonFungibleTokenWithCustomFee";
        final var nftXferTxn = "nftXferTxn";
        final var nftXferTxnWithCustomFee = "nftXferTxnWithCustomFee";

        return hapiTest(
                cryptoCreate(nonTreasurySender).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(SENDER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(RECEIVER),
                cryptoCreate(customFeeCollector),
                tokenCreate(fungibleToken)
                        .treasury(SENDER)
                        .tokenType(FUNGIBLE_COMMON)
                        .initialSupply(100L),
                tokenCreate(fungibleTokenWithCustomFee)
                        .treasury(SENDER)
                        .tokenType(FUNGIBLE_COMMON)
                        .withCustom(fixedHbarFee(transferAmount, customFeeCollector))
                        .initialSupply(100L),
                tokenAssociate(RECEIVER, fungibleToken, fungibleTokenWithCustomFee),
                newKeyNamed(SUPPLY_KEY),
                tokenCreate(nonFungibleToken)
                        .initialSupply(0)
                        .supplyKey(SUPPLY_KEY)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .treasury(SENDER),
                tokenCreate(nonFungibleTokenWithCustomFee)
                        .initialSupply(0)
                        .supplyKey(SUPPLY_KEY)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .withCustom(fixedHbarFee(transferAmount, customFeeCollector))
                        .treasury(SENDER),
                tokenAssociate(nonTreasurySender, List.of(fungibleTokenWithCustomFee, nonFungibleTokenWithCustomFee)),
                mintToken(nonFungibleToken, List.of(copyFromUtf8("memo1"))),
                mintToken(nonFungibleTokenWithCustomFee, List.of(copyFromUtf8("memo2"))),
                tokenAssociate(RECEIVER, nonFungibleToken, nonFungibleTokenWithCustomFee),
                cryptoTransfer(movingUnique(nonFungibleTokenWithCustomFee, 1).between(SENDER, nonTreasurySender))
                        .payingWith(SENDER),
                cryptoTransfer(moving(1, fungibleTokenWithCustomFee).between(SENDER, nonTreasurySender))
                        .payingWith(SENDER),
                cryptoTransfer(tinyBarsFromTo(SENDER, RECEIVER, 100L))
                        .payingWith(SENDER)
                        .blankMemo()
                        .via(hbarXferTxn),
                cryptoTransfer(moving(1, fungibleToken).between(SENDER, RECEIVER))
                        .blankMemo()
                        .payingWith(SENDER)
                        .via(htsXferTxn),
                cryptoTransfer(movingUnique(nonFungibleToken, 1).between(SENDER, RECEIVER))
                        .blankMemo()
                        .payingWith(SENDER)
                        .via(nftXferTxn),
                cryptoTransfer(moving(1, fungibleTokenWithCustomFee).between(nonTreasurySender, RECEIVER))
                        .blankMemo()
                        .fee(ONE_HBAR)
                        .payingWith(nonTreasurySender)
                        .via(htsXferTxnWithCustomFee),
                cryptoTransfer(movingUnique(nonFungibleTokenWithCustomFee, 1).between(nonTreasurySender, RECEIVER))
                        .blankMemo()
                        .fee(ONE_HBAR)
                        .payingWith(nonTreasurySender)
                        .via(nftXferTxnWithCustomFee),
                validateChargedUsdWithin(hbarXferTxn, BASE_FEE_HBAR_CRYPTO_TRANSFER, 0.01),
                validateChargedUsdWithin(htsXferTxn, BASE_FEE_HTS_CRYPTO_TRANSFER, 0.01),
                validateChargedUsdWithin(nftXferTxn, BASE_FEE_NFT_CRYPTO_TRANSFER, 0.01),
                validateChargedUsdWithin(htsXferTxnWithCustomFee, expectedHtsXferWithCustomFeePriceUsd, 0.1),
                validateChargedUsdWithin(nftXferTxnWithCustomFee, expectedNftXferWithCustomFeePriceUsd, 0.3));
    }

    @HapiTest
    @DisplayName("CryptoGetAccountRecords query has expected base fee")
    @Tag(MATS)
    final Stream<DynamicTest> cryptoCryptoGetAccountRecordsBaseUSDFee() {
        final var nonTreasurySender = "nonTreasurySender";

        return customizedHapiTest(
                Map.of("memo.useSpecName", "false"),
                cryptoCreate(nonTreasurySender).balance(ONE_HUNDRED_HBARS),
                cryptoCreate("GetAccountRecordsTest").key(nonTreasurySender).payingWith(nonTreasurySender),
                getAccountRecords("GetAccountRecordsTest")
                        .payingWith(nonTreasurySender)
                        .via("baseGetAccountRecord"),
                sleepFor(2000),
                validateChargedUsd("baseGetAccountRecord", BASE_FEE_CRYPTO_GET_ACCOUNT_RECORDS));
    }

    @HapiTest
    @DisplayName("CryptoGetAccountBalance query has expected base fee of zero")
    final Stream<DynamicTest> cryptoGetAccountBalanceBaseUSDFee() {
        final String getAccountBalanceTestFeesAccount = "GetAccountBalanceTestFeesAccount";
        return hapiTest(
                cryptoCreate(getAccountBalanceTestFeesAccount).balance(ONE_HBAR),
                getAccountBalance(getAccountBalanceTestFeesAccount)
                        .hasTinyBars(ONE_HBAR)
                        .signedBy(getAccountBalanceTestFeesAccount)
                        .payingWith(getAccountBalanceTestFeesAccount),
                getAccountBalance(getAccountBalanceTestFeesAccount).hasTinyBars(ONE_HBAR));
    }

    @HapiTest
    @DisplayName("CryptoGetAccountInfo query has expected base fee")
    final Stream<DynamicTest> cryptoGetAccountInfoBaseUSDFee() {
        return customizedHapiTest(
                Map.of("memo.useSpecName", "false"),
                getAccountInfo(CIVILIAN).via("basicGetInfo").payingWith(FEES_ACCOUNT),
                sleepFor(1000),
                validateChargedUsd("basicGetInfo", BASE_FEE_CRYPTO_GET_ACCOUNT_INFO));
    }
}
