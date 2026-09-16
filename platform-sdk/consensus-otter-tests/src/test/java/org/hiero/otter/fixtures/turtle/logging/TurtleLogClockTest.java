// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.turtle.logging;

import static org.assertj.core.api.Assertions.assertThat;

import com.swirlds.base.test.fixtures.time.FakeTime;
import com.swirlds.base.time.Time;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Duration;
import java.time.Instant;
import org.apache.logging.log4j.ThreadContext;
import org.apache.logging.log4j.core.util.Clock;
import org.apache.logging.log4j.core.util.ClockFactory;
import org.hiero.otter.fixtures.logging.context.NodeLoggingContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests that Log4j2 actually installs {@link TurtleLogClock} when asked via the {@code log4j.Clock} property.
 *
 * <p>Log4j2 instantiates the class named by that property reflectively, from {@code LoaderUtil} in the log4j-api
 * module. If the package holding the class is not exported to that module, the instantiation fails and Log4j2 falls
 * back to {@code SystemClock}, which makes every log timestamp wall-clock time instead of simulated time. The failure
 * is reported only on Log4j2's status logger, so nothing else in the test suite notices.
 */
@DisplayName("TurtleLogClock Test")
class TurtleLogClockTest {

    /** An instant far enough from any plausible wall-clock time that the two cannot be confused. */
    private static final Instant FAKE_NOW = Instant.parse("2000-01-01T00:00:00Z");

    @Nullable
    private String previousClockProperty;

    @AfterEach
    void restoreGlobalState() {
        if (previousClockProperty == null) {
            System.clearProperty(ClockFactory.PROPERTY_NAME);
        } else {
            System.setProperty(ClockFactory.PROPERTY_NAME, previousClockProperty);
        }
        TurtleLogClock.setFakeTime(Time.getCurrent());
        ThreadContext.remove(NodeLoggingContext.NODE_ID_KEY);
    }

    @Test
    @DisplayName("Log4j2 can instantiate the clock named by the log4j.Clock property")
    void log4jCanInstantiateTurtleLogClock() {
        previousClockProperty = System.getProperty(ClockFactory.PROPERTY_NAME);
        System.setProperty(ClockFactory.PROPERTY_NAME, TurtleLogClock.class.getName());

        // ClockFactory creates a new instance on every call, so this reflects the module configuration as it stands
        // rather than whatever Log4j2 resolved when it was first initialized in this JVM.
        final Clock clock = ClockFactory.getClock();

        assertThat(clock).isInstanceOf(TurtleLogClock.class);
    }

    @Test
    @DisplayName("The clock reports simulated time on node threads and wall-clock time elsewhere")
    void clockDistinguishesNodeThreadsFromOtherThreads() {
        TurtleLogClock.setFakeTime(new FakeTime(FAKE_NOW, Duration.ZERO));
        final TurtleLogClock clock = new TurtleLogClock();

        try (final NodeLoggingContext.LoggingContextScope ignored = NodeLoggingContext.install("0")) {
            assertThat(clock.currentTimeMillis()).isEqualTo(FAKE_NOW.toEpochMilli());
        }

        assertThat(clock.currentTimeMillis()).isNotEqualTo(FAKE_NOW.toEpochMilli());
    }
}
