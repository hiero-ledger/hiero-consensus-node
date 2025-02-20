// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.ingest;

import com.hedera.hapi.node.base.Transaction;
import com.hedera.hapi.node.transaction.TransactionResponse;
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
}
