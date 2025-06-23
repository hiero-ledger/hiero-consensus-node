// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.token;

import static com.google.protobuf.ByteString.copyFromUtf8;
import static com.hedera.services.bdd.junit.TestTags.TOKEN;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.NoFungibleTransfers.changingNoFungibleBalances;
import static com.hedera.services.bdd.spec.assertions.NoNftTransfers.changingNoNftOwners;
import static com.hedera.services.bdd.spec.assertions.SomeFungibleTransfers.changingFungibleBalances;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTokenInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTokenNftInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedHbarFee;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedHbarFeeInheritingRoyaltyCollector;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedHtsFee;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedHtsFeeInheritingRoyaltyCollector;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fractionalFee;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fractionalFeeNetOfTransfers;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.royaltyFeeNoFallback;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.royaltyFeeWithFallback;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingHbar;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingUnique;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.blockingOrder;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.HBAR_TOKEN_SENTINEL;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.TOKEN_TREASURY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_AMOUNT_TRANSFERS_ONLY_ALLOWED_FOR_FUNGIBLE_COMMON;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_SENDER_ACCOUNT_BALANCE_FOR_CUSTOM_FEE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_NOT_ASSOCIATED_TO_ACCOUNT;
import static com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hederahashgraph.api.proto.java.CustomFee;
import com.hederahashgraph.api.proto.java.TokenType;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

// This test cases are direct copies of TokenTransactSpecs. The difference here is that
// we are wrapping the operations in an atomic batch to confirm that everything works as expected.
@HapiTestLifecycle
@Tag(TOKEN)
public class AtomicTokenTransactSpecs {

    public static final String PAYER = "payer";
    public static final String CIVILIAN = "civilian";
    public static final String BENEFICIARY = "beneficiary";
    public static final String WEST_WIND_ART = "westWindArt";
    public static final String FREEZE_KEY = "freezeKey";
    public static final String TXN_FROM_TREASURY = "txnFromTreasury";
    public static final String TOKEN_WITH_FRACTIONAL_FEE = "TokenWithFractionalFee";
    public static final String DEBBIE = "Debbie";
    public static final String COLLECTION = "collection";
    public static final String SUPPLY_KEY = "supplyKey";
    public static final String SENTINEL_ACCOUNT = "0.0.0";
    public static final String EDGAR = "Edgar";
    public static final String TOKEN_WITH_NESTED_FEE = "TokenWithNestedFee";
    public static final String NESTED_TOKEN_TREASURY = "NestedTokenTreasury";
    public static final String AMELIE = "amelie";
    public static final String FUGUES_AND_FANTASTICS = "Fugues and fantastics";
    public static final String SUPPLY = "supply";
    public static final String TRANSFER_TXN = "transferTxn";
    private static final String BATCH_OPERATOR = "batchOperator";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(
                Map.of("atomicBatch.isEnabled", "true", "atomicBatch.maxNumberOfTransactions", "50"));
    }

    @HapiTest
    final Stream<DynamicTest> fixedHbarCaseStudy() {
        final var alice = "Alice";
        final var bob = "Bob";
        final var tokenWithHbarFee = "TokenWithHbarFee";
        final var treasuryForToken = TOKEN_TREASURY;
        final var supplyKey = "antique";

        final var txnFromTreasury = TXN_FROM_TREASURY;
        final var txnFromAlice = "txnFromAlice";

        return hapiTest(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                cryptoCreate(TOKEN_TREASURY).payingWith(BATCH_OPERATOR),
                newKeyNamed(supplyKey),
                cryptoCreate(alice).balance(ONE_HUNDRED_HBARS).payingWith(BATCH_OPERATOR),
                cryptoCreate(bob).payingWith(BATCH_OPERATOR),
                cryptoCreate(treasuryForToken).balance(ONE_HUNDRED_HBARS).payingWith(BATCH_OPERATOR),
                atomicBatch(tokenCreate(tokenWithHbarFee)
                                .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                                .supplyKey(supplyKey)
                                .initialSupply(0L)
                                .treasury(treasuryForToken)
                                .withCustom(fixedHbarFee(ONE_HBAR, treasuryForToken))
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                mintToken(tokenWithHbarFee, List.of(copyFromUtf8("First!"))).payingWith(BATCH_OPERATOR),
                mintToken(tokenWithHbarFee, List.of(copyFromUtf8("Second!"))).payingWith(BATCH_OPERATOR),
                tokenAssociate(alice, tokenWithHbarFee).payingWith(BATCH_OPERATOR),
                tokenAssociate(bob, tokenWithHbarFee).payingWith(BATCH_OPERATOR),
                cryptoTransfer(movingUnique(tokenWithHbarFee, 2L).between(treasuryForToken, alice))
                        .payingWith(GENESIS)
                        .fee(ONE_HBAR)
                        .via(txnFromTreasury)
                        .payingWith(BATCH_OPERATOR),
                cryptoTransfer(movingUnique(tokenWithHbarFee, 2L).between(alice, bob))
                        .payingWith(GENESIS)
                        .fee(ONE_HBAR)
                        .via(txnFromAlice)
                        .payingWith(BATCH_OPERATOR),
                getTxnRecord(txnFromTreasury).hasNftTransfer(tokenWithHbarFee, treasuryForToken, alice, 2L),
                getTxnRecord(txnFromAlice)
                        .hasNftTransfer(tokenWithHbarFee, alice, bob, 2L)
                        .hasAssessedCustomFee(HBAR_TOKEN_SENTINEL, treasuryForToken, ONE_HBAR)
                        .hasHbarAmount(treasuryForToken, ONE_HBAR)
                        .hasHbarAmount(alice, -ONE_HBAR),
                getAccountBalance(bob).hasTokenBalance(tokenWithHbarFee, 1L),
                getAccountBalance(alice)
                        .hasTokenBalance(tokenWithHbarFee, 0L)
                        .hasTinyBars(ONE_HUNDRED_HBARS - ONE_HBAR),
                getAccountBalance(treasuryForToken)
                        .hasTokenBalance(tokenWithHbarFee, 1L)
                        .hasTinyBars(ONE_HUNDRED_HBARS + ONE_HBAR));
    }

    @HapiTest
    final Stream<DynamicTest> fractionalCaseStudy() {
        final var alice = "Alice";
        final var bob = "Bob";
        final var tokenWithFractionalFee = TOKEN_WITH_FRACTIONAL_FEE;
        final var treasuryForToken = TOKEN_TREASURY;
        final var txnFromTreasury = TXN_FROM_TREASURY;
        final var txnFromBob = "txnFromBob";

        return hapiTest(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                cryptoCreate(alice).payingWith(BATCH_OPERATOR),
                cryptoCreate(bob).payingWith(BATCH_OPERATOR),
                cryptoCreate(treasuryForToken).payingWith(BATCH_OPERATOR),
                atomicBatch(tokenCreate(tokenWithFractionalFee)
                                .initialSupply(Long.MAX_VALUE)
                                .treasury(treasuryForToken)
                                .withCustom(fractionalFee(1L, 100L, 1L, OptionalLong.of(5L), treasuryForToken))
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                tokenAssociate(alice, tokenWithFractionalFee).payingWith(BATCH_OPERATOR),
                tokenAssociate(bob, tokenWithFractionalFee).payingWith(BATCH_OPERATOR),
                cryptoTransfer(moving(1_000_000L, tokenWithFractionalFee).between(treasuryForToken, bob))
                        .payingWith(treasuryForToken)
                        .fee(ONE_HBAR)
                        .via(txnFromTreasury)
                        .payingWith(BATCH_OPERATOR),
                cryptoTransfer(moving(1_000L, tokenWithFractionalFee).between(bob, alice))
                        .payingWith(bob)
                        .fee(ONE_HBAR)
                        .via(txnFromBob)
                        .payingWith(BATCH_OPERATOR),
                getTxnRecord(txnFromTreasury)
                        .hasTokenAmount(tokenWithFractionalFee, bob, 1_000_000L)
                        .hasTokenAmount(tokenWithFractionalFee, treasuryForToken, -1_000_000L),
                getTxnRecord(txnFromBob)
                        .hasTokenAmount(tokenWithFractionalFee, bob, -1_000L)
                        .hasTokenAmount(tokenWithFractionalFee, alice, 995L)
                        .hasAssessedCustomFee(tokenWithFractionalFee, treasuryForToken, 5L)
                        .hasTokenAmount(tokenWithFractionalFee, treasuryForToken, 5L),
                getAccountBalance(alice).hasTokenBalance(tokenWithFractionalFee, 995L),
                getAccountBalance(bob).hasTokenBalance(tokenWithFractionalFee, 1_000_000L - 1_000L),
                getAccountBalance(treasuryForToken)
                        .hasTokenBalance(tokenWithFractionalFee, Long.MAX_VALUE - 1_000_000L + 5L));
    }

    @HapiTest
    final Stream<DynamicTest> fractionalNetOfTransfersCaseStudy() {
        final var gerry = "gerry";
        final var horace = "horace";
        final var useCaseToken = TOKEN_WITH_FRACTIONAL_FEE;
        final var treasuryForToken = TOKEN_TREASURY;

        final var txnFromTreasury = TXN_FROM_TREASURY;
        final var txnFromHorace = "txnFromHorace";

        return hapiTest(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                cryptoCreate(gerry).payingWith(BATCH_OPERATOR),
                cryptoCreate(horace).payingWith(BATCH_OPERATOR),
                cryptoCreate(treasuryForToken).payingWith(BATCH_OPERATOR),
                atomicBatch(tokenCreate(useCaseToken)
                                .initialSupply(Long.MAX_VALUE)
                                .treasury(treasuryForToken)
                                .withCustom(fractionalFeeNetOfTransfers(
                                        1L, 100L, 1L, OptionalLong.of(5L), treasuryForToken))
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                tokenAssociate(gerry, useCaseToken).payingWith(BATCH_OPERATOR),
                tokenAssociate(horace, useCaseToken).payingWith(BATCH_OPERATOR),
                cryptoTransfer(moving(1_000_000L, useCaseToken).between(treasuryForToken, horace))
                        .payingWith(treasuryForToken)
                        .fee(ONE_HBAR)
                        .via(txnFromTreasury),
                cryptoTransfer(moving(1_000L, useCaseToken).between(horace, gerry))
                        .payingWith(horace)
                        .fee(ONE_HBAR)
                        .via(txnFromHorace),
                getTxnRecord(txnFromTreasury)
                        .hasTokenAmount(useCaseToken, horace, 1_000_000L)
                        .hasTokenAmount(useCaseToken, treasuryForToken, -1_000_000L),
                getTxnRecord(txnFromHorace)
                        .hasTokenAmount(useCaseToken, horace, -1_005L)
                        .hasTokenAmount(useCaseToken, gerry, 1000L)
                        .hasAssessedCustomFee(useCaseToken, treasuryForToken, 5L)
                        .hasTokenAmount(useCaseToken, treasuryForToken, 5L),
                getAccountBalance(gerry).hasTokenBalance(useCaseToken, 1000L),
                getAccountBalance(horace).hasTokenBalance(useCaseToken, 1_000_000L - 1_005L),
                getAccountBalance(treasuryForToken).hasTokenBalance(useCaseToken, Long.MAX_VALUE - 1_000_000L + 5L));
    }

    @HapiTest
    final Stream<DynamicTest> simpleHtsFeeCaseStudy() {
        final var claire = "Claire";
        final var debbie = DEBBIE;
        final var simpleHtsFeeToken = "SimpleHtsFeeToken";
        final var commissionPaymentToken = "commissionPaymentToken";
        final var treasuryForToken = TOKEN_TREASURY;

        final var txnFromTreasury = TXN_FROM_TREASURY;
        final var txnFromClaire = "txnFromClaire";

        return hapiTest(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                cryptoCreate(claire).payingWith(BATCH_OPERATOR),
                cryptoCreate(debbie).payingWith(BATCH_OPERATOR),
                cryptoCreate(treasuryForToken).payingWith(BATCH_OPERATOR),
                tokenCreate(commissionPaymentToken)
                        .initialSupply(Long.MAX_VALUE)
                        .treasury(treasuryForToken)
                        .payingWith(BATCH_OPERATOR),
                atomicBatch(tokenCreate(simpleHtsFeeToken)
                                .initialSupply(Long.MAX_VALUE)
                                .treasury(treasuryForToken)
                                .withCustom(fixedHtsFee(2L, commissionPaymentToken, treasuryForToken))
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                tokenAssociate(claire, List.of(simpleHtsFeeToken, commissionPaymentToken))
                        .payingWith(BATCH_OPERATOR),
                tokenAssociate(debbie, simpleHtsFeeToken).payingWith(BATCH_OPERATOR),
                cryptoTransfer(
                                moving(1_000L, commissionPaymentToken).between(treasuryForToken, claire),
                                moving(1_000L, simpleHtsFeeToken).between(treasuryForToken, claire))
                        .payingWith(treasuryForToken)
                        .fee(ONE_HBAR)
                        .via(txnFromTreasury),
                cryptoTransfer(moving(100L, simpleHtsFeeToken).between(claire, debbie))
                        .payingWith(claire)
                        .fee(ONE_HBAR)
                        .via(txnFromClaire),
                getTxnRecord(txnFromTreasury)
                        .hasTokenAmount(commissionPaymentToken, claire, 1_000L)
                        .hasTokenAmount(commissionPaymentToken, treasuryForToken, -1_000L)
                        .hasTokenAmount(simpleHtsFeeToken, claire, 1_000L)
                        .hasTokenAmount(simpleHtsFeeToken, treasuryForToken, -1_000L),
                getTxnRecord(txnFromClaire)
                        .hasTokenAmount(simpleHtsFeeToken, debbie, 100L)
                        .hasTokenAmount(simpleHtsFeeToken, claire, -100L)
                        .hasAssessedCustomFee(commissionPaymentToken, treasuryForToken, 2L)
                        .hasTokenAmount(commissionPaymentToken, treasuryForToken, 2L)
                        .hasTokenAmount(commissionPaymentToken, claire, -2L),
                getAccountBalance(debbie).hasTokenBalance(simpleHtsFeeToken, 100L),
                getAccountBalance(claire)
                        .hasTokenBalance(simpleHtsFeeToken, 1_000L - 100L)
                        .hasTokenBalance(commissionPaymentToken, 1_000L - 2L),
                getAccountBalance(treasuryForToken)
                        .hasTokenBalance(simpleHtsFeeToken, Long.MAX_VALUE - 1_000L)
                        .hasTokenBalance(commissionPaymentToken, Long.MAX_VALUE - 1_000L + 2L));
    }

    @HapiTest
    final Stream<DynamicTest> nestedHbarCaseStudy() {
        final var debbie = DEBBIE;
        final var edgar = EDGAR;
        final var tokenWithHbarFee = "TokenWithHbarFee";
        final var tokenWithNestedFee = TOKEN_WITH_NESTED_FEE;
        final var treasuryForTopLevelCollection = TOKEN_TREASURY;
        final var treasuryForNestedCollection = NESTED_TOKEN_TREASURY;

        final var txnFromTreasury = TXN_FROM_TREASURY;
        final var txnFromDebbie = "txnFromDebbie";

        return hapiTest(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                cryptoCreate(debbie).balance(ONE_HUNDRED_HBARS).payingWith(BATCH_OPERATOR),
                cryptoCreate(edgar).payingWith(BATCH_OPERATOR),
                cryptoCreate(treasuryForTopLevelCollection).payingWith(BATCH_OPERATOR),
                cryptoCreate(treasuryForNestedCollection)
                        .balance(ONE_HUNDRED_HBARS)
                        .payingWith(BATCH_OPERATOR),
                atomicBatch(tokenCreate(tokenWithHbarFee)
                                .initialSupply(Long.MAX_VALUE)
                                .treasury(treasuryForNestedCollection)
                                .withCustom(fixedHbarFee(ONE_HBAR, treasuryForNestedCollection))
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                tokenAssociate(treasuryForTopLevelCollection, tokenWithHbarFee).payingWith(BATCH_OPERATOR),
                tokenCreate(tokenWithNestedFee)
                        .initialSupply(Long.MAX_VALUE)
                        .treasury(treasuryForTopLevelCollection)
                        .withCustom(fixedHtsFee(1L, tokenWithHbarFee, treasuryForTopLevelCollection))
                        .payingWith(BATCH_OPERATOR),
                tokenAssociate(debbie, List.of(tokenWithHbarFee, tokenWithNestedFee))
                        .payingWith(BATCH_OPERATOR),
                tokenAssociate(edgar, tokenWithNestedFee).payingWith(BATCH_OPERATOR),
                cryptoTransfer(
                                moving(1_000L, tokenWithHbarFee).between(treasuryForNestedCollection, debbie),
                                moving(1_000L, tokenWithNestedFee).between(treasuryForTopLevelCollection, debbie))
                        .payingWith(GENESIS)
                        .fee(ONE_HBAR)
                        .via(txnFromTreasury),
                cryptoTransfer(moving(1L, tokenWithNestedFee).between(debbie, edgar))
                        .payingWith(GENESIS)
                        .fee(ONE_HBAR)
                        .via(txnFromDebbie),
                getTxnRecord(txnFromTreasury)
                        .hasTokenAmount(tokenWithHbarFee, debbie, 1_000L)
                        .hasTokenAmount(tokenWithHbarFee, treasuryForNestedCollection, -1_000L)
                        .hasTokenAmount(tokenWithNestedFee, debbie, 1_000L)
                        .hasTokenAmount(tokenWithNestedFee, treasuryForTopLevelCollection, -1_000L),
                getTxnRecord(txnFromDebbie)
                        .hasTokenAmount(tokenWithNestedFee, edgar, 1L)
                        .hasTokenAmount(tokenWithNestedFee, debbie, -1L)
                        .hasAssessedCustomFee(tokenWithHbarFee, treasuryForTopLevelCollection, 1L)
                        .hasTokenAmount(tokenWithHbarFee, treasuryForTopLevelCollection, 1L)
                        .hasTokenAmount(tokenWithHbarFee, debbie, -1L)
                        .hasAssessedCustomFee(HBAR_TOKEN_SENTINEL, treasuryForNestedCollection, ONE_HBAR)
                        .hasHbarAmount(treasuryForNestedCollection, ONE_HBAR)
                        .hasHbarAmount(debbie, -ONE_HBAR),
                getAccountBalance(edgar).hasTokenBalance(tokenWithNestedFee, 1L),
                getAccountBalance(debbie)
                        .hasTinyBars(ONE_HUNDRED_HBARS - ONE_HBAR)
                        .hasTokenBalance(tokenWithHbarFee, 1_000L - 1L)
                        .hasTokenBalance(tokenWithNestedFee, 1_000L - 1L),
                getAccountBalance(treasuryForTopLevelCollection)
                        .hasTokenBalance(tokenWithNestedFee, Long.MAX_VALUE - 1_000L)
                        .hasTokenBalance(tokenWithHbarFee, 1L),
                getAccountBalance(treasuryForNestedCollection)
                        .hasTinyBars(ONE_HUNDRED_HBARS + ONE_HBAR)
                        .hasTokenBalance(tokenWithHbarFee, Long.MAX_VALUE - 1_000L));
    }

    @HapiTest
    final Stream<DynamicTest> nestedFractionalCaseStudy() {
        final var edgar = EDGAR;
        final var fern = "Fern";
        final var tokenWithFractionalFee = TOKEN_WITH_FRACTIONAL_FEE;
        final var tokenWithNestedFee = TOKEN_WITH_NESTED_FEE;
        final var treasuryForTopLevelCollection = TOKEN_TREASURY;
        final var treasuryForNestedCollection = NESTED_TOKEN_TREASURY;

        final var txnFromTreasury = TXN_FROM_TREASURY;
        final var txnFromEdgar = "txnFromEdgar";

        return hapiTest(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                cryptoCreate(edgar).payingWith(BATCH_OPERATOR),
                cryptoCreate(fern).payingWith(BATCH_OPERATOR),
                cryptoCreate(treasuryForTopLevelCollection).payingWith(BATCH_OPERATOR),
                cryptoCreate(treasuryForNestedCollection).payingWith(BATCH_OPERATOR),
                atomicBatch(tokenCreate(tokenWithFractionalFee)
                                .initialSupply(Long.MAX_VALUE)
                                .treasury(treasuryForNestedCollection)
                                .withCustom(
                                        fractionalFee(1L, 100L, 1L, OptionalLong.of(5L), treasuryForNestedCollection))
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                tokenAssociate(treasuryForTopLevelCollection, tokenWithFractionalFee)
                        .payingWith(BATCH_OPERATOR),
                atomicBatch(tokenCreate(tokenWithNestedFee)
                                .initialSupply(Long.MAX_VALUE)
                                .treasury(treasuryForTopLevelCollection)
                                .withCustom(fixedHtsFee(50L, tokenWithFractionalFee, treasuryForTopLevelCollection))
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                tokenAssociate(edgar, List.of(tokenWithFractionalFee, tokenWithNestedFee))
                        .payingWith(BATCH_OPERATOR),
                tokenAssociate(fern, tokenWithNestedFee).payingWith(BATCH_OPERATOR),
                cryptoTransfer(
                                moving(1_000L, tokenWithFractionalFee).between(treasuryForNestedCollection, edgar),
                                moving(1_000L, tokenWithNestedFee).between(treasuryForTopLevelCollection, edgar))
                        .payingWith(treasuryForNestedCollection)
                        .fee(ONE_HBAR)
                        .via(txnFromTreasury),
                cryptoTransfer(moving(10L, tokenWithNestedFee).between(edgar, fern))
                        .payingWith(edgar)
                        .fee(ONE_HBAR)
                        .via(txnFromEdgar),
                getTxnRecord(txnFromTreasury)
                        .hasTokenAmount(tokenWithFractionalFee, edgar, 1_000L)
                        .hasTokenAmount(tokenWithFractionalFee, treasuryForNestedCollection, -1_000L)
                        .hasTokenAmount(tokenWithNestedFee, edgar, 1_000L)
                        .hasTokenAmount(tokenWithNestedFee, treasuryForTopLevelCollection, -1_000L),
                getTxnRecord(txnFromEdgar)
                        .hasTokenAmount(tokenWithNestedFee, fern, 10L)
                        .hasTokenAmount(tokenWithNestedFee, edgar, -10L)
                        .hasAssessedCustomFee(tokenWithFractionalFee, treasuryForTopLevelCollection, 50L)
                        .hasTokenAmount(tokenWithFractionalFee, treasuryForTopLevelCollection, 49L)
                        .hasTokenAmount(tokenWithFractionalFee, edgar, -50L)
                        .hasAssessedCustomFee(tokenWithFractionalFee, treasuryForNestedCollection, 1L)
                        .hasTokenAmount(tokenWithFractionalFee, treasuryForNestedCollection, 1L),
                getAccountBalance(fern).hasTokenBalance(tokenWithNestedFee, 10L),
                getAccountBalance(edgar)
                        .hasTokenBalance(tokenWithNestedFee, 1_000L - 10L)
                        .hasTokenBalance(tokenWithFractionalFee, 1_000L - 50L),
                getAccountBalance(treasuryForTopLevelCollection)
                        .hasTokenBalance(tokenWithNestedFee, Long.MAX_VALUE - 1_000L)
                        .hasTokenBalance(tokenWithFractionalFee, 49L),
                getAccountBalance(treasuryForNestedCollection)
                        .hasTokenBalance(tokenWithFractionalFee, Long.MAX_VALUE - 1_000L + 1L));
    }

    @HapiTest
    final Stream<DynamicTest> multipleRoyaltyFallbackCaseStudy() {
        final var zephyr = "zephyr";
        final var amelie = AMELIE;
        final var usdcTreasury = "bank";
        final var westWindTreasury = COLLECTION;
        final var westWindArt = WEST_WIND_ART;
        final var westWindDirector = "director";
        final var westWindOwner = "owner";
        final var usdc = "USDC";
        final var supplyKey = SUPPLY;

        final var txnFromTreasury = TXN_FROM_TREASURY;
        final var txnFromZephyr = "txnFromZephyr";

        return hapiTest(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                newKeyNamed(supplyKey),
                cryptoCreate(zephyr).payingWith(BATCH_OPERATOR),
                cryptoCreate(amelie),
                cryptoCreate(amelie).payingWith(BATCH_OPERATOR),
                cryptoCreate(usdcTreasury).payingWith(BATCH_OPERATOR),
                cryptoCreate(westWindTreasury).payingWith(BATCH_OPERATOR),
                cryptoCreate(westWindDirector).payingWith(BATCH_OPERATOR),
                cryptoCreate(westWindOwner).payingWith(BATCH_OPERATOR),
                tokenCreate(usdc).treasury(usdcTreasury).payingWith(BATCH_OPERATOR),
                tokenAssociate(westWindTreasury, usdc).payingWith(BATCH_OPERATOR),
                tokenAssociate(westWindOwner, usdc).payingWith(BATCH_OPERATOR),
                atomicBatch(tokenCreate(westWindArt)
                                .tokenType(NON_FUNGIBLE_UNIQUE)
                                .initialSupply(0)
                                .supplyKey(supplyKey)
                                .treasury(westWindTreasury)
                                .withCustom(royaltyFeeWithFallback(
                                        10, 100, fixedHtsFeeInheritingRoyaltyCollector(1, usdc), westWindTreasury))
                                .withCustom(royaltyFeeNoFallback(10, 100, westWindDirector))
                                .withCustom(royaltyFeeWithFallback(
                                        5, 100, fixedHtsFeeInheritingRoyaltyCollector(1, usdc), westWindOwner))
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                tokenAssociate(amelie, List.of(westWindArt, usdc)).payingWith(BATCH_OPERATOR),
                tokenAssociate(zephyr, List.of(westWindArt, usdc)).payingWith(BATCH_OPERATOR),
                mintToken(westWindArt, List.of(copyFromUtf8(FUGUES_AND_FANTASTICS)))
                        .payingWith(BATCH_OPERATOR),
                cryptoTransfer(movingUnique(westWindArt, 1L).between(westWindTreasury, zephyr))
                        .fee(ONE_HBAR)
                        .via(txnFromTreasury)
                        .payingWith(BATCH_OPERATOR),
                cryptoTransfer(movingUnique(westWindArt, 1L).between(zephyr, amelie))
                        .payingWith(zephyr)
                        .fee(ONE_HBAR)
                        .hasKnownStatus(INVALID_SIGNATURE),
                cryptoTransfer(movingUnique(westWindArt, 1L).between(zephyr, amelie))
                        .signedBy(amelie, zephyr)
                        .payingWith(zephyr)
                        .fee(ONE_HBAR)
                        .hasKnownStatus(INSUFFICIENT_SENDER_ACCOUNT_BALANCE_FOR_CUSTOM_FEE),
                cryptoTransfer(moving(2, usdc).between(usdcTreasury, amelie)),
                cryptoTransfer(movingUnique(westWindArt, 1L).between(zephyr, amelie))
                        .signedBy(amelie, zephyr)
                        .payingWith(zephyr)
                        .fee(ONE_HBAR)
                        .via(txnFromZephyr),
                getTxnRecord(txnFromTreasury),
                getTxnRecord(txnFromZephyr));
    }

    @HapiTest
    final Stream<DynamicTest> respondsCorrectlyWhenNonFungibleTokenWithRoyaltyUsedInTransferList() {
        final var supplyKey = "misc";
        final var nonfungible = "nonfungible";
        final var beneficiary = BENEFICIARY;

        return hapiTest(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                cryptoCreate(CIVILIAN).maxAutomaticTokenAssociations(10).payingWith(BATCH_OPERATOR),
                cryptoCreate(beneficiary).maxAutomaticTokenAssociations(10).payingWith(BATCH_OPERATOR),
                cryptoCreate(TOKEN_TREASURY).payingWith(BATCH_OPERATOR),
                newKeyNamed(supplyKey),
                atomicBatch(tokenCreate(nonfungible)
                                .tokenType(NON_FUNGIBLE_UNIQUE)
                                .supplyKey(supplyKey)
                                .initialSupply(0L)
                                .withCustom(royaltyFeeWithFallback(
                                        1, 2, fixedHbarFeeInheritingRoyaltyCollector(100), TOKEN_TREASURY))
                                .treasury(TOKEN_TREASURY)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                mintToken(nonfungible, List.of(copyFromUtf8("a"), copyFromUtf8("aa"), copyFromUtf8("aaa")))
                        .payingWith(BATCH_OPERATOR),
                cryptoTransfer(movingUnique(nonfungible, 1L, 2L, 3L).between(TOKEN_TREASURY, CIVILIAN))
                        .signedBy(DEFAULT_PAYER, TOKEN_TREASURY, CIVILIAN)
                        .fee(ONE_HBAR)
                        .payingWith(BATCH_OPERATOR),
                cryptoTransfer(moving(1, nonfungible).between(CIVILIAN, beneficiary))
                        .signedBy(DEFAULT_PAYER, CIVILIAN, beneficiary)
                        .fee(ONE_HBAR)
                        .hasKnownStatus(ACCOUNT_AMOUNT_TRANSFERS_ONLY_ALLOWED_FOR_FUNGIBLE_COMMON));
    }

    @HapiTest
    final Stream<DynamicTest> royaltyAndFractionalTogetherCaseStudy() {
        final var alice = "alice";
        final var amelie = AMELIE;
        final var usdcTreasury = "bank";
        final var usdcCollector = "usdcFees";
        final var westWindTreasury = COLLECTION;
        final var westWindArt = WEST_WIND_ART;
        final var usdc = "USDC";
        final var supplyKey = SUPPLY;

        final var txnFromTreasury = TXN_FROM_TREASURY;
        final var txnFromAmelie = "txnFromAmelie";

        return hapiTest(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                newKeyNamed(supplyKey),
                cryptoCreate(alice).balance(10 * ONE_HUNDRED_HBARS).payingWith(BATCH_OPERATOR),
                cryptoCreate(amelie).payingWith(BATCH_OPERATOR),
                cryptoCreate(usdcTreasury).payingWith(BATCH_OPERATOR),
                cryptoCreate(usdcCollector).payingWith(BATCH_OPERATOR),
                cryptoCreate(westWindTreasury).payingWith(BATCH_OPERATOR),
                atomicBatch(tokenCreate(usdc)
                                .signedBy(DEFAULT_PAYER, usdcTreasury, usdcCollector)
                                .initialSupply(Long.MAX_VALUE)
                                .withCustom(fractionalFee(1, 2, 0, OptionalLong.empty(), usdcCollector))
                                .treasury(usdcTreasury)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                tokenAssociate(westWindTreasury, usdc).payingWith(BATCH_OPERATOR),
                atomicBatch(tokenCreate(westWindArt)
                                .tokenType(NON_FUNGIBLE_UNIQUE)
                                .initialSupply(0)
                                .supplyKey(supplyKey)
                                .treasury(westWindTreasury)
                                .withCustom(royaltyFeeWithFallback(
                                        1, 100, fixedHtsFeeInheritingRoyaltyCollector(1, usdc), westWindTreasury))
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                tokenAssociate(amelie, List.of(westWindArt, usdc)).payingWith(BATCH_OPERATOR),
                tokenAssociate(alice, List.of(westWindArt, usdc)).payingWith(BATCH_OPERATOR),
                mintToken(westWindArt, List.of(copyFromUtf8(FUGUES_AND_FANTASTICS)))
                        .payingWith(BATCH_OPERATOR),
                cryptoTransfer(moving(200, usdc).between(usdcTreasury, alice))
                        .fee(ONE_HBAR)
                        .payingWith(BATCH_OPERATOR),
                cryptoTransfer(movingUnique(westWindArt, 1L).between(westWindTreasury, amelie))
                        .fee(ONE_HBAR)
                        .via(txnFromTreasury)
                        .payingWith(BATCH_OPERATOR),
                cryptoTransfer(
                                movingUnique(westWindArt, 1L).between(amelie, alice),
                                moving(200, usdc).between(alice, amelie),
                                movingHbar(10 * ONE_HUNDRED_HBARS)
                                        .between(alice, amelie)) // 100 USDC fractional fee,  royalty 2 USDC
                        .signedBy(amelie, alice)
                        .payingWith(amelie)
                        .via(txnFromAmelie)
                        .fee(ONE_HBAR),
                getTxnRecord(txnFromAmelie));
    }

    @HapiTest
    final Stream<DynamicTest> normalRoyaltyCaseStudy() {
        final var alice = "alice";
        final var amelie = AMELIE;
        final var usdcTreasury = "bank";
        final var westWindTreasury = COLLECTION;
        final var westWindArt = WEST_WIND_ART;
        final var usdc = "USDC";
        final var supplyKey = SUPPLY;

        final var txnFromTreasury = TXN_FROM_TREASURY;
        final var txnFromAmelie = "txnFromAmelie";

        return hapiTest(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                newKeyNamed(supplyKey),
                cryptoCreate(alice).balance(10 * ONE_HUNDRED_HBARS).payingWith(BATCH_OPERATOR),
                cryptoCreate(amelie).payingWith(BATCH_OPERATOR),
                cryptoCreate(usdcTreasury).payingWith(BATCH_OPERATOR),
                cryptoCreate(westWindTreasury).payingWith(BATCH_OPERATOR),
                tokenCreate(usdc)
                        .initialSupply(Long.MAX_VALUE)
                        .treasury(usdcTreasury)
                        .payingWith(BATCH_OPERATOR),
                tokenAssociate(westWindTreasury, usdc).payingWith(BATCH_OPERATOR),
                atomicBatch(tokenCreate(westWindArt)
                                .tokenType(NON_FUNGIBLE_UNIQUE)
                                .initialSupply(0)
                                .supplyKey(supplyKey)
                                .treasury(westWindTreasury)
                                .withCustom(royaltyFeeWithFallback(
                                        1, 100, fixedHtsFeeInheritingRoyaltyCollector(1, usdc), westWindTreasury))
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                tokenAssociate(amelie, List.of(westWindArt, usdc)).payingWith(BATCH_OPERATOR),
                tokenAssociate(alice, List.of(westWindArt, usdc)).payingWith(BATCH_OPERATOR),
                mintToken(westWindArt, List.of(copyFromUtf8(FUGUES_AND_FANTASTICS)))
                        .payingWith(BATCH_OPERATOR),
                cryptoTransfer(moving(200, usdc).between(usdcTreasury, alice))
                        .fee(ONE_HBAR)
                        .payingWith(BATCH_OPERATOR),
                cryptoTransfer(movingUnique(westWindArt, 1L).between(westWindTreasury, amelie))
                        .fee(ONE_HBAR)
                        .via(txnFromTreasury)
                        .payingWith(BATCH_OPERATOR),
                cryptoTransfer(
                                movingUnique(westWindArt, 1L).between(amelie, alice),
                                moving(200, usdc).between(alice, amelie),
                                movingHbar(10 * ONE_HUNDRED_HBARS).between(alice, amelie))
                        .signedBy(amelie, alice)
                        .payingWith(amelie)
                        .via(txnFromAmelie)
                        .fee(ONE_HBAR),
                getTxnRecord(txnFromAmelie));
    }

    @HapiTest
    final Stream<DynamicTest> nestedHtsCaseStudy() {
        final var debbie = DEBBIE;
        final var edgar = EDGAR;
        final var feeToken = "FeeToken";
        final var tokenWithHtsFee = "TokenWithHtsFee";
        final var tokenWithNestedFee = TOKEN_WITH_NESTED_FEE;
        final var treasuryForTopLevelCollection = TOKEN_TREASURY;
        final var treasuryForNestedCollection = NESTED_TOKEN_TREASURY;

        final var txnFromTreasury = TXN_FROM_TREASURY;
        final var txnFromDebbie = "txnFromDebbie";

        return hapiTest(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                cryptoCreate(debbie).payingWith(BATCH_OPERATOR),
                cryptoCreate(edgar).payingWith(BATCH_OPERATOR),
                cryptoCreate(treasuryForTopLevelCollection).payingWith(BATCH_OPERATOR),
                cryptoCreate(treasuryForNestedCollection).payingWith(BATCH_OPERATOR),
                tokenCreate(feeToken)
                        .treasury(DEFAULT_PAYER)
                        .initialSupply(Long.MAX_VALUE)
                        .payingWith(BATCH_OPERATOR),
                tokenAssociate(treasuryForNestedCollection, feeToken).payingWith(BATCH_OPERATOR),
                atomicBatch(tokenCreate(tokenWithHtsFee)
                                .initialSupply(Long.MAX_VALUE)
                                .treasury(treasuryForNestedCollection)
                                .withCustom(fixedHtsFee(1L, feeToken, treasuryForNestedCollection))
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                tokenAssociate(treasuryForTopLevelCollection, tokenWithHtsFee).payingWith(BATCH_OPERATOR),
                atomicBatch(tokenCreate(tokenWithNestedFee)
                                .initialSupply(Long.MAX_VALUE)
                                .treasury(treasuryForTopLevelCollection)
                                .withCustom(fixedHtsFee(1L, tokenWithHtsFee, treasuryForTopLevelCollection))
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                tokenAssociate(debbie, List.of(feeToken, tokenWithHtsFee, tokenWithNestedFee))
                        .payingWith(BATCH_OPERATOR),
                tokenAssociate(edgar, tokenWithNestedFee).payingWith(BATCH_OPERATOR),
                cryptoTransfer(
                                moving(1_000L, feeToken).between(DEFAULT_PAYER, debbie),
                                moving(1_000L, tokenWithHtsFee).between(treasuryForNestedCollection, debbie),
                                moving(1_000L, tokenWithNestedFee).between(treasuryForTopLevelCollection, debbie))
                        .payingWith(treasuryForNestedCollection)
                        .fee(ONE_HBAR)
                        .via(txnFromTreasury),
                getTxnRecord(txnFromTreasury).logged(),
                cryptoTransfer(moving(1L, tokenWithNestedFee).between(debbie, edgar))
                        .payingWith(debbie)
                        .fee(ONE_HBAR)
                        .via(txnFromDebbie),
                getTxnRecord(txnFromTreasury)
                        .hasTokenAmount(feeToken, debbie, 1_000L)
                        .hasTokenAmount(feeToken, DEFAULT_PAYER, -1_000L)
                        .hasTokenAmount(tokenWithHtsFee, debbie, 1_000L)
                        .hasTokenAmount(tokenWithHtsFee, treasuryForNestedCollection, -1_000L)
                        .hasTokenAmount(tokenWithNestedFee, debbie, 1_000L)
                        .hasTokenAmount(tokenWithNestedFee, treasuryForTopLevelCollection, -1_000L),
                getTxnRecord(txnFromDebbie)
                        .hasTokenAmount(tokenWithNestedFee, edgar, 1L)
                        .hasTokenAmount(tokenWithNestedFee, debbie, -1L)
                        .hasAssessedCustomFee(tokenWithHtsFee, treasuryForTopLevelCollection, 1L)
                        .hasTokenAmount(tokenWithHtsFee, treasuryForTopLevelCollection, 1L)
                        .hasTokenAmount(tokenWithHtsFee, debbie, -1L)
                        .hasAssessedCustomFee(feeToken, treasuryForNestedCollection, 1L)
                        .hasTokenAmount(feeToken, treasuryForNestedCollection, 1L)
                        .hasTokenAmount(feeToken, debbie, -1L),
                getAccountBalance(edgar).hasTokenBalance(tokenWithNestedFee, 1L),
                getAccountBalance(debbie)
                        .hasTokenBalance(feeToken, 1_000L - 1L)
                        .hasTokenBalance(tokenWithHtsFee, 1_000L - 1L)
                        .hasTokenBalance(tokenWithNestedFee, 1_000L - 1L),
                getAccountBalance(treasuryForTopLevelCollection)
                        .hasTokenBalance(tokenWithHtsFee, 1L)
                        .hasTokenBalance(tokenWithNestedFee, Long.MAX_VALUE - 1_000L),
                getAccountBalance(treasuryForNestedCollection)
                        .hasTokenBalance(feeToken, 1L)
                        .hasTokenBalance(tokenWithHtsFee, Long.MAX_VALUE - 1_000L),
                getAccountBalance(DEFAULT_PAYER).hasTokenBalance(feeToken, Long.MAX_VALUE - 1_000L));
    }

    @HapiTest
    final Stream<DynamicTest> canTransactInTokenWithSelfDenominatedFixedFee() {
        final var protocolToken = "protocolToken";
        final var gabriella = "gabriella";
        final var harry = "harry";
        final var nonExemptUnderfundedTxn = "nonExemptUnderfundedTxn";
        final var nonExemptFundedTxn = "nonExemptFundedTxn";

        return hapiTest(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                cryptoCreate(gabriella).payingWith(BATCH_OPERATOR),
                cryptoCreate(harry).payingWith(BATCH_OPERATOR),
                atomicBatch(tokenCreate(protocolToken)
                                .blankMemo()
                                .name("Self-absorption")
                                .symbol("SELF")
                                .initialSupply(1_234_567L)
                                .treasury(gabriella)
                                .withCustom(fixedHtsFee(1, SENTINEL_ACCOUNT, gabriella))
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                tokenAssociate(harry, protocolToken).payingWith(BATCH_OPERATOR),
                cryptoTransfer(moving(100, protocolToken).between(gabriella, harry))
                        .payingWith(BATCH_OPERATOR),
                cryptoTransfer(moving(100, protocolToken).between(harry, gabriella))
                        .via(nonExemptUnderfundedTxn)
                        .fee(ONE_HBAR)
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INSUFFICIENT_SENDER_ACCOUNT_BALANCE_FOR_CUSTOM_FEE),
                getTxnRecord(nonExemptUnderfundedTxn)
                        .hasPriority(recordWith().tokenTransfers(changingNoFungibleBalances())),
                cryptoTransfer(moving(99, protocolToken).between(harry, gabriella))
                        .fee(ONE_HBAR)
                        .via(nonExemptFundedTxn)
                        .payingWith(BATCH_OPERATOR),
                getTxnRecord(nonExemptFundedTxn)
                        .hasPriority(recordWith()
                                .tokenTransfers(changingFungibleBalances()
                                        .including(protocolToken, gabriella, +100L)
                                        .including(protocolToken, harry, -100L))));
    }

    /* ✅️ Should pass after fix for https://github.com/hashgraph/hedera-services/issues/1919
     *
     * SCENARIO:
     * ---------
     *   1. Create fungible "protocolToken" to use for a custom fee.
     *   2. Create non-fungible "artToken" with custom fee of 1 unit protocolToken.
     *   3. Use account "gabriella" as treasury for both tokens.
     *   4. Create account "harry" associated ONLY to artToken.
     *   5. Mint serial no 1 for art token, transfer to harry (no custom fee since gabriella is treasury and exempt).
     *   6. Transfer serial no 1 back to gabriella from harry.
     *   7. Transfer fails (correctly) with TOKEN_NOT_ASSOCIATED_TO_ACCOUNT, as harry isn't associated to protocolToken
     *   8. And following getTokenNftInfo query shows that harry is still the owner of serial no 1
     *   9. And following getAccountNftInfos query knows that harry still has serial no 1
     * */
    @HapiTest
    final Stream<DynamicTest> nftOwnersChangeAtomically() {
        final var artToken = "artToken";
        final var protocolToken = "protocolToken";
        final var gabriella = "gabriella";
        final var harry = "harry";
        final var uncompletableTxn = "uncompletableTxn";
        final var supplyKey = SUPPLY_KEY;
        final var serialNo1Meta = copyFromUtf8("PRICELESS");

        return hapiTest(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                newKeyNamed(supplyKey),
                cryptoCreate(gabriella).payingWith(BATCH_OPERATOR),
                cryptoCreate(harry).payingWith(BATCH_OPERATOR),
                tokenCreate(protocolToken)
                        .blankMemo()
                        .name("Self-absorption")
                        .symbol("SELF")
                        .initialSupply(1_234_567L)
                        .treasury(gabriella),
                atomicBatch(tokenCreate(artToken)
                                .supplyKey(supplyKey)
                                .blankMemo()
                                .name("Splash")
                                .symbol("SPLSH")
                                .tokenType(NON_FUNGIBLE_UNIQUE)
                                .initialSupply(0L)
                                .treasury(gabriella)
                                .withCustom(fixedHtsFee(1, protocolToken, gabriella))
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                mintToken(artToken, List.of(serialNo1Meta)).payingWith(BATCH_OPERATOR),
                tokenAssociate(harry, artToken).payingWith(BATCH_OPERATOR),
                cryptoTransfer(movingUnique(artToken, 1L).between(gabriella, harry))
                        .payingWith(BATCH_OPERATOR),
                cryptoTransfer(movingUnique(artToken, 1L).between(harry, gabriella))
                        .fee(ONE_HBAR)
                        .via(uncompletableTxn)
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(TOKEN_NOT_ASSOCIATED_TO_ACCOUNT),
                getTxnRecord(uncompletableTxn).hasPriority(recordWith().tokenTransfers(changingNoNftOwners())),
                getTokenNftInfo(artToken, 1L).hasAccountID(harry));
    }

    @HapiTest
    final Stream<DynamicTest> treasuriesAreExemptFromAllCustomFees() {
        final var edgar = EDGAR;
        final var feeToken = "FeeToken";
        final var topLevelToken = "TopLevelToken";
        final var treasuryForTopLevel = "TokenTreasury";
        final var collectorForTopLevel = "FeeCollector";
        final var nonTreasury = "nonTreasury";

        final var txnFromTreasury = TXN_FROM_TREASURY;
        final var txnFromNonTreasury = "txnFromNonTreasury";

        return hapiTest(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                cryptoCreate(edgar).payingWith(BATCH_OPERATOR),
                cryptoCreate(nonTreasury).payingWith(BATCH_OPERATOR),
                cryptoCreate(TOKEN_TREASURY).payingWith(BATCH_OPERATOR),
                cryptoCreate(treasuryForTopLevel).payingWith(BATCH_OPERATOR),
                cryptoCreate(collectorForTopLevel).balance(0L).payingWith(BATCH_OPERATOR),
                tokenCreate(feeToken)
                        .initialSupply(Long.MAX_VALUE)
                        .treasury(TOKEN_TREASURY)
                        .payingWith(BATCH_OPERATOR),
                tokenAssociate(collectorForTopLevel, feeToken).payingWith(BATCH_OPERATOR),
                tokenAssociate(treasuryForTopLevel, feeToken).payingWith(BATCH_OPERATOR),
                atomicBatch(tokenCreate(topLevelToken)
                                .initialSupply(Long.MAX_VALUE)
                                .treasury(treasuryForTopLevel)
                                .withCustom(fixedHbarFee(ONE_HBAR, collectorForTopLevel))
                                .withCustom(fixedHtsFee(50L, feeToken, collectorForTopLevel))
                                .withCustom(fractionalFee(1L, 10L, 5L, OptionalLong.of(50L), collectorForTopLevel))
                                .signedBy(DEFAULT_PAYER, treasuryForTopLevel, collectorForTopLevel)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                tokenAssociate(nonTreasury, List.of(topLevelToken, feeToken)).payingWith(BATCH_OPERATOR),
                tokenAssociate(edgar, topLevelToken).payingWith(BATCH_OPERATOR),
                cryptoTransfer(moving(2_000L, feeToken).distributing(TOKEN_TREASURY, treasuryForTopLevel, nonTreasury))
                        .payingWith(TOKEN_TREASURY)
                        .fee(ONE_HBAR)
                        .payingWith(BATCH_OPERATOR),
                cryptoTransfer(moving(1_000L, topLevelToken).between(treasuryForTopLevel, nonTreasury))
                        .payingWith(treasuryForTopLevel)
                        .fee(ONE_HBAR)
                        .via(txnFromTreasury)
                        .payingWith(BATCH_OPERATOR),
                getTxnRecord(txnFromTreasury)
                        .hasTokenAmount(topLevelToken, nonTreasury, 1_000L)
                        .hasTokenAmount(topLevelToken, treasuryForTopLevel, -1_000L)
                        .hasAssessedCustomFeesSize(0),
                getAccountBalance(collectorForTopLevel)
                        .hasTinyBars(0L)
                        .hasTokenBalance(feeToken, 0L)
                        .hasTokenBalance(topLevelToken, 0L),
                getAccountBalance(treasuryForTopLevel)
                        .hasTokenBalance(topLevelToken, Long.MAX_VALUE - 1_000L)
                        .hasTokenBalance(feeToken, 1_000L),
                getAccountBalance(nonTreasury)
                        .hasTokenBalance(topLevelToken, 1_000L)
                        .hasTokenBalance(feeToken, 1_000L),
                /* Now we perform the same transfer from a non-treasury and see all three fees charged */
                cryptoTransfer(moving(1_000L, topLevelToken).between(nonTreasury, edgar))
                        .payingWith(TOKEN_TREASURY)
                        .fee(ONE_HBAR)
                        .via(txnFromNonTreasury),
                getTxnRecord(txnFromNonTreasury)
                        .hasAssessedCustomFeesSize(3)
                        .hasTokenAmount(topLevelToken, edgar, 1_000L - 50L)
                        .hasTokenAmount(topLevelToken, nonTreasury, -1_000L)
                        .hasAssessedCustomFee(topLevelToken, collectorForTopLevel, 50L)
                        .hasTokenAmount(topLevelToken, collectorForTopLevel, 50L)
                        .hasAssessedCustomFee(HBAR_TOKEN_SENTINEL, collectorForTopLevel, ONE_HBAR)
                        .hasHbarAmount(collectorForTopLevel, ONE_HBAR)
                        .hasHbarAmount(nonTreasury, -ONE_HBAR)
                        .hasAssessedCustomFee(feeToken, collectorForTopLevel, 50L)
                        .hasTokenAmount(feeToken, collectorForTopLevel, 50L)
                        .hasTokenAmount(feeToken, nonTreasury, -50L),
                getAccountBalance(collectorForTopLevel)
                        .hasTinyBars(ONE_HBAR)
                        .hasTokenBalance(feeToken, 50L)
                        .hasTokenBalance(topLevelToken, 50L),
                getAccountBalance(edgar).hasTokenBalance(topLevelToken, 1_000L - 50L),
                getAccountBalance(nonTreasury)
                        .hasTokenBalance(topLevelToken, 0L)
                        .hasTokenBalance(feeToken, 1_000L - 50L));
    }

    @HapiTest
    final Stream<DynamicTest> collectorsAreExemptFromTheirOwnFeesButNotOthers() {
        final var edgar = EDGAR;
        final var topLevelToken = "TopLevelToken";
        final var treasuryForTopLevel = "TokenTreasury";
        final var firstCollectorForTopLevel = "AFeeCollector";
        final var secondCollectorForTopLevel = "BFeeCollector";

        final var txnFromCollector = "txnFromCollector";

        return hapiTest(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                cryptoCreate(edgar).payingWith(BATCH_OPERATOR),
                cryptoCreate(TOKEN_TREASURY).payingWith(BATCH_OPERATOR),
                cryptoCreate(treasuryForTopLevel).payingWith(BATCH_OPERATOR),
                cryptoCreate(firstCollectorForTopLevel).balance(10 * ONE_HBAR).payingWith(BATCH_OPERATOR),
                cryptoCreate(secondCollectorForTopLevel).balance(10 * ONE_HBAR).payingWith(BATCH_OPERATOR),
                atomicBatch(tokenCreate(topLevelToken)
                                .initialSupply(Long.MAX_VALUE)
                                .treasury(treasuryForTopLevel)
                                .withCustom(fixedHbarFee(ONE_HBAR, firstCollectorForTopLevel))
                                .withCustom(fixedHbarFee(2 * ONE_HBAR, secondCollectorForTopLevel))
                                .withCustom(fractionalFee(1L, 20L, 0L, OptionalLong.of(0L), firstCollectorForTopLevel))
                                .withCustom(fractionalFee(1L, 10L, 0L, OptionalLong.of(0L), secondCollectorForTopLevel))
                                .signedBy(
                                        DEFAULT_PAYER,
                                        treasuryForTopLevel,
                                        firstCollectorForTopLevel,
                                        secondCollectorForTopLevel)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                tokenAssociate(edgar, topLevelToken),
                cryptoTransfer(moving(2_000L, topLevelToken)
                        .distributing(treasuryForTopLevel, firstCollectorForTopLevel, secondCollectorForTopLevel)),
                getTokenInfo(topLevelToken).logged(),
                cryptoTransfer(moving(1_000L, topLevelToken).between(firstCollectorForTopLevel, edgar))
                        .payingWith(firstCollectorForTopLevel)
                        .fee(ONE_HBAR)
                        .via(txnFromCollector),
                getTxnRecord(txnFromCollector)
                        .hasAssessedCustomFeesSize(2)
                        .hasTokenAmount(topLevelToken, edgar, 1_000L - 100L)
                        .hasTokenAmount(topLevelToken, firstCollectorForTopLevel, -1_000L)
                        .hasAssessedCustomFee(topLevelToken, secondCollectorForTopLevel, 100L)
                        .hasTokenAmount(topLevelToken, secondCollectorForTopLevel, 100L)
                        .hasAssessedCustomFee(HBAR_TOKEN_SENTINEL, secondCollectorForTopLevel, 2 * ONE_HBAR)
                        .hasHbarAmount(secondCollectorForTopLevel, 2 * ONE_HBAR)
                        .logged(),
                getAccountBalance(firstCollectorForTopLevel).hasTokenBalance(topLevelToken, 0L),
                getAccountBalance(secondCollectorForTopLevel)
                        .hasTinyBars((10 + 2) * ONE_HBAR)
                        .hasTokenBalance(topLevelToken, 1_000L + 100L),
                getAccountBalance(edgar).hasTokenBalance(topLevelToken, 1_000L - 100L));
    }

    // HIP-573 tests below
    @HapiTest // HERE
    final Stream<DynamicTest> collectorIsChargedFixedFeeUnlessExempt() {
        return hapiTest(
                setupWellKnownTokenWithTwoFeesOnlyOneExemptingCollectors(NON_FUNGIBLE_UNIQUE, this::fixedFeeWith),
                getTokenInfo(TOKEN_WITH_PARALLEL_FEES).logged(),
                // This sender is only exempt from its own fee, but not from the other
                // fee; so a custom fee should be collected
                cryptoTransfer(movingUnique(TOKEN_WITH_PARALLEL_FEES, 1)
                                .between(COLLECTOR_OF_FEE_WITH_EXEMPTIONS, TOKEN_TREASURY))
                        .via(TXN_TRIGGERING_COLLECTOR_NON_EXEMPT_FEE),
                // This sender is already exempt from one fee, and the other
                // fee exempts all collectors; so no custom fees should be collected
                cryptoTransfer(movingUnique(TOKEN_WITH_PARALLEL_FEES, 2)
                                .between(COLLECTOR_OF_FEE_WITHOUT_EXEMPTIONS, TOKEN_TREASURY))
                        .via(TXN_TRIGGERING_COLLECTOR_EXEMPT_FEE),
                getTxnRecord(TXN_TRIGGERING_COLLECTOR_NON_EXEMPT_FEE)
                        .hasPriority(recordWith().assessedCustomFeeCount(1))
                        .logged(),
                getTxnRecord(TXN_TRIGGERING_COLLECTOR_EXEMPT_FEE)
                        .hasPriority(recordWith().assessedCustomFeeCount(0))
                        .logged());
    }

    @HapiTest // HERE
    final Stream<DynamicTest> collectorIsChargedFractionalFeeUnlessExempt() {
        return hapiTest(
                setupWellKnownTokenWithTwoFeesOnlyOneExemptingCollectors(FUNGIBLE_COMMON, this::fractionalFeeWith),
                cryptoCreate(CIVILIAN).maxAutomaticTokenAssociations(1),
                cryptoTransfer(moving(100_000, TOKEN_WITH_PARALLEL_FEES).between(TOKEN_TREASURY, CIVILIAN)),
                getTokenInfo(TOKEN_WITH_PARALLEL_FEES),
                // This receiver is only exempt from its own fee, so a custom
                // fee should be collected
                cryptoTransfer(moving(10_000, TOKEN_WITH_PARALLEL_FEES)
                                .between(CIVILIAN, COLLECTOR_OF_FEE_WITH_EXEMPTIONS))
                        .via(TXN_TRIGGERING_COLLECTOR_NON_EXEMPT_FEE),
                // This receiver is already exempt from its own fee, and the other
                // fee exempts all collectors; so no custom fees should be collected
                cryptoTransfer(moving(10_000, TOKEN_WITH_PARALLEL_FEES)
                                .between(CIVILIAN, COLLECTOR_OF_FEE_WITHOUT_EXEMPTIONS))
                        .via(TXN_TRIGGERING_COLLECTOR_EXEMPT_FEE),
                getTxnRecord(TXN_TRIGGERING_COLLECTOR_NON_EXEMPT_FEE)
                        .hasPriority(recordWith().assessedCustomFeeCount(1)),
                getTxnRecord(TXN_TRIGGERING_COLLECTOR_EXEMPT_FEE)
                        .hasPriority(recordWith().assessedCustomFeeCount(0)));
    }

    @HapiTest
    final Stream<DynamicTest> collectorIsChargedNetOfTransferFractionalFeeUnlessExempt() {
        return hapiTest(
                setupWellKnownTokenWithTwoFeesOnlyOneExemptingCollectors(
                        FUNGIBLE_COMMON, this::netOfTransferFractionalFeeWith),
                cryptoCreate(CIVILIAN).maxAutomaticTokenAssociations(1),
                getTokenInfo(TOKEN_WITH_PARALLEL_FEES),
                // This sender is only exempt from its own fee, so a custom
                // fee should be collected
                cryptoTransfer(moving(10_000, TOKEN_WITH_PARALLEL_FEES)
                                .between(COLLECTOR_OF_FEE_WITH_EXEMPTIONS, CIVILIAN))
                        .via(TXN_TRIGGERING_COLLECTOR_NON_EXEMPT_FEE),
                // This sender is already exempt from its own fee, and the other
                // fee exempts all collectors; so no custom fees should be collected
                cryptoTransfer(moving(10_000, TOKEN_WITH_PARALLEL_FEES)
                                .between(COLLECTOR_OF_FEE_WITHOUT_EXEMPTIONS, CIVILIAN))
                        .via(TXN_TRIGGERING_COLLECTOR_EXEMPT_FEE),
                getTxnRecord(TXN_TRIGGERING_COLLECTOR_NON_EXEMPT_FEE)
                        .hasPriority(recordWith().assessedCustomFeeCount(1)),
                getTxnRecord(TXN_TRIGGERING_COLLECTOR_EXEMPT_FEE)
                        .hasPriority(recordWith().assessedCustomFeeCount(0)));
    }

    @HapiTest // HERE custom fees fee collector ids in different order
    final Stream<DynamicTest> collectorIsChargedRoyaltyFeeUnlessExempt() {
        return hapiTest(
                setupWellKnownTokenWithTwoFeesOnlyOneExemptingCollectors(
                        NON_FUNGIBLE_UNIQUE, this::royaltyFeeNoFallbackWith),
                cryptoCreate(CIVILIAN).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(1),
                getTokenInfo(TOKEN_WITH_PARALLEL_FEES),
                // This sender is only exempt from its own fee, but not from the other
                // fee; so a custom fee should be collected
                cryptoTransfer(
                                movingUnique(TOKEN_WITH_PARALLEL_FEES, 1)
                                        .between(COLLECTOR_OF_FEE_WITH_EXEMPTIONS, CIVILIAN),
                                movingHbar(10 * ONE_HBAR).between(CIVILIAN, COLLECTOR_OF_FEE_WITH_EXEMPTIONS))
                        .via(TXN_TRIGGERING_COLLECTOR_NON_EXEMPT_FEE),
                // This sender is already exempt from one fee, and the other
                // fee exempts all collectors; so no custom fees should be collected
                cryptoTransfer(
                                movingUnique(TOKEN_WITH_PARALLEL_FEES, 2)
                                        .between(COLLECTOR_OF_FEE_WITHOUT_EXEMPTIONS, CIVILIAN),
                                movingHbar(10 * ONE_HBAR).between(CIVILIAN, COLLECTOR_OF_FEE_WITHOUT_EXEMPTIONS))
                        .via(TXN_TRIGGERING_COLLECTOR_EXEMPT_FEE),
                getTxnRecord(TXN_TRIGGERING_COLLECTOR_NON_EXEMPT_FEE)
                        .hasPriority(recordWith().assessedCustomFeeCount(1)),
                getTxnRecord(TXN_TRIGGERING_COLLECTOR_EXEMPT_FEE)
                        .hasPriority(recordWith().assessedCustomFeeCount(0)));
    }

    @HapiTest // HERE
    final Stream<DynamicTest> collectorIsChargedRoyaltyFallbackFeeUnlessExempt() {
        return hapiTest(
                setupWellKnownTokenWithTwoFeesOnlyOneExemptingCollectors(
                        NON_FUNGIBLE_UNIQUE, this::royaltyFeePlusFallbackWith),
                cryptoCreate(CIVILIAN).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(1),
                cryptoTransfer(movingUnique(TOKEN_WITH_PARALLEL_FEES, 3, 4).between(TOKEN_TREASURY, CIVILIAN)),
                getTokenInfo(TOKEN_WITH_PARALLEL_FEES),
                // This receiver is only exempt from its own fee, but not from the other
                // fee; so a custom fee should be collected
                cryptoTransfer(movingUnique(TOKEN_WITH_PARALLEL_FEES, 3)
                                .between(CIVILIAN, COLLECTOR_OF_FEE_WITH_EXEMPTIONS))
                        .via(TXN_TRIGGERING_COLLECTOR_NON_EXEMPT_FEE),
                // This sender is already exempt from one fee, and the other
                // fee exempts all collectors; so no custom fees should be collected
                cryptoTransfer(movingUnique(TOKEN_WITH_PARALLEL_FEES, 4)
                                .between(CIVILIAN, COLLECTOR_OF_FEE_WITHOUT_EXEMPTIONS))
                        .via(TXN_TRIGGERING_COLLECTOR_EXEMPT_FEE),
                getTxnRecord(TXN_TRIGGERING_COLLECTOR_NON_EXEMPT_FEE)
                        .hasPriority(recordWith().assessedCustomFeeCount(1))
                        .logged(),
                getTxnRecord(TXN_TRIGGERING_COLLECTOR_EXEMPT_FEE)
                        .hasPriority(recordWith().assessedCustomFeeCount(0))
                        .logged());
    }

    private static final String TXN_TRIGGERING_COLLECTOR_EXEMPT_FEE = "collectorExempt";
    private static final String TXN_TRIGGERING_COLLECTOR_NON_EXEMPT_FEE = "collectorNonExempt";

    private static final String TWO_FEE_SUPPLY_KEY = "multiKey";
    private static final String TOKEN_WITH_PARALLEL_FEES = "twoFeeToken";
    private static final String COLLECTOR_OF_FEE_WITH_EXEMPTIONS = "selflessCollector";
    private static final String COLLECTOR_OF_FEE_WITHOUT_EXEMPTIONS = "selfishCollector";

    private HapiSpecOperation setupWellKnownTokenWithTwoFeesOnlyOneExemptingCollectors(
            final TokenType tokenType, final Function<Boolean, Function<HapiSpec, CustomFee>> feeFactory) {
        final var creationOp = tokenCreate(TOKEN_WITH_PARALLEL_FEES)
                .treasury(TOKEN_TREASURY)
                .supplyKey(TWO_FEE_SUPPLY_KEY)
                .tokenType(tokenType)
                .withCustom(feeFactory.apply(Boolean.TRUE))
                .withCustom(feeFactory.apply(Boolean.FALSE));
        final HapiSpecOperation finisher;
        if (tokenType == NON_FUNGIBLE_UNIQUE) {
            creationOp.initialSupply(0L);
            finisher = blockingOrder(
                    mintToken(
                            TOKEN_WITH_PARALLEL_FEES,
                            List.of(
                                    ByteString.copyFromUtf8("FIRST"),
                                    ByteString.copyFromUtf8("SECOND"),
                                    ByteString.copyFromUtf8("THIRD"),
                                    ByteString.copyFromUtf8("FOURTH"))),
                    cryptoTransfer(
                            movingUnique(TOKEN_WITH_PARALLEL_FEES, 1L)
                                    .between(TOKEN_TREASURY, COLLECTOR_OF_FEE_WITH_EXEMPTIONS),
                            movingUnique(TOKEN_WITH_PARALLEL_FEES, 2L)
                                    .between(TOKEN_TREASURY, COLLECTOR_OF_FEE_WITHOUT_EXEMPTIONS)));
        } else {
            creationOp.initialSupply(1_000_000L);
            finisher = cryptoTransfer(
                    moving(100_000L, TOKEN_WITH_PARALLEL_FEES)
                            .between(TOKEN_TREASURY, COLLECTOR_OF_FEE_WITH_EXEMPTIONS),
                    moving(100_000L, TOKEN_WITH_PARALLEL_FEES)
                            .between(TOKEN_TREASURY, COLLECTOR_OF_FEE_WITHOUT_EXEMPTIONS));
        }
        return blockingOrder(
                newKeyNamed(TWO_FEE_SUPPLY_KEY),
                cryptoCreate(TOKEN_TREASURY),
                cryptoCreate(COLLECTOR_OF_FEE_WITH_EXEMPTIONS)
                        .maxAutomaticTokenAssociations(2)
                        .key(DEFAULT_PAYER),
                cryptoCreate(COLLECTOR_OF_FEE_WITHOUT_EXEMPTIONS)
                        .maxAutomaticTokenAssociations(2)
                        .key(DEFAULT_PAYER),
                cryptoCreate(BATCH_OPERATOR),
                atomicBatch(creationOp.batchKey(BATCH_OPERATOR)).payingWith(BATCH_OPERATOR),
                finisher);
    }

    private Function<HapiSpec, CustomFee> fixedFeeWith(final boolean allCollectorsExempt) {
        return fixedHbarFee(ONE_HBAR, nameForCollectorOfFeeWith(allCollectorsExempt), allCollectorsExempt);
    }

    private Function<HapiSpec, CustomFee> fractionalFeeWith(final boolean allCollectorsExempt) {
        return fractionalFee(
                1, 10, 0, OptionalLong.empty(), nameForCollectorOfFeeWith(allCollectorsExempt), allCollectorsExempt);
    }

    private Function<HapiSpec, CustomFee> netOfTransferFractionalFeeWith(final boolean allCollectorsExempt) {
        return fractionalFeeNetOfTransfers(
                1, 10, 0, OptionalLong.empty(), nameForCollectorOfFeeWith(allCollectorsExempt), allCollectorsExempt);
    }

    private Function<HapiSpec, CustomFee> royaltyFeeNoFallbackWith(final boolean allCollectorsExempt) {
        return royaltyFeeNoFallback(1, 10, nameForCollectorOfFeeWith(allCollectorsExempt), allCollectorsExempt);
    }

    private Function<HapiSpec, CustomFee> royaltyFeePlusFallbackWith(final boolean allCollectorsExempt) {
        return royaltyFeeWithFallback(
                1,
                10,
                fixedHbarFeeInheritingRoyaltyCollector(ONE_HBAR),
                nameForCollectorOfFeeWith(allCollectorsExempt),
                allCollectorsExempt);
    }

    private String nameForCollectorOfFeeWith(final boolean allCollectorsExempt) {
        return allCollectorsExempt ? COLLECTOR_OF_FEE_WITH_EXEMPTIONS : COLLECTOR_OF_FEE_WITHOUT_EXEMPTIONS;
    }
}
