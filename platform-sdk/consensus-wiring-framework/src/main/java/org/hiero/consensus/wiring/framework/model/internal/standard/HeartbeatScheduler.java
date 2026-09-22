// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.wiring.framework.model.internal.standard;

import com.swirlds.base.time.Time;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.hiero.base.utility.InstantUtils;
import org.hiero.consensus.wiring.framework.model.StandardWiringModel;

/**
 * A scheduler that produces heartbeats at a specified rate.
 */
public class HeartbeatScheduler extends AbstractHeartbeatScheduler {
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    /**
     * Constructor.
     *
     * @param model the wiring model containing this heartbeat scheduler
     * @param time  provides wall clock time
     */
    public HeartbeatScheduler(@NonNull final StandardWiringModel model, @NonNull final Time time) {
        super(model, time);
    }

    /**
     * Start the heartbeats.
     */
    @Override
    public void start() {
        if (started) {
            throw new IllegalStateException("Cannot start the heartbeat more than once");
        }
        started = true;

        for (final HeartbeatTask task : tasks) {
            timer.scheduleAtFixedRate(
                    task, 0, task.getPeriod().toNanos() / InstantUtils.NANOS_IN_MICRO, TimeUnit.MICROSECONDS);
        }
    }

    /**
     * Stop the heartbeats.
     */
    @Override
    public void stop() {
        if (!started) {
            throw new IllegalStateException("Cannot stop the heartbeat before it has started");
        }
        timer.shutdownNow();
    }
}
