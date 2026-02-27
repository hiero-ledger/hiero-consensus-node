// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.grpc.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.hedera.node.app.utils.TestUtils;
import com.hedera.node.app.workflows.synchronous.SynchronousWorkflow;
import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.metrics.api.Counter;
import com.swirlds.metrics.api.Metrics;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
final class SynchronousTransactionMethodTest {
    private static final String SERVICE_NAME = "proto.SynchronousService";
    private static final String METHOD_NAME = "syncTransaction";

    private final SynchronousWorkflow syncWorkflow = (requestBuffer, responseBuffer) -> {};
    private final Metrics metrics = TestUtils.metrics();
    private final int maxMessageSize = 6144;

    @Test
    void nullServiceNameThrows() {
        //noinspection ConstantConditions
        assertThatThrownBy(() ->
                        new SynchronousTransactionMethod(null, METHOD_NAME, syncWorkflow, metrics, maxMessageSize))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void nullMethodNameThrows() {
        //noinspection ConstantConditions
        assertThatThrownBy(() ->
                        new SynchronousTransactionMethod(SERVICE_NAME, null, syncWorkflow, metrics, maxMessageSize))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void nullWorkflowThrows() {
        //noinspection ConstantConditions
        assertThatThrownBy(() ->
                        new SynchronousTransactionMethod(SERVICE_NAME, METHOD_NAME, null, metrics, maxMessageSize))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void nullMetricsThrows() {
        //noinspection ConstantConditions
        assertThatThrownBy(() ->
                        new SynchronousTransactionMethod(SERVICE_NAME, METHOD_NAME, syncWorkflow, null, maxMessageSize))
                .isInstanceOf(NullPointerException.class);
    }

    @ParameterizedTest(name = "With {0} bytes")
    @ValueSource(ints = {1024 * 6 + 1, 1024 * 1024})
    void parseStreamThatIsTooBig(int numBytes) {
        final var arr = TestUtils.randomBytes(numBytes);
        final var requestBuffer = BufferedData.wrap(arr);
        final AtomicBoolean called = new AtomicBoolean(false);
        final SynchronousWorkflow w = (req, res) -> called.set(true);
        final var method = new SynchronousTransactionMethod(SERVICE_NAME, METHOD_NAME, w, metrics, maxMessageSize);

        //noinspection unchecked
        method.invoke(requestBuffer, mock(StreamObserver.class));

        assertThat(called.get()).isFalse();
        assertThat(counter("Rcv").get()).isEqualTo(1L);
        assertThat(counter("Hdl").get()).isZero();
        assertThat(counter("Fail").get()).isEqualTo(1L);
    }

    @Test
    void handleDelegatesToWorkflow(@Mock final StreamObserver<BufferedData> streamObserver) {
        // Given a request with data and a workflow that should be called
        final var requestBuffer = BufferedData.allocate(100);
        final AtomicBoolean called = new AtomicBoolean(false);
        final SynchronousWorkflow w = (req, res) -> {
            assertThat(req).isEqualTo(requestBuffer.getBytes(0, requestBuffer.length()));
            called.set(true);
            res.writeBytes(new byte[] {1, 2, 3});
        };
        final var method = new SynchronousTransactionMethod(SERVICE_NAME, METHOD_NAME, w, metrics, maxMessageSize);

        method.invoke(requestBuffer, streamObserver);

        assertThat(called.get()).isTrue();
        assertThat(counter("Rcv").get()).isEqualTo(1L);
        assertThat(counter("Hdl").get()).isEqualTo(1L);
        assertThat(counter("Fail").get()).isZero();

        final var expectedResponseBytes = Bytes.wrap(new byte[] {1, 2, 3});
        verify(streamObserver).onNext(Mockito.argThat(response -> response.getBytes(0, response.length())
                .equals(expectedResponseBytes)));
    }

    @Test
    void statusRuntimeExceptionFromWorkflowPropagatedToObserver(
            @Mock final StreamObserver<BufferedData> streamObserver) {
        // Given a workflow that throws StatusRuntimeException
        final var requestBuffer = BufferedData.allocate(100);
        final SynchronousWorkflow w = (req, res) -> {
            throw new StatusRuntimeException(Status.FAILED_PRECONDITION.withDescription("INVALID_TRANSACTION"));
        };
        final var method = new SynchronousTransactionMethod(SERVICE_NAME, METHOD_NAME, w, metrics, maxMessageSize);

        method.invoke(requestBuffer, streamObserver);

        assertThat(counter("Rcv").get()).isEqualTo(1L);
        assertThat(counter("Fail").get()).isEqualTo(1L);
        assertThat(counter("Hdl").get()).isZero();

        verify(streamObserver).onError(Mockito.any());
    }

    @Test
    void unexpectedExceptionFromWorkflow(@Mock final StreamObserver<BufferedData> streamObserver) {
        final var requestBuffer = BufferedData.allocate(100);
        final SynchronousWorkflow w = (req, res) -> {
            throw new RuntimeException("Failing!!");
        };
        final var method = new SynchronousTransactionMethod(SERVICE_NAME, METHOD_NAME, w, metrics, maxMessageSize);

        method.invoke(requestBuffer, streamObserver);

        assertThat(counter("Rcv").get()).isEqualTo(1L);
        assertThat(counter("Fail").get()).isEqualTo(1L);
        assertThat(counter("Hdl").get()).isZero();

        verify(streamObserver).onError(Mockito.any());
    }

    private Counter counter(String suffix) {
        return (Counter)
                metrics.getMetric("app", SERVICE_NAME.substring("proto.".length()) + ":" + METHOD_NAME + suffix);
    }
}
