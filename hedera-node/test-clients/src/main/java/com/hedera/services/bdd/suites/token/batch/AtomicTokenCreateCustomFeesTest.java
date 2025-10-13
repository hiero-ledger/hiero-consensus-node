// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.token.batch;

import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.accountWith;
import static com.hedera.services.bdd.spec.assertions.AutoAssocAsserts.accountTokenPairs;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTokenInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.queries.crypto.ExpectedTokenRel.relationshipWith;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedHbarFee;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedHbarFeeInheritingRoyaltyCollector;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedHtsFee;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedHtsFeeInheritingRoyaltyCollector;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fractionalFee;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.incompleteCustomFee;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.royaltyFeeNoFallback;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.royaltyFeeWithFallback;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeTests.fixedHbarFeeInSchedule;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeTests.fixedHtsFeeInSchedule;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeTests.fractionalFeeInSchedule;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.flattened;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CUSTOM_FEE_MUST_BE_POSITIVE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CUSTOM_FEE_NOT_FULLY_SPECIFIED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CUSTOM_ROYALTY_FEE_ONLY_ALLOWED_FOR_NON_FUNGIBLE_UNIQUE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.FRACTIONAL_FEE_MAX_AMOUNT_LESS_THAN_MIN_AMOUNT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.FRACTION_DIVIDES_BY_ZERO;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INNER_TRANSACTION_FAILED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_CUSTOM_FEE_COLLECTOR;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOKEN_ID_IN_CUSTOM_FEES;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TREASURY_ACCOUNT_FOR_TOKEN;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ROYALTY_FRACTION_CANNOT_EXCEED_ONE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_NOT_ASSOCIATED_TO_FEE_COLLECTOR;
import static com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.TestTags;
import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hederahashgraph.api.proto.java.TokenType;
import java.util.List;
import java.util.OptionalLong;
import java.util.stream.Stream;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

// This test cases are direct copies of TokenCreateSpecs. The difference here is that
// we are wrapping the operations in an atomic batch to confirm that everything works as expected.
@Tag(TestTags.TOKEN)
class AtomicTokenCreateCustomFeesTest {

    private static final String TOKEN_TREASURY = "treasury";
    private static final String A_TOKEN = "TokenA";
    private static final String SENTINEL_VALUE = "0.0.0";
    private static final long HBAR_AMOUT = 1_234L;
    private static final long HTS_AMOUNT = 2_345L;
    private static final long NUMERATOR = 1;
    private static final long DENOMINATOR = 10;
    private static final long MINIMUM_TO_COLLECT = 5;
    private static final long MAXIMUM_TO_COLLECT = 50;
    private static final String TOKEN = "withCustomSchedules";
    private static final String FEE_DENOM = "denom";
    private static final String HBAR_COLLECTOR = "hbarFee";
    private static final String HTS_COLLECTOR = "denomFee";
    private static final String TOKEN_COLLECTOR = "fractionalFee";
    private static final String CUSTOM_FEES_KEY = "antique";

    private static final String BATCH_OPERATOR = "batchOperator";
    private static final String ATOMIC_BATCH = "atomicBatch";

    @HapiTest
    final Stream<DynamicTest> idVariantsTreatedAsExpected() {
        return hapiTest(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                newKeyNamed("supplyKey"),
                cryptoCreate(TOKEN_TREASURY).payingWith(BATCH_OPERATOR),
                cryptoCreate("autoRenewAccount").payingWith(BATCH_OPERATOR),
                cryptoCreate("feeCollector").payingWith(BATCH_OPERATOR),
                tokenCreate("feeToken").payingWith(BATCH_OPERATOR),
                tokenAssociate("feeCollector", "feeToken").payingWith(BATCH_OPERATOR),
                atomicBatch(
                                tokenCreate("fungibleToken")
                                        .treasury(TOKEN_TREASURY)
                                        .autoRenewAccount("autoRenewAccount")
                                        .withCustom(fixedHbarFee(1L, "feeCollector"))
                                        .withCustom(fixedHtsFee(1L, "feeToken", "feeCollector"))
                                        .withCustom(fractionalFee(1L, 100L, 1L, OptionalLong.of(5L), "feeCollector"))
                                        .signedBy(DEFAULT_PAYER, TOKEN_TREASURY, "feeCollector", "autoRenewAccount")
                                        .batchKey(BATCH_OPERATOR),
                                tokenCreate("nonFungibleToken")
                                        .treasury(TOKEN_TREASURY)
                                        .tokenType(NON_FUNGIBLE_UNIQUE)
                                        .initialSupply(0L)
                                        .supplyKey("supplyKey")
                                        .autoRenewAccount("autoRenewAccount")
                                        .withCustom(royaltyFeeWithFallback(
                                                1L, 10L, fixedHbarFeeInheritingRoyaltyCollector(123L), "feeCollector"))
                                        .signedBy(DEFAULT_PAYER, TOKEN_TREASURY, "autoRenewAccount")
                                        .batchKey(BATCH_OPERATOR))
                        .via(ATOMIC_BATCH)
                        .payingWith(BATCH_OPERATOR));
    }

    /**
     * Validates that a {@code TokenCreate} auto-associates the following types of
     * accounts:
     * <ul>
     *     <li>Its treasury.</li>
     *     <li>Any fractional fee collector.</li>
     *     <li>Any self-denominated fixed fee collector.</li>
     * </ul>
     * It also verifies that these auto-associations don't "count" against the max
     * automatic associations limit defined by https://hips.hedera.com/hip/hip-23.
     */
    @HapiTest
    final Stream<DynamicTest> validateNewTokenAssociations() {
        final String notToBeToken = "notToBeToken";
        final String hbarCollector = "hbarCollector";
        final String fractionalCollector = "fractionalCollector";
        final String selfDenominatedFixedCollector = "selfDenominatedFixedCollector";
        final String otherSelfDenominatedFixedCollector = "otherSelfDenominatedFixedCollector";
        final String treasury = "treasury";
        final String tbd = "toBeDeletd";
        final String creationTxn = "creationTxn";
        final String failedCreationTxn = "failedCreationTxn";

        return hapiTest(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                cryptoCreate(tbd),
                cryptoDelete(tbd),
                cryptoCreate(hbarCollector),
                cryptoCreate(fractionalCollector),
                cryptoCreate(selfDenominatedFixedCollector),
                cryptoCreate(otherSelfDenominatedFixedCollector),
                cryptoCreate(treasury).maxAutomaticTokenAssociations(10).balance(ONE_HUNDRED_HBARS),
                getAccountInfo(treasury).savingSnapshot(treasury),
                getAccountInfo(hbarCollector).savingSnapshot(hbarCollector),
                getAccountInfo(fractionalCollector).savingSnapshot(fractionalCollector),
                getAccountInfo(selfDenominatedFixedCollector).savingSnapshot(selfDenominatedFixedCollector),
                getAccountInfo(otherSelfDenominatedFixedCollector).savingSnapshot(otherSelfDenominatedFixedCollector),
                atomicBatch(tokenCreate(A_TOKEN)
                                .tokenType(TokenType.FUNGIBLE_COMMON)
                                .initialSupply(Long.MAX_VALUE)
                                .treasury(treasury)
                                .withCustom(fixedHbarFee(20L, hbarCollector))
                                .withCustom(fractionalFee(1L, 100L, 1L, OptionalLong.of(5L), fractionalCollector))
                                .withCustom(fixedHtsFee(2L, SENTINEL_VALUE, selfDenominatedFixedCollector))
                                .withCustom(fixedHtsFee(3L, SENTINEL_VALUE, otherSelfDenominatedFixedCollector))
                                .signedBy(
                                        DEFAULT_PAYER,
                                        treasury,
                                        fractionalCollector,
                                        selfDenominatedFixedCollector,
                                        otherSelfDenominatedFixedCollector)
                                .via(creationTxn)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                atomicBatch(tokenCreate(notToBeToken)
                                .treasury(tbd)
                                .hasKnownStatus(INVALID_TREASURY_ACCOUNT_FOR_TOKEN)
                                .via(failedCreationTxn)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                /* Validate records */
                getTxnRecord(creationTxn)
                        .logged()
                        .hasPriority(recordWith()
                                .autoAssociated(accountTokenPairs(List.of(
                                        Pair.of(fractionalCollector, A_TOKEN),
                                        Pair.of(selfDenominatedFixedCollector, A_TOKEN),
                                        Pair.of(otherSelfDenominatedFixedCollector, A_TOKEN),
                                        Pair.of(treasury, A_TOKEN))))),
                getTxnRecord(failedCreationTxn).hasPriority(recordWith().autoAssociated(accountTokenPairs(List.of()))),
                /* Validate state */
                getAccountInfo(hbarCollector).has(accountWith().noChangesFromSnapshot(hbarCollector)),
                getAccountInfo(treasury)
                        .hasMaxAutomaticAssociations(10)
                        /* TokenCreate auto-associations aren't part of the HIP-23 paradigm */
                        .hasAlreadyUsedAutomaticAssociations(0)
                        .has(accountWith().newAssociationsFromSnapshot(treasury, List.of(relationshipWith(A_TOKEN)))),
                getAccountInfo(fractionalCollector)
                        .has(accountWith()
                                .newAssociationsFromSnapshot(fractionalCollector, List.of(relationshipWith(A_TOKEN)))),
                getAccountInfo(selfDenominatedFixedCollector)
                        .has(accountWith()
                                .newAssociationsFromSnapshot(
                                        selfDenominatedFixedCollector, List.of(relationshipWith(A_TOKEN)))),
                getAccountInfo(otherSelfDenominatedFixedCollector)
                        .has(accountWith()
                                .newAssociationsFromSnapshot(
                                        otherSelfDenominatedFixedCollector, List.of(relationshipWith(A_TOKEN)))));
    }

    private HapiSpecOperation[] customFeeSetup() {
        return new HapiSpecOperation[] {
            cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
            newKeyNamed(CUSTOM_FEES_KEY),
            cryptoCreate(HTS_COLLECTOR).payingWith(BATCH_OPERATOR),
            cryptoCreate(HBAR_COLLECTOR).payingWith(BATCH_OPERATOR),
            cryptoCreate(TOKEN_COLLECTOR).payingWith(BATCH_OPERATOR),
            tokenCreate(FEE_DENOM).treasury(HTS_COLLECTOR).payingWith(BATCH_OPERATOR)
        };
    }

    @HapiTest
    final Stream<DynamicTest> tokenCustomFeeDividesByZero() {
        return hapiTest(flattened(
                customFeeSetup(),
                atomicBatch(tokenCreate(TOKEN)
                                .treasury(TOKEN_COLLECTOR)
                                .withCustom(fractionalFee(
                                        NUMERATOR,
                                        0,
                                        MINIMUM_TO_COLLECT,
                                        OptionalLong.of(MAXIMUM_TO_COLLECT),
                                        TOKEN_COLLECTOR))
                                .hasKnownStatus(FRACTION_DIVIDES_BY_ZERO)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)));
    }

    @HapiTest
    final Stream<DynamicTest> tokenCustomFeeInvalidCustomFee() {
        final String invalidEntityId = "1.2.786";
        return hapiTest(flattened(
                customFeeSetup(),
                atomicBatch(tokenCreate(TOKEN)
                                .treasury(TOKEN_COLLECTOR)
                                .withCustom(fixedHbarFee(HBAR_AMOUT, invalidEntityId))
                                .hasKnownStatus(INVALID_CUSTOM_FEE_COLLECTOR)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)));
    }

    @HapiTest
    final Stream<DynamicTest> tokenCustomFeeInvalidTokenId() {
        final String invalidEntityId = "1.2.786";
        return hapiTest(flattened(
                customFeeSetup(),
                atomicBatch(tokenCreate(TOKEN)
                                .treasury(TOKEN_COLLECTOR)
                                .withCustom(fixedHtsFee(HTS_AMOUNT, invalidEntityId, HTS_COLLECTOR))
                                .hasKnownStatus(INVALID_TOKEN_ID_IN_CUSTOM_FEES)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)));
    }

    @HapiTest
    final Stream<DynamicTest> tokenCustomFeeTokenNotAssociatedToFeeCollector() {
        return hapiTest(flattened(
                customFeeSetup(),
                atomicBatch(tokenCreate(TOKEN)
                                .treasury(TOKEN_COLLECTOR)
                                .withCustom(fixedHtsFee(HTS_AMOUNT, FEE_DENOM, HBAR_COLLECTOR))
                                .hasKnownStatus(TOKEN_NOT_ASSOCIATED_TO_FEE_COLLECTOR)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)));
    }

    @HapiTest
    final Stream<DynamicTest> tokenCustomFeeCustomFeeNotFullySpecified() {
        return hapiTest(flattened(
                customFeeSetup(),
                atomicBatch(tokenCreate(TOKEN)
                                .treasury(TOKEN_COLLECTOR)
                                .withCustom(incompleteCustomFee(HBAR_COLLECTOR))
                                .signedBy(DEFAULT_PAYER, TOKEN_COLLECTOR, HBAR_COLLECTOR)
                                .hasKnownStatus(CUSTOM_FEE_NOT_FULLY_SPECIFIED)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)));
    }

    @HapiTest
    final Stream<DynamicTest> tokenCustomFeeHTSMustBePositive() {
        final long negativeHtsFee = -100L;
        return hapiTest(flattened(
                customFeeSetup(),
                atomicBatch(tokenCreate(TOKEN)
                                .treasury(TOKEN_COLLECTOR)
                                .withCustom(fixedHtsFee(negativeHtsFee, FEE_DENOM, HBAR_COLLECTOR))
                                .hasKnownStatus(CUSTOM_FEE_MUST_BE_POSITIVE)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)));
    }

    @HapiTest
    final Stream<DynamicTest> tokenCustomFeeFractionalNegativeDenominator() {
        return hapiTest(flattened(
                customFeeSetup(),
                atomicBatch(tokenCreate(TOKEN)
                                .treasury(TOKEN_COLLECTOR)
                                .withCustom(fractionalFee(
                                        NUMERATOR,
                                        -DENOMINATOR,
                                        MINIMUM_TO_COLLECT,
                                        OptionalLong.of(MAXIMUM_TO_COLLECT),
                                        TOKEN_COLLECTOR))
                                .hasKnownStatus(CUSTOM_FEE_MUST_BE_POSITIVE)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)));
    }

    @HapiTest
    final Stream<DynamicTest> tokenCustomFeeNegativeMinimumToCollect() {
        return hapiTest(flattened(
                customFeeSetup(),
                atomicBatch(tokenCreate(TOKEN)
                                .treasury(TOKEN_COLLECTOR)
                                .withCustom(fractionalFee(
                                        NUMERATOR,
                                        DENOMINATOR,
                                        -MINIMUM_TO_COLLECT,
                                        OptionalLong.of(MAXIMUM_TO_COLLECT),
                                        TOKEN_COLLECTOR))
                                .hasKnownStatus(CUSTOM_FEE_MUST_BE_POSITIVE)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)));
    }

    @HapiTest
    final Stream<DynamicTest> tokenCustomFeeNegativeMaxToCollect() {
        return hapiTest(flattened(
                customFeeSetup(),
                atomicBatch(tokenCreate(TOKEN)
                                .treasury(TOKEN_COLLECTOR)
                                .withCustom(fractionalFee(
                                        NUMERATOR,
                                        DENOMINATOR,
                                        MINIMUM_TO_COLLECT,
                                        OptionalLong.of(-MAXIMUM_TO_COLLECT),
                                        TOKEN_COLLECTOR))
                                .hasKnownStatus(CUSTOM_FEE_MUST_BE_POSITIVE)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)));
    }

    @HapiTest
    final Stream<DynamicTest> tokenCustomFeeNegativeNumerator() {
        return hapiTest(flattened(
                customFeeSetup(),
                atomicBatch(tokenCreate(TOKEN)
                                .treasury(TOKEN_COLLECTOR)
                                .withCustom(fractionalFee(
                                        -NUMERATOR,
                                        -DENOMINATOR,
                                        MINIMUM_TO_COLLECT,
                                        OptionalLong.of(MAXIMUM_TO_COLLECT),
                                        TOKEN_COLLECTOR))
                                .hasKnownStatus(CUSTOM_FEE_MUST_BE_POSITIVE)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)));
    }

    @HapiTest
    final Stream<DynamicTest> tokenCustomFeeFractionalFeeMaxAmountLessThanMin() {
        return hapiTest(flattened(
                customFeeSetup(),
                atomicBatch(tokenCreate(TOKEN)
                                .treasury(TOKEN_COLLECTOR)
                                .withCustom(fractionalFee(
                                        NUMERATOR,
                                        DENOMINATOR,
                                        MINIMUM_TO_COLLECT,
                                        OptionalLong.of(MINIMUM_TO_COLLECT - 1),
                                        TOKEN_COLLECTOR))
                                .hasKnownStatus(FRACTIONAL_FEE_MAX_AMOUNT_LESS_THAN_MIN_AMOUNT)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)));
    }

    @HapiTest
    final Stream<DynamicTest> tokenCustomFeeCustomRoyaltyFeeOnlyAllowedForNonFungibleUnique() {
        return hapiTest(flattened(
                customFeeSetup(),
                atomicBatch(tokenCreate(TOKEN)
                                .treasury(TOKEN_COLLECTOR)
                                .withCustom(royaltyFeeNoFallback(1, 2, TOKEN_COLLECTOR))
                                .hasKnownStatus(CUSTOM_ROYALTY_FEE_ONLY_ALLOWED_FOR_NON_FUNGIBLE_UNIQUE)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)));
    }

    @HapiTest
    final Stream<DynamicTest> tokenCustomFeeNegativeRoyaltyNumerator() {
        return hapiTest(flattened(
                customFeeSetup(),
                atomicBatch(tokenCreate(TOKEN)
                                .tokenType(NON_FUNGIBLE_UNIQUE)
                                .supplyKey(GENESIS)
                                .initialSupply(0L)
                                .treasury(TOKEN_COLLECTOR)
                                .withCustom(royaltyFeeNoFallback(-1, 2, TOKEN_COLLECTOR))
                                .hasKnownStatus(CUSTOM_FEE_MUST_BE_POSITIVE)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)));
    }

    @HapiTest
    final Stream<DynamicTest> tokenCustomFeeNegativeRoyaltyDenominator() {
        return hapiTest(flattened(
                customFeeSetup(),
                atomicBatch(tokenCreate(TOKEN)
                                .tokenType(NON_FUNGIBLE_UNIQUE)
                                .supplyKey(GENESIS)
                                .initialSupply(0L)
                                .treasury(TOKEN_COLLECTOR)
                                .withCustom(royaltyFeeNoFallback(1, -2, TOKEN_COLLECTOR))
                                .hasKnownStatus(CUSTOM_FEE_MUST_BE_POSITIVE)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)));
    }

    @HapiTest
    final Stream<DynamicTest> tokenCustomFeeFractionDividesByZero() {
        return hapiTest(flattened(
                customFeeSetup(),
                atomicBatch(tokenCreate(TOKEN)
                                .tokenType(NON_FUNGIBLE_UNIQUE)
                                .supplyKey(GENESIS)
                                .initialSupply(0L)
                                .treasury(TOKEN_COLLECTOR)
                                .withCustom(royaltyFeeNoFallback(1, 0, TOKEN_COLLECTOR))
                                .hasKnownStatus(FRACTION_DIVIDES_BY_ZERO)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)));
    }

    @HapiTest
    final Stream<DynamicTest> tokenCustomFeeRoyaltyFractionCannotExceedOne() {
        return hapiTest(flattened(
                customFeeSetup(),
                atomicBatch(tokenCreate(TOKEN)
                                .tokenType(NON_FUNGIBLE_UNIQUE)
                                .supplyKey(GENESIS)
                                .initialSupply(0L)
                                .treasury(TOKEN_COLLECTOR)
                                .withCustom(royaltyFeeNoFallback(2, 1, TOKEN_COLLECTOR))
                                .hasKnownStatus(ROYALTY_FRACTION_CANNOT_EXCEED_ONE)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)));
    }

    @HapiTest
    final Stream<DynamicTest> tokenCustomFeeRoyaltyNegativeFallback() {
        return hapiTest(flattened(
                customFeeSetup(),
                atomicBatch(tokenCreate(TOKEN)
                                .tokenType(NON_FUNGIBLE_UNIQUE)
                                .supplyKey(GENESIS)
                                .initialSupply(0L)
                                .treasury(TOKEN_COLLECTOR)
                                .withCustom(royaltyFeeWithFallback(
                                        1, 2, fixedHbarFeeInheritingRoyaltyCollector(-100), TOKEN_COLLECTOR))
                                .hasKnownStatus(CUSTOM_FEE_MUST_BE_POSITIVE)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)));
    }

    @HapiTest
    final Stream<DynamicTest> tokenCustomFeeInvalidTokenIdInCustomFee() {
        return hapiTest(flattened(
                customFeeSetup(),
                atomicBatch(tokenCreate(TOKEN)
                                .tokenType(NON_FUNGIBLE_UNIQUE)
                                .supplyKey(GENESIS)
                                .initialSupply(0L)
                                .treasury(TOKEN_COLLECTOR)
                                .withCustom(royaltyFeeWithFallback(
                                        1, 2, fixedHtsFeeInheritingRoyaltyCollector(100, "1.2.3"), TOKEN_COLLECTOR))
                                .hasKnownStatus(INVALID_TOKEN_ID_IN_CUSTOM_FEES)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)));
    }

    @HapiTest
    final Stream<DynamicTest> tokenCreateWithAllTypesOfCustomFees() {
        return hapiTest(flattened(
                customFeeSetup(),
                atomicBatch(tokenCreate(TOKEN)
                                .treasury(TOKEN_COLLECTOR)
                                .withCustom(fixedHbarFee(HBAR_AMOUT, HBAR_COLLECTOR))
                                .withCustom(fixedHtsFee(HTS_AMOUNT, FEE_DENOM, HTS_COLLECTOR))
                                .withCustom(fixedHtsFee(HTS_AMOUNT, SENTINEL_VALUE, HTS_COLLECTOR))
                                .withCustom(fractionalFee(
                                        NUMERATOR,
                                        DENOMINATOR,
                                        MINIMUM_TO_COLLECT,
                                        OptionalLong.of(MAXIMUM_TO_COLLECT),
                                        TOKEN_COLLECTOR))
                                .signedBy(DEFAULT_PAYER, TOKEN_COLLECTOR, HTS_COLLECTOR)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                getTokenInfo(TOKEN)
                        .hasCustom(fixedHbarFeeInSchedule(HBAR_AMOUT, HBAR_COLLECTOR))
                        .hasCustom(fixedHtsFeeInSchedule(HTS_AMOUNT, FEE_DENOM, HTS_COLLECTOR))
                        .hasCustom(fixedHtsFeeInSchedule(HTS_AMOUNT, TOKEN, HTS_COLLECTOR))
                        .hasCustom(fractionalFeeInSchedule(
                                NUMERATOR,
                                DENOMINATOR,
                                MINIMUM_TO_COLLECT,
                                OptionalLong.of(MAXIMUM_TO_COLLECT),
                                false,
                                TOKEN_COLLECTOR))));
    }

    private HapiSpecOperation[] feeCollectorSigningSetup() {
        return new HapiSpecOperation[] {
            cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
            newKeyNamed(CUSTOM_FEES_KEY),
            cryptoCreate(HTS_COLLECTOR).receiverSigRequired(true),
            cryptoCreate(HBAR_COLLECTOR),
            cryptoCreate(TOKEN_COLLECTOR),
            cryptoCreate(TOKEN_TREASURY),
            tokenCreate(FEE_DENOM).treasury(HTS_COLLECTOR)
        };
    }

    @HapiTest
    final Stream<DynamicTest> tokenWith0DenominatorCustomFee() {
        return hapiTest(flattened(
                feeCollectorSigningSetup(),
                atomicBatch(tokenCreate(TOKEN)
                                .treasury(TOKEN_TREASURY)
                                .withCustom(fractionalFee(
                                        NUMERATOR,
                                        0,
                                        MINIMUM_TO_COLLECT,
                                        OptionalLong.of(MAXIMUM_TO_COLLECT),
                                        TOKEN_COLLECTOR))
                                .batchKey(BATCH_OPERATOR)
                                .hasKnownStatus(INVALID_SIGNATURE))
                        .via(ATOMIC_BATCH)
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)));
    }

    @HapiTest
    final Stream<DynamicTest> tokenWithInvalidCustomFee() {
        return hapiTest(flattened(
                feeCollectorSigningSetup(),
                atomicBatch(tokenCreate(TOKEN)
                                .treasury(TOKEN_TREASURY)
                                .withCustom(fixedHtsFee(HTS_AMOUNT, FEE_DENOM, HTS_COLLECTOR))
                                .batchKey(BATCH_OPERATOR)
                                .hasKnownStatus(INVALID_SIGNATURE))
                        .via(ATOMIC_BATCH)
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)));
    }

    @HapiTest
    final Stream<DynamicTest> tokenWithNegativeDenominatorCustomFee() {
        return hapiTest(flattened(
                feeCollectorSigningSetup(),
                atomicBatch(tokenCreate(TOKEN)
                                .treasury(TOKEN_TREASURY)
                                .withCustom(fractionalFee(
                                        NUMERATOR,
                                        -DENOMINATOR,
                                        MINIMUM_TO_COLLECT,
                                        OptionalLong.of(MAXIMUM_TO_COLLECT),
                                        TOKEN_COLLECTOR))
                                .batchKey(BATCH_OPERATOR)
                                .hasKnownStatus(INVALID_SIGNATURE))
                        .via(ATOMIC_BATCH)
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)));
    }

    @HapiTest
    final Stream<DynamicTest> tokenWithCustomFee() {
        return hapiTest(flattened(
                feeCollectorSigningSetup(),
                atomicBatch(tokenCreate(TOKEN)
                                .treasury(TOKEN_TREASURY)
                                .withCustom(fixedHbarFee(HBAR_AMOUT, HBAR_COLLECTOR))
                                .withCustom(fixedHtsFee(HTS_AMOUNT, FEE_DENOM, HTS_COLLECTOR))
                                .withCustom(fractionalFee(
                                        NUMERATOR,
                                        DENOMINATOR,
                                        MINIMUM_TO_COLLECT,
                                        OptionalLong.of(MAXIMUM_TO_COLLECT),
                                        TOKEN_COLLECTOR))
                                .signedBy(DEFAULT_PAYER, TOKEN_TREASURY, HTS_COLLECTOR, TOKEN_COLLECTOR)
                                .batchKey(BATCH_OPERATOR))
                        .via(ATOMIC_BATCH)
                        .payingWith(BATCH_OPERATOR)));
    }

    @HapiTest
    final Stream<DynamicTest> deletedAccountCannotBeFeeCollector() {
        final var account = "account";
        return hapiTest(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                cryptoCreate(account).payingWith(BATCH_OPERATOR),
                cryptoDelete(account).payingWith(BATCH_OPERATOR),
                atomicBatch(tokenCreate("anyToken")
                                .treasury(DEFAULT_PAYER)
                                .tokenType(FUNGIBLE_COMMON)
                                .initialSupply(1000L)
                                .withCustom(fixedHbarFee(1, account))
                                .batchKey(BATCH_OPERATOR)
                                .hasKnownStatus(INVALID_CUSTOM_FEE_COLLECTOR))
                        .via(ATOMIC_BATCH)
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    @HapiTest
    final Stream<DynamicTest> withLongMinNumeratorRoyaltyFeeWithFallback() {
        return hapiTest(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                newKeyNamed("supplyKey"),
                cryptoCreate(TOKEN_TREASURY).payingWith(BATCH_OPERATOR),
                cryptoCreate("autoRenewAccount").payingWith(BATCH_OPERATOR),
                cryptoCreate("feeCollector").payingWith(BATCH_OPERATOR),
                tokenCreate("feeToken").payingWith(BATCH_OPERATOR),
                tokenAssociate("feeCollector", "feeToken").payingWith(BATCH_OPERATOR),
                atomicBatch(tokenCreate("nonFungibleToken")
                                .treasury(TOKEN_TREASURY)
                                .tokenType(NON_FUNGIBLE_UNIQUE)
                                .initialSupply(0L)
                                .supplyKey("supplyKey")
                                .autoRenewAccount("autoRenewAccount")
                                .withCustom(royaltyFeeWithFallback(
                                        Long.MIN_VALUE,
                                        10L,
                                        fixedHbarFeeInheritingRoyaltyCollector(123L),
                                        "feeCollector"))
                                .batchKey(BATCH_OPERATOR)
                                .hasKnownStatus(CUSTOM_FEE_MUST_BE_POSITIVE))
                        .via(ATOMIC_BATCH)
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    @HapiTest
    final Stream<DynamicTest> withLongMinDenominatorRoyaltyFeeWithFallback() {
        return hapiTest(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                newKeyNamed("supplyKey"),
                cryptoCreate(TOKEN_TREASURY).payingWith(BATCH_OPERATOR),
                cryptoCreate("autoRenewAccount").payingWith(BATCH_OPERATOR),
                cryptoCreate("feeCollector").payingWith(BATCH_OPERATOR),
                tokenCreate("feeToken").payingWith(BATCH_OPERATOR),
                tokenAssociate("feeCollector", "feeToken").payingWith(BATCH_OPERATOR),
                atomicBatch(tokenCreate("nonFungibleToken")
                                .treasury(TOKEN_TREASURY)
                                .tokenType(NON_FUNGIBLE_UNIQUE)
                                .initialSupply(0L)
                                .supplyKey("supplyKey")
                                .autoRenewAccount("autoRenewAccount")
                                .withCustom(royaltyFeeWithFallback(
                                        1,
                                        Long.MIN_VALUE,
                                        fixedHbarFeeInheritingRoyaltyCollector(123L),
                                        "feeCollector"))
                                .batchKey(BATCH_OPERATOR)
                                .hasKnownStatus(CUSTOM_FEE_MUST_BE_POSITIVE))
                        .via(ATOMIC_BATCH)
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    @HapiTest
    final Stream<DynamicTest> withLongMinNumeratorRoyaltyFeeNoFallback() {
        return hapiTest(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                cryptoCreate(TOKEN_TREASURY).payingWith(BATCH_OPERATOR),
                cryptoCreate("feeCollector").payingWith(BATCH_OPERATOR),
                atomicBatch(tokenCreate(TOKEN)
                                .tokenType(NON_FUNGIBLE_UNIQUE)
                                .supplyKey(GENESIS)
                                .initialSupply(0L)
                                .treasury(TOKEN_TREASURY)
                                .withCustom(royaltyFeeNoFallback(Long.MIN_VALUE, 2, "feeCollector"))
                                .batchKey(BATCH_OPERATOR)
                                .hasKnownStatus(CUSTOM_FEE_MUST_BE_POSITIVE))
                        .via(ATOMIC_BATCH)
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    @HapiTest
    final Stream<DynamicTest> withLongMinDenominatorRoyaltyFeeNoFallback() {
        return hapiTest(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                cryptoCreate(TOKEN_TREASURY).payingWith(BATCH_OPERATOR),
                cryptoCreate("feeCollector").payingWith(BATCH_OPERATOR),
                atomicBatch(tokenCreate(TOKEN)
                                .tokenType(NON_FUNGIBLE_UNIQUE)
                                .supplyKey(GENESIS)
                                .initialSupply(0L)
                                .treasury(TOKEN_TREASURY)
                                .withCustom(royaltyFeeNoFallback(1, Long.MIN_VALUE, "feeCollector"))
                                .batchKey(BATCH_OPERATOR)
                                .hasKnownStatus(CUSTOM_FEE_MUST_BE_POSITIVE))
                        .via(ATOMIC_BATCH)
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }
}
