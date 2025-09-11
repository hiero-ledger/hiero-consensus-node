// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.evm.batch;

import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiPropertySource.asContract;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.isLiteralResult;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.resultWith;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.contractCallLocal;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getContractBytecode;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCallWithFunctionAbi;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.contract.HapiParserUtil.asHeadlongAddress;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.TOKEN_TREASURY;
import static com.hedera.services.bdd.suites.contract.Utils.FunctionType.FUNCTION;
import static com.hedera.services.bdd.suites.contract.Utils.asAddress;
import static com.hedera.services.bdd.suites.contract.Utils.asHexedSolidityAddress;
import static com.hedera.services.bdd.suites.contract.Utils.getABIFor;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_DELETED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INNER_TRANSACTION_FAILED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_CONTRACT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SOLIDITY_ADDRESS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.LOCAL_CALL_MODIFICATION_EXCEPTION;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.HapiPropertySource;
import com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts;
import com.hedera.services.bdd.spec.transactions.contract.HapiParserUtil;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.TokenSupplyType;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigInteger;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.crypto.Hash;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

// This test cases are direct copies of Evm38ValidationSuite. The difference here is that
// we are wrapping the operations in an atomic batch to confirm that everything works as expected.
@Tag(SMART_CONTRACT)
@HapiTestLifecycle
public class AtomicEvm38ValidationSuite {

    private static final String CREATE_TRIVIAL = "CreateTrivial";
    private static final String STATIC_CALL = "staticcall";
    private static final String BENEFICIARY = "beneficiary";
    private static final String SIMPLE_UPDATE_CONTRACT = "SimpleUpdate";
    private static final String BATCH_OPERATOR = "batchOperator";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(Map.of(
                "contracts.evm.version",
                "v0.38",
                "atomicBatch.isEnabled",
                "true",
                "atomicBatch.maxNumberOfTransactions",
                "50"));
        testLifecycle.doAdhoc(cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS));
    }

    @HapiTest
    final Stream<DynamicTest> invalidContractCall() {
        final var function = getABIFor(FUNCTION, "getIndirect", CREATE_TRIVIAL);

        return hapiTest(
                withOpContext((spec, ctxLog) -> spec.registry().saveContractId("invalid", asContract("0.0.5555"))),
                atomicBatch(contractCallWithFunctionAbi("invalid", function)
                                .hasKnownStatus(INVALID_CONTRACT_ID)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    @HapiTest
    final Stream<DynamicTest> cannotSendValueToTokenAccount() {
        final var multiKey = "multiKey";
        final var nonFungibleToken = "NFT";
        final var contract = "ManyChildren";
        final var internalViolation = "internal";
        final var externalViolation = "external";
        final AtomicReference<String> tokenMirrorAddr = new AtomicReference<>();

        return hapiTest(
                newKeyNamed(multiKey),
                cryptoCreate(TOKEN_TREASURY).balance(ONE_HUNDRED_HBARS),
                tokenCreate(nonFungibleToken)
                        .supplyType(TokenSupplyType.INFINITE)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .treasury(TOKEN_TREASURY)
                        .initialSupply(0)
                        .supplyKey(multiKey)
                        .exposingCreatedIdTo(idLit ->
                                tokenMirrorAddr.set(asHexedSolidityAddress(HapiPropertySource.asToken(idLit)))),
                uploadInitCode(contract),
                contractCreate(contract),
                sourcing(() -> atomicBatch(
                                contractCall(contract, "sendSomeValueTo", asHeadlongAddress(tokenMirrorAddr.get()))
                                        .sending(ONE_HBAR)
                                        .payingWith(TOKEN_TREASURY)
                                        .via(internalViolation)
                                        .hasKnownStatus(CONTRACT_REVERT_EXECUTED)
                                        .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)),
                sourcing((() -> atomicBatch(contractCall(tokenMirrorAddr.get())
                                .sending(1L)
                                .payingWith(TOKEN_TREASURY)
                                .refusingEthConversion()
                                .via(externalViolation)
                                .hasKnownStatus(LOCAL_CALL_MODIFICATION_EXCEPTION)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED))),
                getTxnRecord(internalViolation).hasPriority(recordWith().feeGreaterThan(0L)),
                getTxnRecord(externalViolation).hasPriority(recordWith().feeGreaterThan(0L)));
    }

    @HapiTest
    final Stream<DynamicTest> verifiesExistenceForCallCodeOperation() {
        final var contract = "CallOperationsChecker";
        final var INVALID_ADDRESS = "0x0000000000000000000000000000000000123456";
        return hapiTest(
                uploadInitCode(contract),
                contractCreate(contract),
                contractCall(contract, "callCode", asHeadlongAddress(INVALID_ADDRESS))
                        .hasKnownStatus(INVALID_SOLIDITY_ADDRESS),
                withOpContext((spec, opLog) -> {
                    final var id = spec.registry().getAccountID(DEFAULT_PAYER);
                    final var solidityAddress = asHexedSolidityAddress(id);

                    final var contractCall = atomicBatch(
                                    contractCall(contract, "callCode", asHeadlongAddress(solidityAddress))
                                            .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR);

                    allRunFor(spec, contractCall);
                }));
    }

    @HapiTest
    final Stream<DynamicTest> verifiesExistenceForCallOperation() {
        final var contract = "CallOperationsChecker";
        final var INVALID_ADDRESS = "0x0000000000000000000000000000000000123456";
        final var ACCOUNT = "account";
        final var EXPECTED_BALANCE = 10;

        return hapiTest(
                cryptoCreate(ACCOUNT).balance(0L),
                uploadInitCode(contract),
                contractCreate(contract),
                contractCall(contract, "call", asHeadlongAddress(INVALID_ADDRESS))
                        .hasKnownStatus(INVALID_SOLIDITY_ADDRESS),
                withOpContext((spec, opLog) -> {
                    final var id = spec.registry().getAccountID(ACCOUNT);

                    final var contractCall = atomicBatch(
                                    contractCall(contract, "call", HapiParserUtil.asHeadlongAddress(asAddress(id)))
                                            .sending(EXPECTED_BALANCE)
                                            .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR);

                    final var balance = getAccountBalance(ACCOUNT).hasTinyBars(EXPECTED_BALANCE);

                    allRunFor(spec, contractCall, balance);
                }));
    }

    @HapiTest
    final Stream<DynamicTest> verifiesExistenceForCallOperationInternal() {
        final var contract = "CallingContract";
        final var INVALID_ADDRESS = "0x0000000000000000000000000000000000123456";
        return hapiTest(
                uploadInitCode(contract),
                contractCreate(contract),
                atomicBatch(contractCall(contract, "setVar1", BigInteger.valueOf(35))
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                contractCallLocal(contract, "getVar1").logged(),
                atomicBatch(contractCall(
                                        contract,
                                        "callContract",
                                        asHeadlongAddress(INVALID_ADDRESS),
                                        BigInteger.valueOf(222))
                                .hasKnownStatus(INVALID_SOLIDITY_ADDRESS)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                contractCallLocal(contract, "getVar1").logged());
    }

    @HapiTest
    final Stream<DynamicTest> verifiesExistenceForDelegateCallOperation() {
        final var contract = "CallOperationsChecker";
        final var INVALID_ADDRESS = "0x0000000000000000000000000000000000123456";
        return hapiTest(
                uploadInitCode(contract),
                contractCreate(contract),
                contractCall(contract, "delegateCall", asHeadlongAddress(INVALID_ADDRESS))
                        .hasKnownStatus(INVALID_SOLIDITY_ADDRESS),
                withOpContext((spec, opLog) -> {
                    final var id = spec.registry().getAccountID(DEFAULT_PAYER);
                    final var solidityAddress = asHexedSolidityAddress(id);

                    final var contractCall = atomicBatch(
                                    contractCall(contract, "delegateCall", asHeadlongAddress(solidityAddress))
                                            .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR);

                    allRunFor(spec, contractCall);
                }));
    }

    @SuppressWarnings("java:S5960")
    @HapiTest
    final Stream<DynamicTest> verifiesExistenceForExtCodeSize() {
        final var contract = "ExtCodeOperationsChecker";
        final var invalidAddress = "0x0000000000000000000000000000000000123456";
        final var sizeOf = "sizeOf";

        final var account = "account";
        return hapiTest(
                uploadInitCode(contract),
                contractCreate(contract),
                cryptoCreate(account),
                atomicBatch(contractCall(contract, sizeOf, asHeadlongAddress(invalidAddress))
                                .hasKnownStatus(INVALID_SOLIDITY_ADDRESS)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                contractCallLocal(contract, sizeOf, asHeadlongAddress(invalidAddress))
                        .hasAnswerOnlyPrecheck(INVALID_SOLIDITY_ADDRESS),
                withOpContext((spec, opLog) -> {
                    final var accountID = spec.registry().getAccountID(account);
                    final var contractID = spec.registry().getContractId(contract);
                    final var accountSolidityAddress = asHexedSolidityAddress(accountID);
                    final var contractAddress = asHexedSolidityAddress(contractID);

                    final var call = atomicBatch(
                                    contractCall(contract, sizeOf, asHeadlongAddress(accountSolidityAddress))
                                            .via("callRecord")
                                            .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR);

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

    @SuppressWarnings("java:S5960")
    @HapiTest
    final Stream<DynamicTest> verifiesExistenceForExtCodeHash() {
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
                                .hasKnownStatus(INVALID_SOLIDITY_ADDRESS)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                contractCallLocal(contract, hashOf, asHeadlongAddress(invalidAddress))
                        .hasAnswerOnlyPrecheck(INVALID_SOLIDITY_ADDRESS),
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

    @HapiTest
    @Tag(MATS)
    final Stream<DynamicTest> verifiesExistenceForStaticCall() {
        final var contract = "CallOperationsChecker";
        final var INVALID_ADDRESS = "0x0000000000000000000000000000000000123456";

        return hapiTest(
                uploadInitCode(contract),
                contractCreate(contract),
                atomicBatch(contractCall(contract, STATIC_CALL, asHeadlongAddress(INVALID_ADDRESS))
                                .hasKnownStatus(INVALID_SOLIDITY_ADDRESS)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                withOpContext((spec, opLog) -> {
                    final var id = spec.registry().getAccountID(DEFAULT_PAYER);
                    final var solidityAddress = asHexedSolidityAddress(id);

                    final var contractCall = contractCall(contract, STATIC_CALL, asHeadlongAddress(solidityAddress))
                            .hasKnownStatus(SUCCESS);

                    final var contractCallLocal =
                            contractCallLocal(contract, STATIC_CALL, asHeadlongAddress(solidityAddress));

                    allRunFor(spec, contractCall, contractCallLocal);
                }));
    }

    @HapiTest
    final Stream<DynamicTest> callingDestructedContractReturnsStatusDeleted() {
        final AtomicReference<AccountID> accountIDAtomicReference = new AtomicReference<>();
        return hapiTest(
                cryptoCreate(BENEFICIARY).exposingCreatedIdTo(accountIDAtomicReference::set),
                uploadInitCode(SIMPLE_UPDATE_CONTRACT),
                contractCreate(SIMPLE_UPDATE_CONTRACT).gas(300_000L),
                contractCall(SIMPLE_UPDATE_CONTRACT, "set", BigInteger.valueOf(5), BigInteger.valueOf(42))
                        .gas(300_000L),
                sourcing(() -> atomicBatch(contractCall(
                                        SIMPLE_UPDATE_CONTRACT,
                                        "del",
                                        asHeadlongAddress(asAddress(accountIDAtomicReference.get())))
                                .gas(1_000_000L)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)),
                atomicBatch(contractCall(SIMPLE_UPDATE_CONTRACT, "set", BigInteger.valueOf(15), BigInteger.valueOf(434))
                                .gas(350_000L)
                                .hasKnownStatus(CONTRACT_DELETED)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    @HapiTest
    @Tag(MATS)
    final Stream<DynamicTest> factoryAndSelfDestructInConstructorContract() {
        final var contract = "FactorySelfDestructConstructor";

        final var sender = "sender";
        return hapiTest(
                uploadInitCode(contract),
                cryptoCreate(sender).balance(ONE_HUNDRED_HBARS),
                contractCreate(contract).balance(10).payingWith(sender),
                atomicBatch(contractCall(contract)
                                .hasKnownStatus(CONTRACT_DELETED)
                                .payingWith(sender)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                getContractBytecode(contract).hasCostAnswerPrecheck(CONTRACT_DELETED));
    }
}
