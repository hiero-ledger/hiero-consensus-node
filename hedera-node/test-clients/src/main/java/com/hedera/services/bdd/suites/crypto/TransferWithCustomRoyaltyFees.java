// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.crypto;

import static com.hedera.node.app.hapi.utils.EthSigsUtils.recoverAddressFromPubKey;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.asEvmAddress;
import static com.hedera.services.bdd.junit.TestTags.CRYPTO;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AccountDetailsAsserts.accountDetailsWith;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.keys.TrieSigMapGenerator.uniqueWithFullPrefixesFor;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountDetails;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.asId;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.asTokenId;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoApproveAllowance;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAirdrop;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedHbarFee;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedHbarFeeInheritingRoyaltyCollector;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedHtsFee;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedHtsFeeInheritingRoyaltyCollector;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.royaltyFeeNoFallback;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.royaltyFeeWithFallback;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingHbar;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingUnique;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingUniqueWithAllowance;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingWithAllowance;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.createHip32Auto;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SHAPE;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SOURCE_KEY;
import static com.hedera.services.bdd.suites.HapiSuite.flattened;
import static com.hedera.services.bdd.suites.crypto.AutoAccountUpdateSuite.TRANSFER_TXN_2;
import static com.hedera.services.bdd.suites.crypto.AutoCreateUtils.createHollowAccountFrom;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_REPEATED_IN_ACCOUNT_AMOUNTS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_ACCOUNT_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_SENDER_ACCOUNT_BALANCE_FOR_CUSTOM_FEE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_TOKEN_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_AIRDROP_WITH_FALLBACK_ROYALTY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_NOT_ASSOCIATED_TO_ACCOUNT;
import static java.util.Collections.emptyList;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.ByteString;
import com.hedera.node.app.hapi.utils.ByteStringUtils;
import com.hedera.services.bdd.junit.HapiTest;
import com.hederahashgraph.api.proto.java.AccountAmount;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.NftTransfer;
import com.hederahashgraph.api.proto.java.TokenSupplyType;
import com.hederahashgraph.api.proto.java.TokenTransferList;
import com.hederahashgraph.api.proto.java.TokenType;
import com.hederahashgraph.api.proto.java.TransferList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(CRYPTO)
public class TransferWithCustomRoyaltyFees {
    private static final long numerator = 1L;
    private static final long denominator = 10L;
    private static final String nonFungibleToken = "nonFungibleWithCustomFees";
    private static final String feeDenom = "denom";
    private static final String feeDenom2 = "denom2";
    private static final String feeDenom3 = "denom3";
    private static final String hbarCollector = "hbarFee";
    private static final String htsCollector = "denomFee";
    private static final String tokenReceiver = "receiver";
    private static final String tokenTreasury = "tokenTreasury";
    private static final String spender = "spender";
    private static final String NFT_KEY = "nftKey";
    private static final String tokenOwner = "tokenOwner";
    private static final String alice = "alice";
    private static final String bob = "bob";
    private static final String carol = "carol";
    private static final String dave = "dave";

    @HapiTest
    final Stream<DynamicTest> transferNonFungibleWithRoyaltyHbarFee() {
        final var fourHundredHbars = 4 * ONE_HUNDRED_HBARS;
        final var twoHundredHbars = 2 * ONE_HUNDRED_HBARS;
        return hapiTest(
                newKeyNamed(NFT_KEY),
                cryptoCreate(hbarCollector).balance(0L),
                cryptoCreate(tokenReceiver).balance(fourHundredHbars),
                cryptoCreate(tokenTreasury),
                cryptoCreate(tokenOwner).balance(0L),
                tokenCreate(nonFungibleToken)
                        .treasury(tokenTreasury)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0)
                        .supplyKey(NFT_KEY)
                        .supplyType(TokenSupplyType.INFINITE)
                        .withCustom(royaltyFeeNoFallback(1, 2, hbarCollector)),
                tokenAssociate(tokenReceiver, nonFungibleToken),
                tokenAssociate(tokenOwner, nonFungibleToken),
                mintToken(nonFungibleToken, List.of(ByteStringUtils.wrapUnsafely("meta1".getBytes()))),
                cryptoTransfer(movingUnique(nonFungibleToken, 1L).between(tokenTreasury, tokenOwner)),
                cryptoTransfer(
                        movingUnique(nonFungibleToken, 1L).between(tokenOwner, tokenReceiver),
                        movingHbar(fourHundredHbars).between(tokenReceiver, tokenOwner)),
                getAccountBalance(tokenOwner).hasTinyBars(twoHundredHbars).hasTokenBalance(nonFungibleToken, 0),
                getAccountBalance(tokenReceiver).hasTinyBars(0).hasTokenBalance(nonFungibleToken, 1),
                getAccountBalance(hbarCollector).hasTinyBars(twoHundredHbars));
    }

    @HapiTest
    final Stream<DynamicTest> transferNonFungibleWithRoyaltyFungibleFee() {
        return hapiTest(
                newKeyNamed(NFT_KEY),
                cryptoCreate(htsCollector),
                cryptoCreate(tokenReceiver).balance(ONE_MILLION_HBARS),
                cryptoCreate(tokenTreasury),
                cryptoCreate(tokenOwner),
                tokenCreate(feeDenom).treasury(tokenReceiver).initialSupply(4),
                tokenAssociate(tokenOwner, feeDenom),
                tokenAssociate(htsCollector, feeDenom),
                tokenCreate(nonFungibleToken)
                        .treasury(tokenTreasury)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0)
                        .supplyKey(NFT_KEY)
                        .supplyType(TokenSupplyType.INFINITE)
                        .withCustom(royaltyFeeNoFallback(1, 2, htsCollector)),
                tokenAssociate(tokenReceiver, nonFungibleToken),
                tokenAssociate(tokenOwner, nonFungibleToken),
                mintToken(nonFungibleToken, List.of(ByteStringUtils.wrapUnsafely("meta1".getBytes()))),
                cryptoTransfer(movingUnique(nonFungibleToken, 1L).between(tokenTreasury, tokenOwner)),
                cryptoTransfer(
                        movingUnique(nonFungibleToken, 1L).between(tokenOwner, tokenReceiver),
                        moving(4, feeDenom).between(tokenReceiver, tokenOwner)),
                getAccountBalance(tokenOwner)
                        .hasTokenBalance(nonFungibleToken, 0)
                        .hasTokenBalance(feeDenom, 2),
                getAccountBalance(tokenReceiver)
                        .hasTokenBalance(nonFungibleToken, 1)
                        .hasTokenBalance(feeDenom, 0),
                getAccountBalance(htsCollector).hasTokenBalance(feeDenom, 2));
    }

    @HapiTest
    final Stream<DynamicTest> transferNonFungibleWithMultipleRoyaltyFungibleFee() {
        return hapiTest(
                newKeyNamed(NFT_KEY),
                cryptoCreate(htsCollector),
                cryptoCreate(alice),
                cryptoCreate(tokenReceiver).balance(ONE_MILLION_HBARS),
                cryptoCreate(tokenTreasury),
                cryptoCreate(tokenOwner),
                tokenCreate(feeDenom).treasury(tokenReceiver).initialSupply(10),
                tokenAssociate(tokenOwner, feeDenom),
                tokenAssociate(htsCollector, feeDenom),
                tokenAssociate(alice, feeDenom),
                tokenCreate(nonFungibleToken)
                        .treasury(tokenTreasury)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0)
                        .supplyKey(NFT_KEY)
                        .supplyType(TokenSupplyType.INFINITE)
                        .withCustom(royaltyFeeNoFallback(6, 10, htsCollector))
                        .withCustom(royaltyFeeNoFallback(7, 10, alice)),
                tokenAssociate(tokenReceiver, nonFungibleToken),
                tokenAssociate(tokenOwner, nonFungibleToken),
                mintToken(nonFungibleToken, List.of(ByteStringUtils.wrapUnsafely("meta1".getBytes()))),
                cryptoTransfer(movingUnique(nonFungibleToken, 1L).between(tokenTreasury, tokenOwner)),
                cryptoTransfer(
                        movingUnique(nonFungibleToken, 1L).between(tokenOwner, tokenReceiver),
                        moving(4, feeDenom).between(tokenReceiver, tokenOwner)),
                getAccountBalance(tokenOwner)
                        .hasTokenBalance(nonFungibleToken, 0)
                        .hasTokenBalance(feeDenom, 0),
                getAccountBalance(tokenReceiver)
                        .hasTokenBalance(nonFungibleToken, 1)
                        .hasTokenBalance(feeDenom, 6),
                getAccountBalance(htsCollector).hasTokenBalance(feeDenom, 2),
                getAccountBalance(alice).hasTokenBalance(feeDenom, 2));
    }

    @HapiTest
    final Stream<DynamicTest> transferNonFungibleWithMultipleRoyaltyFungibleFeeToFeeCollector() {
        return hapiTest(
                newKeyNamed(NFT_KEY),
                cryptoCreate(htsCollector),
                cryptoCreate(alice),
                cryptoCreate(tokenReceiver).balance(ONE_MILLION_HBARS),
                cryptoCreate(tokenTreasury),
                cryptoCreate(tokenOwner),
                tokenCreate(feeDenom).treasury(alice).initialSupply(10),
                tokenAssociate(tokenOwner, feeDenom),
                tokenAssociate(htsCollector, feeDenom),
                tokenCreate(nonFungibleToken)
                        .treasury(tokenTreasury)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0)
                        .supplyKey(NFT_KEY)
                        .supplyType(TokenSupplyType.INFINITE)
                        .withCustom(royaltyFeeNoFallback(6, 10, htsCollector))
                        .withCustom(royaltyFeeNoFallback(7, 10, alice)),
                tokenAssociate(tokenReceiver, nonFungibleToken),
                tokenAssociate(tokenOwner, nonFungibleToken),
                tokenAssociate(alice, nonFungibleToken),
                mintToken(nonFungibleToken, List.of(ByteStringUtils.wrapUnsafely("meta1".getBytes()))),
                cryptoTransfer(movingUnique(nonFungibleToken, 1L).between(tokenTreasury, tokenOwner)),
                cryptoTransfer(
                        movingUnique(nonFungibleToken, 1L).between(tokenOwner, alice),
                        moving(4, feeDenom).between(alice, tokenOwner)),
                getAccountBalance(tokenOwner)
                        .hasTokenBalance(nonFungibleToken, 0)
                        .hasTokenBalance(feeDenom, 0),
                getAccountBalance(alice).hasTokenBalance(nonFungibleToken, 1).hasTokenBalance(feeDenom, 8),
                getAccountBalance(htsCollector).hasTokenBalance(feeDenom, 2));
    }

    @HapiTest
    final Stream<DynamicTest> transferNonFungibleWithMultipleRoyaltyFungibleFeeNegative() {
        return hapiTest(
                newKeyNamed(NFT_KEY),
                cryptoCreate(htsCollector),
                cryptoCreate(alice),
                cryptoCreate(tokenReceiver).balance(ONE_MILLION_HBARS),
                cryptoCreate(tokenTreasury),
                cryptoCreate(tokenOwner),
                tokenCreate(feeDenom).treasury(tokenReceiver).initialSupply(10),
                tokenAssociate(tokenOwner, feeDenom),
                tokenAssociate(htsCollector, feeDenom),
                tokenAssociate(alice, feeDenom),
                tokenCreate(nonFungibleToken)
                        .treasury(tokenTreasury)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0)
                        .supplyKey(NFT_KEY)
                        .supplyType(TokenSupplyType.INFINITE)
                        .withCustom(royaltyFeeNoFallback(6, 10, htsCollector))
                        .withCustom(royaltyFeeNoFallback(9, 10, alice)),
                tokenAssociate(tokenReceiver, nonFungibleToken),
                tokenAssociate(tokenOwner, nonFungibleToken),
                mintToken(nonFungibleToken, List.of(ByteStringUtils.wrapUnsafely("meta1".getBytes()))),
                cryptoTransfer(movingUnique(nonFungibleToken, 1L).between(tokenTreasury, tokenOwner)),
                cryptoTransfer(
                                movingUnique(nonFungibleToken, 1L).between(tokenOwner, tokenReceiver),
                                moving(4, feeDenom).between(tokenReceiver, tokenOwner))
                        .hasKnownStatus(INSUFFICIENT_SENDER_ACCOUNT_BALANCE_FOR_CUSTOM_FEE),
                getAccountBalance(tokenOwner)
                        .hasTokenBalance(nonFungibleToken, 1)
                        .hasTokenBalance(feeDenom, 0),
                getAccountBalance(tokenReceiver)
                        .hasTokenBalance(nonFungibleToken, 0)
                        .hasTokenBalance(feeDenom, 10),
                getAccountBalance(htsCollector).hasTokenBalance(feeDenom, 0),
                getAccountBalance(alice).hasTokenBalance(feeDenom, 0));
    }

    @HapiTest
    final Stream<DynamicTest> transferNonFungibleWithRoyaltyFallbackHbarFee() {
        return hapiTest(
                newKeyNamed(NFT_KEY),
                cryptoCreate(hbarCollector).balance(0L),
                cryptoCreate(tokenReceiver).balance(ONE_MILLION_HBARS),
                cryptoCreate(tokenTreasury),
                cryptoCreate(tokenOwner),
                tokenCreate(nonFungibleToken)
                        .treasury(tokenTreasury)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0)
                        .supplyKey(NFT_KEY)
                        .supplyType(TokenSupplyType.INFINITE)
                        .withCustom(royaltyFeeWithFallback(
                                1, 2, fixedHbarFeeInheritingRoyaltyCollector(100), hbarCollector)),
                tokenAssociate(tokenReceiver, nonFungibleToken),
                tokenAssociate(tokenOwner, nonFungibleToken),
                mintToken(nonFungibleToken, List.of(ByteStringUtils.wrapUnsafely("meta1".getBytes()))),
                cryptoTransfer(movingUnique(nonFungibleToken, 1L).between(tokenTreasury, tokenOwner)),
                cryptoTransfer(movingUnique(nonFungibleToken, 1L).between(tokenOwner, tokenReceiver))
                        .signedByPayerAnd(tokenReceiver, tokenOwner),
                getAccountBalance(tokenOwner).hasTokenBalance(nonFungibleToken, 0),
                getAccountBalance(tokenReceiver)
                        .hasTokenBalance(nonFungibleToken, 1)
                        .hasTinyBars(ONE_MILLION_HBARS - 100),
                getAccountBalance(hbarCollector).hasTinyBars(100));
    }

    @HapiTest
    final Stream<DynamicTest> transferNonFungibleWithRoyaltyFallbackFungibleFee() {
        return hapiTest(
                newKeyNamed(NFT_KEY),
                cryptoCreate(htsCollector),
                cryptoCreate(tokenReceiver).balance(ONE_MILLION_HBARS),
                cryptoCreate(tokenTreasury),
                cryptoCreate(tokenOwner),
                tokenCreate(feeDenom).treasury(tokenReceiver).initialSupply(5),
                tokenAssociate(tokenOwner, feeDenom),
                tokenAssociate(htsCollector, feeDenom),
                tokenCreate(nonFungibleToken)
                        .treasury(tokenTreasury)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0)
                        .supplyKey(NFT_KEY)
                        .supplyType(TokenSupplyType.INFINITE)
                        .withCustom(royaltyFeeWithFallback(
                                1, 2, fixedHtsFeeInheritingRoyaltyCollector(5, feeDenom), htsCollector)),
                tokenAssociate(tokenReceiver, nonFungibleToken),
                tokenAssociate(tokenOwner, nonFungibleToken),
                mintToken(nonFungibleToken, List.of(ByteStringUtils.wrapUnsafely("meta1".getBytes()))),
                cryptoTransfer(movingUnique(nonFungibleToken, 1L).between(tokenTreasury, tokenOwner)),
                cryptoTransfer(movingUnique(nonFungibleToken, 1L).between(tokenOwner, tokenReceiver))
                        .signedByPayerAnd(tokenReceiver, tokenOwner),
                getAccountBalance(tokenOwner).hasTokenBalance(nonFungibleToken, 0),
                getAccountBalance(tokenReceiver)
                        .hasTokenBalance(nonFungibleToken, 1)
                        .hasTokenBalance(feeDenom, 0),
                getAccountBalance(htsCollector).hasTokenBalance(feeDenom, 5));
    }

    @HapiTest
    final Stream<DynamicTest> transferNonFungibleWithRoyaltyHbarFeeInsufficientBalance() {
        final var fourHundredHbars = 4 * ONE_HUNDRED_HBARS;
        return hapiTest(
                newKeyNamed(NFT_KEY),
                cryptoCreate(hbarCollector).balance(0L),
                cryptoCreate(tokenReceiver).balance(0L),
                cryptoCreate(tokenTreasury),
                cryptoCreate(tokenOwner).balance(0L),
                tokenCreate(nonFungibleToken)
                        .treasury(tokenTreasury)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0)
                        .supplyKey(NFT_KEY)
                        .supplyType(TokenSupplyType.INFINITE)
                        .withCustom(royaltyFeeNoFallback(1, 2, hbarCollector)),
                tokenAssociate(tokenReceiver, nonFungibleToken),
                tokenAssociate(tokenOwner, nonFungibleToken),
                mintToken(nonFungibleToken, List.of(ByteStringUtils.wrapUnsafely("meta1".getBytes()))),
                cryptoTransfer(movingUnique(nonFungibleToken, 1L).between(tokenTreasury, tokenOwner)),
                cryptoTransfer(
                                movingUnique(nonFungibleToken, 1L).between(tokenOwner, tokenReceiver),
                                movingHbar(fourHundredHbars).between(tokenReceiver, tokenOwner))
                        .hasKnownStatus(INSUFFICIENT_ACCOUNT_BALANCE),
                getAccountBalance(hbarCollector).hasTinyBars(0));
    }

    @HapiTest
    final Stream<DynamicTest> transferNonFungibleWithRoyaltyFungibleFeeInsufficientBalance() {
        return hapiTest(
                newKeyNamed(NFT_KEY),
                cryptoCreate(htsCollector),
                cryptoCreate(tokenReceiver).balance(ONE_MILLION_HBARS),
                cryptoCreate(tokenTreasury),
                cryptoCreate(tokenOwner),
                tokenCreate(feeDenom).treasury(tokenReceiver).initialSupply(0),
                tokenAssociate(tokenOwner, feeDenom),
                tokenAssociate(htsCollector, feeDenom),
                tokenCreate(nonFungibleToken)
                        .treasury(tokenTreasury)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0)
                        .supplyKey(NFT_KEY)
                        .supplyType(TokenSupplyType.INFINITE)
                        .withCustom(royaltyFeeNoFallback(1, 2, htsCollector)),
                tokenAssociate(tokenReceiver, nonFungibleToken),
                tokenAssociate(tokenOwner, nonFungibleToken),
                mintToken(nonFungibleToken, List.of(ByteStringUtils.wrapUnsafely("meta1".getBytes()))),
                cryptoTransfer(movingUnique(nonFungibleToken, 1L).between(tokenTreasury, tokenOwner)),
                cryptoTransfer(
                                movingUnique(nonFungibleToken, 1L).between(tokenOwner, tokenReceiver),
                                moving(4, feeDenom).between(tokenReceiver, tokenOwner))
                        .hasKnownStatus(INSUFFICIENT_TOKEN_BALANCE),
                getAccountBalance(htsCollector).hasTokenBalance(feeDenom, 0));
    }

    @HapiTest
    final Stream<DynamicTest> transferNonFungibleWithRoyaltyFallbackHbarFeeInsufficientBalance() {
        return hapiTest(
                newKeyNamed(NFT_KEY),
                cryptoCreate(hbarCollector).balance(0L),
                cryptoCreate(tokenReceiver).balance(0L),
                cryptoCreate(tokenTreasury),
                cryptoCreate(tokenOwner),
                tokenCreate(nonFungibleToken)
                        .treasury(tokenTreasury)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0)
                        .supplyKey(NFT_KEY)
                        .supplyType(TokenSupplyType.INFINITE)
                        .withCustom(royaltyFeeWithFallback(
                                1, 2, fixedHbarFeeInheritingRoyaltyCollector(100), hbarCollector)),
                tokenAssociate(tokenReceiver, nonFungibleToken),
                tokenAssociate(tokenOwner, nonFungibleToken),
                mintToken(nonFungibleToken, List.of(ByteStringUtils.wrapUnsafely("meta1".getBytes()))),
                cryptoTransfer(movingUnique(nonFungibleToken, 1L).between(tokenTreasury, tokenOwner)),
                cryptoTransfer(movingUnique(nonFungibleToken, 1L).between(tokenOwner, tokenReceiver))
                        .signedByPayerAnd(tokenReceiver, tokenOwner)
                        .hasKnownStatus(INSUFFICIENT_SENDER_ACCOUNT_BALANCE_FOR_CUSTOM_FEE),
                getAccountBalance(hbarCollector).hasTinyBars(0));
    }

    @HapiTest
    final Stream<DynamicTest> transferNonFungibleWithRoyaltyFallbackFungibleFeeInsufficientBalance() {
        return hapiTest(
                newKeyNamed(NFT_KEY),
                cryptoCreate(htsCollector),
                cryptoCreate(tokenReceiver).balance(ONE_MILLION_HBARS),
                cryptoCreate(tokenTreasury),
                cryptoCreate(tokenOwner),
                tokenCreate(feeDenom).treasury(tokenReceiver).initialSupply(0),
                tokenAssociate(tokenOwner, feeDenom),
                tokenAssociate(htsCollector, feeDenom),
                tokenCreate(nonFungibleToken)
                        .treasury(tokenTreasury)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0)
                        .supplyKey(NFT_KEY)
                        .supplyType(TokenSupplyType.INFINITE)
                        .withCustom(royaltyFeeWithFallback(
                                1, 2, fixedHtsFeeInheritingRoyaltyCollector(5, feeDenom), htsCollector)),
                tokenAssociate(tokenReceiver, nonFungibleToken),
                tokenAssociate(tokenOwner, nonFungibleToken),
                mintToken(nonFungibleToken, List.of(ByteStringUtils.wrapUnsafely("meta1".getBytes()))),
                cryptoTransfer(movingUnique(nonFungibleToken, 1L).between(tokenTreasury, tokenOwner)),
                cryptoTransfer(movingUnique(nonFungibleToken, 1L).between(tokenOwner, tokenReceiver))
                        .signedByPayerAnd(tokenReceiver, tokenOwner)
                        .hasKnownStatus(INSUFFICIENT_SENDER_ACCOUNT_BALANCE_FOR_CUSTOM_FEE),
                getAccountBalance(htsCollector).hasTokenBalance(feeDenom, 0));
    }

    @HapiTest
    final Stream<DynamicTest> transferNonFungibleWithRoyaltyFallbackFungibleFeeNoAssociation() {
        return hapiTest(
                newKeyNamed(NFT_KEY),
                cryptoCreate(htsCollector),
                cryptoCreate(tokenReceiver).balance(ONE_MILLION_HBARS),
                cryptoCreate(tokenTreasury),
                cryptoCreate(tokenOwner),
                tokenCreate(feeDenom).treasury(tokenTreasury).initialSupply(0),
                tokenAssociate(htsCollector, feeDenom),
                tokenCreate(nonFungibleToken)
                        .treasury(tokenTreasury)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0)
                        .supplyKey(NFT_KEY)
                        .supplyType(TokenSupplyType.INFINITE)
                        .withCustom(royaltyFeeWithFallback(
                                1, 2, fixedHtsFeeInheritingRoyaltyCollector(5, feeDenom), htsCollector)),
                tokenAssociate(tokenReceiver, nonFungibleToken),
                tokenAssociate(tokenOwner, nonFungibleToken),
                mintToken(nonFungibleToken, List.of(ByteStringUtils.wrapUnsafely("meta1".getBytes()))),
                cryptoTransfer(movingUnique(nonFungibleToken, 1L).between(tokenTreasury, tokenOwner)),
                cryptoTransfer(movingUnique(nonFungibleToken, 1L).between(tokenOwner, tokenReceiver))
                        .signedByPayerAnd(tokenReceiver, tokenOwner)
                        .hasKnownStatus(TOKEN_NOT_ASSOCIATED_TO_ACCOUNT),
                getAccountBalance(htsCollector).hasTokenBalance(feeDenom, 0));
    }

    @HapiTest
    final Stream<DynamicTest> transferNonFungibleWithRoyaltyFungibleFeeNoAssociation() {
        return hapiTest(
                newKeyNamed(NFT_KEY),
                cryptoCreate(htsCollector),
                cryptoCreate(tokenReceiver).balance(ONE_MILLION_HBARS),
                cryptoCreate(tokenTreasury),
                cryptoCreate(tokenOwner),
                tokenCreate(feeDenom).treasury(tokenTreasury).initialSupply(0),
                tokenAssociate(htsCollector, feeDenom),
                tokenCreate(nonFungibleToken)
                        .treasury(tokenTreasury)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0)
                        .supplyKey(NFT_KEY)
                        .supplyType(TokenSupplyType.INFINITE)
                        .withCustom(royaltyFeeNoFallback(1, 2, htsCollector)),
                tokenAssociate(tokenReceiver, nonFungibleToken),
                tokenAssociate(tokenOwner, nonFungibleToken),
                mintToken(nonFungibleToken, List.of(ByteStringUtils.wrapUnsafely("meta1".getBytes()))),
                cryptoTransfer(movingUnique(nonFungibleToken, 1L).between(tokenTreasury, tokenOwner)),
                cryptoTransfer(
                                movingUnique(nonFungibleToken, 1L).between(tokenOwner, tokenReceiver),
                                moving(4, feeDenom).between(tokenReceiver, tokenOwner))
                        .hasKnownStatus(TOKEN_NOT_ASSOCIATED_TO_ACCOUNT),
                getAccountBalance(htsCollector).hasTokenBalance(feeDenom, 0));
    }

    @HapiTest
    final Stream<DynamicTest> transferNonFungibleWithRoyaltyZeroTransaction() {
        return hapiTest(
                newKeyNamed(NFT_KEY),
                cryptoCreate(htsCollector).balance(0L),
                cryptoCreate(tokenReceiver).balance(ONE_MILLION_HBARS),
                cryptoCreate(tokenTreasury),
                cryptoCreate(tokenOwner).balance(0L),
                tokenCreate(feeDenom).treasury(tokenReceiver).initialSupply(4),
                tokenAssociate(tokenOwner, feeDenom),
                tokenAssociate(htsCollector, feeDenom),
                tokenCreate(nonFungibleToken)
                        .treasury(tokenTreasury)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0)
                        .supplyKey(NFT_KEY)
                        .supplyType(TokenSupplyType.INFINITE)
                        .withCustom(royaltyFeeWithFallback(
                                numerator,
                                denominator,
                                fixedHtsFeeInheritingRoyaltyCollector(4, feeDenom),
                                htsCollector)),
                tokenAssociate(tokenReceiver, nonFungibleToken),
                tokenAssociate(tokenOwner, nonFungibleToken),
                mintToken(nonFungibleToken, List.of(ByteStringUtils.wrapUnsafely("meta1".getBytes()))),
                cryptoTransfer(movingUnique(nonFungibleToken, 1L).between(tokenTreasury, tokenOwner)),
                cryptoTransfer(
                                movingUnique(nonFungibleToken, 1L).between(tokenOwner, tokenReceiver),
                                moving(0, feeDenom).between(tokenReceiver, tokenOwner))
                        .signedByPayerAnd(tokenReceiver, tokenOwner),
                getAccountBalance(tokenOwner).hasTokenBalance(nonFungibleToken, 0),
                getAccountBalance(tokenReceiver).hasTokenBalance(nonFungibleToken, 1),
                getAccountBalance(htsCollector).hasTokenBalance(feeDenom, 4));
    }

    @HapiTest
    final Stream<DynamicTest> transferNonFungibleWithRoyaltyHtsFee2Transactions() {
        return hapiTest(
                newKeyNamed(NFT_KEY),
                cryptoCreate(htsCollector).balance(0L),
                cryptoCreate(tokenReceiver).balance(ONE_MILLION_HBARS),
                cryptoCreate(tokenTreasury),
                cryptoCreate(tokenOwner).balance(0L),
                tokenCreate(feeDenom).treasury(tokenReceiver).initialSupply(4),
                tokenAssociate(tokenOwner, feeDenom),
                tokenAssociate(htsCollector, feeDenom),
                tokenCreate(nonFungibleToken)
                        .treasury(tokenTreasury)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0)
                        .supplyKey(NFT_KEY)
                        .supplyType(TokenSupplyType.INFINITE)
                        .withCustom(royaltyFeeNoFallback(1, 2, htsCollector)),
                tokenAssociate(tokenReceiver, nonFungibleToken),
                tokenAssociate(tokenOwner, nonFungibleToken),
                mintToken(nonFungibleToken, List.of(ByteStringUtils.wrapUnsafely("meta1".getBytes()))),
                cryptoTransfer(movingUnique(nonFungibleToken, 1L).between(tokenTreasury, tokenOwner)),
                cryptoTransfer(
                        movingUnique(nonFungibleToken, 1L).between(tokenOwner, tokenReceiver),
                        moving(0, feeDenom).between(tokenReceiver, tokenOwner),
                        moving(4, feeDenom).between(tokenReceiver, tokenOwner)),
                getAccountBalance(tokenOwner).hasTokenBalance(nonFungibleToken, 0),
                getAccountBalance(tokenReceiver).hasTokenBalance(nonFungibleToken, 1),
                getAccountBalance(htsCollector).hasTokenBalance(feeDenom, 2));
    }

    @HapiTest
    final Stream<DynamicTest> transferNonFungibleWithRoyaltyHtsFee2TokenFees() {
        return hapiTest(
                newKeyNamed(NFT_KEY),
                cryptoCreate(htsCollector).balance(0L),
                cryptoCreate(tokenReceiver).balance(ONE_MILLION_HBARS),
                cryptoCreate(tokenTreasury),
                cryptoCreate(tokenOwner).balance(0L),
                tokenCreate(feeDenom).treasury(tokenReceiver).initialSupply(4),
                tokenCreate(feeDenom2).treasury(tokenReceiver).initialSupply(4),
                tokenAssociate(tokenOwner, feeDenom),
                tokenAssociate(tokenOwner, feeDenom2),
                tokenAssociate(htsCollector, feeDenom),
                tokenAssociate(htsCollector, feeDenom2),
                tokenCreate(nonFungibleToken)
                        .treasury(tokenTreasury)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0)
                        .supplyKey(NFT_KEY)
                        .supplyType(TokenSupplyType.INFINITE)
                        .withCustom(royaltyFeeNoFallback(1, 2, htsCollector)),
                tokenAssociate(tokenReceiver, nonFungibleToken),
                tokenAssociate(tokenOwner, nonFungibleToken),
                mintToken(nonFungibleToken, List.of(ByteStringUtils.wrapUnsafely("meta1".getBytes()))),
                cryptoTransfer(movingUnique(nonFungibleToken, 1L).between(tokenTreasury, tokenOwner)),
                cryptoTransfer(
                        movingUnique(nonFungibleToken, 1L).between(tokenOwner, tokenReceiver),
                        moving(4, feeDenom).between(tokenReceiver, tokenOwner),
                        moving(4, feeDenom2).between(tokenReceiver, tokenOwner)),
                getAccountBalance(tokenOwner).hasTokenBalance(nonFungibleToken, 0),
                getAccountBalance(tokenReceiver).hasTokenBalance(nonFungibleToken, 1),
                getAccountBalance(htsCollector).hasTokenBalance(feeDenom, 2),
                getAccountBalance(htsCollector).hasTokenBalance(feeDenom2, 2));
    }

    @HapiTest
    final Stream<DynamicTest> transferNonFungibleWithRoyaltyHtsFeeMinFee() {
        return hapiTest(
                newKeyNamed(NFT_KEY),
                cryptoCreate(htsCollector).balance(0L),
                cryptoCreate(tokenReceiver).balance(ONE_MILLION_HBARS),
                cryptoCreate(tokenTreasury),
                cryptoCreate(tokenOwner).balance(0L),
                tokenCreate(feeDenom).treasury(tokenReceiver).initialSupply(4),
                tokenCreate(feeDenom2).treasury(tokenReceiver).initialSupply(4),
                tokenAssociate(tokenOwner, feeDenom),
                tokenAssociate(htsCollector, feeDenom),
                tokenAssociate(tokenOwner, feeDenom2),
                tokenAssociate(htsCollector, feeDenom2),
                tokenCreate(nonFungibleToken)
                        .treasury(tokenTreasury)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0)
                        .supplyKey(NFT_KEY)
                        .supplyType(TokenSupplyType.INFINITE)
                        .withCustom(royaltyFeeWithFallback(
                                1, Long.MAX_VALUE, fixedHtsFeeInheritingRoyaltyCollector(4, feeDenom2), htsCollector)),
                tokenAssociate(tokenReceiver, nonFungibleToken),
                tokenAssociate(tokenOwner, nonFungibleToken),
                mintToken(nonFungibleToken, List.of(ByteStringUtils.wrapUnsafely("meta1".getBytes()))),
                cryptoTransfer(movingUnique(nonFungibleToken, 1L).between(tokenTreasury, tokenOwner)),
                cryptoTransfer(
                        movingUnique(nonFungibleToken, 1L).between(tokenOwner, tokenReceiver),
                        moving(1, feeDenom).between(tokenReceiver, tokenOwner)),
                getAccountBalance(tokenOwner).hasTokenBalance(nonFungibleToken, 0),
                getAccountBalance(tokenReceiver).hasTokenBalance(nonFungibleToken, 1),
                getAccountBalance(tokenReceiver).hasTokenBalance(feeDenom, 3),
                getAccountBalance(tokenReceiver).hasTokenBalance(feeDenom2, 4),
                getAccountBalance(htsCollector).hasTokenBalance(feeDenom, 0),
                getAccountBalance(htsCollector).hasTokenBalance(feeDenom2, 0));
    }

    @HapiTest
    final Stream<DynamicTest> transferNonFungibleWithRoyaltyFallbackAllowanceNegative() {
        return hapiTest(
                newKeyNamed(NFT_KEY),
                cryptoCreate(htsCollector).balance(0L),
                cryptoCreate(tokenReceiver).balance(ONE_MILLION_HBARS),
                cryptoCreate(tokenTreasury),
                cryptoCreate(tokenOwner).balance(ONE_MILLION_HBARS),
                tokenCreate(feeDenom).treasury(tokenTreasury).initialSupply(10),
                tokenAssociate(tokenOwner, feeDenom),
                tokenAssociate(htsCollector, feeDenom),
                tokenAssociate(tokenReceiver, feeDenom),
                cryptoTransfer(moving(10, feeDenom).between(tokenTreasury, tokenOwner)),
                tokenCreate(nonFungibleToken)
                        .treasury(tokenTreasury)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0)
                        .supplyKey(NFT_KEY)
                        .supplyType(TokenSupplyType.INFINITE)
                        .withCustom(royaltyFeeWithFallback(
                                1L, 2L, fixedHtsFeeInheritingRoyaltyCollector(8, feeDenom), htsCollector)),
                tokenAssociate(tokenReceiver, nonFungibleToken),
                tokenAssociate(tokenOwner, nonFungibleToken),
                mintToken(nonFungibleToken, List.of(ByteStringUtils.wrapUnsafely("meta1".getBytes()))),
                cryptoTransfer(movingUnique(nonFungibleToken, 1L).between(tokenTreasury, tokenOwner)),
                cryptoApproveAllowance()
                        .addTokenAllowance(tokenOwner, feeDenom, tokenReceiver, 10L)
                        .fee(ONE_HUNDRED_HBARS)
                        .payingWith(tokenOwner),
                getAccountDetails(tokenOwner)
                        .has(accountDetailsWith().tokenAllowancesContaining(feeDenom, tokenReceiver, 10L)),
                cryptoTransfer(movingUnique(nonFungibleToken, 1L).between(tokenOwner, tokenReceiver))
                        .signedByPayerAnd(tokenOwner, tokenReceiver)
                        .hasKnownStatus(INSUFFICIENT_SENDER_ACCOUNT_BALANCE_FOR_CUSTOM_FEE),
                getAccountBalance(tokenOwner).hasTokenBalance(nonFungibleToken, 1),
                getAccountBalance(tokenReceiver).hasTokenBalance(nonFungibleToken, 0),
                getAccountDetails(tokenOwner)
                        .has(accountDetailsWith().tokenAllowancesContaining(feeDenom, tokenReceiver, 10L)),
                getAccountBalance(htsCollector).hasTokenBalance(feeDenom, 0));
    }

    @HapiTest
    final Stream<DynamicTest> transferNonFungibleWithRoyaltyAllowancePassFromSpenderToOwner() {
        return hapiTest(
                newKeyNamed(NFT_KEY),
                cryptoCreate(htsCollector).balance(0L),
                cryptoCreate(tokenReceiver).balance(ONE_MILLION_HBARS),
                cryptoCreate(tokenTreasury),
                cryptoCreate(tokenOwner).balance(ONE_MILLION_HBARS),
                tokenCreate(feeDenom).treasury(tokenTreasury).initialSupply(20),
                tokenAssociate(tokenOwner, feeDenom),
                tokenAssociate(tokenReceiver, feeDenom),
                tokenAssociate(htsCollector, feeDenom),
                cryptoTransfer(moving(20, feeDenom).between(tokenTreasury, tokenOwner)),
                tokenCreate(nonFungibleToken)
                        .treasury(tokenTreasury)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0)
                        .supplyKey(NFT_KEY)
                        .supplyType(TokenSupplyType.INFINITE)
                        .withCustom(royaltyFeeNoFallback(1L, 5L, htsCollector)),
                tokenAssociate(tokenReceiver, nonFungibleToken),
                tokenAssociate(tokenOwner, nonFungibleToken),
                mintToken(nonFungibleToken, List.of(ByteStringUtils.wrapUnsafely("meta1".getBytes()))),
                cryptoTransfer(movingUnique(nonFungibleToken, 1L).between(tokenTreasury, tokenOwner)),
                cryptoApproveAllowance()
                        .addTokenAllowance(tokenOwner, feeDenom, tokenReceiver, 10L)
                        .fee(ONE_HUNDRED_HBARS)
                        .payingWith(tokenOwner),
                getAccountDetails(tokenOwner)
                        .has(accountDetailsWith().tokenAllowancesContaining(feeDenom, tokenReceiver, 10L)),
                cryptoTransfer(
                                movingUnique(nonFungibleToken, 1L).between(tokenOwner, tokenReceiver),
                                movingWithAllowance(10L, feeDenom).between(tokenOwner, tokenOwner))
                        .fee(ONE_HUNDRED_HBARS)
                        .via("hbarFixedFee")
                        .payingWith(tokenReceiver)
                        .signedBy(tokenReceiver, tokenOwner),
                getAccountBalance(tokenOwner).hasTokenBalance(nonFungibleToken, 0),
                getAccountBalance(tokenReceiver).hasTokenBalance(nonFungibleToken, 1),
                getAccountBalance(tokenReceiver).hasTokenBalance(feeDenom, 0),
                getAccountBalance(htsCollector).hasTokenBalance(feeDenom, 0));
    }

    @HapiTest
    final Stream<DynamicTest> transferNonFungibleWithRoyaltyRandomFeeSameCollectorAccount() {
        return hapiTest(
                newKeyNamed(NFT_KEY),
                cryptoCreate(carol).balance(ONE_MILLION_HBARS),
                cryptoCreate(htsCollector).balance(ONE_MILLION_HBARS),
                cryptoCreate(tokenReceiver).balance(ONE_MILLION_HBARS),
                cryptoCreate(tokenTreasury).balance(ONE_MILLION_HBARS),
                tokenCreate(feeDenom).treasury(tokenTreasury).initialSupply(10).withCustom(fixedHbarFee(10L, carol)),
                tokenAssociate(htsCollector, feeDenom),
                tokenAssociate(tokenReceiver, feeDenom),
                cryptoTransfer(moving(10, feeDenom).between(tokenTreasury, tokenReceiver)),
                tokenCreate(nonFungibleToken)
                        .treasury(tokenTreasury)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0)
                        .supplyKey(NFT_KEY)
                        .supplyType(TokenSupplyType.INFINITE)
                        .withCustom(royaltyFeeWithFallback(
                                numerator,
                                denominator,
                                fixedHtsFeeInheritingRoyaltyCollector(4, feeDenom),
                                htsCollector)),
                tokenAssociate(tokenReceiver, nonFungibleToken),
                tokenAssociate(carol, nonFungibleToken),
                mintToken(nonFungibleToken, List.of(ByteStringUtils.wrapUnsafely("meta1".getBytes()))),
                cryptoTransfer(movingUnique(nonFungibleToken, 1L).between(tokenTreasury, carol)),
                getAccountBalance(carol).hasTokenBalance(nonFungibleToken, 1),
                cryptoTransfer(movingUnique(nonFungibleToken, 1L).between(carol, tokenReceiver))
                        .signedByPayerAnd(tokenReceiver, carol),
                getAccountBalance(tokenReceiver).hasTokenBalance(nonFungibleToken, 1),
                getAccountBalance(tokenReceiver).hasTokenBalance(feeDenom, 6),
                getAccountBalance(htsCollector).hasTokenBalance(feeDenom, 4));
    }

    @HapiTest
    final Stream<DynamicTest> transferNonFungibleWithRoyaltyRandomFeeDifCollectorAccount() {
        return hapiTest(
                newKeyNamed(NFT_KEY),
                cryptoCreate(carol).balance(ONE_MILLION_HBARS),
                cryptoCreate(bob).balance(ONE_MILLION_HBARS),
                cryptoCreate(htsCollector).balance(ONE_MILLION_HBARS),
                cryptoCreate(tokenReceiver).balance(ONE_MILLION_HBARS),
                cryptoCreate(tokenTreasury).balance(ONE_MILLION_HBARS),
                tokenCreate(feeDenom).treasury(tokenTreasury).initialSupply(10).withCustom(fixedHbarFee(10L, bob)),
                tokenCreate(feeDenom2).treasury(tokenTreasury).initialSupply(10).withCustom(fixedHbarFee(10L, carol)),
                tokenAssociate(htsCollector, feeDenom),
                tokenAssociate(tokenReceiver, feeDenom),
                cryptoTransfer(moving(10, feeDenom).between(tokenTreasury, tokenReceiver)),
                tokenCreate(nonFungibleToken)
                        .treasury(tokenTreasury)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0)
                        .supplyKey(NFT_KEY)
                        .supplyType(TokenSupplyType.INFINITE)
                        .withCustom(royaltyFeeWithFallback(
                                numerator,
                                denominator,
                                fixedHtsFeeInheritingRoyaltyCollector(4, feeDenom),
                                htsCollector)),
                tokenAssociate(tokenReceiver, nonFungibleToken),
                tokenAssociate(carol, nonFungibleToken),
                mintToken(nonFungibleToken, List.of(ByteStringUtils.wrapUnsafely("meta1".getBytes()))),
                cryptoTransfer(movingUnique(nonFungibleToken, 1L).between(tokenTreasury, carol)),
                getAccountBalance(carol).hasTokenBalance(nonFungibleToken, 1),
                cryptoTransfer(movingUnique(nonFungibleToken, 1L).between(carol, tokenReceiver))
                        .signedByPayerAnd(tokenReceiver, carol),
                getAccountBalance(tokenReceiver).hasTokenBalance(nonFungibleToken, 1),
                getAccountBalance(tokenReceiver).hasTokenBalance(feeDenom, 6),
                getAccountBalance(htsCollector).hasTokenBalance(feeDenom, 4));
    }

    @HapiTest
    final Stream<DynamicTest> transferNonFungibleWithRoyaltySameHTSTreasuryToRandom() {
        return hapiTest(
                newKeyNamed(NFT_KEY),
                cryptoCreate(alice).balance(ONE_MILLION_HBARS),
                cryptoCreate(carol).balance(ONE_MILLION_HBARS),
                cryptoCreate(bob).balance(ONE_MILLION_HBARS),
                cryptoCreate(htsCollector).balance(ONE_MILLION_HBARS),
                cryptoCreate(tokenReceiver).balance(ONE_MILLION_HBARS),
                cryptoCreate(tokenTreasury).balance(ONE_MILLION_HBARS),
                tokenCreate(feeDenom2).treasury(tokenReceiver).initialSupply(10),
                tokenAssociate(bob, feeDenom2),
                tokenCreate(feeDenom).treasury(carol).initialSupply(10).withCustom(fixedHtsFee(1L, feeDenom2, bob)),
                tokenAssociate(htsCollector, feeDenom),
                tokenAssociate(tokenReceiver, feeDenom),
                cryptoTransfer(moving(10, feeDenom).between(carol, tokenReceiver)),
                tokenCreate(nonFungibleToken)
                        .treasury(tokenTreasury)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0)
                        .supplyKey(NFT_KEY)
                        .supplyType(TokenSupplyType.INFINITE)
                        .withCustom(royaltyFeeWithFallback(
                                numerator,
                                denominator,
                                fixedHtsFeeInheritingRoyaltyCollector(4, feeDenom),
                                htsCollector)),
                tokenAssociate(tokenReceiver, nonFungibleToken),
                tokenAssociate(carol, nonFungibleToken),
                mintToken(nonFungibleToken, List.of(ByteStringUtils.wrapUnsafely("meta1".getBytes()))),
                cryptoTransfer(movingUnique(nonFungibleToken, 1L).between(tokenTreasury, carol)),
                getAccountBalance(carol).hasTokenBalance(nonFungibleToken, 1),
                cryptoTransfer(movingUnique(nonFungibleToken, 1L).between(carol, tokenReceiver))
                        .signedByPayerAnd(tokenReceiver, carol),
                getAccountBalance(tokenReceiver).hasTokenBalance(nonFungibleToken, 1),
                getAccountBalance(tokenReceiver).hasTokenBalance(feeDenom, 6),
                getAccountBalance(htsCollector).hasTokenBalance(feeDenom, 4));
    }

    @HapiTest
    final Stream<DynamicTest> transferNonFungibleWithRoyaltyAnotherHTSTreasuryToRandom() {
        return hapiTest(
                newKeyNamed(NFT_KEY),
                cryptoCreate(carol).balance(ONE_MILLION_HBARS),
                cryptoCreate(bob).balance(ONE_MILLION_HBARS),
                cryptoCreate(htsCollector).balance(ONE_MILLION_HBARS),
                cryptoCreate(tokenReceiver).balance(ONE_MILLION_HBARS),
                cryptoCreate(tokenTreasury).balance(ONE_MILLION_HBARS),
                tokenCreate(feeDenom).treasury(tokenTreasury).initialSupply(10).withCustom(fixedHbarFee(10L, bob)),
                tokenCreate(feeDenom2).treasury(carol).initialSupply(10).withCustom(fixedHbarFee(10L, bob)),
                tokenAssociate(htsCollector, feeDenom),
                tokenAssociate(tokenReceiver, feeDenom),
                cryptoTransfer(moving(10, feeDenom).between(tokenTreasury, tokenReceiver)),
                tokenCreate(nonFungibleToken)
                        .treasury(tokenTreasury)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0)
                        .supplyKey(NFT_KEY)
                        .supplyType(TokenSupplyType.INFINITE)
                        .withCustom(royaltyFeeWithFallback(
                                numerator,
                                denominator,
                                fixedHtsFeeInheritingRoyaltyCollector(4, feeDenom),
                                htsCollector)),
                tokenAssociate(tokenReceiver, nonFungibleToken),
                tokenAssociate(carol, nonFungibleToken),
                mintToken(nonFungibleToken, List.of(ByteStringUtils.wrapUnsafely("meta1".getBytes()))),
                cryptoTransfer(movingUnique(nonFungibleToken, 1L).between(tokenTreasury, carol)),
                getAccountBalance(carol).hasTokenBalance(nonFungibleToken, 1),
                cryptoTransfer(movingUnique(nonFungibleToken, 1L).between(carol, tokenReceiver))
                        .signedByPayerAnd(tokenReceiver, carol),
                getAccountBalance(tokenReceiver).hasTokenBalance(nonFungibleToken, 1),
                getAccountBalance(tokenReceiver).hasTokenBalance(feeDenom, 6),
                getAccountBalance(htsCollector).hasTokenBalance(feeDenom, 4));
    }

    @HapiTest
    final Stream<DynamicTest> transferNonFungibleWithRoyaltyAllowanceOfTheNFTGiven() {
        return hapiTest(
                newKeyNamed(NFT_KEY),
                cryptoCreate(htsCollector).balance(0L),
                cryptoCreate(tokenReceiver).balance(ONE_MILLION_HBARS),
                cryptoCreate(tokenTreasury),
                cryptoCreate(spender),
                cryptoCreate(tokenOwner).balance(ONE_MILLION_HBARS),
                tokenCreate(feeDenom).treasury(tokenTreasury).initialSupply(20),
                tokenAssociate(tokenOwner, feeDenom),
                tokenAssociate(tokenReceiver, feeDenom),
                tokenAssociate(htsCollector, feeDenom),
                tokenAssociate(spender, feeDenom),
                cryptoTransfer(moving(20, feeDenom).between(tokenTreasury, tokenReceiver)),
                tokenCreate(nonFungibleToken)
                        .treasury(tokenTreasury)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0)
                        .supplyKey(NFT_KEY)
                        .supplyType(TokenSupplyType.INFINITE)
                        .withCustom(royaltyFeeNoFallback(1L, 5L, htsCollector)),
                tokenAssociate(tokenReceiver, nonFungibleToken),
                tokenAssociate(tokenOwner, nonFungibleToken),
                mintToken(nonFungibleToken, List.of(ByteStringUtils.wrapUnsafely("meta1".getBytes()))),
                cryptoTransfer(movingUnique(nonFungibleToken, 1L).between(tokenTreasury, tokenOwner)),
                cryptoApproveAllowance()
                        .addNftAllowance(tokenOwner, nonFungibleToken, spender, true, List.of(1L))
                        .fee(ONE_HUNDRED_HBARS)
                        .payingWith(tokenOwner),
                getAccountDetails(tokenOwner)
                        .has(accountDetailsWith().nftApprovedAllowancesContaining(nonFungibleToken, spender)),
                cryptoTransfer(
                                movingUniqueWithAllowance(nonFungibleToken, 1).between(tokenOwner, tokenReceiver),
                                moving(10, feeDenom).between(tokenReceiver, tokenOwner))
                        .fee(ONE_HUNDRED_HBARS)
                        .via("hbarFixedFee")
                        .payingWith(spender)
                        .signedBy(spender, tokenOwner, tokenReceiver),
                getAccountBalance(tokenOwner).hasTokenBalance(nonFungibleToken, 0),
                getAccountBalance(tokenReceiver).hasTokenBalance(nonFungibleToken, 1),
                getAccountBalance(tokenReceiver).hasTokenBalance(feeDenom, 10),
                getAccountBalance(tokenOwner).hasTokenBalance(feeDenom, 8),
                getAccountBalance(spender).hasTokenBalance(feeDenom, 0),
                getAccountBalance(htsCollector).hasTokenBalance(feeDenom, 2));
    }

    @HapiTest
    final Stream<DynamicTest> transferNonFungibleWithRoyaltyAllCollectorsExempt() {
        return hapiTest(
                newKeyNamed(NFT_KEY),
                cryptoCreate(alice).balance(ONE_MILLION_HBARS),
                cryptoCreate(bob).balance(ONE_MILLION_HBARS),
                cryptoCreate(carol).balance(ONE_MILLION_HBARS),
                cryptoCreate(htsCollector).balance(ONE_MILLION_HBARS),
                cryptoCreate(tokenReceiver).balance(ONE_MILLION_HBARS),
                cryptoCreate(tokenTreasury).balance(ONE_MILLION_HBARS),
                tokenCreate(feeDenom).treasury(alice).initialSupply(10),
                tokenCreate(feeDenom2).treasury(bob).initialSupply(20),
                tokenAssociate(htsCollector, feeDenom2),
                tokenAssociate(tokenReceiver, feeDenom2),
                tokenCreate(feeDenom3)
                        .treasury(tokenTreasury)
                        .initialSupply(10)
                        .withCustom(fixedHbarFee(10L, carol, true)),
                tokenCreate(nonFungibleToken)
                        .treasury(tokenTreasury)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0)
                        .supplyKey(NFT_KEY)
                        .supplyType(TokenSupplyType.INFINITE)
                        .withCustom(royaltyFeeWithFallback(
                                1L, 2L, fixedHtsFeeInheritingRoyaltyCollector(10, feeDenom2), htsCollector)),
                tokenAssociate(tokenReceiver, nonFungibleToken),
                tokenAssociate(carol, nonFungibleToken),
                mintToken(nonFungibleToken, List.of(ByteStringUtils.wrapUnsafely("meta1".getBytes()))),
                cryptoTransfer(movingUnique(nonFungibleToken, 1L).between(tokenTreasury, carol)),
                cryptoTransfer(moving(10, feeDenom2).between(bob, tokenReceiver)),
                cryptoTransfer(movingUnique(nonFungibleToken, 1L).between(carol, tokenReceiver))
                        .signedByPayerAnd(tokenReceiver, carol),
                getAccountBalance(tokenReceiver).hasTokenBalance(nonFungibleToken, 1),
                getAccountBalance(tokenReceiver).hasTokenBalance(feeDenom2, 0),
                getAccountBalance(htsCollector).hasTokenBalance(feeDenom, 0));
    }

    @HapiTest
    final Stream<DynamicTest> transferNonFungibleWith2LayersRoyaltyFungibleFee() {
        return hapiTest(
                newKeyNamed(NFT_KEY),
                cryptoCreate(alice),
                cryptoCreate(bob),
                cryptoCreate(carol),
                cryptoCreate(dave).balance(ONE_MILLION_HBARS),
                cryptoCreate(tokenTreasury),
                tokenCreate(feeDenom).treasury(dave).initialSupply(180),
                tokenAssociate(alice, feeDenom),
                tokenAssociate(bob, feeDenom),
                tokenAssociate(carol, feeDenom),
                tokenCreate(nonFungibleToken)
                        .treasury(tokenTreasury)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0)
                        .supplyKey(NFT_KEY)
                        .supplyType(TokenSupplyType.INFINITE)
                        .withCustom(royaltyFeeNoFallback(1, 3, alice))
                        .withCustom(royaltyFeeNoFallback(1, 4, bob)),
                tokenAssociate(carol, nonFungibleToken),
                tokenAssociate(dave, nonFungibleToken),
                mintToken(nonFungibleToken, List.of(ByteStringUtils.wrapUnsafely("meta1".getBytes()))),
                cryptoTransfer(movingUnique(nonFungibleToken, 1L).between(tokenTreasury, carol)),
                cryptoTransfer(
                        movingUnique(nonFungibleToken, 1L).between(carol, dave),
                        moving(180, feeDenom).between(dave, carol)),
                getAccountBalance(alice).hasTokenBalance(feeDenom, 60),
                getAccountBalance(bob).hasTokenBalance(feeDenom, 45),
                getAccountBalance(carol).hasTokenBalance(nonFungibleToken, 0).hasTokenBalance(feeDenom, 75),
                getAccountBalance(dave).hasTokenBalance(nonFungibleToken, 1).hasTokenBalance(feeDenom, 0));
    }

    @HapiTest
    final Stream<DynamicTest> transferNonFungibleWith2LayersRoyaltyHbarFee() {
        return hapiTest(
                newKeyNamed(NFT_KEY),
                cryptoCreate(alice).balance(0L),
                cryptoCreate(bob).balance(0L),
                cryptoCreate(carol).balance(0L),
                cryptoCreate(dave).balance(180L),
                cryptoCreate(tokenTreasury),
                tokenCreate(nonFungibleToken)
                        .treasury(tokenTreasury)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0)
                        .supplyKey(NFT_KEY)
                        .supplyType(TokenSupplyType.INFINITE)
                        .withCustom(royaltyFeeNoFallback(1, 3, alice))
                        .withCustom(royaltyFeeNoFallback(1, 4, bob)),
                tokenAssociate(carol, nonFungibleToken),
                tokenAssociate(dave, nonFungibleToken),
                mintToken(nonFungibleToken, List.of(ByteStringUtils.wrapUnsafely("meta1".getBytes()))),
                cryptoTransfer(movingUnique(nonFungibleToken, 1L).between(tokenTreasury, carol)),
                cryptoTransfer(
                        movingUnique(nonFungibleToken, 1L).between(carol, dave),
                        movingHbar(180).between(dave, carol)),
                getAccountBalance(alice).hasTinyBars(60),
                getAccountBalance(bob).hasTinyBars(45),
                getAccountBalance(carol).hasTokenBalance(nonFungibleToken, 0).hasTinyBars(75),
                getAccountBalance(dave).hasTokenBalance(nonFungibleToken, 1).hasTinyBars(0));
    }

    @HapiTest
    final Stream<DynamicTest> transferNonFungibleWithRoyaltyFromFeeCollector() {
        return hapiTest(
                newKeyNamed(NFT_KEY),
                cryptoCreate(alice),
                cryptoCreate(bob),
                cryptoCreate(carol),
                tokenCreate(feeDenom).treasury(alice).initialSupply(100),
                tokenAssociate(bob, feeDenom),
                tokenAssociate(carol, feeDenom),
                tokenCreate(nonFungibleToken)
                        .treasury(alice)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0)
                        .supplyKey(NFT_KEY)
                        .supplyType(TokenSupplyType.INFINITE)
                        .withCustom(royaltyFeeNoFallback(1, 2, bob)),
                tokenAssociate(bob, nonFungibleToken),
                tokenAssociate(carol, nonFungibleToken),
                mintToken(nonFungibleToken, List.of(ByteStringUtils.wrapUnsafely("meta1".getBytes()))),
                cryptoTransfer(movingUnique(nonFungibleToken, 1L).between(alice, bob)),
                cryptoTransfer(moving(100, feeDenom).between(alice, carol)),
                cryptoTransfer(
                        movingUnique(nonFungibleToken, 1L).between(bob, carol),
                        moving(100, feeDenom).between(carol, bob)),
                getAccountBalance(bob).hasTokenBalance(nonFungibleToken, 0).hasTokenBalance(feeDenom, 100),
                getAccountBalance(carol).hasTokenBalance(nonFungibleToken, 1).hasTokenBalance(feeDenom, 0));
    }

    @HapiTest
    final Stream<DynamicTest> transferMultipleTimesWithRoyaltyWithFallbackFeeShouldVerifyEachTransferIsPaid() {
        return hapiTest(
                newKeyNamed(NFT_KEY),
                cryptoCreate(hbarCollector).balance(0L),
                cryptoCreate(tokenTreasury),
                cryptoCreate(tokenOwner).balance(ONE_MILLION_HBARS),
                cryptoCreate(alice).balance(ONE_MILLION_HBARS),
                cryptoCreate(bob).balance(ONE_MILLION_HBARS),
                cryptoCreate(carol).balance(ONE_MILLION_HBARS),
                tokenCreate(nonFungibleToken)
                        .treasury(tokenTreasury)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0)
                        .supplyKey(NFT_KEY)
                        .supplyType(TokenSupplyType.INFINITE)
                        .withCustom(royaltyFeeWithFallback(
                                1, 2, fixedHbarFeeInheritingRoyaltyCollector(10), hbarCollector)),
                tokenAssociate(tokenOwner, nonFungibleToken),
                tokenAssociate(alice, nonFungibleToken),
                tokenAssociate(bob, nonFungibleToken),
                tokenAssociate(carol, nonFungibleToken),
                mintToken(nonFungibleToken, List.of(ByteStringUtils.wrapUnsafely("meta1".getBytes()))),
                cryptoTransfer(movingUnique(nonFungibleToken, 1L).between(tokenTreasury, tokenOwner)),
                cryptoTransfer(movingUnique(nonFungibleToken, 1L).between(tokenOwner, alice))
                        .signedByPayerAnd(alice, tokenOwner),
                cryptoTransfer(movingUnique(nonFungibleToken, 1L).between(alice, bob))
                        .signedByPayerAnd(bob, alice),
                cryptoTransfer(movingUnique(nonFungibleToken, 1L).between(bob, carol))
                        .signedByPayerAnd(bob, carol),
                getAccountBalance(hbarCollector).hasTinyBars(30L),
                getAccountBalance(tokenOwner)
                        .hasTokenBalance(nonFungibleToken, 0L)
                        .hasTinyBars(ONE_MILLION_HBARS),
                getAccountBalance(alice).hasTokenBalance(nonFungibleToken, 0L).hasTinyBars(ONE_MILLION_HBARS - 10),
                getAccountBalance(bob).hasTokenBalance(nonFungibleToken, 0L).hasTinyBars(ONE_MILLION_HBARS - 10),
                getAccountBalance(carol).hasTokenBalance(nonFungibleToken, 1L).hasTinyBars(ONE_MILLION_HBARS - 10));
    }

    @HapiTest
    final Stream<DynamicTest> transferMultipleNonFungibleWithRoyaltyFungibleFee() {
        return hapiTest(
                newKeyNamed(NFT_KEY),
                cryptoCreate(alice),
                cryptoCreate(carol).balance(ONE_MILLION_HBARS),
                cryptoCreate(tokenTreasury),
                cryptoCreate(bob),
                tokenCreate(feeDenom).treasury(carol).initialSupply(180),
                tokenAssociate(bob, feeDenom),
                tokenAssociate(alice, feeDenom),
                tokenCreate(nonFungibleToken)
                        .treasury(tokenTreasury)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0)
                        .supplyKey(NFT_KEY)
                        .supplyType(TokenSupplyType.INFINITE)
                        .withCustom(royaltyFeeNoFallback(1, 3, alice)),
                tokenAssociate(carol, nonFungibleToken),
                tokenAssociate(bob, nonFungibleToken),
                mintToken(nonFungibleToken, List.of(ByteStringUtils.wrapUnsafely("meta1".getBytes()))),
                mintToken(nonFungibleToken, List.of(ByteStringUtils.wrapUnsafely("meta2".getBytes()))),
                cryptoTransfer(movingUnique(nonFungibleToken, 1L).between(tokenTreasury, bob)),
                cryptoTransfer(movingUnique(nonFungibleToken, 2L).between(tokenTreasury, bob)),
                cryptoTransfer(
                        movingUnique(nonFungibleToken, 1L).between(bob, carol),
                        movingUnique(nonFungibleToken, 2L).between(bob, carol),
                        moving(180, feeDenom).between(carol, bob)),
                getAccountBalance(bob).hasTokenBalance(nonFungibleToken, 0).hasTokenBalance(feeDenom, 120),
                getAccountBalance(carol).hasTokenBalance(nonFungibleToken, 2).hasTokenBalance(feeDenom, 0),
                getAccountBalance(alice).hasTokenBalance(feeDenom, 60));
    }

    @HapiTest
    final Stream<DynamicTest> transferMultipleNonFungibleWithRoyaltyHbarFee() {
        return hapiTest(
                newKeyNamed(NFT_KEY),
                cryptoCreate(alice).balance(0L),
                cryptoCreate(carol).balance(180L),
                cryptoCreate(tokenTreasury),
                cryptoCreate(bob).balance(0L),
                tokenCreate(nonFungibleToken)
                        .treasury(tokenTreasury)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0)
                        .supplyKey(NFT_KEY)
                        .supplyType(TokenSupplyType.INFINITE)
                        .withCustom(royaltyFeeNoFallback(1, 3, alice)),
                tokenAssociate(carol, nonFungibleToken),
                tokenAssociate(bob, nonFungibleToken),
                mintToken(nonFungibleToken, List.of(ByteStringUtils.wrapUnsafely("meta1".getBytes()))),
                mintToken(nonFungibleToken, List.of(ByteStringUtils.wrapUnsafely("meta2".getBytes()))),
                cryptoTransfer(movingUnique(nonFungibleToken, 1L).between(tokenTreasury, bob)),
                cryptoTransfer(movingUnique(nonFungibleToken, 2L).between(tokenTreasury, bob)),
                cryptoTransfer(
                        movingUnique(nonFungibleToken, 1L).between(bob, carol),
                        movingUnique(nonFungibleToken, 2L).between(bob, carol),
                        movingHbar(180).between(carol, bob)),
                getAccountBalance(bob).hasTinyBars(120).hasTokenBalance(nonFungibleToken, 0),
                getAccountBalance(carol).hasTinyBars(0).hasTokenBalance(nonFungibleToken, 2),
                getAccountBalance(alice).hasTinyBars(60));
    }

    @HapiTest
    final Stream<DynamicTest> transferMultipleNonFungibleWithRoyaltyHbarAndFungibleFee() {
        return hapiTest(
                newKeyNamed(NFT_KEY),
                cryptoCreate(alice).balance(0L),
                cryptoCreate(carol).balance(180L),
                cryptoCreate(tokenTreasury),
                cryptoCreate(bob).balance(0L),
                tokenCreate(feeDenom).treasury(carol).initialSupply(180),
                tokenAssociate(bob, feeDenom),
                tokenAssociate(alice, feeDenom),
                tokenCreate(nonFungibleToken)
                        .treasury(tokenTreasury)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0)
                        .supplyKey(NFT_KEY)
                        .supplyType(TokenSupplyType.INFINITE)
                        .withCustom(royaltyFeeNoFallback(1, 3, alice)),
                tokenAssociate(carol, nonFungibleToken),
                tokenAssociate(bob, nonFungibleToken),
                mintToken(nonFungibleToken, List.of(ByteStringUtils.wrapUnsafely("meta1".getBytes()))),
                mintToken(nonFungibleToken, List.of(ByteStringUtils.wrapUnsafely("meta2".getBytes()))),
                cryptoTransfer(movingUnique(nonFungibleToken, 1L).between(tokenTreasury, bob)),
                cryptoTransfer(movingUnique(nonFungibleToken, 2L).between(tokenTreasury, bob)),
                cryptoTransfer(
                        movingUnique(nonFungibleToken, 1L).between(bob, carol),
                        movingUnique(nonFungibleToken, 2L).between(bob, carol),
                        movingHbar(180).between(carol, bob),
                        moving(180, feeDenom).between(carol, bob)),
                getAccountBalance(bob)
                        .hasTinyBars(120)
                        .hasTokenBalance(feeDenom, 120)
                        .hasTokenBalance(nonFungibleToken, 0),
                getAccountBalance(carol)
                        .hasTinyBars(0)
                        .hasTokenBalance(feeDenom, 0)
                        .hasTokenBalance(nonFungibleToken, 2),
                getAccountBalance(alice).hasTinyBars(60).hasTokenBalance(feeDenom, 60));
    }

    @HapiTest
    final Stream<DynamicTest> transferNonFungibleWithHollowAccountAndRoyaltyHbarFee() {
        final var fourHundredHbars = 4 * ONE_HUNDRED_HBARS;
        final var twoHundredHbars = 2 * ONE_HUNDRED_HBARS;
        return hapiTest(flattened(
                newKeyNamed(NFT_KEY),
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(tokenReceiver).balance(fourHundredHbars),
                cryptoCreate(tokenTreasury),
                cryptoCreate(tokenOwner).balance(0L),
                createHollowAccountFrom(SECP_256K1_SOURCE_KEY),
                withOpContext((spec, opLog) -> {
                    final var hollowAccountCollector =
                            spec.registry().getAccountIdName(spec.registry().getAccountAlias(SECP_256K1_SOURCE_KEY));

                    allRunFor(
                            spec,
                            tokenCreate(nonFungibleToken)
                                    .treasury(tokenTreasury)
                                    .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                                    .initialSupply(0)
                                    .supplyKey(NFT_KEY)
                                    .supplyType(TokenSupplyType.INFINITE)
                                    .withCustom(royaltyFeeNoFallback(1, 2, hollowAccountCollector)),
                            tokenAssociate(tokenReceiver, nonFungibleToken),
                            tokenAssociate(tokenOwner, nonFungibleToken),
                            mintToken(nonFungibleToken, List.of(ByteStringUtils.wrapUnsafely("meta1".getBytes()))),
                            cryptoTransfer(movingUnique(nonFungibleToken, 1L).between(tokenTreasury, tokenOwner)),
                            cryptoTransfer(
                                    movingUnique(nonFungibleToken, 1L).between(tokenOwner, tokenReceiver),
                                    movingHbar(fourHundredHbars).between(tokenReceiver, tokenOwner)),
                            getAccountBalance(tokenOwner)
                                    .hasTinyBars(twoHundredHbars)
                                    .hasTokenBalance(nonFungibleToken, 0),
                            getAccountBalance(tokenReceiver).hasTinyBars(0).hasTokenBalance(nonFungibleToken, 1),
                            getAccountBalance(hollowAccountCollector).hasTinyBars(ONE_HUNDRED_HBARS + twoHundredHbars));
                })));
    }

    @HapiTest
    final Stream<DynamicTest> transferNonFungibleWithHollowAccountAndRoyaltyFungibleFee() {
        return hapiTest(flattened(
                newKeyNamed(NFT_KEY),
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(tokenReceiver).balance(ONE_MILLION_HBARS),
                cryptoCreate(tokenTreasury),
                cryptoCreate(tokenOwner),
                createHollowAccountFrom(SECP_256K1_SOURCE_KEY),
                withOpContext((spec, opLog) -> {
                    final var hollowAccountCollector =
                            spec.registry().getAccountIdName(spec.registry().getAccountAlias(SECP_256K1_SOURCE_KEY));
                    allRunFor(
                            spec,
                            tokenCreate(feeDenom)
                                    .treasury(tokenReceiver)
                                    .payingWith(SECP_256K1_SOURCE_KEY)
                                    .sigMapPrefixes(uniqueWithFullPrefixesFor(SECP_256K1_SOURCE_KEY))
                                    .initialSupply(4),
                            tokenAssociate(hollowAccountCollector, feeDenom),
                            tokenAssociate(tokenOwner, feeDenom),
                            tokenCreate(nonFungibleToken)
                                    .treasury(tokenTreasury)
                                    .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                                    .initialSupply(0)
                                    .supplyKey(NFT_KEY)
                                    .supplyType(TokenSupplyType.INFINITE)
                                    .payingWith(SECP_256K1_SOURCE_KEY)
                                    .sigMapPrefixes(uniqueWithFullPrefixesFor(SECP_256K1_SOURCE_KEY))
                                    .via(TRANSFER_TXN_2)
                                    .withCustom(royaltyFeeNoFallback(1, 2, hollowAccountCollector)),
                            tokenAssociate(tokenReceiver, nonFungibleToken),
                            tokenAssociate(tokenOwner, nonFungibleToken),
                            mintToken(nonFungibleToken, List.of(ByteStringUtils.wrapUnsafely("meta1".getBytes()))),
                            cryptoTransfer(movingUnique(nonFungibleToken, 1L).between(tokenTreasury, tokenOwner)),
                            cryptoTransfer(
                                    movingUnique(nonFungibleToken, 1L).between(tokenOwner, tokenReceiver),
                                    moving(4, feeDenom).between(tokenReceiver, tokenOwner)),
                            getAccountBalance(tokenOwner)
                                    .hasTokenBalance(nonFungibleToken, 0)
                                    .hasTokenBalance(feeDenom, 2),
                            getAccountBalance(tokenReceiver)
                                    .hasTokenBalance(nonFungibleToken, 1)
                                    .hasTokenBalance(feeDenom, 0),
                            getAccountBalance(hollowAccountCollector).hasTokenBalance(feeDenom, 2));
                })));
    }

    @HapiTest
    final Stream<DynamicTest> transferNonFungibleWithHollowAccountAndRoyaltyFallbackHbarFee() {
        return hapiTest(flattened(
                newKeyNamed(NFT_KEY),
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(tokenReceiver).balance(ONE_MILLION_HBARS),
                cryptoCreate(tokenTreasury),
                cryptoCreate(tokenOwner),
                createHollowAccountFrom(SECP_256K1_SOURCE_KEY),
                withOpContext((spec, opLog) -> {
                    final var hollowAccountCollector =
                            spec.registry().getAccountIdName(spec.registry().getAccountAlias(SECP_256K1_SOURCE_KEY));
                    allRunFor(
                            spec,
                            tokenCreate(nonFungibleToken)
                                    .treasury(tokenTreasury)
                                    .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                                    .initialSupply(0)
                                    .supplyKey(NFT_KEY)
                                    .supplyType(TokenSupplyType.INFINITE)
                                    .via(TRANSFER_TXN_2)
                                    .withCustom(royaltyFeeWithFallback(
                                            1, 2, fixedHbarFeeInheritingRoyaltyCollector(100), hollowAccountCollector)),
                            tokenAssociate(tokenReceiver, nonFungibleToken),
                            tokenAssociate(tokenOwner, nonFungibleToken),
                            mintToken(nonFungibleToken, List.of(ByteStringUtils.wrapUnsafely("meta1".getBytes()))),
                            cryptoTransfer(movingUnique(nonFungibleToken, 1L).between(tokenTreasury, tokenOwner)),
                            cryptoTransfer(movingUnique(nonFungibleToken, 1L).between(tokenOwner, tokenReceiver))
                                    .signedByPayerAnd(tokenReceiver, tokenOwner),
                            getAccountBalance(tokenOwner).hasTokenBalance(nonFungibleToken, 0),
                            getAccountBalance(tokenReceiver)
                                    .hasTokenBalance(nonFungibleToken, 1)
                                    .hasTinyBars(ONE_MILLION_HBARS - 100),
                            getAccountBalance(hollowAccountCollector).hasTinyBars(ONE_HUNDRED_HBARS + 100));
                })));
    }

    @HapiTest
    final Stream<DynamicTest> transferRoyaltyFeeExplicitlyDuplicatedHbarSenderIds() {
        final var bufferAccount = "account2";
        final var feeCollector = "feeCollector";
        final var xferAmount = Long.MAX_VALUE / 8;
        return hapiTest(
                newKeyNamed(NFT_KEY),
                cryptoCreate(feeCollector).balance(0L),
                cryptoCreate(tokenReceiver).balance(ONE_MILLION_HBARS),
                cryptoCreate(tokenTreasury),
                cryptoCreate(tokenOwner).balance(ONE_MILLION_HBARS),
                cryptoCreate(bufferAccount).balance(ONE_MILLION_HBARS),
                tokenCreate(nonFungibleToken)
                        .treasury(tokenTreasury)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0)
                        .supplyKey(NFT_KEY)
                        .supplyType(TokenSupplyType.INFINITE)
                        .withCustom(royaltyFeeNoFallback(1, 2, feeCollector)),
                tokenAssociate(tokenReceiver, nonFungibleToken),
                tokenAssociate(tokenOwner, nonFungibleToken),
                mintToken(nonFungibleToken, List.of(ByteStringUtils.wrapUnsafely("meta1".getBytes()))),
                cryptoTransfer(movingUnique(nonFungibleToken, 1L).between(tokenTreasury, tokenOwner)),
                cryptoApproveAllowance()
                        .addCryptoAllowance(tokenOwner, bufferAccount, xferAmount)
                        .signedByPayerAnd(tokenOwner, bufferAccount),
                cryptoTransfer((spec, tl) -> tl.setTransfers(TransferList.newBuilder()
                                        .addAccountAmounts(AccountAmount.newBuilder()
                                                .setAccountID(asId(tokenOwner, spec))
                                                .setAmount(-xferAmount)
                                                .setIsApproval(true)
                                                .build())
                                        .addAccountAmounts(AccountAmount.newBuilder()
                                                .setAccountID(asId(tokenOwner, spec))
                                                .setAmount(xferAmount)
                                                .setIsApproval(false)
                                                .build()))
                                .addTokenTransfers(TokenTransferList.newBuilder()
                                        .setToken(asTokenId(nonFungibleToken, spec))
                                        .addNftTransfers(NftTransfer.newBuilder()
                                                .setSenderAccountID(asId(tokenOwner, spec))
                                                .setReceiverAccountID(asId(tokenReceiver, spec))
                                                .setSerialNumber(1))))
                        .fee(ONE_HUNDRED_HBARS)
                        .payingWith(bufferAccount)
                        .signedBy(bufferAccount, tokenOwner, tokenReceiver)
                        .hasPrecheck(ACCOUNT_REPEATED_IN_ACCOUNT_AMOUNTS));
    }

    @HapiTest
    final Stream<DynamicTest> transferRoyaltyFeeDuplicatedEvmAddressFungibleSenderIds() {
        final var bufferAccount = "account2";
        final var fungibleToken = "fungibleToken";
        final var feeCollector = "feeCollector";

        // amount of fungible tokens to mint / 2
        final var xferAmount = Long.MAX_VALUE / 2;
        return hapiTest(
                newKeyNamed(NFT_KEY),
                cryptoCreate(feeCollector).balance(0L),
                cryptoCreate(tokenReceiver).balance(ONE_MILLION_HBARS),
                cryptoCreate(tokenTreasury),
                createHip32Auto(1, SECP_256K1_SHAPE, i -> tokenOwner),
                cryptoCreate(bufferAccount).balance(ONE_MILLION_HBARS),
                tokenCreate(nonFungibleToken)
                        .treasury(tokenTreasury)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0)
                        .supplyKey(NFT_KEY)
                        .supplyType(TokenSupplyType.INFINITE)
                        .withCustom(royaltyFeeNoFallback(1, 2, feeCollector)),
                tokenCreate(fungibleToken)
                        .treasury(tokenTreasury)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .initialSupply(10_000L),
                tokenAssociate(tokenReceiver, nonFungibleToken),
                tokenAssociate(tokenOwner, nonFungibleToken),
                tokenAssociate(tokenOwner, fungibleToken),
                tokenAssociate(bufferAccount, fungibleToken),
                tokenAssociate(feeCollector, fungibleToken),
                mintToken(nonFungibleToken, List.of(ByteStringUtils.wrapUnsafely("meta1".getBytes()))),
                cryptoTransfer(movingUnique(nonFungibleToken, 1L).between(tokenTreasury, tokenOwner)),

                // token balance of attacker is initially 0
                getAccountBalance(tokenOwner).hasTokenBalance(fungibleToken, 0),
                cryptoApproveAllowance()
                        .addTokenAllowance(tokenOwner, fungibleToken, bufferAccount, xferAmount)
                        .signedByPayerAnd(tokenOwner, bufferAccount),
                cryptoTransfer((spec, tl) -> {
                            final var ownerKey = spec.registry().getKey(tokenOwner);
                            final var ownerEcKey = ownerKey.getECDSASecp256K1();
                            final var evmAddress =
                                    ByteString.copyFrom(recoverAddressFromPubKey(ownerEcKey.toByteArray()));
                            tl.addTokenTransfers(TokenTransferList.newBuilder()
                                            .setToken(asTokenId(fungibleToken, spec))
                                            .addTransfers(AccountAmount.newBuilder()
                                                    .setAccountID(AccountID.newBuilder()
                                                            .setShardNum(spec.shard())
                                                            .setRealmNum(spec.realm())
                                                            .setAlias(ownerKey.toByteString())
                                                            .build())
                                                    .setAmount(-xferAmount)
                                                    .setIsApproval(true)
                                                    .build())
                                            .addTransfers(AccountAmount.newBuilder()
                                                    .setAccountID(AccountID.newBuilder()
                                                            .setShardNum(spec.shard())
                                                            .setRealmNum(spec.realm())
                                                            .setAlias(evmAddress)
                                                            .build())
                                                    .setAmount(xferAmount)
                                                    .setIsApproval(false)
                                                    .build()))
                                    .addTokenTransfers(TokenTransferList.newBuilder()
                                            .setToken(asTokenId(nonFungibleToken, spec))
                                            .addNftTransfers(NftTransfer.newBuilder()
                                                    .setSenderAccountID(asId(tokenOwner, spec))
                                                    .setReceiverAccountID(asId(tokenReceiver, spec))
                                                    .setSerialNumber(1)));
                        })
                        .fee(ONE_HUNDRED_HBARS)
                        .payingWith(bufferAccount)
                        .signedBy(bufferAccount, tokenOwner, tokenReceiver)
                        .hasKnownStatus(ACCOUNT_REPEATED_IN_ACCOUNT_AMOUNTS)
                        .via("txn"),
                getTxnRecord("txn").hasPriority(recordWith().tokenTransfers(spec -> tokenTransfers -> {
                    try {
                        assertTrue(tokenTransfers.isEmpty());
                    } catch (Throwable t) {
                        return List.of(t);
                    }
                    return emptyList();
                })),
                getAccountBalance(feeCollector).hasTokenBalance(fungibleToken, 0));
    }

    @HapiTest
    final Stream<DynamicTest> transferRoyaltyFeeLongZeroAddressFungibleSenderIds() {
        final var bufferAccount = "account2";
        final var fungibleToken = "fungibleToken";
        final var feeCollector = "feeCollector";

        // amount of fungible tokens to mint / 2
        final var xferAmount = Long.MAX_VALUE / 2;
        return hapiTest(
                newKeyNamed(NFT_KEY),
                cryptoCreate(feeCollector).balance(0L),
                cryptoCreate(tokenReceiver).balance(ONE_MILLION_HBARS),
                cryptoCreate(tokenTreasury),
                cryptoCreate(tokenOwner).balance(ONE_MILLION_HBARS),
                cryptoCreate(bufferAccount).balance(ONE_MILLION_HBARS),
                tokenCreate(nonFungibleToken)
                        .treasury(tokenTreasury)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0)
                        .supplyKey(NFT_KEY)
                        .supplyType(TokenSupplyType.INFINITE)
                        .withCustom(royaltyFeeNoFallback(1, 2, feeCollector)),
                tokenCreate(fungibleToken)
                        .treasury(tokenTreasury)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .initialSupply(10_000L),
                tokenAssociate(tokenReceiver, nonFungibleToken),
                tokenAssociate(tokenOwner, nonFungibleToken),
                tokenAssociate(tokenOwner, fungibleToken),
                tokenAssociate(bufferAccount, fungibleToken),
                tokenAssociate(feeCollector, fungibleToken),
                mintToken(nonFungibleToken, List.of(ByteStringUtils.wrapUnsafely("meta1".getBytes()))),
                cryptoTransfer(movingUnique(nonFungibleToken, 1L).between(tokenTreasury, tokenOwner)),

                // token balance of attacker is initially 0
                getAccountBalance(tokenOwner).hasTokenBalance(fungibleToken, 0),
                cryptoApproveAllowance()
                        .addTokenAllowance(tokenOwner, fungibleToken, bufferAccount, xferAmount)
                        .signedByPayerAnd(tokenOwner, bufferAccount),
                cryptoTransfer((spec, tl) -> {
                            final var ownerId = asId(tokenOwner, spec);
                            tl.addTokenTransfers(TokenTransferList.newBuilder()
                                            .setToken(asTokenId(fungibleToken, spec))
                                            .addTransfers(AccountAmount.newBuilder()
                                                    .setAccountID(ownerId)
                                                    .setAmount(-xferAmount)
                                                    .setIsApproval(true)
                                                    .build())
                                            .addTransfers(AccountAmount.newBuilder()
                                                    .setAccountID(AccountID.newBuilder()
                                                            .setShardNum(spec.shard())
                                                            .setRealmNum(spec.realm())
                                                            .setAlias(ByteString.copyFrom(
                                                                    asEvmAddress(ownerId.getAccountNum())))
                                                            .build())
                                                    .setAmount(xferAmount)
                                                    .setIsApproval(false)
                                                    .build()))
                                    .addTokenTransfers(TokenTransferList.newBuilder()
                                            .setToken(asTokenId(nonFungibleToken, spec))
                                            .addNftTransfers(NftTransfer.newBuilder()
                                                    .setSenderAccountID(asId(tokenOwner, spec))
                                                    .setReceiverAccountID(asId(tokenReceiver, spec))
                                                    .setSerialNumber(1)));
                        })
                        .fee(ONE_HUNDRED_HBARS)
                        .payingWith(bufferAccount)
                        .signedBy(bufferAccount, tokenOwner, tokenReceiver)
                        .hasKnownStatus(ACCOUNT_REPEATED_IN_ACCOUNT_AMOUNTS)
                        .via("txn"),
                getTxnRecord("txn").hasPriority(recordWith().tokenTransfers(spec -> tokenTransfers -> {
                    try {
                        assertTrue(tokenTransfers.isEmpty());
                    } catch (Throwable t) {
                        return List.of(t);
                    }
                    return emptyList();
                })),
                getAccountBalance(feeCollector).hasTokenBalance(fungibleToken, 0));
    }

    @HapiTest
    final Stream<DynamicTest> transferRoyaltyFeeExplicitlyDuplicatedFungibleSenderIds() {
        final var bufferAccount = "account2";
        final var fungibleToken = "fungibleToken";
        final var feeCollector = "feeCollector";

        // amount of fungible tokens to mint / 2
        final var xferAmount = Long.MAX_VALUE / 2;
        return hapiTest(
                newKeyNamed(NFT_KEY),
                cryptoCreate(feeCollector).balance(0L),
                cryptoCreate(tokenReceiver).balance(ONE_MILLION_HBARS),
                cryptoCreate(tokenTreasury),
                cryptoCreate(tokenOwner).balance(ONE_MILLION_HBARS),
                cryptoCreate(bufferAccount).balance(ONE_MILLION_HBARS),
                tokenCreate(nonFungibleToken)
                        .treasury(tokenTreasury)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0)
                        .supplyKey(NFT_KEY)
                        .supplyType(TokenSupplyType.INFINITE)
                        .withCustom(royaltyFeeNoFallback(1, 2, feeCollector)),
                tokenCreate(fungibleToken)
                        .treasury(tokenTreasury)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .initialSupply(10_000L),
                tokenAssociate(tokenReceiver, nonFungibleToken),
                tokenAssociate(tokenOwner, nonFungibleToken),
                tokenAssociate(tokenOwner, fungibleToken),
                tokenAssociate(bufferAccount, fungibleToken),
                tokenAssociate(feeCollector, fungibleToken),
                mintToken(nonFungibleToken, List.of(ByteStringUtils.wrapUnsafely("meta1".getBytes()))),
                cryptoTransfer(movingUnique(nonFungibleToken, 1L).between(tokenTreasury, tokenOwner)),

                // token balance of attacker is initially 0
                getAccountBalance(tokenOwner).hasTokenBalance(fungibleToken, 0),
                cryptoApproveAllowance()
                        .addTokenAllowance(tokenOwner, fungibleToken, bufferAccount, xferAmount)
                        .signedByPayerAnd(tokenOwner, bufferAccount),
                cryptoTransfer((spec, tl) -> tl.addTokenTransfers(TokenTransferList.newBuilder()
                                        .setToken(asTokenId(fungibleToken, spec))
                                        .addTransfers(AccountAmount.newBuilder()
                                                .setAccountID(asId(tokenOwner, spec))
                                                .setAmount(-xferAmount)
                                                .setIsApproval(true)
                                                .build())
                                        .addTransfers(AccountAmount.newBuilder()
                                                .setAccountID(asId(tokenOwner, spec))
                                                .setAmount(xferAmount)
                                                .setIsApproval(false)
                                                .build()))
                                .addTokenTransfers(TokenTransferList.newBuilder()
                                        .setToken(asTokenId(nonFungibleToken, spec))
                                        .addNftTransfers(NftTransfer.newBuilder()
                                                .setSenderAccountID(asId(tokenOwner, spec))
                                                .setReceiverAccountID(asId(tokenReceiver, spec))
                                                .setSerialNumber(1))))
                        .fee(ONE_HUNDRED_HBARS)
                        .payingWith(bufferAccount)
                        .signedBy(bufferAccount, tokenOwner, tokenReceiver)
                        .hasPrecheck(ACCOUNT_REPEATED_IN_ACCOUNT_AMOUNTS));
    }

    /**
     * Verifies that bidirectional airdrops cannot trigger NFT royalty fee payments.
     * <p>
     * That is, checks that if an NFT is airdropped by a sender that also <i>receives</i> a fungible airdrop in
     * the same transaction, that can only succeed if the sender is exempt from the non-fungible token type's
     * fees.
     */
    @HapiTest
    final Stream<DynamicTest> biDirectionalAirdropsCannotTriggerRoyaltyPayments() {
        return hapiTest(
                newKeyNamed(NFT_KEY),
                cryptoCreate(htsCollector),
                cryptoCreate(tokenReceiver).balance(ONE_MILLION_HBARS),
                cryptoCreate(tokenTreasury),
                cryptoCreate(tokenOwner),
                tokenCreate(feeDenom).treasury(tokenTreasury).initialSupply(2000),
                tokenAssociate(tokenOwner, feeDenom),
                tokenAssociate(tokenReceiver, feeDenom),
                tokenAssociate(htsCollector, feeDenom),
                cryptoTransfer(moving(1000, feeDenom).between(tokenTreasury, tokenReceiver)),
                tokenCreate(nonFungibleToken)
                        .treasury(tokenTreasury)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0)
                        .supplyKey(NFT_KEY)
                        .supplyType(TokenSupplyType.INFINITE)
                        .withCustom(royaltyFeeNoFallback(1, 1, htsCollector)),
                tokenAssociate(tokenReceiver, nonFungibleToken),
                tokenAssociate(tokenOwner, nonFungibleToken),
                mintToken(
                        nonFungibleToken,
                        List.of(
                                ByteStringUtils.wrapUnsafely("meta1".getBytes()),
                                ByteStringUtils.wrapUnsafely("meta2".getBytes()))),
                cryptoTransfer(movingUnique(nonFungibleToken, 1L).between(tokenTreasury, tokenOwner)),

                // If the sender in a bidirectional airdrop would pay a royalty fee for the NFT it is sending, we fail
                tokenAirdrop(
                                movingUnique(nonFungibleToken, 1L).between(tokenOwner, tokenReceiver),
                                moving(1000, feeDenom).between(tokenReceiver, tokenOwner))
                        .signedByPayerAnd(tokenOwner, tokenReceiver)
                        .hasKnownStatus(TOKEN_AIRDROP_WITH_FALLBACK_ROYALTY),
                // But an exempt sender in the exact situation is fine (no royalty charged)
                tokenAirdrop(
                                movingUnique(nonFungibleToken, 2L).between(tokenTreasury, tokenReceiver),
                                moving(1000, feeDenom).between(tokenReceiver, tokenTreasury))
                        .signedByPayerAnd(tokenTreasury, tokenReceiver),
                // And even the non-sender can still airdrop without receiving a bidirectional airdrop
                tokenAirdrop(movingUnique(nonFungibleToken, 1L).between(tokenOwner, tokenReceiver))
                        .signedByPayerAnd(tokenOwner),
                getAccountBalance(htsCollector).hasTokenBalance(feeDenom, 0));
    }
}
