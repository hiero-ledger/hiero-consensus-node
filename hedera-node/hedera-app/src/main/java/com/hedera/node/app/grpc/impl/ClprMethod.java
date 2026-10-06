// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.grpc.impl;

import com.hedera.node.app.workflows.clpr.ClprSyncWorkflow;
import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;

/**
 * Handles gRPC duties for processing CLPR endpoint-to-endpoint sync calls. A single instance
 * of this class is used by all threads handling CLPR sync requests on the node.
 */
/*@ThreadSafe*/
public final class ClprMethod extends MethodBase {
    /** The workflow contains all the steps needed for handling a CLPR sync request. */
    private final ClprSyncWorkflow workflow;

    /**
     * @param serviceName a non-null reference to the service name
     * @param methodName a non-null reference to the method name
     * @param workflow a non-null {@link ClprSyncWorkflow}
     * @param metrics the metrics instance
     * @param maxMessageSize the maximum message size
     */
    public ClprMethod(
            @NonNull final String serviceName,
            @NonNull final String methodName,
            @NonNull final ClprSyncWorkflow workflow,
            @NonNull final Metrics metrics,
            final int maxMessageSize) {
        super(serviceName, methodName, metrics, maxMessageSize);
        this.workflow = Objects.requireNonNull(workflow);
    }

    /** {@inheritDoc} */
    @Override
    protected void handle(@NonNull final Bytes requestBuffer, @NonNull final BufferedData responseBuffer) {
        workflow.handleSync(requestBuffer, responseBuffer);
    }
}
