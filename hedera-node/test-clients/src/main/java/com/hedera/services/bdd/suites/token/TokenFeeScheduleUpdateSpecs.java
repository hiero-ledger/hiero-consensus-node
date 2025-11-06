// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.token;

import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.junit.TestTags.TOKEN;
import static com.hedera.services.bdd.spec.HapiSpec.defaultHapiSpec;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTokenInfo;
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
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.incompleteCustomFee;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.royaltyFeeNoFallback;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.royaltyFeeWithFallback;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeTests.fixedHbarFeeInSchedule;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeTests.fixedHtsFeeInSchedule;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeTests.fractionalFeeInSchedule;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.submitModified;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.spec.utilops.mod.ModificationUtils.withSuccessivelyVariedBodyIds;
import static com.hedera.services.bdd.suites.HapiSuite.APP_PROPERTIES;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CUSTOM_FEES_LIST_TOO_LONG;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CUSTOM_FEE_MUST_BE_POSITIVE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CUSTOM_FEE_NOT_FULLY_SPECIFIED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CUSTOM_SCHEDULE_ALREADY_HAS_NO_FEES;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.FRACTION_DIVIDES_BY_ZERO;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_CUSTOM_FEE_COLLECTOR;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOKEN_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOKEN_ID_IN_CUSTOM_FEES;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ROYALTY_FRACTION_CANNOT_EXCEED_ONE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_HAS_NO_FEE_SCHEDULE_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_NOT_ASSOCIATED_TO_FEE_COLLECTOR;

import com.hedera.services.bdd.junit.HapiTest;
import com.hederahashgraph.api.proto.java.TokenID;
import com.hederahashgraph.api.proto.java.TokenType;
import java.util.Map;
import java.util.OptionalLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(TOKEN)
@Tag(MATS)
public class TokenFeeScheduleUpdateSpecs {

    @HapiTest
    final Stream<DynamicTest> idVariantsTreatedAsExpected() {
        return defaultHapiSpec("idVariantsTreatedAsExpected")
                .given(
                        newKeyNamed("feeScheduleKey"),
                        cryptoCreate("feeCollector"),
                        tokenCreate("t").feeScheduleKey("feeScheduleKey"),
                        tokenAssociate("feeCollector", "t"))
                .when()
                .then(submitModified(withSuccessivelyVariedBodyIds(), () -> tokenFeeScheduleUpdate("t")
                        .withCustom(fixedHbarFee(1, "feeCollector"))
                        .fee(ONE_HBAR)));
    }

    @HapiTest
    final Stream<DynamicTest> failsUpdatingToEmptyFees() {
        return defaultHapiSpec("idVariantsTreatedAsExpected")
                .given(
                        newKeyNamed("feeScheduleKey"),
                        cryptoCreate("feeCollector"),
                        tokenCreate("t").feeScheduleKey("feeScheduleKey"),
                        tokenAssociate("feeCollector", "t"))
                .when()
                .then(submitModified(withSuccessivelyVariedBodyIds(), () -> tokenFeeScheduleUpdate("t")
                        .hasKnownStatus(CUSTOM_SCHEDULE_ALREADY_HAS_NO_FEES)
                        .fee(ONE_HBAR)));
    }

    @HapiTest
    final Stream<DynamicTest> validatesRoyaltyFee() {
        return defaultHapiSpec("validatesRoyaltyFee")
                .given(
                        newKeyNamed("feeScheduleKey"),
                        newKeyNamed("supplyKey"),
                        cryptoCreate("feeCollector"),
                        tokenCreate("t")
                                .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                                .feeScheduleKey("feeScheduleKey")
                                .initialSupply(0)
                                .supplyKey("supplyKey"),
                        tokenAssociate("feeCollector", "t"))
                .when(
                        tokenFeeScheduleUpdate("t")
                                .withCustom(royaltyFeeNoFallback(1, 0, "feeCollector"))
                                .hasKnownStatus(FRACTION_DIVIDES_BY_ZERO)
                                .fee(ONE_HBAR),
                        tokenFeeScheduleUpdate("t")
                                .withCustom(royaltyFeeNoFallback(2, 1, "feeCollector"))
                                .hasKnownStatus(ROYALTY_FRACTION_CANNOT_EXCEED_ONE)
                                .fee(ONE_HBAR),
                        tokenFeeScheduleUpdate("t")
                                .withCustom(royaltyFeeNoFallback(0, 1, "feeCollector"))
                                .hasKnownStatus(CUSTOM_FEE_MUST_BE_POSITIVE)
                                .fee(ONE_HBAR))
                .then(
                        tokenFeeScheduleUpdate("t")
                                .withCustom(royaltyFeeWithFallback(
                                        1, 2, fixedHbarFeeInheritingRoyaltyCollector(0), "feeCollector"))
                                .hasKnownStatus(CUSTOM_FEE_MUST_BE_POSITIVE)
                                .fee(ONE_HBAR),
                        tokenFeeScheduleUpdate("t")
                                .withCustom(royaltyFeeWithFallback(
                                        1, 2, fixedHbarFeeInheritingRoyaltyCollector(-1), "feeCollector"))
                                .hasKnownStatus(CUSTOM_FEE_MUST_BE_POSITIVE)
                                .fee(ONE_HBAR));
    }

    @HapiTest
    final Stream<DynamicTest> onlyValidCustomFeeScheduleCanBeUpdated() {
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
        final var invalidEntityId = "1.2.786";

        final var adminKey = "admin";
        final var feeScheduleKey = "feeSchedule";

        final var newHbarAmount = 17_234L;
        final var newHtsAmount = 27_345L;
        final var newNumerator = 17;
        final var newDenominator = 107;
        final var newMinimumToCollect = 57;
        final var newMaximumToCollect = 507;

        final var newFeeDenom = "newDenom";
        final var newHbarCollector = "newHbarFee";
        final var newHtsCollector = "newDenomFee";
        final var newTokenCollector = "newFractionalFee";

        return defaultHapiSpec("OnlyValidCustomFeeScheduleCanBeUpdated")
                .given(
                        fileUpdate(APP_PROPERTIES)
                                .payingWith(GENESIS)
                                .overridingProps(Map.of("tokens.maxCustomFeesAllowed", "10")),
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
                        fileUpdate(APP_PROPERTIES)
                                .payingWith(GENESIS)
                                .overridingProps(Map.of("tokens.maxCustomFeesAllowed", "1")))
                .when(
                        tokenFeeScheduleUpdate(immutableTokenWithFeeScheduleKey)
                                .withCustom(fractionalFee(
                                        numerator,
                                        0,
                                        minimumToCollect,
                                        OptionalLong.of(maximumToCollect),
                                        tokenCollector))
                                .hasKnownStatus(FRACTION_DIVIDES_BY_ZERO),
                        tokenFeeScheduleUpdate(noFeeScheduleKeyToken)
                                .withCustom(fractionalFee(
                                        numerator,
                                        denominator,
                                        minimumToCollect,
                                        OptionalLong.of(maximumToCollect),
                                        tokenCollector))
                                .hasKnownStatus(TOKEN_HAS_NO_FEE_SCHEDULE_KEY),
                        tokenFeeScheduleUpdate(token)
                                .withCustom(fractionalFee(
                                        numerator,
                                        0,
                                        minimumToCollect,
                                        OptionalLong.of(maximumToCollect),
                                        tokenCollector))
                                .hasKnownStatus(FRACTION_DIVIDES_BY_ZERO),
                        tokenFeeScheduleUpdate(token)
                                .withCustom(fractionalFee(
                                        -numerator,
                                        denominator,
                                        minimumToCollect,
                                        OptionalLong.of(maximumToCollect),
                                        tokenCollector))
                                .hasKnownStatus(CUSTOM_FEE_MUST_BE_POSITIVE),
                        tokenFeeScheduleUpdate(token)
                                .withCustom(fractionalFee(
                                        numerator,
                                        denominator,
                                        -minimumToCollect,
                                        OptionalLong.of(maximumToCollect),
                                        tokenCollector))
                                .hasKnownStatus(CUSTOM_FEE_MUST_BE_POSITIVE),
                        tokenFeeScheduleUpdate(token)
                                .withCustom(fractionalFee(
                                        numerator,
                                        denominator,
                                        minimumToCollect,
                                        OptionalLong.of(-maximumToCollect),
                                        tokenCollector))
                                .hasKnownStatus(CUSTOM_FEE_MUST_BE_POSITIVE),
                        tokenFeeScheduleUpdate(token)
                                .withCustom(fixedHbarFee(hbarAmount, hbarCollector))
                                .withCustom(fixedHtsFee(htsAmount, feeDenom, htsCollector))
                                .hasKnownStatus(CUSTOM_FEES_LIST_TOO_LONG),
                        tokenFeeScheduleUpdate(token)
                                .withCustom(fixedHbarFee(hbarAmount, invalidEntityId))
                                .hasKnownStatus(INVALID_CUSTOM_FEE_COLLECTOR),
                        tokenFeeScheduleUpdate(token)
                                .withCustom(fixedHtsFee(htsAmount, invalidEntityId, htsCollector))
                                .hasKnownStatus(INVALID_TOKEN_ID_IN_CUSTOM_FEES),
                        tokenFeeScheduleUpdate(token)
                                .withCustom(fixedHtsFee(htsAmount, feeDenom, hbarCollector))
                                .hasKnownStatus(TOKEN_NOT_ASSOCIATED_TO_FEE_COLLECTOR),
                        tokenFeeScheduleUpdate(token)
                                .withCustom(fixedHtsFee(-htsAmount, feeDenom, htsCollector))
                                .hasKnownStatus(CUSTOM_FEE_MUST_BE_POSITIVE),
                        tokenFeeScheduleUpdate(token)
                                .withCustom(incompleteCustomFee(hbarCollector))
                                .hasKnownStatus(CUSTOM_FEE_NOT_FULLY_SPECIFIED),
                        fileUpdate(APP_PROPERTIES)
                                .payingWith(GENESIS)
                                .overridingProps(Map.of("tokens.maxCustomFeesAllowed", "10")),
                        tokenAssociate(newTokenCollector, token),
                        tokenFeeScheduleUpdate(token)
                                .withCustom(fixedHbarFee(newHbarAmount, newHbarCollector))
                                .withCustom(fixedHtsFee(newHtsAmount, newFeeDenom, newHtsCollector))
                                .withCustom(fractionalFee(
                                        newNumerator,
                                        newDenominator,
                                        newMinimumToCollect,
                                        OptionalLong.of(newMaximumToCollect),
                                        newTokenCollector)))
                .then(getTokenInfo(token)
                        .hasCustom(fixedHbarFeeInSchedule(newHbarAmount, newHbarCollector))
                        .hasCustom(fixedHtsFeeInSchedule(newHtsAmount, newFeeDenom, newHtsCollector))
                        .hasCustom(fractionalFeeInSchedule(
                                newNumerator,
                                newDenominator,
                                newMinimumToCollect,
                                OptionalLong.of(newMaximumToCollect),
                                false,
                                newTokenCollector)));
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
                tokenFeeScheduleUpdate("t")
                        .withCustom(fixedHbarFee(1, "feeCollector"))
                        .hasKnownStatus(INVALID_TOKEN_ID));
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
                tokenFeeScheduleUpdate("t")
                        .withCustom(fixedHbarFee(1, "feeCollector"))
                        .hasKnownStatus(INVALID_CUSTOM_FEE_COLLECTOR),

                // try to update with fractional fee
                tokenFeeScheduleUpdate("t")
                        .withCustom(fractionalFee(1, 10L, 1L, OptionalLong.empty(), "feeCollector"))
                        .hasKnownStatus(INVALID_CUSTOM_FEE_COLLECTOR),

                // try to update with royalty fee
                tokenFeeScheduleUpdate("nft")
                        .withCustom(royaltyFeeNoFallback(1, 10, "feeCollector"))
                        .hasKnownStatus(INVALID_CUSTOM_FEE_COLLECTOR));
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
                tokenFeeScheduleUpdate("t")
                        .withCustom(fixedHtsFee(1, "denom", "feeCollector"))
                        .hasKnownStatus(INVALID_TOKEN_ID_IN_CUSTOM_FEES));
    }

    @HapiTest
    final Stream<DynamicTest> updatingToEmptyListRequiresSignature() {
        return hapiTest(
                newKeyNamed("feeScheduleKey"),
                newKeyNamed("supplyKey"),
                cryptoCreate("feeCollector"),
                // create a new NFT with the right keys
                tokenCreate("tok")
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0)
                        .supplyKey("supplyKey")
                        .feeScheduleKey("feeScheduleKey"),
                // update the schedule to a valid royalty
                tokenFeeScheduleUpdate("tok")
                        .withCustom(royaltyFeeNoFallback(1, 10, "feeCollector"))
                        .payingWith(GENESIS)
                        .signedBy(GENESIS, "feeScheduleKey", "supplyKey")
                        .hasKnownStatus(SUCCESS),
                // update to an empty fee schedule without the fee schedule key
                tokenFeeScheduleUpdate("tok").signedBy(GENESIS).hasKnownStatus(INVALID_SIGNATURE),
                // update to an empty fee schedule *with* the fee schedule key
                tokenFeeScheduleUpdate("tok")
                        .signedBy(GENESIS, "feeScheduleKey")
                        .hasKnownStatus(SUCCESS));
    }

    @HapiTest
    final Stream<DynamicTest> validateCollectorReceiverSigRequired() {
        return hapiTest(
                newKeyNamed("feeScheduleKey"),
                newKeyNamed("supplyKey"),
                tokenCreate("tok")
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0)
                        .supplyKey("supplyKey")
                        .feeScheduleKey("feeScheduleKey"),
                // collector does not require a sig
                cryptoCreate("feeCollector1").receiverSigRequired(false),
                // collector does require a sign
                cryptoCreate("feeCollector2").receiverSigRequired(true),
                // update schedule with first collector not signing. should succeed
                tokenFeeScheduleUpdate("tok")
                        .withCustom(royaltyFeeNoFallback(1, 10, "feeCollector1"))
                        .signedBy(GENESIS, "feeScheduleKey")
                        .hasKnownStatus(SUCCESS),
                // update schedule second collector not signing, should fail because second requires sig
                tokenFeeScheduleUpdate("tok")
                        .withCustom(royaltyFeeNoFallback(1, 10, "feeCollector2"))
                        .signedBy(GENESIS, "feeScheduleKey")
                        .hasKnownStatus(INVALID_SIGNATURE),
                // try again *with* the second collector signing, should pass now
                tokenFeeScheduleUpdate("tok")
                        .withCustom(royaltyFeeNoFallback(1, 10, "feeCollector2"))
                        .signedBy(GENESIS, "feeScheduleKey", "feeCollector2")
                        .hasKnownStatus(SUCCESS));
    }
}
