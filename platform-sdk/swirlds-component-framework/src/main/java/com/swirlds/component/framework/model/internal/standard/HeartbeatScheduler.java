// SPDX-License-Identifier: Apache-2.0
package com.swirlds.component.framework.model.internal.standard;

import com.swirlds.base.time.Time;
import com.swirlds.component.framework.model.StandardWiringModel;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Timer;

/**
 * A scheduler that produces heartbeats at a specified rate.
 */
public class HeartbeatScheduler extends AbstractHeartbeatScheduler {
    private final Timer timer = new Timer();

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
            timer.scheduleAtFixedRate(task, 0, task.getPeriod().toMillis());
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
        timer.cancel();
    }
}
