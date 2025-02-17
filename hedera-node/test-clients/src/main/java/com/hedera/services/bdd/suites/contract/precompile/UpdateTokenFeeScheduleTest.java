/*
 * Copyright (C) 2024-2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hedera.services.bdd.suites.contract.precompile;

import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.asEvmAddress;
import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.dsl.entities.SpecTokenKey.ADMIN_KEY;
import static com.hedera.services.bdd.spec.dsl.entities.SpecTokenKey.FEE_SCHEDULE_KEY;
import static com.hedera.services.bdd.spec.dsl.entities.SpecTokenKey.SUPPLY_KEY;
import static com.hedera.services.bdd.spec.transactions.contract.HapiParserUtil.asHeadlongAddress;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeTests.fixedHbarFeeInSchedule;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeTests.fixedHtsFeeInSchedule;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeTests.fractionalFeeInSchedule;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeTests.royaltyFeeWithFallbackInHbarsInSchedule;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeTests.royaltyFeeWithFallbackInTokenInSchedule;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeTests.royaltyFeeWithoutFallbackInSchedule;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CUSTOM_FEES_LIST_TOO_LONG;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CUSTOM_FEE_DENOMINATION_MUST_BE_FUNGIBLE_COMMON;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CUSTOM_FEE_MUST_BE_POSITIVE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CUSTOM_SCHEDULE_ALREADY_HAS_NO_FEES;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.FRACTION_DIVIDES_BY_ZERO;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_CUSTOM_FEE_COLLECTOR;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOKEN_ID_IN_CUSTOM_FEES;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_HAS_NO_FEE_SCHEDULE_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_NOT_ASSOCIATED_TO_FEE_COLLECTOR;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.OrderedInIsolation;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.dsl.annotations.Account;
import com.hedera.services.bdd.spec.dsl.annotations.Contract;
import com.hedera.services.bdd.spec.dsl.annotations.FungibleToken;
import com.hedera.services.bdd.spec.dsl.annotations.NonFungibleToken;
import com.hedera.services.bdd.spec.dsl.entities.SpecAccount;
import com.hedera.services.bdd.spec.dsl.entities.SpecContract;
import com.hedera.services.bdd.spec.dsl.entities.SpecFungibleToken;
import com.hedera.services.bdd.spec.dsl.entities.SpecNonFungibleToken;
import com.hedera.services.bdd.spec.queries.token.HapiGetTokenInfo;
import com.hedera.services.bdd.suites.utils.contracts.precompile.TokenKeyType;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.OptionalLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;

@Tag(SMART_CONTRACT)
@DisplayName("updateTokenFeeSchedule")
@HapiTestLifecycle
// To avoid race conditions; it is very fast regardless
@OrderedInIsolation
public class UpdateTokenFeeScheduleTest {

    @Contract(contract = "UpdateTokenFeeSchedules", creationGas = 4_000_000L)
    static SpecContract updateTokenFeeSchedules;

    @FungibleToken(
            name = "fungibleToken",
            keys = {ADMIN_KEY, FEE_SCHEDULE_KEY})
    static SpecFungibleToken fungibleToken;

    @FungibleToken(
            name = "feeToken",
            keys = {ADMIN_KEY, FEE_SCHEDULE_KEY})
    static SpecFungibleToken feeToken;

    @NonFungibleToken(
            name = "nonFungibleToken",
            keys = {ADMIN_KEY, FEE_SCHEDULE_KEY, SUPPLY_KEY})
    static SpecNonFungibleToken nonFungibleToken;

    @Account(name = "feeCollector", tinybarBalance = ONE_HUNDRED_HBARS)
    static SpecAccount feeCollector;

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.doAdhoc(
                feeToken.authorizeContracts(updateTokenFeeSchedules).alsoAuthorizing(TokenKeyType.FEE_SCHEDULE_KEY),
                fungibleToken
                        .authorizeContracts(updateTokenFeeSchedules)
                        .alsoAuthorizing(TokenKeyType.FEE_SCHEDULE_KEY),
                nonFungibleToken
                        .authorizeContracts(updateTokenFeeSchedules)
                        .alsoAuthorizing(TokenKeyType.FEE_SCHEDULE_KEY),
                feeCollector.associateTokens(feeToken, fungibleToken));
    }

    @Order(0)
    @HapiTest
    @DisplayName("fungible token with fixed ℏ fee")
    public Stream<DynamicTest> updateFungibleTokenWithHbarFixedFee() {
        return hapiTest(
                updateTokenFeeSchedules.call("updateFungibleFixedHbarFee", fungibleToken, 10L, feeCollector),
                fungibleToken
                        .getInfo()
                        .andAssert(info -> info.hasCustom(fixedHbarFeeInSchedule(10L, feeCollector.name()))));
    }

    @Order(1)
    @HapiTest
    @DisplayName("non fungible token with fixed ℏ fee")
    public Stream<DynamicTest> updateNonFungibleTokenWithHbarFixedFee() {
        return hapiTest(
                updateTokenFeeSchedules.call("updateNonFungibleFixedHbarFee", nonFungibleToken, 10L, feeCollector),
                nonFungibleToken
                        .getInfo()
                        .andAssert(info -> info.hasCustom(fixedHbarFeeInSchedule(10L, feeCollector.name()))));
    }

    @Order(2)
    @HapiTest
    @DisplayName("fungible token with token fixed fee")
    public Stream<DynamicTest> updateFungibleTokenWithTokenFixedFee() {
        return hapiTest(
                updateTokenFeeSchedules.call("updateFungibleFixedHtsFee", fungibleToken, feeToken, 1L, feeCollector),
                fungibleToken
                        .getInfo()
                        .andAssert(info ->
                                info.hasCustom(fixedHtsFeeInSchedule(1L, feeToken.name(), feeCollector.name()))));
    }

    @Order(3)
    @HapiTest
    @DisplayName("non fungible token with token fixed fee")
    public Stream<DynamicTest> updateNonFungibleTokenWithTokenFixedFee() {
        return hapiTest(
                updateTokenFeeSchedules.call(
                        "updateNonFungibleFixedHtsFee", nonFungibleToken, feeToken, 1L, feeCollector),
                nonFungibleToken
                        .getInfo()
                        .andAssert(info ->
                                info.hasCustom(fixedHtsFeeInSchedule(1L, feeToken.name(), feeCollector.name()))));
    }

    @Order(4)
    @HapiTest
    @DisplayName("fungible token with current token fixed fee")
    public Stream<DynamicTest> updateFungibleTokenWithCurrentTokenFixedFee() {
        return hapiTest(
                updateTokenFeeSchedules.call("updateFungibleFixedTokenFee", feeToken, 1L, feeCollector),
                feeToken.getInfo()
                        .andAssert(info ->
                                info.hasCustom(fixedHtsFeeInSchedule(1L, feeToken.name(), feeCollector.name()))));
    }

    @Order(5)
    @HapiTest
    @DisplayName("fungible token with fractional fee")
    public Stream<DynamicTest> updateFungibleTokenWithFractionalFee() {
        return hapiTest(
                updateTokenFeeSchedules.call("updateFungibleFractionalFee", feeToken, 1L, 10L, false, feeCollector),
                feeToken.getInfo()
                        .andAssert(info -> info.hasCustom(
                                fractionalFeeInSchedule(1L, 10L, 0L, OptionalLong.of(0), false, feeCollector.name()))),
                updateTokenFeeSchedules.call("updateFungibleFractionalFee", feeToken, 1L, 10L, true, feeCollector),
                feeToken.getInfo()
                        .andAssert(info -> info.hasCustom(
                                fractionalFeeInSchedule(1L, 10L, 0L, OptionalLong.of(0), true, feeCollector.name()))));
    }

    @Order(6)
    @HapiTest
    @DisplayName("fungible token with fractional fee with min and max")
    public Stream<DynamicTest> updateFungibleTokenWithFractionalFeeWithMinAndMax() {
        return hapiTest(
                updateTokenFeeSchedules.call(
                        "updateFungibleFractionalFeeMinAndMax", feeToken, 1L, 10L, 10L, 20L, false, feeCollector),
                feeToken.getInfo()
                        .andAssert(info -> info.hasCustom(fractionalFeeInSchedule(
                                1L, 10L, 10L, OptionalLong.of(20), false, feeCollector.name()))));
    }

    @Order(7)
    @HapiTest
    @DisplayName("non fungible token with royalty fee")
    public Stream<DynamicTest> updateNonFungibleTokenWithRoyaltyFee() {
        return hapiTest(
                updateTokenFeeSchedules.call("updateNonFungibleRoyaltyFee", nonFungibleToken, 1L, 10L, feeCollector),
                nonFungibleToken
                        .getInfo()
                        .andAssert(info ->
                                info.hasCustom(royaltyFeeWithoutFallbackInSchedule(1L, 10L, feeCollector.name()))));
    }

    @Order(8)
    @HapiTest
    @DisplayName("non fungible token with royalty fee ℏ fallback")
    public Stream<DynamicTest> updateNonFungibleTokenWithRoyaltyFeeHbarFallback() {
        return hapiTest(
                updateTokenFeeSchedules.call(
                        "updateNonFungibleRoyaltyFeeHbarFallback", nonFungibleToken, 1L, 10L, 20L, feeCollector),
                nonFungibleToken
                        .getInfo()
                        .andAssert(info -> info.hasCustom(
                                royaltyFeeWithFallbackInHbarsInSchedule(1L, 10L, 20L, feeCollector.name()))));
    }

    @Order(9)
    @HapiTest
    @DisplayName("non fungible token with royalty fee token fallback")
    public Stream<DynamicTest> updateNonFungibleTokenWithRoyaltyFeeTokenFallback() {
        return hapiTest(
                updateTokenFeeSchedules.call(
                        "updateNonFungibleRoyaltyFeeHtsFallback",
                        nonFungibleToken,
                        feeToken,
                        1L,
                        10L,
                        20L,
                        feeCollector),
                nonFungibleToken
                        .getInfo()
                        .andAssert(info -> info.hasCustom(royaltyFeeWithFallbackInTokenInSchedule(
                                1L, 10L, 20L, feeToken.name(), feeCollector.name()))));
    }

    @Order(10)
    @HapiTest
    @DisplayName("fungible token with n fixed ℏ fee")
    public Stream<DynamicTest> updateFungibleTokenWithNHbarFixedFee() {
        return hapiTest(
                updateTokenFeeSchedules.call("updateFungibleFixedHbarFees", fungibleToken, 3, 10L, feeCollector),
                fungibleToken.getInfo().andAssert(info -> info.hasCustom(
                                fixedHbarFeeInSchedule(10L, feeCollector.name()))
                        .hasCustom(fixedHbarFeeInSchedule(10L, feeCollector.name()))
                        .hasCustom(fixedHbarFeeInSchedule(10L, feeCollector.name()))));
    }

    @Order(11)
    @HapiTest
    @DisplayName("fungible token with n fractional fee")
    public Stream<DynamicTest> updateFungibleTokenWithNFractionalFee() {
        return hapiTest(
                updateTokenFeeSchedules.call("updateFungibleFractionalFees", feeToken, 3, 1L, 10L, false, feeCollector),
                feeToken.getInfo().andAssert(info -> info.hasCustom(
                                fractionalFeeInSchedule(1L, 10L, 0L, OptionalLong.of(0), false, feeCollector.name()))
                        .hasCustom(fractionalFeeInSchedule(1L, 10L, 0L, OptionalLong.of(0), false, feeCollector.name()))
                        .hasCustom(
                                fractionalFeeInSchedule(1L, 10L, 0L, OptionalLong.of(0), false, feeCollector.name()))));
    }

    @Order(12)
    @HapiTest
    @DisplayName("non fungible token with n royalty fee")
    public Stream<DynamicTest> updateNonFungibleTokenWithNRoyaltyFee() {
        return hapiTest(
                updateTokenFeeSchedules.call(
                        "updateNonFungibleRoyaltyFees", nonFungibleToken, 3, 1L, 10L, feeCollector),
                nonFungibleToken.getInfo().andAssert(info -> info.hasCustom(
                                royaltyFeeWithoutFallbackInSchedule(1L, 10L, feeCollector.name()))
                        .hasCustom(royaltyFeeWithoutFallbackInSchedule(1L, 10L, feeCollector.name()))
                        .hasCustom(royaltyFeeWithoutFallbackInSchedule(1L, 10L, feeCollector.name()))));
    }

    @Order(13)
    @HapiTest
    @DisplayName("fungible token multiple fees")
    public Stream<DynamicTest> updateFungibleTokenFees() {
        return hapiTest(
                updateTokenFeeSchedules.call(
                        "updateFungibleFees", fungibleToken, 3L, feeToken, 1L, 10L, false, feeCollector),
                fungibleToken.getInfo().andAssert(info -> info.hasCustom(
                                fixedHtsFeeInSchedule(3L, feeToken.name(), feeCollector.name()))
                        .hasCustom(fixedHbarFeeInSchedule(6L, feeCollector.name()))
                        .hasCustom(fixedHtsFeeInSchedule(12L, fungibleToken.name(), feeCollector.name()))
                        .hasCustom(
                                fractionalFeeInSchedule(1L, 10L, 0L, OptionalLong.of(0), false, feeCollector.name()))));
    }

    @Order(14)
    @HapiTest
    @DisplayName("non fungible token multiple fees")
    public Stream<DynamicTest> updateNonFungibleTokenFees() {
        return hapiTest(
                updateTokenFeeSchedules.call(
                        "updateNonFungibleFees", nonFungibleToken, feeToken, 1L, 1L, 10L, feeCollector),
                nonFungibleToken.getInfo().andAssert(info -> info.hasCustom(
                                fixedHtsFeeInSchedule(1L, feeToken.name(), feeCollector.name()))
                        .hasCustom(royaltyFeeWithoutFallbackInSchedule(1L, 10L, feeCollector.name()))
                        .hasCustom(royaltyFeeWithFallbackInHbarsInSchedule(1L, 10L, 1L, feeCollector.name()))
                        .hasCustom(royaltyFeeWithFallbackInTokenInSchedule(
                                1L, 10L, 1L, feeToken.name(), feeCollector.name()))));
    }

    @Order(15)
    @HapiTest
    @DisplayName("fungible token custom fees reset")
    public Stream<DynamicTest> resetFungibleTokenCustomFees() {
        return hapiTest(
                updateTokenFeeSchedules.call("resetFungibleTokenFees", fungibleToken),
                fungibleToken.getInfo().andAssert(HapiGetTokenInfo::hasEmptyCustom),
                updateTokenFeeSchedules
                        .call("resetFungibleTokenFees", fungibleToken)
                        .andAssert(result -> result.hasKnownStatuses(
                                CONTRACT_REVERT_EXECUTED, CUSTOM_SCHEDULE_ALREADY_HAS_NO_FEES)));
    }

    @Order(16)
    @HapiTest
    @DisplayName("nft token custom fees reset")
    public Stream<DynamicTest> resetNonFungibleTokenCustomFees() {
        return hapiTest(
                updateTokenFeeSchedules.call("resetNonFungibleTokenFees", nonFungibleToken),
                nonFungibleToken.getInfo().andAssert(HapiGetTokenInfo::hasEmptyCustom),
                updateTokenFeeSchedules
                        .call("resetNonFungibleTokenFees", nonFungibleToken)
                        .andAssert(result -> result.hasKnownStatuses(
                                CONTRACT_REVERT_EXECUTED, CUSTOM_SCHEDULE_ALREADY_HAS_NO_FEES)));
    }

    @Order(17)
    @HapiTest
    @DisplayName("update tokens without fee schedule key")
    public Stream<DynamicTest> updateTokensWithoutFeeScheduleKeyShouldFail(
            @FungibleToken(
                            name = "noFeeKeyFungibleToken",
                            keys = {ADMIN_KEY})
                    final SpecFungibleToken noFeeKeyFungibleToken,
            @NonFungibleToken(
                            name = "noFeeKeyNonFungibleToken",
                            keys = {ADMIN_KEY, SUPPLY_KEY})
                    final SpecNonFungibleToken noFeeKeyNonFungibleToken) {
        return hapiTest(
                updateTokenFeeSchedules
                        .call("updateFungibleFixedTokenFee", noFeeKeyFungibleToken, 1L, feeCollector)
                        .andAssert(
                                txn -> txn.hasKnownStatuses(CONTRACT_REVERT_EXECUTED, TOKEN_HAS_NO_FEE_SCHEDULE_KEY)),
                updateTokenFeeSchedules
                        .call("updateNonFungibleRoyaltyFee", noFeeKeyNonFungibleToken, 1L, 10L, feeCollector)
                        .andAssert(
                                txn -> txn.hasKnownStatuses(CONTRACT_REVERT_EXECUTED, TOKEN_HAS_NO_FEE_SCHEDULE_KEY)));
    }

    @Order(18)
    @HapiTest
    @DisplayName("update tokens with negative values")
    public Stream<DynamicTest> updateTokensWithNegativeValues() {
        return hapiTest(
                updateTokenFeeSchedules
                        .call("updateFungibleFixedHbarFee", fungibleToken, -1L, feeCollector)
                        .andAssert(txn -> txn.hasKnownStatuses(CONTRACT_REVERT_EXECUTED, CUSTOM_FEE_MUST_BE_POSITIVE)),
                updateTokenFeeSchedules
                        .call("updateNonFungibleFixedHbarFee", nonFungibleToken, -1L, feeCollector)
                        .andAssert(txn -> txn.hasKnownStatuses(CONTRACT_REVERT_EXECUTED, CUSTOM_FEE_MUST_BE_POSITIVE)),
                updateTokenFeeSchedules
                        .call("updateFungibleFixedHtsFee", fungibleToken, feeToken, -1L, feeCollector)
                        .andAssert(txn -> txn.hasKnownStatuses(CONTRACT_REVERT_EXECUTED, CUSTOM_FEE_MUST_BE_POSITIVE)),
                updateTokenFeeSchedules
                        .call("updateNonFungibleFixedHtsFee", nonFungibleToken, feeToken, -1L, feeCollector)
                        .andAssert(txn -> txn.hasKnownStatuses(CONTRACT_REVERT_EXECUTED, CUSTOM_FEE_MUST_BE_POSITIVE)),
                updateTokenFeeSchedules
                        .call("updateNonFungibleRoyaltyFee", nonFungibleToken, -1L, -10L, feeCollector)
                        .andAssert(txn -> txn.hasKnownStatuses(CONTRACT_REVERT_EXECUTED, CUSTOM_FEE_MUST_BE_POSITIVE)),
                updateTokenFeeSchedules
                        .call("updateFungibleFractionalFee", fungibleToken, 1L, -10L, false, feeCollector)
                        .andAssert(txn -> txn.hasKnownStatuses(CONTRACT_REVERT_EXECUTED, CUSTOM_FEE_MUST_BE_POSITIVE)));
    }

    @Order(19)
    @HapiTest
    @DisplayName("update token fractional fee with zero fraction")
    public Stream<DynamicTest> updateFractionalFeeWithZeroFraction() {
        return hapiTest(updateTokenFeeSchedules
                .call("updateFungibleFractionalFee", feeToken, 1L, 0L, false, feeCollector)
                .andAssert(txn -> txn.hasKnownStatuses(CONTRACT_REVERT_EXECUTED, FRACTION_DIVIDES_BY_ZERO)));
    }

    @Order(20)
    @HapiTest
    @DisplayName("update token fees with more than max allowed fees")
    public Stream<DynamicTest> updateTokenFeesAboveMaxAllowed() {
        return hapiTest(
                overriding("tokens.maxCustomFeesAllowed", "10"),
                updateTokenFeeSchedules
                        .call("updateFungibleFixedHbarFees", fungibleToken, 11, 10L, feeCollector)
                        .andAssert(txn -> txn.hasKnownStatuses(CONTRACT_REVERT_EXECUTED, CUSTOM_FEES_LIST_TOO_LONG)),
                updateTokenFeeSchedules
                        .call("updateFungibleFractionalFees", feeToken, 11, 1L, 10L, false, feeCollector)
                        .andAssert(txn -> txn.hasKnownStatuses(CONTRACT_REVERT_EXECUTED, CUSTOM_FEES_LIST_TOO_LONG)),
                updateTokenFeeSchedules
                        .call("updateNonFungibleRoyaltyFees", nonFungibleToken, 11, 1L, 10L, feeCollector)
                        .andAssert(txn -> txn.hasKnownStatuses(CONTRACT_REVERT_EXECUTED, CUSTOM_FEES_LIST_TOO_LONG)));
    }

    @Order(21)
    @HapiTest
    @DisplayName("update token fees with invalid fee collector")
    public Stream<DynamicTest> updateFeesWithInvalidFeeCollector() {
        final var invalidFeeCollector = asHeadlongAddress(asEvmAddress(0L));
        return hapiTest(
                updateTokenFeeSchedules
                        .call("updateFungibleFixedHbarFee", fungibleToken, 10L, invalidFeeCollector)
                        .andAssert(txn -> txn.hasKnownStatuses(CONTRACT_REVERT_EXECUTED, INVALID_CUSTOM_FEE_COLLECTOR)),
                updateTokenFeeSchedules
                        .call("updateNonFungibleRoyaltyFee", nonFungibleToken, 1L, 10L, invalidFeeCollector)
                        .andAssert(
                                txn -> txn.hasKnownStatuses(CONTRACT_REVERT_EXECUTED, INVALID_CUSTOM_FEE_COLLECTOR)));
    }

    @Order(22)
    @HapiTest
    @DisplayName("update token fees with invalid token denominator")
    public Stream<DynamicTest> updateFeesWithInvalidToken() {
        final var invalidTokenAddress = asHeadlongAddress(asEvmAddress(1912312313L));
        return hapiTest(
                updateTokenFeeSchedules
                        .call("updateFungibleFixedHtsFee", fungibleToken, invalidTokenAddress, 10L, feeCollector)
                        .andAssert(
                                txn -> txn.hasKnownStatuses(CONTRACT_REVERT_EXECUTED, INVALID_TOKEN_ID_IN_CUSTOM_FEES)),
                updateTokenFeeSchedules
                        .call(
                                "updateNonFungibleRoyaltyFeeHtsFallback",
                                nonFungibleToken,
                                invalidTokenAddress,
                                1L,
                                10L,
                                10L,
                                feeCollector)
                        .andAssert(txn ->
                                txn.hasKnownStatuses(CONTRACT_REVERT_EXECUTED, INVALID_TOKEN_ID_IN_CUSTOM_FEES)));
    }

    @Order(23)
    @HapiTest
    @DisplayName("update token fees with collector not associated with token")
    public Stream<DynamicTest> updateFeesWithCollectorNotAssociatedToToken(
            @Account(name = "collector", tinybarBalance = ONE_HUNDRED_HBARS) final SpecAccount collector) {
        return hapiTest(
                updateTokenFeeSchedules
                        .call("updateFungibleFixedHtsFee", fungibleToken, feeToken, 10L, collector)
                        .andAssert(txn ->
                                txn.hasKnownStatuses(CONTRACT_REVERT_EXECUTED, TOKEN_NOT_ASSOCIATED_TO_FEE_COLLECTOR)),
                updateTokenFeeSchedules
                        .call(
                                "updateNonFungibleRoyaltyFeeHtsFallback",
                                nonFungibleToken,
                                feeToken,
                                1L,
                                10L,
                                10L,
                                collector)
                        .andAssert(txn ->
                                txn.hasKnownStatuses(CONTRACT_REVERT_EXECUTED, TOKEN_NOT_ASSOCIATED_TO_FEE_COLLECTOR)));
    }

    @Order(24)
    @HapiTest
    @DisplayName("update nft token royalty fees with invalid fee denomination")
    public Stream<DynamicTest> updateNonFungibleTokenWithInvalidFeeDenomination() {
        return hapiTest(updateTokenFeeSchedules
                .call("updateNonFungibleFixedTokenFee", nonFungibleToken, 1L, feeCollector)
                .andAssert(txn -> txn.hasKnownStatuses(
                        CONTRACT_REVERT_EXECUTED, CUSTOM_FEE_DENOMINATION_MUST_BE_FUNGIBLE_COMMON)));
    }
}
