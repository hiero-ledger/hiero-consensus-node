// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.grpc.impl;

import com.hedera.node.app.workflows.synchronous.SynchronousWorkflow;
import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;

/**
 * Handles gRPC duties for the synchronous transaction endpoint. Submits the transaction and blocks
 * until the {@code TransactionRecord} is available, then returns it in the response.
 *
 * <p>A single instance of this class is used by all synchronous ingest threads in the node.
 */
public final class SynchronousTransactionMethod extends MethodBase {

    private final SynchronousWorkflow workflow;

    /**
     * @param serviceName    a non-null reference to the service name
     * @param methodName     a non-null reference to the method name
     * @param workflow       a non-null {@link SynchronousWorkflow}
     * @param metrics        metrics for tracking call counts and rates
     * @param maxMessageSize the maximum message size
     */
    public SynchronousTransactionMethod(
            @NonNull final String serviceName,
            @NonNull final String methodName,
            @NonNull final SynchronousWorkflow workflow,
            @NonNull final Metrics metrics,
            final int maxMessageSize) {
        super(serviceName, methodName, metrics, maxMessageSize);
        this.workflow = Objects.requireNonNull(workflow);
    }

    /** {@inheritDoc} */
    @Override
    protected void handle(@NonNull final Bytes requestBuffer, @NonNull final BufferedData responseBuffer) {
        workflow.submitAndWait(requestBuffer, responseBuffer);
    }
}
