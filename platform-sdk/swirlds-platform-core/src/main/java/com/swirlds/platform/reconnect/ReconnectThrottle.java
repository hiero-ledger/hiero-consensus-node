// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.reconnect;

import static com.swirlds.logging.legacy.LogMarker.RECONNECT;

import com.swirlds.base.time.Time;
import com.swirlds.common.merkle.synchronization.config.ReconnectConfig;
import com.swirlds.common.utility.throttle.RateLimitedLogger;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Objects;
import java.util.function.Supplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.node.NodeId;

/**
 * This object is responsible for restricting the frequency of reconnects (in the role of the sender).
 */
public class ReconnectThrottle {

    private static final Logger logger = LogManager.getLogger(ReconnectThrottle.class);

    /**
     * Reconnect settings for this node.
     */
    private final ReconnectConfig config;

    /**
     * A map from node IDs to reconnect times. Nodes not in this map have either never reconnected or have reconnected
     * only in the distant past.
     */
    private final HashMap<NodeId, Instant> lastReconnectTime;

    /**
     * The node that is currently reconnecting, or null of no node is currently reconnecting.
     */
    private NodeId reconnectingNode;

    /** A rate limited logger for rejecting teacher role due to already teaching another node. */
    private final RateLimitedLogger alreadyHelpingLogger;
    /** A rate limited logger for rejecting teacher role due to throttle limit. */
    private final RateLimitedLogger throttledLogger;

    /**
     * A method used to get the current time. Useful to have for debugging.
     */
    private Supplier<Instant> currentTime;

    public ReconnectThrottle(@NonNull final ReconnectConfig config, @NonNull final Time time) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        Objects.requireNonNull(time);
        lastReconnectTime = new HashMap<>();
        reconnectingNode = null;
        currentTime = Instant::now;
        alreadyHelpingLogger = new RateLimitedLogger(logger, time, config.minimumTimeBetweenReconnects());
        throttledLogger = new RateLimitedLogger(logger, time, config.minimumTimeBetweenReconnects());
    }

    /**
     * Prune records of old reconnect attempts that no longer need to be tracked.
     */
    private void forgetOldReconnects(final Instant now) {
        final Iterator<Instant> iterator = lastReconnectTime.values().iterator();

        while (iterator.hasNext()) {
            final Duration elapsed = Duration.between(iterator.next(), now);
            if (config.minimumTimeBetweenReconnects().minus(elapsed).isNegative()) {
                iterator.remove();
            }
        }
    }

    /**
     * Check if it is ok to reconnect (in the role of the sender) with a given node, and if so begin tracking that
     * reconnect.
     *
     * @param nodeId the ID of the node that is behind and needs to reconnect
     * @return true if the reconnect can proceed, false if reconnect is disallowed by policy
     */
    public synchronized boolean initiateReconnect(final NodeId nodeId) {
        if (reconnectingNode != null) {
            alreadyHelpingLogger.info(
                    RECONNECT.getMarker(),
                    "This node is actively helping node {} to reconnect, rejecting "
                            + "concurrent reconnect request from node {}",
                    reconnectingNode,
                    nodeId);
            return false;
        }

        final Instant now = currentTime.get();

        forgetOldReconnects(now);
        if (lastReconnectTime.containsKey(nodeId)) {
            throttledLogger.info(
                    RECONNECT.getMarker(),
                    "Rejecting reconnect request from node {} " + "due to a previous reconnect attempt at {}",
                    nodeId,
                    lastReconnectTime.get(nodeId));
            return false;
        }
        reconnectingNode = nodeId;
        lastReconnectTime.put(nodeId, now);
        return true;
    }

    /**
     * Signal that the ongoing reconnect attempt has finished. Should be called even if the reconnect fails.
     */
    public synchronized void reconnectAttemptFinished() {
        reconnectingNode = null;
    }

    /**
     * Get the number of nodes that have recently reconnected.
     */
    public int getNumberOfRecentReconnects() {
        return lastReconnectTime.size();
    }

    public void setCurrentTime(final Supplier<Instant> currentTime) {
        this.currentTime = currentTime;
    }
}
