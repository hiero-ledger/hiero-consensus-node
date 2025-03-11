// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.grpc.impl;

import static java.util.Objects.requireNonNull;

import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.metrics.SpeedometerMetric;
import com.swirlds.metrics.api.Counter;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.ServerCalls;
import io.grpc.stub.StreamObserver;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * An instance of either {@link TransactionMethod} or {@link QueryMethod} is created per transaction
 * type and query type.
 */
public abstract class MethodBase implements ServerCalls.UnaryMethod<BufferedData, BufferedData> {
    private static final Logger logger = LogManager.getLogger(MethodBase.class);

    // To be set by configuration. See Issue #4294. Originally this was intended to be the same max size as
    // a transaction, but some files and other responses are much larger. So we had to set this larger.
    private static final int MAX_RESPONSE_SIZE = 1024 * 1024 * 2;

    // Constants for metric names and descriptions
    private static final String COUNTER_HANDLED_NAME_TPL = "%sHdl";
    private static final String COUNTER_HANDLED_DESC_TPL = "number of %s handled";
    private static final String COUNTER_RECEIVED_NAME_TPL = "%sRcv";
    private static final String COUNTER_RECEIVED_DESC_TPL = "number of %s received";
    private static final String COUNTER_FAILED_NAME_TPL = "%sFail";
    private static final String COUNTER_FAILED_DESC_TPL = "number of %s failed";
    private static final String SPEEDOMETER_HANDLED_NAME_TPL = "%sHdl_per_sec";
    private static final String SPEEDOMETER_HANDLED_DESC_TPL = "number of %s handled per second";
    private static final String SPEEDOMETER_RECEIVED_NAME_TPL = "%sRcv_per_sec";
    private static final String SPEEDOMETER_RECEIVED_DESC_TPL = "number of %s received per second";

    /**
     * Per-thread shared {@link BufferedData} for responses. We store these in a thread local, because we do
     * not have control over the thread pool used by the underlying gRPC server.
     */
    @SuppressWarnings(
            "java:S5164") // looks like a false positive ("ThreadLocal" variables should be cleaned up when no longer
    // used), but these threads are long-lived and the lifetime of the thread local is the same as
    // the application
    private static final ThreadLocal<BufferedData> BUFFER_THREAD_LOCAL =
            ThreadLocal.withInitial(() -> BufferedData.allocate(MAX_RESPONSE_SIZE));

    /** The name of the service associated with this method. */
    protected final String serviceName;

    /** The name of the method. */
    protected final String methodName;

    /** A metric for the number of times this method has been invoked */
    private final Counter callsReceivedCounter;

    /** A metric for the number of times this method successfully handled an invocation */
    private final Counter callsHandledCounter;

    /** A metric for the number of times this method failed to handle an invocation */
    private final Counter callsFailedCounter;

    /** A metric for the calls per second successfully this method was invoked */
    private final SpeedometerMetric callsReceivedSpeedometer;

    /** A metric for the calls per second successfully handled by this method */
    private final SpeedometerMetric callsHandledSpeedometer;

    private final int maxMessageSize;

    /**
     * Create a new instance.
     *
     * @param serviceName a non-null reference to the service name
     * @param methodName a non-null reference to the method name
     */
    MethodBase(
            @NonNull final String serviceName,
            @NonNull final String methodName,
            @NonNull final Metrics metrics,
            final int maxMessageSize) {

        this.serviceName = requireNonNull(serviceName);
        this.methodName = requireNonNull(methodName);

        this.callsHandledCounter = counter(metrics, COUNTER_HANDLED_NAME_TPL, COUNTER_HANDLED_DESC_TPL);
        this.callsReceivedCounter = counter(metrics, COUNTER_RECEIVED_NAME_TPL, COUNTER_RECEIVED_DESC_TPL);
        this.callsFailedCounter = counter(metrics, COUNTER_FAILED_NAME_TPL, COUNTER_FAILED_DESC_TPL);
        this.callsHandledSpeedometer = speedometer(metrics, SPEEDOMETER_HANDLED_NAME_TPL, SPEEDOMETER_HANDLED_DESC_TPL);
        this.callsReceivedSpeedometer =
                speedometer(metrics, SPEEDOMETER_RECEIVED_NAME_TPL, SPEEDOMETER_RECEIVED_DESC_TPL);
        this.maxMessageSize = maxMessageSize;
    }

    @Override
    public void invoke(
            @NonNull final BufferedData requestBuffer, @NonNull final StreamObserver<BufferedData> responseObserver) {
        // Track the number of times this method has been called
        callsReceivedCounter.increment();
        callsReceivedSpeedometer.cycle();

        // Fail-fast if the request is too large (Note that the request buffer is sized to allow exactly
        // 1 more byte than maxMessageSize, so we can detect this case).
        if (requestBuffer.length() > maxMessageSize) {
            callsFailedCounter.increment();
            final var exception = new RuntimeException("More than " + maxMessageSize + " received");
            responseObserver.onError(exception);
            return;
        }

        try {
            // Prepare the response buffer
            final var responseBuffer = BUFFER_THREAD_LOCAL.get();
            responseBuffer.reset();

            // Convert the request BufferedData to a Bytes instance without copying the bytes
            final var requestBytes = requestBuffer.getBytes(0, requestBuffer.length());

            // Call the workflow
            handle(requestBytes, responseBuffer);

            // Respond to the client
            responseBuffer.flip();
            responseObserver.onNext(responseBuffer);
            responseObserver.onCompleted();

            // Track the number of times we successfully handled a call
            callsHandledCounter.increment();
            callsHandledSpeedometer.cycle();
        } catch (final Exception e) {
            // Track the number of times we failed to handle a call
            if (!(e instanceof StatusRuntimeException)) {
                logger.error("Unexpected exception while handling a GRPC message", e);
            }
            callsFailedCounter.increment();
            responseObserver.onError(e);
        }
    }

    /**
     * Called to handle the method invocation. Implementations should <b>only</b> throw a {@link RuntimeException}
     * if a gRPC <b>ERROR</b> is to be returned.
     *
     * @param requestBuffer The {@link Bytes} containing the protobuf bytes for the request
     * @param responseBuffer A {@link BufferedData} into which the response protobuf bytes may be written
     */
    protected abstract void handle(@NonNull final Bytes requestBuffer, @NonNull final BufferedData responseBuffer);

    /**
     * Helper method for creating a {@link Counter} metric.
     *
     * @param metrics The {@link Metrics} object to use to create the counter.
     * @param nameTemplate A template to use for generating the metric name
     * @param descriptionTemplate A template to use for generating the metric description
     * @return The metric
     */
    protected final @NonNull Counter counter(
            @NonNull final Metrics metrics,
            @NonNull final String nameTemplate,
            @NonNull final String descriptionTemplate) {
        final String baseName = calculateBaseName();
        final var name = String.format(nameTemplate, baseName);
        final var desc = String.format(descriptionTemplate, baseName);
        return metrics.getOrCreate(new Counter.Config("app", name).withDescription(desc));
    }

    /**
     * Helper method for creating a {@link SpeedometerMetric} metric.
     *
     * @param metrics The {@link Metrics} object to use to create the speedometer.
     * @param nameTemplate A template to use for generating the metric name
     * @param descriptionTemplate A template to use for generating the metric description
     * @return The metric
     */
    protected final @NonNull SpeedometerMetric speedometer(
            @NonNull final Metrics metrics,
            @NonNull final String nameTemplate,
            @NonNull final String descriptionTemplate) {
        final String baseName = calculateBaseName();
        final var name = String.format(nameTemplate, baseName);
        final var desc = String.format(descriptionTemplate, baseName);
        return metrics.getOrCreate(new SpeedometerMetric.Config("app", name).withDescription(desc));
    }

    private String calculateBaseName() {
        return serviceName.substring("proto.".length()).replace('.', ':') + ":" + methodName;
    }
}
