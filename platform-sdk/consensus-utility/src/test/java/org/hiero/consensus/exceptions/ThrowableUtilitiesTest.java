// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.exceptions;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.SocketException;
import javax.net.ssl.SSLException;
import org.junit.jupiter.api.Test;

class ThrowableUtilitiesTest {

    @Test
    void isOrCausedBySocketExceptionTest() {
        assertFalse(ThrowableUtilities.isOrCausedBySocketException(null));

        final SSLException sslException = new SSLException("sslException");
        assertFalse(ThrowableUtilities.isOrCausedBySocketException(sslException));

        final SocketException socketException = new SocketException();
        assertTrue(ThrowableUtilities.isOrCausedBySocketException(socketException));

        final SSLException sslExCausedBySocketEx = new SSLException(socketException);
        assertTrue(ThrowableUtilities.isOrCausedBySocketException(sslExCausedBySocketEx));

        final SSLException sslExceptionMultiLayer = new SSLException(sslExCausedBySocketEx);
        assertTrue(ThrowableUtilities.isOrCausedBySocketException(sslExceptionMultiLayer));
    }

    @Test
    void isRootCauseSuppliedTypeTest() {
        assertTrue(
                ThrowableUtilities.isRootCauseSuppliedType(
                        new Exception(new IllegalArgumentException(new IOException())), IOException.class),
                "root cause should be IOException");

        assertFalse(
                ThrowableUtilities.isRootCauseSuppliedType(
                        new Exception(new IllegalArgumentException(new NullPointerException())), IOException.class),
                "root cause should not be IOException");

        assertFalse(
                ThrowableUtilities.isRootCauseSuppliedType(
                        new IOException(new IllegalArgumentException(new NullPointerException())), IOException.class),
                "root cause should not be IOException, even though is exists in the stack");
    }
}
