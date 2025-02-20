// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.log4j.factory;

import static org.assertj.core.api.Assertions.assertThat;

import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.logging.api.Level;
import com.swirlds.logging.api.internal.LoggingSystem;
import com.swirlds.logging.legacy.LogMarker;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.apache.logging.log4j.ThreadContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class SwirldsLogAppenderTest {
    private static final String LOGGER_NAME = "testLogger";

    private Map<String, String> oldContext;

    static Stream<Arguments> levelsAndSizes() {
        return Stream.of(
                Arguments.of(Level.TRACE, 5),
                Arguments.of(Level.DEBUG, 4),
                Arguments.of(Level.INFO, 3),
                Arguments.of(Level.WARN, 2),
                Arguments.of(Level.ERROR, 1),
                Arguments.of(Level.OFF, 0));
    }

    @BeforeEach
    void setUp() {
        oldContext = ThreadContext.getImmutableContext();
    }

    @ParameterizedTest
    @MethodSource("levelsAndSizes")
    void loggingLevels(final Level level, final int expectedSize, @TempDir final Path tempDir) throws Exception {
        // given
        final Path filePath = tempDir.resolve("%s_test.log".formatted(level));
        final LoggingSystem loggingSystem = createLoggingSystem(createConfig(level, filePath, true));
        final Logger log4jLogger = LogManager.getLogger(LOGGER_NAME);

        // when
        log4jLogger.error("This is an error message");
        log4jLogger.warn("This is a warn message");
        log4jLogger.info("This is an info message");
        log4jLogger.debug("This is a debug message");
        log4jLogger.trace("This is a trace message");
        loggingSystem.stopAndFinalize();

        // then
        assertThat(filePath).exists();
        final List<String> logLines = Files.lines(filePath).toList();

        assertThat(logLines).hasSize(expectedSize);
    }

    @Test
    void loggingSimpleMarker(@TempDir final Path tempDir) throws Exception {
        // given
        final Path filePath = tempDir.resolve("marker_test.log");
        final LoggingSystem loggingSystem = createLoggingSystem(createConfig(Level.DEBUG, filePath, true));
        final Logger log4jLogger = LogManager.getLogger(LOGGER_NAME);

        // when
        log4jLogger.warn(LogMarker.CONFIG.getMarker(), "This is a warn message");
        loggingSystem.stopAndFinalize();

        // then
        final List<String> logLines = Files.lines(filePath).toList();

        assertThat(logLines).hasSize(1);
        assertThat(logLines.getFirst()).contains(LogMarker.CONFIG.name());
    }

    @Test
    void loggingChainedMarkers(@TempDir final Path tempDir) throws Exception {
        // given
        final Path filePath = tempDir.resolve("marker_test.log");
        final LoggingSystem loggingSystem = createLoggingSystem(createConfig(Level.DEBUG, filePath, true));
        final Logger log4jLogger = LogManager.getLogger(LOGGER_NAME);

        // when
        log4jLogger.warn(TestMarkers.CHILD_MARKER, "This is a warn message");
        loggingSystem.stopAndFinalize();

        // then
        final List<String> logLines = Files.lines(filePath).toList();

        assertThat(logLines).hasSize(1);
        assertThat(logLines.getFirst()).contains(TestMarkers.CHILD, TestMarkers.PARENT, TestMarkers.GRANT);
    }

    @Test
    void loggingContext(@TempDir final Path tempDir) throws Exception {
        // given
        final Path filePath = tempDir.resolve("context_test.log");
        final LoggingSystem loggingSystem = createLoggingSystem(createConfig(Level.DEBUG, filePath, true));
        final Logger log4jLogger = LogManager.getLogger(LOGGER_NAME);

        // when
        ThreadContext.put("key1", "value1");
        log4jLogger.warn("This is a warn message");
        loggingSystem.stopAndFinalize();

        // then
        final List<String> logLines = Files.lines(filePath).toList();

        assertThat(logLines).hasSize(1);
        assertThat(logLines.getFirst()).contains("key1=value1");
    }

    @Test
    void nothingGetsLoggedIfProviderIsDisabled(@TempDir final Path tempDir) throws Exception {
        // given
        final Path filePath = tempDir.resolve("disabled_provider_test.log");
        final LoggingSystem loggingSystem = createLoggingSystem(createConfig(Level.DEBUG, filePath, false));
        final Logger log4jLogger = LogManager.getLogger(LOGGER_NAME);

        // when
        log4jLogger.warn("This is a warn message");
        loggingSystem.stopAndFinalize();

        // then
        final List<String> logLines = Files.lines(filePath).toList();
        assertThat(logLines).isEmpty();
    }

    @AfterEach
    void tearDown() throws Exception {
        ThreadContext.clearAll();
        ThreadContext.putAll(oldContext);
    }

    private static LoggingSystem createLoggingSystem(final Configuration configuration) {
        final LoggingSystem loggingSystem = new LoggingSystem(configuration);

        loggingSystem.installHandlers();
        loggingSystem.installProviders();

        return loggingSystem;
    }

    private static Configuration createConfig(final Level level, final Path filePath, boolean handlerEnabled) {
        return new TestConfigBuilder()
                .withValue("logging.level", level)
                .withValue("logging.handler.DEFAULT.enabled", true)
                .withValue("logging.handler.DEFAULT.type", "file")
                .withValue("logging.handler.DEFAULT.file", filePath)
                .withValue("logging.provider.log4j.enabled", handlerEnabled)
                .getOrCreateConfig();
    }

    private static class TestMarkers {
        public static final String GRANT = "GRANT";
        public static final String PARENT = "PARENT";
        public static final String CHILD = "CHILD";

        public static final Marker GRANT_MARKER;
        public static final Marker PARENT_MARKER;
        public static final Marker CHILD_MARKER;

        static {
            GRANT_MARKER = MarkerManager.getMarker(GRANT);
            PARENT_MARKER = MarkerManager.getMarker(PARENT).addParents(GRANT_MARKER);
            CHILD_MARKER = MarkerManager.getMarker(CHILD).addParents(PARENT_MARKER);
        }
    }
}
