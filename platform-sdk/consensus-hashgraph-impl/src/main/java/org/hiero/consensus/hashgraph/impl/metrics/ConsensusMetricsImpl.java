// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.hashgraph.impl.metrics;

import static com.swirlds.base.units.UnitConstants.NANOSECONDS_TO_SECONDS;
import static com.swirlds.metrics.api.FloatFormats.FORMAT_10_0;
import static com.swirlds.metrics.api.FloatFormats.FORMAT_10_3;
import static com.swirlds.metrics.api.FloatFormats.FORMAT_4_2;
import static com.swirlds.metrics.api.FloatFormats.FORMAT_9_6;
import static com.swirlds.metrics.api.Metrics.INTERNAL_CATEGORY;
import static com.swirlds.metrics.api.Metrics.PLATFORM_CATEGORY;
import static java.util.Objects.requireNonNull;

import com.swirlds.base.time.Time;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Counter;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import org.hiero.consensus.hashgraph.impl.EventImpl;
import org.hiero.consensus.metrics.RunningAverageMetric;
import org.hiero.consensus.metrics.SpeedometerMetric;
import org.hiero.consensus.metrics.config.ConsensusLayerMetricsConfig;
import org.hiero.consensus.metrics.extensions.LatencyGauges;
import org.hiero.consensus.metrics.extensions.LatencyGauges.Config;
import org.hiero.consensus.metrics.statistics.AverageAndMax;
import org.hiero.consensus.model.event.EventOrigin;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.hiero.consensus.model.node.NodeId;

/**
 * Collection of metrics related to consensus
 */
public class ConsensusMetricsImpl implements ConsensusMetrics {

    private static final Config EVENT_LATENCY_CONFIG = new Config(PLATFORM_CATEGORY, "eventLatency_R2C")
            .withDescription("time from receiving a gossiped event until the consensus engine emits its round");
    private static final Config TRANSACTION_LATENCY_CONFIG = new Config(PLATFORM_CATEGORY, "transactionLatency_total")
            .withDescription("time from a transaction entering the transaction pool until the consensus engine emits "
                    + "its round measured only on the node that received the transaction");
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

    private final AverageAndMax numVotingRounds;
    private final AverageAndMax numEventsAddedUntilConsensus;
    private final AverageAndMax graphDepthUntilConsensus;

    /**
     * Percentiles and the maximum of the time from receiving a gossiped event until the consensus engine emits its
     * round, or {@code null} if latency gauges are disabled.
     */
    @Nullable
    private final LatencyGauges receivedToConsensus;

    /**
     * Percentiles and the maximum of the time from a transaction without priority entering the transaction pool of
     * this node until the consensus engine emits its round, or {@code null} if latency gauges are disabled.
     */
    @Nullable
    private final LatencyGauges transactionToConsensus;

    private final NodeId selfId;
    private final Time time;

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

    private final Sequencer sequencer = new Sequencer();

    /**
     * Constructor of {@code ConsensusMetricsImpl}
     *
     * @param selfId        the {@link NodeId} of this node
     * @param metrics       a reference to the metrics-system
     * @param configuration the configuration, which enables the latency gauges and sets their windows and percentiles
     * @param time          the clock that measures the latency and advances the window of its gauges
     * @throws NullPointerException if any of the parameters is {@code null}.
     */
    public ConsensusMetricsImpl(
            @NonNull final NodeId selfId,
            @NonNull final Metrics metrics,
            @NonNull final Configuration configuration,
            @NonNull final Time time) {
        this.selfId = requireNonNull(selfId, "selfId must not be null");
        requireNonNull(metrics, "metrics must not be null");
        this.time = requireNonNull(time, "time must not be null");

        final ConsensusLayerMetricsConfig metricsConfig = requireNonNull(
                        configuration, "configuration must not be null")
                .getConfigData(ConsensusLayerMetricsConfig.class);
        if (metricsConfig.latencyEnabled()) {
            final Config eventLatencyR2C = EVENT_LATENCY_CONFIG
                    .withWindow(metricsConfig.latencyWindow())
                    .withPercentiles(metricsConfig.latencyPercentiles());
            receivedToConsensus = LatencyGauges.register(metrics, configuration, time, eventLatencyR2C);
            final Config transactionLatencyTotal = TRANSACTION_LATENCY_CONFIG
                    .withWindow(metricsConfig.transactionLatencyWindow())
                    .withPercentiles(metricsConfig.transactionLatencyPercentiles());
            transactionToConsensus = LatencyGauges.register(metrics, configuration, time, transactionLatencyTotal);
        } else {
            receivedToConsensus = null;
            transactionToConsensus = null;
        }

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

        numVotingRounds = new AverageAndMax(
                metrics,
                INTERNAL_CATEGORY,
                "numVotingRounds",
                "number of voting rounds until consensus is reached",
                FORMAT_10_3);
        numEventsAddedUntilConsensus = new AverageAndMax(
                metrics,
                INTERNAL_CATEGORY,
                "numEventsAddedUntilConsensus",
                "after adding event E, how many events are added before E reaches consensus",
                FORMAT_10_3);
        graphDepthUntilConsensus = new AverageAndMax(
                metrics,
                INTERNAL_CATEGORY,
                "graphDepthUntilConsensus",
                "depth of the graph needed to reach consensus (in generations)",
                FORMAT_10_3);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void addedEvent(final EventImpl event) {
        // this method is only ever called by 1 thread, so no need for locks
        sequencer.assignSequenceNumber(event);
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
    public void consensusReachedOnRound(final long round) {
        roundsPerSecond.cycle();
        final EventImpl lastEventAdded = sequencer.getLastEventAdded();
        if (lastEventAdded != null) {
            // we have reached consensus for a round, so the last event that we added decided fame
            // the difference between the round number of the event that decided fame and the consensus round gives us
            // the number of voting rounds that were needed to reach consensus for this round
            numVotingRounds.update(lastEventAdded.getRoundCreated() - round);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void consensusReached(final EventImpl event) {
        final EventImpl lastEventAdded = sequencer.getLastEventAdded();
        if (lastEventAdded != null) {
            // this should never be null
            // but if there is a bug, its better to not have metrics than to throw an NPE
            numEventsAddedUntilConsensus.update(lastEventAdded.getConsensusSequence() - event.getConsensusSequence());
            graphDepthUntilConsensus.update(lastEventAdded.getDeGen() - event.getDeGen());
        }
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
     *
     * <p>Records the time from receiving each gossiped event of the round until now. Events created by this node and
     * events read from storage are left out.
     *
     * <p>Also records the time from each transaction without priority entering the transaction pool until now, for
     * the transactions of the events this node created in this run; see
     * {@link PlatformEvent#getTransactionReceivedTimes()}.
     */
    @Override
    public void consensusRoundEmitted(@NonNull final ConsensusRound round) {
        if (receivedToConsensus == null || transactionToConsensus == null) {
            return;
        }
        final Instant now = time.now();
        for (final PlatformEvent event : round.getConsensusEvents()) {
            if (event.getOrigin() == EventOrigin.GOSSIP) {
                receivedToConsensus.record(
                        Duration.between(event.getTimeReceived(), now).toNanos());
            }
            for (final Instant transactionReceived : event.getTransactionReceivedTimes()) {
                transactionToConsensus.record(
                        Duration.between(transactionReceived, now).toNanos());
            }
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
    public void judgeWeights(final long weight) {
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
