// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.precompile;

import static com.google.protobuf.ByteString.copyFromUtf8;
import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.junit.TestTags.TOKEN;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTokenNftInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenFreeze;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenPause;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenReject;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenUnfreeze;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenUnpause;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedHbarFee;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedHtsFee;
import static com.hedera.services.bdd.spec.transactions.token.HapiTokenReject.rejectingNFT;
import static com.hedera.services.bdd.spec.transactions.token.HapiTokenReject.rejectingToken;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingUnique;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_AMOUNT_TRANSFERS_ONLY_ALLOWED_FOR_FUNGIBLE_COMMON;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_FROZEN_FOR_TOKEN;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_IS_TREASURY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.EMPTY_TOKEN_REFERENCE_LIST;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_PAYER_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_TOKEN_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_NFT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_OWNER_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_IS_PAUSED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_NOT_ASSOCIATED_TO_ACCOUNT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_REFERENCE_REPEATED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_WAS_DELETED;

import com.hedera.services.bdd.junit.HapiTest;
import com.hederahashgraph.api.proto.java.TokenType;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(TOKEN)
@SuppressWarnings("java:S1192") // "string literal should not be duplicated" - this rule makes test suites worse
public class TokenRejectSuite {

    private static final long TOTAL_SUPPLY = 1_000;

    private static final String TOKEN_TREASURY = "treasury";
    private static final String ALT_TOKEN_TREASURY = "altTreasury";
    private static final String ACCOUNT = "anybody";
    private static final String ACCOUNT_1 = "anybody1";
    private static final String SPENDER = "spender";
    private static final String FEE_COLLECTOR = "spender";

    private static final String FUNGIBLE_TOKEN_A = "fungibleTokenA";
    private static final String FUNGIBLE_TOKEN_B = "fungibleTokenB";

    private static final String NON_FUNGIBLE_TOKEN_A = "nonFungibleTokenA";
    private static final String NON_FUNGIBLE_TOKEN_B = "nonFungibleTokenB";

    private static final String MULTI_KEY = "multiKey";

    @HapiTest
    @Tag(MATS)
    final Stream<DynamicTest> tokenRejectWorksAndAvoidsCustomFees() {
        return hapiTest(
                newKeyNamed(MULTI_KEY),
                cryptoCreate(FEE_COLLECTOR).balance(0L).maxAutomaticTokenAssociations(5),
                cryptoCreate(ACCOUNT).maxAutomaticTokenAssociations(5),
                cryptoCreate(ACCOUNT_1).balance(0L).maxAutomaticTokenAssociations(1),
                cryptoCreate(TOKEN_TREASURY).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(ALT_TOKEN_TREASURY).balance(ONE_HUNDRED_HBARS),
                tokenCreate(FUNGIBLE_TOKEN_A)
                        .initialSupply(TOTAL_SUPPLY)
                        .adminKey(MULTI_KEY)
                        .supplyKey(MULTI_KEY)
                        .withCustom(fixedHbarFee(ONE_MILLION_HBARS, FEE_COLLECTOR))
                        .treasury(TOKEN_TREASURY),
                tokenAssociate(FEE_COLLECTOR, FUNGIBLE_TOKEN_A),
                tokenCreate(FUNGIBLE_TOKEN_B)
                        .initialSupply(TOTAL_SUPPLY)
                        .adminKey(MULTI_KEY)
                        .supplyKey(MULTI_KEY)
                        .withCustom(fixedHtsFee(1000L, FUNGIBLE_TOKEN_A, FEE_COLLECTOR))
                        .treasury(TOKEN_TREASURY),
                tokenCreate(NON_FUNGIBLE_TOKEN_A)
                        .initialSupply(0)
                        .adminKey(MULTI_KEY)
                        .supplyKey(MULTI_KEY)
                        .withCustom(fixedHbarFee(ONE_MILLION_HBARS, FEE_COLLECTOR))
                        .treasury(ALT_TOKEN_TREASURY)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE),
                mintToken(NON_FUNGIBLE_TOKEN_A, List.of(copyFromUtf8("fire"), copyFromUtf8("goat"))),
                tokenAssociate(ACCOUNT, FUNGIBLE_TOKEN_A, FUNGIBLE_TOKEN_B, NON_FUNGIBLE_TOKEN_A),
                cryptoTransfer(
                        moving(250L, FUNGIBLE_TOKEN_A).between(TOKEN_TREASURY, ACCOUNT),
                        moving(10L, FUNGIBLE_TOKEN_A).between(TOKEN_TREASURY, ACCOUNT_1),
                        moving(250L, FUNGIBLE_TOKEN_B).between(TOKEN_TREASURY, ACCOUNT),
                        movingUnique(NON_FUNGIBLE_TOKEN_A, 1L).between(ALT_TOKEN_TREASURY, ACCOUNT)),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        // Try rejecting NON_FUNGIBLE_TOKEN_A with FIXED hBar custom fee
                        tokenReject(ACCOUNT, rejectingNFT(NON_FUNGIBLE_TOKEN_A, 1L)),
                        // Try rejecting FUNGIBLE_TOKEN_A with FIXED hBar custom fee
                        tokenReject(rejectingToken(FUNGIBLE_TOKEN_A))
                                .payingWith(ACCOUNT)
                                .signedBy(ACCOUNT),
                        // Try rejecting FUNGIBLE_TOKEN_B with FIXED hts custom fee
                        tokenReject(ACCOUNT, rejectingToken(FUNGIBLE_TOKEN_B)),
                        // Transaction fails because payer does not have hBars
                        tokenReject(ACCOUNT_1, rejectingToken(FUNGIBLE_TOKEN_B))
                                .payingWith(ACCOUNT_1)
                                .hasPrecheck(INSUFFICIENT_PAYER_BALANCE))),
                getTokenNftInfo(NON_FUNGIBLE_TOKEN_A, 1L)
                        .hasAccountID(ALT_TOKEN_TREASURY)
                        .hasNoSpender(),
                getAccountBalance(TOKEN_TREASURY).logged().hasTokenBalance(FUNGIBLE_TOKEN_A, 990L),
                getAccountBalance(TOKEN_TREASURY).logged().hasTokenBalance(FUNGIBLE_TOKEN_B, 1000L),
                // Verify that fee collector account has no tokens and no hBars
                getAccountBalance(FEE_COLLECTOR)
                        .hasTinyBars(0)
                        .hasTokenBalance(FUNGIBLE_TOKEN_A, 0L)
                        .hasTokenBalance(FUNGIBLE_TOKEN_B, 0L));
    }

    @HapiTest
    final Stream<DynamicTest> tokenRejectWorksWithFungibleAndNFTTokens() {
        return hapiTest(
                newKeyNamed(MULTI_KEY),
                cryptoCreate(ACCOUNT).maxAutomaticTokenAssociations(2),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(FUNGIBLE_TOKEN_A)
                        .initialSupply(TOTAL_SUPPLY)
                        .adminKey(MULTI_KEY)
                        .supplyKey(MULTI_KEY)
                        .treasury(TOKEN_TREASURY),
                tokenCreate(NON_FUNGIBLE_TOKEN_A)
                        .initialSupply(0)
                        .adminKey(MULTI_KEY)
                        .supplyKey(MULTI_KEY)
                        .treasury(TOKEN_TREASURY)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE),
                mintToken(
                        NON_FUNGIBLE_TOKEN_A,
                        List.of(copyFromUtf8("souffle"), copyFromUtf8("pie"), copyFromUtf8("apple tart"))),
                cryptoTransfer(
                        moving(200L, FUNGIBLE_TOKEN_A).between(TOKEN_TREASURY, ACCOUNT),
                        movingUnique(NON_FUNGIBLE_TOKEN_A, 1L, 2L, 3L).between(TOKEN_TREASURY, ACCOUNT)),
                // Verify Account's token balances:
                getTokenNftInfo(NON_FUNGIBLE_TOKEN_A, 1L).hasAccountID(ACCOUNT).hasNoSpender(),
                getTokenNftInfo(NON_FUNGIBLE_TOKEN_A, 2L).hasAccountID(ACCOUNT).hasNoSpender(),
                getTokenNftInfo(NON_FUNGIBLE_TOKEN_A, 3L).hasAccountID(ACCOUNT).hasNoSpender(),
                getAccountBalance(ACCOUNT).logged().hasTokenBalance(FUNGIBLE_TOKEN_A, 200L),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        tokenReject(ACCOUNT, rejectingToken(FUNGIBLE_TOKEN_A), rejectingNFT(NON_FUNGIBLE_TOKEN_A, 1L))
                                .via("tokenReject")
                                .logged())),
                getTxnRecord("tokenReject").andAllChildRecords().logged(),
                getTokenNftInfo(NON_FUNGIBLE_TOKEN_A, 1L)
                        .hasAccountID(TOKEN_TREASURY)
                        .hasNoSpender(),
                getTokenNftInfo(NON_FUNGIBLE_TOKEN_A, 2L).hasAccountID(ACCOUNT).hasNoSpender(),
                getTokenNftInfo(NON_FUNGIBLE_TOKEN_A, 3L).hasAccountID(ACCOUNT).hasNoSpender(),
                getAccountBalance(ACCOUNT).logged().hasTokenBalance(FUNGIBLE_TOKEN_A, 0L),
                getAccountBalance(TOKEN_TREASURY).logged().hasTokenBalance(FUNGIBLE_TOKEN_A, 1000L));
    }

    @HapiTest
    @Tag(MATS)
    final Stream<DynamicTest> tokenRejectCasesWhileFreezeOrPausedOrSigRequired() {
        return hapiTest(
                newKeyNamed(MULTI_KEY),
                newKeyNamed("freezeKey"),
                newKeyNamed("pauseKey"),
                cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(TOKEN_TREASURY).receiverSigRequired(true),
                cryptoCreate(ALT_TOKEN_TREASURY).receiverSigRequired(true),
                tokenCreate(FUNGIBLE_TOKEN_A)
                        .initialSupply(TOTAL_SUPPLY)
                        .adminKey(MULTI_KEY)
                        .supplyKey(MULTI_KEY)
                        .pauseKey("pauseKey")
                        .treasury(TOKEN_TREASURY),
                tokenCreate(FUNGIBLE_TOKEN_B)
                        .initialSupply(TOTAL_SUPPLY)
                        .adminKey(MULTI_KEY)
                        .supplyKey(MULTI_KEY)
                        .freezeKey("freezeKey")
                        .treasury(ALT_TOKEN_TREASURY),
                tokenCreate(NON_FUNGIBLE_TOKEN_A)
                        .initialSupply(0)
                        .adminKey(MULTI_KEY)
                        .supplyKey(MULTI_KEY)
                        .pauseKey("pauseKey")
                        .treasury(TOKEN_TREASURY)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE),
                tokenCreate(NON_FUNGIBLE_TOKEN_B)
                        .initialSupply(0)
                        .adminKey(MULTI_KEY)
                        .supplyKey(MULTI_KEY)
                        .freezeKey("freezeKey")
                        .pauseKey("pauseKey")
                        .treasury(TOKEN_TREASURY)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE),
                tokenAssociate(ACCOUNT, FUNGIBLE_TOKEN_A, FUNGIBLE_TOKEN_B, NON_FUNGIBLE_TOKEN_A, NON_FUNGIBLE_TOKEN_B),
                mintToken(NON_FUNGIBLE_TOKEN_A, List.of(copyFromUtf8("apple"), copyFromUtf8("pen"))),
                mintToken(NON_FUNGIBLE_TOKEN_B, List.of(copyFromUtf8("pineapple"), copyFromUtf8("apple-pen"))),
                cryptoTransfer(
                        moving(250L, FUNGIBLE_TOKEN_A).between(TOKEN_TREASURY, ACCOUNT),
                        moving(250L, FUNGIBLE_TOKEN_B).between(ALT_TOKEN_TREASURY, ACCOUNT),
                        movingUnique(NON_FUNGIBLE_TOKEN_A, 1L, 2L).between(TOKEN_TREASURY, ACCOUNT),
                        movingUnique(NON_FUNGIBLE_TOKEN_B, 2L).between(TOKEN_TREASURY, ACCOUNT)),
                // Apply freeze and pause to tokens:
                tokenPause(FUNGIBLE_TOKEN_A),
                tokenFreeze(FUNGIBLE_TOKEN_B, ACCOUNT),
                tokenFreeze(NON_FUNGIBLE_TOKEN_B, ACCOUNT),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        tokenReject(ACCOUNT, rejectingToken(FUNGIBLE_TOKEN_A), rejectingNFT(NON_FUNGIBLE_TOKEN_A, 1L))
                                .hasKnownStatus(TOKEN_IS_PAUSED)
                                .via("tokenRejectFungibleFailsWithPaused"),
                        tokenReject(ACCOUNT, rejectingToken(FUNGIBLE_TOKEN_B))
                                .hasKnownStatus(ACCOUNT_FROZEN_FOR_TOKEN)
                                .via("tokenRejectFungibleFailsWithFreeze"),
                        tokenReject(
                                        ACCOUNT,
                                        rejectingNFT(NON_FUNGIBLE_TOKEN_A, 1L),
                                        rejectingNFT(NON_FUNGIBLE_TOKEN_B, 2L))
                                .hasKnownStatus(ACCOUNT_FROZEN_FOR_TOKEN)
                                .via("tokenRejectNFTFailsWithFreeze"),
                        tokenPause(NON_FUNGIBLE_TOKEN_A),
                        tokenReject(ACCOUNT, rejectingNFT(NON_FUNGIBLE_TOKEN_A, 1L))
                                .hasKnownStatus(TOKEN_IS_PAUSED)
                                .via("tokenRejectNFTFailsWithPaused"),
                        tokenReject(
                                        ACCOUNT,
                                        rejectingToken(FUNGIBLE_TOKEN_A),
                                        rejectingToken(FUNGIBLE_TOKEN_B),
                                        rejectingNFT(NON_FUNGIBLE_TOKEN_A, 1L),
                                        rejectingNFT(NON_FUNGIBLE_TOKEN_B, 2L))
                                .hasKnownStatus(TOKEN_IS_PAUSED)
                                .via("tokenRejectFailsWithPausedAndFreeze"),
                        tokenUnpause(FUNGIBLE_TOKEN_A),
                        tokenUnfreeze(FUNGIBLE_TOKEN_B, ACCOUNT),
                        tokenUnpause(NON_FUNGIBLE_TOKEN_A),
                        tokenUnfreeze(NON_FUNGIBLE_TOKEN_B, ACCOUNT),
                        tokenReject(
                                        ACCOUNT,
                                        rejectingToken(FUNGIBLE_TOKEN_A),
                                        rejectingToken(FUNGIBLE_TOKEN_B),
                                        rejectingNFT(NON_FUNGIBLE_TOKEN_A, 1L),
                                        rejectingNFT(NON_FUNGIBLE_TOKEN_B, 2L))
                                .via("tokenRejectWorksWithSigRequired"))),
                getTxnRecord("tokenRejectFungibleFailsWithPaused")
                        .andAllChildRecords()
                        .logged(),
                getTxnRecord("tokenRejectFungibleFailsWithFreeze")
                        .andAllChildRecords()
                        .logged(),
                getTxnRecord("tokenRejectNFTFailsWithFreeze")
                        .andAllChildRecords()
                        .logged(),
                getTxnRecord("tokenRejectNFTFailsWithPaused")
                        .andAllChildRecords()
                        .logged(),
                getTxnRecord("tokenRejectFailsWithPausedAndFreeze")
                        .andAllChildRecords()
                        .logged(),
                getTxnRecord("tokenRejectWorksWithSigRequired")
                        .andAllChildRecords()
                        .logged(),
                getTokenNftInfo(NON_FUNGIBLE_TOKEN_A, 1L)
                        .hasAccountID(TOKEN_TREASURY)
                        .hasNoSpender(),
                getTokenNftInfo(NON_FUNGIBLE_TOKEN_A, 2L).hasAccountID(ACCOUNT).hasNoSpender(),
                getTokenNftInfo(NON_FUNGIBLE_TOKEN_B, 2L)
                        .hasAccountID(TOKEN_TREASURY)
                        .hasNoSpender(),
                getAccountBalance(ACCOUNT).logged().hasTokenBalance(FUNGIBLE_TOKEN_A, 0L),
                getAccountBalance(ACCOUNT).logged().hasTokenBalance(FUNGIBLE_TOKEN_B, 0L),
                getAccountBalance(TOKEN_TREASURY).logged().hasTokenBalance(FUNGIBLE_TOKEN_A, 1000L),
                getAccountBalance(ALT_TOKEN_TREASURY).logged().hasTokenBalance(FUNGIBLE_TOKEN_B, 1000L));
    }

    @HapiTest
    @Tag(MATS)
    final Stream<DynamicTest> tokenRejectInvalidSignaturesAndInvalidAccountOrTokensFailingScenarios() {
        return hapiTest(
                newKeyNamed(MULTI_KEY),
                cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(5),
                cryptoCreate(ACCOUNT_1).maxAutomaticTokenAssociations(5),
                cryptoCreate(TOKEN_TREASURY).balance(ONE_HUNDRED_HBARS),
                tokenCreate(FUNGIBLE_TOKEN_A)
                        .initialSupply(TOTAL_SUPPLY)
                        .adminKey(MULTI_KEY)
                        .supplyKey(MULTI_KEY)
                        .treasury(TOKEN_TREASURY),
                tokenCreate(FUNGIBLE_TOKEN_B)
                        .initialSupply(TOTAL_SUPPLY)
                        .adminKey(MULTI_KEY)
                        .supplyKey(MULTI_KEY)
                        .treasury(TOKEN_TREASURY),
                tokenCreate(NON_FUNGIBLE_TOKEN_A)
                        .initialSupply(0)
                        .adminKey(MULTI_KEY)
                        .supplyKey(MULTI_KEY)
                        .treasury(TOKEN_TREASURY)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE),
                tokenCreate(NON_FUNGIBLE_TOKEN_B)
                        .initialSupply(0)
                        .adminKey(MULTI_KEY)
                        .supplyKey(MULTI_KEY)
                        .treasury(TOKEN_TREASURY)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE),
                tokenAssociate(ACCOUNT, FUNGIBLE_TOKEN_A, FUNGIBLE_TOKEN_B),
                mintToken(NON_FUNGIBLE_TOKEN_A, List.of(copyFromUtf8("little"), copyFromUtf8("donkey"))),
                mintToken(NON_FUNGIBLE_TOKEN_B, List.of(copyFromUtf8("chipi"), copyFromUtf8("chapa"))),
                cryptoTransfer(
                        moving(250L, FUNGIBLE_TOKEN_A).between(TOKEN_TREASURY, ACCOUNT),
                        moving(250L, FUNGIBLE_TOKEN_B).between(TOKEN_TREASURY, ACCOUNT_1),
                        movingUnique(NON_FUNGIBLE_TOKEN_A, 1L).between(TOKEN_TREASURY, ACCOUNT),
                        movingUnique(NON_FUNGIBLE_TOKEN_B, 2L).between(TOKEN_TREASURY, ACCOUNT_1)),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        // Account's signature is missing
                        tokenReject(ACCOUNT, rejectingToken(FUNGIBLE_TOKEN_A))
                                .signedBy(ACCOUNT_1)
                                .payingWithNoSig(ACCOUNT)
                                .hasPrecheck(INVALID_SIGNATURE),
                        // Account_1's signature is missing
                        tokenReject(ACCOUNT_1, rejectingToken(FUNGIBLE_TOKEN_B))
                                .signedBy(ACCOUNT)
                                .hasPrecheck(INVALID_SIGNATURE),
                        // Account_1's not associated with FUNGIBLE_TOKEN_A
                        tokenReject(rejectingToken(FUNGIBLE_TOKEN_A))
                                .payingWith(ACCOUNT_1)
                                .signedBy(ACCOUNT_1)
                                .hasKnownStatus(TOKEN_NOT_ASSOCIATED_TO_ACCOUNT),
                        tokenAssociate(ACCOUNT_1, FUNGIBLE_TOKEN_A),
                        // Account_1 has no FUNGIBLE_TOKEN_A balance
                        tokenReject(rejectingToken(FUNGIBLE_TOKEN_A))
                                .payingWith(ACCOUNT_1)
                                .signedBy(ACCOUNT_1)
                                .hasKnownStatus(INSUFFICIENT_TOKEN_BALANCE),
                        // Treasury account cannot reject tokens.
                        tokenReject(rejectingNFT(NON_FUNGIBLE_TOKEN_A, 2L))
                                .payingWith(TOKEN_TREASURY)
                                .signedBy(TOKEN_TREASURY)
                                .hasKnownStatus(ACCOUNT_IS_TREASURY),
                        tokenReject(TOKEN_TREASURY, rejectingNFT(NON_FUNGIBLE_TOKEN_B, 1L))
                                .hasKnownStatus(ACCOUNT_IS_TREASURY),
                        tokenReject(TOKEN_TREASURY, rejectingToken(FUNGIBLE_TOKEN_A))
                                .hasKnownStatus(ACCOUNT_IS_TREASURY),
                        // remove Treasury's balance for Fungible_TOKEN-A and try again
                        cryptoTransfer(moving(750L, FUNGIBLE_TOKEN_A).between(TOKEN_TREASURY, ACCOUNT)),
                        tokenReject(TOKEN_TREASURY, rejectingToken(FUNGIBLE_TOKEN_A))
                                .hasKnownStatus(ACCOUNT_IS_TREASURY),
                        // Payer account does not own some of the NFTs he is rejecting
                        tokenReject(
                                        rejectingToken(FUNGIBLE_TOKEN_A),
                                        rejectingToken(FUNGIBLE_TOKEN_B),
                                        rejectingNFT(NON_FUNGIBLE_TOKEN_A, 2L),
                                        rejectingNFT(NON_FUNGIBLE_TOKEN_B, 1L))
                                .signedBy(ACCOUNT)
                                .payingWith(ACCOUNT)
                                .hasKnownStatus(INSUFFICIENT_TOKEN_BALANCE),
                        // Account does not own the NFT he is rejecting
                        tokenReject(ACCOUNT, rejectingToken(FUNGIBLE_TOKEN_A), rejectingNFT(NON_FUNGIBLE_TOKEN_A, 2L))
                                .hasKnownStatus(INVALID_OWNER_ID),
                        // Deleted tokens cannot be rejected, as you can already dissociate from them.
                        tokenDelete(FUNGIBLE_TOKEN_A),
                        tokenReject(ACCOUNT, rejectingToken(FUNGIBLE_TOKEN_A), rejectingNFT(NON_FUNGIBLE_TOKEN_A, 1L))
                                .hasKnownStatus(TOKEN_WAS_DELETED))),
                getTokenNftInfo(NON_FUNGIBLE_TOKEN_A, 2L)
                        .hasAccountID(TOKEN_TREASURY)
                        .hasNoSpender(),
                getTokenNftInfo(NON_FUNGIBLE_TOKEN_A, 1L).hasAccountID(ACCOUNT).hasNoSpender(),
                getTokenNftInfo(NON_FUNGIBLE_TOKEN_B, 1L)
                        .hasAccountID(TOKEN_TREASURY)
                        .hasNoSpender(),
                getAccountBalance(TOKEN_TREASURY).logged().hasTokenBalance(FUNGIBLE_TOKEN_A, 0L),
                getAccountBalance(TOKEN_TREASURY).logged().hasTokenBalance(FUNGIBLE_TOKEN_B, 750L));
    }

    @HapiTest
    @Tag(MATS)
    final Stream<DynamicTest> tokenRejectFailsWithInvalidBodyInputsScenarios() {
        return hapiTest(
                newKeyNamed(MULTI_KEY),
                cryptoCreate(ACCOUNT).balance(ONE_MILLION_HBARS).maxAutomaticTokenAssociations(5),
                cryptoCreate(TOKEN_TREASURY).balance(ONE_HUNDRED_HBARS),
                tokenCreate(FUNGIBLE_TOKEN_A)
                        .initialSupply(TOTAL_SUPPLY)
                        .adminKey(MULTI_KEY)
                        .supplyKey(MULTI_KEY)
                        .treasury(TOKEN_TREASURY),
                tokenCreate(FUNGIBLE_TOKEN_B)
                        .initialSupply(TOTAL_SUPPLY)
                        .adminKey(MULTI_KEY)
                        .supplyKey(MULTI_KEY)
                        .treasury(TOKEN_TREASURY),
                tokenCreate(NON_FUNGIBLE_TOKEN_A)
                        .initialSupply(0)
                        .adminKey(MULTI_KEY)
                        .supplyKey(MULTI_KEY)
                        .treasury(TOKEN_TREASURY)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE),
                tokenCreate(NON_FUNGIBLE_TOKEN_B)
                        .initialSupply(0)
                        .adminKey(MULTI_KEY)
                        .supplyKey(MULTI_KEY)
                        .treasury(TOKEN_TREASURY)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE),
                tokenAssociate(ACCOUNT, FUNGIBLE_TOKEN_A, FUNGIBLE_TOKEN_B),
                mintToken(NON_FUNGIBLE_TOKEN_A, List.of(copyFromUtf8("little"), copyFromUtf8("donkey"))),
                mintToken(NON_FUNGIBLE_TOKEN_B, List.of(copyFromUtf8("chipi"), copyFromUtf8("chapa"))),
                cryptoTransfer(
                        moving(250L, FUNGIBLE_TOKEN_A).between(TOKEN_TREASURY, ACCOUNT),
                        moving(250L, FUNGIBLE_TOKEN_B).between(TOKEN_TREASURY, ACCOUNT),
                        movingUnique(NON_FUNGIBLE_TOKEN_A, 1L).between(TOKEN_TREASURY, ACCOUNT),
                        movingUnique(NON_FUNGIBLE_TOKEN_B, 2L).between(TOKEN_TREASURY, ACCOUNT)),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        // Cannot reject the same token in one transaction
                        tokenReject(ACCOUNT, rejectingToken(FUNGIBLE_TOKEN_A), rejectingToken(FUNGIBLE_TOKEN_A))
                                .hasPrecheck(TOKEN_REFERENCE_REPEATED),
                        tokenReject(
                                        ACCOUNT,
                                        rejectingNFT(NON_FUNGIBLE_TOKEN_B, 1L),
                                        rejectingNFT(NON_FUNGIBLE_TOKEN_B, 1L))
                                .hasPrecheck(TOKEN_REFERENCE_REPEATED),
                        // Rejecting with fungible reference with NFT token fails.
                        tokenReject(ACCOUNT, rejectingToken(NON_FUNGIBLE_TOKEN_A))
                                .hasKnownStatus(ACCOUNT_AMOUNT_TRANSFERS_ONLY_ALLOWED_FOR_FUNGIBLE_COMMON),
                        // Rejecting with NFT reference with fungible token fails.
                        tokenReject(ACCOUNT, rejectingNFT(FUNGIBLE_TOKEN_B, 1L)).hasKnownStatus(INVALID_NFT_ID),
                        // Rejecting with 0 references fails.
                        tokenReject(ACCOUNT).hasPrecheck(EMPTY_TOKEN_REFERENCE_LIST))),
                getTokenNftInfo(NON_FUNGIBLE_TOKEN_A, 2L)
                        .hasAccountID(TOKEN_TREASURY)
                        .hasNoSpender(),
                getTokenNftInfo(NON_FUNGIBLE_TOKEN_A, 1L).hasAccountID(ACCOUNT).hasNoSpender(),
                getTokenNftInfo(NON_FUNGIBLE_TOKEN_B, 1L)
                        .hasAccountID(TOKEN_TREASURY)
                        .hasNoSpender(),
                getAccountBalance(TOKEN_TREASURY).logged().hasTokenBalance(FUNGIBLE_TOKEN_A, 750L),
                getAccountBalance(TOKEN_TREASURY).logged().hasTokenBalance(FUNGIBLE_TOKEN_B, 750L));
    }
}
