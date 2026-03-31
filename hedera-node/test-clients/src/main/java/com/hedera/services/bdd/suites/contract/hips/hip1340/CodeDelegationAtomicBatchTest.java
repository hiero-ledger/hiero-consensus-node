// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.hips.hip1340;

import static com.hedera.node.app.service.contract.impl.utils.ConstantUtils.ZERO_ADDRESS;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.explicitFromHeadlong;
import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
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
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.assertionsHold;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.blockingOrder;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.RELAYER;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SHAPE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;
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

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(SMART_CONTRACT)
@DisplayName("Code Delegation Atomic Batch Tests")
@HapiTestLifecycle
public class CodeDelegationAtomicBatchTest {
    private static final String CODE_DELEGATION_CONTRACT = "CodeDelegationContract";
    private static final String CODE_DELEGATION_CONTRACT_2 = "CodeDelegationContract2";
    private static final AtomicReference<Address> DELEGATION_TARGET = new AtomicReference<>();
    private static final AtomicReference<Address> DELEGATION_TARGET_2 = new AtomicReference<>();
    private static final String DELEGATING_ACCOUNT = "DelegatingAccount";
    private static final String CONTRACT = "CreateTrivial";
    private static final String REVERTING_CONTRACT = "InternalCallee";
    private static final String DELEGATION_SET = "DelegationSet";
    private static final String CRYPTO_CREATE_DELEGATING_ACCOUNT = "CryptoCreateDelegatingAccount";
    private static final String ACCOUNT_WITH_BALANCE = "AccountWithBalance";
    private static final String INSUFFICIENT_BALANCE_ACCOUNT = "InsufficientBalanceAccount";
    private static final long GAS_LIMIT_2M = 2_000_000L;

    @Contract(contract = CONTRACT, creationGas = 5_000_000)
    static SpecContract contract;

    @Contract(contract = REVERTING_CONTRACT, creationGas = 5_000_000)
    static SpecContract revertingContract;

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
                revertingContract.getInfo(),
                account.getInfo(),
                insufficientBalanceAccount.getInfo(),
                relayer.getInfo(),
                uploadInitCode(CODE_DELEGATION_CONTRACT),
                contractCreate(CODE_DELEGATION_CONTRACT).exposingAddressTo(DELEGATION_TARGET::set),
                contractCreate(CODE_DELEGATION_CONTRACT_2)
                        .bytecode(CODE_DELEGATION_CONTRACT)
                        .exposingAddressTo(DELEGATION_TARGET_2::set));
    }

    // 1.1: atomicBatch(type-4 sets delegation on A, valid transfer) - batch succeeds
    @HapiTest
    final Stream<DynamicTest> testDelegationCommitedInSuccessfulAtomicBatch() {
        final var delegationTargetAddress = DELEGATION_TARGET.get();
        final var type4Txn = "DelegationInBatch";
        return hapiTest(
                createFundedAccount(DELEGATING_ACCOUNT),
                getAliasedAccountInfo(DELEGATING_ACCOUNT).hasNoDelegation(),
                atomicBatch(
                        ethereumCall(CONTRACT, "create")
                                .signingWith(DELEGATING_ACCOUNT)
                                .payingWith(RELAYER)
                                .type(EthTransactionType.EIP7702)
                                .addSenderCodeDelegationWithSpecNonce(delegationTargetAddress)
                                .gasLimit(GAS_LIMIT_2M)
                                .via(type4Txn)
                                .batchKey(RELAYER),
                        cryptoTransfer(TokenMovement.movingHbar(ONE_HBAR)
                                .between(ACCOUNT_WITH_BALANCE, RELAYER))
                                .hasKnownStatus(SUCCESS)
                                .batchKey(RELAYER)).payingWith(RELAYER),
                getAliasedAccountInfo(DELEGATING_ACCOUNT).hasDelegationAddress(delegationTargetAddress));
    }

    // 1.2: atomicBatch(type-4 sets delegation on A, invalid transfer) - batch fails
    @HapiTest
    final Stream<DynamicTest> testDelegationSurvivesAtomicBatchRollback() {
        final var delegationTargetAddress = DELEGATION_TARGET.get();
        final var failedBatchTxn = "DelegationInAFailedBatch";
        return hapiTest(
                createFundedAccount(DELEGATING_ACCOUNT),
                getAliasedAccountInfo(DELEGATING_ACCOUNT).hasNoDelegation(),
                atomicBatch(
                        ethereumCall(CONTRACT, "create")
                                .signingWith(DELEGATING_ACCOUNT)
                                .payingWith(RELAYER)
                                .type(EthTransactionType.EIP7702)
                                .addSenderCodeDelegationWithSpecNonce(delegationTargetAddress)
                                .gasLimit(GAS_LIMIT_2M)
                                .via(failedBatchTxn)
                                .batchKey(RELAYER),
                        cryptoTransfer(TokenMovement.movingHbar(ONE_HBAR)
                                .between(INSUFFICIENT_BALANCE_ACCOUNT, RELAYER))
                                .hasKnownStatus(INSUFFICIENT_ACCOUNT_BALANCE)
                                .batchKey(RELAYER))
                        .payingWith(RELAYER)
                        .via(failedBatchTxn)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),

                // TODO (dsinyakov): switch to below assert when atomic batch behavior is fixed
                // getAliasedAccountInfo(DELEGATING_ACCOUNT).hasDelegationAddress(delegationTargetAddress)
                getAliasedAccountInfo(DELEGATING_ACCOUNT).hasNoDelegation());
    }

    // 1.3: atomicBatch(type-4 sets delegation + calls contract that reverts) - batch fails due to CONTRACT_REVERT_EXECUTED
    @HapiTest
    final Stream<DynamicTest> testDelegationSurvivesRevertingType4InAtomicBatch() {
        final var delegationTargetAddress = DELEGATION_TARGET.get();
        final var type4Txn = "DelegationWithRevertInBatch";
        return hapiTest(
                createFundedAccount(DELEGATING_ACCOUNT),
                getAliasedAccountInfo(DELEGATING_ACCOUNT).hasNoDelegation(),
                atomicBatch(
                        ethereumCall(REVERTING_CONTRACT, "revertWithRevertReason")
                                .signingWith(DELEGATING_ACCOUNT)
                                .payingWith(RELAYER)
                                .type(EthTransactionType.EIP7702)
                                .addSenderCodeDelegationWithSpecNonce(delegationTargetAddress)
                                .gasLimit(GAS_LIMIT_2M)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED)
                                .via(type4Txn)
                                .batchKey(RELAYER))
                        .payingWith(RELAYER)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),

                // TODO (dsinyakov): switch to below assert when atomic batch behavior is fixed
                // getAliasedAccountInfo(DELEGATING_ACCOUNT).hasDelegationAddress(delegationTargetAddress)
                getAliasedAccountInfo(DELEGATING_ACCOUNT).hasNoDelegation());
    }

    // 1.4: atomicBatch(invalid transfer, type-4 sets delegation on A) - batch fails before type-4 tx is dispatched
    @HapiTest
    final Stream<DynamicTest> testNoDelegationWhenBatchFailsBeforeType4TxDispatched() {
        final var delegationTargetAddress = DELEGATION_TARGET.get();
        final var batchTxn = "BatchFailsBeforeType4";
        return hapiTest(
                createFundedAccount(DELEGATING_ACCOUNT),
                getAliasedAccountInfo(DELEGATING_ACCOUNT).hasNoDelegation(),
                atomicBatch(
                        cryptoTransfer(TokenMovement.movingHbar(ONE_HBAR)
                                .between(INSUFFICIENT_BALANCE_ACCOUNT, RELAYER))
                                .hasKnownStatus(INSUFFICIENT_ACCOUNT_BALANCE)
                                .batchKey(RELAYER),
                        ethereumCall(CONTRACT, "create")
                                .signingWith(DELEGATING_ACCOUNT)
                                .payingWith(RELAYER)
                                .type(EthTransactionType.EIP7702)
                                .addSenderCodeDelegationWithSpecNonce(delegationTargetAddress)
                                .gasLimit(GAS_LIMIT_2M)
                                .batchKey(RELAYER))
                        .payingWith(RELAYER)
                        .via(batchTxn)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),

                getAliasedAccountInfo(DELEGATING_ACCOUNT).hasNoDelegation());
    }

    // 2.1: atomicBatch(CryptoCreate(A), type-4 delegates A) - batch succeeds
    @HapiTest
    final Stream<DynamicTest> testAtomicBatchCryptoCreateThenType4DelegatesInSameBatch() {
        final var delegationTargetAddress = DELEGATION_TARGET.get();
        final var accountInBatch = DELEGATING_ACCOUNT + "CreateThenDelegateInBatch";
        final var type4Txn = "type4DelegatesNewAccountInBatch";
        return hapiTest(
                newKeyNamed(accountInBatch).shape(SECP_256K1_SHAPE),
                atomicBatch(
                        cryptoCreate(accountInBatch)
                                .key(accountInBatch)
                                .withMatchingEvmAddress()
                                .balance(ONE_HUNDRED_HBARS)
                                .batchKey(RELAYER),
                        ethereumCall(CONTRACT, "create")
                                .signingWith(accountInBatch)
                                .payingWith(RELAYER)
                                .type(EthTransactionType.EIP7702)
                                .addSenderCodeDelegationWithSpecNonce(delegationTargetAddress)
                                .gasLimit(GAS_LIMIT_2M)
                                .via(type4Txn)
                                .batchKey(RELAYER))
                        .payingWith(RELAYER),
                // Account A exists and delegation is set
                getAliasedAccountInfo(accountInBatch).hasDelegationAddress(delegationTargetAddress));
    }

    // 2.2: atomicBatch(CryptoCreate(A), type-4 delegates A, invalid transfer) - batch fails
    @HapiTest
    final Stream<DynamicTest> testAtomicBatchCryptoCreateAndType4DelegateRolledBackOnFailure() {
        final var delegationTargetAddress = DELEGATION_TARGET.get();
        final var accountInBatch = DELEGATING_ACCOUNT + "CreateThenDelegateRollback";
        final var batchTxn = "batchCreateDelegateRollback";
        return hapiTest(
                newKeyNamed(accountInBatch).shape(SECP_256K1_SHAPE),
                createFundedAccount(DELEGATING_ACCOUNT),
                atomicBatch(
                        cryptoCreate(accountInBatch)
                                .key(accountInBatch)
                                .withMatchingEvmAddress()
                                .balance(ONE_HUNDRED_HBARS)
                                .batchKey(RELAYER),
                        ethereumCall(CONTRACT, "create")
                                .signingWith(DELEGATING_ACCOUNT)
                                .payingWith(RELAYER)
                                .type(EthTransactionType.EIP7702)
                                .addCodeDelegationWithSpecNonce(delegationTargetAddress, accountInBatch)
                                .gasLimit(GAS_LIMIT_2M)
                                .batchKey(RELAYER),
                        cryptoTransfer(TokenMovement.movingHbar(ONE_HBAR)
                                .between(INSUFFICIENT_BALANCE_ACCOUNT, RELAYER))
                                .hasKnownStatus(INSUFFICIENT_ACCOUNT_BALANCE)
                                .batchKey(RELAYER))
                        .payingWith(RELAYER)
                        .via(batchTxn)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                // Account A rolled back (does not exist). No delegation persisted.
                getAliasedAccountInfo(accountInBatch)
                        .hasCostAnswerPrecheck(ResponseCodeEnum.INVALID_ACCOUNT_ID)

                // TODO (dsinyakov): Add below assert when atomic batch delegation persistence is fixed
                // When delegation survives rollback, account A should exist with delegation set
                // getAliasedAccountInfo(accountInBatch).hasDelegationAddress(delegationTargetAddress)
        );
    }

    // 2.3: atomicBatch(CryptoCreate(A, initialDelegation=D1), type-4 updates A delegation to D2) - batch succeeds
    @HapiTest
    final Stream<DynamicTest> testAtomicBatchCryptoCreateSetsDelegationThenType4UpdatesIt() {
        final var initialDelegationAddress = ByteString.copyFrom(explicitFromHeadlong(DELEGATION_TARGET.get()));
        final var delegationTargetAddress = DELEGATION_TARGET.get();
        final var accountInBatch = DELEGATING_ACCOUNT + "CreateThenUpdateInBatch";
        final var type4Txn = "type4UpdatesDelegationInBatch";
        return hapiTest(
                newKeyNamed(accountInBatch).shape(SECP_256K1_SHAPE),
                atomicBatch(
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
                                .gasLimit(GAS_LIMIT_2M)
                                .via(type4Txn)
                                .batchKey(RELAYER))
                        .payingWith(RELAYER),
                // Type 4 update should override the delegation set by native create in the same batch.
                getAliasedAccountInfo(accountInBatch).hasDelegationAddress(delegationTargetAddress));
    }

    // 3.1: Account A exists with no delegation. atomicBatch(type-4 delegates to A, invalid transfer) - batch fails
    @HapiTest
    final Stream<DynamicTest> testExistingAccountDelegationSurvivesRollback() {
        final var delegationTargetAddress = DELEGATION_TARGET.get();
        final var sender = DELEGATING_ACCOUNT + "SenderFor3_1";
        final var authorityAccount = DELEGATING_ACCOUNT + "Authority3_1";
        final var batchTxn = "batchExistingAccountDelegation";
        return hapiTest(
                createFundedAccount(sender),
                createFundedAccount(authorityAccount),
                getAliasedAccountInfo(authorityAccount).hasNoDelegation(),
                atomicBatch(
                        ethereumCall(CONTRACT, "create")
                                .signingWith(sender)
                                .payingWith(RELAYER)
                                .type(EthTransactionType.EIP7702)
                                .addCodeDelegationWithSpecNonce(delegationTargetAddress, authorityAccount)
                                .gasLimit(GAS_LIMIT_2M)
                                .batchKey(RELAYER),
                        cryptoTransfer(TokenMovement.movingHbar(ONE_HBAR)
                                .between(INSUFFICIENT_BALANCE_ACCOUNT, RELAYER))
                                .hasKnownStatus(INSUFFICIENT_ACCOUNT_BALANCE)
                                .batchKey(RELAYER))
                        .payingWith(RELAYER)
                        .via(batchTxn)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),

                // TODO (dsinyakov): switch to below assert when atomic batch delegation persistence is fixed
                // Delegation to A should survive rollback
                // getAliasedAccountInfo(authorityAccount).hasDelegationAddress(delegationTargetAddress)
                getAliasedAccountInfo(authorityAccount).hasNoDelegation());
    }

    // 3.2: Account A exists with delegation D1. atomicBatch(type-4 changes A delegation to D2, invalid transfer) - batch fails
    @HapiTest
    final Stream<DynamicTest> testExistingDelegationUpdatedByType4SurvivesRollback() {
        final var d1 = DELEGATION_TARGET.get();
        final var d2 = DELEGATION_TARGET_2.get();
        final var sender = DELEGATING_ACCOUNT + "SenderFor3_2";
        final var authorityAccount = DELEGATING_ACCOUNT + "Authority3_2";
        final var batchTxn = "batchUpdateDelegationRollback";
        return hapiTest(
                createFundedAccount(sender),
                createFundedAccountWithDelegation(authorityAccount, d1),
                getAliasedAccountInfo(authorityAccount).hasDelegationAddress(d1),
                atomicBatch(
                        ethereumCall(CONTRACT, "create")
                                .signingWith(sender)
                                .payingWith(RELAYER)
                                .type(EthTransactionType.EIP7702)
                                .addCodeDelegationWithSpecNonce(d2, authorityAccount)
                                .gasLimit(GAS_LIMIT_2M)
                                .batchKey(RELAYER),
                        cryptoTransfer(TokenMovement.movingHbar(ONE_HBAR)
                                .between(INSUFFICIENT_BALANCE_ACCOUNT, RELAYER))
                                .hasKnownStatus(INSUFFICIENT_ACCOUNT_BALANCE)
                                .batchKey(RELAYER))
                        .payingWith(RELAYER)
                        .via(batchTxn)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),

                // TODO (dsinyakov): switch to below assert when atomic batch delegation persistence is fixed
                // Delegation on A should be D2 (survives rollback). Original D1 is NOT restored.
                // getAliasedAccountInfo(authorityAccount).hasDelegationAddress(d2)
                getAliasedAccountInfo(authorityAccount).hasDelegationAddress(d1));
    }

    // 4.1: Account A has delegation. atomicBatch(type-4 sets A delegation to zero address, valid transfer) - batch succeeds
    @HapiTest
    final Stream<DynamicTest> testDelegationClearedByZeroAddress() {
        final var d1 = DELEGATION_TARGET.get();
        final var sender = DELEGATING_ACCOUNT + "SenderFor4_2";
        final var authorityAccount = DELEGATING_ACCOUNT + "Authority4_2";
        final var batchTxn = "batchClearDelegationRollback";
        return hapiTest(
                createFundedAccount(sender),
                createFundedAccountWithDelegation(authorityAccount, d1),
                getAliasedAccountInfo(authorityAccount).hasDelegationAddress(d1),
                atomicBatch(
                        ethereumCall(CONTRACT, "create")
                                .signingWith(sender)
                                .payingWith(RELAYER)
                                .type(EthTransactionType.EIP7702)
                                .addCodeDelegationWithSpecNonce(ZERO_ADDRESS, authorityAccount)
                                .gasLimit(GAS_LIMIT_2M)
                                .batchKey(RELAYER),
                        cryptoTransfer(TokenMovement.movingHbar(ONE_HBAR)
                                .between(ACCOUNT_WITH_BALANCE, RELAYER))
                                .hasKnownStatus(SUCCESS)
                                .batchKey(RELAYER))
                        .payingWith(RELAYER)
                        .via(batchTxn)
                        .hasKnownStatus(SUCCESS),

                getAliasedAccountInfo(authorityAccount).hasNoDelegation());
    }

    // 4.2: Account A has delegation. atomicBatch(type-4 sets A delegation to zero address, invalid transfer) - batch fails
    @HapiTest
    final Stream<DynamicTest> testDelegationClearedByZeroAddressSurvivesRollback() {
        final var d1 = DELEGATION_TARGET.get();
        final var sender = DELEGATING_ACCOUNT + "Sender";
        final var authorityAccount = DELEGATING_ACCOUNT + "Authority";
        final var batchTxn = "batchClearDelegationRollback";
        return hapiTest(
                createFundedAccount(sender),
                createFundedAccountWithDelegation(authorityAccount, d1),
                getAliasedAccountInfo(authorityAccount).hasDelegationAddress(d1),
                atomicBatch(
                        ethereumCall(CONTRACT, "create")
                                .signingWith(sender)
                                .payingWith(RELAYER)
                                .type(EthTransactionType.EIP7702)
                                .addCodeDelegationWithSpecNonce(ZERO_ADDRESS, authorityAccount)
                                .gasLimit(GAS_LIMIT_2M)
                                .batchKey(RELAYER),
                        cryptoTransfer(TokenMovement.movingHbar(ONE_HBAR)
                                .between(INSUFFICIENT_BALANCE_ACCOUNT, RELAYER))
                                .hasKnownStatus(INSUFFICIENT_ACCOUNT_BALANCE)
                                .batchKey(RELAYER))
                        .payingWith(RELAYER)
                        .via(batchTxn)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),

                // TODO (dsinyakov): switch to below assert when atomic batch delegation persistence is fixed
                // Delegation clearing should survive rollback
                // getAliasedAccountInfo(authorityAccount).hasNoDelegation()
                getAliasedAccountInfo(authorityAccount).hasDelegationAddress(d1));
    }

    // 6.1: atomicBatch(type-4 with 2 valid + 2 invalid auth entries) - batch succeeds
    @HapiTest
    final Stream<DynamicTest> testAtomicBatchType4PartialCommitAcrossAccountsWithInvalidAuthorization() {
        final var delegationAddress = ByteString.copyFrom(explicitFromHeadlong(DELEGATION_TARGET.get()));
        final var delegatingAccount1 = DELEGATING_ACCOUNT + "Batch1";
        final var delegatingAccount2 = DELEGATING_ACCOUNT + "Batch2";
        final var delegatingAccount3 = DELEGATING_ACCOUNT + "Batch3";
        final var delegationTargetAddress = DELEGATION_TARGET.get();
        final var partialCommitTxn = "batchType4PartialCommitAcrossAccounts";
        return hapiTest(
                // Split into two calls to avoid MAX_CHILD_RECORDS_EXCEEDED
                createHollowAccounts(DELEGATING_ACCOUNT, delegatingAccount1),
                createHollowAccounts(delegatingAccount2, delegatingAccount3),
                cryptoCreate(CRYPTO_CREATE_DELEGATING_ACCOUNT).key(RELAYER).balance(ONE_HUNDRED_HBARS),
                getAccountInfo(CRYPTO_CREATE_DELEGATING_ACCOUNT).hasNoDelegation(),
                atomicBatch(
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
                                .gasLimit(GAS_LIMIT_2M)
                                .hasKnownStatus(SUCCESS)
                                .via(partialCommitTxn)
                                .batchKey(RELAYER))
                        .payingWith(RELAYER)
                        .hasKnownStatus(SUCCESS),
                // Atomic batch commits back all delegations.
                getAccountInfo(CRYPTO_CREATE_DELEGATING_ACCOUNT).hasDelegationAddress(DELEGATION_TARGET.get()),
                getAliasedAccountInfo(DELEGATING_ACCOUNT).hasDelegationAddress(DELEGATION_TARGET.get()),
                getAliasedAccountInfo(delegatingAccount1).hasDelegationAddress(DELEGATION_TARGET.get()),
                getAliasedAccountInfo(delegatingAccount2).hasNoDelegation(),
                getAliasedAccountInfo(delegatingAccount3).hasNoDelegation());
    }

    // 6.2: atomicBatch(type-4 with 2 valid + 2 invalid auth entries, invalid transfer) - batch fails
    @HapiTest
    final Stream<DynamicTest> testAtomicBatchType4PartialCommitIsRolledBackOnInnerTxFailureAcrossAccounts() {
        final var initialDelegationAddress = ByteString.copyFrom(explicitFromHeadlong(DELEGATION_TARGET.get()));
        final var delegatingAccount1 = DELEGATING_ACCOUNT + "Batch1";
        final var delegatingAccount2 = DELEGATING_ACCOUNT + "Batch2";
        final var delegatingAccount3 = DELEGATING_ACCOUNT + "Batch3";
        final var delegationTargetAddress = DELEGATION_TARGET.get();
        final var partialCommitTxn = "batchType4PartialCommitAcrossAccounts";
        return hapiTest(
                // Split into two calls to avoid MAX_CHILD_RECORDS_EXCEEDED
                createHollowAccounts(DELEGATING_ACCOUNT, delegatingAccount1),
                createHollowAccounts(delegatingAccount2, delegatingAccount3),
                cryptoCreate(CRYPTO_CREATE_DELEGATING_ACCOUNT).key(RELAYER).balance(ONE_HUNDRED_HBARS),
                getAccountInfo(CRYPTO_CREATE_DELEGATING_ACCOUNT).hasNoDelegation(),
                atomicBatch(
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
                                .gasLimit(GAS_LIMIT_2M)
                                .hasKnownStatus(ResponseCodeEnum.REVERTED_SUCCESS)
                                .via(partialCommitTxn)
                                .batchKey(RELAYER),
                        cryptoTransfer(TokenMovement.movingHbar(ONE_HBAR)
                                .between(INSUFFICIENT_BALANCE_ACCOUNT, RELAYER))
                                .hasKnownStatus(INSUFFICIENT_ACCOUNT_BALANCE)
                                .batchKey(RELAYER))
                        .payingWith(RELAYER)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),

                // TODO (dsinyakov): switch to below asserts when atomic batch delegation persistence is fixed
                // CryptoUpdate delegation should be rolled back
                // getAccountInfo(CRYPTO_CREATE_DELEGATING_ACCOUNT).hasNoDelegation(),
                // Valid type-4 delegations (sender + delegatingAccount1) should survive rollback
                // getAliasedAccountInfo(DELEGATING_ACCOUNT).hasDelegationAddress(delegationTargetAddress),
                // getAliasedAccountInfo(delegatingAccount1).hasDelegationAddress(delegationTargetAddress),
                // Invalid auth entries (wrong nonce) should still be skipped
                // getAliasedAccountInfo(delegatingAccount2).hasNoDelegation(),
                // getAliasedAccountInfo(delegatingAccount3).hasNoDelegation()

                // Current behavior: all delegation effects rolled back
                getAccountInfo(CRYPTO_CREATE_DELEGATING_ACCOUNT).hasNoDelegation(),
                getAliasedAccountInfo(DELEGATING_ACCOUNT).hasNoDelegation(),
                getAliasedAccountInfo(delegatingAccount1).hasNoDelegation(),
                getAliasedAccountInfo(delegatingAccount2).hasNoDelegation(),
                getAliasedAccountInfo(delegatingAccount3).hasNoDelegation());
    }

    // 8.1: atomicBatch(type-4 tx, valid transfer) - batch succeeds. Nonces incremented.
    @HapiTest
    final Stream<DynamicTest> testAtomicBatchType4NoncesOnSuccess() {
        final var sender = DELEGATING_ACCOUNT + "GasSender";
        final var authAccount1 = DELEGATING_ACCOUNT + "GasAuth1";
        final var authAccount2 = DELEGATING_ACCOUNT + "GasAuth2";
        final var delegationTargetAddress = DELEGATION_TARGET.get();
        final var type4Txn = "type4GasSuccess";
        final var senderNonceBefore = new AtomicLong();
        final var auth1NonceBefore = new AtomicLong();
        final var auth2NonceBefore = new AtomicLong();
        final var senderNonceAfter = new AtomicLong();
        final var auth1NonceAfter = new AtomicLong();
        final var auth2NonceAfter = new AtomicLong();
        return hapiTest(
                createHollowAccounts(sender, authAccount1),
                createHollowAccounts(authAccount2),
                getAliasedAccountInfo(sender).exposingEthereumNonceTo(senderNonceBefore::set),
                getAliasedAccountInfo(authAccount1).exposingEthereumNonceTo(auth1NonceBefore::set),
                getAliasedAccountInfo(authAccount2).exposingEthereumNonceTo(auth2NonceBefore::set),
                atomicBatch(
                        ethereumCall(CONTRACT, "create")
                                .signingWith(sender)
                                .payingWith(RELAYER)
                                .type(EthTransactionType.EIP7702)
                                .addSenderCodeDelegationWithSpecNonce(delegationTargetAddress)
                                .addCodeDelegationWithSpecNonce(delegationTargetAddress, authAccount1)
                                .addCodeDelegationWithSpecNonce(delegationTargetAddress, authAccount2)
                                .gasLimit(GAS_LIMIT_2M)
                                .via(type4Txn)
                                .batchKey(RELAYER))
                        .payingWith(RELAYER)
                        .hasKnownStatus(SUCCESS),
                getAliasedAccountInfo(sender).exposingEthereumNonceTo(senderNonceAfter::set),
                getAliasedAccountInfo(authAccount1).exposingEthereumNonceTo(auth1NonceAfter::set),
                getAliasedAccountInfo(authAccount2).exposingEthereumNonceTo(auth2NonceAfter::set),
                assertionsHold((spec, opLog) -> {
                    assertEquals(
                            senderNonceBefore.get() + 2,
                            senderNonceAfter.get(),
                            "Sender nonce should increment by 2 (tx + auth)");
                    assertEquals(
                            auth1NonceBefore.get() + 1,
                            auth1NonceAfter.get(),
                            "Auth1 nonce should increment by 1 (auth only)");
                    assertEquals(
                            auth2NonceBefore.get() + 1,
                            auth2NonceAfter.get(),
                            "Auth2 nonce should increment by 1 (auth only)");
                }),
                getAliasedAccountInfo(sender).hasDelegationAddress(delegationTargetAddress),
                getAliasedAccountInfo(authAccount1).hasDelegationAddress(delegationTargetAddress),
                getAliasedAccountInfo(authAccount2).hasDelegationAddress(delegationTargetAddress));
    }

    // 8.2: atomicBatch(type-4 tx, invalid transfer) - batch fails. Nonces and delegations should survive.
    @HapiTest
    final Stream<DynamicTest> testAtomicBatchType4NoncesOnRollback() {
        final var sender = DELEGATING_ACCOUNT + "GasRollbackSender";
        final var authAccount1 = DELEGATING_ACCOUNT + "GasRollbackAuth1";
        final var authAccount2 = DELEGATING_ACCOUNT + "GasRollbackAuth2";
        final var delegationTargetAddress = DELEGATION_TARGET.get();
        final var type4Txn = "type4GasRollback";
        final var batchTxn = "batchGasRollback";
        final var senderNonceBefore = new AtomicLong();
        final var auth1NonceBefore = new AtomicLong();
        final var auth2NonceBefore = new AtomicLong();
        final var senderNonceAfter = new AtomicLong();
        final var auth1NonceAfter = new AtomicLong();
        final var auth2NonceAfter = new AtomicLong();
        return hapiTest(
                createHollowAccounts(sender, authAccount1),
                createHollowAccounts(authAccount2),
                getAliasedAccountInfo(sender).exposingEthereumNonceTo(senderNonceBefore::set),
                getAliasedAccountInfo(authAccount1).exposingEthereumNonceTo(auth1NonceBefore::set),
                getAliasedAccountInfo(authAccount2).exposingEthereumNonceTo(auth2NonceBefore::set),
                atomicBatch(
                        ethereumCall(CONTRACT, "create")
                                .signingWith(sender)
                                .payingWith(RELAYER)
                                .type(EthTransactionType.EIP7702)
                                .addSenderCodeDelegationWithSpecNonce(delegationTargetAddress)
                                .addCodeDelegationWithSpecNonce(delegationTargetAddress, authAccount1)
                                .addCodeDelegationWithSpecNonce(delegationTargetAddress, authAccount2)
                                .gasLimit(GAS_LIMIT_2M)
                                .via(type4Txn)
                                .batchKey(RELAYER),
                        cryptoTransfer(TokenMovement.movingHbar(ONE_HBAR)
                                .between(INSUFFICIENT_BALANCE_ACCOUNT, RELAYER))
                                .hasKnownStatus(INSUFFICIENT_ACCOUNT_BALANCE)
                                .batchKey(RELAYER))
                        .payingWith(RELAYER)
                        .via(batchTxn)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),

                getAliasedAccountInfo(sender).exposingEthereumNonceTo(senderNonceAfter::set),
                getAliasedAccountInfo(authAccount1).exposingEthereumNonceTo(auth1NonceAfter::set),
                getAliasedAccountInfo(authAccount2).exposingEthereumNonceTo(auth2NonceAfter::set),
                assertionsHold((spec, opLog) -> {
                    assertEquals(
                            senderNonceBefore.get() + 2,
                            senderNonceAfter.get(),
                            "Sender nonce should increment by 2 (tx + auth)");
                    // TODO (dsinyakov): add below asserts when atomic batch nonce persistence if fixed
//                            assertEquals(
//                                    auth1NonceBefore.get() + 1,
//                                    auth1NonceAfter.get(),
//                                    "Auth1 nonce should increment by 1 (auth only)");
//                            assertEquals(
//                                    auth2NonceBefore.get() + 1,
//                                    auth2NonceAfter.get(),
//                                    "Auth2 nonce should increment by 1 (auth only)");
                }),

                // TODO (dsinyakov): switch to below asserts when atomic batch delegation persistence is fixed
                // Delegations and nonces should survive rollback
                // getAliasedAccountInfo(sender).hasDelegationAddress(delegationTargetAddress),
                // getAliasedAccountInfo(authAccount1).hasDelegationAddress(delegationTargetAddress),
                // getAliasedAccountInfo(authAccount2).hasDelegationAddress(delegationTargetAddress)

                // Current behavior: delegations rolled back
                getAliasedAccountInfo(sender).hasNoDelegation(),
                getAliasedAccountInfo(authAccount1).hasNoDelegation(),
                getAliasedAccountInfo(authAccount2).hasNoDelegation());
    }

    // 9.1: atomicBatch(type-4 delegates A, valid transfer) - batch succeeds.
    @HapiTest
    final Stream<DynamicTest> testTx4GasChargesOnSuccessfulBatch() {
        // Intrinsic gas components
        final long TX_BASE_COST = 21_000L;
        final long CALLDATA_GAS = 4 * 16L; // create() selector: 4 non-zero bytes
        final long CODE_DELEGATION_GAS = 25_000L; // 1 auth entry (EIP-7702)
        final long EXPECTED_INTRINSIC_GAS = TX_BASE_COST + CALLDATA_GAS + CODE_DELEGATION_GAS;

        final var sender = DELEGATING_ACCOUNT + "GasChargeSender";
        final var delegationTargetAddress = DELEGATION_TARGET.get();
        final var type4Txn = "type4GasChargeSuccess";

        final var senderBalanceBefore = new AtomicLong();
        final var senderBalanceAfter = new AtomicLong();

        return hapiTest(
                createFundedAccount(sender),
                getAccountBalance(sender).exposingBalanceTo(senderBalanceBefore::set),
                atomicBatch(
                        ethereumCall(CONTRACT, "create")
                                .signingWith(sender)
                                .payingWith(RELAYER)
                                .type(EthTransactionType.EIP7702)
                                .addSenderCodeDelegationWithSpecNonce(delegationTargetAddress)
                                .gasLimit(GAS_LIMIT_2M)
                                .via(type4Txn)
                                .batchKey(RELAYER),
                        cryptoTransfer(TokenMovement.movingHbar(ONE_HBAR)
                                .between(ACCOUNT_WITH_BALANCE, RELAYER))
                                .hasKnownStatus(SUCCESS)
                                .batchKey(RELAYER))
                        .payingWith(RELAYER)
                        .hasKnownStatus(SUCCESS),
                getAccountBalance(sender).exposingBalanceTo(senderBalanceAfter::set),
                assertionsHold((spec, opLog) -> {
                    final var gasPriceTinybars = spec.ratesProvider().currentTinybarGasPrice();

                    final var type4Record = getTxnRecord(type4Txn);
                    allRunFor(spec, type4Record);

                    final var type4Fee = type4Record.getResponseRecord().getTransactionFee();
                    final var gasUsed = type4Record.getResponseRecord()
                            .getContractCallResult().getGasUsed();
                    final var expectedGasCharge = gasUsed * gasPriceTinybars;
                    final var senderDelta = senderBalanceBefore.get() - senderBalanceAfter.get();

                    // gasUsed must exceed intrinsic gas (execution of create() costs extra)
                    assertTrue(gasUsed > EXPECTED_INTRINSIC_GAS,
                            "gasUsed must exceed intrinsic gas (create() deploys a contract)");
                    // Sender pays exactly gasUsed * gasPrice (EVM gas)
                    assertEquals(expectedGasCharge, senderDelta,
                            "Sender must be charged gasUsed * gasPriceTinybars");
                    // The type4 fee in the record should match the sender's balance change
                    assertEquals(type4Fee, senderDelta,
                            "Type4 record fee must equal sender balance change");

                }));
    }

    // 9.1.1: Compare sender gas charges between successful and failed batch.
    @HapiTest
    final Stream<DynamicTest> testSenderGasChargesSameOnSuccessAndRollback() {
        final var delegationTargetAddress = DELEGATION_TARGET.get();

        final var successSender = DELEGATING_ACCOUNT + "GasCompareSuccess";
        final var rollbackSender = DELEGATING_ACCOUNT + "GasCompareRollback";
        final var successType4Txn = "type4GasCompareSuccess";
        final var rollbackType4Txn = "type4GasCompareRollback";

        final var successSenderBefore = new AtomicLong();
        final var successSenderAfter = new AtomicLong();
        final var rollbackSenderBefore = new AtomicLong();
        final var rollbackSenderAfter = new AtomicLong();

        return hapiTest(
                createFundedAccount(successSender),
                createFundedAccount(rollbackSender),

                // Rollback
                getAccountBalance(rollbackSender).exposingBalanceTo(rollbackSenderBefore::set),
                atomicBatch(
                        ethereumCall(CONTRACT, "create")
                                .signingWith(rollbackSender)
                                .payingWith(RELAYER)
                                .type(EthTransactionType.EIP7702)
                                .addSenderCodeDelegationWithSpecNonce(delegationTargetAddress)
                                .gasLimit(GAS_LIMIT_2M)
                                .via(rollbackType4Txn)
                                .batchKey(RELAYER),
                        cryptoTransfer(TokenMovement.movingHbar(ONE_HBAR)
                                .between(INSUFFICIENT_BALANCE_ACCOUNT, RELAYER))
                                .hasKnownStatus(INSUFFICIENT_ACCOUNT_BALANCE)
                                .batchKey(RELAYER))
                        .payingWith(RELAYER)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                getAccountBalance(rollbackSender).exposingBalanceTo(rollbackSenderAfter::set),

                // Success
                getAccountBalance(successSender).exposingBalanceTo(successSenderBefore::set),
                atomicBatch(
                        ethereumCall(CONTRACT, "create")
                                .signingWith(successSender)
                                .payingWith(RELAYER)
                                .type(EthTransactionType.EIP7702)
                                .addSenderCodeDelegationWithSpecNonce(delegationTargetAddress)
                                .gasLimit(GAS_LIMIT_2M)
                                .via(successType4Txn)
                                .batchKey(RELAYER),
                        cryptoTransfer(TokenMovement.movingHbar(ONE_HBAR)
                                .between(ACCOUNT_WITH_BALANCE, RELAYER))
                                .hasKnownStatus(SUCCESS)
                                .batchKey(RELAYER))
                        .payingWith(RELAYER)
                        .hasKnownStatus(SUCCESS),
                getAccountBalance(successSender).exposingBalanceTo(successSenderAfter::set),

                // Compare gas charges between success and rollback paths
                assertionsHold((spec, opLog) -> {
                    final var gasPriceTinybars = spec.ratesProvider().currentTinybarGasPrice();

                    final var successRecord = getTxnRecord(successType4Txn);
                    final var rollbackRecord = getTxnRecord(rollbackType4Txn);
                    allRunFor(spec, successRecord, rollbackRecord);

                    final var successGasUsed = successRecord.getResponseRecord()
                            .getContractCallResult().getGasUsed();
                    final var rollbackGasUsed = rollbackRecord.getResponseRecord()
                            .getContractCallResult().getGasUsed();

                    final var successSenderDelta = successSenderBefore.get() - successSenderAfter.get();
                    final var rollbackSenderDelta = rollbackSenderBefore.get() - rollbackSenderAfter.get();

                    final var expectedSuccessCharge = successGasUsed * gasPriceTinybars;
                    final var expectedRollbackCharge = rollbackGasUsed * gasPriceTinybars;

                    assertEquals(successGasUsed, rollbackGasUsed,
                            "gasUsed must be identical for same contract call on fresh contracts");
                    // Both paths should charge the sender gasUsed * gasPrice
                    assertEquals(expectedSuccessCharge, successSenderDelta,
                            "Success sender charge must equal gasUsed * gasPriceTinybars");
                    // TODO(dsinyakov): add below assert when atomic batch when issue with replay of fees for atomic
                    //  batch is fixed
                    // assertEquals(expectedRollbackCharge, rollbackSenderDelta,
                    //        "Rollback sender charge must equal gasUsed * gasPriceTinybars");
                }));
    }

    // 9.2: atomicBatch(CryptoCreate(A), type-4 delegates A, invalid transfer) - batch fails.
    // Gas charged for all inner txs despite rollback. Account creation fee for CryptoCreate
    // correctly replayed despite account being rolled back.
    @HapiTest
    final Stream<DynamicTest> testGasAndFeesChargedOnRollbackWithCryptoCreate() {
        final var delegationTargetAddress = DELEGATION_TARGET.get();
        final var rollbackPayer = DELEGATING_ACCOUNT + "GasFeesPayer";
        final var payer = DELEGATING_ACCOUNT + "RollbackGasFeePayer";
        final var accountInBatchRollback = DELEGATING_ACCOUNT + "AccountInBatchRollback";
        final var accountInBatch = DELEGATING_ACCOUNT + "AccountInBatch";
        final var type4Txn = "type4GasFee";
        final var type4TxnRollback = "type4GasFeeRollback";
        final var batchTxnRollback = "txnRecordRollback";
        final var batchTxn = "txnRecord";
        final var cryptoCreateTxnRollback = "cryptoCreateGasFeeRollback";
        final var cryptoCreateTxn = "cryptoCreateGasFee";

        final var rollbackPayerBalanceBefore = new AtomicLong();
        final var rollbackPayerBalanceAfter = new AtomicLong();
        final var payerBalanceBefore = new AtomicLong();
        final var payerBalanceAfter = new AtomicLong();
        final var relayerBalanceBefore = new AtomicLong();
        final var relayerBalanceAfterRollback = new AtomicLong();
        final var relayerBalanceAfter = new AtomicLong();

        return hapiTest(
                newKeyNamed(accountInBatch).shape(SECP_256K1_SHAPE),
                newKeyNamed(accountInBatchRollback).shape(SECP_256K1_SHAPE),
                createFundedAccount(rollbackPayer),
                createFundedAccount(payer),
                getAccountBalance(RELAYER).exposingBalanceTo(relayerBalanceBefore::set),
                getAccountBalance(rollbackPayer).exposingBalanceTo(rollbackPayerBalanceBefore::set),
                getAccountBalance(payer).exposingBalanceTo(payerBalanceBefore::set),

                // Rollback
                atomicBatch(
                        cryptoCreate(accountInBatchRollback)
                                .key(accountInBatchRollback)
                                .withMatchingEvmAddress()
                                .balance(ONE_HUNDRED_HBARS)
                                .via(cryptoCreateTxnRollback)
                                .batchKey(RELAYER),
                        ethereumCall(CONTRACT, "create")
                                .signingWith(rollbackPayer)
                                .payingWith(RELAYER)
                                .type(EthTransactionType.EIP7702)
                                .addCodeDelegationWithSpecNonce(delegationTargetAddress, accountInBatchRollback)
                                .gasLimit(GAS_LIMIT_2M)
                                .via(type4TxnRollback)
                                .batchKey(RELAYER),
                        cryptoTransfer(TokenMovement.movingHbar(ONE_HBAR)
                                .between(INSUFFICIENT_BALANCE_ACCOUNT, RELAYER))
                                .hasKnownStatus(INSUFFICIENT_ACCOUNT_BALANCE)
                                .batchKey(RELAYER))
                        .payingWith(RELAYER)
                        .via(batchTxnRollback)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),

                getAccountBalance(RELAYER).exposingBalanceTo(relayerBalanceAfterRollback::set),
                getAccountBalance(rollbackPayer).exposingBalanceTo(rollbackPayerBalanceAfter::set),

                // Success
                atomicBatch(
                        cryptoCreate(accountInBatch)
                                .key(accountInBatch)
                                .withMatchingEvmAddress()
                                .balance(ONE_HUNDRED_HBARS)
                                .via(cryptoCreateTxn)
                                .batchKey(RELAYER),
                        ethereumCall(CONTRACT, "create")
                                .signingWith(payer)
                                .payingWith(RELAYER)
                                .type(EthTransactionType.EIP7702)
                                .addCodeDelegationWithSpecNonce(delegationTargetAddress, accountInBatch)
                                .gasLimit(GAS_LIMIT_2M)
                                .via(type4Txn)
                                .batchKey(RELAYER),
                        cryptoTransfer(TokenMovement.movingHbar(ONE_HBAR)
                                .between(ACCOUNT_WITH_BALANCE, RELAYER))
                                .hasKnownStatus(SUCCESS)
                                .batchKey(RELAYER))
                        .payingWith(RELAYER)
                        .via(batchTxn)
                        .hasKnownStatus(SUCCESS),

                getAccountBalance(RELAYER).exposingBalanceTo(relayerBalanceAfter::set),
                getAccountBalance(payer).exposingBalanceTo(payerBalanceAfter::set),


                assertionsHold((spec, opLog) -> {
                    final var gasPriceTinybars = spec.ratesProvider().currentTinybarGasPrice();

                    final var batchRecordRollback = getTxnRecord(batchTxnRollback);
                    final var batchRecord = getTxnRecord(batchTxn);
                    final var type4RecordRollback = getTxnRecord(type4TxnRollback);
                    final var type4Record = getTxnRecord(type4Txn);
                    final var createRecordRollback = getTxnRecord(cryptoCreateTxnRollback);
                    final var createRecord = getTxnRecord(cryptoCreateTxn);
                    allRunFor(spec,
                            batchRecordRollback,
                            batchRecord,
                            type4RecordRollback,
                            type4Record,
                            createRecordRollback,
                            createRecord);

//                    final var createFeeOnRollback = createRecordRollback.getResponseRecord().getTransactionFee();
//                    final var createFee = createRecord.getResponseRecord().getTransactionFee();
//                    final var createGasOnRollback =
//                            createRecordRollback.getResponseRecord().getContractCallResult().getGasUsed();
//                    final var createGas = createRecord.getResponseRecord().getContractCallResult().getGasUsed();
//
//                    assertEquals(createFee, createFeeOnRollback,
//                            "CryptoCreate fee should be the same on success and rollback since account " +
//                                    "creation fee is deterministic");
//
//                    assertEquals(createGas, createGasOnRollback,
//                            "Gas used for CryptoCreate should be the same on success and rollback since " +
//                                    "account creation gas is deterministic");


                    final var batchFeeOnRollback = batchRecordRollback.getResponseRecord().getTransactionFee();
                    final var batchFee = batchRecord.getResponseRecord().getTransactionFee();
                    final var batchGasOnRollback =
                            batchRecordRollback.getResponseRecord().getContractCallResult().getGasUsed();
                    final var batchGas = batchRecord.getResponseRecord().getContractCallResult().getGasUsed();

                    assertEquals(batchFee, batchFeeOnRollback,
                            "Batch fee should be the same on success and rollback since batch execution " +
                                    "should deterministically consume the same amount of gas");

                    assertEquals(batchGas, batchGasOnRollback,
                            "Gas used for batch execution should be the same on success and rollback since " +
                                    "batch execution should deterministically consume the same amount of gas");

                    final var type4FeeOnRollback = type4RecordRollback.getResponseRecord().getTransactionFee();
                    final var type4Fee = type4Record.getResponseRecord().getTransactionFee();
                    final var gasUsedOnRollback = type4RecordRollback.getResponseRecord()
                            .getContractCallResult().getGasUsed();
                    final var gasUsed = type4Record.getResponseRecord().getContractCallResult().getGasUsed();
                    final var expectedGasChargeOnRollback = gasUsedOnRollback * gasPriceTinybars;
                    final var expectedGasCharge = gasUsed * gasPriceTinybars;

                    assertEquals(expectedGasCharge, type4Fee);
                    assertEquals(expectedGasChargeOnRollback, type4FeeOnRollback);

                    assertEquals(gasUsedOnRollback, gasUsed,
                            "gasUsed must be identical for same contract call on fresh contracts");

                    final var rollbackPayerDelta = rollbackPayerBalanceBefore.get() - rollbackPayerBalanceAfter.get();
                    final var payerDelta = payerBalanceBefore.get() - payerBalanceAfter.get();
//                    assertEquals(expectedGasChargeOnRollback, rollbackPayerDelta,
//                            "Rollback payer charge must equal gasUsed * gasPriceTinybars");
                    assertEquals(expectedGasCharge, payerDelta,
                            "Payer charge must equal gasUsed * gasPriceTinybars");

                    final var relayerDeltaOnRollback = relayerBalanceBefore.get() - relayerBalanceAfterRollback.get();
                    final var relayerDelta = relayerBalanceBefore.get() - relayerBalanceAfter.get();

                    assertEquals(batchFee + batchFeeOnRollback, relayerDelta,
                            "Relayer should be charged for batch part");
                }));
    }


    // 10.1: atomicBatch(CryptoUpdate sets delegation on A, invalid transfer) - batch fails
    @HapiTest
    final Stream<DynamicTest> testCryptoUpdateDelegationRolledBackOnBatchFailure() {
        final var delegationAddress = ByteString.copyFrom(explicitFromHeadlong(DELEGATION_TARGET.get()));
        final var accountA = DELEGATING_ACCOUNT + "CryptoUpdateRollback";
        final var batchTxn = "batchCryptoUpdateRollback";
        return hapiTest(
                createFundedAccount(accountA),
                getAliasedAccountInfo(accountA).hasNoDelegation(),
                atomicBatch(
                        cryptoUpdate(accountA)
                                .delegationAddress(delegationAddress)
                                .batchKey(RELAYER),
                        cryptoTransfer(TokenMovement.movingHbar(ONE_HBAR)
                                .between(INSUFFICIENT_BALANCE_ACCOUNT, RELAYER))
                                .hasKnownStatus(INSUFFICIENT_ACCOUNT_BALANCE)
                                .batchKey(RELAYER))
                        .payingWith(RELAYER)
                        .via(batchTxn)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                // CryptoUpdate delegation should be rolled back
                getAliasedAccountInfo(accountA).hasNoDelegation());
    }

    // 10.2: atomicBatch(CryptoUpdate sets delegation on A, type-4 sets delegation on B, invalid transfer)
    @HapiTest
    final Stream<DynamicTest> testAtomicBatchRevertsAllDelegationTransactionsOnInnerTxFailure() {
        final var initialDelegationAddress = ByteString.copyFrom(explicitFromHeadlong(DELEGATION_TARGET.get()));
        final var delegationTargetAddress = DELEGATION_TARGET.get();
        final var batchTxn = "BatchDelegationRollback";
        return hapiTest(
                createHollowAccounts(DELEGATING_ACCOUNT),
                createFundedAccount(CRYPTO_CREATE_DELEGATING_ACCOUNT),
                getAccountInfo(CRYPTO_CREATE_DELEGATING_ACCOUNT).hasNoDelegation(),
                atomicBatch(
                        cryptoUpdate(CRYPTO_CREATE_DELEGATING_ACCOUNT)
                                .delegationAddress(initialDelegationAddress)
                                .batchKey(RELAYER),
                        ethereumCall(CONTRACT, "create")
                                .signingWith(DELEGATING_ACCOUNT)
                                .payingWith(RELAYER)
                                .type(EthTransactionType.EIP7702)
                                .addSenderCodeDelegationWithSpecNonce(delegationTargetAddress)
                                .gasLimit(GAS_LIMIT_2M)
                                .via(DELEGATION_SET)
                                .batchKey(RELAYER),
                        cryptoTransfer(TokenMovement.movingHbar(ONE_HBAR)
                                .between(INSUFFICIENT_BALANCE_ACCOUNT, RELAYER))
                                .hasKnownStatus(INSUFFICIENT_ACCOUNT_BALANCE)
                                .batchKey(RELAYER))
                        .payingWith(RELAYER)
                        .via(batchTxn)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),

                // TODO (dsinyakov): switch to below asserts when atomic batch delegation persistence is fixed
                // CryptoUpdate delegation should be rolled back
                // getAccountInfo(CRYPTO_CREATE_DELEGATING_ACCOUNT).hasNoDelegation(),
                // Type-4 delegation should survive rollback
                // getAliasedAccountInfo(DELEGATING_ACCOUNT).hasDelegationAddress(delegationTargetAddress)

                // Current behavior: all delegation effects rolled back
                getAccountInfo(CRYPTO_CREATE_DELEGATING_ACCOUNT).hasNoDelegation(),
                getAliasedAccountInfo(DELEGATING_ACCOUNT).hasNoDelegation());
    }

    private static SpecOperation createFundedAccount(@NonNull final String name) {
        return blockingOrder(
                newKeyNamed(name).shape(SECP_256K1_SHAPE),
                cryptoCreate(name).key(name).withMatchingEvmAddress().balance(ONE_HUNDRED_HBARS));
    }

    private static SpecOperation createFundedAccountWithDelegation(
            @NonNull final String name, @NonNull final Address delegation) {
        return blockingOrder(
                newKeyNamed(name).shape(SECP_256K1_SHAPE),
                cryptoCreate(name)
                        .key(name)
                        .withMatchingEvmAddress()
                        .balance(ONE_HUNDRED_HBARS)
                        .delegationAddress(ByteString.copyFrom(explicitFromHeadlong(delegation))));
    }

    private static SpecOperation createHollowAccounts(@NonNull final String... names) {
        final var ops = new java.util.ArrayList<SpecOperation>();
        for (final var name : names) {
            ops.add(newKeyNamed(name).shape(SECP_256K1_SHAPE));
        }
        ops.add(cryptoTransfer(TokenMovement.movingHbar(ONE_HUNDRED_HBARS).distributing(GENESIS, names)));
        return blockingOrder(ops.toArray(SpecOperation[]::new));
    }
}
