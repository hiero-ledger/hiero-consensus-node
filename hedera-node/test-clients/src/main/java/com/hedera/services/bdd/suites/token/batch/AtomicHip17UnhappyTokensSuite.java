// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.token.batch;

import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.junit.TestTags.TOKEN;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTokenInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTokenNftInfo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
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
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ZERO_BYTE_MEMO;
import static com.hedera.services.bdd.suites.HapiSuite.flattened;
import static com.hedera.services.bdd.suites.HapiSuite.salted;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INNER_TRANSACTION_FAILED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ZERO_BYTE_IN_STRING;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_WAS_DELETED;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.transactions.token.TokenMovement;
import com.hederahashgraph.api.proto.java.TokenSupplyType;
import com.hederahashgraph.api.proto.java.TokenType;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

// This test cases are direct copies of Hip17UnhappyTokensSuite. The difference here is that
// we are wrapping the operations in an atomic batch to confirm that everything works as expected.
@HapiTestLifecycle
@Tag(TOKEN)
@Tag(MATS)
public class AtomicHip17UnhappyTokensSuite {

    private static final String ANOTHER_USER = "AnotherUser";
    private static final String ANOTHER_KEY = "AnotherKey";

    private static final String TOKEN_TREASURY = "treasury";
    private static final String NEW_TOKEN_TREASURY = "newTreasury";
    private static final String AUTO_RENEW_ACCT = "autoRenewAcct";
    private static final String NEW_AUTO_RENEW_ACCT = "newAutoRenewAcct";

    private static final String NFT_DELETED = "NFTdeleted";
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

    private static final String FIRST_MEMO = "First things first";
    private static final String SECOND_MEMO = "Nothing left to do";
    private static final String SALTED_NAME = salted("primary");
    private static final String NEW_SALTED_NAME = salted("primary");
    private static final String BATCH_OPERATOR = "batchOperator";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(
                Map.of("atomicBatch.isEnabled", "true", "atomicBatch.maxNumberOfTransactions", "50"));
        testLifecycle.doAdhoc(cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS));
    }

    @HapiTest
    final Stream<DynamicTest> canStillGetNftInfoWhenDeleted() {
        return hapiTest(
                newKeyNamed(SUPPLY_KEY),
                newKeyNamed(ADMIN_KEY),
                cryptoCreate(TOKEN_TREASURY).balance(ONE_HUNDRED_HBARS),
                tokenCreate(NFT_DELETED)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .supplyType(TokenSupplyType.INFINITE)
                        .supplyKey(SUPPLY_KEY)
                        .adminKey(ADMIN_KEY)
                        .initialSupply(0)
                        .treasury(TOKEN_TREASURY),
                atomicBatch(
                                mintToken(NFT_DELETED, List.of(metadata(FIRST_MEMO)))
                                        .batchKey(BATCH_OPERATOR),
                                tokenDelete(NFT_DELETED).batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                getTokenNftInfo(NFT_DELETED, 1L).hasTokenID(NFT_DELETED).hasSerialNum(1L));
    }

    @HapiTest
    final Stream<DynamicTest> cannotTransferNftWhenDeleted() {
        return hapiTest(
                newKeyNamed(SUPPLY_KEY),
                newKeyNamed(ADMIN_KEY),
                cryptoCreate(TOKEN_TREASURY),
                cryptoCreate(ANOTHER_USER),
                tokenCreate(NFT_DELETED)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0)
                        .supplyType(TokenSupplyType.INFINITE)
                        .supplyKey(SUPPLY_KEY)
                        .adminKey(ADMIN_KEY)
                        .treasury(TOKEN_TREASURY),
                atomicBatch(
                                tokenAssociate(ANOTHER_USER, NFT_DELETED).batchKey(BATCH_OPERATOR),
                                mintToken(NFT_DELETED, List.of(metadata(FIRST_MEMO), metadata(SECOND_MEMO)))
                                        .batchKey(BATCH_OPERATOR),
                                cryptoTransfer(TokenMovement.movingUnique(NFT_DELETED, 1L)
                                                .between(TOKEN_TREASURY, ANOTHER_USER))
                                        .batchKey(BATCH_OPERATOR),
                                tokenDelete(NFT_DELETED).batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                cryptoTransfer(TokenMovement.movingUnique(NFT_DELETED, 2L).between(TOKEN_TREASURY, ANOTHER_USER))
                        .hasKnownStatus(TOKEN_WAS_DELETED));
    }

    @HapiTest
    final Stream<DynamicTest> cannotUnfreezeNftWhenDeleted() {
        return hapiTest(
                newKeyNamed(SUPPLY_KEY),
                newKeyNamed(FREEZE_KEY),
                newKeyNamed(ADMIN_KEY),
                cryptoCreate(TOKEN_TREASURY).balance(0L).key(ADMIN_KEY),
                tokenCreate(NFT_DELETED)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0L)
                        .freezeKey(FREEZE_KEY)
                        .freezeDefault(true)
                        .adminKey(ADMIN_KEY)
                        .supplyKey(SUPPLY_KEY)
                        .treasury(TOKEN_TREASURY),
                atomicBatch(tokenDelete(NFT_DELETED).batchKey(BATCH_OPERATOR)).payingWith(BATCH_OPERATOR),
                tokenUnfreeze(NFT_DELETED, TOKEN_TREASURY).hasKnownStatus(TOKEN_WAS_DELETED));
    }

    @HapiTest
    final Stream<DynamicTest> cannotFreezeNftWhenDeleted() {
        return hapiTest(
                newKeyNamed(SUPPLY_KEY),
                newKeyNamed(FREEZE_KEY),
                newKeyNamed(ADMIN_KEY),
                cryptoCreate(TOKEN_TREASURY).balance(0L).key(ADMIN_KEY),
                tokenCreate(NFT_DELETED)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .freezeKey(FREEZE_KEY)
                        .adminKey(ADMIN_KEY)
                        .supplyKey(SUPPLY_KEY)
                        .initialSupply(0L)
                        .treasury(TOKEN_TREASURY),
                atomicBatch(tokenDelete(NFT_DELETED).batchKey(BATCH_OPERATOR)).payingWith(BATCH_OPERATOR),
                tokenFreeze(NFT_DELETED, TOKEN_TREASURY).hasPrecheck(OK).hasKnownStatus(TOKEN_WAS_DELETED));
    }

    @HapiTest
    final Stream<DynamicTest> cannotDissociateNftWhenDeleted() {
        return hapiTest(
                newKeyNamed(ADMIN_KEY),
                newKeyNamed(SUPPLY_KEY),
                cryptoCreate(TOKEN_TREASURY).key(ADMIN_KEY),
                cryptoCreate(ANOTHER_USER),
                tokenCreate(NFT_DELETED)
                        .initialSupply(0)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .supplyType(TokenSupplyType.INFINITE)
                        .supplyKey(SUPPLY_KEY)
                        .adminKey(ADMIN_KEY)
                        .treasury(TOKEN_TREASURY),
                atomicBatch(
                                tokenAssociate(ANOTHER_USER, NFT_DELETED).batchKey(BATCH_OPERATOR),
                                tokenDelete(NFT_DELETED).batchKey(BATCH_OPERATOR),
                                tokenDissociate(ANOTHER_USER, NFT_DELETED).batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR));
    }

    @HapiTest
    final Stream<DynamicTest> cannotAssociateNftWhenDeleted() {
        return hapiTest(
                newKeyNamed(ADMIN_KEY),
                newKeyNamed(SUPPLY_KEY),
                cryptoCreate(TOKEN_TREASURY).key(ADMIN_KEY),
                cryptoCreate(ANOTHER_USER),
                tokenCreate(NFT_DELETED)
                        .initialSupply(0)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .supplyType(TokenSupplyType.INFINITE)
                        .supplyKey(SUPPLY_KEY)
                        .adminKey(ADMIN_KEY)
                        .treasury(TOKEN_TREASURY),
                atomicBatch(tokenDelete(NFT_DELETED).batchKey(BATCH_OPERATOR)).payingWith(BATCH_OPERATOR),
                tokenAssociate(ANOTHER_USER, NFT_DELETED).hasKnownStatus(TOKEN_WAS_DELETED));
    }

    private HapiSpecOperation[] cannotUpdateNftWhenDeletedBase() {
        return new HapiSpecOperation[] {
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
            tokenCreate(NFT_DELETED)
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
            tokenAssociate(NEW_TOKEN_TREASURY, NFT_DELETED)
        };
    }

    @HapiTest
    final Stream<DynamicTest> updateZeroByteMemo() {
        return hapiTest(flattened(
                cannotUpdateNftWhenDeletedBase(),
                atomicBatch(tokenUpdate(NFT_DELETED)
                                .entityMemo(ZERO_BYTE_MEMO)
                                .signedByPayerAnd(ADMIN_KEY)
                                .hasKnownStatus(INVALID_ZERO_BYTE_IN_STRING)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)));
    }

    @HapiTest
    final Stream<DynamicTest> canUpdateBeforeDeletion() {
        return hapiTest(flattened(
                cannotUpdateNftWhenDeletedBase(),
                atomicBatch(tokenUpdate(NFT_DELETED)
                                .name(NEW_SALTED_NAME)
                                .entityMemo(SECOND_MEMO)
                                .treasury(NEW_TOKEN_TREASURY)
                                .autoRenewAccount(NEW_AUTO_RENEW_ACCT)
                                .freezeKey(NEW_FREEZE_KEY)
                                .kycKey(NEW_KYC_KEY)
                                .supplyKey(NEW_SUPPLY_KEY)
                                .wipeKey(NEW_WIPE_KEY)
                                .signedByPayerAnd(ADMIN_KEY, NEW_TOKEN_TREASURY, NEW_AUTO_RENEW_ACCT)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)));
    }

    @HapiTest
    final Stream<DynamicTest> cannotUpdateNftWhenDeleted1() {
        return hapiTest(flattened(
                cannotUpdateNftWhenDeletedBase(),
                tokenDelete(NFT_DELETED),
                atomicBatch(tokenUpdate(NFT_DELETED)
                                .name(NEW_SALTED_NAME)
                                .entityMemo(SECOND_MEMO)
                                .signedByPayerAnd(ADMIN_KEY)
                                .hasKnownStatus(TOKEN_WAS_DELETED)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)));
    }

    @HapiTest
    final Stream<DynamicTest> cannotUpdateNftWhenDeleted2() {
        return hapiTest(flattened(
                cannotUpdateNftWhenDeletedBase(),
                tokenDelete(NFT_DELETED),
                atomicBatch(tokenUpdate(NFT_DELETED)
                                .treasury(NEW_TOKEN_TREASURY)
                                .signedByPayerAnd(ADMIN_KEY, NEW_TOKEN_TREASURY)
                                .hasKnownStatus(TOKEN_WAS_DELETED)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)));
    }

    @HapiTest
    final Stream<DynamicTest> cannotUpdateNftWhenDeleted3() {
        return hapiTest(flattened(
                cannotUpdateNftWhenDeletedBase(),
                tokenDelete(NFT_DELETED),
                atomicBatch(tokenUpdate(NFT_DELETED)
                                .autoRenewAccount(NEW_AUTO_RENEW_ACCT)
                                .signedByPayerAnd(ADMIN_KEY, NEW_AUTO_RENEW_ACCT)
                                .hasKnownStatus(TOKEN_WAS_DELETED)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)));
    }

    @HapiTest
    final Stream<DynamicTest> cannotUpdateNftWhenDeleted() {
        return hapiTest(flattened(
                cannotUpdateNftWhenDeletedBase(),
                tokenDelete(NFT_DELETED),
                atomicBatch(tokenUpdate(NFT_DELETED)
                                .freezeKey(NEW_FREEZE_KEY)
                                .kycKey(NEW_KYC_KEY)
                                .supplyKey(NEW_SUPPLY_KEY)
                                .wipeKey(NEW_WIPE_KEY)
                                .signedByPayerAnd(ADMIN_KEY)
                                .hasKnownStatus(TOKEN_WAS_DELETED)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)));
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
                tokenCreate(NFT_DELETED)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0L)
                        .adminKey(ADMIN_KEY)
                        .treasury(TOKEN_TREASURY)
                        .supplyKey(SUPPLY_KEY)
                        .feeScheduleKey(FEE_SCHEDULE_KEY)
                        .withCustom(fixedHbarFee(origHbarFee, hbarCollector)),
                atomicBatch(
                                tokenDelete(NFT_DELETED).batchKey(BATCH_OPERATOR),
                                tokenFeeScheduleUpdate(NFT_DELETED)
                                        .batchKey(BATCH_OPERATOR)
                                        .withCustom(fixedHbarFee(newHbarFee, hbarCollector))
                                        .hasKnownStatus(TOKEN_WAS_DELETED)
                                        .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    @HapiTest
    final Stream<DynamicTest> cannotMintNftWhenDeleted() {
        return hapiTest(
                newKeyNamed(SUPPLY_KEY),
                newKeyNamed(WIPE_KEY),
                newKeyNamed(ADMIN_KEY),
                cryptoCreate(TOKEN_TREASURY).key(ADMIN_KEY),
                cryptoCreate(ANOTHER_USER),
                tokenCreate(NFT_DELETED)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .supplyType(TokenSupplyType.INFINITE)
                        .supplyKey(SUPPLY_KEY)
                        .initialSupply(0)
                        .treasury(TOKEN_TREASURY)
                        .adminKey(ADMIN_KEY),
                atomicBatch(
                                tokenDelete(NFT_DELETED).batchKey(BATCH_OPERATOR),
                                mintToken(NFT_DELETED, List.of(ByteString.copyFromUtf8(FIRST_MEMO)))
                                        .hasKnownStatus(TOKEN_WAS_DELETED)
                                        .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
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
                tokenCreate(NFT_DELETED)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .supplyType(TokenSupplyType.INFINITE)
                        .supplyKey(SUPPLY_KEY)
                        .initialSupply(0)
                        .treasury(TOKEN_TREASURY)
                        .adminKey(ADMIN_KEY),
                atomicBatch(
                                tokenAssociate(ANOTHER_USER, NFT_DELETED).batchKey(BATCH_OPERATOR),
                                mintToken(
                                                NFT_DELETED,
                                                List.of(
                                                        ByteString.copyFromUtf8(FIRST_MEMO),
                                                        ByteString.copyFromUtf8(SECOND_MEMO)))
                                        .batchKey(BATCH_OPERATOR),
                                cryptoTransfer(movingUnique(NFT_DELETED, 2L).between(TOKEN_TREASURY, ANOTHER_USER))
                                        .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                getAccountInfo(ANOTHER_USER).hasOwnedNfts(1),
                getAccountInfo(TOKEN_TREASURY).hasOwnedNfts(1),
                getTokenInfo(NFT_DELETED).hasTotalSupply(2),
                getTokenNftInfo(NFT_DELETED, 2).hasCostAnswerPrecheck(OK),
                getTokenNftInfo(NFT_DELETED, 1).hasSerialNum(1),
                atomicBatch(
                                tokenDelete(NFT_DELETED).batchKey(BATCH_OPERATOR),
                                burnToken(NFT_DELETED, List.of(2L))
                                        .hasKnownStatus(TOKEN_WAS_DELETED)
                                        .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
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
                tokenCreate(NFT_DELETED)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .supplyType(TokenSupplyType.INFINITE)
                        .supplyKey(SUPPLY_KEY)
                        .initialSupply(0)
                        .treasury(TOKEN_TREASURY)
                        .adminKey(ADMIN_KEY)
                        .wipeKey(WIPE_KEY),
                atomicBatch(
                                tokenAssociate(ANOTHER_USER, NFT_DELETED).batchKey(BATCH_OPERATOR),
                                mintToken(
                                                NFT_DELETED,
                                                List.of(
                                                        ByteString.copyFromUtf8(FIRST_MEMO),
                                                        ByteString.copyFromUtf8(SECOND_MEMO)))
                                        .batchKey(BATCH_OPERATOR),
                                cryptoTransfer(movingUnique(NFT_DELETED, 2L).between(TOKEN_TREASURY, ANOTHER_USER))
                                        .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                getAccountInfo(ANOTHER_USER).hasOwnedNfts(1),
                getAccountInfo(TOKEN_TREASURY).hasOwnedNfts(1),
                getTokenInfo(NFT_DELETED).hasTotalSupply(2),
                getTokenNftInfo(NFT_DELETED, 2).hasCostAnswerPrecheck(OK),
                getTokenNftInfo(NFT_DELETED, 1).hasSerialNum(1),
                atomicBatch(
                                tokenDelete(NFT_DELETED).batchKey(BATCH_OPERATOR),
                                wipeTokenAccount(NFT_DELETED, ANOTHER_USER, List.of(1L))
                                        .hasKnownStatus(TOKEN_WAS_DELETED)
                                        .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    private ByteString metadata(String contents) {
        return ByteString.copyFromUtf8(contents);
    }
}
