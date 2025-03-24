// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.crypto;

import static com.hedera.services.bdd.junit.TestTags.CRYPTO;
import static com.hedera.services.bdd.spec.HapiPropertySource.asEntityString;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.accountWith;
import static com.hedera.services.bdd.spec.keys.KeyShape.SIMPLE;
import static com.hedera.services.bdd.spec.keys.KeyShape.listOf;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.grantTokenKyc;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingUnique;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.suites.HapiSuite.CIVILIAN_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.TOKEN_TREASURY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_DELETED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_PAYER_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_TX_FEE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TRANSACTION_BODY;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.spec.keys.KeyShape;
import com.hederahashgraph.api.proto.java.TokenSupplyType;
import com.hederahashgraph.api.proto.java.TokenType;
import java.util.List;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(CRYPTO)
public class CryptoGetInfoRegression {
    static final Logger log = LogManager.getLogger(CryptoGetInfoRegression.class);

    /** For Demo purpose : The limit on each account info and account balance queries is set to 5 */
    @LeakyHapiTest(overrides = {"tokens.maxRelsPerInfoQuery"})
    final Stream<DynamicTest> fetchesOnlyALimitedTokenAssociations() {
        final var account = "test";
        final var aKey = "tokenKey";
        final var token1 = "token1";
        final var token2 = "token2";
        final var token3 = "token3";
        final var token4 = "token4";
        final var token5 = "token5";
        final var token6 = "token6";
        final var token7 = "token7";
        final var token8 = "token8";
        return hapiTest(
                overriding("tokens.maxRelsPerInfoQuery", "" + 1),
                newKeyNamed(aKey),
                cryptoCreate(account).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(TOKEN_TREASURY).balance(ONE_HUNDRED_HBARS),
                tokenCreate(token1)
                        .supplyType(TokenSupplyType.FINITE)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .treasury(TOKEN_TREASURY)
                        .maxSupply(500)
                        .kycKey(aKey)
                        .initialSupply(100),
                tokenCreate(token2)
                        .supplyType(TokenSupplyType.FINITE)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .treasury(TOKEN_TREASURY)
                        .maxSupply(500)
                        .kycKey(aKey)
                        .initialSupply(100),
                tokenCreate(token3)
                        .supplyType(TokenSupplyType.FINITE)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .treasury(TOKEN_TREASURY)
                        .maxSupply(500)
                        .kycKey(aKey)
                        .initialSupply(100),
                tokenCreate(token4)
                        .supplyType(TokenSupplyType.FINITE)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .treasury(TOKEN_TREASURY)
                        .maxSupply(500)
                        .kycKey(aKey)
                        .initialSupply(100),
                tokenCreate(token5)
                        .supplyType(TokenSupplyType.FINITE)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .treasury(TOKEN_TREASURY)
                        .maxSupply(500)
                        .kycKey(aKey)
                        .initialSupply(100),
                tokenCreate(token6)
                        .supplyType(TokenSupplyType.FINITE)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .treasury(TOKEN_TREASURY)
                        .maxSupply(500)
                        .kycKey(aKey)
                        .initialSupply(100),
                tokenCreate(token7)
                        .supplyType(TokenSupplyType.FINITE)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .treasury(TOKEN_TREASURY)
                        .maxSupply(10L)
                        .initialSupply(0L)
                        .kycKey(aKey)
                        .supplyKey(aKey),
                tokenCreate(token8)
                        .supplyType(TokenSupplyType.FINITE)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .treasury(TOKEN_TREASURY)
                        .maxSupply(10L)
                        .initialSupply(0L)
                        .kycKey(aKey)
                        .supplyKey(aKey),
                mintToken(token7, List.of(ByteString.copyFromUtf8("a"), ByteString.copyFromUtf8("b"))),
                mintToken(token8, List.of(ByteString.copyFromUtf8("a"))),
                tokenAssociate(account, token1, token2, token3, token4, token5, token6, token7, token8),
                grantTokenKyc(token1, account),
                grantTokenKyc(token2, account),
                grantTokenKyc(token3, account),
                grantTokenKyc(token4, account),
                grantTokenKyc(token5, account),
                grantTokenKyc(token6, account),
                grantTokenKyc(token7, account),
                grantTokenKyc(token8, account),
                cryptoTransfer(
                        moving(10L, token1).between(TOKEN_TREASURY, account),
                        moving(20L, token2).between(TOKEN_TREASURY, account),
                        moving(30L, token3).between(TOKEN_TREASURY, account)),
                cryptoTransfer(
                        moving(40L, token4).between(TOKEN_TREASURY, account),
                        moving(50L, token5).between(TOKEN_TREASURY, account),
                        moving(60L, token6).between(TOKEN_TREASURY, account)),
                cryptoTransfer(
                        movingUnique(token7, 1, 2).between(TOKEN_TREASURY, account),
                        movingUnique(token8, 1).between(TOKEN_TREASURY, account)),
                overriding("tokens.maxRelsPerInfoQuery", "3"),
                getAccountInfo(account).hasTokenRelationShipCount(3));
    }

    @HapiTest
    final Stream<DynamicTest> succeedsNormally() {
        long balance = 1_234_567L;
        KeyShape misc = listOf(SIMPLE, listOf(2));

        return hapiTest(
                newKeyNamed("misc").shape(misc),
                cryptoCreate("noStakingTarget").key("misc").balance(balance),
                cryptoCreate("target").key("misc").balance(balance).stakedNodeId(0L),
                cryptoCreate("targetWithStakedAccountId")
                        .key("misc")
                        .balance(balance)
                        .stakedAccountId(asEntityString(20)),
                getAccountInfo("noStakingTarget")
                        .has(accountWith()
                                .accountId("noStakingTarget")
                                .stakedNodeId(0L) // this was -1l and failed on mono code too, changed to 0L, success
                                // in both mono and module code
                                .noStakedAccountId()
                                .key("misc")
                                .balance(balance))
                        .logged(),
                getAccountInfo("target")
                        .has(accountWith()
                                .accountId("target")
                                .noStakingNodeId()
                                .key("misc")
                                .balance(balance))
                        .logged(),
                getAccountInfo("targetWithStakedAccountId")
                        .has(accountWith()
                                .accountId("targetWithStakedAccountId")
                                .stakedAccountId(asEntityString(20))
                                .key("misc")
                                .balance(balance))
                        .logged());
    }

    @HapiTest
    final Stream<DynamicTest> failsForMissingAccount() {
        return hapiTest(getAccountInfo("5.5.3").hasCostAnswerPrecheck(INVALID_ACCOUNT_ID));
    }

    @HapiTest
    final Stream<DynamicTest> failsForMalformedPayment() {
        return hapiTest(
                newKeyNamed("wrong").shape(SIMPLE),
                getAccountInfo(GENESIS).signedBy("wrong").hasAnswerOnlyPrecheck(INVALID_SIGNATURE));
    }

    @HapiTest
    final Stream<DynamicTest> failsForUnfundablePayment() {
        long everything = 1_234L;
        return hapiTest(
                cryptoCreate("brokePayer").balance(everything),
                getAccountInfo(GENESIS)
                        .payingWith("brokePayer")
                        .nodePayment(everything)
                        .hasAnswerOnlyPrecheck(INSUFFICIENT_PAYER_BALANCE));
    }

    @HapiTest
    final Stream<DynamicTest> failsForInsufficientPayment() {
        return hapiTest(
                cryptoCreate(CIVILIAN_PAYER),
                getAccountInfo(GENESIS)
                        .payingWith(CIVILIAN_PAYER)
                        .nodePayment(1L)
                        .hasAnswerOnlyPrecheck(INSUFFICIENT_TX_FEE));
    }

    @HapiTest // this test needs to be updated for both mono and module code.
    final Stream<DynamicTest> failsForMissingPayment() {
        return hapiTest(
                getAccountInfo(GENESIS).useEmptyTxnAsAnswerPayment().hasAnswerOnlyPrecheck(INVALID_TRANSACTION_BODY));
    }

    @HapiTest
    final Stream<DynamicTest> failsForDeletedAccount() {
        return hapiTest(
                cryptoCreate("toBeDeleted"),
                cryptoDelete("toBeDeleted").transfer(GENESIS),
                getAccountInfo("toBeDeleted").hasCostAnswerPrecheck(ACCOUNT_DELETED));
    }
}
