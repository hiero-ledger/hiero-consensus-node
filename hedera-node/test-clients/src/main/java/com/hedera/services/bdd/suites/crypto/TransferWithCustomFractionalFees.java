// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.crypto;

import static com.hedera.services.bdd.junit.TestTags.CRYPTO;
import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AccountDetailsAsserts.accountDetailsWith;
import static com.hedera.services.bdd.spec.keys.TrieSigMapGenerator.uniqueWithFullPrefixesFor;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountDetails;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.*;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.*;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.*;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SHAPE;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SOURCE_KEY;
import static com.hedera.services.bdd.suites.HapiSuite.flattened;
import static com.hedera.services.bdd.suites.crypto.AutoAccountUpdateSuite.TRANSFER_TXN_2;
import static com.hedera.services.bdd.suites.crypto.AutoCreateUtils.createHollowAccountFrom;

import com.hedera.services.bdd.junit.HapiTest;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import java.util.OptionalLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(CRYPTO)
public class TransferWithCustomFractionalFees {
    private static final long tokenTotal = 1_000L;
    private static final long numerator = 1L;
    private static final long denominator = 10L;
    private static final long minHtsFee = 2L;
    private static final long maxHtsFee = 10L;

    private static final String token = "withCustomFees";
    private static final String token2 = "withCustomFees2";
    private static final String hbarCollector = "hbarFee";
    private static final String htsCollector = "denomFee";
    private static final String tokenReceiver = "receiver";
    private static final String tokenTreasury = "tokenTreasury";
    private static final String spender = "spender";
    private static final String tokenOwner = "tokenOwner";
    private static final String alice = "alice";
    private static final String bob = "bob";
    private static final String carol = "carol";
    private static final String ivan = "ivan";

    @HapiTest
    final Stream<DynamicTest> transferWithFractionalCustomFeeNegativeMoreThanTen() {
        return hapiTest(
                cryptoCreate(htsCollector).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(hbarCollector).balance(0L),
                cryptoCreate(tokenReceiver),
                cryptoCreate(tokenTreasury),
                cryptoCreate(tokenOwner).balance(ONE_MILLION_HBARS),
                tokenCreate(token)
                        .treasury(tokenTreasury)
                        .initialSupply(tokenTotal)
                        .payingWith(htsCollector)
                        .withCustom(fractionalFee(
                                numerator, denominator, minHtsFee, OptionalLong.of(maxHtsFee), htsCollector))
                        .withCustom(fractionalFee(
                                numerator, denominator + 1, minHtsFee, OptionalLong.of(maxHtsFee), htsCollector))
                        .withCustom(fractionalFee(
                                numerator, denominator + 2, minHtsFee, OptionalLong.of(maxHtsFee), htsCollector))
                        .withCustom(fractionalFee(
                                numerator, denominator + 3, minHtsFee, OptionalLong.of(maxHtsFee), htsCollector))
                        .withCustom(fractionalFee(
                                numerator, denominator + 4, minHtsFee, OptionalLong.of(maxHtsFee), htsCollector))
                        .withCustom(fractionalFee(
                                numerator, denominator + 5, minHtsFee, OptionalLong.of(maxHtsFee), htsCollector))
                        .withCustom(fractionalFee(
                                numerator, denominator + 6, minHtsFee, OptionalLong.of(maxHtsFee), htsCollector))
                        .withCustom(fractionalFee(
                                numerator, denominator + 7, minHtsFee, OptionalLong.of(maxHtsFee), htsCollector))
                        .withCustom(fractionalFee(
                                numerator, denominator + 8, minHtsFee, OptionalLong.of(maxHtsFee), htsCollector))
                        .withCustom(fractionalFee(
                                numerator, denominator + 9, minHtsFee, OptionalLong.of(maxHtsFee), htsCollector))
                        .withCustom(fractionalFee(
                                numerator, denominator + 10, minHtsFee, OptionalLong.of(maxHtsFee), htsCollector))
                        .hasKnownStatus(ResponseCodeEnum.CUSTOM_FEES_LIST_TOO_LONG));
    }

    @HapiTest
    final Stream<DynamicTest> transferWithFractionalCustomFee() {
        return hapiTest(
                cryptoCreate(htsCollector).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(hbarCollector).balance(0L),
                cryptoCreate(tokenReceiver),
                cryptoCreate(tokenTreasury),
                cryptoCreate(tokenOwner).balance(ONE_MILLION_HBARS),
                tokenCreate(token)
                        .treasury(tokenTreasury)
                        .initialSupply(tokenTotal)
                        .payingWith(htsCollector)
                        .withCustom(fractionalFee(
                                numerator, denominator, minHtsFee, OptionalLong.of(maxHtsFee), htsCollector)),
                tokenAssociate(tokenReceiver, token),
                tokenAssociate(tokenOwner, token),
                cryptoTransfer(moving(tokenTotal, token).between(tokenTreasury, tokenOwner)),
                cryptoTransfer(moving(100L, token).between(tokenOwner, tokenReceiver))
                        .fee(ONE_HUNDRED_HBARS)
                        .payingWith(tokenOwner),
                getAccountBalance(tokenOwner).hasTokenBalance(token, 900L),
                getAccountBalance(tokenReceiver).hasTokenBalance(token, 90L),
                getAccountBalance(htsCollector).hasTokenBalance(token, 10L));
    }

    @HapiTest
    final Stream<DynamicTest> transferWithFractionalCustomFeeZeroDenominator() {
        return hapiTest(
                cryptoCreate(htsCollector).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(hbarCollector).balance(0L),
                cryptoCreate(tokenReceiver),
                cryptoCreate(tokenTreasury),
                cryptoCreate(tokenOwner).balance(ONE_MILLION_HBARS),
                tokenCreate(token)
                        .treasury(tokenTreasury)
                        .initialSupply(tokenTotal)
                        .payingWith(htsCollector)
                        .withCustom(fractionalFee(numerator, 0, minHtsFee, OptionalLong.of(maxHtsFee), htsCollector))
                        .hasKnownStatus(ResponseCodeEnum.FRACTION_DIVIDES_BY_ZERO));
    }

    @HapiTest
    final Stream<DynamicTest> transferWithFractionalCustomFeeNumeratorBiggerThanDenominator() {
        return hapiTest(
                cryptoCreate(htsCollector).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(hbarCollector).balance(0L),
                cryptoCreate(tokenReceiver),
                cryptoCreate(tokenTreasury),
                cryptoCreate(tokenOwner).balance(ONE_MILLION_HBARS),
                tokenCreate(token)
                        .treasury(tokenTreasury)
                        .initialSupply(tokenTotal)
                        .payingWith(htsCollector)
                        .withCustom(fractionalFeeNetOfTransfers(3, 1, minHtsFee, OptionalLong.of(50), htsCollector)),
                tokenAssociate(tokenReceiver, token),
                tokenAssociate(tokenOwner, token),
                cryptoTransfer(moving(tokenTotal, token).between(tokenTreasury, tokenOwner)),
                cryptoTransfer(moving(10L, token).between(tokenOwner, tokenReceiver))
                        .fee(ONE_HUNDRED_HBARS)
                        .payingWith(tokenOwner),
                getAccountBalance(tokenOwner).hasTokenBalance(token, 960L),
                getAccountBalance(tokenReceiver).hasTokenBalance(token, 10L),
                getAccountBalance(htsCollector).hasTokenBalance(token, 30L));
    }

    @HapiTest
    final Stream<DynamicTest> transferWithFractionalCustomFeeNetOfTransfers() {
        return hapiTest(
                cryptoCreate(htsCollector).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(hbarCollector).balance(0L),
                cryptoCreate(tokenReceiver),
                cryptoCreate(tokenTreasury),
                cryptoCreate(tokenOwner).balance(ONE_MILLION_HBARS),
                tokenCreate(token)
                        .treasury(tokenTreasury)
                        .initialSupply(tokenTotal)
                        .payingWith(htsCollector)
                        .withCustom(fractionalFeeNetOfTransfers(
                                numerator, denominator, minHtsFee, OptionalLong.of(maxHtsFee), htsCollector)),
                tokenAssociate(tokenReceiver, token),
                tokenAssociate(tokenOwner, token),
                cryptoTransfer(moving(tokenTotal, token).between(tokenTreasury, tokenOwner)),
                cryptoTransfer(moving(100L, token).between(tokenOwner, tokenReceiver))
                        .fee(ONE_HUNDRED_HBARS)
                        .payingWith(tokenOwner),
                getAccountBalance(tokenOwner).hasTokenBalance(token, 890L),
                getAccountBalance(tokenReceiver).hasTokenBalance(token, 100L),
                getAccountBalance(htsCollector).hasTokenBalance(token, 10L));
    }

    @HapiTest
    final Stream<DynamicTest> transferWithFractionalCustomFeeBellowMinimumAmount() {
        return hapiTest(
                cryptoCreate(htsCollector).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(hbarCollector).balance(0L),
                cryptoCreate(tokenReceiver),
                cryptoCreate(tokenTreasury),
                cryptoCreate(tokenOwner).balance(ONE_MILLION_HBARS),
                tokenCreate(token)
                        .treasury(tokenTreasury)
                        .initialSupply(tokenTotal)
                        .payingWith(htsCollector)
                        .withCustom(fractionalFee(numerator, denominator, 20L, OptionalLong.of(30L), htsCollector)),
                tokenAssociate(tokenReceiver, token),
                tokenAssociate(tokenOwner, token),
                cryptoTransfer(moving(tokenTotal, token).between(tokenTreasury, tokenOwner)),
                cryptoTransfer(moving(100L, token).between(tokenOwner, tokenReceiver))
                        .fee(ONE_HUNDRED_HBARS)
                        .payingWith(tokenOwner),
                getAccountBalance(tokenOwner).hasTokenBalance(token, 900L),
                getAccountBalance(tokenReceiver).hasTokenBalance(token, 80L),
                getAccountBalance(htsCollector).hasTokenBalance(token, 20L));
    }

    @HapiTest
    final Stream<DynamicTest> transferWithFractionalCustomFeeAboveMaximumAmount() {
        return hapiTest(
                cryptoCreate(htsCollector).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(hbarCollector).balance(0L),
                cryptoCreate(tokenReceiver),
                cryptoCreate(tokenTreasury),
                cryptoCreate(tokenOwner).balance(ONE_MILLION_HBARS),
                tokenCreate(token)
                        .treasury(tokenTreasury)
                        .initialSupply(tokenTotal)
                        .payingWith(htsCollector)
                        .withCustom(
                                fractionalFee(numerator, denominator, minHtsFee, OptionalLong.of(9L), htsCollector)),
                tokenAssociate(tokenReceiver, token),
                tokenAssociate(tokenOwner, token),
                cryptoTransfer(moving(tokenTotal, token).between(tokenTreasury, tokenOwner)),
                cryptoTransfer(moving(100L, token).between(tokenOwner, tokenReceiver))
                        .fee(ONE_HUNDRED_HBARS)
                        .payingWith(tokenOwner),
                getAccountBalance(tokenOwner).hasTokenBalance(token, 900),
                getAccountBalance(tokenReceiver).hasTokenBalance(token, 91),
                getAccountBalance(htsCollector).hasTokenBalance(token, 9L));
    }

    @HapiTest
    final Stream<DynamicTest> transferWithFractionalCustomFeeNetOfTransfersBellowMinimumAmount() {
        return hapiTest(
                cryptoCreate(htsCollector).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(hbarCollector).balance(0L),
                cryptoCreate(tokenReceiver),
                cryptoCreate(tokenTreasury),
                cryptoCreate(tokenOwner).balance(ONE_MILLION_HBARS),
                tokenCreate(token)
                        .treasury(tokenTreasury)
                        .initialSupply(tokenTotal)
                        .payingWith(htsCollector)
                        .withCustom(fractionalFeeNetOfTransfers(
                                numerator, denominator, 20L, OptionalLong.of(30), htsCollector)),
                tokenAssociate(tokenReceiver, token),
                tokenAssociate(tokenOwner, token),
                cryptoTransfer(moving(tokenTotal, token).between(tokenTreasury, tokenOwner)),
                cryptoTransfer(moving(100L, token).between(tokenOwner, tokenReceiver))
                        .fee(ONE_HUNDRED_HBARS)
                        .payingWith(tokenOwner),
                getAccountBalance(tokenOwner).hasTokenBalance(token, 880L),
                getAccountBalance(tokenReceiver).hasTokenBalance(token, 100L),
                getAccountBalance(htsCollector).hasTokenBalance(token, 20L));
    }

    @HapiTest
    final Stream<DynamicTest> transferWithFractionalCustomFeeNetOfTransfersAboveMaximumAmount() {
        return hapiTest(
                cryptoCreate(htsCollector).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(hbarCollector).balance(0L),
                cryptoCreate(tokenReceiver),
                cryptoCreate(tokenTreasury),
                cryptoCreate(tokenOwner).balance(ONE_MILLION_HBARS),
                tokenCreate(token)
                        .treasury(tokenTreasury)
                        .initialSupply(tokenTotal)
                        .payingWith(htsCollector)
                        .withCustom(fractionalFeeNetOfTransfers(
                                numerator, denominator, minHtsFee, OptionalLong.of(9L), htsCollector)),
                tokenAssociate(tokenReceiver, token),
                tokenAssociate(tokenOwner, token),
                cryptoTransfer(moving(tokenTotal, token).between(tokenTreasury, tokenOwner)),
                cryptoTransfer(moving(100L, token).between(tokenOwner, tokenReceiver))
                        .fee(ONE_HUNDRED_HBARS)
                        .payingWith(tokenOwner),
                getAccountBalance(tokenOwner).hasTokenBalance(token, 891L),
                getAccountBalance(tokenReceiver).hasTokenBalance(token, 100L),
                getAccountBalance(htsCollector).hasTokenBalance(token, 9L));
    }

    @HapiTest
    final Stream<DynamicTest> transferWithFractionalCustomFeeAllowance() {
        return hapiTest(
                cryptoCreate(htsCollector).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(tokenReceiver),
                cryptoCreate(spender).balance(ONE_MILLION_HBARS),
                cryptoCreate(tokenTreasury),
                cryptoCreate(tokenOwner).balance(ONE_MILLION_HBARS),
                tokenCreate(token)
                        .treasury(tokenTreasury)
                        .initialSupply(tokenTotal)
                        .payingWith(htsCollector)
                        .withCustom(fractionalFee(
                                numerator, denominator, minHtsFee, OptionalLong.of(maxHtsFee), htsCollector)),
                tokenAssociate(tokenReceiver, token),
                tokenAssociate(tokenOwner, token),
                tokenAssociate(spender, token),
                cryptoTransfer(moving(tokenTotal, token).between(tokenTreasury, tokenOwner)),
                cryptoApproveAllowance()
                        .payingWith(tokenOwner)
                        .addTokenAllowance(tokenOwner, token, spender, 120L)
                        .signedBy(tokenOwner)
                        .fee(ONE_HUNDRED_HBARS),
                getAccountDetails(tokenOwner).has(accountDetailsWith().tokenAllowancesContaining(token, spender, 120L)),
                cryptoTransfer(movingWithAllowance(100L, token).between(tokenOwner, tokenReceiver))
                        .fee(ONE_HUNDRED_HBARS)
                        .payingWith(spender)
                        .signedBy(spender),
                getAccountBalance(tokenOwner).hasTokenBalance(token, tokenTotal - 100L),
                getAccountBalance(tokenReceiver).hasTokenBalance(token, 100L - 100L * numerator / denominator),
                getAccountBalance(htsCollector).hasTokenBalance(token, 100L * numerator / denominator),
                getAccountDetails(tokenOwner).has(accountDetailsWith().tokenAllowancesContaining(token, spender, 20L)));
    }

    @HapiTest
    @Tag(MATS)
    final Stream<DynamicTest> transferWithFractionalCustomFeeAllowanceNetOfTransfers() {
        return hapiTest(
                cryptoCreate(htsCollector).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(tokenReceiver),
                cryptoCreate(spender).balance(ONE_MILLION_HBARS),
                cryptoCreate(tokenTreasury),
                cryptoCreate(tokenOwner).balance(ONE_MILLION_HBARS),
                tokenCreate(token)
                        .treasury(tokenTreasury)
                        .initialSupply(tokenTotal)
                        .payingWith(htsCollector)
                        .withCustom(fractionalFeeNetOfTransfers(
                                numerator, denominator, minHtsFee, OptionalLong.of(maxHtsFee), htsCollector)),
                tokenAssociate(tokenReceiver, token),
                tokenAssociate(tokenOwner, token),
                tokenAssociate(spender, token),
                cryptoTransfer(moving(tokenTotal, token).between(tokenTreasury, tokenOwner)),
                cryptoApproveAllowance()
                        .payingWith(tokenOwner)
                        .addTokenAllowance(tokenOwner, token, spender, 120L)
                        .signedBy(tokenOwner)
                        .fee(ONE_HUNDRED_HBARS),
                getAccountDetails(tokenOwner).has(accountDetailsWith().tokenAllowancesContaining(token, spender, 120L)),
                cryptoTransfer(movingWithAllowance(100L, token).between(tokenOwner, tokenReceiver))
                        .fee(ONE_HUNDRED_HBARS)
                        .payingWith(spender)
                        .signedBy(spender),
                getAccountBalance(tokenOwner).hasTokenBalance(token, tokenTotal - 110L),
                getAccountBalance(tokenReceiver).hasTokenBalance(token, 100L),
                getAccountBalance(htsCollector).hasTokenBalance(token, 100L * numerator / denominator),
                getAccountDetails(tokenOwner).has(accountDetailsWith().tokenAllowancesContaining(token, spender, 10L)));
    }

    @HapiTest
    final Stream<DynamicTest> transferWithFractionalCustomFeeAllowanceTokenOwnerIsCollector() {
        return hapiTest(
                cryptoCreate(htsCollector).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(tokenReceiver),
                cryptoCreate(spender).balance(ONE_MILLION_HBARS),
                cryptoCreate(tokenTreasury),
                cryptoCreate(tokenOwner).balance(ONE_MILLION_HBARS),
                tokenCreate(token)
                        .treasury(tokenTreasury)
                        .initialSupply(tokenTotal)
                        .payingWith(tokenOwner)
                        .signedBy(tokenOwner, tokenTreasury)
                        .withCustom(fractionalFee(
                                numerator, denominator, minHtsFee, OptionalLong.of(maxHtsFee), tokenOwner)),
                tokenAssociate(tokenReceiver, token),
                tokenAssociate(spender, token),
                cryptoTransfer(moving(tokenTotal, token).between(tokenTreasury, tokenOwner)),
                cryptoApproveAllowance()
                        .payingWith(tokenOwner)
                        .addTokenAllowance(tokenOwner, token, spender, 120L)
                        .signedBy(tokenOwner)
                        .fee(ONE_HUNDRED_HBARS),
                getAccountDetails(tokenOwner).has(accountDetailsWith().tokenAllowancesContaining(token, spender, 120L)),
                cryptoTransfer(movingWithAllowance(100L, token).between(tokenOwner, tokenReceiver))
                        .fee(ONE_HUNDRED_HBARS)
                        .payingWith(spender)
                        .signedBy(spender),
                getAccountBalance(tokenOwner).hasTokenBalance(token, tokenTotal - 100L),
                getAccountBalance(tokenReceiver).hasTokenBalance(token, 100L),
                getAccountDetails(tokenOwner).has(accountDetailsWith().tokenAllowancesContaining(token, spender, 20L)));
    }

    @HapiTest
    final Stream<DynamicTest> transferWithFractionalCustomFeeNegativeNotEnoughAllowance() {
        return hapiTest(
                cryptoCreate(htsCollector).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(hbarCollector).balance(0L),
                cryptoCreate(tokenReceiver),
                cryptoCreate(spender).balance(ONE_MILLION_HBARS),
                cryptoCreate(tokenTreasury),
                cryptoCreate(tokenOwner).balance(ONE_MILLION_HBARS),
                tokenCreate(token)
                        .treasury(tokenTreasury)
                        .initialSupply(tokenTotal)
                        .payingWith(htsCollector)
                        .withCustom(fractionalFee(
                                numerator, denominator, minHtsFee, OptionalLong.of(maxHtsFee), htsCollector)),
                tokenAssociate(tokenReceiver, token),
                tokenAssociate(tokenOwner, token),
                tokenAssociate(spender, token),
                cryptoTransfer(moving(tokenTotal, token).between(tokenTreasury, tokenOwner)),
                cryptoApproveAllowance()
                        .payingWith(tokenOwner)
                        .addTokenAllowance(tokenOwner, token, spender, 1)
                        .fee(ONE_HUNDRED_HBARS),
                cryptoTransfer(moving(100L, token).between(spender, tokenReceiver))
                        .fee(ONE_HUNDRED_HBARS)
                        .payingWith(spender)
                        .hasKnownStatus(ResponseCodeEnum.INSUFFICIENT_TOKEN_BALANCE),
                getAccountBalance(tokenOwner).hasTokenBalance(token, 1000L),
                getAccountBalance(tokenReceiver).hasTokenBalance(token, 0L),
                getAccountBalance(htsCollector).hasTokenBalance(token, 0L));
    }

    @HapiTest
    @Tag(MATS)
    final Stream<DynamicTest> transferWithFractionalCustomFeesThreeCollectors() {
        return hapiTest(
                cryptoCreate(alice),
                cryptoCreate(bob),
                cryptoCreate(carol),
                cryptoCreate(tokenTreasury).balance(ONE_MILLION_HBARS),
                tokenCreate(token)
                        .treasury(tokenTreasury)
                        .initialSupply(tokenTotal)
                        .payingWith(tokenTreasury)
                        .signedBy(alice, bob, carol, tokenTreasury)
                        .withCustom(fractionalFee(numerator, 2L, minHtsFee, OptionalLong.of(10000), alice))
                        .withCustom(fractionalFee(numerator, 4L, minHtsFee, OptionalLong.of(10000), bob))
                        .withCustom(fractionalFee(numerator, 8L, minHtsFee, OptionalLong.of(10000), carol)),
                cryptoTransfer(moving(100, token).between(tokenTreasury, alice)),
                cryptoTransfer(moving(100 / 2, token).between(alice, bob)),
                cryptoTransfer(moving(100 / 4, token).between(bob, carol)),
                getAccountBalance(alice).hasTokenBalance(token, 100 / 2 + 100 / 8),
                getAccountBalance(bob).hasTokenBalance(token, 19),
                getAccountBalance(carol).hasTokenBalance(token, 19));
    }

    @HapiTest
    final Stream<DynamicTest> transferWithFractionalCustomFeesAllCollectorsExempt() {
        return hapiTest(
                cryptoCreate(alice),
                cryptoCreate(bob),
                cryptoCreate(carol),
                cryptoCreate(tokenTreasury).balance(ONE_MILLION_HBARS),
                tokenCreate(token)
                        .treasury(tokenTreasury)
                        .initialSupply(tokenTotal)
                        .payingWith(tokenTreasury)
                        .signedBy(carol, bob, tokenTreasury)
                        .withCustom(fractionalFee(numerator, 8L, minHtsFee, OptionalLong.of(maxHtsFee), carol, true))
                        .withCustom(fractionalFeeNetOfTransfers(
                                numerator, 8L, minHtsFee, OptionalLong.of(maxHtsFee), bob, true)),
                tokenAssociate(alice, token),
                cryptoTransfer(moving(100, token).between(tokenTreasury, carol)),
                cryptoTransfer(moving(10, token).between(carol, alice)),
                cryptoTransfer(moving(10, token).between(alice, bob)),
                getAccountBalance(carol).hasTokenBalance(token, 90),
                getAccountBalance(bob).hasTokenBalance(token, 10));
    }

    @HapiTest
    final Stream<DynamicTest> transferWithFractionalCustomFeesDenominatorMin() {
        return hapiTest(
                cryptoCreate(alice),
                cryptoCreate(bob),
                cryptoCreate(carol),
                cryptoCreate(ivan),
                cryptoCreate(tokenTreasury).balance(ONE_MILLION_HBARS),
                tokenCreate(token)
                        .treasury(tokenTreasury)
                        .initialSupply(tokenTotal)
                        .payingWith(tokenTreasury)
                        .signedBy(alice, bob, tokenTreasury)
                        .withCustom(fractionalFee(
                                numerator, Long.MAX_VALUE, 1 / Long.MAX_VALUE, OptionalLong.of(maxHtsFee), alice))
                        .withCustom(fractionalFeeNetOfTransfers(
                                numerator, Long.MAX_VALUE, 1 / Long.MAX_VALUE, OptionalLong.of(maxHtsFee), bob)),
                tokenAssociate(carol, token),
                tokenAssociate(ivan, token),
                cryptoTransfer(moving(10, token).between(tokenTreasury, carol)),
                cryptoTransfer(moving(10, token).between(tokenTreasury, ivan)),
                cryptoTransfer(moving(1, token).between(carol, ivan)),
                getAccountBalance(alice).hasTokenBalance(token, 0),
                getAccountBalance(bob).hasTokenBalance(token, 0));
    }

    @HapiTest
    @Tag(MATS)
    final Stream<DynamicTest> transferWithFractionalCustomFeesDenominatorMax() {
        return hapiTest(
                cryptoCreate(alice),
                cryptoCreate(bob),
                cryptoCreate(carol),
                cryptoCreate(tokenTreasury).balance(ONE_MILLION_HBARS),
                tokenCreate(token)
                        .treasury(tokenTreasury)
                        .initialSupply(tokenTotal)
                        .payingWith(tokenTreasury)
                        .signedBy(alice, bob, tokenTreasury)
                        .withCustom(fractionalFee(
                                Long.MAX_VALUE - 1,
                                Long.MAX_VALUE,
                                1 / Long.MAX_VALUE,
                                OptionalLong.of(maxHtsFee),
                                alice))
                        .withCustom(fractionalFeeNetOfTransfers(
                                Long.MAX_VALUE - 1,
                                Long.MAX_VALUE,
                                1 / Long.MAX_VALUE,
                                OptionalLong.of(maxHtsFee),
                                bob)),
                tokenAssociate(carol, token),
                cryptoTransfer(moving(10, token).between(tokenTreasury, alice)),
                cryptoTransfer(moving(10, token).between(alice, bob)),
                cryptoTransfer(moving(1, token).between(bob, carol)),
                getAccountBalance(alice).hasTokenBalance(token, 0));
    }

    @HapiTest
    final Stream<DynamicTest> transferWithFractionalCustomFeesMultipleReceivers() {
        return hapiTest(
                cryptoCreate(htsCollector).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(alice).balance(ONE_MILLION_HBARS),
                cryptoCreate(bob),
                cryptoCreate(carol),
                cryptoCreate(tokenTreasury).balance(ONE_MILLION_HBARS),
                tokenCreate(token)
                        .treasury(tokenTreasury)
                        .initialSupply(tokenTotal)
                        .payingWith(tokenTreasury)
                        .signedBy(tokenTreasury, htsCollector)
                        .withCustom(fractionalFeeNetOfTransfers(
                                numerator, denominator, minHtsFee, OptionalLong.of(maxHtsFee), htsCollector)),
                tokenCreate(token2)
                        .treasury(tokenTreasury)
                        .initialSupply(tokenTotal)
                        .payingWith(tokenTreasury)
                        .signedBy(tokenTreasury, htsCollector)
                        .withCustom(fractionalFeeNetOfTransfers(
                                numerator, denominator, minHtsFee, OptionalLong.of(maxHtsFee), htsCollector)),
                tokenAssociate(alice, token),
                tokenAssociate(carol, token),
                tokenAssociate(bob, token),
                tokenAssociate(alice, token2),
                tokenAssociate(carol, token2),
                tokenAssociate(bob, token2),
                cryptoTransfer(moving(tokenTotal, token).between(tokenTreasury, alice)),
                cryptoTransfer(moving(tokenTotal, token2).between(tokenTreasury, alice)),
                cryptoTransfer(
                        moving(2, token).between(alice, bob), moving(3, token2).between(alice, bob)),
                cryptoTransfer(
                        moving(4, token).between(alice, carol),
                        moving(6, token2).between(alice, carol)),
                getAccountBalance(bob).hasTokenBalance(token, 2),
                getAccountBalance(bob).hasTokenBalance(token2, 3),
                getAccountBalance(carol).hasTokenBalance(token, 4),
                getAccountBalance(carol).hasTokenBalance(token2, 6),
                getAccountBalance(htsCollector).hasTokenBalance(token, 4),
                getAccountBalance(htsCollector).hasTokenBalance(token2, 4));
    }

    @HapiTest
    final Stream<DynamicTest> transferWithFractionalCustomFeeEqToAmount() {
        return hapiTest(
                cryptoCreate(htsCollector).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(hbarCollector).balance(0L),
                cryptoCreate(tokenReceiver),
                cryptoCreate(tokenTreasury),
                cryptoCreate(tokenOwner).balance(ONE_MILLION_HBARS),
                tokenCreate(token)
                        .treasury(tokenTreasury)
                        .initialSupply(tokenTotal)
                        .payingWith(htsCollector)
                        .withCustom(fractionalFee(
                                numerator, denominator, minHtsFee, OptionalLong.of(maxHtsFee), htsCollector)),
                tokenAssociate(tokenReceiver, token),
                tokenAssociate(tokenOwner, token),
                cryptoTransfer(moving(tokenTotal, token).between(tokenTreasury, tokenOwner)),
                cryptoTransfer(moving(2L, token).between(tokenOwner, tokenReceiver))
                        .fee(ONE_HUNDRED_HBARS)
                        .payingWith(tokenOwner),
                getAccountBalance(tokenOwner).hasTokenBalance(token, tokenTotal - 2),
                getAccountBalance(tokenReceiver).hasTokenBalance(token, 0L),
                getAccountBalance(htsCollector).hasTokenBalance(token, 2L));
    }

    @HapiTest
    final Stream<DynamicTest> transferWithFractionalCustomFeeGreaterThanAmountNegative() {
        return hapiTest(
                cryptoCreate(htsCollector).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(hbarCollector).balance(0L),
                cryptoCreate(tokenReceiver),
                cryptoCreate(tokenTreasury),
                cryptoCreate(alice),
                cryptoCreate(tokenOwner).balance(ONE_MILLION_HBARS),
                tokenCreate(token)
                        .treasury(tokenTreasury)
                        .initialSupply(tokenTotal)
                        .payingWith(htsCollector)
                        .withCustom(fractionalFee(
                                numerator, denominator, minHtsFee, OptionalLong.of(maxHtsFee), htsCollector)),
                tokenAssociate(tokenReceiver, token),
                tokenAssociate(tokenOwner, token),
                tokenAssociate(alice, token),
                cryptoTransfer(moving(tokenTotal / 2, token).between(tokenTreasury, tokenOwner)),
                cryptoTransfer(moving(1L, token).between(tokenOwner, alice))
                        .fee(ONE_HUNDRED_HBARS)
                        .payingWith(tokenOwner)
                        .hasKnownStatus(ResponseCodeEnum.INSUFFICIENT_SENDER_ACCOUNT_BALANCE_FOR_CUSTOM_FEE),
                getAccountBalance(tokenOwner).hasTokenBalance(token, tokenTotal / 2));
    }

    @HapiTest
    final Stream<DynamicTest> transferWithFractionalCustomFeeGreaterThanAmount() {
        return hapiTest(
                cryptoCreate(htsCollector).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(hbarCollector).balance(0L),
                cryptoCreate(tokenReceiver),
                cryptoCreate(tokenTreasury),
                cryptoCreate(tokenOwner).balance(ONE_MILLION_HBARS),
                tokenCreate(token)
                        .treasury(tokenTreasury)
                        .initialSupply(tokenTotal)
                        .payingWith(htsCollector)
                        .withCustom(fractionalFee(
                                numerator, denominator, minHtsFee, OptionalLong.of(maxHtsFee), htsCollector)),
                tokenAssociate(tokenReceiver, token),
                tokenAssociate(tokenOwner, token),
                cryptoTransfer(moving(tokenTotal / 2, token).between(tokenTreasury, htsCollector)),
                cryptoTransfer(moving(tokenTotal / 2, token).between(tokenTreasury, tokenReceiver)),
                getAccountBalance(htsCollector).hasTokenBalance(token, tokenTotal / 2),
                getAccountBalance(tokenReceiver).hasTokenBalance(token, tokenTotal / 2),
                cryptoTransfer(moving(1L, token).between(htsCollector, tokenReceiver))
                        .fee(ONE_HUNDRED_HBARS)
                        .payingWith(tokenOwner),
                getAccountBalance(htsCollector).hasTokenBalance(token, tokenTotal / 2 - 1),
                getAccountBalance(tokenReceiver).hasTokenBalance(token, tokenTotal / 2 + 1));
    }

    @HapiTest
    final Stream<DynamicTest> transferWithFractionalCustomFeeGreaterThanAmountNetOfTransfers() {
        return hapiTest(
                cryptoCreate(htsCollector).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(hbarCollector).balance(0L),
                cryptoCreate(tokenReceiver),
                cryptoCreate(tokenTreasury),
                cryptoCreate(tokenOwner).balance(ONE_MILLION_HBARS),
                tokenCreate(token)
                        .treasury(tokenTreasury)
                        .initialSupply(tokenTotal)
                        .payingWith(htsCollector)
                        .withCustom(fractionalFeeNetOfTransfers(
                                numerator, denominator, minHtsFee, OptionalLong.of(maxHtsFee), htsCollector)),
                tokenAssociate(tokenReceiver, token),
                tokenAssociate(tokenOwner, token),
                cryptoTransfer(moving(tokenTotal / 2, token).between(tokenTreasury, htsCollector)),
                cryptoTransfer(moving(tokenTotal / 2, token).between(tokenTreasury, tokenReceiver)),
                getAccountBalance(tokenReceiver).hasTokenBalance(token, tokenTotal / 2),
                cryptoTransfer(moving(1L, token).between(htsCollector, tokenReceiver))
                        .fee(ONE_HUNDRED_HBARS)
                        .payingWith(tokenOwner),
                getAccountBalance(htsCollector).hasTokenBalance(token, tokenTotal / 2 - 1),
                getAccountBalance(tokenReceiver).hasTokenBalance(token, tokenTotal / 2 + 1));
    }

    @HapiTest
    final Stream<DynamicTest> transferWithFractionalCustomFeeMultipleRecipientsShouldRoundFee() {
        return hapiTest(
                cryptoCreate(htsCollector).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(hbarCollector).balance(0L),
                cryptoCreate(tokenReceiver),
                cryptoCreate(tokenTreasury),
                cryptoCreate(carol),
                cryptoCreate(alice),
                cryptoCreate(tokenOwner).balance(ONE_MILLION_HBARS),
                tokenCreate(token)
                        .treasury(tokenTreasury)
                        .initialSupply(tokenTotal)
                        .payingWith(htsCollector)
                        .withCustom(fractionalFee(
                                numerator, denominator, minHtsFee, OptionalLong.of(maxHtsFee), htsCollector)),
                tokenAssociate(alice, token),
                tokenAssociate(carol, token),
                tokenAssociate(tokenOwner, token),
                cryptoTransfer(moving(tokenTotal, token).between(tokenTreasury, tokenOwner)),
                cryptoTransfer(
                                moving(5L, token).between(tokenOwner, alice),
                                moving(5L, token).between(tokenOwner, carol))
                        .fee(ONE_HUNDRED_HBARS)
                        .payingWith(tokenOwner),
                getAccountBalance(htsCollector).hasTokenBalance(token, 2L),
                getAccountBalance(alice).hasTokenBalance(token, 4L),
                getAccountBalance(carol).hasTokenBalance(token, 4L),
                getAccountBalance(tokenOwner).hasTokenBalance(token, 990L));
    }

    @HapiTest
    final Stream<DynamicTest> transferWithFractionalCustomFeeMultipleRecipientsHasNotEnoughBalance() {
        return hapiTest(
                cryptoCreate(htsCollector).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(hbarCollector).balance(0L),
                cryptoCreate(tokenReceiver),
                cryptoCreate(tokenTreasury),
                cryptoCreate(carol),
                cryptoCreate(alice),
                cryptoCreate(tokenOwner).balance(ONE_MILLION_HBARS),
                tokenCreate(token)
                        .treasury(tokenTreasury)
                        .initialSupply(tokenTotal)
                        .payingWith(htsCollector)
                        .withCustom(fractionalFee(numerator, denominator, 10, OptionalLong.of(100), htsCollector)),
                tokenAssociate(alice, token),
                tokenAssociate(carol, token),
                tokenAssociate(tokenOwner, token),
                cryptoTransfer(moving(tokenTotal, token).between(tokenTreasury, tokenOwner)),
                cryptoTransfer(
                                moving(5L, token).between(tokenOwner, alice),
                                moving(5L, token).between(tokenOwner, carol))
                        .fee(ONE_HUNDRED_HBARS)
                        .payingWith(tokenOwner),
                getAccountBalance(tokenOwner).hasTokenBalance(token, tokenTotal - 10),
                getAccountBalance(htsCollector).hasTokenBalance(token, 10L),
                getAccountBalance(alice).hasTokenBalance(token, 0L),
                getAccountBalance(carol).hasTokenBalance(token, 0L));
    }

    @HapiTest
    final Stream<DynamicTest> transferWithFractionalCustomFeeMultipleRecipientsShouldRoundFeeNetOfTransfers() {
        return hapiTest(
                cryptoCreate(htsCollector).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(hbarCollector).balance(0L),
                cryptoCreate(tokenReceiver),
                cryptoCreate(tokenTreasury),
                cryptoCreate(carol),
                cryptoCreate(alice),
                cryptoCreate(tokenOwner).balance(ONE_MILLION_HBARS),
                tokenCreate(token)
                        .treasury(tokenTreasury)
                        .initialSupply(tokenTotal)
                        .payingWith(htsCollector)
                        .withCustom(fractionalFeeNetOfTransfers(
                                numerator, denominator, minHtsFee, OptionalLong.of(maxHtsFee), htsCollector)),
                tokenAssociate(alice, token),
                tokenAssociate(carol, token),
                tokenAssociate(tokenOwner, token),
                cryptoTransfer(moving(tokenTotal, token).between(tokenTreasury, tokenOwner)),
                cryptoTransfer(
                                moving(5L, token).between(tokenOwner, alice),
                                moving(5L, token).between(tokenOwner, carol))
                        .fee(ONE_HUNDRED_HBARS)
                        .payingWith(tokenOwner),
                getAccountBalance(htsCollector).hasTokenBalance(token, 2L),
                getAccountBalance(alice).hasTokenBalance(token, 5L),
                getAccountBalance(carol).hasTokenBalance(token, 5L),
                getAccountBalance(tokenOwner).hasTokenBalance(token, 988L));
    }

    @HapiTest
    final Stream<DynamicTest> transferWithFractionalCustomFeeNotEnoughBalance() {
        return hapiTest(
                cryptoCreate(htsCollector).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(hbarCollector).balance(0L),
                cryptoCreate(tokenReceiver),
                cryptoCreate(tokenTreasury),
                cryptoCreate(carol),
                cryptoCreate(alice),
                cryptoCreate(tokenOwner).balance(ONE_MILLION_HBARS),
                tokenCreate(token)
                        .treasury(tokenTreasury)
                        .initialSupply(tokenTotal)
                        .payingWith(htsCollector)
                        .withCustom(
                                fractionalFee(numerator, denominator, 10L, OptionalLong.of(maxHtsFee), htsCollector)),
                tokenAssociate(alice, token),
                tokenAssociate(carol, token),
                tokenAssociate(tokenOwner, token),
                cryptoTransfer(moving(tokenTotal, token).between(tokenTreasury, tokenOwner)),
                cryptoTransfer(moving(5L, token).between(tokenOwner, alice))
                        .fee(ONE_HUNDRED_HBARS)
                        .payingWith(tokenOwner)
                        .hasKnownStatus(ResponseCodeEnum.INSUFFICIENT_SENDER_ACCOUNT_BALANCE_FOR_CUSTOM_FEE),
                getAccountBalance(htsCollector).hasTokenBalance(token, 0));
    }

    @HapiTest
    final Stream<DynamicTest> transferWithFractionalCustomFeeMultipleRecipientsNotEnoughBalance() {
        return hapiTest(
                cryptoCreate(htsCollector).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(hbarCollector).balance(0L),
                cryptoCreate(tokenReceiver),
                cryptoCreate(tokenTreasury),
                cryptoCreate(carol),
                cryptoCreate(alice),
                cryptoCreate(tokenOwner).balance(ONE_MILLION_HBARS),
                tokenCreate(token)
                        .treasury(tokenTreasury)
                        .initialSupply(tokenTotal)
                        .payingWith(htsCollector)
                        .withCustom(
                                fractionalFee(numerator, denominator, 10L, OptionalLong.of(maxHtsFee), htsCollector)),
                tokenAssociate(alice, token),
                tokenAssociate(carol, token),
                tokenAssociate(tokenOwner, token),
                cryptoTransfer(moving(tokenTotal, token).between(tokenTreasury, tokenOwner)),
                cryptoTransfer(
                                moving(5L, token).between(tokenOwner, alice),
                                moving(5L, token).between(tokenOwner, htsCollector))
                        .fee(ONE_HUNDRED_HBARS)
                        .payingWith(tokenOwner)
                        .hasKnownStatus(ResponseCodeEnum.INSUFFICIENT_SENDER_ACCOUNT_BALANCE_FOR_CUSTOM_FEE));
    }

    @HapiTest
    final Stream<DynamicTest> transferMultipleTimesWithFractionalFeeTakenFromSender() {
        return hapiTest(
                cryptoCreate(htsCollector).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(tokenTreasury),
                cryptoCreate(tokenOwner),
                cryptoCreate(alice).balance(ONE_MILLION_HBARS),
                cryptoCreate(bob).balance(ONE_MILLION_HBARS),
                cryptoCreate(carol).balance(ONE_MILLION_HBARS),
                cryptoCreate(ivan),
                tokenCreate(token)
                        .treasury(tokenTreasury)
                        .initialSupply(tokenTotal)
                        .payingWith(htsCollector)
                        .withCustom(fractionalFeeNetOfTransfers(
                                numerator, denominator, minHtsFee, OptionalLong.of(9L), htsCollector)),
                tokenAssociate(alice, token),
                tokenAssociate(bob, token),
                tokenAssociate(carol, token),
                tokenAssociate(ivan, token),
                tokenAssociate(tokenOwner, token),
                cryptoTransfer(moving(tokenTotal, token).between(tokenTreasury, tokenOwner)),
                cryptoTransfer(moving(100L, token).between(tokenOwner, alice))
                        .fee(ONE_HUNDRED_HBARS)
                        .payingWith(tokenOwner),
                cryptoTransfer(moving(50L, token).between(alice, bob))
                        .fee(ONE_HUNDRED_HBARS)
                        .payingWith(alice),
                cryptoTransfer(moving(30L, token).between(bob, carol))
                        .fee(ONE_HUNDRED_HBARS)
                        .payingWith(bob),
                cryptoTransfer(moving(10L, token).between(carol, ivan))
                        .fee(ONE_HUNDRED_HBARS)
                        .payingWith(carol),
                getAccountBalance(htsCollector).hasTokenBalance(token, 19L),
                getAccountBalance(tokenOwner).hasTokenBalance(token, 891L),
                getAccountBalance(alice).hasTokenBalance(token, 45L),
                getAccountBalance(bob).hasTokenBalance(token, 17L),
                getAccountBalance(carol).hasTokenBalance(token, 18L));
    }

    @HapiTest
    final Stream<DynamicTest> transferMultipleTimesWithFractionalFeeTakenFromReceiver() {
        return hapiTest(
                cryptoCreate(htsCollector).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(tokenTreasury),
                cryptoCreate(tokenOwner),
                cryptoCreate(alice).balance(ONE_MILLION_HBARS),
                cryptoCreate(bob).balance(ONE_MILLION_HBARS),
                cryptoCreate(carol).balance(ONE_MILLION_HBARS),
                cryptoCreate(ivan).balance(ONE_MILLION_HBARS),
                tokenCreate(token)
                        .treasury(tokenTreasury)
                        .initialSupply(tokenTotal)
                        .payingWith(htsCollector)
                        .withCustom(
                                fractionalFee(numerator, denominator, minHtsFee, OptionalLong.of(9L), htsCollector)),
                tokenAssociate(alice, token),
                tokenAssociate(bob, token),
                tokenAssociate(carol, token),
                tokenAssociate(ivan, token),
                tokenAssociate(tokenOwner, token),
                cryptoTransfer(moving(tokenTotal, token).between(tokenTreasury, tokenOwner)),
                cryptoTransfer(moving(100L, token).between(tokenOwner, alice))
                        .fee(ONE_HUNDRED_HBARS)
                        .payingWith(alice),
                cryptoTransfer(moving(50L, token).between(alice, bob))
                        .fee(ONE_HUNDRED_HBARS)
                        .payingWith(bob),
                cryptoTransfer(moving(30L, token).between(bob, carol))
                        .fee(ONE_HUNDRED_HBARS)
                        .payingWith(carol),
                cryptoTransfer(moving(10L, token).between(carol, ivan))
                        .fee(ONE_HUNDRED_HBARS)
                        .payingWith(ivan),
                getAccountBalance(htsCollector).hasTokenBalance(token, 19L),
                getAccountBalance(tokenOwner).hasTokenBalance(token, 900L),
                getAccountBalance(alice).hasTokenBalance(token, 41L),
                getAccountBalance(bob).hasTokenBalance(token, 15L),
                getAccountBalance(carol).hasTokenBalance(token, 17L));
    }

    @HapiTest
    final Stream<DynamicTest> transferWithFractionalCustomFeeAndHollowAccountCollector() {
        return hapiTest(flattened(
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(tokenReceiver),
                cryptoCreate(tokenTreasury).balance(ONE_MILLION_HBARS),
                cryptoCreate(tokenOwner).balance(ONE_MILLION_HBARS),
                createHollowAccountFrom(SECP_256K1_SOURCE_KEY),
                withOpContext((spec, opLog) -> {
                    final var hollowAccountCollector =
                            spec.registry().getAccountIdName(spec.registry().getAccountAlias(SECP_256K1_SOURCE_KEY));
                    allRunFor(
                            spec,
                            tokenCreate(token)
                                    .treasury(tokenTreasury)
                                    .initialSupply(tokenTotal)
                                    .payingWith(SECP_256K1_SOURCE_KEY)
                                    .sigMapPrefixes(uniqueWithFullPrefixesFor(SECP_256K1_SOURCE_KEY))
                                    .via(TRANSFER_TXN_2)
                                    .withCustom(fractionalFee(
                                            numerator,
                                            denominator,
                                            minHtsFee,
                                            OptionalLong.of(maxHtsFee),
                                            hollowAccountCollector)),
                            tokenAssociate(tokenReceiver, token),
                            tokenAssociate(tokenOwner, token),
                            cryptoTransfer(moving(tokenTotal, token).between(tokenTreasury, tokenOwner)),
                            cryptoTransfer(moving(100L, token).between(tokenOwner, tokenReceiver))
                                    .fee(ONE_HUNDRED_HBARS)
                                    .payingWith(tokenOwner),
                            getAccountBalance(tokenOwner).hasTokenBalance(token, 900L),
                            getAccountBalance(tokenReceiver).hasTokenBalance(token, 90L),
                            getAccountBalance(hollowAccountCollector).hasTokenBalance(token, 10L));
                })));
    }
}
