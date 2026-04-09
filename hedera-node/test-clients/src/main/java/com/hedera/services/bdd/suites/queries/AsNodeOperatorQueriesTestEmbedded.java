// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.queries;

import static com.hedera.services.bdd.junit.ContextRequirement.THROTTLE_OVERRIDES;
import static com.hedera.services.bdd.junit.EmbeddedReason.NEEDS_STATE_ACCESS;
import static com.hedera.services.bdd.junit.TestTags.CRYPTO;
import static com.hedera.services.bdd.junit.hedera.embedded.EmbeddedMode.CONCURRENT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getContractBytecode;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getContractInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getFileContents;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getFileInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getScheduleInfo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.inParallel;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overridingThrottles;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.flattened;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.BUSY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hedera.services.bdd.junit.EmbeddedHapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyEmbeddedHapiTest;
import com.hedera.services.bdd.junit.OrderedInIsolation;
import com.hedera.services.bdd.junit.TargetEmbeddedMode;
import com.hedera.services.bdd.junit.hedera.HederaNode;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.CryptoGetInfoQuery;
import com.hederahashgraph.api.proto.java.Query;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.service.proto.java.CryptoServiceGrpc;
import edu.umd.cs.findbugs.annotations.NonNull;
import io.grpc.ConnectivityState;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * A class with Node Operator Queries tests
 */
@Tag(CRYPTO)
@TargetEmbeddedMode(CONCURRENT)
@DisplayName("Node Operator Queries")
@HapiTestLifecycle
@OrderedInIsolation
public class AsNodeOperatorQueriesTestEmbedded extends NodeOperatorQueriesBase {

    private static final int BURST_SIZE = 20;

    private static final Function<String, HapiSpecOperation[]> miscTxnBurstFn = payer -> IntStream.range(0, BURST_SIZE)
            .mapToObj(i -> cryptoCreate(String.format("Account%d", i))
                    .payingWith(payer)
                    .deferStatusResolution())
            .toArray(HapiSpecOperation[]::new);

    private static List<HederaNode> nodes = new ArrayList<>();

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle lifecycle) {
        lifecycle.doAdhoc(createAllAccountsAndTokens());
        nodes = lifecycle.getNodes();
    }

    @EmbeddedHapiTest(NEEDS_STATE_ACCESS)
    @DisplayName("Only node operators don't need to sign file contents queries")
    final Stream<DynamicTest> fileGetContentsNoSigRequired() {
        final var filename = "anyFile.txt";
        final var someoneElse = "someoneElse";
        return hapiTest(flattened(
                nodeOperatorAccount(),
                newKeyNamed(someoneElse),
                payerAccount(),
                fileCreate(filename).contents("anyContent"),
                // Sign the node operator query request with a totally unrelated key, to show that there is no
                // signature check
                getFileContents(filename)
                        .payingWith(NODE_OPERATOR)
                        .signedBy(someoneElse)
                        .asNodeOperator()
                        .hasAnswerOnlyPrecheck(OK),
                // But the non-node operator submitter must still sign
                getFileContents(filename)
                        .payingWith(PAYER)
                        .signedBy(someoneElse)
                        .hasAnswerOnlyPrecheck(ResponseCodeEnum.INVALID_SIGNATURE)));
    }

    @EmbeddedHapiTest(NEEDS_STATE_ACCESS)
    @DisplayName("Only node operators don't need to sign file info queries")
    final Stream<DynamicTest> getFileInfoQueryNoSigRequired() {
        final var filename = "anyFile.json";
        String someoneElse = "someoneElse";
        return hapiTest(flattened(
                nodeOperatorAccount(),
                newKeyNamed(someoneElse),
                payerAccount(),
                fileCreate(filename).contents("anyContentAgain"),
                // Sign the node operator query request with a totally unrelated key, to show that there is no
                // signature check
                getFileInfo(filename)
                        .payingWith(NODE_OPERATOR)
                        .signedBy(someoneElse)
                        .asNodeOperator()
                        .hasAnswerOnlyPrecheck(OK),
                // But the non-node operator submitter must still sign
                getFileInfo(filename)
                        .payingWith(PAYER)
                        .signedBy(someoneElse)
                        .hasAnswerOnlyPrecheck(ResponseCodeEnum.INVALID_SIGNATURE)));
    }

    @EmbeddedHapiTest(NEEDS_STATE_ACCESS)
    @DisplayName("Only node operators don't need to sign contract info queries")
    final Stream<DynamicTest> getSmartContractQuerySigNotRequired() {
        final var contract = "PretendPair"; // any contract, nothing special about this one
        final var someoneElse = "someoneElse";
        return hapiTest(flattened(
                nodeOperatorAccount(),
                payerAccount(),
                newKeyNamed(someoneElse),
                uploadInitCode(contract),
                contractCreate(contract),
                // Sign the node operator query request with a totally unrelated key, to show that there is no
                // signature check
                getContractInfo(contract)
                        .payingWith(NODE_OPERATOR)
                        .signedBy(someoneElse)
                        .asNodeOperator()
                        .hasAnswerOnlyPrecheck(OK),
                // But the non-node operator submitter must still sign
                getContractInfo(contract)
                        .payingWith(PAYER)
                        .signedBy(someoneElse)
                        .hasAnswerOnlyPrecheck(ResponseCodeEnum.INVALID_SIGNATURE)));
    }

    @EmbeddedHapiTest(NEEDS_STATE_ACCESS)
    @DisplayName("Only node operators don't need to sign contract bytecode queries")
    final Stream<DynamicTest> getContractBytecodeQueryNoSigRequired() {
        final var contract = "PretendPair"; // any contract, nothing special about this one
        final var someoneElse = "someoneElse";
        return hapiTest(flattened(
                nodeOperatorAccount(),
                payerAccount(),
                newKeyNamed(someoneElse),
                uploadInitCode(contract),
                contractCreate(contract),
                // Sign the node operator query request with a totally unrelated key, to show that there is no
                // signature check
                getContractBytecode(contract)
                        .payingWith(NODE_OPERATOR)
                        .signedBy(someoneElse)
                        .asNodeOperator()
                        .hasAnswerOnlyPrecheck(OK),
                // But the non-node operator submitter must still sign
                getContractBytecode(contract)
                        .payingWith(PAYER)
                        .signedBy(someoneElse)
                        .hasAnswerOnlyPrecheck(ResponseCodeEnum.INVALID_SIGNATURE)));
    }

    @EmbeddedHapiTest(NEEDS_STATE_ACCESS)
    @DisplayName("Only node operators don't need to sign schedule info queries")
    final Stream<DynamicTest> getScheduleInfoQueryNoSigRequired() {
        final var txnToSchedule =
                cryptoTransfer(tinyBarsFromTo(PAYER, DEFAULT_PAYER, 1)); // any txn, nothing special here
        final var schedule = "anySchedule";
        final var someoneElse = "someoneElse";
        return hapiTest(flattened(
                nodeOperatorAccount(),
                payerAccount(),
                newKeyNamed(someoneElse),
                scheduleCreate(schedule, txnToSchedule),
                // Sign the node operator query request with a totally unrelated key, to show that there is no
                // signature check
                getScheduleInfo(schedule)
                        .payingWith(NODE_OPERATOR)
                        .signedBy(someoneElse)
                        .asNodeOperator()
                        .hasAnswerOnlyPrecheck(OK),
                // But the non-node operator submitter must still sign
                getScheduleInfo(schedule)
                        .payingWith(PAYER)
                        .signedBy(someoneElse)
                        .hasAnswerOnlyPrecheck(ResponseCodeEnum.INVALID_SIGNATURE)));
    }

    @LeakyEmbeddedHapiTest(
            reason = NEEDS_STATE_ACCESS,
            requirement = {THROTTLE_OVERRIDES},
            throttles = "testSystemFiles/node-operator-throttles.json")
    final Stream<DynamicTest> nodeOperatorCryptoGetInfoThrottled() {
        return hapiTest(flattened(
                overridingThrottles("testSystemFiles/node-operator-throttles.json"),
                nodeOperatorAccount(),
                inParallel(miscTxnBurstFn.apply(DEFAULT_PAYER)),
                getAccountInfo(NODE_OPERATOR).payingWith(NODE_OPERATOR).hasAnswerOnlyPrecheck(BUSY)));
    }

    @LeakyEmbeddedHapiTest(
            reason = NEEDS_STATE_ACCESS,
            requirement = {THROTTLE_OVERRIDES},
            throttles = "testSystemFiles/node-operator-throttles.json")
    final Stream<DynamicTest> nodeOperatorCryptoGetInfoNotThrottled() {
        return hapiTest(flattened(
                overridingThrottles("testSystemFiles/node-operator-throttles.json"),
                nodeOperatorAccount(),
                inParallel(miscTxnBurstFn.apply(DEFAULT_PAYER)),
                getAccountInfo(NODE_OPERATOR)
                        .payingWith(NODE_OPERATOR)
                        .asNodeOperator()
                        .hasAnswerOnlyPrecheck(OK)));
    }

    @EmbeddedHapiTest(NEEDS_STATE_ACCESS)
    // Not reliable in OS X local environment for some reason
    @EnabledIfEnvironmentVariable(named = "CI", matches = "true")
    @DisplayName("Node Operator Submit Query not from Localhost")
    final Stream<DynamicTest> submitCryptoTransfer() {
        final Query query = buildCryptoAccountInfoQuery();

        return Stream.of(DynamicTest.dynamicTest("Node Operator Submit Query not from Localhost", () -> {
            final int nodeOperatorGrpcPort = nodes.getFirst().getGrpcNodeOperatorPort();

            // Create the gRPC channel
            ManagedChannel channel = null;
            try {
                channel = ManagedChannelBuilder.forAddress("0.0.0.0", nodeOperatorGrpcPort)
                        .usePlaintext()
                        .idleTimeout(5_000, TimeUnit.MICROSECONDS)
                        .build();
                // The assertion lambda below needs a `final` var to access :(
                final ManagedChannel finalChannel = channel;

                // Create the stub
                final CryptoServiceGrpc.CryptoServiceBlockingStub stub = CryptoServiceGrpc.newBlockingStub(channel);

                // Assert that the exception is thrown
                assertThatThrownBy(() -> {
                            // Once the channel is ready, submit the transaction
                            long counter = 0;
                            while (finalChannel.getState(true) != ConnectivityState.READY) {
                                // Make sure the test doesn't hang forever
                                if (counter++ >= 60) {
                                    break;
                                }

                                Thread.sleep(1000);
                            }
                            stub.getAccountInfo(query);
                        })
                        .isInstanceOf(StatusRuntimeException.class)
                        .hasFieldOrPropertyWithValue("status.code", Status.UNAVAILABLE.getCode());
            } finally {
                if (channel != null) {
                    // Close the channel
                    channel.shutdown();
                }
            }
        }));
    }

    private Query buildCryptoAccountInfoQuery() {
        // Define the account ID for which the account info is being requested
        final AccountID accountID = AccountID.newBuilder().setAccountNum(1001).build();

        // Create the CryptoGetInfo query
        return Query.newBuilder()
                .setCryptoGetInfo(
                        CryptoGetInfoQuery.newBuilder().setAccountID(accountID).build())
                .build();
    }
}
