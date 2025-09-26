// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.token;

import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.junit.TestTags.TOKEN;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTokenInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTokenNftInfo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.burnToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenDissociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenFeeScheduleUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenFreeze;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenUnfreeze;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.wipeTokenAccount;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedHbarFee;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingUnique;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ZERO_BYTE_MEMO;
import static com.hedera.services.bdd.suites.HapiSuite.salted;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ZERO_BYTE_IN_STRING;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_WAS_DELETED;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.spec.transactions.token.TokenMovement;
import com.hederahashgraph.api.proto.java.TokenSupplyType;
import com.hederahashgraph.api.proto.java.TokenType;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(TOKEN)
@Tag(MATS)
public class Hip17UnhappyTokensSuite {
    private static final String ANOTHER_USER = "AnotherUser";
    private static final String ANOTHER_KEY = "AnotherKey";

    private static final String TOKEN_TREASURY = "treasury";
    private static final String NEW_TOKEN_TREASURY = "newTreasury";
    private static final String AUTO_RENEW_ACCT = "autoRenewAcct";
    private static final String NEW_AUTO_RENEW_ACCT = "newAutoRenewAcct";

    private static final String NFTdeleted = "NFTdeleted";
    private static final String ADMIN_KEY = "adminKey";
    private static final String SUPPLY_KEY = "supplyKey";
    private static final String FREEZE_KEY = "freezeKey";
    private static final String WIPE_KEY = "wipeKey";
    private static final String KYC_KEY = "kycKey";
    private static final String FEE_SCHEDULE_KEY = "feeScheduleKey";

    private static final String NEW_FREEZE_KEY = "newFreezeKey";
    private static final String NEW_WIPE_KEY = "newWipeKey";
    private static final String NEW_KYC_KEY = "newKycKey";
    private static final String NEW_SUPPLY_KEY = "newSupplyKey";

    private static String FIRST_MEMO = "First things first";
    private static String SECOND_MEMO = "Nothing left to do";
    private static String SALTED_NAME = salted("primary");
    private static String NEW_SALTED_NAME = salted("primary");

    @HapiTest
    final Stream<DynamicTest> canStillGetNftInfoWhenDeleted() {
        return hapiTest(
                newKeyNamed(SUPPLY_KEY),
                newKeyNamed(ADMIN_KEY),
                cryptoCreate(TOKEN_TREASURY).balance(ONE_HUNDRED_HBARS),
                tokenCreate(NFTdeleted)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .supplyType(TokenSupplyType.INFINITE)
                        .supplyKey(SUPPLY_KEY)
                        .adminKey(ADMIN_KEY)
                        .initialSupply(0)
                        .treasury(TOKEN_TREASURY),
                mintToken(NFTdeleted, List.of(metadata(FIRST_MEMO))),
                (tokenDelete(NFTdeleted)),
                (getTokenNftInfo(NFTdeleted, 1L).hasTokenID(NFTdeleted).hasSerialNum(1L)));
    }

    @HapiTest
    final Stream<DynamicTest> cannotTransferNftWhenDeleted() {
        return hapiTest(
                newKeyNamed(SUPPLY_KEY),
                newKeyNamed(ADMIN_KEY),
                cryptoCreate(TOKEN_TREASURY),
                cryptoCreate(ANOTHER_USER),
                tokenCreate(NFTdeleted)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0)
                        .supplyType(TokenSupplyType.INFINITE)
                        .supplyKey(SUPPLY_KEY)
                        .adminKey(ADMIN_KEY)
                        .treasury(TOKEN_TREASURY),
                tokenAssociate(ANOTHER_USER, NFTdeleted),
                mintToken(NFTdeleted, List.of(metadata(FIRST_MEMO), metadata(SECOND_MEMO))),
                cryptoTransfer(TokenMovement.movingUnique(NFTdeleted, 1L).between(TOKEN_TREASURY, ANOTHER_USER)),
                tokenDelete(NFTdeleted),
                cryptoTransfer(TokenMovement.movingUnique(NFTdeleted, 2L).between(TOKEN_TREASURY, ANOTHER_USER))
                        .hasKnownStatus(TOKEN_WAS_DELETED));
    }

    @HapiTest
    final Stream<DynamicTest> cannotUnfreezeNftWhenDeleted() {
        return hapiTest(
                newKeyNamed(SUPPLY_KEY),
                newKeyNamed(FREEZE_KEY),
                newKeyNamed(ADMIN_KEY),
                cryptoCreate(TOKEN_TREASURY).balance(0L).key(ADMIN_KEY),
                tokenCreate(NFTdeleted)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0L)
                        .freezeKey(FREEZE_KEY)
                        .freezeDefault(true)
                        .adminKey(ADMIN_KEY)
                        .supplyKey(SUPPLY_KEY)
                        .treasury(TOKEN_TREASURY),
                tokenDelete(NFTdeleted),
                tokenUnfreeze(NFTdeleted, TOKEN_TREASURY).hasKnownStatus(TOKEN_WAS_DELETED));
    }

    @HapiTest
    final Stream<DynamicTest> cannotFreezeNftWhenDeleted() {
        return hapiTest(
                newKeyNamed(SUPPLY_KEY),
                newKeyNamed(FREEZE_KEY),
                newKeyNamed(ADMIN_KEY),
                cryptoCreate(TOKEN_TREASURY).balance(0L).key(ADMIN_KEY),
                tokenCreate(NFTdeleted)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .freezeKey(FREEZE_KEY)
                        .adminKey(ADMIN_KEY)
                        .supplyKey(SUPPLY_KEY)
                        .initialSupply(0L)
                        .treasury(TOKEN_TREASURY),
                tokenDelete(NFTdeleted),
                tokenFreeze(NFTdeleted, TOKEN_TREASURY).hasPrecheck(OK).hasKnownStatus(TOKEN_WAS_DELETED));
    }

    @HapiTest
    final Stream<DynamicTest> cannotDissociateNftWhenDeleted() {
        return hapiTest(
                newKeyNamed(ADMIN_KEY),
                newKeyNamed(SUPPLY_KEY),
                cryptoCreate(TOKEN_TREASURY).key(ADMIN_KEY),
                cryptoCreate(ANOTHER_USER),
                tokenCreate(NFTdeleted)
                        .initialSupply(0)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .supplyType(TokenSupplyType.INFINITE)
                        .supplyKey(SUPPLY_KEY)
                        .adminKey(ADMIN_KEY)
                        .treasury(TOKEN_TREASURY),
                tokenAssociate(ANOTHER_USER, NFTdeleted),
                tokenDelete(NFTdeleted),
                tokenDissociate(ANOTHER_USER, NFTdeleted).hasKnownStatus(SUCCESS));
    }

    @HapiTest
    final Stream<DynamicTest> cannotAssociateNftWhenDeleted() {
        return hapiTest(
                newKeyNamed(ADMIN_KEY),
                newKeyNamed(SUPPLY_KEY),
                cryptoCreate(TOKEN_TREASURY).key(ADMIN_KEY),
                cryptoCreate(ANOTHER_USER),
                tokenCreate(NFTdeleted)
                        .initialSupply(0)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .supplyType(TokenSupplyType.INFINITE)
                        .supplyKey(SUPPLY_KEY)
                        .adminKey(ADMIN_KEY)
                        .treasury(TOKEN_TREASURY),
                tokenDelete(NFTdeleted),
                tokenAssociate(ANOTHER_USER, NFTdeleted).hasKnownStatus(TOKEN_WAS_DELETED));
    }

    @HapiTest // transferList differ
    final Stream<DynamicTest> cannotUpdateNftWhenDeleted() {
        return hapiTest(
                cryptoCreate(TOKEN_TREASURY).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(NEW_TOKEN_TREASURY).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(AUTO_RENEW_ACCT).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(NEW_AUTO_RENEW_ACCT).balance(ONE_HUNDRED_HBARS),
                newKeyNamed(ADMIN_KEY),
                newKeyNamed(FREEZE_KEY),
                newKeyNamed(NEW_FREEZE_KEY),
                newKeyNamed(KYC_KEY),
                newKeyNamed(NEW_KYC_KEY),
                newKeyNamed(SUPPLY_KEY),
                newKeyNamed(NEW_SUPPLY_KEY),
                newKeyNamed(WIPE_KEY),
                newKeyNamed(NEW_WIPE_KEY),
                tokenCreate(NFTdeleted)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .supplyType(TokenSupplyType.INFINITE)
                        .name(SALTED_NAME)
                        .entityMemo(FIRST_MEMO)
                        .treasury(TOKEN_TREASURY)
                        .autoRenewAccount(AUTO_RENEW_ACCT)
                        .initialSupply(0)
                        .adminKey(ADMIN_KEY)
                        .freezeKey(FREEZE_KEY)
                        .kycKey(KYC_KEY)
                        .supplyKey(SUPPLY_KEY)
                        .wipeKey(WIPE_KEY),
                tokenAssociate(NEW_TOKEN_TREASURY, NFTdeleted),
                // can update before NFT is deleted
                tokenUpdate(NFTdeleted)
                        .entityMemo(ZERO_BYTE_MEMO)
                        .signedByPayerAnd(ADMIN_KEY)
                        .hasPrecheck(INVALID_ZERO_BYTE_IN_STRING),
                tokenUpdate(NFTdeleted)
                        .name(NEW_SALTED_NAME)
                        .entityMemo(SECOND_MEMO)
                        .treasury(NEW_TOKEN_TREASURY)
                        .autoRenewAccount(NEW_AUTO_RENEW_ACCT)
                        .freezeKey(NEW_FREEZE_KEY)
                        .kycKey(NEW_KYC_KEY)
                        .supplyKey(NEW_SUPPLY_KEY)
                        .wipeKey(NEW_WIPE_KEY)
                        .signedByPayerAnd(ADMIN_KEY, NEW_TOKEN_TREASURY, NEW_AUTO_RENEW_ACCT)
                        .hasKnownStatus(SUCCESS),
                tokenDelete(NFTdeleted),
                // can't update after NFT is deleted.
                tokenUpdate(NFTdeleted)
                        .name(NEW_SALTED_NAME)
                        .entityMemo(SECOND_MEMO)
                        .signedByPayerAnd(ADMIN_KEY)
                        .hasKnownStatus(TOKEN_WAS_DELETED),
                tokenUpdate(NFTdeleted)
                        .treasury(NEW_TOKEN_TREASURY)
                        .signedByPayerAnd(ADMIN_KEY, NEW_TOKEN_TREASURY)
                        .hasKnownStatus(TOKEN_WAS_DELETED),
                tokenUpdate(NFTdeleted)
                        .autoRenewAccount(NEW_AUTO_RENEW_ACCT)
                        .signedByPayerAnd(ADMIN_KEY, NEW_AUTO_RENEW_ACCT)
                        .hasKnownStatus(TOKEN_WAS_DELETED),
                tokenUpdate(NFTdeleted)
                        .freezeKey(NEW_FREEZE_KEY)
                        .kycKey(NEW_KYC_KEY)
                        .supplyKey(NEW_SUPPLY_KEY)
                        .wipeKey(NEW_WIPE_KEY)
                        .signedByPayerAnd(ADMIN_KEY)
                        .hasKnownStatus(TOKEN_WAS_DELETED));
    }

    @HapiTest
    final Stream<DynamicTest> cannotUpdateNftFeeScheduleWhenDeleted() {
        final var origHbarFee = 1_234L;
        final var newHbarFee = 4_321L;
        final var hbarCollector = "hbarFee";

        return hapiTest(
                newKeyNamed(ADMIN_KEY),
                newKeyNamed(FEE_SCHEDULE_KEY),
                newKeyNamed(SUPPLY_KEY),
                cryptoCreate(TOKEN_TREASURY).key(ADMIN_KEY),
                cryptoCreate(hbarCollector),
                tokenCreate(NFTdeleted)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0L)
                        .adminKey(ADMIN_KEY)
                        .treasury(TOKEN_TREASURY)
                        .supplyKey(SUPPLY_KEY)
                        .feeScheduleKey(FEE_SCHEDULE_KEY)
                        .withCustom(fixedHbarFee(origHbarFee, hbarCollector)),
                tokenDelete(NFTdeleted),
                tokenFeeScheduleUpdate(NFTdeleted)
                        .withCustom(fixedHbarFee(newHbarFee, hbarCollector))
                        .hasKnownStatus(TOKEN_WAS_DELETED));
    }

    @HapiTest
    final Stream<DynamicTest> cannotMintNftWhenDeleted() {
        return hapiTest(
                newKeyNamed(SUPPLY_KEY),
                newKeyNamed(WIPE_KEY),
                newKeyNamed(ADMIN_KEY),
                cryptoCreate(TOKEN_TREASURY).key(ADMIN_KEY),
                cryptoCreate(ANOTHER_USER),
                tokenCreate(NFTdeleted)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .supplyType(TokenSupplyType.INFINITE)
                        .supplyKey(SUPPLY_KEY)
                        .initialSupply(0)
                        .treasury(TOKEN_TREASURY)
                        .adminKey(ADMIN_KEY),
                tokenDelete(NFTdeleted),
                mintToken(NFTdeleted, List.of(ByteString.copyFromUtf8(FIRST_MEMO)))
                        .hasKnownStatus(TOKEN_WAS_DELETED));
    }

    @HapiTest
    final Stream<DynamicTest> cannotBurnNftWhenDeleted() {
        return hapiTest(
                newKeyNamed(SUPPLY_KEY),
                newKeyNamed(WIPE_KEY),
                newKeyNamed(ADMIN_KEY),
                newKeyNamed(ANOTHER_KEY),
                cryptoCreate(TOKEN_TREASURY).key(ADMIN_KEY),
                cryptoCreate(ANOTHER_USER).key(ANOTHER_KEY),
                tokenCreate(NFTdeleted)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .supplyType(TokenSupplyType.INFINITE)
                        .supplyKey(SUPPLY_KEY)
                        .initialSupply(0)
                        .treasury(TOKEN_TREASURY)
                        .adminKey(ADMIN_KEY),
                tokenAssociate(ANOTHER_USER, NFTdeleted),
                mintToken(
                        NFTdeleted, List.of(ByteString.copyFromUtf8(FIRST_MEMO), ByteString.copyFromUtf8(SECOND_MEMO))),
                cryptoTransfer(movingUnique(NFTdeleted, 2L).between(TOKEN_TREASURY, ANOTHER_USER)),
                getAccountInfo(ANOTHER_USER).hasOwnedNfts(1),
                getAccountInfo(TOKEN_TREASURY).hasOwnedNfts(1),
                getTokenInfo(NFTdeleted).hasTotalSupply(2),
                getTokenNftInfo(NFTdeleted, 2).hasCostAnswerPrecheck(OK),
                getTokenNftInfo(NFTdeleted, 1).hasSerialNum(1),
                tokenDelete(NFTdeleted),
                burnToken(NFTdeleted, List.of(2L)).hasKnownStatus(TOKEN_WAS_DELETED));
    }

    @HapiTest
    final Stream<DynamicTest> cannotWipeNftWhenDeleted() {
        return hapiTest(
                newKeyNamed(SUPPLY_KEY),
                newKeyNamed(WIPE_KEY),
                newKeyNamed(ADMIN_KEY),
                newKeyNamed(ANOTHER_KEY),
                cryptoCreate(TOKEN_TREASURY).key(ADMIN_KEY),
                cryptoCreate(ANOTHER_USER).key(ANOTHER_KEY),
                tokenCreate(NFTdeleted)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .supplyType(TokenSupplyType.INFINITE)
                        .supplyKey(SUPPLY_KEY)
                        .initialSupply(0)
                        .treasury(TOKEN_TREASURY)
                        .adminKey(ADMIN_KEY)
                        .wipeKey(WIPE_KEY),
                tokenAssociate(ANOTHER_USER, NFTdeleted),
                mintToken(
                        NFTdeleted, List.of(ByteString.copyFromUtf8(FIRST_MEMO), ByteString.copyFromUtf8(SECOND_MEMO))),
                cryptoTransfer(movingUnique(NFTdeleted, 2L).between(TOKEN_TREASURY, ANOTHER_USER)),
                getAccountInfo(ANOTHER_USER).hasOwnedNfts(1),
                getAccountInfo(TOKEN_TREASURY).hasOwnedNfts(1),
                getTokenInfo(NFTdeleted).hasTotalSupply(2),
                getTokenNftInfo(NFTdeleted, 2).hasCostAnswerPrecheck(OK),
                getTokenNftInfo(NFTdeleted, 1).hasSerialNum(1),
                tokenDelete(NFTdeleted),
                wipeTokenAccount(NFTdeleted, ANOTHER_USER, List.of(1L)).hasKnownStatus(TOKEN_WAS_DELETED));
    }

    private ByteString metadata(String contents) {
        return ByteString.copyFromUtf8(contents);
    }
}
