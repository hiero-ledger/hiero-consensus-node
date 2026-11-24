// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.clpr;

import static org.mockito.Mockito.verify;

import io.grpc.stub.BlockingClientCall;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Tests for {@link ClprStreamingSyncCall}.
 */
@ExtendWith(MockitoExtension.class)
class ClprStreamingSyncCallTest {
    @Mock
    private BlockingClientCall<byte[], byte[]> call;

    static Stream<Arguments> causes() {
        return Stream.of(
                Arguments.of("interrupted", new InterruptedException()),
                Arguments.of("failed", new IllegalStateException("boom")),
                Arguments.of("no cause", null));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("causes")
    @DisplayName("cancel passes the message and cause to the underlying call, whatever the cause")
    void cancelPassesCauseToCall(final String description, final Throwable cause) {
        final var subject = new ClprStreamingSyncCall(call, "127.0.0.1:50211");

        subject.cancel(description, cause);

        verify(call).cancel(description, cause);
    }
}
