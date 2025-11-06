// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.opcodes;

import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.isLiteralResult;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.isOneOfLiteral;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.isRandomResult;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.resultWith;
import static com.hedera.services.bdd.spec.assertions.ContractInfoAsserts.contractWith;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.contractCallLocal;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getContractBytecode;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getContractInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.contract.HapiParserUtil.asHeadlongAddress;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.contract.Utils.FunctionType.FUNCTION;
import static com.hedera.services.bdd.suites.contract.Utils.asHexedSolidityAddress;
import static com.hedera.services.bdd.suites.contract.Utils.captureOneChildCreate2MetaFor;
import static com.hedera.services.bdd.suites.contract.Utils.getABIFor;
import static com.hedera.services.bdd.suites.contract.Utils.mirrorAddrWith;
import static com.hedera.services.bdd.suites.utils.contracts.SimpleBytesResult.bigIntResult;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INNER_TRANSACTION_FAILED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OBTAINER_SAME_CONTRACT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts;
import com.hedera.services.bdd.spec.dsl.annotations.Contract;
import com.hedera.services.bdd.spec.dsl.entities.SpecContract;
import com.hedera.services.bdd.spec.utilops.CustomSpecAssert;
import com.hederahashgraph.api.proto.java.ContractID;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.crypto.Hash;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

// Wrapping the most important tests from this package with an atomic batch
@HapiTestLifecycle
class AtomicOpCodesSuite {

    private static final String BATCH_OPERATOR = "batchOperator";
    private static final String CREATE_CONTRACT = "FactoryContract";
    private static final String DEPLOYMENT_SUCCESS_FUNCTION = "deploymentSuccess";
    private static final String DEPLOYMENT_SUCCESS_TXN = "deploymentSuccessTxn";
    private static final String CONTRACT_INFO = "contractInfo";
    private static final String GLOBAL_PROPERTIES_CONTRACT = "GlobalProperties";
    private static final String GET_CHAIN_ID = "getChainID";
    private static final long GAS_TO_OFFER = 400_000L;

    private static final String THE_PRNG_CONTRACT = "PrngSeedOperationContract";
    private static final String BOB = "bob";
    private static final String GET_SEED = "getPseudorandomSeed";

    private static final String PUSH_ZERO_CONTRACT = "OpcodesContract";
    private static final String OP_PUSH_ZERO = "opPush0";

    private static final String SELF_DESTRUCT_CALLABLE_CONTRACT = "SelfDestructCallable";
    private static final String DESTROY_EXPLICIT_BENEFICIARY = "destroyExplicitBeneficiary";

    public static final int MAX_CONTRACT_STORAGE_KB = 1024;

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.doAdhoc(cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS));
        testLifecycle.overrideInClass(Map.of("contracts.evm.version", "v0.50"));
        testLifecycle.doAdhoc(
                // Multiple tests use the same contract, so we upload it once here
                uploadInitCode(SELF_DESTRUCT_CALLABLE_CONTRACT));
    }

    @HapiTest
    @Tag(MATS)
    final Stream<DynamicTest> payableCreate2WorksAsExpected() {
        final var contract = "PayableCreate2Deploy";
        AtomicReference<String> tcMirrorAddr2 = new AtomicReference<>();
        AtomicReference<String> tcAliasAddr2 = new AtomicReference<>();

        return hapiTest(
                uploadInitCode(contract),
                contractCreate(contract).payingWith(GENESIS).gas(1_000_000),
                atomicBatch(contractCall(contract, "testPayableCreate")
                                .sending(100L)
                                .via("testCreate2")
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                captureOneChildCreate2MetaFor("Test contract create2", "testCreate2", tcMirrorAddr2, tcAliasAddr2),
                sourcing(() ->
                        getContractInfo(tcMirrorAddr2.get()).has(contractWith().balance(100))));
    }

    @HapiTest
    final Stream<DynamicTest> simpleFactoryWorks() {
        return hapiTest(
                uploadInitCode(CREATE_CONTRACT),
                contractCreate(CREATE_CONTRACT),
                atomicBatch(contractCall(CREATE_CONTRACT, DEPLOYMENT_SUCCESS_FUNCTION)
                                .gas(780_000)
                                .via(DEPLOYMENT_SUCCESS_TXN)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                withOpContext((spec, opLog) -> {
                    final var successTxn = getTxnRecord(DEPLOYMENT_SUCCESS_TXN);
                    final var parentContract = getContractInfo(CREATE_CONTRACT).saveToRegistry(CONTRACT_INFO);
                    allRunFor(spec, successTxn, parentContract);

                    final var createdContractIDs = successTxn
                            .getResponseRecord()
                            .getContractCallResult()
                            .getCreatedContractIDsList();

                    Assertions.assertEquals(createdContractIDs.size(), 1);
                }));
    }

    @HapiTest
    final Stream<DynamicTest> stackedFactoryWorks() {
        return hapiTest(
                uploadInitCode(CREATE_CONTRACT),
                contractCreate(CREATE_CONTRACT),
                atomicBatch(contractCall(CREATE_CONTRACT, "stackedDeploymentSuccess")
                                .gas(1_000_000)
                                .via("stackedDeploymentSuccessTxn")
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                withOpContext((spec, opLog) -> {
                    final var successTxn = getTxnRecord("stackedDeploymentSuccessTxn");
                    final var parentContract = getContractInfo(CREATE_CONTRACT).saveToRegistry(CONTRACT_INFO);
                    allRunFor(spec, successTxn, parentContract);

                    final var createdContractIDs = successTxn
                            .getResponseRecord()
                            .getContractCallResult()
                            .getCreatedContractIDsList();

                    Assertions.assertEquals(createdContractIDs.size(), 2);
                }));
    }

    @HapiTest
    final Stream<DynamicTest> delegateCallVerifiesExistence() {
        final var contract = "CallOperationsChecker";
        final var INVALID_ADDRESS = "0x0000000000000000000000000000000000123456";
        return hapiTest(
                uploadInitCode(contract),
                contractCreate(contract),
                atomicBatch(contractCall(contract, "delegateCall", asHeadlongAddress(INVALID_ADDRESS))
                                .hasKnownStatus(SUCCESS)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                withOpContext((spec, opLog) -> {
                    final var id = spec.registry().getAccountID(DEFAULT_PAYER);
                    final var solidityAddress = asHexedSolidityAddress(id);

                    final var contractCall = contractCall(contract, "delegateCall", asHeadlongAddress(solidityAddress))
                            .hasKnownStatus(SUCCESS);

                    allRunFor(spec, contractCall);
                }));
    }

    @HapiTest
    final Stream<DynamicTest> extCodeCopyVerifiesExistence() {
        final var contract = "ExtCodeOperationsChecker";
        final var invalidAddress = "0x0000000000000000000000000000000000123456";
        final var emptyBytecode = ByteString.EMPTY;
        final var codeCopyOf = "codeCopyOf";
        final var account = "account";

        return hapiTest(
                cryptoCreate(account),
                uploadInitCode(contract),
                contractCreate(contract),
                atomicBatch(contractCall(contract, codeCopyOf, asHeadlongAddress(invalidAddress))
                                .hasKnownStatus(SUCCESS)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                contractCallLocal(contract, codeCopyOf, asHeadlongAddress(invalidAddress))
                        .hasAnswerOnlyPrecheck(OK),
                withOpContext((spec, opLog) -> {
                    final var accountID = spec.registry().getAccountID(account);
                    final var contractID = spec.registry().getContractId(contract);
                    final var accountSolidityAddress = asHexedSolidityAddress(accountID);
                    final var contractAddress = asHexedSolidityAddress(contractID);

                    final var call = contractCall(contract, codeCopyOf, asHeadlongAddress(accountSolidityAddress))
                            .via("callRecord");
                    final var callRecord = getTxnRecord("callRecord");

                    final var accountCodeCallLocal = contractCallLocal(
                                    contract, codeCopyOf, asHeadlongAddress(accountSolidityAddress))
                            .saveResultTo("accountCode");

                    final var contractCodeCallLocal = contractCallLocal(
                                    contract, codeCopyOf, asHeadlongAddress(contractAddress))
                            .saveResultTo("contractCode");

                    final var getBytecodeCall = getContractBytecode(contract).saveResultTo("contractGetBytecode");

                    allRunFor(spec, call, callRecord, accountCodeCallLocal, contractCodeCallLocal, getBytecodeCall);

                    final var recordResult = callRecord.getResponseRecord().getContractCallResult();
                    final var accountCode = spec.registry().getBytes("accountCode");
                    final var contractCode = spec.registry().getBytes("contractCode");
                    final var getBytecode = spec.registry().getBytes("contractGetBytecode");

                    Assertions.assertEquals(emptyBytecode, recordResult.getContractCallResult());
                    Assertions.assertArrayEquals(emptyBytecode.toByteArray(), accountCode);
                    Assertions.assertArrayEquals(getBytecode, contractCode);
                }));
    }

    @SuppressWarnings("java:S5960")
    @HapiTest
    final Stream<DynamicTest> extCodeHashVerifiesExistence() {
        final var contract = "ExtCodeOperationsChecker";
        final var invalidAddress = "0x0000000000000000000000000000000000123456";
        final var expectedAccountHash =
                ByteString.copyFrom(Hash.keccak256(Bytes.EMPTY).toArray());
        final var hashOf = "hashOf";

        final String account = "account";
        return hapiTest(
                uploadInitCode(contract),
                contractCreate(contract),
                cryptoCreate(account),
                atomicBatch(contractCall(contract, hashOf, asHeadlongAddress(invalidAddress))
                                .hasKnownStatus(SUCCESS)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                contractCallLocal(contract, hashOf, asHeadlongAddress(invalidAddress))
                        .hasAnswerOnlyPrecheck(OK),
                withOpContext((spec, opLog) -> {
                    final var accountID = spec.registry().getAccountID(account);
                    final var contractID = spec.registry().getContractId(contract);
                    final var accountSolidityAddress = asHexedSolidityAddress(accountID);
                    final var contractAddress = asHexedSolidityAddress(contractID);

                    final var call = contractCall(contract, hashOf, asHeadlongAddress(accountSolidityAddress))
                            .via("callRecord");
                    final var callRecord = getTxnRecord("callRecord");

                    final var accountCodeHashCallLocal = contractCallLocal(
                                    contract, hashOf, asHeadlongAddress(accountSolidityAddress))
                            .saveResultTo("accountCodeHash");

                    final var contractCodeHash = contractCallLocal(contract, hashOf, asHeadlongAddress(contractAddress))
                            .saveResultTo("contractCodeHash");

                    final var getBytecode = getContractBytecode(contract).saveResultTo("contractBytecode");

                    allRunFor(spec, call, callRecord, accountCodeHashCallLocal, contractCodeHash, getBytecode);

                    final var recordResult = callRecord.getResponseRecord().getContractCallResult();
                    final var accountCodeHash = spec.registry().getBytes("accountCodeHash");

                    final var contractCodeResult = spec.registry().getBytes("contractCodeHash");
                    final var contractBytecode = spec.registry().getBytes("contractBytecode");
                    final var expectedContractCodeHash = ByteString.copyFrom(
                                    Hash.keccak256(Bytes.of(contractBytecode)).toArray())
                            .toByteArray();

                    Assertions.assertEquals(expectedAccountHash, recordResult.getContractCallResult());
                    Assertions.assertArrayEquals(expectedAccountHash.toByteArray(), accountCodeHash);
                    Assertions.assertArrayEquals(expectedContractCodeHash, contractCodeResult);
                }));
    }

    @SuppressWarnings("java:S5960")
    @HapiTest
    final Stream<DynamicTest> extCodeSizeVerifiesExistence() {
        final var contract = "ExtCodeOperationsChecker";
        final var invalidAddress = "0x0000000000000000000000000000000000123456";
        final var sizeOf = "sizeOf";

        final var account = "account";
        return hapiTest(
                uploadInitCode(contract),
                contractCreate(contract),
                cryptoCreate(account),
                atomicBatch(contractCall(contract, sizeOf, asHeadlongAddress(invalidAddress))
                                .hasKnownStatus(SUCCESS)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                contractCallLocal(contract, sizeOf, asHeadlongAddress(invalidAddress))
                        .hasAnswerOnlyPrecheck(OK),
                withOpContext((spec, opLog) -> {
                    final var accountID = spec.registry().getAccountID(account);
                    final var contractID = spec.registry().getContractId(contract);
                    final var accountSolidityAddress = asHexedSolidityAddress(accountID);
                    final var contractAddress = asHexedSolidityAddress(contractID);

                    final var call = contractCall(contract, sizeOf, asHeadlongAddress(accountSolidityAddress))
                            .via("callRecord");

                    final var callRecord = getTxnRecord("callRecord")
                            .hasPriority(recordWith()
                                    .contractCallResult(resultWith()
                                            .resultThruAbi(
                                                    getABIFor(FUNCTION, sizeOf, contract),
                                                    isLiteralResult(new Object[] {BigInteger.valueOf(0)}))));

                    final var accountCodeSizeCallLocal = contractCallLocal(
                                    contract, sizeOf, asHeadlongAddress(accountSolidityAddress))
                            .has(ContractFnResultAsserts.resultWith()
                                    .resultThruAbi(
                                            getABIFor(FUNCTION, sizeOf, contract),
                                            ContractFnResultAsserts.isLiteralResult(
                                                    new Object[] {BigInteger.valueOf(0)})));

                    final var getBytecode = getContractBytecode(contract).saveResultTo("contractBytecode");

                    final var contractCodeSize = contractCallLocal(contract, sizeOf, asHeadlongAddress(contractAddress))
                            .saveResultTo("contractCodeSize");

                    allRunFor(spec, call, callRecord, accountCodeSizeCallLocal, getBytecode, contractCodeSize);

                    final var contractCodeSizeResult = spec.registry().getBytes("contractCodeSize");
                    final var contractBytecode = spec.registry().getBytes("contractBytecode");

                    Assertions.assertEquals(
                            BigInteger.valueOf(contractBytecode.length), new BigInteger(contractCodeSizeResult));
                }));
    }

    @Contract(contract = "GasPriceContract", creationGas = 8_000_000L)
    static SpecContract gasPriceContract;

    @HapiTest
    Stream<DynamicTest> getGasPrice() {
        return hapiTest(gasPriceContract
                .call("getTxGasPrice")
                .wrappedInBatchOperation(BATCH_OPERATOR)
                .gas(100_000L)
                .andAssert(txn -> txn.hasKnownStatus(ResponseCodeEnum.SUCCESS))
                .andAssert(txn ->
                        txn.hasResults(ContractFnResultAsserts.resultWith().contractCallResult(bigIntResult(71)))));
    }

    @HapiTest
    Stream<DynamicTest> getLastGasPrice() {
        return hapiTest(
                gasPriceContract
                        .call("getLastTxGasPrice")
                        .wrappedInBatchOperation(BATCH_OPERATOR)
                        .gas(100_000L)
                        .andAssert(txn -> txn.hasKnownStatus(ResponseCodeEnum.SUCCESS))
                        .andAssert(txn -> txn.hasResults(
                                ContractFnResultAsserts.resultWith().contractCallResult(bigIntResult(0)))),
                gasPriceContract
                        .call("updateGasPrice")
                        .wrappedInBatchOperation(BATCH_OPERATOR)
                        .gas(100_000L)
                        .andAssert(txn -> txn.hasKnownStatus(ResponseCodeEnum.SUCCESS)),
                gasPriceContract
                        .call("getLastTxGasPrice")
                        .wrappedInBatchOperation(BATCH_OPERATOR)
                        .gas(100_000L)
                        .andAssert(txn -> txn.hasKnownStatus(ResponseCodeEnum.SUCCESS))
                        .andAssert(txn -> txn.hasResults(
                                ContractFnResultAsserts.resultWith().contractCallResult(bigIntResult(71)))));
    }

    @HapiTest
    @Tag(MATS)
    final Stream<DynamicTest> chainIdWorks() {
        final var defaultChainId = BigInteger.valueOf(295L);
        final var devChainId = BigInteger.valueOf(298L);
        final Set<Object> acceptableChainIds = Set.of(devChainId, defaultChainId);
        return hapiTest(
                uploadInitCode(GLOBAL_PROPERTIES_CONTRACT),
                contractCreate(GLOBAL_PROPERTIES_CONTRACT),
                atomicBatch(contractCall(GLOBAL_PROPERTIES_CONTRACT, GET_CHAIN_ID)
                                .via("chainId")
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                getTxnRecord("chainId")
                        .logged()
                        .hasPriority(recordWith()
                                .contractCallResult(resultWith()
                                        .resultThruAbi(
                                                getABIFor(FUNCTION, GET_CHAIN_ID, GLOBAL_PROPERTIES_CONTRACT),
                                                isOneOfLiteral(acceptableChainIds)))),
                contractCallLocal(GLOBAL_PROPERTIES_CONTRACT, GET_CHAIN_ID)
                        .nodePayment(1_234_567)
                        .has(ContractFnResultAsserts.resultWith()
                                .resultThruAbi(
                                        getABIFor(FUNCTION, GET_CHAIN_ID, GLOBAL_PROPERTIES_CONTRACT),
                                        isOneOfLiteral(acceptableChainIds))));
    }

    @HapiTest
    final Stream<DynamicTest> prngPrecompileHappyPathWorks() {
        final var prng = THE_PRNG_CONTRACT;
        final var randomBits = "randomBits";
        return hapiTest(
                cryptoCreate(BOB),
                uploadInitCode(prng),
                contractCreate(prng),
                atomicBatch(contractCall(prng, GET_SEED)
                                .gas(GAS_TO_OFFER)
                                .payingWith(BOB)
                                .via(randomBits)
                                .logged()
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                getTxnRecord(randomBits)
                        .hasPriority(recordWith()
                                .contractCallResult(resultWith()
                                        .resultViaFunctionName(
                                                GET_SEED, prng, isRandomResult((new Object[] {new byte[32]}))))));
    }

    @HapiTest
    final Stream<DynamicTest> pushZeroHappyPathWorks() {
        final var pushZeroContract = PUSH_ZERO_CONTRACT;
        final var pushResult = "pushResult";
        return hapiTest(
                cryptoCreate(BOB),
                uploadInitCode(pushZeroContract),
                contractCreate(pushZeroContract),
                atomicBatch(contractCall(pushZeroContract, OP_PUSH_ZERO)
                                .gas(GAS_TO_OFFER)
                                .payingWith(BOB)
                                .via(pushResult)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                getTxnRecord(pushResult)
                        .hasPriority(recordWith()
                                .contractCallResult(resultWith()
                                        .resultViaFunctionName(
                                                OP_PUSH_ZERO,
                                                pushZeroContract,
                                                isLiteralResult((new Object[] {BigInteger.valueOf(0x5f)}))))));
    }

    @HapiTest
    final Stream<DynamicTest> selfDestructSucceedsWhenContractSelfDestructsItselfWithTokens() {
        final AtomicReference<ContractID> contractId = new AtomicReference<>();
        return hapiTest(
                contractCreate(SELF_DESTRUCT_CALLABLE_CONTRACT)
                        .balance(ONE_HBAR)
                        .exposingContractIdTo(contractId::set),
                atomicBatch(contractCall(
                                        SELF_DESTRUCT_CALLABLE_CONTRACT,
                                        DESTROY_EXPLICIT_BENEFICIARY,
                                        () -> mirrorAddrWith(contractId.get()))
                                .hasKnownStatus(OBTAINER_SAME_CONTRACT_ID)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatusFrom(INNER_TRANSACTION_FAILED),
                getContractInfo(SELF_DESTRUCT_CALLABLE_CONTRACT)
                        .has(contractWith().balance(ONE_HBAR)));
    }

    // This test is failing with CONSENSUS_GAS_EXHAUSTED prior the refactor.
    @HapiTest
    final Stream<DynamicTest> multipleSStoreOpsSucceed() {
        final var contract = "GrowArray";
        final var GAS_TO_OFFER = 6_000_000L;
        return hapiTest(
                uploadInitCode(contract),
                contractCreate(contract),
                withOpContext((spec, opLog) -> {
                    final var step = 16;
                    final List<SpecOperation> subOps = new ArrayList<>();

                    for (int sizeNow = step; sizeNow < MAX_CONTRACT_STORAGE_KB; sizeNow += step) {
                        final var subOp1 = atomicBatch(contractCall(contract, "growTo", BigInteger.valueOf(sizeNow))
                                        .gas(GAS_TO_OFFER)
                                        .logged()
                                        .batchKey(BATCH_OPERATOR))
                                .payingWith(BATCH_OPERATOR);
                        subOps.add(subOp1);
                    }
                    CustomSpecAssert.allRunFor(spec, subOps);
                }),
                withOpContext((spec, opLog) -> {
                    final var numberOfIterations = 10;
                    final List<SpecOperation> subOps = new ArrayList<>();

                    for (int i = 0; i < numberOfIterations; i++) {
                        final var subOp1 = atomicBatch(contractCall(
                                                contract,
                                                "changeArray",
                                                BigInteger.valueOf(ThreadLocalRandom.current()
                                                        .nextInt(1000)))
                                        .logged()
                                        .batchKey(BATCH_OPERATOR))
                                .payingWith(BATCH_OPERATOR);
                        subOps.add(subOp1);
                    }
                    CustomSpecAssert.allRunFor(spec, subOps);
                }));
    }
}
