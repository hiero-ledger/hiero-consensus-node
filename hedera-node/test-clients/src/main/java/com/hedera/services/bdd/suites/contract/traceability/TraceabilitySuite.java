// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.traceability;

import static com.hedera.node.app.hapi.utils.EthSigsUtils.recoverAddressFromPubKey;
import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.junit.hedera.NodeSelector.byNodeId;
import static com.hedera.services.bdd.spec.HapiPropertySource.asContract;
import static com.hedera.services.bdd.spec.HapiPropertySource.asEntityString;
import static com.hedera.services.bdd.spec.HapiSpec.defaultHapiSpec;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.accountWith;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.contractCallLocal;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAliasedAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getContractBytecode;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCustomCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.ethereumContractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.ethereumCryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.contract.HapiParserUtil.asHeadlongAddress;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromAccountToAlias;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingUnique;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.SidecarVerbs.GLOBAL_WATCHER;
import static com.hedera.services.bdd.spec.utilops.SidecarVerbs.expectContractActionSidecarFor;
import static com.hedera.services.bdd.spec.utilops.SidecarVerbs.expectContractBytecodeSansInitcodeFor;
import static com.hedera.services.bdd.spec.utilops.SidecarVerbs.expectContractBytecodeSidecarFor;
import static com.hedera.services.bdd.spec.utilops.SidecarVerbs.expectContractStateChangesSidecarFor;
import static com.hedera.services.bdd.spec.utilops.SidecarVerbs.expectExplicitContractBytecode;
import static com.hedera.services.bdd.spec.utilops.SidecarVerbs.expectFailedContractBytecodeSidecarFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.EMPTY_KEY;
import static com.hedera.services.bdd.suites.HapiSuite.FIVE_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.MAX_UINT256_VALUE;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.RELAYER;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SHAPE;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SOURCE_KEY;
import static com.hedera.services.bdd.suites.HapiSuite.TOKEN_TREASURY;
import static com.hedera.services.bdd.suites.contract.Utils.aaWith;
import static com.hedera.services.bdd.suites.contract.Utils.asAddress;
import static com.hedera.services.bdd.suites.contract.Utils.asHexedSolidityAddress;
import static com.hedera.services.bdd.suites.contract.Utils.asToken;
import static com.hedera.services.bdd.suites.contract.Utils.captureOneChildCreate2MetaFor;
import static com.hedera.services.bdd.suites.contract.Utils.extractBytecodeUnhexed;
import static com.hedera.services.bdd.suites.contract.Utils.getNestedContractAddress;
import static com.hedera.services.bdd.suites.contract.Utils.getResourcePath;
import static com.hedera.services.bdd.suites.contract.opcodes.Create2OperationSuite.CONTRACT_REPORTED_ADDRESS_MESSAGE;
import static com.hedera.services.bdd.suites.contract.opcodes.Create2OperationSuite.CONTRACT_REPORTED_LOG_MESSAGE;
import static com.hedera.services.bdd.suites.contract.opcodes.Create2OperationSuite.DEPLOY;
import static com.hedera.services.bdd.suites.contract.opcodes.Create2OperationSuite.EXPECTED_CREATE2_ADDRESS_MESSAGE;
import static com.hedera.services.bdd.suites.contract.opcodes.Create2OperationSuite.GET_ADDRESS;
import static com.hedera.services.bdd.suites.contract.opcodes.Create2OperationSuite.GET_BYTECODE;
import static com.hedera.services.bdd.suites.contract.traceability.EncodingUtils.encodeFunctionCall;
import static com.hedera.services.bdd.suites.contract.traceability.EncodingUtils.encodeTuple;
import static com.hedera.services.bdd.suites.contract.traceability.EncodingUtils.formattedAssertionValue;
import static com.hedera.services.bdd.suites.contract.traceability.EncodingUtils.hexedSolidityAddressToHeadlongAddress;
import static com.hedera.services.bdd.suites.contract.traceability.EncodingUtils.uint256ReturnWithValue;
import static com.hedera.services.bdd.suites.crypto.AutoAccountCreationSuite.PARTY;
import static com.hedera.services.bdd.suites.token.TokenAssociationSpecs.MULTI_KEY;
import static com.hedera.services.stream.proto.ContractActionType.CALL;
import static com.hedera.services.stream.proto.ContractActionType.CREATE;
import static com.hedera.services.stream.proto.ContractActionType.PRECOMPILE;
import static com.hedera.services.stream.proto.ContractActionType.SYSTEM;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_GAS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SOLIDITY_ADDRESS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;
import static java.util.Objects.requireNonNull;
import static org.hiero.base.utility.CommonUtils.hex;
import static org.hyperledger.besu.crypto.Hash.keccak256;

import com.esaulpaugh.headlong.abi.Address;
import com.esaulpaugh.headlong.abi.Function;
import com.google.common.hash.Hashing;
import com.google.protobuf.ByteString;
import com.hedera.node.app.hapi.utils.ByteStringUtils;
import com.hedera.node.app.hapi.utils.ethereum.EthTxData;
import com.hedera.node.app.hapi.utils.ethereum.EthTxData.EthTransactionType;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.junit.OrderedInIsolation;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.HapiPropertySource;
import com.hedera.services.bdd.spec.assertions.StateChange;
import com.hedera.services.bdd.spec.assertions.StorageChange;
import com.hedera.services.bdd.spec.queries.meta.HapiGetTxnRecord;
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import com.hedera.services.bdd.spec.transactions.TxnVerbs;
import com.hedera.services.bdd.spec.transactions.contract.HapiParserUtil;
import com.hedera.services.bdd.spec.verification.traceability.SidecarWatcher;
import com.hedera.services.bdd.suites.contract.Utils;
import com.hedera.services.stream.proto.CallOperationType;
import com.hedera.services.stream.proto.ContractAction;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.ContractID;
import com.hederahashgraph.api.proto.java.TokenID;
import com.hederahashgraph.api.proto.java.TokenType;
import com.hederahashgraph.api.proto.java.TransferList;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;

@HapiTestLifecycle
@OrderedInIsolation
@Tag(SMART_CONTRACT)
public class TraceabilitySuite {

    private static final Logger log = LogManager.getLogger(TraceabilitySuite.class);

    private static final ByteString EMPTY = ByteStringUtils.wrapUnsafely(new byte[0]);
    private static final ByteString CALL_CODE_INPUT_SUFFIX = ByteStringUtils.wrapUnsafely(new byte[28]);
    private static final String TRACEABILITY = "Traceability";
    private static final String TRACEABILITY_CALLCODE = "TraceabilityCallcode";
    private static final String REVERTING_CONTRACT = "RevertingContract";
    private static final String PAY_RECEIVABLE_CONTRACT = "PayReceivable";
    private static final String FIRST_CREATE_TXN = "FirstCreateTxn";
    private static final String SECOND_CREATE_TXN = "SecondCreateTxn";
    private static final String THIRD_CREATE_TXN = "ThirdCreateTxn";
    private static final String SECOND = "B";
    private static final String THIRD = "C";
    private static final String TRACEABILITY_TXN = "nestedtxn";
    private static final String GET_ZERO_SLOT = "getSlot0";
    private static final String GET_FIRST_SLOT = "getSlot1";
    private static final String GET_SECOND_SLOT = "getSlot2";
    private static final String SET_ZERO_SLOT = "setSlot0";
    private static final String SET_FIRST_SLOT = "setSlot1";
    private static final String SET_SECOND_SLOT = "setSlot2";
    private static final String DELEGATE_CALL_ADDRESS_GET_SLOT_2 = "delegateCallAddressGetSlot2";
    private static final String AUTO_ACCOUNT_TXN = "autoAccount";
    public static final String SIDECARS_PROP = "contracts.sidecars";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.doAdhoc(
                withOpContext((spec, opLog) -> GLOBAL_WATCHER.set(new SidecarWatcher(spec.streamsLoc(byNodeId(0))))),
                overriding("contracts.enforceCreationThrottle", "false"));
    }

    @HapiTest
    @Order(1)
    final Stream<DynamicTest> traceabilityE2EScenario1() {
        return hapiTest(
                uploadInitCode(TRACEABILITY),
                contractCreate(TRACEABILITY, BigInteger.valueOf(55), BigInteger.TWO, BigInteger.TWO)
                        .gas(2_500_000L)
                        .via(FIRST_CREATE_TXN),
                expectContractStateChangesSidecarFor(
                        FIRST_CREATE_TXN,
                        List.of(StateChange.stateChangeFor(TRACEABILITY)
                                .withStorageChanges(
                                        StorageChange.readAndWritten(
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(55)),
                                        StorageChange.readAndWritten(
                                                formattedAssertionValue(1),
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(2)),
                                        StorageChange.readAndWritten(
                                                formattedAssertionValue(2),
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(2))))),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        expectContractActionSidecarFor(
                                FIRST_CREATE_TXN,
                                List.of(ContractAction.newBuilder()
                                        .setCallType(CREATE)
                                        .setCallOperationType(CallOperationType.OP_CREATE)
                                        .setCallingAccount(TxnUtils.asId(GENESIS, spec))
                                        .setGas(2298224)
                                        .setRecipientContract(spec.registry().getContractId(TRACEABILITY))
                                        .setGasUsed(68492)
                                        .setOutput(EMPTY)
                                        .build())))),
                expectContractBytecodeSidecarFor(
                        FIRST_CREATE_TXN,
                        TRACEABILITY,
                        TRACEABILITY,
                        BigInteger.valueOf(55),
                        BigInteger.TWO,
                        BigInteger.TWO),
                contractCustomCreate(TRACEABILITY, SECOND, BigInteger.ZERO, BigInteger.ZERO, BigInteger.valueOf(12))
                        .via(SECOND_CREATE_TXN),
                expectContractStateChangesSidecarFor(
                        SECOND_CREATE_TXN,
                        List.of(StateChange.stateChangeFor(TRACEABILITY + SECOND)
                                .withStorageChanges(
                                        StorageChange.readAndWritten(
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(0)),
                                        StorageChange.readAndWritten(
                                                formattedAssertionValue(1),
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(0)),
                                        StorageChange.readAndWritten(
                                                formattedAssertionValue(2),
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(12))))),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        expectContractActionSidecarFor(
                                SECOND_CREATE_TXN,
                                List.of(ContractAction.newBuilder()
                                        .setCallType(CREATE)
                                        .setCallOperationType(CallOperationType.OP_CREATE)
                                        .setCallingAccount(TxnUtils.asId(GENESIS, spec))
                                        .setGas(2798248)
                                        .setRecipientContract(spec.registry().getContractId(TRACEABILITY + SECOND))
                                        .setGasUsed(28692)
                                        .setOutput(EMPTY)
                                        .build())))),
                expectContractBytecodeSidecarFor(
                        SECOND_CREATE_TXN,
                        TRACEABILITY + SECOND,
                        TRACEABILITY,
                        BigInteger.ZERO,
                        BigInteger.ZERO,
                        BigInteger.valueOf(12)),
                contractCustomCreate(TRACEABILITY, THIRD, BigInteger.ZERO, BigInteger.valueOf(11), BigInteger.ZERO)
                        .via(THIRD_CREATE_TXN),
                expectContractStateChangesSidecarFor(
                        THIRD_CREATE_TXN,
                        List.of(StateChange.stateChangeFor(TRACEABILITY + THIRD)
                                .withStorageChanges(
                                        StorageChange.readAndWritten(
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(0)),
                                        StorageChange.readAndWritten(
                                                formattedAssertionValue(1),
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(11)),
                                        StorageChange.readAndWritten(
                                                formattedAssertionValue(2),
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(0))))),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        expectContractActionSidecarFor(
                                THIRD_CREATE_TXN,
                                List.of(ContractAction.newBuilder()
                                        .setCallType(CREATE)
                                        .setCallOperationType(CallOperationType.OP_CREATE)
                                        .setCallingAccount(TxnUtils.asId(GENESIS, spec))
                                        .setGas(2798248)
                                        .setRecipientContract(spec.registry().getContractId(TRACEABILITY + THIRD))
                                        .setGasUsed(28692)
                                        .setOutput(EMPTY)
                                        .build())))),
                expectContractBytecodeSidecarFor(
                        THIRD_CREATE_TXN,
                        TRACEABILITY + THIRD,
                        TRACEABILITY,
                        BigInteger.ZERO,
                        BigInteger.valueOf(11),
                        BigInteger.ZERO),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCall(
                                        TRACEABILITY,
                                        "eetScenario1",
                                        asHeadlongAddress(getNestedContractAddress(TRACEABILITY + "B", spec)),
                                        asHeadlongAddress(getNestedContractAddress(TRACEABILITY + "C", spec)))
                                .gas(1_000_000)
                                .via(TRACEABILITY_TXN))),
                expectContractStateChangesSidecarFor(
                        TRACEABILITY_TXN,
                        List.of(
                                StateChange.stateChangeFor(TRACEABILITY)
                                        .withStorageChanges(
                                                StorageChange.onlyRead(
                                                        formattedAssertionValue(0), formattedAssertionValue(55)),
                                                StorageChange.readAndWritten(
                                                        formattedAssertionValue(1),
                                                        formattedAssertionValue(2),
                                                        formattedAssertionValue(55))),
                                StateChange.stateChangeFor(TRACEABILITY + SECOND)
                                        .withStorageChanges(StorageChange.readAndWritten(
                                                formattedAssertionValue(2),
                                                formattedAssertionValue(12),
                                                formattedAssertionValue(143))),
                                StateChange.stateChangeFor(TRACEABILITY + THIRD)
                                        .withStorageChanges(
                                                StorageChange.readAndWritten(
                                                        formattedAssertionValue(0),
                                                        formattedAssertionValue(0),
                                                        formattedAssertionValue(0)),
                                                StorageChange.readAndWritten(
                                                        formattedAssertionValue(1),
                                                        formattedAssertionValue(11),
                                                        formattedAssertionValue(0))))),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        expectContractActionSidecarFor(
                                TRACEABILITY_TXN,
                                List.of(
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallingAccount(TxnUtils.asId(GENESIS, spec))
                                                .setCallOperationType(CallOperationType.OP_CALL)
                                                .setGas(978632)
                                                .setGasUsed(33979)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY))
                                                .setOutput(EMPTY)
                                                .setInput(encodeFunctionCall(
                                                        TRACEABILITY,
                                                        "eetScenario1",
                                                        hexedSolidityAddressToHeadlongAddress(
                                                                getNestedContractAddress(TRACEABILITY + "B", spec)),
                                                        hexedSolidityAddressToHeadlongAddress(
                                                                getNestedContractAddress(TRACEABILITY + "C", spec))))
                                                .build(),
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallOperationType(CallOperationType.OP_CALL)
                                                .setCallingContract(
                                                        spec.registry().getContractId(TRACEABILITY))
                                                .setGas(962655)
                                                .setGasUsed(2347)
                                                .setCallDepth(1)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY))
                                                .setOutput(uint256ReturnWithValue(BigInteger.valueOf(55)))
                                                .setInput(encodeFunctionCall(TRACEABILITY, GET_ZERO_SLOT))
                                                .build(),
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallOperationType(CallOperationType.OP_CALL)
                                                .setCallingContract(
                                                        spec.registry().getContractId(TRACEABILITY))
                                                .setCallDepth(1)
                                                .setGas(959873)
                                                .setGasUsed(5324)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY))
                                                .setOutput(EMPTY)
                                                .setInput(encodeFunctionCall(
                                                        TRACEABILITY, SET_FIRST_SLOT, BigInteger.valueOf(55)))
                                                .build(),
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallOperationType(CallOperationType.OP_CALL)
                                                .setCallingContract(
                                                        spec.registry().getContractId(TRACEABILITY))
                                                .setGas(951947)
                                                .setGasUsed(2315)
                                                .setCallDepth(1)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY + SECOND))
                                                .setOutput(uint256ReturnWithValue(BigInteger.valueOf(12)))
                                                .setInput(encodeFunctionCall(TRACEABILITY, GET_SECOND_SLOT))
                                                .build(),
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallOperationType(CallOperationType.OP_CALL)
                                                .setCallingContract(
                                                        spec.registry().getContractId(TRACEABILITY))
                                                .setGas(949181)
                                                .setGasUsed(3180)
                                                .setCallDepth(1)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY + SECOND))
                                                .setOutput(EMPTY)
                                                .setInput(encodeFunctionCall(
                                                        TRACEABILITY, SET_SECOND_SLOT, BigInteger.valueOf(143)))
                                                .build(),
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallOperationType(CallOperationType.OP_CALL)
                                                .setCallingContract(
                                                        spec.registry().getContractId(TRACEABILITY))
                                                .setGas(945691)
                                                .setGasUsed(5778)
                                                .setCallDepth(1)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY + SECOND))
                                                .setOutput(EMPTY)
                                                .setInput(encodeFunctionCall(
                                                        TRACEABILITY,
                                                        "callAddressGetSlot0",
                                                        hexedSolidityAddressToHeadlongAddress(
                                                                getNestedContractAddress(TRACEABILITY + THIRD, spec))))
                                                .build(),
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallOperationType(CallOperationType.OP_CALL)
                                                .setCallingContract(
                                                        spec.registry().getContractId(TRACEABILITY + SECOND))
                                                .setGas(927670)
                                                .setGasUsed(2347)
                                                .setCallDepth(2)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY + THIRD))
                                                .setOutput(uint256ReturnWithValue(BigInteger.valueOf(0)))
                                                .setInput(encodeFunctionCall(TRACEABILITY, GET_ZERO_SLOT))
                                                .build(),
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallOperationType(CallOperationType.OP_CALL)
                                                .setCallingContract(
                                                        spec.registry().getContractId(TRACEABILITY))
                                                .setGas(939625)
                                                .setGasUsed(1501)
                                                .setCallDepth(1)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY + SECOND))
                                                .setOutput(EMPTY)
                                                .setInput(encodeFunctionCall(
                                                        TRACEABILITY,
                                                        "callAddressSetSlot0",
                                                        hexedSolidityAddressToHeadlongAddress(
                                                                getNestedContractAddress(TRACEABILITY + THIRD, spec)),
                                                        BigInteger.valueOf(0)))
                                                .build(),
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallOperationType(CallOperationType.OP_CALL)
                                                .setCallingContract(
                                                        spec.registry().getContractId(TRACEABILITY + SECOND))
                                                .setGas(923945)
                                                .setGasUsed(423)
                                                .setCallDepth(2)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY + THIRD))
                                                .setOutput(EMPTY)
                                                .setInput(encodeFunctionCall(
                                                        TRACEABILITY, SET_ZERO_SLOT, BigInteger.valueOf(0)))
                                                .build(),
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallOperationType(CallOperationType.OP_CALL)
                                                .setCallingContract(
                                                        spec.registry().getContractId(TRACEABILITY))
                                                .setGas(937787)
                                                .setGasUsed(3345)
                                                .setCallDepth(1)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY + SECOND))
                                                .setOutput(EMPTY)
                                                .setInput(encodeFunctionCall(
                                                        TRACEABILITY,
                                                        "callAddressGetSlot1",
                                                        hexedSolidityAddressToHeadlongAddress(
                                                                getNestedContractAddress(TRACEABILITY + THIRD, spec))))
                                                .build(),
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallOperationType(CallOperationType.OP_CALL)
                                                .setCallingContract(
                                                        spec.registry().getContractId(TRACEABILITY + SECOND))
                                                .setGas(922327)
                                                .setGasUsed(2391)
                                                .setCallDepth(2)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY + THIRD))
                                                .setOutput(uint256ReturnWithValue(BigInteger.valueOf(11)))
                                                .setInput(encodeFunctionCall(TRACEABILITY, GET_FIRST_SLOT))
                                                .build(),
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallOperationType(CallOperationType.OP_CALL)
                                                .setCallingContract(
                                                        spec.registry().getContractId(TRACEABILITY))
                                                .setGas(934107)
                                                .setGasUsed(4235)
                                                .setCallDepth(1)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY + SECOND))
                                                .setOutput(EMPTY)
                                                .setInput(encodeFunctionCall(
                                                        TRACEABILITY,
                                                        "callAddressSetSlot1",
                                                        hexedSolidityAddressToHeadlongAddress(
                                                                getNestedContractAddress(TRACEABILITY + THIRD, spec)),
                                                        BigInteger.valueOf(0)))
                                                .build(),
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallOperationType(CallOperationType.OP_CALL)
                                                .setCallingContract(
                                                        spec.registry().getContractId(TRACEABILITY + SECOND))
                                                .setGas(918579)
                                                .setGasUsed(3224)
                                                .setCallDepth(2)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY + THIRD))
                                                .setOutput(EMPTY)
                                                .setInput(encodeFunctionCall(
                                                        TRACEABILITY, SET_FIRST_SLOT, BigInteger.valueOf(0)))
                                                .build())))));
    }

    @HapiTest
    @Order(2)
    final Stream<DynamicTest> traceabilityE2EScenario2() {
        return hapiTest(
                uploadInitCode(TRACEABILITY),
                contractCreate(TRACEABILITY, BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO)
                        .via(FIRST_CREATE_TXN),
                expectContractStateChangesSidecarFor(
                        FIRST_CREATE_TXN,
                        List.of(StateChange.stateChangeFor(TRACEABILITY)
                                .withStorageChanges(
                                        StorageChange.readAndWritten(
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(0)),
                                        StorageChange.readAndWritten(
                                                formattedAssertionValue(1),
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(0)),
                                        StorageChange.readAndWritten(
                                                formattedAssertionValue(2),
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(0))))),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        expectContractActionSidecarFor(
                                FIRST_CREATE_TXN,
                                List.of(ContractAction.newBuilder()
                                        .setCallType(CREATE)
                                        .setCallOperationType(CallOperationType.OP_CREATE)
                                        .setCallingAccount(TxnUtils.asId(GENESIS, spec))
                                        .setGas(2798260)
                                        .setRecipientContract(spec.registry().getContractId(TRACEABILITY))
                                        .setGasUsed(8792)
                                        .setOutput(EMPTY)
                                        .build())))),
                expectContractBytecodeSidecarFor(
                        FIRST_CREATE_TXN,
                        TRACEABILITY,
                        TRACEABILITY,
                        BigInteger.ZERO,
                        BigInteger.ZERO,
                        BigInteger.ZERO),
                contractCustomCreate(TRACEABILITY, SECOND, BigInteger.ZERO, BigInteger.ZERO, BigInteger.valueOf(99))
                        .via(SECOND_CREATE_TXN),
                expectContractStateChangesSidecarFor(
                        SECOND_CREATE_TXN,
                        List.of(StateChange.stateChangeFor(TRACEABILITY + SECOND)
                                .withStorageChanges(
                                        StorageChange.readAndWritten(
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(0)),
                                        StorageChange.readAndWritten(
                                                formattedAssertionValue(1),
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(0)),
                                        StorageChange.readAndWritten(
                                                formattedAssertionValue(2),
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(99))))),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        expectContractActionSidecarFor(
                                SECOND_CREATE_TXN,
                                List.of(ContractAction.newBuilder()
                                        .setCallType(CREATE)
                                        .setCallOperationType(CallOperationType.OP_CREATE)
                                        .setCallingAccount(TxnUtils.asId(GENESIS, spec))
                                        .setGas(2798248)
                                        .setRecipientContract(spec.registry().getContractId(TRACEABILITY + SECOND))
                                        .setGasUsed(28692)
                                        .setOutput(EMPTY)
                                        .build())))),
                expectContractBytecodeSidecarFor(
                        SECOND_CREATE_TXN,
                        TRACEABILITY + SECOND,
                        TRACEABILITY,
                        BigInteger.ZERO,
                        BigInteger.ZERO,
                        BigInteger.valueOf(99)),
                contractCustomCreate(TRACEABILITY, THIRD, BigInteger.ZERO, BigInteger.valueOf(88), BigInteger.ZERO)
                        .via(THIRD_CREATE_TXN),
                expectContractStateChangesSidecarFor(
                        THIRD_CREATE_TXN,
                        List.of(StateChange.stateChangeFor(TRACEABILITY + THIRD)
                                .withStorageChanges(
                                        StorageChange.readAndWritten(
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(0)),
                                        StorageChange.readAndWritten(
                                                formattedAssertionValue(1),
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(88)),
                                        StorageChange.readAndWritten(
                                                formattedAssertionValue(2),
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(0))))),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        expectContractActionSidecarFor(
                                THIRD_CREATE_TXN,
                                List.of(ContractAction.newBuilder()
                                        .setCallType(CREATE)
                                        .setCallOperationType(CallOperationType.OP_CREATE)
                                        .setCallingAccount(TxnUtils.asId(GENESIS, spec))
                                        .setGas(2798248)
                                        .setRecipientContract(spec.registry().getContractId(TRACEABILITY + THIRD))
                                        .setGasUsed(28692)
                                        .setOutput(EMPTY)
                                        .build())))),
                expectContractBytecodeSidecarFor(
                        THIRD_CREATE_TXN,
                        TRACEABILITY + THIRD,
                        TRACEABILITY,
                        BigInteger.ZERO,
                        BigInteger.valueOf(88),
                        BigInteger.ZERO),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCall(
                                        TRACEABILITY,
                                        "eetScenario2",
                                        asHeadlongAddress(getNestedContractAddress(TRACEABILITY + "B", spec)),
                                        asHeadlongAddress(getNestedContractAddress(TRACEABILITY + "C", spec)))
                                .gas(1_000_000)
                                .via(TRACEABILITY_TXN))),
                expectContractStateChangesSidecarFor(
                        TRACEABILITY_TXN,
                        List.of(
                                StateChange.stateChangeFor(TRACEABILITY)
                                        .withStorageChanges(
                                                StorageChange.onlyRead(
                                                        formattedAssertionValue(0), formattedAssertionValue(0)),
                                                StorageChange.readAndWritten(
                                                        formattedAssertionValue(1),
                                                        formattedAssertionValue(0),
                                                        formattedAssertionValue(55))),
                                StateChange.stateChangeFor(TRACEABILITY + SECOND)
                                        .withStorageChanges(
                                                StorageChange.readAndWritten(
                                                        formattedAssertionValue(0),
                                                        formattedAssertionValue(0),
                                                        formattedAssertionValue(100)),
                                                StorageChange.readAndWritten(
                                                        formattedAssertionValue(1),
                                                        formattedAssertionValue(0),
                                                        formattedAssertionValue(0)),
                                                StorageChange.readAndWritten(
                                                        formattedAssertionValue(2),
                                                        formattedAssertionValue(99),
                                                        formattedAssertionValue(143))))),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        expectContractActionSidecarFor(
                                TRACEABILITY_TXN,
                                List.of(
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallingAccount(TxnUtils.asId(GENESIS, spec))
                                                .setCallOperationType(CallOperationType.OP_CALL)
                                                .setGas(978632)
                                                .setGasUsed(70255)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY))
                                                .setOutput(EMPTY)
                                                .setInput(encodeFunctionCall(
                                                        TRACEABILITY,
                                                        "eetScenario2",
                                                        hexedSolidityAddressToHeadlongAddress(
                                                                getNestedContractAddress(TRACEABILITY + "B", spec)),
                                                        hexedSolidityAddressToHeadlongAddress(
                                                                getNestedContractAddress(TRACEABILITY + "C", spec))))
                                                .build(),
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallOperationType(CallOperationType.OP_CALL)
                                                .setCallingContract(
                                                        spec.registry().getContractId(TRACEABILITY))
                                                .setGas(962721)
                                                .setGasUsed(2347)
                                                .setCallDepth(1)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY))
                                                .setOutput(uint256ReturnWithValue(BigInteger.valueOf(0)))
                                                .setInput(encodeFunctionCall(TRACEABILITY, GET_ZERO_SLOT))
                                                .build(),
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallOperationType(CallOperationType.OP_CALL)
                                                .setCallingContract(
                                                        spec.registry().getContractId(TRACEABILITY))
                                                .setCallDepth(1)
                                                .setGas(959939)
                                                .setGasUsed(22424)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY))
                                                .setOutput(EMPTY)
                                                .setInput(encodeFunctionCall(
                                                        TRACEABILITY, SET_FIRST_SLOT, BigInteger.valueOf(55)))
                                                .build(),
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallOperationType(CallOperationType.OP_CALL)
                                                .setCallingContract(
                                                        spec.registry().getContractId(TRACEABILITY))
                                                .setGas(937512)
                                                .setGasUsed(5811)
                                                .setCallDepth(1)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY))
                                                .setOutput(EMPTY)
                                                .setInput(encodeFunctionCall(
                                                        TRACEABILITY,
                                                        "callAddressGetSlot2",
                                                        hexedSolidityAddressToHeadlongAddress(
                                                                getNestedContractAddress(TRACEABILITY + SECOND, spec))))
                                                .build(),
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallOperationType(CallOperationType.OP_CALL)
                                                .setCallingContract(
                                                        spec.registry().getContractId(TRACEABILITY))
                                                .setGas(919554)
                                                .setGasUsed(2315)
                                                .setCallDepth(2)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY + SECOND))
                                                .setOutput(uint256ReturnWithValue(BigInteger.valueOf(99)))
                                                .setInput(encodeFunctionCall(TRACEABILITY, GET_SECOND_SLOT))
                                                .build(),
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallOperationType(CallOperationType.OP_CALL)
                                                .setCallingContract(
                                                        spec.registry().getContractId(TRACEABILITY))
                                                .setGas(931421)
                                                .setGasUsed(4235)
                                                .setCallDepth(1)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY))
                                                .setOutput(EMPTY)
                                                .setInput(encodeFunctionCall(
                                                        TRACEABILITY,
                                                        "callAddressSetSlot2",
                                                        hexedSolidityAddressToHeadlongAddress(
                                                                getNestedContractAddress(TRACEABILITY + SECOND, spec)),
                                                        BigInteger.valueOf(143)))
                                                .build(),
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallOperationType(CallOperationType.OP_CALL)
                                                .setCallingContract(
                                                        spec.registry().getContractId(TRACEABILITY))
                                                .setGas(915892)
                                                .setGasUsed(3180)
                                                .setCallDepth(2)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY + SECOND))
                                                .setOutput(EMPTY)
                                                .setInput(encodeFunctionCall(
                                                        TRACEABILITY, SET_SECOND_SLOT, BigInteger.valueOf(143)))
                                                .build(),
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallOperationType(CallOperationType.OP_CALL)
                                                .setCallingContract(
                                                        spec.registry().getContractId(TRACEABILITY))
                                                .setGas(926886)
                                                .setGasUsed(5819)
                                                .setCallDepth(1)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY + SECOND))
                                                .setOutput(EMPTY)
                                                .setInput(encodeFunctionCall(
                                                        TRACEABILITY,
                                                        "delegateCallAddressGetSlot0",
                                                        hexedSolidityAddressToHeadlongAddress(
                                                                getNestedContractAddress(TRACEABILITY + THIRD, spec))))
                                                .build(),
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallOperationType(CallOperationType.OP_DELEGATECALL)
                                                .setCallingContract(
                                                        spec.registry().getContractId(TRACEABILITY + SECOND))
                                                .setGas(909118)
                                                .setGasUsed(2347)
                                                .setCallDepth(2)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY + THIRD))
                                                .setOutput(uint256ReturnWithValue(BigInteger.valueOf(0)))
                                                .setInput(encodeFunctionCall(TRACEABILITY, GET_ZERO_SLOT))
                                                .build(),
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallOperationType(CallOperationType.OP_CALL)
                                                .setCallingContract(
                                                        spec.registry().getContractId(TRACEABILITY))
                                                .setGas(920783)
                                                .setGasUsed(21353)
                                                .setCallDepth(1)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY + SECOND))
                                                .setOutput(EMPTY)
                                                .setInput(encodeFunctionCall(
                                                        TRACEABILITY,
                                                        "delegateCallAddressSetSlot0",
                                                        hexedSolidityAddressToHeadlongAddress(
                                                                getNestedContractAddress(TRACEABILITY + THIRD, spec)),
                                                        BigInteger.valueOf(100)))
                                                .build(),
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallOperationType(CallOperationType.OP_DELEGATECALL)
                                                .setCallingContract(
                                                        spec.registry().getContractId(TRACEABILITY + SECOND))
                                                .setGas(905444)
                                                .setGasUsed(20323)
                                                .setCallDepth(2)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY + THIRD))
                                                .setOutput(EMPTY)
                                                .setInput(encodeFunctionCall(
                                                        TRACEABILITY, SET_ZERO_SLOT, BigInteger.valueOf(100)))
                                                .build(),
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallOperationType(CallOperationType.OP_CALL)
                                                .setCallingContract(
                                                        spec.registry().getContractId(TRACEABILITY))
                                                .setGas(899403)
                                                .setGasUsed(3387)
                                                .setCallDepth(1)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY + SECOND))
                                                .setOutput(EMPTY)
                                                .setInput(encodeFunctionCall(
                                                        TRACEABILITY,
                                                        "delegateCallAddressGetSlot1",
                                                        hexedSolidityAddressToHeadlongAddress(
                                                                getNestedContractAddress(TRACEABILITY + THIRD, spec))))
                                                .build(),
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallOperationType(CallOperationType.OP_DELEGATECALL)
                                                .setCallingContract(
                                                        spec.registry().getContractId(TRACEABILITY + SECOND))
                                                .setGas(884502)
                                                .setGasUsed(2391)
                                                .setCallDepth(2)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY + THIRD))
                                                .setOutput(uint256ReturnWithValue(BigInteger.valueOf(0)))
                                                .setInput(encodeFunctionCall(TRACEABILITY, GET_FIRST_SLOT))
                                                .build(),
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallOperationType(CallOperationType.OP_CALL)
                                                .setCallingContract(
                                                        spec.registry().getContractId(TRACEABILITY))
                                                .setGas(895682)
                                                .setGasUsed(1476)
                                                .setCallDepth(1)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY + SECOND))
                                                .setOutput(EMPTY)
                                                .setInput(encodeFunctionCall(
                                                        TRACEABILITY,
                                                        "delegateCallAddressSetSlot1",
                                                        hexedSolidityAddressToHeadlongAddress(
                                                                getNestedContractAddress(TRACEABILITY + THIRD, spec)),
                                                        BigInteger.valueOf(0)))
                                                .build(),
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallOperationType(CallOperationType.OP_DELEGATECALL)
                                                .setCallingContract(
                                                        spec.registry().getContractId(TRACEABILITY + SECOND))
                                                .setGas(880714)
                                                .setGasUsed(424)
                                                .setCallDepth(2)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY + THIRD))
                                                .setOutput(EMPTY)
                                                .setInput(encodeFunctionCall(
                                                        TRACEABILITY, SET_FIRST_SLOT, BigInteger.valueOf(0)))
                                                .build())))));
    }

    @HapiTest
    @Order(3)
    final Stream<DynamicTest> traceabilityE2EScenario3() {
        return hapiTest(
                uploadInitCode(TRACEABILITY),
                contractCreate(TRACEABILITY, BigInteger.valueOf(55), BigInteger.TWO, BigInteger.TWO)
                        .via(FIRST_CREATE_TXN)
                        .gas(2_500_000L),
                expectContractStateChangesSidecarFor(
                        FIRST_CREATE_TXN,
                        List.of(StateChange.stateChangeFor(TRACEABILITY)
                                .withStorageChanges(
                                        StorageChange.readAndWritten(
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(55)),
                                        StorageChange.readAndWritten(
                                                formattedAssertionValue(1),
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(2)),
                                        StorageChange.readAndWritten(
                                                formattedAssertionValue(2),
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(2))))),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        expectContractActionSidecarFor(
                                FIRST_CREATE_TXN,
                                List.of(ContractAction.newBuilder()
                                        .setCallType(CREATE)
                                        .setCallOperationType(CallOperationType.OP_CREATE)
                                        .setCallingAccount(TxnUtils.asId(GENESIS, spec))
                                        .setGas(2298224)
                                        .setRecipientContract(spec.registry().getContractId(TRACEABILITY))
                                        .setGasUsed(68492)
                                        .setOutput(EMPTY)
                                        .build())))),
                expectContractBytecodeSidecarFor(
                        FIRST_CREATE_TXN,
                        TRACEABILITY,
                        TRACEABILITY,
                        BigInteger.valueOf(55),
                        BigInteger.TWO,
                        BigInteger.TWO),
                contractCustomCreate(TRACEABILITY, SECOND, BigInteger.ZERO, BigInteger.ZERO, BigInteger.valueOf(12))
                        .via(SECOND_CREATE_TXN),
                expectContractStateChangesSidecarFor(
                        SECOND_CREATE_TXN,
                        List.of(StateChange.stateChangeFor(TRACEABILITY + SECOND)
                                .withStorageChanges(
                                        StorageChange.readAndWritten(
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(0)),
                                        StorageChange.readAndWritten(
                                                formattedAssertionValue(1),
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(0)),
                                        StorageChange.readAndWritten(
                                                formattedAssertionValue(2),
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(12))))),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        expectContractActionSidecarFor(
                                SECOND_CREATE_TXN,
                                List.of(ContractAction.newBuilder()
                                        .setCallType(CREATE)
                                        .setCallOperationType(CallOperationType.OP_CREATE)
                                        .setCallingAccount(TxnUtils.asId(GENESIS, spec))
                                        .setGas(2798248)
                                        .setRecipientContract(spec.registry().getContractId(TRACEABILITY + SECOND))
                                        .setGasUsed(28692)
                                        .setOutput(EMPTY)
                                        .build())))),
                expectContractBytecodeSidecarFor(
                        SECOND_CREATE_TXN,
                        TRACEABILITY + SECOND,
                        TRACEABILITY,
                        BigInteger.ZERO,
                        BigInteger.ZERO,
                        BigInteger.valueOf(12)),
                contractCustomCreate(TRACEABILITY, THIRD, BigInteger.ZERO, BigInteger.valueOf(11), BigInteger.ZERO)
                        .via(THIRD_CREATE_TXN),
                expectContractStateChangesSidecarFor(
                        THIRD_CREATE_TXN,
                        List.of(StateChange.stateChangeFor(TRACEABILITY + THIRD)
                                .withStorageChanges(
                                        StorageChange.readAndWritten(
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(0)),
                                        StorageChange.readAndWritten(
                                                formattedAssertionValue(1),
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(11)),
                                        StorageChange.readAndWritten(
                                                formattedAssertionValue(2),
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(0))))),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        expectContractActionSidecarFor(
                                THIRD_CREATE_TXN,
                                List.of(ContractAction.newBuilder()
                                        .setCallType(CREATE)
                                        .setCallOperationType(CallOperationType.OP_CREATE)
                                        .setCallingAccount(TxnUtils.asId(GENESIS, spec))
                                        .setGas(2798248)
                                        .setRecipientContract(spec.registry().getContractId(TRACEABILITY + THIRD))
                                        .setGasUsed(28692)
                                        .setOutput(EMPTY)
                                        .build())))),
                expectContractBytecodeSidecarFor(
                        THIRD_CREATE_TXN,
                        TRACEABILITY + THIRD,
                        TRACEABILITY,
                        BigInteger.ZERO,
                        BigInteger.valueOf(11),
                        BigInteger.ZERO),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCall(
                                        TRACEABILITY,
                                        "eetScenario3",
                                        asHeadlongAddress(getNestedContractAddress(TRACEABILITY + "B", spec)),
                                        asHeadlongAddress(getNestedContractAddress(TRACEABILITY + "C", spec)))
                                .gas(1_000_000)
                                .via(TRACEABILITY_TXN))),
                expectContractStateChangesSidecarFor(
                        TRACEABILITY_TXN,
                        List.of(
                                StateChange.stateChangeFor(TRACEABILITY)
                                        .withStorageChanges(
                                                StorageChange.onlyRead(
                                                        formattedAssertionValue(0), formattedAssertionValue(55)),
                                                StorageChange.readAndWritten(
                                                        formattedAssertionValue(1),
                                                        formattedAssertionValue(2),
                                                        formattedAssertionValue(55252)),
                                                StorageChange.readAndWritten(
                                                        formattedAssertionValue(2),
                                                        formattedAssertionValue(2),
                                                        formattedAssertionValue(524))),
                                StateChange.stateChangeFor(TRACEABILITY + THIRD)
                                        .withStorageChanges(
                                                StorageChange.readAndWritten(
                                                        formattedAssertionValue(0),
                                                        formattedAssertionValue(0),
                                                        formattedAssertionValue(54)),
                                                StorageChange.readAndWritten(
                                                        formattedAssertionValue(1),
                                                        formattedAssertionValue(11),
                                                        formattedAssertionValue(0))))),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        expectContractActionSidecarFor(
                                TRACEABILITY_TXN,
                                List.of(
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallingAccount(TxnUtils.asId(GENESIS, spec))
                                                .setCallOperationType(CallOperationType.OP_CALL)
                                                .setGas(978632)
                                                .setGasUsed(57011)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY))
                                                .setOutput(EMPTY)
                                                .setInput(encodeFunctionCall(
                                                        TRACEABILITY,
                                                        "eetScenario3",
                                                        hexedSolidityAddressToHeadlongAddress(
                                                                getNestedContractAddress(TRACEABILITY + "B", spec)),
                                                        hexedSolidityAddressToHeadlongAddress(
                                                                getNestedContractAddress(TRACEABILITY + "C", spec))))
                                                .build(),
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallOperationType(CallOperationType.OP_CALL)
                                                .setCallingContract(
                                                        spec.registry().getContractId(TRACEABILITY))
                                                .setGas(962697)
                                                .setGasUsed(2347)
                                                .setCallDepth(1)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY))
                                                .setOutput(uint256ReturnWithValue(BigInteger.valueOf(55)))
                                                .setInput(encodeFunctionCall(TRACEABILITY, GET_ZERO_SLOT))
                                                .build(),
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallOperationType(CallOperationType.OP_CALL)
                                                .setCallingContract(
                                                        spec.registry().getContractId(TRACEABILITY))
                                                .setCallDepth(1)
                                                .setGas(959915)
                                                .setGasUsed(5324)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY))
                                                .setOutput(EMPTY)
                                                .setInput(encodeFunctionCall(
                                                        TRACEABILITY, SET_FIRST_SLOT, BigInteger.valueOf(55252)))
                                                .build(),
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallOperationType(CallOperationType.OP_CALL)
                                                .setCallingContract(
                                                        spec.registry().getContractId(TRACEABILITY))
                                                .setGas(954321)
                                                .setGasUsed(5810)
                                                .setCallDepth(1)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY))
                                                .setOutput(EMPTY)
                                                .setInput(encodeFunctionCall(
                                                        TRACEABILITY,
                                                        DELEGATE_CALL_ADDRESS_GET_SLOT_2,
                                                        hexedSolidityAddressToHeadlongAddress(
                                                                getNestedContractAddress(TRACEABILITY + THIRD, spec))))
                                                .build(),
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallOperationType(CallOperationType.OP_DELEGATECALL)
                                                .setCallingContract(
                                                        spec.registry().getContractId(TRACEABILITY))
                                                .setGas(936102)
                                                .setGasUsed(2315)
                                                .setCallDepth(2)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY + THIRD))
                                                .setOutput(uint256ReturnWithValue(BigInteger.valueOf(2)))
                                                .setInput(encodeFunctionCall(TRACEABILITY, GET_SECOND_SLOT))
                                                .build(),
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallOperationType(CallOperationType.OP_CALL)
                                                .setCallingContract(
                                                        spec.registry().getContractId(TRACEABILITY))
                                                .setGas(948230)
                                                .setGasUsed(4209)
                                                .setCallDepth(1)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY))
                                                .setOutput(EMPTY)
                                                .setInput(encodeFunctionCall(
                                                        TRACEABILITY,
                                                        "delegateCallAddressSetSlot2",
                                                        hexedSolidityAddressToHeadlongAddress(
                                                                getNestedContractAddress(TRACEABILITY + THIRD, spec)),
                                                        BigInteger.valueOf(524)))
                                                .build(),
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallOperationType(CallOperationType.OP_DELEGATECALL)
                                                .setCallingContract(
                                                        spec.registry().getContractId(TRACEABILITY))
                                                .setGas(932463)
                                                .setGasUsed(3180)
                                                .setCallDepth(2)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY + THIRD))
                                                .setOutput(EMPTY)
                                                .setInput(encodeFunctionCall(
                                                        TRACEABILITY, SET_SECOND_SLOT, BigInteger.valueOf(524)))
                                                .build(),
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallOperationType(CallOperationType.OP_DELEGATECALL)
                                                .setCallingContract(
                                                        spec.registry().getContractId(TRACEABILITY))
                                                .setGas(941036)
                                                .setGasUsed(3278)
                                                .setCallDepth(1)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY + SECOND))
                                                .setOutput(EMPTY)
                                                .setInput(encodeFunctionCall(
                                                        TRACEABILITY,
                                                        "callAddressGetSlot0",
                                                        hexedSolidityAddressToHeadlongAddress(
                                                                getNestedContractAddress(TRACEABILITY + THIRD, spec))))
                                                .build(),
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallOperationType(CallOperationType.OP_CALL)
                                                .setCallingContract(
                                                        spec.registry().getContractId(TRACEABILITY + SECOND))
                                                .setGas(925548)
                                                .setGasUsed(2347)
                                                .setCallDepth(2)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY + THIRD))
                                                .setOutput(uint256ReturnWithValue(BigInteger.valueOf(0)))
                                                .setInput(encodeFunctionCall(TRACEABILITY, GET_ZERO_SLOT))
                                                .build(),
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallOperationType(CallOperationType.OP_DELEGATECALL)
                                                .setCallingContract(
                                                        spec.registry().getContractId(TRACEABILITY))
                                                .setGas(937112)
                                                .setGasUsed(21401)
                                                .setCallDepth(1)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY + SECOND))
                                                .setOutput(EMPTY)
                                                .setInput(encodeFunctionCall(
                                                        TRACEABILITY,
                                                        "callAddressSetSlot0",
                                                        hexedSolidityAddressToHeadlongAddress(
                                                                getNestedContractAddress(TRACEABILITY + THIRD, spec)),
                                                        BigInteger.valueOf(54)))
                                                .build(),
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallOperationType(CallOperationType.OP_CALL)
                                                .setCallingContract(
                                                        spec.registry().getContractId(TRACEABILITY + SECOND))
                                                .setGas(921471)
                                                .setGasUsed(20323)
                                                .setCallDepth(2)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY + THIRD))
                                                .setOutput(EMPTY)
                                                .setInput(encodeFunctionCall(
                                                        TRACEABILITY, SET_ZERO_SLOT, BigInteger.valueOf(54)))
                                                .build(),
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallOperationType(CallOperationType.OP_DELEGATECALL)
                                                .setCallingContract(
                                                        spec.registry().getContractId(TRACEABILITY))
                                                .setGas(915443)
                                                .setGasUsed(3345)
                                                .setCallDepth(1)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY + SECOND))
                                                .setOutput(EMPTY)
                                                .setInput(encodeFunctionCall(
                                                        TRACEABILITY,
                                                        "callAddressGetSlot1",
                                                        hexedSolidityAddressToHeadlongAddress(
                                                                getNestedContractAddress(TRACEABILITY + THIRD, spec))))
                                                .build(),
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallOperationType(CallOperationType.OP_CALL)
                                                .setCallingContract(
                                                        spec.registry().getContractId(TRACEABILITY + SECOND))
                                                .setGas(900333)
                                                .setGasUsed(2391)
                                                .setCallDepth(2)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY + THIRD))
                                                .setOutput(uint256ReturnWithValue(BigInteger.valueOf(11)))
                                                .setInput(encodeFunctionCall(TRACEABILITY, GET_FIRST_SLOT))
                                                .build(),
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallOperationType(CallOperationType.OP_DELEGATECALL)
                                                .setCallingContract(
                                                        spec.registry().getContractId(TRACEABILITY))
                                                .setGas(911452)
                                                .setGasUsed(4235)
                                                .setCallDepth(1)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY + SECOND))
                                                .setOutput(EMPTY)
                                                .setInput(encodeFunctionCall(
                                                        TRACEABILITY,
                                                        "callAddressSetSlot1",
                                                        hexedSolidityAddressToHeadlongAddress(
                                                                getNestedContractAddress(TRACEABILITY + THIRD, spec)),
                                                        BigInteger.valueOf(0)))
                                                .build(),
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallOperationType(CallOperationType.OP_CALL)
                                                .setCallingContract(
                                                        spec.registry().getContractId(TRACEABILITY + SECOND))
                                                .setGas(896278)
                                                .setGasUsed(3224)
                                                .setCallDepth(2)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY + THIRD))
                                                .setOutput(EMPTY)
                                                .setInput(encodeFunctionCall(
                                                        TRACEABILITY, SET_FIRST_SLOT, BigInteger.valueOf(0)))
                                                .build())))));
    }

    @HapiTest
    @Order(4)
    final Stream<DynamicTest> traceabilityE2EScenario4() {
        return hapiTest(
                uploadInitCode(TRACEABILITY),
                contractCreate(TRACEABILITY, BigInteger.TWO, BigInteger.valueOf(3), BigInteger.valueOf(4))
                        .via(FIRST_CREATE_TXN)
                        .gas(2_500_000L),
                expectContractStateChangesSidecarFor(
                        FIRST_CREATE_TXN,
                        List.of(StateChange.stateChangeFor(TRACEABILITY)
                                .withStorageChanges(
                                        StorageChange.readAndWritten(
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(2)),
                                        StorageChange.readAndWritten(
                                                formattedAssertionValue(1),
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(3)),
                                        StorageChange.readAndWritten(
                                                formattedAssertionValue(2),
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(4))))),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        expectContractActionSidecarFor(
                                FIRST_CREATE_TXN,
                                List.of(ContractAction.newBuilder()
                                        .setCallType(CREATE)
                                        .setCallOperationType(CallOperationType.OP_CREATE)
                                        .setCallingAccount(TxnUtils.asId(GENESIS, spec))
                                        .setGas(2298224)
                                        .setRecipientContract(spec.registry().getContractId(TRACEABILITY))
                                        .setGasUsed(68492)
                                        .setOutput(EMPTY)
                                        .build())))),
                expectContractBytecodeSidecarFor(
                        FIRST_CREATE_TXN,
                        TRACEABILITY,
                        TRACEABILITY,
                        BigInteger.TWO,
                        BigInteger.valueOf(3),
                        BigInteger.valueOf(4)),
                contractCustomCreate(TRACEABILITY, SECOND, BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO)
                        .via(SECOND_CREATE_TXN),
                expectContractStateChangesSidecarFor(
                        SECOND_CREATE_TXN,
                        List.of(StateChange.stateChangeFor(TRACEABILITY + SECOND)
                                .withStorageChanges(
                                        StorageChange.readAndWritten(
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(0)),
                                        StorageChange.readAndWritten(
                                                formattedAssertionValue(1),
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(0)),
                                        StorageChange.readAndWritten(
                                                formattedAssertionValue(2),
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(0))))),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        expectContractActionSidecarFor(
                                SECOND_CREATE_TXN,
                                List.of(ContractAction.newBuilder()
                                        .setCallType(CREATE)
                                        .setCallOperationType(CallOperationType.OP_CREATE)
                                        .setCallingAccount(TxnUtils.asId(GENESIS, spec))
                                        .setGas(2798260)
                                        .setRecipientContract(spec.registry().getContractId(TRACEABILITY + SECOND))
                                        .setGasUsed(8792)
                                        .setOutput(EMPTY)
                                        .build())))),
                expectContractBytecodeSidecarFor(
                        SECOND_CREATE_TXN,
                        TRACEABILITY + SECOND,
                        TRACEABILITY,
                        BigInteger.ZERO,
                        BigInteger.ZERO,
                        BigInteger.ZERO),
                contractCustomCreate(TRACEABILITY, THIRD, BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO)
                        .via(THIRD_CREATE_TXN),
                expectContractStateChangesSidecarFor(
                        THIRD_CREATE_TXN,
                        List.of(StateChange.stateChangeFor(TRACEABILITY + THIRD)
                                .withStorageChanges(
                                        StorageChange.readAndWritten(
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(0)),
                                        StorageChange.readAndWritten(
                                                formattedAssertionValue(1),
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(0)),
                                        StorageChange.readAndWritten(
                                                formattedAssertionValue(2),
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(0))))),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        expectContractActionSidecarFor(
                                THIRD_CREATE_TXN,
                                List.of(ContractAction.newBuilder()
                                        .setCallType(CREATE)
                                        .setCallOperationType(CallOperationType.OP_CREATE)
                                        .setCallingAccount(TxnUtils.asId(GENESIS, spec))
                                        .setGas(2798260)
                                        .setRecipientContract(spec.registry().getContractId(TRACEABILITY + THIRD))
                                        .setGasUsed(8792)
                                        .setOutput(EMPTY)
                                        .build())))),
                expectContractBytecodeSidecarFor(
                        THIRD_CREATE_TXN,
                        TRACEABILITY + THIRD,
                        TRACEABILITY,
                        BigInteger.ZERO,
                        BigInteger.ZERO,
                        BigInteger.ZERO),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCall(
                                        TRACEABILITY,
                                        "eetScenario4",
                                        asHeadlongAddress(getNestedContractAddress(TRACEABILITY + "B", spec)),
                                        asHeadlongAddress(getNestedContractAddress(TRACEABILITY + "C", spec)))
                                .gas(1_000_000)
                                .via(TRACEABILITY_TXN))),
                expectContractStateChangesSidecarFor(
                        TRACEABILITY_TXN,
                        List.of(StateChange.stateChangeFor(TRACEABILITY)
                                .withStorageChanges(
                                        StorageChange.readAndWritten(
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(2),
                                                formattedAssertionValue(55)),
                                        StorageChange.readAndWritten(
                                                formattedAssertionValue(1),
                                                formattedAssertionValue(3),
                                                formattedAssertionValue(4)),
                                        StorageChange.onlyRead(
                                                formattedAssertionValue(2), formattedAssertionValue(4))))),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        expectContractActionSidecarFor(
                                TRACEABILITY_TXN,
                                List.of(
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallingAccount(TxnUtils.asId(GENESIS, spec))
                                                .setCallOperationType(CallOperationType.OP_CALL)
                                                .setGas(978632)
                                                .setGasUsed(23913)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY))
                                                .setOutput(EMPTY)
                                                .setInput(encodeFunctionCall(
                                                        TRACEABILITY,
                                                        "eetScenario4",
                                                        hexedSolidityAddressToHeadlongAddress(
                                                                getNestedContractAddress(TRACEABILITY + "B", spec)),
                                                        hexedSolidityAddressToHeadlongAddress(
                                                                getNestedContractAddress(TRACEABILITY + "C", spec))))
                                                .build(),
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallOperationType(CallOperationType.OP_CALL)
                                                .setCallingContract(
                                                        spec.registry().getContractId(TRACEABILITY))
                                                .setGas(962676)
                                                .setGasUsed(2347)
                                                .setCallDepth(1)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY))
                                                .setOutput(uint256ReturnWithValue(BigInteger.valueOf(2)))
                                                .setInput(encodeFunctionCall(TRACEABILITY, GET_ZERO_SLOT))
                                                .build(),
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallOperationType(CallOperationType.OP_CALL)
                                                .setCallingContract(
                                                        spec.registry().getContractId(TRACEABILITY))
                                                .setCallDepth(1)
                                                .setGas(959894)
                                                .setGasUsed(3223)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY))
                                                .setOutput(EMPTY)
                                                .setInput(encodeFunctionCall(
                                                        TRACEABILITY, SET_ZERO_SLOT, BigInteger.valueOf(3)))
                                                .build(),
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallOperationType(CallOperationType.OP_CALL)
                                                .setCallingContract(
                                                        spec.registry().getContractId(TRACEABILITY))
                                                .setGas(956509)
                                                .setGasUsed(2391)
                                                .setCallDepth(1)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY))
                                                .setOutput(uint256ReturnWithValue(BigInteger.valueOf(3)))
                                                .setInput(encodeFunctionCall(TRACEABILITY, GET_FIRST_SLOT))
                                                .build(),
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallOperationType(CallOperationType.OP_CALL)
                                                .setCallingContract(
                                                        spec.registry().getContractId(TRACEABILITY))
                                                .setCallDepth(1)
                                                .setGas(953687)
                                                .setGasUsed(3224)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY))
                                                .setOutput(EMPTY)
                                                .setInput(encodeFunctionCall(
                                                        TRACEABILITY, SET_FIRST_SLOT, BigInteger.valueOf(4)))
                                                .build(),
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallOperationType(CallOperationType.OP_CALL)
                                                .setCallingContract(
                                                        spec.registry().getContractId(TRACEABILITY))
                                                .setGas(950160)
                                                .setGasUsed(5810)
                                                .setCallDepth(1)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY))
                                                .setOutput(EMPTY)
                                                .setInput(encodeFunctionCall(
                                                        TRACEABILITY,
                                                        DELEGATE_CALL_ADDRESS_GET_SLOT_2,
                                                        hexedSolidityAddressToHeadlongAddress(
                                                                getNestedContractAddress(TRACEABILITY + SECOND, spec))))
                                                .build(),
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallOperationType(CallOperationType.OP_DELEGATECALL)
                                                .setCallingContract(
                                                        spec.registry().getContractId(TRACEABILITY))
                                                .setGas(932006)
                                                .setGasUsed(2315)
                                                .setCallDepth(2)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY + SECOND))
                                                .setOutput(uint256ReturnWithValue(BigInteger.valueOf(4)))
                                                .setInput(encodeFunctionCall(TRACEABILITY, GET_SECOND_SLOT))
                                                .build(),
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallOperationType(CallOperationType.OP_DELEGATECALL)
                                                .setCallingContract(
                                                        spec.registry().getContractId(TRACEABILITY))
                                                .setGas(943755)
                                                .setGasUsed(3953)
                                                .setCallDepth(1)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY + SECOND))
                                                .setOutput(EMPTY)
                                                .setInput(encodeFunctionCall(
                                                        TRACEABILITY,
                                                        "delegateCallAddressSetSlot0",
                                                        hexedSolidityAddressToHeadlongAddress(
                                                                getNestedContractAddress(TRACEABILITY + THIRD, spec)),
                                                        BigInteger.valueOf(55)))
                                                .build(),
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallOperationType(CallOperationType.OP_DELEGATECALL)
                                                .setCallingContract(
                                                        spec.registry().getContractId(TRACEABILITY + SECOND))
                                                .setGas(925596)
                                                .setGasUsed(423)
                                                .setCallDepth(2)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY + THIRD))
                                                .setOutput(EMPTY)
                                                .setInput(encodeFunctionCall(
                                                        TRACEABILITY, SET_ZERO_SLOT, BigInteger.valueOf(55)))
                                                .build())))));
    }

    @HapiTest
    @Order(5)
    final Stream<DynamicTest> traceabilityE2EScenario5() {
        return hapiTest(
                uploadInitCode(TRACEABILITY),
                contractCreate(TRACEABILITY, BigInteger.valueOf(55), BigInteger.TWO, BigInteger.TWO)
                        .via(FIRST_CREATE_TXN)
                        .gas(2_500_000L),
                expectContractStateChangesSidecarFor(
                        FIRST_CREATE_TXN,
                        List.of(StateChange.stateChangeFor(TRACEABILITY)
                                .withStorageChanges(
                                        StorageChange.readAndWritten(
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(55)),
                                        StorageChange.readAndWritten(
                                                formattedAssertionValue(1),
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(2)),
                                        StorageChange.readAndWritten(
                                                formattedAssertionValue(2),
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(2))))),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        expectContractActionSidecarFor(
                                FIRST_CREATE_TXN,
                                List.of(ContractAction.newBuilder()
                                        .setCallType(CREATE)
                                        .setCallOperationType(CallOperationType.OP_CREATE)
                                        .setCallingAccount(TxnUtils.asId(GENESIS, spec))
                                        .setGas(2298224)
                                        .setRecipientContract(spec.registry().getContractId(TRACEABILITY))
                                        .setGasUsed(68492)
                                        .setOutput(EMPTY)
                                        .build())))),
                expectContractBytecodeSidecarFor(
                        FIRST_CREATE_TXN,
                        TRACEABILITY,
                        TRACEABILITY,
                        BigInteger.valueOf(55),
                        BigInteger.TWO,
                        BigInteger.TWO),
                contractCustomCreate(TRACEABILITY, SECOND, BigInteger.ZERO, BigInteger.ZERO, BigInteger.valueOf(12))
                        .via(SECOND_CREATE_TXN)
                        .gas(2_500_000L),
                expectContractStateChangesSidecarFor(
                        SECOND_CREATE_TXN,
                        List.of(StateChange.stateChangeFor(TRACEABILITY + SECOND)
                                .withStorageChanges(
                                        StorageChange.readAndWritten(
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(0)),
                                        StorageChange.readAndWritten(
                                                formattedAssertionValue(1),
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(0)),
                                        StorageChange.readAndWritten(
                                                formattedAssertionValue(2),
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(12))))),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        expectContractActionSidecarFor(
                                SECOND_CREATE_TXN,
                                List.of(ContractAction.newBuilder()
                                        .setCallType(CREATE)
                                        .setCallOperationType(CallOperationType.OP_CREATE)
                                        .setCallingAccount(TxnUtils.asId(GENESIS, spec))
                                        .setGas(2298248)
                                        .setRecipientContract(spec.registry().getContractId(TRACEABILITY + SECOND))
                                        .setGasUsed(28692)
                                        .setOutput(EMPTY)
                                        .build())))),
                expectContractBytecodeSidecarFor(
                        SECOND_CREATE_TXN,
                        TRACEABILITY + SECOND,
                        TRACEABILITY,
                        BigInteger.ZERO,
                        BigInteger.ZERO,
                        BigInteger.valueOf(12)),
                contractCustomCreate(TRACEABILITY, THIRD, BigInteger.valueOf(4), BigInteger.ONE, BigInteger.ZERO)
                        .via(THIRD_CREATE_TXN)
                        .gas(2_500_000L),
                expectContractStateChangesSidecarFor(
                        THIRD_CREATE_TXN,
                        List.of(StateChange.stateChangeFor(TRACEABILITY + THIRD)
                                .withStorageChanges(
                                        StorageChange.readAndWritten(
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(4)),
                                        StorageChange.readAndWritten(
                                                formattedAssertionValue(1),
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(1)),
                                        StorageChange.readAndWritten(
                                                formattedAssertionValue(2),
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(0))))),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        expectContractActionSidecarFor(
                                THIRD_CREATE_TXN,
                                List.of(ContractAction.newBuilder()
                                        .setCallType(CREATE)
                                        .setCallOperationType(CallOperationType.OP_CREATE)
                                        .setCallingAccount(TxnUtils.asId(GENESIS, spec))
                                        .setGas(2298224)
                                        .setRecipientContract(spec.registry().getContractId(TRACEABILITY + THIRD))
                                        .setGasUsed(48592)
                                        .setOutput(EMPTY)
                                        .build())))),
                expectContractBytecodeSidecarFor(
                        THIRD_CREATE_TXN,
                        TRACEABILITY + THIRD,
                        TRACEABILITY,
                        BigInteger.valueOf(4),
                        BigInteger.ONE,
                        BigInteger.ZERO),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCall(
                                        TRACEABILITY,
                                        "eetScenario5",
                                        asHeadlongAddress(getNestedContractAddress(TRACEABILITY + "B", spec)),
                                        asHeadlongAddress(getNestedContractAddress(TRACEABILITY + "C", spec)))
                                .gas(1_000_000)
                                .via(TRACEABILITY_TXN))),
                expectContractStateChangesSidecarFor(
                        TRACEABILITY_TXN,
                        List.of(
                                StateChange.stateChangeFor(TRACEABILITY)
                                        .withStorageChanges(
                                                StorageChange.onlyRead(
                                                        formattedAssertionValue(0), formattedAssertionValue(55)),
                                                StorageChange.readAndWritten(
                                                        formattedAssertionValue(1),
                                                        formattedAssertionValue(2),
                                                        formattedAssertionValue(55252))),
                                StateChange.stateChangeFor(TRACEABILITY + SECOND)
                                        .withStorageChanges(StorageChange.readAndWritten(
                                                formattedAssertionValue(2),
                                                formattedAssertionValue(12),
                                                formattedAssertionValue(524))),
                                StateChange.stateChangeFor(TRACEABILITY + THIRD)
                                        .withStorageChanges(
                                                StorageChange.onlyRead(
                                                        formattedAssertionValue(0), formattedAssertionValue(4)),
                                                StorageChange.onlyRead(
                                                        formattedAssertionValue(1), formattedAssertionValue(1))))),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        expectContractActionSidecarFor(
                                TRACEABILITY_TXN,
                                List.of(
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallingAccount(TxnUtils.asId(GENESIS, spec))
                                                .setCallOperationType(CallOperationType.OP_CALL)
                                                .setGas(978632)
                                                .setGasUsed(27376)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY))
                                                .setOutput(EMPTY)
                                                .setInput(encodeFunctionCall(
                                                        TRACEABILITY,
                                                        "eetScenario5",
                                                        hexedSolidityAddressToHeadlongAddress(
                                                                getNestedContractAddress(TRACEABILITY + "B", spec)),
                                                        hexedSolidityAddressToHeadlongAddress(
                                                                getNestedContractAddress(TRACEABILITY + "C", spec))))
                                                .build(),
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallOperationType(CallOperationType.OP_CALL)
                                                .setCallingContract(
                                                        spec.registry().getContractId(TRACEABILITY))
                                                .setGas(962719)
                                                .setGasUsed(2347)
                                                .setCallDepth(1)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY))
                                                .setOutput(uint256ReturnWithValue(BigInteger.valueOf(55)))
                                                .setInput(encodeFunctionCall(TRACEABILITY, GET_ZERO_SLOT))
                                                .build(),
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallOperationType(CallOperationType.OP_CALL)
                                                .setCallingContract(
                                                        spec.registry().getContractId(TRACEABILITY))
                                                .setCallDepth(1)
                                                .setGas(959937)
                                                .setGasUsed(5324)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY))
                                                .setOutput(EMPTY)
                                                .setInput(encodeFunctionCall(
                                                        TRACEABILITY, SET_FIRST_SLOT, BigInteger.valueOf(55252)))
                                                .build(),
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallOperationType(CallOperationType.OP_CALL)
                                                .setCallingContract(
                                                        spec.registry().getContractId(TRACEABILITY))
                                                .setGas(952011)
                                                .setGasUsed(2315)
                                                .setCallDepth(1)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY + SECOND))
                                                .setOutput(uint256ReturnWithValue(BigInteger.valueOf(12)))
                                                .setInput(encodeFunctionCall(TRACEABILITY, GET_SECOND_SLOT))
                                                .build(),
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallOperationType(CallOperationType.OP_CALL)
                                                .setCallingContract(
                                                        spec.registry().getContractId(TRACEABILITY))
                                                .setGas(949245)
                                                .setGasUsed(3180)
                                                .setCallDepth(1)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY + SECOND))
                                                .setOutput(EMPTY)
                                                .setInput(encodeFunctionCall(
                                                        TRACEABILITY, SET_SECOND_SLOT, BigInteger.valueOf(524)))
                                                .build(),
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallOperationType(CallOperationType.OP_CALL)
                                                .setCallingContract(
                                                        spec.registry().getContractId(TRACEABILITY))
                                                .setGas(945755)
                                                .setGasUsed(5777)
                                                .setCallDepth(1)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY + SECOND))
                                                .setOutput(EMPTY)
                                                .setInput(encodeFunctionCall(
                                                        TRACEABILITY,
                                                        "staticCallAddressGetSlot0",
                                                        hexedSolidityAddressToHeadlongAddress(
                                                                getNestedContractAddress(TRACEABILITY + THIRD, spec))))
                                                .build(),
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallOperationType(CallOperationType.OP_STATICCALL)
                                                .setCallingContract(
                                                        spec.registry().getContractId(TRACEABILITY + SECOND))
                                                .setGas(927734)
                                                .setGasUsed(2347)
                                                .setCallDepth(2)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY + THIRD))
                                                .setOutput(uint256ReturnWithValue(BigInteger.valueOf(4)))
                                                .setInput(encodeFunctionCall(TRACEABILITY, GET_ZERO_SLOT))
                                                .build(),
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallOperationType(CallOperationType.OP_CALL)
                                                .setCallingContract(
                                                        spec.registry().getContractId(TRACEABILITY))
                                                .setGas(939707)
                                                .setGasUsed(3320)
                                                .setCallDepth(1)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY + SECOND))
                                                .setOutput(EMPTY)
                                                .setInput(encodeFunctionCall(
                                                        TRACEABILITY,
                                                        "staticCallAddressGetSlot1",
                                                        hexedSolidityAddressToHeadlongAddress(
                                                                getNestedContractAddress(TRACEABILITY + THIRD, spec))))
                                                .build(),
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallOperationType(CallOperationType.OP_STATICCALL)
                                                .setCallingContract(
                                                        spec.registry().getContractId(TRACEABILITY + SECOND))
                                                .setGas(924242)
                                                .setGasUsed(2391)
                                                .setCallDepth(2)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY + THIRD))
                                                .setOutput(uint256ReturnWithValue(BigInteger.valueOf(1)))
                                                .setInput(encodeFunctionCall(TRACEABILITY, GET_FIRST_SLOT))
                                                .build())))));
    }

    @HapiTest
    @Order(6)
    final Stream<DynamicTest> traceabilityE2EScenario6() {
        return hapiTest(
                uploadInitCode(TRACEABILITY),
                contractCreate(TRACEABILITY, BigInteger.TWO, BigInteger.valueOf(3), BigInteger.valueOf(4))
                        .via(FIRST_CREATE_TXN)
                        .gas(2_500_000L),
                expectContractStateChangesSidecarFor(
                        FIRST_CREATE_TXN,
                        List.of(StateChange.stateChangeFor(TRACEABILITY)
                                .withStorageChanges(
                                        StorageChange.readAndWritten(
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(2)),
                                        StorageChange.readAndWritten(
                                                formattedAssertionValue(1),
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(3)),
                                        StorageChange.readAndWritten(
                                                formattedAssertionValue(2),
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(4))))),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        expectContractActionSidecarFor(
                                FIRST_CREATE_TXN,
                                List.of(ContractAction.newBuilder()
                                        .setCallType(CREATE)
                                        .setCallOperationType(CallOperationType.OP_CREATE)
                                        .setCallingAccount(TxnUtils.asId(GENESIS, spec))
                                        .setGas(2298224)
                                        .setRecipientContract(spec.registry().getContractId(TRACEABILITY))
                                        .setGasUsed(68492)
                                        .setOutput(EMPTY)
                                        .build())))),
                expectContractBytecodeSidecarFor(
                        FIRST_CREATE_TXN,
                        TRACEABILITY,
                        TRACEABILITY,
                        BigInteger.TWO,
                        BigInteger.valueOf(3),
                        BigInteger.valueOf(4)),
                contractCustomCreate(TRACEABILITY, SECOND, BigInteger.ZERO, BigInteger.ZERO, BigInteger.valueOf(3))
                        .via(SECOND_CREATE_TXN),
                expectContractStateChangesSidecarFor(
                        SECOND_CREATE_TXN,
                        List.of(StateChange.stateChangeFor(TRACEABILITY + SECOND)
                                .withStorageChanges(
                                        StorageChange.readAndWritten(
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(0)),
                                        StorageChange.readAndWritten(
                                                formattedAssertionValue(1),
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(0)),
                                        StorageChange.readAndWritten(
                                                formattedAssertionValue(2),
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(3))))),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        expectContractActionSidecarFor(
                                SECOND_CREATE_TXN,
                                List.of(ContractAction.newBuilder()
                                        .setCallType(CREATE)
                                        .setCallOperationType(CallOperationType.OP_CREATE)
                                        .setCallingAccount(TxnUtils.asId(GENESIS, spec))
                                        .setGas(2798248)
                                        .setRecipientContract(spec.registry().getContractId(TRACEABILITY + SECOND))
                                        .setGasUsed(28692)
                                        .setOutput(EMPTY)
                                        .build())))),
                expectContractBytecodeSidecarFor(
                        SECOND_CREATE_TXN,
                        TRACEABILITY + SECOND,
                        TRACEABILITY,
                        BigInteger.ZERO,
                        BigInteger.ZERO,
                        BigInteger.valueOf(3)),
                contractCustomCreate(TRACEABILITY, THIRD, BigInteger.ZERO, BigInteger.ONE, BigInteger.ZERO)
                        .via(THIRD_CREATE_TXN),
                expectContractStateChangesSidecarFor(
                        THIRD_CREATE_TXN,
                        List.of(StateChange.stateChangeFor(TRACEABILITY + THIRD)
                                .withStorageChanges(
                                        StorageChange.readAndWritten(
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(0)),
                                        StorageChange.readAndWritten(
                                                formattedAssertionValue(1),
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(1)),
                                        StorageChange.readAndWritten(
                                                formattedAssertionValue(2),
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(0))))),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        expectContractActionSidecarFor(
                                THIRD_CREATE_TXN,
                                List.of(ContractAction.newBuilder()
                                        .setCallType(CREATE)
                                        .setCallOperationType(CallOperationType.OP_CREATE)
                                        .setCallingAccount(TxnUtils.asId(GENESIS, spec))
                                        .setGas(2798248)
                                        .setRecipientContract(spec.registry().getContractId(TRACEABILITY + THIRD))
                                        .setGasUsed(28692)
                                        .setOutput(EMPTY)
                                        .build())))),
                expectContractBytecodeSidecarFor(
                        THIRD_CREATE_TXN,
                        TRACEABILITY + THIRD,
                        TRACEABILITY,
                        BigInteger.ZERO,
                        BigInteger.ONE,
                        BigInteger.ZERO),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCall(
                                        TRACEABILITY,
                                        "eetScenario6",
                                        asHeadlongAddress(getNestedContractAddress(TRACEABILITY + "B", spec)),
                                        asHeadlongAddress(getNestedContractAddress(TRACEABILITY + "C", spec)))
                                .gas(1_000_000)
                                .via(TRACEABILITY_TXN))),
                expectContractStateChangesSidecarFor(
                        TRACEABILITY_TXN,
                        List.of(
                                StateChange.stateChangeFor(TRACEABILITY)
                                        .withStorageChanges(
                                                StorageChange.onlyRead(
                                                        formattedAssertionValue(0), formattedAssertionValue(2)),
                                                StorageChange.readAndWritten(
                                                        formattedAssertionValue(1),
                                                        formattedAssertionValue(3),
                                                        formattedAssertionValue(4)),
                                                StorageChange.readAndWritten(
                                                        formattedAssertionValue(2),
                                                        formattedAssertionValue(4),
                                                        formattedAssertionValue(5))),
                                StateChange.stateChangeFor(TRACEABILITY + THIRD)
                                        .withStorageChanges(
                                                StorageChange.onlyRead(
                                                        formattedAssertionValue(0), formattedAssertionValue(0)),
                                                StorageChange.onlyRead(
                                                        formattedAssertionValue(1), formattedAssertionValue(1))))),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        expectContractActionSidecarFor(
                                TRACEABILITY_TXN,
                                List.of(
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallingAccount(TxnUtils.asId(GENESIS, spec))
                                                .setCallOperationType(CallOperationType.OP_CALL)
                                                .setGas(978632)
                                                .setGasUsed(29910)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY))
                                                .setOutput(EMPTY)
                                                .setInput(encodeFunctionCall(
                                                        TRACEABILITY,
                                                        "eetScenario6",
                                                        hexedSolidityAddressToHeadlongAddress(
                                                                getNestedContractAddress(TRACEABILITY + "B", spec)),
                                                        hexedSolidityAddressToHeadlongAddress(
                                                                getNestedContractAddress(TRACEABILITY + "C", spec))))
                                                .build(),
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallOperationType(CallOperationType.OP_CALL)
                                                .setCallingContract(
                                                        spec.registry().getContractId(TRACEABILITY))
                                                .setGas(962720)
                                                .setGasUsed(2347)
                                                .setCallDepth(1)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY))
                                                .setOutput(uint256ReturnWithValue(BigInteger.valueOf(2)))
                                                .setInput(encodeFunctionCall(TRACEABILITY, GET_ZERO_SLOT))
                                                .build(),
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallOperationType(CallOperationType.OP_CALL)
                                                .setCallingContract(
                                                        spec.registry().getContractId(TRACEABILITY))
                                                .setCallDepth(1)
                                                .setGas(959938)
                                                .setGasUsed(5324)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY))
                                                .setOutput(EMPTY)
                                                .setInput(encodeFunctionCall(
                                                        TRACEABILITY, SET_FIRST_SLOT, BigInteger.valueOf(4)))
                                                .build(),
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallOperationType(CallOperationType.OP_CALL)
                                                .setCallingContract(
                                                        spec.registry().getContractId(TRACEABILITY))
                                                .setGas(954344)
                                                .setGasUsed(5810)
                                                .setCallDepth(1)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY))
                                                .setOutput(EMPTY)
                                                .setInput(encodeFunctionCall(
                                                        TRACEABILITY,
                                                        DELEGATE_CALL_ADDRESS_GET_SLOT_2,
                                                        hexedSolidityAddressToHeadlongAddress(
                                                                getNestedContractAddress(TRACEABILITY + SECOND, spec))))
                                                .build(),
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallOperationType(CallOperationType.OP_DELEGATECALL)
                                                .setCallingContract(
                                                        spec.registry().getContractId(TRACEABILITY))
                                                .setGas(936124)
                                                .setGasUsed(2315)
                                                .setCallDepth(2)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY + SECOND))
                                                .setOutput(uint256ReturnWithValue(BigInteger.valueOf(4)))
                                                .setInput(encodeFunctionCall(TRACEABILITY, GET_SECOND_SLOT))
                                                .build(),
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallOperationType(CallOperationType.OP_CALL)
                                                .setCallingContract(
                                                        spec.registry().getContractId(TRACEABILITY))
                                                .setGas(948254)
                                                .setGasUsed(4209)
                                                .setCallDepth(1)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY))
                                                .setOutput(EMPTY)
                                                .setInput(encodeFunctionCall(
                                                        TRACEABILITY,
                                                        "delegateCallAddressSetSlot2",
                                                        hexedSolidityAddressToHeadlongAddress(
                                                                getNestedContractAddress(TRACEABILITY + SECOND, spec)),
                                                        BigInteger.valueOf(5)))
                                                .build(),
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallOperationType(CallOperationType.OP_DELEGATECALL)
                                                .setCallingContract(
                                                        spec.registry().getContractId(TRACEABILITY))
                                                .setGas(932487)
                                                .setGasUsed(3180)
                                                .setCallDepth(2)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY + SECOND))
                                                .setOutput(EMPTY)
                                                .setInput(encodeFunctionCall(
                                                        TRACEABILITY, SET_SECOND_SLOT, BigInteger.valueOf(5)))
                                                .build(),
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallOperationType(CallOperationType.OP_DELEGATECALL)
                                                .setCallingContract(
                                                        spec.registry().getContractId(TRACEABILITY))
                                                .setGas(943521)
                                                .setGasUsed(5777)
                                                .setCallDepth(1)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY + SECOND))
                                                .setOutput(EMPTY)
                                                .setInput(encodeFunctionCall(
                                                        TRACEABILITY,
                                                        "staticCallAddressGetSlot0",
                                                        hexedSolidityAddressToHeadlongAddress(
                                                                getNestedContractAddress(TRACEABILITY + THIRD, spec))))
                                                .build(),
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallOperationType(CallOperationType.OP_STATICCALL)
                                                .setCallingContract(
                                                        spec.registry().getContractId(TRACEABILITY + SECOND))
                                                .setGas(925534)
                                                .setGasUsed(2347)
                                                .setCallDepth(2)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY + THIRD))
                                                .setOutput(uint256ReturnWithValue(BigInteger.valueOf(0)))
                                                .setInput(encodeFunctionCall(TRACEABILITY, GET_ZERO_SLOT))
                                                .build(),
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallOperationType(CallOperationType.OP_DELEGATECALL)
                                                .setCallingContract(
                                                        spec.registry().getContractId(TRACEABILITY))
                                                .setGas(937229)
                                                .setGasUsed(3320)
                                                .setCallDepth(1)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY + SECOND))
                                                .setOutput(EMPTY)
                                                .setInput(encodeFunctionCall(
                                                        TRACEABILITY,
                                                        "staticCallAddressGetSlot1",
                                                        hexedSolidityAddressToHeadlongAddress(
                                                                getNestedContractAddress(TRACEABILITY + THIRD, spec))))
                                                .build(),
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallOperationType(CallOperationType.OP_STATICCALL)
                                                .setCallingContract(
                                                        spec.registry().getContractId(TRACEABILITY + SECOND))
                                                .setGas(921803)
                                                .setGasUsed(2391)
                                                .setCallDepth(2)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY + THIRD))
                                                .setOutput(uint256ReturnWithValue(BigInteger.valueOf(1)))
                                                .setInput(encodeFunctionCall(TRACEABILITY, GET_FIRST_SLOT))
                                                .build())))));
    }

    @HapiTest
    @Order(7)
    final Stream<DynamicTest> traceabilityE2EScenario7() {
        return hapiTest(
                uploadInitCode(TRACEABILITY_CALLCODE),
                contractCreate(TRACEABILITY_CALLCODE, BigInteger.valueOf(55), BigInteger.TWO, BigInteger.TWO)
                        .via(FIRST_CREATE_TXN),
                expectContractStateChangesSidecarFor(
                        FIRST_CREATE_TXN,
                        List.of(StateChange.stateChangeFor(TRACEABILITY_CALLCODE)
                                .withStorageChanges(
                                        StorageChange.readAndWritten(
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(55)),
                                        StorageChange.readAndWritten(
                                                formattedAssertionValue(1),
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(2)),
                                        StorageChange.readAndWritten(
                                                formattedAssertionValue(2),
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(2))))),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        expectContractActionSidecarFor(
                                FIRST_CREATE_TXN,
                                List.of(ContractAction.newBuilder()
                                        .setCallType(CREATE)
                                        .setCallOperationType(CallOperationType.OP_CREATE)
                                        .setCallingAccount(TxnUtils.asId(GENESIS, spec))
                                        .setGas(2865992)
                                        .setRecipientContract(spec.registry().getContractId(TRACEABILITY_CALLCODE))
                                        .setGasUsed(67632)
                                        .setOutput(EMPTY)
                                        .build())))),
                expectContractBytecodeSidecarFor(
                        FIRST_CREATE_TXN,
                        TRACEABILITY_CALLCODE,
                        TRACEABILITY_CALLCODE,
                        BigInteger.valueOf(55),
                        BigInteger.TWO,
                        BigInteger.TWO),
                contractCustomCreate(
                                TRACEABILITY_CALLCODE, SECOND, BigInteger.ZERO, BigInteger.ZERO, BigInteger.valueOf(12))
                        .via(SECOND_CREATE_TXN),
                expectContractStateChangesSidecarFor(
                        SECOND_CREATE_TXN,
                        List.of(StateChange.stateChangeFor(TRACEABILITY_CALLCODE + SECOND)
                                .withStorageChanges(
                                        StorageChange.readAndWritten(
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(0)),
                                        StorageChange.readAndWritten(
                                                formattedAssertionValue(1),
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(0)),
                                        StorageChange.readAndWritten(
                                                formattedAssertionValue(2),
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(12))))),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        expectContractActionSidecarFor(
                                SECOND_CREATE_TXN,
                                List.of(ContractAction.newBuilder()
                                        .setCallType(CREATE)
                                        .setCallOperationType(CallOperationType.OP_CREATE)
                                        .setCallingAccount(TxnUtils.asId(GENESIS, spec))
                                        .setGas(2866016)
                                        .setRecipientContract(
                                                spec.registry().getContractId(TRACEABILITY_CALLCODE + SECOND))
                                        .setGasUsed(27832)
                                        .setOutput(EMPTY)
                                        .build())))),
                expectContractBytecodeSidecarFor(
                        SECOND_CREATE_TXN,
                        TRACEABILITY_CALLCODE + SECOND,
                        TRACEABILITY_CALLCODE,
                        BigInteger.ZERO,
                        BigInteger.ZERO,
                        BigInteger.valueOf(12)),
                contractCustomCreate(
                                TRACEABILITY_CALLCODE, THIRD, BigInteger.valueOf(4), BigInteger.ONE, BigInteger.ZERO)
                        .via(THIRD_CREATE_TXN),
                expectContractStateChangesSidecarFor(
                        THIRD_CREATE_TXN,
                        List.of(StateChange.stateChangeFor(TRACEABILITY_CALLCODE + THIRD)
                                .withStorageChanges(
                                        StorageChange.readAndWritten(
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(4)),
                                        StorageChange.readAndWritten(
                                                formattedAssertionValue(1),
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(1)),
                                        StorageChange.readAndWritten(
                                                formattedAssertionValue(2),
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(0))))),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        expectContractActionSidecarFor(
                                THIRD_CREATE_TXN,
                                List.of(ContractAction.newBuilder()
                                        .setCallType(CREATE)
                                        .setCallOperationType(CallOperationType.OP_CREATE)
                                        .setCallingAccount(TxnUtils.asId(GENESIS, spec))
                                        .setGas(2866004)
                                        .setRecipientContract(
                                                spec.registry().getContractId(TRACEABILITY_CALLCODE + THIRD))
                                        .setGasUsed(47732)
                                        .setOutput(EMPTY)
                                        .build())))),
                expectContractBytecodeSidecarFor(
                        THIRD_CREATE_TXN,
                        TRACEABILITY_CALLCODE + THIRD,
                        TRACEABILITY_CALLCODE,
                        BigInteger.valueOf(4),
                        BigInteger.ONE,
                        BigInteger.ZERO),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCall(
                                        TRACEABILITY_CALLCODE,
                                        "eetScenario7",
                                        asHeadlongAddress(getNestedContractAddress(TRACEABILITY_CALLCODE + "B", spec)),
                                        asHeadlongAddress(getNestedContractAddress(TRACEABILITY_CALLCODE + "C", spec)))
                                .gas(1_000_000)
                                .via(TRACEABILITY_TXN))),
                expectContractStateChangesSidecarFor(
                        TRACEABILITY_TXN,
                        List.of(
                                StateChange.stateChangeFor(TRACEABILITY_CALLCODE)
                                        .withStorageChanges(
                                                StorageChange.onlyRead(
                                                        formattedAssertionValue(0), formattedAssertionValue(55)),
                                                StorageChange.readAndWritten(
                                                        formattedAssertionValue(1),
                                                        formattedAssertionValue(2),
                                                        formattedAssertionValue(55252))),
                                StateChange.stateChangeFor(TRACEABILITY_CALLCODE + SECOND)
                                        .withStorageChanges(
                                                StorageChange.readAndWritten(
                                                        formattedAssertionValue(0),
                                                        formattedAssertionValue(0),
                                                        formattedAssertionValue(54)),
                                                StorageChange.readAndWritten(
                                                        formattedAssertionValue(1),
                                                        formattedAssertionValue(0),
                                                        formattedAssertionValue(0)),
                                                StorageChange.readAndWritten(
                                                        formattedAssertionValue(2),
                                                        formattedAssertionValue(12),
                                                        formattedAssertionValue(524))))),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        expectContractActionSidecarFor(
                                TRACEABILITY_TXN,
                                List.of(
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallingAccount(TxnUtils.asId(GENESIS, spec))
                                                .setCallOperationType(CallOperationType.OP_CALL)
                                                .setGas(978632)
                                                .setGasUsed(51483)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY_CALLCODE))
                                                .setOutput(EMPTY)
                                                .setInput(encodeFunctionCall(
                                                        TRACEABILITY_CALLCODE,
                                                        "eetScenario7",
                                                        hexedSolidityAddressToHeadlongAddress(
                                                                getNestedContractAddress(
                                                                        TRACEABILITY_CALLCODE + "B", spec)),
                                                        hexedSolidityAddressToHeadlongAddress(
                                                                getNestedContractAddress(
                                                                        TRACEABILITY_CALLCODE + "C", spec))))
                                                .build(),
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallOperationType(CallOperationType.OP_CALL)
                                                .setCallingContract(
                                                        spec.registry().getContractId(TRACEABILITY_CALLCODE))
                                                .setGas(962797)
                                                .setGasUsed(2500)
                                                .setCallDepth(1)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY_CALLCODE))
                                                .setOutput(uint256ReturnWithValue(BigInteger.valueOf(55)))
                                                .setInput(encodeFunctionCall(TRACEABILITY_CALLCODE, GET_ZERO_SLOT))
                                                .build(),
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallOperationType(CallOperationType.OP_CALL)
                                                .setCallingContract(
                                                        spec.registry().getContractId(TRACEABILITY_CALLCODE))
                                                .setCallDepth(1)
                                                .setGas(959897)
                                                .setGasUsed(5249)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY_CALLCODE))
                                                .setOutput(EMPTY)
                                                .setInput(encodeFunctionCall(
                                                        TRACEABILITY_CALLCODE,
                                                        SET_FIRST_SLOT,
                                                        BigInteger.valueOf(55252)))
                                                .build(),
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallOperationType(CallOperationType.OP_CALL)
                                                .setCallingContract(
                                                        spec.registry().getContractId(TRACEABILITY_CALLCODE))
                                                .setGas(951931)
                                                .setGasUsed(2368)
                                                .setCallDepth(1)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY_CALLCODE + SECOND))
                                                .setOutput(uint256ReturnWithValue(BigInteger.valueOf(12)))
                                                .setInput(encodeFunctionCall(TRACEABILITY_CALLCODE, GET_SECOND_SLOT))
                                                .build(),
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallOperationType(CallOperationType.OP_CALL)
                                                .setCallingContract(
                                                        spec.registry().getContractId(TRACEABILITY_CALLCODE))
                                                .setGas(949163)
                                                .setGasUsed(3215)
                                                .setCallDepth(1)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY_CALLCODE + SECOND))
                                                .setOutput(EMPTY)
                                                .setInput(encodeFunctionCall(
                                                        TRACEABILITY, SET_SECOND_SLOT, BigInteger.valueOf(524)))
                                                .build(),
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallOperationType(CallOperationType.OP_CALL)
                                                .setCallingContract(
                                                        spec.registry().getContractId(TRACEABILITY_CALLCODE))
                                                .setGas(945630)
                                                .setGasUsed(6069)
                                                .setCallDepth(1)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY_CALLCODE + SECOND))
                                                .setOutput(EMPTY)
                                                .setInput(encodeFunctionCall(
                                                        TRACEABILITY_CALLCODE,
                                                        "callcodeAddressGetSlot0",
                                                        hexedSolidityAddressToHeadlongAddress(
                                                                getNestedContractAddress(
                                                                        TRACEABILITY_CALLCODE + THIRD, spec))))
                                                .build(),
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallOperationType(CallOperationType.OP_CALLCODE)
                                                .setCallingContract(
                                                        spec.registry().getContractId(TRACEABILITY_CALLCODE + SECOND))
                                                .setGas(927361)
                                                .setGasUsed(2500)
                                                .setCallDepth(2)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY_CALLCODE + THIRD))
                                                .setOutput(uint256ReturnWithValue(BigInteger.valueOf(0)))
                                                .setInput(encodeFunctionCall(TRACEABILITY_CALLCODE, GET_ZERO_SLOT)
                                                        .concat(CALL_CODE_INPUT_SUFFIX))
                                                .build(),
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallOperationType(CallOperationType.OP_CALL)
                                                .setCallingContract(
                                                        spec.registry().getContractId(TRACEABILITY_CALLCODE))
                                                .setGas(939264)
                                                .setGasUsed(21544)
                                                .setCallDepth(1)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY_CALLCODE + SECOND))
                                                .setOutput(EMPTY)
                                                .setInput(encodeFunctionCall(
                                                        TRACEABILITY_CALLCODE,
                                                        "callcodeAddressSetSlot0",
                                                        hexedSolidityAddressToHeadlongAddress(
                                                                getNestedContractAddress(
                                                                        TRACEABILITY_CALLCODE + THIRD, spec)),
                                                        BigInteger.valueOf(54)))
                                                .build(),
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallOperationType(CallOperationType.OP_CALLCODE)
                                                .setCallingContract(
                                                        spec.registry().getContractId(TRACEABILITY_CALLCODE + SECOND))
                                                .setGas(923465)
                                                .setGasUsed(20381)
                                                .setCallDepth(2)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY_CALLCODE + THIRD))
                                                .setOutput(EMPTY)
                                                .setInput(encodeFunctionCall(
                                                                TRACEABILITY_CALLCODE,
                                                                SET_ZERO_SLOT,
                                                                BigInteger.valueOf(54))
                                                        .concat(CALL_CODE_INPUT_SUFFIX))
                                                .build(),
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallOperationType(CallOperationType.OP_CALL)
                                                .setCallingContract(
                                                        spec.registry().getContractId(TRACEABILITY_CALLCODE))
                                                .setGas(917687)
                                                .setGasUsed(3393)
                                                .setCallDepth(1)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY_CALLCODE + SECOND))
                                                .setOutput(EMPTY)
                                                .setInput(encodeFunctionCall(
                                                        TRACEABILITY_CALLCODE,
                                                        "callcodeAddressGetSlot1",
                                                        hexedSolidityAddressToHeadlongAddress(
                                                                getNestedContractAddress(
                                                                        TRACEABILITY_CALLCODE + THIRD, spec))))
                                                .build(),
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallOperationType(CallOperationType.OP_CALLCODE)
                                                .setCallingContract(
                                                        spec.registry().getContractId(TRACEABILITY_CALLCODE + SECOND))
                                                .setGas(902511)
                                                .setGasUsed(2522)
                                                .setCallDepth(2)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY_CALLCODE + THIRD))
                                                .setOutput(uint256ReturnWithValue(BigInteger.valueOf(0)))
                                                .setInput(encodeFunctionCall(TRACEABILITY_CALLCODE, GET_FIRST_SLOT)
                                                        .concat(CALL_CODE_INPUT_SUFFIX))
                                                .build(),
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallOperationType(CallOperationType.OP_CALL)
                                                .setCallingContract(
                                                        spec.registry().getContractId(TRACEABILITY_CALLCODE))
                                                .setGas(913958)
                                                .setGasUsed(1270)
                                                .setCallDepth(1)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY_CALLCODE + SECOND))
                                                .setOutput(EMPTY)
                                                .setInput(encodeFunctionCall(
                                                        TRACEABILITY_CALLCODE,
                                                        "callcodeAddressSetSlot1",
                                                        hexedSolidityAddressToHeadlongAddress(
                                                                getNestedContractAddress(
                                                                        TRACEABILITY_CALLCODE + THIRD, spec)),
                                                        BigInteger.valueOf(0)))
                                                .build(),
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallOperationType(CallOperationType.OP_CALLCODE)
                                                .setCallingContract(
                                                        spec.registry().getContractId(TRACEABILITY_CALLCODE + SECOND))
                                                .setGas(898793)
                                                .setGasUsed(349)
                                                .setCallDepth(2)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY_CALLCODE + THIRD))
                                                .setOutput(EMPTY)
                                                .setInput(encodeFunctionCall(
                                                                TRACEABILITY_CALLCODE,
                                                                SET_FIRST_SLOT,
                                                                BigInteger.valueOf(0))
                                                        .concat(CALL_CODE_INPUT_SUFFIX))
                                                .build())))));
    }

    @HapiTest
    @Order(8)
    final Stream<DynamicTest> traceabilityE2EScenario8() {
        return hapiTest(
                uploadInitCode(TRACEABILITY_CALLCODE),
                contractCreate(TRACEABILITY_CALLCODE, BigInteger.valueOf(55), BigInteger.TWO, BigInteger.TWO)
                        .via(FIRST_CREATE_TXN),
                expectContractStateChangesSidecarFor(
                        FIRST_CREATE_TXN,
                        List.of(StateChange.stateChangeFor(TRACEABILITY_CALLCODE)
                                .withStorageChanges(
                                        StorageChange.readAndWritten(
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(55)),
                                        StorageChange.readAndWritten(
                                                formattedAssertionValue(1),
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(2)),
                                        StorageChange.readAndWritten(
                                                formattedAssertionValue(2),
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(2))))),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        expectContractActionSidecarFor(
                                FIRST_CREATE_TXN,
                                List.of(ContractAction.newBuilder()
                                        .setCallType(CREATE)
                                        .setCallOperationType(CallOperationType.OP_CREATE)
                                        .setCallingAccount(TxnUtils.asId(GENESIS, spec))
                                        .setGas(2865992)
                                        .setRecipientContract(spec.registry().getContractId(TRACEABILITY_CALLCODE))
                                        .setGasUsed(67632)
                                        .setOutput(EMPTY)
                                        .build())))),
                expectContractBytecodeSidecarFor(
                        FIRST_CREATE_TXN,
                        TRACEABILITY_CALLCODE,
                        TRACEABILITY_CALLCODE,
                        BigInteger.valueOf(55),
                        BigInteger.TWO,
                        BigInteger.TWO),
                contractCustomCreate(
                                TRACEABILITY_CALLCODE, SECOND, BigInteger.ZERO, BigInteger.ZERO, BigInteger.valueOf(12))
                        .via(SECOND_CREATE_TXN),
                expectContractStateChangesSidecarFor(
                        SECOND_CREATE_TXN,
                        List.of(StateChange.stateChangeFor(TRACEABILITY_CALLCODE + SECOND)
                                .withStorageChanges(
                                        StorageChange.readAndWritten(
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(0)),
                                        StorageChange.readAndWritten(
                                                formattedAssertionValue(1),
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(0)),
                                        StorageChange.readAndWritten(
                                                formattedAssertionValue(2),
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(12))))),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        expectContractActionSidecarFor(
                                SECOND_CREATE_TXN,
                                List.of(ContractAction.newBuilder()
                                        .setCallType(CREATE)
                                        .setCallOperationType(CallOperationType.OP_CREATE)
                                        .setCallingAccount(TxnUtils.asId(GENESIS, spec))
                                        .setGas(2866016)
                                        .setRecipientContract(
                                                spec.registry().getContractId(TRACEABILITY_CALLCODE + SECOND))
                                        .setGasUsed(27832)
                                        .setOutput(EMPTY)
                                        .build())))),
                expectContractBytecodeSidecarFor(
                        SECOND_CREATE_TXN,
                        TRACEABILITY_CALLCODE + SECOND,
                        TRACEABILITY_CALLCODE,
                        BigInteger.ZERO,
                        BigInteger.ZERO,
                        BigInteger.valueOf(12)),
                contractCustomCreate(
                                TRACEABILITY_CALLCODE, THIRD, BigInteger.valueOf(4), BigInteger.ONE, BigInteger.ZERO)
                        .via(THIRD_CREATE_TXN),
                expectContractStateChangesSidecarFor(
                        THIRD_CREATE_TXN,
                        List.of(StateChange.stateChangeFor(TRACEABILITY_CALLCODE + THIRD)
                                .withStorageChanges(
                                        StorageChange.readAndWritten(
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(4)),
                                        StorageChange.readAndWritten(
                                                formattedAssertionValue(1),
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(1)),
                                        StorageChange.readAndWritten(
                                                formattedAssertionValue(2),
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(0))))),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        expectContractActionSidecarFor(
                                THIRD_CREATE_TXN,
                                List.of(ContractAction.newBuilder()
                                        .setCallType(CREATE)
                                        .setCallOperationType(CallOperationType.OP_CREATE)
                                        .setCallingAccount(TxnUtils.asId(GENESIS, spec))
                                        .setGas(2866004)
                                        .setRecipientContract(
                                                spec.registry().getContractId(TRACEABILITY_CALLCODE + THIRD))
                                        .setGasUsed(47732)
                                        .setOutput(EMPTY)
                                        .build())))),
                expectContractBytecodeSidecarFor(
                        THIRD_CREATE_TXN,
                        TRACEABILITY_CALLCODE + THIRD,
                        TRACEABILITY_CALLCODE,
                        BigInteger.valueOf(4),
                        BigInteger.ONE,
                        BigInteger.ZERO),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCall(
                                        TRACEABILITY_CALLCODE,
                                        "eetScenario8",
                                        asHeadlongAddress(getNestedContractAddress(TRACEABILITY_CALLCODE + "B", spec)),
                                        asHeadlongAddress(getNestedContractAddress(TRACEABILITY_CALLCODE + "C", spec)))
                                .gas(1_000_000)
                                .via(TRACEABILITY_TXN))),
                expectContractStateChangesSidecarFor(
                        TRACEABILITY_TXN,
                        List.of(StateChange.stateChangeFor(TRACEABILITY_CALLCODE)
                                .withStorageChanges(
                                        StorageChange.readAndWritten(
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(55),
                                                formattedAssertionValue(55)),
                                        StorageChange.readAndWritten(
                                                formattedAssertionValue(1),
                                                formattedAssertionValue(2),
                                                formattedAssertionValue(55252)),
                                        StorageChange.readAndWritten(
                                                formattedAssertionValue(2),
                                                formattedAssertionValue(2),
                                                formattedAssertionValue(524))))),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        expectContractActionSidecarFor(
                                TRACEABILITY_TXN,
                                List.of(
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallingAccount(TxnUtils.asId(GENESIS, spec))
                                                .setCallOperationType(CallOperationType.OP_CALL)
                                                .setGas(978632)
                                                .setGasUsed(29301)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY_CALLCODE))
                                                .setOutput(EMPTY)
                                                .setInput(encodeFunctionCall(
                                                        TRACEABILITY_CALLCODE,
                                                        "eetScenario8",
                                                        hexedSolidityAddressToHeadlongAddress(
                                                                getNestedContractAddress(
                                                                        TRACEABILITY_CALLCODE + "B", spec)),
                                                        hexedSolidityAddressToHeadlongAddress(
                                                                getNestedContractAddress(
                                                                        TRACEABILITY_CALLCODE + "C", spec))))
                                                .build(),
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallOperationType(CallOperationType.OP_CALL)
                                                .setCallingContract(
                                                        spec.registry().getContractId(TRACEABILITY_CALLCODE))
                                                .setGas(962562)
                                                .setGasUsed(2500)
                                                .setCallDepth(1)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY_CALLCODE))
                                                .setOutput(uint256ReturnWithValue(BigInteger.valueOf(55)))
                                                .setInput(encodeFunctionCall(TRACEABILITY_CALLCODE, GET_ZERO_SLOT))
                                                .build(),
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallOperationType(CallOperationType.OP_CALL)
                                                .setCallingContract(
                                                        spec.registry().getContractId(TRACEABILITY_CALLCODE))
                                                .setCallDepth(1)
                                                .setGas(959662)
                                                .setGasUsed(3281)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY_CALLCODE))
                                                .setOutput(EMPTY)
                                                .setInput(encodeFunctionCall(
                                                        TRACEABILITY_CALLCODE, SET_ZERO_SLOT, BigInteger.valueOf(2)))
                                                .build(),
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallOperationType(CallOperationType.OP_CALL)
                                                .setCallingContract(
                                                        spec.registry().getContractId(TRACEABILITY_CALLCODE))
                                                .setGas(956103)
                                                .setGasUsed(2522)
                                                .setCallDepth(1)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY_CALLCODE))
                                                .setOutput(uint256ReturnWithValue(BigInteger.valueOf(2)))
                                                .setInput(encodeFunctionCall(TRACEABILITY_CALLCODE, GET_FIRST_SLOT))
                                                .build(),
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallOperationType(CallOperationType.OP_CALL)
                                                .setCallingContract(
                                                        spec.registry().getContractId(TRACEABILITY_CALLCODE))
                                                .setCallDepth(1)
                                                .setGas(953185)
                                                .setGasUsed(3149)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY_CALLCODE))
                                                .setOutput(EMPTY)
                                                .setInput(encodeFunctionCall(
                                                        TRACEABILITY_CALLCODE,
                                                        SET_FIRST_SLOT,
                                                        BigInteger.valueOf(55252)))
                                                .build(),
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallOperationType(CallOperationType.OP_CALL)
                                                .setCallingContract(
                                                        spec.registry().getContractId(TRACEABILITY_CALLCODE))
                                                .setGas(949717)
                                                .setGasUsed(5783)
                                                .setCallDepth(1)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY_CALLCODE))
                                                .setOutput(EMPTY)
                                                .setInput(encodeFunctionCall(
                                                        TRACEABILITY_CALLCODE,
                                                        "callcodeAddressGetSlot2",
                                                        hexedSolidityAddressToHeadlongAddress(
                                                                getNestedContractAddress(
                                                                        TRACEABILITY_CALLCODE + SECOND, spec))))
                                                .build(),
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallOperationType(CallOperationType.OP_CALLCODE)
                                                .setCallingContract(
                                                        spec.registry().getContractId(TRACEABILITY_CALLCODE))
                                                .setGas(931536)
                                                .setGasUsed(2368)
                                                .setCallDepth(2)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY_CALLCODE + SECOND))
                                                .setOutput(uint256ReturnWithValue(BigInteger.valueOf(2)))
                                                .setInput(encodeFunctionCall(TRACEABILITY_CALLCODE, GET_SECOND_SLOT)
                                                        .concat(CALL_CODE_INPUT_SUFFIX))
                                                .build(),
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallOperationType(CallOperationType.OP_CALL)
                                                .setCallingContract(
                                                        spec.registry().getContractId(TRACEABILITY_CALLCODE))
                                                .setGas(943633)
                                                .setGasUsed(4290)
                                                .setCallDepth(1)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY_CALLCODE))
                                                .setOutput(EMPTY)
                                                .setInput(encodeFunctionCall(
                                                        TRACEABILITY_CALLCODE,
                                                        "callcodeAddressSetSlot2",
                                                        hexedSolidityAddressToHeadlongAddress(
                                                                getNestedContractAddress(
                                                                        TRACEABILITY_CALLCODE + SECOND, spec)),
                                                        BigInteger.valueOf(524)))
                                                .build(),
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallOperationType(CallOperationType.OP_CALLCODE)
                                                .setCallingContract(
                                                        spec.registry().getContractId(TRACEABILITY_CALLCODE))
                                                .setGas(927853)
                                                .setGasUsed(3215)
                                                .setCallDepth(2)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY_CALLCODE + SECOND))
                                                .setOutput(EMPTY)
                                                .setInput(encodeFunctionCall(
                                                                TRACEABILITY_CALLCODE,
                                                                SET_SECOND_SLOT,
                                                                BigInteger.valueOf(524))
                                                        .concat(CALL_CODE_INPUT_SUFFIX))
                                                .build(),
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallOperationType(CallOperationType.OP_CALLCODE)
                                                .setCallingContract(
                                                        spec.registry().getContractId(TRACEABILITY_CALLCODE))
                                                .setGas(938599)
                                                .setGasUsed(4144)
                                                .setCallDepth(1)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY_CALLCODE + SECOND))
                                                .setOutput(EMPTY)
                                                .setInput(encodeFunctionCall(
                                                                TRACEABILITY_CALLCODE,
                                                                "callcodeAddressSetSlot0",
                                                                hexedSolidityAddressToHeadlongAddress(
                                                                        getNestedContractAddress(
                                                                                TRACEABILITY_CALLCODE + THIRD, spec)),
                                                                BigInteger.valueOf(55))
                                                        .concat(CALL_CODE_INPUT_SUFFIX))
                                                .build(),
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallOperationType(CallOperationType.OP_CALLCODE)
                                                .setCallingContract(
                                                        spec.registry().getContractId(TRACEABILITY_CALLCODE + SECOND))
                                                .setGas(920350)
                                                .setGasUsed(481)
                                                .setCallDepth(2)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY_CALLCODE + THIRD))
                                                .setOutput(EMPTY)
                                                .setInput(encodeFunctionCall(
                                                                TRACEABILITY_CALLCODE,
                                                                SET_ZERO_SLOT,
                                                                BigInteger.valueOf(55))
                                                        .concat(CALL_CODE_INPUT_SUFFIX))
                                                .build())))));
    }

    @HapiTest
    @Order(9)
    final Stream<DynamicTest> traceabilityE2EScenario9() {
        return hapiTest(
                uploadInitCode(TRACEABILITY),
                contractCreate(TRACEABILITY, BigInteger.valueOf(55), BigInteger.TWO, BigInteger.TWO)
                        .via(FIRST_CREATE_TXN)
                        .gas(2_500_000L),
                expectContractStateChangesSidecarFor(
                        FIRST_CREATE_TXN,
                        List.of(StateChange.stateChangeFor(TRACEABILITY)
                                .withStorageChanges(
                                        StorageChange.readAndWritten(
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(55)),
                                        StorageChange.readAndWritten(
                                                formattedAssertionValue(1),
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(2)),
                                        StorageChange.readAndWritten(
                                                formattedAssertionValue(2),
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(2))))),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        expectContractActionSidecarFor(
                                FIRST_CREATE_TXN,
                                List.of(ContractAction.newBuilder()
                                        .setCallType(CREATE)
                                        .setCallOperationType(CallOperationType.OP_CREATE)
                                        .setCallingAccount(TxnUtils.asId(GENESIS, spec))
                                        .setGas(2298224)
                                        .setRecipientContract(spec.registry().getContractId(TRACEABILITY))
                                        .setGasUsed(68492)
                                        .setOutput(EMPTY)
                                        .build())))),
                expectContractBytecodeSidecarFor(
                        FIRST_CREATE_TXN,
                        TRACEABILITY,
                        TRACEABILITY,
                        BigInteger.valueOf(55),
                        BigInteger.TWO,
                        BigInteger.TWO),
                contractCustomCreate(TRACEABILITY, SECOND, BigInteger.ZERO, BigInteger.ZERO, BigInteger.valueOf(12))
                        .via(SECOND_CREATE_TXN),
                expectContractStateChangesSidecarFor(
                        SECOND_CREATE_TXN,
                        List.of(StateChange.stateChangeFor(TRACEABILITY + SECOND)
                                .withStorageChanges(
                                        StorageChange.readAndWritten(
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(0)),
                                        StorageChange.readAndWritten(
                                                formattedAssertionValue(1),
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(0)),
                                        StorageChange.readAndWritten(
                                                formattedAssertionValue(2),
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(12))))),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        expectContractActionSidecarFor(
                                SECOND_CREATE_TXN,
                                List.of(ContractAction.newBuilder()
                                        .setCallType(CREATE)
                                        .setCallOperationType(CallOperationType.OP_CREATE)
                                        .setCallingAccount(TxnUtils.asId(GENESIS, spec))
                                        .setGas(2798248)
                                        .setRecipientContract(spec.registry().getContractId(TRACEABILITY + SECOND))
                                        .setGasUsed(28692)
                                        .setOutput(EMPTY)
                                        .build())))),
                expectContractBytecodeSidecarFor(
                        SECOND_CREATE_TXN,
                        TRACEABILITY + SECOND,
                        TRACEABILITY,
                        BigInteger.ZERO,
                        BigInteger.ZERO,
                        BigInteger.valueOf(12)),
                contractCustomCreate(TRACEABILITY, THIRD, BigInteger.ZERO, BigInteger.ONE, BigInteger.ZERO)
                        .via(THIRD_CREATE_TXN),
                expectContractStateChangesSidecarFor(
                        THIRD_CREATE_TXN,
                        List.of(StateChange.stateChangeFor(TRACEABILITY + THIRD)
                                .withStorageChanges(
                                        StorageChange.readAndWritten(
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(0)),
                                        StorageChange.readAndWritten(
                                                formattedAssertionValue(1),
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(1)),
                                        StorageChange.readAndWritten(
                                                formattedAssertionValue(2),
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(0))))),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        expectContractActionSidecarFor(
                                THIRD_CREATE_TXN,
                                List.of(ContractAction.newBuilder()
                                        .setCallType(CREATE)
                                        .setCallOperationType(CallOperationType.OP_CREATE)
                                        .setCallingAccount(TxnUtils.asId(GENESIS, spec))
                                        .setGas(2798248)
                                        .setRecipientContract(spec.registry().getContractId(TRACEABILITY + THIRD))
                                        .setGasUsed(28692)
                                        .setOutput(EMPTY)
                                        .build())))),
                expectContractBytecodeSidecarFor(
                        THIRD_CREATE_TXN,
                        TRACEABILITY + THIRD,
                        TRACEABILITY,
                        BigInteger.ZERO,
                        BigInteger.ONE,
                        BigInteger.ZERO),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCall(
                                        TRACEABILITY,
                                        "eetScenario9",
                                        asHeadlongAddress(getNestedContractAddress(TRACEABILITY + "B", spec)),
                                        asHeadlongAddress(getNestedContractAddress(TRACEABILITY + "C", spec)))
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED)
                                .gas(1_000_000)
                                .via(TRACEABILITY_TXN))),
                expectContractStateChangesSidecarFor(
                        TRACEABILITY_TXN,
                        List.of(
                                StateChange.stateChangeFor(TRACEABILITY)
                                        .withStorageChanges(
                                                StorageChange.onlyRead(
                                                        formattedAssertionValue(0), formattedAssertionValue(55)),
                                                StorageChange.onlyRead(
                                                        formattedAssertionValue(1), formattedAssertionValue(2))),
                                StateChange.stateChangeFor(TRACEABILITY + SECOND)
                                        .withStorageChanges(StorageChange.onlyRead(
                                                formattedAssertionValue(2), formattedAssertionValue(12))),
                                StateChange.stateChangeFor(TRACEABILITY + THIRD)
                                        .withStorageChanges(
                                                StorageChange.onlyRead(
                                                        formattedAssertionValue(0), formattedAssertionValue(0)),
                                                StorageChange.onlyRead(
                                                        formattedAssertionValue(1), formattedAssertionValue(1))))),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        expectContractActionSidecarFor(
                                TRACEABILITY_TXN,
                                List.of(
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallingAccount(TxnUtils.asId(GENESIS, spec))
                                                .setCallOperationType(CallOperationType.OP_CALL)
                                                .setGas(978632)
                                                .setGasUsed(50335)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY))
                                                .setRevertReason(EMPTY)
                                                .setInput(encodeFunctionCall(
                                                        TRACEABILITY,
                                                        "eetScenario9",
                                                        hexedSolidityAddressToHeadlongAddress(
                                                                getNestedContractAddress(TRACEABILITY + "B", spec)),
                                                        hexedSolidityAddressToHeadlongAddress(
                                                                getNestedContractAddress(TRACEABILITY + "C", spec))))
                                                .build(),
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallOperationType(CallOperationType.OP_CALL)
                                                .setCallingContract(
                                                        spec.registry().getContractId(TRACEABILITY))
                                                .setGas(962678)
                                                .setGasUsed(2347)
                                                .setCallDepth(1)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY))
                                                .setOutput(uint256ReturnWithValue(BigInteger.valueOf(55)))
                                                .setInput(encodeFunctionCall(TRACEABILITY, GET_ZERO_SLOT))
                                                .build(),
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallOperationType(CallOperationType.OP_CALL)
                                                .setCallingContract(
                                                        spec.registry().getContractId(TRACEABILITY))
                                                .setCallDepth(1)
                                                .setGas(959896)
                                                .setGasUsed(5324)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY))
                                                .setOutput(EMPTY)
                                                .setInput(encodeFunctionCall(
                                                        TRACEABILITY, SET_FIRST_SLOT, BigInteger.valueOf(55252)))
                                                .build(),
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallOperationType(CallOperationType.OP_CALL)
                                                .setCallingContract(
                                                        spec.registry().getContractId(TRACEABILITY))
                                                .setGas(951970)
                                                .setGasUsed(2315)
                                                .setCallDepth(1)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY + SECOND))
                                                .setOutput(uint256ReturnWithValue(BigInteger.valueOf(12)))
                                                .setInput(encodeFunctionCall(TRACEABILITY, GET_SECOND_SLOT))
                                                .build(),
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallOperationType(CallOperationType.OP_CALL)
                                                .setCallingContract(
                                                        spec.registry().getContractId(TRACEABILITY))
                                                .setGas(949204)
                                                .setGasUsed(3180)
                                                .setCallDepth(1)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY + SECOND))
                                                .setOutput(EMPTY)
                                                .setInput(encodeFunctionCall(
                                                        TRACEABILITY, SET_SECOND_SLOT, BigInteger.valueOf(524)))
                                                .build(),
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallOperationType(CallOperationType.OP_CALL)
                                                .setCallingContract(
                                                        spec.registry().getContractId(TRACEABILITY))
                                                .setGas(943262)
                                                .setGasUsed(29899)
                                                .setCallDepth(1)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY + THIRD))
                                                .setRevertReason(EMPTY)
                                                .setInput(encodeFunctionCall(
                                                        TRACEABILITY, "callToContractCForE2EScenario92"))
                                                .build(),
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallOperationType(CallOperationType.OP_CALL)
                                                .setCallingContract(
                                                        spec.registry().getContractId(TRACEABILITY + THIRD))
                                                .setGas(928136)
                                                .setGasUsed(2347)
                                                .setCallDepth(2)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY + THIRD))
                                                .setOutput(uint256ReturnWithValue(BigInteger.valueOf(0)))
                                                .setInput(encodeFunctionCall(TRACEABILITY, GET_ZERO_SLOT))
                                                .build(),
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallOperationType(CallOperationType.OP_CALL)
                                                .setCallingContract(
                                                        spec.registry().getContractId(TRACEABILITY + THIRD))
                                                .setGas(925354)
                                                .setGasUsed(20323)
                                                .setCallDepth(2)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY + THIRD))
                                                .setOutput(EMPTY)
                                                .setInput(encodeFunctionCall(
                                                        TRACEABILITY, SET_ZERO_SLOT, BigInteger.valueOf(55)))
                                                .build(),
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallOperationType(CallOperationType.OP_CALL)
                                                .setCallingContract(
                                                        spec.registry().getContractId(TRACEABILITY + THIRD))
                                                .setGas(905136)
                                                .setGasUsed(2391)
                                                .setCallDepth(2)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY + THIRD))
                                                .setOutput(uint256ReturnWithValue(BigInteger.valueOf(1)))
                                                .setInput(encodeFunctionCall(TRACEABILITY, GET_FIRST_SLOT))
                                                .build(),
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallOperationType(CallOperationType.OP_CALL)
                                                .setCallingContract(
                                                        spec.registry().getContractId(TRACEABILITY + THIRD))
                                                .setGas(902302)
                                                .setGasUsed(3224)
                                                .setCallDepth(2)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY + THIRD))
                                                .setOutput(EMPTY)
                                                .setInput(encodeFunctionCall(
                                                        TRACEABILITY, SET_FIRST_SLOT, BigInteger.valueOf(155)))
                                                .build())))));
    }

    @HapiTest
    @Order(10)
    final Stream<DynamicTest> traceabilityE2EScenario10() {
        return hapiTest(
                uploadInitCode(TRACEABILITY),
                contractCreate(TRACEABILITY, BigInteger.TWO, BigInteger.valueOf(3), BigInteger.valueOf(4))
                        .via(FIRST_CREATE_TXN)
                        .gas(2_500_000L),
                expectContractStateChangesSidecarFor(
                        FIRST_CREATE_TXN,
                        List.of(StateChange.stateChangeFor(TRACEABILITY)
                                .withStorageChanges(
                                        StorageChange.readAndWritten(
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(2)),
                                        StorageChange.readAndWritten(
                                                formattedAssertionValue(1),
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(3)),
                                        StorageChange.readAndWritten(
                                                formattedAssertionValue(2),
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(4))))),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        expectContractActionSidecarFor(
                                FIRST_CREATE_TXN,
                                List.of(ContractAction.newBuilder()
                                        .setCallType(CREATE)
                                        .setCallOperationType(CallOperationType.OP_CREATE)
                                        .setCallingAccount(TxnUtils.asId(GENESIS, spec))
                                        .setGas(2298224)
                                        .setRecipientContract(spec.registry().getContractId(TRACEABILITY))
                                        .setGasUsed(68492)
                                        .setOutput(EMPTY)
                                        .build())))),
                expectContractBytecodeSidecarFor(
                        FIRST_CREATE_TXN,
                        TRACEABILITY,
                        TRACEABILITY,
                        BigInteger.TWO,
                        BigInteger.valueOf(3),
                        BigInteger.valueOf(4)),
                contractCustomCreate(TRACEABILITY, SECOND, BigInteger.ZERO, BigInteger.ZERO, BigInteger.valueOf(3))
                        .via(SECOND_CREATE_TXN),
                expectContractStateChangesSidecarFor(
                        SECOND_CREATE_TXN,
                        List.of(StateChange.stateChangeFor(TRACEABILITY + SECOND)
                                .withStorageChanges(
                                        StorageChange.readAndWritten(
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(0)),
                                        StorageChange.readAndWritten(
                                                formattedAssertionValue(1),
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(0)),
                                        StorageChange.readAndWritten(
                                                formattedAssertionValue(2),
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(3))))),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        expectContractActionSidecarFor(
                                SECOND_CREATE_TXN,
                                List.of(ContractAction.newBuilder()
                                        .setCallType(CREATE)
                                        .setCallOperationType(CallOperationType.OP_CREATE)
                                        .setCallingAccount(TxnUtils.asId(GENESIS, spec))
                                        .setGas(2798248)
                                        .setRecipientContract(spec.registry().getContractId(TRACEABILITY + SECOND))
                                        .setGasUsed(28692)
                                        .setOutput(EMPTY)
                                        .build())))),
                expectContractBytecodeSidecarFor(
                        SECOND_CREATE_TXN,
                        TRACEABILITY + SECOND,
                        TRACEABILITY,
                        BigInteger.ZERO,
                        BigInteger.ZERO,
                        BigInteger.valueOf(3)),
                contractCustomCreate(TRACEABILITY, THIRD, BigInteger.ZERO, BigInteger.ONE, BigInteger.ZERO)
                        .via(THIRD_CREATE_TXN),
                expectContractStateChangesSidecarFor(
                        THIRD_CREATE_TXN,
                        List.of(StateChange.stateChangeFor(TRACEABILITY + THIRD)
                                .withStorageChanges(
                                        StorageChange.readAndWritten(
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(0)),
                                        StorageChange.readAndWritten(
                                                formattedAssertionValue(1),
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(1)),
                                        StorageChange.readAndWritten(
                                                formattedAssertionValue(2),
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(0))))),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        expectContractActionSidecarFor(
                                THIRD_CREATE_TXN,
                                List.of(ContractAction.newBuilder()
                                        .setCallType(CREATE)
                                        .setCallOperationType(CallOperationType.OP_CREATE)
                                        .setCallingAccount(TxnUtils.asId(GENESIS, spec))
                                        .setGas(2798248)
                                        .setRecipientContract(spec.registry().getContractId(TRACEABILITY + THIRD))
                                        .setGasUsed(28692)
                                        .setOutput(EMPTY)
                                        .build())))),
                expectContractBytecodeSidecarFor(
                        THIRD_CREATE_TXN,
                        TRACEABILITY + THIRD,
                        TRACEABILITY,
                        BigInteger.ZERO,
                        BigInteger.ONE,
                        BigInteger.ZERO),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCall(
                                        TRACEABILITY,
                                        "eetScenario10",
                                        asHeadlongAddress(getNestedContractAddress(TRACEABILITY + "B", spec)),
                                        asHeadlongAddress(getNestedContractAddress(TRACEABILITY + "C", spec)))
                                .gas(1_000_000)
                                .via(TRACEABILITY_TXN))),
                expectContractStateChangesSidecarFor(
                        TRACEABILITY_TXN,
                        List.of(
                                StateChange.stateChangeFor(TRACEABILITY)
                                        .withStorageChanges(
                                                StorageChange.onlyRead(
                                                        formattedAssertionValue(0), formattedAssertionValue(2)),
                                                StorageChange.readAndWritten(
                                                        formattedAssertionValue(1),
                                                        formattedAssertionValue(3),
                                                        formattedAssertionValue(4))),
                                StateChange.stateChangeFor(TRACEABILITY + SECOND)
                                        .withStorageChanges(StorageChange.readAndWritten(
                                                formattedAssertionValue(2),
                                                formattedAssertionValue(3),
                                                formattedAssertionValue(5))),
                                StateChange.stateChangeFor(TRACEABILITY + THIRD)
                                        .withStorageChanges(
                                                StorageChange.onlyRead(
                                                        formattedAssertionValue(0), formattedAssertionValue(0)),
                                                StorageChange.onlyRead(
                                                        formattedAssertionValue(1), formattedAssertionValue(1))))),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        expectContractActionSidecarFor(
                                TRACEABILITY_TXN,
                                List.of(
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallingAccount(TxnUtils.asId(GENESIS, spec))
                                                .setCallOperationType(CallOperationType.OP_CALL)
                                                .setGas(978632)
                                                .setGasUsed(52541)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY))
                                                .setOutput(EMPTY)
                                                .setInput(encodeFunctionCall(
                                                        TRACEABILITY,
                                                        "eetScenario10",
                                                        hexedSolidityAddressToHeadlongAddress(
                                                                getNestedContractAddress(TRACEABILITY + "B", spec)),
                                                        hexedSolidityAddressToHeadlongAddress(
                                                                getNestedContractAddress(TRACEABILITY + "C", spec))))
                                                .build(),
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallOperationType(CallOperationType.OP_CALL)
                                                .setCallingContract(
                                                        spec.registry().getContractId(TRACEABILITY))
                                                .setGas(962676)
                                                .setGasUsed(2347)
                                                .setCallDepth(1)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY))
                                                .setOutput(uint256ReturnWithValue(BigInteger.valueOf(2)))
                                                .setInput(encodeFunctionCall(TRACEABILITY, GET_ZERO_SLOT))
                                                .build(),
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallOperationType(CallOperationType.OP_CALL)
                                                .setCallingContract(
                                                        spec.registry().getContractId(TRACEABILITY))
                                                .setCallDepth(1)
                                                .setGas(959894)
                                                .setGasUsed(5324)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY))
                                                .setOutput(EMPTY)
                                                .setInput(encodeFunctionCall(
                                                        TRACEABILITY, SET_FIRST_SLOT, BigInteger.valueOf(4)))
                                                .build(),
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallOperationType(CallOperationType.OP_CALL)
                                                .setCallingContract(
                                                        spec.registry().getContractId(TRACEABILITY))
                                                .setGas(954300)
                                                .setGasUsed(5811)
                                                .setCallDepth(1)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY))
                                                .setOutput(EMPTY)
                                                .setInput(encodeFunctionCall(
                                                        TRACEABILITY,
                                                        "callAddressGetSlot2",
                                                        hexedSolidityAddressToHeadlongAddress(
                                                                getNestedContractAddress(TRACEABILITY + SECOND, spec))))
                                                .build(),
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallOperationType(CallOperationType.OP_CALL)
                                                .setCallingContract(
                                                        spec.registry().getContractId(TRACEABILITY))
                                                .setGas(936080)
                                                .setGasUsed(2315)
                                                .setCallDepth(2)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY + SECOND))
                                                .setOutput(uint256ReturnWithValue(BigInteger.valueOf(3)))
                                                .setInput(encodeFunctionCall(TRACEABILITY, GET_SECOND_SLOT))
                                                .build(),
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallOperationType(CallOperationType.OP_CALL)
                                                .setCallingContract(
                                                        spec.registry().getContractId(TRACEABILITY))
                                                .setGas(948209)
                                                .setGasUsed(4235)
                                                .setCallDepth(1)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY))
                                                .setOutput(EMPTY)
                                                .setInput(encodeFunctionCall(
                                                        TRACEABILITY,
                                                        "callAddressSetSlot2",
                                                        hexedSolidityAddressToHeadlongAddress(
                                                                getNestedContractAddress(TRACEABILITY + SECOND, spec)),
                                                        BigInteger.valueOf(5)))
                                                .build(),
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallOperationType(CallOperationType.OP_CALL)
                                                .setCallingContract(
                                                        spec.registry().getContractId(TRACEABILITY))
                                                .setGas(932417)
                                                .setGasUsed(3180)
                                                .setCallDepth(2)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY + SECOND))
                                                .setOutput(EMPTY)
                                                .setInput(encodeFunctionCall(
                                                        TRACEABILITY, SET_SECOND_SLOT, BigInteger.valueOf(5)))
                                                .build(),
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallOperationType(CallOperationType.OP_CALL)
                                                .setCallingContract(
                                                        spec.registry().getContractId(TRACEABILITY))
                                                .setGas(941228)
                                                .setGasUsed(29898)
                                                .setCallDepth(1)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY + THIRD))
                                                .setRevertReason(EMPTY)
                                                .setInput(encodeFunctionCall(TRACEABILITY, "failingGettingAndSetting"))
                                                .build(),
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallOperationType(CallOperationType.OP_CALL)
                                                .setCallingContract(
                                                        spec.registry().getContractId(TRACEABILITY + THIRD))
                                                .setGas(926135)
                                                .setGasUsed(2347)
                                                .setCallDepth(2)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY + THIRD))
                                                .setOutput(uint256ReturnWithValue(BigInteger.valueOf(0)))
                                                .setInput(encodeFunctionCall(TRACEABILITY, GET_ZERO_SLOT))
                                                .build(),
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallOperationType(CallOperationType.OP_CALL)
                                                .setCallingContract(
                                                        spec.registry().getContractId(TRACEABILITY + THIRD))
                                                .setGas(923353)
                                                .setGasUsed(20323)
                                                .setCallDepth(2)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY + THIRD))
                                                .setOutput(EMPTY)
                                                .setInput(encodeFunctionCall(
                                                        TRACEABILITY, SET_ZERO_SLOT, BigInteger.valueOf(12)))
                                                .build(),
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallOperationType(CallOperationType.OP_CALL)
                                                .setCallingContract(
                                                        spec.registry().getContractId(TRACEABILITY + THIRD))
                                                .setGas(903135)
                                                .setGasUsed(2391)
                                                .setCallDepth(2)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY + THIRD))
                                                .setOutput(uint256ReturnWithValue(BigInteger.valueOf(1)))
                                                .setInput(encodeFunctionCall(TRACEABILITY, GET_FIRST_SLOT))
                                                .build(),
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallOperationType(CallOperationType.OP_CALL)
                                                .setCallingContract(
                                                        spec.registry().getContractId(TRACEABILITY + THIRD))
                                                .setGas(900301)
                                                .setGasUsed(3224)
                                                .setCallDepth(2)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY + THIRD))
                                                .setOutput(EMPTY)
                                                .setInput(encodeFunctionCall(
                                                        TRACEABILITY, SET_FIRST_SLOT, BigInteger.valueOf(0)))
                                                .build())))));
    }

    @HapiTest
    @Order(11)
    final Stream<DynamicTest> traceabilityE2EScenario11() {
        return hapiTest(
                uploadInitCode(TRACEABILITY),
                contractCreate(TRACEABILITY, BigInteger.TWO, BigInteger.valueOf(3), BigInteger.valueOf(4))
                        .via(FIRST_CREATE_TXN)
                        .gas(2_500_000L),
                expectContractStateChangesSidecarFor(
                        FIRST_CREATE_TXN,
                        List.of(StateChange.stateChangeFor(TRACEABILITY)
                                .withStorageChanges(
                                        StorageChange.readAndWritten(
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(2)),
                                        StorageChange.readAndWritten(
                                                formattedAssertionValue(1),
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(3)),
                                        StorageChange.readAndWritten(
                                                formattedAssertionValue(2),
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(4))))),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        expectContractActionSidecarFor(
                                FIRST_CREATE_TXN,
                                List.of(ContractAction.newBuilder()
                                        .setCallType(CREATE)
                                        .setCallOperationType(CallOperationType.OP_CREATE)
                                        .setCallingAccount(TxnUtils.asId(GENESIS, spec))
                                        .setGas(2298224)
                                        .setRecipientContract(spec.registry().getContractId(TRACEABILITY))
                                        .setGasUsed(68492)
                                        .setOutput(EMPTY)
                                        .build())))),
                expectContractBytecodeSidecarFor(
                        FIRST_CREATE_TXN,
                        TRACEABILITY,
                        TRACEABILITY,
                        BigInteger.TWO,
                        BigInteger.valueOf(3),
                        BigInteger.valueOf(4)),
                contractCustomCreate(TRACEABILITY, SECOND, BigInteger.ZERO, BigInteger.ZERO, BigInteger.valueOf(3))
                        .via(SECOND_CREATE_TXN),
                expectContractStateChangesSidecarFor(
                        SECOND_CREATE_TXN,
                        List.of(StateChange.stateChangeFor(TRACEABILITY + SECOND)
                                .withStorageChanges(
                                        StorageChange.readAndWritten(
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(0)),
                                        StorageChange.readAndWritten(
                                                formattedAssertionValue(1),
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(0)),
                                        StorageChange.readAndWritten(
                                                formattedAssertionValue(2),
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(3))))),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        expectContractActionSidecarFor(
                                SECOND_CREATE_TXN,
                                List.of(ContractAction.newBuilder()
                                        .setCallType(CREATE)
                                        .setCallOperationType(CallOperationType.OP_CREATE)
                                        .setCallingAccount(TxnUtils.asId(GENESIS, spec))
                                        .setGas(2798248)
                                        .setRecipientContract(spec.registry().getContractId(TRACEABILITY + SECOND))
                                        .setGasUsed(28692)
                                        .setOutput(EMPTY)
                                        .build())))),
                expectContractBytecodeSidecarFor(
                        SECOND_CREATE_TXN,
                        TRACEABILITY + SECOND,
                        TRACEABILITY,
                        BigInteger.ZERO,
                        BigInteger.ZERO,
                        BigInteger.valueOf(3)),
                contractCustomCreate(TRACEABILITY, THIRD, BigInteger.ZERO, BigInteger.ONE, BigInteger.ZERO)
                        .via(THIRD_CREATE_TXN),
                expectContractStateChangesSidecarFor(
                        THIRD_CREATE_TXN,
                        List.of(StateChange.stateChangeFor(TRACEABILITY + THIRD)
                                .withStorageChanges(
                                        StorageChange.readAndWritten(
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(0)),
                                        StorageChange.readAndWritten(
                                                formattedAssertionValue(1),
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(1)),
                                        StorageChange.readAndWritten(
                                                formattedAssertionValue(2),
                                                formattedAssertionValue(0),
                                                formattedAssertionValue(0))))),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        expectContractActionSidecarFor(
                                THIRD_CREATE_TXN,
                                List.of(ContractAction.newBuilder()
                                        .setCallType(CREATE)
                                        .setCallOperationType(CallOperationType.OP_CREATE)
                                        .setCallingAccount(TxnUtils.asId(GENESIS, spec))
                                        .setGas(2798248)
                                        .setRecipientContract(spec.registry().getContractId(TRACEABILITY + THIRD))
                                        .setGasUsed(28692)
                                        .setOutput(EMPTY)
                                        .build())))),
                expectContractBytecodeSidecarFor(
                        THIRD_CREATE_TXN,
                        TRACEABILITY + THIRD,
                        TRACEABILITY,
                        BigInteger.ZERO,
                        BigInteger.ONE,
                        BigInteger.ZERO),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCall(
                                        TRACEABILITY,
                                        "eetScenario11",
                                        asHeadlongAddress(getNestedContractAddress(TRACEABILITY + "B", spec)),
                                        asHeadlongAddress(getNestedContractAddress(TRACEABILITY + "C", spec)))
                                .gas(1_000_000)
                                .via(TRACEABILITY_TXN))),
                expectContractStateChangesSidecarFor(
                        TRACEABILITY_TXN,
                        List.of(
                                StateChange.stateChangeFor(TRACEABILITY)
                                        .withStorageChanges(
                                                StorageChange.onlyRead(
                                                        formattedAssertionValue(0), formattedAssertionValue(2)),
                                                StorageChange.readAndWritten(
                                                        formattedAssertionValue(1),
                                                        formattedAssertionValue(3),
                                                        formattedAssertionValue(4))),
                                StateChange.stateChangeFor(TRACEABILITY + THIRD)
                                        .withStorageChanges(
                                                StorageChange.readAndWritten(
                                                        formattedAssertionValue(0),
                                                        formattedAssertionValue(0),
                                                        formattedAssertionValue(123)),
                                                StorageChange.readAndWritten(
                                                        formattedAssertionValue(1),
                                                        formattedAssertionValue(1),
                                                        formattedAssertionValue(0))))),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        expectContractActionSidecarFor(
                                TRACEABILITY_TXN,
                                List.of(
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallingAccount(TxnUtils.asId(GENESIS, spec))
                                                .setCallOperationType(CallOperationType.OP_CALL)
                                                .setGas(978632)
                                                .setGasUsed(44077)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY))
                                                .setOutput(EMPTY)
                                                .setInput(encodeFunctionCall(
                                                        TRACEABILITY,
                                                        "eetScenario11",
                                                        hexedSolidityAddressToHeadlongAddress(
                                                                getNestedContractAddress(TRACEABILITY + "B", spec)),
                                                        hexedSolidityAddressToHeadlongAddress(
                                                                getNestedContractAddress(TRACEABILITY + "C", spec))))
                                                .build(),
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallOperationType(CallOperationType.OP_CALL)
                                                .setCallingContract(
                                                        spec.registry().getContractId(TRACEABILITY))
                                                .setGas(962676)
                                                .setGasUsed(2347)
                                                .setCallDepth(1)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY))
                                                .setOutput(uint256ReturnWithValue(BigInteger.valueOf(2)))
                                                .setInput(encodeFunctionCall(TRACEABILITY, GET_ZERO_SLOT))
                                                .build(),
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallOperationType(CallOperationType.OP_CALL)
                                                .setCallingContract(
                                                        spec.registry().getContractId(TRACEABILITY))
                                                .setCallDepth(1)
                                                .setGas(959894)
                                                .setGasUsed(5324)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY))
                                                .setOutput(EMPTY)
                                                .setInput(encodeFunctionCall(
                                                        TRACEABILITY, SET_FIRST_SLOT, BigInteger.valueOf(4)))
                                                .build(),
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallOperationType(CallOperationType.OP_DELEGATECALL)
                                                .setCallingContract(
                                                        spec.registry().getContractId(TRACEABILITY))
                                                .setGas(951979)
                                                .setGasUsed(237)
                                                .setCallDepth(1)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY + SECOND))
                                                .setRevertReason(EMPTY)
                                                .setInput(ByteString.copyFrom(
                                                        "readAndWriteThenRevert()".getBytes(StandardCharsets.UTF_8)))
                                                .build(),
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallOperationType(CallOperationType.OP_CALL)
                                                .setCallingContract(
                                                        spec.registry().getContractId(TRACEABILITY))
                                                .setGas(949041)
                                                .setGasUsed(2347)
                                                .setCallDepth(1)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY + THIRD))
                                                .setOutput(uint256ReturnWithValue(BigInteger.valueOf(0)))
                                                .setInput(encodeFunctionCall(TRACEABILITY, GET_ZERO_SLOT))
                                                .build(),
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallOperationType(CallOperationType.OP_CALL)
                                                .setCallingContract(
                                                        spec.registry().getContractId(TRACEABILITY))
                                                .setCallDepth(1)
                                                .setGas(946244)
                                                .setGasUsed(20323)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY + THIRD))
                                                .setOutput(EMPTY)
                                                .setInput(encodeFunctionCall(
                                                        TRACEABILITY, SET_ZERO_SLOT, BigInteger.valueOf(123)))
                                                .build(),
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallOperationType(CallOperationType.OP_CALL)
                                                .setCallingContract(
                                                        spec.registry().getContractId(TRACEABILITY))
                                                .setGas(926025)
                                                .setGasUsed(2391)
                                                .setCallDepth(1)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY + THIRD))
                                                .setOutput(uint256ReturnWithValue(BigInteger.valueOf(1)))
                                                .setInput(encodeFunctionCall(TRACEABILITY, GET_FIRST_SLOT))
                                                .build(),
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallOperationType(CallOperationType.OP_CALL)
                                                .setCallingContract(
                                                        spec.registry().getContractId(TRACEABILITY))
                                                .setGas(923172)
                                                .setGasUsed(3224)
                                                .setCallDepth(1)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(TRACEABILITY + THIRD))
                                                .setOutput(EMPTY)
                                                .setInput(encodeFunctionCall(
                                                        TRACEABILITY, SET_FIRST_SLOT, BigInteger.valueOf(0)))
                                                .build())))));
    }

    @HapiTest
    @Order(12)
    final Stream<DynamicTest> traceabilityE2EScenario12() {
        final var contract = "CreateTrivial";
        final var scenario12 = "traceabilityE2EScenario12";
        return defaultHapiSpec(scenario12)
                .given(uploadInitCode(contract))
                .when(contractCreate(contract)
                        .via(TRACEABILITY_TXN)
                        .inlineInitCode(extractBytecodeUnhexed(getResourcePath(contract, ".bin"))))
                .then(
                        withOpContext((spec, opLog) -> {
                            final HapiGetTxnRecord txnRecord = getTxnRecord(TRACEABILITY_TXN);
                            allRunFor(
                                    spec,
                                    txnRecord,
                                    expectContractActionSidecarFor(
                                            TRACEABILITY_TXN,
                                            List.of(ContractAction.newBuilder()
                                                    .setCallType(CREATE)
                                                    .setCallOperationType(CallOperationType.OP_CREATE)
                                                    .setCallingAccount(
                                                            spec.registry().getAccountID(GENESIS))
                                                    .setRecipientContract(
                                                            spec.registry().getContractId(contract))
                                                    .setGas(2934672)
                                                    .setGasUsed(214)
                                                    .setOutput(EMPTY)
                                                    .build())));
                        }),
                        expectContractBytecodeSansInitcodeFor(TRACEABILITY_TXN, contract));
    }

    @HapiTest
    @Order(13)
    final Stream<DynamicTest> traceabilityE2EScenario13() {
        final AtomicReference<AccountID> accountIDAtomicReference = new AtomicReference<>();
        return hapiTest(
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(RELAYER).balance(6 * ONE_MILLION_HBARS),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_HUNDRED_HBARS))
                        .via(AUTO_ACCOUNT_TXN),
                getAliasedAccountInfo(SECP_256K1_SOURCE_KEY).exposingIdTo(accountIDAtomicReference::set),
                getTxnRecord(AUTO_ACCOUNT_TXN).andAllChildRecords(),
                uploadInitCode(PAY_RECEIVABLE_CONTRACT),
                ethereumContractCreate(PAY_RECEIVABLE_CONTRACT)
                        .type(EthTxData.EthTransactionType.EIP1559)
                        .signingWith(SECP_256K1_SOURCE_KEY)
                        .payingWith(RELAYER)
                        .nonce(0)
                        .maxGasAllowance(ONE_HUNDRED_HBARS)
                        .gasLimit(1_000_000L)
                        .hasKnownStatus(SUCCESS)
                        .via(FIRST_CREATE_TXN),
                withOpContext((spec, opLog) -> {
                    final HapiGetTxnRecord txnRecord = getTxnRecord(FIRST_CREATE_TXN);
                    allRunFor(
                            spec,
                            txnRecord,
                            expectContractActionSidecarFor(
                                    FIRST_CREATE_TXN,
                                    List.of(ContractAction.newBuilder()
                                            .setCallType(CREATE)
                                            .setCallOperationType(CallOperationType.OP_CREATE)
                                            .setCallingAccount(accountIDAtomicReference.get())
                                            .setRecipientContract(
                                                    spec.registry().getContractId(PAY_RECEIVABLE_CONTRACT))
                                            .setGas(937984)
                                            .setGasUsed(135)
                                            .setOutput(EMPTY)
                                            .build())));
                }),
                // The bytecode is externalized along with the synthetic ContractCreate
                // child following the top-level EthereumTransaction record (index=1)
                expectContractBytecodeSansInitcodeFor(FIRST_CREATE_TXN, 1, PAY_RECEIVABLE_CONTRACT));
    }

    @HapiTest
    @Order(14)
    final Stream<DynamicTest> traceabilityE2EScenario14() {
        return hapiTest(
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(RELAYER).balance(6 * ONE_MILLION_HBARS),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_HUNDRED_HBARS))
                        .via(AUTO_ACCOUNT_TXN),
                getTxnRecord(AUTO_ACCOUNT_TXN).andAllChildRecords(),
                uploadInitCode(PAY_RECEIVABLE_CONTRACT),
                ethereumContractCreate(PAY_RECEIVABLE_CONTRACT)
                        .type(EthTransactionType.EIP1559)
                        .signingWith(SECP_256K1_SOURCE_KEY)
                        .payingWith(RELAYER)
                        .nonce(0)
                        .maxGasAllowance(ONE_HUNDRED_HBARS)
                        .gasLimit(1_000_000L)
                        .hasKnownStatus(SUCCESS)
                        .via(TRACEABILITY_TXN),
                withOpContext((spec, opLog) -> {
                    final AtomicReference<AccountID> accountIDAtomicReference = new AtomicReference<>();
                    final var hapiGetAccountInfo =
                            getAliasedAccountInfo(SECP_256K1_SOURCE_KEY).exposingIdTo(accountIDAtomicReference::set);
                    allRunFor(spec, hapiGetAccountInfo);
                    allRunFor(
                            spec,
                            expectContractActionSidecarFor(
                                    TRACEABILITY_TXN,
                                    List.of(ContractAction.newBuilder()
                                            .setCallType(CREATE)
                                            .setCallOperationType(CallOperationType.OP_CREATE)
                                            .setCallingAccount(accountIDAtomicReference.get())
                                            .setGas(937984)
                                            .setRecipientContract(
                                                    spec.registry().getContractId(PAY_RECEIVABLE_CONTRACT))
                                            .setGasUsed(135)
                                            .setOutput(EMPTY)
                                            .build())),
                            // The bytecode is externalized along with the synthetic ContractCreate
                            // child following the top-level EthereumTransaction record (index=1)
                            expectContractBytecodeSansInitcodeFor(TRACEABILITY_TXN, 1, PAY_RECEIVABLE_CONTRACT));
                }));
    }

    @HapiTest
    @Order(15)
    final Stream<DynamicTest> traceabilityE2EScenario15() {
        final String GET_BYTECODE = "getBytecode";
        final String DEPLOY = "deploy";
        final var CREATE_2_TXN = "Create2Txn";
        final var CREATE_TXN = "CreateTxn";
        final var tcValue = 1_234L;
        final var contract = "Create2Factory";
        final var salt = 42;
        final AtomicReference<String> factoryEvmAddress = new AtomicReference<>();
        final AtomicReference<String> expectedCreate2Address = new AtomicReference<>();
        final AtomicReference<byte[]> testContractInitcode = new AtomicReference<>();
        final AtomicReference<byte[]> bytecodeFromMirror = new AtomicReference<>();
        final AtomicReference<String> mirrorLiteralId = new AtomicReference<>();
        return hapiTest(
                uploadInitCode(contract),
                contractCreate(contract)
                        .via(CREATE_TXN)
                        .exposingContractIdTo(id -> factoryEvmAddress.set(asHexedSolidityAddress(id))),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        expectContractActionSidecarFor(
                                CREATE_TXN,
                                List.of(ContractAction.newBuilder()
                                        .setCallType(CREATE)
                                        .setCallOperationType(CallOperationType.OP_CREATE)
                                        .setCallingAccount(TxnUtils.asId(GENESIS, spec))
                                        .setGas(153184)
                                        .setRecipientContract(spec.registry().getContractId(contract))
                                        .setGasUsed(613)
                                        .setOutput(EMPTY)
                                        .build())))),
                expectContractBytecodeSidecarFor(CREATE_TXN, contract, contract),
                sourcing(() -> contractCallLocal(
                                contract,
                                GET_BYTECODE,
                                asHeadlongAddress(factoryEvmAddress.get()),
                                BigInteger.valueOf(salt))
                        .exposingTypedResultsTo(results -> {
                            final var tcInitcode = (byte[]) results[0];
                            testContractInitcode.set(tcInitcode);
                            log.info("Contract reported TestContract" + " initcode is {} bytes", tcInitcode.length);
                        })),
                sourcing(() -> contractCallLocal(
                                contract, "getAddress", testContractInitcode.get(), BigInteger.valueOf(salt))
                        .exposingTypedResultsTo(results -> {
                            log.info("Contract reported address" + " results {}", results);
                            final var expectedAddr = (Address) results[0];
                            final var hexedAddress = expectedAddr.toString();
                            log.info("  --> Expected CREATE2 address" + " is {}", hexedAddress);
                            expectedCreate2Address.set(hexedAddress);
                        })),
                sourcing(() -> contractCall(contract, DEPLOY, testContractInitcode.get(), BigInteger.valueOf(salt))
                        .payingWith(GENESIS)
                        .gas(4_000_000L)
                        .sending(tcValue)
                        .via(CREATE_2_TXN)),
                withOpContext((spec, opLog) -> {
                    final var parentId = spec.registry().getContractId(contract);
                    final var childId = ContractID.newBuilder()
                            .setContractNum(parentId.getContractNum() + 1L)
                            .build();
                    mirrorLiteralId.set(asEntityString(spec.shard(), spec.realm(), childId.getContractNum()));
                    final var topLevelCallTxnRecord =
                            getTxnRecord(CREATE_2_TXN).andAllChildRecords().logged();
                    final var hapiGetContractBytecode =
                            getContractBytecode(mirrorLiteralId.get()).exposingBytecodeTo(bytecodeFromMirror::set);
                    allRunFor(
                            spec,
                            topLevelCallTxnRecord,
                            expectContractStateChangesSidecarFor(
                                    CREATE_2_TXN,
                                    List.of(StateChange.stateChangeFor(HapiPropertySource.asContractString(childId))
                                            .withStorageChanges(
                                                    StorageChange.readAndWritten(
                                                            formattedAssertionValue(0L),
                                                            formattedAssertionValue(0L),
                                                            ByteStringUtils.wrapUnsafely(
                                                                    Bytes.fromHexString(factoryEvmAddress.get())
                                                                            .trimLeadingZeros()
                                                                            .toArrayUnsafe())),
                                                    StorageChange.readAndWritten(
                                                            formattedAssertionValue(1L),
                                                            formattedAssertionValue(0L),
                                                            formattedAssertionValue(salt))))),
                            expectContractActionSidecarFor(
                                    CREATE_2_TXN,
                                    List.of(
                                            ContractAction.newBuilder()
                                                    .setCallType(CALL)
                                                    .setCallOperationType(CallOperationType.OP_CALL)
                                                    .setCallingAccount(TxnUtils.asId(GENESIS, spec))
                                                    .setGas(3965516)
                                                    .setValue(tcValue)
                                                    .setRecipientContract(
                                                            spec.registry().getContractId(contract))
                                                    .setGasUsed(80193)
                                                    .setOutput(EMPTY)
                                                    .setInput(
                                                            encodeFunctionCall(
                                                                    contract,
                                                                    DEPLOY,
                                                                    testContractInitcode.get(),
                                                                    BigInteger.valueOf(salt)))
                                                    .build(),
                                            ContractAction.newBuilder()
                                                    .setCallType(CREATE)
                                                    .setCallOperationType(CallOperationType.OP_CREATE2)
                                                    .setCallingContract(
                                                            spec.registry().getContractId(contract))
                                                    .setGas(3870552)
                                                    .setRecipientContract(childId)
                                                    .setGasUsed(44936)
                                                    .setValue(tcValue)
                                                    .setOutput(EMPTY)
                                                    .setCallDepth(1)
                                                    .build())),
                            hapiGetContractBytecode);
                    allRunFor(
                            spec,
                            // The bytecode is externalized along with the synthetic ContractCreate
                            // child corresponding to the internal creation (index=1)
                            expectExplicitContractBytecode(
                                    CREATE_2_TXN,
                                    1,
                                    asContract(mirrorLiteralId.get()),
                                    ByteStringUtils.wrapUnsafely(testContractInitcode.get()),
                                    ByteStringUtils.wrapUnsafely(bytecodeFromMirror.get())));
                }));
    }

    @HapiTest
    @Order(16)
    final Stream<DynamicTest> traceabilityE2EScenario16() {
        final AtomicReference<TokenID> vanillaTokenID = new AtomicReference<>();
        final String PRECOMPILE_CALLER = "PrecompileCaller";
        final String txn = "payTxn";
        final String toHash = "toHash";
        return hapiTest(
                tokenCreate("goodToken")
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .treasury(GENESIS)
                        .exposingCreatedIdTo(id -> vanillaTokenID.set(asToken(id))),
                uploadInitCode(PRECOMPILE_CALLER),
                contractCreate(PRECOMPILE_CALLER).via(txn),
                withOpContext((spec, opLog) -> {
                    final HapiGetTxnRecord txnRecord = getTxnRecord(txn);
                    allRunFor(
                            spec,
                            txnRecord,
                            expectContractActionSidecarFor(
                                    txn,
                                    List.of(ContractAction.newBuilder()
                                            .setCallType(CREATE)
                                            .setCallOperationType(CallOperationType.OP_CREATE)
                                            .setCallingAccount(spec.registry().getAccountID(GENESIS))
                                            .setRecipientContract(
                                                    spec.registry().getContractId(PRECOMPILE_CALLER))
                                            .setGas(125628)
                                            .setGasUsed(942)
                                            .setOutput(EMPTY)
                                            .build())));
                }),
                expectContractBytecodeSidecarFor(txn, PRECOMPILE_CALLER, PRECOMPILE_CALLER),
                sourcing(() -> contractCall(
                                PRECOMPILE_CALLER,
                                "callSha256AndIsToken",
                                toHash.getBytes(),
                                HapiParserUtil.asHeadlongAddress(asAddress(vanillaTokenID.get())))
                        .via("callTxn")),
                withOpContext((spec, opLog) -> {
                    final byte[] expectedHash =
                            Hashing.sha256().hashBytes(toHash.getBytes()).asBytes();
                    allRunFor(
                            spec,
                            expectContractActionSidecarFor(
                                    "callTxn",
                                    List.of(
                                            ContractAction.newBuilder()
                                                    .setCallType(CALL)
                                                    .setCallOperationType(CallOperationType.OP_CALL)
                                                    .setCallingAccount(
                                                            spec.registry().getAccountID(GENESIS))
                                                    .setGas(78304)
                                                    .setRecipientContract(
                                                            spec.registry().getContractId(PRECOMPILE_CALLER))
                                                    .setGasUsed(5330)
                                                    .setInput(
                                                            encodeFunctionCall(
                                                                    PRECOMPILE_CALLER,
                                                                    "callSha256AndIsToken",
                                                                    toHash.getBytes(),
                                                                    hexedSolidityAddressToHeadlongAddress(
                                                                            asHexedSolidityAddress(
                                                                                    vanillaTokenID.get()))))
                                                    .setOutput(
                                                            ByteStringUtils.wrapUnsafely(
                                                                    encodeTuple("(bool,bytes32)", true, expectedHash)))
                                                    .build(),
                                            ContractAction.newBuilder()
                                                    .setCallType(PRECOMPILE)
                                                    .setCallOperationType(CallOperationType.OP_STATICCALL)
                                                    .setCallingContract(
                                                            spec.registry().getContractId(PRECOMPILE_CALLER))
                                                    .setGas(75902)
                                                    // SHA 256 precompile address is
                                                    // 0x02
                                                    .setRecipientContract(
                                                            ContractID.newBuilder()
                                                                    .setContractNum(2)
                                                                    .build())
                                                    .setGasUsed(72)
                                                    .setInput(ByteStringUtils.wrapUnsafely(toHash.getBytes()))
                                                    .setOutput(ByteStringUtils.wrapUnsafely(expectedHash))
                                                    .setCallDepth(1)
                                                    .build(),
                                            ContractAction.newBuilder()
                                                    .setCallType(SYSTEM)
                                                    .setCallOperationType(CallOperationType.OP_CALL)
                                                    .setCallingContract(
                                                            spec.registry().getContractId(PRECOMPILE_CALLER))
                                                    .setGas(72555)
                                                    // HTS precompile address is
                                                    // 0x167
                                                    .setRecipientContract(
                                                            ContractID.newBuilder()
                                                                    .setContractNum(359)
                                                                    .build())
                                                    .setGasUsed(100)
                                                    .setInput(
                                                            ByteStringUtils.wrapUnsafely(
                                                                    Function.parse("isToken" + "(address)")
                                                                            .encodeCallWithArgs(
                                                                                    hexedSolidityAddressToHeadlongAddress(
                                                                                            asHexedSolidityAddress(
                                                                                                    vanillaTokenID
                                                                                                            .get())))
                                                                            .array()))
                                                    .setOutput(
                                                            ByteStringUtils.wrapUnsafely(
                                                                    encodeTuple(
                                                                            ("(int64,bool)"),
                                                                            (long) SUCCESS.getNumber(),
                                                                            true)))
                                                    .setCallDepth(1)
                                                    .build())));
                }));
    }

    @HapiTest
    @Order(17)
    final Stream<DynamicTest> traceabilityE2EScenario17() {
        return hapiTest(
                uploadInitCode(REVERTING_CONTRACT),
                contractCreate(REVERTING_CONTRACT, BigInteger.valueOf(6)).via(FIRST_CREATE_TXN),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        expectContractActionSidecarFor(
                                FIRST_CREATE_TXN,
                                List.of(ContractAction.newBuilder()
                                        .setCallType(CREATE)
                                        .setCallOperationType(CallOperationType.OP_CREATE)
                                        .setCallingAccount(TxnUtils.asId(GENESIS, spec))
                                        .setGas(185276)
                                        .setRecipientContract(spec.registry().getContractId(REVERTING_CONTRACT))
                                        .setGasUsed(345)
                                        .setOutput(EMPTY)
                                        .build())))),
                expectContractBytecodeSidecarFor(
                        FIRST_CREATE_TXN, REVERTING_CONTRACT, REVERTING_CONTRACT, BigInteger.valueOf(6)),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCall(REVERTING_CONTRACT, "createContract", BigInteger.valueOf(4))
                                .gas(1_000_000)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED)
                                .via(TRACEABILITY_TXN))),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        expectContractActionSidecarFor(
                                TRACEABILITY_TXN,
                                List.of(
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallingAccount(TxnUtils.asId(GENESIS, spec))
                                                .setCallOperationType(CallOperationType.OP_CALL)
                                                .setGas(978796)
                                                .setGasUsed(32583)
                                                .setRecipientContract(
                                                        spec.registry().getContractId(REVERTING_CONTRACT))
                                                .setRevertReason(EMPTY)
                                                .setInput(encodeFunctionCall(
                                                        REVERTING_CONTRACT, "createContract", BigInteger.valueOf(4)))
                                                .build(),
                                        ContractAction.newBuilder()
                                                .setCallType(CREATE)
                                                .setCallOperationType(CallOperationType.OP_CREATE)
                                                .setCallingContract(
                                                        spec.registry().getContractId(REVERTING_CONTRACT))
                                                .setGas(931667)
                                                .setCallDepth(1)
                                                .setGasUsed(201)
                                                .setRevertReason(EMPTY)
                                                .build())))));
    }

    @HapiTest
    @Order(18)
    final Stream<DynamicTest> traceabilityE2EScenario18() {
        return hapiTest(
                uploadInitCode(REVERTING_CONTRACT),
                contractCreate(REVERTING_CONTRACT, BigInteger.valueOf(4))
                        .via(FIRST_CREATE_TXN)
                        .hasKnownStatus(CONTRACT_REVERT_EXECUTED),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        expectContractActionSidecarFor(
                                FIRST_CREATE_TXN,
                                List.of(ContractAction.newBuilder()
                                        .setCallType(CREATE)
                                        .setCallOperationType(CallOperationType.OP_CREATE)
                                        .setCallingAccount(TxnUtils.asId(GENESIS, spec))
                                        .setGas(185276)
                                        .setGasUsed(201)
                                        .setRevertReason(EMPTY)
                                        .build())))),
                expectFailedContractBytecodeSidecarFor(FIRST_CREATE_TXN, REVERTING_CONTRACT, BigInteger.valueOf(4)));
    }

    @HapiTest
    @Order(19)
    final Stream<DynamicTest> traceabilityE2EScenario19() {
        final var RECEIVER = "RECEIVER";
        final var hbarsToSend = 1;
        final var transferTxn = "payTxn";
        return hapiTest(
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(RECEIVER).balance(0L),
                cryptoCreate(RELAYER).balance(6 * ONE_MILLION_HBARS),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_HUNDRED_HBARS))
                        .via(AUTO_ACCOUNT_TXN),
                getTxnRecord(AUTO_ACCOUNT_TXN).andAllChildRecords(),
                ethereumCryptoTransfer(RECEIVER, hbarsToSend)
                        .type(EthTxData.EthTransactionType.EIP1559)
                        .signingWith(SECP_256K1_SOURCE_KEY)
                        .gasLimit(2_000_000L)
                        .payingWith(RELAYER)
                        .via(transferTxn),
                withOpContext((spec, opLog) -> {
                    final AtomicReference<AccountID> ethSenderAccountReference = new AtomicReference<>();
                    final var hapiGetAccountInfo =
                            getAliasedAccountInfo(SECP_256K1_SOURCE_KEY).exposingIdTo(ethSenderAccountReference::set);
                    allRunFor(spec, hapiGetAccountInfo);
                    allRunFor(
                            spec,
                            expectContractActionSidecarFor(
                                    transferTxn,
                                    List.of(ContractAction.newBuilder()
                                            .setCallType(CALL)
                                            .setCallOperationType(CallOperationType.OP_CALL)
                                            .setCallingAccount(ethSenderAccountReference.get())
                                            .setGas(1979000)
                                            .setGasUsed(0) // we only transfer
                                            // hbars, no code
                                            // executed
                                            .setValue(hbarsToSend)
                                            .setRecipientAccount(spec.registry().getAccountID(RECEIVER))
                                            .setOutput(EMPTY)
                                            .build())));
                }));
    }

    @HapiTest
    @Order(20)
    final Stream<DynamicTest> traceabilityE2EScenario20() {
        return hapiTest(
                uploadInitCode(REVERTING_CONTRACT),
                contractCreate(REVERTING_CONTRACT, BigInteger.valueOf(6))
                        .via(FIRST_CREATE_TXN)
                        .gas(64774)
                        .hasKnownStatus(INSUFFICIENT_GAS),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        expectContractActionSidecarFor(
                                FIRST_CREATE_TXN,
                                List.of(ContractAction.newBuilder()
                                        .setCallType(CREATE)
                                        .setCallOperationType(CallOperationType.OP_CREATE)
                                        .setCallingAccount(TxnUtils.asId(GENESIS, spec))
                                        .setGas(50)
                                        .setGasUsed(50)
                                        .setError(ByteString.copyFromUtf8(INSUFFICIENT_GAS.name()))
                                        .build())))),
                expectFailedContractBytecodeSidecarFor(FIRST_CREATE_TXN, REVERTING_CONTRACT, BigInteger.valueOf(6)));
    }

    @HapiTest
    @Order(21)
    final Stream<DynamicTest> traceabilityE2EScenario21() {
        return hapiTest(
                uploadInitCode(REVERTING_CONTRACT),
                contractCreate(REVERTING_CONTRACT, BigInteger.valueOf(6)).via(FIRST_CREATE_TXN),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        expectContractActionSidecarFor(
                                FIRST_CREATE_TXN,
                                List.of(ContractAction.newBuilder()
                                        .setCallType(CREATE)
                                        .setCallOperationType(CallOperationType.OP_CREATE)
                                        .setCallingAccount(TxnUtils.asId(GENESIS, spec))
                                        .setGas(185276)
                                        .setRecipientContract(spec.registry().getContractId(REVERTING_CONTRACT))
                                        .setGasUsed(345)
                                        .setOutput(EMPTY)
                                        .build())))),
                expectContractBytecodeSidecarFor(
                        FIRST_CREATE_TXN, REVERTING_CONTRACT, REVERTING_CONTRACT, BigInteger.valueOf(6)),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCall(REVERTING_CONTRACT, "callingWrongAddress")
                                .gas(1_000_000)
                                .hasKnownStatusFrom(SUCCESS, INVALID_SOLIDITY_ADDRESS)
                                .via(TRACEABILITY_TXN))),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        expectContractActionSidecarFor(
                                TRACEABILITY_TXN,
                                List.of(
                                        ContractAction.newBuilder()
                                                .setCallType(CALL)
                                                .setCallingAccount(TxnUtils.asId(GENESIS, spec))
                                                .setCallOperationType(CallOperationType.OP_CALL)
                                                .setGas(978936)
                                                .setGasUsed(963748)
                                                .setOutput(EMPTY)
                                                /*
                                                   For EVM v0.34 use this code block instead:
                                                .setGasUsed(979000)
                                                .setError(ByteString.copyFromUtf8(INVALID_SOLIDITY_ADDRESS.name()))
                                                */
                                                .setRecipientContract(
                                                        spec.registry().getContractId(REVERTING_CONTRACT))
                                                .setInput(encodeFunctionCall(REVERTING_CONTRACT, "callingWrongAddress"))
                                                .build(),
                                        ContractAction.newBuilder()
                                                .setCallType(PRECOMPILE)
                                                .setCallingContract(
                                                        spec.registry().getContractId(REVERTING_CONTRACT))
                                                .setCallOperationType(CallOperationType.OP_CALL)
                                                .setCallDepth(1)
                                                .setInput(ByteStringUtils.wrapUnsafely(
                                                        Function.parse("boo" + "(uint256)")
                                                                .encodeCallWithArgs(BigInteger.valueOf(234))
                                                                .array()))
                                                .setGas(960576)
                                                .setGasUsed(960576)
                                                .setRecipientContract(ContractID.newBuilder()
                                                        .setContractNum(0)
                                                        .build())
                                                .setOutput(EMPTY)
                                                /*
                                                   For EVM v0.34 use this code block instead:

                                                .setGas(978487)
                                                .setError(ByteString.copyFromUtf8(INVALID_SOLIDITY_ADDRESS.name()))

                                                */
                                                .build())))));
    }

    @HapiTest
    @Order(22)
    final Stream<DynamicTest> vanillaBytecodeSidecar() {
        final var EMPTY_CONSTRUCTOR_CONTRACT = "EmptyConstructor";
        final var vanillaBytecodeSidecar = "vanillaBytecodeSidecar";
        final var firstTxn = "firstTxn";
        return defaultHapiSpec(vanillaBytecodeSidecar)
                .given(uploadInitCode(EMPTY_CONSTRUCTOR_CONTRACT))
                .when(contractCreate(EMPTY_CONSTRUCTOR_CONTRACT)
                        .hasKnownStatus(SUCCESS)
                        .via(firstTxn))
                .then(
                        withOpContext((spec, opLog) -> {
                            final HapiGetTxnRecord txnRecord = getTxnRecord(firstTxn);
                            allRunFor(
                                    spec,
                                    txnRecord,
                                    expectContractActionSidecarFor(
                                            firstTxn,
                                            List.of(ContractAction.newBuilder()
                                                    .setCallType(CREATE)
                                                    .setCallOperationType(CallOperationType.OP_CREATE)
                                                    .setCallingAccount(
                                                            spec.registry().getAccountID(GENESIS))
                                                    .setRecipientContract(
                                                            spec.registry().getContractId(EMPTY_CONSTRUCTOR_CONTRACT))
                                                    .setGas(195600)
                                                    .setGasUsed(66)
                                                    .setOutput(EMPTY)
                                                    .build())));
                        }),
                        expectContractBytecodeSidecarFor(
                                firstTxn, EMPTY_CONSTRUCTOR_CONTRACT, EMPTY_CONSTRUCTOR_CONTRACT));
    }

    @HapiTest
    @Order(23)
    final Stream<DynamicTest> vanillaBytecodeSidecar2() {
        final var contract = "CreateTrivial";
        final var firstTxn = "firstTxn";
        return hapiTest(
                uploadInitCode(contract),
                contractCreate(contract).via(firstTxn),
                withOpContext((spec, opLog) -> {
                    final HapiGetTxnRecord txnRecord = getTxnRecord(firstTxn);
                    allRunFor(
                            spec,
                            txnRecord,
                            expectContractActionSidecarFor(
                                    firstTxn,
                                    List.of(ContractAction.newBuilder()
                                            .setCallType(CREATE)
                                            .setCallOperationType(CallOperationType.OP_CREATE)
                                            .setCallingAccount(spec.registry().getAccountID(GENESIS))
                                            .setRecipientContract(
                                                    spec.registry().getContractId(contract))
                                            .setGas(2934672)
                                            .setGasUsed(214)
                                            .setOutput(EMPTY)
                                            .build())));
                }),
                expectContractBytecodeSidecarFor(firstTxn, contract, contract));
    }

    @Order(24)
    @LeakyHapiTest(overrides = {"contracts.sidecars"})
    final Stream<DynamicTest> actionsShowPropagatedRevert() {
        final var APPROVE_BY_DELEGATE = "ApproveByDelegateCall";
        final var badApproval = "BadApproval";
        final var somebody = "somebody";
        final var somebodyElse = "somebodyElse";
        final var tokenInQuestion = "TokenInQuestion";
        final var someSupplyKey = "someSupplyKey";
        final AtomicReference<String> tiqMirrorAddr = new AtomicReference<>();
        final AtomicReference<String> somebodyMirrorAddr = new AtomicReference<>();
        final AtomicReference<String> somebodyElseMirrorAddr = new AtomicReference<>();
        final String contractCreateTxn = "contractCreate";
        final var serialNumberId = MAX_UINT256_VALUE;
        return hapiTest(
                overriding(SIDECARS_PROP, "CONTRACT_ACTION"),
                uploadInitCode(APPROVE_BY_DELEGATE),
                contractCreate(APPROVE_BY_DELEGATE).gas(500_000).via(contractCreateTxn),
                withOpContext((spec, opLog) -> {
                    final HapiGetTxnRecord txnRecord = getTxnRecord(contractCreateTxn);
                    allRunFor(
                            spec,
                            txnRecord,
                            expectContractActionSidecarFor(
                                    contractCreateTxn,
                                    List.of(ContractAction.newBuilder()
                                            .setCallType(CREATE)
                                            .setCallOperationType(CallOperationType.OP_CREATE)
                                            .setCallingAccount(spec.registry().getAccountID(GENESIS))
                                            .setRecipientContract(
                                                    spec.registry().getContractId(APPROVE_BY_DELEGATE))
                                            .setGas(433856)
                                            .setGasUsed(214)
                                            .setOutput(EMPTY)
                                            .build())));
                }),
                cryptoCreate(TOKEN_TREASURY),
                cryptoCreate(somebody)
                        .maxAutomaticTokenAssociations(2)
                        .exposingCreatedIdTo(id -> somebodyMirrorAddr.set(asHexedSolidityAddress(id))),
                cryptoCreate(somebodyElse)
                        .maxAutomaticTokenAssociations(2)
                        .exposingCreatedIdTo(id -> somebodyElseMirrorAddr.set(asHexedSolidityAddress(id))),
                newKeyNamed(someSupplyKey),
                tokenCreate(tokenInQuestion)
                        .supplyKey(someSupplyKey)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .treasury(TOKEN_TREASURY)
                        .initialSupply(0)
                        .exposingCreatedIdTo(
                                idLit -> tiqMirrorAddr.set(asHexedSolidityAddress(HapiPropertySource.asToken(idLit)))),
                mintToken(
                        tokenInQuestion,
                        List.of(ByteString.copyFromUtf8("A penny for"), ByteString.copyFromUtf8("the Old Guy"))),
                cryptoTransfer(movingUnique(tokenInQuestion, 1L).between(TOKEN_TREASURY, somebody)),
                sourcing(() -> contractCall(
                                APPROVE_BY_DELEGATE,
                                "doIt",
                                asHeadlongAddress(tiqMirrorAddr.get()),
                                asHeadlongAddress(somebodyElseMirrorAddr.get()),
                                serialNumberId)
                        .payingWith(somebody)
                        .gas(1_000_000)
                        .via(badApproval)
                        .hasKnownStatus(CONTRACT_REVERT_EXECUTED)),
                withOpContext((spec, opLog) -> {
                    final HapiGetTxnRecord txnRecord = getTxnRecord(badApproval);
                    allRunFor(
                            spec,
                            txnRecord,
                            expectContractActionSidecarFor(
                                    badApproval,
                                    List.of(
                                            ContractAction.newBuilder()
                                                    .setCallType(CALL)
                                                    .setCallOperationType(CallOperationType.OP_CALL)
                                                    .setCallingAccount(
                                                            spec.registry().getAccountID(somebody))
                                                    .setRecipientContract(
                                                            spec.registry().getContractId(APPROVE_BY_DELEGATE))
                                                    .setInput(
                                                            encodeFunctionCall(
                                                                    APPROVE_BY_DELEGATE,
                                                                    "doIt",
                                                                    hexedSolidityAddressToHeadlongAddress(
                                                                            asHexedSolidityAddress(
                                                                                    spec.registry()
                                                                                            .getTokenID(
                                                                                                    tokenInQuestion))),
                                                                    hexedSolidityAddressToHeadlongAddress(
                                                                            asHexedSolidityAddress(
                                                                                    spec.registry()
                                                                                            .getAccountID(
                                                                                                    somebodyElse))),
                                                                    serialNumberId))
                                                    .setGas(978120)
                                                    .setGasUsed(948098)
                                                    .setRevertReason(ByteString.EMPTY)
                                                    .build(),
                                            ContractAction.newBuilder()
                                                    .setCallType(CALL)
                                                    .setCallOperationType(CallOperationType.OP_DELEGATECALL)
                                                    .setCallingContract(
                                                            spec.registry().getContractId(APPROVE_BY_DELEGATE))
                                                    .setRecipientContract(
                                                            ContractID.newBuilder()
                                                                    .setContractNum(
                                                                            spec.registry()
                                                                                    .getTokenID(tokenInQuestion)
                                                                                    .getTokenNum())
                                                                    .build())
                                                    .setGas(958481)
                                                    .setGasUsed(943594)
                                                    .setInput(
                                                            ByteStringUtils.wrapUnsafely(
                                                                    Function.parse("approve(address,uint256)")
                                                                            .encodeCallWithArgs(
                                                                                    hexedSolidityAddressToHeadlongAddress(
                                                                                            asHexedSolidityAddress(
                                                                                                    spec.registry()
                                                                                                            .getAccountID(
                                                                                                                    somebodyElse))),
                                                                                    serialNumberId)
                                                                            .array()))
                                                    .setRevertReason(ByteString.EMPTY)
                                                    .setCallDepth(1)
                                                    .build(),
                                            ContractAction.newBuilder()
                                                    .setCallType(SYSTEM)
                                                    .setCallOperationType(CallOperationType.OP_DELEGATECALL)
                                                    .setCallingContract(
                                                            ContractID.newBuilder()
                                                                    .setContractNum(
                                                                            spec.registry()
                                                                                    .getTokenID(tokenInQuestion)
                                                                                    .getTokenNum())
                                                                    .build())
                                                    .setRecipientContract(
                                                            ContractID.newBuilder()
                                                                    .setContractNum(359L)
                                                                    .build())
                                                    .setGas(940841)
                                                    .setGasUsed(940841)
                                                    .setInput(
                                                            ByteStringUtils.wrapUnsafely(
                                                                    ArrayUtils.addAll(
                                                                            ArrayUtils.addAll(
                                                                                    Arrays.copyOfRange(
                                                                                            keccak256(
                                                                                                            Bytes.of(
                                                                                                                    "redirectForToken(address,bytes)"
                                                                                                                            .getBytes()))
                                                                                                    .toArrayUnsafe(),
                                                                                            0,
                                                                                            4),
                                                                                    Arrays.copyOfRange(
                                                                                            encodeTuple(
                                                                                                    "(address)",
                                                                                                    hexedSolidityAddressToHeadlongAddress(
                                                                                                            asHexedSolidityAddress(
                                                                                                                    spec.registry()
                                                                                                                            .getTokenID(
                                                                                                                                    tokenInQuestion)))),
                                                                                            12,
                                                                                            32)),
                                                                            Function.parse("approve(address,uint256)")
                                                                                    .encodeCallWithArgs(
                                                                                            hexedSolidityAddressToHeadlongAddress(
                                                                                                    asHexedSolidityAddress(
                                                                                                            spec.registry()
                                                                                                                    .getAccountID(
                                                                                                                            somebodyElse))),
                                                                                            serialNumberId)
                                                                                    .array())))
                                                    .setError(ByteString.copyFrom("PRECOMPILE_ERROR".getBytes()))
                                                    .setCallDepth(2)
                                                    .build())));
                }));
    }

    @Order(25)
    @LeakyHapiTest(overrides = {"contracts.evm.version"})
    final Stream<DynamicTest> ethereumLazyCreateExportsExpectedSidecars() {
        final var RECIPIENT_KEY = "lazyAccountRecipient";
        final var RECIPIENT_KEY2 = "lazyAccountRecipient2";
        final var lazyCreateTxn = "lazyCreateTxn";
        final var failedlazyCreateTxn = "payTxn2";
        final var valueToSend = FIVE_HBARS;
        return hapiTest(
                overriding("contracts.evm.version", "v0.34"),
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                newKeyNamed(RECIPIENT_KEY).shape(SECP_256K1_SHAPE),
                newKeyNamed(RECIPIENT_KEY2).shape(SECP_256K1_SHAPE),
                cryptoCreate(RELAYER).balance(6 * ONE_MILLION_HBARS),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_HUNDRED_HBARS))
                        .via(AUTO_ACCOUNT_TXN),
                getTxnRecord(AUTO_ACCOUNT_TXN).andAllChildRecords(),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        TxnVerbs.ethereumCryptoTransferToAlias(
                                        spec.registry().getKey(RECIPIENT_KEY).getECDSASecp256K1(), valueToSend)
                                .type(EthTxData.EthTransactionType.EIP1559)
                                .signingWith(SECP_256K1_SOURCE_KEY)
                                .payingWith(RELAYER)
                                .nonce(0)
                                .maxFeePerGas(0L)
                                .maxGasAllowance(FIVE_HBARS)
                                .gasLimit(200_000L)
                                .via(failedlazyCreateTxn)
                                .hasKnownStatus(INSUFFICIENT_GAS),
                        TxnVerbs.ethereumCryptoTransferToAlias(
                                        spec.registry().getKey(RECIPIENT_KEY).getECDSASecp256K1(), valueToSend)
                                .type(EthTxData.EthTransactionType.EIP1559)
                                .signingWith(SECP_256K1_SOURCE_KEY)
                                .payingWith(RELAYER)
                                .nonce(1)
                                .maxFeePerGas(0L)
                                .maxGasAllowance(FIVE_HBARS)
                                .gasLimit(2_000_000L)
                                .via(lazyCreateTxn)
                                .hasKnownStatus(SUCCESS))),
                withOpContext((spec, opLog) -> {
                    final var ecdsaSecp256K1 =
                            spec.registry().getKey(RECIPIENT_KEY).getECDSASecp256K1();
                    final var firstAliasAsByteString =
                            ByteStringUtils.wrapUnsafely(recoverAddressFromPubKey(ecdsaSecp256K1.toByteArray()));
                    final AtomicReference<AccountID> lazyAccountIdReference = new AtomicReference<>();
                    final var lazyAccountInfoCheck = getAliasedAccountInfo(firstAliasAsByteString)
                            .logged()
                            .has(accountWith().balance(FIVE_HBARS).key(EMPTY_KEY))
                            .exposingIdTo(lazyAccountIdReference::set);
                    final AtomicReference<AccountID> ethSenderAccountReference = new AtomicReference<>();
                    final var hapiGetAccountInfo =
                            getAliasedAccountInfo(SECP_256K1_SOURCE_KEY).exposingIdTo(ethSenderAccountReference::set);
                    allRunFor(spec, hapiGetAccountInfo, lazyAccountInfoCheck);
                    allRunFor(
                            spec,
                            expectContractActionSidecarFor(
                                    failedlazyCreateTxn,
                                    List.of(ContractAction.newBuilder()
                                            .setCallType(CALL)
                                            .setCallOperationType(CallOperationType.OP_CALL)
                                            .setCallingAccount(ethSenderAccountReference.get())
                                            .setGas(179000)
                                            .setGasUsed(179000)
                                            .setValue(valueToSend)
                                            .setTargetedAddress(firstAliasAsByteString)
                                            .setError(ByteString.copyFromUtf8(INSUFFICIENT_GAS.name()))
                                            .build())),
                            expectContractActionSidecarFor(
                                    lazyCreateTxn,
                                    List.of(ContractAction.newBuilder()
                                            .setCallType(CALL)
                                            .setCallOperationType(CallOperationType.OP_CALL)
                                            .setCallingAccount(ethSenderAccountReference.get())
                                            .setGas(1_979_000)
                                            .setGasUsed(555_112)
                                            .setValue(valueToSend)
                                            .setRecipientAccount(lazyAccountIdReference.get())
                                            .setOutput(EMPTY)
                                            .build())));
                }));
    }

    @SuppressWarnings("java:S5960")
    @Order(26)
    @LeakyHapiTest(overrides = {"contracts.sidecars"})
    final Stream<DynamicTest> hollowAccountCreate2MergeExportsExpectedSidecars() {
        final var tcValue = 1_234L;
        final var create2Factory = "Create2Factory";
        final var creation = "creation";
        final var salt = BigInteger.valueOf(42);
        final var adminKey = "ADMIN_KEY";
        final var entityMemo = "JUST DO IT";
        final AtomicReference<String> factoryEvmAddress = new AtomicReference<>();
        final AtomicReference<String> expectedCreate2Address = new AtomicReference<>();
        final AtomicReference<String> hollowCreationAddress = new AtomicReference<>();
        final AtomicReference<String> mergedAliasAddr = new AtomicReference<>();
        final AtomicReference<String> mergedMirrorAddr = new AtomicReference<>();
        final AtomicReference<byte[]> testContractInitcode = new AtomicReference<>();
        final AtomicReference<AccountID> mergedAccountId = new AtomicReference<>();
        final var CREATE_2_TXN = "create2Txn";
        return hapiTest(
                overriding("contracts.sidecars", ""),
                newKeyNamed(adminKey),
                newKeyNamed(MULTI_KEY),
                uploadInitCode(create2Factory),
                contractCreate(create2Factory)
                        .payingWith(GENESIS)
                        .adminKey(adminKey)
                        .entityMemo(entityMemo)
                        .via(CREATE_2_TXN)
                        .exposingContractIdTo(id -> factoryEvmAddress.set(asHexedSolidityAddress(id))),
                getTxnRecord(CREATE_2_TXN).logged(),
                cryptoCreate(PARTY).maxAutomaticTokenAssociations(2),
                sourcing(() -> contractCallLocal(
                                create2Factory, GET_BYTECODE, asHeadlongAddress(factoryEvmAddress.get()), salt)
                        .exposingTypedResultsTo(results -> {
                            final var tcInitcode = (byte[]) results[0];
                            testContractInitcode.set(tcInitcode);
                            log.info(CONTRACT_REPORTED_LOG_MESSAGE, tcInitcode.length);
                        })
                        .payingWith(GENESIS)
                        .nodePayment(ONE_HBAR)),
                sourcing(() -> contractCallLocal(create2Factory, GET_ADDRESS, testContractInitcode.get(), salt)
                        .exposingTypedResultsTo(results -> {
                            log.info(CONTRACT_REPORTED_ADDRESS_MESSAGE, results);
                            final var expectedAddrBytes = (Address) results[0];
                            final var hexedAddress = hex(Bytes.fromHexString(expectedAddrBytes.toString())
                                    .toArray());
                            log.info(EXPECTED_CREATE2_ADDRESS_MESSAGE, hexedAddress);
                            expectedCreate2Address.set(hexedAddress);
                        })
                        .payingWith(GENESIS)),
                // Create a hollow account at the desired address
                cryptoTransfer((spec, b) -> {
                            final var defaultPayerId = spec.registry().getAccountID(DEFAULT_PAYER);
                            b.setTransfers(TransferList.newBuilder()
                                    .addAccountAmounts(Utils.aaWith(spec, expectedCreate2Address.get(), +ONE_HBAR))
                                    .addAccountAmounts(aaWith(defaultPayerId, -ONE_HBAR)));
                        })
                        .signedBy(DEFAULT_PAYER, PARTY)
                        .fee(ONE_HBAR)
                        .via(creation),
                getTxnRecord(creation)
                        .andAllChildRecords()
                        .exposingCreationsTo(l -> hollowCreationAddress.set(l.getFirst())),
                // save the id of the hollow account
                sourcing(() ->
                        getAccountInfo(hollowCreationAddress.get()).logged().exposingIdTo(mergedAccountId::set)),
                sourcing(() -> overriding(SIDECARS_PROP, "CONTRACT_ACTION,CONTRACT_STATE_CHANGE,CONTRACT_BYTECODE")),
                sourcing(() -> contractCall(create2Factory, DEPLOY, testContractInitcode.get(), salt)
                        .payingWith(GENESIS)
                        .gas(4_000_000L)
                        .sending(tcValue)
                        .via(CREATE_2_TXN)),
                captureOneChildCreate2MetaFor(
                        "Merged deployed create2Factory with hollow account",
                        CREATE_2_TXN,
                        mergedMirrorAddr,
                        mergedAliasAddr),
                // assert sidecars
                withOpContext((spec, opLog) -> {
                    final var mergedContractIdAsString = HapiPropertySource.asAccountString(mergedAccountId.get());
                    final AtomicReference<byte[]> mergedContractBytecode = new AtomicReference<>();
                    final var hapiGetContractBytecode = getContractBytecode(mergedContractIdAsString)
                            .exposingBytecodeTo(mergedContractBytecode::set);
                    allRunFor(
                            spec,
                            expectContractStateChangesSidecarFor(
                                    CREATE_2_TXN,
                                    List.of(
                                            // recipient should be the original
                                            // hollow account id as a contract
                                            StateChange.stateChangeFor(mergedContractIdAsString)
                                                    .withStorageChanges(
                                                            StorageChange.readAndWritten(
                                                                    formattedAssertionValue(0L),
                                                                    formattedAssertionValue(0L),
                                                                    ByteStringUtils.wrapUnsafely(
                                                                            Bytes.fromHexString(factoryEvmAddress.get())
                                                                                    .trimLeadingZeros()
                                                                                    .toArrayUnsafe())),
                                                            StorageChange.readAndWritten(
                                                                    formattedAssertionValue(1L),
                                                                    formattedAssertionValue(0L),
                                                                    formattedAssertionValue(salt.longValue()))))),
                            expectContractActionSidecarFor(
                                    CREATE_2_TXN,
                                    List.of(
                                            ContractAction.newBuilder()
                                                    .setCallType(CALL)
                                                    .setCallOperationType(CallOperationType.OP_CALL)
                                                    .setCallingAccount(TxnUtils.asId(GENESIS, spec))
                                                    .setGas(3965516)
                                                    .setValue(tcValue)
                                                    .setRecipientContract(
                                                            spec.registry().getContractId(create2Factory))
                                                    .setGasUsed(80193)
                                                    .setOutput(EMPTY)
                                                    .setInput(
                                                            encodeFunctionCall(
                                                                    create2Factory,
                                                                    DEPLOY,
                                                                    testContractInitcode.get(),
                                                                    salt))
                                                    .build(),
                                            ContractAction.newBuilder()
                                                    .setCallType(CREATE)
                                                    .setCallOperationType(CallOperationType.OP_CREATE2)
                                                    .setCallingContract(
                                                            spec.registry().getContractId(create2Factory))
                                                    .setGas(3870552)
                                                    // recipient should be the
                                                    // original hollow account id as
                                                    // a contract
                                                    .setRecipientContract(asContract(mergedContractIdAsString))
                                                    .setGasUsed(44936)
                                                    .setValue(tcValue)
                                                    .setOutput(EMPTY)
                                                    .setCallDepth(1)
                                                    .build())),
                            hapiGetContractBytecode);
                    allRunFor(
                            spec,
                            // The bytecode is externalized along with the synthetic ContractCreate
                            // child corresponding to the internal creation (index=1)
                            expectExplicitContractBytecode(
                                    CREATE_2_TXN,
                                    1,
                                    asContract(mergedContractIdAsString),
                                    ByteStringUtils.wrapUnsafely(testContractInitcode.get()),
                                    ByteStringUtils.wrapUnsafely(mergedContractBytecode.get())));
                }));
    }

    @Order(Integer.MAX_VALUE)
    public final Stream<DynamicTest> assertSidecars() {
        return hapiTest(withOpContext(
                (spec, opLog) -> requireNonNull(GLOBAL_WATCHER.get()).assertExpectations(spec)));
    }
}
