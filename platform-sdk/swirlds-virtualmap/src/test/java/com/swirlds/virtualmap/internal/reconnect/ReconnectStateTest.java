// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal.reconnect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.swirlds.virtualmap.test.fixtures.VirtualTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Tags;
import org.junit.jupiter.api.Test;

class ReconnectStateTest extends VirtualTestBase {
    // simple unit tests mainly aimed at getting increased code coverage for the ReconnectState class.
    private final ReconnectState state = new ReconnectState(0L, 1L);

    @Test
    @Tags({@Tag("Reconnect")})
    @DisplayName("getLabel()")
    void testGetLabel() {
        assertThrows(UnsupportedOperationException.class, () -> state.getLabel(), "getLabel is not supported.");
    }

    @Test
    @Tags({@Tag("Reconnect")})
    @DisplayName("getFirstLeafPath()")
    void testGetFirstLeafPath() {
        assertEquals(0L, state.getFirstLeafPath(), "Unexpected value for getFirstLeafPath()");
    }

    @Test
    @Tags({@Tag("Reconnect")})
    @DisplayName("getLastLeafPath()")
    void testGetLastLeafPath() {
        assertEquals(1L, state.getLastLeafPath(), "Unexpected value for getLastLeafPath()");
    }

    @Test
    @Tags({@Tag("Reconnect")})
    @DisplayName("Size()")
    void testSize() {
        assertEquals(2L, state.size(), "Unexpected value for size()");
        state.setFirstLeafPath(-1L);
        state.setLastLeafPath(-1L);
        assertEquals(0L, state.size(), "Unexpected value for size()");
    }
}
