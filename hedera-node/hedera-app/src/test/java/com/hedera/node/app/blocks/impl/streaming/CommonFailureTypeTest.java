// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.ConnectException;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class CommonFailureTypeTest {

    @ParameterizedTest
    @MethodSource("failureArgs")
    void testFindCommonFailureType(final Throwable failure, final FailureType expectedType) {
        assertThat(FailureType.findFailureType(failure)).isEqualTo(expectedType);
    }

    static Stream<Arguments> failureArgs() {
        return Stream.of(
                Arguments.of(new ConnectException("Connection Refused"), FailureType.CONNECTION_REFUSED),
                Arguments.of(
                        new RuntimeException("foo", new ConnectException("Connection refused")),
                        FailureType.CONNECTION_REFUSED),
                Arguments.of(new ConnectException("Connection broken"), FailureType.OTHER),
                Arguments.of(new SocketException("SOCKET CLOSED"), FailureType.SOCKET_CLOSED),
                Arguments.of(
                        new RuntimeException("foo", new RuntimeException("bar", new SocketException("socket closed"))),
                        FailureType.SOCKET_CLOSED),
                Arguments.of(new SocketException("broken pipe"), FailureType.BROKEN_PIPE),
                Arguments.of(
                        new RuntimeException("foo", new RuntimeException("bar", new SocketException("BROKEN pipe"))),
                        FailureType.BROKEN_PIPE),
                Arguments.of(new SocketException("destroyed pipe"), FailureType.OTHER),
                Arguments.of(new UnknownHostException("foo.localhost"), FailureType.UNKNOWN_HOST),
                Arguments.of(new UnknownHostException(), FailureType.UNKNOWN_HOST),
                Arguments.of(
                        new RuntimeException("foo", new UnknownHostException("foo.local")), FailureType.UNKNOWN_HOST),
                Arguments.of(
                        new IllegalArgumentException("Failed to get address for host foo.bar.local"),
                        FailureType.UNKNOWN_HOST),
                Arguments.of(
                        new RuntimeException(
                                "foo", new IllegalArgumentException("Failed to get address for host foo.bar.local")),
                        FailureType.UNKNOWN_HOST),
                Arguments.of(new IllegalArgumentException("Failed to resolve address: foo.local"), FailureType.OTHER),
                Arguments.of(new InterruptedException(), FailureType.INTERRUPTED),
                Arguments.of(new TimeoutException(), FailureType.TIMEOUT),
                Arguments.of(new RuntimeException(), FailureType.OTHER),
                Arguments.of(null, FailureType.OTHER));
    }
}
