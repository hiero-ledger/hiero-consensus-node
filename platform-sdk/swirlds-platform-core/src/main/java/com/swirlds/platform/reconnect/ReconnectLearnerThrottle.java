// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.reconnect;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.logging.legacy.LogMarker.STARTUP;

import com.swirlds.base.time.Time;
import com.swirlds.common.merkle.synchronization.config.ReconnectConfig;
import com.swirlds.logging.legacy.payload.ReconnectFailurePayload;
import com.swirlds.logging.legacy.payload.UnableToReconnectPayload;
import com.swirlds.platform.Utilities;
import com.swirlds.platform.network.Connection;
import com.swirlds.platform.network.NetworkUtils;
import com.swirlds.platform.system.SystemExitCode;
import com.swirlds.platform.system.SystemExitUtils;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.node.NodeId;

/**
 * Throttles reconnect learner attempts
 * <p>
 * NOTE: This class is not thread safe
 */
public class ReconnectLearnerThrottle {
    private static final Logger logger = LogManager.getLogger(ReconnectLearnerThrottle.class);
    private final NodeId selfId;
    private final ReconnectConfig config;

    private final Time time;
    private final Instant startupTime;

    /**
     * The number of times reconnect has failed since the last successful reconnect.
     */
    private int failedReconnectsInARow;

    /**
     * Constructor.
     *
     * @param time   provides wall clock time
     * @param selfId the id of this node
     * @param config the reconnect configuration
     */
    public ReconnectLearnerThrottle(
            @NonNull final Time time, @NonNull final NodeId selfId, @NonNull final ReconnectConfig config) {

        this.selfId = Objects.requireNonNull(selfId);
        this.config = Objects.requireNonNull(config);
        this.time = Objects.requireNonNull(time);
        this.failedReconnectsInARow = 0;
        startupTime = time.now();
    }

    /**
     * Get the time since this node was started. Exact instant is captured at some time when the JVM is starting.
     *
     * @return the time since when this node started
     */
    @NonNull
    private Duration getTimeSinceStartup() {
        return Duration.between(startupTime, time.now());
    }

    /**
     * Notifies the throttle that a successful reconnect occurred
     */
    public void successfulReconnect() {
        failedReconnectsInARow = 0;
    }

    /**
     * Notifies the throttle that a reconnect failed
     */
    public void handleFailedReconnect(final Connection conn, final Exception e) {
        if (Utilities.isOrCausedBySocketException(e)) {
            logger.error(EXCEPTION.getMarker(), () -> new ReconnectFailurePayload(
                            "Got socket exception while receiving a signed state! " + NetworkUtils.formatException(e),
                            ReconnectFailurePayload.CauseOfFailure.SOCKET)
                    .toString());
        } else {
            logger.error(
                    EXCEPTION.getMarker(),
                    () -> new ReconnectFailurePayload(
                                    "Error while receiving a signed state!",
                                    ReconnectFailurePayload.CauseOfFailure.ERROR)
                            .toString(),
                    e);
            if (conn != null) {
                conn.disconnect();
            }
        }

        failedReconnectsInARow++;
        killNodeIfThresholdMet();
    }

    private void killNodeIfThresholdMet() {
        if (failedReconnectsInARow >= config.maximumReconnectFailuresBeforeShutdown()) {
            logger.error(EXCEPTION.getMarker(), "Too many reconnect failures in a row, killing node");
            SystemExitUtils.exitSystem(SystemExitCode.RECONNECT_FAILURE);
        }
    }

    /**
     * Check if a reconnect is currently allowed. If not then kill the node.
     */
    public void exitIfReconnectIsDisabled() {
        if (!config.active()) {
            logger.warn(STARTUP.getMarker(), () -> new UnableToReconnectPayload(
                            "Node has fallen behind, reconnect is disabled, will die", selfId.id())
                    .toString());
            SystemExitUtils.exitSystem(SystemExitCode.BEHIND_RECONNECT_DISABLED);
        }

        if (config.reconnectWindowSeconds() >= 0
                && config.reconnectWindowSeconds() < getTimeSinceStartup().toSeconds()) {
            logger.warn(STARTUP.getMarker(), () -> new UnableToReconnectPayload(
                            "Node has fallen behind, reconnect is disabled outside of time window, will die",
                            selfId.id())
                    .toString());
            SystemExitUtils.exitSystem(SystemExitCode.BEHIND_RECONNECT_DISABLED);
        }
    }
}
