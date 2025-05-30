// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.crypto;

import static com.hedera.node.app.hapi.utils.EthSigsUtils.recoverAddressFromPubKey;
import static com.hedera.services.bdd.junit.TestTags.CRYPTO;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.accountWith;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.assertions.TransferListAsserts.noCreditAboveNumber;
import static com.hedera.services.bdd.spec.keys.TrieSigMapGenerator.uniqueWithFullPrefixesFor;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAliasedAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAutoCreatedAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.queries.crypto.ExpectedTokenRel.relationshipWith;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.ethereumContractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromToWithAlias;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.accountAmount;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.assumingNoStakingChildRecordCausesMaxChildRecordsExceeded;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.childRecordsCheck;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.emptyChildRecordsCheck;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.tokenTransferList;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.EMPTY_KEY;
import static com.hedera.services.bdd.suites.HapiSuite.FIVE_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.RELAYER;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SHAPE;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SOURCE_KEY;
import static com.hedera.services.bdd.suites.HapiSuite.THREE_MONTHS_IN_SECONDS;
import static com.hedera.services.bdd.suites.HapiSuite.flattened;
import static com.hedera.services.bdd.suites.contract.Utils.asSolidityAddress;
import static com.hedera.services.bdd.suites.contract.hapi.ContractUpdateSuite.ADMIN_KEY;
import static com.hedera.services.bdd.suites.crypto.AutoCreateUtils.createHollowAccountFrom;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_ACCOUNT_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ALIAS_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.MAX_CHILD_RECORDS_EXCEEDED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.esaulpaugh.headlong.abi.Tuple;
import com.google.protobuf.ByteString;
import com.hedera.node.app.hapi.utils.ethereum.EthTxData;
import com.hedera.node.app.hapi.utils.ethereum.EthTxData.EthTransactionType;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.queries.crypto.HapiGetAccountInfo;
import com.hedera.services.bdd.spec.queries.meta.HapiGetTxnRecord;
import com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer;
import com.hedera.services.bdd.suites.contract.Utils;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.TokenID;
import com.hederahashgraph.api.proto.java.TokenTransferList;
import com.hederahashgraph.api.proto.java.TokenType;
import com.hederahashgraph.api.proto.java.TransferList;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(CRYPTO)
public class HollowAccountFinalizationSuite {
    private static final String ANOTHER_SECP_256K1_SOURCE_KEY = "anotherSecp256k1Alias";
    private static final String PAY_RECEIVABLE = "PayReceivable";
    private static final long INITIAL_BALANCE = 1000L;
    private static final String LAZY_MEMO = "";
    private static final String TRANSFER_TXN = "transferTxn";
    private static final String TRANSFER_TXN_2 = "transferTxn2";
    private static final String PARTY = "party";
    private static final String LAZY_CREATE_SPONSOR = "lazyCreateSponsor";
    private static final String CRYPTO_TRANSFER_RECEIVER = "cryptoTransferReceiver";
    private static final String FT_XFER = "ftXfer";
    private static final String TOKEN_TREASURY = "treasury";
    private static final String VANILLA_TOKEN = "TokenD";

    @HapiTest
    final Stream<DynamicTest> hollowAccountCompletionWithEthereumTransaction() {
        final String CONTRACT = "Fuse";
        return hapiTest(
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(RELAYER).balance(6 * ONE_MILLION_HBARS),
                cryptoCreate(LAZY_CREATE_SPONSOR).balance(INITIAL_BALANCE * ONE_HBAR),
                uploadInitCode(CONTRACT),
                withOpContext((spec, opLog) -> {
                    final var ecdsaKey = spec.registry()
                            .getKey(SECP_256K1_SOURCE_KEY)
                            .getECDSASecp256K1()
                            .toByteArray();
                    final var evmAddress = ByteString.copyFrom(recoverAddressFromPubKey(ecdsaKey));
                    final var op = cryptoTransfer(
                                    tinyBarsFromTo(LAZY_CREATE_SPONSOR, evmAddress, 2 * ONE_HUNDRED_HBARS))
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

                    final var op2 = ethereumContractCreate(CONTRACT)
                            .type(EthTxData.EthTransactionType.LEGACY_ETHEREUM)
                            .gasLimit(1_000_000)
                            .signingWith(SECP_256K1_SOURCE_KEY)
                            .payingWith(RELAYER)
                            .hasKnownStatus(SUCCESS)
                            .via(TRANSFER_TXN_2);

                    final var op3 = getAliasedAccountInfo(evmAddress)
                            .has(accountWith().key(SECP_256K1_SOURCE_KEY).noAlias());

                    final HapiGetTxnRecord hapiGetSecondTxnRecord =
                            getTxnRecord(TRANSFER_TXN_2).andAllChildRecords().logged();

                    allRunFor(spec, op2, op3, hapiGetSecondTxnRecord);
                }));
    }

    @HapiTest
    final Stream<DynamicTest> hollowAccountCompletionWithTokenTransfer() {
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
                    ftId.set(registry.getTokenID(fungibleToken));
                    partyId.set(registry.getAccountID(PARTY));
                    partyAlias.set(asSolidityAddress(partyId.get()));
                    counterAlias.set(addressBytes);
                }),
                withOpContext((spec, opLog) -> {
                    /* hollow account created with fungible token transfer as expected */
                    final var cryptoTransferWithLazyCreate = cryptoTransfer(
                                    (s, b) -> b.addTokenTransfers(TokenTransferList.newBuilder()
                                            .setToken(ftId.get())
                                            .addTransfers(Utils.aaWith(s, partyAlias.get(), -500))
                                            .addTransfers(Utils.aaWith(s, counterAlias.get(), +500))))
                            .signedBy(DEFAULT_PAYER, PARTY)
                            .via(FT_XFER);

                    final var getHollowAccountInfoAfterCreation = getAliasedAccountInfo(
                                    ByteString.copyFrom(counterAlias.get()))
                            .hasToken(relationshipWith(fungibleToken).balance(500))
                            .has(accountWith()
                                    .hasEmptyKey()
                                    .noAlias()
                                    .autoRenew(THREE_MONTHS_IN_SECONDS)
                                    .receiverSigReq(false)
                                    .memo(LAZY_MEMO));

                    final HapiGetTxnRecord hapiGetTxnRecord =
                            getTxnRecord(FT_XFER).andAllChildRecords().logged();

                    allRunFor(spec, cryptoTransferWithLazyCreate, getHollowAccountInfoAfterCreation, hapiGetTxnRecord);

                    final AccountID newAccountID = hapiGetTxnRecord
                            .getFirstNonStakingChildRecord()
                            .getReceipt()
                            .getAccountID();

                    spec.registry().saveAccountId(SECP_256K1_SOURCE_KEY, newAccountID);
                }),
                withOpContext((spec, opLog) -> {
                    final var counterAliasStr = ByteString.copyFrom(counterAlias.get());
                    final var hbarTransfer = cryptoTransfer(tinyBarsFromTo(PARTY, counterAliasStr, ONE_HUNDRED_HBARS))
                            .hasKnownStatus(SUCCESS)
                            .via(TRANSFER_TXN_2);

                    /* complete hollow account creation with fungible token transfer */
                    final var fungibleTokenTransfer = cryptoTransfer(
                                    moving(5, fungibleToken).between(PARTY, counterAliasStr))
                            .payingWith(SECP_256K1_SOURCE_KEY)
                            .sigMapPrefixes(uniqueWithFullPrefixesFor(SECP_256K1_SOURCE_KEY))
                            .via(TRANSFER_TXN_2);

                    final var cryptoCreateWithEVMAddress = cryptoCreate(PARTY)
                            .alias(counterAliasStr)
                            .hasPrecheck(INVALID_ALIAS_KEY)
                            .balance(ONE_HBAR);

                    final var cryptoCreateWithECDSAKeyAlias = cryptoCreate(PARTY)
                            .alias(spec.registry().getKey(SECP_256K1_SOURCE_KEY).toByteString())
                            .hasPrecheck(INVALID_ALIAS_KEY)
                            .balance(ONE_HBAR);

                    final var getCompletedHollowAccountInfo = getAliasedAccountInfo(counterAliasStr)
                            .hasToken(relationshipWith(fungibleToken).balance(505))
                            .has(accountWith().key(SECP_256K1_SOURCE_KEY).noAlias());

                    final var hapiGetTxnRecord = getTxnRecord(FT_XFER)
                            .hasNonStakingChildRecordCount(1)
                            .hasChildRecords(recordWith().status(SUCCESS).memo(LAZY_MEMO));

                    allRunFor(
                            spec,
                            hbarTransfer,
                            fungibleTokenTransfer,
                            cryptoCreateWithEVMAddress,
                            cryptoCreateWithECDSAKeyAlias,
                            getCompletedHollowAccountInfo,
                            hapiGetTxnRecord);
                }));
    }

    @HapiTest
    final Stream<DynamicTest> hollowAccountCompletionWithHollowPayer() {
        return hapiTest(flattened(
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(LAZY_CREATE_SPONSOR).balance(INITIAL_BALANCE * ONE_HBAR),
                cryptoCreate(TOKEN_TREASURY).balance(0L),
                tokenCreate(VANILLA_TOKEN).treasury(TOKEN_TREASURY),
                cryptoCreate("test"),
                createHollowAccountFrom(SECP_256K1_SOURCE_KEY),
                getAliasedAccountInfo(SECP_256K1_SOURCE_KEY)
                        .has(accountWith().maxAutoAssociations(-1).hasEmptyKey()),
                cryptoTransfer(moving(1, VANILLA_TOKEN).between(TOKEN_TREASURY, SECP_256K1_SOURCE_KEY))
                        .signedBy(SECP_256K1_SOURCE_KEY, TOKEN_TREASURY)
                        .payingWith(SECP_256K1_SOURCE_KEY)
                        .sigMapPrefixes(uniqueWithFullPrefixesFor(SECP_256K1_SOURCE_KEY)),
                getAliasedAccountInfo(SECP_256K1_SOURCE_KEY).has(accountWith().hasNonEmptyKey())));
    }

    @HapiTest
    final Stream<DynamicTest> hollowAccountCompletionWithTokenAssociation() {
        return hapiTest(flattened(
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(LAZY_CREATE_SPONSOR).balance(INITIAL_BALANCE * ONE_HBAR),
                cryptoCreate(TOKEN_TREASURY).balance(0L),
                tokenCreate(VANILLA_TOKEN).treasury(TOKEN_TREASURY),
                cryptoCreate("test"),
                createHollowAccountFrom(SECP_256K1_SOURCE_KEY),
                withOpContext((spec, opLog) -> {
                    final var ecdsaKey = spec.registry()
                            .getKey(SECP_256K1_SOURCE_KEY)
                            .getECDSASecp256K1()
                            .toByteArray();
                    final var evmAddress = ByteString.copyFrom(recoverAddressFromPubKey(ecdsaKey));
                    final var op2 = tokenAssociate("test", VANILLA_TOKEN)
                            .payingWith(SECP_256K1_SOURCE_KEY)
                            .sigMapPrefixes(uniqueWithFullPrefixesFor(SECP_256K1_SOURCE_KEY))
                            .via(TRANSFER_TXN_2);
                    final var op3 = getAliasedAccountInfo(evmAddress)
                            .has(accountWith().key(SECP_256K1_SOURCE_KEY).noAlias());
                    final var hapiGetSecondTxnRecord =
                            getTxnRecord(TRANSFER_TXN_2).andAllChildRecords().logged();

                    allRunFor(spec, op2, op3, hapiGetSecondTxnRecord);
                })));
    }

    @HapiTest
    final Stream<DynamicTest> hollowAccountFinalizationWhenAccountNotPresentInPreHandle() {
        final var ECDSA_2 = "ECDSA_2";
        return hapiTest(
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                newKeyNamed(ECDSA_2).shape(SECP_256K1_SHAPE),
                cryptoCreate(LAZY_CREATE_SPONSOR).balance(INITIAL_BALANCE * ONE_HBAR),
                cryptoCreate(TOKEN_TREASURY).balance(0L),
                tokenCreate(VANILLA_TOKEN).treasury(TOKEN_TREASURY),
                cryptoCreate("test"),
                withOpContext((spec, opLog) -> {
                    final var ecdsaKey = spec.registry()
                            .getKey(SECP_256K1_SOURCE_KEY)
                            .getECDSASecp256K1()
                            .toByteArray();
                    final var ecdsaKey2 = spec.registry().getKey(ECDSA_2);
                    final var evmAddress = ByteString.copyFrom(recoverAddressFromPubKey(ecdsaKey));
                    final var op = cryptoTransfer(tinyBarsFromTo(LAZY_CREATE_SPONSOR, evmAddress, ONE_HUNDRED_HBARS))
                            .hasKnownStatus(SUCCESS)
                            .deferStatusResolution()
                            .via(TRANSFER_TXN);
                    // transfer from the hollow account to receiver
                    // the txns requires signature from the hollow account
                    final var op2 = cryptoTransfer(tinyBarsFromTo(evmAddress, ecdsaKey2.toByteString(), ONE_HBAR))
                            .signedBy(GENESIS, SECP_256K1_SOURCE_KEY)
                            .sigMapPrefixes(uniqueWithFullPrefixesFor(SECP_256K1_SOURCE_KEY))
                            .hasKnownStatus(SUCCESS)
                            .via(TRANSFER_TXN_2);

                    allRunFor(spec, op, op2);

                    final var op3 = getAliasedAccountInfo(evmAddress)
                            .has(accountWith().key(SECP_256K1_SOURCE_KEY).noAlias());

                    final HapiGetTxnRecord hapiGetSecondTxnRecord =
                            getTxnRecord(TRANSFER_TXN_2).andAllChildRecords().logged();

                    allRunFor(spec, op3, hapiGetSecondTxnRecord);
                }));
    }

    @HapiTest
    final Stream<DynamicTest> hollowAccountFinalizationOccursOnlyOnceWhenMultipleFinalizationTensComeInAtTheSameTime() {
        final var ECDSA_2 = "ECDSA_2";
        return hapiTest(
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                newKeyNamed(ECDSA_2).shape(SECP_256K1_SHAPE),
                cryptoCreate(LAZY_CREATE_SPONSOR).balance(INITIAL_BALANCE * ONE_HBAR),
                cryptoCreate(TOKEN_TREASURY).balance(0L),
                tokenCreate(VANILLA_TOKEN).treasury(TOKEN_TREASURY),
                cryptoCreate("test"),
                withOpContext((spec, opLog) -> {
                    final var ecdsaKey = spec.registry()
                            .getKey(SECP_256K1_SOURCE_KEY)
                            .getECDSASecp256K1()
                            .toByteArray();
                    final var ecdsaKey2 = spec.registry().getKey(ECDSA_2);
                    final var evmAddress = ByteString.copyFrom(recoverAddressFromPubKey(ecdsaKey));
                    final var op = cryptoTransfer(tinyBarsFromTo(LAZY_CREATE_SPONSOR, evmAddress, ONE_HUNDRED_HBARS))
                            .hasKnownStatus(SUCCESS)
                            .deferStatusResolution()
                            .via(TRANSFER_TXN);
                    // finalize the account via transfer from the hollow account to receiver
                    // the txns requires signature from the hollow account
                    final var op2 = cryptoTransfer(tinyBarsFromTo(evmAddress, ecdsaKey2.toByteString(), ONE_HBAR))
                            .signedBy(GENESIS, SECP_256K1_SOURCE_KEY)
                            .sigMapPrefixes(uniqueWithFullPrefixesFor(SECP_256K1_SOURCE_KEY))
                            .hasKnownStatus(SUCCESS)
                            .deferStatusResolution()
                            .via(TRANSFER_TXN_2);
                    // send the finalization transaction again immediately after the 1st one
                    final var op3 = cryptoTransfer(tinyBarsFromTo(evmAddress, ecdsaKey2.toByteString(), ONE_HBAR))
                            .signedBy(GENESIS, SECP_256K1_SOURCE_KEY)
                            .sigMapPrefixes(uniqueWithFullPrefixesFor(SECP_256K1_SOURCE_KEY))
                            .hasKnownStatus(SUCCESS)
                            .via("shouldNotFinalize");

                    allRunFor(spec, op, op2, op3);

                    final var op4 = getAliasedAccountInfo(evmAddress)
                            .has(accountWith().key(SECP_256K1_SOURCE_KEY).noAlias());

                    final var hapiGetSecondTxnRecord = childRecordsCheck(
                            TRANSFER_TXN_2,
                            SUCCESS,
                            recordWith().status(SUCCESS),
                            recordWith().status(SUCCESS));

                    allRunFor(spec, op4, hapiGetSecondTxnRecord, emptyChildRecordsCheck("shouldNotFinalize", SUCCESS));
                }));
    }

    @HapiTest
    final Stream<DynamicTest> hollowAccountCompletionWithCryptoTransfer() {
        return hapiTest(flattened(
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                createHollowAccountFrom(SECP_256K1_SOURCE_KEY),
                withOpContext((spec, opLog) -> {
                    final var ecdsaKey = spec.registry().getKey(SECP_256K1_SOURCE_KEY);

                    final var evmAddress = ByteString.copyFrom(recoverAddressFromPubKey(
                            ecdsaKey.getECDSASecp256K1().toByteArray()));

                    final var op3 = cryptoTransfer(
                                    tinyBarsFromTo(LAZY_CREATE_SPONSOR, CRYPTO_TRANSFER_RECEIVER, ONE_HUNDRED_HBARS))
                            .payingWith(SECP_256K1_SOURCE_KEY)
                            .sigMapPrefixes(uniqueWithFullPrefixesFor(SECP_256K1_SOURCE_KEY))
                            .hasKnownStatus(SUCCESS)
                            .via(TRANSFER_TXN_2);

                    final var cryptoCreateWithEVMAddress = cryptoCreate(PARTY)
                            .alias(evmAddress)
                            .hasPrecheck(INVALID_ALIAS_KEY)
                            .balance(ONE_HBAR);

                    final var cryptoCreateWithECDSAKeyAlias = cryptoCreate(PARTY)
                            .alias(ecdsaKey.toByteString())
                            .hasPrecheck(INVALID_ALIAS_KEY)
                            .balance(ONE_HBAR);

                    final var op4 = getAliasedAccountInfo(evmAddress)
                            .has(accountWith().key(SECP_256K1_SOURCE_KEY).noAlias());

                    final HapiGetTxnRecord hapiGetSecondTxnRecord =
                            getTxnRecord(TRANSFER_TXN_2).andAllChildRecords().logged();
                    allRunFor(
                            spec,
                            op3,
                            cryptoCreateWithEVMAddress,
                            cryptoCreateWithECDSAKeyAlias,
                            op4,
                            hapiGetSecondTxnRecord);
                })));
    }

    @HapiTest
    final Stream<DynamicTest> hollowAccountCompletionWhenHollowAccountSigRequiredInOtherReqSigs() {
        return hapiTest(
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(LAZY_CREATE_SPONSOR).balance(INITIAL_BALANCE * ONE_HBAR),
                cryptoCreate(CRYPTO_TRANSFER_RECEIVER).balance(INITIAL_BALANCE * ONE_HBAR),
                withOpContext((spec, opLog) -> {
                    // create hollow account
                    final var ecdsaKey = spec.registry()
                            .getKey(SECP_256K1_SOURCE_KEY)
                            .getECDSASecp256K1()
                            .toByteArray();
                    final var evmAddress = ByteString.copyFrom(recoverAddressFromPubKey(ecdsaKey));
                    final var op = cryptoTransfer(tinyBarsFromTo(LAZY_CREATE_SPONSOR, evmAddress, ONE_HUNDRED_HBARS))
                            .hasKnownStatus(SUCCESS)
                            .via(TRANSFER_TXN);
                    final var op2 = getAliasedAccountInfo(evmAddress)
                            .has(accountWith()
                                    .hasEmptyKey()
                                    .expectedBalanceWithChargedUsd(ONE_HUNDRED_HBARS, 0, 0)
                                    .autoRenew(THREE_MONTHS_IN_SECONDS)
                                    .receiverSigReq(false)
                                    .memo(LAZY_MEMO));
                    allRunFor(spec, op, op2);
                }),
                withOpContext((spec, opLog) -> {
                    final var ecdsaKey = spec.registry().getKey(SECP_256K1_SOURCE_KEY);

                    final var evmAddress = ByteString.copyFrom(recoverAddressFromPubKey(
                            ecdsaKey.getECDSASecp256K1().toByteArray()));
                    // transfer from the hollow account to receiver
                    // the txns requires signature from the hollow account
                    final var op3 = cryptoTransfer(
                                    tinyBarsFromToWithAlias(SECP_256K1_SOURCE_KEY, CRYPTO_TRANSFER_RECEIVER, ONE_HBAR))
                            .signedBy(GENESIS, SECP_256K1_SOURCE_KEY)
                            .sigMapPrefixes(uniqueWithFullPrefixesFor(SECP_256K1_SOURCE_KEY))
                            .hasKnownStatus(SUCCESS)
                            .via(TRANSFER_TXN_2);
                    final var op4 = getAliasedAccountInfo(evmAddress)
                            .has(accountWith()
                                    .key(SECP_256K1_SOURCE_KEY)
                                    .evmAddress(evmAddress)
                                    .noAlias());

                    final var childRecordCheck = childRecordsCheck(
                            TRANSFER_TXN_2, SUCCESS, recordWith().status(SUCCESS));
                    allRunFor(spec, op3, op4, childRecordCheck);
                }));
    }

    @HapiTest
    final Stream<DynamicTest> hollowAccountCompletionWithContractCreate() {
        final var CONTRACT = "CreateTrivial";
        return hapiTest(flattened(
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                newKeyNamed(ADMIN_KEY),
                uploadInitCode(CONTRACT),
                createHollowAccountFrom(SECP_256K1_SOURCE_KEY),
                withOpContext((spec, opLog) -> {
                    final var ecdsaKey = spec.registry()
                            .getKey(SECP_256K1_SOURCE_KEY)
                            .getECDSASecp256K1()
                            .toByteArray();
                    final var evmAddress = ByteString.copyFrom(recoverAddressFromPubKey(ecdsaKey));
                    final var op2 = contractCreate(CONTRACT)
                            .adminKey(ADMIN_KEY)
                            .payingWith(SECP_256K1_SOURCE_KEY)
                            .sigMapPrefixes(uniqueWithFullPrefixesFor(SECP_256K1_SOURCE_KEY))
                            .hasKnownStatus(SUCCESS)
                            .via(TRANSFER_TXN_2);
                    final var op3 = getAliasedAccountInfo(evmAddress)
                            .has(accountWith().key(SECP_256K1_SOURCE_KEY).noAlias());
                    final var hapiGetSecondTxnRecord =
                            getTxnRecord(TRANSFER_TXN_2).andAllChildRecords().logged();

                    allRunFor(spec, op2, op3, hapiGetSecondTxnRecord);
                })));
    }

    @HapiTest
    final Stream<DynamicTest> hollowAccountCompletionWithEthereumContractCreate() {
        final var CONTRACT = "CreateTrivial";
        return hapiTest(flattened(
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                newKeyNamed(ADMIN_KEY),
                uploadInitCode(CONTRACT),
                createHollowAccountFrom(
                        SECP_256K1_SOURCE_KEY, INITIAL_BALANCE * ONE_MILLION_HBARS, 1_000_000 * ONE_HUNDRED_HBARS),
                withOpContext((spec, opLog) -> {
                    final var ecdsaKey = spec.registry()
                            .getKey(SECP_256K1_SOURCE_KEY)
                            .getECDSASecp256K1()
                            .toByteArray();
                    final var evmAddress = ByteString.copyFrom(recoverAddressFromPubKey(ecdsaKey));
                    final var op2 = ethereumContractCreate(CONTRACT)
                            .type(EthTransactionType.LEGACY_ETHEREUM)
                            .nonce(0)
                            .gasPrice(2L)
                            .maxPriorityGas(2L)
                            .gasLimit(1_000_000L)
                            .payingWith(SECP_256K1_SOURCE_KEY)
                            .sigMapPrefixes(uniqueWithFullPrefixesFor(SECP_256K1_SOURCE_KEY))
                            .hasKnownStatus(SUCCESS)
                            .via(TRANSFER_TXN_2);
                    final var op3 = getAliasedAccountInfo(evmAddress)
                            .has(accountWith().key(SECP_256K1_SOURCE_KEY).noAlias());
                    final var hapiGetSecondTxnRecord =
                            getTxnRecord(TRANSFER_TXN_2).andAllChildRecords().logged();

                    allRunFor(spec, op2, op3, hapiGetSecondTxnRecord);
                    // ensure that the finalized contract has a self management key
                    final var contractIdKey = Key.newBuilder()
                            .setContractID(spec.registry().getContractId(CONTRACT))
                            .build();
                    final var op4 = getAccountInfo(CONTRACT).has(accountWith().key(contractIdKey));
                    allRunFor(spec, op4);
                })));
    }

    @HapiTest
    final Stream<DynamicTest> hollowAccountCompletionWithContractCall() {
        final var DEPOSIT_AMOUNT = 1000;
        return hapiTest(flattened(
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                newKeyNamed(ADMIN_KEY),
                uploadInitCode(PAY_RECEIVABLE),
                contractCreate(PAY_RECEIVABLE).adminKey(ADMIN_KEY),
                createHollowAccountFrom(SECP_256K1_SOURCE_KEY),
                withOpContext((spec, opLog) -> {
                    final var ecdsaKey = spec.registry()
                            .getKey(SECP_256K1_SOURCE_KEY)
                            .getECDSASecp256K1()
                            .toByteArray();
                    final var evmAddress = ByteString.copyFrom(recoverAddressFromPubKey(ecdsaKey));
                    final var op2 = contractCall(PAY_RECEIVABLE)
                            .sending(DEPOSIT_AMOUNT)
                            .payingWith(SECP_256K1_SOURCE_KEY)
                            .sigMapPrefixes(uniqueWithFullPrefixesFor(SECP_256K1_SOURCE_KEY))
                            .hasKnownStatus(SUCCESS)
                            .via(TRANSFER_TXN_2);
                    final var op3 = getAliasedAccountInfo(evmAddress)
                            .has(accountWith().key(SECP_256K1_SOURCE_KEY).noAlias());
                    final var hapiGetSecondTxnRecord =
                            getTxnRecord(TRANSFER_TXN_2).andAllChildRecords().logged();
                    allRunFor(spec, op2, op3, hapiGetSecondTxnRecord);
                })));
    }

    @HapiTest
    final Stream<DynamicTest> hollowAccountCompletionViaNonReqSigIsNotAllowed() {
        final var DEPOSIT_AMOUNT = 1000;
        return hapiTest(
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(LAZY_CREATE_SPONSOR).balance(INITIAL_BALANCE * ONE_HBAR),
                newKeyNamed(ADMIN_KEY),
                uploadInitCode(PAY_RECEIVABLE),
                contractCreate(PAY_RECEIVABLE).adminKey(ADMIN_KEY),
                withOpContext((spec, opLog) -> {
                    // create a hollow account
                    final var ecdsaKey = spec.registry()
                            .getKey(SECP_256K1_SOURCE_KEY)
                            .getECDSASecp256K1()
                            .toByteArray();
                    final var evmAddress = ByteString.copyFrom(recoverAddressFromPubKey(ecdsaKey));
                    final var op = cryptoTransfer(tinyBarsFromTo(LAZY_CREATE_SPONSOR, evmAddress, ONE_HUNDRED_HBARS))
                            .hasKnownStatus(SUCCESS)
                            .via(TRANSFER_TXN);
                    allRunFor(spec, op);
                    // send a ContractCall signed by the ecdsa
                    // key of the hollow account's evmAddress
                    final var op2 = contractCall(PAY_RECEIVABLE)
                            .sending(DEPOSIT_AMOUNT)
                            .signedBy(GENESIS, SECP_256K1_SOURCE_KEY)
                            .sigMapPrefixes(uniqueWithFullPrefixesFor(SECP_256K1_SOURCE_KEY))
                            .hasKnownStatus(SUCCESS)
                            .via(TRANSFER_TXN_2);
                    final var op3 = getAliasedAccountInfo(evmAddress)
                            .has(accountWith()
                                    .key(EMPTY_KEY)
                                    .evmAddress(evmAddress)
                                    .noAlias());
                    final var checkRecords = emptyChildRecordsCheck(TRANSFER_TXN_2, SUCCESS);
                    allRunFor(spec, op2, op3, checkRecords);
                }));
    }

    @HapiTest
    final Stream<DynamicTest> tooManyHollowAccountFinalizationsShouldFail() {
        final var ECDSA_KEY_1 = "ECDSA_KEY_1";
        final var ECDSA_KEY_2 = "ECDSA_KEY_2";
        final var ECDSA_KEY_3 = "ECDSA_KEY_3";
        final var ECDSA_KEY_4 = "ECDSA_KEY_4";
        final var RECIPIENT_KEY = "ECDSA_KEY_5";
        final AtomicInteger numHollow = new AtomicInteger(0);
        return hapiTest(
                newKeyNamed(ECDSA_KEY_1).shape(SECP_256K1_SHAPE),
                newKeyNamed(ECDSA_KEY_2).shape(SECP_256K1_SHAPE),
                newKeyNamed(ECDSA_KEY_3).shape(SECP_256K1_SHAPE),
                newKeyNamed(ECDSA_KEY_4).shape(SECP_256K1_SHAPE),
                newKeyNamed(RECIPIENT_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(LAZY_CREATE_SPONSOR).balance(INITIAL_BALANCE * ONE_HBAR),
                newKeyNamed(ADMIN_KEY),
                withOpContext((spec, opLog) -> {
                    // create hollow accounts
                    allRunFor(
                            spec,
                            sendToEvmAddressFromECDSAKey(spec, ECDSA_KEY_1, TRANSFER_TXN),
                            sendToEvmAddressFromECDSAKey(spec, ECDSA_KEY_2, TRANSFER_TXN),
                            sendToEvmAddressFromECDSAKey(spec, ECDSA_KEY_3, TRANSFER_TXN),
                            sendToEvmAddressFromECDSAKey(spec, ECDSA_KEY_4, TRANSFER_TXN));
                    // send a CryptoTransfer signed by all the ecdsa
                    // keys of the hollow accounts;
                    final var op2 = cryptoTransfer(sendFromEvmAddressFromECDSAKey(
                                            spec,
                                            spec.registry()
                                                    .getKey(RECIPIENT_KEY)
                                                    .toByteString(),
                                            ECDSA_KEY_1,
                                            ECDSA_KEY_2,
                                            ECDSA_KEY_3,
                                            ECDSA_KEY_4)
                                    .toArray(Function[]::new))
                            .signedBy(GENESIS, ECDSA_KEY_1, ECDSA_KEY_2, ECDSA_KEY_3, ECDSA_KEY_4)
                            .sigMapPrefixes(
                                    uniqueWithFullPrefixesFor(ECDSA_KEY_1, ECDSA_KEY_2, ECDSA_KEY_3, ECDSA_KEY_4))
                            .hasKnownStatus(MAX_CHILD_RECORDS_EXCEEDED)
                            .via(TRANSFER_TXN_2);
                    final var op3 =
                            getTxnRecord(TRANSFER_TXN_2).andAllChildRecords().hasChildRecordCount(3);
                    final Consumer<Boolean> consumer = b -> {
                        if (b) {
                            numHollow.incrementAndGet();
                        }
                    };
                    allRunFor(
                            spec,
                            op2,
                            op3,
                            checkHollowStatus(spec, ECDSA_KEY_1, consumer),
                            checkHollowStatus(spec, ECDSA_KEY_2, consumer),
                            checkHollowStatus(spec, ECDSA_KEY_3, consumer),
                            checkHollowStatus(spec, ECDSA_KEY_4, consumer));
                    assertTrue(numHollow.get() == 1 || numHollow.get() == 2);
                }));
    }

    @HapiTest
    final Stream<DynamicTest> completedHollowAccountsTransfer() {
        return hapiTest(
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                newKeyNamed(ANOTHER_SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(LAZY_CREATE_SPONSOR).balance(INITIAL_BALANCE * ONE_HBAR),
                cryptoCreate(CRYPTO_TRANSFER_RECEIVER).balance(INITIAL_BALANCE * ONE_HBAR),
                withOpContext((spec, opLog) -> {
                    final var firstECDSAKey = spec.registry()
                            .getKey(SECP_256K1_SOURCE_KEY)
                            .getECDSASecp256K1()
                            .toByteArray();
                    final var firstEVMAddress = ByteString.copyFrom(recoverAddressFromPubKey(firstECDSAKey));
                    final var op = cryptoTransfer(
                                    tinyBarsFromTo(LAZY_CREATE_SPONSOR, firstEVMAddress, ONE_HUNDRED_HBARS))
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

                    final var secondECDSAKey = spec.registry()
                            .getKey(ANOTHER_SECP_256K1_SOURCE_KEY)
                            .getECDSASecp256K1()
                            .toByteArray();
                    final var secondEVMAddress = ByteString.copyFrom(recoverAddressFromPubKey(secondECDSAKey));
                    final var op2 = cryptoTransfer(
                                    tinyBarsFromTo(LAZY_CREATE_SPONSOR, secondEVMAddress, ONE_HUNDRED_HBARS))
                            .hasKnownStatus(SUCCESS)
                            .via(TRANSFER_TXN_2);
                    final HapiGetTxnRecord secondHapiGetTxnRecord =
                            getTxnRecord(TRANSFER_TXN_2).andAllChildRecords().logged();
                    allRunFor(spec, op2, secondHapiGetTxnRecord);

                    final AccountID anotherNewAccountID = secondHapiGetTxnRecord
                            .getFirstNonStakingChildRecord()
                            .getReceipt()
                            .getAccountID();
                    spec.registry().saveAccountId(ANOTHER_SECP_256K1_SOURCE_KEY, anotherNewAccountID);

                    final var op3 = cryptoTransfer(
                                    tinyBarsFromTo(LAZY_CREATE_SPONSOR, CRYPTO_TRANSFER_RECEIVER, ONE_HUNDRED_HBARS))
                            .payingWith(SECP_256K1_SOURCE_KEY)
                            .sigMapPrefixes(uniqueWithFullPrefixesFor(SECP_256K1_SOURCE_KEY))
                            .hasKnownStatus(SUCCESS)
                            .via(TRANSFER_TXN + "3");

                    allRunFor(spec, op3);

                    final var op4 = cryptoTransfer(
                                    tinyBarsFromTo(LAZY_CREATE_SPONSOR, CRYPTO_TRANSFER_RECEIVER, ONE_HUNDRED_HBARS))
                            .payingWith(ANOTHER_SECP_256K1_SOURCE_KEY)
                            .sigMapPrefixes(uniqueWithFullPrefixesFor(ANOTHER_SECP_256K1_SOURCE_KEY))
                            .hasKnownStatus(SUCCESS)
                            .via(TRANSFER_TXN + "4");

                    allRunFor(spec, op4);
                }),
                withOpContext((spec, opLog) -> {
                    final var firstECDSAKey = spec.registry()
                            .getKey(SECP_256K1_SOURCE_KEY)
                            .getECDSASecp256K1()
                            .toByteArray();
                    final var firstEVMAddress = ByteString.copyFrom(recoverAddressFromPubKey(firstECDSAKey));

                    final var secondECDSAKey = spec.registry()
                            .getKey(ANOTHER_SECP_256K1_SOURCE_KEY)
                            .getECDSASecp256K1()
                            .toByteArray();
                    final var secondEVMAddress = ByteString.copyFrom(recoverAddressFromPubKey(secondECDSAKey));

                    var op5 = cryptoTransfer(tinyBarsFromTo(firstEVMAddress, secondEVMAddress, FIVE_HBARS))
                            .payingWith(SECP_256K1_SOURCE_KEY)
                            .via(TRANSFER_TXN + "5");

                    var op6 = getTxnRecord(TRANSFER_TXN + "5")
                            .andAllChildRecords()
                            .logged();

                    allRunFor(spec, op5, op6);
                }));
    }

    @HapiTest
    final Stream<DynamicTest> txnWith2CompletionsAndAnother2PrecedingChildRecords() {
        final var ecdsaKey2 = "ecdsaKey2";
        final var recipientKey = "recipient";
        final var recipientKey2 = "recipient2";
        final var receiverId = new AtomicLong();
        return hapiTest(
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                newKeyNamed(ecdsaKey2).shape(SECP_256K1_SHAPE),
                newKeyNamed(recipientKey).shape(SECP_256K1_SHAPE),
                newKeyNamed(recipientKey2).shape(SECP_256K1_SHAPE),
                cryptoCreate(LAZY_CREATE_SPONSOR).balance(INITIAL_BALANCE * ONE_HBAR),
                cryptoCreate(CRYPTO_TRANSFER_RECEIVER)
                        .balance(INITIAL_BALANCE * ONE_HBAR)
                        .exposingCreatedIdTo(id -> receiverId.set(id.getAccountNum())),
                withOpContext((spec, opLog) -> {
                    final var op1 = sendToEvmAddressFromECDSAKey(spec, SECP_256K1_SOURCE_KEY, TRANSFER_TXN);
                    final var op2 = sendToEvmAddressFromECDSAKey(spec, ecdsaKey2, "randomTxn");
                    final var hapiGetTxnRecord =
                            getTxnRecord(TRANSFER_TXN).andAllChildRecords().logged();
                    allRunFor(spec, op1, op2, hapiGetTxnRecord);
                    final var newAccountID = hapiGetTxnRecord
                            .getFirstNonStakingChildRecord()
                            .getReceipt()
                            .getAccountID();
                    spec.registry().saveAccountId(SECP_256K1_SOURCE_KEY, newAccountID);
                }),
                withOpContext((spec, opLog) -> {
                    // send a crypto transfer from the hollow payer
                    // also sending hbars from the other hollow account
                    final var op3 = cryptoTransfer(
                                    tinyBarsFromTo(
                                            evmAddressFromECDSAKey(spec, ecdsaKey2),
                                            evmAddressFromECDSAKey(spec, recipientKey),
                                            ONE_HBAR / 4),
                                    tinyBarsFromTo(
                                            evmAddressFromECDSAKey(spec, ecdsaKey2),
                                            evmAddressFromECDSAKey(spec, recipientKey2),
                                            ONE_HBAR / 4))
                            .payingWith(SECP_256K1_SOURCE_KEY)
                            .signedBy(SECP_256K1_SOURCE_KEY, ecdsaKey2)
                            .sigMapPrefixes(uniqueWithFullPrefixesFor(SECP_256K1_SOURCE_KEY, ecdsaKey2))
                            .hasKnownStatus(MAX_CHILD_RECORDS_EXCEEDED)
                            .via(TRANSFER_TXN_2);
                    final var childRecordCheck = childRecordsCheck(
                            TRANSFER_TXN_2,
                            MAX_CHILD_RECORDS_EXCEEDED,
                            // Ensure there are no credits to auto-created accounts
                            parentAsserts -> parentAsserts.transfers(noCreditAboveNumber(ignore -> spec.registry()
                                    .getAccountID(SECP_256K1_SOURCE_KEY)
                                    .getAccountNum())),
                            recordWith().status(SUCCESS),
                            recordWith().status(SUCCESS));
                    // assert that the payer has been finalized
                    final var ecdsaKey = spec.registry().getKey(SECP_256K1_SOURCE_KEY);
                    final var payerEvmAddress = ByteString.copyFrom(recoverAddressFromPubKey(
                            ecdsaKey.getECDSASecp256K1().toByteArray()));
                    final var op4 = getAliasedAccountInfo(payerEvmAddress)
                            .has(accountWith()
                                    .key(SECP_256K1_SOURCE_KEY)
                                    .noAlias()
                                    .evmAddress(payerEvmAddress));
                    // assert that the other hollow account has been finalized
                    final var otherEcdsaKey = spec.registry().getKey(ecdsaKey2);
                    final var otherEvmAddress = ByteString.copyFrom(recoverAddressFromPubKey(
                            otherEcdsaKey.getECDSASecp256K1().toByteArray()));
                    final var op5 = getAliasedAccountInfo(otherEvmAddress)
                            .has(accountWith().key(ecdsaKey2).noAlias().evmAddress(otherEvmAddress));
                    allRunFor(spec, op3, childRecordCheck, op4, op5);
                }));
    }

    @HapiTest
    final Stream<DynamicTest> hollowPayerAndOtherReqSignerBothGetCompletedInASingleTransaction() {
        final var ecdsaKey2 = "ecdsaKey2";
        final var recipientKey = "recipient";
        return hapiTest(
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                newKeyNamed(ecdsaKey2).shape(SECP_256K1_SHAPE),
                newKeyNamed(recipientKey).shape(SECP_256K1_SHAPE),
                cryptoCreate(LAZY_CREATE_SPONSOR).balance(INITIAL_BALANCE * ONE_HBAR),
                cryptoCreate(CRYPTO_TRANSFER_RECEIVER).balance(INITIAL_BALANCE * ONE_HBAR),
                withOpContext((spec, opLog) -> {
                    final var op1 = sendToEvmAddressFromECDSAKey(spec, SECP_256K1_SOURCE_KEY, TRANSFER_TXN);
                    final var op2 = sendToEvmAddressFromECDSAKey(spec, ecdsaKey2, "randomTxn");
                    final var hapiGetTxnRecord =
                            getTxnRecord(TRANSFER_TXN).andAllChildRecords().logged();
                    allRunFor(spec, op1, op2, hapiGetTxnRecord);
                    final var newAccountID = hapiGetTxnRecord
                            .getFirstNonStakingChildRecord()
                            .getReceipt()
                            .getAccountID();
                    spec.registry().saveAccountId(SECP_256K1_SOURCE_KEY, newAccountID);
                }),
                withOpContext((spec, opLog) -> {
                    final var op = assumingNoStakingChildRecordCausesMaxChildRecordsExceeded(
                            // send a crypto transfer from the hollow payer
                            // also sending hbars from the other hollow account
                            cryptoTransfer(sendFromEvmAddressFromECDSAKey(
                                                    spec,
                                                    spec.registry()
                                                            .getKey(recipientKey)
                                                            .toByteString(),
                                                    ecdsaKey2)
                                            .toArray(Function[]::new))
                                    .payingWith(SECP_256K1_SOURCE_KEY)
                                    .signedBy(SECP_256K1_SOURCE_KEY, ecdsaKey2)
                                    .sigMapPrefixes(uniqueWithFullPrefixesFor(SECP_256K1_SOURCE_KEY, ecdsaKey2)),
                            TRANSFER_TXN_2,
                            childRecordsCheck(
                                    TRANSFER_TXN_2,
                                    SUCCESS,
                                    recordWith().status(SUCCESS),
                                    recordWith().status(SUCCESS),
                                    recordWith().status(SUCCESS)),
                            withOpContext((ignoredSpec, ignoredOpLog) -> {
                                final var ecdsaKey = spec.registry().getKey(SECP_256K1_SOURCE_KEY);
                                final var payerEvmAddress = ByteString.copyFrom(recoverAddressFromPubKey(
                                        ecdsaKey.getECDSASecp256K1().toByteArray()));
                                // assert that the payer has been finalized
                                final var op4 = getAliasedAccountInfo(payerEvmAddress)
                                        .has(accountWith()
                                                .key(SECP_256K1_SOURCE_KEY)
                                                .noAlias()
                                                .evmAddress(payerEvmAddress));
                                // assert that the other hollow account has been finalized
                                final var otherEcdsaKey = spec.registry().getKey(ecdsaKey2);
                                final var otherEvmAddress = ByteString.copyFrom(recoverAddressFromPubKey(
                                        otherEcdsaKey.getECDSASecp256K1().toByteArray()));
                                final var op5 = getAliasedAccountInfo(otherEvmAddress)
                                        .has(accountWith()
                                                .key(ecdsaKey2)
                                                .noAlias()
                                                .evmAddress(otherEvmAddress));
                                allRunFor(spec, op4, op5);
                            }));
                    allRunFor(spec, op);
                }));
    }

    @HapiTest
    final Stream<DynamicTest> hollowAccountCompletionIsPersistedEvenIfTxnFails() {
        return hapiTest(flattened(
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                createHollowAccountFrom(SECP_256K1_SOURCE_KEY),
                withOpContext((spec, opLog) -> {
                    final var op3 = cryptoTransfer(
                                    tinyBarsFromTo(LAZY_CREATE_SPONSOR, CRYPTO_TRANSFER_RECEIVER, ONE_MILLION_HBARS))
                            .payingWith(SECP_256K1_SOURCE_KEY)
                            .sigMapPrefixes(uniqueWithFullPrefixesFor(SECP_256K1_SOURCE_KEY))
                            .hasKnownStatus(INSUFFICIENT_ACCOUNT_BALANCE)
                            .via(TRANSFER_TXN_2);
                    final var childRecordsCheck = childRecordsCheck(
                            TRANSFER_TXN_2,
                            INSUFFICIENT_ACCOUNT_BALANCE,
                            recordWith().status(SUCCESS));
                    allRunFor(spec, op3, childRecordsCheck);

                    final var ecdsaKey = spec.registry().getKey(SECP_256K1_SOURCE_KEY);
                    final var evmAddress = ByteString.copyFrom(recoverAddressFromPubKey(
                            ecdsaKey.getECDSASecp256K1().toByteArray()));
                    allRunFor(
                            spec,
                            getAliasedAccountInfo(evmAddress)
                                    .has(accountWith()
                                            .key(SECP_256K1_SOURCE_KEY)
                                            .noAlias()));
                })));
    }

    @HapiTest
    final Stream<DynamicTest> precompileTransferFromHollowAccountWithNeededSigFailsAndDoesNotFinalizeAccount() {
        final var receiver = "receiver";
        final var ft = "ft";
        final String CONTRACT = "CryptoTransfer";
        final var TRANSFER_MULTIPLE_TOKENS = "transferMultipleTokens";
        // since we are passing the address of the account looking up in spec-registry function parameters will vary
        return hapiTest(
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(receiver).balance(2 * ONE_HUNDRED_HBARS).receiverSigRequired(true),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(ft)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .initialSupply(100)
                        .treasury(TOKEN_TREASURY),
                tokenAssociate(receiver, List.of(ft)),
                uploadInitCode(CONTRACT),
                contractCreate(CONTRACT),
                withOpContext((spec, opLog) -> {
                    final var amountToBeSent = 1L;
                    // create a hollow account, sending it 1 of the token
                    final var ecdsaKey = spec.registry()
                            .getKey(SECP_256K1_SOURCE_KEY)
                            .getECDSASecp256K1()
                            .toByteArray();
                    final var evmAddress = ByteString.copyFrom(recoverAddressFromPubKey(ecdsaKey));
                    final var op = cryptoTransfer(moving(amountToBeSent, ft).between(TOKEN_TREASURY, evmAddress))
                            .hasKnownStatus(SUCCESS)
                            .via(TRANSFER_TXN);
                    final var hapiGetTxnRecord =
                            getTxnRecord(TRANSFER_TXN).andAllChildRecords().logged();
                    allRunFor(spec, op, hapiGetTxnRecord);
                    final var hollowAccountId = hapiGetTxnRecord
                            .getFirstNonStakingChildRecord()
                            .getReceipt()
                            .getAccountID();
                    // try sending from hollow through transfer precompile and with appropriate sig
                    // this should fail, since the sig is not a required sig for the ContractCall
                    final var token = spec.registry().getTokenID(ft);
                    final var receiverId = spec.registry().getAccountID(receiver);
                    allRunFor(
                            spec,
                            contractCall(CONTRACT, TRANSFER_MULTIPLE_TOKENS, (Object) new Tuple[] {
                                        tokenTransferList()
                                                .forToken(token)
                                                .withAccountAmounts(
                                                        accountAmount(hollowAccountId, -amountToBeSent),
                                                        accountAmount(receiverId, amountToBeSent))
                                                .build()
                                    })
                                    .payingWith(GENESIS)
                                    .signedBy(GENESIS, SECP_256K1_SOURCE_KEY)
                                    .sigMapPrefixes(uniqueWithFullPrefixesFor(SECP_256K1_SOURCE_KEY))
                                    .alsoSigningWithFullPrefix(SECP_256K1_SOURCE_KEY)
                                    .via(TRANSFER_TXN)
                                    .gas(4_000_000)
                                    .hasKnownStatus(CONTRACT_REVERT_EXECUTED),
                            getTxnRecord(TRANSFER_TXN).andAllChildRecords().logged());
                }),
                getAccountBalance(receiver).hasTokenBalance(ft, 0).logged(),
                getAutoCreatedAccountBalance(SECP_256K1_SOURCE_KEY)
                        .hasTokenBalance(ft, 1)
                        .logged(),
                getAliasedAccountInfo(SECP_256K1_SOURCE_KEY).has(accountWith().hasEmptyKey()));
    }

    private HapiCryptoTransfer sendToEvmAddressFromECDSAKey(final HapiSpec spec, final String key, String txn) {
        final var ecdsaKey = spec.registry().getKey(key).getECDSASecp256K1().toByteArray();
        final var evmAddress = ByteString.copyFrom(recoverAddressFromPubKey(ecdsaKey));
        return cryptoTransfer(tinyBarsFromTo(LAZY_CREATE_SPONSOR, evmAddress, ONE_HBAR))
                .hasKnownStatus(SUCCESS)
                .via(txn);
    }

    private List<Function<HapiSpec, TransferList>> sendFromEvmAddressFromECDSAKey(
            final HapiSpec spec, final ByteString recipient, final String... keys) {
        List<Function<HapiSpec, TransferList>> transfers = new ArrayList<>();
        for (final var key : keys) {
            final var ecdsaKey = spec.registry().getKey(key).getECDSASecp256K1().toByteArray();
            final var evmAddress = ByteString.copyFrom(recoverAddressFromPubKey(ecdsaKey));
            transfers.add(tinyBarsFromTo(evmAddress, recipient, ONE_HBAR));
        }
        return transfers;
    }

    private ByteString evmAddressFromECDSAKey(HapiSpec spec, String key) {
        final var ecdsaKey = spec.registry().getKey(key).getECDSASecp256K1().toByteArray();
        return ByteString.copyFrom(recoverAddressFromPubKey(ecdsaKey));
    }

    private HapiGetAccountInfo checkHollowStatus(final HapiSpec spec, final String key, Consumer<Boolean> callback) {
        final var ecdsaKey = spec.registry().getKey(key).getECDSASecp256K1().toByteArray();
        final var evmAddress = ByteString.copyFrom(recoverAddressFromPubKey(ecdsaKey));
        return getAliasedAccountInfo(evmAddress).exposingKeyTo(k -> callback.accept(EMPTY_KEY.equals(k)));
    }
}
