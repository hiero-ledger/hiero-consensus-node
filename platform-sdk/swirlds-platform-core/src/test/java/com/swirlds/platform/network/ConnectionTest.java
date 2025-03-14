// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.network;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.platform.network.connection.NotConnectedConnection;
import org.junit.jupiter.api.Test;

class ConnectionTest {

    public static final String THROW_MESSAGE = "should throw when calling this method";

    @Test
    void notConnectedConnectionTest() {
        Connection c = NotConnectedConnection.getSingleton();
        assertDoesNotThrow(c::disconnect, "disconnect should do nothing");
        assertTrue(
                c.getDescription().toLowerCase().contains("notconnected"),
                "description should indicate it is not connected");
        assertFalse(c.connected(), "should never be connected");
        assertThrows(Exception.class, c::getSelfId, THROW_MESSAGE);
        assertThrows(Exception.class, c::getOtherId, THROW_MESSAGE);
        assertThrows(Exception.class, c::getDis, THROW_MESSAGE);
        assertThrows(Exception.class, c::getDos, THROW_MESSAGE);
        assertThrows(Exception.class, c::initForSync, THROW_MESSAGE);
        assertThrows(Exception.class, c::isOutbound, THROW_MESSAGE);
        assertThrows(Exception.class, c::getTimeout, THROW_MESSAGE);
        assertThrows(Exception.class, () -> c.setTimeout(0), THROW_MESSAGE);
    }
}
