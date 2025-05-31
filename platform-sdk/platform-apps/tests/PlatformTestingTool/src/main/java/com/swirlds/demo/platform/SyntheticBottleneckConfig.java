// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.platform;

import static com.swirlds.logging.legacy.LogMarker.DEMO_INFO;

import com.swirlds.logging.legacy.payload.SyntheticBottleneckFinishPayload;
import com.swirlds.logging.legacy.payload.SyntheticBottleneckStartPayload;
import java.util.HashSet;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * This class is responsible creating a synthetic bottleneck for a node
 * until it falls behind and is forced to reconnect.
 */
public class SyntheticBottleneckConfig {

    private static final Logger logger = LogManager.getLogger(SyntheticBottleneckConfig.class);

    /**
     * This class is concerned with causing a single node to reconnect. All other nodes
     * are unaffected by this class and its methods.
     */
    private final Set<Long> targetNodeIds = new HashSet<>();

    /**
     * The number of times the targeted node has reconnected.
     */
    private int reconnectCount;

    /**
     * The maximum number of times that the node will be forced to reconnect.
     */
    private int maximumReconnectCount = 0;

    /**
     * The amount of time that the targeted node has slept since it last reconnected.
     */
    private long millisecondsSlept;

    /**
     * The amount of time that the thread is made to sleep during throttling.
     */
    private long millisecondSleepPeriod = 1_000;

    /**
     * Once this amount of time has been slept in total, stop throttling the node. A node will not reconnect until
     * it starts syncing again and notices that it is behind. If throttling is never stopped then this takes an
     * extreme amount of time.
     */
    private long totalMillisecondsToSleep = 1_000 * 120;

    /**
     * The amount of time to wait before throttling is initialized.
     */
    private long throttleInitMillisecondDelay = 1_000 * 120;

    /**
     * The timestamp at the beginning of the throttle period. A new throttle period begins when the first transaction
     * is handled after boot or when a node finishes a reconnect.
     */
    private long throttleInitTimestamp;

    /**
     * For each node there should be an instance of this class. (In the case where multiple nodes run on a single
     * process there will be only one, but this doesn't break anything).
     */
    private static SyntheticBottleneckConfig activeConfig;

    /**
     * Default config if none is set
     */
    private static final SyntheticBottleneckConfig defaultConfig = new SyntheticBottleneckConfig();

    /**
     * Get the current active instance of this object. This object is not part of the state and survives a
     * reconnect operation intact.
     */
    public static synchronized SyntheticBottleneckConfig getActiveConfig() {
        return activeConfig != null ? activeConfig : defaultConfig;
    }

    /**
     * Set the current active instance of this object. Should be set when the configuration file is read.
     */
    public static synchronized void setActiveConfig(SyntheticBottleneckConfig activeConfig) {
        SyntheticBottleneckConfig.activeConfig = activeConfig;
    }

    public SyntheticBottleneckConfig() {
        throttleInitTimestamp = System.currentTimeMillis();
    }

    /**
     * This method should be called every time a node finishes a reconnect.
     */
    public synchronized void registerReconnect(long nodeId) {
        if (!targetNodeIds.contains(nodeId)) {
            return;
        }
        reconnectCount++;
        millisecondsSlept = 0;
        logger.info(
                DEMO_INFO.getMarker(),
                "{}",
                (new SyntheticBottleneckFinishPayload("finishing synthetic node throttle")));
        throttleInitTimestamp = System.currentTimeMillis();
    }

    /**
     * If a reconnect on this node is desired at this time, this method may block for an extended period of time.
     *
     * @param nodeId
     * 		the id of the current node
     */
    public void throttleIfNeeded(long nodeId) {
        synchronized (this) {
            if (!targetNodeIds.contains(nodeId)) {
                return;
            }
            if (millisecondsSlept > totalMillisecondsToSleep) {
                return;
            }
            if (reconnectCount >= maximumReconnectCount) {
                return;
            }
            if (System.currentTimeMillis() - throttleInitTimestamp < throttleInitMillisecondDelay) {
                return;
            }
            if (millisecondsSlept == 0) {
                // This is the first time we are throttling since the node started or reconnected
                logger.info(
                        DEMO_INFO.getMarker(),
                        "{}",
                        (new SyntheticBottleneckStartPayload("engaging synthetic node throttle")));
            }
            millisecondsSlept += millisecondSleepPeriod;
        }
        try {
            logger.info(DEMO_INFO.getMarker(), "Will sleep for {}ms on throttle", millisecondSleepPeriod);
            Thread.sleep(millisecondSleepPeriod);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Get the IDs of the node that are being throttled until they reconnect.
     */
    public Set<Long> getTargetNodeIds() {
        return targetNodeIds;
    }

    /**
     * Set the IDs of the node that are being throttled until they reconnect.
     */
    public void setTargetNodeIds(final Set<Long> targetNodeIds) {
        this.targetNodeIds.clear();
        this.targetNodeIds.addAll(targetNodeIds);
    }

    /**
     * Get the maximum number of times the target node will be made to reconnect.
     */
    public int getMaximumReconnectCount() {
        return maximumReconnectCount;
    }

    /**
     * Set the maximum number of times the target node will be made to reconnect.
     */
    public void setMaximumReconnectCount(int maximumReconnectCount) {
        this.maximumReconnectCount = maximumReconnectCount;
    }

    /**
     * Get the length of time that each transaction is delayed while a reconnect is being provoked.
     */
    public long getMillisecondSleepPeriod() {
        return millisecondSleepPeriod;
    }

    /**
     * Set the length of time that each transaction is delayed while a reconnect is being provoked.
     */
    public void setMillisecondSleepPeriod(long millisecondSleepPeriod) {
        this.millisecondSleepPeriod = millisecondSleepPeriod;
    }

    /**
     * Get the total amount of time that will be slept (over multiple transactions) before the trottler will back off.
     */
    public long getTotalMillisecondsToSleep() {
        return totalMillisecondsToSleep;
    }

    /**
     * Set the total amount of time that will be slept (over multiple transactions) before the trottler will back off.
     */
    public void setTotalMillisecondsToSleep(long totalMillisecondsToSleep) {
        this.totalMillisecondsToSleep = totalMillisecondsToSleep;
    }

    /**
     * The reconnect throttler does not begin throttling right away. After each boot and reconnect, this throttler will
     * wait this period of time before beginning. Get that value with this method.
     */
    public long getThrottleInitMillisecondDelay() {
        return throttleInitMillisecondDelay;
    }

    /**
     * The reconnect throttler does not begin throttling right away. After each boot and reconnect, this throttler will
     * wait this period of time before beginning. Set that value with this method.
     */
    public void setThrottleInitMillisecondDelay(long throttleInitMillisecondDelay) {
        this.throttleInitMillisecondDelay = throttleInitMillisecondDelay;
    }

    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("target: ")
                .append(targetNodeIds)
                .append(", total reconnects: ")
                .append(maximumReconnectCount);

        return sb.toString();
    }
}
