// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.grpc.impl;

import com.hedera.node.app.workflows.clpr.ClprSyncWorkflow;
import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;

/**
 * Handles gRPC duties for processing CLPR endpoint discovery calls. A single instance
 * of this class is used by all threads handling CLPR discovery requests on the node.
 */
/*@ThreadSafe*/
public final class ClprDiscoveryMethod extends MethodBase {
    private final ClprSyncWorkflow workflow;

    public ClprDiscoveryMethod(
            @NonNull final String serviceName,
            @NonNull final String methodName,
            @NonNull final ClprSyncWorkflow workflow,
            @NonNull final Metrics metrics,
            final int maxMessageSize) {
        super(serviceName, methodName, metrics, maxMessageSize);
        this.workflow = Objects.requireNonNull(workflow);
    }

    @Override
    protected void handle(@NonNull final Bytes requestBuffer, @NonNull final BufferedData responseBuffer) {
        workflow.handleDiscovery(requestBuffer, responseBuffer);
    }
}
