// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.SocketException;
import javax.net.ssl.SSLException;
import org.junit.jupiter.api.Test;

class UtilitiesTest {

    @Test
    void isOrCausedBySocketExceptionTest() {
        assertFalse(Utilities.isOrCausedBySocketException(null));

        final SSLException sslException = new SSLException("sslException");
        assertFalse(Utilities.isOrCausedBySocketException(sslException));

        final SocketException socketException = new SocketException();
        assertTrue(Utilities.isOrCausedBySocketException(socketException));

        final SSLException sslExCausedBySocketEx = new SSLException(socketException);
        assertTrue(Utilities.isOrCausedBySocketException(sslExCausedBySocketEx));

        final SSLException sslExceptionMultiLayer = new SSLException(sslExCausedBySocketEx);
        assertTrue(Utilities.isOrCausedBySocketException(sslExceptionMultiLayer));
    }

    @Test
    void isRootCauseSuppliedTypeTest() {
        assertTrue(
                Utilities.isRootCauseSuppliedType(
                        new Exception(new IllegalArgumentException(new IOException())), IOException.class),
                "root cause should be IOException");

        assertFalse(
                Utilities.isRootCauseSuppliedType(
                        new Exception(new IllegalArgumentException(new NullPointerException())), IOException.class),
                "root cause should not be IOException");

        assertFalse(
                Utilities.isRootCauseSuppliedType(
                        new IOException(new IllegalArgumentException(new NullPointerException())), IOException.class),
                "root cause should not be IOException, even though is exists in the stack");
    }
}
