// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.precompile;

import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiPropertySource.asAccount;
import static com.hedera.services.bdd.spec.HapiPropertySource.asEntityString;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AccountDetailsAsserts.accountDetailsWith;
import static com.hedera.services.bdd.spec.assertions.AssertUtils.inOrder;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.resultWith;
import static com.hedera.services.bdd.spec.assertions.ContractLogAsserts.logWith;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.keys.KeyShape.DELEGATE_CONTRACT;
import static com.hedera.services.bdd.spec.keys.KeyShape.sigs;
import static com.hedera.services.bdd.spec.keys.SigControl.ON;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountDetails;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTokenInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoApproveAllowance;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedHbarFee;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedHbarFeeInheritingRoyaltyCollector;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedHtsFee;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedHtsFeeInheritingRoyaltyCollector;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fractionalFee;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.royaltyFeeWithFallback;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingUnique;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.accountAmount;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.childRecordsCheck;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.emptyChildRecordsCheck;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.nftTransfer;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.tokenTransferList;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.tokenTransferLists;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.TOKEN_TREASURY;
import static com.hedera.services.bdd.suites.HapiSuite.flattened;
import static com.hedera.services.bdd.suites.contract.Utils.asAddress;
import static com.hedera.services.bdd.suites.contract.Utils.asSolidityAddress;
import static com.hedera.services.bdd.suites.contract.Utils.eventSignatureOf;
import static com.hedera.services.bdd.suites.contract.Utils.mirrorAddrWith;
import static com.hedera.services.bdd.suites.contract.Utils.parsedToByteString;
import static com.hedera.services.bdd.suites.contract.evm.Evm46ValidationSuite.existingSystemAccounts;
import static com.hedera.services.bdd.suites.contract.evm.Evm46ValidationSuite.nonExistingSystemAccounts;
import static com.hedera.services.bdd.suites.contract.precompile.ERCPrecompileSuite.TRANSFER_SIGNATURE;
import static com.hedera.services.bdd.suites.utils.MiscEETUtils.metadata;
import static com.hedera.services.bdd.suites.utils.contracts.precompile.HTSPrecompileResult.htsPrecompileResult;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.AMOUNT_EXCEEDS_ALLOWANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_TOKEN_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ALIAS_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_RECEIVING_NODE_ACCOUNT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOKEN_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SPENDER_DOES_NOT_HAVE_ALLOWANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;

import com.esaulpaugh.headlong.abi.Tuple;
import com.google.protobuf.ByteString;
import com.hedera.node.app.hapi.utils.ByteStringUtils;
import com.hedera.node.app.hapi.utils.contracts.ParsingConstants.FunctionType;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.assertions.NonFungibleTransfers;
import com.hedera.services.bdd.spec.assertions.SomeFungibleTransfers;
import com.hedera.services.bdd.spec.keys.KeyShape;
import com.hedera.services.bdd.spec.transactions.contract.HapiParserUtil;
import com.hedera.services.bdd.spec.transactions.token.TokenMovement;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.TokenID;
import com.hederahashgraph.api.proto.java.TokenSupplyType;
import com.hederahashgraph.api.proto.java.TokenType;
import java.math.BigInteger;
import java.util.List;
import java.util.OptionalLong;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(SMART_CONTRACT)
public class CryptoTransferHTSSuite {

    private static final Logger log = LogManager.getLogger(CryptoTransferHTSSuite.class);

    private static final long GAS_FOR_AUTO_ASSOCIATING_CALLS = 2_000_000;
    private static final long GAS_TO_OFFER = 4_000_000L;
    public static final long TOTAL_SUPPLY = 1_000;
    private static final String FUNGIBLE_TOKEN = "TokenA";
    private static final String NFT_TOKEN = "Token_NFT";

    private static final String RECEIVER = "receiver";
    private static final String RECEIVER2 = "receiver2";
    private static final String SENDER = "sender";
    private static final String SENDER2 = "sender2";
    private static final KeyShape DELEGATE_CONTRACT_KEY_SHAPE =
            KeyShape.threshOf(1, KeyShape.SIMPLE, DELEGATE_CONTRACT);

    public static final String DELEGATE_KEY = "contractKey";
    private static final String CONTRACT = "CryptoTransfer";
    private static final String MULTI_KEY = "purpose";
    private static final String HTS_TRANSFER_FROM_CONTRACT = "HtsTransferFrom";
    private static final String NEGATIVE_HTS_TRANSFER_FROM_CONTRACT = "NegativeHtsTransferFrom";
    private static final String OWNER = "Owner";
    private static final String HTS_TRANSFER_FROM = "htsTransferFrom";
    private static final String HTS_TRANSFER_FROM_NFT = "htsTransferFromNFT";
    private static final String HTS_TRANSFER_FROM_UNDERFLOW = "transferFromUnderflowAmountValue";
    private static final String HTS_TRANSFER_FROM_OVERFLOW = "transferFromWithOverflowAmountValue";
    public static final String TRANSFER_MULTIPLE_TOKENS = "transferMultipleTokens";
    private static final ByteString META1 = ByteStringUtils.wrapUnsafely("meta1".getBytes());
    private static final ByteString META2 = ByteStringUtils.wrapUnsafely("meta2".getBytes());
    private static final ByteString META3 = ByteStringUtils.wrapUnsafely("meta3".getBytes());
    private static final ByteString META4 = ByteStringUtils.wrapUnsafely("meta4".getBytes());
    private static final String NFT_TOKEN_WITH_FIXED_HBAR_FEE = "nftTokenWithFixedHbarFee";
    private static final String NFT_TOKEN_WITH_FIXED_TOKEN_FEE = "nftTokenWithFixedTokenFee";
    private static final String NFT_TOKEN_WITH_ROYALTY_FEE_WITH_HBAR_FALLBACK =
            "nftTokenWithRoyaltyFeeWithHbarFallback";
    private static final String NFT_TOKEN_WITH_ROYALTY_FEE_WITH_TOKEN_FALLBACK =
            "nftTokenWithRoyaltyFeeWithTokenFallback";
    private static final String FUNGIBLE_TOKEN_FEE = "fungibleTokenFee";
    private static final String RECEIVER_SIGNATURE = "receiverSignature";
    private static final String APPROVE_TXN = "approveTxn";
    private static final String FIRST_MEMO = "firstMemo";
    private static final String SECOND_MEMO = "secondMemo";
    private static final String CRYPTO_TRANSFER_TXN = "cryptoTransferTxn";
    private static final String SPENDER = "spender";

    @HapiTest
    final Stream<DynamicTest> hapiTransferFromForFungibleToken() {
        final var allowance = 10L;
        final var successfulTransferFromTxn = "txn";
        final var successfulTransferFromTxn2 = "txn2";
        final var revertingTransferFromTxn = "revertWhenMoreThanAllowance";
        final var revertingTransferFromTxn2 = "revertingTxn";
        return hapiTest(
                newKeyNamed(MULTI_KEY),
                cryptoCreate(OWNER).balance(100 * ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(5),
                cryptoCreate(SPENDER).maxAutomaticTokenAssociations(5),
                cryptoCreate(RECEIVER).maxAutomaticTokenAssociations(5),
                tokenCreate(FUNGIBLE_TOKEN)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .supplyType(TokenSupplyType.FINITE)
                        .initialSupply(10L)
                        .maxSupply(1000L)
                        .supplyKey(MULTI_KEY)
                        .treasury(OWNER),
                uploadInitCode(HTS_TRANSFER_FROM_CONTRACT),
                contractCreate(HTS_TRANSFER_FROM_CONTRACT),
                cryptoApproveAllowance()
                        .payingWith(DEFAULT_PAYER)
                        .addTokenAllowance(OWNER, FUNGIBLE_TOKEN, HTS_TRANSFER_FROM_CONTRACT, allowance)
                        .via("baseApproveTxn")
                        .signedBy(DEFAULT_PAYER, OWNER)
                        .fee(ONE_HBAR),
                getAccountDetails(OWNER)
                        .payingWith(GENESIS)
                        .has(accountDetailsWith()
                                .tokenAllowancesContaining(FUNGIBLE_TOKEN, HTS_TRANSFER_FROM_CONTRACT, allowance)),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        // trying to transfer more than allowance should
                        // revert
                        contractCall(
                                        HTS_TRANSFER_FROM_CONTRACT,
                                        HTS_TRANSFER_FROM,
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getTokenID(FUNGIBLE_TOKEN))),
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getAccountID(OWNER))),
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getAccountID(RECEIVER))),
                                        BigInteger.valueOf(allowance + 1))
                                .via(revertingTransferFromTxn)
                                .gas(GAS_FOR_AUTO_ASSOCIATING_CALLS)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED),
                        // transfer allowance/2 amount
                        contractCall(
                                        HTS_TRANSFER_FROM_CONTRACT,
                                        HTS_TRANSFER_FROM,
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getTokenID(FUNGIBLE_TOKEN))),
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getAccountID(OWNER))),
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getAccountID(RECEIVER))),
                                        BigInteger.valueOf(allowance / 2))
                                .via(successfulTransferFromTxn)
                                .gas(GAS_FOR_AUTO_ASSOCIATING_CALLS)
                                .hasKnownStatus(SUCCESS),
                        // transfer the rest of the allowance
                        contractCall(
                                        HTS_TRANSFER_FROM_CONTRACT,
                                        HTS_TRANSFER_FROM,
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getTokenID(FUNGIBLE_TOKEN))),
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getAccountID(OWNER))),
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getAccountID(RECEIVER))),
                                        BigInteger.valueOf(allowance / 2))
                                .via(successfulTransferFromTxn2)
                                .gas(GAS_FOR_AUTO_ASSOCIATING_CALLS)
                                .hasKnownStatus(SUCCESS),
                        getAccountDetails(OWNER)
                                .payingWith(GENESIS)
                                .has(accountDetailsWith().noAllowances()),
                        // no allowance left, should fail
                        contractCall(
                                        HTS_TRANSFER_FROM_CONTRACT,
                                        HTS_TRANSFER_FROM,
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getTokenID(FUNGIBLE_TOKEN))),
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getAccountID(OWNER))),
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getAccountID(RECEIVER))),
                                        BigInteger.ONE)
                                .via(revertingTransferFromTxn2)
                                .gas(GAS_FOR_AUTO_ASSOCIATING_CALLS)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED))),
                childRecordsCheck(
                        revertingTransferFromTxn,
                        CONTRACT_REVERT_EXECUTED,
                        recordWith()
                                .status(AMOUNT_EXCEEDS_ALLOWANCE)
                                .contractCallResult(resultWith()
                                        .contractCallResult(htsPrecompileResult()
                                                .forFunction(FunctionType.HAPI_TRANSFER_FROM)
                                                .withStatus(AMOUNT_EXCEEDS_ALLOWANCE)))),
                childRecordsCheck(
                        successfulTransferFromTxn,
                        SUCCESS,
                        recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith()
                                        .contractCallResult(htsPrecompileResult()
                                                .forFunction(FunctionType.HAPI_TRANSFER_FROM)
                                                .withStatus(SUCCESS)))),
                withOpContext((spec, log) -> {
                    final var idOfToken = toTokenIdString(spec.registry().getTokenID(FUNGIBLE_TOKEN));
                    final var sender = spec.registry().getAccountID(OWNER);
                    final var receiver = spec.registry().getAccountID(RECEIVER);
                    final var txnRecord = getTxnRecord(successfulTransferFromTxn)
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
                                                                    receiver.getAccountNum())))
                                                    .longValue(allowance / 2)))))
                            .andAllChildRecords();
                    allRunFor(spec, txnRecord);
                }),
                childRecordsCheck(
                        successfulTransferFromTxn2,
                        SUCCESS,
                        recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith()
                                        .contractCallResult(htsPrecompileResult()
                                                .forFunction(FunctionType.HAPI_TRANSFER_FROM)
                                                .withStatus(SUCCESS)))),
                withOpContext((spec, log) -> {
                    final var idOfToken = toTokenIdString(spec.registry().getTokenID(FUNGIBLE_TOKEN));
                    final var sender = spec.registry().getAccountID(OWNER);
                    final var receiver = spec.registry().getAccountID(RECEIVER);
                    final var txnRecord = getTxnRecord(successfulTransferFromTxn2)
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
                                                                    receiver.getAccountNum())))
                                                    .longValue(allowance / 2)))))
                            .andAllChildRecords()
                            .logged();
                    allRunFor(spec, txnRecord);
                }),
                childRecordsCheck(
                        revertingTransferFromTxn2,
                        CONTRACT_REVERT_EXECUTED,
                        recordWith()
                                .status(SPENDER_DOES_NOT_HAVE_ALLOWANCE)
                                .contractCallResult(resultWith()
                                        .contractCallResult(htsPrecompileResult()
                                                .forFunction(FunctionType.HAPI_TRANSFER_FROM)
                                                .withStatus(SPENDER_DOES_NOT_HAVE_ALLOWANCE)))));
    }

    @HapiTest
    final Stream<DynamicTest> hapiTransferFromForFungibleTokenToSystemAccountsFails() {
        final var UPPER_BOUND_SYSTEM_ADDRESS = 750L;
        final var ADDRESS_ONE = 1L;
        final var NON_EXISTING_SYSTEM_ADDRESS = 345L;
        final var NON_EXISTING_SYSTEM_ADDRESS_360 = 360L;
        final var TXN_TO_FIRST_ADDRESS = "TXN_TO_FIRST_ADDRESS";
        final var TXN_TO_NON_EXISTING_ADDRESS = "TXN_TO_NON_EXISTING_ADDRESS";
        final var TXN_TO_SYSTEM_ADDRESS_360 = "TXN_TO_SYSTEM_ADDRESS_360";
        final var TXN_TO_UPPER_BOUND_ADDRESS = "TXN_TO_UPPER_BOUND_ADDRESS";

        final var allowance = 10L;
        return hapiTest(
                newKeyNamed(MULTI_KEY),
                cryptoCreate(OWNER).balance(100 * ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(5),
                cryptoCreate(SPENDER).maxAutomaticTokenAssociations(5),
                cryptoCreate(RECEIVER).maxAutomaticTokenAssociations(5),
                tokenCreate(FUNGIBLE_TOKEN)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .supplyType(TokenSupplyType.FINITE)
                        .initialSupply(10L)
                        .maxSupply(1000L)
                        .supplyKey(MULTI_KEY)
                        .treasury(OWNER),
                uploadInitCode(HTS_TRANSFER_FROM_CONTRACT),
                contractCreate(HTS_TRANSFER_FROM_CONTRACT),
                cryptoApproveAllowance()
                        .payingWith(DEFAULT_PAYER)
                        .addTokenAllowance(OWNER, FUNGIBLE_TOKEN, HTS_TRANSFER_FROM_CONTRACT, allowance)
                        .signedBy(DEFAULT_PAYER, OWNER)
                        .fee(ONE_HBAR),
                getAccountDetails(OWNER)
                        .payingWith(GENESIS)
                        .has(accountDetailsWith()
                                .tokenAllowancesContaining(FUNGIBLE_TOKEN, HTS_TRANSFER_FROM_CONTRACT, allowance)),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        // transfer to system account 0.0.750 upper bound
                        contractCall(
                                        HTS_TRANSFER_FROM_CONTRACT,
                                        HTS_TRANSFER_FROM,
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getTokenID(FUNGIBLE_TOKEN))),
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getAccountID(OWNER))),
                                        HapiParserUtil.asHeadlongAddress(asAddress(AccountID.newBuilder()
                                                .setAccountNum(UPPER_BOUND_SYSTEM_ADDRESS)
                                                .build())),
                                        BigInteger.valueOf(allowance / 2))
                                .gas(100_000_00L)
                                .via(TXN_TO_UPPER_BOUND_ADDRESS)
                                .payingWith(GENESIS)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED),
                        // transfer to system account 0.0.451
                        contractCall(
                                        HTS_TRANSFER_FROM_CONTRACT,
                                        HTS_TRANSFER_FROM,
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getTokenID(FUNGIBLE_TOKEN))),
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getAccountID(OWNER))),
                                        HapiParserUtil.asHeadlongAddress(asAddress(AccountID.newBuilder()
                                                .setAccountNum(NON_EXISTING_SYSTEM_ADDRESS)
                                                .build())),
                                        BigInteger.valueOf(allowance / 2))
                                .via(TXN_TO_NON_EXISTING_ADDRESS)
                                .gas(100_000_00L)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED),
                        // transfer to system account 0.0.360
                        contractCall(
                                        HTS_TRANSFER_FROM_CONTRACT,
                                        HTS_TRANSFER_FROM,
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getTokenID(FUNGIBLE_TOKEN))),
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getAccountID(OWNER))),
                                        HapiParserUtil.asHeadlongAddress(asAddress(AccountID.newBuilder()
                                                .setAccountNum(NON_EXISTING_SYSTEM_ADDRESS_360)
                                                .build())),
                                        BigInteger.valueOf(allowance / 2))
                                .via(TXN_TO_SYSTEM_ADDRESS_360)
                                .gas(100_000_00L)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED),
                        // transfer to system account 0.0.0 lower bound
                        contractCall(
                                        HTS_TRANSFER_FROM_CONTRACT,
                                        HTS_TRANSFER_FROM,
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getTokenID(FUNGIBLE_TOKEN))),
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getAccountID(OWNER))),
                                        HapiParserUtil.asHeadlongAddress(asAddress(AccountID.newBuilder()
                                                .setAccountNum(ADDRESS_ONE)
                                                .build())),
                                        BigInteger.valueOf(allowance / 2))
                                .gas(100_000_00L)
                                .via(TXN_TO_FIRST_ADDRESS)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED))),
                childRecordsCheck(
                        TXN_TO_UPPER_BOUND_ADDRESS,
                        CONTRACT_REVERT_EXECUTED,
                        recordWith().status(INVALID_RECEIVING_NODE_ACCOUNT)),
                childRecordsCheck(
                        TXN_TO_FIRST_ADDRESS,
                        CONTRACT_REVERT_EXECUTED,
                        recordWith().status(INVALID_RECEIVING_NODE_ACCOUNT)),
                childRecordsCheck(
                        TXN_TO_SYSTEM_ADDRESS_360,
                        CONTRACT_REVERT_EXECUTED,
                        recordWith().status(INVALID_RECEIVING_NODE_ACCOUNT)),
                childRecordsCheck(
                        TXN_TO_NON_EXISTING_ADDRESS,
                        CONTRACT_REVERT_EXECUTED,
                        recordWith().status(INVALID_RECEIVING_NODE_ACCOUNT)));
    }

    @HapiTest
    final Stream<DynamicTest> hapiTransferFromForNFTWithInvalidAddressesFails() {
        final var NON_EXISTING_CONTACT_NUM = 123456;
        final var TXN_TO_NON_EXISTING_ADDRESS = "TXN_TO_NON_EXISTING_ADDRESS";
        final var TXN_FROM_NON_EXISTING_ADDRESS = "TXN_FROM_NON_EXISTING_ADDRESS";
        final var TXN_WITH_NON_EXISTING_NFT = "TXN_WITH_NON_EXISTING_NFT";

        return hapiTest(
                newKeyNamed(MULTI_KEY),
                cryptoCreate(OWNER).balance(100 * ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(5),
                cryptoCreate(RECEIVER).maxAutomaticTokenAssociations(5),
                tokenCreate(NFT_TOKEN)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .treasury(OWNER)
                        .initialSupply(0L)
                        .supplyKey(MULTI_KEY),
                uploadInitCode(HTS_TRANSFER_FROM_CONTRACT),
                contractCreate(HTS_TRANSFER_FROM_CONTRACT),
                mintToken(NFT_TOKEN, List.of(META1, META2)),
                cryptoApproveAllowance()
                        .payingWith(DEFAULT_PAYER)
                        .addNftAllowance(OWNER, NFT_TOKEN, HTS_TRANSFER_FROM_CONTRACT, false, List.of(2L))
                        .signedBy(DEFAULT_PAYER, OWNER)
                        .fee(ONE_HBAR),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        // transfer TO address that does not exist
                        contractCall(
                                        HTS_TRANSFER_FROM_CONTRACT,
                                        HTS_TRANSFER_FROM_NFT,
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getTokenID(NFT_TOKEN))),
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getAccountID(OWNER))),
                                        HapiParserUtil.asHeadlongAddress(
                                                asSolidityAddress(spec, NON_EXISTING_CONTACT_NUM)),
                                        BigInteger.ONE)
                                .gas(100_000_00L)
                                .via(TXN_TO_NON_EXISTING_ADDRESS)
                                .payingWith(GENESIS)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED),
                        // transfer FROM address that does not exist
                        contractCall(
                                        HTS_TRANSFER_FROM_CONTRACT,
                                        HTS_TRANSFER_FROM_NFT,
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getTokenID(NFT_TOKEN))),
                                        HapiParserUtil.asHeadlongAddress(
                                                asSolidityAddress(spec, NON_EXISTING_CONTACT_NUM)),
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getAccountID(RECEIVER))),
                                        BigInteger.ONE)
                                .gas(100_000_00L)
                                .via(TXN_FROM_NON_EXISTING_ADDRESS)
                                .payingWith(GENESIS)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED),
                        // transfer with nft address that does not exist
                        contractCall(
                                        HTS_TRANSFER_FROM_CONTRACT,
                                        HTS_TRANSFER_FROM_NFT,
                                        HapiParserUtil.asHeadlongAddress(new byte[20]),
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getAccountID(OWNER))),
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getAccountID(RECEIVER))),
                                        BigInteger.ONE)
                                .gas(100_000_00L)
                                .via(TXN_WITH_NON_EXISTING_NFT)
                                .payingWith(GENESIS)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED))),
                childRecordsCheck(
                        TXN_TO_NON_EXISTING_ADDRESS,
                        CONTRACT_REVERT_EXECUTED,
                        recordWith().status(INVALID_ALIAS_KEY)),
                childRecordsCheck(
                        TXN_FROM_NON_EXISTING_ADDRESS,
                        CONTRACT_REVERT_EXECUTED,
                        recordWith().status(INVALID_ACCOUNT_ID)),
                childRecordsCheck(
                        TXN_WITH_NON_EXISTING_NFT,
                        CONTRACT_REVERT_EXECUTED,
                        recordWith().status(INVALID_TOKEN_ID)));
    }

    @HapiTest
    final Stream<DynamicTest> hapiTransferFromForFungibleTokenWithInvalidAddressesFails() {
        final var NON_EXISTING_CONTACT_NUM = 123456;
        final var TXN_TO_NON_EXISTING_ADDRESS = "TXN_TO_NON_EXISTING_ADDRESS";
        final var TXN_FROM_NON_EXISTING_ADDRESS = "TXN_FROM_NON_EXISTING_ADDRESS";
        final var TXN_WITH_NON_EXISTING_TOKEN = "TXN_WITH_NON_EXISTING_TOKEN";

        return hapiTest(
                newKeyNamed(MULTI_KEY),
                cryptoCreate(OWNER).balance(100 * ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(5),
                cryptoCreate(RECEIVER).maxAutomaticTokenAssociations(5),
                tokenCreate(FUNGIBLE_TOKEN)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .supplyType(TokenSupplyType.FINITE)
                        .initialSupply(10L)
                        .maxSupply(1000L)
                        .supplyKey(MULTI_KEY)
                        .treasury(OWNER),
                uploadInitCode(HTS_TRANSFER_FROM_CONTRACT),
                contractCreate(HTS_TRANSFER_FROM_CONTRACT),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        // transfer TO address that does not exist
                        contractCall(
                                        HTS_TRANSFER_FROM_CONTRACT,
                                        HTS_TRANSFER_FROM,
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getTokenID(FUNGIBLE_TOKEN))),
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getAccountID(OWNER))),
                                        HapiParserUtil.asHeadlongAddress(
                                                asSolidityAddress(spec, NON_EXISTING_CONTACT_NUM)),
                                        BigInteger.valueOf(5L))
                                .gas(100_000_00L)
                                .via(TXN_TO_NON_EXISTING_ADDRESS)
                                .payingWith(GENESIS)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED),
                        // transfer FROM address that does not exist
                        contractCall(
                                        HTS_TRANSFER_FROM_CONTRACT,
                                        HTS_TRANSFER_FROM,
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getTokenID(FUNGIBLE_TOKEN))),
                                        HapiParserUtil.asHeadlongAddress(
                                                asSolidityAddress(spec, NON_EXISTING_CONTACT_NUM)),
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getAccountID(RECEIVER))),
                                        BigInteger.valueOf(5L))
                                .gas(100_000_00L)
                                .via(TXN_FROM_NON_EXISTING_ADDRESS)
                                .payingWith(GENESIS)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED),
                        // transfer with token address that does not exist
                        contractCall(
                                        HTS_TRANSFER_FROM_CONTRACT,
                                        HTS_TRANSFER_FROM,
                                        HapiParserUtil.asHeadlongAddress(new byte[20]),
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getAccountID(OWNER))),
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getAccountID(RECEIVER))),
                                        BigInteger.valueOf(5L))
                                .gas(100_000_00L)
                                .via(TXN_WITH_NON_EXISTING_TOKEN)
                                .payingWith(GENESIS)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED))),
                childRecordsCheck(
                        TXN_TO_NON_EXISTING_ADDRESS,
                        CONTRACT_REVERT_EXECUTED,
                        recordWith().status(INVALID_ALIAS_KEY)),
                childRecordsCheck(
                        TXN_FROM_NON_EXISTING_ADDRESS,
                        CONTRACT_REVERT_EXECUTED,
                        recordWith().status(INVALID_ACCOUNT_ID)),
                childRecordsCheck(
                        TXN_WITH_NON_EXISTING_TOKEN,
                        CONTRACT_REVERT_EXECUTED,
                        recordWith().status(INVALID_TOKEN_ID)));
    }

    @HapiTest
    final Stream<DynamicTest> hapiTransferFromForFungibleTokenWithInvalidAmountsFails() {
        final var TXN_WITH_AMOUNT_BIGGER_THAN_BALANCE = "TXN_WITH_AMOUNT_BIGGER_THAN_BALANCE";
        final var TXN_WITH_AMOUNT_BIGGER_THAN_ALLOWANCE = "TXN_WITH_AMOUNT_BIGGER_THAN_ALLOWANCE";
        final var TXN_WITH_AMOUNT_UNDERFLOW_UINT = "TXN_WITH_AMOUNT_UNDERFLOW_UINT";
        final var TXN_WITH_AMOUNT_OVERFLOW_UINT = "TXN_WITH_AMOUNT_OVERFLOW_UINT";

        final var allowance = 10L;
        return hapiTest(
                newKeyNamed(MULTI_KEY),
                cryptoCreate(OWNER).balance(100 * ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(5),
                cryptoCreate(SPENDER).maxAutomaticTokenAssociations(5),
                cryptoCreate(RECEIVER).maxAutomaticTokenAssociations(5),
                tokenCreate(FUNGIBLE_TOKEN)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .supplyType(TokenSupplyType.FINITE)
                        .initialSupply(15L)
                        .maxSupply(1000L)
                        .supplyKey(MULTI_KEY)
                        .treasury(OWNER),
                uploadInitCode(HTS_TRANSFER_FROM_CONTRACT),
                contractCreate(HTS_TRANSFER_FROM_CONTRACT),
                uploadInitCode(NEGATIVE_HTS_TRANSFER_FROM_CONTRACT),
                contractCreate(NEGATIVE_HTS_TRANSFER_FROM_CONTRACT),
                cryptoApproveAllowance()
                        .payingWith(DEFAULT_PAYER)
                        .addTokenAllowance(OWNER, FUNGIBLE_TOKEN, HTS_TRANSFER_FROM_CONTRACT, allowance)
                        .signedBy(DEFAULT_PAYER, OWNER)
                        .fee(ONE_HBAR),
                getAccountDetails(OWNER)
                        .payingWith(GENESIS)
                        .has(accountDetailsWith()
                                .tokenAllowancesContaining(FUNGIBLE_TOKEN, HTS_TRANSFER_FROM_CONTRACT, allowance)),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        // transfer with amount > allowance for the caller
                        contractCall(
                                        HTS_TRANSFER_FROM_CONTRACT,
                                        HTS_TRANSFER_FROM,
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getTokenID(FUNGIBLE_TOKEN))),
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getAccountID(OWNER))),
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getAccountID(RECEIVER))),
                                        BigInteger.valueOf(allowance + 1))
                                .gas(100_000_00L)
                                .via(TXN_WITH_AMOUNT_BIGGER_THAN_ALLOWANCE)
                                .payingWith(GENESIS)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED),
                        // successful transfer to reduce balance
                        cryptoTransfer(moving(10L, FUNGIBLE_TOKEN).between(OWNER, SPENDER)),
                        // transfer with amount > balance of owner account
                        contractCall(
                                        HTS_TRANSFER_FROM_CONTRACT,
                                        HTS_TRANSFER_FROM,
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getTokenID(FUNGIBLE_TOKEN))),
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getAccountID(OWNER))),
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getAccountID(RECEIVER))),
                                        BigInteger.valueOf(6L))
                                .gas(100_000_00L)
                                .via(TXN_WITH_AMOUNT_BIGGER_THAN_BALANCE)
                                .payingWith(GENESIS)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED),
                        // try transfer with underflow (negative) amount for the uint256 type
                        contractCall(
                                        NEGATIVE_HTS_TRANSFER_FROM_CONTRACT,
                                        HTS_TRANSFER_FROM_UNDERFLOW,
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getTokenID(FUNGIBLE_TOKEN))),
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getAccountID(OWNER))),
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getAccountID(RECEIVER))))
                                .gas(100_000_00L)
                                .via(TXN_WITH_AMOUNT_UNDERFLOW_UINT)
                                .payingWith(GENESIS)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED),
                        emptyChildRecordsCheck(TXN_WITH_AMOUNT_UNDERFLOW_UINT, CONTRACT_REVERT_EXECUTED),
                        // try transfer with overflow amount for the uint256 type
                        contractCall(
                                        NEGATIVE_HTS_TRANSFER_FROM_CONTRACT,
                                        HTS_TRANSFER_FROM_OVERFLOW,
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getTokenID(FUNGIBLE_TOKEN))),
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getAccountID(OWNER))),
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getAccountID(RECEIVER))))
                                .gas(100_000_00L)
                                .via(TXN_WITH_AMOUNT_OVERFLOW_UINT)
                                .payingWith(GENESIS)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED),
                        emptyChildRecordsCheck(TXN_WITH_AMOUNT_OVERFLOW_UINT, CONTRACT_REVERT_EXECUTED))),
                childRecordsCheck(
                        TXN_WITH_AMOUNT_BIGGER_THAN_ALLOWANCE,
                        CONTRACT_REVERT_EXECUTED,
                        recordWith().status(AMOUNT_EXCEEDS_ALLOWANCE)),
                childRecordsCheck(
                        TXN_WITH_AMOUNT_BIGGER_THAN_BALANCE,
                        CONTRACT_REVERT_EXECUTED,
                        recordWith().status(INSUFFICIENT_TOKEN_BALANCE)));
    }

    @HapiTest
    final Stream<DynamicTest> hapiTransferFromForNFT() {
        final var successfulTransferFromTxn = "txn";
        final var revertingTransferFromTxn = "revertWhenMoreThanAllowance";
        return hapiTest(
                newKeyNamed(MULTI_KEY),
                cryptoCreate(OWNER).balance(100 * ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(5),
                cryptoCreate(SPENDER).maxAutomaticTokenAssociations(5),
                cryptoCreate(RECEIVER).maxAutomaticTokenAssociations(5),
                tokenCreate(NFT_TOKEN)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .treasury(OWNER)
                        .initialSupply(0L)
                        .supplyKey(MULTI_KEY),
                uploadInitCode(HTS_TRANSFER_FROM_CONTRACT),
                contractCreate(HTS_TRANSFER_FROM_CONTRACT),
                mintToken(NFT_TOKEN, List.of(META1, META2)),
                cryptoApproveAllowance()
                        .payingWith(DEFAULT_PAYER)
                        .addNftAllowance(OWNER, NFT_TOKEN, HTS_TRANSFER_FROM_CONTRACT, false, List.of(2L))
                        .via("baseApproveTxn")
                        .signedBy(DEFAULT_PAYER, OWNER)
                        .fee(ONE_HBAR),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        // trying to transfer NFT that is not approved
                        contractCall(
                                        HTS_TRANSFER_FROM_CONTRACT,
                                        HTS_TRANSFER_FROM_NFT,
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getTokenID(NFT_TOKEN))),
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getAccountID(OWNER))),
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getAccountID(RECEIVER))),
                                        BigInteger.ONE)
                                .via(revertingTransferFromTxn)
                                .gas(GAS_FOR_AUTO_ASSOCIATING_CALLS)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED),
                        // transfer allowed NFT
                        contractCall(
                                        HTS_TRANSFER_FROM_CONTRACT,
                                        HTS_TRANSFER_FROM_NFT,
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getTokenID(NFT_TOKEN))),
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getAccountID(OWNER))),
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getAccountID(RECEIVER))),
                                        BigInteger.TWO)
                                .via(successfulTransferFromTxn)
                                .gas(GAS_FOR_AUTO_ASSOCIATING_CALLS)
                                .hasKnownStatus(SUCCESS))),
                childRecordsCheck(
                        revertingTransferFromTxn,
                        CONTRACT_REVERT_EXECUTED,
                        recordWith()
                                .status(SPENDER_DOES_NOT_HAVE_ALLOWANCE)
                                .contractCallResult(resultWith()
                                        .contractCallResult(htsPrecompileResult()
                                                .forFunction(FunctionType.HAPI_TRANSFER_FROM_NFT)
                                                .withStatus(SPENDER_DOES_NOT_HAVE_ALLOWANCE)))),
                childRecordsCheck(
                        successfulTransferFromTxn,
                        SUCCESS,
                        recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith()
                                        .contractCallResult(htsPrecompileResult()
                                                .forFunction(FunctionType.HAPI_TRANSFER_FROM_NFT)
                                                .withStatus(SUCCESS)))),
                withOpContext((spec, log) -> {
                    final var idOfToken = toTokenIdString(spec.registry().getTokenID(NFT_TOKEN));
                    final var sender = spec.registry().getAccountID(OWNER);
                    final var receiver = spec.registry().getAccountID(RECEIVER);
                    final var txnRecord = getTxnRecord(successfulTransferFromTxn)
                            .logged()
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
                                                            parsedToByteString(0, 0, 2L)))))))
                            .andAllChildRecords()
                            .logged();
                    allRunFor(spec, txnRecord);
                }));
    }

    @HapiTest
    final Stream<DynamicTest> repeatedTokenIdsAreAutomaticallyConsolidated() {
        final var repeatedIdsPrecompileXferTxn = "repeatedIdsPrecompileXfer";
        final var senderStartBalance = 200L;
        final var receiverStartBalance = 0L;
        final var toSendEachTuple = 50L;

        return hapiTest(
                cryptoCreate(SENDER).balance(10 * ONE_HUNDRED_HBARS),
                cryptoCreate(RECEIVER).balance(2 * ONE_HUNDRED_HBARS).receiverSigRequired(true),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(FUNGIBLE_TOKEN)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .initialSupply(TOTAL_SUPPLY)
                        .treasury(TOKEN_TREASURY),
                tokenAssociate(SENDER, List.of(FUNGIBLE_TOKEN)),
                tokenAssociate(RECEIVER, List.of(FUNGIBLE_TOKEN)),
                cryptoTransfer(moving(senderStartBalance, FUNGIBLE_TOKEN).between(TOKEN_TREASURY, SENDER)),
                uploadInitCode(CONTRACT),
                contractCreate(CONTRACT),
                withOpContext((spec, opLog) -> {
                    final var token = spec.registry().getTokenID(FUNGIBLE_TOKEN);
                    final var sender = spec.registry().getAccountID(SENDER);
                    final var receiver = spec.registry().getAccountID(RECEIVER);

                    allRunFor(
                            spec,
                            newKeyNamed(DELEGATE_KEY).shape(DELEGATE_CONTRACT_KEY_SHAPE.signedWith(sigs(ON, CONTRACT))),
                            cryptoUpdate(SENDER).key(DELEGATE_KEY),
                            cryptoUpdate(RECEIVER).key(DELEGATE_KEY),
                            contractCall(CONTRACT, TRANSFER_MULTIPLE_TOKENS, (Object) new Tuple[] {
                                        tokenTransferList()
                                                .forToken(token)
                                                .withAccountAmounts(
                                                        accountAmount(sender, -toSendEachTuple),
                                                        accountAmount(receiver, toSendEachTuple))
                                                .build(),
                                        tokenTransferList()
                                                .forToken(token)
                                                .withAccountAmounts(
                                                        accountAmount(sender, -toSendEachTuple),
                                                        accountAmount(receiver, toSendEachTuple))
                                                .build()
                                    })
                                    .payingWith(GENESIS)
                                    .via(repeatedIdsPrecompileXferTxn)
                                    .gas(GAS_TO_OFFER));
                }),
                getTxnRecord(repeatedIdsPrecompileXferTxn).andAllChildRecords(),
                getAccountBalance(RECEIVER).hasTokenBalance(FUNGIBLE_TOKEN, receiverStartBalance + 2 * toSendEachTuple),
                getAccountBalance(SENDER).hasTokenBalance(FUNGIBLE_TOKEN, senderStartBalance - 2 * toSendEachTuple),
                childRecordsCheck(
                        repeatedIdsPrecompileXferTxn,
                        SUCCESS,
                        recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith()
                                        .contractCallResult(
                                                htsPrecompileResult().withStatus(SUCCESS)))
                                .tokenTransfers(SomeFungibleTransfers.changingFungibleBalances()
                                        .including(FUNGIBLE_TOKEN, SENDER, -2 * toSendEachTuple)
                                        .including(FUNGIBLE_TOKEN, RECEIVER, 2 * toSendEachTuple))));
    }

    @HapiTest
    final Stream<DynamicTest> nonNestedCryptoTransferForFungibleTokenWithMultipleReceivers() {
        final var cryptoTransferTxn = CRYPTO_TRANSFER_TXN;

        return hapiTest(
                cryptoCreate(SENDER).balance(10 * ONE_HUNDRED_HBARS),
                cryptoCreate(RECEIVER).balance(2 * ONE_HUNDRED_HBARS).receiverSigRequired(true),
                cryptoCreate(RECEIVER2).balance(ONE_HUNDRED_HBARS).receiverSigRequired(true),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(FUNGIBLE_TOKEN)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .initialSupply(TOTAL_SUPPLY)
                        .treasury(TOKEN_TREASURY),
                tokenAssociate(SENDER, List.of(FUNGIBLE_TOKEN)),
                tokenAssociate(RECEIVER, List.of(FUNGIBLE_TOKEN)),
                tokenAssociate(RECEIVER2, List.of(FUNGIBLE_TOKEN)),
                cryptoTransfer(moving(200, FUNGIBLE_TOKEN).between(TOKEN_TREASURY, SENDER))
                        .payingWith(SENDER),
                uploadInitCode(CONTRACT),
                contractCreate(CONTRACT),
                withOpContext((spec, opLog) -> {
                    final var token = spec.registry().getTokenID(FUNGIBLE_TOKEN);
                    final var sender = spec.registry().getAccountID(SENDER);
                    final var receiver = spec.registry().getAccountID(RECEIVER);
                    final var receiver2 = spec.registry().getAccountID(RECEIVER2);

                    allRunFor(
                            spec,
                            newKeyNamed(DELEGATE_KEY).shape(DELEGATE_CONTRACT_KEY_SHAPE.signedWith(sigs(ON, CONTRACT))),
                            cryptoUpdate(SENDER).key(DELEGATE_KEY),
                            cryptoUpdate(RECEIVER).key(DELEGATE_KEY),
                            cryptoUpdate(RECEIVER2).key(DELEGATE_KEY),
                            contractCall(CONTRACT, TRANSFER_MULTIPLE_TOKENS, (Object) new Tuple[] {
                                        tokenTransferList()
                                                .forToken(token)
                                                .withAccountAmounts(
                                                        accountAmount(sender, -50L),
                                                        accountAmount(receiver, 30L),
                                                        accountAmount(receiver2, 20L))
                                                .build()
                                    })
                                    .gas(GAS_TO_OFFER)
                                    .payingWith(GENESIS)
                                    .via(cryptoTransferTxn));
                }),
                getTxnRecord(cryptoTransferTxn).andAllChildRecords(),
                getTokenInfo(FUNGIBLE_TOKEN).hasTotalSupply(TOTAL_SUPPLY),
                getAccountBalance(RECEIVER).hasTokenBalance(FUNGIBLE_TOKEN, 30),
                getAccountBalance(RECEIVER2).hasTokenBalance(FUNGIBLE_TOKEN, 20),
                getAccountBalance(SENDER).hasTokenBalance(FUNGIBLE_TOKEN, 150),
                getTokenInfo(FUNGIBLE_TOKEN),
                childRecordsCheck(
                        cryptoTransferTxn,
                        SUCCESS,
                        recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith()
                                        .contractCallResult(
                                                htsPrecompileResult().withStatus(SUCCESS)))
                                .tokenTransfers(SomeFungibleTransfers.changingFungibleBalances()
                                        .including(FUNGIBLE_TOKEN, SENDER, -50)
                                        .including(FUNGIBLE_TOKEN, RECEIVER, 30)
                                        .including(FUNGIBLE_TOKEN, RECEIVER2, 20))));
    }

    @HapiTest
    final Stream<DynamicTest> nonNestedCryptoTransferForNonFungibleToken() {
        final var cryptoTransferTxn = CRYPTO_TRANSFER_TXN;

        return hapiTest(
                newKeyNamed(MULTI_KEY),
                cryptoCreate(SENDER).balance(10 * ONE_HUNDRED_HBARS),
                cryptoCreate(RECEIVER).receiverSigRequired(true),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(NFT_TOKEN)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .adminKey(MULTI_KEY)
                        .supplyKey(MULTI_KEY)
                        .supplyType(TokenSupplyType.INFINITE)
                        .initialSupply(0)
                        .treasury(TOKEN_TREASURY),
                tokenAssociate(SENDER, List.of(NFT_TOKEN)),
                mintToken(NFT_TOKEN, List.of(metadata(FIRST_MEMO), metadata(SECOND_MEMO))),
                tokenAssociate(RECEIVER, List.of(NFT_TOKEN)),
                cryptoTransfer(TokenMovement.movingUnique(NFT_TOKEN, 1).between(TOKEN_TREASURY, SENDER))
                        .payingWith(SENDER),
                uploadInitCode(CONTRACT),
                contractCreate(CONTRACT),
                withOpContext((spec, opLog) -> {
                    final var token = spec.registry().getTokenID(NFT_TOKEN);
                    final var sender = spec.registry().getAccountID(SENDER);
                    final var receiver = spec.registry().getAccountID(RECEIVER);

                    allRunFor(
                            spec,
                            newKeyNamed(DELEGATE_KEY).shape(DELEGATE_CONTRACT_KEY_SHAPE.signedWith(sigs(ON, CONTRACT))),
                            cryptoUpdate(SENDER).key(DELEGATE_KEY),
                            cryptoUpdate(RECEIVER).key(DELEGATE_KEY),
                            contractCall(CONTRACT, TRANSFER_MULTIPLE_TOKENS, (Object) new Tuple[] {
                                        tokenTransferList()
                                                .forToken(token)
                                                .withNftTransfers(nftTransfer(sender, receiver, 1L))
                                                .build()
                                    })
                                    .payingWith(GENESIS)
                                    .via(cryptoTransferTxn)
                                    .gas(GAS_TO_OFFER));
                }),
                getTxnRecord(cryptoTransferTxn).andAllChildRecords(),
                getTokenInfo(NFT_TOKEN).hasTotalSupply(2),
                getAccountInfo(RECEIVER).hasOwnedNfts(1),
                getAccountBalance(RECEIVER).hasTokenBalance(NFT_TOKEN, 1),
                getAccountInfo(SENDER).hasOwnedNfts(0),
                getAccountBalance(SENDER).hasTokenBalance(NFT_TOKEN, 0),
                getTokenInfo(NFT_TOKEN),
                childRecordsCheck(
                        CRYPTO_TRANSFER_TXN,
                        SUCCESS,
                        recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith()
                                        .contractCallResult(
                                                htsPrecompileResult().withStatus(SUCCESS)))
                                .tokenTransfers(NonFungibleTransfers.changingNFTBalances()
                                        .including(NFT_TOKEN, SENDER, RECEIVER, 1L))));
    }

    @HapiTest
    final Stream<DynamicTest> nonNestedCryptoTransferForMultipleNonFungibleTokens() {
        final var cryptoTransferTxn = CRYPTO_TRANSFER_TXN;

        return hapiTest(
                newKeyNamed(MULTI_KEY),
                cryptoCreate(SENDER).balance(10 * ONE_HUNDRED_HBARS),
                cryptoCreate(SENDER2).balance(5 * ONE_HUNDRED_HBARS),
                cryptoCreate(RECEIVER).receiverSigRequired(true),
                cryptoCreate(RECEIVER2).receiverSigRequired(true),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(NFT_TOKEN)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .adminKey(MULTI_KEY)
                        .supplyKey(MULTI_KEY)
                        .supplyType(TokenSupplyType.INFINITE)
                        .initialSupply(0)
                        .treasury(TOKEN_TREASURY),
                tokenAssociate(SENDER, List.of(NFT_TOKEN)),
                tokenAssociate(SENDER2, List.of(NFT_TOKEN)),
                mintToken(NFT_TOKEN, List.of(metadata(FIRST_MEMO), metadata(SECOND_MEMO))),
                tokenAssociate(RECEIVER, List.of(NFT_TOKEN)),
                tokenAssociate(RECEIVER2, List.of(NFT_TOKEN)),
                cryptoTransfer(movingUnique(NFT_TOKEN, 1).between(TOKEN_TREASURY, SENDER))
                        .payingWith(SENDER),
                cryptoTransfer(TokenMovement.movingUnique(NFT_TOKEN, 2).between(TOKEN_TREASURY, SENDER2))
                        .payingWith(SENDER2),
                uploadInitCode(CONTRACT),
                contractCreate(CONTRACT),
                withOpContext((spec, opLog) -> {
                    final var token = spec.registry().getTokenID(NFT_TOKEN);
                    final var sender = spec.registry().getAccountID(SENDER);
                    final var sender2 = spec.registry().getAccountID(SENDER2);
                    final var receiver = spec.registry().getAccountID(RECEIVER);
                    final var receiver2 = spec.registry().getAccountID(RECEIVER2);

                    allRunFor(
                            spec,
                            newKeyNamed(DELEGATE_KEY).shape(DELEGATE_CONTRACT_KEY_SHAPE.signedWith(sigs(ON, CONTRACT))),
                            cryptoUpdate(SENDER).key(DELEGATE_KEY),
                            cryptoUpdate(SENDER2).key(DELEGATE_KEY),
                            cryptoUpdate(RECEIVER).key(DELEGATE_KEY),
                            cryptoUpdate(RECEIVER2).key(DELEGATE_KEY),
                            contractCall(CONTRACT, TRANSFER_MULTIPLE_TOKENS, (Object) new Tuple[] {
                                        tokenTransferList()
                                                .forToken(token)
                                                .withNftTransfers(
                                                        nftTransfer(sender, receiver, 1L),
                                                        nftTransfer(sender2, receiver2, 2L))
                                                .build()
                                    })
                                    .payingWith(GENESIS)
                                    .via(cryptoTransferTxn)
                                    .gas(GAS_TO_OFFER));
                }),
                getTxnRecord(cryptoTransferTxn).andAllChildRecords(),
                getTokenInfo(NFT_TOKEN).hasTotalSupply(2),
                getAccountInfo(RECEIVER).hasOwnedNfts(1),
                getAccountBalance(RECEIVER).hasTokenBalance(NFT_TOKEN, 1),
                getAccountInfo(SENDER).hasOwnedNfts(0),
                getAccountBalance(SENDER).hasTokenBalance(NFT_TOKEN, 0),
                getAccountInfo(RECEIVER2).hasOwnedNfts(1),
                getAccountBalance(RECEIVER2).hasTokenBalance(NFT_TOKEN, 1),
                getAccountInfo(SENDER2).hasOwnedNfts(0),
                getAccountBalance(SENDER2).hasTokenBalance(NFT_TOKEN, 0),
                getTokenInfo(NFT_TOKEN).logged(),
                childRecordsCheck(
                        cryptoTransferTxn,
                        SUCCESS,
                        recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith()
                                        .contractCallResult(
                                                htsPrecompileResult().withStatus(SUCCESS)))
                                .tokenTransfers(NonFungibleTransfers.changingNFTBalances()
                                        .including(NFT_TOKEN, SENDER, RECEIVER, 1L)
                                        .including(NFT_TOKEN, SENDER2, RECEIVER2, 2L))));
    }

    @HapiTest
    final Stream<DynamicTest> nonNestedCryptoTransferForFungibleAndNonFungibleToken() {
        final var cryptoTransferTxn = CRYPTO_TRANSFER_TXN;

        return hapiTest(
                newKeyNamed(MULTI_KEY),
                cryptoCreate(SENDER).balance(10 * ONE_HUNDRED_HBARS),
                cryptoCreate(RECEIVER).receiverSigRequired(true),
                cryptoCreate(SENDER2).balance(5 * ONE_HUNDRED_HBARS),
                cryptoCreate(RECEIVER2).receiverSigRequired(true),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(FUNGIBLE_TOKEN)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .initialSupply(TOTAL_SUPPLY)
                        .treasury(TOKEN_TREASURY),
                tokenCreate(NFT_TOKEN)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .adminKey(MULTI_KEY)
                        .supplyKey(MULTI_KEY)
                        .supplyType(TokenSupplyType.INFINITE)
                        .initialSupply(0)
                        .treasury(TOKEN_TREASURY),
                tokenAssociate(SENDER, List.of(FUNGIBLE_TOKEN)),
                tokenAssociate(SENDER2, List.of(NFT_TOKEN)),
                mintToken(NFT_TOKEN, List.of(metadata(FIRST_MEMO), metadata(SECOND_MEMO))),
                tokenAssociate(RECEIVER, List.of(FUNGIBLE_TOKEN)),
                tokenAssociate(RECEIVER2, List.of(NFT_TOKEN)),
                cryptoTransfer(moving(200, FUNGIBLE_TOKEN).between(TOKEN_TREASURY, SENDER))
                        .payingWith(SENDER),
                cryptoTransfer(TokenMovement.movingUnique(NFT_TOKEN, 1).between(TOKEN_TREASURY, SENDER2))
                        .payingWith(SENDER2),
                uploadInitCode(CONTRACT),
                contractCreate(CONTRACT),
                withOpContext((spec, opLog) -> {
                    final var fungibleToken = spec.registry().getTokenID(FUNGIBLE_TOKEN);
                    final var nonFungibleToken = spec.registry().getTokenID(NFT_TOKEN);
                    final var fungibleTokenSender = spec.registry().getAccountID(SENDER);
                    final var fungibleTokenReceiver = spec.registry().getAccountID(RECEIVER);
                    final var nonFungibleTokenSender = spec.registry().getAccountID(SENDER2);
                    final var nonFungibleTokenReceiver = spec.registry().getAccountID(RECEIVER2);

                    allRunFor(
                            spec,
                            newKeyNamed(DELEGATE_KEY).shape(DELEGATE_CONTRACT_KEY_SHAPE.signedWith(sigs(ON, CONTRACT))),
                            cryptoUpdate(SENDER).key(DELEGATE_KEY),
                            cryptoUpdate(SENDER2).key(DELEGATE_KEY),
                            cryptoUpdate(RECEIVER).key(DELEGATE_KEY),
                            cryptoUpdate(RECEIVER2).key(DELEGATE_KEY),
                            contractCall(
                                            CONTRACT,
                                            TRANSFER_MULTIPLE_TOKENS,
                                            tokenTransferLists()
                                                    .withTokenTransferList(
                                                            tokenTransferList()
                                                                    .forToken(fungibleToken)
                                                                    .withAccountAmounts(
                                                                            accountAmount(fungibleTokenSender, -45L),
                                                                            accountAmount(fungibleTokenReceiver, 45L))
                                                                    .build(),
                                                            tokenTransferList()
                                                                    .forToken(nonFungibleToken)
                                                                    .withNftTransfers(
                                                                            nftTransfer(
                                                                                    nonFungibleTokenSender,
                                                                                    nonFungibleTokenReceiver,
                                                                                    1L))
                                                                    .build())
                                                    .build())
                                    .payingWith(GENESIS)
                                    .via(cryptoTransferTxn)
                                    .gas(GAS_TO_OFFER));
                }),
                getTxnRecord(cryptoTransferTxn).andAllChildRecords(),
                getTokenInfo(FUNGIBLE_TOKEN).hasTotalSupply(TOTAL_SUPPLY),
                getAccountBalance(RECEIVER).hasTokenBalance(FUNGIBLE_TOKEN, 45),
                getAccountBalance(SENDER).hasTokenBalance(FUNGIBLE_TOKEN, 155),
                getTokenInfo(FUNGIBLE_TOKEN).logged(),
                getTokenInfo(NFT_TOKEN).hasTotalSupply(2),
                getAccountInfo(RECEIVER2).hasOwnedNfts(1),
                getAccountBalance(RECEIVER2).hasTokenBalance(NFT_TOKEN, 1),
                getAccountInfo(SENDER2).hasOwnedNfts(0),
                getAccountBalance(SENDER2).hasTokenBalance(NFT_TOKEN, 0),
                getTokenInfo(NFT_TOKEN).logged(),
                childRecordsCheck(
                        cryptoTransferTxn,
                        SUCCESS,
                        recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith()
                                        .contractCallResult(
                                                htsPrecompileResult().withStatus(SUCCESS)))
                                .tokenTransfers(SomeFungibleTransfers.changingFungibleBalances()
                                        .including(FUNGIBLE_TOKEN, SENDER, -45L)
                                        .including(FUNGIBLE_TOKEN, RECEIVER, 45L))
                                .tokenTransfers(NonFungibleTransfers.changingNFTBalances()
                                        .including(NFT_TOKEN, SENDER2, RECEIVER2, 1L))));
    }

    @HapiTest
    final Stream<DynamicTest>
            nonNestedCryptoTransferForFungibleTokenWithMultipleSendersAndReceiversAndNonFungibleTokens() {
        final var cryptoTransferTxn = CRYPTO_TRANSFER_TXN;

        return hapiTest(
                newKeyNamed(MULTI_KEY),
                cryptoCreate(SENDER).balance(10 * ONE_HUNDRED_HBARS),
                cryptoCreate(RECEIVER).receiverSigRequired(true),
                cryptoCreate(SENDER2).balance(5 * ONE_HUNDRED_HBARS),
                cryptoCreate(RECEIVER2).receiverSigRequired(true),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(FUNGIBLE_TOKEN)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .initialSupply(TOTAL_SUPPLY)
                        .treasury(TOKEN_TREASURY),
                tokenCreate(NFT_TOKEN)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .adminKey(MULTI_KEY)
                        .supplyKey(MULTI_KEY)
                        .supplyType(TokenSupplyType.INFINITE)
                        .initialSupply(0)
                        .treasury(TOKEN_TREASURY),
                mintToken(NFT_TOKEN, List.of(metadata(FIRST_MEMO), metadata(SECOND_MEMO))),
                tokenAssociate(SENDER, List.of(FUNGIBLE_TOKEN, NFT_TOKEN)),
                tokenAssociate(SENDER2, List.of(FUNGIBLE_TOKEN, NFT_TOKEN)),
                tokenAssociate(RECEIVER, List.of(FUNGIBLE_TOKEN, NFT_TOKEN)),
                tokenAssociate(RECEIVER2, List.of(FUNGIBLE_TOKEN, NFT_TOKEN)),
                cryptoTransfer(moving(200, FUNGIBLE_TOKEN).between(TOKEN_TREASURY, SENDER))
                        .payingWith(SENDER),
                cryptoTransfer(moving(100, FUNGIBLE_TOKEN).between(TOKEN_TREASURY, SENDER2))
                        .payingWith(SENDER2),
                cryptoTransfer(movingUnique(NFT_TOKEN, 1).between(TOKEN_TREASURY, SENDER))
                        .payingWith(SENDER),
                cryptoTransfer(TokenMovement.movingUnique(NFT_TOKEN, 2).between(TOKEN_TREASURY, SENDER2))
                        .payingWith(SENDER2),
                uploadInitCode(CONTRACT),
                contractCreate(CONTRACT),
                withOpContext((spec, opLog) -> {
                    final var fungibleToken = spec.registry().getTokenID(FUNGIBLE_TOKEN);
                    final var nonFungibleToken = spec.registry().getTokenID(NFT_TOKEN);
                    final var firstSender = spec.registry().getAccountID(SENDER);
                    final var firstReceiver = spec.registry().getAccountID(RECEIVER);
                    final var secondSender = spec.registry().getAccountID(SENDER2);
                    final var secondReceiver = spec.registry().getAccountID(RECEIVER2);

                    allRunFor(
                            spec,
                            newKeyNamed(DELEGATE_KEY).shape(DELEGATE_CONTRACT_KEY_SHAPE.signedWith(sigs(ON, CONTRACT))),
                            cryptoUpdate(SENDER).key(DELEGATE_KEY),
                            cryptoUpdate(SENDER2).key(DELEGATE_KEY),
                            cryptoUpdate(RECEIVER).key(DELEGATE_KEY),
                            cryptoUpdate(RECEIVER2).key(DELEGATE_KEY),
                            contractCall(
                                            CONTRACT,
                                            TRANSFER_MULTIPLE_TOKENS,
                                            tokenTransferLists()
                                                    .withTokenTransferList(
                                                            tokenTransferList()
                                                                    .forToken(fungibleToken)
                                                                    .withAccountAmounts(
                                                                            accountAmount(firstSender, -45L),
                                                                            accountAmount(firstReceiver, 45L),
                                                                            accountAmount(secondSender, -32L),
                                                                            accountAmount(secondReceiver, 32L))
                                                                    .build(),
                                                            tokenTransferList()
                                                                    .forToken(nonFungibleToken)
                                                                    .withNftTransfers(
                                                                            nftTransfer(firstSender, firstReceiver, 1L),
                                                                            nftTransfer(
                                                                                    secondSender, secondReceiver, 2L))
                                                                    .build())
                                                    .build())
                                    .payingWith(GENESIS)
                                    .via(cryptoTransferTxn)
                                    .gas(GAS_TO_OFFER));
                }),
                getTxnRecord(cryptoTransferTxn).andAllChildRecords(),
                getTokenInfo(FUNGIBLE_TOKEN).hasTotalSupply(TOTAL_SUPPLY),
                getAccountBalance(RECEIVER).hasTokenBalance(FUNGIBLE_TOKEN, 45),
                getAccountBalance(SENDER).hasTokenBalance(FUNGIBLE_TOKEN, 155),
                getAccountBalance(RECEIVER2).hasTokenBalance(FUNGIBLE_TOKEN, 32),
                getAccountBalance(SENDER2).hasTokenBalance(FUNGIBLE_TOKEN, 68),
                getTokenInfo(FUNGIBLE_TOKEN).logged(),
                getTokenInfo(NFT_TOKEN).hasTotalSupply(2),
                getAccountInfo(RECEIVER).hasOwnedNfts(1),
                getAccountBalance(RECEIVER).hasTokenBalance(NFT_TOKEN, 1),
                getAccountInfo(SENDER).hasOwnedNfts(0),
                getAccountBalance(SENDER).hasTokenBalance(NFT_TOKEN, 0),
                getAccountInfo(RECEIVER2).hasOwnedNfts(1),
                getAccountBalance(RECEIVER2).hasTokenBalance(NFT_TOKEN, 1),
                getAccountInfo(SENDER2).hasOwnedNfts(0),
                getAccountBalance(SENDER2).hasTokenBalance(NFT_TOKEN, 0),
                getTokenInfo(NFT_TOKEN).logged(),
                childRecordsCheck(
                        cryptoTransferTxn,
                        SUCCESS,
                        recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith()
                                        .contractCallResult(
                                                htsPrecompileResult().withStatus(SUCCESS)))
                                .tokenTransfers(SomeFungibleTransfers.changingFungibleBalances()
                                        .including(FUNGIBLE_TOKEN, SENDER, -45L)
                                        .including(FUNGIBLE_TOKEN, RECEIVER, 45L)
                                        .including(FUNGIBLE_TOKEN, SENDER2, -32L)
                                        .including(FUNGIBLE_TOKEN, RECEIVER2, 32L))
                                .tokenTransfers(NonFungibleTransfers.changingNFTBalances()
                                        .including(NFT_TOKEN, SENDER, RECEIVER, 1L)
                                        .including(NFT_TOKEN, SENDER2, RECEIVER2, 2L))));
    }

    @HapiTest
    final Stream<DynamicTest> hapiTransferFromForNFTWithCustomFeesWithoutApproveFails() {
        return hapiTest(
                newKeyNamed(MULTI_KEY),
                newKeyNamed(RECEIVER_SIGNATURE),
                cryptoCreate(TOKEN_TREASURY),
                cryptoCreate(OWNER)
                        .balance(ONE_HUNDRED_HBARS)
                        .maxAutomaticTokenAssociations(5)
                        .key(MULTI_KEY),
                cryptoCreate(SENDER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(RECEIVER).balance(ONE_HUNDRED_HBARS).key(RECEIVER_SIGNATURE),
                tokenCreate(NFT_TOKEN_WITH_FIXED_HBAR_FEE)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .treasury(OWNER)
                        .initialSupply(0L)
                        .supplyKey(MULTI_KEY)
                        .adminKey(MULTI_KEY)
                        .withCustom(fixedHbarFee(1, OWNER)),
                tokenCreate(FUNGIBLE_TOKEN_FEE)
                        .tokenType(FUNGIBLE_COMMON)
                        .treasury(TOKEN_TREASURY)
                        .initialSupply(1000L),
                tokenAssociate(SENDER, FUNGIBLE_TOKEN_FEE),
                tokenAssociate(OWNER, FUNGIBLE_TOKEN_FEE),
                tokenAssociate(RECEIVER, FUNGIBLE_TOKEN_FEE),
                tokenCreate(NFT_TOKEN_WITH_FIXED_TOKEN_FEE)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .treasury(OWNER)
                        .initialSupply(0L)
                        .supplyKey(MULTI_KEY)
                        .adminKey(MULTI_KEY)
                        .withCustom(fixedHtsFee(1, FUNGIBLE_TOKEN_FEE, OWNER)),
                tokenCreate(NFT_TOKEN_WITH_ROYALTY_FEE_WITH_HBAR_FALLBACK)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .treasury(OWNER)
                        .initialSupply(0L)
                        .supplyKey(MULTI_KEY)
                        .adminKey(MULTI_KEY)
                        .withCustom(royaltyFeeWithFallback(1, 2, fixedHbarFeeInheritingRoyaltyCollector(1), OWNER)),
                tokenCreate(NFT_TOKEN_WITH_ROYALTY_FEE_WITH_TOKEN_FALLBACK)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .treasury(OWNER)
                        .initialSupply(0L)
                        .supplyKey(MULTI_KEY)
                        .adminKey(MULTI_KEY)
                        .withCustom(royaltyFeeWithFallback(
                                1, 2, fixedHtsFeeInheritingRoyaltyCollector(1, FUNGIBLE_TOKEN_FEE), OWNER)),
                tokenAssociate(
                        SENDER,
                        List.of(
                                NFT_TOKEN_WITH_FIXED_HBAR_FEE,
                                NFT_TOKEN_WITH_FIXED_TOKEN_FEE,
                                NFT_TOKEN_WITH_ROYALTY_FEE_WITH_HBAR_FALLBACK,
                                NFT_TOKEN_WITH_ROYALTY_FEE_WITH_TOKEN_FALLBACK)),
                tokenAssociate(
                        RECEIVER,
                        List.of(
                                NFT_TOKEN_WITH_FIXED_HBAR_FEE,
                                NFT_TOKEN_WITH_FIXED_TOKEN_FEE,
                                NFT_TOKEN_WITH_ROYALTY_FEE_WITH_HBAR_FALLBACK,
                                NFT_TOKEN_WITH_ROYALTY_FEE_WITH_TOKEN_FALLBACK)),
                mintToken(NFT_TOKEN_WITH_FIXED_HBAR_FEE, List.of(META1, META2)),
                mintToken(NFT_TOKEN_WITH_FIXED_TOKEN_FEE, List.of(META3, META4)),
                mintToken(
                        NFT_TOKEN_WITH_ROYALTY_FEE_WITH_HBAR_FALLBACK,
                        List.of(
                                ByteStringUtils.wrapUnsafely("meta5".getBytes()),
                                ByteStringUtils.wrapUnsafely("meta6".getBytes()))),
                mintToken(
                        NFT_TOKEN_WITH_ROYALTY_FEE_WITH_TOKEN_FALLBACK,
                        List.of(
                                ByteStringUtils.wrapUnsafely("meta7".getBytes()),
                                ByteStringUtils.wrapUnsafely("meta8".getBytes()))),
                cryptoTransfer(movingUnique(NFT_TOKEN_WITH_FIXED_HBAR_FEE, 1L).between(OWNER, SENDER)),
                cryptoTransfer(movingUnique(NFT_TOKEN_WITH_FIXED_TOKEN_FEE, 1L).between(OWNER, SENDER)),
                cryptoTransfer(movingUnique(NFT_TOKEN_WITH_ROYALTY_FEE_WITH_HBAR_FALLBACK, 1L)
                        .between(OWNER, SENDER)),
                cryptoTransfer(movingUnique(NFT_TOKEN_WITH_ROYALTY_FEE_WITH_TOKEN_FALLBACK, 1L)
                        .between(OWNER, SENDER)),
                uploadInitCode(HTS_TRANSFER_FROM_CONTRACT),
                contractCreate(HTS_TRANSFER_FROM_CONTRACT),
                cryptoTransfer(moving(1L, FUNGIBLE_TOKEN_FEE).between(TOKEN_TREASURY, SENDER)),
                cryptoTransfer(moving(1L, FUNGIBLE_TOKEN_FEE).between(TOKEN_TREASURY, RECEIVER)),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCall(
                                        HTS_TRANSFER_FROM_CONTRACT,
                                        HTS_TRANSFER_FROM_NFT,
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getTokenID(NFT_TOKEN_WITH_FIXED_HBAR_FEE))),
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getAccountID(SENDER))),
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getAccountID(RECEIVER))),
                                        BigInteger.valueOf(1L))
                                .payingWith(GENESIS)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED),
                        contractCall(
                                        HTS_TRANSFER_FROM_CONTRACT,
                                        HTS_TRANSFER_FROM_NFT,
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getTokenID(NFT_TOKEN_WITH_FIXED_TOKEN_FEE))),
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getAccountID(SENDER))),
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getAccountID(RECEIVER))),
                                        BigInteger.valueOf(1L))
                                .payingWith(GENESIS)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED),
                        contractCall(
                                        HTS_TRANSFER_FROM_CONTRACT,
                                        HTS_TRANSFER_FROM_NFT,
                                        HapiParserUtil.asHeadlongAddress(asAddress(spec.registry()
                                                .getTokenID(NFT_TOKEN_WITH_ROYALTY_FEE_WITH_HBAR_FALLBACK))),
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getAccountID(SENDER))),
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getAccountID(RECEIVER))),
                                        BigInteger.valueOf(1L))
                                .payingWith(GENESIS)
                                .alsoSigningWithFullPrefix(RECEIVER_SIGNATURE)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED),
                        contractCall(
                                        HTS_TRANSFER_FROM_CONTRACT,
                                        HTS_TRANSFER_FROM_NFT,
                                        HapiParserUtil.asHeadlongAddress(asAddress(spec.registry()
                                                .getTokenID(NFT_TOKEN_WITH_ROYALTY_FEE_WITH_TOKEN_FALLBACK))),
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getAccountID(SENDER))),
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getAccountID(RECEIVER))),
                                        BigInteger.valueOf(1L))
                                .payingWith(GENESIS)
                                .alsoSigningWithFullPrefix(RECEIVER_SIGNATURE)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED))));
    }

    @HapiTest
    final Stream<DynamicTest> hapiTransferFromForFungibleTokenWithCustomFeesWithoutApproveFails() {
        final var FUNGIBLE_TOKEN_WITH_FIXED_HBAR_FEE = "fungibleTokenWithFixedHbarFee";
        final var FUNGIBLE_TOKEN_WITH_FIXED_TOKEN_FEE = "fungibleTokenWithFixedTokenFee";
        final var FUNGIBLE_TOKEN_WITH_FRACTIONAL_FEE = "fungibleTokenWithFractionalTokenFee";
        return hapiTest(
                newKeyNamed(RECEIVER_SIGNATURE),
                cryptoCreate(TOKEN_TREASURY),
                cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(SENDER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(RECEIVER).balance(ONE_HUNDRED_HBARS).key(RECEIVER_SIGNATURE),
                tokenCreate(FUNGIBLE_TOKEN_FEE)
                        .tokenType(FUNGIBLE_COMMON)
                        .treasury(TOKEN_TREASURY)
                        .initialSupply(1000L),
                tokenAssociate(SENDER, FUNGIBLE_TOKEN_FEE),
                tokenAssociate(OWNER, FUNGIBLE_TOKEN_FEE),
                tokenAssociate(RECEIVER, FUNGIBLE_TOKEN_FEE),
                tokenCreate(FUNGIBLE_TOKEN_WITH_FIXED_HBAR_FEE)
                        .tokenType(FUNGIBLE_COMMON)
                        .treasury(OWNER)
                        .initialSupply(1000L)
                        .withCustom(fixedHbarFee(1, OWNER)),
                tokenCreate(FUNGIBLE_TOKEN_WITH_FIXED_TOKEN_FEE)
                        .tokenType(FUNGIBLE_COMMON)
                        .treasury(OWNER)
                        .initialSupply(1000L)
                        .withCustom(fixedHtsFee(1, FUNGIBLE_TOKEN_FEE, OWNER)),
                tokenCreate(FUNGIBLE_TOKEN_WITH_FRACTIONAL_FEE)
                        .tokenType(FUNGIBLE_COMMON)
                        .treasury(OWNER)
                        .initialSupply(1000L)
                        .withCustom(fractionalFee(1, 2, 1, OptionalLong.of(10), OWNER)),
                tokenAssociate(
                        SENDER,
                        List.of(
                                FUNGIBLE_TOKEN_WITH_FIXED_HBAR_FEE,
                                FUNGIBLE_TOKEN_WITH_FIXED_TOKEN_FEE,
                                FUNGIBLE_TOKEN_WITH_FRACTIONAL_FEE)),
                tokenAssociate(
                        RECEIVER,
                        List.of(
                                FUNGIBLE_TOKEN_WITH_FIXED_HBAR_FEE,
                                FUNGIBLE_TOKEN_WITH_FIXED_TOKEN_FEE,
                                FUNGIBLE_TOKEN_WITH_FRACTIONAL_FEE)),
                cryptoTransfer(moving(1L, FUNGIBLE_TOKEN_FEE).between(TOKEN_TREASURY, SENDER)),
                cryptoTransfer(moving(1L, FUNGIBLE_TOKEN_WITH_FIXED_HBAR_FEE).between(OWNER, SENDER)),
                cryptoTransfer(moving(1L, FUNGIBLE_TOKEN_WITH_FIXED_TOKEN_FEE).between(OWNER, SENDER)),
                cryptoTransfer(moving(2L, FUNGIBLE_TOKEN_WITH_FRACTIONAL_FEE).between(OWNER, SENDER)),
                uploadInitCode(HTS_TRANSFER_FROM_CONTRACT),
                contractCreate(HTS_TRANSFER_FROM_CONTRACT),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCall(
                                        HTS_TRANSFER_FROM_CONTRACT,
                                        HTS_TRANSFER_FROM,
                                        HapiParserUtil.asHeadlongAddress(asAddress(
                                                spec.registry().getTokenID(FUNGIBLE_TOKEN_WITH_FIXED_HBAR_FEE))),
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getAccountID(SENDER))),
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getAccountID(RECEIVER))),
                                        BigInteger.valueOf(1L))
                                .payingWith(GENESIS)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED),
                        contractCall(
                                        HTS_TRANSFER_FROM_CONTRACT,
                                        HTS_TRANSFER_FROM,
                                        HapiParserUtil.asHeadlongAddress(asAddress(
                                                spec.registry().getTokenID(FUNGIBLE_TOKEN_WITH_FIXED_TOKEN_FEE))),
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getAccountID(SENDER))),
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getAccountID(RECEIVER))),
                                        BigInteger.valueOf(1L))
                                .payingWith(GENESIS)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED),
                        contractCall(
                                        HTS_TRANSFER_FROM_CONTRACT,
                                        HTS_TRANSFER_FROM,
                                        HapiParserUtil.asHeadlongAddress(asAddress(
                                                spec.registry().getTokenID(FUNGIBLE_TOKEN_WITH_FRACTIONAL_FEE))),
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getAccountID(SENDER))),
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getAccountID(RECEIVER))),
                                        BigInteger.valueOf(1L))
                                .payingWith(GENESIS)
                                .alsoSigningWithFullPrefix(RECEIVER_SIGNATURE)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED))));
    }

    @HapiTest
    final Stream<DynamicTest> hapiTransferFromForFungibleTokenWithCustomFeesWithBothApproveForAllAndAssignedSpender() {
        final var FUNGIBLE_TOKEN_WITH_FIXED_HBAR_FEE = "fungibleTokenWithFixedHbarFee";
        final var FUNGIBLE_TOKEN_WITH_FIXED_TOKEN_FEE = "fungibleTokenWithFixedTokenFee";
        final var FUNGIBLE_TOKEN_WITH_FRACTIONAL_FEE = "fungibleTokenWithFractionalTokenFee";
        return hapiTest(
                newKeyNamed(RECEIVER_SIGNATURE),
                cryptoCreate(TOKEN_TREASURY),
                cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(SENDER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(RECEIVER).balance(ONE_HUNDRED_HBARS).key(RECEIVER_SIGNATURE),
                tokenCreate(FUNGIBLE_TOKEN_FEE)
                        .tokenType(FUNGIBLE_COMMON)
                        .treasury(TOKEN_TREASURY)
                        .initialSupply(1000L),
                tokenAssociate(SENDER, FUNGIBLE_TOKEN_FEE),
                tokenAssociate(OWNER, FUNGIBLE_TOKEN_FEE),
                tokenAssociate(RECEIVER, FUNGIBLE_TOKEN_FEE),
                tokenCreate(FUNGIBLE_TOKEN_WITH_FIXED_HBAR_FEE)
                        .tokenType(FUNGIBLE_COMMON)
                        .treasury(OWNER)
                        .initialSupply(1000L)
                        .withCustom(fixedHbarFee(1, OWNER)),
                tokenCreate(FUNGIBLE_TOKEN_WITH_FIXED_TOKEN_FEE)
                        .tokenType(FUNGIBLE_COMMON)
                        .treasury(OWNER)
                        .initialSupply(1000L)
                        .withCustom(fixedHtsFee(1, FUNGIBLE_TOKEN_FEE, OWNER)),
                tokenCreate(FUNGIBLE_TOKEN_WITH_FRACTIONAL_FEE)
                        .tokenType(FUNGIBLE_COMMON)
                        .treasury(OWNER)
                        .initialSupply(1000L)
                        .withCustom(fractionalFee(1, 2, 1, OptionalLong.of(10), OWNER)),
                tokenAssociate(
                        SENDER,
                        List.of(
                                FUNGIBLE_TOKEN_WITH_FIXED_HBAR_FEE,
                                FUNGIBLE_TOKEN_WITH_FIXED_TOKEN_FEE,
                                FUNGIBLE_TOKEN_WITH_FRACTIONAL_FEE)),
                tokenAssociate(
                        RECEIVER,
                        List.of(
                                FUNGIBLE_TOKEN_WITH_FIXED_HBAR_FEE,
                                FUNGIBLE_TOKEN_WITH_FIXED_TOKEN_FEE,
                                FUNGIBLE_TOKEN_WITH_FRACTIONAL_FEE)),
                cryptoTransfer(moving(1L, FUNGIBLE_TOKEN_FEE).between(TOKEN_TREASURY, SENDER)),
                cryptoTransfer(moving(1L, FUNGIBLE_TOKEN_WITH_FIXED_HBAR_FEE).between(OWNER, SENDER)),
                cryptoTransfer(moving(1L, FUNGIBLE_TOKEN_WITH_FIXED_TOKEN_FEE).between(OWNER, SENDER)),
                cryptoTransfer(moving(2L, FUNGIBLE_TOKEN_WITH_FRACTIONAL_FEE).between(OWNER, SENDER)),
                uploadInitCode(HTS_TRANSFER_FROM_CONTRACT),
                contractCreate(HTS_TRANSFER_FROM_CONTRACT),
                cryptoApproveAllowance()
                        .payingWith(DEFAULT_PAYER)
                        .addTokenAllowance(SENDER, FUNGIBLE_TOKEN_WITH_FIXED_HBAR_FEE, HTS_TRANSFER_FROM_CONTRACT, 1L)
                        .addTokenAllowance(SENDER, FUNGIBLE_TOKEN_WITH_FIXED_TOKEN_FEE, HTS_TRANSFER_FROM_CONTRACT, 1L)
                        .addTokenAllowance(SENDER, FUNGIBLE_TOKEN_WITH_FRACTIONAL_FEE, HTS_TRANSFER_FROM_CONTRACT, 2L)
                        .via(APPROVE_TXN)
                        .signedBy(DEFAULT_PAYER, SENDER),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCall(
                                        HTS_TRANSFER_FROM_CONTRACT,
                                        HTS_TRANSFER_FROM,
                                        HapiParserUtil.asHeadlongAddress(asAddress(
                                                spec.registry().getTokenID(FUNGIBLE_TOKEN_WITH_FIXED_HBAR_FEE))),
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getAccountID(SENDER))),
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getAccountID(RECEIVER))),
                                        BigInteger.valueOf(1L))
                                .payingWith(GENESIS),
                        contractCall(
                                        HTS_TRANSFER_FROM_CONTRACT,
                                        HTS_TRANSFER_FROM,
                                        HapiParserUtil.asHeadlongAddress(asAddress(
                                                spec.registry().getTokenID(FUNGIBLE_TOKEN_WITH_FIXED_TOKEN_FEE))),
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getAccountID(SENDER))),
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getAccountID(RECEIVER))),
                                        BigInteger.valueOf(1L))
                                .payingWith(GENESIS),
                        contractCall(
                                        HTS_TRANSFER_FROM_CONTRACT,
                                        HTS_TRANSFER_FROM,
                                        HapiParserUtil.asHeadlongAddress(asAddress(
                                                spec.registry().getTokenID(FUNGIBLE_TOKEN_WITH_FRACTIONAL_FEE))),
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getAccountID(SENDER))),
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getAccountID(RECEIVER))),
                                        BigInteger.valueOf(1L))
                                .payingWith(GENESIS)
                                .alsoSigningWithFullPrefix(RECEIVER_SIGNATURE))));
    }

    @HapiTest
    final Stream<DynamicTest> testHtsTokenTransferToNonExistingSystemAccount() {
        final HapiSpecOperation[] opsArray = new HapiSpecOperation[nonExistingSystemAccounts.size()];
        final HapiSpecOperation[] childRecordsChecks = new HapiSpecOperation[nonExistingSystemAccounts.size()];
        final var contract = "CryptoTransfer";
        final var toSendEachTuple = 50L;
        for (int i = 0; i < nonExistingSystemAccounts.size(); i++) {
            int finalI = i;
            opsArray[i] = withOpContext((spec, opLog) -> {
                final var token = spec.registry().getTokenID(FUNGIBLE_TOKEN);
                final var sender = spec.registry().getAccountID(SENDER);
                final var receiver = AccountID.newBuilder()
                        .setAccountNum(nonExistingSystemAccounts.get(finalI))
                        .build();

                allRunFor(
                        spec,
                        newKeyNamed(DELEGATE_KEY).shape(DELEGATE_CONTRACT_KEY_SHAPE.signedWith(sigs(ON, contract))),
                        cryptoUpdate(SENDER).key(DELEGATE_KEY),
                        contractCall(contract, TRANSFER_MULTIPLE_TOKENS, (Object) new Tuple[] {
                                    tokenTransferList()
                                            .forToken(token)
                                            .withAccountAmounts(
                                                    accountAmount(sender, -toSendEachTuple),
                                                    accountAmount(receiver, toSendEachTuple))
                                            .build(),
                                    tokenTransferList()
                                            .forToken(token)
                                            .withAccountAmounts(
                                                    accountAmount(sender, -toSendEachTuple),
                                                    accountAmount(receiver, toSendEachTuple))
                                            .build()
                                })
                                .payingWith(GENESIS)
                                .gas(GAS_TO_OFFER)
                                .via("htsTransfer" + finalI)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED));
            });
            childRecordsChecks[i] = childRecordsCheck(
                    "htsTransfer" + i, CONTRACT_REVERT_EXECUTED, recordWith().status(INVALID_RECEIVING_NODE_ACCOUNT));
        }
        return hapiTest(flattened(
                cryptoCreate(SENDER).balance(10 * ONE_HUNDRED_HBARS),
                uploadInitCode(contract),
                contractCreate(contract),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(FUNGIBLE_TOKEN)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .initialSupply(TOTAL_SUPPLY)
                        .treasury(TOKEN_TREASURY),
                tokenAssociate(SENDER, List.of(FUNGIBLE_TOKEN)),
                cryptoTransfer(moving(200L, FUNGIBLE_TOKEN).between(TOKEN_TREASURY, SENDER)),
                opsArray,
                childRecordsChecks));
    }

    @HapiTest
    final Stream<DynamicTest> testNftTransferToNonExistingSystemAccount() {
        final HapiSpecOperation[] opsArray = new HapiSpecOperation[nonExistingSystemAccounts.size()];
        final HapiSpecOperation[] childRecordsChecks = new HapiSpecOperation[nonExistingSystemAccounts.size()];
        for (int i = 0; i < nonExistingSystemAccounts.size(); i++) {
            int finalI = i;
            opsArray[i] = withOpContext((spec, opLog) -> {
                allRunFor(
                        spec,
                        contractCall(
                                        HTS_TRANSFER_FROM_CONTRACT,
                                        HTS_TRANSFER_FROM_NFT,
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getTokenID(NFT_TOKEN))),
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getAccountID(OWNER))),
                                        mirrorAddrWith(spec, nonExistingSystemAccounts.get(finalI)),
                                        BigInteger.TWO)
                                .via("nftTransfer" + finalI)
                                .gas(1000000)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED));
            });
            childRecordsChecks[i] = childRecordsCheck(
                    "nftTransfer" + i, CONTRACT_REVERT_EXECUTED, recordWith().status(INVALID_ALIAS_KEY));
        }
        return hapiTest(flattened(
                newKeyNamed(MULTI_KEY),
                cryptoCreate(OWNER).balance(ONE_MILLION_HBARS).maxAutomaticTokenAssociations(5),
                cryptoCreate(SPENDER).maxAutomaticTokenAssociations(5),
                tokenCreate(NFT_TOKEN)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .treasury(OWNER)
                        .initialSupply(0L)
                        .supplyKey(MULTI_KEY),
                uploadInitCode(HTS_TRANSFER_FROM_CONTRACT),
                contractCreate(HTS_TRANSFER_FROM_CONTRACT),
                mintToken(NFT_TOKEN, List.of(META1, META2)),
                cryptoApproveAllowance()
                        .payingWith(DEFAULT_PAYER)
                        .addNftAllowance(OWNER, NFT_TOKEN, HTS_TRANSFER_FROM_CONTRACT, false, List.of(2L))
                        .via("baseApproveTxn")
                        .signedBy(DEFAULT_PAYER, OWNER)
                        .fee(ONE_HBAR),
                opsArray,
                childRecordsChecks));
    }

    @HapiTest
    final Stream<DynamicTest> testHtsTokenTransferToExistingSystemAccount() {
        final HapiSpecOperation[] opsArray = new HapiSpecOperation[existingSystemAccounts.size()];
        final var contract = "CryptoTransfer";
        final var toSendEachTuple = 50L;

        for (int i = 0; i < existingSystemAccounts.size(); i++) {
            int finalI = i;
            opsArray[i] = withOpContext((spec, opLog) -> {
                final var token = spec.registry().getTokenID(FUNGIBLE_TOKEN);
                final var sender = spec.registry().getAccountID(SENDER);
                final var receiver = asAccount(spec, existingSystemAccounts.get(finalI));
                allRunFor(
                        spec,
                        newKeyNamed(DELEGATE_KEY).shape(DELEGATE_CONTRACT_KEY_SHAPE.signedWith(sigs(ON, contract))),
                        cryptoUpdate(SENDER).key(DELEGATE_KEY),
                        contractCall(contract, TRANSFER_MULTIPLE_TOKENS, (Object) new Tuple[] {
                                    tokenTransferList()
                                            .forToken(token)
                                            .withAccountAmounts(
                                                    accountAmount(sender, -toSendEachTuple),
                                                    accountAmount(receiver, toSendEachTuple))
                                            .build(),
                                    tokenTransferList()
                                            .forToken(token)
                                            .withAccountAmounts(
                                                    accountAmount(sender, -toSendEachTuple),
                                                    accountAmount(receiver, toSendEachTuple))
                                            .build()
                                })
                                .payingWith(GENESIS)
                                .gas(GAS_TO_OFFER)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED));
            });
        }
        return hapiTest(flattened(
                cryptoCreate(SENDER).balance(10 * ONE_HUNDRED_HBARS),
                uploadInitCode(contract),
                contractCreate(contract),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(FUNGIBLE_TOKEN)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .initialSupply(TOTAL_SUPPLY)
                        .treasury(TOKEN_TREASURY),
                tokenAssociate(SENDER, List.of(FUNGIBLE_TOKEN)),
                cryptoTransfer(moving(200L, FUNGIBLE_TOKEN).between(TOKEN_TREASURY, SENDER)),
                opsArray));
    }

    private String toTokenIdString(TokenID tokenId) {
        return asEntityString(tokenId.getShardNum(), tokenId.getRealmNum(), tokenId.getTokenNum());
    }
}
