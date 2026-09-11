// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.status.monitor.uptime;

import static org.hiero.consensus.status.monitor.uptime.UptimeData.NO_ROUND;

import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;

/**
 * Uptime data about a particular node.
 */
class NodeUptimeData {

    private long lastEventRound = NO_ROUND;
    private Instant lastEventTime;
    private long lastJudgeRound = NO_ROUND;
    private Instant lastJudgeTime;

    /**
     * Get the round number of the most recently observed consensus event.
     */
    public long getLastEventRound() {
        return lastEventRound;
    }

    /**
     * Set the round number of the most recently observed consensus event.
     *
     * @param lastEventRound the round number of the most recently observed consensus event
     */
    public void setLastEventRound(final long lastEventRound) {
        this.lastEventRound = lastEventRound;
    }

    /**
     * Get the time of the most recently observed consensus event.
     */
    @Nullable
    public Instant getLastEventTime() {
        return lastEventTime;
    }

    /**
     * Set the time of the most recently observed consensus event.
     *
     * @param lastEventTime the time of the most recently observed consensus event
     */
    public void setLastEventTime(@Nullable final Instant lastEventTime) {
        this.lastEventTime = lastEventTime;
    }

    /**
     * Get the round number of the most recently observed judge event.
     */
    public long getLastJudgeRound() {
        return lastJudgeRound;
    }

    /**
     * Set the round number of the most recently observed judge event.
     *
     * @param lastJudgeRound the round number of the most recently observed judge event
     */
    public void setLastJudgeRound(final long lastJudgeRound) {
        this.lastJudgeRound = lastJudgeRound;
    }

    /**
     * Get the time of the most recently observed judge event.
     */
    @Nullable
    public Instant getLastJudgeTime() {
        return lastJudgeTime;
    }

    /**
     * Set the time of the most recently observed judge event.
     *
     * @param lastJudgeTime the time of the most recently observed judge event
     */
    public void setLastJudgeTime(@Nullable final Instant lastJudgeTime) {
        this.lastJudgeTime = lastJudgeTime;
    }
}
