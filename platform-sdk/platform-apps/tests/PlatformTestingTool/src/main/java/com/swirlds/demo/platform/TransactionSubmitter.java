// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.platform;

import static com.swirlds.base.units.UnitConstants.SECONDS_TO_MILLISECONDS;
import static com.swirlds.metrics.api.Metrics.PLATFORM_CATEGORY;

import com.swirlds.base.utility.Pair;
import com.swirlds.common.utility.throttle.MultiThrottle;
import com.swirlds.common.utility.throttle.Throttle;
import com.swirlds.demo.platform.actions.QuorumTriggeredAction;
import com.swirlds.demo.platform.fs.stresstest.proto.ControlType;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.platform.system.Platform;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;

public class TransactionSubmitter {

    /**
     * use this for all logging
     */
    private static final Logger logger = LogManager.getLogger(TransactionSubmitter.class);

    private static final Marker LOGM_DEMO_INFO = MarkerManager.getMarker("DEMO_INFO");
    private static final Marker LOGM_SUBMIT_DETAIL = MarkerManager.getMarker("SUBMIT_DETAIL");
    private static final Marker LOGM_EXCEPTION = MarkerManager.getMarker("EXCEPTION");

    private static final int FREEZE_SUBMIT_ATTEMPT_SLEEP = 250;
    private static final int FREEZE_SUBMIT_MAX_ATTEMPTS = 10;

    public static final long USE_DEFAULT_TPS = 0;

    private volatile long customizedTPS = 0;

    private static final double ALLOWED_CATCHUP_DELTA = 1.3;

    /**
     * Force canSubmitMore to halt temporarily or allow it to run normally
     *
     * @return true: canSubmitMore is forced to halt generating transactions
     * 		false: permit canSubmitMore to run it's normal internal logic w.r.t. generating transactions
     */
    public static AtomicBoolean getForcePauseCanSubmitMore() {
        return forcePauseCanSubmitMore;
    }

    public static void setForcePauseCanSubmitMore(final AtomicBoolean forcePauseCanSubmitMore) {
        TransactionSubmitter.forcePauseCanSubmitMore = forcePauseCanSubmitMore;
    }

    private static AtomicBoolean forcePauseCanSubmitMore;

    /**
     * If set to true will prevent future transactions from being submitted by the {@link #trySubmit(Platform, Pair)}
     * method.
     */
    private final AtomicBoolean paused = new AtomicBoolean(false);

    public enum SUBMIT_GOAL {
        BYTES_PER_SECOND_PER_NODE,
        TRANS_PER_SECOND_PER_NODE,
        EVENTS_PER_SECOND_WHOLE_NETWORK,
        ROUNDS_PER_SECOND_WHOLE_NETWORK,
        TRANS_PER_EVENT_WHOLE_NETWORK,
        C2C_LATENCY;

        private static SUBMIT_GOAL[] allValues = values();

        public static SUBMIT_GOAL fromOrdinal(int n) {
            return allValues[n];
        }
    }

    /**
     * Test metrix goal
     */
    SUBMIT_GOAL goal = SUBMIT_GOAL.BYTES_PER_SECOND_PER_NODE;

    long bytesPerSecondGoal = 1_000_000;
    float tranPerSecondGoal = 1000;
    long eventsPerSecondGoal = 500;
    float roundsPerSecondGoal = 30;
    float tranPerEventGoal = 200;
    float c2cLatencySecond = 3;

    /**
     * track statistics to achive system goal
     */
    long accumulatedBytes = 0;

    long accumulatedTrans = 0;
    long accumulatedEvent = 0;
    long accumulatedRound = 0;

    long cycleStartMS = 0;
    /** cycle start time in millisecond unit */

    /**
     * pause submission after certain amount of transaction
     */
    long pauseAfter = 0;

    int pauseSeconds = 15;
    boolean waitPauseFinished = false;
    long pauseStartTime;
    int C2CDelayThreshold = 5;

    private SubmitConfig submitConfig;
    private Map<PAYLOAD_TYPE, Throttle> throttleForTxTypes;
    private Map<PAYLOAD_CATEGORY, Throttle> throttleForTxCategory;

    /**
     * Transient reference to the control quorum mechanism created by the {@link PlatformTestingToolState} instance.
     */
    private QuorumTriggeredAction<ControlAction> controlQuorum;

    /**
     * Constructor
     */
    TransactionSubmitter(final SubmitConfig sc, final QuorumTriggeredAction<ControlAction> controlQuorum) {

        this.submitConfig = sc;
        this.controlQuorum = controlQuorum;

        SUBMIT_GOAL goal = submitConfig.getSystemMetric();
        float value = submitConfig.getMetricThreshold();
        long pauseAfter = submitConfig.getPauseAfter();
        int pauseSeconds = submitConfig.getPauseSeconds();
        int C2CDelayThreshold = submitConfig.getC2CDelayThreshold();

        this.goal = goal;
        if (this.goal == SUBMIT_GOAL.BYTES_PER_SECOND_PER_NODE) {
            bytesPerSecondGoal = (long) value;
        } else if (this.goal == SUBMIT_GOAL.TRANS_PER_SECOND_PER_NODE) {
            tranPerSecondGoal = (float) value;
        } else if (this.goal == SUBMIT_GOAL.EVENTS_PER_SECOND_WHOLE_NETWORK) {
            eventsPerSecondGoal = (long) value;
        } else if (this.goal == SUBMIT_GOAL.ROUNDS_PER_SECOND_WHOLE_NETWORK) {
            roundsPerSecondGoal = value;
        } else if (this.goal == SUBMIT_GOAL.TRANS_PER_EVENT_WHOLE_NETWORK) {
            tranPerEventGoal = value;
        } else if (this.goal == SUBMIT_GOAL.C2C_LATENCY) {
            c2cLatencySecond = value;
        }
        logger.info(LOGM_DEMO_INFO, "Goal is " + goal + " targe " + value);
        this.pauseAfter = pauseAfter;
        this.pauseSeconds = pauseSeconds;
        this.C2CDelayThreshold = C2CDelayThreshold;

        if (submitConfig.isEnableThrottling()) {
            submitConfig.initThrottling();
            throttleForTxTypes = submitConfig.getThrottleForTxTypes();
            throttleForTxCategory = submitConfig.getThrottleForTxCategory();
        }
    }

    /*
     * if submit transaction to through platform.createTransaction
     * Use this to update statistics to control submit rate
     */
    public void udpateStatistics(int length) {
        if (accumulatedTrans == 0) {
            cycleStartMS = System.currentTimeMillis();
        }
        accumulatedTrans++;
        accumulatedBytes += length;
    }

    public void sendTransaction(Platform platform, byte[] data) {
        // send start pause message to all others, keep trying until it go through
        while (!platform.createTransaction(data))
            ;
    }

    /**
     * if submit successfully return true, other false,
     * called may retry to submit the same data next time
     */
    public boolean trySubmit(Platform platform, Pair<byte[], PAYLOAD_TYPE> data) {
        if (controlQuorum.hasQuorum(ControlAction.of(ControlType.EXIT_VALIDATION))) {
            logger.info(
                    LOGM_DEMO_INFO,
                    "Submitter has exited pause due to reaching quorum on {} messages",
                    ControlType.EXIT_VALIDATION);
            paused.set(false);

            // clear accumulatedTrans to make sure measured TPS for next interval will be accurate
            cycleStartMS = System.currentTimeMillis();
            accumulatedTrans = 0;

            return false;
        }

        if (controlQuorum.hasQuorum(ControlAction.of(ControlType.ENTER_VALIDATION))) {
            logger.info(
                    LOGM_DEMO_INFO,
                    "Submitter has entered pause due to reaching quorum on {} messages",
                    ControlType.ENTER_VALIDATION);
            paused.set(true);
            return false;
        }

        if (controlQuorum.hasQuorum(ControlAction.of(ControlType.EXIT_SYNC))) {
            logger.info(
                    LOGM_DEMO_INFO,
                    "Submitter has exited pause due to reaching quorum on {} messages",
                    ControlType.EXIT_SYNC);
            paused.set(false);

            // clear accumulatedTrans to make sure measured TPS for next interval will be accurate
            cycleStartMS = System.currentTimeMillis();
            accumulatedTrans = 0;

            return false;
        }

        if (controlQuorum.hasQuorum(ControlAction.of(ControlType.ENTER_SYNC))) {
            logger.info(
                    LOGM_DEMO_INFO,
                    "Submitter has entered pause due to reaching quorum on {} messages",
                    ControlType.ENTER_SYNC);
            paused.set(true);
            return false;
        }

        if (paused.get()) {
            return false;
        }

        // check whether we send too fast
        if (canSubmitMore(platform)) {

            if (submitConfig.isEnableThrottling()) {
                PAYLOAD_TYPE type = data.value();
                if (type != null) {
                    if (!handleThrottles(type)) {
                        return false;
                    }
                }
            }
            if (!platform.createTransaction(data.key())) {
                logger.info(
                        LOGM_SUBMIT_DETAIL,
                        "Submitter will not submit this transaction because platform failed to createTransaction");
                return false;
            } else { // update statistics
                if (accumulatedTrans == 0) {
                    cycleStartMS = System.currentTimeMillis();
                }
                accumulatedTrans++;
                accumulatedBytes += data.key().length;

                if (pauseAfter > 0 && accumulatedTrans == pauseAfter && !waitPauseFinished) {
                    waitPauseFinished = true;
                    pauseStartTime = System.currentTimeMillis() / 1000;
                    logger.info(LOGM_DEMO_INFO, "Pause started");
                }

                return true;
            }
        } else {
            logger.info(
                    LOGM_SUBMIT_DETAIL,
                    "Submitter will not submit this transaction because canSubmitMore returns false");
            return false;
        }
    }

    private boolean handleThrottles(final PAYLOAD_TYPE type) {
        MultiThrottle multiThrottle = new MultiThrottle();

        PAYLOAD_CATEGORY category = type.getPayloadCategory();

        if (throttleForTxCategory != null) {
            Throttle categoryThrottle = throttleForTxCategory.get(category);
            if (categoryThrottle != null) {
                multiThrottle.addThrottle(categoryThrottle);
            }
        }

        if (throttleForTxTypes != null) {
            Throttle typeThrottle = throttleForTxTypes.get(type);
            if (typeThrottle != null) {
                multiThrottle.addThrottle(typeThrottle);
            }
        }

        if (multiThrottle.allow()) {
            return true;
        } else {
            logger.info(
                    LOGM_SUBMIT_DETAIL,
                    "Submitter will not submit this transaction because type transaction"
                            + " throttle doesn't allow for type {} or category {}",
                    type::name,
                    category::name);
            return false;
        }
    }

    /**
     * check current statistics whether we can submit more
     */
    public boolean canSubmitMore(Platform platform) {
        boolean result = false;
        long now = System.currentTimeMillis();
        final Metrics metrics = platform.getContext().getMetrics();

        if (getForcePauseCanSubmitMore().get()) {
            // suppress transaction generation
            return false;
        }

        if (waitPauseFinished) {
            if ((System.currentTimeMillis() / 1000) > (pauseStartTime + pauseSeconds)) {
                waitPauseFinished = false;
                logger.info(LOGM_DEMO_INFO, "Pause finished");
            } else {
                logger.info(
                        LOGM_SUBMIT_DETAIL, "Submitter cannot submit the transaction because pause is not finished");
                return false;
            }
        }

        if (this.goal == SUBMIT_GOAL.BYTES_PER_SECOND_PER_NODE) {
            // measure since the start of the cycle how many bytes submitted, then div by elapsed time
            // to decide whether can submit more

            // use float instead of int or long (to support TPS < 1.0)
            // and the +1 in the denominator prevents division by 0 -> +NaN
            float realBytesPerSecond = ((float) accumulatedBytes) * SECONDS_TO_MILLISECONDS / (now - cycleStartMS + 1);
            if (realBytesPerSecond > bytesPerSecondGoal) {
                return false;
            } else {
                return true;
            }
        } else if (this.goal == SUBMIT_GOAL.TRANS_PER_SECOND_PER_NODE && this.customizedTPS != USE_DEFAULT_TPS) {
            float realTranPerSecond = ((float) accumulatedTrans) * SECONDS_TO_MILLISECONDS / (now - cycleStartMS + 1);
            if (realTranPerSecond > this.customizedTPS) {
                logger.info(
                        LOGM_SUBMIT_DETAIL,
                        "Submitter cannot submit the transaction because realTranPerSecond {} is greater than "
                                + "customizedTPS {}",
                        realTranPerSecond,
                        customizedTPS);
                return false;
            } else {
                return true;
            }
        } else if (this.goal == SUBMIT_GOAL.TRANS_PER_SECOND_PER_NODE) {
            // if the node is offline for a while due to network disruption,
            // it may try to catch up the lost transactions count by submitting
            // many transactions in short window and lead to a burst of transactions. This is not good for
            // platform, so we need to check the current TPS and make sure it is not too high.
            final double tranSubTPS = (double) metrics.getValue("Debug:info", "tranSubTPS");
            if (tranSubTPS > tranPerSecondGoal * ALLOWED_CATCHUP_DELTA) {
                return false;
            }

            float realTranPerSecond = ((float) accumulatedTrans) * SECONDS_TO_MILLISECONDS / (now - cycleStartMS + 1);
            if (realTranPerSecond > tranPerSecondGoal) {
                logger.info(
                        LOGM_SUBMIT_DETAIL,
                        "Submitter cannot submit the transaction because realTranPerSecond {} is greater than "
                                + "tranPerSecondGoal {}",
                        realTranPerSecond,
                        tranPerSecondGoal);
                return false;
            } else {
                return true;
            }

        } else if (this.goal == SUBMIT_GOAL.EVENTS_PER_SECOND_WHOLE_NETWORK) {
            double realEvensPerSecond = (double) metrics.getValue(PLATFORM_CATEGORY, "events_per_sec");
            return realEvensPerSecond > eventsPerSecondGoal;
        } else if (this.goal == SUBMIT_GOAL.ROUNDS_PER_SECOND_WHOLE_NETWORK) {
            final double realRoundsPerSecond = (double) metrics.getValue(PLATFORM_CATEGORY, "rounds_per_sec");
            return realRoundsPerSecond <= roundsPerSecondGoal;
        } else if (this.goal == SUBMIT_GOAL.TRANS_PER_EVENT_WHOLE_NETWORK) {
            final double realTranPerEvent = (double) metrics.getValue(PLATFORM_CATEGORY, "trans_per_event");
            return realTranPerEvent <= tranPerEventGoal;

        } else if (this.goal == SUBMIT_GOAL.C2C_LATENCY) {
            final double realLatency = (double) metrics.getValue(PLATFORM_CATEGORY, "secC2C");
            // if latency is large submit less transaction
            return realLatency <= c2cLatencySecond;
        }

        return result;
    }

    public boolean sendFreezeTran(Platform platform, byte[] data) {
        logger.info(LOGM_DEMO_INFO, "Sending Freeze Transaction...");
        // Try N times to successfully submit waiting X milliseconds between each attempt
        int attemptCount = 0;
        while (!platform.createTransaction(data)) {
            try {
                attemptCount++;

                if (attemptCount > FREEZE_SUBMIT_MAX_ATTEMPTS) {
                    return false;
                }

                Thread.sleep(FREEZE_SUBMIT_ATTEMPT_SLEEP);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        logger.info(LOGM_DEMO_INFO, "Finished Sending Freeze Transaction.");
        return true;
    }

    public long getCustomizedTPS() {
        return customizedTPS;
    }

    // set customized TPS, value -1 means use default TPS defined in submitConfig
    public void setCustomizedTPS(long customizedTPS) {
        boolean isTPSChanged = false;
        if (this.customizedTPS == USE_DEFAULT_TPS && this.customizedTPS != customizedTPS) {
            // detect whether it restores the default value
            logger.info(LOGM_DEMO_INFO, " set customized TPS {}", customizedTPS);
            isTPSChanged = true;
        } else if (this.customizedTPS != USE_DEFAULT_TPS && customizedTPS == USE_DEFAULT_TPS) {
            // detect whether it sets to a new non-default value
            logger.info(LOGM_DEMO_INFO, " clear customized TPS to default");
            isTPSChanged = true;
        }
        if (isTPSChanged) {
            cycleStartMS = System.currentTimeMillis();
            accumulatedTrans = 0;
        }
        this.customizedTPS = customizedTPS;
    }

    public SubmitConfig getSubmitConfig() {
        return submitConfig;
    }
}
