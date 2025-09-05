// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.evm;

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
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.TOKEN_TREASURY;
import static com.hedera.services.bdd.suites.contract.Utils.FunctionType.FUNCTION;
import static com.hedera.services.bdd.suites.contract.Utils.asAddress;
import static com.hedera.services.bdd.suites.contract.Utils.asHexedSolidityAddress;
import static com.hedera.services.bdd.suites.contract.Utils.captureOneChildCreate2MetaFor;
import static com.hedera.services.bdd.suites.contract.Utils.getABIFor;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_DELETED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_CONTRACT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SOLIDITY_ADDRESS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.LOCAL_CALL_MODIFICATION_EXCEPTION;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;
import static org.junit.jupiter.api.Assertions.assertEquals;

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
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.crypto.Hash;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(SMART_CONTRACT)
@HapiTestLifecycle
public class Evm38ValidationSuite {
    private static final Logger LOG = LogManager.getLogger(Evm38ValidationSuite.class);
    private static final String CREATE_TRIVIAL = "CreateTrivial";
    private static final String BALANCE_OF = "balanceOf";
    private static final String CREATE_2_TXN = "create2Txn";
    private static final String RETURNER = "Returner";
    private static final String CALL_RETURNER = "callReturner";
    public static final String RETURNER_REPORTED_LOG_MESSAGE = "Returner reported {} when called with mirror address";
    private static final String STATIC_CALL = "staticcall";
    private static final String BENEFICIARY = "beneficiary";
    private static final String SIMPLE_UPDATE_CONTRACT = "SimpleUpdate";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(Map.of("contracts.evm.version", "v0.38"));
    }

    @HapiTest
    final Stream<DynamicTest> invalidContractCall() {
        final var function = getABIFor(FUNCTION, "getIndirect", CREATE_TRIVIAL);

        return hapiTest(
                withOpContext((spec, ctxLog) -> spec.registry().saveContractId("invalid", asContract("0.0.5555"))),
                contractCallWithFunctionAbi("invalid", function).hasKnownStatus(INVALID_CONTRACT_ID));
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
                sourcing(() -> contractCall(contract, "sendSomeValueTo", asHeadlongAddress(tokenMirrorAddr.get()))
                        .sending(ONE_HBAR)
                        .payingWith(TOKEN_TREASURY)
                        .via(internalViolation)
                        .hasKnownStatus(CONTRACT_REVERT_EXECUTED)),
                sourcing((() -> contractCall(tokenMirrorAddr.get())
                        .sending(1L)
                        .payingWith(TOKEN_TREASURY)
                        .refusingEthConversion()
                        .via(externalViolation)
                        .hasKnownStatus(LOCAL_CALL_MODIFICATION_EXCEPTION))),
                getTxnRecord(internalViolation).hasPriority(recordWith().feeGreaterThan(0L)),
                getTxnRecord(externalViolation).hasPriority(recordWith().feeGreaterThan(0L)));
    }

    @HapiTest
    @Tag(MATS)
    final Stream<DynamicTest> verifiesExistenceOfAccountsAndContracts() {
        final var contract = "BalanceChecker";
        final var BALANCE = 10L;
        final var ACCOUNT = "test";
        final var INVALID_ADDRESS = "0x0000000000000000000000000000000000123456";

        return hapiTest(
                cryptoCreate("test").balance(BALANCE),
                uploadInitCode(contract),
                contractCreate(contract),
                contractCall(contract, BALANCE_OF, asHeadlongAddress(INVALID_ADDRESS))
                        .hasKnownStatus(INVALID_SOLIDITY_ADDRESS),
                contractCallLocal(contract, BALANCE_OF, asHeadlongAddress(INVALID_ADDRESS))
                        .hasAnswerOnlyPrecheck(INVALID_SOLIDITY_ADDRESS),
                withOpContext((spec, opLog) -> {
                    final var id = spec.registry().getAccountID(ACCOUNT);
                    final var contractID = spec.registry().getContractId(contract);

                    final var solidityAddress = HapiParserUtil.asHeadlongAddress(asAddress(id));
                    final var contractAddress = asHeadlongAddress(asHexedSolidityAddress(contractID));

                    final var call =
                            contractCall(contract, BALANCE_OF, solidityAddress).via("callRecord");

                    final var callRecord = getTxnRecord("callRecord")
                            .hasPriority(recordWith()
                                    .contractCallResult(resultWith()
                                            .resultThruAbi(
                                                    getABIFor(FUNCTION, BALANCE_OF, contract),
                                                    isLiteralResult(new Object[] {BigInteger.valueOf(BALANCE)}))));

                    final var callLocal = contractCallLocal(contract, BALANCE_OF, solidityAddress)
                            .has(ContractFnResultAsserts.resultWith()
                                    .resultThruAbi(
                                            getABIFor(FUNCTION, BALANCE_OF, contract),
                                            ContractFnResultAsserts.isLiteralResult(
                                                    new Object[] {BigInteger.valueOf(BALANCE)})));

                    final var contractCallLocal = contractCallLocal(contract, BALANCE_OF, contractAddress)
                            .has(ContractFnResultAsserts.resultWith()
                                    .resultThruAbi(
                                            getABIFor(FUNCTION, BALANCE_OF, contract),
                                            ContractFnResultAsserts.isLiteralResult(
                                                    new Object[] {BigInteger.valueOf(0)})));

                    allRunFor(spec, call, callLocal, callRecord, contractCallLocal);
                }));
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

                    final var contractCall = contractCall(contract, "callCode", asHeadlongAddress(solidityAddress))
                            .hasKnownStatus(SUCCESS);

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

                    final var contractCall = contractCall(
                                    contract, "call", HapiParserUtil.asHeadlongAddress(asAddress(id)))
                            .sending(EXPECTED_BALANCE);

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
                contractCall(contract, "setVar1", BigInteger.valueOf(35)),
                contractCallLocal(contract, "getVar1").logged(),
                contractCall(contract, "callContract", asHeadlongAddress(INVALID_ADDRESS), BigInteger.valueOf(222))
                        .hasKnownStatus(INVALID_SOLIDITY_ADDRESS),
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

                    final var contractCall = contractCall(contract, "delegateCall", asHeadlongAddress(solidityAddress))
                            .hasKnownStatus(SUCCESS);

                    allRunFor(spec, contractCall);
                }));
    }

    @SuppressWarnings("java:S5960")
    @HapiTest
    @Tag(MATS)
    final Stream<DynamicTest> verifiesExistenceForExtCodeOperation() {
        final var contract = "ExtCodeOperationsChecker";
        final var invalidAddress = "0x0000000000000000000000000000000000123456";
        final var emptyBytecode = ByteString.EMPTY;
        final var codeCopyOf = "codeCopyOf";
        final var account = "account";

        return hapiTest(
                cryptoCreate(account),
                uploadInitCode(contract),
                contractCreate(contract),
                contractCall(contract, codeCopyOf, asHeadlongAddress(invalidAddress))
                        .hasKnownStatus(INVALID_SOLIDITY_ADDRESS),
                contractCallLocal(contract, codeCopyOf, asHeadlongAddress(invalidAddress))
                        .hasAnswerOnlyPrecheck(INVALID_SOLIDITY_ADDRESS),
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
    final Stream<DynamicTest> verifiesExistenceForExtCodeSize() {
        final var contract = "ExtCodeOperationsChecker";
        final var invalidAddress = "0x0000000000000000000000000000000000123456";
        final var sizeOf = "sizeOf";

        final var account = "account";
        return hapiTest(
                uploadInitCode(contract),
                contractCreate(contract),
                cryptoCreate(account),
                contractCall(contract, sizeOf, asHeadlongAddress(invalidAddress))
                        .hasKnownStatus(INVALID_SOLIDITY_ADDRESS),
                contractCallLocal(contract, sizeOf, asHeadlongAddress(invalidAddress))
                        .hasAnswerOnlyPrecheck(INVALID_SOLIDITY_ADDRESS),
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
                contractCall(contract, hashOf, asHeadlongAddress(invalidAddress))
                        .hasKnownStatus(INVALID_SOLIDITY_ADDRESS),
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
    final Stream<DynamicTest> verifiesExistenceForStaticCall() {
        final var contract = "CallOperationsChecker";
        final var INVALID_ADDRESS = "0x0000000000000000000000000000000000123456";

        return hapiTest(
                uploadInitCode(contract),
                contractCreate(contract),
                contractCall(contract, STATIC_CALL, asHeadlongAddress(INVALID_ADDRESS))
                        .hasKnownStatus(INVALID_SOLIDITY_ADDRESS),
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

    @SuppressWarnings("java:S5669")
    @HapiTest
    final Stream<DynamicTest> canInternallyCallAliasedAddressesOnlyViaCreate2Address() {
        final var contract = "AddressValueRet";
        final var aliasCall = "aliasCall";
        final var mirrorCall = "mirrorCall";

        final AtomicReference<String> aliasAddr = new AtomicReference<>();
        final AtomicReference<String> mirrorAddr = new AtomicReference<>();
        final AtomicReference<BigInteger> staticCallAliasAns = new AtomicReference<>();
        final AtomicReference<BigInteger> staticCallMirrorAns = new AtomicReference<>();

        final var salt = new byte[32];
        new Random().nextBytes(salt);

        return hapiTest(
                uploadInitCode(contract),
                contractCreate(contract).payingWith(GENESIS),
                contractCall(contract, "createReturner", salt)
                        .payingWith(GENESIS)
                        .gas(4_000_000L)
                        .via(CREATE_2_TXN),
                captureOneChildCreate2MetaFor(RETURNER, CREATE_2_TXN, mirrorAddr, aliasAddr),
                sourcing(() -> contractCallLocal(contract, CALL_RETURNER, asHeadlongAddress(mirrorAddr.get()))
                        .hasAnswerOnlyPrecheck(INVALID_SOLIDITY_ADDRESS)
                        .payingWith(GENESIS)
                        .exposingTypedResultsTo(results -> {
                            LOG.info(RETURNER_REPORTED_LOG_MESSAGE, results);
                            staticCallMirrorAns.set((BigInteger) results[0]);
                        })),
                sourcing(() -> contractCallLocal(contract, CALL_RETURNER, asHeadlongAddress(aliasAddr.get()))
                        .payingWith(GENESIS)
                        .exposingTypedResultsTo(results -> {
                            LOG.info("Returner reported {} when" + " called with alias" + " address", results);
                            staticCallAliasAns.set((BigInteger) results[0]);
                        })),
                sourcing(() -> contractCall(contract, CALL_RETURNER, asHeadlongAddress(aliasAddr.get()))
                        .payingWith(GENESIS)
                        .via(aliasCall)),
                sourcing(() -> contractCall(contract, CALL_RETURNER, asHeadlongAddress(mirrorAddr.get()))
                        .hasKnownStatus(INVALID_SOLIDITY_ADDRESS)
                        .payingWith(GENESIS)
                        .via(mirrorCall)),
                withOpContext((spec, opLog) -> {
                    final var mirrorLookup = getTxnRecord(mirrorCall);
                    allRunFor(spec, mirrorLookup);
                    final var mirrorResult = mirrorLookup
                            .getResponseRecord()
                            .getContractCallResult()
                            .getContractCallResult();
                    assertEquals(
                            ByteString.EMPTY,
                            mirrorResult,
                            "Internal calls with mirror address should not be" + " possible for aliased contracts");
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
                sourcing(() -> contractCall(
                                SIMPLE_UPDATE_CONTRACT,
                                "del",
                                asHeadlongAddress(asAddress(accountIDAtomicReference.get())))
                        .gas(1_000_000L)),
                contractCall(SIMPLE_UPDATE_CONTRACT, "set", BigInteger.valueOf(15), BigInteger.valueOf(434))
                        .gas(350_000L)
                        .hasPrecheck(CONTRACT_DELETED));
    }

    @HapiTest
    final Stream<DynamicTest> factoryAndSelfDestructInConstructorContract() {
        final var contract = "FactorySelfDestructConstructor";

        final var sender = "sender";
        return hapiTest(
                uploadInitCode(contract),
                cryptoCreate(sender).balance(ONE_HUNDRED_HBARS),
                contractCreate(contract).balance(10).payingWith(sender),
                contractCall(contract)
                        .hasPrecheck(CONTRACT_DELETED)
                        .payingWith(sender)
                        .hasKnownStatus(SUCCESS),
                getContractBytecode(contract).hasCostAnswerPrecheck(CONTRACT_DELETED));
    }
}
