// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.hips.hip1340;

import static com.esaulpaugh.headlong.abi.Address.toChecksumAddress;
import static com.hedera.node.app.hapi.utils.EthSigsUtils.recoverAddressFromPubKey;
import static com.hedera.node.app.service.contract.impl.utils.ConstantUtils.ZERO_ADDRESS;
import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.accountWith;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.accountAllowanceHook;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.ethereumCryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromAccountToAlias;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.useAddressOfKey;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.RELAYER;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SHAPE;
import static com.hedera.services.bdd.suites.contract.Utils.aaWith;
import static com.hedera.services.bdd.suites.contract.Utils.aaWithPreHook;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.esaulpaugh.headlong.abi.Address;
import com.esaulpaugh.headlong.abi.Function;
import com.esaulpaugh.headlong.abi.Tuple;
import com.esaulpaugh.headlong.abi.TupleType;
import com.google.common.hash.Hashing;
import com.google.protobuf.ByteString;
import com.hedera.hapi.node.base.EvmHookCall;
import com.hedera.hapi.node.base.HookCall;
import com.hedera.node.app.hapi.utils.ethereum.EthTxData.EthTransactionType;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.transactions.contract.HapiEthereumCall;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.ContractFunctionResult;
import com.hederahashgraph.api.proto.java.ContractLoginfo;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.TransactionRecord;
import com.hederahashgraph.api.proto.java.TransferList;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.bouncycastle.util.encoders.Hex;
import org.hiero.base.utility.CommonUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(SMART_CONTRACT)
@DisplayName("Code Delegation Tests")
@HapiTestLifecycle
public class CodeDelegationTests {
    public static final Address HTS_HOOKS_CONTRACT_ADDRESS = Address.wrap("0x000000000000000000000000000000000000016D");

    private static final String CODE_DELEGATION_CONTRACT = "CodeDelegationContract";

    private final AtomicInteger accountIdCounter = new AtomicInteger(0);

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(Map.of("hooks.hooksEnabled", "true"));
    }

    @HapiTest
    final Stream<DynamicTest> testCodeDelegationFallbackMethod() {
        return hapiTest(withOpContext((spec, opLog) -> {
            final var payer = createFundedEvmAccountWithKey(spec, ONE_HUNDRED_HBARS);
            final var caller = createFundedEvmAccountWithKey(spec, ONE_HUNDRED_HBARS);

            /* Create a delegation target contract and an EOA delegating to it. */
            final var delegationTargetContract = createEvmContract(spec, CODE_DELEGATION_CONTRACT);
            final var delegatingEoa = createFundedEvmAccountWithKey(spec, ONE_HUNDRED_HBARS);
            nativeSetCodeDelegation(spec, delegatingEoa, delegationTargetContract);

            final AtomicReference<ContractLoginfo> logInfo = new AtomicReference<>();

            final var callData = "cafebabe";

            /* Call the EOA */
            allRunFor(
                    spec,
                    HapiEthereumCall.explicitlyTo(delegatingEoa.evmAddressBytes(), 0)
                            .withExplicitParams(() -> callData)
                            .payingWith(payer.name())
                            .signingWith(caller.keyName())
                            .exposingEventDataTo(logInfo::set)
                            .hasKnownStatus(ResponseCodeEnum.SUCCESS));

            // Verify that the event originates from the EOA
            assertEquals(
                    delegatingEoa.accountId().getAccountNum(),
                    logInfo.get().getContractID().getContractNum());

            // And verify the remaining fields of the event
            final var eventData = logInfo.get().getData().toByteArray();
            final var eventArgs =
                    TupleType.parse("(address,address,uint256,bytes)").decode(eventData);

            final var senderInEvent = (Address) eventArgs.get(0);
            final var thisAddressInEvent = (Address) eventArgs.get(1);
            final var valueInEvent = (BigInteger) eventArgs.get(2);
            final var callDataInEvent = (byte[]) eventArgs.get(3);

            assertEquals(caller.evmAddress(), senderInEvent);
            assertEquals(delegatingEoa.evmAddress(), thisAddressInEvent);
            assertEquals(BigInteger.ZERO, valueInEvent);
            assertArrayEquals(Hex.decode(callData), callDataInEvent);
        }));
    }

    @HapiTest
    final Stream<DynamicTest> testCodeDelegationStorage() {
        return hapiTest(withOpContext((spec, opLog) -> {
            final var payer = createFundedEvmAccountWithKey(spec, ONE_HUNDRED_HBARS);
            final var caller = createFundedEvmAccountWithKey(spec, ONE_HUNDRED_HBARS);

            /* Create a delegation target contract and an EOA delegating to it. */
            final var delegationTargetContract = createEvmContract(spec, CODE_DELEGATION_CONTRACT);
            final var delegatingEoa = createFundedEvmAccountWithKey(spec, ONE_HUNDRED_HBARS);
            nativeSetCodeDelegation(spec, delegatingEoa, delegationTargetContract);

            final AtomicReference<ContractLoginfo> logInfo = new AtomicReference<>();

            final var callData = new Function("storeAndEmit(uint256)")
                    .encodeCall(Tuple.singleton(BigInteger.valueOf(1234)))
                    .array();

            allRunFor(
                    spec,
                    HapiEthereumCall.explicitlyTo(delegatingEoa.evmAddressBytes(), 0)
                            .withExplicitParams(() -> Hex.toHexString(callData))
                            .payingWith(payer.name())
                            .signingWith(caller.keyName())
                            .exposingEventDataTo(logInfo::set)
                            .hasKnownStatus(ResponseCodeEnum.SUCCESS));

            // Verify that the event originates from the EOA
            assertEquals(
                    delegatingEoa.accountId.getAccountNum(),
                    logInfo.get().getContractID().getContractNum());

            // And verify the remaining fields of the event
            final var eventData = logInfo.get().getData().toByteArray();
            final var eventArgs =
                    TupleType.parse("(address,address,uint256,uint256)").decode(eventData);

            final var senderInEvent = (Address) eventArgs.get(0);
            final var thisAddressInEvent = (Address) eventArgs.get(1);
            final var valueInEvent = (BigInteger) eventArgs.get(2);
            final var paramInEvent = (BigInteger) eventArgs.get(3);

            assertEquals(caller.evmAddress(), senderInEvent);
            assertEquals(delegatingEoa.evmAddress(), thisAddressInEvent);
            assertEquals(BigInteger.ZERO, valueInEvent);
            assertEquals(BigInteger.valueOf(1234), paramInEvent);

            // Verify that the value is stored correctly
            final AtomicReference<ContractFunctionResult> getValueResult = new AtomicReference<>();
            allRunFor(
                    spec,
                    HapiEthereumCall.explicitlyTo(delegatingEoa.evmAddressBytes(), 0)
                            .withExplicitParams(() -> Hex.toHexString(new Function("getValue()")
                                    .encodeCall(Tuple.EMPTY)
                                    .array()))
                            .payingWith(payer.name())
                            .signingWith(caller.keyName())
                            .exposingRawResultTo(getValueResult::set)
                            .hasKnownStatus(ResponseCodeEnum.SUCCESS));

            assertEquals(
                    BigInteger.valueOf(1234),
                    new BigInteger(getValueResult.get().getContractCallResult().toByteArray()));
        }));
    }

    @HapiTest
    final Stream<DynamicTest> testCodeDelegationInternalHtsTransfer() {
        return hapiTest(withOpContext((spec, opLog) -> {
            final var payer = createFundedEvmAccountWithKey(spec, ONE_HUNDRED_HBARS);
            final var caller = createFundedEvmAccountWithKey(spec, ONE_HUNDRED_HBARS);

            /* Create a delegation target contract and an EOA delegating to it. */
            final var delegationTargetContract = createEvmContract(spec, CODE_DELEGATION_CONTRACT);
            final var delegatingEoa = createFundedEvmAccountWithKey(spec, ONE_HUNDRED_HBARS);
            nativeSetCodeDelegation(spec, delegatingEoa, delegationTargetContract);

            final var token = "TOKEN_1";
            final AtomicReference<Address> tokenAddress = new AtomicReference<>();

            /* Create a token */
            allRunFor(
                    spec,
                    tokenCreate(token)
                            .initialSupply(1000)
                            .treasury(delegatingEoa.name())
                            .exposingAddressTo(tokenAddress::set));

            final var tokenReceiver = createEvmAccountWithKey(spec);
            allRunFor(spec, cryptoUpdate(tokenReceiver.name).maxAutomaticAssociations(100));

            final var innerTransferCallData = encodeHtsTransfer(tokenReceiver.evmAddress(), BigInteger.valueOf(200L));

            final var callData = new Function("executeCall(address,uint256,bytes)")
                    .encodeCall(Tuple.of(
                            tokenAddress.get() /* call target */,
                            BigInteger.valueOf(0L) /* hbar value to pass */,
                            innerTransferCallData /* call data */))
                    .array();

            /* Call the EOA */
            allRunFor(
                    spec,
                    /* Verify the initial balance of the delegating EOA */
                    getAccountBalance(delegatingEoa.name()).hasTokenBalance(token, 1000),

                    /* Trigger an HTS token transfer by sending a transaction from the owner account */
                    HapiEthereumCall.explicitlyTo(delegatingEoa.evmAddressBytes(), 0)
                            .withExplicitParams(() -> Hex.toHexString(callData))
                            .payingWith(payer.name())
                            .signingWith(delegatingEoa.keyName())
                            .hasKnownStatus(ResponseCodeEnum.SUCCESS),
                    getAccountBalance(delegatingEoa.name()).hasTokenBalance(token, 800),
                    getAccountBalance(tokenReceiver.name()).hasTokenBalance(token, 200L),

                    /* Trigger an HTS token transfer by sending a transaction from an unrelated account */
                    HapiEthereumCall.explicitlyTo(delegatingEoa.evmAddressBytes(), 0)
                            .withExplicitParams(() -> Hex.toHexString(callData))
                            .payingWith(payer.name())
                            .signingWith(caller.keyName())
                            .hasKnownStatus(ResponseCodeEnum.SUCCESS),
                    getAccountBalance(delegatingEoa.name()).hasTokenBalance(token, 600),
                    getAccountBalance(tokenReceiver.name()).hasTokenBalance(token, 400L));
        }));
    }

    @HapiTest
    final Stream<DynamicTest> testCodeDelegationInnerCall() {
        return hapiTest(withOpContext((spec, opLog) -> {
            final var payer = createFundedEvmAccountWithKey(spec, ONE_HUNDRED_HBARS);
            final var caller = createFundedEvmAccountWithKey(spec, ONE_HUNDRED_HBARS);

            /* Create a delegation target contract and an EOA delegating to it. */
            final var delegationTargetContract = createEvmContract(spec, CODE_DELEGATION_CONTRACT);
            final var delegatingEoa = createFundedEvmAccountWithKey(spec, ONE_HUNDRED_HBARS);
            nativeSetCodeDelegation(spec, delegatingEoa, delegationTargetContract);

            final AtomicReference<ContractLoginfo> logInfo = new AtomicReference<>();

            // We will call the testing contract directly (no delegation) and in its impl do an inner call to an EOA.
            // The delegation of an EOA should work (which happens to be the same target contract address) and run
            // its fallback function.
            final var callData = new Function("executeCall(address,uint256,bytes)")
                    .encodeCall(Tuple.of(
                            delegatingEoa.evmAddress() /* call target */,
                            BigInteger.valueOf(0L) /* hbar value to pass */,
                            Hex.decode("cafebabe") /* call data */))
                    .array();

            allRunFor(
                    spec,
                    HapiEthereumCall.explicitlyTo(delegationTargetContract.evmAddressBytes(), 0)
                            .withExplicitParams(() -> Hex.toHexString(callData))
                            .payingWith(payer.name())
                            .signingWith(caller.keyName())
                            .exposingEventDataTo(logInfo::set)
                            .hasKnownStatus(ResponseCodeEnum.SUCCESS));

            // Verify that the event originates from the EOA
            assertEquals(
                    delegatingEoa.accountId().getAccountNum(),
                    logInfo.get().getContractID().getContractNum());

            // And verify the remaining fields of the event
            final var eventData = logInfo.get().getData().toByteArray();
            final var eventArgs =
                    TupleType.parse("(address,address,uint256,bytes)").decode(eventData);

            final var senderInEvent = (Address) eventArgs.get(0);
            final var thisAddressInEvent = (Address) eventArgs.get(1);
            final var valueInEvent = (BigInteger) eventArgs.get(2);
            final var callDataInEvent = (byte[]) eventArgs.get(3);

            assertEquals(delegationTargetContract.address(), senderInEvent);
            assertEquals(delegatingEoa.evmAddress(), thisAddressInEvent);
            assertEquals(BigInteger.ZERO, valueInEvent);
            assertArrayEquals(Hex.decode("cafebabe"), callDataInEvent);
        }));
    }

    @HapiTest
    /// This scenario is equivalent to chaining delegations (since token account delegates to the HTS
    /// System Contract), so we expect the second delegation code to be treated literally and fail
    /// due to trying to execute an invalid op code.
    final Stream<DynamicTest> testDelegationToHtsTokenReverts() {
        return hapiTest(withOpContext((spec, opLog) -> {
            final var payer = createFundedEvmAccountWithKey(spec, ONE_HUNDRED_HBARS);
            final var caller = createFundedEvmAccountWithKey(spec, ONE_HUNDRED_HBARS);

            final var delegatingEoa = createFundedEvmAccountWithKey(spec, ONE_HUNDRED_HBARS);

            final var token = "TOKEN_2";
            final AtomicReference<Address> tokenAddress = new AtomicReference<>();

            /* Create a token */
            allRunFor(
                    spec,
                    tokenCreate(token)
                            .initialSupply(1000)
                            .treasury(delegatingEoa.name())
                            .exposingAddressTo(tokenAddress::set));

            final var tokenAddressPbj = ByteString.fromHex(
                    toChecksumAddress(tokenAddress.get().value()).replace("0x", ""));

            // Delegate to the HTS token
            nativeSetCodeDelegation(spec, delegatingEoa, tokenAddressPbj);

            // Let's encode a valid HTS call (token transfer) and verify that it doesn't trigger any action.
            final var wouldBeTokenReceiver = createEvmAccountWithKey(spec);
            allRunFor(spec, cryptoUpdate(wouldBeTokenReceiver.name).maxAutomaticAssociations(100));

            final var callData = encodeHtsTransfer(wouldBeTokenReceiver.evmAddress(), BigInteger.valueOf(200L));

            final var tinyBarsAmount = 40_500_000L;
            final AtomicReference<Long> delegatingEoaBalanceBefore = new AtomicReference<>();
            final AtomicReference<Long> delegatingEoaBalanceAfter = new AtomicReference<>();

            allRunFor(
                    spec,
                    getAccountBalance(delegatingEoa.name())
                            .exposingBalanceTo(delegatingEoaBalanceBefore::set)
                            .hasTokenBalance(token, 1000),
                    getAccountBalance(wouldBeTokenReceiver.name()).hasTokenBalance(token, 0L),
                    HapiEthereumCall.explicitlyTo(delegatingEoa.evmAddressBytes(), 0)
                            .withExplicitParams(() -> Hex.toHexString(callData))
                            .sending(tinyBarsAmount)
                            .payingWith(payer.name())
                            .signingWith(caller.keyName())
                            .hasKnownStatus(ResponseCodeEnum.CONTRACT_EXECUTION_EXCEPTION),
                    getAccountBalance(delegatingEoa.name())
                            .hasTokenBalance(token, 1000)
                            .exposingBalanceTo(delegatingEoaBalanceAfter::set),
                    getAccountBalance(wouldBeTokenReceiver.name()).hasTokenBalance(token, 0L));

            // EOA balance should be unchanged due to revert
            assertEquals(delegatingEoaBalanceAfter.get(), delegatingEoaBalanceBefore.get());
        }));
    }

    @HapiTest
    final Stream<DynamicTest> testDelegationToSystemContractsAndPrecompilesAllowsValueTransfer() {
        /*
         * For each test case we're going to:
         *  1. Execute a direct call to `targetAddress` using `validCallPayload` and verify that
         *     the output is correct (equal to `expectedActualCallResult`), which should act
         *     as a validation that our call data is correct.
         *  2. Then we submit the same call data in a call to an EOA that delegates to `targetAddress`
         *     and we expect that no execution happens (output data is empty), but value transfer
         *     should work.
         */
        record TestCase(String targetAddress, byte[] validCallData, byte[] expectedActualCallResult) {}

        final List<TestCase> testCases = List.of(
                new TestCase(
                        "0000000000000000000000000000000000000167",
                        new Function("isToken(address)")
                                .encodeCall(Tuple.singleton(
                                        Address.wrap(toChecksumAddress("0x0000000000000000000000000000000000001234"))))
                                .array(),
                        // SUCCESS status (0x16 == 22 decimal) followed by 32-zeros (boolean false)
                        Hex.decode("0000000000000000000000000000000000000000000000000000000000000016"
                                + "0000000000000000000000000000000000000000000000000000000000000000")),
                new TestCase(
                        "0000000000000000000000000000000000000002", // SHA256 precompile
                        Hex.decode("cafebabe"),
                        Hashing.sha256().hashBytes(Hex.decode("cafebabe")).asBytes()));

        return testCases.stream()
                .flatMap(testCase -> hapiTest(withOpContext((spec, opLog) -> {
                    final var payer = createFundedEvmAccountWithKey(spec, ONE_HUNDRED_HBARS);
                    final var caller = createFundedEvmAccountWithKey(spec, ONE_HUNDRED_HBARS);

                    final var targetAddressBytes = ByteString.fromHex(testCase.targetAddress);
                    final var delegatingEoa = createFundedEvmAccountWithKey(spec, ONE_HUNDRED_HBARS);
                    nativeSetCodeDelegation(spec, delegatingEoa, targetAddressBytes);

                    final AtomicReference<ContractFunctionResult> directCallResult = new AtomicReference<>();
                    final AtomicReference<ContractFunctionResult> delegationCallResult = new AtomicReference<>();

                    final AtomicReference<Long> callerBalanceBefore = new AtomicReference<>();
                    final AtomicReference<Long> delegatingEoaBalanceBefore = new AtomicReference<>();
                    final AtomicReference<Long> callerBalanceAfter = new AtomicReference<>();
                    final AtomicReference<Long> delegatingEoaBalanceAfter = new AtomicReference<>();

                    final var tinyBarsAmount = 40_500_000L;

                    allRunFor(
                            spec,
                            HapiEthereumCall.explicitlyTo(targetAddressBytes.toByteArray(), 0)
                                    .withExplicitParams(() -> Hex.toHexString(testCase.validCallData()))
                                    .payingWith(payer.name())
                                    .signingWith(caller.keyName())
                                    .hasKnownStatus(ResponseCodeEnum.SUCCESS)
                                    .exposingRawResultTo(directCallResult::set),
                            getAccountBalance(caller.name()).exposingBalanceTo(callerBalanceBefore::set),
                            getAccountBalance(delegatingEoa.name()).exposingBalanceTo(delegatingEoaBalanceBefore::set),
                            HapiEthereumCall.explicitlyTo(delegatingEoa.evmAddressBytes(), 0)
                                    .withExplicitParams(() -> Hex.toHexString(testCase.validCallData()))
                                    .sending(tinyBarsAmount)
                                    .payingWith(payer.name())
                                    .signingWith(caller.keyName())
                                    .hasKnownStatus(ResponseCodeEnum.SUCCESS)
                                    .exposingRawResultTo(delegationCallResult::set),
                            getAccountBalance(caller.name()).exposingBalanceTo(callerBalanceAfter::set),
                            getAccountBalance(delegatingEoa.name()).exposingBalanceTo(delegatingEoaBalanceAfter::set));

                    // We expect the result from the actual call to match expectations
                    assertArrayEquals(
                            testCase.expectedActualCallResult(),
                            directCallResult.get().getContractCallResult().toByteArray());
                    // But we don't expect any output when using code delegation
                    assertEquals(
                            0,
                            delegationCallResult.get().getContractCallResult().size());

                    // Verify that value transfer succeeded. Using inequality for the caller as they additionally pay
                    // the fees.
                    assertTrue(callerBalanceBefore.get() >= callerBalanceAfter.get() + tinyBarsAmount);
                    assertEquals(delegatingEoaBalanceAfter.get(), delegatingEoaBalanceBefore.get() + tinyBarsAmount);
                })));
    }

    @HapiTest
    final Stream<DynamicTest> testCodeDelegationDuringHookExecution() {
        return hapiTest(withOpContext((spec, opLog) -> {
            final var payer = createFundedEvmAccountWithKey(spec, ONE_HUNDRED_HBARS);
            final var caller = createFundedEvmAccountWithKey(spec, ONE_HUNDRED_HBARS);

            final var delegationTargetContract = createEvmContract(spec, CODE_DELEGATION_CONTRACT);
            final var delegatingEoa = createFundedEvmAccountWithKey(spec, ONE_HUNDRED_HBARS);
            nativeSetCodeDelegation(spec, delegatingEoa, delegationTargetContract);

            final var hookId = 1L;

            final var owner = createFundedEvmAccountWithKey(spec, ONE_HBAR);
            final var receiver = createFundedEvmAccountWithKey(spec, ONE_HBAR);

            allRunFor(
                    spec,
                    cryptoUpdate(owner.name()).withHook(accountAllowanceHook(hookId, CODE_DELEGATION_CONTRACT)),
                    cryptoUpdate(receiver.name()).maxAutomaticAssociations(10),
                    cryptoTransfer((unused, builder) -> {
                                final var registry = spec.registry();
                                final var hookData = TupleType.parse("(address,bytes)")
                                        .encode(Tuple.of(delegatingEoa.evmAddress, Hex.decode("cafebabe")));
                                final var hookCall = HookCall.newBuilder()
                                        .hookId(hookId)
                                        .evmHookCall(EvmHookCall.newBuilder()
                                                .gasLimit(5000_000L)
                                                .data(Bytes.wrap(hookData.array())))
                                        .build();
                                builder.setTransfers(TransferList.newBuilder()
                                        .addAccountAmounts(aaWithPreHook(owner.accountId(), -20, hookCall))
                                        .addAccountAmounts(aaWith(registry.getAccountID(receiver.name()), +20))
                                        .build());
                            })
                            .payingWith(payer.name())
                            .signedBy(owner.name(), receiver.name(), payer.name())
                            .via("codeDelegationInHook"),
                    // There should be a successful child record for the hook call
                    getTxnRecord("codeDelegationInHook")
                            .andAllChildRecords()
                            .logged()
                            .exposingAllTo(txRecords -> {
                                final var callTxRecord = txRecords.stream()
                                        .filter(TransactionRecord::hasContractCallResult)
                                        .findFirst();
                                assertTrue(callTxRecord.isPresent());
                                final var logs = callTxRecord
                                        .get()
                                        .getContractCallResult()
                                        .getLogInfoList();
                                assertEquals(2, logs.size());

                                // An event emitted in a hook method
                                final var hookExecutedLogRaw =
                                        logs.get(0).getData().toByteArray();
                                final var hookExecutedLogArgs =
                                        TupleType.parse("(address,address)").decode(hookExecutedLogRaw);
                                final var senderInHookEvent = (Address) hookExecutedLogArgs.get(0);
                                final var thisAddressInHookEvent = (Address) hookExecutedLogArgs.get(1);
                                assertEquals(payer.evmAddress(), senderInHookEvent);
                                assertEquals(HTS_HOOKS_CONTRACT_ADDRESS, thisAddressInHookEvent);

                                // An event emitted in an EOA call (via code delegation)
                                final var eoaFallbackLogRaw =
                                        logs.get(1).getData().toByteArray();
                                final var eoaFallbackLogArgs = TupleType.parse("(address,address,uint256,bytes)")
                                        .decode(eoaFallbackLogRaw);
                                final var senderInEvent = (Address) eoaFallbackLogArgs.get(0);
                                final var thisAddressInEvent = (Address) eoaFallbackLogArgs.get(1);
                                final var valueInEvent = (BigInteger) eoaFallbackLogArgs.get(2);
                                final var callDataInEvent = (byte[]) eoaFallbackLogArgs.get(3);

                                assertEquals(owner.evmAddress(), senderInEvent);
                                assertEquals(delegatingEoa.evmAddress(), thisAddressInEvent);
                                assertEquals(BigInteger.ZERO, valueInEvent);
                                assertArrayEquals(Hex.decode("cafebabe"), callDataInEvent);
                            }));
        }));
    }

    /// Tests a corner case where the delegation target address is set to 0x16d (hook address)
    /// and a call is made to that EOA inside a hook.
    /// This should resolve the target address literally, and result in a no-op, because
    /// no entity actually has address 0x16d.
    @HapiTest
    final Stream<DynamicTest> testCodeDelegationToHookAddressDuringHookExecution() {
        return hapiTest(withOpContext((spec, opLog) -> {
            final var payer = createFundedEvmAccountWithKey(spec, ONE_HUNDRED_HBARS);
            final var caller = createFundedEvmAccountWithKey(spec, ONE_HUNDRED_HBARS);

            final var delegatingEoa = createFundedEvmAccountWithKey(spec, ONE_HUNDRED_HBARS);
            final var htsAddr =
                    ByteString.fromHex(HTS_HOOKS_CONTRACT_ADDRESS.toString().replace("0x", ""));
            nativeSetCodeDelegation(spec, delegatingEoa, htsAddr);

            final var hookId = 1L;

            final var owner = createFundedEvmAccountWithKey(spec, ONE_HBAR);
            final var receiver = createFundedEvmAccountWithKey(spec, ONE_HBAR);

            createEvmContract(spec, CODE_DELEGATION_CONTRACT);

            allRunFor(
                    spec,
                    cryptoUpdate(owner.name()).withHook(accountAllowanceHook(hookId, CODE_DELEGATION_CONTRACT)),
                    cryptoUpdate(receiver.name()).maxAutomaticAssociations(10),
                    cryptoTransfer((unused, builder) -> {
                                final var registry = spec.registry();
                                final var hookData = TupleType.parse("(address,bytes)")
                                        .encode(Tuple.of(delegatingEoa.evmAddress, Hex.decode("cafebabe")));
                                final var hookCall = HookCall.newBuilder()
                                        .hookId(hookId)
                                        .evmHookCall(EvmHookCall.newBuilder()
                                                .gasLimit(5000_000L)
                                                .data(Bytes.wrap(hookData.array())))
                                        .build();
                                builder.setTransfers(TransferList.newBuilder()
                                        .addAccountAmounts(aaWithPreHook(owner.accountId(), -20, hookCall))
                                        .addAccountAmounts(aaWith(registry.getAccountID(receiver.name()), +20))
                                        .build());
                            })
                            .payingWith(payer.name())
                            .signedBy(owner.name(), receiver.name(), payer.name())
                            .via("codeDelegationToHookAddrInHook"),
                    // There should be a successful child record for the hook call
                    getTxnRecord("codeDelegationToHookAddrInHook")
                            .andAllChildRecords()
                            .logged()
                            .exposingAllTo(txRecords -> {
                                final var callTxRecord = txRecords.stream()
                                        .filter(TransactionRecord::hasContractCallResult)
                                        .findFirst();
                                assertTrue(callTxRecord.isPresent());
                                final var logs = callTxRecord
                                        .get()
                                        .getContractCallResult()
                                        .getLogInfoList();
                                assertEquals(1, logs.size());

                                // An event emitted in a hook method
                                final var hookExecutedLogRaw =
                                        logs.get(0).getData().toByteArray();
                                final var hookExecutedLogArgs =
                                        TupleType.parse("(address,address)").decode(hookExecutedLogRaw);
                                final var senderInHookEvent = (Address) hookExecutedLogArgs.get(0);
                                final var thisAddressInHookEvent = (Address) hookExecutedLogArgs.get(1);
                                assertEquals(payer.evmAddress(), senderInHookEvent);
                                assertEquals(HTS_HOOKS_CONTRACT_ADDRESS, thisAddressInHookEvent);
                            }));
        }));
    }

    @HapiTest
    final Stream<DynamicTest> testCrossAPIDelegationNativeToType4() {
        return hapiTest(withOpContext((spec, opLog) -> {
            // Create delegation target and account
            final var delegationTargetContract = createEvmContract(spec, CODE_DELEGATION_CONTRACT);
            final var delegatingEoa = createFundedEvmAccountWithKey(spec, ONE_HUNDRED_HBARS);

            nativeSetCodeDelegation(spec, delegatingEoa, delegationTargetContract);
            allRunFor(
                    spec,
                    cryptoCreate(RELAYER).balance(ONE_MILLION_HBARS),
                    // verify delegation was set
                    getAccountInfo(delegatingEoa.name()).hasDelegationAddress(delegationTargetContract.address()),

                    // Now proceed to clear delegation with type 4 eth transaction
                    sourcing(() -> ethereumCryptoTransfer(RELAYER, 1L)
                            .signingWith(delegatingEoa.keyName)
                            .payingWith(RELAYER)
                            .type(EthTransactionType.EIP7702)
                            .gasLimit(50_000L)
                            .addSenderCodeDelegationWithSpecNonce(ZERO_ADDRESS)),
                    // Verify that the delegation was cleared
                    getAccountInfo(delegatingEoa.name()).hasNoDelegation());
        }));
    }

    @HapiTest
    final Stream<DynamicTest> testCrossAPIDelegationType4ToNative() {
        return hapiTest(withOpContext(((spec, assertLog) -> {
            // Create delegation target and account
            final var delegationTargetContract = createEvmContract(spec, CODE_DELEGATION_CONTRACT);
            final var delegatingEoa = createFundedEvmAccountWithKey(spec, ONE_HUNDRED_HBARS);

            final AtomicReference<Long> preEthereumNonce = new AtomicReference<>();

            allRunFor(
                    spec,
                    cryptoCreate(RELAYER).balance(ONE_MILLION_HBARS),
                    getAccountInfo(delegatingEoa.name()).exposingEthereumNonceTo(preEthereumNonce::set),

                    // ethereum transaction with code delegation
                    sourcing(() -> ethereumCryptoTransfer(RELAYER, 1L)
                            .signingWith(delegatingEoa.keyName())
                            .payingWith(RELAYER)
                            .type(EthTransactionType.EIP7702)
                            .addSenderCodeDelegationWithSpecNonce(delegationTargetContract.address())
                            .gasLimit(2_000_000L)),

                    // Verify account has delegation set
                    getAccountInfo(delegatingEoa.name())
                            .hasDelegationAddress(delegationTargetContract.address())
                            .exposingEthereumNonceTo(newNonce -> {
                                // We're expecting the nonce to increase by 2.
                                // Once for sending the transaction and once for the signed authorization.
                                assertEquals(preEthereumNonce.get() + 2, newNonce);
                            }),

                    // Clear delegation natively
                    cryptoUpdate(delegatingEoa.name()).delegationAddress(ByteString.empty()),

                    // Verify that the delegation was cleared from the account
                    getAccountInfo(delegatingEoa.name()).hasNoDelegation());
        })));
    }

    private record EvmAccount(
            String keyName, String name, AccountID accountId, Address evmAddress, Address longZeroEvmAddress) {
        String evmAddressHex() {
            return toChecksumAddress(evmAddress.value()).replace("0x", "");
        }

        byte[] evmAddressBytes() {
            return CommonUtils.unhex(evmAddressHex());
        }
    }

    private record EvmContract(String name, Address address) {
        String evmAddressHex() {
            return toChecksumAddress(address.value()).replace("0x", "");
        }

        ByteString evmAddressPbjBytes() {
            return ByteString.fromHex(evmAddressHex());
        }

        byte[] evmAddressBytes() {
            return CommonUtils.unhex(evmAddressHex());
        }
    }

    private EvmAccount createEvmAccountWithKey(HapiSpec spec) {
        final var id = accountIdCounter.getAndIncrement();
        final var name = "ACCOUNT_" + id;
        final var keyName = "ACCOUNT_KEY_" + id;
        final AtomicReference<Address> keyAliasEvmAddress = new AtomicReference<>();
        final AtomicReference<AccountID> accountId = new AtomicReference<>();
        final AtomicReference<Address> longZeroEvmAddress = new AtomicReference<>();
        allRunFor(spec, newKeyNamed(keyName).shape(SECP_256K1_SHAPE));
        final var key = spec.registry().getKey(keyName);
        final var keyEvmAddress = ByteString.copyFrom(
                recoverAddressFromPubKey(key.getECDSASecp256K1().toByteArray()));
        allRunFor(
                spec,
                cryptoCreate(name)
                        .key(keyName)
                        .alias(keyEvmAddress)
                        .exposingCreatedIdTo(accountId::set)
                        .exposingEvmAddressTo(longZeroEvmAddress::set),
                useAddressOfKey(keyName, keyAliasEvmAddress::set));
        return new EvmAccount(keyName, name, accountId.get(), keyAliasEvmAddress.get(), longZeroEvmAddress.get());
    }

    private EvmAccount createFundedEvmAccountWithKey(HapiSpec spec, long hbarAmount) {
        final var account = createEvmAccountWithKey(spec);
        allRunFor(spec, cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, account.name, hbarAmount)));
        return account;
    }

    private EvmContract createEvmContract(HapiSpec spec, String name) {
        final AtomicReference<Address> evmAddress = new AtomicReference<>();
        allRunFor(
                spec,
                uploadInitCode(CODE_DELEGATION_CONTRACT),
                contractCreate(CODE_DELEGATION_CONTRACT)
                        .exposingAddressTo(evmAddress::set)
                        .gas(2_000_000L));
        return new EvmContract(name, evmAddress.get());
    }

    private void nativeSetCodeDelegation(HapiSpec spec, EvmAccount account, EvmContract target) {
        nativeSetCodeDelegation(spec, account, target.evmAddressPbjBytes());
    }

    private void nativeSetCodeDelegation(HapiSpec spec, EvmAccount account, ByteString targetAddress) {
        allRunFor(
                spec,
                cryptoUpdate(account.name()).delegationAddress(targetAddress),
                getAccountInfo(account.name()).has(accountWith().delegationAddress(targetAddress)));
    }

    private static byte[] encodeHtsTransfer(Address receiver, BigInteger amount) {
        return new Function("transfer(address,uint256)")
                .encodeCall(Tuple.of(receiver, amount))
                .array();
    }
}
