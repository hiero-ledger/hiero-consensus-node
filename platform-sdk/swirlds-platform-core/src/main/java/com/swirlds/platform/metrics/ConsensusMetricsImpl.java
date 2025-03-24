// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.metrics;

import static com.swirlds.base.units.UnitConstants.NANOSECONDS_TO_SECONDS;
import static com.swirlds.metrics.api.FloatFormats.FORMAT_10_0;
import static com.swirlds.metrics.api.FloatFormats.FORMAT_10_3;
import static com.swirlds.metrics.api.FloatFormats.FORMAT_4_2;
import static com.swirlds.metrics.api.FloatFormats.FORMAT_9_6;
import static com.swirlds.metrics.api.Metrics.INTERNAL_CATEGORY;
import static com.swirlds.metrics.api.Metrics.PLATFORM_CATEGORY;

import com.swirlds.common.metrics.RunningAverageMetric;
import com.swirlds.common.metrics.SpeedometerMetric;
import com.swirlds.metrics.api.Counter;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.platform.internal.EventImpl;
import com.swirlds.platform.stats.AverageAndMax;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import org.hiero.consensus.model.node.NodeId;

/**
 * Collection of metrics related to consensus
 */
public class ConsensusMetricsImpl implements ConsensusMetrics {

    private static final RunningAverageMetric.Config AVG_JUDGES_WEIGHT_CONFIG = new RunningAverageMetric.Config(
                    PLATFORM_CATEGORY, "judgesWeight")
            .withDescription("weight of judges of rounds")
            .withFormat(FORMAT_10_3);
    private final RunningAverageMetric avgJudgesWeight;
    private static final RunningAverageMetric.Config AVG_FIRST_EVENT_IN_ROUND_RECEIVED_TIME_CONFIG =
            new RunningAverageMetric.Config(PLATFORM_CATEGORY, "secR2nR")
                    .withDescription("time from first event received in one round, to first event received in the "
                            + "next round (in seconds)")
                    .withFormat(FORMAT_10_3);
    private final RunningAverageMetric avgFirstEventInRoundReceivedTime;

    private static final RunningAverageMetric.Config NUM_COIN_ROUNDS_CONFIG = new RunningAverageMetric.Config(
                    INTERNAL_CATEGORY, "coinR")
            .withDescription("number of coin rounds that have occurred so far")
            .withFormat(FORMAT_10_0);
    private final RunningAverageMetric numCoinRounds;

    private static final RunningAverageMetric.Config AVG_RECEIVED_FAMOUS_TIME_CONFIG = new RunningAverageMetric.Config(
                    PLATFORM_CATEGORY, "secR2F")
            .withDescription(
                    "time from a round's first received event to all the famous witnesses being known (in seconds)")
            .withFormat(FORMAT_10_3);
    private final RunningAverageMetric avgReceivedFamousTime;

    private static final SpeedometerMetric.Config ROUNDS_PER_SECOND_CONFIG = new SpeedometerMetric.Config(
                    PLATFORM_CATEGORY, "rounds_per_sec")
            .withDescription("average number of rounds per second");
    private final SpeedometerMetric roundsPerSecond;

    private static final RunningAverageMetric.Config AVG_CREATED_CONSENSUS_TIME_CONFIG =
            new RunningAverageMetric.Config(PLATFORM_CATEGORY, "secC2C")
                    .withDescription("time from creating an event to knowing its consensus (in seconds)")
                    .withFormat(FORMAT_10_3);
    private final RunningAverageMetric avgCreatedConsensusTime;

    private static final RunningAverageMetric.Config AVG_RECEIVED_CONSENSUS_TIME_CONFIG =
            new RunningAverageMetric.Config(PLATFORM_CATEGORY, "secR2C")
                    .withDescription("time from receiving an event to knowing its consensus (in seconds)")
                    .withFormat(FORMAT_10_3);
    private final RunningAverageMetric avgReceivedConsensusTime;

    private static final RunningAverageMetric.Config AVG_CREATED_RECEIVED_CONSENSUS_TIME_CONFIG =
            new RunningAverageMetric.Config(PLATFORM_CATEGORY, "secC2RC")
                    .withDescription("time from another member creating an event to it being received and knowing "
                            + "consensus for it (in seconds)")
                    .withFormat(FORMAT_10_3);
    private final RunningAverageMetric avgCreatedReceivedConsensusTime;

    private static final RunningAverageMetric.Config AVG_SELF_CREATED_TIMESTAMP_CONFIG =
            new RunningAverageMetric.Config(INTERNAL_CATEGORY, "secSC2T")
                    .withDescription("self event consensus timestamp minus time created (in seconds)")
                    .withFormat(FORMAT_10_3);
    private final RunningAverageMetric avgSelfCreatedTimestamp;

    private static final RunningAverageMetric.Config AVG_OTHER_RECEIVED_TIMESTAMP_CONFIG =
            new RunningAverageMetric.Config(INTERNAL_CATEGORY, "secOR2T")
                    .withDescription("other event consensus timestamp minus time received (in seconds)")
                    .withFormat(FORMAT_10_3);
    private final RunningAverageMetric avgOtherReceivedTimestamp;

    private static final SpeedometerMetric.Config TIME_FRAC_DOT_CONFIG = new SpeedometerMetric.Config(
                    INTERNAL_CATEGORY, "timeFracDot")
            .withDescription("fraction of each second spent on dot products")
            .withFormat(FORMAT_9_6);
    private final SpeedometerMetric timeFracDot;

    private final AverageAndMax witnessesSeen;

    private static final Counter.Config ROUND_INCREMENT_STRONGLY_SEEN_CONFIG =
            new Counter.Config(INTERNAL_CATEGORY, "roundIncEqParents");

    private final Counter roundIncrementedByStronglySeen;

    private final NodeId selfId;

    /**
     * Time when this platform received the first event created by someone else in the most recent round. This is used
     * to calculate Statistics.avgFirstEventInRoundReceivedTime which is "time for event, from receiving the first event
     * in a round to the first event in the next round".
     */
    private static volatile Instant firstEventInLastRoundTime = null;
    /**
     * the max round number for which at least one event is known that was created by someone else
     */
    private static volatile long lastRoundNumber = -1;

    /**
     * Constructor of {@code ConsensusMetricsImpl}
     *
     * @param selfId  the {@link NodeId} of this node
     * @param metrics a reference to the metrics-system
     * @throws NullPointerException if any of the following parameters are {@code null}.
     *     <ul>
     *       <li>{@code selfId}</li>
     *       <li>{@code metrics}</li>
     *     </ul>
     */
    public ConsensusMetricsImpl(final NodeId selfId, final Metrics metrics) {
        this.selfId = Objects.requireNonNull(selfId, "selfId must not be null");
        Objects.requireNonNull(metrics, "metrics must not be null");

        avgJudgesWeight = metrics.getOrCreate(AVG_JUDGES_WEIGHT_CONFIG);
        avgFirstEventInRoundReceivedTime = metrics.getOrCreate(AVG_FIRST_EVENT_IN_ROUND_RECEIVED_TIME_CONFIG);
        numCoinRounds = metrics.getOrCreate(NUM_COIN_ROUNDS_CONFIG);
        avgReceivedFamousTime = metrics.getOrCreate(AVG_RECEIVED_FAMOUS_TIME_CONFIG);
        roundsPerSecond = metrics.getOrCreate(ROUNDS_PER_SECOND_CONFIG);
        avgCreatedConsensusTime = metrics.getOrCreate(AVG_CREATED_CONSENSUS_TIME_CONFIG);
        avgReceivedConsensusTime = metrics.getOrCreate(AVG_RECEIVED_CONSENSUS_TIME_CONFIG);
        avgCreatedReceivedConsensusTime = metrics.getOrCreate(AVG_CREATED_RECEIVED_CONSENSUS_TIME_CONFIG);
        avgSelfCreatedTimestamp = metrics.getOrCreate(AVG_SELF_CREATED_TIMESTAMP_CONFIG);
        avgOtherReceivedTimestamp = metrics.getOrCreate(AVG_OTHER_RECEIVED_TIMESTAMP_CONFIG);
        timeFracDot = metrics.getOrCreate(TIME_FRAC_DOT_CONFIG);
        witnessesSeen = new AverageAndMax(
                metrics,
                INTERNAL_CATEGORY,
                "witnessesSeen",
                "number of witnesses seen by an event added to the hashgraph when both parents have the same round "
                        + "created",
                FORMAT_4_2);
        roundIncrementedByStronglySeen = metrics.getOrCreate(ROUND_INCREMENT_STRONGLY_SEEN_CONFIG);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void addedEvent(final EventImpl event) {
        // this method is only ever called by 1 thread, so no need for locks
        if (!Objects.equals(selfId, event.getCreatorId())
                && event.getRoundCreated() > lastRoundNumber) { // if first event in a round
            final Instant now = Instant.now();
            if (firstEventInLastRoundTime != null) {
                avgFirstEventInRoundReceivedTime.update(
                        firstEventInLastRoundTime.until(now, ChronoUnit.NANOS) * NANOSECONDS_TO_SECONDS);
            }
            firstEventInLastRoundTime = now;
            lastRoundNumber = event.getRoundCreated();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void coinRound() {
        this.numCoinRounds.update(1);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void lastFamousInRound(final EventImpl event) {
        if (selfId.id() != event.getCreatorId().id()) { // record this for events received
            avgReceivedFamousTime.update(event.getBaseEvent().getTimeReceived().until(Instant.now(), ChronoUnit.NANOS)
                    * NANOSECONDS_TO_SECONDS);
        }
    }

    // this might not need to be a separate method
    // we could just update the stats in consensusReached(EventImpl event) when event.lastInRoundReceived()==true

    /**
     * {@inheritDoc}
     */
    @Override
    public void consensusReachedOnRound() {
        roundsPerSecond.cycle();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void consensusReached(final EventImpl event) {
        // Keep a running average of how many seconds from when I first know of an event
        // until it achieves consensus. Actually, keep two such averages: one for events I
        // create, and one for events I receive.
        if (Objects.equals(selfId, event.getCreatorId())) {
            avgCreatedConsensusTime.update(
                    event.getBaseEvent().getTimeReceived().until(Instant.now(), ChronoUnit.NANOS)
                            * NANOSECONDS_TO_SECONDS);
        } else {
            avgReceivedConsensusTime.update(
                    event.getBaseEvent().getTimeReceived().until(Instant.now(), ChronoUnit.NANOS)
                            * NANOSECONDS_TO_SECONDS);
            avgCreatedReceivedConsensusTime.update(
                    event.getTimeCreated().until(Instant.now(), ChronoUnit.NANOS) * NANOSECONDS_TO_SECONDS);
        }
        if (Objects.equals(selfId, event.getCreatorId())) {
            avgSelfCreatedTimestamp.update(event.getTimeCreated().until(event.getConsensusTimestamp(), ChronoUnit.NANOS)
                    * NANOSECONDS_TO_SECONDS);
        } else {
            avgOtherReceivedTimestamp.update(
                    event.getBaseEvent().getTimeReceived().until(event.getConsensusTimestamp(), ChronoUnit.NANOS)
                            * NANOSECONDS_TO_SECONDS);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void dotProductTime(final long nanoTime) {
        timeFracDot.update(nanoTime * NANOSECONDS_TO_SECONDS);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public double getAvgSelfCreatedTimestamp() {
        return avgSelfCreatedTimestamp.get();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public double getAvgOtherReceivedTimestamp() {
        return avgOtherReceivedTimestamp.get();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void judgeWeights(long weight) {
        avgJudgesWeight.update(weight);
    }

    @Override
    public void witnessesStronglySeen(final int numSeen) {
        witnessesSeen.update(numSeen);
    }

    @Override
    public void roundIncrementedByStronglySeen() {
        roundIncrementedByStronglySeen.increment();
    }
}
