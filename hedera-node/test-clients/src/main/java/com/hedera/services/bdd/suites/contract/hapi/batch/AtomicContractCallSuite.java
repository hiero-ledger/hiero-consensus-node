// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.hapi.batch;

import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiPropertySource.asContract;
import static com.hedera.services.bdd.spec.HapiPropertySource.asContractString;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.changeFromSnapshot;
import static com.hedera.services.bdd.spec.assertions.AssertUtils.inOrder;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.isLiteralResult;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.resultWith;
import static com.hedera.services.bdd.spec.assertions.ContractInfoAsserts.contractWith;
import static com.hedera.services.bdd.spec.assertions.ContractLogAsserts.logWith;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.keys.KeyFactory.KeyType.THRESHOLD;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.contractCallLocal;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountDetails;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getContractInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getContractRecords;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.queries.crypto.ExpectedTokenRel.relationshipWith;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.asId;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.literalInitcodeFor;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCallWithFunctionAbi;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCustomCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoApproveAllowance;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.contract.HapiParserUtil.asHeadlongAddress;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromAccountToAlias;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.assertionsHold;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.balanceSnapshot;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.childRecordsCheck;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.createLargeFile;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.logIt;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyListNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.recordStreamMustIncludeNoFailuresFrom;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sidecarIdValidator;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.CIVILIAN_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_CONTRACT_RECEIVER;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_CONTRACT_SENDER;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_RECEIVER_SOURCE_KEY;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SHAPE;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SOURCE_KEY;
import static com.hedera.services.bdd.suites.HapiSuite.TINY_PARTS_PER_WHOLE;
import static com.hedera.services.bdd.suites.HapiSuite.TOKEN_TREASURY;
import static com.hedera.services.bdd.suites.contract.Utils.FunctionType.FUNCTION;
import static com.hedera.services.bdd.suites.contract.Utils.asAddress;
import static com.hedera.services.bdd.suites.contract.Utils.asHexedSolidityAddress;
import static com.hedera.services.bdd.suites.contract.Utils.asSolidityAddress;
import static com.hedera.services.bdd.suites.contract.Utils.asToken;
import static com.hedera.services.bdd.suites.contract.Utils.captureChildCreate2MetaFor;
import static com.hedera.services.bdd.suites.contract.Utils.contractIdFromHexedMirrorAddress;
import static com.hedera.services.bdd.suites.contract.Utils.getABIFor;
import static com.hedera.services.bdd.suites.contract.Utils.getABIForContract;
import static com.hedera.services.bdd.suites.contract.Utils.idAsHeadlongAddress;
import static com.hedera.services.bdd.suites.contract.Utils.numAsHeadlongAddress;
import static com.hedera.services.bdd.suites.contract.opcodes.Create2OperationSuite.SALT;
import static com.hedera.services.bdd.suites.regression.factories.HollowAccountCompletedFuzzingFactory.CONTRACT;
import static com.hedera.services.bdd.suites.utils.EvmAddressUtils.randomHeadlongAddress;
import static com.hedera.services.bdd.suites.utils.contracts.SimpleBytesResult.bigIntResult;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INNER_TRANSACTION_FAILED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_GAS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_PAYER_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_TX_FEE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_CONTRACT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SOLIDITY_ADDRESS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.NOT_SUPPORTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OBTAINER_SAME_CONTRACT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;
import static org.hiero.base.utility.CommonUtils.unhex;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.esaulpaugh.headlong.abi.ABIType;
import com.esaulpaugh.headlong.abi.Address;
import com.esaulpaugh.headlong.abi.Function;
import com.esaulpaugh.headlong.abi.Tuple;
import com.esaulpaugh.headlong.abi.TupleType;
import com.esaulpaugh.headlong.abi.TypeFactory;
import com.google.protobuf.ByteString;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.queries.meta.HapiGetTxnRecord;
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import com.hedera.services.bdd.spec.transactions.token.TokenMovement;
import com.hedera.services.bdd.spec.utilops.CustomSpecAssert;
import com.hedera.services.bdd.suites.utils.contracts.ContractCallResult;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.Timestamp;
import com.hederahashgraph.api.proto.java.TokenID;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.hiero.base.utility.CommonUtils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

// This test cases are direct copies of ContractCallSuite. The difference here is that
// we are wrapping the operations in an atomic batch to confirm that everything works as expected.
@HapiTestLifecycle
@Tag(SMART_CONTRACT)
public class AtomicContractCallSuite {

    public static final String TOKEN = "yahcliToken";
    private static final Logger LOG = LogManager.getLogger(AtomicContractCallSuite.class);

    private static final String ALICE = "Alice";

    private static final long DEPOSIT_AMOUNT = 1000;
    private static final long GAS_TO_OFFER = 1_000_000L;

    public static final String PAY_RECEIVABLE_CONTRACT = "PayReceivable";
    public static final String SIMPLE_UPDATE_CONTRACT = "SimpleUpdate";
    public static final String TRANSFERRING_CONTRACT = "Transferring";
    private static final String SIMPLE_STORAGE_CONTRACT = "SimpleStorage";
    private static final String OWNER = "owner";
    private static final String INSERT = "insert";
    private static final String TOKEN_ISSUER = "tokenIssuer";
    private static final String DECIMALS = "decimals";
    private static final String BALANCE_OF = "balanceOf";
    private static final String ISSUER_TOKEN_BALANCE = "issuerTokenBalance";
    private static final String TRANSFER = "transfer";
    private static final String ALICE_TOKEN_BALANCE = "aliceTokenBalance";
    private static final String CAROL_TOKEN_BALANCE = "carolTokenBalance";
    private static final String BOB_TOKEN_BALANCE = "bobTokenBalance";
    private static final String PAYER = "payer";
    public static final String DEPOSIT = "deposit";
    private static final String PAY_TXN = "payTxn";
    private static final String BENEFICIARY = "beneficiary";
    private static final String RECEIVER = "receiver";
    private static final String GET_BALANCE = "getBalance";
    private static final String TRANSFER_TXN = "transferTxn";
    public static final String ACCOUNT_INFO_AFTER_CALL = "accountInfoAfterCall";
    private static final String CREATE_TRIVIAL = "CreateTrivial";
    private static final String FAIL_INSUFFICIENT_GAS = "failInsufficientGas";
    private static final String FAIL_INVALID_INITIAL_BALANCE = "failInvalidInitialBalance";
    private static final String SUCCESS_WITH_ZERO_INITIAL_BALANCE = "successWithZeroInitialBalance";
    private static final String KILL_ME = "killMe";
    private static final String RECEIVABLE_SIG_REQ_ACCOUNT = "receivableSigReqAccount";
    private static final String RECEIVABLE_SIG_REQ_ACCOUNT_INFO = "receivableSigReqAccountInfo";
    private static final String TRANSFER_TO_ADDRESS = "transferToAddress";
    public static final String CALL_TX = "callTX";
    public static final String CALL_TX_REC = "callTXRec";
    private static final String ACCOUNT = "account";
    public static final String ACCOUNT_INFO = "accountInfo";
    public static final String CONTRACT_FROM = "contract_from";
    private static final String RECEIVER_INFO = "receiverInfo";
    private static final String SCINFO = "scinfo";
    private static final String NESTED_TRANSFER_CONTRACT = "NestedTransferContract";
    private static final String NESTED_TRANSFERRING_CONTRACT = "NestedTransferringContract";
    private static final String ACC_INFO = "accInfo";
    private static final String RECEIVER_1 = "receiver1";
    public static final String RECEIVER_2 = "receiver2";
    private static final String RECEIVER_3 = "receiver3";
    private static final String RECEIVER_1_INFO = "receiver1Info";
    private static final String RECEIVER_2_INFO = "receiver2Info";
    private static final String RECEIVER_3_INFO = "receiver3Info";
    public static final String DELEGATE_CALL_SPECIFIC = "delegateCallSpecific";
    private static final long INTRINSIC_GAS_FOR_0_ARG_METHOD = 21_064L;
    private static final String BATCH_OPERATOR = "batchOperator";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(Map.of(
                "atomicBatch.isEnabled",
                "true",
                "atomicBatch.maxNumberOfTransactions",
                "50",
                "contracts.throttle.throttleByGas",
                "false"));
        testLifecycle.doAdhoc(cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS));
    }

    @HapiTest
    final Stream<DynamicTest> canHandleInvalidContractCallTransactions() {
        return hapiTest(contractCall(null).hasPrecheck(INVALID_CONTRACT_ID));
    }

    @HapiTest
    final Stream<DynamicTest> repeatedCreate2FailsWithInterpretableActionSidecars() {
        final var contract = "Create2PrecompileUser";
        final var salt = unhex(SALT);
        final var firstCreation = "firstCreation";
        final var secondCreation = "secondCreation";
        return hapiTest(
                recordStreamMustIncludeNoFailuresFrom(sidecarIdValidator()),
                cryptoCreate(ACCOUNT).balance(ONE_MILLION_HBARS),
                uploadInitCode(contract),
                contractCreate(contract),
                atomicBatch(contractCall(contract, "createUser", salt)
                                .payingWith(ACCOUNT)
                                .gas(4_000_000L)
                                .via(firstCreation)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                atomicBatch(contractCall(contract, "createUser", salt)
                                .payingWith(ACCOUNT)
                                .gas(4_000_000L)
                                .via(secondCreation)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    @HapiTest
    final Stream<DynamicTest> insufficientGasToPrecompileFailsWithInterpretableActionSidecars() {
        final var contract = "LowLevelCall";
        // A real-world payload for a call to the altbn128 pairing precompile, for
        // completeness; c.f. https://hashscan.io/testnet/transaction/1711397022.025030483
        final var payload = unhex(
                "1d19ea4313c1943e9d66830b1e05f54895b9743810ad24d831b1186a32c15fe83000fdae8d8e064d5db6ef2b9bd63baa748da3ac6c2f5b4ea372a0f16f844797198e9393920d483a7260bfb731fb5d25f1aa493335a9e71297e485b7aef312c21800deef121f1e76426a00665e5c4479674322d4f75edadd46debd5cd992f6ed275dc4a288d1afb3cbb1ac09187524c7db36395df7be3b99e673b13a075a65ec1d9befcd05a5323e6da4d435f3b617cdb3af83285c2df711ef39c01571827f9d2e1aca507b1e6203bddd296bb9b5a2021850bf2fe51011efd7ea2ef559df305727c5b75c764a9eb1f38f05fb732c3a738ebd02b01eade2a3ff4f065eebb96cf70ef864561faf2e2d8ab64de6f2403cd4335860945d2259681d9fc47508786bae186a54489576bced52e7a986603846c314b59164d18591323a739752634f40482f6e185aecd100dde233463f21e06c2aa0d61c046bebb729b891f8d4660c6cbf2f1f2e789e7364c70ecb38087cc90810c3789ef30c0dd973f0f1a572a089f81c");
        final AtomicReference<Address> someTokenAddress = new AtomicReference<>();
        final var altbn128PairingAddress = asHeadlongAddress("0x08");
        final var htsSystemContractAddress = asHeadlongAddress("0x0167");
        final var tokenInfoFn = new Function("getTokenInfo(address)");
        return hapiTest(
                recordStreamMustIncludeNoFailuresFrom(sidecarIdValidator()),
                uploadInitCode(contract),
                contractCreate(contract),
                tokenCreate("someToken").exposingAddressTo(someTokenAddress::set),
                // Generates CONTRACT_ACTION sidecars for a call to an EVM precompile
                // with insufficient gas
                contractCall(contract, "callRequested", altbn128PairingAddress, payload, BigInteger.valueOf(11_256))
                        .hasKnownStatus(CONTRACT_REVERT_EXECUTED),
                // Generates CONTRACT_ACTION sidecars for a call to an HTS
                // system contract with insufficient gas
                sourcing(() -> atomicBatch(contractCall(
                                        contract,
                                        "callRequested",
                                        htsSystemContractAddress,
                                        tokenInfoFn
                                                .encodeCallWithArgs(someTokenAddress.get())
                                                .array(),
                                        BigInteger.valueOf(1))
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)));
    }

    @HapiTest
    final Stream<DynamicTest> hollowCreationFailsCleanly() {
        final var contract = "HollowAccountCreator";
        return hapiTest(
                recordStreamMustIncludeNoFailuresFrom(sidecarIdValidator()),
                uploadInitCode(contract),
                contractCreate(contract),
                atomicBatch(contractCall(contract, "testCallFoo", randomHeadlongAddress(), BigInteger.valueOf(500_000L))
                                .sending(ONE_HBAR)
                                .gas(2_000_000L)
                                .via("callTransaction")
                                .hasKnownStatusFrom(SUCCESS, INVALID_SOLIDITY_ADDRESS)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                getTxnRecord("callTransaction").andAllChildRecords());
    }

    @HapiTest
    final Stream<DynamicTest> lowLevelEcrecCallBehavior() {
        final var TEST_CONTRACT = "TestContract";
        final var somebody = "somebody";
        final var account = "1";
        return hapiTest(
                uploadInitCode(TEST_CONTRACT),
                withOpContext((spec, log) -> allRunFor(
                        spec,
                        contractCreate(TEST_CONTRACT, numAsHeadlongAddress(spec, 2), BigInteger.ONE)
                                .balance(ONE_HBAR))),
                cryptoCreate(somebody),
                balanceSnapshot("start", account),
                cryptoUpdate(account).receiverSigRequired(true).signedBy(GENESIS),
                atomicBatch(contractCall(TEST_CONTRACT, "lowLevelECREC")
                                .payingWith(somebody)
                                .hasKnownStatus(SUCCESS)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                atomicBatch(contractCall(TEST_CONTRACT, "lowLevelECRECWithValue")
                                .payingWith(somebody)
                                .hasKnownStatus(INVALID_CONTRACT_ID)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                cryptoUpdate(account).receiverSigRequired(false).signedBy(GENESIS),
                atomicBatch(contractCall(TEST_CONTRACT, "lowLevelECREC")
                                .payingWith(somebody)
                                .hasKnownStatus(SUCCESS)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                atomicBatch(contractCall(TEST_CONTRACT, "lowLevelECRECWithValue")
                                .payingWith(somebody)
                                .hasKnownStatus(INVALID_CONTRACT_ID)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                getAccountBalance(account).hasTinyBars(changeFromSnapshot("start", +0)));
    }

    @HapiTest
    final Stream<DynamicTest> callsToSystemEntityNumsAreTreatedAsPrecompileCalls() {
        final var TEST_CONTRACT = "TestContract";
        final var ZERO_ADDRESS = 0L;
        final var zeroAddressTxn = "zeroAddressTxn";
        final var zeroAddressWithValueTxn = "zeroAddressWithValueTxn";
        final var ECREC_NUM = 1L;
        final var existingNumAndPrecompileDelegateCallTxn = "existingNumAndPrecompileDelegateCallTxn";
        final var existingNumAndPrecompileTxn = "existingNumAndPrecompileTxn";
        final var existingNumAndPrecompileWithValueTxn = "existingNumAndPrecompileWithValueTxn";
        final var EXISTING_SYSTEM_ENTITY_NUMBNO_PRECOMPILE_COLLISION = 50L;
        final var existingSystemEntityTxn = "existingSystemEntityTxn";
        final var existingSystemEntityWithValueTxn = "existingSystemEntityWithValueTxn";
        final var NON_EXISTING_SYSTEM_ENTITY_NUM = 345L;
        final var nonExistingSystemEntityTxn = "nonExistingSystemEntityTxn";
        final var nonExistingSystemEntityWithValueTxn = "nonExistingSystemEntityWithValueTxn";
        final var callSpecificAddressFunction = "callSpecific";
        final var callSpecificAddressWithValueFunction = "callSpecificWithValue";
        final var selfDestructToSystemAccountTxn = "selfDestructToSystemAccount";
        final var balanceOfSystemAccountTxn = "balanceTxn";
        final var delegateCallNonExistingPrecompile = "delegateCallNonExisting";
        final var delegateCallExistingAccountNonExistingPrecompile = "delegateCallExistingAccountNonExisting";
        final var failedResult = Bytes32.repeat((byte) 0);
        final ContractCallResult unsuccessfulResult = () -> failedResult;
        final ContractCallResult successfulResult = () -> failedResult.or(Bytes.of(1));
        return hapiTest(
                uploadInitCode(TEST_CONTRACT),
                contractCreate(
                                TEST_CONTRACT,
                                idAsHeadlongAddress(
                                        AccountID.newBuilder().setAccountNum(2).build()),
                                BigInteger.ONE)
                        .balance(ONE_HBAR),
                balanceSnapshot("initialBalance", TEST_CONTRACT),
                // call to 0x0
                atomicBatch(contractCall(
                                        TEST_CONTRACT,
                                        callSpecificAddressFunction,
                                        idAsHeadlongAddress(AccountID.newBuilder()
                                                .setAccountNum(ZERO_ADDRESS)
                                                .build()))
                                .via(zeroAddressTxn)
                                .hasKnownStatus(SUCCESS)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                // call with value to 0x0
                atomicBatch(contractCall(
                                        TEST_CONTRACT,
                                        callSpecificAddressWithValueFunction,
                                        idAsHeadlongAddress(AccountID.newBuilder()
                                                .setAccountNum(ZERO_ADDRESS)
                                                .build()))
                                .sending(500L)
                                .via(zeroAddressWithValueTxn)
                                .hasKnownStatus(INVALID_CONTRACT_ID)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                // call to existing account in the 0-750 range, without precompile collision on the same address
                atomicBatch(contractCall(
                                        TEST_CONTRACT,
                                        callSpecificAddressFunction,
                                        idAsHeadlongAddress(AccountID.newBuilder()
                                                .setAccountNum(EXISTING_SYSTEM_ENTITY_NUMBNO_PRECOMPILE_COLLISION)
                                                .build()))
                                .via(existingSystemEntityTxn)
                                .hasKnownStatus(SUCCESS)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                // call with value to existing account in the 0-750 range, without precompile collision on the
                // same address
                atomicBatch(contractCall(
                                        TEST_CONTRACT,
                                        callSpecificAddressWithValueFunction,
                                        idAsHeadlongAddress(AccountID.newBuilder()
                                                .setAccountNum(EXISTING_SYSTEM_ENTITY_NUMBNO_PRECOMPILE_COLLISION)
                                                .build()))
                                .via(existingSystemEntityWithValueTxn)
                                .sending(500L)
                                .hasKnownStatus(INVALID_CONTRACT_ID)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                // call to existing account in the 0-750 range, WITH precompile collision
                atomicBatch(contractCall(
                                        TEST_CONTRACT,
                                        callSpecificAddressFunction,
                                        idAsHeadlongAddress(AccountID.newBuilder()
                                                .setAccountNum(ECREC_NUM)
                                                .build()))
                                .via(existingNumAndPrecompileTxn)
                                .hasKnownStatus(SUCCESS)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                // call with value to existing account in the 0-750 range, WITH precompile collision
                atomicBatch(contractCall(
                                        TEST_CONTRACT,
                                        callSpecificAddressWithValueFunction,
                                        idAsHeadlongAddress(AccountID.newBuilder()
                                                .setAccountNum(ECREC_NUM)
                                                .build()))
                                .via(existingNumAndPrecompileWithValueTxn)
                                .sending(500L)
                                .hasKnownStatus(INVALID_CONTRACT_ID)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                // call to non-existing account in the 0-750 range
                atomicBatch(contractCall(
                                        TEST_CONTRACT,
                                        callSpecificAddressFunction,
                                        idAsHeadlongAddress(AccountID.newBuilder()
                                                .setAccountNum(NON_EXISTING_SYSTEM_ENTITY_NUM)
                                                .build()))
                                .via(nonExistingSystemEntityTxn)
                                .hasKnownStatus(SUCCESS)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                // call with value to non-existing account in the 0-750 range
                atomicBatch(contractCall(
                                        TEST_CONTRACT,
                                        callSpecificAddressWithValueFunction,
                                        idAsHeadlongAddress(AccountID.newBuilder()
                                                .setAccountNum(NON_EXISTING_SYSTEM_ENTITY_NUM)
                                                .build()))
                                .via(nonExistingSystemEntityWithValueTxn)
                                .sending(500L)
                                .hasKnownStatus(INVALID_CONTRACT_ID)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                // delegate call to collision address (0.0.1)
                atomicBatch(contractCall(
                                        TEST_CONTRACT,
                                        DELEGATE_CALL_SPECIFIC,
                                        idAsHeadlongAddress(AccountID.newBuilder()
                                                .setAccountNum(ECREC_NUM)
                                                .build()))
                                .via(existingNumAndPrecompileDelegateCallTxn)
                                .hasKnownStatus(SUCCESS)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                // delegate call existing account in the 0-750, but no corresponding precompile at that address
                atomicBatch(contractCall(
                                        TEST_CONTRACT,
                                        DELEGATE_CALL_SPECIFIC,
                                        idAsHeadlongAddress(AccountID.newBuilder()
                                                .setAccountNum(98)
                                                .build()))
                                .via(delegateCallExistingAccountNonExistingPrecompile)
                                .hasKnownStatus(SUCCESS)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                // delegate call non-existing address in the 0-750 range
                atomicBatch(contractCall(
                                        TEST_CONTRACT,
                                        DELEGATE_CALL_SPECIFIC,
                                        idAsHeadlongAddress(AccountID.newBuilder()
                                                .setAccountNum(125)
                                                .build()))
                                .via(delegateCallNonExistingPrecompile)
                                .hasKnownStatus(SUCCESS)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                // self-destruct with beneficiary in the 0-750 range
                atomicBatch(contractCall(
                                        TEST_CONTRACT,
                                        "selfDestructWithBeneficiary",
                                        idAsHeadlongAddress(AccountID.newBuilder()
                                                .setAccountNum(2)
                                                .build()))
                                .via(selfDestructToSystemAccountTxn)
                                .hasKnownStatus(INVALID_SOLIDITY_ADDRESS)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                // balance operation of an address in the 0-750 range
                atomicBatch(contractCall(
                                        TEST_CONTRACT,
                                        BALANCE_OF,
                                        idAsHeadlongAddress(AccountID.newBuilder()
                                                .setAccountNum(2)
                                                .build()))
                                .via(balanceOfSystemAccountTxn)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                getTxnRecord(zeroAddressTxn)
                        .hasPriority(recordWith().status(SUCCESS))
                        .logged(),
                getTxnRecord(zeroAddressWithValueTxn)
                        .hasPriority(recordWith().status(INVALID_CONTRACT_ID))
                        .logged(),
                getTxnRecord(existingSystemEntityTxn)
                        .hasPriority(
                                recordWith().contractCallResult(resultWith().contractCallResult(successfulResult)))
                        .logged(),
                getTxnRecord(existingSystemEntityWithValueTxn)
                        .hasPriority(recordWith().status(INVALID_CONTRACT_ID))
                        .logged(),
                getTxnRecord(existingNumAndPrecompileTxn)
                        .hasPriority(
                                recordWith().contractCallResult(resultWith().contractCallResult(successfulResult)))
                        .logged(),
                getTxnRecord(existingNumAndPrecompileWithValueTxn)
                        .hasPriority(recordWith().status(INVALID_CONTRACT_ID))
                        .logged(),
                getTxnRecord(nonExistingSystemEntityTxn)
                        .hasPriority(
                                recordWith().contractCallResult(resultWith().contractCallResult(successfulResult)))
                        .logged(),
                getTxnRecord(nonExistingSystemEntityWithValueTxn)
                        .hasPriority(recordWith().status(INVALID_CONTRACT_ID))
                        .logged(),
                getTxnRecord(existingNumAndPrecompileDelegateCallTxn)
                        .hasPriority(
                                recordWith().contractCallResult(resultWith().contractCallResult(successfulResult)))
                        .logged(),
                getTxnRecord(delegateCallNonExistingPrecompile)
                        .hasPriority(
                                recordWith().contractCallResult(resultWith().contractCallResult(successfulResult)))
                        .logged(),
                getTxnRecord(delegateCallExistingAccountNonExistingPrecompile)
                        .hasPriority(
                                recordWith().contractCallResult(resultWith().contractCallResult(successfulResult)))
                        .logged(),
                getTxnRecord(selfDestructToSystemAccountTxn)
                        .hasPriority(
                                recordWith().contractCallResult(resultWith().error(INVALID_SOLIDITY_ADDRESS.name())))
                        .logged(),
                getTxnRecord(balanceOfSystemAccountTxn)
                        .hasPriority(
                                recordWith().contractCallResult(resultWith().contractCallResult(unsuccessfulResult)))
                        .logged(),
                getAccountBalance(TEST_CONTRACT).hasTinyBars(changeFromSnapshot("initialBalance", 0)));
    }

    @HapiTest
    final Stream<DynamicTest> depositMoreThanBalanceFailsGracefully() {
        return hapiTest(
                uploadInitCode(PAY_RECEIVABLE_CONTRACT),
                cryptoCreate(ACCOUNT).balance(ONE_HBAR - 1),
                contractCreate(PAY_RECEIVABLE_CONTRACT).adminKey(THRESHOLD),
                atomicBatch(contractCall(PAY_RECEIVABLE_CONTRACT, DEPOSIT, BigInteger.valueOf(ONE_HBAR))
                                .via(PAY_TXN)
                                .payingWith(ACCOUNT)
                                .sending(ONE_HBAR)
                                .hasPrecheck(INSUFFICIENT_PAYER_BALANCE)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasPrecheck(INSUFFICIENT_PAYER_BALANCE));
    }

    @HapiTest
    final Stream<DynamicTest> nestedContractCannotOverSendValue() {
        return hapiTest(
                cryptoCreate(ACCOUNT).balance(ONE_MILLION_HBARS),
                cryptoCreate(RECEIVER).balance(10_000L),
                uploadInitCode(NESTED_TRANSFERRING_CONTRACT, NESTED_TRANSFER_CONTRACT),
                contractCustomCreate(NESTED_TRANSFER_CONTRACT, "1")
                        .balance(10_000L)
                        .payingWith(ACCOUNT),
                contractCustomCreate(NESTED_TRANSFER_CONTRACT, "2")
                        .balance(10_000L)
                        .payingWith(ACCOUNT),
                getAccountInfo(RECEIVER).savingSnapshot(RECEIVER_INFO),
                withOpContext((spec, log) -> {
                    var receiverAddr =
                            spec.registry().getAccountInfo(RECEIVER_INFO).getContractAccountID();

                    allRunFor(
                            spec,
                            contractCreate(
                                            NESTED_TRANSFERRING_CONTRACT,
                                            asHeadlongAddress(
                                                    getNestedContractAddress(NESTED_TRANSFER_CONTRACT + "1", spec)),
                                            asHeadlongAddress(
                                                    getNestedContractAddress(NESTED_TRANSFER_CONTRACT + "2", spec)))
                                    .balance(10_000L)
                                    .payingWith(ACCOUNT),
                            atomicBatch(contractCall(
                                                    NESTED_TRANSFERRING_CONTRACT,
                                                    "transferFromDifferentAddressesToAddress",
                                                    asHeadlongAddress(receiverAddr),
                                                    BigInteger.valueOf(40_000L))
                                            .hasKnownStatus(CONTRACT_REVERT_EXECUTED)
                                            .payingWith(ACCOUNT)
                                            .logged()
                                            .batchKey(BATCH_OPERATOR))
                                    .payingWith(BATCH_OPERATOR)
                                    .hasKnownStatus(INNER_TRANSACTION_FAILED));
                }),
                getAccountBalance(RECEIVER).hasTinyBars(10_000L),
                sourcing(() -> getContractInfo(NESTED_TRANSFER_CONTRACT + "1")
                        .has(contractWith().balance(10_000L))),
                sourcing(() -> getContractInfo(NESTED_TRANSFER_CONTRACT + "2")
                        .has(contractWith().balance(10_000L))));
    }

    @HapiTest
    final Stream<DynamicTest> whitelistingAliasedContract() {
        final var creationTxn = "creationTxn";
        final var mirrorWhitelistCheckTxn = "mirrorWhitelistCheckTxn";
        final var evmWhitelistCheckTxn = "evmWhitelistCheckTxn";

        final var WHITELISTER = "Whitelister";
        final var CREATOR = "Creator";

        final AtomicReference<String> childMirror = new AtomicReference<>();
        final AtomicReference<String> childEip1014 = new AtomicReference<>();

        return hapiTest(
                sourcing(() -> createLargeFile(DEFAULT_PAYER, WHITELISTER, literalInitcodeFor("Whitelister"))),
                sourcing(() -> createLargeFile(DEFAULT_PAYER, CREATOR, literalInitcodeFor("Creator"))),
                withOpContext((spec, op) -> allRunFor(
                        spec,
                        contractCreate(WHITELISTER).payingWith(DEFAULT_PAYER).gas(GAS_TO_OFFER),
                        contractCreate(CREATOR)
                                .payingWith(DEFAULT_PAYER)
                                .gas(GAS_TO_OFFER)
                                .via(creationTxn))),
                captureChildCreate2MetaFor(1, 0, "setup", creationTxn, childMirror, childEip1014),
                withOpContext((spec, op) -> allRunFor(
                        spec,
                        atomicBatch(
                                        contractCall(
                                                        WHITELISTER,
                                                        "addToWhitelist",
                                                        asHeadlongAddress(childEip1014.get()))
                                                .payingWith(DEFAULT_PAYER)
                                                .batchKey(BATCH_OPERATOR),
                                        contractCallWithFunctionAbi(
                                                        asContractString(contractIdFromHexedMirrorAddress(
                                                                spec, childMirror.get())),
                                                        getABIFor(FUNCTION, "isWhitelisted", WHITELISTER),
                                                        asHeadlongAddress(getNestedContractAddress(WHITELISTER, spec)))
                                                .payingWith(DEFAULT_PAYER)
                                                .via(mirrorWhitelistCheckTxn)
                                                .batchKey(BATCH_OPERATOR),
                                        contractCall(
                                                        CREATOR,
                                                        "isWhitelisted",
                                                        asHeadlongAddress(getNestedContractAddress(WHITELISTER, spec)))
                                                .payingWith(DEFAULT_PAYER)
                                                .via(evmWhitelistCheckTxn)
                                                .batchKey(BATCH_OPERATOR))
                                .payingWith(BATCH_OPERATOR))),
                getTxnRecord(mirrorWhitelistCheckTxn)
                        .hasPriority(
                                recordWith().contractCallResult(resultWith().contractCallResult(bigIntResult(1)))),
                getTxnRecord(evmWhitelistCheckTxn)
                        .hasPriority(
                                recordWith().contractCallResult(resultWith().contractCallResult(bigIntResult(1)))));
    }

    @HapiTest
    final Stream<DynamicTest> cannotUseMirrorAddressOfAliasedContractInPrecompileMethod() {
        final var creationTxn = "creationTxn";
        final var ASSOCIATOR = "Associator";

        final AtomicReference<String> childMirror = new AtomicReference<>();
        final AtomicReference<String> childEip1014 = new AtomicReference<>();
        final AtomicReference<TokenID> tokenID = new AtomicReference<>();

        return hapiTest(
                cryptoCreate("Treasury"),
                sourcing(() -> createLargeFile(DEFAULT_PAYER, ASSOCIATOR, literalInitcodeFor("Associator"))),
                withOpContext((spec, op) -> allRunFor(
                        spec,
                        contractCreate(ASSOCIATOR)
                                .payingWith(DEFAULT_PAYER)
                                .bytecode(ASSOCIATOR)
                                .gas(GAS_TO_OFFER)
                                .via(creationTxn))),
                withOpContext((spec, op) -> {
                    allRunFor(
                            spec,
                            captureChildCreate2MetaFor(1, 0, "setup", creationTxn, childMirror, childEip1014),
                            tokenCreate("TokenA")
                                    .initialSupply(100)
                                    .treasury("Treasury")
                                    .exposingCreatedIdTo(id -> tokenID.set(asToken(id))));
                    final var create2address = childEip1014.get();
                    final var mirrorAddress = childMirror.get();
                    allRunFor(
                            spec,
                            atomicBatch(contractCall(
                                                    ASSOCIATOR,
                                                    "associate",
                                                    asHeadlongAddress(mirrorAddress),
                                                    asHeadlongAddress(asAddress(tokenID.get())))
                                            .hasKnownStatus(CONTRACT_REVERT_EXECUTED)
                                            .gas(GAS_TO_OFFER)
                                            .via("NOPE")
                                            .batchKey(BATCH_OPERATOR))
                                    .payingWith(BATCH_OPERATOR)
                                    .hasKnownStatus(INNER_TRANSACTION_FAILED),
                            childRecordsCheck(
                                    "NOPE",
                                    CONTRACT_REVERT_EXECUTED,
                                    recordWith().status(INVALID_ACCOUNT_ID)),
                            atomicBatch(contractCall(
                                                    ASSOCIATOR,
                                                    "associate",
                                                    asHeadlongAddress(create2address),
                                                    asHeadlongAddress(asAddress(tokenID.get())))
                                            .gas(GAS_TO_OFFER)
                                            .batchKey(BATCH_OPERATOR))
                                    .payingWith(BATCH_OPERATOR));
                }));
    }

    @SuppressWarnings("java:S5669")
    @HapiTest
    final Stream<DynamicTest> bitcarbonTestStillPasses() {
        final var addressBook = "AddressBook";
        final var jurisdictions = "Jurisdictions";
        final var minters = "Minters";
        final var addJurisTxn = "addJurisTxn";
        final var historicalAddress = "1234567890123456789012345678901234567890";
        final AtomicReference<byte[]> nyJurisCode = new AtomicReference<>();
        final AtomicReference<byte[]> defaultPayerMirror = new AtomicReference<>();
        final AtomicReference<String> addressBookMirror = new AtomicReference<>();
        final AtomicReference<String> jurisdictionMirror = new AtomicReference<>();
        return hapiTest(
                getAccountInfo(DEFAULT_CONTRACT_SENDER).savingSnapshot(DEFAULT_CONTRACT_SENDER),
                withOpContext((spec, opLog) -> defaultPayerMirror.set((unhex(
                        spec.registry().getAccountInfo(DEFAULT_CONTRACT_SENDER).getContractAccountID())))),
                uploadInitCode(addressBook, jurisdictions),
                // refusingEthConversion because the minters contract has placeholders that the
                // HapiEthereumContractCreate doesn't
                // support
                contractCreate(addressBook)
                        .gas(1_000_000L)
                        .exposingContractIdTo(id -> addressBookMirror.set(
                                asHexedSolidityAddress((int) id.getShardNum(), id.getRealmNum(), id.getContractNum())))
                        .payingWith(DEFAULT_CONTRACT_SENDER)
                        .refusingEthConversion(),
                contractCreate(jurisdictions)
                        .gas(4_000_000L)
                        .exposingContractIdTo(id -> jurisdictionMirror.set(
                                asHexedSolidityAddress((int) id.getShardNum(), id.getRealmNum(), id.getContractNum())))
                        .withExplicitParams(() -> EXPLICIT_JURISDICTION_CONS_PARAMS)
                        .payingWith(DEFAULT_CONTRACT_SENDER)
                        .refusingEthConversion(),
                sourcing(() -> createLargeFile(
                        DEFAULT_CONTRACT_SENDER,
                        minters,
                        bookInterpolated(literalInitcodeFor(minters).toByteArray(), addressBookMirror.get()))),
                contractCreate(minters)
                        .gas(2_000_000L)
                        .withExplicitParams(
                                () -> String.format(EXPLICIT_MINTER_CONS_PARAMS_TPL, jurisdictionMirror.get()))
                        .payingWith(DEFAULT_CONTRACT_SENDER)
                        .refusingEthConversion(),
                atomicBatch(
                                contractCall(minters)
                                        .withExplicitParams(() -> String.format(
                                                EXPLICIT_MINTER_CONFIG_PARAMS_TPL, jurisdictionMirror.get()))
                                        .batchKey(BATCH_OPERATOR),
                                contractCall(jurisdictions)
                                        .withExplicitParams(() -> EXPLICIT_JURISDICTIONS_ADD_PARAMS)
                                        .via(addJurisTxn)
                                        .gas(1_000_000)
                                        .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                getTxnRecord(addJurisTxn)
                        .exposingFilteredCallResultVia(
                                getABIForContract(jurisdictions),
                                "JurisdictionAdded",
                                data -> nyJurisCode.set((byte[]) data[0])),
                sourcing(() -> logIt("NY juris code is " + CommonUtils.hex(nyJurisCode.get()))),
                sourcing(() -> contractCallLocal(jurisdictions, "isValid", nyJurisCode.get())
                        .has(resultWith()
                                .resultThruAbi(
                                        getABIFor(FUNCTION, "isValid", jurisdictions),
                                        isLiteralResult(new Object[] {Boolean.TRUE})))),
                contractCallLocal(minters, "seven")
                        .has(resultWith()
                                .resultThruAbi(
                                        getABIFor(FUNCTION, "seven", minters),
                                        isLiteralResult(new Object[] {BigInteger.valueOf(7L)}))),
                sourcing(() -> contractCallLocal(minters, OWNER)
                        .has(resultWith()
                                .resultThruAbi(
                                        getABIFor(FUNCTION, OWNER, minters),
                                        isLiteralResult(new Object[] {asHeadlongAddress(defaultPayerMirror.get())})))),
                sourcing(() -> contractCallLocal(jurisdictions, OWNER)
                        .has(resultWith()
                                .resultThruAbi(
                                        getABIFor(FUNCTION, OWNER, minters),
                                        isLiteralResult(new Object[] {asHeadlongAddress(defaultPayerMirror.get())})))),
                sourcing(() -> atomicBatch(contractCall(
                                        minters,
                                        "add",
                                        asHeadlongAddress(historicalAddress),
                                        "Peter",
                                        nyJurisCode.get())
                                .gas(1_000_000)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)));
    }

    @HapiTest
    final Stream<DynamicTest> exchangeRatePrecompileWorks() {
        final var valueToTinycentCall = "recoverUsd";
        final var rateAware = "ExchangeRatePrecompile";
        // Must send $6.66 USD to access the gated method
        final var minPriceToAccessGatedMethod = 666L;
        final var minValueToAccessGatedMethodAtCurrentRate = new AtomicLong();

        return hapiTest(
                uploadInitCode(rateAware),
                contractCreate(rateAware, BigInteger.valueOf(minPriceToAccessGatedMethod)),
                withOpContext((spec, opLog) -> {
                    final var rates = spec.ratesProvider().rates();
                    minValueToAccessGatedMethodAtCurrentRate.set(minPriceToAccessGatedMethod
                            * TINY_PARTS_PER_WHOLE
                            * rates.getHbarEquiv()
                            / rates.getCentEquiv());
                    LOG.info(
                            "Requires {} tinybar of value to access the method",
                            minValueToAccessGatedMethodAtCurrentRate::get);
                }),
                sourcing(() -> atomicBatch(contractCall(rateAware, "gatedAccess")
                                .sending(minValueToAccessGatedMethodAtCurrentRate.get() - 1)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)),
                sourcing(() -> atomicBatch(contractCall(rateAware, "gatedAccess")
                                .sending(minValueToAccessGatedMethodAtCurrentRate.get())
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)),
                sourcing(() -> atomicBatch(contractCall(rateAware, "approxUsdValue")
                                .sending(minValueToAccessGatedMethodAtCurrentRate.get())
                                .via(valueToTinycentCall)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)),
                getTxnRecord(valueToTinycentCall)
                        .hasPriority(recordWith()
                                .contractCallResult(resultWith()
                                        .resultViaFunctionName(
                                                "approxUsdValue", rateAware, isLiteralResult(new Object[] {
                                                    BigInteger.valueOf(
                                                            minPriceToAccessGatedMethod * TINY_PARTS_PER_WHOLE)
                                                }))))
                        .logged(),
                sourcing(() -> atomicBatch(contractCall(rateAware, "invalidCall")
                                .sending(minValueToAccessGatedMethodAtCurrentRate.get())
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)),
                sourcing(() -> atomicBatch(contractCall(
                                        rateAware,
                                        "callWithValue",
                                        BigInteger.valueOf(minValueToAccessGatedMethodAtCurrentRate.get()))
                                .sending(minValueToAccessGatedMethodAtCurrentRate.get())
                                .hasKnownStatus(INVALID_CONTRACT_ID)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)));
    }

    /**
     * This test characterizes a difference in behavior between the ERC721 {@code tokenURI()} and
     * HTS {@code getNonFungibleTokenInfo()} methods. The HTS method will leave non-UTF-8 bytes
     * as-is, while the ERC721 method will replace them with the Unicode replacement character.
     *
     * @return a spec characterizing this behavior
     */
    @SuppressWarnings("java:S5960")
    @HapiTest
    final Stream<DynamicTest> erc721TokenUriAndHtsNftInfoTreatNonUtf8BytesDifferently() {
        final var contractAlternatives = "ErcAndHtsAlternatives";
        final AtomicReference<Address> nftAddr = new AtomicReference<>();
        final var viaErc721TokenURI = "erc721TokenURI";
        final var viaHtsNftInfo = "viaHtsNftInfo";
        // Valid UTF-8 bytes cannot include 0xff
        final var hexedNonUtf8Meta = "ff";

        return hapiTest(
                uploadInitCode(contractAlternatives),
                contractCreate(contractAlternatives),
                tokenCreate("nft")
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .exposingAddressTo(nftAddr::set)
                        .initialSupply(0)
                        .supplyKey(DEFAULT_PAYER)
                        .treasury(DEFAULT_PAYER),
                mintToken("nft", List.of(ByteString.copyFrom(CommonUtils.unhex(hexedNonUtf8Meta)))),
                sourcing(() -> atomicBatch(contractCall(
                                        contractAlternatives,
                                        "canGetMetadataViaERC",
                                        nftAddr.get(),
                                        BigInteger.valueOf(1))
                                .via(viaErc721TokenURI)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)),
                sourcing(() -> atomicBatch(contractCall(
                                        contractAlternatives,
                                        "canGetMetadataViaHTS",
                                        nftAddr.get(),
                                        BigInteger.valueOf(1))
                                .via(viaHtsNftInfo)
                                .gas(1_000_000)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)),
                withOpContext((spec, opLog) -> {
                    final var getErcResult = getTxnRecord(viaErc721TokenURI);
                    final var getHtsResult = getTxnRecord(viaHtsNftInfo);
                    CustomSpecAssert.allRunFor(spec, getErcResult, getHtsResult);

                    ABIType<Tuple> decoder = TypeFactory.create("(bytes)");

                    final var htsResult = getHtsResult
                            .getResponseRecord()
                            .getContractCallResult()
                            .getContractCallResult();
                    final var htsMetadata = decoder.decode(htsResult.toByteArray());
                    // The HTS method leaves non-UTF-8 bytes as-is
                    assertEquals(hexedNonUtf8Meta, CommonUtils.hex((byte[]) htsMetadata.get(0)));

                    final var ercResult = getErcResult
                            .getResponseRecord()
                            .getContractCallResult()
                            .getContractCallResult();
                    // But the ERC721 method returns the Unicode replacement
                    // character
                    final var ercMetadata = decoder.decode(ercResult.toByteArray());
                    assertEquals("efbfbd", CommonUtils.hex((byte[]) ercMetadata.get(0)));
                }));
    }

    @HapiTest
    final Stream<DynamicTest> imapUserExercise() {
        final var contract = "User";
        final var insert1To4 = "insert1To10";
        final var insert2To8 = "insert2To8";
        final var insert3To16 = "insert3To16";
        final var remove2 = "remove2";
        final var gasToOffer = 400_000;

        return hapiTest(
                uploadInitCode(contract),
                contractCreate(contract),
                atomicBatch(
                                contractCall(contract, INSERT, BigInteger.ONE, BigInteger.valueOf(4))
                                        .gas(gasToOffer)
                                        .via(insert1To4)
                                        .batchKey(BATCH_OPERATOR),
                                contractCall(contract, INSERT, BigInteger.TWO, BigInteger.valueOf(8))
                                        .gas(gasToOffer)
                                        .via(insert2To8)
                                        .batchKey(BATCH_OPERATOR),
                                contractCall(contract, INSERT, BigInteger.valueOf(3), BigInteger.valueOf(16))
                                        .gas(gasToOffer)
                                        .via(insert3To16)
                                        .batchKey(BATCH_OPERATOR),
                                contractCall(contract, "remove", BigInteger.TWO)
                                        .gas(gasToOffer)
                                        .via(remove2)
                                        .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR));
    }

    // For this test we use refusingEthConversion() for the Eth Call isomer,
    // since we should modify the expected balances and change the test itself in order to pass with
    // Eth Calls
    @HapiTest
    final Stream<DynamicTest> ocToken() {
        final var contract = "OcToken";

        return hapiTest(
                cryptoCreate(TOKEN_ISSUER).balance(1_000_000_000_000L),
                cryptoCreate(ALICE).balance(10_000_000_000L).payingWith(TOKEN_ISSUER),
                cryptoCreate("Bob").balance(10_000_000_000L).payingWith(TOKEN_ISSUER),
                cryptoCreate("Carol").balance(10_000_000_000L).payingWith(TOKEN_ISSUER),
                cryptoCreate("Dave").balance(10_000_000_000L).payingWith(TOKEN_ISSUER),
                getAccountInfo(TOKEN_ISSUER).savingSnapshot("tokenIssuerAcctInfo"),
                getAccountInfo(ALICE).savingSnapshot("AliceAcctInfo"),
                getAccountInfo("Bob").savingSnapshot("BobAcctInfo"),
                getAccountInfo("Carol").savingSnapshot("CarolAcctInfo"),
                getAccountInfo("Dave").savingSnapshot("DaveAcctInfo"),
                uploadInitCode(contract),
                contractCreate(contract, BigInteger.valueOf(1_000_000L), "OpenCrowd Token", "OCT")
                        .gas(1_000_000L)
                        .payingWith(TOKEN_ISSUER)
                        .via("tokenCreateTxn")
                        .refusingEthConversion(),
                assertionsHold((spec, ctxLog) -> {
                    final var issuerEthAddress = spec.registry()
                            .getAccountInfo("tokenIssuerAcctInfo")
                            .getContractAccountID();
                    final var aliceEthAddress =
                            spec.registry().getAccountInfo("AliceAcctInfo").getContractAccountID();
                    final var bobEthAddress =
                            spec.registry().getAccountInfo("BobAcctInfo").getContractAccountID();
                    final var carolEthAddress =
                            spec.registry().getAccountInfo("CarolAcctInfo").getContractAccountID();
                    final var daveEthAddress =
                            spec.registry().getAccountInfo("DaveAcctInfo").getContractAccountID();

                    final var subop1 =
                            getContractInfo(contract).nodePayment(10L).saveToRegistry("tokenContract");

                    final var subop3 = contractCallLocal(contract, DECIMALS)
                            .saveResultTo(DECIMALS)
                            .payingWith(TOKEN_ISSUER);

                    // Note: This contract call will cause a INSUFFICIENT_TX_FEE
                    // error, not sure why.
                    final var subop4 = contractCallLocal(contract, "symbol")
                            .saveResultTo("token_symbol")
                            .payingWith(TOKEN_ISSUER)
                            .hasAnswerOnlyPrecheckFrom(OK, INSUFFICIENT_TX_FEE);

                    final var subop5 = contractCallLocal(contract, BALANCE_OF, asHeadlongAddress(issuerEthAddress))
                            .gas(250_000L)
                            .saveResultTo(ISSUER_TOKEN_BALANCE);

                    allRunFor(spec, subop1, subop3, subop4, subop5);

                    final var funcSymbol = Function.fromJson(getABIFor(FUNCTION, "symbol", contract));

                    final var symbol = getValueFromRegistry(spec, "token_symbol", funcSymbol);

                    ctxLog.info("symbol: [{}]", symbol);

                    assertEquals("OCT", symbol, "TokenIssuer's symbol should be fixed value");
                    final var funcDecimals = Function.fromJson(getABIFor(FUNCTION, DECIMALS, contract));

                    final Integer decimals = getValueFromRegistry(spec, DECIMALS, funcDecimals);

                    ctxLog.info("decimals {}", decimals);
                    assertEquals(3, decimals, "TokenIssuer's decimals should be fixed value");

                    final long tokenMultiplier = (long) Math.pow(10, decimals);

                    final var function = Function.fromJson(getABIFor(FUNCTION, BALANCE_OF, contract));

                    long issuerBalance =
                            ((BigInteger) getValueFromRegistry(spec, ISSUER_TOKEN_BALANCE, function)).longValue();

                    ctxLog.info("initial balance of Issuer {}", issuerBalance / tokenMultiplier);
                    assertEquals(
                            1_000_000,
                            issuerBalance / tokenMultiplier,
                            "TokenIssuer's initial token balance should be" + " 1_000_000");

                    //  Do token transfers
                    final var subop6 = atomicBatch(contractCall(
                                            contract,
                                            TRANSFER,
                                            asHeadlongAddress(aliceEthAddress),
                                            BigInteger.valueOf(1000 * tokenMultiplier))
                                    .gas(250_000L)
                                    .payingWith(TOKEN_ISSUER)
                                    .refusingEthConversion()
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR);

                    final var subop7 = atomicBatch(contractCall(
                                            contract,
                                            TRANSFER,
                                            asHeadlongAddress(bobEthAddress),
                                            BigInteger.valueOf(2000 * tokenMultiplier))
                                    .gas(250_000L)
                                    .payingWith(TOKEN_ISSUER)
                                    .refusingEthConversion()
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR);

                    final var subop8 = atomicBatch(contractCall(
                                            contract,
                                            TRANSFER,
                                            asHeadlongAddress(carolEthAddress),
                                            BigInteger.valueOf(500 * tokenMultiplier))
                                    .gas(250_000L)
                                    .payingWith("Bob")
                                    .refusingEthConversion()
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR);

                    final var subop9 = contractCallLocal(contract, BALANCE_OF, asHeadlongAddress(aliceEthAddress))
                            .gas(250_000L)
                            .saveResultTo(ALICE_TOKEN_BALANCE);

                    final var subop10 = contractCallLocal(contract, BALANCE_OF, asHeadlongAddress(carolEthAddress))
                            .gas(250_000L)
                            .saveResultTo(CAROL_TOKEN_BALANCE);

                    final var subop11 = contractCallLocal(contract, BALANCE_OF, asHeadlongAddress(bobEthAddress))
                            .gas(250_000L)
                            .saveResultTo(BOB_TOKEN_BALANCE);

                    allRunFor(spec, subop6, subop7, subop8, subop9, subop10, subop11);

                    var aliceBalance =
                            ((BigInteger) getValueFromRegistry(spec, ALICE_TOKEN_BALANCE, function)).longValue();
                    var bobBalance = ((BigInteger) getValueFromRegistry(spec, BOB_TOKEN_BALANCE, function)).longValue();
                    var carolBalance =
                            ((BigInteger) getValueFromRegistry(spec, CAROL_TOKEN_BALANCE, function)).longValue();

                    ctxLog.info("aliceBalance  {}", aliceBalance / tokenMultiplier);
                    ctxLog.info("bobBalance  {}", bobBalance / tokenMultiplier);
                    ctxLog.info("carolBalance  {}", carolBalance / tokenMultiplier);

                    assertEquals(1000, aliceBalance / tokenMultiplier, "Alice's token balance should be 1_000");

                    final var subop12 = atomicBatch(contractCall(
                                            contract,
                                            "approve",
                                            asHeadlongAddress(daveEthAddress),
                                            BigInteger.valueOf(200 * tokenMultiplier))
                                    .gas(250_000L)
                                    .payingWith(ALICE)
                                    .refusingEthConversion()
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR);

                    final var subop13 = atomicBatch(contractCall(
                                            contract,
                                            "transferFrom",
                                            asHeadlongAddress(aliceEthAddress),
                                            asHeadlongAddress(bobEthAddress),
                                            BigInteger.valueOf(100 * tokenMultiplier))
                                    .gas(250_000L)
                                    .payingWith("Dave")
                                    .refusingEthConversion()
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR);

                    final var subop14 = contractCallLocal(contract, BALANCE_OF, asHeadlongAddress(aliceEthAddress))
                            .gas(250_000L)
                            .saveResultTo(ALICE_TOKEN_BALANCE);

                    final var subop15 = contractCallLocal(contract, BALANCE_OF, asHeadlongAddress(bobEthAddress))
                            .gas(250_000L)
                            .saveResultTo(BOB_TOKEN_BALANCE);

                    final var subop16 = contractCallLocal(contract, BALANCE_OF, asHeadlongAddress(carolEthAddress))
                            .gas(250_000L)
                            .saveResultTo(CAROL_TOKEN_BALANCE);

                    final var subop17 = contractCallLocal(contract, BALANCE_OF, asHeadlongAddress(daveEthAddress))
                            .gas(250_000L)
                            .saveResultTo("daveTokenBalance");

                    final var subop18 = contractCallLocal(contract, BALANCE_OF, asHeadlongAddress(issuerEthAddress))
                            .gas(250_000L)
                            .saveResultTo(ISSUER_TOKEN_BALANCE);

                    allRunFor(spec, subop12, subop13, subop14, subop15, subop16, subop17, subop18);

                    final var daveBalance =
                            ((BigInteger) getValueFromRegistry(spec, "daveTokenBalance", function)).longValue();
                    aliceBalance = ((BigInteger) getValueFromRegistry(spec, ALICE_TOKEN_BALANCE, function)).longValue();
                    bobBalance = ((BigInteger) getValueFromRegistry(spec, BOB_TOKEN_BALANCE, function)).longValue();
                    carolBalance = ((BigInteger) getValueFromRegistry(spec, CAROL_TOKEN_BALANCE, function)).longValue();
                    issuerBalance =
                            ((BigInteger) getValueFromRegistry(spec, ISSUER_TOKEN_BALANCE, function)).longValue();

                    ctxLog.info("aliceBalance at end {}", aliceBalance / tokenMultiplier);
                    ctxLog.info("bobBalance at end {}", bobBalance / tokenMultiplier);
                    ctxLog.info("carolBalance at end {}", carolBalance / tokenMultiplier);
                    ctxLog.info("daveBalance at end {}", daveBalance / tokenMultiplier);
                    ctxLog.info("issuerBalance at end {}", issuerBalance / tokenMultiplier);

                    assertEquals(
                            997000, issuerBalance / tokenMultiplier, "TokenIssuer's final balance should be 997000");

                    assertEquals(900, aliceBalance / tokenMultiplier, "Alice's final balance should be 900");
                    assertEquals(1600, bobBalance / tokenMultiplier, "Bob's final balance should be 1600");
                    assertEquals(500, carolBalance / tokenMultiplier, "Carol's final balance should be 500");
                    assertEquals(0, daveBalance / tokenMultiplier, "Dave's final balance should be 0");
                }),
                getContractRecords(contract).hasCostAnswerPrecheck(NOT_SUPPORTED),
                getContractRecords(contract).nodePayment(100L).hasAnswerOnlyPrecheck(NOT_SUPPORTED));
    }

    private <T> T getValueFromRegistry(HapiSpec spec, String from, Function function) {
        byte[] value = spec.registry().getBytes(from);

        T decodedReturnedValue;
        if (function.getOutputs().equals(TupleType.parse("(string)"))) {
            decodedReturnedValue = (T) "";
        } else {
            decodedReturnedValue = (T) new byte[0];
        }

        if (value.length > 0) {
            Tuple retResults = function.decodeReturn(value);
            decodedReturnedValue = (T) retResults.get(0);
        }
        return decodedReturnedValue;
    }

    @HapiTest
    final Stream<DynamicTest> multipleSelfDestructsAreSafe() {
        final var contract = "Fuse";
        return hapiTest(
                uploadInitCode(contract),
                contractCreate(contract).gas(600_000),
                atomicBatch(contractCall(contract, "light")
                                .via("lightTxn")
                                .withTxnTransform(tx -> tx)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                getTxnRecord("lightTxn"));
    }

    @HapiTest
    final Stream<DynamicTest> depositSuccess() {
        return hapiTest(
                uploadInitCode(PAY_RECEIVABLE_CONTRACT),
                contractCreate(PAY_RECEIVABLE_CONTRACT).adminKey(THRESHOLD),
                atomicBatch(contractCall(PAY_RECEIVABLE_CONTRACT, DEPOSIT, BigInteger.valueOf(DEPOSIT_AMOUNT))
                                .via(PAY_TXN)
                                .sending(DEPOSIT_AMOUNT)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                getTxnRecord(PAY_TXN)
                        .hasPriority(
                                recordWith().contractCallResult(resultWith().logs(inOrder()))));
    }

    @HapiTest
    final Stream<DynamicTest> multipleDepositSuccess() {
        return hapiTest(
                uploadInitCode(PAY_RECEIVABLE_CONTRACT),
                contractCreate(PAY_RECEIVABLE_CONTRACT).adminKey(THRESHOLD),
                withOpContext((spec, opLog) -> {
                    for (int i = 0; i < 10; i++) {
                        final var subOp1 = balanceSnapshot("payerBefore", PAY_RECEIVABLE_CONTRACT);
                        final var subOp2 = atomicBatch(contractCall(
                                                PAY_RECEIVABLE_CONTRACT, DEPOSIT, BigInteger.valueOf(DEPOSIT_AMOUNT))
                                        .via(PAY_TXN)
                                        .sending(DEPOSIT_AMOUNT)
                                        .batchKey(BATCH_OPERATOR))
                                .payingWith(BATCH_OPERATOR);
                        final var subOp3 = getAccountBalance(PAY_RECEIVABLE_CONTRACT)
                                .hasTinyBars(changeFromSnapshot("payerBefore", +DEPOSIT_AMOUNT));
                        allRunFor(spec, subOp1, subOp2, subOp3);
                    }
                }));
    }

    @HapiTest
    final Stream<DynamicTest> depositDeleteSuccess() {
        final var initBalance = 7890L;
        return hapiTest(
                cryptoCreate(BENEFICIARY).balance(initBalance),
                uploadInitCode(PAY_RECEIVABLE_CONTRACT),
                contractCreate(PAY_RECEIVABLE_CONTRACT)
                        .adminKey(THRESHOLD)
                        // Refusing ethereum create conversion, because we get INVALID_SIGNATURE upon
                        // tokenAssociate,
                        // since we have CONTRACT_ID key
                        .refusingEthConversion(),
                atomicBatch(contractCall(PAY_RECEIVABLE_CONTRACT, DEPOSIT, BigInteger.valueOf(DEPOSIT_AMOUNT))
                                .via(PAY_TXN)
                                .sending(DEPOSIT_AMOUNT)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                contractDelete(PAY_RECEIVABLE_CONTRACT).transferAccount(BENEFICIARY),
                getAccountBalance(BENEFICIARY).hasTinyBars(initBalance + DEPOSIT_AMOUNT));
    }

    @HapiTest
    final Stream<DynamicTest> payableSuccess() {
        return hapiTest(
                uploadInitCode(PAY_RECEIVABLE_CONTRACT),
                contractCreate(PAY_RECEIVABLE_CONTRACT).adminKey(THRESHOLD).gas(1_000_000),
                atomicBatch(contractCall(PAY_RECEIVABLE_CONTRACT)
                                .via(PAY_TXN)
                                .sending(DEPOSIT_AMOUNT)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                getTxnRecord(PAY_TXN)
                        .hasPriority(recordWith()
                                .contractCallResult(
                                        resultWith().logs(inOrder(logWith().longAtBytes(DEPOSIT_AMOUNT, 24))))));
    }

    @HapiTest
    final Stream<DynamicTest> insufficientGas() {
        return hapiTest(
                uploadInitCode(SIMPLE_STORAGE_CONTRACT),
                contractCreate(SIMPLE_STORAGE_CONTRACT).adminKey(THRESHOLD),
                getContractInfo(SIMPLE_STORAGE_CONTRACT).saveToRegistry("simpleStorageInfo"),
                atomicBatch(contractCall(SIMPLE_STORAGE_CONTRACT, "get")
                                .via("simpleStorageTxn")
                                .gas(0L)
                                .hasPrecheck(INSUFFICIENT_GAS)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasPrecheck(INSUFFICIENT_GAS));
    }

    @HapiTest
    final Stream<DynamicTest> insufficientFee() {
        final var contract = CREATE_TRIVIAL;

        // FUTURE: Once we add the check again that compares the estimated gas with the maximum transaction fee,
        // this test will need to be updated and expect INSUFFICIENT_TX_FEE.
        return hapiTest(
                cryptoCreate("accountToPay"),
                uploadInitCode(contract),
                contractCreate(contract),
                atomicBatch(contractCall(contract, "create")
                                .fee(0L)
                                .payingWith("accountToPay")
                                .gas(400_000L)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR));
    }

    @HapiTest
    final Stream<DynamicTest> nonPayable() {
        final var contract = CREATE_TRIVIAL;

        return hapiTest(
                uploadInitCode(contract),
                contractCreate(contract),
                atomicBatch(contractCall(contract, "create")
                                .via("callTxn")
                                .sending(DEPOSIT_AMOUNT)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                getTxnRecord("callTxn")
                        .hasPriority(
                                recordWith().contractCallResult(resultWith().logs(inOrder()))));
    }

    // This test disabled for modularization service
    // Adding refusingEthConversion() due to fee differences
    @HapiTest
    final Stream<DynamicTest> smartContractFailFirst() {
        final var civilian = "civilian";
        return hapiTest(
                uploadInitCode(SIMPLE_STORAGE_CONTRACT),
                cryptoCreate(civilian).balance(ONE_MILLION_HBARS).payingWith(GENESIS),
                withOpContext((spec, ignore) -> {
                    final var subop1 = balanceSnapshot("balanceBefore0", civilian);
                    final var subop2 = contractCreate(SIMPLE_STORAGE_CONTRACT)
                            .balance(0)
                            .payingWith(civilian)
                            .gas(1)
                            .hasPrecheck(INSUFFICIENT_GAS)
                            .via(FAIL_INSUFFICIENT_GAS);
                    // There should be no fee charged for this transaction as it fails in precheck
                    allRunFor(spec, subop1, subop2);
                    final var delta = 0;
                    final var subop3 =
                            getAccountBalance(civilian).hasTinyBars(changeFromSnapshot("balanceBefore0", -delta));
                    allRunFor(spec, subop3);
                }),
                withOpContext((spec, ignore) -> {
                    final var subop1 = balanceSnapshot("balanceBefore1", civilian);
                    final var subop2 = contractCreate(SIMPLE_STORAGE_CONTRACT)
                            .balance(100_000_000_000L)
                            .payingWith(civilian)
                            .gas(250_000L)
                            .via(FAIL_INVALID_INITIAL_BALANCE)
                            .hasKnownStatus(CONTRACT_REVERT_EXECUTED)
                            .refusingEthConversion();
                    final var subop3 = getTxnRecord(FAIL_INVALID_INITIAL_BALANCE);
                    allRunFor(spec, subop1, subop2, subop3);
                    final var delta = subop3.getResponseRecord().getTransactionFee();
                    final var subop4 =
                            getAccountBalance(civilian).hasTinyBars(changeFromSnapshot("balanceBefore1", -delta));
                    allRunFor(spec, subop4);
                }),
                withOpContext((spec, ignore) -> {
                    final var subop1 = balanceSnapshot("balanceBefore2", civilian);
                    final var subop2 = contractCreate(SIMPLE_STORAGE_CONTRACT)
                            .balance(0L)
                            .payingWith(civilian)
                            .gas(250_000L)
                            .hasKnownStatus(SUCCESS)
                            .via(SUCCESS_WITH_ZERO_INITIAL_BALANCE)
                            .refusingEthConversion();
                    final var subop3 = getTxnRecord(SUCCESS_WITH_ZERO_INITIAL_BALANCE);
                    allRunFor(spec, subop1, subop2, subop3);
                    final var delta = subop3.getResponseRecord().getTransactionFee();
                    final var subop4 =
                            getAccountBalance(civilian).hasTinyBars(changeFromSnapshot("balanceBefore2", -delta));
                    allRunFor(spec, subop4);
                }),
                withOpContext((spec, ignore) -> {
                    final var subop1 = balanceSnapshot("balanceBefore3", civilian);
                    final var subop2 = atomicBatch(
                                    contractCall(SIMPLE_STORAGE_CONTRACT, "set", BigInteger.valueOf(999_999L))
                                            .payingWith(civilian)
                                            .gas(300_000L)
                                            .hasKnownStatus(SUCCESS)
                                            // ContractCall and EthereumTransaction gas fees
                                            // differ
                                            .refusingEthConversion()
                                            .via("setValue")
                                            .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR);
                    final var subop3 = getTxnRecord("setValue");
                    allRunFor(spec, subop1, subop2, subop3);
                    final var delta = subop3.getResponseRecord().getTransactionFee();
                    final var subop4 =
                            getAccountBalance(civilian).hasTinyBars(changeFromSnapshot("balanceBefore3", -delta));
                    allRunFor(spec, subop4);
                }),
                withOpContext((spec, ignore) -> {
                    final var subop1 = balanceSnapshot("balanceBefore4", civilian);
                    final var subop2 = atomicBatch(
                                    contractCall(SIMPLE_STORAGE_CONTRACT, "set", BigInteger.valueOf(999_999L))
                                            .payingWith(civilian)
                                            .gas(0)
                                            .refusingEthConversion()
                                            .hasPrecheck(INSUFFICIENT_GAS)
                                            .via("setValueNoGas")
                                            .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR)
                            .hasPrecheck(INSUFFICIENT_GAS);
                    allRunFor(spec, subop1, subop2);
                    final var subop4 = getAccountBalance(civilian).hasTinyBars(changeFromSnapshot("balanceBefore4", 0));
                    allRunFor(spec, subop4);
                }),
                withOpContext((spec, ignore) -> {
                    final var subop1 = balanceSnapshot("balanceBefore5", civilian);
                    final var subop2 = atomicBatch(contractCall(SIMPLE_STORAGE_CONTRACT, "get")
                                    .payingWith(civilian)
                                    .gas(300_000L)
                                    .hasKnownStatus(SUCCESS)
                                    // ContractCall and EthereumTransaction gas fees
                                    // differ
                                    .refusingEthConversion()
                                    .via("getValue")
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR);
                    final var subop3 = getTxnRecord("getValue");
                    allRunFor(spec, subop1, subop2, subop3);
                    final var delta = subop3.getResponseRecord().getTransactionFee();

                    final var subop4 =
                            getAccountBalance(civilian).hasTinyBars(changeFromSnapshot("balanceBefore5", -delta));
                    allRunFor(spec, subop4);
                }),
                getTxnRecord(SUCCESS_WITH_ZERO_INITIAL_BALANCE),
                getTxnRecord(FAIL_INVALID_INITIAL_BALANCE));
    }

    @HapiTest
    final Stream<DynamicTest> payTestSelfDestructCall() {
        final var contract = "PayTestSelfDestruct";
        final AtomicReference<Address> tokenCreateContractAddress = new AtomicReference<>();

        return hapiTest(
                cryptoCreate(PAYER).balance(1_000_000_000_000L).logged(),
                cryptoCreate(RECEIVER).balance(1_000L),
                uploadInitCode(contract),
                contractCreate(contract),
                getContractInfo(contract)
                        .exposingEvmAddress(cb -> tokenCreateContractAddress.set(asHeadlongAddress(cb))),
                withOpContext((spec, opLog) -> {
                    final var subop1 = atomicBatch(contractCall(contract, DEPOSIT, BigInteger.valueOf(1_000L))
                                    .payingWith(PAYER)
                                    .gas(300_000L)
                                    .via(DEPOSIT)
                                    .sending(1_000L)
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR);

                    final var subop2 = atomicBatch(contractCall(contract, GET_BALANCE)
                                    .payingWith(PAYER)
                                    .gas(300_000L)
                                    .via(GET_BALANCE)
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR);

                    final var subop3 = atomicBatch(contractCall(contract, KILL_ME, tokenCreateContractAddress.get())
                                    .payingWith(PAYER)
                                    .gas(300_000L)
                                    .hasKnownStatus(OBTAINER_SAME_CONTRACT_ID)
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR)
                            .hasKnownStatus(INNER_TRANSACTION_FAILED);

                    final var subop4 = atomicBatch(contractCall(contract, KILL_ME, asHeadlongAddress(new byte[20]))
                                    .payingWith(PAYER)
                                    .gas(300_000L)
                                    .hasKnownStatus(INVALID_SOLIDITY_ADDRESS)
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR)
                            .hasKnownStatus(INNER_TRANSACTION_FAILED);

                    final var receiverAccountId = asId(RECEIVER, spec);
                    final var subop5 = atomicBatch(
                                    contractCall(contract, KILL_ME, asHeadlongAddress(asAddress(receiverAccountId)))
                                            .payingWith(PAYER)
                                            .gas(300_000L)
                                            .via("selfDestruct")
                                            .hasKnownStatus(SUCCESS)
                                            .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR);

                    allRunFor(spec, subop1, subop2, subop3, subop4, subop5);
                }),
                getTxnRecord(DEPOSIT),
                getTxnRecord(GET_BALANCE)
                        .hasPriority(recordWith()
                                .contractCallResult(resultWith()
                                        .resultViaFunctionName(GET_BALANCE, contract, isLiteralResult(new Object[] {
                                            BigInteger.valueOf(1_000L)
                                        })))),
                getAccountBalance(RECEIVER).hasTinyBars(2_000L));
    }

    @HapiTest
    final Stream<DynamicTest> contractTransferToSigReqAccountWithoutKeyFails() {
        return hapiTest(
                cryptoCreate(RECEIVABLE_SIG_REQ_ACCOUNT)
                        .balance(1_000_000_000_000L)
                        .receiverSigRequired(true),
                getAccountInfo(RECEIVABLE_SIG_REQ_ACCOUNT).savingSnapshot(RECEIVABLE_SIG_REQ_ACCOUNT_INFO),
                uploadInitCode(TRANSFERRING_CONTRACT),
                contractCreate(TRANSFERRING_CONTRACT).gas(1_000_000L).balance(5000L),
                withOpContext((spec, opLog) -> {
                    final var accountAddress = spec.registry()
                            .getAccountInfo(RECEIVABLE_SIG_REQ_ACCOUNT_INFO)
                            .getContractAccountID();
                    final var call = atomicBatch(contractCall(
                                            TRANSFERRING_CONTRACT,
                                            TRANSFER_TO_ADDRESS,
                                            asHeadlongAddress(accountAddress),
                                            BigInteger.ONE)
                                    .gas(300_000)
                                    .hasKnownStatus(INVALID_SIGNATURE)
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR)
                            .hasKnownStatus(INNER_TRANSACTION_FAILED);
                    allRunFor(spec, call);
                }));
    }

    @HapiTest
    final Stream<DynamicTest> minChargeIsTXGasUsedByContractCall() {
        return hapiTest(
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
                    Assertions.assertTrue(gasUsed > 0L);
                }));
    }

    @HapiTest
    final Stream<DynamicTest> hscsEvm006ContractHBarTransferToAccount() {
        return hapiTest(
                cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(RECEIVER).balance(10_000L),
                uploadInitCode(TRANSFERRING_CONTRACT),
                contractCreate(TRANSFERRING_CONTRACT).balance(10_000L).payingWith(ACCOUNT),
                getContractInfo(TRANSFERRING_CONTRACT).saveToRegistry(CONTRACT_FROM),
                getAccountInfo(ACCOUNT).savingSnapshot(ACCOUNT_INFO),
                getAccountInfo(RECEIVER).savingSnapshot(RECEIVER_INFO),
                withOpContext((spec, log) -> {
                    final var receiverAddr =
                            spec.registry().getAccountInfo(RECEIVER_INFO).getContractAccountID();
                    final var transferCall = atomicBatch(contractCall(
                                            TRANSFERRING_CONTRACT,
                                            TRANSFER_TO_ADDRESS,
                                            asHeadlongAddress(receiverAddr),
                                            BigInteger.valueOf(10))
                                    .payingWith(ACCOUNT)
                                    .logged()
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR);
                    allRunFor(spec, transferCall);
                }),
                getAccountBalance(RECEIVER).hasTinyBars(10_000L + 10));
    }

    @HapiTest
    final Stream<DynamicTest> hscsEvm005TransfersWithSubLevelCallsBetweenContracts() {
        final var topLevelContract = "TopLevelTransferring";
        final var subLevelContract = "SubLevelTransferring";
        final var INITIAL_CONTRACT_BALANCE = 100;

        return hapiTest(
                cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                uploadInitCode(topLevelContract, subLevelContract),
                contractCreate(topLevelContract)
                        .payingWith(ACCOUNT)
                        .balance(INITIAL_CONTRACT_BALANCE)
                        // Adding refusingEthConversion() due to fee differences
                        .refusingEthConversion(),
                contractCreate(subLevelContract)
                        .payingWith(ACCOUNT)
                        .balance(INITIAL_CONTRACT_BALANCE)
                        // Adding refusingEthConversion() due to fee differences
                        .refusingEthConversion(),
                atomicBatch(contractCall(topLevelContract)
                                .sending(10)
                                .payingWith(ACCOUNT)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                getAccountBalance(topLevelContract).hasTinyBars(INITIAL_CONTRACT_BALANCE + 10L),
                atomicBatch(contractCall(topLevelContract, "topLevelTransferCall")
                                .sending(10)
                                .payingWith(ACCOUNT)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                getAccountBalance(topLevelContract).hasTinyBars(INITIAL_CONTRACT_BALANCE + 20L),
                atomicBatch(contractCall(topLevelContract, "topLevelNonPayableCall")
                                .sending(10)
                                .payingWith(ACCOUNT)
                                .hasKnownStatus(ResponseCodeEnum.CONTRACT_REVERT_EXECUTED)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                getAccountBalance(topLevelContract).hasTinyBars(INITIAL_CONTRACT_BALANCE + 20L),
                getContractInfo(topLevelContract).saveToRegistry("tcinfo"),
                getContractInfo(subLevelContract).saveToRegistry(SCINFO),

                /* sub-level non-payable contract call */
                assertionsHold((spec, log) -> {
                    final var subLevelSolidityAddr =
                            spec.registry().getContractInfo(SCINFO).getContractAccountID();
                    final var cc = atomicBatch(contractCall(
                                            subLevelContract,
                                            "subLevelNonPayableCall",
                                            asHeadlongAddress(subLevelSolidityAddr),
                                            BigInteger.valueOf(20L))
                                    .hasKnownStatus(ResponseCodeEnum.CONTRACT_REVERT_EXECUTED)
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR)
                            .hasKnownStatus(INNER_TRANSACTION_FAILED);
                    allRunFor(spec, cc);
                }),
                getAccountBalance(topLevelContract).hasTinyBars(20L + INITIAL_CONTRACT_BALANCE),
                getAccountBalance(subLevelContract).hasTinyBars(INITIAL_CONTRACT_BALANCE),

                /* sub-level payable contract call */
                assertionsHold((spec, log) -> {
                    final var subLevelSolidityAddr =
                            spec.registry().getContractInfo(SCINFO).getContractAccountID();
                    final var cc = atomicBatch(contractCall(
                                            topLevelContract,
                                            "subLevelPayableCall",
                                            asHeadlongAddress(subLevelSolidityAddr),
                                            BigInteger.valueOf(20L))
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR);
                    allRunFor(spec, cc);
                }),
                getAccountBalance(topLevelContract).hasTinyBars(INITIAL_CONTRACT_BALANCE),
                getAccountBalance(subLevelContract).hasTinyBars(20L + INITIAL_CONTRACT_BALANCE));
    }

    @HapiTest
    final Stream<DynamicTest> hscsEvm005TransferOfHBarsWorksBetweenContracts() {
        final var to = "To";

        return hapiTest(
                cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                uploadInitCode(TRANSFERRING_CONTRACT),
                contractCreate(TRANSFERRING_CONTRACT).balance(10_000L).payingWith(ACCOUNT),
                // refuse eth conversion because we can't call contract by contract num
                // when it has EVM address alias (isNotPriority check fails)
                contractCustomCreate(TRANSFERRING_CONTRACT, to)
                        .balance(10_000L)
                        .payingWith(ACCOUNT)
                        .refusingEthConversion(),
                getContractInfo(TRANSFERRING_CONTRACT).saveToRegistry(CONTRACT_FROM),
                getContractInfo(TRANSFERRING_CONTRACT + to).saveToRegistry("contract_to"),
                getAccountInfo(ACCOUNT).savingSnapshot(ACCOUNT_INFO),
                withOpContext((spec, log) -> {
                    var cto = asSolidityAddress(
                            spec.registry().getContractInfo("contract_to").getContractID());
                    var transferCall = atomicBatch(contractCall(
                                            TRANSFERRING_CONTRACT,
                                            TRANSFER_TO_ADDRESS,
                                            asHeadlongAddress(cto),
                                            BigInteger.valueOf(10))
                                    .payingWith(ACCOUNT)
                                    .logged()
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR);
                    allRunFor(spec, transferCall);
                }),
                getAccountBalance(TRANSFERRING_CONTRACT).hasTinyBars(10_000 - 10L),
                getAccountBalance(TRANSFERRING_CONTRACT + to).hasTinyBars(10_000 + 10L));
    }

    @HapiTest
    final Stream<DynamicTest> hscsEvm010ReceiverMustSignContractTx() {
        final var ACC = "acc";
        final var RECEIVER_KEY = "receiverKey";
        return hapiTest(
                recordStreamMustIncludeNoFailuresFrom(sidecarIdValidator()),
                newKeyNamed(RECEIVER_KEY),
                cryptoCreate(ACC)
                        .balance(5 * ONE_HUNDRED_HBARS)
                        .receiverSigRequired(true)
                        .key(RECEIVER_KEY),
                getAccountInfo(ACC).savingSnapshot(ACC_INFO),
                uploadInitCode(TRANSFERRING_CONTRACT),
                contractCreate(TRANSFERRING_CONTRACT).payingWith(ACC).balance(ONE_HUNDRED_HBARS),
                withOpContext((spec, log) -> {
                    final var acc = spec.registry().getAccountInfo(ACC_INFO).getContractAccountID();
                    final var withoutReceiverSignature = atomicBatch(contractCall(
                                            TRANSFERRING_CONTRACT,
                                            TRANSFER_TO_ADDRESS,
                                            asHeadlongAddress(acc),
                                            BigInteger.valueOf(ONE_HUNDRED_HBARS / 2))
                                    .hasKnownStatus(INVALID_SIGNATURE)
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR)
                            .hasKnownStatus(INNER_TRANSACTION_FAILED);
                    allRunFor(spec, withoutReceiverSignature);

                    final var withSignature = atomicBatch(contractCall(
                                            TRANSFERRING_CONTRACT,
                                            TRANSFER_TO_ADDRESS,
                                            asHeadlongAddress(acc),
                                            BigInteger.valueOf(ONE_HUNDRED_HBARS / 2))
                                    .payingWith(ACC)
                                    .signedBy(RECEIVER_KEY)
                                    .hasKnownStatus(SUCCESS)
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR);
                    allRunFor(spec, withSignature);
                }));
    }

    // Adding refusingEthConversion() due to fee differences
    @HapiTest
    final Stream<DynamicTest> hscsEvm010MultiSignatureAccounts() {
        final var ACC = "acc";
        final var PAYER_KEY = "pkey";
        final var OTHER_KEY = "okey";
        final var KEY_LIST = "klist";
        return hapiTest(
                newKeyNamed(PAYER_KEY),
                newKeyNamed(OTHER_KEY),
                newKeyListNamed(KEY_LIST, List.of(PAYER_KEY, OTHER_KEY)),
                cryptoCreate(ACC).balance(ONE_HUNDRED_HBARS).key(KEY_LIST).keyType(THRESHOLD),
                uploadInitCode(TRANSFERRING_CONTRACT),
                getAccountInfo(ACC).savingSnapshot(ACC_INFO),
                contractCreate(TRANSFERRING_CONTRACT)
                        .payingWith(ACC)
                        .signedBy(PAYER_KEY)
                        .adminKey(KEY_LIST)
                        .hasPrecheck(INVALID_SIGNATURE)
                        .refusingEthConversion(),
                contractCreate(TRANSFERRING_CONTRACT)
                        .payingWith(ACC)
                        .signedBy(PAYER_KEY, OTHER_KEY)
                        .balance(10)
                        .adminKey(KEY_LIST)
                        .refusingEthConversion(),
                withOpContext((spec, log) -> {
                    final var acc = spec.registry().getAccountInfo(ACC_INFO).getContractAccountID();
                    final var assertionWithOnlyOneKey = atomicBatch(contractCall(
                                            TRANSFERRING_CONTRACT,
                                            TRANSFER_TO_ADDRESS,
                                            asHeadlongAddress(acc),
                                            BigInteger.valueOf(10L))
                                    .payingWith(ACC)
                                    .signedBy(PAYER_KEY)
                                    .hasPrecheck(INVALID_SIGNATURE)
                                    .refusingEthConversion()
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR)
                            .hasPrecheck(INVALID_SIGNATURE);
                    allRunFor(spec, assertionWithOnlyOneKey);

                    final var assertionWithBothKeys = atomicBatch(contractCall(
                                            TRANSFERRING_CONTRACT,
                                            TRANSFER_TO_ADDRESS,
                                            asHeadlongAddress(acc),
                                            BigInteger.valueOf(10L))
                                    .payingWith(ACC)
                                    .signedBy(PAYER_KEY, OTHER_KEY)
                                    .hasKnownStatus(SUCCESS)
                                    .refusingEthConversion()
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR);
                    allRunFor(spec, assertionWithBothKeys);
                }));
    }

    @HapiTest
    final Stream<DynamicTest> sendHbarsToAddressesMultipleTimes() {
        return hapiTest(
                cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(RECEIVER).balance(10_000L),
                uploadInitCode(TRANSFERRING_CONTRACT),
                contractCreate(TRANSFERRING_CONTRACT).balance(10_000L).payingWith(ACCOUNT),
                getAccountInfo(RECEIVER).savingSnapshot(RECEIVER_INFO),
                withOpContext((spec, log) -> {
                    var receiverAddr =
                            spec.registry().getAccountInfo(RECEIVER_INFO).getContractAccountID();
                    var transferCall = atomicBatch(contractCall(
                                            TRANSFERRING_CONTRACT,
                                            "transferToAddressMultipleTimes",
                                            asHeadlongAddress(receiverAddr),
                                            BigInteger.valueOf(64))
                                    .payingWith(ACCOUNT)
                                    .via("lalala")
                                    .logged()
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR);
                    var getRec = getTxnRecord("lalala").andAllChildRecords().logged();
                    allRunFor(spec, transferCall, getRec);
                }),
                getAccountBalance(RECEIVER).hasTinyBars(10_000L + 127L),
                sourcing(() -> getContractInfo(TRANSFERRING_CONTRACT)
                        .has(contractWith().balance(10_000L - 127L))));
    }

    @HapiTest
    final Stream<DynamicTest> sendHbarsToDifferentAddresses() {
        return hapiTest(
                cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(RECEIVER_1).balance(10_000L),
                cryptoCreate(RECEIVER_2).balance(10_000L),
                cryptoCreate(RECEIVER_3).balance(10_000L),
                uploadInitCode(TRANSFERRING_CONTRACT),
                contractCreate(TRANSFERRING_CONTRACT).balance(10_000L).payingWith(ACCOUNT),
                getAccountInfo(RECEIVER_1).savingSnapshot(RECEIVER_1_INFO),
                getAccountInfo(RECEIVER_2).savingSnapshot(RECEIVER_2_INFO),
                getAccountInfo(RECEIVER_3).savingSnapshot(RECEIVER_3_INFO),
                withOpContext((spec, log) -> {
                    var receiver1Addr =
                            spec.registry().getAccountInfo(RECEIVER_1_INFO).getContractAccountID();
                    var receiver2Addr =
                            spec.registry().getAccountInfo(RECEIVER_2_INFO).getContractAccountID();
                    var receiver3Addr =
                            spec.registry().getAccountInfo(RECEIVER_3_INFO).getContractAccountID();

                    var transferCall = atomicBatch(contractCall(
                                            TRANSFERRING_CONTRACT,
                                            "transferToDifferentAddresses",
                                            asHeadlongAddress(receiver1Addr),
                                            asHeadlongAddress(receiver2Addr),
                                            asHeadlongAddress(receiver3Addr),
                                            BigInteger.valueOf(20))
                                    .payingWith(ACCOUNT)
                                    .logged()
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR);
                    allRunFor(spec, transferCall);
                }),
                getAccountBalance(RECEIVER_1).hasTinyBars(10_000L + 20L),
                getAccountBalance(RECEIVER_2).hasTinyBars(10_000L + 10L),
                getAccountBalance(RECEIVER_3).hasTinyBars(10_000L + 5L),
                sourcing(() -> getContractInfo(TRANSFERRING_CONTRACT)
                        .has(contractWith().balance(10_000L - 35L))));
    }

    @HapiTest
    final Stream<DynamicTest> sendHbarsFromDifferentAddressessToAddress() {
        return hapiTest(
                cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(RECEIVER).balance(10_000L),
                uploadInitCode(NESTED_TRANSFERRING_CONTRACT, NESTED_TRANSFER_CONTRACT),
                contractCustomCreate(NESTED_TRANSFER_CONTRACT, "1")
                        .balance(10_000L)
                        .payingWith(ACCOUNT),
                contractCustomCreate(NESTED_TRANSFER_CONTRACT, "2")
                        .balance(10_000L)
                        .payingWith(ACCOUNT),
                getAccountInfo(RECEIVER).savingSnapshot(RECEIVER_INFO),
                withOpContext((spec, log) -> {
                    var receiverAddr =
                            spec.registry().getAccountInfo(RECEIVER_INFO).getContractAccountID();

                    allRunFor(
                            spec,
                            contractCreate(
                                            NESTED_TRANSFERRING_CONTRACT,
                                            asHeadlongAddress(
                                                    getNestedContractAddress(NESTED_TRANSFER_CONTRACT + "1", spec)),
                                            asHeadlongAddress(
                                                    getNestedContractAddress(NESTED_TRANSFER_CONTRACT + "2", spec)))
                                    .balance(10_000L)
                                    .payingWith(ACCOUNT),
                            atomicBatch(contractCall(
                                                    NESTED_TRANSFERRING_CONTRACT,
                                                    "transferFromDifferentAddressesToAddress",
                                                    asHeadlongAddress(receiverAddr),
                                                    BigInteger.valueOf(40L))
                                            .payingWith(ACCOUNT)
                                            .logged()
                                            .batchKey(BATCH_OPERATOR))
                                    .payingWith(BATCH_OPERATOR));
                }),
                getAccountBalance(RECEIVER).hasTinyBars(10_000L + 80L),
                sourcing(() -> getContractInfo(NESTED_TRANSFER_CONTRACT + "1")
                        .has(contractWith().balance(10_000L - 20L))),
                sourcing(() -> getContractInfo(NESTED_TRANSFER_CONTRACT + "2")
                        .has(contractWith().balance(10_000L - 20L))));
    }

    @HapiTest
    final Stream<DynamicTest> sendHbarsToOuterContractFromDifferentAddresses() {
        return hapiTest(
                cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                uploadInitCode(NESTED_TRANSFERRING_CONTRACT, NESTED_TRANSFER_CONTRACT),
                contractCustomCreate(NESTED_TRANSFER_CONTRACT, "1")
                        .balance(10_000L)
                        .payingWith(ACCOUNT),
                contractCustomCreate(NESTED_TRANSFER_CONTRACT, "2")
                        .balance(10_000L)
                        .payingWith(ACCOUNT),
                withOpContext((spec, log) -> allRunFor(
                        spec,
                        contractCreate(
                                        NESTED_TRANSFERRING_CONTRACT,
                                        asHeadlongAddress(
                                                getNestedContractAddress(NESTED_TRANSFER_CONTRACT + "1", spec)),
                                        asHeadlongAddress(
                                                getNestedContractAddress(NESTED_TRANSFER_CONTRACT + "2", spec)))
                                .balance(10_000L)
                                .payingWith(ACCOUNT),
                        atomicBatch(contractCall(
                                                NESTED_TRANSFERRING_CONTRACT,
                                                "transferToContractFromDifferentAddresses",
                                                BigInteger.valueOf(50L))
                                        .payingWith(ACCOUNT)
                                        .batchKey(BATCH_OPERATOR))
                                .payingWith(BATCH_OPERATOR))),
                sourcing(() -> getContractInfo(NESTED_TRANSFERRING_CONTRACT)
                        .has(contractWith().balance(10_000L + 100L))),
                sourcing(() -> getContractInfo(NESTED_TRANSFER_CONTRACT + "1")
                        .has(contractWith().balance(10_000L - 50L))),
                sourcing(() -> getContractInfo(NESTED_TRANSFER_CONTRACT + "2")
                        .has(contractWith().balance(10_000L - 50L))));
    }

    @HapiTest
    final Stream<DynamicTest> sendHbarsToCallerFromDifferentAddresses() {
        return hapiTest(
                withOpContext((spec, log) -> {
                    final var keyCreation = newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE);
                    if (!spec.isUsingEthCalls()) {
                        final var sender = "sender";
                        final var createSender = cryptoCreate(sender);
                        allRunFor(spec, createSender);
                        spec.registry()
                                .saveKey(
                                        DEFAULT_CONTRACT_RECEIVER,
                                        spec.registry().getKey(sender));
                        spec.registry()
                                .saveAccountId(
                                        DEFAULT_CONTRACT_RECEIVER,
                                        spec.registry().getAccountID(sender));
                    }
                    final var transfer1 = cryptoTransfer(
                                    tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_HUNDRED_HBARS))
                            .via("autoAccount");

                    final var nestedTransferringUpload =
                            uploadInitCode(NESTED_TRANSFERRING_CONTRACT, NESTED_TRANSFER_CONTRACT);
                    final var createFirstNestedContract = contractCustomCreate(NESTED_TRANSFER_CONTRACT, "1")
                            .balance(10_000L)

                            // Adding refusingEthConversion() due to fee differences
                            .refusingEthConversion();
                    final var createSecondNestedContract = contractCustomCreate(NESTED_TRANSFER_CONTRACT, "2")
                            .balance(10_000L)

                            // Adding refusingEthConversion() due to fee differences
                            .refusingEthConversion();
                    final var transfer2 = cryptoTransfer(
                            TokenMovement.movingHbar(10_000_000L).between(GENESIS, DEFAULT_CONTRACT_RECEIVER));
                    final var saveSnapshot = getAccountInfo(DEFAULT_CONTRACT_RECEIVER)
                            .savingSnapshot(ACCOUNT_INFO)
                            .payingWith(GENESIS);
                    allRunFor(
                            spec,
                            keyCreation,
                            transfer1,
                            nestedTransferringUpload,
                            createFirstNestedContract,
                            createSecondNestedContract,
                            transfer2,
                            saveSnapshot);
                }),
                withOpContext((spec, log) -> allRunFor(
                        spec,
                        contractCreate(
                                        NESTED_TRANSFERRING_CONTRACT,
                                        asHeadlongAddress(
                                                getNestedContractAddress(NESTED_TRANSFER_CONTRACT + "1", spec)),
                                        asHeadlongAddress(
                                                getNestedContractAddress(NESTED_TRANSFER_CONTRACT + "2", spec)))
                                .balance(10_000L)
                                .payingWith(GENESIS)

                                // Adding refusingEthConversion() due to fee differences
                                .refusingEthConversion(),
                        atomicBatch(contractCall(
                                                NESTED_TRANSFERRING_CONTRACT,
                                                "transferToCallerFromDifferentAddresses",
                                                BigInteger.valueOf(100L))
                                        .payingWith(DEFAULT_CONTRACT_RECEIVER)
                                        .signingWith(SECP_256K1_RECEIVER_SOURCE_KEY)
                                        .via(TRANSFER_TXN)
                                        .logged()
                                        .batchKey(BATCH_OPERATOR))
                                .payingWith(BATCH_OPERATOR),
                        getTxnRecord(TRANSFER_TXN)
                                .saveTxnRecordToRegistry("txn")
                                .payingWith(GENESIS),
                        getAccountInfo(DEFAULT_CONTRACT_RECEIVER)
                                .savingSnapshot(ACCOUNT_INFO_AFTER_CALL)
                                .payingWith(GENESIS))),
                assertionsHold((spec, opLog) -> {
                    final var callRecord = spec.registry().getTransactionRecord("txn");
                    final var fee = spec.registry().getTransactionRecord("txn").getTransactionFee();
                    final var accountBalanceBeforeCall =
                            spec.registry().getAccountInfo(ACCOUNT_INFO).getBalance();
                    final var accountBalanceAfterCall = spec.registry()
                            .getAccountInfo(ACCOUNT_INFO_AFTER_CALL)
                            .getBalance();

                    assertEquals(accountBalanceAfterCall, accountBalanceBeforeCall - fee + 200L);
                }),
                sourcing(() -> getContractInfo(NESTED_TRANSFERRING_CONTRACT)
                        .has(contractWith().balance(10_000L - 200L))),
                sourcing(() -> getContractInfo(NESTED_TRANSFER_CONTRACT + "1")
                        .has(contractWith().balance(10_000L))),
                sourcing(() -> getContractInfo(NESTED_TRANSFER_CONTRACT + "2")
                        .has(contractWith().balance(10_000L))));
    }

    @HapiTest
    final Stream<DynamicTest> sendHbarsFromAndToDifferentAddressess() {
        return hapiTest(
                cryptoCreate(ACCOUNT).balance(200 * ONE_HUNDRED_HBARS),
                cryptoCreate(RECEIVER_1).balance(10_000L),
                cryptoCreate(RECEIVER_2).balance(10_000L),
                cryptoCreate(RECEIVER_3).balance(10_000L),
                uploadInitCode(NESTED_TRANSFERRING_CONTRACT, NESTED_TRANSFER_CONTRACT),
                contractCustomCreate(NESTED_TRANSFER_CONTRACT, "1")
                        .balance(10_000L)
                        .payingWith(ACCOUNT),
                contractCustomCreate(NESTED_TRANSFER_CONTRACT, "2")
                        .balance(10_000L)
                        .payingWith(ACCOUNT),
                getAccountInfo(RECEIVER_1).savingSnapshot(RECEIVER_1_INFO),
                getAccountInfo(RECEIVER_2).savingSnapshot(RECEIVER_2_INFO),
                getAccountInfo(RECEIVER_3).savingSnapshot(RECEIVER_3_INFO),
                withOpContext((spec, log) -> {
                    var receiver1Addr =
                            spec.registry().getAccountInfo(RECEIVER_1_INFO).getContractAccountID();
                    var receiver2Addr =
                            spec.registry().getAccountInfo(RECEIVER_2_INFO).getContractAccountID();
                    var receiver3Addr =
                            spec.registry().getAccountInfo(RECEIVER_3_INFO).getContractAccountID();

                    allRunFor(
                            spec,
                            contractCreate(
                                            NESTED_TRANSFERRING_CONTRACT,
                                            asHeadlongAddress(
                                                    getNestedContractAddress(NESTED_TRANSFER_CONTRACT + "1", spec)),
                                            asHeadlongAddress(
                                                    getNestedContractAddress(NESTED_TRANSFER_CONTRACT + "2", spec)))
                                    .balance(10_000L)
                                    .payingWith(ACCOUNT),
                            atomicBatch(contractCall(
                                                    NESTED_TRANSFERRING_CONTRACT,
                                                    "transferFromAndToDifferentAddresses",
                                                    asHeadlongAddress(receiver1Addr),
                                                    asHeadlongAddress(receiver2Addr),
                                                    asHeadlongAddress(receiver3Addr),
                                                    BigInteger.valueOf(40))
                                            .payingWith(ACCOUNT)
                                            .gas(1_000_000L)
                                            .logged()
                                            .batchKey(BATCH_OPERATOR))
                                    .payingWith(BATCH_OPERATOR));
                }),
                getAccountBalance(RECEIVER_1).hasTinyBars(10_000 + 80L),
                getAccountBalance(RECEIVER_2).hasTinyBars(10_000 + 80L),
                getAccountBalance(RECEIVER_3).hasTinyBars(10_000 + 80L),
                sourcing(() -> getContractInfo(NESTED_TRANSFER_CONTRACT + "1")
                        .has(contractWith().balance(10_000 - 60L))),
                sourcing(() -> getContractInfo(NESTED_TRANSFER_CONTRACT + "2")
                        .has(contractWith().balance(10_000 - 60L))));
    }

    // Adding refusingEthConversion() due to fee differences
    @HapiTest
    final Stream<DynamicTest> transferNegativeAmountOfHbarsFails() {
        return hapiTest(
                cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(RECEIVER).balance(10_000L),
                uploadInitCode(TRANSFERRING_CONTRACT),
                contractCreate(TRANSFERRING_CONTRACT)
                        .balance(10_000L)
                        .payingWith(ACCOUNT)
                        .refusingEthConversion(),
                getAccountInfo(RECEIVER).savingSnapshot(RECEIVER_INFO),
                withOpContext((spec, log) -> {
                    var receiverAddr =
                            spec.registry().getAccountInfo(RECEIVER_INFO).getContractAccountID();
                    var transferCall = atomicBatch(contractCall(
                                            TRANSFERRING_CONTRACT,
                                            "transferToAddressNegativeAmount",
                                            asHeadlongAddress(receiverAddr),
                                            BigInteger.valueOf(10))
                                    .payingWith(ACCOUNT)
                                    .hasKnownStatus(CONTRACT_REVERT_EXECUTED)
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR)
                            .hasKnownStatus(INNER_TRANSACTION_FAILED);
                    var transferCallZeroHbars = atomicBatch(contractCall(
                                            TRANSFERRING_CONTRACT,
                                            "transferToAddressNegativeAmount",
                                            asHeadlongAddress(receiverAddr),
                                            BigInteger.ZERO)
                                    .payingWith(ACCOUNT)
                                    .hasKnownStatus(SUCCESS)
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR);

                    allRunFor(spec, transferCall, transferCallZeroHbars);
                }),
                getAccountBalance(RECEIVER).hasTinyBars(10_000L),
                sourcing(() -> getContractInfo(TRANSFERRING_CONTRACT)
                        .has(contractWith().balance(10_000L))));
    }

    @HapiTest
    final Stream<DynamicTest> transferZeroHbars() {
        return hapiTest(
                cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(RECEIVER).balance(10_000L),
                uploadInitCode(TRANSFERRING_CONTRACT),
                // Adding refusingEthConversion() due to fee differences
                contractCreate(TRANSFERRING_CONTRACT).balance(10_000L).refusingEthConversion(),
                getAccountInfo(RECEIVER).savingSnapshot(RECEIVER_INFO),
                withOpContext((spec, log) -> {
                    var receiverAddr =
                            spec.registry().getAccountInfo(RECEIVER_INFO).getContractAccountID();

                    var transferCall = atomicBatch(contractCall(
                                            TRANSFERRING_CONTRACT,
                                            TRANSFER_TO_ADDRESS,
                                            asHeadlongAddress(receiverAddr),
                                            BigInteger.ZERO)
                                    .payingWith(ACCOUNT)
                                    .via(TRANSFER_TXN)
                                    .logged()
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR);

                    var saveContractInfo =
                            getContractInfo(TRANSFERRING_CONTRACT).saveToRegistry(CONTRACT_FROM);

                    allRunFor(spec, transferCall, saveContractInfo);
                }),
                assertionsHold((spec, opLog) -> {
                    final var contractBalanceAfterCall =
                            spec.registry().getContractInfo(CONTRACT_FROM).getBalance();

                    assertEquals(contractBalanceAfterCall, 10_000L);
                }),
                getAccountBalance(RECEIVER).hasTinyBars(10_000L));
    }

    @HapiTest
    final Stream<DynamicTest> consTimeManagementWorksWithRevertedInternalCreations() {
        final var contract = "ConsTimeRepro";
        final var failingCall = "FailingCall";
        final AtomicReference<Timestamp> parentConsTime = new AtomicReference<>();
        return hapiTest(
                uploadInitCode(contract),
                contractCreate(contract),
                atomicBatch(contractCall(
                                        contract,
                                        "createChildThenFailToAssociate",
                                        asHeadlongAddress(new byte[20]),
                                        asHeadlongAddress(new byte[20]))
                                .via(failingCall)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                getTxnRecord(failingCall)
                        .exposingTo(failureRecord -> parentConsTime.set(failureRecord.getConsensusTimestamp())),
                sourcing(() -> childRecordsCheck(
                        failingCall,
                        CONTRACT_REVERT_EXECUTED,
                        recordWith().status(INSUFFICIENT_GAS).consensusTimeImpliedByOffset(parentConsTime.get(), 1))));
    }

    @HapiTest
    final Stream<DynamicTest> callStaticCallToLargeAddress() {
        final var txn = "txn";
        final var contract = "CallInConstructor";
        return hapiTest(
                uploadInitCode(contract),
                contractCreate(contract).via(txn).hasKnownStatus(SUCCESS),
                atomicBatch(contractCall(contract, "callSomebody").via(txn).batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                getTxnRecord(txn).logged(),
                withOpContext((spec, opLog) -> {
                    final var op = getTxnRecord(txn);
                    allRunFor(spec, op);
                    final var record = op.getResponseRecord();
                    final var callResult = record.getContractCallResult();
                    final var callContractID = callResult.getContractID();
                    assertTrue(
                            callContractID.getContractNum() < 10000,
                            "Expected contract num < 10000 but got " + callContractID.getContractNum());
                }));
    }

    @HapiTest
    final Stream<DynamicTest> htsCallWithInsufficientGasHasNoStateChanges() {
        final var contract = "LowLevelCall";
        final var htsSystemContractAddress = asHeadlongAddress("0x0167");
        final var transferToken = new Function("transferToken(address,address,address,int64)", "(int64)");
        final AtomicReference<Address> treasuryAddress = new AtomicReference<>();
        final AtomicReference<Address> receiverAddress = new AtomicReference<>();
        final AtomicReference<Address> tokenAddress = new AtomicReference<>();
        final var initialSupply = 100L;
        return hapiTest(
                cryptoCreate(TOKEN_TREASURY).exposingEvmAddressTo(treasuryAddress::set),
                cryptoCreate(CIVILIAN_PAYER)
                        .exposingEvmAddressTo(receiverAddress::set)
                        .maxAutomaticTokenAssociations(1),
                tokenCreate(TOKEN)
                        .treasury(TOKEN_TREASURY)
                        .initialSupply(initialSupply)
                        .exposingAddressTo(tokenAddress::set),
                uploadInitCode(contract),
                contractCreate(contract),
                cryptoApproveAllowance()
                        .addTokenAllowance(TOKEN_TREASURY, TOKEN, contract, 100)
                        .signedBy(DEFAULT_PAYER, TOKEN_TREASURY),

                // Call transferToken() with insufficient gas
                sourcing(() -> atomicBatch(contractCall(
                                        contract,
                                        "callRequestedAndIgnoreFailure",
                                        htsSystemContractAddress,
                                        transferToken
                                                .encodeCallWithArgs(
                                                        tokenAddress.get(),
                                                        treasuryAddress.get(),
                                                        receiverAddress.get(),
                                                        13L)
                                                .array(),
                                        BigInteger.valueOf(13_000L))
                                .via("callTxn")
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)),
                childRecordsCheck("callTxn", SUCCESS, recordWith().status(INSUFFICIENT_GAS)),
                // Verify no token balances changed
                getAccountDetails(TOKEN_TREASURY)
                        .hasToken(relationshipWith(TOKEN).balance(initialSupply)),
                getAccountDetails(CIVILIAN_PAYER).hasNoTokenRelationship(TOKEN));
    }

    @HapiTest
    final Stream<DynamicTest> callToNonExtantLongZeroAddressUsesTargetedAddress() {
        final var contract = "LowLevelCall";
        final var nonExtantMirrorAddress = asHeadlongAddress("0xE8D4A50FFF");
        return hapiTest(
                recordStreamMustIncludeNoFailuresFrom(sidecarIdValidator()),
                uploadInitCode(contract),
                contractCreate(contract),
                atomicBatch(contractCall(
                                        contract,
                                        "callRequested",
                                        nonExtantMirrorAddress,
                                        new byte[0],
                                        BigInteger.valueOf(88_888L))
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR));
    }

    @HapiTest
    final Stream<DynamicTest> callToNonExtantEvmAddressUsesTargetedAddress() {
        final var contract = "LowLevelCall";
        final var nonExtantEvmAddress = asHeadlongAddress(TxnUtils.randomUtf8Bytes(20));
        return hapiTest(
                recordStreamMustIncludeNoFailuresFrom(sidecarIdValidator()),
                uploadInitCode(contract),
                contractCreate(contract).gas(400_000L),
                atomicBatch(contractCall(
                                        contract,
                                        "callRequested",
                                        nonExtantEvmAddress,
                                        new byte[0],
                                        BigInteger.valueOf(88_888L))
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR));
    }

    @HapiTest
    final Stream<DynamicTest> failsWithLessThanIntrinsicGas() {
        final String randomContract = "0.0.1051";
        final String functionName = "name";
        final String contractName = "ERC721ABI";
        return hapiTest(
                cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                withOpContext((spec, opLog) -> spec.registry().saveContractId(CONTRACT, asContract(randomContract))),
                withOpContext((spec, ctxLog) -> allRunFor(
                        spec,
                        atomicBatch(contractCallWithFunctionAbi(
                                                CONTRACT, getABIFor(FUNCTION, functionName, contractName))
                                        .gas(INTRINSIC_GAS_FOR_0_ARG_METHOD - 1)
                                        .signingWith(ACCOUNT)
                                        .hasPrecheck(INSUFFICIENT_GAS)
                                        .batchKey(BATCH_OPERATOR))
                                .payingWith(BATCH_OPERATOR)
                                .hasPrecheck(INSUFFICIENT_GAS))));
    }

    @HapiTest
    final Stream<DynamicTest> contractCreateFollowedByContractCallNoncesExternalization() {
        final var contract = "NoncesExternalization";
        final var payer = "payer";

        /* SMART CONTRACT FUNCTION NAMES */
        final var deployParentContractFn = "deployParentContract";
        final var deployChildFromParentContractFn = "deployChildFromParentContract";
        final var deployChildAndRevertFromParentContractFn = "deployChildAndRevertFromParentContract";

        /* VIA TRANSACTION NAMES */
        final var contractCreateTx = "contractCreateTx";
        final var deployContractTx = "deployContractTx";
        final var committedInnerCreationTx = "committedInnerCreationTx";
        final var revertedInnerCreationTx = "revertedInnerCreationTx";

        return hapiTest(
                cryptoCreate(payer).balance(10 * ONE_HUNDRED_HBARS),
                uploadInitCode(contract),
                contractCreate(contract).via(contractCreateTx).gas(1_000_000L),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        atomicBatch(contractCall(contract, deployParentContractFn)
                                        .payingWith(payer)
                                        .via(deployContractTx)
                                        .gas(GAS_TO_OFFER)
                                        .hasKnownStatus(SUCCESS)
                                        .batchKey(BATCH_OPERATOR))
                                .payingWith(BATCH_OPERATOR))),
                withOpContext((spec, opLog) -> {
                    /** 1. Retrieves sorted list of all contracts deployed in the constructor (parent contracts) */
                    final var opCreateTxRecord = getTxnRecord(contractCreateTx);
                    allRunFor(spec, opCreateTxRecord);

                    final var parentContractsList = opCreateTxRecord
                            .getResponse()
                            .getTransactionGetRecord()
                            .getTransactionRecord()
                            .getContractCreateResult()
                            .getContractNoncesList()
                            .stream()
                            .filter(contractNonceInfo -> !contractNonceInfo
                                    .getContractId()
                                    .equals(spec.registry().getContractId(contract)))
                            .toList();

                    /** 2. Asserts main contract (NoncesExternalization) nonce is 5 */
                    final var opAssertMain = getTxnRecord(deployContractTx)
                            .logged()
                            .hasPriority(recordWith()
                                    .contractCallResult(resultWith()
                                            .contractWithNonce(spec.registry().getContractId(contract), 5L)));
                    allRunFor(spec, opAssertMain);

                    /**
                     * 3. Deploys child from the first parent contract deployed in the constructor (index 0).
                     * Asserts parent's nonce is 2.
                     */
                    final var deployChild = atomicBatch(
                                    contractCall(contract, deployChildFromParentContractFn, BigInteger.ZERO)
                                            .gas(GAS_TO_OFFER)
                                            .via(committedInnerCreationTx)
                                            .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR);
                    HapiGetTxnRecord deployChildTxnRecord = getTxnRecord(committedInnerCreationTx);
                    allRunFor(spec, deployChild, deployChildTxnRecord);

                    /* Retrieves contractId of the first deployed contract in the constructor - index 0 */
                    final var firstParentContractId = parentContractsList.get(0).getContractId();
                    spec.registry().saveContractId("firstParentContractId", firstParentContractId);

                    HapiGetTxnRecord opFirstParentNonce = getTxnRecord(committedInnerCreationTx)
                            .andAllChildRecords()
                            .logged()
                            .hasPriority(recordWith()
                                    .contractCallResult(resultWith()
                                            .contractWithNonce(
                                                    spec.registry().getContractId("firstParentContractId"), 2L)));
                    allRunFor(spec, opFirstParentNonce);

                    /** 4. Tries to deploy child from parent and reverts. Asserts contract_nonces entries are null. */
                    final var deployChildAndRevert = atomicBatch(
                                    contractCall(contract, deployChildAndRevertFromParentContractFn, BigInteger.ONE)
                                            .gas(GAS_TO_OFFER)
                                            .via(revertedInnerCreationTx)
                                            .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR);
                    final var deployChildAndRevertTxnRecord = getTxnRecord(revertedInnerCreationTx);
                    allRunFor(spec, deployChildAndRevert, deployChildAndRevertTxnRecord);

                    /* Retrieves contractId of the second deployed contract in the constructor - index 1 */
                    final var secondParentContractId =
                            parentContractsList.get(1).getContractId();
                    spec.registry().saveContractId("secondParentContractId", secondParentContractId);

                    HapiGetTxnRecord opSecondParentNonce = getTxnRecord(revertedInnerCreationTx)
                            .andAllChildRecords()
                            .logged()
                            .hasPriority(recordWith()
                                    .contractCallResult(resultWith()
                                            .contractWithNonce(
                                                    spec.registry().getContractId("secondParentContractId"), null)));
                    allRunFor(spec, opSecondParentNonce);
                }));
    }

    @HapiTest
    final Stream<DynamicTest> badEvmAddressResultsInPrecheckFail() {
        final var BAD_EVM_ADDRESS = "123456";
        final var NAME = "name";
        final var ERC_721_ABI = "ERC721ABI";
        final var BAD_EVM_ADDRESS_CONTRACT = "badEvmAddressContract";

        return hapiTest(
                withOpContext((spec, ctxLog) -> spec.registry()
                        .saveContractId(BAD_EVM_ADDRESS_CONTRACT, spec, ByteString.copyFrom(unhex(BAD_EVM_ADDRESS)))),
                withOpContext((spec, ctxLog) -> allRunFor(
                        spec,
                        atomicBatch(contractCallWithFunctionAbi(
                                                BAD_EVM_ADDRESS_CONTRACT, getABIFor(FUNCTION, NAME, ERC_721_ABI))
                                        .notTryingAsHexedliteral()
                                        .hasPrecheck(INVALID_CONTRACT_ID)
                                        .batchKey(BATCH_OPERATOR))
                                .payingWith(BATCH_OPERATOR)
                                .hasPrecheck(INVALID_CONTRACT_ID))));
    }

    private String getNestedContractAddress(final String contract, final HapiSpec spec) {
        return asHexedSolidityAddress(spec.registry().getContractId(contract));
    }

    private ByteString bookInterpolated(final byte[] jurisdictionInitcode, final String addressBookMirror) {
        return ByteString.copyFrom(new String(jurisdictionInitcode)
                .replaceAll( // NOSONAR
                        "_+AddressBook.sol:AddressBook_+", // NOSONAR
                        addressBookMirror) // NOSONAR ignoring security hotspot in tests
                .getBytes());
    }

    private static final String EXPLICIT_JURISDICTION_CONS_PARAMS =
            "45fd06740000000000000000000000001234567890123456789012345678901234567890";
    private static final String EXPLICIT_MINTER_CONS_PARAMS_TPL =
            "1c26cc85%s0000000000000000000000001234567890123456789012345678901234567890";
    private static final String EXPLICIT_MINTER_CONFIG_PARAMS_TPL = "da71addf000000000000000000000000%s";
    private static final String EXPLICIT_JURISDICTIONS_ADD_PARAMS =
            "218c66ea0000000000000000000000000000000000000000000000000000000000000080000000000000000000000000"
                    + "0000000000000000000000000000000000000339000000000000000000000000123456789012345678901234"
                    + "5678901234567890000000000000000000000000123456789012345678901234567890123456789000000000"
                    + "000000000000000000000000000000000000000000000000000000026e790000000000000000000000000000"
                    + "00000000000000000000000000000000";
}
