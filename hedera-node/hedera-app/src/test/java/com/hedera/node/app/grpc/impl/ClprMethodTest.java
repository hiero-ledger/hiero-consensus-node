// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.grpc.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.hedera.node.app.utils.TestUtils;
import com.hedera.node.app.workflows.clpr.ClprSyncWorkflow;
import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.metrics.api.Metrics;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
final class ClprMethodTest {
    private static final String SERVICE_NAME = "proto.testService";
    private static final String METHOD_NAME = "testMethod";

    private final ClprSyncWorkflow workflow = mock(ClprSyncWorkflow.class);
    private final Metrics metrics = TestUtils.metrics();
    private final int maxMessageSize = 6144;

    @Test
    void nullWorkflowThrows() {
        //noinspection ConstantConditions
        assertThrows(
                NullPointerException.class,
                () -> new ClprMethod(SERVICE_NAME, METHOD_NAME, null, metrics, maxMessageSize));
    }

    @Test
    void handleDelegatesToSyncWorkflow(@Mock final StreamObserver<BufferedData> streamObserver) {
        // Given a request buffer and a ClprMethod wired to a mock workflow
        final var requestBuffer = BufferedData.allocate(100);
        final var expectedRequestBytes = requestBuffer.getBytes(0, requestBuffer.length());
        final var method = new ClprMethod(SERVICE_NAME, METHOD_NAME, workflow, metrics, maxMessageSize);

        // When the method is invoked
        method.invoke(requestBuffer, streamObserver);

        // Then handleSync was called with the request bytes
        final var requestCaptor = ArgumentCaptor.forClass(Bytes.class);
        verify(workflow).handleSync(requestCaptor.capture(), any(BufferedData.class));
        assertThat(requestCaptor.getValue()).isEqualTo(expectedRequestBytes);
    }
}
