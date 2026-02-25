// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.queries;

import static com.hedera.services.bdd.junit.TestTags.TOKEN;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.lessThan;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.contractCallLocal;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getContractBytecode;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getFileContents;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getFileInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getScheduleInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTokenInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTopicInfo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.createTopic;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.logIt;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sleepFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.flattened;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.suites.regression.system.LifecycleTest;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * Verifies the node operator port is not available to any queries when it is disabled. Since
 * whether the port is enabled is a node configuration, not a network-level setting, this test
 * is a lifecycle test that restarts the shared network with the operator port disabled, and
 * then restarts it again with the port re-enabled.
 */
@Tag(TOKEN)
@HapiTestLifecycle
public class DisabledNodeOperatorTest extends NodeOperatorQueriesBase implements LifecycleTest {
    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle lifecycle) {
        lifecycle.doAdhoc(
                logIt("Restarting network with operator port DISABLED"),
                LifecycleTest.restartWithDisabledNodeOperatorGrpcPort());
    }

    @AfterAll
    static void afterAll(@NonNull final TestLifecycle lifecycle) {
        lifecycle.doAdhoc(
                logIt("Restarting network with operator port RE-ENABLED"), LifecycleTest.restartAtNextConfigVersion());
    }

    @HapiTest
    final Stream<DynamicTest> nodeOperatorQueryPortNotAccessibleForAccountBalance() {
        return hapiTest(flattened(
                nodeOperatorAccount(),
                payerAccount(),
                // perform getAccountBalance() query, pay for the query with payer account
                getAccountBalance(NODE_OPERATOR).payingWith(PAYER),
                sleepFor(3000),
                // assert payer is charged
                getAccountBalance(PAYER).hasTinyBars(ONE_HUNDRED_HBARS),
                withOpContext((spec, opLog) -> assertThatThrownBy(() -> {
                            final var getAccountBalanceAsNodeOperator = getAccountBalance(NODE_OPERATOR)
                                    .payingWith(PAYER)
                                    .asNodeOperator();
                            allRunFor(spec, getAccountBalanceAsNodeOperator);
                        })
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("io.grpc.StatusRuntimeException: UNAVAILABLE: io exception"))));
    }

    @HapiTest
    final Stream<DynamicTest> nodeOperatorQueryPortNotAccessibleForAccountInfo() {
        return hapiTest(flattened(
                nodeOperatorAccount(),
                payerAccount(),
                getAccountInfo(NODE_OPERATOR).payingWith(PAYER),
                sleepFor(3000),
                getAccountBalance(PAYER).hasTinyBars(lessThan(ONE_HUNDRED_HBARS)),
                withOpContext((spec, opLog) -> assertThatThrownBy(() -> {
                            final var getAccountInfoAsNodeOperator = getAccountInfo(NODE_OPERATOR)
                                    .payingWith(PAYER)
                                    .asNodeOperator();
                            allRunFor(spec, getAccountInfoAsNodeOperator);
                        })
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("io.grpc.StatusRuntimeException: UNAVAILABLE: io exception"))));
    }

    @HapiTest
    final Stream<DynamicTest> nodeOperatorQueryPortNotAccessibleForTopicInfo() {
        return hapiTest(flattened(
                nodeOperatorAccount(),
                payerAccount(),
                nodeOperatorAccount(),
                createTopic(TOPIC),
                getTopicInfo(TOPIC).payingWith(PAYER),
                sleepFor(3000),
                getAccountBalance(PAYER).hasTinyBars(lessThan(ONE_HUNDRED_HBARS)),
                withOpContext((spec, opLog) -> assertThatThrownBy(() -> {
                            final var getTopicInfoAsNodeOperator = getTopicInfo(TOPIC)
                                    .payingWith(NODE_OPERATOR)
                                    .asNodeOperator();
                            allRunFor(spec, getTopicInfoAsNodeOperator);
                        })
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("io.grpc.StatusRuntimeException: UNAVAILABLE: io exception"))));
    }

    @HapiTest
    final Stream<DynamicTest> nodeOperatorQueryPortNotAccessibleForTokenInfo() {
        return hapiTest(flattened(
                nodeOperatorAccount(),
                payerAccount(),
                nodeOperatorAccount(),
                tokenCreate(TOKEN),
                getTokenInfo(TOKEN).payingWith(PAYER),
                sleepFor(3000),
                getAccountBalance(PAYER).hasTinyBars(lessThan(ONE_HUNDRED_HBARS)),
                withOpContext((spec, opLog) -> assertThatThrownBy(() -> {
                            final var getTokenInfoAsNodeOperator = getTokenInfo(TOKEN)
                                    .payingWith(NODE_OPERATOR)
                                    .asNodeOperator();
                            allRunFor(spec, getTokenInfoAsNodeOperator);
                        })
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("io.grpc.StatusRuntimeException: UNAVAILABLE: io exception"))));
    }

    @HapiTest
    final Stream<DynamicTest> nodeOperatorQueryPortNotAccessibleForFileContents() {
        return hapiTest(flattened(
                nodeOperatorAccount(),
                payerAccount(),
                fileCreate(FILE).contents("anyContentAgain").payingWith(PAYER),
                getFileContents(FILE).payingWith(PAYER),
                sleepFor(3000),
                getAccountBalance(PAYER).hasTinyBars(lessThan(ONE_HUNDRED_HBARS)),
                withOpContext((spec, opLog) -> assertThatThrownBy(() -> {
                            final var getFileContentsAsNodeOperator = getFileContents(FILE)
                                    .payingWith(NODE_OPERATOR)
                                    .asNodeOperator();
                            allRunFor(spec, getFileContentsAsNodeOperator);
                        })
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("io.grpc.StatusRuntimeException: UNAVAILABLE: io exception"))));
    }

    @HapiTest
    final Stream<DynamicTest> nodeOperatorQueryPortNotAccessibleForFileInfo() {
        return hapiTest(flattened(
                nodeOperatorAccount(),
                payerAccount(),
                fileCreate(FILE).contents("anyContentAgain").payingWith(PAYER),
                getFileInfo(FILE).payingWith(PAYER),
                sleepFor(3000),
                getAccountBalance(PAYER).hasTinyBars(lessThan(ONE_HUNDRED_HBARS)),
                withOpContext((spec, opLog) -> assertThatThrownBy(() -> {
                            final var getFileInfoAsNodeOperator =
                                    getFileInfo(FILE).payingWith(NODE_OPERATOR).asNodeOperator();
                            allRunFor(spec, getFileInfoAsNodeOperator);
                        })
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("io.grpc.StatusRuntimeException: UNAVAILABLE: io exception"))));
    }

    @HapiTest
    final Stream<DynamicTest> nodeOperatorQueryPortNotAccessibleForContractCall() {
        return hapiTest(flattened(
                nodeOperatorAccount(),
                payerAccount(),
                uploadInitCode("CreateTrivial"),
                contractCreate("CreateTrivial").gas(3_000_000L).payingWith(PAYER),
                contractCall("CreateTrivial", "create").gas(785_000),
                contractCallLocal("CreateTrivial", "getIndirect").gas(300_000L).payingWith(PAYER),
                sleepFor(3000),
                getAccountBalance(PAYER).hasTinyBars(lessThan(ONE_HUNDRED_HBARS)),
                withOpContext((spec, opLog) -> assertThatThrownBy(() -> {
                            final var getContractCallLocalAsNodeOperator = contractCallLocal(
                                            "CreateTrivial", "getIndirect")
                                    .gas(300_000L)
                                    .payingWith(PAYER)
                                    .asNodeOperator();
                            allRunFor(spec, getContractCallLocalAsNodeOperator);
                        })
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("io.grpc.StatusRuntimeException: UNAVAILABLE: io exception"))));
    }

    @HapiTest
    final Stream<DynamicTest> nodeOperatorQueryPortNotAccessibleForContractBytecode() {
        return hapiTest(flattened(
                nodeOperatorAccount(),
                payerAccount(),
                uploadInitCode("CreateTrivial"),
                contractCreate("CreateTrivial").gas(3_000_000L).payingWith(PAYER),
                contractCall("CreateTrivial", "create").gas(785_000),
                getContractBytecode("CreateTrivial").payingWith(PAYER),
                sleepFor(3000),
                getAccountBalance(PAYER).hasTinyBars(lessThan(ONE_HUNDRED_HBARS)),
                withOpContext((spec, opLog) -> assertThatThrownBy(() -> {
                            final var getContractBytecodeAsNodeOperator = getContractBytecode("CreateTrivial")
                                    .payingWith(PAYER)
                                    .asNodeOperator();
                            allRunFor(spec, getContractBytecodeAsNodeOperator);
                        })
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("io.grpc.StatusRuntimeException: UNAVAILABLE: io exception"))));
    }

    @HapiTest
    final Stream<DynamicTest> nodeOperatorQueryPortNotAccessibleForScheduleInfo() {
        final var txnToSchedule = cryptoTransfer(tinyBarsFromTo(PAYER, DEFAULT_PAYER, 1));
        return hapiTest(flattened(
                nodeOperatorAccount(),
                payerAccount(),
                scheduleCreate(SCHEDULE, txnToSchedule),
                getScheduleInfo(SCHEDULE).payingWith(PAYER),
                sleepFor(3000),
                getAccountBalance(PAYER).hasTinyBars(lessThan(ONE_HUNDRED_HBARS)),
                withOpContext((spec, opLog) -> assertThatThrownBy(() -> {
                            final var getScheduleInfoAsNodeOperator =
                                    getScheduleInfo(SCHEDULE).payingWith(PAYER).asNodeOperator();
                            allRunFor(spec, getScheduleInfoAsNodeOperator);
                        })
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("io.grpc.StatusRuntimeException: UNAVAILABLE: io exception"))));
    }
}
