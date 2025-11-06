// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.leaky;

import static com.google.protobuf.ByteString.EMPTY;
import static com.hedera.node.app.hapi.utils.EthSigsUtils.recoverAddressFromPubKey;
import static com.hedera.services.bdd.junit.ContextRequirement.FEE_SCHEDULE_OVERRIDES;
import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.spec.HapiPropertySource.asContract;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AccountDetailsAsserts.accountDetailsWith;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.accountWith;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.resultWith;
import static com.hedera.services.bdd.spec.assertions.ContractInfoAsserts.contractWith;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.keys.KeyFactory.KeyType.THRESHOLD;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.contractCallLocal;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountDetails;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAliasedAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getContractInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getReceipt;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTokenNftInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCallWithFunctionAbi;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCustomCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.ethereumCallWithFunctionAbi;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uncheckedSubmit;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadSingleInitCode;
import static com.hedera.services.bdd.spec.transactions.contract.HapiParserUtil.asHeadlongAddress;
import static com.hedera.services.bdd.spec.transactions.contract.HapiParserUtil.asHeadlongAddressArray;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromAccountToAlias;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingUnique;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.SidecarVerbs.expectContractActionSidecarFor;
import static com.hedera.services.bdd.spec.utilops.SidecarVerbs.expectContractBytecodeSansInitcodeFor;
import static com.hedera.services.bdd.spec.utilops.SidecarVerbs.expectContractStateChangesSidecarFor;
import static com.hedera.services.bdd.spec.utilops.SidecarVerbs.sidecarValidation;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.assertionsHold;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.balanceSnapshot;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.childRecordsCheck;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.emptyChildRecordsCheck;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.inParallel;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overridingTwo;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.reduceFeeFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sleepFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.usableTxnIdNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_CONTRACT_SENDER;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
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
import static com.hedera.services.bdd.suites.contract.Utils.aaWith;
import static com.hedera.services.bdd.suites.contract.Utils.asAddress;
import static com.hedera.services.bdd.suites.contract.Utils.asHexedAddress;
import static com.hedera.services.bdd.suites.contract.Utils.asHexedSolidityAddress;
import static com.hedera.services.bdd.suites.contract.Utils.asSolidityAddress;
import static com.hedera.services.bdd.suites.contract.Utils.asToken;
import static com.hedera.services.bdd.suites.contract.Utils.captureOneChildCreate2MetaFor;
import static com.hedera.services.bdd.suites.contract.Utils.extractBytecodeUnhexed;
import static com.hedera.services.bdd.suites.contract.Utils.getABIFor;
import static com.hedera.services.bdd.suites.contract.Utils.getResourcePath;
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
import static com.hedera.services.bdd.suites.contract.opcodes.Create2OperationSuite.CONTRACT_REPORTED_ADDRESS_MESSAGE;
import static com.hedera.services.bdd.suites.contract.opcodes.Create2OperationSuite.EXPECTED_CREATE2_ADDRESS_MESSAGE;
import static com.hedera.services.bdd.suites.contract.opcodes.Create2OperationSuite.GET_ADDRESS;
import static com.hedera.services.bdd.suites.contract.precompile.ApproveAllowanceSuite.ATTACK_CALL;
import static com.hedera.services.bdd.suites.contract.precompile.ApproveAllowanceSuite.CALL_TO;
import static com.hedera.services.bdd.suites.contract.precompile.ApproveAllowanceSuite.CONTRACTS_PERMITTED_DELEGATE_CALLERS;
import static com.hedera.services.bdd.suites.contract.precompile.ApproveAllowanceSuite.DELEGATE_ERC_CALLEE;
import static com.hedera.services.bdd.suites.contract.precompile.ApproveAllowanceSuite.DELEGATE_PRECOMPILE_CALLEE;
import static com.hedera.services.bdd.suites.contract.precompile.ApproveAllowanceSuite.PRETEND_ATTACKER;
import static com.hedera.services.bdd.suites.contract.precompile.ApproveAllowanceSuite.PRETEND_PAIR;
import static com.hedera.services.bdd.suites.contract.precompile.CreatePrecompileSuite.TOKEN_NAME;
import static com.hedera.services.bdd.suites.contract.precompile.ERCPrecompileSuite.NAME_TXN;
import static com.hedera.services.bdd.suites.contract.precompile.token.GasCalculationIntegrityTest.constantGasAssertion;
import static com.hedera.services.bdd.suites.contract.traceability.EncodingUtils.formattedAssertionValue;
import static com.hedera.services.bdd.suites.crypto.AutoAccountCreationSuite.LAZY_MEMO;
import static com.hedera.services.bdd.suites.crypto.AutoCreateUtils.updateSpecFor;
import static com.hedera.services.bdd.suites.crypto.CryptoApproveAllowanceSuite.ADMIN_KEY;
import static com.hedera.services.bdd.suites.utils.contracts.AddressResult.hexedAddress;
import static com.hedera.services.bdd.suites.utils.contracts.precompile.HTSPrecompileResult.htsPrecompileResult;
import static com.hedera.services.stream.proto.ContractActionType.CALL;
import static com.hedera.services.stream.proto.ContractActionType.CREATE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_ACCOUNT_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_PAYER_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_CONTRACT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SOLIDITY_ADDRESS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.MAX_CHILD_RECORDS_EXCEEDED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.MAX_GAS_LIMIT_EXCEEDED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;
import static org.hiero.base.utility.CommonUtils.hex;
import static org.hyperledger.besu.datatypes.Address.contractAddress;
import static org.hyperledger.besu.datatypes.Address.fromHexString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.esaulpaugh.headlong.abi.Address;
import com.google.protobuf.ByteString;
import com.google.protobuf.BytesValue;
import com.hedera.node.app.hapi.utils.contracts.ParsingConstants.FunctionType;
import com.hedera.node.app.hapi.utils.ethereum.EthTxData;
import com.hedera.node.app.hapi.utils.fee.FeeBuilder;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.junit.OrderedInIsolation;
import com.hedera.services.bdd.spec.HapiPropertySource;
import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.assertions.ContractInfoAsserts;
import com.hedera.services.bdd.spec.assertions.StateChange;
import com.hedera.services.bdd.spec.assertions.StorageChange;
import com.hedera.services.bdd.spec.dsl.annotations.Contract;
import com.hedera.services.bdd.spec.dsl.entities.SpecContract;
import com.hedera.services.bdd.spec.queries.QueryVerbs;
import com.hedera.services.bdd.spec.queries.contract.HapiContractCallLocal;
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
import com.hederahashgraph.api.proto.java.TransferList;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.tuweni.bytes.Bytes;
import org.hiero.base.utility.CommonUtils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;

@SuppressWarnings("java:S1192") // "string literal should not be duplicated" - this rule makes test suites worse
@OrderedInIsolation
public class LeakyContractTestsSuite {
    public static final String CREATE_TX = "createTX";
    public static final String CREATE_TX_REC = "createTXRec";
    public static final String FALSE = "false";
    public static final int GAS_TO_OFFER = 2_000_000;
    private static final Logger log = LogManager.getLogger(LeakyContractTestsSuite.class);
    public static final String SENDER = "yahcliSender";
    public static final String RECEIVER = "yahcliReceiver";
    public static final String TOKEN_TRANSFER_CONTRACT = "TokenTransferContract";
    public static final String TRANSFER_TOKEN_PUBLIC = "transferTokenPublic";
    private static final String FUNGIBLE_TOKEN = "fungibleToken";
    private static final String NON_FUNGIBLE_TOKEN = "nonFungibleToken";
    private static final String MULTI_KEY = "purpose";
    private static final String ACCOUNT = "anybody";
    public static final String RECIPIENT = "recipient";
    private static final String FIRST = "FIRST";
    private static final ByteString FIRST_META = ByteString.copyFrom(FIRST.getBytes(StandardCharsets.UTF_8));
    public static final String TRANSFER_SIG_NAME = "transferSig";
    public static final String ERC_20_CONTRACT = "ERC20Contract";
    private static final String TRANSFER_TXN = "transferTxn";
    public static final String TRANSFER = "transfer";
    public static final String APPROVE = "approve";
    private static final String NF_TOKEN = "nfToken";
    public static final String TRANSFER_FROM = "transferFrom";
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
    public static final String TRANSFER_SIGNATURE = "Transfer(address,address,uint256)";
    private static final String CREATION = "creation";
    private static final String ENTITY_MEMO = "JUST DO IT";
    private static final String CREATE_2_TXN = "create2Txn";
    public static final String GET_BYTECODE = "getBytecode";
    public static final String CONTRACT_REPORTED_LOG_MESSAGE = "Contract reported TestContract initcode is {} bytes";
    public static final String DEPLOY = "deploy";
    private static final String CREATE_2_TXN_2 = "create2Txn2";
    public static final String NESTED_LAZY_CREATE_VIA_CONSTRUCTOR = "NestedLazyCreateViaConstructor";
    private static final long NONEXISTENT_CONTRACT_NUM = 1_234_567_890L;

    @SuppressWarnings("java:S5960")
    @Order(37)
    @LeakyHapiTest(overrides = {"contracts.evm.version"})
    final Stream<DynamicTest> canMergeCreate2ChildWithHollowAccountAndSelfDestructInConstructor() {
        final var tcValue = 1_234L;
        final var contract = "Create2SelfDestructContract";
        final var creation = CREATION;
        final var salt = BigInteger.valueOf(42);
        final var adminKey = ADMIN_KEY;
        final AtomicReference<byte[]> factoryEvmAddress = new AtomicReference<>();
        final AtomicReference<String> expectedCreate2Address = new AtomicReference<>();
        final AtomicReference<String> hollowCreationAddress = new AtomicReference<>();
        final AtomicReference<String> mergedAliasAddr = new AtomicReference<>();
        final AtomicReference<String> mergedMirrorAddr = new AtomicReference<>();
        final AtomicReference<String> mergedAliasAddr2 = new AtomicReference<>();
        final AtomicReference<String> mergedMirrorAddr2 = new AtomicReference<>();
        final AtomicReference<byte[]> testContractInitcode = new AtomicReference<>();
        return hapiTest(
                overriding("contracts.evm.version", "v0.46"),
                newKeyNamed(adminKey),
                newKeyNamed(MULTI_KEY),
                uploadInitCode(contract),
                contractCreate(contract)
                        .payingWith(GENESIS)
                        .adminKey(adminKey)
                        .entityMemo(ENTITY_MEMO)
                        .via(CREATE_2_TXN)
                        .exposingContractIdTo(id -> factoryEvmAddress.set(asSolidityAddress(id))),
                sourcing(() -> contractCallLocal(
                                contract, GET_BYTECODE, asHeadlongAddress(factoryEvmAddress.get()), salt)
                        .exposingTypedResultsTo(results -> {
                            final var tcInitcode = (byte[]) results[0];
                            testContractInitcode.set(tcInitcode);
                            log.info(CONTRACT_REPORTED_LOG_MESSAGE, tcInitcode.length);
                        })
                        .payingWith(GENESIS)
                        .nodePayment(ONE_HBAR)),
                sourcing(() -> setExpectedCreate2Address(contract, salt, expectedCreate2Address, testContractInitcode)),
                // Now create a hollow account at the desired address
                cryptoTransfer((spec, b) -> {
                            final var defaultPayerId = spec.registry().getAccountID(DEFAULT_PAYER);
                            b.setTransfers(TransferList.newBuilder()
                                    .addAccountAmounts(Utils.aaWith(spec, expectedCreate2Address.get(), +ONE_HBAR))
                                    .addAccountAmounts(aaWith(defaultPayerId, -ONE_HBAR)));
                        })
                        .signedBy(DEFAULT_PAYER)
                        .fee(ONE_HBAR)
                        .via(creation),
                getTxnRecord(creation)
                        .andAllChildRecords()
                        .exposingCreationsTo(l -> hollowCreationAddress.set(l.get(0))),
                getContractInfo(contract).has(ContractInfoAsserts.contractWith().balance(0L)),
                sourcing(() -> contractCall(contract, DEPLOY, testContractInitcode.get(), salt)
                        .payingWith(GENESIS)
                        .gas(4_000_000L)
                        .sending(tcValue)
                        .via(CREATE_2_TXN)),
                captureOneChildCreate2MetaFor(
                        "Merged deployed contract with hollow account and self-destructed the contract",
                        CREATE_2_TXN,
                        mergedMirrorAddr,
                        mergedAliasAddr),
                sourcing(() -> getContractInfo(mergedMirrorAddr.get())
                        .has(ContractInfoAsserts.contractWith().isDeleted())),
                getContractInfo(contract).has(ContractInfoAsserts.contractWith().balance(ONE_HBAR + tcValue)),
                /* Can repeat CREATE2 with same args because the previous contract was destroyed in the constructor*/
                sourcing(() -> contractCall(contract, DEPLOY, testContractInitcode.get(), salt)
                        .payingWith(GENESIS)
                        .gas(4_000_000L)
                        .sending(tcValue)
                        .via(CREATE_2_TXN_2)),
                captureOneChildCreate2MetaFor(
                        "Merged deployed contract with hollow account and self-destructed the contract",
                        CREATE_2_TXN_2,
                        mergedMirrorAddr2,
                        mergedAliasAddr2),
                sourcing(() -> getContractInfo(mergedMirrorAddr2.get())
                        .has(ContractInfoAsserts.contractWith().isDeleted())),
                sourcing(() -> assertionsHold((spec, asertLog) -> {
                    assertEquals(mergedAliasAddr.get(), mergedAliasAddr2.get(), "Alias addresses must be equal!");
                    assertNotEquals(
                            mergedMirrorAddr.get(), mergedMirrorAddr2.get(), "Mirror addresses must not be equal!");
                })));
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
                        contractCall(
                                        ERC_20_CONTRACT,
                                        TRANSFER,
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getTokenID(NON_FUNGIBLE_TOKEN))),
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getAccountID(RECIPIENT))),
                                        BigInteger.TWO)
                                .payingWith(ACCOUNT)
                                .alsoSigningWithFullPrefix(MULTI_KEY)
                                .via(TRANSFER_TXN)
                                .gas(GAS_TO_OFFER)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED))),
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
                        contractCall(
                                        ERC_20_CONTRACT,
                                        "nameNTimes",
                                        asHeadlongAddress(
                                                asHexedAddress(spec.registry().getTokenID(FUNGIBLE_TOKEN))),
                                        BigInteger.valueOf(51))
                                .payingWith(ACCOUNT)
                                .via(NAME_TXN)
                                .gas(4_000_000)
                                .hasKnownStatus(MAX_CHILD_RECORDS_EXCEEDED))),
                getTxnRecord(NAME_TXN)
                        .andAllChildRecords()
                        .logged()
                        .hasPriority(recordWith()
                                .contractCallResult(resultWith()
                                        .error(Bytes.of(MAX_CHILD_RECORDS_EXCEEDED
                                                        .name()
                                                        .getBytes())
                                                .toHexString())
                                        .gasUsed(4_000_000))),
                getAccountDetails(ACCOUNT)
                        .has(accountDetailsWith()
                                .balanceLessThan(INIT_ACCOUNT_BALANCE - REDUCED_NETWORK_FEE - REDUCED_NODE_FEE)));
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
                uncheckedSubmit(contractCall(PAY_RECEIVABLE_CONTRACT, DEPOSIT, BigInteger.valueOf(overdraftAmount))
                                .txnId(uncheckedCC)
                                .payingWith(overAmbitiousPayer)
                                .sending(overdraftAmount))
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
                    var transferCall = contractCall(TRANSFERRING_CONTRACT, TRANSFER_TO_CALLER, BigInteger.valueOf(10))
                            .payingWith(sender)
                            .via(transferTxn)
                            .logged();

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
                contractCall(SIMPLE_UPDATE_CONTRACT, "set", BigInteger.valueOf(5), BigInteger.valueOf(42))
                        .gas(300_000L)
                        .via(CALL_TX),
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
                contractCall(toyMaker, "make")
                        .payingWith(normalPayer)
                        .exposingGasTo((status, gasUsed) -> normalPayerGasUsed.set(gasUsed)),
                contractCall(toyMaker, "make")
                        .payingWith(longLivedPayer)
                        .exposingGasTo((status, gasUsed) -> longLivedPayerGasUsed.set(gasUsed)),
                assertionsHold((spec, opLog) -> assertEquals(
                        normalPayerGasUsed.get(),
                        longLivedPayerGasUsed.get(),
                        "Payer expiry should not affect create storage" + " cost")),
                // Verify that we are still charged a "typical" amount despite the payer and
                // the original sender contract having extremely long expiry dates
                sourcing(() -> contractCall(createIndirectly, "makeOpaquely", asHeadlongAddress(toyMakerMirror.get()))
                        .payingWith(longLivedPayer)));
    }

    @LeakyHapiTest(overrides = {"contracts.maxGasPerSec"})
    final Stream<DynamicTest> gasLimitOverMaxGasLimitFailsPrecheck() {
        return hapiTest(
                uploadInitCode(SIMPLE_UPDATE_CONTRACT),
                uploadInitCode(EMPTY_CONSTRUCTOR_CONTRACT),
                contractCreate(SIMPLE_UPDATE_CONTRACT).gas(300_000L),
                overriding("contracts.maxGasPerSec", "100"),
                contractCall(SIMPLE_UPDATE_CONTRACT, "set", BigInteger.valueOf(5), BigInteger.valueOf(42))
                        .gas(23_000L)
                        .hasPrecheck(MAX_GAS_LIMIT_EXCEEDED),
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
                    var transferCall = contractCall(TRANSFERRING_CONTRACT, TRANSFER_TO_CALLER, BigInteger.ZERO)
                            .payingWith(DEFAULT_CONTRACT_SENDER)
                            .via(transferTxn)
                            .logged();

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
                contractCall(contract, DEPOSIT, TRANSFER_AMOUNT, 0L, "So we out-danced thought...")
                        .via("noLogsCallTxn")
                        .sending(TRANSFER_AMOUNT),
                contractCall(contract, DEPOSIT, TRANSFER_AMOUNT, 5L, "So we out-danced thought...")
                        .via("loggedCallTxn")
                        .sending(TRANSFER_AMOUNT),
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
    final Stream<DynamicTest> autoAssociationSlotsAppearsInInfo() {
        final int maxAutoAssociations = 100;
        final String CONTRACT = "Multipurpose";

        return hapiTest(
                newKeyNamed(ADMIN_KEY),
                uploadInitCode(CONTRACT),
                contractCreate(CONTRACT).adminKey(ADMIN_KEY).maxAutomaticTokenAssociations(maxAutoAssociations),
                getContractInfo(CONTRACT)
                        .has(ContractInfoAsserts.contractWith().maxAutoAssociations(maxAutoAssociations)));
    }

    @Order(16)
    @LeakyHapiTest(overrides = {"contracts.maxRefundPercentOfGasLimit"})
    final Stream<DynamicTest> createMaxRefundIsMaxGasRefundConfiguredWhenTXGasPriceIsSmaller() {
        return hapiTest(
                overriding("contracts.maxRefundPercentOfGasLimit", "5"),
                uploadInitCode(EMPTY_CONSTRUCTOR_CONTRACT),
                contractCreate(EMPTY_CONSTRUCTOR_CONTRACT).gas(300_000L).via(CREATE_TX),
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
                contractCreate(EMPTY_CONSTRUCTOR_CONTRACT).gas(300_000L).via(CREATE_TX),
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
                contractCall(contract, "propagate").gas(4_000_000L).via(call),
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
                contractCall(contract, "holdTemporary", BigInteger.valueOf(10)).via("tempHoldTx"),
                contractCall(contract, "holdPermanently", BigInteger.valueOf(10))
                        .via("permHoldTx"),
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

    @HapiTest
    @Order(6)
    final Stream<DynamicTest> canCallPendingContractSafely() {
        final var numSlots = 64L;
        final var createBurstSize = 500;
        final long[] targets = {19, 24};
        final AtomicLong createdFileNum = new AtomicLong();
        final var callTxn = "callTxn";
        final var contract = "FibonacciPlus";
        final var expiry = Instant.now().getEpochSecond() + 7776000;

        return hapiTest(
                uploadSingleInitCode(contract, expiry, GENESIS, createdFileNum::set),
                inParallel(IntStream.range(0, createBurstSize)
                        .mapToObj(i -> contractCustomCreate(contract, String.valueOf(i), numSlots)
                                .fee(ONE_HUNDRED_HBARS)
                                .gas(800_000L)
                                .payingWith(GENESIS)
                                .noLogging()
                                .deferStatusResolution()
                                .bytecode(contract)
                                .adminKey(THRESHOLD))
                        .toArray(HapiSpecOperation[]::new)),
                sourcing(() -> contractCallWithFunctionAbi(
                                "0.0." + (createdFileNum.get() + createBurstSize),
                                getABIFor(FUNCTION, "addNthFib", contract),
                                targets,
                                12L)
                        .payingWith(GENESIS)
                        .gas(300_000L)
                        .via(callTxn)));
    }

    @LeakyHapiTest
    final Stream<DynamicTest> bytecodeSidecarExternalizedEvenWithInlineInitcode() {
        return hapiTest(
                sidecarValidation(),
                contractCreate("CreateTrivial")
                        .via("inlineCreation")
                        .inlineInitCode(extractBytecodeUnhexed(getResourcePath("CreateTrivial", ".bin"))),
                withOpContext((spec, opLog) -> {
                    final HapiGetTxnRecord txnRecord = getTxnRecord("inlineCreation");
                    allRunFor(
                            spec,
                            txnRecord,
                            expectContractActionSidecarFor(
                                    "inlineCreation",
                                    List.of(ContractAction.newBuilder()
                                            .setCallType(CREATE)
                                            .setCallOperationType(CallOperationType.OP_CREATE)
                                            .setCallingAccount(spec.registry().getAccountID(DEFAULT_PAYER))
                                            .setRecipientContract(
                                                    spec.registry().getContractId("CreateTrivial"))
                                            .setGas(2934672)
                                            .setGasUsed(214)
                                            .setOutput(EMPTY)
                                            .build())));
                }),
                expectContractBytecodeSansInitcodeFor("inlineCreation", "CreateTrivial"));
    }

    @LeakyHapiTest(overrides = {"contracts.evm.version"})
    @Tag(MATS)
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
                contractCreate(LAZY_CREATE_CONTRACT).via(CALL_TX_REC).gas(6_000_000L),
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
                            contractCall(
                                            LAZY_CREATE_CONTRACT,
                                            callLazyCreateFunction,
                                            mirrorAddrWith(spec, NONEXISTENT_CONTRACT_NUM))
                                    .sending(depositAmount)
                                    .via(mirrorTxn)
                                    .hasKnownStatus(CONTRACT_REVERT_EXECUTED)
                                    .gas(6_000_000),
                            emptyChildRecordsCheck(mirrorTxn, CONTRACT_REVERT_EXECUTED),
                            getAccountInfo(String.valueOf(NONEXISTENT_CONTRACT_NUM))
                                    .hasCostAnswerPrecheck(INVALID_ACCOUNT_ID),
                            // given a reverting contract call, should also revert the hollow account creation
                            contractCall(LAZY_CREATE_CONTRACT, revertingCallLazyCreateFunction, address)
                                    .sending(depositAmount)
                                    .via(revertingTxn)
                                    .hasKnownStatus(CONTRACT_REVERT_EXECUTED)
                                    .gas(6_000_000),
                            emptyChildRecordsCheck(revertingTxn, CONTRACT_REVERT_EXECUTED),
                            getAliasedAccountInfo(evmAddress).hasCostAnswerPrecheck(INVALID_ACCOUNT_ID),
                            // given a valid address that is derived from an ECDSA key, should create hollow account
                            contractCall(LAZY_CREATE_CONTRACT, callLazyCreateFunction, address)
                                    .via(payTxn)
                                    .sending(depositAmount)
                                    .hasKnownStatus(SUCCESS)
                                    .gas(6_000_000),
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
                            contractCall(LAZY_CREATE_CONTRACT, createTooManyHollowAccounts, (Object)
                                            asHeadlongAddressArray(addressBytes, addressBytes2))
                                    .sending(depositAmount)
                                    .via(TRANSFER_TXN)
                                    .gas(6_000_000)
                                    .hasKnownStatus(MAX_CHILD_RECORDS_EXCEEDED),
                            getAliasedAccountInfo(ecdsaKey.toByteString())
                                    .logged()
                                    .hasCostAnswerPrecheck(INVALID_ACCOUNT_ID),
                            getAliasedAccountInfo(ecdsaKey2.toByteString())
                                    .logged()
                                    .hasCostAnswerPrecheck(INVALID_ACCOUNT_ID));
                }),
                emptyChildRecordsCheck(TRANSFER_TXN, MAX_CHILD_RECORDS_EXCEEDED));
    }

    @Order(22)
    @LeakyHapiTest(overrides = {"contracts.permittedDelegateCallers"})
    final Stream<DynamicTest> whitelistPositiveCase() {
        final AtomicLong whitelistedCalleeMirrorNum = new AtomicLong();
        final AtomicReference<TokenID> tokenID = new AtomicReference<>();
        final AtomicReference<String> attackerMirrorAddr = new AtomicReference<>();
        final AtomicReference<byte[]> whitelistedCalleeMirrorAddr = new AtomicReference<>();
        return hapiTest(
                cryptoCreate(TOKEN_TREASURY),
                cryptoCreate(PRETEND_ATTACKER)
                        .exposingCreatedIdTo(id -> attackerMirrorAddr.set(asHexedSolidityAddress(id))),
                tokenCreate(FUNGIBLE_TOKEN)
                        .initialSupply(Long.MAX_VALUE)
                        .tokenType(FUNGIBLE_COMMON)
                        .treasury(TOKEN_TREASURY)
                        .exposingCreatedIdTo(id -> tokenID.set(asToken(id))),
                uploadInitCode(PRETEND_PAIR),
                contractCreate(PRETEND_PAIR).adminKey(DEFAULT_PAYER),
                uploadInitCode(DELEGATE_PRECOMPILE_CALLEE),
                contractCreate(DELEGATE_PRECOMPILE_CALLEE)
                        .adminKey(DEFAULT_PAYER)
                        .exposingContractIdTo(id -> {
                            whitelistedCalleeMirrorNum.set(id.getContractNum());
                            whitelistedCalleeMirrorAddr.set(asSolidityAddress(id));
                        }),
                tokenAssociate(PRETEND_PAIR, FUNGIBLE_TOKEN),
                tokenAssociate(DELEGATE_PRECOMPILE_CALLEE, FUNGIBLE_TOKEN),
                sourcing(() -> overriding(
                        CONTRACTS_PERMITTED_DELEGATE_CALLERS, String.valueOf(whitelistedCalleeMirrorNum.get()))),
                sourcing(() -> contractCall(
                                PRETEND_PAIR,
                                CALL_TO,
                                asHeadlongAddress(whitelistedCalleeMirrorAddr.get()),
                                asHeadlongAddress(asSolidityAddress(tokenID.get())),
                                asHeadlongAddress(attackerMirrorAddr.get()))
                        .via(ATTACK_CALL)
                        .gas(5_000_000L)
                        .hasKnownStatus(SUCCESS)),
                // Because this callee is on the whitelist, the pair WILL have an allowance
                // here
                getAccountDetails(PRETEND_PAIR).has(accountDetailsWith().tokenAllowancesCount(1)),
                // Instead of the callee
                getAccountDetails(DELEGATE_PRECOMPILE_CALLEE)
                        .has(accountDetailsWith().tokenAllowancesCount(0)));
    }

    @LeakyHapiTest(overrides = {"contracts.permittedDelegateCallers"})
    final Stream<DynamicTest> whitelistNegativeCases() {
        final AtomicLong unlistedCalleeMirrorNum = new AtomicLong();
        final AtomicLong whitelistedCalleeMirrorNum = new AtomicLong();
        final AtomicReference<TokenID> tokenID = new AtomicReference<>();
        final AtomicReference<String> attackerMirrorAddr = new AtomicReference<>();
        final AtomicReference<byte[]> unListedCalleeMirrorAddr = new AtomicReference<>();
        final AtomicReference<byte[]> whitelistedCalleeMirrorAddr = new AtomicReference<>();
        return hapiTest(
                cryptoCreate(TOKEN_TREASURY),
                cryptoCreate(PRETEND_ATTACKER)
                        .exposingCreatedIdTo(id -> attackerMirrorAddr.set(asHexedSolidityAddress(id))),
                tokenCreate(FUNGIBLE_TOKEN)
                        .initialSupply(Long.MAX_VALUE)
                        .tokenType(FUNGIBLE_COMMON)
                        .treasury(TOKEN_TREASURY)
                        .exposingCreatedIdTo(id -> tokenID.set(asToken(id))),
                uploadInitCode(PRETEND_PAIR),
                contractCreate(PRETEND_PAIR).adminKey(DEFAULT_PAYER),
                uploadInitCode(DELEGATE_ERC_CALLEE),
                contractCreate(DELEGATE_ERC_CALLEE).adminKey(DEFAULT_PAYER).exposingContractIdTo(id -> {
                    whitelistedCalleeMirrorNum.set(id.getContractNum());
                    whitelistedCalleeMirrorAddr.set(asSolidityAddress(id));
                }),
                uploadInitCode(DELEGATE_PRECOMPILE_CALLEE),
                contractCreate(DELEGATE_PRECOMPILE_CALLEE)
                        .adminKey(DEFAULT_PAYER)
                        .exposingContractIdTo(id -> {
                            unlistedCalleeMirrorNum.set(id.getContractNum());
                            unListedCalleeMirrorAddr.set(asSolidityAddress(id));
                        }),
                tokenAssociate(PRETEND_PAIR, FUNGIBLE_TOKEN),
                tokenAssociate(DELEGATE_ERC_CALLEE, FUNGIBLE_TOKEN),
                tokenAssociate(DELEGATE_PRECOMPILE_CALLEE, FUNGIBLE_TOKEN),
                sourcing(() -> overriding(
                        CONTRACTS_PERMITTED_DELEGATE_CALLERS, String.valueOf(whitelistedCalleeMirrorNum.get()))),
                sourcing(() -> contractCall(
                                PRETEND_PAIR,
                                CALL_TO,
                                asHeadlongAddress(unListedCalleeMirrorAddr.get()),
                                asHeadlongAddress(asSolidityAddress(tokenID.get())),
                                asHeadlongAddress(attackerMirrorAddr.get()))
                        .gas(5_000_000L)
                        .hasKnownStatus(SUCCESS)),
                // Because this callee isn't on the whitelist, the pair won't have an
                // allowance here
                getAccountDetails(PRETEND_PAIR).has(accountDetailsWith().tokenAllowancesCount(0)),
                // Instead nobody gets an allowance
                getAccountDetails(DELEGATE_PRECOMPILE_CALLEE)
                        .has(accountDetailsWith().tokenAllowancesCount(0)),
                sourcing(() -> contractCall(
                                PRETEND_PAIR,
                                CALL_TO,
                                asHeadlongAddress(whitelistedCalleeMirrorAddr.get()),
                                asHeadlongAddress(asSolidityAddress(tokenID.get())),
                                asHeadlongAddress(attackerMirrorAddr.get()))
                        .gas(5_000_000L)
                        .hasKnownStatus(SUCCESS)),
                // Even though this is on the whitelist, b/c the whitelisted contract
                // is going through a delegatecall "chain" via the ERC-20 call, the pair
                // still won't have an allowance here
                getAccountDetails(PRETEND_PAIR).has(accountDetailsWith().tokenAllowancesCount(0)),
                // Instead of the callee
                getAccountDetails(DELEGATE_ERC_CALLEE).has(accountDetailsWith().tokenAllowancesCount(0)));
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
                contractCreate(contract).logged().gas(1_000_000L).via("txn"),
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
                sourcing(() -> contractCall(
                                SOME_ERC_721_SCENARIOS,
                                GET_APPROVED,
                                asHeadlongAddress(zTokenMirrorAddr.get()),
                                BigInteger.ONE)
                        .via(MISSING_TOKEN)
                        .gas(1_000_000)
                        .hasKnownStatus(INVALID_SOLIDITY_ADDRESS)),
                sourcing(() -> contractCall(
                                SOME_ERC_721_SCENARIOS,
                                DO_SPECIFIC_APPROVAL,
                                asHeadlongAddress(tokenMirrorAddr.get()),
                                asHeadlongAddress(aCivilianMirrorAddr.get()),
                                BigInteger.ONE)
                        .gas(1_000_000)),
                sourcing(() -> contractCall(
                                SOME_ERC_721_SCENARIOS,
                                GET_APPROVED,
                                asHeadlongAddress(tokenMirrorAddr.get()),
                                BigInteger.valueOf(55))
                        .via("MISSING_SERIAL")
                        .gas(1_000_000)
                        .hasKnownStatus(CONTRACT_REVERT_EXECUTED)),
                getTokenNftInfo(NF_TOKEN, 1L).logged(),
                sourcing(() -> contractCall(
                                SOME_ERC_721_SCENARIOS,
                                GET_APPROVED,
                                asHeadlongAddress(tokenMirrorAddr.get()),
                                BigInteger.TWO)
                        .via("MISSING_SPENDER")
                        .gas(1_000_000)
                        .hasKnownStatus(SUCCESS)),
                sourcing(() -> contractCall(
                                SOME_ERC_721_SCENARIOS,
                                GET_APPROVED,
                                asHeadlongAddress(tokenMirrorAddr.get()),
                                BigInteger.ONE)
                        .via(WITH_SPENDER)
                        .gas(1_000_000)
                        .hasKnownStatus(SUCCESS)),
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
                sourcing(() -> contractCall(
                                SOME_ERC_721_SCENARIOS,
                                GET_BALANCE_OF,
                                asHeadlongAddress(tokenMirrorAddr.get()),
                                asHeadlongAddress(aCivilianMirrorAddr.get()))
                        .via("BALANCE_OF")
                        .gas(1_000_000)
                        .hasKnownStatus(SUCCESS)),
                sourcing(() -> contractCall(
                                SOME_ERC_721_SCENARIOS,
                                GET_BALANCE_OF,
                                asHeadlongAddress(zTokenMirrorAddr.get()),
                                asHeadlongAddress(aCivilianMirrorAddr.get()))
                        .via(MISSING_TOKEN)
                        .gas(1_000_000)
                        .hasKnownStatus(INVALID_SOLIDITY_ADDRESS)),
                sourcing(() -> contractCall(
                                SOME_ERC_721_SCENARIOS,
                                GET_BALANCE_OF,
                                asHeadlongAddress(tokenMirrorAddr.get()),
                                asHeadlongAddress(bCivilianMirrorAddr.get()))
                        .via("NOT_ASSOCIATED")
                        .gas(1_000_000)
                        .hasKnownStatus(SUCCESS)),
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
                sourcing(() -> contractCall(
                                SOME_ERC_721_SCENARIOS,
                                GET_OWNER_OF,
                                asHeadlongAddress(zTokenMirrorAddr.get()),
                                BigInteger.ONE)
                        .via(MISSING_TOKEN)
                        .gas(1_000_000)
                        .hasKnownStatus(INVALID_SOLIDITY_ADDRESS)),
                sourcing(() -> contractCall(
                                SOME_ERC_721_SCENARIOS,
                                GET_OWNER_OF,
                                asHeadlongAddress(tokenMirrorAddr.get()),
                                BigInteger.valueOf(55))
                        .gas(1_000_000)
                        .hasKnownStatus(CONTRACT_REVERT_EXECUTED)),
                sourcing(() -> contractCall(
                                SOME_ERC_721_SCENARIOS,
                                GET_OWNER_OF,
                                asHeadlongAddress(tokenMirrorAddr.get()),
                                BigInteger.TWO)
                        .via("TREASURY_OWNER")
                        .gas(1_000_000)
                        .hasKnownStatus(SUCCESS)),
                cryptoTransfer(movingUnique(NF_TOKEN, 1L).between(SOME_ERC_721_SCENARIOS, A_CIVILIAN)),
                sourcing(() -> contractCall(
                                SOME_ERC_721_SCENARIOS,
                                GET_OWNER_OF,
                                asHeadlongAddress(tokenMirrorAddr.get()),
                                BigInteger.ONE)
                        .via("CIVILIAN_OWNER")
                        .gas(1_000_000)
                        .hasKnownStatus(SUCCESS)),
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
                        ethereumCallWithFunctionAbi(
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
                                .hasPrecheck(INVALID_CONTRACT_ID))));
    }

    @Order(38)
    @LeakyHapiTest
    final Stream<DynamicTest> invalidContractCallFailsInV038() {
        final var function = getABIFor(FUNCTION, "getIndirect", "CreateTrivial");

        return hapiTest(
                overriding("contracts.evm.version", "v0.38"),
                withOpContext((spec, ctxLog) -> spec.registry().saveContractId("invalid", asContract("0.0.100000001"))),
                contractCallWithFunctionAbi("invalid", function).hasKnownStatus(INVALID_CONTRACT_ID));
    }

    private HapiContractCallLocal setExpectedCreate2Address(
            String contract,
            BigInteger salt,
            AtomicReference<String> expectedCreate2Address,
            AtomicReference<byte[]> testContractInitcode) {
        return contractCallLocal(contract, GET_ADDRESS, testContractInitcode.get(), salt)
                .exposingTypedResultsTo(results -> {
                    log.info(CONTRACT_REPORTED_ADDRESS_MESSAGE, results);
                    final var expectedAddrBytes = (Address) results[0];
                    final var hexedAddress = hex(
                            Bytes.fromHexString(expectedAddrBytes.toString()).toArray());
                    log.info(EXPECTED_CREATE2_ADDRESS_MESSAGE, hexedAddress);
                    expectedCreate2Address.set(hexedAddress);
                })
                .payingWith(GENESIS);
    }

    @Order(39)
    @LeakyHapiTest(overrides = "contracts.maxRefundPercentOfGasLimit")
    @DisplayName("Check that gas refund can be changed")
    public Stream<DynamicTest> checkGasRefundChange(
            @Contract(contract = "CallingContract") final SpecContract contract) {
        record RefundPair(String refundPercentage, Long expectedGasUsed) {}
        final var gasRefund = Stream.of(
                new RefundPair("0", 400_000L),
                new RefundPair("10", 360_000L),
                new RefundPair("20", 320_000L),
                new RefundPair("30", 280_000L),
                new RefundPair("40", 240_000L),
                new RefundPair("50", 200_000L),
                new RefundPair("60", 160_000L),
                new RefundPair("70", 120_000L),
                new RefundPair("80", 80_000L),
                new RefundPair("90", 40_000L),
                new RefundPair("100", 23_653L));

        return gasRefund.flatMap(refundPair -> hapiTest(
                overriding("contracts.maxRefundPercentOfGasLimit", refundPair.refundPercentage),
                contract.call("setVar1", BigInteger.ONE)
                        .gas(400_000L)
                        .andAssert(txn ->
                                txn.exposingGasTo(constantGasAssertion(new AtomicLong(refundPair.expectedGasUsed))))));
    }
}
