// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.token.batch;

import static com.hedera.services.bdd.junit.TestTags.TOKEN;
import static com.hedera.services.bdd.spec.HapiSpec.defaultHapiSpec;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTokenInfo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
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
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.TOKEN_TREASURY;
import static com.hedera.services.bdd.suites.HapiSuite.flattened;
import static com.hedera.services.bdd.suites.utils.MiscEETUtils.metadata;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INNER_TRANSACTION_FAILED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOKEN_ID_IN_CUSTOM_FEES;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_HAS_NO_PAUSE_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_IS_IMMUTABLE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_IS_PAUSED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_WAS_DELETED;
import static com.hederahashgraph.api.proto.java.TokenPauseStatus.Paused;
import static com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.TokenSupplyType;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

// This test cases are direct copies of TokenPauseSpecs. The difference here is that
// we are wrapping the operations in an atomic batch to confirm that everything works as expected.
@HapiTestLifecycle
@Tag(TOKEN)
public class AtomicTokenPauseSpecs {

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
    private static final String BATCH_OPERATOR = "batchOperator";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(
                Map.of("atomicBatch.isEnabled", "true", "atomicBatch.maxNumberOfTransactions", "50"));
        testLifecycle.doAdhoc(cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS));
    }

    @HapiTest
    final Stream<DynamicTest> cannotAddPauseKeyViaTokenUpdate() {
        return defaultHapiSpec("CannotAddPauseKeyViaTokenUpdate")
                .given(newKeyNamed(PAUSE_KEY), newKeyNamed(ADMIN_KEY))
                .when(tokenCreate(PRIMARY), tokenCreate(SECONDARY).adminKey(ADMIN_KEY))
                .then(
                        atomicBatch(tokenUpdate(PRIMARY)
                                        .pauseKey(PAUSE_KEY)
                                        .hasKnownStatus(TOKEN_IS_IMMUTABLE)
                                        .batchKey(BATCH_OPERATOR))
                                .payingWith(BATCH_OPERATOR)
                                .hasKnownStatus(INNER_TRANSACTION_FAILED),
                        atomicBatch(tokenUpdate(SECONDARY)
                                        .pauseKey(PAUSE_KEY)
                                        .signedByPayerAnd(ADMIN_KEY)
                                        .hasKnownStatus(TOKEN_HAS_NO_PAUSE_KEY)
                                        .batchKey(BATCH_OPERATOR))
                                .payingWith(BATCH_OPERATOR)
                                .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    @HapiTest
    final Stream<DynamicTest> cannotPauseWithInvalidPauseKey() {
        return defaultHapiSpec("cannotPauseWithInvalidPauseKey")
                .given(newKeyNamed(PAUSE_KEY), newKeyNamed(OTHER_KEY))
                .when(tokenCreate(PRIMARY).pauseKey(PAUSE_KEY))
                .then(atomicBatch(tokenPause(PRIMARY)
                                .signedBy(DEFAULT_PAYER, OTHER_KEY)
                                .hasKnownStatus(INVALID_SIGNATURE)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
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
                atomicBatch(cryptoTransfer(moving(10, SECONDARY).between(SECOND_USER, THIRD_USER))
                                .fee(ONE_HBAR)
                                .payingWith(SECOND_USER)
                                .hasKnownStatus(TOKEN_IS_PAUSED)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    private HapiSpecOperation[] pausedNonFungibleUniqueCannotBeUsedBase() {
        final String uniqueToken = "nonFungibleUnique";
        final String firstUser = FIRST_USER;
        final String secondUser = SECOND_USER;
        final String otherToken = SECONDARY;
        final String thirdUser = THIRD_USER;
        return new HapiSpecOperation[] {
            cryptoCreate(TOKEN_TREASURY),
            cryptoCreate(firstUser),
            cryptoCreate(secondUser),
            cryptoCreate(thirdUser),
            newKeyNamed(PAUSE_KEY),
            newKeyNamed(ADMIN_KEY),
            newKeyNamed(FREEZE_KEY),
            newKeyNamed(KYC_KEY),
            newKeyNamed(SUPPLY_KEY),
            newKeyNamed(WIPE_KEY),
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
            tokenPause(uniqueToken),
            getTokenInfo(uniqueToken).logged().hasPauseKey(uniqueToken).hasPauseStatus(Paused),
            tokenCreate("failedTokenCreate")
                    .treasury(TOKEN_TREASURY)
                    .withCustom(fixedHtsFee(1, uniqueToken, firstUser))
                    .hasKnownStatus(INVALID_TOKEN_ID_IN_CUSTOM_FEES),
        };
    }

    @HapiTest
    final Stream<DynamicTest> pausedNonFungibleUniqueCannotBeUsed() {
        final String uniqueToken = "nonFungibleUnique";
        final String firstUser = FIRST_USER;
        final String secondUser = SECOND_USER;
        final String otherToken = SECONDARY;
        final String thirdUser = THIRD_USER;

        return hapiTest(flattened(
                pausedNonFungibleUniqueCannotBeUsedBase(),
                atomicBatch(tokenAssociate(secondUser, uniqueToken)
                                .hasKnownStatus(TOKEN_IS_PAUSED)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatch(cryptoTransfer(movingUnique(uniqueToken, 2L).between(TOKEN_TREASURY, firstUser))
                                .hasKnownStatus(TOKEN_IS_PAUSED)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatch(tokenDissociate(firstUser, uniqueToken)
                                .hasKnownStatus(TOKEN_IS_PAUSED)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatch(mintToken(uniqueToken, List.of(metadata("thirdMinted")))
                                .hasKnownStatus(TOKEN_IS_PAUSED)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatch(burnToken(uniqueToken, List.of(2L))
                                .hasKnownStatus(TOKEN_IS_PAUSED)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatch(tokenFreeze(uniqueToken, firstUser)
                                .hasKnownStatus(TOKEN_IS_PAUSED)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatch(tokenUnfreeze(uniqueToken, firstUser)
                                .hasKnownStatus(TOKEN_IS_PAUSED)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatch(revokeTokenKyc(uniqueToken, firstUser)
                                .hasKnownStatus(TOKEN_IS_PAUSED)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatch(grantTokenKyc(uniqueToken, firstUser)
                                .hasKnownStatus(TOKEN_IS_PAUSED)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatch(tokenFeeScheduleUpdate(uniqueToken)
                                .withCustom(fixedHbarFee(100, TOKEN_TREASURY))
                                .hasKnownStatus(TOKEN_IS_PAUSED)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatch(wipeTokenAccount(uniqueToken, firstUser, List.of(1L))
                                .hasKnownStatus(TOKEN_IS_PAUSED)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatch(tokenUpdate(uniqueToken)
                                .name("newName")
                                .signedByPayerAnd(ADMIN_KEY)
                                .hasKnownStatus(TOKEN_IS_PAUSED)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatch(tokenDelete(uniqueToken)
                                .hasKnownStatus(TOKEN_IS_PAUSED)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatch(cryptoTransfer(
                                        moving(100, otherToken).between(TOKEN_TREASURY, thirdUser),
                                        movingUnique(uniqueToken, 2L).between(TOKEN_TREASURY, firstUser))
                                .via("rolledBack")
                                .hasKnownStatus(TOKEN_IS_PAUSED)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)));
    }

    private HapiSpecOperation[] pausedFungibleTokenCannotBeUsedBase() {
        final String token = PRIMARY;
        final String otherToken = SECONDARY;
        final String firstUser = FIRST_USER;
        final String secondUser = SECOND_USER;
        final String thirdUser = THIRD_USER;

        return new HapiSpecOperation[] {
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
            cryptoTransfer(moving(100, token).between(TOKEN_TREASURY, firstUser))
        };
    }

    @HapiTest
    final Stream<DynamicTest> pausedFungibleTokenCannotBeUsed() {
        final String token = PRIMARY;
        final String otherToken = SECONDARY;
        final String firstUser = FIRST_USER;
        final String secondUser = SECOND_USER;
        final String thirdUser = THIRD_USER;
        return hapiTest(flattened(
                pausedFungibleTokenCannotBeUsedBase(),
                tokenPause(token),
                atomicBatch(tokenCreate("failedTokenCreate")
                                .treasury(TOKEN_TREASURY)
                                .withCustom(fixedHtsFee(1, token, firstUser))
                                .hasKnownStatus(INVALID_TOKEN_ID_IN_CUSTOM_FEES)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatch(tokenAssociate(secondUser, token)
                                .hasKnownStatus(TOKEN_IS_PAUSED)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatch(cryptoTransfer(moving(10, token).between(TOKEN_TREASURY, firstUser))
                                .hasKnownStatus(TOKEN_IS_PAUSED)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatch(tokenDissociate(firstUser, token)
                                .hasKnownStatus(TOKEN_IS_PAUSED)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatch(mintToken(token, 1).hasKnownStatus(TOKEN_IS_PAUSED).batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatch(burnToken(token, 1).hasKnownStatus(TOKEN_IS_PAUSED).batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatch(tokenFreeze(token, firstUser)
                                .hasKnownStatus(TOKEN_IS_PAUSED)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatch(tokenUnfreeze(token, firstUser)
                                .hasKnownStatus(TOKEN_IS_PAUSED)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatch(revokeTokenKyc(token, firstUser)
                                .hasKnownStatus(TOKEN_IS_PAUSED)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatch(grantTokenKyc(token, firstUser)
                                .hasKnownStatus(TOKEN_IS_PAUSED)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatch(tokenFeeScheduleUpdate(token)
                                .withCustom(fixedHbarFee(100, TOKEN_TREASURY))
                                .hasKnownStatus(TOKEN_IS_PAUSED)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatch(wipeTokenAccount(token, firstUser, 10)
                                .hasKnownStatus(TOKEN_IS_PAUSED)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatch(tokenUpdate(token)
                                .name("newName")
                                .signedByPayerAnd(ADMIN_KEY)
                                .hasKnownStatus(TOKEN_IS_PAUSED)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatch(tokenDelete(token).hasKnownStatus(TOKEN_IS_PAUSED).batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatch(cryptoTransfer(
                                        moving(100, otherToken).between(TOKEN_TREASURY, thirdUser),
                                        moving(20, token).between(TOKEN_TREASURY, firstUser))
                                .via("rolledBack")
                                .hasKnownStatus(TOKEN_IS_PAUSED)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)));
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
                        atomicBatch(tokenPause(PRIMARY)
                                        .signedBy(GENESIS)
                                        .hasKnownStatus(ResponseCodeEnum.TOKEN_HAS_NO_PAUSE_KEY)
                                        .batchKey(BATCH_OPERATOR))
                                .payingWith(BATCH_OPERATOR)
                                .hasKnownStatus(INNER_TRANSACTION_FAILED),
                        atomicBatch(tokenUnpause(PRIMARY)
                                        .signedBy(GENESIS)
                                        .hasKnownStatus(ResponseCodeEnum.TOKEN_HAS_NO_PAUSE_KEY)
                                        .batchKey(BATCH_OPERATOR))
                                .payingWith(BATCH_OPERATOR)
                                .hasKnownStatus(INNER_TRANSACTION_FAILED),
                        atomicBatch(tokenPause(NON_FUNGIBLE_UNIQUE_PRIMARY)
                                        .signedBy(GENESIS)
                                        .hasKnownStatus(ResponseCodeEnum.TOKEN_HAS_NO_PAUSE_KEY)
                                        .batchKey(BATCH_OPERATOR))
                                .payingWith(BATCH_OPERATOR)
                                .hasKnownStatus(INNER_TRANSACTION_FAILED),
                        atomicBatch(tokenUnpause(NON_FUNGIBLE_UNIQUE_PRIMARY)
                                        .signedBy(GENESIS)
                                        .hasKnownStatus(ResponseCodeEnum.TOKEN_HAS_NO_PAUSE_KEY)
                                        .batchKey(BATCH_OPERATOR))
                                .payingWith(BATCH_OPERATOR)
                                .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    @HapiTest
    final Stream<DynamicTest> unpauseDeletedToken() {
        return hapiTest(
                cryptoCreate(PAUSE_KEY),
                cryptoCreate(ADMIN_KEY),
                tokenCreate(PRIMARY).adminKey(ADMIN_KEY).pauseKey(PAUSE_KEY),
                tokenDelete(PRIMARY),
                atomicBatch(tokenUnpause(PRIMARY)
                                .hasKnownStatus(TOKEN_WAS_DELETED)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }
}
