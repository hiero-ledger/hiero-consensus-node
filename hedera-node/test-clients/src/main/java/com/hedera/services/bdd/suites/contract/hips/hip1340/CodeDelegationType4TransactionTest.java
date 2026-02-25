// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.hips.hip1340;

import static com.hedera.node.app.hapi.utils.EthSigsUtils.recoverAddressFromPubKey;
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
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.RELAYER;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SHAPE;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.esaulpaugh.headlong.abi.Address;
import com.esaulpaugh.headlong.abi.Function;
import com.esaulpaugh.headlong.abi.Tuple;
import com.google.protobuf.ByteString;
import com.hedera.node.app.hapi.utils.ethereum.EthTxData.EthTransactionType;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.HapiPropertySource;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts;
import com.hedera.services.bdd.spec.transactions.contract.HapiEthereumCall;
import com.hedera.services.bdd.spec.transactions.token.TokenMovement;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Arrays;
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
public class CodeDelegationType4TransactionTest {

    private static final AtomicReference<Address> DELEGATION_TARGET = new AtomicReference<>();
    private static final String DELEGATING_ACCOUNT = "delegating_account";
    private static final String DELEGATING_ACCOUNT_1 = DELEGATING_ACCOUNT + "_1";
    private static final String DELEGATING_ACCOUNT_2 = DELEGATING_ACCOUNT + "_2";
    private static final String DELEGATING_ACCOUNT_3 = DELEGATING_ACCOUNT + "_3";
    private static final String DELEGATING_ACCOUNT_ID = "delegating_account_id";
    private static final String DELEGATING_ACCOUNT_ID_1 = DELEGATING_ACCOUNT_ID + "_1";
    private static final String DELEGATING_ACCOUNT_ID_2 = DELEGATING_ACCOUNT_ID + "_2";
    private static final String DELEGATING_ACCOUNT_ID_3 = DELEGATING_ACCOUNT_ID + "_3";
    private static final String CONTRACT = "CreateTrivial";
    private static final String REVERTING_CONTRACT = "InternalCallee";
    private static final String CREATION = "creation";
    private static final String DELEGATION_SET = "delegation_set";
    private static final String DELEGATION_CALL = "delegation_call";
    private static final String DELEGATION_RESET = "delegation_reset";
    private static final String PAYER_KEY = "PayerAccountKey";
    private static final String PAYER = "PayerAccount";

    @BeforeAll
    public static void setup(@NonNull TestLifecycle lifecycle) {
        lifecycle.doAdhoc(
                uploadInitCode("CodeDelegationContract"),
                contractCreate("CodeDelegationContract").exposingAddressTo(DELEGATION_TARGET::set),
                cryptoCreate(RELAYER).balance(ONE_MILLION_HBARS));
    }

    @HapiTest
    final Stream<DynamicTest> testCodeDelegationSetViaEthCall() {

        final var delegatedFunctionSelector = Hex.toHexString(
                new Function("getValue()").encodeCall(Tuple.EMPTY).array());

        return hapiTest(withOpContext((spec, opLog) -> {
            deployContract(spec, CONTRACT);
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
            deployContract(spec, CONTRACT);
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
                    spec, delegationTargetAddress, DELEGATING_ACCOUNT, DELEGATING_ACCOUNT_1, DELEGATING_ACCOUNT_2,
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
            deployContract(spec, CONTRACT);
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
                                        + 554_517L /* auto creation hapi fee*/;
                                assertEquals(expectedGas, gas);
                            })),
                    getAliasedAccountInfo(DELEGATING_ACCOUNT)
                            .hasDelegationAddress(DELEGATION_TARGET.get())
                            .isNotHollow()
                            .exposingIdTo(id -> spec.registry().saveAccountId(DELEGATING_ACCOUNT_ID, id)),
                    getTxnRecord(DELEGATION_SET)
                            .hasChildRecords(
                                    recordWith().status(ResponseCodeEnum.SUCCESS).targetAccountId(PAYER),
                                    recordWith()
                                            .targetAccountId(DELEGATING_ACCOUNT_ID)
                                            .status(ResponseCodeEnum.SUCCESS),
                                    recordWith().contractCreateResult(anyResult()).status(ResponseCodeEnum.SUCCESS))
                            .logged());
        }));
    }

    @HapiTest
    final Stream<DynamicTest> testDelegationSetToHollowAccountWithRevertingCall() {
        return hapiTest(withOpContext((spec, opLog) -> {
            deployContract(spec, REVERTING_CONTRACT);
            createPayerAccountWithAlias(spec);
            createSecp256k1Keys(spec, DELEGATING_ACCOUNT);
            allRunFor(
                    spec,
                    sourcing(() -> ethereumCall(REVERTING_CONTRACT, "revertWithRevertReason")
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
                                        + 554_517L /* auto creation hapi fee*/;
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
                                    recordWith()
                                            .targetAccountId(DELEGATING_ACCOUNT_ID)
                                            .status(ResponseCodeEnum.SUCCESS))
                            .logged());
        }));
    }

    @HapiTest
    final Stream<DynamicTest> testDelegationSetToMultipleHollowAccountsWithRevertingCall() {
        return hapiTest(withOpContext((spec, opLog) -> {
            deployContract(spec, REVERTING_CONTRACT);
            createPayerAccountWithAlias(spec);
            createSecp256k1Keys(spec, DELEGATING_ACCOUNT_1, DELEGATING_ACCOUNT_2, DELEGATING_ACCOUNT_3);
            allRunFor(
                    spec,
                    sourcing(() -> ethereumCall(REVERTING_CONTRACT, "revertWithRevertReason")
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
                                        + 3 * 554_517L /* auto creation hapi fee*/;
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
            deployContract(spec, CONTRACT);
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
                        + 2 * 554_517L /* auto creation hapi fee*/;
                        assertEquals(expectedGas, gas);
                    })));
            allRunFor(spec,
                    getAliasedAccountInfo(DELEGATING_ACCOUNT_1)
                            .hasDelegationAddress(DELEGATION_TARGET.get())
                            .isNotHollow()
                            .exposingIdTo(id -> spec.registry().saveAccountId(DELEGATING_ACCOUNT_ID_1, id)),
                    getAliasedAccountInfo(DELEGATING_ACCOUNT_2)
                            .hasDelegationAddress(DELEGATION_TARGET.get())
                            .isNotHollow()
                            .exposingIdTo(id -> spec.registry().saveAccountId(DELEGATING_ACCOUNT_ID_2, id)),
                    getAliasedAccountInfo(DELEGATING_ACCOUNT_3).hasCostAnswerPrecheck(ResponseCodeEnum.INVALID_ACCOUNT_ID),
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
                            recordWith()
                                    .status(ResponseCodeEnum.INSUFFICIENT_GAS),
                            recordWith().contractCreateResult(anyResult()).status(ResponseCodeEnum.SUCCESS))
                    .logged());
        }));
    }

    private static void deployContract(HapiSpec spec, String contractName) {
        allRunFor(spec, uploadInitCode(contractName), contractCreate(contractName).gas(4_000_000L));
    }

    private static void createPayerAccountWithAlias(HapiSpec spec) {
        allRunFor(
                spec,
                newKeyNamed(PAYER_KEY).shape(SECP_256K1_SHAPE).exposingKeyTo(key -> {
                    final var evmAddress = ByteString.copyFrom(
                            recoverAddressFromPubKey(key.getECDSASecp256K1().toByteArray()));
                    spec.registry()
                            .saveAccountAlias(
                                    PAYER_KEY,
                                    AccountID.newBuilder().setAlias(evmAddress).build());
                }),
                cryptoTransfer(TokenMovement.movingHbar(ONE_HUNDRED_HBARS).between(GENESIS, PAYER_KEY))
                        .via(CREATION),
                getTxnRecord(CREATION).exposingCreationsTo(creations -> {
                    final var createdId = HapiPropertySource.asAccount(creations.getFirst());
                    spec.registry().saveAccountId(PAYER, createdId);
                }));
    }

    private static void createSecp256k1Keys(HapiSpec spec, String... names) {
        allRunFor(
                spec,
                Arrays.stream(names)
                        .map(name -> (HapiSpecOperation) newKeyNamed(name).shape(SECP_256K1_SHAPE))
                        .toArray(HapiSpecOperation[]::new));
    }

    private static void verifyDelegationSet(HapiSpec spec, Address target, String... accountNames) {
        allRunFor(
                spec,
                Arrays.stream(accountNames)
                        .map(name -> (HapiSpecOperation) getAliasedAccountInfo(name).isNotHollow().hasDelegationAddress(target))
                        .toArray(HapiSpecOperation[]::new));
    }

    private static void verifyDelegationCleared(HapiSpec spec, String... accountNames) {
        allRunFor(
                spec,
                Arrays.stream(accountNames)
                        .map(name -> (HapiSpecOperation) getAliasedAccountInfo(name).hasNoDelegation())
                        .toArray(HapiSpecOperation[]::new));
    }
}
