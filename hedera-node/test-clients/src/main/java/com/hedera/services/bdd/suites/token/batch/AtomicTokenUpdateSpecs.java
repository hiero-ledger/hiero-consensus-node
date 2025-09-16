// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.token.batch;

import static com.hedera.services.bdd.junit.TestTags.TOKEN;
import static com.hedera.services.bdd.spec.HapiSpec.defaultHapiSpec;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTokenInfo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenUpdate;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingUnique;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doSeveralWithStartupConfigNow;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doWithStartupConfigNow;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.specOps;
import static com.hedera.services.bdd.suites.HapiSuite.ADDRESS_BOOK_CONTROL;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.THREE_MONTHS_IN_SECONDS;
import static com.hedera.services.bdd.suites.HapiSuite.salted;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_DELETED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.EXPIRATION_REDUCTION_NOT_ALLOWED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INNER_TRANSACTION_FAILED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ADMIN_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_AUTORENEW_ACCOUNT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_EXPIRATION_TIME;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_RENEWAL_PERIOD;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOKEN_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.NO_REMAINING_AUTOMATIC_ASSOCIATIONS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_HAS_NO_FREEZE_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_HAS_NO_KYC_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_HAS_NO_METADATA_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_HAS_NO_PAUSE_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_HAS_NO_SUPPLY_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_HAS_NO_WIPE_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_IS_IMMUTABLE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_NAME_TOO_LONG;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_SYMBOL_TOO_LONG;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_WAS_DELETED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TRANSACTION_REQUIRES_ZERO_TOKEN_BALANCES;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;
import static java.lang.Long.parseLong;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

// This test cases are direct copies of TokenUpdateSpecs. The difference here is that
// we are wrapping the operations in an atomic batch to confirm that everything works as expected.
@HapiTestLifecycle
@Tag(TOKEN)
public class AtomicTokenUpdateSpecs {

    private static final int MAX_NAME_LENGTH = 100;
    private static final int MAX_SYMBOL_LENGTH = 100;
    private static final String PAYER = "payer";
    private static final String INVALID_TREASURY = "invalidTreasury";
    private static final String TOKEN_TREASURY = "treasury";
    private static final String BATCH_OPERATOR = "batchOperator";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(
                Map.of("atomicBatch.isEnabled", "true", "atomicBatch.maxNumberOfTransactions", "50"));
        testLifecycle.doAdhoc(cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS));
    }

    @HapiTest
    final Stream<DynamicTest> canUpdateExpiryOnlyOpWithoutAdminKey() {
        String originalMemo = "First things first";
        String saltedName = salted("primary");
        final var civilian = "civilian";
        return defaultHapiSpec("ValidatesNewExpiry")
                .given(
                        cryptoCreate(civilian).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TOKEN_TREASURY).balance(0L),
                        newKeyNamed("adminKey"),
                        newKeyNamed("freezeKey"),
                        newKeyNamed("newFreezeKey"),
                        newKeyNamed("kycKey"),
                        newKeyNamed("newKycKey"),
                        newKeyNamed("supplyKey"),
                        newKeyNamed("newSupplyKey"),
                        newKeyNamed("wipeKey"),
                        newKeyNamed("newWipeKey"),
                        newKeyNamed("pauseKey"),
                        newKeyNamed("newPauseKey"),
                        tokenCreate("primary")
                                .autoRenewAccount(civilian)
                                .name(saltedName)
                                .entityMemo(originalMemo)
                                .treasury(TOKEN_TREASURY)
                                .initialSupply(500)
                                .decimals(1)
                                .freezeKey("freezeKey")
                                .kycKey("kycKey")
                                .supplyKey("supplyKey")
                                .wipeKey("wipeKey")
                                .pauseKey("pauseKey")
                                .payingWith(civilian))
                .when()
                .then(doWithStartupConfigNow("entities.maxLifetime", (value, now) -> atomicBatch(tokenUpdate("primary")
                                .expiry(parseLong(value) + now.getEpochSecond() - 12345)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)));
    }

    @HapiTest
    final Stream<DynamicTest> validatesNewExpiry() {
        return hapiTest(tokenCreate("tbu"), doSeveralWithStartupConfigNow("entities.maxLifetime", (value, now) -> {
            final var maxLifetime = Long.parseLong(value);
            final var excessiveExpiry = now.getEpochSecond() + maxLifetime + 12_345L;
            return specOps(atomicBatch(tokenUpdate("tbu")
                            .expiry(excessiveExpiry)
                            .hasKnownStatus(INVALID_EXPIRATION_TIME)
                            .batchKey(BATCH_OPERATOR))
                    .payingWith(BATCH_OPERATOR)
                    .hasKnownStatus(INNER_TRANSACTION_FAILED));
        }));
    }

    @HapiTest
    final Stream<DynamicTest> validatesAlreadyDeletedToken() {
        return defaultHapiSpec("ValidatesAlreadyDeletedToken")
                .given(
                        newKeyNamed("adminKey"),
                        cryptoCreate(TOKEN_TREASURY).balance(0L),
                        tokenCreate("tbd").adminKey("adminKey").treasury(TOKEN_TREASURY),
                        tokenDelete("tbd"))
                .when()
                .then(atomicBatch(tokenUpdate("tbd")
                                .signedByPayerAnd("adminKey")
                                .hasKnownStatus(TOKEN_WAS_DELETED)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    @HapiTest
    final Stream<DynamicTest> tokensCanBeMadeImmutableWithEmptyKeyList() {
        final var mutableForNow = "mutableForNow";
        return defaultHapiSpec("TokensCanBeMadeImmutableWithEmptyKeyList")
                .given(
                        newKeyNamed("initialAdmin"),
                        cryptoCreate("neverToBe").balance(0L),
                        tokenCreate(mutableForNow).adminKey("initialAdmin"))
                .when(
                        atomicBatch(tokenUpdate(mutableForNow)
                                        .usingInvalidAdminKey()
                                        .signedByPayerAnd("initialAdmin")
                                        .hasPrecheck(INVALID_ADMIN_KEY)
                                        .batchKey(BATCH_OPERATOR))
                                .payingWith(BATCH_OPERATOR)
                                .hasPrecheck(INVALID_ADMIN_KEY),
                        tokenUpdate(mutableForNow).properlyEmptyingAdminKey().signedByPayerAnd("initialAdmin"))
                .then(
                        getTokenInfo(mutableForNow),
                        atomicBatch(tokenUpdate(mutableForNow)
                                        .treasury("neverToBe")
                                        .signedBy(GENESIS, "initialAdmin", "neverToBe")
                                        .hasKnownStatus(TOKEN_IS_IMMUTABLE)
                                        .batchKey(BATCH_OPERATOR))
                                .payingWith(BATCH_OPERATOR)
                                .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    @HapiTest
    final Stream<DynamicTest> standardImmutabilitySemanticsHold() {
        long then = Instant.now().getEpochSecond() + 1_234_567L;
        final var immutable = "immutable";
        return defaultHapiSpec("StandardImmutabilitySemanticsHold")
                .given(tokenCreate(immutable).expiry(then))
                .when(
                        atomicBatch(tokenUpdate(immutable)
                                        .treasury(ADDRESS_BOOK_CONTROL)
                                        .hasKnownStatus(TOKEN_IS_IMMUTABLE)
                                        .batchKey(BATCH_OPERATOR))
                                .payingWith(BATCH_OPERATOR)
                                .hasKnownStatus(INNER_TRANSACTION_FAILED),
                        atomicBatch(tokenUpdate(immutable)
                                        .expiry(then - 1)
                                        .hasKnownStatus(EXPIRATION_REDUCTION_NOT_ALLOWED)
                                        .batchKey(BATCH_OPERATOR))
                                .payingWith(BATCH_OPERATOR)
                                .hasKnownStatus(INNER_TRANSACTION_FAILED))
                .then();
    }

    @HapiTest
    final Stream<DynamicTest> validatesMissingRef() {
        return defaultHapiSpec("ValidatesMissingRef")
                .given(cryptoCreate(PAYER))
                .when()
                .then(
                        atomicBatch(tokenUpdate("0.0.0")
                                        .fee(ONE_HBAR)
                                        .payingWith(PAYER)
                                        .signedBy(PAYER)
                                        .hasKnownStatus(INVALID_TOKEN_ID)
                                        .batchKey(BATCH_OPERATOR))
                                .payingWith(BATCH_OPERATOR)
                                .hasKnownStatus(INNER_TRANSACTION_FAILED),
                        atomicBatch(tokenUpdate("1.2.3")
                                        .fee(ONE_HBAR)
                                        .payingWith(PAYER)
                                        .signedBy(PAYER)
                                        .hasKnownStatus(INVALID_TOKEN_ID)
                                        .batchKey(BATCH_OPERATOR))
                                .payingWith(BATCH_OPERATOR)
                                .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    @HapiTest
    final Stream<DynamicTest> validatesMissingAdminKey() {
        return defaultHapiSpec("ValidatesMissingAdminKey")
                .given(
                        cryptoCreate(TOKEN_TREASURY).balance(0L),
                        cryptoCreate(PAYER),
                        tokenCreate("tbd").treasury(TOKEN_TREASURY))
                .when()
                .then(atomicBatch(tokenUpdate("tbd")
                                .autoRenewAccount(GENESIS)
                                .payingWith(PAYER)
                                .signedBy(PAYER, GENESIS)
                                .hasKnownStatus(TOKEN_IS_IMMUTABLE)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    @HapiTest
    final Stream<DynamicTest> newTreasuryAutoAssociationWorks() {
        return defaultHapiSpec("NewTreasuryAutoAssociationWorks")
                .given(
                        newKeyNamed("adminKey"),
                        cryptoCreate("oldTreasury").balance(0L),
                        tokenCreate("tbu").adminKey("adminKey").treasury("oldTreasury"))
                .when(
                        cryptoCreate("newTreasuryWithoutRemainingAutoAssociations")
                                .balance(0L),
                        cryptoCreate("newTreasuryWithRemainingAutoAssociations")
                                .balance(0L)
                                .maxAutomaticTokenAssociations(10))
                .then(
                        atomicBatch(tokenUpdate("tbu")
                                        .treasury("newTreasuryWithoutRemainingAutoAssociations")
                                        .signedByPayerAnd("adminKey", "newTreasuryWithoutRemainingAutoAssociations")
                                        .hasKnownStatus(NO_REMAINING_AUTOMATIC_ASSOCIATIONS)
                                        .batchKey(BATCH_OPERATOR))
                                .payingWith(BATCH_OPERATOR)
                                .hasKnownStatus(INNER_TRANSACTION_FAILED),
                        atomicBatch(tokenUpdate("tbu")
                                        .treasury("newTreasuryWithRemainingAutoAssociations")
                                        .signedByPayerAnd("adminKey", "newTreasuryWithRemainingAutoAssociations")
                                        .batchKey(BATCH_OPERATOR))
                                .payingWith(BATCH_OPERATOR),
                        getTokenInfo("tbu").hasTreasury("newTreasuryWithRemainingAutoAssociations"));
    }

    @HapiTest
    final Stream<DynamicTest> newTreasuryMustSign() {
        return defaultHapiSpec("NewTreasuryMustSign")
                .given(
                        newKeyNamed("adminKey"),
                        cryptoCreate("oldTreasury").balance(0L),
                        cryptoCreate("newTreasury").balance(0L),
                        tokenCreate("tbu").adminKey("adminKey").treasury("oldTreasury"))
                .when(
                        tokenAssociate("newTreasury", "tbu"),
                        cryptoTransfer(moving(1, "tbu").between("oldTreasury", "newTreasury")))
                .then(atomicBatch(tokenUpdate("tbu")
                                .treasury("newTreasury")
                                .signedBy(GENESIS, "adminKey")
                                .hasKnownStatus(INVALID_SIGNATURE)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    @HapiTest
    final Stream<DynamicTest> validAutoRenewWorks() {
        final var secondPeriod = THREE_MONTHS_IN_SECONDS + 1234;
        return defaultHapiSpec("validAutoRenewWorks")
                .given(
                        cryptoCreate("autoRenew").balance(0L),
                        cryptoCreate("newAutoRenew").balance(0L),
                        newKeyNamed("adminKey"))
                .when(
                        tokenCreate("tbu")
                                .adminKey("adminKey")
                                .autoRenewAccount("autoRenew")
                                .autoRenewPeriod(THREE_MONTHS_IN_SECONDS),
                        atomicBatch(tokenUpdate("tbu")
                                        .signedBy(GENESIS, "adminKey")
                                        .autoRenewAccount("newAutoRenew")
                                        .autoRenewPeriod(secondPeriod)
                                        .hasKnownStatus(INVALID_SIGNATURE)
                                        .batchKey(BATCH_OPERATOR))
                                .payingWith(BATCH_OPERATOR)
                                .hasKnownStatus(INNER_TRANSACTION_FAILED),
                        atomicBatch(tokenUpdate("tbu")
                                        .autoRenewAccount("newAutoRenew")
                                        .autoRenewPeriod(secondPeriod)
                                        .signedByPayerAnd("adminKey", "newAutoRenew")
                                        .batchKey(BATCH_OPERATOR))
                                .payingWith(BATCH_OPERATOR))
                .then(getTokenInfo("tbu").logged());
    }

    @HapiTest
    final Stream<DynamicTest> tooLongNameCheckHolds() {
        var tooLongName = "ORIGINAL" + TxnUtils.randomUppercase(MAX_NAME_LENGTH + 1);

        return defaultHapiSpec("TooLongNameCheckHolds")
                .given(newKeyNamed("adminKey"), cryptoCreate(TOKEN_TREASURY).balance(0L))
                .when(tokenCreate("tbu").adminKey("adminKey").treasury(TOKEN_TREASURY))
                .then(atomicBatch(tokenUpdate("tbu")
                                .name(tooLongName)
                                .signedByPayerAnd("adminKey")
                                .hasKnownStatus(TOKEN_NAME_TOO_LONG)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    @HapiTest
    final Stream<DynamicTest> tooLongSymbolCheckHolds() {
        var tooLongSymbol = TxnUtils.randomUppercase(MAX_SYMBOL_LENGTH + 1);

        return defaultHapiSpec("TooLongSymbolCheckHolds")
                .given(newKeyNamed("adminKey"), cryptoCreate(TOKEN_TREASURY).balance(0L))
                .when(tokenCreate("tbu").adminKey("adminKey").treasury(TOKEN_TREASURY))
                .then(atomicBatch(tokenUpdate("tbu")
                                .symbol(tooLongSymbol)
                                .signedByPayerAnd("adminKey")
                                .hasKnownStatus(TOKEN_SYMBOL_TOO_LONG)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    @HapiTest
    final Stream<DynamicTest> deletedAutoRenewAccountCheckHolds() {
        return defaultHapiSpec("DeletedAutoRenewAccountCheckHolds")
                .given(
                        newKeyNamed("adminKey"),
                        cryptoCreate("autoRenewAccount").balance(0L),
                        cryptoCreate(TOKEN_TREASURY).balance(0L))
                .when(
                        cryptoDelete("autoRenewAccount"),
                        tokenCreate("tbu").adminKey("adminKey").treasury(TOKEN_TREASURY))
                .then(atomicBatch(tokenUpdate("tbu")
                                .autoRenewAccount("autoRenewAccount")
                                .signedByPayerAnd("adminKey", "autoRenewAccount")
                                .hasKnownStatus(INVALID_AUTORENEW_ACCOUNT)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    @HapiTest
    final Stream<DynamicTest> invalidTreasuryCheckHolds() {
        return defaultHapiSpec("InvalidTreasuryCheckHolds")
                .given(
                        newKeyNamed("adminKey"),
                        cryptoCreate(TOKEN_TREASURY).balance(0L),
                        cryptoCreate(INVALID_TREASURY).balance(0L))
                .when(
                        cryptoDelete(INVALID_TREASURY),
                        tokenCreate("tbu").adminKey("adminKey").treasury(TOKEN_TREASURY))
                .then(atomicBatch(tokenUpdate("tbu")
                                .treasury(INVALID_TREASURY)
                                .signedByPayerAnd("adminKey", INVALID_TREASURY)
                                .hasKnownStatus(ACCOUNT_DELETED)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    @HapiTest
    final Stream<DynamicTest> updateTokenTreasuryRequiresZeroTokenBalance() {
        return defaultHapiSpec("updateTokenTreasuryRequiresZeroTokenBalance")
                .given(
                        cryptoCreate("oldTreasury"),
                        cryptoCreate("newTreasury"),
                        newKeyNamed("adminKey"),
                        newKeyNamed("supplyKey"),
                        tokenCreate("non-fungible")
                                .tokenType(NON_FUNGIBLE_UNIQUE)
                                .initialSupply(0)
                                .adminKey("adminKey")
                                .supplyKey("supplyKey")
                                .treasury("oldTreasury"))
                .when(
                        mintToken(
                                "non-fungible",
                                List.of(ByteString.copyFromUtf8("memo"), ByteString.copyFromUtf8("memo1"))),
                        tokenAssociate("newTreasury", "non-fungible"),
                        cryptoTransfer(movingUnique("non-fungible", 1).between("oldTreasury", "newTreasury")))
                .then(atomicBatch(tokenUpdate("non-fungible")
                                .treasury("newTreasury")
                                .signedByPayerAnd("adminKey", "newTreasury")
                                .hasKnownStatus(TRANSACTION_REQUIRES_ZERO_TOKEN_BALANCES)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    @HapiTest
    final Stream<DynamicTest> tokenUpdateTokenHasNoFreezeKey() {
        final var tokenName = "token";
        final var HBAR_COLLECTOR = "hbarFee";

        final var admin = "admin";
        final var adminKey = "adminKey";
        final var freezeKey = "freezeKey";
        final var freeze = "freeze";
        final var freezeKey2 = "freezeKey2";

        return defaultHapiSpec("tokenUpdateTokenHasNoFreezeKey")
                .given(
                        newKeyNamed(adminKey),
                        newKeyNamed(freezeKey),
                        newKeyNamed(freezeKey2),
                        cryptoCreate(HBAR_COLLECTOR),
                        cryptoCreate(TOKEN_TREASURY),
                        cryptoCreate(admin).key(adminKey).balance(ONE_MILLION_HBARS),
                        cryptoCreate(freeze).key(freezeKey).balance(ONE_MILLION_HBARS),
                        tokenCreate(tokenName)
                                .treasury(TOKEN_TREASURY)
                                .initialSupply(10)
                                .adminKey(adminKey)
                                .payingWith(admin))
                .when(atomicBatch(tokenUpdate(tokenName)
                                .freezeKey(freezeKey2)
                                .signedBy(adminKey, freezeKey)
                                .payingWith(freeze)
                                .hasKnownStatus(TOKEN_HAS_NO_FREEZE_KEY)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED))
                .then();
    }

    @HapiTest
    final Stream<DynamicTest> tokenUpdateTokenHasNoSupplyKey() {
        final var tokenName = "token";
        final var HBAR_COLLECTOR = "hbarFee";

        final var admin = "admin";
        final var adminKey = "adminKey";
        final var supplyKey = "supplyKey";
        final var supply = "supply";
        final var supplyKey2 = "supplyKey2";

        return defaultHapiSpec("tokenUpdateTokenHasNoSupplyKey")
                .given(
                        newKeyNamed(adminKey),
                        newKeyNamed(supplyKey),
                        newKeyNamed(supplyKey2),
                        cryptoCreate(HBAR_COLLECTOR),
                        cryptoCreate(TOKEN_TREASURY),
                        cryptoCreate(admin).key(adminKey).balance(ONE_MILLION_HBARS),
                        cryptoCreate(supply).key(supplyKey).balance(ONE_MILLION_HBARS),
                        tokenCreate(tokenName)
                                .treasury(TOKEN_TREASURY)
                                .initialSupply(10)
                                .adminKey(adminKey)
                                .payingWith(admin))
                .when(atomicBatch(tokenUpdate(tokenName)
                                .supplyKey(supplyKey2)
                                .signedBy(adminKey, supplyKey)
                                .payingWith(supply)
                                .hasKnownStatus(TOKEN_HAS_NO_SUPPLY_KEY)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED))
                .then();
    }

    @HapiTest
    final Stream<DynamicTest> tokenUpdateTokenHasNoKycKey() {
        final var tokenName = "token";
        final var HBAR_COLLECTOR = "hbarFee";

        final var admin = "admin";
        final var adminKey = "adminKey";
        final var kycKey = "kycKey";
        final var kyc = "kyc";
        final var kycKey2 = "kycKey2";

        return defaultHapiSpec("tokenUpdateTokenHasNoKycKey")
                .given(
                        newKeyNamed(adminKey),
                        newKeyNamed(kycKey),
                        newKeyNamed(kycKey2),
                        cryptoCreate(HBAR_COLLECTOR),
                        cryptoCreate(TOKEN_TREASURY),
                        cryptoCreate(admin).key(adminKey).balance(ONE_MILLION_HBARS),
                        cryptoCreate(kyc).key(kycKey).balance(ONE_MILLION_HBARS),
                        tokenCreate(tokenName)
                                .treasury(TOKEN_TREASURY)
                                .initialSupply(10)
                                .adminKey(adminKey)
                                .payingWith(admin))
                .when(atomicBatch(tokenUpdate(tokenName)
                                .kycKey(kycKey2)
                                .signedBy(adminKey, kycKey)
                                .payingWith(kyc)
                                .hasKnownStatus(TOKEN_HAS_NO_KYC_KEY)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED))
                .then();
    }

    @HapiTest
    final Stream<DynamicTest> tokenUpdateTokenHasNoWipeKey() {
        final var tokenName = "token";
        final var HBAR_COLLECTOR = "hbarFee";

        final var admin = "admin";
        final var adminKey = "adminKey";
        final var wipeKey = "wipeKey";
        final var wipe = "wipe";
        final var wipeKey2 = "wipeKey2";

        return defaultHapiSpec("tokenUpdateTokenHasNoWipeKey")
                .given(
                        newKeyNamed(adminKey),
                        newKeyNamed(wipeKey),
                        newKeyNamed(wipeKey2),
                        cryptoCreate(HBAR_COLLECTOR),
                        cryptoCreate(TOKEN_TREASURY),
                        cryptoCreate(admin).key(adminKey).balance(ONE_MILLION_HBARS),
                        cryptoCreate(wipe).key(wipeKey).balance(ONE_MILLION_HBARS),
                        tokenCreate(tokenName)
                                .treasury(TOKEN_TREASURY)
                                .initialSupply(10)
                                .adminKey(adminKey)
                                .payingWith(admin))
                .when(atomicBatch(tokenUpdate(tokenName)
                                .wipeKey(wipeKey2)
                                .signedBy(adminKey, wipeKey)
                                .payingWith(wipe)
                                .hasKnownStatus(TOKEN_HAS_NO_WIPE_KEY)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED))
                .then();
    }

    @HapiTest
    final Stream<DynamicTest> tokenUpdateTokenHasNoPauseKey() {
        final var tokenName = "token";
        final var HBAR_COLLECTOR = "hbarFee";

        final var admin = "admin";
        final var adminKey = "adminKey";
        final var pauseKey = "pauseKey";
        final var pause = "pause";
        final var pauseKey2 = "pauseKey2";

        return defaultHapiSpec("tokenUpdateTokenHasNoPauseKey")
                .given(
                        newKeyNamed(adminKey),
                        newKeyNamed(pauseKey),
                        newKeyNamed(pauseKey2),
                        cryptoCreate(HBAR_COLLECTOR),
                        cryptoCreate(TOKEN_TREASURY),
                        cryptoCreate(admin).key(adminKey).balance(ONE_MILLION_HBARS),
                        cryptoCreate(pause).key(pauseKey).balance(ONE_MILLION_HBARS),
                        tokenCreate(tokenName)
                                .treasury(TOKEN_TREASURY)
                                .initialSupply(10)
                                .adminKey(adminKey)
                                .payingWith(admin))
                .when(atomicBatch(tokenUpdate(tokenName)
                                .pauseKey(pauseKey2)
                                .signedBy(adminKey, pauseKey)
                                .payingWith(pause)
                                .hasKnownStatus(TOKEN_HAS_NO_PAUSE_KEY)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED))
                .then();
    }

    @HapiTest
    final Stream<DynamicTest> tokenUpdateTokenHasNoMetadataKey() {
        final var tokenName = "token";
        final var HBAR_COLLECTOR = "hbarFee";

        final var admin = "admin";
        final var adminKey = "adminKey";
        final var metadataKey = "metadataKey";
        final var metadata = "metadata";
        final var metadataKey2 = "metadataKey2";

        return defaultHapiSpec("tokenUpdateTokenHasNoMetadataKey")
                .given(
                        newKeyNamed(adminKey),
                        newKeyNamed(metadataKey),
                        newKeyNamed(metadataKey2),
                        cryptoCreate(HBAR_COLLECTOR),
                        cryptoCreate(TOKEN_TREASURY),
                        cryptoCreate(admin).key(adminKey).balance(ONE_MILLION_HBARS),
                        cryptoCreate(metadata).key(metadataKey).balance(ONE_MILLION_HBARS),
                        tokenCreate(tokenName)
                                .treasury(TOKEN_TREASURY)
                                .initialSupply(10)
                                .adminKey(adminKey)
                                .payingWith(admin))
                .when(atomicBatch(tokenUpdate(tokenName)
                                .metadataKey(metadataKey2)
                                .signedBy(adminKey, metadataKey)
                                .payingWith(metadata)
                                .hasKnownStatus(TOKEN_HAS_NO_METADATA_KEY)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED))
                .then();
    }

    @HapiTest
    final Stream<DynamicTest> tokenUpdateReduceExpiry() {
        return hapiTest(
                cryptoCreate("adminKey"),
                cryptoCreate("supplyKey"),
                tokenCreate("token")
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0)
                        .adminKey("adminKey")
                        .supplyKey("supplyKey")
                        .expiry(Instant.now().getEpochSecond() + 1000),
                atomicBatch(tokenUpdate("token")
                                .expiry(Instant.now().getEpochSecond() + 500)
                                .hasKnownStatus(EXPIRATION_REDUCTION_NOT_ALLOWED)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    @HapiTest
    final Stream<DynamicTest> withLongMinAutoRenewPeriod() {
        return hapiTest(
                cryptoCreate("adminKey"),
                cryptoCreate("supplyKey"),
                tokenCreate("token")
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0)
                        .adminKey("adminKey")
                        .supplyKey("supplyKey"),
                atomicBatch(tokenUpdate("token")
                                .autoRenewAccount("supplyKey")
                                .autoRenewPeriod(Long.MIN_VALUE)
                                .hasKnownStatus(INVALID_RENEWAL_PERIOD)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    @HapiTest
    final Stream<DynamicTest> withNegativeAutoRenewPeriod() {
        return hapiTest(
                cryptoCreate("adminKey"),
                cryptoCreate("supplyKey"),
                tokenCreate("token")
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0)
                        .adminKey("adminKey")
                        .supplyKey("supplyKey"),
                atomicBatch(tokenUpdate("token")
                                .autoRenewAccount("supplyKey")
                                .autoRenewPeriod(-1)
                                .hasKnownStatus(INVALID_RENEWAL_PERIOD)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    @HapiTest
    final Stream<DynamicTest> withNegativeExpiry() {
        return hapiTest(
                cryptoCreate("adminKey"),
                cryptoCreate("supplyKey"),
                tokenCreate("token")
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0)
                        .adminKey("adminKey")
                        .supplyKey("supplyKey"),
                atomicBatch(tokenUpdate("token")
                                .expiry(-1)
                                .hasKnownStatus(INVALID_EXPIRATION_TIME)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    @HapiTest
    final Stream<DynamicTest> tokenUpdateWithAutoRenewAccountAndNoPeriod() {
        return hapiTest(
                cryptoCreate("adminKey"),
                cryptoCreate("supplyKey"),
                cryptoCreate("autoRenewAccount"),
                tokenCreate("token")
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0)
                        .adminKey("adminKey")
                        .supplyKey("supplyKey"),
                atomicBatch(tokenUpdate("token")
                                .autoRenewAccount("autoRenewAccount")
                                .hasKnownStatus(INVALID_RENEWAL_PERIOD)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }
}
