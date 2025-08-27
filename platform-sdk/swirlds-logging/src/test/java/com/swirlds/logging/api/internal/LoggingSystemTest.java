// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.api.internal;

import static com.swirlds.base.test.fixtures.assertions.AssertionUtils.assertThrowsNPE;
import static com.swirlds.logging.test.fixtures.util.LoggingTestUtils.EXPECTED_STATEMENTS;
import static com.swirlds.logging.test.fixtures.util.LoggingTestUtils.countLinesInStatements;
import static com.swirlds.logging.test.fixtures.util.LoggingTestUtils.getLines;
import static com.swirlds.logging.test.fixtures.util.LoggingTestUtils.linesToStatements;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.base.context.Context;
import com.swirlds.base.test.fixtures.context.WithContext;
import com.swirlds.base.test.fixtures.io.SystemErrProvider;
import com.swirlds.base.test.fixtures.io.WithSystemError;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.logging.LoggerApiSpecAssertions;
import com.swirlds.logging.api.Level;
import com.swirlds.logging.api.Logger;
import com.swirlds.logging.api.Marker;
import com.swirlds.logging.api.extensions.event.LogEvent;
import com.swirlds.logging.api.extensions.event.LogMessage;
import com.swirlds.logging.api.extensions.handler.LogHandler;
import com.swirlds.logging.api.internal.configuration.ConfigLevelConverter;
import com.swirlds.logging.api.internal.emergency.EmergencyLoggerImpl;
import com.swirlds.logging.api.internal.event.DefaultLogEvent;
import com.swirlds.logging.api.internal.level.ConfigLevel;
import com.swirlds.logging.test.fixtures.InMemoryHandler;
import com.swirlds.logging.test.fixtures.internal.LoggingMirrorImpl;
import com.swirlds.logging.test.fixtures.util.LoggingSystemTestOrchestrator;
import com.swirlds.logging.test.fixtures.util.LoggingTestScenario;
import com.swirlds.logging.test.fixtures.util.LoggingTestUtils;
import jakarta.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@WithContext
@WithSystemError
public class LoggingSystemTest {

    @Inject
    private SystemErrProvider provider;

    @BeforeEach
    void cleanupBefore() {
        // reset Emergency logger to remove messages from previous tests
        EmergencyLoggerImpl.getInstance().publishLoggedEvents();
    }

    @Test
    @DisplayName("Test that a logger name is always created correctly")
    void testLoggerName() {
        // given
        final Configuration configuration = new TestConfigBuilder().getOrCreateConfig();
        final LoggingSystem loggingSystem = new LoggingSystem(configuration);

        // when
        final LoggerImpl testNameLogger = loggingSystem.getLogger("test-name");
        final LoggerImpl nullNameLogger = loggingSystem.getLogger(null);
        final LoggerImpl blankNameLogger = loggingSystem.getLogger("");
        final LoggerImpl ToTrimNameLogger = loggingSystem.getLogger("  test-name  ");
        final LoggerImpl ToTrimBlankNameLogger = loggingSystem.getLogger("    ");

        // then
        assertEquals("test-name", testNameLogger.getName());
        assertEquals("", nullNameLogger.getName());
        assertEquals("", blankNameLogger.getName());
        assertEquals("test-name", ToTrimNameLogger.getName());
        assertEquals("", ToTrimBlankNameLogger.getName());
    }

    @Test
    @DisplayName("Test that creating loggers with same name ends in same logger instance")
    void testSameLoggerByName() {
        // given
        final Configuration configuration = new TestConfigBuilder().getOrCreateConfig();
        final LoggingSystem loggingSystem = new LoggingSystem(configuration);

        // when
        final LoggerImpl testNameLogger = loggingSystem.getLogger("test-name");
        final LoggerImpl nullNameLogger = loggingSystem.getLogger(null);
        final LoggerImpl blankNameLogger = loggingSystem.getLogger("");
        final LoggerImpl ToTrimNameLogger = loggingSystem.getLogger("  test-name  ");
        final LoggerImpl ToTrimBlankNameLogger = loggingSystem.getLogger("    ");
        final LoggerImpl testNameLogger2 = loggingSystem.getLogger("test-name");

        // then
        Assertions.assertSame(testNameLogger, ToTrimNameLogger);
        Assertions.assertSame(nullNameLogger, blankNameLogger);
        Assertions.assertSame(nullNameLogger, ToTrimBlankNameLogger);
        Assertions.assertSame(testNameLogger, testNameLogger2);
    }

    @Test
    @DisplayName("Test that INFO is default level for a non configured logging system")
    void testDefaultLevel() {
        // given
        LoggingTestScenario.builder()
                .name("Test that INFO is default level for a non configured logging system")
                .withConfiguration(new TestConfigBuilder())
                // then
                .assertThatLevelIsNotAllowed("", Level.TRACE)
                .assertThatLevelIsNotAllowed("", Level.DEBUG)
                .assertThatLevelIsAllowed("", Level.INFO)
                .assertThatLevelIsAllowed("", Level.WARN)
                .assertThatLevelIsAllowed("", Level.ERROR)
                .assertThatLevelIsNotAllowed("", Level.OFF)
                .assertThatLevelIsNotAllowed(null, Level.TRACE)
                .assertThatLevelIsNotAllowed(null, Level.DEBUG)
                .assertThatLevelIsAllowed(null, Level.INFO)
                .assertThatLevelIsAllowed(null, Level.WARN)
                .assertThatLevelIsAllowed(null, Level.ERROR)
                .assertThatLevelIsNotAllowed(null, Level.OFF)
                .assertThatLevelIsNotAllowed("test.Class", Level.TRACE)
                .assertThatLevelIsNotAllowed("test.Class", Level.DEBUG)
                .assertThatLevelIsAllowed("test.Class", Level.INFO)
                .assertThatLevelIsAllowed("test.Class", Level.WARN)
                .assertThatLevelIsAllowed("test.Class", Level.ERROR)
                .assertThatLevelIsNotAllowed("test.Class", Level.OFF)
                .build()
                .verifyAssertionRules();
    }

    @Test
    @DisplayName("Test that logging system can handle null params for isEnabled")
    void testNullLevel() {
        // given
        LoggingTestScenario.builder()
                .name("Test that logging system can handle null params for isEnabled")
                .withConfiguration(new TestConfigBuilder())
                // then
                .assertThatLevelIsAllowed("", null)
                .assertThatLevelIsAllowed(" ", null)
                .assertThatLevelIsAllowed(null, null)
                .assertThatLevelIsAllowed("test.Class", null)
                .build()
                .verifyAssertionRules();
    }

    @Test
    @DisplayName("Test that isEnabled logs errors to emergency logger")
    void testErrorsForEnabled() {
        // given
        LoggingTestScenario.builder()
                .name("Test that isEnabled logs errors to emergency logger")
                .withConfiguration(new TestConfigBuilder())
                // then
                .assertThatLevelIsNotAllowed("test.Class", Level.TRACE) // no logged error
                .assertThatLevelIsAllowed("test.Class", null) // 1 logged error
                .assertThatLevelIsNotAllowed(null, Level.TRACE) // 1 logged error
                .assertThatLevelIsAllowed(null, null) // 2 logged errors
                .build()
                .verifyAssertionRules();

        final List<LogEvent> loggedErrorEvents = LoggingTestUtils.getEmergencyLoggerEvents(Level.ERROR);

        assertEquals(4, loggedErrorEvents.size(), "There should be 6 ERROR events");
    }

    @Test
    @DisplayName("Test that accept logs errors to emergency logger")
    void testErrorsForAccept() {
        // given
        final Configuration configuration = new TestConfigBuilder().getOrCreateConfig();
        final LoggingSystem loggingSystem = new LoggingSystem(configuration);

        // when
        loggingSystem.accept(null); // 1 logged error

        final List<LogEvent> loggedErrorEvents = LoggingTestUtils.getEmergencyLoggerEvents(Level.ERROR);

        assertEquals(1, loggedErrorEvents.size(), "There should be 1 ERROR event");
    }

    @Test
    @DisplayName("Test that log level can be configured")
    void testCustomLevel() {
        // given
        LoggingTestScenario.builder()
                .name("Test that log level can be configured")
                .withConfiguration(new TestConfigBuilder()
                        .withValue("logging.level", "ERROR")
                        .withValue("logging.level.test.Class", "TRACE")
                        .withConverter(ConfigLevel.class, new ConfigLevelConverter()))
                // then
                .assertThatLevelIsNotAllowed("", Level.TRACE)
                .assertThatLevelIsNotAllowed("", Level.DEBUG)
                .assertThatLevelIsNotAllowed("", Level.INFO)
                .assertThatLevelIsNotAllowed("", Level.WARN)
                .assertThatLevelIsAllowed("", Level.ERROR)
                .assertThatLevelIsNotAllowed("", Level.OFF)
                .assertThatLevelIsNotAllowed(null, Level.TRACE)
                .assertThatLevelIsNotAllowed(null, Level.INFO)
                .assertThatLevelIsNotAllowed(null, Level.DEBUG)
                .assertThatLevelIsNotAllowed(null, Level.WARN)
                .assertThatLevelIsAllowed(null, Level.ERROR)
                .assertThatLevelIsNotAllowed(null, Level.OFF)
                .assertThatLevelIsAllowed("test.Class", Level.TRACE)
                .assertThatLevelIsAllowed("test.Class", Level.DEBUG)
                .assertThatLevelIsAllowed("test.Class", Level.INFO)
                .assertThatLevelIsAllowed("test.Class", Level.WARN)
                .assertThatLevelIsAllowed("test.Class", Level.ERROR)
                .assertThatLevelIsNotAllowed("test.Class", Level.OFF)
                .build()
                .verifyAssertionRules();
    }

    @Test
    @DisplayName("Test that log can be disabled")
    void testOFFLevel() {
        // given
        LoggingTestScenario.builder()
                .name("Test that log can be disabled")
                .withConfiguration(new TestConfigBuilder()
                        .withValue("logging.level", "OFF")
                        .withConverter(ConfigLevel.class, new ConfigLevelConverter()))
                // then
                .assertThatLevelIsNotAllowed("", Level.TRACE)
                .assertThatLevelIsNotAllowed("", Level.DEBUG)
                .assertThatLevelIsNotAllowed("", Level.INFO)
                .assertThatLevelIsNotAllowed("", Level.WARN)
                .assertThatLevelIsNotAllowed("", Level.ERROR)
                .assertThatLevelIsNotAllowed("", Level.OFF)
                .assertThatLevelIsNotAllowed(null, Level.TRACE)
                .assertThatLevelIsNotAllowed(null, Level.INFO)
                .assertThatLevelIsNotAllowed(null, Level.DEBUG)
                .assertThatLevelIsNotAllowed(null, Level.WARN)
                .assertThatLevelIsNotAllowed(null, Level.ERROR)
                .assertThatLevelIsNotAllowed(null, Level.OFF)
                .assertThatLevelIsNotAllowed("test.Class", Level.TRACE)
                .assertThatLevelIsNotAllowed("test.Class", Level.DEBUG)
                .assertThatLevelIsNotAllowed("test.Class", Level.INFO)
                .assertThatLevelIsNotAllowed("test.Class", Level.WARN)
                .assertThatLevelIsNotAllowed("test.Class", Level.ERROR)
                .assertThatLevelIsNotAllowed("test.Class", Level.OFF)
                .build()
                .verifyAssertionRules();
    }

    @Test
    @DisplayName("Test that addHandler logs errors to emergency logger")
    void testNullHandler() {
        // given
        final Configuration configuration = new TestConfigBuilder().getOrCreateConfig();
        final LoggingSystem loggingSystem = new LoggingSystem(configuration);

        // when
        loggingSystem.addHandler(null);

        // then
        final List<String> loggedErrorEvents = LoggingTestUtils.getEmergencyLoggerEvents(Level.ERROR).stream()
                .map(LogEvent::message)
                .map(LogMessage::getMessage)
                .toList();
        assertEquals(1, loggedErrorEvents.size());
        final String expectedError = "Null parameter: handler";
        assertTrue(loggedErrorEvents.contains(expectedError));
        assertTrue(this.provider.getLines().toList().stream().anyMatch(s -> s.contains(expectedError)));
    }

    @Test
    @DisplayName("Test that getLogger logs errors to emergency logger")
    void testNullLogger() {
        // given
        LoggingTestScenario.builder()
                .name("Test that getLogger logs errors to emergency logger")
                .withConfiguration(new TestConfigBuilder())
                // then
                .assertThatLevelIsNotAllowed(Level.TRACE)
                .assertThatLevelIsNotAllowed(Level.DEBUG)
                .assertThatLevelIsAllowed(Level.INFO)
                .assertThatLevelIsAllowed(Level.WARN)
                .assertThatLevelIsAllowed(Level.ERROR)
                .assertThatLevelIsNotAllowed(Level.OFF)
                .withGetLogger(ls -> ls.getLogger(null))
                .assertWitLogger(logger -> {
                    Assertions.assertNotNull(logger);
                    assertEquals("", logger.getName());
                })
                .build()
                .verifyAssertionRules();

        final List<LogEvent> loggedErrorEvents = LoggingTestUtils.getEmergencyLoggerEvents(Level.ERROR);
        assertEquals(1, loggedErrorEvents.size());
    }

    @Test
    @DisplayName("Test that all logging is forwarded to emergency logger if no handler is defined")
    void testEmergencyLoggerIsUsedIfNoAppender() {
        // given
        final Configuration configuration = new TestConfigBuilder().getOrCreateConfig();
        final LoggingSystem loggingSystem = new LoggingSystem(configuration);
        final LoggerImpl logger = loggingSystem.getLogger("");
        EmergencyLoggerImpl.getInstance().publishLoggedEvents(); // reset Emergency logger to remove the init logging

        // when
        logger.trace("trace-message"); // should not be logged since root logger is defined as INFO level
        logger.debug("debug-message"); // should not be logged since root logger is defined as INFO level
        logger.info("info-message");
        logger.warn("warn-message");
        logger.error("error-message");

        // then
        final List<LogEvent> loggedEvents = EmergencyLoggerImpl.getInstance().publishLoggedEvents();
        assertEquals(3, loggedEvents.size());
        assertEquals("info-message", loggedEvents.get(0).message().getMessage());
        assertEquals(Level.INFO, loggedEvents.get(0).level());
        assertEquals("warn-message", loggedEvents.get(1).message().getMessage());
        assertEquals(Level.WARN, loggedEvents.get(1).level());
        assertEquals("error-message", loggedEvents.get(2).message().getMessage());
        assertEquals(Level.ERROR, loggedEvents.get(2).level());
    }

    @Test
    @DisplayName("Test that all logging for info+ is forwarded to emergency logger if no handler is defined")
    void testEmergencyLoggerIsUsedForConfiguredLevelIfNoAppender() {
        // given
        final Configuration configuration = new TestConfigBuilder().getOrCreateConfig();
        final LoggingSystem loggingSystem = new LoggingSystem(configuration);
        final LoggerImpl logger = loggingSystem.getLogger("test.Class");
        EmergencyLoggerImpl.getInstance().publishLoggedEvents(); // reset Emergency logger to remove the init logging

        // when
        logger.trace("trace-message"); // should not be logged
        logger.debug("debug-message"); // should not be logged
        logger.info("info-message");
        logger.warn("warn-message");
        logger.error("error-message");

        // then
        final List<LogEvent> loggedEvents = EmergencyLoggerImpl.getInstance().publishLoggedEvents();
        assertEquals(3, loggedEvents.size());
        assertEquals("info-message", loggedEvents.get(0).message().getMessage());
        assertEquals(Level.INFO, loggedEvents.get(0).level());
        assertEquals("warn-message", loggedEvents.get(1).message().getMessage());
        assertEquals(Level.WARN, loggedEvents.get(1).level());
        assertEquals("error-message", loggedEvents.get(2).message().getMessage());
        assertEquals(Level.ERROR, loggedEvents.get(2).level());
    }

    @Test
    void testLoggingLevelOff() {
        // given
        final Configuration configuration =
                new TestConfigBuilder().withValue("logging.level", "OFF").getOrCreateConfig();
        final LoggingSystem loggingSystem = LoggingTestUtils.loggingSystemWithHandlers(configuration, new LogHandler() {
            @Override
            public String getName() {
                return "ExceptionThrowingHandler";
            }

            @Override
            public boolean isActive() {
                return true;
            }

            @Override
            public boolean isEnabled(final String name, final Level level, final Marker marker) {
                return true;
            }

            @Override
            public void handle(LogEvent event) {
                Assertions.fail("Should not invoke handler");
            }
        });
        final LoggerImpl logger = loggingSystem.getLogger("test.Class");

        // when
        logger.trace("trace-message"); // should not be logged
        logger.debug("debug-message"); // should not be logged
        logger.info("info-message"); // should not be logged
        logger.warn("warn-message"); // should not be logged
        logger.error("error-message"); // should not be logged
    }

    @Test
    void testHandlerLoggingLevelOff() {
        // given
        final Configuration configuration = new TestConfigBuilder()
                .withValue("logging.level", "TRACE")
                .withValue("logging.handler.HANDLER1.enabled", "true")
                .withValue("logging.handler.HANDLER1.type", "inMemory")
                .withValue("logging.handler.HANDLER1.level.com.bar.ZOO", "OFF")
                .getOrCreateConfig();
        final InMemoryHandler inMemoryHandler = new InMemoryHandler("HANDLER1", configuration);
        final LoggingSystem loggingSystem = LoggingTestUtils.loggingSystemWithHandlers(configuration, inMemoryHandler);
        final LoggerImpl logger = loggingSystem.getLogger("com.bar.ZOO");

        // when
        logger.trace("trace-message"); // should not be logged
        logger.debug("debug-message"); // should not be logged
        logger.info("info-message"); // should not be logged
        logger.warn("warn-message"); // should not be logged
        logger.error("error-message"); // should not be logged

        final List<LogEvent> loggedEvents = inMemoryHandler.getEvents();
        assertEquals(0, loggedEvents.size());
    }

    @Test
    @DisplayName(
            "Test that checks if simple log calls are forwarded correctly will all information to the configured handler")
    void testSimpleLoggingHandling() {
        // given
        final Configuration configuration = new TestConfigBuilder().getOrCreateConfig();
        final InMemoryHandler handler = new InMemoryHandler(configuration);
        final LoggingSystem loggingSystem = LoggingTestUtils.loggingSystemWithHandlers(configuration, handler);
        final LoggerImpl logger = loggingSystem.getLogger("test-logger");
        final long startTime = System.currentTimeMillis() - 1;

        // when
        logger.trace("trace-message"); // Should not be forwarded since INFO is configured as root level
        logger.debug("debug-message"); // Should not be forwarded since INFO is configured as root level
        logger.info("info-message");
        logger.warn("warn-message");
        logger.error("error-message");

        // then
        final List<LogEvent> loggedEvents = handler.getEvents();
        assertEquals(3, loggedEvents.size());

        final LogEvent event1 = loggedEvents.get(0);
        assertEquals("info-message", event1.message().getMessage());
        assertEquals(Level.INFO, event1.level());
        assertEquals(Map.of(), event1.context());
        assertEquals("test-logger", event1.loggerName());
        Assertions.assertNull(event1.marker());
        assertEquals(Thread.currentThread().getName(), event1.threadName());
        Assertions.assertNull(event1.throwable());
        Assertions.assertTrue(event1.timestamp() > startTime);
        Assertions.assertTrue(event1.timestamp() <= System.currentTimeMillis());

        final LogEvent event2 = loggedEvents.get(1);
        assertEquals("warn-message", event2.message().getMessage());
        assertEquals(Level.WARN, event2.level());
        assertEquals(Map.of(), event2.context());
        assertEquals("test-logger", event2.loggerName());
        Assertions.assertNull(event2.marker());
        assertEquals(Thread.currentThread().getName(), event2.threadName());
        Assertions.assertNull(event2.throwable());
        Assertions.assertTrue(event2.timestamp() > startTime);
        Assertions.assertTrue(event2.timestamp() <= System.currentTimeMillis());

        final LogEvent event3 = loggedEvents.get(2);
        assertEquals("error-message", event3.message().getMessage());
        assertEquals(Level.ERROR, event3.level());
        assertEquals(Map.of(), event3.context());
        assertEquals("test-logger", event3.loggerName());
        Assertions.assertNull(event3.marker());
        assertEquals(Thread.currentThread().getName(), event3.threadName());
        Assertions.assertNull(event3.throwable());
        Assertions.assertTrue(event3.timestamp() > startTime);
        Assertions.assertTrue(event3.timestamp() <= System.currentTimeMillis());

        Assertions.assertTrue(event1.timestamp() <= event2.timestamp());
        Assertions.assertTrue(event2.timestamp() <= event3.timestamp());
    }

    @Test
    @DisplayName("Test that accept passes events to the configured handler")
    void testAcceptHandling() {
        // given
        final Configuration configuration = new TestConfigBuilder().getOrCreateConfig();
        final LoggingSystem loggingSystem = new LoggingSystem(configuration);
        final InMemoryHandler handler = new InMemoryHandler(configuration);
        loggingSystem.addHandler(handler);
        final LocalDateTime startTime = LocalDateTime.now();
        LogEvent event1 = loggingSystem.getLogEventFactory().createLogEvent(Level.INFO, "test-logger", "info-message");
        LogEvent event2 = loggingSystem
                .getLogEventFactory()
                .createLogEvent(
                        Level.TRACE,
                        "test-logger",
                        "trace-message"); // should not be forwarded since INFO is configured as root level
        LogEvent event3 =
                loggingSystem.getLogEventFactory().createLogEvent(Level.ERROR, "test-logger", "error-message");
        LogEvent event4 = loggingSystem.getLogEventFactory().createLogEvent(Level.INFO, "test-logger", "info-message");

        // when
        loggingSystem.accept(event1);
        loggingSystem.accept(event2);
        loggingSystem.accept(event3);
        loggingSystem.accept(event4);

        // then
        final List<LogEvent> loggedEvents = handler.getEvents();
        assertEquals(3, loggedEvents.size());
        assertEquals(event1, loggedEvents.get(0));
        assertEquals(event3, loggedEvents.get(1));
        assertEquals(event4, loggedEvents.get(2));
    }

    @Test
    @DisplayName(
            "Test that checks if complex log calls are forwarded correctly with all information to the configured handler")
    void testComplexLoggingHandling() {
        // given
        final Configuration configuration = new TestConfigBuilder().getOrCreateConfig();
        final InMemoryHandler handler = new InMemoryHandler(configuration);
        final LoggingSystem loggingSystem = LoggingTestUtils.loggingSystemWithHandlers(configuration, handler);
        final LoggerImpl logger = loggingSystem.getLogger("test-logger");
        final long startTime = System.currentTimeMillis() - 1;

        // when
        Context.getGlobalContext().add("global", "global-value");
        logger.withMarker("TRACE_MARKER")
                .withContext("level", "trace")
                .withContext("context", "unit-test.Class")
                .trace(
                        "trace-message {}",
                        new RuntimeException("trace-error"),
                        "ARG"); // Should not be forwarded since INFO is configured as root level

        try (final AutoCloseable closeable =
                Context.getThreadLocalContext().add("thread-local", "thread-local-value")) {
            logger.withMarker("INFO_MARKER")
                    .withContext("level", "info")
                    .withContext("context", "unit-test.Class")
                    .info("info-message {}", new RuntimeException("info-error"), "ARG");
        } catch (final Exception e) {
            Assertions.fail();
        }

        Context.getGlobalContext().remove("global");

        logger.withMarker("INFO_MARKER")
                .withContext("level", "info")
                .withContext("context", "unit-test.Class")
                .info("info-message2 {}", new RuntimeException("info-error2"), "ARG2");

        // then
        final List<LogEvent> loggedEvents = handler.getEvents();
        assertEquals(2, loggedEvents.size());

        final LogEvent event1 = loggedEvents.get(0);
        assertEquals("info-message ARG", event1.message().getMessage());
        assertEquals(Level.INFO, event1.level());
        assertEquals(
                Map.of(
                        "context",
                        "unit-test.Class",
                        "global",
                        "global-value",
                        "thread-local",
                        "thread-local-value",
                        "level",
                        "info"),
                event1.context());
        assertEquals("test-logger", event1.loggerName());
        assertEquals(new Marker("INFO_MARKER"), event1.marker());
        assertEquals(Thread.currentThread().getName(), event1.threadName());
        Assertions.assertNotNull(event1.throwable());
        assertEquals("info-error", event1.throwable().getMessage());
        assertEquals(RuntimeException.class, event1.throwable().getClass());
        Assertions.assertTrue(event1.timestamp() > startTime);
        Assertions.assertTrue(event1.timestamp() <= System.currentTimeMillis());

        final LogEvent event2 = loggedEvents.get(1);
        assertEquals("info-message2 ARG2", event2.message().getMessage());
        assertEquals(Level.INFO, event2.level());
        assertEquals(Map.of("context", "unit-test.Class", "level", "info"), event2.context());
        assertEquals("test-logger", event2.loggerName());
        assertEquals(new Marker("INFO_MARKER"), event2.marker());
        assertEquals(Thread.currentThread().getName(), event2.threadName());
        Assertions.assertNotNull(event2.throwable());
        assertEquals("info-error2", event2.throwable().getMessage());
        assertEquals(RuntimeException.class, event2.throwable().getClass());
        Assertions.assertTrue(event1.timestamp() > startTime);
        Assertions.assertTrue(event1.timestamp() <= System.currentTimeMillis());

        Assertions.assertTrue(event1.timestamp() <= event2.timestamp());
    }

    @Test
    @DisplayName("Test that accept passes complex log calls correctly with all informations to the configured handler")
    void testAcceptComplexHandling() {
        // given
        final Configuration configuration = new TestConfigBuilder().getOrCreateConfig();
        final InMemoryHandler handler = new InMemoryHandler(configuration);
        final LoggingSystem loggingSystem = LoggingTestUtils.loggingSystemWithHandlers(configuration, handler);

        LogEvent event1 = loggingSystem
                .getLogEventFactory()
                .createLogEvent(
                        Level.INFO,
                        "test-logger",
                        Thread.currentThread().getName(),
                        System.currentTimeMillis(),
                        "message",
                        new RuntimeException("error"),
                        new Marker("INFO_MARKER"),
                        Map.of("context", "unit-test.Class", "level", "info"));
        Context.getGlobalContext().add("new-global", "new-global-value");
        LogEvent event2 = loggingSystem
                .getLogEventFactory()
                .createLogEvent(
                        Level.TRACE,
                        "test-logger",
                        "trace-message"); // should not be forwarded since INFO is configured as root level
        LogEvent event3 =
                loggingSystem.getLogEventFactory().createLogEvent(Level.ERROR, "test-logger", "error-message");
        LogEvent event4 = loggingSystem
                .getLogEventFactory()
                .createLogEvent(
                        Level.INFO,
                        "test-logger",
                        Thread.currentThread().getName(),
                        System.currentTimeMillis(),
                        "message",
                        new RuntimeException("error"),
                        new Marker("INFO_MARKER"),
                        Map.of("context", "unit-test.Class"));

        // when
        loggingSystem.accept(event1);
        loggingSystem.accept(event2);
        loggingSystem.accept(event3);
        loggingSystem.accept(event4);

        // then
        final List<LogEvent> loggedEvents = handler.getEvents();
        assertEquals(3, loggedEvents.size());
        assertEquals(event1, loggedEvents.get(0));
        assertEquals(
                new DefaultLogEvent(
                        event3.level(),
                        event3.loggerName(),
                        event3.threadName(),
                        event3.timestamp(),
                        event3.message(),
                        event3.throwable(),
                        event3.marker(),
                        Map.of("new-global", "new-global-value")),
                loggedEvents.get(1));
        assertEquals(
                loggingSystem
                        .getLogEventFactory()
                        .createLogEvent(
                                event4.level(),
                                event4.loggerName(),
                                event4.threadName(),
                                event4.timestamp(),
                                event4.message(),
                                event4.throwable(),
                                event4.marker(),
                                Map.of("context", "unit-test.Class", "new-global", "new-global-value")),
                loggedEvents.get(2));
    }

    @Test
    @DisplayName("Test that any exception in a handler will not be thrown but logged instead")
    void testExceptionInHandler() {
        // given
        final Configuration configuration = new TestConfigBuilder().getOrCreateConfig();
        final LoggingSystem loggingSystem = new LoggingSystem(configuration);
        loggingSystem.addHandler(new LogHandler() {
            @Override
            public String getName() {
                return "ExceptionThrowingHandler";
            }

            @Override
            public boolean isActive() {
                return true;
            }

            @Override
            public boolean isEnabled(final String name, final Level level, final Marker marker) {
                return true;
            }

            @Override
            public void handle(LogEvent event) {
                throw new RuntimeException("Exception in handler");
            }
        });

        // when
        loggingSystem.accept(loggingSystem.getLogEventFactory().createLogEvent(Level.INFO, "logger", "message"));

        final List<LogEvent> loggedErrorEvents = LoggingTestUtils.getEmergencyLoggerEvents(Level.ERROR);

        assertEquals(1, loggedErrorEvents.size(), "There should be 1 ERROR event");
    }

    @Test
    @DisplayName("Test that empty configuration throws no exception")
    void testHandler() {
        // given
        final Configuration configuration = new TestConfigBuilder().getOrCreateConfig();
        final LoggingSystem loggingSystem = new LoggingSystem(configuration);

        // when
        loggingSystem.installHandlers();

        // then
        assertThat(loggingSystem.getHandlers()).hasSize(0);
    }

    @Test
    @DisplayName("Test that unknown handler type throws no exception")
    void testUnknownTypeHandler() {
        // given
        final Configuration configuration = new TestConfigBuilder()
                .withValue("logging.handler.CRYPTO_FILE.type", "space")
                .getOrCreateConfig();
        final LoggingSystem loggingSystem = new LoggingSystem(configuration);

        // when
        loggingSystem.installHandlers();

        // then
        assertThat(loggingSystem.getHandlers()).hasSize(0);
    }

    @Test
    @DisplayName("Test that installing handler with known type but not enabled throws no exception")
    void testAddHandlerWithoutEnabled() {
        // given
        final Configuration configuration = new TestConfigBuilder()
                .withValue("logging.handler.CRYPTO_FILE.enabled", "false")
                .withValue("logging.handler.CRYPTO_FILE.type", "console")
                .getOrCreateConfig();
        final LoggingSystem loggingSystem = new LoggingSystem(configuration);

        // when
        loggingSystem.installHandlers();

        // then
        assertThat(loggingSystem.getHandlers()).hasSize(0);
    }

    @Test
    @DisplayName("Test that installing known handler type and enabled throws no exception")
    void testAddHandler() {
        // given
        final Configuration configuration = new TestConfigBuilder()
                .withValue("logging.handler.CRYPTO_FILE.enabled", "true")
                .withValue("logging.handler.CRYPTO_FILE.type", "console")
                .getOrCreateConfig();
        final LoggingSystem loggingSystem = new LoggingSystem(configuration);

        // when
        loggingSystem.installHandlers();

        // then
        assertThat(loggingSystem.getHandlers()).hasSize(1);
    }

    @Test
    @DisplayName("Test that installing multiple known handler type and enabled throws no exception")
    void testAddMultipleHandler() {
        // given
        final Configuration configuration = new TestConfigBuilder()
                .withValue("logging.handler.CRYPTO_FILE.enabled", "true")
                .withValue("logging.handler.CRYPTO_FILE.type", "console")
                .withValue("logging.handler.TRANSACTION_FILE.enabled", "true")
                .withValue("logging.handler.TRANSACTION_FILE.type", "console")
                .getOrCreateConfig();
        final LoggingSystem loggingSystem = new LoggingSystem(configuration);

        // when
        loggingSystem.installHandlers();

        // then
        assertThat(loggingSystem.getHandlers()).hasSize(2);
    }

    @Test
    void testSpecWithLoggingSystemWithoutHandler() {
        // given
        LoggingTestScenario.builder()
                .name("testSpecWithLoggingSystemWithoutHandler")
                .withConfiguration(new TestConfigBuilder())
                .withGetLogger(ls -> ls.getLogger("test-name"))
                // then
                .assertWitLogger(LoggerApiSpecAssertions::assertSpecForLogger)
                .build()
                .verifyAssertionRules();
    }

    @Test
    void testSpecWithLoggingSystemWithHandler() {
        // given
        LoggingTestScenario.builder()
                .name("testSpecWithLoggingSystemWithHandler")
                .withConfiguration(new TestConfigBuilder())
                .withGetLogger(ls -> ls.getLogger("test-name"))
                // then
                .assertWitLogger(LoggerApiSpecAssertions::assertSpecForLogger)
                .build()
                .verifyAssertionRules(c -> {
                    final LoggingSystem loggingSystem = new LoggingSystem(c);
                    loggingSystem.addHandler(new LogHandler() {
                        @Override
                        public boolean isEnabled(final String name, final Level level, final Marker marker) {
                            return true;
                        }

                        @Override
                        public void handle(final LogEvent event) {}
                    });
                    return loggingSystem;
                });
    }

    @Test
    void testSpecWithLoggingSystemWithHandlerAndNullLoggerName() {
        // given
        final Configuration configuration = new TestConfigBuilder().getOrCreateConfig();
        final LoggingSystem loggingSystem = new LoggingSystem(configuration);
        loggingSystem.addHandler(new LogHandler() {
            @Override
            public boolean isEnabled(final String name, final Level level, final Marker marker) {
                return true;
            }

            @Override
            public void handle(final LogEvent event) {}
        });

        // then // when
        final LoggerImpl logger = loggingSystem.getLogger(null);

        final List<String> loggedErrorEvents = LoggingTestUtils.getEmergencyLoggerEvents(Level.ERROR).stream()
                .map(LogEvent::message)
                .map(LogMessage::getMessage)
                .toList();
        assertEquals(1, loggedErrorEvents.size());
        final String expectedError = "Null parameter: name";
        assertTrue(loggedErrorEvents.contains(expectedError));
        assertTrue(this.provider.getLines().toList().stream().anyMatch(s -> s.contains(expectedError)));

        LoggerApiSpecAssertions.assertSpecForLogger(logger);
    }

    @Test
    @DisplayName("Test that installing multiple known handler type and enabled throws no exception")
    void testAddMultipleFileHandler(@TempDir final Path tempDir) throws IOException {
        // given
        final String cryptoFile = tempDir.resolve("crypto.log").toString();
        final String transactionFile = tempDir.resolve("transaction.log").toString();
        // given
        final Configuration configuration = new TestConfigBuilder()
                .withValue("logging.handler.CRYPTO_FILE.enabled", "true")
                .withValue("logging.handler.CRYPTO_FILE.type", "file")
                .withValue("logging.handler.CRYPTO_FILE.file", cryptoFile)
                .withValue("logging.handler.TRANSACTION_FILE.enabled", "true")
                .withValue("logging.handler.TRANSACTION_FILE.type", "file")
                .withValue("logging.handler.TRANSACTION_FILE.file", transactionFile)
                .getOrCreateConfig();

        LoggingSystem loggingSystem = new LoggingSystem(configuration);
        try {

            // when
            loggingSystem.installHandlers();

            // then
            assertThat(loggingSystem.getHandlers()).hasSize(2);
            assertThat(new File(cryptoFile)).exists();
            assertThat(new File(transactionFile)).exists();
        } finally {
            loggingSystem.stopAndFinalize();
            Files.deleteIfExists(Path.of(cryptoFile));
            Files.deleteIfExists(Path.of(transactionFile));
        }
    }

    private static final String LOG_FILE = "logging.log";

    @Test
    void testFileHandlerLogging(@TempDir final Path tempDir) throws IOException {

        // given
        final String logFile = tempDir.resolve(LOG_FILE).toString();
        final String fileHandlerName = "file";
        final Configuration configuration = LoggingTestUtils.prepareConfiguration(logFile, fileHandlerName);
        final LoggingMirrorImpl mirror = new LoggingMirrorImpl();
        final LoggingSystem loggingSystem = LoggingTestUtils.loggingSystemWithHandlers(configuration, mirror);
        // A random log name, so it's easier to combine lines after
        final String loggerName = UUID.randomUUID().toString();
        final Logger logger = loggingSystem.getLogger(loggerName);

        // when
        LoggingTestUtils.loggExtensively(logger);
        loggingSystem.stopAndFinalize();

        try {
            final List<String> statementsInMirror = LoggingTestUtils.mirrorToStatements(mirror);
            final List<String> logLines = getLines(logFile);
            final List<String> statementsInFile = linesToStatements(logLines);

            // then
            org.assertj.core.api.Assertions.assertThat(statementsInFile.size()).isEqualTo(EXPECTED_STATEMENTS);

            // Loglines should be 1 per statement in mirror + 1 for each statement
            final int expectedLineCountInFile = countLinesInStatements(statementsInMirror);
            org.assertj.core.api.Assertions.assertThat((long) logLines.size()).isEqualTo(expectedLineCountInFile);
            org.assertj.core.api.Assertions.assertThat(statementsInFile).isSubsetOf(statementsInMirror);

        } finally {
            loggingSystem.stopAndFinalize();
            Files.deleteIfExists(Path.of(logFile));
        }
    }

    @Test
    void testSimpleConfigUpdate() {
        // given
        final Configuration initialConfiguration = LoggingTestUtils.getConfigBuilder()
                .withValue("logging.level", Level.ERROR)
                .getOrCreateConfig();
        final Configuration updatedConfiguration = LoggingTestUtils.getConfigBuilder()
                .withValue("logging.level", Level.TRACE)
                .getOrCreateConfig();
        final LoggingSystem system = LoggingTestUtils.loggingSystemWithHandlers(initialConfiguration);

        // when
        system.update(updatedConfiguration);

        // then
        assertTrue(system.isEnabled("com.sample.Foo", Level.TRACE, null));
    }

    @Test
    void testConfigUpdateWithNullConfig() {
        // given
        final Configuration initialConfiguration =
                LoggingTestUtils.getConfigBuilder().getOrCreateConfig();
        final LoggingSystem loggingSystem = new LoggingSystem(initialConfiguration);

        // then
        assertThrowsNPE(() -> loggingSystem.update(null));
    }

    @Test
    void testWithConfigUpdateWithEmptyConfig() {
        // given
        final Configuration configuration = LoggingTestUtils.getConfigBuilder()
                .withValue("logging.level", "INFO")
                .withValue("logging.level.com.sample", "DEBUG")
                .withValue("logging.level.com.sample.package", "ERROR")
                .withValue("logging.level.com.sample.package.ClassB", "ERROR")
                .withValue("logging.level.com.sample.package.ClassC", "TRACE")
                .getOrCreateConfig();
        final LoggingSystem loggingSystem = LoggingTestUtils.loggingSystemWithHandlers(configuration);

        // when
        final LoggingTestScenario scenario = LoggingTestScenario.builder()
                .name("reloaded")
                // empty configuration
                .assertThatLevelIsAllowed("", Level.INFO)
                .assertThatLevelIsAllowed("a.long.name.for.a.logger", Level.INFO)
                .assertThatLevelIsAllowed("com.sample", Level.INFO)
                .assertThatLevelIsAllowed("com.sample.Class", Level.INFO)
                .assertThatLevelIsAllowed("com.sample.package", Level.INFO)
                .assertThatLevelIsAllowed("com.sample.package.ClassA", Level.INFO)
                .assertThatLevelIsAllowed("com.sample.package.ClassB", Level.INFO)
                .assertThatLevelIsAllowed("com.sample.package.ClassC", Level.INFO)
                .build();

        // then
        LoggingSystemTestOrchestrator.runScenarios(loggingSystem, scenario);
    }

    @Test
    @DisplayName("Multiple configuration updates test")
    public void testConfigUpdate() {

        final Configuration configuration = LoggingTestUtils.getConfigBuilder().getOrCreateConfig();
        final LoggingSystem loggingSystem = LoggingTestUtils.loggingSystemWithHandlers(configuration);

        // Default scenario: what should happen if no configuration is reloaded
        final LoggingTestScenario defaultScenario = LoggingTestScenario.builder()
                .name("default")
                .assertThatLevelIsAllowed("", Level.ERROR)
                .assertThatLevelIsAllowed("", Level.WARN)
                .assertThatLevelIsAllowed("", Level.INFO)
                .assertThatLevelIsNotAllowed("", Level.DEBUG)
                .build();

        // Scenario 1: Change logging level
        final LoggingTestScenario scenario1 = LoggingTestScenario.builder()
                .name("scenario1")
                .withConfiguration("logging.level", Level.TRACE)
                .assertThatLevelIsAllowed("com.sample.Class", Level.TRACE) // Fix Scenario
                .assertThatLevelIsAllowed("com.Class", Level.INFO)
                .assertThatLevelIsAllowed("Class", Level.TRACE)
                .build();
        // Scenario 2: No Specific Configuration for Package
        final LoggingTestScenario scenario2 = LoggingTestScenario.builder()
                .name("scenario2")
                .withConfiguration("logging.level", Level.WARN)
                .assertThatLevelIsNotAllowed("com.sample.Class", Level.DEBUG)
                .build();

        // Scenario 3: Class-Specific Configuration
        final LoggingTestScenario scenario3 = LoggingTestScenario.builder()
                .name("scenario3")
                .withConfiguration("logging.level.com.sample.package.Class", Level.ERROR)
                .assertThatLevelIsAllowed("com.sample.package.Class", Level.ERROR)
                .build();

        // Scenario 4: Package-Level Configuration
        final LoggingTestScenario scenario4 = LoggingTestScenario.builder()
                .name("scenario4")
                .withConfiguration("logging.level.com.sample.package", Level.TRACE)
                .assertThatLevelIsAllowed("com.sample.package.Class", Level.TRACE)
                .assertThatLevelIsNotAllowed("com.sample.package.Class", Level.OFF)
                .build();

        final String clazz = ".Random";
        final String subpackageSubClazz = ".sub.Random";

        // Scenario 5: Mixed Configuration
        final LoggingTestScenario scenario5 = LoggingTestScenario.builder()
                .name("scenario5")
                .withConfiguration("logging.level", Level.WARN)
                .withConfiguration("logging.level.com.sample", Level.INFO)
                .withConfiguration("logging.level.com.sample.package", Level.ERROR)
                .assertThatLevelIsAllowed("Class", Level.ERROR)
                .assertThatLevelIsAllowed("Class", Level.WARN)
                .assertThatLevelIsNotAllowed("Class", Level.OFF)
                .assertThatLevelIsNotAllowed("Class", Level.INFO)
                .assertThatLevelIsNotAllowed("Class", Level.DEBUG)
                .assertThatLevelIsNotAllowed("Class", Level.TRACE)
                .assertThatLevelIsAllowed("a" + clazz, Level.WARN)
                .assertThatLevelIsNotAllowed("b" + clazz, Level.INFO)
                .assertThatLevelIsNotAllowed("c" + clazz, Level.DEBUG)
                .assertThatLevelIsNotAllowed("d" + clazz, Level.TRACE)
                .assertThatLevelIsNotAllowed("d" + clazz, Level.OFF)
                .assertThatLevelIsAllowed("other" + clazz, Level.ERROR)
                .assertThatLevelIsNotAllowed("other" + clazz, Level.OFF)
                .assertThatLevelIsAllowed("other.a" + clazz, Level.WARN)
                .assertThatLevelIsNotAllowed("other.b" + clazz, Level.INFO)
                .assertThatLevelIsNotAllowed("other.c" + clazz, Level.DEBUG)
                .assertThatLevelIsNotAllowed("other.d" + clazz, Level.TRACE)
                .assertThatLevelIsAllowed("com.sample" + clazz, Level.ERROR)
                .assertThatLevelIsAllowed("com.sample" + clazz, Level.WARN)
                .assertThatLevelIsAllowed("com.sample" + clazz, Level.INFO)
                .assertThatLevelIsNotAllowed("com.sample" + clazz, Level.DEBUG)
                .assertThatLevelIsNotAllowed("com.sample" + clazz, Level.TRACE)
                .assertThatLevelIsAllowed("com.sample.package" + clazz, Level.ERROR)
                .assertThatLevelIsNotAllowed("com.sample.package" + clazz, Level.WARN)
                .assertThatLevelIsNotAllowed("com.sample.package" + clazz, Level.INFO)
                .assertThatLevelIsNotAllowed("com.sample.package" + clazz, Level.DEBUG)
                .assertThatLevelIsNotAllowed("com.sample.package" + clazz, Level.TRACE)
                .assertThatLevelIsAllowed("com.sample.sub", Level.WARN)
                .assertThatLevelIsAllowed("com.sample" + subpackageSubClazz, Level.INFO)
                .build();

        final LoggingTestScenario scenario6 = LoggingTestScenario.builder()
                .name("scenario6")
                .withConfiguration("logging.level", Level.OFF)
                .assertThatLevelIsNotAllowed("Class", Level.ERROR)
                .assertThatLevelIsNotAllowed("Class", Level.WARN)
                .assertThatLevelIsNotAllowed("Class", Level.OFF)
                .assertThatLevelIsNotAllowed("Class", Level.INFO)
                .assertThatLevelIsNotAllowed("Class", Level.DEBUG)
                .assertThatLevelIsNotAllowed("Class", Level.TRACE)
                .assertThatLevelIsNotAllowed("a" + clazz, Level.WARN)
                .assertThatLevelIsNotAllowed("b" + clazz, Level.INFO)
                .assertThatLevelIsNotAllowed("c" + clazz, Level.DEBUG)
                .assertThatLevelIsNotAllowed("d" + clazz, Level.TRACE)
                .assertThatLevelIsNotAllowed("d" + clazz, Level.OFF)
                .assertThatLevelIsNotAllowed("other" + clazz, Level.ERROR)
                .assertThatLevelIsNotAllowed("other" + clazz, Level.OFF)
                .assertThatLevelIsNotAllowed("other.a" + clazz, Level.WARN)
                .assertThatLevelIsNotAllowed("other.b" + clazz, Level.INFO)
                .assertThatLevelIsNotAllowed("other.c" + clazz, Level.DEBUG)
                .assertThatLevelIsNotAllowed("other.d" + clazz, Level.TRACE)
                .assertThatLevelIsNotAllowed("com.sample" + clazz, Level.ERROR)
                .assertThatLevelIsNotAllowed("com.sample" + clazz, Level.WARN)
                .assertThatLevelIsNotAllowed("com.sample" + clazz, Level.INFO)
                .assertThatLevelIsNotAllowed("com.sample" + clazz, Level.DEBUG)
                .assertThatLevelIsNotAllowed("com.sample" + clazz, Level.TRACE)
                .assertThatLevelIsNotAllowed("com.sample.package" + clazz, Level.ERROR)
                .assertThatLevelIsNotAllowed("com.sample.package" + clazz, Level.WARN)
                .assertThatLevelIsNotAllowed("com.sample.package" + clazz, Level.INFO)
                .assertThatLevelIsNotAllowed("com.sample.package" + clazz, Level.DEBUG)
                .assertThatLevelIsNotAllowed("com.sample.package" + clazz, Level.TRACE)
                .assertThatLevelIsNotAllowed("com.sample.sub", Level.WARN)
                .assertThatLevelIsNotAllowed("com.sample" + subpackageSubClazz, Level.INFO)
                .build();

        // Ask the Orchestrator to run all desired scenarios up to 2 Seconds
        // Scenarios define a configuration and a set of assertions for that config.
        LoggingSystemTestOrchestrator.runScenarios(
                loggingSystem,
                Duration.of(2, ChronoUnit.SECONDS),
                defaultScenario,
                scenario1,
                scenario2,
                scenario3,
                scenario4,
                scenario5,
                scenario6);
    }
}
