// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.hips.hip1340;

import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.explicitFromHeadlong;
import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAliasedAccountInfo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.ethereumCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.blockingOrder;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doAdhoc;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
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
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.esaulpaugh.headlong.abi.Address;
import com.google.protobuf.ByteString;
import com.hedera.node.app.hapi.utils.ethereum.EthTxData.EthTransactionType;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.dsl.annotations.Account;
import com.hedera.services.bdd.spec.dsl.annotations.Contract;
import com.hedera.services.bdd.spec.dsl.entities.SpecAccount;
import com.hedera.services.bdd.spec.dsl.entities.SpecContract;
import com.hedera.services.bdd.spec.transactions.token.TokenMovement;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.concurrent.atomic.AtomicLong;
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
    private static final String CODE_DELEGATION_CONTRACT_2 = "CodeDelegationContract2";
    private static final AtomicReference<Address> DELEGATION_TARGET = new AtomicReference<>();
    private static final AtomicReference<Address> DELEGATION_TARGET_2 = new AtomicReference<>();
    private static final String CONTRACT = "CreateTrivial";
    private static final String REVERTING_CONTRACT = "InternalCallee";
    private static final String CRYPTO_CREATE_DELEGATING_ACCOUNT = "CryptoCreateDelegatingAccount";
    private static final String INSUFFICIENT_BALANCE_ACCOUNT = "InsufficientBalanceAccount";
    private static final long GAS_LIMIT_2M = 2_000_000L;

    @Contract(contract = CONTRACT, creationGas = 5_000_000)
    static SpecContract contract;

    @Contract(contract = REVERTING_CONTRACT, creationGas = 5_000_000)
    static SpecContract revertingContract;

    @Account(name = INSUFFICIENT_BALANCE_ACCOUNT)
    static SpecAccount insufficientBalanceAccount;

    @Account(name = RELAYER, tinybarBalance = ONE_MILLION_HBARS)
    static SpecAccount relayer;

    @BeforeAll
    public static void setup(@NonNull final TestLifecycle lifecycle) {
        lifecycle.doAdhoc(
                contract.getInfo(),
                revertingContract.getInfo(),
                insufficientBalanceAccount.getInfo(),
                relayer.getInfo(),
                uploadInitCode(CODE_DELEGATION_CONTRACT),
                contractCreate(CODE_DELEGATION_CONTRACT).exposingAddressTo(DELEGATION_TARGET::set),
                contractCreate(CODE_DELEGATION_CONTRACT_2)
                        .bytecode(CODE_DELEGATION_CONTRACT)
                        .exposingAddressTo(DELEGATION_TARGET_2::set));
    }

    // 1.3: atomicBatch(type-4 sets delegation + calls contract that reverts) - batch fails due to
    // CONTRACT_REVERT_EXECUTED
    @LeakyHapiTest(overrides = {"contracts.codeDelegations.enabled"})
    final Stream<DynamicTest> testDelegationSurvivesRevertingType4InAtomicBatch() {
        final var delegationTargetAddress = DELEGATION_TARGET.get();
        final var delegatingAccount = "DelegatingAccount";
        return hapiTest(
                overriding("contracts.codeDelegations.enabled", "true"),
                createFundedAccount(delegatingAccount),
                getAliasedAccountInfo(delegatingAccount).hasNoDelegation(),
                atomicBatch(ethereumCall(REVERTING_CONTRACT, "revertWithRevertReason")
                                .signingWith(delegatingAccount)
                                .payingWith(RELAYER)
                                .type(EthTransactionType.EIP7702)
                                .addSenderCodeDelegationWithSpecNonce(delegationTargetAddress)
                                .gasLimit(GAS_LIMIT_2M)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED)
                                .batchKey(RELAYER))
                        .payingWith(RELAYER)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                getAliasedAccountInfo(delegatingAccount).hasDelegationAddress(delegationTargetAddress));
    }

    // 1.4: atomicBatch(invalid transfer, type-4 sets delegation on A) - batch fails before type-4 tx is dispatched
    @LeakyHapiTest(overrides = {"contracts.codeDelegations.enabled"})
    final Stream<DynamicTest> testNoDelegationWhenBatchFailsBeforeType4TxDispatched() {
        final var delegationTargetAddress = DELEGATION_TARGET.get();
        final var delegatingAccount = "DelegatingAccount";
        return hapiTest(
                overriding("contracts.codeDelegations.enabled", "true"),
                createFundedAccount(delegatingAccount),
                getAliasedAccountInfo(delegatingAccount).hasNoDelegation(),
                atomicBatch(
                                cryptoTransfer(TokenMovement.movingHbar(ONE_HBAR)
                                                .between(INSUFFICIENT_BALANCE_ACCOUNT, RELAYER))
                                        .hasKnownStatus(INSUFFICIENT_ACCOUNT_BALANCE)
                                        .batchKey(RELAYER),
                                ethereumCall(CONTRACT, "create")
                                        .signingWith(delegatingAccount)
                                        .payingWith(RELAYER)
                                        .type(EthTransactionType.EIP7702)
                                        .addSenderCodeDelegationWithSpecNonce(delegationTargetAddress)
                                        .gasLimit(GAS_LIMIT_2M)
                                        .batchKey(RELAYER))
                        .payingWith(RELAYER)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                getAliasedAccountInfo(delegatingAccount).hasNoDelegation());
    }

    // 2.1: atomicBatch(CryptoCreate(A), type-4 delegates A) - batch succeeds
    @LeakyHapiTest(overrides = {"contracts.codeDelegations.enabled"})
    final Stream<DynamicTest> testAtomicBatchCryptoCreateThenType4DelegatesInSameBatch() {
        final var delegationTargetAddress = DELEGATION_TARGET.get();
        final var accountInBatch = "AccountCreatedInBatch";
        return hapiTest(
                overriding("contracts.codeDelegations.enabled", "true"),
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
                                        .batchKey(RELAYER))
                        .payingWith(RELAYER),
                // Account A exists and delegation is set
                getAliasedAccountInfo(accountInBatch).hasDelegationAddress(delegationTargetAddress));
    }

    // 2.3: atomicBatch(CryptoCreate(A, initialDelegation=D1), type-4 updates A delegation to D2) - batch succeeds
    @LeakyHapiTest(overrides = {"contracts.codeDelegations.enabled"})
    final Stream<DynamicTest> testAtomicBatchCryptoCreateSetsDelegationThenType4UpdatesIt() {
        final var initialDelegationAddress = ByteString.copyFrom(explicitFromHeadlong(DELEGATION_TARGET.get())); // D1
        final var delegationTargetAddress = DELEGATION_TARGET_2.get(); // D2
        final var accountInBatch = "AccountCreatedInBatch";
        return hapiTest(
                overriding("contracts.codeDelegations.enabled", "true"),
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
                                        .batchKey(RELAYER))
                        .payingWith(RELAYER),
                // Type 4 update should override the delegation set by native create in the same batch.
                getAliasedAccountInfo(accountInBatch).hasDelegationAddress(delegationTargetAddress));
    }

    // 6.1: atomicBatch(CryptoUpdate sets delegation on D, type-4 with 2 valid + 2 invalid auth entries) - batch
    // succeeds
    @LeakyHapiTest(overrides = {"contracts.codeDelegations.enabled"})
    final Stream<DynamicTest> testAtomicBatchType4PartialCommitAcrossAccountsWithInvalidAuthorization() {
        final var delegationTargetAddress = DELEGATION_TARGET.get();
        final var delegationAddress = ByteString.copyFrom(explicitFromHeadlong(delegationTargetAddress));
        final var sender = "SenderAccount";
        final var authority1 = "Auth1";
        final var authority2 = "Auth2";
        final var authority3 = "Auth3";
        return hapiTest(
                overriding("contracts.codeDelegations.enabled", "true"),
                // Split into two calls to avoid MAX_CHILD_RECORDS_EXCEEDED
                createHollowAccounts(sender, authority1),
                createHollowAccounts(authority2, authority3),
                cryptoCreate(CRYPTO_CREATE_DELEGATING_ACCOUNT).key(RELAYER).balance(ONE_HUNDRED_HBARS),
                getAccountInfo(CRYPTO_CREATE_DELEGATING_ACCOUNT).hasNoDelegation(),
                atomicBatch(
                                cryptoUpdate(CRYPTO_CREATE_DELEGATING_ACCOUNT)
                                        .delegationAddress(delegationAddress)
                                        .batchKey(RELAYER),
                                ethereumCall(CONTRACT, "create")
                                        .signingWith(sender)
                                        .payingWith(RELAYER)
                                        .type(EthTransactionType.EIP7702)
                                        .addSenderCodeDelegationWithSpecNonce(delegationTargetAddress)
                                        .addCodeDelegationWithSpecNonce(delegationTargetAddress, authority1)
                                        // We set wrong nonce on purpose to simulate invalid auth entries.
                                        // These should be skipped, but valid delegations should still be committed.
                                        .addCodeDelegationWithNonce(delegationTargetAddress, 999L, authority2)
                                        .addCodeDelegationWithNonce(delegationTargetAddress, 999L, authority3)
                                        .gasLimit(GAS_LIMIT_2M)
                                        .hasKnownStatus(SUCCESS)
                                        .batchKey(RELAYER))
                        .payingWith(RELAYER)
                        .hasKnownStatus(SUCCESS),
                // Atomic batch commits back all delegations.
                getAccountInfo(CRYPTO_CREATE_DELEGATING_ACCOUNT).hasDelegationAddress(delegationTargetAddress),
                getAliasedAccountInfo(sender).hasDelegationAddress(delegationTargetAddress),
                getAliasedAccountInfo(authority1).hasDelegationAddress(delegationTargetAddress),
                getAliasedAccountInfo(authority2).hasNoDelegation(),
                getAliasedAccountInfo(authority3).hasNoDelegation());
    }

    // 8.1: atomicBatch(type-4 with 3 auth entries) - batch succeeds. Nonces incremented.
    @LeakyHapiTest(overrides = {"contracts.codeDelegations.enabled"})
    final Stream<DynamicTest> testAtomicBatchType4NoncesOnSuccess() {
        final var sender = "SenderAccount";
        final var authAccount1 = "Auth1";
        final var authAccount2 = "Auth2";
        final var delegationTargetAddress = DELEGATION_TARGET.get();
        final var senderNonceBefore = new AtomicLong();
        final var auth1NonceBefore = new AtomicLong();
        final var auth2NonceBefore = new AtomicLong();
        final var senderNonceAfter = new AtomicLong();
        final var auth1NonceAfter = new AtomicLong();
        final var auth2NonceAfter = new AtomicLong();
        return hapiTest(
                overriding("contracts.codeDelegations.enabled", "true"),
                createHollowAccounts(sender, authAccount1),
                createHollowAccounts(authAccount2),
                getAliasedAccountInfo(sender).exposingEthereumNonceTo(senderNonceBefore::set),
                getAliasedAccountInfo(authAccount1).exposingEthereumNonceTo(auth1NonceBefore::set),
                getAliasedAccountInfo(authAccount2).exposingEthereumNonceTo(auth2NonceBefore::set),
                atomicBatch(ethereumCall(CONTRACT, "create")
                                .signingWith(sender)
                                .payingWith(RELAYER)
                                .type(EthTransactionType.EIP7702)
                                .addSenderCodeDelegationWithSpecNonce(delegationTargetAddress)
                                .addCodeDelegationWithSpecNonce(delegationTargetAddress, authAccount1)
                                .addCodeDelegationWithSpecNonce(delegationTargetAddress, authAccount2)
                                .gasLimit(GAS_LIMIT_2M)
                                .batchKey(RELAYER))
                        .payingWith(RELAYER)
                        .hasKnownStatus(SUCCESS),
                getAliasedAccountInfo(sender).exposingEthereumNonceTo(senderNonceAfter::set),
                getAliasedAccountInfo(authAccount1).exposingEthereumNonceTo(auth1NonceAfter::set),
                getAliasedAccountInfo(authAccount2).exposingEthereumNonceTo(auth2NonceAfter::set),
                doAdhoc(() -> {
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

    // 10.1: atomicBatch(CryptoUpdate sets delegation on A, invalid transfer) - batch fails
    @LeakyHapiTest(overrides = {"contracts.codeDelegations.enabled"})
    final Stream<DynamicTest> testCryptoUpdateDelegationRolledBackOnBatchFailure() {
        final var delegationAddress = ByteString.copyFrom(explicitFromHeadlong(DELEGATION_TARGET.get()));
        final var preCreatedAccount = "PreCreatedAccount";
        return hapiTest(
                overriding("contracts.codeDelegations.enabled", "true"),
                createFundedAccount(preCreatedAccount),
                getAliasedAccountInfo(preCreatedAccount).hasNoDelegation(),
                atomicBatch(
                                cryptoUpdate(preCreatedAccount)
                                        .delegationAddress(delegationAddress)
                                        .batchKey(RELAYER),
                                cryptoTransfer(TokenMovement.movingHbar(ONE_HBAR)
                                                .between(INSUFFICIENT_BALANCE_ACCOUNT, RELAYER))
                                        .hasKnownStatus(INSUFFICIENT_ACCOUNT_BALANCE)
                                        .batchKey(RELAYER))
                        .payingWith(RELAYER)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                // CryptoUpdate delegation should be rolled back
                getAliasedAccountInfo(preCreatedAccount).hasNoDelegation());
    }

    private static SpecOperation createFundedAccount(@NonNull final String name) {
        return blockingOrder(
                newKeyNamed(name).shape(SECP_256K1_SHAPE),
                cryptoCreate(name).key(name).withMatchingEvmAddress().balance(ONE_HUNDRED_HBARS));
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
