// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.queries;

import static com.hedera.services.bdd.junit.TestTags.CRYPTO;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.OrderedInIsolation;
import com.hedera.services.bdd.junit.hedera.HederaNode;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hederahashgraph.api.proto.java.AccountAmount;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.CryptoTransferTransactionBody;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.api.proto.java.TransactionBody;
import com.hederahashgraph.api.proto.java.TransferList;
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
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * A class with Node Operator Queries tests
 */
@Tag(CRYPTO)
@DisplayName("Node Operator Queries")
@HapiTestLifecycle
@OrderedInIsolation
public class AsNodeOperatorQueriesTestSubprocess extends NodeOperatorQueriesBase {

    private static List<HederaNode> nodes = new ArrayList<>();

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle lifecycle) {
        lifecycle.doAdhoc(createAllAccountsAndTokens());
        nodes = lifecycle.getNodes();
    }

    @HapiTest
    @DisplayName("Node Operator gets failure trying to submit any transaction to the Node Operator port")
    final Stream<DynamicTest> submitCryptoTransferLocalHostNodeOperatorPort() {
        // Create the transaction
        final AccountID TO_ACCOUNT = AccountID.newBuilder().setAccountNum(1002).build();
        final AccountID FROM_ACCOUNT =
                AccountID.newBuilder().setAccountNum(1001).build();
        final Transaction transaction = buildCryptoTransferTransaction(FROM_ACCOUNT, TO_ACCOUNT, 1000L);

        return Stream.of(
                DynamicTest.dynamicTest("Node Operator Submit Crypto Transfer Localhost Node Operator Port", () -> {
                    final int nodeOperatorGrpcPort = nodes.getFirst().getGrpcNodeOperatorPort();

                    ManagedChannel channel = null;
                    try {
                        // Create the gRPC channel
                        channel = ManagedChannelBuilder.forAddress("localhost", nodeOperatorGrpcPort)
                                .usePlaintext()
                                .idleTimeout(5000, TimeUnit.MICROSECONDS)
                                .build();
                        // The assertion lambda below needs a `final` var to access :(
                        final ManagedChannel finalChannel = channel;

                        // Create the stub
                        final CryptoServiceGrpc.CryptoServiceBlockingStub stub =
                                CryptoServiceGrpc.newBlockingStub(channel);

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

                                    stub.cryptoTransfer(transaction);
                                })
                                .isInstanceOf(StatusRuntimeException.class)
                                .hasFieldOrPropertyWithValue("status.code", Status.UNIMPLEMENTED.getCode())
                                .hasMessageContaining(
                                        "UNIMPLEMENTED: Method not found: proto.CryptoService/cryptoTransfer");
                    } finally {
                        if (channel != null) {
                            // Close the channel
                            channel.shutdown();
                        }
                    }
                }));
    }

    private static Transaction buildCryptoTransferTransaction(
            final AccountID fromAccount, final AccountID toAccount, final long amount) {
        // Create the transfer list
        final TransferList transferList = TransferList.newBuilder()
                .addAccountAmounts(AccountAmount.newBuilder()
                        .setAccountID(fromAccount)
                        .setAmount(-amount)
                        .build())
                .addAccountAmounts(AccountAmount.newBuilder()
                        .setAccountID(toAccount)
                        .setAmount(amount)
                        .build())
                .build();

        // Create the CryptoTransferTransactionBody
        final CryptoTransferTransactionBody body = CryptoTransferTransactionBody.newBuilder()
                .setTransfers(transferList)
                .build();

        // Create the TransactionBody
        final TransactionBody transactionBody =
                TransactionBody.newBuilder().setCryptoTransfer(body).build();

        // Create the Transaction
        return Transaction.newBuilder()
                .setSignedTransactionBytes(transactionBody.toByteString())
                .build();
    }
}
