// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.token;

import static com.hedera.services.bdd.junit.TestTags.TOKEN;
import static com.hedera.services.bdd.spec.HapiSpec.defaultHapiSpec;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTokenInfo;
import static com.hedera.services.bdd.spec.queries.crypto.ExpectedTokenRel.relationshipWith;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.burnToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.grantTokenKyc;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.revokeTokenKyc;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenDissociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenFeeScheduleUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenFreeze;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenPause;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenUnfreeze;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenUnpause;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.wipeTokenAccount;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedHbarFee;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedHtsFee;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingUnique;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.TOKEN_TREASURY;
import static com.hedera.services.bdd.suites.utils.MiscEETUtils.metadata;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOKEN_ID_IN_CUSTOM_FEES;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_HAS_NO_PAUSE_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_IS_IMMUTABLE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_IS_PAUSED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_WAS_DELETED;
import static com.hederahashgraph.api.proto.java.TokenPauseStatus.Paused;
import static com.hederahashgraph.api.proto.java.TokenPauseStatus.Unpaused;
import static com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.assertions.BaseErroringAssertsProvider;
import com.hedera.services.bdd.spec.assertions.ErroringAsserts;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.TokenSupplyType;
import com.hederahashgraph.api.proto.java.TokenTransferList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(TOKEN)
public class TokenPauseSpecs {
    private static final String PAUSE_KEY = "pauseKey";
    private static final String SUPPLY_KEY = "supplyKey";
    private static final String FREEZE_KEY = "freezeKey";
    private static final String ADMIN_KEY = "adminKey";
    private static final String KYC_KEY = "kycKey";
    private static final String WIPE_KEY = "wipeKey";
    private static final String FEE_SCHEDULE_KEY = "feeScheduleKey";
    private static final String PRIMARY = "primary";
    private static final String SECONDARY = "secondary";
    private static final String OTHER_KEY = "otherKey";
    private static final String FIRST_USER = "firstUser";
    private static final String SECOND_USER = "secondUser";
    private static final String THIRD_USER = "thirdUser";
    private static final String NON_FUNGIBLE_UNIQUE_PRIMARY = "non-fungible-unique-primary";

    @HapiTest
    final Stream<DynamicTest> cannotAddPauseKeyViaTokenUpdate() {
        return defaultHapiSpec("CannotAddPauseKeyViaTokenUpdate")
                .given(newKeyNamed(PAUSE_KEY), newKeyNamed(ADMIN_KEY))
                .when(tokenCreate(PRIMARY), tokenCreate(SECONDARY).adminKey(ADMIN_KEY))
                .then(
                        tokenUpdate(PRIMARY).pauseKey(PAUSE_KEY).hasKnownStatus(TOKEN_IS_IMMUTABLE),
                        tokenUpdate(SECONDARY)
                                .pauseKey(PAUSE_KEY)
                                .signedByPayerAnd(ADMIN_KEY)
                                .hasKnownStatus(TOKEN_HAS_NO_PAUSE_KEY));
    }

    @HapiTest
    final Stream<DynamicTest> cannotPauseWithInvalidPauseKey() {
        return defaultHapiSpec("cannotPauseWithInvalidPauseKey")
                .given(newKeyNamed(PAUSE_KEY), newKeyNamed(OTHER_KEY))
                .when(tokenCreate(PRIMARY).pauseKey(PAUSE_KEY))
                .then(tokenPause(PRIMARY).signedBy(DEFAULT_PAYER, OTHER_KEY).hasKnownStatus(INVALID_SIGNATURE));
    }

    @HapiTest
    final Stream<DynamicTest> cannotUnpauseWithInvalidPauseKey() {
        return defaultHapiSpec("cannotUnpauseWithInvalidPauseKey")
                .given(newKeyNamed(PAUSE_KEY), newKeyNamed(OTHER_KEY))
                .when(
                        tokenCreate(PRIMARY).pauseKey(PAUSE_KEY),
                        tokenPause(PRIMARY).signedBy(DEFAULT_PAYER, PAUSE_KEY))
                .then(tokenUnpause(PRIMARY).signedBy(DEFAULT_PAYER, OTHER_KEY).hasKnownStatus(INVALID_SIGNATURE));
    }

    @HapiTest
    final Stream<DynamicTest> pausedTokenInCustomFeeCaseStudy() {
        return hapiTest(
                cryptoCreate(TOKEN_TREASURY),
                cryptoCreate(FIRST_USER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(SECOND_USER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(THIRD_USER),
                newKeyNamed(PAUSE_KEY),
                newKeyNamed(KYC_KEY),
                tokenCreate(PRIMARY)
                        .tokenType(FUNGIBLE_COMMON)
                        .supplyType(TokenSupplyType.FINITE)
                        .maxSupply(1000)
                        .initialSupply(500)
                        .decimals(1)
                        .treasury(TOKEN_TREASURY)
                        .pauseKey(PAUSE_KEY)
                        .kycKey(KYC_KEY),
                tokenAssociate(FIRST_USER, PRIMARY),
                grantTokenKyc(PRIMARY, FIRST_USER),
                tokenCreate(SECONDARY)
                        .tokenType(FUNGIBLE_COMMON)
                        .supplyType(TokenSupplyType.FINITE)
                        .maxSupply(1000)
                        .initialSupply(500)
                        .decimals(1)
                        .kycKey(KYC_KEY)
                        .treasury(TOKEN_TREASURY)
                        .withCustom(fixedHtsFee(1, PRIMARY, FIRST_USER)),
                tokenAssociate(SECOND_USER, PRIMARY, SECONDARY),
                grantTokenKyc(SECONDARY, SECOND_USER),
                grantTokenKyc(PRIMARY, SECOND_USER),
                tokenAssociate(THIRD_USER, SECONDARY),
                grantTokenKyc(SECONDARY, THIRD_USER),
                cryptoTransfer(moving(10, PRIMARY).between(TOKEN_TREASURY, SECOND_USER)),
                cryptoTransfer(moving(100, SECONDARY).between(TOKEN_TREASURY, SECOND_USER)),
                tokenPause(PRIMARY),
                cryptoTransfer(moving(10, SECONDARY).between(SECOND_USER, THIRD_USER))
                        .fee(ONE_HBAR)
                        .payingWith(SECOND_USER)
                        .hasKnownStatus(TOKEN_IS_PAUSED));
    }

    @HapiTest
    final Stream<DynamicTest> unpauseWorks() {
        final String firstUser = FIRST_USER;
        final String token = PRIMARY;

        return defaultHapiSpec("UnpauseWorks")
                .given(
                        cryptoCreate(TOKEN_TREASURY),
                        cryptoCreate(firstUser),
                        newKeyNamed(PAUSE_KEY),
                        newKeyNamed(KYC_KEY))
                .when(
                        tokenCreate(token)
                                .tokenType(FUNGIBLE_COMMON)
                                .supplyType(TokenSupplyType.FINITE)
                                .maxSupply(1000)
                                .initialSupply(500)
                                .decimals(1)
                                .pauseKey(PAUSE_KEY)
                                .kycKey(KYC_KEY)
                                .treasury(TOKEN_TREASURY),
                        getTokenInfo(token).hasPauseStatus(Unpaused).hasPauseKey(token),
                        tokenAssociate(firstUser, token),
                        grantTokenKyc(token, firstUser),
                        tokenPause(token),
                        getTokenInfo(token).hasPauseStatus(Paused))
                .then(
                        cryptoTransfer(moving(10, token).between(TOKEN_TREASURY, firstUser))
                                .hasKnownStatus(TOKEN_IS_PAUSED),
                        tokenUnpause(token),
                        getTokenInfo(token).hasPauseStatus(Unpaused),
                        cryptoTransfer(moving(10, token).between(TOKEN_TREASURY, firstUser)),
                        getAccountInfo(firstUser).logged());
    }

    @HapiTest
    final Stream<DynamicTest> pausedNonFungibleUniqueCannotBeUsed() {
        final String uniqueToken = "nonFungibleUnique";
        final String firstUser = FIRST_USER;
        final String secondUser = SECOND_USER;
        final String otherToken = SECONDARY;
        final String thirdUser = THIRD_USER;

        return defaultHapiSpec("PausedNonFungibleUniqueCannotBeUsed")
                .given(
                        cryptoCreate(TOKEN_TREASURY),
                        cryptoCreate(firstUser),
                        cryptoCreate(secondUser),
                        cryptoCreate(thirdUser),
                        newKeyNamed(PAUSE_KEY),
                        newKeyNamed(ADMIN_KEY),
                        newKeyNamed(FREEZE_KEY),
                        newKeyNamed(KYC_KEY),
                        newKeyNamed(SUPPLY_KEY),
                        newKeyNamed(WIPE_KEY))
                .when(
                        tokenCreate(uniqueToken)
                                .tokenType(NON_FUNGIBLE_UNIQUE)
                                .supplyType(TokenSupplyType.FINITE)
                                .pauseKey(PAUSE_KEY)
                                .supplyKey(SUPPLY_KEY)
                                .adminKey(ADMIN_KEY)
                                .freezeKey(FREEZE_KEY)
                                .kycKey(KYC_KEY)
                                .wipeKey(WIPE_KEY)
                                .initialSupply(0)
                                .maxSupply(100)
                                .treasury(TOKEN_TREASURY),
                        tokenCreate(otherToken)
                                .tokenType(FUNGIBLE_COMMON)
                                .supplyType(TokenSupplyType.FINITE)
                                .maxSupply(1000)
                                .initialSupply(500)
                                .decimals(1)
                                .kycKey(KYC_KEY)
                                .treasury(TOKEN_TREASURY),
                        tokenAssociate(firstUser, uniqueToken),
                        mintToken(uniqueToken, List.of(metadata("firstMinted"), metadata("SecondMinted"))),
                        grantTokenKyc(uniqueToken, firstUser),
                        tokenAssociate(thirdUser, otherToken),
                        grantTokenKyc(otherToken, thirdUser),
                        cryptoTransfer(movingUnique(uniqueToken, 1L).between(TOKEN_TREASURY, firstUser)),
                        tokenPause(uniqueToken))
                .then(
                        getTokenInfo(uniqueToken)
                                .logged()
                                .hasPauseKey(uniqueToken)
                                .hasPauseStatus(Paused),
                        tokenCreate("failedTokenCreate")
                                .treasury(TOKEN_TREASURY)
                                .withCustom(fixedHtsFee(1, uniqueToken, firstUser))
                                .hasKnownStatus(INVALID_TOKEN_ID_IN_CUSTOM_FEES),
                        tokenAssociate(secondUser, uniqueToken).hasKnownStatus(TOKEN_IS_PAUSED),
                        cryptoTransfer(movingUnique(uniqueToken, 2L).between(TOKEN_TREASURY, firstUser))
                                .hasKnownStatus(TOKEN_IS_PAUSED),
                        tokenDissociate(firstUser, uniqueToken).hasKnownStatus(TOKEN_IS_PAUSED),
                        mintToken(uniqueToken, List.of(metadata("thirdMinted"))).hasKnownStatus(TOKEN_IS_PAUSED),
                        burnToken(uniqueToken, List.of(2L)).hasKnownStatus(TOKEN_IS_PAUSED),
                        tokenFreeze(uniqueToken, firstUser).hasKnownStatus(TOKEN_IS_PAUSED),
                        tokenUnfreeze(uniqueToken, firstUser).hasKnownStatus(TOKEN_IS_PAUSED),
                        revokeTokenKyc(uniqueToken, firstUser).hasKnownStatus(TOKEN_IS_PAUSED),
                        grantTokenKyc(uniqueToken, firstUser).hasKnownStatus(TOKEN_IS_PAUSED),
                        tokenFeeScheduleUpdate(uniqueToken)
                                .withCustom(fixedHbarFee(100, TOKEN_TREASURY))
                                .hasKnownStatus(TOKEN_IS_PAUSED),
                        wipeTokenAccount(uniqueToken, firstUser, List.of(1L)).hasKnownStatus(TOKEN_IS_PAUSED),
                        tokenUpdate(uniqueToken)
                                .name("newName")
                                .signedByPayerAnd(ADMIN_KEY)
                                .hasKnownStatus(TOKEN_IS_PAUSED),
                        tokenDelete(uniqueToken).hasKnownStatus(TOKEN_IS_PAUSED),
                        cryptoTransfer(
                                        moving(100, otherToken).between(TOKEN_TREASURY, thirdUser),
                                        movingUnique(uniqueToken, 2L).between(TOKEN_TREASURY, firstUser))
                                .via("rolledBack")
                                .hasKnownStatus(TOKEN_IS_PAUSED),
                        getAccountInfo(TOKEN_TREASURY)
                                .hasToken(relationshipWith(otherToken).balance(500)));
    }

    @HapiTest
    final Stream<DynamicTest> pausedFungibleTokenCannotBeUsed() {
        final String token = PRIMARY;
        final String otherToken = SECONDARY;
        final String firstUser = FIRST_USER;
        final String secondUser = SECOND_USER;
        final String thirdUser = THIRD_USER;
        return hapiTest(
                cryptoCreate(TOKEN_TREASURY),
                cryptoCreate(firstUser).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(secondUser),
                cryptoCreate(thirdUser),
                newKeyNamed(PAUSE_KEY),
                newKeyNamed(ADMIN_KEY),
                newKeyNamed(FREEZE_KEY),
                newKeyNamed(KYC_KEY),
                newKeyNamed(FEE_SCHEDULE_KEY),
                newKeyNamed(SUPPLY_KEY),
                newKeyNamed(WIPE_KEY),
                tokenCreate(token)
                        .tokenType(FUNGIBLE_COMMON)
                        .supplyType(TokenSupplyType.FINITE)
                        .maxSupply(1000)
                        .initialSupply(500)
                        .decimals(1)
                        .treasury(TOKEN_TREASURY)
                        .adminKey(ADMIN_KEY)
                        .pauseKey(PAUSE_KEY)
                        .freezeKey(FREEZE_KEY)
                        .kycKey(KYC_KEY)
                        .wipeKey(WIPE_KEY)
                        .supplyKey(SUPPLY_KEY)
                        .feeScheduleKey(FEE_SCHEDULE_KEY),
                tokenCreate(otherToken)
                        .tokenType(FUNGIBLE_COMMON)
                        .supplyType(TokenSupplyType.FINITE)
                        .maxSupply(1000)
                        .initialSupply(500)
                        .decimals(1)
                        .kycKey(KYC_KEY)
                        .treasury(TOKEN_TREASURY),
                tokenAssociate(firstUser, token),
                grantTokenKyc(token, firstUser),
                tokenAssociate(thirdUser, otherToken),
                grantTokenKyc(otherToken, thirdUser),
                cryptoTransfer(moving(100, token).between(TOKEN_TREASURY, firstUser)),
                tokenPause(token),
                getTokenInfo(token).logged().hasPauseKey(token).hasPauseStatus(Paused),
                tokenCreate("failedTokenCreate")
                        .treasury(TOKEN_TREASURY)
                        .withCustom(fixedHtsFee(1, token, firstUser))
                        .hasKnownStatus(INVALID_TOKEN_ID_IN_CUSTOM_FEES),
                tokenAssociate(secondUser, token).hasKnownStatus(TOKEN_IS_PAUSED),
                cryptoTransfer(moving(10, token).between(TOKEN_TREASURY, firstUser))
                        .hasKnownStatus(TOKEN_IS_PAUSED),
                tokenDissociate(firstUser, token).hasKnownStatus(TOKEN_IS_PAUSED),
                mintToken(token, 1).hasKnownStatus(TOKEN_IS_PAUSED),
                burnToken(token, 1).hasKnownStatus(TOKEN_IS_PAUSED),
                tokenFreeze(token, firstUser).hasKnownStatus(TOKEN_IS_PAUSED),
                tokenUnfreeze(token, firstUser).hasKnownStatus(TOKEN_IS_PAUSED),
                revokeTokenKyc(token, firstUser).hasKnownStatus(TOKEN_IS_PAUSED),
                grantTokenKyc(token, firstUser).hasKnownStatus(TOKEN_IS_PAUSED),
                tokenFeeScheduleUpdate(token)
                        .withCustom(fixedHbarFee(100, TOKEN_TREASURY))
                        .hasKnownStatus(TOKEN_IS_PAUSED),
                wipeTokenAccount(token, firstUser, 10).hasKnownStatus(TOKEN_IS_PAUSED),
                tokenUpdate(token).name("newName").signedByPayerAnd(ADMIN_KEY).hasKnownStatus(TOKEN_IS_PAUSED),
                tokenDelete(token).hasKnownStatus(TOKEN_IS_PAUSED),
                cryptoTransfer(
                                moving(100, otherToken).between(TOKEN_TREASURY, thirdUser),
                                moving(20, token).between(TOKEN_TREASURY, firstUser))
                        .via("rolledBack")
                        .hasKnownStatus(TOKEN_IS_PAUSED),
                getAccountInfo(TOKEN_TREASURY)
                        .hasToken(relationshipWith(otherToken).balance(500)));
    }

    @HapiTest
    final Stream<DynamicTest> cannotChangePauseStatusIfMissingPauseKey() {
        return defaultHapiSpec("CannotChangePauseStatusIfMissingPauseKey")
                .given(cryptoCreate(TOKEN_TREASURY))
                .when(
                        tokenCreate(PRIMARY)
                                .tokenType(FUNGIBLE_COMMON)
                                .supplyType(TokenSupplyType.FINITE)
                                .maxSupply(1000)
                                .initialSupply(500)
                                .decimals(1)
                                .treasury(TOKEN_TREASURY),
                        tokenCreate(NON_FUNGIBLE_UNIQUE_PRIMARY)
                                .tokenType(NON_FUNGIBLE_UNIQUE)
                                .supplyKey(GENESIS)
                                .supplyType(TokenSupplyType.FINITE)
                                .initialSupply(0)
                                .maxSupply(100)
                                .treasury(TOKEN_TREASURY))
                .then(
                        tokenPause(PRIMARY).signedBy(GENESIS).hasKnownStatus(ResponseCodeEnum.TOKEN_HAS_NO_PAUSE_KEY),
                        tokenUnpause(PRIMARY).signedBy(GENESIS).hasKnownStatus(ResponseCodeEnum.TOKEN_HAS_NO_PAUSE_KEY),
                        tokenPause(NON_FUNGIBLE_UNIQUE_PRIMARY)
                                .signedBy(GENESIS)
                                .hasKnownStatus(ResponseCodeEnum.TOKEN_HAS_NO_PAUSE_KEY),
                        tokenUnpause(NON_FUNGIBLE_UNIQUE_PRIMARY)
                                .signedBy(GENESIS)
                                .hasKnownStatus(ResponseCodeEnum.TOKEN_HAS_NO_PAUSE_KEY));
    }

    @HapiTest
    final Stream<DynamicTest> unpauseDeletedToken() {
        return hapiTest(
                cryptoCreate(PAUSE_KEY),
                cryptoCreate(ADMIN_KEY),
                tokenCreate(PRIMARY).adminKey(ADMIN_KEY).pauseKey(PAUSE_KEY),
                tokenDelete(PRIMARY),
                tokenUnpause(PRIMARY).hasKnownStatus(TOKEN_WAS_DELETED));
    }

    public static class TokenIdOrderingAsserts extends BaseErroringAssertsProvider<List<TokenTransferList>> {

        private final String[] expectedTokenIds;

        public TokenIdOrderingAsserts(final String[] expectedTokenIds) {
            this.expectedTokenIds = expectedTokenIds;
        }

        public static TokenIdOrderingAsserts withOrderedTokenIds(String... tokenIds) {
            return new TokenIdOrderingAsserts(tokenIds);
        }

        @Override
        @SuppressWarnings("java:S5960")
        public ErroringAsserts<List<TokenTransferList>> assertsFor(final HapiSpec spec) {
            return tokenTransfers -> {
                final var registry = spec.registry();
                try {
                    assertEquals(expectedTokenIds.length, tokenTransfers.size(), "Wrong # of token ids");
                    var nextI = 0;
                    for (final var expected : expectedTokenIds) {
                        final var expectedId = registry.getTokenID(expected);
                        final var actualId = tokenTransfers.get(nextI++).getToken();
                        assertEquals(expectedId, actualId, "Wrong token at index " + (nextI - 1));
                    }
                } catch (Exception failure) {
                    return List.of(failure);
                }
                return Collections.emptyList();
            };
        }
    }
}
