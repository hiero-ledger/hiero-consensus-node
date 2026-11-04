// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.ingest;

import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.Transaction;
import com.hedera.hapi.node.transaction.SignedTransaction;
import com.hedera.hapi.node.transaction.TransactionResponse;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * The {@link IngestWorkflow} represents the workflow used when receiving a {@link Transaction} from
 * a client (currently always through gRPC). This workflow takes the transaction, checks it,
 * verifies the payer exists, signed the transaction, and has sufficient balance, checks the
 * throttles, and performs any other required tasks, and then submits the transaction to the
 * hashgraph platform for consensus.
 */
public interface IngestWorkflow {
    /**
     * Called to handle a single transaction during the ingestion flow. The call terminates in a
     * {@link TransactionResponse} being returned to the client (for both successful and
     * unsuccessful calls). There are no unhandled exceptions (even Throwable is handled).
     *
     * @param requestBuffer The raw protobuf transaction bytes. Must be a transaction object.
     * @param responseBuffer The raw protobuf response bytes.
     */
    void submitTransaction(@NonNull Bytes requestBuffer, @NonNull BufferedData responseBuffer);

    /**
     * Runs the ingest checks on a transaction saved at a freeze and, if they pass, submits it to the platform.
     * Reuses {@link #submitTransaction} so it goes through the exact same ingest path.
     *
     * @param serializedSignedTx the serialized {@code SignedTransaction}, as it was in the transaction pool
     * @return the precheck code; {@link ResponseCodeEnum#OK} if the transaction was submitted
     */
    @NonNull
    default ResponseCodeEnum submitRestoredTransaction(@NonNull final Bytes serializedSignedTx) {
        final SignedTransaction signedTx;
        try {
            signedTx = SignedTransaction.PROTOBUF.parse(serializedSignedTx.toReadableSequentialData());
        } catch (final ParseException e) {
            return ResponseCodeEnum.INVALID_TRANSACTION;
        }
        // Legacy (no signedTransactionBytes) clients hash bodyBytes+sigMap directly; rebuild that shape so
        // TransactionChecker.check() re-derives the same signed bytes instead of rejecting them.
        final var transaction = signedTx.useSerializedTxMessageHashAlgorithm()
                ? Transaction.newBuilder()
                        .bodyBytes(signedTx.bodyBytes())
                        .sigMap(signedTx.sigMap())
                        .build()
                : Transaction.newBuilder()
                        .signedTransactionBytes(serializedSignedTx)
                        .build();
        final var requestBuffer = Transaction.PROTOBUF.toBytes(transaction);
        final var responseBuffer = BufferedData.allocate(256); // a precheck-only response is tiny
        submitTransaction(requestBuffer, responseBuffer);
        responseBuffer.flip();
        try {
            return TransactionResponse.PROTOBUF.parse(responseBuffer).nodeTransactionPrecheckCode();
        } catch (final ParseException e) {
            return ResponseCodeEnum.FAIL_INVALID;
        }
    }
}
