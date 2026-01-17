// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.leaky.batch;

import static com.google.protobuf.ByteString.EMPTY;
import static com.hedera.node.app.hapi.utils.EthSigsUtils.recoverAddressFromPubKey;
import static com.hedera.services.bdd.junit.ContextRequirement.FEE_SCHEDULE_OVERRIDES;
import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.spec.HapiPropertySource.asContract;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.accountWith;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.resultWith;
import static com.hedera.services.bdd.spec.assertions.ContractInfoAsserts.contractWith;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.keys.KeyFactory.KeyType.THRESHOLD;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.contractCallLocal;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAliasedAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getContractInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getReceipt;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTokenNftInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCallWithFunctionAbi;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.ethereumCallWithFunctionAbi;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uncheckedSubmit;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.contract.HapiParserUtil.asHeadlongAddress;
import static com.hedera.services.bdd.spec.transactions.contract.HapiParserUtil.asHeadlongAddressArray;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromAccountToAlias;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingUnique;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.SidecarVerbs.expectContractActionSidecarFor;
import static com.hedera.services.bdd.spec.utilops.SidecarVerbs.expectContractStateChangesSidecarFor;
import static com.hedera.services.bdd.spec.utilops.SidecarVerbs.sidecarValidation;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.assertionsHold;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.balanceSnapshot;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.childRecordsCheck;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.emptyChildRecordsCheck;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overridingTwo;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.reduceFeeFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sleepFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.usableTxnIdNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_CONTRACT_SENDER;
import static com.hedera.services.bdd.suites.HapiSuite.EMPTY_KEY;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.RELAYER;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SHAPE;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SOURCE_KEY;
import static com.hedera.services.bdd.suites.HapiSuite.THREE_MONTHS_IN_SECONDS;
import static com.hedera.services.bdd.suites.HapiSuite.TOKEN_TREASURY;
import static com.hedera.services.bdd.suites.contract.Utils.FunctionType.FUNCTION;
import static com.hedera.services.bdd.suites.contract.Utils.asAddress;
import static com.hedera.services.bdd.suites.contract.Utils.asHexedAddress;
import static com.hedera.services.bdd.suites.contract.Utils.asHexedSolidityAddress;
import static com.hedera.services.bdd.suites.contract.Utils.asSolidityAddress;
import static com.hedera.services.bdd.suites.contract.Utils.getABIFor;
import static com.hedera.services.bdd.suites.contract.Utils.mirrorAddrWith;
import static com.hedera.services.bdd.suites.contract.hapi.ContractCallSuite.ACCOUNT_INFO;
import static com.hedera.services.bdd.suites.contract.hapi.ContractCallSuite.ACCOUNT_INFO_AFTER_CALL;
import static com.hedera.services.bdd.suites.contract.hapi.ContractCallSuite.CALL_TX;
import static com.hedera.services.bdd.suites.contract.hapi.ContractCallSuite.CALL_TX_REC;
import static com.hedera.services.bdd.suites.contract.hapi.ContractCallSuite.CONTRACT_FROM;
import static com.hedera.services.bdd.suites.contract.hapi.ContractCallSuite.DEPOSIT;
import static com.hedera.services.bdd.suites.contract.hapi.ContractCallSuite.PAY_RECEIVABLE_CONTRACT;
import static com.hedera.services.bdd.suites.contract.hapi.ContractCallSuite.SIMPLE_UPDATE_CONTRACT;
import static com.hedera.services.bdd.suites.contract.hapi.ContractCallSuite.TRANSFERRING_CONTRACT;
import static com.hedera.services.bdd.suites.contract.hapi.ContractCallSuite.TRANSFER_TO_CALLER;
import static com.hedera.services.bdd.suites.contract.hapi.ContractCreateSuite.EMPTY_CONSTRUCTOR_CONTRACT;
import static com.hedera.services.bdd.suites.contract.precompile.CreatePrecompileSuite.TOKEN_NAME;
import static com.hedera.services.bdd.suites.contract.precompile.ERCPrecompileSuite.NAME_TXN;
import static com.hedera.services.bdd.suites.contract.traceability.EncodingUtils.formattedAssertionValue;
import static com.hedera.services.bdd.suites.crypto.AutoAccountCreationSuite.LAZY_MEMO;
import static com.hedera.services.bdd.suites.crypto.AutoCreateUtils.updateSpecFor;
import static com.hedera.services.bdd.suites.crypto.CryptoApproveAllowanceSuite.ADMIN_KEY;
import static com.hedera.services.bdd.suites.utils.contracts.AddressResult.hexedAddress;
import static com.hedera.services.bdd.suites.utils.contracts.precompile.HTSPrecompileResult.htsPrecompileResult;
import static com.hedera.services.stream.proto.ContractActionType.CALL;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INNER_TRANSACTION_FAILED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_ACCOUNT_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_PAYER_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_CONTRACT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SOLIDITY_ADDRESS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.MAX_CHILD_RECORDS_EXCEEDED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.MAX_GAS_LIMIT_EXCEEDED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;
import static org.hyperledger.besu.datatypes.Address.contractAddress;
import static org.hyperledger.besu.datatypes.Address.fromHexString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.ByteString;
import com.google.protobuf.BytesValue;
import com.hedera.node.app.hapi.utils.contracts.ParsingConstants.FunctionType;
import com.hedera.node.app.hapi.utils.ethereum.EthTxData;
import com.hedera.node.app.hapi.utils.fee.FeeBuilder;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.junit.OrderedInIsolation;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.HapiPropertySource;
import com.hedera.services.bdd.spec.assertions.ContractInfoAsserts;
import com.hedera.services.bdd.spec.assertions.StateChange;
import com.hedera.services.bdd.spec.assertions.StorageChange;
import com.hedera.services.bdd.spec.queries.QueryVerbs;
import com.hedera.services.bdd.spec.queries.meta.HapiGetTxnRecord;
import com.hedera.services.bdd.spec.transactions.contract.HapiParserUtil;
import com.hedera.services.bdd.spec.utilops.CustomSpecAssert;
import com.hedera.services.bdd.suites.contract.Utils;
import com.hedera.services.stream.proto.CallOperationType;
import com.hedera.services.stream.proto.ContractAction;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.TokenID;
import com.hederahashgraph.api.proto.java.TokenSupplyType;
import com.hederahashgraph.api.proto.java.TokenType;
import com.hederahashgraph.api.proto.java.TransactionRecord;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.stream.Stream;
import org.apache.logging.log4j.Logger;
import org.apache.tuweni.bytes.Bytes;
import org.hiero.base.utility.CommonUtils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;

// This test cases are direct copies of LeakyContractTestsSuite. The difference here is that
// we are wrapping the operations in an atomic batch to confirm that everything works as expected.
@SuppressWarnings("java:S1192") // "string literal should not be duplicated" - this rule makes test suites worse
@OrderedInIsolation
@HapiTestLifecycle
class AtomicLeakyContractTestsSuite {
    public static final String CREATE_TX = "createTX";
    public static final String CREATE_TX_REC = "createTXRec";
    public static final String FALSE = "false";
    public static final int GAS_TO_OFFER = 2_000_000;
    public static final String SENDER = "yahcliSender";
    public static final String RECEIVER = "yahcliReceiver";
    private static final String FUNGIBLE_TOKEN = "fungibleToken";
    private static final String NON_FUNGIBLE_TOKEN = "nonFungibleToken";
    private static final String MULTI_KEY = "purpose";
    private static final String ACCOUNT = "anybody";
    public static final String RECIPIENT = "recipient";
    private static final String FIRST = "FIRST";
    private static final ByteString FIRST_META = ByteString.copyFrom(FIRST.getBytes(StandardCharsets.UTF_8));
    public static final String ERC_20_CONTRACT = "ERC20Contract";
    private static final String TRANSFER_TXN = "transferTxn";
    public static final String TRANSFER = "transfer";
    private static final String NF_TOKEN = "nfToken";
    private static final String MULTI_KEY_NAME = "multiKey";
    private static final String A_CIVILIAN = "aCivilian";
    private static final String B_CIVILIAN = "bCivilian";
    private static final String GET_APPROVED = "outerGetApproved";
    private static final String GET_BALANCE_OF = "getBalanceOf";
    private static final String SOME_ERC_721_SCENARIOS = "SomeERC721Scenarios";
    private static final String GET_OWNER_OF = "getOwnerOf";
    private static final String MISSING_TOKEN = "MISSING_TOKEN";
    private static final String WITH_SPENDER = "WITH_SPENDER";
    private static final String DO_SPECIFIC_APPROVAL = "doSpecificApproval";
    public static final String GET_BYTECODE = "getBytecode";
    public static final String CONTRACT_REPORTED_LOG_MESSAGE = "Contract reported TestContract initcode is {} bytes";
    public static final String DEPLOY = "deploy";
    private static final long NONEXISTENT_CONTRACT_NUM = 1_234_567_890L;
    private static final String BATCH_OPERATOR = "batchOperator";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(
                Map.of("contracts.throttle.throttleByGas", "false", "cryptoCreateWithAlias.enabled", "false"));
        testLifecycle.doAdhoc(cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS));
    }

    @HapiTest
    @Order(27)
    final Stream<DynamicTest> transferErc20TokenFromErc721TokenFails() {
        return hapiTest(
                newKeyNamed(MULTI_KEY),
                cryptoCreate(ACCOUNT).balance(100 * ONE_MILLION_HBARS),
                cryptoCreate(RECIPIENT),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(NON_FUNGIBLE_TOKEN)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0)
                        .treasury(TOKEN_TREASURY)
                        .adminKey(MULTI_KEY)
                        .supplyKey(MULTI_KEY),
                mintToken(NON_FUNGIBLE_TOKEN, List.of(FIRST_META)),
                tokenAssociate(ACCOUNT, List.of(NON_FUNGIBLE_TOKEN)),
                tokenAssociate(RECIPIENT, List.of(NON_FUNGIBLE_TOKEN)),
                cryptoTransfer(movingUnique(NON_FUNGIBLE_TOKEN, 1).between(TOKEN_TREASURY, ACCOUNT))
                        .payingWith(ACCOUNT),
                uploadInitCode(ERC_20_CONTRACT),
                contractCreate(ERC_20_CONTRACT),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        atomicBatch(contractCall(
                                                ERC_20_CONTRACT,
                                                TRANSFER,
                                                HapiParserUtil.asHeadlongAddress(asAddress(
                                                        spec.registry().getTokenID(NON_FUNGIBLE_TOKEN))),
                                                HapiParserUtil.asHeadlongAddress(asAddress(
                                                        spec.registry().getAccountID(RECIPIENT))),
                                                BigInteger.TWO)
                                        .payingWith(ACCOUNT)
                                        .alsoSigningWithFullPrefix(MULTI_KEY)
                                        .via(TRANSFER_TXN)
                                        .gas(GAS_TO_OFFER)
                                        .hasKnownStatus(CONTRACT_REVERT_EXECUTED)
                                        .batchKey(BATCH_OPERATOR))
                                .payingWith(BATCH_OPERATOR)
                                .hasKnownStatus(INNER_TRANSACTION_FAILED))),
                getTxnRecord(TRANSFER_TXN).andAllChildRecords());
    }

    @Order(35)
    @LeakyHapiTest(requirement = FEE_SCHEDULE_OVERRIDES)
    final Stream<DynamicTest> getErc20TokenNameExceedingLimits() {
        final var REDUCED_NETWORK_FEE = 1L;
        final var REDUCED_NODE_FEE = 1L;
        final var REDUCED_SERVICE_FEE = 1L;
        final var INIT_ACCOUNT_BALANCE = 100 * ONE_HUNDRED_HBARS;
        return hapiTest(
                newKeyNamed(MULTI_KEY),
                cryptoCreate(ACCOUNT).balance(INIT_ACCOUNT_BALANCE),
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
                balanceSnapshot("accountSnapshot", ACCOUNT),
                reduceFeeFor(
                        HederaFunctionality.ContractCall, REDUCED_NODE_FEE, REDUCED_NETWORK_FEE, REDUCED_SERVICE_FEE),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        atomicBatch(contractCall(
                                                ERC_20_CONTRACT,
                                                "nameNTimes",
                                                asHeadlongAddress(asHexedAddress(
                                                        spec.registry().getTokenID(FUNGIBLE_TOKEN))),
                                                BigInteger.valueOf(51))
                                        .payingWith(ACCOUNT)
                                        .via(NAME_TXN)
                                        .gas(4_000_000)
                                        .hasKnownStatus(MAX_CHILD_RECORDS_EXCEEDED)
                                        .batchKey(BATCH_OPERATOR))
                                .payingWith(BATCH_OPERATOR)
                                .hasKnownStatus(INNER_TRANSACTION_FAILED))),
                getTxnRecord(NAME_TXN)
                        .andAllChildRecords()
                        .logged()
                        .hasPriority(recordWith()
                                .contractCallResult(resultWith()
                                        .error(Bytes.of(MAX_CHILD_RECORDS_EXCEEDED
                                                        .name()
                                                        .getBytes())
                                                .toHexString())
                                        .gasUsed(4_000_000))));
    }

    @HapiTest
    @Order(2)
    final Stream<DynamicTest> payerCannotOverSendValue() {
        final var payerBalance = 666 * ONE_HBAR;
        final var overdraftAmount = payerBalance + ONE_HBAR;
        final var overAmbitiousPayer = "overAmbitiousPayer";
        final var uncheckedCC = "uncheckedCC";
        return hapiTest(
                uploadInitCode(PAY_RECEIVABLE_CONTRACT),
                contractCreate(PAY_RECEIVABLE_CONTRACT).adminKey(THRESHOLD),
                cryptoCreate(overAmbitiousPayer).balance(payerBalance),
                contractCall(PAY_RECEIVABLE_CONTRACT, DEPOSIT, BigInteger.valueOf(overdraftAmount))
                        .payingWith(overAmbitiousPayer)
                        .sending(overdraftAmount)
                        .hasPrecheck(INSUFFICIENT_PAYER_BALANCE),
                usableTxnIdNamed(uncheckedCC).payerId(overAmbitiousPayer),
                uncheckedSubmit(atomicBatch(contractCall(
                                                PAY_RECEIVABLE_CONTRACT, DEPOSIT, BigInteger.valueOf(overdraftAmount))
                                        .txnId(uncheckedCC)
                                        .payingWith(overAmbitiousPayer)
                                        .sending(overdraftAmount)
                                        .batchKey(BATCH_OPERATOR))
                                .payingWith(BATCH_OPERATOR))
                        .payingWith(GENESIS),
                sleepFor(1_000),
                getReceipt(uncheckedCC)
                        // Mod-service and mono-service use these mostly interchangeably
                        .hasPriorityStatusFrom(INSUFFICIENT_PAYER_BALANCE, INSUFFICIENT_ACCOUNT_BALANCE)
                        .logged());
    }

    @HapiTest
    @Order(0)
    final Stream<DynamicTest> transferToCaller() {
        final var transferTxn = TRANSFER_TXN;
        final var sender = "sender";
        return hapiTest(
                uploadInitCode(TRANSFERRING_CONTRACT),
                contractCreate(TRANSFERRING_CONTRACT).balance(10_000L),
                cryptoCreate(sender).balance(ONE_HUNDRED_HBARS),
                getAccountInfo(sender).savingSnapshot(ACCOUNT_INFO).payingWith(GENESIS),
                withOpContext((spec, log) -> {
                    var transferCall = atomicBatch(
                                    contractCall(TRANSFERRING_CONTRACT, TRANSFER_TO_CALLER, BigInteger.valueOf(10))
                                            .payingWith(sender)
                                            .via(transferTxn)
                                            .logged()
                                            .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR);

                    var saveTxnRecord = getTxnRecord(transferTxn)
                            .saveTxnRecordToRegistry("txn")
                            .payingWith(GENESIS);
                    var saveAccountInfoAfterCall = getAccountInfo(sender)
                            .savingSnapshot(ACCOUNT_INFO_AFTER_CALL)
                            .payingWith(GENESIS);
                    var saveContractInfo =
                            getContractInfo(TRANSFERRING_CONTRACT).saveToRegistry(CONTRACT_FROM);

                    allRunFor(spec, transferCall, saveTxnRecord, saveAccountInfoAfterCall, saveContractInfo);
                }),
                assertionsHold((spec, opLog) -> {
                    final var fee = spec.registry().getTransactionRecord("txn").getTransactionFee();
                    final var accountBalanceBeforeCall =
                            spec.registry().getAccountInfo(ACCOUNT_INFO).getBalance();
                    final var accountBalanceAfterCall = spec.registry()
                            .getAccountInfo(ACCOUNT_INFO_AFTER_CALL)
                            .getBalance();
                    assertEquals(accountBalanceAfterCall, accountBalanceBeforeCall - fee + 10L);
                }),
                sourcing(() -> getContractInfo(TRANSFERRING_CONTRACT)
                        .has(contractWith().balance(10_000L - 10L))));
    }

    @LeakyHapiTest(overrides = {"contracts.maxRefundPercentOfGasLimit"})
    @Order(14)
    final Stream<DynamicTest> maxRefundIsMaxGasRefundConfiguredWhenTXGasPriceIsSmaller() {
        return hapiTest(
                overriding("contracts.maxRefundPercentOfGasLimit", "5"),
                uploadInitCode(SIMPLE_UPDATE_CONTRACT),
                contractCreate(SIMPLE_UPDATE_CONTRACT).gas(300_000L),
                atomicBatch(contractCall(SIMPLE_UPDATE_CONTRACT, "set", BigInteger.valueOf(5), BigInteger.valueOf(42))
                                .gas(300_000L)
                                .via(CALL_TX)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                withOpContext((spec, ignore) -> {
                    final var subop01 = getTxnRecord(CALL_TX).saveTxnRecordToRegistry(CALL_TX_REC);
                    allRunFor(spec, subop01);

                    final var gasUsed = spec.registry()
                            .getTransactionRecord(CALL_TX_REC)
                            .getContractCallResult()
                            .getGasUsed();
                    assertEquals(285000, gasUsed);
                }));
    }

    @SuppressWarnings("java:S5960")
    @LeakyHapiTest(overrides = {"ledger.autoRenewPeriod.maxDuration", "entities.maxLifetime"})
    final Stream<DynamicTest> contractCreationStoragePriceMatchesFinalExpiry() {
        final var toyMaker = "ToyMaker";
        final var createIndirectly = "CreateIndirectly";
        final var normalPayer = "normalPayer";
        final var longLivedPayer = "longLivedPayer";
        final var longLifetime = 100 * 7776000L;
        final AtomicLong normalPayerGasUsed = new AtomicLong();
        final AtomicLong longLivedPayerGasUsed = new AtomicLong();
        final AtomicReference<byte[]> toyMakerMirror = new AtomicReference<>();
        return hapiTest(
                overridingTwo(
                        "ledger.autoRenewPeriod.maxDuration", "" + longLifetime,
                        "entities.maxLifetime", "" + longLifetime),
                cryptoCreate(normalPayer),
                cryptoCreate(longLivedPayer).autoRenewSecs(longLifetime - 12345),
                uploadInitCode(toyMaker, createIndirectly),
                contractCreate(toyMaker).exposingContractIdTo(id -> toyMakerMirror.set(asSolidityAddress(id))),
                sourcing(() -> contractCreate(createIndirectly)
                        .autoRenewSecs(longLifetime - 12345)
                        .payingWith(GENESIS)),
                atomicBatch(contractCall(toyMaker, "make")
                                .payingWith(normalPayer)
                                .exposingGasTo((status, gasUsed) -> normalPayerGasUsed.set(gasUsed))
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                atomicBatch(contractCall(toyMaker, "make")
                                .payingWith(longLivedPayer)
                                .exposingGasTo((status, gasUsed) -> longLivedPayerGasUsed.set(gasUsed))
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                assertionsHold((spec, opLog) -> assertEquals(
                        normalPayerGasUsed.get(),
                        longLivedPayerGasUsed.get(),
                        "Payer expiry should not affect create storage" + " cost")),
                // Verify that we are still charged a "typical" amount despite the payer and
                // the original sender contract having extremely long expiry dates
                sourcing(() -> contractCall(createIndirectly, "makeOpaquely", asHeadlongAddress(toyMakerMirror.get()))
                        .payingWith(longLivedPayer)));
    }

    @LeakyHapiTest(overrides = {"contracts.maxGasPerTransaction"})
    final Stream<DynamicTest> gasLimitOverMaxGasLimitFailsPrecheck() {
        return hapiTest(
                uploadInitCode(SIMPLE_UPDATE_CONTRACT),
                uploadInitCode(EMPTY_CONSTRUCTOR_CONTRACT),
                contractCreate(SIMPLE_UPDATE_CONTRACT).gas(300_000L),
                overriding("contracts.maxGasPerTransaction", "100"),
                atomicBatch(contractCall(SIMPLE_UPDATE_CONTRACT, "set", BigInteger.valueOf(5), BigInteger.valueOf(42))
                                .gas(23_000L)
                                .hasKnownStatus(MAX_GAS_LIMIT_EXCEEDED)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                contractCreate(EMPTY_CONSTRUCTOR_CONTRACT).gas(1_000_000L).hasPrecheck(MAX_GAS_LIMIT_EXCEEDED));
    }

    @HapiTest
    @Order(5)
    final Stream<DynamicTest> transferZeroHbarsToCaller() {
        final var transferTxn = TRANSFER_TXN;
        return hapiTest(
                uploadInitCode(TRANSFERRING_CONTRACT),
                contractCreate(TRANSFERRING_CONTRACT).balance(10_000L),
                getAccountInfo(DEFAULT_CONTRACT_SENDER)
                        .savingSnapshot(ACCOUNT_INFO)
                        .payingWith(GENESIS),
                withOpContext((spec, log) -> {
                    var transferCall = atomicBatch(
                                    contractCall(TRANSFERRING_CONTRACT, TRANSFER_TO_CALLER, BigInteger.ZERO)
                                            .payingWith(DEFAULT_CONTRACT_SENDER)
                                            .via(transferTxn)
                                            .logged()
                                            .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR);

                    var saveTxnRecord = getTxnRecord(transferTxn)
                            .saveTxnRecordToRegistry("txn_registry")
                            .payingWith(GENESIS);
                    var saveAccountInfoAfterCall = getAccountInfo(DEFAULT_CONTRACT_SENDER)
                            .savingSnapshot(ACCOUNT_INFO_AFTER_CALL)
                            .payingWith(GENESIS);
                    var saveContractInfo =
                            getContractInfo(TRANSFERRING_CONTRACT).saveToRegistry(CONTRACT_FROM);

                    allRunFor(spec, transferCall, saveTxnRecord, saveAccountInfoAfterCall, saveContractInfo);
                }),
                assertionsHold((spec, opLog) -> {
                    final var fee =
                            spec.registry().getTransactionRecord("txn_registry").getTransactionFee();
                    final var accountBalanceBeforeCall =
                            spec.registry().getAccountInfo(ACCOUNT_INFO).getBalance();
                    final var accountBalanceAfterCall = spec.registry()
                            .getAccountInfo(ACCOUNT_INFO_AFTER_CALL)
                            .getBalance();
                    final var contractBalanceAfterCall =
                            spec.registry().getContractInfo(CONTRACT_FROM).getBalance();

                    assertEquals(accountBalanceAfterCall, accountBalanceBeforeCall - fee);
                    assertEquals(contractBalanceAfterCall, 10_000L);
                }));
    }

    @Order(1)
    @LeakyHapiTest(overrides = {"contracts.maxRefundPercentOfGasLimit"})
    final Stream<DynamicTest> resultSizeAffectsFees() {
        final var contract = "VerboseDeposit";
        final var TRANSFER_AMOUNT = 1_000L;
        BiConsumer<TransactionRecord, Logger> resultSizeFormatter = (rcd, txnLog) -> {
            final var result = rcd.getContractCallResult();
            txnLog.info(
                    "Contract call result FeeBuilder size = {}, fee = {}, result is"
                            + " [self-reported size = {}, '{}']",
                    () -> FeeBuilder.getContractFunctionSize(result),
                    rcd::getTransactionFee,
                    result.getContractCallResult()::size,
                    result::getContractCallResult);
            txnLog.info("  Literally :: {}", result);
        };

        return hapiTest(
                overriding("contracts.maxRefundPercentOfGasLimit", "100"),
                uploadInitCode(contract),
                contractCreate(contract),
                atomicBatch(contractCall(contract, DEPOSIT, TRANSFER_AMOUNT, 0L, "So we out-danced thought...")
                                .via("noLogsCallTxn")
                                .sending(TRANSFER_AMOUNT)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                atomicBatch(contractCall(contract, DEPOSIT, TRANSFER_AMOUNT, 5L, "So we out-danced thought...")
                                .via("loggedCallTxn")
                                .sending(TRANSFER_AMOUNT)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                assertionsHold((spec, assertLog) -> {
                    HapiGetTxnRecord noLogsLookup =
                            QueryVerbs.getTxnRecord("noLogsCallTxn").loggedWith(resultSizeFormatter);
                    HapiGetTxnRecord logsLookup =
                            QueryVerbs.getTxnRecord("loggedCallTxn").loggedWith(resultSizeFormatter);
                    allRunFor(spec, noLogsLookup, logsLookup);
                    final var unloggedRecord =
                            noLogsLookup.getResponse().getTransactionGetRecord().getTransactionRecord();
                    final var loggedRecord =
                            logsLookup.getResponse().getTransactionGetRecord().getTransactionRecord();
                    assertLog.info("Fee for logged record   = {}", loggedRecord::getTransactionFee);
                    assertLog.info("Fee for unlogged record = {}", unloggedRecord::getTransactionFee);
                    Assertions.assertNotEquals(
                            unloggedRecord.getTransactionFee(),
                            loggedRecord.getTransactionFee(),
                            "Result size should change the txn fee!");
                }));
    }

    @HapiTest
    @Order(8)
    @Tag(MATS)
    final Stream<DynamicTest> autoAssociationSlotsAppearsInInfo() {
        final int maxAutoAssociations = 100;
        final String CONTRACT = "Multipurpose";

        return hapiTest(
                newKeyNamed(ADMIN_KEY),
                uploadInitCode(CONTRACT),
                atomicBatch(contractCreate(CONTRACT)
                                .adminKey(ADMIN_KEY)
                                .maxAutomaticTokenAssociations(maxAutoAssociations)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                getContractInfo(CONTRACT)
                        .has(ContractInfoAsserts.contractWith().maxAutoAssociations(maxAutoAssociations)));
    }

    @Order(16)
    @LeakyHapiTest(overrides = {"contracts.maxRefundPercentOfGasLimit"})
    final Stream<DynamicTest> createMaxRefundIsMaxGasRefundConfiguredWhenTXGasPriceIsSmaller() {
        return hapiTest(
                overriding("contracts.maxRefundPercentOfGasLimit", "5"),
                uploadInitCode(EMPTY_CONSTRUCTOR_CONTRACT),
                atomicBatch(contractCreate(EMPTY_CONSTRUCTOR_CONTRACT)
                                .gas(300_000L)
                                .via(CREATE_TX)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                withOpContext((spec, ignore) -> {
                    final var subop01 = getTxnRecord(CREATE_TX).saveTxnRecordToRegistry(CREATE_TX_REC);
                    allRunFor(spec, subop01);

                    final var gasUsed = spec.registry()
                            .getTransactionRecord(CREATE_TX_REC)
                            .getContractCreateResult()
                            .getGasUsed();
                    assertEquals(285_000L, gasUsed);
                }));
    }

    @Order(11)
    @LeakyHapiTest(overrides = {"contracts.maxRefundPercentOfGasLimit"})
    final Stream<DynamicTest> createMinChargeIsTXGasUsedByContractCreate() {
        return hapiTest(
                overriding("contracts.maxRefundPercentOfGasLimit", "100"),
                uploadInitCode(EMPTY_CONSTRUCTOR_CONTRACT),
                atomicBatch(contractCreate(EMPTY_CONSTRUCTOR_CONTRACT)
                                .gas(300_000L)
                                .via(CREATE_TX)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                withOpContext((spec, ignore) -> {
                    final var subop01 = getTxnRecord(CREATE_TX).saveTxnRecordToRegistry(CREATE_TX_REC);
                    allRunFor(spec, subop01);

                    final var gasUsed = spec.registry()
                            .getTransactionRecord(CREATE_TX_REC)
                            .getContractCreateResult()
                            .getGasUsed();
                    assertTrue(gasUsed > 0L);
                }));
    }

    @HapiTest
    @Order(3)
    @Tag(MATS)
    final Stream<DynamicTest> propagatesNestedCreations() {
        final var call = "callTxn";
        final var creation = "createTxn";
        final var contract = "NestedCreations";

        final var adminKey = "adminKey";
        final var entityMemo = "JUST DO IT";
        final var customAutoRenew = 7776001L;
        final AtomicLong childNum = new AtomicLong();
        final AtomicLong grandChildNum = new AtomicLong();
        final AtomicReference<ByteString> expectedChildAddress = new AtomicReference<>();
        final AtomicReference<ByteString> expectedParentAddress = new AtomicReference<>();

        return hapiTest(
                newKeyNamed(adminKey),
                uploadInitCode(contract),
                contractCreate(contract)
                        .stakedNodeId(0)
                        .adminKey(adminKey)
                        .entityMemo(entityMemo)
                        .autoRenewSecs(customAutoRenew)
                        .via(creation),
                atomicBatch(contractCall(contract, "propagate")
                                .gas(4_000_000L)
                                .via(call)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                withOpContext((spec, opLog) -> {
                    final var parentNum = spec.registry().getContractId(contract);

                    final var expectedParentContractAddress = asHeadlongAddress(asSolidityAddress(parentNum))
                            .toString()
                            .toLowerCase()
                            .substring(2);
                    expectedParentAddress.set(ByteString.copyFrom(CommonUtils.unhex(expectedParentContractAddress)));

                    final var expectedChildContractAddress =
                            contractAddress(fromHexString(expectedParentContractAddress), 1L);
                    final var expectedGrandChildContractAddress = contractAddress(expectedChildContractAddress, 1L);
                    childNum.set(parentNum.getContractNum() + 1L);
                    expectedChildAddress.set(ByteString.copyFrom(expectedChildContractAddress.toArray()));
                    grandChildNum.set(parentNum.getContractNum() + 2L);

                    final var parentContractInfo =
                            getContractInfo(contract).has(contractWith().addressOrAlias(expectedParentContractAddress));
                    final var childContractInfo = getContractInfo(String.valueOf(childNum.get()))
                            .has(contractWith().addressOrAlias(expectedChildContractAddress.toUnprefixedHexString()));
                    final var grandChildContractInfo = getContractInfo(String.valueOf(grandChildNum.get()))
                            .has(contractWith()
                                    .addressOrAlias(expectedGrandChildContractAddress.toUnprefixedHexString()))
                            .logged();

                    allRunFor(spec, parentContractInfo, childContractInfo, grandChildContractInfo);
                }),
                sourcing(() -> childRecordsCheck(
                        call,
                        SUCCESS,
                        recordWith()
                                .contractCreateResult(resultWith().create1EvmAddress(expectedParentAddress.get(), 1L))
                                .status(SUCCESS),
                        recordWith()
                                .contractCreateResult(resultWith().create1EvmAddress(expectedChildAddress.get(), 1L))
                                .status(SUCCESS))),
                sourcing(() -> getContractInfo(String.valueOf(childNum.get()))
                        .logged()
                        .has(contractWith().propertiesInheritedFrom(contract))));
    }

    @LeakyHapiTest(overrides = {"contracts.maxRefundPercentOfGasLimit"})
    final Stream<DynamicTest> temporarySStoreRefundTest() {
        final var contract = "TemporarySStoreRefund";
        return hapiTest(
                overriding("contracts.maxRefundPercentOfGasLimit", "100"),
                uploadInitCode(contract),
                contractCreate(contract).gas(500_000L),
                atomicBatch(contractCall(contract, "holdTemporary", BigInteger.valueOf(10))
                                .via("tempHoldTx")
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                atomicBatch(contractCall(contract, "holdPermanently", BigInteger.valueOf(10))
                                .via("permHoldTx")
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                withOpContext((spec, opLog) -> {
                    final var subop01 = getTxnRecord("tempHoldTx")
                            .saveTxnRecordToRegistry("tempHoldTxRec")
                            .logged();
                    final var subop02 = getTxnRecord("permHoldTx")
                            .saveTxnRecordToRegistry("permHoldTxRec")
                            .logged();

                    CustomSpecAssert.allRunFor(spec, subop01, subop02);

                    final var gasUsedForTemporaryHoldTx = spec.registry()
                            .getTransactionRecord("tempHoldTxRec")
                            .getContractCallResult()
                            .getGasUsed();
                    final var gasUsedForPermanentHoldTx = spec.registry()
                            .getTransactionRecord("permHoldTxRec")
                            .getContractCallResult()
                            .getGasUsed();

                    Assertions.assertTrue(gasUsedForTemporaryHoldTx < 35000L);
                    Assertions.assertTrue(gasUsedForPermanentHoldTx > 40000L);
                }));
    }

    @Disabled
    @LeakyHapiTest(overrides = {"contracts.evm.version"})
    final Stream<DynamicTest> evmLazyCreateViaSolidityCall() {
        final var LAZY_CREATE_CONTRACT = "NestedLazyCreateContract";
        final var ECDSA_KEY = "ECDSAKey";
        final var callLazyCreateFunction = "nestedLazyCreateThenSendMore";
        final var revertingCallLazyCreateFunction = "nestedLazyCreateThenRevert";
        final var depositAmount = 1000;
        final var mirrorTxn = "mirrorTxn";
        final var revertingTxn = "revertingTxn";
        final var payTxn = "payTxn";
        final var evmAddressOfChildContract = new AtomicReference<BytesValue>();

        return hapiTest(
                sidecarValidation(),
                overriding("contracts.evm.version", "v0.34"),
                newKeyNamed(ECDSA_KEY).shape(SECP_256K1_SHAPE),
                uploadInitCode(LAZY_CREATE_CONTRACT),
                atomicBatch(contractCreate(LAZY_CREATE_CONTRACT)
                                .via(CALL_TX_REC)
                                .gas(6_000_000L)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                getTxnRecord(CALL_TX_REC).andAllChildRecords().logged().exposingAllTo(records -> {
                    final var lastChildResult = records.getLast().getContractCreateResult();
                    evmAddressOfChildContract.set(lastChildResult.getEvmAddress());
                }),
                withOpContext((spec, opLog) -> {
                    final var ecdsaKey = spec.registry().getKey(ECDSA_KEY);
                    final var keyBytes = ecdsaKey.getECDSASecp256K1().toByteArray();
                    final var address = asHeadlongAddress(recoverAddressFromPubKey(keyBytes));
                    final var evmAddress = ByteString.copyFrom(recoverAddressFromPubKey(keyBytes));
                    allRunFor(
                            spec,
                            // given invalid address that's not derived from an ECDSA key, should revert the transaction
                            atomicBatch(contractCall(
                                                    LAZY_CREATE_CONTRACT,
                                                    callLazyCreateFunction,
                                                    mirrorAddrWith(spec, NONEXISTENT_CONTRACT_NUM))
                                            .sending(depositAmount)
                                            .via(mirrorTxn)
                                            .hasKnownStatus(CONTRACT_REVERT_EXECUTED)
                                            .gas(6_000_000)
                                            .batchKey(BATCH_OPERATOR))
                                    .payingWith(BATCH_OPERATOR)
                                    .hasKnownStatus(INNER_TRANSACTION_FAILED),
                            emptyChildRecordsCheck(mirrorTxn, CONTRACT_REVERT_EXECUTED),
                            getAccountInfo(String.valueOf(NONEXISTENT_CONTRACT_NUM))
                                    .hasCostAnswerPrecheck(INVALID_ACCOUNT_ID),
                            // given a reverting contract call, should also revert the hollow account creation
                            atomicBatch(contractCall(LAZY_CREATE_CONTRACT, revertingCallLazyCreateFunction, address)
                                            .sending(depositAmount)
                                            .via(revertingTxn)
                                            .hasKnownStatus(CONTRACT_REVERT_EXECUTED)
                                            .gas(6_000_000)
                                            .batchKey(BATCH_OPERATOR))
                                    .payingWith(BATCH_OPERATOR)
                                    .hasKnownStatus(INNER_TRANSACTION_FAILED),
                            emptyChildRecordsCheck(revertingTxn, CONTRACT_REVERT_EXECUTED),
                            getAliasedAccountInfo(evmAddress).hasCostAnswerPrecheck(INVALID_ACCOUNT_ID),
                            // given a valid address that is derived from an ECDSA key, should create hollow account
                            atomicBatch(contractCall(LAZY_CREATE_CONTRACT, callLazyCreateFunction, address)
                                            .via(payTxn)
                                            .sending(depositAmount)
                                            .hasKnownStatus(SUCCESS)
                                            .gas(6_000_000)
                                            .batchKey(BATCH_OPERATOR))
                                    .payingWith(BATCH_OPERATOR),
                            childRecordsCheck(
                                    payTxn,
                                    SUCCESS,
                                    recordWith().status(SUCCESS).memo(LAZY_MEMO)),
                            getAliasedAccountInfo(ECDSA_KEY)
                                    .has(accountWith()
                                            .key(EMPTY_KEY)
                                            .autoRenew(THREE_MONTHS_IN_SECONDS)
                                            .receiverSigReq(false)
                                            .memo(LAZY_MEMO)
                                            .evmAddress(evmAddress)
                                            .balance(depositAmount)));
                }),
                withOpContext((spec, opLog) -> {
                    final var getTxnRecord =
                            getTxnRecord(payTxn).andAllChildRecords().logged();
                    allRunFor(spec, getTxnRecord);

                    final var childRecord = getTxnRecord.getFirstNonStakingChildRecord();
                    final var lazyAccountId = childRecord.getReceipt().getAccountID();
                    final var lazyAccountName = "lazy";
                    spec.registry().saveAccountId(lazyAccountName, lazyAccountId);

                    allRunFor(
                            spec,
                            getAccountBalance(lazyAccountName).hasTinyBars(depositAmount),
                            expectContractStateChangesSidecarFor(
                                    payTxn,
                                    List.of(StateChange.stateChangeFor(LAZY_CREATE_CONTRACT)
                                            .withStorageChanges(StorageChange.onlyRead(
                                                    formattedAssertionValue(0L),
                                                    evmAddressOfChildContract
                                                            .get()
                                                            .getValue())))),
                            expectContractActionSidecarFor(
                                    payTxn,
                                    List.of(ContractAction.newBuilder()
                                            .setCallType(CALL)
                                            .setCallDepth(1)
                                            .setCallOperationType(CallOperationType.OP_CALL)
                                            .setCallingContract(spec.registry().getContractId(LAZY_CREATE_CONTRACT))
                                            .setRecipientAccount(lazyAccountId)
                                            .setOutput(EMPTY)
                                            .setGas(5_832_424)
                                            .setValue(depositAmount / 4)
                                            .build())));
                }));
    }

    @LeakyHapiTest(overrides = {"consensus.handle.maxFollowingRecords", "contracts.evm.version"})
    final Stream<DynamicTest> evmLazyCreateViaSolidityCallTooManyCreatesFails() {
        final var LAZY_CREATE_CONTRACT = "NestedLazyCreateContract";
        final var ECDSA_KEY = "ECDSAKey";
        final var ECDSA_KEY2 = "ECDSAKey2";
        final var createTooManyHollowAccounts = "createTooManyHollowAccounts";
        final var depositAmount = 1000;
        return hapiTest(
                overridingTwo("consensus.handle.maxFollowingRecords", "1", "contracts.evm.version", "v0.34"),
                newKeyNamed(ECDSA_KEY).shape(SECP_256K1_SHAPE),
                newKeyNamed(ECDSA_KEY2).shape(SECP_256K1_SHAPE),
                uploadInitCode(LAZY_CREATE_CONTRACT),
                contractCreate(LAZY_CREATE_CONTRACT).via(CALL_TX_REC).gas(2_000_000),
                getTxnRecord(CALL_TX_REC).andAllChildRecords().logged(),
                withOpContext((spec, opLog) -> {
                    final var ecdsaKey = spec.registry().getKey(ECDSA_KEY);
                    final var tmp = ecdsaKey.getECDSASecp256K1().toByteArray();
                    final var addressBytes = recoverAddressFromPubKey(tmp);
                    final var ecdsaKey2 = spec.registry().getKey(ECDSA_KEY2);
                    final var tmp2 = ecdsaKey2.getECDSASecp256K1().toByteArray();
                    final var addressBytes2 = recoverAddressFromPubKey(tmp2);
                    allRunFor(
                            spec,
                            atomicBatch(contractCall(LAZY_CREATE_CONTRACT, createTooManyHollowAccounts, (Object)
                                                    asHeadlongAddressArray(addressBytes, addressBytes2))
                                            .sending(depositAmount)
                                            .via(TRANSFER_TXN)
                                            .gas(6_000_000)
                                            .hasKnownStatus(MAX_CHILD_RECORDS_EXCEEDED)
                                            .batchKey(BATCH_OPERATOR))
                                    .payingWith(BATCH_OPERATOR)
                                    .hasKnownStatus(INNER_TRANSACTION_FAILED),
                            getAliasedAccountInfo(ecdsaKey.toByteString())
                                    .logged()
                                    .hasCostAnswerPrecheck(INVALID_ACCOUNT_ID),
                            getAliasedAccountInfo(ecdsaKey2.toByteString())
                                    .logged()
                                    .hasCostAnswerPrecheck(INVALID_ACCOUNT_ID));
                }),
                emptyChildRecordsCheck(TRANSFER_TXN, MAX_CHILD_RECORDS_EXCEEDED));
    }

    @Order(30)
    @LeakyHapiTest(overrides = {"contracts.nonces.externalization.enabled"})
    final Stream<DynamicTest> shouldReturnNullWhenContractsNoncesExternalizationFlagIsDisabled() {
        final var contract = "NoncesExternalization";
        final var payer = "payer";

        return hapiTest(
                overriding("contracts.nonces.externalization.enabled", "false"),
                cryptoCreate(payer).balance(10 * ONE_HUNDRED_HBARS),
                uploadInitCode(contract),
                atomicBatch(contractCreate(contract)
                                .logged()
                                .gas(1_000_000L)
                                .via("txn")
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                withOpContext((spec, opLog) -> {
                    HapiGetTxnRecord op = getTxnRecord("txn")
                            .logged()
                            .hasPriority(recordWith()
                                    .contractCreateResult(resultWith()
                                            .contractWithNonce(spec.registry().getContractId(contract), null)));
                    allRunFor(spec, op);
                }));
    }

    @Order(31)
    @LeakyHapiTest(overrides = {"contracts.evm.version"})
    final Stream<DynamicTest> someErc721GetApprovedScenariosPass() {
        final AtomicReference<String> tokenMirrorAddr = new AtomicReference<>();
        final AtomicReference<String> aCivilianMirrorAddr = new AtomicReference<>();
        final AtomicReference<String> zCivilianMirrorAddr = new AtomicReference<>();
        final AtomicReference<String> zTokenMirrorAddr = new AtomicReference<>();

        return hapiTest(
                overriding("contracts.evm.version", "v0.38"),
                newKeyNamed(MULTI_KEY_NAME),
                cryptoCreate(A_CIVILIAN).exposingCreatedIdTo(id -> aCivilianMirrorAddr.set(asHexedSolidityAddress(id))),
                uploadInitCode(SOME_ERC_721_SCENARIOS),
                contractCreate(SOME_ERC_721_SCENARIOS).adminKey(MULTI_KEY_NAME).gas(2_000_000L),
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
                }),
                sourcing(() -> atomicBatch(contractCall(
                                        SOME_ERC_721_SCENARIOS,
                                        GET_APPROVED,
                                        asHeadlongAddress(zTokenMirrorAddr.get()),
                                        BigInteger.ONE)
                                .via(MISSING_TOKEN)
                                .gas(1_000_000)
                                .hasKnownStatus(INVALID_SOLIDITY_ADDRESS)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)),
                sourcing(() -> atomicBatch(contractCall(
                                        SOME_ERC_721_SCENARIOS,
                                        DO_SPECIFIC_APPROVAL,
                                        asHeadlongAddress(tokenMirrorAddr.get()),
                                        asHeadlongAddress(aCivilianMirrorAddr.get()),
                                        BigInteger.ONE)
                                .gas(1_000_000)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)),
                sourcing(() -> atomicBatch(contractCall(
                                        SOME_ERC_721_SCENARIOS,
                                        GET_APPROVED,
                                        asHeadlongAddress(tokenMirrorAddr.get()),
                                        BigInteger.valueOf(55))
                                .via("MISSING_SERIAL")
                                .gas(1_000_000)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)),
                getTokenNftInfo(NF_TOKEN, 1L).logged(),
                sourcing(() -> atomicBatch(contractCall(
                                        SOME_ERC_721_SCENARIOS,
                                        GET_APPROVED,
                                        asHeadlongAddress(tokenMirrorAddr.get()),
                                        BigInteger.TWO)
                                .via("MISSING_SPENDER")
                                .gas(1_000_000)
                                .hasKnownStatus(SUCCESS)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)),
                sourcing(() -> atomicBatch(contractCall(
                                        SOME_ERC_721_SCENARIOS,
                                        GET_APPROVED,
                                        asHeadlongAddress(tokenMirrorAddr.get()),
                                        BigInteger.ONE)
                                .via(WITH_SPENDER)
                                .gas(1_000_000)
                                .hasKnownStatus(SUCCESS)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)),
                getTxnRecord(WITH_SPENDER).andAllChildRecords().logged(),
                sourcing(() -> contractCallLocal(
                                SOME_ERC_721_SCENARIOS,
                                GET_APPROVED,
                                asHeadlongAddress(tokenMirrorAddr.get()),
                                BigInteger.ONE)
                        .logged()
                        .gas(1_000_000)
                        .has(resultWith().contractCallResult(hexedAddress(aCivilianMirrorAddr.get())))),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        childRecordsCheck(
                                "MISSING_SPENDER",
                                SUCCESS,
                                recordWith()
                                        .status(SUCCESS)
                                        .contractCallResult(resultWith()
                                                .contractCallResult(htsPrecompileResult()
                                                        .forFunction(FunctionType.ERC_GET_APPROVED)
                                                        .withSpender(new byte[0])))),
                        childRecordsCheck(
                                WITH_SPENDER,
                                SUCCESS,
                                recordWith()
                                        .status(SUCCESS)
                                        .contractCallResult(resultWith()
                                                .contractCallResult(htsPrecompileResult()
                                                        .forFunction(FunctionType.ERC_GET_APPROVED)
                                                        .withSpender(asAddress(
                                                                spec.registry().getAccountID(A_CIVILIAN)))))))));
    }

    @Order(33)
    @LeakyHapiTest(overrides = {"contracts.evm.version"})
    final Stream<DynamicTest> someErc721BalanceOfScenariosPass() {
        final AtomicReference<String> tokenMirrorAddr = new AtomicReference<>();
        final AtomicReference<String> aCivilianMirrorAddr = new AtomicReference<>();
        final AtomicReference<String> bCivilianMirrorAddr = new AtomicReference<>();
        final AtomicReference<String> zTokenMirrorAddr = new AtomicReference<>();

        return hapiTest(
                overriding("contracts.evm.version", "v0.38"),
                newKeyNamed(MULTI_KEY_NAME),
                cryptoCreate(A_CIVILIAN).exposingCreatedIdTo(id -> aCivilianMirrorAddr.set(asHexedSolidityAddress(id))),
                cryptoCreate(B_CIVILIAN).exposingCreatedIdTo(id -> bCivilianMirrorAddr.set(asHexedSolidityAddress(id))),
                uploadInitCode(SOME_ERC_721_SCENARIOS),
                contractCreate(SOME_ERC_721_SCENARIOS).adminKey(MULTI_KEY_NAME).gas(2_000_000),
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
                withOpContext((spec, opLog) -> zTokenMirrorAddr.set(asHexedSolidityAddress(
                        TokenID.newBuilder().setTokenNum(666_666L).build()))),
                sourcing(() -> atomicBatch(contractCall(
                                        SOME_ERC_721_SCENARIOS,
                                        GET_BALANCE_OF,
                                        asHeadlongAddress(tokenMirrorAddr.get()),
                                        asHeadlongAddress(aCivilianMirrorAddr.get()))
                                .via("BALANCE_OF")
                                .gas(1_000_000)
                                .hasKnownStatus(SUCCESS)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)),
                sourcing(() -> atomicBatch(contractCall(
                                        SOME_ERC_721_SCENARIOS,
                                        GET_BALANCE_OF,
                                        asHeadlongAddress(zTokenMirrorAddr.get()),
                                        asHeadlongAddress(aCivilianMirrorAddr.get()))
                                .via(MISSING_TOKEN)
                                .gas(1_000_000)
                                .hasKnownStatus(INVALID_SOLIDITY_ADDRESS)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)),
                sourcing(() -> atomicBatch(contractCall(
                                        SOME_ERC_721_SCENARIOS,
                                        GET_BALANCE_OF,
                                        asHeadlongAddress(tokenMirrorAddr.get()),
                                        asHeadlongAddress(bCivilianMirrorAddr.get()))
                                .via("NOT_ASSOCIATED")
                                .gas(1_000_000)
                                .hasKnownStatus(SUCCESS)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)),
                childRecordsCheck(
                        "BALANCE_OF",
                        SUCCESS,
                        recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith()
                                        .contractCallResult(htsPrecompileResult()
                                                .forFunction(FunctionType.ERC_BALANCE)
                                                .withBalance(2)))),
                childRecordsCheck(
                        "NOT_ASSOCIATED",
                        SUCCESS,
                        recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith()
                                        .contractCallResult(htsPrecompileResult()
                                                .forFunction(FunctionType.ERC_BALANCE)
                                                .withBalance(0)))));
    }

    @Order(32)
    @LeakyHapiTest(overrides = {"contracts.evm.version"})
    final Stream<DynamicTest> someErc721OwnerOfScenariosPass() {
        final AtomicReference<String> tokenMirrorAddr = new AtomicReference<>();
        final AtomicReference<String> aCivilianMirrorAddr = new AtomicReference<>();
        final AtomicReference<String> zCivilianMirrorAddr = new AtomicReference<>();
        final AtomicReference<String> zTokenMirrorAddr = new AtomicReference<>();

        return hapiTest(
                overriding("contracts.evm.version", "v0.38"),
                newKeyNamed(MULTI_KEY_NAME),
                cryptoCreate(A_CIVILIAN).exposingCreatedIdTo(id -> aCivilianMirrorAddr.set(asHexedSolidityAddress(id))),
                uploadInitCode(SOME_ERC_721_SCENARIOS),
                contractCreate(SOME_ERC_721_SCENARIOS).adminKey(MULTI_KEY_NAME).gas(2_000_000),
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
                }),
                sourcing(() -> atomicBatch(contractCall(
                                        SOME_ERC_721_SCENARIOS,
                                        GET_OWNER_OF,
                                        asHeadlongAddress(zTokenMirrorAddr.get()),
                                        BigInteger.ONE)
                                .via(MISSING_TOKEN)
                                .gas(1_000_000)
                                .hasKnownStatus(INVALID_SOLIDITY_ADDRESS)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)),
                sourcing(() -> atomicBatch(contractCall(
                                        SOME_ERC_721_SCENARIOS,
                                        GET_OWNER_OF,
                                        asHeadlongAddress(tokenMirrorAddr.get()),
                                        BigInteger.valueOf(55))
                                .gas(1_000_000)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)),
                sourcing(() -> atomicBatch(contractCall(
                                        SOME_ERC_721_SCENARIOS,
                                        GET_OWNER_OF,
                                        asHeadlongAddress(tokenMirrorAddr.get()),
                                        BigInteger.TWO)
                                .via("TREASURY_OWNER")
                                .gas(1_000_000)
                                .hasKnownStatus(SUCCESS)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)),
                cryptoTransfer(movingUnique(NF_TOKEN, 1L).between(SOME_ERC_721_SCENARIOS, A_CIVILIAN)),
                sourcing(() -> atomicBatch(contractCall(
                                        SOME_ERC_721_SCENARIOS,
                                        GET_OWNER_OF,
                                        asHeadlongAddress(tokenMirrorAddr.get()),
                                        BigInteger.ONE)
                                .via("CIVILIAN_OWNER")
                                .gas(1_000_000)
                                .hasKnownStatus(SUCCESS)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        childRecordsCheck(
                                "TREASURY_OWNER",
                                SUCCESS,
                                recordWith()
                                        .status(SUCCESS)
                                        .contractCallResult(resultWith()
                                                .contractCallResult(htsPrecompileResult()
                                                        .forFunction(FunctionType.ERC_OWNER)
                                                        .withOwner(asAddress(
                                                                spec.registry()
                                                                        .getAccountID(SOME_ERC_721_SCENARIOS)))))),
                        childRecordsCheck(
                                "CIVILIAN_OWNER",
                                SUCCESS,
                                recordWith()
                                        .status(SUCCESS)
                                        .contractCallResult(resultWith()
                                                .contractCallResult(htsPrecompileResult()
                                                        .forFunction(FunctionType.ERC_GET_APPROVED)
                                                        .withSpender(asAddress(
                                                                spec.registry().getAccountID(A_CIVILIAN)))))))));
    }

    @Order(34)
    @LeakyHapiTest(overrides = {"contracts.evm.version"})
    final Stream<DynamicTest> callToNonExistingContractFailsGracefullyInV038() {
        return hapiTest(
                overriding("contracts.evm.version", "v0.38"),
                withOpContext((spec, ctxLog) -> spec.registry().saveContractId("invalid", asContract("0.0.100000001"))),
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(RELAYER).balance(6 * ONE_MILLION_HBARS),
                cryptoCreate(TOKEN_TREASURY),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_HUNDRED_HBARS)),
                withOpContext((spec, opLog) -> updateSpecFor(spec, SECP_256K1_SOURCE_KEY)),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        atomicBatch(ethereumCallWithFunctionAbi(
                                                false,
                                                "invalid",
                                                getABIFor(Utils.FunctionType.FUNCTION, "totalSupply", "ERC20ABI"))
                                        .type(EthTxData.EthTransactionType.EIP1559)
                                        .signingWith(SECP_256K1_SOURCE_KEY)
                                        .payingWith(RELAYER)
                                        .via("invalidContractCallTxn")
                                        .nonce(0)
                                        .gasPrice(0L)
                                        .gasLimit(1_000_000L)
                                        .hasKnownStatus(INVALID_CONTRACT_ID)
                                        .batchKey(BATCH_OPERATOR))
                                .payingWith(BATCH_OPERATOR)
                                .hasKnownStatus(INNER_TRANSACTION_FAILED))));
    }

    @Order(38)
    @LeakyHapiTest
    final Stream<DynamicTest> invalidContractCallFailsInV038() {
        final var function = getABIFor(FUNCTION, "getIndirect", "CreateTrivial");

        return hapiTest(
                overriding("contracts.evm.version", "v0.38"),
                withOpContext((spec, ctxLog) -> spec.registry().saveContractId("invalid", asContract("0.0.100000001"))),
                atomicBatch(contractCallWithFunctionAbi("invalid", function)
                                .hasKnownStatus(INVALID_CONTRACT_ID)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }
}
