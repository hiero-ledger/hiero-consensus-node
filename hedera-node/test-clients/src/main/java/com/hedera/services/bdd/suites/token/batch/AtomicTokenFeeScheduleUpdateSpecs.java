// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.token.batch;

import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.junit.TestTags.TOKEN;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenFeeScheduleUpdate;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedHbarFee;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedHbarFeeInheritingRoyaltyCollector;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedHtsFee;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fractionalFee;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.royaltyFeeNoFallback;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.royaltyFeeWithFallback;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.APP_PROPERTIES;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.flattened;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CUSTOM_FEE_MUST_BE_POSITIVE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CUSTOM_SCHEDULE_ALREADY_HAS_NO_FEES;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.FRACTION_DIVIDES_BY_ZERO;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INNER_TRANSACTION_FAILED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_CUSTOM_FEE_COLLECTOR;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOKEN_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOKEN_ID_IN_CUSTOM_FEES;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ROYALTY_FRACTION_CANNOT_EXCEED_ONE;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hederahashgraph.api.proto.java.TokenID;
import com.hederahashgraph.api.proto.java.TokenType;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Map;
import java.util.OptionalLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

// This test cases are direct copies of TokenFeeScheduleUpdateSpecs. The difference here is that
// we are wrapping the operations in an atomic batch to confirm that everything works as expected.
@HapiTestLifecycle
@Tag(TOKEN)
@Tag(MATS)
class AtomicTokenFeeScheduleUpdateSpecs {

    private static final String BATCH_OPERATOR = "batchOperator";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.doAdhoc(cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS));
    }

    @HapiTest
    final Stream<DynamicTest> failsUpdatingToEmptyFees() {
        return hapiTest(
                newKeyNamed("feeScheduleKey"),
                cryptoCreate("feeCollector"),
                tokenCreate("t").feeScheduleKey("feeScheduleKey"),
                tokenAssociate("feeCollector", "t"),
                atomicBatch(tokenFeeScheduleUpdate("t")
                                .hasKnownStatus(CUSTOM_SCHEDULE_ALREADY_HAS_NO_FEES)
                                .fee(ONE_HBAR)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatusFrom(INNER_TRANSACTION_FAILED));
    }

    private HapiSpecOperation[] validatesRoyaltyFeeBase() {
        return new HapiSpecOperation[] {
            newKeyNamed("feeScheduleKey"),
            newKeyNamed("supplyKey"),
            cryptoCreate("feeCollector"),
            tokenCreate("t")
                    .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                    .feeScheduleKey("feeScheduleKey")
                    .initialSupply(0)
                    .supplyKey("supplyKey"),
            tokenAssociate("feeCollector", "t")
        };
    }

    @HapiTest
    final Stream<DynamicTest> royaltyFractionDividesByZero() {
        return hapiTest(flattened(
                validatesRoyaltyFeeBase(),
                atomicBatch(tokenFeeScheduleUpdate("t")
                                .withCustom(royaltyFeeNoFallback(1, 0, "feeCollector"))
                                .hasKnownStatus(FRACTION_DIVIDES_BY_ZERO)
                                .fee(ONE_HBAR)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)));
    }

    @HapiTest
    final Stream<DynamicTest> royaltyFractionCannoExceedOne() {
        return hapiTest(flattened(
                validatesRoyaltyFeeBase(),
                atomicBatch(tokenFeeScheduleUpdate("t")
                                .withCustom(royaltyFeeNoFallback(2, 1, "feeCollector"))
                                .hasKnownStatus(ROYALTY_FRACTION_CANNOT_EXCEED_ONE)
                                .fee(ONE_HBAR)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)));
    }

    @HapiTest
    final Stream<DynamicTest> royaltyCustomFeeNumeratorMustBePositive() {
        return hapiTest(flattened(
                validatesRoyaltyFeeBase(),
                atomicBatch(tokenFeeScheduleUpdate("t")
                                .withCustom(royaltyFeeNoFallback(0, 1, "feeCollector"))
                                .hasKnownStatus(CUSTOM_FEE_MUST_BE_POSITIVE)
                                .fee(ONE_HBAR)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)));
    }

    @HapiTest
    final Stream<DynamicTest> royaltyCustomFeeZeroFallbackMustBePositive() {
        return hapiTest(flattened(
                validatesRoyaltyFeeBase(),
                atomicBatch(tokenFeeScheduleUpdate("t")
                                .withCustom(royaltyFeeWithFallback(
                                        1, 2, fixedHbarFeeInheritingRoyaltyCollector(0), "feeCollector"))
                                .hasKnownStatus(CUSTOM_FEE_MUST_BE_POSITIVE)
                                .fee(ONE_HBAR)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)));
    }

    @HapiTest
    final Stream<DynamicTest> royaltyCustomFeeNegativeFallbackMustBePositive() {
        return hapiTest(flattened(
                validatesRoyaltyFeeBase(),
                atomicBatch(tokenFeeScheduleUpdate("t")
                                .withCustom(royaltyFeeWithFallback(
                                        1, 2, fixedHbarFeeInheritingRoyaltyCollector(-1), "feeCollector"))
                                .hasKnownStatus(CUSTOM_FEE_MUST_BE_POSITIVE)
                                .fee(ONE_HBAR)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)));
    }

    private HapiSpecOperation[] customFeeValidationBase() {
        final var hbarAmount = 1_234L;
        final var htsAmount = 2_345L;
        final var numerator = 1;
        final var denominator = 10;
        final var minimumToCollect = 5;
        final var maximumToCollect = 50;

        final var token = "withCustomSchedules";
        final var immutableTokenWithFeeScheduleKey = "immutableToken";
        final var noFeeScheduleKeyToken = "tokenWithoutFeeScheduleKey";
        final var feeDenom = "denom";
        final var hbarCollector = "hbarFee";
        final var htsCollector = "denomFee";
        final var tokenCollector = "fractionalFee";

        final var adminKey = "admin";
        final var feeScheduleKey = "feeSchedule";

        final var newFeeDenom = "newDenom";
        final var newHbarCollector = "newHbarFee";
        final var newHtsCollector = "newDenomFee";
        final var newTokenCollector = "newFractionalFee";

        return new HapiSpecOperation[] {
            fileUpdate(APP_PROPERTIES).payingWith(GENESIS).overridingProps(Map.of("tokens.maxCustomFeesAllowed", "10")),
            newKeyNamed(adminKey),
            newKeyNamed(feeScheduleKey),
            cryptoCreate(htsCollector),
            cryptoCreate(newHtsCollector),
            cryptoCreate(hbarCollector),
            cryptoCreate(newHbarCollector),
            cryptoCreate(tokenCollector),
            cryptoCreate(newTokenCollector),
            tokenCreate(feeDenom).treasury(htsCollector),
            tokenCreate(newFeeDenom).treasury(newHtsCollector),
            tokenCreate(token)
                    .adminKey(adminKey)
                    .feeScheduleKey(feeScheduleKey)
                    .treasury(tokenCollector)
                    .withCustom(fixedHbarFee(hbarAmount, hbarCollector))
                    .withCustom(fixedHtsFee(htsAmount, feeDenom, htsCollector))
                    .withCustom(fractionalFee(
                            numerator,
                            denominator,
                            minimumToCollect,
                            OptionalLong.of(maximumToCollect),
                            tokenCollector)),
            tokenCreate(immutableTokenWithFeeScheduleKey)
                    .feeScheduleKey(feeScheduleKey)
                    .treasury(tokenCollector)
                    .withCustom(fixedHbarFee(hbarAmount, hbarCollector))
                    .withCustom(fixedHtsFee(htsAmount, feeDenom, htsCollector))
                    .withCustom(fractionalFee(
                            numerator,
                            denominator,
                            minimumToCollect,
                            OptionalLong.of(maximumToCollect),
                            tokenCollector)),
            tokenCreate(noFeeScheduleKeyToken)
                    .adminKey(adminKey)
                    .treasury(tokenCollector)
                    .withCustom(fixedHbarFee(hbarAmount, hbarCollector))
                    .withCustom(fixedHtsFee(htsAmount, feeDenom, htsCollector))
                    .withCustom(fractionalFee(
                            numerator,
                            denominator,
                            minimumToCollect,
                            OptionalLong.of(maximumToCollect),
                            tokenCollector)),
            fileUpdate(APP_PROPERTIES).payingWith(GENESIS).overridingProps(Map.of("tokens.maxCustomFeesAllowed", "1"))
        };
    }

    @HapiTest
    final Stream<DynamicTest> updatingInvalidTokenId() {
        return hapiTest(
                newKeyNamed("feeScheduleKey"),
                cryptoCreate("feeCollector"),
                tokenCreate("t").feeScheduleKey("feeScheduleKey"),
                tokenAssociate("feeCollector", "t"),

                // save invalid token id into spec registry
                withOpContext((spec, opLog) -> {
                    spec.registry()
                            .saveTokenId(
                                    "t", TokenID.newBuilder().setTokenNum(9999).build());
                }),
                // try to update with invalid token id
                atomicBatch(tokenFeeScheduleUpdate("t")
                                .withCustom(fixedHbarFee(1, "feeCollector"))
                                .hasKnownStatus(INVALID_TOKEN_ID)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    @HapiTest
    final Stream<DynamicTest> updatingWithDeletedCollector() {
        return hapiTest(
                newKeyNamed("feeScheduleKey"),
                newKeyNamed("supplyKey"),
                cryptoCreate("feeCollector"),
                tokenCreate("t").feeScheduleKey("feeScheduleKey"),
                tokenCreate("nft")
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .supplyKey("supplyKey")
                        .feeScheduleKey("feeScheduleKey")
                        .initialSupply(0),
                tokenAssociate("feeCollector", "t"),
                // delete the collector
                cryptoDelete("feeCollector"),

                // try to update with fixed fee
                atomicBatch(tokenFeeScheduleUpdate("t")
                                .withCustom(fixedHbarFee(1, "feeCollector"))
                                .hasKnownStatus(INVALID_CUSTOM_FEE_COLLECTOR)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),

                // try to update with fractional fee
                atomicBatch(tokenFeeScheduleUpdate("t")
                                .withCustom(fractionalFee(1, 10L, 1L, OptionalLong.empty(), "feeCollector"))
                                .hasKnownStatus(INVALID_CUSTOM_FEE_COLLECTOR)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),

                // try to update with royalty fee
                atomicBatch(tokenFeeScheduleUpdate("nft")
                                .withCustom(royaltyFeeNoFallback(1, 10, "feeCollector"))
                                .hasKnownStatus(INVALID_CUSTOM_FEE_COLLECTOR)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    @HapiTest
    final Stream<DynamicTest> updatingWithDeletedDenomToken() {
        return hapiTest(
                newKeyNamed("feeScheduleKey"),
                cryptoCreate("feeCollector"),
                tokenCreate("t").feeScheduleKey("feeScheduleKey"),
                tokenCreate("denom").adminKey("feeCollector"),
                tokenAssociate("feeCollector", "denom"),
                // delete the denominating token
                tokenDelete("denom").signedByPayerAnd("feeCollector"),

                // update fixed fee
                atomicBatch(tokenFeeScheduleUpdate("t")
                                .withCustom(fixedHtsFee(1, "denom", "feeCollector"))
                                .hasKnownStatus(INVALID_TOKEN_ID_IN_CUSTOM_FEES)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }
}
