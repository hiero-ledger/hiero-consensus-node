// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.gossip.impl.network;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.logging.legacy.LogMarker.SOCKET_EXCEPTIONS;

import java.io.IOException;
import java.net.SocketException;
import javax.net.ssl.SSLException;
import org.hiero.base.concurrent.throttle.StackTraceDeduplicator;
import org.hiero.consensus.gossip.impl.test.fixtures.sync.FakeConnection;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class NetworkUtilsTest {
    private final StackTraceDeduplicator socketExceptionDeduplicator = new StackTraceDeduplicator();

    @Test
    void handleNetworkExceptionTest() {
        final Connection c = new FakeConnection();
        Assertions.assertDoesNotThrow(
                () -> NetworkUtils.handleNetworkException(new Exception(), c, socketExceptionDeduplicator),
                "handling should not throw an exception");
        Assertions.assertFalse(c.connected(), "method should have disconnected the connection");

        Assertions.assertDoesNotThrow(
                () -> NetworkUtils.handleNetworkException(
                        new SSLException("test", new NullPointerException()), null, socketExceptionDeduplicator),
                "handling should not throw an exception");

        Assertions.assertThrows(
                InterruptedException.class,
                () -> NetworkUtils.handleNetworkException(
                        new InterruptedException(), null, socketExceptionDeduplicator),
                "an interrupted exception should be rethrown");
    }

    @Test
    void socketExceptionsAreRegisteredInDeduplicator() throws InterruptedException {
        final Connection c = new FakeConnection();
        final Exception e = new SocketException("Connection reset");

        NetworkUtils.handleNetworkException(e, c, socketExceptionDeduplicator);

        Assertions.assertFalse(c.connected(), "method should have disconnected the connection");
        Assertions.assertFalse(
                socketExceptionDeduplicator.isNew(e), "handling a socket exception should have registered its trace");
    }

    @Test
    void repeatedSocketExceptionsAreHandled() {
        // the first occurrence logs a full stack trace, the following ones a short message; none should fail or throw
        for (int i = 0; i < 3; i++) {
            final Connection c = new FakeConnection();
            Assertions.assertDoesNotThrow(
                    () -> NetworkUtils.handleNetworkException(
                            new SocketException("Connection reset"), c, socketExceptionDeduplicator),
                    "handling should not throw an exception");
            Assertions.assertFalse(c.connected(), "method should have disconnected the connection");
        }
    }

    @Test
    void otherExceptionsAreNotTracked() throws InterruptedException {
        final Exception e = new IllegalStateException("not a socket exception");

        NetworkUtils.handleNetworkException(e, new FakeConnection(), socketExceptionDeduplicator);

        Assertions.assertTrue(
                socketExceptionDeduplicator.isNew(e),
                "only socket exceptions should be tracked, other ones are always logged in full");
    }

    @Test
    void exceptionMarkerTest() {
        Assertions.assertEquals(
                SOCKET_EXCEPTIONS.getMarker(), NetworkUtils.determineExceptionMarker(new IOException("test")));
        Assertions.assertEquals(
                EXCEPTION.getMarker(), NetworkUtils.determineExceptionMarker(new IllegalStateException("test")));
    }
}
