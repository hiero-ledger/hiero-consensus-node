// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging;

import com.swirlds.base.test.fixtures.io.WithSystemError;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.logging.api.Level;
import com.swirlds.logging.api.Logger;
import com.swirlds.logging.api.Marker;
import com.swirlds.logging.api.extensions.event.LogEvent;
import com.swirlds.logging.api.extensions.event.LogEventConsumer;
import com.swirlds.logging.api.internal.LoggerImpl;
import com.swirlds.logging.api.internal.LoggingSystem;
import com.swirlds.logging.api.internal.configuration.ConfigLevelConverter;
import com.swirlds.logging.api.internal.configuration.MarkerStateConverter;
import com.swirlds.logging.api.internal.event.SimpleLogEventFactory;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

@WithSystemError
public class LoggerImplTest {

    @Test
    void testSimpleLogger() {
        // given
        LoggerImpl logger = new LoggerImpl("test-name", new SimpleLogEventFactory(), dummySystem());

        // when
        final String name = logger.getName();
        final boolean traceEnabled = logger.isTraceEnabled();
        final boolean debugEnabled = logger.isDebugEnabled();
        final boolean infoEnabled = logger.isInfoEnabled();
        final boolean warnEnabled = logger.isWarnEnabled();
        final boolean errorEnabled = logger.isErrorEnabled();

        // then
        Assertions.assertEquals("test-name", name);
        Assertions.assertTrue(traceEnabled);
        Assertions.assertTrue(debugEnabled);
        Assertions.assertTrue(infoEnabled);
        Assertions.assertTrue(warnEnabled);
        Assertions.assertTrue(errorEnabled);
    }

    @Test
    void testNullLogEventConsumer() {
        Assertions.assertThrows(
                NullPointerException.class, () -> new LoggerImpl("test-name", new SimpleLogEventFactory(), null));
    }

    @Test
    void testNullLogEventFactory() {
        Assertions.assertThrows(NullPointerException.class, () -> new LoggerImpl("test-name", null, dummySystem()));
    }

    @Test
    void testSpecWithNullName() {
        // given + then
        Assertions.assertThrows(
                NullPointerException.class, () -> new LoggerImpl(null, new SimpleLogEventFactory(), null));
    }

    @Test
    void testSpecWithSimpleLogger() {
        // given
        LoggerImpl logger = new LoggerImpl("test-name", new SimpleLogEventFactory(), dummySystem());

        // then
        LoggerApiSpecAssertions.assertSpecForLogger(logger);
    }

    @Test
    void testSpecWithFileLogHandler() {
        // given
        final Configuration configuration = ConfigurationBuilder.create()
                .withConverter(new ConfigLevelConverter())
                .withConverter(new MarkerStateConverter())
                .withValue("logging.level", "trace")
                .withValue("logging.handler.file.type", "file")
                .withValue("logging.handler.file.enabled", "true")
                .withValue("logging.handler.file.level", "trace")
                .withValue("logging.handler.file.file", "benchmark.log")
                .build();
        final LoggingSystem loggingSystem = new LoggingSystem(configuration);
        loggingSystem.installHandlers();
        final Logger logger = loggingSystem.getLogger("test-name");

        // then
        LoggerApiSpecAssertions.assertSpecForLogger(logger);
    }

    @Test
    void testSpecWithDifferentLoggers() {
        LoggerApiSpecAssertions.assertSpecForLogger(
                new LoggerImpl("test-name", new SimpleLogEventFactory(), dummySystem()));
        LoggerApiSpecAssertions.assertSpecForLogger(new LoggerImpl("null", new SimpleLogEventFactory(), dummySystem()));
    }

    private static LogEventConsumer dummySystem() {
        return new LogEventConsumer() {
            @Override
            public boolean isEnabled(String name, Level level, Marker marker) {
                return true;
            }
            /**
             * Accepts a log event but performs no action.
             *
             * @param event the log event to be accepted
             */
            @Override
            public void accept(LogEvent event) {
                // Empty implementation; does not perform any action
            }
        };
    }
}
