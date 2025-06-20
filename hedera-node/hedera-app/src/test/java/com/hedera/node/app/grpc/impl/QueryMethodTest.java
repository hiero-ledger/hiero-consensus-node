// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.grpc.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.hedera.node.app.utils.TestUtils;
import com.hedera.node.app.workflows.query.QueryWorkflow;
import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.metrics.api.Counter;
import com.swirlds.metrics.api.Metrics;
import io.grpc.stub.StreamObserver;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
final class QueryMethodTest {
    private static final String SERVICE_NAME = "proto.testService";
    private static final String METHOD_NAME = "testMethod";

    private final QueryWorkflow queryWorkflow = (requestBuffer, responseBuffer) -> {};
    private final Metrics metrics = TestUtils.metrics();
    private final int maxMessageSize = 6144;

    @Test
    void nullServiceNameThrows() {
        //noinspection ConstantConditions
        assertThrows(
                NullPointerException.class,
                () -> new QueryMethod(null, METHOD_NAME, queryWorkflow, metrics, maxMessageSize));
    }

    @Test
    void nullMethodNameThrows() {
        //noinspection ConstantConditions
        assertThrows(
                NullPointerException.class,
                () -> new QueryMethod(SERVICE_NAME, null, queryWorkflow, metrics, maxMessageSize));
    }

    @Test
    void nullWorkflowThrows() {
        //noinspection ConstantConditions
        assertThrows(
                NullPointerException.class,
                () -> new QueryMethod(SERVICE_NAME, METHOD_NAME, null, metrics, maxMessageSize));
    }

    @Test
    void nullMetricsThrows() {
        //noinspection ConstantConditions
        assertThrows(
                NullPointerException.class,
                () -> new QueryMethod(SERVICE_NAME, METHOD_NAME, queryWorkflow, null, maxMessageSize));
    }

    @ParameterizedTest(name = "With {0} bytes")
    @ValueSource(ints = {1024 * 6 + 1, 1024 * 1024})
    void parseStreamThatIsTooBig(int numBytes) {
        final var arr = TestUtils.randomBytes(numBytes);
        final var requestBuffer = BufferedData.wrap(arr);
        final AtomicBoolean called = new AtomicBoolean(false);
        final QueryWorkflow w = (req, res) -> called.set(true);
        final var method = new QueryMethod(SERVICE_NAME, METHOD_NAME, w, metrics, maxMessageSize);

        // When we invoke the method
        //noinspection unchecked
        method.invoke(requestBuffer, mock(StreamObserver.class));

        // Then the workflow was not called
        assertThat(called.get()).isFalse();
        // And the counter for receiving the request was incremented
        assertThat(counter("Rcv").get()).isEqualTo(1L);
        // And the counter for handling the request was not incremented
        assertThat(counter("Hdl").get()).isZero();
        // And the counter for failing to handle the request was incremented
        assertThat(counter("Fail").get()).isEqualTo(1L);
        // And the counter for answering the query was not incremented
        assertThat(counter("Sub").get()).isZero();
    }

    @Test
    void handleDelegatesToWorkflow(@Mock final StreamObserver<BufferedData> streamObserver) {
        // Given a request with data and a workflow that should be called, and a QueryMethod
        final var requestBuffer = BufferedData.allocate(100);
        final AtomicBoolean called = new AtomicBoolean(false);
        final QueryWorkflow w = (req, res) -> {
            assertEquals(requestBuffer.getBytes(0, requestBuffer.length()), req);
            called.set(true);
            res.writeBytes(new byte[] {1, 2, 3});
        };
        final var method = new QueryMethod(SERVICE_NAME, METHOD_NAME, w, metrics, maxMessageSize);

        // When the method is invoked
        method.invoke(requestBuffer, streamObserver);

        // Then the workflow was called
        assertTrue(called.get());

        // And the counter for receiving the query was incremented
        assertThat(counter("Rcv").get()).isEqualTo(1L);
        // And the counter for handling the query was incremented
        assertThat(counter("Hdl").get()).isEqualTo(1L);
        // And the counter for answering the query was incremented
        assertThat(counter("Sub").get()).isEqualTo(1L);
        // But the counter for failing to handle the query was not incremented
        assertThat(counter("Fail").get()).isZero();

        // And the response includes the data written by the workflow
        final var expectedResponseBytes = Bytes.wrap(new byte[] {1, 2, 3});
        verify(streamObserver).onNext(Mockito.argThat(response -> response.getBytes(0, response.length())
                .equals(expectedResponseBytes)));
    }

    @Test
    void unexpectedExceptionFromHandler(@Mock final StreamObserver<BufferedData> streamObserver) {
        // Given a request with data and a workflow that will throw, and a QueryMethod
        final var requestBuffer = BufferedData.allocate(100);
        final QueryWorkflow w = (req, res) -> {
            throw new RuntimeException("Unexpected!");
        };
        final var method = new QueryMethod(SERVICE_NAME, METHOD_NAME, w, metrics, maxMessageSize);

        // When the method is invoked
        method.invoke(requestBuffer, streamObserver);

        // Then the counter for receiving the query was incremented
        assertThat(counter("Rcv").get()).isEqualTo(1L);
        // And the counter for failing to handle the query was incremented
        assertThat(counter("Fail").get()).isEqualTo(1L);
        // But the counter for handling the query was NOT
        assertThat(counter("Hdl").get()).isZero();
        // And the counter for answering the query was NOT
        assertThat(counter("Sub").get()).isZero();

        // And the stream observer was notified of the error
        verify(streamObserver).onError(Mockito.any());
    }

    private Counter counter(String suffix) {
        return (Counter)
                metrics.getMetric("app", SERVICE_NAME.substring("proto.".length()) + ":" + METHOD_NAME + suffix);
    }

    @Test
    @DisplayName("Hammer the TransactionMethod from multiple threads")
    void hammer() {
        final var numThreads = 5;
        final var numRequests = 1000;
        final QueryWorkflow w = (req, res) -> res.writeBytes(req);
        final var method = new QueryMethod(SERVICE_NAME, METHOD_NAME, w, metrics, maxMessageSize);

        final var futures = new ArrayList<Future<?>>();
        final var exec = Executors.newFixedThreadPool(numThreads);
        for (int i = 0; i < numRequests; i++) {
            final var data = "Query " + i;
            futures.add(exec.submit(() -> {
                final var requestBuffer = BufferedData.wrap(data.getBytes(StandardCharsets.UTF_8));
                final var observer = new StubbedStreamObserver();
                method.invoke(requestBuffer, observer);
                assertThat(observer.responseData).isEqualTo(data);
            }));
        }

        for (final var future : futures) {
            try {
                future.get();
            } catch (final Exception e) {
                throw new AssertionError("Unexpected error", e);
            }
        }

        assertThat(counter("Rcv").get()).isEqualTo(numRequests);
        assertThat(counter("Hdl").get()).isEqualTo(numRequests);
        assertThat(counter("Sub").get()).isEqualTo(numRequests);
        assertThat(counter("Fail").get()).isZero();
    }

    private static final class StubbedStreamObserver implements StreamObserver<BufferedData> {
        private String responseData;

        @Override
        public void onNext(BufferedData value) {
            responseData = value.asUtf8String();
        }

        @Override
        public void onError(Throwable t) {
            throw new AssertionError("Unexpected error", t);
        }

        @Override
        public void onCompleted() {
            // No-op
        }
    }
}
