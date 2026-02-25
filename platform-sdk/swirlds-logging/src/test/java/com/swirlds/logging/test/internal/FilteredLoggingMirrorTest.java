// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.test.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.swirlds.base.context.internal.ThreadLocalContext;
import com.swirlds.base.test.fixtures.concurrent.TestExecutor;
import com.swirlds.base.test.fixtures.concurrent.WithTestExecutor;
import com.swirlds.logging.api.Level;
import com.swirlds.logging.api.Logger;
import com.swirlds.logging.api.Loggers;
import com.swirlds.logging.test.fixtures.LoggingMirror;
import com.swirlds.logging.test.fixtures.WithLoggingMirror;
import jakarta.inject.Inject;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@WithTestExecutor
@WithLoggingMirror
class FilteredLoggingMirrorTest {

    private final Logger logger = Loggers.getLogger(FilteredLoggingMirrorTest.class);
    private static final String KEY = UUID.randomUUID().toString();
    private static final String VALUE = "VALUE";
    private static final String KEY2 = UUID.randomUUID().toString();
    private static final String TEST_THREAD = UUID.randomUUID().toString();
    private static final String VALUE2 = "OTHER VALUE";

    @Inject
    private LoggingMirror loggingMirror;

    @Inject
    private TestExecutor executor;

    @Test
    @DisplayName("filtering By Context Should Return Log Statements Done With right Context")
    void testFilterByContext() {
        executor.executeAndWait(this::doLog);
        assertThat(loggingMirror.filterByContext(KEY, VALUE).getEventCount()).isEqualTo(7);
        assertThat(loggingMirror
                        .filter(logEvent -> logEvent.context().containsKey(KEY))
                        .getEventCount())
                .isEqualTo(7);
    }

    @Test
    @DisplayName("filtering By Context Nesting Conditions Should Return Log Statements Done With right Context")
    void testFilterWithNestingByContext() {
        executor.executeAndWait(this::doLog);
        assertThat(loggingMirror
                        .filter(logEvent -> logEvent.context().containsKey(KEY))
                        .filter(logEvent -> logEvent.context().containsKey(KEY2))
                        .getEventCount())
                .isEqualTo(1);
        assertThat(loggingMirror
                        .filterByContext(KEY, VALUE)
                        .filterByContext(KEY2, VALUE2)
                        .getEventCount())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("filtering By Logger Should Return Log Statements Done With right Logger")
    void testFilterByLogger() {
        executor.executeAndWait(this::doLog);
        assertThat(loggingMirror.filterByLogger(FilteredLoggingMirrorTest.class).getEventCount())
                .isEqualTo(19);
        assertThat(loggingMirror.filterByLogger("AnotherName").getEventCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("filtering By Thread Should Return Log Statements Done With right Thread")
    void testFilterByThread() {
        executor.executeAndWait(() -> {
            Thread.currentThread().setName(TEST_THREAD);
            logger.trace("This is a {} level message", Level.TRACE);
            logger.debug("This is a {} level message", Level.DEBUG);
        });
        assertThat(loggingMirror.filterByThread(TEST_THREAD).getEventCount()).isEqualTo(2);

        assertThat(loggingMirror
                        .filterByThread(TEST_THREAD)
                        .filterByLevel(Level.TRACE)
                        .getEventCount())
                .isEqualTo(1);

        assertThat(loggingMirror
                        .filterByThread(TEST_THREAD)
                        .filterByLevel(Level.DEBUG)
                        .getEventCount())
                .isEqualTo(1);

        assertThat(loggingMirror
                        .filterByThread(TEST_THREAD)
                        .filterByLevel(Level.INFO)
                        .getEventCount())
                .isEqualTo(0);

        assertThat(loggingMirror.filterByCurrentThread().getEventCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("filtering By Level Should Return Log Statements Done With right Level")
    void testFilterByLevel() {
        executor.executeAndWait(this::doLog);
        assertThat(loggingMirror.filterByLevel(Level.OFF).getEventCount()).isEqualTo(1);

        assertThat(loggingMirror.filterByLevel(Level.TRACE).getEventCount()).isEqualTo(3);

        assertThat(loggingMirror.filterByLevel(Level.DEBUG).getEventCount()).isEqualTo(3);

        assertThat(loggingMirror.filterByLevel(Level.ERROR).getEventCount()).isEqualTo(3);

        assertThat(loggingMirror.filterByLevel(Level.WARN).getEventCount()).isEqualTo(3);

        assertThat(loggingMirror.filterByLevel(Level.INFO).getEventCount()).isEqualTo(6);

        assertThat(loggingMirror
                        .filterByLevel(Level.INFO)
                        .filterByContext(KEY, VALUE)
                        .getEventCount())
                .isEqualTo(3);

        assertThat(loggingMirror
                        .filterByLevel(Level.INFO)
                        .filterByContext(KEY, VALUE)
                        .filterByContext(KEY2, VALUE2)
                        .getEventCount())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("filtering Above Level Should Return Log Statements Done With right Level")
    void testFilterAboveLevel() {
        executor.executeAndWait(() -> {
            Thread.currentThread().setName(TEST_THREAD);
            logger.error("This is a {} level message", Level.ERROR);
            logger.warn("This is a {} level message", Level.WARN);
            logger.info("This is a {} level message", Level.INFO);
            logger.debug("This is a {} level message", Level.DEBUG);
            logger.trace("This is a {} level message", Level.TRACE);
        });
        assertThat(loggingMirror.filterAboveLevel(Level.OFF).getEventCount()).isEqualTo(0);
        assertThat(loggingMirror.filterAboveLevel(Level.ERROR).getEventCount()).isEqualTo(1);
        assertThat(loggingMirror.filterAboveLevel(Level.WARN).getEventCount()).isEqualTo(2);
        assertThat(loggingMirror.filterAboveLevel(Level.INFO).getEventCount()).isEqualTo(3);
        assertThat(loggingMirror.filterAboveLevel(Level.DEBUG).getEventCount()).isEqualTo(4);
        assertThat(loggingMirror.filterAboveLevel(Level.TRACE).getEventCount()).isEqualTo(5);
        assertThat(loggingMirror
                        .filterAboveLevel(Level.TRACE)
                        .filterByThread("WRONG-NAME")
                        .getEventCount())
                .isEqualTo(0);
        assertThat(loggingMirror
                        .filterAboveLevel(Level.TRACE)
                        .filterByThread(TEST_THREAD)
                        .getEventCount())
                .isEqualTo(5);
    }

    private void doLog() {

        logger.trace("This is a {} level message", Level.TRACE);
        logger.debug("This is a {} level message", Level.DEBUG);
        logger.info("This is a {} level message", Level.INFO);
        logger.error("This is a {} level message", Level.ERROR);
        logger.warn("This is a {} level message", Level.WARN);
        logger.log(Level.OFF, "This is an off level");

        final Logger testMarker = logger.withMarker("TEST_MARKER");
        testMarker.info("This is a {} level message", Level.INFO);

        try (final AutoCloseable closable = ThreadLocalContext.getInstance().add(KEY, VALUE)) {
            logger.trace("This is a {} level message with context", Level.TRACE);
            logger.debug("This is a {} level message with context", Level.DEBUG);
            logger.info("This is a {} level message with context", Level.INFO);
            logger.error("This is a {} level message with context", Level.ERROR);
            logger.warn("This is a {} level message with context", Level.WARN);
            testMarker.info("This is a {} level message with context and marker", Level.WARN);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        try (final AutoCloseable closable = ThreadLocalContext.getInstance().add(KEY2, VALUE2)) {
            logger.trace("This is a {} level message with 2nd context", Level.TRACE);
            logger.debug("This is a {} level message with 2nd context", Level.DEBUG);
            logger.info("This is a {} level message with 2nd context", Level.INFO);
            logger.error("This is a {} level message with 2nd context", Level.ERROR);
            logger.warn("This is a {} level message with 2nd context", Level.WARN);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        try (final AutoCloseable closable = ThreadLocalContext.getInstance().add(KEY, VALUE);
                final AutoCloseable closable2 = ThreadLocalContext.getInstance().add(KEY2, VALUE2)) {
            testMarker.info("This is a {} level message with context, 2nd context and marker", Level.INFO);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
