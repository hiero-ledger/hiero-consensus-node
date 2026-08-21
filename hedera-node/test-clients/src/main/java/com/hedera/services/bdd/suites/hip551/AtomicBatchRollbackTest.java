// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip551;

import static com.hedera.services.bdd.junit.TestTags.SERIAL;
import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.ethereumCall;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.blockingOrder;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.RELAYER;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SHAPE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INNER_TRANSACTION_FAILED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_ACCOUNT_BALANCE;

import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.dsl.annotations.Account;
import com.hedera.services.bdd.spec.dsl.annotations.Contract;
import com.hedera.services.bdd.spec.dsl.entities.SpecAccount;
import com.hedera.services.bdd.spec.dsl.entities.SpecContract;
import com.hedera.services.bdd.spec.queries.QueryVerbs;
import com.hedera.services.bdd.spec.transactions.token.TokenMovement;
import com.hederahashgraph.api.proto.java.ContractID;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(SMART_CONTRACT)
@Tag(SERIAL)
@HapiTestLifecycle
public class AtomicBatchRollbackTest {

    private static final String LOGGED_CONTRACT = "CreateTrivialLogged";
    private static final long GAS_LIMIT_2M = 2_000_000L;
    private static final String INSUFFICIENT_BALANCE_ACCOUNT = "InsufficientBalanceAccount";
    private static final String SENDER_IN_FAILED_BATCH = "SenderInFailedBatch";
    private static final String SENDER_IN_SUCCESSFUL_BATCH = "SenderInSuccessfulBatch";
    private static final String BATCH_OPERATOR = "BatchOperator";

    @Contract(contract = LOGGED_CONTRACT, creationGas = 5_000_000)
    static SpecContract loggedContract;

    @Account(name = INSUFFICIENT_BALANCE_ACCOUNT)
    static SpecAccount insufficientBalanceAccount;

    @Account(name = RELAYER, tinybarBalance = ONE_MILLION_HBARS)
    static SpecAccount relayer;

    @BeforeAll
    public static void setup(@NonNull final TestLifecycle lifecycle) {
        lifecycle.doAdhoc(loggedContract.getInfo(), insufficientBalanceAccount.getInfo(), relayer.getInfo());
    }

    @LeakyHapiTest
    final Stream<DynamicTest> batchContractDelegationSuppressesRolledBackFields() {
        final var delegatingAccount = "TestAccount";
        return hapiTest(
                createFundedAccount(delegatingAccount),
                atomicBatch(
                                ethereumCall(LOGGED_CONTRACT, "create")
                                        .signingWith(delegatingAccount)
                                        .via("ethTx")
                                        .payingWith(RELAYER)
                                        .gasLimit(GAS_LIMIT_2M)
                                        .batchKey(RELAYER),
                                cryptoTransfer(TokenMovement.movingHbar(ONE_HBAR)
                                                .between(INSUFFICIENT_BALANCE_ACCOUNT, RELAYER))
                                        .hasKnownStatus(INSUFFICIENT_ACCOUNT_BALANCE)
                                        .batchKey(RELAYER))
                        .payingWith(RELAYER)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                doingContextual(spec -> {
                    var ecrOp = QueryVerbs.getTxnRecord("ethTx").exposingTo(rcd -> {
                        final var result = rcd.getContractCallResult();
                        final List<ContractID> createdContractIDs = result.getCreatedContractIDsList();
                        Assertions.assertTrue(
                                createdContractIDs.isEmpty(),
                                "Expected createdContractIDs to be empty after rollback (got: " + createdContractIDs
                                        + ")");
                        Assertions.assertTrue(
                                result.getLogInfoList().isEmpty(), "Expected logInfo to be empty after rollback");
                        Assertions.assertTrue(result.getBloom().isEmpty(), "Expected bloom to be empty after rollback");
                    });
                    allRunFor(spec, ecrOp);
                }));
    }

    /**
     * A rolled-back batch must replay an inner contract call's fee at the net actually paid (gas used),
     * not the gross gas reserved - i.e. the unused-gas refund must survive the rollback. Runs the same call
     * in a failing batch and a succeeding batch and asserts both payers are charged the same.
     */
    @LeakyHapiTest
    final Stream<DynamicTest> rolledBackBatchReplaysNetGasFeeNotGross() {
        final var failStart = new AtomicLong();
        final var failEnd = new AtomicLong();
        final var okStart = new AtomicLong();
        final var okEnd = new AtomicLong();
        return hapiTest(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(SENDER_IN_FAILED_BATCH).balance(ONE_MILLION_HBARS),
                cryptoCreate(SENDER_IN_SUCCESSFUL_BATCH).balance(ONE_MILLION_HBARS),
                QueryVerbs.getAccountBalance(SENDER_IN_FAILED_BATCH).exposingBalanceTo(failStart::set),
                QueryVerbs.getAccountBalance(SENDER_IN_SUCCESSFUL_BATCH).exposingBalanceTo(okStart::set),
                // Batch that rolls back: the contract call succeeds (reserving 2M gas but using little, so a
                // large unused-gas refund is issued), but the sibling transfer fails -> INNER_TRANSACTION_FAILED
                // -> the whole batch is rolled back and the inner fees are replayed.
                atomicBatch(
                                contractCall(LOGGED_CONTRACT, "create")
                                        .gas(GAS_LIMIT_2M)
                                        .payingWith(SENDER_IN_FAILED_BATCH)
                                        .batchKey(BATCH_OPERATOR),
                                cryptoTransfer(TokenMovement.movingHbar(ONE_HBAR)
                                                .between(INSUFFICIENT_BALANCE_ACCOUNT, BATCH_OPERATOR))
                                        .hasKnownStatus(INSUFFICIENT_ACCOUNT_BALANCE)
                                        .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                // Batch that succeeds: the identical contract call, whose payer is therefore charged the true
                // net fee (gas used, after the unused-gas refund) - the reference amount.
                atomicBatch(contractCall(LOGGED_CONTRACT, "create")
                                .gas(GAS_LIMIT_2M)
                                .payingWith(SENDER_IN_SUCCESSFUL_BATCH)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                QueryVerbs.getAccountBalance(SENDER_IN_FAILED_BATCH).exposingBalanceTo(failEnd::set),
                QueryVerbs.getAccountBalance(SENDER_IN_SUCCESSFUL_BATCH).exposingBalanceTo(okEnd::set),
                doingContextual(ignore -> {
                    final long failCharged = failStart.get() - failEnd.get();
                    final long okCharged = okStart.get() - okEnd.get();
                    final long overcharge = failCharged - okCharged;
                    // Both calls did identical work; the rolled-back payer must not pay more than the
                    // successful one. A small tolerance absorbs incidental per-transaction fee variance; the
                    // dropped-refund overcharge is ~(unused gas x gas price), far larger than the tolerance.
                    Assertions.assertTrue(
                            overcharge <= ONE_HBAR,
                            "Rolled-back batch overcharged its payer by " + overcharge
                                    + " tinybars vs the identical successful call (failCharged=" + failCharged
                                    + ", okCharged=" + okCharged
                                    + "); the unused-gas refund was dropped from the rollback replay.");
                }));
    }

    private static SpecOperation createFundedAccount(@NonNull final String name) {
        return blockingOrder(
                newKeyNamed(name).shape(SECP_256K1_SHAPE),
                cryptoCreate(name).key(name).withMatchingEvmAddress().balance(ONE_HUNDRED_HBARS));
    }
}
