// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.event.preconsensus.durability;

import static com.swirlds.common.units.TimeUnit.UNIT_MICROSECONDS;
import static com.swirlds.common.units.TimeUnit.UNIT_NANOSECONDS;
import static com.swirlds.common.utility.CompareTo.isGreaterThan;
import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;

import com.swirlds.base.time.Time;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.metrics.RunningAverageMetric;
import com.swirlds.common.utility.throttle.RateLimitedLogger;
import com.swirlds.metrics.api.LongGauge;
import com.swirlds.platform.event.preconsensus.PcesConfig;
import com.swirlds.platform.internal.ConsensusRound;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * A default implementation of {@link RoundDurabilityBuffer}.
 */
public class DefaultRoundDurabilityBuffer implements RoundDurabilityBuffer {

    private static final Logger logger = LogManager.getLogger(DefaultRoundDurabilityBuffer.class);

    private long durableSequenceNumber = -1;
    private final Queue<NotYetDurableRound> rounds = new LinkedList<>();

    private final Time time;
    private final Duration suspiciousRoundDuration;

    private final RateLimitedLogger suspiciousRoundLogger;

    private static final RunningAverageMetric.Config AVERAGE_ROUND_DURABILITY_DELAY_METRIC_CONFIG =
            new RunningAverageMetric.Config("platform", "averageRoundDurabilityDelay")
                    .withUnit("us")
                    .withDescription(
                            "The average delay between a round being eligible for handling and when the round's keystone event is guaranteed to be durable.");
    private final RunningAverageMetric averageRoundDurabilityDelayMetric;

    private static final LongGauge.Config ROUND_DURABILITY_BUFFER_SIZE_METRIC_CONFIG = new LongGauge.Config(
                    "platform", "roundDurabilityBufferSize")
            .withUnit("count")
            .withDescription("The number of rounds that are waiting for their keystone event to become durable");
    private final LongGauge roundDurabilityBufferSizeMetric;

    /**
     * Constructor.
     *
     * @param platformContext the platform context
     */
    public DefaultRoundDurabilityBuffer(@NonNull final PlatformContext platformContext) {
        this.time = platformContext.getTime();
        suspiciousRoundDuration = platformContext
                .getConfiguration()
                .getConfigData(PcesConfig.class)
                .suspiciousRoundDurabilityDuration();

        suspiciousRoundLogger = new RateLimitedLogger(logger, time, Duration.ofMinutes(10));

        averageRoundDurabilityDelayMetric =
                platformContext.getMetrics().getOrCreate(AVERAGE_ROUND_DURABILITY_DELAY_METRIC_CONFIG);
        roundDurabilityBufferSizeMetric =
                platformContext.getMetrics().getOrCreate(ROUND_DURABILITY_BUFFER_SIZE_METRIC_CONFIG);
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public List<ConsensusRound> setLatestDurableSequenceNumber(@NonNull final Long durableSequenceNumber) {
        if (durableSequenceNumber < this.durableSequenceNumber) {
            throw new IllegalArgumentException(
                    "The durable sequence number cannot be less than the current durable sequence number. "
                            + "Current sequence number: " + this.durableSequenceNumber + ", requested sequence number: "
                            + durableSequenceNumber + ".");
        }
        this.durableSequenceNumber = durableSequenceNumber;

        final List<ConsensusRound> durableRounds = new ArrayList<>();

        final Instant now = time.now();
        while (!rounds.isEmpty() && getKeystoneSequence(rounds.peek().round()) <= durableSequenceNumber) {
            final NotYetDurableRound round = rounds.remove();
            final Duration delay = Duration.between(round.receivedTime(), now);
            averageRoundDurabilityDelayMetric.update(UNIT_NANOSECONDS.convertTo(delay.toNanos(), UNIT_MICROSECONDS));
            durableRounds.add(round.round());
        }

        roundDurabilityBufferSizeMetric.set(rounds.size());
        return durableRounds;
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public List<ConsensusRound> addRound(@NonNull final ConsensusRound round) {
        if (rounds.isEmpty() && getKeystoneSequence(round) <= durableSequenceNumber) {
            averageRoundDurabilityDelayMetric.update(0);
            return List.of(round);
        }

        rounds.add(new NotYetDurableRound(round, time.now()));
        roundDurabilityBufferSizeMetric.set(rounds.size());
        return List.of();
    }

    /**
     * Get the keystone sequence number of the given consensus round.
     *
     * @param consensusRound the consensus round
     * @return the keystone sequence number
     */
    private static long getKeystoneSequence(@NonNull final ConsensusRound consensusRound) {
        return consensusRound.getKeystoneEvent().getStreamSequenceNumber();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void clear() {
        durableSequenceNumber = -1;
        rounds.clear();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void checkForStaleRounds(@NonNull final Instant now) {
        for (final NotYetDurableRound round : rounds) {
            final Duration duration = Duration.between(round.receivedTime(), now);
            if (isGreaterThan(duration, suspiciousRoundDuration)) {
                suspiciousRoundLogger.error(
                        EXCEPTION.getMarker(),
                        "Round " + round.round().getRoundNum()
                                + " has been waiting for its keystone event to become durable for " + duration
                                + ". System may be deadlocked.");
            }
        }
    }
}
