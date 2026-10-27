// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip551;

import static com.hedera.services.bdd.junit.TestTags.ATOMIC_BATCH;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.NoNftTransfers.changingNoNftOwners;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTokenNftInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAirdrop;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenReject;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenUpdate;
import static com.hedera.services.bdd.spec.transactions.token.HapiTokenReject.rejectingNFT;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingUnique;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.flattened;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.transactions.HapiTxnOp;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(ATOMIC_BATCH)
public class AtomicBatchNftRecordsTest {
    private static final String ADMIN_KEY = "adminKey";
    private static final String SUPPLY_KEY = "supplyKey";
    private static final String OPERATOR = "operator";
    private static final String HOLDER = "holder";
    private static final String OLD_TREASURY = "oldTreasury";
    private static final String NEW_TREASURY = "newTreasury";
    private static final String NEXT_TREASURY = "nextTreasury";
    private static final String MINT_SENDER = "0.0.0";
    private static final String NFT = "nft";
    private static final String BATCH = "batch";

    @HapiTest
    final Stream<DynamicTest> batchWithNftTransferAndTreasuryUpdate() {
        return hapiTest(flattened(
                setup(),
                atomicBatch(nftTransfer(OLD_TREASURY, "transfer"), treasuryUpdate(NEW_TREASURY, "update"))
                        .payingWith(OPERATOR)
                        .via(BATCH),
                batchRecordCheck(),
                getTxnRecord("transfer").hasNftTransfer(NFT, HOLDER, OLD_TREASURY, 1L),
                getTxnRecord("update").hasNftTransfer(NFT, OLD_TREASURY, NEW_TREASURY, -1L),
                getAccountBalance(HOLDER).hasTokenBalance(NFT, 0),
                getAccountBalance(OLD_TREASURY).hasTokenBalance(NFT, 0),
                getAccountBalance(NEW_TREASURY).hasTokenBalance(NFT, 2),
                getTokenNftInfo(NFT, 1).hasAccountID(NEW_TREASURY)));
    }

    @HapiTest
    final Stream<DynamicTest> batchWithNftRejectAndTreasuryUpdate() {
        return hapiTest(flattened(
                setup(),
                atomicBatch(
                                tokenReject(HOLDER, rejectingNFT(NFT, 1L))
                                        .payingWith(OPERATOR)
                                        .signedBy(OPERATOR, HOLDER)
                                        .batchKey(OPERATOR)
                                        .via("reject"),
                                treasuryUpdate(NEW_TREASURY, "update"))
                        .payingWith(OPERATOR)
                        .via(BATCH),
                batchRecordCheck(),
                getTxnRecord("reject").hasNftTransfer(NFT, HOLDER, OLD_TREASURY, 1L),
                getAccountBalance(HOLDER).hasTokenBalance(NFT, 0),
                getAccountBalance(NEW_TREASURY).hasTokenBalance(NFT, 2)));
    }

    @HapiTest
    final Stream<DynamicTest> batchWithNftMintAndTreasuryUpdate() {
        return hapiTest(flattened(
                setup(),
                atomicBatch(
                                mintToken(NFT, List.of(ByteString.copyFromUtf8("three")))
                                        .payingWith(OPERATOR)
                                        .signedBy(OPERATOR, SUPPLY_KEY)
                                        .batchKey(OPERATOR)
                                        .via("mint"),
                                treasuryUpdate(NEW_TREASURY, "update"))
                        .payingWith(OPERATOR)
                        .via(BATCH),
                batchRecordCheck(),
                getTxnRecord("mint").hasNftTransfer(NFT, MINT_SENDER, OLD_TREASURY, 3L),
                getAccountBalance(HOLDER).hasTokenBalance(NFT, 1),
                getAccountBalance(OLD_TREASURY).hasTokenBalance(NFT, 0),
                getAccountBalance(NEW_TREASURY).hasTokenBalance(NFT, 2),
                getTokenNftInfo(NFT, 3).hasAccountID(NEW_TREASURY)));
    }

    @HapiTest
    final Stream<DynamicTest> batchWithNftTransferAndTwoTreasuryUpdates() {
        return hapiTest(flattened(
                setup(),
                cryptoCreate(NEXT_TREASURY),
                tokenAssociate(NEXT_TREASURY, NFT),
                atomicBatch(
                                nftTransfer(OLD_TREASURY, "transfer"),
                                treasuryUpdate(NEW_TREASURY, "update"),
                                treasuryUpdate(NEXT_TREASURY, "nextUpdate"))
                        .payingWith(OPERATOR)
                        .via(BATCH),
                batchRecordCheck(),
                getTxnRecord("nextUpdate").hasNftTransfer(NFT, NEW_TREASURY, NEXT_TREASURY, -1L),
                getAccountBalance(HOLDER).hasTokenBalance(NFT, 0),
                getAccountBalance(NEW_TREASURY).hasTokenBalance(NFT, 0),
                getAccountBalance(NEXT_TREASURY).hasTokenBalance(NFT, 2),
                getTokenNftInfo(NFT, 1).hasAccountID(NEXT_TREASURY)));
    }

    @HapiTest
    final Stream<DynamicTest> batchWithNftTransferBetweenTreasuryUpdates() {
        return hapiTest(flattened(
                setup(),
                cryptoCreate(NEXT_TREASURY),
                tokenAssociate(NEXT_TREASURY, NFT),
                atomicBatch(
                                treasuryUpdate(NEW_TREASURY, "update"),
                                nftTransfer(NEW_TREASURY, "transfer"),
                                treasuryUpdate(NEXT_TREASURY, "nextUpdate"))
                        .payingWith(OPERATOR)
                        .via(BATCH),
                batchRecordCheck(),
                getTxnRecord("transfer").hasNftTransfer(NFT, HOLDER, NEW_TREASURY, 1L),
                getAccountBalance(HOLDER).hasTokenBalance(NFT, 0),
                getAccountBalance(NEXT_TREASURY).hasTokenBalance(NFT, 2),
                getTokenNftInfo(NFT, 1).hasAccountID(NEXT_TREASURY)));
    }

    @HapiTest
    final Stream<DynamicTest> batchWithNftAirdropAndTreasuryUpdate() {
        return hapiTest(flattened(
                setup(),
                atomicBatch(
                                tokenAirdrop(movingUnique(NFT, 1L).between(HOLDER, OLD_TREASURY))
                                        .payingWith(OPERATOR)
                                        .signedBy(OPERATOR, HOLDER)
                                        .batchKey(OPERATOR)
                                        .via("airdrop"),
                                treasuryUpdate(NEW_TREASURY, "update"))
                        .payingWith(OPERATOR)
                        .via(BATCH),
                batchRecordCheck(),
                getTxnRecord("airdrop").hasNftTransfer(NFT, HOLDER, OLD_TREASURY, 1L),
                getAccountBalance(HOLDER).hasTokenBalance(NFT, 0),
                getAccountBalance(NEW_TREASURY).hasTokenBalance(NFT, 2)));
    }

    @HapiTest
    final Stream<DynamicTest> batchWithTreasuryUpdateAndNftTransfer() {
        return hapiTest(flattened(
                setup(),
                atomicBatch(treasuryUpdate(NEW_TREASURY, "update"), nftTransfer(NEW_TREASURY, "transfer"))
                        .payingWith(OPERATOR)
                        .via(BATCH),
                batchRecordCheck(),
                getTxnRecord("transfer").hasNftTransfer(NFT, HOLDER, NEW_TREASURY, 1L),
                getAccountBalance(HOLDER).hasTokenBalance(NFT, 0),
                getAccountBalance(NEW_TREASURY).hasTokenBalance(NFT, 2)));
    }

    private static SpecOperation[] setup() {
        return new SpecOperation[] {
            newKeyNamed(ADMIN_KEY),
            newKeyNamed(SUPPLY_KEY),
            cryptoCreate(OPERATOR).balance(ONE_HUNDRED_HBARS),
            cryptoCreate(HOLDER),
            cryptoCreate(OLD_TREASURY),
            cryptoCreate(NEW_TREASURY),
            tokenCreate(NFT)
                    .tokenType(NON_FUNGIBLE_UNIQUE)
                    .initialSupply(0)
                    .treasury(OLD_TREASURY)
                    .adminKey(ADMIN_KEY)
                    .supplyKey(SUPPLY_KEY),
            mintToken(NFT, List.of(ByteString.copyFromUtf8("one"), ByteString.copyFromUtf8("two"))),
            tokenAssociate(HOLDER, NFT),
            tokenAssociate(NEW_TREASURY, NFT),
            cryptoTransfer(movingUnique(NFT, 1L).between(OLD_TREASURY, HOLDER))
        };
    }

    private static HapiTxnOp<?> nftTransfer(final String receiver, final String txnName) {
        return cryptoTransfer(movingUnique(NFT, 1L).between(HOLDER, receiver))
                .payingWith(OPERATOR)
                .signedBy(OPERATOR, HOLDER)
                .batchKey(OPERATOR)
                .via(txnName);
    }

    private static HapiTxnOp<?> treasuryUpdate(final String treasury, final String txnName) {
        return tokenUpdate(NFT)
                .treasury(treasury)
                .payingWith(OPERATOR)
                .signedBy(OPERATOR, ADMIN_KEY, treasury)
                .batchKey(OPERATOR)
                .via(txnName);
    }

    private static SpecOperation batchRecordCheck() {
        return getTxnRecord(BATCH).hasPriority(recordWith().tokenTransfers(changingNoNftOwners()));
    }
}
