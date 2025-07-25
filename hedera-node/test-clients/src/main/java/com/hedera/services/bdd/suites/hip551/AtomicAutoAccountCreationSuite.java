// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip551;

import static com.hedera.node.app.hapi.utils.EthSigsUtils.recoverAddressFromPubKey;
import static com.hedera.services.bdd.junit.TestTags.CRYPTO;
import static com.hedera.services.bdd.spec.HapiPropertySource.asAccountString;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.accountWith;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.includingFungibleMovement;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAliasedAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAutoCreatedAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getReceipt;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.queries.crypto.ExpectedTokenRel.relationshipWith;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.isEndOfStakingPeriodRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoUpdateAliased;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.sortedCryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromAccountToAlias;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromToWithAlias;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedHbarFeeInheritingRoyaltyCollector;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.royaltyFeeWithFallback;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingHbar;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingUnique;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.assertionsHold;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.assumingNoStakingChildRecordCausesMaxChildRecordsExceeded;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.childRecordsCheck;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.logIt;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.FUNDING;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SHAPE;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SOURCE_KEY;
import static com.hedera.services.bdd.suites.HapiSuite.THREE_MONTHS_IN_SECONDS;
import static com.hedera.services.bdd.suites.HapiSuite.TOKEN_TREASURY;
import static com.hedera.services.bdd.suites.contract.Utils.aaWith;
import static com.hedera.services.bdd.suites.contract.Utils.asSolidityAddress;
import static com.hedera.services.bdd.suites.crypto.AutoAccountCreationSuite.ALIAS;
import static com.hedera.services.bdd.suites.crypto.AutoAccountCreationSuite.ALIAS_2;
import static com.hedera.services.bdd.suites.crypto.AutoAccountCreationSuite.AUTO_MEMO;
import static com.hedera.services.bdd.suites.crypto.AutoAccountCreationSuite.A_TOKEN;
import static com.hedera.services.bdd.suites.crypto.AutoAccountCreationSuite.B_TOKEN;
import static com.hedera.services.bdd.suites.crypto.AutoAccountCreationSuite.CIVILIAN;
import static com.hedera.services.bdd.suites.crypto.AutoAccountCreationSuite.EXPECTED_ASSOCIATION_FEE;
import static com.hedera.services.bdd.suites.crypto.AutoAccountCreationSuite.EXPECTED_HBAR_TRANSFER_AUTO_CREATION_FEE;
import static com.hedera.services.bdd.suites.crypto.AutoAccountCreationSuite.EXPECTED_MULTI_TOKEN_TRANSFER_AUTO_CREATION_FEE;
import static com.hedera.services.bdd.suites.crypto.AutoAccountCreationSuite.EXPECTED_SINGLE_TOKEN_TRANSFER_AUTO_CREATE_FEE;
import static com.hedera.services.bdd.suites.crypto.AutoAccountCreationSuite.HBAR_XFER;
import static com.hedera.services.bdd.suites.crypto.AutoAccountCreationSuite.INITIAL_BALANCE;
import static com.hedera.services.bdd.suites.crypto.AutoAccountCreationSuite.LAZY_CREATE_SPONSOR;
import static com.hedera.services.bdd.suites.crypto.AutoAccountCreationSuite.LAZY_MEMO;
import static com.hedera.services.bdd.suites.crypto.AutoAccountCreationSuite.NFT_CREATE;
import static com.hedera.services.bdd.suites.crypto.AutoAccountCreationSuite.NFT_FINITE_SUPPLY_TOKEN;
import static com.hedera.services.bdd.suites.crypto.AutoAccountCreationSuite.NFT_INFINITE_SUPPLY_TOKEN;
import static com.hedera.services.bdd.suites.crypto.AutoAccountCreationSuite.PARTY;
import static com.hedera.services.bdd.suites.crypto.AutoAccountCreationSuite.PAYER_1;
import static com.hedera.services.bdd.suites.crypto.AutoAccountCreationSuite.TOKEN_A_CREATE;
import static com.hedera.services.bdd.suites.crypto.AutoAccountCreationSuite.TOKEN_B_CREATE;
import static com.hedera.services.bdd.suites.crypto.AutoAccountCreationSuite.TRANSFER_TXN;
import static com.hedera.services.bdd.suites.crypto.AutoAccountCreationSuite.VALID_25519_ALIAS;
import static com.hedera.services.bdd.suites.crypto.AutoAccountCreationSuite.VALID_ALIAS;
import static com.hedera.services.bdd.suites.crypto.AutoAccountCreationSuite.assertAliasBalanceAndFeeInChildRecord;
import static com.hedera.services.bdd.suites.crypto.AutoCreateUtils.updateSpecFor;
import static com.hedera.services.bdd.suites.token.TokenAssociationSpecs.MULTI_KEY;
import static com.hedera.services.bdd.suites.token.TokenTransactSpecs.SPONSOR;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INNER_TRANSACTION_FAILED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_ACCOUNT_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_PAYER_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_SENDER_ACCOUNT_BALANCE_FOR_CUSTOM_FEE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ALIAS_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.PAYER_ACCOUNT_NOT_FOUND;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.TokenSupplyType.FINITE;
import static com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.keys.KeyShape;
import com.hedera.services.bdd.suites.contract.Utils;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.Timestamp;
import com.hederahashgraph.api.proto.java.TokenSupplyType;
import com.hederahashgraph.api.proto.java.TokenType;
import com.hederahashgraph.api.proto.java.TransferList;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
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

    @HapiTest
    final Stream<DynamicTest> multipleTokenTransfersSucceed() {
        final var initialTokenSupply = 1000;
        final var multiTokenXfer = "multiTokenXfer";

        return hapiTest(
                newKeyNamed(VALID_ALIAS),
                cryptoCreate(TOKEN_TREASURY).balance(ONE_HUNDRED_HBARS),
                tokenCreate(A_TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
                        .initialSupply(initialTokenSupply)
                        .treasury(TOKEN_TREASURY)
                        .via(TOKEN_A_CREATE),
                getTxnRecord(TOKEN_A_CREATE)
                        .hasNewTokenAssociation(A_TOKEN, TOKEN_TREASURY)
                        .logged(),
                tokenCreate(B_TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
                        .initialSupply(initialTokenSupply)
                        .treasury(TOKEN_TREASURY)
                        .via(TOKEN_B_CREATE),
                getTxnRecord(TOKEN_A_CREATE)
                        .hasNewTokenAssociation(A_TOKEN, TOKEN_TREASURY)
                        .logged(),
                getTxnRecord(TOKEN_B_CREATE).hasNewTokenAssociation(B_TOKEN, TOKEN_TREASURY),
                cryptoCreate(CIVILIAN).balance(10 * ONE_HBAR).maxAutomaticTokenAssociations(2),
                atomicBatch(cryptoTransfer(
                                        moving(100, A_TOKEN).between(TOKEN_TREASURY, CIVILIAN),
                                        moving(100, B_TOKEN).between(TOKEN_TREASURY, CIVILIAN))
                                .via("transferAToSponsor")
                                .batchKey("batchOperator"))
                        .payingWith("batchOperator"),
                getAccountInfo(TOKEN_TREASURY)
                        .hasToken(relationshipWith(B_TOKEN).balance(900)),
                getAccountInfo(TOKEN_TREASURY)
                        .hasToken(relationshipWith(A_TOKEN).balance(900)),
                getAccountInfo(CIVILIAN).hasToken(relationshipWith(A_TOKEN).balance(100)),
                getAccountInfo(CIVILIAN).hasToken(relationshipWith(B_TOKEN).balance(100)),

                /* --- transfer same token type to alias --- */
                atomicBatch(cryptoTransfer(
                                        moving(10, A_TOKEN).between(CIVILIAN, VALID_ALIAS),
                                        moving(10, B_TOKEN).between(CIVILIAN, VALID_ALIAS))
                                .via(multiTokenXfer)
                                .payingWith(CIVILIAN)
                                .signedBy(CIVILIAN, VALID_ALIAS)
                                .batchKey("batchOperator"))
                        .payingWith("batchOperator"),
                withOpContext((spec, opLog) -> updateSpecFor(spec, VALID_ALIAS)),
                // auto-creation and token association
                getTxnRecord(multiTokenXfer)
                        .andAllChildRecords()
                        .hasNonStakingChildRecordCount(1)
                        .hasPriority(recordWith()
                                .status(SUCCESS)
                                .tokenTransfers(includingFungibleMovement(
                                        moving(10, A_TOKEN).to(VALID_ALIAS)))
                                .tokenTransfers(includingFungibleMovement(
                                        moving(10, B_TOKEN).to(VALID_ALIAS)))
                                .autoAssociationCount(2))
                        .logged(),
                childRecordsCheck(
                        multiTokenXfer,
                        SUCCESS,
                        recordWith().status(SUCCESS).fee(EXPECTED_MULTI_TOKEN_TRANSFER_AUTO_CREATION_FEE)),
                getAliasedAccountInfo(VALID_ALIAS)
                        .hasToken(relationshipWith(A_TOKEN).balance(10))
                        .hasToken(relationshipWith(B_TOKEN).balance(10))
                        .has(accountWith().balance(0L).maxAutoAssociations(-1)),
                getAccountInfo(CIVILIAN)
                        .hasToken(relationshipWith(A_TOKEN).balance(90))
                        .hasToken(relationshipWith(B_TOKEN).balance(90))
                        .has(accountWith().balanceLessThan(10 * ONE_HBAR)),
                /* --- transfer token to created alias */
                atomicBatch(cryptoTransfer(moving(10, B_TOKEN).between(CIVILIAN, VALID_ALIAS))
                                .via("newXfer")
                                .payingWith(CIVILIAN)
                                .signedBy(CIVILIAN, VALID_ALIAS, TOKEN_TREASURY)
                                .batchKey("batchOperator"))
                        .payingWith("batchOperator"),
                getTxnRecord("newXfer")
                        .andAllChildRecords()
                        .hasNonStakingChildRecordCount(0)
                        .logged(),
                getAliasedAccountInfo(VALID_ALIAS)
                        .hasToken(relationshipWith(A_TOKEN).balance(10))
                        .hasToken(relationshipWith(B_TOKEN).balance(20)));
    }

    @HapiTest
    final Stream<DynamicTest> payerBalanceIsReflectsAllChangesBeforeFeeCharging() {
        final var secondAliasKey = "secondAlias";
        final var secondPayer = "secondPayer";
        final AtomicLong totalAutoCreationFees = new AtomicLong();

        return hapiTest(
                newKeyNamed(VALID_ALIAS),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(A_TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
                        .initialSupply(1000)
                        .treasury(TOKEN_TREASURY),
                cryptoCreate(CIVILIAN).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(1),
                atomicBatch(
                                cryptoTransfer(moving(100, A_TOKEN).between(TOKEN_TREASURY, CIVILIAN))
                                        .batchKey("batchOperator"),
                                cryptoTransfer(
                                                moving(10, A_TOKEN).between(CIVILIAN, VALID_ALIAS),
                                                movingHbar(1).between(CIVILIAN, FUNDING))
                                        .fee(50 * ONE_HBAR)
                                        .payingWith(CIVILIAN)
                                        .signedBy(CIVILIAN)
                                        .batchKey("batchOperator"))
                        .payingWith("batchOperator"),
                getAccountBalance(CIVILIAN)
                        .exposingBalanceTo(balance -> totalAutoCreationFees.set(ONE_HUNDRED_HBARS - balance - 1)),
                logIt(spec -> String.format("Total auto-creation fees: %d", totalAutoCreationFees.get())),
                sourcing(() -> cryptoCreate(secondPayer)
                        .maxAutomaticTokenAssociations(1)
                        .balance(totalAutoCreationFees.get())),
                cryptoTransfer(moving(100, A_TOKEN).between(TOKEN_TREASURY, secondPayer)),
                newKeyNamed(secondAliasKey),
                sourcing(() -> atomicBatch(cryptoTransfer(
                                        moving(10, A_TOKEN).between(secondPayer, secondAliasKey),
                                        movingHbar(1).between(secondPayer, FUNDING))
                                .fee(totalAutoCreationFees.get() - 2)
                                .payingWith(secondPayer)
                                .signedBy(secondPayer)
                                .hasKnownStatusFrom(INSUFFICIENT_PAYER_BALANCE, INSUFFICIENT_ACCOUNT_BALANCE)
                                .batchKey("batchOperator"))
                        .payingWith("batchOperator")
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)),
                getAccountBalance(secondPayer)
                        .hasTinyBars(spec ->
                                // Should only be charged a few hundred thousand
                                // tinybar at most
                                balance -> ((totalAutoCreationFees.get() - balance) > 500_000L)
                                        ? Optional.empty()
                                        : Optional.of("Payer was" + " over-charged!")));
    }

    @HapiTest
    final Stream<DynamicTest> canAutoCreateWithFungibleTokenTransfersToAlias() {
        final var initialTokenSupply = 1000;
        final var sameTokenXfer = "sameTokenXfer";
        // The expected (network + service) fee for two token transfers to a receiver
        // with no auto-creation; note it is approximate because the fee will vary slightly
        // with the size of the sig map, depending on the lengths of the public key prefixes required
        final long approxTransferFee = 1162100L;

        return hapiTest(
                newKeyNamed(VALID_ALIAS),
                cryptoCreate(TOKEN_TREASURY).balance(ONE_HUNDRED_HBARS),
                tokenCreate(A_TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
                        .initialSupply(initialTokenSupply)
                        .treasury(TOKEN_TREASURY)
                        .via(TOKEN_A_CREATE),
                tokenCreate(B_TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
                        .initialSupply(initialTokenSupply)
                        .treasury(TOKEN_TREASURY)
                        .via(TOKEN_B_CREATE),
                getTxnRecord(TOKEN_A_CREATE).hasNewTokenAssociation(A_TOKEN, TOKEN_TREASURY),
                getTxnRecord(TOKEN_B_CREATE).hasNewTokenAssociation(B_TOKEN, TOKEN_TREASURY),
                cryptoCreate(CIVILIAN).balance(10 * ONE_HBAR).maxAutomaticTokenAssociations(2),
                atomicBatch(cryptoTransfer(
                                        moving(100, A_TOKEN).between(TOKEN_TREASURY, CIVILIAN),
                                        moving(100, B_TOKEN).between(TOKEN_TREASURY, CIVILIAN))
                                .via("transferAToSponsor")
                                .batchKey("batchOperator"))
                        .payingWith("batchOperator"),
                getAccountInfo(TOKEN_TREASURY)
                        .hasToken(relationshipWith(B_TOKEN).balance(900)),
                getAccountInfo(TOKEN_TREASURY)
                        .hasToken(relationshipWith(A_TOKEN).balance(900)),
                getAccountInfo(CIVILIAN).hasToken(relationshipWith(A_TOKEN).balance(100)),
                getAccountInfo(CIVILIAN).hasToken(relationshipWith(B_TOKEN).balance(100)),

                /* --- transfer the same token type to alias --- */
                atomicBatch(cryptoTransfer(
                                        moving(10, A_TOKEN).between(CIVILIAN, VALID_ALIAS),
                                        moving(10, A_TOKEN).between(TOKEN_TREASURY, VALID_ALIAS))
                                .via(sameTokenXfer)
                                .payingWith(CIVILIAN)
                                .signedBy(CIVILIAN, VALID_ALIAS, TOKEN_TREASURY)
                                .batchKey("batchOperator"))
                        .payingWith("batchOperator"),
                getTxnRecord(sameTokenXfer)
                        .andAllChildRecords()
                        .hasNonStakingChildRecordCount(1)
                        .hasPriority(recordWith().autoAssociationCount(1))
                        .logged(),
                childRecordsCheck(
                        sameTokenXfer,
                        SUCCESS,
                        recordWith().status(SUCCESS).fee(EXPECTED_SINGLE_TOKEN_TRANSFER_AUTO_CREATE_FEE)),
                getAliasedAccountInfo(VALID_ALIAS)
                        .hasToken(relationshipWith(A_TOKEN).balance(20)),
                getAccountInfo(CIVILIAN)
                        .hasToken(relationshipWith(A_TOKEN).balance(90))
                        .has(accountWith().balanceLessThan(10 * ONE_HBAR)),
                assertionsHold((spec, opLog) -> {
                    final var lookup = getTxnRecord(sameTokenXfer)
                            .andAllChildRecords()
                            .hasNonStakingChildRecordCount(1)
                            .hasPriority(recordWith().autoAssociationCount(1))
                            .hasNoAliasInChildRecord(0)
                            .logged();
                    allRunFor(spec, lookup);
                    final var sponsor = spec.registry().getAccountID(DEFAULT_PAYER);
                    final var payer = spec.registry().getAccountID(CIVILIAN);
                    final var parent = lookup.getResponseRecord();
                    final var child = lookup.getFirstNonStakingChildRecord();
                    assertAliasBalanceAndFeeInChildRecord(
                            parent, child, sponsor, payer, 0L, approxTransferFee, EXPECTED_ASSOCIATION_FEE);
                }),
                /* --- transfer another token to create alias.
                Alias created will have -1 as max-auto associations */
                cryptoTransfer(moving(10, B_TOKEN).between(CIVILIAN, VALID_ALIAS))
                        .via("failedXfer")
                        .payingWith(CIVILIAN)
                        .signedBy(CIVILIAN, VALID_ALIAS, TOKEN_TREASURY));
    }

    @HapiTest
    final Stream<DynamicTest> hollowAccountCreationWithCryptoTransfer() {
        final var initialTokenSupply = 1000;
        final AtomicReference<ByteString> evmAddress = new AtomicReference<>();
        return hapiTest(
                newKeyNamed(MULTI_KEY),
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(LAZY_CREATE_SPONSOR).balance(INITIAL_BALANCE * ONE_HBAR),
                cryptoCreate(TOKEN_TREASURY).balance(10 * ONE_HUNDRED_HBARS),
                tokenCreate(A_TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
                        .supplyType(FINITE)
                        .initialSupply(initialTokenSupply)
                        .maxSupply(10L * initialTokenSupply)
                        .treasury(TOKEN_TREASURY)
                        .via(TOKEN_A_CREATE),
                tokenCreate(NFT_INFINITE_SUPPLY_TOKEN)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .adminKey(MULTI_KEY)
                        .supplyKey(MULTI_KEY)
                        .supplyType(TokenSupplyType.INFINITE)
                        .initialSupply(0)
                        .treasury(TOKEN_TREASURY)
                        .via(NFT_CREATE),
                mintToken(
                        NFT_INFINITE_SUPPLY_TOKEN, List.of(ByteString.copyFromUtf8("a"), ByteString.copyFromUtf8("b"))),
                cryptoCreate(CIVILIAN).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(2),
                tokenAssociate(CIVILIAN, A_TOKEN, NFT_INFINITE_SUPPLY_TOKEN),
                cryptoTransfer(
                        moving(10, A_TOKEN).between(TOKEN_TREASURY, CIVILIAN),
                        movingUnique(NFT_INFINITE_SUPPLY_TOKEN, 1L, 2L).between(TOKEN_TREASURY, CIVILIAN)),
                withOpContext((spec, opLog) -> {
                    final var ecdsaKey = spec.registry()
                            .getKey(SECP_256K1_SOURCE_KEY)
                            .getECDSASecp256K1()
                            .toByteArray();
                    final var evmAddressBytes = ByteString.copyFrom(recoverAddressFromPubKey(ecdsaKey));
                    evmAddress.set(evmAddressBytes);
                }),
                withOpContext((spec, opLog) -> {
                    /* hollow account created with transfer as expected */
                    final var cryptoTransferWithLazyCreate = atomicBatch(cryptoTransfer(
                                            movingHbar(ONE_HUNDRED_HBARS)
                                                    .between(LAZY_CREATE_SPONSOR, evmAddress.get()),
                                            moving(5, A_TOKEN).between(CIVILIAN, evmAddress.get()),
                                            movingUnique(NFT_INFINITE_SUPPLY_TOKEN, 1L)
                                                    .between(CIVILIAN, evmAddress.get()))
                                    .hasKnownStatus(SUCCESS)
                                    .batchKey("batchOperator")
                                    .via(TRANSFER_TXN))
                            .payingWith("batchOperator");

                    final var getHollowAccountInfoAfterCreation = getAliasedAccountInfo(evmAddress.get())
                            .hasToken(relationshipWith(A_TOKEN).balance(5))
                            .hasToken(
                                    relationshipWith(NFT_INFINITE_SUPPLY_TOKEN).balance(1))
                            .has(accountWith()
                                    .hasEmptyKey()
                                    .noAlias()
                                    .expectedBalanceWithChargedUsd(ONE_HUNDRED_HBARS, 0, 0)
                                    .autoRenew(THREE_MONTHS_IN_SECONDS)
                                    .receiverSigReq(false)
                                    .memo(LAZY_MEMO));

                    allRunFor(spec, cryptoTransferWithLazyCreate, getHollowAccountInfoAfterCreation);

                    /* transfers of hbar, fungible and non-fungible tokens to the hollow account should succeed */
                    final var batchTransfers = atomicBatch(
                                    // hbar transfer
                                    cryptoTransfer(tinyBarsFromTo(CIVILIAN, evmAddress.get(), ONE_HUNDRED_HBARS))
                                            .batchKey("batchOperator"),
                                    // fungible token transfer
                                    cryptoTransfer(moving(5, A_TOKEN).between(CIVILIAN, evmAddress.get()))
                                            .signedBy(DEFAULT_PAYER, CIVILIAN)
                                            .batchKey("batchOperator"),
                                    // nft transfer
                                    cryptoTransfer(movingUnique(NFT_INFINITE_SUPPLY_TOKEN, 2L)
                                                    .between(CIVILIAN, evmAddress.get()))
                                            .signedBy(DEFAULT_PAYER, CIVILIAN)
                                            .batchKey("batchOperator"))
                            .payingWith("batchOperator");

                    final var getHollowAccountInfoAfterTransfers = getAliasedAccountInfo(evmAddress.get())
                            .hasToken(relationshipWith(A_TOKEN).balance(10))
                            .hasToken(
                                    relationshipWith(NFT_INFINITE_SUPPLY_TOKEN).balance(2))
                            .has(accountWith()
                                    .hasEmptyKey()
                                    .noAlias()
                                    .expectedBalanceWithChargedUsd(2 * ONE_HUNDRED_HBARS, 0, 0));

                    allRunFor(spec, batchTransfers, getHollowAccountInfoAfterTransfers);
                }));
    }

    @HapiTest
    final Stream<DynamicTest> failureAfterHollowAccountCreationReclaimsAlias() {
        final var underfunded = "underfunded";
        final var secondTransferTxn = "SecondTransferTxn";
        final AtomicReference<byte[]> targetAddress = new AtomicReference<>();
        return hapiTest(
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(LAZY_CREATE_SPONSOR).balance(INITIAL_BALANCE * ONE_HBAR),
                cryptoCreate(underfunded).balance(10 * ONE_HBAR),
                withOpContext((spec, opLog) -> {
                    final var ecdsaKey = spec.registry()
                            .getKey(SECP_256K1_SOURCE_KEY)
                            .getECDSASecp256K1()
                            .toByteArray();
                    final var evmAddress = recoverAddressFromPubKey(ecdsaKey);
                    targetAddress.set(evmAddress);
                    final var controlledOp = atomicBatch(cryptoTransfer((sameSpec, b) -> {
                                        final var sponsorId = spec.registry().getAccountID(LAZY_CREATE_SPONSOR);
                                        final var underfundedId =
                                                spec.registry().getAccountID(underfunded);
                                        final var funding = spec.registry().getAccountID(FUNDING);
                                        b.setTransfers(TransferList.newBuilder()
                                                .addAccountAmounts(aaWith(sponsorId, -ONE_HUNDRED_HBARS))
                                                .addAccountAmounts(Utils.aaWith(spec, evmAddress, +ONE_HUNDRED_HBARS))
                                                .addAccountAmounts(aaWith(underfundedId, -ONE_HUNDRED_HBARS))
                                                .addAccountAmounts(aaWith(funding, +ONE_HUNDRED_HBARS))
                                                .build());
                                    })
                                    .hasKnownStatus(SUCCESS)
                                    .memo("QUESTIONABLE")
                                    .signedBy(DEFAULT_PAYER, LAZY_CREATE_SPONSOR, underfunded)
                                    .hasKnownStatus(INSUFFICIENT_ACCOUNT_BALANCE)
                                    .batchKey("batchOperator")
                                    .via(TRANSFER_TXN))
                            .payingWith("batchOperator")
                            .hasKnownStatus(INNER_TRANSACTION_FAILED);
                    allRunFor(spec, controlledOp);
                }),
                getAliasedAccountInfo(SECP_256K1_SOURCE_KEY).nodePayment(123).hasAnswerOnlyPrecheck(INVALID_ACCOUNT_ID),
                sourcing(() -> atomicBatch(cryptoTransfer(tinyBarsFromTo(
                                        LAZY_CREATE_SPONSOR,
                                        ByteString.copyFrom(targetAddress.get()),
                                        ONE_HUNDRED_HBARS))
                                .signedBy(DEFAULT_PAYER, LAZY_CREATE_SPONSOR)
                                .batchKey("batchOperator")
                                .via(secondTransferTxn))
                        .payingWith("batchOperator")),
                getAliasedAccountInfo(SECP_256K1_SOURCE_KEY).isHollow());
    }

    @HapiTest
    final Stream<DynamicTest> canGetBalanceAndInfoViaAlias() {
        final var ed25519SourceKey = "ed25519Alias";
        final var secp256k1SourceKey = "secp256k1Alias";
        final var secp256k1Shape = KeyShape.SECP256K1;
        final var ed25519Shape = KeyShape.ED25519;
        final var autoCreation = "autoCreation";

        return hapiTest(
                cryptoCreate(CIVILIAN).balance(ONE_HUNDRED_HBARS),
                newKeyNamed(ed25519SourceKey).shape(ed25519Shape),
                newKeyNamed(secp256k1SourceKey).shape(secp256k1Shape),
                atomicBatch(sortedCryptoTransfer(
                                        tinyBarsFromAccountToAlias(CIVILIAN, ed25519SourceKey, ONE_HUNDRED_HBARS),
                                        tinyBarsFromAccountToAlias(GENESIS, secp256k1SourceKey, ONE_HUNDRED_HBARS))
                                /* Sort the transfer list so the accounts are created in a predictable order (the
                                 * serialized bytes of an Ed25519 are always lexicographically prior to the serialized
                                 * bytes of a secp256k1 key, so now the first child record will _always_ be for the
                                 * ed25519 auto-creation). */
                                .payingWith(GENESIS)
                                .batchKey("batchOperator")
                                .via(autoCreation))
                        .payingWith("batchOperator"),
                withOpContext((spec, opLog) -> updateSpecFor(spec, ed25519SourceKey)),
                withOpContext((spec, opLog) -> updateSpecFor(spec, secp256k1SourceKey)),
                getTxnRecord(autoCreation)
                        .andAllChildRecords()
                        .hasNoAliasInChildRecord(0)
                        .hasNoAliasInChildRecord(1),
                getAutoCreatedAccountBalance(ed25519SourceKey).hasExpectedAccountID(),
                getAutoCreatedAccountBalance(secp256k1SourceKey).hasExpectedAccountID(),
                getAliasedAccountInfo(ed25519SourceKey)
                        .hasExpectedAliasKey()
                        .hasExpectedAccountID()
                        .has(accountWith().expectedBalanceWithChargedUsd(ONE_HUNDRED_HBARS, 0, 0)),
                getAliasedAccountInfo(secp256k1SourceKey)
                        .hasExpectedAliasKey()
                        .hasExpectedAccountID()
                        .has(accountWith().expectedBalanceWithChargedUsd(ONE_HUNDRED_HBARS, 0, 0)));
    }

    @HapiTest
    final Stream<DynamicTest> aliasCanBeUsedOnManyAccountsNotAsAlias() {
        final var payer = "payer";
        return hapiTest(
                /* have an alias key on other accounts and tokens not as alias */
                newKeyNamed(VALID_ALIAS),
                cryptoCreate(payer).key(VALID_ALIAS).balance(INITIAL_BALANCE * ONE_HBAR),
                tokenCreate(payer).adminKey(VALID_ALIAS),
                tokenCreate(payer).supplyKey(VALID_ALIAS),
                tokenCreate("a").treasury(payer),
                /* auto account is created */
                atomicBatch(cryptoTransfer(tinyBarsFromToWithAlias(payer, VALID_ALIAS, ONE_HUNDRED_HBARS))
                                .batchKey("batchOperator")
                                .via(TRANSFER_TXN))
                        .payingWith("batchOperator"),
                /* get transaction record and validate the child record has alias bytes as expected */
                getTxnRecord(TRANSFER_TXN)
                        .andAllChildRecords()
                        .hasNonStakingChildRecordCount(1)
                        .hasNoAliasInChildRecord(0),
                getAccountInfo(payer)
                        .has(accountWith()
                                .balance((INITIAL_BALANCE * ONE_HBAR) - ONE_HUNDRED_HBARS)
                                .noAlias()),
                getAliasedAccountInfo(VALID_ALIAS)
                        .has(accountWith()
                                .key(VALID_ALIAS)
                                .expectedBalanceWithChargedUsd(ONE_HUNDRED_HBARS, 0, 0)
                                .alias(VALID_ALIAS)
                                .autoRenew(THREE_MONTHS_IN_SECONDS)
                                .receiverSigReq(false)
                                .memo(AUTO_MEMO)));
    }

    @HapiTest
    final Stream<DynamicTest> accountCreatedIfAliasUsedAsPubKey() {
        return hapiTest(
                newKeyNamed(ALIAS),
                cryptoCreate(PAYER_1)
                        .balance(INITIAL_BALANCE * ONE_HBAR)
                        .key(ALIAS)
                        .signedBy(ALIAS, DEFAULT_PAYER),
                atomicBatch(cryptoTransfer(tinyBarsFromToWithAlias(PAYER_1, ALIAS, ONE_HUNDRED_HBARS))
                                .batchKey("batchOperator"))
                        .payingWith("batchOperator"),
                getAccountInfo(PAYER_1)
                        .has(accountWith()
                                .balance((INITIAL_BALANCE * ONE_HBAR) - ONE_HUNDRED_HBARS)
                                .noAlias()),
                getAliasedAccountInfo(ALIAS)
                        .has(accountWith()
                                .key(ALIAS)
                                .expectedBalanceWithChargedUsd(ONE_HUNDRED_HBARS, 0, 0)
                                .alias(ALIAS)
                                .autoRenew(THREE_MONTHS_IN_SECONDS)
                                .receiverSigReq(false)));
    }

    @HapiTest
    final Stream<DynamicTest> transferFromAliasToAlias() {
        return hapiTest(
                newKeyNamed(ALIAS),
                newKeyNamed(ALIAS_2),
                cryptoCreate(PAYER_1).balance(INITIAL_BALANCE * ONE_HBAR),
                cryptoTransfer(tinyBarsFromToWithAlias(PAYER_1, ALIAS, 2 * ONE_HUNDRED_HBARS))
                        .via("txn"),
                withOpContext((spec, opLog) -> updateSpecFor(spec, ALIAS)),
                getAliasedAccountInfo(ALIAS)
                        .has(accountWith().expectedBalanceWithChargedUsd((2 * ONE_HUNDRED_HBARS), 0, 0)),
                /* transfer from an alias that was auto created to a new alias, validate account is created */
                atomicBatch(cryptoTransfer(tinyBarsFromToWithAlias(ALIAS, ALIAS_2, ONE_HUNDRED_HBARS))
                                .batchKey("batchOperator"))
                        .payingWith("batchOperator"),
                getAliasedAccountInfo(ALIAS).has(accountWith().expectedBalanceWithChargedUsd(ONE_HUNDRED_HBARS, 0, 0)),
                getAliasedAccountInfo(ALIAS_2)
                        .has(accountWith().expectedBalanceWithChargedUsd(ONE_HUNDRED_HBARS, 0, 0)));
    }

    @HapiTest
    final Stream<DynamicTest> transferToAccountAutoCreatedUsingAccount() {
        return hapiTest(
                newKeyNamed(ALIAS),
                cryptoCreate(PAYER_1).balance(INITIAL_BALANCE * ONE_HBAR),
                cryptoTransfer(tinyBarsFromToWithAlias(PAYER_1, ALIAS, ONE_HUNDRED_HBARS))
                        .via("txn"),
                withOpContext((spec, opLog) -> updateSpecFor(spec, ALIAS)),
                /* get the account associated with alias and transfer */
                withOpContext((spec, opLog) -> {
                    final var aliasAccount = spec.registry()
                            .getAccountID(
                                    spec.registry().getKey(ALIAS).toByteString().toStringUtf8());

                    final var op = atomicBatch(cryptoTransfer(
                                            tinyBarsFromTo(PAYER_1, asAccountString(aliasAccount), ONE_HUNDRED_HBARS))
                                    .batchKey("batchOperator")
                                    .via(TRANSFER_TXN))
                            .payingWith("batchOperator");
                    final var op2 = getAccountInfo(PAYER_1)
                            .has(accountWith().balance((INITIAL_BALANCE * ONE_HBAR) - (2 * ONE_HUNDRED_HBARS)));
                    final var op3 = getAliasedAccountInfo(ALIAS)
                            .has(accountWith().expectedBalanceWithChargedUsd((2 * ONE_HUNDRED_HBARS), 0, 0));
                    allRunFor(spec, op, op2, op3);
                }));
    }

    @HapiTest
    final Stream<DynamicTest> transferToAccountAutoCreatedUsingAlias() {
        return hapiTest(
                newKeyNamed(ALIAS),
                cryptoCreate(PAYER_1).balance(INITIAL_BALANCE * ONE_HBAR),
                cryptoTransfer(tinyBarsFromToWithAlias(PAYER_1, ALIAS, ONE_HUNDRED_HBARS)),
                getAccountInfo(PAYER_1).has(accountWith().balance((INITIAL_BALANCE * ONE_HBAR) - ONE_HUNDRED_HBARS)),
                getAliasedAccountInfo(ALIAS).has(accountWith().expectedBalanceWithChargedUsd(ONE_HUNDRED_HBARS, 0, 0)),
                /* transfer using alias and not account number */
                atomicBatch(cryptoTransfer(tinyBarsFromToWithAlias(PAYER_1, ALIAS, ONE_HUNDRED_HBARS))
                                .batchKey("batchOperator")
                                .via(TRANSFER_TXN))
                        .payingWith("batchOperator"),
                getTxnRecord(TRANSFER_TXN).andAllChildRecords().hasNonStakingChildRecordCount(0),
                getAccountInfo(PAYER_1)
                        .has(accountWith().balance((INITIAL_BALANCE * ONE_HBAR) - (2 * ONE_HUNDRED_HBARS))),
                getAliasedAccountInfo(ALIAS)
                        .has(accountWith().expectedBalanceWithChargedUsd((2 * ONE_HUNDRED_HBARS), 0, 0)));
    }

    @HapiTest
    final Stream<DynamicTest> autoAccountCreationBadAlias() {
        final var invalidAlias = VALID_25519_ALIAS.substring(0, 10);

        return hapiTest(
                cryptoCreate(PAYER_1).balance(INITIAL_BALANCE * ONE_HBAR),
                atomicBatch(cryptoTransfer(tinyBarsFromTo(PAYER_1, invalidAlias, ONE_HUNDRED_HBARS))
                                .batchKey("batchOperator")
                                .hasKnownStatus(INVALID_ALIAS_KEY))
                        .payingWith("batchOperator")
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    @HapiTest
    final Stream<DynamicTest> autoAccountCreationsHappyPath() {
        final var creationTime = new AtomicLong();
        final long transferFee = 188608L;
        return hapiTest(
                newKeyNamed(VALID_ALIAS),
                cryptoCreate(CIVILIAN).balance(10 * ONE_HBAR),
                cryptoCreate(PAYER_1).balance(10 * ONE_HBAR),
                cryptoCreate(SPONSOR).balance(INITIAL_BALANCE * ONE_HBAR),
                atomicBatch(cryptoTransfer(
                                        tinyBarsFromToWithAlias(SPONSOR, VALID_ALIAS, ONE_HUNDRED_HBARS),
                                        tinyBarsFromToWithAlias(CIVILIAN, VALID_ALIAS, ONE_HBAR))
                                .batchKey("batchOperator")
                                .via(TRANSFER_TXN)
                                .payingWith(PAYER_1))
                        .payingWith("batchOperator"),
                getReceipt(TRANSFER_TXN).andAnyChildReceipts().hasChildAutoAccountCreations(1),
                getAccountInfo(SPONSOR)
                        .has(accountWith()
                                .balance((INITIAL_BALANCE * ONE_HBAR) - ONE_HUNDRED_HBARS)
                                .noAlias()),
                childRecordsCheck(
                        TRANSFER_TXN,
                        SUCCESS,
                        recordWith().status(SUCCESS).fee(EXPECTED_HBAR_TRANSFER_AUTO_CREATION_FEE)),
                assertionsHold((spec, opLog) -> {
                    final var lookup = getTxnRecord(TRANSFER_TXN)
                            .andAllChildRecords()
                            .hasNonStakingChildRecordCount(1)
                            .hasNoAliasInChildRecord(0);
                    allRunFor(spec, lookup);
                    final var sponsor = spec.registry().getAccountID(SPONSOR);
                    final var payer = spec.registry().getAccountID(PAYER_1);
                    final var parent = lookup.getResponseRecord();
                    var child = lookup.getChildRecord(0);
                    if (isEndOfStakingPeriodRecord(child)) {
                        child = lookup.getChildRecord(1);
                    }
                    assertAliasBalanceAndFeeInChildRecord(
                            parent, child, sponsor, payer, ONE_HUNDRED_HBARS + ONE_HBAR, transferFee, 0);
                    creationTime.set(child.getConsensusTimestamp().getSeconds());
                }),
                sourcing(() -> getAliasedAccountInfo(VALID_ALIAS)
                        .has(accountWith()
                                .key(VALID_ALIAS)
                                .expectedBalanceWithChargedUsd(ONE_HUNDRED_HBARS + ONE_HBAR, 0, 0)
                                .alias(VALID_ALIAS)
                                .autoRenew(THREE_MONTHS_IN_SECONDS)
                                .receiverSigReq(false)
                                .expiry(creationTime.get() + THREE_MONTHS_IN_SECONDS, 0)
                                .memo(AUTO_MEMO))));
    }

    @HapiTest
    final Stream<DynamicTest> multipleAutoAccountCreations() {
        return hapiTest(
                cryptoCreate(PAYER_1).balance(INITIAL_BALANCE * ONE_HBAR),
                newKeyNamed("alias1"),
                newKeyNamed(ALIAS_2),
                newKeyNamed("alias3"),
                newKeyNamed("alias4"),
                newKeyNamed("alias5"),
                assumingNoStakingChildRecordCausesMaxChildRecordsExceeded(
                        atomicBatch(cryptoTransfer(
                                                tinyBarsFromToWithAlias(PAYER_1, "alias1", ONE_HUNDRED_HBARS),
                                                tinyBarsFromToWithAlias(PAYER_1, ALIAS_2, ONE_HUNDRED_HBARS),
                                                tinyBarsFromToWithAlias(PAYER_1, "alias3", ONE_HUNDRED_HBARS))
                                        .via("multipleAutoAccountCreates")
                                        .batchKey("batchOperator"))
                                .payingWith("batchOperator"),
                        "batchTransfer",
                        getTxnRecord("multipleAutoAccountCreates").hasNonStakingChildRecordCount(3),
                        getAccountInfo(PAYER_1)
                                .has(accountWith().balance((INITIAL_BALANCE * ONE_HBAR) - 3 * ONE_HUNDRED_HBARS)),
                        atomicBatch(cryptoTransfer(
                                                tinyBarsFromToWithAlias(PAYER_1, "alias4", 7 * ONE_HUNDRED_HBARS),
                                                tinyBarsFromToWithAlias(PAYER_1, "alias5", 100))
                                        .batchKey("batchOperator")
                                        .via("failedAutoCreate")
                                        .hasKnownStatus(INSUFFICIENT_ACCOUNT_BALANCE))
                                .payingWith("batchOperator")
                                .hasKnownStatus(INNER_TRANSACTION_FAILED),
                        getTxnRecord("failedAutoCreate").hasNonStakingChildRecordCount(0),
                        getAccountInfo(PAYER_1)
                                .has(accountWith().balance((INITIAL_BALANCE * ONE_HBAR) - 3 * ONE_HUNDRED_HBARS))));
    }

    @HapiTest
    final Stream<DynamicTest> transferHbarsToEVMAddressAlias() {

        final AtomicReference<AccountID> partyId = new AtomicReference<>();
        final AtomicReference<byte[]> partyAlias = new AtomicReference<>();
        final AtomicReference<byte[]> counterAlias = new AtomicReference<>();

        return hapiTest(
                cryptoCreate(PARTY).maxAutomaticTokenAssociations(2),
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                withOpContext((spec, opLog) -> {
                    final var registry = spec.registry();
                    final var ecdsaKey = registry.getKey(SECP_256K1_SOURCE_KEY);
                    final var tmp = ecdsaKey.getECDSASecp256K1().toByteArray();
                    final var addressBytes = recoverAddressFromPubKey(tmp);
                    assert addressBytes != null;
                    partyId.set(registry.getAccountID(PARTY));
                    partyAlias.set(asSolidityAddress(partyId.get()));
                    counterAlias.set(addressBytes);
                }),
                withOpContext((spec, opLog) -> {
                    var op1 = atomicBatch(cryptoTransfer((s, b) -> b.setTransfers(TransferList.newBuilder()
                                            .addAccountAmounts(Utils.aaWith(spec, partyAlias.get(), -2 * ONE_HBAR))
                                            .addAccountAmounts(Utils.aaWith(spec, counterAlias.get(), +2 * ONE_HBAR))))
                                    .signedBy(DEFAULT_PAYER, PARTY)
                                    .batchKey("batchOperator")
                                    .via(HBAR_XFER))
                            .payingWith("batchOperator");

                    final var counterAliasStr = ByteString.copyFrom(counterAlias.get());
                    var op2 = getAliasedAccountInfo(counterAliasStr)
                            .has(accountWith()
                                    .expectedBalanceWithChargedUsd(2 * ONE_HBAR, 0, 0)
                                    .hasEmptyKey()
                                    .noAlias()
                                    .autoRenew(THREE_MONTHS_IN_SECONDS)
                                    .receiverSigReq(false)
                                    .memo(LAZY_MEMO));

                    final var txnRequiringHollowAccountSignature = tokenCreate(A_TOKEN)
                            .adminKey(SECP_256K1_SOURCE_KEY)
                            .signedBy(SECP_256K1_SOURCE_KEY)
                            .hasPrecheck(INVALID_SIGNATURE);

                    allRunFor(spec, op1, op2, txnRequiringHollowAccountSignature);
                }),
                getTxnRecord(HBAR_XFER)
                        .hasNonStakingChildRecordCount(1)
                        .hasChildRecords(recordWith().status(SUCCESS).memo(LAZY_MEMO)),
                // and transfers to the 0.0.ECDSA_BYTES alias should succeed.
                cryptoTransfer(tinyBarsFromToWithAlias(PARTY, SECP_256K1_SOURCE_KEY, ONE_HBAR))
                        .hasKnownStatus(SUCCESS)
                        .via(TRANSFER_TXN),
                getAliasedAccountInfo(SECP_256K1_SOURCE_KEY)
                        .has(accountWith().expectedBalanceWithChargedUsd(3 * ONE_HBAR, 0, 0)));
    }

    @HapiTest
    final Stream<DynamicTest> cannotAutoCreateWithTxnToLongZero() {
        final AtomicReference<byte[]> evmAddress = new AtomicReference<>();

        return hapiTest(
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(PAYER_1).balance(10 * ONE_HBAR),
                withOpContext((spec, opLog) -> {
                    final var registry = spec.registry();
                    final var ecdsaKey = registry.getKey(SECP_256K1_SOURCE_KEY);
                    final var tmp = ecdsaKey.getECDSASecp256K1().toByteArray();
                    final var addressBytes = recoverAddressFromPubKey(tmp);
                    evmAddress.set(addressBytes);
                }),
                withOpContext((spec, opLog) -> {
                    final var evmAddressStr = ByteString.copyFrom(evmAddress.get());
                    final var validTransfer = atomicBatch(
                                    cryptoTransfer(tinyBarsFromTo(PAYER_1, evmAddressStr, ONE_HBAR))
                                            .batchKey("batchOperator")
                                            .via("passedTxn"))
                            .payingWith("batchOperator");

                    final var longZeroAddress = ByteString.copyFrom(asSolidityAddress(spec, 5555));

                    final var invalidTransferToLongZero = atomicBatch(
                                    cryptoTransfer(tinyBarsFromTo(PAYER_1, longZeroAddress, ONE_HBAR))
                                            .batchKey("batchOperator")
                                            .hasKnownStatusFrom(INVALID_ACCOUNT_ID, INVALID_ALIAS_KEY))
                            .payingWith("batchOperator")
                            .hasKnownStatus(INNER_TRANSACTION_FAILED);

                    allRunFor(spec, validTransfer, invalidTransferToLongZero);
                }),
                withOpContext((spec, opLog) -> {
                    final var evmAddressStr = ByteString.copyFrom(evmAddress.get());
                    getTxnRecord("passedTxn")
                            .hasNonStakingChildRecordCount(1)
                            .hasChildRecords(
                                    recordWith().status(SUCCESS).memo(LAZY_MEMO).alias(evmAddressStr));
                }));
    }
}
