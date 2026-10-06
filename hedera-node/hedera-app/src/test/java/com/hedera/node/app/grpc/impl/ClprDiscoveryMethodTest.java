// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.grpc.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
final class ClprDiscoveryMethodTest {
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
                () -> new ClprDiscoveryMethod(SERVICE_NAME, METHOD_NAME, null, metrics, maxMessageSize));
    }

    @Test
    void handleDelegatesToDiscoveryWorkflow(@Mock final StreamObserver<BufferedData> streamObserver) {
        // Given a request buffer and a ClprDiscoveryMethod wired to a mock workflow
        final var requestBuffer = BufferedData.allocate(100);
        final var expectedRequestBytes = requestBuffer.getBytes(0, requestBuffer.length());
        final var method = new ClprDiscoveryMethod(SERVICE_NAME, METHOD_NAME, workflow, metrics, maxMessageSize);

        // When the method is invoked
        method.invoke(requestBuffer, streamObserver);

        // Then handleDiscovery was called with the request bytes
        final var requestCaptor = ArgumentCaptor.forClass(Bytes.class);
        verify(workflow).handleDiscovery(requestCaptor.capture(), any(BufferedData.class));
        assertThat(requestCaptor.getValue()).isEqualTo(expectedRequestBytes);

        // And handleSync was never called (sanity check that the correct method was routed)
        verify(workflow, never()).handleSync(any(), any());
    }
}
