// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.utility.throttle;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static org.hiero.consensus.utility.test.fixtures.RandomUtils.getRandomPrintSeed;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.swirlds.base.test.fixtures.time.FakeTime;
import com.swirlds.common.utility.CompareTo;
import java.time.Duration;
import java.time.Instant;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Marker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("RateLimitedLogger Tests")
class RateLimitedLoggerTests {

    @DisplayName("debug() Test")
    @Test
    void debugTest() {
        final Random random = getRandomPrintSeed();

        final FakeTime time = new FakeTime();
        final Duration period = Duration.ofSeconds(random.nextInt(1, 5));

        final Logger logger = mock(Logger.class);
        final AtomicInteger count = new AtomicInteger();

        doAnswer(invocation -> {
                    final Level level = invocation.getArgument(0);
                    final Marker marker = invocation.getArgument(1);
                    final String message = invocation.getArgument(2);
                    final Integer countArg = invocation.getArgument(3);
                    final Exception exceptionArg = invocation.getArgument(4);

                    assertEquals(Level.DEBUG, level);
                    assertEquals(EXCEPTION.getMarker(), marker);
                    assertTrue(message.startsWith("Exception occurred {}"));
                    assertEquals(count.get(), countArg);
                    assertTrue(exceptionArg instanceof IllegalStateException);

                    count.incrementAndGet();
                    return null;
                })
                .when(logger)
                .log(any(Level.class), any(Marker.class), any(String.class), any(Object[].class));

        final RateLimitedLogger rateLimitedLogger = new RateLimitedLogger(logger, time, period);

        Instant previousTime = time.now();
        time.tick(period);

        while (count.get() < 100) {
            final Instant currentTime = time.now();
            final Duration elapsed = Duration.between(previousTime, currentTime);

            final int initialCount = count.get();

            rateLimitedLogger.debug(
                    EXCEPTION.getMarker(), "Exception occurred {}", count.get(), new IllegalStateException("derp"));
            if (CompareTo.isGreaterThanOrEqualTo(elapsed, period)) {
                assertEquals(initialCount + 1, count.get());
                previousTime = currentTime;
            }
            time.tick(Duration.ofMillis(random.nextInt(1000)));
        }
    }

    @DisplayName("trace() Test")
    @Test
    void traceTest() {
        final Random random = getRandomPrintSeed();

        final FakeTime time = new FakeTime();
        final Duration period = Duration.ofSeconds(random.nextInt(1, 5));

        final Logger logger = mock(Logger.class);
        final AtomicInteger count = new AtomicInteger();

        doAnswer(invocation -> {
                    final Level level = invocation.getArgument(0);
                    final Marker marker = invocation.getArgument(1);
                    final String message = invocation.getArgument(2);
                    final Integer countArg = invocation.getArgument(3);
                    final Exception exceptionArg = invocation.getArgument(4);

                    assertEquals(Level.TRACE, level);
                    assertEquals(EXCEPTION.getMarker(), marker);
                    assertTrue(message.startsWith("Exception occurred {}"));
                    assertEquals(count.get(), countArg);
                    assertTrue(exceptionArg instanceof IllegalStateException);

                    count.incrementAndGet();
                    return null;
                })
                .when(logger)
                .log(any(Level.class), any(Marker.class), any(String.class), any(Object[].class));

        final RateLimitedLogger rateLimitedLogger = new RateLimitedLogger(logger, time, period);

        Instant previousTime = time.now();
        time.tick(period);

        while (count.get() < 100) {
            final Instant currentTime = time.now();
            final Duration elapsed = Duration.between(previousTime, currentTime);

            final int initialCount = count.get();

            rateLimitedLogger.trace(
                    EXCEPTION.getMarker(), "Exception occurred {}", count.get(), new IllegalStateException("derp"));
            if (CompareTo.isGreaterThanOrEqualTo(elapsed, period)) {
                assertEquals(initialCount + 1, count.get());
                previousTime = currentTime;
            }
            time.tick(Duration.ofMillis(random.nextInt(1000)));
        }
    }

    @DisplayName("info() Test")
    @Test
    void infoTest() {
        final Random random = getRandomPrintSeed();

        final FakeTime time = new FakeTime();
        final Duration period = Duration.ofSeconds(random.nextInt(1, 5));

        final Logger logger = mock(Logger.class);
        final AtomicInteger count = new AtomicInteger();

        doAnswer(invocation -> {
                    final Level level = invocation.getArgument(0);
                    final Marker marker = invocation.getArgument(1);
                    final String message = invocation.getArgument(2);
                    final Integer countArg = invocation.getArgument(3);
                    final Exception exceptionArg = invocation.getArgument(4);

                    assertEquals(Level.INFO, level);
                    assertEquals(EXCEPTION.getMarker(), marker);
                    assertTrue(message.startsWith("Exception occurred {}"));
                    assertEquals(count.get(), countArg);
                    assertTrue(exceptionArg instanceof IllegalStateException);

                    count.incrementAndGet();
                    return null;
                })
                .when(logger)
                .log(any(Level.class), any(Marker.class), any(String.class), any(Object[].class));

        final RateLimitedLogger rateLimitedLogger = new RateLimitedLogger(logger, time, period);

        Instant previousTime = time.now();
        time.tick(period);

        while (count.get() < 100) {
            final Instant currentTime = time.now();
            final Duration elapsed = Duration.between(previousTime, currentTime);

            final int initialCount = count.get();

            rateLimitedLogger.info(
                    EXCEPTION.getMarker(), "Exception occurred {}", count.get(), new IllegalStateException("derp"));
            if (CompareTo.isGreaterThanOrEqualTo(elapsed, period)) {
                assertEquals(initialCount + 1, count.get());
                previousTime = currentTime;
            }
            time.tick(Duration.ofMillis(random.nextInt(1000)));
        }
    }

    @DisplayName("warn() Test")
    @Test
    void warnTest() {
        final Random random = getRandomPrintSeed();

        final FakeTime time = new FakeTime();
        final Duration period = Duration.ofSeconds(random.nextInt(1, 5));

        final Logger logger = mock(Logger.class);
        final AtomicInteger count = new AtomicInteger();

        doAnswer(invocation -> {
                    final Level level = invocation.getArgument(0);
                    final Marker marker = invocation.getArgument(1);
                    final String message = invocation.getArgument(2);
                    final Integer countArg = invocation.getArgument(3);
                    final Exception exceptionArg = invocation.getArgument(4);

                    assertEquals(Level.WARN, level);
                    assertEquals(EXCEPTION.getMarker(), marker);
                    assertTrue(message.startsWith("Exception occurred {}"));
                    assertEquals(count.get(), countArg);
                    assertTrue(exceptionArg instanceof IllegalStateException);

                    count.incrementAndGet();
                    return null;
                })
                .when(logger)
                .log(any(Level.class), any(Marker.class), any(String.class), any(Object[].class));

        final RateLimitedLogger rateLimitedLogger = new RateLimitedLogger(logger, time, period);

        Instant previousTime = time.now();
        time.tick(period);

        while (count.get() < 100) {
            final Instant currentTime = time.now();
            final Duration elapsed = Duration.between(previousTime, currentTime);

            final int initialCount = count.get();

            rateLimitedLogger.warn(
                    EXCEPTION.getMarker(), "Exception occurred {}", count.get(), new IllegalStateException("derp"));
            if (CompareTo.isGreaterThanOrEqualTo(elapsed, period)) {
                assertEquals(initialCount + 1, count.get());
                previousTime = currentTime;
            }
            time.tick(Duration.ofMillis(random.nextInt(1000)));
        }
    }

    @DisplayName("error() Test")
    @Test
    void errorTest() {
        final Random random = getRandomPrintSeed();

        final FakeTime time = new FakeTime();
        final Duration period = Duration.ofSeconds(random.nextInt(1, 5));

        final Logger logger = mock(Logger.class);
        final AtomicInteger count = new AtomicInteger();

        doAnswer(invocation -> {
                    final Level level = invocation.getArgument(0);
                    final Marker marker = invocation.getArgument(1);
                    final String message = invocation.getArgument(2);
                    final Integer countArg = invocation.getArgument(3);
                    final Exception exceptionArg = invocation.getArgument(4);

                    assertEquals(Level.ERROR, level);
                    assertEquals(EXCEPTION.getMarker(), marker);
                    assertTrue(message.startsWith("Exception occurred {}"));
                    assertEquals(count.get(), countArg);
                    assertTrue(exceptionArg instanceof IllegalStateException);

                    count.incrementAndGet();
                    return null;
                })
                .when(logger)
                .log(any(Level.class), any(Marker.class), any(String.class), any(Object[].class));

        final RateLimitedLogger rateLimitedLogger = new RateLimitedLogger(logger, time, period);

        Instant previousTime = time.now();
        time.tick(period);

        while (count.get() < 100) {
            final Instant currentTime = time.now();
            final Duration elapsed = Duration.between(previousTime, currentTime);

            final int initialCount = count.get();

            rateLimitedLogger.error(
                    EXCEPTION.getMarker(), "Exception occurred {}", count.get(), new IllegalStateException("derp"));
            if (CompareTo.isGreaterThanOrEqualTo(elapsed, period)) {
                assertEquals(initialCount + 1, count.get());
                previousTime = currentTime;
            }
            time.tick(Duration.ofMillis(random.nextInt(1000)));
        }
    }

    @DisplayName("fatal() Test")
    @Test
    void fatalTest() {
        final Random random = getRandomPrintSeed();

        final FakeTime time = new FakeTime();
        final Duration period = Duration.ofSeconds(random.nextInt(1, 5));

        final Logger logger = mock(Logger.class);
        final AtomicInteger count = new AtomicInteger();

        doAnswer(invocation -> {
                    final Level level = invocation.getArgument(0);
                    final Marker marker = invocation.getArgument(1);
                    final String message = invocation.getArgument(2);
                    final Integer countArg = invocation.getArgument(3);
                    final Exception exceptionArg = invocation.getArgument(4);

                    assertEquals(Level.FATAL, level);
                    assertEquals(EXCEPTION.getMarker(), marker);
                    assertTrue(message.startsWith("Exception occurred {}"));
                    assertEquals(count.get(), countArg);
                    assertTrue(exceptionArg instanceof IllegalStateException);

                    count.incrementAndGet();
                    return null;
                })
                .when(logger)
                .log(any(Level.class), any(Marker.class), any(String.class), any(Object[].class));

        final RateLimitedLogger rateLimitedLogger = new RateLimitedLogger(logger, time, period);

        Instant previousTime = time.now();
        time.tick(period);

        while (count.get() < 100) {
            final Instant currentTime = time.now();
            final Duration elapsed = Duration.between(previousTime, currentTime);

            final int initialCount = count.get();

            rateLimitedLogger.fatal(
                    EXCEPTION.getMarker(), "Exception occurred {}", count.get(), new IllegalStateException("derp"));
            if (CompareTo.isGreaterThanOrEqualTo(elapsed, period)) {
                assertEquals(initialCount + 1, count.get());
                previousTime = currentTime;
            }
            time.tick(Duration.ofMillis(random.nextInt(1000)));
        }
    }
}
