// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.hips.hip1340;

import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.esaulpaugh.headlong.abi.Function;
import com.esaulpaugh.headlong.abi.Tuple;
import com.google.protobuf.ByteString;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.spec.transactions.contract.HapiEthereumCall;
import com.hederahashgraph.api.proto.java.ContractFunctionResult;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import java.math.BigInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.bouncycastle.util.encoders.Hex;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(SMART_CONTRACT)
@DisplayName("Code Delegation Chaining Tests")
public class CodeDelegationChainingTest extends CodeDelegationTestBase {

    @HapiTest
    @DisplayName("Delegation chain through two EOAs fails with banned opcode")
    final Stream<DynamicTest> testDelegationChainThroughTwoEoasResultsInFailure() {
        return hapiTest(withOpContext((spec, opLog) -> {
            final var payer = createFundedEvmAccountWithKey(spec, ONE_HUNDRED_HBARS);
            final var caller = createFundedEvmAccountWithKey(spec, ONE_HUNDRED_HBARS);

            final var contract = deployEvmContract(spec, CODE_DELEGATION_CONTRACT);

            // EOA_B delegates to the contract
            final var eoaB = createFundedEvmAccountWithKey(spec, ONE_HUNDRED_HBARS);
            nativeSetCodeDelegation(spec, eoaB, contract);

            // EOA_A delegates to EOA_B, creating a chain: A -> B -> Contract
            final var eoaA = createFundedEvmAccountWithKey(spec, ONE_HUNDRED_HBARS);
            nativeSetCodeDelegation(spec, eoaA, ByteString.fromHex(eoaB.evmAddressHex()));

            // Calling EOA_A resolves to EOA_B whose code is a delegation indicator (0xef0100...).
            // The indicator is interpreted literally -> 0xef is a banned opcode -> execution fails.
            allRunFor(
                    spec,
                    HapiEthereumCall.explicitlyTo(eoaA.evmAddressBytes(), 0)
                            .withExplicitParams(() -> "cafebabe")
                            .payingWith(payer.name())
                            .signingWith(caller.keyName())
                            .gasLimit(200_000L)
                            .hasKnownStatus(ResponseCodeEnum.CONTRACT_EXECUTION_EXCEPTION));
        }));
    }

    @HapiTest
    @DisplayName("Delegation loop between two EOAs fails with banned opcode")
    final Stream<DynamicTest> testDelegationLoopResultsInFailure() {
        return hapiTest(withOpContext((spec, opLog) -> {
            final var payer = createFundedEvmAccountWithKey(spec, ONE_HUNDRED_HBARS);
            final var caller = createFundedEvmAccountWithKey(spec, ONE_HUNDRED_HBARS);

            final var eoaA = createFundedEvmAccountWithKey(spec, ONE_HUNDRED_HBARS);
            final var eoaB = createFundedEvmAccountWithKey(spec, ONE_HUNDRED_HBARS);

            // Circular delegation: A -> B, B -> A
            nativeSetCodeDelegation(spec, eoaA, ByteString.fromHex(eoaB.evmAddressHex()));
            nativeSetCodeDelegation(spec, eoaB, ByteString.fromHex(eoaA.evmAddressHex()));

            // Calling EOA_A: resolves to EOA_B, whose code is 0xef0100||A_address -> banned opcode
            allRunFor(
                    spec,
                    HapiEthereumCall.explicitlyTo(eoaA.evmAddressBytes(), 0)
                            .withExplicitParams(() -> "cafebabe")
                            .payingWith(payer.name())
                            .signingWith(caller.keyName())
                            .gasLimit(200_000L)
                            .hasKnownStatus(ResponseCodeEnum.CONTRACT_EXECUTION_EXCEPTION));

            // Symmetrically, calling EOA_B should also fail
            allRunFor(
                    spec,
                    HapiEthereumCall.explicitlyTo(eoaB.evmAddressBytes(), 0)
                            .withExplicitParams(() -> "cafebabe")
                            .payingWith(payer.name())
                            .signingWith(caller.keyName())
                            .gasLimit(200_000L)
                            .hasKnownStatus(ResponseCodeEnum.CONTRACT_EXECUTION_EXCEPTION));
        }));
    }

    @HapiTest
    @DisplayName("Self-delegation fails with banned opcode")
    final Stream<DynamicTest> testSelfDelegationResultsInFailure() {
        return hapiTest(withOpContext((spec, opLog) -> {
            final var payer = createFundedEvmAccountWithKey(spec, ONE_HUNDRED_HBARS);
            final var caller = createFundedEvmAccountWithKey(spec, ONE_HUNDRED_HBARS);

            final var eoa = createFundedEvmAccountWithKey(spec, ONE_HUNDRED_HBARS);

            // Self-delegation: EOA -> itself
            nativeSetCodeDelegation(spec, eoa, ByteString.fromHex(eoa.evmAddressHex()));

            // Calling the EOA resolves to itself, code is 0xef0100||own_address -> banned opcode
            allRunFor(
                    spec,
                    HapiEthereumCall.explicitlyTo(eoa.evmAddressBytes(), 0)
                            .withExplicitParams(() -> "cafebabe")
                            .payingWith(payer.name())
                            .signingWith(caller.keyName())
                            .gasLimit(200_000L)
                            .hasKnownStatus(ResponseCodeEnum.CONTRACT_EXECUTION_EXCEPTION));
        }));
    }

    @HapiTest
    @DisplayName("Delegation to non-existent address is a no-op with value transfer")
    final Stream<DynamicTest> testDelegationToNonExistentAddressIsNoOp() {
        return hapiTest(withOpContext((spec, opLog) -> {
            final var payer = createFundedEvmAccountWithKey(spec, ONE_HUNDRED_HBARS);
            final var caller = createFundedEvmAccountWithKey(spec, ONE_HUNDRED_HBARS);

            final var eoa = createFundedEvmAccountWithKey(spec, ONE_HUNDRED_HBARS);

            // Delegate to a non-existent address
            final var nonExistentAddress = ByteString.fromHex("abcdef0123456789abcdef0123456789abcdef01");
            nativeSetCodeDelegation(spec, eoa, nonExistentAddress);

            final var tinyBarsAmount = 40_500_000L;
            final AtomicReference<Long> eoaBalanceBefore = new AtomicReference<>();
            final AtomicReference<Long> eoaBalanceAfter = new AtomicReference<>();
            final AtomicReference<ContractFunctionResult> callResult = new AtomicReference<>();

            allRunFor(
                    spec,
                    getAccountBalance(eoa.name()).exposingBalanceTo(eoaBalanceBefore::set),
                    HapiEthereumCall.explicitlyTo(eoa.evmAddressBytes(), 0)
                            .withExplicitParams(() -> "cafebabe")
                            .sending(tinyBarsAmount)
                            .payingWith(payer.name())
                            .signingWith(caller.keyName())
                            .gasLimit(200_000L)
                            .exposingRawResultTo(callResult::set)
                            .hasKnownStatus(ResponseCodeEnum.SUCCESS)
                            .via(""),
                    getAccountBalance(eoa.name()).exposingBalanceTo(eoaBalanceAfter::set));

            // No code executed -> empty output
            assertEquals(0, callResult.get().getContractCallResult().size());
            // Value transfer should have succeeded
            assertEquals(eoaBalanceBefore.get() + tinyBarsAmount, eoaBalanceAfter.get());
        }));
    }

    @HapiTest
    @DisplayName("Delegation chain reached via inner CALL reverts the inner call")
    final Stream<DynamicTest> testDelegationChainViaInnerCallReverts() {
        return hapiTest(withOpContext((spec, opLog) -> {
            final var payer = createFundedEvmAccountWithKey(spec, ONE_HUNDRED_HBARS);
            final var caller = createFundedEvmAccountWithKey(spec, ONE_HUNDRED_HBARS);

            final var contract = deployEvmContract(spec, CODE_DELEGATION_CONTRACT);

            // EOA_B delegates to the contract
            final var eoaB = createFundedEvmAccountWithKey(spec, ONE_HUNDRED_HBARS);
            nativeSetCodeDelegation(spec, eoaB, contract);

            // EOA_A delegates to EOA_B, creating a chain: A -> B -> Contract
            final var eoaA = createFundedEvmAccountWithKey(spec, ONE_HUNDRED_HBARS);
            nativeSetCodeDelegation(spec, eoaA, ByteString.fromHex(eoaB.evmAddressHex()));

            // Use the contract's executeCall to make an inner call to EOA_A.
            // The inner call fails due to the delegation chain (banned opcode),
            // and executeCall propagates the revert.
            final var innerCallData = new Function("executeCall(address,uint256,bytes)")
                    .encodeCall(Tuple.of(eoaA.evmAddress(), BigInteger.ZERO, Hex.decode("cafebabe")))
                    .array();

            allRunFor(
                    spec,
                    HapiEthereumCall.explicitlyTo(contract.evmAddressBytes(), 0)
                            .withExplicitParams(() -> Hex.toHexString(innerCallData))
                            .payingWith(payer.name())
                            .signingWith(caller.keyName())
                            .gasLimit(200_000L)
                            .hasKnownStatus(ResponseCodeEnum.CONTRACT_REVERT_EXECUTED));
        }));
    }
}
