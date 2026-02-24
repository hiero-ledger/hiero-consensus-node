// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.hips.hip1340;

import static com.hedera.node.app.service.contract.impl.utils.ConstantUtils.ZERO_ADDRESS;
import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
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
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(SMART_CONTRACT)
@DisplayName("Code Delegation Via Ethereum Type 4 Transaction Tests")
@HapiTestLifecycle
public class CodeDelegationType4TransactionTest {

    private static final AtomicReference<Address> DELEGATION_TARGET = new AtomicReference<>();
    private static final String DELEGATING_ACCOUNT = "delegating_account";
    private static final String DELEGATING_ACCOUNT_ID = "delegating_account_id";
    private static final String CONTRACT = "CreateTrivial";
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

        return hapiTest(withOpContext((spec, opLog) -> allRunFor(
                spec,
                // deploy contract
                uploadInitCode(CONTRACT),
                contractCreate(CONTRACT).gas(4_000_000L),
                // create accounts
                newKeyNamed(DELEGATING_ACCOUNT).shape(SECP_256K1_SHAPE),
                cryptoTransfer(TokenMovement.movingHbar(ONE_HUNDRED_HBARS).between(GENESIS, DELEGATING_ACCOUNT))
                        .via(CREATION),
                getTxnRecord(CREATION).exposingCreationsTo(creations -> {
                    final var createdId = HapiPropertySource.asAccount(creations.getFirst());
                    spec.registry().saveAccountId(DELEGATING_ACCOUNT_ID, createdId);
                }),
                // ethereum transaction with code delegation
                sourcing(() -> ethereumCall(CONTRACT, "create")
                        .signingWith(DELEGATING_ACCOUNT)
                        .payingWith(RELAYER)
                        .type(EthTransactionType.EIP7702)
                        .addSenderCodeDelegationWithSpecNonce(DELEGATION_TARGET.get())
                        .gasLimit(2_000_000L)
                        .via(DELEGATION_SET)),
                // check delegation is set
                getAliasedAccountInfo(DELEGATING_ACCOUNT)
                        .hasDelegationAddress(DELEGATION_TARGET.get())
                        .isNotHollow(),
                // Try calling the delegation
                sourcing(() -> new HapiEthereumCall(DELEGATING_ACCOUNT_ID, 0L)
                        .withExplicitParams(() -> delegatedFunctionSelector)
                        .payingWith(RELAYER)
                        .signingWith(DELEGATING_ACCOUNT)
                        // setting nonce manually
                        .hasKnownStatus(ResponseCodeEnum.SUCCESS)
                        .via(DELEGATION_CALL)),
                // check records
                getTxnRecord(DELEGATION_SET)
                        .andAllChildRecords()
                        .hasChildRecords(
                                // check crypto update
                                recordWith()
                                        .targetAccountId(DELEGATING_ACCOUNT_ID)
                                        .status(ResponseCodeEnum.SUCCESS),
                                // check actual transaction executed
                                recordWith()
                                        .contractCreateResult(ContractFnResultAsserts.anyResult())
                                        .status(ResponseCodeEnum.SUCCESS))
                        .logged(),
                getTxnRecord(DELEGATION_CALL).andAllChildRecords().logged(),
                // try to reset delegation with 0x00
                sourcing(() -> ethereumCall(CONTRACT, "getAddress")
                        .signingWith(DELEGATING_ACCOUNT)
                        .payingWith(RELAYER)
                        .type(EthTransactionType.EIP7702)
                        .addSenderCodeDelegationWithSpecNonce(ZERO_ADDRESS)
                        .gasLimit(50_000L)
                        .via(DELEGATION_RESET)),
                // verify delegation is cleared
                getAliasedAccountInfo(DELEGATING_ACCOUNT).hasNoDelegation(),
                getTxnRecord(DELEGATION_RESET).andAllChildRecords().logged())));
    }

    @HapiTest
    final Stream<DynamicTest> testMultipleCodeDelegationsSetViaEthCall() {
        final var DELEGATING_ACCOUNT_1 = DELEGATING_ACCOUNT + "_1";
        final var DELEGATING_ACCOUNT_2 = DELEGATING_ACCOUNT + "_2";
        final var DELEGATING_ACCOUNT_3 = DELEGATING_ACCOUNT + "_3";
        final var delegationTargetAddress = DELEGATION_TARGET.get();
        return hapiTest(withOpContext((spec, opLog) -> allRunFor(
                spec,
                // deploy contract
                uploadInitCode(CONTRACT),
                contractCreate(CONTRACT).gas(4_000_000L),
                // create accounts
                newKeyNamed(DELEGATING_ACCOUNT).shape(SECP_256K1_SHAPE),
                newKeyNamed(DELEGATING_ACCOUNT_1).shape(SECP_256K1_SHAPE),
                newKeyNamed(DELEGATING_ACCOUNT_2).shape(SECP_256K1_SHAPE),
                newKeyNamed(DELEGATING_ACCOUNT_3).shape(SECP_256K1_SHAPE),
                cryptoTransfer(TokenMovement.movingHbar(ONE_HUNDRED_HBARS)
                        .distributing(GENESIS, DELEGATING_ACCOUNT, DELEGATING_ACCOUNT_1)),
                // Can't auto create more than 3 times per txn
                cryptoTransfer(TokenMovement.movingHbar(ONE_HUNDRED_HBARS)
                        .distributing(GENESIS, DELEGATING_ACCOUNT_2, DELEGATING_ACCOUNT_3)),
                // ethereum transaction with code delegation
                sourcing(() -> ethereumCall(CONTRACT, "create")
                        .signingWith(DELEGATING_ACCOUNT)
                        .payingWith(RELAYER)
                        .type(EthTransactionType.EIP7702)
                        .addSenderCodeDelegationWithSpecNonce(delegationTargetAddress)
                        .addCodeDelegationWithSpecNonce(delegationTargetAddress, DELEGATING_ACCOUNT_1)
                        .addCodeDelegationWithSpecNonce(delegationTargetAddress, DELEGATING_ACCOUNT_2)
                        .addCodeDelegationWithSpecNonce(delegationTargetAddress, DELEGATING_ACCOUNT_3)
                        .gasLimit(2_000_000L)
                        .via(DELEGATION_SET)),
                // check delegation is set
                getAliasedAccountInfo(DELEGATING_ACCOUNT)
                        .hasDelegationAddress(delegationTargetAddress)
                        .isNotHollow(),
                getAliasedAccountInfo(DELEGATING_ACCOUNT_1)
                        .hasDelegationAddress(delegationTargetAddress)
                        .isNotHollow(),
                getAliasedAccountInfo(DELEGATING_ACCOUNT_2)
                        .hasDelegationAddress(delegationTargetAddress)
                        .isNotHollow(),
                getAliasedAccountInfo(DELEGATING_ACCOUNT_3)
                        .hasDelegationAddress(delegationTargetAddress)
                        .isNotHollow(),
                // check records
                getTxnRecord(DELEGATION_SET)
                        .andAllChildRecords()
                        .hasChildRecordCount(5)
                        .logged(),
                // try to reset delegation with 0x00
                sourcing(() -> ethereumCall(CONTRACT, "getAddress")
                        .signingWith(DELEGATING_ACCOUNT)
                        .payingWith(RELAYER)
                        .type(EthTransactionType.EIP7702)
                        .addSenderCodeDelegationWithSpecNonce(ZERO_ADDRESS)
                        .addCodeDelegationWithSpecNonce(ZERO_ADDRESS, DELEGATING_ACCOUNT_1)
                        .addCodeDelegationWithSpecNonce(ZERO_ADDRESS, DELEGATING_ACCOUNT_2)
                        .addCodeDelegationWithSpecNonce(ZERO_ADDRESS, DELEGATING_ACCOUNT_3)
                        .gasLimit(200_000L)
                        .via(DELEGATION_RESET)),
                // verify delegation is cleared
                getAliasedAccountInfo(DELEGATING_ACCOUNT).hasNoDelegation(),
                getAliasedAccountInfo(DELEGATING_ACCOUNT_1).hasNoDelegation(),
                getAliasedAccountInfo(DELEGATING_ACCOUNT_2).hasNoDelegation(),
                getAliasedAccountInfo(DELEGATING_ACCOUNT_3).hasNoDelegation(),
                getTxnRecord(DELEGATION_RESET).andAllChildRecords().logged())));
    }

    @Disabled("Until we sort the storage issue")
    @HapiTest
    final Stream<DynamicTest> testDelegatingToKey() {
        return hapiTest(withOpContext((spec, opLog) -> allRunFor(
                spec,
                // deploy contract
                uploadInitCode(CONTRACT),
                contractCreate(CONTRACT).gas(4_000_000L),
                // create accounts
                newKeyNamed(PAYER_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(PAYER).key(PAYER_KEY),
                newKeyNamed(DELEGATING_ACCOUNT).shape(SECP_256K1_SHAPE),
                // ethereum transaction with code delegation
                sourcing(() -> ethereumCall(CONTRACT, "create")
                        .signingWith(PAYER_KEY)
                        .payingWith(RELAYER)
                        .type(EthTransactionType.EIP7702)
                        .addCodeDelegationWithSpecNonce(DELEGATION_TARGET.get(), DELEGATING_ACCOUNT)
                        .gasLimit(2_000_000L)
                        .via(DELEGATION_SET)),
                // check delegation is set
                getAliasedAccountInfo(DELEGATING_ACCOUNT)
                        .hasDelegationAddress(DELEGATION_TARGET.get())
                        .isNotHollow())));
    }
}
