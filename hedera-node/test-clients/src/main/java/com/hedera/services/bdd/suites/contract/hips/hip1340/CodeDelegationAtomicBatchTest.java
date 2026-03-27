// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.hips.hip1340;

import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.explicitFromHeadlong;
import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAliasedAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.ethereumCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.blockingOrder;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.RELAYER;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SHAPE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INNER_TRANSACTION_FAILED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_ACCOUNT_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;

import com.esaulpaugh.headlong.abi.Address;
import com.google.protobuf.ByteString;
import com.hedera.node.app.hapi.utils.ethereum.EthTxData.EthTransactionType;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.dsl.annotations.Account;
import com.hedera.services.bdd.spec.dsl.annotations.Contract;
import com.hedera.services.bdd.spec.dsl.entities.SpecAccount;
import com.hedera.services.bdd.spec.dsl.entities.SpecContract;
import com.hedera.services.bdd.spec.transactions.token.TokenMovement;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import edu.umd.cs.findbugs.annotations.NonNull;

import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(SMART_CONTRACT)
@DisplayName("Code Delegation Atomic Batch Tests")
@HapiTestLifecycle
public class CodeDelegationAtomicBatchTest {
    private static final String CODE_DELEGATION_CONTRACT = "CodeDelegationContract";
    private static final AtomicReference<Address> DELEGATION_TARGET = new AtomicReference<>();
    private static final String DELEGATING_ACCOUNT = "DelegatingAccount";
    private static final String CONTRACT = "CreateTrivial";
    private static final String DELEGATION_SET = "DelegationSet";
    private static final String CRYPTO_CREATE_DELEGATING_ACCOUNT = "CryptoCreateDelegatingAccount";
    private static final String ACCOUNT_WITH_BALANCE = "AccountWithBalance";
    private static final String INSUFFICIENT_BALANCE_ACCOUNT = "InsufficientBalanceAccount";

    @Contract(contract = CONTRACT, creationGas = 5_000_000)
    static SpecContract contract;

    @Account(name = ACCOUNT_WITH_BALANCE, tinybarBalance = ONE_HUNDRED_HBARS)
    static SpecAccount account;

    @Account(name = INSUFFICIENT_BALANCE_ACCOUNT)
    static SpecAccount insufficientBalanceAccount;

    @Account(name = RELAYER, tinybarBalance = ONE_MILLION_HBARS)
    static SpecAccount relayer;

    @BeforeAll
    public static void setup(@NonNull final TestLifecycle lifecycle) {
        lifecycle.doAdhoc(
                contract.getInfo(),
                account.getInfo(),
                insufficientBalanceAccount.getInfo(),
                relayer.getInfo(),
                uploadInitCode(CODE_DELEGATION_CONTRACT),
                contractCreate(CODE_DELEGATION_CONTRACT).exposingAddressTo(DELEGATION_TARGET::set));
    }

    @HapiTest
    final Stream<DynamicTest> testDelegationCommitedInSuccessfulAtomicBatch() {
        final var delegationTargetAddress = DELEGATION_TARGET.get();
        final var type4Txn = "DelegationInBatch";
        return hapiTest(
                createFundedAccount(DELEGATING_ACCOUNT),
                getAliasedAccountInfo(DELEGATING_ACCOUNT).hasNoDelegation(),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        sourcing(() -> atomicBatch(
                                ethereumCall(CONTRACT, "create")
                                        .signingWith(DELEGATING_ACCOUNT)
                                        .payingWith(RELAYER)
                                        .type(EthTransactionType.EIP7702)
                                        .addSenderCodeDelegationWithSpecNonce(delegationTargetAddress)
                                        .gasLimit(2_000_000L)
                                        .via(type4Txn)
                                        .batchKey(RELAYER),
                                cryptoTransfer(TokenMovement.movingHbar(ONE_HBAR)
                                        .between(ACCOUNT_WITH_BALANCE, RELAYER))
                                        .hasKnownStatus(SUCCESS)
                                        .batchKey(RELAYER))
                                .payingWith(RELAYER)),
                        getTxnRecord(type4Txn).andAllChildRecords().logged(),
                        getAliasedAccountInfo(DELEGATING_ACCOUNT).hasDelegationAddress(delegationTargetAddress))));
    }

    @HapiTest
    final Stream<DynamicTest> testDelegationSurvivesAtomicBatchRollback() {
        final var delegationTargetAddress = DELEGATION_TARGET.get();
        final var failedBatchTxn = "DelegationInAFailedBatch";
        return hapiTest(
                createFundedAccount(DELEGATING_ACCOUNT),
                getAliasedAccountInfo(DELEGATING_ACCOUNT).hasNoDelegation(),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        sourcing(() -> atomicBatch(
                                ethereumCall(CONTRACT, "create")
                                        .signingWith(DELEGATING_ACCOUNT)
                                        .payingWith(RELAYER)
                                        .type(EthTransactionType.EIP7702)
                                        .addSenderCodeDelegationWithSpecNonce(delegationTargetAddress)
                                        .gasLimit(2_000_000L)
                                        .via(failedBatchTxn)
                                        .batchKey(RELAYER),
                                cryptoTransfer(TokenMovement.movingHbar(ONE_HBAR)
                                        .between(INSUFFICIENT_BALANCE_ACCOUNT, RELAYER))
                                        .hasKnownStatus(INSUFFICIENT_ACCOUNT_BALANCE)
                                        .batchKey(RELAYER))
                                .payingWith(RELAYER)
                                .via(failedBatchTxn)
                                .hasKnownStatus(INNER_TRANSACTION_FAILED)),
                        getTxnRecord(failedBatchTxn).andAllChildRecords().logged(),

                        // TODO (dsinyakov): switch to below assert when atomic batch behavior is fixed
                        // getAliasedAccountInfo(DELEGATING_ACCOUNT).hasDelegationAddress(delegationTargetAddress)
                        getAliasedAccountInfo(DELEGATING_ACCOUNT).hasNoDelegation())
                ));
    }

    @HapiTest
    final Stream<DynamicTest> testAtomicBatchRevertsAllDelegationTransactionsOnInnerTxFailure() {
        final var initialDelegationAddress = ByteString.copyFrom(explicitFromHeadlong(DELEGATION_TARGET.get()));
        final var delegationTargetAddress = DELEGATION_TARGET.get();
        final var batchTxn = "BatchDelegationRollback";
        return hapiTest(
                createHollowAccount(DELEGATING_ACCOUNT),
                cryptoCreate(CRYPTO_CREATE_DELEGATING_ACCOUNT).key(RELAYER).balance(ONE_HUNDRED_HBARS),
                getAccountInfo(CRYPTO_CREATE_DELEGATING_ACCOUNT).hasNoDelegation(),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        sourcing(() -> atomicBatch(
                                cryptoUpdate(CRYPTO_CREATE_DELEGATING_ACCOUNT)
                                        .delegationAddress(initialDelegationAddress)
                                        .batchKey(RELAYER),
                                ethereumCall(CONTRACT, "create")
                                        .signingWith(DELEGATING_ACCOUNT)
                                        .payingWith(RELAYER)
                                        .type(EthTransactionType.EIP7702)
                                        .addSenderCodeDelegationWithSpecNonce(delegationTargetAddress)
                                        .gasLimit(2_000_000L)
                                        .via(DELEGATION_SET)
                                        .batchKey(RELAYER),
                                cryptoTransfer(TokenMovement.movingHbar(ONE_HBAR)
                                        .between(INSUFFICIENT_BALANCE_ACCOUNT, RELAYER))
                                        .hasKnownStatus(INSUFFICIENT_ACCOUNT_BALANCE)
                                        .batchKey(RELAYER))
                                .payingWith(RELAYER)
                                .via(batchTxn)
                                .hasKnownStatus(INNER_TRANSACTION_FAILED)),
                        getTxnRecord(batchTxn).andAllChildRecords().logged(),
                        getTxnRecord(DELEGATION_SET).andAllChildRecords().logged(),
                        // Verify rollback: native delegation update was not persisted.
                        getAccountInfo(CRYPTO_CREATE_DELEGATING_ACCOUNT).hasNoDelegation(),
                        // Verify rollback: type 4 delegation to delegationTargetAddress was not applied.
                        getAliasedAccountInfo(DELEGATING_ACCOUNT).hasNoDelegation())));
    }

    @HapiTest
    final Stream<DynamicTest> testAtomicBatchCryptoCreateSetsDelegationThenType4UpdatesIt() {
        final var initialDelegationAddress = ByteString.copyFrom(explicitFromHeadlong(DELEGATION_TARGET.get()));
        final var delegationTargetAddress = DELEGATION_TARGET.get();
        final var accountInBatch = DELEGATING_ACCOUNT + "CreateThenUpdateInBatch";
        final var type4Txn = "type4UpdatesDelegationInBatch";
        return hapiTest(
                newKeyNamed(accountInBatch).shape(SECP_256K1_SHAPE),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        sourcing(() -> atomicBatch(
                                cryptoCreate(accountInBatch)
                                        .key(accountInBatch)
                                        .withMatchingEvmAddress()
                                        .balance(ONE_HUNDRED_HBARS)
                                        .delegationAddress(initialDelegationAddress)
                                        .batchKey(RELAYER),
                                ethereumCall(CONTRACT, "create")
                                        .signingWith(accountInBatch)
                                        .payingWith(RELAYER)
                                        .type(EthTransactionType.EIP7702)
                                        .addSenderCodeDelegationWithSpecNonce(delegationTargetAddress)
                                        .gasLimit(2_000_000L)
                                        .via(type4Txn)
                                        .batchKey(RELAYER))
                                .payingWith(RELAYER)),
                        getTxnRecord(type4Txn).andAllChildRecords().logged(),
                        // Type 4 update should override the delegation set by native create in the same batch.
                        getAliasedAccountInfo(accountInBatch).hasDelegationAddress(delegationTargetAddress))));
    }

    @HapiTest
    final Stream<DynamicTest> testAtomicBatchType4PartialCommitIsRolledBackOnInnerTxFailureAcrossAccounts() {
        final var initialDelegationAddress = ByteString.copyFrom(explicitFromHeadlong(DELEGATION_TARGET.get()));
        final var delegatingAccount1 = DELEGATING_ACCOUNT + "Batch1";
        final var delegatingAccount2 = DELEGATING_ACCOUNT + "Batch2";
        final var delegatingAccount3 = DELEGATING_ACCOUNT + "Batch3";
        final var delegationTargetAddress = DELEGATION_TARGET.get();
        final var partialCommitTxn = "batchType4PartialCommitAcrossAccounts";
        return hapiTest(
                newKeyNamed(DELEGATING_ACCOUNT).shape(SECP_256K1_SHAPE),
                newKeyNamed(delegatingAccount1).shape(SECP_256K1_SHAPE),
                newKeyNamed(delegatingAccount2).shape(SECP_256K1_SHAPE),
                newKeyNamed(delegatingAccount3).shape(SECP_256K1_SHAPE),
                cryptoTransfer(TokenMovement.movingHbar(ONE_HUNDRED_HBARS)
                        .distributing(GENESIS, DELEGATING_ACCOUNT, delegatingAccount1)),
                cryptoTransfer(TokenMovement.movingHbar(ONE_HUNDRED_HBARS)
                        .distributing(GENESIS, delegatingAccount2, delegatingAccount3)),
                cryptoCreate(CRYPTO_CREATE_DELEGATING_ACCOUNT).key(RELAYER).balance(ONE_HUNDRED_HBARS),
                getAccountInfo(CRYPTO_CREATE_DELEGATING_ACCOUNT).hasNoDelegation(),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        sourcing(() -> atomicBatch(
                                cryptoUpdate(CRYPTO_CREATE_DELEGATING_ACCOUNT)
                                        .delegationAddress(initialDelegationAddress)
                                        .batchKey(RELAYER),
                                ethereumCall(CONTRACT, "create")
                                        .signingWith(DELEGATING_ACCOUNT)
                                        .payingWith(RELAYER)
                                        .type(EthTransactionType.EIP7702)
                                        .addSenderCodeDelegationWithSpecNonce(delegationTargetAddress)
                                        .addCodeDelegationWithSpecNonce(delegationTargetAddress, delegatingAccount1)
                                        .addCodeDelegationWithNonce(delegationTargetAddress, 1L, delegatingAccount2)
                                        .addCodeDelegationWithNonce(delegationTargetAddress, 1L, delegatingAccount3)
                                        .gasLimit(2_000_000L)
                                        .hasKnownStatus(ResponseCodeEnum.REVERTED_SUCCESS)
                                        .via(partialCommitTxn)
                                        .batchKey(RELAYER),
                                cryptoTransfer(TokenMovement.movingHbar(ONE_HBAR)
                                        .between(INSUFFICIENT_BALANCE_ACCOUNT, RELAYER))
                                        .hasKnownStatus(INSUFFICIENT_ACCOUNT_BALANCE)
                                        .batchKey(RELAYER))
                                .payingWith(RELAYER)
                                .hasKnownStatus(INNER_TRANSACTION_FAILED)),
                        getTxnRecord(partialCommitTxn).andAllChildRecords().logged(),
                        // Atomic batch failure rolls back all delegation effects from inner txns.
                        getAccountInfo(CRYPTO_CREATE_DELEGATING_ACCOUNT).hasNoDelegation(),
                        getAliasedAccountInfo(DELEGATING_ACCOUNT).hasNoDelegation(),
                        getAliasedAccountInfo(delegatingAccount1).hasNoDelegation(),
                        getAliasedAccountInfo(delegatingAccount2).hasNoDelegation(),
                        getAliasedAccountInfo(delegatingAccount3).hasNoDelegation())));
    }

    @HapiTest
    final Stream<DynamicTest> testAtomicBatchType4PartialCommitAcrossAccountsWithInvalidAuthorization() {
        final var delegationAddress = ByteString.copyFrom(explicitFromHeadlong(DELEGATION_TARGET.get()));
        final var delegatingAccount1 = DELEGATING_ACCOUNT + "Batch1";
        final var delegatingAccount2 = DELEGATING_ACCOUNT + "Batch2";
        final var delegatingAccount3 = DELEGATING_ACCOUNT + "Batch3";
        final var delegationTargetAddress = DELEGATION_TARGET.get();
        final var partialCommitTxn = "batchType4PartialCommitAcrossAccounts";
        return hapiTest(
                newKeyNamed(DELEGATING_ACCOUNT).shape(SECP_256K1_SHAPE),
                newKeyNamed(delegatingAccount1).shape(SECP_256K1_SHAPE),
                newKeyNamed(delegatingAccount2).shape(SECP_256K1_SHAPE),
                newKeyNamed(delegatingAccount3).shape(SECP_256K1_SHAPE),
                cryptoTransfer(TokenMovement.movingHbar(ONE_HUNDRED_HBARS)
                        .distributing(GENESIS, DELEGATING_ACCOUNT, delegatingAccount1)),
                cryptoTransfer(TokenMovement.movingHbar(ONE_HUNDRED_HBARS)
                        .distributing(GENESIS, delegatingAccount2, delegatingAccount3)),
                cryptoCreate(CRYPTO_CREATE_DELEGATING_ACCOUNT).key(RELAYER).balance(ONE_HUNDRED_HBARS),
                getAccountInfo(CRYPTO_CREATE_DELEGATING_ACCOUNT).hasNoDelegation(),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        sourcing(() -> atomicBatch(
                                cryptoUpdate(CRYPTO_CREATE_DELEGATING_ACCOUNT)
                                        .delegationAddress(delegationAddress)
                                        .batchKey(RELAYER),
                                ethereumCall(CONTRACT, "create")
                                        .signingWith(DELEGATING_ACCOUNT)
                                        .payingWith(RELAYER)
                                        .type(EthTransactionType.EIP7702)
                                        .addSenderCodeDelegationWithSpecNonce(delegationTargetAddress)
                                        .addCodeDelegationWithSpecNonce(delegationTargetAddress, delegatingAccount1)
                                        .addCodeDelegationWithNonce(delegationTargetAddress, 1L, delegatingAccount2)
                                        .addCodeDelegationWithNonce(delegationTargetAddress, 1L, delegatingAccount3)
                                        .gasLimit(2_000_000L)
                                        .hasKnownStatus(SUCCESS)
                                        .via(partialCommitTxn)
                                        .batchKey(RELAYER))
                                .payingWith(RELAYER)
                                .hasKnownStatus(SUCCESS)),
                        getTxnRecord(partialCommitTxn).andAllChildRecords().logged(),
                        // Atomic batch commits back all delegations.
                        getAccountInfo(CRYPTO_CREATE_DELEGATING_ACCOUNT).hasDelegationAddress(DELEGATION_TARGET.get()),
                        getAliasedAccountInfo(DELEGATING_ACCOUNT).hasDelegationAddress(DELEGATION_TARGET.get()),
                        getAliasedAccountInfo(delegatingAccount1).hasDelegationAddress(DELEGATION_TARGET.get()),
                        getAliasedAccountInfo(delegatingAccount2).hasNoDelegation(),
                        getAliasedAccountInfo(delegatingAccount3).hasNoDelegation())));
    }

    private static SpecOperation createFundedAccount(@NonNull final String name) {
        return blockingOrder(
                newKeyNamed(name).shape(SECP_256K1_SHAPE),
                cryptoCreate(name).key(name).withMatchingEvmAddress().balance(ONE_HUNDRED_HBARS));
    }

    private static SpecOperation createHollowAccount(@NonNull final String name) {
        return blockingOrder(
                newKeyNamed(name).shape(SECP_256K1_SHAPE),
                cryptoTransfer(TokenMovement.movingHbar(ONE_HUNDRED_HBARS)
                        .distributing(GENESIS, DELEGATING_ACCOUNT, name)));
    }
}
