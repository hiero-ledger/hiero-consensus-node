// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.evm.batch;

import static com.hedera.node.app.hapi.utils.EthSigsUtils.recoverAddressFromPubKey;
import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiPropertySource.asAccount;
import static com.hedera.services.bdd.spec.HapiPropertySource.asAccountString;
import static com.hedera.services.bdd.spec.HapiPropertySource.asContract;
import static com.hedera.services.bdd.spec.HapiSpec.defaultHapiSpec;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.changeFromSnapshot;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.resultWith;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.assertions.TransferListAsserts.including;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAliasedAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAutoCreatedAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getContractInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCallWithFunctionAbi;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.contract.HapiParserUtil.asHeadlongAddress;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromAccountToAlias;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.balanceSnapshot;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SHAPE;
import static com.hedera.services.bdd.suites.HapiSuite.flattened;
import static com.hedera.services.bdd.suites.contract.Utils.FunctionType.FUNCTION;
import static com.hedera.services.bdd.suites.contract.Utils.asAddress;
import static com.hedera.services.bdd.suites.contract.Utils.asSolidityAddress;
import static com.hedera.services.bdd.suites.contract.Utils.getABIFor;
import static com.hedera.services.bdd.suites.contract.Utils.idAsHeadlongAddress;
import static com.hedera.services.bdd.suites.contract.Utils.mirrorAddrParamFunction;
import static com.hedera.services.bdd.suites.contract.Utils.mirrorAddrWith;
import static com.hedera.services.bdd.suites.contract.Utils.nonMirrorAddrWith;
import static com.hedera.services.bdd.suites.crypto.AutoCreateUtils.updateSpecFor;
import static com.hedera.services.bdd.suites.utils.contracts.ErrorMessageResult.errorMessageResult;
import static com.hedera.services.bdd.suites.utils.contracts.SimpleBytesResult.bigIntResult;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INNER_TRANSACTION_FAILED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_CONTRACT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SOLIDITY_ADDRESS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static org.hiero.base.utility.CommonUtils.unhex;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.ContractID;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

// This test cases are direct copies of Evm46ValidationSuite. The difference here is that
// we are wrapping the operations in an atomic batch to confirm that everything works as expected.
@HapiTestLifecycle
@Tag(SMART_CONTRACT)
public class AtomicEvm46ValidationSuite {

    private static final long FIRST_NONEXISTENT_CONTRACT_NUM = 4303224382569680425L;
    private static final String NAME = "name";
    private static final String ERC_721_ABI = "ERC721ABI";
    private static final String NON_EXISTING_MIRROR_ADDRESS = "0000000000000000000000000000000000123456";
    private static final String NON_EXISTING_NON_MIRROR_ADDRESS = "1234561234561234561234561234568888123456";
    private static final String MAKE_CALLS_CONTRACT = "MakeCalls";
    private static final String INTERNAL_CALLER_CONTRACT = "InternalCaller";
    private static final String INTERNAL_CALLEE_CONTRACT = "InternalCallee";
    private static final String REVERT_WITH_REVERT_REASON_FUNCTION = "revertWithRevertReason";
    private static final String CALL_NON_EXISTING_FUNCTION = "callNonExisting";
    private static final String CALL_EXTERNAL_FUNCTION = "callExternalFunction";
    private static final String DELEGATE_CALL_EXTERNAL_FUNCTION = "delegateCallExternalFunction";
    private static final String STATIC_CALL_EXTERNAL_FUNCTION = "staticCallExternalFunction";
    private static final String CALL_REVERT_WITH_REVERT_REASON_FUNCTION = "callRevertWithRevertReason";
    private static final String TRANSFER_TO_FUNCTION = "transferTo";
    private static final String SEND_TO_FUNCTION = "sendTo";
    private static final String CALL_WITH_VALUE_TO_FUNCTION = "callWithValueTo";
    private static final String SELFDESTRUCT = "selfdestruct";
    private static final String INNER_TXN = "innerTx";
    private static final Long INTRINSIC_GAS_COST = 21000L;
    private static final Long GAS_LIMIT_FOR_CALL = 26000L;
    private static final Long EXTRA_GAS_FOR_FUNCTION_SELECTOR = 64L;
    private static final Long NOT_ENOUGH_GAS_LIMIT_FOR_CREATION = 500_000L;
    private static final Long ENOUGH_GAS_LIMIT_FOR_CREATION = 900_000L;
    private static final String RECEIVER = "receiver";
    private static final String ECDSA_KEY = "ecdsaKey";
    private static final String CUSTOM_PAYER = "customPayer";
    private static final String BENEFICIARY = "beneficiary";
    private static final String SIMPLE_UPDATE_CONTRACT = "SimpleUpdate";
    public static final List<Long> callOperationsSuccessSystemAccounts = List.of(0L, 1L, 358L, 750L, 751L, 999L, 1000L);
    private static final String BATCH_OPERATOR = "batchOperator";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(
                Map.of("atomicBatch.isEnabled", "true", "atomicBatch.maxNumberOfTransactions", "50"));
        testLifecycle.doAdhoc(cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS));
    }

    @HapiTest
    final Stream<DynamicTest> directCallToDeletedContractResultsInSuccessfulNoop() {
        AtomicReference<AccountID> receiverId = new AtomicReference<>();

        return hapiTest(
                cryptoCreate(RECEIVER).exposingCreatedIdTo(receiverId::set),
                uploadInitCode(INTERNAL_CALLER_CONTRACT),
                contractCreate(INTERNAL_CALLER_CONTRACT)
                        // Refusing ethereum create conversion, because we get INVALID_SIGNATURE upon
                        // tokenAssociate,
                        // since we have CONTRACT_ID key
                        .refusingEthConversion()
                        .balance(ONE_HBAR),
                contractDelete(INTERNAL_CALLER_CONTRACT),
                withOpContext((spec, op) -> allRunFor(
                        spec,
                        balanceSnapshot("initialBalance", asAccountString(receiverId.get())),
                        atomicBatch(contractCall(
                                                INTERNAL_CALLER_CONTRACT,
                                                CALL_WITH_VALUE_TO_FUNCTION,
                                                mirrorAddrWith(
                                                        spec, receiverId.get().getAccountNum()))
                                        .gas(GAS_LIMIT_FOR_CALL * 4)
                                        .via(INNER_TXN)
                                        .batchKey(BATCH_OPERATOR))
                                .payingWith(BATCH_OPERATOR))),
                getTxnRecord(INNER_TXN).hasPriority(recordWith().status(SUCCESS)),
                getAccountBalance(RECEIVER).hasTinyBars(changeFromSnapshot("initialBalance", 0)));
    }

    @HapiTest
    final Stream<DynamicTest> selfdestructToExistingMirrorAddressResultsInSuccess() {
        AtomicReference<AccountID> receiverId = new AtomicReference<>();
        return hapiTest(
                cryptoCreate(RECEIVER).exposingCreatedIdTo(receiverId::set),
                uploadInitCode(INTERNAL_CALLER_CONTRACT),
                contractCreate(INTERNAL_CALLER_CONTRACT).balance(ONE_HBAR),
                withOpContext((spec, op) -> {
                    allRunFor(
                            spec,
                            balanceSnapshot("selfdestructTargetAccount", asAccountString(receiverId.get())),
                            atomicBatch(contractCall(
                                                    INTERNAL_CALLER_CONTRACT,
                                                    SELFDESTRUCT,
                                                    mirrorAddrWith(
                                                            spec,
                                                            receiverId.get().getAccountNum()))
                                            .gas(GAS_LIMIT_FOR_CALL * 4)
                                            .via(INNER_TXN)
                                            .batchKey(BATCH_OPERATOR))
                                    .payingWith(BATCH_OPERATOR));
                }),
                getAccountBalance(RECEIVER).hasTinyBars(changeFromSnapshot("selfdestructTargetAccount", 100000000)));
    }

    @HapiTest
    final Stream<DynamicTest> selfdestructToExistingNonMirrorAddressResultsInSuccess() {
        return hapiTest(
                newKeyNamed(ECDSA_KEY).shape(SECP_256K1_SHAPE),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, ECDSA_KEY, ONE_HUNDRED_HBARS)),
                withOpContext((spec, opLog) -> updateSpecFor(spec, ECDSA_KEY)),
                uploadInitCode(INTERNAL_CALLER_CONTRACT),
                contractCreate(INTERNAL_CALLER_CONTRACT).balance(ONE_HBAR),
                withOpContext((spec, op) -> {
                    final var ecdsaKey = spec.registry().getKey(ECDSA_KEY);
                    final var tmp = ecdsaKey.getECDSASecp256K1().toByteArray();
                    final var addressBytes = recoverAddressFromPubKey(tmp);
                    allRunFor(
                            spec,
                            balanceSnapshot("selfdestructTargetAccount", ECDSA_KEY)
                                    .accountIsAlias(),
                            atomicBatch(contractCall(
                                                    INTERNAL_CALLER_CONTRACT,
                                                    SELFDESTRUCT,
                                                    asHeadlongAddress(addressBytes))
                                            .gas(GAS_LIMIT_FOR_CALL * 4)
                                            .via(INNER_TXN)
                                            .batchKey(BATCH_OPERATOR))
                                    .payingWith(BATCH_OPERATOR));
                }),
                getAccountBalance(ECDSA_KEY).hasTinyBars(changeFromSnapshot("selfdestructTargetAccount", 100000000)));
    }

    @HapiTest
    final Stream<DynamicTest> selfdestructToNonExistingNonMirrorAddressResultsInInvalidSolidityAddress() {
        AtomicReference<Bytes> nonExistingNonMirrorAddress = new AtomicReference<>();

        return hapiTest(
                newKeyNamed(ECDSA_KEY).shape(SECP_256K1_SHAPE),
                withOpContext((spec, op) -> {
                    final var ecdsaKey = spec.registry().getKey(ECDSA_KEY);
                    final var tmp = ecdsaKey.getECDSASecp256K1().toByteArray();
                    final var addressBytes = recoverAddressFromPubKey(tmp);
                    nonExistingNonMirrorAddress.set(Bytes.of(addressBytes));
                }),
                uploadInitCode(INTERNAL_CALLER_CONTRACT),
                contractCreate(INTERNAL_CALLER_CONTRACT).balance(ONE_HBAR),
                withOpContext((spec, op) -> allRunFor(
                        spec,
                        atomicBatch(contractCall(
                                                INTERNAL_CALLER_CONTRACT,
                                                SELFDESTRUCT,
                                                asHeadlongAddress(nonExistingNonMirrorAddress
                                                        .get()
                                                        .toArray()))
                                        .gas(ENOUGH_GAS_LIMIT_FOR_CREATION)
                                        .via(INNER_TXN)
                                        .hasKnownStatus(INVALID_SOLIDITY_ADDRESS)
                                        .batchKey(BATCH_OPERATOR))
                                .payingWith(BATCH_OPERATOR)
                                .hasKnownStatus(INNER_TRANSACTION_FAILED))),
                getTxnRecord(INNER_TXN)
                        .hasPriority(recordWith()
                                .status(INVALID_SOLIDITY_ADDRESS)
                                .contractCallResult(resultWith().gasUsed(900000))));
    }

    @HapiTest
    final Stream<DynamicTest> selfdestructToNonExistingMirrorAddressResultsInInvalidSolidityAddress() {
        return hapiTest(
                uploadInitCode(INTERNAL_CALLER_CONTRACT),
                contractCreate(INTERNAL_CALLER_CONTRACT).balance(ONE_HBAR),
                withOpContext((spec, op) -> allRunFor(
                        spec,
                        atomicBatch(contractCall(
                                                INTERNAL_CALLER_CONTRACT,
                                                SELFDESTRUCT,
                                                mirrorAddrWith(spec, FIRST_NONEXISTENT_CONTRACT_NUM))
                                        .gas(ENOUGH_GAS_LIMIT_FOR_CREATION)
                                        .via(INNER_TXN)
                                        .hasKnownStatus(INVALID_SOLIDITY_ADDRESS)
                                        .batchKey(BATCH_OPERATOR))
                                .payingWith(BATCH_OPERATOR)
                                .hasKnownStatus(INNER_TRANSACTION_FAILED))),
                getTxnRecord(INNER_TXN)
                        .hasPriority(recordWith()
                                .status(INVALID_SOLIDITY_ADDRESS)
                                .contractCallResult(resultWith().gasUsed(900000))));
    }

    @HapiTest
    final Stream<DynamicTest> directCallToNonExistingMirrorAddressResultsInSuccessfulNoOp() {

        return hapiTest(
                withOpContext((spec, ctxLog) -> spec.registry()
                        .saveContractId(
                                "nonExistingMirrorAddress",
                                spec,
                                ByteString.copyFrom(unhex(NON_EXISTING_MIRROR_ADDRESS)))),
                withOpContext((spec, ctxLog) -> allRunFor(
                        spec,
                        atomicBatch(
                                        contractCallWithFunctionAbi(
                                                        "nonExistingMirrorAddress",
                                                        getABIFor(FUNCTION, NAME, ERC_721_ABI))
                                                .gas(GAS_LIMIT_FOR_CALL)
                                                .via("directCallToNonExistingMirrorAddress")
                                                .batchKey(BATCH_OPERATOR),
                                        // attempt call again, make sure the result is the same
                                        contractCallWithFunctionAbi(
                                                        "nonExistingMirrorAddress",
                                                        getABIFor(FUNCTION, NAME, ERC_721_ABI))
                                                .gas(GAS_LIMIT_FOR_CALL)
                                                .via("directCallToNonExistingMirrorAddress2")
                                                .batchKey(BATCH_OPERATOR))
                                .payingWith(BATCH_OPERATOR))),
                getTxnRecord("directCallToNonExistingMirrorAddress")
                        .hasPriority(recordWith()
                                .status(SUCCESS)
                                .contractCallResult(
                                        resultWith().gasUsed(INTRINSIC_GAS_COST + EXTRA_GAS_FOR_FUNCTION_SELECTOR))),
                getTxnRecord("directCallToNonExistingMirrorAddress2")
                        .hasPriority(recordWith()
                                .status(SUCCESS)
                                .contractCallResult(
                                        resultWith().gasUsed(INTRINSIC_GAS_COST + EXTRA_GAS_FOR_FUNCTION_SELECTOR))),
                getContractInfo("nonExistingMirrorAddress").hasCostAnswerPrecheck(INVALID_CONTRACT_ID));
    }

    @HapiTest
    final Stream<DynamicTest> directCallToNonExistingNonMirrorAddressResultsInSuccessfulNoOp() {

        return hapiTest(
                withOpContext((spec, ctxLog) -> spec.registry()
                        .saveContractId(
                                "nonExistingNonMirrorAddress",
                                spec,
                                ByteString.copyFrom(unhex(NON_EXISTING_NON_MIRROR_ADDRESS)))),
                withOpContext((spec, ctxLog) -> allRunFor(
                        spec,
                        atomicBatch(
                                        contractCallWithFunctionAbi(
                                                        "nonExistingNonMirrorAddress",
                                                        getABIFor(FUNCTION, NAME, ERC_721_ABI))
                                                .gas(GAS_LIMIT_FOR_CALL)
                                                .via("directCallToNonExistingNonMirrorAddress")
                                                .batchKey(BATCH_OPERATOR),
                                        // attempt call again, make sure the result is the same
                                        contractCallWithFunctionAbi(
                                                        "nonExistingNonMirrorAddress",
                                                        getABIFor(FUNCTION, NAME, ERC_721_ABI))
                                                .gas(GAS_LIMIT_FOR_CALL)
                                                .via("directCallToNonExistingNonMirrorAddress2")
                                                .batchKey(BATCH_OPERATOR))
                                .payingWith(BATCH_OPERATOR))),
                getTxnRecord("directCallToNonExistingNonMirrorAddress")
                        .hasPriority(recordWith()
                                .status(SUCCESS)
                                .contractCallResult(
                                        resultWith().gasUsed(INTRINSIC_GAS_COST + EXTRA_GAS_FOR_FUNCTION_SELECTOR))),
                getTxnRecord("directCallToNonExistingNonMirrorAddress2")
                        .hasPriority(recordWith()
                                .status(SUCCESS)
                                .contractCallResult(
                                        resultWith().gasUsed(INTRINSIC_GAS_COST + EXTRA_GAS_FOR_FUNCTION_SELECTOR))),
                getContractInfo("nonExistingNonMirrorAddress").hasCostAnswerPrecheck(INVALID_CONTRACT_ID));
    }

    @HapiTest
    final Stream<DynamicTest> directCallToRevertingContractRevertsWithCorrectRevertReason() {

        return hapiTest(
                uploadInitCode(INTERNAL_CALLEE_CONTRACT),
                contractCreate(INTERNAL_CALLEE_CONTRACT),
                withOpContext((spec, ctxLog) -> allRunFor(
                        spec,
                        atomicBatch(contractCall(INTERNAL_CALLEE_CONTRACT, REVERT_WITH_REVERT_REASON_FUNCTION)
                                        .gas(GAS_LIMIT_FOR_CALL)
                                        .via(INNER_TXN)
                                        .hasKnownStatusFrom(CONTRACT_REVERT_EXECUTED)
                                        .batchKey(BATCH_OPERATOR))
                                .payingWith(BATCH_OPERATOR)
                                .hasKnownStatus(INNER_TRANSACTION_FAILED))),
                getTxnRecord(INNER_TXN)
                        .hasPriority(recordWith()
                                .status(CONTRACT_REVERT_EXECUTED)
                                .contractCallResult(resultWith()
                                        .gasUsed(21472)
                                        .error(errorMessageResult("RevertReason")
                                                .getBytes()
                                                .toString()))));
    }

    @HapiTest
    final Stream<DynamicTest> directCallToExistingCryptoAccountResultsInSuccess() {

        AtomicReference<AccountID> mirrorAccountID = new AtomicReference<>();

        return hapiTest(
                newKeyNamed(ECDSA_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate("MirrorAccount").balance(ONE_HUNDRED_HBARS).exposingCreatedIdTo(mirrorAccountID::set),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, ECDSA_KEY, ONE_HUNDRED_HBARS)),
                withOpContext((spec, opLog) -> {
                    spec.registry()
                            .saveContractId(
                                    "mirrorAddress",
                                    asContract("0.0." + mirrorAccountID.get().getAccountNum()));
                    updateSpecFor(spec, ECDSA_KEY);
                    spec.registry()
                            .saveContractId(
                                    "nonMirrorAddress",
                                    asContract("0.0."
                                            + spec.registry()
                                                    .getAccountID(ECDSA_KEY)
                                                    .getAccountNum()));
                }),
                withOpContext((spec, ctxLog) -> allRunFor(
                        spec,
                        atomicBatch(
                                        contractCallWithFunctionAbi(
                                                        "mirrorAddress", getABIFor(FUNCTION, NAME, ERC_721_ABI))
                                                .gas(GAS_LIMIT_FOR_CALL)
                                                .via("callToMirrorAddress")
                                                .batchKey(BATCH_OPERATOR),
                                        contractCallWithFunctionAbi(
                                                        "nonMirrorAddress", getABIFor(FUNCTION, NAME, ERC_721_ABI))
                                                .gas(GAS_LIMIT_FOR_CALL)
                                                .via("callToNonMirrorAddress")
                                                .batchKey(BATCH_OPERATOR))
                                .payingWith(BATCH_OPERATOR))),
                getTxnRecord("callToMirrorAddress")
                        .hasPriority(recordWith()
                                .status(SUCCESS)
                                .contractCallResult(
                                        resultWith().gasUsed(INTRINSIC_GAS_COST + EXTRA_GAS_FOR_FUNCTION_SELECTOR))),
                getTxnRecord("callToNonMirrorAddress")
                        .hasPriority(recordWith()
                                .status(SUCCESS)
                                .contractCallResult(
                                        resultWith().gasUsed(INTRINSIC_GAS_COST + EXTRA_GAS_FOR_FUNCTION_SELECTOR))));
    }

    @HapiTest
    final Stream<DynamicTest> directCallWithValueToExistingCryptoAccountResultsInSuccess() {

        AtomicReference<AccountID> mirrorAccountID = new AtomicReference<>();

        return hapiTest(
                newKeyNamed(ECDSA_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate("MirrorAccount").balance(ONE_HUNDRED_HBARS).exposingCreatedIdTo(mirrorAccountID::set),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, ECDSA_KEY, ONE_HUNDRED_HBARS)),
                withOpContext((spec, opLog) -> {
                    spec.registry().saveContractId("mirrorAddress", asContract(mirrorAccountID.get()));
                    updateSpecFor(spec, ECDSA_KEY);
                    final var ecdsaKey = spec.registry()
                            .getKey(ECDSA_KEY)
                            .getECDSASecp256K1()
                            .toByteArray();
                    final var senderAddress = ByteString.copyFrom(recoverAddressFromPubKey(ecdsaKey));
                    spec.registry().saveContractId("nonMirrorAddress", spec, senderAddress);
                    spec.registry()
                            .saveAccountId("NonMirrorAccount", spec.registry().getAccountID(ECDSA_KEY));
                }),
                withOpContext((spec, ctxLog) -> allRunFor(
                        spec,
                        balanceSnapshot("mirrorSnapshot", "MirrorAccount"),
                        balanceSnapshot("nonMirrorSnapshot", "NonMirrorAccount"),
                        atomicBatch(
                                        contractCallWithFunctionAbi(
                                                        "mirrorAddress", getABIFor(FUNCTION, NAME, ERC_721_ABI))
                                                .gas(GAS_LIMIT_FOR_CALL)
                                                .sending(ONE_HBAR)
                                                .via("callToMirrorAddress")
                                                .batchKey(BATCH_OPERATOR),
                                        contractCallWithFunctionAbi(
                                                        "nonMirrorAddress", getABIFor(FUNCTION, NAME, ERC_721_ABI))
                                                .sending(ONE_HBAR)
                                                .gas(GAS_LIMIT_FOR_CALL)
                                                .via("callToNonMirrorAddress")
                                                .batchKey(BATCH_OPERATOR))
                                .payingWith(BATCH_OPERATOR))),
                getTxnRecord("callToMirrorAddress")
                        .hasPriority(recordWith()
                                .status(SUCCESS)
                                .contractCallResult(
                                        resultWith().gasUsed(INTRINSIC_GAS_COST + EXTRA_GAS_FOR_FUNCTION_SELECTOR))),
                getTxnRecord("callToNonMirrorAddress")
                        .hasPriority(recordWith()
                                .status(SUCCESS)
                                .contractCallResult(
                                        resultWith().gasUsed(INTRINSIC_GAS_COST + EXTRA_GAS_FOR_FUNCTION_SELECTOR))),
                getAccountBalance("MirrorAccount").hasTinyBars(changeFromSnapshot("mirrorSnapshot", ONE_HBAR)),
                getAccountBalance("NonMirrorAccount").hasTinyBars(changeFromSnapshot("nonMirrorSnapshot", ONE_HBAR)));
    }

    @HapiTest
    final Stream<DynamicTest> internalCallToNonExistingMirrorAddressResultsInNoopSuccess() {
        return hapiTest(
                uploadInitCode(INTERNAL_CALLER_CONTRACT),
                contractCreate(INTERNAL_CALLER_CONTRACT).balance(ONE_HBAR),
                atomicBatch(contractCall(
                                        INTERNAL_CALLER_CONTRACT,
                                        CALL_NON_EXISTING_FUNCTION,
                                        mirrorAddrParamFunction(FIRST_NONEXISTENT_CONTRACT_NUM + 1))
                                .gas(GAS_LIMIT_FOR_CALL)
                                .via(INNER_TXN)
                                .exposingGasTo((status, gas) ->
                                        assertTrue(gas == 24996 || gas == 24972, "Gas is not correct!"))
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                getTxnRecord(INNER_TXN).hasPriority(recordWith().status(SUCCESS)));
    }

    @HapiTest
    final Stream<DynamicTest> internalCallToExistingMirrorAddressResultsInSuccessfulCall() {
        final AtomicReference<ContractID> calleeId = new AtomicReference<>();

        return hapiTest(
                uploadInitCode(INTERNAL_CALLER_CONTRACT, INTERNAL_CALLEE_CONTRACT),
                contractCreate(INTERNAL_CALLER_CONTRACT)
                        .balance(ONE_HBAR)
                        // Adding refusingEthConversion() due to fee differences and not supported address type
                        .refusingEthConversion(),
                contractCreate(INTERNAL_CALLEE_CONTRACT)
                        .exposingContractIdTo(calleeId::set)
                        // Adding refusingEthConversion() due to fee differences and not supported address type
                        .refusingEthConversion(),
                atomicBatch(contractCall(
                                        INTERNAL_CALLER_CONTRACT,
                                        CALL_EXTERNAL_FUNCTION,
                                        () -> mirrorAddrWith(calleeId.get()))
                                .gas(GAS_LIMIT_FOR_CALL * 2)
                                .via(INNER_TXN)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                getTxnRecord(INNER_TXN)
                        .hasPriority(recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith()
                                        .createdContractIdsCount(0)
                                        .contractCallResult(bigIntResult(1))
                                        .gasUsedModuloIntrinsicVariation(48107))));
    }

    @HapiTest
    final Stream<DynamicTest> internalCallToNonExistingNonMirrorAddressResultsInNoopSuccess() {

        return hapiTest(
                uploadInitCode(INTERNAL_CALLER_CONTRACT),
                contractCreate(INTERNAL_CALLER_CONTRACT).balance(ONE_HBAR),
                atomicBatch(contractCall(
                                        INTERNAL_CALLER_CONTRACT,
                                        CALL_NON_EXISTING_FUNCTION,
                                        nonMirrorAddrWith(FIRST_NONEXISTENT_CONTRACT_NUM + 2))
                                .gas(GAS_LIMIT_FOR_CALL)
                                .via(INNER_TXN)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                getTxnRecord(INNER_TXN)
                        .hasPriority(recordWith()
                                .status(SUCCESS)
                                .contractCallResult(
                                        resultWith().createdContractIdsCount(0).gasUsed(25020))));
    }

    @HapiTest
    final Stream<DynamicTest> internalCallToExistingRevertingResultsInSuccessfulTopLevelTxn() {

        final AtomicReference<ContractID> calleeId = new AtomicReference<>();

        return hapiTest(
                uploadInitCode(INTERNAL_CALLER_CONTRACT, INTERNAL_CALLEE_CONTRACT),
                contractCreate(INTERNAL_CALLER_CONTRACT).balance(ONE_HBAR),
                contractCreate(INTERNAL_CALLEE_CONTRACT).exposingContractIdTo(calleeId::set),
                atomicBatch(contractCall(
                                        INTERNAL_CALLER_CONTRACT,
                                        CALL_REVERT_WITH_REVERT_REASON_FUNCTION,
                                        () -> mirrorAddrWith(calleeId.get()))
                                .gas(GAS_LIMIT_FOR_CALL * 8)
                                .hasKnownStatus(SUCCESS)
                                .via(INNER_TXN)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                getTxnRecord(INNER_TXN).hasPriority(recordWith().status(SUCCESS)));
    }

    @HapiTest
    final Stream<DynamicTest> internalTransferToNonExistingMirrorAddressResultsInInvalidAliasKey() {
        return hapiTest(
                uploadInitCode(INTERNAL_CALLER_CONTRACT),
                contractCreate(INTERNAL_CALLER_CONTRACT).balance(ONE_HBAR),
                atomicBatch(contractCall(
                                        INTERNAL_CALLER_CONTRACT,
                                        TRANSFER_TO_FUNCTION,
                                        mirrorAddrParamFunction(FIRST_NONEXISTENT_CONTRACT_NUM + 3))
                                .gas(GAS_LIMIT_FOR_CALL * 4)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                getAccountBalance("0.0." + (FIRST_NONEXISTENT_CONTRACT_NUM + 3))
                        .nodePayment(ONE_HBAR)
                        .hasAnswerOnlyPrecheck(INVALID_ACCOUNT_ID));
    }

    @HapiTest
    final Stream<DynamicTest> internalTransferToExistingMirrorAddressResultsInSuccess() {

        AtomicReference<AccountID> receiverId = new AtomicReference<>();

        return hapiTest(
                cryptoCreate(RECEIVER).exposingCreatedIdTo(receiverId::set),
                uploadInitCode(INTERNAL_CALLER_CONTRACT),
                contractCreate(INTERNAL_CALLER_CONTRACT).balance(ONE_HBAR),
                withOpContext((spec, op) -> allRunFor(
                        spec,
                        balanceSnapshot("initialBalance", asAccountString(receiverId.get())),
                        atomicBatch(contractCall(
                                                INTERNAL_CALLER_CONTRACT,
                                                TRANSFER_TO_FUNCTION,
                                                mirrorAddrWith(
                                                        spec, receiverId.get().getAccountNum()))
                                        .gas(GAS_LIMIT_FOR_CALL * 4)
                                        .via(INNER_TXN)
                                        .batchKey(BATCH_OPERATOR))
                                .payingWith(BATCH_OPERATOR))),
                getTxnRecord(INNER_TXN)
                        .hasPriority(recordWith()
                                .transfers(including(tinyBarsFromTo(INTERNAL_CALLER_CONTRACT, RECEIVER, 1)))),
                getAccountBalance(RECEIVER).hasTinyBars(changeFromSnapshot("initialBalance", 1)));
    }

    @HapiTest
    final Stream<DynamicTest> internalTransferToNonExistingNonMirrorAddressResultsInRevert() {
        return hapiTest(
                cryptoCreate(CUSTOM_PAYER).balance(ONE_HUNDRED_HBARS),
                uploadInitCode(INTERNAL_CALLER_CONTRACT),
                contractCreate(INTERNAL_CALLER_CONTRACT).balance(ONE_HBAR),
                atomicBatch(contractCall(
                                        INTERNAL_CALLER_CONTRACT,
                                        TRANSFER_TO_FUNCTION,
                                        nonMirrorAddrWith(FIRST_NONEXISTENT_CONTRACT_NUM + 4))
                                .gas(GAS_LIMIT_FOR_CALL * 4)
                                .payingWith(CUSTOM_PAYER)
                                .via(INNER_TXN)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                getTxnRecord(INNER_TXN).hasPriority(recordWith().status(CONTRACT_REVERT_EXECUTED)));
    }

    @HapiTest
    final Stream<DynamicTest> internalTransferToExistingNonMirrorAddressResultsInSuccess() {

        return hapiTest(
                newKeyNamed(ECDSA_KEY).shape(SECP_256K1_SHAPE),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, ECDSA_KEY, ONE_HUNDRED_HBARS)),
                withOpContext((spec, opLog) -> updateSpecFor(spec, ECDSA_KEY)),
                uploadInitCode(INTERNAL_CALLER_CONTRACT),
                contractCreate(INTERNAL_CALLER_CONTRACT).balance(ONE_HBAR),
                withOpContext((spec, op) -> {
                    final var ecdsaKey = spec.registry().getKey(ECDSA_KEY);
                    final var tmp = ecdsaKey.getECDSASecp256K1().toByteArray();
                    final var addressBytes = recoverAddressFromPubKey(tmp);
                    allRunFor(
                            spec,
                            balanceSnapshot("autoCreatedSnapshot", ECDSA_KEY).accountIsAlias(),
                            atomicBatch(contractCall(
                                                    INTERNAL_CALLER_CONTRACT,
                                                    TRANSFER_TO_FUNCTION,
                                                    asHeadlongAddress(addressBytes))
                                            .gas(GAS_LIMIT_FOR_CALL * 4)
                                            .via(INNER_TXN)
                                            .batchKey(BATCH_OPERATOR))
                                    .payingWith(BATCH_OPERATOR));
                }),
                getTxnRecord(INNER_TXN)
                        .hasPriority(recordWith()
                                .transfers(including(tinyBarsFromTo(INTERNAL_CALLER_CONTRACT, ECDSA_KEY, 1)))),
                getAutoCreatedAccountBalance(ECDSA_KEY).hasTinyBars(changeFromSnapshot("autoCreatedSnapshot", 1)));
    }

    @HapiTest
    final Stream<DynamicTest> internalSendToNonExistingMirrorAddressDoesNotLazyCreateIt() {
        return hapiTest(
                uploadInitCode(INTERNAL_CALLER_CONTRACT),
                contractCreate(INTERNAL_CALLER_CONTRACT).balance(ONE_HBAR),
                atomicBatch(contractCall(
                                        INTERNAL_CALLER_CONTRACT,
                                        SEND_TO_FUNCTION,
                                        mirrorAddrParamFunction(FIRST_NONEXISTENT_CONTRACT_NUM + 5))
                                .gas(GAS_LIMIT_FOR_CALL * 4)
                                .via(INNER_TXN)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                getAccountBalance("0.0." + (FIRST_NONEXISTENT_CONTRACT_NUM + 5))
                        .nodePayment(ONE_HBAR)
                        .hasAnswerOnlyPrecheck(INVALID_ACCOUNT_ID));
    }

    @HapiTest
    final Stream<DynamicTest> internalSendToExistingMirrorAddressResultsInSuccess() {

        AtomicReference<AccountID> receiverId = new AtomicReference<>();

        return hapiTest(
                cryptoCreate(RECEIVER).exposingCreatedIdTo(receiverId::set),
                uploadInitCode(INTERNAL_CALLER_CONTRACT),
                contractCreate(INTERNAL_CALLER_CONTRACT).balance(ONE_HBAR),
                withOpContext((spec, op) -> allRunFor(
                        spec,
                        balanceSnapshot("initialBalance", asAccountString(receiverId.get())),
                        atomicBatch(contractCall(
                                                INTERNAL_CALLER_CONTRACT,
                                                SEND_TO_FUNCTION,
                                                mirrorAddrWith(
                                                        spec, receiverId.get().getAccountNum()))
                                        .gas(GAS_LIMIT_FOR_CALL * 4)
                                        .via(INNER_TXN)
                                        .batchKey(BATCH_OPERATOR))
                                .payingWith(BATCH_OPERATOR))),
                getTxnRecord(INNER_TXN)
                        .hasPriority(recordWith()
                                .transfers(including(tinyBarsFromTo(INTERNAL_CALLER_CONTRACT, RECEIVER, 1)))),
                getAccountBalance(RECEIVER).hasTinyBars(changeFromSnapshot("initialBalance", 1)));
    }

    @HapiTest
    final Stream<DynamicTest> internalSendToNonExistingNonMirrorAddressResultsInSuccess() {

        AtomicReference<Bytes> nonExistingNonMirrorAddress = new AtomicReference<>();

        return hapiTest(
                cryptoCreate(CUSTOM_PAYER).balance(ONE_HUNDRED_HBARS),
                newKeyNamed(ECDSA_KEY).shape(SECP_256K1_SHAPE),
                withOpContext((spec, op) -> {
                    final var ecdsaKey = spec.registry().getKey(ECDSA_KEY);
                    final var tmp = ecdsaKey.getECDSASecp256K1().toByteArray();
                    final var addressBytes = recoverAddressFromPubKey(tmp);
                    nonExistingNonMirrorAddress.set(Bytes.of(addressBytes));
                }),
                uploadInitCode(INTERNAL_CALLER_CONTRACT),
                contractCreate(INTERNAL_CALLER_CONTRACT).balance(ONE_HBAR),
                withOpContext((spec, op) -> allRunFor(
                        spec,
                        balanceSnapshot("contractBalance", INTERNAL_CALLER_CONTRACT),
                        atomicBatch(contractCall(
                                                INTERNAL_CALLER_CONTRACT,
                                                SEND_TO_FUNCTION,
                                                asHeadlongAddress(nonExistingNonMirrorAddress
                                                        .get()
                                                        .toArray()))
                                        .gas(GAS_LIMIT_FOR_CALL * 4)
                                        .payingWith(CUSTOM_PAYER)
                                        .batchKey(BATCH_OPERATOR))
                                .payingWith(BATCH_OPERATOR))),
                getAccountBalance(INTERNAL_CALLER_CONTRACT).hasTinyBars(changeFromSnapshot("contractBalance", 0)),
                sourcing(() -> getAliasedAccountInfo(ByteString.copyFrom(
                                nonExistingNonMirrorAddress.get().toArray()))
                        .hasCostAnswerPrecheck(INVALID_ACCOUNT_ID)));
    }

    @HapiTest
    final Stream<DynamicTest> internalSendToExistingNonMirrorAddressResultsInSuccess() {

        return hapiTest(
                newKeyNamed(ECDSA_KEY).shape(SECP_256K1_SHAPE),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, ECDSA_KEY, ONE_HUNDRED_HBARS)),
                withOpContext((spec, opLog) -> updateSpecFor(spec, ECDSA_KEY)),
                uploadInitCode(INTERNAL_CALLER_CONTRACT),
                contractCreate(INTERNAL_CALLER_CONTRACT).balance(ONE_HBAR),
                withOpContext((spec, op) -> {
                    final var ecdsaKey = spec.registry().getKey(ECDSA_KEY);
                    final var tmp = ecdsaKey.getECDSASecp256K1().toByteArray();
                    final var addressBytes = recoverAddressFromPubKey(tmp);
                    allRunFor(
                            spec,
                            balanceSnapshot("autoCreatedSnapshot", ECDSA_KEY).accountIsAlias(),
                            atomicBatch(contractCall(
                                                    INTERNAL_CALLER_CONTRACT,
                                                    SEND_TO_FUNCTION,
                                                    asHeadlongAddress(addressBytes))
                                            .gas(GAS_LIMIT_FOR_CALL * 4)
                                            .via(INNER_TXN)
                                            .batchKey(BATCH_OPERATOR))
                                    .payingWith(BATCH_OPERATOR));
                }),
                getTxnRecord(INNER_TXN)
                        .hasPriority(recordWith()
                                .transfers(including(tinyBarsFromTo(INTERNAL_CALLER_CONTRACT, ECDSA_KEY, 1)))),
                getAutoCreatedAccountBalance(ECDSA_KEY).hasTinyBars(changeFromSnapshot("autoCreatedSnapshot", 1)));
    }

    @HapiTest
    final Stream<DynamicTest> internalCallWithValueToNonExistingMirrorAddressResultsInInvalidAliasKey() {
        return hapiTest(
                uploadInitCode(INTERNAL_CALLER_CONTRACT),
                contractCreate(INTERNAL_CALLER_CONTRACT).balance(ONE_HBAR),
                atomicBatch(contractCall(
                                        INTERNAL_CALLER_CONTRACT,
                                        CALL_WITH_VALUE_TO_FUNCTION,
                                        mirrorAddrParamFunction(FIRST_NONEXISTENT_CONTRACT_NUM + 6))
                                .gas(ENOUGH_GAS_LIMIT_FOR_CREATION)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                getAccountBalance(String.valueOf(FIRST_NONEXISTENT_CONTRACT_NUM + 6))
                        .nodePayment(ONE_HBAR)
                        .hasAnswerOnlyPrecheck(INVALID_ACCOUNT_ID));
    }

    @HapiTest
    final Stream<DynamicTest> internalCallWithValueToExistingMirrorAddressResultsInSuccess() {

        AtomicReference<AccountID> receiverId = new AtomicReference<>();

        return hapiTest(
                cryptoCreate(RECEIVER).exposingCreatedIdTo(receiverId::set),
                uploadInitCode(INTERNAL_CALLER_CONTRACT),
                contractCreate(INTERNAL_CALLER_CONTRACT).balance(ONE_HBAR),
                withOpContext((spec, op) -> allRunFor(
                        spec,
                        balanceSnapshot("initialBalance", asAccountString(receiverId.get())),
                        atomicBatch(contractCall(
                                                INTERNAL_CALLER_CONTRACT,
                                                CALL_WITH_VALUE_TO_FUNCTION,
                                                mirrorAddrWith(
                                                        spec, receiverId.get().getAccountNum()))
                                        .gas(GAS_LIMIT_FOR_CALL * 4)
                                        .via(INNER_TXN)
                                        .batchKey(BATCH_OPERATOR))
                                .payingWith(BATCH_OPERATOR))),
                getTxnRecord(INNER_TXN)
                        .hasPriority(recordWith()
                                .transfers(including(tinyBarsFromTo(INTERNAL_CALLER_CONTRACT, RECEIVER, 1)))),
                getAccountBalance(RECEIVER).hasTinyBars(changeFromSnapshot("initialBalance", 1)));
    }

    @HapiTest
    final Stream<DynamicTest>
            internalCallWithValueToNonExistingNonMirrorAddressWithoutEnoughGasForLazyCreationResultsInSuccessNoAccountCreated() {
        return defaultHapiSpec(
                        "internalCallWithValueToNonExistingNonMirrorAddressWithoutEnoughGasForLazyCreationResultsInSuccessNoAccountCreated")
                .given(
                        cryptoCreate(CUSTOM_PAYER),
                        uploadInitCode(INTERNAL_CALLER_CONTRACT),
                        contractCreate(INTERNAL_CALLER_CONTRACT).balance(ONE_HBAR))
                .when(
                        balanceSnapshot("contractBalance", INTERNAL_CALLER_CONTRACT),
                        atomicBatch(contractCall(
                                                INTERNAL_CALLER_CONTRACT,
                                                CALL_WITH_VALUE_TO_FUNCTION,
                                                nonMirrorAddrWith(FIRST_NONEXISTENT_CONTRACT_NUM + 7))
                                        .payingWith(CUSTOM_PAYER)
                                        .gas(NOT_ENOUGH_GAS_LIMIT_FOR_CREATION)
                                        .via("transferWithLowGasLimit")
                                        .batchKey(BATCH_OPERATOR))
                                .payingWith(BATCH_OPERATOR))
                .then(
                        getTxnRecord("transferWithLowGasLimit")
                                .hasPriority(recordWith().status(SUCCESS)),
                        getAccountBalance(INTERNAL_CALLER_CONTRACT)
                                .hasTinyBars(changeFromSnapshot("contractBalance", 0)));
    }

    @HapiTest
    final Stream<DynamicTest>
            internalCallWithValueToNonExistingNonMirrorAddressWithEnoughGasForLazyCreationResultsInSuccessAccountCreated() {
        return defaultHapiSpec(
                        "internalCallWithValueToNonExistingNonMirrorAddressWithEnoughGasForLazyCreationResultsInSuccessAccountCreated")
                .given(
                        cryptoCreate(CUSTOM_PAYER),
                        uploadInitCode(INTERNAL_CALLER_CONTRACT),
                        contractCreate(INTERNAL_CALLER_CONTRACT).balance(ONE_HBAR))
                .when(
                        balanceSnapshot("contractBalance", INTERNAL_CALLER_CONTRACT),
                        atomicBatch(contractCall(
                                                INTERNAL_CALLER_CONTRACT,
                                                CALL_WITH_VALUE_TO_FUNCTION,
                                                nonMirrorAddrWith(FIRST_NONEXISTENT_CONTRACT_NUM + 8))
                                        .payingWith(CUSTOM_PAYER)
                                        .gas(ENOUGH_GAS_LIMIT_FOR_CREATION)
                                        .via("transferWithEnoughGasLimit")
                                        .batchKey(BATCH_OPERATOR))
                                .payingWith(BATCH_OPERATOR))
                .then(
                        getTxnRecord("transferWithEnoughGasLimit")
                                .hasPriority(recordWith().status(SUCCESS)),
                        getAccountBalance(INTERNAL_CALLER_CONTRACT)
                                .hasTinyBars(changeFromSnapshot("contractBalance", -1)));
    }

    @HapiTest
    final Stream<DynamicTest> internalCallWithValueToExistingNonMirrorAddressResultsInSuccess() {

        return hapiTest(
                newKeyNamed(ECDSA_KEY).shape(SECP_256K1_SHAPE),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, ECDSA_KEY, ONE_HUNDRED_HBARS)),
                withOpContext((spec, opLog) -> updateSpecFor(spec, ECDSA_KEY)),
                uploadInitCode(INTERNAL_CALLER_CONTRACT),
                contractCreate(INTERNAL_CALLER_CONTRACT).balance(ONE_HBAR),
                withOpContext((spec, op) -> {
                    final var ecdsaKey = spec.registry().getKey(ECDSA_KEY);
                    final var tmp = ecdsaKey.getECDSASecp256K1().toByteArray();
                    final var addressBytes = recoverAddressFromPubKey(tmp);
                    allRunFor(
                            spec,
                            balanceSnapshot("autoCreatedSnapshot", ECDSA_KEY).accountIsAlias(),
                            atomicBatch(contractCall(
                                                    INTERNAL_CALLER_CONTRACT,
                                                    CALL_WITH_VALUE_TO_FUNCTION,
                                                    asHeadlongAddress(addressBytes))
                                            .gas(GAS_LIMIT_FOR_CALL * 4)
                                            .via(INNER_TXN)
                                            .batchKey(BATCH_OPERATOR))
                                    .payingWith(BATCH_OPERATOR));
                }),
                getTxnRecord(INNER_TXN)
                        .hasPriority(recordWith()
                                .transfers(including(tinyBarsFromTo(INTERNAL_CALLER_CONTRACT, ECDSA_KEY, 1)))),
                getAutoCreatedAccountBalance(ECDSA_KEY).hasTinyBars(changeFromSnapshot("autoCreatedSnapshot", 1)));
    }

    @HapiTest
    final Stream<DynamicTest> internalCallToDeletedContractReturnsSuccessfulNoop() {
        final AtomicReference<ContractID> calleeId = new AtomicReference<>();
        return hapiTest(
                uploadInitCode(INTERNAL_CALLER_CONTRACT, INTERNAL_CALLEE_CONTRACT),
                contractCreate(INTERNAL_CALLER_CONTRACT)
                        // Refusing ethereum create conversion, because we get INVALID_SIGNATURE upon
                        // tokenAssociate,
                        // since we have CONTRACT_ID key
                        .refusingEthConversion()
                        .balance(ONE_HBAR),
                contractCreate(INTERNAL_CALLEE_CONTRACT)
                        // Refusing ethereum create conversion, because we get INVALID_SIGNATURE upon
                        // tokenAssociate,
                        // since we have CONTRACT_ID key
                        .refusingEthConversion()
                        .exposingContractIdTo(calleeId::set),
                atomicBatch(
                                contractDelete(INTERNAL_CALLEE_CONTRACT).batchKey(BATCH_OPERATOR),
                                contractCall(
                                                INTERNAL_CALLER_CONTRACT,
                                                CALL_EXTERNAL_FUNCTION,
                                                () -> mirrorAddrWith(calleeId.get()))
                                        .gas(50_000L)
                                        .via(INNER_TXN)
                                        .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                withOpContext((spec, opLog) -> {
                    final var lookup = getTxnRecord(INNER_TXN);
                    allRunFor(spec, lookup);
                    final var result =
                            lookup.getResponseRecord().getContractCallResult().getContractCallResult();
                    assertEquals(ByteString.copyFrom(new byte[32]), result);
                }));
    }

    @HapiTest
    final Stream<DynamicTest> callingDestructedContractReturnsStatusSuccess() {
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
                                .hasKnownStatus(SUCCESS)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR));
    }

    @HapiTest
    final Stream<DynamicTest> internalStaticCallNonExistingMirrorAddressResultsInSuccess() {
        return hapiTest(
                uploadInitCode(INTERNAL_CALLER_CONTRACT),
                contractCreate(INTERNAL_CALLER_CONTRACT).balance(ONE_HBAR),
                atomicBatch(contractCall(
                                        INTERNAL_CALLER_CONTRACT,
                                        STATIC_CALL_EXTERNAL_FUNCTION,
                                        mirrorAddrParamFunction(FIRST_NONEXISTENT_CONTRACT_NUM + 9))
                                .gas(GAS_LIMIT_FOR_CALL)
                                .via(INNER_TXN)
                                .hasKnownStatus(SUCCESS)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                getTxnRecord(INNER_TXN)
                        .logged()
                        .hasPriority(
                                recordWith().contractCallResult(resultWith().contractCallResult(bigIntResult(0)))));
    }

    @HapiTest
    final Stream<DynamicTest> internalStaticCallExistingMirrorAddressResultsInSuccess() {
        AtomicReference<AccountID> receiverId = new AtomicReference<>();
        return hapiTest(
                cryptoCreate(RECEIVER).exposingCreatedIdTo(receiverId::set),
                uploadInitCode(INTERNAL_CALLER_CONTRACT),
                contractCreate(INTERNAL_CALLER_CONTRACT).balance(ONE_HBAR),
                withOpContext((spec, op) -> allRunFor(
                        spec,
                        balanceSnapshot("initialBalance", asAccountString(receiverId.get())),
                        atomicBatch(contractCall(
                                                INTERNAL_CALLER_CONTRACT,
                                                STATIC_CALL_EXTERNAL_FUNCTION,
                                                mirrorAddrWith(
                                                        spec, receiverId.get().getAccountNum()))
                                        .gas(GAS_LIMIT_FOR_CALL)
                                        .via(INNER_TXN)
                                        .batchKey(BATCH_OPERATOR))
                                .payingWith(BATCH_OPERATOR))),
                getTxnRecord(INNER_TXN)
                        .hasPriority(
                                recordWith().contractCallResult(resultWith().contractCallResult(bigIntResult(0)))),
                getAccountBalance(RECEIVER).hasTinyBars(changeFromSnapshot("initialBalance", 0)));
    }

    @HapiTest
    final Stream<DynamicTest> internalStaticCallNonExistingNonMirrorAddressResultsInSuccess() {
        AtomicReference<Bytes> nonExistingNonMirrorAddress = new AtomicReference<>();
        return hapiTest(
                cryptoCreate(CUSTOM_PAYER).balance(ONE_HUNDRED_HBARS),
                newKeyNamed(ECDSA_KEY).shape(SECP_256K1_SHAPE),
                withOpContext((spec, op) -> {
                    final var ecdsaKey = spec.registry().getKey(ECDSA_KEY);
                    final var tmp = ecdsaKey.getECDSASecp256K1().toByteArray();
                    final var addressBytes = recoverAddressFromPubKey(tmp);
                    nonExistingNonMirrorAddress.set(Bytes.of(addressBytes));
                }),
                uploadInitCode(INTERNAL_CALLER_CONTRACT),
                contractCreate(INTERNAL_CALLER_CONTRACT).balance(ONE_HBAR),
                withOpContext((spec, op) -> allRunFor(
                        spec,
                        balanceSnapshot("contractBalance", INTERNAL_CALLER_CONTRACT),
                        atomicBatch(contractCall(
                                                INTERNAL_CALLER_CONTRACT,
                                                STATIC_CALL_EXTERNAL_FUNCTION,
                                                asHeadlongAddress(nonExistingNonMirrorAddress
                                                        .get()
                                                        .toArray()))
                                        .gas(GAS_LIMIT_FOR_CALL)
                                        .payingWith(CUSTOM_PAYER)
                                        .via(INNER_TXN)
                                        .batchKey(BATCH_OPERATOR))
                                .payingWith(BATCH_OPERATOR))),
                getTxnRecord(INNER_TXN)
                        .hasPriority(
                                recordWith().contractCallResult(resultWith().contractCallResult(bigIntResult(0)))),
                getAccountBalance(INTERNAL_CALLER_CONTRACT).hasTinyBars(changeFromSnapshot("contractBalance", 0)));
    }

    @HapiTest
    final Stream<DynamicTest> internalStaticCallExistingNonMirrorAddressResultsInSuccess() {
        return hapiTest(
                newKeyNamed(ECDSA_KEY).shape(SECP_256K1_SHAPE),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, ECDSA_KEY, ONE_HUNDRED_HBARS)),
                withOpContext((spec, opLog) -> updateSpecFor(spec, ECDSA_KEY)),
                uploadInitCode(INTERNAL_CALLER_CONTRACT),
                contractCreate(INTERNAL_CALLER_CONTRACT).balance(ONE_HBAR),
                withOpContext((spec, op) -> {
                    final var ecdsaKey = spec.registry().getKey(ECDSA_KEY);
                    final var tmp = ecdsaKey.getECDSASecp256K1().toByteArray();
                    final var addressBytes = recoverAddressFromPubKey(tmp);
                    allRunFor(
                            spec,
                            balanceSnapshot("targetSnapshot", ECDSA_KEY).accountIsAlias(),
                            atomicBatch(contractCall(
                                                    INTERNAL_CALLER_CONTRACT,
                                                    STATIC_CALL_EXTERNAL_FUNCTION,
                                                    asHeadlongAddress(addressBytes))
                                            .gas(GAS_LIMIT_FOR_CALL)
                                            .via(INNER_TXN)
                                            .batchKey(BATCH_OPERATOR))
                                    .payingWith(BATCH_OPERATOR));
                }),
                getTxnRecord(INNER_TXN)
                        .hasPriority(
                                recordWith().contractCallResult(resultWith().contractCallResult(bigIntResult(0)))),
                getAutoCreatedAccountBalance(ECDSA_KEY).hasTinyBars(changeFromSnapshot("targetSnapshot", 0)));
    }

    @HapiTest
    final Stream<DynamicTest> internalDelegateCallNonExistingMirrorAddressResultsInSuccess() {
        return hapiTest(
                uploadInitCode(INTERNAL_CALLER_CONTRACT),
                contractCreate(INTERNAL_CALLER_CONTRACT).balance(ONE_HBAR),
                atomicBatch(contractCall(
                                        INTERNAL_CALLER_CONTRACT,
                                        DELEGATE_CALL_EXTERNAL_FUNCTION,
                                        mirrorAddrParamFunction(FIRST_NONEXISTENT_CONTRACT_NUM + 10))
                                .gas(GAS_LIMIT_FOR_CALL)
                                .via(INNER_TXN)
                                .hasKnownStatus(SUCCESS)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                getTxnRecord(INNER_TXN)
                        .logged()
                        .hasPriority(
                                recordWith().contractCallResult(resultWith().contractCallResult(bigIntResult(0)))));
    }

    @HapiTest
    final Stream<DynamicTest> internalDelegateCallExistingMirrorAddressResultsInSuccess() {
        AtomicReference<AccountID> receiverId = new AtomicReference<>();
        return hapiTest(
                cryptoCreate(RECEIVER).exposingCreatedIdTo(receiverId::set),
                uploadInitCode(INTERNAL_CALLER_CONTRACT),
                contractCreate(INTERNAL_CALLER_CONTRACT).balance(ONE_HBAR),
                withOpContext((spec, op) -> allRunFor(
                        spec,
                        balanceSnapshot("initialBalance", asAccountString(receiverId.get())),
                        atomicBatch(contractCall(
                                                INTERNAL_CALLER_CONTRACT,
                                                DELEGATE_CALL_EXTERNAL_FUNCTION,
                                                mirrorAddrWith(
                                                        spec, receiverId.get().getAccountNum()))
                                        .gas(GAS_LIMIT_FOR_CALL)
                                        .via(INNER_TXN)
                                        .batchKey(BATCH_OPERATOR))
                                .payingWith(BATCH_OPERATOR))),
                getTxnRecord(INNER_TXN)
                        .hasPriority(
                                recordWith().contractCallResult(resultWith().contractCallResult(bigIntResult(0)))),
                getAccountBalance(RECEIVER).hasTinyBars(changeFromSnapshot("initialBalance", 0)));
    }

    @HapiTest
    final Stream<DynamicTest> internalDelegateCallNonExistingNonMirrorAddressResultsInSuccess() {
        AtomicReference<Bytes> nonExistingNonMirrorAddress = new AtomicReference<>();
        return hapiTest(
                cryptoCreate(CUSTOM_PAYER).balance(ONE_HUNDRED_HBARS),
                newKeyNamed(ECDSA_KEY).shape(SECP_256K1_SHAPE),
                withOpContext((spec, op) -> {
                    final var ecdsaKey = spec.registry().getKey(ECDSA_KEY);
                    final var tmp = ecdsaKey.getECDSASecp256K1().toByteArray();
                    final var addressBytes = recoverAddressFromPubKey(tmp);
                    nonExistingNonMirrorAddress.set(Bytes.of(addressBytes));
                }),
                uploadInitCode(INTERNAL_CALLER_CONTRACT),
                contractCreate(INTERNAL_CALLER_CONTRACT).balance(ONE_HBAR),
                withOpContext((spec, op) -> allRunFor(
                        spec,
                        balanceSnapshot("contractBalance", INTERNAL_CALLER_CONTRACT),
                        atomicBatch(contractCall(
                                                INTERNAL_CALLER_CONTRACT,
                                                DELEGATE_CALL_EXTERNAL_FUNCTION,
                                                asHeadlongAddress(nonExistingNonMirrorAddress
                                                        .get()
                                                        .toArray()))
                                        .gas(GAS_LIMIT_FOR_CALL)
                                        .payingWith(CUSTOM_PAYER)
                                        .via(INNER_TXN)
                                        .batchKey(BATCH_OPERATOR))
                                .payingWith(BATCH_OPERATOR))),
                getTxnRecord(INNER_TXN)
                        .hasPriority(
                                recordWith().contractCallResult(resultWith().contractCallResult(bigIntResult(0)))),
                getAccountBalance(INTERNAL_CALLER_CONTRACT).hasTinyBars(changeFromSnapshot("contractBalance", 0)));
    }

    @HapiTest
    final Stream<DynamicTest> internalDelegateCallExistingNonMirrorAddressResultsInSuccess() {
        return hapiTest(
                newKeyNamed(ECDSA_KEY).shape(SECP_256K1_SHAPE),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, ECDSA_KEY, ONE_HUNDRED_HBARS)),
                withOpContext((spec, opLog) -> updateSpecFor(spec, ECDSA_KEY)),
                uploadInitCode(INTERNAL_CALLER_CONTRACT),
                contractCreate(INTERNAL_CALLER_CONTRACT).balance(ONE_HBAR),
                withOpContext((spec, op) -> {
                    final var ecdsaKey = spec.registry().getKey(ECDSA_KEY);
                    final var tmp = ecdsaKey.getECDSASecp256K1().toByteArray();
                    final var addressBytes = recoverAddressFromPubKey(tmp);
                    allRunFor(
                            spec,
                            balanceSnapshot("targetSnapshot", ECDSA_KEY).accountIsAlias(),
                            atomicBatch(contractCall(
                                                    INTERNAL_CALLER_CONTRACT,
                                                    DELEGATE_CALL_EXTERNAL_FUNCTION,
                                                    asHeadlongAddress(addressBytes))
                                            .gas(GAS_LIMIT_FOR_CALL)
                                            .via(INNER_TXN)
                                            .batchKey(BATCH_OPERATOR))
                                    .payingWith(BATCH_OPERATOR));
                }),
                getTxnRecord(INNER_TXN)
                        .hasPriority(
                                recordWith().contractCallResult(resultWith().contractCallResult(bigIntResult(0)))),
                getAutoCreatedAccountBalance(ECDSA_KEY).hasTinyBars(changeFromSnapshot("targetSnapshot", 0)));
    }

    @HapiTest
    final Stream<DynamicTest> internalCallWithValueToAccountWithReceiverSigRequiredTrue() {
        AtomicReference<AccountID> receiverId = new AtomicReference<>();

        return hapiTest(
                cryptoCreate(RECEIVER).receiverSigRequired(true).exposingCreatedIdTo(receiverId::set),
                uploadInitCode(INTERNAL_CALLER_CONTRACT),
                contractCreate(INTERNAL_CALLER_CONTRACT).balance(ONE_HBAR),
                withOpContext((spec, op) -> allRunFor(
                        spec,
                        balanceSnapshot("initialBalance", INTERNAL_CALLER_CONTRACT),
                        atomicBatch(contractCall(
                                                INTERNAL_CALLER_CONTRACT,
                                                CALL_WITH_VALUE_TO_FUNCTION,
                                                mirrorAddrWith(
                                                        spec, receiverId.get().getAccountNum()))
                                        .gas(GAS_LIMIT_FOR_CALL * 4)
                                        .via(INNER_TXN)
                                        .hasKnownStatus(INVALID_SIGNATURE)
                                        .batchKey(BATCH_OPERATOR))
                                .payingWith(BATCH_OPERATOR)
                                .hasKnownStatus(INNER_TRANSACTION_FAILED))),
                getTxnRecord(INNER_TXN).hasPriority(recordWith().status(INVALID_SIGNATURE)),
                getAccountBalance(INTERNAL_CALLER_CONTRACT).hasTinyBars(changeFromSnapshot("initialBalance", 0)));
    }

    @HapiTest
    final Stream<DynamicTest> internalCallToSystemAccount564ResultsInSuccessNoop() {
        AtomicReference<AccountID> targetId = new AtomicReference<>();
        targetId.set(AccountID.newBuilder().setAccountNum(564L).build());

        return hapiTest(
                uploadInitCode(INTERNAL_CALLER_CONTRACT),
                contractCreate(INTERNAL_CALLER_CONTRACT).balance(ONE_HBAR),
                balanceSnapshot("initialBalance", INTERNAL_CALLER_CONTRACT),
                withOpContext((spec, op) -> allRunFor(
                        spec,
                        atomicBatch(contractCall(
                                                INTERNAL_CALLER_CONTRACT,
                                                CALL_EXTERNAL_FUNCTION,
                                                mirrorAddrWith(
                                                        spec, targetId.get().getAccountNum()))
                                        .gas(GAS_LIMIT_FOR_CALL * 4)
                                        .via(INNER_TXN)
                                        .batchKey(BATCH_OPERATOR))
                                .payingWith(BATCH_OPERATOR))),
                getTxnRecord(INNER_TXN).hasPriority(recordWith().status(SUCCESS)),
                getAccountBalance(INTERNAL_CALLER_CONTRACT).hasTinyBars(changeFromSnapshot("initialBalance", 0)));
    }

    @HapiTest
    final Stream<DynamicTest> internalCallsAgainstSystemAccountsWithValue() {
        final var withAmount = "makeCallWithAmount";
        return hapiTest(
                uploadInitCode(MAKE_CALLS_CONTRACT),
                contractCreate(MAKE_CALLS_CONTRACT).gas(400_000L),
                balanceSnapshot("initialBalance", MAKE_CALLS_CONTRACT),
                atomicBatch(contractCall(MAKE_CALLS_CONTRACT, withAmount, (spec) -> List.of(
                                                idAsHeadlongAddress(asAccount(spec, 357)),
                                                new byte[] {"system account".getBytes()[0]})
                                        .toArray())
                                .gas(GAS_LIMIT_FOR_CALL * 4)
                                .sending(2L)
                                .via(INNER_TXN)
                                .hasKnownStatus(INVALID_CONTRACT_ID)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                getAccountBalance(MAKE_CALLS_CONTRACT).hasTinyBars(changeFromSnapshot("initialBalance", 0)));
    }

    @HapiTest
    final Stream<DynamicTest> internalCallsAgainstSystemAccountsWithoutValue() {
        final var withoutAmount = "makeCallWithoutAmount";
        return hapiTest(
                uploadInitCode(MAKE_CALLS_CONTRACT),
                contractCreate(MAKE_CALLS_CONTRACT).gas(400_000L),
                balanceSnapshot("initialBalance", MAKE_CALLS_CONTRACT),
                atomicBatch(contractCall(
                                        MAKE_CALLS_CONTRACT,
                                        withoutAmount,
                                        idAsHeadlongAddress(AccountID.newBuilder()
                                                .setAccountNum(357)
                                                .build()),
                                        new byte[] {"system account".getBytes()[0]})
                                .gas(GAS_LIMIT_FOR_CALL * 4)
                                .via(INNER_TXN)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                getTxnRecord(INNER_TXN).hasPriority(recordWith().status(SUCCESS)),
                getAccountBalance(MAKE_CALLS_CONTRACT).hasTinyBars(changeFromSnapshot("initialBalance", 0)));
    }

    @HapiTest
    final Stream<DynamicTest> internalCallToEthereumPrecompile0x2ResultsInSuccess() {
        AtomicReference<AccountID> targetId = new AtomicReference<>();
        targetId.set(AccountID.newBuilder().setAccountNum(2L).build());

        return hapiTest(
                uploadInitCode(INTERNAL_CALLER_CONTRACT),
                contractCreate(INTERNAL_CALLER_CONTRACT).balance(ONE_HBAR),
                balanceSnapshot("initialBalance", INTERNAL_CALLER_CONTRACT),
                withOpContext((spec, op) -> allRunFor(
                        spec,
                        atomicBatch(contractCall(
                                                INTERNAL_CALLER_CONTRACT,
                                                CALL_EXTERNAL_FUNCTION,
                                                nonMirrorAddrWith(
                                                        0, targetId.get().getAccountNum()))
                                        .gas(GAS_LIMIT_FOR_CALL * 4)
                                        .via(INNER_TXN)
                                        .batchKey(BATCH_OPERATOR))
                                .payingWith(BATCH_OPERATOR))),
                withOpContext((spec, opLog) -> {
                    final var lookup = getTxnRecord(INNER_TXN);
                    allRunFor(spec, lookup);
                    final var result =
                            lookup.getResponseRecord().getContractCallResult().getContractCallResult();
                    assertNotEquals(ByteString.copyFrom(new byte[32]), result);
                }),
                getAccountBalance(INTERNAL_CALLER_CONTRACT).hasTinyBars(changeFromSnapshot("initialBalance", 0)));
    }

    @HapiTest
    final Stream<DynamicTest> internalCallWithValueToEthereumPrecompile0x2ResultsInRevert() {
        AtomicReference<AccountID> targetId = new AtomicReference<>();
        targetId.set(AccountID.newBuilder().setAccountNum(2L).build());

        return hapiTest(
                uploadInitCode(INTERNAL_CALLER_CONTRACT),
                contractCreate(INTERNAL_CALLER_CONTRACT).balance(ONE_HBAR),
                balanceSnapshot("initialBalance", INTERNAL_CALLER_CONTRACT),
                withOpContext((spec, op) -> allRunFor(
                        spec,
                        atomicBatch(contractCall(
                                                INTERNAL_CALLER_CONTRACT,
                                                CALL_WITH_VALUE_TO_FUNCTION,
                                                mirrorAddrWith(
                                                        spec, targetId.get().getAccountNum()))
                                        .gas(GAS_LIMIT_FOR_CALL * 4)
                                        .via(INNER_TXN)
                                        .hasKnownStatus(INVALID_CONTRACT_ID)
                                        .batchKey(BATCH_OPERATOR))
                                .payingWith(BATCH_OPERATOR)
                                .hasKnownStatus(INNER_TRANSACTION_FAILED))),
                getAccountBalance(INTERNAL_CALLER_CONTRACT).hasTinyBars(changeFromSnapshot("initialBalance", 0)));
    }

    @HapiTest
    final Stream<DynamicTest> internalCallToNonExistingSystemAccount852ResultsInSuccessNoop() {
        AtomicReference<AccountID> targetId = new AtomicReference<>();
        targetId.set(AccountID.newBuilder().setAccountNum(852L).build());

        return hapiTest(
                uploadInitCode(INTERNAL_CALLER_CONTRACT),
                contractCreate(INTERNAL_CALLER_CONTRACT).balance(ONE_HBAR),
                balanceSnapshot("initialBalance", INTERNAL_CALLER_CONTRACT),
                withOpContext((spec, op) -> allRunFor(
                        spec,
                        atomicBatch(contractCall(
                                                INTERNAL_CALLER_CONTRACT,
                                                CALL_EXTERNAL_FUNCTION,
                                                mirrorAddrWith(
                                                        spec, targetId.get().getAccountNum()))
                                        .gas(GAS_LIMIT_FOR_CALL * 4)
                                        .via(INNER_TXN)
                                        .batchKey(BATCH_OPERATOR))
                                .payingWith(BATCH_OPERATOR))),
                getTxnRecord(INNER_TXN).hasPriority(recordWith().status(SUCCESS)),
                getAccountBalance(INTERNAL_CALLER_CONTRACT).hasTinyBars(changeFromSnapshot("initialBalance", 0)));
    }

    @HapiTest
    final Stream<DynamicTest> internalCallWithValueToNonExistingSystemAccount852ResultsInInvalidAliasKey() {
        AtomicReference<AccountID> targetId = new AtomicReference<>();
        final var systemAccountNum = 852L;
        targetId.set(AccountID.newBuilder().setAccountNum(systemAccountNum).build());

        return hapiTest(
                uploadInitCode(INTERNAL_CALLER_CONTRACT),
                contractCreate(INTERNAL_CALLER_CONTRACT).balance(ONE_HBAR),
                balanceSnapshot("initialBalance", INTERNAL_CALLER_CONTRACT),
                withOpContext((spec, op) -> allRunFor(
                        spec,
                        atomicBatch(contractCall(
                                                INTERNAL_CALLER_CONTRACT,
                                                CALL_WITH_VALUE_TO_FUNCTION,
                                                mirrorAddrWith(
                                                        spec, targetId.get().getAccountNum()))
                                        .gas(GAS_LIMIT_FOR_CALL * 4)
                                        .batchKey(BATCH_OPERATOR))
                                .payingWith(BATCH_OPERATOR))),
                getAccountBalance(INTERNAL_CALLER_CONTRACT).hasTinyBars(changeFromSnapshot("initialBalance", 0)),
                getAccountBalance("0.0." + systemAccountNum).hasAnswerOnlyPrecheck(INVALID_ACCOUNT_ID));
    }

    @HapiTest
    final Stream<DynamicTest> internalCallWithValueToSystemAccount564ResultsInSuccessNoopNoTransfer() {
        AtomicReference<AccountID> targetId = new AtomicReference<>();
        targetId.set(AccountID.newBuilder().setAccountNum(564L).build());

        return hapiTest(
                uploadInitCode(INTERNAL_CALLER_CONTRACT),
                contractCreate(INTERNAL_CALLER_CONTRACT).balance(ONE_HBAR),
                balanceSnapshot("initialBalance", INTERNAL_CALLER_CONTRACT),
                withOpContext((spec, op) -> allRunFor(
                        spec,
                        atomicBatch(contractCall(
                                                INTERNAL_CALLER_CONTRACT,
                                                CALL_WITH_VALUE_TO_FUNCTION,
                                                mirrorAddrWith(
                                                        spec, targetId.get().getAccountNum()))
                                        .gas(GAS_LIMIT_FOR_CALL * 4)
                                        .via(INNER_TXN)
                                        .hasKnownStatus(INVALID_CONTRACT_ID)
                                        .batchKey(BATCH_OPERATOR))
                                .payingWith(BATCH_OPERATOR)
                                .hasKnownStatus(INNER_TRANSACTION_FAILED))),
                getAccountBalance(INTERNAL_CALLER_CONTRACT).hasTinyBars(changeFromSnapshot("initialBalance", 0)));
    }

    @HapiTest
    final Stream<DynamicTest> internalCallWithValueToExistingSystemAccount800ResultsInSuccessfulTransfer() {
        AtomicReference<AccountID> targetId = new AtomicReference<>();
        targetId.set(AccountID.newBuilder().setAccountNum(800L).build());

        return hapiTest(
                uploadInitCode(INTERNAL_CALLER_CONTRACT),
                contractCreate(INTERNAL_CALLER_CONTRACT).balance(ONE_HBAR),
                balanceSnapshot("initialBalance", INTERNAL_CALLER_CONTRACT),
                withOpContext((spec, op) -> allRunFor(
                        spec,
                        atomicBatch(contractCall(
                                                INTERNAL_CALLER_CONTRACT,
                                                CALL_WITH_VALUE_TO_FUNCTION,
                                                mirrorAddrWith(
                                                        spec, targetId.get().getAccountNum()))
                                        .gas(GAS_LIMIT_FOR_CALL * 4)
                                        .via(INNER_TXN)
                                        .batchKey(BATCH_OPERATOR))
                                .payingWith(BATCH_OPERATOR))),
                getTxnRecord(INNER_TXN).hasPriority(recordWith().status(SUCCESS)),
                getAccountBalance(INTERNAL_CALLER_CONTRACT).hasTinyBars(changeFromSnapshot("initialBalance", -1)));
    }

    @HapiTest
    final Stream<DynamicTest> internalCallToExistingSystemAccount800ResultsInSuccessNoop() {
        AtomicReference<AccountID> targetId = new AtomicReference<>();
        targetId.set(AccountID.newBuilder().setAccountNum(800L).build());

        return hapiTest(
                uploadInitCode(INTERNAL_CALLER_CONTRACT),
                contractCreate(INTERNAL_CALLER_CONTRACT).balance(ONE_HBAR),
                balanceSnapshot("initialBalance", INTERNAL_CALLER_CONTRACT),
                withOpContext((spec, op) -> allRunFor(
                        spec,
                        atomicBatch(contractCall(
                                                INTERNAL_CALLER_CONTRACT,
                                                CALL_EXTERNAL_FUNCTION,
                                                mirrorAddrWith(
                                                        spec, targetId.get().getAccountNum()))
                                        .gas(GAS_LIMIT_FOR_CALL * 4)
                                        .via(INNER_TXN)
                                        .batchKey(BATCH_OPERATOR))
                                .payingWith(BATCH_OPERATOR))),
                getTxnRecord(INNER_TXN).hasPriority(recordWith().status(SUCCESS)),
                getAccountBalance(INTERNAL_CALLER_CONTRACT).hasTinyBars(changeFromSnapshot("initialBalance", 0)));
    }

    @HapiTest
    final Stream<DynamicTest> directCallToSystemAccountResultsInSuccessfulNoOp() {
        return hapiTest(
                cryptoCreate("account").balance(ONE_HUNDRED_HBARS),
                withOpContext((spec, opLog) -> spec.registry()
                        .saveContractId("contract", spec, ByteString.copyFrom(asSolidityAddress(spec, 629)))),
                withOpContext((spec, ctxLog) -> allRunFor(
                        spec,
                        atomicBatch(contractCallWithFunctionAbi("contract", getABIFor(FUNCTION, NAME, ERC_721_ABI))
                                        .gas(GAS_LIMIT_FOR_CALL)
                                        .via("callToSystemAddress")
                                        .signingWith("account")
                                        .batchKey(BATCH_OPERATOR))
                                .payingWith(BATCH_OPERATOR))),
                getTxnRecord("callToSystemAddress")
                        .hasPriority(recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith().gasUsed(GAS_LIMIT_FOR_CALL))));
    }

    @HapiTest
    final Stream<DynamicTest> testCallOperationsForSystemAccounts() {
        final var contract = "CallOperationsCheckerSuccess";
        final var functionName = "call";
        final HapiSpecOperation[] opsArray = getCallOperationsOnSystemAccounts(contract, functionName);
        return hapiTest(flattened(uploadInitCode(contract), contractCreate(contract), opsArray));
    }

    @HapiTest
    final Stream<DynamicTest> testCallCodeOperationsForSystemAccounts() {
        final var contract = "CallOperationsCheckerSuccess";
        final var functionName = "callCode";
        final HapiSpecOperation[] opsArray = getCallOperationsOnSystemAccounts(contract, functionName);
        return hapiTest(flattened(uploadInitCode(contract), contractCreate(contract), opsArray));
    }

    @HapiTest
    final Stream<DynamicTest> testDelegateCallOperationsForSystemAccounts() {
        final var contract = "CallOperationsCheckerSuccess";
        final var functionName = "delegateCall";
        final HapiSpecOperation[] opsArray = getCallOperationsOnSystemAccounts(contract, functionName);
        return hapiTest(flattened(uploadInitCode(contract), contractCreate(contract), opsArray));
    }

    @HapiTest
    final Stream<DynamicTest> testStaticCallOperationsForSystemAccounts() {
        final var contract = "CallOperationsCheckerSuccess";
        final var functionName = "staticcall";
        final HapiSpecOperation[] opsArray = getCallOperationsOnSystemAccounts(contract, functionName);
        return hapiTest(flattened(uploadInitCode(contract), contractCreate(contract), opsArray));
    }

    private HapiSpecOperation[] getCallOperationsOnSystemAccounts(final String contract, final String functionName) {
        final HapiSpecOperation[] opsArray = new HapiSpecOperation[callOperationsSuccessSystemAccounts.size()];
        for (int i = 0; i < callOperationsSuccessSystemAccounts.size(); i++) {
            int finalI = i;
            opsArray[i] = withOpContext((spec, opLog) -> allRunFor(
                    spec,
                    atomicBatch(contractCall(
                                            contract,
                                            functionName,
                                            mirrorAddrWith(spec, callOperationsSuccessSystemAccounts.get(finalI)))
                                    .hasKnownStatus(SUCCESS)
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR)));
        }
        return opsArray;
    }
}
