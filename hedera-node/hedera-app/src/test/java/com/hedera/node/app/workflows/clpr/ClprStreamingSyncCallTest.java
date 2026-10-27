// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.clpr;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.state.clpr.ClprStreamingSyncPayload;
import com.hedera.node.app.workflows.clpr.ClprEndpointClient.ClprSyncException;
import io.grpc.stub.BlockingClientCall;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ClprStreamingSyncCallTest {

    private static final Duration STEP_TIMEOUT = Duration.ofSeconds(5);

    @Mock
    private BlockingClientCall<byte[], byte[]> call;

    @Test
    @DisplayName("a read that outlasts the step timeout cancels the call and fails the sync")
    void readTimeoutCancelsTheCall() throws Exception {
        given(call.read(STEP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)).willThrow(new TimeoutException());
        final var subject = new ClprStreamingSyncCall(call, "203.0.113.1:50211", STEP_TIMEOUT);

        assertThatThrownBy(subject::read).isInstanceOf(ClprSyncException.class).hasMessageContaining("timed out");
        verify(call).cancel(eq("Streaming sync read timed out"), any(TimeoutException.class));
    }

    @Test
    @DisplayName("a write that outlasts the step timeout cancels the call and fails the sync")
    void writeTimeoutCancelsTheCall() throws Exception {
        given(call.write(any(), eq(STEP_TIMEOUT.toMillis()), eq(TimeUnit.MILLISECONDS)))
                .willThrow(new TimeoutException());
        final var subject = new ClprStreamingSyncCall(call, "203.0.113.1:50211", STEP_TIMEOUT);

        assertThatThrownBy(() -> subject.write(ClprStreamingSyncPayload.DEFAULT))
                .isInstanceOf(ClprSyncException.class)
                .hasMessageContaining("timed out");
        verify(call).cancel(eq("Streaming sync write timed out"), any(TimeoutException.class));
    }
}
