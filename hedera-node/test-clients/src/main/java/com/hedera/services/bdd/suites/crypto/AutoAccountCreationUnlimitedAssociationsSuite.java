// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.crypto;

import static com.hedera.node.app.hapi.utils.EthSigsUtils.recoverAddressFromPubKey;
import static com.hedera.services.bdd.junit.TestTags.CRYPTO;
import static com.hedera.services.bdd.spec.HapiSpec.customizedHapiTest;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.accountWith;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.keys.TrieSigMapGenerator.uniqueWithFullPrefixesFor;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAliasedAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getReceipt;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.asIdWithAlias;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.isEndOfStakingPeriodRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromToWithAlias;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.assertionsHold;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.childRecordsCheck;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_CONTRACT_RECEIVER;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SHAPE;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SOURCE_KEY;
import static com.hedera.services.bdd.suites.HapiSuite.THREE_MONTHS_IN_SECONDS;
import static com.hedera.services.bdd.suites.HapiSuite.TOKEN_TREASURY;
import static com.hedera.services.bdd.suites.contract.Utils.asSolidityAddress;
import static com.hedera.services.bdd.suites.crypto.AutoAccountCreationSuite.NFT_INFINITE_SUPPLY_TOKEN;
import static com.hedera.services.bdd.suites.crypto.AutoAccountCreationSuite.assertAliasBalanceAndFeeInChildRecord;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.spec.queries.meta.HapiGetTxnRecord;
import com.hedera.services.bdd.suites.contract.Utils;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.NftTransfer;
import com.hederahashgraph.api.proto.java.TokenID;
import com.hederahashgraph.api.proto.java.TokenSupplyType;
import com.hederahashgraph.api.proto.java.TokenTransferList;
import com.hederahashgraph.api.proto.java.TokenType;
import com.hederahashgraph.api.proto.java.TransferList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(CRYPTO)
public class AutoAccountCreationUnlimitedAssociationsSuite {

    public static final String TRUE = "true";
    public static final String FALSE = "false";
    public static final String LAZY_MEMO = "";
    public static final String VALID_ALIAS = "validAlias";
    public static final String A_TOKEN = "tokenA";
    public static final String PARTY = "party";
    private static final String PAYER = "payer";
    private static final String TRANSFER_TXN = "transferTxn";
    private static final String MULTI_KEY = "multi";
    private static final long INITIAL_BALANCE = 1000L;
    private static final String AUTO_MEMO = "";
    private static final String CIVILIAN = "somebody";
    private static final String SPONSOR = "autoCreateSponsor";
    private static final long EXPECTED_HBAR_TRANSFER_AUTO_CREATION_FEE = 39_376_619L;
    private static final String HBAR_XFER = "hbarXfer";
    private static final String FT_XFER = "ftXfer";
    private static final String NFT_XFER = "nftXfer";
    private static final String NFT_CREATE = "nftCreateTxn";

    @HapiTest
    final Stream<DynamicTest> autoAccountCreationsUnlimitedAssociationHappyPath() {
        final var creationTime = new AtomicLong();
        final long transferFee = 188608L;
        return customizedHapiTest(
                Map.of("memo.useSpecName", "false"),
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
                                .memo(AUTO_MEMO)
                                .maxAutoAssociations(-1))
                        .logged()));
    }

    @LeakyHapiTest(overrides = {"entities.unlimitedAutoAssociationsEnabled"})
    final Stream<DynamicTest> autoAccountCreationsUnlimitedAssociationsDisabled() {
        final var creationTime = new AtomicLong();
        final long transferFee = 188608L;
        return customizedHapiTest(
                Map.of("memo.useSpecName", "false"),
                overriding("entities.unlimitedAutoAssociationsEnabled", FALSE),
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
                                .memo(AUTO_MEMO)
                                .maxAutoAssociations(0))
                        .logged()));
    }

    @HapiTest
    final Stream<DynamicTest> transferHbarsToEVMAddressAliasUnlimitedAssociations() {
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
                    final var counterAliasStr = ByteString.copyFrom(counterAlias.get());

                    // account create with key than delete account
                    var accountCreate = cryptoCreate("testAccount")
                            .key(SECP_256K1_SOURCE_KEY)
                            .maxAutomaticTokenAssociations(-1)
                            .alias(counterAliasStr);

                    var getAccountInfo = getAccountInfo("testAccount")
                            .has(accountWith().key(SECP_256K1_SOURCE_KEY).maxAutoAssociations(-1));

                    var accountDelete = cryptoDelete("testAccount").hasKnownStatus(SUCCESS);

                    allRunFor(spec, accountCreate, getAccountInfo, accountDelete);

                    // create hollow account with the deleted account alias
                    var hollowAccount = cryptoTransfer((s, b) -> b.setTransfers(TransferList.newBuilder()
                                    .addAccountAmounts(Utils.aaWith(s, partyAlias.get(), -2 * ONE_HBAR))
                                    .addAccountAmounts(Utils.aaWith(s, counterAlias.get(), +2 * ONE_HBAR))))
                            .signedBy(DEFAULT_PAYER, PARTY)
                            .via(HBAR_XFER);

                    // verify hollow account creation
                    var hollowAccountCreated = getAliasedAccountInfo(counterAliasStr)
                            .has(accountWith()
                                    .expectedBalanceWithChargedUsd(2 * ONE_HBAR, 0, 0)
                                    .hasEmptyKey()
                                    .noAlias()
                                    .autoRenew(THREE_MONTHS_IN_SECONDS)
                                    .receiverSigReq(false)
                                    .maxAutoAssociations(-1)
                                    .memo(LAZY_MEMO));

                    final var txnRequiringHollowAccountSignature = tokenCreate(A_TOKEN)
                            .adminKey(SECP_256K1_SOURCE_KEY)
                            .signedBy(SECP_256K1_SOURCE_KEY)
                            .hasPrecheck(INVALID_SIGNATURE);

                    // get and save hollow account in registry
                    final HapiGetTxnRecord hapiGetTxnRecord =
                            getTxnRecord(HBAR_XFER).andAllChildRecords().assertingNothingAboutHashes();

                    allRunFor(
                            spec,
                            hollowAccount,
                            hollowAccountCreated,
                            txnRequiringHollowAccountSignature,
                            hapiGetTxnRecord);

                    if (!hapiGetTxnRecord.getChildRecords().isEmpty()) {
                        final var newAccountID = hapiGetTxnRecord
                                .getFirstNonStakingChildRecord()
                                .getReceipt()
                                .getAccountID();
                        spec.registry().saveAccountId(SECP_256K1_SOURCE_KEY, newAccountID);
                    }

                    // sign and pay for transfer with hollow account to complete the hollow account
                    final var completion = cryptoTransfer(tinyBarsFromTo(DEFAULT_PAYER, DEFAULT_CONTRACT_RECEIVER, 1))
                            .payingWith(SECP_256K1_SOURCE_KEY)
                            .sigMapPrefixes(uniqueWithFullPrefixesFor(SECP_256K1_SOURCE_KEY))
                            .hasKnownStatusFrom(SUCCESS);
                    allRunFor(spec, completion);

                    // verify completed hollow account
                    var completedAccount = getAliasedAccountInfo(ByteString.copyFrom(counterAlias.get()))
                            .has(accountWith()
                                    .expectedBalanceWithChargedUsd(2 * ONE_HBAR, 0.00015, 10)
                                    .key(SECP_256K1_SOURCE_KEY)
                                    .noAlias()
                                    .autoRenew(THREE_MONTHS_IN_SECONDS)
                                    .receiverSigReq(false)
                                    .maxAutoAssociations(-1)
                                    .newAssociationsFromSnapshot(SECP_256K1_SOURCE_KEY, Collections.EMPTY_LIST)
                                    .memo(LAZY_MEMO));

                    allRunFor(spec, completedAccount);
                }),
                getTxnRecord(HBAR_XFER)
                        .hasNonStakingChildRecordCount(1)
                        .hasChildRecords(recordWith().status(SUCCESS).memo(LAZY_MEMO)),
                cryptoTransfer(tinyBarsFromToWithAlias(PARTY, SECP_256K1_SOURCE_KEY, ONE_HBAR))
                        .hasKnownStatus(SUCCESS)
                        .via(TRANSFER_TXN),
                getAliasedAccountInfo(SECP_256K1_SOURCE_KEY).has(accountWith().maxAutoAssociations(-1)));
    }

    @HapiTest
    final Stream<DynamicTest> transferTokensToEVMAddressAliasUnlimitedAssociations() {
        final AtomicReference<AccountID> partyId = new AtomicReference<>();
        final AtomicReference<byte[]> partyAlias = new AtomicReference<>();
        final AtomicReference<byte[]> counterAlias = new AtomicReference<>();
        final AtomicReference<TokenID> ftId = new AtomicReference<>();

        return hapiTest(
                cryptoCreate(PARTY).maxAutomaticTokenAssociations(2),
                tokenCreate("vanilla")
                        .tokenType(FUNGIBLE_COMMON)
                        .treasury(PARTY)
                        .initialSupply(1_000),
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                withOpContext((spec, opLog) -> {
                    final var registry = spec.registry();
                    final var ecdsaKey = registry.getKey(SECP_256K1_SOURCE_KEY);
                    final var tmp = ecdsaKey.getECDSASecp256K1().toByteArray();
                    final var addressBytes = recoverAddressFromPubKey(tmp);
                    assert addressBytes != null;
                    ftId.set(registry.getTokenID("vanilla"));
                    partyId.set(registry.getAccountID(PARTY));
                    partyAlias.set(asSolidityAddress(partyId.get()));
                    counterAlias.set(addressBytes);
                }),
                withOpContext((spec, opLog) -> {

                    // account create with key than delete account
                    final var counterAliasStr = ByteString.copyFrom(counterAlias.get());
                    var accountCreate = cryptoCreate("testAccount")
                            .key(SECP_256K1_SOURCE_KEY)
                            .maxAutomaticTokenAssociations(-1)
                            .alias(counterAliasStr);

                    var getAccountInfo = getAccountInfo("testAccount")
                            .hasAlreadyUsedAutomaticAssociations(0)
                            .has(accountWith().key(SECP_256K1_SOURCE_KEY).maxAutoAssociations(-1));

                    var accountDelete = cryptoDelete("testAccount").hasKnownStatus(SUCCESS);

                    allRunFor(spec, accountCreate, getAccountInfo, accountDelete);

                    /* hollow account created with fungible token transfer as expected */
                    final var hollowAccount = cryptoTransfer(
                                    (s, b) -> b.addTokenTransfers(TokenTransferList.newBuilder()
                                            .setToken(ftId.get())
                                            .addTransfers(Utils.aaWith(s, partyAlias.get(), -500))
                                            .addTransfers(Utils.aaWith(s, counterAlias.get(), +500))))
                            .signedBy(DEFAULT_PAYER, PARTY)
                            .via(FT_XFER);

                    // verify hollow account
                    var hollowAccountCreated = getAliasedAccountInfo(counterAliasStr)
                            .has(accountWith()
                                    .hasEmptyKey()
                                    .noAlias()
                                    .autoRenew(THREE_MONTHS_IN_SECONDS)
                                    .receiverSigReq(false)
                                    .maxAutoAssociations(-1)
                                    .memo(LAZY_MEMO));

                    final HapiGetTxnRecord hapiGetTxnRecord =
                            getTxnRecord(FT_XFER).andAllChildRecords().assertingNothingAboutHashes();

                    allRunFor(spec, hollowAccount, hollowAccountCreated, hapiGetTxnRecord);

                    if (!hapiGetTxnRecord.getChildRecords().isEmpty()) {
                        final var newAccountID = hapiGetTxnRecord
                                .getFirstNonStakingChildRecord()
                                .getReceipt()
                                .getAccountID();
                        spec.registry().saveAccountId(SECP_256K1_SOURCE_KEY, newAccountID);
                    }

                    // transfer some hbars to the hollow account so that we can pay with it later
                    var hollowAccountTransfer = cryptoTransfer((s, b) -> b.setTransfers(TransferList.newBuilder()
                                    .addAccountAmounts(Utils.aaWith(s, partyAlias.get(), -2 * ONE_HBAR))
                                    .addAccountAmounts(Utils.aaWith(s, counterAlias.get(), +2 * ONE_HBAR))))
                            .signedBy(DEFAULT_PAYER, PARTY)
                            .via(HBAR_XFER);

                    // sign and pay for transfer with hollow account to complete the hollow account
                    final var completion = cryptoTransfer(tinyBarsFromTo(DEFAULT_PAYER, DEFAULT_CONTRACT_RECEIVER, 1))
                            .payingWith(SECP_256K1_SOURCE_KEY)
                            .sigMapPrefixes(uniqueWithFullPrefixesFor(SECP_256K1_SOURCE_KEY))
                            .hasKnownStatusFrom(SUCCESS);

                    final var completed = getAccountInfo(SECP_256K1_SOURCE_KEY)
                            .hasAlreadyUsedAutomaticAssociations(1)
                            .has(accountWith().maxAutoAssociations(-1).memo(LAZY_MEMO));

                    allRunFor(spec, hollowAccountTransfer, completion, completed);
                }));
    }

    @HapiTest
    final Stream<DynamicTest> transferNftToEVMAddressAliasUnlimitedAssociations() {
        final AtomicReference<AccountID> partyId = new AtomicReference<>();
        final AtomicReference<byte[]> partyAlias = new AtomicReference<>();
        final AtomicReference<AccountID> treasuryId = new AtomicReference<>();
        final AtomicReference<byte[]> counterAlias = new AtomicReference<>();
        final AtomicReference<TokenID> nftId = new AtomicReference<>();

        return hapiTest(
                cryptoCreate(PARTY).maxAutomaticTokenAssociations(2),
                cryptoCreate(TOKEN_TREASURY).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(2),
                newKeyNamed(MULTI_KEY),
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
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                withOpContext((spec, opLog) -> {
                    final var registry = spec.registry();
                    final var ecdsaKey = registry.getKey(SECP_256K1_SOURCE_KEY);
                    final var tmp = ecdsaKey.getECDSASecp256K1().toByteArray();
                    final var addressBytes = recoverAddressFromPubKey(tmp);
                    assert addressBytes != null;
                    counterAlias.set(addressBytes);
                    nftId.set(registry.getTokenID(NFT_INFINITE_SUPPLY_TOKEN));
                    partyId.set(registry.getAccountID(PARTY));
                    treasuryId.set(registry.getAccountID(TOKEN_TREASURY));
                    partyAlias.set(asSolidityAddress(partyId.get()));
                }),
                withOpContext((spec, opLog) -> {

                    // account create with key than delete account
                    final var counterAliasStr = ByteString.copyFrom(counterAlias.get());
                    var accountCreate = cryptoCreate("testAccount")
                            .key(SECP_256K1_SOURCE_KEY)
                            .maxAutomaticTokenAssociations(-1)
                            .alias(counterAliasStr);

                    var getAccountInfo = getAccountInfo("testAccount")
                            .hasAlreadyUsedAutomaticAssociations(0)
                            .has(accountWith().key(SECP_256K1_SOURCE_KEY).maxAutoAssociations(-1));

                    var accountDelete = cryptoDelete("testAccount").hasKnownStatus(SUCCESS);
                    allRunFor(spec, accountCreate, getAccountInfo, accountDelete);

                    /* hollow account created with NFT transfer as expected */
                    final var hollowAccount = cryptoTransfer(
                                    (s, b) -> b.addTokenTransfers(TokenTransferList.newBuilder()
                                            .setToken(nftId.get())
                                            .addNftTransfers(NftTransfer.newBuilder()
                                                    .setSerialNumber(1L)
                                                    .setSenderAccountID(treasuryId.get())
                                                    .setReceiverAccountID(asIdWithAlias(counterAliasStr)))))
                            .signedBy(MULTI_KEY, DEFAULT_PAYER, TOKEN_TREASURY)
                            .via(NFT_XFER);

                    // verify hollow account
                    var hollowAccountCreated = getAliasedAccountInfo(counterAliasStr)
                            .has(accountWith()
                                    .hasEmptyKey()
                                    .noAlias()
                                    .autoRenew(THREE_MONTHS_IN_SECONDS)
                                    .receiverSigReq(false)
                                    .maxAutoAssociations(-1)
                                    .memo(LAZY_MEMO));

                    final HapiGetTxnRecord hapiGetTxnRecord =
                            getTxnRecord(NFT_XFER).andAllChildRecords().assertingNothingAboutHashes();

                    allRunFor(spec, hollowAccount, hollowAccountCreated, hapiGetTxnRecord);

                    if (!hapiGetTxnRecord.getChildRecords().isEmpty()) {
                        final var newAccountID = hapiGetTxnRecord
                                .getFirstNonStakingChildRecord()
                                .getReceipt()
                                .getAccountID();
                        spec.registry().saveAccountId(SECP_256K1_SOURCE_KEY, newAccountID);
                    }

                    // transfer some hbars to the hollow account so that we can pay with it later
                    var hollowAccountTransfer = cryptoTransfer((s, b) -> b.setTransfers(TransferList.newBuilder()
                                    .addAccountAmounts(Utils.aaWith(s, partyAlias.get(), -2 * ONE_HBAR))
                                    .addAccountAmounts(Utils.aaWith(s, counterAlias.get(), +2 * ONE_HBAR))))
                            .signedBy(DEFAULT_PAYER, PARTY)
                            .via(HBAR_XFER);

                    // sign and pay for transfer with hollow account to complete the hollow account
                    final var completion = cryptoTransfer(tinyBarsFromTo(DEFAULT_PAYER, DEFAULT_CONTRACT_RECEIVER, 1))
                            .payingWith(SECP_256K1_SOURCE_KEY)
                            .sigMapPrefixes(uniqueWithFullPrefixesFor(SECP_256K1_SOURCE_KEY))
                            .hasKnownStatusFrom(SUCCESS);

                    final var completed = getAccountInfo(SECP_256K1_SOURCE_KEY)
                            .hasAlreadyUsedAutomaticAssociations(1)
                            .has(accountWith().maxAutoAssociations(-1).memo(LAZY_MEMO));

                    allRunFor(spec, hollowAccountTransfer, completion, completed);
                }));
    }
}
