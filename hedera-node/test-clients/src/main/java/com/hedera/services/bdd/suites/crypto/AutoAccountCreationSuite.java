// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.crypto;

import static com.google.protobuf.ByteString.copyFromUtf8;
import static com.hedera.node.app.hapi.utils.EthSigsUtils.recoverAddressFromPubKey;
import static com.hedera.services.bdd.junit.TestTags.CRYPTO;
import static com.hedera.services.bdd.spec.HapiPropertySource.asAccount;
import static com.hedera.services.bdd.spec.HapiPropertySource.asAccountString;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.accountWith;
import static com.hedera.services.bdd.spec.assertions.ContractInfoAsserts.contractWith;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.includingFungibleMovement;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAliasedAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAutoCreatedAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getContractInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getReceipt;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.queries.crypto.ExpectedTokenRel.relationshipWith;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.isEndOfStakingPeriodRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.createDefaultContract;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoDeleteAliased;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoUpdate;
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
import static com.hedera.services.bdd.suites.contract.Utils.accountIdFromEvmAddress;
import static com.hedera.services.bdd.suites.contract.Utils.asSolidityAddress;
import static com.hedera.services.bdd.suites.contract.Utils.ocWith;
import static com.hedera.services.bdd.suites.contract.hapi.ContractUpdateSuite.ADMIN_KEY;
import static com.hedera.services.bdd.suites.crypto.AutoCreateUtils.updateSpecFor;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_DELETED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_REPEATED_IN_ACCOUNT_AMOUNTS;
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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.spec.keys.KeyShape;
import com.hedera.services.bdd.spec.queries.meta.HapiGetTxnRecord;
import com.hedera.services.bdd.suites.contract.Utils;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.ContractID;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.KeyList;
import com.hederahashgraph.api.proto.java.ThresholdKey;
import com.hederahashgraph.api.proto.java.Timestamp;
import com.hederahashgraph.api.proto.java.TokenID;
import com.hederahashgraph.api.proto.java.TokenSupplyType;
import com.hederahashgraph.api.proto.java.TokenTransferList;
import com.hederahashgraph.api.proto.java.TokenType;
import com.hederahashgraph.api.proto.java.TransactionRecord;
import com.hederahashgraph.api.proto.java.TransferList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(CRYPTO)
public class AutoAccountCreationSuite {

    private static final long INITIAL_BALANCE = 1000L;
    private static final ByteString ALIAS_CONTENT = ByteString.copyFromUtf8(
            "a479462fba67674b5a41acfb16cb6828626b61d3f389fa611005a45754130e5c749073c0b1b791596430f4a54649cc8a3f6d28147dd4099070a5c3c4811d1771");
    private static final Key VALID_ED_25519_KEY =
            Key.newBuilder().setEd25519(ALIAS_CONTENT).build();
    private static final ByteString VALID_25519_ALIAS = VALID_ED_25519_KEY.toByteString();
    private static final String AUTO_MEMO = "";
    public static final String LAZY_MEMO = "";
    public static final String VALID_ALIAS = "validAlias";
    private static final String PAYER = "payer";
    private static final String TRANSFER_TXN = "transferTxn";
    private static final String ALIAS = "alias";
    private static final String PAYER_1 = "payer1";
    private static final String ALIAS_2 = "alias2";
    private static final String PAYER_4 = "payer4";
    private static final String TRANSFER_TXN_2 = "transferTxn2";
    private static final String TRANSFER_ALIAS = "transferAlias";
    public static final String A_TOKEN = "tokenA";
    private static final String B_TOKEN = "tokenB";
    public static final String NFT_INFINITE_SUPPLY_TOKEN = "nftA";
    private static final String NFT_FINITE_SUPPLY_TOKEN = "nftB";
    private static final String MULTI_KEY = "multi";
    public static final String PARTY = "party";
    private static final String COUNTERPARTY = "counterparty";

    private static final String CIVILIAN = "somebody";
    public static final String TOKEN_A_CREATE = "tokenACreateTxn";

    private static final String TOKEN_B_CREATE = "tokenBCreateTxn";
    public static final String NFT_CREATE = "nftCreateTxn";
    private static final String SPONSOR = "autoCreateSponsor";
    public static final String LAZY_CREATE_SPONSOR = "lazyCreateSponsor";

    private static final long EXPECTED_HBAR_TRANSFER_AUTO_CREATION_FEE = 39_376_619L;
    private static final long EXPECTED_MULTI_TOKEN_TRANSFER_AUTO_CREATION_FEE = 39_376_619L;
    private static final long EXPECTED_SINGLE_TOKEN_TRANSFER_AUTO_CREATE_FEE = 39_376_619L;
    private static final long EXPECTED_ASSOCIATION_FEE = 41666666L;

    public static final String CRYPTO_TRANSFER_RECEIVER = "cryptoTransferReceiver";
    public static final String TRUE = "true";
    public static final String FALSE = "false";
    private static final String HBAR_XFER = "hbarXfer";
    private static final String NFT_XFER = "nftXfer";
    private static final String FT_XFER = "ftXfer";

    @HapiTest
    final Stream<DynamicTest> aliasedPayerDoesntWork() {
        return hapiTest(
                newKeyNamed(ALIAS),
                newKeyNamed(ALIAS_2),
                cryptoCreate(PAYER_4).balance(INITIAL_BALANCE * ONE_HBAR),
                cryptoTransfer(tinyBarsFromToWithAlias(PAYER_4, ALIAS, 2 * ONE_HUNDRED_HBARS))
                        .via(TRANSFER_TXN),
                withOpContext((spec, opLog) -> updateSpecFor(spec, ALIAS)),
                getAliasedAccountInfo(ALIAS)
                        .has(accountWith().expectedBalanceWithChargedUsd((2 * ONE_HUNDRED_HBARS), 0, 0)),
                // pay with aliased id
                cryptoTransfer(tinyBarsFromToWithAlias(ALIAS, ALIAS_2, ONE_HUNDRED_HBARS))
                        .payingWithAliased(ALIAS)
                        .hasPrecheck(PAYER_ACCOUNT_NOT_FOUND),
                // pay with regular accountID
                cryptoTransfer(tinyBarsFromToWithAlias(ALIAS, ALIAS_2, ONE_HUNDRED_HBARS))
                        .payingWith(ALIAS));
    }

    @HapiTest
    final Stream<DynamicTest> canAutoCreateWithHbarAndTokenTransfers() {
        final var initialTokenSupply = 1000;
        return hapiTest(
                newKeyNamed(VALID_ALIAS),
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
                cryptoTransfer(
                                movingHbar(10L).between(CIVILIAN, VALID_ALIAS),
                                moving(1, A_TOKEN).between(CIVILIAN, VALID_ALIAS))
                        .signedBy(DEFAULT_PAYER, CIVILIAN)
                        .via(TRANSFER_TXN),
                getTxnRecord(TRANSFER_TXN).andAllChildRecords().logged(),
                getAliasedAccountInfo(VALID_ALIAS)
                        .has(accountWith().balance(10L))
                        .hasToken(relationshipWith(A_TOKEN)));
    }

    @HapiTest
    final Stream<DynamicTest> repeatedAliasInSameTransferListFails() {
        final AtomicReference<TokenID> ftId = new AtomicReference<>();
        final AtomicReference<TokenID> nftId = new AtomicReference<>();
        final AtomicReference<AccountID> partyId = new AtomicReference<>();
        final AtomicReference<AccountID> counterId = new AtomicReference<>();
        final AtomicReference<ByteString> partyAlias = new AtomicReference<>();
        final AtomicReference<ByteString> counterAlias = new AtomicReference<>();
        return hapiTest(
                newKeyNamed(VALID_ALIAS),
                newKeyNamed(MULTI_KEY),
                newKeyNamed(VALID_ALIAS),
                cryptoCreate(PARTY).maxAutomaticTokenAssociations(2),
                cryptoCreate(COUNTERPARTY).maxAutomaticTokenAssociations(2),
                cryptoCreate(TOKEN_TREASURY).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(2),
                tokenCreate(A_TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
                        .initialSupply(Long.MAX_VALUE)
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
                cryptoCreate(CIVILIAN).balance(10 * ONE_HBAR),
                cryptoCreate(SPONSOR).balance(INITIAL_BALANCE * ONE_HBAR).maxAutomaticTokenAssociations(10),
                tokenAssociate(SPONSOR, NFT_INFINITE_SUPPLY_TOKEN),
                cryptoTransfer(movingUnique(NFT_INFINITE_SUPPLY_TOKEN, 1L, 2L).between(TOKEN_TREASURY, SPONSOR)),
                getAccountInfo(SPONSOR).logged(),
                getAccountInfo(TOKEN_TREASURY).logged(),
                withOpContext((spec, opLog) -> {
                    final var registry = spec.registry();
                    ftId.set(registry.getTokenID(A_TOKEN));
                    nftId.set(registry.getTokenID(NFT_INFINITE_SUPPLY_TOKEN));
                    partyId.set(registry.getAccountID(PARTY));
                    counterId.set(registry.getAccountID(COUNTERPARTY));
                    partyAlias.set(ByteString.copyFrom(asSolidityAddress(partyId.get())));
                    counterAlias.set(ByteString.copyFrom(asSolidityAddress(counterId.get())));

                    cryptoTransfer((x, b) -> b.addTokenTransfers(TokenTransferList.newBuilder()
                                    .addTransfers(aaWith(spec, SPONSOR, -1))
                                    .addTransfers(aaWith(asAccount("0.0." + partyAlias.get()), +1))
                                    .addTransfers(aaWith(spec, TOKEN_TREASURY, -1))
                                    .addTransfers(aaWith(asAccount("0.0." + partyAlias.get()), +1))))
                            .signedBy(DEFAULT_PAYER, PARTY, SPONSOR)
                            .hasKnownStatus(ACCOUNT_REPEATED_IN_ACCOUNT_AMOUNTS);
                }));
    }

    @HapiTest
    final Stream<DynamicTest> autoCreateWithNftFallBackFeeFails() {
        final var firstRoyaltyCollector = "firstRoyaltyCollector";
        return hapiTest(
                newKeyNamed(VALID_ALIAS),
                newKeyNamed(MULTI_KEY),
                newKeyNamed(VALID_ALIAS),
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
                // auto creating an account using a nft with fall back royalty fee fails
                cryptoTransfer(movingUnique(NFT_INFINITE_SUPPLY_TOKEN, 1, 2).between(SPONSOR, VALID_ALIAS))
                        .payingWith(CIVILIAN)
                        .signedBy(CIVILIAN, SPONSOR, VALID_ALIAS)
                        .hasKnownStatus(INSUFFICIENT_SENDER_ACCOUNT_BALANCE_FOR_CUSTOM_FEE),
                getAccountInfo(SPONSOR).hasOwnedNfts(2).hasToken(relationshipWith(NFT_INFINITE_SUPPLY_TOKEN)),
                getAccountInfo(TOKEN_TREASURY),
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
                        .signedBy(SPONSOR, VALID_ALIAS)
                        .logged(),
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
                cryptoTransfer(
                        movingUnique(NFT_FINITE_SUPPLY_TOKEN, 3L, 4L).between(TOKEN_TREASURY, CIVILIAN),
                        movingUnique(NFT_INFINITE_SUPPLY_TOKEN, 1L, 2L).between(TOKEN_TREASURY, CIVILIAN)),
                getAccountInfo(CIVILIAN)
                        .hasToken(relationshipWith(NFT_FINITE_SUPPLY_TOKEN))
                        .hasToken(relationshipWith(NFT_INFINITE_SUPPLY_TOKEN))
                        .has(accountWith().balance(civilianBal)),
                cryptoTransfer(
                                movingUnique(NFT_FINITE_SUPPLY_TOKEN, 3, 4).between(CIVILIAN, VALID_ALIAS),
                                movingUnique(NFT_INFINITE_SUPPLY_TOKEN, 1, 2).between(CIVILIAN, VALID_ALIAS))
                        .via(multiNftTransfer)
                        .payingWith(CIVILIAN)
                        .signedBy(CIVILIAN, VALID_ALIAS),
                getTxnRecord(multiNftTransfer)
                        .andAllChildRecords()
                        .hasPriority(recordWith().autoAssociationCount(2))
                        .hasNonStakingChildRecordCount(1)
                        .logged(),
                childRecordsCheck(
                        multiNftTransfer,
                        SUCCESS,
                        recordWith().status(SUCCESS).fee(EXPECTED_MULTI_TOKEN_TRANSFER_AUTO_CREATION_FEE)),
                getAliasedAccountInfo(VALID_ALIAS)
                        .has(accountWith().balance(0).maxAutoAssociations(-1).ownedNfts(4))
                        .logged()
                // A single extra byte in the signature map will cost just ~130 tinybar more, so allowing
                // a delta of 2600 tinybar will stabilize this test indefinitely (the spec would have to
                // randomly choose two public keys with a shared prefix of length 10, which is...unlikely)
                //                        getAccountInfo(CIVILIAN)
                //                                .has(accountWith().approxBalance((long) (civilianBal -
                // approxTransferFee), 2600))
                );
    }

    @HapiTest
    final Stream<DynamicTest> canAutoCreateWithNftTransferToEvmAddress() {
        final var civilianBal = 10 * ONE_HBAR;
        final var nftTransfer = "multiNftTransfer";
        final AtomicReference<Timestamp> parentConsTime = new AtomicReference<>();
        final AtomicBoolean hasNodeStakeUpdate = new AtomicBoolean(false);

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
                        .exposingAllTo(records -> hasNodeStakeUpdate.set(
                                records.size() > 1 && isEndOfStakingPeriodRecord(records.get(1))))
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
                cryptoTransfer(
                                moving(100, A_TOKEN).between(TOKEN_TREASURY, CIVILIAN),
                                moving(100, B_TOKEN).between(TOKEN_TREASURY, CIVILIAN))
                        .via("transferAToSponsor"),
                getAccountInfo(TOKEN_TREASURY)
                        .hasToken(relationshipWith(B_TOKEN).balance(900)),
                getAccountInfo(TOKEN_TREASURY)
                        .hasToken(relationshipWith(A_TOKEN).balance(900)),
                getAccountInfo(CIVILIAN).hasToken(relationshipWith(A_TOKEN).balance(100)),
                getAccountInfo(CIVILIAN).hasToken(relationshipWith(B_TOKEN).balance(100)),

                /* --- transfer same token type to alias --- */
                cryptoTransfer(
                                moving(10, A_TOKEN).between(CIVILIAN, VALID_ALIAS),
                                moving(10, B_TOKEN).between(CIVILIAN, VALID_ALIAS))
                        .via(multiTokenXfer)
                        .payingWith(CIVILIAN)
                        .signedBy(CIVILIAN, VALID_ALIAS)
                        .logged(),
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
                cryptoTransfer(moving(10, B_TOKEN).between(CIVILIAN, VALID_ALIAS))
                        .via("newXfer")
                        .payingWith(CIVILIAN)
                        .signedBy(CIVILIAN, VALID_ALIAS, TOKEN_TREASURY),
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
                cryptoTransfer(moving(100, A_TOKEN).between(TOKEN_TREASURY, CIVILIAN)),
                cryptoTransfer(
                                moving(10, A_TOKEN).between(CIVILIAN, VALID_ALIAS),
                                movingHbar(1).between(CIVILIAN, FUNDING))
                        .fee(50 * ONE_HBAR)
                        .payingWith(CIVILIAN)
                        .signedBy(CIVILIAN),
                getAccountBalance(CIVILIAN)
                        .exposingBalanceTo(balance -> totalAutoCreationFees.set(ONE_HUNDRED_HBARS - balance - 1)),
                logIt(spec -> String.format("Total auto-creation fees: %d", totalAutoCreationFees.get())),
                sourcing(() -> cryptoCreate(secondPayer)
                        .maxAutomaticTokenAssociations(1)
                        .balance(totalAutoCreationFees.get())),
                cryptoTransfer(moving(100, A_TOKEN).between(TOKEN_TREASURY, secondPayer)),
                newKeyNamed(secondAliasKey),
                sourcing(() -> cryptoTransfer(
                                moving(10, A_TOKEN).between(secondPayer, secondAliasKey),
                                movingHbar(1).between(secondPayer, FUNDING))
                        .fee(totalAutoCreationFees.get() - 2)
                        .payingWith(secondPayer)
                        .signedBy(secondPayer)
                        .hasKnownStatusFrom(INSUFFICIENT_PAYER_BALANCE, INSUFFICIENT_ACCOUNT_BALANCE)),
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
        final long approxTransferFee = 1162008L;

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
                cryptoTransfer(
                                moving(100, A_TOKEN).between(TOKEN_TREASURY, CIVILIAN),
                                moving(100, B_TOKEN).between(TOKEN_TREASURY, CIVILIAN))
                        .via("transferAToSponsor"),
                getAccountInfo(TOKEN_TREASURY)
                        .hasToken(relationshipWith(B_TOKEN).balance(900)),
                getAccountInfo(TOKEN_TREASURY)
                        .hasToken(relationshipWith(A_TOKEN).balance(900)),
                getAccountInfo(CIVILIAN).hasToken(relationshipWith(A_TOKEN).balance(100)),
                getAccountInfo(CIVILIAN).hasToken(relationshipWith(B_TOKEN).balance(100)),

                /* --- transfer same token type to alias --- */
                cryptoTransfer(
                                moving(10, A_TOKEN).between(CIVILIAN, VALID_ALIAS),
                                moving(10, A_TOKEN).between(TOKEN_TREASURY, VALID_ALIAS))
                        .via(sameTokenXfer)
                        .payingWith(CIVILIAN)
                        .signedBy(CIVILIAN, VALID_ALIAS, TOKEN_TREASURY)
                        .logged(),
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
                /* --- transfer another token to created alias.
                Alias created will have -1 as max-auto associations */
                cryptoTransfer(moving(10, B_TOKEN).between(CIVILIAN, VALID_ALIAS))
                        .via("failedXfer")
                        .payingWith(CIVILIAN)
                        .signedBy(CIVILIAN, VALID_ALIAS, TOKEN_TREASURY));
    }

    @HapiTest
    final Stream<DynamicTest> noStakePeriodStartIfNotStakingToNode() {
        final var user = "user";
        final var contract = "contract";
        return hapiTest(
                newKeyNamed(ADMIN_KEY),
                cryptoCreate(user).key(ADMIN_KEY).stakedNodeId(0L),
                createDefaultContract(contract).adminKey(ADMIN_KEY).stakedNodeId(0L),
                getAccountInfo(user).has(accountWith().someStakePeriodStart()),
                getContractInfo(contract).has(contractWith().someStakePeriodStart()),
                cryptoUpdate(user).newStakedAccountId(contract),
                contractUpdate(contract).newStakedAccountId(user),
                getAccountInfo(user).has(accountWith().noStakePeriodStart()),
                getContractInfo(contract).has(contractWith().noStakePeriodStart()));
    }

    @HapiTest
    final Stream<DynamicTest> hollowAccountCreationWithCryptoTransfer() {
        final var initialTokenSupply = 1000;
        final AtomicReference<TokenID> ftId = new AtomicReference<>();
        final AtomicReference<TokenID> nftId = new AtomicReference<>();
        final AtomicReference<AccountID> civilianId = new AtomicReference<>();
        final AtomicReference<ByteString> civilianAlias = new AtomicReference<>();
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
                    final var registry = spec.registry();
                    final var ecdsaKey = spec.registry()
                            .getKey(SECP_256K1_SOURCE_KEY)
                            .getECDSASecp256K1()
                            .toByteArray();
                    final var evmAddressBytes = ByteString.copyFrom(recoverAddressFromPubKey(ecdsaKey));
                    ftId.set(registry.getTokenID(A_TOKEN));
                    nftId.set(registry.getTokenID(NFT_INFINITE_SUPPLY_TOKEN));
                    civilianId.set(registry.getAccountID(CIVILIAN));
                    civilianAlias.set(ByteString.copyFrom(asSolidityAddress(civilianId.get())));
                    evmAddress.set(evmAddressBytes);
                }),
                withOpContext((spec, opLog) -> {
                    /* hollow account created with transfer as expected */
                    final var cryptoTransferWithLazyCreate = cryptoTransfer(
                                    movingHbar(ONE_HUNDRED_HBARS).between(LAZY_CREATE_SPONSOR, evmAddress.get()),
                                    moving(5, A_TOKEN).between(CIVILIAN, evmAddress.get()),
                                    movingUnique(NFT_INFINITE_SUPPLY_TOKEN, 1L).between(CIVILIAN, evmAddress.get()))
                            .hasKnownStatus(SUCCESS)
                            .via(TRANSFER_TXN);

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
                    final var hbarTransfer = cryptoTransfer(
                                    tinyBarsFromTo(CIVILIAN, evmAddress.get(), ONE_HUNDRED_HBARS))
                            .hasKnownStatus(SUCCESS)
                            .via(TRANSFER_TXN_2);

                    final var fungibleTokenTransfer = cryptoTransfer(
                                    moving(5, A_TOKEN).between(CIVILIAN, evmAddress.get()))
                            .signedBy(DEFAULT_PAYER, CIVILIAN)
                            .via(TRANSFER_TXN_2);

                    final var nftTransfer = cryptoTransfer(
                                    movingUnique(NFT_INFINITE_SUPPLY_TOKEN, 2L).between(CIVILIAN, evmAddress.get()))
                            .signedBy(DEFAULT_PAYER, CIVILIAN)
                            .hasKnownStatus(SUCCESS)
                            .via(TRANSFER_TXN_2);

                    final var getHollowAccountInfoAfterTransfers = getAliasedAccountInfo(evmAddress.get())
                            .hasToken(relationshipWith(A_TOKEN).balance(10))
                            .hasToken(
                                    relationshipWith(NFT_INFINITE_SUPPLY_TOKEN).balance(2))
                            .has(accountWith()
                                    .hasEmptyKey()
                                    .noAlias()
                                    .expectedBalanceWithChargedUsd(2 * ONE_HUNDRED_HBARS, 0, 0));

                    allRunFor(
                            spec, hbarTransfer, fungibleTokenTransfer, nftTransfer, getHollowAccountInfoAfterTransfers);
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
                    final var controlledOp = cryptoTransfer((sameSpec, b) -> {
                                final var sponsorId = spec.registry().getAccountID(LAZY_CREATE_SPONSOR);
                                final var underfundedId = spec.registry().getAccountID(underfunded);
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
                            .via(TRANSFER_TXN);
                    allRunFor(spec, controlledOp);
                }),
                getTxnRecord(TRANSFER_TXN).andAllChildRecords().logged(),
                getAliasedAccountInfo(SECP_256K1_SOURCE_KEY).nodePayment(123).hasAnswerOnlyPrecheck(INVALID_ACCOUNT_ID),
                sourcing(() -> cryptoTransfer(tinyBarsFromTo(
                                LAZY_CREATE_SPONSOR, ByteString.copyFrom(targetAddress.get()), ONE_HUNDRED_HBARS))
                        .signedBy(DEFAULT_PAYER, LAZY_CREATE_SPONSOR)
                        .via(secondTransferTxn)),
                getAliasedAccountInfo(SECP_256K1_SOURCE_KEY).logged());
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
                sortedCryptoTransfer(
                                tinyBarsFromAccountToAlias(CIVILIAN, ed25519SourceKey, ONE_HUNDRED_HBARS),
                                tinyBarsFromAccountToAlias(GENESIS, secp256k1SourceKey, ONE_HUNDRED_HBARS))
                        /* Sort the transfer list so the accounts are created in a predictable order (the
                         * serialized bytes of an Ed25519 are always lexicographically prior to the serialized
                         * bytes of a secp256k1 key, so now the first child record will _always_ be for the
                         * ed25519 auto-creation). */
                        .payingWith(GENESIS)
                        .via(autoCreation),
                withOpContext((spec, opLog) -> updateSpecFor(spec, ed25519SourceKey)),
                withOpContext((spec, opLog) -> updateSpecFor(spec, secp256k1SourceKey)),
                getTxnRecord(autoCreation)
                        .andAllChildRecords()
                        .hasNoAliasInChildRecord(0)
                        .hasNoAliasInChildRecord(1)
                        .logged(),
                getAutoCreatedAccountBalance(ed25519SourceKey)
                        .hasExpectedAccountID()
                        .logged(),
                getAutoCreatedAccountBalance(secp256k1SourceKey)
                        .hasExpectedAccountID()
                        .logged(),
                getAliasedAccountInfo(ed25519SourceKey)
                        .hasExpectedAliasKey()
                        .hasExpectedAccountID()
                        .has(accountWith().expectedBalanceWithChargedUsd(ONE_HUNDRED_HBARS, 0, 0))
                        .logged(),
                getAliasedAccountInfo(secp256k1SourceKey)
                        .hasExpectedAliasKey()
                        .hasExpectedAccountID()
                        .has(accountWith().expectedBalanceWithChargedUsd(ONE_HUNDRED_HBARS, 0, 0))
                        .logged());
    }

    @HapiTest
    final Stream<DynamicTest> aliasCanBeUsedOnManyAccountsNotAsAlias() {
        return hapiTest(
                /* have alias key on other accounts and tokens not as alias */
                newKeyNamed(VALID_ALIAS),
                cryptoCreate(PAYER).key(VALID_ALIAS).balance(INITIAL_BALANCE * ONE_HBAR),
                tokenCreate(PAYER).adminKey(VALID_ALIAS),
                tokenCreate(PAYER).supplyKey(VALID_ALIAS),
                tokenCreate("a").treasury(PAYER),
                /* auto account is created */
                cryptoTransfer(tinyBarsFromToWithAlias(PAYER, VALID_ALIAS, ONE_HUNDRED_HBARS))
                        .via(TRANSFER_TXN),
                /* get transaction record and validate the child record has alias bytes as expected */
                getTxnRecord(TRANSFER_TXN)
                        .andAllChildRecords()
                        .hasNonStakingChildRecordCount(1)
                        .hasNoAliasInChildRecord(0),
                getAccountInfo(PAYER)
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
                cryptoTransfer(tinyBarsFromToWithAlias(PAYER_1, ALIAS, ONE_HUNDRED_HBARS))
                        .via(TRANSFER_TXN),
                getTxnRecord(TRANSFER_TXN).andAllChildRecords().logged(),
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
                                .receiverSigReq(false))
                        .logged());
    }

    @HapiTest
    final Stream<DynamicTest> autoAccountCreationWorksWhenUsingAliasOfDeletedAccount() {
        return hapiTest(
                newKeyNamed(ALIAS),
                newKeyNamed(ALIAS_2),
                cryptoCreate(PAYER).balance(INITIAL_BALANCE * ONE_HBAR),
                cryptoTransfer(tinyBarsFromToWithAlias(PAYER, ALIAS, ONE_HUNDRED_HBARS))
                        .via("txn"),
                withOpContext((spec, opLog) -> updateSpecFor(spec, ALIAS)),
                getTxnRecord("txn").hasNonStakingChildRecordCount(1).logged(),
                cryptoDeleteAliased(ALIAS)
                        .transfer(PAYER)
                        .hasKnownStatus(SUCCESS)
                        .signedBy(ALIAS, PAYER, DEFAULT_PAYER)
                        .purging(),
                cryptoTransfer(tinyBarsFromToWithAlias(PAYER, ALIAS, ONE_HUNDRED_HBARS))
                        .via("txn2")
                        .hasKnownStatus(ACCOUNT_DELETED)

                /* need to validate it creates after expiration */
                //						sleepFor(60000L),
                //						cryptoTransfer(
                //								HapiCryptoTransfer.tinyBarsFromToWithAlias("payer", "alias",
                // ONE_HUNDRED_HBARS)).via(
                //								"txn2"),
                //						getTxnRecord("txn2").hasChildRecordCount(1).logged()
                );
    }

    @HapiTest
    final Stream<DynamicTest> transferFromAliasToAlias() {
        return hapiTest(
                newKeyNamed(ALIAS),
                newKeyNamed(ALIAS_2),
                cryptoCreate(PAYER_4).balance(INITIAL_BALANCE * ONE_HBAR),
                cryptoTransfer(tinyBarsFromToWithAlias(PAYER_4, ALIAS, 2 * ONE_HUNDRED_HBARS))
                        .via("txn"),
                withOpContext((spec, opLog) -> updateSpecFor(spec, ALIAS)),
                getTxnRecord("txn").andAllChildRecords().logged(),
                getAliasedAccountInfo(ALIAS)
                        .has(accountWith().expectedBalanceWithChargedUsd((2 * ONE_HUNDRED_HBARS), 0, 0)),
                /* transfer from an alias that was auto created to a new alias, validate account is created */
                cryptoTransfer(tinyBarsFromToWithAlias(ALIAS, ALIAS_2, ONE_HUNDRED_HBARS))
                        .via(TRANSFER_TXN_2),
                getTxnRecord(TRANSFER_TXN_2).andAllChildRecords().logged(),
                getAliasedAccountInfo(ALIAS).has(accountWith().expectedBalanceWithChargedUsd(ONE_HUNDRED_HBARS, 0, 0)),
                getAliasedAccountInfo(ALIAS_2)
                        .has(accountWith().expectedBalanceWithChargedUsd(ONE_HUNDRED_HBARS, 0, 0)));
    }

    @HapiTest
    final Stream<DynamicTest> transferFromAliasToAccount() {
        final var payer = PAYER_4;
        final var alias = ALIAS;
        return hapiTest(
                newKeyNamed(alias),
                cryptoCreate(payer).balance(INITIAL_BALANCE * ONE_HBAR),
                cryptoCreate("randomAccount").balance(0L).payingWith(payer),
                cryptoTransfer(tinyBarsFromToWithAlias(payer, alias, 2 * ONE_HUNDRED_HBARS))
                        .via("txn"),
                withOpContext((spec, opLog) -> updateSpecFor(spec, alias)),
                getTxnRecord("txn").andAllChildRecords().logged(),
                getAliasedAccountInfo(alias)
                        .has(accountWith().expectedBalanceWithChargedUsd((2 * ONE_HUNDRED_HBARS), 0, 0)),
                /* transfer from an alias that was auto created to a new alias, validate account is created */
                cryptoTransfer(tinyBarsFromToWithAlias(alias, "randomAccount", ONE_HUNDRED_HBARS))
                        .via(TRANSFER_TXN_2),
                getTxnRecord(TRANSFER_TXN_2).andAllChildRecords().hasNonStakingChildRecordCount(0),
                getAliasedAccountInfo(alias).has(accountWith().expectedBalanceWithChargedUsd(ONE_HUNDRED_HBARS, 0, 0)));
    }

    @HapiTest
    final Stream<DynamicTest> transferToAccountAutoCreatedUsingAccount() {
        return hapiTest(
                newKeyNamed(TRANSFER_ALIAS),
                cryptoCreate(PAYER).balance(INITIAL_BALANCE * ONE_HBAR),
                cryptoTransfer(tinyBarsFromToWithAlias(PAYER, TRANSFER_ALIAS, ONE_HUNDRED_HBARS))
                        .via("txn"),
                withOpContext((spec, opLog) -> updateSpecFor(spec, TRANSFER_ALIAS)),
                getTxnRecord("txn").andAllChildRecords().logged(),
                /* get the account associated with alias and transfer */
                withOpContext((spec, opLog) -> {
                    final var aliasAccount = spec.registry()
                            .getAccountID(spec.registry()
                                    .getKey(TRANSFER_ALIAS)
                                    .toByteString()
                                    .toStringUtf8());

                    final var op = cryptoTransfer(
                                    tinyBarsFromTo(PAYER, asAccountString(aliasAccount), ONE_HUNDRED_HBARS))
                            .via(TRANSFER_TXN_2);
                    final var op2 =
                            getTxnRecord(TRANSFER_TXN_2).andAllChildRecords().logged();
                    final var op3 = getAccountInfo(PAYER)
                            .has(accountWith().balance((INITIAL_BALANCE * ONE_HBAR) - (2 * ONE_HUNDRED_HBARS)));
                    final var op4 = getAliasedAccountInfo(TRANSFER_ALIAS)
                            .has(accountWith().expectedBalanceWithChargedUsd((2 * ONE_HUNDRED_HBARS), 0, 0));
                    allRunFor(spec, op, op2, op3, op4);
                }));
    }

    @HapiTest
    final Stream<DynamicTest> transferToAccountAutoCreatedUsingAlias() {
        return hapiTest(
                newKeyNamed(ALIAS),
                cryptoCreate(PAYER).balance(INITIAL_BALANCE * ONE_HBAR),
                cryptoTransfer(tinyBarsFromToWithAlias(PAYER, ALIAS, ONE_HUNDRED_HBARS))
                        .via(TRANSFER_TXN),
                getTxnRecord(TRANSFER_TXN).andAllChildRecords().logged(),
                getAccountInfo(PAYER).has(accountWith().balance((INITIAL_BALANCE * ONE_HBAR) - ONE_HUNDRED_HBARS)),
                getAliasedAccountInfo(ALIAS).has(accountWith().expectedBalanceWithChargedUsd(ONE_HUNDRED_HBARS, 0, 0)),
                /* transfer using alias and not account number */
                cryptoTransfer(tinyBarsFromToWithAlias(PAYER, ALIAS, ONE_HUNDRED_HBARS))
                        .via(TRANSFER_TXN_2),
                getTxnRecord(TRANSFER_TXN_2)
                        .andAllChildRecords()
                        .hasNonStakingChildRecordCount(0)
                        .logged(),
                getAccountInfo(PAYER)
                        .has(accountWith().balance((INITIAL_BALANCE * ONE_HBAR) - (2 * ONE_HUNDRED_HBARS))),
                getAliasedAccountInfo(ALIAS)
                        .has(accountWith().expectedBalanceWithChargedUsd((2 * ONE_HUNDRED_HBARS), 0, 0)));
    }

    @HapiTest
    final Stream<DynamicTest> autoAccountCreationUnsupportedAlias() {
        final var threshKeyAlias = Key.newBuilder()
                .setThresholdKey(ThresholdKey.newBuilder()
                        .setThreshold(2)
                        .setKeys(KeyList.newBuilder()
                                .addKeys(Key.newBuilder().setEd25519(ByteString.copyFrom("aaa".getBytes())))
                                .addKeys(Key.newBuilder().setECDSASecp256K1(ByteString.copyFrom("bbbb".getBytes())))
                                .addKeys(Key.newBuilder().setEd25519(ByteString.copyFrom("cccccc".getBytes())))))
                .build()
                .toByteString();
        final var keyListAlias = Key.newBuilder()
                .setKeyList(KeyList.newBuilder()
                        .addKeys(Key.newBuilder().setEd25519(ByteString.copyFrom("aaaaaa".getBytes())))
                        .addKeys(Key.newBuilder().setECDSASecp256K1(ByteString.copyFrom("bbbbbbb".getBytes()))))
                .build()
                .toByteString();
        final var contractKeyAlias = Key.newBuilder()
                .setContractID(ContractID.newBuilder().setContractNum(100L))
                .build()
                .toByteString();
        final var delegateContractKeyAlias = Key.newBuilder()
                .setDelegatableContractId(ContractID.newBuilder().setContractNum(100L))
                .build()
                .toByteString();

        return hapiTest(
                cryptoCreate(PAYER).balance(INITIAL_BALANCE * ONE_HBAR),
                cryptoTransfer(tinyBarsFromTo(PAYER, threshKeyAlias, ONE_HUNDRED_HBARS))
                        .hasKnownStatus(INVALID_ALIAS_KEY)
                        .via("transferTxnThreshKey"),
                cryptoTransfer(tinyBarsFromTo(PAYER, keyListAlias, ONE_HUNDRED_HBARS))
                        .hasKnownStatus(INVALID_ALIAS_KEY)
                        .via("transferTxnKeyList"),
                cryptoTransfer(tinyBarsFromTo(PAYER, contractKeyAlias, ONE_HUNDRED_HBARS))
                        .hasKnownStatus(INVALID_ALIAS_KEY)
                        .via("transferTxnContract"),
                cryptoTransfer(tinyBarsFromTo(PAYER, delegateContractKeyAlias, ONE_HUNDRED_HBARS))
                        .hasKnownStatus(INVALID_ALIAS_KEY)
                        .via("transferTxnKeyDelegate"));
    }

    @HapiTest
    final Stream<DynamicTest> autoAccountCreationBadAlias() {
        final var invalidAlias = VALID_25519_ALIAS.substring(0, 10);

        return hapiTest(
                cryptoCreate(PAYER).balance(INITIAL_BALANCE * ONE_HBAR),
                cryptoTransfer(tinyBarsFromTo(PAYER, invalidAlias, ONE_HUNDRED_HBARS))
                        .hasKnownStatus(INVALID_ALIAS_KEY)
                        .via("transferTxnBad"));
    }

    @HapiTest
    final Stream<DynamicTest> autoAccountCreationsHappyPath() {
        final var creationTime = new AtomicLong();
        final long transferFee = 188608L;
        return hapiTest(
                newKeyNamed(VALID_ALIAS),
                cryptoCreate(CIVILIAN).balance(10 * ONE_HBAR),
                cryptoCreate(PAYER).balance(10 * ONE_HBAR),
                cryptoCreate(SPONSOR).balance(INITIAL_BALANCE * ONE_HBAR),
                cryptoTransfer(
                                tinyBarsFromToWithAlias(SPONSOR, VALID_ALIAS, ONE_HUNDRED_HBARS),
                                tinyBarsFromToWithAlias(CIVILIAN, VALID_ALIAS, ONE_HBAR))
                        .via(TRANSFER_TXN)
                        .payingWith(PAYER),
                getReceipt(TRANSFER_TXN).andAnyChildReceipts().hasChildAutoAccountCreations(1),
                getTxnRecord(TRANSFER_TXN).andAllChildRecords().logged(),
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
                            .hasNoAliasInChildRecord(0)
                            .logged();
                    allRunFor(spec, lookup);
                    final var sponsor = spec.registry().getAccountID(SPONSOR);
                    final var payer = spec.registry().getAccountID(PAYER);
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
                                .memo(AUTO_MEMO))
                        .logged()));
    }

    @SuppressWarnings("java:S5960")
    static void assertAliasBalanceAndFeeInChildRecord(
            final TransactionRecord parent,
            final TransactionRecord child,
            final AccountID sponsor,
            final AccountID defaultPayer,
            final long newAccountFunding,
            final long approxTransferFee,
            final long squashedFees) {
        long receivedBalance = 0;
        long payerBalWithAutoCreationFee = 0;
        for (final var adjust : parent.getTransferList().getAccountAmountsList()) {
            final var id = adjust.getAccountID();
            if (!(id.getAccountNum() < 100
                    || id.equals(sponsor)
                    || id.equals(defaultPayer)
                    || id.getAccountNum() == 800
                    || id.getAccountNum() == 801)) {
                receivedBalance = adjust.getAmount();
            }

            // sum of all deductions from the payer along with auto creation fee
            if ((id.getAccountNum() <= 98
                    || id.equals(defaultPayer)
                    || id.getAccountNum() == 800
                    || id.getAccountNum() == 801)) {
                payerBalWithAutoCreationFee += adjust.getAmount();
            }
        }
        assertEquals(newAccountFunding, receivedBalance, "Transferred incorrect amount to alias");
        final var childRecordFee = child.getTransactionFee();
        assertNotEquals(0, childRecordFee);
        // A single extra byte in the signature map will cost just ~40 tinybar more, so allowing
        // a delta of 1000 tinybar is sufficient to stabilize this test indefinitely
        final var permissibleDelta = 1000L;
        final var observedDelta = Math.abs(parent.getTransactionFee() - approxTransferFee - squashedFees);
        assertTrue(
                observedDelta <= permissibleDelta,
                "Parent record did not specify the transfer fee (expected ~" + (approxTransferFee + squashedFees)
                        + " but was " + parent.getTransactionFee() + ")");
        assertEquals(0, payerBalWithAutoCreationFee, "Auto creation fee is deducted from payer");
    }

    @HapiTest
    final Stream<DynamicTest> multipleAutoAccountCreations() {
        return hapiTest(
                cryptoCreate(PAYER).balance(INITIAL_BALANCE * ONE_HBAR),
                newKeyNamed("alias1"),
                newKeyNamed(ALIAS_2),
                newKeyNamed("alias3"),
                newKeyNamed("alias4"),
                newKeyNamed("alias5"),
                assumingNoStakingChildRecordCausesMaxChildRecordsExceeded(
                        cryptoTransfer(
                                tinyBarsFromToWithAlias(PAYER, "alias1", ONE_HUNDRED_HBARS),
                                tinyBarsFromToWithAlias(PAYER, ALIAS_2, ONE_HUNDRED_HBARS),
                                tinyBarsFromToWithAlias(PAYER, "alias3", ONE_HUNDRED_HBARS)),
                        "multipleAutoAccountCreates",
                        getTxnRecord("multipleAutoAccountCreates")
                                .hasNonStakingChildRecordCount(3)
                                .logged(),
                        getAccountInfo(PAYER)
                                .has(accountWith().balance((INITIAL_BALANCE * ONE_HBAR) - 3 * ONE_HUNDRED_HBARS)),
                        cryptoTransfer(
                                        tinyBarsFromToWithAlias(PAYER, "alias4", 7 * ONE_HUNDRED_HBARS),
                                        tinyBarsFromToWithAlias(PAYER, "alias5", 100))
                                .via("failedAutoCreate")
                                .hasKnownStatus(INSUFFICIENT_ACCOUNT_BALANCE),
                        getTxnRecord("failedAutoCreate")
                                .hasNonStakingChildRecordCount(0)
                                .logged(),
                        getAccountInfo(PAYER)
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
                    var op1 = cryptoTransfer((s, b) -> b.setTransfers(TransferList.newBuilder()
                                    .addAccountAmounts(Utils.aaWith(spec, partyAlias.get(), -2 * ONE_HBAR))
                                    .addAccountAmounts(Utils.aaWith(spec, counterAlias.get(), +2 * ONE_HBAR))))
                            .signedBy(DEFAULT_PAYER, PARTY)
                            .via(HBAR_XFER);

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
    final Stream<DynamicTest> transferHbarsToECDSAKey() {

        final AtomicReference<ByteString> evmAddress = new AtomicReference<>();
        final var transferToECDSA = "transferToЕCDSA";

        return hapiTest(
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(PAYER).balance(10 * ONE_HBAR),
                withOpContext((spec, opLog) -> {
                    final var registry = spec.registry();
                    final var ecdsaKey = registry.getKey(SECP_256K1_SOURCE_KEY);
                    final var tmp = ecdsaKey.getECDSASecp256K1().toByteArray();
                    final var addressBytes = recoverAddressFromPubKey(tmp);
                    final var evmAddressBytes = ByteString.copyFrom(addressBytes);
                    evmAddress.set(evmAddressBytes);
                }),
                withOpContext((spec, opLog) -> {
                    final var hbarCreateTransfer = cryptoTransfer(
                                    tinyBarsFromAccountToAlias(PAYER, SECP_256K1_SOURCE_KEY, ONE_HBAR))
                            .via(transferToECDSA);

                    final var op1 = cryptoTransfer(tinyBarsFromTo(PAYER, evmAddress.get(), ONE_HBAR));

                    final var op2 = getAliasedAccountInfo(SECP_256K1_SOURCE_KEY)
                            .has(accountWith()
                                    .balance(2 * ONE_HBAR)
                                    .alias(SECP_256K1_SOURCE_KEY)
                                    .key(SECP_256K1_SOURCE_KEY)
                                    .autoRenew(THREE_MONTHS_IN_SECONDS)
                                    .receiverSigReq(false)
                                    .memo(AUTO_MEMO));

                    final var op3 = childRecordsCheck(
                            transferToECDSA,
                            SUCCESS,
                            recordWith()
                                    .evmAddress(evmAddress.get())
                                    .hasNoAlias()
                                    .status(SUCCESS));

                    allRunFor(spec, hbarCreateTransfer, op1, op2, op3);
                }),
                getTxnRecord(transferToECDSA).andAllChildRecords());
    }

    @HapiTest
    final Stream<DynamicTest> transferFungibleToEVMAddressAlias() {

        final var fungibleToken = "fungibleToken";
        final AtomicReference<TokenID> ftId = new AtomicReference<>();
        final AtomicReference<AccountID> partyId = new AtomicReference<>();
        final AtomicReference<byte[]> partyAlias = new AtomicReference<>();
        final AtomicReference<byte[]> counterAlias = new AtomicReference<>();

        return hapiTest(
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(PARTY).balance(INITIAL_BALANCE * ONE_HBAR).maxAutomaticTokenAssociations(2),
                tokenCreate(fungibleToken).treasury(PARTY).initialSupply(1_000_000),
                withOpContext((spec, opLog) -> {
                    final var registry = spec.registry();
                    final var ecdsaKey = registry.getKey(SECP_256K1_SOURCE_KEY);
                    final var tmp = ecdsaKey.getECDSASecp256K1().toByteArray();
                    final var addressBytes = recoverAddressFromPubKey(tmp);
                    assert addressBytes != null;
                    ftId.set(registry.getTokenID(fungibleToken));
                    partyId.set(registry.getAccountID(PARTY));
                    partyAlias.set(asSolidityAddress(partyId.get()));
                    counterAlias.set(addressBytes);
                }),
                withOpContext((spec, opLog) -> {
                    opLog.warn("Creating hollow account with alias " + Arrays.toString(counterAlias.get()));
                    opLog.warn("From party with alias " + Arrays.toString(partyAlias.get()));
                    /* hollow account created with fungible token transfer as expected */
                    final var cryptoTransferWithLazyCreate = cryptoTransfer(
                                    (s, b) -> b.addTokenTransfers(TokenTransferList.newBuilder()
                                            .setToken(ftId.get())
                                            .addTransfers(Utils.aaWith(spec, partyAlias.get(), -500))
                                            .addTransfers(Utils.aaWith(spec, counterAlias.get(), +500))))
                            .signedBy(DEFAULT_PAYER, PARTY)
                            .via(FT_XFER);

                    final var evmAliasStr = ByteString.copyFrom(counterAlias.get());
                    final var getHollowAccountInfoAfterCreation = getAliasedAccountInfo(evmAliasStr)
                            .hasToken(relationshipWith(fungibleToken).balance(500))
                            .has(accountWith()
                                    .hasEmptyKey()
                                    .noAlias()
                                    .autoRenew(THREE_MONTHS_IN_SECONDS)
                                    .receiverSigReq(false)
                                    .memo(LAZY_MEMO));

                    final var txnRequiringHollowAccountSignature = tokenCreate(A_TOKEN)
                            .adminKey(SECP_256K1_SOURCE_KEY)
                            .signedBy(SECP_256K1_SOURCE_KEY)
                            .hasPrecheck(INVALID_SIGNATURE);

                    allRunFor(
                            spec,
                            cryptoTransferWithLazyCreate,
                            getHollowAccountInfoAfterCreation,
                            txnRequiringHollowAccountSignature);

                    /* transfers of hbar or fungible tokens to the hollow account should succeed */
                    final var hbarTransfer = cryptoTransfer(tinyBarsFromTo(PARTY, evmAliasStr, ONE_HUNDRED_HBARS))
                            .hasKnownStatus(SUCCESS)
                            .via(TRANSFER_TXN_2);

                    final var fungibleTokenTransfer = cryptoTransfer(
                                    moving(5, fungibleToken).between(PARTY, evmAliasStr))
                            .signedBy(DEFAULT_PAYER, PARTY)
                            .via(TRANSFER_TXN_2);

                    // and transfers to the 0.0.ECDSA_BYTES alias should succeed.
                    final var fungibleTokenTransferToECDSAKeyAlias = cryptoTransfer(
                                    moving(1, fungibleToken).between(PARTY, SECP_256K1_SOURCE_KEY))
                            .signedBy(DEFAULT_PAYER, PARTY)
                            .via(TRANSFER_TXN_2);

                    final var getHollowAccountInfoAfterTransfers = getAliasedAccountInfo(evmAliasStr)
                            .hasToken(relationshipWith(fungibleToken).balance(506))
                            .has(accountWith()
                                    .hasEmptyKey()
                                    .noAlias()
                                    .expectedBalanceWithChargedUsd(ONE_HUNDRED_HBARS, 0, 0));

                    allRunFor(
                            spec,
                            hbarTransfer,
                            fungibleTokenTransfer,
                            fungibleTokenTransferToECDSAKeyAlias,
                            getHollowAccountInfoAfterTransfers);
                }),
                getTxnRecord(FT_XFER)
                        .hasNonStakingChildRecordCount(1)
                        .hasChildRecords(recordWith().status(SUCCESS).memo(LAZY_MEMO))
                        .hasPriority(recordWith().autoAssociationCount(1)));
    }

    @HapiTest
    final Stream<DynamicTest> transferNonFungibleToEVMAddressAlias() {
        final var nonFungibleToken = "nonFungibleToken";
        final AtomicReference<TokenID> nftId = new AtomicReference<>();
        final AtomicReference<AccountID> partyId = new AtomicReference<>();
        final AtomicReference<ByteString> partyAlias = new AtomicReference<>();
        final AtomicReference<ByteString> counterAlias = new AtomicReference<>();

        return hapiTest(
                newKeyNamed(MULTI_KEY),
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(PARTY).balance(INITIAL_BALANCE * ONE_HBAR).maxAutomaticTokenAssociations(2),
                tokenCreate(nonFungibleToken)
                        .initialSupply(0)
                        .treasury(PARTY)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .supplyKey(MULTI_KEY),
                mintToken(nonFungibleToken, List.of(copyFromUtf8("a"), copyFromUtf8("b"))),
                withOpContext((spec, opLog) -> {
                    final var registry = spec.registry();
                    final var ecdsaKey = registry.getKey(SECP_256K1_SOURCE_KEY);
                    final var tmp = ecdsaKey.getECDSASecp256K1().toByteArray();
                    final var addressBytes = recoverAddressFromPubKey(tmp);
                    assert addressBytes != null;
                    final var evmAddressBytes = ByteString.copyFrom(addressBytes);
                    nftId.set(registry.getTokenID(nonFungibleToken));
                    partyId.set(registry.getAccountID(PARTY));
                    partyAlias.set(ByteString.copyFrom(asSolidityAddress(partyId.get())));
                    counterAlias.set(evmAddressBytes);
                }),
                withOpContext((spec, opLog) -> {
                    /* hollow account created with nft transfer as expected */
                    var cryptoTransferWithLazyCreate = cryptoTransfer(
                                    (s, b) -> b.addTokenTransfers(TokenTransferList.newBuilder()
                                            .setToken(nftId.get())
                                            .addNftTransfers(ocWith(
                                                    accountIdFromEvmAddress(spec, partyAlias.get()),
                                                    accountIdFromEvmAddress(spec, counterAlias.get()),
                                                    1L))))
                            .signedBy(DEFAULT_PAYER, PARTY)
                            .via(NFT_XFER);

                    final var getHollowAccountInfoAfterCreation = getAliasedAccountInfo(counterAlias.get())
                            .hasToken(relationshipWith(nonFungibleToken).balance(1))
                            .has(accountWith()
                                    .hasEmptyKey()
                                    .noAlias()
                                    .autoRenew(THREE_MONTHS_IN_SECONDS)
                                    .receiverSigReq(false)
                                    .memo(LAZY_MEMO));

                    final var txnRequiringHollowAccountSignature = tokenCreate(A_TOKEN)
                            .adminKey(SECP_256K1_SOURCE_KEY)
                            .signedBy(SECP_256K1_SOURCE_KEY)
                            .hasPrecheck(INVALID_SIGNATURE);

                    allRunFor(
                            spec,
                            cryptoTransferWithLazyCreate,
                            getHollowAccountInfoAfterCreation,
                            txnRequiringHollowAccountSignature);

                    /* transfers of hbar or nft to the hollow account should succeed */
                    final var hbarTransfer = cryptoTransfer(
                                    tinyBarsFromTo(PARTY, counterAlias.get(), ONE_HUNDRED_HBARS))
                            .hasKnownStatus(SUCCESS)
                            .via(TRANSFER_TXN_2);

                    final var nftTransfer = cryptoTransfer(
                                    movingUnique(nonFungibleToken, 2L).between(PARTY, counterAlias.get()))
                            .signedBy(DEFAULT_PAYER, PARTY)
                            .hasKnownStatus(SUCCESS)
                            .via(TRANSFER_TXN_2);

                    // and transfers to the 0.0.ECDSA_BYTES alias should succeed.
                    final var transferToECDSAKeyAlias = cryptoTransfer(
                                    movingHbar(ONE_HBAR).between(PARTY, SECP_256K1_SOURCE_KEY))
                            .signedBy(DEFAULT_PAYER, PARTY)
                            .hasKnownStatus(SUCCESS)
                            .via(TRANSFER_TXN_2);

                    final var getHollowAccountInfoAfterTransfers = getAliasedAccountInfo(counterAlias.get())
                            .hasToken(relationshipWith(nonFungibleToken).balance(2))
                            .has(accountWith()
                                    .hasEmptyKey()
                                    .noAlias()
                                    .expectedBalanceWithChargedUsd(ONE_HUNDRED_HBARS + ONE_HBAR, 0, 0));

                    allRunFor(
                            spec,
                            hbarTransfer,
                            nftTransfer,
                            transferToECDSAKeyAlias,
                            getHollowAccountInfoAfterTransfers);
                }),
                getTxnRecord(NFT_XFER)
                        .hasNonStakingChildRecordCount(1)
                        .hasPriority(recordWith().autoAssociationCount(1))
                        .hasChildRecords(recordWith().status(SUCCESS).memo(LAZY_MEMO)));
    }

    @HapiTest
    final Stream<DynamicTest> cannotAutoCreateWithTxnToLongZero() {
        final AtomicReference<byte[]> evmAddress = new AtomicReference<>();

        return hapiTest(
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(PAYER).balance(10 * ONE_HBAR),
                withOpContext((spec, opLog) -> {
                    final var registry = spec.registry();
                    final var ecdsaKey = registry.getKey(SECP_256K1_SOURCE_KEY);
                    final var tmp = ecdsaKey.getECDSASecp256K1().toByteArray();
                    final var addressBytes = recoverAddressFromPubKey(tmp);
                    evmAddress.set(addressBytes);
                }),
                withOpContext((spec, opLog) -> {
                    final var evmAddressStr = ByteString.copyFrom(evmAddress.get());
                    final var validTransfer = cryptoTransfer(tinyBarsFromTo(PAYER, evmAddressStr, ONE_HBAR))
                            .hasKnownStatus(SUCCESS)
                            .via("passedTxn");

                    final var longZeroAddress = ByteString.copyFrom(asSolidityAddress(spec, 5555));

                    final var invalidTransferToLongZero = cryptoTransfer(
                                    tinyBarsFromTo(PAYER, longZeroAddress, ONE_HBAR))
                            .hasKnownStatusFrom(INVALID_ACCOUNT_ID, INVALID_ALIAS_KEY)
                            .via("failedTxn");

                    allRunFor(spec, validTransfer, invalidTransferToLongZero);
                }),
                withOpContext((spec, opLog) -> {
                    final var evmAddressStr = ByteString.copyFrom(evmAddress.get());
                    getTxnRecord("failedTxn").logged();
                    getTxnRecord("passedTxn")
                            .hasNonStakingChildRecordCount(1)
                            .hasChildRecords(
                                    recordWith().status(SUCCESS).memo(LAZY_MEMO).alias(evmAddressStr));
                }));
    }

    @HapiTest
    final Stream<DynamicTest> hollowAccountCompletionWithSimultaneousPropertiesUpdate() {
        return hapiTest(
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(LAZY_CREATE_SPONSOR).balance(INITIAL_BALANCE * ONE_HBAR),
                cryptoCreate(CRYPTO_TRANSFER_RECEIVER).balance(INITIAL_BALANCE * ONE_HBAR),
                withOpContext((spec, opLog) -> {
                    final var ecdsaKey = spec.registry()
                            .getKey(SECP_256K1_SOURCE_KEY)
                            .getECDSASecp256K1()
                            .toByteArray();
                    final var evmAddress = ByteString.copyFrom(recoverAddressFromPubKey(ecdsaKey));
                    final var op = cryptoTransfer(tinyBarsFromTo(LAZY_CREATE_SPONSOR, evmAddress, ONE_HUNDRED_HBARS))
                            .hasKnownStatus(SUCCESS)
                            .via(TRANSFER_TXN);

                    final HapiGetTxnRecord hapiGetTxnRecord =
                            getTxnRecord(TRANSFER_TXN).andAllChildRecords().logged();

                    allRunFor(spec, op, hapiGetTxnRecord);

                    final AccountID newAccountID = hapiGetTxnRecord
                            .getFirstNonStakingChildRecord()
                            .getReceipt()
                            .getAccountID();
                    spec.registry().saveAccountId(SECP_256K1_SOURCE_KEY, newAccountID);
                }));
    }
}
