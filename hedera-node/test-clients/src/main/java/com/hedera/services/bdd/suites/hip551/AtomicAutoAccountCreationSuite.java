// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip551;

import static com.hedera.services.bdd.junit.TestTags.CRYPTO;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.accountWith;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAliasedAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.queries.crypto.ExpectedTokenRel.relationshipWith;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoUpdateAliased;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromToWithAlias;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedHbarFeeInheritingRoyaltyCollector;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.royaltyFeeWithFallback;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingHbar;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingUnique;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.childRecordsCheck;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SHAPE;
import static com.hedera.services.bdd.suites.HapiSuite.TOKEN_TREASURY;
import static com.hedera.services.bdd.suites.crypto.AutoAccountCreationSuite.A_TOKEN;
import static com.hedera.services.bdd.suites.crypto.AutoAccountCreationSuite.CIVILIAN;
import static com.hedera.services.bdd.suites.crypto.AutoAccountCreationSuite.EXPECTED_MULTI_TOKEN_TRANSFER_AUTO_CREATION_FEE;
import static com.hedera.services.bdd.suites.crypto.AutoAccountCreationSuite.NFT_CREATE;
import static com.hedera.services.bdd.suites.crypto.AutoAccountCreationSuite.NFT_FINITE_SUPPLY_TOKEN;
import static com.hedera.services.bdd.suites.crypto.AutoAccountCreationSuite.NFT_INFINITE_SUPPLY_TOKEN;
import static com.hedera.services.bdd.suites.crypto.AutoAccountCreationSuite.TOKEN_A_CREATE;
import static com.hedera.services.bdd.suites.crypto.AutoAccountCreationSuite.TRANSFER_TXN;
import static com.hedera.services.bdd.suites.crypto.AutoAccountCreationSuite.VALID_ALIAS;
import static com.hedera.services.bdd.suites.crypto.AutoAccountUpdateSuite.ALIAS;
import static com.hedera.services.bdd.suites.crypto.AutoAccountUpdateSuite.INITIAL_BALANCE;
import static com.hedera.services.bdd.suites.crypto.AutoCreateUtils.updateSpecFor;
import static com.hedera.services.bdd.suites.token.TokenAssociationSpecs.MULTI_KEY;
import static com.hedera.services.bdd.suites.token.TokenTransactSpecs.SPONSOR;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INNER_TRANSACTION_FAILED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_SENDER_ACCOUNT_BALANCE_FOR_CUSTOM_FEE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.PAYER_ACCOUNT_NOT_FOUND;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.TokenSupplyType.FINITE;
import static com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hederahashgraph.api.proto.java.Timestamp;
import com.hederahashgraph.api.proto.java.TokenSupplyType;
import com.hederahashgraph.api.proto.java.TokenType;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

// This test cases are direct copies of AutoAccountCreationSuite.
// The difference here is that we are wrapping the operations in an atomic batch to confirm the behavior is the same
@Tag(CRYPTO)
@HapiTestLifecycle
public class AtomicAutoAccountCreationSuite {

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(
                Map.of("atomicBatch.isEnabled", "true", "atomicBatch.maxNumberOfTransactions", "50"));
        testLifecycle.doAdhoc(cryptoCreate("batchOperator").balance(ONE_MILLION_HBARS));
    }

    @HapiTest
    final Stream<DynamicTest> aliasedPayerDoesntWork() {
        return hapiTest(
                newKeyNamed(ALIAS),
                newKeyNamed("alias2"),
                cryptoCreate("payer").balance(INITIAL_BALANCE * ONE_HBAR),
                cryptoTransfer(tinyBarsFromToWithAlias("payer", ALIAS, 2 * ONE_HUNDRED_HBARS)),
                withOpContext((spec, opLog) -> updateSpecFor(spec, ALIAS)),
                getAliasedAccountInfo(ALIAS)
                        .has(accountWith().expectedBalanceWithChargedUsd((2 * ONE_HUNDRED_HBARS), 0, 0)),
                // pay with aliased id
                atomicBatch(cryptoTransfer(tinyBarsFromToWithAlias(ALIAS, "alias2", ONE_HUNDRED_HBARS))
                                .payingWithAliased(ALIAS)
                                .batchKey("batchOperator"))
                        .payingWith("batchOperator")
                        .hasPrecheck(PAYER_ACCOUNT_NOT_FOUND),
                // pay with regular accountID
                atomicBatch(cryptoTransfer(tinyBarsFromToWithAlias(ALIAS, "alias2", ONE_HUNDRED_HBARS))
                                .payingWith(ALIAS)
                                .batchKey("batchOperator"))
                        .payingWith("batchOperator"));
    }

    @HapiTest
    final Stream<DynamicTest> canAutoCreateWithHbarAndTokenTransfers() {
        final var initialTokenSupply = 1000;
        return hapiTest(
                newKeyNamed(VALID_ALIAS),
                cryptoCreate("batchOperator"),
                cryptoCreate(TOKEN_TREASURY).balance(10 * ONE_HUNDRED_HBARS),
                cryptoCreate(CIVILIAN).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(2),
                tokenCreate(A_TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
                        .supplyType(FINITE)
                        .initialSupply(initialTokenSupply)
                        .maxSupply(10L * initialTokenSupply)
                        .treasury(TOKEN_TREASURY)
                        .via(TOKEN_A_CREATE),
                getTxnRecord(TOKEN_A_CREATE).hasNewTokenAssociation(A_TOKEN, TOKEN_TREASURY),
                tokenAssociate(CIVILIAN, A_TOKEN),
                cryptoTransfer(moving(10, A_TOKEN).between(TOKEN_TREASURY, CIVILIAN)),
                getAccountInfo(CIVILIAN).hasToken(relationshipWith(A_TOKEN).balance(10)),
                atomicBatch(cryptoTransfer(
                                        movingHbar(10L).between(CIVILIAN, VALID_ALIAS),
                                        moving(1, A_TOKEN).between(CIVILIAN, VALID_ALIAS))
                                .signedBy(DEFAULT_PAYER, CIVILIAN)
                                .batchKey("batchOperator"))
                        .payingWith("batchOperator"),
                getAliasedAccountInfo(VALID_ALIAS)
                        .has(accountWith().balance(10L))
                        .hasToken(relationshipWith(A_TOKEN)));
    }

    @HapiTest
    final Stream<DynamicTest> autoCreateWithNftFallBackFeeFails() {
        final var firstRoyaltyCollector = "firstRoyaltyCollector";
        return hapiTest(
                newKeyNamed(VALID_ALIAS),
                newKeyNamed(MULTI_KEY),
                newKeyNamed(VALID_ALIAS),
                cryptoCreate("batchOperator"),
                cryptoCreate(TOKEN_TREASURY).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(2),
                cryptoCreate(firstRoyaltyCollector).maxAutomaticTokenAssociations(100),
                tokenCreate(NFT_INFINITE_SUPPLY_TOKEN)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .adminKey(MULTI_KEY)
                        .supplyKey(MULTI_KEY)
                        .supplyType(TokenSupplyType.INFINITE)
                        .initialSupply(0)
                        .treasury(TOKEN_TREASURY)
                        .withCustom(royaltyFeeWithFallback(
                                1, 20, fixedHbarFeeInheritingRoyaltyCollector(1), firstRoyaltyCollector))
                        .via(NFT_CREATE),
                mintToken(
                        NFT_INFINITE_SUPPLY_TOKEN,
                        List.of(
                                ByteString.copyFromUtf8("a"),
                                ByteString.copyFromUtf8("b"),
                                ByteString.copyFromUtf8("c"),
                                ByteString.copyFromUtf8("d"),
                                ByteString.copyFromUtf8("e"))),
                cryptoCreate(CIVILIAN).balance(1000 * ONE_HBAR).maxAutomaticTokenAssociations(2),
                cryptoCreate("dummy").balance(10 * ONE_HBAR),
                cryptoCreate(SPONSOR).balance(ONE_MILLION_HBARS).maxAutomaticTokenAssociations(10),
                cryptoTransfer(movingUnique(NFT_INFINITE_SUPPLY_TOKEN, 1L, 2L).between(TOKEN_TREASURY, SPONSOR)),
                getAccountInfo(SPONSOR).hasToken(relationshipWith(NFT_INFINITE_SUPPLY_TOKEN)),
                // auto creating an account using a nft with fallback royalty fee fails
                atomicBatch(cryptoTransfer(movingUnique(NFT_INFINITE_SUPPLY_TOKEN, 1, 2)
                                        .between(SPONSOR, VALID_ALIAS))
                                .payingWith(CIVILIAN)
                                .signedBy(CIVILIAN, SPONSOR, VALID_ALIAS)
                                .batchKey("batchOperator")
                                .hasKnownStatus(INSUFFICIENT_SENDER_ACCOUNT_BALANCE_FOR_CUSTOM_FEE))
                        .payingWith("batchOperator")
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                getAccountInfo(SPONSOR).hasOwnedNfts(2).hasToken(relationshipWith(NFT_INFINITE_SUPPLY_TOKEN)),
                // But transferring this NFT to a known alias with hbar in it works
                cryptoTransfer(tinyBarsFromToWithAlias(SPONSOR, VALID_ALIAS, 10 * ONE_HBAR))
                        .payingWith(CIVILIAN)
                        .signedBy(CIVILIAN, SPONSOR, VALID_ALIAS)
                        .via(TRANSFER_TXN),
                withOpContext((spec, opLog) -> updateSpecFor(spec, VALID_ALIAS)),
                getTxnRecord(TRANSFER_TXN).andAllChildRecords().hasNonStakingChildRecordCount(1),
                cryptoUpdateAliased(VALID_ALIAS).maxAutomaticAssociations(10).signedBy(VALID_ALIAS, DEFAULT_PAYER),
                cryptoTransfer(movingUnique(NFT_INFINITE_SUPPLY_TOKEN, 1, 2).between(SPONSOR, VALID_ALIAS))
                        .payingWith(SPONSOR)
                        .fee(10 * ONE_HBAR)
                        .signedBy(SPONSOR, VALID_ALIAS),
                getAliasedAccountInfo(VALID_ALIAS).hasOwnedNfts(2));
    }

    @HapiTest
    final Stream<DynamicTest> canAutoCreateWithNftTransfersToAlias() {
        final var civilianBal = 10 * ONE_HBAR;
        final var multiNftTransfer = "multiNftTransfer";

        return hapiTest(
                newKeyNamed(VALID_ALIAS),
                newKeyNamed(MULTI_KEY),
                newKeyNamed(VALID_ALIAS),
                cryptoCreate(TOKEN_TREASURY).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(2),
                tokenCreate(NFT_INFINITE_SUPPLY_TOKEN)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .adminKey(MULTI_KEY)
                        .supplyKey(MULTI_KEY)
                        .supplyType(TokenSupplyType.INFINITE)
                        .initialSupply(0)
                        .treasury(TOKEN_TREASURY)
                        .via(NFT_CREATE),
                tokenCreate(NFT_FINITE_SUPPLY_TOKEN)
                        .supplyType(FINITE)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .treasury(TOKEN_TREASURY)
                        .maxSupply(12L)
                        .supplyKey(MULTI_KEY)
                        .adminKey(MULTI_KEY)
                        .initialSupply(0L),
                mintToken(
                        NFT_INFINITE_SUPPLY_TOKEN,
                        List.of(
                                ByteString.copyFromUtf8("a"),
                                ByteString.copyFromUtf8("b"),
                                ByteString.copyFromUtf8("c"),
                                ByteString.copyFromUtf8("d"),
                                ByteString.copyFromUtf8("e"))),
                mintToken(
                        NFT_FINITE_SUPPLY_TOKEN,
                        List.of(
                                ByteString.copyFromUtf8("a"),
                                ByteString.copyFromUtf8("b"),
                                ByteString.copyFromUtf8("c"),
                                ByteString.copyFromUtf8("d"),
                                ByteString.copyFromUtf8("e"))),
                cryptoCreate(CIVILIAN).balance(civilianBal),
                tokenAssociate(CIVILIAN, NFT_FINITE_SUPPLY_TOKEN, NFT_INFINITE_SUPPLY_TOKEN),
                atomicBatch(cryptoTransfer(
                                        movingUnique(NFT_FINITE_SUPPLY_TOKEN, 3L, 4L)
                                                .between(TOKEN_TREASURY, CIVILIAN),
                                        movingUnique(NFT_INFINITE_SUPPLY_TOKEN, 1L, 2L)
                                                .between(TOKEN_TREASURY, CIVILIAN))
                                .batchKey("batchOperator"))
                        .payingWith("batchOperator"),
                getAccountInfo(CIVILIAN)
                        .hasToken(relationshipWith(NFT_FINITE_SUPPLY_TOKEN))
                        .hasToken(relationshipWith(NFT_INFINITE_SUPPLY_TOKEN))
                        .has(accountWith().balance(civilianBal)),
                atomicBatch(cryptoTransfer(
                                        movingUnique(NFT_FINITE_SUPPLY_TOKEN, 3, 4)
                                                .between(CIVILIAN, VALID_ALIAS),
                                        movingUnique(NFT_INFINITE_SUPPLY_TOKEN, 1, 2)
                                                .between(CIVILIAN, VALID_ALIAS))
                                .via(multiNftTransfer)
                                .payingWith(CIVILIAN)
                                .signedBy(CIVILIAN, VALID_ALIAS)
                                .batchKey("batchOperator"))
                        .payingWith("batchOperator"),
                getTxnRecord(multiNftTransfer)
                        .andAllChildRecords()
                        .hasPriority(recordWith().autoAssociationCount(2))
                        .hasNonStakingChildRecordCount(1),
                childRecordsCheck(
                        multiNftTransfer,
                        SUCCESS,
                        recordWith().status(SUCCESS).fee(EXPECTED_MULTI_TOKEN_TRANSFER_AUTO_CREATION_FEE)),
                getAliasedAccountInfo(VALID_ALIAS)
                        .has(accountWith().balance(0).maxAutoAssociations(-1).ownedNfts(4)));
    }

    @HapiTest
    final Stream<DynamicTest> canAutoCreateWithNftTransferToEvmAddress() {
        final var civilianBal = 10 * ONE_HBAR;
        final var nftTransfer = "multiNftTransfer";
        final AtomicReference<Timestamp> parentConsTime = new AtomicReference<>();

        return hapiTest(
                newKeyNamed(MULTI_KEY),
                newKeyNamed(VALID_ALIAS).shape(SECP_256K1_SHAPE),
                cryptoCreate(TOKEN_TREASURY).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(2),
                tokenCreate(NFT_INFINITE_SUPPLY_TOKEN)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .adminKey(MULTI_KEY)
                        .supplyKey(MULTI_KEY)
                        .supplyType(TokenSupplyType.INFINITE)
                        .initialSupply(0)
                        .treasury(TOKEN_TREASURY)
                        .via(NFT_CREATE),
                mintToken(NFT_INFINITE_SUPPLY_TOKEN, List.of(ByteString.copyFromUtf8("a"))),
                cryptoCreate(CIVILIAN).balance(civilianBal),
                tokenAssociate(CIVILIAN, NFT_INFINITE_SUPPLY_TOKEN),
                cryptoTransfer(movingUnique(NFT_INFINITE_SUPPLY_TOKEN, 1L).between(TOKEN_TREASURY, CIVILIAN)),
                getAccountInfo(CIVILIAN)
                        .hasToken(relationshipWith(NFT_INFINITE_SUPPLY_TOKEN))
                        .has(accountWith().balance(civilianBal)),
                // Auto-creation so, it will have -1 as max auto-associations.
                // Then auto-associated with the EVM address.
                cryptoTransfer(movingUnique(NFT_INFINITE_SUPPLY_TOKEN, 1).between(CIVILIAN, VALID_ALIAS))
                        .via(nftTransfer)
                        .payingWith(CIVILIAN)
                        .signedBy(CIVILIAN, VALID_ALIAS),
                getTxnRecord(nftTransfer)
                        .exposingTo(record -> parentConsTime.set(record.getConsensusTimestamp()))
                        .andAllChildRecords()
                        .hasNonStakingChildRecordCount(1)
                        .hasPriority(recordWith().autoAssociationCount(1))
                        .logged(),
                sourcing(() -> childRecordsCheck(
                        nftTransfer,
                        SUCCESS,
                        recordWith().status(SUCCESS).consensusTimeImpliedByOffset(parentConsTime.get(), -1))));
    }
}
