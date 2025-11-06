// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.turtle.logging;

import com.swirlds.base.time.Time;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.apache.logging.log4j.ThreadContext;

/**
 * Custom Log4j2 Clock implementation that provides context-aware time sources.
 *
 * <p>This clock uses different time sources based on the thread context:
 * <ul>
 *   <li>For TurtleNode threads (with nodeId in ThreadContext): Uses FakeTime for simulated timestamps</li>
 *   <li>For other threads (TurtleTestEnvironment, etc.): Uses System time for real timestamps</li>
 * </ul>
 */
public class TurtleLogClock implements org.apache.logging.log4j.core.util.Clock {

    private static volatile Time fakeTime = Time.getCurrent();
    private static final Time systemTime = Time.getCurrent();

    /**
     * Required no-argument constructor for Log4j2 ClockFactory.
     */
    public TurtleLogClock() {
        // Log4j2 will instantiate this
    }

    /**
     * Set the fake Time instance to be used by turtle nodes.
     *
     * @param time the Time instance to use for simulated log timestamps
     */
    public static void setFakeTime(@NonNull final Time time) {
        fakeTime = time;
    }

    @Override
    public long currentTimeMillis() {
        // Check if we're in a TurtleNode thread (has nodeId in ThreadContext)
        final String nodeId = ThreadContext.get("nodeId");

        if (nodeId != null && !nodeId.isEmpty()) {
            // We're in a TurtleNode thread - use fake time for simulation
            return fakeTime.now().toEpochMilli();
        } else {
            // We're in TurtleTestEnvironment or other thread - use system time
            return systemTime.now().toEpochMilli();
        }
    }
}
