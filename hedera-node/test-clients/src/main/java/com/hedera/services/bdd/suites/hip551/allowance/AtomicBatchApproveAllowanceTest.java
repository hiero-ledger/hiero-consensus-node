// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip551.allowance;

import static com.hedera.services.bdd.junit.TestTags.CRYPTO;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AccountDetailsAsserts.accountDetailsWith;
import static com.hedera.services.bdd.spec.assertions.AssertUtils.inOrder;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.resultWith;
import static com.hedera.services.bdd.spec.assertions.ContractLogAsserts.logWith;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountDetails;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getContractInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTokenNftInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
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
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenFreeze;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenPause;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenUnpause;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoApproveAllowance.MISSING_OWNER;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
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
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateInnerTxnChargedUsd;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.FUNDING;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.TOKEN_TREASURY;
import static com.hedera.services.bdd.suites.contract.Utils.eventSignatureOf;
import static com.hedera.services.bdd.suites.contract.Utils.parsedToByteString;
import static com.hedera.services.bdd.suites.contract.leaky.LeakyContractTestsSuite.APPROVE;
import static com.hedera.services.bdd.suites.contract.leaky.LeakyContractTestsSuite.ERC_20_CONTRACT;
import static com.hedera.services.bdd.suites.contract.leaky.LeakyContractTestsSuite.TRANSFER_FROM;
import static com.hedera.services.bdd.suites.contract.leaky.LeakyContractTestsSuite.TRANSFER_SIGNATURE;
import static com.hedera.services.bdd.suites.token.TokenTransactSpecs.TRANSFER_TXN;
import static com.hedera.services.bdd.suites.utils.contracts.precompile.HTSPrecompileResult.htsPrecompileResult;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.AMOUNT_EXCEEDS_TOKEN_MAX_SUPPLY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.DELEGATING_SPENDER_CANNOT_GRANT_APPROVE_FOR_ALL;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.DELEGATING_SPENDER_DOES_NOT_HAVE_APPROVE_FOR_ALL;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.EMPTY_ALLOWANCES;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.FUNGIBLE_TOKEN_IN_NFT_ALLOWANCES;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INNER_TRANSACTION_FAILED;
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

import com.esaulpaugh.headlong.abi.Address;
import com.google.protobuf.ByteString;
import com.hedera.node.app.hapi.utils.contracts.ParsingConstants;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.transactions.HapiTxnOp;
import com.hedera.services.bdd.spec.transactions.util.HapiAtomicBatch;
import com.hederahashgraph.api.proto.java.NftTransfer;
import com.hederahashgraph.api.proto.java.TokenSupplyType;
import com.hederahashgraph.api.proto.java.TokenTransferList;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * This class tests the behavior of atomic batch operations
 * involving approve allowance.
 */
@Tag(CRYPTO)
@HapiTestLifecycle
public class AtomicBatchApproveAllowanceTest {
    private static final String OWNER = "owner";
    private static final String SPENDER = "spender";
    private static final String RECEIVER = "receiver";
    private static final String OTHER_RECEIVER = "otherReceiver";
    private static final String FUNGIBLE_TOKEN = "fungible";
    private static final String NON_FUNGIBLE_TOKEN = "nonFungible";
    private static final String SUPPLY_KEY = "supplyKey";
    private static final String NFT_TOKEN_MINT_TXN = "nftTokenMint";
    private static final String FUNGIBLE_TOKEN_MINT_TXN = "tokenMint";
    private static final String BASE_APPROVE_TXN = "baseApproveTxn";
    private static final String PAYER = "payer";
    private static final String APPROVE_TXN = "approveTxn";
    private static final String ANOTHER_SPENDER = "spender1";
    private static final String SECOND_OWNER = "owner2";
    private static final String SECOND_SPENDER = "spender2";
    private static final String THIRD_SPENDER = "spender3";
    private static final String ADMIN_KEY = "adminKey";
    private static final String FREEZE_KEY = "freezeKey";
    private static final String KYC_KEY = "kycKey";
    private static final String PAUSE_KEY = "pauseKey";
    private static final String DEFAULT_BATCH_OPERATOR = "defaultBatchOperator";

    @BeforeAll
    public static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(
                Map.of("atomicBatch.isEnabled", "true", "atomicBatch.maxNumberOfTransactions", "50"));
        testLifecycle.doAdhoc(cryptoCreate(DEFAULT_BATCH_OPERATOR).balance(ONE_MILLION_HBARS));
    }

    /**
     * Tests transfer Erc20 token from contract with approval in batch txn.
     * @return hapi test
     */
    @HapiTest
    public final Stream<DynamicTest> transferErc20TokenFromContractWithApproval() {
        final var transferFromOtherContractWithSignaturesTxn = "transferFromOtherContractWithSignaturesTxn";
        final var nestedContract = "NestedERC20Contract";
        final var tokenAddress = new AtomicReference<Address>();
        final var erc20ContractAddress = new AtomicReference<Address>();
        final var nestedContractAddress = new AtomicReference<Address>();

        return hapiTest(
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(FUNGIBLE_TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
                        .initialSupply(35)
                        .treasury(TOKEN_TREASURY)
                        .exposingAddressTo(tokenAddress::set),
                uploadInitCode(ERC_20_CONTRACT, nestedContract),
                contractCreate(ERC_20_CONTRACT).exposingAddressTo(erc20ContractAddress::set),
                contractCreate(nestedContract).exposingAddressTo(nestedContractAddress::set),
                sourcing(() -> atomicBatchDefaultOperator(
                        tokenAssociate(ERC_20_CONTRACT, List.of(FUNGIBLE_TOKEN)),
                        tokenAssociate(nestedContract, List.of(FUNGIBLE_TOKEN)),
                        cryptoTransfer(moving(20, FUNGIBLE_TOKEN).between(TOKEN_TREASURY, ERC_20_CONTRACT)),
                        contractCall(
                                        ERC_20_CONTRACT,
                                        APPROVE,
                                        tokenAddress.get(),
                                        erc20ContractAddress.get(),
                                        BigInteger.valueOf(20))
                                .gas(1_000_000),
                        contractCall(
                                        ERC_20_CONTRACT,
                                        TRANSFER_FROM,
                                        tokenAddress.get(),
                                        erc20ContractAddress.get(),
                                        nestedContractAddress.get(),
                                        BigInteger.valueOf(5))
                                .via(TRANSFER_TXN)
                                .hasKnownStatus(SUCCESS),
                        contractCall(
                                        ERC_20_CONTRACT,
                                        TRANSFER_FROM,
                                        tokenAddress.get(),
                                        erc20ContractAddress.get(),
                                        nestedContractAddress.get(),
                                        BigInteger.valueOf(5))
                                .via(transferFromOtherContractWithSignaturesTxn))),
                getContractInfo(ERC_20_CONTRACT).saveToRegistry(ERC_20_CONTRACT),
                getContractInfo(nestedContract).saveToRegistry(nestedContract),
                withOpContext((spec, log) -> {
                    final var sender =
                            spec.registry().getContractInfo(ERC_20_CONTRACT).getContractID();
                    final var receiver =
                            spec.registry().getContractInfo(nestedContract).getContractID();

                    final var transferRecord = getTxnRecord(TRANSFER_TXN)
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

                    final var transferFromOtherContractWithSignaturesTxnRecord = getTxnRecord(
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

    /**
     * Tests that cannot pay for any transaction with contract account in batch txn.
     * @return hapi test
     */
    @HapiTest
    public final Stream<DynamicTest> cannotPayForAnyTransactionWithContractAccount() {
        final var cryptoAdminKey = "cryptoAdminKey";
        final var contract = "PayableConstructor";
        return hapiTest(
                newKeyNamed(cryptoAdminKey),
                uploadInitCode(contract),
                contractCreate(contract).adminKey(cryptoAdminKey).balance(ONE_HUNDRED_HBARS),
                atomicBatchDefaultOperator(cryptoTransfer(tinyBarsFromTo(contract, FUNDING, 1))
                                .fee(ONE_HBAR)
                                .payingWith(contract)
                                .signedBy(cryptoAdminKey))
                        .hasPrecheck(PAYER_ACCOUNT_NOT_FOUND));
    }

    /**
     * Tests transferring a missing NFT via approval fails with INVALID_NFT_ID inside a batch transaction.
     * @return hapi test
     */
    @HapiTest
    public final Stream<DynamicTest> transferringMissingNftViaApprovalFailsWithInvalidNftId() {
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
                atomicBatchDefaultOperator(
                                tokenAssociate(OWNER, NON_FUNGIBLE_TOKEN),
                                mintToken(
                                        NON_FUNGIBLE_TOKEN,
                                        List.of(
                                                ByteString.copyFromUtf8("a"),
                                                ByteString.copyFromUtf8("b"),
                                                ByteString.copyFromUtf8("c"))),
                                cryptoTransfer((spec, builder) ->
                                                builder.addTokenTransfers(TokenTransferList.newBuilder()
                                                        .setToken(
                                                                spec.registry().getTokenID(NON_FUNGIBLE_TOKEN))
                                                        .addNftTransfers(NftTransfer.newBuilder()
                                                                // Doesn't exist
                                                                .setSerialNumber(4L)
                                                                .setSenderAccountID(spec.registry()
                                                                        .getAccountID(OWNER))
                                                                .setReceiverAccountID(spec.registry()
                                                                        .getAccountID(RECEIVER))
                                                                .setIsApproval(true)
                                                                .build())))
                                        .payingWith(SPENDER)
                                        .signedBy(SPENDER, OWNER)
                                        .via("transferTxn")
                                        .hasKnownStatus(INVALID_NFT_ID))
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    /**
     * Tests the deletion of allowances from a deleted spender inside a batch transaction.
     * @return hapi test
     */
    @HapiTest
    public final Stream<DynamicTest> deleteAllowanceFromDeletedSpender() {
        return hapiTest(
                newKeyNamed(SUPPLY_KEY),
                cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                cryptoCreate(SPENDER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(TOKEN_TREASURY).balance(100 * ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                tokenCreate(FUNGIBLE_TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
                        .initialSupply(500L)
                        .treasury(TOKEN_TREASURY),
                tokenCreate(NON_FUNGIBLE_TOKEN)
                        .initialSupply(0)
                        .supplyKey(SUPPLY_KEY)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .treasury(TOKEN_TREASURY),
                atomicBatchDefaultOperator(
                        tokenAssociate(OWNER, FUNGIBLE_TOKEN, NON_FUNGIBLE_TOKEN),
                        mintToken(NON_FUNGIBLE_TOKEN, List.of(ByteString.copyFromUtf8("a"))),
                        cryptoApproveAllowance()
                                .payingWith(OWNER)
                                .addCryptoAllowance(OWNER, SPENDER, 100L)
                                .addTokenAllowance(OWNER, FUNGIBLE_TOKEN, SPENDER, 1)
                                .addNftAllowance(OWNER, NON_FUNGIBLE_TOKEN, SPENDER, true, List.of())),
                getAccountDetails(OWNER)
                        .has(accountDetailsWith()
                                .cryptoAllowancesCount(1)
                                .tokenAllowancesCount(1)
                                .nftApprovedForAllAllowancesCount(1)
                                .cryptoAllowancesContaining(SPENDER, 100L)
                                .tokenAllowancesContaining(FUNGIBLE_TOKEN, SPENDER, 1)),
                atomicBatchDefaultOperator(
                        cryptoDelete(SPENDER),
                        // removing fungible allowances should be possible even if the
                        // spender is deleted
                        cryptoApproveAllowance()
                                .payingWith(OWNER)
                                .addCryptoAllowance(OWNER, SPENDER, 0)
                                .blankMemo()),
                getAccountDetails(OWNER)
                        .payingWith(GENESIS)
                        .has(accountDetailsWith()
                                .cryptoAllowancesCount(0)
                                .tokenAllowancesCount(1)
                                .nftApprovedForAllAllowancesCount(1)
                                .tokenAllowancesContaining(FUNGIBLE_TOKEN, SPENDER, 1)),
                atomicBatchDefaultOperator(cryptoApproveAllowance()
                        .payingWith(OWNER)
                        .addTokenAllowance(OWNER, FUNGIBLE_TOKEN, SPENDER, 0)
                        .blankMemo()),
                getAccountDetails(OWNER)
                        .payingWith(GENESIS)
                        .has(accountDetailsWith()
                                .cryptoAllowancesCount(0)
                                .tokenAllowancesCount(0)
                                .nftApprovedForAllAllowancesCount(1)),
                // It should not be possible to remove approveForAllNftAllowance
                // and also add allowance to serials
                atomicBatchDefaultOperator(cryptoApproveAllowance()
                                .payingWith(OWNER)
                                .addNftAllowance(OWNER, NON_FUNGIBLE_TOKEN, SPENDER, false, List.of(1L))
                                .via("removeApproveForAllButSetSerials")
                                .hasKnownStatus(INVALID_ALLOWANCE_SPENDER_ID))
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                getAccountDetails(OWNER)
                        .payingWith(GENESIS)
                        .has(accountDetailsWith()
                                .cryptoAllowancesCount(0)
                                .tokenAllowancesCount(0)
                                .nftApprovedForAllAllowancesCount(1)),
                getTokenNftInfo(NON_FUNGIBLE_TOKEN, 1L).hasNoSpender(),
                atomicBatchDefaultOperator(cryptoApproveAllowance()
                        .payingWith(OWNER)
                        .addNftAllowance(OWNER, NON_FUNGIBLE_TOKEN, SPENDER, false, List.of())),
                getAccountDetails(OWNER)
                        .payingWith(GENESIS)
                        .has(accountDetailsWith()
                                .cryptoAllowancesCount(0)
                                .tokenAllowancesCount(0)
                                .nftApprovedForAllAllowancesCount(0)));
    }

    /**
     * Tests that duplicate keys and serials in the same transaction do not throw an error inside a batch transaction.
     * @return hapi test
     */
    @HapiTest
    public final Stream<DynamicTest> duplicateKeysAndSerialsInSameTxnDoesntThrow() {
        return hapiTest(
                newKeyNamed(SUPPLY_KEY),
                cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                cryptoCreate(SPENDER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(TOKEN_TREASURY).balance(100 * ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                tokenCreate(FUNGIBLE_TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
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
                atomicBatchDefaultOperator(
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
                        cryptoTransfer(
                                movingUnique(NON_FUNGIBLE_TOKEN, 1L, 2L, 3L).between(TOKEN_TREASURY, OWNER)),
                        cryptoApproveAllowance()
                                .payingWith(OWNER)
                                .addCryptoAllowance(OWNER, SPENDER, 100L)
                                .addCryptoAllowance(OWNER, SPENDER, 200L)
                                .blankMemo(),
                        cryptoApproveAllowance()
                                .payingWith(OWNER)
                                .addCryptoAllowance(OWNER, SPENDER, 300L)
                                .addTokenAllowance(OWNER, FUNGIBLE_TOKEN, SPENDER, 300L)
                                .addTokenAllowance(OWNER, FUNGIBLE_TOKEN, SPENDER, 500L)
                                .blankMemo()
                                .logged(),
                        cryptoApproveAllowance()
                                .payingWith(OWNER)
                                .addCryptoAllowance(OWNER, SPENDER, 500L)
                                .addTokenAllowance(OWNER, FUNGIBLE_TOKEN, SPENDER, 600L)
                                .addNftAllowance(OWNER, NON_FUNGIBLE_TOKEN, SPENDER, false, List.of(1L, 2L, 2L, 2L, 2L))
                                .addNftAllowance(OWNER, NON_FUNGIBLE_TOKEN, SPENDER, true, List.of(1L))
                                .addNftAllowance(OWNER, NON_FUNGIBLE_TOKEN, SPENDER, false, List.of(2L))
                                .addNftAllowance(OWNER, NON_FUNGIBLE_TOKEN, SPENDER, true, List.of(3L))
                                .blankMemo()),
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

    /**
     * Tests that a delegating spender can delegate approveForAll on an NFT inside a batch transaction.
     * @return hapi test
     */
    @HapiTest
    public final Stream<DynamicTest> approveForAllSpenderCanDelegateOnNft() {
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
                atomicBatchDefaultOperator(
                        tokenAssociate(OWNER, NON_FUNGIBLE_TOKEN),
                        mintToken(
                                        NON_FUNGIBLE_TOKEN,
                                        List.of(ByteString.copyFromUtf8("a"), ByteString.copyFromUtf8("b")))
                                .via(NFT_TOKEN_MINT_TXN),
                        cryptoTransfer(movingUnique(NON_FUNGIBLE_TOKEN, 1L, 2L).between(TOKEN_TREASURY, OWNER)),
                        cryptoApproveAllowance()
                                .payingWith(DEFAULT_PAYER)
                                .addNftAllowance(OWNER, NON_FUNGIBLE_TOKEN, delegatingSpender, true, List.of(1L))
                                .addNftAllowance(OWNER, NON_FUNGIBLE_TOKEN, newSpender, false, List.of(2L))
                                .signedBy(DEFAULT_PAYER, OWNER)),
                atomicBatchDefaultOperator(cryptoApproveAllowance()
                                .payingWith(DEFAULT_PAYER)
                                .addDelegatedNftAllowance(
                                        OWNER, NON_FUNGIBLE_TOKEN, newSpender, delegatingSpender, false, List.of(1L))
                                .signedBy(DEFAULT_PAYER, OWNER)
                                .hasKnownStatus(INVALID_SIGNATURE))
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatchDefaultOperator(cryptoApproveAllowance()
                                .payingWith(DEFAULT_PAYER)
                                .addDelegatedNftAllowance(
                                        OWNER, NON_FUNGIBLE_TOKEN, delegatingSpender, newSpender, false, List.of(2L))
                                .signedBy(DEFAULT_PAYER, newSpender)
                                .hasKnownStatus(DELEGATING_SPENDER_DOES_NOT_HAVE_APPROVE_FOR_ALL))
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatchDefaultOperator(cryptoApproveAllowance()
                                .payingWith(DEFAULT_PAYER)
                                .addDelegatedNftAllowance(
                                        OWNER, NON_FUNGIBLE_TOKEN, newSpender, delegatingSpender, true, List.of())
                                .signedBy(DEFAULT_PAYER, OWNER)
                                .hasKnownStatus(DELEGATING_SPENDER_CANNOT_GRANT_APPROVE_FOR_ALL))
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                getTokenNftInfo(NON_FUNGIBLE_TOKEN, 2L).hasSpenderID(newSpender),
                getTokenNftInfo(NON_FUNGIBLE_TOKEN, 1L).hasSpenderID(delegatingSpender),
                atomicBatchDefaultOperator(cryptoApproveAllowance()
                        .payingWith(DEFAULT_PAYER)
                        .addDelegatedNftAllowance(
                                OWNER, NON_FUNGIBLE_TOKEN, newSpender, delegatingSpender, false, List.of(1L))
                        .signedBy(DEFAULT_PAYER, delegatingSpender)),
                getTokenNftInfo(NON_FUNGIBLE_TOKEN, 1L).hasSpenderID(newSpender));
    }

    /**
     * Tests granting fungible and NFT allowances with the treasury as the owner inside a batch transaction.
     * @return hapi test
     */
    @HapiTest
    public final Stream<DynamicTest> grantFungibleAllowancesWithTreasuryOwner() {
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
                atomicBatchDefaultOperator(
                        cryptoApproveAllowance()
                                .addTokenAllowance(TOKEN_TREASURY, FUNGIBLE_TOKEN, SPENDER, 10)
                                .signedBy(TOKEN_TREASURY, DEFAULT_PAYER),
                        cryptoApproveAllowance()
                                .addTokenAllowance(TOKEN_TREASURY, FUNGIBLE_TOKEN, SPENDER, 110)
                                .signedBy(TOKEN_TREASURY, DEFAULT_PAYER),
                        cryptoTransfer(movingWithAllowance(30, FUNGIBLE_TOKEN).between(TOKEN_TREASURY, OTHER_RECEIVER))
                                .payingWith(SPENDER)
                                .signedBy(SPENDER)),
                getAccountDetails(TOKEN_TREASURY)
                        .payingWith(GENESIS)
                        .has(accountDetailsWith().tokenAllowancesContaining(FUNGIBLE_TOKEN, SPENDER, 80))
                        .logged(),
                getAccountDetails(TOKEN_TREASURY)
                        .payingWith(GENESIS)
                        .has(accountDetailsWith().tokenAllowancesContaining(FUNGIBLE_TOKEN, SPENDER, 80))
                        .logged());
    }

    /**
     * Tests granting NFT allowances with the treasury as the owner inside a batch transaction.
     * @return hapi test
     */
    @HapiTest
    public final Stream<DynamicTest> grantNftAllowancesWithTreasuryOwner() {
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
                atomicBatchDefaultOperator(cryptoApproveAllowance()
                                .addNftAllowance(TOKEN_TREASURY, NON_FUNGIBLE_TOKEN, SPENDER, false, List.of(4L))
                                .signedBy(TOKEN_TREASURY, DEFAULT_PAYER)
                                .hasKnownStatus(INVALID_TOKEN_NFT_SERIAL_NUMBER))
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatchDefaultOperator(cryptoDeleteAllowance()
                                .addNftDeleteAllowance(TOKEN_TREASURY, NON_FUNGIBLE_TOKEN, List.of(4L))
                                .signedBy(TOKEN_TREASURY, DEFAULT_PAYER)
                                .hasKnownStatus(INVALID_TOKEN_NFT_SERIAL_NUMBER))
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatchDefaultOperator(
                        cryptoApproveAllowance()
                                .addNftAllowance(TOKEN_TREASURY, NON_FUNGIBLE_TOKEN, SPENDER, false, List.of(1L, 3L))
                                .signedBy(TOKEN_TREASURY, DEFAULT_PAYER),
                        cryptoTransfer(movingUniqueWithAllowance(NON_FUNGIBLE_TOKEN, 1L)
                                        .between(TOKEN_TREASURY, OTHER_RECEIVER))
                                .payingWith(SPENDER)
                                .signedBy(SPENDER)),
                getAccountDetails(TOKEN_TREASURY).has(accountDetailsWith().nftApprovedForAllAllowancesCount(0)));
    }

    /**
     * Tests that an invalid owner fails to approve allowances inside a batch transaction.
     * @return hapi test
     */
    @HapiTest
    public final Stream<DynamicTest> invalidOwnerFails() {
        return hapiTest(
                newKeyNamed(SUPPLY_KEY),
                cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(SPENDER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(TOKEN_TREASURY).balance(100 * ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                tokenCreate(FUNGIBLE_TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
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
                atomicBatchDefaultOperator(cryptoApproveAllowance()
                                .via("invalidOwnerTxn")
                                .payingWith(PAYER)
                                .addCryptoAllowance(OWNER, SPENDER, 100L)
                                .signedBy(PAYER, OWNER)
                                .blankMemo()
                                .hasKnownStatus(INVALID_ALLOWANCE_OWNER_ID))
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatchDefaultOperator(cryptoApproveAllowance()
                                .payingWith(PAYER)
                                .addTokenAllowance(OWNER, FUNGIBLE_TOKEN, SPENDER, 100L)
                                .signedBy(PAYER, OWNER)
                                .blankMemo()
                                .hasKnownStatus(INVALID_ALLOWANCE_OWNER_ID))
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatchDefaultOperator(cryptoApproveAllowance()
                                .payingWith(PAYER)
                                .addNftAllowance(OWNER, NON_FUNGIBLE_TOKEN, SPENDER, false, List.of(1L))
                                .signedBy(PAYER, OWNER)
                                .via(BASE_APPROVE_TXN)
                                .blankMemo()
                                .hasKnownStatus(INVALID_ALLOWANCE_OWNER_ID))
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                getAccountDetails(OWNER).has(accountDetailsWith().deleted(true)));
    }

    /**
     * Tests that an invalid spender fails to approve allowances inside a batch transaction.
     * @return hapi test
     */
    @HapiTest
    public final Stream<DynamicTest> invalidSpenderFails() {
        return hapiTest(
                newKeyNamed(SUPPLY_KEY),
                cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                cryptoCreate(SPENDER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(TOKEN_TREASURY).balance(100 * ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                tokenCreate(FUNGIBLE_TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
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
                atomicBatchDefaultOperator(cryptoApproveAllowance()
                                .payingWith(OWNER)
                                .addCryptoAllowance(OWNER, SPENDER, 100L)
                                .blankMemo()
                                .hasKnownStatus(INVALID_ALLOWANCE_SPENDER_ID))
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatchDefaultOperator(cryptoApproveAllowance()
                                .payingWith(OWNER)
                                .addTokenAllowance(OWNER, FUNGIBLE_TOKEN, SPENDER, 100L)
                                .blankMemo()
                                .hasKnownStatus(INVALID_ALLOWANCE_SPENDER_ID))
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatchDefaultOperator(cryptoApproveAllowance()
                                .payingWith(OWNER)
                                .addNftAllowance(OWNER, NON_FUNGIBLE_TOKEN, SPENDER, false, List.of(1L))
                                .via(BASE_APPROVE_TXN)
                                .blankMemo()
                                .hasKnownStatus(INVALID_ALLOWANCE_SPENDER_ID))
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    /**
     * Tests that if no owner is specified, the payer is used as the owner inside a batch transaction.
     * @return hapi test
     */
    @HapiTest
    public final Stream<DynamicTest> noOwnerDefaultsToPayer() {
        return hapiTest(
                newKeyNamed(SUPPLY_KEY),
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                cryptoCreate(SPENDER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(ANOTHER_SPENDER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(TOKEN_TREASURY).balance(100 * ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                tokenCreate(FUNGIBLE_TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
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
                atomicBatchDefaultOperator(
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
                        cryptoTransfer(
                                movingUnique(NON_FUNGIBLE_TOKEN, 1L, 2L, 3L).between(TOKEN_TREASURY, PAYER))),
                cryptoApproveAllowance()
                        .payingWith(PAYER)
                        .addCryptoAllowance(MISSING_OWNER, ANOTHER_SPENDER, 100L)
                        .addTokenAllowance(MISSING_OWNER, FUNGIBLE_TOKEN, SPENDER, 100L)
                        .addNftAllowance(MISSING_OWNER, NON_FUNGIBLE_TOKEN, SPENDER, false, List.of(1L))
                        .via(APPROVE_TXN)
                        .blankMemo(),
                validateChargedUsdWithin(APPROVE_TXN, 0.052_380, 0.01),
                getAccountDetails(PAYER)
                        .payingWith(GENESIS)
                        .has(accountDetailsWith()
                                .cryptoAllowancesCount(1)
                                .nftApprovedForAllAllowancesCount(0)
                                .tokenAllowancesCount(1)
                                .cryptoAllowancesContaining(ANOTHER_SPENDER, 100L)
                                .tokenAllowancesContaining(FUNGIBLE_TOKEN, SPENDER, 100L)));
    }

    /**
     * Tests that multiple owners in a batch transaction fail with INVALID_SIGNATURE.
     * @return hapi test
     */
    @HapiTest
    public final Stream<DynamicTest> multipleOwners() {
        return hapiTest(
                newKeyNamed(SUPPLY_KEY),
                cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                cryptoCreate(SECOND_OWNER).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                cryptoCreate(SPENDER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(TOKEN_TREASURY).balance(100 * ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                tokenCreate(FUNGIBLE_TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
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
                atomicBatchDefaultOperator(cryptoApproveAllowance()
                                .payingWith(DEFAULT_PAYER)
                                .addCryptoAllowance(OWNER, SPENDER, ONE_HBAR)
                                .addTokenAllowance(OWNER, FUNGIBLE_TOKEN, SPENDER, 100L)
                                .addNftAllowance(OWNER, NON_FUNGIBLE_TOKEN, SPENDER, false, List.of(1L))
                                .addCryptoAllowance(SECOND_OWNER, SPENDER, ONE_HBAR)
                                .addTokenAllowance(SECOND_OWNER, FUNGIBLE_TOKEN, SPENDER, 100L)
                                .addNftAllowance(SECOND_OWNER, NON_FUNGIBLE_TOKEN, SPENDER, false, List.of(4L))
                                .hasKnownStatus(INVALID_SIGNATURE))
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatchDefaultOperator(cryptoApproveAllowance()
                                .payingWith(DEFAULT_PAYER)
                                .addCryptoAllowance(OWNER, SPENDER, ONE_HBAR)
                                .addTokenAllowance(OWNER, FUNGIBLE_TOKEN, SPENDER, 100L)
                                .addNftAllowance(OWNER, NON_FUNGIBLE_TOKEN, SPENDER, false, List.of(1L))
                                .addCryptoAllowance(SECOND_OWNER, SPENDER, ONE_HBAR)
                                .addTokenAllowance(SECOND_OWNER, FUNGIBLE_TOKEN, SPENDER, 100L)
                                .addNftAllowance(SECOND_OWNER, NON_FUNGIBLE_TOKEN, SPENDER, false, List.of(4L))
                                .signedBy(DEFAULT_PAYER, OWNER)
                                .hasKnownStatus(INVALID_SIGNATURE))
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatchDefaultOperator(cryptoApproveAllowance()
                                .payingWith(DEFAULT_PAYER)
                                .addCryptoAllowance(OWNER, SPENDER, ONE_HBAR)
                                .addTokenAllowance(OWNER, FUNGIBLE_TOKEN, SPENDER, 100L)
                                .addNftAllowance(OWNER, NON_FUNGIBLE_TOKEN, SPENDER, false, List.of(1L))
                                .addCryptoAllowance(SECOND_OWNER, SPENDER, ONE_HBAR)
                                .addTokenAllowance(SECOND_OWNER, FUNGIBLE_TOKEN, SPENDER, 100L)
                                .addNftAllowance(SECOND_OWNER, NON_FUNGIBLE_TOKEN, SPENDER, false, List.of(4L))
                                .signedBy(DEFAULT_PAYER, SECOND_OWNER)
                                .hasKnownStatus(INVALID_SIGNATURE))
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatchDefaultOperator(cryptoApproveAllowance()
                        .payingWith(DEFAULT_PAYER)
                        .addCryptoAllowance(OWNER, SPENDER, ONE_HBAR)
                        .addTokenAllowance(OWNER, FUNGIBLE_TOKEN, SPENDER, 100L)
                        .addNftAllowance(OWNER, NON_FUNGIBLE_TOKEN, SPENDER, false, List.of(1L))
                        .addCryptoAllowance(SECOND_OWNER, SPENDER, 2 * ONE_HBAR)
                        .addTokenAllowance(SECOND_OWNER, FUNGIBLE_TOKEN, SPENDER, 300L)
                        .addNftAllowance(SECOND_OWNER, NON_FUNGIBLE_TOKEN, SPENDER, false, List.of(4L, 5L))
                        .signedBy(DEFAULT_PAYER, OWNER, SECOND_OWNER)),
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

    /**
     * Tests that the order of serials in approveForAllNftAllowance doesn't matter inside a batch transaction.
     * @return hapi test
     */
    @HapiTest
    public final Stream<DynamicTest> serialsInAscendingOrder() {
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
                atomicBatchDefaultOperator(
                        tokenAssociate(OWNER, NON_FUNGIBLE_TOKEN),
                        mintToken(
                                        NON_FUNGIBLE_TOKEN,
                                        List.of(
                                                ByteString.copyFromUtf8("a"),
                                                ByteString.copyFromUtf8("b"),
                                                ByteString.copyFromUtf8("c"),
                                                ByteString.copyFromUtf8("d")))
                                .via(NFT_TOKEN_MINT_TXN),
                        cryptoTransfer(
                                movingUnique(NON_FUNGIBLE_TOKEN, 1L, 2L, 3L, 4L).between(TOKEN_TREASURY, OWNER)),
                        cryptoApproveAllowance()
                                .payingWith(OWNER)
                                .addNftAllowance(OWNER, NON_FUNGIBLE_TOKEN, SPENDER, true, List.of(1L))
                                .fee(ONE_HBAR),
                        cryptoApproveAllowance()
                                .payingWith(OWNER)
                                .addNftAllowance(OWNER, NON_FUNGIBLE_TOKEN, ANOTHER_SPENDER, false, List.of(4L, 2L, 3L))
                                .fee(ONE_HBAR)),
                getAccountDetails(OWNER)
                        .payingWith(GENESIS)
                        .logged()
                        .has(accountDetailsWith()
                                .nftApprovedForAllAllowancesCount(1)
                                .nftApprovedAllowancesContaining(NON_FUNGIBLE_TOKEN, SPENDER)));
    }

    /**
     * Tests that a token can be paused, frozen, and KYC revoked inside a batch transaction.
     * @return hapi test
     */
    @HapiTest
    public final Stream<DynamicTest> succeedsWhenTokenPausedFrozenKycRevoked() {
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
                        .tokenType(FUNGIBLE_COMMON)
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
                atomicBatchDefaultOperator(
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
                        cryptoTransfer(
                                movingUnique(NON_FUNGIBLE_TOKEN, 1L, 2L, 3L).between(TOKEN_TREASURY, OWNER)),
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
                                .fee(ONE_HBAR)),
                getAccountDetails(OWNER)
                        .payingWith(GENESIS)
                        .has(accountDetailsWith()
                                .cryptoAllowancesCount(0)
                                .nftApprovedForAllAllowancesCount(0)
                                .tokenAllowancesCount(4)));
    }

    /**
     * Tests that a token cannot be minted if the mint exceeds the max supply.
     * @return hapi test
     */
    @HapiTest
    public final Stream<DynamicTest> tokenExceedsMaxSupplyFails() {
        return hapiTest(
                newKeyNamed(SUPPLY_KEY),
                cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                cryptoCreate(SPENDER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(TOKEN_TREASURY).balance(100 * ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                tokenCreate(FUNGIBLE_TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
                        .supplyType(TokenSupplyType.FINITE)
                        .supplyKey(SUPPLY_KEY)
                        .maxSupply(1000L)
                        .initialSupply(10L)
                        .treasury(TOKEN_TREASURY),
                atomicBatchDefaultOperator(
                                tokenAssociate(OWNER, FUNGIBLE_TOKEN),
                                mintToken(FUNGIBLE_TOKEN, 500L).via(FUNGIBLE_TOKEN_MINT_TXN),
                                cryptoApproveAllowance()
                                        .payingWith(OWNER)
                                        .addTokenAllowance(OWNER, FUNGIBLE_TOKEN, SPENDER, 5000L)
                                        .fee(ONE_HUNDRED_HBARS)
                                        .hasKnownStatus(AMOUNT_EXCEEDS_TOKEN_MAX_SUPPLY))
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    /**
     * Tests that an invalid serial number in the NFT allowance fails inside a batch transaction.
     * @return hapi test
     */
    @HapiTest
    public final Stream<DynamicTest> validatesSerialNums() {
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
                atomicBatchDefaultOperator(cryptoApproveAllowance()
                                .payingWith(OWNER)
                                .addNftAllowance(OWNER, NON_FUNGIBLE_TOKEN, SPENDER, false, List.of(1000L))
                                .fee(ONE_HUNDRED_HBARS)
                                .hasKnownStatus(INVALID_TOKEN_NFT_SERIAL_NUMBER))
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatchDefaultOperator(cryptoApproveAllowance()
                                .payingWith(OWNER)
                                .addNftAllowance(OWNER, NON_FUNGIBLE_TOKEN, SPENDER, false, List.of(-1000L))
                                .fee(ONE_HUNDRED_HBARS)
                                .hasKnownStatus(INVALID_TOKEN_NFT_SERIAL_NUMBER))
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatchDefaultOperator(cryptoApproveAllowance()
                                .payingWith(OWNER)
                                .addNftAllowance(OWNER, NON_FUNGIBLE_TOKEN, SPENDER, false, List.of(3L))
                                .fee(ONE_HUNDRED_HBARS)
                                .hasKnownStatus(SENDER_DOES_NOT_OWN_NFT_SERIAL_NO))
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatchDefaultOperator(cryptoApproveAllowance()
                        .payingWith(OWNER)
                        .addNftAllowance(OWNER, NON_FUNGIBLE_TOKEN, SPENDER, false, List.of(2L, 2L, 2L))
                        .fee(ONE_HUNDRED_HBARS)));
    }

    /**
     * Tests that an invalid token type in the allowance fails inside a batch transaction.
     * @return hapi test
     */
    @HapiTest
    public final Stream<DynamicTest> invalidTokenTypeFails() {
        return hapiTest(
                newKeyNamed(SUPPLY_KEY),
                cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                cryptoCreate(SPENDER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(TOKEN_TREASURY).balance(100 * ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                tokenCreate(FUNGIBLE_TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
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
                atomicBatchDefaultOperator(cryptoApproveAllowance()
                                .payingWith(OWNER)
                                .addTokenAllowance(OWNER, NON_FUNGIBLE_TOKEN, SPENDER, 100L)
                                .fee(ONE_HUNDRED_HBARS)
                                .hasKnownStatus(NFT_IN_FUNGIBLE_TOKEN_ALLOWANCES))
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatchDefaultOperator(cryptoApproveAllowance()
                                .payingWith(OWNER)
                                .addNftAllowance(OWNER, FUNGIBLE_TOKEN, SPENDER, false, List.of(1L))
                                .fee(ONE_HUNDRED_HBARS)
                                .hasKnownStatus(FUNGIBLE_TOKEN_IN_NFT_ALLOWANCES))
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    /**
     * Tests that an empty allowances list is rejected inside a batch transaction.
     * @return hapi test
     */
    @HapiTest
    public final Stream<DynamicTest> emptyAllowancesRejected() {
        return hapiTest(
                cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                atomicBatchDefaultOperator(cryptoApproveAllowance()
                                .hasPrecheck(EMPTY_ALLOWANCES)
                                .fee(ONE_HUNDRED_HBARS))
                        .hasPrecheck(EMPTY_ALLOWANCES));
    }

    /**
     * Tests that a token not associated to the account fails inside a batch transaction.
     * @return hapi test
     */
    @HapiTest
    public final Stream<DynamicTest> tokenNotAssociatedToAccountFails() {
        return hapiTest(
                newKeyNamed(SUPPLY_KEY),
                cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                cryptoCreate(SPENDER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(TOKEN_TREASURY).balance(100 * ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                tokenCreate(FUNGIBLE_TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
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
                atomicBatchDefaultOperator(cryptoApproveAllowance()
                                .payingWith(OWNER)
                                .addTokenAllowance(OWNER, FUNGIBLE_TOKEN, SPENDER, 100L)
                                .fee(ONE_HUNDRED_HBARS)
                                .hasKnownStatus(TOKEN_NOT_ASSOCIATED_TO_ACCOUNT))
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatchDefaultOperator(cryptoApproveAllowance()
                                .payingWith(OWNER)
                                .addNftAllowance(OWNER, NON_FUNGIBLE_TOKEN, SPENDER, false, List.of(1L))
                                .fee(ONE_HUNDRED_HBARS)
                                .hasKnownStatus(TOKEN_NOT_ASSOCIATED_TO_ACCOUNT))
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                getAccountDetails(OWNER)
                        .payingWith(GENESIS)
                        .has(accountDetailsWith()
                                .cryptoAllowancesCount(0)
                                .nftApprovedForAllAllowancesCount(0)
                                .tokenAllowancesCount(0)));
    }

    /**
     * Tests that a negative amount in the allowance fails for fungible tokens inside a batch transaction.
     * @return hapi test
     */
    @HapiTest
    public final Stream<DynamicTest> negativeAmountFailsForFungible() {
        return hapiTest(
                newKeyNamed(SUPPLY_KEY),
                cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                cryptoCreate(SPENDER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(TOKEN_TREASURY).balance(100 * ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                tokenCreate(FUNGIBLE_TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
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
                atomicBatchDefaultOperator(
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
                        cryptoTransfer(
                                movingUnique(NON_FUNGIBLE_TOKEN, 1L, 2L, 3L).between(TOKEN_TREASURY, OWNER))),
                atomicBatchDefaultOperator(cryptoApproveAllowance()
                                .payingWith(OWNER)
                                .addCryptoAllowance(OWNER, SPENDER, -100L)
                                .fee(ONE_HUNDRED_HBARS)
                                .hasKnownStatus(NEGATIVE_ALLOWANCE_AMOUNT))
                        .hasPrecheck(NEGATIVE_ALLOWANCE_AMOUNT),
                atomicBatchDefaultOperator(cryptoApproveAllowance()
                                .payingWith(OWNER)
                                .addTokenAllowance(OWNER, FUNGIBLE_TOKEN, SPENDER, -100L)
                                .fee(ONE_HUNDRED_HBARS)
                                .hasKnownStatus(NEGATIVE_ALLOWANCE_AMOUNT))
                        .hasPrecheck(NEGATIVE_ALLOWANCE_AMOUNT),
                getAccountDetails(OWNER)
                        .payingWith(GENESIS)
                        .has(accountDetailsWith()
                                .cryptoAllowancesCount(0)
                                .nftApprovedForAllAllowancesCount(0)
                                .tokenAllowancesCount(0)));
    }

    /**
     * Tests that a negative amount in the allowance fails for fungible tokens inside a batch transaction.
     * @return hapi test
     */
    @HapiTest
    public final Stream<DynamicTest> chargedUsdScalesWithAllowances() {
        final var batchTxn = "batchTxn";
        return hapiTest(
                newKeyNamed(SUPPLY_KEY),
                cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                cryptoCreate(SPENDER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(ANOTHER_SPENDER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(SECOND_SPENDER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(THIRD_SPENDER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(TOKEN_TREASURY).balance(100 * ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                atomicBatchDefaultOperator(
                                cryptoApproveAllowance()
                                        .payingWith(SPENDER)
                                        .addCryptoAllowance(SPENDER, ANOTHER_SPENDER, 100L)
                                        .via(BASE_APPROVE_TXN + "_1")
                                        .blankMemo(),
                                cryptoApproveAllowance()
                                        .payingWith(SPENDER)
                                        .addCryptoAllowance(SPENDER, ANOTHER_SPENDER, 100L)
                                        .addCryptoAllowance(SPENDER, SECOND_SPENDER, 100L)
                                        .via(BASE_APPROVE_TXN + "_2")
                                        .blankMemo(),
                                cryptoApproveAllowance()
                                        .payingWith(SPENDER)
                                        .addCryptoAllowance(SPENDER, ANOTHER_SPENDER, 100L)
                                        .addCryptoAllowance(SPENDER, SECOND_SPENDER, 100L)
                                        .addCryptoAllowance(SPENDER, THIRD_SPENDER, 100L)
                                        .via(BASE_APPROVE_TXN + "_3")
                                        .blankMemo())
                        .via(batchTxn),
                validateInnerTxnChargedUsd(BASE_APPROVE_TXN + "_1", batchTxn, 0.05, 0.01),
                validateInnerTxnChargedUsd(BASE_APPROVE_TXN + "_2", batchTxn, 0.0505, 0.1),
                validateInnerTxnChargedUsd(BASE_APPROVE_TXN + "_3", batchTxn, 0.0509, 0.1));
    }

    /**
     * Tests that a batch transaction with multiple cryptoApproveAllowance works as expected.
     * @return hapi test
     */
    @HapiTest
    public final Stream<DynamicTest> happyPathWorks() {
        final var batchTxn = "batchTxn";
        return hapiTest(
                newKeyNamed(SUPPLY_KEY),
                cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                cryptoCreate(SPENDER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(ANOTHER_SPENDER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(TOKEN_TREASURY).balance(100 * ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                tokenCreate(FUNGIBLE_TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
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
                atomicBatchDefaultOperator(
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
                                cryptoTransfer(movingUnique(NON_FUNGIBLE_TOKEN, 1L, 2L, 3L)
                                        .between(TOKEN_TREASURY, OWNER)),
                                cryptoApproveAllowance()
                                        .payingWith(OWNER)
                                        .addCryptoAllowance(OWNER, SPENDER, 100L)
                                        .via(BASE_APPROVE_TXN)
                                        .blankMemo(),
                                cryptoApproveAllowance()
                                        .payingWith(OWNER)
                                        .addCryptoAllowance(OWNER, ANOTHER_SPENDER, 100L)
                                        .addTokenAllowance(OWNER, FUNGIBLE_TOKEN, SPENDER, 100L)
                                        .addNftAllowance(OWNER, NON_FUNGIBLE_TOKEN, SPENDER, false, List.of(1L))
                                        .via(APPROVE_TXN)
                                        .blankMemo())
                        .via(batchTxn),
                validateInnerTxnChargedUsd(BASE_APPROVE_TXN, batchTxn, 0.05, 0.01),
                validateInnerTxnChargedUsd(APPROVE_TXN, batchTxn, 0.052_380, 0.01),
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

    /**
     * Tests that duplicated allowances are replaced with the last one inside a batch transaction.
     * @return hapi test
     */
    @HapiTest
    public final Stream<DynamicTest> duplicateEntriesGetsReplacedWithDifferentTxn() {
        return hapiTest(
                newKeyNamed(SUPPLY_KEY),
                cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                cryptoCreate(SPENDER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(TOKEN_TREASURY).balance(100 * ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                tokenCreate(FUNGIBLE_TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
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
                atomicBatchDefaultOperator(
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
                        cryptoTransfer(
                                movingUnique(NON_FUNGIBLE_TOKEN, 1L, 2L, 3L).between(TOKEN_TREASURY, OWNER)),
                        cryptoApproveAllowance()
                                .payingWith(OWNER)
                                .addCryptoAllowance(OWNER, SPENDER, 100L)
                                .addTokenAllowance(OWNER, FUNGIBLE_TOKEN, SPENDER, 100L)
                                .addNftAllowance(OWNER, NON_FUNGIBLE_TOKEN, SPENDER, true, List.of(1L, 2L))
                                .via(BASE_APPROVE_TXN)
                                .blankMemo(),
                        cryptoApproveAllowance()
                                .payingWith(OWNER)
                                .addCryptoAllowance(OWNER, SPENDER, 200L)
                                .addTokenAllowance(OWNER, FUNGIBLE_TOKEN, SPENDER, 300L)
                                .addNftAllowance(OWNER, NON_FUNGIBLE_TOKEN, SPENDER, false, List.of(3L))
                                .via("duplicateAllowances"),
                        cryptoApproveAllowance()
                                .payingWith(OWNER)
                                .addCryptoAllowance(OWNER, SPENDER, 0L)
                                .addTokenAllowance(OWNER, FUNGIBLE_TOKEN, SPENDER, 0L)
                                .addNftAllowance(OWNER, NON_FUNGIBLE_TOKEN, SPENDER, true, List.of())
                                .via("removeAllowances")),
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

    /**
     * Tests that can't have multiple allowed spenders for the same NFT serial number inside a batch transaction.
     * @return hapi test
     */
    @HapiTest
    public final Stream<DynamicTest> cannotHaveMultipleAllowedSpendersForTheSameNftSerial() {
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

                // approve for all
                atomicBatchDefaultOperator(
                        cryptoApproveAllowance()
                                .payingWith(DEFAULT_PAYER)
                                .addNftAllowance(OWNER, NON_FUNGIBLE_TOKEN, SPENDER, true, List.of(1L))
                                .signedBy(DEFAULT_PAYER, OWNER),
                        cryptoApproveAllowance()
                                .payingWith(DEFAULT_PAYER)
                                .addNftAllowance(OWNER, NON_FUNGIBLE_TOKEN, SECOND_SPENDER, true, List.of(1L))
                                .signedBy(DEFAULT_PAYER, OWNER),

                        // Queries are not allowed in batch, but the comments here are good for keeping track
                        // of what is supposed to happen.

                        //                      getTokenNftInfo(NON_FUNGIBLE_TOKEN, 1L)
                        //                              .hasSpenderID(SECOND_SPENDER),
                        //                      getAccountDetails(OWNER)
                        //                              .payingWith(GENESIS)
                        //                              .has(accountDetailsWith().nftApprovedForAllAllowancesCount(2)),
                        cryptoTransfer(movingUniqueWithAllowance(NON_FUNGIBLE_TOKEN, 1)
                                        .between(OWNER, RECEIVER))
                                .payingWith(SECOND_SPENDER)
                                .signedBy(SECOND_SPENDER),
                        //                      getTokenNftInfo(NON_FUNGIBLE_TOKEN, 1L).hasNoSpender().logged(),
                        cryptoTransfer(movingUnique(NON_FUNGIBLE_TOKEN, 1).between(RECEIVER, OWNER)),
                        cryptoTransfer(movingUniqueWithAllowance(NON_FUNGIBLE_TOKEN, 1)
                                        .between(OWNER, RECEIVER))
                                .payingWith(SECOND_SPENDER)
                                .signedBy(SECOND_SPENDER),
                        // delete OWNER -> SECOND_SPENDER allowance
                        cryptoApproveAllowance()
                                .payingWith(DEFAULT_PAYER)
                                .addNftAllowance(OWNER, NON_FUNGIBLE_TOKEN, SECOND_SPENDER, false, List.of())
                                .signedBy(DEFAULT_PAYER, OWNER),
                        // return serial 1 to OWNER
                        cryptoTransfer(movingUnique(NON_FUNGIBLE_TOKEN, 1).between(RECEIVER, OWNER))),
                getAccountDetails(OWNER)
                        .payingWith(GENESIS)
                        .has(accountDetailsWith().nftApprovedForAllAllowancesCount(1)),
                cryptoTransfer(movingUniqueWithAllowance(NON_FUNGIBLE_TOKEN, 1).between(OWNER, RECEIVER))
                        .payingWith(SECOND_SPENDER)
                        .signedBy(SECOND_SPENDER)
                        .hasKnownStatus(SPENDER_DOES_NOT_HAVE_ALLOWANCE),

                // approve for all is false
                atomicBatchDefaultOperator(
                                cryptoApproveAllowance()
                                        .payingWith(DEFAULT_PAYER)
                                        .addNftAllowance(OWNER, NON_FUNGIBLE_TOKEN, SECOND_SPENDER, false, List.of(1L))
                                        .signedBy(DEFAULT_PAYER, OWNER),
                                cryptoTransfer(movingUniqueWithAllowance(NON_FUNGIBLE_TOKEN, 1)
                                                .between(OWNER, RECEIVER))
                                        .payingWith(SECOND_SPENDER)
                                        .signedBy(SECOND_SPENDER),
                                cryptoTransfer(
                                        movingUnique(NON_FUNGIBLE_TOKEN, 1).between(RECEIVER, OWNER)),
                                cryptoTransfer(movingUniqueWithAllowance(NON_FUNGIBLE_TOKEN, 1)
                                                .between(OWNER, RECEIVER))
                                        .payingWith(SECOND_SPENDER)
                                        .signedBy(SECOND_SPENDER)
                                        .hasKnownStatus(SPENDER_DOES_NOT_HAVE_ALLOWANCE))
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    /**
     * Tests that approving for all does not set the explicit spender for the NFT.
     * @return hapi test
     */
    @HapiTest
    public final Stream<DynamicTest> approveForAllDoesNotSetExplicitNftSpender() {
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
                atomicBatchDefaultOperator(
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
                        //                getTokenNftInfo(NON_FUNGIBLE_TOKEN, 1L).hasNoSpender().logged(),
                        //                getAccountDetails(OWNER)
                        //                        .payingWith(GENESIS)
                        //                        .has(accountDetailsWith().nftApprovedForAllAllowancesCount(1)),
                        cryptoTransfer(movingUniqueWithAllowance(NON_FUNGIBLE_TOKEN, 1)
                                        .between(OWNER, RECEIVER))
                                .payingWith(SPENDER)),
                getTokenNftInfo(NON_FUNGIBLE_TOKEN, 1L).hasNoSpender().logged());
    }

    /**
     * Tests various negative cases for approving allowances inside a batch transaction.
     * @return hapi test
     */
    @HapiTest
    public final Stream<DynamicTest> approveNegativeCases() {
        final var tryApprovingTheSender = "tryApprovingTheSender";
        final var tryApprovingAboveBalance = "tryApprovingAboveBalance";
        final var tryApprovingNftToOwner = "tryApprovingNFTToOwner";
        final var tryApprovingNftWithInvalidSerial = "tryApprovingNFTWithInvalidSerial";
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
                        .maxSupply(10_000)
                        .initialSupply(5000),
                tokenCreate(NON_FUNGIBLE_TOKEN)
                        .supplyType(TokenSupplyType.FINITE)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .treasury(TOKEN_TREASURY)
                        .maxSupply(12L)
                        .supplyKey(SUPPLY_KEY)
                        .initialSupply(0L),
                atomicBatchDefaultOperator(
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
                                .via(tryApprovingAboveBalance)),
                atomicBatchDefaultOperator(cryptoApproveAllowance()
                                .payingWith(DEFAULT_PAYER)
                                .addNftAllowance(OWNER, NON_FUNGIBLE_TOKEN, OWNER, false, List.of(1L))
                                .signedBy(DEFAULT_PAYER, OWNER)
                                .hasKnownStatus(SPENDER_ACCOUNT_SAME_AS_OWNER)
                                .via(tryApprovingNftToOwner))
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatchDefaultOperator(cryptoApproveAllowance()
                                .payingWith(DEFAULT_PAYER)
                                .addNftAllowance(OWNER, NON_FUNGIBLE_TOKEN, SPENDER, false, List.of(1L, 2L, 3L, 4L))
                                .signedBy(DEFAULT_PAYER, OWNER)
                                .hasKnownStatus(INVALID_TOKEN_NFT_SERIAL_NUMBER)
                                .via(tryApprovingNftWithInvalidSerial))
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                emptyChildRecordsCheck(tryApprovingTheSender, SUCCESS),
                emptyChildRecordsCheck(tryApprovingAboveBalance, SUCCESS),
                emptyChildRecordsCheck(tryApprovingNftToOwner, SPENDER_ACCOUNT_SAME_AS_OWNER),
                emptyChildRecordsCheck(tryApprovingNftWithInvalidSerial, INVALID_TOKEN_NFT_SERIAL_NUMBER),
                getAccountBalance(OWNER).hasTokenBalance(FUNGIBLE_TOKEN, 500L),
                getAccountBalance(SPENDER).hasTokenBalance(FUNGIBLE_TOKEN, 0L),
                getAccountBalance(OWNER).hasTokenBalance(NON_FUNGIBLE_TOKEN, 3L),
                getAccountBalance(SPENDER).hasTokenBalance(NON_FUNGIBLE_TOKEN, 0L));
    }

    /**
     * Tests that approving allowances for deleted tokens fails inside a batch transaction.
     * @return hapi test
     */
    @HapiTest
    public final Stream<DynamicTest> approveAllowanceForDeletedToken() {
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
                atomicBatchDefaultOperator(
                                tokenDelete(FUNGIBLE_TOKEN),
                                // try to approve allowance for deleted fungible token
                                cryptoApproveAllowance()
                                        .addTokenAllowance(OWNER, FUNGIBLE_TOKEN, SPENDER, 100L)
                                        .signedBy(DEFAULT_PAYER, OWNER)
                                        .hasKnownStatus(TOKEN_WAS_DELETED))
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatchDefaultOperator(
                                tokenDelete(NON_FUNGIBLE_TOKEN),
                                // try to approve allowance for deleted nft token
                                cryptoApproveAllowance()
                                        .addNftAllowance(OWNER, NON_FUNGIBLE_TOKEN, SPENDER, true, List.of())
                                        .signedBy(DEFAULT_PAYER, OWNER)
                                        .hasKnownStatus(TOKEN_WAS_DELETED))
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    /**
     * Tests that approving allowances for deleted tokens fails inside a batch transaction.
     * @return hapi test
     */
    @HapiTest
    public final Stream<DynamicTest> approveAllowanceToOwner() {
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
                atomicBatchDefaultOperator(
                                tokenAssociate(OWNER, FUNGIBLE_TOKEN, NON_FUNGIBLE_TOKEN),
                                cryptoTransfer(
                                        moving(500L, FUNGIBLE_TOKEN).between(TOKEN_TREASURY, OWNER),
                                        movingUnique(NON_FUNGIBLE_TOKEN, 1L).between(TOKEN_TREASURY, OWNER)),
                                // try to approve allowance to the owner
                                cryptoApproveAllowance()
                                        .addTokenAllowance(OWNER, FUNGIBLE_TOKEN, OWNER, 100L)
                                        .signedBy(DEFAULT_PAYER, OWNER),
                                // try to approve allowance to the owner
                                cryptoApproveAllowance()
                                        .addNftAllowance(OWNER, NON_FUNGIBLE_TOKEN, OWNER, true, List.of())
                                        .signedBy(DEFAULT_PAYER, OWNER)
                                        .hasKnownStatus(SPENDER_ACCOUNT_SAME_AS_OWNER))
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    /**
     * Tests that delegating an allowance from a deleted spender fails inside a batch transaction.
     * @return hapi test
     */
    @HapiTest
    public final Stream<DynamicTest> delegateAllowanceFromDeletedSpender() {
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
                atomicBatchDefaultOperator(
                                mintToken(NON_FUNGIBLE_TOKEN, List.of(ByteString.copyFromUtf8("a"))),
                                tokenAssociate(OWNER, NON_FUNGIBLE_TOKEN),
                                tokenAssociate(SPENDER, NON_FUNGIBLE_TOKEN),
                                tokenAssociate(SECOND_SPENDER, NON_FUNGIBLE_TOKEN),
                                cryptoTransfer(
                                        movingUnique(NON_FUNGIBLE_TOKEN, 1L).between(TOKEN_TREASURY, OWNER)),
                                cryptoApproveAllowance()
                                        .addNftAllowance(OWNER, NON_FUNGIBLE_TOKEN, SPENDER, true, List.of())
                                        .signedByPayerAnd(OWNER),
                                cryptoDelete(SPENDER),
                                cryptoApproveAllowance()
                                        .payingWith(DEFAULT_PAYER)
                                        .addDelegatedNftAllowance(
                                                OWNER, NON_FUNGIBLE_TOKEN, SECOND_SPENDER, SPENDER, false, List.of(1L))
                                        .signedByPayerAnd(SPENDER)
                                        .hasKnownStatus(INVALID_DELEGATING_SPENDER))
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    private HapiAtomicBatch atomicBatchDefaultOperator(final HapiTxnOp<?>... ops) {
        return atomicBatch(Arrays.stream(ops)
                        .map(op -> op.batchKey(DEFAULT_BATCH_OPERATOR))
                        .toArray(HapiTxnOp[]::new))
                .payingWith(DEFAULT_BATCH_OPERATOR);
    }
}
