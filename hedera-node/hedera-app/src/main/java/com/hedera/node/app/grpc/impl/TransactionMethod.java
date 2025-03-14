// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.grpc.impl;

import com.hedera.hapi.node.base.Transaction;
import com.hedera.node.app.workflows.ingest.IngestWorkflow;
import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;

/**
 * Handles gRPC duties for processing {@link Transaction} gRPC calls. A single instance of this
 * class is used by all transaction ingest threads in the node.
 *
 * <p>FUTURE WORK: ThreadSafe annotation missing in spotbugs annotations but should be added to
 * class
 */
/*@ThreadSafe*/
public final class TransactionMethod extends MethodBase {
    /** The pipeline contains all the steps needed for handling the ingestion of a transaction. */
    private final IngestWorkflow workflow;

    /**
     * @param serviceName a non-null reference to the service name
     * @param methodName a non-null reference to the method name
     * @param workflow a non-null {@link IngestWorkflow}
     * @param maxMessageSize the maximum message size
     */
    public TransactionMethod(
            @NonNull final String serviceName,
            @NonNull final String methodName,
            @NonNull final IngestWorkflow workflow,
            @NonNull final Metrics metrics,
            final int maxMessageSize) {
        super(serviceName, methodName, metrics, maxMessageSize);
        this.workflow = Objects.requireNonNull(workflow);
    }

    /** {@inheritDoc} */
    @Override
    protected void handle(@NonNull final Bytes requestBuffer, @NonNull final BufferedData responseBuffer) {
        workflow.submitTransaction(requestBuffer, responseBuffer);
    }
}
