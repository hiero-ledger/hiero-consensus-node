// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.grpc.impl;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.transaction.Query;
import com.hedera.node.app.workflows.query.QueryWorkflow;
import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.metrics.SpeedometerMetric;
import com.swirlds.metrics.api.Counter;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Handles gRPC duties for processing {@link Query} gRPC calls. A single instance of this class is
 * used by all query threads in the node.
 *
 * <p>FUTURE WORK: ThreadSafe annotation missing in spotbugs annotations but should be added to
 * class
 */
/*@ThreadSafe*/
public final class QueryMethod extends MethodBase {
    // Constants for metric names and descriptions
    private static final String COUNTER_ANSWERED_NAME_TPL = "%sSub";
    private static final String COUNTER_ANSWERED_DESC_TPL = "number of %s answered";
    private static final String SPEEDOMETER_ANSWERED_NAME_TPL = "%sSub_per_sec";
    private static final String SPEEDOMETER_ANSWERED_DESC_TPL = "number of %s answered per second";

    /** The workflow contains all the steps needed for handling the query. */
    private final QueryWorkflow workflow;

    /** A metric for the number of times the query was answered */
    private final Counter queriesAnsweredCounter;

    /** A metric for the calls per second that queries were answered */
    private final SpeedometerMetric queriesAnsweredSpeedometer;

    /**
     * Create a new QueryMethod.
     *
     * @param serviceName a non-null reference to the service name
     * @param methodName a non-null reference to the method name
     * @param workflow a non-null {@link QueryWorkflow}
     * @param maxMessageSize the maximum message size
     */
    public QueryMethod(
            @NonNull final String serviceName,
            @NonNull final String methodName,
            @NonNull final QueryWorkflow workflow,
            @NonNull final Metrics metrics,
            final int maxMessageSize) {
        super(serviceName, methodName, metrics, maxMessageSize);
        this.workflow = requireNonNull(workflow);
        this.queriesAnsweredCounter = counter(metrics, COUNTER_ANSWERED_NAME_TPL, COUNTER_ANSWERED_DESC_TPL);
        this.queriesAnsweredSpeedometer =
                speedometer(metrics, SPEEDOMETER_ANSWERED_NAME_TPL, SPEEDOMETER_ANSWERED_DESC_TPL);
    }

    /** {@inheritDoc} */
    @Override
    protected void handle(@NonNull final Bytes requestBuffer, @NonNull final BufferedData responseBuffer) {
        workflow.handleQuery(requestBuffer, responseBuffer);
        queriesAnsweredCounter.increment();
        queriesAnsweredSpeedometer.cycle();
    }
}
