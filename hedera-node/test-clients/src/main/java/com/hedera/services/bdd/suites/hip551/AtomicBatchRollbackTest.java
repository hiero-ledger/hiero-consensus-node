// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip551;

import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.ethereumCall;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.blockingOrder;
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
import com.hedera.services.bdd.spec.transactions.token.TokenMovement;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(SMART_CONTRACT)
@HapiTestLifecycle
public class AtomicBatchRollbackTest {

    private static final String CONTRACT = "CreateTrivial";
    private static final long GAS_LIMIT_2M = 2_000_000L;
    private static final String INSUFFICIENT_BALANCE_ACCOUNT = "InsufficientBalanceAccount";

    @Contract(contract = CONTRACT, creationGas = 5_000_000)
    static SpecContract contract;

    @Account(name = INSUFFICIENT_BALANCE_ACCOUNT)
    static SpecAccount insufficientBalanceAccount;

    @Account(name = RELAYER, tinybarBalance = ONE_MILLION_HBARS)
    static SpecAccount relayer;

    @BeforeAll
    public static void setup(@NonNull final TestLifecycle lifecycle) {
        lifecycle.doAdhoc(contract.getInfo(), insufficientBalanceAccount.getInfo(), relayer.getInfo());
    }

    private static SpecOperation createFundedAccount(@NonNull final String name) {
        return blockingOrder(
                newKeyNamed(name).shape(SECP_256K1_SHAPE),
                cryptoCreate(name).key(name).withMatchingEvmAddress().balance(ONE_HUNDRED_HBARS));
    }

    @LeakyHapiTest
    final Stream<DynamicTest> test() {
        final var delegatingAccount = "TestAccount";
        return hapiTest(
                createFundedAccount(delegatingAccount),
                atomicBatch(
                                ethereumCall(CONTRACT, "create")
                                        .signingWith(delegatingAccount)
                                        .payingWith(RELAYER)
                                        .gasLimit(GAS_LIMIT_2M)
                                        .batchKey(RELAYER),
                                cryptoTransfer(TokenMovement.movingHbar(ONE_HBAR)
                                                .between(INSUFFICIENT_BALANCE_ACCOUNT, RELAYER))
                                        .hasKnownStatus(INSUFFICIENT_ACCOUNT_BALANCE)
                                        .batchKey(RELAYER))
                        .payingWith(RELAYER)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }
}
