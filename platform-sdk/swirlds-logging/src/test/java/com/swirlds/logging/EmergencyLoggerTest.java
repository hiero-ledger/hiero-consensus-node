// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging;

import com.swirlds.base.test.fixtures.io.SystemErrProvider;
import com.swirlds.base.test.fixtures.io.WithSystemError;
import com.swirlds.base.test.fixtures.io.WithSystemOut;
import com.swirlds.logging.api.Level;
import com.swirlds.logging.api.Marker;
import com.swirlds.logging.api.extensions.event.LogEventFactory;
import com.swirlds.logging.api.internal.emergency.EmergencyLoggerImpl;
import com.swirlds.logging.api.internal.event.SimpleLogEventFactory;
import jakarta.inject.Inject;
import java.util.Map;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

@WithSystemError
@WithSystemOut
public class EmergencyLoggerTest {

    @Inject
    SystemErrProvider systemErrProvider;

    @Test
    void testLog1Line() {
        // given
        EmergencyLoggerImpl emergencyLogger = EmergencyLoggerImpl.getInstance();

        // when
        emergencyLogger.log(Level.INFO, "test");

        // then
        Assertions.assertEquals(1, systemErrProvider.getLines().count());
        Assertions.assertTrue(systemErrProvider.getLines().toList().getFirst().endsWith("EMERGENCY-LOGGER - test"));
    }

    @Test
    void testLogMultipleLines() {
        // given
        EmergencyLoggerImpl emergencyLogger = EmergencyLoggerImpl.getInstance();

        // when
        emergencyLogger.log(Level.INFO, "test");
        emergencyLogger.log(Level.INFO, "test1");
        emergencyLogger.log(Level.INFO, "test2");
        emergencyLogger.log(Level.INFO, "test3");

        // then
        Assertions.assertEquals(4, systemErrProvider.getLines().count());
    }

    @Test
    void testDefaultLevel() {
        // given
        EmergencyLoggerImpl emergencyLogger = EmergencyLoggerImpl.getInstance();

        // when
        emergencyLogger.log(Level.ERROR, "test");
        emergencyLogger.log(Level.WARN, "test");
        emergencyLogger.log(Level.INFO, "test");
        emergencyLogger.log(Level.DEBUG, "test");
        emergencyLogger.log(Level.TRACE, "test");

        // then
        Assertions.assertEquals(
                4,
                systemErrProvider.getLines().count(),
                "Only ERROR, WARNING, INFO and DEBUG should be logged by default");
    }

    @Test
    void loggerMustBe100Solid() {
        // given
        LogEventFactory logEventFactory = new SimpleLogEventFactory();
        EmergencyLoggerImpl emergencyLogger = EmergencyLoggerImpl.getInstance();
        emergencyLogger.publishLoggedEvents(); // clear the queue

        // when
        Assertions.assertDoesNotThrow(() -> emergencyLogger.log(Level.INFO, null));
        Assertions.assertDoesNotThrow(() -> emergencyLogger.log(null, "message"));
        Assertions.assertDoesNotThrow(() -> emergencyLogger.log(null, null));

        Assertions.assertDoesNotThrow(() -> emergencyLogger.logNPE(null));

        Assertions.assertDoesNotThrow(() -> emergencyLogger.log(null, "message", new RuntimeException()));
        Assertions.assertDoesNotThrow(() -> emergencyLogger.log(Level.INFO, null, new RuntimeException()));
        Assertions.assertDoesNotThrow(() -> emergencyLogger.log(Level.INFO, "message", null));
        Assertions.assertDoesNotThrow(() -> emergencyLogger.log(null, null, null));

        Assertions.assertDoesNotThrow(() -> emergencyLogger.log(null));
        Assertions.assertDoesNotThrow(() -> emergencyLogger.log(logEventFactory.createLogEvent(
                Level.INFO,
                "loggerName",
                "threadName",
                System.currentTimeMillis(),
                (String) null,
                new RuntimeException(),
                new Marker("marker"),
                Map.of())));
        Assertions.assertDoesNotThrow(() -> emergencyLogger.log(logEventFactory.createLogEvent(
                Level.INFO, "loggerName", null, new RuntimeException(), new Marker("marker"), Map.of())));
        Assertions.assertDoesNotThrow(() -> emergencyLogger.log(logEventFactory.createLogEvent(
                Level.INFO,
                "loggerName",
                null,
                System.currentTimeMillis(),
                "message",
                new RuntimeException(),
                new Marker("marker"),
                Map.of())));
        Assertions.assertDoesNotThrow(() -> emergencyLogger.log(logEventFactory.createLogEvent(
                Level.INFO,
                null,
                "threadName",
                System.currentTimeMillis(),
                "message",
                new RuntimeException(),
                new Marker("marker"),
                Map.of())));
        Assertions.assertDoesNotThrow(() -> emergencyLogger.log(logEventFactory.createLogEvent(
                null,
                "loggerName",
                "threadName",
                System.currentTimeMillis(),
                "message",
                new RuntimeException(),
                new Marker("marker"),
                Map.of())));
        Assertions.assertDoesNotThrow(() -> emergencyLogger.log(logEventFactory.createLogEvent(
                Level.INFO,
                "loggerName",
                "threadName",
                System.currentTimeMillis(),
                "message",
                new RuntimeException(),
                null,
                Map.of())));
        Assertions.assertDoesNotThrow(() -> emergencyLogger.log(logEventFactory.createLogEvent(
                Level.INFO,
                "loggerName",
                "threadName",
                System.currentTimeMillis(),
                "message",
                new RuntimeException(),
                new Marker("marker"),
                null)));
        Assertions.assertDoesNotThrow(() -> emergencyLogger.log(logEventFactory.createLogEvent(
                Level.INFO,
                "loggerName",
                "threadName",
                System.currentTimeMillis(),
                "message",
                null,
                new Marker("marker"),
                Map.of())));
    }
}
