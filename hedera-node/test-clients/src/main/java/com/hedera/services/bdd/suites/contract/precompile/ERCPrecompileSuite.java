// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.precompile;

import static com.hedera.services.bdd.junit.TestTags.LONG_RUNNING;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AccountDetailsAsserts.accountDetailsWith;
import static com.hedera.services.bdd.spec.assertions.AssertUtils.inOrder;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.resultWith;
import static com.hedera.services.bdd.spec.assertions.ContractLogAsserts.logWith;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.keys.KeyShape.SIMPLE;
import static com.hedera.services.bdd.spec.keys.SigControl.ON;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.contractCallLocal;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountDetails;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getContractInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTokenNftInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCallWithFunctionAbi;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoApproveAllowance;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.contract.HapiParserUtil.asHeadlongAddress;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingUnique;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingUniqueWithAllowance;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.childRecordsCheck;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.TOKEN_TREASURY;
import static com.hedera.services.bdd.suites.contract.Utils.asAddress;
import static com.hedera.services.bdd.suites.contract.Utils.asHexedAddress;
import static com.hedera.services.bdd.suites.contract.Utils.asHexedSolidityAddress;
import static com.hedera.services.bdd.suites.contract.Utils.asToken;
import static com.hedera.services.bdd.suites.contract.Utils.eventSignatureOf;
import static com.hedera.services.bdd.suites.contract.Utils.getABIFor;
import static com.hedera.services.bdd.suites.contract.Utils.parsedToByteString;
import static com.hedera.services.bdd.suites.utils.contracts.BoolResult.flag;
import static com.hedera.services.bdd.suites.utils.contracts.precompile.HTSPrecompileResult.htsPrecompileResult;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.AMOUNT_EXCEEDS_ALLOWANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_TOKEN_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ALLOWANCE_OWNER_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ALLOWANCE_SPENDER_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SENDER_DOES_NOT_OWN_NFT_SERIAL_NO;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SPENDER_ACCOUNT_SAME_AS_OWNER;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SPENDER_DOES_NOT_HAVE_ALLOWANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_NOT_ASSOCIATED_TO_ACCOUNT;
import static com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;

import com.google.protobuf.ByteString;
import com.hedera.node.app.hapi.utils.contracts.ParsingConstants.FunctionType;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.spec.HapiPropertySource;
import com.hedera.services.bdd.spec.transactions.contract.HapiParserUtil;
import com.hedera.services.bdd.spec.transactions.token.TokenMovement;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.TokenID;
import com.hederahashgraph.api.proto.java.TokenSupplyType;
import com.hederahashgraph.api.proto.java.TokenType;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(LONG_RUNNING)
public class ERCPrecompileSuite {

    private static final Logger log = LogManager.getLogger(ERCPrecompileSuite.class);
    private static final long GAS_TO_OFFER = 1_000_000L;
    private static final String FUNGIBLE_TOKEN = "fungibleToken";
    private static final String NON_FUNGIBLE_TOKEN = "nonFungibleToken";
    private static final String MULTI_KEY = "purpose";
    private static final String ERC_20_CONTRACT_NAME = "erc20Contract";
    private static final String OWNER = "owner";
    private static final String ACCOUNT = "anybody";
    public static final String RECIPIENT = "recipient";
    private static final String FIRST = "FIRST";
    private static final String TOKEN_NAME = "TokenA";
    private static final ByteString FIRST_META = ByteString.copyFrom(FIRST.getBytes(StandardCharsets.UTF_8));
    private static final ByteString SECOND_META = ByteString.copyFrom(FIRST.getBytes(StandardCharsets.UTF_8));
    public static final String TRANSFER_SIG_NAME = "transferSig";
    public static final String ERC_20_CONTRACT = "ERC20Contract";
    private static final String ERC_721_CONTRACT = "ERC721Contract";
    public static final String NAME_TXN = "nameTxn";
    private static final String SYMBOL_TXN = "symbolTxn";
    private static final String TOTAL_SUPPLY_TXN = "totalSupplyTxn";
    private static final String BALANCE_OF_TXN = "balanceOfTxn";
    private static final String ALLOWANCE_TXN = "allowanceTxn";
    private static final String TRANSFER_TXN = "transferTxn";
    public static final String TRANSFER_FROM_ACCOUNT_TXN = "transferFromAccountTxn";
    private static final String BASE_APPROVE_TXN = "baseApproveTxn";
    private static final String IS_APPROVED_FOR_ALL = "outerIsApprovedForAll";
    private static final String GET_ALLOWANCE = "getAllowance";
    private static final String ALLOWANCE = "allowance";
    private static final String SYMBOL = "symbol";
    private static final String DECIMALS = "decimals";
    private static final String TOTAL_SUPPLY = "totalSupply";
    private static final String BALANCE_OF = "balanceOf";
    public static final String TRANSFER = "transfer";
    public static final String APPROVE = "approve";
    private static final String OWNER_OF = "ownerOf";
    private static final String TOKEN_URI = "tokenURI";
    private static final String TOKEN = "token";
    private static final String SPENDER = "spender";
    private static final String NF_TOKEN = "nfToken";
    public static final String TRANSFER_FROM = "transferFrom";
    private static final String ERC_20_ABI = "ERC20ABI";
    private static final String ERC_721_ABI = "ERC721ABI";
    private static final String MULTI_KEY_NAME = "multiKey";
    private static final String A_CIVILIAN = "aCivilian";
    private static final String B_CIVILIAN = "bCivilian";
    private static final String DO_TRANSFER_FROM = "doTransferFrom";
    private static final String MISSING_FROM = "MISSING_FROM";
    private static final String MISSING_TO = "MISSING_TO";
    private static final String SOME_ERC_20_SCENARIOS = "SomeERC20Scenarios";
    private static final String SOME_ERC_721_SCENARIOS = "SomeERC721Scenarios";
    private static final String OPERATOR_DOES_NOT_EXISTS = "OPERATOR_DOES_NOT_EXISTS";
    private static final String SET_APPROVAL_FOR_ALL = "outerSetApprovalForAll";
    private static final String REVOKE_SPECIFIC_APPROVAL = "revokeSpecificApproval";
    private static final String MSG_SENDER_IS_NOT_THE_SAME_AS_FROM = "MSG_SENDER_IS_NOT_THE_SAME_AS_FROM";
    private static final String MSG_SENDER_IS_THE_SAME_AS_FROM = "MSG_SENDER_IS_THE_SAME_AS_FROM";
    private static final String DO_SPECIFIC_APPROVAL = "doSpecificApproval";
    private static final String NFT_TOKEN_MINT = "nftTokenMint";
    public static final String TRANSFER_SIGNATURE = "Transfer(address,address,uint256)";
    private static final String NESTED_ERC_20_CONTRACT = "NestedERC20Contract";

    @HapiTest
    final Stream<DynamicTest> getErc20TokenName() {
        return hapiTest(
                newKeyNamed(MULTI_KEY),
                cryptoCreate(ACCOUNT).balance(100 * ONE_HUNDRED_HBARS),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(FUNGIBLE_TOKEN)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .supplyType(TokenSupplyType.INFINITE)
                        .initialSupply(5)
                        .name(TOKEN_NAME)
                        .treasury(TOKEN_TREASURY)
                        .adminKey(MULTI_KEY)
                        .supplyKey(MULTI_KEY),
                uploadInitCode(ERC_20_CONTRACT),
                contractCreate(ERC_20_CONTRACT),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCall(
                                        ERC_20_CONTRACT,
                                        "name",
                                        asHeadlongAddress(
                                                asHexedAddress(spec.registry().getTokenID(FUNGIBLE_TOKEN))))
                                .payingWith(ACCOUNT)
                                .via(NAME_TXN)
                                .gas(4_000_000)
                                .hasKnownStatus(SUCCESS))),
                childRecordsCheck(
                        NAME_TXN,
                        SUCCESS,
                        recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith()
                                        .contractCallResult(htsPrecompileResult()
                                                .forFunction(FunctionType.ERC_NAME)
                                                .withName(TOKEN_NAME)))));
    }

    @HapiTest
    final Stream<DynamicTest> getErc20TokenSymbol() {
        final var tokenSymbol = "F";
        final AtomicReference<String> tokenAddr = new AtomicReference<>();

        return hapiTest(
                newKeyNamed(MULTI_KEY),
                cryptoCreate(ACCOUNT).balance(100 * ONE_HUNDRED_HBARS),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(FUNGIBLE_TOKEN)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .supplyType(TokenSupplyType.INFINITE)
                        .initialSupply(5)
                        .symbol(tokenSymbol)
                        .treasury(TOKEN_TREASURY)
                        .adminKey(MULTI_KEY)
                        .supplyKey(MULTI_KEY)
                        .exposingCreatedIdTo(
                                id -> tokenAddr.set(asHexedSolidityAddress(HapiPropertySource.asToken(id)))),
                uploadInitCode(ERC_20_CONTRACT),
                contractCreate(ERC_20_CONTRACT),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCall(
                                        ERC_20_CONTRACT,
                                        SYMBOL,
                                        asHeadlongAddress(
                                                asHexedAddress(spec.registry().getTokenID(FUNGIBLE_TOKEN))))
                                .payingWith(ACCOUNT)
                                .via(SYMBOL_TXN)
                                .hasKnownStatus(SUCCESS)
                                .gas(GAS_TO_OFFER))),
                childRecordsCheck(
                        SYMBOL_TXN,
                        SUCCESS,
                        recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith()
                                        .contractCallResult(htsPrecompileResult()
                                                .forFunction(FunctionType.ERC_SYMBOL)
                                                .withSymbol(tokenSymbol)))),
                sourcing(() -> contractCallLocal(ERC_20_CONTRACT, SYMBOL, asHeadlongAddress(tokenAddr.get()))));
    }

    @HapiTest
    final Stream<DynamicTest> getErc20TokenDecimals() {
        final var decimals = 10;
        final var decimalsTxn = "decimalsTxn";
        final AtomicReference<String> tokenAddr = new AtomicReference<>();

        return hapiTest(
                newKeyNamed(MULTI_KEY),
                cryptoCreate(ACCOUNT).balance(100 * ONE_HUNDRED_HBARS),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(FUNGIBLE_TOKEN)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .supplyType(TokenSupplyType.INFINITE)
                        .initialSupply(5)
                        .decimals(decimals)
                        .treasury(TOKEN_TREASURY)
                        .adminKey(MULTI_KEY)
                        .supplyKey(MULTI_KEY)
                        .exposingCreatedIdTo(
                                id -> tokenAddr.set(asHexedSolidityAddress(HapiPropertySource.asToken(id)))),
                fileCreate(ERC_20_CONTRACT_NAME),
                uploadInitCode(ERC_20_CONTRACT),
                contractCreate(ERC_20_CONTRACT),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCall(
                                        ERC_20_CONTRACT,
                                        DECIMALS,
                                        asHeadlongAddress(
                                                asHexedAddress(spec.registry().getTokenID(FUNGIBLE_TOKEN))))
                                .payingWith(ACCOUNT)
                                .via(decimalsTxn)
                                .hasKnownStatus(SUCCESS)
                                .gas(GAS_TO_OFFER))),
                childRecordsCheck(
                        decimalsTxn,
                        SUCCESS,
                        recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith()
                                        .contractCallResult(htsPrecompileResult()
                                                .forFunction(FunctionType.ERC_DECIMALS)
                                                .withDecimals(decimals)))),
                sourcing(() -> contractCallLocal(ERC_20_CONTRACT, DECIMALS, asHeadlongAddress(tokenAddr.get()))));
    }

    @HapiTest
    final Stream<DynamicTest> getErc20TotalSupply() {
        final var totalSupply = 50;
        final var supplyTxn = "supplyTxn";
        final AtomicReference<String> tokenAddr = new AtomicReference<>();

        return hapiTest(
                newKeyNamed(MULTI_KEY),
                cryptoCreate(ACCOUNT).balance(100 * ONE_HUNDRED_HBARS),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(FUNGIBLE_TOKEN)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .initialSupply(totalSupply)
                        .treasury(TOKEN_TREASURY)
                        .adminKey(MULTI_KEY)
                        .supplyKey(MULTI_KEY)
                        .exposingCreatedIdTo(
                                id -> tokenAddr.set(asHexedSolidityAddress(HapiPropertySource.asToken(id)))),
                uploadInitCode(ERC_20_CONTRACT),
                contractCreate(ERC_20_CONTRACT),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCall(
                                        ERC_20_CONTRACT,
                                        TOTAL_SUPPLY,
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getTokenID(FUNGIBLE_TOKEN))))
                                .payingWith(ACCOUNT)
                                .via(supplyTxn)
                                .hasKnownStatus(SUCCESS)
                                .gas(GAS_TO_OFFER))),
                childRecordsCheck(
                        supplyTxn,
                        SUCCESS,
                        recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith()
                                        .contractCallResult(htsPrecompileResult()
                                                .forFunction(FunctionType.ERC_TOTAL_SUPPLY)
                                                .withTotalSupply(totalSupply)))),
                sourcing(() -> contractCallLocal(ERC_20_CONTRACT, DECIMALS, asHeadlongAddress(tokenAddr.get()))));
    }

    @HapiTest
    final Stream<DynamicTest> getErc20BalanceOfAccount() {
        final var balanceTxn = "balanceTxn";
        final var zeroBalanceTxn = "zBalanceTxn";
        final AtomicReference<String> tokenAddr = new AtomicReference<>();
        final AtomicReference<String> accountAddr = new AtomicReference<>();

        return hapiTest(
                newKeyNamed(MULTI_KEY),
                cryptoCreate(ACCOUNT)
                        .balance(100 * ONE_HUNDRED_HBARS)
                        .exposingCreatedIdTo(id -> accountAddr.set(asHexedSolidityAddress(id))),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(FUNGIBLE_TOKEN)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .supplyType(TokenSupplyType.INFINITE)
                        .initialSupply(5)
                        .treasury(TOKEN_TREASURY)
                        .adminKey(MULTI_KEY)
                        .supplyKey(MULTI_KEY)
                        .exposingCreatedIdTo(
                                id -> tokenAddr.set(asHexedSolidityAddress(HapiPropertySource.asToken(id)))),
                uploadInitCode(ERC_20_CONTRACT),
                contractCreate(ERC_20_CONTRACT),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCall(
                                        ERC_20_CONTRACT,
                                        BALANCE_OF,
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getTokenID(FUNGIBLE_TOKEN))),
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getAccountID(ACCOUNT))))
                                .payingWith(ACCOUNT)
                                .via(zeroBalanceTxn)
                                .hasKnownStatus(SUCCESS)
                                .gas(GAS_TO_OFFER),
                        tokenAssociate(ACCOUNT, List.of(FUNGIBLE_TOKEN)),
                        cryptoTransfer(moving(3, FUNGIBLE_TOKEN).between(TOKEN_TREASURY, ACCOUNT)),
                        contractCall(
                                        ERC_20_CONTRACT,
                                        BALANCE_OF,
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getTokenID(FUNGIBLE_TOKEN))),
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getAccountID(ACCOUNT))))
                                .payingWith(ACCOUNT)
                                .via(balanceTxn)
                                .hasKnownStatus(SUCCESS)
                                .gas(GAS_TO_OFFER))),
                /* expect 0 returned from balanceOf() if the account and token are not associated */
                childRecordsCheck(
                        zeroBalanceTxn,
                        SUCCESS,
                        recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith()
                                        .contractCallResult(htsPrecompileResult()
                                                .forFunction(FunctionType.ERC_BALANCE)
                                                .withBalance(0)))),
                childRecordsCheck(
                        balanceTxn,
                        SUCCESS,
                        recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith()
                                        .contractCallResult(htsPrecompileResult()
                                                .forFunction(FunctionType.ERC_BALANCE)
                                                .withBalance(3)))),
                sourcing(() -> contractCallLocal(
                        ERC_20_CONTRACT,
                        BALANCE_OF,
                        asHeadlongAddress(tokenAddr.get()),
                        asHeadlongAddress(accountAddr.get()))));
    }

    @HapiTest
    final Stream<DynamicTest> transferErc20Token() {
        final AtomicReference<String> tokenAddr = new AtomicReference<>();
        final AtomicReference<String> accountAddr = new AtomicReference<>();

        return hapiTest(
                newKeyNamed(MULTI_KEY),
                cryptoCreate(ACCOUNT)
                        .balance(100 * ONE_HUNDRED_HBARS)
                        .exposingCreatedIdTo(id -> accountAddr.set(asHexedSolidityAddress(id))),
                cryptoCreate(RECIPIENT),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(FUNGIBLE_TOKEN)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .initialSupply(5)
                        .treasury(TOKEN_TREASURY)
                        .adminKey(MULTI_KEY)
                        .supplyKey(MULTI_KEY)
                        .exposingCreatedIdTo(id -> tokenAddr.set(asHexedSolidityAddress(asToken(id)))),
                uploadInitCode(ERC_20_CONTRACT),
                // Refusing ethereum create conversion, because we get INVALID_SIGNATURE upon tokenAssociate,
                // since we have CONTRACT_ID key
                contractCreate(ERC_20_CONTRACT).refusingEthConversion(),
                tokenAssociate(ACCOUNT, List.of(FUNGIBLE_TOKEN)),
                tokenAssociate(RECIPIENT, List.of(FUNGIBLE_TOKEN)),
                tokenAssociate(ERC_20_CONTRACT, List.of(FUNGIBLE_TOKEN)),
                cryptoTransfer(moving(5, FUNGIBLE_TOKEN).between(TOKEN_TREASURY, ERC_20_CONTRACT)),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCall(
                                        ERC_20_CONTRACT,
                                        TRANSFER,
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getTokenID(FUNGIBLE_TOKEN))),
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getAccountID(RECIPIENT))),
                                        BigInteger.TWO)
                                .via(TRANSFER_TXN)
                                .gas(GAS_TO_OFFER))),
                getContractInfo(ERC_20_CONTRACT).saveToRegistry(ERC_20_CONTRACT),
                getAccountInfo(RECIPIENT).savingSnapshot(RECIPIENT),
                withOpContext((spec, log) -> {
                    final var sender =
                            spec.registry().getContractInfo(ERC_20_CONTRACT).getContractID();
                    final var receiver =
                            spec.registry().getAccountInfo(RECIPIENT).getAccountID();
                    final var idOfToken = String.valueOf(
                            spec.registry().getTokenID(FUNGIBLE_TOKEN).getTokenNum());
                    var txnRecord = getTxnRecord(TRANSFER_TXN)
                            .hasPriority(recordWith()
                                    .contractCallResult(resultWith()
                                            .logs(inOrder(logWith()
                                                    .contract(idOfToken)
                                                    .withTopicsInOrder(List.of(
                                                            eventSignatureOf(TRANSFER_SIGNATURE),
                                                            parsedToByteString(
                                                                    sender.getShardNum(),
                                                                    sender.getRealmNum(),
                                                                    sender.getContractNum()),
                                                            parsedToByteString(
                                                                    receiver.getShardNum(),
                                                                    receiver.getRealmNum(),
                                                                    receiver.getAccountNum())))
                                                    .longValue(2)))))
                            .andAllChildRecords();
                    allRunFor(spec, txnRecord);
                }),
                childRecordsCheck(
                        TRANSFER_TXN,
                        SUCCESS,
                        recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith()
                                        .contractCallResult(htsPrecompileResult()
                                                .forFunction(FunctionType.ERC_TRANSFER)
                                                .withErcFungibleTransferStatus(true)))),
                getAccountBalance(ERC_20_CONTRACT).hasTokenBalance(FUNGIBLE_TOKEN, 3),
                getAccountBalance(RECIPIENT).hasTokenBalance(FUNGIBLE_TOKEN, 2),
                sourcing(() -> contractCallLocal(
                                ERC_20_CONTRACT,
                                TRANSFER,
                                asHeadlongAddress(tokenAddr.get()),
                                asHeadlongAddress(accountAddr.get()),
                                BigInteger.ONE)
                        .hasAnswerOnlyPrecheck(CONTRACT_REVERT_EXECUTED)));
    }

    @HapiTest
    final Stream<DynamicTest> transferErc20TokenFailWithAccount() {
        final AtomicReference<String> tokenAddr = new AtomicReference<>();
        final AtomicReference<String> accountAddr = new AtomicReference<>();

        return hapiTest(
                newKeyNamed(MULTI_KEY),
                cryptoCreate(ACCOUNT)
                        .balance(100 * ONE_HUNDRED_HBARS)
                        .exposingCreatedIdTo(id -> accountAddr.set(asHexedSolidityAddress(id))),
                cryptoCreate(RECIPIENT),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(FUNGIBLE_TOKEN)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .initialSupply(5)
                        .treasury(TOKEN_TREASURY)
                        .adminKey(MULTI_KEY)
                        .supplyKey(MULTI_KEY)
                        .exposingCreatedIdTo(
                                id -> tokenAddr.set(asHexedSolidityAddress(HapiPropertySource.asToken(id)))),
                uploadInitCode(ERC_20_CONTRACT),
                // Refusing ethereum create conversion, because we get INVALID_SIGNATURE upon tokenAssociate,
                // since we have CONTRACT_ID key
                contractCreate(ERC_20_CONTRACT).refusingEthConversion(),
                tokenAssociate(ACCOUNT, List.of(FUNGIBLE_TOKEN)),
                tokenAssociate(RECIPIENT, List.of(FUNGIBLE_TOKEN)),
                tokenAssociate(ERC_20_CONTRACT, List.of(FUNGIBLE_TOKEN)),
                cryptoTransfer(moving(5, FUNGIBLE_TOKEN).between(TOKEN_TREASURY, ACCOUNT)),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCall(
                                        ERC_20_CONTRACT,
                                        TRANSFER,
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getAccountID(ACCOUNT))),
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getAccountID(RECIPIENT))),
                                        BigInteger.TWO)
                                .via(TRANSFER_TXN)
                                .gas(GAS_TO_OFFER)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED))),
                getContractInfo(ERC_20_CONTRACT).saveToRegistry(ERC_20_CONTRACT),
                childRecordsCheck(TRANSFER_TXN, CONTRACT_REVERT_EXECUTED));
    }

    @HapiTest
    final Stream<DynamicTest> transferErc20TokenReceiverContract() {
        final var nestedContract = NESTED_ERC_20_CONTRACT;

        return hapiTest(
                newKeyNamed(MULTI_KEY),
                cryptoCreate(ACCOUNT).balance(100 * ONE_MILLION_HBARS),
                cryptoCreate(RECIPIENT),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(FUNGIBLE_TOKEN)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .initialSupply(5)
                        .treasury(TOKEN_TREASURY)
                        .adminKey(MULTI_KEY)
                        .supplyKey(MULTI_KEY),
                uploadInitCode(ERC_20_CONTRACT, nestedContract),
                // Refusing ethereum create conversion, because we get INVALID_SIGNATURE upon tokenAssociate,
                // since we have CONTRACT_ID key
                contractCreate(ERC_20_CONTRACT).refusingEthConversion(),
                contractCreate(nestedContract).refusingEthConversion(),
                tokenAssociate(ACCOUNT, List.of(FUNGIBLE_TOKEN)),
                tokenAssociate(RECIPIENT, List.of(FUNGIBLE_TOKEN)),
                tokenAssociate(ERC_20_CONTRACT, List.of(FUNGIBLE_TOKEN)),
                tokenAssociate(nestedContract, List.of(FUNGIBLE_TOKEN)),
                cryptoTransfer(moving(5, FUNGIBLE_TOKEN).between(TOKEN_TREASURY, ERC_20_CONTRACT)),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCall(
                                        ERC_20_CONTRACT,
                                        TRANSFER,
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getTokenID(FUNGIBLE_TOKEN))),
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getContractId(nestedContract))),
                                        BigInteger.TWO)
                                .via(TRANSFER_TXN)
                                .gas(GAS_TO_OFFER)
                                .hasKnownStatus(SUCCESS))),
                getContractInfo(ERC_20_CONTRACT).saveToRegistry(ERC_20_CONTRACT),
                getContractInfo(nestedContract).saveToRegistry(nestedContract),
                withOpContext((spec, log) -> {
                    final var sender =
                            spec.registry().getContractInfo(ERC_20_CONTRACT).getContractID();
                    final var receiver =
                            spec.registry().getContractInfo(nestedContract).getContractID();

                    var txnRecord = getTxnRecord(TRANSFER_TXN)
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
                                                    .longValue(2)))))
                            .andAllChildRecords()
                            .logged();
                    allRunFor(spec, txnRecord);
                }),
                childRecordsCheck(
                        TRANSFER_TXN,
                        SUCCESS,
                        recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith()
                                        .contractCallResult(htsPrecompileResult()
                                                .forFunction(FunctionType.ERC_TRANSFER)
                                                .withErcFungibleTransferStatus(true)))),
                getAccountBalance(ERC_20_CONTRACT).hasTokenBalance(FUNGIBLE_TOKEN, 3),
                getAccountBalance(nestedContract).hasTokenBalance(FUNGIBLE_TOKEN, 2));
    }

    @HapiTest
    final Stream<DynamicTest> transferErc20TokenFromContractWithNoApproval() {
        final var transferFromOtherContractWithSignaturesTxn = "transferFromOtherContractWithSignaturesTxn";
        final var nestedContract = NESTED_ERC_20_CONTRACT;

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
                // Refusing ethereum create conversion, because we get INVALID_SIGNATURE upon tokenAssociate,
                // since we have CONTRACT_ID key
                contractCreate(ERC_20_CONTRACT).adminKey(TRANSFER_SIG_NAME).refusingEthConversion(),
                contractCreate(nestedContract).adminKey(TRANSFER_SIG_NAME).refusingEthConversion(),
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
                                        TRANSFER_FROM,
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getTokenID(FUNGIBLE_TOKEN))),
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getContractId(ERC_20_CONTRACT))),
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getContractId(nestedContract))),
                                        BigInteger.valueOf(5))
                                .via(TRANSFER_TXN)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED),
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
                                .payingWith(GENESIS)
                                .alsoSigningWithFullPrefix(TRANSFER_SIG_NAME)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED)
                                .via(transferFromOtherContractWithSignaturesTxn))),
                getContractInfo(ERC_20_CONTRACT).saveToRegistry(ERC_20_CONTRACT),
                getContractInfo(nestedContract).saveToRegistry(nestedContract),
                childRecordsCheck(
                        TRANSFER_TXN, CONTRACT_REVERT_EXECUTED, recordWith().status(SPENDER_DOES_NOT_HAVE_ALLOWANCE)),
                childRecordsCheck(
                        transferFromOtherContractWithSignaturesTxn,
                        CONTRACT_REVERT_EXECUTED,
                        recordWith().status(SPENDER_DOES_NOT_HAVE_ALLOWANCE)));
    }

    @HapiTest
    final Stream<DynamicTest> erc20Allowance() {
        return hapiTest(
                newKeyNamed(MULTI_KEY),
                cryptoCreate(OWNER).balance(100 * ONE_HUNDRED_HBARS),
                cryptoCreate(SPENDER),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(FUNGIBLE_TOKEN)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .supplyType(TokenSupplyType.FINITE)
                        .initialSupply(10L)
                        .maxSupply(1000L)
                        .treasury(TOKEN_TREASURY)
                        .adminKey(MULTI_KEY)
                        .supplyKey(MULTI_KEY),
                uploadInitCode(ERC_20_CONTRACT),
                contractCreate(ERC_20_CONTRACT),
                tokenAssociate(OWNER, FUNGIBLE_TOKEN),
                cryptoTransfer(moving(10, FUNGIBLE_TOKEN).between(TOKEN_TREASURY, OWNER)),
                cryptoApproveAllowance()
                        .payingWith(DEFAULT_PAYER)
                        .addTokenAllowance(OWNER, FUNGIBLE_TOKEN, SPENDER, 2L)
                        .via(BASE_APPROVE_TXN)
                        .logged()
                        .signedBy(DEFAULT_PAYER, OWNER)
                        .fee(ONE_HBAR),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCall(
                                        ERC_20_CONTRACT,
                                        ALLOWANCE,
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getTokenID(FUNGIBLE_TOKEN))),
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getAccountID(OWNER))),
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getAccountID(SPENDER))))
                                .payingWith(OWNER)
                                .via(ALLOWANCE_TXN)
                                .hasKnownStatus(SUCCESS))),
                getTxnRecord(ALLOWANCE_TXN).andAllChildRecords().logged(),
                childRecordsCheck(
                        ALLOWANCE_TXN,
                        SUCCESS,
                        recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith()
                                        .contractCallResult(htsPrecompileResult()
                                                .forFunction(FunctionType.ERC_ALLOWANCE)
                                                .withAllowance(2)))));
    }

    @HapiTest
    final Stream<DynamicTest> erc20Approve() {
        final var approveTxn = "approveTxn";

        return hapiTest(
                newKeyNamed(MULTI_KEY),
                cryptoCreate(OWNER).balance(100 * ONE_HUNDRED_HBARS),
                cryptoCreate(SPENDER),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(FUNGIBLE_TOKEN)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .supplyType(TokenSupplyType.FINITE)
                        .initialSupply(10L)
                        .maxSupply(1000L)
                        .treasury(TOKEN_TREASURY)
                        .adminKey(MULTI_KEY)
                        .supplyKey(MULTI_KEY),
                uploadInitCode(ERC_20_CONTRACT),
                // Refusing ethereum create conversion, because we get INVALID_SIGNATURE upon tokenAssociate,
                // since we have CONTRACT_ID key
                contractCreate(ERC_20_CONTRACT).refusingEthConversion(),
                tokenAssociate(OWNER, FUNGIBLE_TOKEN),
                tokenAssociate(ERC_20_CONTRACT, FUNGIBLE_TOKEN),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCall(
                                        ERC_20_CONTRACT,
                                        APPROVE,
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getTokenID(FUNGIBLE_TOKEN))),
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getAccountID(SPENDER))),
                                        BigInteger.valueOf(10))
                                .payingWith(OWNER)
                                .gas(4_000_000L)
                                .via(approveTxn)
                                .hasKnownStatus(SUCCESS))),
                childRecordsCheck(approveTxn, SUCCESS, recordWith().status(SUCCESS)),
                getTxnRecord(approveTxn).andAllChildRecords().logged());
    }

    @HapiTest
    final Stream<DynamicTest> getErc20TokenDecimalsFromErc721TokenFails() {
        final var invalidDecimalsTxn = "decimalsFromErc721Txn";

        return hapiTest(
                newKeyNamed(MULTI_KEY),
                cryptoCreate(ACCOUNT).balance(100 * ONE_HUNDRED_HBARS),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(NON_FUNGIBLE_TOKEN)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0)
                        .treasury(TOKEN_TREASURY)
                        .adminKey(MULTI_KEY)
                        .supplyKey(MULTI_KEY),
                mintToken(NON_FUNGIBLE_TOKEN, List.of(FIRST_META)),
                fileCreate(ERC_20_CONTRACT_NAME),
                uploadInitCode(ERC_20_CONTRACT),
                contractCreate(ERC_20_CONTRACT),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCall(
                                        ERC_20_CONTRACT,
                                        DECIMALS,
                                        asHeadlongAddress(
                                                asHexedAddress(spec.registry().getTokenID(NON_FUNGIBLE_TOKEN))))
                                .payingWith(ACCOUNT)
                                .via(invalidDecimalsTxn)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED)
                                .gas(GAS_TO_OFFER))),
                getTxnRecord(invalidDecimalsTxn).andAllChildRecords());
    }

    @HapiTest
    final Stream<DynamicTest> getErc721TokenName() {
        return hapiTest(
                newKeyNamed(MULTI_KEY),
                cryptoCreate(ACCOUNT).balance(100 * ONE_HUNDRED_HBARS),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(NON_FUNGIBLE_TOKEN)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .name(TOKEN_NAME)
                        .initialSupply(0)
                        .treasury(TOKEN_TREASURY)
                        .adminKey(MULTI_KEY)
                        .supplyKey(MULTI_KEY),
                mintToken(NON_FUNGIBLE_TOKEN, List.of(FIRST_META)),
                uploadInitCode(ERC_721_CONTRACT),
                contractCreate(ERC_721_CONTRACT),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCall(
                                        ERC_721_CONTRACT,
                                        "name",
                                        asHeadlongAddress(
                                                asHexedAddress(spec.registry().getTokenID(NON_FUNGIBLE_TOKEN))))
                                .payingWith(ACCOUNT)
                                .via(NAME_TXN)
                                .hasKnownStatus(SUCCESS)
                                .gas(GAS_TO_OFFER))),
                childRecordsCheck(
                        NAME_TXN,
                        SUCCESS,
                        recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith()
                                        .contractCallResult(htsPrecompileResult()
                                                .forFunction(FunctionType.ERC_NAME)
                                                .withName(TOKEN_NAME)))));
    }

    @HapiTest
    final Stream<DynamicTest> getErc721Symbol() {
        final var tokenSymbol = "N";

        return hapiTest(
                newKeyNamed(MULTI_KEY),
                cryptoCreate(ACCOUNT).balance(100 * ONE_HUNDRED_HBARS),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(NON_FUNGIBLE_TOKEN)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .symbol(tokenSymbol)
                        .initialSupply(0)
                        .treasury(TOKEN_TREASURY)
                        .adminKey(MULTI_KEY)
                        .supplyKey(MULTI_KEY),
                mintToken(NON_FUNGIBLE_TOKEN, List.of(FIRST_META)),
                uploadInitCode(ERC_721_CONTRACT),
                contractCreate(ERC_721_CONTRACT),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCall(
                                        ERC_721_CONTRACT,
                                        SYMBOL,
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getTokenID(NON_FUNGIBLE_TOKEN))))
                                .payingWith(ACCOUNT)
                                .via(SYMBOL_TXN)
                                .hasKnownStatus(SUCCESS)
                                .gas(GAS_TO_OFFER))),
                childRecordsCheck(
                        SYMBOL_TXN,
                        SUCCESS,
                        recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith()
                                        .contractCallResult(htsPrecompileResult()
                                                .forFunction(FunctionType.ERC_SYMBOL)
                                                .withSymbol(tokenSymbol)))));
    }

    @HapiTest
    final Stream<DynamicTest> getErc721TokenURI() {
        final var tokenURITxn = "tokenURITxn";
        final var nonExistingTokenURITxn = "nonExistingTokenURITxn";
        final var ERC721MetadataNonExistingToken = "ERC721Metadata: URI query for nonexistent token";

        return hapiTest(
                newKeyNamed(MULTI_KEY),
                cryptoCreate(ACCOUNT).balance(100 * ONE_HUNDRED_HBARS),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(NON_FUNGIBLE_TOKEN)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0)
                        .treasury(TOKEN_TREASURY)
                        .adminKey(MULTI_KEY)
                        .supplyKey(MULTI_KEY),
                mintToken(NON_FUNGIBLE_TOKEN, List.of(FIRST_META)),
                uploadInitCode(ERC_721_CONTRACT),
                contractCreate(ERC_721_CONTRACT),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCall(
                                        ERC_721_CONTRACT,
                                        TOKEN_URI,
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getTokenID(NON_FUNGIBLE_TOKEN))),
                                        BigInteger.ONE)
                                .payingWith(ACCOUNT)
                                .via(tokenURITxn)
                                .hasKnownStatus(SUCCESS)
                                .gas(GAS_TO_OFFER),
                        contractCall(
                                        ERC_721_CONTRACT,
                                        TOKEN_URI,
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getTokenID(NON_FUNGIBLE_TOKEN))),
                                        BigInteger.TWO)
                                .payingWith(ACCOUNT)
                                .via(nonExistingTokenURITxn)
                                .hasKnownStatus(SUCCESS)
                                .gas(GAS_TO_OFFER))),
                childRecordsCheck(
                        tokenURITxn,
                        SUCCESS,
                        recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith()
                                        .contractCallResult(htsPrecompileResult()
                                                .forFunction(FunctionType.ERC_TOKEN_URI)
                                                .withTokenUri(FIRST)))),
                childRecordsCheck(
                        nonExistingTokenURITxn,
                        SUCCESS,
                        recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith()
                                        .contractCallResult(htsPrecompileResult()
                                                .forFunction(FunctionType.ERC_TOKEN_URI)
                                                .withTokenUri(ERC721MetadataNonExistingToken)))));
    }

    @HapiTest
    final Stream<DynamicTest> getErc721TotalSupply() {
        return hapiTest(
                newKeyNamed(MULTI_KEY),
                cryptoCreate(ACCOUNT).balance(100 * ONE_HUNDRED_HBARS),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(NON_FUNGIBLE_TOKEN)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0)
                        .treasury(TOKEN_TREASURY)
                        .adminKey(MULTI_KEY)
                        .supplyKey(MULTI_KEY),
                mintToken(NON_FUNGIBLE_TOKEN, List.of(FIRST_META)),
                uploadInitCode(ERC_721_CONTRACT),
                contractCreate(ERC_721_CONTRACT),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCall(
                                        ERC_721_CONTRACT,
                                        TOTAL_SUPPLY,
                                        asHeadlongAddress(
                                                asHexedAddress(spec.registry().getTokenID(NON_FUNGIBLE_TOKEN))))
                                .payingWith(ACCOUNT)
                                .via(TOTAL_SUPPLY_TXN)
                                .hasKnownStatus(SUCCESS)
                                .gas(GAS_TO_OFFER))),
                childRecordsCheck(
                        TOTAL_SUPPLY_TXN,
                        SUCCESS,
                        recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith()
                                        .contractCallResult(htsPrecompileResult()
                                                .forFunction(FunctionType.ERC_TOTAL_SUPPLY)
                                                .withTotalSupply(1)))));
    }

    @HapiTest
    final Stream<DynamicTest> getErc721BalanceOf() {
        final var zeroBalanceOfTxn = "zbalanceOfTxn";

        return hapiTest(
                newKeyNamed(MULTI_KEY),
                cryptoCreate(OWNER).balance(100 * ONE_HUNDRED_HBARS),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(NON_FUNGIBLE_TOKEN)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0)
                        .treasury(TOKEN_TREASURY)
                        .adminKey(MULTI_KEY)
                        .supplyKey(MULTI_KEY),
                mintToken(NON_FUNGIBLE_TOKEN, List.of(FIRST_META)),
                uploadInitCode(ERC_721_CONTRACT),
                contractCreate(ERC_721_CONTRACT),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCall(
                                        ERC_721_CONTRACT,
                                        BALANCE_OF,
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getTokenID(NON_FUNGIBLE_TOKEN))),
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getAccountID(OWNER))))
                                .payingWith(OWNER)
                                .via(zeroBalanceOfTxn)
                                .hasKnownStatus(SUCCESS)
                                .gas(GAS_TO_OFFER),
                        tokenAssociate(OWNER, List.of(NON_FUNGIBLE_TOKEN)),
                        cryptoTransfer(movingUnique(NON_FUNGIBLE_TOKEN, 1).between(TOKEN_TREASURY, OWNER)),
                        contractCall(
                                        ERC_721_CONTRACT,
                                        BALANCE_OF,
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getTokenID(NON_FUNGIBLE_TOKEN))),
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getAccountID(OWNER))))
                                .payingWith(OWNER)
                                .via(BALANCE_OF_TXN)
                                .hasKnownStatus(SUCCESS)
                                .gas(GAS_TO_OFFER))),
                /* expect 0 returned from balanceOf() if the account and token are not associated */
                childRecordsCheck(
                        zeroBalanceOfTxn,
                        SUCCESS,
                        recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith()
                                        .contractCallResult(htsPrecompileResult()
                                                .forFunction(FunctionType.ERC_BALANCE)
                                                .withBalance(0)))),
                childRecordsCheck(
                        BALANCE_OF_TXN,
                        SUCCESS,
                        recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith()
                                        .contractCallResult(htsPrecompileResult()
                                                .forFunction(FunctionType.ERC_BALANCE)
                                                .withBalance(1)))));
    }

    @HapiTest
    final Stream<DynamicTest> getErc721OwnerOf() {
        final var ownerOfTxn = "ownerOfTxn";
        final AtomicReference<byte[]> ownerAddr = new AtomicReference<>();
        final AtomicReference<String> tokenAddr = new AtomicReference<>();

        return hapiTest(
                newKeyNamed(MULTI_KEY),
                cryptoCreate(OWNER).balance(100 * ONE_HUNDRED_HBARS),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(NON_FUNGIBLE_TOKEN)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0)
                        .treasury(TOKEN_TREASURY)
                        .adminKey(MULTI_KEY)
                        .supplyKey(MULTI_KEY)
                        .exposingCreatedIdTo(
                                id -> tokenAddr.set(asHexedSolidityAddress(HapiPropertySource.asToken(id)))),
                mintToken(NON_FUNGIBLE_TOKEN, List.of(FIRST_META)),
                tokenAssociate(OWNER, List.of(NON_FUNGIBLE_TOKEN)),
                cryptoTransfer(movingUnique(NON_FUNGIBLE_TOKEN, 1).between(TOKEN_TREASURY, OWNER)),
                uploadInitCode(ERC_721_CONTRACT),
                contractCreate(ERC_721_CONTRACT),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCall(
                                        ERC_721_CONTRACT,
                                        OWNER_OF,
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getTokenID(NON_FUNGIBLE_TOKEN))),
                                        BigInteger.ONE)
                                .payingWith(OWNER)
                                .via(ownerOfTxn)
                                .hasKnownStatus(SUCCESS)
                                .gas(GAS_TO_OFFER))),
                withOpContext((spec, opLog) -> {
                    ownerAddr.set(asAddress(spec.registry().getAccountID(OWNER)));
                    allRunFor(
                            spec,
                            childRecordsCheck(
                                    ownerOfTxn,
                                    SUCCESS,
                                    recordWith()
                                            .status(SUCCESS)
                                            .contractCallResult(resultWith()
                                                    .contractCallResult(htsPrecompileResult()
                                                            .forFunction(FunctionType.ERC_OWNER)
                                                            .withOwner(ownerAddr.get())))));
                }),
                sourcing(() -> contractCallLocal(
                                ERC_721_CONTRACT, OWNER_OF, asHeadlongAddress(tokenAddr.get()), BigInteger.ONE)
                        .payingWith(OWNER)
                        .gas(GAS_TO_OFFER)));
    }

    // Expects revert
    @HapiTest
    final Stream<DynamicTest> getErc721TokenURIFromErc20TokenFails() {
        final var invalidTokenURITxn = "tokenURITxnFromErc20";

        return hapiTest(
                newKeyNamed(MULTI_KEY),
                cryptoCreate(ACCOUNT).balance(100 * ONE_HUNDRED_HBARS),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(FUNGIBLE_TOKEN)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .initialSupply(10)
                        .treasury(TOKEN_TREASURY)
                        .adminKey(MULTI_KEY)
                        .supplyKey(MULTI_KEY),
                uploadInitCode(ERC_721_CONTRACT),
                contractCreate(ERC_721_CONTRACT),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCall(
                                        ERC_721_CONTRACT,
                                        TOKEN_URI,
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getTokenID(FUNGIBLE_TOKEN))),
                                        BigInteger.ONE)
                                .payingWith(ACCOUNT)
                                .via(invalidTokenURITxn)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED)
                                .gas(GAS_TO_OFFER))),
                getTxnRecord(invalidTokenURITxn).andAllChildRecords());
    }

    @HapiTest
    final Stream<DynamicTest> getErc721OwnerOfFromErc20TokenFails() {
        final var invalidOwnerOfTxn = "ownerOfTxnFromErc20Token";

        return hapiTest(
                newKeyNamed(MULTI_KEY),
                cryptoCreate(OWNER).balance(100 * ONE_HUNDRED_HBARS),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(FUNGIBLE_TOKEN)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .initialSupply(10)
                        .treasury(TOKEN_TREASURY)
                        .adminKey(MULTI_KEY)
                        .supplyKey(MULTI_KEY),
                tokenAssociate(OWNER, List.of(FUNGIBLE_TOKEN)),
                cryptoTransfer(moving(3, FUNGIBLE_TOKEN).between(TOKEN_TREASURY, OWNER)),
                uploadInitCode(ERC_721_CONTRACT),
                contractCreate(ERC_721_CONTRACT),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCall(
                                        ERC_721_CONTRACT,
                                        OWNER_OF,
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getTokenID(FUNGIBLE_TOKEN))),
                                        BigInteger.ONE)
                                .payingWith(OWNER)
                                .via(invalidOwnerOfTxn)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED)
                                .gas(GAS_TO_OFFER))),
                getTxnRecord(invalidOwnerOfTxn).andAllChildRecords());
    }

    @HapiTest
    final Stream<DynamicTest> directCallsWorkForErc20() {
        final AtomicReference<String> tokenNum = new AtomicReference<>();

        final var tokenSymbol = "FDFGF";
        final var tokenDecimals = 10;
        final var tokenTotalSupply = 5;
        final var tokenTransferAmount = 3;

        final var decimalsTxn = "decimalsTxn";

        return hapiTest(
                newKeyNamed(MULTI_KEY),
                cryptoCreate(ACCOUNT).balance(100 * ONE_HUNDRED_HBARS),
                cryptoCreate(RECIPIENT).balance(100 * ONE_HUNDRED_HBARS),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(FUNGIBLE_TOKEN)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .supplyType(TokenSupplyType.INFINITE)
                        .initialSupply(tokenTotalSupply)
                        .name(TOKEN_NAME)
                        .symbol(tokenSymbol)
                        .decimals(tokenDecimals)
                        .treasury(TOKEN_TREASURY)
                        .adminKey(MULTI_KEY)
                        .supplyKey(MULTI_KEY)
                        .exposingCreatedIdTo(tokenNum::set),
                tokenAssociate(ACCOUNT, FUNGIBLE_TOKEN),
                tokenAssociate(RECIPIENT, FUNGIBLE_TOKEN),
                cryptoTransfer(moving(tokenTransferAmount, FUNGIBLE_TOKEN).between(TOKEN_TREASURY, ACCOUNT)),
                withOpContext((spec, ignore) -> {
                    var tokenAddress = asHexedSolidityAddress(asToken(tokenNum.get()));
                    allRunFor(
                            spec,
                            contractCallWithFunctionAbi(
                                            tokenAddress,
                                            getABIFor(
                                                    com.hedera.services.bdd.suites.contract.Utils.FunctionType.FUNCTION,
                                                    "name",
                                                    ERC_20_ABI))
                                    .via(NAME_TXN),
                            contractCallWithFunctionAbi(
                                            tokenAddress,
                                            getABIFor(
                                                    com.hedera.services.bdd.suites.contract.Utils.FunctionType.FUNCTION,
                                                    SYMBOL,
                                                    ERC_20_ABI))
                                    .via(SYMBOL_TXN),
                            contractCallWithFunctionAbi(
                                            tokenAddress,
                                            getABIFor(
                                                    com.hedera.services.bdd.suites.contract.Utils.FunctionType.FUNCTION,
                                                    DECIMALS,
                                                    ERC_20_ABI))
                                    .via(decimalsTxn),
                            contractCallWithFunctionAbi(
                                            tokenAddress,
                                            getABIFor(
                                                    com.hedera.services.bdd.suites.contract.Utils.FunctionType.FUNCTION,
                                                    TOTAL_SUPPLY,
                                                    ERC_20_ABI))
                                    .via(TOTAL_SUPPLY_TXN),
                            contractCallWithFunctionAbi(
                                            tokenAddress,
                                            getABIFor(
                                                    com.hedera.services.bdd.suites.contract.Utils.FunctionType.FUNCTION,
                                                    BALANCE_OF,
                                                    ERC_20_ABI),
                                            asHeadlongAddress(asHexedSolidityAddress(
                                                    spec.registry().getAccountID(ACCOUNT))))
                                    .via(BALANCE_OF_TXN),
                            contractCallWithFunctionAbi(
                                            tokenAddress,
                                            getABIFor(
                                                    com.hedera.services.bdd.suites.contract.Utils.FunctionType.FUNCTION,
                                                    TRANSFER,
                                                    ERC_20_ABI),
                                            asHeadlongAddress(asHexedSolidityAddress(
                                                    spec.registry().getAccountID(RECIPIENT))),
                                            BigInteger.valueOf(tokenTransferAmount))
                                    .via(TRANSFER_TXN)
                                    /* Don't run with Ethereum calls, since txn payer
                                     * keys are revoked in Ethereum transactions and sender is the wrapped
                                     * ethereum txn sender . Analogous test with appropriate setup is present in EthereumSuite  */
                                    .refusingEthConversion()
                                    .payingWith(ACCOUNT));
                }),
                withOpContext((spec, ignore) -> allRunFor(
                        spec,
                        childRecordsCheck(
                                NAME_TXN,
                                SUCCESS,
                                recordWith()
                                        .status(SUCCESS)
                                        .contractCallResult(resultWith()
                                                .contractCallResult(htsPrecompileResult()
                                                        .forFunction(FunctionType.ERC_NAME)
                                                        .withName(TOKEN_NAME)))),
                        childRecordsCheck(
                                SYMBOL_TXN,
                                SUCCESS,
                                recordWith()
                                        .status(SUCCESS)
                                        .contractCallResult(resultWith()
                                                .contractCallResult(htsPrecompileResult()
                                                        .forFunction(FunctionType.ERC_SYMBOL)
                                                        .withSymbol(tokenSymbol)))),
                        childRecordsCheck(
                                decimalsTxn,
                                SUCCESS,
                                recordWith()
                                        .status(SUCCESS)
                                        .contractCallResult(resultWith()
                                                .contractCallResult(htsPrecompileResult()
                                                        .forFunction(FunctionType.ERC_DECIMALS)
                                                        .withDecimals(tokenDecimals)))),
                        childRecordsCheck(
                                TOTAL_SUPPLY_TXN,
                                SUCCESS,
                                recordWith()
                                        .status(SUCCESS)
                                        .contractCallResult(resultWith()
                                                .contractCallResult(htsPrecompileResult()
                                                        .forFunction(FunctionType.ERC_TOTAL_SUPPLY)
                                                        .withTotalSupply(tokenTotalSupply)))),
                        childRecordsCheck(
                                BALANCE_OF_TXN,
                                SUCCESS,
                                recordWith()
                                        .status(SUCCESS)
                                        .contractCallResult(resultWith()
                                                .contractCallResult(htsPrecompileResult()
                                                        .forFunction(FunctionType.ERC_BALANCE)
                                                        .withBalance(tokenTransferAmount)))),
                        childRecordsCheck(
                                TRANSFER_TXN,
                                SUCCESS,
                                recordWith()
                                        .status(SUCCESS)
                                        .contractCallResult(resultWith()
                                                .contractCallResult(htsPrecompileResult()
                                                        .forFunction(FunctionType.ERC_TRANSFER)
                                                        .withErcFungibleTransferStatus(true)))))));
    }

    @HapiTest
    final Stream<DynamicTest> someErc721NegativeTransferFromScenariosPass() {
        final AtomicReference<String> tokenMirrorAddr = new AtomicReference<>();
        final AtomicReference<String> contractMirrorAddr = new AtomicReference<>();
        final AtomicReference<String> aCivilianMirrorAddr = new AtomicReference<>();
        final AtomicReference<String> bCivilianMirrorAddr = new AtomicReference<>();
        final AtomicReference<String> zCivilianMirrorAddr = new AtomicReference<>();

        return hapiTest(
                newKeyNamed(MULTI_KEY_NAME),
                cryptoCreate(A_CIVILIAN).exposingCreatedIdTo(id -> aCivilianMirrorAddr.set(asHexedSolidityAddress(id))),
                cryptoCreate(B_CIVILIAN).exposingCreatedIdTo(id -> bCivilianMirrorAddr.set(asHexedSolidityAddress(id))),
                uploadInitCode(SOME_ERC_721_SCENARIOS),
                contractCreate(SOME_ERC_721_SCENARIOS)
                        .adminKey(MULTI_KEY_NAME)
                        // Refusing ethereum create conversion, because we get INVALID_SIGNATURE upon
                        // tokenAssociate,
                        // since we have CONTRACT_ID key
                        .refusingEthConversion(),
                tokenCreate(NF_TOKEN)
                        .supplyKey(MULTI_KEY_NAME)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .treasury(SOME_ERC_721_SCENARIOS)
                        .initialSupply(0)
                        .exposingCreatedIdTo(idLit ->
                                tokenMirrorAddr.set(asHexedSolidityAddress(HapiPropertySource.asToken(idLit)))),
                mintToken(NF_TOKEN, List.of(ByteString.copyFromUtf8("I"))),
                tokenAssociate(A_CIVILIAN, NF_TOKEN),
                tokenAssociate(B_CIVILIAN, NF_TOKEN),
                withOpContext((spec, opLog) -> {
                    zCivilianMirrorAddr.set(asHexedSolidityAddress(
                            AccountID.newBuilder().setAccountNum(666_666_666L).build()));
                    contractMirrorAddr.set(
                            asHexedSolidityAddress(spec.registry().getAccountID(SOME_ERC_721_SCENARIOS)));
                }),
                // --- Negative cases for transfer ---
                // * Can't transfer a non-existent serial number
                sourcing(() -> contractCall(
                                SOME_ERC_721_SCENARIOS,
                                "iMustOwnAfterReceiving",
                                asHeadlongAddress(tokenMirrorAddr.get()),
                                BigInteger.valueOf(5))
                        .payingWith(B_CIVILIAN)
                        .via("D")
                        .hasKnownStatus(CONTRACT_REVERT_EXECUTED)),
                // * Can't transfer with missing "from"
                sourcing(() -> contractCall(
                                SOME_ERC_721_SCENARIOS,
                                TRANSFER_FROM,
                                asHeadlongAddress(tokenMirrorAddr.get()),
                                asHeadlongAddress(zCivilianMirrorAddr.get()),
                                asHeadlongAddress(bCivilianMirrorAddr.get()),
                                BigInteger.ONE)
                        .payingWith(GENESIS)
                        .via(MISSING_FROM)
                        .hasKnownStatus(CONTRACT_REVERT_EXECUTED)),
                sourcing(() -> contractCall(
                                SOME_ERC_721_SCENARIOS,
                                TRANSFER_FROM,
                                asHeadlongAddress(tokenMirrorAddr.get()),
                                asHeadlongAddress(contractMirrorAddr.get()),
                                asHeadlongAddress(bCivilianMirrorAddr.get()),
                                BigInteger.ONE)
                        .payingWith(GENESIS)
                        .via(MSG_SENDER_IS_THE_SAME_AS_FROM)),
                cryptoTransfer(movingUnique(NF_TOKEN, 1L).between(B_CIVILIAN, A_CIVILIAN)),
                sourcing(() -> contractCall(
                                SOME_ERC_721_SCENARIOS,
                                TRANSFER_FROM,
                                asHeadlongAddress(tokenMirrorAddr.get()),
                                asHeadlongAddress(aCivilianMirrorAddr.get()),
                                asHeadlongAddress(bCivilianMirrorAddr.get()),
                                BigInteger.ONE)
                        .payingWith(GENESIS)
                        .via(MSG_SENDER_IS_NOT_THE_SAME_AS_FROM)
                        .hasKnownStatus(CONTRACT_REVERT_EXECUTED)),
                cryptoApproveAllowance()
                        .payingWith(A_CIVILIAN)
                        .addNftAllowance(A_CIVILIAN, NF_TOKEN, SOME_ERC_721_SCENARIOS, false, List.of(1L))
                        .signedBy(DEFAULT_PAYER, A_CIVILIAN)
                        .fee(ONE_HBAR),
                sourcing(() -> contractCall(
                                SOME_ERC_721_SCENARIOS,
                                TRANSFER_FROM,
                                asHeadlongAddress(tokenMirrorAddr.get()),
                                asHeadlongAddress(contractMirrorAddr.get()),
                                asHeadlongAddress(bCivilianMirrorAddr.get()),
                                BigInteger.ONE)
                        .payingWith(GENESIS)
                        .via("SERIAL_NOT_OWNED_BY_FROM")
                        .hasKnownStatus(CONTRACT_REVERT_EXECUTED)),
                childRecordsCheck(
                        MISSING_FROM, CONTRACT_REVERT_EXECUTED, recordWith().status(INVALID_ACCOUNT_ID)),
                childRecordsCheck(
                        "SERIAL_NOT_OWNED_BY_FROM",
                        CONTRACT_REVERT_EXECUTED,
                        recordWith().status(SENDER_DOES_NOT_OWN_NFT_SERIAL_NO)),
                childRecordsCheck(
                        MSG_SENDER_IS_THE_SAME_AS_FROM, SUCCESS, recordWith().status(SUCCESS)),
                childRecordsCheck(
                        MSG_SENDER_IS_NOT_THE_SAME_AS_FROM,
                        CONTRACT_REVERT_EXECUTED,
                        recordWith().status(SPENDER_DOES_NOT_HAVE_ALLOWANCE)));
    }

    @HapiTest
    final Stream<DynamicTest> someErc721ApproveAndRemoveScenariosPass() {
        final AtomicReference<String> tokenMirrorAddr = new AtomicReference<>();
        final AtomicReference<String> aCivilianMirrorAddr = new AtomicReference<>();
        final AtomicReference<String> bCivilianMirrorAddr = new AtomicReference<>();
        final AtomicReference<String> zCivilianMirrorAddr = new AtomicReference<>();

        return hapiTest(
                newKeyNamed(MULTI_KEY_NAME),
                cryptoCreate(A_CIVILIAN).exposingCreatedIdTo(id -> aCivilianMirrorAddr.set(asHexedSolidityAddress(id))),
                cryptoCreate(B_CIVILIAN).exposingCreatedIdTo(id -> bCivilianMirrorAddr.set(asHexedSolidityAddress(id))),
                uploadInitCode(SOME_ERC_721_SCENARIOS),
                contractCreate(SOME_ERC_721_SCENARIOS)
                        .adminKey(MULTI_KEY_NAME)
                        // Refusing ethereum create conversion, because we get INVALID_SIGNATURE upon
                        // tokenAssociate,
                        // since we have CONTRACT_ID key
                        .refusingEthConversion(),
                tokenCreate(NF_TOKEN)
                        .supplyKey(MULTI_KEY_NAME)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .treasury(SOME_ERC_721_SCENARIOS)
                        .initialSupply(0)
                        .exposingCreatedIdTo(idLit ->
                                tokenMirrorAddr.set(asHexedSolidityAddress(HapiPropertySource.asToken(idLit)))),
                mintToken(
                        NF_TOKEN,
                        List.of(
                                // 1
                                ByteString.copyFromUtf8("I"),
                                // 2
                                ByteString.copyFromUtf8("turn"),
                                // 3
                                ByteString.copyFromUtf8("the"),
                                // 4
                                ByteString.copyFromUtf8("page"),
                                // 5
                                ByteString.copyFromUtf8("and"),
                                // 6
                                ByteString.copyFromUtf8("read"),
                                // 7
                                ByteString.copyFromUtf8("I dream of silent verses"))),
                tokenAssociate(A_CIVILIAN, NF_TOKEN),
                tokenAssociate(B_CIVILIAN, NF_TOKEN),
                withOpContext((spec, opLog) -> zCivilianMirrorAddr.set(asHexedSolidityAddress(spec, 666_666_666L))),
                // --- Negative cases for approve ---
                // * Can't approve a non-existent serial number
                sourcing(() -> contractCall(
                                SOME_ERC_721_SCENARIOS,
                                DO_SPECIFIC_APPROVAL,
                                asHeadlongAddress(tokenMirrorAddr.get()),
                                asHeadlongAddress(aCivilianMirrorAddr.get()),
                                BigInteger.valueOf(666))
                        .via("MISSING_SERIAL_NO")
                        .gas(1_000_000)
                        .hasKnownStatus(CONTRACT_REVERT_EXECUTED)),
                // * Can't approve a non-existent spender
                sourcing(() -> contractCall(
                                SOME_ERC_721_SCENARIOS,
                                DO_SPECIFIC_APPROVAL,
                                asHeadlongAddress(tokenMirrorAddr.get()),
                                asHeadlongAddress(zCivilianMirrorAddr.get()),
                                BigInteger.valueOf(5))
                        .via(MISSING_TO)
                        .gas(1_000_000)
                        .hasKnownStatus(CONTRACT_REVERT_EXECUTED)),
                getTokenNftInfo(NF_TOKEN, 5L).logged(),
                childRecordsCheck(
                        MISSING_TO,
                        CONTRACT_REVERT_EXECUTED,
                        recordWith()
                                .status(INVALID_ALLOWANCE_SPENDER_ID)
                                .contractCallResult(resultWith()
                                        .contractCallResult(
                                                htsPrecompileResult().withStatus(INVALID_ALLOWANCE_SPENDER_ID)))),
                // * Can't approve if msg.sender != owner and not an operator
                cryptoTransfer(movingUnique(NF_TOKEN, 1L, 2L).between(SOME_ERC_721_SCENARIOS, A_CIVILIAN)),
                cryptoTransfer(movingUnique(NF_TOKEN, 3L, 4L).between(SOME_ERC_721_SCENARIOS, B_CIVILIAN)),
                getTokenNftInfo(NF_TOKEN, 1L).hasAccountID(A_CIVILIAN),
                getTokenNftInfo(NF_TOKEN, 2L).hasAccountID(A_CIVILIAN),
                sourcing(() -> contractCall(
                                SOME_ERC_721_SCENARIOS,
                                DO_SPECIFIC_APPROVAL,
                                asHeadlongAddress(tokenMirrorAddr.get()),
                                asHeadlongAddress(aCivilianMirrorAddr.get()),
                                BigInteger.valueOf(3))
                        .via("NOT_AN_OPERATOR")
                        .gas(1_000_000)
                        .hasKnownStatus(CONTRACT_REVERT_EXECUTED)),
                // * Can't revoke if not owner or approvedForAll
                sourcing(() -> contractCall(
                                SOME_ERC_721_SCENARIOS,
                                REVOKE_SPECIFIC_APPROVAL,
                                asHeadlongAddress(tokenMirrorAddr.get()),
                                BigInteger.ONE)
                        .via("MISSING_REVOKE")
                        .gas(1_000_000)
                        .hasKnownStatus(CONTRACT_REVERT_EXECUTED)),
                cryptoApproveAllowance()
                        .payingWith(B_CIVILIAN)
                        .addNftAllowance(B_CIVILIAN, NF_TOKEN, SOME_ERC_721_SCENARIOS, false, List.of(3L))
                        .signedBy(DEFAULT_PAYER, B_CIVILIAN)
                        .fee(ONE_HBAR),
                // * Still can't approve if msg.sender != owner and not an operator
                sourcing(() -> contractCall(
                                SOME_ERC_721_SCENARIOS,
                                DO_SPECIFIC_APPROVAL,
                                asHeadlongAddress(tokenMirrorAddr.get()),
                                asHeadlongAddress(aCivilianMirrorAddr.get()),
                                BigInteger.valueOf(3))
                        .via("E")
                        .gas(1_000_000)
                        .hasKnownStatus(CONTRACT_REVERT_EXECUTED)),
                // --- Positive cases for approve ---
                // * owner == msg.sender can approve
                sourcing(() -> contractCall(
                                SOME_ERC_721_SCENARIOS,
                                DO_SPECIFIC_APPROVAL,
                                asHeadlongAddress(tokenMirrorAddr.get()),
                                asHeadlongAddress(bCivilianMirrorAddr.get()),
                                BigInteger.valueOf(6))
                        .via("EXTANT_TO")
                        .gas(1_000_000)),
                getTokenNftInfo(NF_TOKEN, 6L).hasSpenderID(B_CIVILIAN),
                // Approve the contract as an operator of aCivilian's NFTs
                cryptoApproveAllowance()
                        .payingWith(A_CIVILIAN)
                        .addNftAllowance(A_CIVILIAN, NF_TOKEN, SOME_ERC_721_SCENARIOS, true, List.of())
                        .signedBy(DEFAULT_PAYER, A_CIVILIAN)
                        .fee(ONE_HBAR),
                sourcing(() -> contractCall(
                                SOME_ERC_721_SCENARIOS,
                                REVOKE_SPECIFIC_APPROVAL,
                                asHeadlongAddress(tokenMirrorAddr.get()),
                                BigInteger.ONE)
                        .via("B")
                        .gas(1_000_000)),
                // These should work because the contract is an operator for aCivilian
                sourcing(() -> contractCall(
                                SOME_ERC_721_SCENARIOS,
                                DO_SPECIFIC_APPROVAL,
                                asHeadlongAddress(tokenMirrorAddr.get()),
                                asHeadlongAddress(bCivilianMirrorAddr.get()),
                                BigInteger.TWO)
                        .via("C")
                        .gas(1_000_000)),
                sourcing(() -> contractCall(
                                SOME_ERC_721_SCENARIOS,
                                "iMustOwnAfterReceiving",
                                asHeadlongAddress(tokenMirrorAddr.get()),
                                BigInteger.valueOf(5))
                        .payingWith(B_CIVILIAN)
                        /* Don't run with Ethereum calls, since txn payer
                         * keys are revoked in Ethereum transactions and sender is the wrapped
                         * ethereum txn sender . Analogous test with appropriate setup is present in EthereumSuite  */
                        .refusingEthConversion()
                        .via("D")),
                getTxnRecord("D").andAllChildRecords(),
                // Now make contract operator for bCivilian, approve aCivilian, have it grab
                // serial number 3
                cryptoApproveAllowance()
                        .payingWith(B_CIVILIAN)
                        .addNftAllowance(B_CIVILIAN, NF_TOKEN, SOME_ERC_721_SCENARIOS, true, List.of())
                        .signedBy(DEFAULT_PAYER, B_CIVILIAN)
                        .fee(ONE_HBAR),
                sourcing(() -> contractCall(
                                SOME_ERC_721_SCENARIOS,
                                DO_SPECIFIC_APPROVAL,
                                asHeadlongAddress(tokenMirrorAddr.get()),
                                asHeadlongAddress(aCivilianMirrorAddr.get()),
                                BigInteger.valueOf(3))
                        .gas(1_000_000)),
                cryptoTransfer(movingUniqueWithAllowance(NF_TOKEN, 3L).between(B_CIVILIAN, A_CIVILIAN))
                        .payingWith(A_CIVILIAN)
                        .fee(ONE_HBAR),
                getTokenNftInfo(NF_TOKEN, 3L).hasAccountID(A_CIVILIAN),
                cryptoApproveAllowance()
                        .payingWith(B_CIVILIAN)
                        .addNftAllowance(B_CIVILIAN, NF_TOKEN, A_CIVILIAN, false, List.of(5L))
                        .signedBy(DEFAULT_PAYER, B_CIVILIAN)
                        .fee(ONE_HBAR),
                getTokenNftInfo(NF_TOKEN, 5L).hasAccountID(B_CIVILIAN).hasSpenderID(A_CIVILIAN),
                // * Because contract is operator for bCivilian, it can revoke aCivilian as
                // spender for 5L
                sourcing(() -> contractCall(
                                SOME_ERC_721_SCENARIOS,
                                REVOKE_SPECIFIC_APPROVAL,
                                asHeadlongAddress(tokenMirrorAddr.get()),
                                BigInteger.valueOf(5))
                        .gas(1_000_000)),
                getTokenNftInfo(NF_TOKEN, 5L).hasAccountID(B_CIVILIAN).hasNoSpender());
    }

    @HapiTest
    final Stream<DynamicTest> someErc20ApproveAllowanceScenariosPass() {
        final AtomicReference<String> tokenMirrorAddr = new AtomicReference<>();
        final AtomicReference<String> contractMirrorAddr = new AtomicReference<>();
        final AtomicReference<String> aCivilianMirrorAddr = new AtomicReference<>();
        final AtomicReference<String> bCivilianMirrorAddr = new AtomicReference<>();
        final AtomicReference<String> zCivilianMirrorAddr = new AtomicReference<>();

        return hapiTest(
                newKeyNamed(MULTI_KEY_NAME),
                cryptoCreate(A_CIVILIAN).exposingCreatedIdTo(id -> aCivilianMirrorAddr.set(asHexedSolidityAddress(id))),
                cryptoCreate(B_CIVILIAN).exposingCreatedIdTo(id -> bCivilianMirrorAddr.set(asHexedSolidityAddress(id))),
                uploadInitCode(SOME_ERC_20_SCENARIOS),
                contractCreate(SOME_ERC_20_SCENARIOS)
                        .adminKey(MULTI_KEY_NAME)
                        // Refusing ethereum create conversion, because we get INVALID_SIGNATURE upon
                        // tokenAssociate,
                        // since we have CONTRACT_ID key
                        .refusingEthConversion(),
                tokenCreate(TOKEN)
                        .supplyKey(MULTI_KEY_NAME)
                        .tokenType(FUNGIBLE_COMMON)
                        .treasury(B_CIVILIAN)
                        .initialSupply(10)
                        .exposingCreatedIdTo(idLit ->
                                tokenMirrorAddr.set(asHexedSolidityAddress(HapiPropertySource.asToken(idLit)))),
                withOpContext((spec, opLog) -> {
                    zCivilianMirrorAddr.set(asHexedSolidityAddress(
                            AccountID.newBuilder().setAccountNum(666_666_666L).build()));
                    contractMirrorAddr.set(
                            asHexedSolidityAddress(spec.registry().getAccountID(SOME_ERC_20_SCENARIOS)));
                }),
                sourcing(() -> contractCall(
                                SOME_ERC_20_SCENARIOS,
                                DO_SPECIFIC_APPROVAL,
                                asHeadlongAddress(tokenMirrorAddr.get()),
                                asHeadlongAddress(aCivilianMirrorAddr.get()),
                                BigInteger.ZERO)
                        .via("ACCOUNT_NOT_ASSOCIATED_TXN")
                        .gas(1_000_000)
                        .hasKnownStatus(CONTRACT_REVERT_EXECUTED)),
                tokenAssociate(SOME_ERC_20_SCENARIOS, TOKEN),
                sourcing(() -> contractCall(
                                SOME_ERC_20_SCENARIOS,
                                DO_SPECIFIC_APPROVAL,
                                asHeadlongAddress(tokenMirrorAddr.get()),
                                asHeadlongAddress(zCivilianMirrorAddr.get()),
                                BigInteger.valueOf(5))
                        .via(MISSING_TO)
                        .gas(1_000_000)
                        .hasKnownStatus(CONTRACT_REVERT_EXECUTED)),
                sourcing(() -> contractCall(
                                SOME_ERC_20_SCENARIOS,
                                DO_SPECIFIC_APPROVAL,
                                asHeadlongAddress(tokenMirrorAddr.get()),
                                asHeadlongAddress(contractMirrorAddr.get()),
                                BigInteger.valueOf(5))
                        .via("SPENDER_SAME_AS_OWNER_TXN")
                        .gas(1_000_000)
                        .hasKnownStatus(SUCCESS)),
                sourcing(() -> contractCall(
                                SOME_ERC_20_SCENARIOS,
                                DO_SPECIFIC_APPROVAL,
                                asHeadlongAddress(tokenMirrorAddr.get()),
                                asHeadlongAddress(aCivilianMirrorAddr.get()),
                                BigInteger.valueOf(5))
                        .via("SUCCESSFUL_APPROVE_TXN")
                        .gas(1_000_000)
                        .hasKnownStatus(SUCCESS)),
                sourcing(() -> contractCall(
                                SOME_ERC_20_SCENARIOS,
                                GET_ALLOWANCE,
                                asHeadlongAddress(tokenMirrorAddr.get()),
                                asHeadlongAddress(contractMirrorAddr.get()),
                                asHeadlongAddress(aCivilianMirrorAddr.get()))
                        .via("ALLOWANCE_TXN")
                        .gas(1_000_000)
                        .hasKnownStatus(SUCCESS)),
                sourcing(() -> contractCallLocal(
                        SOME_ERC_20_SCENARIOS,
                        GET_ALLOWANCE,
                        asHeadlongAddress(tokenMirrorAddr.get()),
                        asHeadlongAddress(contractMirrorAddr.get()),
                        asHeadlongAddress(aCivilianMirrorAddr.get()))),
                sourcing(() -> contractCall(
                                SOME_ERC_20_SCENARIOS,
                                DO_SPECIFIC_APPROVAL,
                                asHeadlongAddress(tokenMirrorAddr.get()),
                                asHeadlongAddress(aCivilianMirrorAddr.get()),
                                BigInteger.ZERO)
                        .via("SUCCESSFUL_REVOKE_TXN")
                        .gas(1_000_000)
                        .hasKnownStatus(SUCCESS)),
                sourcing(() -> contractCall(
                                SOME_ERC_20_SCENARIOS,
                                GET_ALLOWANCE,
                                asHeadlongAddress(tokenMirrorAddr.get()),
                                asHeadlongAddress(contractMirrorAddr.get()),
                                asHeadlongAddress(aCivilianMirrorAddr.get()))
                        .via("ALLOWANCE_AFTER_REVOKE_TXN")
                        .gas(1_000_000)
                        .hasKnownStatus(SUCCESS)),
                sourcing(() -> contractCall(
                                SOME_ERC_20_SCENARIOS,
                                GET_ALLOWANCE,
                                asHeadlongAddress(tokenMirrorAddr.get()),
                                asHeadlongAddress(zCivilianMirrorAddr.get()),
                                asHeadlongAddress(aCivilianMirrorAddr.get()))
                        .via("MISSING_OWNER_ID")
                        .gas(1_000_000)
                        .hasKnownStatus(CONTRACT_REVERT_EXECUTED)),
                childRecordsCheck(
                        "ACCOUNT_NOT_ASSOCIATED_TXN",
                        CONTRACT_REVERT_EXECUTED,
                        recordWith().status(TOKEN_NOT_ASSOCIATED_TO_ACCOUNT)),
                childRecordsCheck(
                        MISSING_TO, CONTRACT_REVERT_EXECUTED, recordWith().status(INVALID_ALLOWANCE_SPENDER_ID)),
                childRecordsCheck(
                        "SPENDER_SAME_AS_OWNER_TXN", SUCCESS, recordWith().status(SUCCESS)),
                childRecordsCheck(
                        "SUCCESSFUL_APPROVE_TXN", SUCCESS, recordWith().status(SUCCESS)),
                childRecordsCheck("SUCCESSFUL_REVOKE_TXN", SUCCESS, recordWith().status(SUCCESS)),
                childRecordsCheck(
                        "MISSING_OWNER_ID",
                        CONTRACT_REVERT_EXECUTED,
                        recordWith().status(INVALID_ALLOWANCE_OWNER_ID)),
                childRecordsCheck(
                        "ALLOWANCE_TXN",
                        SUCCESS,
                        recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith()
                                        .contractCallResult(htsPrecompileResult()
                                                .forFunction(FunctionType.ERC_ALLOWANCE)
                                                .withAllowance(5L)))),
                childRecordsCheck(
                        "ALLOWANCE_AFTER_REVOKE_TXN",
                        SUCCESS,
                        recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith()
                                        .contractCallResult(htsPrecompileResult()
                                                .forFunction(FunctionType.ERC_ALLOWANCE)
                                                .withAllowance(0L)))));
    }

    @HapiTest
    final Stream<DynamicTest> someErc20NegativeTransferFromScenariosPass() {
        final AtomicReference<String> tokenMirrorAddr = new AtomicReference<>();
        final AtomicReference<String> contractMirrorAddr = new AtomicReference<>();
        final AtomicReference<String> aCivilianMirrorAddr = new AtomicReference<>();
        final AtomicReference<String> bCivilianMirrorAddr = new AtomicReference<>();
        final AtomicReference<String> zCivilianMirrorAddr = new AtomicReference<>();

        return hapiTest(
                newKeyNamed(MULTI_KEY_NAME),
                cryptoCreate(A_CIVILIAN).exposingCreatedIdTo(id -> aCivilianMirrorAddr.set(asHexedSolidityAddress(id))),
                cryptoCreate(B_CIVILIAN).exposingCreatedIdTo(id -> bCivilianMirrorAddr.set(asHexedSolidityAddress(id))),
                uploadInitCode(SOME_ERC_20_SCENARIOS),
                contractCreate(SOME_ERC_20_SCENARIOS)
                        .adminKey(MULTI_KEY_NAME)
                        // Refusing ethereum create conversion, because we get INVALID_SIGNATURE upon
                        // tokenAssociate,
                        // since we have CONTRACT_ID key
                        .refusingEthConversion(),
                tokenCreate(TOKEN)
                        .supplyKey(MULTI_KEY_NAME)
                        .tokenType(FUNGIBLE_COMMON)
                        .treasury(SOME_ERC_20_SCENARIOS)
                        .initialSupply(10)
                        .exposingCreatedIdTo(idLit ->
                                tokenMirrorAddr.set(asHexedSolidityAddress(HapiPropertySource.asToken(idLit)))),
                withOpContext((spec, opLog) -> {
                    zCivilianMirrorAddr.set(asHexedSolidityAddress(
                            AccountID.newBuilder().setAccountNum(666_666_666L).build()));
                    contractMirrorAddr.set(
                            asHexedSolidityAddress(spec.registry().getAccountID(SOME_ERC_20_SCENARIOS)));
                }),
                sourcing(() -> contractCall(
                                SOME_ERC_20_SCENARIOS,
                                DO_TRANSFER_FROM,
                                asHeadlongAddress(tokenMirrorAddr.get()),
                                asHeadlongAddress(contractMirrorAddr.get()),
                                asHeadlongAddress(bCivilianMirrorAddr.get()),
                                BigInteger.ONE)
                        .payingWith(GENESIS)
                        .via("TOKEN_NOT_ASSOCIATED_TO_ACCOUNT_TXN")
                        .hasKnownStatus(CONTRACT_REVERT_EXECUTED)),
                tokenAssociate(B_CIVILIAN, TOKEN),
                sourcing(() -> contractCall(
                                SOME_ERC_20_SCENARIOS,
                                DO_TRANSFER_FROM,
                                asHeadlongAddress(tokenMirrorAddr.get()),
                                asHeadlongAddress(zCivilianMirrorAddr.get()),
                                asHeadlongAddress(bCivilianMirrorAddr.get()),
                                BigInteger.ONE)
                        .payingWith(GENESIS)
                        .via(MISSING_FROM)
                        .hasKnownStatus(CONTRACT_REVERT_EXECUTED)),
                sourcing(() -> contractCall(
                                SOME_ERC_20_SCENARIOS,
                                DO_TRANSFER_FROM,
                                asHeadlongAddress(tokenMirrorAddr.get()),
                                asHeadlongAddress(contractMirrorAddr.get()),
                                asHeadlongAddress(bCivilianMirrorAddr.get()),
                                BigInteger.ONE)
                        .payingWith(GENESIS)
                        .via(MSG_SENDER_IS_THE_SAME_AS_FROM)
                        .hasKnownStatus(CONTRACT_REVERT_EXECUTED)),
                cryptoTransfer(moving(9L, TOKEN).between(SOME_ERC_20_SCENARIOS, B_CIVILIAN)),
                tokenAssociate(A_CIVILIAN, TOKEN),
                sourcing(() -> contractCall(
                                SOME_ERC_20_SCENARIOS,
                                DO_TRANSFER_FROM,
                                asHeadlongAddress(tokenMirrorAddr.get()),
                                asHeadlongAddress(bCivilianMirrorAddr.get()),
                                asHeadlongAddress(aCivilianMirrorAddr.get()),
                                BigInteger.ONE)
                        .payingWith(GENESIS)
                        .via(MSG_SENDER_IS_NOT_THE_SAME_AS_FROM)
                        .hasKnownStatus(CONTRACT_REVERT_EXECUTED)),
                cryptoApproveAllowance()
                        .payingWith(B_CIVILIAN)
                        .addTokenAllowance(B_CIVILIAN, TOKEN, SOME_ERC_20_SCENARIOS, 1L)
                        .signedBy(DEFAULT_PAYER, B_CIVILIAN)
                        .fee(ONE_HBAR),
                sourcing(() -> contractCall(
                                SOME_ERC_20_SCENARIOS,
                                DO_TRANSFER_FROM,
                                asHeadlongAddress(tokenMirrorAddr.get()),
                                asHeadlongAddress(bCivilianMirrorAddr.get()),
                                asHeadlongAddress(aCivilianMirrorAddr.get()),
                                BigInteger.valueOf(5))
                        .payingWith(GENESIS)
                        .via("TRY_TO_TRANSFER_MORE_THAN_APPROVED_AMOUNT_TXN")
                        .hasKnownStatus(CONTRACT_REVERT_EXECUTED)),
                cryptoApproveAllowance()
                        .payingWith(B_CIVILIAN)
                        .addTokenAllowance(B_CIVILIAN, TOKEN, SOME_ERC_20_SCENARIOS, 20L)
                        .signedBy(DEFAULT_PAYER, B_CIVILIAN)
                        .fee(ONE_HBAR),
                sourcing(() -> contractCall(
                                SOME_ERC_20_SCENARIOS,
                                DO_TRANSFER_FROM,
                                asHeadlongAddress(tokenMirrorAddr.get()),
                                asHeadlongAddress(bCivilianMirrorAddr.get()),
                                asHeadlongAddress(aCivilianMirrorAddr.get()),
                                BigInteger.valueOf(20))
                        .payingWith(GENESIS)
                        .via("TRY_TO_TRANSFER_MORE_THAN_OWNERS_BALANCE_TXN")
                        .hasKnownStatus(CONTRACT_REVERT_EXECUTED)),
                childRecordsCheck(
                        "TOKEN_NOT_ASSOCIATED_TO_ACCOUNT_TXN",
                        CONTRACT_REVERT_EXECUTED,
                        recordWith().status(TOKEN_NOT_ASSOCIATED_TO_ACCOUNT)),
                childRecordsCheck(
                        MISSING_FROM, CONTRACT_REVERT_EXECUTED, recordWith().status(INVALID_ACCOUNT_ID)),
                childRecordsCheck(
                        MSG_SENDER_IS_THE_SAME_AS_FROM,
                        CONTRACT_REVERT_EXECUTED,
                        recordWith().status(SPENDER_DOES_NOT_HAVE_ALLOWANCE)),
                childRecordsCheck(
                        MSG_SENDER_IS_NOT_THE_SAME_AS_FROM,
                        CONTRACT_REVERT_EXECUTED,
                        recordWith().status(SPENDER_DOES_NOT_HAVE_ALLOWANCE)),
                childRecordsCheck(
                        "TRY_TO_TRANSFER_MORE_THAN_APPROVED_AMOUNT_TXN",
                        CONTRACT_REVERT_EXECUTED,
                        recordWith().status(AMOUNT_EXCEEDS_ALLOWANCE)),
                childRecordsCheck(
                        "TRY_TO_TRANSFER_MORE_THAN_OWNERS_BALANCE_TXN",
                        CONTRACT_REVERT_EXECUTED,
                        recordWith().status(INSUFFICIENT_TOKEN_BALANCE)));
    }

    @HapiTest
    final Stream<DynamicTest> someErc20ApproveAllowanceScenarioInOneCall() {
        final AtomicReference<String> tokenMirrorAddr = new AtomicReference<>();
        final AtomicReference<String> contractMirrorAddr = new AtomicReference<>();
        final AtomicReference<String> aCivilianMirrorAddr = new AtomicReference<>();
        final AtomicReference<String> bCivilianMirrorAddr = new AtomicReference<>();
        final AtomicReference<String> zCivilianMirrorAddr = new AtomicReference<>();

        return hapiTest(
                newKeyNamed(MULTI_KEY_NAME),
                cryptoCreate(A_CIVILIAN).exposingCreatedIdTo(id -> aCivilianMirrorAddr.set(asHexedSolidityAddress(id))),
                cryptoCreate(B_CIVILIAN).exposingCreatedIdTo(id -> bCivilianMirrorAddr.set(asHexedSolidityAddress(id))),
                uploadInitCode(SOME_ERC_20_SCENARIOS),
                contractCreate(SOME_ERC_20_SCENARIOS)
                        .adminKey(MULTI_KEY_NAME)
                        // Refusing ethereum create conversion, because we get INVALID_SIGNATURE upon
                        // tokenAssociate,
                        // since we have CONTRACT_ID key
                        .refusingEthConversion(),
                tokenCreate(TOKEN)
                        .supplyKey(MULTI_KEY_NAME)
                        .tokenType(FUNGIBLE_COMMON)
                        .treasury(B_CIVILIAN)
                        .initialSupply(10)
                        .exposingCreatedIdTo(idLit ->
                                tokenMirrorAddr.set(asHexedSolidityAddress(HapiPropertySource.asToken(idLit)))),
                tokenAssociate(SOME_ERC_20_SCENARIOS, TOKEN),
                withOpContext((spec, opLog) -> {
                    zCivilianMirrorAddr.set(asHexedSolidityAddress(
                            AccountID.newBuilder().setAccountNum(666_666_666L).build()));
                    contractMirrorAddr.set(
                            asHexedSolidityAddress(spec.registry().getAccountID(SOME_ERC_20_SCENARIOS)));
                }),
                sourcing(() -> contractCall(
                                SOME_ERC_20_SCENARIOS,
                                "approveAndGetAllowanceAmount",
                                asHeadlongAddress(tokenMirrorAddr.get()),
                                asHeadlongAddress(aCivilianMirrorAddr.get()),
                                BigInteger.valueOf(5))
                        .via("APPROVE_AND_GET_ALLOWANCE_TXN")
                        .gas(1_000_000)),
                childRecordsCheck(
                        "APPROVE_AND_GET_ALLOWANCE_TXN",
                        SUCCESS,
                        recordWith().status(SUCCESS),
                        recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith()
                                        .contractCallResult(htsPrecompileResult()
                                                .forFunction(FunctionType.ERC_ALLOWANCE)
                                                .withAllowance(5L)))));
    }

    @HapiTest
    final Stream<DynamicTest> directCallsWorkForErc721() {

        final AtomicReference<String> tokenNum = new AtomicReference<>();

        final var tokenSymbol = "FDFDFD";
        final var tokenTotalSupply = 1;

        final var tokenURITxn = "tokenURITxn";
        final var ownerOfTxn = "ownerOfTxn";

        return hapiTest(
                newKeyNamed(MULTI_KEY),
                cryptoCreate(ACCOUNT).balance(100 * ONE_HUNDRED_HBARS),
                cryptoCreate(RECIPIENT).balance(100 * ONE_HUNDRED_HBARS),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(NON_FUNGIBLE_TOKEN)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .name(TOKEN_NAME)
                        .symbol(tokenSymbol)
                        .initialSupply(0)
                        .treasury(TOKEN_TREASURY)
                        .adminKey(MULTI_KEY)
                        .supplyKey(MULTI_KEY)
                        .exposingCreatedIdTo(tokenNum::set),
                mintToken(NON_FUNGIBLE_TOKEN, List.of(FIRST_META)),
                tokenAssociate(ACCOUNT, NON_FUNGIBLE_TOKEN),
                tokenAssociate(RECIPIENT, NON_FUNGIBLE_TOKEN),
                cryptoTransfer(movingUnique(NON_FUNGIBLE_TOKEN, 1).between(TOKEN_TREASURY, ACCOUNT)),
                withOpContext((spec, ignore) -> {
                    var tokenAddress = asHexedSolidityAddress(asToken(tokenNum.get()));
                    allRunFor(
                            spec,
                            contractCallWithFunctionAbi(
                                            tokenAddress,
                                            getABIFor(
                                                    com.hedera.services.bdd.suites.contract.Utils.FunctionType.FUNCTION,
                                                    "name",
                                                    ERC_721_ABI))
                                    .via(NAME_TXN),
                            contractCallWithFunctionAbi(
                                            tokenAddress,
                                            getABIFor(
                                                    com.hedera.services.bdd.suites.contract.Utils.FunctionType.FUNCTION,
                                                    SYMBOL,
                                                    ERC_721_ABI))
                                    .via(SYMBOL_TXN),
                            contractCallWithFunctionAbi(
                                            tokenAddress,
                                            getABIFor(
                                                    com.hedera.services.bdd.suites.contract.Utils.FunctionType.FUNCTION,
                                                    TOKEN_URI,
                                                    ERC_721_ABI),
                                            BigInteger.ONE)
                                    .via(tokenURITxn),
                            contractCallWithFunctionAbi(
                                            tokenAddress,
                                            getABIFor(
                                                    com.hedera.services.bdd.suites.contract.Utils.FunctionType.FUNCTION,
                                                    TOTAL_SUPPLY,
                                                    ERC_721_ABI))
                                    .via(TOTAL_SUPPLY_TXN),
                            contractCallWithFunctionAbi(
                                            tokenAddress,
                                            getABIFor(
                                                    com.hedera.services.bdd.suites.contract.Utils.FunctionType.FUNCTION,
                                                    BALANCE_OF,
                                                    ERC_721_ABI),
                                            asHeadlongAddress(asHexedSolidityAddress(
                                                    spec.registry().getAccountID(ACCOUNT))))
                                    .via(BALANCE_OF_TXN),
                            contractCallWithFunctionAbi(
                                            tokenAddress,
                                            getABIFor(
                                                    com.hedera.services.bdd.suites.contract.Utils.FunctionType.FUNCTION,
                                                    OWNER_OF,
                                                    ERC_721_ABI),
                                            BigInteger.ONE)
                                    .via(ownerOfTxn));
                }),
                withOpContext((spec, ignore) -> allRunFor(
                        spec,
                        childRecordsCheck(
                                NAME_TXN,
                                SUCCESS,
                                recordWith()
                                        .status(SUCCESS)
                                        .contractCallResult(resultWith()
                                                .contractCallResult(htsPrecompileResult()
                                                        .forFunction(FunctionType.ERC_NAME)
                                                        .withName(TOKEN_NAME)))),
                        childRecordsCheck(
                                SYMBOL_TXN,
                                SUCCESS,
                                recordWith()
                                        .status(SUCCESS)
                                        .contractCallResult(resultWith()
                                                .contractCallResult(htsPrecompileResult()
                                                        .forFunction(FunctionType.ERC_SYMBOL)
                                                        .withSymbol(tokenSymbol)))),
                        childRecordsCheck(
                                tokenURITxn,
                                SUCCESS,
                                recordWith()
                                        .status(SUCCESS)
                                        .contractCallResult(resultWith()
                                                .contractCallResult(htsPrecompileResult()
                                                        .forFunction(FunctionType.ERC_TOKEN_URI)
                                                        .withTokenUri(FIRST)))),
                        childRecordsCheck(
                                TOTAL_SUPPLY_TXN,
                                SUCCESS,
                                recordWith()
                                        .status(SUCCESS)
                                        .contractCallResult(resultWith()
                                                .contractCallResult(htsPrecompileResult()
                                                        .forFunction(FunctionType.ERC_TOTAL_SUPPLY)
                                                        .withTotalSupply(tokenTotalSupply)))),
                        childRecordsCheck(
                                BALANCE_OF_TXN,
                                SUCCESS,
                                recordWith()
                                        .status(SUCCESS)
                                        .contractCallResult(resultWith()
                                                .contractCallResult(htsPrecompileResult()
                                                        .forFunction(FunctionType.ERC_BALANCE)
                                                        .withBalance(1)))),
                        childRecordsCheck(
                                ownerOfTxn,
                                SUCCESS,
                                recordWith()
                                        .status(SUCCESS)
                                        .contractCallResult(resultWith()
                                                .contractCallResult(htsPrecompileResult()
                                                        .forFunction(FunctionType.ERC_OWNER)
                                                        .withOwner(asAddress(
                                                                spec.registry().getAccountID(ACCOUNT)))))))));
    }

    @HapiTest
    final Stream<DynamicTest> someErc721IsApprovedForAllScenariosPass() {
        final AtomicReference<String> tokenMirrorAddr = new AtomicReference<>();
        final AtomicReference<String> contractMirrorAddr = new AtomicReference<>();
        final AtomicReference<String> aCivilianMirrorAddr = new AtomicReference<>();
        final AtomicReference<String> zCivilianMirrorAddr = new AtomicReference<>();
        final AtomicReference<String> zTokenMirrorAddr = new AtomicReference<>();

        return hapiTest(
                newKeyNamed(MULTI_KEY_NAME),
                cryptoCreate(A_CIVILIAN).exposingCreatedIdTo(id -> aCivilianMirrorAddr.set(asHexedSolidityAddress(id))),
                uploadInitCode(SOME_ERC_721_SCENARIOS),
                contractCreate(SOME_ERC_721_SCENARIOS)
                        .adminKey(MULTI_KEY_NAME)
                        // Refusing ethereum create conversion, because we get INVALID_SIGNATURE upon
                        // tokenAssociate,
                        // since we have CONTRACT_ID key
                        .refusingEthConversion(),
                tokenCreate(NF_TOKEN)
                        .supplyKey(MULTI_KEY_NAME)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .treasury(SOME_ERC_721_SCENARIOS)
                        .initialSupply(0)
                        .exposingCreatedIdTo(idLit ->
                                tokenMirrorAddr.set(asHexedSolidityAddress(HapiPropertySource.asToken(idLit)))),
                mintToken(
                        NF_TOKEN,
                        List.of(
                                // 1
                                ByteString.copyFromUtf8("A"),
                                // 2
                                ByteString.copyFromUtf8("B"))),
                tokenAssociate(A_CIVILIAN, NF_TOKEN),
                cryptoTransfer(movingUnique(NF_TOKEN, 1L, 2L).between(SOME_ERC_721_SCENARIOS, A_CIVILIAN)),
                withOpContext((spec, opLog) -> {
                    zCivilianMirrorAddr.set(asHexedSolidityAddress(
                            AccountID.newBuilder().setAccountNum(666_666_666L).build()));
                    zTokenMirrorAddr.set(asHexedSolidityAddress(
                            TokenID.newBuilder().setTokenNum(666_666L).build()));
                    contractMirrorAddr.set(
                            asHexedSolidityAddress(spec.registry().getAccountID(SOME_ERC_721_SCENARIOS)));
                }),
                sourcing(() -> contractCall(
                                SOME_ERC_721_SCENARIOS,
                                IS_APPROVED_FOR_ALL,
                                asHeadlongAddress(tokenMirrorAddr.get()),
                                asHeadlongAddress(zCivilianMirrorAddr.get()),
                                asHeadlongAddress(contractMirrorAddr.get()))
                        .via("OWNER_DOES_NOT_EXISTS")
                        .gas(1_000_000)),
                sourcing(() -> contractCall(
                                SOME_ERC_721_SCENARIOS,
                                IS_APPROVED_FOR_ALL,
                                asHeadlongAddress(tokenMirrorAddr.get()),
                                asHeadlongAddress(aCivilianMirrorAddr.get()),
                                asHeadlongAddress(zCivilianMirrorAddr.get()))
                        .via(OPERATOR_DOES_NOT_EXISTS)
                        .gas(1_000_000)
                        .hasKnownStatus(SUCCESS)),
                sourcing(() -> contractCall(
                                SOME_ERC_721_SCENARIOS,
                                IS_APPROVED_FOR_ALL,
                                asHeadlongAddress(tokenMirrorAddr.get()),
                                asHeadlongAddress(aCivilianMirrorAddr.get()),
                                asHeadlongAddress(contractMirrorAddr.get()))
                        .via("OPERATOR_IS_NOT_APPROVED")
                        .gas(1_000_000)
                        .hasKnownStatus(SUCCESS)),
                cryptoApproveAllowance()
                        .payingWith(A_CIVILIAN)
                        .addNftAllowance(A_CIVILIAN, NF_TOKEN, SOME_ERC_721_SCENARIOS, true, List.of())
                        .signedBy(DEFAULT_PAYER, A_CIVILIAN)
                        .fee(ONE_HBAR),
                getAccountDetails(A_CIVILIAN)
                        .payingWith(GENESIS)
                        .has(accountDetailsWith()
                                .cryptoAllowancesCount(0)
                                .nftApprovedForAllAllowancesCount(1)
                                .tokenAllowancesCount(0)
                                .nftApprovedAllowancesContaining(NF_TOKEN, SOME_ERC_721_SCENARIOS)),
                sourcing(() -> contractCall(
                                SOME_ERC_721_SCENARIOS,
                                IS_APPROVED_FOR_ALL,
                                asHeadlongAddress(tokenMirrorAddr.get()),
                                asHeadlongAddress(aCivilianMirrorAddr.get()),
                                asHeadlongAddress(contractMirrorAddr.get()))
                        .via("OPERATOR_IS_APPROVED_FOR_ALL")
                        .gas(1_000_000)
                        .hasKnownStatus(SUCCESS)),
                sourcing(() -> contractCallLocal(
                                SOME_ERC_721_SCENARIOS,
                                IS_APPROVED_FOR_ALL,
                                asHeadlongAddress(tokenMirrorAddr.get()),
                                asHeadlongAddress(aCivilianMirrorAddr.get()),
                                asHeadlongAddress(contractMirrorAddr.get()))
                        .gas(1_000_000)
                        .has(resultWith().contractCallResult(flag(true)))),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        childRecordsCheck(
                                OPERATOR_DOES_NOT_EXISTS,
                                SUCCESS,
                                recordWith()
                                        .status(SUCCESS)
                                        .contractCallResult(resultWith()
                                                .contractCallResult(htsPrecompileResult()
                                                        .forFunction(FunctionType.ERC_IS_APPROVED_FOR_ALL)
                                                        .withIsApprovedForAll(false)))),
                        childRecordsCheck(
                                "OPERATOR_IS_NOT_APPROVED",
                                SUCCESS,
                                recordWith()
                                        .status(SUCCESS)
                                        .contractCallResult(resultWith()
                                                .contractCallResult(htsPrecompileResult()
                                                        .forFunction(FunctionType.ERC_IS_APPROVED_FOR_ALL)
                                                        .withIsApprovedForAll(false)))),
                        childRecordsCheck(
                                "OPERATOR_IS_APPROVED_FOR_ALL",
                                SUCCESS,
                                recordWith()
                                        .status(SUCCESS)
                                        .contractCallResult(resultWith()
                                                .contractCallResult(htsPrecompileResult()
                                                        .forFunction(FunctionType.ERC_IS_APPROVED_FOR_ALL)
                                                        .withIsApprovedForAll(true)))))));
    }

    @HapiTest
    final Stream<DynamicTest> someErc721SetApprovedForAllScenariosPass() {
        final AtomicReference<String> tokenMirrorAddr = new AtomicReference<>();
        final AtomicReference<String> contractMirrorAddr = new AtomicReference<>();
        final AtomicReference<String> aCivilianMirrorAddr = new AtomicReference<>();
        final AtomicReference<String> zCivilianMirrorAddr = new AtomicReference<>();
        final AtomicReference<String> zTokenMirrorAddr = new AtomicReference<>();

        return hapiTest(
                newKeyNamed(MULTI_KEY_NAME),
                cryptoCreate(A_CIVILIAN).exposingCreatedIdTo(id -> aCivilianMirrorAddr.set(asHexedSolidityAddress(id))),
                uploadInitCode(SOME_ERC_721_SCENARIOS),
                contractCreate(SOME_ERC_721_SCENARIOS)
                        .adminKey(MULTI_KEY_NAME)
                        // Refusing ethereum create conversion, because we get INVALID_SIGNATURE upon
                        // tokenAssociate,
                        // since we have CONTRACT_ID key
                        .refusingEthConversion(),
                tokenCreate(NF_TOKEN)
                        .supplyKey(MULTI_KEY_NAME)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .treasury(SOME_ERC_721_SCENARIOS)
                        .initialSupply(0)
                        .exposingCreatedIdTo(idLit ->
                                tokenMirrorAddr.set(asHexedSolidityAddress(HapiPropertySource.asToken(idLit)))),
                mintToken(
                        NF_TOKEN,
                        List.of(
                                // 1
                                ByteString.copyFromUtf8("A"),
                                // 2
                                ByteString.copyFromUtf8("B"))),
                tokenAssociate(A_CIVILIAN, NF_TOKEN),
                withOpContext((spec, opLog) -> {
                    zCivilianMirrorAddr.set(asHexedSolidityAddress(
                            AccountID.newBuilder().setAccountNum(666_666_666L).build()));
                    zTokenMirrorAddr.set(asHexedSolidityAddress(
                            TokenID.newBuilder().setTokenNum(666_666L).build()));
                    contractMirrorAddr.set(
                            asHexedSolidityAddress(spec.registry().getAccountID(SOME_ERC_721_SCENARIOS)));
                }),
                sourcing(() -> contractCall(
                                SOME_ERC_721_SCENARIOS,
                                SET_APPROVAL_FOR_ALL,
                                asHeadlongAddress(tokenMirrorAddr.get()),
                                asHeadlongAddress(contractMirrorAddr.get()),
                                true)
                        .via("OPERATOR_SAME_AS_MSG_SENDER")
                        .gas(1_000_000)
                        .hasKnownStatus(CONTRACT_REVERT_EXECUTED)),
                sourcing(() -> contractCall(
                                SOME_ERC_721_SCENARIOS,
                                SET_APPROVAL_FOR_ALL,
                                asHeadlongAddress(tokenMirrorAddr.get()),
                                asHeadlongAddress(zCivilianMirrorAddr.get()),
                                true)
                        .via(OPERATOR_DOES_NOT_EXISTS)
                        .gas(1_000_000)
                        .hasKnownStatus(SUCCESS)),
                sourcing(() -> contractCall(
                                SOME_ERC_721_SCENARIOS,
                                SET_APPROVAL_FOR_ALL,
                                asHeadlongAddress(tokenMirrorAddr.get()),
                                asHeadlongAddress(aCivilianMirrorAddr.get()),
                                true)
                        .via("OPERATOR_EXISTS")
                        .gas(1_000_000)
                        .hasKnownStatus(SUCCESS)),
                sourcing(() -> contractCall(
                                SOME_ERC_721_SCENARIOS,
                                IS_APPROVED_FOR_ALL,
                                asHeadlongAddress(tokenMirrorAddr.get()),
                                asHeadlongAddress(contractMirrorAddr.get()),
                                asHeadlongAddress(aCivilianMirrorAddr.get()))
                        .via("SUCCESSFULLY_APPROVED_CHECK_TXN")
                        .gas(1_000_000)
                        .hasKnownStatus(SUCCESS)),
                sourcing(() -> contractCall(
                                SOME_ERC_721_SCENARIOS,
                                SET_APPROVAL_FOR_ALL,
                                asHeadlongAddress(tokenMirrorAddr.get()),
                                asHeadlongAddress(aCivilianMirrorAddr.get()),
                                false)
                        .via("OPERATOR_EXISTS_REVOKE_APPROVE_FOR_ALL")
                        .gas(1_000_000)
                        .hasKnownStatus(SUCCESS)),
                sourcing(() -> contractCall(
                                SOME_ERC_721_SCENARIOS,
                                IS_APPROVED_FOR_ALL,
                                asHeadlongAddress(tokenMirrorAddr.get()),
                                asHeadlongAddress(contractMirrorAddr.get()),
                                asHeadlongAddress(aCivilianMirrorAddr.get()))
                        .via("SUCCESSFULLY_REVOKED_CHECK_TXN")
                        .gas(1_000_000)),
                childRecordsCheck(
                        "OPERATOR_SAME_AS_MSG_SENDER",
                        CONTRACT_REVERT_EXECUTED,
                        recordWith().status(SPENDER_ACCOUNT_SAME_AS_OWNER)),
                childRecordsCheck(
                        OPERATOR_DOES_NOT_EXISTS, SUCCESS, recordWith().status(INVALID_ALLOWANCE_SPENDER_ID)),
                childRecordsCheck("OPERATOR_EXISTS", SUCCESS, recordWith().status(SUCCESS)),
                childRecordsCheck(
                        "OPERATOR_EXISTS_REVOKE_APPROVE_FOR_ALL",
                        SUCCESS,
                        recordWith().status(SUCCESS)),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        childRecordsCheck(
                                "SUCCESSFULLY_APPROVED_CHECK_TXN",
                                SUCCESS,
                                recordWith()
                                        .status(SUCCESS)
                                        .contractCallResult(resultWith()
                                                .contractCallResult(htsPrecompileResult()
                                                        .forFunction(FunctionType.ERC_IS_APPROVED_FOR_ALL)
                                                        .withIsApprovedForAll(true)))),
                        childRecordsCheck(
                                "SUCCESSFULLY_REVOKED_CHECK_TXN",
                                SUCCESS,
                                recordWith()
                                        .status(SUCCESS)
                                        .contractCallResult(resultWith()
                                                .contractCallResult(htsPrecompileResult()
                                                        .forFunction(FunctionType.ERC_IS_APPROVED_FOR_ALL)
                                                        .withIsApprovedForAll(false)))))));
    }

    @HapiTest
    final Stream<DynamicTest> getErc721IsApprovedForAll() {
        final var notApprovedTxn = "notApprovedTxn";
        final var approvedForAllTxn = "approvedForAllTxn";

        return hapiTest(
                newKeyNamed(MULTI_KEY),
                cryptoCreate(OWNER).balance(100 * ONE_HUNDRED_HBARS),
                cryptoCreate(RECIPIENT).balance(100 * ONE_HUNDRED_HBARS),
                cryptoCreate(ACCOUNT).balance(100 * ONE_HUNDRED_HBARS),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(NON_FUNGIBLE_TOKEN)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0)
                        .treasury(TOKEN_TREASURY)
                        .adminKey(MULTI_KEY)
                        .supplyKey(MULTI_KEY),
                mintToken(NON_FUNGIBLE_TOKEN, List.of(ByteString.copyFromUtf8("A"), ByteString.copyFromUtf8("B"))),
                uploadInitCode(ERC_721_CONTRACT),
                contractCreate(ERC_721_CONTRACT),
                tokenAssociate(OWNER, NON_FUNGIBLE_TOKEN),
                cryptoTransfer(movingUnique(NON_FUNGIBLE_TOKEN, 1L, 2L).between(TOKEN_TREASURY, OWNER)),
                cryptoApproveAllowance()
                        .payingWith(OWNER)
                        .addNftAllowance(OWNER, NON_FUNGIBLE_TOKEN, RECIPIENT, true, List.of(1L, 2L))
                        .signedBy(DEFAULT_PAYER, OWNER)
                        .fee(ONE_HBAR),
                getAccountDetails(OWNER)
                        .payingWith(GENESIS)
                        .has(accountDetailsWith()
                                .cryptoAllowancesCount(0)
                                .nftApprovedForAllAllowancesCount(1)
                                .tokenAllowancesCount(0)
                                .nftApprovedAllowancesContaining(NON_FUNGIBLE_TOKEN, RECIPIENT)),
                getTokenNftInfo(NON_FUNGIBLE_TOKEN, 1L).hasSpenderID(RECIPIENT),
                getTokenNftInfo(NON_FUNGIBLE_TOKEN, 2L).hasSpenderID(RECIPIENT),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCall(
                                        ERC_721_CONTRACT,
                                        "isApprovedForAll",
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getTokenID(NON_FUNGIBLE_TOKEN))),
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getAccountID(OWNER))),
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getAccountID(RECIPIENT))))
                                .payingWith(OWNER)
                                .via(approvedForAllTxn)
                                .hasKnownStatus(SUCCESS)
                                .gas(GAS_TO_OFFER),
                        contractCall(
                                        ERC_721_CONTRACT,
                                        "isApprovedForAll",
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getTokenID(NON_FUNGIBLE_TOKEN))),
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getAccountID(OWNER))),
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getAccountID(ACCOUNT))))
                                .payingWith(OWNER)
                                .via(notApprovedTxn)
                                .hasKnownStatus(SUCCESS)
                                .gas(GAS_TO_OFFER))),
                childRecordsCheck(
                        approvedForAllTxn,
                        SUCCESS,
                        recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith()
                                        .contractCallResult(htsPrecompileResult()
                                                .forFunction(FunctionType.ERC_IS_APPROVED_FOR_ALL)
                                                .withIsApprovedForAll(true)))),
                childRecordsCheck(
                        notApprovedTxn,
                        SUCCESS,
                        recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith()
                                        .contractCallResult(htsPrecompileResult()
                                                .forFunction(FunctionType.ERC_IS_APPROVED_FOR_ALL)
                                                .withIsApprovedForAll(false)))));
    }

    @HapiTest
    final Stream<DynamicTest> erc721TokenApprove() {
        return hapiTest(
                newKeyNamed(MULTI_KEY),
                cryptoCreate(ACCOUNT).balance(100 * ONE_HUNDRED_HBARS),
                cryptoCreate(RECIPIENT).balance(100 * ONE_HUNDRED_HBARS),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(NON_FUNGIBLE_TOKEN)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0)
                        .treasury(TOKEN_TREASURY)
                        .adminKey(MULTI_KEY)
                        .supplyKey(MULTI_KEY),
                uploadInitCode(ERC_721_CONTRACT),
                // Refusing ethereum create conversion, because we get INVALID_SIGNATURE upon tokenAssociate,
                // since we have CONTRACT_ID key
                contractCreate(ERC_721_CONTRACT).refusingEthConversion(),
                mintToken(NON_FUNGIBLE_TOKEN, List.of(FIRST_META)),
                tokenAssociate(ACCOUNT, List.of(NON_FUNGIBLE_TOKEN)),
                tokenAssociate(RECIPIENT, List.of(NON_FUNGIBLE_TOKEN)),
                tokenAssociate(ERC_721_CONTRACT, List.of(NON_FUNGIBLE_TOKEN)),
                cryptoTransfer(movingUnique(NON_FUNGIBLE_TOKEN, 1L).between(TOKEN_TREASURY, ERC_721_CONTRACT)),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCall(
                                        ERC_721_CONTRACT,
                                        APPROVE,
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getTokenID(NON_FUNGIBLE_TOKEN))),
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getAccountID(RECIPIENT))),
                                        BigInteger.ONE)
                                .payingWith(ACCOUNT)
                                .via(NAME_TXN)
                                .hasKnownStatus(SUCCESS)
                                .gas(GAS_TO_OFFER))),
                getTxnRecord(NAME_TXN).andAllChildRecords());
    }

    @HapiTest
    final Stream<DynamicTest> erc721GetApproved() {
        final var theSpender2 = "spender2";

        return hapiTest(
                newKeyNamed(MULTI_KEY),
                cryptoCreate(OWNER).balance(100 * ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                cryptoCreate(SPENDER),
                cryptoCreate(theSpender2),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(NON_FUNGIBLE_TOKEN)
                        .initialSupply(0)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .supplyKey(MULTI_KEY)
                        .adminKey(MULTI_KEY)
                        .treasury(TOKEN_TREASURY),
                uploadInitCode(ERC_721_CONTRACT),
                contractCreate(ERC_721_CONTRACT),
                tokenAssociate(OWNER, NON_FUNGIBLE_TOKEN),
                mintToken(NON_FUNGIBLE_TOKEN, List.of(ByteString.copyFromUtf8("a")))
                        .via(NFT_TOKEN_MINT),
                cryptoTransfer(movingUnique(NON_FUNGIBLE_TOKEN, 1L).between(TOKEN_TREASURY, OWNER)),
                cryptoApproveAllowance()
                        .payingWith(DEFAULT_PAYER)
                        .addNftAllowance(OWNER, NON_FUNGIBLE_TOKEN, SPENDER, false, List.of(1L))
                        .via(BASE_APPROVE_TXN)
                        .logged()
                        .signedBy(DEFAULT_PAYER, OWNER)
                        .fee(ONE_HBAR),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCall(
                                        ERC_721_CONTRACT,
                                        "getApproved",
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getTokenID(NON_FUNGIBLE_TOKEN))),
                                        BigInteger.ONE)
                                .payingWith(OWNER)
                                .via(ALLOWANCE_TXN))),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        childRecordsCheck(
                                ALLOWANCE_TXN,
                                SUCCESS,
                                recordWith()
                                        .status(SUCCESS)
                                        .contractCallResult(resultWith()
                                                .contractCallResult(htsPrecompileResult()
                                                        .forFunction(FunctionType.ERC_GET_APPROVED)
                                                        .withSpender(asAddress(
                                                                spec.registry().getAccountID(SPENDER)))))))),
                getTxnRecord(ALLOWANCE_TXN).andAllChildRecords());
    }

    @HapiTest
    final Stream<DynamicTest> erc20TransferFromAllowance() {
        final var allowanceTxn2 = "allowanceTxn2";

        return hapiTest(
                newKeyNamed(MULTI_KEY),
                cryptoCreate(OWNER).balance(100 * ONE_HUNDRED_HBARS),
                cryptoCreate(RECIPIENT),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(FUNGIBLE_TOKEN)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .supplyType(TokenSupplyType.FINITE)
                        .initialSupply(10L)
                        .maxSupply(1000L)
                        .treasury(TOKEN_TREASURY)
                        .adminKey(MULTI_KEY)
                        .supplyKey(MULTI_KEY),
                uploadInitCode(ERC_20_CONTRACT),
                // Refusing ethereum create conversion, because we get INVALID_SIGNATURE upon tokenAssociate,
                // since we have CONTRACT_ID key
                contractCreate(ERC_20_CONTRACT).refusingEthConversion(),
                tokenAssociate(OWNER, FUNGIBLE_TOKEN),
                tokenAssociate(RECIPIENT, FUNGIBLE_TOKEN),
                tokenAssociate(ERC_20_CONTRACT, FUNGIBLE_TOKEN),
                cryptoTransfer(moving(10, FUNGIBLE_TOKEN).between(TOKEN_TREASURY, OWNER)),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        // ERC_20_CONTRACT is approved as spender of
                        // fungible tokens for OWNER
                        cryptoApproveAllowance()
                                .payingWith(DEFAULT_PAYER)
                                .addTokenAllowance(OWNER, FUNGIBLE_TOKEN, ERC_20_CONTRACT, 2L)
                                .via(BASE_APPROVE_TXN)
                                .logged()
                                .signedBy(DEFAULT_PAYER, OWNER)
                                .fee(ONE_HBAR),
                        // Check that ERC_20_CONTRACT has allowance of 2
                        contractCall(
                                        ERC_20_CONTRACT,
                                        ALLOWANCE,
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getTokenID(FUNGIBLE_TOKEN))),
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getAccountID(OWNER))),
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getAccountID(ERC_20_CONTRACT))))
                                .gas(500_000L)
                                .via(ALLOWANCE_TXN)
                                .hasKnownStatus(SUCCESS),
                        // ERC_20_CONTRACT calls the precompile transferFrom
                        // as the spender
                        contractCall(
                                        ERC_20_CONTRACT,
                                        TRANSFER_FROM,
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getTokenID(FUNGIBLE_TOKEN))),
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getAccountID(OWNER))),
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getAccountID(RECIPIENT))),
                                        BigInteger.TWO)
                                .gas(500_000L)
                                .via(TRANSFER_FROM_ACCOUNT_TXN)
                                .hasKnownStatus(SUCCESS),
                        // ERC_20_CONTRACT should have spent its allowance
                        contractCall(
                                        ERC_20_CONTRACT,
                                        ALLOWANCE,
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getTokenID(FUNGIBLE_TOKEN))),
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getAccountID(OWNER))),
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getAccountID(ERC_20_CONTRACT))))
                                .gas(500_000L)
                                .via(allowanceTxn2))),
                childRecordsCheck(
                        ALLOWANCE_TXN,
                        SUCCESS,
                        recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith()
                                        .contractCallResult(htsPrecompileResult()
                                                .forFunction(FunctionType.ERC_ALLOWANCE)
                                                .withAllowance(2)))),
                childRecordsCheck(
                        allowanceTxn2,
                        SUCCESS,
                        recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith()
                                        .contractCallResult(htsPrecompileResult()
                                                .forFunction(FunctionType.ERC_ALLOWANCE)
                                                .withAllowance(0)))));
    }

    @HapiTest
    final Stream<DynamicTest> erc20TransferFromSelf() {
        return hapiTest(
                newKeyNamed(MULTI_KEY),
                cryptoCreate(RECIPIENT),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(FUNGIBLE_TOKEN)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .supplyType(TokenSupplyType.FINITE)
                        .initialSupply(10L)
                        .maxSupply(1000L)
                        .treasury(TOKEN_TREASURY)
                        .adminKey(MULTI_KEY)
                        .supplyKey(MULTI_KEY),
                uploadInitCode(ERC_20_CONTRACT),
                // Refusing ethereum create conversion, because we get INVALID_SIGNATURE upon tokenAssociate,
                // since we have CONTRACT_ID key
                contractCreate(ERC_20_CONTRACT).refusingEthConversion(),
                tokenAssociate(RECIPIENT, FUNGIBLE_TOKEN),
                tokenAssociate(ERC_20_CONTRACT, FUNGIBLE_TOKEN),
                cryptoTransfer(moving(10, FUNGIBLE_TOKEN).between(TOKEN_TREASURY, ERC_20_CONTRACT)),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        // ERC_20_CONTRACT should be able to transfer its
                        // own tokens
                        contractCall(
                                        ERC_20_CONTRACT,
                                        TRANSFER_FROM,
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getTokenID(FUNGIBLE_TOKEN))),
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getAccountID(ERC_20_CONTRACT))),
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getAccountID(RECIPIENT))),
                                        BigInteger.TWO)
                                .gas(500_000L)
                                .via(TRANSFER_FROM_ACCOUNT_TXN)
                                // No longer works unless you have allowance
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED))),
                getAccountBalance(RECIPIENT).hasTokenBalance(FUNGIBLE_TOKEN, 0),
                childRecordsCheck(
                        TRANSFER_FROM_ACCOUNT_TXN,
                        CONTRACT_REVERT_EXECUTED,
                        recordWith().status(SPENDER_DOES_NOT_HAVE_ALLOWANCE)));
    }

    @HapiTest
    final Stream<DynamicTest> erc721TransferFromWithApproval() {
        return hapiTest(
                newKeyNamed(MULTI_KEY),
                cryptoCreate(OWNER).balance(100 * ONE_HUNDRED_HBARS),
                cryptoCreate(SPENDER),
                cryptoCreate(RECIPIENT),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(NON_FUNGIBLE_TOKEN)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0)
                        .treasury(TOKEN_TREASURY)
                        .adminKey(MULTI_KEY)
                        .supplyKey(MULTI_KEY),
                uploadInitCode(ERC_721_CONTRACT),
                // Refusing ethereum create conversion, because we get INVALID_SIGNATURE upon tokenAssociate,
                // since we have CONTRACT_ID key
                contractCreate(ERC_721_CONTRACT).refusingEthConversion(),
                tokenAssociate(OWNER, NON_FUNGIBLE_TOKEN),
                tokenAssociate(SPENDER, NON_FUNGIBLE_TOKEN),
                tokenAssociate(RECIPIENT, NON_FUNGIBLE_TOKEN),
                tokenAssociate(ERC_721_CONTRACT, NON_FUNGIBLE_TOKEN),
                mintToken(NON_FUNGIBLE_TOKEN, List.of(FIRST_META, SECOND_META)),
                cryptoTransfer(movingUnique(NON_FUNGIBLE_TOKEN, 1L).between(TOKEN_TREASURY, OWNER)),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        cryptoApproveAllowance()
                                .payingWith(DEFAULT_PAYER)
                                .addNftAllowance(OWNER, NON_FUNGIBLE_TOKEN, ERC_721_CONTRACT, false, List.of(1L))
                                .via(BASE_APPROVE_TXN)
                                .logged()
                                .signedBy(DEFAULT_PAYER, OWNER)
                                .fee(ONE_HBAR),
                        getTokenNftInfo(NON_FUNGIBLE_TOKEN, 1L).hasSpenderID(ERC_721_CONTRACT),
                        contractCall(
                                        ERC_721_CONTRACT,
                                        TRANSFER_FROM,
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getTokenID(NON_FUNGIBLE_TOKEN))),
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getAccountID(OWNER))),
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getAccountID(RECIPIENT))),
                                        BigInteger.ONE)
                                .via(TRANSFER_FROM_ACCOUNT_TXN)
                                .hasKnownStatus(SUCCESS),
                        getTxnRecord(TRANSFER_FROM_ACCOUNT_TXN)
                                .andAllChildRecords()
                                .logged(),
                        getAccountDetails(RECIPIENT).logged(),
                        getAccountDetails(OWNER).logged(),
                        getTokenNftInfo(NON_FUNGIBLE_TOKEN, 1L).hasNoSpender())),
                getAccountInfo(OWNER).savingSnapshot(OWNER),
                getAccountInfo(RECIPIENT).savingSnapshot(RECIPIENT),
                withOpContext((spec, log) -> {
                    final var sender = spec.registry().getAccountInfo(OWNER).getAccountID();
                    final var receiver =
                            spec.registry().getAccountInfo(RECIPIENT).getAccountID();
                    final var idOfToken = String.valueOf(
                            spec.registry().getTokenID(NON_FUNGIBLE_TOKEN).getTokenNum());
                    var txnRecord = getTxnRecord(TRANSFER_FROM_ACCOUNT_TXN)
                            .hasPriority(recordWith()
                                    .contractCallResult(resultWith()
                                            .logs(inOrder(logWith()
                                                    .contract(idOfToken)
                                                    .withTopicsInOrder(List.of(
                                                            eventSignatureOf(TRANSFER_SIGNATURE),
                                                            parsedToByteString(
                                                                    sender.getShardNum(),
                                                                    sender.getRealmNum(),
                                                                    sender.getAccountNum()),
                                                            parsedToByteString(
                                                                    receiver.getShardNum(),
                                                                    receiver.getRealmNum(),
                                                                    receiver.getAccountNum()),
                                                            parsedToByteString(1L)))))))
                            .andAllChildRecords()
                            .logged();
                    allRunFor(spec, txnRecord);
                }));
    }

    @HapiTest
    final Stream<DynamicTest> erc721TransferFromWithApproveForAll() {
        return hapiTest(
                newKeyNamed(MULTI_KEY),
                cryptoCreate(OWNER).balance(100 * ONE_HUNDRED_HBARS),
                cryptoCreate(SPENDER),
                cryptoCreate(RECIPIENT),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(NON_FUNGIBLE_TOKEN)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0)
                        .treasury(TOKEN_TREASURY)
                        .adminKey(MULTI_KEY)
                        .supplyKey(MULTI_KEY),
                uploadInitCode(ERC_721_CONTRACT),
                // Refusing ethereum create conversion, because we get INVALID_SIGNATURE upon tokenAssociate,
                // since we have CONTRACT_ID key
                contractCreate(ERC_721_CONTRACT).refusingEthConversion(),
                tokenAssociate(OWNER, NON_FUNGIBLE_TOKEN),
                tokenAssociate(SPENDER, NON_FUNGIBLE_TOKEN),
                tokenAssociate(RECIPIENT, NON_FUNGIBLE_TOKEN),
                tokenAssociate(ERC_721_CONTRACT, NON_FUNGIBLE_TOKEN),
                mintToken(NON_FUNGIBLE_TOKEN, List.of(FIRST_META, SECOND_META)),
                cryptoTransfer(movingUnique(NON_FUNGIBLE_TOKEN, 1L, 2L).between(TOKEN_TREASURY, OWNER)),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        cryptoApproveAllowance()
                                .payingWith(DEFAULT_PAYER)
                                .addNftAllowance(OWNER, NON_FUNGIBLE_TOKEN, ERC_721_CONTRACT, true, List.of(1L, 2L))
                                .via(BASE_APPROVE_TXN)
                                .logged()
                                .signedBy(DEFAULT_PAYER, OWNER)
                                .fee(ONE_HBAR),
                        getTokenNftInfo(NON_FUNGIBLE_TOKEN, 1L).hasSpenderID(ERC_721_CONTRACT),
                        getTokenNftInfo(NON_FUNGIBLE_TOKEN, 2L).hasSpenderID(ERC_721_CONTRACT),
                        getAccountDetails(OWNER).logged(),
                        getAccountDetails(OWNER)
                                .payingWith(GENESIS)
                                .has(accountDetailsWith().nftApprovedForAllAllowancesCount(1)),
                        contractCall(
                                        ERC_721_CONTRACT,
                                        TRANSFER_FROM,
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getTokenID(NON_FUNGIBLE_TOKEN))),
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getAccountID(OWNER))),
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getAccountID(RECIPIENT))),
                                        BigInteger.ONE)
                                .via(TRANSFER_FROM_ACCOUNT_TXN)
                                .hasKnownStatus(SUCCESS),
                        getAccountDetails(RECIPIENT).logged(),
                        getAccountDetails(OWNER).logged())));
    }
}
