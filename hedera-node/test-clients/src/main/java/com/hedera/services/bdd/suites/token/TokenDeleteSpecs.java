// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.token;

import static com.hedera.services.bdd.junit.TestTags.TOKEN;
import static com.hedera.services.bdd.spec.HapiSpec.defaultHapiSpec;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTokenInfo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.burnToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.grantTokenKyc;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.revokeTokenKyc;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenDissociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenFreeze;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenUnfreeze;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.submitModified;
import static com.hedera.services.bdd.spec.utilops.mod.ModificationUtils.withSuccessivelyVariedBodyIds;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.TOKEN_TREASURY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_IS_TREASURY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOKEN_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_IS_IMMUTABLE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_WAS_DELETED;

import com.hedera.services.bdd.junit.HapiTest;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(TOKEN)
public class TokenDeleteSpecs {
    private static final String FIRST_TBD = "firstTbd";
    private static final String SECOND_TBD = "secondTbd";
    private static final String TOKEN_ADMIN = "tokenAdmin";
    private static final String PAYER = "payer";
    private static final String MULTI_KEY = "multiKey";

    @HapiTest
    final Stream<DynamicTest> treasuryBecomesDeletableAfterTokenDelete() {
        return defaultHapiSpec("TreasuryBecomesDeletableAfterTokenDelete")
                .given(
                        newKeyNamed(TOKEN_ADMIN),
                        cryptoCreate(TOKEN_TREASURY).balance(0L),
                        tokenCreate(FIRST_TBD).adminKey(TOKEN_ADMIN).treasury(TOKEN_TREASURY),
                        tokenCreate(SECOND_TBD).adminKey(TOKEN_ADMIN).treasury(TOKEN_TREASURY),
                        cryptoDelete(TOKEN_TREASURY).hasKnownStatus(ACCOUNT_IS_TREASURY),
                        tokenDissociate(TOKEN_TREASURY, FIRST_TBD).hasKnownStatus(ACCOUNT_IS_TREASURY))
                .when(
                        tokenDelete(FIRST_TBD),
                        tokenDissociate(TOKEN_TREASURY, FIRST_TBD),
                        cryptoDelete(TOKEN_TREASURY).hasKnownStatus(ACCOUNT_IS_TREASURY),
                        tokenDelete(SECOND_TBD))
                .then(tokenDissociate(TOKEN_TREASURY, SECOND_TBD), cryptoDelete(TOKEN_TREASURY));
    }

    @HapiTest
    final Stream<DynamicTest> deletionValidatesAlreadyDeletedToken() {
        return defaultHapiSpec("DeletionValidatesAlreadyDeletedToken")
                .given(
                        newKeyNamed(MULTI_KEY),
                        cryptoCreate(TOKEN_TREASURY).balance(0L),
                        tokenCreate("tbd").adminKey(MULTI_KEY).treasury(TOKEN_TREASURY),
                        tokenDelete("tbd"))
                .when()
                .then(tokenDelete("tbd").hasKnownStatus(TOKEN_WAS_DELETED));
    }

    @HapiTest
    final Stream<DynamicTest> idVariantsTreatedAsExpected() {
        return defaultHapiSpec("idVariantsTreatedAsExpected")
                .given(newKeyNamed("adminKey"), tokenCreate("t").adminKey("adminKey"))
                .when()
                .then(submitModified(withSuccessivelyVariedBodyIds(), () -> tokenDelete("t")));
    }

    @HapiTest
    final Stream<DynamicTest> deletionValidatesMissingAdminKey() {
        return defaultHapiSpec("DeletionValidatesMissingAdminKey")
                .given(
                        newKeyNamed(MULTI_KEY),
                        cryptoCreate(TOKEN_TREASURY).balance(0L),
                        cryptoCreate(PAYER),
                        tokenCreate("tbd")
                                .freezeDefault(false)
                                .treasury(TOKEN_TREASURY)
                                .payingWith(PAYER))
                .when()
                .then(tokenDelete("tbd").payingWith(PAYER).signedBy(PAYER).hasKnownStatus(TOKEN_IS_IMMUTABLE));
    }

    @HapiTest
    final Stream<DynamicTest> deletionValidatesWrongAdminKey() {
        return defaultHapiSpec("DeletionValidatesWrongAdminKey")
                .given(
                        newKeyNamed(MULTI_KEY),
                        cryptoCreate(TOKEN_TREASURY).balance(0L),
                        cryptoCreate(PAYER),
                        tokenCreate("tbd")
                                .adminKey(MULTI_KEY)
                                .freezeDefault(false)
                                .treasury(TOKEN_TREASURY)
                                .payingWith(PAYER))
                .when()
                .then(tokenDelete("tbd").payingWith(PAYER).signedBy(PAYER).hasKnownStatus(INVALID_SIGNATURE));
    }

    @HapiTest
    final Stream<DynamicTest> deletionWorksAsExpected() {
        return hapiTest(
                newKeyNamed(MULTI_KEY),
                cryptoCreate(TOKEN_TREASURY).balance(0L),
                cryptoCreate(PAYER),
                tokenCreate("tbd")
                        .adminKey(MULTI_KEY)
                        .freezeKey(MULTI_KEY)
                        .kycKey(MULTI_KEY)
                        .wipeKey(MULTI_KEY)
                        .supplyKey(MULTI_KEY)
                        .freezeDefault(false)
                        .treasury(TOKEN_TREASURY)
                        .payingWith(PAYER),
                tokenAssociate(GENESIS, "tbd"),
                getAccountInfo(TOKEN_TREASURY).logged(),
                mintToken("tbd", 1),
                burnToken("tbd", 1),
                revokeTokenKyc("tbd", GENESIS),
                grantTokenKyc("tbd", GENESIS),
                tokenFreeze("tbd", GENESIS),
                tokenUnfreeze("tbd", GENESIS),
                cryptoTransfer(moving(1, "tbd").between(TOKEN_TREASURY, GENESIS)),
                tokenDelete("tbd").payingWith(PAYER),
                getTokenInfo("tbd").logged(),
                getAccountInfo(TOKEN_TREASURY).logged(),
                cryptoTransfer(moving(1, "tbd").between(TOKEN_TREASURY, GENESIS))
                        .hasKnownStatus(TOKEN_WAS_DELETED),
                mintToken("tbd", 1).hasKnownStatus(TOKEN_WAS_DELETED),
                burnToken("tbd", 1).hasKnownStatus(TOKEN_WAS_DELETED),
                revokeTokenKyc("tbd", GENESIS).hasKnownStatus(TOKEN_WAS_DELETED),
                grantTokenKyc("tbd", GENESIS).hasKnownStatus(TOKEN_WAS_DELETED),
                tokenFreeze("tbd", GENESIS).hasKnownStatus(TOKEN_WAS_DELETED),
                tokenUnfreeze("tbd", GENESIS).hasKnownStatus(TOKEN_WAS_DELETED));
    }

    @HapiTest
    final Stream<DynamicTest> deletionValidatesRef() {
        return defaultHapiSpec("DeletionValidatesRef")
                .given(cryptoCreate(PAYER))
                .when()
                .then(
                        tokenDelete("0.0.0").payingWith(PAYER).signedBy(PAYER).hasKnownStatus(INVALID_TOKEN_ID),
                        tokenDelete("1.2.3").payingWith(PAYER).signedBy(PAYER).hasKnownStatus(INVALID_TOKEN_ID));
    }
}
