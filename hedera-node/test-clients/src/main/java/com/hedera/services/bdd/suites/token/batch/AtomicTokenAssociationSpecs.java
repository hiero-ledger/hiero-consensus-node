// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.token.batch;

import static com.hedera.services.bdd.junit.TestTags.TOKEN;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.NoTokenTransfers.emptyTokenTransfers;
import static com.hedera.services.bdd.spec.assertions.SomeFungibleTransfers.changingFungibleBalances;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getContractInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.queries.crypto.ExpectedTokenRel.relationshipWith;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.burnToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.createDefaultContract;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenDissociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenFreeze;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenUnfreeze;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.token.HapiTokenAssociate.DEFAULT_FEE;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overridingTwo;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sleepFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.TOKEN_TREASURY;
import static com.hedera.services.bdd.suites.HapiSuite.flattened;
import static com.hedera.services.bdd.suites.token.TokenAssociationSpecs.basicKeysAndTokens;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_DELETED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_FROZEN_FOR_TOKEN;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_IS_TREASURY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INNER_TRANSACTION_FAILED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOKEN_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKENS_PER_ACCOUNT_LIMIT_EXCEEDED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_ALREADY_ASSOCIATED_TO_ACCOUNT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_ID_REPEATED_IN_TOKEN_LIST;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_NOT_ASSOCIATED_TO_ACCOUNT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_WAS_DELETED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TRANSACTION_REQUIRES_ZERO_TOKEN_BALANCES;
import static com.hederahashgraph.api.proto.java.TokenFreezeStatus.FreezeNotApplicable;
import static com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.transactions.token.TokenMovement;
import com.hederahashgraph.api.proto.java.TokenID;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

// This test cases are direct copies of TokenAssociationSpecs. The difference here is that
// we are wrapping the operations in an atomic batch to confirm that everything works as expected.
@HapiTestLifecycle
@Tag(TOKEN)
public class AtomicTokenAssociationSpecs {

    public static final String FREEZABLE_TOKEN_ON_BY_DEFAULT = "TokenA";
    public static final String KNOWABLE_TOKEN = "TokenC";
    public static final String VANILLA_TOKEN = "TokenD";
    public static final String MULTI_KEY = "multiKey";
    public static final String TBD_TOKEN = "ToBeDeleted";
    public static final String CREATION = "creation";
    public static final String SIMPLE = "simple";
    public static final String FREEZE_KEY = "freezeKey";
    public static final String KYC_KEY = "kycKey";
    private static final String BATCH_OPERATOR = "batchOperator";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(
                Map.of("atomicBatch.isEnabled", "true", "atomicBatch.maxNumberOfTransactions", "50"));
        testLifecycle.doAdhoc(cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS));
    }

    private HapiSpecOperation[] canHandleInvalidAssociateTransactionsBase() {
        final String alice = "ALICE";
        final String bob = "BOB";
        return new HapiSpecOperation[] {
            newKeyNamed(MULTI_KEY),
            cryptoCreate(alice),
            cryptoCreate(bob),
            cryptoDelete(bob),
            tokenCreate(VANILLA_TOKEN),
            tokenCreate(KNOWABLE_TOKEN),
            tokenAssociate(alice, KNOWABLE_TOKEN),
            tokenCreate(TBD_TOKEN).adminKey(MULTI_KEY),
            tokenDelete(TBD_TOKEN)
        };
    }

    @HapiTest
    final Stream<DynamicTest> canHandleInvalidAssociateTransactionsWithNullAccount() {
        return hapiTest(flattened(
                canHandleInvalidAssociateTransactionsBase(),
                atomicBatch(tokenAssociate(null, VANILLA_TOKEN)
                                .fee(DEFAULT_FEE)
                                .signedBy(DEFAULT_PAYER)
                                .hasPrecheck(INVALID_ACCOUNT_ID)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasPrecheck(INVALID_ACCOUNT_ID)));
    }

    @HapiTest
    final Stream<DynamicTest> canHandleInvalidAssociateTransactionsWithUnknownAccount() {
        final String unknownID = String.valueOf(Long.MAX_VALUE);
        return hapiTest(flattened(
                canHandleInvalidAssociateTransactionsBase(),
                atomicBatch(tokenAssociate(unknownID, VANILLA_TOKEN)
                                .fee(DEFAULT_FEE)
                                .signedBy(DEFAULT_PAYER)
                                .hasPrecheck(INVALID_ACCOUNT_ID)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)));
    }

    @HapiTest
    final Stream<DynamicTest> canHandleInvalidAssociateTransactionsWithDeletedAccount() {
        final String bob = "BOB";
        return hapiTest(flattened(
                canHandleInvalidAssociateTransactionsBase(),
                atomicBatch(tokenAssociate(bob, VANILLA_TOKEN)
                                .fee(DEFAULT_FEE)
                                .hasKnownStatus(ACCOUNT_DELETED)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)));
    }

    @HapiTest
    final Stream<DynamicTest> canHandleInvalidAssociateTransactionsWithRepeatedTokenList() {
        final String alice = "ALICE";
        return hapiTest(flattened(
                canHandleInvalidAssociateTransactionsBase(),
                atomicBatch(tokenAssociate(alice, VANILLA_TOKEN, VANILLA_TOKEN)
                                .hasPrecheck(TOKEN_ID_REPEATED_IN_TOKEN_LIST)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasPrecheck(TOKEN_ID_REPEATED_IN_TOKEN_LIST)));
    }

    @HapiTest
    final Stream<DynamicTest> canHandleInvalidAssociateTransactionsWithInvalidTokenId() {
        final String alice = "ALICE";
        final String unknownID = String.valueOf(Long.MAX_VALUE);
        return hapiTest(flattened(
                canHandleInvalidAssociateTransactionsBase(),
                atomicBatch(tokenAssociate(alice, unknownID)
                                .hasKnownStatus(INVALID_TOKEN_ID)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)));
    }

    @HapiTest
    final Stream<DynamicTest> canHandleInvalidAssociateTransactionsWithDeletedToken() {
        final String alice = "ALICE";
        return hapiTest(flattened(
                canHandleInvalidAssociateTransactionsBase(),
                atomicBatch(tokenAssociate(alice, TBD_TOKEN)
                                .hasKnownStatus(TOKEN_WAS_DELETED)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)));
    }

    @HapiTest
    final Stream<DynamicTest> canHandleInvalidAssociateTransactionsAlreadyAssociated() {
        final String alice = "ALICE";
        return hapiTest(flattened(
                canHandleInvalidAssociateTransactionsBase(),
                atomicBatch(tokenAssociate(alice, KNOWABLE_TOKEN)
                                .hasKnownStatus(TOKEN_ALREADY_ASSOCIATED_TO_ACCOUNT)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)));
    }

    @LeakyHapiTest(overrides = {"tokens.maxPerAccount", "entities.limitTokenAssociations"})
    final Stream<DynamicTest> canLimitMaxTokensPerAccountTransactions() {
        final String alice = "ALICE";
        final String treasury2 = "TREASURY_2";
        return hapiTest(
                overridingTwo("tokens.maxPerAccount", "1", "entities.limitTokenAssociations", "true"),
                cryptoCreate(alice),
                cryptoCreate(TOKEN_TREASURY),
                cryptoCreate(treasury2),
                tokenCreate(VANILLA_TOKEN).treasury(TOKEN_TREASURY),
                tokenCreate(KNOWABLE_TOKEN).treasury(treasury2),
                tokenAssociate(alice, KNOWABLE_TOKEN),
                atomicBatch(tokenAssociate(alice, VANILLA_TOKEN)
                                .hasKnownStatus(TOKENS_PER_ACCOUNT_LIMIT_EXCEEDED)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    @HapiTest
    final Stream<DynamicTest> handlesUseOfDefaultTokenId() {
        return hapiTest(atomicBatch(tokenAssociate(DEFAULT_PAYER, "0.0.0")
                        .hasPrecheck(INVALID_TOKEN_ID)
                        .batchKey(BATCH_OPERATOR))
                .payingWith(BATCH_OPERATOR)
                .hasPrecheck(INVALID_TOKEN_ID));
    }

    @HapiTest
    final Stream<DynamicTest> canDeleteNonFungibleTokenTreasuryAfterUpdate() {
        return hapiTest(
                newKeyNamed(MULTI_KEY),
                cryptoCreate(TOKEN_TREASURY),
                cryptoCreate("replacementTreasury"),
                tokenCreate(TBD_TOKEN)
                        .adminKey(MULTI_KEY)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0L)
                        .treasury(TOKEN_TREASURY)
                        .supplyKey(MULTI_KEY),
                mintToken(TBD_TOKEN, List.of(ByteString.copyFromUtf8("1"), ByteString.copyFromUtf8("2"))),
                cryptoDelete(TOKEN_TREASURY).hasKnownStatus(ACCOUNT_IS_TREASURY),
                atomicBatch(
                                tokenAssociate("replacementTreasury", TBD_TOKEN).batchKey(BATCH_OPERATOR),
                                tokenUpdate(TBD_TOKEN)
                                        .treasury("replacementTreasury")
                                        .signedByPayerAnd(MULTI_KEY, "replacementTreasury")
                                        .batchKey(BATCH_OPERATOR),
                                cryptoDelete(TOKEN_TREASURY).batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR));
    }

    @HapiTest
    final Stream<DynamicTest> canDeleteNonFungibleTokenTreasuryBurnsAndTokenDeletion() {
        final var firstTbdToken = "firstTbdToken";
        final var secondTbdToken = "secondTbdToken";
        final var treasuryWithoutAllPiecesBurned = "treasuryWithoutAllPiecesBurned";
        final var treasuryWithAllPiecesBurned = "treasuryWithAllPiecesBurned";
        return hapiTest(
                newKeyNamed(MULTI_KEY),
                cryptoCreate(treasuryWithAllPiecesBurned),
                cryptoCreate(treasuryWithoutAllPiecesBurned),
                tokenCreate(firstTbdToken)
                        .adminKey(MULTI_KEY)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0L)
                        .treasury(treasuryWithAllPiecesBurned)
                        .supplyKey(MULTI_KEY),
                tokenCreate(secondTbdToken)
                        .adminKey(MULTI_KEY)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0L)
                        .treasury(treasuryWithoutAllPiecesBurned)
                        .supplyKey(MULTI_KEY),
                mintToken(firstTbdToken, List.of(ByteString.copyFromUtf8("1"), ByteString.copyFromUtf8("2"))),
                mintToken(secondTbdToken, List.of(ByteString.copyFromUtf8("1"), ByteString.copyFromUtf8("2"))),
                atomicBatch(
                                // Delete both tokens, but only burn all serials for
                                // one of them (so that the other has a treasury that
                                // will need to explicitly dissociate from the deleted
                                // token before it can be deleted)
                                burnToken(firstTbdToken, List.of(1L, 2L)).batchKey(BATCH_OPERATOR),
                                tokenDelete(firstTbdToken).batchKey(BATCH_OPERATOR),
                                tokenDelete(secondTbdToken).batchKey(BATCH_OPERATOR),
                                cryptoDelete(treasuryWithAllPiecesBurned).batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                // This treasury still has numPositiveBalances=1
                cryptoDelete(treasuryWithoutAllPiecesBurned).hasKnownStatus(TRANSACTION_REQUIRES_ZERO_TOKEN_BALANCES),
                atomicBatch(
                                tokenDissociate(treasuryWithoutAllPiecesBurned, secondTbdToken)
                                        .batchKey(BATCH_OPERATOR),
                                cryptoDelete(treasuryWithoutAllPiecesBurned).batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR));
    }

    @HapiTest
    final Stream<DynamicTest> associatedContractsMustHaveAdminKeys() {
        String misc = "someToken";
        String contract = "defaultContract";
        return hapiTest(
                tokenCreate(misc),
                atomicBatch(createDefaultContract(contract).omitAdminKey().batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                tokenAssociate(contract, misc).hasKnownStatus(INVALID_SIGNATURE));
    }

    @HapiTest
    final Stream<DynamicTest> contractInfoQueriesAsExpected() {
        final var contract = "contract";
        return hapiTest(
                newKeyNamed(SIMPLE),
                tokenCreate("a"),
                tokenCreate("b"),
                tokenCreate("c"),
                tokenCreate("tbd").adminKey(SIMPLE),
                createDefaultContract(contract),
                atomicBatch(tokenAssociate(contract, "a", "b", "c", "tbd").batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                getContractInfo(contract)
                        .hasToken(relationshipWith("a"))
                        .hasToken(relationshipWith("b"))
                        .hasToken(relationshipWith("c"))
                        .hasToken(relationshipWith("tbd")),
                atomicBatch(
                                tokenDissociate(contract, "b").batchKey(BATCH_OPERATOR),
                                tokenDelete("tbd").batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                getContractInfo(contract)
                        .hasToken(relationshipWith("a"))
                        .hasNoTokenRelationship("b")
                        .hasToken(relationshipWith("c"))
                        .hasToken(relationshipWith("tbd"))
                        .logged());
    }

    @HapiTest
    final Stream<DynamicTest> accountInfoQueriesAsExpected() {
        final var account = "account";
        return hapiTest(
                newKeyNamed(SIMPLE),
                tokenCreate("a").decimals(1),
                tokenCreate("b").decimals(2),
                tokenCreate("c").decimals(3),
                tokenCreate("tbd").adminKey(SIMPLE).decimals(4),
                cryptoCreate(account).balance(0L),
                atomicBatch(tokenAssociate(account, "a", "b", "c", "tbd").batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                getAccountInfo(account)
                        .hasToken(relationshipWith("a").decimals(1))
                        .hasToken(relationshipWith("b").decimals(2))
                        .hasToken(relationshipWith("c").decimals(3))
                        .hasToken(relationshipWith("tbd").decimals(4)),
                atomicBatch(
                                tokenDissociate(account, "b").batchKey(BATCH_OPERATOR),
                                tokenDelete("tbd").batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                getAccountInfo(account)
                        .hasToken(relationshipWith("a").decimals(1))
                        .hasNoTokenRelationship("b")
                        .hasToken(relationshipWith("c").decimals(3))
                        .hasToken(relationshipWith("tbd").decimals(4))
                        .logged());
    }

    @HapiTest
    final Stream<DynamicTest> expiredAndDeletedTokensStillAppearInContractInfo() {
        final String contract = "Fuse";
        final String treasury = "something";
        final String expiringToken = "expiringToken";
        final long lifetimeSecs = 10;
        final long xfer = 123L;
        AtomicLong now = new AtomicLong();

        return hapiTest(
                newKeyNamed("admin"),
                cryptoCreate(treasury),
                uploadInitCode(contract),
                contractCreate(contract).gas(600_000).via(CREATION),
                withOpContext((spec, opLog) -> {
                    var subOp = getTxnRecord(CREATION);
                    allRunFor(spec, subOp);
                    var record = subOp.getResponseRecord();
                    now.set(record.getConsensusTimestamp().getSeconds());
                }),
                sourcing(() -> tokenCreate(expiringToken)
                        .decimals(666)
                        .adminKey("admin")
                        .treasury(treasury)
                        .expiry(now.get() + lifetimeSecs)),
                atomicBatch(
                                tokenAssociate(contract, expiringToken).batchKey(BATCH_OPERATOR),
                                cryptoTransfer(moving(xfer, expiringToken).between(treasury, contract))
                                        .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                getAccountBalance(contract).hasTokenBalance(expiringToken, xfer),
                getContractInfo(contract)
                        .hasToken(relationshipWith(expiringToken).freeze(FreezeNotApplicable)),
                sleepFor(lifetimeSecs * 1_000L),
                getAccountBalance(contract).hasTokenBalance(expiringToken, xfer, 666),
                getContractInfo(contract)
                        .hasToken(relationshipWith(expiringToken).freeze(FreezeNotApplicable)),
                tokenDelete(expiringToken),
                getAccountBalance(contract).hasTokenBalance(expiringToken, xfer),
                getContractInfo(contract)
                        .hasToken(relationshipWith(expiringToken).decimals(666).freeze(FreezeNotApplicable)));
    }

    @HapiTest
    final Stream<DynamicTest> canDissociateFromDeletedTokenWithAlreadyDissociatedTreasury() {
        final String aNonTreasuryAcquaintance = "aNonTreasuryAcquaintance";
        final String bNonTreasuryAcquaintance = "bNonTreasuryAcquaintance";
        final long initialSupply = 100L;
        final long nonZeroXfer = 10L;
        final var treasuryDissoc = "treasuryDissoc";
        final var aNonTreasuryDissoc = "aNonTreasuryDissoc";
        final var bNonTreasuryDissoc = "bNonTreasuryDissoc";

        return hapiTest(
                newKeyNamed(MULTI_KEY),
                cryptoCreate(TOKEN_TREASURY).balance(0L),
                tokenCreate(TBD_TOKEN)
                        .freezeKey(MULTI_KEY)
                        .freezeDefault(false)
                        .adminKey(MULTI_KEY)
                        .initialSupply(initialSupply)
                        .treasury(TOKEN_TREASURY),
                cryptoCreate(aNonTreasuryAcquaintance).balance(0L),
                cryptoCreate(bNonTreasuryAcquaintance).maxAutomaticTokenAssociations(1),
                atomicBatch(
                                tokenAssociate(aNonTreasuryAcquaintance, TBD_TOKEN)
                                        .batchKey(BATCH_OPERATOR),
                                cryptoTransfer(moving(nonZeroXfer, TBD_TOKEN)
                                                .distributing(
                                                        TOKEN_TREASURY,
                                                        aNonTreasuryAcquaintance,
                                                        bNonTreasuryAcquaintance))
                                        .batchKey(BATCH_OPERATOR),
                                tokenFreeze(TBD_TOKEN, aNonTreasuryAcquaintance).batchKey(BATCH_OPERATOR),
                                tokenDelete(TBD_TOKEN).batchKey(BATCH_OPERATOR),
                                tokenDissociate(bNonTreasuryAcquaintance, TBD_TOKEN)
                                        .via(bNonTreasuryDissoc)
                                        .batchKey(BATCH_OPERATOR),
                                tokenDissociate(TOKEN_TREASURY, TBD_TOKEN)
                                        .via(treasuryDissoc)
                                        .batchKey(BATCH_OPERATOR),
                                tokenDissociate(aNonTreasuryAcquaintance, TBD_TOKEN)
                                        .via(aNonTreasuryDissoc)
                                        .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                getTxnRecord(bNonTreasuryDissoc)
                        .hasPriority(recordWith()
                                .tokenTransfers(changingFungibleBalances()
                                        .including(TBD_TOKEN, bNonTreasuryAcquaintance, -nonZeroXfer / 2))),
                getTxnRecord(treasuryDissoc)
                        .hasPriority(recordWith()
                                .tokenTransfers(changingFungibleBalances()
                                        .including(TBD_TOKEN, TOKEN_TREASURY, nonZeroXfer - initialSupply))),
                getTxnRecord(aNonTreasuryDissoc)
                        .hasPriority(recordWith()
                                .tokenTransfers(changingFungibleBalances()
                                        .including(TBD_TOKEN, aNonTreasuryAcquaintance, -nonZeroXfer / 2))));
    }

    @HapiTest
    final Stream<DynamicTest> dissociateHasExpectedSemanticsForDeletedTokens() {
        final String tbdUniqToken = "UniqToBeDeleted";
        final String zeroBalanceFrozen = "0bFrozen";
        final String zeroBalanceUnfrozen = "0bUnfrozen";
        final String nonZeroBalanceFrozen = "1bFrozen";
        final String nonZeroBalanceUnfrozen = "1bUnfrozen";
        final long initialSupply = 100L;
        final long nonZeroXfer = 10L;
        final var zeroBalanceDissoc = "zeroBalanceDissoc";
        final var nonZeroBalanceDissoc = "nonZeroBalanceDissoc";
        final var uniqDissoc = "uniqDissoc";
        final var firstMeta = ByteString.copyFrom("FIRST".getBytes(StandardCharsets.UTF_8));
        final var secondMeta = ByteString.copyFrom("SECOND".getBytes(StandardCharsets.UTF_8));
        final var thirdMeta = ByteString.copyFrom("THIRD".getBytes(StandardCharsets.UTF_8));

        return hapiTest(
                newKeyNamed(MULTI_KEY),
                cryptoCreate(TOKEN_TREASURY).balance(0L),
                tokenCreate(TBD_TOKEN)
                        .adminKey(MULTI_KEY)
                        .initialSupply(initialSupply)
                        .treasury(TOKEN_TREASURY)
                        .freezeKey(MULTI_KEY)
                        .freezeDefault(true),
                tokenCreate(tbdUniqToken)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .treasury(TOKEN_TREASURY)
                        .adminKey(MULTI_KEY)
                        .supplyKey(MULTI_KEY)
                        .initialSupply(0),
                cryptoCreate(zeroBalanceFrozen).balance(0L),
                cryptoCreate(zeroBalanceUnfrozen).balance(0L),
                cryptoCreate(nonZeroBalanceFrozen).balance(0L),
                cryptoCreate(nonZeroBalanceUnfrozen).balance(0L),
                atomicBatch(
                                tokenAssociate(zeroBalanceFrozen, TBD_TOKEN).batchKey(BATCH_OPERATOR),
                                tokenAssociate(zeroBalanceUnfrozen, TBD_TOKEN).batchKey(BATCH_OPERATOR),
                                tokenAssociate(nonZeroBalanceFrozen, TBD_TOKEN).batchKey(BATCH_OPERATOR),
                                tokenAssociate(nonZeroBalanceUnfrozen, TBD_TOKEN)
                                        .batchKey(BATCH_OPERATOR),
                                mintToken(tbdUniqToken, List.of(firstMeta, secondMeta, thirdMeta))
                                        .batchKey(BATCH_OPERATOR),
                                tokenUnfreeze(TBD_TOKEN, zeroBalanceUnfrozen).batchKey(BATCH_OPERATOR),
                                tokenUnfreeze(TBD_TOKEN, nonZeroBalanceUnfrozen).batchKey(BATCH_OPERATOR),
                                tokenUnfreeze(TBD_TOKEN, nonZeroBalanceFrozen).batchKey(BATCH_OPERATOR),
                                cryptoTransfer(moving(nonZeroXfer, TBD_TOKEN)
                                                .between(TOKEN_TREASURY, nonZeroBalanceFrozen))
                                        .batchKey(BATCH_OPERATOR),
                                cryptoTransfer(moving(nonZeroXfer, TBD_TOKEN)
                                                .between(TOKEN_TREASURY, nonZeroBalanceUnfrozen))
                                        .batchKey(BATCH_OPERATOR),
                                tokenFreeze(TBD_TOKEN, nonZeroBalanceFrozen).batchKey(BATCH_OPERATOR),
                                tokenDelete(TBD_TOKEN).batchKey(BATCH_OPERATOR),
                                tokenDelete(tbdUniqToken).batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                tokenDissociate(zeroBalanceFrozen, TBD_TOKEN).via(zeroBalanceDissoc),
                tokenDissociate(zeroBalanceUnfrozen, TBD_TOKEN),
                tokenDissociate(nonZeroBalanceFrozen, TBD_TOKEN).via(nonZeroBalanceDissoc),
                tokenDissociate(nonZeroBalanceUnfrozen, TBD_TOKEN),
                tokenDissociate(TOKEN_TREASURY, tbdUniqToken).via(uniqDissoc),
                getAccountBalance(TOKEN_TREASURY).hasTokenBalance(TBD_TOKEN, initialSupply - 2 * nonZeroXfer),
                getTxnRecord(zeroBalanceDissoc).hasPriority(recordWith().tokenTransfers(emptyTokenTransfers())),
                getTxnRecord(nonZeroBalanceDissoc)
                        .hasPriority(recordWith()
                                .tokenTransfers(changingFungibleBalances()
                                        .including(TBD_TOKEN, nonZeroBalanceFrozen, -nonZeroXfer))),
                getTxnRecord(uniqDissoc)
                        .hasPriority(recordWith()
                                .tokenTransfers(
                                        changingFungibleBalances().including(tbdUniqToken, TOKEN_TREASURY, -3))),
                getAccountInfo(TOKEN_TREASURY).hasOwnedNfts(0));
    }

    @HapiTest
    final Stream<DynamicTest> dissociateHasExpectedSemantics() {
        return hapiTest(flattened(
                basicKeysAndTokens(),
                tokenCreate("tkn1"),
                cryptoCreate("misc"),
                atomicBatch(tokenDissociate(TOKEN_TREASURY, "tkn1")
                                .hasKnownStatus(ACCOUNT_IS_TREASURY)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatch(tokenDissociate("misc", FREEZABLE_TOKEN_ON_BY_DEFAULT)
                                .hasKnownStatus(TOKEN_NOT_ASSOCIATED_TO_ACCOUNT)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatch(tokenAssociate("misc", FREEZABLE_TOKEN_ON_BY_DEFAULT, KNOWABLE_TOKEN)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                atomicBatch(tokenDissociate("misc", FREEZABLE_TOKEN_ON_BY_DEFAULT)
                                .hasKnownStatus(ACCOUNT_FROZEN_FOR_TOKEN)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatch(
                                tokenUnfreeze(FREEZABLE_TOKEN_ON_BY_DEFAULT, "misc")
                                        .batchKey(BATCH_OPERATOR),
                                cryptoTransfer(moving(1, FREEZABLE_TOKEN_ON_BY_DEFAULT)
                                                .between(TOKEN_TREASURY, "misc"))
                                        .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                atomicBatch(tokenDissociate("misc", FREEZABLE_TOKEN_ON_BY_DEFAULT)
                                .hasKnownStatus(TRANSACTION_REQUIRES_ZERO_TOKEN_BALANCES)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatch(
                                cryptoTransfer(moving(1, FREEZABLE_TOKEN_ON_BY_DEFAULT)
                                                .between("misc", TOKEN_TREASURY))
                                        .batchKey(BATCH_OPERATOR),
                                tokenDissociate("misc", FREEZABLE_TOKEN_ON_BY_DEFAULT)
                                        .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                getAccountInfo("misc")
                        .hasToken(relationshipWith(KNOWABLE_TOKEN))
                        .hasNoTokenRelationship(FREEZABLE_TOKEN_ON_BY_DEFAULT)
                        .logged()));
    }

    @HapiTest
    final Stream<DynamicTest> dissociateHasExpectedSemanticsForDissociatedContracts() {
        final var uniqToken = "UniqToken";
        final var contract = "Fuse";
        final var firstMeta = ByteString.copyFrom("FIRST".getBytes(StandardCharsets.UTF_8));
        final var secondMeta = ByteString.copyFrom("SECOND".getBytes(StandardCharsets.UTF_8));
        final var thirdMeta = ByteString.copyFrom("THIRD".getBytes(StandardCharsets.UTF_8));

        return hapiTest(
                newKeyNamed(MULTI_KEY),
                cryptoCreate(TOKEN_TREASURY).balance(0L).maxAutomaticTokenAssociations(542),
                uploadInitCode(contract),
                contractCreate(contract).gas(600_000),
                tokenCreate(uniqToken)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0)
                        .supplyKey(MULTI_KEY)
                        .treasury(TOKEN_TREASURY),
                mintToken(uniqToken, List.of(firstMeta, secondMeta, thirdMeta)),
                getAccountInfo(TOKEN_TREASURY).logged(),
                atomicBatch(
                                tokenAssociate(contract, uniqToken).batchKey(BATCH_OPERATOR),
                                tokenDissociate(contract, uniqToken).batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                cryptoTransfer(TokenMovement.movingUnique(uniqToken, 1L).between(TOKEN_TREASURY, contract))
                        .hasKnownStatus(TOKEN_NOT_ASSOCIATED_TO_ACCOUNT));
    }

    private HapiSpecOperation[] associateAndDissociateNotValidBase() {
        final var account = "account";
        return new HapiSpecOperation[] {
            newKeyNamed(SIMPLE),
            tokenCreate("a").decimals(1),
            cryptoCreate(account).key(SIMPLE).balance(0L)
        };
    }

    @HapiTest
    final Stream<DynamicTest> associateInvalidAccount() {
        return hapiTest(flattened(
                associateAndDissociateNotValidBase(),
                atomicBatch(tokenAssociate("0.0.0", "a")
                                .signedBy(DEFAULT_PAYER)
                                .hasPrecheck(INVALID_ACCOUNT_ID)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasPrecheck(INVALID_ACCOUNT_ID)));
    }

    @HapiTest
    final Stream<DynamicTest> dissociateInvalidAccount() {
        return hapiTest(flattened(
                associateAndDissociateNotValidBase(),
                atomicBatch(tokenDissociate("0.0.0", "a")
                                .signedBy(DEFAULT_PAYER)
                                .hasPrecheck(INVALID_ACCOUNT_ID)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasPrecheck(INVALID_ACCOUNT_ID)));
    }

    @HapiTest
    final Stream<DynamicTest> associateInvalidToken() {
        final var account = "account";
        return hapiTest(flattened(
                associateAndDissociateNotValidBase(),
                atomicBatch(tokenAssociate(account, "0.0.0")
                                .hasPrecheck(INVALID_TOKEN_ID)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasPrecheck(INVALID_TOKEN_ID)));
    }

    @HapiTest
    final Stream<DynamicTest> dissociateInvalidToken() {
        final var account = "account";
        return hapiTest(flattened(
                associateAndDissociateNotValidBase(),
                atomicBatch(tokenDissociate(account, "0.0.0")
                                .hasPrecheck(INVALID_TOKEN_ID)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasPrecheck(INVALID_TOKEN_ID)));
    }

    @HapiTest
    final Stream<DynamicTest> dissociateDeletedToken() {
        final var account = "account";
        final var tokenToDelete = "anyToken";
        final var supplyKey = "supplyKey";
        final var adminKey = "adminKey";
        return hapiTest(
                newKeyNamed(supplyKey),
                newKeyNamed(adminKey),
                cryptoCreate(account),
                tokenCreate(tokenToDelete)
                        .treasury(DEFAULT_PAYER)
                        .tokenType(FUNGIBLE_COMMON)
                        .initialSupply(1000L)
                        .supplyKey(supplyKey)
                        .adminKey(adminKey),
                tokenAssociate(account, tokenToDelete),
                tokenDelete(tokenToDelete).signedByPayerAnd(adminKey),
                getAccountInfo(account).hasToken(relationshipWith(tokenToDelete)),
                atomicBatch(tokenDissociate(account, tokenToDelete).batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                getAccountInfo(account).hasNoTokenRelationship(tokenToDelete));
    }

    @HapiTest
    final Stream<DynamicTest> dissociateWithInvalidToken() {
        return hapiTest(withOpContext((spec, oplog) -> {
            final var bogusTokenId = TokenID.newBuilder().setTokenNum(9999L);
            spec.registry().saveTokenId("nonexistentToken", bogusTokenId.build());
            allRunFor(
                    spec,
                    cryptoCreate("acc"),
                    atomicBatch(tokenDissociate("acc", "nonexistentToken")
                                    .hasKnownStatus(TOKEN_NOT_ASSOCIATED_TO_ACCOUNT)
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR)
                            .hasKnownStatus(INNER_TRANSACTION_FAILED));
        }));
    }
}
