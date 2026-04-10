// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.hips.hip1340;

import static com.hedera.node.app.service.contract.impl.utils.ConstantUtils.ZERO_ADDRESS;
import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.anyResult;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAliasedAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.ethereumCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.RELAYER;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.esaulpaugh.headlong.abi.Address;
import com.esaulpaugh.headlong.abi.Function;
import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.node.app.hapi.utils.ethereum.EthTxData.EthTransactionType;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.HapiPropertySource;
import com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts;
import com.hedera.services.bdd.spec.transactions.contract.HapiEthereumCall;
import com.hedera.services.bdd.spec.transactions.token.TokenMovement;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.bouncycastle.util.encoders.Hex;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(SMART_CONTRACT)
@DisplayName("Code Delegation Via Ethereum Type 4 Transaction Tests")
@HapiTestLifecycle
public class CodeDelegationType4TransactionTest extends CodeDelegationTestBase {

    private static final AtomicReference<Address> DELEGATION_TARGET = new AtomicReference<>();

    @BeforeAll
    public static void setup(@NonNull TestLifecycle lifecycle) {
        lifecycle.doAdhoc(
                uploadInitCode(CODE_DELEGATION_CONTRACT),
                contractCreate(CODE_DELEGATION_CONTRACT).exposingAddressTo(DELEGATION_TARGET::set),
                cryptoCreate(RELAYER).balance(ONE_MILLION_HBARS));
    }

    @HapiTest
    final Stream<DynamicTest> testCodeDelegationSetViaEthCall() {

        final var delegatedFunctionSelector = Hex.toHexString(
                new Function("getValue()").encodeCall(Tuple.EMPTY).array());

        return hapiTest(withOpContext((spec, opLog) -> {
            deployEvmContract(spec, CONTRACT);
            createSecp256k1Keys(spec, DELEGATING_ACCOUNT);
            allRunFor(
                    spec,
                    cryptoTransfer(TokenMovement.movingHbar(ONE_HUNDRED_HBARS).between(GENESIS, DELEGATING_ACCOUNT))
                            .via(CREATION),
                    getTxnRecord(CREATION).exposingCreationsTo(creations -> {
                        final var createdId = HapiPropertySource.asAccount(creations.getFirst());
                        spec.registry().saveAccountId(DELEGATING_ACCOUNT_ID, createdId);
                    }),
                    sourcing(() -> ethereumCall(CONTRACT, "create")
                            .signingWith(DELEGATING_ACCOUNT)
                            .payingWith(RELAYER)
                            .type(EthTransactionType.EIP7702)
                            .addSenderCodeDelegationWithSpecNonce(DELEGATION_TARGET.get())
                            .gasLimit(2_000_000L)
                            .via(DELEGATION_SET)));
            verifyDelegationSet(spec, DELEGATION_TARGET.get(), DELEGATING_ACCOUNT);
            allRunFor(
                    spec,
                    sourcing(() -> new HapiEthereumCall(DELEGATING_ACCOUNT_ID, 0L)
                            .withExplicitParams(() -> delegatedFunctionSelector)
                            .payingWith(RELAYER)
                            .signingWith(DELEGATING_ACCOUNT)
                            .hasKnownStatus(ResponseCodeEnum.SUCCESS)
                            .via(DELEGATION_CALL)),
                    getTxnRecord(DELEGATION_SET)
                            .andAllChildRecords()
                            .hasChildRecords(
                                    recordWith()
                                            .targetAccountId(DELEGATING_ACCOUNT_ID)
                                            .status(ResponseCodeEnum.SUCCESS),
                                    recordWith()
                                            .contractCreateResult(ContractFnResultAsserts.anyResult())
                                            .status(ResponseCodeEnum.SUCCESS))
                            .logged(),
                    getTxnRecord(DELEGATION_CALL).andAllChildRecords().logged(),
                    sourcing(() -> ethereumCall(CONTRACT, "getAddress")
                            .signingWith(DELEGATING_ACCOUNT)
                            .payingWith(RELAYER)
                            .type(EthTransactionType.EIP7702)
                            .addSenderCodeDelegationWithSpecNonce(ZERO_ADDRESS)
                            .gasLimit(50_000L)
                            .via(DELEGATION_RESET)));
            verifyDelegationCleared(spec, DELEGATING_ACCOUNT);
            allRunFor(spec, getTxnRecord(DELEGATION_RESET).andAllChildRecords().logged());
        }));
    }

    @HapiTest
    final Stream<DynamicTest> testMultipleCodeDelegationsSetViaEthCall() {
        final var delegationTargetAddress = DELEGATION_TARGET.get();
        return hapiTest(withOpContext((spec, opLog) -> {
            deployEvmContract(spec, CONTRACT);
            createSecp256k1Keys(
                    spec, DELEGATING_ACCOUNT, DELEGATING_ACCOUNT_1, DELEGATING_ACCOUNT_2, DELEGATING_ACCOUNT_3);
            allRunFor(
                    spec,
                    cryptoTransfer(TokenMovement.movingHbar(ONE_HUNDRED_HBARS)
                            .distributing(GENESIS, DELEGATING_ACCOUNT, DELEGATING_ACCOUNT_1)),
                    cryptoTransfer(TokenMovement.movingHbar(ONE_HUNDRED_HBARS)
                            .distributing(GENESIS, DELEGATING_ACCOUNT_2, DELEGATING_ACCOUNT_3)),
                    sourcing(() -> ethereumCall(CONTRACT, "create")
                            .signingWith(DELEGATING_ACCOUNT)
                            .payingWith(RELAYER)
                            .type(EthTransactionType.EIP7702)
                            .addSenderCodeDelegationWithSpecNonce(delegationTargetAddress)
                            .addCodeDelegationWithSpecNonce(delegationTargetAddress, DELEGATING_ACCOUNT_1)
                            .addCodeDelegationWithSpecNonce(delegationTargetAddress, DELEGATING_ACCOUNT_2)
                            .addCodeDelegationWithSpecNonce(delegationTargetAddress, DELEGATING_ACCOUNT_3)
                            .gasLimit(2_000_000L)
                            .via(DELEGATION_SET)));
            verifyDelegationSet(
                    spec,
                    delegationTargetAddress,
                    DELEGATING_ACCOUNT,
                    DELEGATING_ACCOUNT_1,
                    DELEGATING_ACCOUNT_2,
                    DELEGATING_ACCOUNT_3);
            allRunFor(
                    spec,
                    getTxnRecord(DELEGATION_SET)
                            .andAllChildRecords()
                            .hasChildRecordCount(5)
                            .logged(),
                    sourcing(() -> ethereumCall(CONTRACT, "getAddress")
                            .signingWith(DELEGATING_ACCOUNT)
                            .payingWith(RELAYER)
                            .type(EthTransactionType.EIP7702)
                            .addSenderCodeDelegationWithSpecNonce(ZERO_ADDRESS)
                            .addCodeDelegationWithSpecNonce(ZERO_ADDRESS, DELEGATING_ACCOUNT_1)
                            .addCodeDelegationWithSpecNonce(ZERO_ADDRESS, DELEGATING_ACCOUNT_2)
                            .addCodeDelegationWithSpecNonce(ZERO_ADDRESS, DELEGATING_ACCOUNT_3)
                            .gasLimit(200_000L)
                            .via(DELEGATION_RESET)));
            verifyDelegationCleared(
                    spec, DELEGATING_ACCOUNT, DELEGATING_ACCOUNT_1, DELEGATING_ACCOUNT_2, DELEGATING_ACCOUNT_3);
            allRunFor(spec, getTxnRecord(DELEGATION_RESET).andAllChildRecords().logged());
        }));
    }

    @HapiTest
    final Stream<DynamicTest> testDelegatingToKey() {
        return hapiTest(withOpContext((spec, opLog) -> {
            deployEvmContract(spec, CONTRACT);
            createPayerAccountWithAlias(spec);
            createSecp256k1Keys(spec, DELEGATING_ACCOUNT);
            allRunFor(
                    spec,
                    sourcing(() -> ethereumCall(CONTRACT, "create")
                            .signingWith(PAYER_KEY)
                            .payingWith(RELAYER)
                            .type(EthTransactionType.EIP7702)
                            .addCodeDelegationWithSpecNonce(DELEGATION_TARGET.get(), DELEGATING_ACCOUNT)
                            .gasLimit(2_000_000L)
                            .via(DELEGATION_SET)
                            .exposingGasTo((s, gas) -> {
                                final var expectedGas = 21_000L /* intrinsic gas base fee */
                                        + 64 /* payload cost */
                                        + 25_000L /* code delegation fee with new account creation */
                                        + 108_893L /*call costs in gas*/
                                        + 554_517L /* auto creation hapi fee*/
                                        + 32_337L /* mysterious new fee; TODO(Pectra) figure this out */;
                                assertEquals(expectedGas, gas);
                            })),
                    getAliasedAccountInfo(DELEGATING_ACCOUNT)
                            .hasDelegationAddress(DELEGATION_TARGET.get())
                            .isNotHollow()
                            .exposingIdTo(id -> spec.registry().saveAccountId(DELEGATING_ACCOUNT_ID, id)),
                    getTxnRecord(DELEGATION_SET)
                            .hasChildRecords(
                                    recordWith()
                                            .status(ResponseCodeEnum.SUCCESS)
                                            .targetAccountId(PAYER),
                                    recordWith()
                                            .targetAccountId(DELEGATING_ACCOUNT_ID)
                                            .status(ResponseCodeEnum.SUCCESS),
                                    recordWith()
                                            .contractCreateResult(anyResult())
                                            .status(ResponseCodeEnum.SUCCESS))
                            .logged());
        }));
    }

    @HapiTest
    final Stream<DynamicTest> testDelegationSetToHollowAccountWithRevertingCall() {
        return hapiTest(withOpContext((spec, opLog) -> {
            deployEvmContract(spec, REVERTING_CONTRACT);
            createPayerAccountWithAlias(spec);
            createSecp256k1Keys(spec, DELEGATING_ACCOUNT);
            allRunFor(spec, sourcing(() -> ethereumCall(REVERTING_CONTRACT, "revertWithRevertReason")
                    .signingWith(PAYER_KEY)
                    .payingWith(RELAYER)
                    .type(EthTransactionType.EIP7702)
                    .addCodeDelegationWithSpecNonce(DELEGATION_TARGET.get(), DELEGATING_ACCOUNT)
                    .gasLimit(2_000_000L)
                    .hasKnownStatus(ResponseCodeEnum.CONTRACT_REVERT_EXECUTED)
                    .via(DELEGATION_SET)
                    .exposingGasTo((s, gas) -> {
                        final var expectedGas = 21_000L /* intrinsic gas base fee */
                                + 64 /* payload cost */
                                + 25_000L /* code delegation fee with new account creation */
                                + 408 /*call costs in gas*/
                                + 554_517L /* auto creation hapi fee*/
                                + 32_337L /* mysterious new fee; TODO(Pectra) figure this out */;
                        assertEquals(expectedGas, gas);
                    })));
            allRunFor(
                    spec,
                    getAliasedAccountInfo(DELEGATING_ACCOUNT)
                            .hasDelegationAddress(DELEGATION_TARGET.get())
                            .isNotHollow()
                            .exposingIdTo(id -> spec.registry().saveAccountId(DELEGATING_ACCOUNT_ID, id)),
                    getTxnRecord(DELEGATION_SET)
                            .andAllChildRecords()
                            .logged()
                            .hasChildRecords(
                                    recordWith()
                                            .status(ResponseCodeEnum.SUCCESS)
                                            .targetAccountId(PAYER),
                                    // TODO(Pectra): something is off; figure out why we now have two child records for
                                    // payer?
                                    recordWith()
                                            .status(ResponseCodeEnum.SUCCESS)
                                            .targetAccountId(PAYER),
                                    recordWith()
                                            .targetAccountId(DELEGATING_ACCOUNT_ID)
                                            .status(ResponseCodeEnum.SUCCESS))
                            .logged());
        }));
    }

    @HapiTest
    final Stream<DynamicTest> testDelegationSetToMultipleHollowAccountsWithRevertingCall() {
        return hapiTest(withOpContext((spec, opLog) -> {
            deployEvmContract(spec, REVERTING_CONTRACT);
            createPayerAccountWithAlias(spec);
            createSecp256k1Keys(spec, DELEGATING_ACCOUNT_1, DELEGATING_ACCOUNT_2, DELEGATING_ACCOUNT_3);
            allRunFor(spec, sourcing(() -> ethereumCall(REVERTING_CONTRACT, "revertWithRevertReason")
                    .signingWith(PAYER_KEY)
                    .payingWith(RELAYER)
                    .type(EthTransactionType.EIP7702)
                    .addCodeDelegationWithSpecNonce(DELEGATION_TARGET.get(), DELEGATING_ACCOUNT_1)
                    .addCodeDelegationWithSpecNonce(DELEGATION_TARGET.get(), DELEGATING_ACCOUNT_2)
                    .addCodeDelegationWithSpecNonce(DELEGATION_TARGET.get(), DELEGATING_ACCOUNT_3)
                    .gasLimit(2_000_000L)
                    .hasKnownStatus(ResponseCodeEnum.CONTRACT_REVERT_EXECUTED)
                    .via(DELEGATION_SET)
                    .exposingGasTo((s, gas) -> {
                        final var expectedGas = 21_000L /* intrinsic gas base fee */
                                + 64 /* payload cost */
                                + 3 * 25_000L /* code delegation fee with new account creation */
                                + 408 /*call costs in gas*/
                                + 3 * 554_517L /* auto creation hapi fee*/
                                + 3 * 32_337L /* mysterious new fee; TODO(Pectra) figure this out */;
                        assertEquals(expectedGas, gas);
                    })));
            allRunFor(
                    spec,
                    getAliasedAccountInfo(DELEGATING_ACCOUNT_1)
                            .hasDelegationAddress(DELEGATION_TARGET.get())
                            .isNotHollow()
                            .exposingIdTo(id -> spec.registry().saveAccountId(DELEGATING_ACCOUNT_ID_1, id)),
                    getAliasedAccountInfo(DELEGATING_ACCOUNT_2)
                            .hasDelegationAddress(DELEGATION_TARGET.get())
                            .isNotHollow()
                            .exposingIdTo(id -> spec.registry().saveAccountId(DELEGATING_ACCOUNT_ID_2, id)),
                    getAliasedAccountInfo(DELEGATING_ACCOUNT_3)
                            .hasDelegationAddress(DELEGATION_TARGET.get())
                            .isNotHollow()
                            .exposingIdTo(id -> spec.registry().saveAccountId(DELEGATING_ACCOUNT_ID_3, id)),
                    getTxnRecord(DELEGATION_SET)
                            .hasChildRecords(
                                    recordWith()
                                            .status(ResponseCodeEnum.SUCCESS)
                                            .targetAccountId(PAYER),
                                    // TODO(Pectra): something is off; figure out why we now have two child records for
                                    // payer?
                                    recordWith()
                                            .status(ResponseCodeEnum.SUCCESS)
                                            .targetAccountId(PAYER),
                                    recordWith()
                                            .targetAccountId(DELEGATING_ACCOUNT_ID_1)
                                            .status(ResponseCodeEnum.SUCCESS),
                                    recordWith()
                                            .targetAccountId(DELEGATING_ACCOUNT_ID_2)
                                            .status(ResponseCodeEnum.SUCCESS),
                                    recordWith()
                                            .targetAccountId(DELEGATING_ACCOUNT_ID_3)
                                            .status(ResponseCodeEnum.SUCCESS))
                            .logged());
        }));
    }

    @HapiTest
    final Stream<DynamicTest> testDelegationWithMultipleAccountsAndNotEnoughGasForLazyCreation() {
        return hapiTest(withOpContext((spec, opLog) -> {
            deployEvmContract(spec, CONTRACT);
            createPayerAccountWithAlias(spec);
            createSecp256k1Keys(spec, DELEGATING_ACCOUNT_1, DELEGATING_ACCOUNT_2, DELEGATING_ACCOUNT_3);
            allRunFor(spec, sourcing(() -> ethereumCall(CONTRACT, "create")
                    .signingWith(PAYER_KEY)
                    .payingWith(RELAYER)
                    .gasLimit(1_500_000L)
                    .type(EthTransactionType.EIP7702)
                    .addCodeDelegationWithSpecNonce(DELEGATION_TARGET.get(), DELEGATING_ACCOUNT_1)
                    .addCodeDelegationWithSpecNonce(DELEGATION_TARGET.get(), DELEGATING_ACCOUNT_2)
                    .addCodeDelegationWithSpecNonce(DELEGATION_TARGET.get(), DELEGATING_ACCOUNT_3)
                    .via(DELEGATION_SET)
                    .hasKnownStatus(ResponseCodeEnum.SUCCESS)
                    .exposingGasTo((s, gas) -> {
                        final var expectedGas = 21_000L /* intrinsic gas base fee */
                                + 64 /* payload cost */
                                + 3 * 25_000L /* code delegation fee with new account creation */
                                + 108_893L /*call costs in gas*/
                                + 2 * 554_517L /* auto creation hapi fee*/
                                + 2 * 32_337L /* mysterious new fee; TODO(Pectra) figure this out */;
                        assertEquals(expectedGas, gas);
                    })));
            allRunFor(
                    spec,
                    getAliasedAccountInfo(DELEGATING_ACCOUNT_1)
                            .hasDelegationAddress(DELEGATION_TARGET.get())
                            .isNotHollow()
                            .exposingIdTo(id -> spec.registry().saveAccountId(DELEGATING_ACCOUNT_ID_1, id)),
                    getAliasedAccountInfo(DELEGATING_ACCOUNT_2)
                            .hasDelegationAddress(DELEGATION_TARGET.get())
                            .isNotHollow()
                            .exposingIdTo(id -> spec.registry().saveAccountId(DELEGATING_ACCOUNT_ID_2, id)),
                    getAliasedAccountInfo(DELEGATING_ACCOUNT_3)
                            .hasCostAnswerPrecheck(ResponseCodeEnum.INVALID_ACCOUNT_ID),
                    getTxnRecord(DELEGATION_SET)
                            .hasChildRecords(
                                    recordWith()
                                            .status(ResponseCodeEnum.SUCCESS)
                                            .targetAccountId(PAYER),
                                    recordWith()
                                            .targetAccountId(DELEGATING_ACCOUNT_ID_1)
                                            .status(ResponseCodeEnum.SUCCESS),
                                    recordWith()
                                            .targetAccountId(DELEGATING_ACCOUNT_ID_2)
                                            .status(ResponseCodeEnum.SUCCESS),
                                    recordWith().status(ResponseCodeEnum.INSUFFICIENT_GAS),
                                    recordWith()
                                            .contractCreateResult(anyResult())
                                            .status(ResponseCodeEnum.SUCCESS))
                            .logged());
        }));
    }
}
