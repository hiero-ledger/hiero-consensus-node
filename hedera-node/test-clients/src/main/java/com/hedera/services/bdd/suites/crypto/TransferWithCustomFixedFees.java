// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.crypto;

import static com.hedera.services.bdd.junit.TestTags.CRYPTO;
import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.spec.HapiSpec.customizedHapiTest;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AccountDetailsAsserts.accountDetailsWith;
import static com.hedera.services.bdd.spec.keys.TrieSigMapGenerator.uniqueWithFullPrefixesFor;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountDetails;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoApproveAllowance;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedHbarFee;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedHtsFee;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fractionalFee;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingUnique;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingUniqueWithAllowance;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingWithAllowance;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SHAPE;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SOURCE_KEY;
import static com.hedera.services.bdd.suites.HapiSuite.THOUSAND_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.flattened;
import static com.hedera.services.bdd.suites.crypto.AutoAccountUpdateSuite.TRANSFER_TXN_2;
import static com.hedera.services.bdd.suites.crypto.AutoCreateUtils.createHollowAccountFrom;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CUSTOM_FEE_CHARGING_EXCEEDED_MAX_RECURSION_DEPTH;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CUSTOM_FEE_MUST_BE_POSITIVE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_SENDER_ACCOUNT_BALANCE_FOR_CUSTOM_FEE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_TOKEN_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SPENDER_DOES_NOT_HAVE_ALLOWANCE;

import com.hedera.node.app.hapi.utils.ByteStringUtils;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hederahashgraph.api.proto.java.TokenSupplyType;
import com.hederahashgraph.api.proto.java.TokenType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(CRYPTO)
public class TransferWithCustomFixedFees {
    private static final long hbarFee = 1_000L;
    public static final long htsFee = 100L;
    private static final long tokenTotal = 1_000L;
    private static final long numerator = 1L;
    private static final long denominator = 10L;
    private static final long minHtsFee = 2L;
    private static final long maxHtsFee = 10L;

    private static final String fungibleToken = "fungibleWithCustomFees";
    private static final String fungibleToken2 = "fungibleWithCustomFees2";
    private static final String nonFungibleToken = "nonFungibleWithCustomFees";
    private static final String feeDenom = "denom";
    private static final String feeDenom2 = "denom2";
    private static final String hbarCollector = "hbarFee";
    private static final String htsCollector = "denomFee";
    private static final String htsCollector2 = "denomFee2";
    private static final String tokenReceiver = "receiver";
    private static final String tokenTreasury = "tokenTreasury";
    private static final String spender = "spender";
    private static final String NFT_KEY = "nftKey";
    private static final String tokenOwner = "tokenOwner";
    private static final String alice = "alice";
    private static final long aliceFee = 100L;
    private static final String bob = "bob";
    private static final long bobFee = 200L;
    private static final String carol = "carol";
    private static final long carolFee = 300L;

    private static final String ivan = "ivan";

    @HapiTest
    final Stream<DynamicTest> transferFungibleWithFixedHbarCustomFee() {
        return hapiTest(
                cryptoCreate(hbarCollector).balance(0L),
                cryptoCreate(tokenOwner).balance(ONE_MILLION_HBARS),
                cryptoCreate(tokenReceiver),
                cryptoCreate(tokenTreasury),
                tokenCreate(fungibleToken)
                        .treasury(tokenTreasury)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .initialSupply(tokenTotal)
                        .withCustom(fixedHbarFee(hbarFee, hbarCollector)),
                tokenAssociate(tokenReceiver, fungibleToken),
                tokenAssociate(tokenOwner, fungibleToken),
                cryptoTransfer(moving(1000, fungibleToken).between(tokenTreasury, tokenOwner)),
                cryptoTransfer(moving(1, fungibleToken).between(tokenOwner, tokenReceiver))
                        .fee(ONE_HUNDRED_HBARS)
                        .via("transferTx")
                        .payingWith(tokenOwner),
                withOpContext((spec, log) -> {
                    final var record = getTxnRecord("transferTx");
                    allRunFor(spec, record);
                    final var txFee = record.getResponseRecord().getTransactionFee();

                    final var ownerBalance = getAccountBalance(tokenOwner)
                            .hasTinyBars(ONE_MILLION_HBARS - (txFee + hbarFee))
                            .hasTokenBalance(fungibleToken, 999);
                    final var receiverBalance = getAccountBalance(tokenReceiver).hasTokenBalance(fungibleToken, 1);
                    final var collectorBalance =
                            getAccountBalance(hbarCollector).hasTinyBars(hbarFee);

                    allRunFor(spec, ownerBalance, receiverBalance, collectorBalance);
                }));
    }

    @HapiTest
    final Stream<DynamicTest> transferFungibleWithFixedHbarCustomFeeNotEnoughBalance() {
        return hapiTest(
                cryptoCreate(hbarCollector).balance(0L),
                cryptoCreate(tokenOwner).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(tokenReceiver),
                cryptoCreate(tokenTreasury),
                tokenCreate(fungibleToken)
                        .treasury(tokenTreasury)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .initialSupply(tokenTotal)
                        .withCustom(fixedHbarFee(ONE_HUNDRED_HBARS, hbarCollector)),
                tokenAssociate(tokenReceiver, fungibleToken),
                tokenAssociate(tokenOwner, fungibleToken),
                cryptoTransfer(moving(tokenTotal, fungibleToken).between(tokenTreasury, tokenOwner)),
                cryptoTransfer(moving(1, fungibleToken).between(tokenOwner, tokenReceiver))
                        .payingWith(tokenOwner)
                        .fee(ONE_HUNDRED_HBARS)
                        .hasKnownStatus(INSUFFICIENT_SENDER_ACCOUNT_BALANCE_FOR_CUSTOM_FEE),
                getAccountBalance(tokenOwner).hasTokenBalance(fungibleToken, tokenTotal),
                getAccountBalance(tokenReceiver).hasTokenBalance(fungibleToken, 0),
                getAccountBalance(hbarCollector).hasTinyBars(0));
    }

    @HapiTest
    final Stream<DynamicTest> transferFungibleWithFixedHtsCustomFee() {
        return hapiTest(
                cryptoCreate(htsCollector),
                cryptoCreate(tokenOwner).balance(ONE_MILLION_HBARS),
                cryptoCreate(tokenReceiver),
                cryptoCreate(tokenTreasury),
                tokenCreate(feeDenom).treasury(tokenOwner).initialSupply(tokenTotal),
                tokenAssociate(htsCollector, feeDenom),
                tokenCreate(fungibleToken)
                        .treasury(tokenTreasury)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .initialSupply(tokenTotal)
                        .withCustom(fixedHtsFee(htsFee, feeDenom, htsCollector)),
                tokenAssociate(tokenReceiver, fungibleToken),
                tokenAssociate(tokenOwner, fungibleToken),
                cryptoTransfer(moving(1000, fungibleToken).between(tokenTreasury, tokenOwner)),
                cryptoTransfer(moving(1, fungibleToken).between(tokenOwner, tokenReceiver))
                        .fee(ONE_HUNDRED_HBARS)
                        .payingWith(tokenOwner),
                getAccountBalance(tokenOwner)
                        .hasTokenBalance(fungibleToken, 999)
                        .hasTokenBalance(feeDenom, tokenTotal - htsFee),
                getAccountBalance(tokenReceiver).hasTokenBalance(fungibleToken, 1),
                getAccountBalance(htsCollector).hasTokenBalance(feeDenom, htsFee));
    }

    @HapiTest
    final Stream<DynamicTest> transferFungibleWithFixedHtsCustomFeeNotEnoughBalanceFeeToken() {
        return hapiTest(
                cryptoCreate(htsCollector),
                cryptoCreate(tokenOwner).balance(ONE_MILLION_HBARS),
                cryptoCreate(tokenReceiver),
                cryptoCreate(tokenTreasury),
                tokenCreate(feeDenom).treasury(tokenOwner).initialSupply(1),
                tokenAssociate(htsCollector, feeDenom),
                tokenCreate(fungibleToken)
                        .treasury(tokenTreasury)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .initialSupply(tokenTotal)
                        .withCustom(fixedHtsFee(htsFee, feeDenom, htsCollector)),
                tokenAssociate(tokenReceiver, fungibleToken),
                tokenAssociate(tokenOwner, fungibleToken),
                cryptoTransfer(moving(tokenTotal, fungibleToken).between(tokenTreasury, tokenOwner)),
                cryptoTransfer(moving(1, fungibleToken).between(tokenOwner, tokenReceiver))
                        .payingWith(tokenOwner)
                        .fee(ONE_HUNDRED_HBARS)
                        .hasKnownStatus(INSUFFICIENT_SENDER_ACCOUNT_BALANCE_FOR_CUSTOM_FEE),
                getAccountBalance(tokenOwner)
                        .hasTokenBalance(fungibleToken, tokenTotal)
                        .hasTokenBalance(feeDenom, 1),
                getAccountBalance(tokenReceiver).hasTokenBalance(fungibleToken, 0),
                getAccountBalance(htsCollector).hasTokenBalance(feeDenom, 0));
    }

    @HapiTest
    final Stream<DynamicTest> transferFungibleWithFixedHtsCustomFeeNotEnoughBalanceTransferToken() {
        return hapiTest(
                cryptoCreate(htsCollector),
                cryptoCreate(tokenOwner).balance(ONE_MILLION_HBARS),
                cryptoCreate(tokenReceiver),
                cryptoCreate(tokenTreasury),
                tokenCreate(feeDenom).treasury(tokenOwner).initialSupply(2),
                tokenAssociate(htsCollector, feeDenom),
                tokenCreate(fungibleToken)
                        .treasury(tokenTreasury)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .initialSupply(tokenTotal)
                        .withCustom(fixedHtsFee(2, feeDenom, htsCollector)),
                tokenAssociate(tokenReceiver, fungibleToken),
                tokenAssociate(tokenOwner, fungibleToken),
                cryptoTransfer(moving(2, fungibleToken).between(tokenTreasury, tokenOwner)),
                cryptoTransfer(moving(3, fungibleToken).between(tokenOwner, tokenReceiver))
                        .payingWith(tokenOwner)
                        .fee(ONE_HUNDRED_HBARS)
                        .hasKnownStatus(INSUFFICIENT_TOKEN_BALANCE),
                getAccountBalance(tokenOwner).hasTokenBalance(fungibleToken, 2).hasTokenBalance(feeDenom, 2),
                getAccountBalance(tokenReceiver).hasTokenBalance(fungibleToken, 0),
                getAccountBalance(htsCollector).hasTokenBalance(feeDenom, 0));
    }

    @HapiTest
    final Stream<DynamicTest> transferNonFungibleWithFixedHbarCustomFee() {
        return hapiTest(
                newKeyNamed(NFT_KEY),
                cryptoCreate(hbarCollector).balance(0L),
                cryptoCreate(tokenOwner).balance(ONE_MILLION_HBARS),
                cryptoCreate(tokenReceiver),
                cryptoCreate(tokenTreasury),
                tokenCreate(nonFungibleToken)
                        .treasury(tokenTreasury)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .supplyKey(NFT_KEY)
                        .supplyType(TokenSupplyType.INFINITE)
                        .initialSupply(0)
                        .withCustom(fixedHbarFee(hbarFee, hbarCollector)),
                mintToken(nonFungibleToken, List.of(ByteStringUtils.wrapUnsafely("meta1".getBytes()))),
                tokenAssociate(tokenReceiver, nonFungibleToken),
                tokenAssociate(tokenOwner, nonFungibleToken),
                cryptoTransfer(movingUnique(nonFungibleToken, 1L).between(tokenTreasury, tokenOwner)),
                cryptoTransfer(movingUnique(nonFungibleToken, 1L).between(tokenOwner, tokenReceiver))
                        .fee(ONE_HUNDRED_HBARS)
                        .via("transferTx")
                        .payingWith(tokenOwner),
                withOpContext((spec, log) -> {
                    final var record = getTxnRecord("transferTx");
                    allRunFor(spec, record);
                    final var txFee = record.getResponseRecord().getTransactionFee();

                    final var ownerBalance = getAccountBalance(tokenOwner)
                            .hasTinyBars(ONE_MILLION_HBARS - (txFee + hbarFee))
                            .hasTokenBalance(nonFungibleToken, 0);
                    final var receiverBalance = getAccountBalance(tokenReceiver).hasTokenBalance(nonFungibleToken, 1);
                    final var collectorBalance =
                            getAccountBalance(hbarCollector).hasTinyBars(hbarFee);

                    allRunFor(spec, ownerBalance, receiverBalance, collectorBalance);
                }));
    }

    @HapiTest
    final Stream<DynamicTest> transferNonFungibleWithFixedHbarCustomFeeNotEnoughBalance() {
        return hapiTest(
                newKeyNamed(NFT_KEY),
                cryptoCreate(htsCollector),
                cryptoCreate(hbarCollector).balance(0L),
                cryptoCreate(tokenOwner).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(tokenReceiver),
                cryptoCreate(tokenTreasury),
                tokenCreate(nonFungibleToken)
                        .treasury(tokenTreasury)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .supplyKey(NFT_KEY)
                        .supplyType(TokenSupplyType.INFINITE)
                        .initialSupply(0)
                        .withCustom(fixedHbarFee(THOUSAND_HBAR, hbarCollector)),
                mintToken(nonFungibleToken, List.of(ByteStringUtils.wrapUnsafely("meta1".getBytes()))),
                tokenAssociate(tokenReceiver, nonFungibleToken),
                tokenAssociate(tokenOwner, nonFungibleToken),
                cryptoTransfer(movingUnique(nonFungibleToken, 1L).between(tokenTreasury, tokenOwner)),
                cryptoTransfer(movingUnique(nonFungibleToken, 1L).between(tokenOwner, tokenReceiver))
                        .payingWith(tokenOwner)
                        .fee(ONE_HUNDRED_HBARS)
                        .hasKnownStatus(INSUFFICIENT_SENDER_ACCOUNT_BALANCE_FOR_CUSTOM_FEE),
                getAccountBalance(tokenOwner).hasTokenBalance(nonFungibleToken, 1),
                getAccountBalance(tokenReceiver).hasTokenBalance(nonFungibleToken, 0),
                getAccountBalance(hbarCollector).hasTinyBars(0));
    }

    @HapiTest
    final Stream<DynamicTest> transferNonFungibleWithFixedHtsCustomFee() {
        return hapiTest(
                newKeyNamed(NFT_KEY),
                cryptoCreate(htsCollector),
                cryptoCreate(tokenOwner).balance(ONE_MILLION_HBARS),
                cryptoCreate(tokenReceiver),
                cryptoCreate(tokenTreasury),
                tokenCreate(feeDenom).treasury(tokenOwner).initialSupply(tokenTotal),
                tokenAssociate(htsCollector, feeDenom),
                tokenCreate(nonFungibleToken)
                        .treasury(tokenTreasury)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .supplyKey(NFT_KEY)
                        .supplyType(TokenSupplyType.INFINITE)
                        .initialSupply(0)
                        .withCustom(fixedHtsFee(htsFee, feeDenom, htsCollector)),
                mintToken(nonFungibleToken, List.of(ByteStringUtils.wrapUnsafely("meta1".getBytes()))),
                tokenAssociate(tokenReceiver, nonFungibleToken),
                tokenAssociate(tokenOwner, nonFungibleToken),
                cryptoTransfer(movingUnique(nonFungibleToken, 1L).between(tokenTreasury, tokenOwner)),
                cryptoTransfer(movingUnique(nonFungibleToken, 1L).between(tokenOwner, tokenReceiver))
                        .fee(ONE_HUNDRED_HBARS)
                        .payingWith(tokenOwner),
                getAccountBalance(tokenOwner).hasTokenBalance(nonFungibleToken, 0),
                getAccountBalance(tokenReceiver).hasTokenBalance(nonFungibleToken, 1),
                getAccountBalance(htsCollector).hasTokenBalance(feeDenom, htsFee));
    }

    @HapiTest
    final Stream<DynamicTest> transferNonFungibleWithFixedHtsCustomFeeNotEnoughBalanceFeeToken() {
        return hapiTest(
                newKeyNamed(NFT_KEY),
                cryptoCreate(htsCollector),
                cryptoCreate(hbarCollector).balance(0L),
                cryptoCreate(tokenOwner).balance(ONE_MILLION_HBARS),
                cryptoCreate(tokenReceiver),
                cryptoCreate(tokenTreasury),
                tokenCreate(feeDenom).treasury(tokenOwner).initialSupply(1),
                tokenAssociate(htsCollector, feeDenom),
                tokenCreate(nonFungibleToken)
                        .treasury(tokenTreasury)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .supplyKey(NFT_KEY)
                        .supplyType(TokenSupplyType.INFINITE)
                        .initialSupply(0)
                        .withCustom(fixedHtsFee(2, feeDenom, htsCollector)),
                mintToken(nonFungibleToken, List.of(ByteStringUtils.wrapUnsafely("meta1".getBytes()))),
                tokenAssociate(tokenReceiver, nonFungibleToken),
                tokenAssociate(tokenOwner, nonFungibleToken),
                cryptoTransfer(movingUnique(nonFungibleToken, 1L).between(tokenTreasury, tokenOwner)),
                cryptoTransfer(movingUnique(nonFungibleToken, 1L).between(tokenOwner, tokenReceiver))
                        .payingWith(tokenOwner)
                        .fee(ONE_HUNDRED_HBARS)
                        .hasKnownStatus(INSUFFICIENT_SENDER_ACCOUNT_BALANCE_FOR_CUSTOM_FEE),
                getAccountBalance(tokenOwner)
                        .hasTokenBalance(nonFungibleToken, 1)
                        .hasTokenBalance(feeDenom, 1),
                getAccountBalance(tokenReceiver).hasTokenBalance(nonFungibleToken, 0),
                getAccountBalance(htsCollector).hasTokenBalance(feeDenom, 0));
    }

    @HapiTest
    final Stream<DynamicTest> transferApprovedFungibleWithFixedHbarCustomFee() {
        return hapiTest(
                cryptoCreate(hbarCollector).balance(0L),
                cryptoCreate(tokenOwner).balance(ONE_MILLION_HBARS),
                cryptoCreate(spender).balance(ONE_MILLION_HBARS),
                cryptoCreate(tokenReceiver),
                cryptoCreate(tokenTreasury),
                tokenCreate(fungibleToken)
                        .treasury(tokenTreasury)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .initialSupply(tokenTotal)
                        .withCustom(fixedHbarFee(hbarFee, hbarCollector)),
                tokenAssociate(tokenReceiver, fungibleToken),
                tokenAssociate(tokenOwner, fungibleToken),
                tokenAssociate(spender, fungibleToken),
                cryptoTransfer(moving(tokenTotal, fungibleToken).between(tokenTreasury, tokenOwner)),
                cryptoApproveAllowance()
                        .addTokenAllowance(tokenOwner, fungibleToken, spender, 10L)
                        .fee(ONE_HUNDRED_HBARS)
                        .via("allowanceTx")
                        .payingWith(tokenOwner),
                getAccountDetails(tokenOwner)
                        .has(accountDetailsWith().tokenAllowancesContaining(fungibleToken, spender, 10L)),
                cryptoTransfer(movingWithAllowance(1L, fungibleToken).between(tokenOwner, tokenReceiver))
                        .fee(ONE_HUNDRED_HBARS)
                        .payingWith(spender)
                        .signedBy(spender),
                withOpContext((spec, log) -> {
                    final var allowanceRecord = getTxnRecord("allowanceTx");
                    allRunFor(spec, allowanceRecord);
                    final var allowanceFee = allowanceRecord.getResponseRecord().getTransactionFee();

                    final var ownerBalance = getAccountBalance(tokenOwner)
                            .hasTinyBars(ONE_MILLION_HBARS - (allowanceFee + hbarFee))
                            .hasTokenBalance(fungibleToken, 999);
                    final var spenderBalance = getAccountBalance(spender).hasTokenBalance(fungibleToken, 0);
                    final var receiverBalance = getAccountBalance(tokenReceiver).hasTokenBalance(fungibleToken, 1);
                    final var collectorBalance =
                            getAccountBalance(hbarCollector).hasTinyBars(hbarFee);
                    final var spenderAllowance = getAccountDetails(tokenOwner)
                            .has(accountDetailsWith().tokenAllowancesContaining(fungibleToken, spender, 9L));

                    allRunFor(spec, ownerBalance, spenderBalance, receiverBalance, collectorBalance, spenderAllowance);
                }));
    }

    @HapiTest
    final Stream<DynamicTest> transferApprovedFungibleWithFixedHbarCustomFeeNoAllowance() {
        return hapiTest(
                cryptoCreate(hbarCollector).balance(0L),
                cryptoCreate(tokenOwner).balance(ONE_MILLION_HBARS),
                cryptoCreate(spender).balance(ONE_MILLION_HBARS),
                cryptoCreate(tokenReceiver),
                cryptoCreate(tokenTreasury),
                tokenCreate(fungibleToken)
                        .treasury(tokenTreasury)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .initialSupply(tokenTotal)
                        .withCustom(fixedHbarFee(hbarFee, hbarCollector)),
                tokenAssociate(tokenReceiver, fungibleToken),
                tokenAssociate(tokenOwner, fungibleToken),
                tokenAssociate(spender, fungibleToken),
                cryptoTransfer(moving(tokenTotal, fungibleToken).between(tokenTreasury, tokenOwner)),
                cryptoTransfer(movingWithAllowance(1L, fungibleToken).between(tokenOwner, tokenReceiver))
                        .fee(ONE_HUNDRED_HBARS)
                        .payingWith(spender)
                        .signedBy(spender)
                        .hasKnownStatus(SPENDER_DOES_NOT_HAVE_ALLOWANCE),
                getAccountBalance(tokenOwner).hasTokenBalance(fungibleToken, tokenTotal),
                getAccountBalance(spender).hasTokenBalance(fungibleToken, 0),
                getAccountBalance(tokenReceiver).hasTokenBalance(fungibleToken, 0),
                getAccountBalance(hbarCollector).hasTinyBars(0));
    }

    @HapiTest
    @Tag(MATS)
    final Stream<DynamicTest> transferApprovedFungibleWithFixedHtsCustomFeeAsOwner() {
        return hapiTest(
                cryptoCreate(htsCollector),
                cryptoCreate(tokenOwner).balance(ONE_MILLION_HBARS),
                cryptoCreate(spender).balance(ONE_MILLION_HBARS),
                cryptoCreate(tokenReceiver),
                cryptoCreate(tokenTreasury),
                tokenCreate(feeDenom).treasury(spender).initialSupply(tokenTotal),
                tokenAssociate(htsCollector, feeDenom),
                tokenAssociate(tokenOwner, feeDenom),
                tokenCreate(fungibleToken)
                        .treasury(tokenTreasury)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .initialSupply(tokenTotal)
                        .withCustom(fixedHtsFee(htsFee, feeDenom, htsCollector)),
                tokenAssociate(tokenReceiver, fungibleToken),
                tokenAssociate(tokenOwner, fungibleToken),
                tokenAssociate(spender, fungibleToken),
                cryptoTransfer(moving(tokenTotal, fungibleToken).between(tokenTreasury, tokenOwner)),
                cryptoTransfer(moving(200L, feeDenom).between(spender, tokenOwner)),
                cryptoApproveAllowance()
                        .addTokenAllowance(tokenOwner, fungibleToken, spender, 10L)
                        .fee(ONE_HUNDRED_HBARS)
                        .payingWith(tokenOwner),
                getAccountDetails(tokenOwner)
                        .has(accountDetailsWith().tokenAllowancesContaining(fungibleToken, spender, 10L)),
                cryptoTransfer(movingWithAllowance(1L, fungibleToken).between(tokenOwner, tokenReceiver))
                        .fee(ONE_HUNDRED_HBARS)
                        .payingWith(spender)
                        .signedBy(spender),
                getAccountDetails(tokenOwner)
                        .has(accountDetailsWith().tokenAllowancesContaining(fungibleToken, spender, 9L)),
                getAccountBalance(tokenOwner)
                        .hasTokenBalance(fungibleToken, 999)
                        .hasTokenBalance(feeDenom, 200L - htsFee),
                getAccountBalance(spender).hasTokenBalance(fungibleToken, 0).hasTokenBalance(feeDenom, 800L),
                getAccountBalance(tokenReceiver).hasTokenBalance(fungibleToken, 1),
                getAccountBalance(htsCollector).hasTokenBalance(feeDenom, htsFee));
    }

    @HapiTest
    final Stream<DynamicTest> transferApprovedFungibleWithFixedHtsCustomFeeAsSpender() {
        return hapiTest(
                cryptoCreate(htsCollector),
                cryptoCreate(tokenOwner).balance(ONE_MILLION_HBARS),
                cryptoCreate(spender).balance(ONE_MILLION_HBARS),
                cryptoCreate(tokenReceiver),
                cryptoCreate(tokenTreasury),
                tokenCreate(feeDenom).treasury(tokenOwner).initialSupply(tokenTotal),
                tokenAssociate(htsCollector, feeDenom),
                tokenAssociate(spender, feeDenom),
                tokenCreate(fungibleToken)
                        .treasury(tokenTreasury)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .initialSupply(tokenTotal)
                        .withCustom(fixedHtsFee(htsFee, feeDenom, htsCollector)),
                tokenAssociate(tokenReceiver, fungibleToken),
                tokenAssociate(tokenOwner, fungibleToken),
                tokenAssociate(spender, fungibleToken),
                cryptoTransfer(moving(tokenTotal, fungibleToken).between(tokenTreasury, tokenOwner)),
                cryptoApproveAllowance()
                        .addTokenAllowance(tokenOwner, fungibleToken, spender, 10L)
                        .fee(ONE_HUNDRED_HBARS)
                        .signedBy(tokenOwner)
                        .payingWith(tokenOwner),
                getAccountDetails(tokenOwner)
                        .has(accountDetailsWith().tokenAllowancesContaining(fungibleToken, spender, 10L)),
                cryptoTransfer(movingWithAllowance(1L, fungibleToken).between(tokenOwner, tokenReceiver))
                        .fee(ONE_HUNDRED_HBARS)
                        .payingWith(spender)
                        .signedBy(spender),
                getAccountDetails(tokenOwner)
                        .has(accountDetailsWith().tokenAllowancesContaining(fungibleToken, spender, 9L)),
                getAccountBalance(tokenOwner)
                        .hasTokenBalance(fungibleToken, 999)
                        .hasTokenBalance(feeDenom, tokenTotal - htsFee),
                getAccountBalance(spender).hasTokenBalance(fungibleToken, 0).hasTokenBalance(feeDenom, 0),
                getAccountBalance(tokenReceiver).hasTokenBalance(fungibleToken, 1),
                getAccountBalance(htsCollector).hasTokenBalance(feeDenom, htsFee));
    }

    @HapiTest
    final Stream<DynamicTest> transferApprovedFungibleWithFixedHtsCustomFeeNoAllowance() {
        return hapiTest(
                cryptoCreate(htsCollector),
                cryptoCreate(tokenOwner).balance(ONE_MILLION_HBARS),
                cryptoCreate(spender).balance(ONE_MILLION_HBARS),
                cryptoCreate(tokenReceiver),
                cryptoCreate(tokenTreasury),
                tokenCreate(feeDenom).treasury(tokenOwner).initialSupply(tokenTotal),
                tokenAssociate(htsCollector, feeDenom),
                tokenAssociate(spender, feeDenom),
                tokenCreate(fungibleToken)
                        .treasury(tokenTreasury)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .initialSupply(tokenTotal)
                        .withCustom(fixedHtsFee(htsFee, feeDenom, htsCollector)),
                tokenAssociate(tokenReceiver, fungibleToken),
                tokenAssociate(tokenOwner, fungibleToken),
                tokenAssociate(spender, fungibleToken),
                cryptoTransfer(moving(tokenTotal, fungibleToken).between(tokenTreasury, tokenOwner)),
                cryptoTransfer(movingWithAllowance(1L, fungibleToken).between(tokenOwner, tokenReceiver))
                        .fee(ONE_HUNDRED_HBARS)
                        .payingWith(spender)
                        .signedBy(spender)
                        .hasKnownStatus(SPENDER_DOES_NOT_HAVE_ALLOWANCE),
                getAccountBalance(tokenOwner)
                        .hasTokenBalance(fungibleToken, tokenTotal)
                        .hasTokenBalance(feeDenom, tokenTotal),
                getAccountBalance(spender).hasTokenBalance(fungibleToken, 0).hasTokenBalance(feeDenom, 0),
                getAccountBalance(tokenReceiver).hasTokenBalance(fungibleToken, 0),
                getAccountBalance(htsCollector).hasTokenBalance(feeDenom, 0));
    }

    @HapiTest
    final Stream<DynamicTest> transferApprovedNonFungibleWithFixedHbarCustomFee() {
        return hapiTest(
                newKeyNamed(NFT_KEY),
                cryptoCreate(hbarCollector).balance(0L),
                cryptoCreate(tokenOwner).balance(ONE_MILLION_HBARS),
                cryptoCreate(spender).balance(ONE_MILLION_HBARS),
                cryptoCreate(tokenReceiver),
                cryptoCreate(tokenTreasury),
                tokenCreate(nonFungibleToken)
                        .treasury(tokenTreasury)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .supplyKey(NFT_KEY)
                        .supplyType(TokenSupplyType.INFINITE)
                        .initialSupply(0)
                        .withCustom(fixedHbarFee(hbarFee, hbarCollector)),
                mintToken(nonFungibleToken, List.of(ByteStringUtils.wrapUnsafely("meta1".getBytes()))),
                tokenAssociate(tokenReceiver, nonFungibleToken),
                tokenAssociate(tokenOwner, nonFungibleToken),
                tokenAssociate(spender, nonFungibleToken),
                cryptoTransfer(movingUnique(nonFungibleToken, 1L).between(tokenTreasury, tokenOwner)),
                cryptoApproveAllowance()
                        .addNftAllowance(tokenOwner, nonFungibleToken, spender, false, List.of(1L))
                        .fee(ONE_HUNDRED_HBARS)
                        .via("allowanceTx")
                        .payingWith(tokenOwner),
                cryptoTransfer(movingUniqueWithAllowance(nonFungibleToken, 1L).between(tokenOwner, tokenReceiver))
                        .fee(ONE_HUNDRED_HBARS)
                        .payingWith(spender)
                        .signedBy(spender),
                withOpContext((spec, log) -> {
                    final var allowanceRecord = getTxnRecord("allowanceTx");
                    allRunFor(spec, allowanceRecord);
                    final var allowanceFee = allowanceRecord.getResponseRecord().getTransactionFee();

                    final var ownerBalance = getAccountBalance(tokenOwner)
                            .hasTinyBars(ONE_MILLION_HBARS - (allowanceFee + hbarFee))
                            .hasTokenBalance(nonFungibleToken, 0);
                    final var spenderBalance = getAccountBalance(spender).hasTokenBalance(nonFungibleToken, 0);
                    final var receiverBalance = getAccountBalance(tokenReceiver).hasTokenBalance(nonFungibleToken, 1);
                    final var collectorBalance =
                            getAccountBalance(hbarCollector).hasTinyBars(hbarFee);

                    allRunFor(spec, ownerBalance, spenderBalance, receiverBalance, collectorBalance);
                }));
    }

    @HapiTest
    final Stream<DynamicTest> transferApprovedNonFungibleWithFixedHbarCustomFeeNoAllowance() {
        return hapiTest(
                newKeyNamed(NFT_KEY),
                cryptoCreate(hbarCollector).balance(0L),
                cryptoCreate(tokenOwner).balance(ONE_MILLION_HBARS),
                cryptoCreate(spender).balance(ONE_MILLION_HBARS),
                cryptoCreate(tokenReceiver),
                cryptoCreate(tokenTreasury),
                tokenCreate(nonFungibleToken)
                        .treasury(tokenTreasury)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .supplyKey(NFT_KEY)
                        .supplyType(TokenSupplyType.INFINITE)
                        .initialSupply(0)
                        .withCustom(fixedHbarFee(hbarFee, hbarCollector)),
                mintToken(nonFungibleToken, List.of(ByteStringUtils.wrapUnsafely("meta1".getBytes()))),
                tokenAssociate(tokenReceiver, nonFungibleToken),
                tokenAssociate(tokenOwner, nonFungibleToken),
                tokenAssociate(spender, nonFungibleToken),
                cryptoTransfer(movingUnique(nonFungibleToken, 1L).between(tokenTreasury, tokenOwner)),
                cryptoTransfer(movingUniqueWithAllowance(nonFungibleToken, 1L).between(tokenOwner, tokenReceiver))
                        .fee(ONE_HUNDRED_HBARS)
                        .payingWith(spender)
                        .signedBy(spender)
                        .hasKnownStatus(SPENDER_DOES_NOT_HAVE_ALLOWANCE),
                getAccountBalance(tokenOwner).hasTokenBalance(nonFungibleToken, 1),
                getAccountBalance(spender).hasTokenBalance(nonFungibleToken, 0),
                getAccountBalance(tokenReceiver).hasTokenBalance(nonFungibleToken, 0),
                getAccountBalance(hbarCollector).hasTinyBars(0));
    }

    @HapiTest
    final Stream<DynamicTest> transferApprovedNonFungibleWithFixedHtsCustomFeeAsOwner() {
        return hapiTest(
                newKeyNamed(NFT_KEY),
                cryptoCreate(htsCollector),
                cryptoCreate(tokenOwner).balance(ONE_MILLION_HBARS),
                cryptoCreate(spender).balance(ONE_MILLION_HBARS),
                cryptoCreate(tokenReceiver),
                cryptoCreate(tokenTreasury),
                tokenCreate(feeDenom).treasury(spender).initialSupply(tokenTotal),
                tokenAssociate(htsCollector, feeDenom),
                tokenAssociate(tokenOwner, feeDenom),
                tokenCreate(nonFungibleToken)
                        .treasury(tokenTreasury)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .supplyKey(NFT_KEY)
                        .supplyType(TokenSupplyType.INFINITE)
                        .initialSupply(0)
                        .withCustom(fixedHtsFee(htsFee, feeDenom, htsCollector)),
                mintToken(nonFungibleToken, List.of(ByteStringUtils.wrapUnsafely("meta1".getBytes()))),
                tokenAssociate(tokenReceiver, nonFungibleToken),
                tokenAssociate(tokenOwner, nonFungibleToken),
                tokenAssociate(spender, nonFungibleToken),
                cryptoTransfer(movingUnique(nonFungibleToken, 1L).between(tokenTreasury, tokenOwner)),
                cryptoTransfer(moving(200L, feeDenom).between(spender, tokenOwner)),
                cryptoApproveAllowance()
                        .addNftAllowance(tokenOwner, nonFungibleToken, spender, false, List.of(1L))
                        .fee(ONE_HUNDRED_HBARS)
                        .payingWith(tokenOwner),
                cryptoTransfer(movingUniqueWithAllowance(nonFungibleToken, 1L).between(tokenOwner, tokenReceiver))
                        .fee(ONE_HUNDRED_HBARS)
                        .payingWith(spender)
                        .signedBy(spender),
                getAccountBalance(tokenOwner)
                        .hasTokenBalance(nonFungibleToken, 0)
                        .hasTokenBalance(feeDenom, 200L - htsFee),
                getAccountBalance(spender).hasTokenBalance(nonFungibleToken, 0).hasTokenBalance(feeDenom, 800L),
                getAccountBalance(tokenReceiver).hasTokenBalance(nonFungibleToken, 1),
                getAccountBalance(htsCollector).hasTokenBalance(feeDenom, htsFee));
    }

    @HapiTest
    @Tag(MATS)
    final Stream<DynamicTest> transferApprovedNonFungibleWithFixedHtsCustomFeeAsSpender() {
        return hapiTest(
                newKeyNamed(NFT_KEY),
                cryptoCreate(htsCollector),
                cryptoCreate(tokenOwner).balance(ONE_MILLION_HBARS),
                cryptoCreate(spender).balance(ONE_MILLION_HBARS),
                cryptoCreate(tokenReceiver),
                cryptoCreate(tokenTreasury),
                tokenCreate(feeDenom).treasury(tokenOwner).initialSupply(tokenTotal),
                tokenAssociate(htsCollector, feeDenom),
                tokenAssociate(spender, feeDenom),
                tokenCreate(nonFungibleToken)
                        .treasury(tokenTreasury)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .supplyKey(NFT_KEY)
                        .supplyType(TokenSupplyType.INFINITE)
                        .initialSupply(0)
                        .withCustom(fixedHtsFee(htsFee, feeDenom, htsCollector)),
                mintToken(nonFungibleToken, List.of(ByteStringUtils.wrapUnsafely("meta1".getBytes()))),
                tokenAssociate(tokenReceiver, nonFungibleToken),
                tokenAssociate(tokenOwner, nonFungibleToken),
                tokenAssociate(spender, nonFungibleToken),
                cryptoTransfer(movingUnique(nonFungibleToken, 1L).between(tokenTreasury, tokenOwner)),
                cryptoApproveAllowance()
                        .addNftAllowance(tokenOwner, nonFungibleToken, spender, false, List.of(1L))
                        .fee(ONE_HUNDRED_HBARS)
                        .payingWith(tokenOwner),
                cryptoTransfer(movingUniqueWithAllowance(nonFungibleToken, 1L).between(tokenOwner, tokenReceiver))
                        .fee(ONE_HUNDRED_HBARS)
                        .payingWith(spender)
                        .signedBy(spender),
                getAccountBalance(tokenOwner)
                        .hasTokenBalance(nonFungibleToken, 0)
                        .hasTokenBalance(feeDenom, tokenTotal - htsFee),
                getAccountBalance(spender).hasTokenBalance(nonFungibleToken, 0).hasTokenBalance(feeDenom, 0),
                getAccountBalance(tokenReceiver).hasTokenBalance(nonFungibleToken, 1),
                getAccountBalance(htsCollector).hasTokenBalance(feeDenom, htsFee));
    }

    @HapiTest
    final Stream<DynamicTest> transferApprovedNonFungibleWithFixedHtsCustomFeeNoAllowance() {
        return hapiTest(
                newKeyNamed(NFT_KEY),
                cryptoCreate(htsCollector),
                cryptoCreate(tokenOwner).balance(ONE_MILLION_HBARS),
                cryptoCreate(spender).balance(ONE_MILLION_HBARS),
                cryptoCreate(tokenReceiver),
                cryptoCreate(tokenTreasury),
                tokenCreate(feeDenom).treasury(tokenOwner).initialSupply(tokenTotal),
                tokenAssociate(htsCollector, feeDenom),
                tokenAssociate(spender, feeDenom),
                tokenCreate(nonFungibleToken)
                        .treasury(tokenTreasury)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .supplyKey(NFT_KEY)
                        .supplyType(TokenSupplyType.INFINITE)
                        .initialSupply(0)
                        .withCustom(fixedHtsFee(htsFee, feeDenom, htsCollector)),
                mintToken(nonFungibleToken, List.of(ByteStringUtils.wrapUnsafely("meta1".getBytes()))),
                tokenAssociate(tokenReceiver, nonFungibleToken),
                tokenAssociate(tokenOwner, nonFungibleToken),
                tokenAssociate(spender, nonFungibleToken),
                cryptoTransfer(movingUnique(nonFungibleToken, 1L).between(tokenTreasury, tokenOwner)),
                cryptoTransfer(movingUniqueWithAllowance(nonFungibleToken, 1L).between(tokenOwner, tokenReceiver))
                        .fee(ONE_HUNDRED_HBARS)
                        .payingWith(spender)
                        .signedBy(spender)
                        .hasKnownStatus(SPENDER_DOES_NOT_HAVE_ALLOWANCE),
                getAccountBalance(tokenOwner)
                        .hasTokenBalance(nonFungibleToken, 1)
                        .hasTokenBalance(feeDenom, tokenTotal),
                getAccountBalance(spender).hasTokenBalance(nonFungibleToken, 0).hasTokenBalance(feeDenom, 0),
                getAccountBalance(tokenReceiver).hasTokenBalance(nonFungibleToken, 0),
                getAccountBalance(htsCollector).hasTokenBalance(feeDenom, 0));
    }

    @HapiTest
    final Stream<DynamicTest> transferFungibleWithThreeFixedHtsCustomFeesWithoutAllCollectorsExempt() {
        final long amountToSend = 400L;
        return hapiTest(
                cryptoCreate(alice).balance(0L),
                cryptoCreate(bob).balance(0L),
                cryptoCreate(carol).balance(0L),
                cryptoCreate(tokenOwner).balance(ONE_MILLION_HBARS),
                cryptoCreate(tokenReceiver),
                cryptoCreate(tokenTreasury),
                tokenCreate(feeDenom).treasury(tokenOwner).initialSupply(tokenTotal * 10L),
                tokenAssociate(alice, feeDenom),
                tokenAssociate(bob, feeDenom),
                tokenAssociate(carol, feeDenom),
                tokenCreate(fungibleToken)
                        .treasury(tokenTreasury)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .initialSupply(tokenTotal)
                        .withCustom(fixedHtsFee(aliceFee, feeDenom, alice))
                        .withCustom(fixedHtsFee(bobFee, feeDenom, bob))
                        .withCustom(fixedHtsFee(carolFee, feeDenom, carol)),
                tokenAssociate(tokenReceiver, fungibleToken),
                tokenAssociate(tokenOwner, fungibleToken),
                tokenAssociate(alice, fungibleToken),
                tokenAssociate(bob, fungibleToken),
                tokenAssociate(carol, fungibleToken),
                cryptoTransfer(moving(1000, feeDenom).between(tokenOwner, alice)),
                cryptoTransfer(moving(1000, feeDenom).between(tokenOwner, bob)),
                cryptoTransfer(moving(1000, feeDenom).between(tokenOwner, carol)),
                cryptoTransfer(moving(amountToSend, fungibleToken).between(tokenTreasury, alice)),
                cryptoTransfer(moving(amountToSend / 2, fungibleToken).between(alice, bob)),
                cryptoTransfer(moving(amountToSend / 4, fungibleToken).between(bob, carol)),
                getAccountBalance(alice).hasTokenBalance(fungibleToken, 200).hasTokenBalance(feeDenom, 600),
                getAccountBalance(bob).hasTokenBalance(fungibleToken, 100).hasTokenBalance(feeDenom, 800),
                getAccountBalance(carol).hasTokenBalance(fungibleToken, 100).hasTokenBalance(feeDenom, 1600));
    }

    @HapiTest
    final Stream<DynamicTest> transferFungibleWithThreeFixedHtsCustomFeesWithAllCollectorsExempt() {
        final long amountToSend = 400L;
        return hapiTest(
                cryptoCreate(alice).balance(0L),
                cryptoCreate(bob).balance(0L),
                cryptoCreate(carol).balance(0L),
                cryptoCreate(tokenOwner).balance(ONE_MILLION_HBARS),
                cryptoCreate(tokenReceiver),
                cryptoCreate(tokenTreasury),
                tokenCreate(feeDenom).treasury(tokenOwner).initialSupply(tokenTotal * 10L),
                tokenAssociate(alice, feeDenom),
                tokenAssociate(bob, feeDenom),
                tokenAssociate(carol, feeDenom),
                tokenCreate(fungibleToken)
                        .treasury(tokenTreasury)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .initialSupply(tokenTotal)
                        .withCustom(fixedHtsFee(aliceFee, feeDenom, alice, true))
                        .withCustom(fixedHtsFee(bobFee, feeDenom, bob, true))
                        .withCustom(fixedHtsFee(carolFee, feeDenom, carol, true)),
                tokenAssociate(tokenReceiver, fungibleToken),
                tokenAssociate(tokenOwner, fungibleToken),
                tokenAssociate(alice, fungibleToken),
                tokenAssociate(bob, fungibleToken),
                tokenAssociate(carol, fungibleToken),
                cryptoTransfer(moving(1000, feeDenom).between(tokenOwner, alice)),
                cryptoTransfer(moving(1000, feeDenom).between(tokenOwner, bob)),
                cryptoTransfer(moving(1000, feeDenom).between(tokenOwner, carol)),
                cryptoTransfer(moving(amountToSend, fungibleToken).between(tokenTreasury, alice)),
                cryptoTransfer(moving(amountToSend / 2, fungibleToken).between(alice, bob)),
                cryptoTransfer(moving(amountToSend / 4, fungibleToken).between(bob, carol)),
                getAccountBalance(alice).hasTokenBalance(fungibleToken, 200).hasTokenBalance(feeDenom, 1000),
                getAccountBalance(bob).hasTokenBalance(fungibleToken, 100).hasTokenBalance(feeDenom, 1000),
                getAccountBalance(carol).hasTokenBalance(fungibleToken, 100).hasTokenBalance(feeDenom, 1000));
    }

    @HapiTest
    @Tag(MATS)
    final Stream<DynamicTest> transferFungibleWithFixedHtsCustomFees2Layers() {
        return hapiTest(
                cryptoCreate(htsCollector),
                cryptoCreate(htsCollector2),
                cryptoCreate(tokenOwner).balance(ONE_MILLION_HBARS),
                cryptoCreate(tokenReceiver),
                cryptoCreate(tokenTreasury),
                tokenCreate(feeDenom).treasury(tokenOwner).initialSupply(tokenTotal),
                tokenAssociate(htsCollector, feeDenom),
                tokenCreate(fungibleToken)
                        .treasury(tokenTreasury)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .initialSupply(tokenTotal)
                        .withCustom(fixedHtsFee(htsFee, feeDenom, htsCollector)),
                tokenAssociate(htsCollector2, fungibleToken),
                tokenCreate(fungibleToken2)
                        .treasury(tokenTreasury)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .initialSupply(tokenTotal)
                        .withCustom(fixedHtsFee(htsFee, fungibleToken, htsCollector2)),
                tokenAssociate(tokenReceiver, fungibleToken),
                tokenAssociate(tokenOwner, fungibleToken),
                tokenAssociate(tokenReceiver, fungibleToken2),
                tokenAssociate(tokenOwner, fungibleToken2),
                cryptoTransfer(
                        moving(tokenTotal, fungibleToken2).between(tokenTreasury, tokenOwner),
                        moving(tokenTotal, fungibleToken).between(tokenTreasury, tokenOwner)),
                cryptoTransfer(moving(1, fungibleToken2).between(tokenOwner, tokenReceiver))
                        .fee(ONE_HUNDRED_HBARS)
                        .payingWith(tokenOwner),
                getAccountBalance(tokenOwner)
                        .hasTokenBalance(fungibleToken2, 999)
                        .hasTokenBalance(fungibleToken, tokenTotal - htsFee)
                        .hasTokenBalance(feeDenom, tokenTotal - htsFee),
                getAccountBalance(tokenReceiver).hasTokenBalance(fungibleToken2, 1),
                getAccountBalance(htsCollector).hasTokenBalance(feeDenom, htsFee),
                getAccountBalance(htsCollector2).hasTokenBalance(fungibleToken, htsFee));
    }

    @HapiTest
    final Stream<DynamicTest> transferNonFungibleWithFixedHtsCustomFees2Layers() {
        return hapiTest(
                newKeyNamed(NFT_KEY),
                cryptoCreate(htsCollector),
                cryptoCreate(htsCollector2),
                cryptoCreate(tokenOwner).balance(ONE_MILLION_HBARS),
                cryptoCreate(tokenReceiver),
                cryptoCreate(tokenTreasury),
                tokenCreate(feeDenom).treasury(tokenOwner).initialSupply(tokenTotal),
                tokenAssociate(htsCollector, feeDenom),
                tokenCreate(fungibleToken)
                        .treasury(tokenTreasury)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .initialSupply(tokenTotal)
                        .withCustom(fixedHtsFee(htsFee, feeDenom, htsCollector)),
                tokenAssociate(htsCollector2, fungibleToken),
                tokenCreate(nonFungibleToken)
                        .treasury(tokenTreasury)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .supplyKey(NFT_KEY)
                        .supplyType(TokenSupplyType.INFINITE)
                        .initialSupply(0)
                        .withCustom(fixedHtsFee(htsFee, fungibleToken, htsCollector2)),
                mintToken(nonFungibleToken, List.of(ByteStringUtils.wrapUnsafely("meta1".getBytes()))),
                tokenAssociate(tokenReceiver, fungibleToken),
                tokenAssociate(tokenOwner, fungibleToken),
                tokenAssociate(tokenReceiver, nonFungibleToken),
                tokenAssociate(tokenOwner, nonFungibleToken),
                cryptoTransfer(
                        movingUnique(nonFungibleToken, 1L).between(tokenTreasury, tokenOwner),
                        moving(tokenTotal, fungibleToken).between(tokenTreasury, tokenOwner)),
                cryptoTransfer(movingUnique(nonFungibleToken, 1L).between(tokenOwner, tokenReceiver))
                        .fee(ONE_HUNDRED_HBARS)
                        .payingWith(tokenOwner),
                getAccountBalance(tokenOwner)
                        .hasTokenBalance(nonFungibleToken, 0)
                        .hasTokenBalance(fungibleToken, tokenTotal - htsFee)
                        .hasTokenBalance(feeDenom, tokenTotal - htsFee),
                getAccountBalance(tokenReceiver).hasTokenBalance(nonFungibleToken, 1),
                getAccountBalance(htsCollector).hasTokenBalance(feeDenom, htsFee),
                getAccountBalance(htsCollector2).hasTokenBalance(fungibleToken, htsFee));
    }

    @HapiTest
    final Stream<DynamicTest> transferFungibleWithFixedHtsCustomFees3LayersShouldFail() {
        return hapiTest(
                cryptoCreate(htsCollector),
                cryptoCreate(htsCollector2),
                cryptoCreate(tokenOwner).balance(ONE_MILLION_HBARS),
                cryptoCreate(tokenReceiver),
                cryptoCreate(tokenTreasury),
                tokenCreate(feeDenom).treasury(tokenTreasury).initialSupply(tokenTotal),
                tokenAssociate(htsCollector, feeDenom),
                tokenAssociate(tokenOwner, feeDenom),
                tokenCreate(feeDenom2)
                        .treasury(tokenTreasury)
                        .initialSupply(tokenTotal)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .initialSupply(tokenTotal)
                        .withCustom(fixedHtsFee(htsFee, feeDenom, htsCollector)),
                tokenAssociate(htsCollector, feeDenom2),
                tokenAssociate(tokenOwner, feeDenom2),
                tokenCreate(fungibleToken)
                        .treasury(tokenTreasury)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .initialSupply(tokenTotal)
                        .withCustom(fixedHtsFee(htsFee, feeDenom2, htsCollector)),
                tokenAssociate(htsCollector2, fungibleToken),
                tokenCreate(fungibleToken2)
                        .treasury(tokenTreasury)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .initialSupply(tokenTotal)
                        .withCustom(fixedHtsFee(htsFee, fungibleToken, htsCollector2)),
                tokenAssociate(tokenOwner, fungibleToken),
                tokenAssociate(tokenReceiver, fungibleToken2),
                tokenAssociate(tokenOwner, fungibleToken2),
                cryptoTransfer(
                        moving(tokenTotal, fungibleToken).between(tokenTreasury, tokenOwner),
                        moving(tokenTotal, fungibleToken2).between(tokenTreasury, tokenOwner)),
                cryptoTransfer(moving(1, fungibleToken2).between(tokenOwner, tokenReceiver))
                        .fee(ONE_HUNDRED_HBARS)
                        .payingWith(tokenOwner)
                        .hasKnownStatus(CUSTOM_FEE_CHARGING_EXCEEDED_MAX_RECURSION_DEPTH));
    }

    @HapiTest
    final Stream<DynamicTest> transferNonFungibleWithFixedHtsCustomFees3LayersShouldFail() {
        return hapiTest(
                newKeyNamed(NFT_KEY),
                cryptoCreate(htsCollector),
                cryptoCreate(htsCollector2),
                cryptoCreate(tokenOwner).balance(ONE_MILLION_HBARS),
                cryptoCreate(tokenReceiver),
                cryptoCreate(tokenTreasury),
                tokenCreate(feeDenom).treasury(tokenTreasury).initialSupply(tokenTotal),
                tokenAssociate(htsCollector, feeDenom),
                tokenAssociate(tokenOwner, feeDenom),
                tokenCreate(feeDenom2)
                        .treasury(tokenTreasury)
                        .initialSupply(tokenTotal)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .initialSupply(tokenTotal)
                        .withCustom(fixedHtsFee(htsFee, feeDenom, htsCollector)),
                tokenAssociate(htsCollector, feeDenom2),
                tokenAssociate(tokenOwner, feeDenom2),
                tokenCreate(fungibleToken)
                        .treasury(tokenTreasury)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .initialSupply(tokenTotal)
                        .withCustom(fixedHtsFee(htsFee, feeDenom2, htsCollector)),
                tokenAssociate(htsCollector2, fungibleToken),
                tokenCreate(nonFungibleToken)
                        .treasury(tokenTreasury)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .supplyKey(NFT_KEY)
                        .supplyType(TokenSupplyType.INFINITE)
                        .initialSupply(0)
                        .withCustom(fixedHtsFee(htsFee, fungibleToken, htsCollector2)),
                mintToken(nonFungibleToken, List.of(ByteStringUtils.wrapUnsafely("meta1".getBytes()))),
                tokenAssociate(tokenOwner, fungibleToken),
                tokenAssociate(tokenReceiver, nonFungibleToken),
                tokenAssociate(tokenOwner, nonFungibleToken),
                cryptoTransfer(
                        moving(tokenTotal, fungibleToken).between(tokenTreasury, tokenOwner),
                        movingUnique(nonFungibleToken, 1L).between(tokenTreasury, tokenOwner)),
                cryptoTransfer(movingUnique(nonFungibleToken, 1L).between(tokenOwner, tokenReceiver))
                        .fee(ONE_HUNDRED_HBARS)
                        .payingWith(tokenOwner)
                        .hasKnownStatus(CUSTOM_FEE_CHARGING_EXCEEDED_MAX_RECURSION_DEPTH));
    }

    @HapiTest
    final Stream<DynamicTest> transferMaxFungibleWith10FixedHtsCustomFees2Layers() {
        final String fungibleToken3 = "fungibleWithCustomFees3";
        final String fungibleToken4 = "fungibleWithCustomFees4";
        final String fungibleToken5 = "fungibleWithCustomFees5";
        final String fungibleToken6 = "fungibleWithCustomFees6";
        final String fungibleToken7 = "fungibleWithCustomFees7";
        final String fungibleToken8 = "fungibleWithCustomFees8";
        final String fungibleToken9 = "fungibleWithCustomFees9";
        final String fungibleToken10 = "fungibleWithCustomFees10";
        final String fungibleToken11 = "fungibleWithCustomFees11";
        final String fungibleToken12 = "fungibleWithCustomFees12";
        final String fungibleToken13 = "fungibleWithCustomFees13";
        final String fungibleToken14 = "fungibleWithCustomFees14";
        final String fungibleToken15 = "fungibleWithCustomFees15";
        final String fungibleToken16 = "fungibleWithCustomFees16";
        final String fungibleToken17 = "fungibleWithCustomFees17";
        final String fungibleToken18 = "fungibleWithCustomFees18";
        final String fungibleToken19 = "fungibleWithCustomFees19";
        final String fungibleToken20 = "fungibleWithCustomFees20";
        final var specificTokenTotal = 2 * tokenTotal;

        List<String> firstLayerCustomFees = List.of(
                fungibleToken2,
                fungibleToken3,
                fungibleToken4,
                fungibleToken5,
                fungibleToken6,
                fungibleToken7,
                fungibleToken8,
                fungibleToken9,
                fungibleToken10);
        List<String> secondLayerCustomFees = List.of(
                fungibleToken11,
                fungibleToken12,
                fungibleToken13,
                fungibleToken14,
                fungibleToken15,
                fungibleToken16,
                fungibleToken17,
                fungibleToken18,
                fungibleToken19,
                fungibleToken20);

        return hapiTest(
                withOpContext((spec, log) -> {
                    final ArrayList<SpecOperation> ops = new ArrayList<>();
                    var collectorCreate = cryptoCreate(htsCollector);
                    var collector2Create = cryptoCreate(htsCollector2);
                    var tokenOwnerCreate = cryptoCreate(tokenOwner).balance(ONE_MILLION_HBARS);
                    var tokenReceiverCreate = cryptoCreate(tokenReceiver);
                    var tokenTreasuryCreate = cryptoCreate(tokenTreasury);
                    allRunFor(
                            spec,
                            collectorCreate,
                            collector2Create,
                            tokenOwnerCreate,
                            tokenReceiverCreate,
                            tokenTreasuryCreate);

                    // create all second layer custom fee hts tokens
                    for (String secondLayerCustomFee : secondLayerCustomFees) {
                        ops.add(tokenCreate(secondLayerCustomFee)
                                .treasury(tokenOwner)
                                .initialSupply(specificTokenTotal));
                        ops.add(tokenAssociate(htsCollector, secondLayerCustomFee));
                    }
                    // create all first layer custom fee hts tokens
                    for (String firstLayerCustomFee : firstLayerCustomFees) {
                        ops.add(tokenCreate(firstLayerCustomFee)
                                .treasury(tokenOwner)
                                .tokenType(TokenType.FUNGIBLE_COMMON)
                                .initialSupply(specificTokenTotal)
                                .withCustom(fixedHtsFee(htsFee, fungibleToken11, htsCollector))
                                .withCustom(fixedHtsFee(htsFee, fungibleToken12, htsCollector))
                                .withCustom(fixedHtsFee(htsFee, fungibleToken13, htsCollector))
                                .withCustom(fixedHtsFee(htsFee, fungibleToken14, htsCollector))
                                .withCustom(fixedHtsFee(htsFee, fungibleToken15, htsCollector))
                                .withCustom(fixedHtsFee(htsFee, fungibleToken16, htsCollector))
                                .withCustom(fixedHtsFee(htsFee, fungibleToken17, htsCollector))
                                .withCustom(fixedHtsFee(htsFee, fungibleToken18, htsCollector))
                                .withCustom(fixedHtsFee(htsFee, fungibleToken19, htsCollector))
                                .withCustom(fixedHtsFee(htsFee, fungibleToken20, htsCollector)));

                        ops.add(tokenAssociate(htsCollector2, firstLayerCustomFee));
                    }
                    allRunFor(spec, ops);

                    var fungibleToTransfer = tokenCreate(fungibleToken)
                            .treasury(tokenTreasury)
                            .tokenType(TokenType.FUNGIBLE_COMMON)
                            .initialSupply(9223372036854775807L)
                            .withCustom(fixedHtsFee(htsFee, fungibleToken2, htsCollector2))
                            .withCustom(fixedHtsFee(htsFee, fungibleToken3, htsCollector2))
                            .withCustom(fixedHtsFee(htsFee, fungibleToken4, htsCollector2))
                            .withCustom(fixedHtsFee(htsFee, fungibleToken5, htsCollector2))
                            .withCustom(fixedHtsFee(htsFee, fungibleToken6, htsCollector2))
                            .withCustom(fixedHtsFee(htsFee, fungibleToken7, htsCollector2))
                            .withCustom(fixedHtsFee(htsFee, fungibleToken8, htsCollector2))
                            .withCustom(fixedHtsFee(htsFee, fungibleToken9, htsCollector2))
                            .withCustom(fixedHtsFee(htsFee, fungibleToken10, htsCollector2));
                    var ownerAssociate = tokenAssociate(tokenOwner, fungibleToken);
                    var receiverAssociate = tokenAssociate(tokenReceiver, fungibleToken);
                    var transferToOwner = cryptoTransfer(
                            moving(9223372036854775807L, fungibleToken).between(tokenTreasury, tokenOwner));
                    allRunFor(spec, fungibleToTransfer, ownerAssociate, receiverAssociate, transferToOwner);
                }),
                cryptoTransfer(moving(1, fungibleToken).between(tokenOwner, tokenReceiver))
                        .fee(ONE_HUNDRED_HBARS)
                        .payingWith(tokenOwner),
                getAccountBalance(tokenOwner)
                        .hasTokenBalance(fungibleToken, 9223372036854775806L)
                        .hasTokenBalance(fungibleToken2, specificTokenTotal - htsFee)
                        .hasTokenBalance(fungibleToken3, specificTokenTotal - htsFee)
                        .hasTokenBalance(fungibleToken4, specificTokenTotal - htsFee)
                        .hasTokenBalance(fungibleToken5, specificTokenTotal - htsFee)
                        .hasTokenBalance(fungibleToken6, specificTokenTotal - htsFee)
                        .hasTokenBalance(fungibleToken7, specificTokenTotal - htsFee)
                        .hasTokenBalance(fungibleToken8, specificTokenTotal - htsFee)
                        .hasTokenBalance(fungibleToken9, specificTokenTotal - htsFee)
                        .hasTokenBalance(fungibleToken10, specificTokenTotal - htsFee),
                getAccountBalance(tokenReceiver).hasTokenBalance(fungibleToken, 1),
                getAccountBalance(htsCollector2)
                        .hasTokenBalance(fungibleToken2, htsFee)
                        .hasTokenBalance(fungibleToken3, htsFee)
                        .hasTokenBalance(fungibleToken4, htsFee)
                        .hasTokenBalance(fungibleToken5, htsFee)
                        .hasTokenBalance(fungibleToken6, htsFee)
                        .hasTokenBalance(fungibleToken7, htsFee)
                        .hasTokenBalance(fungibleToken8, htsFee)
                        .hasTokenBalance(fungibleToken9, htsFee)
                        .hasTokenBalance(fungibleToken10, htsFee));
    }

    @HapiTest
    final Stream<DynamicTest> multipleTransfersWithMultipleCustomFees() {
        return hapiTest(
                newKeyNamed(NFT_KEY),
                cryptoCreate(hbarCollector).balance(0L),
                cryptoCreate(htsCollector).balance(ONE_MILLION_HBARS),
                cryptoCreate(htsCollector2),
                cryptoCreate(tokenOwner).balance(ONE_MILLION_HBARS),
                cryptoCreate(tokenTreasury),
                cryptoCreate(alice).balance(ONE_MILLION_HBARS),
                cryptoCreate(bob).balance(ONE_MILLION_HBARS),
                cryptoCreate(carol),
                tokenCreate(feeDenom).treasury(tokenOwner).initialSupply(tokenTotal),
                tokenCreate(feeDenom2).treasury(tokenOwner).initialSupply(tokenTotal),
                tokenAssociate(htsCollector, feeDenom),
                tokenAssociate(htsCollector2, feeDenom2),
                tokenAssociate(alice, feeDenom),
                tokenAssociate(alice, feeDenom2),
                tokenAssociate(bob, feeDenom),
                tokenAssociate(bob, feeDenom2),
                tokenCreate(fungibleToken)
                        .treasury(tokenTreasury)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .initialSupply(tokenTotal)
                        .payingWith(htsCollector)
                        .withCustom(fixedHbarFee(hbarFee, hbarCollector))
                        .withCustom(fixedHtsFee(htsFee, feeDenom, htsCollector))
                        .withCustom(fractionalFee(
                                numerator, denominator, minHtsFee, OptionalLong.of(maxHtsFee), htsCollector)),
                tokenAssociate(alice, fungibleToken),
                tokenAssociate(bob, fungibleToken),
                tokenCreate(nonFungibleToken)
                        .treasury(tokenTreasury)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .supplyKey(NFT_KEY)
                        .supplyType(TokenSupplyType.INFINITE)
                        .initialSupply(0)
                        .withCustom(fixedHtsFee(50L, feeDenom, htsCollector))
                        .withCustom(fixedHtsFee(htsFee, feeDenom2, htsCollector2)),
                mintToken(nonFungibleToken, List.of(ByteStringUtils.wrapUnsafely("meta1".getBytes()))),
                tokenAssociate(alice, nonFungibleToken),
                tokenAssociate(bob, nonFungibleToken),
                tokenAssociate(carol, nonFungibleToken),
                cryptoTransfer(
                        moving(tokenTotal, feeDenom).between(tokenOwner, alice),
                        moving(tokenTotal, feeDenom2).between(tokenOwner, alice),
                        moving(tokenTotal, fungibleToken).between(tokenTreasury, alice),
                        movingUnique(nonFungibleToken, 1L).between(tokenTreasury, alice)),
                // make 2 transfers - one with the same HTS token as custom fee and one with different HTS token
                // as custom fee
                cryptoTransfer(moving(10L, fungibleToken).between(alice, bob))
                        .fee(ONE_HUNDRED_HBARS)
                        .payingWith(alice),
                cryptoTransfer(movingUnique(nonFungibleToken, 1L).between(alice, bob))
                        .fee(ONE_HUNDRED_HBARS)
                        .payingWith(alice),
                // check balances
                getAccountBalance(alice)
                        .hasTokenBalance(fungibleToken, tokenTotal - 10L)
                        .hasTokenBalance(nonFungibleToken, 0)
                        .hasTokenBalance(feeDenom, tokenTotal - htsFee - 50L)
                        .hasTokenBalance(feeDenom2, tokenTotal - htsFee),
                getAccountBalance(bob)
                        .hasTokenBalance(fungibleToken, 8L)
                        .hasTokenBalance(nonFungibleToken, 1)
                        .hasTokenBalance(feeDenom, 0)
                        .hasTokenBalance(feeDenom2, 0),
                getAccountBalance(hbarCollector).hasTinyBars(hbarFee),
                getAccountBalance(htsCollector).hasTokenBalance(feeDenom, htsFee + 50L),
                getAccountBalance(htsCollector2).hasTokenBalance(feeDenom2, htsFee),
                // transfer some hts custom fee tokens to bob as he is a sender and needs to pay with them
                cryptoTransfer(
                        moving(100L, feeDenom).between(alice, bob),
                        moving(200L, feeDenom2).between(alice, bob)),
                // make 2 transfers in a single tx with different senders and receivers
                cryptoTransfer(
                                moving(10L, fungibleToken).between(alice, bob),
                                movingUnique(nonFungibleToken, 1L).between(bob, carol))
                        .fee(ONE_HUNDRED_HBARS)
                        .signedBy(alice, bob)
                        .payingWith(alice),
                // check balances
                getAccountBalance(alice)
                        .hasTokenBalance(fungibleToken, tokenTotal - 2 * 10L)
                        .hasTokenBalance(nonFungibleToken, 0)
                        .hasTokenBalance(feeDenom, tokenTotal - 2 * htsFee - 50L - 100L)
                        .hasTokenBalance(feeDenom2, tokenTotal - htsFee - 200L),
                getAccountBalance(bob)
                        .hasTokenBalance(fungibleToken, 16L)
                        .hasTokenBalance(nonFungibleToken, 0)
                        .hasTokenBalance(feeDenom, 50L)
                        .hasTokenBalance(feeDenom2, 100L),
                getAccountBalance(carol)
                        .hasTokenBalance(fungibleToken, 0L)
                        .hasTokenBalance(nonFungibleToken, 1)
                        .hasTokenBalance(feeDenom, 0)
                        .hasTokenBalance(feeDenom2, 0),
                getAccountBalance(hbarCollector).hasTinyBars(2 * hbarFee),
                getAccountBalance(htsCollector).hasTokenBalance(feeDenom, 2 * htsFee + 2 * 50L),
                getAccountBalance(htsCollector2).hasTokenBalance(feeDenom2, 2 * htsFee));
    }

    @HapiTest
    final Stream<DynamicTest> transferFungibleWithFixedHbarCustomFeeAmount0() {
        return hapiTest(
                cryptoCreate(hbarCollector).balance(0L),
                cryptoCreate(tokenOwner).balance(ONE_MILLION_HBARS),
                cryptoCreate(tokenReceiver),
                cryptoCreate(tokenTreasury),
                tokenCreate(fungibleToken)
                        .treasury(tokenTreasury)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .initialSupply(tokenTotal)
                        .withCustom(fixedHbarFee(0, hbarCollector))
                        .hasKnownStatus(CUSTOM_FEE_MUST_BE_POSITIVE));
    }

    @HapiTest
    final Stream<DynamicTest> transferFungibleWithFixedHtsCustomFeeAmount0() {
        return hapiTest(
                cryptoCreate(htsCollector),
                cryptoCreate(tokenOwner).balance(ONE_MILLION_HBARS),
                cryptoCreate(tokenReceiver),
                cryptoCreate(tokenTreasury),
                tokenCreate(feeDenom).treasury(tokenOwner).initialSupply(tokenTotal),
                tokenAssociate(htsCollector, feeDenom),
                tokenCreate(fungibleToken)
                        .treasury(tokenTreasury)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .initialSupply(tokenTotal)
                        .withCustom(fixedHtsFee(0, feeDenom, htsCollector))
                        .hasKnownStatus(CUSTOM_FEE_MUST_BE_POSITIVE));
    }

    @HapiTest
    final Stream<DynamicTest> transferNonFungibleWithFixedHbarCustomFeeAmount0() {
        return hapiTest(
                newKeyNamed(NFT_KEY),
                cryptoCreate(hbarCollector).balance(0L),
                cryptoCreate(tokenOwner).balance(ONE_MILLION_HBARS),
                cryptoCreate(tokenReceiver),
                cryptoCreate(tokenTreasury),
                tokenCreate(nonFungibleToken)
                        .treasury(tokenTreasury)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .supplyKey(NFT_KEY)
                        .supplyType(TokenSupplyType.INFINITE)
                        .initialSupply(0)
                        .withCustom(fixedHbarFee(0, hbarCollector))
                        .hasKnownStatus(CUSTOM_FEE_MUST_BE_POSITIVE));
    }

    @HapiTest
    final Stream<DynamicTest> transferNonFungibleWithFixedHtsCustomFeeAmount0() {
        return hapiTest(
                newKeyNamed(NFT_KEY),
                cryptoCreate(htsCollector),
                cryptoCreate(tokenOwner).balance(ONE_MILLION_HBARS),
                cryptoCreate(tokenReceiver),
                cryptoCreate(tokenTreasury),
                tokenCreate(feeDenom).treasury(tokenOwner).initialSupply(tokenTotal),
                tokenAssociate(htsCollector, feeDenom),
                tokenCreate(nonFungibleToken)
                        .treasury(tokenTreasury)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .supplyKey(NFT_KEY)
                        .supplyType(TokenSupplyType.INFINITE)
                        .initialSupply(0)
                        .withCustom(fixedHtsFee(0, feeDenom, htsCollector))
                        .hasKnownStatus(CUSTOM_FEE_MUST_BE_POSITIVE));
    }

    @HapiTest
    final Stream<DynamicTest> transferFungibleWithFixedHbarCustomFeeSenderHasOnlyGasAmount() {
        final var gasAmount = 1669096L;
        return customizedHapiTest(
                Map.of("memo.useSpecName", "false"),
                cryptoCreate(hbarCollector).balance(0L),
                cryptoCreate(tokenOwner).balance(gasAmount),
                cryptoCreate(tokenReceiver),
                cryptoCreate(tokenTreasury),
                tokenCreate(fungibleToken)
                        .treasury(tokenTreasury)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .initialSupply(tokenTotal)
                        .withCustom(fixedHbarFee(gasAmount, hbarCollector)),
                tokenAssociate(tokenReceiver, fungibleToken),
                tokenAssociate(tokenOwner, fungibleToken),
                cryptoTransfer(moving(1000, fungibleToken).between(tokenTreasury, tokenOwner)),
                cryptoTransfer(moving(1, fungibleToken).between(tokenOwner, tokenReceiver))
                        .fee(ONE_HUNDRED_HBARS)
                        .payingWith(tokenOwner)
                        .hasKnownStatus(INSUFFICIENT_SENDER_ACCOUNT_BALANCE_FOR_CUSTOM_FEE));
    }

    @HapiTest
    final Stream<DynamicTest> transferFungibleWithFixedHtsCustomFeeTotalSupply0() {
        return hapiTest(
                cryptoCreate(htsCollector),
                cryptoCreate(tokenOwner).balance(ONE_MILLION_HBARS),
                cryptoCreate(spender).balance(ONE_MILLION_HBARS),
                cryptoCreate(tokenReceiver),
                cryptoCreate(tokenTreasury),
                tokenCreate(feeDenom).treasury(tokenOwner).initialSupply(0L),
                tokenAssociate(htsCollector, feeDenom),
                tokenCreate(fungibleToken)
                        .treasury(tokenTreasury)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .initialSupply(tokenTotal)
                        .withCustom(fixedHtsFee(1, feeDenom, htsCollector)),
                tokenAssociate(tokenOwner, fungibleToken),
                tokenAssociate(tokenReceiver, fungibleToken),
                tokenAssociate(spender, fungibleToken),
                cryptoApproveAllowance()
                        .addTokenAllowance(tokenOwner, fungibleToken, spender, 10L)
                        .fee(ONE_HUNDRED_HBARS)
                        .payingWith(tokenOwner),
                cryptoApproveAllowance()
                        .addTokenAllowance(tokenOwner, feeDenom, spender, 10L)
                        .fee(ONE_HUNDRED_HBARS)
                        .payingWith(tokenOwner),
                cryptoTransfer(movingWithAllowance(10L, fungibleToken).between(tokenOwner, tokenReceiver))
                        .fee(ONE_HUNDRED_HBARS)
                        .signedBy(spender)
                        .payingWith(spender)
                        .hasKnownStatus(INSUFFICIENT_TOKEN_BALANCE));
    }

    @HapiTest
    final Stream<DynamicTest> transferFungibleWithFixedHtsCustomFeeNotEnoughForGasAndFee() {
        final var gasAmount = 1669096L;
        return customizedHapiTest(
                Map.of("memo.useSpecName", "false"),
                cryptoCreate(hbarCollector).balance(0L),
                cryptoCreate(tokenOwner).balance(gasAmount),
                cryptoCreate(tokenReceiver),
                cryptoCreate(tokenTreasury),
                tokenCreate(fungibleToken)
                        .treasury(tokenTreasury)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .initialSupply(tokenTotal)
                        .withCustom(fixedHbarFee(ONE_HBAR, hbarCollector)),
                tokenAssociate(tokenReceiver, fungibleToken),
                tokenAssociate(tokenOwner, fungibleToken),
                cryptoTransfer(moving(1000, fungibleToken).between(tokenTreasury, tokenOwner)),
                cryptoTransfer(moving(1, fungibleToken).between(tokenOwner, tokenReceiver))
                        .fee(ONE_HUNDRED_HBARS)
                        .payingWith(tokenOwner)
                        .hasKnownStatus(INSUFFICIENT_SENDER_ACCOUNT_BALANCE_FOR_CUSTOM_FEE));
    }

    @HapiTest
    @Tag(MATS)
    final Stream<DynamicTest> transferWithFractionalCustomFee() {
        return hapiTest(
                cryptoCreate(htsCollector).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(hbarCollector).balance(0L),
                cryptoCreate(tokenReceiver),
                cryptoCreate(tokenTreasury),
                cryptoCreate(tokenOwner).balance(ONE_MILLION_HBARS),
                tokenCreate(feeDenom).treasury(tokenOwner).initialSupply(tokenTotal),
                tokenAssociate(htsCollector, feeDenom),
                tokenCreate(fungibleToken)
                        .treasury(tokenTreasury)
                        .initialSupply(tokenTotal)
                        .payingWith(htsCollector)
                        .withCustom(fixedHbarFee(hbarFee, hbarCollector))
                        .withCustom(fractionalFee(
                                numerator, denominator, minHtsFee, OptionalLong.of(maxHtsFee), htsCollector)),
                tokenAssociate(tokenReceiver, fungibleToken),
                tokenAssociate(tokenOwner, fungibleToken),
                cryptoTransfer(moving(tokenTotal, fungibleToken).between(tokenTreasury, tokenOwner)),
                cryptoTransfer(moving(3, fungibleToken).between(tokenOwner, tokenReceiver))
                        .fee(ONE_HUNDRED_HBARS)
                        .payingWith(tokenOwner),
                getAccountBalance(tokenOwner)
                        .hasTokenBalance(fungibleToken, 997)
                        .hasTokenBalance(feeDenom, tokenTotal),
                getAccountBalance(hbarCollector).hasTinyBars(hbarFee));
    }

    @HapiTest
    final Stream<DynamicTest> transferWithInsufficientCustomFee() {
        return hapiTest(
                cryptoCreate(htsCollector),
                cryptoCreate(hbarCollector).balance(0L),
                cryptoCreate(tokenReceiver),
                cryptoCreate(tokenTreasury),
                cryptoCreate(tokenOwner).balance(ONE_MILLION_HBARS),
                tokenCreate(feeDenom).treasury(tokenOwner).initialSupply(10),
                tokenAssociate(htsCollector, feeDenom),
                tokenCreate(fungibleToken)
                        .treasury(tokenTreasury)
                        .initialSupply(tokenTotal)
                        .withCustom(fixedHtsFee(htsFee, feeDenom, htsCollector)),
                tokenAssociate(tokenReceiver, fungibleToken),
                tokenAssociate(tokenOwner, fungibleToken),
                cryptoTransfer(moving(tokenTotal, fungibleToken).between(tokenTreasury, tokenOwner)),
                cryptoTransfer(moving(1, fungibleToken).between(tokenOwner, tokenReceiver))
                        .fee(ONE_HUNDRED_HBARS)
                        .payingWith(tokenOwner)
                        .hasKnownStatus(INSUFFICIENT_SENDER_ACCOUNT_BALANCE_FOR_CUSTOM_FEE));
    }

    @HapiTest
    final Stream<DynamicTest> transferMultipleTimesWithFixedFeeInHBarShouldVerifyEachTransferIsPaid() {
        return hapiTest(
                cryptoCreate(hbarCollector).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(tokenTreasury).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(tokenOwner).balance(ONE_MILLION_HBARS),
                cryptoCreate(alice).balance(ONE_MILLION_HBARS),
                cryptoCreate(bob).balance(ONE_MILLION_HBARS),
                cryptoCreate(carol).balance(ONE_MILLION_HBARS),
                cryptoCreate(ivan),
                tokenCreate(fungibleToken)
                        .treasury(tokenTreasury)
                        .initialSupply(tokenTotal)
                        .payingWith(tokenTreasury)
                        .withCustom(fixedHbarFee(1L, hbarCollector)),
                tokenAssociate(alice, fungibleToken),
                tokenAssociate(bob, fungibleToken),
                tokenAssociate(carol, fungibleToken),
                tokenAssociate(ivan, fungibleToken),
                tokenAssociate(tokenOwner, fungibleToken),
                cryptoTransfer(moving(tokenTotal, fungibleToken).between(tokenTreasury, tokenOwner)),
                cryptoTransfer(moving(10L, fungibleToken).between(tokenOwner, alice))
                        .fee(1L),
                cryptoTransfer(moving(5L, fungibleToken).between(alice, bob)).fee(1L),
                cryptoTransfer(moving(2L, fungibleToken).between(bob, carol)).fee(1L),
                cryptoTransfer(moving(1L, fungibleToken).between(carol, ivan)).fee(1L),
                getAccountBalance(hbarCollector).hasTinyBars(ONE_HUNDRED_HBARS + 4L),
                getAccountBalance(alice).hasTokenBalance(fungibleToken, 5L).hasTinyBars(ONE_MILLION_HBARS - 1L),
                getAccountBalance(bob).hasTokenBalance(fungibleToken, 3L).hasTinyBars(ONE_MILLION_HBARS - 1L),
                getAccountBalance(carol).hasTokenBalance(fungibleToken, 1L).hasTinyBars(ONE_MILLION_HBARS - 1L));
    }

    @HapiTest
    final Stream<DynamicTest> transferMultipleTimesWithFixedFeeInCustomFungibleTokenShouldVerifyEachTransferIsPaid() {
        return hapiTest(
                cryptoCreate(htsCollector).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(tokenTreasury).balance(ONE_MILLION_HBARS),
                cryptoCreate(tokenOwner),
                cryptoCreate(alice),
                cryptoCreate(bob),
                cryptoCreate(carol),
                cryptoCreate(ivan),
                tokenCreate(fungibleToken)
                        .treasury(tokenTreasury)
                        .initialSupply(tokenTotal)
                        .payingWith(tokenTreasury),
                tokenAssociate(htsCollector, fungibleToken),
                tokenCreate(fungibleToken2)
                        .treasury(tokenTreasury)
                        .initialSupply(tokenTotal)
                        .payingWith(tokenTreasury)
                        .withCustom(fixedHtsFee(1L, fungibleToken, htsCollector)),
                tokenAssociate(alice, fungibleToken, fungibleToken2),
                tokenAssociate(bob, fungibleToken, fungibleToken2),
                tokenAssociate(carol, fungibleToken, fungibleToken2),
                tokenAssociate(ivan, fungibleToken, fungibleToken2),
                tokenAssociate(tokenOwner, fungibleToken, fungibleToken2),
                cryptoTransfer(moving(50L, fungibleToken).between(tokenTreasury, tokenOwner)),
                cryptoTransfer(moving(50L, fungibleToken2).between(tokenTreasury, tokenOwner)),
                cryptoTransfer(moving(1L, fungibleToken).between(tokenTreasury, alice)),
                cryptoTransfer(moving(1L, fungibleToken).between(tokenTreasury, bob)),
                cryptoTransfer(moving(1L, fungibleToken).between(tokenTreasury, carol)),
                cryptoTransfer(moving(10L, fungibleToken2).between(tokenOwner, alice))
                        .fee(1L),
                cryptoTransfer(moving(5L, fungibleToken2).between(alice, bob)).fee(1L),
                cryptoTransfer(moving(2L, fungibleToken2).between(bob, carol)).fee(1L),
                cryptoTransfer(moving(1L, fungibleToken2).between(carol, ivan)).fee(1L),
                getAccountBalance(htsCollector).hasTokenBalance(fungibleToken, 4L),
                getAccountBalance(alice).hasTokenBalance(fungibleToken2, 5L).hasTokenBalance(fungibleToken, 0L),
                getAccountBalance(bob).hasTokenBalance(fungibleToken2, 3L).hasTokenBalance(fungibleToken, 0L),
                getAccountBalance(carol).hasTokenBalance(fungibleToken2, 1L).hasTokenBalance(fungibleToken, 0L),
                getAccountBalance(ivan).hasTokenBalance(fungibleToken, 0L));
    }

    @HapiTest
    final Stream<DynamicTest> transferFungibleToHollowAccountWithFixedHBarFee() {
        return hapiTest(flattened(
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(tokenTreasury).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(tokenOwner),
                cryptoCreate(alice),
                createHollowAccountFrom(SECP_256K1_SOURCE_KEY),
                withOpContext((spec, opLog) -> {
                    final var hollowAccountCollector =
                            spec.registry().getAccountIdName(spec.registry().getAccountAlias(SECP_256K1_SOURCE_KEY));

                    allRunFor(
                            spec,
                            tokenCreate(fungibleToken)
                                    .treasury(tokenTreasury)
                                    .tokenType(TokenType.FUNGIBLE_COMMON)
                                    .initialSupply(tokenTotal)
                                    .withCustom(fixedHbarFee(hbarFee, hollowAccountCollector)),
                            tokenAssociate(tokenOwner, fungibleToken),
                            tokenAssociate(alice, fungibleToken),
                            cryptoTransfer(moving(tokenTotal, fungibleToken).between(tokenTreasury, tokenOwner)),
                            cryptoTransfer(moving(10L, fungibleToken).between(tokenOwner, alice))
                                    .fee(1L),
                            getAccountBalance(hollowAccountCollector).hasTinyBars(ONE_HUNDRED_HBARS + hbarFee),
                            getAccountBalance(tokenOwner).hasTokenBalance(fungibleToken, tokenTotal - 10L),
                            getAccountBalance(alice).hasTokenBalance(fungibleToken, 10L));
                })));
    }

    @HapiTest
    final Stream<DynamicTest> transferFungibleToHollowAccountWithFixedHtsFee() {
        return hapiTest(flattened(
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(tokenOwner).balance(ONE_MILLION_HBARS),
                cryptoCreate(tokenReceiver),
                cryptoCreate(tokenTreasury),
                tokenCreate(feeDenom).treasury(tokenOwner).initialSupply(tokenTotal),
                createHollowAccountFrom(SECP_256K1_SOURCE_KEY),
                withOpContext((spec, opLog) -> {
                    final var hollowAccountCollector =
                            spec.registry().getAccountIdName(spec.registry().getAccountAlias(SECP_256K1_SOURCE_KEY));
                    allRunFor(
                            spec,
                            tokenAssociate(hollowAccountCollector, feeDenom)
                                    .payingWith(SECP_256K1_SOURCE_KEY)
                                    .sigMapPrefixes(uniqueWithFullPrefixesFor(SECP_256K1_SOURCE_KEY))
                                    .via(TRANSFER_TXN_2),
                            tokenCreate(fungibleToken)
                                    .treasury(tokenTreasury)
                                    .tokenType(TokenType.FUNGIBLE_COMMON)
                                    .initialSupply(tokenTotal)
                                    .withCustom(fixedHtsFee(htsFee, feeDenom, hollowAccountCollector)),
                            tokenAssociate(tokenReceiver, fungibleToken),
                            tokenAssociate(tokenOwner, fungibleToken),
                            cryptoTransfer(moving(1000, fungibleToken).between(tokenTreasury, tokenOwner)),
                            cryptoTransfer(moving(1, fungibleToken).between(tokenOwner, tokenReceiver))
                                    .fee(ONE_HUNDRED_HBARS)
                                    .payingWith(tokenOwner),
                            getAccountBalance(tokenOwner)
                                    .hasTokenBalance(fungibleToken, 999)
                                    .hasTokenBalance(feeDenom, tokenTotal - htsFee),
                            getAccountBalance(tokenReceiver).hasTokenBalance(fungibleToken, 1),
                            getAccountBalance(hollowAccountCollector).hasTokenBalance(feeDenom, htsFee));
                })));
    }

    @HapiTest
    final Stream<DynamicTest> transferNonFungibleToHollowAccountWithFixedHBarFee() {
        return hapiTest(flattened(
                newKeyNamed(NFT_KEY),
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(tokenTreasury).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(tokenOwner),
                cryptoCreate(alice),
                createHollowAccountFrom(SECP_256K1_SOURCE_KEY),
                withOpContext((spec, opLog) -> {
                    final var hollowAccountCollector =
                            spec.registry().getAccountIdName(spec.registry().getAccountAlias(SECP_256K1_SOURCE_KEY));

                    allRunFor(
                            spec,
                            tokenCreate(nonFungibleToken)
                                    .treasury(tokenTreasury)
                                    .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                                    .supplyKey(NFT_KEY)
                                    .supplyType(TokenSupplyType.INFINITE)
                                    .initialSupply(0)
                                    .withCustom(fixedHbarFee(hbarFee, hollowAccountCollector)),
                            mintToken(nonFungibleToken, List.of(ByteStringUtils.wrapUnsafely("meta1".getBytes()))),
                            tokenAssociate(tokenOwner, nonFungibleToken),
                            tokenAssociate(alice, nonFungibleToken),
                            cryptoTransfer(movingUnique(nonFungibleToken, 1L).between(tokenTreasury, tokenOwner)),
                            cryptoTransfer(movingUnique(nonFungibleToken, 1L).between(tokenOwner, alice))
                                    .fee(1L),
                            getAccountBalance(hollowAccountCollector).hasTinyBars(ONE_HUNDRED_HBARS + hbarFee),
                            getAccountBalance(tokenOwner).hasTokenBalance(nonFungibleToken, 0L),
                            getAccountBalance(alice).hasTokenBalance(nonFungibleToken, 1L));
                })));
    }

    @HapiTest
    final Stream<DynamicTest> transferNonFungibleToHollowAccountWithFixedHtsFee() {
        return hapiTest(flattened(
                newKeyNamed(NFT_KEY),
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(tokenOwner).balance(ONE_MILLION_HBARS),
                cryptoCreate(tokenReceiver),
                cryptoCreate(tokenTreasury),
                tokenCreate(feeDenom).treasury(tokenOwner).initialSupply(tokenTotal),
                createHollowAccountFrom(SECP_256K1_SOURCE_KEY),
                withOpContext((spec, opLog) -> {
                    final var hollowAccountCollector =
                            spec.registry().getAccountIdName(spec.registry().getAccountAlias(SECP_256K1_SOURCE_KEY));
                    allRunFor(
                            spec,
                            tokenAssociate(hollowAccountCollector, feeDenom)
                                    .payingWith(SECP_256K1_SOURCE_KEY)
                                    .sigMapPrefixes(uniqueWithFullPrefixesFor(SECP_256K1_SOURCE_KEY))
                                    .via(TRANSFER_TXN_2),
                            tokenCreate(nonFungibleToken)
                                    .treasury(tokenTreasury)
                                    .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                                    .supplyKey(NFT_KEY)
                                    .supplyType(TokenSupplyType.INFINITE)
                                    .initialSupply(0)
                                    .withCustom(fixedHtsFee(htsFee, feeDenom, hollowAccountCollector)),
                            mintToken(nonFungibleToken, List.of(ByteStringUtils.wrapUnsafely("meta1".getBytes()))),
                            tokenAssociate(tokenReceiver, nonFungibleToken),
                            tokenAssociate(tokenOwner, nonFungibleToken),
                            cryptoTransfer(movingUnique(nonFungibleToken, 1L).between(tokenTreasury, tokenOwner)),
                            cryptoTransfer(movingUnique(nonFungibleToken, 1L).between(tokenOwner, tokenReceiver))
                                    .fee(ONE_HUNDRED_HBARS)
                                    .payingWith(tokenOwner),
                            getAccountBalance(tokenOwner)
                                    .hasTokenBalance(nonFungibleToken, 0L)
                                    .hasTokenBalance(feeDenom, tokenTotal - htsFee),
                            getAccountBalance(tokenReceiver).hasTokenBalance(nonFungibleToken, 1L),
                            getAccountBalance(hollowAccountCollector).hasTokenBalance(feeDenom, htsFee));
                })));
    }
}
