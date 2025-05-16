// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.crypto;

import static com.hedera.services.bdd.junit.TestTags.CRYPTO;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AccountDetailsAsserts.accountDetailsWith;
import static com.hedera.services.bdd.spec.assertions.AssertUtils.inOrder;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.resultWith;
import static com.hedera.services.bdd.spec.assertions.ContractLogAsserts.logWith;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.keys.KeyShape.SIMPLE;
import static com.hedera.services.bdd.spec.keys.SigControl.ON;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountDetails;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getContractInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTokenNftInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoApproveAllowance;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoDeleteAllowance;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.grantTokenKyc;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.revokeTokenKyc;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleSign;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenFreeze;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenPause;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenUnpause;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoApproveAllowance.MISSING_OWNER;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.allowanceTinyBarsFromTo;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedHtsFee;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingUnique;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingUniqueWithAllowance;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingWithAllowance;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.childRecordsCheck;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.emptyChildRecordsCheck;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsdWithin;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.FUNDING;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.TOKEN_TREASURY;
import static com.hedera.services.bdd.suites.contract.Utils.asAddress;
import static com.hedera.services.bdd.suites.contract.Utils.eventSignatureOf;
import static com.hedera.services.bdd.suites.contract.Utils.parsedToByteString;
import static com.hedera.services.bdd.suites.contract.leaky.LeakyContractTestsSuite.APPROVE;
import static com.hedera.services.bdd.suites.contract.leaky.LeakyContractTestsSuite.ERC_20_CONTRACT;
import static com.hedera.services.bdd.suites.contract.leaky.LeakyContractTestsSuite.RECIPIENT;
import static com.hedera.services.bdd.suites.contract.leaky.LeakyContractTestsSuite.TRANSFER_FROM;
import static com.hedera.services.bdd.suites.contract.leaky.LeakyContractTestsSuite.TRANSFER_SIGNATURE;
import static com.hedera.services.bdd.suites.contract.leaky.LeakyContractTestsSuite.TRANSFER_SIG_NAME;
import static com.hedera.services.bdd.suites.crypto.CryptoCreateSuite.ACCOUNT;
import static com.hedera.services.bdd.suites.token.TokenAssociationSpecs.MULTI_KEY;
import static com.hedera.services.bdd.suites.token.TokenTransactSpecs.TRANSFER_TXN;
import static com.hedera.services.bdd.suites.utils.contracts.precompile.HTSPrecompileResult.htsPrecompileResult;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.AMOUNT_EXCEEDS_TOKEN_MAX_SUPPLY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.DELEGATING_SPENDER_CANNOT_GRANT_APPROVE_FOR_ALL;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.DELEGATING_SPENDER_DOES_NOT_HAVE_APPROVE_FOR_ALL;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.EMPTY_ALLOWANCES;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.FUNGIBLE_TOKEN_IN_NFT_ALLOWANCES;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ALLOWANCE_OWNER_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ALLOWANCE_SPENDER_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_DELEGATING_SPENDER;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_NFT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOKEN_NFT_SERIAL_NUMBER;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.NEGATIVE_ALLOWANCE_AMOUNT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.NFT_IN_FUNGIBLE_TOKEN_ALLOWANCES;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.PAYER_ACCOUNT_NOT_FOUND;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SENDER_DOES_NOT_OWN_NFT_SERIAL_NO;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SPENDER_ACCOUNT_SAME_AS_OWNER;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SPENDER_DOES_NOT_HAVE_ALLOWANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_NOT_ASSOCIATED_TO_ACCOUNT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_WAS_DELETED;
import static com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;

import com.google.protobuf.ByteString;
import com.hedera.node.app.hapi.utils.contracts.ParsingConstants;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.spec.transactions.contract.HapiParserUtil;
import com.hedera.services.bdd.spec.transactions.token.TokenMovement;
import com.hederahashgraph.api.proto.java.NftTransfer;
import com.hederahashgraph.api.proto.java.TokenSupplyType;
import com.hederahashgraph.api.proto.java.TokenTransferList;
import com.hederahashgraph.api.proto.java.TokenType;
import java.math.BigInteger;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(CRYPTO)
public class CryptoApproveAllowanceSuite {
    public static final String OWNER = "owner";
    public static final String SPENDER = "spender";
    private static final String RECEIVER = "receiver";
    public static final String OTHER_RECEIVER = "otherReceiver";
    public static final String FUNGIBLE_TOKEN = "fungible";
    public static final String NON_FUNGIBLE_TOKEN = "nonFungible";
    public static final String TOKEN_WITH_CUSTOM_FEE = "tokenWithCustomFee";
    private static final String SUPPLY_KEY = "supplyKey";
    public static final String SCHEDULED_TXN = "scheduledTxn";
    public static final String NFT_TOKEN_MINT_TXN = "nftTokenMint";
    public static final String FUNGIBLE_TOKEN_MINT_TXN = "tokenMint";
    public static final String BASE_APPROVE_TXN = "baseApproveTxn";
    public static final String PAYER = "payer";
    public static final String APPROVE_TXN = "approveTxn";
    public static final String ANOTHER_SPENDER = "spender1";
    public static final String SECOND_OWNER = "owner2";
    public static final String SECOND_SPENDER = "spender2";
    public static final String THIRD_SPENDER = "spender3";
    public static final String ADMIN_KEY = "adminKey";
    public static final String FREEZE_KEY = "freezeKey";
    public static final String KYC_KEY = "kycKey";
    public static final String PAUSE_KEY = "pauseKey";

    @HapiTest
    final Stream<DynamicTest> transferErc20TokenFromContractWithApproval() {
        final var transferFromOtherContractWithSignaturesTxn = "transferFromOtherContractWithSignaturesTxn";
        final var nestedContract = "NestedERC20Contract";

        return hapiTest(
                newKeyNamed(MULTI_KEY),
                cryptoCreate(ACCOUNT).balance(10 * ONE_MILLION_HBARS),
                cryptoCreate(RECIPIENT),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(FUNGIBLE_TOKEN)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .initialSupply(35)
                        .treasury(TOKEN_TREASURY)
                        .adminKey(MULTI_KEY)
                        .supplyKey(MULTI_KEY),
                uploadInitCode(ERC_20_CONTRACT, nestedContract),
                newKeyNamed(TRANSFER_SIG_NAME).shape(SIMPLE.signedWith(ON)),
                contractCreate(ERC_20_CONTRACT).adminKey(TRANSFER_SIG_NAME),
                contractCreate(nestedContract).adminKey(TRANSFER_SIG_NAME),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        tokenAssociate(ACCOUNT, List.of(FUNGIBLE_TOKEN)),
                        tokenAssociate(RECIPIENT, List.of(FUNGIBLE_TOKEN)),
                        tokenAssociate(ERC_20_CONTRACT, List.of(FUNGIBLE_TOKEN)),
                        tokenAssociate(nestedContract, List.of(FUNGIBLE_TOKEN)),
                        cryptoTransfer(TokenMovement.moving(20, FUNGIBLE_TOKEN)
                                        .between(TOKEN_TREASURY, ERC_20_CONTRACT))
                                .payingWith(ACCOUNT),
                        contractCall(
                                        ERC_20_CONTRACT,
                                        APPROVE,
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getTokenID(FUNGIBLE_TOKEN))),
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getContractId(ERC_20_CONTRACT))),
                                        BigInteger.valueOf(20))
                                .gas(1_000_000)
                                .payingWith(ACCOUNT)
                                .alsoSigningWithFullPrefix(TRANSFER_SIG_NAME),
                        contractCall(
                                        ERC_20_CONTRACT,
                                        TRANSFER_FROM,
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getTokenID(FUNGIBLE_TOKEN))),
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getContractId(ERC_20_CONTRACT))),
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getContractId(nestedContract))),
                                        BigInteger.valueOf(5))
                                .via(TRANSFER_TXN)
                                .alsoSigningWithFullPrefix(TRANSFER_SIG_NAME)
                                .hasKnownStatus(SUCCESS),
                        contractCall(
                                        ERC_20_CONTRACT,
                                        TRANSFER_FROM,
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getTokenID(FUNGIBLE_TOKEN))),
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getContractId(ERC_20_CONTRACT))),
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getContractId(nestedContract))),
                                        BigInteger.valueOf(5))
                                .payingWith(ACCOUNT)
                                .alsoSigningWithFullPrefix(TRANSFER_SIG_NAME)
                                .via(transferFromOtherContractWithSignaturesTxn))),
                getContractInfo(ERC_20_CONTRACT).saveToRegistry(ERC_20_CONTRACT),
                getContractInfo(nestedContract).saveToRegistry(nestedContract),
                withOpContext((spec, log) -> {
                    final var sender =
                            spec.registry().getContractInfo(ERC_20_CONTRACT).getContractID();
                    final var receiver =
                            spec.registry().getContractInfo(nestedContract).getContractID();

                    var transferRecord = getTxnRecord(TRANSFER_TXN)
                            .hasPriority(recordWith()
                                    .contractCallResult(resultWith()
                                            .logs(inOrder(logWith()
                                                    .withTopicsInOrder(List.of(
                                                            eventSignatureOf(TRANSFER_SIGNATURE),
                                                            parsedToByteString(
                                                                    sender.getShardNum(),
                                                                    sender.getRealmNum(),
                                                                    sender.getContractNum()),
                                                            parsedToByteString(
                                                                    receiver.getShardNum(),
                                                                    receiver.getRealmNum(),
                                                                    receiver.getContractNum())))
                                                    .longValue(5)))))
                            .andAllChildRecords();

                    var transferFromOtherContractWithSignaturesTxnRecord = getTxnRecord(
                                    transferFromOtherContractWithSignaturesTxn)
                            .hasPriority(recordWith()
                                    .contractCallResult(resultWith()
                                            .logs(inOrder(logWith()
                                                    .withTopicsInOrder(List.of(
                                                            eventSignatureOf(TRANSFER_SIGNATURE),
                                                            parsedToByteString(
                                                                    sender.getShardNum(),
                                                                    sender.getRealmNum(),
                                                                    sender.getContractNum()),
                                                            parsedToByteString(
                                                                    receiver.getShardNum(),
                                                                    receiver.getRealmNum(),
                                                                    receiver.getContractNum())))
                                                    .longValue(5)))))
                            .andAllChildRecords();

                    allRunFor(spec, transferRecord, transferFromOtherContractWithSignaturesTxnRecord);
                }),
                childRecordsCheck(
                        TRANSFER_TXN,
                        SUCCESS,
                        recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith()
                                        .contractCallResult(htsPrecompileResult()
                                                .forFunction(ParsingConstants.FunctionType.ERC_TRANSFER)
                                                .withErcFungibleTransferStatus(true)))),
                childRecordsCheck(
                        transferFromOtherContractWithSignaturesTxn,
                        SUCCESS,
                        recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith()
                                        .contractCallResult(htsPrecompileResult()
                                                .forFunction(ParsingConstants.FunctionType.ERC_TRANSFER)
                                                .withErcFungibleTransferStatus(true)))),
                getAccountBalance(ERC_20_CONTRACT).hasTokenBalance(FUNGIBLE_TOKEN, 10),
                getAccountBalance(nestedContract).hasTokenBalance(FUNGIBLE_TOKEN, 10));
    }

    @HapiTest
    final Stream<DynamicTest> cannotPayForAnyTransactionWithContractAccount() {
        final var cryptoAdminKey = "cryptoAdminKey";
        final var contractNum = new AtomicLong();
        final var contract = "PayableConstructor";
        return hapiTest(
                newKeyNamed(cryptoAdminKey),
                uploadInitCode(contract),
                contractCreate(contract)
                        .adminKey(cryptoAdminKey)
                        .balance(ONE_HUNDRED_HBARS)
                        .exposingNumTo(contractNum::set),
                sourcing(() -> cryptoTransfer(tinyBarsFromTo(contract, FUNDING, 1))
                        .fee(ONE_HBAR)
                        .payingWith("0.0." + contractNum.longValue())
                        .signedBy(cryptoAdminKey)
                        .hasPrecheck(PAYER_ACCOUNT_NOT_FOUND)));
    }

    @HapiTest
    final Stream<DynamicTest> transferringMissingNftViaApprovalFailsWithInvalidNftId() {
        return hapiTest(
                newKeyNamed(SUPPLY_KEY),
                cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                cryptoCreate(RECEIVER).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                cryptoCreate(SPENDER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(TOKEN_TREASURY).balance(100 * ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                tokenCreate(NON_FUNGIBLE_TOKEN)
                        .maxSupply(10L)
                        .initialSupply(0)
                        .supplyType(TokenSupplyType.FINITE)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .supplyKey(SUPPLY_KEY)
                        .treasury(TOKEN_TREASURY),
                tokenAssociate(OWNER, NON_FUNGIBLE_TOKEN),
                mintToken(
                        NON_FUNGIBLE_TOKEN,
                        List.of(
                                ByteString.copyFromUtf8("a"),
                                ByteString.copyFromUtf8("b"),
                                ByteString.copyFromUtf8("c"))),
                cryptoTransfer((spec, builder) -> builder.addTokenTransfers(TokenTransferList.newBuilder()
                                .setToken(spec.registry().getTokenID(NON_FUNGIBLE_TOKEN))
                                .addNftTransfers(NftTransfer.newBuilder()
                                        // Doesn't exist
                                        .setSerialNumber(4L)
                                        .setSenderAccountID(spec.registry().getAccountID(OWNER))
                                        .setReceiverAccountID(spec.registry().getAccountID(RECEIVER))
                                        .setIsApproval(true)
                                        .build())))
                        .payingWith(SPENDER)
                        .signedBy(SPENDER, OWNER)
                        .hasKnownStatus(INVALID_NFT_ID));
    }

    @HapiTest
    final Stream<DynamicTest> canDeleteAllowanceFromDeletedSpender() {
        return hapiTest(
                newKeyNamed(SUPPLY_KEY),
                cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                cryptoCreate(SPENDER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(TOKEN_TREASURY).balance(100 * ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                tokenCreate(FUNGIBLE_TOKEN)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .supplyType(TokenSupplyType.FINITE)
                        .supplyKey(SUPPLY_KEY)
                        .maxSupply(1000L)
                        .initialSupply(10L)
                        .treasury(TOKEN_TREASURY),
                tokenCreate(NON_FUNGIBLE_TOKEN)
                        .maxSupply(10L)
                        .initialSupply(0)
                        .supplyType(TokenSupplyType.FINITE)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .supplyKey(SUPPLY_KEY)
                        .treasury(TOKEN_TREASURY),
                tokenAssociate(OWNER, FUNGIBLE_TOKEN),
                tokenAssociate(OWNER, NON_FUNGIBLE_TOKEN),
                mintToken(FUNGIBLE_TOKEN, 500L).via(FUNGIBLE_TOKEN_MINT_TXN),
                mintToken(
                        NON_FUNGIBLE_TOKEN,
                        List.of(
                                ByteString.copyFromUtf8("a"),
                                ByteString.copyFromUtf8("b"),
                                ByteString.copyFromUtf8("c"))),
                cryptoApproveAllowance()
                        .payingWith(OWNER)
                        .addCryptoAllowance(OWNER, SPENDER, 100L)
                        .addTokenAllowance(OWNER, FUNGIBLE_TOKEN, SPENDER, 1)
                        .addNftAllowance(OWNER, NON_FUNGIBLE_TOKEN, SPENDER, true, List.of()),
                getAccountDetails(OWNER)
                        .payingWith(GENESIS)
                        .has(accountDetailsWith()
                                .cryptoAllowancesCount(1)
                                .tokenAllowancesCount(1)
                                .nftApprovedForAllAllowancesCount(1)
                                .cryptoAllowancesContaining(SPENDER, 100L)
                                .tokenAllowancesContaining(FUNGIBLE_TOKEN, SPENDER, 1)),
                cryptoDelete(SPENDER),
                // removing fungible allowances should be possible even if the
                // spender is deleted
                cryptoApproveAllowance()
                        .payingWith(OWNER)
                        .addCryptoAllowance(OWNER, SPENDER, 0)
                        .blankMemo(),
                getAccountDetails(OWNER)
                        .payingWith(GENESIS)
                        .has(accountDetailsWith()
                                .cryptoAllowancesCount(0)
                                .tokenAllowancesCount(1)
                                .nftApprovedForAllAllowancesCount(1)
                                .tokenAllowancesContaining(FUNGIBLE_TOKEN, SPENDER, 1)),
                cryptoApproveAllowance()
                        .payingWith(OWNER)
                        .addTokenAllowance(OWNER, FUNGIBLE_TOKEN, SPENDER, 0)
                        .blankMemo(),
                getAccountDetails(OWNER)
                        .payingWith(GENESIS)
                        .has(accountDetailsWith()
                                .cryptoAllowancesCount(0)
                                .tokenAllowancesCount(0)
                                .nftApprovedForAllAllowancesCount(1)),
                // It should not be possible to remove approveForAllNftAllowance
                // and also add allowance to serials
                cryptoApproveAllowance()
                        .payingWith(OWNER)
                        .addNftAllowance(OWNER, NON_FUNGIBLE_TOKEN, SPENDER, false, List.of(1L, 2L))
                        .hasKnownStatus(INVALID_ALLOWANCE_SPENDER_ID),
                getAccountDetails(OWNER)
                        .payingWith(GENESIS)
                        .has(accountDetailsWith()
                                .cryptoAllowancesCount(0)
                                .tokenAllowancesCount(0)
                                .nftApprovedForAllAllowancesCount(1)),
                getTokenNftInfo(NON_FUNGIBLE_TOKEN, 1L).hasNoSpender(),
                getTokenNftInfo(NON_FUNGIBLE_TOKEN, 2L).hasNoSpender(),
                cryptoApproveAllowance()
                        .payingWith(OWNER)
                        .addNftAllowance(OWNER, NON_FUNGIBLE_TOKEN, SPENDER, false, List.of()),
                getAccountDetails(OWNER)
                        .payingWith(GENESIS)
                        .has(accountDetailsWith()
                                .cryptoAllowancesCount(0)
                                .tokenAllowancesCount(0)
                                .nftApprovedForAllAllowancesCount(0)));
    }

    @HapiTest
    final Stream<DynamicTest> duplicateKeysAndSerialsInSameTxnDoesntThrow() {
        return hapiTest(
                newKeyNamed(SUPPLY_KEY),
                cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                cryptoCreate(SPENDER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(TOKEN_TREASURY).balance(100 * ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                tokenCreate(FUNGIBLE_TOKEN)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .supplyType(TokenSupplyType.FINITE)
                        .supplyKey(SUPPLY_KEY)
                        .maxSupply(1000L)
                        .initialSupply(10L)
                        .treasury(TOKEN_TREASURY),
                tokenCreate(NON_FUNGIBLE_TOKEN)
                        .maxSupply(10L)
                        .initialSupply(0)
                        .supplyType(TokenSupplyType.FINITE)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .supplyKey(SUPPLY_KEY)
                        .treasury(TOKEN_TREASURY),
                tokenAssociate(OWNER, FUNGIBLE_TOKEN),
                tokenAssociate(OWNER, NON_FUNGIBLE_TOKEN),
                mintToken(
                                NON_FUNGIBLE_TOKEN,
                                List.of(
                                        ByteString.copyFromUtf8("a"),
                                        ByteString.copyFromUtf8("b"),
                                        ByteString.copyFromUtf8("c")))
                        .via(NFT_TOKEN_MINT_TXN),
                mintToken(FUNGIBLE_TOKEN, 500L).via(FUNGIBLE_TOKEN_MINT_TXN),
                cryptoTransfer(movingUnique(NON_FUNGIBLE_TOKEN, 1L, 2L, 3L).between(TOKEN_TREASURY, OWNER)),
                cryptoApproveAllowance()
                        .payingWith(OWNER)
                        .addCryptoAllowance(OWNER, SPENDER, 100L)
                        .addCryptoAllowance(OWNER, SPENDER, 200L)
                        .blankMemo()
                        .logged(),
                getAccountDetails(OWNER)
                        .payingWith(GENESIS)
                        .has(accountDetailsWith().cryptoAllowancesCount(1).cryptoAllowancesContaining(SPENDER, 200L)),
                cryptoApproveAllowance()
                        .payingWith(OWNER)
                        .addCryptoAllowance(OWNER, SPENDER, 300L)
                        .addTokenAllowance(OWNER, FUNGIBLE_TOKEN, SPENDER, 300L)
                        .addTokenAllowance(OWNER, FUNGIBLE_TOKEN, SPENDER, 500L)
                        .blankMemo()
                        .logged(),
                getAccountDetails(OWNER)
                        .payingWith(GENESIS)
                        .has(accountDetailsWith()
                                .cryptoAllowancesCount(1)
                                .cryptoAllowancesContaining(SPENDER, 300L)
                                .tokenAllowancesCount(1)
                                .tokenAllowancesContaining(FUNGIBLE_TOKEN, SPENDER, 500L)),
                cryptoApproveAllowance()
                        .payingWith(OWNER)
                        .addCryptoAllowance(OWNER, SPENDER, 500L)
                        .addTokenAllowance(OWNER, FUNGIBLE_TOKEN, SPENDER, 600L)
                        .addNftAllowance(OWNER, NON_FUNGIBLE_TOKEN, SPENDER, false, List.of(1L, 2L, 2L, 2L, 2L))
                        .addNftAllowance(OWNER, NON_FUNGIBLE_TOKEN, SPENDER, true, List.of(1L))
                        .addNftAllowance(OWNER, NON_FUNGIBLE_TOKEN, SPENDER, false, List.of(2L))
                        .addNftAllowance(OWNER, NON_FUNGIBLE_TOKEN, SPENDER, true, List.of(3L))
                        .blankMemo()
                        .logged(),
                getAccountDetails(OWNER)
                        .payingWith(GENESIS)
                        .has(accountDetailsWith()
                                .cryptoAllowancesCount(1)
                                .cryptoAllowancesContaining(SPENDER, 500L)
                                .tokenAllowancesCount(1)
                                .tokenAllowancesContaining(FUNGIBLE_TOKEN, SPENDER, 600L)
                                .nftApprovedForAllAllowancesCount(1)),
                getTokenNftInfo(NON_FUNGIBLE_TOKEN, 1L).hasSpenderID(SPENDER),
                getTokenNftInfo(NON_FUNGIBLE_TOKEN, 2L).hasSpenderID(SPENDER),
                getTokenNftInfo(NON_FUNGIBLE_TOKEN, 3L).hasSpenderID(SPENDER));
    }

    @HapiTest
    final Stream<DynamicTest> approveForAllSpenderCanDelegateOnNFT() {
        final String delegatingSpender = "delegatingSpender";
        final String newSpender = "newSpender";
        return hapiTest(
                newKeyNamed(SUPPLY_KEY),
                cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                cryptoCreate(delegatingSpender).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(newSpender).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(TOKEN_TREASURY).balance(100 * ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                tokenCreate(NON_FUNGIBLE_TOKEN)
                        .maxSupply(10L)
                        .initialSupply(0)
                        .supplyType(TokenSupplyType.FINITE)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .supplyKey(SUPPLY_KEY)
                        .treasury(TOKEN_TREASURY),
                tokenAssociate(OWNER, NON_FUNGIBLE_TOKEN),
                mintToken(NON_FUNGIBLE_TOKEN, List.of(ByteString.copyFromUtf8("a"), ByteString.copyFromUtf8("b")))
                        .via(NFT_TOKEN_MINT_TXN),
                cryptoTransfer(movingUnique(NON_FUNGIBLE_TOKEN, 1L, 2L).between(TOKEN_TREASURY, OWNER)),
                cryptoApproveAllowance()
                        .payingWith(DEFAULT_PAYER)
                        .addNftAllowance(OWNER, NON_FUNGIBLE_TOKEN, delegatingSpender, true, List.of(1L))
                        .addNftAllowance(OWNER, NON_FUNGIBLE_TOKEN, newSpender, false, List.of(2L))
                        .signedBy(DEFAULT_PAYER, OWNER),
                cryptoApproveAllowance()
                        .payingWith(DEFAULT_PAYER)
                        .addDelegatedNftAllowance(
                                OWNER, NON_FUNGIBLE_TOKEN, newSpender, delegatingSpender, false, List.of(1L))
                        .signedBy(DEFAULT_PAYER, OWNER)
                        .hasKnownStatus(INVALID_SIGNATURE),
                cryptoApproveAllowance()
                        .payingWith(DEFAULT_PAYER)
                        .addDelegatedNftAllowance(
                                OWNER, NON_FUNGIBLE_TOKEN, delegatingSpender, newSpender, false, List.of(2L))
                        .signedBy(DEFAULT_PAYER, newSpender)
                        .hasPrecheck(DELEGATING_SPENDER_DOES_NOT_HAVE_APPROVE_FOR_ALL),
                cryptoApproveAllowance()
                        .payingWith(DEFAULT_PAYER)
                        .addDelegatedNftAllowance(
                                OWNER, NON_FUNGIBLE_TOKEN, newSpender, delegatingSpender, true, List.of())
                        .signedBy(DEFAULT_PAYER, OWNER)
                        .hasPrecheck(DELEGATING_SPENDER_CANNOT_GRANT_APPROVE_FOR_ALL),
                getTokenNftInfo(NON_FUNGIBLE_TOKEN, 2L).hasSpenderID(newSpender),
                getTokenNftInfo(NON_FUNGIBLE_TOKEN, 1L).hasSpenderID(delegatingSpender),
                cryptoApproveAllowance()
                        .payingWith(DEFAULT_PAYER)
                        .addDelegatedNftAllowance(
                                OWNER, NON_FUNGIBLE_TOKEN, newSpender, delegatingSpender, false, List.of(1L))
                        .signedBy(DEFAULT_PAYER, delegatingSpender),
                getTokenNftInfo(NON_FUNGIBLE_TOKEN, 1L).hasSpenderID(newSpender));
    }

    @HapiTest
    final Stream<DynamicTest> canGrantFungibleAllowancesWithTreasuryOwner() {
        return hapiTest(
                newKeyNamed(SUPPLY_KEY),
                cryptoCreate(TOKEN_TREASURY),
                cryptoCreate(SPENDER),
                tokenCreate(FUNGIBLE_TOKEN)
                        .supplyType(TokenSupplyType.FINITE)
                        .tokenType(FUNGIBLE_COMMON)
                        .treasury(TOKEN_TREASURY)
                        .maxSupply(10000)
                        .initialSupply(5000),
                cryptoCreate(OTHER_RECEIVER).balance(ONE_HBAR).maxAutomaticTokenAssociations(1),
                cryptoApproveAllowance()
                        .addTokenAllowance(TOKEN_TREASURY, FUNGIBLE_TOKEN, SPENDER, 10)
                        .signedBy(TOKEN_TREASURY, DEFAULT_PAYER),
                cryptoApproveAllowance()
                        .addTokenAllowance(TOKEN_TREASURY, FUNGIBLE_TOKEN, SPENDER, 110)
                        .signedBy(TOKEN_TREASURY, DEFAULT_PAYER),
                cryptoTransfer(movingWithAllowance(30, FUNGIBLE_TOKEN).between(TOKEN_TREASURY, OTHER_RECEIVER))
                        .payingWith(SPENDER)
                        .signedBy(SPENDER),
                getAccountDetails(TOKEN_TREASURY)
                        .payingWith(GENESIS)
                        .has(accountDetailsWith().tokenAllowancesContaining(FUNGIBLE_TOKEN, SPENDER, 80))
                        .logged(),
                getAccountDetails(TOKEN_TREASURY)
                        .payingWith(GENESIS)
                        .has(accountDetailsWith().tokenAllowancesContaining(FUNGIBLE_TOKEN, SPENDER, 80))
                        .logged());
    }

    @HapiTest
    final Stream<DynamicTest> canGrantNftAllowancesWithTreasuryOwner() {
        return hapiTest(
                newKeyNamed(SUPPLY_KEY),
                cryptoCreate(TOKEN_TREASURY),
                cryptoCreate(SPENDER),
                tokenCreate(NON_FUNGIBLE_TOKEN)
                        .supplyType(TokenSupplyType.INFINITE)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .treasury(TOKEN_TREASURY)
                        .initialSupply(0)
                        .supplyKey(SUPPLY_KEY),
                mintToken(
                        NON_FUNGIBLE_TOKEN,
                        List.of(
                                ByteString.copyFromUtf8("a"),
                                ByteString.copyFromUtf8("b"),
                                ByteString.copyFromUtf8("c"))),
                cryptoCreate(OTHER_RECEIVER).balance(ONE_HBAR).maxAutomaticTokenAssociations(1),
                cryptoApproveAllowance()
                        .addNftAllowance(TOKEN_TREASURY, NON_FUNGIBLE_TOKEN, SPENDER, false, List.of(4L))
                        .signedBy(TOKEN_TREASURY, DEFAULT_PAYER)
                        .hasPrecheck(INVALID_TOKEN_NFT_SERIAL_NUMBER),
                cryptoApproveAllowance()
                        .addNftAllowance(TOKEN_TREASURY, NON_FUNGIBLE_TOKEN, SPENDER, false, List.of(1L, 3L))
                        .signedBy(TOKEN_TREASURY, DEFAULT_PAYER),
                cryptoDeleteAllowance()
                        .addNftDeleteAllowance(TOKEN_TREASURY, NON_FUNGIBLE_TOKEN, List.of(4L))
                        .signedBy(TOKEN_TREASURY, DEFAULT_PAYER)
                        .hasPrecheck(INVALID_TOKEN_NFT_SERIAL_NUMBER),
                getAccountDetails(TOKEN_TREASURY).payingWith(GENESIS),
                cryptoTransfer(movingUniqueWithAllowance(NON_FUNGIBLE_TOKEN, 1L)
                                .between(TOKEN_TREASURY, OTHER_RECEIVER))
                        .payingWith(SPENDER)
                        .signedBy(SPENDER),
                getAccountDetails(TOKEN_TREASURY)
                        .payingWith(GENESIS)
                        .has(accountDetailsWith().nftApprovedForAllAllowancesCount(0)));
    }

    @HapiTest
    final Stream<DynamicTest> invalidOwnerFails() {
        return hapiTest(
                newKeyNamed(SUPPLY_KEY),
                cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(SPENDER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(TOKEN_TREASURY).balance(100 * ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                tokenCreate(FUNGIBLE_TOKEN)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .supplyType(TokenSupplyType.FINITE)
                        .supplyKey(SUPPLY_KEY)
                        .maxSupply(1000L)
                        .initialSupply(10L)
                        .treasury(TOKEN_TREASURY),
                tokenCreate(NON_FUNGIBLE_TOKEN)
                        .maxSupply(10L)
                        .initialSupply(0)
                        .supplyType(TokenSupplyType.FINITE)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .supplyKey(SUPPLY_KEY)
                        .treasury(TOKEN_TREASURY),
                mintToken(
                                NON_FUNGIBLE_TOKEN,
                                List.of(
                                        ByteString.copyFromUtf8("a"),
                                        ByteString.copyFromUtf8("b"),
                                        ByteString.copyFromUtf8("c")))
                        .via(NFT_TOKEN_MINT_TXN),
                mintToken(FUNGIBLE_TOKEN, 500L).via(FUNGIBLE_TOKEN_MINT_TXN),
                cryptoApproveAllowance()
                        .payingWith(PAYER)
                        .addCryptoAllowance(OWNER, SPENDER, 100L)
                        .signedBy(PAYER, OWNER)
                        .blankMemo(),
                cryptoDelete(OWNER),
                cryptoApproveAllowance()
                        .payingWith(PAYER)
                        .addCryptoAllowance(OWNER, SPENDER, 100L)
                        .signedBy(PAYER, OWNER)
                        .blankMemo()
                        .hasPrecheck(INVALID_ALLOWANCE_OWNER_ID),
                cryptoApproveAllowance()
                        .payingWith(PAYER)
                        .addTokenAllowance(OWNER, FUNGIBLE_TOKEN, SPENDER, 100L)
                        .signedBy(PAYER, OWNER)
                        .blankMemo()
                        .hasPrecheck(INVALID_ALLOWANCE_OWNER_ID),
                cryptoApproveAllowance()
                        .payingWith(PAYER)
                        .addNftAllowance(OWNER, NON_FUNGIBLE_TOKEN, SPENDER, false, List.of(1L))
                        .signedBy(PAYER, OWNER)
                        .via(BASE_APPROVE_TXN)
                        .blankMemo()
                        .hasPrecheck(INVALID_ALLOWANCE_OWNER_ID),
                getAccountDetails(OWNER).has(accountDetailsWith().deleted(true)).payingWith(GENESIS));
    }

    @HapiTest
    final Stream<DynamicTest> invalidSpenderFails() {
        return hapiTest(
                newKeyNamed(SUPPLY_KEY),
                cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                cryptoCreate(SPENDER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(TOKEN_TREASURY).balance(100 * ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                tokenCreate(FUNGIBLE_TOKEN)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .supplyType(TokenSupplyType.FINITE)
                        .supplyKey(SUPPLY_KEY)
                        .maxSupply(1000L)
                        .initialSupply(10L)
                        .treasury(TOKEN_TREASURY),
                tokenCreate(NON_FUNGIBLE_TOKEN)
                        .maxSupply(10L)
                        .initialSupply(0)
                        .supplyType(TokenSupplyType.FINITE)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .supplyKey(SUPPLY_KEY)
                        .treasury(TOKEN_TREASURY),
                tokenAssociate(OWNER, FUNGIBLE_TOKEN),
                tokenAssociate(OWNER, NON_FUNGIBLE_TOKEN),
                mintToken(
                                NON_FUNGIBLE_TOKEN,
                                List.of(
                                        ByteString.copyFromUtf8("a"),
                                        ByteString.copyFromUtf8("b"),
                                        ByteString.copyFromUtf8("c")))
                        .via(NFT_TOKEN_MINT_TXN),
                mintToken(FUNGIBLE_TOKEN, 500L).via(FUNGIBLE_TOKEN_MINT_TXN),
                cryptoTransfer(movingUnique(NON_FUNGIBLE_TOKEN, 1L, 2L, 3L).between(TOKEN_TREASURY, OWNER)),
                cryptoDelete(SPENDER),
                cryptoApproveAllowance()
                        .payingWith(OWNER)
                        .addCryptoAllowance(OWNER, SPENDER, 100L)
                        .blankMemo()
                        .hasKnownStatus(INVALID_ALLOWANCE_SPENDER_ID),
                cryptoApproveAllowance()
                        .payingWith(OWNER)
                        .addTokenAllowance(OWNER, FUNGIBLE_TOKEN, SPENDER, 100L)
                        .blankMemo()
                        .hasKnownStatus(INVALID_ALLOWANCE_SPENDER_ID),
                cryptoApproveAllowance()
                        .payingWith(OWNER)
                        .addNftAllowance(OWNER, NON_FUNGIBLE_TOKEN, SPENDER, false, List.of(1L))
                        .via(BASE_APPROVE_TXN)
                        .blankMemo()
                        .hasKnownStatus(INVALID_ALLOWANCE_SPENDER_ID));
    }

    @HapiTest
    final Stream<DynamicTest> noOwnerDefaultsToPayer() {
        return hapiTest(
                newKeyNamed(SUPPLY_KEY),
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                cryptoCreate(SPENDER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(ANOTHER_SPENDER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(TOKEN_TREASURY).balance(100 * ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                tokenCreate(FUNGIBLE_TOKEN)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .supplyType(TokenSupplyType.FINITE)
                        .supplyKey(SUPPLY_KEY)
                        .maxSupply(1000L)
                        .initialSupply(10L)
                        .treasury(TOKEN_TREASURY),
                tokenCreate(NON_FUNGIBLE_TOKEN)
                        .maxSupply(10L)
                        .initialSupply(0)
                        .supplyType(TokenSupplyType.FINITE)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .supplyKey(SUPPLY_KEY)
                        .treasury(TOKEN_TREASURY),
                tokenAssociate(PAYER, FUNGIBLE_TOKEN),
                tokenAssociate(PAYER, NON_FUNGIBLE_TOKEN),
                mintToken(
                                NON_FUNGIBLE_TOKEN,
                                List.of(
                                        ByteString.copyFromUtf8("a"),
                                        ByteString.copyFromUtf8("b"),
                                        ByteString.copyFromUtf8("c")))
                        .via(NFT_TOKEN_MINT_TXN),
                mintToken(FUNGIBLE_TOKEN, 500L).via(FUNGIBLE_TOKEN_MINT_TXN),
                cryptoTransfer(movingUnique(NON_FUNGIBLE_TOKEN, 1L, 2L, 3L).between(TOKEN_TREASURY, PAYER)),
                cryptoApproveAllowance()
                        .payingWith(PAYER)
                        .addCryptoAllowance(MISSING_OWNER, ANOTHER_SPENDER, 100L)
                        .addTokenAllowance(MISSING_OWNER, FUNGIBLE_TOKEN, SPENDER, 100L)
                        .addNftAllowance(MISSING_OWNER, NON_FUNGIBLE_TOKEN, SPENDER, false, List.of(1L))
                        .via(APPROVE_TXN)
                        .blankMemo()
                        .logged(),
                getTxnRecord(APPROVE_TXN),
                validateChargedUsdWithin(APPROVE_TXN, 0.05238, 0.01),
                getAccountDetails(PAYER)
                        .payingWith(GENESIS)
                        .has(accountDetailsWith()
                                .cryptoAllowancesCount(1)
                                .nftApprovedForAllAllowancesCount(0)
                                .tokenAllowancesCount(1)
                                .cryptoAllowancesContaining(ANOTHER_SPENDER, 100L)
                                .tokenAllowancesContaining(FUNGIBLE_TOKEN, SPENDER, 100L)));
    }

    @HapiTest
    final Stream<DynamicTest> canHaveMultipleOwners() {
        return hapiTest(
                newKeyNamed(SUPPLY_KEY),
                cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                cryptoCreate(SECOND_OWNER).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                cryptoCreate(SPENDER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(TOKEN_TREASURY).balance(100 * ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                tokenCreate(FUNGIBLE_TOKEN)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .supplyType(TokenSupplyType.FINITE)
                        .supplyKey(SUPPLY_KEY)
                        .maxSupply(10_000L)
                        .initialSupply(10L)
                        .treasury(TOKEN_TREASURY),
                tokenCreate(NON_FUNGIBLE_TOKEN)
                        .maxSupply(10L)
                        .initialSupply(0)
                        .supplyType(TokenSupplyType.FINITE)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .supplyKey(SUPPLY_KEY)
                        .treasury(TOKEN_TREASURY),
                tokenAssociate(OWNER, FUNGIBLE_TOKEN, NON_FUNGIBLE_TOKEN),
                tokenAssociate(SECOND_OWNER, FUNGIBLE_TOKEN, NON_FUNGIBLE_TOKEN),
                mintToken(
                                NON_FUNGIBLE_TOKEN,
                                List.of(
                                        ByteString.copyFromUtf8("a"),
                                        ByteString.copyFromUtf8("b"),
                                        ByteString.copyFromUtf8("c"),
                                        ByteString.copyFromUtf8("d"),
                                        ByteString.copyFromUtf8("e"),
                                        ByteString.copyFromUtf8("f")))
                        .via(NFT_TOKEN_MINT_TXN),
                mintToken(FUNGIBLE_TOKEN, 1000L).via(FUNGIBLE_TOKEN_MINT_TXN),
                cryptoTransfer(
                        moving(500, FUNGIBLE_TOKEN).between(TOKEN_TREASURY, OWNER),
                        moving(500, FUNGIBLE_TOKEN).between(TOKEN_TREASURY, SECOND_OWNER),
                        movingUnique(NON_FUNGIBLE_TOKEN, 1L, 2L, 3L).between(TOKEN_TREASURY, OWNER),
                        movingUnique(NON_FUNGIBLE_TOKEN, 4L, 5L, 6L).between(TOKEN_TREASURY, SECOND_OWNER)),
                cryptoApproveAllowance()
                        .payingWith(DEFAULT_PAYER)
                        .addCryptoAllowance(OWNER, SPENDER, ONE_HBAR)
                        .addTokenAllowance(OWNER, FUNGIBLE_TOKEN, SPENDER, 100L)
                        .addNftAllowance(OWNER, NON_FUNGIBLE_TOKEN, SPENDER, false, List.of(1L))
                        .addCryptoAllowance(SECOND_OWNER, SPENDER, ONE_HBAR)
                        .addTokenAllowance(SECOND_OWNER, FUNGIBLE_TOKEN, SPENDER, 100L)
                        .addNftAllowance(SECOND_OWNER, NON_FUNGIBLE_TOKEN, SPENDER, false, List.of(4L))
                        .hasKnownStatus(INVALID_SIGNATURE),
                cryptoApproveAllowance()
                        .payingWith(DEFAULT_PAYER)
                        .addCryptoAllowance(OWNER, SPENDER, ONE_HBAR)
                        .addTokenAllowance(OWNER, FUNGIBLE_TOKEN, SPENDER, 100L)
                        .addNftAllowance(OWNER, NON_FUNGIBLE_TOKEN, SPENDER, false, List.of(1L))
                        .addCryptoAllowance(SECOND_OWNER, SPENDER, ONE_HBAR)
                        .addTokenAllowance(SECOND_OWNER, FUNGIBLE_TOKEN, SPENDER, 100L)
                        .addNftAllowance(SECOND_OWNER, NON_FUNGIBLE_TOKEN, SPENDER, false, List.of(4L))
                        .signedBy(DEFAULT_PAYER, OWNER)
                        .hasKnownStatus(INVALID_SIGNATURE),
                cryptoApproveAllowance()
                        .payingWith(DEFAULT_PAYER)
                        .addCryptoAllowance(OWNER, SPENDER, ONE_HBAR)
                        .addTokenAllowance(OWNER, FUNGIBLE_TOKEN, SPENDER, 100L)
                        .addNftAllowance(OWNER, NON_FUNGIBLE_TOKEN, SPENDER, false, List.of(1L))
                        .addCryptoAllowance(SECOND_OWNER, SPENDER, ONE_HBAR)
                        .addTokenAllowance(SECOND_OWNER, FUNGIBLE_TOKEN, SPENDER, 100L)
                        .addNftAllowance(SECOND_OWNER, NON_FUNGIBLE_TOKEN, SPENDER, false, List.of(4L))
                        .signedBy(DEFAULT_PAYER, SECOND_OWNER)
                        .hasKnownStatus(INVALID_SIGNATURE),
                cryptoApproveAllowance()
                        .payingWith(DEFAULT_PAYER)
                        .addCryptoAllowance(OWNER, SPENDER, ONE_HBAR)
                        .addTokenAllowance(OWNER, FUNGIBLE_TOKEN, SPENDER, 100L)
                        .addNftAllowance(OWNER, NON_FUNGIBLE_TOKEN, SPENDER, false, List.of(1L))
                        .addCryptoAllowance(SECOND_OWNER, SPENDER, 2 * ONE_HBAR)
                        .addTokenAllowance(SECOND_OWNER, FUNGIBLE_TOKEN, SPENDER, 300L)
                        .addNftAllowance(SECOND_OWNER, NON_FUNGIBLE_TOKEN, SPENDER, false, List.of(4L, 5L))
                        .signedBy(DEFAULT_PAYER, OWNER, SECOND_OWNER),
                getAccountDetails(OWNER)
                        .payingWith(GENESIS)
                        .has(accountDetailsWith()
                                .tokenAllowancesContaining(FUNGIBLE_TOKEN, SPENDER, 100L)
                                .cryptoAllowancesContaining(SPENDER, ONE_HBAR)),
                getAccountDetails(SECOND_OWNER)
                        .payingWith(GENESIS)
                        .has(accountDetailsWith()
                                .tokenAllowancesContaining(FUNGIBLE_TOKEN, SPENDER, 300L)
                                .cryptoAllowancesContaining(SPENDER, 2 * ONE_HBAR)));
    }

    @HapiTest
    final Stream<DynamicTest> serialsInAscendingOrder() {
        return hapiTest(
                newKeyNamed(SUPPLY_KEY),
                cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                cryptoCreate(SPENDER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(ANOTHER_SPENDER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(TOKEN_TREASURY).balance(100 * ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                tokenCreate(NON_FUNGIBLE_TOKEN)
                        .maxSupply(10L)
                        .initialSupply(0)
                        .supplyType(TokenSupplyType.FINITE)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .supplyKey(SUPPLY_KEY)
                        .treasury(TOKEN_TREASURY),
                tokenAssociate(OWNER, NON_FUNGIBLE_TOKEN),
                mintToken(
                                NON_FUNGIBLE_TOKEN,
                                List.of(
                                        ByteString.copyFromUtf8("a"),
                                        ByteString.copyFromUtf8("b"),
                                        ByteString.copyFromUtf8("c"),
                                        ByteString.copyFromUtf8("d")))
                        .via(NFT_TOKEN_MINT_TXN),
                cryptoTransfer(movingUnique(NON_FUNGIBLE_TOKEN, 1L, 2L, 3L, 4L).between(TOKEN_TREASURY, OWNER)),
                cryptoApproveAllowance()
                        .payingWith(OWNER)
                        .addNftAllowance(OWNER, NON_FUNGIBLE_TOKEN, SPENDER, true, List.of(1L))
                        .fee(ONE_HBAR),
                cryptoApproveAllowance()
                        .payingWith(OWNER)
                        .addNftAllowance(OWNER, NON_FUNGIBLE_TOKEN, ANOTHER_SPENDER, false, List.of(4L, 2L, 3L))
                        .fee(ONE_HBAR),
                getAccountDetails(OWNER)
                        .payingWith(GENESIS)
                        .logged()
                        .has(accountDetailsWith()
                                .nftApprovedForAllAllowancesCount(1)
                                .nftApprovedAllowancesContaining(NON_FUNGIBLE_TOKEN, SPENDER)));
    }

    @HapiTest
    final Stream<DynamicTest> succeedsWhenTokenPausedFrozenKycRevoked() {
        return hapiTest(
                newKeyNamed(SUPPLY_KEY),
                newKeyNamed(ADMIN_KEY),
                newKeyNamed(FREEZE_KEY),
                newKeyNamed(KYC_KEY),
                newKeyNamed(PAUSE_KEY),
                cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                cryptoCreate(SPENDER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(ANOTHER_SPENDER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(SECOND_SPENDER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(THIRD_SPENDER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(TOKEN_TREASURY).balance(100 * ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                tokenCreate(FUNGIBLE_TOKEN)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .supplyType(TokenSupplyType.FINITE)
                        .supplyKey(SUPPLY_KEY)
                        .maxSupply(1000L)
                        .initialSupply(10L)
                        .kycKey(KYC_KEY)
                        .adminKey(ADMIN_KEY)
                        .freezeKey(FREEZE_KEY)
                        .pauseKey(PAUSE_KEY)
                        .treasury(TOKEN_TREASURY),
                tokenCreate(NON_FUNGIBLE_TOKEN)
                        .maxSupply(10L)
                        .initialSupply(0)
                        .supplyType(TokenSupplyType.FINITE)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .supplyKey(SUPPLY_KEY)
                        .kycKey(KYC_KEY)
                        .adminKey(ADMIN_KEY)
                        .freezeKey(FREEZE_KEY)
                        .pauseKey(PAUSE_KEY)
                        .treasury(TOKEN_TREASURY),
                tokenAssociate(OWNER, FUNGIBLE_TOKEN),
                tokenAssociate(OWNER, NON_FUNGIBLE_TOKEN),
                mintToken(
                                NON_FUNGIBLE_TOKEN,
                                List.of(
                                        ByteString.copyFromUtf8("a"),
                                        ByteString.copyFromUtf8("b"),
                                        ByteString.copyFromUtf8("c")))
                        .via(NFT_TOKEN_MINT_TXN),
                mintToken(FUNGIBLE_TOKEN, 500L).via(FUNGIBLE_TOKEN_MINT_TXN),
                grantTokenKyc(FUNGIBLE_TOKEN, OWNER),
                grantTokenKyc(NON_FUNGIBLE_TOKEN, OWNER),
                cryptoTransfer(movingUnique(NON_FUNGIBLE_TOKEN, 1L, 2L, 3L).between(TOKEN_TREASURY, OWNER)),
                cryptoApproveAllowance()
                        .payingWith(OWNER)
                        .addTokenAllowance(OWNER, FUNGIBLE_TOKEN, SPENDER, 100L)
                        .addNftAllowance(OWNER, NON_FUNGIBLE_TOKEN, SPENDER, false, List.of(1L))
                        .fee(ONE_HBAR),
                revokeTokenKyc(FUNGIBLE_TOKEN, OWNER),
                revokeTokenKyc(NON_FUNGIBLE_TOKEN, OWNER),
                cryptoApproveAllowance()
                        .payingWith(OWNER)
                        .addTokenAllowance(OWNER, FUNGIBLE_TOKEN, ANOTHER_SPENDER, 100L)
                        .addNftAllowance(OWNER, NON_FUNGIBLE_TOKEN, ANOTHER_SPENDER, false, List.of(1L))
                        .fee(ONE_HBAR),
                tokenPause(FUNGIBLE_TOKEN),
                tokenPause(NON_FUNGIBLE_TOKEN),
                cryptoApproveAllowance()
                        .payingWith(OWNER)
                        .addTokenAllowance(OWNER, FUNGIBLE_TOKEN, SECOND_SPENDER, 100L)
                        .addNftAllowance(OWNER, NON_FUNGIBLE_TOKEN, SECOND_SPENDER, false, List.of(2L))
                        .fee(ONE_HBAR),
                tokenUnpause(FUNGIBLE_TOKEN),
                tokenUnpause(NON_FUNGIBLE_TOKEN),
                tokenFreeze(FUNGIBLE_TOKEN, OWNER),
                tokenFreeze(NON_FUNGIBLE_TOKEN, OWNER),
                cryptoApproveAllowance()
                        .payingWith(OWNER)
                        .addTokenAllowance(OWNER, FUNGIBLE_TOKEN, THIRD_SPENDER, 100L)
                        .addNftAllowance(OWNER, NON_FUNGIBLE_TOKEN, THIRD_SPENDER, false, List.of(3L))
                        .fee(ONE_HBAR),
                getAccountDetails(OWNER)
                        .payingWith(GENESIS)
                        .has(accountDetailsWith()
                                .cryptoAllowancesCount(0)
                                .nftApprovedForAllAllowancesCount(0)
                                .tokenAllowancesCount(4)));
    }

    @HapiTest
    final Stream<DynamicTest> tokenExceedsMaxSupplyFails() {
        return hapiTest(
                newKeyNamed(SUPPLY_KEY),
                cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                cryptoCreate(SPENDER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(TOKEN_TREASURY).balance(100 * ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                tokenCreate(FUNGIBLE_TOKEN)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .supplyType(TokenSupplyType.FINITE)
                        .supplyKey(SUPPLY_KEY)
                        .maxSupply(1000L)
                        .initialSupply(10L)
                        .treasury(TOKEN_TREASURY),
                tokenAssociate(OWNER, FUNGIBLE_TOKEN),
                mintToken(FUNGIBLE_TOKEN, 500L).via(FUNGIBLE_TOKEN_MINT_TXN),
                cryptoApproveAllowance()
                        .payingWith(OWNER)
                        .addTokenAllowance(OWNER, FUNGIBLE_TOKEN, SPENDER, 5000L)
                        .fee(ONE_HUNDRED_HBARS)
                        .hasPrecheck(AMOUNT_EXCEEDS_TOKEN_MAX_SUPPLY));
    }

    @HapiTest
    final Stream<DynamicTest> validatesSerialNums() {
        return hapiTest(
                newKeyNamed(SUPPLY_KEY),
                cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                cryptoCreate(SPENDER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(TOKEN_TREASURY).balance(100 * ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                tokenCreate(NON_FUNGIBLE_TOKEN)
                        .maxSupply(10L)
                        .initialSupply(0)
                        .supplyType(TokenSupplyType.FINITE)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .supplyKey(SUPPLY_KEY)
                        .treasury(TOKEN_TREASURY),
                tokenAssociate(OWNER, NON_FUNGIBLE_TOKEN),
                mintToken(
                                NON_FUNGIBLE_TOKEN,
                                List.of(
                                        ByteString.copyFromUtf8("a"),
                                        ByteString.copyFromUtf8("b"),
                                        ByteString.copyFromUtf8("c")))
                        .via(NFT_TOKEN_MINT_TXN),
                cryptoTransfer(movingUnique(NON_FUNGIBLE_TOKEN, 1L, 2L).between(TOKEN_TREASURY, OWNER)),
                cryptoApproveAllowance()
                        .payingWith(OWNER)
                        .addNftAllowance(OWNER, NON_FUNGIBLE_TOKEN, SPENDER, false, List.of(1000L))
                        .fee(ONE_HUNDRED_HBARS)
                        .hasPrecheck(INVALID_TOKEN_NFT_SERIAL_NUMBER),
                cryptoApproveAllowance()
                        .payingWith(OWNER)
                        .addNftAllowance(OWNER, NON_FUNGIBLE_TOKEN, SPENDER, false, List.of(-1000L))
                        .fee(ONE_HUNDRED_HBARS)
                        .hasPrecheck(INVALID_TOKEN_NFT_SERIAL_NUMBER),
                cryptoApproveAllowance()
                        .payingWith(OWNER)
                        .addNftAllowance(OWNER, NON_FUNGIBLE_TOKEN, SPENDER, false, List.of(3L))
                        .fee(ONE_HUNDRED_HBARS)
                        .hasKnownStatus(SENDER_DOES_NOT_OWN_NFT_SERIAL_NO),
                cryptoApproveAllowance()
                        .payingWith(OWNER)
                        .addNftAllowance(OWNER, NON_FUNGIBLE_TOKEN, SPENDER, false, List.of(2L, 2L, 2L))
                        .fee(ONE_HUNDRED_HBARS));
    }

    @HapiTest
    final Stream<DynamicTest> invalidTokenTypeFails() {
        return hapiTest(
                newKeyNamed(SUPPLY_KEY),
                cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                cryptoCreate(SPENDER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(TOKEN_TREASURY).balance(100 * ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                tokenCreate(FUNGIBLE_TOKEN)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .supplyType(TokenSupplyType.FINITE)
                        .supplyKey(SUPPLY_KEY)
                        .maxSupply(1000L)
                        .initialSupply(10L)
                        .treasury(TOKEN_TREASURY),
                tokenCreate(NON_FUNGIBLE_TOKEN)
                        .maxSupply(10L)
                        .initialSupply(0)
                        .supplyType(TokenSupplyType.FINITE)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .supplyKey(SUPPLY_KEY)
                        .treasury(TOKEN_TREASURY),
                tokenAssociate(OWNER, FUNGIBLE_TOKEN),
                tokenAssociate(OWNER, NON_FUNGIBLE_TOKEN),
                mintToken(
                                NON_FUNGIBLE_TOKEN,
                                List.of(
                                        ByteString.copyFromUtf8("a"),
                                        ByteString.copyFromUtf8("b"),
                                        ByteString.copyFromUtf8("c")))
                        .via(NFT_TOKEN_MINT_TXN),
                mintToken(FUNGIBLE_TOKEN, 500L).via(FUNGIBLE_TOKEN_MINT_TXN),
                cryptoTransfer(movingUnique(NON_FUNGIBLE_TOKEN, 1L, 2L, 3L).between(TOKEN_TREASURY, OWNER)),
                cryptoApproveAllowance()
                        .payingWith(OWNER)
                        .addTokenAllowance(OWNER, NON_FUNGIBLE_TOKEN, SPENDER, 100L)
                        .fee(ONE_HUNDRED_HBARS)
                        .hasPrecheck(NFT_IN_FUNGIBLE_TOKEN_ALLOWANCES),
                cryptoApproveAllowance()
                        .payingWith(OWNER)
                        .addNftAllowance(OWNER, FUNGIBLE_TOKEN, SPENDER, false, List.of(1L))
                        .fee(ONE_HUNDRED_HBARS)
                        .hasPrecheck(FUNGIBLE_TOKEN_IN_NFT_ALLOWANCES));
    }

    @HapiTest
    final Stream<DynamicTest> emptyAllowancesRejected() {
        return hapiTest(
                cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                cryptoApproveAllowance().hasPrecheck(EMPTY_ALLOWANCES).fee(ONE_HUNDRED_HBARS));
    }

    @HapiTest
    final Stream<DynamicTest> tokenNotAssociatedToAccountFails() {
        return hapiTest(
                newKeyNamed(SUPPLY_KEY),
                cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                cryptoCreate(SPENDER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(TOKEN_TREASURY).balance(100 * ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                tokenCreate(FUNGIBLE_TOKEN)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .supplyType(TokenSupplyType.FINITE)
                        .supplyKey(SUPPLY_KEY)
                        .maxSupply(1000L)
                        .initialSupply(10L)
                        .treasury(TOKEN_TREASURY),
                tokenCreate(NON_FUNGIBLE_TOKEN)
                        .maxSupply(10L)
                        .initialSupply(0)
                        .supplyType(TokenSupplyType.FINITE)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .supplyKey(SUPPLY_KEY)
                        .treasury(TOKEN_TREASURY),
                mintToken(
                                NON_FUNGIBLE_TOKEN,
                                List.of(
                                        ByteString.copyFromUtf8("a"),
                                        ByteString.copyFromUtf8("b"),
                                        ByteString.copyFromUtf8("c")))
                        .via(NFT_TOKEN_MINT_TXN),
                mintToken(FUNGIBLE_TOKEN, 500L).via(FUNGIBLE_TOKEN_MINT_TXN),
                cryptoApproveAllowance()
                        .payingWith(OWNER)
                        .addTokenAllowance(OWNER, FUNGIBLE_TOKEN, SPENDER, 100L)
                        .fee(ONE_HUNDRED_HBARS)
                        .hasPrecheck(TOKEN_NOT_ASSOCIATED_TO_ACCOUNT),
                cryptoApproveAllowance()
                        .payingWith(OWNER)
                        .addNftAllowance(OWNER, NON_FUNGIBLE_TOKEN, SPENDER, false, List.of(1L))
                        .fee(ONE_HUNDRED_HBARS)
                        .hasPrecheck(TOKEN_NOT_ASSOCIATED_TO_ACCOUNT),
                getAccountDetails(OWNER)
                        .payingWith(GENESIS)
                        .has(accountDetailsWith()
                                .cryptoAllowancesCount(0)
                                .nftApprovedForAllAllowancesCount(0)
                                .tokenAllowancesCount(0)));
    }

    @HapiTest
    final Stream<DynamicTest> negativeAmountFailsForFungible() {
        return hapiTest(
                newKeyNamed(SUPPLY_KEY),
                cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                cryptoCreate(SPENDER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(TOKEN_TREASURY).balance(100 * ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                tokenCreate(FUNGIBLE_TOKEN)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .supplyType(TokenSupplyType.FINITE)
                        .supplyKey(SUPPLY_KEY)
                        .maxSupply(1000L)
                        .initialSupply(10L)
                        .treasury(TOKEN_TREASURY),
                tokenCreate(NON_FUNGIBLE_TOKEN)
                        .maxSupply(10L)
                        .initialSupply(0)
                        .supplyType(TokenSupplyType.FINITE)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .supplyKey(SUPPLY_KEY)
                        .treasury(TOKEN_TREASURY),
                tokenAssociate(OWNER, FUNGIBLE_TOKEN),
                tokenAssociate(OWNER, NON_FUNGIBLE_TOKEN),
                mintToken(
                                NON_FUNGIBLE_TOKEN,
                                List.of(
                                        ByteString.copyFromUtf8("a"),
                                        ByteString.copyFromUtf8("b"),
                                        ByteString.copyFromUtf8("c")))
                        .via(NFT_TOKEN_MINT_TXN),
                mintToken(FUNGIBLE_TOKEN, 500L).via(FUNGIBLE_TOKEN_MINT_TXN),
                cryptoTransfer(movingUnique(NON_FUNGIBLE_TOKEN, 1L, 2L, 3L).between(TOKEN_TREASURY, OWNER)),
                cryptoApproveAllowance()
                        .payingWith(OWNER)
                        .addCryptoAllowance(OWNER, SPENDER, -100L)
                        .fee(ONE_HUNDRED_HBARS)
                        .hasPrecheck(NEGATIVE_ALLOWANCE_AMOUNT),
                cryptoApproveAllowance()
                        .payingWith(OWNER)
                        .addTokenAllowance(OWNER, FUNGIBLE_TOKEN, SPENDER, -100L)
                        .fee(ONE_HUNDRED_HBARS)
                        .hasPrecheck(NEGATIVE_ALLOWANCE_AMOUNT),
                getAccountDetails(OWNER)
                        .payingWith(GENESIS)
                        .has(accountDetailsWith()
                                .cryptoAllowancesCount(0)
                                .nftApprovedForAllAllowancesCount(0)
                                .tokenAllowancesCount(0)));
    }

    @HapiTest
    final Stream<DynamicTest> chargedUSDScalesWithAllowances() {
        return hapiTest(
                newKeyNamed(SUPPLY_KEY),
                cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                cryptoCreate(SPENDER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(ANOTHER_SPENDER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(SECOND_SPENDER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(THIRD_SPENDER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(TOKEN_TREASURY).balance(100 * ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                cryptoApproveAllowance()
                        .payingWith(SPENDER)
                        .addCryptoAllowance(SPENDER, ANOTHER_SPENDER, 100L)
                        .via(BASE_APPROVE_TXN)
                        .blankMemo()
                        .logged(),
                validateChargedUsdWithin(BASE_APPROVE_TXN, 0.05, 0.01),
                cryptoApproveAllowance()
                        .payingWith(SPENDER)
                        .addCryptoAllowance(SPENDER, ANOTHER_SPENDER, 100L)
                        .addCryptoAllowance(SPENDER, SECOND_SPENDER, 100L)
                        .via(BASE_APPROVE_TXN)
                        .blankMemo()
                        .logged(),
                validateChargedUsdWithin(BASE_APPROVE_TXN, 0.0505, 0.1),
                cryptoApproveAllowance()
                        .payingWith(SPENDER)
                        .addCryptoAllowance(SPENDER, ANOTHER_SPENDER, 100L)
                        .addCryptoAllowance(SPENDER, SECOND_SPENDER, 100L)
                        .addCryptoAllowance(SPENDER, THIRD_SPENDER, 100L)
                        .via(BASE_APPROVE_TXN)
                        .blankMemo()
                        .logged(),
                validateChargedUsdWithin(BASE_APPROVE_TXN, 0.0509, 0.1));
    }

    @HapiTest
    final Stream<DynamicTest> happyPathWorks() {
        return hapiTest(
                newKeyNamed(SUPPLY_KEY),
                cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                cryptoCreate(SPENDER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(ANOTHER_SPENDER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(TOKEN_TREASURY).balance(100 * ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                tokenCreate(FUNGIBLE_TOKEN)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .supplyType(TokenSupplyType.FINITE)
                        .supplyKey(SUPPLY_KEY)
                        .maxSupply(1000L)
                        .initialSupply(10L)
                        .treasury(TOKEN_TREASURY),
                tokenCreate(NON_FUNGIBLE_TOKEN)
                        .maxSupply(10L)
                        .initialSupply(0)
                        .supplyType(TokenSupplyType.FINITE)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .supplyKey(SUPPLY_KEY)
                        .treasury(TOKEN_TREASURY),
                tokenAssociate(OWNER, FUNGIBLE_TOKEN),
                tokenAssociate(OWNER, NON_FUNGIBLE_TOKEN),
                mintToken(
                                NON_FUNGIBLE_TOKEN,
                                List.of(
                                        ByteString.copyFromUtf8("a"),
                                        ByteString.copyFromUtf8("b"),
                                        ByteString.copyFromUtf8("c")))
                        .via(NFT_TOKEN_MINT_TXN),
                mintToken(FUNGIBLE_TOKEN, 500L).via(FUNGIBLE_TOKEN_MINT_TXN),
                cryptoTransfer(movingUnique(NON_FUNGIBLE_TOKEN, 1L, 2L, 3L).between(TOKEN_TREASURY, OWNER)),
                cryptoApproveAllowance()
                        .payingWith(OWNER)
                        .addCryptoAllowance(OWNER, SPENDER, 100L)
                        .via(BASE_APPROVE_TXN)
                        .blankMemo()
                        .logged(),
                validateChargedUsdWithin(BASE_APPROVE_TXN, 0.05, 0.01),
                cryptoApproveAllowance()
                        .payingWith(OWNER)
                        .addCryptoAllowance(OWNER, ANOTHER_SPENDER, 100L)
                        .addTokenAllowance(OWNER, FUNGIBLE_TOKEN, SPENDER, 100L)
                        .addNftAllowance(OWNER, NON_FUNGIBLE_TOKEN, SPENDER, false, List.of(1L))
                        .via(APPROVE_TXN)
                        .blankMemo(),
                validateChargedUsdWithin(APPROVE_TXN, 0.05238, 0.01),
                getAccountDetails(OWNER)
                        .payingWith(GENESIS)
                        .has(accountDetailsWith()
                                .cryptoAllowancesCount(2)
                                .nftApprovedForAllAllowancesCount(0)
                                .tokenAllowancesCount(1)
                                .cryptoAllowancesContaining(SPENDER, 100L)
                                .tokenAllowancesContaining(FUNGIBLE_TOKEN, SPENDER, 100L)),
                getTokenNftInfo(NON_FUNGIBLE_TOKEN, 1L).hasSpenderID(SPENDER));
    }

    @HapiTest
    final Stream<DynamicTest> duplicateEntriesGetsReplacedWithDifferentTxn() {
        return hapiTest(
                newKeyNamed(SUPPLY_KEY),
                cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                cryptoCreate(SPENDER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(TOKEN_TREASURY).balance(100 * ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                tokenCreate(FUNGIBLE_TOKEN)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .supplyType(TokenSupplyType.FINITE)
                        .supplyKey(SUPPLY_KEY)
                        .maxSupply(1000L)
                        .initialSupply(10L)
                        .treasury(TOKEN_TREASURY),
                tokenCreate(NON_FUNGIBLE_TOKEN)
                        .maxSupply(10L)
                        .initialSupply(0)
                        .supplyType(TokenSupplyType.FINITE)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .supplyKey(SUPPLY_KEY)
                        .treasury(TOKEN_TREASURY),
                tokenAssociate(OWNER, FUNGIBLE_TOKEN),
                tokenAssociate(OWNER, NON_FUNGIBLE_TOKEN),
                mintToken(
                                NON_FUNGIBLE_TOKEN,
                                List.of(
                                        ByteString.copyFromUtf8("a"),
                                        ByteString.copyFromUtf8("b"),
                                        ByteString.copyFromUtf8("c")))
                        .via(NFT_TOKEN_MINT_TXN),
                mintToken(FUNGIBLE_TOKEN, 500L).via(FUNGIBLE_TOKEN_MINT_TXN),
                cryptoTransfer(movingUnique(NON_FUNGIBLE_TOKEN, 1L, 2L, 3L).between(TOKEN_TREASURY, OWNER)),
                cryptoApproveAllowance()
                        .payingWith(OWNER)
                        .addCryptoAllowance(OWNER, SPENDER, 100L)
                        .addTokenAllowance(OWNER, FUNGIBLE_TOKEN, SPENDER, 100L)
                        .addNftAllowance(OWNER, NON_FUNGIBLE_TOKEN, SPENDER, true, List.of(1L, 2L))
                        .via(BASE_APPROVE_TXN)
                        .blankMemo()
                        .logged(),
                getAccountDetails(OWNER)
                        .payingWith(GENESIS)
                        .has(accountDetailsWith()
                                .cryptoAllowancesCount(1)
                                .nftApprovedForAllAllowancesCount(1)
                                .tokenAllowancesCount(1)
                                .cryptoAllowancesContaining(SPENDER, 100L)
                                .tokenAllowancesContaining(FUNGIBLE_TOKEN, SPENDER, 100L)
                                .nftApprovedAllowancesContaining(NON_FUNGIBLE_TOKEN, SPENDER)),
                getTokenNftInfo(NON_FUNGIBLE_TOKEN, 1L).hasSpenderID(SPENDER),
                getTokenNftInfo(NON_FUNGIBLE_TOKEN, 2L).hasSpenderID(SPENDER),
                cryptoApproveAllowance()
                        .payingWith(OWNER)
                        .addCryptoAllowance(OWNER, SPENDER, 200L)
                        .addTokenAllowance(OWNER, FUNGIBLE_TOKEN, SPENDER, 300L)
                        .addNftAllowance(OWNER, NON_FUNGIBLE_TOKEN, SPENDER, false, List.of(3L))
                        .via("duplicateAllowances"),
                getTokenNftInfo(NON_FUNGIBLE_TOKEN, 1L).hasSpenderID(SPENDER),
                getTokenNftInfo(NON_FUNGIBLE_TOKEN, 2L).hasSpenderID(SPENDER),
                getTokenNftInfo(NON_FUNGIBLE_TOKEN, 3L).hasSpenderID(SPENDER),
                getAccountDetails(OWNER)
                        .payingWith(GENESIS)
                        .has(accountDetailsWith()
                                .cryptoAllowancesCount(1)
                                .nftApprovedForAllAllowancesCount(0)
                                .tokenAllowancesCount(1)
                                .cryptoAllowancesContaining(SPENDER, 200L)
                                .tokenAllowancesContaining(FUNGIBLE_TOKEN, SPENDER, 300L)),
                cryptoApproveAllowance()
                        .payingWith(OWNER)
                        .addCryptoAllowance(OWNER, SPENDER, 0L)
                        .addTokenAllowance(OWNER, FUNGIBLE_TOKEN, SPENDER, 0L)
                        .addNftAllowance(OWNER, NON_FUNGIBLE_TOKEN, SPENDER, true, List.of())
                        .via("removeAllowances"),
                getAccountDetails(OWNER)
                        .payingWith(GENESIS)
                        .has(accountDetailsWith()
                                .cryptoAllowancesCount(0)
                                .nftApprovedForAllAllowancesCount(1)
                                .tokenAllowancesCount(0)
                                .nftApprovedAllowancesContaining(NON_FUNGIBLE_TOKEN, SPENDER)),
                getTokenNftInfo(NON_FUNGIBLE_TOKEN, 1L).hasSpenderID(SPENDER),
                getTokenNftInfo(NON_FUNGIBLE_TOKEN, 2L).hasSpenderID(SPENDER),
                getTokenNftInfo(NON_FUNGIBLE_TOKEN, 3L).hasSpenderID(SPENDER));
    }

    @HapiTest
    final Stream<DynamicTest> cannotHaveMultipleAllowedSpendersForTheSameNFTSerial() {
        return hapiTest(
                newKeyNamed(SUPPLY_KEY),
                cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                cryptoCreate(SPENDER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(SECOND_SPENDER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(RECEIVER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(TOKEN_TREASURY).balance(100 * ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                tokenCreate(NON_FUNGIBLE_TOKEN)
                        .maxSupply(10L)
                        .initialSupply(0)
                        .supplyType(TokenSupplyType.FINITE)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .supplyKey(SUPPLY_KEY)
                        .treasury(TOKEN_TREASURY),
                tokenAssociate(OWNER, NON_FUNGIBLE_TOKEN),
                tokenAssociate(RECEIVER, NON_FUNGIBLE_TOKEN),
                mintToken(NON_FUNGIBLE_TOKEN, List.of(ByteString.copyFromUtf8("a")))
                        .via(NFT_TOKEN_MINT_TXN),
                cryptoTransfer(movingUnique(NON_FUNGIBLE_TOKEN, 1L).between(TOKEN_TREASURY, OWNER)),
                cryptoApproveAllowance()
                        .payingWith(DEFAULT_PAYER)
                        .addNftAllowance(OWNER, NON_FUNGIBLE_TOKEN, SPENDER, true, List.of(1L))
                        .signedBy(DEFAULT_PAYER, OWNER),
                cryptoApproveAllowance()
                        .payingWith(DEFAULT_PAYER)
                        .addNftAllowance(OWNER, NON_FUNGIBLE_TOKEN, SECOND_SPENDER, true, List.of(1L))
                        .signedBy(DEFAULT_PAYER, OWNER),
                getTokenNftInfo(NON_FUNGIBLE_TOKEN, 1L)
                        .hasSpenderID(SECOND_SPENDER)
                        .logged(),
                getAccountDetails(OWNER)
                        .payingWith(GENESIS)
                        .has(accountDetailsWith().nftApprovedForAllAllowancesCount(2)),
                cryptoTransfer(movingUniqueWithAllowance(NON_FUNGIBLE_TOKEN, 1).between(OWNER, RECEIVER))
                        .payingWith(SECOND_SPENDER)
                        .signedBy(SECOND_SPENDER),
                getTokenNftInfo(NON_FUNGIBLE_TOKEN, 1L).hasNoSpender().logged(),
                cryptoTransfer(movingUnique(NON_FUNGIBLE_TOKEN, 1).between(RECEIVER, OWNER)),
                cryptoTransfer(movingUniqueWithAllowance(NON_FUNGIBLE_TOKEN, 1).between(OWNER, RECEIVER))
                        .payingWith(SECOND_SPENDER)
                        .signedBy(SECOND_SPENDER),
                cryptoApproveAllowance()
                        .payingWith(DEFAULT_PAYER)
                        .addNftAllowance(OWNER, NON_FUNGIBLE_TOKEN, SECOND_SPENDER, false, List.of())
                        .signedBy(DEFAULT_PAYER, OWNER),
                getAccountDetails(OWNER)
                        .payingWith(GENESIS)
                        .has(accountDetailsWith().nftApprovedForAllAllowancesCount(1)),
                cryptoTransfer(movingUnique(NON_FUNGIBLE_TOKEN, 1).between(RECEIVER, OWNER)),
                cryptoTransfer(movingUniqueWithAllowance(NON_FUNGIBLE_TOKEN, 1).between(OWNER, RECEIVER))
                        .payingWith(SECOND_SPENDER)
                        .signedBy(SECOND_SPENDER)
                        .hasKnownStatus(SPENDER_DOES_NOT_HAVE_ALLOWANCE),
                cryptoApproveAllowance()
                        .payingWith(DEFAULT_PAYER)
                        .addNftAllowance(OWNER, NON_FUNGIBLE_TOKEN, SECOND_SPENDER, false, List.of(1L))
                        .signedBy(DEFAULT_PAYER, OWNER),
                cryptoTransfer(movingUniqueWithAllowance(NON_FUNGIBLE_TOKEN, 1).between(OWNER, RECEIVER))
                        .payingWith(SECOND_SPENDER)
                        .signedBy(SECOND_SPENDER),
                cryptoTransfer(movingUnique(NON_FUNGIBLE_TOKEN, 1).between(RECEIVER, OWNER)),
                cryptoTransfer(movingUniqueWithAllowance(NON_FUNGIBLE_TOKEN, 1).between(OWNER, RECEIVER))
                        .payingWith(SECOND_SPENDER)
                        .signedBy(SECOND_SPENDER)
                        .hasKnownStatus(SPENDER_DOES_NOT_HAVE_ALLOWANCE));
    }

    @HapiTest
    final Stream<DynamicTest> approveForAllDoesNotSetExplicitNFTSpender() {
        return hapiTest(
                newKeyNamed(SUPPLY_KEY),
                cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                cryptoCreate(SPENDER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(RECEIVER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(TOKEN_TREASURY).balance(100 * ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                tokenCreate(NON_FUNGIBLE_TOKEN)
                        .maxSupply(10L)
                        .initialSupply(0)
                        .supplyType(TokenSupplyType.FINITE)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .supplyKey(SUPPLY_KEY)
                        .treasury(TOKEN_TREASURY),
                tokenAssociate(OWNER, NON_FUNGIBLE_TOKEN),
                tokenAssociate(RECEIVER, NON_FUNGIBLE_TOKEN),
                tokenAssociate(SPENDER, NON_FUNGIBLE_TOKEN),
                mintToken(NON_FUNGIBLE_TOKEN, List.of(ByteString.copyFromUtf8("a")))
                        .via(NFT_TOKEN_MINT_TXN),
                cryptoTransfer(movingUnique(NON_FUNGIBLE_TOKEN, 1L).between(TOKEN_TREASURY, OWNER)),
                cryptoApproveAllowance()
                        .payingWith(DEFAULT_PAYER)
                        .addNftAllowance(OWNER, NON_FUNGIBLE_TOKEN, SPENDER, true, List.of())
                        .signedBy(DEFAULT_PAYER, OWNER),
                getTokenNftInfo(NON_FUNGIBLE_TOKEN, 1L).hasNoSpender().logged(),
                getAccountDetails(OWNER)
                        .payingWith(GENESIS)
                        .has(accountDetailsWith().nftApprovedForAllAllowancesCount(1)),
                cryptoTransfer(movingUniqueWithAllowance(NON_FUNGIBLE_TOKEN, 1).between(OWNER, RECEIVER))
                        .payingWith(SPENDER),
                getTokenNftInfo(NON_FUNGIBLE_TOKEN, 1L).hasNoSpender().logged());
    }

    @HapiTest
    final Stream<DynamicTest> scheduledCryptoApproveAllowanceWorks() {
        return hapiTest(
                newKeyNamed(SUPPLY_KEY),
                cryptoCreate(TOKEN_TREASURY),
                cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(SPENDER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(RECEIVER),
                cryptoCreate(OTHER_RECEIVER).balance(ONE_HBAR).maxAutomaticTokenAssociations(1),
                tokenCreate(FUNGIBLE_TOKEN)
                        .supplyType(TokenSupplyType.FINITE)
                        .tokenType(FUNGIBLE_COMMON)
                        .treasury(TOKEN_TREASURY)
                        .maxSupply(10000)
                        .initialSupply(5000),
                tokenCreate(NON_FUNGIBLE_TOKEN)
                        .supplyType(TokenSupplyType.FINITE)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .treasury(TOKEN_TREASURY)
                        .maxSupply(12L)
                        .supplyKey(SUPPLY_KEY)
                        .initialSupply(0L),
                tokenCreate(TOKEN_WITH_CUSTOM_FEE)
                        .treasury(TOKEN_TREASURY)
                        .supplyType(TokenSupplyType.FINITE)
                        .initialSupply(1000)
                        .maxSupply(5000)
                        .withCustom(fixedHtsFee(10, "0.0.0", TOKEN_TREASURY)),
                mintToken(
                        NON_FUNGIBLE_TOKEN,
                        List.of(
                                ByteString.copyFromUtf8("a"),
                                ByteString.copyFromUtf8("b"),
                                ByteString.copyFromUtf8("c"))),
                tokenAssociate(OWNER, FUNGIBLE_TOKEN, NON_FUNGIBLE_TOKEN, TOKEN_WITH_CUSTOM_FEE),
                tokenAssociate(RECEIVER, FUNGIBLE_TOKEN, NON_FUNGIBLE_TOKEN, TOKEN_WITH_CUSTOM_FEE),
                cryptoTransfer(
                        moving(1000, FUNGIBLE_TOKEN).between(TOKEN_TREASURY, OWNER),
                        moving(15, TOKEN_WITH_CUSTOM_FEE).between(TOKEN_TREASURY, OWNER),
                        movingUnique(NON_FUNGIBLE_TOKEN, 1L, 2L, 3L).between(TOKEN_TREASURY, OWNER)),
                scheduleCreate(
                                SCHEDULED_TXN,
                                cryptoApproveAllowance()
                                        .addCryptoAllowance(OWNER, SPENDER, 10 * ONE_HBAR)
                                        .addTokenAllowance(OWNER, FUNGIBLE_TOKEN, SPENDER, 1500)
                                        .addTokenAllowance(OWNER, TOKEN_WITH_CUSTOM_FEE, SPENDER, 100)
                                        .addNftAllowance(OWNER, NON_FUNGIBLE_TOKEN, SPENDER, false, List.of(1L, 2L, 3L))
                                        .fee(ONE_HUNDRED_HBARS))
                        .via("successTx"),
                getTxnRecord("successTx"),
                cryptoTransfer(movingUniqueWithAllowance(NON_FUNGIBLE_TOKEN, 3).between(OWNER, OTHER_RECEIVER))
                        .payingWith(SPENDER)
                        .hasKnownStatus(SPENDER_DOES_NOT_HAVE_ALLOWANCE),
                cryptoTransfer(movingWithAllowance(50, FUNGIBLE_TOKEN).between(OWNER, RECEIVER))
                        .payingWith(SPENDER)
                        .hasKnownStatus(SPENDER_DOES_NOT_HAVE_ALLOWANCE),
                cryptoTransfer(allowanceTinyBarsFromTo(OWNER, RECEIVER, 5 * ONE_HBAR))
                        .payingWith(SPENDER)
                        .hasKnownStatus(SPENDER_DOES_NOT_HAVE_ALLOWANCE),
                scheduleSign(SCHEDULED_TXN).alsoSigningWith(OWNER),
                cryptoTransfer(movingUniqueWithAllowance(NON_FUNGIBLE_TOKEN, 3).between(OWNER, OTHER_RECEIVER))
                        .payingWith(SPENDER),
                getAccountBalance(OTHER_RECEIVER).hasTokenBalance(NON_FUNGIBLE_TOKEN, 1),
                cryptoTransfer(movingWithAllowance(50, FUNGIBLE_TOKEN).between(OWNER, RECEIVER))
                        .payingWith(SPENDER),
                getAccountBalance(RECEIVER).hasTokenBalance(FUNGIBLE_TOKEN, 50),
                cryptoTransfer(allowanceTinyBarsFromTo(OWNER, RECEIVER, 5 * ONE_HBAR))
                        .payingWith(SPENDER),
                getAccountBalance(RECEIVER).hasTinyBars(15 * ONE_HBAR));
    }

    @HapiTest
    final Stream<DynamicTest> approveNegativeCases() {
        final var tryApprovingTheSender = "tryApprovingTheSender";
        final var tryApprovingAboveBalance = "tryApprovingAboveBalance";
        final var tryApprovingNFTToOwner = "tryApprovingNFTToOwner";
        final var tryApprovingNFTWithInvalidSerial = "tryApprovingNFTWithInvalidSerial";
        return hapiTest(
                newKeyNamed(SUPPLY_KEY),
                cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                cryptoCreate(SPENDER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(TOKEN_TREASURY).balance(100 * ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                tokenCreate(FUNGIBLE_TOKEN)
                        .supplyType(TokenSupplyType.FINITE)
                        .tokenType(FUNGIBLE_COMMON)
                        .supplyKey(SUPPLY_KEY)
                        .treasury(TOKEN_TREASURY)
                        .maxSupply(10000)
                        .initialSupply(5000),
                tokenCreate(NON_FUNGIBLE_TOKEN)
                        .supplyType(TokenSupplyType.FINITE)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .treasury(TOKEN_TREASURY)
                        .maxSupply(12L)
                        .supplyKey(SUPPLY_KEY)
                        .initialSupply(0L),
                mintToken(FUNGIBLE_TOKEN, 500L).via(FUNGIBLE_TOKEN_MINT_TXN),
                mintToken(
                        NON_FUNGIBLE_TOKEN,
                        List.of(
                                ByteString.copyFromUtf8("1"),
                                ByteString.copyFromUtf8("2"),
                                ByteString.copyFromUtf8("3"))),
                tokenAssociate(OWNER, FUNGIBLE_TOKEN, NON_FUNGIBLE_TOKEN),
                cryptoTransfer(
                        moving(500L, FUNGIBLE_TOKEN).between(TOKEN_TREASURY, OWNER),
                        movingUnique(NON_FUNGIBLE_TOKEN, 1L, 2L, 3L).between(TOKEN_TREASURY, OWNER)),
                cryptoApproveAllowance()
                        .payingWith(DEFAULT_PAYER)
                        .addTokenAllowance(OWNER, FUNGIBLE_TOKEN, OWNER, 100L)
                        .signedBy(DEFAULT_PAYER, OWNER)
                        .hasKnownStatus(SUCCESS)
                        .via(tryApprovingTheSender),
                cryptoApproveAllowance()
                        .payingWith(DEFAULT_PAYER)
                        .addTokenAllowance(OWNER, FUNGIBLE_TOKEN, SPENDER, 1000L)
                        .signedBy(DEFAULT_PAYER, OWNER)
                        .hasKnownStatus(SUCCESS)
                        .via(tryApprovingAboveBalance),
                cryptoApproveAllowance()
                        .payingWith(DEFAULT_PAYER)
                        .addNftAllowance(OWNER, NON_FUNGIBLE_TOKEN, OWNER, false, List.of(1L))
                        .signedBy(DEFAULT_PAYER, OWNER)
                        .hasKnownStatus(SPENDER_ACCOUNT_SAME_AS_OWNER)
                        .via(tryApprovingNFTToOwner),
                cryptoApproveAllowance()
                        .payingWith(DEFAULT_PAYER)
                        .addNftAllowance(OWNER, NON_FUNGIBLE_TOKEN, SPENDER, false, List.of(1L, 2L, 3L, 4L))
                        .signedBy(DEFAULT_PAYER, OWNER)
                        .hasKnownStatus(INVALID_TOKEN_NFT_SERIAL_NUMBER)
                        .via(tryApprovingNFTWithInvalidSerial),
                emptyChildRecordsCheck(tryApprovingTheSender, SUCCESS),
                emptyChildRecordsCheck(tryApprovingAboveBalance, SUCCESS),
                emptyChildRecordsCheck(tryApprovingNFTToOwner, SPENDER_ACCOUNT_SAME_AS_OWNER),
                emptyChildRecordsCheck(tryApprovingNFTWithInvalidSerial, INVALID_TOKEN_NFT_SERIAL_NUMBER),
                getAccountBalance(OWNER).hasTokenBalance(FUNGIBLE_TOKEN, 500L),
                getAccountBalance(SPENDER).hasTokenBalance(FUNGIBLE_TOKEN, 0L),
                getAccountBalance(OWNER).hasTokenBalance(NON_FUNGIBLE_TOKEN, 3L),
                getAccountBalance(SPENDER).hasTokenBalance(NON_FUNGIBLE_TOKEN, 0L));
    }

    @HapiTest
    final Stream<DynamicTest> approveAllowanceForDeletedToken() {
        return hapiTest(
                newKeyNamed(SUPPLY_KEY),
                cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                cryptoCreate(SPENDER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(NON_FUNGIBLE_TOKEN)
                        .supplyKey(SUPPLY_KEY)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .treasury(TOKEN_TREASURY)
                        .adminKey(TOKEN_TREASURY)
                        .initialSupply(0),
                mintToken(NON_FUNGIBLE_TOKEN, List.of(ByteString.copyFromUtf8("a"))),
                tokenCreate(FUNGIBLE_TOKEN)
                        .adminKey(TOKEN_TREASURY)
                        .supplyKey(SUPPLY_KEY)
                        .treasury(TOKEN_TREASURY),
                tokenAssociate(OWNER, FUNGIBLE_TOKEN, NON_FUNGIBLE_TOKEN),
                cryptoTransfer(
                        moving(500L, FUNGIBLE_TOKEN).between(TOKEN_TREASURY, OWNER),
                        movingUnique(NON_FUNGIBLE_TOKEN, 1L).between(TOKEN_TREASURY, OWNER)),
                tokenDelete(FUNGIBLE_TOKEN),
                tokenDelete(NON_FUNGIBLE_TOKEN),
                // try to approve allowance for deleted fungible token
                cryptoApproveAllowance()
                        .addTokenAllowance(OWNER, FUNGIBLE_TOKEN, SPENDER, 100L)
                        .signedBy(DEFAULT_PAYER, OWNER)
                        .hasKnownStatus(TOKEN_WAS_DELETED),

                // try to approve allowance for deleted nft token
                cryptoApproveAllowance()
                        .addNftAllowance(OWNER, NON_FUNGIBLE_TOKEN, SPENDER, true, List.of())
                        .signedBy(DEFAULT_PAYER, OWNER)
                        .hasKnownStatus(TOKEN_WAS_DELETED));
    }

    @HapiTest
    final Stream<DynamicTest> approveAllowanceToOwner() {
        return hapiTest(
                newKeyNamed(SUPPLY_KEY),
                cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(NON_FUNGIBLE_TOKEN)
                        .supplyKey(SUPPLY_KEY)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .treasury(TOKEN_TREASURY)
                        .initialSupply(0),
                mintToken(NON_FUNGIBLE_TOKEN, List.of(ByteString.copyFromUtf8("a"))),
                tokenCreate(FUNGIBLE_TOKEN).supplyKey(SUPPLY_KEY).treasury(TOKEN_TREASURY),
                tokenAssociate(OWNER, FUNGIBLE_TOKEN, NON_FUNGIBLE_TOKEN),
                cryptoTransfer(
                        moving(500L, FUNGIBLE_TOKEN).between(TOKEN_TREASURY, OWNER),
                        movingUnique(NON_FUNGIBLE_TOKEN, 1L).between(TOKEN_TREASURY, OWNER)),

                // try to approve allowance to the owner
                cryptoApproveAllowance()
                        .addTokenAllowance(OWNER, FUNGIBLE_TOKEN, OWNER, 100L)
                        .signedBy(DEFAULT_PAYER, OWNER)
                        .hasKnownStatus(SUCCESS),

                // try to approve allowance to the owner
                cryptoApproveAllowance()
                        .addNftAllowance(OWNER, NON_FUNGIBLE_TOKEN, OWNER, true, List.of())
                        .signedBy(DEFAULT_PAYER, OWNER)
                        .hasKnownStatus(SPENDER_ACCOUNT_SAME_AS_OWNER));
    }

    @HapiTest
    final Stream<DynamicTest> delegateAllowanceFromDeletedSpender() {
        final var SECOND_SPENDER = "secondSpender";
        return hapiTest(
                newKeyNamed(SUPPLY_KEY),
                cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                cryptoCreate(SPENDER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(SECOND_SPENDER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(NON_FUNGIBLE_TOKEN)
                        .supplyKey(SUPPLY_KEY)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .treasury(TOKEN_TREASURY)
                        .initialSupply(0),
                mintToken(NON_FUNGIBLE_TOKEN, List.of(ByteString.copyFromUtf8("a"))),
                tokenAssociate(OWNER, NON_FUNGIBLE_TOKEN),
                tokenAssociate(SPENDER, NON_FUNGIBLE_TOKEN),
                tokenAssociate(SECOND_SPENDER, NON_FUNGIBLE_TOKEN),
                cryptoTransfer(movingUnique(NON_FUNGIBLE_TOKEN, 1L).between(TOKEN_TREASURY, OWNER)),
                cryptoApproveAllowance()
                        .addNftAllowance(OWNER, NON_FUNGIBLE_TOKEN, SPENDER, true, List.of())
                        .signedByPayerAnd(OWNER),
                cryptoDelete(SPENDER),
                cryptoApproveAllowance()
                        .payingWith(DEFAULT_PAYER)
                        .addDelegatedNftAllowance(
                                OWNER, NON_FUNGIBLE_TOKEN, SECOND_SPENDER, SPENDER, false, List.of(1L))
                        .signedByPayerAnd(SPENDER)
                        .hasKnownStatus(INVALID_DELEGATING_SPENDER));
    }
}
