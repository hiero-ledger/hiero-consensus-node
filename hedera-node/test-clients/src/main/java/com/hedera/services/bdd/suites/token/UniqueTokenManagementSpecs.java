// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.token;

import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.junit.TestTags.TOKEN;
import static com.hedera.services.bdd.spec.HapiSpec.defaultHapiSpec;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getReceipt;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTokenInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTokenNftInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.queries.crypto.ExpectedTokenRel.relationshipWith;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.burnToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenDissociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.wipeTokenAccount;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingUnique;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.TOKEN_TREASURY;
import static com.hedera.services.bdd.suites.utils.MiscEETUtils.batchOfSize;
import static com.hedera.services.bdd.suites.utils.MiscEETUtils.metadata;
import static com.hedera.services.bdd.suites.utils.MiscEETUtils.metadataOfLength;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_DOES_NOT_OWN_WIPED_NFT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_STILL_OWNS_NFTS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.BATCH_SIZE_LIMIT_EXCEEDED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_NFT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOKEN_BURN_METADATA;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOKEN_MINT_METADATA;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOKEN_NFT_SERIAL_NUMBER;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_WIPING_AMOUNT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_MAX_SUPPLY_REACHED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_WAS_DELETED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TREASURY_MUST_OWN_BURNED_NFT;
import static com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import com.hedera.services.bdd.spec.transactions.token.TokenMovement;
import com.hedera.services.bdd.spec.utilops.UtilVerbs;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.TokenSupplyType;
import com.hederahashgraph.api.proto.java.TokenType;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(TOKEN)
public class UniqueTokenManagementSpecs {

    private static final org.apache.logging.log4j.Logger log = LogManager.getLogger(UniqueTokenManagementSpecs.class);
    private static final String A_TOKEN = "TokenA";
    private static final String NFT = "nft";
    private static final String FUNGIBLE_TOKEN = "fungible";
    private static final String SUPPLY_KEY = "supplyKey";
    private static final String FIRST_USER = "Client1";
    private static final int BIGGER_THAN_LIMIT = 11;
    private static final String MEMO_1 = "memo1";
    private static final String MINT_TXN = "mintTxn";
    private static final String MEMO_2 = "memo2";
    private static final String SHOULD_NOT_WORK = "should-not-work";
    private static final String SHOULD_NOT_APPEAR = "should-not-appear";
    private static final String BURN_FAILURE = "burn-failure";
    private static final String BURN_TXN = "burnTxn";
    private static final String WIPE_TXN = "wipeTxn";
    private static final String ACCOUNT = "account";
    private static final String CUSTOM_PAYER = "customPayer";
    private static final String WIPE_KEY = "wipeKey";

    @HapiTest // here
    final Stream<DynamicTest> populatingMetadataForFungibleDoesNotWork() {
        return defaultHapiSpec("PopulatingMetadataForFungibleDoesNotWork")
                .given(
                        newKeyNamed(SUPPLY_KEY),
                        cryptoCreate(TOKEN_TREASURY),
                        tokenCreate(FUNGIBLE_TOKEN)
                                .initialSupply(0)
                                .tokenType(TokenType.FUNGIBLE_COMMON)
                                .supplyType(TokenSupplyType.INFINITE)
                                .supplyKey(SUPPLY_KEY)
                                .treasury(TOKEN_TREASURY))
                .when(mintToken(
                                FUNGIBLE_TOKEN,
                                List.of(
                                        metadata("some-data"),
                                        metadata("some-data2"),
                                        metadata("some-data3"),
                                        metadata("some-data4")))
                        .via(SHOULD_NOT_WORK))
                .then(
                        getAccountBalance(TOKEN_TREASURY).hasTokenBalance(FUNGIBLE_TOKEN, 0),
                        getTxnRecord(SHOULD_NOT_WORK).showsNoTransfers(),
                        UtilVerbs.withOpContext((spec, opLog) -> {
                            var mintNFT = getTxnRecord(SHOULD_NOT_WORK);
                            allRunFor(spec, mintNFT);
                            var receipt = mintNFT.getResponseRecord().getReceipt();
                            Assertions.assertEquals(0, receipt.getNewTotalSupply());
                        }));
    }

    @HapiTest
    final Stream<DynamicTest> populatingAmountForNonFungibleDoesNotWork() {
        return defaultHapiSpec("PopulatingAmountForNonFungibleDoesNotWork")
                .given(
                        newKeyNamed(SUPPLY_KEY),
                        cryptoCreate(TOKEN_TREASURY),
                        tokenCreate(NFT)
                                .initialSupply(0)
                                .tokenType(NON_FUNGIBLE_UNIQUE)
                                .supplyType(TokenSupplyType.INFINITE)
                                .supplyKey(SUPPLY_KEY)
                                .treasury(TOKEN_TREASURY))
                .when(mintToken(NFT, 300)
                        .hasKnownStatus(INVALID_TOKEN_MINT_METADATA)
                        .via(SHOULD_NOT_WORK))
                .then(
                        getTxnRecord(SHOULD_NOT_WORK).showsNoTransfers(),
                        getAccountBalance(TOKEN_TREASURY).hasTokenBalance(NFT, 0),
                        UtilVerbs.withOpContext((spec, opLog) -> {
                            var mintNFT = getTxnRecord(SHOULD_NOT_WORK);
                            allRunFor(spec, mintNFT);
                            var receipt = mintNFT.getResponseRecord().getReceipt();
                            Assertions.assertEquals(0, receipt.getNewTotalSupply());
                            Assertions.assertEquals(0, receipt.getSerialNumbersCount());
                        }));
    }

    @HapiTest
    @Tag(MATS)
    final Stream<DynamicTest> finiteNftReachesMaxSupplyProperly() {
        return defaultHapiSpec("FiniteNftReachesMaxSupplyProperly")
                .given(
                        newKeyNamed(SUPPLY_KEY),
                        cryptoCreate(TOKEN_TREASURY),
                        tokenCreate(NFT)
                                .initialSupply(0)
                                .maxSupply(3)
                                .tokenType(NON_FUNGIBLE_UNIQUE)
                                .supplyType(TokenSupplyType.FINITE)
                                .supplyKey(SUPPLY_KEY)
                                .treasury(TOKEN_TREASURY))
                .when(mintToken(
                                NFT,
                                List.of(
                                        metadata("some-data"),
                                        metadata("some-data2"),
                                        metadata("some-data3"),
                                        metadata("some-data4")))
                        .hasKnownStatus(TOKEN_MAX_SUPPLY_REACHED)
                        .via(SHOULD_NOT_APPEAR))
                .then(
                        getTxnRecord(SHOULD_NOT_APPEAR).showsNoTransfers(),
                        getAccountBalance(TOKEN_TREASURY).hasTokenBalance(NFT, 0),
                        UtilVerbs.withOpContext((spec, opLog) -> {
                            var mintNFT = getTxnRecord(SHOULD_NOT_APPEAR);
                            allRunFor(spec, mintNFT);
                            var receipt = mintNFT.getResponseRecord().getReceipt();
                            Assertions.assertEquals(0, receipt.getNewTotalSupply());
                            Assertions.assertEquals(0, receipt.getSerialNumbersCount());
                        }));
    }

    @HapiTest
    final Stream<DynamicTest> serialNumbersOnlyOnFungibleBurnFails() {
        return defaultHapiSpec("SerialNumbersOnlyOnFungibleBurnFails")
                .given(
                        newKeyNamed(SUPPLY_KEY),
                        cryptoCreate(TOKEN_TREASURY),
                        tokenCreate(FUNGIBLE_TOKEN)
                                .initialSupply(0)
                                .tokenType(FUNGIBLE_COMMON)
                                .supplyType(TokenSupplyType.INFINITE)
                                .supplyKey(SUPPLY_KEY)
                                .treasury(TOKEN_TREASURY))
                .when(mintToken(FUNGIBLE_TOKEN, 300))
                .then(
                        burnToken(FUNGIBLE_TOKEN, List.of(1L, 2L, 3L))
                                .via(BURN_FAILURE)
                                .logged(),
                        getAccountBalance(TOKEN_TREASURY).hasTokenBalance(FUNGIBLE_TOKEN, 300),
                        getTxnRecord(BURN_FAILURE).showsNoTransfers().logged(),
                        UtilVerbs.withOpContext((spec, opLog) -> {
                            var burnTxn = getTxnRecord(BURN_FAILURE);
                            allRunFor(spec, burnTxn);
                            Assertions.assertEquals(
                                    300,
                                    burnTxn.getResponseRecord().getReceipt().getNewTotalSupply());
                        }));
    }

    @HapiTest
    final Stream<DynamicTest> amountOnlyOnNonFungibleBurnFails() {
        return defaultHapiSpec("AmountOnlyOnNonFungibleBurnFails")
                .given(
                        newKeyNamed(SUPPLY_KEY),
                        cryptoCreate(TOKEN_TREASURY),
                        tokenCreate(NFT)
                                .initialSupply(0)
                                .tokenType(NON_FUNGIBLE_UNIQUE)
                                .supplyType(TokenSupplyType.INFINITE)
                                .supplyKey(SUPPLY_KEY)
                                .treasury(TOKEN_TREASURY))
                .when(mintToken(NFT, List.of(metadata("some-random-data"), metadata("some-other-random-data"))))
                .then(
                        burnToken(NFT, 300)
                                .hasKnownStatus(INVALID_TOKEN_BURN_METADATA)
                                .via(BURN_FAILURE),
                        getTxnRecord(BURN_FAILURE).showsNoTransfers(),
                        getAccountBalance(TOKEN_TREASURY).hasTokenBalance(NFT, 2),
                        UtilVerbs.withOpContext((spec, opLog) -> {
                            var burnTxn = getTxnRecord(BURN_FAILURE);
                            allRunFor(spec, burnTxn);
                            Assertions.assertEquals(
                                    0, burnTxn.getResponseRecord().getReceipt().getNewTotalSupply());
                        }));
    }

    @HapiTest
    @Tag(MATS)
    final Stream<DynamicTest> burnWorksWhenAccountsAreFrozenByDefault() {
        return defaultHapiSpec("BurnWorksWhenAccountsAreFrozenByDefault")
                .given(
                        newKeyNamed(SUPPLY_KEY),
                        cryptoCreate(TOKEN_TREASURY),
                        tokenCreate(NFT)
                                .tokenType(NON_FUNGIBLE_UNIQUE)
                                .supplyType(TokenSupplyType.INFINITE)
                                .initialSupply(0)
                                .supplyKey(SUPPLY_KEY)
                                .treasury(TOKEN_TREASURY),
                        mintToken(NFT, List.of(metadata("memo"))))
                .when(burnToken(NFT, List.of(1L)).via(BURN_TXN).logged())
                .then(
                        getTxnRecord(BURN_TXN).hasCostAnswerPrecheck(OK),
                        getTokenNftInfo(NFT, 1).hasCostAnswerPrecheck(INVALID_NFT_ID),
                        getAccountInfo(TOKEN_TREASURY).hasOwnedNfts(0));
    }

    @HapiTest
    @Tag(MATS)
    final Stream<DynamicTest> burnFailsOnInvalidSerialNumber() {
        return defaultHapiSpec("BurnFailsOnInvalidSerialNumber")
                .given(
                        newKeyNamed(SUPPLY_KEY),
                        cryptoCreate(TOKEN_TREASURY),
                        tokenCreate(NFT)
                                .tokenType(NON_FUNGIBLE_UNIQUE)
                                .supplyType(TokenSupplyType.INFINITE)
                                .initialSupply(0)
                                .supplyKey(SUPPLY_KEY)
                                .treasury(TOKEN_TREASURY),
                        mintToken(NFT, List.of(metadata("memo"))))
                .when()
                .then(
                        burnToken(NFT, List.of(0L, 1L, 2L)).via(BURN_TXN).hasPrecheck(INVALID_NFT_ID),
                        getAccountInfo(TOKEN_TREASURY).hasOwnedNfts(1));
    }

    @HapiTest
    @Tag(MATS)
    final Stream<DynamicTest> burnRespectsBurnBatchConstraints() {
        return defaultHapiSpec("BurnRespectsBurnBatchConstraints")
                .given(
                        newKeyNamed(SUPPLY_KEY),
                        cryptoCreate(TOKEN_TREASURY),
                        tokenCreate(NFT)
                                .tokenType(NON_FUNGIBLE_UNIQUE)
                                .supplyType(TokenSupplyType.INFINITE)
                                .initialSupply(0)
                                .supplyKey(SUPPLY_KEY)
                                .treasury(TOKEN_TREASURY),
                        mintToken(NFT, List.of(metadata("memo"))))
                .when()
                // This ID range needs to be exclusively positive (i.e. not zero)
                .then(burnToken(NFT, LongStream.range(1, 1001).boxed().collect(Collectors.toList()))
                        .via(BURN_TXN)
                        .hasPrecheck(BATCH_SIZE_LIMIT_EXCEEDED));
    }

    @HapiTest
    @Tag(MATS)
    final Stream<DynamicTest> burnHappyPath() {
        return defaultHapiSpec("BurnHappyPath")
                .given(
                        newKeyNamed(SUPPLY_KEY),
                        cryptoCreate(TOKEN_TREASURY),
                        tokenCreate(NFT)
                                .tokenType(NON_FUNGIBLE_UNIQUE)
                                .supplyType(TokenSupplyType.INFINITE)
                                .initialSupply(0)
                                .supplyKey(SUPPLY_KEY)
                                .treasury(TOKEN_TREASURY),
                        mintToken(NFT, List.of(metadata("memo"))))
                .when(burnToken(NFT, List.of(1L)).via(BURN_TXN))
                .then(
                        getTokenNftInfo(NFT, 1).hasCostAnswerPrecheck(INVALID_NFT_ID),
                        getTokenInfo(NFT).hasTotalSupply(0),
                        getAccountBalance(TOKEN_TREASURY).hasTokenBalance(NFT, 0),
                        getAccountInfo(TOKEN_TREASURY)
                                .hasToken(relationshipWith(NFT))
                                .hasOwnedNfts(0));
    }

    @HapiTest
    @Tag(MATS)
    final Stream<DynamicTest> mintFailsOnInvalidSupplyKey() {
        return hapiTest(
                newKeyNamed(SUPPLY_KEY),
                cryptoCreate(TOKEN_TREASURY),
                // create valid NFT
                tokenCreate(NFT)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .supplyType(TokenSupplyType.INFINITE)
                        .initialSupply(0)
                        .supplyKey(SUPPLY_KEY)
                        .treasury(TOKEN_TREASURY),
                // mint with the wrong supply key
                mintToken(NFT, List.of(metadata("1"), metadata("2")))
                        .signedBy(GENESIS, GENESIS)
                        .hasKnownStatus(INVALID_SIGNATURE),
                // mint with the right supply key
                mintToken(NFT, List.of(metadata("1"), metadata("2")))
                        .signedBy(GENESIS, SUPPLY_KEY)
                        .hasKnownStatus(SUCCESS));
    }

    @HapiTest
    @Tag(MATS)
    final Stream<DynamicTest> burnFailsOnInvalidSupplyKey() {
        return hapiTest(
                newKeyNamed(SUPPLY_KEY),
                cryptoCreate(TOKEN_TREASURY),
                // create valid NFT
                tokenCreate(NFT)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .supplyType(TokenSupplyType.INFINITE)
                        .initialSupply(0)
                        .supplyKey(SUPPLY_KEY)
                        .treasury(TOKEN_TREASURY),
                mintToken(NFT, List.of(metadata("1"), metadata("2"))),
                // burn with the wrong supply key
                burnToken(NFT, List.of(1L, 2L)).signedBy(GENESIS, GENESIS).hasKnownStatus(INVALID_SIGNATURE),
                // burn with the right supply key
                burnToken(NFT, List.of(1L, 2L)).signedBy(GENESIS, SUPPLY_KEY).hasKnownStatus(SUCCESS));
    }

    @HapiTest
    @Tag(MATS)
    final Stream<DynamicTest> canOnlyBurnFromTreasury() {
        final var nonTreasury = "anybodyElse";

        return defaultHapiSpec("CanOnlyBurnFromTreasury")
                .given(
                        newKeyNamed(SUPPLY_KEY),
                        cryptoCreate(TOKEN_TREASURY),
                        cryptoCreate(nonTreasury),
                        tokenCreate(NFT)
                                .tokenType(NON_FUNGIBLE_UNIQUE)
                                .supplyType(TokenSupplyType.INFINITE)
                                .initialSupply(0)
                                .supplyKey(SUPPLY_KEY)
                                .treasury(TOKEN_TREASURY),
                        mintToken(NFT, List.of(metadata("1"), metadata("2"))),
                        tokenAssociate(nonTreasury, NFT),
                        cryptoTransfer(movingUnique(NFT, 2L).between(TOKEN_TREASURY, nonTreasury)))
                .when(burnToken(NFT, List.of(1L, 2L)).via(BURN_TXN).hasKnownStatus(TREASURY_MUST_OWN_BURNED_NFT))
                .then(
                        getTokenNftInfo(NFT, 1).hasSerialNum(1),
                        getTokenNftInfo(NFT, 2).hasSerialNum(2),
                        getTokenInfo(NFT).hasTotalSupply(2),
                        getAccountBalance(nonTreasury).hasTokenBalance(NFT, 1),
                        getAccountBalance(TOKEN_TREASURY).hasTokenBalance(NFT, 1),
                        getAccountInfo(nonTreasury).hasOwnedNfts(1),
                        getAccountInfo(TOKEN_TREASURY).hasOwnedNfts(1));
    }

    @HapiTest
    @Tag(MATS)
    final Stream<DynamicTest> treasuryBalanceCorrectAfterBurn() {
        return defaultHapiSpec("TreasuryBalanceCorrectAfterBurn")
                .given(
                        newKeyNamed(SUPPLY_KEY),
                        cryptoCreate(TOKEN_TREASURY),
                        tokenCreate(NFT)
                                .tokenType(NON_FUNGIBLE_UNIQUE)
                                .supplyType(TokenSupplyType.INFINITE)
                                .initialSupply(0)
                                .supplyKey(SUPPLY_KEY)
                                .treasury(TOKEN_TREASURY),
                        mintToken(
                                NFT,
                                List.of(metadata("1"), metadata("2"), metadata("3"), metadata("4"), metadata("5"))))
                .when(burnToken(NFT, List.of(3L, 4L, 5L))
                        .payingWith(TOKEN_TREASURY)
                        .via(BURN_TXN))
                .then(
                        getTokenNftInfo(NFT, 1).hasSerialNum(1).hasCostAnswerPrecheck(OK),
                        getTokenNftInfo(NFT, 2).hasSerialNum(2).hasCostAnswerPrecheck(OK),
                        getTokenNftInfo(NFT, 3).hasCostAnswerPrecheck(INVALID_NFT_ID),
                        getTokenNftInfo(NFT, 4).hasCostAnswerPrecheck(INVALID_NFT_ID),
                        getTokenNftInfo(NFT, 5).hasCostAnswerPrecheck(INVALID_NFT_ID),
                        getTokenInfo(NFT).hasTotalSupply(2),
                        getAccountBalance(TOKEN_TREASURY).hasTokenBalance(NFT, 2),
                        getAccountInfo(TOKEN_TREASURY).hasOwnedNfts(2));
    }

    @HapiTest
    @Tag(MATS)
    final Stream<DynamicTest> mintDistinguishesFeeSubTypes() {
        return defaultHapiSpec("MintDistinguishesFeeSubTypes")
                .given(
                        newKeyNamed(SUPPLY_KEY),
                        cryptoCreate(TOKEN_TREASURY),
                        cryptoCreate(CUSTOM_PAYER),
                        tokenCreate(NFT)
                                .tokenType(NON_FUNGIBLE_UNIQUE)
                                .supplyType(TokenSupplyType.INFINITE)
                                .initialSupply(0)
                                .supplyKey(SUPPLY_KEY)
                                .treasury(TOKEN_TREASURY),
                        tokenCreate(FUNGIBLE_TOKEN)
                                .tokenType(TokenType.FUNGIBLE_COMMON)
                                .supplyType(TokenSupplyType.FINITE)
                                .initialSupply(10)
                                .maxSupply(1100)
                                .supplyKey(SUPPLY_KEY)
                                .treasury(TOKEN_TREASURY))
                .when(
                        mintToken(NFT, List.of(metadata("memo")))
                                .payingWith(CUSTOM_PAYER)
                                .signedBy(CUSTOM_PAYER, SUPPLY_KEY)
                                .via("mintNFT"),
                        mintToken(FUNGIBLE_TOKEN, 100L)
                                .payingWith(CUSTOM_PAYER)
                                .signedBy(CUSTOM_PAYER, SUPPLY_KEY)
                                .via("mintFungible"))
                .then(UtilVerbs.withOpContext((spec, opLog) -> {
                    var mintNFT = getTxnRecord("mintNFT");
                    var mintFungible = getTxnRecord("mintFungible");
                    allRunFor(spec, mintNFT, mintFungible);
                    var nftFee = mintNFT.getResponseRecord().getTransactionFee();
                    var fungibleFee = mintFungible.getResponseRecord().getTransactionFee();
                    Assertions.assertNotEquals(nftFee, fungibleFee, "NFT Fee should NOT equal to the Fungible Fee!");
                }));
    }

    @HapiTest
    @Tag(MATS)
    final Stream<DynamicTest> mintFailsWithTooLongMetadata() {
        return defaultHapiSpec("MintFailsWithTooLongMetadata")
                .given(
                        newKeyNamed(SUPPLY_KEY),
                        cryptoCreate(TOKEN_TREASURY),
                        tokenCreate(NFT)
                                .tokenType(NON_FUNGIBLE_UNIQUE)
                                .supplyType(TokenSupplyType.INFINITE)
                                .initialSupply(0)
                                .supplyKey(SUPPLY_KEY)
                                .treasury(TOKEN_TREASURY))
                .when()
                .then(mintToken(NFT, List.of(metadataOfLength(101))).hasPrecheck(ResponseCodeEnum.METADATA_TOO_LONG));
    }

    @HapiTest
    @Tag(MATS)
    final Stream<DynamicTest> mintFailsWithInvalidMetadataFromBatch() {
        return defaultHapiSpec("MintFailsWithInvalidMetadataFromBatch")
                .given(
                        newKeyNamed(SUPPLY_KEY),
                        cryptoCreate(TOKEN_TREASURY),
                        tokenCreate(NFT)
                                .tokenType(NON_FUNGIBLE_UNIQUE)
                                .supplyType(TokenSupplyType.INFINITE)
                                .initialSupply(0)
                                .supplyKey(SUPPLY_KEY)
                                .treasury(TOKEN_TREASURY))
                .when()
                .then(mintToken(NFT, List.of(metadataOfLength(101), metadataOfLength(1)))
                        .hasPrecheck(ResponseCodeEnum.METADATA_TOO_LONG));
    }

    @HapiTest
    @Tag(MATS)
    final Stream<DynamicTest> mintFailsWithLargeBatchSize() {
        return defaultHapiSpec("MintFailsWithLargeBatchSize")
                .given(
                        newKeyNamed(SUPPLY_KEY),
                        cryptoCreate(TOKEN_TREASURY),
                        tokenCreate(NFT)
                                .tokenType(NON_FUNGIBLE_UNIQUE)
                                .supplyType(TokenSupplyType.INFINITE)
                                .initialSupply(0)
                                .supplyKey(SUPPLY_KEY)
                                .treasury(TOKEN_TREASURY))
                .when()
                .then(mintToken(NFT, batchOfSize(BIGGER_THAN_LIMIT)).hasPrecheck(BATCH_SIZE_LIMIT_EXCEEDED));
    }

    @HapiTest
    @Tag(MATS)
    final Stream<DynamicTest> mintUniqueTokenHappyPath() {
        return defaultHapiSpec("MintUniqueTokenHappyPath")
                .given(
                        newKeyNamed(SUPPLY_KEY),
                        cryptoCreate(TOKEN_TREASURY),
                        tokenCreate(NFT)
                                .tokenType(NON_FUNGIBLE_UNIQUE)
                                .supplyKey(SUPPLY_KEY)
                                .supplyType(TokenSupplyType.INFINITE)
                                .initialSupply(0)
                                .supplyKey(SUPPLY_KEY)
                                .treasury(TOKEN_TREASURY))
                .when(mintToken(NFT, List.of(metadata("memo"), metadata(MEMO_1)))
                        .via(MINT_TXN))
                .then(
                        getReceipt(MINT_TXN).logged(),
                        getTokenNftInfo(NFT, 1)
                                .hasSerialNum(1)
                                .hasMetadata(metadata("memo"))
                                .hasTokenID(NFT)
                                .hasAccountID(TOKEN_TREASURY)
                                .hasValidCreationTime(),
                        getTokenNftInfo(NFT, 2)
                                .hasSerialNum(2)
                                .hasMetadata(metadata(MEMO_1))
                                .hasTokenID(NFT)
                                .hasAccountID(TOKEN_TREASURY)
                                .hasValidCreationTime(),
                        getTokenNftInfo(NFT, 3).hasCostAnswerPrecheck(INVALID_NFT_ID),
                        getAccountBalance(TOKEN_TREASURY).hasTokenBalance(NFT, 2),
                        getTokenInfo(NFT).hasTreasury(TOKEN_TREASURY).hasTotalSupply(2),
                        getAccountInfo(TOKEN_TREASURY)
                                .hasToken(relationshipWith(NFT))
                                .hasOwnedNfts(2));
    }

    @HapiTest
    @Tag(MATS)
    final Stream<DynamicTest> mintTokenWorksWhenAccountsAreFrozenByDefault() {
        return defaultHapiSpec("MintTokenWorksWhenAccountsAreFrozenByDefault")
                .given(
                        newKeyNamed(SUPPLY_KEY),
                        newKeyNamed("tokenFreezeKey"),
                        cryptoCreate(TOKEN_TREASURY).balance(0L),
                        tokenCreate(NFT)
                                .tokenType(NON_FUNGIBLE_UNIQUE)
                                .supplyKey(SUPPLY_KEY)
                                .freezeKey("tokenFreezeKey")
                                .freezeDefault(true)
                                .initialSupply(0)
                                .treasury(TOKEN_TREASURY))
                .when(mintToken(NFT, List.of(metadata("memo"))).via(MINT_TXN))
                .then(
                        getTokenNftInfo(NFT, 1)
                                .hasTokenID(NFT)
                                .hasAccountID(TOKEN_TREASURY)
                                .hasMetadata(metadata("memo"))
                                .hasValidCreationTime(),
                        getAccountInfo(TOKEN_TREASURY).hasOwnedNfts(1));
    }

    @HapiTest
    @Tag(MATS)
    final Stream<DynamicTest> mintFailsWithDeletedToken() {
        return defaultHapiSpec("MintFailsWithDeletedToken")
                .given(
                        newKeyNamed(SUPPLY_KEY),
                        newKeyNamed("adminKey"),
                        cryptoCreate(TOKEN_TREASURY),
                        tokenCreate(NFT)
                                .supplyKey(SUPPLY_KEY)
                                .adminKey("adminKey")
                                .treasury(TOKEN_TREASURY))
                .when(tokenDelete(NFT))
                .then(
                        mintToken(NFT, List.of(metadata("memo"))).via(MINT_TXN).hasKnownStatus(TOKEN_WAS_DELETED),
                        getTokenNftInfo(NFT, 1).hasCostAnswerPrecheck(INVALID_NFT_ID),
                        getTokenInfo(NFT).isDeleted());
    }

    @HapiTest
    @Tag(MATS)
    final Stream<DynamicTest> getTokenNftInfoFailsWithNoNft() {
        return defaultHapiSpec("GetTokenNftInfoFailsWithNoNft")
                .given(newKeyNamed(SUPPLY_KEY), cryptoCreate(TOKEN_TREASURY))
                .when(
                        tokenCreate(NFT)
                                .tokenType(NON_FUNGIBLE_UNIQUE)
                                .supplyType(TokenSupplyType.INFINITE)
                                .supplyKey(SUPPLY_KEY)
                                .initialSupply(0)
                                .treasury(TOKEN_TREASURY),
                        mintToken(NFT, List.of(metadata("memo"))).via(MINT_TXN))
                .then(
                        getTokenNftInfo(NFT, 0).hasCostAnswerPrecheck(INVALID_TOKEN_NFT_SERIAL_NUMBER),
                        getTokenNftInfo(NFT, -1).hasCostAnswerPrecheck(INVALID_TOKEN_NFT_SERIAL_NUMBER),
                        getTokenNftInfo(NFT, 2).hasCostAnswerPrecheck(INVALID_NFT_ID));
    }

    @HapiTest
    @Tag(MATS)
    final Stream<DynamicTest> getTokenNftInfoWorks() {
        return defaultHapiSpec("GetTokenNftInfoWorks")
                .given(newKeyNamed(SUPPLY_KEY), cryptoCreate(TOKEN_TREASURY))
                .when(
                        tokenCreate(NFT)
                                .tokenType(NON_FUNGIBLE_UNIQUE)
                                .supplyType(TokenSupplyType.INFINITE)
                                .supplyKey(SUPPLY_KEY)
                                .initialSupply(0)
                                .treasury(TOKEN_TREASURY),
                        mintToken(NFT, List.of(metadata("memo"))))
                .then(
                        getTokenNftInfo(NFT, 0).hasCostAnswerPrecheck(INVALID_TOKEN_NFT_SERIAL_NUMBER),
                        getTokenNftInfo(NFT, -1).hasCostAnswerPrecheck(INVALID_TOKEN_NFT_SERIAL_NUMBER),
                        getTokenNftInfo(NFT, 2).hasCostAnswerPrecheck(INVALID_NFT_ID),
                        getTokenNftInfo(NFT, 1)
                                .hasTokenID(NFT)
                                .hasAccountID(TOKEN_TREASURY)
                                .hasMetadata(metadata("memo"))
                                .hasSerialNum(1)
                                .hasValidCreationTime());
    }

    @HapiTest
    @Tag(MATS)
    final Stream<DynamicTest> mintUniqueTokenWorksWithRepeatedMetadata() {
        return defaultHapiSpec("MintUniqueTokenWorksWithRepeatedMetadata")
                .given(
                        newKeyNamed(SUPPLY_KEY),
                        cryptoCreate(TOKEN_TREASURY),
                        tokenCreate(NFT)
                                .tokenType(NON_FUNGIBLE_UNIQUE)
                                .supplyType(TokenSupplyType.INFINITE)
                                .supplyKey(SUPPLY_KEY)
                                .initialSupply(0)
                                .treasury(TOKEN_TREASURY))
                .when(mintToken(NFT, List.of(metadata("memo"), metadata("memo")))
                        .via(MINT_TXN))
                .then(
                        getTokenNftInfo(NFT, 1)
                                .hasSerialNum(1)
                                .hasMetadata(metadata("memo"))
                                .hasAccountID(TOKEN_TREASURY)
                                .hasTokenID(NFT)
                                .hasValidCreationTime(),
                        getTokenNftInfo(NFT, 2)
                                .hasSerialNum(2)
                                .hasMetadata(metadata("memo"))
                                .hasAccountID(TOKEN_TREASURY)
                                .hasTokenID(NFT)
                                .hasValidCreationTime(),
                        getAccountInfo(TOKEN_TREASURY).hasOwnedNfts(2));
    }

    @HapiTest
    @Tag(MATS)
    final Stream<DynamicTest> wipeHappyPath() {
        return defaultHapiSpec("WipeHappyPath")
                .given(
                        newKeyNamed(SUPPLY_KEY),
                        newKeyNamed(WIPE_KEY),
                        newKeyNamed("treasuryKey"),
                        newKeyNamed("accKey"),
                        cryptoCreate(TOKEN_TREASURY).key("treasuryKey"),
                        cryptoCreate(ACCOUNT).key("accKey"),
                        tokenCreate(NFT)
                                .tokenType(NON_FUNGIBLE_UNIQUE)
                                .supplyType(TokenSupplyType.INFINITE)
                                .supplyKey(SUPPLY_KEY)
                                .initialSupply(0)
                                .treasury(TOKEN_TREASURY)
                                .wipeKey(WIPE_KEY),
                        tokenAssociate(ACCOUNT, NFT),
                        getTokenInfo(NFT).logged(),
                        mintToken(NFT, List.of(ByteString.copyFromUtf8("memo"), ByteString.copyFromUtf8(MEMO_2))),
                        getTokenInfo(NFT).logged(),
                        cryptoTransfer(movingUnique(NFT, 2L).between(TOKEN_TREASURY, ACCOUNT)))
                .when(wipeTokenAccount(NFT, ACCOUNT, List.of(2L)).via(WIPE_TXN))
                .then(
                        getAccountInfo(ACCOUNT).hasOwnedNfts(0),
                        getAccountInfo(TOKEN_TREASURY).hasOwnedNfts(1),
                        getTokenInfo(NFT).hasTotalSupply(1).logged(),
                        getTokenNftInfo(NFT, 2).hasCostAnswerPrecheck(INVALID_NFT_ID),
                        getTokenNftInfo(NFT, 1).hasSerialNum(1),
                        wipeTokenAccount(NFT, ACCOUNT, List.of(1L)).hasKnownStatus(ACCOUNT_DOES_NOT_OWN_WIPED_NFT));
    }

    @HapiTest
    @Tag(MATS)
    final Stream<DynamicTest> wipeRespectsConstraints() {
        return defaultHapiSpec("WipeRespectsConstraints")
                .given(
                        newKeyNamed(SUPPLY_KEY),
                        newKeyNamed(WIPE_KEY),
                        cryptoCreate(TOKEN_TREASURY),
                        cryptoCreate(ACCOUNT),
                        tokenCreate(NFT)
                                .tokenType(NON_FUNGIBLE_UNIQUE)
                                .supplyType(TokenSupplyType.INFINITE)
                                .supplyKey(SUPPLY_KEY)
                                .initialSupply(0)
                                .treasury(TOKEN_TREASURY)
                                .wipeKey(WIPE_KEY),
                        tokenAssociate(ACCOUNT, NFT),
                        mintToken(NFT, List.of(metadata("memo"), metadata(MEMO_2)))
                                .via(MINT_TXN),
                        cryptoTransfer(movingUnique(NFT, 1, 2).between(TOKEN_TREASURY, ACCOUNT)))
                .when()
                .then(wipeTokenAccount(
                                // This ID range needs to be exclusively positive (i.e. not zero)
                                NFT, ACCOUNT, LongStream.range(1, 1001).boxed().collect(Collectors.toList()))
                        .hasPrecheck(BATCH_SIZE_LIMIT_EXCEEDED));
    }

    @HapiTest // here
    @Tag(MATS)
    final Stream<DynamicTest> commonWipeFailsWhenInvokedOnUniqueToken() {
        return defaultHapiSpec("CommonWipeFailsWhenInvokedOnUniqueToken")
                .given(
                        newKeyNamed(SUPPLY_KEY),
                        newKeyNamed(WIPE_KEY),
                        cryptoCreate(TOKEN_TREASURY),
                        cryptoCreate(ACCOUNT),
                        tokenCreate(NFT)
                                .tokenType(NON_FUNGIBLE_UNIQUE)
                                .supplyType(TokenSupplyType.INFINITE)
                                .supplyKey(SUPPLY_KEY)
                                .initialSupply(0)
                                .treasury(TOKEN_TREASURY)
                                .wipeKey(WIPE_KEY),
                        tokenAssociate(ACCOUNT, NFT),
                        mintToken(NFT, List.of(metadata("memo"))),
                        cryptoTransfer(movingUnique(NFT, 1).between(TOKEN_TREASURY, ACCOUNT)))
                .when()
                .then(
                        wipeTokenAccount(NFT, ACCOUNT, 1L)
                                .hasKnownStatus(INVALID_WIPING_AMOUNT)
                                .via(WIPE_TXN),
                        // no new totalSupply
                        getTokenInfo(NFT).hasTotalSupply(1),
                        // no tx record
                        getTxnRecord(WIPE_TXN).showsNoTransfers(),
                        // verify balance not decreased
                        getAccountInfo(ACCOUNT).hasOwnedNfts(1),
                        getAccountBalance(ACCOUNT).hasTokenBalance(NFT, 1));
    }

    @HapiTest // here
    @Tag(MATS)
    final Stream<DynamicTest> uniqueWipeFailsWhenInvokedOnFungibleToken() { // invokes unique wipe on fungible tokens
        return defaultHapiSpec("UniqueWipeFailsWhenInvokedOnFungibleToken")
                .given(
                        newKeyNamed(WIPE_KEY),
                        cryptoCreate(TOKEN_TREASURY),
                        cryptoCreate(ACCOUNT),
                        tokenCreate(A_TOKEN)
                                .tokenType(TokenType.FUNGIBLE_COMMON)
                                .initialSupply(10)
                                .treasury(TOKEN_TREASURY)
                                .wipeKey(WIPE_KEY),
                        tokenAssociate(ACCOUNT, A_TOKEN),
                        cryptoTransfer(TokenMovement.moving(5, A_TOKEN).between(TOKEN_TREASURY, ACCOUNT)))
                .when(
                        wipeTokenAccount(A_TOKEN, ACCOUNT, List.of(1L, 2L)).via("wipeTx"),
                        wipeTokenAccount(A_TOKEN, ACCOUNT, List.of())
                                .hasKnownStatus(SUCCESS)
                                .via("wipeEmptySerialTx"))
                .then(
                        getTokenInfo(A_TOKEN).hasTotalSupply(10),
                        getTxnRecord("wipeTx").showsNoTransfers(),
                        getAccountBalance(ACCOUNT).hasTokenBalance(A_TOKEN, 5));
    }

    @HapiTest
    @Tag(MATS)
    final Stream<DynamicTest> wipeFailsWithInvalidSerialNumber() {
        return defaultHapiSpec("WipeFailsWithInvalidSerialNumber")
                .given(
                        newKeyNamed(SUPPLY_KEY),
                        newKeyNamed(WIPE_KEY),
                        cryptoCreate(TOKEN_TREASURY),
                        cryptoCreate(ACCOUNT),
                        tokenCreate(NFT)
                                .tokenType(NON_FUNGIBLE_UNIQUE)
                                .supplyType(TokenSupplyType.INFINITE)
                                .supplyKey(SUPPLY_KEY)
                                .initialSupply(0)
                                .treasury(TOKEN_TREASURY)
                                .wipeKey(WIPE_KEY),
                        tokenAssociate(ACCOUNT, NFT),
                        mintToken(NFT, List.of(metadata("memo"), metadata("memo")))
                                .via(MINT_TXN))
                .when()
                .then(wipeTokenAccount(NFT, ACCOUNT, List.of(-5L, -6L)).hasPrecheck(INVALID_NFT_ID));
    }

    @HapiTest
    final Stream<DynamicTest> mintUniqueTokenReceiptCheck() {
        final var mintTransferTxn = "mintTransferTxn";
        return defaultHapiSpec("mintUniqueTokenReceiptCheck")
                .given(
                        cryptoCreate(TOKEN_TREASURY),
                        cryptoCreate(FIRST_USER),
                        newKeyNamed(SUPPLY_KEY),
                        tokenCreate(A_TOKEN)
                                .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                                .initialSupply(0)
                                .supplyKey(SUPPLY_KEY)
                                .treasury(TOKEN_TREASURY))
                .when(mintToken(A_TOKEN, List.of(metadata("memo"))).via(mintTransferTxn))
                .then(
                        UtilVerbs.withOpContext((spec, opLog) -> {
                            var mintNft = getTxnRecord(mintTransferTxn);
                            allRunFor(spec, mintNft);
                            var tokenTransferLists = mintNft.getResponseRecord().getTokenTransferListsList();
                            Assertions.assertEquals(1, tokenTransferLists.size());
                            tokenTransferLists.forEach(tokenTransferList -> {
                                Assertions.assertEquals(
                                        1,
                                        tokenTransferList.getNftTransfersList().size());
                                tokenTransferList.getNftTransfersList().forEach(nftTransfers -> {
                                    Assertions.assertEquals(
                                            AccountID.newBuilder()
                                                    .setAccountNum(0)
                                                    .build(),
                                            nftTransfers.getSenderAccountID());
                                    Assertions.assertEquals(
                                            TxnUtils.asId(TOKEN_TREASURY, spec), nftTransfers.getReceiverAccountID());
                                    Assertions.assertEquals(1L, nftTransfers.getSerialNumber());
                                });
                            });
                        }),
                        getTxnRecord(mintTransferTxn).logged(),
                        getReceipt(mintTransferTxn).logged());
    }

    @HapiTest
    final Stream<DynamicTest> tokenDissociateHappyPath() {
        return defaultHapiSpec("tokenDissociateHappyPath")
                .given(
                        newKeyNamed(SUPPLY_KEY),
                        cryptoCreate(TOKEN_TREASURY),
                        cryptoCreate("acc"),
                        tokenCreate(NFT)
                                .initialSupply(0)
                                .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                                .supplyType(TokenSupplyType.INFINITE)
                                .supplyKey(SUPPLY_KEY)
                                .treasury(TOKEN_TREASURY))
                .when(tokenAssociate("acc", NFT))
                .then(
                        tokenDissociate("acc", NFT).hasKnownStatus(SUCCESS),
                        getAccountInfo("acc").hasNoTokenRelationship(NFT));
    }

    @HapiTest
    @Tag(MATS)
    final Stream<DynamicTest> tokenDissociateFailsIfAccountOwnsUniqueTokens() {
        return defaultHapiSpec("tokenDissociateFailsIfAccountOwnsUniqueTokens")
                .given(
                        newKeyNamed(SUPPLY_KEY),
                        cryptoCreate(TOKEN_TREASURY),
                        cryptoCreate("acc"),
                        tokenCreate(NFT)
                                .initialSupply(0)
                                .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                                .supplyType(TokenSupplyType.INFINITE)
                                .supplyKey(SUPPLY_KEY)
                                .treasury(TOKEN_TREASURY))
                .when(tokenAssociate("acc", NFT), mintToken(NFT, List.of(metadata(MEMO_1), metadata(MEMO_2))))
                .then(
                        cryptoTransfer(TokenMovement.movingUnique(NFT, 1, 2).between(TOKEN_TREASURY, "acc")),
                        tokenDissociate("acc", NFT).hasKnownStatus(ACCOUNT_STILL_OWNS_NFTS));
    }

    protected Logger getResultsLogger() {
        return log;
    }
}
