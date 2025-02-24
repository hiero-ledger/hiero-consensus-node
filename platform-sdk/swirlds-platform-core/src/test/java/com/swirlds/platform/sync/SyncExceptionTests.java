// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.swirlds.platform.gossip.SyncException;
import com.swirlds.platform.network.Connection;
import org.junit.jupiter.api.Test;

class SyncExceptionTests {

    private static final String msg = "hello swirlds";
    private static final String description = "connection description";

    @Test
    void testMessage() {
        SyncException e = new SyncException(msg);
        assertEquals(msg, e.getMessage(), "Message does not match the message provided.");
    }

    @Test
    void testConnection() {
        Connection conn = mock(Connection.class);
        when(conn.getDescription()).thenReturn(description);

        SyncException e = new SyncException(conn, msg);
        assertTrue(e.getMessage().contains(description), "Description does not match the description provided.");
        assertTrue(e.getMessage().contains(msg), "Message does not match the message provided.");
        assertNull(e.getCause(), "Cause should be null when none is provided.");
    }

    @Test
    void testCause() {
        Connection conn = mock(Connection.class);
        when(conn.getDescription()).thenReturn(description);
        Throwable t = mock(Throwable.class);
        SyncException e = new SyncException(conn, msg, t);

        assertEquals(t, e.getCause(), "Cause does not match the cause provided.");
        assertTrue(e.getMessage().contains(description), "Description does not match the description provided.");
        assertTrue(e.getMessage().contains(msg), "Message does not match the message provided.");
    }
}
