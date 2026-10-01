// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.gossip.impl.network;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.logging.legacy.LogMarker.SOCKET_EXCEPTIONS;

import java.io.IOException;
import java.net.SocketException;
import javax.net.ssl.SSLException;
import org.hiero.consensus.gossip.impl.test.fixtures.sync.FakeConnection;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class NetworkUtilsTest {

    @Test
    void handleNetworkExceptionTest() {
        final Connection c = new FakeConnection();
        Assertions.assertDoesNotThrow(
                () -> NetworkUtils.handleNetworkException(new Exception(), c),
                "handling should not throw an exception");
        Assertions.assertFalse(c.connected(), "method should have disconnected the connection");

        Assertions.assertDoesNotThrow(
                () -> NetworkUtils.handleNetworkException(new SSLException("test", new NullPointerException()), null),
                "handling should not throw an exception");

        Assertions.assertThrows(
                InterruptedException.class,
                () -> NetworkUtils.handleNetworkException(new InterruptedException(), null),
                "an interrupted exception should be rethrown");
    }

    @Test
    void repeatedSocketExceptionsAreHandled() {
        // the first occurrence logs a full stack trace, the following ones a short message; none should fail or throw
        for (int i = 0; i < 3; i++) {
            final Connection c = new FakeConnection();
            Assertions.assertDoesNotThrow(
                    () -> NetworkUtils.handleNetworkException(new SocketException("Connection reset"), c),
                    "handling should not throw an exception");
            Assertions.assertFalse(c.connected(), "method should have disconnected the connection");
        }
    }

    @Test
    void exceptionMarkerTest() {
        Assertions.assertEquals(
                SOCKET_EXCEPTIONS.getMarker(), NetworkUtils.determineExceptionMarker(new IOException("test")));
        Assertions.assertEquals(
                EXCEPTION.getMarker(), NetworkUtils.determineExceptionMarker(new IllegalStateException("test")));
    }
}
