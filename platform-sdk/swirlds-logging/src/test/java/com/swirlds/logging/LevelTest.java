// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging;

import com.swirlds.logging.api.Level;
import com.swirlds.logging.api.internal.emergency.EmergencyLoggerImpl;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

@Disabled
class LevelTest {

    @Test
    void testError() {
        // given
        Level level = Level.ERROR;

        // then
        Assertions.assertTrue(level.enabledLoggingOfLevel(Level.ERROR));
        Assertions.assertFalse(level.enabledLoggingOfLevel(Level.WARN));
        Assertions.assertFalse(level.enabledLoggingOfLevel(Level.INFO));
        Assertions.assertFalse(level.enabledLoggingOfLevel(Level.DEBUG));
        Assertions.assertFalse(level.enabledLoggingOfLevel(Level.TRACE));
        Assertions.assertTrue(level.enabledLoggingOfLevel(null), "for null param it should return true");
        Assertions.assertEquals("ERROR", level.name());
    }

    @Test
    void testWarn() {
        // given
        Level level = Level.WARN;

        // then
        Assertions.assertTrue(level.enabledLoggingOfLevel(Level.ERROR));
        Assertions.assertTrue(level.enabledLoggingOfLevel(Level.WARN));
        Assertions.assertFalse(level.enabledLoggingOfLevel(Level.INFO));
        Assertions.assertFalse(level.enabledLoggingOfLevel(Level.DEBUG));
        Assertions.assertFalse(level.enabledLoggingOfLevel(Level.TRACE));
        Assertions.assertTrue(level.enabledLoggingOfLevel(null), "for null param it should return true");
        Assertions.assertEquals("WARN", level.name());
    }

    @Test
    void testInfo() {
        // given
        Level level = Level.INFO;

        // then
        Assertions.assertTrue(level.enabledLoggingOfLevel(Level.ERROR));
        Assertions.assertTrue(level.enabledLoggingOfLevel(Level.WARN));
        Assertions.assertTrue(level.enabledLoggingOfLevel(Level.INFO));
        Assertions.assertFalse(level.enabledLoggingOfLevel(Level.DEBUG));
        Assertions.assertFalse(level.enabledLoggingOfLevel(Level.TRACE));
        Assertions.assertTrue(level.enabledLoggingOfLevel(null), "for null param it should return true");
        Assertions.assertEquals("INFO", level.name());
    }

    @Test
    void testDebug() {
        // given
        EmergencyLoggerImpl.getInstance();
        Level level = Level.DEBUG;

        // then
        Assertions.assertTrue(level.enabledLoggingOfLevel(Level.ERROR));
        Assertions.assertTrue(level.enabledLoggingOfLevel(Level.WARN));
        Assertions.assertTrue(level.enabledLoggingOfLevel(Level.INFO));
        Assertions.assertTrue(level.enabledLoggingOfLevel(Level.DEBUG));
        Assertions.assertFalse(level.enabledLoggingOfLevel(Level.TRACE));
        Assertions.assertTrue(level.enabledLoggingOfLevel(null), "for null param it should return true");
        Assertions.assertEquals("DEBUG", level.name());
    }

    @Test
    void testTrace() {
        // given
        Level level = Level.TRACE;

        // then
        Assertions.assertTrue(level.enabledLoggingOfLevel(Level.ERROR));
        Assertions.assertTrue(level.enabledLoggingOfLevel(Level.WARN));
        Assertions.assertTrue(level.enabledLoggingOfLevel(Level.INFO));
        Assertions.assertTrue(level.enabledLoggingOfLevel(Level.DEBUG));
        Assertions.assertTrue(level.enabledLoggingOfLevel(Level.TRACE));
        Assertions.assertTrue(level.enabledLoggingOfLevel(null), "for null param it should return true");
        Assertions.assertEquals("TRACE", level.name());
    }
}
