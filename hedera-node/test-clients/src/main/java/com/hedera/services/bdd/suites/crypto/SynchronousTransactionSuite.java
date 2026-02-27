// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.crypto;

import static com.hedera.services.bdd.junit.TestTags.CRYPTO;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.spec.infrastructure.HapiClients;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.Transaction;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * BDD tests for the {@code proto.SynchronousService/syncTransaction} gRPC endpoint.
 *
 * <p>These tests require a running network and exercise the synchronous transaction path
 * end-to-end: submitting a transaction and blocking until the {@code TransactionRecord}
 * is available.
 */
@Tag(CRYPTO)
public class SynchronousTransactionSuite {

    private static final String SENDER = "sender";
    private static final String RECEIVER = "receiver";

    @HapiTest
    final Stream<DynamicTest> syncCryptoTransferReturnsRecord() {
        return hapiTest(
                cryptoCreate(SENDER).balance(10 * ONE_HBAR),
                cryptoCreate(RECEIVER).balance(0L),
                withOpContext((spec, log) -> {
                    // Build a properly signed cryptoTransfer transaction via spec helpers
                    final var op = cryptoTransfer(tinyBarsFromTo(SENDER, RECEIVER, ONE_HBAR));
                    final Transaction txn = op.signedTxnFor(spec);

                    // Get the synchronous stub for the default node
                    final var nodeId = spec.setup().defaultNode();
                    final var clients = HapiClients.clientsFor(spec.targetNetworkOrThrow());
                    final var stub = clients.getSyncSvcStub(nodeId, false, false);

                    // Call syncTransaction — blocks until TransactionRecord is available
                    final var record = stub.syncTransaction(txn);

                    // Assert receipt status is SUCCESS
                    assertThat(record.getReceipt().getStatus()).isEqualTo(ResponseCodeEnum.SUCCESS);
                }));
    }

    @HapiTest
    final Stream<DynamicTest> syncTransactionFailingPreCheckReturnsGrpcError() {
        return hapiTest(withOpContext((spec, log) -> {
            // An empty / default Transaction is structurally invalid and will fail pre-checks
            final Transaction invalidTxn = Transaction.getDefaultInstance();

            final var nodeId = spec.setup().defaultNode();
            final var clients = HapiClients.clientsFor(spec.targetNetworkOrThrow());
            final var stub = clients.getSyncSvcStub(nodeId, false, false);

            // Expect a StatusRuntimeException with FAILED_PRECONDITION status
            assertThatThrownBy(() -> stub.syncTransaction(invalidTxn))
                    .isInstanceOf(StatusRuntimeException.class)
                    .satisfies(e -> assertThat(
                                    ((StatusRuntimeException) e).getStatus().getCode())
                            .isEqualTo(Status.Code.FAILED_PRECONDITION));
        }));
    }
}
